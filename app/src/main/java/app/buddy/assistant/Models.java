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
     *  10 minutes; the helper's answer updates the lists. */
    static void refresh(android.content.Context c) {
        long now = System.currentTimeMillis();
        if (now - refreshedAt < 600_000 || !Linux.installed(c)) return;
        refreshedAt = now;
        try {
            Helper.run(c, "ping");
        } catch (Exception ignored) {
        }
    }

    static String[][] forBackend(String backend) {
        if ("opencode".equals(backend)) return withDefault(opencodeLive != null ? opencodeLive : OPENCODE);
        if ("agy".equals(backend)) return withDefault(agyLive != null ? agyLive : AGY);
        if ("grok".equals(backend)) return withDefault(grokLive != null ? grokLive : GROK);
        if (!"codex".equals(backend)) return withDefault(CLAUDE);
        return withDefault(codexLive != null ? codexLive : CODEX);
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

    /** Effort levels an agent accepts ([value, label]; "" is the agent's default), or null if Buddy can't set it. */
    static String[][] efforts(String backend) {
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
        return s.brainName() + " · " + shownModel(s)
                + (s.effort.isEmpty() ? "" : " · " + BuddyApp.str(R.string.models_chip_effort, s.effort));
    }

    /** The chat's model for display: the one picked, or what "Default" really is when the agent told us. */
    static String shownModel(Sessions.Session s) {
        if (!s.model.isEmpty()) return label(s.backend, s.model);
        if (!s.actualModel.isEmpty()) return BuddyApp.str(R.string.models_name_default, nameOf(s.backend, s.actualModel));
        return BuddyApp.str(R.string.models_default_model);
    }

    /** What "Default" means for this chat, for the pickers. */
    static String defaultHint(Sessions.Session s) {
        if (!s.actualModel.isEmpty()) return BuddyApp.str(R.string.models_default_now, nameOf(s.backend, s.actualModel));
        if ("claude".equals(s.backend)) return BuddyApp.str(R.string.models_default_claude);
        if ("codex".equals(s.backend)) return BuddyApp.str(R.string.models_default_codex);
        if ("agy".equals(s.backend)) return BuddyApp.str(R.string.models_default_agy);
        if ("grok".equals(s.backend)) return BuddyApp.str(R.string.models_default_grok);
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
        for (String[] m : forBackend(backend)) if (m[0].equals(model)) return m[1];
        return model;
    }
}
