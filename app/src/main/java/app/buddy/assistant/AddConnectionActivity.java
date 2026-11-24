package app.buddy.assistant;

import android.app.Dialog;
import android.content.Intent;
import android.os.Bundle;
import android.text.TextUtils;
import android.text.format.Formatter;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

import static app.buddy.assistant.Ui.*;

/**
 * Settings → Agents and models → Add a connection: every way Buddy has to get a model, in tabs by how you connect.
 * Free (ways to start without paying, each a card with what's true of it), Plans (an account you already pay for),
 * API keys (services billed by use), This phone (models Buddy runs itself) and Servers (a model server of your own).
 * Nothing is set up here: each button or row leads to the flow that does it, an agent's install and sign-in, Models on
 * this phone, and for every service (a key online, a server of your own, another address) the same sheet
 * (ServiceEditor) with the service filled in, then its own screen, which checks it. OpenRouter can also sign in
 * instead of taking a key. One that's set up already says so and leads to its own screen.
 *
 * The Free tab's lines about limits, payment and privacy are the providers' own statements (read on their pages,
 * November 2026), or Buddy's own use where a line says so. A line that can't be checked there is left out.
 */
public class AddConnectionActivity extends Screen implements Account.Listener, Connections.Listener {
    /** Opens on this tab instead of the one that was open last. */
    static final String EXTRA_TAB = "tab";
    static final String FREE = "free", PLANS = "plans", KEYS = "keys", PHONE = "phone", SERVERS = "servers";

    /**
     * API keys, by what the service is: a heading, then {the preset's name (Providers.PRESETS), its line, the line's
     * %1$s}. A preset that isn't listed here still shows, at the end.
     */
    private static final Object[][] KEY_SECTIONS = {
            {R.string.conn_keys_many, new Object[][]{
                    {"OpenRouter", R.string.conn_what_openrouter, ""}}},
            {R.string.conn_keys_makers, new Object[][]{
                    {"Anthropic", R.string.conn_what_models, "Claude"},
                    {"OpenAI", R.string.conn_what_models, "GPT"},
                    {"Google AI Studio", R.string.conn_what_gemini, ""},
                    {"DeepSeek", R.string.conn_what_models, "DeepSeek"},
                    {"Mistral", R.string.conn_what_models, "Mistral"},
                    {"xAI", R.string.conn_what_models, "Grok"}}},
            {R.string.conn_keys_fast, new Object[][]{
                    {"Groq", R.string.conn_what_fast, ""},
                    {"Cerebras", R.string.conn_what_fast, ""}}},
    };

    private FrameLayout tabBar;
    private ScrollView scroll;
    private LinearLayout body;
    private String tab;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        // where a caller sends it (the sign-in screens' "Use an API key instead"), else where it was left, else Free
        String asked = state != null ? state.getString(EXTRA_TAB) : getIntent().getStringExtra(EXTRA_TAB);
        String last = Prefs.connectionTab(this);
        tab = known(asked) ? asked : known(last) ? last : FREE;
        LinearLayout root = column(this);
        root.setBackgroundColor(BG);
        LinearLayout top = row(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPaddingRelative(dp(this, 4), dp(this, 4), dp(this, 16), dp(this, 4));
        ImageButton back = iconButton(this, R.drawable.ms_arrow_back, getString(R.string.perms_back));
        back.setLayoutParams(new LinearLayout.LayoutParams(dp(this, 44), dp(this, 44)));
        back.setOnClickListener(v -> finish());
        top.addView(back);
        TextView t = title(this, getString(R.string.conn_add_title), 18);
        t.setPaddingRelative(dp(this, 6), 0, 0, 0);
        top.addView(t, weight1());
        root.addView(top, full());
        // the tabs stay put above the page: only the page under them scrolls and is redrawn
        tabBar = new FrameLayout(this);
        tabBar.setPaddingRelative(dp(this, 4), 0, dp(this, 4), 0);
        root.addView(tabBar, full());
        View line = new View(this);
        line.setBackgroundColor(LINE);
        root.addView(line, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(this, 1)));
        scroll = new ScrollView(this);
        body = column(this);
        body.setPaddingRelative(dp(this, 16), dp(this, 16), dp(this, 16), dp(this, 32));
        scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(BG);
        frame.setFitsSystemWindows(true);
        frame.addView(root);
        setContentView(protect(frame)); // sign-ins and keys: the user's own
        showTabs(false);
    }

    private static boolean known(String tab) {
        return FREE.equals(tab) || PLANS.equals(tab) || KEYS.equals(tab) || PHONE.equals(tab) || SERVERS.equals(tab);
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putString(EXTRA_TAB, tab); // (the screen is built again when the theme changes)
    }

    @Override
    protected void onResume() {
        super.onResume();
        Account.addListener(this);
        Connections.addListener(this);
        render();
    }

    @Override
    protected void onPause() {
        if (phonePanel != null) phonePanel.pause();
        Account.removeListener(this);
        Connections.removeListener(this);
        super.onPause();
    }

    @Override
    public void onAccountChanged() {
        if (!isFinishing() && !isDestroyed()) render();
    }

    @Override
    public void onConnectionsChanged() {
        if (!isFinishing() && !isDestroyed()) render();
    }

    /** The tabs, with the open one marked. picked: the user just chose it, so a screen reader stays on it. */
    private void showTabs(boolean picked) {
        tabBar.removeAllViews();
        Object[][] items = {
                {FREE, getString(R.string.conn_free), R.drawable.ms_redeem},
                {PLANS, getString(R.string.conn_tab_plans), R.drawable.ms_card_membership},
                {KEYS, getString(R.string.conn_tab_keys), R.drawable.ms_key},
                {PHONE, getString(R.string.conn_tab_phone), R.drawable.ms_smartphone},
                {SERVERS, getString(R.string.conn_tab_servers), R.drawable.ms_dns},
        };
        LinearLayout bar = tabs(this, items, tab, to -> {
            tab = to;
            Prefs.setConnectionTab(this, to);
            showTabs(true);
            render();
            scroll.scrollTo(0, 0);
        });
        tabBar.addView(bar);
        if (!picked) return;
        // the tab that was tapped is a new view now: without this a screen reader's focus would fall off the bar
        android.view.accessibility.AccessibilityManager am = getSystemService(android.view.accessibility.AccessibilityManager.class);
        if (am == null || !am.isTouchExplorationEnabled()) return;
        for (int i = 0; i < items.length; i++) {
            if (!items[i][0].equals(tab)) continue;
            View now = bar.getChildAt(i);
            now.post(() -> now.performAccessibilityAction(
                    android.view.accessibility.AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null));
        }
    }

    /** The models' own controls, while the This phone tab shows (it follows a download every second). */
    private OnPhonePanel phonePanel;

    private void render() {
        if (phonePanel != null) phonePanel.pause();
        phonePanel = null;
        body.removeAllViews();
        if (OpenRouterAuth.busy) {
            LinearLayout w = row(this);
            w.setGravity(Gravity.CENTER_VERTICAL);
            w.setPaddingRelative(dp(this, 4), 0, 0, 0);
            w.addView(spinner(this, 18));
            TextView t = text(this, getString(R.string.conn_or_busy), 14, TEXT);
            t.setPaddingRelative(dp(this, 10), 0, 0, 0);
            w.addView(t, weight1());
            body.addView(w, gap(0));
        }
        switch (tab) {
            case PLANS:
                plans();
                break;
            case KEYS:
                keysTab();
                break;
            case PHONE:
                phone();
                break;
            case SERVERS:
                servers();
                break;
            default:
                free();
        }
    }

    // ------------------------------------------------------------------ the tabs

    /** Ways to start without paying: what you get, what it asks of you and what happens to what you send. */
    private void free() {
        lead(getString(R.string.conn_free_lead));
        boolean oc = SetupState.signedIn(this, "opencode");
        LinearLayout c = card(agentTile(this, "opencode"), getString(R.string.conn_opencode_free), runsIn("opencode"),
                oc ? getString(R.string.conn_ready) : null, OK);
        fact(c, R.drawable.ms_speed, R.string.conn_fact_opencode_get);
        fact(c, R.drawable.ms_person, R.string.conn_fact_opencode_account);
        fact(c, R.drawable.ms_shield_person, R.string.conn_fact_opencode_privacy);
        action(c, oc ? null : getString(R.string.background_setup), v -> agent("opencode"));

        boolean agy = SetupState.signedIn(this, "agy");
        c = card(agentTile(this, "agy"), getString(R.string.conn_agy_free), runsIn("agy"),
                agy ? getString(R.string.conn_signed_in) : null, OK);
        fact(c, R.drawable.ms_speed, R.string.conn_fact_agy_get);
        fact(c, R.drawable.ms_person, R.string.conn_fact_agy_account);
        fact(c, R.drawable.ms_shield_person, R.string.conn_fact_agy_privacy);
        action(c, agy ? null : getString(R.string.signin_with_google), v -> agent("agy"));

        Providers.P or = openRouter();
        c = card(agentTile(this, "opencode"), getString(R.string.conn_or_free), runsIn("opencode"),
                or == null ? null : state(or)[0], color(or));
        fact(c, R.drawable.ms_speed, R.string.conn_fact_or_get);
        fact(c, R.drawable.ms_person, R.string.conn_fact_or_account);
        fact(c, R.drawable.ms_shield_person, R.string.conn_fact_or_privacy);
        action(c, or != null ? null : getString(R.string.conn_or_sign_in), v -> {
            if (or != null) open(or);
            else openRouterSignIn(true);
        });

        boolean cp = SetupState.signedIn(this, "copilot");
        c = card(agentTile(this, "copilot"), getString(R.string.conn_copilot_free), runsIn("copilot"),
                cp ? getString(R.string.conn_signed_in) : null, OK);
        fact(c, R.drawable.ms_speed, R.string.conn_fact_copilot_get);
        fact(c, R.drawable.ms_person, R.string.conn_fact_copilot_who);
        fact(c, R.drawable.ms_shield_person, R.string.conn_fact_copilot_privacy);
        action(c, cp ? null : getString(R.string.signin_with_github), v -> agent("copilot"));

        // a key rather than a sign-in, but free to use within its limits: the one preset that belongs here too
        String[] gemini = preset("Google AI Studio");
        if (gemini != null) {
            Providers.P have = byHost(gemini);
            c = card(iconTile(this, R.drawable.ms_key), getString(R.string.conn_gemini_free),
                    getString(R.string.agents_runs_in, agents(gemini)), have == null ? null : state(have)[0], color(have));
            fact(c, R.drawable.ms_speed, R.string.conn_fact_gemini_get);
            fact(c, R.drawable.ms_credit_card, R.string.conn_fact_gemini_card);
            fact(c, R.drawable.ms_shield_person, R.string.conn_fact_gemini_privacy);
            fact(c, R.drawable.ms_public, R.string.conn_fact_gemini_region);
            action(c, have != null ? null : getString(R.string.conn_or_paste), v -> {
                if (have != null) open(have);
                else keys(gemini[0]);
            });
        }
        foot(getString(R.string.conn_free_checked));
    }

    /** An account the user already pays for: sign in with it, in the agent that runs on it. */
    private void plans() {
        lead(getString(R.string.conn_plans_lead));
        LinearLayout g = group(this);
        body.addView(g, gap(14));
        boolean claude = SetupState.ownModels(this, "claude"), codex = SetupState.planSignedIn(this, "codex");
        boolean agy = SetupState.signedIn(this, "agy"), grok = SetupState.signedIn(this, "grok");
        boolean copilot = SetupState.signedIn(this, "copilot"), pi = SetupState.ownModels(this, "pi");
        String in = getString(R.string.conn_signed_in);
        planRow(g, getString(R.string.conn_claude_plan), claude ? in : getString(R.string.conn_claude_plan_sub), claude, "claude");
        planRow(g, getString(R.string.conn_chatgpt_plan), codex ? in : getString(R.string.conn_chatgpt_plan_sub), codex, "codex");
        planRow(g, getString(R.string.conn_google_plan), agy ? in : getString(R.string.conn_google_sub), agy, "agy");
        planRow(g, getString(R.string.conn_supergrok), grok ? in : getString(R.string.conn_supergrok_sub), grok, "grok");
        planRow(g, getString(R.string.conn_copilot), copilot ? in : getString(R.string.conn_copilot_sub), copilot, "copilot");
        planRow(g, getString(R.string.conn_pi), pi ? getString(R.string.conn_pi_connected, Account.PI.planLabel())
                : getString(R.string.conn_pi_sub), pi, "pi");
        foot(getString(R.string.conn_add_foot));
    }

    /** Services billed by use: the ones Buddy knows the addresses of, by what they are, then any other address. */
    private void keysTab() {
        lead(getString(R.string.conn_keys_lead));
        java.util.Set<String> listed = new java.util.HashSet<>();
        for (Object[] section : KEY_SECTIONS) {
            LinearLayout g = null;
            for (Object[] it : (Object[][]) section[1]) {
                String[] pr = preset((String) it[0]);
                if (pr == null) continue;
                if (g == null) g = section(getString((Integer) section[0]));
                presetRow(g, pr, getString((Integer) it[1], it[2]));
                listed.add(pr[0]);
            }
        }
        LinearLayout other = section(getString(R.string.conn_keys_other));
        for (String[] pr : Providers.PRESETS) {
            if (Providers.isLocal(pr[2]) || Providers.isLocal(pr[1]) || listed.contains(pr[0])) continue; // (servers of your own: their tab)
            presetRow(other, pr, getString(R.string.conn_paste_key));
        }
        serviceRow(other, getString(R.string.conn_custom), getString(R.string.conn_custom_sub), "", null, v -> keys(""));
    }

    /** Models Buddy runs on the phone itself: what that means here, the list as it stands, and the way to its screen. */
    private void phone() {
        lead(getString(R.string.conn_phone_lead));
        LinearLayout c = card(iconTile(this, R.drawable.ms_memory), getString(R.string.onphone_title),
                getString(R.string.agents_runs_in_lite, Account.nameOf("opencode")), null, OK); // (which are here: the list below)
        fact(c, R.drawable.ms_lock, R.string.conn_phone_private);
        fact(c, R.drawable.ms_speed, R.string.conn_phone_small);
        long ram = OnPhone.ram(this);
        if (ram > 0) {
            c.addView(Ui.fact(this, R.drawable.ms_memory, getString(R.string.conn_phone_ram,
                    Formatter.formatShortFileSize(this, ram))), gap(10));
        }
        // where a model runs, downloads and removing: the same panel as the screen of its own, right here
        LinearLayout models = column(this);
        body.addView(models, gap(8));
        phonePanel = new OnPhonePanel(this, models, false);
        phonePanel.resume();
    }

    /** A model server of the user's own: the ones saved, what it takes, and the sheet that adds one. */
    private void servers() {
        lead(getString(R.string.conn_servers_lead));
        LinearLayout mine = null;
        for (Providers.P p : Providers.all()) {
            if (!p.local() || OnPhone.ID.equals(p.id)) continue; // (the models on this phone have their own tab)
            if (mine == null) mine = section(getString(R.string.conn_servers_yours));
            serviceRow(mine, p.name, shortLink((p.openai.isEmpty() ? p.anthropic : p.openai).replaceFirst("^https?://", "")),
                    Connections.runs(this, p), p, v -> open(p));
        }
        List<String[]> local = new ArrayList<>();
        List<String> names = new ArrayList<>();
        for (String[] pr : Providers.PRESETS) {
            if (!Providers.isLocal(pr[2])) continue;
            local.add(pr);
            names.add(pr[0]);
        }
        String runs = local.isEmpty() ? getString(R.string.conn_custom_agents)
                : getString(R.string.agents_can_run_in, agents(local.get(0)));
        LinearLayout c = card(iconTile(this, R.drawable.ms_dns), getString(R.string.conn_servers_title), runs, null, OK);
        c.addView(Ui.fact(this, R.drawable.ms_check, getString(R.string.conn_servers_what, TextUtils.join(", ", names))), gap(14));
        fact(c, R.drawable.ms_search, R.string.conn_servers_address);
        fact(c, R.drawable.ms_lock, R.string.conn_servers_private);
        fact(c, R.drawable.ms_key, R.string.conn_servers_key);
        if (!local.isEmpty()) { // each with its usual address filled in
            TextView from = text(this, getString(R.string.conn_servers_start), 13, MUTED);
            c.addView(from, gap(16));
            Flow chips = new Flow(this, 8);
            for (String[] pr : local) {
                TextView chip = text(this, pr[0], 14, TEXT);
                chip.setTypeface(sans(this, 500));
                chip.setGravity(Gravity.CENTER);
                chip.setMinHeight(dp(this, 40));
                chip.setPaddingRelative(dp(this, 14), 0, dp(this, 14), 0);
                chip.setBackground(rounded(this, CARD2, 12));
                chip.setOnClickListener(v -> keys(pr[0]));
                chips.addView(chip);
            }
            c.addView(chips, gap(8));
        }
        // the sheet starts on a server of the user's own, so its key reads as optional from the start (on an empty
        // sheet it asks for one until a local address is typed); its Service menu switches to any other
        String first = local.isEmpty() ? "" : local.get(0)[0];
        action(c, getString(R.string.conn_servers_add), true, v -> keys(first));
    }

    // ------------------------------------------------------------------ the pieces

    /** The line a tab starts with: what this way of connecting is. */
    private void lead(String s) {
        TextView t = text(this, s, 15, TEXT2);
        t.setPaddingRelative(dp(this, 4), 0, dp(this, 4), 0);
        body.addView(t, gap(body.getChildCount() == 0 ? 0 : 14));
    }

    private void foot(String s) {
        TextView t = text(this, s, 13, MUTED);
        t.setPaddingRelative(dp(this, 4), 0, dp(this, 4), 0);
        body.addView(t, gap(18));
    }

    /**
     * A way in as a card: its tile, its name and who runs it, and its state once it's set up (null before). Its facts
     * and its one button are added under them (fact, action).
     */
    private LinearLayout card(View tile, String name, String runs, String state, int stateColor) {
        LinearLayout c = column(this);
        c.setBackground(rounded(this, CARD, 20));
        c.setPaddingRelative(dp(this, 16), dp(this, 16), dp(this, 16), dp(this, 16));
        LinearLayout h = row(this);
        h.setGravity(Gravity.CENTER_VERTICAL);
        h.addView(tile);
        LinearLayout t = column(this);
        t.setPaddingRelative(dp(this, 12), 0, 0, 0);
        t.addView(title(this, name, 16));
        TextView r = text(this, runs, 13, MUTED);
        r.setPaddingRelative(0, dp(this, 2), 0, 0);
        t.addView(r);
        h.addView(t, weight1());
        if (state != null) {
            TextView st = text(this, state, 13, stateColor);
            st.setTypeface(sans(this, 600));
            LinearLayout.LayoutParams sp = wrap();
            sp.setMarginStart(dp(this, 10));
            h.addView(st, sp);
        }
        c.addView(h, full());
        body.addView(c, gap(14));
        return c;
    }

    private void fact(LinearLayout card, int icon, int text) {
        card.addView(Ui.fact(this, icon, getString(text)), gap(card.getChildCount() == 1 ? 14 : 10));
    }

    /** A card's one button. label null: it's set up already, and the button opens it. */
    private void action(LinearLayout card, String label, View.OnClickListener l) {
        action(card, label == null ? getString(R.string.conn_open) : label, label != null, l);
    }

    private void action(LinearLayout card, String label, boolean primary, View.OnClickListener l) {
        Button b = button(this, label, primary);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(this, 48));
        p.topMargin = dp(this, 16);
        card.addView(b, p);
    }

    /** A small heading and the card its rows go in. */
    private LinearLayout section(String title) {
        TextView t = text(this, title, 13, MUTED);
        t.setTypeface(sans(this, 600));
        t.setPaddingRelative(dp(this, 4), 0, dp(this, 4), 0);
        body.addView(t, gap(22));
        LinearLayout g = group(this);
        body.addView(g, gap(8));
        return g;
    }

    /** A plan's row: its name, how to sign in (or, in green, that it is), and the agent that runs on it as a tag. */
    private void planRow(LinearLayout g, String name, String how, boolean done, String agent) {
        LinearLayout r = row(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPaddingRelative(dp(this, 16), dp(this, 12), dp(this, 12), dp(this, 12));
        r.setMinimumHeight(dp(this, 60));
        r.setBackgroundResource(ripple(this, false));
        LinearLayout t = column(this);
        t.addView(title(this, name, 16));
        TextView s = text(this, how, 13, done ? OK : MUTED);
        if (done) s.setTypeface(sans(this, 600));
        s.setPaddingRelative(0, dp(this, 2), 0, 0);
        t.addView(s);
        r.addView(t, weight1());
        TextView tag = text(this, Account.nameOf(agent), 12, TEXT2);
        tag.setTypeface(sans(this, 600));
        tag.setGravity(Gravity.END);
        tag.setMaxWidth(dp(this, 130));
        tag.setPaddingRelative(dp(this, 10), dp(this, 4), dp(this, 10), dp(this, 4));
        tag.setBackground(rounded(this, CARD2, 10));
        LinearLayout.LayoutParams tp = wrap();
        tp.setMarginStart(dp(this, 10));
        r.addView(tag, tp);
        r.setOnClickListener(v -> agent(agent));
        addToGroup(this, g, r, 16);
    }

    /** A known service's row: what it is and who can run it; saved already, its state, and a tap opens its screen. */
    private void presetRow(LinearLayout g, String[] pr, String what) {
        Providers.P have = byHost(pr);
        boolean isOr = "OpenRouter".equals(pr[0]);
        serviceRow(g, pr[0], what, have != null ? Connections.runs(this, have)
                : getString(R.string.agents_can_run_in, agents(pr)), have, v -> {
            if (have != null) open(have);
            else if (isOr) openRouterChoice();
            else keys(pr[0]);
        });
    }

    /** A service's row: its name, a line about it, the agents ("": none to name), and its state once it's saved. */
    private void serviceRow(LinearLayout g, String name, String what, String agents, Providers.P have, View.OnClickListener l) {
        LinearLayout r = row(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPaddingRelative(dp(this, 16), dp(this, 12), dp(this, 16), dp(this, 12));
        r.setMinimumHeight(dp(this, 60));
        r.setBackgroundResource(ripple(this, false));
        LinearLayout t = column(this);
        t.addView(title(this, name, 16));
        TextView s = text(this, what, 13, TEXT2);
        s.setPaddingRelative(0, dp(this, 2), 0, 0);
        t.addView(s);
        if (!agents.isEmpty()) {
            TextView a = text(this, agents, 12, MUTED);
            a.setPaddingRelative(0, dp(this, 2), 0, 0);
            t.addView(a);
        }
        r.addView(t, weight1());
        if (have != null) {
            TextView st = text(this, state(have)[0], 13, color(have));
            st.setTypeface(sans(this, 600));
            st.setGravity(Gravity.END);
            st.setMaxWidth(dp(this, 110));
            LinearLayout.LayoutParams sp = wrap();
            sp.setMarginStart(dp(this, 10));
            r.addView(st, sp);
        }
        r.setOnClickListener(l);
        addToGroup(this, g, r, 16);
    }

    /** A saved service's state as its own screen and Agents and models word it: {text, kind}. */
    private String[] state(Providers.P p) {
        return ConnectionActivity.status(this, p);
    }

    private int color(Providers.P p) {
        if (p == null) return MUTED;
        String kind = state(p)[1];
        return "ok".equals(kind) ? OK : "err".equals(kind) ? ERR : MUTED;
    }

    private String runsIn(String agent) {
        return getString(R.string.agents_runs_in, Account.nameOf(agent));
    }

    /**
     * The agents that can use a preset, by name: the ones its addresses fit (Providers.P.fits), without Codex where
     * the service's docs show no Responses API (the preset's "chat"): a row shouldn't promise an agent that can't run.
     */
    private static String agents(String[] preset) {
        Providers.P p = new Providers.P("");
        p.anthropic = preset[1];
        p.openai = preset[2];
        List<String> names = new ArrayList<>();
        for (String a : Connections.AGENTS) {
            if (p.fits(a) && !("codex".equals(a) && "chat".equals(preset[3]))) names.add(Account.nameOf(a));
        }
        return TextUtils.join(", ", names);
    }

    private static String[] preset(String name) {
        for (String[] pr : Providers.PRESETS) if (pr[0].equals(name)) return pr;
        return null;
    }

    private Providers.P openRouter() {
        for (Providers.P p : Providers.all()) if (Connections.isOpenRouter(p)) return p;
        return null;
    }

    /** The saved service at a preset's address, if there is one. */
    private static Providers.P byHost(String[] preset) {
        String h = android.net.Uri.parse(!preset[2].isEmpty() ? preset[2] : preset[1]).getHost();
        for (Providers.P p : Providers.all()) if (h != null && h.equals(p.host())) return p;
        return null;
    }

    private void agent(String agent) {
        startActivity(new Intent(this, SignInActivity.class).putExtra("agent", agent));
    }

    private void open(Providers.P p) {
        startActivity(new Intent(this, ConnectionActivity.class).putExtra("id", p.id));
    }

    /** The sheet for a new service, with this one filled in ("" for another address); once saved, its own screen. */
    private void keys(String preset) {
        ServiceEditor.add(this, preset.isEmpty() ? null : preset, this::open);
    }

    /** OpenRouter: sign in (no key to copy), or paste a key. */
    private void openRouterChoice() {
        LinearLayout c = protect(column(this));
        c.addView(title(this, "OpenRouter", 18));
        TextView info = text(this, getString(R.string.conn_or_choice_info), 13, MUTED);
        info.setPaddingRelative(0, dp(this, 4), 0, dp(this, 12));
        c.addView(info);
        final Dialog[] d = new Dialog[1];
        Button in = button(this, getString(R.string.conn_or_sign_in), true);
        in.setOnClickListener(v -> {
            d[0].dismiss();
            openRouterSignIn(false);
        });
        c.addView(in, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(this, 52)));
        Button paste = button(this, getString(R.string.conn_or_paste), false);
        paste.setOnClickListener(v -> {
            d[0].dismiss();
            keys("OpenRouter");
        });
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(this, 48));
        pp.topMargin = dp(this, 8);
        c.addView(paste, pp);
        d[0] = bottomSheet(this, c);
        d[0].show();
    }

    /**
     * OpenRouter's page in the browser; it comes back to Buddy's own server on this phone, which runs with Buddy's
     * accessibility service. Without it, a pasted key still works.
     */
    private void openRouterSignIn(boolean free) {
        String url = OpenRouterAuth.start(free);
        if (url == null) {
            confirmSheet(this, getString(R.string.conn_or_needs_service_title), getString(R.string.conn_or_needs_service),
                    getString(R.string.conn_or_paste), false, () -> keys("OpenRouter"));
            return;
        }
        openLink(this, url);
    }

    private LinearLayout.LayoutParams gap(int top) {
        LinearLayout.LayoutParams p = full();
        p.topMargin = dp(this, top);
        return p;
    }
}
