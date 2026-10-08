package app.buddy.assistant;

import android.app.Dialog;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static app.buddy.assistant.Ui.*;

/** Home screen: the list of chats. */
public class MainActivity extends Screen implements ChatHub.Listener {
    private ChatHub hub;
    private LinearLayout list;
    private FrameLayout navSlot;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        hub = ChatHub.get(this);
        if (!Prefs.onboarded(this)) {
            // already set up: no need for the guide
            if (new SetupState(this).count() >= 3) Prefs.setOnboarded(this, true);
            else startActivity(new Intent(this, OnboardingActivity.class));
        }
        // Needed for the "Buddy is hidden" notification after dragging the bubble away.
        if (getIntent().getBooleanExtra("askMic", false) && !Voice.micAllowed(this)) {
            requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, 3);
        } else if (android.os.Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{android.Manifest.permission.POST_NOTIFICATIONS}, 2);
        }
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(BG);
        root.setFitsSystemWindows(true);
        LinearLayout column = column(this);
        ScrollView scroll = new ScrollView(this);
        list = column(this);
        list.setPadding(0, dp(this, 8), 0, dp(this, 96));
        scroll.addView(list);
        column.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        navSlot = new FrameLayout(this);
        column.addView(navSlot, full());
        root.addView(column);

        // "New chat" extended FAB above the nav
        LinearLayout fab = row(this);
        fab.setPadding(dp(this, 16), 0, dp(this, 20), 0);
        fab.setBackground(rounded(this, ACCENT, 18));
        fab.setElevation(dp(this, 6));
        fab.addView(icon(this, R.drawable.ms_add, ON_ACCENT, 24));
        TextView ft = title(this, "New chat", 15);
        ft.setTextColor(ON_ACCENT);
        ft.setPadding(dp(this, 8), 0, 0, 0);
        fab.addView(ft);
        fab.setOnClickListener(v -> newChatSheet());
        FrameLayout.LayoutParams fp = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(this, 56),
                Gravity.BOTTOM | Gravity.END);
        fp.rightMargin = dp(this, 16);
        fp.bottomMargin = dp(this, 92);
        root.addView(fab, fp);
        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (isFinishing()) return;
        hub.addListener(this);
        render();
        // After an app update the helper may report an old version: re-check quietly instead of nagging.
        if (Prefs.helperStatus(this) != null && !SetupState.helperCurrent(this) && BuddyService.get() != null
                && Termux.permitted(this)) {
            try {
                Termux.run(this, "ping");
                list.postDelayed(() -> {
                    if (!isFinishing()) render();
                }, 1500);
            } catch (Exception ignored) {
            }
        }
    }

    @Override
    protected void onPause() {
        hub.removeListener(this);
        super.onPause();
    }

    @Override
    public void onChatsChanged() {
        render();
    }

    @Override
    public void onMessage(Sessions.Session ses, Sessions.Msg msg) {
    }

    private void render() {
        list.removeAllViews();
        navSlot.removeAllViews();
        navSlot.addView(bottomNav(this, NAV_CHATS, hub.runningCount()));

        // header
        SetupState setup = new SetupState(this);
        boolean ready = setup.canRun();
        LinearLayout top = row(this);
        top.setPadding(dp(this, 20), dp(this, 12), dp(this, 20), dp(this, 8));
        FrameLayout tile = new FrameLayout(this);
        tile.setBackgroundResource(R.drawable.ic_launcher_background);
        tile.setClipToOutline(true);
        tile.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View v, android.graphics.Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(MainActivity.this, 12));
            }
        });
        ImageView face = new ImageView(this);
        face.setImageResource(R.drawable.bubble_face);
        tile.addView(face, new FrameLayout.LayoutParams(dp(this, 30), dp(this, 30), Gravity.CENTER));
        top.addView(tile, new LinearLayout.LayoutParams(dp(this, 40), dp(this, 40)));
        LinearLayout name = column(this);
        name.setPadding(dp(this, 12), 0, 0, 0);
        name.addView(title(this, "Buddy", 22));
        LinearLayout status = row(this);
        status.addView(dot(this, ready ? OK : ACCENT, 7));
        TextView st = monoText(this, ready ? "ready · 127.0.0.1:" + McpServer.PORT : "setup " + setup.count() + "/5", 12, MUTED);
        st.setPadding(dp(this, 6), 0, 0, 0);
        status.addView(st);
        name.addView(status);
        top.addView(name, weight1());
        list.addView(top, full());

        if (!ready || setup.next() >= 0 && setup.next() < 3) {
            LinearLayout warn = row(this);
            warn.setPadding(dp(this, 16), dp(this, 14), dp(this, 12), dp(this, 14));
            warn.setBackground(outlined(this, CARD, 16, Theme.withAlpha(ACCENT, 0x59), 1));
            warn.addView(icon(this, R.drawable.ms_build_circle, ACCENT, 24));
            LinearLayout wt = column(this);
            wt.setPadding(dp(this, 12), 0, dp(this, 8), 0);
            wt.addView(title(this, "Finish setup to run chats", 15));
            int n = setup.next();
            wt.addView(text(this, "Next: " + (n >= 0 ? SetupState.STEPS[n] : "open Buddy's accessibility service"), 13, MUTED));
            warn.addView(wt, weight1());
            warn.addView(icon(this, R.drawable.ms_chevron_right, MUTED, 22));
            warn.setOnClickListener(v -> go(SetupActivity.class));
            LinearLayout.LayoutParams wp = full();
            wp.leftMargin = wp.rightMargin = dp(this, 16);
            wp.topMargin = dp(this, 8);
            wp.bottomMargin = dp(this, 4);
            list.addView(warn, wp);
        }

        List<Sessions.Session> working = new ArrayList<>(), recent = new ArrayList<>();
        for (Sessions.Session ses : hub.sessions.all) {
            if (ses.running) working.add(ses);
            else if (!ses.messages.isEmpty()) recent.add(ses); // hide untouched new chats
        }
        java.util.Collections.sort(recent, (a, b) -> Long.compare(b.updated, a.updated));
        if (!working.isEmpty()) {
            list.addView(sectionHeader("Working", working.size()));
            for (Sessions.Session s : working) list.addView(chatRow(s), full());
        }
        if (!recent.isEmpty()) {
            list.addView(sectionLabel(this, "Recent"));
            for (Sessions.Session s : recent) list.addView(chatRow(s), full());
        }
        if (working.isEmpty() && recent.isEmpty()) {
            LinearLayout empty = column(this);
            empty.setGravity(Gravity.CENTER_HORIZONTAL);
            empty.setPadding(dp(this, 32), dp(this, 64), dp(this, 32), 0);
            empty.addView(icon(this, R.drawable.ms_forum, MUTED, 40));
            TextView et = title(this, "No chats yet", 17);
            et.setPadding(0, dp(this, 12), 0, dp(this, 4));
            empty.addView(et);
            TextView es = text(this, "Tap New chat to start one with Claude Code or Codex. Long-press a chat to delete it.", 14, MUTED);
            es.setGravity(Gravity.CENTER);
            empty.addView(es);
            list.addView(empty, full());
        }
    }

    private View sectionHeader(String label, int count) {
        LinearLayout r = row(this);
        TextView l = sectionLabel(this, label);
        l.setPadding(dp(this, 20), dp(this, 16), dp(this, 8), dp(this, 4));
        r.addView(l);
        TextView n = monoText(this, String.valueOf(count), 12, ACCENT_TEXT);
        n.setTypeface(mono(this, 600));
        n.setPadding(0, dp(this, 12), 0, 0);
        r.addView(n);
        return r;
    }

    private View chatRow(final Sessions.Session ses) {
        LinearLayout r = listRow(this);
        r.addView(agentTile(this, ses.backend));
        LinearLayout col = column(this);
        col.setPadding(dp(this, 14), 0, 0, 0);

        LinearLayout first = row(this);
        first.addView(single(title(this, ses.label(), 15)), weight1());
        TextView time = monoText(this, ago(ses.updated), 12, MUTED);
        time.setPadding(dp(this, 8), 0, 0, 0);
        first.addView(time);
        if (ses.unread) {
            View d = dot(this, OK, 8);
            ((LinearLayout.LayoutParams) d.getLayoutParams()).leftMargin = dp(this, 6);
            first.addView(d);
        }
        col.addView(first, full());

        String agent = "codex".equals(ses.backend) ? "codex" : "claude-code";
        col.addView(single(monoText(this, agent + " · " + Models.label(ses.backend, ses.model).toLowerCase(Locale.ROOT),
                12, MUTED)));
        if (ses.running) {
            TextView s = single(monoText(this, ses.status.isEmpty() ? "working…" : ses.status, 12, ACCENT_TEXT));
            s.setPadding(0, dp(this, 4), 0, dp(this, 6));
            col.addView(s);
            col.addView(thinBar(this));
        } else {
            String last = "";
            for (int i = ses.messages.size() - 1; i >= 0; i--) {
                Sessions.Msg m = ses.messages.get(i);
                if (m.kind == Bubble.Kind.BUDDY || m.kind == Bubble.Kind.USER) {
                    last = (m.kind == Bubble.Kind.USER ? "You: " : "") + m.label().replace('\n', ' ');
                    break;
                }
            }
            if (!last.isEmpty()) {
                TextView l = text(this, last, 14, MUTED);
                l.setMaxLines(2);
                l.setEllipsize(TextUtils.TruncateAt.END);
                l.setPadding(0, dp(this, 4), 0, 0);
                col.addView(l);
            }
        }
        r.addView(col, weight1());
        r.setOnClickListener(v -> open(ses));
        r.setOnLongClickListener(v -> {
            confirmSheet(this, "Delete this chat?", ses.label(), "Delete", true, () -> hub.delete(ses));
            return true;
        });
        return r;
    }

    private void newChatSheet() {
        LinearLayout c = column(this);
        TextView h = title(this, "New chat", 18);
        h.setPadding(0, 0, 0, dp(this, 8));
        c.addView(h);
        final Dialog[] d = new Dialog[1];
        String def = Prefs.backend(this);
        String[][] agents = {{"claude", "Claude Code", "Uses your Claude plan"}, {"codex", "Codex", "Uses your ChatGPT plan"}};
        for (String[] a : agents) {
            LinearLayout r = row(this);
            r.setPadding(dp(this, 4), dp(this, 12), dp(this, 4), dp(this, 12));
            r.setBackgroundResource(ripple(this, false));
            r.addView(agentTile(this, a[0]));
            LinearLayout t = column(this);
            t.setPadding(dp(this, 14), 0, 0, 0);
            LinearLayout tr = row(this);
            tr.addView(title(this, a[1], 15));
            if (a[0].equals(def)) {
                TextView tag = monoText(this, "default", 11, ACCENT_TEXT);
                tag.setPadding(dp(this, 6), dp(this, 1), dp(this, 6), dp(this, 1));
                tag.setBackground(rounded(this, ACCENT_SOFT, 6));
                LinearLayout.LayoutParams tp = wrap();
                tp.leftMargin = dp(this, 8);
                tr.addView(tag, tp);
            }
            t.addView(tr);
            t.addView(text(this, a[2], 13, MUTED));
            r.addView(t, weight1());
            r.addView(icon(this, R.drawable.ms_chevron_right, MUTED, 22));
            r.setOnClickListener(v -> {
                d[0].dismiss();
                open(hub.create(a[0]));
            });
            c.addView(r, full());
        }
        d[0] = bottomSheet(this, c);
        d[0].show();
    }

    static String ago(long t) {
        long s = Math.max(0, (System.currentTimeMillis() - t) / 1000);
        if (s < 60) return "now";
        if (s < 3600) return s / 60 + "m";
        if (s < 86400) return s / 3600 + "h";
        return s / 86400 + "d";
    }

    private void go(Class<?> c) {
        startActivity(new Intent(this, c));
    }

    private void open(Sessions.Session ses) {
        hub.select(ses);
        startActivity(new Intent(this, ChatActivity.class).putExtra("id", ses.id));
    }
}
