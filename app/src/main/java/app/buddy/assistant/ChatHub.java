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
    /** After this long with nothing from the agent, the run is given up on. */
    private static final long GIVE_UP_MS = 180000;
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
        add(s, Bubble.Kind.STEP, app.getString(R.string.hub_continuing, s.brainName()));
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
        // a chat still marked working when Buddy starts was cut off: Android closed Buddy (an update, low memory), and
        // its agent with it
        for (String id : Prefs.busyChats(app)) {
            Sessions.Session s = sessions.find(id);
            Prefs.setBusy(app, id, false);
            if (s == null) continue;
            s.interrupted = true;
            sessions.add(s, Bubble.Kind.STEP, app.getString(R.string.hub_interrupted), null);
        }
        // incognito chats don't outlive Buddy: they go, with what their agent kept of them
        for (Sessions.Session s : new ArrayList<>(sessions.all)) {
            if (!s.incognito) continue;
            sessions.remove(s);
            if (Linux.installed(app)) {
                try {
                    Helper.run(app, "delete", s.id, "forget");
                } catch (Exception ignored) {
                }
            }
        }
    }

    /** After a cut-off run: the agent checks what's done and goes on. */
    String resume(Sessions.Session ses) {
        return send(ses, app.getString(R.string.hub_resume_message), null, "(From Buddy, not the user) Android closed Buddy "
                + "in the middle of your last task, and stopped you with it. Check what is already done (the screen, the "
                + "files, the conversation so far), then carry on from where you stopped. Don't redo steps that already "
                + "happened, such as sending a message again.", false, false);
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
        return create(backend, true);
    }

    /** A new chat; select: make it the one the bubble shows (a rule's chat doesn't take over the bubble). */
    Sessions.Session create(String backend, boolean select) {
        Sessions.Session s = sessions.create(backend, select);
        changed();
        return s;
    }

    /** A new chat from a New chat sheet, with Linux tools as its switch says (the others get the Settings default). */
    Sessions.Session create(String backend, boolean select, boolean dev) {
        Sessions.Session s = sessions.create(backend, select, dev);
        changed();
        return s;
    }

    void select(Sessions.Session s) {
        sessions.select(s);
        changed();
    }

    void delete(Sessions.Session s) {
        if (CodexLive.on(s)) CodexLive.end(null);
        try {
            Helper.run(app, "delete", s.id);
        } catch (Exception ignored) {
        }
        viewing.remove(s);
        sessions.remove(s);
        Schedules.cancelAll(app, s);
        changed();
    }

    /** Sends a message. Returns an error text, or null when sent. */
    String send(final Sessions.Session ses, String text) {
        return send(ses, text, null);
    }

    /** Sends a message with attachments (ids from Attachments); the helper fetches them before the agent runs. */
    String send(final Sessions.Session ses, String text, List<String> files) {
        String skill = ses.running ? null : Skills.prompt(app, ses.backend, text); // "/name …": a skill picked with /
        if (skill != null) return send(ses, text, files, skill, false, false);
        return send(ses, text, files, null);
    }

    /** A notification rule's run (see Triggers): the chat shows a short line, the agent gets the whole prompt. */
    String sendFromRule(Sessions.Session ses, String shown, String prompt) {
        return send(ses, shown, null, prompt, false);
    }

    /**
     * A scheduled task's run (see Schedules): the user's saved message, shown as theirs and marked "Scheduled", with
     * the chat's own settings (Linux tools included). The agent gets it with a line saying when and why it came.
     */
    String sendScheduled(Sessions.Session ses, String text, String prompt) {
        if (ses.running) return app.getString(R.string.schedule_skip_running);
        return send(ses, text, null, prompt, true);
    }

    private String send(final Sessions.Session ses, String text, List<String> files, String prompt) {
        return send(ses, text, files, prompt, false);
    }

    private String send(final Sessions.Session ses, String text, List<String> files, String prompt, boolean scheduled) {
        return send(ses, text, files, prompt, scheduled, prompt != null && !scheduled);
    }

    /** rule: a notification rule's run (someone else's message): no shell, files or web. */
    private String send(final Sessions.Session ses, String text, List<String> files, String prompt, boolean scheduled,
                        boolean rule) {
        if (ses.running) {
            if (files != null && !files.isEmpty()) return app.getString(R.string.hub_still_working_attachments);
            return addNote(ses, text);
        }
        boolean hasFiles = files != null && !files.isEmpty();
        if (text.isEmpty() && !hasFiles) return null;
        String forAgent = prompt != null ? prompt : text.isEmpty() && hasFiles ? "Have a look at what I attached." : text;
        BuddyService svc = BuddyService.get();
        // only the user's own message lifts a Stop: a queued rule run must not undo it
        if (svc != null && prompt == null) svc.allowControl();
        try {
            // a rule's run works for someone else's message: no shell, files or web, even with Linux tools on
            Helper.run(app, "ask", ses.id, ses.backend, rule ? "rule" : devArg(ses), forAgent,
                    model(ses), hasFiles ? android.text.TextUtils.join(",", files) : "", ses.effort);
        } catch (Exception e) {
            return e.getMessage();
        }
        ses.interrupted = false;
        sessions.add(ses, Bubble.Kind.USER, text, hasFiles ? new ArrayList<>(files) : null);
        Sessions.Msg um = ses.messages.get(ses.messages.size() - 1);
        if (scheduled) {
            um.scheduled = true;
            sessions.save();
        }
        for (Listener l : new ArrayList<>(listeners)) l.onMessage(ses, um);
        startRun(ses);
        ses.scheduledRun = scheduled;
        ses.status = app.getString(R.string.hub_thinking);
        changed();
        final int id = ses.runId;
        // the agent is running but its model can be slow (free models especially): say so, keep waiting
        main.postDelayed(() -> {
            if (ses.running && id == ses.runId && SystemClock.elapsedRealtime() - ses.lastEventAt >= NO_RESPONSE_MS - 100) {
                add(ses, Bubble.Kind.STEP, app.getString(R.string.hub_still_waiting));
                changed();
            }
        }, NO_RESPONSE_MS);
        main.postDelayed(() -> {
            if (ses.running && id == ses.runId && SystemClock.elapsedRealtime() - ses.lastEventAt >= GIVE_UP_MS - 100) {
                ses.running = false;
                String err = lastError(ses);
                add(ses, Bubble.Kind.ERROR, app.getString(R.string.hub_no_answer) + (err.isEmpty() ? "" : "\n\n"
                        + ses.brainName()
                        + ": " + err));
                ses.outcome = "failed";
                runEnded(ses);
                changed();
            }
        }, GIVE_UP_MS);
        return null;
    }

    private static long read(JSONObject modelUsage) {
        return modelUsage.optLong("inputTokens") + modelUsage.optLong("cacheReadInputTokens");
    }

    /** The end of what the agent wrote to stderr this run (buddy-run keeps it), or "": a hung agent often says why there. */
    private String lastError(Sessions.Session ses) {
        java.io.File f = new java.io.File(Linux.root(app), "root/.buddy/sessions/" + ses.id + "/last-error.log");
        try (java.io.RandomAccessFile r = new java.io.RandomAccessFile(f, "r")) {
            long n = Math.min(r.length(), 300);
            byte[] b = new byte[(int) n];
            r.seek(r.length() - n);
            r.readFully(b);
            return new String(b, java.nio.charset.StandardCharsets.UTF_8).trim();
        } catch (Exception e) {
            return "";
        }
    }

    /** Change a chat's model; the next message uses it, and the conversation continues. */
    void setModel(Sessions.Session ses, String model) {
        ses.model = model == null ? "" : model.trim();
        add(ses, Bubble.Kind.STEP, app.getString(R.string.hub_model_set, Models.label(ses.backend, ses.model)));
        changed();
    }

    /** Change a chat's effort ("" = the agent's default); the next message uses it. */
    void setEffort(Sessions.Session ses, String effort) {
        ses.effort = effort == null ? "" : effort;
        add(ses, Bubble.Kind.STEP, ses.effort.isEmpty() ? app.getString(R.string.hub_effort_default)
                : app.getString(R.string.hub_effort_set, ses.effort));
        changed();
    }

    /** Turn a chat's Linux tools on or off; the next message uses them (a running one keeps what it started with). */
    void setDev(Sessions.Session ses, boolean on) {
        ses.dev = on;
        sessions.save();
        changed();
    }

    /**
     * Set or remove (null) a chat's scheduled task, and its alarm with it. Removing it also ends the "Allowed without
     * asking" apps kept for it (a running chat keeps them until its run ends, as any chat).
     */
    void setSchedule(Sessions.Session ses, Schedules.Plan plan) {
        ses.schedule = plan;
        if (plan == null && !ses.running) ses.allowed.clear();
        sessions.save();
        Schedules.set(app, ses);
        changed();
    }

    /** Long-task mode for a chat: the screen stays on while it works (see KeepAwake); a running one gets it at once. */
    void setIncognito(Sessions.Session ses, boolean on) {
        ses.incognito = on;
        sessions.save();
        changed();
    }

    void setAwake(Sessions.Session ses, boolean on) {
        ses.awake = on;
        sessions.save();
        changed();
    }

    /**
     * Add or take away an app from a chat's "Allowed without asking" (until its run ends, see Guard.runRule; with a
     * schedule, kept for its scheduled runs).
     */
    void setAllowed(Sessions.Session ses, String pkg, boolean on) {
        if (on) ses.allowed.add(pkg);
        else ses.allowed.remove(pkg);
        if (ses.schedule != null) sessions.save();
        changed();
    }

    /** The calling chat's "Allowed without asking" apps (any thread); empty for a session outside Buddy's chats. */
    java.util.Set<String> allowedFor(String chat) {
        if (chat == null) return java.util.Collections.emptySet();
        for (Sessions.Session s : new ArrayList<>(sessions.all)) {
            if (!s.id.equals(chat)) continue;
            synchronized (s.allowed) {
                return new java.util.HashSet<>(s.allowed);
            }
        }
        return java.util.Collections.emptySet();
    }

    /**
     * Whether the agent behind a phone tool call has Linux tools (any thread): its chat's own, or for the user's own
     * sessions outside Buddy's chats (no chat), the Settings default.
     */
    boolean devFor(String chat) {
        if (chat == null) return Prefs.devMode(app);
        for (Sessions.Session s : new ArrayList<>(sessions.all)) if (s.id.equals(chat)) return s.dev;
        return false;
    }

    /** Stop every chat that is working (the bubble's stop button). */
    void stopAll() {
        for (Sessions.Session s : new java.util.ArrayList<>(sessions.all)) if (s.running) stop(s);
    }

    void stop(Sessions.Session ses) {
        if (!ses.running) return;
        BuddyService svc = BuddyService.get();
        if (svc != null) svc.stopPaused();
        try {
            Helper.run(app, "stop", ses.id);
            // set now: the run's own buddy_done can arrive before buddy_stopped, and must not send the notes
            ses.outcome = "stopped";
            ses.status = app.getString(R.string.hub_stopping);
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
        if (!ses.running && (("system".equals(type) && !"model".equals(ev.optString("subtype"))) || "assistant".equals(type))) startRun(ses);
        switch (type) {
            case "system":
                if ("model".equals(ev.optString("subtype"))) { // Codex, after its turn (see buddy-run)
                    String m = ev.optString("model", "");
                    if (!m.isEmpty() && !m.equals(ses.actualModel)) {
                        ses.actualModel = m;
                        sessions.save();
                        changed();
                    }
                } else if ("status".equals(ev.optString("subtype"))) { // Claude Code: compacting the conversation
                    if ("compacting".equals(ev.optString("status"))) ses.status = app.getString(R.string.hub_compacting);
                } else if ("compact_boundary".equals(ev.optString("subtype"))) {
                    JSONObject cm = ev.optJSONObject("compact_metadata");
                    if (cm != null && cm.optLong("post_tokens") > 0) {
                        ses.ctxUsed = cm.optLong("post_tokens");
                        add(ses, Bubble.Kind.STEP, app.getString(R.string.hub_compacted,
                                Models.tokens(cm.optLong("pre_tokens")), Models.tokens(ses.ctxUsed)));
                    }
                } else if ("init".equals(ev.optString("subtype"))) {
                    ses.status = app.getString(R.string.hub_thinking);
                    // the model really in use (Claude Code says it here), shown when the chat is on "Default"
                    String m = ev.optString("model", "");
                    if (!m.isEmpty() && !m.equals(ses.actualModel)) {
                        ses.actualModel = m;
                        sessions.save();
                    }
                }
                break;
            case "assistant": {
                JSONObject msg = ev.optJSONObject("message");
                JSONObject u = msg == null ? null : msg.optJSONObject("usage");
                // Claude Code: what this request sent and got is how full the context is now (not a subagent's)
                if (u != null && ev.isNull("parent_tool_use_id")) {
                    long used = u.optLong("input_tokens") + u.optLong("cache_creation_input_tokens")
                            + u.optLong("cache_read_input_tokens") + u.optLong("output_tokens");
                    if (used > 0) ses.ctxUsed = used;
                }
                JSONArray content = msg == null ? null : msg.optJSONArray("content");
                if (content == null) break;
                for (int i = 0; i < content.length(); i++) {
                    JSONObject c = content.optJSONObject(i);
                    if (c == null) continue;
                    if ("text".equals(c.optString("type"))) {
                        String t = c.optString("text").trim();
                        if (!t.isEmpty()) {
                            add(ses, Bubble.Kind.BUDDY, t, pictures(t));
                            ses.lastReply = t;
                            ses.gotText = true;
                            CodexLive.agentSaid(ses, t); // a live voice call in this chat tells the user
                        }
                    } else if ("tool_use".equals(c.optString("type"))) {
                        String step = describeTool(c.optString("name"), c.optJSONObject("input"));
                        if (step != null) {
                            add(ses, Bubble.Kind.STEP, step);
                            ses.status = step;
                            String n = c.optString("name");
                            if (n.equals(TOOL_PREFIX + "confirm") || n.equals(TOOL_PREFIX + "ask")) CodexLive.agentWaiting(ses, step, c.optJSONObject("input"));
                        }
                    }
                }
                break;
            }
            case "result": {
                // Claude Code: the context size it works with for the chat's model (its own idea of an unknown one)
                JSONObject mu = ev.optJSONObject("modelUsage");
                if (mu != null) {
                    JSONObject m = mu.optJSONObject(ses.actualModel);
                    if (m == null) { // otherwise the one that did most of the reading (a helper model reads little)
                        for (java.util.Iterator<String> it = mu.keys(); it.hasNext(); ) {
                            JSONObject o = mu.optJSONObject(it.next());
                            if (o != null && (m == null || read(o) > read(m))) m = o;
                        }
                    }
                    if (m != null && m.optLong("contextWindow") > 0) ses.ctxWindow = m.optLong("contextWindow");
                    sessions.save();
                }
                boolean error = ev.optBoolean("is_error") || !"success".equals(ev.optString("subtype", "success"));
                String r = ev.optString("result", "").trim();
                if (error && ses.outcome == null) ses.outcome = "failed";
                if (error) {
                    String shown = r.isEmpty() ? app.getString(R.string.hub_agent_stopped, ses.brainName()) : r;
                    add(ses, Bubble.Kind.ERROR, shown);
                    CodexLive.agentSaid(ses, shown);
                } else if (!ses.gotText && !r.isEmpty()) {
                    add(ses, Bubble.Kind.BUDDY, r, pictures(r));
                    ses.lastReply = r;
                    CodexLive.agentSaid(ses, r);
                }
                break;
            }
            case "buddy_error": {
                if (ses.outcome == null) ses.outcome = "failed";
                String text = ev.optString("text", app.getString(R.string.hub_something_wrong));
                add(ses, Bubble.Kind.ERROR, text);
                CodexLive.agentSaid(ses, text);
                break;
            }
            case "buddy_stopped":
                ses.outcome = "stopped";
                add(ses, Bubble.Kind.STEP, app.getString(R.string.hub_stopped));
                break;
            case "rate_limit_event": { // Claude Code, Codex: how much of the plan's 5-hour and weekly limits is used
                String warning = Usage.onEvent(app, ses.backend, ev);
                if (warning != null) add(ses, Bubble.Kind.STEP, warning);
                break;
            }
            case "buddy_context": // Codex, after its turn (see buddy-run): how full the context is, and its size
                if (ev.optLong("used") > 0) ses.ctxUsed = ev.optLong("used");
                if (ev.optLong("window") > 0) ses.ctxWindow = ev.optLong("window");
                sessions.save();
                break;
            case "buddy_step": // a line from the helper under the steps (Antigravity: a refused step)
                add(ses, Bubble.Kind.STEP, ev.optString("text"));
                break;
            case "buddy_done":
                if (ses.running) {
                    ses.running = false;
                    ses.status = "";
                    runEnded(ses);
                    if (!ses.lastReply.isEmpty()) {
                        Prefs.setHadReply(app);
                        flash(ses.lastReply);
                    }
                    // what the user added but the agent never got (it made no phone action since): send it now
                    String left;
                    synchronized (ses.notes) {
                        left = ses.notes.isEmpty() ? null : android.text.TextUtils.join("\n", ses.notes);
                        ses.notes.clear();
                    }
                    if (left != null && "stopped".equals(ses.outcome)) {
                        // Stop means stop: no new run; the notes go back into the chat's message box
                        ses.unsent = ses.unsent == null ? left : ses.unsent + "\n" + left;
                        add(ses, Bubble.Kind.STEP, app.getString(R.string.hub_note_not_sent));
                    } else if (left != null) {
                        final String msg = left;
                        main.post(() -> {
                            if (!ses.running) sendNoteAsMessage(ses, msg);
                        });
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

    /**
     * For the smart reviewer, from the chat that's working (call on the main thread): {agent backend, the
     * user's latest request, the agent's latest note}, or null when no chat is running (an outside agent).
     */
    String[] reviewContext() {
        Sessions.Session latest = null;
        for (Sessions.Session s : sessions.all)
            if (s.running && (latest == null || s.lastEventAt > latest.lastEventAt)) latest = s;
        if (latest == null) return null;
        String request = "", note = "";
        for (int i = latest.messages.size() - 1; i >= 0; i--) {
            Sessions.Msg m = latest.messages.get(i);
            if (note.isEmpty() && (m.kind == Bubble.Kind.BUDDY || m.kind == Bubble.Kind.STEP)) note = m.text;
            if (m.kind == Bubble.Kind.USER) {
                request = m.text;
                break;
            }
        }
        if (!latest.lastReply.isEmpty() && note.isEmpty()) note = latest.lastReply;
        return new String[]{latest.backend, request, note};
    }

    /**
     * Something the user says while the chat works (typed or dictated): shown in the chat now, passed to the
     * agent with its next phone action (see takeNotes), or sent as the next message if the task ends first.
     */
    String addNote(Sessions.Session ses, String text) {
        text = text == null ? "" : text.trim();
        if (text.isEmpty()) return null;
        if (!ses.running) return send(ses, text, null);
        // writing to the agent while paused in the browser's live view: the user is done there
        BuddyService svc = BuddyService.get();
        if (svc != null && svc.pausedForBrowser()) svc.resume();
        synchronized (ses.notes) {
            ses.notes.add(text);
        }
        add(ses, Bubble.Kind.USER, text);
        add(ses, Bubble.Kind.STEP, app.getString(R.string.hub_note_added, ses.brainName()));
        changed();
        return null;
    }

    /**
     * The model to run. OpenCode's own default free model can take over 30 seconds to answer, so without
     * connected providers Buddy uses Big Pickle, which answers in seconds.
     */
    /** The helper's mode for a chat's run: "1" with Linux tools, "0" without. */
    private static String devArg(Sessions.Session ses) {
        return ses.dev ? "1" : "0";
    }

    private static String model(Sessions.Session ses) {
        String eff = Models.effective(ses); // the chat's pick, or a service the agent uses by default
        if (!eff.isEmpty() || !"opencode".equals(ses.backend)) return eff;
        String plan = Account.OPENCODE.plan;
        return plan.isEmpty() || plan.equals("free models") ? "opencode/big-pickle" : "";
    }

    /** A note that arrived too late for the run: becomes the next message (already shown in the chat). */
    private void sendNoteAsMessage(Sessions.Session ses, String text) {
        try {
            Helper.run(app, "ask", ses.id, ses.backend, devArg(ses),
                    "(Added while you were working:) " + text, model(ses), "", ses.effort);
        } catch (Exception e) {
            add(ses, Bubble.Kind.ERROR, e.getMessage());
            return;
        }
        startRun(ses);
        ses.status = app.getString(R.string.hub_thinking);
        changed();
    }

    /** The user dismissed the offer to continue a cut-off run. */
    void dismissInterrupted(Sessions.Session ses) {
        ses.interrupted = false;
        sessions.save();
        changed();
    }

    /** Notes the user added before stopping this chat, for its message box; taken once (null when none). */
    String takeUnsent(Sessions.Session ses) {
        String text = ses.unsent;
        ses.unsent = null;
        return text;
    }

    /** The newest working chat, or null. */
    Sessions.Session latestRunning() {
        Sessions.Session latest = null;
        for (Sessions.Session s : sessions.all)
            if (s.running && (latest == null || s.lastEventAt > latest.lastEventAt)) latest = s;
        return latest;
    }

    /**
     * Notes for the agent that's working, to attach to a phone tool's result (any thread). Taken once: null
     * when there are none.
     */
    String takeNotes() {
        for (Sessions.Session s : new ArrayList<>(sessions.all)) {
            if (!s.running) continue;
            String joined;
            synchronized (s.notes) {
                if (s.notes.isEmpty()) continue;
                joined = android.text.TextUtils.join("\n", s.notes);
                s.notes.clear();
            }
            main.post(() -> {
                add(s, Bubble.Kind.STEP, app.getString(R.string.hub_note_passed));
                changed();
            });
            return joined;
        }
        return null;
    }

    /** Text for the bubble's status pill, or null for none. */
    String pillText() {
        int n = runningCount();
        if (n > 0) {
            Sessions.Session latest = null;
            for (Sessions.Session s : sessions.all)
                if (s.running && (latest == null || s.lastEventAt > latest.lastEventAt)) latest = s;
            String st = latest == null || latest.status.isEmpty() ? app.getString(R.string.chat_working) : latest.status;
            return n > 1 ? app.getResources().getQuantityString(R.plurals.hub_pill_chats, n, n, st) : st;
        }
        return flash;
    }

    boolean anyUnread() {
        return sessions.anyUnread();
    }

    private void flash(String reply) {
        final String text = Ui.linkText(reply).replace('\n', ' ');
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
        ses.outcome = null;
        ses.gotText = false;
        ses.lastReply = "";
        ses.lastEventAt = SystemClock.elapsedRealtime();
        ses.runId++;
        Tools.runStarted(ses.id);
        Prefs.setBusy(app, ses.id, true);
    }

    /**
     * A chat's run is over (finished, stopped or failed): what was allowed without asking ends with it, and in
     * long-task mode or a scheduled task's run (nobody may be watching) a notification says how it went.
     */
    private void runEnded(Sessions.Session ses) {
        Prefs.setBusy(app, ses.id, false);
        if (ses.schedule == null) ses.allowed.clear(); // a scheduled chat keeps them for its next scheduled run
        CodexLive.agentDone(ses, ses.outcome, !ses.lastReply.isEmpty());
        if (ses.awake || ses.scheduledRun) KeepAwake.notifyEnded(app, ses);
        ses.scheduledRun = false;
    }

    private void add(Sessions.Session ses, Bubble.Kind kind, String text) {
        add(ses, kind, text, null);
    }

    private void add(Sessions.Session ses, Bubble.Kind kind, String text, List<String> files) {
        if (text == null) return;
        sessions.add(ses, kind, text, files);
        if (kind == Bubble.Kind.BUDDY && !viewing.contains(ses)) ses.unread = true;
        // not during a live voice call: the voice tells the user itself
        if (kind == Bubble.Kind.BUDDY && Prefs.speakReplies(app) && !CodexLive.on()) Voice.speak(app, text);
        Sessions.Msg m = ses.messages.get(ses.messages.size() - 1);
        for (Listener l : new ArrayList<>(listeners)) l.onMessage(ses, m);
    }

    /** A picture's path in Buddy's Linux, as agents write it: /root/.buddy/work/chart.png, ~/out/photo.jpg. */
    private static final java.util.regex.Pattern PICTURE = java.util.regex.Pattern.compile(
            "(?<![\\w/.~-])(~|/(?:root|tmp|home|srv|opt|var|mnt|media))(/[^\\s`'\"()\\[\\]<>|*]+?\\.(?i:png|jpe?g|webp|gif))(?![\\w/])");

    /**
     * Pictures a reply names in Buddy's Linux (a chart the agent drew, a photo it edited), copied to the attachments so
     * the chat shows them under it; at most 4. Only files inside Buddy's Linux, never through a link out of it.
     */
    private List<String> pictures(String text) {
        List<String> out = new ArrayList<>();
        java.util.regex.Matcher m = PICTURE.matcher(text);
        if (!m.find() || !Linux.installed(app)) return out;
        java.util.Set<String> seen = new java.util.HashSet<>();
        do {
            String path = ("~".equals(m.group(1)) ? "/root" : m.group(1)) + m.group(2);
            if (!seen.add(path)) continue;
            try {
                java.io.File root = Linux.root(app).getCanonicalFile(), f = new java.io.File(root, path).getCanonicalFile();
                if (!f.getPath().startsWith(root.getPath() + "/") || !f.isFile() || f.length() > Attachments.MAX_BYTES) continue;
                android.graphics.BitmapFactory.Options b = new android.graphics.BitmapFactory.Options();
                b.inJustDecodeBounds = true;
                android.graphics.BitmapFactory.decodeFile(f.getPath(), b);
                if (b.outWidth > 0) out.add(Attachments.importFile(app, f));
            } catch (Exception ignored) { // not there (yet), or unreadable: the path stays in the text
            }
        } while (out.size() < 4 && m.find());
        return out;
    }

    private boolean wasRunning;
    private long lastRunEndedAt;

    /** When the last running chat finished (elapsedRealtime). */
    long lastRunEndedAt() {
        return lastRunEndedAt;
    }

    void changed() {
        CodexLive.refresh(); // a live call's status follows the chat (Working on it…)
        boolean now = runningCount() > 0;
        if (wasRunning && !now) {
            lastRunEndedAt = SystemClock.elapsedRealtime();
            BuddyService svc = BuddyService.get();
            if (svc != null) svc.onRunsEnded();
        }
        wasRunning = now;
        BuddyService svc = BuddyService.get();
        if (svc != null) svc.keepAwake().update(); // a long-task chat started, ended or changed its switch
        for (Listener l : new ArrayList<>(listeners)) l.onChatsChanged();
    }

    static String describeTool(String name, JSONObject in) {
        if (in == null) in = new JSONObject();
        String n = name.startsWith(TOOL_PREFIX) ? name.substring(TOOL_PREFIX.length()) : name;
        switch (n) {
            case "read_screen": return BuddyApp.str(R.string.hub_tool_read_screen);
            case "screenshot": return BuddyApp.str(R.string.hub_tool_screenshot);
            case "tap": return in.has("element") ? BuddyApp.str(R.string.hub_tool_tap_element, in.optInt("element"))
                    : BuddyApp.str(R.string.hub_tool_tap_point, in.optInt("x"), in.optInt("y"));
            case "long_press": return BuddyApp.str(R.string.hub_tool_long_press);
            case "type_text": return BuddyApp.str(R.string.hub_tool_type, clip(in.optString("text"), 40));
            case "scroll": {
                String d = in.optString("direction"); // the tool's enum values get whole translated lines
                switch (d) {
                    case "up": return BuddyApp.str(R.string.hub_tool_scroll_up);
                    case "down": return BuddyApp.str(R.string.hub_tool_scroll_down);
                    case "left": return BuddyApp.str(R.string.hub_tool_scroll_left);
                    case "right": return BuddyApp.str(R.string.hub_tool_scroll_right);
                    default: return BuddyApp.str(R.string.hub_tool_scroll, d);
                }
            }
            case "swipe": return BuddyApp.str(R.string.hub_tool_swipe);
            case "press": {
                String b = in.optString("button"); // as for scroll
                switch (b) {
                    case "back": return BuddyApp.str(R.string.hub_tool_press_back);
                    case "home": return BuddyApp.str(R.string.hub_tool_press_home);
                    case "recents": return BuddyApp.str(R.string.hub_tool_press_recents);
                    case "notifications": return BuddyApp.str(R.string.hub_tool_press_notifications);
                    case "quick_settings": return BuddyApp.str(R.string.hub_tool_press_quick_settings);
                    default: return BuddyApp.str(R.string.hub_tool_press, b);
                }
            }
            case "open_app": return BuddyApp.str(R.string.hub_tool_open_app, in.optString("app"));
            case "list_apps": return BuddyApp.str(R.string.hub_tool_list_apps);
            case "open_url": return BuddyApp.str(R.string.hub_tool_open_url, clip(in.optString("url"), 40));
            case "wait":
            case "ToolSearch":
            case "TodoWrite":
                return null;
            case "confirm": {
                // OpenCode reports a step only once it's done, so its approval step carries the answer (_answer)
                String ans = in.optString("_answer", "");
                if (ans.startsWith("APPROVED")) return BuddyApp.str(R.string.hub_tool_approved, clip(in.optString("summary"), 80));
                if (ans.startsWith("CHANGES")) {
                    int a = ans.indexOf('“'), b = ans.lastIndexOf('”');
                    return a >= 0 && b > a ? BuddyApp.str(R.string.hub_tool_change_requested_text, clip(ans.substring(a + 1, b), 80))
                            : BuddyApp.str(R.string.hub_tool_change_requested);
                }
                if (ans.startsWith("DENIED")) return BuddyApp.str(R.string.hub_tool_denied);
                return BuddyApp.str(R.string.hub_tool_asking_approval);
            }
            case "ask": {
                String ans = in.optString("_answer", ""); // OpenCode, as for confirm
                if (ans.startsWith("ANSWER")) {
                    int a = ans.indexOf('“'), b = ans.lastIndexOf('”');
                    return a >= 0 && b > a ? BuddyApp.str(R.string.hub_tool_answered_text, clip(ans.substring(a + 1, b), 80))
                            : BuddyApp.str(R.string.hub_tool_answered);
                }
                if (ans.startsWith("NO ANSWER")) return BuddyApp.str(R.string.hub_tool_question_skipped);
                return BuddyApp.str(R.string.hub_tool_asking, clip(in.optString("question"), 80));
            }
            case "point_out": {
                JSONArray marks = in.optJSONArray("marks");
                StringBuilder labels = new StringBuilder();
                for (int i = 0; marks != null && i < marks.length(); i++) {
                    JSONObject m = marks.optJSONObject(i);
                    String l = m == null ? "" : m.optString("label", "").trim();
                    if (!l.isEmpty()) labels.append(labels.length() > 0 ? ", " : "").append(l);
                }
                return labels.length() > 0 ? BuddyApp.str(R.string.hub_tool_point_out, clip(labels.toString(), 60))
                        : BuddyApp.str(R.string.hub_tool_point_out_any);
            }
            case "update_buddy": return BuddyApp.str(in.optBoolean("install") ? R.string.hub_tool_update_download : R.string.hub_tool_update_check);
            case "notify": return in.optString("text");
            case "schedule_message": return BuddyApp.str(R.string.hub_tool_schedule_message, clip(in.optString("message"), 40));
            case "list_scheduled": return BuddyApp.str(R.string.hub_tool_list_scheduled);
            case "cancel_scheduled": return BuddyApp.str(R.string.hub_tool_cancel_scheduled);
            case "reply_notification": return BuddyApp.str(R.string.hub_tool_reply_notification, clip(in.optString("text"), 40));
            case "browser_open": return BuddyApp.str(R.string.hub_tool_browser_open, clip(in.optString("url"), 40));
            case "browser_read": return BuddyApp.str(R.string.hub_tool_browser_read);
            case "browser_click": return BuddyApp.str(R.string.hub_tool_browser_click, in.optInt("ref"));
            case "browser_type": return BuddyApp.str(R.string.hub_tool_browser_type, clip(in.optString("text"), 40));
            case "browser_scroll": return BuddyApp.str(R.string.hub_tool_browser_scroll);
            case "browser_screenshot": return BuddyApp.str(R.string.hub_tool_browser_screenshot);
            case "browser_back": return BuddyApp.str(R.string.hub_tool_browser_back);
            case "browser_tabs": return BuddyApp.str(R.string.hub_tool_browser_tabs);
            case "WebSearch": return BuddyApp.str(R.string.hub_tool_web_search, clip(in.optString("query"), 40));
            case "WebFetch": return BuddyApp.str(R.string.hub_tool_web_fetch, clip(in.optString("url"), 40));
            case "Bash": return in.has("command") ? BuddyApp.str(R.string.hub_tool_command, clip(in.optString("command"), 40))
                    : BuddyApp.str(R.string.hub_tool_command_any);
            case "Edit":
            case "Write": return in.has("file_path") ? BuddyApp.str(R.string.hub_tool_edit, clip(in.optString("file_path").replaceAll(".*/", ""), 40))
                    : BuddyApp.str(R.string.hub_tool_edit_any);
            case "Read": return BuddyApp.str(R.string.hub_tool_read_file, clip(in.optString("file_path").replaceAll(".*/", ""), 40));
            default: return BuddyApp.str(R.string.hub_tool_other, n);
        }
    }

    static String clip(String s, int n) {
        s = s.replace('\n', ' ');
        return s.length() > n ? s.substring(0, n - 1) + "…" : s;
    }
}
