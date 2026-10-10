package app.buddy.assistant;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Chats shared by the app screens and the bubble. Runs on the main thread. */
final class ChatHub {
    interface Listener {
        void onChatsChanged();

        void onMessage(Sessions.Session ses, Sessions.Msg msg);
    }

    private static final long NO_RESPONSE_MS = 30000;
    private static final String TOOL_PREFIX = "mcp__phone__";
    private static ChatHub instance;

    final Sessions sessions;
    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new ArrayList<>();
    private final List<Sessions.Session> viewing = new ArrayList<>();
    private String flash;
    /** Receives buddy_procs / buddy_recent / buddy_transcript replies for the monitor screens. */
    interface MonitorListener {
        void onMonitor(JSONObject ev);
    }

    MonitorListener monitor;

    void onMonitorEvent(JSONObject ev) {
        if (monitor != null) monitor.onMonitor(ev);
    }

    /** Turn an existing Claude Code / Codex session into a Buddy chat that continues it. */
    Sessions.Session adopt(String backend, String agentSessionId, String title) {
        Sessions.Session s = sessions.create(backend);
        s.title = title == null ? "" : title;
        try {
            Helper.run(app, "adopt", s.id, backend, agentSessionId);
        } catch (Exception e) {
            add(s, Bubble.Kind.ERROR, e.getMessage());
        }
        add(s, Bubble.Kind.STEP, "Continuing an earlier " + s.brainName() + " session. If it's still open in a terminal, close it there first.");
        changed();
        return s;
    }

    static synchronized ChatHub get(Context c) {
        if (instance == null) instance = new ChatHub(c.getApplicationContext());
        return instance;
    }

    private ChatHub(Context app) {
        this.app = app;
        sessions = new Sessions(app);
    }

    void addListener(Listener l) {
        if (!listeners.contains(l)) listeners.add(l);
    }

    void removeListener(Listener l) {
        listeners.remove(l);
    }

    void setViewing(Sessions.Session ses, boolean on) {
        if (on) {
            viewing.add(ses);
            ses.unread = false;
        } else {
            viewing.remove(ses);
        }
        changed();
    }

    Sessions.Session create(String backend) {
        Sessions.Session s = sessions.create(backend);
        changed();
        return s;
    }

    void select(Sessions.Session s) {
        sessions.select(s);
        changed();
    }

    void delete(Sessions.Session s) {
        try {
            Helper.run(app, "delete", s.id);
        } catch (Exception ignored) {
        }
        viewing.remove(s);
        sessions.remove(s);
        changed();
    }

    /** Sends a message. Returns an error text, or null when sent. */
    String send(final Sessions.Session ses, String text) {
        return send(ses, text, null);
    }

    /** Sends a message with attachments (ids from Attachments); the helper fetches them before the agent runs. */
    String send(final Sessions.Session ses, String text, List<String> files) {
        if (ses.running) return "This chat is still working. Tap Stop, or use another chat.";
        boolean hasFiles = files != null && !files.isEmpty();
        if (text.isEmpty() && !hasFiles) return null;
        String forAgent = text.isEmpty() && hasFiles ? "Have a look at what I attached." : text;
        BuddyService svc = BuddyService.get();
        if (svc != null) svc.allowControl();
        try {
            Helper.run(app, "ask", ses.id, ses.backend, Prefs.devMode(app) ? "1" : "0", forAgent, ses.model,
                    hasFiles ? android.text.TextUtils.join(",", files) : "");
        } catch (Exception e) {
            return e.getMessage();
        }
        sessions.add(ses, Bubble.Kind.USER, text, hasFiles ? new ArrayList<>(files) : null);
        Sessions.Msg um = ses.messages.get(ses.messages.size() - 1);
        for (Listener l : new ArrayList<>(listeners)) l.onMessage(ses, um);
        startRun(ses);
        ses.status = "Thinking…";
        changed();
        final int id = ses.runId;
        main.postDelayed(() -> {
            if (ses.running && id == ses.runId && SystemClock.elapsedRealtime() - ses.lastEventAt >= NO_RESPONSE_MS - 100) {
                ses.running = false;
                add(ses, Bubble.Kind.ERROR, "No response from the agent. Open Buddy's Settings and check that it's installed and signed in.");
                changed();
            }
        }, NO_RESPONSE_MS);
        return null;
    }

    /** Change a chat's model; the next message uses it, and the conversation continues. */
    void setModel(Sessions.Session ses, String model) {
        ses.model = model == null ? "" : model.trim();
        add(ses, Bubble.Kind.STEP, "Model: " + Models.label(ses.backend, ses.model));
        changed();
    }

    /** Stop every chat that is working (the bubble's stop button). */
    void stopAll() {
        for (Sessions.Session s : new java.util.ArrayList<>(sessions.all)) if (s.running) stop(s);
    }

    void stop(Sessions.Session ses) {
        if (!ses.running) return;
        try {
            Helper.run(app, "stop", ses.id);
            ses.status = "Stopping…";
        } catch (Exception e) {
            add(ses, Bubble.Kind.ERROR, e.getMessage());
        }
        changed();
    }

    void note(Sessions.Session ses, Bubble.Kind kind, String text) {
        add(ses, kind, text);
        changed();
    }

    /** Called for any phone-tool use; keeps running chats from timing out. */
    void onToolActivity() {
        for (Sessions.Session s : sessions.all) if (s.running) s.lastEventAt = SystemClock.elapsedRealtime();
    }

    void onEvent(JSONObject ev) {
        Sessions.Session ses = sessions.find(ev.optString("buddy_session", null));
        if (ses == null) return; // unknown chat (deleted, or started outside Buddy)
        ses.lastEventAt = SystemClock.elapsedRealtime();
        String type = ev.optString("type");
        if (!ses.running && ("system".equals(type) || "assistant".equals(type))) startRun(ses);
        switch (type) {
            case "system":
                if ("init".equals(ev.optString("subtype"))) ses.status = "Thinking…";
                break;
            case "assistant": {
                JSONObject msg = ev.optJSONObject("message");
                JSONArray content = msg == null ? null : msg.optJSONArray("content");
                if (content == null) break;
                for (int i = 0; i < content.length(); i++) {
                    JSONObject c = content.optJSONObject(i);
                    if (c == null) continue;
                    if ("text".equals(c.optString("type"))) {
                        String t = c.optString("text").trim();
                        if (!t.isEmpty()) {
                            add(ses, Bubble.Kind.BUDDY, t);
                            ses.lastReply = t;
                            ses.gotText = true;
                        }
                    } else if ("tool_use".equals(c.optString("type"))) {
                        String step = describeTool(c.optString("name"), c.optJSONObject("input"));
                        if (step != null) {
                            add(ses, Bubble.Kind.STEP, step);
                            ses.status = step;
                        }
                    }
                }
                break;
            }
            case "result": {
                boolean error = ev.optBoolean("is_error") || !"success".equals(ev.optString("subtype", "success"));
                String r = ev.optString("result", "").trim();
                if (error) add(ses, Bubble.Kind.ERROR, r.isEmpty() ? ses.brainName() + " stopped." : r);
                else if (!ses.gotText && !r.isEmpty()) {
                    add(ses, Bubble.Kind.BUDDY, r);
                    ses.lastReply = r;
                }
                break;
            }
            case "buddy_error":
                add(ses, Bubble.Kind.ERROR, ev.optString("text", "Something went wrong."));
                break;
            case "buddy_stopped":
                add(ses, Bubble.Kind.STEP, "Stopped.");
                break;
            case "buddy_done":
                if (ses.running) {
                    ses.running = false;
                    ses.status = "";
                    if (!ses.lastReply.isEmpty()) {
                        Prefs.setHadReply(app);
                        flash(ses.lastReply);
                    }
                }
                break;
            default:
                break;
        }
        changed();
    }

    // ---------------------------------------------------------------- state

    int runningCount() {
        return sessions.runningCount();
    }

    /** Text for the bubble's status pill, or null for none. */
    String pillText() {
        int n = runningCount();
        if (n > 0) {
            Sessions.Session latest = null;
            for (Sessions.Session s : sessions.all)
                if (s.running && (latest == null || s.lastEventAt > latest.lastEventAt)) latest = s;
            String st = latest == null || latest.status.isEmpty() ? "Working…" : latest.status;
            return n > 1 ? n + " chats · " + st : st;
        }
        return flash;
    }

    boolean anyUnread() {
        return sessions.anyUnread();
    }

    private void flash(String reply) {
        final String text = reply.replace('\n', ' ');
        flash = text.length() > 60 ? text.substring(0, 59) + "…" : text;
        final String mine = flash;
        main.postDelayed(() -> {
            if (mine.equals(flash)) {
                flash = null;
                changed();
            }
        }, 8000);
    }

    private void startRun(Sessions.Session ses) {
        ses.running = true;
        ses.gotText = false;
        ses.lastReply = "";
        ses.lastEventAt = SystemClock.elapsedRealtime();
        ses.runId++;
    }

    private void add(Sessions.Session ses, Bubble.Kind kind, String text) {
        if (text == null) return;
        sessions.add(ses, kind, text);
        if (kind == Bubble.Kind.BUDDY && !viewing.contains(ses)) ses.unread = true;
        if (kind == Bubble.Kind.BUDDY && Prefs.speakReplies(app)) Voice.speak(app, text);
        Sessions.Msg m = ses.messages.get(ses.messages.size() - 1);
        for (Listener l : new ArrayList<>(listeners)) l.onMessage(ses, m);
    }

    private boolean wasRunning;
    private long lastRunEndedAt;

    /** When the last running chat finished (elapsedRealtime). */
    long lastRunEndedAt() {
        return lastRunEndedAt;
    }

    void changed() {
        boolean now = runningCount() > 0;
        if (wasRunning && !now) lastRunEndedAt = SystemClock.elapsedRealtime();
        wasRunning = now;
        for (Listener l : new ArrayList<>(listeners)) l.onChatsChanged();
    }

    static String describeTool(String name, JSONObject in) {
        if (in == null) in = new JSONObject();
        String n = name.startsWith(TOOL_PREFIX) ? name.substring(TOOL_PREFIX.length()) : name;
        switch (n) {
            case "read_screen": return "Reading the screen";
            case "screenshot": return "Taking a screenshot";
            case "tap": return in.has("element") ? "Tapping element " + in.optInt("element") : "Tapping " + in.optInt("x") + "," + in.optInt("y");
            case "long_press": return "Long-pressing";
            case "type_text": return "Typing \"" + clip(in.optString("text"), 40) + "\"";
            case "scroll": return "Scrolling " + in.optString("direction");
            case "swipe": return "Swiping";
            case "press": return "Pressing " + in.optString("button");
            case "open_app": return "Opening " + in.optString("app");
            case "list_apps": return "Listing apps";
            case "open_url": return "Opening " + clip(in.optString("url"), 40);
            case "wait":
            case "ToolSearch":
            case "TodoWrite":
                return null;
            case "confirm": return "Asking for your approval";
            case "notify": return in.optString("text");
            case "WebSearch": return "Searching the web: " + clip(in.optString("query"), 40);
            case "WebFetch": return "Reading " + clip(in.optString("url"), 40);
            case "Bash": return in.has("command") ? "Running: " + clip(in.optString("command"), 40) : "Running a command";
            case "Edit":
            case "Write": return in.has("file_path") ? "Editing " + clip(in.optString("file_path").replaceAll(".*/", ""), 40) : "Editing files";
            case "Read": return "Reading " + clip(in.optString("file_path").replaceAll(".*/", ""), 40);
            default: return "Using " + n;
        }
    }

    static String clip(String s, int n) {
        s = s.replace('\n', ' ');
        return s.length() > n ? s.substring(0, n - 1) + "…" : s;
    }
}
