package app.buddy.assistant;

import android.app.AppOpsManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStatsManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.os.Process;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Bank mode, automatic: while a banking app is on screen every accessibility service is off (see PauseTile), and they
 * come back when it leaves. Banks refuse to open while any of them runs, and remembering to tap the tile first is easy
 * to forget.
 *
 * The app on screen comes from Usage access (Android's own record of which activity was started), so this is a
 * foreground service of its own: turning accessibility off doesn't stop it. It acts only when the app on screen
 * changes, so a tap on the tile isn't undone while the banking app stays open. Ticking stops while the screen is off.
 */
public class BankWatch extends Service {
    /** Apps that refuse to run with an accessibility service on: Lao banking and wallet apps. */
    private static final Set<String> BANKS = new HashSet<>(Arrays.asList(
            "com.bcel.bcelone", "com.bcel.onepayshop", "com.ldb.wallet", "com.umoney.enduser"));
    private static final String CHANNEL = "bank";
    private static final int ID = 4412;
    private static final long TICK = 1200;
    /** How far back the app on screen is looked for (the newest start wins). */
    private static final long BACK = 60_000;

    private final Handler main = new Handler(Looper.getMainLooper());
    private String shown; // the app on screen, as it was last seen
    private boolean ticking;

    /** Starts or stops the watch as the setting says (safe to call from anywhere). */
    static void sync(Context c) {
        if (Prefs.bankAuto(c) && PauseTile.allowed(c) && usageAllowed(c)) start(c);
        else stop(c);
    }

    static void start(Context c) {
        c.startForegroundService(new Intent(c, BankWatch.class));
    }

    static void stop(Context c) {
        c.stopService(new Intent(c, BankWatch.class));
    }

    /** Android's Usage access: Buddy may see which app is on screen. */
    static boolean usageAllowed(Context c) {
        AppOpsManager m = c.getSystemService(AppOpsManager.class);
        return m.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.getPackageName())
                == AppOpsManager.MODE_ALLOWED;
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!Prefs.bankAuto(BankWatch.this) || !PauseTile.allowed(BankWatch.this)) {
                stopSelf();
                return;
            }
            String top = onScreen();
            if (top != null && !top.equals(shown)) {
                shown = top;
                boolean bank = BANKS.contains(top);
                if (bank && !PauseTile.paused(BankWatch.this)) {
                    if (PauseTile.pause(BankWatch.this)) Prefs.setAutoPaused(BankWatch.this, true);
                } else if (!bank && Prefs.autoPaused(BankWatch.this)) {
                    Prefs.setAutoPaused(BankWatch.this, false);
                    PauseTile.resume(BankWatch.this);
                }
            }
            main.postDelayed(this, TICK);
        }
    };

    /** The app whose activity started last in the last minute; null when Android recorded none. */
    private String onScreen() {
        long now = System.currentTimeMillis();
        UsageEvents ev = getSystemService(UsageStatsManager.class).queryEvents(now - BACK, now);
        if (ev == null) return null;
        UsageEvents.Event e = new UsageEvents.Event();
        String top = null;
        while (ev.hasNextEvent()) {
            ev.getNextEvent(e);
            if (e.getEventType() == UsageEvents.Event.ACTIVITY_RESUMED) top = e.getPackageName();
        }
        return top;
    }

    /** Ticking only while the screen is on: with it off no banking app is being used. */
    private final BroadcastReceiver screen = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            if (Intent.ACTION_SCREEN_ON.equals(i.getAction())) wake();
            else stand();
        }
    };

    private void wake() {
        if (!ticking && Prefs.bankAuto(this)) {
            ticking = true;
            main.removeCallbacks(tick);
            main.post(tick);
        }
    }

    private void stand() {
        ticking = false;
        main.removeCallbacks(tick);
    }

    @Override
    public void onCreate() {
        super.onCreate();
        NotificationChannel ch = new NotificationChannel(CHANNEL, getString(R.string.bank_channel),
                NotificationManager.IMPORTANCE_LOW);
        ch.setShowBadge(false);
        getSystemService(NotificationManager.class).createNotificationChannel(ch);
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(R.drawable.ic_pause)
                .setContentTitle(getString(R.string.bank_watch_title))
                .setContentText(getString(R.string.bank_watch_text))
                .setOngoing(true)
                .setContentIntent(PendingIntent.getActivity(this, 0, new Intent(this, SetupActivity.class),
                        PendingIntent.FLAG_IMMUTABLE))
                .build();
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(ID, n);
        }
        register(Intent.ACTION_SCREEN_ON);
        register(Intent.ACTION_SCREEN_OFF);
    }

    /** Listens for one of Android's screen broadcasts (not exported: only Android sends them). */
    private void register(String action) {
        IntentFilter f = new IntentFilter(action);
        if (Build.VERSION.SDK_INT >= 33) registerReceiver(screen, f, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(screen, f);
    }

    @Override
    public int onStartCommand(Intent i, int flags, int start) {
        if (getSystemService(PowerManager.class).isInteractive()) wake();
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        stand();
        try {
            unregisterReceiver(screen);
        } catch (Exception ignored) {
        }
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent i) {
        return null;
    }
}
