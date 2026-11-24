package app.buddy.assistant;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.ArrayList;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Agent teams (issue #81, with #75: members on other agents): a team is a set of members, each with a role (the
 * instructions it works by), its own agent and model. A chat can work with a team (Session.team).
 * <p>
 * Two ways a team works: in fixed stages, every task goes through the members stage by stage (each stage gets the
 * reports of the one before; members of one stage work at once), and then the chat's own agent answers from the
 * last reports; or the lead decides, and the chat's agent hands work to the members it wants, when it wants (delegate,
 * naming the member). Members work like the chat: with its Linux tools in its project folder, and what they send,
 * post, buy or delete still comes to the user on a card. They don't ask the user questions or start members of their own.
 */
final class Teams {
    static final String STAGES = "stages", LEAD = "lead";

    static final class Member {
        String name, role, agent, model;
        /** Fixed stages: which stage it works in, from 1. */
        int stage;

        Member(String name, String role, String agent, String model, int stage) {
            this.name = name;
            this.role = role;
            this.agent = agent;
            this.model = model;
            this.stage = stage;
        }

        JSONObject json() throws Exception {
            return new JSONObject().put("name", name).put("role", role).put("agent", agent).put("model", model).put("stage", stage);
        }

        static Member of(JSONObject o) {
            return new Member(o.optString("name").trim(), o.optString("role").trim(), o.optString("agent"), o.optString("model"),
                    Math.max(1, o.optInt("stage", 1)));
        }
    }

    static final class Team {
        String id, name, flow = STAGES;
        final List<Member> members = new ArrayList<>();

        JSONObject json() throws Exception {
            JSONArray m = new JSONArray();
            for (Member x : members) m.put(x.json());
            return new JSONObject().put("id", id).put("name", name).put("flow", flow).put("members", m);
        }

        static Team of(JSONObject o) {
            Team t = new Team();
            t.id = o.optString("id");
            t.name = o.optString("name").trim();
            t.flow = LEAD.equals(o.optString("flow")) ? LEAD : STAGES;
            JSONArray m = o.optJSONArray("members");
            if (m != null) for (int i = 0; i < m.length(); i++) {
                JSONObject x = m.optJSONObject(i);
                if (x != null && !x.optString("name").trim().isEmpty()) t.members.add(Member.of(x));
            }
            return t;
        }

        Member member(String name) {
            for (Member m : members) if (m.name.equalsIgnoreCase(name == null ? "" : name.trim())) return m;
            return null;
        }

        /** Fixed stages, in order: the stage numbers used, smallest first. */
        List<Integer> stages() {
            java.util.TreeSet<Integer> s = new java.util.TreeSet<>();
            for (Member m : members) s.add(m.stage);
            return new ArrayList<>(s);
        }
    }

    private Teams() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences("teams", Context.MODE_PRIVATE);
    }

    /** Every team; the first time, one to start from: a coding team on the agent new chats use. */
    static synchronized List<Team> all(Context c) {
        List<Team> out = new ArrayList<>();
        String raw = sp(c).getString("all", null);
        if (raw == null) {
            out.add(starter(c));
            save(c, out);
            return out;
        }
        try {
            JSONArray a = new JSONArray(raw);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i);
                if (o != null) out.add(Team.of(o));
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    static synchronized void save(Context c, List<Team> teams) {
        JSONArray a = new JSONArray();
        try {
            for (Team t : teams) a.put(t.json());
        } catch (Exception ignored) {
        }
        sp(c).edit().putString("all", a.toString()).apply();
    }

    static Team find(Context c, String id) {
        if (id == null || id.isEmpty()) return null;
        for (Team t : all(c)) if (t.id.equals(id)) return t;
        return null;
    }

    static void put(Context c, Team team) {
        List<Team> l = all(c);
        boolean found = false;
        for (int i = 0; i < l.size(); i++) if (l.get(i).id.equals(team.id)) {
            l.set(i, team);
            found = true;
        }
        if (!found) l.add(team);
        save(c, l);
    }

    static void remove(Context c, String id) {
        List<Team> l = all(c);
        l.removeIf(t -> t.id.equals(id));
        save(c, l);
    }

    static String newId() {
        return "t" + Long.toString(System.currentTimeMillis(), 36);
    }

    private static Team starter(Context c) {
        Team t = new Team();
        t.id = "coding";
        t.name = c.getString(R.string.teams_starter_name);
        t.flow = STAGES;
        String a = Prefs.backend(c);
        t.members.add(new Member(c.getString(R.string.teams_starter_planner), c.getString(R.string.teams_starter_planner_role), a, "", 1));
        t.members.add(new Member(c.getString(R.string.teams_starter_developer), c.getString(R.string.teams_starter_developer_role), a, "", 2));
        t.members.add(new Member(c.getString(R.string.teams_starter_auditor), c.getString(R.string.teams_starter_auditor_role), a, "", 3));
        t.members.add(new Member(c.getString(R.string.teams_starter_finalizer), c.getString(R.string.teams_starter_finalizer_role), a, "", 4));
        return t;
    }

    /** A member's agent as people know it ("Claude Code"), with its model when one is set. */
    static String describe(Member m) {
        String agent = m.agent;
        for (Account acc : Account.ALL) if (acc.agent.equals(m.agent)) agent = acc.name();
        return agent + (m.model == null || m.model.isEmpty() ? "" : " · " + Models.label(m.agent, m.model));
    }

    /**
     * What the chat's agent is told with each message of a chat that works with a team: who is in it and how to use
     * them. English, as all that agents are told.
     */
    static String brief(Team t) {
        StringBuilder b = new StringBuilder("[This chat works with the team \"").append(t.name).append("\". ");
        if (STAGES.equals(t.flow)) {
            b.append("It works in fixed stages: before anything else, call the team tool with the user's task as they gave ")
                    .append("it (and anything they attached or said before that the members need). The members work stage ")
                    .append("by stage and the tool returns their reports; then do what is left and answer the user from them.");
        } else {
            b.append("You lead it: hand parts of the work to the members you choose with delegate, naming each in ")
                    .append("\"member\" (they work like you, on their own agent), and use their reports.");
        }
        b.append(" Members: ");
        for (int i = 0; i < t.members.size(); i++) {
            Member m = t.members.get(i);
            b.append(i > 0 ? "; " : "").append(m.name).append(" (").append(describe(m))
                    .append(STAGES.equals(t.flow) ? ", stage " + m.stage : "").append("): ").append(ChatHub.clip(m.role, 300));
        }
        return b.append(".]\n\n").toString();
    }
}
