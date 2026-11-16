package app.buddy.assistant;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.IBinder;

/**
 * A foreground service for as long as a chat works, so Android keeps Buddy's process while it does.
 *
 * A task takes Buddy off the screen (it opens the app it works in), and a process that only hosts an accessibility
 * service isn't "in use" to Android. A permission given "Only this time" (the microphone, asked the first time the
 * user dictates) then runs out about a minute later, and Android takes it back by ending the whole app: the agent,
 * its Linux and the phone tools went with it in the middle of a task (seen on a RedMagic 11 Pro, Android 16:
 * "Killing app.buddy.assistant: one-time permission revoked"). With a foreground service the permission lasts until
 * the task is over, and the process is among the last Android ends for memory.
 *
 * Its notification is on a channel of its own at the lowest importance (the control chip and "working in the
 * background" already say what Buddy does); the user can switch that channel off. Where Android doesn't let it
 * start (from the background, on some systems), the run goes on without it, as before.
 */
public final class RunService extends Service {
    private static final String CHANNEL = "keep";
    private static final int ID = 12;
    private static boolean on;

    /** A run started or ended (main thread): keep the process while a chat works, let go when none does. */
    static void update(Context c) {
        Context app = c.getApplicationContext();
        boolean want = ChatHub.get(app).runningCount() > 0;
        if (want == on) return;
        Intent i = new Intent(app, RunService.class);
        try {
            if (want) app.startService(i);
            else app.stopService(i);
            on = want;
        } catch (Exception ignored) { // not allowed to start from here: the run goes on without it
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            Notification n = notice();
            if (android.os.Build.VERSION.SDK_INT >= 34) {
                startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
            } else {
                startForeground(ID, n);
            }
        } catch (Exception e) { // Android refused the foreground state: nothing gained, nothing lost
            on = false;
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ChatHub.get(this).runningCount() == 0) { // the run ended before this started
            on = false;
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    private Notification notice() {
        NotificationChannel ch = new NotificationChannel(CHANNEL, getString(R.string.keep_channel_name),
                NotificationManager.IMPORTANCE_MIN);
        ch.setDescription(getString(R.string.keep_channel_description));
        ch.setShowBadge(false);
        getSystemService(NotificationManager.class).createNotificationChannel(ch);
        PendingIntent open = PendingIntent.getActivity(this, ID,
                new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setContentTitle(getString(R.string.keep_title))
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setContentIntent(open)
                .build();
    }

    @Override
    public void onDestroy() {
        on = false;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
