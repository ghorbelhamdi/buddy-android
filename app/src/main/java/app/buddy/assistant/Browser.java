package app.buddy.assistant;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * The background browser: one headless Chromium inside Buddy's Linux (linux/buddy-browser), started when an agent or
 * the user first needs it and stopped after a while unused. Buddy drives it with the DevTools protocol over the
 * script's stdin and stdout (NUL-separated JSON, Chromium's --remote-debugging-pipe) instead of a debugging port: a
 * port on 127.0.0.1 would let any app or agent on the phone drive the browser around Buddy's approvals, and the pipe
 * needs no WebSocket client. Each chat gets its own tab; the chat's live card shows it (Page.startScreencast) and
 * passes the user's taps, scrolls and typing on to it. The agents' tools are in BrowserTools.
 */
final class Browser {
    enum State { OFF, INSTALLING, STARTING, READY, ERROR }

    interface Listener {
        /** The browser started or stopped, or a tab's address or title changed (main thread). */
        void onBrowserChanged();
    }

    /** Live frames of a chat's tab (any thread; the view posts them to the main thread). */
    interface Frames {
        void onFrame(Bitmap frame, float cssWidth, float cssHeight);
    }

    /** The page's size in CSS pixels; frames are scaled from it. */
    static final int WIDTH = 400, HEIGHT = 820;
    /** The chat key of the tab the user opens from Settings (signing in to sites), and of agents outside Buddy's chats. */
    static final String USER = "user";
    private static final long IDLE_STOP_MS = 3 * 60_000L, FRAME_GAP_MS = 280;

    static volatile State state = State.OFF;
    static volatile String detail = "";

    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final List<Listener> listeners = new ArrayList<>();
    private static final ExecutorService work = Executors.newCachedThreadPool();
    private static final ScheduledExecutorService acks = Executors.newSingleThreadScheduledExecutor();
    private static Context app;
    private static Process proc;
    private static OutputStream pipe;
    private static String js;
    private static final AtomicInteger ids = new AtomicInteger();
    private static final Map<Integer, AtomicReference<JSONObject>> waiting = new ConcurrentHashMap<>();
    /** chat key → its tab (target id); target id → DevTools session, and what the tab shows. */
    private static final Map<String, String> tabs = new ConcurrentHashMap<>();
    private static final Map<String, String> sessions = new ConcurrentHashMap<>();
    private static final Map<String, JSONObject> pages = new ConcurrentHashMap<>();
    /** A JavaScript dialog (alert, confirm, …) waiting on a tab, by session. */
    private static final Map<String, JSONObject> dialogs = new ConcurrentHashMap<>();
    /** Live views: chat key → its frame sinks, and the session its screencast runs in. */
    private static final Map<String, List<Frames>> viewers = new ConcurrentHashMap<>();
    private static final Map<String, String> casting = new ConcurrentHashMap<>();
    /** The isolated world Buddy's page helpers run in, by session (see world). */
    private static final Map<String, Integer> worlds = new ConcurrentHashMap<>();
    private static volatile long lastUse;

    private Browser() {
    }

    // ------------------------------------------------------------------ state

    /** The feature is there: the GitHub build, with the switch on in Settings (see Prefs.browser). */
    static boolean available(Context c) {
        return !Flavor.PLAY && Prefs.browser(c);
    }

    /** Chromium is installed in Buddy's Linux. */
    static boolean installed(Context c) {
        return new File(Linux.root(c), "usr/lib/chromium/chromium").exists();
    }

    static boolean running() {
        return state == State.READY;
    }

    static void addListener(Listener l) {
        if (!listeners.contains(l)) listeners.add(l);
    }

    static void removeListener(Listener l) {
        listeners.remove(l);
    }

    private static void changed() {
        main.post(() -> {
            for (Listener l : new ArrayList<>(listeners)) l.onBrowserChanged();
        });
    }

    private static void set(State s, String d) {
        state = s;
        detail = d == null ? "" : d;
        changed();
    }

    /** Something used the browser: it isn't idle. */
    static void used() {
        lastUse = SystemClock.elapsedRealtime();
    }

    // ------------------------------------------------------------ start, stop

    /**
     * Starts the browser if it isn't running, installing Chromium first if needed, and waits up to waitMs for it.
     * Null once it's ready, otherwise what to tell the agent.
     */
    static String ensure(Context c, long waitMs) throws InterruptedException {
        start(c);
        long end = SystemClock.elapsedRealtime() + waitMs;
        boolean again = true;
        while (state != State.READY && state != State.ERROR && SystemClock.elapsedRealtime() < end) {
            if (state == State.OFF) { // Settings' install ended while we waited (start() left it alone): start now, once
                if (!again || !installed(c)) break;
                again = false;
                start(c);
            }
            Thread.sleep(250);
        }
        if (state == State.READY) return null;
        if (state == State.INSTALLING) {
            return "The background browser is still being installed (a one-time download of about 300 MB). Tell the "
                    + "user in one line, then call this tool again in a minute.";
        }
        if (state == State.STARTING) return "The background browser is still starting. Call this tool again.";
        return "The background browser couldn't start" + (detail.isEmpty() ? "." : ": " + detail) + " Use the phone instead.";
    }

    /** Starts it in the background (no wait). */
    static void start(Context c) {
        synchronized (Browser.class) {
            app = c.getApplicationContext();
            used();
            if (proc != null || state == State.INSTALLING || state == State.STARTING) return;
            if (!Linux.installed(app)) {
                set(State.ERROR, app.getString(R.string.helper_linux_missing));
                return;
            }
            state = State.STARTING;
        }
        set(installed(app) ? State.STARTING : State.INSTALLING, "");
        work.execute(Browser::run);
    }

    /** Installs Chromium without starting it (Settings, when the switch goes on). */
    static void install(Context c) {
        final Context a = c.getApplicationContext();
        synchronized (Browser.class) {
            if (installed(a) || proc != null || state == State.INSTALLING || state == State.STARTING) return;
            state = State.INSTALLING;
        }
        set(State.INSTALLING, "");
        work.execute(() -> {
            try {
                Helper.sync(a);
                String out = Linux.exec(a, "/bin/sh /root/bin/buddy-browser install 2>&1", 1800);
                set(out.contains("BUDDY_INSTALLED") ? State.OFF : State.ERROR,
                        out.contains("BUDDY_INSTALLED") ? "" : lastLines(out));
            } catch (Exception e) {
                set(State.ERROR, e.getMessage());
            }
        });
    }

    private static void run() {
        Process p;
        try {
            Helper.sync(app);
            String lang = java.util.Locale.getDefault().toLanguageTag();
            ProcessBuilder pb = Linux.builder(app, "/usr/bin/env", "BUDDY_LANG=" + lang, "/bin/sh", "/root/bin/buddy-browser", "run");
            pb.redirectErrorStream(false); // stdout carries the protocol; proot's and the script's notes come on stderr
            p = pb.start();
        } catch (Exception e) {
            set(State.ERROR, e.getMessage());
            return;
        }
        synchronized (Browser.class) {
            proc = p;
            pipe = p.getOutputStream();
        }
        final CountDownLatch launching = new CountDownLatch(1);
        final StringBuilder notes = new StringBuilder();
        Thread err = new Thread(() -> {
            try (BufferedReader in = new BufferedReader(new InputStreamReader(p.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = in.readLine()) != null) {
                    line = line.trim();
                    if (line.equals("BUDDY_STATUS installing")) set(State.INSTALLING, "");
                    else if (line.equals("BUDDY_STATUS starting")) {
                        set(State.STARTING, "");
                        launching.countDown();
                    } else if (!line.isEmpty() && !line.startsWith("proot warning")) {
                        synchronized (notes) {
                            notes.append(line).append('\n');
                            if (notes.length() > 2000) notes.delete(0, notes.length() - 2000);
                        }
                    }
                }
            } catch (IOException ignored) {
            }
            launching.countDown();
        }, "buddy-browser-err");
        err.setDaemon(true);
        err.start();
        Thread reader = new Thread(() -> read(p), "buddy-browser");
        reader.setDaemon(true);
        reader.start();
        try {
            // the first start downloads Chromium: a stalled download must not leave it "installing" for good
            if (!launching.await(30, TimeUnit.MINUTES)) throw new IOException(app.getString(R.string.browser_install_failed));
            JSONObject v = null;
            for (int i = 0; i < 30 && v == null && p.isAlive(); i++) {
                try {
                    v = call("Browser.getVersion", null, null, 2000);
                } catch (Exception ignored) {
                }
            }
            if (v == null) throw new IOException(p.isAlive() ? "no answer" : lastLines(notes.toString()));
            call("Target.setDiscoverTargets", new JSONObject().put("discover", true), null, 5000);
            call("Browser.setDownloadBehavior", new JSONObject().put("behavior", "deny"), null, 5000);
            used();
            set(State.READY, "");
            main.removeCallbacks(idleCheck);
            main.postDelayed(idleCheck, 60_000);
        } catch (Exception e) {
            stopProcess(p);
            String why = notes.toString().contains("BUDDY_FAILED")
                    ? app.getString(R.string.browser_install_failed) : e.getMessage();
            set(State.ERROR, why);
        }
    }

    /** The protocol's messages from Chromium, until it stops. */
    private static void read(Process p) {
        try (InputStream in = p.getInputStream()) {
            ByteArrayOutputStream msg = new ByteArrayOutputStream();
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                int from = 0;
                for (int i = 0; i < n; i++) {
                    if (buf[i] != 0) continue;
                    msg.write(buf, from, i - from);
                    from = i + 1;
                    String text = new String(msg.toByteArray(), StandardCharsets.UTF_8);
                    msg.reset();
                    try {
                        onMessage(new JSONObject(text));
                    } catch (Exception e) {
                        android.util.Log.w("BuddyBrowser", "bad message", e);
                    }
                }
                msg.write(buf, from, n - from);
            }
        } catch (IOException ignored) {
        }
        boolean mine;
        synchronized (Browser.class) {
            mine = proc == p;
            if (mine) {
                proc = null;
                pipe = null;
            }
        }
        if (!mine) return;
        reset();
        if (state != State.ERROR) set(State.OFF, "");
    }

    /** Forget every tab and wake every waiting call: the browser is gone. */
    private static void reset() {
        tabs.clear();
        sessions.clear();
        pages.clear();
        dialogs.clear();
        casting.clear();
        worlds.clear();
        owners.clear();
        for (AtomicReference<JSONObject> w : waiting.values()) {
            synchronized (w) {
                w.notifyAll();
            }
        }
        waiting.clear();
        main.removeCallbacks(idleCheck);
    }

    /** Closes Chromium (it saves its sign-ins first) and waits for it to end. */
    static void stop() {
        Process p;
        synchronized (Browser.class) {
            p = proc;
        }
        if (p == null) return;
        try {
            call("Browser.close", null, null, 3000);
        } catch (Exception ignored) {
        }
        stopProcess(p);
    }

    private static void stopProcess(Process p) {
        try {
            if (!p.waitFor(4, TimeUnit.SECONDS)) {
                p.destroy();
                p.waitFor(3, TimeUnit.SECONDS);
            }
        } catch (InterruptedException ignored) {
        }
        if (p.isAlive()) p.destroyForcibly();
        synchronized (Browser.class) {
            if (proc == p) {
                proc = null;
                pipe = null;
            }
        }
        reset();
        set(State.OFF, "");
    }

    /** Stops the browser after a while with no agent using it and no live view open. */
    private static final Runnable idleCheck = new Runnable() {
        @Override
        public void run() {
            if (state != State.READY) return;
            if (viewers.isEmpty() && SystemClock.elapsedRealtime() - lastUse > IDLE_STOP_MS) {
                work.execute(Browser::stop);
                return;
            }
            main.postDelayed(this, 60_000);
        }
    };

    private static String lastLines(String out) {
        String[] l = out.trim().split("\n");
        StringBuilder sb = new StringBuilder();
        for (int i = Math.max(0, l.length - 3); i < l.length; i++) {
            if (l[i].startsWith("[exit") || l[i].startsWith("BUDDY_")) continue;
            sb.append(l[i].trim()).append(' ');
        }
        return sb.toString().trim();
    }

    // --------------------------------------------------------------- protocol

    /** Sends a command (to a tab's session, or to the browser when session is null) and waits for its result. */
    static JSONObject call(String method, JSONObject params, String session, long timeoutMs) throws Exception {
        OutputStream o;
        synchronized (Browser.class) {
            o = pipe;
        }
        if (o == null) throw new IllegalStateException("The background browser isn't running.");
        int id = ids.incrementAndGet();
        JSONObject m = new JSONObject().put("id", id).put("method", method);
        if (params != null) m.put("params", params);
        if (session != null) m.put("sessionId", session);
        AtomicReference<JSONObject> box = new AtomicReference<>();
        waiting.put(id, box);
        try {
            byte[] b = m.toString().getBytes(StandardCharsets.UTF_8);
            synchronized (o) {
                o.write(b);
                o.write(0);
                o.flush();
            }
            long end = SystemClock.elapsedRealtime() + timeoutMs;
            synchronized (box) {
                while (box.get() == null && waiting.containsKey(id)) {
                    long left = end - SystemClock.elapsedRealtime();
                    if (left <= 0) throw new IllegalStateException(method + " timed out");
                    box.wait(left);
                }
            }
            JSONObject r = box.get();
            if (r == null) throw new IllegalStateException("The background browser stopped.");
            if (r.has("error")) throw new IllegalStateException(r.getJSONObject("error").optString("message", "error"));
            return r.optJSONObject("result") != null ? r.getJSONObject("result") : new JSONObject();
        } finally {
            waiting.remove(id);
        }
    }

    /** As call, without waiting for the result. */
    private static void send(String method, JSONObject params, String session) {
        work.execute(() -> {
            try {
                call(method, params, session, 10_000);
            } catch (Exception ignored) {
            }
        });
    }

    private static void onMessage(JSONObject m) throws Exception {
        if (m.has("id")) {
            AtomicReference<JSONObject> box = waiting.get(m.getInt("id"));
            if (box != null) {
                synchronized (box) {
                    box.set(m);
                    box.notifyAll();
                }
            }
            return;
        }
        String method = m.optString("method");
        JSONObject p = m.optJSONObject("params");
        if (p == null) p = new JSONObject();
        String sid = m.optString("sessionId", null);
        switch (method) {
            case "Page.screencastFrame":
                onFrame(sid, p);
                break;
            case "Target.targetCreated":
            case "Target.targetInfoChanged": {
                JSONObject t = p.getJSONObject("targetInfo");
                if (!"page".equals(t.optString("type"))) break;
                boolean isNew = !pages.containsKey(t.getString("targetId"));
                pages.put(t.getString("targetId"), t);
                String opener = t.optString("openerId", "");
                if (isNew && !opener.isEmpty()) { // a link that opens a new tab: its chat goes on there
                    for (Map.Entry<String, String> e : tabs.entrySet()) {
                        if (opener.equals(e.getValue())) {
                            assign(e.getKey(), t.getString("targetId"));
                            if (viewers.containsKey(e.getKey())) work.execute(() -> recast(e.getKey()));
                        }
                    }
                }
                changed();
                break;
            }
            case "Target.targetDestroyed": {
                String id = p.optString("targetId");
                JSONObject gone = pages.remove(id);
                owners.remove(id);
                String sess = sessions.remove(id);
                if (sess != null) {
                    dialogs.remove(sess);
                    worlds.remove(sess);
                }
                String opener = gone == null ? "" : gone.optString("openerId", "");
                for (Map.Entry<String, String> e : tabs.entrySet()) {
                    if (!id.equals(e.getValue())) continue;
                    String key = e.getKey();
                    if (!opener.isEmpty() && pages.containsKey(opener)) tabs.put(key, opener); // back to where it came from
                    else tabs.remove(key);
                    // a live view on it shows the tab the chat is in now (a sign-in popup that closed, window.close())
                    if (viewers.containsKey(key)) work.execute(() -> recast(key));
                }
                if (sess != null) casting.values().removeIf(sess::equals);
                changed();
                break;
            }
            case "Target.detachedFromTarget":
                sessions.values().remove(p.optString("sessionId"));
                break;
            case "Page.frameNavigated": // a new document: the helpers' world went with the old one
                if (sid != null && !p.optJSONObject("frame").has("parentId")) worlds.remove(sid);
                break;
            case "Page.javascriptDialogOpening":
                if (sid != null) dialogs.put(sid, p);
                changed();
                break;
            case "Page.javascriptDialogClosed":
                if (sid != null) dialogs.remove(sid);
                changed();
                break;
            default:
                break;
        }
    }

    // ------------------------------------------------------------------ tabs

    /**
     * The key of the tab a tool call works in: its Buddy chat, or for an agent outside Buddy's chats (a terminal, Remote
     * Control) the user's own tab, which they watch from Settings.
     */
    static String key(String chat) {
        return chat == null ? USER : chat;
    }

    /** The chat each tab belongs to (the last one that worked in it), so a tab one chat left isn't another's to use. */
    private static final Map<String, String> owners = new ConcurrentHashMap<>();

    /** The chat goes on in this tab. */
    private static void assign(String key, String target) {
        tabs.put(key, target);
        owners.put(target, key);
    }

    /** Is this tab another chat's (its current one, or one it left open)? */
    static boolean ownedByOther(String key, String target) {
        String o = owners.get(target);
        if (o != null && !o.equals(key)) return true;
        for (Map.Entry<String, String> e : tabs.entrySet()) if (!e.getKey().equals(key) && e.getValue().equals(target)) return true;
        return false;
    }

    /**
     * What an agent's question asks the user to do in a chat's tab (ask with browser_highlight): item numbers and
     * labels, drawn over the live view until they answer.
     */
    private static final Map<String, JSONArray> marks = new ConcurrentHashMap<>();

    static void setMarks(String key, JSONArray m) {
        if (m == null) marks.remove(key);
        else marks.put(key, m);
        changed();
    }

    static JSONArray marks(String key) {
        return marks.get(key);
    }

    /** Where the marked items are now ({x, y, w, h} in page pixels, or null for one that's gone), first in view. */
    static JSONArray markBoxes(String key, boolean reveal) throws Exception {
        JSONArray m = marks.get(key);
        if (m == null) return null;
        JSONArray refs = new JSONArray();
        for (int i = 0; i < m.length(); i++) refs.put(m.getJSONObject(i).getInt("ref"));
        Object r = eval(key, "__buddy.boxes(" + refs + ", " + reveal + ")");
        return r instanceof JSONArray ? (JSONArray) r : null;
    }

    /** When an agent last used each chat's tab (see agentBusy). */
    private static final Map<String, Long> agentUse = new ConcurrentHashMap<>();

    /** An agent used the chat's tab (a browser tool call). */
    static void agentUsed(String key) {
        agentUse.put(key, SystemClock.elapsedRealtime());
    }

    /** Is an agent working in this tab (it used it in the last two minutes)? */
    static boolean agentBusy(String key) {
        Long t = agentUse.get(key);
        return t != null && SystemClock.elapsedRealtime() - t < 120_000;
    }

    /** Chats whose tab the user used in the live view since their agent last read it. */
    private static final java.util.Set<String> touched = java.util.Collections.newSetFromMap(new ConcurrentHashMap<>());

    /** The user used the chat's tab in the live view: the agent's item numbers may be stale. */
    static void touched(String key) {
        touched.add(key);
        used();
    }

    /** Whether the user used the chat's tab since the last call (and forget it). */
    static boolean takeTouched(String key) {
        return touched.remove(key);
    }

    /** The chat's tab (target id), opening one when there's none and create is set; null otherwise. */
    static String tab(String key, boolean create) throws Exception {
        String t = tabs.get(key);
        if (t != null && pages.containsKey(t)) return t;
        if (!create) return null;
        // the blank tab Chromium starts with, if nobody has it yet
        for (JSONObject p : pages.values()) {
            String id = p.optString("targetId");
            if ("about:blank".equals(p.optString("url")) && !tabs.containsValue(id)) {
                assign(key, id);
                return id;
            }
        }
        t = call("Target.createTarget", new JSONObject().put("url", "about:blank"), null, 10_000).getString("targetId");
        JSONObject info = new JSONObject().put("targetId", t).put("type", "page").put("url", "about:blank").put("title", "");
        pages.putIfAbsent(t, info);
        assign(key, t);
        changed();
        return t;
    }

    /** Switches the chat to another open tab. */
    static void useTab(String key, String target) {
        if (!pages.containsKey(target)) throw new IllegalArgumentException("No such tab.");
        assign(key, target);
        if (viewers.containsKey(key)) work.execute(() -> recast(key));
        changed();
    }

    /** The open tabs, as Chromium lists them. */
    static List<JSONObject> pages() {
        return new ArrayList<>(pages.values());
    }

    /** The DevTools session for a tab, attaching (and setting it up as a phone screen) on first use. */
    static String session(String target) throws Exception {
        String s = sessions.get(target);
        if (s != null) return s;
        s = call("Target.attachToTarget", new JSONObject().put("targetId", target).put("flatten", true), null, 10_000)
                .getString("sessionId");
        sessions.put(target, s);
        call("Page.enable", null, s, 10_000);
        call("Emulation.setDeviceMetricsOverride", new JSONObject().put("width", WIDTH).put("height", HEIGHT)
                .put("deviceScaleFactor", 2).put("mobile", true), s, 10_000);
        return s;
    }

    /** The session of the chat's tab (opening one if needed). */
    static String sessionFor(String key) throws Exception {
        return session(tab(key, true));
    }

    /** What the chat's tab shows: {url, title}, or null when it has none. */
    static String[] page(String key) {
        String t = tabs.get(key);
        JSONObject p = t == null ? null : pages.get(t);
        return p == null ? null : new String[]{p.optString("url"), p.optString("title")};
    }

    static boolean hasTab(String key) {
        String t = tabs.get(key);
        return t != null && pages.containsKey(t);
    }

    /** A JavaScript dialog open on the chat's tab ({type, message}), or null. */
    static JSONObject dialog(String key) {
        String t = tabs.get(key), s = t == null ? null : sessions.get(t);
        return s == null ? null : dialogs.get(s);
    }

    /** Answers the chat's tab's JavaScript dialog. */
    static void answerDialog(String key, boolean accept) throws Exception {
        String t = tabs.get(key), s = t == null ? null : sessions.get(t);
        if (s == null) return;
        call("Page.handleJavaScriptDialog", new JSONObject().put("accept", accept), s, 5000);
        dialogs.remove(s);
        changed();
    }

    // ---------------------------------------------------------------- the page

    /** Runs expression in the chat's tab, with Buddy's page helpers (assets/browser.js), and returns its value. */
    static Object eval(String key, String expression) throws Exception {
        if (js == null) {
            try (InputStream in = app.getAssets().open("browser.js")) {
                ByteArrayOutputStream b = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
                js = b.toString("UTF-8");
            }
        }
        String s = sessionFor(key);
        JSONObject r;
        try {
            r = call("Runtime.evaluate", new JSONObject().put("expression", expression).put("contextId", world(s))
                    .put("returnByValue", true).put("awaitPromise", true), s, 20_000);
        } catch (IllegalStateException e) { // a new page since: its world went with the old one
            if (!String.valueOf(e.getMessage()).contains("context")) throw e;
            worlds.remove(s);
            r = call("Runtime.evaluate", new JSONObject().put("expression", expression).put("contextId", world(s))
                    .put("returnByValue", true).put("awaitPromise", true), s, 20_000);
        }
        if (r.has("exceptionDetails")) {
            JSONObject ex = r.getJSONObject("exceptionDetails");
            JSONObject e = ex.optJSONObject("exception");
            throw new IllegalStateException("Page script failed: " + (e != null ? e.optString("description") : ex.optString("text")));
        }
        JSONObject v = r.optJSONObject("result");
        return v == null ? null : v.opt("value");
    }

    /**
     * The tab's page helpers run in a world of their own: the page's scripts share its document but can't see or
     * replace them (a page defining its own __buddy would otherwise decide what a click looks like to the guard).
     * Made, with the helpers in it, once per page.
     */
    private static int world(String session) throws Exception {
        Integer w = worlds.get(session);
        if (w != null) return w;
        String frame = call("Page.getFrameTree", null, session, 10_000).getJSONObject("frameTree").getJSONObject("frame").getString("id");
        int id = call("Page.createIsolatedWorld", new JSONObject().put("frameId", frame).put("worldName", "buddy"), session, 10_000)
                .getInt("executionContextId");
        JSONObject r = call("Runtime.evaluate", new JSONObject().put("expression", js).put("contextId", id), session, 20_000);
        if (r.has("exceptionDetails")) throw new IllegalStateException("Page helpers failed to load.");
        worlds.put(session, id);
        return id;
    }

    /** Waits until the chat's tab has loaded (or maxMs), then a moment for the page's own scripts. */
    static void settle(String key, long maxMs) throws Exception {
        long end = SystemClock.elapsedRealtime() + maxMs;
        String s = sessionFor(key);
        while (SystemClock.elapsedRealtime() < end) {
            if (dialog(key) != null) return;
            try {
                JSONObject r = call("Runtime.evaluate", new JSONObject().put("expression", "document.readyState")
                        .put("returnByValue", true), s, 3000);
                if ("complete".equals(r.optJSONObject("result") == null ? null : r.getJSONObject("result").optString("value"))) break;
            } catch (Exception ignored) { // between two pages
            }
            Thread.sleep(300);
        }
        Thread.sleep(600);
    }

    /** Opens url in the chat's tab and waits for it. Null when it loaded, else Chromium's error. */
    static String navigate(String key, String url) throws Exception {
        JSONObject r = call("Page.navigate", new JSONObject().put("url", url), sessionFor(key), 30_000);
        String err = r.optString("errorText", "");
        settle(key, 20_000);
        return err.isEmpty() ? null : err;
    }

    /** Back in the chat's tab's history; false when there's nothing to go back to. */
    static boolean back(String key) throws Exception {
        String s = sessionFor(key);
        JSONObject h = call("Page.getNavigationHistory", null, s, 5000);
        int i = h.getInt("currentIndex");
        if (i <= 0) return false;
        call("Page.navigateToHistoryEntry", new JSONObject().put("entryId",
                h.getJSONArray("entries").getJSONObject(i - 1).getInt("id")), s, 10_000);
        settle(key, 15_000);
        return true;
    }

    /** A click at a point of the chat's tab, in CSS pixels of its viewport. */
    static void click(String key, double x, double y) throws Exception {
        String s = sessionFor(key);
        call("Input.dispatchMouseEvent", mouse("mouseMoved", x, y, 0), s, 5000);
        call("Input.dispatchMouseEvent", mouse("mousePressed", x, y, 1), s, 5000);
        call("Input.dispatchMouseEvent", mouse("mouseReleased", x, y, 1), s, 5000);
    }

    private static JSONObject mouse(String type, double x, double y, int clicks) throws Exception {
        JSONObject o = new JSONObject().put("type", type).put("x", x).put("y", y);
        if (clicks > 0) o.put("button", "left").put("clickCount", clicks);
        return o;
    }

    /** Scrolls the chat's tab like a mouse wheel at (x, y), by dy CSS pixels (positive: down). */
    static void wheel(String key, double x, double y, double dx, double dy) throws Exception {
        call("Input.dispatchMouseEvent", new JSONObject().put("type", "mouseWheel").put("x", x).put("y", y)
                .put("deltaX", dx).put("deltaY", dy), sessionFor(key), 5000);
    }

    /** Types text where the chat's tab has its focus, as a keyboard would. */
    static void insertText(String key, String text) throws Exception {
        call("Input.insertText", new JSONObject().put("text", text), sessionFor(key), 10_000);
    }

    /** Presses Enter, Backspace, Tab or Escape in the chat's tab. */
    static void key(String key, String name) throws Exception {
        String s = sessionFor(key);
        int code;
        String text = null;
        switch (name) {
            case "Enter": code = 13; text = "\r"; break;
            case "Backspace": code = 8; break;
            case "Tab": code = 9; break;
            default: code = 27; break; // Escape
        }
        JSONObject down = new JSONObject().put("type", text != null ? "keyDown" : "rawKeyDown").put("key", name)
                .put("code", name).put("windowsVirtualKeyCode", code).put("nativeVirtualKeyCode", code);
        if (text != null) down.put("text", text).put("unmodifiedText", text);
        call("Input.dispatchKeyEvent", down, s, 5000);
        call("Input.dispatchKeyEvent", new JSONObject().put("type", "keyUp").put("key", name).put("code", name)
                .put("windowsVirtualKeyCode", code).put("nativeVirtualKeyCode", code), s, 5000);
    }

    /** A JPEG of what the chat's tab shows. */
    static byte[] screenshot(String key) throws Exception {
        JSONObject r = call("Page.captureScreenshot", new JSONObject().put("format", "jpeg").put("quality", 70),
                sessionFor(key), 15_000);
        return android.util.Base64.decode(r.getString("data"), android.util.Base64.DEFAULT);
    }

    // ------------------------------------------------------------- live view

    /** Starts sending the chat's tab's frames to sink (any thread; starts the browser if needed). */
    static void watch(Context c, String key, Frames sink) {
        List<Frames> l = viewers.get(key);
        if (l == null) viewers.put(key, l = new java.util.concurrent.CopyOnWriteArrayList<>());
        l.add(sink);
        used();
        work.execute(() -> {
            try {
                if (ensure(c, 120_000) != null) return;
                recast(key);
            } catch (Exception ignored) {
            }
        });
    }

    static void unwatch(String key, Frames sink) {
        List<Frames> l = viewers.get(key);
        if (l == null) return;
        l.remove(sink);
        if (!l.isEmpty()) return;
        viewers.remove(key);
        String s = casting.remove(key);
        if (s != null && !casting.containsValue(s)) send("Page.stopScreencast", null, s);
        used(); // the idle stop counts from when the last view closed
    }

    /** (Re)starts the screencast for the chat's current tab, and sends a first frame right away. */
    private static synchronized void recast(String key) {
        try {
            if (!viewers.containsKey(key) || state != State.READY) return;
            String s = sessionFor(key);
            String old = casting.put(key, s);
            if (old != null && !old.equals(s) && !casting.containsValue(old)) send("Page.stopScreencast", null, old);
            call("Page.startScreencast", new JSONObject().put("format", "jpeg").put("quality", 55)
                    .put("maxWidth", WIDTH * 2).put("maxHeight", HEIGHT * 2).put("everyNthFrame", 1), s, 10_000);
            // the screencast only sends a frame when the page changes: show what's there now
            byte[] jpg = screenshot(key);
            deliver(key, BitmapFactory.decodeByteArray(jpg, 0, jpg.length));
            changed();
        } catch (Exception e) {
            android.util.Log.w("BuddyBrowser", "screencast", e);
        }
    }

    private static volatile long lastFrame;

    private static void onFrame(String session, JSONObject p) {
        final int frameId = p.optInt("sessionId");
        String data = p.optString("data");
        // a few frames a second: the next one comes only after this one is acknowledged
        long wait = Math.max(0, FRAME_GAP_MS - (SystemClock.elapsedRealtime() - lastFrame));
        acks.schedule(() -> {
            lastFrame = SystemClock.elapsedRealtime();
            send("Page.screencastFrameAck", jsonOrNull("sessionId", frameId), session);
        }, wait, TimeUnit.MILLISECONDS);
        work.execute(() -> {
            byte[] jpg = android.util.Base64.decode(data, android.util.Base64.DEFAULT);
            Bitmap b = BitmapFactory.decodeByteArray(jpg, 0, jpg.length);
            for (Map.Entry<String, String> e : casting.entrySet()) if (e.getValue().equals(session)) deliver(e.getKey(), b);
        });
    }

    private static void deliver(String key, Bitmap b) {
        List<Frames> l = viewers.get(key);
        if (b == null || l == null) return;
        for (Frames f : l) f.onFrame(b, WIDTH, HEIGHT);
    }

    private static JSONObject jsonOrNull(String k, Object v) {
        try {
            return new JSONObject().put(k, v);
        } catch (Exception e) {
            return null;
        }
    }

    /** For the live view: runs r on the browser's own threads (never on the main thread). */
    static void later(ThrowingRunnable r) {
        used();
        work.execute(() -> {
            try {
                r.run();
            } catch (Exception e) {
                android.util.Log.w("BuddyBrowser", "live view action", e);
            }
        });
    }

    interface ThrowingRunnable {
        void run() throws Exception;
    }

    // ------------------------------------------------------- signed-in sites

    /**
     * The sites with cookies in the browser's profile (its sign-ins), by site name ("amazon.de"), sorted. Read from
     * the running browser, or else from the profile's cookie file. Not on the main thread.
     */
    static List<String> sites(Context c) {
        java.util.TreeSet<String> out = new java.util.TreeSet<>();
        if (running()) {
            try {
                JSONArray a = call("Storage.getCookies", null, null, 10_000).getJSONArray("cookies");
                for (int i = 0; i < a.length(); i++) out.add(site(a.getJSONObject(i).optString("domain")));
                out.remove("");
                return new ArrayList<>(out);
            } catch (Exception ignored) {
            }
        }
        File db = new File(Linux.root(c), "root/.buddy/browser/Default/Cookies");
        if (!db.exists()) return new ArrayList<>();
        File copy = new File(c.getCacheDir(), "browser-cookies.db");
        try {
            java.nio.file.Files.copy(db.toPath(), copy.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            try (android.database.sqlite.SQLiteDatabase d = android.database.sqlite.SQLiteDatabase.openDatabase(copy.getPath(),
                    null, android.database.sqlite.SQLiteDatabase.OPEN_READONLY);
                 android.database.Cursor cur = d.rawQuery("SELECT DISTINCT host_key FROM cookies", null)) {
                while (cur.moveToNext()) out.add(site(cur.getString(0)));
            }
        } catch (Exception e) {
            android.util.Log.w("BuddyBrowser", "cookies", e);
        } finally {
            copy.delete();
        }
        out.remove("");
        return new ArrayList<>(out);
    }

    /**
     * The user typed on the chat's tab while it showed a sign-in form: remember its site as one they signed in to.
     * Cookies alone don't say that (ads and every page an agent opened leave some). Not on the main thread.
     */
    static void userTyped(String key) {
        try {
            String[] p = page(key);
            if (p == null || !((JSONObject) eval(key, "__buddy.signals()")).optBoolean("login")) return;
            String site = site(new java.net.URI(p[0]).getHost());
            if (site.isEmpty()) return;
            java.util.Set<String> s = new java.util.TreeSet<>(signIns(app));
            if (s.add(site)) app.getSharedPreferences("browser", 0).edit().putStringSet("signins", s).apply();
        } catch (Exception ignored) {
        }
    }

    /** The sites the user signed in to in the browser (see userTyped), whether or not they still have cookies. */
    static java.util.Set<String> signIns(Context c) {
        return c.getSharedPreferences("browser", 0).getStringSet("signins", java.util.Collections.emptySet());
    }

    private static void forget(Context c, String site) {
        java.util.Set<String> s = new java.util.TreeSet<>(c.getSharedPreferences("browser", 0)
                .getStringSet("signins", java.util.Collections.emptySet()));
        if (site == null) s.clear();
        else s.remove(site);
        c.getSharedPreferences("browser", 0).edit().putStringSet("signins", s).apply();
    }

    /** "accounts.google.com" → "google.com", "www.amazon.co.uk" → "amazon.co.uk". */
    static String site(String host) {
        String h = host == null ? "" : host.trim().toLowerCase(java.util.Locale.ROOT);
        while (h.startsWith(".")) h = h.substring(1);
        String[] p = h.split("\\.");
        if (p.length <= 2) return h;
        // a second level under a country (co.uk, com.au, co.jp, …) belongs to the name
        int keep = p[p.length - 2].matches("co|com|net|org|gov|ac|edu|ne|or|gob|gouv") && p[p.length - 1].length() == 2 ? 3 : 2;
        StringBuilder sb = new StringBuilder();
        for (int i = Math.max(0, p.length - keep); i < p.length; i++) sb.append(sb.length() > 0 ? "." : "").append(p[i]);
        return sb.toString();
    }

    /** Signs the browser out of a site: its cookies and the data it stored go (starts the browser for it). */
    static void logOut(Context c, String site) throws Exception {
        String why = ensure(c, 60_000);
        if (why != null) throw new IllegalStateException(why);
        JSONArray a = call("Storage.getCookies", null, null, 10_000).getJSONArray("cookies");
        java.util.Set<String> hosts = new java.util.HashSet<>();
        for (int i = 0; i < a.length(); i++) {
            JSONObject k = a.getJSONObject(i);
            if (!site.equals(site(k.optString("domain")))) continue;
            call("Network.deleteCookies", new JSONObject().put("name", k.getString("name")).put("domain", k.getString("domain"))
                    .put("path", k.optString("path", "/")), sessionFor(USER), 5000);
            String d = k.getString("domain");
            hosts.add(d.startsWith(".") ? d.substring(1) : d);
        }
        hosts.add(site);
        hosts.add("www." + site);
        forget(c, site);
        for (String h : hosts) {
            try {
                call("Storage.clearDataForOrigin", new JSONObject().put("origin", "https://" + h)
                        .put("storageTypes", "local_storage,indexeddb,websql,cache_storage,service_workers"), null, 5000);
            } catch (Exception ignored) {
            }
        }
        changed();
    }

    /** Wipes the browser's profile: every sign-in, cookie, history and cache. Not on the main thread. */
    static void wipe(Context c) {
        stop();
        Linux.deleteTree(new File(Linux.root(c), "root/.buddy/browser"));
        forget(c, null);
        changed();
    }
}
