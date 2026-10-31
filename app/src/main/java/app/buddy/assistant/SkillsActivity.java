package app.buddy.assistant;

import android.app.Dialog;
import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.List;
import static app.buddy.assistant.Ui.*;

/**
 * Settings → Skills: the skills Claude Code, Codex and OpenCode share (see Skills). Import one (.zip, .skill or
 * SKILL.md, as Claude and ChatGPT export them), write one, look at one, or delete it.
 */
public class SkillsActivity extends Screen {
    private static final int PICK = 41;
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
        TextView t = title(this, getString(R.string.skills_title), 18);
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
        setContentView(protect(frame)); // skills are instructions for the agents: only the user changes them
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
    }

    private void render() {
        body.removeAllViews();
        body.addView(text(this, getString(R.string.skills_intro), 13, MUTED), full());
        if (!Linux.installed(this)) {
            body.addView(text(this, getString(R.string.skills_need_linux), 15, TEXT), gap(18));
            return;
        }
        List<Skills.Skill> all = Skills.all(this);
        if (all.isEmpty()) body.addView(text(this, getString(R.string.skills_none), 15, TEXT), gap(18));
        for (int i = 0; i < all.size(); i++) body.addView(skillRow(all.get(i)), gap(i == 0 ? 14 : 4));
        LinearLayout btns = row(this);
        Button imp = button(this, getString(R.string.skills_import), all.isEmpty());
        imp.setOnClickListener(v -> startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), PICK));
        Button write = button(this, getString(R.string.skills_write), false);
        write.setOnClickListener(v -> writeSheet());
        btns.addView(imp, new LinearLayout.LayoutParams(0, dp(this, 48), 1));
        LinearLayout.LayoutParams wp = new LinearLayout.LayoutParams(0, dp(this, 48), 1);
        wp.setMarginStart(dp(this, 8));
        btns.addView(write, wp);
        body.addView(btns, gap(14));
        body.addView(text(this, getString(R.string.skills_how), 13, MUTED), gap(12));
    }

    private LinearLayout skillRow(Skills.Skill s) {
        LinearLayout r = row(this);
        r.setPaddingRelative(dp(this, 14), dp(this, 12), dp(this, 10), dp(this, 12));
        r.setBackground(outlined(this, CARD, 16, OUTLINE, 1));
        LinearLayout t = column(this);
        t.addView(single(monoText(this, "/" + s.name, 14, TEXT)));
        if (!s.description.isEmpty()) {
            TextView d = text(this, s.description, 12, MUTED);
            d.setMaxLines(3);
            d.setEllipsize(android.text.TextUtils.TruncateAt.END);
            t.addView(d);
        }
        r.addView(t, weight1());
        r.addView(icon(this, R.drawable.ms_chevron_right, MUTED, 22));
        r.setOnClickListener(v -> showSkill(s));
        return r;
    }

    /** Its instructions, and Delete. */
    private void showSkill(Skills.Skill s) {
        LinearLayout c = column(this);
        c.addView(single(monoText(this, "/" + s.name, 18, TEXT)));
        if (!s.description.isEmpty()) c.addView(text(this, s.description, 13, MUTED), gap(4));
        String bodyText = Skills.frontMatter(Skills.read(new java.io.File(s.dir, "SKILL.md")))[2];
        if (bodyText.length() > 4000) bodyText = bodyText.substring(0, 4000) + "…";
        TextView b = monoText(this, bodyText, 12, TEXT);
        b.setBackground(rounded(this, BG, 12));
        b.setPaddingRelative(dp(this, 12), dp(this, 10), dp(this, 12), dp(this, 10));
        b.setTextIsSelectable(true);
        c.addView(b, gap(12));
        Button del = button(this, getString(R.string.skills_delete), false);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(this, 48));
        lp.topMargin = dp(this, 14);
        c.addView(del, lp);
        Dialog d = bottomSheet(this, protect(c));
        del.setOnClickListener(v -> confirmSheet(this, getString(R.string.skills_delete_title, s.name),
                getString(R.string.skills_delete_text), getString(R.string.skills_delete), true, () -> {
                    Skills.delete(s);
                    d.dismiss();
                    render();
                }));
        d.show();
    }

    /** A new skill: its name, when to use it, and the instructions. */
    private void writeSheet() {
        LinearLayout c = column(this);
        c.addView(title(this, getString(R.string.skills_write), 18));
        c.addView(text(this, getString(R.string.skills_write_info), 13, MUTED), gap(4));
        EditText name = field(getString(R.string.skills_name_hint), true);
        EditText desc = field(getString(R.string.skills_desc_hint), false);
        EditText instr = field(getString(R.string.skills_instructions_hint), false);
        instr.setMinLines(6);
        instr.setGravity(Gravity.TOP | Gravity.START);
        c.addView(name, gap(12));
        c.addView(desc, gap(8));
        c.addView(instr, gap(8));
        Button save = button(this, getString(R.string.api_keys_save), true);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(this, 52));
        sp.topMargin = dp(this, 14);
        c.addView(save, sp);
        Dialog d = bottomSheet(this, protect(c));
        save.setOnClickListener(v -> {
            if (instr.getText().toString().trim().isEmpty() || desc.getText().toString().trim().isEmpty()) {
                Toast.makeText(this, R.string.skills_need_all, Toast.LENGTH_SHORT).show();
                return;
            }
            try {
                String n = Skills.create(this, name.getText().toString(), desc.getText().toString(), instr.getText().toString());
                Toast.makeText(this, getString(R.string.skills_added, n), Toast.LENGTH_LONG).show();
                d.dismiss();
                render();
            } catch (Exception e) {
                Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show();
            }
        });
        d.show();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != PICK || res != RESULT_OK || data == null || data.getData() == null) return;
        android.net.Uri uri = data.getData();
        new Thread(() -> {
            String msg;
            try {
                msg = getString(R.string.skills_added, Skills.importUri(this, uri));
            } catch (Exception e) {
                msg = e.getMessage();
            }
            final String m = msg;
            runOnUiThread(() -> {
                Toast.makeText(this, m, Toast.LENGTH_LONG).show();
                render();
            });
        }, "skill-import").start();
    }

    private EditText field(String hint, boolean single) {
        EditText et = new EditText(this);
        et.setHint(hint);
        et.setHintTextColor(MUTED);
        et.setTextColor(TEXT);
        et.setTextSize(14);
        et.setSingleLine(single);
        if (!single) et.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
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
