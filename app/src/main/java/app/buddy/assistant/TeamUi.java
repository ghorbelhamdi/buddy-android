package app.buddy.assistant;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

import static app.buddy.assistant.Ui.*;

/**
 * What a chat's team (see Team) looks like: the sheet that sets one up (how it works, where the members work, how they
 * share apps, then the members in that shape with their agents and models), the strip of members over the chat, the
 * boxes that say who a message goes to, and the frame around what a member said.
 */
final class TeamUi {
    private TeamUi() {
    }

    interface Done {
        /** The team as set up, and the leader's agent and model (the chat's own, changed there or not). */
        void done(Team team, String leaderAgent, String leaderModel);
    }

    // ------------------------------------------------------------------ setting one up

    static void setup(final Activity a, final Sessions.Session ses, final Done done) {
        final Team[] team = {Team.preset(a, Team.PIPE, ses.backend)};
        final String[] leader = {ses.backend, ses.model == null ? "" : ses.model};
        final int[] step = {0};
        final LinearLayout box = protect(column(a)); // only the user sets a team up
        ScrollView sv = new ScrollView(a);
        sv.addView(box);
        final Dialog d = bottomSheet(a, sv);
        final Runnable[] render = new Runnable[1];
        render[0] = () -> {
            box.removeAllViews();
            // where we are: a dot per step
            LinearLayout dots = row(a);
            dots.setGravity(Gravity.CENTER_VERTICAL);
            for (int i = 0; i < 4; i++) {
                View dot = new View(a);
                dot.setBackground(rounded(a, i <= step[0] ? ACCENT : OUTLINE, 3));
                LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(dp(a, i == step[0] ? 22 : 6), dp(a, 6));
                dp.setMarginEnd(dp(a, 6));
                dots.addView(dot, dp);
            }
            dots.addView(new View(a), weight1());
            dots.addView(monoText(a, a.getString(R.string.team_step, step[0] + 1, 4), 12, MUTED));
            box.addView(dots, full());
            final Team t = team[0];
            if (step[0] == 0) {
                head(a, box, a.getString(R.string.team_q_structure), a.getString(R.string.team_q_structure_info));
                String[][] o = {{Team.LEAD, a.getString(R.string.team_lead), a.getString(R.string.team_lead_info)},
                        {Team.PIPE, a.getString(R.string.team_pipe), a.getString(R.string.team_pipe_info)},
                        {Team.CHECK, a.getString(R.string.team_check), a.getString(R.string.team_check_info)},
                        {Team.DOERS, a.getString(R.string.team_doers), a.getString(R.string.team_doers_info)}};
                for (final String[] x : o) {
                    choice(a, box, x[1], x[2], x[0].equals(t.structure), () -> {
                        if (!x[0].equals(t.structure)) { // another shape: its own members to start from
                            Team n = Team.preset(a, x[0], leader[0]);
                            n.workspace = t.workspace;
                            n.apps = t.apps;
                            team[0] = n;
                        }
                        render[0].run();
                    });
                }
            } else if (step[0] == 1) {
                head(a, box, a.getString(R.string.team_q_workspace), a.getString(R.string.team_q_workspace_info));
                String[][] o = {{Team.SEP, a.getString(R.string.team_ws_sep), a.getString(R.string.team_ws_sep_info)},
                        {Team.SAME, a.getString(R.string.team_ws_same), a.getString(R.string.team_ws_same_info)},
                        {Team.LOOK, a.getString(R.string.team_ws_look), a.getString(R.string.team_ws_look_info)}};
                for (final String[] x : o) {
                    choice(a, box, x[1], x[2], x[0].equals(t.workspace), () -> {
                        t.workspace = x[0];
                        render[0].run();
                    });
                }
            } else if (step[0] == 2) {
                boolean screens = Background.setUp(a);
                head(a, box, a.getString(R.string.team_q_apps), a.getString(screens ? R.string.team_q_apps_info
                        : R.string.team_q_apps_no_background));
                String[][] o = {{Team.DIFF, a.getString(R.string.team_apps_diff), a.getString(R.string.team_apps_diff_info)},
                        {Team.TURN, a.getString(R.string.team_apps_turn), a.getString(R.string.team_apps_turn_info)},
                        {Team.NONE, a.getString(R.string.team_apps_none), a.getString(R.string.team_apps_none_info)}};
                if (!screens) t.apps = Team.NONE; // (said above: without background mode there is no screen to share)
                for (final String[] x : o) {
                    if (!screens && !Team.NONE.equals(x[0])) continue;
                    choice(a, box, x[1], x[2], x[0].equals(t.apps), () -> {
                        t.apps = x[0];
                        render[0].run();
                    });
                }
            } else {
                head(a, box, a.getString(Team.structureName(t.structure)), a.getString(R.string.team_q_members_info));
                diagram(a, box, t, leader, render[0]);
            }
            LinearLayout buttons = row(a);
            Button back = button(a, a.getString(step[0] == 0 ? R.string.team_cancel : R.string.chat_back), false);
            back.setOnClickListener(v -> {
                if (step[0] == 0) d.dismiss();
                else {
                    step[0]--;
                    render[0].run();
                }
            });
            Button next = button(a, a.getString(step[0] == 3 ? R.string.team_start : R.string.team_next), true);
            next.setEnabled(step[0] < 3 || !t.members.isEmpty());
            next.setAlpha(next.isEnabled() ? 1f : 0.5f);
            next.setOnClickListener(v -> {
                if (step[0] < 3) {
                    step[0]++;
                    render[0].run();
                    return;
                }
                d.dismiss();
                done.done(t, leader[0], leader[1]);
            });
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(0, dp(a, 48), 1);
            bp.setMarginEnd(dp(a, 8));
            buttons.addView(back, bp);
            buttons.addView(next, new LinearLayout.LayoutParams(0, dp(a, 48), 2));
            LinearLayout.LayoutParams bsp = full();
            bsp.topMargin = dp(a, 16);
            box.addView(buttons, bsp);
        };
        render[0].run();
        d.show();
    }

    private static void head(Context c, LinearLayout box, String title, String info) {
        TextView h = title(c, title, 20);
        h.setPaddingRelative(0, dp(c, 14), 0, 0);
        box.addView(h);
        TextView i = text(c, info, 14, TEXT2);
        i.setPaddingRelative(0, dp(c, 6), 0, dp(c, 4));
        box.addView(i);
    }

    private static void choice(Context c, LinearLayout box, String name, String info, boolean on, final Runnable tap) {
        LinearLayout r = row(c);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setBackground(outlined(c, on ? ACCENT_SOFT : CARD2, 16, on ? ACCENT : CARD2, 2));
        r.setPaddingRelative(dp(c, 12), dp(c, 12), dp(c, 12), dp(c, 12));
        r.setMinimumHeight(dp(c, 64));
        r.addView(radio(c, on));
        LinearLayout t = column(c);
        t.setPaddingRelative(dp(c, 12), 0, 0, 0);
        t.addView(title(c, name, 15));
        TextView i = text(c, info, 13, TEXT2);
        i.setPaddingRelative(0, dp(c, 2), 0, 0);
        t.addView(i);
        r.addView(t, weight1());
        r.setOnClickListener(v -> tap.run());
        LinearLayout.LayoutParams p = full();
        p.topMargin = dp(c, 8);
        box.addView(r, p);
    }

    /** The members in the structure's shape: the leader on top, then the others, each with its agent and model to pick. */
    private static void diagram(final Activity a, LinearLayout box, final Team t, final String[] leader, final Runnable render) {
        // the leader: this chat's own agent (changing it here changes the chat's)
        LinearLayout top = row(a);
        top.setGravity(Gravity.CENTER_HORIZONTAL);
        View lead = node(a, a.getString(R.string.team_leader), ACCENT, a.getString(R.string.team_this_chat), true,
                leader[0], leader[1], agent -> {
                    leader[0] = agent;
                    leader[1] = "";
                    render.run();
                }, model -> {
                    leader[1] = model;
                    render.run();
                }, null, null);
        top.addView(lead, new LinearLayout.LayoutParams(dp(a, 220), LinearLayout.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams tp = full();
        tp.topMargin = dp(a, 10);
        box.addView(top, tp);
        boolean pipe = Team.PIPE.equals(t.structure), check = Team.CHECK.equals(t.structure);
        box.addView(arrow(a, pipe || check ? "↓" : "↙  ↓  ↘"), full());
        List<View> cards = new ArrayList<>();
        for (final Team.Member m : t.members) {
            cards.add(node(a, m.name, Team.color(a, m.color), null, false, m.agent, m.model, agent -> {
                m.agent = agent;
                m.model = "";
                render.run();
            }, model -> {
                m.model = model;
                render.run();
            }, () -> rename(a, m, t, render), () -> {
                t.members.remove(m);
                render.run();
            }));
        }
        // one more: a dashed card that adds a member on the chat's own agent
        LinearLayout add = column(a);
        add.setGravity(Gravity.CENTER);
        add.setBackground(dashed(a));
        add.setMinimumHeight(dp(a, pipe ? 56 : 120));
        add.addView(icon(a, R.drawable.ms_add, TEXT2, 22));
        TextView at = text(a, a.getString(R.string.team_add), 13, TEXT2);
        at.setPaddingRelative(0, dp(a, 4), 0, 0);
        add.addView(at);
        add.setOnClickListener(v -> {
            t.add(Team.nextName(a, t), leader[0], "", "");
            render.run();
        });
        cards.add(add);
        if (pipe) { // one after another, top to bottom
            for (int i = 0; i < cards.size(); i++) {
                if (i > 0) box.addView(arrow(a, "↓"), full());
                box.addView(cards.get(i), full());
            }
            return;
        }
        for (int i = 0; i < cards.size(); i += 2) { // side by side, two across
            LinearLayout line = row(a);
            LinearLayout.LayoutParams first = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1);
            line.addView(cards.get(i), first);
            if (check && i + 1 < cards.size() && i + 1 < t.members.size()) { // a doer and its checker: back and forth
                TextView both = text(a, "⇄", 18, ACCENT_TEXT);
                both.setGravity(Gravity.CENTER);
                line.addView(both, new LinearLayout.LayoutParams(dp(a, 24), LinearLayout.LayoutParams.MATCH_PARENT));
            } else {
                line.addView(new View(a), new LinearLayout.LayoutParams(dp(a, 8), 1));
            }
            if (i + 1 < cards.size()) line.addView(cards.get(i + 1), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1));
            else line.addView(new View(a), new LinearLayout.LayoutParams(0, 1, 1));
            LinearLayout.LayoutParams lp = full();
            if (i > 0) lp.topMargin = dp(a, 8);
            box.addView(line, lp);
        }
    }

    private static View arrow(Context c, String s) {
        TextView t = text(c, s, 16, ACCENT_TEXT);
        t.setGravity(Gravity.CENTER);
        t.setPaddingRelative(0, dp(c, 4), 0, dp(c, 4));
        return t;
    }

    private static android.graphics.drawable.Drawable dashed(Context c) {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setCornerRadius(dp(c, 16));
        g.setColor(0x00000000);
        g.setStroke(dp(c, 2), MUTED, dp(c, 6), dp(c, 4));
        return g;
    }

    private interface Pick {
        void picked(String id);
    }

    /** A member's card: its name (a tap renames it, a long press or the × removes it), its agent and its model. */
    private static View node(final Activity a, String name, int color, String sub, boolean leader, final String agent, final String model,
                             final Pick onAgent, final Pick onModel, final Runnable rename, final Runnable remove) {
        LinearLayout card = column(a);
        card.setBackground(outlined(a, CARD2, 16, leader ? ACCENT : CARD2, 2));
        card.setPaddingRelative(dp(a, 10), dp(a, 8), dp(a, 10), dp(a, 10));
        LinearLayout nm = row(a);
        nm.setGravity(Gravity.CENTER_VERTICAL);
        nm.setMinimumHeight(dp(a, 32));
        nm.addView(dot(a, color, 10));
        TextView n = single(text(a, name, 14, TEXT));
        n.setTypeface(sans(a, 600));
        n.setPaddingRelative(dp(a, 6), 0, dp(a, 6), 0);
        nm.addView(n, weight1());
        if (sub != null) nm.addView(text(a, sub, 11, MUTED));
        if (rename != null) {
            n.setOnClickListener(v -> rename.run());
            n.setContentDescription(a.getString(R.string.chat_menu_rename) + ": " + name);
        }
        if (remove != null) {
            ImageButton x = iconButton(a, R.drawable.ms_close, a.getString(R.string.team_remove, name), MUTED);
            x.setOnClickListener(v -> remove.run());
            nm.addView(x, new LinearLayout.LayoutParams(dp(a, 32), dp(a, 32)));
            card.setOnLongClickListener(v -> {
                remove.run();
                return true;
            });
        }
        card.addView(nm, full());
        card.addView(text(a, a.getString(R.string.team_agent), 11, MUTED));
        final List<String[]> agents = new ArrayList<>();
        for (Account acc : Account.ALL) if (acc.agent.equals(agent) || SetupState.signedIn(a, acc.agent)) agents.add(new String[]{acc.agent, acc.name()});
        card.addView(picker(a, Account.nameOf(agent), agents, onAgent), full());
        TextView ml = text(a, a.getString(R.string.chat_model), 11, MUTED);
        ml.setPaddingRelative(0, dp(a, 6), 0, 0);
        card.addView(ml);
        card.addView(picker(a, Models.label(agent, model), Models.options(agent, true), onModel), full());
        return card;
    }

    /** A dropdown: what is picked, and a menu of the rest ({id, name}; a row without an id is a heading). */
    private static View picker(final Activity a, String shown, final List<String[]> options, final Pick pick) {
        final LinearLayout p = row(a);
        p.setGravity(Gravity.CENTER_VERTICAL);
        p.setBackground(outlined(a, CARD, 10, OUTLINE, 1));
        p.setPaddingRelative(dp(a, 10), 0, dp(a, 4), 0);
        p.setMinimumHeight(dp(a, 40));
        p.addView(single(text(a, shown, 13, TEXT)), weight1());
        p.addView(icon(a, R.drawable.ms_expand_more, MUTED, 20));
        p.setOnClickListener(v -> {
            android.widget.PopupMenu menu = new android.widget.PopupMenu(a, p);
            for (int i = 0; i < options.size(); i++) {
                String[] o = options.get(i);
                android.view.MenuItem item = menu.getMenu().add(0, i, i, o.length > 1 ? o[1] : o[0]);
                if (o[0] == null) item.setEnabled(false);
            }
            menu.setOnMenuItemClickListener(item -> {
                String[] o = options.get(item.getItemId());
                if (o[0] != null) pick.picked(o[0]);
                return true;
            });
            menu.show();
        });
        return p;
    }

    private static void rename(final Activity a, final Team.Member m, final Team t, final Runnable render) {
        LinearLayout c = column(a);
        c.addView(title(a, a.getString(R.string.team_name), 18));
        final EditText field = new EditText(a);
        field.setText(m.name);
        field.setSelection(field.length());
        field.setTextColor(TEXT);
        field.setTextSize(15);
        field.setSingleLine(true);
        field.setFilters(new android.text.InputFilter[]{new android.text.InputFilter.LengthFilter(40)});
        LinearLayout.LayoutParams fp = full();
        fp.topMargin = dp(a, 8);
        c.addView(field, fp);
        Button save = button(a, a.getString(R.string.team_save), true);
        LinearLayout.LayoutParams sp = full();
        sp.topMargin = dp(a, 12);
        c.addView(save, sp);
        final Dialog d = bottomSheet(a, c);
        save.setOnClickListener(v -> {
            String name = field.getText().toString().trim();
            Team.Member other = t.member(name);
            if (name.isEmpty() || (other != null && other != m)) return; // (two of one name: the leader couldn't tell them apart)
            m.name = name;
            d.dismiss();
            render.run();
        });
        d.show();
    }

    // ------------------------------------------------------------------ in the chat

    /** One of the strip's members: its colour, name and state. */
    static View chip(Context c, String name, int color, String state, boolean leader, boolean working) {
        LinearLayout r = row(c);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setBackground(outlined(c, CARD, 12, leader ? Theme.withAlpha(ACCENT, 0x80) : LINE, 1));
        r.setPaddingRelative(dp(c, 8), dp(c, 6), dp(c, 10), dp(c, 6));
        r.addView(dot(c, color, 8));
        TextView n = single(text(c, name, 12, TEXT));
        n.setTypeface(sans(c, 600));
        n.setPaddingRelative(dp(c, 6), 0, dp(c, 6), 0);
        r.addView(n);
        r.addView(single(monoText(c, state, 11, working ? ACCENT_TEXT : MUTED)));
        return r;
    }

    static View between(Context c, String s) {
        TextView t = text(c, s, 13, MUTED);
        t.setPaddingRelative(dp(c, 6), 0, dp(c, 6), 0);
        return t;
    }

    /** A box that says a message goes to this one: ticked or not. */
    static View sendTo(Context c, String name, int color, boolean on, final Runnable toggle) {
        LinearLayout r = row(c);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setBackground(outlined(c, on ? CARD2 : 0x00000000, 18, on ? OUTLINE : LINE, 1));
        r.setPaddingRelative(dp(c, 8), 0, dp(c, 12), 0);
        r.setMinimumHeight(dp(c, 36));
        FrameLayout box = new FrameLayout(c);
        box.setBackground(outlined(c, on ? color : 0x00000000, 5, color, 2));
        if (on) box.addView(icon(c, R.drawable.ms_check, BG, 14), new FrameLayout.LayoutParams(dp(c, 14), dp(c, 14), Gravity.CENTER));
        r.addView(box, new LinearLayout.LayoutParams(dp(c, 18), dp(c, 18)));
        TextView n = single(text(c, name, 13, TEXT));
        n.setPaddingRelative(dp(c, 6), 0, 0, 0);
        r.addView(n);
        r.setOnClickListener(v -> toggle.run());
        r.setContentDescription(name);
        r.setSelected(on);
        return r;
    }

    /**
     * The frame around what the leader or a member said in a team's chat: its name in its colour, its agent, and the
     * message it answers. What it said goes in after these.
     */
    static LinearLayout frame(Context c, String name, String on, int color, String quoteFrom, String quote) {
        LinearLayout f = column(c);
        f.setBackground(corners(c, Theme.withAlpha(color, 0x24), 18, 18, 18, 6));
        f.setPaddingRelative(dp(c, 14), dp(c, 10), dp(c, 14), dp(c, 12));
        LinearLayout h = row(c);
        h.setGravity(Gravity.CENTER_VERTICAL);
        h.addView(dot(c, color, 8));
        TextView n = single(text(c, name, 13, color));
        n.setTypeface(sans(c, 600));
        n.setPaddingRelative(dp(c, 6), 0, dp(c, 6), 0);
        h.addView(n);
        h.addView(single(text(c, on, 11, MUTED)), weight1());
        f.addView(h, full());
        if (quote != null && !quote.isEmpty()) {
            TextView q = text(c, "", 12, TEXT2);
            android.text.SpannableString s = new android.text.SpannableString(quoteFrom + ": " + quote);
            s.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, quoteFrom.length() + 1, 0);
            q.setText(s);
            q.setMaxLines(3);
            q.setEllipsize(android.text.TextUtils.TruncateAt.END);
            q.setBackground(rounded(c, Theme.withAlpha(BG, 0x80), 10));
            q.setPaddingRelative(dp(c, 10), dp(c, 6), dp(c, 10), dp(c, 6));
            LinearLayout.LayoutParams qp = full();
            qp.topMargin = dp(c, 6);
            f.addView(q, qp);
        }
        return f;
    }

    static HorizontalScrollView across(Context c, View content) {
        HorizontalScrollView s = new HorizontalScrollView(c);
        s.setHorizontalScrollBarEnabled(false);
        s.setOverScrollMode(View.OVER_SCROLL_NEVER);
        s.addView(content);
        return s;
    }
}
