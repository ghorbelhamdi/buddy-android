package app.buddy.assistant;

import android.app.Notification;
import android.content.ComponentName;
import android.content.Context;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;

/**
 * Notification access, for the user's notification rules. A notification is read only when a switched-on rule watches
 * its app; everything else is ignored right away. Of those, only the latest of each conversation is kept, in memory, to
 * tell which one Android blanked (see Triggers.hidden). Besides, a call's notification pauses an agent that's operating
 * the phone (AutoPause): for that only the notification's category is looked at.
 */
public class NotificationWatcher extends NotificationListenerService {
    private static volatile NotificationWatcher instance;

    @Override
    public void onListenerConnected() {
        instance = this;
        try {
            AutoPause.onNotifications(getActiveNotifications());
        } catch (Exception ignored) {
        }
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        AutoPause.onNotification(sbn, false);
    }

    @Override
    public void onListenerDisconnected() {
        instance = null;
    }

    /** A conversation's notification as it is now, or null (gone, or no notification access). */
    static Triggers.Note current(String pkg, String who) {
        NotificationWatcher w = instance;
        if (w == null) return null;
        try {
            for (StatusBarNotification s : w.getActiveNotifications()) {
                if (!pkg.equals(s.getPackageName())
                        || (s.getNotification().flags & Notification.FLAG_GROUP_SUMMARY) != 0) continue;
                Triggers.Note n = Triggers.Note.of(w, s);
                if (n != null && who.equals(n.who())) return n;
            }
        } catch (Exception ignored) { // not connected right now
        }
        return null;
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        AutoPause.onNotification(sbn, true);
        try {
            String pkg = sbn.getPackageName();
            if (pkg == null || pkg.equals(getPackageName()) || !Prefs.rulesOn(this) || !Rules.watches(this, pkg)) return;
            Notification n = sbn.getNotification();
            // progress, music and other ongoing ones aren't news; a group's summary repeats its children
            if ((n.flags & (Notification.FLAG_ONGOING_EVENT | Notification.FLAG_FOREGROUND_SERVICE
                    | Notification.FLAG_GROUP_SUMMARY)) != 0) return;
            Triggers.Note note = Triggers.Note.of(this, sbn);
            if (note == null) return; // nothing to read
            Triggers t = Triggers.get(this);
            if (note.hidden()) { // Android blanked it (it may hold a code): say so instead of going quiet
                t.hidden(sbn.getKey());
                return;
            }
            t.seen(sbn.getKey(), note);
            if (note.fromMe) return; // the user's own message (e.g. a reply just sent)
            Rules.Rule rule = Rules.match(this, note);
            if (rule != null) t.fire(rule, note);
        } catch (Exception e) {
            android.util.Log.w("Buddy", "notification rule failed", e);
        }
    }

    /** The notifications on the phone now (none when Buddy has no notification access). */
    static StatusBarNotification[] active() {
        NotificationWatcher w = instance;
        if (w == null) return null;
        try {
            return w.getActiveNotifications();
        } catch (Exception e) { // not connected right now
            return null;
        }
    }

    static Context context() {
        return instance;
    }

    /** Has the user given Buddy notification access? */
    static boolean enabled(Context c) {
        return c.getSystemService(android.app.NotificationManager.class)
                .isNotificationListenerAccessGranted(new ComponentName(c, NotificationWatcher.class));
    }
}
