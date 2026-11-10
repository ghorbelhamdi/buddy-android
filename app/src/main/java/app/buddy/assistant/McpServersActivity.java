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

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

import static app.buddy.assistant.Ui.*;

/**
 * Settings → MCP servers (issue #25): the user's own MCP servers, reached over HTTP. Add one by its address (and
 * its key, if it has one), see the tools it offers, switch it off and on, and move the list in or out as other MCP
 * clients write it. See McpServers for how the agents reach them.
 */
public class McpServersActivity extends Screen {
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
        TextView t = title(this, getString(R.string.mcp_title), 18);
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
        body.addView(text(this, getString(R.string.mcp_intro), 13, MUTED), full());
        List<McpServers.S> all = new ArrayList<>(McpServers.all());
        if (all.isEmpty()) body.addView(text(this, getString(R.string.mcp_none), 15, TEXT), gap(18));
        for (McpServers.S s : all) body.addView(serverRow(s), gap(all.indexOf(s) == 0 ? 14 : 4));
        Button add = button(this, getString(R.string.mcp_add), all.isEmpty());
        add.setOnClickListener(v -> editor(null));
        body.addView(add, tall(14));
        Button in = button(this, getString(R.string.mcp_import), false);
        in.setOnClickListener(v -> importSheet());
        body.addView(in, tall(8));
        if (!all.isEmpty()) {
            Button out = button(this, getString(R.string.mcp_export), false);
            out.setOnClickListener(v -> {
                android.content.ClipboardManager cm = (android.content.ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
                cm.setPrimaryClip(android.content.ClipData.newPlainText("mcp", McpServers.exported()));
                Toast.makeText(this, R.string.mcp_exported, Toast.LENGTH_LONG).show();
            });
            body.addView(out, tall(8));
        }
        body.addView(text(this, getString(R.string.mcp_note), 12, MUTED), gap(18));
    }

    private LinearLayout.LayoutParams gap(int top) {
        LinearLayout.LayoutParams p = full();
        p.topMargin = dp(this, top);
        return p;
    }

    private LinearLayout.LayoutParams tall(int top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(this, 48));
        p.topMargin = dp(this, top);
        return p;
    }

    private LinearLayout serverRow(McpServers.S s) {
        LinearLayout r = row(this);
        r.setPaddingRelative(dp(this, 14), dp(this, 12), dp(this, 10), dp(this, 12));
        r.setBackground(outlined(this, CARD, 16, OUTLINE, 1));
        LinearLayout t = column(this);
        TextView nm = title(this, s.id, 15);
        if (!s.on) nm.setTextColor(MUTED);
        t.addView(nm);
        t.addView(single(monoText(this, (s.on ? "" : getString(R.string.api_keys_off) + " · ") + s.host() + " · "
                + getString(R.string.mcp_tools_count, s.tools.length()), 12, MUTED)));
        r.addView(t, weight1());
        r.addView(icon(this, R.drawable.ms_chevron_right, MUTED, 22));
        r.setOnClickListener(v -> editor(s));
        return r;
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

    /** Add a server, or change one: its tools are asked for before it's saved, so what's saved is known to work. */
    private void editor(final McpServers.S s) {
        final boolean adding = s == null;
        LinearLayout c = column(this);
        c.addView(title(this, adding ? getString(R.string.mcp_add) : s.id, 18));
        final EditText name = field(getString(R.string.mcp_name_hint), false);
        final EditText url = field(getString(R.string.mcp_url_hint), false);
        final EditText key = field(getString(adding || !Secrets.has(this, McpServers.secretName(s.id))
                ? R.string.mcp_key_hint : R.string.api_keys_key_keep_hint), true);
        if (adding) c.addView(name, gap(12));
        else {
            url.setText(s.url);
            LinearLayout use = switchRow(this, getString(R.string.mcp_use), getString(R.string.mcp_use_info), s.on, on -> {
                McpServers.setOn(this, s, on);
                render();
            });
            use.setPaddingRelative(0, dp(this, 8), 0, dp(this, 4));
            c.addView(use, gap(8));
        }
        c.addView(url, gap(8));
        c.addView(key, gap(8));
        c.addView(text(this, getString(R.string.mcp_key_info), 13, MUTED), gap(6));
        final TextView status = text(this, "", 13, MUTED);
        status.setVisibility(View.GONE);
        c.addView(status, gap(8));
        final Button save = button(this, getString(R.string.mcp_save), true);
        c.addView(save, tall(14));
        final Dialog[] sheet = new Dialog[1];
        if (!adding) {
            Button remove = button(this, getString(R.string.api_keys_remove), false);
            c.addView(remove, tall(8));
            remove.setOnClickListener(v -> confirmSheet(this, getString(R.string.api_keys_remove_title, s.id),
                    getString(R.string.mcp_remove_text), getString(R.string.api_keys_remove), true, () -> {
                        sheet[0].dismiss();
                        McpServers.remove(this, s.id);
                        render();
                    }));
        }
        // what the server offers: every tool with its description, as the agents get them
        final LinearLayout tools = column(this);
        c.addView(tools, gap(16));
        if (!adding) showTools(tools, s.tools);
        sheet[0] = bottomSheet(this, protect(c));
        sheet[0].show();
        save.setOnClickListener(v -> {
            final String id = adding ? McpServers.slug(name.getText().toString()) : s.id;
            final String address = url.getText().toString().trim(), k = key.getText().toString().trim();
            if (adding && !McpServers.free(id)) {
                name.requestFocus();
                Toast.makeText(this, id.isEmpty() ? R.string.mcp_need_name : R.string.mcp_name_taken, Toast.LENGTH_LONG).show();
                return;
            }
            if (!McpServers.addressOk(address)) {
                url.requestFocus();
                Toast.makeText(this, R.string.signin_api_base_bad, Toast.LENGTH_LONG).show();
                return;
            }
            final String useKey = !k.isEmpty() ? k : adding ? null : Secrets.get(this, McpServers.secretName(s.id));
            save.setEnabled(false);
            status.setVisibility(View.VISIBLE);
            status.setTextColor(MUTED);
            status.setText(R.string.mcp_checking);
            new Thread(() -> {
                JSONArray found = null;
                String err = null;
                try {
                    found = McpServers.discover(address, McpServers.header(useKey));
                } catch (Exception e) {
                    err = e.getMessage() == null ? e.toString() : e.getMessage();
                }
                final JSONArray list = found;
                final String error = err;
                runOnUiThread(() -> {
                    save.setEnabled(true);
                    if (list == null) { // the server's own words: nothing is saved
                        status.setTextColor(ERR);
                        status.setText(getString(R.string.api_keys_failed, error));
                        return;
                    }
                    McpServers.S q = adding ? new McpServers.S(id) : s;
                    q.url = address;
                    q.tools = list;
                    try {
                        McpServers.save(this, q, k.isEmpty() ? null : k);
                    } catch (Exception e) {
                        status.setTextColor(ERR);
                        status.setText(getString(R.string.api_keys_failed, e.getMessage()));
                        return;
                    }
                    render();
                    if (adding) {
                        sheet[0].dismiss();
                        Toast.makeText(this, getString(R.string.mcp_saved, id, list.length()),
                                Toast.LENGTH_LONG).show();
                    } else {
                        status.setText(getString(R.string.mcp_saved, id, list.length()));
                        showTools(tools, list);
                    }
                });
            }, "mcp-check").start();
        });
    }

    private void showTools(LinearLayout into, JSONArray list) {
        into.removeAllViews();
        if (list.length() == 0) return;
        into.addView(sectionLabel(this, getString(R.string.mcp_tools_count, list.length())));
        for (int i = 0; i < list.length(); i++) {
            JSONObject t = list.optJSONObject(i);
            if (t == null) continue;
            TextView n = monoText(this, t.optString("sig", t.optString("name")), 13, TEXT);
            n.setPaddingRelative(0, dp(this, 8), 0, 0);
            into.addView(n, full());
            String d = t.optString("description");
            if (!d.isEmpty()) into.addView(text(this, d, 12, MUTED), full());
        }
    }

    /** Paste a list as other MCP clients keep it. */
    private void importSheet() {
        LinearLayout c = column(this);
        c.addView(title(this, getString(R.string.mcp_import), 18));
        c.addView(text(this, getString(R.string.mcp_import_info), 13, MUTED), gap(6));
        final EditText box = new EditText(this);
        box.setHint("{\"mcpServers\": {\"name\": {\"url\": \"https://…\"}}}");
        box.setHintTextColor(MUTED);
        box.setTextColor(TEXT);
        box.setTypeface(mono(this, 400));
        box.setTextSize(13);
        box.setMinLines(5);
        box.setGravity(Gravity.TOP);
        box.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        box.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);
        box.setPaddingRelative(dp(this, 12), dp(this, 12), dp(this, 12), dp(this, 12));
        box.setBackground(outlined(this, BG, 12, OUTLINE, 1));
        c.addView(box, gap(12));
        Button go = button(this, getString(R.string.mcp_import), true);
        c.addView(go, tall(14));
        final Dialog d = bottomSheet(this, protect(c));
        go.setOnClickListener(v -> {
            try {
                int[] n = McpServers.imported(this, box.getText().toString());
                d.dismiss();
                render();
                Toast.makeText(this, getString(R.string.mcp_imported, n[0], n[1]), Toast.LENGTH_LONG).show();
                if (n[0] > 0) McpServers.fillIn(this, () -> runOnUiThread(() -> {
                    if (!isFinishing() && !isDestroyed()) render();
                }));
            } catch (Exception e) {
                Toast.makeText(this, getString(R.string.api_keys_failed, e.getMessage()), Toast.LENGTH_LONG).show();
            }
        });
        d.show();
    }
}
