package app.buddy.assistant;

import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import static app.buddy.assistant.Ui.*;

/**
 * Settings → Tools and connections → Tools: every phone tool, grouped, each with a switch (see ToolSwitch). With a chat (Chat
 * options → Tools) the same list for that chat alone: a switch there is the chat's own choice, the others follow
 * Settings.
 */
public class ToolsActivity extends Screen {
    static final String EXTRA_CHAT = "chat";
    private LinearLayout body;
    /** The chat whose tools these are, or null for Settings. */
    private Sessions.Session chat;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = column(this);
        root.setBackgroundColor(BG);
        LinearLayout top = row(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPaddingRelative(dp(this, 4), dp(this, 4), dp(this, 16), dp(this, 4));
        ImageButton back = iconButton(this, R.drawable.ms_arrow_back, getString(R.string.perms_back));
        back.setLayoutParams(new LinearLayout.LayoutParams(dp(this, 44), dp(this, 44)));
        back.setOnClickListener(v -> finish());
        top.addView(back);
        String id = getIntent().getStringExtra(EXTRA_CHAT);
        if (id != null) {
            chat = ChatHub.get(this).sessions.find(id);
            if (chat == null) { // deleted meanwhile
                finish();
                return;
            }
        }
        TextView t = title(this, getString(chat == null ? R.string.tools_title : R.string.tools_chat_title), 18);
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
        setContentView(protect(frame)); // the user's own: an agent must never turn a tool back on
        build();
    }

    private void build() {
        body.removeAllViews();
        body.addView(text(this, getString(chat == null ? R.string.tools_intro : R.string.tools_chat_intro), 13, MUTED),
                full());
        if (chat != null && !chat.tools.isEmpty()) { // back to what Settings says, for every tool
            android.widget.Button same = outlinedButton(this, getString(R.string.tools_chat_reset), TEXT);
            same.setOnClickListener(v -> {
                ChatHub.get(this).clearTools(chat);
                build();
            });
            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                    dp(this, 40));
            sp.topMargin = dp(this, 12);
            body.addView(same, sp);
        }
        for (String[] g : ToolSwitch.GROUPS) {
            TextView label = sectionLabel(this, str("tools_group_" + g[0], g[0]));
            label.setPaddingRelative(0, dp(this, 20), 0, dp(this, 8));
            body.addView(label);
            if ("browser".equals(g[0]) && !Browser.available(this)) {
                TextView note = text(this, getString(R.string.tools_browser_off), 12, MUTED);
                note.setPaddingRelative(dp(this, 4), 0, dp(this, 4), dp(this, 8));
                body.addView(note, full());
            }
            LinearLayout box = group(this);
            for (int i = 1; i < g.length; i++) addToGroup(this, box, toolRow(g[i]), 16);
            if ("info".equals(g[0]) && chat == null) { // codes in SMS: hidden unless the user wants them (issue #39)
                addToGroup(this, box, switchRow(this, getString(R.string.tools_sms_codes),
                        getString(R.string.tools_sms_codes_info), Prefs.smsCodes(this), on -> Prefs.setSmsCodes(this, on)), 16);
            }
            body.addView(box, full());
        }
    }

    private View toolRow(String tool) {
        boolean locked = ToolSwitch.LOCKED.contains(tool);
        LinearLayout r = switchRow(this, str("tool_" + tool, tool), str("tool_" + tool + "_info", ""),
                ToolSwitch.on(this, tool, chat), on -> {
                    save(tool, on);
                });
        // the name agents call it by, for whoever reads their steps; and in a chat, whether the choice is its own
        boolean own = chat != null && chat.tools.containsKey(tool);
        TextView name = monoText(this, own ? tool + " · " + getString(R.string.tools_chat_own) : tool, 11,
                own ? ACCENT_TEXT : MUTED);
        name.setPaddingRelative(0, dp(this, 4), 0, 0);
        ((LinearLayout) r.getChildAt(0)).addView(name);
        if (locked) { // the approval cards: always there
            r.getChildAt(1).setAlpha(0.5f);
            r.setOnClickListener(v -> Toast.makeText(this, R.string.tools_always_on, Toast.LENGTH_SHORT).show());
        }
        return r;
    }

    private void save(String tool, boolean on) {
        if (chat == null) {
            ToolSwitch.set(this, tool, on);
            return;
        }
        // the chat's own choice; one that matches Settings again isn't kept as one
        ChatHub.get(this).setTool(chat, tool, on == ToolSwitch.on(this, tool) ? null : on);
        build();
    }

    /** tools_group_screen, tool_tap, tool_tap_info: named after the tool (no build step shrinks resources away). */
    private String str(String name, String fallback) {
        int id = getResources().getIdentifier(name, "string", getPackageName());
        return id == 0 ? fallback : getString(id);
    }
}
