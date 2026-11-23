package app.buddy.assistant;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;

/**
 * Buddy picked as the phone's digital assistant (Settings → Apps → Default apps): long-pressing the power button or
 * the corner swipe lands here, and Buddy starts listening beside the bubble, as a tap does when set to talk. Nothing
 * listens before that: there's no wake word. No screen of its own; with Buddy's service off it opens the app.
 */
public final class AssistActivity extends Activity {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        // exported, as the system needs: only while the user has picked Buddy as the assistant app does it answer, so
        // another app can't make it listen (launching "the user's assistant" is open to any app, by Android's design)
        android.app.role.RoleManager roles = getSystemService(android.app.role.RoleManager.class);
        if (roles == null || !roles.isRoleHeld(android.app.role.RoleManager.ROLE_ASSISTANT)) {
            finish();
            return;
        }
        // the user's choice (issue #80): not from the lock screen or standby, where power is held by accident
        android.app.KeyguardManager lock = getSystemService(android.app.KeyguardManager.class);
        android.os.PowerManager pm = getSystemService(android.os.PowerManager.class);
        // (a dark screen too: with no lock set, or in the minutes before the lock comes on, the keyguard says "unlocked")
        if (Prefs.assistUnlockedOnly(this) && ((lock != null && lock.isKeyguardLocked()) || (pm != null && !pm.isInteractive()))) {
            finish();
            return;
        }
        BuddyService svc = BuddyService.get();
        Bubble bubble = svc == null ? null : svc.bubble();
        if (bubble != null) bubble.assist(); // hidden or not: assist() opens the chat when it is
        else startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        finish();
    }
}
