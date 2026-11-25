package app.buddy.assistant;

import android.content.Context;


import java.util.ArrayList;
import java.util.List;

/**
 * Chats under another chat (Session.parent): today a team's members (see Team). What they may do, whose card their
 * questions for the user's OK are, and the agents a member can run on. Sub-agents of their own (the delegate tool)
 * became the team's helpers in 2.5.0; chats left from them are deleted when Buddy starts (see ChatHub).
 */
final class Helpers {
    private Helpers() {
    }

    static boolean is(Context c, String chat) {
        Sessions.Session s = chat == null ? null : ChatHub.get(c).sessions.find(chat);
        return s != null && !s.parent.isEmpty();
    }

    /** A helper that works like its chat (see above). */
    static boolean works(Context c, String chat) {
        Sessions.Session s = chat == null ? null : ChatHub.get(c).sessions.find(chat);
        return s != null && !s.parent.isEmpty() && s.works;
    }

    /**
     * Whose card a chat's question for the user's OK is: a working helper's or a team member's comes up in the chat
     * the user talks to (its own chat is out of sight), saying who wants it.
     */
    static String cardChat(Context c, String chat) {
        Sessions.Session s = chat == null ? null : ChatHub.get(c).sessions.find(chat);
        return s != null && !s.parent.isEmpty() ? s.parent : chat;
    }

    static String cardText(Context c, String chat, String summary) {
        Sessions.Session s = chat == null ? null : ChatHub.get(c).sessions.find(chat);
        if (s == null || s.parent.isEmpty()) return summary;
        // through the leader (the user picked that): for whom, then what it wants to do, word for word (first, so a
        // card cut short still shows it), then the task that member was given (the leader's words, or the user's)
        String task = s.told == null ? "" : Ui.linkText(s.told).replace('\n', ' ').trim();
        if (task.length() > 200) task = task.substring(0, 200) + "…";
        String out = c.getString(R.string.team_card_for, s.title) + "\n\n" + summary;
        if (!task.isEmpty()) out += "\n\n" + c.getString(s.toldBy == null || s.toldBy.isEmpty() ? R.string.team_card_task_you
                : R.string.team_card_task, task);
        return out;
    }

    /** A helper that only looks things up: no card, no trust, nothing it does is waved through. */
    static boolean lookOnly(Context c, String chat) {
        return is(c, chat) && !works(c, chat);
    }

    /**
     * An agent that is signed in on this phone ("claude", "codex"…), by its id or its name as people say it ("Claude",
     * "Claude Code", "GitHub Copilot"); null when there's none such.
     */
    static String agentOf(Context c, String said) {
        if (said == null || said.trim().isEmpty()) return null;
        String s = said.trim().toLowerCase(java.util.Locale.ROOT);
        for (Account a : Account.ALL) {
            String n = a.name().toLowerCase(java.util.Locale.ROOT);
            boolean named = a.agent.equals(s) || n.equals(s) || n.startsWith(s + " ") || s.endsWith(" " + n)
                    || ("agy".equals(a.agent) && s.startsWith("antigravity"));
            if (named && SetupState.signedIn(c, a.agent)) return a.agent;
        }
        return null;
    }

    /** The agents a helper can run on, for the agent to read ("claude (Claude Code), pi (Pi)"). */
    static String agents(Context c) {
        StringBuilder b = new StringBuilder();
        for (Account a : Account.ALL) if (SetupState.signedIn(c, a.agent)) b.append(b.length() > 0 ? ", " : "").append(a.agent).append(" (").append(a.name()).append(")");
        return b.toString();
    }

    /** The chat's helpers, oldest first. */
    static List<Sessions.Session> of(ChatHub hub, String parent) {
        List<Sessions.Session> l = new ArrayList<>();
        // (a team's members are no sub-agents: they last, and the chat shows them its own way, see Team)
        for (Sessions.Session s : new ArrayList<>(hub.sessions.all)) if (parent.equals(s.parent) && s.member.isEmpty()) l.add(s);
        // in the order they were started (the chat list's own order changes with every message)
        java.util.Collections.sort(l, (x, y) -> Integer.compare(x.slot, y.slot));
        return l;
    }

    /** What a tool a helper may not use answers. */
    static String refuse(String what) {
        return "Not for a sub-agent: " + what + ". Say in your report what the chat that started you should do.";
    }

    static void stop(ChatHub hub, String parent) {
        for (Sessions.Session h : of(hub, parent)) {
            if (h.running) hub.stop(h);
        }
        hub.changed();
    }

    /** A helper's own folder in the chat's work folder (with Linux tools): what it makes stays out of the others' way. */
    static String folder(String helper) {
        return "/root/.buddy/work/sub-agents/" + helper;
    }
}
