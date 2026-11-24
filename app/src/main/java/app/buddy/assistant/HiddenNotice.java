package app.buddy.assistant;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/** The "Buddy is hidden" notification shown after the bubble is dragged away, and its actions. */
public final class HiddenNotice extends BroadcastReceiver {
    private static final String CHANNEL = "bubble_hidden";
    private static final int ID = 1;
    static final String ACTION_SHOW = "app.buddy.assistant.SHOW_BUBBLE";
    /** Resume, on the notice of a bubble hidden while a task was paused (issue #72). */
    static final String ACTION_RESUME = "app.buddy.assistant.RESUME_HIDDEN";

    static void show(Context c) {
        show(c, false);
    }

    static void show(Context c, boolean paused) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, c.getString(R.string.notice_channel_name),
                NotificationManager.IMPORTANCE_LOW));
        PendingIntent showBubble = PendingIntent.getBroadcast(c, 0,
                new Intent(c, HiddenNotice.class).setAction(ACTION_SHOW),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent openApp = PendingIntent.getActivity(c, 1,
                new Intent(c, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder nb = new Notification.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setContentTitle(c.getString(R.string.notice_hidden_title))
                .setContentText(c.getString(R.string.notice_hidden_text))
                .setContentIntent(showBubble)
                .addAction(new Notification.Action.Builder(null, c.getString(R.string.notice_open_chats), openApp).build())
                .setOngoing(false)
                .setAutoCancel(true);
        if (paused) { // the pill that resumes went with the bubble
            nb.addAction(new Notification.Action.Builder(null, c.getString(R.string.menu_resume), PendingIntent.getBroadcast(c, 2,
                    new Intent(c, HiddenNotice.class).setAction(ACTION_RESUME),
                    PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT)).build());
        }
        Notification n = nb.build();
        try {
            nm.notify(ID, n);
        } catch (SecurityException ignored) {
            // notifications not allowed; the bubble can still be shown again from Settings
        }
    }

    /** A pause ended: the notice of a bubble hidden during it, if it's still up, without Resume now. */
    static void pauseEnded(Context c) {
        try {
            for (android.service.notification.StatusBarNotification n
                    : c.getSystemService(NotificationManager.class).getActiveNotifications()) {
                if (n.getId() == ID && n.getNotification().actions != null && n.getNotification().actions.length > 1) {
                    show(c, false);
                    return;
                }
            }
        } catch (RuntimeException ignored) {
        }
    }

    static void cancel(Context c) {
        c.getSystemService(NotificationManager.class).cancel(ID);
    }

    @Override
    public void onReceive(Context c, Intent intent) {
        if (ControlIndicator.ACTION_STOP.equals(intent.getAction())) {
            BuddyService s = BuddyService.get();
            if (s != null) s.takeBackControl();
            return;
        }
        if (WorkNotice.ACTION_STOP.equals(intent.getAction())) { // Stop on "working in the background"
            // only the outside sessions when they were all it named (a chat on the real screen has its own Stop)
            if (!intent.getBooleanExtra(WorkNotice.EXTRA_OUTSIDE_ONLY, false)) ChatHub.get(c).stopAll();
            BuddyService s = BuddyService.get(); // and the sessions from outside Buddy working on the phone
            String[] named = intent.getStringArrayExtra(WorkNotice.EXTRA_OUTSIDE); // the ones it named
            if (s != null && named != null) for (String k : named) Background.stopOutside(s, k);
            return;
        }
        if (LinuxFiles.ACTION_CANCEL.equals(intent.getAction())) { // Cancel on "Sending … to Linux tools"
            LinuxFiles.cancel(null);
            return;
        }
        if (CodexLive.ACTION_END.equals(intent.getAction())) { // the live voice notification's End
            CodexLive.end(null);
            return;
        }
        if (AutoPause.ACTION_RESUME.equals(intent.getAction())) { // "Call ended": resume the paused task
            BuddyService s = BuddyService.get();
            if (s != null) s.resumeFromShade();
            AutoPause.cancelCallEnded(c); // also when it had ended already
            return;
        }
        if (ACTION_RESUME.equals(intent.getAction())) { // the task goes on; the bubble stays hidden (its notice, plain)
            BuddyService s = BuddyService.get();
            if (s != null && s.paused()) s.resumeFromShade(); // (a pause still on: the one it was shown for)
            show(c, false);
            return;
        }
        if (!ACTION_SHOW.equals(intent.getAction())) return;
        Prefs.setBubbleEnabled(c, true);
        cancel(c);
        BuddyService svc = BuddyService.get();
        if (svc != null) svc.applyBubbleSetting();
    }
}
