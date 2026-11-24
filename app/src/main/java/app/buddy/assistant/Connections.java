package app.buddy.assistant;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * What Settings → Agents and models shows about a service (see Providers): which agents run it, what it costs, the check
 * Buddy makes when one is added (does the model answer, does it call a tool, does Buddy's part of the first request
 * fit), and OpenRouter's balance. The services themselves, their keys and the agents' defaults stay where they were;
 * this only reads them, and the steps after saving or removing one are here so every way in does the same.
 */
final class Connections {
    interface Listener {
        /** A check ended or a balance came in (main thread). */
        void onConnectionsChanged();
    }

    /** The agents that can use a service (Providers.P.fits). */
    static final String[] AGENTS = {"claude", "codex", "opencode", "pi"};

    private static final List<Listener> listeners = new ArrayList<>();
    private static final Handler main = new Handler(Looper.getMainLooper());
    /** Services being checked now. */
    private static final java.util.Set<String> checking = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private Connections() {
    }

    static void addListener(Listener l) {
        if (!listeners.contains(l)) listeners.add(l);
    }

    static void removeListener(Listener l) {
        listeners.remove(l);
    }

    static void changed() {
        main.post(() -> {
            for (Listener l : new ArrayList<>(listeners)) l.onConnectionsChanged();
        });
    }

    /** Its own file: the results belong to keys on this phone, so they stay out of backups (see Backup.PREFS). */
    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("connections", Context.MODE_PRIVATE);
    }

    /** The key came from "Sign in with OpenRouter": OpenRouter made it for Buddy, and removing it is signing out. */
    static boolean signedIn(Context c, String id) {
        return sp(c).getBoolean("oauth_" + id, false);
    }

    static void setSignedIn(Context c, String id, boolean on) {
        sp(c).edit().putBoolean("oauth_" + id, on).apply();
    }

    /** The last check's results (see check), or null if it was never checked. */
    static JSONObject lastCheck(Context c, String id) {
        try {
            String s = sp(c).getString("check_" + id, null);
            return s == null ? null : new JSONObject(s);
        } catch (Exception e) {
            return null;
        }
    }

    static boolean checking(String id) {
        return checking.contains(id);
    }

    /** The model answered but didn't call the tool: it can't use the phone. */
    static boolean noTools(Context c, String id) {
        JSONObject j = lastCheck(c, id);
        JSONObject a = j == null ? null : j.optJSONObject("answers"), t = j == null ? null : j.optJSONObject("tools");
        return a != null && a.optBoolean("ok") && t != null && !t.optBoolean("ok");
    }

    /** The model didn't answer at all when last checked. */
    static boolean failed(Context c, String id) {
        JSONObject j = lastCheck(c, id);
        JSONObject a = j == null ? null : j.optJSONObject("answers");
        return a != null && !a.optBoolean("ok");
    }

    static boolean isOpenRouter(Providers.P p) {
        String h = p.host().toLowerCase(Locale.ROOT);
        return h.equals("openrouter.ai") || h.endsWith(".openrouter.ai");
    }

    // ------------------------------------------------------------------ which agent, which model

    /**
     * The agent this service works for: the default agent if its chats use the service, else the first installed
     * agent whose chats do; if none does yet, the default agent if it could, else the first installed one that could,
     * else the first that could ("" if none can). It's the agents' own defaults (Prefs.agentDefault) that decide;
     * nothing else is stored. (An agent that isn't installed doesn't count as using it: a new key is every plan-less
     * agent's default, installed or not, see saved.)
     */
    static String runsIn(Context c, Providers.P p) {
        String backend = Prefs.backend(c);
        if (uses(c, backend, p)) return backend;
        for (String a : AGENTS) if (uses(c, a, p)) return a;
        if (p.fits(backend)) return backend;
        for (String a : AGENTS) if (p.fits(a) && SetupState.installed(c, a)) return a;
        for (String a : AGENTS) if (p.fits(a)) return a;
        return "";
    }

    /** Do this agent's chats on Default use this service, and is the agent there to run them? */
    static boolean uses(Context c, String agent, Providers.P p) {
        return SetupState.installed(c, agent) && defaultsTo(agent, p);
    }

    /** Is this service the agent's default (installed or not)? */
    static boolean defaultsTo(String agent, Providers.P p) {
        return p.id.equals(Providers.idOf(Models.agentDefault(agent)));
    }

    /** The model the agent's chats use from it: its default's, or the service's own default. */
    static String chatModel(Providers.P p, String agent) {
        String d = Models.agentDefault(agent);
        if (p.id.equals(Providers.idOf(d)) && !Providers.model(d).isEmpty()) return Providers.model(d);
        return p.defaultModel();
    }

    /**
     * The line under a service's name in Agents and models: "Runs in Claude Code, Pi" (the agents whose chats on
     * Default use it), or, while none does, "Can run in Claude Code, Codex, OpenCode, Pi" (the ones that could).
     */
    static String runs(Context c, Providers.P p) {
        List<String> use = new ArrayList<>();
        for (String a : AGENTS) if (!p.off && uses(c, a, p)) use.add(Account.nameOf(a));
        return use.isEmpty() ? c.getString(R.string.agents_can_run_in, agentNames(p))
                : c.getString(R.string.agents_runs_in, android.text.TextUtils.join(", ", use));
    }

    /**
     * What using it costs, in a word: "Private" for a server on this phone or the user's network, "Free" when the
     * pickers offer only its free models (OpenRouter's ":free"), else "Pay per use".
     */
    static String cost(Context c, Providers.P p) {
        if (p.local()) return c.getString(R.string.agents_cost_private);
        boolean freeOnly = !p.shown.isEmpty();
        for (String m : p.shown) freeOnly &= m.endsWith(":free");
        return c.getString(freeOnly ? R.string.agents_cost_free : R.string.agents_cost_pay);
    }

    /** The agents that can use it, by name: "Claude Code, Codex". */
    static String agentNames(Providers.P p) {
        List<String> names = new ArrayList<>();
        for (String a : AGENTS) if (p.fits(a)) names.add(Account.nameOf(a));
        return android.text.TextUtils.join(", ", names);
    }

    // ------------------------------------------------------------------ after saving or removing

    /**
     * After a service was saved (ServiceEditor, Sign in with OpenRouter): an agent with no plan uses it by default
     * so its chats run, the first-run guide's "Local model" makes it OpenCode's, the agents' sign-in state follows the
     * list, and a new or changed key is checked.
     */
    static void saved(Context c, Providers.P q, boolean check) {
        for (String a : AGENTS) {
            if (q.fits(a) && Prefs.agentDefault(c, a).isEmpty() && !"opencode".equals(a)
                    && !SetupState.ownModels(c, a)) Prefs.setAgentDefault(c, a, Providers.ref(q, q.defaultModel()));
        }
        // "Local model" was the first-run choice: OpenCode's chats use this server, not its free models
        if (Prefs.localSetup(c) && q.local() && q.fits("opencode") && Prefs.agentDefault(c, "opencode").isEmpty()) {
            Prefs.setAgentDefault(c, "opencode", Providers.ref(q, q.defaultModel()));
        }
        refreshAgents(c);
        if (check && !OnPhone.ID.equals(q.id)) check(c, q);
        changed();
    }

    /** Removes a service: its key, the chats that picked its models go back to Default, and what Buddy noted about it. */
    static void remove(Context c, String id) {
        Providers.remove(c, id);
        ChatHub hub = ChatHub.get(c);
        for (Sessions.Session s : new ArrayList<>(hub.sessions.all)) {
            if (id.equals(Providers.idOf(s.model))) hub.setModel(s, "");
        }
        sp(c).edit().remove("check_" + id).remove("oauth_" + id).apply();
        balances.remove(id);
        refreshAgents(c);
        changed();
    }

    /** Claude Code, Codex and Pi count as signed in with a service they can use: their state changes with the list. */
    static void refreshAgents(Context c) {
        Account.CLAUDE.refresh(c);
        Account.CODEX.refresh(c);
        Account.PI.refresh(c);
        ChatHub.get(c).changed();
    }

    // ------------------------------------------------------------------ the check

    static final String TOOL = "get_time";
    static final String ASK = "Reply with the single word OK.";
    static final String ASK_TOOL = "What time is it? Answer by calling the get_time tool.";

    /**
     * Checks the service in the background, the way the agent that runs it talks to it (Claude Code: Anthropic's
     * Messages; Codex: OpenAI's Responses; OpenCode and Pi: Chat Completions, or Messages with only that address), with
     * the model its chats use: (a) a one-line request, (b) one tool and a request to call it, and (c), only where the
     * service says the model's context size, whether Buddy's own part of the first request fits it. Each answer is
     * kept in the service's own words. A few tokens of API credit at most.
     */
    static void check(Context c, Providers.P p) {
        if (!checking.add(p.id)) return;
        changed();
        final Context app = c.getApplicationContext();
        new Thread(() -> {
            try {
                JSONObject r = run(app, p);
                sp(app).edit().putString("check_" + p.id, r.toString()).apply();
            } catch (Exception ignored) {
            } finally {
                checking.remove(p.id);
                changed();
            }
        }, "connection-check").start();
    }

    private static JSONObject run(Context c, Providers.P p) throws Exception {
        String agent = runsIn(c, p);
        String model = chatModel(p, agent);
        String api = api(p, agent);
        String key = Secrets.get(c, Providers.secretName(p.id));
        if (key == null) key = "";
        JSONObject out = new JSONObject().put("at", System.currentTimeMillis()).put("agent", agent).put("model", model)
                .put("api", api);
        if (model.isEmpty()) {
            out.put("answers", new JSONObject().put("ok", false).put("said", BuddyApp.str(R.string.conn_check_no_model)));
            return out;
        }
        String url = url(p, api);
        String[] a = post(url, key, "messages".equals(api), body(api, model, false, Relay.isOpenAi(url)).toString());
        boolean ok = a[0].startsWith("2");
        out.put("answers", new JSONObject().put("ok", ok).put("said", ok ? "" : said(a)));
        if (ok) { // (no answer at all: the tool request would only say the same again)
            String[] t = post(url, key, "messages".equals(api), body(api, model, true, Relay.isOpenAi(url)).toString());
            boolean called = t[0].startsWith("2") && calledTool(api, t[1]);
            out.put("tools", new JSONObject().put("ok", called).put("said", called ? ""
                    : t[0].startsWith("2") ? BuddyApp.str(R.string.conn_check_no_call) : said(t)));
        }
        long window = Models.window(Providers.ref(p, model));
        long need = buddyTokens(c);
        if (window > 0 && need > 0) {
            out.put("fits", new JSONObject().put("ok", need < window).put("need", need).put("window", window));
        }
        return out;
    }

    /** "messages", "responses" or "chat": how this agent sends its requests to the service (see buddy-run). */
    static String api(Providers.P p, String agent) {
        if ("claude".equals(agent) && !p.anthropic.isEmpty()) return "messages";
        if ("codex".equals(agent) && !p.openai.isEmpty()) return "responses";
        return !p.openai.isEmpty() ? "chat" : "messages";
    }

    static String url(Providers.P p, String api) {
        if ("messages".equals(api)) return trim(p.anthropic) + "/v1/messages";
        return trim(p.openai) + ("responses".equals(api) ? "/responses" : "/chat/completions");
    }

    private static String trim(String b) {
        return b.replaceAll("/+$", "");
    }

    /**
     * The request: one line to answer, or (tool) one tool to call, in the shape of the API. openAi: OpenAI's own API,
     * whose newer models turn down max_tokens in Chat Completions and want max_completion_tokens.
     */
    static JSONObject body(String api, String model, boolean tool, boolean openAi) throws Exception {
        String ask = tool ? ASK_TOOL : ASK;
        // room to think first: a reasoning model spends its first tokens on that, before the call
        int max = tool ? 512 : 32;
        JSONObject params = new JSONObject().put("type", "object").put("properties", new JSONObject());
        String desc = "Returns the current time on the user's phone.";
        JSONObject b = new JSONObject().put("model", model);
        if ("responses".equals(api)) {
            b.put("input", ask).put("max_output_tokens", max);
            if (tool) b.put("tools", new JSONArray().put(new JSONObject().put("type", "function").put("name", TOOL)
                    .put("description", desc).put("parameters", params)));
            return b;
        }
        b.put("chat".equals(api) && openAi ? "max_completion_tokens" : "max_tokens", max).put("messages", new JSONArray().put(new JSONObject().put("role", "user").put("content", ask)));
        if (!tool) return b;
        if ("messages".equals(api)) {
            b.put("tools", new JSONArray().put(new JSONObject().put("name", TOOL).put("description", desc)
                    .put("input_schema", params)));
        } else {
            b.put("tools", new JSONArray().put(new JSONObject().put("type", "function").put("function",
                    new JSONObject().put("name", TOOL).put("description", desc).put("parameters", params))));
        }
        return b;
    }

    /** Did the answer call the tool? */
    static boolean calledTool(String api, String body) {
        try {
            JSONObject o = new JSONObject(body);
            if ("messages".equals(api)) {
                JSONArray content = o.optJSONArray("content");
                for (int i = 0; content != null && i < content.length(); i++) {
                    JSONObject b = content.optJSONObject(i);
                    if (b != null && "tool_use".equals(b.optString("type"))) return true;
                }
                return false;
            }
            if ("responses".equals(api)) {
                JSONArray output = o.optJSONArray("output");
                for (int i = 0; output != null && i < output.length(); i++) {
                    JSONObject b = output.optJSONObject(i);
                    if (b != null && "function_call".equals(b.optString("type"))) return true;
                }
                return false;
            }
            JSONArray choices = o.optJSONArray("choices");
            JSONObject m = choices == null || choices.length() == 0 ? null : choices.optJSONObject(0).optJSONObject("message");
            if (m == null) return false;
            JSONArray calls = m.optJSONArray("tool_calls");
            return calls != null && calls.length() > 0 || m.optJSONObject("function_call") != null;
        } catch (Exception e) {
            return false;
        }
    }

    /** The service's own words for a failed request: "403 …", its error message or its whole answer. */
    private static String said(String[] r) {
        String t = Relay.errorText(r[1]);
        String said = t.isEmpty() ? r[1].trim() : t;
        String s = said.startsWith(r[0] + " ") ? said : (r[0] + " " + said).trim(); // (some repeat the status as their code)
        return s.length() > 400 ? s.substring(0, 400) + "…" : s;
    }

    /** {status code (or "" when it never answered), body}. The key never shows in either. */
    private static String[] post(String url, String key, boolean anthropic, String json) {
        HttpURLConnection con = null;
        try {
            con = (HttpURLConnection) new URL(url).openConnection();
            con.setRequestMethod("POST");
            con.setConnectTimeout(15_000);
            // a model on the user's own computer may load first
            con.setReadTimeout((Providers.isLocal(url) ? 300 : 60) * 1000);
            con.setInstanceFollowRedirects(false);
            con.setDoOutput(true);
            con.setRequestProperty("Content-Type", "application/json");
            if (anthropic) con.setRequestProperty("anthropic-version", "2023-06-01");
            if (Relay.xApiKey(anthropic, url)) con.setRequestProperty("x-api-key", key);
            else if (!key.isEmpty()) con.setRequestProperty("Authorization", "Bearer " + key); // a local server needs none
            try (OutputStream o = con.getOutputStream()) {
                o.write(json.getBytes(StandardCharsets.UTF_8));
            }
            int code = con.getResponseCode();
            InputStream in = code >= 400 ? con.getErrorStream() : con.getInputStream();
            return new String[]{String.valueOf(code), in == null ? "" : Relay.hideKey(read(in), key)};
        } catch (Exception e) {
            return new String[]{"", Relay.hideKey(String.valueOf(e.getMessage()), key)};
        } finally {
            if (con != null) con.disconnect();
        }
    }

    static String read(InputStream in) throws Exception {
        java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[16 * 1024];
        int n;
        while ((n = in.read(buf)) > 0 && o.size() < 1 << 20) o.write(buf, 0, n);
        return o.toString("UTF-8");
    }

    /**
     * Buddy's own part of an agent's first request, in tokens (about 4 characters each): the phone tools it offers
     * (as the user has them switched on) and Buddy's instructions. The agent adds its own prompt and tools on top,
     * which Buddy can't see, so this can only say a model is too small, not that it's big enough. 0 when Buddy's
     * service isn't running (it holds the tool list).
     */
    static long buddyTokens(Context c) {
        BuddyService svc = BuddyService.get();
        if (svc == null) return 0;
        try {
            long chars = svc.listTools().toString().length();
            try (InputStream in = c.getAssets().open("linux/system-prompt.md")) {
                chars += read(in).length();
            }
            return chars / 4;
        } catch (Exception e) {
            return 0;
        }
    }

    // ------------------------------------------------------------------ OpenRouter's balance

    /** Last balance line per service, and when it was asked for. */
    private static final java.util.Map<String, String> balances = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<String, Long> balanceAt = new java.util.concurrent.ConcurrentHashMap<>();

    /** What OpenRouter says is left on the key ("$4.20 left"), "" when it doesn't say, null until asked. */
    static String balance(String id) {
        return balances.get(id);
    }

    /** Free-model requests left today, as OpenRouter counts them for this key, or -1. */
    private static final java.util.Map<String, Integer> freeLeft = new java.util.concurrent.ConcurrentHashMap<>();

    static int freeLeft(String id) {
        Integer n = freeLeft.get(id);
        return n == null ? -1 : n;
    }

    /**
     * Asks OpenRouter about the key (GET /api/v1/key) at most every 5 minutes, in the background. Only the key's own
     * limit says what's left: a key without one (null) has the account's credit, which a normal key can't read
     * (/api/v1/credits wants a management key), so then there's no balance line at all.
     */
    static void refreshBalance(Context c, Providers.P p, boolean now) {
        if (!isOpenRouter(p) || p.off) return;
        Long at = balanceAt.get(p.id);
        long t = System.currentTimeMillis();
        if (!now && at != null && t - at < 300_000) return;
        balanceAt.put(p.id, t);
        final Context app = c.getApplicationContext();
        new Thread(() -> {
            String key = Secrets.get(app, Providers.secretName(p.id));
            if (key == null || key.isEmpty()) return;
            HttpURLConnection con = null;
            try {
                con = (HttpURLConnection) new URL("https://openrouter.ai/api/v1/key").openConnection();
                con.setConnectTimeout(15_000);
                con.setReadTimeout(30_000);
                con.setRequestProperty("Authorization", "Bearer " + key);
                if (con.getResponseCode() != 200) return; // (no line rather than a guess)
                JSONObject d = new JSONObject(read(con.getInputStream())).optJSONObject("data");
                if (d == null) return;
                balances.put(p.id, d.isNull("limit_remaining") ? "" : BuddyApp.str(R.string.conn_balance_left,
                        String.format(Locale.ROOT, "$%.2f", d.optDouble("limit_remaining"))));
                JSONObject free = d.optJSONObject("free_model_daily_requests");
                if (free != null && free.has("remaining")) freeLeft.put(p.id, free.optInt("remaining"));
                changed();
            } catch (Exception ignored) {
            } finally {
                if (con != null) con.disconnect();
            }
        }, "openrouter-key").start();
    }
}
