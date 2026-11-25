package app.buddy.assistant;

import android.app.Activity;
import android.app.Dialog;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.format.DateUtils;
import android.view.Gravity;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static app.buddy.assistant.Ui.*;

/**
 * "What is it doing" (issue #81): the command a chat's agent runs, for how long, and the processes under the agent in
 * Buddy's Linux (buddy-run cmdprocs), refreshed every 2 s while the sheet is open. Its output isn't shown: Buddy only
 * gets that from the agent once the command ends, and it isn't guessed from files. Stop ends one process and what it
 * started, never the agent (buddy-run cmdkill checks that).
 */
final class CommandSheet {
    private static final long REFRESH_MS = 2000, TICK_MS = 500;
    private static final Pattern ROW = Pattern.compile("^\\s*(\\d+)\\s+(\\d+)\\s+(\\d+)\\s+(\\d+)\\s(.*)$");
    private static final Pattern SHELL = Pattern.compile("^(\\S*/)?(ba|z|da|a)?sh\\s.*");
    /**
     * Claude Code's shell around a command, as ps shows it (measured, 2.1): bash -c "source …/shell-snapshots/… &&
     * … && eval '<the command>' < /dev/null && pwd -P >| /tmp/claude-…-cwd". The command is what the user reads.
     */
    private static final Pattern WRAPPED = Pattern.compile("&& eval (.+) < /dev/null && pwd -P >\\| \\S+\\s*$", Pattern.DOTALL);

    /** One process under the agent. */
    static final class Proc {
        long pid, ppid, seconds, cpu;
        String args;
        int depth;
    }

    private final Activity a;
    private final Sessions.Session ses;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LinearLayout body;
    private Dialog dialog;
    private boolean open, busy, synced;
    /** The last listing; null until the first one came. */
    private List<Proc> procs;
    private long agentPid;
    /** When that listing was asked for (elapsedRealtime): one from before a command began says nothing of it. */
    private long procsAt;
    /** What the sheet shows now, and its line that counts the seconds: redrawn when the chat's state moves on. */
    private String drawn;
    private TextView howText;
    private Runnable how;
    /** What a Stop said when it failed (its own words), or null. */
    private String error;

    private CommandSheet(Activity a, Sessions.Session ses) {
        this.a = a;
        this.ses = ses;
        body = column(a);
    }

    /** Opens the sheet over the chat; dismiss() on the returned dialog closes it and ends the refreshing. */
    static Dialog show(Activity a, Sessions.Session ses) {
        CommandSheet s = new CommandSheet(a, ses);
        LinearLayout c = protect(column(a)); // its Stop is the user's own
        c.addView(title(a, a.getString(R.string.cmd_sheet_title), 18));
        c.addView(s.body, full());
        Button close = button(a, a.getString(R.string.bubble_close), false);
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(a, 48));
        cp.topMargin = dp(a, 16);
        c.addView(close, cp);
        s.dialog = bottomSheet(a, c);
        close.setOnClickListener(v -> s.dialog.dismiss());
        s.dialog.setOnDismissListener(d -> {
            s.open = false;
            s.main.removeCallbacksAndMessages(null);
        });
        s.open = true;
        s.render();
        s.dialog.show();
        s.fetch();
        s.main.postDelayed(s::tick, TICK_MS);
        return s.dialog;
    }

    /**
     * Between listings the chat moves on (a command begins or ends, the run ends): the sheet follows it at once, not
     * 2 s later with a list from before. The seconds count on their own line, so the Stop buttons stay as they are.
     */
    private void tick() {
        if (!open) return;
        if (!shape().equals(drawn)) render();
        else if (how != null) how.run();
        main.postDelayed(this::tick, TICK_MS);
    }

    private String shape() {
        return ses.running + "|" + (ses.running ? ses.cmd : null) + "|" + ses.cmdAt + "|" + procsAt;
    }

    // ------------------------------------------------------------------ listing

    private void fetch() {
        if (!open || busy) return;
        if (!ses.running) {
            procs = new ArrayList<>();
            render();
            return; // nothing more to watch
        }
        busy = true;
        final long asked = SystemClock.elapsedRealtime();
        new Thread(() -> {
            String out;
            try {
                if (!synced) Helper.sync(a); // a run older than this Buddy's process may have an older helper on disk
                synced = true;
                out = Linux.exec(a, "bash /root/bin/buddy-run - cmdprocs " + ses.id, 20);
            } catch (Exception e) {
                out = String.valueOf(e.getMessage());
            }
            final String text = out;
            main.post(() -> {
                busy = false;
                if (!open) return;
                parse(text);
                procsAt = asked;
                render();
                main.postDelayed(this::fetch, REFRESH_MS); // two seconds after this one came back
            });
        }, "cmd-sheet").start();
    }

    private void parse(String out) {
        long[] agent = new long[1];
        procs = list(out, agent);
        agentPid = agent[0];
    }

    /** buddy-run cmdprocs' answer as the processes under the agent, parents first; agent[0] gets the agent's pid (0: none). */
    static List<Proc> list(String out, long[] agent) {
        List<Proc> all = new ArrayList<>();
        agent[0] = 0;
        for (String line : out.split("\n")) {
            if (line.startsWith("agent ")) {
                try {
                    agent[0] = Long.parseLong(line.substring(6).trim());
                } catch (NumberFormatException ignored) {
                }
                continue;
            }
            Matcher m = ROW.matcher(line);
            if (!m.matches()) continue;
            Proc p = new Proc();
            p.pid = Long.parseLong(m.group(1));
            p.ppid = Long.parseLong(m.group(2));
            p.seconds = Long.parseLong(m.group(3));
            p.cpu = Long.parseLong(m.group(4));
            p.args = m.group(5).trim();
            all.add(p);
        }
        // parents first, each one's children under it (ps lists by pid)
        Map<Long, List<Proc>> kids = new HashMap<>();
        for (Proc p : all) {
            List<Proc> k = kids.get(p.ppid);
            if (k == null) kids.put(p.ppid, k = new ArrayList<>());
            k.add(p);
        }
        List<Proc> ordered = new ArrayList<>();
        walk(kids, agent[0], 0, ordered);
        return ordered;
    }

    /**
     * The list as it is shown: without the process that only repeats the line above it (Claude Code's shell reads as
     * its command, "sleep 60", and the sleep it started is the same words again).
     */
    static List<Proc> listed(List<Proc> procs) {
        Map<Long, Proc> byPid = new HashMap<>();
        for (Proc p : procs) byPid.put(p.pid, p);
        List<Proc> out = new ArrayList<>();
        for (Proc p : procs) {
            Proc parent = byPid.get(p.ppid);
            if (parent != null && !parent.args.equals(shown(parent.args)) && norm(shown(parent.args)).equals(norm(p.args))) continue;
            out.add(p);
        }
        return out;
    }

    /** The agent's own shell among them, the newest: what it runs now when the chat hasn't said so (yet). */
    static Proc shell(List<Proc> procs) {
        Proc best = null;
        if (procs != null) for (Proc p : procs) {
            if (p.depth == 0 && SHELL.matcher(p.args).matches() && (best == null || p.seconds < best.seconds)) best = p;
        }
        return best;
    }

    /**
     * The same for the page other devices open (WebDevices): the command, for how long, and the processes. Asks
     * Buddy's Linux, so never on the main thread.
     */
    static org.json.JSONObject snapshot(android.content.Context c, Sessions.Session ses) throws Exception {
        org.json.JSONObject o = new org.json.JSONObject().put("running", ses.running);
        if (!ses.running || !Linux.installed(c)) return o;
        long[] agent = new long[1];
        List<Proc> procs = list(Linux.exec(c, "bash /root/bin/buddy-run - cmdprocs " + ses.id, 20), agent);
        String cmd = ses.cmd;
        Proc sh = cmd == null ? shell(procs) : null;
        if (cmd != null) o.put("cmd", cmd).put("since", (SystemClock.elapsedRealtime() - ses.cmdAt) / 1000);
        else if (sh != null) o.put("cmd", shown(sh.args)).put("since", sh.seconds);
        org.json.JSONArray a = new org.json.JSONArray();
        for (Proc p : listed(procs)) a.put(new org.json.JSONObject().put("pid", p.pid).put("depth", p.depth)
                .put("seconds", p.seconds).put("cpu", p.cpu).put("args", ChatHub.clip(shown(p.args), 600)));
        return o.put("agent", agent[0] != 0).put("procs", a);
    }

    /** Stops one process under the chat's agent (never the agent): "" when done, else the helper's own words. */
    static String stop(android.content.Context c, Sessions.Session ses, long pid) {
        String out;
        try {
            out = Linux.exec(c, "bash /root/bin/buddy-run - cmdkill " + ses.id + " " + pid, 20).trim();
        } catch (Exception e) {
            out = String.valueOf(e.getMessage());
        }
        return out.endsWith("[exit 0]") ? "" : out.replaceAll("\\s*\\[(exit \\d+|timed out[^\\]]*)\\]$", "");
    }

    private static void walk(Map<Long, List<Proc>> kids, long pid, int depth, List<Proc> out) {
        List<Proc> k = kids.get(pid);
        if (k == null || depth > 30) return;
        for (Proc p : k) {
            if (ENGINE.matcher(p.args).find()) { // the agent itself, not something it started: its children in its place
                walk(kids, p.pid, depth, out);
                continue;
            }
            p.depth = depth;
            out.add(p);
            walk(kids, p.pid, depth + 1, out);
        }
    }

    /**
     * An agent's own parts that run as processes of their own (OpenCode 2's engine under its command, and the
     * terminal service it runs commands in): the agent itself, not what it started. Not listed, and no Stop of its
     * own (that would end the agent's run; the chat's Stop does that).
     */
    static final Pattern ENGINE = Pattern.compile("(^|/)opencode(\\.exe)? serve( |$)|(^|/)opencode-pty daemon( |$)");

    /**
     * The process that runs the chat's command: one started since the step whose command line holds the command's
     * start (the agent's shell wraps it: bash -c "… eval '…'"), else a shell the agent started since then. Never an
     * older one (an "ls" would match a server started earlier). Null when none fits: then only the list shows, and
     * each process the agent started has its own Stop.
     */
    private Proc command(String cmd, long since) {
        if (cmd == null || procs == null) return null;
        String key = norm(cmd.trim().split("\n", 2)[0]);
        if (key.length() > 40) key = key.substring(0, 40);
        Proc best = null;
        if (!key.isEmpty()) {
            for (Proc p : procs) {
                if (p.seconds > since + 3 || !norm(p.args).contains(key)) continue;
                if (best == null || p.depth < best.depth || (p.depth == best.depth && p.seconds < best.seconds)) best = p;
            }
        }
        if (best != null) return best;
        for (Proc p : procs) {
            if (p.depth != 0 || p.seconds > since + 3 || !SHELL.matcher(p.args).matches()) continue;
            if (best == null || p.seconds < best.seconds) best = p;
        }
        return best;
    }

    /** A process's command line as the user reads it: the command inside Claude Code's shell, else as it is. */
    static String shown(String args) {
        if (!args.contains("/shell-snapshots/")) return args;
        Matcher m = WRAPPED.matcher(args);
        if (!m.find()) return args;
        String c = m.group(1).trim();
        // (it is quoted for eval: '…', a ' inside as '"'"')
        if (c.length() >= 2 && c.startsWith("'") && c.endsWith("'")) {
            c = c.substring(1, c.length() - 1).replace("'\"'\"'", "'").replace("'\\''", "'");
        }
        return c;
    }

    private static String norm(String s) {
        return s.replaceAll("[\"'\\\\]", "").replaceAll("\\s+", " ").trim();
    }

    // ------------------------------------------------------------------ view

    private void render() {
        body.removeAllViews();
        drawn = shape();
        how = null;
        final String cmd = ses.running ? ses.cmd : null;
        long since = cmd == null ? 0 : (SystemClock.elapsedRealtime() - ses.cmdAt) / 1000;
        Proc found = command(cmd, since);
        // the chat hears of a command a moment after it began (and of its end a moment after): what the agent's
        // own shell runs is the command too
        final Proc running = found != null ? found : cmd == null && ses.running ? shell(procs) : null;
        String show = cmd != null ? cmd : running != null ? shown(running.args) : null;
        if (show != null) {
            body.addView(label(R.string.cmd_sheet_command), gap(12));
            TextView code = codeBlock(a, show);
            code.setTextIsSelectable(true);
            body.addView(code, gap(6));
            howText = text(a, "", 14, TEXT);
            body.addView(howText, gap(8));
            final long cmdAt = ses.cmdAt, listedAt = procsAt, listedFor = running == null ? 0 : running.seconds;
            // "may have ended" only from a listing made well after the command began: an older one can't know it
            final boolean gone = running == null && procs != null && agentPid != 0 && listedAt > cmdAt + 3000;
            how = () -> {
                long now = SystemClock.elapsedRealtime();
                long s = running != null ? listedFor + (now - listedAt) / 1000 : (now - cmdAt) / 1000;
                howText.setText(a.getString(gone ? R.string.cmd_sheet_started : R.string.cmd_sheet_running_for,
                        DateUtils.formatElapsedTime(Math.max(0, s))));
            };
            how.run();
        } else if (!ses.running || procs != null) { // (while the first listing is on its way, only "Looking…" below)
            body.addView(text(a, a.getString(ses.running ? R.string.cmd_sheet_no_command : R.string.cmd_sheet_ended,
                    ses.brainName()), 14, TEXT2), gap(8));
        }
        if (running != null) {
            Button stop = button(a, a.getString(R.string.cmd_sheet_stop), false);
            stop.setTextColor(ERR);
            stop.setBackground(rounded(a, ERR_SOFT, 12));
            final long pid = running.pid;
            stop.setOnClickListener(v -> kill(pid));
            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(a, 44));
            sp.topMargin = dp(a, 10);
            body.addView(stop, sp);
        }
        if (error != null) body.addView(text(a, error, 13, ERR), gap(8));
        if (!ses.running) return;
        body.addView(label(R.string.cmd_sheet_processes, ses.brainName()), gap(18));
        if (procs == null) {
            body.addView(text(a, a.getString(R.string.cmd_sheet_loading), 13, MUTED), gap(6));
        } else if (agentPid == 0) {
            body.addView(text(a, a.getString(R.string.cmd_sheet_no_agent, ses.brainName()), 13, MUTED), gap(6));
        } else if (procs.isEmpty()) {
            body.addView(text(a, a.getString(R.string.cmd_sheet_none, ses.brainName()), 13, MUTED), gap(6));
        } else {
            LinearLayout list = column(a);
            list.setBackground(rounded(a, BG, 14));
            list.setPaddingRelative(dp(a, 10), dp(a, 6), dp(a, 4), dp(a, 6));
            for (Proc p : listed(procs)) list.addView(row(p, p == running), full());
            body.addView(list, gap(6));
        }
        body.addView(text(a, a.getString(R.string.cmd_sheet_output_note, ses.brainName()), 12, MUTED), gap(10));
    }

    private LinearLayout row(Proc p, boolean isCommand) {
        LinearLayout r = Ui.row(a);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPaddingRelative(dp(a, 10 * Math.min(p.depth, 4)), dp(a, 4), 0, dp(a, 4));
        LinearLayout t = column(a);
        TextView args = monoText(a, shown(p.args), 12, isCommand ? ACCENT_TEXT : TEXT);
        args.setMaxLines(4);
        args.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.addView(args);
        // (processor time it used: a command that waits, like sleep, has none, and then it isn't said)
        t.addView(text(a, p.cpu > 0 ? a.getString(R.string.cmd_sheet_proc_line, p.pid, DateUtils.formatElapsedTime(p.seconds),
                DateUtils.formatElapsedTime(p.cpu)) : a.getString(R.string.cmd_sheet_proc_line_idle, p.pid,
                DateUtils.formatElapsedTime(p.seconds)), 11, MUTED));
        r.addView(t, weight1());
        if (p.depth == 0) { // what the agent itself started: each can be stopped, with what it started
            ImageButton x = iconButton(a, R.drawable.ic_stop, a.getString(R.string.cmd_sheet_stop_one), ERR);
            x.setOnClickListener(v -> confirmSheet(a, a.getString(R.string.cmd_sheet_stop_title),
                    a.getString(R.string.cmd_sheet_stop_text, ses.brainName()), a.getString(R.string.cmd_sheet_stop_one),
                    true, () -> kill(p.pid)));
            r.addView(x, new LinearLayout.LayoutParams(dp(a, 40), dp(a, 40)));
        }
        return r;
    }

    private void kill(long pid) {
        error = null;
        new Thread(() -> {
            final String said = stop(a, ses, pid); // the helper's own words when it refused
            main.post(() -> {
                if (!said.isEmpty()) error = said;
                if (!open) return;
                render();
                main.removeCallbacksAndMessages(null); // the one refresh chain goes on from here, and the ticking
                fetch();
                main.postDelayed(this::tick, TICK_MS);
            });
        }, "cmd-kill").start();
    }

    private TextView label(int res, Object... args) {
        return sectionLabel(a, a.getString(res, args));
    }

    private LinearLayout.LayoutParams gap(int top) {
        LinearLayout.LayoutParams p = full();
        p.topMargin = dp(a, top);
        return p;
    }
}
