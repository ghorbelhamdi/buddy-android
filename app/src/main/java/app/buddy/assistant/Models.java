package app.buddy.assistant;

/** Models offered per agent. "" means the agent's own default. */
final class Models {
    /**
     * Each agent's own models as the agent itself reported them (the helper's ping): rows {id, name, effort levels
     * ("low,high", "" when it has none or didn't say)}, "" (its default) first. Nothing is built in: a model that
     * comes out tomorrow shows up as soon as the agent knows it.
     */
    private static final java.util.Map<String, String[][]> live = new java.util.concurrent.ConcurrentHashMap<>();
    private static final String[][] NONE = {{"", "Default", ""}};
    /** The model Claude Code's own default is right now (an id), "" until it has said. */
    private static volatile String claudeDefault = "";

    private Models() {
    }

    private static long refreshedAt;

    /** Re-reads the agents' model lists (new free OpenCode models appear often) when the picker opens, at most every
     *  10 minutes; the helper's answer updates the lists. The API key services' lists too. */
    static void refresh(android.content.Context c) {
        refresh(c, null);
    }

    /** changed: an API key service's list came back different (see Providers.refresh). */
    static void refresh(android.content.Context c, Runnable changed) {
        Providers.refresh(c, changed);
        long now = System.currentTimeMillis();
        if (now - refreshedAt < 600_000 || !Linux.installed(c)) return;
        refreshedAt = now;
        try {
            Helper.run(c, "ping");
        } catch (Exception ignored) {
        }
    }

    private static String key(String backend) {
        return "codex".equals(backend) || "opencode".equals(backend) || "agy".equals(backend) || "grok".equals(backend)
                ? backend : "claude";
    }

    /** The agent's own models (its plan's, or OpenCode's), "" (its default) first; only that until it has reported. */
    static String[][] forBackend(String backend) {
        String[][] l = live.get(key(backend));
        return withDefault(l != null ? l : NONE);
    }

    /** Does the agent's own list have this model? */
    static boolean has(String backend, String id) {
        return row(backend, id) != null;
    }

    /** A real model id of this agent, as an example for the "other" field ("…" until it has reported). */
    static String example(String backend) {
        String[][] l = live.get(key(backend));
        return l != null && l.length > 1 ? l[1][0] : "…";
    }

    /** The row of a model in the agent's list; a dated id ("claude-opus-5-5-20261001") finds its undated row. */
    private static String[] row(String backend, String id) {
        String[][] l = live.get(key(backend));
        if (l == null || id == null || id.isEmpty()) return null;
        for (String[] m : l) if (m[0].equals(id)) return m;
        String[] best = null; // the longest match: "claude-opus-5-5-2026…" is Opus 5.5's, not Opus 5's
        for (String[] m : l)
            if (!m[0].isEmpty() && id.startsWith(m[0] + "-") && (best == null || m[0].length() > best[0].length())) best = m;
        return best;
    }

    /**
     * Everything a model picker offers for this agent: Default, its plan's models (when it's signed in to a plan, or
     * OpenCode's own), then each API key service it can use, under the service's name: rows {id, name}, a heading
     * {null, service name}. Service models are "@service/model".
     */
    static java.util.List<String[]> options(String backend, boolean services) {
        java.util.List<String[]> out = new java.util.ArrayList<>();
        String[][] own = forBackend(backend);
        boolean plan = !("claude".equals(backend) || "codex".equals(backend)) || SetupState.ownModels(BuddyApp.context(), backend);
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

    /**
     * What a chat on Default uses with this agent, as the user set it (Settings → Chats on Default): "" (the agent's
     * own default), one of the agent's own models, or "@service/model".
     */
    static String agentDefault(String backend) {
        String d = Prefs.agentDefault(BuddyApp.context(), backend);
        if (d.isEmpty()) return "";
        if (!d.startsWith("@")) return d; // one of the agent's own models (issue #20)
        Providers.P p = Providers.of(d);
        if (p == null || p.off || !p.fits(backend)) return ""; // gone, or switched off for now
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

    /** An agent's model list as it reported it: JSON [[id, name, efforts?], …]. An empty or broken one changes nothing. */
    static void set(String backend, String json) {
        try {
            org.json.JSONArray a = new org.json.JSONArray(json);
            if (a.length() == 0) return;
            String[][] m = new String[a.length() + 1][];
            m[0] = NONE[0];
            for (int i = 0; i < a.length(); i++) {
                org.json.JSONArray e = a.getJSONArray(i);
                m[i + 1] = new String[]{e.getString(0), e.getString(1), e.optString(2, "")};
            }
            live.put(key(backend), m);
        } catch (Exception ignored) {
        }
    }

    /** What Claude Code's default model is right now (an id from its own list). */
    static void setClaudeDefault(String id) {
        if (id != null && !id.isEmpty()) claudeDefault = id;
    }

    /**
     * Effort levels the chat's agent accepts ([value, label]; "" is the agent's default), or null if Buddy can't set it.
     * A service model that lists its levels (DeepSeek: low, high, max) keeps only those, besides Auto.
     */
    static String[][] efforts(Sessions.Session s) {
        String ref = effective(s);
        Providers.P p = Providers.of(ref);
        java.util.List<String> levels;
        if (p != null) {
            // a service's model: what the agent accepts at all, less what the service says the model lacks
            levels = agentEfforts(s.backend);
            org.json.JSONObject m = p.modelInfo(Providers.model(ref));
            org.json.JSONArray e = m == null ? null : m.optJSONArray("efforts");
            if (e != null && e.length() > 0) {
                java.util.Set<String> has = new java.util.HashSet<>();
                for (int j = 0; j < e.length(); j++) has.add(e.optString(j));
                levels.retainAll(has);
            }
        } else {
            // the agent's own: the chat's model, or the one its default is (as far as it has said)
            String id = !ref.isEmpty() ? ref : !s.actualModel.isEmpty() ? s.actualModel : defaultId(s.backend);
            String[] row = row(s.backend, id);
            levels = row != null ? split(row[2]) : agentEfforts(s.backend);
        }
        if (levels.isEmpty()) return null;
        String[][] out = new String[levels.size() + 1][];
        out[0] = new String[]{"", BuddyApp.str(R.string.models_effort_auto)};
        for (int i = 0; i < levels.size(); i++) out[i + 1] = new String[]{levels.get(i), effortLabel(levels.get(i))};
        return out;
    }

    /** The agent's default model, where known: Claude Code says which; the others list theirs first. */
    private static String defaultId(String backend) {
        if ("claude".equals(key(backend)) && !claudeDefault.isEmpty()) return claudeDefault;
        String[][] l = live.get(key(backend));
        return l != null && l.length > 1 ? l[1][0] : "";
    }

    /** Every effort level any of the agent's models reported, in the agent's order (none: Buddy can't set it). */
    private static java.util.List<String> agentEfforts(String backend) {
        java.util.List<String> out = new java.util.ArrayList<>();
        String[][] l = live.get(key(backend));
        if (l != null) for (String[] m : l) for (String e : split(m[2])) if (!out.contains(e)) out.add(e);
        return out;
    }

    private static java.util.List<String> split(String levels) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String e : levels.split(",")) if (!e.isEmpty()) out.add(e);
        return out;
    }

    /** A level's name: translated for the ones Buddy knows, the agent's own word for a new one ("ultra" → "Ultra"). */
    private static String effortLabel(String e) {
        switch (e) {
            case "low": return BuddyApp.str(R.string.models_effort_low);
            case "medium": return BuddyApp.str(R.string.models_effort_medium);
            case "high": return BuddyApp.str(R.string.models_effort_high);
            case "xhigh": return BuddyApp.str(R.string.models_effort_xhigh);
            case "max": return BuddyApp.str(R.string.models_effort_max);
            default: return Character.toUpperCase(e.charAt(0)) + e.substring(1);
        }
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
        if (!eff.isEmpty()) return label(s.backend, eff); // the chat's pick, or the user's default for the agent
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
        if (!d.isEmpty()) return label(s.backend, d);
        if (!s.actualModel.isEmpty()) return BuddyApp.str(R.string.models_default_now, nameOf(s.backend, s.actualModel));
        return defaultHint(s.backend);
    }

    /** What the agent's own default is, before it has said. */
    static String defaultHint(String backend) {
        if ("claude".equals(backend) && !claudeDefault.isEmpty())
            return BuddyApp.str(R.string.models_default_now, nameOf(backend, claudeDefault));
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

    /** "claude-opus-5-5-20261001" → "Opus 5.5", "claude-fable-6" → "Fable 6" (any family); other ids as they are. */
    static String pretty(String id) {
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("^claude-([a-z]+)-(\\d{1,2})(?:-(\\d{1,2}))?(?:-\\d{6,})?$").matcher(id);
        if (!m.find()) return id;
        return Character.toUpperCase(m.group(1).charAt(0)) + m.group(1).substring(1) + " " + m.group(2)
                + (m.group(3) == null ? "" : "." + m.group(3));
    }

    static String label(String backend, String model) {
        if (model == null || model.isEmpty()) return BuddyApp.str(R.string.models_default);
        Providers.P p = Providers.of(model);
        if (p != null) return BuddyApp.str(R.string.models_via, serviceModelName(p, Providers.model(model)), p.name);
        String[] row = row(backend, model);
        return row != null ? row[1] : pretty(model);
    }
}
