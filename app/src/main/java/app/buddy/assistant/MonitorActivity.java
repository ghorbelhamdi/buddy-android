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

/** Every Claude Code, Codex, OpenCode, Antigravity and Grok session on the phone: running processes and recent history. */
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
        list.setPaddingRelative(0, dp(this, 12), 0, dp(this, 24));
        scroll.addView(list);
        root.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        navSlot = new FrameLayout(this);
        navSlot.addView(bottomNav(this, NAV_SESSIONS, hub.runningCount()));
        root.addView(navSlot, full());
        setContentView(root);

        LinearLayout head = column(this);
        head.setPaddingRelative(dp(this, 20), dp(this, 8), dp(this, 20), dp(this, 4));
        head.addView(title(this, getString(R.string.monitor_title), 28));
        TextView sub = text(this, getString(R.string.monitor_subtitle), 14, MUTED);
        sub.setPaddingRelative(0, dp(this, 4), 0, 0);
        head.addView(sub);
        list.addView(head, full());

        // a shell in Buddy's Linux
        LinearLayout tc = row(this);
        tc.setPaddingRelative(dp(this, 16), dp(this, 14), dp(this, 12), dp(this, 14));
        tc.setBackground(rounded(this, CARD, 16));
        tc.setForeground(getDrawable(ripple(this, false)));
        android.widget.FrameLayout ti = new android.widget.FrameLayout(this);
        ti.setBackground(rounded(this, CODE, 12));
        ti.addView(monoText(this, ">_", 14, ACCENT), new android.widget.FrameLayout.LayoutParams(
                android.widget.FrameLayout.LayoutParams.WRAP_CONTENT, android.widget.FrameLayout.LayoutParams.WRAP_CONTENT,
                android.view.Gravity.CENTER));
        tc.addView(ti, new LinearLayout.LayoutParams(dp(this, 40), dp(this, 40)));
        LinearLayout tt = column(this);
        tt.setPaddingRelative(dp(this, 14), 0, dp(this, 8), 0);
        tt.addView(title(this, getString(R.string.terminal_title), 15));
        tt.addView(text(this, getString(TermSession.current != null && !TermSession.current.exited
                ? R.string.monitor_terminal_running : R.string.monitor_terminal_idle), 13, MUTED));
        tc.addView(tt, weight1());
        tc.addView(icon(this, R.drawable.ms_chevron_right, MUTED, 22));
        tc.setOnClickListener(v -> startActivity(new Intent(this, TerminalActivity.class)));
        LinearLayout.LayoutParams tp = full();
        tp.setMargins(dp(this, 16), dp(this, 12), dp(this, 16), 0);
        list.addView(tc, tp);

        LinearLayout rh = row(this);
        TextView rl = sectionLabel(this, getString(R.string.monitor_running_now));
        rl.setPaddingRelative(dp(this, 20), dp(this, 16), dp(this, 8), dp(this, 4));
        rh.addView(rl);
        runCount = monoText(this, "", 12, OK);
        runCount.setTypeface(mono(this, 600));
        runCount.setPaddingRelative(0, dp(this, 12), 0, 0);
        rh.addView(runCount, weight1());
        View pd = dot(this, OK, 6);
        ((LinearLayout.LayoutParams) pd.getLayoutParams()).topMargin = dp(this, 12);
        rh.addView(pd);
        pulse = ObjectAnimator.ofFloat(pd, "alpha", 1f, 0.2f);
        pulse.setDuration(900);
        pulse.setRepeatMode(ValueAnimator.REVERSE);
        pulse.setRepeatCount(ValueAnimator.INFINITE);
        TextView rs = monoText(this, getString(R.string.monitor_refresh), 12, MUTED);
        rs.setPaddingRelative(dp(this, 6), dp(this, 12), dp(this, 20), 0);
        rh.addView(rs);
        list.addView(rh, full());

        running = column(this);
        running.setPaddingRelative(dp(this, 16), 0, dp(this, 16), 0);
        list.addView(running, full());
        runningEmpty = empty(getString(R.string.monitor_checking));
        running.addView(runningEmpty);

        list.addView(sectionLabel(this, getString(R.string.monitor_recent)));
        recent = column(this);
        list.addView(recent, full());
        recentEmpty = empty(getString(R.string.monitor_loading));
        recent.addView(recentEmpty);
    }

    private TextView empty(String s) {
        TextView t = text(this, s, 14, MUTED);
        t.setPaddingRelative(dp(this, 20), dp(this, 8), dp(this, 20), dp(this, 8));
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
            runningEmpty.setText(R.string.monitor_service_off);
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
            runningEmpty.setText(R.string.monitor_nothing_running);
            running.addView(runningEmpty);
        }
        for (final JSONObject p : sessions) {
            final String backend = p.optString("agent");
            final String agent = Account.nameOf(backend);
            final Sessions.Session chat = hub.sessions.find(p.optString("buddy"));
            final String kind = p.optString("kind", "terminal");
            final boolean background = "background".equals(kind);
            String title = p.optString("title").trim();
            final String where = chat != null ? chat.label()
                    : !p.optString("buddy").isEmpty() ? getString(R.string.monitor_deleted_chat)
                    : !title.isEmpty() ? title
                    : getString(background ? R.string.monitor_background_session : R.string.monitor_terminal_session);

            LinearLayout card = column(this);
            card.setPaddingRelative(dp(this, 16), dp(this, 14), dp(this, 16), dp(this, 14));
            card.setBackground(rounded(this, CARD, 16));
            LinearLayout first = row(this);
            first.addView(single(title(this, where, 15)), weight1());
            TextView el = monoText(this, DateUtils.formatElapsedTime(p.optLong("seconds")), 12, MUTED);
            el.setPaddingRelative(dp(this, 8), 0, 0, 0);
            first.addView(el);
            card.addView(first, full());

            LinearLayout tags = row(this);
            tags.setPaddingRelative(0, dp(this, 6), 0, 0);
            tags.addView(tag(chat != null ? "buddy" : getString(background ? R.string.monitor_tag_background
                    : R.string.monitor_tag_terminal), chat != null || background));
            String ses = p.optString("session");
            if (ses.length() >= 8) {
                TextView id = monoText(this, "#" + ses.substring(0, 8), 12, MUTED);
                id.setPaddingRelative(dp(this, 8), 0, 0, 0);
                tags.addView(id);
            }
            card.addView(tags, full());
            TextView meta = single(monoText(this, getString(R.string.monitor_proc_meta, Account.cliOf(backend),
                    p.optLong("pid"), shortPath(p.optString("cwd"))), 12, MUTED));
            meta.setPaddingRelative(0, dp(this, 4), 0, 0);
            card.addView(meta);
            if (background && chat == null) {
                TextView hint = text(this, getString(R.string.monitor_background_hint), 13, MUTED);
                hint.setPaddingRelative(0, dp(this, 4), 0, 0);
                card.addView(hint);
            }
            if (chat != null && !chat.status.isEmpty()) {
                TextView st = single(monoText(this, chat.status, 12, ACCENT_TEXT));
                st.setPaddingRelative(0, dp(this, 3), 0, 0);
                card.addView(st);
            }
            LinearLayout btns = row(this);
            if (chat != null) {
                Button open = button(this, getString(R.string.monitor_open_chat), false);
                open.setOnClickListener(v -> openChat(chat));
                LinearLayout.LayoutParams op = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(this, 40));
                op.setMarginEnd(dp(this, 8));
                btns.addView(open, op);
            }
            Button stop = outlinedButton(this, getString(R.string.monitor_stop), ERR);
            stop.setOnClickListener(v -> confirmSheet(this, getString(R.string.monitor_stop_title, agent),
                    chat != null ? where : getString(background ? R.string.monitor_stop_background_body
                            : R.string.monitor_stop_terminal_body),
                    getString(R.string.monitor_stop), true, () -> {
                        if (chat != null) hub.stop(chat);
                        else request("kill", String.valueOf(p.optLong("pid")));
                        Toast.makeText(this, R.string.monitor_stopping, Toast.LENGTH_SHORT).show();
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
            r.setPaddingRelative(dp(this, 4), dp(this, 12), dp(this, 4), 0);
            r.addView(icon(this, R.drawable.ms_terminal, MUTED, 18));
            LinearLayout col = column(this);
            col.setPaddingRelative(dp(this, 10), 0, 0, 0);
            col.addView(text(this, getString(R.string.monitor_service_title), 14, TEXT));
            col.addView(single(monoText(this, getString(R.string.monitor_service_meta, d.optLong("pid"),
                    DateUtils.formatElapsedTime(d.optLong("seconds"))), 12, MUTED)));
            r.addView(col, weight1());
            running.addView(r, full());
        }
    }

    private TextView tag(String s, boolean accent) {
        TextView t = monoText(this, s, 11, accent ? ACCENT_TEXT : MUTED);
        t.setPaddingRelative(dp(this, 6), dp(this, 1), dp(this, 6), dp(this, 1));
        t.setBackground(accent ? rounded(this, ACCENT_SOFT, 6) : outlined(this, 0x00000000, 6, OUTLINE, 1));
        return t;
    }

    private void showRecent(JSONArray list) {
        recent.removeAllViews();
        if (list == null || list.length() == 0) {
            recentEmpty.setText(R.string.monitor_no_history);
            recent.addView(recentEmpty);
            return;
        }
        for (int i = 0; i < list.length(); i++) {
            final JSONObject s = list.optJSONObject(i);
            if (s == null) continue;
            final String backend = s.optString("agent");
            final Sessions.Session chat = hub.sessions.find(s.optString("buddy"));
            String title = s.optString("title");
            final String t = title.isEmpty() ? getString(R.string.monitor_no_messages) : title;

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
                tag.setPaddingRelative(dp(this, 6), dp(this, 1), dp(this, 6), dp(this, 1));
                tag.setBackground(rounded(this, ACCENT_SOFT, 6));
                LinearLayout.LayoutParams tp = wrap();
                tp.setMarginStart(dp(this, 8));
                first.addView(tag, tp);
            }
            col.addView(first, full());
            TextView meta = single(monoText(this, Account.cliOf(backend) + " · "
                    + MainActivity.ago(s.optLong("mtime") * 1000) + " · " + shortPath(s.optString("cwd")), 12, MUTED));
            meta.setPaddingRelative(0, dp(this, 3), 0, 0);
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
