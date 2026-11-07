package app.buddy.assistant;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Bubble conversations. Each has its own brain (claude/codex), history and agent session in Buddy's Linux.
 * Each chat is its own file (files/chats/&lt;id&gt;.json), so a message rewrites only its chat; the list's order and
 * the current chat are in the buddy_sessions settings. There's no limit on how many chats there are: archiving
 * hides one from the list instead.
 */
final class Sessions {
    static final int MAX_MESSAGES = 150;

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

        /** For a chat list row: a reply's words only, without the names of its pictures; empty if it has none. */
        String preview() {
            return kind == Bubble.Kind.BUDDY ? text : label();
        }

        /** Text for places that can't show attachments (bubble panel). */
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
        /** This run may end quietly (a scheduled task's "only tell me when there's news"); quietSaid: it did. Runtime. */
        boolean quietRun, quietSaid;
        /** Linux tools: the agent may run commands and edit files in ~/.buddy/work (buddy-run's dev). Never on Play. */
        boolean dev;
        /** Long-task mode: the screen stays on while this chat works, and its end is a notification (see KeepAwake). */
        boolean awake;
        /** Pinned to the top of the chat list. */
        boolean pinned;
        /** Archived: out of the chat list (it has an Archived section), the bubble and sharing; its schedules still run. */
        boolean archived;
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
    private final java.io.File dir;
    /** A fingerprint of what each chat's file holds, so a save writes only the chats that changed. */
    private final java.util.Map<String, Long> written = new java.util.HashMap<>();
    /** Chat files are written here, one after the other, off the main thread. */
    private final java.util.concurrent.ExecutorService writer = java.util.concurrent.Executors.newSingleThreadExecutor();
    private volatile boolean frozen;
    final List<Session> all = new ArrayList<>(); // most recent first
    Session current;
    /**
     * Every chat was read without trouble: only then may the Linux folders of chats that no longer exist be removed
     * (see ChatHub.pruneLinux), so a chat file that failed to read never takes its agent's session with it.
     */
    boolean complete;

    Sessions(Context c) {
        app = c;
        sp = c.getSharedPreferences("buddy_sessions", Context.MODE_PRIVATE);
        dir = new java.io.File(c.getFilesDir(), "chats");
        dir.mkdirs();
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
        if (select || current == null || !all.contains(current)) current = s;
        save(s);
        return s;
    }

    Session find(String id) {
        if (id == null) return null;
        for (Session s : all) if (s.id.equals(id)) return s;
        return null;
    }

    void remove(Session s) {
        all.remove(s);
        synchronized (this) {
            written.remove(s.id);
        }
        final String id = s.id;
        writer.execute(() -> {
            //noinspection ResultOfMethodCallIgnored
            file(id).delete();
        });
        if (all.isEmpty()) create(s.backend);
        if (current == s) current = all.get(0);
        saveOrder();
    }

    void select(Session s) {
        current = s;
        s.unread = false;
        saveOrder();
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
        if (kind == Bubble.Kind.USER && s.title.isEmpty()) s.title = autoTitle(text, files);
        // keep most recently used first
        all.remove(s);
        all.add(0, s);
        save(s);
    }

    /** A chat's name from its first message: its text, or its first attachment's name; at most 40 characters. */
    static String autoTitle(String text, List<String> files) {
        String t = text.isEmpty() && files != null && !files.isEmpty() ? Attachments.name(files.get(0)) : text;
        return t.length() > 40 ? t.substring(0, 39) + "…" : t;
    }

    /**
     * Stops saving until Buddy restarts: a restore has put the backup's chats and settings in place, and nothing from
     * the chats in memory may write over them.
     */
    void freeze() {
        frozen = true;
    }

    /** Saves every chat that changed, and the list's order. */
    void save() {
        for (Session s : new ArrayList<>(all)) write(s);
        saveOrder();
    }

    /** Saves this chat (when it changed) and the list's order: what changing one chat needs. */
    void save(Session s) {
        if (all.contains(s)) write(s);
        saveOrder();
    }

    private synchronized void saveOrder() {
        if (frozen) return;
        JSONArray ids = new JSONArray();
        for (Session s : all) ids.put(s.id);
        SharedPreferences.Editor e = sp.edit().putString("order", ids.toString());
        if (complete) e.remove("all"); // the chats from before 2.2.26 are all in files now
        if (current != null) e.putString("current", current.id); // none yet while the chats are first read
        e.apply();
    }

    private java.io.File file(String id) {
        return new java.io.File(dir, id + ".json");
    }

    private static long fingerprint(String json) {
        return ((long) json.hashCode() << 32) ^ json.length();
    }

    /**
     * Writes the chat's file when its contents changed: the JSON is made here (on the caller's thread, which owns the
     * chat), the file is written by the writer thread through a temporary file, so a cut-off write loses nothing.
     */
    private synchronized void write(Session s) {
        if (frozen) return;
        final String json;
        try {
            json = toJson(s).toString();
        } catch (Exception e) {
            return; // kept in memory; the next save tries again
        }
        long fp = fingerprint(json);
        Long had = written.get(s.id);
        if (had != null && had == fp) return;
        written.put(s.id, fp);
        final String id = s.id;
        writer.execute(() -> {
            if (frozen) return;
            java.io.File f = file(id), tmp = new java.io.File(dir, id + ".json.tmp");
            try (java.io.FileOutputStream o = new java.io.FileOutputStream(tmp)) {
                o.write(json.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                o.getFD().sync();
            } catch (Exception e) {
                failed(id);
                return;
            }
            if (!tmp.renameTo(f)) failed(id);
        });
    }

    /** A chat's file couldn't be written: the next save of that chat writes it again. */
    private synchronized void failed(String id) {
        written.remove(id);
    }

    /** Waits until the chat files queued so far are written (the move from before 2.2.26 needs to know). */
    private boolean flush() {
        java.util.concurrent.Future<?> done = writer.submit(() -> {
        });
        try {
            done.get(30, java.util.concurrent.TimeUnit.SECONDS);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private static JSONObject toJson(Session s) throws Exception {
        JSONArray msgs = new JSONArray();
        for (Msg m : s.messages) {
            JSONObject mo = new JSONObject().put("k", m.kind.name()).put("t", m.text);
            if (!m.files.isEmpty()) mo.put("f", new JSONArray(m.files));
            if (m.scheduled) mo.put("s", true);
            msgs.put(mo);
        }
        JSONObject o = new JSONObject().put("id", s.id).put("backend", s.backend).put("title", s.title).put("model", s.model)
                .put("effort", s.effort).put("actual_model", s.actualModel).put("ctx_used", s.ctxUsed).put("ctx_window", s.ctxWindow)
                .put("interrupted", s.interrupted).put("incognito", s.incognito).put("dev", s.dev).put("awake", s.awake)
                .put("pinned", s.pinned).put("archived", s.archived).put("updated", s.updated).put("messages", msgs);
        if (s.schedule != null) {
            JSONArray ok;
            synchronized (s.allowed) {
                ok = new JSONArray(s.allowed);
            }
            o.put("schedule", s.schedule.toJson()).put("allowed", ok);
        }
        if (!s.later.isEmpty()) {
            JSONArray l = new JSONArray();
            for (Schedules.Later m : s.later) l.put(m.toJson());
            o.put("later", l);
        }
        return o;
    }

    /** A chat from its saved form; older: it's from before Linux tools were per chat (no "dev"). */
    private Session fromJson(JSONObject o, boolean[] older) throws Exception {
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
        older[0] |= !o.has("dev");
        s.dev = o.optBoolean("dev", Prefs.devMode(app));
        s.awake = o.optBoolean("awake");
        s.pinned = o.optBoolean("pinned");
        s.archived = o.optBoolean("archived");
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
        return s;
    }

    /**
     * Reads the chats: one file each, in the saved order (a file the order doesn't name yet, from a save cut off
     * between the two, comes after them). Chats from before 2.2.26, all in one "all" setting, move to files first.
     * Returns whether there were chats from before Linux tools were per chat.
     */
    private boolean load() {
        boolean[] older = {false};
        String legacy = sp.getString("all", null), order = sp.getString("order", null);
        // chats in the old "all" setting: from before 2.2.26 (an update, an older backup restored, or 2.2.25 used
        // again after a downgrade); it goes only once every chat is in its file, so a move that failed runs again
        if (legacy != null) {
            Linux.deleteTree(dir);
            dir.mkdirs();
            // the chats as they were, kept as they are in case the move goes wrong (not read again)
            try (java.io.FileOutputStream o = new java.io.FileOutputStream(new java.io.File(dir, "before-2.2.26.bak"))) {
                o.write(legacy.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            } catch (Exception ignored) {
            }
            try {
                JSONArray arr = new JSONArray(legacy);
                for (int i = 0; i < arr.length(); i++) all.add(fromJson(arr.getJSONObject(i), older));
                for (Session s : all) write(s);
                complete = flush() && written.size() == all.size();
                saveOrder(); // with every file written, the old setting goes
            } catch (Exception e) {
                all.clear();
                written.clear();
                complete = false;
            }
            return older[0];
        }
        boolean ok = true;
        java.util.Set<String> seen = new java.util.HashSet<>();
        try {
            JSONArray ids = new JSONArray(order == null ? "[]" : order);
            for (int i = 0; i < ids.length(); i++) {
                String id = ids.getString(i);
                if (seen.add(id) && !read(id, older)) ok = false;
            }
        } catch (Exception e) {
            ok = false;
        }
        java.io.File[] fs = dir.listFiles();
        if (fs != null) for (java.io.File f : fs) {
            String n = f.getName();
            if (n.endsWith(".json.tmp")) {
                //noinspection ResultOfMethodCallIgnored
                f.delete(); // a write that was cut off: the chat's last complete file is still there
            } else if (n.endsWith(".json") && seen.add(n.substring(0, n.length() - 5)) && !read(n.substring(0, n.length() - 5), older)) {
                ok = false;
            }
        }
        complete = ok;
        return older[0];
    }

    /** Reads one chat's file into the list; false when it's missing or can't be read. */
    private boolean read(String id, boolean[] older) {
        java.io.File f = file(id);
        try {
            String json = new String(java.nio.file.Files.readAllBytes(f.toPath()), java.nio.charset.StandardCharsets.UTF_8);
            Session s = fromJson(new JSONObject(json), older);
            if (!s.id.equals(id)) return false;
            all.add(s);
            written.put(id, fingerprint(json));
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
