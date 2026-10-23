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
import android.view.ViewConfiguration;
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

/** The phone-control tools exposed to the agents (Claude Code, Codex, OpenCode, Antigravity) over MCP. */
final class Tools {
    private static final int MAX_NODES = 350;
    /** Screenshots are scaled so their shorter side is at most this, the same in portrait and landscape. */
    private static final int SCREENSHOT_SHORT_SIDE = 720;

    private final BuddyService svc;
    private final Object lock = new Object();
    private final List<AccessibilityNodeInfo> lastNodes = new ArrayList<>();
    private final BrowserTools browser;

    Tools(BuddyService svc) {
        this.svc = svc;
        browser = new BrowserTools(svc);
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
                        + "Show the exact text or action. Returns APPROVED, DENIED, or CHANGES REQUESTED with "
                        + "what the user wants changed: then make those changes and call confirm again.",
                props().put("summary", strProp("Exactly what you are about to do, including any text to be sent")),
                "summary"));
        t.put(tool("ask",
                "Ask the user a question in the Buddy bubble and wait for the answer: 2 to 4 answer buttons, and "
                        + "the user can always type their own answer instead. Use it when you need the user to choose "
                        + "or decide (which reply, which item, which time) instead of guessing. With highlight, it also "
                        + "hands a step over to the user (a password or code, their details in a form, a file picker, a "
                        + "captcha): e.g. \"Type your password, then tap Done\" with options [\"Done\", \"I can't\"] and the "
                        + "field in highlight. Returns ANSWER with the option picked, ANSWER (typed) with the user's own "
                        + "words, or NO ANSWER. It is not an approval: still call confirm before anything outward-facing.",
                props().put("question", strProp("The question, short and clear"))
                        .put("options", new JSONObject().put("type", "array").put("minItems", 2).put("maxItems", 4)
                                .put("items", new JSONObject().put("type", "string"))
                                .put("description", "2 to 4 short answers, one per button"))
                        .put("highlight", marksProp("Optional: what the user has to do on the screen before answering "
                                + "(as in point_out), e.g. the fields to fill in. Buddy marks them, and the question waits "
                                + "next to the bubble instead of covering the screen; the marks go once they answer. When "
                                + "every mark is a button or a switch, Buddy closes the question itself once it sees "
                                + "them done (the result starts with DONE).")),
                "question", "options"));
        if (!Flavor.PLAY) { // the background browser's items, for a step the user does there (see BrowserTools)
            JSONObject item = new JSONObject().put("type", "object").put("properties", props()
                    .put("ref", intProp("The item's number from browser_read"))
                    .put("label", strProp("A few words shown next to it, e.g. \"Your password\"")));
            t.getJSONObject(t.length() - 1).getJSONObject("inputSchema").getJSONObject("properties").put("browser_highlight",
                    new JSONObject().put("type", "array").put("maxItems", 6).put("items", item).put("description",
                            "Optional, instead of highlight: items in the background browser the user has to use (a "
                                    + "sign-in, a captcha, their details). Buddy opens the browser full screen with them "
                                    + "marked, and closes it once the user answers."));
        }
        t.put(tool("point_out",
                "Show the user where to look or what to do on the screen: a box, circle or arrow with a short label "
                        + "around up to 6 elements, drawn over the app without blocking their touches. When the user does "
                        + "what a mark points at (taps that button, flips that switch, fills in that field and moves on), "
                        + "it turns green and goes. With wait=true the call returns only then (DONE: what they did) or "
                        + "when they don't (GONE, LEFT, NOT DONE): use it to guide the user step by step through "
                        + "something they want to learn to do themselves, one step per call, instead of doing it for "
                        + "them. Marks follow their element as the screen scrolls, and go when it's gone, when the app "
                        + "changes, when you use another phone action, or after seconds. Call with no marks to remove them.",
                props().put("marks", marksProp("What to point out, e.g. [{\"element\": 12, \"label\": \"First name\"}, "
                                + "{\"element\": 14, \"label\": \"Last name\"}]"))
                        .put("style", enumProp("box", "circle", "arrow"))
                        .put("wait", boolProp("Wait until the user has done what the marks point at, or until they can't anymore"))
                        .put("seconds", intProp("How long the marks stay at most: default 60 (180 with wait), max 300")),
                "marks"));
        t.put(tool("notify", "Show a short status line in the Buddy bubble without stopping.",
                props().put("text", strProp("Status text")), "text"));
        t.put(tool("reply_notification",
                "Answer the notification that started this chat through its own reply field, without opening the app. "
                        + "Only in chats started by one of the user's notification rules, when the notification has a reply "
                        + "field. Buddy shows the user an approval card with the text first, unless you called confirm "
                        + "with it just before.",
                props().put("text", strProp("The reply, exactly as it should be sent")), "text"));
        if (!Flavor.PLAY) { // Play updates Buddy itself and doesn't allow installing other APKs
            t.put(tool("install_apk",
                    "(Developer mode) Open Android's installer for an APK built in Buddy's Linux, e.g. "
                            + "/root/.buddy/work/myapp/build/app.apk, or served by another app on this phone at a local URL "
                            + "(http://127.0.0.1:<port>/app.apk, e.g. from Termux). The user taps Install/Update themselves; "
                            + "call wait_for_install next.",
                    props().put("path", strProp("Full path of the .apk inside Buddy's Linux, or its http://127.0.0.1 URL")), "path"));
            t.put(tool("update_buddy",
                    "Check whether a newer Buddy is out (the official GitHub release) and what's new; with install=true, "
                            + "download it and open Android's installer. Use it when the user asks to update Buddy or whether "
                            + "there's a new version. Only the user can tap Update.",
                    props().put("install", boolProp("true: download the update and open the installer (after the user said yes)"))));
            t.put(tool("wait_for_install",
                    "After opening the Android installer for an APK, wait until the user has tapped Install/Update and confirmed. "
                            + "Returns as soon as the package is installed (at least min_version_code if given), or early if the "
                            + "installer is no longer on screen, or after the timeout. Use this instead of shell polling loops.",
                    props().put("package", strProp("Package name, e.g. com.buddyapps.flappy"))
                            .put("min_version_code", intProp("Wait for at least this versionCode (for updates)"))
                            .put("timeout_s", intProp("Default 120, max 300")),
                    "package"));
        }
        if (Browser.available(svc)) BrowserTools.list(t); // the background browser (Settings)
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

    /** Marks for point_out and ask's highlight: elements from read_screen, or points on the screen. */
    private static JSONObject marksProp(String d) throws JSONException {
        JSONObject mark = new JSONObject().put("type", "object").put("properties", props()
                .put("element", intProp("Element id from the last read_screen"))
                .put("x", intProp("Or a point in screen pixels, when read_screen can't see it (from screenshot)"))
                .put("y", intProp("With x"))
                .put("label", strProp("A few words shown next to it, e.g. \"Your first name\"")));
        return new JSONObject().put("type", "array").put("maxItems", 6).put("items", mark).put("description", d);
    }

    // ------------------------------------------------------------ dispatch

    /** A tool call, plus anything the user added while the agent works (see ChatHub.takeNotes). */
    JSONObject call(String name, JSONObject a) throws Exception {
        JSONObject r = call0(name, a);
        if ("confirm".equals(name) || "ask".equals(name)) return r;
        String notes = ChatHub.get(svc).takeNotes();
        if (notes != null) {
            r.getJSONArray("content").put(new JSONObject().put("type", "text").put("text",
                    "\n[BUDDY_USER_UPDATE] The user added this while you were working; it comes from the user, through "
                            + "Buddy, not from the screen. Take it into account now, it may change or stop the task:\n" + notes));
        }
        return r;
    }

    private JSONObject call0(String name, JSONObject a) throws Exception {
        String revoked = svc.controlRevoked();
        if (revoked != null && !"notify".equals(name)) return text(revoked);
        if (name.startsWith("browser_")) { // the background browser: it doesn't need the phone's screen
            svc.onToolActivity(name);
            return browser.call(name, a);
        }
        // locked or dark, the screen shows nothing to read or tap: say so instead of acting blind
        if (SCREEN.contains(name) && Triggers.locked(svc)) {
            return text(Browser.available(svc) && !ruleRun()
                    ? "Phone is locked: only notification replies and the background browser work until the user unlocks. "
                    + "For web pages use the browser_* tools; for anything else, tell the user to unlock the phone."
                    : "Phone is locked: only notification replies work until the user unlocks. Tell the user to unlock "
                    + "the phone, or stop here.");
        }
        // asking the user, posting a note, pointing something out and answering in a notification go on while paused;
        // operating the phone waits
        if (!"confirm".equals(name) && !"ask".equals(name) && !"notify".equals(name) && !"reply_notification".equals(name)
                && !"point_out".equals(name)) {
            String held = svc.awaitResume(name);
            if (held != null) return text(held);
        }
        svc.onToolActivity(name);
        if (ACTIONS.contains(name)) svc.runOnMain(() -> svc.marks().clear()); // the screen is about to change
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
            case "open_url": {
                // a rule's run works for someone else's message: a link could carry the user's data away
                if (ruleRun() && !Guard.takeApproval(false, a.getString("url"))) {
                    String answer = svc.askConfirm(svc.getString(R.string.tools_card_open_url, a.getString("url")), false);
                    if (!answer.startsWith("APPROVED")) return text("Not opened. " + answer);
                }
                return text(openUrl(a.getString("url")));
            }
            case "screenshot": return screenshot();
            case "wait": {
                long ms = Math.max(0, Math.min(10000, a.optLong("ms", 1000)));
                Thread.sleep(ms);
                return text("Waited " + ms + " ms.");
            }
            case "confirm": {
                if (!ruleRun() && Guard.autonomous(svc, svc.foregroundPkg())) {
                    // no card in an app the user trusts; not counted as approval for a tap in another app
                    return text("APPROVED automatically: the user trusts " + svc.foregroundAppName() + ", so work on "
                            + "your own there without calling confirm. Apps on Hard or Smart still ask the user.");
                }
                String answer = svc.askConfirm(a.getString("summary"), true);
                // covers the next risky tap (after a rule's countdown, only a send)
                if (answer.startsWith("APPROVED")) Guard.approved(a.getString("summary"), answer.equals(BuddyService.AUTO_APPROVED));
                return text(answer);
            }
            case "ask": {
                JSONArray o = a.optJSONArray("options");
                java.util.List<String> options = new java.util.ArrayList<>();
                for (int i = 0; o != null && i < o.length() && options.size() < 4; i++) {
                    String v = o.optString(i, "").trim();
                    if (!v.isEmpty() && !options.contains(v)) options.add(v);
                }
                if (options.size() < 2) return text("Give 2 to 4 options. The user can always type their own answer.");
                JSONArray inBrowser = a.optJSONArray("browser_highlight");
                if (inBrowser != null && inBrowser.length() > 0) return text(askInBrowser(a.getString("question"), options, inBrowser));
                List<Marks.Mark> marks = marks(a.optJSONArray("highlight"));
                // when every mark is a tap or a switch, seeing them all done answers the question; typing has no clear
                // end, so a question with a field waits for the user's answer
                boolean closes = !marks.isEmpty();
                for (Marks.Mark m : marks) closes &= m.detectable() && !m.field;
                final boolean auto = closes;
                AtomicReference<String> stepDone = new AtomicReference<>();
                if (!marks.isEmpty()) svc.runOnMain(() -> svc.marks().show(marks, "box", 0, r -> {
                    if (auto && r.startsWith("DONE")) stepDone.set(r);
                }));
                try {
                    return text(svc.askQuestion(a.getString("question"), options, !marks.isEmpty(), stepDone::get));
                } finally {
                    if (!marks.isEmpty()) svc.runOnMain(() -> svc.marks().clear());
                }
            }
            case "point_out":
                return text(pointOut(a));
            case "update_buddy":
            case "install_apk":
            case "wait_for_install":
                if (ruleRun()) return text("Not available in a notification rule's run. Tell the user what's needed instead.");
                return text(installs(name, a));
            case "notify": {
                svc.showStatus(a.getString("text"));
                return text("Shown.");
            }
            case "reply_notification":
                return text(replyNotification(a.getString("text")));
            default:
                throw new IllegalArgumentException("Unknown tool " + name);
        }
    }

    /**
     * ask with browser_highlight: the background browser opens full screen with the items marked, the question waits
     * next to the bubble, and the browser closes again once the user answers.
     */
    private String askInBrowser(String question, List<String> options, JSONArray items) throws Exception {
        String key = Browser.key(McpServer.CALLER_CHAT.get());
        if (Flavor.PLAY || !Browser.available(svc) || !Browser.running() || !Browser.hasTab(key)) {
            return "The background browser has no page open for you: use browser_open first, or highlight instead.";
        }
        JSONArray m = new JSONArray();
        for (int i = 0; i < items.length() && m.length() < 6; i++) {
            JSONObject it = items.optJSONObject(i);
            if (it != null && it.has("ref")) m.put(new JSONObject().put("ref", it.getInt("ref")).put("label", it.optString("label")));
        }
        if (m.length() == 0) return "Give browser_highlight as [{\"ref\": 12, \"label\": \"Your password\"}].";
        Browser.setMarks(key, m);
        JSONArray boxes = Browser.markBoxes(key, true);
        for (int i = 0; boxes != null && i < boxes.length(); i++) {
            if (boxes.isNull(i)) {
                Browser.setMarks(key, null);
                return "There's no item [" + m.getJSONObject(i).getInt("ref") + "] on the page now. Call browser_read again.";
            }
        }
        svc.runOnMain(() -> svc.startActivity(new Intent(svc, BrowserActivity.class).putExtra("key", key)
                .putExtra("guided", true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)));
        String answer;
        try {
            answer = svc.askQuestion(question, options, true, null);
        } finally {
            Browser.setMarks(key, null); // closes the browser Buddy opened for it
        }
        Browser.touched(key);
        return answer.replace("call read_screen before going on", "call browser_read before going on");
    }

    /** update_buddy, install_apk and wait_for_install. */
    private String installs(String name, JSONObject a) throws Exception {
        switch (name) {
            case "update_buddy":
                if (Flavor.PLAY) return "Buddy updates through Google Play.";
                return updateBuddy(a.optBoolean("install", false));
            case "install_apk":
                if (Flavor.PLAY) return "Installing APKs isn't available in Buddy from Google Play.";
                return installApk(a.getString("path"));
            default:
                return waitForInstall(a.getString("package"), a.optLong("min_version_code", 0),
                        Math.max(10, Math.min(300, a.optInt("timeout_s", 120))));
        }
    }

    /** point_out: shows the marks, and with wait, blocks until the user did what they point at (or why not). */
    private String pointOut(JSONObject a) throws JSONException, InterruptedException {
        List<Marks.Mark> marks = marks(a.optJSONArray("marks"));
        String style = a.optString("style", "box");
        final String shape = "circle".equals(style) || "arrow".equals(style) ? style : "box";
        long ms = Math.max(1, Math.min(300, a.optInt("seconds", a.optBoolean("wait") ? 180 : 60))) * 1000L;
        if (marks.isEmpty()) {
            svc.runOnMain(() -> svc.marks().clear());
            return "Marks removed.";
        }
        if (!a.optBoolean("wait")) {
            svc.runOnMain(() -> svc.marks().show(marks, shape, ms, null));
            return "Showing " + marks.size() + (marks.size() == 1 ? " mark" : " marks") + " for up to " + ms / 1000
                    + " s; they don't block the user's touches. When the user does what one points at, it turns green and "
                    + "goes. To wait for that, call point_out with wait=true, or ask with these marks in highlight.";
        }
        boolean watchable = false;
        for (Marks.Mark m : marks) watchable |= m.detectable();
        if (!watchable) return "Can't wait on these: Buddy can only see the user do it on elements from read_screen, not on bare points.";
        CountDownLatch over = new CountDownLatch(1);
        AtomicReference<String> how = new AtomicReference<>();
        svc.runOnMain(() -> svc.marks().show(marks, shape, ms, r -> {
            how.set(r);
            over.countDown();
        }));
        java.util.function.BooleanSupplier gone = McpServer.CALLER_GONE.get();
        long end = System.currentTimeMillis() + ms + 3000;
        while (!over.await(1, TimeUnit.SECONDS)) {
            if ((gone != null && gone.getAsBoolean()) || System.currentTimeMillis() > end) {
                svc.runOnMain(() -> svc.marks().clear("NOT DONE: Buddy stopped waiting."));
                over.await(2, TimeUnit.SECONDS);
                break;
            }
        }
        svc.screenChangedByUser();
        String r = how.get() == null ? "NOT DONE: Buddy stopped waiting." : how.get();
        return r + (r.startsWith("DONE") ? " Call read_screen to see where they are now."
                : " Call read_screen, then point it out again or ask the user.");
    }

    /** The switch or checkbox a mark on n is about: n itself, or the only one in its row; else null. */
    private static AccessibilityNodeInfo toggleOf(AccessibilityNodeInfo n) {
        if (n.isCheckable()) return n;
        AccessibilityNodeInfo row = n;
        for (int i = 0; row != null && !row.isClickable() && i < 2; i++) row = row.getParent();
        if (row == null || !row.isClickable()) return null;
        List<AccessibilityNodeInfo> found = new ArrayList<>();
        findCheckable(row, 3, found);
        return found.size() == 1 ? found.get(0) : null;
    }

    private static void findCheckable(AccessibilityNodeInfo n, int depth, List<AccessibilityNodeInfo> out) {
        if (n == null || out.size() > 1) return;
        if (n.isCheckable()) {
            out.add(n);
            return;
        }
        for (int i = 0; depth > 0 && i < n.getChildCount(); i++) findCheckable(n.getChild(i), depth - 1, out);
    }

    /** Tools that need the screen: they can't work while the phone is locked or its screen is off. */
    private static final java.util.Set<String> SCREEN = new java.util.HashSet<>(java.util.Arrays.asList(
            "read_screen", "tap", "long_press", "type_text", "scroll", "swipe", "press", "open_app", "open_url",
            "screenshot", "point_out"));

    /** Tools that act on the screen: the point_out marks go first, as the screen is about to change. */
    private static final java.util.Set<String> ACTIONS = new java.util.HashSet<>(java.util.Arrays.asList(
            "tap", "long_press", "type_text", "scroll", "swipe", "press", "open_app", "open_url"));

    /** point_out's marks or ask's highlight: elements from the last read_screen, or points on the screen. */
    private List<Marks.Mark> marks(JSONArray a) throws JSONException {
        List<Marks.Mark> out = new ArrayList<>();
        for (int i = 0; a != null && i < a.length() && out.size() < 6; i++) {
            Object v = a.get(i);
            JSONObject o = v instanceof JSONObject ? (JSONObject) v
                    : v instanceof Number ? new JSONObject().put("element", ((Number) v).intValue()) : null;
            if (o == null) continue;
            String label = o.optString("label", "");
            if (o.has("element")) {
                AccessibilityNodeInfo n = node(o); // says so when it's unknown or gone
                Rect r = new Rect();
                n.getBoundsInScreen(r);
                out.add(new Marks.Mark(n, toggleOf(n), r, label));
            } else if (o.has("x") && o.has("y")) {
                int x = o.getInt("x"), y = o.getInt("y"), s = Ui.dp(svc, 22);
                out.add(new Marks.Mark(null, null, new Rect(x - s, y - s, x + s, y + s), label));
            } else {
                throw new IllegalArgumentException("Each mark needs an element id, or x and y.");
            }
        }
        return out;
    }

    /** The calling chat is a notification rule's run (see Triggers.ruleRun). */
    private boolean ruleRun() {
        return Triggers.ruleRun(McpServer.CALLER_CHAT.get());
    }

    /** The app's mode for this call: a rule's run gets no Trust (it works for someone else's message). */
    private String mode(String pkg) {
        String m = Guard.mode(svc, pkg, null);
        return "trust".equals(m) && ruleRun() ? "hard" : m;
    }

    private static JSONObject text(String s) throws JSONException {
        return new JSONObject().put("content", new JSONArray().put(new JSONObject().put("type", "text").put("text", s)));
    }

    /** Answers the notification a rule's run started from, through its reply field (see Triggers). */
    private String replyNotification(String text) throws InterruptedException {
        String chat = McpServer.CALLER_CHAT.get();
        Triggers.Note n = Triggers.note(chat);
        if (n == null) {
            return "There's no notification to answer: reply_notification only works in chats started by a notification "
                    + "rule. Open the app to reply instead.";
        }
        if (n.reply == null) return "This notification has no reply field. Open " + n.app + " to answer instead.";
        text = text == null ? "" : text.trim();
        if (text.isEmpty()) return "Give the text to send.";
        String sent = Triggers.alreadyReplied(chat);
        if (sent != null) {
            return "Not sent: this run already answered with \u201c" + sent + "\u201d, and a run sends one reply. If "
                    + "something is still missing, tell the user instead.";
        }
        if (Triggers.userAnswered(chat)) return answeredItself(n);
        if (Triggers.wroteAgain(chat)) return wroteAgain(chat, n);
        // like a tap on Send (in a rule's run, where Trust doesn't apply): the confirm just before covers it, or a card
        // asks now; if the user doesn't answer in time, the reply waits in their list and the run ends
        if (!Guard.takeApproval(true, text)) { // an approval covers this reply only if it was for this very text
            final String forChat = chat;
            String answer = svc.askRuleReply(svc.getString(R.string.tools_card_reply, n.who(), n.app, text), chat,
                    () -> Triggers.wroteAgain(forChat) || Triggers.userAnswered(forChat));
            if (BuddyService.STALE.equals(answer)) return Triggers.userAnswered(chat) ? answeredItself(n) : wroteAgain(chat, n);
            if (answer.startsWith(BuddyService.LATER)) {
                Triggers.later(svc, chat, text, answer.substring(BuddyService.LATER.length()));
                noteInChat(chat, svc.getString(R.string.card_note_later));
                return "Not sent yet: the user didn't answer in time, so Buddy keeps your reply in their Waiting list, "
                        + "where they'll send, change or skip it. Don't call reply_notification again; end the run and say "
                        + "in one line that your reply is waiting for them.";
            }
            if (answer.startsWith("CHANGES")) return "Not sent: the user wants changes first. " + answer;
            if (!answer.startsWith("APPROVED")) return "Not sent. " + answer;
            String revoked = svc.controlRevoked(); // Stop while the card was up
            if (revoked != null) return "Not sent. " + revoked;
        }
        if (Triggers.userAnswered(chat)) return answeredItself(n); // they may have, while the card waited
        if (Triggers.wroteAgain(chat)) return wroteAgain(chat, n);
        if (!Triggers.sendReply(svc, n.reply, text)) {
            return "Not sent: " + n.app + " no longer takes replies from this notification (it was probably answered or "
                    + "dismissed). Open the app to check before trying again.";
        }
        Triggers.replied(svc, chat, text);
        return "Sent to " + n.who() + " through the notification's reply field: \u201c" + text + "\u201d. Don't send it again.";
    }

    /** They wrote again since the run began: its reply would answer old messages only, so the next run takes them along. */
    private String wroteAgain(String chat, Triggers.Note n) {
        Triggers.carryOver(chat);
        return "Not sent: " + n.who() + " wrote again in " + n.app + " since this run began, so your reply would answer "
                + "old messages only. Buddy starts a new run for everything together; stop here and say so in one line.";
    }

    /** A line in the run's chat (main thread). */
    private void noteInChat(String chat, String text) {
        svc.runOnMain(() -> {
            ChatHub hub = ChatHub.get(svc);
            Sessions.Session s = chat == null ? null : hub.sessions.find(chat);
            if (s != null) hub.note(s, Bubble.Kind.STEP, text);
        });
    }

    private static String answeredItself(Triggers.Note n) {
        return "Not sent: the user has written to " + n.who() + " in " + n.app + " since, so they answered it themselves. "
                + "Don't reply; just say so.";
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
                CharSequence title = w.getTitle();
                if (root == null) {
                    // an app can hide its windows from accessibility services that aren't assistive tools (Gmail
                    // does): say so, or the agent taps blind on a screen it can't see
                    if (w.getType() == AccessibilityWindowInfo.TYPE_APPLICATION) {
                        sb.append("\n## App window").append(title != null ? " \"" + title + "\"" : "")
                                .append(w.isActive() ? " (active)" : "")
                                .append(": Buddy can't read this window (the app hides its screen, or it's still"
                                        + " loading). Use screenshot to see it. Taps may not work here; if they"
                                        + " don't, ask the user to do this step.\n");
                    }
                    continue;
                }
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
            // gone since read_screen (a dialog closed, the page changed): say so instead of acting on a stale
            // node, which would also put a pointless approval card in front of the user
            if (!n.refresh())
                throw new IllegalArgumentException("Element " + id + " is no longer on screen; the screen changed. "
                        + "Call read_screen again.");
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
                String blocked = guard(n, n);
                if (blocked != null) return blocked;
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
        String blocked = guard(n, Guard.nodeAt(svc.getWindows(), x, y));
        if (blocked != null) return blocked;
        Path p = new Path();
        p.moveTo(x, y);
        boolean ok = gesture(p, longPress ? 700 : 60);
        return (ok ? (longPress ? "Long-pressed" : "Tapped") : "Gesture was cancelled at")
                + " (" + x + ", " + y + "). Call read_screen to see the result.";
    }

    /**
     * The safety rules (see {@link Guard}): null when the tap may go ahead, otherwise why it didn't happen.
     * element: what the agent aimed at (may be null); hit: what's actually under the finger.
     */
    private String guard(AccessibilityNodeInfo element, AccessibilityNodeInfo hit) throws InterruptedException {
        if (svc.bubble() != null && svc.bubble().confirming()) {
            return "BLOCKED: a card is waiting for the user's answer. Wait for it; don't tap anything.";
        }
        if (Guard.offLimits(svc, hit) || (element != null && Guard.offLimits(svc, element))) {
            return "BLOCKED: that's one of Buddy's own screens. Only the user taps those.";
        }
        AccessibilityNodeInfo target = element != null ? element : hit;
        if (target == null) return null;
        String pkg = str(target.getPackageName());
        String mode = mode(pkg);
        if ("trust".equals(mode)) return null;
        String label = Guard.buttonLabel(target);
        String app = svc.foregroundAppName();
        Guard.remember(svc, label, pkg, app);
        if (!ruleRun() && Guard.listed(svc, "allow", label, pkg)) return null; // the user said: never ask for this one
        AccessibilityNodeInfo field = findFocusedInput();
        CharSequence draft = field == null || field.isShowingHintText() ? null : field.getText();
        String what = "Tap “" + (label == null ? "this" : label) + "”"; // for the agent and the reviewer
        java.util.function.Function<String, String> card = in -> tapCard(label, in); // the same, on the user's card
        boolean ask = Guard.listed(svc, "ask", label, pkg)
                || (Prefs.guardBuiltin(svc) && (Guard.riskyLabel(target) != null || (hit != null && Guard.riskyLabel(hit) != null)));
        if (ask) return guardAction(what, card, draft == null ? null : draft.toString(),
                Guard.sends(svc, target) || (hit != null && Guard.sends(svc, hit)));
        if (!"smart".equals(mode)) return null;
        return review(what, card, target, draft == null ? null : draft.toString());
    }

    /**
     * Smart mode: a temporary reviewer session decides an action the rules didn't catch. null when it may go ahead,
     * otherwise the card's answer. draft: text about to be sent, shown on the card (may be null).
     */
    private String review(String what, java.util.function.Function<String, String> card, AccessibilityNodeInfo target,
                          String draft) throws InterruptedException {
        String app = svc.foregroundAppName();
        final String[][] ctx = new String[1][];
        final CountDownLatch got = new CountDownLatch(1);
        svc.runOnMain(() -> {
            ctx[0] = ChatHub.get(svc).reviewContext();
            got.countDown();
        });
        got.await(2, TimeUnit.SECONDS);
        String[] cx = ctx[0];
        String harness = Reviewer.harness(svc, cx == null ? null : cx[0]);
        if (harness == null) return guardAction(what, card, svc.getString(R.string.tools_card_no_reviewer), false);
        svc.runOnMain(() -> {
            if (svc.bubble() != null) svc.bubble().setStatus(svc.getString(R.string.tools_status_checking_tap));
        });
        Reviewer.Decision d = Reviewer.review(svc, harness, cx == null ? null : cx[1], cx == null ? null : cx[2],
                what + (app == null ? "" : " in " + app), Reviewer.screen(svc.getWindows(), target));
        if ("allow".equals(d.decision)) return null;
        String why = svc.getString("block".equals(d.decision) ? R.string.tools_card_reviewer_block : R.string.tools_card_reviewer_ask,
                d.by, d.reason);
        return guardAction(what, card, (draft == null || draft.isEmpty() ? "" : svc.getString(R.string.tools_card_quote, draft) + "\n") + why, false);
    }

    /** The card's line for a tap on label (null: a button without one) in the app named app (null: not known). */
    private String tapCard(String label, String app) {
        if (label == null) return app == null ? svc.getString(R.string.tools_card_tap_this) : svc.getString(R.string.tools_card_tap_this_in, app);
        return app == null ? svc.getString(R.string.tools_card_tap, label) : svc.getString(R.string.tools_card_tap_in, label, app);
    }

    /**
     * A risky action needs the user's OK: the agent's confirm just before, or a card now. what: the action in English,
     * for the agent; card: the same action on the card, in the user's language, given the app's name (or null).
     * send: it sends a message, which a notification rule's countdown may approve.
     */
    private String guardAction(String what, java.util.function.Function<String, String> card, String detail, boolean send)
            throws InterruptedException {
        if (Guard.takeApproval(send)) return null;
        String app = svc.foregroundAppName();
        String summary = detail == null || detail.trim().isEmpty() ? card.apply(app)
                : svc.getString(R.string.tools_card_detail, card.apply(app), detail.contains("\n") || detail.startsWith("“")
                ? detail.trim() : svc.getString(R.string.tools_card_quote, detail.trim()));
        String answer = svc.askConfirm(summary, send);
        if (answer.startsWith("APPROVED")) return null;
        if (answer.startsWith("CHANGES")) return "Not tapped: the user wants changes first. " + answer;
        return "BLOCKED by Buddy's safety rule: the user did not approve “" + what + "”. Don't retry it; "
                + "ask the user what they want.";
    }

    private static String str(CharSequence s) {
        return s == null ? null : s.toString();
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
        if (Guard.offLimits(svc, target)) return "BLOCKED: that's Buddy's own screen. Only the user types there.";
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
            String mode = mode(str(target.getPackageName()));
            java.util.function.Function<String, String> card = in -> in == null ? svc.getString(R.string.tools_card_enter)
                    : svc.getString(R.string.tools_card_enter_in, in);
            // Hard always asks; Smart lets the reviewer decide, as for a tap the rules don't catch
            String blocked = "hard".equals(mode) ? guardAction("Press Enter to send", card, text, !Guard.moneyApp(svc, target.getPackageName()))
                    : "smart".equals(mode) ? review("Press Enter to send", card, target, text) : null;
            if (blocked != null) return result + " Not sent: " + blocked;
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
        int[] xy; // from x, y to x, y
        switch (dir) {
            case "down": xy = new int[]{cx, cy + dy, cx, cy - dy}; break;
            case "up": xy = new int[]{cx, cy - dy, cx, cy + dy}; break;
            case "right": xy = new int[]{cx + dx, cy, cx - dx, cy}; break;
            case "left": xy = new int[]{cx - dx, cy, cx + dx, cy}; break;
            default: throw new IllegalArgumentException("direction must be up, down, left or right");
        }
        String blocked = guardStroke(n, xy);
        if (blocked != null) return blocked;
        Path p = new Path();
        p.moveTo(xy[0], xy[1]);
        p.lineTo(xy[2], xy[3]);
        boolean ok = gesture(p, 350);
        Thread.sleep(400);
        return ok ? "Scrolled " + dir + ". Call read_screen to see the new content." : "Scroll was cancelled.";
    }

    private String swipe(JSONObject a) throws Exception {
        int x1 = a.getInt("x1"), y1 = a.getInt("y1"), x2 = a.getInt("x2"), y2 = a.getInt("y2");
        // A stroke too short to scroll anything lands as a tap, so it gets every check a tap gets
        int slop = ViewConfiguration.get(svc).getScaledTouchSlop();
        boolean tap = Math.hypot(x2 - x1, y2 - y1) <= slop;
        String blocked = tap ? guard(null, Guard.nodeAt(svc.getWindows(), x1, y1)) : guardStroke(null, x1, y1, x2, y2);
        if (blocked != null) return blocked;
        Path p = new Path();
        p.moveTo(x1, y1);
        p.lineTo(x2, y2);
        long d = Math.max(50, Math.min(3000, a.optLong("duration_ms", 300)));
        boolean ok = gesture(p, d);
        return ok ? "Swiped." : "Swipe was cancelled.";
    }

    /**
     * The checks every touch gets, for a scroll or swipe (element: what it was aimed at, may be null; then the
     * points it touches): no touching while a card waits, and never on Buddy's own screens. null when it may go.
     */
    private String guardStroke(AccessibilityNodeInfo element, int... xy) {
        if (svc.bubble() != null && svc.bubble().confirming()) {
            return "BLOCKED: a card is waiting for the user's answer. Wait for it; don't touch the screen.";
        }
        boolean off = element != null && Guard.offLimits(svc, element);
        List<AccessibilityWindowInfo> windows = svc.getWindows();
        for (int i = 0; !off && i + 1 < xy.length; i += 2) off = Guard.offLimits(svc, Guard.nodeAt(windows, xy[i], xy[i + 1]));
        return off ? "BLOCKED: that's one of Buddy's own screens. Only the user touches those." : null;
    }

    private boolean gesture(Path path, long durationMs) throws InterruptedException {
        svc.setBubblePassThrough(true);
        try {
            final CountDownLatch done = new CountDownLatch(1);
            final boolean[] ok = {false};
            GestureDescription g = new GestureDescription.Builder()
                    .addStroke(new GestureDescription.StrokeDescription(path, 0, durationMs)).build();
            if (svc.paused()) return false; // the user took the phone since this call began
            svc.autoPauser().ownGesture(durationMs); // Buddy's own touch: not the user taking the phone
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
        // Buddy's own screens hold the safety settings: only the user opens them (unless they let agents in)
        if (best.equals(svc.getPackageName()) && !Prefs.agentsTapBuddy(svc))
            return "BLOCKED: agents can't open Buddy itself. Only the user uses Buddy's screens.";
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
        svc.showStatus(svc.getString(before < 0 ? R.string.tools_status_tap_install : R.string.tools_status_tap_update));
        svc.nudge();
        long start = System.currentTimeMillis(), lastSeen = start;
        while (System.currentTimeMillis() - start < timeoutS * 1000L) {
            long v = installedVersion(pkg);
            if (v >= 0 && v >= need) {
                svc.showStatus(svc.getString(R.string.tools_status_installed));
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
        java.io.File out = FilesProvider.apkFile(svc);
        if (path != null && path.matches("http://(127\\.0\\.0\\.1|localhost):\\d+/.*")) {
            // only from this phone: another app (e.g. Termux) serving an APK it built
            java.net.HttpURLConnection c = (java.net.HttpURLConnection) new java.net.URL(path).openConnection();
            c.setConnectTimeout(5000);
            c.setReadTimeout(30000);
            try (java.io.InputStream in = c.getInputStream(); java.io.OutputStream o = new java.io.FileOutputStream(out)) {
                copy(in, o);
            } catch (java.io.IOException e) {
                return "Couldn't download " + path + ": " + e.getMessage();
            } finally {
                c.disconnect();
            }
            if (out.length() < 4) return "No APK at " + path + ".";
        } else {
            if (path == null || !path.startsWith("/") || !path.endsWith(".apk")) return "Give the full path of an .apk file.";
            java.io.File root = Linux.root(svc).getCanonicalFile();
            java.io.File f = new java.io.File(root, path.substring(1)).getCanonicalFile();
            if (!f.getPath().startsWith(root.getPath() + "/") || !f.isFile()) return "No APK at " + path + ".";
            try (java.io.InputStream in = new java.io.FileInputStream(f); java.io.OutputStream o = new java.io.FileOutputStream(out)) {
                copy(in, o);
            }
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

    /** Buddy's own updates (see {@link Updates}). */
    private String updateBuddy(boolean install) {
        Updates.Release r;
        try {
            r = Updates.latest();
        } catch (Exception e) {
            return "Couldn't reach GitHub to check for updates (" + e.getMessage() + "). " + Updates.MANUAL;
        }
        String head = "Installed: Buddy " + BuildInfo.VERSION + ". Latest release: " + r.version + ".";
        if (!r.newer()) return head + " Buddy is up to date.";
        if (!install) {
            return head + " An update is available. What's new:\n" + r.notes + "\n\nAsk the user if they want it now; "
                    + "if yes, call update_buddy with install=true.";
        }
        svc.showStatus(svc.getString(R.string.tools_status_downloading, r.version));
        String res = Updates.install(svc, r);
        if (Updates.NEEDS_PERMISSION.equals(res)) {
            return "Downloaded Buddy " + r.version + ", but Buddy isn't allowed to install apps yet. Android's settings page "
                    + "for that is open now: ask the user to turn on 'Allow from this source' for Buddy, then call "
                    + "update_buddy with install=true again.";
        }
        if (!Updates.OPENED.equals(res)) return res + " " + Updates.MANUAL;
        return "The installer is open with Buddy " + r.version + ". Tell the user now, in one line, to tap Update (only "
                + "they can): Buddy restarts once it's installed, which ends this task, and their chats stay. Then stop; "
                + "don't call wait_for_install.";
    }

    private static void copy(java.io.InputStream in, java.io.OutputStream o) throws java.io.IOException {
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
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
        ResolveInfo to = svc.getPackageManager().resolveActivity(i, 0);
        if (to != null && to.activityInfo != null && svc.getPackageName().equals(to.activityInfo.packageName)
                && !Prefs.agentsTapBuddy(svc))
            return "BLOCKED: that link opens Buddy itself. Only the user uses Buddy's screens.";
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
        float scale = Math.min(1f, (float) SCREENSHOT_SHORT_SIDE / Math.min(full.getWidth(), full.getHeight()));
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
