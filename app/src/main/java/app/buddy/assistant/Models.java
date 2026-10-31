package app.buddy.assistant;

/** Models offered per agent. "" means the agent's own default. */
final class Models {
    static final String[][] CLAUDE = {
            {"", "Default"},
            {"claude-opus-5-5", "Opus 5.5"},
            {"claude-sonnet-5-5", "Sonnet 5.5"},
            {"claude-haiku-5-5", "Haiku 5.5"},
            {"claude-haiku-4-5-20251001", "Haiku 4.5"},
            {"claude-fable-5-1", "Fable 5.1"},
    };
    /** Used until the helper reports Codex's own model list. */
    static final String[][] CODEX = {
            {"", "Default"},
            {"gpt-6.1-sol", "GPT-6.1-Sol"},
            {"gpt-6-astra", "GPT-6-Astra"},
            {"gpt-6-luna", "GPT-6-Luna"},
    };
    /** Used until OpenCode reports its own list (`opencode models`, which includes the free ones). */
    static final String[][] OPENCODE = {
            {"", "Default"},
            {"opencode/big-pickle", "opencode/big-pickle"},
    };
    /** Used until Antigravity reports its own list (`agy models`, which depends on the plan). */
    static final String[][] AGY = {
            {"", "Default"},
            {"gemini-3.8-flash-high", "Gemini 3.8 Flash (High)"},
            {"gemini-3.8-flash-medium", "Gemini 3.8 Flash (Medium)"},
            {"gemini-3.8-flash-low", "Gemini 3.8 Flash (Low)"},
    };
    /** Used until Grok reports its own list (`grok models`). */
    static final String[][] GROK = {
            {"", "Default"},
            {"grok-4.7", "grok-4.7"},
    };
    private static String[][] codexLive, opencodeLive, agyLive, grokLive;

    private Models() {
    }

    private static long refreshedAt;

    /** Re-reads the agents' model lists (new free OpenCode models appear often) when the picker opens, at most every
     *  10 minutes; the helper's answer updates the lists. The API key services' lists too. */
    static void refresh(android.content.Context c) {
        Providers.refresh(c);
        long now = System.currentTimeMillis();
        if (now - refreshedAt < 600_000 || !Linux.installed(c)) return;
        refreshedAt = now;
        try {
            Helper.run(c, "ping");
        } catch (Exception ignored) {
        }
    }

    /** The agent's own models (its plan's, or OpenCode's), "" (its default) first. */
    static String[][] forBackend(String backend) {
        if ("opencode".equals(backend)) return withDefault(opencodeLive != null ? opencodeLive : OPENCODE);
        if ("agy".equals(backend)) return withDefault(agyLive != null ? agyLive : AGY);
        if ("grok".equals(backend)) return withDefault(grokLive != null ? grokLive : GROK);
        if (!"codex".equals(backend)) return withDefault(CLAUDE);
        return withDefault(codexLive != null ? codexLive : CODEX);
    }

    /**
     * Everything a model picker offers for this agent: Default, its plan's models (when it's signed in to a plan, or
     * OpenCode's own), then each API key service it can use, under the service's name: rows {id, name}, a heading
     * {null, service name}. Service models are "@service/model".
     */
    static java.util.List<String[]> options(String backend, boolean services) {
        java.util.List<String[]> out = new java.util.ArrayList<>();
        String[][] own = forBackend(backend);
        boolean plan = !("claude".equals(backend) || "codex".equals(backend)) || SetupState.planSignedIn(BuddyApp.context(), backend);
        for (String[] m : own) if (m[0].isEmpty() || plan) out.add(m);
        if (!services) return out;
        for (Providers.P p : Providers.forAgent(backend)) {
            org.json.JSONArray models = p.offered();
            if (models.length() == 0) continue;
            out.add(new String[]{null, p.name});
            for (int i = 0; i < models.length(); i++) {
                org.json.JSONObject m = models.optJSONObject(i);
                out.add(new String[]{Providers.ref(p, m.optString("id")), m.optString("name")});
            }
        }
        return out;
    }

    /** What a chat on Default uses with this agent: "" (its plan's default) or "@service/model". */
    static String agentDefault(String backend) {
        String d = Prefs.agentDefault(BuddyApp.context(), backend);
        Providers.P p = Providers.of(d);
        if (p == null || !p.fits(backend)) return "";
        String m = Providers.model(d);
        return Providers.ref(p, m.isEmpty() ? p.defaultModel() : m);
    }

    /** The chat's model as it will run: the one picked, or the agent's default. */
    static String effective(Sessions.Session s) {
        return s.model.isEmpty() ? agentDefault(s.backend) : s.model;
    }

    /** A service model's context size in tokens as its service said, or 0 when not known. */
    static long window(String ref) {
        Providers.P p = Providers.of(ref);
        org.json.JSONObject m = p == null ? null : p.modelInfo(Providers.model(ref));
        return m == null ? 0 : m.optLong("window");
    }

    /** The line under a model in the pickers: its id (or what Default means), with its context size when known. */
    static String detail(String backend, String id, String defaultHint) {
        if (id.isEmpty()) return defaultHint;
        long w = window(id);
        String shown = Providers.of(id) != null ? Providers.model(id) : id;
        return w > 0 ? shown + " · " + BuddyApp.str(R.string.models_context, tokens(w)) : shown;
    }

    /** 1048576 → "1M", 200000 → "200K", 8104 → "8.1K". */
    static String tokens(long n) {
        if (n >= 1_000_000) return trim(String.format(java.util.Locale.ROOT, "%.1f", n / 1_000_000.0)) + "M";
        if (n >= 10_000) return Math.round(n / 1000.0) + "K";
        if (n >= 1000) return trim(String.format(java.util.Locale.ROOT, "%.1f", n / 1000.0)) + "K";
        return String.valueOf(n);
    }

    private static String trim(String s) {
        return s.endsWith(".0") ? s.substring(0, s.length() - 2) : s;
    }

    /** How full the chat's context is (percent), or -1 when its agent hasn't said. */
    static int contextPercent(Sessions.Session s) {
        if (s.ctxWindow <= 0 || s.ctxUsed <= 0) return -1;
        return (int) Math.min(100, Math.round(s.ctxUsed * 100.0 / s.ctxWindow));
    }

    /** A copy of a list with its "" row (the agent's default) labeled in the user's language; the lists keep "Default". */
    private static String[][] withDefault(String[][] list) {
        String[][] m = list.clone();
        for (int i = 0; i < m.length; i++) if (m[i][0].isEmpty()) m[i] = new String[]{"", BuddyApp.str(R.string.models_default)};
        return m;
    }

    /** OpenCode's models as JSON [[id, name], …] ("provider/model" for both). */
    static void setOpencode(String json) {
        String[][] m = parse(json);
        if (m != null) opencodeLive = m;
    }

    /** Antigravity's models as JSON [[id, name], …]. */
    static void setAgy(String json) {
        String[][] m = parse(json);
        if (m != null) agyLive = m;
    }

    /** Grok's models as JSON [[id, name], …] (its ids are its names). */
    static void setGrok(String json) {
        String[][] m = parse(json);
        if (m != null) grokLive = m;
    }

    /** Codex's model list as reported by the helper: JSON [[slug, name], …]. */
    static void setCodex(String json) {
        String[][] m = parse(json);
        if (m != null) codexLive = m;
    }

    private static String[][] parse(String json) {
        try {
            org.json.JSONArray a = new org.json.JSONArray(json);
            if (a.length() == 0) return null;
            String[][] m = new String[a.length() + 1][];
            m[0] = new String[]{"", "Default"};
            for (int i = 0; i < a.length(); i++) {
                org.json.JSONArray e = a.getJSONArray(i);
                m[i + 1] = new String[]{e.getString(0), e.getString(1)};
            }
            return m;
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * Effort levels the chat's agent accepts ([value, label]; "" is the agent's default), or null if Buddy can't set it.
     * A service model that lists its levels (DeepSeek: low, high, max) keeps only those, besides Auto.
     */
    static String[][] efforts(Sessions.Session s) {
        String[][] all = agentEfforts(s.backend);
        String ref = effective(s);
        Providers.P p = Providers.of(ref);
        org.json.JSONObject m = p == null ? null : p.modelInfo(Providers.model(ref));
        org.json.JSONArray e = m == null ? null : m.optJSONArray("efforts");
        if (all == null || e == null || e.length() == 0) return all;
        java.util.Set<String> has = new java.util.HashSet<>();
        for (int j = 0; j < e.length(); j++) has.add(e.optString(j));
        java.util.List<String[]> out = new java.util.ArrayList<>();
        for (String[] x : all) if (x[0].isEmpty() || has.contains(x[0])) out.add(x);
        return out.toArray(new String[0][]);
    }

    private static String[][] agentEfforts(String backend) {
        if ("claude".equals(backend)) return new String[][]{{"", BuddyApp.str(R.string.models_effort_auto)},
                {"low", BuddyApp.str(R.string.models_effort_low)}, {"medium", BuddyApp.str(R.string.models_effort_medium)},
                {"high", BuddyApp.str(R.string.models_effort_high)}, {"xhigh", BuddyApp.str(R.string.models_effort_xhigh)},
                {"max", BuddyApp.str(R.string.models_effort_max)}};
        if ("codex".equals(backend)) return new String[][]{{"", BuddyApp.str(R.string.models_effort_auto)},
                {"low", BuddyApp.str(R.string.models_effort_low)}, {"medium", BuddyApp.str(R.string.models_effort_medium)},
                {"high", BuddyApp.str(R.string.models_effort_high)}};
        // OpenCode: effort ("variants") depends on the model; Antigravity's model ids include it; Grok's depends on the
        // model too (it lists no levels)
        return null;
    }

    /** "agent · model · effort" for a chat's chip. */
    static String summary(Sessions.Session s) {
        int ctx = contextPercent(s); // only once it matters: past half full
        return s.brainName() + " · " + shownModel(s)
                + (s.effort.isEmpty() ? "" : " · " + BuddyApp.str(R.string.models_chip_effort, s.effort))
                + (ctx >= 50 ? " · " + BuddyApp.str(R.string.models_chip_context, ctx) : "");
    }

    /** The API key service the chat's model comes from (its name), or null for the agent's own. */
    static String via(Sessions.Session s) {
        Providers.P p = Providers.of(effective(s));
        return p == null ? null : p.name;
    }

    /** The chat's model for display: the one picked, or what "Default" really is when the agent told us. */
    static String shownModel(Sessions.Session s) {
        String eff = effective(s);
        Providers.P p = Providers.of(eff);
        if (p != null) return BuddyApp.str(R.string.models_via, serviceModelName(p, Providers.model(eff)), p.name);
        if (!s.model.isEmpty()) return label(s.backend, s.model);
        if (!s.actualModel.isEmpty()) return BuddyApp.str(R.string.models_name_default, nameOf(s.backend, s.actualModel));
        return BuddyApp.str(R.string.models_default_model);
    }

    private static String serviceModelName(Providers.P p, String model) {
        org.json.JSONObject m = p.modelInfo(model);
        return m == null || m.optString("name").isEmpty() ? model : m.optString("name");
    }

    /** What "Default" means for this chat, for the pickers. */
    static String defaultHint(Sessions.Session s) {
        String d = agentDefault(s.backend);
        Providers.P p = Providers.of(d);
        if (p != null) return BuddyApp.str(R.string.models_via, Providers.model(d), p.name);
        if (!s.actualModel.isEmpty()) return BuddyApp.str(R.string.models_default_now, nameOf(s.backend, s.actualModel));
        return defaultHint(s.backend);
    }

    /** What the agent's own default is, before it has said. */
    static String defaultHint(String backend) {
        if ("claude".equals(backend)) return BuddyApp.str(R.string.models_default_claude);
        if ("codex".equals(backend)) return BuddyApp.str(R.string.models_default_codex);
        if ("agy".equals(backend)) return BuddyApp.str(R.string.models_default_agy);
        if ("grok".equals(backend)) return BuddyApp.str(R.string.models_default_grok);
        return BuddyApp.str(R.string.models_default_opencode);
    }

    /** A model id the agent reported, by its name in the model list if it's there. */
    static String nameOf(String backend, String id) {
        String l = label(backend, id);
        return l.equals(id) ? pretty(id) : l;
    }

    /** "claude-opus-5-5-20261001" → "Opus 5.5"; other ids as they are. */
    static String pretty(String id) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("claude-(opus|sonnet|haiku|fable)-(\\d+)-(\\d+)").matcher(id);
        if (m.find()) return Character.toUpperCase(m.group(1).charAt(0)) + m.group(1).substring(1) + " " + m.group(2) + "." + m.group(3);
        return id;
    }

    static String label(String backend, String model) {
        if (model == null || model.isEmpty()) return BuddyApp.str(R.string.models_default);
        Providers.P p = Providers.of(model);
        if (p != null) return BuddyApp.str(R.string.models_via, serviceModelName(p, Providers.model(model)), p.name);
        for (String[] m : forBackend(backend)) if (m[0].equals(model)) return m[1];
        return model;
    }
}
