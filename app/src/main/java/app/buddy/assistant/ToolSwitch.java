package app.buddy.assistant;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/**
 * Settings → Tools and connections → Tools: each phone tool on or off, the user's choice (ToolsActivity). A tool that is off
 * is in no agent's tool list, and a call to it anyway (an agent that listed its tools earlier) is refused.
 */
final class ToolSwitch {
    /** Always on: the approval cards. Without them an agent couldn't ask the user before a risky step. */
    static final Set<String> LOCKED = new HashSet<>(Arrays.asList("confirm", "ask"));

    /** Off until the user turns them on. */
    private static final Set<String> DEFAULT_OFF = new HashSet<>(Arrays.asList("call_log", "sms_list"));

    /** Read through background mode's helper: not offered while background mode isn't set up (the switch stays). */
    private static final Set<String> NEEDS_HELPER = new HashSet<>(Arrays.asList("call_log", "sms_list"));

    /** {group, its tools}, as the screen shows them; every tool Buddy has is in one. */
    static final String[][] GROUPS = {
            {"screen", "read_screen", "screenshot", "tap", "long_press", "type_text", "scroll", "swipe", "press",
                    "find", "collect", "do_steps", "wait", "wait_for", "point_out"},
            {"apps", "open_app", "list_apps", "open_url", "share", "save_to_downloads", "install_apk",
                    "wait_for_install", "update_buddy"},
            {"info", "device_status", "notifications", "reply_notification", "location", "calendar_events",
                    "add_calendar_event", "media", "clipboard_read", "clipboard_write", "call_log", "sms_list"},
            {"quick", "set_alarm", "set_timer", "take_photo", "notify"},
            {"talk", "confirm", "ask"},
            {"browser", "browser_open", "browser_read", "browser_click", "browser_type", "browser_scroll",
                    "browser_back", "browser_screenshot", "browser_tabs"},
            {"schedule", "schedule_message", "list_scheduled", "cancel_scheduled", "add_notification_rule"},
            {"advanced", "delegate", "subagents", "phone_shell"},
    };

    private ToolSwitch() {
    }

    /** In Settings: what every chat follows unless it has its own choice. */
    static boolean on(Context c, String tool) {
        return LOCKED.contains(tool) || Prefs.toolOn(c, tool, !DEFAULT_OFF.contains(tool));
    }

    /** In this chat: its own choice (Chat options → Tools), else Settings; null (no chat) is Settings. */
    static boolean on(Context c, String tool, Sessions.Session chat) {
        if (LOCKED.contains(tool)) return true;
        // a sub-agent goes by its chat's choices as they are now (changed while it works too)
        if (chat != null && !chat.parent.isEmpty()) {
            Sessions.Session p = ChatHub.get(c).sessions.find(chat.parent);
            if (p != null) chat = p;
        }
        Boolean own = chat == null ? null : chat.tools.get(tool);
        return own != null ? own : on(c, tool);
    }

    /** On, and usable on this phone now: what an agent is offered. */
    static boolean offered(Context c, String tool, Sessions.Session chat) {
        return on(c, tool, chat) && (!NEEDS_HELPER.contains(tool) || Background.setUp(c));
    }

    /** For the agent calling now: its chat's choice, or Settings for a session outside Buddy. */
    static boolean onForCaller(Context c, String tool) {
        return on(c, tool, caller(c));
    }

    static Sessions.Session caller(Context c) {
        String id = McpServer.CALLER_CHAT.get();
        return id == null ? null : ChatHub.get(c).sessions.find(id);
    }

    static void set(Context c, String tool, boolean on) {
        if (!LOCKED.contains(tool)) Prefs.setToolOn(c, tool, on);
    }

    /** The tools that are on, of these. */
    static JSONArray filter(Context c, JSONArray tools) {
        Sessions.Session chat = caller(c);
        JSONArray out = new JSONArray();
        for (int i = 0; i < tools.length(); i++) {
            JSONObject t = tools.optJSONObject(i);
            if (t != null && offered(c, t.optString("name"), chat)) out.put(t);
        }
        return out;
    }

    /** What an agent is told when it calls a tool that is off. */
    static String refusal(String tool) {
        return "The user turned the " + tool + " tool off (Buddy's Settings → Tools and connections → Tools, or this chat's "
                + "Chat options → Tools). Do the task without it if you can; otherwise tell them it needs that tool.";
    }
}
