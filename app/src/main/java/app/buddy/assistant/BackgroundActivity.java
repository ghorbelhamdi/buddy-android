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
        // from the lock's "allow it on this Wi-Fi" notification: once more, so Android's question shows now
        // Android asks about an access point only when the user switches it on in Settings (switched on by Buddy, it
        // just goes off again, measured): Settings opens on the switch
        // (once: not again when the screen is made anew, by its own recreate, a theme change or a rotation)
        if (state == null && getIntent().getBooleanExtra("debug_lock_again", false)) {
            getIntent().removeExtra("debug_lock_again");
            openWirelessDebugging();
        }
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
        Background.onStartEnded(() -> runOnUiThread(this::render));
        render();
    }

    @Override
    protected void onPause() {
        super.onPause();
        Background.onStartEnded(null);
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

    /**
     * Developer options, scrolled to the Wireless debugging switch where the phone's Settings knows how (Android's own
     * does since Android 11), for the user to turn it on; this screen shows how it stands when they come back.
     */
    private void openWirelessDebugging() {
        try {
            Bundle row = new Bundle();
            row.putString(":settings:fragment_args_key", "toggle_adb_wireless");
            startActivity(new Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                    .putExtra(":settings:fragment_args_key", "toggle_adb_wireless")
                    .putExtra(":settings:show_fragment_args", row));
            android.widget.Toast.makeText(this, R.string.debug_lock_by_hand, android.widget.Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            startActivity(new Intent(Settings.ACTION_SETTINGS));
        }
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
                TextView why = text(this, getString(R.string.background_failed, Background.detail()), 14, ERR);
                why.setTextIsSelectable(true); // (to copy into a report)
                body.addView(why, gap(16));
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
        info.setTextIsSelectable(true); // (what adb said, to copy into a report)
        body.addView(info, gap(4));
        final Button start = button(this, getString(R.string.background_start_now), true);
        start.setVisibility(View.GONE);
        body.addView(start, big(14));
        final Button pair = button(this, getString(R.string.chat_bg_pair_again), false);
        pair.setVisibility(View.GONE);
        body.addView(pair, big(8));
        pair.setOnClickListener(v -> {
            BuddyService svc = BuddyService.get();
            if (svc == null) {
                android.widget.Toast.makeText(this, R.string.background_needs_service, android.widget.Toast.LENGTH_LONG).show();
                return;
            }
            opened = false;
            Background.beginSetup(svc);
        });
        Runnable check = () -> new Thread(() -> {
            boolean alive = Background.alive(this);
            boolean older = !alive && Background.olderRunning(this);
            String problem = alive ? Background.problem(this) : null;
            // (not started again after a restart although that's switched on: why, where it would say it isn't running)
            String restart = alive || older || !Prefs.backgroundRestart(this) ? null : Background.restartNote();
            String last = alive || older ? null : Background.lastStartWhy(); // (the last Start now, also from before this screen was drawn)
            runOnUiThread(() -> {
                status.setText(alive ? R.string.background_ready : R.string.background_paired);
                if (!alive && Background.startBusy()) { // (a Start now from before this screen was drawn: still at it)
                    info.setText(R.string.background_starting);
                    start.setVisibility(View.VISIBLE);
                    start.setEnabled(false);
                    return;
                }
                if (problem != null) info.setText(getString(R.string.background_failed, problem));
                else if (last != null) {
                    info.setText(getString(R.string.background_failed, last));
                    if (last.contains("paired")) pair.setVisibility(View.VISIBLE); // Wireless debugging forgot Buddy
                } else if (restart != null) info.setText(getString(R.string.background_failed, restart));
                else info.setText(alive ? R.string.background_ready_info
                        : older ? R.string.background_older : R.string.background_asleep);
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
                        if (why.contains("paired")) pair.setVisibility(View.VISIBLE); // Wireless debugging forgot Buddy
                    } else {
                        status.setText(R.string.background_ready);
                        info.setText(R.string.background_ready_info);
                        start.setVisibility(View.GONE);
                        check.run(); // it runs, but may say this phone gives it no screens
                    }
                });
            }).start();
        });
        check.run();
        LinearLayout sw = switchRow(this, getString(R.string.background_default), getString(R.string.background_default_info),
                Prefs.backgroundDefault(this), on -> Prefs.setBackgroundDefault(this, on));
        sw.setPaddingRelative(dp(this, 4), dp(this, 12), dp(this, 4), dp(this, 12));
        body.addView(sw, gap(14));
        // after a restart of the phone Buddy starts the helper again itself, where the phone lets it switch Wireless
        // debugging on (it says so where it doesn't: the switch can't do anything there)
        LinearLayout again = switchRow(this, getString(R.string.background_restart),
                getString(Background.canSwitchWireless(this) ? R.string.background_restart_info : R.string.background_restart_cant),
                Prefs.backgroundRestart(this), on -> Background.setRestart(this, on));
        again.setPaddingRelative(dp(this, 4), dp(this, 12), dp(this, 4), dp(this, 12));
        body.addView(again, full());
        // off right now: said here too, not only in a notification, with the way to turn it on
        if (Background.setUp(this) && !DebugLock.on(this)) {
            LinearLayout warn = column(this);
            warn.setBackground(outlined(this, CARD, 16, ERR, 1));
            warn.setPaddingRelative(dp(this, 14), dp(this, 12), dp(this, 14), dp(this, 12));
            warn.addView(title(this, getString(R.string.debug_lock_off_title), 15));
            TextView wt = text(this, getString(R.string.debug_lock_settings_text), 13, MUTED);
            wt.setPaddingRelative(0, dp(this, 4), 0, dp(this, 10));
            warn.addView(wt);
            android.widget.Button on = button(this, getString(R.string.debug_lock_turn_on), true);
            on.setOnClickListener(v -> {
                v.setEnabled(false);
                new Thread(() -> {
                    // Buddy switches it on itself where the phone gave it the right, or through the helper while that
                    // runs; else (the helper went with it) only the user can: Settings opens on the switch
                    boolean can = Background.canSwitchWireless(this) || Background.alive(this);
                    runOnUiThread(() -> {
                        if (isFinishing()) return;
                        v.setEnabled(true);
                        if (can) {
                            DebugLock.changed(BuddyService.get()); // one more try: Android's question comes up if it has one
                            v.postDelayed(this::recreate, 6000); // (it tries after a moment, and the phone answers after another)
                            return;
                        }
                        openWirelessDebugging();
                    });
                }, "debug-lock-on").start();
            });
            warn.addView(on, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(this, 40)));
            LinearLayout.LayoutParams wp = full();
            wp.topMargin = dp(this, 8);
            wp.bottomMargin = dp(this, 8);
            body.addView(protect(warn), wp);
        }
        // the phone switches Wireless debugging off now and then; without it the helper can't be started again
        LinearLayout lock = switchRow(this, getString(R.string.debug_lock), getString(R.string.debug_lock_info),
                Prefs.debugLock(this), on -> {
                    Prefs.setDebugLock(this, on);
                    DebugLock.changed(BuddyService.get());
                });
        lock.setPaddingRelative(dp(this, 4), dp(this, 12), dp(this, 4), dp(this, 12));
        body.addView(lock, full());
        // a video that plays by itself on a hidden screen would be heard through the speaker
        LinearLayout mute = switchRow(this, getString(R.string.background_mute), getString(R.string.background_mute_info),
                Prefs.muteHidden(this), on -> {
                    Prefs.setMuteHidden(this, on);
                    HiddenMute.check(this);
                });
        mute.setPaddingRelative(dp(this, 4), dp(this, 12), dp(this, 4), dp(this, 12));
        body.addView(mute, full());
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
