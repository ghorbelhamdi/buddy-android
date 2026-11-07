package app.buddy.assistant;

import android.app.Dialog;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.List;
import static app.buddy.assistant.Ui.*;

/**
 * Settings → API keys: other services whose models Claude Code, Codex and OpenCode can use (see Providers), and what
 * each of those agents uses for chats on Default.
 */
public class ApiKeysActivity extends Screen {
    /** The agents that can use a service. */
    private static final String[] AGENTS = {"claude", "codex", "opencode"};

    private LinearLayout body;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = column(this);
        root.setBackgroundColor(BG);
        LinearLayout top = row(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPaddingRelative(dp(this, 4), dp(this, 4), dp(this, 16), dp(this, 4));
        ImageButton back = iconButton(this, R.drawable.ms_arrow_back, getString(R.string.perms_back));
        back.setLayoutParams(new LinearLayout.LayoutParams(dp(this, 44), dp(this, 44)));
        back.setOnClickListener(v -> finish());
        top.addView(back);
        TextView t = title(this, getString(R.string.api_keys_title), 18);
        t.setPaddingRelative(dp(this, 6), 0, 0, 0);
        top.addView(t, weight1());
        root.addView(top, full());
        ScrollView sv = new ScrollView(this);
        body = column(this);
        body.setPaddingRelative(dp(this, 20), dp(this, 4), dp(this, 20), dp(this, 32));
        sv.addView(body);
        root.addView(sv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(BG);
        frame.setFitsSystemWindows(true);
        frame.addView(root);
        setContentView(protect(frame)); // agents never see or change keys, even when they may tap Buddy's screens
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
    }

    private void render() {
        body.removeAllViews();
        body.addView(text(this, getString(R.string.api_keys_intro), 13, MUTED), full());
        List<Providers.P> all = new ArrayList<>(Providers.all());
        if (all.isEmpty()) body.addView(text(this, getString(R.string.api_keys_none), 15, TEXT), gap(18));
        for (Providers.P p : all) body.addView(serviceRow(p), gap(all.indexOf(p) == 0 ? 14 : 4));
        Button add = button(this, getString(R.string.api_keys_add), all.isEmpty());
        add.setOnClickListener(v -> editor(null));
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(this, 48));
        ap.topMargin = dp(this, 14);
        body.addView(add, ap);

        // what a chat on Default uses, for each agent that has a service it can use
        boolean any = false;
        for (String agent : AGENTS) {
            if (Providers.forAgent(agent).isEmpty()) continue;
            if (!any) {
                TextView l = sectionLabel(this, getString(R.string.api_keys_defaults));
                body.addView(l, gap(26));
                body.addView(text(this, getString(R.string.api_keys_defaults_info), 13, MUTED), gap(2));
                any = true;
            }
            body.addView(defaultRow(agent), gap(6));
        }
    }

    private LinearLayout serviceRow(Providers.P p) {
        LinearLayout r = row(this);
        r.setPaddingRelative(dp(this, 14), dp(this, 12), dp(this, 10), dp(this, 12));
        r.setBackground(outlined(this, CARD, 16, OUTLINE, 1));
        LinearLayout t = column(this);
        t.addView(title(this, p.name, 15));
        t.addView(single(monoText(this, getString(R.string.api_keys_models, p.host(), p.offered().length()), 12, MUTED)));
        List<String> names = new ArrayList<>();
        for (String a : AGENTS) if (p.fits(a)) names.add(Account.nameOf(a));
        t.addView(text(this, getString(R.string.api_keys_for, android.text.TextUtils.join(", ", names)), 12, MUTED));
        r.addView(t, weight1());
        r.addView(icon(this, R.drawable.ms_chevron_right, MUTED, 22));
        r.setOnClickListener(v -> editor(p));
        return r;
    }

    private LinearLayout defaultRow(String agent) {
        LinearLayout r = row(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPaddingRelative(0, dp(this, 8), 0, dp(this, 8));
        r.setBackgroundResource(ripple(this, false));
        r.addView(agentTile(this, agent));
        LinearLayout t = column(this);
        t.setPaddingRelative(dp(this, 12), 0, dp(this, 8), 0);
        t.addView(title(this, Account.nameOf(agent), 15));
        String d = Models.agentDefault(agent);
        t.addView(single(monoText(this, d.isEmpty() ? getString(R.string.api_keys_own_default) : Models.label(agent, d), 12, MUTED)));
        r.addView(t, weight1());
        r.addView(icon(this, R.drawable.ms_chevron_right, MUTED, 22));
        r.setOnClickListener(v -> pickDefault(agent));
        return r;
    }

    /** The agent's own default, or one of its services' models. */
    private void pickDefault(String agent) {
        LinearLayout c = column(this);
        TextView h = title(this, getString(R.string.chat_model_title, Account.nameOf(agent)), 18);
        h.setPaddingRelative(0, 0, 0, dp(this, 8));
        c.addView(h);
        final Dialog[] d = new Dialog[1];
        String now = Models.agentDefault(agent);
        c.addView(pickRow(getString(R.string.api_keys_own_default), Models.defaultHint(agent), now.isEmpty(), () -> {
            d[0].dismiss();
            setDefault(agent, "");
        }), full());
        for (Providers.P p : Providers.forAgent(agent)) {
            TextView sl = sectionLabel(this, p.name);
            sl.setPaddingRelative(0, dp(this, 14), 0, dp(this, 2));
            c.addView(sl);
            org.json.JSONArray models = p.offered();
            for (int i = 0; i < models.length(); i++) {
                String id = models.optJSONObject(i).optString("id");
                String ref = Providers.ref(p, id);
                c.addView(pickRow(models.optJSONObject(i).optString("name"), Models.detail(agent, ref, ""),
                        ref.equals(now), () -> {
                            d[0].dismiss();
                            setDefault(agent, ref);
                        }), full());
            }
        }
        d[0] = bottomSheet(this, c);
        d[0].show();
    }

    private LinearLayout pickRow(String name, String sub, boolean on, Runnable l) {
        LinearLayout r = row(this);
        r.setPaddingRelative(dp(this, 4), dp(this, 10), dp(this, 4), dp(this, 10));
        r.setBackgroundResource(ripple(this, false));
        r.addView(radio(this, on));
        LinearLayout t = column(this);
        t.setPaddingRelative(dp(this, 14), 0, 0, 0);
        t.addView(title(this, name, 15));
        if (!sub.isEmpty()) t.addView(single(monoText(this, sub, 12, MUTED)));
        r.addView(t, weight1());
        r.setOnClickListener(v -> l.run());
        return r;
    }

    private void setDefault(String agent, String ref) {
        Prefs.setAgentDefault(this, agent, ref);
        ChatHub.get(this).changed();
        render();
    }

    /** Add a service (p null) or change one: its name, key, addresses; its models are listed with the key to check it. */
    private void editor(Providers.P p) {
        boolean adding = p == null;
        LinearLayout c = column(this);
        c.addView(title(this, adding ? getString(R.string.api_keys_add) : p.name, 18));
        EditText name = field(getString(R.string.api_keys_name_hint), false);
        EditText key = field(getString(adding ? R.string.api_keys_key_hint : R.string.api_keys_key_keep_hint), true);
        EditText anthropic = field(getString(R.string.api_keys_anthropic_hint), false);
        EditText openai = field(getString(R.string.api_keys_openai_hint), false);
        // for a server of your own: no key, and a PC's address instead of localhost
        TextView localInfo = text(this, getString(R.string.api_keys_local_info), 13, MUTED);
        localInfo.setVisibility(!adding && Providers.isLocal(p.openai.isEmpty() ? p.anthropic : p.openai) ? View.VISIBLE : View.GONE);
        if (adding) { // a known service fills in its addresses; Custom leaves them to the user
            LinearLayout menu = row(this);
            menu.setGravity(Gravity.CENTER_VERTICAL);
            menu.setPaddingRelative(dp(this, 12), dp(this, 12), dp(this, 10), dp(this, 12));
            menu.setBackground(outlined(this, BG, 12, OUTLINE, 1));
            TextView chosen = text(this, getString(R.string.api_keys_custom), 15, TEXT);
            menu.addView(chosen, weight1());
            menu.addView(icon(this, R.drawable.ms_expand_more, MUTED, 22));
            menu.setOnClickListener(v -> {
                android.widget.PopupMenu pm = new android.widget.PopupMenu(
                        new android.view.ContextThemeWrapper(this, android.R.style.Theme_DeviceDefault_DayNight), menu);
                for (String[] pr : Providers.PRESETS) pm.getMenu().add(pr[0]);
                pm.getMenu().add(getString(R.string.api_keys_custom));
                pm.setOnMenuItemClickListener(it -> {
                    String picked = String.valueOf(it.getTitle());
                    chosen.setText(picked);
                    String[] known = null;
                    for (String[] pr : Providers.PRESETS) if (pr[0].equals(picked)) known = pr;
                    name.setText(known == null ? "" : known[0]);
                    anthropic.setText(known == null ? "" : known[1]);
                    openai.setText(known == null ? "" : known[2]);
                    localInfo.setVisibility(known != null && Providers.isLocal(known[2]) ? View.VISIBLE : View.GONE);
                    return true;
                });
                pm.show();
            });
            c.addView(text(this, getString(R.string.api_keys_service), 13, MUTED), gap(12));
            c.addView(menu, gap(6));
        } else {
            name.setText(p.name);
            anthropic.setText(p.anthropic);
            openai.setText(p.openai);
        }
        c.addView(name, gap(12));
        c.addView(key, gap(8));
        c.addView(anthropic, gap(8));
        c.addView(openai, gap(8));
        c.addView(text(this, getString(R.string.api_keys_addresses_info), 13, MUTED), gap(6));
        c.addView(localInfo, gap(6));
        TextView status = text(this, "", 13, MUTED);
        status.setVisibility(View.GONE);
        c.addView(status, gap(8));
        Button save = button(this, getString(R.string.api_keys_save), true);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(this, 52));
        sp.topMargin = dp(this, 14);
        c.addView(save, sp);
        Button checkNow = button(this, getString(R.string.api_keys_check), false);
        LinearLayout.LayoutParams kp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(this, 48));
        kp.topMargin = dp(this, 8);
        c.addView(checkNow, kp);
        c.addView(text(this, getString(R.string.api_keys_check_info), 12, MUTED), gap(4));
        checkNow.setOnClickListener(v -> {
            String k = key.getText().toString().trim();
            String an = anthropic.getText().toString().trim(), oa = openai.getText().toString().trim();
            String saved = !k.isEmpty() || adding ? k : Secrets.get(this, Providers.secretName(p.id));
            final String useKey = (saved == null || saved.isEmpty()) && localOnly(an, oa) ? "" : saved;
            if (useKey == null || useKey.isEmpty() && !localOnly(an, oa)) {
                key.requestFocus();
                Toast.makeText(this, R.string.api_keys_need_key, Toast.LENGTH_SHORT).show();
                return;
            }
            if (an.isEmpty() && oa.isEmpty()) {
                Toast.makeText(this, R.string.api_keys_need_address, Toast.LENGTH_SHORT).show();
                return;
            }
            if (!addressOk(an) || !addressOk(oa)) {
                Toast.makeText(this, R.string.signin_api_base_bad, Toast.LENGTH_LONG).show();
                return;
            }
            checkNow.setEnabled(false);
            status.setVisibility(View.VISIBLE);
            status.setText(R.string.api_keys_saving);
            new Thread(() -> {
                String report, better = "";
                try {
                    String[] r = Providers.check(useKey, an, oa);
                    report = r[0];
                    better = r[1];
                } catch (Exception e) {
                    report = getString(R.string.api_keys_failed, e.getMessage());
                }
                final String said = report, use = better;
                runOnUiThread(() -> {
                    checkNow.setEnabled(true);
                    status.setText(said);
                    if (!use.isEmpty()) openai.setText(use); // it answered with /v1 added: saved if the user saves
                });
            }, "api-key-check").start();
        });
        if (!adding && p.models.length() > 0) {
            Button choose = button(this, getString(R.string.api_keys_choose, p.offered().length(), p.models.length()), false);
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(this, 48));
            cp.topMargin = dp(this, 8);
            c.addView(choose, cp);
            choose.setOnClickListener(v -> chooseModels(p));
        }
        final Dialog[] sheet = new Dialog[1];
        if (!adding) {
            Button remove = button(this, getString(R.string.api_keys_remove), false);
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(this, 48));
            rp.topMargin = dp(this, 8);
            c.addView(remove, rp);
            remove.setOnClickListener(v -> confirmSheet(this, getString(R.string.api_keys_remove_title, p.name),
                    getString(R.string.api_keys_remove_text), getString(R.string.api_keys_remove), true, () -> {
                        sheet[0].dismiss();
                        Providers.remove(this, p.id);
                        forgetChats(p.id);
                        refreshAgents();
                        render();
                    }));
        }
        final Dialog d = bottomSheet(this, protect(c));
        sheet[0] = d;
        save.setOnClickListener(v -> {
            String n = name.getText().toString().trim(), k = key.getText().toString().trim();
            String an = anthropic.getText().toString().trim(), oa = openai.getText().toString().trim();
            if (n.isEmpty()) n = !oa.isEmpty() || !an.isEmpty() ? hostName(!oa.isEmpty() ? oa : an) : "";
            if (adding && k.isEmpty() && !localOnly(an, oa)) {
                key.requestFocus();
                Toast.makeText(this, R.string.api_keys_need_key, Toast.LENGTH_SHORT).show();
                return;
            }
            if (an.isEmpty() && oa.isEmpty()) {
                Toast.makeText(this, R.string.api_keys_need_address, Toast.LENGTH_SHORT).show();
                return;
            }
            if (!addressOk(an) || !addressOk(oa)) {
                Toast.makeText(this, R.string.signin_api_base_bad, Toast.LENGTH_LONG).show();
                return;
            }
            // a local server without a key keeps an empty one, so the relay still finds the service
            String old = adding ? "" : Secrets.get(this, Providers.secretName(p.id));
            final String fn = n, useKey = !k.isEmpty() ? k : old != null ? old : localOnly(an, oa) ? "" : null;
            save.setEnabled(false);
            status.setVisibility(View.VISIBLE);
            status.setText(R.string.api_keys_saving);
            // the key is checked by listing the service's models with it: they're what the pickers offer
            new Thread(() -> {
                org.json.JSONArray models = null;
                String err = null;
                try { // a new name isn't a reason to ask a service again; a server of your own is, since it costs
                    // nothing and you may have added models to it
                    boolean same = !adding && k.isEmpty() && an.equals(p.anthropic) && oa.equals(p.openai) && p.models.length() > 0
                            && !localOnly(an, oa);
                    models = same ? p.models : Providers.list(useKey, an, oa);
                } catch (Exception e) {
                    err = e.getMessage();
                }
                final org.json.JSONArray m = models;
                final String error = err;
                runOnUiThread(() -> {
                    if (m == null) {
                        save.setEnabled(true);
                        status.setText(getString(R.string.api_keys_failed, error));
                        return;
                    }
                    Providers.P q = adding ? new Providers.P(Providers.newId(fn)) : p;
                    q.name = fn;
                    q.anthropic = an;
                    q.openai = oa;
                    q.models = m;
                    try {
                        Providers.save(this, q, !k.isEmpty() ? k : adding || old == null ? useKey : null);
                    } catch (Exception e) {
                        save.setEnabled(true);
                        status.setText(e.getMessage());
                        return;
                    }
                    // an agent with no plan uses its first service by default, so its chats run
                    for (String a : AGENTS) {
                        if (q.fits(a) && Prefs.agentDefault(this, a).isEmpty() && !"opencode".equals(a)
                                && !SetupState.planSignedIn(this, a)) Prefs.setAgentDefault(this, a, Providers.ref(q, q.defaultModel()));
                    }
                    d.dismiss();
                    refreshAgents();
                    render();
                });
            }, "api-key").start();
        });
        d.show();
    }

    /** Which of the service's models the pickers show: a search box and a tick per model. */
    private void chooseModels(Providers.P p) {
        LinearLayout c = column(this);
        c.addView(title(this, getString(R.string.api_keys_choose_title, p.name), 18));
        c.addView(text(this, getString(R.string.api_keys_choose_info), 13, MUTED), gap(4));
        EditText search = field(getString(R.string.api_keys_choose_search), false);
        c.addView(search, gap(10));
        LinearLayout list = column(this);
        c.addView(list, gap(6));
        java.util.Set<String> picked = new java.util.LinkedHashSet<>(p.shown);
        Runnable fill = () -> {
            list.removeAllViews();
            String q = search.getText().toString().trim().toLowerCase(java.util.Locale.ROOT);
            int n = 0;
            for (int i = 0; i < p.models.length() && n < 200; i++) {
                org.json.JSONObject m = p.models.optJSONObject(i);
                String id = m.optString("id"), name = m.optString("name");
                if (!q.isEmpty() && !id.toLowerCase(java.util.Locale.ROOT).contains(q)
                        && !name.toLowerCase(java.util.Locale.ROOT).contains(q)) continue;
                n++;
                LinearLayout r = row(this);
                r.setGravity(Gravity.CENTER_VERTICAL);
                r.setPaddingRelative(dp(this, 4), dp(this, 8), dp(this, 4), dp(this, 8));
                r.setBackgroundResource(ripple(this, false));
                android.widget.CheckBox cb = new android.widget.CheckBox(this);
                cb.setChecked(picked.contains(id));
                cb.setClickable(false);
                r.addView(cb);
                LinearLayout t = column(this);
                t.setPaddingRelative(dp(this, 8), 0, 0, 0);
                t.addView(title(this, name, 15));
                t.addView(single(monoText(this, Models.detail("opencode", Providers.ref(p, id), ""), 12, MUTED)));
                r.addView(t, weight1());
                r.setOnClickListener(v -> {
                    if (!picked.remove(id)) picked.add(id);
                    cb.setChecked(picked.contains(id));
                });
                list.addView(r, full());
            }
        };
        fill.run();
        search.addTextChangedListener(new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int a, int b, int n) {
            }

            public void onTextChanged(CharSequence s, int a, int b, int n) {
            }

            public void afterTextChanged(android.text.Editable e) {
                fill.run();
            }
        });
        Button done = button(this, getString(R.string.api_keys_save), true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(this, 52));
        lp.topMargin = dp(this, 14);
        c.addView(done, lp);
        final Dialog d = bottomSheet(this, protect(c));
        done.setOnClickListener(v -> {
            p.shown.clear();
            p.shown.addAll(picked);
            try {
                Providers.save(this, p, null);
            } catch (Exception ignored) {
            }
            d.dismiss();
            render();
        });
        d.show();
    }

    /** HTTPS, or this phone itself (a local proxy), or empty. */
    /** https anywhere; plain http only on this phone or the user's own network (Providers.isLocal). */
    private static boolean addressOk(String b) {
        return b.isEmpty() || b.startsWith("https://") || b.startsWith("http://") && Providers.isLocal(b);
    }

    /** Every address given is on this phone or the user's network: a key is optional (local servers don't use one). */
    private static boolean localOnly(String an, String oa) {
        return !(an + oa).isEmpty() && (an.isEmpty() || Providers.isLocal(an)) && (oa.isEmpty() || Providers.isLocal(oa));
    }

    /** "https://api.example.com/v1" → "Example". */
    private static String hostName(String url) {
        String h = android.net.Uri.parse(url).getHost();
        if (h == null) return "API";
        h = h.replaceFirst("^api\\.", "").replaceFirst("\\..*$", "");
        return h.isEmpty() ? "API" : h.substring(0, 1).toUpperCase(java.util.Locale.ROOT) + h.substring(1);
    }

    /** Chats that picked a removed service's model go back to Default. */
    private void forgetChats(String id) {
        ChatHub hub = ChatHub.get(this);
        for (Sessions.Session s : new ArrayList<>(hub.sessions.all)) {
            if (id.equals(Providers.idOf(s.model))) hub.setModel(s, "");
        }
    }

    /** Claude Code and Codex count as signed in with a service they can use: their state changes with the list. */
    private void refreshAgents() {
        Account.CLAUDE.refresh(this);
        Account.CODEX.refresh(this);
        ChatHub.get(this).changed();
    }

    private EditText field(String hint, boolean secret) {
        EditText et = new EditText(this);
        et.setHint(hint);
        et.setHintTextColor(MUTED);
        et.setTextColor(TEXT);
        et.setTypeface(mono(this, 400));
        et.setTextSize(14);
        et.setSingleLine(true);
        et.setInputType(secret ? android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                : android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        if (secret) et.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        et.setPaddingRelative(dp(this, 12), dp(this, 12), dp(this, 12), dp(this, 12));
        et.setBackground(outlined(this, BG, 12, OUTLINE, 1));
        return et;
    }

    private LinearLayout.LayoutParams gap(int top) {
        LinearLayout.LayoutParams p = full();
        p.topMargin = dp(this, top);
        return p;
    }
}
