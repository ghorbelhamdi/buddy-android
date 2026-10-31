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
        final boolean[] responses = {adding || !p.chatOnly};
        FrameLayout responsesBox = new FrameLayout(this); // rebuilt when a preset changes it
        Runnable responsesRow = () -> {
            responsesBox.removeAllViews();
            responsesBox.addView(switchRow(this, getString(R.string.api_keys_responses), null, responses[0],
                    on -> responses[0] = on));
        };
        responsesRow.run();
        if (adding) { // a preset fills in the addresses
            List<String[]> opts = new ArrayList<>();
            for (String[] pr : Providers.PRESETS) opts.add(new String[]{pr[0], pr[0]});
            opts.add(new String[]{"", getString(R.string.api_keys_preset_other)});
            LinearLayout seg = segmented(this, opts.toArray(new String[0][]), null, false, v -> {
                for (String[] pr : Providers.PRESETS) {
                    if (!pr[0].equals(v)) continue;
                    name.setText(pr[0]);
                    anthropic.setText(pr[1]);
                    openai.setText(pr[2]);
                    responses[0] = !"chat".equals(pr[3]);
                    responsesRow.run();
                    return;
                }
                name.setText("");
                anthropic.setText("");
                openai.setText("");
            });
            android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(this);
            hs.setHorizontalScrollBarEnabled(false);
            hs.addView(seg);
            c.addView(hs, gap(12));
        } else {
            name.setText(p.name);
            anthropic.setText(p.anthropic);
            openai.setText(p.openai);
        }
        c.addView(name, gap(12));
        c.addView(key, gap(8));
        c.addView(anthropic, gap(8));
        c.addView(openai, gap(8));
        c.addView(responsesBox, gap(4));
        TextView info = text(this, getString(R.string.api_keys_addresses_info), 13, MUTED);
        c.addView(info, gap(6));
        TextView status = text(this, "", 13, MUTED);
        status.setVisibility(View.GONE);
        c.addView(status, gap(8));
        Button save = button(this, getString(R.string.api_keys_save), true);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(this, 52));
        sp.topMargin = dp(this, 14);
        c.addView(save, sp);
        if (!adding && p.models.length() > 0) {
            Button choose = button(this, getString(R.string.api_keys_choose, p.offered().length(), p.models.length()), false);
            LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(this, 48));
            cp.topMargin = dp(this, 8);
            c.addView(choose, cp);
            choose.setOnClickListener(v -> chooseModels(p));
        }
        if (!adding) {
            Button remove = button(this, getString(R.string.api_keys_remove), false);
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(this, 48));
            rp.topMargin = dp(this, 8);
            c.addView(remove, rp);
            remove.setOnClickListener(v -> confirmSheet(this, getString(R.string.api_keys_remove_title, p.name),
                    getString(R.string.api_keys_remove_text), getString(R.string.api_keys_remove), true, () -> {
                        Providers.remove(this, p.id);
                        forgetChats(p.id);
                        refreshAgents();
                        render();
                    }));
        }
        final Dialog d = bottomSheet(this, protect(c));
        save.setOnClickListener(v -> {
            String n = name.getText().toString().trim(), k = key.getText().toString().trim();
            String an = anthropic.getText().toString().trim(), oa = openai.getText().toString().trim();
            if (n.isEmpty()) n = !oa.isEmpty() || !an.isEmpty() ? hostName(!oa.isEmpty() ? oa : an) : "";
            if (adding && k.isEmpty()) {
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
            final String fn = n, useKey = k.isEmpty() ? Secrets.get(this, Providers.secretName(p.id)) : k;
            save.setEnabled(false);
            status.setVisibility(View.VISIBLE);
            status.setText(R.string.api_keys_saving);
            // the key is checked by listing the service's models with it: they're what the pickers offer
            new Thread(() -> {
                org.json.JSONArray models = null;
                String err = null;
                try {
                    models = Providers.list(useKey, an, oa);
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
                    q.chatOnly = !oa.isEmpty() && !responses[0];
                    q.models = m;
                    try {
                        Providers.save(this, q, k.isEmpty() ? null : k);
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
    private static boolean addressOk(String b) {
        return b.isEmpty() || b.startsWith("https://") || b.matches("http://(localhost|127\\.0\\.0\\.1)(:\\d+)?(/.*)?");
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
