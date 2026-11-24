package app.buddy.assistant;

import android.app.Dialog;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

import static app.buddy.assistant.Ui.*;

/**
 * Settings → Agents and models → one service (see Providers), the same screen whatever it is (a key at a service
 * online, a server of the user's own): its state and, where the service reports one, its balance; what Buddy found
 * when it checked it; the agents that run it; its model for chats and the models the pickers show; its addresses and
 * key (the sheets of ServiceEditor); switching it off; making it what new chats use; removing it.
 */
public class ConnectionActivity extends Screen implements Connections.Listener, Account.Listener {
    private LinearLayout body;
    private String id;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        id = getIntent().getStringExtra("id");
        LinearLayout root = column(this);
        root.setBackgroundColor(BG);
        LinearLayout top = row(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPaddingRelative(dp(this, 4), dp(this, 4), dp(this, 16), dp(this, 4));
        ImageButton back = iconButton(this, R.drawable.ms_arrow_back, getString(R.string.perms_back));
        back.setLayoutParams(new LinearLayout.LayoutParams(dp(this, 44), dp(this, 44)));
        back.setOnClickListener(v -> finish());
        top.addView(back);
        root.addView(top, full());
        ScrollView sv = new ScrollView(this);
        body = column(this);
        body.setPaddingRelative(dp(this, 20), 0, dp(this, 20), dp(this, 32));
        sv.addView(body);
        root.addView(sv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(BG);
        frame.setFitsSystemWindows(true);
        frame.addView(root);
        setContentView(protect(frame)); // keys, money and what the agents use: the user's own
    }

    @Override
    protected void onResume() {
        super.onResume();
        Connections.addListener(this);
        Account.addListener(this);
        Providers.P p = Providers.find(id);
        if (p == null) { // removed meanwhile
            finish();
            return;
        }
        Connections.refreshBalance(this, p, false);
        render();
    }

    @Override
    protected void onPause() {
        Connections.removeListener(this);
        Account.removeListener(this);
        super.onPause();
    }

    @Override
    public void onConnectionsChanged() {
        if (!isFinishing() && !isDestroyed()) render();
    }

    @Override
    public void onAccountChanged() {
        if (!isFinishing() && !isDestroyed()) render();
    }

    private void render() {
        Providers.P p = Providers.find(id);
        if (p == null) {
            finish();
            return;
        }
        body.removeAllViews();
        boolean oauth = Connections.signedIn(this, p.id);
        TextView h = text(this, p.name, 28, TEXT);
        h.setTypeface(sans(this, 700));
        body.addView(h, gap(4));
        // where it is and what it costs, as its row in Agents and models says it
        TextView sub = text(this, p.host() + " · " + Connections.cost(this, p), 15, TEXT2);
        body.addView(sub, gap(4));

        // status, and what's left where the service says (OpenRouter)
        LinearLayout st = group(this);
        st.setPaddingRelative(dp(this, 16), dp(this, 14), dp(this, 16), dp(this, 14));
        LinearLayout line = row(this);
        line.setGravity(Gravity.CENTER_VERTICAL);
        String[] status = status(this, p);
        int color = "ok".equals(status[1]) ? OK : "err".equals(status[1]) ? ERR : MUTED;
        line.addView(dot(this, color, 8));
        TextView s = title(this, status[0], 15);
        s.setTextColor(color);
        s.setPaddingRelative(dp(this, 8), 0, dp(this, 8), 0);
        line.addView(s, weight1());
        String bal = Connections.balance(p.id);
        if (bal != null && !bal.isEmpty()) line.addView(title(this, bal, 15));
        st.addView(line, full());
        int free = Connections.freeLeft(p.id);
        if (free >= 0) st.addView(text(this, getString(R.string.conn_free_left, free), 13, MUTED), gap(6));
        if (oauth) st.addView(text(this, getString(R.string.conn_or_own_key), 13, MUTED), gap(6));
        body.addView(st, gap(18));

        checks(p);
        if (!p.off) runsIn(p);
        models(p);

        LinearLayout more = group(this);
        more.addView(valueRow(getString(R.string.conn_edit), p.host(), v -> ServiceEditor.edit(this, p, q -> render())), full());
        body.addView(more, gap(14));

        // off: the key stays saved, nothing uses it
        LinearLayout sw = group(this);
        sw.addView(switchRow(this, getString(R.string.api_keys_use), getString(R.string.api_keys_use_info), !p.off, on -> {
            Providers.setOff(this, p, !on);
            Connections.refreshAgents(this);
            render();
        }), full());
        body.addView(sw, gap(14));

        String agent = Connections.runsIn(this, p);
        boolean now = !agent.isEmpty() && agent.equals(Prefs.backend(this)) && Connections.uses(this, agent, p);
        if (!p.off && !agent.isEmpty()) {
            if (now) {
                TextView t = text(this, getString(R.string.conn_used_now, Account.nameOf(agent)), 14, OK);
                t.setGravity(Gravity.CENTER);
                body.addView(t, gap(22));
            } else {
                Button b = button(this, getString(R.string.conn_use_for_new, Account.nameOf(agent)), true);
                b.setOnClickListener(v -> useForNew(p, agent));
                LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(this, 52));
                bp.topMargin = dp(this, 22);
                body.addView(b, bp);
            }
        }
        Button rm = button(this, oauth ? getString(R.string.conn_sign_out, p.name) : getString(R.string.conn_remove, p.name), false);
        rm.setTextColor(ERR);
        rm.setBackgroundResource(ripple(this, false));
        rm.setOnClickListener(v -> confirmSheet(this, getString(R.string.api_keys_remove_title, p.name),
                getString(oauth ? R.string.conn_sign_out_text : R.string.api_keys_remove_text),
                oauth ? getString(R.string.conn_sign_out, p.name) : getString(R.string.api_keys_remove), true, () -> {
                    Connections.remove(this, p.id);
                    finish();
                }));
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(this, 52));
        rp.topMargin = dp(this, 8);
        body.addView(rm, rp);
    }

    /** {what to show, "ok" / "err" / "muted"}: the service's state in a few words (also the Agents and models row). */
    static String[] status(android.content.Context c, Providers.P p) {
        if (p.off) return new String[]{c.getString(R.string.conn_status_off), "muted"};
        if (Connections.checking(p.id)) return new String[]{c.getString(R.string.conn_status_checking), "muted"};
        if (Connections.failed(c, p.id)) return new String[]{c.getString(R.string.conn_status_failed), "err"};
        if (Connections.noTools(c, p.id)) return new String[]{c.getString(R.string.conn_status_no_tools), "err"};
        if (Connections.signedIn(c, p.id)) return new String[]{c.getString(R.string.conn_status_signed_in), "ok"};
        // (a server of the user's own mostly has no key: it's simply there)
        if (p.local()) return new String[]{c.getString(R.string.conn_status_ready), "ok"};
        return new String[]{c.getString(R.string.conn_status_key), "ok"};
    }

    /** "Checked when you connected": the three answers, each failure in the service's own words, and Check again. */
    private void checks(Providers.P p) {
        body.addView(heading(getString(R.string.conn_checked)), gap(22));
        LinearLayout g = group(this);
        g.setPaddingRelative(dp(this, 16), dp(this, 8), dp(this, 16), dp(this, 14));
        JSONObject r = Connections.lastCheck(this, p.id);
        boolean busy = Connections.checking(p.id);
        if (busy) {
            LinearLayout w = row(this);
            w.setGravity(Gravity.CENTER_VERTICAL);
            w.setPaddingRelative(0, dp(this, 8), 0, dp(this, 4));
            w.addView(spinner(this, 20));
            TextView t = text(this, getString(R.string.conn_checking), 15, TEXT);
            t.setPaddingRelative(dp(this, 12), 0, 0, 0);
            w.addView(t, weight1());
            g.addView(w, full());
        } else if (r == null) {
            g.addView(text(this, getString(R.string.conn_not_checked), 15, MUTED), gap(8));
        } else {
            JSONObject a = r.optJSONObject("answers"), t = r.optJSONObject("tools"), f = r.optJSONObject("fits");
            g.addView(result(a != null && a.optBoolean("ok"), getString(R.string.conn_check_answers), a == null ? "" : a.optString("said")), full());
            if (t != null) {
                boolean ok = t.optBoolean("ok");
                g.addView(result(ok, getString(ok ? R.string.conn_check_tools : R.string.conn_check_tools_failed),
                        t.optString("said")), full());
                if (!ok) g.addView(warningText(getString(R.string.conn_no_tools_warning, p.name)), gap(8));
            }
            if (f != null) {
                boolean ok = f.optBoolean("ok");
                String need = Models.tokens(f.optLong("need")), window = Models.tokens(f.optLong("window"));
                // a fit is only Buddy's part: the agent's own prompt comes on top, so it gets no tick
                g.addView(ok ? note(getString(R.string.conn_check_fits, need, window))
                        : result(false, getString(R.string.conn_check_fits_not, need, window), ""), full());
            }
            String agent = r.optString("agent");
            String how = getString(R.string.conn_checked_how, r.optString("model"),
                    agent.isEmpty() ? p.name : Account.nameOf(agent),
                    android.text.format.DateUtils.getRelativeTimeSpanString(r.optLong("at")).toString());
            TextView hw = monoText(this, how, 12, MUTED);
            g.addView(hw, gap(10));
            String nowAgent = Connections.runsIn(this, p);
            if (!agent.equals(nowAgent) || !r.optString("model").equals(Connections.chatModel(p, nowAgent))) {
                g.addView(text(this, getString(R.string.conn_check_stale), 13, MUTED), gap(4));
            }
        }
        if (!busy && !p.off) {
            Button again = button(this, getString(r == null ? R.string.conn_check_now : R.string.conn_check_again), false);
            again.setOnClickListener(v -> Connections.check(this, p));
            LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(this, 44));
            ap.topMargin = dp(this, 12);
            g.addView(again, ap);
            g.addView(text(this, getString(R.string.conn_check_cost), 12, MUTED), gap(6));
        }
        body.addView(g, gap(10));
    }

    private View result(boolean ok, String what, String said) {
        LinearLayout r = row(this);
        r.setGravity(Gravity.TOP);
        r.setPaddingRelative(0, dp(this, 8), 0, 0);
        r.addView(icon(this, ok ? R.drawable.ms_check : R.drawable.ms_error, ok ? OK : ERR, 20));
        LinearLayout t = column(this);
        t.setPaddingRelative(dp(this, 12), 0, 0, 0);
        t.addView(text(this, what, 15, TEXT));
        if (!ok && said != null && !said.isEmpty()) { // the service's own words, as it said them
            TextView s = monoText(this, said, 12, MUTED);
            s.setTextIsSelectable(true);
            s.setPaddingRelative(0, dp(this, 4), 0, 0);
            t.addView(s);
        }
        r.addView(t, weight1());
        return r;
    }

    private View note(String what) {
        LinearLayout r = row(this);
        r.setGravity(Gravity.TOP);
        r.setPaddingRelative(0, dp(this, 8), 0, 0);
        r.addView(icon(this, R.drawable.ms_memory, MUTED, 20));
        TextView t = text(this, what, 15, TEXT);
        t.setPaddingRelative(dp(this, 12), 0, 0, 0);
        r.addView(t, weight1());
        return r;
    }

    private View warningText(String s) {
        TextView t = text(this, s, 13, ERR);
        t.setPaddingRelative(dp(this, 32), 0, 0, 0);
        return t;
    }

    /**
     * "Runs in": a tick for each agent whose chats on Default use this service (more than one may). Ticking one makes
     * the service that agent's default; unticking gives it back its own default, which only an agent with models of
     * its own has (a plan, or OpenCode's free models). One that isn't installed leads to its setup.
     */
    private void runsIn(Providers.P p) {
        List<String> fit = new ArrayList<>();
        for (String a : Connections.AGENTS) if (p.fits(a)) fit.add(a);
        if (fit.isEmpty()) return;
        String agent = Connections.runsIn(this, p);
        body.addView(heading(getString(R.string.conn_runs_in)), gap(22));
        LinearLayout g = group(this);
        g.setPaddingRelative(dp(this, 12), dp(this, 12), dp(this, 12), dp(this, 12));
        Ui.Flow chips = new Ui.Flow(this, 8);
        for (String a : fit) {
            boolean installed = SetupState.installed(this, a), on = Connections.uses(this, a, p);
            LinearLayout chip = row(this);
            chip.setGravity(Gravity.CENTER_VERTICAL);
            chip.setMinimumHeight(dp(this, 44));
            chip.setPaddingRelative(dp(this, 12), 0, dp(this, 14), 0);
            chip.setBackground(on ? rounded(this, ACCENT_SOFT, 14) : outlined(this, 0x00000000, 14, OUTLINE, 1));
            if (on) {
                chip.addView(icon(this, R.drawable.ms_check, ACCENT_TEXT, 18));
                chip.addView(new View(this), new LinearLayout.LayoutParams(dp(this, 6), 1));
            }
            TextView t = text(this, Account.nameOf(a), 14, on ? ACCENT_TEXT : installed ? TEXT : MUTED);
            t.setTypeface(sans(this, on ? 600 : 500));
            chip.addView(t);
            chip.setContentDescription(Account.nameOf(a));
            chip.setSelected(on);
            chip.setOnClickListener(v -> {
                if (!installed) {
                    Toast.makeText(this, getString(R.string.setup_set_up_first, Account.nameOf(a)), Toast.LENGTH_SHORT).show();
                    startActivity(new Intent(this, SignInActivity.class).putExtra("agent", a));
                } else if (!on) {
                    Prefs.setAgentDefault(this, a, Providers.ref(p, Connections.chatModel(p, agent)));
                    ChatHub.get(this).changed();
                    render();
                } else if ("opencode".equals(a) || SetupState.ownModels(this, a)) {
                    Prefs.setAgentDefault(this, a, "");
                    ChatHub.get(this).changed();
                    render();
                } else { // with no plan, its chats need a service: it keeps this one until another is picked for it
                    Toast.makeText(this, getString(R.string.conn_runs_in_keep, Account.nameOf(a)), Toast.LENGTH_LONG).show();
                }
            });
            chips.addView(chip);
        }
        g.addView(chips, full());
        TextView info = text(this, getString(R.string.conn_runs_in_info), 13, MUTED);
        info.setPaddingRelative(dp(this, 4), dp(this, 8), dp(this, 4), 0);
        g.addView(info, full());
        body.addView(g, gap(10));
    }

    /** "Models": the one its chats use (not while it's off: nothing uses it then), and the ones the pickers show. */
    private void models(Providers.P p) {
        if (p.models.length() == 0) return;
        body.addView(heading(getString(R.string.conn_models)), gap(22));
        LinearLayout m = group(this);
        if (!p.off) {
            String agent = Connections.runsIn(this, p);
            String model = Connections.chatModel(p, agent);
            org.json.JSONObject mi = p.modelInfo(model);
            String name = mi == null || mi.optString("name").isEmpty() ? model : mi.optString("name");
            addToGroup(this, m, valueRow(getString(R.string.conn_for_chats), name.isEmpty() ? getString(R.string.models_default)
                    : name, v -> pickModel(p, agent)), 16);
        }
        addToGroup(this, m, valueRow(getString(R.string.conn_shown), getString(R.string.conn_shown_count, p.offered().length(),
                p.models.length()), v -> ServiceEditor.chooseModels(this, p, this::render)), 16);
        body.addView(m, gap(10));
    }

    /** The model chats use from it: the service's default, and the agents' that use it. */
    private void pickModel(Providers.P p, String agent) {
        LinearLayout c = protect(column(this));
        c.addView(title(this, getString(R.string.conn_for_chats_title, p.name), 18));
        final Dialog[] d = new Dialog[1];
        org.json.JSONArray models = p.offered();
        List<Object[]> found = new ArrayList<>();
        if (models.length() > SEARCH_FROM) c.addView(listFilter(this, getString(R.string.chat_model_search), found), gap(10));
        String current = Connections.chatModel(p, agent);
        for (int i = 0; i < models.length(); i++) {
            org.json.JSONObject o = models.optJSONObject(i);
            if (o == null) continue;
            String mid = o.optString("id"), nm = o.optString("name", mid);
            LinearLayout r = row(this);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPaddingRelative(dp(this, 4), dp(this, 10), dp(this, 4), dp(this, 10));
            r.setBackgroundResource(ripple(this, false));
            r.addView(radio(this, mid.equals(current)));
            LinearLayout t = column(this);
            t.setPaddingRelative(dp(this, 14), 0, 0, 0);
            t.addView(title(this, nm, 15));
            t.addView(single(monoText(this, Models.detail("opencode", Providers.ref(p, mid), ""), 12, MUTED)));
            r.addView(t, weight1());
            r.setOnClickListener(v -> {
                d[0].dismiss();
                p.model = mid;
                try {
                    Providers.save(this, p, null);
                } catch (Exception ignored) {
                }
                // the agents whose chats use this service use this model now
                for (String a : Connections.AGENTS) if (Connections.defaultsTo(a, p)) Prefs.setAgentDefault(this, a, Providers.ref(p, mid));
                ChatHub.get(this).changed();
                render();
            });
            c.addView(r, full());
            found.add(new Object[]{r, (nm + " " + mid).toLowerCase(java.util.Locale.ROOT), null});
        }
        d[0] = bottomSheet(this, c);
        d[0].show();
    }

    /** New chats: this agent, on this service's model. An agent that isn't installed yet goes to its setup first. */
    private void useForNew(Providers.P p, String agent) {
        if (!SetupState.signedIn(this, agent)) {
            Toast.makeText(this, getString(R.string.setup_set_up_first, Account.nameOf(agent)), Toast.LENGTH_SHORT).show();
            startActivity(new Intent(this, SignInActivity.class).putExtra("agent", agent));
            return;
        }
        Prefs.setAgentDefault(this, agent, Providers.ref(p, Connections.chatModel(p, agent)));
        Prefs.setBackend(this, agent);
        ChatHub.get(this).changed();
        Toast.makeText(this, getString(R.string.setup_default_brain_set, Account.nameOf(agent)), Toast.LENGTH_LONG).show();
        render();
    }

    private TextView heading(String s) {
        TextView t = text(this, s, 13, MUTED);
        t.setTypeface(sans(this, 600));
        t.setPaddingRelative(dp(this, 4), 0, 0, 0);
        return t;
    }

    private LinearLayout valueRow(String title, String value, View.OnClickListener l) {
        LinearLayout r = row(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPaddingRelative(dp(this, 16), dp(this, 12), dp(this, 10), dp(this, 12));
        r.setMinimumHeight(dp(this, 56));
        r.setBackgroundResource(ripple(this, false));
        LinearLayout t = column(this);
        t.addView(text(this, title, 13, MUTED));
        t.addView(single(title(this, value, 15)));
        r.addView(t, weight1());
        r.addView(icon(this, R.drawable.ms_chevron_right, MUTED, 22));
        r.setOnClickListener(l);
        return r;
    }

    private LinearLayout.LayoutParams gap(int top) {
        LinearLayout.LayoutParams p = full();
        p.topMargin = dp(this, top);
        return p;
    }
}
