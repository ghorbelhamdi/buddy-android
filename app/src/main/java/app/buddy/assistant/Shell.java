package app.buddy.assistant;

import android.content.AttributionSource;
import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;
import android.view.InputDevice;
import android.view.InputEvent;
import android.view.KeyCharacterMap;
import android.view.KeyEvent;
import android.view.MotionEvent;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Buddy's helper for background mode. It is NOT part of the app's own process: Buddy starts it over adb as the shell
 * user (app_process with the APK as its class path), because only the shell user may make a display that other apps
 * run on. It owns one invisible display per chat and does there what Buddy's accessibility service does on the real
 * screen: open an app, tap, swipe, press Back, and hand out a picture of it.
 *
 * It listens on 127.0.0.1 only and every connection starts with the token Buddy started it with. For background mode
 * it acts on the displays it made itself and nowhere else (the one thing it does on the real screen is bring an app
 * there, for "Show on my screen"). `exec` is the exception: it runs a command line as the shell user, for Buddy's
 * phone_shell tool. Buddy sends it only when the user turned Phone commands on, and after their approval card
 * unless they turned that off too (see Tools.phoneShell): the token is what stands between an app on this phone
 * and that.
 *
 * One request per connection, lines of UTF-8: the token, the command, its arguments separated by tabs. The answer is
 * "OK …" or "ERR …" in one line; `frame` and `exec` answer "OK <bytes>" followed by that many bytes (a JPEG; the
 * exit code, a line break and the output).
 */
public final class Shell {
    /** Raised when the protocol changes: Buddy restarts a helper that answers another number. */
    static final int VERSION = 3;
    /** A chat's display goes when nothing was asked of it for this long (Buddy keeps the ones in use alive). */
    private static final long IDLE_MS = 10 * 60_000;

    private static final class Screen {
        VirtualDisplay display;
        ImageReader reader;
        Image latest;
        int id, w, h;
        long used = SystemClock.uptimeMillis();
    }

    private static final Map<String, Screen> screens = new HashMap<>();
    private static DisplayManager dm;
    private static Handler frames;
    private static Object inputManager;
    private static Method inject, setDisplayId;

    private Shell() {
    }

    public static void main(String[] a) throws Exception {
        final int port = Integer.parseInt(a[0]);
        final String token = a[1];
        Looper.prepareMainLooper();
        dm = displayManager();
        HandlerThread ht = new HandlerThread("frames");
        ht.start();
        frames = new Handler(ht.getLooper());
        final ServerSocket server = new ServerSocket(port, 16, InetAddress.getByName("127.0.0.1"));
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                while (true) {
                    try {
                        final Socket s = server.accept();
                        new Thread(new Runnable() {
                            @Override
                            public void run() {
                                serve(s, token);
                            }
                        }).start();
                    } catch (Exception e) {
                        return;
                    }
                }
            }
        });
        t.setDaemon(true);
        t.start();
        final Handler main = new Handler(Looper.getMainLooper());
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                releaseIdle();
                main.postDelayed(this, 30_000);
            }
        }, 30_000);
        System.out.println("BUDDY_SHELL " + VERSION + " " + android.os.Process.myPid());
        System.out.flush();
        Looper.loop();
    }

    /** A DisplayManager that acts as the shell user: there is no app here, so its context is the system's, renamed. */
    private static DisplayManager displayManager() throws Exception {
        Class<?> at = Class.forName("android.app.ActivityThread");
        Constructor<?> c = at.getDeclaredConstructor();
        c.setAccessible(true);
        Object thread = c.newInstance();
        Field cur = at.getDeclaredField("sCurrentActivityThread");
        cur.setAccessible(true);
        cur.set(null, thread);
        Field sys = at.getDeclaredField("mSystemThread");
        sys.setAccessible(true);
        sys.setBoolean(thread, true);
        Context system = (Context) at.getMethod("getSystemContext").invoke(thread);
        Context ctx = new ContextWrapper(system) {
            @Override
            public String getPackageName() {
                return "com.android.shell";
            }

            @Override
            public String getOpPackageName() {
                return "com.android.shell";
            }

            @Override
            public AttributionSource getAttributionSource() {
                return new AttributionSource.Builder(android.os.Process.SHELL_UID).setPackageName("com.android.shell").build();
            }

            @Override
            public Context getApplicationContext() {
                return this;
            }
        };
        Constructor<DisplayManager> dc = DisplayManager.class.getDeclaredConstructor(Context.class);
        dc.setAccessible(true);
        return dc.newInstance(ctx);
    }

    private static void serve(Socket s, String token) {
        try {
            s.setSoTimeout(15_000);
            InputStream in = s.getInputStream();
            OutputStream out = s.getOutputStream();
            String given = line(in), cmd = line(in);
            if (given == null || cmd == null || !java.security.MessageDigest.isEqual(given.getBytes(StandardCharsets.UTF_8),
                    token.getBytes(StandardCharsets.UTF_8))) {
                out.write("ERR no\n".getBytes(StandardCharsets.UTF_8));
                return;
            }
            String[] a = cmd.split("\t", -1);
            byte[] body = null;
            String answer;
            try {
                if ("frame".equals(a[0])) {
                    body = frame(a[1], a.length > 2 ? Integer.parseInt(a[2]) : 0, a.length > 3 ? Integer.parseInt(a[3]) : 80);
                    answer = "OK " + body.length;
                } else if ("exec".equals(a[0])) { // exec <seconds> <the command line, Base64>
                    body = exec(Integer.parseInt(a[1]), new String(android.util.Base64.decode(a[2], android.util.Base64.NO_WRAP),
                            StandardCharsets.UTF_8));
                    answer = "OK " + body.length;
                } else {
                    answer = "OK " + run(a);
                }
            } catch (Throwable e) {
                Throwable c = e instanceof java.lang.reflect.InvocationTargetException && e.getCause() != null ? e.getCause() : e;
                answer = "ERR " + String.valueOf(c).replace('\n', ' ');
                body = null;
            }
            out.write((answer + "\n").getBytes(StandardCharsets.UTF_8));
            if (body != null) out.write(body);
            out.flush();
        } catch (Exception ignored) {
        } finally {
            try {
                s.close();
            } catch (Exception ignored) {
            }
        }
    }

    /** One line (without its end), or null at the end of the stream; lines here are short. */
    private static String line(InputStream in) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        for (int c; (c = in.read()) >= 0; ) {
            if (c == '\n') return new String(b.toByteArray(), StandardCharsets.UTF_8);
            if (b.size() > 65536) return null;
            b.write(c);
        }
        return b.size() == 0 ? null : new String(b.toByteArray(), StandardCharsets.UTF_8);
    }

    private static String run(String[] a) throws Exception {
        switch (a[0]) {
            case "ping":
                return VERSION + " " + android.os.Process.myPid();
            case "open": // open <chat> <w> <h> <dpi>: the chat's display, made now if it has none → its id
                return String.valueOf(open(a[1], Integer.parseInt(a[2]), Integer.parseInt(a[3]), Integer.parseInt(a[4])).id);
            case "close":
                close(a[1]);
                return "";
            case "list": {
                StringBuilder b = new StringBuilder();
                synchronized (screens) {
                    for (Map.Entry<String, Screen> e : screens.entrySet()) {
                        e.getValue().used = "keep".equals(a.length > 1 ? a[1] : "") ? SystemClock.uptimeMillis() : e.getValue().used;
                        b.append(b.length() == 0 ? "" : " ").append(e.getKey()).append('=').append(e.getValue().id);
                    }
                }
                return b.toString();
            }
            case "keep": // the chat is still at work: its display stays
                screen(a[1]);
                return "";
            case "start": { // start <chat> <component>: the app's screen, on the chat's display
                Screen s = screen(a[1]);
                component(a[2]);
                return am("start", "--display", String.valueOf(s.id), "-n", a[2], "-a", "android.intent.action.MAIN",
                        "-c", "android.intent.category.LAUNCHER");
            }
            case "view": { // view <chat> <uri> [package]: a link, opened on the chat's display
                Screen s = screen(a[1]);
                List<String> c = new ArrayList<>(java.util.Arrays.asList("start", "--display", String.valueOf(s.id),
                        "-a", "android.intent.action.VIEW", "-d", a[2]));
                if (a.length > 3 && !a[3].isEmpty()) {
                    pkg(a[3]);
                    c.add("-p");
                    c.add(a[3]);
                }
                return am(c.toArray(new String[0]));
            }
            case "show": // show <component>: bring that app to the real screen ("Show on my screen")
                component(a[1]);
                return am("start", "--display", "0", "-n", a[1], "-a", "android.intent.action.MAIN",
                        "-c", "android.intent.category.LAUNCHER");
            case "gesture": { // gesture <chat> <ms> x,y x,y …: one finger along the points in that time (a tap: one point)
                Screen s = screen(a[1]);
                long ms = Long.parseLong(a[2]);
                float[] xs = new float[a.length - 3], ys = new float[a.length - 3];
                for (int i = 3; i < a.length; i++) {
                    int comma = a[i].indexOf(',');
                    xs[i - 3] = Float.parseFloat(a[i].substring(0, comma));
                    ys[i - 3] = Float.parseFloat(a[i].substring(comma + 1));
                }
                gesture(s.id, ms, xs, ys);
                return "";
            }
            case "key": { // key <chat> <keycode>
                Screen s = screen(a[1]);
                key(s.id, Integer.parseInt(a[2]));
                return "";
            }
            case "text": { // text <chat> <text>: typed as keys (what a keyboard can type; Buddy sets other text itself)
                Screen s = screen(a[1]);
                KeyEvent[] events = KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD).getEvents(a[2].toCharArray());
                if (events == null) throw new IllegalArgumentException("can't be typed as keys");
                for (KeyEvent e : events) inject(e, s.id);
                return "";
            }
            case "stay": // USB debugging on: with it, Android keeps its debugging service (this helper's parent) running
                // when Wireless debugging goes off, which it does whenever Wi-Fi drops. Without it the helper is
                // stopped right then (seen on a OnePlus 12: no more background mode until the next Start now).
                return String.valueOf(new ProcessBuilder("settings", "put", "global", "adb_enabled", "1")
                        .redirectErrorStream(true).start().waitFor());
            case "quit":
                synchronized (screens) {
                    for (String k : new ArrayList<>(screens.keySet())) close(k);
                }
                new Handler(Looper.getMainLooper()).postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        System.exit(0);
                    }
                }, 200);
                return "";
            default:
                throw new IllegalArgumentException("unknown command");
        }
    }

    /** Only a plain "package/class" goes to `am`: nothing that could read as another option. */
    private static void component(String c) {
        if (!c.matches("[A-Za-z0-9_.]+/[A-Za-z0-9_.$]+")) throw new IllegalArgumentException("not a component");
    }

    private static void pkg(String p) {
        if (!p.matches("[A-Za-z0-9_.]+")) throw new IllegalArgumentException("not a package");
    }

    private static Screen screen(String chat) {
        Screen s;
        synchronized (screens) {
            s = screens.get(chat);
            if (s == null) throw new IllegalStateException("no display");
            s.used = SystemClock.uptimeMillis();
        }
        wake(s);
        return s;
    }

    private static Object power;
    private static Method wakeDisplay, interactive;

    /**
     * The chat's display, awake. It has a power state of its own, and Android puts it to sleep with the phone (the
     * power button, the screen's timeout): its apps then stop drawing and take no touches, while the phone's own
     * screen stays off, as it should. Waking it is for this display alone (Android 14+; before that there's no such
     * call, and waking "the device" would light the phone's screen: not done). Whether it sleeps is asked of the
     * power service: the display's own state goes on saying "on".
     */
    private static void wake(Screen s) {
        try {
            if (wakeDisplay == null) {
                Class<?> sm = Class.forName("android.os.ServiceManager");
                Object binder = sm.getMethod("getService", String.class).invoke(null, "power");
                power = Class.forName("android.os.IPowerManager$Stub").getMethod("asInterface", android.os.IBinder.class)
                        .invoke(null, binder);
                interactive = power.getClass().getMethod("isDisplayInteractive", int.class);
                wakeDisplay = power.getClass().getMethod("wakeUpWithDisplayId", long.class, int.class, String.class,
                        String.class, int.class);
            }
            if (Boolean.TRUE.equals(interactive.invoke(power, s.id))) return;
            wakeDisplay.invoke(power, SystemClock.uptimeMillis(), 2 /* an application asked */, "buddy:background",
                    "com.android.shell", s.id);
            for (int i = 0; i < 20 && !Boolean.TRUE.equals(interactive.invoke(power, s.id)); i++) Thread.sleep(50);
            Thread.sleep(800); // its apps come back to life
        } catch (Throwable ignored) {
        }
    }

    private static Screen open(String chat, int w, int h, int dpi) throws Exception {
        if (!chat.matches("[A-Za-z0-9_-]{1,64}")) throw new IllegalArgumentException("bad chat");
        synchronized (screens) {
            Screen old = screens.get(chat);
            if (old != null) {
                old.used = SystemClock.uptimeMillis();
                wake(old);
                return old;
            }
            if (screens.size() >= 4) throw new IllegalStateException("too many displays");
            if (w < 200 || h < 200 || w > 4096 || h > 4096 || dpi < 80 || dpi > 800) throw new IllegalArgumentException("bad size");
            final Screen s = new Screen();
            s.w = w;
            s.h = h;
            s.reader = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 2);
            s.reader.setOnImageAvailableListener(new ImageReader.OnImageAvailableListener() {
                @Override
                public void onImageAvailable(ImageReader r) {
                    try {
                        Image img = r.acquireLatestImage();
                        if (img == null) return;
                        synchronized (s) {
                            if (s.latest != null) s.latest.close();
                            s.latest = img;
                        }
                    } catch (Exception ignored) {
                    }
                }
            }, frames);
            // a display of its own that others' apps may run on: with its own focus (so the user's keyboard stays
            // theirs), never locked, and whatever is on it ends with it instead of landing on the real screen
            int flags = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC | DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
                    | DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY | (1 << 6) /* supports touch */
                    | (1 << 8) /* destroy content on removal */;
            if (Build.VERSION.SDK_INT >= 33) flags |= (1 << 10) /* trusted */ | (1 << 11) /* own display group */
                    | (1 << 12) /* always unlocked */ | (1 << 13) /* no touch feedback */;
            if (Build.VERSION.SDK_INT >= 34) flags |= (1 << 14) /* own focus */ | (1 << 15) /* device display group */;
            s.display = dm.createVirtualDisplay("buddy-" + chat, w, h, dpi, s.reader.getSurface(), flags);
            if (s.display == null) throw new IllegalStateException("the system made no display");
            s.id = s.display.getDisplay().getDisplayId();
            hideKeyboard(s.id);
            screens.put(chat, s);
            return s;
        }
    }

    /** No on-screen keyboard for this display: by default it would pop up on the real screen, over what the user does. */
    private static void hideKeyboard(int displayId) {
        try {
            Class<?> sm = Class.forName("android.os.ServiceManager");
            Object binder = sm.getMethod("getService", String.class).invoke(null, "window");
            Object wm = Class.forName("android.view.IWindowManager$Stub").getMethod("asInterface", android.os.IBinder.class)
                    .invoke(null, binder);
            wm.getClass().getMethod("setDisplayImePolicy", int.class, int.class).invoke(wm, displayId, 2 /* hide */);
        } catch (Throwable ignored) {
        }
    }

    private static void close(String chat) {
        Screen s;
        synchronized (screens) {
            s = screens.remove(chat);
        }
        if (s == null) return;
        try {
            s.display.release();
        } catch (Exception ignored) {
        }
        synchronized (s) {
            if (s.latest != null) s.latest.close();
            s.latest = null;
        }
        try {
            s.reader.close();
        } catch (Exception ignored) {
        }
    }

    private static void releaseIdle() {
        long now = SystemClock.uptimeMillis();
        List<String> idle = new ArrayList<>();
        synchronized (screens) {
            for (Map.Entry<String, Screen> e : screens.entrySet()) if (now - e.getValue().used > IDLE_MS) idle.add(e.getKey());
        }
        for (String k : idle) close(k);
    }

    /** What the chat's display shows now, as JPEG, at most maxWidth wide (0: as it is). Empty when nothing was drawn yet. */
    private static byte[] frame(String chat, int maxWidth, int quality) {
        Screen s = screen(chat);
        Bitmap b;
        synchronized (s) {
            if (s.latest == null) return new byte[0];
            Image.Plane p = s.latest.getPlanes()[0];
            int rowW = p.getRowStride() / p.getPixelStride();
            Bitmap full = Bitmap.createBitmap(rowW, s.h, Bitmap.Config.ARGB_8888);
            p.getBuffer().rewind();
            full.copyPixelsFromBuffer(p.getBuffer());
            b = rowW == s.w ? full : Bitmap.createBitmap(full, 0, 0, s.w, s.h);
        }
        if (maxWidth > 0 && b.getWidth() > maxWidth)
            b = Bitmap.createScaledBitmap(b, maxWidth, Math.round(b.getHeight() * (maxWidth / (float) b.getWidth())), true);
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        b.compress(Bitmap.CompressFormat.JPEG, Math.max(10, Math.min(100, quality)), o);
        return o.toByteArray();
    }

    private static String am(String... args) throws Exception {
        String[] cmd = new String[args.length + 2];
        cmd[0] = "cmd";
        cmd[1] = "activity";
        System.arraycopy(args, 0, cmd, 2, args.length);
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        StringBuilder b = new StringBuilder();
        BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
        for (String l; (l = r.readLine()) != null; ) b.append(b.length() == 0 ? "" : " | ").append(l.trim());
        p.waitFor();
        String out = b.toString();
        if (out.contains("Error") || out.contains("Exception")) throw new IllegalStateException(out);
        return out;
    }

    /**
     * A command line, run by sh as the shell user, for at most that many seconds: its exit code (-1: it ran out of
     * time and was stopped), a line break, and what it printed (errors too), the last 200,000 bytes of it.
     */
    private static byte[] exec(int seconds, String command) throws Exception {
        final Process p = new ProcessBuilder("sh", "-c", command).redirectErrorStream(true).start();
        p.getOutputStream().close();
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        Thread reader = new Thread(new Runnable() {
            @Override
            public void run() {
                try (InputStream in = p.getInputStream()) {
                    byte[] buf = new byte[8192];
                    for (int n; (n = in.read(buf)) > 0; ) {
                        synchronized (out) {
                            out.write(buf, 0, n);
                            if (out.size() > 400_000) { // keep the end: that's where a build or a log says how it went
                                byte[] all = out.toByteArray();
                                out.reset();
                                out.write(all, all.length - 200_000, 200_000);
                            }
                        }
                    }
                } catch (Exception ignored) {
                }
            }
        });
        reader.start();
        boolean done = p.waitFor(Math.max(1, Math.min(seconds, 600)), java.util.concurrent.TimeUnit.SECONDS);
        if (!done) p.destroyForcibly();
        reader.join(2000);
        byte[] text;
        synchronized (out) {
            text = out.toByteArray();
        }
        if (text.length > 200_000) text = java.util.Arrays.copyOfRange(text, text.length - 200_000, text.length);
        byte[] head = ((done ? p.exitValue() : -1) + "\n").getBytes(StandardCharsets.UTF_8);
        byte[] all = new byte[head.length + text.length];
        System.arraycopy(head, 0, all, 0, head.length);
        System.arraycopy(text, 0, all, head.length, text.length);
        return all;
    }

    // ------------------------------------------------------------------ input, sent to one display

    private static void inject(InputEvent e, int displayId) throws Exception {
        if (inject == null) {
            Class<?> c;
            try {
                c = Class.forName("android.hardware.input.InputManagerGlobal"); // Android 14+
            } catch (ClassNotFoundException x) {
                c = Class.forName("android.hardware.input.InputManager");
            }
            Method get = c.getDeclaredMethod("getInstance");
            get.setAccessible(true);
            inputManager = get.invoke(null);
            inject = inputManager.getClass().getMethod("injectInputEvent", InputEvent.class, int.class);
            setDisplayId = InputEvent.class.getMethod("setDisplayId", int.class);
        }
        setDisplayId.invoke(e, displayId);
        Object ok = inject.invoke(inputManager, e, 2 /* wait until it's handled */);
        if (Boolean.FALSE.equals(ok)) throw new IllegalStateException("the display didn't take the touch");
    }

    private static void key(int displayId, int code) throws Exception {
        long now = SystemClock.uptimeMillis();
        inject(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0,
                InputDevice.SOURCE_KEYBOARD), displayId);
        inject(new KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0, 0, KeyCharacterMap.VIRTUAL_KEYBOARD, 0, 0,
                InputDevice.SOURCE_KEYBOARD), displayId);
    }

    private static MotionEvent touch(long down, long at, int action, float x, float y) {
        MotionEvent.PointerProperties[] pp = {new MotionEvent.PointerProperties()};
        pp[0].id = 0;
        pp[0].toolType = MotionEvent.TOOL_TYPE_FINGER;
        MotionEvent.PointerCoords[] pc = {new MotionEvent.PointerCoords()};
        pc[0].x = x;
        pc[0].y = y;
        pc[0].pressure = action == MotionEvent.ACTION_UP ? 0f : 1f;
        pc[0].size = 1f;
        return MotionEvent.obtain(down, at, action, 1, pp, pc, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0);
    }

    /** One finger down at the first point, along the others in ms, and up: a tap, a long press (one point, long) or a swipe. */
    private static void gesture(int displayId, long ms, float[] xs, float[] ys) throws Exception {
        ms = Math.max(1, Math.min(ms, 60_000));
        long down = SystemClock.uptimeMillis();
        inject(touch(down, down, MotionEvent.ACTION_DOWN, xs[0], ys[0]), displayId);
        int steps = xs.length == 1 ? 0 : (int) Math.max(xs.length - 1, ms / 12);
        for (int i = 1; i <= steps; i++) {
            float at = i * (xs.length - 1) / (float) steps; // where along the points
            int k = Math.min((int) at, xs.length - 2);
            float f = at - k;
            long when = down + ms * i / steps;
            long wait = when - SystemClock.uptimeMillis();
            if (wait > 0) Thread.sleep(wait);
            inject(touch(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_MOVE, xs[k] + (xs[k + 1] - xs[k]) * f,
                    ys[k] + (ys[k + 1] - ys[k]) * f), displayId);
        }
        long wait = down + ms - SystemClock.uptimeMillis();
        if (wait > 0) Thread.sleep(wait);
        inject(touch(down, SystemClock.uptimeMillis(), MotionEvent.ACTION_UP, xs[xs.length - 1], ys[ys.length - 1]), displayId);
    }
}
