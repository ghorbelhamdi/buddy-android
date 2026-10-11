package app.buddy.assistant;

import android.content.Context;
import android.content.SharedPreferences;

import java.security.SecureRandom;

final class Prefs {
    private Prefs() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("buddy", Context.MODE_PRIVATE);
    }

    /** Shared secret for the local MCP server; generated once per install. */
    static synchronized String token(Context c) {
        String t = sp(c).getString("token", null);
        if (t == null) {
            byte[] b = new byte[24];
            new SecureRandom().nextBytes(b);
            StringBuilder sb = new StringBuilder();
            for (byte x : b) sb.append(String.format("%02x", x));
            t = sb.toString();
            sp(c).edit().putString("token", t).apply();
        }
        return t;
    }

    static int[] bubblePos(Context c) {
        return new int[]{sp(c).getInt("bx", -1), sp(c).getInt("by", 400)};
    }

    /** Agents installed in Buddy's Linux from the last helper ping ("claude,codex", "none"), or null if never answered. */
    static String helperStatus(Context c) {
        return sp(c).getString("helper", null);
    }

    static long helperCheckedAt(Context c) {
        return sp(c).getLong("helper_at", 0);
    }

    static void setHelperStatus(Context c, String status) {
        sp(c).edit().putString("helper", status).putLong("helper_at", System.currentTimeMillis()).apply();
    }

    /** Which agent answers the bubble: "claude" (default) or "codex". */
    static String backend(Context c) {
        return sp(c).getString("backend", "claude");
    }

    static void setBackend(Context c, String backend) {
        sp(c).edit().putString("backend", backend).apply();
    }

    /** Developer mode: the bubble's agent may run commands and edit files in ~/.buddy/work. */
    static boolean devMode(Context c) {
        return sp(c).getBoolean("dev", false);
    }

    static void setDevMode(Context c, boolean on) {
        sp(c).edit().putBoolean("dev", on).apply();
    }

    static int helperVersion(Context c) {
        return sp(c).getInt("helper_version", 1);
    }

    static void setHelperVersion(Context c, int v) {
        sp(c).edit().putInt("helper_version", v).apply();
    }

    /** Whether the floating bubble is shown. When off, it still appears for approval cards. */
    static boolean bubbleEnabled(Context c) {
        return sp(c).getBoolean("bubble", true);
    }

    static void setBubbleEnabled(Context c, boolean on) {
        sp(c).edit().putBoolean("bubble", on).apply();
    }

    /** Read replies aloud with text-to-speech. */
    static boolean speakReplies(Context c) {
        return sp(c).getBoolean("speak", false);
    }

    static void setSpeakReplies(Context c, boolean on) {
        sp(c).edit().putBoolean("speak", on).apply();
    }

    static boolean hadReply(Context c) {
        return sp(c).getBoolean("had_reply", false);
    }

    static void setHadReply(Context c) {
        if (!hadReply(c)) sp(c).edit().putBoolean("had_reply", true).apply();
    }

    /** Appearance: "system", "light" or "dark". */
    static String themeMode(Context c) {
        return sp(c).getString("theme_mode", "system");
    }

    static void setThemeMode(Context c, String m) {
        sp(c).edit().putString("theme_mode", m).apply();
    }

    /** Accent colour (opaque ARGB). */
    static int accent(Context c) {
        return sp(c).getInt("accent", 0xFFD97757);
    }

    static void setAccent(Context c, int color) {
        sp(c).edit().putInt("accent", color | 0xFF000000).apply();
    }

    /** Surface style: "warm", "neutral" or "black" (black applies in dark mode only). */
    static String surface(Context c) {
        return sp(c).getString("surface", "warm");
    }

    static void setSurface(Context c, String s) {
        sp(c).edit().putString("surface", s).apply();
    }

    /** Agents that are signed in, as reported by the helper ("claude,codex", "none"), or null. */
    static String signedAgents(Context c) {
        return sp(c).getString("signed", null);
    }

    static void setSignedAgents(Context c, String s) {
        sp(c).edit().putString("signed", s).apply();
    }

    /** Codex's model list from its cache in Buddy's Linux, as JSON [[slug, name], …]. */
    static String codexModels(Context c) {
        return sp(c).getString("codex_models", "[]");
    }

    static void setCodexModels(Context c, String json) {
        sp(c).edit().putString("codex_models", json).apply();
    }

    /** First-run guide finished (or skipped). */
    static boolean onboarded(Context c) {
        return sp(c).getBoolean("onboarded", false);
    }

    static void setOnboarded(Context c, boolean on) {
        sp(c).edit().putBoolean("onboarded", on).apply();
    }

    static int onboardStep(Context c) {
        return sp(c).getInt("onboard_step", 0);
    }

    static void setOnboardStep(Context c, int step) {
        sp(c).edit().putInt("onboard_step", step).apply();
    }

    /** Which agents the user wants: "claude", "codex" or "both". */
    static String agents(Context c) {
        return sp(c).getString("agents", "claude");
    }

    static void setAgents(Context c, String a) {
        sp(c).edit().putString("agents", a).apply();
    }

    /** Unused since Buddy has its own Linux (was: Termux setup watch). */
    static long setupWatchUntil(Context c) {
        return sp(c).getLong("watch_until", 0);
    }

    static void setSetupWatchUntil(Context c, long t) {
        sp(c).edit().putLong("watch_until", t).apply();
    }

    /** Last known state of an agent in Buddy's Linux: NEED_INSTALL, SIGNED_OUT or SIGNED_IN (null = never checked). */
    static String agentState(Context c, String agent) {
        return sp(c).getString("agent_" + agent, null);
    }

    static void setAgentState(Context c, String agent, String state) {
        sp(c).edit().putString("agent_" + agent, state).apply();
    }

    /** Keep a Claude Code session with Remote Control running inside Buddy's Linux. */
    static boolean remoteSession(Context c) {
        return sp(c).getBoolean("remote_session", false);
    }

    static void setRemoteSession(Context c, boolean on) {
        sp(c).edit().putBoolean("remote_session", on).apply();
    }

    /** Its name in the Claude app. */
    static String remoteName(Context c) {
        return sp(c).getString("remote_name", "Buddy");
    }

    static void saveBubblePos(Context c, int x, int y) {
        sp(c).edit().putInt("bx", x).putInt("by", y).apply();
    }
}
