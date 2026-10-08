package app.buddy.assistant;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;
import android.os.Bundle;
import android.os.SystemClock;

/**
 * Makes it obvious when an agent is operating the phone, so the user knows not to touch it:
 * a status-bar chip (an Android 16 "Live Update", like Google Maps navigation; a plain ongoing
 * notification on older Android) and the bubble in its "in control" state with a Stop button.
 * Shown from the first phone action until the task ends.
 *
 * No extra overlay windows on purpose: some phones' security features (e.g. OxygenOS "Block
 * suspicious app activities") treat a full-screen overlay from an accessibility app as an attack.
 */
final class ControlIndicator {
    static final String CHANNEL = "control", ACTION_STOP = "app.buddy.assistant.STOP_CONTROL";
    private static final int NOTIFICATION_ID = 7;
    /** Idle time before it hides when no Buddy chat is running (e.g. a terminal session using the tools). */
    private static final long IDLE_EXTERNAL_MS = 15_000, IDLE_BUDDY_MS = 4_000, TICK_MS = 1_000;

    private final BuddyService svc;
    private boolean active;
    private long lastActivity;

    ControlIndicator(BuddyService svc) {
        this.svc = svc;
    }

    boolean active() {
        return active;
    }

    /** A phone tool is being used (main thread). */
    void onActivity() {
        lastActivity = SystemClock.elapsedRealtime();
        if (active) return;
        active = true;
        Bubble b = svc.bubble();
        if (b != null) b.setControlling(true);
        notifyChip();
        svc.mainHandler().postDelayed(tick, TICK_MS);
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!active) return;
            long idle = SystemClock.elapsedRealtime() - lastActivity;
            ChatHub hub = ChatHub.get(svc);
            // A Buddy chat keeps it on while it thinks between actions; once the run ends it goes away.
            // Sessions outside Buddy (e.g. a terminal) only show their activity, so it times out.
            boolean runEnded = hub.runningCount() == 0 && hub.lastRunEndedAt() >= lastActivity;
            boolean buddyRunning = hub.runningCount() > 0;
            if ((runEnded && idle > IDLE_BUDDY_MS) || (!buddyRunning && idle > IDLE_EXTERNAL_MS)) {
                end();
            } else {
                svc.mainHandler().postDelayed(this, TICK_MS);
            }
        }
    };

    void end() {
        if (!active) return;
        active = false;
        svc.mainHandler().removeCallbacks(tick);
        Bubble b = svc.bubble();
        if (b != null) b.setControlling(false);
        svc.getSystemService(NotificationManager.class).cancel(NOTIFICATION_ID);
    }

    // ------------------------------------------------------- notification

    private void notifyChip() {
        NotificationManager nm = svc.getSystemService(NotificationManager.class);
        NotificationChannel ch = new NotificationChannel(CHANNEL, "Buddy is using your phone",
                NotificationManager.IMPORTANCE_DEFAULT);
        ch.setDescription("Shows in the status bar while an agent operates the phone.");
        ch.setSound(null, null);
        ch.enableVibration(false);
        nm.createNotificationChannel(ch);
        PendingIntent stop = PendingIntent.getBroadcast(svc, 2,
                new Intent(svc, HiddenNotice.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent open = PendingIntent.getActivity(svc, 3,
                new Intent(svc, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Bundle extras = new Bundle();
        // Android 16 Live Update: shows as a chip in the status bar (Notification.EXTRA_REQUEST_PROMOTED_ONGOING)
        extras.putBoolean("android.requestPromotedOngoing", true);
        Notification.Builder b = new Notification.Builder(svc, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setContentTitle("Buddy is using your phone")
                .setContentText("Don't touch the screen until it's done, or tap Stop to take over.")
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_PROGRESS)
                .setContentIntent(open)
                .addExtras(extras)
                .addAction(new Notification.Action.Builder(null, "Stop", stop).build());
        if (android.os.Build.VERSION.SDK_INT >= 36) b.setShortCriticalText("Buddy");
        try {
            nm.notify(NOTIFICATION_ID, b.build());
        } catch (SecurityException ignored) {
            // notifications off: the bubble still shows it
        }
    }
}
