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

    /** Which agent answers the bubble: "claude" (default), "codex", "opencode", "agy" or "grok". */
    static String backend(Context c) {
        return sp(c).getString("backend", "claude");
    }

    static void setBackend(Context c, String backend) {
        sp(c).edit().putString("backend", backend).apply();
    }

    /**
     * Agents (any MCP client, e.g. Claude Code testing Buddy) may tap and type in Buddy's own screens, except the
     * bubble, approval cards, App permissions and the developer switches. Off by default.
     */
    static boolean agentsTapBuddy(Context c) {
        return sp(c).getBoolean("agents_tap_buddy", false);
    }

    static void setAgentsTapBuddy(Context c, boolean on) {
        sp(c).edit().putBoolean("agents_tap_buddy", on).apply();
    }

    /** Notify when a new Buddy release is out (checked about once a day). */
    static boolean updateNotices(Context c) {
        return !Flavor.PLAY && sp(c).getBoolean("update_notices", true);
    }

    static void setUpdateNotices(Context c, boolean on) {
        sp(c).edit().putBoolean("update_notices", on).apply();
    }

    static long updateCheckedAt(Context c) {
        return sp(c).getLong("update_checked_at", 0);
    }

    static void setUpdateCheckedAt(Context c, long t) {
        sp(c).edit().putLong("update_checked_at", t).apply();
    }

    /** The last version an update notice was shown for (one notice per version). */
    static String updateNotified(Context c) {
        return sp(c).getString("update_notified", "");
    }

    static void setUpdateNotified(Context c, String v) {
        sp(c).edit().putString("update_notified", v).apply();
    }

    /** First-setup choice: apps the user hasn't set (and that aren't on Hard by default) start on Trust, else Hard. */
    static boolean autonomousDefault(Context c) {
        return sp(c).getBoolean("autonomous_default", false);
    }

    static void setAutonomousDefault(Context c, boolean on) {
        sp(c).edit().putBoolean("autonomous_default", on).apply();
    }

    /** Every app the user set a mode for: package → "trust", "hard" or "smart". */
    static java.util.Map<String, String> appSafetyAll(Context c) {
        java.util.Map<String, String> out = new java.util.TreeMap<>();
        for (java.util.Map.Entry<String, ?> e : sp(c).getAll().entrySet()) {
            if (e.getKey().startsWith("app_safety_") && e.getValue() instanceof String) {
                out.put(e.getKey().substring("app_safety_".length()), (String) e.getValue());
            }
        }
        return out;
    }

    /**
     * Linux tools in new chats (was developer mode, for every chat): what a new chat starts with (each chat then has
     * its own, Sessions.Session.dev), and whether the user's own sessions outside Buddy's chats may install apps.
     */
    static boolean devMode(Context c) {
        return sp(c).getBoolean("dev", false);
    }

    static void setDevMode(Context c, boolean on) {
        sp(c).edit().putBoolean("dev", on).apply();
    }

    /** The user agreed to the accessibility disclosure (shown before Android's accessibility settings). */
    static boolean a11yConsent(Context c) {
        return sp(c).getBoolean("a11y_consent_v3", false);
    }

    static void setA11yConsent(Context c) {
        sp(c).edit().putBoolean("a11y_consent_v3", true).apply();
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

    /**
     * What a tap on the bubble does: "panel" (open the chat panel), "talk" (start the microphone when no task runs)
     * or "app" (open the chat in the app). Before this was a choice, a switch turned "talk" on.
     */
    static String tapAction(Context c) {
        String a = sp(c).getString("tap_action", null);
        return a != null ? a : sp(c).getBoolean("tap_to_talk", false) ? "talk" : "panel";
    }

    static void setTapAction(Context c, String action) {
        sp(c).edit().putString("tap_action", action).apply();
    }

    /** The chat panel: "full" (the screen, clear of the edge gestures) or "compact" (smaller, more room at the edges). */
    static String panelSize(Context c) {
        return sp(c).getString("panel_size", "full");
    }

    static void setPanelSize(Context c, String size) {
        sp(c).edit().putString("panel_size", size).apply();
    }

    /** Buddy's face on the bubble and in the chats: "buddy" (the drawn one) or "picture" (one the user picked). */
    static String face(Context c) {
        return sp(c).getString("face", "buddy");
    }

    static void setFace(Context c, String face) {
        sp(c).edit().putString("face", face).apply();
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

    /** The "New: talk live with Codex" hint was shown (once, in a Codex chat; see CodexLive). */
    static boolean liveHintShown(Context c) {
        return sp(c).getBoolean("live_hint_shown", false);
    }

    static void setLiveHintShown(Context c) {
        sp(c).edit().putBoolean("live_hint_shown", true).apply();
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

    /** OpenCode's model list (`opencode models`), as JSON [[id, name], …]. */
    static String opencodeModels(Context c) {
        return sp(c).getString("opencode_models", "[]");
    }

    static void setOpencodeModels(Context c, String json) {
        sp(c).edit().putString("opencode_models", json).apply();
    }

    /** Antigravity's model list (`agy models`), as JSON [[id, name], …]. */
    static String agyModels(Context c) {
        return sp(c).getString("agy_models", "[]");
    }

    static void setAgyModels(Context c, String json) {
        sp(c).edit().putString("agy_models", json).apply();
    }

    /** Grok's model list (`grok models`), as JSON [[id, name], …]. */
    static String grokModels(Context c) {
        return sp(c).getString("grok_models", "[]");
    }

    static void setGrokModels(Context c, String json) {
        sp(c).edit().putString("grok_models", json).apply();
    }

    /** First-run guide finished (or skipped). */
    static boolean onboarded(Context c) {
        return sp(c).getBoolean("onboarded", false);
    }

    static void setOnboarded(Context c, boolean on) {
        sp(c).edit().putBoolean("onboarded", on).apply();
    }

    /** Numbering of onboard_step: 1 = Termux-era steps (1.x), 2 = since 2.0. */
    static int onboardVersion(Context c) {
        return sp(c).getInt("onboard_v", sp(c).contains("onboard_step") ? 1 : 2);
    }

    static void setOnboardVersion(Context c, int v) {
        sp(c).edit().putInt("onboard_v", v).apply();
    }

    static int onboardStep(Context c) {
        return sp(c).getInt("onboard_step", 0);
    }

    static void setOnboardStep(Context c, int step) {
        sp(c).edit().putInt("onboard_step", step).apply();
    }

    /** Which agents the user wants: "claude", "codex", "opencode", "agy", "grok" or "both" (Claude Code and Codex). */
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

    /** Keep a Codex session with remote control running inside Buddy's Linux, for the ChatGPT app (CodexRemote). */
    static boolean codexRemote(Context c) {
        return sp(c).getBoolean("codex_remote", false);
    }

    static void setCodexRemote(Context c, boolean on) {
        sp(c).edit().putBoolean("codex_remote", on).apply();
    }

    /** Keep an SSH server running in Buddy's Linux, for Termux (SshServer). */
    static boolean sshServer(Context c) {
        return sp(c).getBoolean("ssh_server", false);
    }

    static void setSshServer(Context c, boolean on) {
        sp(c).edit().putBoolean("ssh_server", on).apply();
    }

    /**
     * The background browser (Browser): off until the user turns it on. Never in the Play build for now: it downloads
     * and runs a whole browser (Chromium from Alpine), which Play's policies on downloaded executable code may not allow.
     */
    static boolean browser(Context c) {
        return sp(c).getBoolean("browser", false);
    }

    static void setBrowser(Context c, boolean on) {
        sp(c).edit().putBoolean("browser", on).apply();
    }

    /** Terminal text size in sp (pinch to zoom). */
    static float termFont(Context c) {
        return sp(c).getFloat("term_font", 13f);
    }

    static void setTermFont(Context c, float sp) {
        sp(c).edit().putFloat("term_font", sp).apply();
    }

    /** Keep the screen on while the Terminal is open. */
    static boolean termAwake(Context c) {
        return sp(c).getBoolean("term_awake", false);
    }

    static void setTermAwake(Context c, boolean on) {
        sp(c).edit().putBoolean("term_awake", on).apply();
    }

    /** Voice input engine: "phone" (Android's recognizer), "claude" (Claude Code's dictation) or "chatgpt". */
    static String voiceEngine(Context c) {
        return sp(c).getString("voice_engine", "phone");
    }

    static void setVoiceEngine(Context c, String e) {
        sp(c).edit().putString("voice_engine", e).apply();
    }

    /** The user's choice for an app: "trust" or "hard" (hard limit), or null for the default (see Guard.mode). */
    static String appSafety(Context c, String pkg) {
        return sp(c).getString("app_safety_" + pkg, null);
    }

    static void setAppSafety(Context c, String pkg, String mode) {
        sp(c).edit().putString("app_safety_" + pkg, mode).apply();
    }

    /** Guard lists as JSON: "allow", "ask", "seen". */
    static String guardList(Context c, String list) {
        return sp(c).getString("guard_" + list, "[]");
    }

    static void setGuardList(Context c, String list, String json) {
        sp(c).edit().putString("guard_" + list, json).apply();
    }

    /** The built-in risky words (Send, Pay, Delete… in several languages) count as "always ask". */
    static boolean guardBuiltin(Context c) {
        return sp(c).getBoolean("guard_builtin", true);
    }

    static void setGuardBuiltin(Context c, boolean on) {
        sp(c).edit().putBoolean("guard_builtin", on).apply();
    }

    /** The smart reviewer's harness: "same" (as the chat's agent), "claude" or "codex". */
    static String reviewer(Context c) {
        return sp(c).getString("reviewer", "same");
    }

    static void setReviewer(Context c, String r) {
        sp(c).edit().putString("reviewer", r).apply();
    }

    /** The model the reviewer uses on a harness (claude or codex); "" for the quick default (Haiku, or a small Codex model). */
    static String reviewerModel(Context c, String harness) {
        return sp(c).getString("reviewer_model_" + harness, "");
    }

    static void setReviewerModel(Context c, String harness, String model) {
        sp(c).edit().putString("reviewer_model_" + harness, model == null ? "" : model.trim()).apply();
    }

    /** The user has opened the bubble's hold menu once (no more hint in the status pill). */
    static boolean menuHinted(Context c) {
        return sp(c).getBoolean("menu_hinted", false);
    }

    static void setMenuHinted(Context c) {
        sp(c).edit().putBoolean("menu_hinted", true).apply();
    }

    /** How many times the hold menu peeked out / the "hold the bubble" tip was shown (each stops after 3). */
    static int peekCount(Context c) {
        return sp(c).getInt("menu_peeks", 0);
    }

    static void addPeek(Context c) {
        sp(c).edit().putInt("menu_peeks", peekCount(c) + 1).apply();
    }

    static int tipCount(Context c) {
        return sp(c).getInt("menu_tips", 0);
    }

    static void addTip(Context c) {
        sp(c).edit().putInt("menu_tips", tipCount(c) + 1).apply();
    }

    /** Who reads replies aloud: "phone" (Android's voices) or "chatgpt" (ChatGPT's voice through Codex). */
    static String speechEngine(Context c) {
        return sp(c).getString("speech_engine", "phone");
    }

    static void setSpeechEngine(Context c, String e) {
        sp(c).edit().putString("speech_engine", e).apply();
    }

    /** Which ChatGPT voice reads replies. */
    static String speechVoice(Context c) {
        return sp(c).getString("speech_voice", "juniper");
    }

    static void setSpeechVoice(Context c, String v) {
        sp(c).edit().putString("speech_voice", v).apply();
    }

    /** The user's voice languages, comma-separated BCP-47 tags (first = default). */
    static String voiceLangs(Context c) {
        String d = java.util.Locale.getDefault().toLanguageTag();
        return sp(c).getString("voice_langs", d);
    }

    static void setVoiceLangs(Context c, String csv) {
        sp(c).edit().putString("voice_langs", csv).apply();
    }

    /** The language to listen for next (one of voiceLangs). */
    static String voiceLang(Context c) {
        String all = voiceLangs(c);
        String cur = sp(c).getString("voice_lang", null);
        if (cur != null && ("," + all + ",").contains("," + cur + ",")) return cur;
        return all.split(",")[0];
    }

    static void setVoiceLang(Context c, String tag) {
        sp(c).edit().putString("voice_lang", tag).apply();
    }

    static void saveBubblePos(Context c, int x, int y) {
        sp(c).edit().putInt("bx", x).putInt("by", y).apply();
    }

    /** Notification rules as JSON (see Rules). */
    static String rules(Context c) {
        return sp(c).getString("rules", "[]");
    }

    static void setRules(Context c, String json) {
        sp(c).edit().putString("rules", json).apply();
    }

    /** The rules screen's main switch: off pauses every rule. */
    static boolean rulesOn(Context c) {
        return sp(c).getBoolean("rules_on", true);
    }

    static void setRulesOn(Context c, boolean on) {
        sp(c).edit().putBoolean("rules_on", on).apply();
    }

    /** The user agreed to what notification access is for (Google Play's prominent disclosure). */
    static boolean noticeConsent(Context c) {
        return sp(c).getBoolean("notice_consent", false);
    }

    static void setNoticeConsent(Context c) {
        sp(c).edit().putBoolean("notice_consent", true).apply();
    }

    /** What the rules did lately, as JSON (see Triggers.log). */
    static String rulesLog(Context c) {
        return sp(c).getString("rules_log", "[]");
    }

    static void setRulesLog(Context c, String json) {
        sp(c).edit().putString("rules_log", json).apply();
    }

    /** The chat each rule conversation continues in, as JSON {key: chat id}. */
    static String ruleChats(Context c) {
        return sp(c).getString("rule_chats", "{}");
    }

    static void setRuleChats(Context c, String json) {
        sp(c).edit().putString("rule_chats", json).apply();
    }

    /** The rules' replies waiting for the user's OK, as JSON (see Waiting). */
    static String waiting(Context c) {
        return sp(c).getString("waiting", "[]");
    }

    static void setWaiting(Context c, String json) {
        sp(c).edit().putString("waiting", json).apply();
    }
}
