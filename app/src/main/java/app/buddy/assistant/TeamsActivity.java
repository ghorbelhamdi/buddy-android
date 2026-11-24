package app.buddy.assistant;

import android.app.Dialog;
import android.os.Bundle;
import android.text.InputType;
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
 * Settings → Teams (see Teams): the teams, and one team's name, how it works (fixed stages, or the chat's agent
 * leads) and its members, each with a role, an agent, a model and, in fixed stages, its stage. A chat picks its team
 * in chat options. Changes are kept as they are made.
 */
public class TeamsActivity extends Screen {
    static final String EXTRA_TEAM = "team";
    private LinearLayout body;
    private TextView titleView;
    /** The team being edited (its id), or null: the list. */
    private String editing;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        editing = getIntent().getStringExtra(EXTRA_TEAM);
        LinearLayout root = column(this);
        root.setBackgroundColor(BG);
        LinearLayout top = row(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPaddingRelative(dp(this, 4), dp(this, 4), dp(this, 16), dp(this, 4));
        ImageButton back = iconButton(this, R.drawable.ms_arrow_back, getString(R.string.perms_back));
        back.setLayoutParams(new LinearLayout.LayoutParams(dp(this, 44), dp(this, 44)));
        back.setOnClickListener(v -> finish());
        top.addView(back);
        titleView = title(this, getString(R.string.teams_title), 18);
        titleView.setPaddingRelative(dp(this, 6), 0, 0, 0);
        top.addView(titleView, weight1());
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
        setContentView(protect(frame)); // what agents may do as a team is the user's to set
    }

    @Override
    protected void onResume() {
        super.onResume();
        render();
    }

    private void render() {
        body.removeAllViews();
        Teams.Team t = editing == null ? null : Teams.find(this, editing);
        if (t == null) list();
        else team(t);
    }

    // ------------------------------------------------------------------ the list

    private void list() {
        titleView.setText(R.string.teams_title);
        body.addView(text(this, getString(R.string.teams_intro), 13, MUTED), full());
        for (Teams.Team t : Teams.all(this)) {
            LinearLayout r = card();
            LinearLayout tx = column(this);
            tx.addView(single(title(this, t.name, 15)));
            tx.addView(text(this, getString(Teams.STAGES.equals(t.flow) ? R.string.teams_flow_stages : R.string.teams_flow_lead)
                    + " · " + getResources().getQuantityString(R.plurals.teams_members_count, t.members.size(), t.members.size()), 13, MUTED));
            r.addView(tx, weight1());
            r.addView(icon(this, R.drawable.ms_chevron_right, MUTED, 22));
            r.setOnClickListener(v -> open(t.id));
            body.addView(r, gap(8));
        }
        Button add = button(this, getString(R.string.teams_new), true);
        add.setOnClickListener(v -> {
            Teams.Team t = new Teams.Team();
            t.id = Teams.newId();
            t.name = getString(R.string.teams_new_name);
            Teams.put(this, t);
            open(t.id);
        });
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(this, 44));
        ap.topMargin = dp(this, 14);
        body.addView(add, ap);
        body.addView(text(this, getString(R.string.teams_how), 13, MUTED), gap(16));
    }

    private void open(String id) {
        editing = id;
        render();
    }

    @Override
    public void onBackPressed() {
        if (editing != null && getIntent().getStringExtra(EXTRA_TEAM) == null) { // back to the list
            editing = null;
            render();
            return;
        }
        super.onBackPressed();
    }

    // ------------------------------------------------------------------ one team

    private void team(final Teams.Team t) {
        titleView.setText(t.name);
        // name
        body.addView(sectionLabel(this, getString(R.string.teams_name)), gap(4));
        EditText name = field(t.name, false);
        name.addTextChangedListener(new android.text.TextWatcher() { // kept as it's typed (Back leaves at once)
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(android.text.Editable e) {
                String n = e.toString().trim();
                if (n.isEmpty() || n.equals(t.name)) return;
                t.name = ChatHub.clip(n, 40);
                Teams.put(TeamsActivity.this, t);
                titleView.setText(t.name);
            }
        });
        body.addView(name, gap(6));
        // how it works
        body.addView(sectionLabel(this, getString(R.string.teams_flow)), gap(18));
        body.addView(choice(getString(R.string.teams_flow_stages), getString(R.string.teams_flow_stages_info),
                Teams.STAGES.equals(t.flow), () -> {
                    t.flow = Teams.STAGES;
                    save(t);
                }), gap(6));
        body.addView(choice(getString(R.string.teams_flow_lead), getString(R.string.teams_flow_lead_info),
                Teams.LEAD.equals(t.flow), () -> {
                    t.flow = Teams.LEAD;
                    save(t);
                }), gap(6));
        // members
        body.addView(sectionLabel(this, getString(R.string.teams_members)), gap(18));
        List<Teams.Member> ms = new ArrayList<>(t.members);
        if (Teams.STAGES.equals(t.flow)) ms.sort((a, b) -> Integer.compare(a.stage, b.stage));
        for (Teams.Member m : ms) {
            LinearLayout r = card();
            LinearLayout tx = column(this);
            tx.addView(single(title(this, m.name, 15)));
            boolean ready = SetupState.signedIn(this, m.agent);
            tx.addView(single(text(this, (Teams.STAGES.equals(t.flow) ? getString(R.string.teams_member_stage, m.stage) + " · " : "")
                    + Teams.describe(m) + (ready ? "" : " · " + getString(R.string.teams_not_signed)), 13, ready ? MUTED : ERR)));
            TextView role = text(this, m.role, 13, TEXT2);
            role.setMaxLines(2);
            role.setEllipsize(android.text.TextUtils.TruncateAt.END);
            tx.addView(role);
            r.addView(tx, weight1());
            r.addView(icon(this, R.drawable.ms_chevron_right, MUTED, 22));
            r.setOnClickListener(v -> memberSheet(t, m));
            body.addView(r, gap(8));
        }
        Button add = button(this, getString(R.string.teams_add_member), false);
        add.setOnClickListener(v -> {
            Teams.Member m = new Teams.Member(getString(R.string.teams_member_new), "", Prefs.backend(this), "",
                    t.members.isEmpty() ? 1 : t.members.get(t.members.size() - 1).stage + 1);
            t.members.add(m);
            save(t);
            memberSheet(t, m);
        });
        LinearLayout.LayoutParams ap = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(this, 44));
        ap.topMargin = dp(this, 12);
        body.addView(add, ap);
        body.addView(text(this, getString(R.string.teams_how), 13, MUTED), gap(16));
        Button del = button(this, getString(R.string.teams_delete), false);
        del.setTextColor(ERR);
        del.setOnClickListener(v -> confirmSheet(this, getString(R.string.teams_delete_title, t.name), getString(R.string.teams_delete_text),
                getString(R.string.teams_delete), true, () -> {
                    Teams.remove(this, t.id);
                    ChatHub hub = ChatHub.get(this);
                    for (Sessions.Session s : new ArrayList<>(hub.sessions.all)) if (t.id.equals(s.team)) hub.setTeam(s, "");
                    if (getIntent().getStringExtra(EXTRA_TEAM) != null) finish();
                    else open(null);
                }));
        LinearLayout.LayoutParams dp2 = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(this, 44));
        dp2.topMargin = dp(this, 20);
        body.addView(del, dp2);
    }

    private void save(Teams.Team t) {
        Teams.put(this, t);
        render();
    }

    /** One member: its name, role, agent, model and stage; or remove it. */
    private void memberSheet(final Teams.Team t, final Teams.Member m) {
        LinearLayout c = column(this);
        c.addView(title(this, m.name, 18));
        c.addView(sectionLabel(this, getString(R.string.teams_member_name)), gap(12));
        EditText name = field(m.name, false);
        c.addView(name, gap(6));
        c.addView(sectionLabel(this, getString(R.string.teams_member_role)), gap(14));
        EditText role = field(m.role, true);
        role.setHint(R.string.teams_member_role_hint);
        c.addView(role, gap(6));
        // agent and model: the signed-in agents, and the chosen one's models
        c.addView(sectionLabel(this, getString(R.string.teams_member_agent)), gap(14));
        final String[] agent = {m.agent}, model = {m.model};
        final LinearLayout agents = column(this), models = column(this);
        c.addView(agents, full());
        c.addView(sectionLabel(this, getString(R.string.teams_member_model)), gap(14));
        c.addView(models, full());
        final Runnable[] draw = new Runnable[1];
        draw[0] = () -> {
            agents.removeAllViews();
            for (Account acc : Account.ALL) {
                if (!SetupState.signedIn(this, acc.agent) && !acc.agent.equals(agent[0])) continue;
                agents.addView(pick(acc.name(), acc.agent.equals(agent[0]), () -> {
                    if (!acc.agent.equals(agent[0])) model[0] = ""; // (another agent: its own default)
                    agent[0] = acc.agent;
                    draw[0].run();
                }), full());
            }
            models.removeAllViews();
            for (String[] o : Models.options(agent[0], true)) {
                if (o[0] == null) {
                    TextView h = text(this, o[1], 12, MUTED);
                    h.setPaddingRelative(dp(this, 4), dp(this, 8), 0, dp(this, 2));
                    models.addView(h, full());
                    continue;
                }
                models.addView(pick(o[1], o[0].equals(model[0]), () -> {
                    model[0] = o[0];
                    draw[0].run();
                }), full());
            }
        };
        draw[0].run();
        // stage (fixed stages only)
        final int[] stage = {m.stage};
        if (Teams.STAGES.equals(t.flow)) {
            c.addView(sectionLabel(this, getString(R.string.teams_member_stage_label)), gap(14));
            LinearLayout st = row(this);
            st.setGravity(Gravity.CENTER_VERTICAL);
            Button minus = button(this, "−", false), plus = button(this, "+", false);
            TextView n = title(this, getString(R.string.teams_member_stage, stage[0]), 15);
            n.setGravity(Gravity.CENTER);
            minus.setOnClickListener(v -> {
                stage[0] = Math.max(1, stage[0] - 1);
                n.setText(getString(R.string.teams_member_stage, stage[0]));
            });
            plus.setOnClickListener(v -> {
                stage[0] = Math.min(9, stage[0] + 1);
                n.setText(getString(R.string.teams_member_stage, stage[0]));
            });
            st.addView(minus, new LinearLayout.LayoutParams(dp(this, 52), dp(this, 44)));
            st.addView(n, new LinearLayout.LayoutParams(dp(this, 120), LinearLayout.LayoutParams.WRAP_CONTENT));
            st.addView(plus, new LinearLayout.LayoutParams(dp(this, 52), dp(this, 44)));
            c.addView(st, gap(6));
            c.addView(text(this, getString(R.string.teams_member_stage_info), 12, MUTED), gap(4));
        }
        Button save = button(this, getString(R.string.teams_member_save), true);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(this, 48));
        sp.topMargin = dp(this, 18);
        c.addView(save, sp);
        Button remove = button(this, getString(R.string.teams_member_delete), false);
        remove.setTextColor(ERR);
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(this, 44));
        rp.topMargin = dp(this, 8);
        c.addView(remove, rp);
        ScrollView sv = new ScrollView(this);
        sv.addView(c);
        final Dialog d = bottomSheet(this, protect(sv));
        save.setOnClickListener(v -> {
            String n = name.getText().toString().trim();
            if (n.isEmpty()) {
                Toast.makeText(this, R.string.teams_member_no_name, Toast.LENGTH_SHORT).show();
                return;
            }
            for (Teams.Member o : t.members) if (o != m && o.name.equalsIgnoreCase(n)) {
                Toast.makeText(this, R.string.teams_member_taken, Toast.LENGTH_SHORT).show();
                return;
            }
            m.name = ChatHub.clip(n, 40);
            m.role = role.getText().toString().trim();
            m.agent = agent[0];
            m.model = model[0];
            m.stage = stage[0];
            d.dismiss();
            save(t);
        });
        remove.setOnClickListener(v -> {
            t.members.remove(m);
            d.dismiss();
            save(t);
        });
        d.show();
    }

    // ------------------------------------------------------------------ pieces

    private LinearLayout card() {
        LinearLayout r = row(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setBackground(outlined(this, CARD, 16, OUTLINE, 1));
        r.setPaddingRelative(dp(this, 16), dp(this, 12), dp(this, 10), dp(this, 12));
        r.setForeground(getDrawable(ripple(this, false)));
        return r;
    }

    private View choice(String name, String about, boolean on, Runnable pick) {
        LinearLayout r = card();
        r.addView(radio(this, on));
        LinearLayout tx = column(this);
        tx.setPaddingRelative(dp(this, 12), 0, 0, 0);
        tx.addView(title(this, name, 15));
        tx.addView(text(this, about, 13, MUTED));
        r.addView(tx, weight1());
        r.setOnClickListener(v -> pick.run());
        return r;
    }

    private View pick(String name, boolean on, Runnable tap) {
        LinearLayout r = row(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPaddingRelative(dp(this, 4), dp(this, 9), dp(this, 4), dp(this, 9));
        r.setBackgroundResource(ripple(this, false));
        r.addView(radio(this, on));
        TextView t = single(text(this, name, 15, TEXT));
        t.setPaddingRelative(dp(this, 12), 0, 0, 0);
        r.addView(t, weight1());
        r.setOnClickListener(v -> tap.run());
        return r;
    }

    private EditText field(String value, boolean multi) {
        EditText et = new EditText(this);
        et.setText(value);
        et.setHintTextColor(MUTED);
        et.setTextColor(TEXT);
        et.setTextSize(15);
        if (multi) {
            et.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
            et.setMinLines(3);
            et.setMaxLines(8);
            et.setGravity(Gravity.TOP | Gravity.START);
        } else et.setSingleLine(true);
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
