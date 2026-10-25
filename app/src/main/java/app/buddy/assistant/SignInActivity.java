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

/**
 * Install an agent (Claude Code, Codex, OpenCode, Antigravity or Grok) in Buddy's built-in Linux and sign in: one
 * button, approve in the browser, done. OpenCode needs no sign-in; its providers are connected in Buddy's terminal.
 */
public class SignInActivity extends Screen implements Account.Listener {
    private LinearLayout body, bottom;
    private Account acc;
    private String openedUrl;
    private boolean pasteOpen;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        acc = Account.of(getIntent().getStringExtra("agent"));
        LinearLayout root = column(this);
        root.setBackgroundColor(BG);
        root.setFitsSystemWindows(true);
        LinearLayout top = row(this);
        top.setPaddingRelative(dp(this, 4), dp(this, 4), dp(this, 16), dp(this, 4));
        ImageButton back = iconButton(this, R.drawable.ms_arrow_back, getString(R.string.signin_back));
        back.setOnClickListener(v -> finish());
        top.addView(back);
        root.addView(top, full());
        ScrollView scroll = new ScrollView(this);
        body = column(this);
        body.setPaddingRelative(dp(this, 24), dp(this, 8), dp(this, 24), dp(this, 24));
        scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        bottom = column(this);
        bottom.setPaddingRelative(dp(this, 20), dp(this, 8), dp(this, 20), dp(this, 16));
        root.addView(bottom, full());
        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (isFinishing()) return;
        Account.addListener(this);
        if (acc.step == Account.Step.CHECKING || acc.step == Account.Step.SIGNED_OUT
                || acc.step == Account.Step.SIGNED_IN || acc.step == Account.Step.NEED_INSTALL) {
            acc.refresh(this);
        }
        render();
    }

    @Override
    protected void onPause() {
        Account.removeListener(this);
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
        if (!hasFocus || !acc.waitingForCode()) return;
        try {
            ClipData clip = getSystemService(ClipboardManager.class).getPrimaryClip();
            if (clip != null && clip.getItemCount() > 0) {
                String code = acc.findCode(clip.getItemAt(0).coerceToText(this));
                if (code != null) acc.submit(code);
            }
        } catch (Exception ignored) {
        }
    }

    private void render() {
        body.removeAllViews();
        bottom.removeAllViews();
        switch (acc.step) {
            case CHECKING:
                header(R.drawable.ms_terminal, acc.name(), getString(R.string.signin_checking), false);
                spinnerRow();
                break;
            case NEED_INSTALL:
                header(R.drawable.ms_terminal, getString(R.string.signin_install, acc.name()), getString(acc.claude()
                        ? R.string.signin_install_sub_claude
                        : acc.opencode() ? R.string.signin_install_sub_opencode
                        : acc.agy() ? R.string.signin_install_sub_agy
                        : acc.grok() ? R.string.signin_install_sub_grok
                        : R.string.signin_install_sub_codex), false);
                note(getString(acc.claude() ? R.string.signin_install_size_claude
                        : acc.opencode() ? R.string.signin_install_size_opencode
                        : acc.agy() ? R.string.signin_install_size_agy
                        : acc.grok() ? R.string.signin_install_size_grok
                        : R.string.signin_install_size_codex));
                primary(getString(R.string.signin_install, acc.name()), v -> acc.install(this));
                break;
            case INSTALLING:
                header(R.drawable.ms_terminal, getString(acc.updating ? R.string.signin_updating : R.string.signin_installing),
                        acc.detail, false);
                spinnerRow();
                note(getString(R.string.signin_install_wait));
                break;
            case SIGNED_OUT:
                if (acc.opencode()) {
                    openCodeReady();
                    break;
                }
                header(R.drawable.ms_shield_person, getString(acc.claude() ? R.string.signin_title_claude
                                : acc.agy() ? R.string.signin_title_google : acc.grok() ? R.string.signin_title_grok
                                : R.string.signin_title_chatgpt),
                        getString(acc.claude() ? R.string.signin_sub_claude : acc.agy() ? R.string.signin_sub_agy
                                : acc.grok() ? R.string.signin_sub_grok : R.string.signin_sub_chatgpt), false);
                primary(getString(acc.claude() ? R.string.signin_with_claude : acc.agy() ? R.string.signin_with_google
                        : acc.grok() ? R.string.signin_with_grok : R.string.signin_with_chatgpt), v -> acc.startLogin(this));
                secondary(getString(acc.pastesCode() || acc.grok() ? R.string.signin_other_device : R.string.signin_with_code), v -> {
                    pasteOpen = true;
                    acc.startLogin(this, true);
                });
                break;
            case WAITING_BROWSER:
                if (acc.url == null) {
                    header(R.drawable.ms_shield_person, getString(R.string.signin_sign_in), getString(R.string.signin_starting), false);
                    spinnerRow();
                    break;
                }
                if (acc.remote) {
                    remoteSignIn();
                    break;
                }
                openBrowserOnce();
                header(R.drawable.ms_shield_person, getString(R.string.signin_approve_title), acc.grok()
                        ? getString(R.string.signin_approve_grok, acc.userCode == null ? "…" : acc.userCode) : getString(acc.claude()
                        ? R.string.signin_approve_claude : acc.agy() ? R.string.signin_approve_agy : R.string.signin_approve_codex), false);
                primary(getString(R.string.signin_open_again), v -> {
                    openedUrl = null;
                    openBrowserOnce();
                });
                if (acc.pastesCode()) pasteBox();
                secondary(getString(android.R.string.cancel), v -> acc.cancelLogin());
                break;
            case FINISHING:
                header(R.drawable.ms_shield_person, getString(R.string.signin_signing_in), getString(R.string.signin_finishing), false);
                spinnerRow();
                break;
            case SIGNED_IN:
                if (acc.opencode()) {
                    openCodeReady();
                    break;
                }
                header(R.drawable.ms_check, getString(R.string.signin_signed_in), (acc.email.isEmpty()
                        ? getString(R.string.signin_agent_ready, acc.name()) : acc.email)
                        + (acc.plan.isEmpty() ? "" : " · " + acc.planLabel()), true);
                versionNote();
                primary(getString(R.string.signin_done), v -> finish());
                updateButton();
                secondary(getString(R.string.signin_sign_out), v -> confirmSheet(this, getString(R.string.signin_sign_out_title, acc.name()),
                        getString(R.string.signin_sign_out_text, acc.name()), getString(R.string.signin_sign_out), true,
                        () -> acc.logout(this)));
                break;
            case ERROR:
                header(R.drawable.ms_error, getString(R.string.signin_error_title), acc.detail, false);
                primary(getString(R.string.signin_try_again), v -> {
                    acc.step = Account.Step.CHECKING;
                    acc.refresh(this);
                    render();
                });
                break;
        }
    }

    /** OpenCode is installed: nothing to sign in to. Providers are connected with `opencode auth login` in the terminal. */
    private void openCodeReady() {
        boolean free = acc.plan.isEmpty() || "free models".equals(acc.plan); // Account's marker, not display text
        header(R.drawable.ms_check, getString(R.string.signin_opencode_ready), free
                ? getString(R.string.signin_opencode_free)
                : getString(R.string.signin_opencode_connected, acc.plan), true);
        note(getString(R.string.signin_opencode_connect_note, "opencode auth login", "opencode auth logout"));
        versionNote();
        primary(getString(R.string.signin_done), v -> finish());
        updateButton();
        secondary(getString(R.string.signin_connect_provider), v -> startActivity(new Intent(this, TerminalActivity.class)
                .putExtra("run", acc.opencode2 ? "opencode auth login --standalone" : "opencode auth login")));
    }

    /** The installed version, and what the last update did. */
    private void versionNote() {
        if (!acc.updateNote.isEmpty()) note(acc.updateNote);
        else if (!acc.version.isEmpty()) note(acc.oldOpencode() ? getString(R.string.signin_version_old_opencode, acc.version)
                : getString(R.string.signin_version, acc.name(), acc.version));
    }

    private void updateButton() {
        secondary(getString(acc.oldOpencode() ? R.string.signin_update_opencode2 : R.string.signin_check_updates),
                v -> acc.update(this));
    }

    /** Sign in on another device: show the link (and Codex's or Grok's code) to use anywhere; nothing opens here. */
    private void remoteSignIn() {
        final String link = acc.url;
        if (acc.pastesCode()) {
            header(R.drawable.ms_shield_person, getString(R.string.signin_other_device),
                    getString(acc.agy() ? R.string.signin_remote_agy : R.string.signin_remote_claude), false);
        } else {
            header(R.drawable.ms_shield_person, getString(acc.grok() ? R.string.signin_other_device : R.string.signin_enter_code),
                    getString(acc.grok() ? R.string.signin_remote_grok : R.string.signin_remote_codex), false);
            TextView code = monoText(this, acc.userCode == null ? "…" : acc.userCode, 34, TEXT);
            code.setTypeface(mono(this, 600));
            code.setLetterSpacing(0.08f);
            code.setTextIsSelectable(true);
            code.setGravity(Gravity.CENTER);
            code.setPaddingRelative(dp(this, 12), dp(this, 18), dp(this, 12), dp(this, 18));
            code.setBackground(rounded(this, CARD, 16));
            LinearLayout.LayoutParams cp = full();
            cp.topMargin = dp(this, 20);
            body.addView(code, cp);
            Button copyCode = button(this, getString(R.string.signin_copy_code), false);
            copyCode.setOnClickListener(v -> copy(acc.userCode, getString(R.string.signin_code_copied)));
            LinearLayout.LayoutParams ccp = wrap();
            ccp.topMargin = dp(this, 8);
            body.addView(copyCode, ccp);
            if (!acc.grok()) { // Codex's code works for 15 minutes; Grok doesn't say how long its code does
                TextView exp = text(this, getString(R.string.signin_code_expiry), 13, MUTED);
                exp.setPaddingRelative(0, dp(this, 6), 0, 0);
                body.addView(exp);
            }
        }
        TextView l = monoText(this, link, 13, ACCENT_TEXT);
        l.setTextIsSelectable(true);
        l.setMaxLines(acc.pastesCode() ? 3 : 2);
        l.setEllipsize(android.text.TextUtils.TruncateAt.END);
        l.setPaddingRelative(dp(this, 12), dp(this, 12), dp(this, 12), dp(this, 12));
        l.setBackground(outlined(this, BG, 12, OUTLINE, 1));
        LinearLayout.LayoutParams lp = full();
        lp.topMargin = dp(this, 20);
        body.addView(l, lp);
        LinearLayout btns = row(this);
        Button copyLink = button(this, getString(R.string.signin_copy_link), false);
        copyLink.setOnClickListener(v -> copy(link, getString(R.string.signin_link_copied)));
        btns.addView(copyLink, wrap());
        Button share = button(this, getString(R.string.signin_share), false);
        share.setOnClickListener(v -> startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND)
                .setType("text/plain").putExtra(Intent.EXTRA_TEXT, link), null)));
        LinearLayout.LayoutParams sp = wrap();
        sp.setMarginStart(dp(this, 8));
        btns.addView(share, sp);
        Button here = button(this, getString(R.string.signin_open_here), false);
        here.setOnClickListener(v -> {
            try {
                startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(link)));
            } catch (Exception e) {
                Toast.makeText(this, R.string.signin_no_browser, Toast.LENGTH_SHORT).show();
            }
        });
        LinearLayout.LayoutParams hp = wrap();
        hp.setMarginStart(dp(this, 8));
        btns.addView(here, hp);
        LinearLayout.LayoutParams bp = full();
        bp.topMargin = dp(this, 10);
        body.addView(btns, bp);
        if (acc.pastesCode()) pasteBox();
        secondary(getString(android.R.string.cancel), v -> acc.cancelLogin());
    }

    private void copy(String s, String toast) {
        if (s == null) return;
        getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText(getString(R.string.signin_clip_label), s));
        Toast.makeText(this, toast, Toast.LENGTH_SHORT).show();
    }

    private void openBrowserOnce() {
        String u = acc.url;
        if (u == null || u.equals(openedUrl)) return;
        openedUrl = u;
        BuddyService svc = BuddyService.get();
        if (svc != null) svc.watchForLoginCode();
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(u)));
        } catch (Exception e) {
            Toast.makeText(this, R.string.signin_no_browser_page, Toast.LENGTH_LONG).show();
        }
    }

    private void pasteBox() {
        LinearLayout c = column(this);
        LinearLayout head = row(this);
        head.setPaddingRelative(0, dp(this, 10), 0, dp(this, 10));
        head.setBackgroundResource(ripple(this, false));
        TextView h = title(this, getString(R.string.signin_paste_instead), 14);
        h.setTextColor(ACCENT_TEXT);
        head.addView(h, weight1());
        head.addView(icon(this, pasteOpen ? R.drawable.ms_expand_less : R.drawable.ms_expand_more, ACCENT_TEXT, 20));
        head.setOnClickListener(v -> {
            pasteOpen = !pasteOpen;
            render();
        });
        c.addView(head, full());
        if (pasteOpen) {
            c.addView(text(this, getString(acc.agy() ? R.string.signin_paste_help_agy : R.string.signin_paste_help), 14, MUTED));
            LinearLayout r = row(this);
            r.setPaddingRelative(0, dp(this, 10), 0, 0);
            EditText et = new EditText(this);
            et.setHint(R.string.signin_code_hint);
            et.setHintTextColor(MUTED);
            et.setTextColor(TEXT);
            et.setTypeface(mono(this, 400));
            et.setTextSize(14);
            et.setSingleLine(true);
            et.setPaddingRelative(dp(this, 12), dp(this, 10), dp(this, 12), dp(this, 10));
            et.setBackground(outlined(this, BG, 12, OUTLINE, 1));
            r.addView(et, weight1());
            Button go = button(this, getString(R.string.signin_use_code), true);
            LinearLayout.LayoutParams gp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(this, 44));
            gp.setMarginStart(dp(this, 8));
            r.addView(go, gp);
            go.setOnClickListener(v -> {
                if (!acc.submit(et.getText().toString())) {
                    Toast.makeText(this, R.string.signin_start_again, Toast.LENGTH_SHORT).show();
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
        t.setPaddingRelative(0, dp(this, 18), 0, dp(this, 6));
        body.addView(t);
        body.addView(text(this, sub, 16, MUTED));
    }

    private void spinnerRow() {
        LinearLayout r = row(this);
        r.setPaddingRelative(0, dp(this, 20), 0, 0);
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
