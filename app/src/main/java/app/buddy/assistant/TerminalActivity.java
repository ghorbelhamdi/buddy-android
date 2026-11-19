package app.buddy.assistant;

import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import static app.buddy.assistant.Ui.*;

/**
 * Terminals for Buddy's Linux, as tabs. Shells keep running when you leave; come back to them any time.
 */
public class TerminalActivity extends Screen implements TermView.Callbacks {
    private TermView term;
    private TextView titleView, subView, endedText;
    private View statusDot;
    private LinearLayout ended, tabs;
    private HorizontalScrollView tabScroll;
    private TextView ctrlKey, altKey;
    private TermSession session;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        TermColors.apply(DARK, ACCENT);
        int bg = TermColors.DEFAULT_BG, fg = TermColors.DEFAULT_FG;
        getWindow().setStatusBarColor(bg);
        getWindow().setNavigationBarColor(bg);
        if (Prefs.termAwake(this)) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        LinearLayout root = column(this);
        root.setBackgroundColor(bg);
        root.setFitsSystemWindows(true);

        // top bar: back · title + status · keyboard · menu
        LinearLayout bar = row(this);
        bar.setPaddingRelative(dp(this, 4), dp(this, 4), dp(this, 4), 0);
        ImageButton back = iconButton(this, R.drawable.ms_arrow_back, getString(R.string.terminal_back), fg);
        back.setLayoutParams(new LinearLayout.LayoutParams(dp(this, 44), dp(this, 44)));
        back.setOnClickListener(v -> finish());
        bar.addView(back);
        LinearLayout titles = column(this);
        titles.setPaddingRelative(dp(this, 4), 0, dp(this, 4), 0);
        titleView = single(title(this, getString(R.string.terminal_title), 16));
        titleView.setTextColor(fg);
        titles.addView(titleView);
        LinearLayout sub = row(this);
        statusDot = dot(this, OK, 7);
        sub.addView(statusDot);
        subView = single(monoText(this, "buddy linux · alpine", 12, Theme.withAlpha(fg, 0x99)));
        subView.setPaddingRelative(dp(this, 6), 0, 0, 0);
        sub.addView(subView);
        titles.addView(sub);
        bar.addView(titles, weight1());
        ImageButton kb = iconButton(this, R.drawable.ms_keyboard_hide, getString(R.string.terminal_hide_keyboard), fg);
        kb.setLayoutParams(new LinearLayout.LayoutParams(dp(this, 44), dp(this, 44)));
        kb.setOnClickListener(v -> term.hideKeyboard());
        bar.addView(kb);
        ImageButton menu = iconButton(this, R.drawable.ms_more_vert, getString(R.string.terminal_menu), fg);
        menu.setLayoutParams(new LinearLayout.LayoutParams(dp(this, 44), dp(this, 44)));
        menu.setOnClickListener(v -> menu());
        bar.addView(menu);
        root.addView(bar, full());

        // tabs: one per shell, plus "+"
        tabScroll = new HorizontalScrollView(this);
        tabScroll.setHorizontalScrollBarEnabled(false);
        tabs = row(this);
        tabs.setPaddingRelative(dp(this, 12), dp(this, 6), dp(this, 12), dp(this, 8));
        tabScroll.addView(tabs);
        root.addView(tabScroll, full());
        View line = new View(this);
        line.setBackgroundColor(Theme.withAlpha(fg, 0x14));
        root.addView(line, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(this, 1)));

        term = new TermView(this);
        root.addView(term, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        // shown when the shell in front has exited
        ended = row(this);
        ended.setPaddingRelative(dp(this, 16), dp(this, 10), dp(this, 10), dp(this, 10));
        ended.setBackgroundColor(Theme.withAlpha(fg, 0x0F));
        endedText = text(this, getString(R.string.terminal_session_ended), 14, fg);
        ended.addView(endedText, weight1());
        android.widget.Button close = button(this, getString(R.string.terminal_close), false);
        close.setOnClickListener(v -> closeSession(session));
        ended.addView(close, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(this, 38)));
        android.widget.Button again = button(this, getString(R.string.terminal_restart), true);
        again.setOnClickListener(v -> {
            TermSession old = session;
            newSession();
            if (old != null) {
                old.finish();
                renderTabs();
            }
        });
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(this, 38));
        ap.setMarginStart(dp(this, 8));
        ended.addView(again, ap);
        ended.setVisibility(View.GONE);
        root.addView(ended, full());

        root.addView(extraKeys(), full());
        root.setLayoutDirection(View.LAYOUT_DIRECTION_LTR); // a terminal stays left to right in every language
        setContentView(root);

        if (!Linux.installed(this)) {
            Toast.makeText(this, R.string.terminal_needs_linux, Toast.LENGTH_LONG).show();
            startActivity(new Intent(this, SetupActivity.class));
            finish();
            return;
        }
        // opened to run something (e.g. "opencode auth login"): always in a new tab
        String run = getIntent().getStringExtra("run");
        TermSession s = TermSession.current;
        if (run != null) term.post(() -> newSession(run));
        else if (s == null) term.post(this::newSession);
        else show(s);
    }

    // ---------------------------------------------------------------- sessions

    /** Listens to one session; only the one in front draws, but every tab's title and state update. */
    private final class Watch implements TermSession.Listener {
        final TermSession s;

        Watch(TermSession s) {
            this.s = s;
        }

        @Override
        public void onScreenChanged() {
            if (s == session) term.invalidate();
        }

        @Override
        public void onTitleChanged(String title) {
            if (s == session) titleView.setText(s.label());
            renderTabs();
        }

        @Override
        public void onExited(int code) {
            if (s == session) showEnded();
            renderTabs();
        }

        @Override
        public void onBell() {
            if (s == session) term.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
        }
    }

    private void newSession() {
        newSession(null);
    }

    private void newSession(String run) {
        int[] g = term.gridFor(term.getWidth() > 0 ? term.getWidth() : getResources().getDisplayMetrics().widthPixels,
                term.getHeight() > 0 ? term.getHeight() : getResources().getDisplayMetrics().heightPixels / 2);
        TermSession s;
        try {
            s = TermSession.start(this, g[0], g[1], run);
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.terminal_start_failed, e.getMessage()), Toast.LENGTH_LONG).show();
            return;
        }
        show(s);
        term.showKeyboard();
    }

    private void show(TermSession s) {
        session = s;
        TermSession.current = s;
        for (TermSession t : TermSession.all) t.setListener(new Watch(t));
        term.clearSelection();
        term.attach(s, this);
        titleView.setText(s.label());
        if (s.exited) showEnded();
        else {
            ended.setVisibility(View.GONE);
            statusDot.setBackground(oval(OK));
        }
        renderTabs();
    }

    private void showEnded() {
        statusDot.setBackground(oval(MUTED));
        endedText.setText(session != null && session.exitCode != 0
                ? getString(R.string.terminal_session_ended_code, session.exitCode) : getString(R.string.terminal_session_ended));
        ended.setVisibility(View.VISIBLE);
        term.invalidate();
    }

    private void closeSession(TermSession s) {
        if (s == null) return;
        s.finish();
        if (TermSession.all.isEmpty()) {
            finish();
            return;
        }
        show(TermSession.all.get(TermSession.all.size() - 1));
    }

    private void renderTabs() {
        tabs.removeAllViews();
        int fg = TermColors.DEFAULT_FG;
        for (final TermSession s : TermSession.all) {
            boolean on = s == session;
            LinearLayout tab = row(this);
            tab.setPaddingRelative(dp(this, 12), 0, dp(this, 12), 0);
            tab.setBackground(rounded(this, on ? Theme.withAlpha(TermColors.CURSOR, 0x33) : Theme.withAlpha(fg, 0x0F), 10));
            if (!s.exited) {
                View d = dot(this, on ? TermColors.CURSOR : OK, 6);
                ((LinearLayout.LayoutParams) d.getLayoutParams()).setMarginEnd(dp(this, 7));
                tab.addView(d);
            }
            TextView t = single(monoText(this, s.label(), 12, s.exited ? Theme.withAlpha(fg, 0x66) : fg));
            t.setMaxWidth(dp(this, 150));
            if (on) t.setTypeface(mono(this, 600));
            tab.addView(t);
            tab.setOnClickListener(v -> {
                if (s != session) show(s);
            });
            tab.setOnLongClickListener(v -> {
                confirmSheet(this, getString(R.string.terminal_close_title, s.label()), s.exited ? null
                        : getString(R.string.terminal_close_running), getString(R.string.terminal_close), true,
                        () -> closeSession(s));
                return true;
            });
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(this, 32));
            p.setMarginEnd(dp(this, 6));
            tabs.addView(tab, p);
        }
        ImageButton add = iconButton(this, R.drawable.ms_add, getString(R.string.terminal_new_session), fg);
        add.setBackground(rounded(this, Theme.withAlpha(fg, 0x0F), 10));
        tabs.addView(add, new LinearLayout.LayoutParams(dp(this, 36), dp(this, 32)));
        add.setOnClickListener(v -> newSession());
        tabScroll.post(() -> {
            View cur = tabs.getChildAt(Math.max(0, TermSession.all.indexOf(session)));
            if (cur != null) tabScroll.smoothScrollTo(Math.max(0, cur.getLeft() - dp(this, 24)), 0);
        });
        int n = TermSession.all.size();
        subView.setText(n > 1 ? getResources().getQuantityString(R.plurals.terminal_sessions_count, n, n) : "buddy linux · alpine");
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (session != null && !session.exited) term.requestFocus();
        // the phone's microphone for Claude Code's /voice in this terminal; only while it's on screen
        MicServer.start(this, new MicServer.Host() {
            @Override
            public void needMicPermission() {
                runOnUiThread(() -> Voice.askMic(TerminalActivity.this, 7));
            }

            @Override
            public void onListening(boolean on) {
                runOnUiThread(() -> {
                    int n = TermSession.all.size();
                    subView.setText(on ? getString(R.string.terminal_listening) : (n > 1
                            ? getResources().getQuantityString(R.plurals.terminal_sessions_count, n, n) : "buddy linux · alpine"));
                    statusDot.setBackground(oval(on ? ERR : (session != null && session.exited ? MUTED : OK)));
                });
            }
        });
    }

    @Override
    protected void onPause() {
        MicServer.stop();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        for (TermSession t : TermSession.all) t.setListener(null);
        super.onDestroy();
    }

    // ---------------------------------------------------------------- view callbacks

    @Override
    public void onLongPress() {
        menu();
    }

    @Override
    public void onPaste() {
        paste();
    }

    @Override
    public void onModifiersUsed() {
        paintToggle(ctrlKey, false);
        paintToggle(altKey, false);
    }

    // ---------------------------------------------------------------- extra keys

    private View extraKeys() {
        HorizontalScrollView hs = new HorizontalScrollView(this);
        hs.setHorizontalScrollBarEnabled(false);
        hs.setBackgroundColor(Theme.withAlpha(TermColors.DEFAULT_FG, 0x0A));
        LinearLayout keys = row(this);
        keys.setPaddingRelative(dp(this, 8), dp(this, 6), dp(this, 8), dp(this, 6));
        addKey(keys, "esc", v -> term.send("\033"));
        addKey(keys, "tab", v -> term.send("\t"));
        ctrlKey = addKey(keys, "ctrl", v -> {
            term.ctrl = !term.ctrl;
            paintToggle(ctrlKey, term.ctrl);
        });
        altKey = addKey(keys, "alt", v -> {
            term.alt = !term.alt;
            paintToggle(altKey, term.alt);
        });
        int[][] arrows = {{KeyEvent.KEYCODE_DPAD_LEFT, '←'}, {KeyEvent.KEYCODE_DPAD_DOWN, '↓'},
                {KeyEvent.KEYCODE_DPAD_UP, '↑'}, {KeyEvent.KEYCODE_DPAD_RIGHT, '→'}};
        for (int[] a : arrows) addKey(keys, String.valueOf((char) a[1]), v -> special(a[0]));
        addKey(keys, "home", v -> special(KeyEvent.KEYCODE_MOVE_HOME));
        addKey(keys, "end", v -> special(KeyEvent.KEYCODE_MOVE_END));
        addKey(keys, "pgup", v -> special(KeyEvent.KEYCODE_PAGE_UP));
        addKey(keys, "pgdn", v -> special(KeyEvent.KEYCODE_PAGE_DOWN));
        for (String s : new String[]{"|", "/", "-", "~", "_", ":"}) addKey(keys, s, v -> term.type(s));
        TextView paste = addKey(keys, "", v -> paste());
        paste.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ms_content_paste, 0, 0, 0);
        paste.setCompoundDrawableTintList(ColorStateList.valueOf(TermColors.DEFAULT_FG));
        paste.setContentDescription(getString(R.string.terminal_paste));
        hs.addView(keys);
        return hs;
    }

    private void special(int keyCode) {
        String s = term.keyCode(keyCode, false, term.alt, term.ctrl);
        if (s != null) term.send(s);
        if (term.ctrl || term.alt) {
            term.ctrl = term.alt = false;
            onModifiersUsed();
        }
    }

    private TextView addKey(LinearLayout keys, String label, View.OnClickListener l) {
        TextView k = monoText(this, label, 13, TermColors.DEFAULT_FG);
        k.setGravity(Gravity.CENTER);
        k.setMinWidth(dp(this, 40));
        k.setPaddingRelative(dp(this, 12), 0, dp(this, 12), 0);
        k.setBackground(rounded(this, Theme.withAlpha(TermColors.DEFAULT_FG, 0x14), 10));
        k.setForeground(getDrawable(ripple(this, false)));
        k.setOnClickListener(v -> {
            v.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
            l.onClick(v);
        });
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(this, 38));
        p.setMarginEnd(dp(this, 6));
        keys.addView(k, p);
        return k;
    }

    private void paintToggle(TextView k, boolean on) {
        if (k == null) return;
        k.setBackground(rounded(this, on ? TermColors.CURSOR : Theme.withAlpha(TermColors.DEFAULT_FG, 0x14), 10));
        k.setTextColor(on ? ON_ACCENT : TermColors.DEFAULT_FG);
        k.setTypeface(mono(this, on ? 600 : 400));
    }

    // ---------------------------------------------------------------- menu

    private void paste() {
        ClipboardManager cm = getSystemService(ClipboardManager.class);
        ClipData clip = cm.getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) return;
        CharSequence t = clip.getItemAt(0).coerceToText(this);
        if (t != null && session != null && !session.exited) {
            term.scrollToBottom();
            session.paste(t.toString());
        }
    }

    private void menu() {
        LinearLayout c = column(this);
        TextView h = title(this, getString(R.string.terminal_title), 18);
        h.setPaddingRelative(0, 0, 0, dp(this, 6));
        c.addView(h);
        final Dialog[] d = new Dialog[1];
        boolean awake = Prefs.termAwake(this);
        Object[][] items = {
                {R.drawable.ms_terminal, getString(R.string.terminal_new_session), (Runnable) this::newSession},
                {R.drawable.ms_content_paste, getString(R.string.terminal_paste), (Runnable) this::paste},
                {R.drawable.ms_content_copy, getString(R.string.terminal_select_all), (Runnable) () -> term.selectAll()},
                {R.drawable.ms_add, getString(R.string.terminal_larger_text), (Runnable) () -> zoom(1.15f)},
                {R.drawable.ms_expand_more, getString(R.string.terminal_smaller_text), (Runnable) () -> zoom(1 / 1.15f)},
                {R.drawable.ms_build_circle, getString(awake ? R.string.terminal_screen_sleep : R.string.terminal_keep_screen_on),
                        (Runnable) () -> {
                    Prefs.setTermAwake(this, !awake);
                    if (!awake) getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                    else getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
                }},
                {R.drawable.ms_close, getString(R.string.terminal_close_session), (Runnable) () -> confirmSheet(this,
                        getString(R.string.terminal_close_title, session.label()),
                        session.exited ? null : getString(R.string.terminal_close_running),
                        getString(R.string.terminal_close), true, () -> closeSession(session))},
        };
        for (Object[] it : items) {
            LinearLayout r = row(this);
            r.setPaddingRelative(dp(this, 4), dp(this, 11), dp(this, 4), dp(this, 11));
            r.setBackgroundResource(ripple(this, false));
            FrameLayout ic = new FrameLayout(this);
            ic.setBackground(rounded(this, ACCENT_SOFT, 12));
            ImageView iv = icon(this, (Integer) it[0], ACCENT_TEXT, 20);
            ic.addView(iv, new FrameLayout.LayoutParams(dp(this, 20), dp(this, 20), Gravity.CENTER));
            r.addView(ic, new LinearLayout.LayoutParams(dp(this, 36), dp(this, 36)));
            TextView t = title(this, (String) it[1], 15);
            t.setPaddingRelative(dp(this, 14), 0, 0, 0);
            r.addView(t, weight1());
            r.setOnClickListener(v -> {
                d[0].dismiss();
                ((Runnable) it[2]).run();
            });
            c.addView(r, full());
        }
        TextView tip = text(this, getString(R.string.terminal_tips), 13, MUTED);
        tip.setPaddingRelative(0, dp(this, 8), 0, 0);
        c.addView(tip);
        d[0] = bottomSheet(this, c);
        d[0].show();
    }

    private void zoom(float f) {
        term.setFontSize(Math.max(8f, Math.min(30f, term.fontSize() * f)));
        Prefs.setTermFont(this, term.fontSize());
        term.resizeGrid();
    }
}
