package app.buddy.assistant;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Intent;

import java.util.ArrayList;
import java.util.List;

/**
 * While a chat works without the phone's screen (on a hidden screen, in the background browser, with Linux tools, or
 * thinking), a quiet notification says so and can stop it. The bubble says the same, but it can be hidden, and with
 * the phone locked or a full-screen app in front nothing else told that an agent was at work. A task on the phone's
 * own screen has the control chip (ControlIndicator), a paused one AutoPause's notification: then this one goes.
 */
final class WorkNotice implements ChatHub.Listener {
    static final String ACTION_STOP = "app.buddy.assistant.STOP_RUNS", EXTRA_OUTSIDE_ONLY = "outsideOnly",
            EXTRA_OUTSIDE = "outside";
    private static final int ID = 11;

    private final BuddyService svc;
    /** The chats it names now (null: not showing), so it's posted again only when they change. */
    private String shown;
    /** Nothing was posted or cleared yet: a notice left by an earlier process (killed mid-run) may still be there. */
    private boolean fresh = true;
    /** The service is going (see remove): nothing is posted any more. */
    private boolean gone;

    private final Runnable recheck = this::update;

    WorkNotice(BuddyService svc) {
        this.svc = svc;
    }

    @Override
    public void onChatsChanged() {
        update();
    }

    @Override
    public void onMessage(Sessions.Session ses, Sessions.Msg msg) {
    }

    /** A run, the control chip or a pause changed (main thread). */
    void update() {
        if (gone) return;
        ChatHub hub = ChatHub.get(svc);
        List<String> names = new ArrayList<>();
        Sessions.Session first = hub.latestRunning();
        // a chat on the real screen has the control chip instead; only chats named here are opened or stopped from it
        if (svc.controlActive() || svc.paused()) first = null;
        if (first != null) {
            names.add(first.label());
            // (a sub-agent works for its chat, which is named)
            for (Sessions.Session s : hub.sessions.all) if (s.running && s.parent.isEmpty() && s != first) names.add(s.label());
        }
        // sessions from outside Buddy (a paired computer, this phone's terminal) working on the phone: named too,
        // and looked at again a minute on, when one that went quiet no longer counts as working
        java.util.List<String> outside = Background.outsideActive();
        outside.removeIf(k -> !Background.outsideHidden(k)); // one on the real screen has the control chip
        for (String k : outside) names.add(Background.outsideName(svc, k));
        svc.mainHandler().removeCallbacks(recheck);
        if (!outside.isEmpty()) svc.mainHandler().postDelayed(recheck, 15_000);
        String key = first != null ? first.id : outside.isEmpty() ? "" : outside.get(0);
        // (which sessions too: two computers can have the same name, and Stop must stop the ones named now)
        String want = names.isEmpty() ? null : key + "\n" + android.text.TextUtils.join(", ", names) + "\n"
                + android.text.TextUtils.join(",", outside);
        if (!fresh && (want == null ? shown == null : want.equals(shown))) return;
        fresh = false;
        shown = want;
        NotificationManager nm = svc.getSystemService(NotificationManager.class);
        if (want == null) {
            nm.cancel(ID);
            return;
        }
        ControlIndicator.channel(svc);
        PendingIntent stop = PendingIntent.getBroadcast(svc, ID, new Intent(svc, HiddenNotice.class).setAction(ACTION_STOP)
                        .putExtra(EXTRA_OUTSIDE_ONLY, first == null)
                        .putExtra(EXTRA_OUTSIDE, outside.toArray(new String[0])), // the ones it names, no others
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        // a chat's run opens its chat; only outside sessions working: the Sessions tab, where they are listed
        Intent where = first != null ? new Intent(svc, ChatActivity.class).putExtra("id", first.id)
                : new Intent(svc, MonitorActivity.class);
        PendingIntent open = PendingIntent.getActivity(svc, ID,
                where.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(svc, ControlIndicator.CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setContentTitle(svc.getString(R.string.work_title))
                .setContentText(android.text.TextUtils.join(", ", names))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_PROGRESS)
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(null, svc.getString(R.string.control_stop), stop).build())
                .build();
        try {
            nm.notify(ID, n);
        } catch (SecurityException ignored) { // notifications off: the bubble still says it
        }
    }

    /** The service is going: nothing of it stays in the shade. */
    void remove() {
        gone = true;
        shown = null;
        svc.getSystemService(NotificationManager.class).cancel(ID);
    }
}
