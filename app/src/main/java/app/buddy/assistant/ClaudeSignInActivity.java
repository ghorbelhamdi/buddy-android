package app.buddy.assistant;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import static app.buddy.assistant.Ui.*;

/** Install Claude Code in Buddy's built-in Linux and sign in: one button, approve in the browser, done. */
public class ClaudeSignInActivity extends Screen implements ClaudeAccount.Listener {
    private LinearLayout body, bottom;
    private String openedUrl;
    private boolean pasteOpen;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = column(this);
        root.setBackgroundColor(BG);
        root.setFitsSystemWindows(true);
        LinearLayout top = row(this);
        top.setPadding(dp(this, 4), dp(this, 4), dp(this, 16), dp(this, 4));
        ImageButton back = iconButton(this, R.drawable.ms_arrow_back, "Back");
        back.setOnClickListener(v -> finish());
        top.addView(back);
        root.addView(top, full());
        ScrollView scroll = new ScrollView(this);
        body = column(this);
        body.setPadding(dp(this, 24), dp(this, 8), dp(this, 24), dp(this, 24));
        scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        bottom = column(this);
        bottom.setPadding(dp(this, 20), dp(this, 8), dp(this, 20), dp(this, 16));
        root.addView(bottom, full());
        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (isFinishing()) return;
        ClaudeAccount.addListener(this);
        if (ClaudeAccount.step == ClaudeAccount.Step.CHECKING || ClaudeAccount.step == ClaudeAccount.Step.SIGNED_OUT
                || ClaudeAccount.step == ClaudeAccount.Step.SIGNED_IN || ClaudeAccount.step == ClaudeAccount.Step.NEED_INSTALL) {
            ClaudeAccount.refresh(this);
        }
        render();
    }

    @Override
    protected void onPause() {
        ClaudeAccount.removeListener(this);
        super.onPause();
    }

    @Override
    public void onAccountChanged() {
        if (isFinishing() || isDestroyed()) return;
        render();
    }

    /** Coming back from the browser with the code copied: use it. */
    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (!hasFocus || !ClaudeAccount.waitingForCode()) return;
        try {
            ClipData clip = getSystemService(ClipboardManager.class).getPrimaryClip();
            if (clip != null && clip.getItemCount() > 0) {
                String code = ClaudeAccount.findCode(clip.getItemAt(0).coerceToText(this));
                if (code != null) ClaudeAccount.submit(code);
            }
        } catch (Exception ignored) {
        }
    }

    private void render() {
        body.removeAllViews();
        bottom.removeAllViews();
        switch (ClaudeAccount.step) {
            case CHECKING:
                header(R.drawable.ms_terminal, "Claude Code in Buddy", "Checking…", false);
                spinnerRow();
                break;
            case NEED_INSTALL:
                header(R.drawable.ms_terminal, "Claude Code in Buddy",
                        "Runs Claude Code inside Buddy on your Claude subscription. No Termux needed.", false);
                note("Downloads about 235 MB once: a small Linux (4 MB) and Claude Code from Anthropic. Use Wi-Fi if you can.");
                primary("Install Claude Code", v -> ClaudeAccount.install(this));
                break;
            case INSTALLING:
                header(R.drawable.ms_terminal, "Installing", ClaudeAccount.detail, false);
                spinnerRow();
                note("This takes a few minutes. You can leave this screen; it keeps going.");
                break;
            case SIGNED_OUT:
                header(R.drawable.ms_shield_person, "Sign in to Claude",
                        "Use your Claude account (Pro or Max). Your browser opens; approve there and Buddy comes back by itself.", false);
                primary("Sign in with Claude", v -> ClaudeAccount.startLogin(this));
                break;
            case WAITING_BROWSER:
                if (ClaudeAccount.url == null) {
                    header(R.drawable.ms_shield_person, "Sign in to Claude", "Starting sign-in…", false);
                    spinnerRow();
                    break;
                }
                openBrowserOnce();
                header(R.drawable.ms_shield_person, "Approve in your browser",
                        "Tap Authorize on the Claude page. Buddy picks up the code and comes back by itself.", false);
                primary("Open the sign-in page again", v -> {
                    openedUrl = null;
                    openBrowserOnce();
                });
                pasteBox();
                secondary("Cancel", v -> ClaudeAccount.cancelLogin());
                break;
            case FINISHING:
                header(R.drawable.ms_shield_person, "Signing in", "Finishing sign-in…", false);
                spinnerRow();
                break;
            case SIGNED_IN:
                header(R.drawable.ms_check, "Signed in", (ClaudeAccount.email.isEmpty() ? "Claude Code is ready."
                        : ClaudeAccount.email) + (ClaudeAccount.plan.isEmpty() ? "" : " · " + ClaudeAccount.plan), true);
                primary("Done", v -> finish());
                secondary("Sign out", v -> confirmSheet(this, "Sign out of Claude?",
                        "Claude Code in Buddy stops working until you sign in again.", "Sign out", true, () ->
                                new Thread(() -> {
                                    try {
                                        Linux.exec(this, "claude auth logout", 60);
                                    } catch (Exception ignored) {
                                    }
                                    ClaudeAccount.refresh(this);
                                }).start()));
                break;
            case ERROR:
                header(R.drawable.ms_error, "Something went wrong", ClaudeAccount.detail, false);
                primary("Try again", v -> {
                    ClaudeAccount.step = ClaudeAccount.Step.CHECKING;
                    ClaudeAccount.refresh(this);
                    render();
                });
                break;
        }
    }

    private void openBrowserOnce() {
        String u = ClaudeAccount.url;
        if (u == null || u.equals(openedUrl)) return;
        openedUrl = u;
        BuddyService svc = BuddyService.get();
        if (svc != null) svc.watchForLoginCode();
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(u)));
        } catch (Exception e) {
            Toast.makeText(this, "No browser found to open the sign-in page.", Toast.LENGTH_LONG).show();
        }
    }

    private void pasteBox() {
        LinearLayout c = column(this);
        LinearLayout head = row(this);
        head.setPadding(0, dp(this, 10), 0, dp(this, 10));
        head.setBackgroundResource(ripple(this, false));
        TextView h = title(this, "Paste the code instead", 14);
        h.setTextColor(ACCENT_TEXT);
        head.addView(h, weight1());
        head.addView(icon(this, pasteOpen ? R.drawable.ms_expand_less : R.drawable.ms_expand_more, ACCENT_TEXT, 20));
        head.setOnClickListener(v -> {
            pasteOpen = !pasteOpen;
            render();
        });
        c.addView(head, full());
        if (pasteOpen) {
            c.addView(text(this, "If Buddy doesn't come back by itself: tap Copy Code on the Claude page, then paste it here.",
                    14, MUTED));
            LinearLayout r = row(this);
            r.setPadding(0, dp(this, 10), 0, 0);
            EditText et = new EditText(this);
            et.setHint("code");
            et.setHintTextColor(MUTED);
            et.setTextColor(TEXT);
            et.setTypeface(mono(this, 400));
            et.setTextSize(14);
            et.setSingleLine(true);
            et.setPadding(dp(this, 12), dp(this, 10), dp(this, 12), dp(this, 10));
            et.setBackground(outlined(this, BG, 12, OUTLINE, 1));
            r.addView(et, weight1());
            Button go = button(this, "Use", true);
            LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(this, 44));
            gp.leftMargin = dp(this, 8);
            r.addView(go, gp);
            go.setOnClickListener(v -> {
                if (!ClaudeAccount.submit(et.getText().toString())) {
                    Toast.makeText(this, "Start the sign-in again first.", Toast.LENGTH_SHORT).show();
                }
            });
            c.addView(r, full());
        }
        LinearLayout.LayoutParams p = full();
        p.topMargin = dp(this, 18);
        body.addView(c, p);
    }

    // ------------------------------------------------------------ pieces

    private void header(int iconRes, String title, String sub, boolean ok) {
        FrameLayout ic = new FrameLayout(this);
        ic.setBackground(oval(ok ? Theme.withAlpha(OK, 0x2E) : ACCENT_SOFT));
        ic.addView(icon(this, iconRes, ok ? OK : ACCENT_TEXT, 28),
                new FrameLayout.LayoutParams(dp(this, 28), dp(this, 28), Gravity.CENTER));
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(this, 56), dp(this, 56));
        ip.topMargin = dp(this, 8);
        body.addView(ic, ip);
        TextView t = title(this, title, 26);
        t.setPadding(0, dp(this, 18), 0, dp(this, 6));
        body.addView(t);
        body.addView(text(this, sub, 16, MUTED));
    }

    private void spinnerRow() {
        LinearLayout r = row(this);
        r.setPadding(0, dp(this, 20), 0, 0);
        r.addView(spinner(this, 22));
        body.addView(r, full());
    }

    private void note(String s) {
        TextView t = text(this, s, 14, MUTED);
        LinearLayout.LayoutParams p = full();
        p.topMargin = dp(this, 14);
        body.addView(t, p);
    }

    private void primary(String label, View.OnClickListener l) {
        Button b = button(this, label, true);
        b.setOnClickListener(l);
        bottom.addView(b, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(this, 52)));
    }

    private void secondary(String label, View.OnClickListener l) {
        Button b = button(this, label, false);
        b.setBackgroundResource(ripple(this, false));
        b.setTextColor(TEXT);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(this, 52));
        p.topMargin = dp(this, 4);
        bottom.addView(b, p);
    }
}
