package app.buddy.assistant;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.HashMap;
import java.util.Map;

/** Shared look for the app screens: theme colours (set by {@link Theme}), type, and building blocks. */
final class Ui {
    // Palette: filled by Theme.load() from the appearance settings (dark/light, accent, surface).
    static int BG = 0xFF121110, CARD = 0xFF1D1B19, CARD2 = 0xFF2A2724, TEXT = 0xFFF3EFE8, MUTED = 0xFFA39D93,
            TEXT2 = 0xFFC9C3B9,       // secondary text: previews, body copy under titles
            ACCENT = 0xFFD97757, OK = 0xFF5CC08A, USER = 0xFF4A3A2E, ERR = 0xFFE58A7A,
            ON_ACCENT = 0xFF1A1714,   // text on ACCENT
            ACCENT_TEXT = 0xFFD97757, // accent used as text on BG/CARD (darker in light mode)
            NAV = 0xFF24221F,         // the floating bottom nav pill
            CODE = 0xFF0F0E0D,        // terminal/code block background
            CODE_TEXT = 0xFFF4EFE6,
            LINE = 0x0FF4EFE6,        // dividers
            OUTLINE = 0x1FF4EFE6,     // chip/input borders
            ACCENT_SOFT = 0x29D97757,
            ERR_SOFT = 0x1FE58A7A;
    static boolean DARK = true;

    private static final Map<String, Typeface> FONTS = new HashMap<>();

    private Ui() {
    }

    // ------------------------------------------------------------------ basics

    static int dp(Context c, int v) {
        return Math.round(v * c.getResources().getDisplayMetrics().density);
    }

    /** The app's language, for changing the case of text the user sees (Turkish i/İ). */
    static java.util.Locale locale(Context c) {
        return c.getResources().getConfiguration().getLocales().get(0);
    }

    /** Geist at a weight (400…700); falls back to the system font. */
    static Typeface sans(Context c, int weight) {
        return font(c, "fonts/Geist.ttf", weight, weight >= 600 ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
    }

    /** Geist Mono at a weight; falls back to the system monospace. */
    static Typeface mono(Context c, int weight) {
        return font(c, "fonts/GeistMono.ttf", weight, Typeface.MONOSPACE);
    }

    private static Typeface font(Context c, String path, int weight, Typeface fallback) {
        String k = path + weight;
        Typeface t = FONTS.get(k);
        if (t == null) {
            try {
                t = new Typeface.Builder(c.getAssets(), path).setFontVariationSettings("'wght' " + weight).build();
            } catch (Exception e) {
                t = fallback;
            }
            if (t == null) t = fallback;
            FONTS.put(k, t);
        }
        return t;
    }

    static TextView text(Context c, String s, int sp, int color) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(sans(c, 400));
        return t;
    }

    static TextView title(Context c, String s, int sp) {
        TextView t = text(c, s, sp, TEXT);
        t.setTypeface(sans(c, 600));
        return t;
    }

    static TextView monoText(Context c, String s, int sp, int color) {
        TextView t = text(c, s, sp, color);
        t.setTypeface(mono(c, 400));
        return t;
    }

    /** The quiet "free · limited" tag beside an agent's name in the pickers (Account.freeTier). */
    static TextView freeTag(Context c) {
        TextView tag = monoText(c, c.getString(R.string.agent_tag_free), 11, MUTED);
        tag.setPaddingRelative(dp(c, 6), dp(c, 1), dp(c, 6), dp(c, 1));
        tag.setBackground(rounded(c, CARD2, 6));
        return tag;
    }

    static TextView single(TextView t) {
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    static GradientDrawable rounded(Context c, int color, int radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(c, radiusDp));
        return d;
    }

    static GradientDrawable outlined(Context c, int fill, int radiusDp, int stroke, int strokeDp) {
        GradientDrawable d = rounded(c, fill, radiusDp);
        d.setStroke(dp(c, strokeDp), stroke);
        return d;
    }

    /** Per-corner radii in dp: top-left, top-right, bottom-right, bottom-left. */
    static GradientDrawable corners(Context c, int color, int tl, int tr, int br, int bl) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        float a = dp(c, tl), b = dp(c, tr), e = dp(c, br), f = dp(c, bl);
        d.setCornerRadii(new float[]{a, a, b, b, e, e, f, f});
        return d;
    }

    static GradientDrawable oval(int color) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        return d;
    }

    static ImageView icon(Context c, int res, int color, int sizeDp) {
        ImageView i = new ImageView(c);
        i.setImageResource(res);
        i.setImageTintList(ColorStateList.valueOf(color));
        i.setLayoutParams(new LinearLayout.LayoutParams(dp(c, sizeDp), dp(c, sizeDp)));
        return i;
    }

    static View dot(Context c, int color, int sizeDp) {
        View v = new View(c);
        v.setBackground(oval(color));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(c, sizeDp), dp(c, sizeDp));
        p.gravity = Gravity.CENTER_VERTICAL;
        v.setLayoutParams(p);
        return v;
    }

    static int ripple(Context c, boolean borderless) {
        TypedValue tv = new TypedValue();
        c.getTheme().resolveAttribute(borderless ? android.R.attr.selectableItemBackgroundBorderless
                : android.R.attr.selectableItemBackground, tv, true);
        return tv.resourceId;
    }

    /** A transparent icon button with a round ripple; 48dp touch target. */
    static ImageButton iconButton(Context c, int icon, String description) {
        return iconButton(c, icon, description, TEXT);
    }

    static ImageButton iconButton(Context c, int icon, String description, int tint) {
        ImageButton b = new ImageButton(c);
        b.setImageResource(icon);
        b.setImageTintList(ColorStateList.valueOf(tint));
        b.setContentDescription(description);
        b.setScaleType(ImageView.ScaleType.CENTER);
        b.setBackgroundResource(ripple(c, true));
        b.setLayoutParams(new LinearLayout.LayoutParams(dp(c, 48), dp(c, 48)));
        return b;
    }

    static Button button(Context c, String label, boolean primary) {
        Button b = new Button(c);
        b.setText(label);
        b.setAllCaps(false);
        b.setSingleLine(true);
        b.setTextColor(primary ? ON_ACCENT : TEXT);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        b.setTypeface(sans(c, 600));
        b.setPaddingRelative(dp(c, 16), dp(c, 8), dp(c, 16), dp(c, 8));
        b.setMinHeight(dp(c, 44));
        b.setMinimumHeight(dp(c, 44));
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setStateListAnimator(null);
        b.setBackground(rounded(c, primary ? ACCENT : CARD2, 14));
        return b;
    }

    static Button outlinedButton(Context c, String label, int color) {
        Button b = button(c, label, false);
        b.setTextColor(color);
        b.setBackground(outlined(c, 0x00000000, 14, Theme.withAlpha(color, 0x80), 1));
        return b;
    }

    static LinearLayout.LayoutParams full() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    static LinearLayout.LayoutParams wrap() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    static LinearLayout.LayoutParams weight1() {
        return new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
    }

    static LinearLayout row(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.HORIZONTAL);
        l.setGravity(Gravity.CENTER_VERTICAL);
        return l;
    }

    static LinearLayout column(Context c) {
        LinearLayout l = new LinearLayout(c);
        l.setOrientation(LinearLayout.VERTICAL);
        return l;
    }

    // ------------------------------------------------------------- components

    /** 13sp/600 muted sentence-case label above a group of cards; padding 20/20/20/8. */
    static TextView sectionLabel(Context c, String s) {
        TextView t = text(c, s, 13, MUTED);
        t.setTypeface(sans(c, 600));
        t.setPaddingRelative(dp(c, 20), dp(c, 20), dp(c, 20), dp(c, 8));
        return t;
    }

    /** A screen's large title (34sp, tight), as on Chats and Settings. */
    static TextView largeTitle(Context c, String s) {
        TextView t = text(c, s, 34, TEXT);
        t.setTypeface(sans(c, 600));
        t.setLetterSpacing(-0.02f);
        t.setPaddingRelative(dp(c, 20), dp(c, 12), dp(c, 20), dp(c, 4));
        return t;
    }

    /**
     * A rounded CARD group holding rows, with a hairline between them (an inset list). Rows keep their own padding
     * and ripple; the group clips them to its corners.
     */
    static LinearLayout group(Context c) {
        LinearLayout g = column(c);
        g.setBackground(rounded(c, CARD, 20));
        g.setClipToOutline(true);
        return g;
    }

    /** Adds a row to a {@link #group(Context)}, with a divider above every row but the first. */
    static void addToGroup(Context c, LinearLayout group, View row) {
        addToGroup(c, group, row, 68); // a chat row: under the text, not the 40dp tile
    }

    /** As above, the divider starting textStartDp in: where the row's text begins. */
    static void addToGroup(Context c, LinearLayout group, View row, int textStartDp) {
        if (group.getChildCount() > 0) {
            View line = new View(c);
            line.setBackgroundColor(LINE);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1);
            lp.setMarginStart(dp(c, textStartDp));
            group.addView(line, lp);
        }
        group.addView(row, full());
    }

    /** Layout params for a card or group inset 16dp from the screen's sides. */
    static LinearLayout.LayoutParams inset(Context c) {
        LinearLayout.LayoutParams p = full();
        p.leftMargin = p.rightMargin = dp(c, 16);
        return p;
    }

    /** Each agent's own color (as text on BG/CARD): Claude Code clay, Codex blue, OpenCode green, Antigravity violet. */
    static int agentColor(String backend) {
        if ("codex".equals(backend)) return DARK ? 0xFF9DB3FF : 0xFF3C5FCC;
        if ("opencode".equals(backend)) return DARK ? 0xFF7FD6A7 : 0xFF23855A;
        if ("agy".equals(backend)) return DARK ? 0xFFBFA9FF : 0xFF6A4FD0;
        if ("grok".equals(backend)) return DARK ? 0xFFD7D3CC : 0xFF5A554E;
        return DARK ? 0xFFE89A7C : 0xFFB4532F;
    }

    /** 40×40 tile with "CC" (Claude Code), "CX" (Codex), "OC" (OpenCode), "AG" (Antigravity) or "GK" (Grok), in the agent's color. */
    static TextView agentTile(Context c, String backend) {
        TextView t = text(c, "codex".equals(backend) ? "CX" : "opencode".equals(backend) ? "OC"
                : "agy".equals(backend) ? "AG" : "grok".equals(backend) ? "GK" : "CC", 13, agentColor(backend));
        t.setTypeface(sans(c, 700));
        t.setGravity(Gravity.CENTER);
        t.setBackground(rounded(c, Theme.withAlpha(agentColor(backend), 0x29), 14));
        t.setLayoutParams(new LinearLayout.LayoutParams(dp(c, 40), dp(c, 40)));
        return t;
    }

    /** Outlined mono chip with a trailing expand icon. */
    static LinearLayout chip(Context c, String s) {
        LinearLayout l = row(c);
        l.setPaddingRelative(dp(c, 10), dp(c, 4), dp(c, 6), dp(c, 4));
        l.setBackground(rounded(c, CARD, 14));
        TextView t = single(monoText(c, s, 12, TEXT2));
        l.addView(t, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1)); // a chip short of room shortens its text, not the icon
        ImageView i = icon(c, R.drawable.ms_expand_more, MUTED, 16);
        l.addView(i);
        return l;
    }

    /** The "Linux tools" chip beside a chat's model chip: a terminal icon and the name (see paintLinuxChip). */
    static LinearLayout linuxChip(Context c) {
        LinearLayout l = row(c);
        l.setPaddingRelative(dp(c, 6), dp(c, 3), dp(c, 8), dp(c, 3));
        l.addView(icon(c, R.drawable.ms_terminal, MUTED, 14));
        TextView t = single(monoText(c, c.getString(R.string.chat_linux_tools), 12, MUTED));
        t.setPaddingRelative(dp(c, 4), 0, 0, 0);
        l.addView(t);
        return l;
    }

    /** On: accent, like the agent pickers' "default" tag; off: outlined and muted, like the model chip. */
    static void paintLinuxChip(Context c, LinearLayout chip, boolean on) {
        int color = on ? ACCENT_TEXT : MUTED;
        ((ImageView) chip.getChildAt(0)).setImageTintList(ColorStateList.valueOf(color));
        ((TextView) chip.getChildAt(1)).setTextColor(color);
        chip.setBackground(rounded(c, on ? ACCENT_SOFT : CARD, 14));
        chip.setSelected(on); // for TalkBack: on reads as selected
    }

    /** A red note with a warning icon (what could go wrong with a setting). */
    static LinearLayout warning(Context c, String s) {
        LinearLayout w = row(c);
        w.setGravity(Gravity.TOP);
        w.setPaddingRelative(dp(c, 12), dp(c, 10), dp(c, 12), dp(c, 10));
        w.setBackground(rounded(c, ERR_SOFT, 14));
        w.addView(icon(c, R.drawable.ms_warning, ERR, 18));
        TextView t = text(c, s, 13, ERR);
        t.setPaddingRelative(dp(c, 10), 0, 0, 0);
        w.addView(t, weight1());
        return w;
    }

    /** A plain flat row with a ripple (for list rows). */
    static LinearLayout listRow(Context c) {
        LinearLayout r = row(c);
        r.setPaddingRelative(dp(c, 20), dp(c, 12), dp(c, 20), dp(c, 12));
        r.setBackgroundResource(ripple(c, false));
        r.setGravity(Gravity.TOP);
        return r;
    }

    interface Toggle {
        void onToggle(boolean on);
    }

    /** Title + subtitle with a switch on the right. */
    /** Accessibility extra that marks a view (and everything inside it) as off-limits to agents. */
    static final String PROTECTED = "app.buddy.protected";

    /**
     * Agents may never tap or type in this view, even when "Let agents tap Buddy's screens" is on (see Guard).
     * Invisible to the user and to screen readers: it's only an extra on the accessibility node.
     */
    static <T extends View> T protect(T v) {
        v.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override
            public void onInitializeAccessibilityNodeInfo(View host, android.view.accessibility.AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.getExtras().putBoolean(PROTECTED, true);
            }
        });
        return v;
    }

    static LinearLayout switchRow(Context c, String title, String sub, boolean on, Toggle l) {
        LinearLayout r = row(c);
        r.setPaddingRelative(dp(c, 16), dp(c, 14), dp(c, 16), dp(c, 14));
        r.setBackgroundResource(ripple(c, false));
        LinearLayout texts = column(c);
        texts.addView(title(c, title, 15));
        if (sub != null && !sub.isEmpty()) {
            TextView s = text(c, sub, 13, MUTED);
            s.setPaddingRelative(0, dp(c, 2), dp(c, 12), 0);
            texts.addView(s);
        }
        r.addView(texts, weight1());
        final FrameLayout track = new FrameLayout(c);
        final View knob = new View(c);
        final boolean[] state = {on};
        Runnable paint = () -> {
            track.setBackground(rounded(c, state[0] ? ACCENT : CARD2, 14));
            knob.setBackground(oval(state[0] ? ON_ACCENT : MUTED));
            FrameLayout.LayoutParams kp = new FrameLayout.LayoutParams(dp(c, 20), dp(c, 20),
                    Gravity.CENTER_VERTICAL | (state[0] ? Gravity.END : Gravity.START));
            kp.leftMargin = kp.rightMargin = dp(c, 4);
            knob.setLayoutParams(kp);
        };
        track.addView(knob);
        paint.run();
        r.addView(track, new LinearLayout.LayoutParams(dp(c, 48), dp(c, 28)));
        r.setOnClickListener(v -> {
            state[0] = !state[0];
            paint.run();
            l.onToggle(state[0]);
        });
        return r;
    }

    interface Pick {
        void onPick(String value);
    }

    /** Segmented control: options are {value, label}. */
    static LinearLayout segmented(Context c, String[][] options, String selected, boolean monoLabels, Pick l) {
        return segmented(c, options, selected, monoLabels, l, null, null);
    }

    /**
     * Like {@link #segmented(Context, String[][], String, boolean, Pick)}, but the options in {@code disabled} are
     * dimmed and can't be picked: tapping one calls {@code whyNot} (e.g. to say what to set up first).
     */
    static LinearLayout segmented(Context c, String[][] options, String selected, boolean monoLabels, Pick l,
                                  java.util.Set<String> disabled, Pick whyNot) {
        LinearLayout box = row(c);
        box.setPaddingRelative(dp(c, 3), dp(c, 3), dp(c, 3), dp(c, 3));
        box.setBackground(rounded(c, BG, 14));
        for (String[] o : options) {
            boolean on = o[0].equals(selected);
            TextView t = text(c, o[1], 14, on ? TEXT : MUTED);
            t.setTypeface(monoLabels ? mono(c, on ? 600 : 400) : sans(c, on ? 600 : 500));
            t.setGravity(Gravity.CENTER);
            int side = dp(c, options.length > 4 ? 4 : 10);
            t.setPaddingRelative(side, dp(c, 8), side, dp(c, 8));
            // one line; a label too long for its segment (often a translation) shrinks instead of wrapping
            t.setMaxLines(1);
            t.setAutoSizeTextTypeUniformWithConfiguration(10, 14, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
            if (on) t.setBackground(rounded(c, CARD2, 11));
            else t.setBackgroundResource(ripple(c, false));
            boolean off = !on && disabled != null && disabled.contains(o[0]);
            if (off) t.setAlpha(0.38f);
            t.setOnClickListener(v -> {
                if (off) {
                    if (whyNot != null) whyNot.onPick(o[0]);
                } else if (!on) l.onPick(o[0]);
            });
            box.addView(t, weight1());
        }
        return box;
    }

    /** A radio button's look (for lists of choices in sheets). */
    static View radio(Context c, boolean on) {
        FrameLayout f = new FrameLayout(c);
        f.setBackground(outlined(c, 0x00000000, 11, on ? ACCENT : MUTED, 2));
        if (on) {
            View in = new View(c);
            in.setBackground(oval(ACCENT));
            f.addView(in, new FrameLayout.LayoutParams(dp(c, 10), dp(c, 10), Gravity.CENTER));
        }
        f.setLayoutParams(new LinearLayout.LayoutParams(dp(c, 22), dp(c, 22)));
        return f;
    }

    /**
     * Settings → Chats on Default (issue #20): for each agent that's signed in, what a chat uses while its model is
     * "Default": the agent's own default, one of its models, or an API key service's.
     */
    static void defaultsSheet(Activity a) {
        LinearLayout c = column(a);
        c.addView(title(a, a.getString(R.string.api_keys_defaults), 18));
        TextView info = text(a, a.getString(R.string.api_keys_defaults_info), 13, MUTED);
        info.setPaddingRelative(0, dp(a, 4), 0, dp(a, 8));
        c.addView(info);
        final Dialog[] d = new Dialog[1];
        for (Account acc : Account.ALL) {
            final String agent = acc.agent;
            if (!SetupState.signedIn(a, agent)) continue;
            LinearLayout r = row(a);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPaddingRelative(0, dp(a, 8), 0, dp(a, 8));
            r.setBackgroundResource(ripple(a, false));
            r.addView(agentTile(a, agent));
            LinearLayout t = column(a);
            t.setPaddingRelative(dp(a, 12), 0, dp(a, 8), 0);
            t.addView(title(a, Account.nameOf(agent), 15));
            String now = Models.agentDefault(agent);
            t.addView(single(monoText(a, now.isEmpty() ? a.getString(R.string.api_keys_own_default) : Models.label(agent, now), 12, MUTED)));
            r.addView(t, weight1());
            r.addView(icon(a, R.drawable.ms_chevron_right, MUTED, 22));
            r.setOnClickListener(v -> {
                d[0].dismiss();
                pickDefault(a, agent, () -> defaultsSheet(a));
            });
            c.addView(r, full());
        }
        d[0] = bottomSheet(a, c);
        d[0].show();
    }

    /** The model sheet, for an agent's default: "" is the agent's own default. after: once one is picked. */
    static void pickDefault(Activity a, String agent, Runnable after) {
        modelSheet(a, agent, Models.agentDefault(agent), Models.defaultHint(agent), pick -> {
            Prefs.setAgentDefault(a, agent, pick);
            ChatHub.get(a).changed();
            if (after != null) after.run();
        });
    }

    /**
     * Pick a model for an agent: its list ("" first, described by defaultHint) or any model name typed in. The pick goes
     * to l; nothing happens if the sheet is closed.
     */
    static void modelSheet(Activity a, String backend, String current, String defaultHint, Pick l) {
        modelSheet(a, backend, current, defaultHint, true, l);
    }

    /** services: also the API key services' models (not for runs outside a chat, like the reviewer's). */
    static void modelSheet(Activity a, String backend, String current, String defaultHint, boolean services, Pick l) {
        final Dialog[] d = new Dialog[1];
        Models.refresh(a, () -> { // a service's list changed meanwhile: the sheet again, with it
            if (d[0] == null || !d[0].isShowing() || a.isFinishing()) return;
            d[0].dismiss();
            modelSheet(a, backend, current, defaultHint, services, l);
        });
        LinearLayout c = column(a);
        TextView h = title(a, a.getString(R.string.chat_model_title, Account.nameOf(backend)), 18);
        h.setPaddingRelative(0, 0, 0, dp(a, 8));
        c.addView(h);
        boolean known = false;
        for (final String[] o : Models.options(backend, services)) {
            if (o[0] == null) { // an API key service's models
                TextView sl = sectionLabel(a, o[1]);
                sl.setPaddingRelative(0, dp(a, 14), 0, dp(a, 2));
                c.addView(sl);
                continue;
            }
            boolean on = o[0].equals(current);
            known |= on;
            LinearLayout r = row(a);
            r.setPaddingRelative(dp(a, 4), dp(a, 10), dp(a, 4), dp(a, 10));
            r.setBackgroundResource(ripple(a, false));
            r.addView(radio(a, on));
            LinearLayout t = column(a);
            t.setPaddingRelative(dp(a, 14), 0, 0, 0);
            t.addView(title(a, o[1], 15));
            t.addView(single(monoText(a, Models.detail(backend, o[0], defaultHint), 12, MUTED)));
            r.addView(t, weight1());
            r.setOnClickListener(v -> {
                d[0].dismiss();
                l.onPick(o[0]);
            });
            c.addView(r, full());
        }
        LinearLayout other = row(a);
        other.setPaddingRelative(0, dp(a, 10), 0, 0);
        final android.widget.EditText et = new android.widget.EditText(a);
        et.setHint(a.getString(R.string.chat_model_other_hint, Models.example(backend)));
        et.setHintTextColor(MUTED);
        et.setTextColor(TEXT);
        et.setTextSize(14);
        et.setTypeface(mono(a, 400));
        et.setSingleLine(true);
        if (!known) et.setText(current);
        et.setPaddingRelative(dp(a, 12), dp(a, 10), dp(a, 12), dp(a, 10));
        et.setBackground(outlined(a, BG, 12, OUTLINE, 1));
        other.addView(et, weight1());
        Button use = button(a, a.getString(R.string.chat_model_use), true);
        LinearLayout.LayoutParams up = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(a, 44));
        up.setMarginStart(dp(a, 8));
        other.addView(use, up);
        use.setOnClickListener(v -> {
            d[0].dismiss();
            l.onPick(et.getText().toString().trim());
        });
        c.addView(other, full());
        d[0] = bottomSheet(a, c);
        d[0].show();
    }

    /** Terminal-style block with an accent "$ " prompt. */
    static TextView codeBlock(Context c, String cmd) {
        TextView t = monoText(c, "", 12, CODE_TEXT);
        android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder("$ " + cmd);
        sb.setSpan(new android.text.style.ForegroundColorSpan(ACCENT), 0, 2, 0);
        t.setText(sb);
        t.setLineSpacing(0, 1.4f);
        t.setPaddingRelative(dp(c, 12), dp(c, 12), dp(c, 12), dp(c, 12));
        t.setBackground(rounded(c, CODE, 12));
        return t;
    }

    static ProgressBar thinBar(Context c) {
        ProgressBar p = new ProgressBar(c, null, android.R.attr.progressBarStyleHorizontal);
        p.setIndeterminate(true);
        p.setIndeterminateTintList(ColorStateList.valueOf(ACCENT));
        p.setLayoutParams(new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(c, 2)));
        return p;
    }

    /**
     * Make web links and email addresses in t tappable, opened with a new task (for the bubble, which isn't an
     * activity). Then runs before each one opens (e.g. to fold the panel).
     */
    static void linkifyForService(TextView t, Runnable then) {
        android.text.SpannableString s = new android.text.SpannableString(t.getText());
        boolean found = android.text.util.Linkify.addLinks(s, android.text.util.Linkify.WEB_URLS | android.text.util.Linkify.EMAIL_ADDRESSES);
        for (android.text.style.URLSpan u : s.getSpans(0, s.length(), android.text.style.URLSpan.class)) {
            int a = s.getSpanStart(u), b = s.getSpanEnd(u);
            s.removeSpan(u);
            s.setSpan(new LinkSpan(u.getURL(), then), a, b, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        // markdown links (see markdown) are LinkSpans already
        if (!found && s.getSpans(0, s.length(), LinkSpan.class).length == 0) return;
        t.setText(s);
        t.setLinkTextColor(ACCENT_TEXT);
        t.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
    }

    /** A markdown link, [text](https://…); one level of parentheses in the address too (Wikipedia's have them). */
    private static final java.util.regex.Pattern MD_LINK = java.util.regex.Pattern.compile(
            "\\[([^\\[\\]\\n]+)\\]\\((https?://[^\\s()]*(?:\\([^\\s()]*\\)[^\\s()]*)*)\\)");

    /** The same in plain text, for one-line previews: [text](url) becomes text, **bold** bold, and so on. */
    /**
     * What Buddy downloads, with F-Droid's notice in its build: downloaded programs (Buddy's Linux, the agents, the
     * browser) come from their makers, not from F-Droid, and F-Droid asks that users know this before they agree.
     */
    static String downloadNote(Context c, String s) {
        return Flavor.FDROID ? s + "\n" + c.getString(R.string.fdroid_download_note) : s;
    }

    static String linkText(String text) {
        return markdown(text, null, true).toString();
    }

    private static final java.util.regex.Pattern MD_INLINE = java.util.regex.Pattern.compile(
            "`([^`\\n]+)`" // 1: code
                    + "|!\\[([^\\[\\]\\n]*)\\]\\(([^\\s()]+)\\)" // 2, 3: an image (shown under the reply, see ChatHub.pictures)
                    + "|" + MD_LINK.pattern() // 4, 5: a link
                    + "|\\*\\*(?!\\s)(.+?)\\*\\*|__(?!\\s)(.+?)__" // 6, 7: bold
                    + "|~~(.+?)~~" // 8: struck through
                    + "|(?<![\\w*])\\*(?![\\s*])(.+?)(?<!\\s)\\*(?![\\w*])" // 9: italic; not a * b * c
                    + "|(?<![\\w_])_(?![\\s_])(.+?)(?<!\\s)_(?![\\w_])"); // 10: italic; not snake_case
    private static final java.util.regex.Pattern MD_HEADING = java.util.regex.Pattern.compile("#{1,6}\\s+(.*?)\\s*#*\\s*"),
            MD_BULLET = java.util.regex.Pattern.compile("(\\s*)[-*+]\\s+(.*)"),
            MD_NUMBER = java.util.regex.Pattern.compile("(\\s*)(\\d{1,3}[.)])\\s+(.*)"),
            MD_RULE = java.util.regex.Pattern.compile("\\s*([-*_])(\\s*\\1){2,}\\s*"),
            MD_TABLE_LINE = java.util.regex.Pattern.compile("\\s*\\|?\\s*:?-+:?\\s*(\\|\\s*:?-+:?\\s*)*\\|?\\s*");

    /**
     * An agent's reply in Markdown, as formatted text: headings, bold, italic, struck through, `code` and code blocks,
     * lists, quotes, rules, tables (monospaced) and links, [text](url): the text, opening the address when tapped
     * (bare addresses are left to Linkify). Then runs before a link opens (e.g. to fold the panel).
     */
    static CharSequence markdown(String text, Runnable then) {
        return markdown(text, then, false);
    }

    private static CharSequence markdown(String text, Runnable then, boolean plain) {
        android.text.SpannableStringBuilder out = new android.text.SpannableStringBuilder();
        String[] lines = text.split("\n", -1);
        int code = -1; // where the open code block started
        for (int i = 0; i < lines.length; i++) {
            String l = lines[i];
            int start = out.length();
            java.util.regex.Matcher m;
            if (l.trim().startsWith("```")) {
                if (code < 0) {
                    code = start;
                    continue;
                }
                if (out.length() > code && out.charAt(out.length() - 1) == '\n') out.delete(out.length() - 1, out.length());
                monospace(out, code, out.length());
                code = -1;
            } else if (code >= 0) {
                out.append(l);
            } else if ((m = MD_HEADING.matcher(l)).matches()) {
                inline(out, m.group(1), then);
                int level = l.indexOf(' ');
                set(out, new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), start);
                set(out, new android.text.style.RelativeSizeSpan(level == 1 ? 1.25f : level == 2 ? 1.15f : 1.05f), start);
            } else if (MD_RULE.matcher(l).matches()) {
                out.append("――――――");
                set(out, new android.text.style.ForegroundColorSpan(MUTED), start);
            } else if ((m = MD_BULLET.matcher(l)).matches()) {
                indent(out, m.group(1));
                out.append("•  ");
                inline(out, m.group(2), then);
            } else if ((m = MD_NUMBER.matcher(l)).matches()) {
                indent(out, m.group(1));
                out.append(m.group(2)).append(' ');
                inline(out, m.group(3), then);
            } else if (l.startsWith(">")) {
                inline(out, l.replaceFirst(">\\s?", ""), then);
                set(out, new android.text.style.QuoteSpan(MUTED), start);
            } else if (tableLine(l)) {
                int j = i;
                while (j + 1 < lines.length && tableLine(lines[j + 1])) j++;
                table(out, lines, i, j, then, plain);
                i = j;
            } else {
                inline(out, l, then);
            }
            if (i < lines.length - 1) out.append('\n');
        }
        if (code >= 0) monospace(out, code, out.length()); // never closed
        return out;
    }

    /**
     * A reply split at its tables, in order: {"text", Markdown} and {"table", its lines}. A table is a heading line, a
     * |---|---| line under it, and rows; lines inside a fenced code block are never one.
     */
    static java.util.List<String[]> tableParts(String text) {
        java.util.List<String[]> parts = new java.util.ArrayList<>();
        StringBuilder cur = new StringBuilder();
        String[] lines = text.split("\n", -1);
        boolean code = false;
        for (int i = 0; i < lines.length; i++) {
            String l = lines[i];
            if (l.trim().startsWith("```")) code = !code;
            if (!code && tableLine(l) && i + 1 < lines.length && tableLine(lines[i + 1])
                    && MD_TABLE_LINE.matcher(lines[i + 1]).matches()) {
                int j = i + 1;
                while (j + 1 < lines.length && tableLine(lines[j + 1])) j++;
                textPart(parts, cur);
                StringBuilder t = new StringBuilder();
                for (int k = i; k <= j; k++) t.append(k > i ? "\n" : "").append(lines[k].trim());
                parts.add(new String[]{"table", t.toString()});
                i = j;
                continue;
            }
            cur.append(l).append('\n');
        }
        textPart(parts, cur);
        if (parts.isEmpty()) parts.add(new String[]{"text", text});
        return parts;
    }

    private static void textPart(java.util.List<String[]> parts, StringBuilder cur) {
        String t = cur.toString().replaceAll("^(\\s*\n)+", "").replaceAll("\\s+$", "");
        cur.setLength(0);
        if (!t.isEmpty()) parts.add(new String[]{"text", t});
    }

    private static String[] tableCells(String line) {
        String t = line.trim();
        String[] c = t.substring(1, t.length() - 1).split("(?<!\\\\)\\|", -1);
        for (int i = 0; i < c.length; i++) c[i] = c[i].trim().replace("\\|", "|").replaceAll("(?i)\\s*<br\\s*/?>\\s*", "\n");
        return c;
    }

    /**
     * A table of a reply (one "table" part of tableParts) as a real one: a heading row, lines between the cells, each
     * column as wide as its longest cell (a long cell wraps), and a sideways scroll when it is wider than the screen.
     * Each cell's text is given to "each" for what the caller does with a reply's text (links, copying).
     */
    static View tableView(Context c, String table, Runnable then, java.util.function.Consumer<TextView> each) {
        String[] lines = table.split("\n");
        String[] align = tableCells(lines[1]);
        int cols = 0;
        for (int i = 0; i < lines.length; i++) if (i != 1) cols = Math.max(cols, tableCells(lines[i]).length);
        android.widget.TableLayout t = new android.widget.TableLayout(c);
        t.setBackground(rounded(c, OUTLINE, 10)); // shows between the cells and around them as the lines
        t.setClipToOutline(true);
        int line = Math.max(1, dp(c, 1));
        t.setPadding(line, line, 0, 0);
        for (int i = 0; i < lines.length; i++) {
            if (i == 1) continue; // |---|---|
            String[] cells = tableCells(lines[i]);
            android.widget.TableRow row = new android.widget.TableRow(c);
            for (int k = 0; k < cols; k++) {
                TextView tv = text(c, "", 14, TEXT);
                tv.setText(inlineMarkdown(k < cells.length ? cells[k] : "", then));
                if (i == 0) tv.setTypeface(sans(c, 600));
                tv.setBackgroundColor(i == 0 ? CARD2 : BG);
                tv.setPaddingRelative(dp(c, 10), dp(c, 7), dp(c, 10), dp(c, 7));
                tv.setMaxWidth(dp(c, 240));
                String a = k < align.length ? align[k] : "";
                tv.setGravity((a.startsWith(":") && a.endsWith(":") ? Gravity.CENTER_HORIZONTAL
                        : a.endsWith(":") ? Gravity.END : Gravity.START) | Gravity.CENTER_VERTICAL);
                if (each != null) each.accept(tv);
                android.widget.TableRow.LayoutParams lp = new android.widget.TableRow.LayoutParams(
                        android.widget.TableRow.LayoutParams.WRAP_CONTENT, android.widget.TableRow.LayoutParams.MATCH_PARENT);
                lp.setMargins(0, 0, line, line);
                row.addView(tv, lp);
            }
            t.addView(row);
        }
        android.widget.HorizontalScrollView hs = new android.widget.HorizontalScrollView(c);
        hs.setOverScrollMode(View.OVER_SCROLL_NEVER);
        hs.setHorizontalFadingEdgeEnabled(true); // more to the side shows as a fade
        hs.setFadingEdgeLength(dp(c, 24));
        hs.addView(t, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return hs;
    }

    /** One line's Markdown (bold, italic, `code`, links) as formatted text. */
    static CharSequence inlineMarkdown(String s, Runnable then) {
        android.text.SpannableStringBuilder out = new android.text.SpannableStringBuilder();
        inline(out, s, then);
        return out;
    }

    private static boolean tableLine(String l) {
        String t = l.trim();
        return t.length() > 1 && t.startsWith("|") && t.endsWith("|");
    }

    /**
     * A table where a real one (tableView) can't be shown, inside one text: a user's own message, or lines that only
     * look like a table. Each row is a block, its first cell in bold and the others under it as "Heading: value";
     * that reads at any width, which a grid of padded columns doesn't. Plain (one-line previews, notifications):
     * each row's cells with " · " between.
     */
    private static void table(android.text.SpannableStringBuilder out, String[] lines, int from, int to, Runnable then,
            boolean plain) {
        java.util.List<String[]> rows = new java.util.ArrayList<>();
        boolean header = false;
        for (int i = from; i <= to; i++) {
            if (MD_TABLE_LINE.matcher(lines[i]).matches()) { // |---|---|
                if (rows.size() == 1) header = true;
                continue;
            }
            rows.add(tableCells(lines[i]));
        }
        if (plain || !header || rows.size() == 1) { // nothing to put headings on: the cells as they come
            for (int r = 0; r < rows.size(); r++) {
                int rowStart = out.length();
                for (int k = 0; k < rows.get(r).length; k++) {
                    if (k > 0) out.append(" · ");
                    inline(out, rows.get(r)[k].replace('\n', ' '), then);
                }
                if (header && r == 0 && !plain) set(out, new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), rowStart);
                if (r < rows.size() - 1) out.append('\n');
            }
            return;
        }
        String[] head = rows.get(0);
        for (int r = 1; r < rows.size(); r++) {
            String[] row = rows.get(r);
            int rowStart = out.length();
            if (row.length > 0) inline(out, row[0].replace('\n', ' '), then);
            set(out, new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), rowStart);
            for (int k = 1; k < row.length; k++) {
                if (row[k].isEmpty()) continue;
                out.append('\n');
                if (k < head.length && !head[k].isEmpty()) {
                    int h = out.length();
                    inline(out, head[k].replace('\n', ' '), then);
                    out.append(": ");
                    set(out, new android.text.style.ForegroundColorSpan(MUTED), h);
                }
                inline(out, row[k].replace('\n', ' '), then);
            }
            if (r < rows.size() - 1) out.append("\n\n");
        }
    }

    private static void inline(android.text.SpannableStringBuilder out, String s, Runnable then) {
        java.util.regex.Matcher m = MD_INLINE.matcher(s);
        int last = 0;
        while (m.find()) {
            out.append(s, last, m.start());
            int a = out.length();
            if (m.group(1) != null) {
                out.append(m.group(1));
                monospace(out, a, out.length());
            } else if (m.group(3) != null) {
                String alt = m.group(2).trim();
                out.append(alt.isEmpty() ? m.group(3).substring(m.group(3).lastIndexOf('/') + 1) : alt);
            } else if (m.group(5) != null) {
                out.append(m.group(4));
                set(out, new LinkSpan(m.group(5), then), a);
            } else if (m.group(6) != null || m.group(7) != null) {
                inline(out, m.group(6) != null ? m.group(6) : m.group(7), then);
                set(out, new android.text.style.StyleSpan(android.graphics.Typeface.BOLD), a);
            } else if (m.group(8) != null) {
                inline(out, m.group(8), then);
                set(out, new android.text.style.StrikethroughSpan(), a);
            } else {
                inline(out, m.group(9) != null ? m.group(9) : m.group(10), then);
                set(out, new android.text.style.StyleSpan(android.graphics.Typeface.ITALIC), a);
            }
            last = m.end();
        }
        out.append(s, last, s.length());
    }

    private static void indent(android.text.SpannableStringBuilder out, String spaces) {
        for (int i = 0; i < Math.min(3, spaces.replace("\t", "  ").length() / 2); i++) out.append("    ");
    }

    private static void monospace(android.text.SpannableStringBuilder out, int a, int b) {
        if (b <= a) return;
        out.setSpan(monoSpan(400), a, b, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        out.setSpan(new android.text.style.BackgroundColorSpan(CARD2), a, b, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        out.setSpan(new android.text.style.RelativeSizeSpan(0.9f), a, b, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    /**
     * Buddy's own monospaced font at a weight. Not the system's "monospace" family: a phone's font setting can map
     * that to a proportional font, and then a table's columns don't line up.
     */
    private static android.text.style.TypefaceSpan monoSpan(int weight) {
        Context c = BuddyApp.context();
        return c == null ? new android.text.style.TypefaceSpan("monospace")
                : new android.text.style.TypefaceSpan(mono(c, weight));
    }

    private static void set(android.text.SpannableStringBuilder out, Object span, int from) {
        if (out.length() > from) out.setSpan(span, from, out.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    /**
     * For copying part of a reply as shown (start to end): a link whose text hides its address gets it after it,
     * "text (address)", so the address isn't lost outside Buddy.
     */
    static String linkCopy(CharSequence shown, int start, int end) {
        if (!(shown instanceof android.text.Spanned)) return shown.subSequence(start, end).toString();
        android.text.Spanned s = (android.text.Spanned) shown;
        LinkSpan[] links = s.getSpans(start, end, LinkSpan.class);
        java.util.Arrays.sort(links, (x, y) -> s.getSpanStart(x) - s.getSpanStart(y));
        StringBuilder b = new StringBuilder();
        int at = start;
        for (LinkSpan l : links) {
            int a = Math.max(start, s.getSpanStart(l)), z = Math.min(end, s.getSpanEnd(l));
            String label = s.subSequence(s.getSpanStart(l), s.getSpanEnd(l)).toString();
            if (a < at || l.url.contains(label)) continue; // a bare address or email shows itself already
            b.append(s, at, z).append(" (").append(l.url).append(')');
            at = z;
        }
        return b.append(s, at, end).toString();
    }

    /** Copy in a selectable reply keeps the addresses of its links (see linkCopy). */
    static void copyWithLinks(TextView t) {
        t.setCustomSelectionActionModeCallback(new android.view.ActionMode.Callback() {
            @Override
            public boolean onCreateActionMode(android.view.ActionMode mode, android.view.Menu menu) {
                return true;
            }

            @Override
            public boolean onPrepareActionMode(android.view.ActionMode mode, android.view.Menu menu) {
                return false;
            }

            @Override
            public boolean onActionItemClicked(android.view.ActionMode mode, android.view.MenuItem item) {
                if (item.getItemId() != android.R.id.copy) return false;
                int a = Math.min(t.getSelectionStart(), t.getSelectionEnd()), b = Math.max(t.getSelectionStart(), t.getSelectionEnd());
                if (a < 0 || b <= a) return false;
                t.getContext().getSystemService(android.content.ClipboardManager.class)
                        .setPrimaryClip(android.content.ClipData.newPlainText("Buddy", linkCopy(t.getText(), a, b)));
                mode.finish();
                return true;
            }

            @Override
            public void onDestroyActionMode(android.view.ActionMode mode) {
            }
        });
    }

    /**
     * A link that opens with a new task (so it works from the bubble, which isn't an activity). Not a URLSpan: Linkify,
     * which replaces those, leaves it be. Then runs before it opens.
     */
    static final class LinkSpan extends android.text.style.ClickableSpan {
        final String url;
        private final Runnable then;

        LinkSpan(String url, Runnable then) {
            this.url = url;
            this.then = then;
        }

        @Override
        public void onClick(android.view.View v) {
            if (then != null) then.run();
            try {
                v.getContext().startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW,
                        android.net.Uri.parse(url)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
            } catch (Exception e) {
                android.widget.Toast.makeText(v.getContext(), R.string.ui_no_app_for_link, android.widget.Toast.LENGTH_SHORT).show();
            }
        }
    }

    static ProgressBar spinner(Context c, int sizeDp) {
        ProgressBar p = new ProgressBar(c, null, android.R.attr.progressBarStyleSmall);
        p.setIndeterminateTintList(ColorStateList.valueOf(ACCENT));
        p.setLayoutParams(new LinearLayout.LayoutParams(dp(c, sizeDp), dp(c, sizeDp)));
        return p;
    }

    // --------------------------------------------------------------- bottom sheet

    /** A bottom sheet dialog: CARD, top radius 28, a handle, and the given content (scrolls when tall). */
    static Dialog bottomSheet(Activity a, View content) {
        Dialog d = new Dialog(a);
        d.requestWindowFeature(Window.FEATURE_NO_TITLE);
        LinearLayout box = column(a);
        box.setPaddingRelative(dp(a, 20), dp(a, 10), dp(a, 20), dp(a, 32));
        box.setBackground(corners(a, CARD, 28, 28, 0, 0));
        View handle = new View(a);
        handle.setBackground(rounded(a, OUTLINE, 2));
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(dp(a, 32), dp(a, 4));
        hp.gravity = Gravity.CENTER_HORIZONTAL;
        hp.bottomMargin = dp(a, 14);
        box.addView(handle, hp);
        // Long content (e.g. many models) scrolls instead of running off the screen.
        ScrollView scroll = new ScrollView(a);
        scroll.setOverScrollMode(View.OVER_SCROLL_NEVER);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.addView(content, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        box.addView(scroll, full());
        d.setContentView(box);
        Window w = d.getWindow();
        if (w != null) {
            w.setBackgroundDrawable(new ColorDrawable(0x00000000));
            w.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            w.setGravity(Gravity.BOTTOM);
            w.setDimAmount(0.55f);
            w.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
            w.setWindowAnimations(android.R.style.Animation_InputMethod);
        }
        return d;
    }

    static final String PRIVACY_URL = "https://github.com/ghorbelhamdi/buddy-android/blob/main/docs/PRIVACY.md";

    /**
     * Opens Android's accessibility settings, after the user has agreed to what Buddy's accessibility service does
     * (Google Play's prominent disclosure: shown in the app, before the permission, with an explicit choice).
     */
    static void openAccessibilitySettings(Activity a) {
        Runnable open = () -> a.startActivity(new android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS));
        if (Prefs.a11yConsent(a)) {
            open.run();
            return;
        }
        LinearLayout c = column(a);
        c.addView(title(a, a.getString(R.string.ui_a11y_title), 18));
        String[] points = {
                a.getString(R.string.ui_a11y_what),
                a.getString(R.string.ui_a11y_data),
                a.getString(R.string.ui_a11y_no_servers),
                a.getString(R.string.ui_a11y_asks),
        };
        for (String pt : points) {
            TextView t = text(a, pt, 14, MUTED);
            t.setPaddingRelative(0, dp(a, 8), 0, 0);
            c.addView(t);
        }
        TextView policy = text(a, a.getString(R.string.ui_privacy_policy), 14, ACCENT_TEXT);
        policy.setPaddingRelative(0, dp(a, 10), 0, 0);
        policy.setOnClickListener(v -> a.startActivity(new android.content.Intent(android.content.Intent.ACTION_VIEW,
                android.net.Uri.parse(PRIVACY_URL))));
        c.addView(policy);
        LinearLayout btns = row(a);
        btns.setPaddingRelative(0, dp(a, 18), 0, 0);
        Button no = button(a, a.getString(R.string.ui_no_thanks), false);
        Button yes = button(a, a.getString(R.string.ui_agree_continue), true);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(0, dp(a, 48), 1);
        btns.addView(no, bp);
        LinearLayout.LayoutParams yp = new LinearLayout.LayoutParams(0, dp(a, 48), 1);
        yp.setMarginStart(dp(a, 10));
        btns.addView(yes, yp);
        c.addView(btns, full());
        Dialog d = bottomSheet(a, c);
        no.setOnClickListener(v -> d.dismiss());
        yes.setOnClickListener(v -> {
            Prefs.setA11yConsent(a);
            d.dismiss();
            open.run();
        });
        d.show();
    }

    /** A sheet asking to confirm; the action runs on the destructive button. */
    static void confirmSheet(Activity a, String title, String message, String action, boolean destructive, Runnable onYes) {
        LinearLayout c = column(a);
        c.addView(title(a, title, 18));
        if (message != null) {
            TextView m = text(a, message, 14, MUTED);
            m.setPaddingRelative(0, dp(a, 6), 0, dp(a, 18));
            c.addView(m);
        }
        LinearLayout btns = row(a);
        Button no = button(a, a.getString(android.R.string.cancel), false);
        Button yes = button(a, action, !destructive);
        if (destructive) {
            yes.setTextColor(ERR);
            yes.setBackground(rounded(a, ERR_SOFT, 12));
        }
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(0, dp(a, 48), 1);
        btns.addView(no, bp);
        LinearLayout.LayoutParams yp = new LinearLayout.LayoutParams(0, dp(a, 48), 1);
        yp.setMarginStart(dp(a, 10));
        btns.addView(yes, yp);
        c.addView(btns, full());
        Dialog d = bottomSheet(a, c);
        no.setOnClickListener(v -> d.dismiss());
        yes.setOnClickListener(v -> {
            d.dismiss();
            onYes.run();
        });
        d.show();
    }

    /**
     * A sheet showing a long text someone else wrote (a message an agent scheduled), with one action: the text sits in
     * its own card, and long web addresses are shortened to "site/…/last part" links, since whole ones wrap into
     * ragged lines that are hard to read. "Close" leaves it as it is.
     */
    static void messageSheet(Activity a, String title, String message, String action, boolean destructive, Runnable onYes) {
        LinearLayout c = column(a);
        c.addView(title(a, title, 18));
        TextView m = text(a, "", 14, TEXT);
        m.setText(shortLinks(message));
        m.setLinkTextColor(ACCENT_TEXT);
        m.setMovementMethod(android.text.method.LinkMovementMethod.getInstance());
        m.setLineSpacing(dp(a, 3), 1f);
        // fill each line: Android's default evens the lines of a paragraph out, which ends them well short of the edge
        m.setBreakStrategy(android.graphics.text.LineBreaker.BREAK_STRATEGY_SIMPLE);
        m.setHyphenationFrequency(android.text.Layout.HYPHENATION_FREQUENCY_NONE);
        m.setBackground(rounded(a, BG, 16));
        m.setPaddingRelative(dp(a, 14), dp(a, 12), dp(a, 14), dp(a, 12));
        LinearLayout.LayoutParams mp = full();
        mp.topMargin = dp(a, 12);
        mp.bottomMargin = dp(a, 18);
        c.addView(m, mp);
        LinearLayout btns = row(a);
        Button no = button(a, a.getString(R.string.bubble_close), false);
        Button yes = button(a, action, !destructive);
        if (destructive) {
            yes.setTextColor(ERR);
            yes.setBackground(rounded(a, ERR_SOFT, 12));
        }
        btns.addView(no, new LinearLayout.LayoutParams(0, dp(a, 48), 1));
        LinearLayout.LayoutParams yp = new LinearLayout.LayoutParams(0, dp(a, 48), 1);
        yp.setMarginStart(dp(a, 10));
        btns.addView(yes, yp);
        c.addView(btns, full());
        Dialog d = bottomSheet(a, c);
        no.setOnClickListener(v -> d.dismiss());
        yes.setOnClickListener(v -> {
            d.dismiss();
            onYes.run();
        });
        d.show();
    }

    private static final java.util.regex.Pattern WEB = java.util.regex.Pattern.compile("https?://[^\\s<>\"]+");

    /** The text with each web address over 40 characters shown as "site/…/last part"; every address opens on a tap. */
    static CharSequence shortLinks(String text) {
        android.text.SpannableStringBuilder out = new android.text.SpannableStringBuilder();
        java.util.regex.Matcher m = WEB.matcher(text);
        int at = 0;
        while (m.find()) {
            String url = m.group();
            while (!url.isEmpty() && ").,;:!?'".indexOf(url.charAt(url.length() - 1)) >= 0) url = url.substring(0, url.length() - 1);
            if (url.length() < 12) continue;
            out.append(text, at, m.start());
            String shown = url.replaceFirst("^https?://(www\\.)?", "");
            if (url.length() > 40) shown = shortLink(shown);
            int from = out.length();
            out.append(shown);
            out.setSpan(new LinkSpan(url, null), from, out.length(), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            at = m.start() + url.length();
        }
        out.append(text, at, text.length());
        return out;
    }

    /** "site.com/a/b/c/page?id=x" → "site.com/…/page?id=x": the site and where it ends up, long parts cut. */
    static String shortLink(String address) {
        int q = address.indexOf('?'), h = address.indexOf('#');
        int end = q < 0 ? (h < 0 ? address.length() : h) : (h < 0 ? q : Math.min(q, h));
        String path = address.substring(0, end), rest = address.substring(end);
        while (path.endsWith("/")) path = path.substring(0, path.length() - 1);
        int slash = path.indexOf('/');
        if (slash < 0) return path + cut(rest, 32);
        String host = path.substring(0, slash), last = path.substring(path.lastIndexOf('/') + 1);
        boolean deep = path.lastIndexOf('/') > slash;
        return host + (deep ? "/…/" : "/") + cut(last, 28) + cut(rest, 32);
    }

    private static String cut(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    // ---------------------------------------------------------------- bottom nav

    static final int NAV_CHATS = 0, NAV_SESSIONS = 1, NAV_SETTINGS = 2;

    /** Chats · Sessions · Settings as a floating pill; the active item is a filled capsule with its label. */
    static LinearLayout bottomNav(Activity a, int active, int sessionsBadge) {
        LinearLayout bar = row(a);
        bar.setGravity(Gravity.CENTER);
        bar.setPaddingRelative(dp(a, 6), dp(a, 6), dp(a, 6), dp(a, 6));
        bar.setBackground(DARK ? rounded(a, NAV, 34) : outlined(a, NAV, 34, LINE, 1));
        bar.setElevation(dp(a, DARK ? 8 : 4));
        Object[][] items = {
                {R.drawable.ms_chat_bubble, a.getString(R.string.ui_nav_chats), MainActivity.class},
                {R.drawable.ms_terminal, a.getString(R.string.ui_nav_sessions), MonitorActivity.class},
                {R.drawable.ms_tune, a.getString(R.string.ui_nav_settings), SetupActivity.class},
        };
        for (int i = 0; i < items.length; i++) {
            final int idx = i;
            boolean on = i == active;
            LinearLayout it = row(a);
            it.setGravity(Gravity.CENTER);
            it.setPaddingRelative(dp(a, 8), 0, dp(a, 8), 0);
            it.setMinimumHeight(dp(a, 56));
            if (on) it.setBackground(rounded(a, Theme.withAlpha(ACCENT, 0x29), 28));
            else it.setBackgroundResource(ripple(a, true));
            FrameLayout iconBox = new FrameLayout(a);
            iconBox.addView(icon(a, (Integer) items[i][0], on ? TEXT : MUTED, 20),
                    new FrameLayout.LayoutParams(dp(a, 20), dp(a, 20), Gravity.CENTER));
            it.addView(iconBox, new LinearLayout.LayoutParams(dp(a, 28), dp(a, 28)));
            TextView label = single(text(a, (String) items[i][1], 14, on ? TEXT : MUTED));
            label.setTypeface(sans(a, on ? 600 : 500));
            label.setPaddingRelative(dp(a, 6), 0, 0, 0);
            it.addView(label);
            if (i == NAV_SESSIONS && sessionsBadge > 0) {
                TextView b = text(a, String.valueOf(sessionsBadge), 11, ON_ACCENT);
                b.setTypeface(sans(a, 700));
                b.setGravity(Gravity.CENTER);
                b.setMinWidth(dp(a, 18));
                b.setBackground(rounded(a, ACCENT, 9));
                b.setPaddingRelative(dp(a, 5), 0, dp(a, 5), 0);
                LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(a, 18));
                bp.setMarginStart(dp(a, 6));
                it.addView(b, bp);
            }
            it.setSelected(on);
            it.setOnClickListener(v -> {
                if (idx == active) return;
                Intent in = new Intent(a, (Class<?>) items[idx][2]).addFlags(Intent.FLAG_ACTIVITY_NO_ANIMATION);
                // Chats is the root: go back to it instead of stacking another copy
                if (idx == NAV_CHATS) in.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                a.startActivity(in);
                a.overridePendingTransition(0, 0);
                if (!(a instanceof MainActivity)) a.finish();
            });
            LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(0, dp(a, 56), 1);
            if (i > 0) ip.setMarginStart(dp(a, 4));
            bar.addView(it, ip);
        }
        LinearLayout wrap = column(a);
        wrap.setBackgroundColor(BG);
        wrap.setPaddingRelative(dp(a, 16), dp(a, 8), dp(a, 16), dp(a, 14));
        wrap.setClipToPadding(false);
        wrap.addView(bar, full());
        return wrap;
    }
}
