package app.buddy.assistant;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.List;
import static app.buddy.assistant.Ui.*;

/** Settings → Other devices: the switch, the address to open, and the paired devices (see WebDevices). */
public class OtherDevicesActivity extends Screen {
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
        TextView t = title(this, getString(R.string.web_title), 18);
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
        setContentView(protect(frame)); // only the user lets devices in or removes them
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
    }

    private void render() {
        body.removeAllViews();
        body.addView(text(this, getString(R.string.web_intro), 13, MUTED), full());
        LinearLayout sw = switchRow(this, getString(R.string.web_switch), getString(R.string.web_switch_info),
                WebDevices.on(this), on -> {
                    WebDevices.setOn(this, on);
                    render();
                });
        sw.setBackground(outlined(this, CARD, 16, OUTLINE, 1));
        body.addView(sw, gap(14));
        if (WebDevices.on(this)) { // (only the phone turns this on: a paired device can only look)
            LinearLayout scr = switchRow(this, getString(R.string.web_screens_switch), getString(R.string.web_screens_info),
                    Prefs.webScreens(this), on -> {
                        Prefs.setWebScreens(this, on);
                        WebDevices.changed();
                    });
            scr.setBackground(outlined(this, CARD, 16, OUTLINE, 1));
            body.addView(scr, gap(8));
        }
        if (WebDevices.on(this)) {
            String url = WebDevices.url(this);
            LinearLayout box = column(this);
            box.setBackground(outlined(this, CARD, 16, OUTLINE, 1));
            box.setPaddingRelative(dp(this, 16), dp(this, 14), dp(this, 16), dp(this, 14));
            box.addView(text(this, getString(R.string.web_address_label), 13, MUTED));
            TextView addr = text(this, url == null ? getString(R.string.web_no_tailscale) : url, url == null ? 14 : 16, TEXT);
            if (url != null) addr.setTypeface(android.graphics.Typeface.MONOSPACE);
            addr.setTextIsSelectable(true);
            box.addView(addr, gap(6));
            if (url != null) {
                Button copy = button(this, getString(R.string.web_copy), false);
                copy.setOnClickListener(v -> {
                    getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("Buddy", url));
                    Toast.makeText(this, R.string.web_copied, Toast.LENGTH_SHORT).show();
                });
                LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(this, 40));
                cp.topMargin = dp(this, 10);
                box.addView(copy, cp);
            }
            body.addView(box, gap(12));
        }
        body.addView(title(this, getString(R.string.web_devices_title), 15), gap(20));
        List<WebDevices.Device> ds = WebDevices.devices(this);
        if (ds.isEmpty()) body.addView(text(this, getString(R.string.web_none), 14, MUTED), gap(6));
        for (WebDevices.Device d : ds) {
            LinearLayout r = row(this);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setBackground(outlined(this, CARD, 16, OUTLINE, 1));
            r.setPaddingRelative(dp(this, 16), dp(this, 10), dp(this, 8), dp(this, 10));
            LinearLayout tx = column(this);
            tx.addView(single(title(this, d.name, 15)));
            tx.addView(text(this, getString(R.string.web_device_seen, android.text.format.DateUtils
                    .getRelativeTimeSpanString(Math.max(d.seen, d.added), System.currentTimeMillis(), 60000)), 13, MUTED));
            r.addView(tx, weight1());
            Button rm = button(this, getString(R.string.web_remove), false);
            rm.setTextColor(ERR);
            rm.setOnClickListener(v -> confirmSheet(this, getString(R.string.web_remove_title, d.name),
                    getString(R.string.web_remove_text), getString(R.string.web_remove), true, () -> {
                        WebDevices.remove(this, d.id);
                        render();
                    }));
            r.addView(rm, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(this, 40)));
            body.addView(r, gap(8));
        }
        body.addView(text(this, getString(R.string.web_pair_how), 13, MUTED), gap(10));
        computers();
    }

    /** Computers agents can work on, over SSH (see Computers). */
    private void computers() {
        body.addView(title(this, getString(R.string.computers_title), 15), gap(28));
        body.addView(text(this, getString(R.string.computers_intro), 13, MUTED), gap(4));
        for (Computers.Computer m : Computers.all(this)) {
            LinearLayout r = row(this);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setBackground(outlined(this, CARD, 16, OUTLINE, 1));
            r.setPaddingRelative(dp(this, 16), dp(this, 10), dp(this, 8), dp(this, 10));
            LinearLayout tx = column(this);
            tx.addView(single(title(this, m.name, 15)));
            TextView w = text(this, m.where() + " · " + getString(R.string.computers_mode_each), 13, MUTED);
            tx.addView(w);
            r.addView(tx, weight1());
            Button rm = button(this, getString(R.string.web_remove), false);
            rm.setTextColor(ERR);
            rm.setOnClickListener(v -> confirmSheet(this, getString(R.string.web_remove_title, m.name),
                    getString(R.string.computers_remove_text), getString(R.string.web_remove), true, () -> {
                        Computers.remove(this, m.id);
                        render();
                    }));
            r.addView(rm, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(this, 40)));
            body.addView(r, gap(8));
        }
        Button add = button(this, getString(R.string.computers_add), true);
        add.setOnClickListener(v -> addSheet());
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(this, 44));
        ap.topMargin = dp(this, 12);
        body.addView(add, ap);
    }

    private android.widget.EditText field(String hint, String value) {
        android.widget.EditText et = new android.widget.EditText(this);
        et.setHint(hint);
        et.setText(value);
        et.setHintTextColor(MUTED);
        et.setTextColor(TEXT);
        et.setTextSize(15);
        et.setSingleLine(true);
        et.setPaddingRelative(dp(this, 12), dp(this, 12), dp(this, 12), dp(this, 12));
        et.setBackground(outlined(this, BG, 12, OUTLINE, 1));
        return et;
    }

    /** Name, address and user; Buddy's key to add on that computer (every command asks). */
    private void addSheet() {
        if (!Linux.installed(this)) {
            Toast.makeText(this, R.string.computers_need_linux, Toast.LENGTH_LONG).show();
            return;
        }
        LinearLayout c = column(this);
        c.addView(title(this, getString(R.string.computers_add), 18));
        android.widget.EditText name = field(getString(R.string.computers_name), getString(R.string.computers_name_default));
        android.widget.EditText host = field(getString(R.string.computers_host), "");
        android.widget.EditText user = field(getString(R.string.computers_user), "");
        android.widget.EditText port = field(getString(R.string.computers_port), "22");
        port.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
        c.addView(name, gap(12));
        c.addView(host, gap(8));
        c.addView(user, gap(8));
        c.addView(port, gap(8));
        c.addView(text(this, getString(R.string.computers_key_info), 13, MUTED), gap(14));
        TextView key = text(this, getString(R.string.computers_key_making), 12, TEXT);
        key.setTypeface(android.graphics.Typeface.MONOSPACE);
        key.setTextIsSelectable(true);
        key.setBackground(rounded(this, BG, 12));
        key.setPaddingRelative(dp(this, 12), dp(this, 10), dp(this, 12), dp(this, 10));
        c.addView(key, gap(6));
        Button copy = button(this, getString(R.string.computers_copy_key), false);
        copy.setEnabled(false);
        c.addView(copy, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(this, 40)));
        TextView status = text(this, "", 13, MUTED);
        c.addView(status, gap(8));
        Button save = button(this, getString(R.string.computers_check_add), true);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(this, 52));
        sp.topMargin = dp(this, 12);
        c.addView(save, sp);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(c);
        android.app.Dialog d = bottomSheet(this, protect(scroll));
        new Thread(() -> {
            String pub, err = null;
            try {
                pub = Computers.publicKey(this);
            } catch (Exception e) {
                pub = null;
                err = e.getMessage();
            }
            final String k = pub, e = err;
            runOnUiThread(() -> {
                key.setText(k != null ? k : getString(R.string.computers_key_failed, String.valueOf(e)));
                copy.setEnabled(k != null);
                copy.setOnClickListener(v -> {
                    getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("Buddy key", k));
                    Toast.makeText(this, R.string.computers_key_copied, Toast.LENGTH_SHORT).show();
                });
            });
        }, "computer-key").start();
        save.setOnClickListener(v -> {
            String n = name.getText().toString().trim(), h = host.getText().toString().trim(), u = user.getText().toString().trim();
            int pt;
            try {
                pt = Integer.parseInt(port.getText().toString().trim());
            } catch (NumberFormatException ex) {
                pt = 22;
            }
            if (n.isEmpty() || !Computers.validHost(h) || !Computers.validUser(u) || pt < 1 || pt > 65535) {
                status.setText(R.string.computers_fill_in);
                return;
            }
            Computers.Computer m = new Computers.Computer(Computers.newId(), n, h, u, pt, "each");
            save.setEnabled(false);
            status.setText(R.string.computers_checking);
            new Thread(() -> {
                String why = Computers.check(this, m);
                runOnUiThread(() -> {
                    save.setEnabled(true);
                    if (why != null) {
                        status.setText(getString(R.string.computers_check_failed, why)); // SSH's own words
                        return;
                    }
                    Computers.add(this, m);
                    Toast.makeText(this, getString(R.string.computers_added, n), Toast.LENGTH_SHORT).show();
                    d.dismiss();
                    render();
                });
            }, "computer-check").start();
        });
        d.show();
    }

    private LinearLayout.LayoutParams gap(int top) {
        LinearLayout.LayoutParams p = full();
        p.topMargin = dp(this, top);
        return p;
    }
}
