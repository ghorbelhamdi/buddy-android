package app.buddy.assistant;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Bubble conversations. Each has its own brain (claude/codex), history and agent session in Buddy's Linux. */
final class Sessions {
    static final int MAX_SESSIONS = 20, MAX_MESSAGES = 150;

    static final class Msg {
        final Bubble.Kind kind;
        final String text;
        /** Attachment ids (see Attachments); empty for most messages. */
        final List<String> files;
        /** A user message Buddy sent on its own at a scheduled time (see Schedules): the chat marks it so. */
        boolean scheduled;

        Msg(Bubble.Kind kind, String text) {
            this(kind, text, null);
        }

        Msg(Bubble.Kind kind, String text, List<String> files) {
            this.kind = kind;
            this.text = text;
            this.files = files == null ? new ArrayList<>() : files;
        }

        /** Text for places that can't show attachments (bubble panel, previews). */
        String label() {
            if (files.isEmpty()) return text;
            String n = files.size() == 1 ? "📎 " + Attachments.name(files.get(0))
                    : BuddyApp.plural(R.plurals.attach_count, files.size(), files.size());
            return text.isEmpty() ? n : kind == Bubble.Kind.BUDDY ? text + "\n" + n : n + "\n" + text;
        }
    }

    static final class Session {
        final String id;
        final String backend;
        String title = "";
        /** Model for this chat ("" = the agent's default), e.g. claude-opus-5-5. */
        String model = "";
        /** Reasoning effort for this chat ("" = the agent's default); see Models.efforts. */
        String effort = "";
        /** The model the agent last reported using (for "Default"), or "" if it doesn't say. */
        String actualModel = "";
        /** How much of the model's context the conversation fills, and its size, in tokens (0: the agent hasn't said). */
        long ctxUsed, ctxWindow;
        /** Android closed Buddy while the agent was working: the chat offers to continue (see ChatHub.resume). */
        boolean interrupted;
        /** Incognito: deleted, with what the agent kept of it, when Buddy starts again (see ChatHub). */
        boolean incognito;
        /** Linux tools: the agent may run commands and edit files in ~/.buddy/work (buddy-run's dev). Never on Play. */
        boolean dev;
        /** Long-task mode: the screen stays on while this chat works, and its end is a notification (see KeepAwake). */
        boolean awake;
        /** The chat's scheduled task (see Schedules), or null. */
        Schedules.Plan schedule;
        /** Messages the agent scheduled for itself in this chat (schedule_message), soonest first. Main thread. */
        final List<Schedules.Later> later = new ArrayList<>();
        long updated = System.currentTimeMillis();
        final List<Msg> messages = new ArrayList<>();
        // runtime only
        boolean running, gotText, unread;
        String status = "";
        String lastReply = "";
        long lastEventAt;
        int runId;
        /** What the user added while this chat was working, not yet passed to the agent (guarded by itself). */
        final List<String> notes = new ArrayList<>();
        /** Notes the agent never got because the user stopped it: put back into the message box (main thread). */
        String unsent;
        /**
         * "Allowed without asking": apps where this chat's routine approvals go ahead on their own until its run ends
         * (see Guard.runRule). Never saved, so a restart forgets them; except in a chat with a schedule, which keeps
         * them between runs (and saved) for its scheduled runs, until the schedule is removed.
         */
        final java.util.Set<String> allowed = java.util.Collections.synchronizedSet(new java.util.LinkedHashSet<>());
        /** The current run is a scheduled task's: its end is a notification, as in long-task mode. */
        boolean scheduledRun;
        /** How the current run ended so far: null (fine), "stopped" or "failed". */
        String outcome;

        Session(String id, String backend) {
            this.id = id;
            this.backend = backend;
        }

        String brainName() {
            return Account.nameOf(backend);
        }

        String label() {
            return title.isEmpty() ? BuddyApp.str(R.string.session_new_chat, brainName()) : title;
        }
    }

    private final Context app;
    private final SharedPreferences sp;
    final List<Session> all = new ArrayList<>(); // most recent first
    Session current;

    Sessions(Context c) {
        app = c;
        sp = c.getSharedPreferences("buddy_sessions", Context.MODE_PRIVATE);
        boolean older = load(); // chats from before Linux tools were per chat
        if (all.isEmpty()) create(Prefs.backend(c));
        String cur = sp.getString("current", null);
        current = find(cur);
        if (current == null) current = all.get(0);
        if (older) save(); // so they keep what Settings said now, whatever it says later
    }

    Session create(String backend) {
        return create(backend, true);
    }

    /** New chats start with Linux tools as Settings says (Prefs.devMode). */
    Session create(String backend, boolean select) {
        return create(backend, select, Prefs.devMode(app));
    }

    /** dev: Linux tools for this chat (a New chat sheet's switch). */
    Session create(String backend, boolean select, boolean dev) {
        Session s = new Session(UUID.randomUUID().toString().replace("-", "").substring(0, 12), backend);
        s.dev = dev;
        all.add(0, s);
        // the oldest chats make room, but not one with something scheduled (unless every one has)
        while (all.size() > MAX_SESSIONS) {
            int drop = all.size() - 1;
            for (int i = all.size() - 1; i > 0; i--) {
                if (all.get(i).schedule == null && all.get(i).later.isEmpty() && !all.get(i).running) {
                    drop = i;
                    break;
                }
            }
            all.remove(drop);
        }
        if (select || current == null || !all.contains(current)) current = s;
        save();
        return s;
    }

    Session find(String id) {
        if (id == null) return null;
        for (Session s : all) if (s.id.equals(id)) return s;
        return null;
    }

    void remove(Session s) {
        all.remove(s);
        if (all.isEmpty()) create(s.backend);
        if (current == s) current = all.get(0);
        save();
    }

    void select(Session s) {
        current = s;
        s.unread = false;
        save();
    }

    int runningCount() {
        int n = 0;
        for (Session s : all) if (s.running) n++;
        return n;
    }

    boolean anyUnread() {
        for (Session s : all) if (s.unread) return true;
        return false;
    }

    void add(Session s, Bubble.Kind kind, String text) {
        add(s, kind, text, null);
    }

    void add(Session s, Bubble.Kind kind, String text, List<String> files) {
        s.messages.add(new Msg(kind, text, files));
        while (s.messages.size() > MAX_MESSAGES) s.messages.remove(0);
        s.updated = System.currentTimeMillis();
        if (kind == Bubble.Kind.USER && s.title.isEmpty()) {
            String t = text.isEmpty() && files != null && !files.isEmpty() ? Attachments.name(files.get(0)) : text;
            s.title = t.length() > 40 ? t.substring(0, 39) + "…" : t;
        }
        // keep most recently used first
        all.remove(s);
        all.add(0, s);
        save();
    }

    void save() {
        try {
            JSONArray arr = new JSONArray();
            for (Session s : all) {
                JSONArray msgs = new JSONArray();
                for (Msg m : s.messages) {
                    JSONObject mo = new JSONObject().put("k", m.kind.name()).put("t", m.text);
                    if (!m.files.isEmpty()) mo.put("f", new JSONArray(m.files));
                    if (m.scheduled) mo.put("s", true);
                    msgs.put(mo);
                }
                arr.put(new JSONObject().put("id", s.id).put("backend", s.backend).put("title", s.title).put("model", s.model).put("effort", s.effort).put("actual_model", s.actualModel).put("ctx_used", s.ctxUsed).put("ctx_window", s.ctxWindow).put("interrupted", s.interrupted).put("incognito", s.incognito)
                        .put("dev", s.dev).put("awake", s.awake).put("updated", s.updated).put("messages", msgs));
                if (s.schedule != null) {
                    JSONArray ok;
                    synchronized (s.allowed) {
                        ok = new JSONArray(s.allowed);
                    }
                    arr.getJSONObject(arr.length() - 1).put("schedule", s.schedule.toJson()).put("allowed", ok);
                }
                if (!s.later.isEmpty()) {
                    JSONArray l = new JSONArray();
                    for (Schedules.Later m : s.later) l.put(m.toJson());
                    arr.getJSONObject(arr.length() - 1).put("later", l);
                }
            }
            sp.edit().putString("all", arr.toString()).putString("current", current == null ? null : current.id).apply();
        } catch (Exception ignored) {
        }
    }

    /** Returns whether there were chats from before Linux tools were per chat. */
    private boolean load() {
        boolean older = false;
        try {
            JSONArray arr = new JSONArray(sp.getString("all", "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Session s = new Session(o.getString("id"), o.optString("backend", "claude"));
                s.title = o.optString("title");
                s.model = o.optString("model");
                s.effort = o.optString("effort");
                s.actualModel = o.optString("actual_model");
                s.ctxUsed = o.optLong("ctx_used");
                s.ctxWindow = o.optLong("ctx_window");
                s.interrupted = o.optBoolean("interrupted");
                s.incognito = o.optBoolean("incognito");
                // a chat from before Linux tools were per chat gets what the one switch in Settings gave every chat
                older |= !o.has("dev");
                s.dev = o.optBoolean("dev", Prefs.devMode(app));
                s.awake = o.optBoolean("awake");
                s.schedule = Schedules.Plan.of(o.optJSONObject("schedule"));
                JSONArray ok = s.schedule == null ? null : o.optJSONArray("allowed"); // kept for its scheduled runs
                if (ok != null) for (int j = 0; j < ok.length(); j++) s.allowed.add(ok.getString(j));
                JSONArray later = o.optJSONArray("later");
                for (int j = 0; later != null && j < later.length(); j++) {
                    Schedules.Later m = Schedules.Later.of(later.optJSONObject(j));
                    if (m != null) s.later.add(m);
                }
                s.updated = o.optLong("updated");
                JSONArray msgs = o.optJSONArray("messages");
                if (msgs != null) for (int j = 0; j < msgs.length(); j++) {
                    JSONObject m = msgs.getJSONObject(j);
                    List<String> files = new ArrayList<>();
                    JSONArray f = m.optJSONArray("f");
                    if (f != null) for (int k = 0; k < f.length(); k++) files.add(f.getString(k));
                    Msg msg = new Msg(Bubble.Kind.valueOf(m.getString("k")), m.getString("t"), files);
                    msg.scheduled = m.optBoolean("s");
                    s.messages.add(msg);
                }
                all.add(s);
            }
        } catch (Exception ignored) {
            all.clear();
            return false;
        }
        return older;
    }
}
