package app.buddy.assistant;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Lite mode, for small models (a model on this phone, issue #63): the agent starts with a dozen phone tools and
 * a short prompt instead of all of them, and loads a group of the others with more_tools when the task needs it. A
 * small model reads its whole prompt again at every step: 24k tokens made each step take minutes on a phone. Only
 * OpenCode chats (buddy-run also leaves out its own tools, skills and the user's MCP servers there).
 */
final class Lite {
    static final String MORE = "more_tools";

    /** Always there. */
    private static final Set<String> CORE = new HashSet<>(Arrays.asList("read_screen", "tap", "type_text", "scroll",
            "swipe", "press", "open_app", "wait_for", "ask", "confirm", "notify", "device_status", MORE));

    /** {name, what it's for, its tools}; a tool in none of them goes with "advanced". */
    private static final String[][] GROUPS = {
            {"screen", "more screen tools: long press, screenshot, scroll until some text shows or gather a long list, "
                    + "several steps in one call, open a link, list the apps, point something out to the user",
                    "long_press", "screenshot", "find", "collect", "do_steps", "wait", "open_url", "list_apps", "point_out"},
            {"info", "the phone's status, its notifications (read them, reply to one), location, calendar events, "
                    + "what's playing, the clipboard",
                    "device_status", "notifications", "reply_notification", "location", "calendar_events", "media",
                    "clipboard_read", "clipboard_write"},
            {"quick", "set an alarm or a timer, add a calendar event, share something, take a photo",
                    "set_alarm", "set_timer", "add_calendar_event", "share", "take_photo"},
            {"browser", "the background browser, for web pages",
                    "browser_open", "browser_read", "browser_click", "browser_type", "browser_scroll", "browser_back",
                    "browser_screenshot", "browser_tabs"},
            {"schedule", "messages to yourself at a later time, rules for incoming notifications",
                    "schedule_message", "list_scheduled", "cancel_scheduled", "add_notification_rule"},
            {"advanced", "sub-agents, phone commands, installing an app, saving a file to Downloads, updating Buddy"},
    };

    /** Set while a call loaded tools: the reply tells the agent's client the list changed (see McpServer). */
    static final ThreadLocal<Boolean> CHANGED = new ThreadLocal<>();

    /** How many times each chat's agent asked for its tool list (see McpServer: more_tools waits for the next). */
    private static final java.util.Map<String, Long> LISTED = new java.util.concurrent.ConcurrentHashMap<>();

    static void listing(String chat) {
        if (chat != null) LISTED.merge(chat, 1L, Long::sum);
    }

    static long listed(String chat) {
        Long n = chat == null ? null : LISTED.get(chat);
        return n == null ? 0 : n;
    }

    private Lite() {
    }

    /**
     * Is this chat in lite mode? Automatic: an OpenCode chat on a model on this phone (OnPhone). One on the user's own
     * computer can be a big model that does fine with everything (and with Linux tools): there it's the user's pick.
     */
    static boolean on(Sessions.Session ses) {
        if (ses == null || !"opencode".equals(ses.backend)) return false;
        if (ses.lite != null) return ses.lite;
        Providers.P p = Providers.of(Models.effective(ses));
        return p != null && OnPhone.ID.equals(p.id);
    }

    /** The calling chat, when it's in lite mode; else null. */
    static Sessions.Session caller(Context c) {
        String chat = McpServer.CALLER_CHAT.get();
        ChatHub hub = chat == null ? null : ChatHub.get(c);
        Sessions.Session ses = hub == null ? null : hub.sessions.find(chat);
        return on(ses) ? ses : null;
    }

    private static String group(String tool) {
        for (String[] g : GROUPS) for (int i = 2; i < g.length; i++) if (g[i].equals(tool)) return g[0];
        return "advanced";
    }

    /** What a lite chat's agent sees of all: the core, the groups it loaded, more_tools; read_screen without "screen". */
    static JSONArray filter(JSONArray all, Sessions.Session ses) throws JSONException {
        JSONArray out = new JSONArray();
        Set<String> loaded;
        synchronized (ses.liteGroups) {
            loaded = new HashSet<>(ses.liteGroups);
        }
        StringBuilder groups = new StringBuilder();
        for (String[] g : GROUPS) {
            if (loaded.contains(g[0])) continue;
            boolean any = false; // (the browser's group only while the browser is on)
            for (int i = 0; i < all.length() && !any; i++) {
                String n = all.getJSONObject(i).optString("name");
                any = !CORE.contains(n) && group(n).equals(g[0]);
            }
            if (any) groups.append(groups.length() > 0 ? "; " : "").append(g[0]).append(": ").append(g[1]);
        }
        for (int i = 0; i < all.length(); i++) {
            JSONObject t = all.getJSONObject(i);
            String n = t.optString("name");
            if (!CORE.contains(n) && !loaded.contains(group(n))) continue;
            if ("read_screen".equals(n) || "screenshot".equals(n)) { // it reads its own screen, never the user's
                t = new JSONObject(t.toString());
                JSONObject props = t.getJSONObject("inputSchema").optJSONObject("properties");
                if (props != null) props.remove("screen");
            }
            out.put(t);
        }
        if (groups.length() > 0) {
            out.put(Tools.tool(MORE, "Load more phone tools when the task needs something the tools you have don't "
                            + "do. They are ready from your next step. Groups: " + groups + ".",
                    new JSONObject().put("group", Tools.strProp("The group's name")), "group"));
        }
        return out;
    }

    /** more_tools: adds the group to the chat's tools. */
    static String more(Context c, JSONObject a) {
        Sessions.Session ses = caller(c);
        if (ses == null) return "Every tool is already there in this chat.";
        String want = a.optString("group").trim().toLowerCase(java.util.Locale.ROOT);
        String[] g = null;
        for (String[] x : GROUPS) if (x[0].equals(want)) g = x;
        // small models copy more of the description ("quick: set an alarm…"): the group named first
        if (g == null) for (String w : want.split("[^a-z]+")) {
            for (String[] x : GROUPS) if (g == null && x[0].equals(w)) g = x;
            if (g != null || !w.isEmpty()) break;
        }
        if (g == null) {
            StringBuilder names = new StringBuilder();
            for (String[] x : GROUPS) names.append(names.length() > 0 ? ", " : "").append(x[0]);
            return "No group \"" + want + "\". The groups: " + names + ".";
        }
        if ("browser".equals(g[0]) && !Browser.available(c)) {
            return "The background browser is off in Buddy's Settings, so there are no browser tools. Open web pages "
                    + "in the phone's browser with phone_open_app instead.";
        }
        boolean added = ses.liteGroups.add(g[0]);
        if (added) { // saved where the chat is (the main thread owns its messages)
            final ChatHub hub = ChatHub.get(c);
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> hub.sessions.save(ses));
            CHANGED.set(true); // (only then: an already loaded group answers at once)
        }
        StringBuilder names = new StringBuilder();
        if (g.length > 2) {
            for (int i = 2; i < g.length; i++) names.append(names.length() > 0 ? ", " : "").append("phone_").append(g[i]);
        } else {
            names.append("the rest of the phone tools");
        }
        return (added ? "Loaded the " : "Already loaded: the ") + g[0] + " tools: " + names
                + (added ? ". They are ready from your next step." : ".");
    }
}
