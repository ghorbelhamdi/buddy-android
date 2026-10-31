package app.buddy.assistant;

import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.util.ArrayList;
import static app.buddy.assistant.Ui.*;

/**
 * Settings → Scheduled: every chat's scheduled task (Run now, Pause) and every message or reminder the agents
 * scheduled (Cancel), in one place. Tap one to open its chat.
 */
public class SchedulesActivity extends Screen implements ChatHub.Listener {
    private LinearLayout body;
    private ChatHub hub;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        hub = ChatHub.get(this);
        LinearLayout root = column(this);
        root.setBackgroundColor(BG);
        LinearLayout top = row(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPaddingRelative(dp(this, 4), dp(this, 4), dp(this, 16), dp(this, 4));
        ImageButton back = iconButton(this, R.drawable.ms_arrow_back, getString(R.string.perms_back));
        back.setLayoutParams(new LinearLayout.LayoutParams(dp(this, 44), dp(this, 44)));
        back.setOnClickListener(v -> finish());
        top.addView(back);
        TextView t = title(this, getString(R.string.schedules_title), 18);
        t.setPaddingRelative(dp(this, 6), 0, 0, 0);
        top.addView(t, weight1());
        root.addView(top, full());
        ScrollView sv = new ScrollView(this);
        body = column(this);
        body.setPaddingRelative(dp(this, 20), dp(this, 4), dp(this, 20), dp(this, 32));
        sv.addView(body);
        root.addView(sv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(BG);
        frame.setFitsSystemWindows(true);
        frame.addView(root);
        setContentView(protect(frame)); // only the user runs, pauses or cancels them
    }

    @Override
    protected void onResume() {
        super.onResume();
        hub.addListener(this);
        render();
    }

    @Override
    protected void onPause() {
        super.onPause();
        hub.removeListener(this);
    }

    @Override
    public void onChatsChanged() {
        render();
    }

    @Override
    public void onMessage(Sessions.Session s, Sessions.Msg msg) {
    }

    private void render() {
        body.removeAllViews();
        body.addView(text(this, getString(R.string.schedules_intro), 13, MUTED), full());
        int n = 0;
        for (Sessions.Session s : new ArrayList<>(hub.sessions.all)) {
            if (s.schedule == null && s.later.isEmpty()) continue;
            body.addView(chatCard(s), gap(n++ == 0 ? 14 : 8));
        }
        if (n == 0) body.addView(text(this, getString(R.string.schedules_none), 15, TEXT), gap(18));
    }

    private LinearLayout chatCard(Sessions.Session s) {
        LinearLayout card = column(this);
        card.setBackground(outlined(this, CARD, 16, OUTLINE, 1));
        card.setPaddingRelative(dp(this, 14), dp(this, 12), dp(this, 14), dp(this, 10));
        LinearLayout head = row(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(agentTile(this, s.backend));
        TextView name = single(title(this, s.label(), 15));
        name.setPaddingRelative(dp(this, 10), 0, 0, 0);
        head.addView(name, weight1());
        head.setOnClickListener(v -> startActivity(new Intent(this, ChatActivity.class).putExtra("id", s.id)));
        card.addView(head, full());
        if (s.schedule != null) {
            Schedules.Plan p = s.schedule;
            TextView when = text(this, p.summary(this) + (p.paused ? " · " + getString(R.string.schedules_paused) : "")
                    + (p.quiet ? " · " + getString(R.string.schedules_quiet_tag) : ""), 14, p.paused ? MUTED : ACCENT_TEXT);
            card.addView(when, gap(10));
            TextView msg = text(this, p.text, 13, MUTED);
            msg.setMaxLines(3);
            msg.setEllipsize(android.text.TextUtils.TruncateAt.END);
            card.addView(msg, gap(2));
            LinearLayout btns = row(this);
            Button run = button(this, getString(R.string.schedules_run_now), false);
            run.setEnabled(!s.running);
            run.setOnClickListener(v -> {
                Schedules.runNow(this, s);
                android.widget.Toast.makeText(this, R.string.schedules_started, android.widget.Toast.LENGTH_SHORT).show();
            });
            Button pause = button(this, getString(p.paused ? R.string.schedules_resume : R.string.schedules_pause), false);
            pause.setOnClickListener(v -> Schedules.setPaused(this, s, !p.paused));
            btns.addView(run, new LinearLayout.LayoutParams(0, dp(this, 40), 1));
            LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(0, dp(this, 40), 1);
            pp.setMarginStart(dp(this, 8));
            btns.addView(pause, pp);
            card.addView(btns, gap(10));
        }
        for (Schedules.Later l : new ArrayList<>(s.later)) {
            LinearLayout r = row(this);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.addView(icon(this, l.remind ? R.drawable.ms_notifications : R.drawable.ms_schedule, ACCENT_TEXT, 18));
            LinearLayout tx = column(this);
            tx.setPaddingRelative(dp(this, 8), 0, dp(this, 4), 0);
            tx.addView(text(this, (l.remind ? getString(R.string.schedules_reminder) + " · " : "")
                    + Schedules.when(this, l.at), 13, TEXT));
            TextView lt = text(this, l.text.replace('\n', ' '), 12, MUTED);
            lt.setMaxLines(2);
            lt.setEllipsize(android.text.TextUtils.TruncateAt.END);
            tx.addView(lt);
            r.addView(tx, weight1());
            ImageButton x = iconButton(this, R.drawable.ms_close, getString(R.string.later_cancel), MUTED);
            x.setOnClickListener(v -> Schedules.cancelLater(this, s, l.id));
            r.addView(x, new LinearLayout.LayoutParams(dp(this, 40), dp(this, 40)));
            card.addView(r, gap(8));
        }
        return card;
    }

    private LinearLayout.LayoutParams gap(int top) {
        LinearLayout.LayoutParams p = full();
        p.topMargin = dp(this, top);
        return p;
    }
}
