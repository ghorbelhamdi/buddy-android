package app.buddy.assistant;

import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import static app.buddy.assistant.Ui.*;

/**
 * Settings → Background mode: what it is, the one-time pairing with the phone's Wireless debugging (see Background),
 * and whether its helper runs. Only the user sets this up or removes it.
 */
public class BackgroundActivity extends Screen {
    private LinearLayout body;
    private Background.Step shown;
    private boolean opened; // Developer options, once per set-up

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
        TextView t = title(this, getString(R.string.background_title), 18);
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
        setContentView(protect(frame));
    }

    @Override
    protected void onResume() {
        super.onResume();
        Background.listen(() -> runOnUiThread(this::render));
        render();
    }

    @Override
    protected void onPause() {
        super.onPause();
        // the set-up goes on while the user is in Settings: it brings this screen back itself when it's done
        Background.listen(() -> {
            Background.Step s = Background.step();
            if (s == Background.Step.READY || s == Background.Step.FAILED) {
                Background.listen(null);
                BuddyService svc = BuddyService.get();
                if (svc != null) svc.runOnMain(() -> svc.startActivity(new Intent(svc, BackgroundActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT
                                | Intent.FLAG_ACTIVITY_SINGLE_TOP)));
            }
        });
    }

    private void openDeveloperOptions() {
        if (opened) return;
        opened = true;
        try {
            startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS));
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_SETTINGS));
        }
    }

    private void render() {
        Background.Step s = Background.step();
        shown = s;
        body.removeAllViews();
        body.addView(text(this, getString(R.string.background_intro), 14, TEXT), full());
        body.addView(text(this, getString(R.string.background_how), 13, MUTED), gap(8));
        boolean working = s == Background.Step.INSTALLING || s == Background.Step.WAITING || s == Background.Step.PAIRING
                || s == Background.Step.STARTING;
        if (Background.setUp(this) && !working) {
            renderSetUp();
            return;
        }
        LinearLayout steps = group(this);
        for (int id : new int[]{R.string.background_step1, R.string.background_step2, R.string.background_step3}) {
            TextView st = text(this, getString(id), 14, TEXT);
            st.setPaddingRelative(dp(this, 16), dp(this, 10), dp(this, 16), dp(this, 10));
            steps.addView(st, full());
        }
        body.addView(steps, gap(16));
        if (working) {
            LinearLayout r = row(this);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.addView(spinner(this, 20));
            TextView w = text(this, getString(s == Background.Step.INSTALLING ? R.string.background_installing
                    : s == Background.Step.WAITING ? R.string.background_waiting
                    : s == Background.Step.PAIRING ? R.string.background_pairing : R.string.background_starting), 14, TEXT);
            w.setPaddingRelative(dp(this, 12), 0, 0, 0);
            r.addView(w, weight1());
            body.addView(r, gap(18));
            if (s == Background.Step.WAITING) {
                Button again = button(this, getString(R.string.background_open_options), true);
                again.setOnClickListener(v -> {
                    opened = false;
                    openDeveloperOptions();
                });
                body.addView(again, big(14));
                Button cancel = button(this, getString(android.R.string.cancel), false);
                cancel.setOnClickListener(v -> Background.cancelSetup());
                body.addView(cancel, big(8));
            }
        } else {
            if (s == Background.Step.FAILED) {
                body.addView(text(this, getString(R.string.background_failed, Background.detail()), 14, ERR), gap(16));
            }
            Button go = button(this, getString(R.string.background_setup), true);
            go.setOnClickListener(v -> {
                BuddyService svc = BuddyService.get();
                if (svc == null) {
                    android.widget.Toast.makeText(this, R.string.background_needs_service, android.widget.Toast.LENGTH_LONG).show();
                    return;
                }
                opened = false;
                Background.beginSetup(svc);
            });
            body.addView(go, big(18));
        }
        body.addView(text(this, getString(R.string.background_usb_note), 12, MUTED), gap(18));
        body.addView(text(this, getString(R.string.background_security), 12, MUTED), gap(8));
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

    /** Paired: whether the helper runs, the default for new chats, and Remove. */
    private void renderSetUp() {
        final TextView status = title(this, getString(R.string.background_ready), 16);
        body.addView(status, gap(20));
        final TextView info = text(this, getString(R.string.background_ready_info), 13, MUTED);
        body.addView(info, gap(4));
        final Button start = button(this, getString(R.string.background_start_now), true);
        start.setVisibility(View.GONE);
        body.addView(start, big(14));
        Runnable check = () -> new Thread(() -> {
            boolean alive = Background.alive(this);
            runOnUiThread(() -> {
                status.setText(alive ? R.string.background_ready : R.string.background_paired);
                info.setText(alive ? R.string.background_ready_info : R.string.background_asleep);
                start.setVisibility(alive ? View.GONE : View.VISIBLE);
                start.setEnabled(true);
            });
        }).start();
        start.setOnClickListener(v -> {
            start.setEnabled(false);
            info.setText(R.string.background_starting);
            new Thread(() -> {
                String why = Background.start(this, true);
                BuddyService svc = BuddyService.get(); // Buddy may have walked Settings for it: back here
                if (svc != null && !hasWindowFocus()) svc.runOnMain(() -> svc.startActivity(new Intent(svc, BackgroundActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP)));
                runOnUiThread(() -> {
                    if (why != null) {
                        info.setText(getString(R.string.background_failed, why));
                        start.setEnabled(true);
                    } else {
                        status.setText(R.string.background_ready);
                        info.setText(R.string.background_ready_info);
                        start.setVisibility(View.GONE);
                    }
                });
            }).start();
        });
        check.run();
        LinearLayout sw = switchRow(this, getString(R.string.background_default), getString(R.string.background_default_info),
                Prefs.backgroundDefault(this), on -> Prefs.setBackgroundDefault(this, on));
        sw.setPaddingRelative(dp(this, 4), dp(this, 12), dp(this, 4), dp(this, 12));
        body.addView(sw, gap(14));
        // phone commands (issue #22): the agent's own command line on the phone, through the same helper
        TextView pc = sectionLabel(this, getString(R.string.background_shell));
        body.addView(pc, gap(18));
        final LinearLayout[] ask = new LinearLayout[1];
        LinearLayout shell = switchRow(this, getString(R.string.background_shell_switch), getString(R.string.background_shell_info),
                Prefs.phoneShell(this), on -> {
                    Prefs.setPhoneShell(this, on);
                    ask[0].setVisibility(on ? View.VISIBLE : View.GONE);
                });
        shell.setPaddingRelative(dp(this, 4), dp(this, 8), dp(this, 4), dp(this, 12));
        body.addView(shell, full());
        ask[0] = switchRow(this, getString(R.string.background_shell_ask), getString(R.string.background_shell_ask_info),
                Prefs.phoneShellAsk(this), on -> Prefs.setPhoneShellAsk(this, on));
        ask[0].setPaddingRelative(dp(this, 4), dp(this, 8), dp(this, 4), dp(this, 12));
        ask[0].setVisibility(Prefs.phoneShell(this) ? View.VISIBLE : View.GONE);
        body.addView(ask[0], full());
        Button remove = button(this, getString(R.string.background_remove), false);
        remove.setOnClickListener(v -> confirmSheet(this, getString(R.string.background_remove),
                getString(R.string.background_remove_text), getString(R.string.background_remove), true, () -> {
                    Background.remove(this);
                    render();
                }));
        body.addView(remove, big(14));
        body.addView(text(this, getString(R.string.background_usb_note), 12, MUTED), gap(18));
        body.addView(text(this, getString(R.string.background_security), 12, MUTED), gap(8));
    }
}
