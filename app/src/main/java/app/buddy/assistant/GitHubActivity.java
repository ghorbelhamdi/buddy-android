package app.buddy.assistant;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import static app.buddy.assistant.Ui.*;

/** Settings → GitHub: sign the Linux tools' git and GitHub CLI in to the user's account (see GitHub). */
public class GitHubActivity extends Screen {
    private LinearLayout body;
    private String opened;

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
        TextView t = title(this, "GitHub", 18);
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
        setContentView(protect(frame)); // only the user signs in or out
    }

    @Override
    protected void onResume() {
        super.onResume();
        GitHub.setListener(this::render);
        if (GitHub.step == GitHub.Step.IDLE) GitHub.refresh(this);
        render();
    }

    @Override
    protected void onPause() {
        super.onPause();
        GitHub.setListener(null);
    }

    private void render() {
        body.removeAllViews();
        body.addView(text(this, getString(R.string.github_intro), 13, MUTED), full());
        if (!Linux.installed(this)) {
            body.addView(text(this, getString(R.string.skills_need_linux), 15, TEXT), gap(18));
            return;
        }
        switch (GitHub.step) {
            case INSTALLING:
            case FINISHING:
                body.addView(text(this, getString(GitHub.step == GitHub.Step.INSTALLING ? R.string.github_installing
                        : R.string.github_finishing), 15, TEXT), gap(18));
                body.addView(spinner(this, 22), gap(12));
                return;
            case WAITING: {
                body.addView(text(this, getString(R.string.github_enter_code), 15, TEXT), gap(18));
                TextView code = monoText(this, GitHub.code, 32, TEXT);
                code.setGravity(Gravity.CENTER);
                code.setTextIsSelectable(true);
                body.addView(code, gap(12));
                Button open = button(this, getString(R.string.github_open), true);
                open.setOnClickListener(v -> openGitHub());
                body.addView(open, big(16));
                Button cancel = button(this, getString(android.R.string.cancel), false);
                cancel.setOnClickListener(v -> GitHub.cancel());
                body.addView(cancel, big(8));
                if (!GitHub.code.equals(opened)) { // the first time: the code is copied, and GitHub opens
                    opened = GitHub.code;
                    openGitHub();
                }
                return;
            }
            case ERROR:
                body.addView(warning(this, getString(R.string.github_failed, GitHub.error)), gap(18));
                break;
            default:
                break;
        }
        String login = GitHub.login;
        if (login == null) {
            body.addView(spinner(this, 22), gap(18));
            return;
        }
        if (!login.isEmpty()) {
            body.addView(title(this, getString(R.string.github_signed_in, login), 16), gap(18));
            body.addView(text(this, getString(R.string.github_signed_in_info), 13, MUTED), gap(4));
            Button out = button(this, getString(R.string.signin_sign_out), false);
            out.setOnClickListener(v -> confirmSheet(this, getString(R.string.github_sign_out_title),
                    getString(R.string.github_sign_out_text), getString(R.string.signin_sign_out), true,
                    () -> {
                        GitHub.login = null;
                        GitHub.signOut(this);
                        render();
                    }));
            body.addView(out, big(16));
            return;
        }
        body.addView(warning(this, getString(R.string.github_risk)), gap(18));
        Button in = button(this, getString(R.string.github_sign_in), true);
        in.setOnClickListener(v -> GitHub.signIn(this));
        body.addView(in, big(16));
    }

    /** The code on the clipboard, and GitHub's page for it. */
    private void openGitHub() {
        ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        cm.setPrimaryClip(ClipData.newPlainText("GitHub code", GitHub.code));
        Toast.makeText(this, R.string.github_code_copied, Toast.LENGTH_SHORT).show();
        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/login/device")));
    }

    private LinearLayout.LayoutParams big(int top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(this, 52));
        p.topMargin = dp(this, top);
        return p;
    }

    private LinearLayout.LayoutParams gap(int top) {
        LinearLayout.LayoutParams p = full();
        p.topMargin = dp(this, top);
        return p;
    }
}
