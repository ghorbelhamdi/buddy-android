package app.buddy.assistant;

import android.app.Dialog;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

import static app.buddy.assistant.Ui.*;

/**
 * Settings → Notification rules: the rules, notification access, the switch that pauses them all and what they did
 * lately; with the extra "edit" (a rule's id, or "" for a new one) it's the rule editor.
 */
public class RulesActivity extends Screen {
    private LinearLayout body;
    /** On the editor page: the rule being edited (a copy, saved with Save). */
    private Rules.Rule draft;
    private boolean isNew;
    private final Runnable logChanged = () -> runOnUiThread(() -> {
        if (draft == null && !isFinishing()) renderList();
    });

    /** An installed app, loaded off the main thread (labels and icons are slow). */
    private static final class App {
        String pkg, label;
        Drawable icon;
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        String edit = getIntent().getStringExtra("edit");
        if (edit != null) {
            Rules.Rule r = edit.isEmpty() ? null : Rules.find(this, edit);
            isNew = r == null;
            draft = r == null ? new Rules.Rule() : r;
            if (isNew) draft.backend = Account.of(Prefs.backend(this)).runsRules() ? Prefs.backend(this) : "claude";
        }
        LinearLayout root = column(this);
        root.setBackgroundColor(BG);
        LinearLayout top = row(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPaddingRelative(dp(this, 4), dp(this, 4), dp(this, 16), dp(this, 4));
        ImageButton back = iconButton(this, R.drawable.ms_arrow_back, getString(R.string.perms_back));
        back.setLayoutParams(new LinearLayout.LayoutParams(dp(this, 44), dp(this, 44)));
        back.setOnClickListener(v -> finish());
        top.addView(back);
        TextView t = title(this, getString(draft == null ? R.string.rules_title : isNew ? R.string.rules_new : R.string.rules_edit), 18);
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
        // agents never change rules (a rule can let them act on their own), even when they may tap Buddy's screens
        setContentView(protect(frame));
        if (draft != null) renderEditor();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (draft == null) {
            Triggers.logListener = logChanged;
            renderList();
        } else if (approvalSlot != null) {
            renderApproval(); // the second AI may have been changed in App permissions
        }
    }

    @Override
    protected void onPause() {
        if (Triggers.logListener == logChanged) Triggers.logListener = null;
        super.onPause();
    }

    // ------------------------------------------------------------------ list

    private void renderList() {
        body.removeAllViews();
        body.addView(text(this, getString(R.string.rules_intro), 13, MUTED), full());

        if (!NotificationWatcher.enabled(this)) {
            LinearLayout warn = column(this);
            warn.setPaddingRelative(dp(this, 16), dp(this, 14), dp(this, 16), dp(this, 14));
            warn.setBackground(outlined(this, CARD, 16, Theme.withAlpha(ACCENT, 0x59), 1));
            LinearLayout head = row(this);
            head.addView(icon(this, R.drawable.ms_warning, ACCENT, 20));
            TextView wt = title(this, getString(R.string.rules_access_off_title), 15);
            wt.setPaddingRelative(dp(this, 10), 0, 0, 0);
            head.addView(wt, weight1());
            warn.addView(head);
            String why = getString(R.string.rules_access_off_text);
            boolean restricted = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU;
            if (restricted) why += "\n\n" + getString(R.string.setup_step_a11y_restricted);
            warn.addView(text(this, why, 13, MUTED), gap(6));
            LinearLayout btns = row(this);
            if (restricted) {
                Button info = button(this, getString(R.string.setup_app_info), false);
                info.setOnClickListener(v -> startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        android.net.Uri.parse("package:" + getPackageName()))));
                btns.addView(info);
            }
            Button on = button(this, getString(R.string.rules_access_turn_on), true);
            on.setOnClickListener(v -> openAccess());
            LinearLayout.LayoutParams op = wrap();
            op.setMarginStart(dp(this, restricted ? 8 : 0));
            btns.addView(on, op);
            warn.addView(btns, gap(12));
            body.addView(warn, gap(16));
        }

        LinearLayout sw = switchRow(this, getString(R.string.rules_run), getString(R.string.rules_run_info),
                Prefs.rulesOn(this), on -> Prefs.setRulesOn(this, on));
        sw.setPaddingRelative(0, dp(this, 14), 0, dp(this, 14));
        body.addView(sw, gap(8));

        body.addView(label(getString(R.string.rules_section)), gap(12));
        List<Rules.Rule> rules = Rules.all(this);
        if (rules.isEmpty()) body.addView(text(this, getString(R.string.rules_empty), 13, MUTED), gap(6));
        for (Rules.Rule r : rules) body.addView(ruleRow(r), full());
        Button add = button(this, getString(R.string.rules_add), true);
        add.setOnClickListener(v -> startActivity(new Intent(this, RulesActivity.class).putExtra("edit", "")));
        body.addView(add, gap(12));

        List<JSONObject> log = Triggers.log(this);
        LinearLayout lh = row(this);
        lh.setGravity(Gravity.CENTER_VERTICAL);
        lh.addView(label(getString(R.string.rules_activity)), weight1());
        if (!log.isEmpty()) {
            TextView clear = text(this, getString(R.string.rules_activity_clear), 13, ACCENT_TEXT);
            clear.setPaddingRelative(dp(this, 12), dp(this, 6), 0, dp(this, 6));
            clear.setOnClickListener(v -> {
                Triggers.clearLog(this);
                renderList();
            });
            lh.addView(clear);
        }
        body.addView(lh, gap(24));
        if (log.isEmpty()) body.addView(text(this, getString(R.string.rules_activity_empty), 13, MUTED), gap(6));
        for (int i = 0; i < log.size() && i < 20; i++) body.addView(logRow(log.get(i)), gap(10));
    }

    private View ruleRow(final Rules.Rule r) {
        LinearLayout row = row(this);
        row.setGravity(Gravity.TOP);
        row.setPaddingRelative(0, dp(this, 10), 0, dp(this, 10));
        row.setBackgroundResource(ripple(this, false));
        ImageView ic = new ImageView(this);
        try {
            ic.setImageDrawable(getPackageManager().getApplicationIcon(r.pkg));
        } catch (PackageManager.NameNotFoundException e) {
            ic.setImageResource(R.drawable.ms_warning);
            ic.setImageTintList(android.content.res.ColorStateList.valueOf(MUTED));
        }
        row.addView(ic, new LinearLayout.LayoutParams(dp(this, 32), dp(this, 32)));
        LinearLayout col = column(this);
        col.setPaddingRelative(dp(this, 12), 0, dp(this, 8), 0);
        String filters = r.filters(this);
        col.addView(single(title(this, r.app + " · " + (filters == null ? getString(R.string.rules_any) : filters), 15)));
        TextView ins = text(this, r.instruction, 13, MUTED);
        ins.setMaxLines(2);
        ins.setEllipsize(android.text.TextUtils.TruncateAt.END);
        ins.setPaddingRelative(0, dp(this, 2), 0, 0);
        col.addView(ins);
        String tag = (r.on ? "" : getString(R.string.rules_tag_off) + " · ") + Account.nameOf(r.backend)
                + (r.model.isEmpty() ? "" : " · " + Models.label(r.backend, r.model)) + " · "
                + (r.countdown() ? getString(R.string.rules_tag_countdown, Rules.Rule.duration(this, r.seconds))
                : getString(R.string.rules_tag_ask)) + " · "
                + (r.limit == 0 ? getString(R.string.rules_tag_no_limit) : getString(R.string.rules_tag_limit, r.limit));
        TextView tg = monoText(this, tag, 12, r.on ? ACCENT_TEXT : MUTED);
        tg.setPaddingRelative(0, dp(this, 4), 0, 0);
        col.addView(tg);
        row.addView(col, weight1());
        row.addView(icon(this, R.drawable.ms_chevron_right, MUTED, 20));
        if (!r.on) row.setAlpha(0.55f);
        row.setOnClickListener(v -> startActivity(new Intent(this, RulesActivity.class).putExtra("edit", r.id)));
        return row;
    }

    private View logRow(JSONObject e) {
        LinearLayout col = column(this);
        String when = android.text.format.DateUtils.formatSameDayTime(e.optLong("at"), System.currentTimeMillis(),
                java.text.DateFormat.SHORT, java.text.DateFormat.SHORT).toString();
        col.addView(single(monoText(this, when + " · " + e.optString("who") + " · " + e.optString("app"), 12, MUTED)));
        String d = e.optString("detail");
        String line;
        switch (e.optString("kind")) {
            case "replied": line = getString(R.string.rules_log_replied, d); break;
            case "limit": line = getString(R.string.rules_log_limit); break; // before 2.2.3, a limit dropped the run
            case "held": line = getString(R.string.rules_log_held, d); break;
            case "waiting": line = getString(R.string.rules_log_waiting, d); break;
            case "full": line = getString(R.string.rules_log_full); break;
            case "hidden": line = getString(R.string.rules_log_hidden); break;
            case "busy": line = getString(R.string.rules_log_busy); break; // before 2.2.4, a long wait dropped the run
            case "error": line = getString(R.string.rules_log_error, d); break;
            default: line = d.isEmpty() ? getString(R.string.rules_log_done_empty) : d;
        }
        TextView t = text(this, line, 14, TEXT);
        t.setMaxLines(3);
        t.setEllipsize(android.text.TextUtils.TruncateAt.END);
        t.setPaddingRelative(0, dp(this, 2), 0, 0);
        col.addView(t);
        return col;
    }

    // ---------------------------------------------------------------- editor

    private TextView appName;
    private ImageView appIcon;
    private EditText instruction;
    private FrameLayout approvalSlot, limitSlot;

    private void renderEditor() {
        body.removeAllViews();

        body.addView(label(getString(R.string.rules_app)), gap(8));
        LinearLayout pick = row(this);
        pick.setGravity(Gravity.CENTER_VERTICAL);
        pick.setPaddingRelative(dp(this, 12), dp(this, 10), dp(this, 12), dp(this, 10));
        pick.setBackground(outlined(this, CARD, 12, OUTLINE, 1));
        appIcon = new ImageView(this);
        pick.addView(appIcon, new LinearLayout.LayoutParams(dp(this, 28), dp(this, 28)));
        appName = text(this, "", 15, TEXT);
        appName.setPaddingRelative(dp(this, 12), 0, 0, 0);
        pick.addView(appName, weight1());
        pick.addView(icon(this, R.drawable.ms_expand_more, MUTED, 20));
        pick.setOnClickListener(v -> pickApp());
        body.addView(pick, gap(8));
        showApp();

        body.addView(label(getString(R.string.rules_from)), gap(18));
        body.addView(field(draft.from, getString(R.string.rules_from_hint), false, s -> draft.from = s), gap(8));
        body.addView(label(getString(R.string.rules_words)), gap(18));
        body.addView(field(draft.words, getString(R.string.rules_words_hint), false, s -> draft.words = s), gap(8));

        body.addView(label(getString(R.string.rules_instruction)), gap(18));
        instruction = field(draft.instruction, getString(R.string.rules_instruction_hint), true, s -> draft.instruction = s);
        body.addView(instruction, gap(8));
        // examples to start from: a tap puts one in the box
        int[][] ex = {{R.string.rules_example_reply, R.string.rules_example_reply_text},
                {R.string.rules_example_summary, R.string.rules_example_summary_text},
                {R.string.rules_example_calendar, R.string.rules_example_calendar_text}};
        HorizontalChips chips = new HorizontalChips();
        for (int[] e : ex) {
            TextView c = text(this, getString(e[0]), 13, TEXT);
            c.setPaddingRelative(dp(this, 10), dp(this, 6), dp(this, 10), dp(this, 6));
            c.setBackground(outlined(this, 0x00000000, 10, OUTLINE, 1));
            c.setOnClickListener(v -> {
                instruction.setText(getString(e[1]));
                instruction.setSelection(instruction.getText().length());
            });
            chips.add(c);
        }
        body.addView(chips.view(), gap(8));

        body.addView(label(getString(R.string.rules_agent)), gap(20));
        java.util.Set<String> notReady = new java.util.HashSet<>();
        java.util.List<String[]> runners = new java.util.ArrayList<>();
        for (Account a : Account.ALL) {
            if (!a.runsRules()) continue;
            runners.add(new String[]{a.agent, a.name()});
            if (!SetupState.signedIn(this, a.agent)) notReady.add(a.agent);
        }
        String[][] agents = runners.toArray(new String[0][]);
        final FrameLayout agentSlot = new FrameLayout(this);
        final Runnable[] paintAgent = new Runnable[1];
        paintAgent[0] = () -> {
            agentSlot.removeAllViews();
            LinearLayout box = column(this);
            box.addView(segmented(this, agents, draft.backend, false, v -> {
                draft.backend = v;
                draft.model = ""; // model names differ per agent
                paintAgent[0].run();
                renderApproval(); // the second AI can depend on the agent
            }, notReady, v -> Toast.makeText(this, getString(R.string.perms_reviewer_sign_in, Account.nameOf(v)),
                    Toast.LENGTH_LONG).show()), full());
            // the model: the agent's default, or one picked from its list (a smaller one is quicker and uses less of the plan)
            LinearLayout model = row(this);
            model.setGravity(Gravity.CENTER_VERTICAL);
            model.setPaddingRelative(dp(this, 12), dp(this, 10), dp(this, 12), dp(this, 10));
            model.setBackground(outlined(this, CARD, 12, OUTLINE, 1));
            model.addView(text(this, getString(R.string.chat_model), 15, MUTED), weight1());
            model.addView(single(monoText(this, Models.label(draft.backend, draft.model), 13, TEXT)));
            model.addView(icon(this, R.drawable.ms_expand_more, MUTED, 20));
            model.setOnClickListener(v -> modelSheet(this, draft.backend, draft.model, getString(R.string.rules_model_default), m -> {
                draft.model = m;
                paintAgent[0].run();
            }));
            box.addView(model, gap(8));
            agentSlot.addView(box);
        };
        paintAgent[0].run();
        body.addView(agentSlot, gap(8));

        body.addView(label(getString(R.string.rules_approval)), gap(20));
        approvalSlot = new FrameLayout(this);
        body.addView(approvalSlot, gap(8));
        renderApproval();

        body.addView(label(getString(R.string.rules_limit)), gap(20));
        limitSlot = new FrameLayout(this);
        body.addView(limitSlot, gap(8));
        renderLimit();

        if (!isNew) {
            LinearLayout sw = switchRow(this, getString(R.string.rules_on), null, draft.on, on -> draft.on = on);
            sw.setPaddingRelative(0, dp(this, 14), 0, dp(this, 14));
            body.addView(sw, gap(12));
        }

        LinearLayout btns = row(this);
        if (!isNew) {
            Button del = button(this, getString(R.string.rules_delete), false);
            del.setTextColor(ERR);
            del.setBackground(rounded(this, ERR_SOFT, 12));
            del.setOnClickListener(v -> confirmSheet(this, getString(R.string.rules_delete_title), null,
                    getString(R.string.rules_delete), true, () -> {
                        Rules.remove(this, draft.id);
                        finish();
                    }));
            btns.addView(del, new LinearLayout.LayoutParams(0, dp(this, 48), 1));
        }
        Button save = button(this, getString(R.string.rules_save), true);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(0, dp(this, 48), 1);
        if (!isNew) sp.setMarginStart(dp(this, 10));
        btns.addView(save, sp);
        save.setOnClickListener(v -> save());
        body.addView(btns, gap(24));
    }

    private void renderApproval() {
        approvalSlot.removeAllViews();
        LinearLayout box = column(this);
        box.addView(segmented(this, new String[][]{{"ask", getString(R.string.rules_approval_ask)},
                {"countdown", getString(R.string.rules_approval_countdown)}}, draft.approval, false, v -> {
            draft.approval = v;
            renderApproval();
        }), full());
        if (draft.countdown()) {
            String[][] lengths = new String[Rules.SECONDS.length][];
            for (int i = 0; i < lengths.length; i++) {
                lengths[i] = new String[]{String.valueOf(Rules.SECONDS[i]), Rules.Rule.duration(this, Rules.SECONDS[i])};
            }
            box.addView(segmented(this, lengths, String.valueOf(draft.seconds), true, v -> {
                draft.seconds = Integer.parseInt(v);
                renderApproval();
            }), gap(8));
        }
        box.addView(text(this, getString(draft.countdown() ? R.string.rules_approval_countdown_info
                : R.string.rules_approval_ask_info), 12, MUTED), gap(8));
        if (draft.countdown()) { // which second AI checks the replies, and where to change it
            String harness = Reviewer.harness(this, draft.backend);
            String who = harness == null ? null : Account.nameOf(harness) + " · " + ("claude".equals(harness)
                    && Prefs.reviewerModel(this, harness).isEmpty() ? "Haiku" : Models.label(harness, Prefs.reviewerModel(this, harness)));
            TextView checker = text(this, who == null ? getString(R.string.tools_card_no_reviewer) : getString(R.string.rules_checker, who),
                    12, ACCENT_TEXT);
            checker.setPaddingRelative(0, dp(this, 6), 0, dp(this, 6));
            checker.setOnClickListener(v -> startActivity(new Intent(this, AppPermissionsActivity.class)));
            box.addView(checker, gap(2));
        }
        approvalSlot.addView(box);
    }

    /** Runs an hour for one conversation; past it, the conversation's messages wait and get one run together. */
    private void renderLimit() {
        limitSlot.removeAllViews();
        LinearLayout box = column(this);
        String[][] limits = new String[Rules.LIMITS.length][];
        for (int i = 0; i < limits.length; i++) {
            int n = Rules.LIMITS[i];
            limits[i] = new String[]{String.valueOf(n), n == 0 ? getString(R.string.rules_limit_off)
                    : String.format(java.util.Locale.getDefault(), "%d", n)};
        }
        box.addView(segmented(this, limits, String.valueOf(draft.limit), true, v -> {
            draft.limit = Integer.parseInt(v);
            renderLimit();
        }), full());
        box.addView(text(this, getString(R.string.rules_limit_info), 12, MUTED), gap(8));
        limitSlot.addView(box);
    }

    private void showApp() {
        if (draft.pkg.isEmpty()) {
            appName.setText(R.string.rules_pick_app);
            appName.setTextColor(MUTED);
            appIcon.setVisibility(View.GONE);
            return;
        }
        appName.setText(draft.app);
        appName.setTextColor(TEXT);
        appIcon.setVisibility(View.VISIBLE);
        try {
            appIcon.setImageDrawable(getPackageManager().getApplicationIcon(draft.pkg));
        } catch (PackageManager.NameNotFoundException e) {
            appIcon.setVisibility(View.GONE);
        }
    }

    private void save() {
        if (draft.pkg.isEmpty()) {
            Toast.makeText(this, R.string.rules_need_app, Toast.LENGTH_SHORT).show();
            return;
        }
        draft.instruction = draft.instruction.trim();
        if (draft.instruction.isEmpty()) {
            Toast.makeText(this, R.string.rules_need_instruction, Toast.LENGTH_SHORT).show();
            instruction.requestFocus();
            return;
        }
        draft.from = draft.from.trim();
        draft.words = draft.words.trim();
        Rules.put(this, draft);
        Triggers.get(this).rulesChanged();
        if (NotificationWatcher.enabled(this)) finish();
        else openAccess(); // the rule can't see anything yet
    }

    /** Pick the app whose notifications the rule watches: a sheet with every app, and a search box. */
    private void pickApp() {
        LinearLayout c = column(this);
        c.addView(title(this, getString(R.string.rules_pick_app), 18));
        EditText search = new EditText(this);
        search.setHint(R.string.rules_search_apps);
        search.setHintTextColor(MUTED);
        search.setTextColor(TEXT);
        search.setTextSize(15);
        search.setSingleLine(true);
        search.setBackground(outlined(this, CARD, 12, OUTLINE, 1));
        search.setPaddingRelative(dp(this, 12), dp(this, 10), dp(this, 12), dp(this, 10));
        c.addView(search, gap(12));
        LinearLayout list = column(this);
        list.addView(spinner(this, 28), gap(16));
        c.addView(list, gap(8));
        Dialog d = bottomSheet(this, c);
        final List<App> apps = new ArrayList<>();
        final Runnable fill = () -> {
            list.removeAllViews();
            String q = search.getText().toString().trim().toLowerCase(Ui.locale(this));
            for (App a : apps) {
                if (!q.isEmpty() && !a.label.toLowerCase(Ui.locale(this)).contains(q)) continue;
                LinearLayout r = row(this);
                r.setGravity(Gravity.CENTER_VERTICAL);
                r.setPaddingRelative(dp(this, 4), dp(this, 8), dp(this, 4), dp(this, 8));
                r.setBackgroundResource(ripple(this, false));
                ImageView ic = new ImageView(this);
                ic.setImageDrawable(a.icon);
                r.addView(ic, new LinearLayout.LayoutParams(dp(this, 32), dp(this, 32)));
                TextView l = single(text(this, a.label, 15, TEXT));
                l.setPaddingRelative(dp(this, 12), 0, 0, 0);
                r.addView(l, weight1());
                r.setOnClickListener(v -> {
                    draft.pkg = a.pkg;
                    draft.app = a.label;
                    showApp();
                    d.dismiss();
                });
                list.addView(r, full());
            }
        };
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int n) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int n) {
            }

            @Override
            public void afterTextChanged(Editable e) {
                if (!apps.isEmpty()) fill.run();
            }
        });
        d.show();
        new Thread(() -> {
            PackageManager pm = getPackageManager();
            List<App> out = new ArrayList<>();
            java.util.Set<String> seen = new java.util.HashSet<>();
            for (ResolveInfo ri : pm.queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)) {
                String pkg = ri.activityInfo.packageName;
                if (pkg.equals(getPackageName()) || !seen.add(pkg)) continue;
                App a = new App();
                a.pkg = pkg;
                a.label = String.valueOf(ri.loadLabel(pm));
                a.icon = ri.loadIcon(pm);
                out.add(a);
            }
            out.sort((x, y) -> x.label.compareToIgnoreCase(y.label));
            runOnUiThread(() -> {
                if (isFinishing() || !d.isShowing()) return;
                apps.addAll(out);
                fill.run();
            });
        }, "rule-apps").start();
    }

    // ------------------------------------------------------- notification access

    /**
     * Opens Android's notification access page for Buddy, after the user has agreed to what it's for (Google Play's
     * prominent disclosure: shown in the app, before the permission, with an explicit choice).
     */
    private void openAccess() {
        Runnable open = () -> {
            try {
                startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(
                        Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME,
                        new ComponentName(this, NotificationWatcher.class).flattenToString()));
            } catch (Exception e) {
                startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
            }
        };
        if (Prefs.noticeConsent(this)) {
            open.run();
            return;
        }
        LinearLayout c = column(this);
        c.addView(title(this, getString(R.string.rules_access_title), 18));
        for (int s : new int[]{R.string.rules_access_what, R.string.rules_access_data, R.string.rules_access_reply}) {
            TextView t = text(this, getString(s), 14, MUTED);
            t.setPaddingRelative(0, dp(this, 8), 0, 0);
            c.addView(t);
        }
        TextView policy = text(this, getString(R.string.ui_privacy_policy), 14, ACCENT_TEXT);
        policy.setPaddingRelative(0, dp(this, 10), 0, 0);
        policy.setOnClickListener(v -> startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(Ui.PRIVACY_URL))));
        c.addView(policy);
        LinearLayout btns = row(this);
        btns.setPaddingRelative(0, dp(this, 18), 0, 0);
        Button no = button(this, getString(R.string.ui_no_thanks), false);
        Button yes = button(this, getString(R.string.ui_agree_continue), true);
        btns.addView(no, new LinearLayout.LayoutParams(0, dp(this, 48), 1));
        LinearLayout.LayoutParams yp = new LinearLayout.LayoutParams(0, dp(this, 48), 1);
        yp.setMarginStart(dp(this, 10));
        btns.addView(yes, yp);
        c.addView(btns, full());
        Dialog d = bottomSheet(this, c);
        no.setOnClickListener(v -> {
            d.dismiss();
            if (draft != null) finish(); // the rule is saved; it starts working once access is on
        });
        yes.setOnClickListener(v -> {
            Prefs.setNoticeConsent(this);
            d.dismiss();
            open.run();
            if (draft != null) finish();
        });
        d.show();
    }

    // ---------------------------------------------------------------- helpers

    private interface Edited {
        void onEdit(String s);
    }

    private EditText field(String value, String hint, boolean multiLine, Edited l) {
        EditText e = new EditText(this);
        e.setText(value);
        e.setHint(hint);
        e.setHintTextColor(MUTED);
        e.setTextColor(TEXT);
        e.setTextSize(15);
        e.setTypeface(sans(this, 400));
        if (multiLine) {
            e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                    | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_AUTO_CORRECT);
            e.setMinLines(3);
            e.setMaxLines(8);
            e.setGravity(Gravity.TOP | Gravity.START);
        } else {
            e.setSingleLine(true);
            e.setInputType(InputType.TYPE_CLASS_TEXT);
        }
        e.setBackground(outlined(this, CARD, 12, OUTLINE, 1));
        e.setPaddingRelative(dp(this, 12), dp(this, 10), dp(this, 12), dp(this, 10));
        e.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int n) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int n) {
            }

            @Override
            public void afterTextChanged(Editable s) {
                l.onEdit(s.toString());
            }
        });
        return e;
    }

    /** Chips that wrap onto more lines when they don't fit on one. */
    private final class HorizontalChips {
        private final List<View> chips = new ArrayList<>();

        void add(View v) {
            chips.add(v);
        }

        View view() {
            LinearLayout box = column(RulesActivity.this);
            box.post(() -> {
                int width = box.getWidth(), used = 0;
                LinearLayout line = null;
                for (View c : chips) {
                    c.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
                    int w = c.getMeasuredWidth() + dp(RulesActivity.this, 8);
                    if (line == null || used + w > width) {
                        line = row(RulesActivity.this);
                        LinearLayout.LayoutParams lp = full();
                        if (box.getChildCount() > 0) lp.topMargin = dp(RulesActivity.this, 8);
                        box.addView(line, lp);
                        used = 0;
                    }
                    LinearLayout.LayoutParams cp = wrap();
                    cp.setMarginEnd(dp(RulesActivity.this, 8));
                    line.addView(c, cp);
                    used += w;
                }
            });
            return box;
        }
    }

    private View label(String s) {
        return title(this, s, 15);
    }

    private LinearLayout.LayoutParams gap(int dpv) {
        LinearLayout.LayoutParams p = full();
        p.topMargin = dp(this, dpv);
        return p;
    }
}
