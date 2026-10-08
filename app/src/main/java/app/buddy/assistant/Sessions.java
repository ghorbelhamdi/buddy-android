package app.buddy.assistant;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Bubble conversations. Each has its own brain (claude/codex), history and agent session in Termux. */
final class Sessions {
    static final int MAX_SESSIONS = 20, MAX_MESSAGES = 150;

    static final class Msg {
        final Bubble.Kind kind;
        final String text;
        /** Attachment ids (see Attachments); empty for most messages. */
        final List<String> files;

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
            String n = files.size() == 1 ? "📎 " + Attachments.name(files.get(0)) : "📎 " + files.size() + " attachments";
            return text.isEmpty() ? n : n + "\n" + text;
        }
    }

    static final class Session {
        final String id;
        final String backend;
        String title = "";
        /** Model for this chat ("" = the agent's default), e.g. claude-opus-5-5. */
        String model = "";
        long updated = System.currentTimeMillis();
        final List<Msg> messages = new ArrayList<>();
        // runtime only
        boolean running, gotText, unread;
        String status = "";
        String lastReply = "";
        long lastEventAt;
        int runId;

        Session(String id, String backend) {
            this.id = id;
            this.backend = backend;
        }

        String brainName() {
            return "codex".equals(backend) ? "Codex" : "Claude Code";
        }

        String label() {
            return title.isEmpty() ? "New " + brainName() + " chat" : title;
        }
    }

    private final SharedPreferences sp;
    final List<Session> all = new ArrayList<>(); // most recent first
    Session current;

    Sessions(Context c) {
        sp = c.getSharedPreferences("buddy_sessions", Context.MODE_PRIVATE);
        load();
        if (all.isEmpty()) create(Prefs.backend(c));
        String cur = sp.getString("current", null);
        current = find(cur);
        if (current == null) current = all.get(0);
    }

    Session create(String backend) {
        Session s = new Session(UUID.randomUUID().toString().replace("-", "").substring(0, 12), backend);
        all.add(0, s);
        while (all.size() > MAX_SESSIONS) all.remove(all.size() - 1);
        current = s;
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
                    msgs.put(mo);
                }
                arr.put(new JSONObject().put("id", s.id).put("backend", s.backend).put("title", s.title).put("model", s.model)
                        .put("updated", s.updated).put("messages", msgs));
            }
            sp.edit().putString("all", arr.toString()).putString("current", current == null ? null : current.id).apply();
        } catch (Exception ignored) {
        }
    }

    private void load() {
        try {
            JSONArray arr = new JSONArray(sp.getString("all", "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Session s = new Session(o.getString("id"), o.optString("backend", "claude"));
                s.title = o.optString("title");
                s.model = o.optString("model");
                s.updated = o.optLong("updated");
                JSONArray msgs = o.optJSONArray("messages");
                if (msgs != null) for (int j = 0; j < msgs.length(); j++) {
                    JSONObject m = msgs.getJSONObject(j);
                    List<String> files = new ArrayList<>();
                    JSONArray f = m.optJSONArray("f");
                    if (f != null) for (int k = 0; k < f.length(); k++) files.add(f.getString(k));
                    s.messages.add(new Msg(Bubble.Kind.valueOf(m.getString("k")), m.getString("t"), files));
                }
                all.add(s);
            }
        } catch (Exception ignored) {
            all.clear();
        }
    }
}
