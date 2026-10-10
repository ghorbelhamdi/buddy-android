package app.buddy.assistant;

import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import static app.buddy.assistant.Ui.*;

/** Read the last messages of a Claude Code / Codex session that isn't a Buddy chat, and optionally continue it. */
public class TranscriptActivity extends Screen implements ChatHub.MonitorListener {
    private ChatHub hub;
    private LinearLayout messages;
    private ScrollView scroll;
    private String file, agent, id, title;
    private int maxBubble;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        hub = ChatHub.get(this);
        file = getIntent().getStringExtra("file");
        agent = getIntent().getStringExtra("agent");
        id = getIntent().getStringExtra("id");
        title = getIntent().getStringExtra("title");
        String cwd = getIntent().getStringExtra("cwd");
        maxBubble = (int) (getResources().getDisplayMetrics().widthPixels * 0.82f);

        LinearLayout root = column(this);
        root.setBackgroundColor(BG);

        LinearLayout top = row(this);
        top.setPadding(dp(this, 4), dp(this, 4), dp(this, 16), dp(this, 4));
        ImageButton back = iconButton(this, R.drawable.ms_arrow_back, "Back");
        back.setLayoutParams(new LinearLayout.LayoutParams(dp(this, 44), dp(this, 44)));
        back.setOnClickListener(v -> finish());
        top.addView(back);
        LinearLayout titles = column(this);
        titles.setPadding(dp(this, 4), 0, 0, 0);
        titles.addView(single(title(this, title, 16)));
        titles.addView(single(monoText(this, ("codex".equals(agent) ? "codex" : "claude-code")
                + (cwd != null && !cwd.isEmpty() ? " · " + cwd : ""), 12, MUTED)));
        top.addView(titles, weight1());
        root.addView(top, full());
        View line = new View(this);
        line.setBackgroundColor(LINE);
        root.addView(line, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(this, 1)));

        scroll = new ScrollView(this);
        messages = column(this);
        messages.setPadding(dp(this, 16), dp(this, 8), dp(this, 16), dp(this, 12));
        messages.addView(note("Loading…"), full());
        scroll.addView(messages);
        root.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        boolean canContinue = id != null && id.matches("[A-Za-z0-9-]{8,64}");
        if (canContinue) {
            LinearLayout cont = row(this);
            cont.setGravity(Gravity.CENTER);
            cont.setBackground(rounded(this, ACCENT, 14));
            cont.addView(icon(this, R.drawable.ms_forum, ON_ACCENT, 20));
            TextView ct = title(this, "Continue in Buddy", 15);
            ct.setTextColor(ON_ACCENT);
            ct.setPadding(dp(this, 8), 0, 0, 0);
            cont.addView(ct);
            cont.setOnClickListener(v -> {
                Sessions.Session s = hub.adopt(agent, id, title);
                hub.select(s);
                startActivity(new Intent(this, ChatActivity.class).putExtra("id", s.id));
                finish();
            });
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(this, 52));
            cp.setMargins(dp(this, 16), dp(this, 8), dp(this, 16), dp(this, 12));
            root.addView(cont, cp);
        }
        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(BG);
        frame.setFitsSystemWindows(true);
        frame.addView(root);
        setContentView(frame);
    }

    private TextView note(String s) {
        TextView t = monoText(this, s, 12, MUTED);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, dp(this, 12), 0, dp(this, 4));
        return t;
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (isFinishing()) return;
        hub.monitor = this;
        try {
            Helper.run(this, "transcript", agent, file);
        } catch (Exception e) {
            messages.removeAllViews();
            messages.addView(text(this, e.getMessage(), 14, ERR));
        }
    }

    @Override
    protected void onPause() {
        if (hub.monitor == this) hub.monitor = null;
        super.onPause();
    }

    @Override
    public void onMonitor(JSONObject ev) {
        if (!"buddy_transcript".equals(ev.optString("type"))) return;
        messages.removeAllViews();
        JSONArray list = ev.optJSONArray("messages");
        if (list == null || list.length() == 0) {
            messages.addView(note("No readable messages in this session."), full());
            return;
        }
        messages.addView(note("last " + list.length() + " messages · read-only"), full());
        for (int i = 0; i < list.length(); i++) {
            JSONObject m = list.optJSONObject(i);
            if (m == null) continue;
            boolean user = "user".equals(m.optString("role"));
            TextView tv = text(this, m.optString("text"), 15, TEXT);
            tv.setTextIsSelectable(true);
            tv.setPadding(dp(this, 14), dp(this, 10), dp(this, 14), dp(this, 10));
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            p.topMargin = dp(this, 12);
            if (user) {
                tv.setMaxWidth(maxBubble);
                tv.setBackground(corners(this, USER, 20, 20, 6, 20));
                p.gravity = Gravity.END;
                messages.addView(tv, p);
            } else {
                LinearLayout r = row(this);
                r.setGravity(Gravity.BOTTOM);
                FrameLayout av = new FrameLayout(this);
                av.setBackground(oval(ACCENT_SOFT));
                ImageView face = new ImageView(this);
                face.setImageResource(R.drawable.bubble_face);
                av.addView(face, new FrameLayout.LayoutParams(dp(this, 20), dp(this, 20), Gravity.CENTER));
                LinearLayout.LayoutParams avp = new LinearLayout.LayoutParams(dp(this, 24), dp(this, 24));
                avp.rightMargin = dp(this, 8);
                r.addView(av, avp);
                tv.setMaxWidth(maxBubble - dp(this, 32));
                tv.setBackground(corners(this, CARD, 20, 20, 20, 6));
                r.addView(tv);
                messages.addView(r, p);
            }
        }
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }
}
