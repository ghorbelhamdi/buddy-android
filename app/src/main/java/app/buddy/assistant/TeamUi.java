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
import android.widget.ImageView;
import android.widget.ImageButton;
import android.widget.LinearLayout;
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

    /**
     * from: a team to start from (the chat's last one), or null for a new one; with one the sheet opens on its
     * members, the questions a step back.
     */
    static void setup(final Activity a, final Sessions.Session ses, Team from, final Done done) {
        setup(a, ses, from, new boolean[3], false, from != null, done);
    }

    /**
     * known: the structure, the workspace and the apps the user already said (a team asked for in the chat, see
     * Team.Draft): those questions aren't asked again. keepMembers: the members are the user's (named in the chat), so
     * another structure picked here keeps them. atEnd: opens on the members.
     */
    static void setup(final Activity a, final Sessions.Session ses, Team from, boolean[] known, final boolean keepMembers,
                      boolean atEnd, final Done done) {
        final Team[] team = {from != null ? from.copy() : Team.preset(a, Team.PIPE, ses.backend)};
        final String[] leader = {ses.backend, ses.model == null ? "" : ses.model};
        final LinearLayout box = protect(column(a)); // only the user sets a team up
        final Dialog d = bottomSheet(a, box); // (which scrolls what is longer than the screen)
        final Runnable[] render = new Runnable[1];
        // everything on one sheet: the shape, the members in it with their agents, where they work and the apps;
        // what the user already said in the chat is filled in and marked so
        render[0] = () -> {
            box.removeAllViews();
            final Team t = team[0];
            box.addView(title(a, a.getString(R.string.team_setup_title), 20));
            section(a, box, a.getString(R.string.team_sec_shape), known[0]);
            LinearLayout tiles = row(a);
            String[][] shapes = {{Team.LEAD, a.getString(R.string.team_lead)}, {Team.PIPE, a.getString(R.string.team_pipe)},
                    {Team.CHECK, a.getString(R.string.team_check)}, {Team.DOERS, a.getString(R.string.team_doers)}};
            for (int i = 0; i < shapes.length; i++) {
                final String[] x = shapes[i];
                boolean on = x[0].equals(t.structure);
                LinearLayout tile = column(a);
                tile.setGravity(Gravity.CENTER);
                tile.setBackground(outlined(a, on ? ACCENT_SOFT : CARD2, 14, on ? ACCENT : CARD2, 2));
                tile.setPaddingRelative(dp(a, 4), dp(a, 8), dp(a, 4), dp(a, 8));
                View pic = new Shape(a, x[0]);
                tile.addView(pic, new LinearLayout.LayoutParams(dp(a, 40), dp(a, 32)));
                TextView n = text(a, x[1], 11, on ? TEXT : TEXT2);
                n.setGravity(Gravity.CENTER);
                n.setMaxLines(3); // ("Doers with their own goals" lost its last word on two)
                if (on) n.setTypeface(sans(a, 600));
                n.setPaddingRelative(0, dp(a, 4), 0, 0);
                tile.addView(n, full());
                tile.setSelected(on);
                tile.setContentDescription(x[1]);
                tile.setOnClickListener(v -> {
                    if (keepMembers) { // (the members the user named stay; a hand-off that was only the old shape's own follows the new one)
                        if (t.handoff.isEmpty() || t.handoff.equals(Team.handoffs(t.structure)[0])) t.handoff = Team.handoffs(x[0])[0];
                        t.structure = x[0];
                    }
                    else if (!x[0].equals(t.structure)) { // another shape: its own members to start from
                        Team n2 = Team.preset(a, x[0], leader[0]);
                        n2.workspace = t.workspace;
                        n2.apps = t.apps;
                        // (rules and a hand-off the user said stay; the hand-off a shape starts with is that shape's own)
                        n2.rules = t.rules;
                        if (!t.handoff.isEmpty() && !t.handoff.equals(Team.handoffs(t.structure)[0])) n2.handoff = t.handoff;
                        team[0] = n2;
                    }
                    render[0].run();
                });
                LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1);
                if (i > 0) tp.setMarginStart(dp(a, 6));
                tiles.addView(tile, tp);
            }
            box.addView(tiles, full());
            TextView how = text(a, a.getString(Team.LEAD.equals(t.structure) ? R.string.team_lead_info : Team.CHECK.equals(t.structure)
                    ? R.string.team_check_info : Team.DOERS.equals(t.structure) ? R.string.team_doers_info : R.string.team_pipe_info), 13, MUTED);
            how.setPaddingRelative(dp(a, 2), dp(a, 6), 0, 0);
            box.addView(how);
            section(a, box, a.getString(R.string.team_sec_members), keepMembers);
            TextView mi = text(a, a.getString(R.string.team_q_members_info), 13, MUTED);
            mi.setPaddingRelative(dp(a, 2), 0, 0, 0);
            box.addView(mi);
            diagram(a, box, t, leader, render[0]);
            section(a, box, a.getString(R.string.team_q_workspace), known[1]);
            segmented(a, box, new String[][]{{Team.SEP, a.getString(R.string.team_ws_sep), a.getString(R.string.team_ws_sep_info)},
                    {Team.SAME, a.getString(R.string.team_ws_same), a.getString(R.string.team_ws_same_info)},
                    {Team.LOOK, a.getString(R.string.team_ws_look), a.getString(R.string.team_ws_look_info)}}, t.workspace, v -> {
                t.workspace = v;
                render[0].run();
            });
            boolean screens = Background.setUp(a);
            section(a, box, a.getString(R.string.team_q_apps), known[2]);
            if (!screens) { // (without background mode there is no screen to share)
                t.apps = Team.NONE;
                TextView no = text(a, a.getString(R.string.team_q_apps_no_background), 13, MUTED);
                no.setPaddingRelative(dp(a, 2), 0, 0, 0);
                box.addView(no);
            } else {
                segmented(a, box, new String[][]{{Team.DIFF, a.getString(R.string.team_apps_diff), a.getString(R.string.team_apps_diff_info)},
                        {Team.TURN, a.getString(R.string.team_apps_turn), a.getString(R.string.team_apps_turn_info)},
                        {Team.NONE, a.getString(R.string.team_apps_none), a.getString(R.string.team_apps_none_info)}}, t.apps, v -> {
                    t.apps = v;
                    render[0].run();
                });
            }
            TextView then = text(a, a.getString(R.string.team_then_asks), 13, TEXT2);
            then.setPaddingRelative(dp(a, 2), dp(a, 16), 0, 0);
            box.addView(then);
            LinearLayout buttons = row(a);
            Button back = button(a, a.getString(R.string.team_cancel), false);
            back.setOnClickListener(v -> d.dismiss());
            Button go = button(a, a.getString(R.string.team_start), true);
            go.setEnabled(!t.members.isEmpty());
            go.setAlpha(go.isEnabled() ? 1f : 0.5f);
            go.setOnClickListener(v -> {
                d.dismiss();
                for (Team.Member m : t.members) m.helper = false; // (the user's team now: none is the leader's to drop unasked)
                done.done(t, leader[0], leader[1]);
            });
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(0, dp(a, 48), 1);
            bp.setMarginEnd(dp(a, 8));
            buttons.addView(back, bp);
            buttons.addView(go, new LinearLayout.LayoutParams(0, dp(a, 48), 2));
            LinearLayout.LayoutParams bsp = full();
            bsp.topMargin = dp(a, 16);
            box.addView(buttons, bsp);
        };
        render[0].run();
        d.show();
    }

    /** A section's name on the setup sheet; from: the user said it in the chat already (marked so). */
    private static void section(Context c, LinearLayout box, String name, boolean from) {
        LinearLayout r = row(c);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPaddingRelative(dp(c, 2), dp(c, 18), 0, dp(c, 8));
        TextView n = text(c, name, 14, TEXT);
        n.setTypeface(sans(c, 600));
        r.addView(n, weight1());
        if (from) r.addView(single(text(c, c.getString(R.string.team_from_message), 12, ACCENT_TEXT)));
        box.addView(r, full());
    }

    interface Pick1 {
        void pick(String value);
    }

    /** Three choices side by side ({value, name, what it means}), the one on filled; what it means under them. */
    private static void segmented(Context c, LinearLayout box, String[][] o, String on, final Pick1 pick) {
        LinearLayout r = row(c);
        r.setBackground(rounded(c, CARD2, 22));
        r.setPadding(dp(c, 4), dp(c, 4), dp(c, 4), dp(c, 4));
        String info = "";
        for (final String[] x : o) {
            boolean sel = x[0].equals(on);
            if (sel) info = x[2];
            TextView b = text(c, x[1], 12, sel ? ON_ACCENT : TEXT2);
            b.setGravity(Gravity.CENTER);
            b.setMaxLines(2);
            b.setMinHeight(dp(c, 44));
            b.setPaddingRelative(dp(c, 6), dp(c, 4), dp(c, 6), dp(c, 4));
            if (sel) {
                b.setBackground(rounded(c, ACCENT, 18));
                b.setTypeface(sans(c, 600));
            } else b.setBackgroundResource(ripple(c, false));
            b.setSelected(sel);
            b.setOnClickListener(v -> pick.pick(x[0]));
            r.addView(b, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1));
        }
        box.addView(r, full());
        if (!info.isEmpty()) {
            TextView i = text(c, info, 13, MUTED);
            i.setPaddingRelative(dp(c, 2), dp(c, 6), 0, 0);
            box.addView(i);
        }
    }

    /** The members in the structure's shape: the leader on top, then the others, each with its agent and model to pick. */
    private static void diagram(final Activity a, LinearLayout box, final Team t, final String[] leader, final Runnable render) {
        // the leader: this chat's own agent (changing it here changes the chat's)
        View lead = node(a, a.getString(R.string.team_leader), Team.leaderColor(a), a.getString(R.string.team_this_chat), true,
                leader[0], leader[1], agent -> {
                    leader[0] = agent;
                    leader[1] = "";
                    render.run();
                }, model -> {
                    leader[1] = model;
                    render.run();
                }, null, null);
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
        add.setMinimumHeight(dp(a, Team.PIPE.equals(t.structure) ? 56 : 120));
        add.addView(icon(a, R.drawable.ms_add, TEXT2, 22));
        TextView at = text(a, a.getString(R.string.team_add), 13, TEXT2);
        at.setPaddingRelative(dp(a, 8), dp(a, 4), dp(a, 8), 0);
        at.setGravity(Gravity.CENTER); // (a column's child is as wide as the column: its text centred in it)
        add.addView(at, full());
        add.setOnClickListener(v -> {
            t.add(Team.nextName(a, t), leader[0], "", "");
            render.run();
        });
        cards.add(add);
        place(a, box, t.structure, lead, cards, t.members.size());
    }

    /**
     * A team's shape, for the setup and for the chat's view of it alike: the leader on top; a pipeline's members one
     * under the other; a doer beside its checker; workers and doers side by side, two across. members: how many of
     * the cards are members (a card after them, the setup's "Add a member", takes the next place).
     */
    private static void place(Context c, LinearLayout box, String structure, View lead, List<View> cards, int members) {
        LinearLayout top = row(c);
        top.setGravity(Gravity.CENTER_HORIZONTAL);
        top.addView(lead, new LinearLayout.LayoutParams(dp(c, 220), LinearLayout.LayoutParams.WRAP_CONTENT));
        LinearLayout.LayoutParams tp = full();
        tp.topMargin = dp(c, 10);
        box.addView(top, tp);
        boolean pipe = Team.PIPE.equals(structure), check = Team.CHECK.equals(structure);
        if (cards.isEmpty()) return;
        // down to the one under it; or, to members side by side, a line that parts to both columns
        if (pipe || check || cards.size() == 1) box.addView(arrow(c, "↓"), full());
        else box.addView(new Fan(c), new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(c, 22)));
        if (pipe) { // one after another, top to bottom
            for (int i = 0; i < cards.size(); i++) {
                if (i > 0) box.addView(arrow(c, "↓"), full());
                box.addView(cards.get(i), full());
            }
            return;
        }
        for (int i = 0; i < cards.size(); i += 2) { // side by side, two across
            LinearLayout line = row(c);
            line.addView(cards.get(i), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1));
            if (check && i + 1 < members) { // a doer and its checker: back and forth
                TextView both = text(c, "⇄", 18, ACCENT_TEXT);
                both.setGravity(Gravity.CENTER);
                line.addView(both, new LinearLayout.LayoutParams(dp(c, 24), LinearLayout.LayoutParams.MATCH_PARENT));
            } else {
                // (every child fills the line's height: with one that doesn't, a line of cards that do is only as
                // tall as that one, and the cards were cut off at 1 px)
                line.addView(new View(c), new LinearLayout.LayoutParams(dp(c, 8), LinearLayout.LayoutParams.MATCH_PARENT));
            }
            if (i + 1 < cards.size()) line.addView(cards.get(i + 1), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1));
            else line.addView(new View(c), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1));
            LinearLayout.LayoutParams lp = full();
            if (i > 0) lp.topMargin = dp(c, 8);
            box.addView(line, lp);
        }
    }

    // ------------------------------------------------------------------ the team as it is, in its shape

    /** The sheet that shows a chat's team in its shape; refresh draws it again as the members' states change. */
    static final class Shown {
        Dialog dialog;
        Runnable refresh;

        boolean showing() {
            return dialog != null && dialog.isShowing();
        }
    }

    /** Members' last pictures of their hidden screens, shown at once when the panel is drawn again. */
    private static final java.util.Map<String, android.graphics.Bitmap> PICTURES = new java.util.concurrent.ConcurrentHashMap<>();
    /** Where each member's picture shows now (the panel is drawn anew), when its last one was asked for, and which load. */
    private static final java.util.Map<String, ImageView> PICTURE_VIEWS = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Map<String, Long> PICTURE_AT = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.Set<String> LOADING = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /**
     * The team panel (the team bar opens it): the team drawn in its shape, each one with its agent and model, what
     * it is for, what it does now and its last answer; a member with an app on its hidden screen shows that screen
     * live, with Show on my screen. A card opens that one's own chat (the leader's: back to the team's chat). Then
     * where they work, how they share apps, the hand-off and the rules, and Turn off. To look at: the team is changed
     * by telling the leader. here: the chat this was opened from (the team's or a member's own).
     */
    static Shown view(final Activity a, final ChatHub hub, final Sessions.Session ses, final Sessions.Session here,
                      final Runnable off) {
        final Shown shown = new Shown();
        final LinearLayout box = column(a);
        shown.dialog = bottomSheet(a, box);
        final android.os.Handler tick = new android.os.Handler(android.os.Looper.getMainLooper());
        final Runnable[] again = new Runnable[1];
        shown.refresh = () -> {
            final Team t = ses.team;
            if (t == null) {
                shown.dialog.dismiss();
                return;
            }
            box.removeAllViews();
            LinearLayout head = row(a);
            head.setGravity(Gravity.CENTER_VERTICAL);
            head.addView(shape(a, t.structure, 40, 32));
            TextView name = title(a, a.getString(Team.structureName(t.structure)), 20);
            name.setPaddingRelative(dp(a, 12), 0, 0, 0);
            head.addView(name, weight1());
            box.addView(head, full());
            StringBuilder how = new StringBuilder(a.getString(Team.SEP.equals(t.workspace) ? R.string.team_ws_sep
                    : Team.LOOK.equals(t.workspace) ? R.string.team_ws_look : R.string.team_ws_same))
                    .append(" · ").append(a.getString(Team.TURN.equals(t.apps) ? R.string.team_apps_turn
                            : Team.NONE.equals(t.apps) ? R.string.team_apps_none : R.string.team_apps_diff));
            TextView hw = text(a, how.toString(), 13, MUTED);
            hw.setPaddingRelative(0, dp(a, 6), 0, 0);
            box.addView(hw);
            boolean screens = false;
            String on = Account.nameOf(ses.backend) + (ses.model == null || ses.model.isEmpty() ? "" : " · " + Models.label(ses.backend, ses.model));
            String leadState = ses.running ? a.getString(Team.waits(hub, ses) ? R.string.team_waiting : R.string.helper_working)
                    : a.getString(R.string.team_ready);
            View lead = info(a, a.getString(R.string.team_leader), Team.leaderColor(a), on, a.getString(R.string.team_this_chat),
                    null, leadState, ses.running, true, here == ses, null, here == ses ? () -> shown.dialog.dismiss() : () -> {
                        shown.dialog.dismiss();
                        ChatActivity.openInTeam(a, ses, here, ses); // (from a member's chat back to the team's)
                    });
            List<View> cards = new ArrayList<>();
            for (Team.Member m : t.members) {
                final Sessions.Session h = Team.session(hub, ses.id, m.id);
                boolean screen = h != null && h.background && Background.hasApp(h.id);
                // (one with a hidden screen at all: hasApp answers from its last look, so the panel looks again soon)
                screens |= h != null && h.background && Background.displayOf(h.id) != android.view.Display.DEFAULT_DISPLAY;
                cards.add(info(a, m.name, Team.color(a, m.color), m.on(), m.goal, h == null ? null : h.lastReply, state(a, h),
                        h != null && h.running, false, h == here, screen ? h : null,
                        // (the one whose chat this is: the panel closes, back to it)
                        h == null ? null : h == here ? () -> shown.dialog.dismiss() : () -> {
                            shown.dialog.dismiss();
                            ChatActivity.openInTeam(a, ses, here, h);
                        }));
            }
            place(a, box, t.structure, lead, cards, cards.size());
            if (!t.handoff.isEmpty() || !t.rules.isEmpty()) {
                LinearLayout facts = column(a);
                facts.setBackground(rounded(a, CARD2, 14));
                facts.setPaddingRelative(dp(a, 12), dp(a, 10), dp(a, 12), dp(a, 10));
                if (!t.handoff.isEmpty()) fact(a, facts, a.getString(R.string.team_fact_handoff, t.handoff));
                if (!t.rules.isEmpty()) fact(a, facts, a.getString(R.string.team_fact_rules, t.rules));
                LinearLayout.LayoutParams fp = full();
                fp.topMargin = dp(a, 14);
                box.addView(facts, fp);
            }
            LinearLayout foot = row(a);
            foot.setGravity(Gravity.CENTER_VERTICAL);
            TextView change = text(a, a.getString(R.string.team_change_hint), 13, MUTED);
            foot.addView(change, weight1());
            if (off != null) {
                Button o = button(a, a.getString(R.string.team_off_yes), false);
                o.setOnClickListener(v -> {
                    shown.dialog.dismiss();
                    off.run();
                });
                LinearLayout.LayoutParams op = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(a, 44));
                op.setMarginStart(dp(a, 12));
                foot.addView(o, op);
            }
            LinearLayout.LayoutParams ftp = full();
            ftp.topMargin = dp(a, 14);
            box.addView(foot, ftp);
            // the screens live while it is open: drawn again every few seconds
            tick.removeCallbacksAndMessages(null);
            if (screens) tick.postDelayed(again[0], 3000);
        };
        again[0] = () -> {
            if (shown.showing()) shown.refresh.run();
        };
        shown.dialog.setOnDismissListener(x -> {
            tick.removeCallbacksAndMessages(null);
            PICTURES.clear();
            PICTURE_VIEWS.clear();
            PICTURE_AT.clear();
        });
        shown.refresh.run();
        shown.dialog.show();
        return shown;
    }

    /** What a member does now, as its chip and its card say it. */
    static String state(Context c, Sessions.Session h) {
        if (h == null || h.messages.isEmpty()) return c.getString(R.string.team_ready);
        if (h.running) return c.getString(R.string.helper_working); // (not its command: the strip has no room for it)
        return c.getString("failed".equals(h.outcome) ? R.string.helper_failed : "stopped".equals(h.outcome) ? R.string.helper_stopped
                : R.string.helper_done);
    }

    private static void fact(Context c, LinearLayout box, String s) {
        TextView t = text(c, s, 13, TEXT2);
        if (box.getChildCount() > 0) t.setPaddingRelative(0, dp(c, 6), 0, 0);
        box.addView(t);
    }

    /**
     * One's card in the panel: its mark and name in its colour, its agent, what it is for, its last answer and what it
     * does now; here: the chat it was opened from (outlined); screen: a member with an app on its hidden screen (the
     * screen live, and Show on my screen); open: its own chat, or null.
     */
    private static View info(final Activity c, String name, int color, String on, String goal, String last, String state,
                             boolean working, boolean leader, boolean here, final Sessions.Session screen, final Runnable open) {
        LinearLayout card = column(c);
        card.setBackground(outlined(c, CARD2, 16, here ? color : leader ? Theme.withAlpha(color, 0x80) : CARD2, 2));
        card.setPaddingRelative(dp(c, 12), dp(c, 10), dp(c, 12), dp(c, 10));
        LinearLayout nm = row(c);
        nm.setGravity(Gravity.CENTER_VERTICAL);
        nm.addView(avatar(c, name, color, 24));
        TextView n = single(text(c, name, 14, color));
        n.setTypeface(sans(c, 600));
        n.setPaddingRelative(dp(c, 8), 0, dp(c, 6), 0);
        nm.addView(n, weight1());
        TextView st = single(text(c, state, 12, working ? TEXT : MUTED));
        if (working) st.setTypeface(sans(c, 600));
        nm.addView(st);
        card.addView(nm, full());
        TextView o = single(text(c, on, 12, TEXT2));
        o.setPaddingRelative(0, dp(c, 4), 0, 0);
        card.addView(o);
        if (goal != null && !goal.isEmpty()) {
            TextView g = text(c, goal, 12, MUTED);
            g.setMaxLines(2);
            g.setEllipsize(android.text.TextUtils.TruncateAt.END);
            g.setPaddingRelative(0, dp(c, 4), 0, 0);
            card.addView(g);
        }
        if (last != null && !last.trim().isEmpty()) {
            TextView l = text(c, c.getString(R.string.team_last, Ui.linkText(ChatHub.clip(last.trim(), 600)).replace('\n', ' ')), 12, TEXT2);
            l.setMaxLines(2);
            l.setEllipsize(android.text.TextUtils.TruncateAt.END);
            l.setPaddingRelative(0, dp(c, 4), 0, 0);
            card.addView(l);
        }
        if (screen != null) { // its hidden screen, live, and the way to bring the app to the user's own
            final ImageView pic = new ImageView(c);
            pic.setScaleType(ImageView.ScaleType.FIT_CENTER);
            pic.setBackground(rounded(c, BG, 10));
            pic.setClipToOutline(true);
            pic.setContentDescription(c.getString(R.string.background_preview));
            android.graphics.Bitmap had = PICTURES.get(screen.id);
            if (had != null) pic.setImageBitmap(had);
            android.util.DisplayMetrics dm = c.getResources().getDisplayMetrics();
            int w = dp(c, 72);
            LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(w, Math.round(w * (dm.heightPixels / (float) dm.widthPixels)));
            pp.topMargin = dp(c, 8);
            pp.gravity = Gravity.CENTER_HORIZONTAL;
            card.addView(pic, pp);
            final android.content.Context app = c.getApplicationContext();
            final String id = screen.id;
            PICTURE_VIEWS.put(id, pic);
            Long at = PICTURE_AT.get(id);
            long now = android.os.SystemClock.elapsedRealtime();
            // one load at a time per member, and not more often than every two seconds; it lands in the panel as it is then
            if ((at == null || now - at > 2000) && LOADING.add(id)) {
                PICTURE_AT.put(id, now);
                new Thread(() -> {
                    try {
                        byte[] jpg = Background.frame(app, id, w * 2, 70);
                        final android.graphics.Bitmap b = jpg == null ? null : android.graphics.BitmapFactory.decodeByteArray(jpg, 0, jpg.length);
                        if (b == null) return;
                        PICTURES.put(id, b);
                        ImageView into = PICTURE_VIEWS.get(id);
                        if (into != null) into.post(() -> into.setImageBitmap(b));
                    } finally {
                        LOADING.remove(id);
                    }
                }, "team-screen").start();
            }
            Button bring = button(c, c.getString(R.string.background_show), false);
            bring.setMinHeight(dp(c, 36));
            bring.setMinimumHeight(dp(c, 36));
            bring.setOnClickListener(v -> {
                BuddyService svc = BuddyService.get();
                if (svc == null) return;
                bring.setEnabled(false);
                new Thread(() -> {
                    Background.bringToScreen(svc, screen);
                    bring.post(() -> bring.setEnabled(true));
                }).start();
            });
            LinearLayout.LayoutParams bp = full();
            bp.topMargin = dp(c, 8);
            card.addView(bring, bp);
        }
        if (open != null) {
            card.setForeground(c.getDrawable(ripple(c, false)));
            card.setOnClickListener(v -> open.run());
            card.setContentDescription((here ? name : c.getString(leader ? R.string.team_back_to_team : R.string.team_open_chat_of, name)) + ", " + state
                    + (here ? ", " + c.getString(R.string.bubble_close) : ""));
        }
        return card;
    }

    /** A structure's shape as a small picture: the leader and what links it in the accent, the members plain. */
    private static final class Shape extends View {
        private final String structure;
        private final android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);

        Shape(Context c, String structure) {
            super(c);
            this.structure = structure;
            p.setStyle(android.graphics.Paint.Style.STROKE);
            p.setStrokeCap(android.graphics.Paint.Cap.ROUND);
            p.setStrokeJoin(android.graphics.Paint.Join.ROUND);
        }

        private float u;
        private android.graphics.Canvas cv;

        private void box(float x, float y, float w, float h, boolean accent) {
            p.setColor(accent ? ACCENT_TEXT : TEXT2);
            cv.drawRoundRect(x * u, y * u, (x + w) * u, (y + h) * u, 3 * u, 3 * u, p);
        }

        private void line(float... xy) {
            p.setColor(ACCENT_TEXT);
            for (int i = 0; i + 3 < xy.length; i += 2) cv.drawLine(xy[i] * u, xy[i + 1] * u, xy[i + 2] * u, xy[i + 3] * u, p);
        }

        @Override
        protected void onDraw(android.graphics.Canvas canvas) {
            cv = canvas;
            u = Math.min(getWidth() / 40f, getHeight() / 32f); // drawn on 40 × 32
            canvas.translate((getWidth() - 40 * u) / 2, (getHeight() - 32 * u) / 2);
            p.setStrokeWidth(2 * u);
            if (Team.LEAD.equals(structure)) {
                box(14, 2, 12, 8, true);
                line(20, 10, 20, 15);
                line(7, 15, 33, 15);
                line(7, 15, 7, 21);
                line(20, 15, 20, 21);
                line(33, 15, 33, 21);
                box(2, 21, 10, 8, false);
                box(15, 21, 10, 8, false);
                box(28, 21, 10, 8, false);
            } else if (Team.CHECK.equals(structure)) {
                box(2, 11, 12, 10, false);
                box(26, 11, 12, 10, false);
                line(16, 14, 24, 14);
                line(21, 11.5f, 24, 14, 21, 16.5f);
                line(24, 18.5f, 16, 18.5f);
                line(19, 16, 16, 18.5f, 19, 21);
            } else if (Team.DOERS.equals(structure)) {
                box(2, 3, 16, 10, false);
                box(22, 3, 16, 10, false);
                line(10, 13, 10, 19, 30, 19, 30, 13);
                line(20, 19, 20, 23);
                box(14, 23, 12, 7, true);
            } else {
                box(1, 12, 10, 8, false);
                box(15, 12, 10, 8, false);
                box(29, 12, 10, 8, false);
                line(11, 16, 15, 16);
                line(25, 16, 29, 16);
            }
        }
    }

    /** The picture of a structure on its tile, for the setup's first question and the strip's button. */
    static View shape(Context c, String structure, int w, int h) {
        FrameLayout tile = new FrameLayout(c);
        tile.setBackground(rounded(c, CARD, 12));
        int pad = dp(c, Math.max(3, h / 8));
        tile.setPadding(pad, pad, pad, pad);
        tile.addView(new Shape(c, structure), new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        tile.setLayoutParams(new LinearLayout.LayoutParams(dp(c, w), dp(c, h)));
        return tile;
    }

    /** From the leader down to the two columns of members: drawn, as arrows that point sideways come out as emoji. */
    private static final class Fan extends View {
        private final android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);

        Fan(Context c) {
            super(c);
            p.setStyle(android.graphics.Paint.Style.STROKE);
            p.setStrokeCap(android.graphics.Paint.Cap.ROUND);
            p.setStrokeWidth(dp(c, 2));
        }

        @Override
        protected void onDraw(android.graphics.Canvas cv) {
            p.setColor(ACCENT_TEXT);
            float w = getWidth(), h = getHeight(), top = h * 0.15f, mid = h * 0.5f, end = h * 0.85f;
            cv.drawLine(w / 2, top, w / 2, mid, p);
            cv.drawLine(w / 4, mid, w * 3 / 4, mid, p);
            cv.drawLine(w / 4, mid, w / 4, end, p);
            cv.drawLine(w * 3 / 4, mid, w * 3 / 4, end, p);
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
        card.setBackground(outlined(a, CARD2, 16, leader ? Team.leaderColor(a) : CARD2, 2));
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
            // (the phone's own menu look, light or dark, as the app's other dropdown)
            android.widget.PopupMenu menu = new android.widget.PopupMenu(
                    new android.view.ContextThemeWrapper(a, android.R.style.Theme_DeviceDefault_DayNight), p);
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

    /**
     * What a member's mark says: its initial, with its number where its name ends in one ("Worker 2": W2; a number of
     * two digits alone), so a team of Worker 1 to 4 isn't four times W.
     */
    static String mark(String name) {
        String n = name == null ? "" : name.trim();
        if (n.isEmpty()) return "?";
        String first = n.substring(0, n.offsetByCodePoints(0, 1)).toUpperCase(java.util.Locale.getDefault());
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(\\d{1,2})$").matcher(n);
        if (!m.find() || m.start() == 0) return first;
        return m.group(1).length() == 1 ? first + m.group(1) : m.group(1);
    }

    /** A member's mark: its initial on its colour, in the ink (dark or light) that reads on it. */
    static TextView avatar(Context c, String name, int color, int sizeDp) {
        TextView t = new TextView(c);
        String mark = mark(name);
        t.setText(mark);
        t.setGravity(Gravity.CENTER);
        t.setIncludeFontPadding(false);
        t.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, sizeDp * (mark.length() > 1 ? 0.42f : 0.5f));
        t.setTypeface(sans(c, 700));
        t.setTextColor(Theme.luminance(color) > 0.5 ? 0xFF1A1714 : 0xFFFFFFFF);
        t.setBackground(oval(color));
        t.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(c, sizeDp), dp(c, sizeDp));
        p.gravity = Gravity.CENTER_VERTICAL;
        t.setLayoutParams(p);
        return t;
    }

    /** Who a team chat's message can go to: the leader first, then the members ({id, name, colour}). */
    static List<Object[]> recipients(Context c, Team t) {
        List<Object[]> l = new ArrayList<>();
        l.add(new Object[]{Team.LEADER, c.getString(R.string.team_leader), Team.leaderColor(c)});
        for (Team.Member m : t.members) l.add(new Object[]{m.id, m.name, Team.color(c, m.color)});
        return l;
    }

    /**
     * The message box's "To" chip: the ones a message goes to now (their marks, a name or how many), and an arrow
     * that opens the picker (see sendToSheet).
     */
    static void paintTo(Context c, LinearLayout chip, Team t, java.util.Set<String> to) {
        chip.removeAllViews();
        List<String> names = new ArrayList<>();
        int shown = 0;
        for (Object[] r : recipients(c, t)) {
            if (!to.contains((String) r[0])) continue;
            names.add((String) r[1]);
            if (shown++ >= 3) continue; // (three marks at most, the others in the count)
            TextView av = avatar(c, (String) r[1], (Integer) r[2], 18);
            av.setBackground(outlinedOval(c, (Integer) r[2], CARD2, 2));
            LinearLayout.LayoutParams p = (LinearLayout.LayoutParams) av.getLayoutParams();
            if (shown > 1) p.setMarginStart(-dp(c, 3)); // (a little over the one before: its number stays readable)
            chip.addView(av, p);
        }
        String label = names.size() == 1 ? c.getString(R.string.team_to, names.get(0)) : c.getString(R.string.team_to_n, names.size());
        TextView n = single(text(c, label, 13, TEXT));
        n.setPaddingRelative(dp(c, 6), 0, dp(c, 2), 0);
        chip.addView(n);
        chip.addView(icon(c, R.drawable.ms_expand_less, MUTED, 16));
        chip.setContentDescription(c.getString(R.string.team_to_desc, android.text.TextUtils.join(", ", names)));
    }

    private static android.graphics.drawable.Drawable outlinedOval(Context c, int fill, int ring, int ringDp) {
        android.graphics.drawable.GradientDrawable g = new android.graphics.drawable.GradientDrawable();
        g.setShape(android.graphics.drawable.GradientDrawable.OVAL);
        g.setColor(fill);
        g.setStroke(dp(c, ringDp), ring);
        return g;
    }

    /**
     * The picker the "To" chip opens: the leader and each member, its agent and what it does now, ticked or not
     * (a tick takes effect at once); Everyone ticks them all. The leader alone when nothing is left ticked.
     */
    static Dialog sendToSheet(final Activity a, final ChatHub hub, final Sessions.Session owner, final java.util.Set<String> to,
                              final Runnable changed) {
        final LinearLayout box = column(a);
        final Dialog d = bottomSheet(a, box);
        final Runnable[] render = new Runnable[1];
        render[0] = () -> {
            box.removeAllViews();
            Team t = owner.team;
            if (t == null) {
                d.dismiss();
                return;
            }
            box.addView(title(a, a.getString(R.string.team_send_to), 20));
            for (final Object[] r : recipients(a, t)) {
                final String id = (String) r[0];
                boolean leader = Team.LEADER.equals(id);
                Team.Member m = leader ? null : t.byId(id);
                Sessions.Session h = leader ? owner : Team.session(hub, owner.id, id);
                String on = leader ? Account.nameOf(owner.backend) : m == null ? "" : m.on();
                String state = leader ? a.getString(owner.running ? R.string.helper_working : R.string.team_ready) : state(a, h);
                boolean ticked = to.contains(id);
                LinearLayout row = row(a);
                row.setGravity(Gravity.CENTER_VERTICAL);
                row.setMinimumHeight(dp(a, 56));
                row.setPaddingRelative(dp(a, 10), dp(a, 6), dp(a, 10), dp(a, 6));
                row.setBackground(rounded(a, ticked ? CARD2 : 0x00000000, 14));
                row.setForeground(a.getDrawable(ripple(a, false)));
                FrameLayout tick = new FrameLayout(a);
                int color = (Integer) r[2];
                tick.setBackground(outlined(a, ticked ? color : 0x00000000, 5, color, 2));
                if (ticked) tick.addView(icon(a, R.drawable.ms_check, Theme.luminance(color) > 0.5 ? 0xFF1A1714 : 0xFFFFFFFF, 14),
                        new FrameLayout.LayoutParams(dp(a, 14), dp(a, 14), Gravity.CENTER));
                row.addView(tick, new LinearLayout.LayoutParams(dp(a, 20), dp(a, 20)));
                TextView av = avatar(a, (String) r[1], color, 28);
                LinearLayout.LayoutParams ap = (LinearLayout.LayoutParams) av.getLayoutParams();
                ap.setMarginStart(dp(a, 12));
                row.addView(av, ap);
                LinearLayout col = column(a);
                col.setPaddingRelative(dp(a, 12), 0, 0, 0);
                TextView n = single(text(a, (String) r[1], 15, TEXT));
                n.setTypeface(sans(a, 600));
                col.addView(n);
                col.addView(single(text(a, on.isEmpty() ? state : on + " · " + state, 12, MUTED)));
                row.addView(col, weight1());
                row.setContentDescription(r[1] + ", " + state);
                row.setSelected(ticked);
                row.setOnClickListener(v -> {
                    if (!to.remove(id)) to.add(id);
                    if (to.isEmpty()) to.add(Team.LEADER);
                    changed.run();
                    render[0].run();
                });
                LinearLayout.LayoutParams rp = full();
                rp.topMargin = dp(a, 6);
                box.addView(row, rp);
            }
            LinearLayout buttons = row(a);
            Button all = button(a, a.getString(R.string.team_everyone), false);
            all.setOnClickListener(v -> {
                for (Object[] r : recipients(a, owner.team == null ? new Team() : owner.team)) to.add((String) r[0]);
                changed.run();
                render[0].run();
            });
            Button done = button(a, a.getString(R.string.team_done), true);
            done.setOnClickListener(v -> d.dismiss());
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(0, dp(a, 48), 1);
            bp.setMarginEnd(dp(a, 8));
            buttons.addView(all, bp);
            buttons.addView(done, new LinearLayout.LayoutParams(0, dp(a, 48), 1));
            LinearLayout.LayoutParams bsp = full();
            bsp.topMargin = dp(a, 16);
            box.addView(buttons, bsp);
        };
        render[0].run();
        d.show();
        return d;
    }

    /**
     * A round of member answers that came one after another, as one card ({name, colour, text} each): their marks,
     * how many, each one's first line, and Show all.
     */
    static View round(Context c, List<Object[]> who, final Runnable showAll) {
        LinearLayout card = column(c);
        card.setBackground(outlined(c, CARD, 16, LINE, 1));
        card.setPaddingRelative(dp(c, 14), dp(c, 12), dp(c, 14), dp(c, 12));
        LinearLayout head = row(c);
        head.setGravity(Gravity.CENTER_VERTICAL);
        java.util.Set<String> seen = new java.util.HashSet<>();
        for (Object[] w : who) {
            if (!seen.add((String) w[0]) || seen.size() > 4) continue;
            TextView av = avatar(c, (String) w[0], (Integer) w[1], 22);
            av.setBackground(outlinedOval(c, (Integer) w[1], CARD, 2));
            LinearLayout.LayoutParams p = (LinearLayout.LayoutParams) av.getLayoutParams();
            if (seen.size() > 1) p.setMarginStart(-dp(c, 3)); // (a little over the one before: its number stays readable)
            head.addView(av, p);
        }
        // (never cut short: with four marks beside it "4 answers" lost its end; a second line where it must)
        TextView n = text(c, c.getResources().getQuantityString(R.plurals.team_round, who.size(), who.size()), 13, TEXT);
        n.setMaxLines(2);
        n.setTypeface(sans(c, 600));
        n.setPaddingRelative(dp(c, 8), 0, dp(c, 8), 0);
        head.addView(n, weight1());
        Button all = button(c, c.getString(R.string.team_show_all), false);
        all.setMinHeight(dp(c, 36));
        all.setMinimumHeight(dp(c, 36));
        all.setMinWidth(0);
        all.setMinimumWidth(0);
        all.setPaddingRelative(dp(c, 12), 0, dp(c, 12), 0);
        all.setTextSize(13);
        all.setOnClickListener(v -> showAll.run());
        head.addView(all, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(c, 36)));
        card.addView(head, full());
        for (Object[] w : who) {
            TextView l = text(c, "", 13, TEXT2);
            android.text.SpannableString s = new android.text.SpannableString(w[0] + "  " + w[2]);
            s.setSpan(new android.text.style.ForegroundColorSpan((Integer) w[1]), 0, ((String) w[0]).length(), 0);
            s.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), 0, ((String) w[0]).length(), 0);
            l.setText(s);
            l.setMaxLines(2);
            l.setEllipsize(android.text.TextUtils.TruncateAt.END);
            l.setPaddingRelative(0, dp(c, 8), 0, 0);
            card.addView(l, full());
        }
        return card;
    }

    /** One in the team bar: its mark (ringed in the chat it is), and what it does now in a word. */
    static View barItem(Context c, String name, int color, String word, boolean working, boolean here) {
        LinearLayout r = row(c);
        r.setGravity(Gravity.CENTER_VERTICAL);
        FrameLayout ring = new FrameLayout(c);
        if (here) ring.setBackground(outlinedOval(c, 0x00000000, color, 2));
        ring.addView(avatar(c, name, color, 20), new FrameLayout.LayoutParams(dp(c, 20), dp(c, 20), Gravity.CENTER));
        r.addView(ring, new LinearLayout.LayoutParams(dp(c, 28), dp(c, 28)));
        TextView w = single(text(c, word, 12, working || here ? TEXT : MUTED));
        if (working) w.setTypeface(sans(c, 600));
        w.setPaddingRelative(dp(c, 4), 0, dp(c, 10), 0);
        r.addView(w);
        r.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
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
