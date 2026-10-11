package app.buddy.assistant;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Path;
import android.graphics.Rect;
import android.net.Uri;
import android.os.Bundle;
import android.util.Base64;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** The phone-control tools exposed to Claude Code and Codex over MCP. */
final class Tools {
    private static final int MAX_NODES = 350;
    private static final int SCREENSHOT_WIDTH = 720;

    private final BuddyService svc;
    private final Object lock = new Object();
    private final List<AccessibilityNodeInfo> lastNodes = new ArrayList<>();

    Tools(BuddyService svc) {
        this.svc = svc;
    }

    // ---------------------------------------------------------------- schema

    JSONArray list() throws JSONException {
        JSONArray t = new JSONArray();
        t.put(tool("read_screen",
                "Read what is on the phone screen as a list of UI elements with ids, text, and tap coordinates. "
                        + "Call this first and after every action to see the result. Element ids are only valid until the next read_screen.",
                props()));
        t.put(tool("tap",
                "Tap an element (by id from the last read_screen) or a screen coordinate in pixels.",
                props().put("element", intProp("Element id from read_screen"))
                        .put("x", intProp("X in screen pixels")).put("y", intProp("Y in screen pixels"))));
        t.put(tool("long_press",
                "Long-press an element or coordinate.",
                props().put("element", intProp("Element id from read_screen"))
                        .put("x", intProp("X in screen pixels")).put("y", intProp("Y in screen pixels"))));
        t.put(tool("type_text",
                "Type into the focused text field (or the given element). Replaces its content unless append is true. "
                        + "Set submit to press the keyboard's enter/send action afterwards.",
                props().put("text", strProp("Text to type"))
                        .put("element", intProp("Optional element id of the text field"))
                        .put("append", boolProp("Append instead of replacing"))
                        .put("submit", boolProp("Press the IME enter/send action after typing")),
                "text"));
        t.put(tool("scroll",
                "Scroll the screen (or a scrollable element) in a direction. 'down' reveals content further down.",
                props().put("direction", enumProp("up", "down", "left", "right"))
                        .put("element", intProp("Optional scrollable element id")),
                "direction"));
        t.put(tool("swipe",
                "Swipe from one point to another, in screen pixels.",
                props().put("x1", intProp("")).put("y1", intProp("")).put("x2", intProp("")).put("y2", intProp(""))
                        .put("duration_ms", intProp("Default 300")),
                "x1", "y1", "x2", "y2"));
        t.put(tool("press",
                "Press a system button.",
                props().put("button", enumProp("back", "home", "recents", "notifications", "quick_settings")),
                "button"));
        t.put(tool("open_app",
                "Open an installed app by its name (e.g. 'Messenger') or package name.",
                props().put("app", strProp("App name or package")), "app"));
        t.put(tool("list_apps", "List launchable apps with their package names.", props()));
        t.put(tool("open_url", "Open a URL or deep link (https://, mailto:, tel:, geo:, ...).",
                props().put("url", strProp("The URL")), "url"));
        t.put(tool("screenshot",
                "Take a screenshot (JPEG). Use only when read_screen is not enough (images, games, canvas UIs). "
                        + "The reply says how to convert image coordinates to screen pixels.",
                props()));
        t.put(tool("wait", "Wait for the UI to settle, up to 10000 ms.",
                props().put("ms", intProp("Milliseconds")), "ms"));
        t.put(tool("confirm",
                "Ask the user to approve an action in the Buddy bubble and wait for the answer. "
                        + "REQUIRED before sending messages, posting, buying, deleting, or changing settings. "
                        + "Show the exact text or action. Returns APPROVED or DENIED.",
                props().put("summary", strProp("Exactly what you are about to do, including any text to be sent")),
                "summary"));
        t.put(tool("notify", "Show a short status line in the Buddy bubble without stopping.",
                props().put("text", strProp("Status text")), "text"));
        t.put(tool("install_apk",
                "(Developer mode) Open Android's installer for an APK built in Buddy's Linux, e.g. "
                        + "/root/.buddy/work/myapp/build/app.apk. The user taps Install/Update themselves; call wait_for_install next.",
                props().put("path", strProp("Full path of the .apk inside Buddy's Linux")), "path"));
        t.put(tool("wait_for_install",
                "After opening the Android installer for an APK, wait until the user has tapped Install/Update and confirmed. "
                        + "Returns as soon as the package is installed (at least min_version_code if given), or early if the "
                        + "installer is no longer on screen, or after the timeout. Use this instead of shell polling loops.",
                props().put("package", strProp("Package name, e.g. com.buddyapps.flappy"))
                        .put("min_version_code", intProp("Wait for at least this versionCode (for updates)"))
                        .put("timeout_s", intProp("Default 120, max 300")),
                "package"));
        return t;
    }

    private static JSONObject tool(String name, String desc, JSONObject properties, String... required) throws JSONException {
        JSONObject schema = new JSONObject().put("type", "object").put("properties", properties);
        if (required.length > 0) {
            JSONArray r = new JSONArray();
            for (String s : required) r.put(s);
            schema.put("required", r);
        }
        return new JSONObject().put("name", name).put("description", desc).put("inputSchema", schema);
    }

    private static JSONObject props() {
        return new JSONObject();
    }

    private static JSONObject intProp(String d) throws JSONException {
        return new JSONObject().put("type", "integer").put("description", d);
    }

    private static JSONObject strProp(String d) throws JSONException {
        return new JSONObject().put("type", "string").put("description", d);
    }

    private static JSONObject boolProp(String d) throws JSONException {
        return new JSONObject().put("type", "boolean").put("description", d);
    }

    private static JSONObject enumProp(String... values) throws JSONException {
        JSONArray a = new JSONArray();
        for (String v : values) a.put(v);
        return new JSONObject().put("type", "string").put("enum", a);
    }

    // ------------------------------------------------------------ dispatch

    JSONObject call(String name, JSONObject a) throws Exception {
        String revoked = svc.controlRevoked();
        if (revoked != null && !"notify".equals(name)) return text(revoked);
        svc.onToolActivity(name);
        switch (name) {
            case "read_screen": return text(readScreen());
            case "tap": return text(tap(a, false));
            case "long_press": return text(tap(a, true));
            case "type_text": return text(typeText(a));
            case "scroll": return text(scroll(a));
            case "swipe": return text(swipe(a));
            case "press": return text(press(a.getString("button")));
            case "open_app": return text(openApp(a.getString("app")));
            case "list_apps": return text(listApps());
            case "open_url": return text(openUrl(a.getString("url")));
            case "screenshot": return screenshot();
            case "wait": {
                long ms = Math.max(0, Math.min(10000, a.optLong("ms", 1000)));
                Thread.sleep(ms);
                return text("Waited " + ms + " ms.");
            }
            case "confirm": return text(svc.askConfirm(a.getString("summary")));
            case "install_apk":
                return text(installApk(a.getString("path")));
            case "wait_for_install":
                return text(waitForInstall(a.getString("package"), a.optLong("min_version_code", 0),
                        Math.max(10, Math.min(300, a.optInt("timeout_s", 120)))));
            case "notify": {
                svc.showStatus(a.getString("text"));
                return text("Shown.");
            }
            default:
                throw new IllegalArgumentException("Unknown tool " + name);
        }
    }

    private static JSONObject text(String s) throws JSONException {
        return new JSONObject().put("content", new JSONArray().put(new JSONObject().put("type", "text").put("text", s)));
    }

    // ---------------------------------------------------------- read_screen

    private String readScreen() {
        DisplayMetrics dm = svc.getResources().getDisplayMetrics();
        StringBuilder sb = new StringBuilder();
        sb.append("Screen ").append(dm.widthPixels).append('x').append(dm.heightPixels).append(" px\n");
        synchronized (lock) {
            for (AccessibilityNodeInfo n : lastNodes) n.recycle();
            lastNodes.clear();
            List<AccessibilityWindowInfo> windows = svc.getWindows();
            boolean keyboard = false;
            for (AccessibilityWindowInfo w : windows) {
                if (w.getType() == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) continue;
                if (w.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                    keyboard = true;
                    continue;
                }
                AccessibilityNodeInfo root = w.getRoot();
                if (root == null) continue;
                CharSequence title = w.getTitle();
                sb.append("\n## ").append(windowType(w.getType()))
                        .append(" window").append(root.getPackageName() != null ? " [" + root.getPackageName() + "]" : "")
                        .append(title != null ? " \"" + title + "\"" : "")
                        .append(w.isActive() ? " (active)" : "").append('\n');
                walk(root, sb, 0);
            }
            if (keyboard) sb.append("\n(The on-screen keyboard is open.)\n");
            if (lastNodes.size() >= MAX_NODES) sb.append("\n(Truncated at ").append(MAX_NODES).append(" elements; scroll for more.)\n");
        }
        return sb.toString();
    }

    private void walk(AccessibilityNodeInfo n, StringBuilder sb, int depth) {
        if (n == null || lastNodes.size() >= MAX_NODES) return;
        if (!n.isVisibleToUser()) {
            n.recycle();
            return;
        }
        CharSequence text = n.getText();
        CharSequence desc = n.getContentDescription();
        boolean interesting = !empty(text) || !empty(desc) || n.isClickable() || n.isEditable()
                || n.isScrollable() || n.isCheckable() || n.isLongClickable();
        if (interesting) {
            int id = lastNodes.size();
            lastNodes.add(AccessibilityNodeInfo.obtain(n));
            Rect r = new Rect();
            n.getBoundsInScreen(r);
            sb.append(indent(depth)).append('[').append(id).append("] ").append(shortClass(n.getClassName()));
            if (!empty(text)) sb.append(" \"").append(clip(text)).append('"');
            if (!empty(desc) && (empty(text) || !desc.toString().equals(text.toString())))
                sb.append(" desc=\"").append(clip(desc)).append('"');
            if (n.isEditable() && n.isShowingHintText()) sb.append(" (hint)");
            sb.append(" @").append(r.centerX()).append(',').append(r.centerY());
            StringBuilder f = new StringBuilder();
            if (n.isClickable()) f.append(" click");
            if (n.isLongClickable()) f.append(" longclick");
            if (n.isEditable()) f.append(" edit");
            if (n.isScrollable()) f.append(" scroll");
            if (n.isCheckable()) f.append(n.isChecked() ? " checked" : " unchecked");
            if (n.isFocused()) f.append(" focused");
            if (n.isSelected()) f.append(" selected");
            if (!n.isEnabled()) f.append(" disabled");
            if (f.length() > 0) sb.append(" {").append(f.toString().trim()).append('}');
            sb.append('\n');
        }
        int count = n.getChildCount();
        for (int i = 0; i < count; i++) {
            walk(n.getChild(i), sb, interesting ? depth + 1 : depth);
        }
    }

    private static String windowType(int t) {
        switch (t) {
            case AccessibilityWindowInfo.TYPE_APPLICATION: return "App";
            case AccessibilityWindowInfo.TYPE_SYSTEM: return "System";
            case AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER: return "Divider";
            default: return "Other";
        }
    }

    private static String indent(int d) {
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < Math.min(d, 8); i++) s.append("  ");
        return s.toString();
    }

    private static boolean empty(CharSequence c) {
        return c == null || c.toString().trim().isEmpty();
    }

    private static String clip(CharSequence c) {
        String s = c.toString().replace('\n', ' ').replace("\"", "'");
        return s.length() > 160 ? s.substring(0, 157) + "..." : s;
    }

    private static String shortClass(CharSequence c) {
        if (c == null) return "View";
        String s = c.toString();
        int dot = s.lastIndexOf('.');
        return dot >= 0 ? s.substring(dot + 1) : s;
    }

    private AccessibilityNodeInfo node(JSONObject a) {
        if (!a.has("element")) return null;
        int id = a.optInt("element", -1);
        synchronized (lock) {
            if (id < 0 || id >= lastNodes.size())
                throw new IllegalArgumentException("Unknown element " + id + ". Call read_screen again.");
            AccessibilityNodeInfo n = AccessibilityNodeInfo.obtain(lastNodes.get(id));
            n.refresh();
            return n;
        }
    }

    // ------------------------------------------------------------- actions

    private String tap(JSONObject a, boolean longPress) throws Exception {
        AccessibilityNodeInfo n = node(a);
        int x, y;
        if (n != null) {
            Rect r = new Rect();
            n.getBoundsInScreen(r);
            x = r.centerX();
            y = r.centerY();
            if (!n.isVisibleToUser() || r.isEmpty()) {
                // Off-screen or stale: fall back to an accessibility click.
                AccessibilityNodeInfo target = clickableAncestor(n, longPress);
                boolean ok = target != null && target.performAction(longPress
                        ? AccessibilityNodeInfo.ACTION_LONG_CLICK : AccessibilityNodeInfo.ACTION_CLICK);
                return ok ? "Clicked element via accessibility action." : "Element is not visible; scroll to it first.";
            }
        } else {
            if (!a.has("x") || !a.has("y")) throw new IllegalArgumentException("Give element or x and y.");
            x = a.getInt("x");
            y = a.getInt("y");
        }
        Path p = new Path();
        p.moveTo(x, y);
        boolean ok = gesture(p, longPress ? 700 : 60);
        return (ok ? (longPress ? "Long-pressed" : "Tapped") : "Gesture was cancelled at")
                + " (" + x + ", " + y + "). Call read_screen to see the result.";
    }

    private static AccessibilityNodeInfo clickableAncestor(AccessibilityNodeInfo n, boolean longPress) {
        AccessibilityNodeInfo cur = n;
        for (int i = 0; cur != null && i < 8; i++) {
            if (longPress ? cur.isLongClickable() : cur.isClickable()) return cur;
            cur = cur.getParent();
        }
        return null;
    }

    private String typeText(JSONObject a) throws Exception {
        String text = a.getString("text");
        AccessibilityNodeInfo target = node(a);
        if (target == null) target = findFocusedInput();
        if (target == null)
            throw new IllegalStateException("No text field is focused. Tap the text field first, then type.");
        if (!target.isFocused()) target.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
        String value = text;
        if (a.optBoolean("append")) {
            CharSequence cur = target.isShowingHintText() ? null : target.getText();
            value = (cur == null ? "" : cur.toString()) + text;
        }
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, value);
        boolean ok = target.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
        if (!ok) throw new IllegalStateException("This field did not accept text.");
        String result = "Typed " + text.length() + " characters.";
        if (a.optBoolean("submit")) {
            Thread.sleep(150);
            boolean sent = target.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.getId());
            result += sent ? " Pressed enter." : " Enter action not supported here; tap the send button instead.";
        }
        return result;
    }

    /** The focused text field, or the only visible text field when nothing has focus. */
    private AccessibilityNodeInfo findFocusedInput() {
        List<AccessibilityNodeInfo> editable = new ArrayList<>();
        for (AccessibilityWindowInfo w : svc.getWindows()) {
            if (w.getType() == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY
                    || w.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD) continue;
            AccessibilityNodeInfo root = w.getRoot();
            if (root == null) continue;
            AccessibilityNodeInfo f = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
            if (f != null && f.isEditable()) return f;
            collectEditable(root, editable);
        }
        return editable.size() == 1 ? editable.get(0) : null;
    }

    private static void collectEditable(AccessibilityNodeInfo n, List<AccessibilityNodeInfo> out) {
        if (n == null || out.size() > 1 || !n.isVisibleToUser()) return;
        if (n.isEditable() && n.isEnabled()) out.add(n);
        for (int i = 0; i < n.getChildCount(); i++) collectEditable(n.getChild(i), out);
    }

    private String scroll(JSONObject a) throws Exception {
        String dir = a.getString("direction").toLowerCase(Locale.ROOT);
        AccessibilityNodeInfo n = node(a);
        Rect r = new Rect();
        if (n != null) {
            n.getBoundsInScreen(r);
        } else {
            DisplayMetrics dm = svc.getResources().getDisplayMetrics();
            r.set(0, (int) (dm.heightPixels * 0.15), dm.widthPixels, (int) (dm.heightPixels * 0.85));
        }
        int cx = r.centerX(), cy = r.centerY();
        int dy = (int) (r.height() * 0.35), dx = (int) (r.width() * 0.35);
        Path p = new Path();
        switch (dir) {
            case "down": p.moveTo(cx, cy + dy); p.lineTo(cx, cy - dy); break;
            case "up": p.moveTo(cx, cy - dy); p.lineTo(cx, cy + dy); break;
            case "right": p.moveTo(cx + dx, cy); p.lineTo(cx - dx, cy); break;
            case "left": p.moveTo(cx - dx, cy); p.lineTo(cx + dx, cy); break;
            default: throw new IllegalArgumentException("direction must be up, down, left or right");
        }
        gesture(p, 350);
        Thread.sleep(400);
        return "Scrolled " + dir + ". Call read_screen to see the new content.";
    }

    private String swipe(JSONObject a) throws Exception {
        Path p = new Path();
        p.moveTo(a.getInt("x1"), a.getInt("y1"));
        p.lineTo(a.getInt("x2"), a.getInt("y2"));
        long d = Math.max(50, Math.min(3000, a.optLong("duration_ms", 300)));
        boolean ok = gesture(p, d);
        return ok ? "Swiped." : "Swipe was cancelled.";
    }

    private boolean gesture(Path path, long durationMs) throws InterruptedException {
        svc.setBubblePassThrough(true);
        try {
            final CountDownLatch done = new CountDownLatch(1);
            final boolean[] ok = {false};
            GestureDescription g = new GestureDescription.Builder()
                    .addStroke(new GestureDescription.StrokeDescription(path, 0, durationMs)).build();
            boolean dispatched = svc.dispatchGesture(g, new AccessibilityService.GestureResultCallback() {
                @Override
                public void onCompleted(GestureDescription d) {
                    ok[0] = true;
                    done.countDown();
                }

                @Override
                public void onCancelled(GestureDescription d) {
                    done.countDown();
                }
            }, svc.mainHandler());
            if (!dispatched) return false;
            done.await(durationMs + 3000, TimeUnit.MILLISECONDS);
            Thread.sleep(250);
            return ok[0];
        } finally {
            svc.setBubblePassThrough(false);
        }
    }

    private String press(String button) {
        int action;
        switch (button) {
            case "back": action = AccessibilityService.GLOBAL_ACTION_BACK; break;
            case "home": action = AccessibilityService.GLOBAL_ACTION_HOME; break;
            case "recents": action = AccessibilityService.GLOBAL_ACTION_RECENTS; break;
            case "notifications": action = AccessibilityService.GLOBAL_ACTION_NOTIFICATIONS; break;
            case "quick_settings": action = AccessibilityService.GLOBAL_ACTION_QUICK_SETTINGS; break;
            default: throw new IllegalArgumentException("Unknown button " + button);
        }
        boolean ok = svc.performGlobalAction(action);
        try {
            Thread.sleep(500);
        } catch (InterruptedException ignored) {
        }
        return ok ? "Pressed " + button + "." : "Could not press " + button + ".";
    }

    private List<ResolveInfo> launchables() {
        Intent i = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER);
        return svc.getPackageManager().queryIntentActivities(i, 0);
    }

    private String openApp(String query) throws InterruptedException {
        PackageManager pm = svc.getPackageManager();
        String q = query.trim().toLowerCase(Locale.ROOT);
        // Score every app: package name > exact label > label starts with query > label contains it.
        List<String[]> top = new ArrayList<>(); // {package, label} with the best score
        int bestScore = 0;
        for (ResolveInfo ri : launchables()) {
            String pkg = ri.activityInfo.packageName;
            String label = String.valueOf(ri.loadLabel(pm));
            String l = label.toLowerCase(Locale.ROOT);
            int score = pkg.equalsIgnoreCase(q) ? 4 : l.equals(q) ? 3 : l.startsWith(q) ? 2 : l.contains(q) ? 1 : 0;
            if (score == 0 || score < bestScore) continue;
            if (score > bestScore) {
                bestScore = score;
                top.clear();
            }
            top.add(new String[]{pkg, label});
        }
        if (top.isEmpty()) return "No installed app matches '" + query + "'. Use list_apps to see names.";
        if (top.size() > 1) {
            // Don't guess between e.g. Amazon Shopping / Amazon Music / Amazon Alexa.
            StringBuilder sb = new StringBuilder("Several apps match '" + query + "', so nothing was opened:\n");
            for (String[] app : top) sb.append("- ").append(app[1]).append(" (").append(app[0]).append(")\n");
            sb.append("Call open_app again with the exact name or package. If it's unclear which one the user means, ask them.");
            return sb.toString();
        }
        String best = top.get(0)[0];
        String bestLabel = top.get(0)[1];
        Intent launch = pm.getLaunchIntentForPackage(best);
        if (launch == null) return "App " + bestLabel + " cannot be launched.";
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
        svc.startActivity(launch);
        Thread.sleep(1500);
        return "Opened " + bestLabel + " (" + best + "). Call read_screen to see it.";
    }

    private long installedVersion(String pkg) {
        try {
            return svc.getPackageManager().getPackageInfo(pkg, 0).getLongVersionCode();
        } catch (PackageManager.NameNotFoundException e) {
            return -1;
        }
    }

    /** Is an installer / Play Protect dialog what the user is looking at? */
    private boolean installerOnScreen() {
        for (AccessibilityWindowInfo w : svc.getWindows()) {
            AccessibilityNodeInfo r = w.getRoot();
            if (r == null || r.getPackageName() == null) continue;
            String p = r.getPackageName().toString();
            if (p.contains("packageinstaller") || p.equals("com.android.vending")) return true;
            if (w.getType() == AccessibilityWindowInfo.TYPE_SYSTEM && w.getTitle() != null
                    && w.getTitle().toString().toLowerCase(Locale.ROOT).contains("install")) return true;
            if ("android".equals(p) && w.isActive()) return true; // "Open with" chooser
        }
        return false;
    }

    private String waitForInstall(String pkg, long minVersion, int timeoutS) throws InterruptedException {
        long before = installedVersion(pkg);
        long need = minVersion > 0 ? minVersion : (before < 0 ? 0 : before + 1);
        svc.showStatus(before < 0 ? "Tap Install, then confirm" : "Tap Update, then confirm");
        svc.nudge();
        long start = System.currentTimeMillis(), lastSeen = start;
        while (System.currentTimeMillis() - start < timeoutS * 1000L) {
            long v = installedVersion(pkg);
            if (v >= 0 && v >= need) {
                svc.showStatus("Installed");
                return "Installed " + pkg + " (versionCode " + v + "). The user confirmed the install.";
            }
            if (installerOnScreen()) lastSeen = System.currentTimeMillis();
            else if (System.currentTimeMillis() - lastSeen > 12000) {
                return "Not installed yet, and the installer is no longer on screen (the user may have closed it or "
                        + "switched apps). Call install_apk again, then wait_for_install again.";
            }
            Thread.sleep(1000);
        }
        return "Timed out after " + timeoutS + " s: the user hasn't confirmed the install. Ask them whether they want to "
                + "install it, instead of waiting longer.";
    }

    /** Copies an APK out of Buddy's Linux and opens Android's installer for it. */
    private String installApk(String path) throws Exception {
        if (!Prefs.devMode(svc)) return "Installing apps needs developer mode. Ask the user to turn it on in Buddy's Settings.";
        if (path == null || !path.startsWith("/") || !path.endsWith(".apk")) return "Give the full path of an .apk file.";
        java.io.File root = Linux.root(svc).getCanonicalFile();
        java.io.File f = new java.io.File(root, path.substring(1)).getCanonicalFile();
        if (!f.getPath().startsWith(root.getPath() + "/") || !f.isFile()) return "No APK at " + path + ".";
        java.io.File out = FilesProvider.apkFile(svc);
        try (java.io.InputStream in = new java.io.FileInputStream(f); java.io.OutputStream o = new java.io.FileOutputStream(out)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
        }
        if (!svc.getPackageManager().canRequestPackageInstalls()) {
            svc.startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    android.net.Uri.parse("package:" + svc.getPackageName())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            return "Buddy isn't allowed to install apps yet. Android's settings page for that is open now: ask the user to "
                    + "turn on 'Allow from this source' for Buddy, wait until they say it's done, then call install_apk again.";
        }
        svc.startActivity(new Intent(Intent.ACTION_VIEW)
                .setDataAndType(FilesProvider.apkUri(), "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION));
        return "The installer is open. The user must tap Install/Update and confirm (you can't). Call wait_for_install now.";
    }

    private String listApps() {
        PackageManager pm = svc.getPackageManager();
        List<String> lines = new ArrayList<>();
        for (ResolveInfo ri : launchables()) {
            lines.add(ri.loadLabel(pm) + " — " + ri.activityInfo.packageName);
        }
        Collections.sort(lines, String.CASE_INSENSITIVE_ORDER);
        StringBuilder sb = new StringBuilder();
        for (String l : lines) sb.append(l).append('\n');
        return sb.toString();
    }

    private String openUrl(String url) throws InterruptedException {
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        svc.startActivity(i);
        Thread.sleep(1500);
        return "Opened " + url + ". Call read_screen to see it.";
    }

    private JSONObject screenshot() throws Exception {
        svc.setBubblePassThrough(true);
        Thread.sleep(150);
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicReference<Bitmap> shot = new AtomicReference<>();
        final AtomicReference<String> error = new AtomicReference<>();
        try {
            svc.takeScreenshot(Display.DEFAULT_DISPLAY, svc.getMainExecutor(), new AccessibilityService.TakeScreenshotCallback() {
                @Override
                public void onSuccess(AccessibilityService.ScreenshotResult r) {
                    try {
                        Bitmap hw = Bitmap.wrapHardwareBuffer(r.getHardwareBuffer(), r.getColorSpace());
                        if (hw != null) shot.set(hw.copy(Bitmap.Config.ARGB_8888, false));
                        r.getHardwareBuffer().close();
                    } catch (Exception e) {
                        error.set(String.valueOf(e.getMessage()));
                    }
                    done.countDown();
                }

                @Override
                public void onFailure(int code) {
                    error.set(code == AccessibilityService.ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT
                            ? "Screenshots are rate-limited; wait a second and retry." : "Screenshot failed (code " + code + ").");
                    done.countDown();
                }
            });
            done.await(5, TimeUnit.SECONDS);
        } finally {
            svc.setBubblePassThrough(false);
        }
        Bitmap full = shot.get();
        if (full == null) throw new IllegalStateException(error.get() != null ? error.get() : "Screenshot timed out.");
        float scale = Math.min(1f, (float) SCREENSHOT_WIDTH / full.getWidth());
        Bitmap small = scale < 1f
                ? Bitmap.createScaledBitmap(full, Math.round(full.getWidth() * scale), Math.round(full.getHeight() * scale), true)
                : full;
        ByteArrayOutputStream jpg = new ByteArrayOutputStream();
        small.compress(Bitmap.CompressFormat.JPEG, 70, jpg);
        String note = String.format(Locale.ROOT,
                "Screenshot %dx%d (screen is %dx%d). To tap something you see, multiply image coordinates by %.3f.",
                small.getWidth(), small.getHeight(), full.getWidth(), full.getHeight(), 1f / scale);
        return new JSONObject().put("content", new JSONArray()
                .put(new JSONObject().put("type", "image").put("mimeType", "image/jpeg")
                        .put("data", Base64.encodeToString(jpg.toByteArray(), Base64.NO_WRAP)))
                .put(new JSONObject().put("type", "text").put("text", note)));
    }
}
