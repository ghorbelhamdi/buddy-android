package app.buddy.assistant;

import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import static app.buddy.assistant.Ui.*;

/** Every Claude Code / Codex session on the phone: running processes and recent history. */
public class MonitorActivity extends Screen implements ChatHub.MonitorListener {
    private static final long REFRESH_MS = 3000;

    private ChatHub hub;
    private LinearLayout running, recent;
    private TextView runningEmpty, recentEmpty, runCount;
    private FrameLayout navSlot;
    private ObjectAnimator pulse;
    private boolean visible;
    private final Runnable poll = new Runnable() {
        @Override
        public void run() {
            if (!visible) return;
            request("procs");
            running.postDelayed(this, REFRESH_MS);
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        hub = ChatHub.get(this);

        LinearLayout root = column(this);
        root.setBackgroundColor(BG);
        root.setFitsSystemWindows(true);
        ScrollView scroll = new ScrollView(this);
        LinearLayout list = column(this);
        list.setPadding(0, dp(this, 12), 0, dp(this, 24));
        scroll.addView(list);
        root.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        navSlot = new FrameLayout(this);
        navSlot.addView(bottomNav(this, NAV_SESSIONS, hub.runningCount()));
        root.addView(navSlot, full());
        setContentView(root);

        LinearLayout head = column(this);
        head.setPadding(dp(this, 20), dp(this, 8), dp(this, 20), dp(this, 4));
        head.addView(title(this, "Sessions", 28));
        TextView sub = text(this, "Every Claude Code and Codex session on this phone.", 14, MUTED);
        sub.setPadding(0, dp(this, 4), 0, 0);
        head.addView(sub);
        list.addView(head, full());

        LinearLayout rh = row(this);
        TextView rl = sectionLabel(this, "Running now");
        rl.setPadding(dp(this, 20), dp(this, 16), dp(this, 8), dp(this, 4));
        rh.addView(rl);
        runCount = monoText(this, "", 12, OK);
        runCount.setTypeface(mono(this, 600));
        runCount.setPadding(0, dp(this, 12), 0, 0);
        rh.addView(runCount, weight1());
        View pd = dot(this, OK, 6);
        ((LinearLayout.LayoutParams) pd.getLayoutParams()).topMargin = dp(this, 12);
        rh.addView(pd);
        pulse = ObjectAnimator.ofFloat(pd, "alpha", 1f, 0.2f);
        pulse.setDuration(900);
        pulse.setRepeatMode(ValueAnimator.REVERSE);
        pulse.setRepeatCount(ValueAnimator.INFINITE);
        TextView rs = monoText(this, "refresh 3s", 12, MUTED);
        rs.setPadding(dp(this, 6), dp(this, 12), dp(this, 20), 0);
        rh.addView(rs);
        list.addView(rh, full());

        running = column(this);
        running.setPadding(dp(this, 16), 0, dp(this, 16), 0);
        list.addView(running, full());
        runningEmpty = empty("Checking…");
        running.addView(runningEmpty);

        list.addView(sectionLabel(this, "Recent"));
        recent = column(this);
        list.addView(recent, full());
        recentEmpty = empty("Loading…");
        recent.addView(recentEmpty);
    }

    private TextView empty(String s) {
        TextView t = text(this, s, 14, MUTED);
        t.setPadding(dp(this, 20), dp(this, 8), dp(this, 20), dp(this, 8));
        return t;
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (isFinishing()) return;
        visible = true;
        hub.monitor = this;
        pulse.start();
        if (BuddyService.get() == null) {
            runningEmpty.setText("Buddy's accessibility service is off, so sessions can't be checked. Turn it on in Settings.");
            return;
        }
        request("recent");
        running.post(poll);
    }

    @Override
    protected void onPause() {
        visible = false;
        pulse.cancel();
        if (hub.monitor == this) hub.monitor = null;
        running.removeCallbacks(poll);
        super.onPause();
    }

    private void request(String... args) {
        try {
            Helper.run(this, args);
        } catch (Exception e) {
            runningEmpty.setText(e.getMessage());
        }
    }

    @Override
    public void onMonitor(JSONObject ev) {
        switch (ev.optString("type")) {
            case "buddy_procs":
                showProcs(ev.optJSONArray("procs"));
                break;
            case "buddy_recent":
                showRecent(ev.optJSONArray("sessions"));
                break;
            default:
                break;
        }
    }

    private void showProcs(JSONArray procs) {
        running.removeAllViews();
        // Claude Code's background service isn't a conversation: list it apart, without a Stop button.
        java.util.List<JSONObject> sessions = new java.util.ArrayList<>(), services = new java.util.ArrayList<>();
        for (int i = 0; procs != null && i < procs.length(); i++) {
            JSONObject p = procs.optJSONObject(i);
            if (p == null) continue;
            ("daemon".equals(p.optString("kind")) ? services : sessions).add(p);
        }
        int n = sessions.size();
        runCount.setText(n > 0 ? String.valueOf(n) : "");
        navSlot.removeAllViews();
        navSlot.addView(bottomNav(this, NAV_SESSIONS, n));
        if (n == 0) {
            runningEmpty.setText("Nothing running.");
            running.addView(runningEmpty);
        }
        for (final JSONObject p : sessions) {
            final String backend = p.optString("agent");
            final String agent = "codex".equals(backend) ? "Codex" : "Claude Code";
            final Sessions.Session chat = hub.sessions.find(p.optString("buddy"));
            final String kind = p.optString("kind", "terminal");
            final boolean background = "background".equals(kind);
            String title = p.optString("title").trim();
            final String where = chat != null ? chat.label()
                    : !p.optString("buddy").isEmpty() ? "Deleted Buddy chat"
                    : !title.isEmpty() ? title
                    : background ? "Background session" : "Terminal session";

            LinearLayout card = column(this);
            card.setPadding(dp(this, 16), dp(this, 14), dp(this, 16), dp(this, 14));
            card.setBackground(rounded(this, CARD, 16));
            LinearLayout first = row(this);
            first.addView(single(title(this, where, 15)), weight1());
            TextView el = monoText(this, DateUtils.formatElapsedTime(p.optLong("seconds")), 12, MUTED);
            el.setPadding(dp(this, 8), 0, 0, 0);
            first.addView(el);
            card.addView(first, full());

            LinearLayout tags = row(this);
            tags.setPadding(0, dp(this, 6), 0, 0);
            tags.addView(tag(chat != null ? "buddy" : background ? "background" : "terminal", chat != null || background));
            String ses = p.optString("session");
            if (ses.length() >= 8) {
                TextView id = monoText(this, "#" + ses.substring(0, 8), 12, MUTED);
                id.setPadding(dp(this, 8), 0, 0, 0);
                tags.addView(id);
            }
            card.addView(tags, full());
            TextView meta = single(monoText(this, ("codex".equals(backend) ? "codex" : "claude-code") + " · pid "
                    + p.optLong("pid") + " · " + shortPath(p.optString("cwd")), 12, MUTED));
            meta.setPadding(0, dp(this, 4), 0, 0);
            card.addView(meta);
            if (background && chat == null) {
                TextView hint = text(this, "Runs in Claude Code's background service, e.g. a chat you continue from the Claude app.",
                        13, MUTED);
                hint.setPadding(0, dp(this, 4), 0, 0);
                card.addView(hint);
            }
            if (chat != null && !chat.status.isEmpty()) {
                TextView st = single(monoText(this, chat.status, 12, ACCENT_TEXT));
                st.setPadding(0, dp(this, 3), 0, 0);
                card.addView(st);
            }
            LinearLayout btns = row(this);
            if (chat != null) {
                Button open = button(this, "Open chat", false);
                open.setOnClickListener(v -> openChat(chat));
                LinearLayout.LayoutParams op = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(this, 40));
                op.rightMargin = dp(this, 8);
                btns.addView(open, op);
            }
            Button stop = outlinedButton(this, "Stop", ERR);
            stop.setOnClickListener(v -> confirmSheet(this, "Stop this " + agent + " session?",
                    chat != null ? where
                            : (background ? "It runs in the background, for example a chat you're continuing from the Claude app. "
                            : "It may be a session you're using in a terminal right now. ")
                            + "It could even be the one controlling Buddy. Stopping it ends that conversation's current work.",
                    "Stop", true, () -> {
                        if (chat != null) hub.stop(chat);
                        else request("kill", String.valueOf(p.optLong("pid")));
                        Toast.makeText(this, "Stopping…", Toast.LENGTH_SHORT).show();
                    }));
            btns.addView(stop, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(this, 40)));
            LinearLayout.LayoutParams bp = full();
            bp.topMargin = dp(this, 12);
            card.addView(btns, bp);
            if (chat != null) card.setOnClickListener(v -> openChat(chat));
            LinearLayout.LayoutParams cp = full();
            cp.topMargin = dp(this, 8);
            running.addView(card, cp);
        }
        for (JSONObject d : services) {
            LinearLayout r = row(this);
            r.setGravity(android.view.Gravity.TOP);
            r.setPadding(dp(this, 4), dp(this, 12), dp(this, 4), 0);
            r.addView(icon(this, R.drawable.ms_terminal, MUTED, 18));
            LinearLayout col = column(this);
            col.setPadding(dp(this, 10), 0, 0, 0);
            col.addView(text(this, "Claude Code background service", 14, TEXT));
            col.addView(single(monoText(this, "pid " + d.optLong("pid") + " · up "
                    + DateUtils.formatElapsedTime(d.optLong("seconds")) + " · hosts background sessions", 12, MUTED)));
            r.addView(col, weight1());
            running.addView(r, full());
        }
    }

    private TextView tag(String s, boolean accent) {
        TextView t = monoText(this, s, 11, accent ? ACCENT_TEXT : MUTED);
        t.setPadding(dp(this, 6), dp(this, 1), dp(this, 6), dp(this, 1));
        t.setBackground(accent ? rounded(this, ACCENT_SOFT, 6) : outlined(this, 0x00000000, 6, OUTLINE, 1));
        return t;
    }

    private void showRecent(JSONArray list) {
        recent.removeAllViews();
        if (list == null || list.length() == 0) {
            recentEmpty.setText("No history found.");
            recent.addView(recentEmpty);
            return;
        }
        for (int i = 0; i < list.length(); i++) {
            final JSONObject s = list.optJSONObject(i);
            if (s == null) continue;
            final String backend = s.optString("agent");
            final Sessions.Session chat = hub.sessions.find(s.optString("buddy"));
            String title = s.optString("title");
            final String t = title.isEmpty() ? "(no messages)" : title;

            LinearLayout r = listRow(this);
            r.setGravity(android.view.Gravity.CENTER_VERTICAL);
            LinearLayout col = column(this);
            LinearLayout first = row(this);
            TextView tt = title(this, t, 15);
            tt.setMaxLines(1);
            tt.setEllipsize(TextUtils.TruncateAt.END);
            first.addView(tt, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
            if (chat != null) {
                TextView tag = monoText(this, "buddy", 11, ACCENT_TEXT);
                tag.setPadding(dp(this, 6), dp(this, 1), dp(this, 6), dp(this, 1));
                tag.setBackground(rounded(this, ACCENT_SOFT, 6));
                LinearLayout.LayoutParams tp = wrap();
                tp.leftMargin = dp(this, 8);
                first.addView(tag, tp);
            }
            col.addView(first, full());
            TextView meta = single(monoText(this, ("codex".equals(backend) ? "codex" : "claude-code") + " · "
                    + MainActivity.ago(s.optLong("mtime") * 1000) + " · " + shortPath(s.optString("cwd")), 12, MUTED));
            meta.setPadding(0, dp(this, 3), 0, 0);
            col.addView(meta);
            r.addView(col, weight1());
            r.addView(icon(this, R.drawable.ms_chevron_right, MUTED, 22));
            r.setOnClickListener(v -> {
                if (chat != null) {
                    openChat(chat);
                } else {
                    startActivity(new Intent(this, TranscriptActivity.class)
                            .putExtra("file", s.optString("file")).putExtra("agent", backend)
                            .putExtra("id", s.optString("id")).putExtra("title", t)
                            .putExtra("cwd", shortPath(s.optString("cwd"))));
                }
            });
            recent.addView(r, full());
        }
    }

    private void openChat(Sessions.Session chat) {
        hub.select(chat);
        startActivity(new Intent(this, ChatActivity.class).putExtra("id", chat.id));
    }

    static String shortPath(String p) {
        return p.replaceFirst("^/root(?=/|$)", "~");
    }
}
