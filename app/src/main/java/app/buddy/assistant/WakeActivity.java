package app.buddy.assistant;

import android.app.Activity;
import android.app.KeyguardManager;
import android.os.Bundle;
import android.view.Gravity;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import static app.buddy.assistant.Ui.*;

/**
 * A scheduled task with "Wake the phone for this task" needs the screen while the phone sleeps: this turns the screen
 * on over the lock screen. Without a PIN, pattern or fingerprint to ask for (no secure lock, or Smart Lock keeps the
 * phone unlocked) it lets the lock screen go and gets out of the way, and the run goes on. With one, it never gets
 * around it: it says the task needs the user to unlock, a tap brings up the phone's own unlock, and after a short
 * while it goes so the screen sleeps as usual (the notification stays).
 */
public class WakeActivity extends Activity {
    private static final long SHOW_MS = 30_000L;
    private static WakeActivity shown;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        setShowWhenLocked(true);
        setTurnScreenOn(true);
        shown = this;
        KeyguardManager km = getSystemService(KeyguardManager.class);
        // once unlocked it goes at once: the agent is about to work the screen under it
        KeyguardManager.KeyguardDismissCallback gone = new KeyguardManager.KeyguardDismissCallback() {
            @Override
            public void onDismissSucceeded() {
                finish();
            }
        };
        if (!km.isDeviceLocked()) { // nothing to unlock with: the lock screen goes, the run goes on
            km.requestDismissKeyguard(this, new KeyguardManager.KeyguardDismissCallback() {
                @Override
                public void onDismissSucceeded() {
                    finish();
                }

                @Override
                public void onDismissError() {
                    finish();
                }

                @Override
                public void onDismissCancelled() {
                    finish();
                }
            });
            return;
        }
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        LinearLayout c = column(this);
        c.setGravity(Gravity.CENTER);
        c.setBackgroundColor(BG);
        c.setPaddingRelative(dp(this, 32), 0, dp(this, 32), 0);
        TextView t = title(this, getString(R.string.schedule_screen_title), 20);
        t.setGravity(Gravity.CENTER);
        c.addView(t);
        // the phone's own unlock (PIN, pattern, fingerprint): only the user gets past it
        c.setOnClickListener(v -> km.requestDismissKeyguard(this, gone));
        setContentView(c);
        c.postDelayed(this::finish, SHOW_MS);
    }

    @Override
    protected void onDestroy() {
        if (shown == this) shown = null;
        super.onDestroy();
    }

    /** The run ended or the user unlocked: it goes, if it's still there (main thread). */
    static void close() {
        if (shown != null) shown.finish();
    }
}
