package app.buddy.assistant;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

/**
 * Long-task mode (a chat's "Keep the screen on while it works"): while such a chat runs, the screen stays on so the
 * phone doesn't lock under the agent, and when the run ends a notification says how it went.
 *
 * An accessibility service has no window of its own to keep the screen on with, and the screen wake locks are
 * deprecated, so it's a 1-pixel window nobody can see or touch with FLAG_KEEP_SCREEN_ON, like AutoPause's watcher
 * (no full-screen overlay: see ControlIndicator). It goes while the run is paused or stopped, and while the phone is
 * locked: the user locking it means they want it off.
 */
final class KeepAwake {
    private static final String TAG = "run_end";

    private final BuddyService svc;
    private View window;

    KeepAwake(BuddyService svc) {
        this.svc = svc;
    }

    /** Something changed (a run, a pause, Stop, the lock; main thread): hold the screen on, or let it go. */
    void update() {
        if (wanted()) add();
        else remove();
    }

    private boolean wanted() {
        if (svc.paused() || svc.controlRevoked() != null || Triggers.locked(svc)) return false;
        for (Sessions.Session s : ChatHub.get(svc).sessions.all) if (s.running && s.awake) return true;
        return false;
    }

    private void add() {
        if (window != null) return;
        View v = new View(svc);
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(1, 1,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.TOP | Gravity.END; // not where AutoPause's watcher is
        try {
            svc.getSystemService(WindowManager.class).addView(v, p);
            window = v;
        } catch (Exception ignored) { // then the screen sleeps as usual
        }
    }

    void remove() {
        if (window == null) return;
        try {
            svc.getSystemService(WindowManager.class).removeView(window);
        } catch (Exception ignored) {
        }
        window = null;
    }

    /**
     * A long-task chat's run ended: one notification with how it went, opening the chat. The control chip has its own
     * notification while the agent acts; this one comes once, at the end, and goes when tapped.
     */
    static void notifyEnded(Context c, Sessions.Session ses) {
        ControlIndicator.channel(c);
        int title = "stopped".equals(ses.outcome) ? R.string.run_end_stopped
                : "failed".equals(ses.outcome) ? R.string.run_end_failed : R.string.run_end_done;
        String text = ses.outcome == null && !ses.lastReply.isEmpty() ? Ui.linkText(ses.lastReply) : ses.label();
        PendingIntent open = PendingIntent.getActivity(c, ses.id.hashCode(), new Intent(c, ChatActivity.class)
                        .putExtra("id", ses.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(c, ControlIndicator.CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setContentTitle(c.getString(title, ses.brainName()))
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build();
        try {
            c.getSystemService(NotificationManager.class).notify(TAG, ses.id.hashCode(), n);
        } catch (SecurityException ignored) { // notifications off: the chat says it
        }
    }
}
