package app.buddy.assistant;

import android.content.Context;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * API keys for other services (Settings → API keys): each with an Anthropic-style address (Claude Code, OpenCode), an
 * OpenAI-style one (OpenCode, and Codex when it has the Responses API), or both, and the models it lists. A chat picks
 * one of its models in the model sheet ("@id/model"); an agent can also use one by default. Keys stay in Secrets and
 * never enter Buddy's Linux: the agents talk to Relay, which adds them. Linux only gets $D/providers/&lt;id&gt;.json
 * (no key), so buddy-run knows how to point each agent there.
 */
final class Providers {
    static final class P {
        final String id;
        String name = "", anthropic = "", openai = "";
        /** The OpenAI-style address has only Chat Completions, not the Responses API Codex needs. */
        boolean chatOnly;
        /** [{id, name, window, efforts}] (see Relay.models). */
        JSONArray models = new JSONArray();
        /** The model a chat on Default uses when this service is an agent's default ("": the first listed). */
        String model = "";
        /** The models the pickers show (empty: all of them; OpenRouter lists hundreds). */
        java.util.Set<String> shown = new java.util.LinkedHashSet<>();

        P(String id) {
            this.id = id;
        }

        /** Can this agent use it? */
        boolean fits(String agent) {
            if ("claude".equals(agent)) return !anthropic.isEmpty();
            if ("codex".equals(agent)) return !openai.isEmpty() && !chatOnly;
            return "opencode".equals(agent) && (!anthropic.isEmpty() || !openai.isEmpty());
        }

        /** The models the pickers offer: the ones picked to show, or all. */
        JSONArray offered() {
            if (shown.isEmpty()) return models;
            JSONArray out = new JSONArray();
            for (int i = 0; i < models.length(); i++) {
                JSONObject m = models.optJSONObject(i);
                if (m != null && shown.contains(m.optString("id"))) out.put(m);
            }
            return out.length() == 0 ? models : out;
        }

        String defaultModel() {
            if (!model.isEmpty() || models.length() == 0) return model;
            return offered().optJSONObject(0).optString("id");
        }

        JSONObject modelInfo(String m) {
            for (int i = 0; i < models.length(); i++) {
                JSONObject o = models.optJSONObject(i);
                if (o != null && o.optString("id").equals(m)) return o;
            }
            return null;
        }

        String host() {
            String h = android.net.Uri.parse(!openai.isEmpty() ? openai : anthropic).getHost();
            return h == null ? "" : h;
        }
    }

    /** Presets for the editor: name, Anthropic-style address, OpenAI-style address, Chat Completions only. */
    static final String[][] PRESETS = {
            {"DeepSeek", "https://api.deepseek.com/anthropic", "https://api.deepseek.com", ""},
            {"OpenRouter", "https://openrouter.ai/api", "https://openrouter.ai/api/v1", "chat"},
            {"Anthropic", "https://api.anthropic.com", "", ""},
            {"OpenAI", "", "https://api.openai.com/v1", ""},
    };

    private static List<P> cache;

    private Providers() {
    }

    static synchronized List<P> all() {
        if (cache != null) return cache;
        cache = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(Prefs.providers(BuddyApp.context()));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                P p = new P(o.getString("id"));
                p.name = o.optString("name");
                p.anthropic = o.optString("anthropic");
                p.openai = o.optString("openai");
                p.chatOnly = o.optBoolean("chat_only");
                p.model = o.optString("model");
                JSONArray m = o.optJSONArray("models");
                if (m != null) p.models = m;
                JSONArray sh = o.optJSONArray("shown");
                for (int j = 0; sh != null && j < sh.length(); j++) p.shown.add(sh.getString(j));
                cache.add(p);
            }
        } catch (Exception ignored) {
        }
        return cache;
    }

    static synchronized P find(String id) {
        for (P p : all()) if (p.id.equals(id)) return p;
        return null;
    }

    static synchronized List<P> forAgent(String agent) {
        List<P> out = new ArrayList<>();
        for (P p : all()) if (p.fits(agent)) out.add(p);
        return out;
    }

    /** A chat's model when it's a service's: "@id/model". */
    static String ref(P p, String model) {
        return "@" + p.id + "/" + model;
    }

    /** "@id/model" → the service, or null (a plan model, or a service since removed). */
    static P of(String ref) {
        if (ref == null || !ref.startsWith("@") || ref.indexOf('/') < 0) return null;
        return find(ref.substring(1, ref.indexOf('/')));
    }

    /** "@id/model" → "model". */
    static String model(String ref) {
        return ref.substring(ref.indexOf('/') + 1);
    }

    static String secretName(String id) {
        return "provider_" + id;
    }

    /** Adds or updates a service (key: null keeps the saved one), then writes Linux's view of the list. */
    static synchronized void save(Context c, P p, String key) throws Exception {
        if (key != null) Secrets.put(c, secretName(p.id), key.trim());
        if (find(p.id) == null) all().add(p);
        store(c);
    }

    static synchronized void remove(Context c, String id) {
        P p = find(id);
        if (p == null) return;
        all().remove(p);
        Secrets.remove(c, secretName(id));
        for (Account a : Account.ALL) { // an agent that used it by default goes back to its own
            if (id.equals(idOf(Prefs.agentDefault(c, a.agent)))) Prefs.setAgentDefault(c, a.agent, "");
        }
        try {
            store(c);
        } catch (Exception ignored) {
        }
    }

    static String idOf(String ref) {
        return ref.startsWith("@") && ref.indexOf('/') > 0 ? ref.substring(1, ref.indexOf('/')) : "";
    }

    /** Saves the list (no keys) and gives Buddy's Linux one file per service: its kind of address and its models. */
    private static void store(Context c) throws Exception {
        JSONArray a = new JSONArray();
        for (P p : all()) a.put(json(p).put("models", p.models));
        Prefs.setProviders(c, a.toString());
        File dir = new File(Linux.root(c), "root/.buddy/providers");
        File[] old = dir.listFiles();
        if (old != null) for (File f : old) f.delete();
        if (!Linux.installed(c)) return;
        dir.mkdirs();
        for (P p : all()) {
            JSONObject windows = new JSONObject();
            for (int i = 0; i < p.models.length(); i++) {
                JSONObject m = p.models.getJSONObject(i);
                windows.put(m.getString("id"), new JSONObject().put("name", m.optString("name"))
                        .put("window", m.optLong("window")));
            }
            Linux.write(new File(dir, p.id + ".json"), new JSONObject().put("name", p.name)
                    .put("anthropic", !p.anthropic.isEmpty())
                    .put("openai", p.openai.isEmpty() ? "" : p.chatOnly ? "chat" : "responses")
                    .put("models", windows).toString());
        }
    }

    private static JSONObject json(P p) throws Exception {
        return new JSONObject().put("id", p.id).put("name", p.name).put("anthropic", p.anthropic).put("openai", p.openai)
                .put("chat_only", p.chatOnly).put("model", p.model).put("shown", new JSONArray(p.shown));
    }

    /** Asks the service for its models (OpenAI-style address first: it lists more) and keeps them. */
    static void fetch(Context c, P p) throws Exception {
        String key = Secrets.get(c, secretName(p.id));
        if (key == null) return;
        p.models = list(key, p.anthropic, p.openai);
        store(c);
    }

    /** The models at these addresses, with this key: straight from the app. */
    static JSONArray list(String key, String anthropic, String openai) throws Exception {
        Exception first = null;
        if (!openai.isEmpty()) {
            try {
                return Relay.models(false, key, openai);
            } catch (Exception e) {
                first = e;
            }
        }
        if (!anthropic.isEmpty()) {
            try {
                return Relay.models(true, key, anthropic);
            } catch (Exception e) {
                if (first == null) first = e;
            }
        }
        throw first != null ? first : new Exception(BuddyApp.str(R.string.signin_api_models_none));
    }

    /** Fresh model lists for every service, at most every 10 minutes (in the background). */
    static void refresh(Context c) {
        long now = System.currentTimeMillis();
        synchronized (Providers.class) {
            if (now - refreshedAt < 600_000 || all().isEmpty()) return;
            refreshedAt = now;
        }
        final Context app = c.getApplicationContext();
        new Thread(() -> {
            for (P p : new ArrayList<>(all())) {
                try {
                    fetch(app, p);
                } catch (Exception ignored) { // the list stays as it was
                }
            }
        }, "providers").start();
    }

    private static long refreshedAt;

    /**
     * For a Claude Code run outside a chat (the smart reviewer) when Claude Code has no plan: the relay settings for
     * its default service (or the first it can use), every model name mapped to that service's model. "" otherwise.
     */
    static String claudeEnv(Context c) {
        if (SetupState.planSignedIn(c, "claude")) return "";
        P p = of(Models.agentDefault("claude"));
        if (p == null && !forAgent("claude").isEmpty()) p = forAgent("claude").get(0);
        if (p == null) return "";
        String m = p.defaultModel();
        if (!m.matches("[A-Za-z0-9._:/-]+")) m = "";
        String models = m.isEmpty() ? "" : " ANTHROPIC_MODEL=" + m + " ANTHROPIC_DEFAULT_OPUS_MODEL=" + m
                + " ANTHROPIC_DEFAULT_SONNET_MODEL=" + m + " ANTHROPIC_DEFAULT_HAIKU_MODEL=" + m;
        return "env -u ANTHROPIC_API_KEY ANTHROPIC_BASE_URL=" + Relay.url(p.id, true) + " ANTHROPIC_AUTH_TOKEN="
                + Prefs.token(c) + models + " ";
    }

    /** A new id: short, from the name, unique. */
    static synchronized String newId(String name) {
        String base = name.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-|-$", "");
        if (base.isEmpty()) base = "key";
        if (base.length() > 20) base = base.substring(0, 20);
        String id = base;
        for (int n = 2; find(id) != null; n++) id = base + "-" + n;
        return id;
    }

    /**
     * The key saved per agent before services (2.2.19 builds before release): becomes a service, and the agent's default
     * if it had a default model. Runs once.
     */
    static synchronized void migrate(Context c) {
        for (String agent : new String[]{"claude", "codex"}) {
            String old = "api_key_" + agent;
            String key = Secrets.get(c, old);
            if (key == null) continue;
            String base = Prefs.apiBase(c, agent);
            P p = null;
            for (P q : all()) { // DeepSeek for both agents: one service with both addresses
                String h = android.net.Uri.parse(base).getHost();
                if (h != null && h.equals(q.host()) && key.equals(Secrets.get(c, secretName(q.id)))) p = q;
            }
            try {
                if (p == null) {
                    String host = android.net.Uri.parse(base).getHost();
                    String name = base.isEmpty() ? "claude".equals(agent) ? "Anthropic" : "OpenAI"
                            : host == null ? "API" : host.replaceFirst("^api\\.", "").replaceFirst("\\..*$", "");
                    name = name.substring(0, 1).toUpperCase(java.util.Locale.ROOT) + name.substring(1);
                    p = new P(newId(name));
                    p.name = name;
                }
                if ("claude".equals(agent)) p.anthropic = base.isEmpty() ? "https://api.anthropic.com" : base;
                else p.openai = base.isEmpty() ? "https://api.openai.com/v1" : base;
                for (String[] pr : PRESETS) { // a known service: its name and both its addresses
                    String h = android.net.Uri.parse(!pr[2].isEmpty() ? pr[2] : pr[1]).getHost();
                    if (h == null || !h.equals(p.host())) continue;
                    p.name = pr[0];
                    if (p.anthropic.isEmpty()) p.anthropic = pr[1];
                    if (p.openai.isEmpty()) p.openai = pr[2];
                    p.chatOnly = "chat".equals(pr[3]);
                }
                File f = new File(Linux.root(c), "root/.buddy/" + agent + "-key");
                String model = "";
                try {
                    model = new String(java.nio.file.Files.readAllBytes(f.toPath())).trim();
                } catch (Exception ignored) {
                }
                if (!model.isEmpty()) {
                    p.model = model;
                    Prefs.setAgentDefault(c, agent, ref(p, model));
                } else if (!Prefs.agentPlan(c, agent)) {
                    Prefs.setAgentDefault(c, agent, ref(p, ""));
                }
                save(c, p, key);
                Secrets.remove(c, old);
                Prefs.setApiBase(c, agent, "");
                f.delete();
                new File(Linux.root(c), "root/.buddy/" + agent + "-models").delete();
                Prefs.setKeyModels(c, agent, "");
            } catch (Exception ignored) {
            }
        }
    }
}
