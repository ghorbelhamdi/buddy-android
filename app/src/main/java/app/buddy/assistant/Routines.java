package app.buddy.assistant;

import android.content.Context;
import android.content.SharedPreferences;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/**
 * Routines: a task done once, saved as its steps, and done again later without the model. Each step names what it
 * touches by its text on the screen (never an element id or, where there's text, a position) and the app it expects
 * in front; text it types is a blank, filled at each run, so nothing the user typed is kept. Replays go through the
 * same checks and approval cards as the agent's own actions (see Tools.runRoutine), and stop at the first step that
 * doesn't fit rather than guess.
 *
 * While a chat's run goes, Buddy notes its actions here in that form (in memory only), so the agent can turn what it
 * just did into a routine.
 */
final class Routines {
    static final int MAX_STEPS = 60;
    /** Actions a routine's step can be. */
    static final Set<String> ACTIONS = new java.util.HashSet<>(java.util.Arrays.asList(
            "open_app", "open_url", "tap", "long_press", "type", "scroll", "swipe", "press", "wait", "wait_for", "find"));
    private static final Pattern BLANK = Pattern.compile("^\\{([A-Za-z0-9 _-]{1,30})\\}$");
    /** Runs noted per chat (the latest first), and steps per run. */
    private static final int KEEP_RUNS = 3, KEEP_STEPS = 80;
    private static final Map<String, Deque<List<JSONObject>>> noted = new ConcurrentHashMap<>();

    private Routines() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences("routines", Context.MODE_PRIVATE);
    }

    // ------------------------------------------------------------ saved routines

    static synchronized List<JSONObject> all(Context c) {
        List<JSONObject> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(sp(c).getString("all", "[]"));
            for (int i = 0; i < a.length(); i++) out.add(a.getJSONObject(i));
        } catch (JSONException ignored) {
        }
        return out;
    }

    private static synchronized void store(Context c, List<JSONObject> list) {
        sp(c).edit().putString("all", new JSONArray(list).toString()).apply();
        Listeners.changed();
    }

    static JSONObject find(Context c, String name) {
        String want = name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
        for (JSONObject r : all(c)) if (r.optString("name").trim().toLowerCase(Locale.ROOT).equals(want)) return r;
        return null;
    }

    static synchronized void delete(Context c, String name) {
        List<JSONObject> list = all(c);
        for (int i = list.size() - 1; i >= 0; i--) if (list.get(i).optString("name").equalsIgnoreCase(name)) list.remove(i);
        store(c, list);
    }

    /** After a run: when, and how it went (0: done, else the step it stopped at). */
    static synchronized void ran(Context c, String name, int stoppedAt) {
        List<JSONObject> list = all(c);
        try {
            for (JSONObject r : list) if (r.optString("name").equalsIgnoreCase(name)) {
                r.put("ran", System.currentTimeMillis()).put("stopped", stoppedAt).put("runs", r.optInt("runs") + 1);
            }
        } catch (JSONException ignored) {
        }
        store(c, list);
    }

    /** Saves a routine; null when saved, else what's wrong (for the agent). */
    static synchronized String save(Context c, String name, String about, JSONArray steps, boolean replace) throws JSONException {
        name = name == null ? "" : name.trim();
        if (name.isEmpty() || name.length() > 60) return "Give the routine a short name (up to 60 characters).";
        String wrong = check(steps);
        if (wrong != null) return wrong;
        List<JSONObject> list = all(c);
        int at = -1;
        for (int i = 0; i < list.size(); i++) if (list.get(i).optString("name").equalsIgnoreCase(name)) at = i;
        if (at >= 0 && !replace) return "There's already a routine called \"" + name + "\". Pick another name, or "
                + "set replace: true if the user wants that one replaced.";
        JSONObject r = new JSONObject().put("name", name).put("about", about == null ? "" : about.trim())
                .put("steps", steps).put("saved", System.currentTimeMillis());
        if (at >= 0) list.set(at, r);
        else list.add(r);
        store(c, list);
        return null;
    }

    /** What's wrong with these steps as a routine, or null. */
    static String check(JSONArray steps) throws JSONException {
        if (steps == null || steps.length() == 0) return "A routine needs steps.";
        if (steps.length() > MAX_STEPS) return "A routine has at most " + MAX_STEPS + " steps.";
        for (int i = 0; i < steps.length(); i++) {
            JSONObject st = steps.optJSONObject(i);
            String n = "Step " + (i + 1) + ": ";
            if (st == null) return n + "not an object.";
            String action = st.optString("action");
            if (!ACTIONS.contains(action)) return n + "unknown action \"" + action + "\". A step is one of " + ACTIONS + ".";
            if (i == 0 && !"open_app".equals(action) && !"open_url".equals(action))
                return n + "a routine starts from a known place: its first step is open_app (or open_url).";
            if (st.has("element"))
                return n + "element ids change with every read_screen. Name the target by its \"text\" instead.";
            if (st.has("until") && st.optJSONObject("until") == null)
                return n + "\"until\" is an object, as in wait_for: {\"text\": \"Sent\"}.";
            for (String k : new String[]{"x", "y", "x1", "y1", "x2", "y2", "ms", "nth", "of", "duration_ms"})
                if (st.has(k) && !(st.opt(k) instanceof Number)) return n + "\"" + k + "\" is a number.";
            switch (action) {
                case "open_app":
                    if (st.optString("app").isEmpty()) return n + "say which app.";
                    break;
                case "open_url":
                    if (st.optString("url").isEmpty()) return n + "say which url.";
                    break;
                case "tap":
                case "long_press":
                    if (st.optString("text").isEmpty() && st.optString("id").isEmpty() && !(st.has("x") && st.has("y")))
                        return n + "say what to tap by its \"text\" (or x and y when it has none).";
                    break;
                case "type": {
                    String t = st.optString("text");
                    if (!BLANK.matcher(t).matches()) return n + "typed text is a blank, filled in at each run: \"text\": "
                            + "\"{message}\" (a short name in braces), never the text itself.";
                    break;
                }
                case "swipe":
                    if (!(st.has("x1") && st.has("y1") && st.has("x2") && st.has("y2"))) return n + "a swipe needs x1, y1, x2 and y2.";
                    break;
                case "press":
                    if (st.optString("button").isEmpty()) return n + "say which button.";
                    break;
                case "find":
                    if (st.optString("text").isEmpty()) return n + "say which text to scroll to.";
                    break;
                default:
            }
        }
        return null;
    }

    /** The blanks a routine's steps type into, in order (without the braces). */
    static List<String> blanks(JSONArray steps) {
        LinkedHashSet<String> out = new LinkedHashSet<>();
        for (int i = 0; steps != null && i < steps.length(); i++) {
            JSONObject st = steps.optJSONObject(i);
            if (st == null || !"type".equals(st.optString("action"))) continue;
            Matcher m = BLANK.matcher(st.optString("text"));
            if (m.matches()) out.add(m.group(1));
        }
        return new ArrayList<>(out);
    }

    /** A blank's name if this is one ("{message}" → "message"), else null. */
    static String blank(String text) {
        Matcher m = BLANK.matcher(text == null ? "" : text);
        return m.matches() ? m.group(1) : null;
    }

    /** For the agent's tool description: the routines there are, by name. */
    static String names(Context c) {
        List<String> n = new ArrayList<>();
        for (JSONObject r : all(c)) {
            List<String> b = blanks(r.optJSONArray("steps"));
            n.add("\"" + r.optString("name") + "\"" + (b.isEmpty() ? "" : " (blanks: " + android.text.TextUtils.join(", ", b) + ")"));
            if (n.size() >= 30) break;
        }
        return android.text.TextUtils.join(", ", n);
    }

    // ------------------------------------------------------------ actions noted while a run goes

    private static String key(String chat) {
        return chat == null ? "" : chat;
    }

    /** A chat's run starts: a new list of its actions. */
    static void runStarted(String chat) {
        Deque<List<JSONObject>> runs = noted.computeIfAbsent(key(chat), k -> new ArrayDeque<>());
        synchronized (runs) {
            if (runs.peekFirst() != null && runs.peekFirst().isEmpty()) return;
            runs.addFirst(new ArrayList<>());
            while (runs.size() > KEEP_RUNS) runs.removeLast();
        }
    }

    static void note(String chat, JSONObject step) {
        Deque<List<JSONObject>> runs = noted.computeIfAbsent(key(chat), k -> new ArrayDeque<>());
        synchronized (runs) {
            if (runs.isEmpty()) runs.addFirst(new ArrayList<>());
            List<JSONObject> run = runs.peekFirst();
            run.add(step);
            while (run.size() > KEEP_STEPS) run.remove(0);
        }
    }

    /** The last noted step waited for something after it (a do_steps "until"): so does its routine step. */
    static void untilLast(String chat, JSONObject until) {
        Deque<List<JSONObject>> runs = noted.get(key(chat));
        if (runs == null) return;
        synchronized (runs) {
            List<JSONObject> run = runs.peekFirst();
            if (run == null || run.isEmpty()) return;
            try {
                run.get(run.size() - 1).put("until", until);
            } catch (JSONException ignored) {
            }
        }
    }

    static void forget(String chat) {
        noted.remove(key(chat));
    }

    /** The steps noted in this chat: its current run, and the ones before it, as routine steps for the agent. */
    static String noted(String chat) {
        Deque<List<JSONObject>> runs = noted.get(key(chat));
        StringBuilder b = new StringBuilder();
        if (runs != null) synchronized (runs) {
            int i = 0;
            for (List<JSONObject> run : runs) {
                if (run.isEmpty()) {
                    i++;
                    continue;
                }
                b.append(i == 0 ? "This run" : i == 1 ? "The run before" : "Two runs before").append(":\n");
                for (JSONObject st : run) b.append(st).append('\n');
                i++;
            }
        }
        if (b.length() == 0) return "No phone actions noted in this chat yet (Buddy notes them while a run goes, "
                + "and keeps the last " + KEEP_RUNS + " runs until Buddy restarts).";
        return b + "\nThese are the actions as Buddy saw them, in routine form. To save a routine, pick the ones the "
                + "task needs (leave out detours and mistakes), and call routine with action \"save\". In every "
                + "\"type\" step replace the text with a blank such as \"{message}\": nothing typed is kept, the "
                + "user or you fill it in at each run. Add \"until\" to a step that must wait for something (as in "
                + "wait_for) when the next screen takes a while.";
    }

    /** Pages that show the list follow changes. */
    /** The routine running now (from Settings → Routines or a paired device), or null: one at a time, they share a screen. */
    static final java.util.concurrent.atomic.AtomicReference<String> RUNNING = new java.util.concurrent.atomic.AtomicReference<>();

    static final class Listeners {
        private static final List<Runnable> all = new java.util.concurrent.CopyOnWriteArrayList<>();

        static void add(Runnable r) {
            all.add(r);
        }

        static void remove(Runnable r) {
            all.remove(r);
        }

        private static final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());

        static void changed() {
            for (Runnable r : all) main.post(r);
        }
    }
}
