package app.buddy.assistant;

import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.AutoCompleteTextView;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import static app.buddy.assistant.Ui.*;

/**
 * What Buddy lets the agent do, per app (Trust / Hard / Smart), the smart reviewer, and (page "rules") the
 * button rules: always ask / never ask, with the buttons the agent has tapped as suggestions.
 */
public class AppPermissionsActivity extends Screen {
    /** {mode, label} for the per-app segmented controls. */
    private String[][] modes() {
        return new String[][]{{"trust", getString(R.string.perms_mode_trust)}, {"hard", getString(R.string.perms_mode_hard)},
                {"smart", getString(R.string.perms_mode_smart)}};
    }

    private LinearLayout body;
    private boolean rulesPage;

    /** An installed app, loaded off the main thread (labels and icons are slow). */
    private static final class App {
        String pkg, label;
        Drawable icon;
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        rulesPage = "rules".equals(getIntent().getStringExtra("page"));
        LinearLayout root = column(this);
        root.setBackgroundColor(BG);
        LinearLayout top = row(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPaddingRelative(dp(this, 4), dp(this, 4), dp(this, 16), dp(this, 4));
        ImageButton back = iconButton(this, R.drawable.ms_arrow_back, getString(R.string.perms_back));
        back.setLayoutParams(new LinearLayout.LayoutParams(dp(this, 44), dp(this, 44)));
        back.setOnClickListener(v -> finish());
        top.addView(back);
        TextView t = title(this, getString(rulesPage ? R.string.perms_button_rules : R.string.perms_title), 18);
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
        setContentView(protect(frame)); // agents never change App permissions, even when they may tap Buddy's screens
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (rulesPage) renderRules();
        else renderApps();
    }

    // ------------------------------------------------------------------ apps

    private void renderApps() {
        body.removeAllViews();
        body.addView(text(this, getString(R.string.perms_modes_intro), 13, MUTED), full());

        body.addView(label(getString(R.string.perms_smart_reviewer)), gap(18));
        // a reviewer that isn't signed in is greyed out
        java.util.Set<String> noReviewer = new java.util.HashSet<>();
        for (String r : new String[]{"claude", "codex"}) if (!SetupState.signedIn(this, r)) noReviewer.add(r);
        body.addView(segmented(this, new String[][]{{"same", getString(R.string.perms_reviewer_same)}, {"claude", "Claude Code"}, {"codex", "Codex"}},
                Prefs.reviewer(this), false, v -> {
                    Prefs.setReviewer(this, v);
                    renderApps();
                }, noReviewer, v -> Toast.makeText(this, getString(R.string.perms_reviewer_sign_in, Account.nameOf(v)),
                        Toast.LENGTH_LONG).show()), gap(8));
        body.addView(text(this, getString(R.string.perms_reviewer_about), 12, MUTED), gap(6));
        body.addView(text(this, getString(R.string.perms_reviewer_rules), 12, MUTED), gap(4));
        // the reviewer's model on each harness that's signed in: quick by default
        for (final String h : new String[]{"claude", "codex"}) {
            if (!SetupState.signedIn(this, h)) continue;
            String m = Prefs.reviewerModel(this, h);
            LinearLayout r = row(this);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPaddingRelative(0, dp(this, 10), 0, dp(this, 10));
            r.setBackgroundResource(ripple(this, false));
            r.addView(title(this, getString(R.string.chat_model_title, Account.nameOf(h)), 15), weight1());
            r.addView(single(monoText(this, m.isEmpty() ? ("claude".equals(h) ? "Haiku" : getString(R.string.models_default))
                    : Models.label(h, m), 13, MUTED)));
            r.addView(icon(this, R.drawable.ms_chevron_right, MUTED, 20));
            r.setOnClickListener(v -> modelSheet(this, h, m, getString("claude".equals(h) ? R.string.reviewer_default_claude
                    : R.string.reviewer_default_codex), false, picked -> {
                Prefs.setReviewerModel(this, h, picked);
                renderApps();
            }));
            body.addView(r, gap(4));
        }

        LinearLayout rules = row(this);
        rules.setGravity(Gravity.CENTER_VERTICAL);
        rules.setPaddingRelative(0, dp(this, 12), 0, dp(this, 12));
        rules.setBackgroundResource(ripple(this, false));
        rules.addView(title(this, getString(R.string.perms_button_rules), 15), weight1());
        rules.addView(text(this, getString(R.string.perms_rules_summary, Guard.rules(this, "ask").size(), Guard.rules(this, "allow").size()), 13, MUTED));
        rules.addView(icon(this, R.drawable.ms_chevron_right, MUTED, 20));
        rules.setOnClickListener(v -> startActivity(new Intent(this, AppPermissionsActivity.class).putExtra("page", "rules")));
        body.addView(rules, gap(8));

        body.addView(label(getString(R.string.perms_apps)), gap(12));
        final LinearLayout list = column(this);
        body.addView(list, full());
        list.addView(spinner(this, 28), gap(16));
        new Thread(() -> {
            final List<App> apps = loadApps();
            runOnUiThread(() -> {
                if (isFinishing()) return;
                list.removeAllViews();
                fillApps(list, apps);
            });
        }, "app-permissions").start();
    }

    private List<App> loadApps() {
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
        return out;
    }

    private void fillApps(LinearLayout list, List<App> apps) {
        // all apps at once
        LinearLayout all = row(this);
        all.setGravity(Gravity.CENTER_VERTICAL);
        all.setPaddingRelative(0, dp(this, 6), 0, dp(this, 6));
        LinearLayout at = column(this);
        at.addView(title(this, getString(R.string.perms_all_apps), 15));
        TextView reset = text(this, getString(R.string.perms_reset_suggested), 12, ACCENT_TEXT);
        reset.setPaddingRelative(0, dp(this, 4), 0, dp(this, 4));
        reset.setOnClickListener(v -> {
            for (App a : apps) Prefs.setAppSafety(this, a.pkg, null);
            renderApps();
        });
        at.addView(reset);
        all.addView(at, weight1());
        all.addView(compact(modes(), commonMode(apps), m -> {
            for (App a : apps) Prefs.setAppSafety(this, a.pkg, m);
            renderApps();
        }));
        list.addView(all, full());
        View line = new View(this);
        line.setBackgroundColor(LINE);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(this, 1));
        lp.topMargin = lp.bottomMargin = dp(this, 6);
        list.addView(line, lp);

        // locked-down apps first, then trusted ones
        for (int pass = 0; pass < 2; pass++) {
            for (final App a : apps) {
                String[] mw = Guard.modeWhy(this, a.pkg, a.label);
                if ("trust".equals(mw[0]) == (pass == 0)) continue;
                list.addView(appRow(a), full());
            }
        }
    }

    private View appRow(final App a) {
        final LinearLayout r = row(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPaddingRelative(0, dp(this, 6), 0, dp(this, 6));
        ImageView ic = new ImageView(this);
        ic.setImageDrawable(a.icon);
        r.addView(ic, new LinearLayout.LayoutParams(dp(this, 32), dp(this, 32)));
        LinearLayout names = column(this);
        names.addView(single(text(this, a.label, 14, TEXT)));
        final TextView why = single(text(this, Guard.modeWhy(this, a.pkg, a.label)[1], 11, MUTED));
        names.addView(why);
        LinearLayout.LayoutParams np = weight1();
        np.leftMargin = np.rightMargin = dp(this, 10);
        r.addView(names, np);
        final FrameLayout holder = new FrameLayout(this);
        final Runnable[] paint = new Runnable[1];
        paint[0] = () -> {
            holder.removeAllViews();
            String[] mw = Guard.modeWhy(this, a.pkg, a.label);
            why.setText(mw[1]);
            holder.addView(compact(modes(), mw[0], m -> {
                Prefs.setAppSafety(this, a.pkg, m);
                paint[0].run();
            }));
        };
        paint[0].run();
        r.addView(holder);
        return r;
    }

    private String commonMode(List<App> apps) {
        String m = null;
        for (App a : apps) {
            String x = Prefs.appSafety(this, a.pkg);
            if (x == null) return "";
            if (m == null) m = x;
            else if (!m.equals(x)) return "";
        }
        return m == null ? "" : m;
    }

    /** A small one-line segmented control (fits next to an app name). */
    private LinearLayout compact(String[][] options, String selected, Pick l) {
        LinearLayout box = row(this);
        box.setPaddingRelative(dp(this, 2), dp(this, 2), dp(this, 2), dp(this, 2));
        box.setBackground(rounded(this, BG2(), 10));
        for (String[] o : options) {
            boolean on = o[0].equals(selected);
            TextView t = text(this, o[1], 12, on ? TEXT : MUTED);
            t.setSingleLine(true);
            t.setTypeface(sans(this, on ? 600 : 500));
            t.setGravity(Gravity.CENTER);
            t.setPaddingRelative(dp(this, 9), dp(this, 6), dp(this, 9), dp(this, 6));
            if (on) t.setBackground(rounded(this, CARD2, 8));
            else t.setBackgroundResource(ripple(this, false));
            t.setOnClickListener(v -> {
                if (!on) l.onPick(o[0]);
            });
            box.addView(t);
        }
        return box;
    }

    private static int BG2() {
        return CARD;
    }

    // ------------------------------------------------------------------ button rules

    private void renderRules() {
        body.removeAllViews();
        body.addView(text(this, getString(R.string.perms_rules_intro), 13, MUTED), full());
        body.addView(switchRow(this, getString(R.string.perms_builtin_title), getString(R.string.perms_builtin_sub),
                Prefs.guardBuiltin(this), on -> Prefs.setGuardBuiltin(this, on)), gap(8));
        List<Guard.Rule> seen = Guard.rules(this, "seen");
        for (String[] l : new String[][]{{"ask", getString(R.string.perms_always_ask)}, {"allow", getString(R.string.perms_never_ask)}}) {
            final String list = l[0];
            body.addView(label(l[1]), gap(18));
            for (final Guard.Rule r : Guard.rules(this, list)) {
                TextView chip = text(this, r.describe() + "   ✕", 14, TEXT);
                chip.setPaddingRelative(dp(this, 12), dp(this, 8), dp(this, 12), dp(this, 8));
                chip.setBackground(rounded(this, CARD2, 10));
                chip.setOnClickListener(v -> {
                    List<Guard.Rule> rs = Guard.rules(this, list);
                    rs.removeIf(x -> x.label.equals(r.label) && java.util.Objects.equals(x.pkg, r.pkg));
                    Guard.saveRules(this, list, rs);
                    renderRules();
                });
                body.addView(chip, gap(4));
            }
            // add: type a button name, with suggestions from the buttons seen before
            LinearLayout add = row(this);
            add.setGravity(Gravity.CENTER_VERTICAL);
            final AutoCompleteTextView et = new AutoCompleteTextView(this);
            et.setHint(R.string.perms_add_hint);
            et.setHintTextColor(MUTED);
            et.setTextColor(TEXT);
            et.setTextSize(14);
            et.setSingleLine(true);
            et.setThreshold(1);
            et.setBackground(outlined(this, CARD, 10, OUTLINE, 1));
            et.setPaddingRelative(dp(this, 12), dp(this, 10), dp(this, 12), dp(this, 10));
            final List<String> labels = new ArrayList<>();
            final java.util.Map<String, Guard.Rule> bySuggestion = new java.util.HashMap<>();
            for (Guard.Rule r : seen) {
                String k = r.describe();
                if (bySuggestion.containsKey(k)) continue;
                labels.add(k);
                bySuggestion.put(k, r);
            }
            et.setAdapter(new android.widget.ArrayAdapter<>(this, android.R.layout.simple_dropdown_item_1line, labels));
            final Guard.Rule[] picked = new Guard.Rule[1];
            et.setOnItemClickListener((parent, view, pos, id) -> picked[0] = bySuggestion.get(String.valueOf(parent.getItemAtPosition(pos))));
            add.addView(et, weight1());
            Button plus = button(this, getString(R.string.perms_add), true);
            LinearLayout.LayoutParams pl = wrap();
            pl.setMarginStart(dp(this, 8));
            add.addView(plus, pl);
            plus.setOnClickListener(v -> {
                String typed = et.getText().toString().trim();
                if (typed.isEmpty()) return;
                Guard.Rule r = picked[0] != null && picked[0].describe().equals(typed) ? picked[0] : new Guard.Rule(typed, null, null);
                Guard.addRule(this, list, r);
                renderRules();
            });
            body.addView(add, gap(6));
        }
        // buttons the agent tapped lately: one tap to put them on a list
        body.addView(label(getString(R.string.perms_seen_title)), gap(20));
        if (seen.isEmpty()) {
            body.addView(text(this, getString(R.string.perms_seen_empty), 13, MUTED), gap(4));
            return;
        }
        int n = 0;
        for (final Guard.Rule r : seen) {
            if (n++ >= 40) break;
            String now = Guard.listed(this, "allow", r.label, r.pkg) ? "allow" : Guard.listed(this, "ask", r.label, r.pkg) ? "ask" : "";
            LinearLayout row = row(this);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPaddingRelative(0, dp(this, 5), 0, dp(this, 5));
            LinearLayout names = column(this);
            names.addView(single(text(this, r.label, 14, TEXT)));
            names.addView(single(text(this, r.app == null || r.app.isEmpty() ? r.pkg : r.app, 11, MUTED)));
            LinearLayout.LayoutParams np = weight1();
            np.setMarginEnd(dp(this, 10));
            row.addView(names, np);
            row.addView(compact(new String[][]{{"allow", getString(R.string.perms_never_ask)}, {"ask", getString(R.string.perms_ask)}}, now, m -> {
                Guard.addRule(this, m, r);
                renderRules();
            }));
            body.addView(row, full());
        }
    }

    // ------------------------------------------------------------------ helpers

    private View label(String s) {
        return title(this, s, 15);
    }

    private LinearLayout.LayoutParams gap(int dpv) {
        LinearLayout.LayoutParams p = full();
        p.topMargin = dp(this, dpv);
        return p;
    }
}
