package app.buddy.assistant;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.database.ContentObserver;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;

/**
 * "Keep Wireless debugging on" (Settings → Background mode): the phone switches Wireless debugging off by itself (a
 * Wi-Fi change, a timeout, some phones now and then), and without it background mode's helper can't be started again,
 * nor do what needs it (putting Buddy's accessibility back, phone commands). When it goes off while background mode
 * is set up, Buddy turns it straight back on: itself where the phone gave it the right, else through the helper while
 * that still runs (as the shell user, it may). It needs Wi-Fi, so with none Buddy waits for Wi-Fi to come back. When
 * neither can (the helper ended with it), a notification says so once, with the way to start it again.
 */
final class DebugLock {
    private static final String CHANNEL = "debug_lock";
    private static final int NOTE_ID = 0x0de6;
    private static final long SETTLE_MS = 3000;
    private static boolean started;
    private static Handler main;
    /** Told once per time it went off and couldn't be put back; again only after it was on in between. */
    private static boolean told;
    /**
     * One try per time it goes off (and per Wi-Fi network joined): on a network the phone doesn't trust it asks the
     * user, and a "no" there turns it off again, which must not start the question over and over.
     */
    private static boolean tried;
    private static ContentObserver observer;
    private static ConnectivityManager.NetworkCallback wifiBack;
    /** USB plugged in or out: on some phones (a OnePlus 12, measured) that restarts adbd, and the helper ends with it. */
    private static android.content.BroadcastReceiver usb;
    private static final Handler again = new Handler(Looper.getMainLooper());
    /** When starting the helper again by itself last failed (0: it didn't): not tried again for a while after. */
    private static long restartFailedAt;
    private static final long RESTART_PAUSE_MS = 10 * 60_000;
    private static Network lastWifi;

    private DebugLock() {
    }

    static void start(BuddyService svc) {
        if (started) return;
        started = true;
        main = new Handler(Looper.getMainLooper());
        observer = new ContentObserver(main) {
            @Override
            public void onChange(boolean self) {
                later(svc);
            }
        };
        svc.getContentResolver().registerContentObserver(Settings.Global.getUriFor("adb_wifi_enabled"), false, observer);
        try {
            ConnectivityManager cm = svc.getSystemService(ConnectivityManager.class);
            wifiBack = new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network n) {
                    if (!n.equals(lastWifi)) {
                        lastWifi = n;
                        tried = false; // another network: one more try there
                        restartFailedAt = 0;
                    }
                    later(svc); // Wi-Fi back: now it can be on again
                }
            };
            cm.registerNetworkCallback(new NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(),
                    wifiBack);
        } catch (Exception ignored) {
        }
        try {
            usb = new android.content.BroadcastReceiver() {
                @Override
                public void onReceive(Context c, Intent i) {
                    later(svc);
                    // (and again once adbd is surely back: not cancelled by later; one pending at a time, as the
                    // broadcast comes several times per plug)
                    again.removeCallbacksAndMessages(null);
                    again.postDelayed(() -> later(svc), 15000);
                }
            };
            svc.registerReceiver(usb, new android.content.IntentFilter("android.hardware.usb.action.USB_STATE"),
                    Context.RECEIVER_EXPORTED);
        } catch (Exception ignored) {
        }
        later(svc);
    }

    /** After a moment: the phone switches it off and on itself around a Wi-Fi change. */
    private static void later(BuddyService svc) {
        main.removeCallbacksAndMessages(null);
        main.postDelayed(() -> new Thread(() -> check(svc), "debug-lock").start(), SETTLE_MS);
    }

    static boolean on(Context c) {
        return Settings.Global.getInt(c.getContentResolver(), "adb_wifi_enabled", 0) == 1;
    }

    private static boolean wifi(Context c) {
        try {
            ConnectivityManager cm = c.getSystemService(ConnectivityManager.class);
            for (Network n : cm.getAllNetworks()) {
                NetworkCapabilities nc = cm.getNetworkCapabilities(n);
                if (nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    private static synchronized void check(BuddyService svc) {
        if (on(svc)) {
            told = false;
            tried = false;
            clearNote(svc);
            // on, but the helper may be gone (adbd restarted, as a USB change does on some phones): started again now,
            // which needs no screen while Wireless debugging is on. Else the next time it goes off nothing could put
            // it back, and it would wait for the user's Start now
            // (not again and again when it fails, e.g. the pairing is gone: once per network or every ten minutes)
            long now = android.os.SystemClock.elapsedRealtime();
            if (Prefs.debugLock(svc) && Background.setUp(svc) && (restartFailedAt == 0 || now - restartFailedAt > RESTART_PAUSE_MS)
                    && !Background.alive(svc)) {
                restartFailedAt = Background.start(svc, false) == null ? 0 : android.os.SystemClock.elapsedRealtime();
            }
            return;
        }
        if (!Prefs.debugLock(svc) || !Background.setUp(svc) || !wifi(svc) || tried) return;
        tried = true;
        boolean done = false;
        String why; // what happened, in the phone's own words where it said any (shown on the notification)
        if (Background.canSwitchWireless(svc)) {
            try {
                done = Settings.Global.putInt(svc.getContentResolver(), "adb_wifi_enabled", 1);
            } catch (Exception e) {
                why = String.valueOf(e.getMessage());
            }
        }
        if (!done) {
            String[] r = Background.execIfRunning(svc, "settings put global adb_wifi_enabled 1 2>&1; echo \"exit $?\"", 10);
            done = r != null && (r[0] + " " + r[1]).contains("exit 0");
            why = r == null ? "the helper isn't running" : ("helper: " + (r[0] + " " + r[1]).trim());
        } else why = "set by Buddy";
        boolean asks = false;
        if (done) {
            try {
                Thread.sleep(2000);
            } catch (InterruptedException ignored) {
            }
            if (on(svc)) return;
            // set, and off again at once: Android does that on a Wi-Fi access point the user hasn't allowed yet, and
            // asks them ("Allow wireless debugging on this network?"). A mesh network has several access points
            asks = true;
            why += "; it went off again within 2 s";
        }
        if (!told) {
            told = true;
            note(svc, why, asks);
        }
    }

    private static void note(Context c, String why, boolean asks) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, c.getString(R.string.debug_lock_channel),
                NotificationManager.IMPORTANCE_DEFAULT));
        // (asks: a tap tries once more, so Android's question comes up in front of the user)
        PendingIntent open = PendingIntent.getActivity(c, NOTE_ID, new Intent(c, BackgroundActivity.class)
                .putExtra("debug_lock_again", asks).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        String text = asks ? c.getString(R.string.debug_lock_ask_text) : c.getString(R.string.debug_lock_off_text) + "\n(" + why + ")";
        Notification n = new Notification.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setContentTitle(c.getString(asks ? R.string.debug_lock_ask_title : R.string.debug_lock_off_title))
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build();
        try {
            nm.notify(NOTE_ID, n);
        } catch (SecurityException ignored) { // notifications off
        }
    }

    private static void clearNote(Context c) {
        c.getSystemService(NotificationManager.class).cancel(NOTE_ID);
    }

    /** The switch changed: act on it now (turned on by the user: one more try). */
    static void changed(BuddyService svc) {
        tried = false;
        if (svc != null && main != null) later(svc);
    }

    /** The service ends: its watchers go with it, and the next one starts them again. */
    static void stop(BuddyService svc) {
        if (!started) return;
        started = false;
        if (main != null) main.removeCallbacksAndMessages(null);
        again.removeCallbacksAndMessages(null);
        try {
            if (observer != null) svc.getContentResolver().unregisterContentObserver(observer);
            if (wifiBack != null) svc.getSystemService(ConnectivityManager.class).unregisterNetworkCallback(wifiBack);
            if (usb != null) svc.unregisterReceiver(usb);
        } catch (Exception ignored) {
        }
        observer = null;
        wifiBack = null;
        usb = null;
    }
}
