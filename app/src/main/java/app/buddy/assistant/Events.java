package app.buddy.assistant;

import android.app.KeyguardManager;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.net.wifi.WifiInfo;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Events that start a chat's scheduled message (see Schedules): the charger connected or disconnected, Wi-Fi connected
 * (to any network or a named one) or disconnected, a Bluetooth device connected (any or a named one), and the phone
 * unlocked for the first time in the day. Android delivers none of these to a receiver in the manifest any more except
 * Bluetooth's, and a run needs Buddy's service anyway, so all of them are registered by the service while it runs.
 *
 * A chat's runs from events are capped per day (Plan.cap, 5 unless the user sets 1 to 20) and come at least 10 minutes
 * apart, so a flapping Wi-Fi or a loose charger cable can't spend the user's plan. Main thread.
 */
final class Events {
    static final String CHARGER_ON = "charger_on", CHARGER_OFF = "charger_off", WIFI_ON = "wifi_on", WIFI_OFF = "wifi_off",
            BT_ON = "bt_on", UNLOCK = "unlock";
    /** In the order the schedule sheet lists them. */
    static final String[] ALL = {CHARGER_ON, CHARGER_OFF, WIFI_ON, WIFI_OFF, BT_ON, UNLOCK};
    static final int CAP_DEFAULT = 5, CAP_MIN = 1, CAP_MAX = 20;
    static final long GAP_MS = 10 * 60_000L;

    private static final Handler main = new Handler(Looper.getMainLooper());
    private static BroadcastReceiver receiver;
    private static ConnectivityManager.NetworkCallback wifiCallback;
    /** The Wi-Fi networks up now (seeded at start: one already there isn't "connected"). */
    private static final Set<Network> wifiUp = new HashSet<>();

    private Events() {
    }

    // ------------------------------------------------------------------ listening

    /** The service started: from now on these events can start runs. */
    static void start(BuddyService svc) {
        if (receiver != null) return;
        receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                String a = String.valueOf(i.getAction());
                switch (a) {
                    case Intent.ACTION_POWER_CONNECTED: fire(svc, CHARGER_ON, null); break;
                    case Intent.ACTION_POWER_DISCONNECTED: fire(svc, CHARGER_OFF, null); break;
                    case BluetoothDevice.ACTION_ACL_CONNECTED: {
                        BluetoothDevice d = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                        fire(svc, BT_ON, d == null ? null : d.getAddress());
                        break;
                    }
                    case Intent.ACTION_USER_PRESENT:
                        unlocked(svc);
                        break;
                    case Intent.ACTION_SCREEN_ON: // a phone without a lock screen says nothing else
                        if (!svc.getSystemService(KeyguardManager.class).isKeyguardLocked()) unlocked(svc);
                        break;
                    default:
                        break;
                }
            }
        };
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_POWER_CONNECTED);
        f.addAction(Intent.ACTION_POWER_DISCONNECTED);
        f.addAction(BluetoothDevice.ACTION_ACL_CONNECTED); // (only with the Nearby devices permission, Android 12+)
        f.addAction(Intent.ACTION_USER_PRESENT);
        f.addAction(Intent.ACTION_SCREEN_ON);
        // exported: Bluetooth's broadcasts come from the Bluetooth app, not the system (the rest are the system's own)
        if (Build.VERSION.SDK_INT >= 33) svc.registerReceiver(receiver, f, null, main, Context.RECEIVER_EXPORTED);
        else svc.registerReceiver(receiver, f, null, main);

        ConnectivityManager cm = svc.getSystemService(ConnectivityManager.class);
        wifiUp.clear();
        try {
            for (Network n : cm.getAllNetworks()) {
                NetworkCapabilities nc = cm.getNetworkCapabilities(n);
                // the same networks the callback below hears of (its request: Wi-Fi with internet, not a VPN), or
                // one it never reports lost would keep "Wi-Fi is up" for good
                if (nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !nc.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                        && nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                        && nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)) wifiUp.add(n);
            }
        } catch (Exception ignored) {
        }
        // with location info: the network's name, when Buddy may read it (fine location, location on)
        wifiCallback = Build.VERSION.SDK_INT >= 31
                ? new ConnectivityManager.NetworkCallback(ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO) {
                    @Override
                    public void onCapabilitiesChanged(Network n, NetworkCapabilities nc) {
                        wifiChanged(svc, n, nc);
                    }

                    @Override
                    public void onLost(Network n) {
                        wifiLost(svc, n);
                    }
                }
                : new ConnectivityManager.NetworkCallback() {
                    @Override
                    public void onCapabilitiesChanged(Network n, NetworkCapabilities nc) {
                        wifiChanged(svc, n, nc);
                    }

                    @Override
                    public void onLost(Network n) {
                        wifiLost(svc, n);
                    }
                };
        try {
            cm.registerNetworkCallback(new NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI).build(),
                    wifiCallback, main);
        } catch (Exception e) {
            wifiCallback = null;
        }
        // the service came up after the phone was unlocked (after a restart): that counts as today's first unlock
        if (!svc.getSystemService(KeyguardManager.class).isKeyguardLocked() && BuddyService.screenOn(svc)) unlocked(svc);
    }

    static void stop(Context c) {
        try {
            if (receiver != null) c.unregisterReceiver(receiver);
        } catch (Exception ignored) {
        }
        receiver = null;
        try {
            if (wifiCallback != null) c.getSystemService(ConnectivityManager.class).unregisterNetworkCallback(wifiCallback);
        } catch (Exception ignored) {
        }
        wifiCallback = null;
    }

    private static void wifiChanged(Context c, Network n, NetworkCapabilities nc) {
        if (wifiUp.contains(n)) return;
        boolean first = wifiUp.isEmpty();
        wifiUp.add(n);
        if (!first) return;
        String ssid = null;
        if (Build.VERSION.SDK_INT >= 31 && nc.getTransportInfo() instanceof WifiInfo) ssid = ssid(((WifiInfo) nc.getTransportInfo()).getSSID());
        if (ssid != null || !namedWifiWanted(c)) {
            fire(c, WIFI_ON, ssid);
            return;
        }
        // Android kept the name from Buddy (no location access in the background): background mode's helper can
        // read it, when it runs. Unknown, it never matches a named network.
        new Thread(() -> {
            String[] r = Background.execIfRunning(c, "cmd wifi status", 5);
            String name = null;
            if (r != null && "0".equals(r[0].trim())) {
                Matcher m = HELPER_SSID.matcher(r[1]);
                if (m.find()) name = m.group(1);
            }
            final String got = name;
            main.post(() -> fire(c, WIFI_ON, got));
        }, "wifi-name").start();
    }

    /** `cmd wifi status` (WifiShellCommand): "Wifi is connected to \"name\"". */
    private static final Pattern HELPER_SSID = Pattern.compile("Wifi is connected to \"([^\"]+)\"");

    private static void wifiLost(Context c, Network n) {
        if (!wifiUp.remove(n) || !wifiUp.isEmpty()) return;
        fire(c, WIFI_OFF, null);
    }

    /** The name without Android's quotes; null when Buddy may not read it. */
    static String ssid(String raw) {
        if (raw == null || raw.isEmpty() || "<unknown ssid>".equals(raw)) return null;
        if (raw.length() >= 2 && raw.startsWith("\"") && raw.endsWith("\"")) return raw.substring(1, raw.length() - 1);
        return raw;
    }

    /** The Wi-Fi network the phone is on now (for the schedule sheet, in front), or null. */
    @SuppressWarnings("deprecation")
    static String currentWifi(Context c) {
        try {
            android.net.wifi.WifiManager wm = c.getSystemService(android.net.wifi.WifiManager.class);
            return wm == null ? null : ssid(wm.getConnectionInfo().getSSID());
        } catch (Exception e) {
            return null;
        }
    }

    private static void unlocked(Context c) {
        fire(c, UNLOCK, null); // (the day's first, per chat: see fire)
    }

    /** Each chat whose schedule waits for this event (a named network or device must match) gets its run. */
    private static void fire(Context c, String kind, String name) {
        for (Sessions.Session s : new ArrayList<>(ChatHub.get(c).sessions.all)) {
            Schedules.Plan p = s.schedule;
            if (p == null || p.paused || !p.events.contains(kind)) continue;
            if (WIFI_ON.equals(kind) && !p.wifi.isEmpty() && !p.wifi.equals(name)) continue;
            if (BT_ON.equals(kind) && !p.bt.isEmpty() && !p.bt.equalsIgnoreCase(String.valueOf(name))) continue;
            if (UNLOCK.equals(kind)) {
                // the day's first unlock for this chat is the first its run was taken at: one refused (another event
                // ran it minutes ago, or a run still waits) leaves the next unlock to be it
                String key = "unlock_day_" + s.id;
                int today = day();
                if (prefs(c).getInt(key, 0) == today) continue;
                if (Schedules.fireEvent(c, s, english(p, kind))) prefs(c).edit().putInt(key, today).apply();
                continue;
            }
            Schedules.fireEvent(c, s, english(p, kind));
        }
    }

    private static boolean namedWifiWanted(Context c) {
        for (Sessions.Session s : new ArrayList<>(ChatHub.get(c).sessions.all)) {
            if (s.schedule != null && s.schedule.events.contains(WIFI_ON) && !s.schedule.wifi.isEmpty()) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ words

    /** For the agent, in English like the rest of its instructions: "the charger is connected". */
    static String english(Schedules.Plan p, String kind) {
        switch (kind) {
            case CHARGER_ON: return "the charger is connected";
            case CHARGER_OFF: return "the charger is disconnected";
            case WIFI_ON: return p.wifi.isEmpty() ? "the phone connects to Wi-Fi" : "the phone connects to the Wi-Fi network \"" + p.wifi + "\"";
            case WIFI_OFF: return "the phone disconnects from Wi-Fi";
            case BT_ON: return p.bt.isEmpty() ? "a Bluetooth device connects" : "the Bluetooth device \"" + p.btName + "\" connects";
            default: return "the phone is unlocked for the first time in the day";
        }
    }

    /** For the user: "Charger connected", "Wi-Fi “Home” connected". */
    static String label(Context c, Schedules.Plan p, String kind) {
        switch (kind) {
            case CHARGER_ON: return c.getString(R.string.event_charger_on);
            case CHARGER_OFF: return c.getString(R.string.event_charger_off);
            case WIFI_ON: return p.wifi.isEmpty() ? c.getString(R.string.event_wifi_on) : c.getString(R.string.event_wifi_on_named, p.wifi);
            case WIFI_OFF: return c.getString(R.string.event_wifi_off);
            case BT_ON: return p.bt.isEmpty() ? c.getString(R.string.event_bt_on) : c.getString(R.string.event_bt_on_named, p.btName);
            default: return c.getString(R.string.event_unlock);
        }
    }

    /** The plan's events for the user, joined ("Charger connected, First unlock of the day"). */
    static String summary(Context c, Schedules.Plan p) {
        StringBuilder b = new StringBuilder();
        for (String k : ALL) if (p.events.contains(k)) b.append(b.length() > 0 ? ", " : "").append(label(c, p, k));
        return b.toString();
    }

    /** Can Buddy see Bluetooth devices connect (Android 12+ asks the user for Nearby devices)? */
    static boolean bluetoothAllowed(Context c) {
        return Build.VERSION.SDK_INT < 31
                || c.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
    }

    static boolean locationAllowed(Context c) {
        return c.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }

    /** A chat's schedule waits for a Bluetooth device / a named Wi-Fi network (what Set up what's missing asks for). */
    static boolean anyWants(Context c, boolean bluetooth) {
        for (Sessions.Session s : new ArrayList<>(ChatHub.get(c).sessions.all)) {
            Schedules.Plan p = s.schedule;
            if (p == null || p.paused) continue;
            if (bluetooth ? p.events.contains(BT_ON) : p.events.contains(WIFI_ON) && !p.wifi.isEmpty()) return true;
        }
        return false;
    }

    // ------------------------------------------------------------------ the cap

    /** Kept outside the chat's plan (the schedule sheet replaces that), in wall-clock time. */
    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("events", Context.MODE_PRIVATE);
    }

    private static int day() {
        Calendar k = Calendar.getInstance();
        return k.get(Calendar.YEAR) * 10000 + (k.get(Calendar.MONTH) + 1) * 100 + k.get(Calendar.DAY_OF_MONTH);
    }

    /** Runs this chat's events started today. */
    static int runsToday(Context c, String chat) {
        SharedPreferences p = prefs(c);
        return p.getInt(chat + ".day", 0) == day() ? p.getInt(chat + ".count", 0) : 0;
    }

    /** May an event start this chat's run now: under its cap for today, and 10 minutes after the last one? */
    static boolean allowed(Context c, Sessions.Session s) {
        long last = prefs(c).getLong(s.id + ".last", 0), now = System.currentTimeMillis();
        boolean gap = last <= 0 || last > now || now - last >= GAP_MS; // (a clock set back doesn't hold it up for good)
        return gap && runsToday(c, s.id) < s.schedule.cap;
    }

    /** An event came for this chat: the 10 minutes count from here, whether it runs or is skipped. */
    static void tried(Context c, String chat) {
        prefs(c).edit().putLong(chat + ".last", System.currentTimeMillis()).apply();
    }

    /** An event's run started: it counts toward today's cap. */
    static void ran(Context c, String chat) {
        prefs(c).edit().putInt(chat + ".day", day()).putInt(chat + ".count", runsToday(c, chat) + 1).apply();
    }

    static void forget(Context c, String chat) {
        prefs(c).edit().remove(chat + ".day").remove(chat + ".count").remove(chat + ".last").remove("unlock_day_" + chat).apply();
    }
}
