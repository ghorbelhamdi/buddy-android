package app.buddy.assistant;

import android.content.Context;
import android.content.pm.PackageManager;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Background mode: a chat's agent works on an invisible display of its own, so the user keeps their screen, their
 * keyboard and their Back button. Android lets only the shell user make such a display, so Buddy pairs with the
 * phone's own Wireless debugging once (adb, run in Buddy's Linux) and starts a small helper as the shell user (see
 * Shell). From then on Buddy talks to the helper directly; adb is needed again only to start it after a reboot or an
 * update.
 *
 * The phone tools ask here which display a chat works on (display), and send the helper what the accessibility
 * service can only do on the real screen: touches, Back, opening an app, a picture.
 */
final class Background {
    /** In front of a chat's id: the bubble's live picture is that chat's background screen, not a browser tab. */
    static final String KEY = "bg:";
    /** Where the helper listens (this phone only). */
    static final int PORT = 8771;
    /** Where the helper's own output goes (the shell user's folder): read back when it doesn't start. */
    private static final String LOG = "/data/local/tmp/buddy-helper.log";
    /** Buddy's adb keeps a server of its own: another app's (Termux's) on the usual port has other keys. */
    private static final String ADB = "export HOME=/root ANDROID_ADB_SERVER_PORT=5138; ";
    private static final String SECURE = "android.permission.WRITE_SECURE_SETTINGS";

    /** Set up, step by step; the screen shows it. */
    enum Step {IDLE, INSTALLING, WAITING, PAIRING, STARTING, READY, FAILED}

    private static volatile Step step = Step.IDLE;
    private static volatile String detail = "";
    private static volatile Runnable listener;
    private static volatile long waitUntil;
    /** chat → the id of its display, while the helper has one for it. */
    private static final Map<String, Integer> displays = new ConcurrentHashMap<>();
    private static final Object starting = new Object();

    private Background() {
    }

    static final class Unavailable extends Exception {
        Unavailable(String why) {
            super(why);
        }
    }

    // ------------------------------------------------------------------ which chats, which display

    /** Paired with this phone's Wireless debugging: background mode can be used. */
    static boolean setUp(Context c) {
        return Prefs.backgroundPaired(c);
    }

    /** Does this chat work in the background (its switch is on and the phone is set up for it)? */
    static boolean on(Context c, String chat) {
        if (chat == null || !setUp(c)) return false;
        Sessions.Session s = ChatHub.get(c).sessions.find(chat);
        return s != null && s.background;
    }

    /**
     * The display the chat works on, made now if it has none (and the helper started if it isn't running). Called for
     * every phone tool of a background chat, which also tells the helper the display is still in use.
     */
    static int display(Context c, String chat) throws Unavailable {
        DisplayMetrics m = c.getResources().getDisplayMetrics();
        String open = "open\t" + chat + "\t" + m.widthPixels + "\t" + m.heightPixels + "\t" + m.densityDpi;
        String id;
        try {
            id = call(c, open);
        } catch (IOException e) {
            String why = start(c);
            if (why != null) throw new Unavailable(why);
            try {
                id = call(c, open);
            } catch (IOException e2) {
                throw new Unavailable(String.valueOf(e2.getMessage()));
            }
        }
        int d = Integer.parseInt(id.trim());
        displays.put(chat, d);
        keepAlive(c.getApplicationContext());
        return d;
    }

    /** The chat's display if it has one right now, else the real screen's id. Doesn't make one. */
    static int displayOf(String chat) {
        Integer d = chat == null ? null : displays.get(chat);
        return d == null ? Display.DEFAULT_DISPLAY : d;
    }

    /** The chat's display goes, with what's on it. */
    static void close(Context c, String chat) {
        if (displays.remove(chat) == null) return;
        new Thread(() -> {
            try {
                call(c, "close\t" + chat);
            } catch (IOException ignored) {
            }
        }).start();
    }

    /**
     * The chat's run ended: its display sleeps until the next tool call wakes it. An app left awake there counts as
     * on screen, so a messenger would mark new messages read and post no notification (which notification rules wait
     * for).
     */
    static void rest(final Context c, final String chat) {
        if (!displays.containsKey(chat)) return;
        new Thread(() -> {
            try {
                call(c, "rest\t" + chat);
            } catch (IOException ignored) {
            }
        }).start();
    }

    /** What the chat's display shows, as JPEG (maxWidth 0: full size); null when there's nothing to show. */
    static byte[] frame(Context c, String chat, int maxWidth, int quality) {
        if (!displays.containsKey(chat)) return null;
        try {
            byte[] b = request(c, "frame\t" + chat + "\t" + maxWidth + "\t" + quality);
            return b.length == 0 ? null : b;
        } catch (IOException e) {
            if (String.valueOf(e.getMessage()).contains("no display")) displays.remove(chat); // let go after idling
            return null;
        }
    }

    /** One finger along the points on the chat's display, in that time (a tap or a long press: one point). */
    static boolean gesture(Context c, String chat, List<float[]> points, long ms) {
        if (points.isEmpty()) return false;
        StringBuilder b = new StringBuilder("gesture\t").append(chat).append('\t').append(ms);
        for (float[] xy : points) b.append('\t').append(Math.round(xy[0])).append(',').append(Math.round(xy[1]));
        try {
            call(c, b.toString());
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** The app in front on the chat's display (its package), or null. */
    static String appOn(BuddyService svc, String chat) {
        int d = displayOf(chat);
        if (d == Display.DEFAULT_DISPLAY) return null;
        List<AccessibilityWindowInfo> ws = svc.getWindowsOnAllDisplays().get(d);
        AccessibilityWindowInfo top = null;
        if (ws != null) for (AccessibilityWindowInfo w : ws)
            if (w.getType() == AccessibilityWindowInfo.TYPE_APPLICATION && (top == null || w.getLayer() > top.getLayer())) top = w;
        AccessibilityNodeInfo root = top == null ? null : top.getRoot();
        return root == null || root.getPackageName() == null ? null : root.getPackageName().toString();
    }

    /**
     * "Show on my screen": the app the chat works in comes to the real screen, the chat's display goes and the chat
     * works on the real screen from here on. Returns the app's package, or null when the display was empty. Not for
     * the main thread (it talks to the helper).
     */
    static String bringToScreen(BuddyService svc, Sessions.Session ses) {
        String pkg = appOn(svc, ses.id);
        android.content.Intent launch = pkg == null ? null : svc.getPackageManager().getLaunchIntentForPackage(pkg);
        if (launch != null && launch.getComponent() != null) {
            try {
                call(svc, "show\t" + launch.getComponent().flattenToString());
            } catch (IOException ignored) {
            }
        }
        if (displays.remove(ses.id) != null) {
            try {
                call(svc, "close\t" + ses.id);
            } catch (IOException ignored) {
            }
        }
        svc.runOnMain(() -> ChatHub.get(svc).setBackground(ses, false));
        return launch == null ? null : pkg;
    }

    /** Displays of chats that are still at work stay, however long the agent thinks or a card waits (see Shell.IDLE_MS). */
    private static java.util.concurrent.ScheduledExecutorService keeper;

    private static synchronized void keepAlive(final Context c) {
        if (keeper != null) return;
        keeper = Executors.newSingleThreadScheduledExecutor();
        keeper.scheduleWithFixedDelay(() -> {
            for (String chat : new ArrayList<>(displays.keySet())) {
                Sessions.Session s = ChatHub.get(c).sessions.find(chat);
                if (s == null || !s.background) {
                    close(c, chat);
                } else if (s.running) {
                    try {
                        call(c, "keep\t" + chat);
                    } catch (IOException e) {
                        displays.remove(chat); // the helper let it go, or is gone itself
                    }
                }
            }
        }, 2, 2, TimeUnit.MINUTES);
    }

    // ------------------------------------------------------------------ the helper

    /** One request to the helper: its answer without the "OK ", or an IOException saying what went wrong. */
    static String call(Context c, String command) throws IOException {
        return new String(request(c, command), StandardCharsets.UTF_8);
    }

    private static byte[] request(Context c, String command) throws IOException {
        return request(c, command, 70_000);
    }

    private static byte[] request(Context c, String command, int waitMs) throws IOException {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", PORT), 1500);
            s.setSoTimeout(waitMs);
            s.getOutputStream().write((Prefs.backgroundToken(c) + "\n" + command + "\n").getBytes(StandardCharsets.UTF_8));
            s.getOutputStream().flush();
            InputStream in = s.getInputStream();
            ByteArrayOutputStream head = new ByteArrayOutputStream();
            for (int ch; (ch = in.read()) >= 0 && ch != '\n'; ) head.write(ch);
            String line = new String(head.toByteArray(), StandardCharsets.UTF_8);
            if (!line.startsWith("OK")) throw new IOException(line.startsWith("ERR ") ? line.substring(4) : "no answer");
            String rest = line.length() > 3 ? line.substring(3) : "";
            if (!command.startsWith("frame\t") && !command.startsWith("exec\t")) return rest.getBytes(StandardCharsets.UTF_8);
            int n = Integer.parseInt(rest.trim());
            byte[] body = new byte[n];
            for (int at = 0, r; at < n; at += r) {
                r = in.read(body, at, n - at);
                if (r < 0) throw new IOException("cut off");
            }
            return body;
        }
    }

    /**
     * Phone commands (issue #22): a command line run on the phone itself by the helper, as the shell user (what
     * `adb shell` gives). {exit code, output}; the helper is started first if it isn't running.
     */
    static String[] exec(Context c, String command, int seconds) throws Unavailable {
        String line = "exec\t" + seconds + "\t" + android.util.Base64.encodeToString(
                command.getBytes(StandardCharsets.UTF_8), android.util.Base64.NO_WRAP);
        byte[] body;
        try {
            if (!alive(c)) {
                String why = start(c);
                if (why != null) throw new Unavailable(why);
            }
            body = request(c, line, (seconds + 20) * 1000);
        } catch (IOException e) {
            throw new Unavailable(String.valueOf(e.getMessage()));
        }
        String all = new String(body, StandardCharsets.UTF_8);
        int nl = all.indexOf('\n');
        return nl < 0 ? new String[]{all.trim(), ""} : new String[]{all.substring(0, nl), all.substring(nl + 1)};
    }

    /** Is the helper running, and this version's? */
    static boolean alive(Context c) {
        try {
            return call(c, "ping").trim().startsWith(Shell.VERSION + " ");
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Starts the helper over adb (once per boot, or after an update changed it). null when it runs; else why not, in
     * words for the agent and the user.
     */
    static String start(Context c) {
        return start(c, false);
    }

    /**
     * asked: the user tapped Start now, so where Wireless debugging is off Buddy may switch it on itself by walking
     * Settings (a phone that lets Buddy's adb grant it the right needs no walk).
     */
    static String start(Context c, boolean asked) {
        synchronized (starting) {
            if (alive(c)) return null;
            if (!setUp(c)) return "background mode isn't set up (Settings → Background mode)";
            if (!Linux.installed(c)) return "background mode needs Buddy's Linux, which isn't installed";
            int known = Prefs.backgroundAdbPort(c);
            if (known > 0 && listening(known)) { // the port it had last time, or the one Settings just showed
                launch(c, Collections.singletonList(known));
                for (int i = 0; i < 12; i++) {
                    if (alive(c)) {
                        stay(c);
                        return null;
                    }
                    sleep(300);
                }
            }
            List<Integer> ports = adbPorts(c);
            if (ports.isEmpty() && wirelessDebugging(c, true)) {
                for (int i = 0; i < 12 && ports.isEmpty(); i++) {
                    sleep(700);
                    ports = adbPorts(c);
                }
            }
            BuddyService svc = BuddyService.get();
            if (ports.isEmpty() && asked && svc != null) {
                walking = true;
                int port;
                try {
                    port = openWireless(svc, false);
                } finally {
                    walking = false;
                }
                for (int i = 0; i < 10 && port > 0 && !listening(port); i++) sleep(300);
                if (port > 0 && listening(port)) ports = Collections.singletonList(port);
                else ports = adbPorts(c);
            }
            if (ports.isEmpty()) {
                return "Wireless debugging is off, and background mode needs it once after a restart of the phone: the "
                        + "user opens Buddy → Settings → Background mode and taps Start now (Wi-Fi must be connected)";
            }
            String out = launch(c, ports);
            for (int i = 0; i < 20; i++) {
                if (alive(c)) {
                    stay(c);
                    return null;
                }
                sleep(300);
            }
            return out.contains("PORT=") ? "its helper didn't start" + said(out)
                    : "Buddy isn't paired with this phone's Wireless debugging any more (Settings → Background mode → Set up)";
        }
    }

    /** What the helper itself printed before it gave up (launch's output after LOG=), as ": …", or "". */
    private static String said(String out) {
        int at = out.indexOf("LOG=");
        String log = at < 0 ? "" : out.substring(at + 4).trim();
        if (log.length() > 1200) log = "…" + log.substring(log.length() - 1200);
        return log.isEmpty() ? " (it printed nothing)" : ": " + log;
    }

    /** What the running helper says is wrong with it (this phone gives it no screens of its own), or null. */
    static String problem(Context c) {
        try {
            String p = call(c, "ping").trim();
            int at = p.indexOf(" nodisplays ");
            return at < 0 ? null : "this phone doesn't let the helper make a screen of its own (" + p.substring(at + 12) + ")";
        } catch (IOException e) {
            return null;
        }
    }

    /** The helper switches USB debugging on, so it outlives Wireless debugging going off (see Shell's "stay"). */
    private static void stay(Context c) {
        try {
            call(c, "stay");
        } catch (IOException ignored) {
        }
    }

    /** adb in Buddy's Linux: connect to the first port that is this phone's adb, and start the helper there. */
    private static String launch(Context c, List<Integer> ports) {
        StringBuilder list = new StringBuilder();
        for (int p : ports) list.append(' ').append(p);
        // an old helper goes first. The pattern is anchored: unanchored it also matches the shell that runs this very
        // line (which names the helper further on), and killed it before the new helper started
        String helper = "pkill -f '^app_process.*assistant.Shell'; CLASSPATH=" + c.getPackageCodePath()
                + " setsid app_process / " + Shell.class.getName() + " " + PORT + " " + Prefs.backgroundToken(c)
                + " >" + LOG + " 2>&1 </dev/null &";
        String script = ADB + "adb start-server >/dev/null 2>&1; for p in" + list + "; do "
                + "timeout 8 adb connect 127.0.0.1:$p >/dev/null 2>&1; "
                + "if [ \"$(timeout 5 adb -s 127.0.0.1:$p get-state 2>/dev/null)\" = device ]; then echo PORT=$p; "
                + "adb -s 127.0.0.1:$p shell \"pm grant " + c.getPackageName() + " " + SECURE + "\" >/dev/null 2>&1; "
                + "adb -s 127.0.0.1:$p shell \"" + helper + "\"; sleep 2; echo LOG=; "
                + "adb -s 127.0.0.1:$p shell \"tail -c 1500 " + LOG + "\" 2>&1; break; fi; "
                + "adb disconnect 127.0.0.1:$p >/dev/null 2>&1; done; adb kill-server >/dev/null 2>&1";
        try {
            String out = Linux.exec(c, script, 150);
            Matcher m = Pattern.compile("PORT=(\\d+)").matcher(out);
            if (m.find()) Prefs.setBackgroundAdbPort(c, Integer.parseInt(m.group(1)));
            return out;
        } catch (Exception e) {
            return String.valueOf(e.getMessage());
        }
    }

    private static boolean listening(int port) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", port), 300);
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /** Ports on this phone that something listens on, in the range Wireless debugging picks its port from. */
    private static List<Integer> adbPorts(Context c) {
        final List<Integer> open = Collections.synchronizedList(new ArrayList<>());
        ExecutorService ex = Executors.newFixedThreadPool(32);
        for (int p = 30000; p < 50000; p++) {
            final int port = p;
            ex.execute(() -> {
                try (Socket s = new Socket()) {
                    s.connect(new InetSocketAddress("127.0.0.1", port), 200);
                    open.add(port);
                } catch (IOException ignored) {
                }
            });
        }
        ex.shutdown();
        try {
            ex.awaitTermination(30, TimeUnit.SECONDS);
        } catch (InterruptedException ignored) {
        }
        List<Integer> out = new ArrayList<>(open);
        int last = Prefs.backgroundAdbPort(c);
        if (out.remove((Integer) last)) out.add(0, last); // the one that worked last time first
        return out;
    }

    /**
     * Where to pair: the port the dialog shows, or, when it shows none, the one the phone announces on the local
     * network for pairing (as adb on a computer finds it), then the other ports listening on this phone that could
     * be it. The first that takes the code is the one; at most six are tried.
     */
    private static List<Integer> pairingPorts(Context c, String shown) {
        List<Integer> out = new ArrayList<>();
        if (shown != null) {
            out.add(Integer.parseInt(shown));
            return out;
        }
        int announced = announcedPairingPort(c, 6000);
        if (announced > 0) out.add(announced);
        int connect = Prefs.backgroundAdbPort(c);
        for (int p : adbPorts(c)) if (p != connect && !out.contains(p) && out.size() < 6) out.add(p);
        return out;
    }

    /** The port this phone announces for pairing (_adb-tls-pairing._tcp), or 0 when none shows up in time. */
    private static int announcedPairingPort(Context c, long ms) {
        final android.net.nsd.NsdManager nsd = (android.net.nsd.NsdManager) c.getSystemService(Context.NSD_SERVICE);
        if (nsd == null) return 0;
        final int[] port = {0};
        final java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        android.net.nsd.NsdManager.DiscoveryListener l = new android.net.nsd.NsdManager.DiscoveryListener() {
            @Override
            public void onServiceFound(android.net.nsd.NsdServiceInfo info) {
                try {
                    nsd.resolveService(info, new android.net.nsd.NsdManager.ResolveListener() {
                        @Override
                        public void onResolveFailed(android.net.nsd.NsdServiceInfo i, int error) {
                        }

                        @Override
                        public void onServiceResolved(android.net.nsd.NsdServiceInfo i) {
                            try { // this phone's own, not another device pairing on the same network
                                java.net.InetAddress host = i.getHost();
                                if (host != null && !host.isLoopbackAddress()
                                        && java.net.NetworkInterface.getByInetAddress(host) == null) return;
                            } catch (Exception ignored) {
                            }
                            port[0] = i.getPort();
                            done.countDown();
                        }
                    });
                } catch (Exception ignored) {
                }
            }

            @Override
            public void onServiceLost(android.net.nsd.NsdServiceInfo info) {
            }

            @Override
            public void onDiscoveryStarted(String type) {
            }

            @Override
            public void onDiscoveryStopped(String type) {
            }

            @Override
            public void onStartDiscoveryFailed(String type, int error) {
                done.countDown();
            }

            @Override
            public void onStopDiscoveryFailed(String type, int error) {
            }
        };
        try {
            nsd.discoverServices("_adb-tls-pairing._tcp", android.net.nsd.NsdManager.PROTOCOL_DNS_SD, l);
            done.await(ms, TimeUnit.MILLISECONDS);
        } catch (Exception ignored) {
        } finally {
            try {
                nsd.stopServiceDiscovery(l);
            } catch (Exception ignored) {
            }
        }
        return port[0];
    }

    /** Wireless debugging on or off, if Buddy was given the right to (granted over adb when it paired). */
    private static boolean wirelessDebugging(Context c, boolean on) {
        if (c.checkSelfPermission(SECURE) != PackageManager.PERMISSION_GRANTED) return false;
        try {
            return android.provider.Settings.Global.putInt(c.getContentResolver(), "adb_wifi_enabled", on ? 1 : 0);
        } catch (Exception e) {
            return false;
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
        }
    }

    // ------------------------------------------------------------------ setting up: adb, then pairing

    static Step step() {
        return step;
    }

    static String detail() {
        return detail;
    }

    /** The set-up screen redraws when the step changes (null when it closes). */
    static void listen(Runnable r) {
        listener = r;
    }

    private static void set(Step s, String d) {
        step = s;
        detail = d == null ? "" : d;
        Runnable r = listener;
        if (r != null) r.run();
    }

    /**
     * Set up: installs adb in Buddy's Linux if it isn't there, then waits (5 minutes) for the user to open "Pair
     * device with pairing code" in Wireless debugging. Buddy's accessibility service reads the code and the port
     * off that dialog, pairs, and starts the helper. Only this flow reads that dialog, and only while it waits.
     */
    static void beginSetup(final BuddyService svc) {
        if (step == Step.INSTALLING || step == Step.WAITING || step == Step.PAIRING || step == Step.STARTING) return;
        set(Step.INSTALLING, "");
        new Thread(() -> {
            try {
                if (!Linux.installed(svc)) {
                    set(Step.FAILED, svc.getString(R.string.background_needs_linux));
                    return;
                }
                String out = Linux.exec(svc, "command -v adb >/dev/null && echo ADB_OK && exit 0; "
                        + "grep -q '^@edge .*/edge/community' /etc/apk/repositories || "
                        + "echo '@edge https://dl-cdn.alpinelinux.org/alpine/edge/community' >>/etc/apk/repositories; "
                        + "grep -q '^@edge .*/edge/main' /etc/apk/repositories || "
                        + "echo '@edge https://dl-cdn.alpinelinux.org/alpine/edge/main' >>/etc/apk/repositories; "
                        + "apk update -q 2>&1 | tail -2; apk add -q android-tools-adb@edge 2>&1 | tail -3; "
                        + "command -v adb >/dev/null && echo ADB_OK", 600);
                if (!out.contains("ADB_OK")) {
                    set(Step.FAILED, out.trim());
                    return;
                }
                // (Settings.Global.DEVELOPMENT_SETTINGS_ENABLED isn't asked: a Pixel on Android 17 answers 0 with
                // Developer options on, issue #29. Whether they're on shows when Buddy opens them.)
                waitUntil = System.currentTimeMillis() + 5 * 60_000;
                set(Step.WAITING, "");
                // Buddy goes there itself; wherever that stops short, the user does the step and it carries on
                new Thread(() -> {
                    try {
                        openWireless(svc, true);
                    } catch (Exception ignored) {
                    }
                }).start();
                String[] found = null;
                while (step == Step.WAITING && System.currentTimeMillis() < waitUntil && found == null) {
                    sleep(700);
                    found = pairingDialog(svc);
                }
                if (step != Step.WAITING) return; // cancelled
                if (found == null) { // the likeliest reason comes along when the phone says Developer options are off
                    set(Step.FAILED, svc.getString(R.string.background_no_dialog)
                            + (walkStopped.isEmpty() ? "" : "\n(" + walkStopped + ")"));
                    return;
                }
                set(Step.PAIRING, "");
                StringBuilder tryPorts = new StringBuilder();
                for (int port : pairingPorts(svc, found[1])) tryPorts.append(' ').append(port);
                if (tryPorts.length() == 0) {
                    set(Step.FAILED, svc.getString(R.string.background_no_dialog) + "\n(no pairing port found)");
                    return;
                }
                String paired = Linux.exec(svc, ADB + "adb start-server >/dev/null 2>&1; for p in" + tryPorts + "; do "
                        + "r=$(timeout 15 adb pair 127.0.0.1:$p " + found[0] + " 2>&1 | tail -2); echo \"$p: $r\"; "
                        + "case \"$r\" in *'Successfully paired'*) break;; esac; done; "
                        + "adb kill-server >/dev/null 2>&1", 150);
                if (!paired.contains("Successfully paired")) {
                    set(Step.FAILED, paired.trim());
                    return;
                }
                Prefs.setBackgroundPaired(svc, true);
                set(Step.STARTING, "");
                sleep(1500); // the dialog closes and Wireless debugging lists the new device
                String why = start(svc);
                if (why != null) {
                    set(Step.FAILED, why);
                    return;
                }
                set(Step.READY, "");
            } catch (Exception e) {
                set(Step.FAILED, String.valueOf(e.getMessage()));
            }
        }).start();
    }

    static void cancelSetup() {
        if (step == Step.WAITING) set(Step.IDLE, "");
    }

    /** Forget the pairing and stop the helper (its displays go with it). */
    static void remove(final Context c) {
        Prefs.setBackgroundPaired(c, false);
        displays.clear();
        set(Step.IDLE, "");
        new Thread(() -> {
            try {
                call(c, "quit");
            } catch (IOException ignored) {
            }
            try {
                Linux.exec(c, "rm -f /root/.android/adbkey /root/.android/adbkey.pub", 30);
            } catch (Exception ignored) {
            }
        }).start();
    }

    // ------------------------------------------------------------------ Settings, walked by Buddy itself

    /** A label of Android's Settings app in the phone's language (its own text, by its name there), or null. */
    private static String settingsLabel(Context c, String name) {
        try {
            Context s = c.createPackageContext("com.android.settings", 0);
            int id = s.getResources().getIdentifier(name, "string", "com.android.settings");
            return id == 0 ? null : s.getString(id);
        } catch (Exception e) {
            return null;
        }
    }

    private interface Match {
        boolean is(AccessibilityNodeInfo n);
    }

    private static AccessibilityNodeInfo first(AccessibilityNodeInfo n, Match m, int depth) {
        if (n == null || depth > 40) return null;
        if (m.is(n)) return n;
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo f = first(n.getChild(i), m, depth + 1);
            if (f != null) return f;
        }
        return null;
    }

    /** The first node in a window of Settings that matches, or null. */
    private static AccessibilityNodeInfo inSettings(BuddyService svc, Match m) {
        try {
            for (AccessibilityWindowInfo w : svc.getWindows()) {
                AccessibilityNodeInfo root = w.getRoot();
                if (root == null || !"com.android.settings".contentEquals(String.valueOf(root.getPackageName()))) continue;
                AccessibilityNodeInfo f = first(root, m, 0);
                if (f != null) return f;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static Match text(final String label) {
        return n -> n.getText() != null && label.contentEquals(n.getText().toString().trim()) && n.isVisibleToUser();
    }

    private static AccessibilityNodeInfo await(BuddyService svc, Match m, long ms) {
        long until = System.currentTimeMillis() + ms;
        do {
            AccessibilityNodeInfo n = inSettings(svc, m);
            if (n != null) return n;
            sleep(300);
        } while (System.currentTimeMillis() < until && going());
        return null;
    }

    /** Buddy walks Settings only while the set-up waits for the pairing dialog, or Start now asked for it. */
    private static volatile boolean walking;

    private static boolean going() {
        return step == Step.WAITING || walking;
    }

    private static boolean click(AccessibilityNodeInfo n) {
        for (int i = 0; n != null && i < 5; i++, n = n.getParent())
            if (n.isClickable()) return n.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        return false;
    }

    /**
     * Developer options → Wireless debugging (switched on if it's off, "Allow" on Android's question), and with pair
     * on to "Pair device with pairing code". The rows are found by Settings' own labels, so in any language; where one
     * isn't found this stops and the user goes on by hand (the set-up's loop sees the pairing dialog either way).
     * Returns the port Wireless debugging shows for connecting, 0 when it didn't get that far.
     */
    /** Where Buddy's own walk through Settings stopped short, for the error the user reports ("" when it didn't). */
    private static volatile String walkStopped = "";

    private static int stopped(String where) {
        walkStopped = where;
        return 0;
    }

    private static int openWireless(final BuddyService svc, boolean pairToo) {
        walkStopped = "";
        final String wireless = settingsLabel(svc, "enable_adb_wireless"), pair = settingsLabel(svc, "adb_pair_method_code_title");
        svc.runOnMain(() -> {
            try {
                // Android's own Settings scrolls to a row named this way and marks it (its key has been the same since
                // Android 11); a maker's Settings that doesn't know the extras just opens the page
                android.os.Bundle row = new android.os.Bundle();
                row.putString(":settings:fragment_args_key", "toggle_adb_wireless");
                svc.startActivity(new android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                        .putExtra(":settings:fragment_args_key", "toggle_adb_wireless")
                        .putExtra(":settings:show_fragment_args", row)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK | android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP));
            } catch (Exception ignored) {
            }
        });
        if (wireless == null || pair == null) return stopped("Settings has no text named " + (wireless == null ? "enable_adb_wireless" : "adb_pair_method_code_title"));
        if (await(svc, n -> n.isScrollable(), 5000) == null) return stopped("Developer options didn't open as a list");
        sleep(500);
        AccessibilityNodeInfo title = null;
        for (int i = 0; i < 40 && going() && title == null; i++) {
            title = inSettings(svc, text(wireless));
            if (title != null) break;
            // the list itself: on stock Android the page's collapsing title bar is scrollable too, comes first, and
            // answers "scrolled" for ever without moving the list (issue #29, a Pixel)
            AccessibilityNodeInfo list = inSettings(svc, n -> n.isScrollable() && isList(n));
            if (list == null) list = inSettings(svc, n -> n.isScrollable());
            if (list == null || !list.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)) break;
            sleep(450);
        }
        if (title == null) return stopped("no row named \"" + wireless + "\" in Developer options");
        sleep(400); // the list comes to rest
        title = inSettings(svc, text(wireless));
        if (title == null) return stopped("the row \"" + wireless + "\" went out of sight");
        AccessibilityNodeInfo row = title, sw = null;
        for (int i = 0; i < 4 && row != null && sw == null; i++, row = row.getParent()) sw = first(row, n -> n.isCheckable(), 0);
        if (sw != null && !sw.isChecked()) {
            if (!sw.isEnabled() || !click(sw)) return stopped("the switch of \"" + wireless + "\" can't be turned on (no Wi-Fi?)"); // the user sees why
            AccessibilityNodeInfo allow = await(svc, n -> "android:id/button1".equals(n.getViewIdResourceName()), 4000);
            if (allow != null) click(allow);
            sleep(1500);
            title = inSettings(svc, text(wireless));
            if (title == null) return stopped("the row \"" + wireless + "\" was gone after switching it on");
        }
        if (!click(title)) return stopped("the row \"" + wireless + "\" took no tap");
        AccessibilityNodeInfo code = await(svc, text(pair), 6000);
        if (code == null) return stopped("no \"" + pair + "\" on the " + wireless + " screen");
        // this screen also shows the port to connect to: no need to look for it
        int port = 0;
        AccessibilityNodeInfo address = inSettings(svc, n -> n.getText() != null && ADDRESS.matcher(n.getText().toString().trim()).matches());
        if (address != null) {
            Matcher m = ADDRESS.matcher(address.getText().toString().trim());
            if (m.matches()) port = Integer.parseInt(m.group(1));
        }
        if (port > 0) Prefs.setBackgroundAdbPort(svc, port);
        if (pairToo) click(code);
        return port;
    }

    private static boolean isList(AccessibilityNodeInfo n) {
        String cls = String.valueOf(n.getClassName()), id = String.valueOf(n.getViewIdResourceName());
        return cls.endsWith("RecyclerView") || cls.endsWith("ListView") || id.endsWith(":id/recycler_view") || id.endsWith(":id/list");
    }

    private static final Pattern CODE = Pattern.compile("^\\d{6}$");
    private static final Pattern ADDRESS = Pattern.compile("^(?:\\d{1,3}\\.){3}\\d{1,3}:(\\d{4,5})$");

    /**
     * {code, port} when Settings shows its "Pair with device" dialog right now, else null. The port is null when the
     * dialog doesn't show it: Android 17 moved the address to a page of its own (issue #29), so it's found another
     * way (see pairingPorts).
     */
    private static String[] pairingDialog(BuddyService svc) {
        try {
            for (AccessibilityWindowInfo w : svc.getWindows()) {
                AccessibilityNodeInfo root = w.getRoot();
                if (root == null || !"com.android.settings".contentEquals(String.valueOf(root.getPackageName()))) continue;
                String[] found = new String[2];
                scan(root, found, 0);
                if (found[0] != null) return found;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static void scan(AccessibilityNodeInfo n, String[] found, int depth) {
        if (n == null || depth > 30) return;
        CharSequence t = n.getText();
        if (t != null) {
            String s = t.toString().trim(), id = String.valueOf(n.getViewIdResourceName());
            if (CODE.matcher(s).matches()) found[0] = s;
            Matcher m = ADDRESS.matcher(s);
            if (m.matches()) found[1] = m.group(1);
            else if (id.endsWith(":id/ip_addr")) { // the dialog's own address field, whatever kind of address it shows
                Matcher last = Pattern.compile(":(\\d{4,5})$").matcher(s);
                if (last.find()) found[1] = last.group(1);
            }
        }
        for (int i = 0; i < n.getChildCount(); i++) scan(n.getChild(i), found, depth + 1);
    }
}
