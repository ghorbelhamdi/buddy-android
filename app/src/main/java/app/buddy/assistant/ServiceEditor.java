package app.buddy.assistant;

import android.app.Activity;
import android.app.Dialog;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import static app.buddy.assistant.Ui.*;

/**
 * The sheets every service is set up with (see Providers), whatever it is: a key at OpenRouter, Anthropic, OpenAI or
 * DeepSeek, a server of the user's own (Ollama, LM Studio, llama.cpp), any other address. One for its name, addresses
 * and key (adding one from Add a connection or the first-run guide, changing one from its own screen), one for which
 * of its models the pickers show. Everything else about a service is on its screen (ConnectionActivity).
 */
final class ServiceEditor {
    interface Saved {
        void onSaved(Providers.P p);
    }

    private ServiceEditor() {
    }

    /** A new service. preset: the one the Service menu starts on, its addresses filled in (null: another service). */
    static void add(Activity a, String preset, Saved after) {
        editor(a, null, preset, after);
    }

    /** Its name, addresses and key. */
    static void edit(Activity a, Providers.P p, Saved after) {
        editor(a, p, null, after);
    }

    private static void editor(Activity a, Providers.P p, String preset, Saved after) {
        boolean adding = p == null;
        LinearLayout c = column(a);
        c.addView(title(a, adding ? a.getString(R.string.api_keys_add) : p.name, 18));
        EditText name = field(a, a.getString(R.string.api_keys_name_hint), false);
        EditText key = field(a, "", true);
        EditText anthropic = field(a, a.getString(R.string.api_keys_anthropic_hint), false);
        EditText openai = field(a, a.getString(R.string.api_keys_openai_hint), false);
        // for a server of your own: no key, and a PC's address instead of localhost
        TextView localInfo = text(a, a.getString(R.string.api_keys_local_info), 13, MUTED);
        // the one real difference between a service online and a server of your own: there the key is optional, and
        // the field and the note under the addresses say so, following the addresses as they're typed
        Runnable local = () -> {
            boolean own = localOnly(anthropic.getText().toString().trim(), openai.getText().toString().trim());
            key.setHint(a.getString(own ? R.string.api_keys_key_optional_hint : adding ? R.string.api_keys_key_hint
                    : R.string.api_keys_key_keep_hint));
            localInfo.setVisibility(own ? View.VISIBLE : View.GONE);
        };
        android.text.TextWatcher typed = new android.text.TextWatcher() {
            public void beforeTextChanged(CharSequence s, int x, int y, int n) {
            }

            public void onTextChanged(CharSequence s, int x, int y, int n) {
            }

            public void afterTextChanged(android.text.Editable e) {
                local.run();
            }
        };
        if (adding) { // a known service fills in its addresses; "Another service" leaves them to the user
            LinearLayout menu = row(a);
            menu.setGravity(Gravity.CENTER_VERTICAL);
            menu.setPaddingRelative(dp(a, 12), dp(a, 12), dp(a, 10), dp(a, 12));
            menu.setBackground(outlined(a, BG, 12, OUTLINE, 1));
            TextView chosen = text(a, a.getString(R.string.conn_custom), 15, TEXT);
            menu.addView(chosen, weight1());
            menu.addView(icon(a, R.drawable.ms_expand_more, MUTED, 22));
            menu.setOnClickListener(v -> {
                android.widget.PopupMenu pm = new android.widget.PopupMenu(
                        new android.view.ContextThemeWrapper(a, android.R.style.Theme_DeviceDefault_DayNight), menu);
                for (String[] pr : Providers.PRESETS) pm.getMenu().add(pr[0]);
                pm.getMenu().add(a.getString(R.string.conn_custom));
                pm.setOnMenuItemClickListener(it -> {
                    String picked = String.valueOf(it.getTitle());
                    chosen.setText(picked);
                    String[] known = null;
                    for (String[] pr : Providers.PRESETS) if (pr[0].equals(picked)) known = pr;
                    name.setText(known == null ? "" : known[0]);
                    anthropic.setText(known == null ? "" : known[1]);
                    openai.setText(known == null ? "" : known[2]);
                    return true;
                });
                pm.show();
            });
            for (String[] pr : Providers.PRESETS) {
                if (!pr[0].equals(preset)) continue;
                chosen.setText(pr[0]);
                name.setText(pr[0]);
                anthropic.setText(pr[1]);
                openai.setText(pr[2]);
            }
            c.addView(text(a, a.getString(R.string.api_keys_service), 13, MUTED), gap(a, 12));
            c.addView(menu, gap(a, 6));
        } else {
            name.setText(p.name);
            anthropic.setText(p.anthropic);
            openai.setText(p.openai);
        }
        local.run();
        anthropic.addTextChangedListener(typed);
        openai.addTextChangedListener(typed);
        c.addView(name, gap(a, 12));
        c.addView(key, gap(a, 8));
        c.addView(anthropic, gap(a, 8));
        c.addView(openai, gap(a, 8));
        c.addView(text(a, a.getString(R.string.api_keys_addresses_info), 13, MUTED), gap(a, 6));
        c.addView(localInfo, gap(a, 6));
        TextView status = text(a, "", 13, MUTED);
        status.setVisibility(View.GONE);
        c.addView(status, gap(a, 8));
        Button save = button(a, a.getString(R.string.api_keys_save), true);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(a, 52));
        sp.topMargin = dp(a, 14);
        c.addView(save, sp);
        Button checkNow = button(a, a.getString(R.string.api_keys_check), false);
        LinearLayout.LayoutParams kp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(a, 48));
        kp.topMargin = dp(a, 8);
        c.addView(checkNow, kp);
        c.addView(text(a, a.getString(R.string.api_keys_check_info), 12, MUTED), gap(a, 4));
        checkNow.setOnClickListener(v -> {
            String k = key.getText().toString().trim();
            String an = anthropic.getText().toString().trim(), oa = openai.getText().toString().trim();
            String saved = !k.isEmpty() || adding ? k : Secrets.get(a, Providers.secretName(p.id));
            final String useKey = (saved == null || saved.isEmpty()) && localOnly(an, oa) ? "" : saved;
            if (useKey == null || useKey.isEmpty() && !localOnly(an, oa)) {
                key.requestFocus();
                Toast.makeText(a, R.string.api_keys_need_key, Toast.LENGTH_SHORT).show();
                return;
            }
            if (an.isEmpty() && oa.isEmpty()) {
                Toast.makeText(a, R.string.api_keys_need_address, Toast.LENGTH_SHORT).show();
                return;
            }
            if (!addressOk(an) || !addressOk(oa)) {
                Toast.makeText(a, R.string.signin_api_base_bad, Toast.LENGTH_LONG).show();
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
                    report = a.getString(R.string.api_keys_failed, e.getMessage());
                }
                final String said = report, use = better;
                a.runOnUiThread(() -> {
                    checkNow.setEnabled(true);
                    status.setText(said);
                    if (!use.isEmpty()) openai.setText(use); // it answered with /v1 added: saved if the user saves
                });
            }, "service-check").start();
        });
        final Dialog d = bottomSheet(a, protect(c)); // agents never see or change keys, even when they may tap Buddy's screens
        save.setOnClickListener(v -> {
            String n = name.getText().toString().trim(), k = key.getText().toString().trim();
            String an = anthropic.getText().toString().trim(), oa = openai.getText().toString().trim();
            if (n.isEmpty()) n = !oa.isEmpty() || !an.isEmpty() ? hostName(!oa.isEmpty() ? oa : an) : "";
            if (adding && k.isEmpty() && !localOnly(an, oa)) {
                key.requestFocus();
                Toast.makeText(a, R.string.api_keys_need_key, Toast.LENGTH_SHORT).show();
                return;
            }
            if (an.isEmpty() && oa.isEmpty()) {
                Toast.makeText(a, R.string.api_keys_need_address, Toast.LENGTH_SHORT).show();
                return;
            }
            if (!addressOk(an) || !addressOk(oa)) {
                Toast.makeText(a, R.string.signin_api_base_bad, Toast.LENGTH_LONG).show();
                return;
            }
            // a local server without a key keeps an empty one, so the relay still finds the service
            String old = adding ? "" : Secrets.get(a, Providers.secretName(p.id));
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
                a.runOnUiThread(() -> {
                    if (a.isFinishing() || a.isDestroyed()) return;
                    if (m == null) { // the service's own words
                        save.setEnabled(true);
                        status.setText(a.getString(R.string.api_keys_failed, error));
                        return;
                    }
                    Providers.P q = adding ? new Providers.P(Providers.newId(fn)) : p;
                    final String oldAn = adding ? "" : p.anthropic, oldOa = adding ? "" : p.openai;
                    q.name = fn;
                    q.anthropic = an;
                    q.openai = oa;
                    q.models = m;
                    try {
                        Providers.save(a, q, !k.isEmpty() ? k : adding || old == null ? useKey : null);
                    } catch (Exception e) {
                        save.setEnabled(true);
                        status.setText(e.getMessage());
                        return;
                    }
                    // a key pasted in place of one OpenRouter made for Buddy isn't that one: no "sign out", no note
                    if (!k.isEmpty()) Connections.setSignedIn(a, q.id, false);
                    // a new key, or new addresses, is checked (see Connections.check)
                    Connections.saved(a, q, adding || !k.isEmpty() || !an.equals(oldAn) || !oa.equals(oldOa));
                    d.dismiss();
                    if (after != null) after.onSaved(q);
                });
            }, "service-save").start();
        });
        d.show();
    }

    /** Which of the service's models the pickers show: a search box and a tick per model. */
    static void chooseModels(Activity a, Providers.P p, Runnable after) {
        LinearLayout c = column(a);
        c.addView(title(a, a.getString(R.string.api_keys_choose_title, p.name), 18));
        c.addView(text(a, a.getString(R.string.api_keys_choose_info), 13, MUTED), gap(a, 4));
        EditText search = field(a, a.getString(R.string.api_keys_choose_search), false);
        c.addView(search, gap(a, 10));
        LinearLayout list = column(a);
        c.addView(list, gap(a, 6));
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
                LinearLayout r = row(a);
                r.setGravity(Gravity.CENTER_VERTICAL);
                r.setPaddingRelative(dp(a, 4), dp(a, 8), dp(a, 4), dp(a, 8));
                r.setBackgroundResource(ripple(a, false));
                android.widget.CheckBox cb = new android.widget.CheckBox(a);
                cb.setChecked(picked.contains(id));
                cb.setClickable(false);
                r.addView(cb);
                LinearLayout t = column(a);
                t.setPaddingRelative(dp(a, 8), 0, 0, 0);
                t.addView(title(a, name, 15));
                t.addView(single(monoText(a, Models.detail("opencode", Providers.ref(p, id), ""), 12, MUTED)));
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
            public void beforeTextChanged(CharSequence s, int x, int y, int n) {
            }

            public void onTextChanged(CharSequence s, int x, int y, int n) {
            }

            public void afterTextChanged(android.text.Editable e) {
                fill.run();
            }
        });
        Button done = button(a, a.getString(R.string.api_keys_save), true);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(a, 52));
        lp.topMargin = dp(a, 14);
        c.addView(done, lp);
        final Dialog d = bottomSheet(a, protect(c));
        done.setOnClickListener(v -> {
            p.shown.clear();
            p.shown.addAll(picked);
            try {
                Providers.save(a, p, null);
            } catch (Exception ignored) {
            }
            d.dismiss();
            if (after != null) after.run();
        });
        d.show();
    }

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

    private static EditText field(Activity a, String hint, boolean secret) {
        EditText et = new EditText(a);
        et.setHint(hint);
        et.setHintTextColor(MUTED);
        et.setTextColor(TEXT);
        et.setTypeface(mono(a, 400));
        et.setTextSize(14);
        et.setSingleLine(true);
        et.setInputType(secret ? android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                : android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_VARIATION_URI);
        if (secret) et.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        et.setPaddingRelative(dp(a, 12), dp(a, 12), dp(a, 12), dp(a, 12));
        et.setBackground(outlined(a, BG, 12, OUTLINE, 1));
        return et;
    }

    private static LinearLayout.LayoutParams gap(Activity a, int top) {
        LinearLayout.LayoutParams p = full();
        p.topMargin = dp(a, top);
        return p;
    }
}
