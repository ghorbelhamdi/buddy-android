package app.buddy.assistant;

import android.content.Intent;
import android.os.Bundle;
import android.text.Editable;
import android.text.SpannableStringBuilder;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static app.buddy.assistant.Ui.*;

/**
 * Chats → the search icon: finds chats by their title and by what was said in them (the user's messages and the
 * agents' answers; archived chats too). Each result shows where the words are, and opens its chat. Everything is
 * searched here on the phone, in the chats Buddy already holds.
 */
public class SearchActivity extends Screen {
    private EditText field;
    private LinearLayout results;
    private ImageButton clear;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = column(this);
        root.setBackgroundColor(BG);
        LinearLayout top = row(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPaddingRelative(dp(this, 4), dp(this, 4), dp(this, 8), dp(this, 4));
        ImageButton back = iconButton(this, R.drawable.ms_arrow_back, getString(R.string.perms_back));
        back.setLayoutParams(new LinearLayout.LayoutParams(dp(this, 44), dp(this, 44)));
        back.setOnClickListener(v -> finish());
        top.addView(back);
        field = new EditText(this);
        field.setHint(R.string.search_hint);
        field.setHintTextColor(MUTED);
        field.setTextColor(TEXT);
        field.setTextSize(16);
        field.setTypeface(sans(this, 400));
        field.setSingleLine(true);
        field.setBackground(null);
        field.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        field.setPaddingRelative(dp(this, 6), dp(this, 10), dp(this, 6), dp(this, 10));
        top.addView(field, weight1());
        clear = iconButton(this, R.drawable.ms_close, getString(R.string.search_clear));
        clear.setLayoutParams(new LinearLayout.LayoutParams(dp(this, 44), dp(this, 44)));
        clear.setVisibility(View.GONE);
        clear.setOnClickListener(v -> field.setText(""));
        top.addView(clear);
        root.addView(top, full());
        ScrollView sv = new ScrollView(this);
        results = column(this);
        results.setPaddingRelative(0, dp(this, 4), 0, dp(this, 32));
        sv.addView(results);
        root.addView(sv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(BG);
        frame.setFitsSystemWindows(true);
        frame.addView(root);
        setContentView(protect(frame));
        field.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                render();
            }
        });
        if (state == null) {
            field.requestFocus();
            getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
                    | android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
    }

    /** A chat that matches: where (its title, or the message that has the words), and how many of its messages do. */
    private static final class Hit {
        Sessions.Session chat;
        String text = "";
        boolean mine;
        int count;
        /** Where in the chat that message is (-1: only the title matches). */
        int at = -1;
    }

    private void render() {
        String q = field.getText().toString().trim();
        clear.setVisibility(q.isEmpty() ? View.GONE : View.VISIBLE);
        results.removeAllViews();
        if (q.isEmpty()) {
            results.addView(note(getString(R.string.search_info)), full());
            return;
        }
        Locale loc = Ui.locale(this);
        String needle = q.toLowerCase(loc);
        List<Hit> hits = new ArrayList<>();
        for (Sessions.Session s : ChatHub.get(this).sessions.all) {
            if (s.messages.isEmpty() || s.incognito) continue;
            Hit h = new Hit();
            h.chat = s;
            boolean inTitle = s.label().toLowerCase(loc).contains(needle);
            for (int i = s.messages.size() - 1; i >= 0; i--) { // the newest message that has it is the one shown
                Sessions.Msg m = s.messages.get(i);
                if (m.kind != Bubble.Kind.USER && m.kind != Bubble.Kind.BUDDY) continue;
                if (!m.text.toLowerCase(loc).contains(needle)) continue;
                if (h.count++ == 0) {
                    h.at = i;
                    h.text = m.text;
                    h.mine = m.kind == Bubble.Kind.USER;
                }
            }
            if (inTitle || h.count > 0) hits.add(h);
        }
        java.util.Collections.sort(hits, (a, b) -> Long.compare(b.chat.updated, a.chat.updated));
        if (hits.isEmpty()) {
            results.addView(note(getString(R.string.search_none, q)), full());
            return;
        }
        results.addView(sectionLabel(this, getString(R.string.search_found, hits.size())));
        LinearLayout g = group(this);
        for (Hit h : hits) addToGroup(this, g, hitRow(h, needle, loc));
        results.addView(g, inset(this));
    }

    private TextView note(String s) {
        TextView t = text(this, s, 14, MUTED);
        t.setPaddingRelative(dp(this, 24), dp(this, 24), dp(this, 24), 0);
        return t;
    }

    private View hitRow(final Hit h, String needle, Locale loc) {
        LinearLayout r = listRow(this);
        r.setPaddingRelative(dp(this, 14), dp(this, 14), dp(this, 14), dp(this, 14));
        r.addView(agentTile(this, h.chat.backend));
        LinearLayout col = column(this);
        col.setPaddingRelative(dp(this, 14), 0, 0, 0);
        TextView title = single(title(this, "", 15));
        title.setText(marked(h.chat.label(), needle, loc));
        col.addView(title);
        String meta = Account.nameOf(h.chat.backend) + (h.chat.archived ? " · " + getString(R.string.search_archived) : "")
                + (h.count > 1 ? " · " + getString(R.string.search_messages, h.count) : "");
        col.addView(single(text(this, meta, 12, MUTED)));
        if (h.count > 0) {
            String around = around(Ui.linkText(h.text).replace('\n', ' '), needle, loc);
            TextView t = text(this, "", 14, TEXT2);
            t.setText(marked(h.mine ? getString(R.string.main_chat_last_you, around) : around, needle, loc));
            t.setLineSpacing(0, 1.1f);
            t.setPaddingRelative(0, dp(this, 4), 0, 0);
            col.addView(t);
        }
        r.addView(col, weight1());
        r.setOnClickListener(v -> {
            ChatHub.get(this).select(h.chat);
            startActivity(new Intent(this, ChatActivity.class).putExtra("id", h.chat.id).putExtra("find", h.at));
        });
        return r;
    }

    /** The part of a message around the words: some of what comes before, and what follows. */
    private static String around(String text, String needle, Locale loc) {
        int at = text.toLowerCase(loc).indexOf(needle);
        if (at < 0) return text.length() > 240 ? text.substring(0, 240) + "…" : text;
        int from = Math.max(0, at - 60), to = Math.min(text.length(), at + needle.length() + 180);
        if (from > 0) { // start on a word
            int sp = text.indexOf(' ', from);
            if (sp > 0 && sp < at) from = sp + 1;
        }
        return (from > 0 ? "…" : "") + text.substring(from, to) + (to < text.length() ? "…" : "");
    }

    /** The text with every place the words appear in the accent colour, bold. */
    private static CharSequence marked(String text, String needle, Locale loc) {
        SpannableStringBuilder b = new SpannableStringBuilder(text);
        String low = text.toLowerCase(loc);
        if (low.length() != text.length()) return b; // a letter that changes length in lower case: no marks, same text
        for (int at = low.indexOf(needle); at >= 0; at = low.indexOf(needle, at + needle.length())) {
            b.setSpan(new android.text.style.ForegroundColorSpan(ACCENT_TEXT), at, at + needle.length(), 0);
            b.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), at, at + needle.length(), 0);
        }
        return b;
    }
}
