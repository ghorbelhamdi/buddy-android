package app.buddy.assistant;

import android.content.Context;
import android.content.SharedPreferences;
import android.text.format.DateUtils;
import org.json.JSONObject;

/**
 * How much of the plan is used: the 5-hour and weekly windows from Claude Code's rate_limit_event (and Codex's, which
 * buddy-run reads from Codex's session log and passes on in the same form), kept per agent and account (another account's numbers never show after a sign-in change). Scheduled tasks and rules spend the plan
 * unattended, so a chat says once per window when it passes 80% and 95%.
 */
final class Usage {
    private static final String FIVE = "five_hour", WEEK = "seven_day";

    private Usage() {
    }

    private static SharedPreferences sp(Context c, String agent) {
        return c.getSharedPreferences("usage_" + agent, Context.MODE_PRIVATE);
    }

    /** The agents that report their plan's limits (the others' tools don't say). */
    static boolean reports(String agent) {
        return "claude".equals(agent) || "codex".equals(agent);
    }

    /**
     * A rate_limit_event from Claude Code: {rate_limit_info: {unifiedWindows: {five_hour|seven_day: {utilization,
     * resetsAt}}}}, utilization a fraction (or a percent), resetsAt Unix seconds. Returns the warning to show in the
     * chat, or null.
     */
    static String onEvent(Context c, String agent, JSONObject ev) {
        if (!reports(agent)) return null;
        JSONObject info = ev.optJSONObject("rate_limit_info");
        JSONObject windows = info == null ? null : info.optJSONObject("unifiedWindows");
        if (windows == null) return null;
        SharedPreferences.Editor e = sp(c, agent).edit().putString("email", Account.of(agent).email)
                .putLong("at", System.currentTimeMillis());
        String warning = null;
        for (String w : new String[]{FIVE, WEEK}) {
            JSONObject o = windows.optJSONObject(w);
            if (o == null || !o.has("utilization")) continue;
            double u = o.optDouble("utilization", 0);
            int pct = (int) Math.round(u > 1 ? u : u * 100);
            long reset = o.optLong("resetsAt", 0) * 1000;
            e.putInt(w, pct).putLong(w + "_reset", reset);
            // once per window and level (80, then 95): the window's reset time tells windows apart
            int level = pct >= 95 ? 95 : pct >= 80 ? 80 : 0;
            String last = sp(c, agent).getString(w + "_warned", "");
            int warned = last.startsWith(reset + ":") ? Integer.parseInt(last.substring(last.indexOf(':') + 1)) : 0;
            if (level > warned) {
                e.putString(w + "_warned", reset + ":" + level);
                if (warning == null) warning = c.getString(FIVE.equals(w) ? R.string.usage_warn_five
                        : R.string.usage_warn_week, pct, when(c, reset));
            }
        }
        e.apply();
        return warning;
    }

    /** "5-hour 62% · resets 16:40 / weekly 30% · resets Fri 09:00" for the signed-in account, or null when not known. */
    static String line(Context c, String agent) {
        return text(c, agent, false);
    }

    /** "5h 62% · week 30%", for under a chat's title, or null when not known. */
    static String shortLine(Context c, String agent) {
        return text(c, agent, true);
    }

    /** The higher of the two windows' use (percent), or -1 when not known. */
    static int peak(Context c, String agent) {
        if (!reports(agent)) return -1;
        SharedPreferences p = sp(c, agent);
        if (!p.contains("at") || !Account.of(agent).email.equals(p.getString("email", ""))) return -1;
        int max = -1;
        for (String w : new String[]{FIVE, WEEK}) {
            if (p.contains(w) && p.getLong(w + "_reset", 0) >= System.currentTimeMillis()) max = Math.max(max, p.getInt(w, 0));
        }
        return max;
    }

    private static String text(Context c, String agent, boolean brief) {
        if (!reports(agent)) return null;
        SharedPreferences p = sp(c, agent);
        if (!p.contains("at") || !Account.of(agent).email.equals(p.getString("email", ""))) return null;
        StringBuilder b = new StringBuilder();
        for (String w : new String[]{FIVE, WEEK}) {
            long reset = p.getLong(w + "_reset", 0);
            if (!p.contains(w) || reset < System.currentTimeMillis()) continue; // a window that ended says nothing now
            if (b.length() > 0) b.append(brief ? " · " : " / ");
            boolean five = FIVE.equals(w);
            b.append(brief ? c.getString(five ? R.string.usage_five_short : R.string.usage_week_short, p.getInt(w, 0))
                    : c.getString(five ? R.string.usage_five : R.string.usage_week, p.getInt(w, 0), when(c, reset)));
        }
        return b.length() == 0 ? null : b.toString();
    }

    /** The reset time in the phone's format: the time today, otherwise with the weekday. */
    private static String when(Context c, long t) {
        int flags = DateUtils.FORMAT_SHOW_TIME;
        if (!DateUtils.isToday(t)) flags |= DateUtils.FORMAT_SHOW_WEEKDAY | DateUtils.FORMAT_ABBREV_WEEKDAY;
        return DateUtils.formatDateTime(c, t, flags);
    }
}
