package app.buddy.assistant;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The user's notification rules (Settings → Notification rules): when a notification from an app arrives, optionally
 * from someone or with some words in it, the rule's instruction goes to an agent (see {@link Triggers}).
 */
final class Rules {
    /** Countdown lengths the editor offers, in seconds. */
    static final int[] SECONDS = {10, 30, 60, 300};
    /** Hourly limits the editor offers (runs for one conversation); 0 is no limit. */
    static final int[] LIMITS = {5, 20, 50, 0};

    static final class Rule {
        String id = Long.toString(System.currentTimeMillis(), 36);
        /** The app the notification comes from, and its name when the rule was saved. */
        String pkg = "", app = "";
        /** Who it's from (names, comma-separated) and words it contains; empty matches any. */
        String from = "", words = "";
        String instruction = "";
        /** The agent that runs it (claude, codex, opencode or grok), and its model ("" for the agent's default). */
        String backend = "claude", model = "";
        /** Before acting: "ask" (approval cards wait for the user) or "countdown" (they approve themselves). */
        String approval = "ask";
        int seconds = 30;
        /** Most runs an hour for one conversation, and three times as many for the whole rule; 0 for no limit. */
        int limit = 20;
        boolean on = true;

        JSONObject json() throws org.json.JSONException {
            return new JSONObject().put("id", id).put("pkg", pkg).put("app", app).put("from", from).put("words", words)
                    .put("instruction", instruction).put("backend", backend).put("model", model).put("approval", approval)
                    .put("seconds", seconds).put("limit", limit).put("on", on);
        }

        static Rule of(JSONObject o) {
            Rule r = new Rule();
            r.id = o.optString("id", r.id);
            r.pkg = o.optString("pkg");
            r.app = o.optString("app");
            r.from = o.optString("from");
            r.words = o.optString("words");
            r.instruction = o.optString("instruction");
            r.backend = o.optString("backend", "claude");
            r.model = o.optString("model", "");
            r.approval = o.optString("approval", "ask");
            r.seconds = o.optInt("seconds", 30);
            r.limit = o.optInt("limit", 20);
            r.on = o.optBoolean("on", true);
            return r;
        }

        boolean countdown() {
            return "countdown".equals(approval);
        }

        /** Does a notification from this app match the rule's sender and words? */
        boolean matches(Triggers.Note n) {
            if (!on || !pkg.equals(n.pkg)) return false;
            if (!any(from, n.conversation + "\n" + n.title + "\n" + n.sender)) return false;
            return any(words, n.title + "\n" + n.text);
        }

        /** "From Sarah · with “price”", or null when the rule takes every notification of its app. */
        String filters(Context c) {
            List<String> parts = new ArrayList<>();
            if (!from.trim().isEmpty()) parts.add(c.getString(R.string.rules_from_summary, from.trim()));
            if (!words.trim().isEmpty()) parts.add(c.getString(R.string.rules_words_summary, words.trim()));
            return parts.isEmpty() ? null : android.text.TextUtils.join(" · ", parts);
        }

        /** Seconds as the user reads them: "30 s", "1 min", "5 min". */
        static String duration(Context c, int s) {
            return s < 60 ? c.getString(R.string.rules_seconds, s) : c.getString(R.string.rules_minutes, s / 60);
        }
    }

    private Rules() {
    }

    /**
     * Is any of the comma-separated terms in the text (ignoring case)? An empty list matches anything. The commas of
     * Arabic, Chinese and Japanese keyboards (، ， 、) count too.
     */
    static boolean any(String terms, String text) {
        if (terms == null || terms.trim().isEmpty()) return true;
        String t = text == null ? "" : text.toLowerCase(Locale.ROOT);
        for (String term : terms.split("[,،，、]")) {
            String x = term.trim().toLowerCase(Locale.ROOT);
            if (!x.isEmpty() && t.contains(x)) return true;
        }
        return false;
    }

    static List<Rule> all(Context c) {
        List<Rule> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(Prefs.rules(c));
            for (int i = 0; i < a.length(); i++) out.add(Rule.of(a.getJSONObject(i)));
        } catch (Exception ignored) {
        }
        return out;
    }

    static void save(Context c, List<Rule> rules) {
        JSONArray a = new JSONArray();
        try {
            for (Rule r : rules) a.put(r.json());
        } catch (Exception ignored) {
        }
        Prefs.setRules(c, a.toString());
    }

    static Rule find(Context c, String id) {
        for (Rule r : all(c)) if (r.id.equals(id)) return r;
        return null;
    }

    /** Adds the rule, or replaces the one with its id. */
    static void put(Context c, Rule rule) {
        List<Rule> rules = all(c);
        boolean replaced = false;
        for (int i = 0; i < rules.size(); i++) {
            if (rules.get(i).id.equals(rule.id)) {
                rules.set(i, rule);
                replaced = true;
            }
        }
        if (!replaced) rules.add(rule);
        save(c, rules);
    }

    static void remove(Context c, String id) {
        List<Rule> rules = all(c);
        for (java.util.Iterator<Rule> it = rules.iterator(); it.hasNext(); ) if (it.next().id.equals(id)) it.remove();
        save(c, rules);
    }

    /** The first switched-on rule a notification matches, or null. */
    static Rule match(Context c, Triggers.Note n) {
        for (Rule r : all(c)) if (r.matches(n)) return r;
        return null;
    }

    /** Does any switched-on rule watch this app? (Checked before a notification is even read.) */
    static boolean watches(Context c, String pkg) {
        for (Rule r : all(c)) if (r.on && r.pkg.equals(pkg)) return true;
        return false;
    }
}
