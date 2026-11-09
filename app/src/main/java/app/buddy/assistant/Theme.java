package app.buddy.assistant;

import android.app.Activity;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.view.View;
import android.view.Window;

/**
 * Computes Buddy's palette from the user's appearance settings (mode, accent, surface style) and
 * writes it into the {@link Ui} colour fields. Every screen calls {@link #apply(Activity)} in onCreate.
 */
final class Theme {
    static final String[][] ACCENTS = {
            {"#D97757", "Coral"}, {"#4F8BFF", "Ocean"}, {"#3FB98A", "Mint"},
            {"#9A7CFF", "Violet"}, {"#E5678F", "Rose"}, {"#E0A43A", "Amber"},
    };
    static final String[][] SURFACES = {{"warm", "Warm"}, {"neutral", "Neutral"}, {"black", "Black"}};
    static final String[][] MODES = {{"system", "System"}, {"light", "Light"}, {"dark", "Dark"}};

    private static String applied = "";

    private Theme() {
    }

    /** {@link #ACCENTS} with the colour names in the user's language (the labels above are English). */
    static String[][] accents(Context c) {
        return labeled(c, ACCENTS, R.string.prefs_accent_coral, R.string.prefs_accent_ocean, R.string.prefs_accent_mint,
                R.string.prefs_accent_violet, R.string.prefs_accent_rose, R.string.prefs_accent_amber);
    }

    /** {@link #SURFACES} with labels in the user's language. */
    static String[][] surfaces(Context c) {
        return labeled(c, SURFACES, R.string.prefs_surface_warm, R.string.prefs_surface_neutral, R.string.prefs_surface_black);
    }

    /** {@link #MODES} with labels in the user's language. */
    static String[][] modes(Context c) {
        return labeled(c, MODES, R.string.prefs_theme_system, R.string.prefs_theme_light, R.string.prefs_theme_dark);
    }

    /** {value, label} pairs with each label replaced by the string resource at the same index. */
    private static String[][] labeled(Context c, String[][] options, int... labels) {
        String[][] out = new String[options.length][];
        for (int i = 0; i < options.length; i++) out[i] = new String[]{options[i][0], c.getString(labels[i])};
        return out;
    }

    static boolean isDark(Context c) {
        String m = Prefs.themeMode(c);
        if ("dark".equals(m)) return true;
        if ("light".equals(m)) return false;
        return (c.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }

    /** Fill Ui's palette for the current settings. Cheap; returns whether anything changed. */
    static boolean load(Context c) {
        boolean dark = isDark(c);
        int accent = Prefs.accent(c);
        String surface = Prefs.surface(c);
        String key = dark + "|" + accent + "|" + surface;
        if (key.equals(applied)) return false;
        applied = key;

        if (dark) {
            // Buddy 3: soft layered surfaces (BG < CARD < CARD2); NAV is the floating nav pill
            switch (surface) {
                case "neutral":
                    set(0xFF121212, 0xFF1D1D1C, 0xFF2A2A29, 0xFF242423, 0xFF0B0B0B);
                    break;
                case "black":
                    set(0xFF000000, 0xFF151413, 0xFF262420, 0xFF1C1B19, 0xFF000000);
                    break;
                default: // warm, the original tone
                    set(0xFF121110, 0xFF1D1B19, 0xFF2A2724, 0xFF24221F, 0xFF0F0E0D);
            }
            Ui.TEXT = 0xFFF3EFE8;
            Ui.TEXT2 = 0xFFC9C3B9;
            Ui.MUTED = 0xFFA39D93;
            Ui.OK = 0xFF5CC08A;
            Ui.ERR = 0xFFF0A090;
            Ui.USER = blend(accent, Ui.BG, 0.32f);
        } else {
            if ("neutral".equals(surface)) set(0xFFF4F4F3, 0xFFFFFFFF, 0xFFEAEAE8, 0xFFFFFFFF, 0xFF1C1C1C);
            else set(0xFFF6F2EC, 0xFFFFFFFF, 0xFFEEE7DE, 0xFFFFFFFF, 0xFF24211E);
            Ui.TEXT = 0xFF1F1B17;
            Ui.TEXT2 = 0xFF4A443D;
            Ui.MUTED = 0xFF6B635A;
            Ui.OK = 0xFF2F9E66;
            Ui.ERR = 0xFFC5483A;
            Ui.USER = blend(accent, Ui.CARD, 0.22f);
        }
        Ui.ACCENT = accent;
        Ui.ON_ACCENT = luminance(accent) > 0.45 ? 0xFF1A1714 : 0xFFFFFFFF;
        Ui.ACCENT_TEXT = dark ? accent : darken(accent, luminance(accent) > 0.45 ? 0.62f : 0.85f);
        Ui.LINE = withAlpha(Ui.TEXT, 0x10);
        Ui.OUTLINE = withAlpha(Ui.TEXT, 0x22);
        Ui.ACCENT_SOFT = withAlpha(accent, 0x29);
        Ui.ERR_SOFT = withAlpha(Ui.ERR, 0x1F);
        Ui.CODE_TEXT = 0xFFF4EFE6; // the code block stays dark in both modes
        Ui.DARK = dark;
        return true;
    }

    private static void set(int bg, int card, int card2, int nav, int code) {
        Ui.BG = bg;
        Ui.CARD = card;
        Ui.CARD2 = card2;
        Ui.NAV = nav;
        Ui.CODE = code;
    }

    /** Load the palette and style the window (bar colours and icon contrast) for an activity. */
    static void apply(Activity a) {
        load(a);
        Window w = a.getWindow();
        w.setStatusBarColor(Ui.BG);
        w.setNavigationBarColor(Ui.BG); // the nav floats as a pill on BG
        int flags = w.getDecorView().getSystemUiVisibility();
        if (Ui.DARK) {
            flags &= ~(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
        } else {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
        }
        w.getDecorView().setSystemUiVisibility(flags);
    }

    /** Settings changed: recalc, and tell the bubble to restyle itself. */
    static void changed(Context c) {
        applied = "";
        load(c);
        BuddyService s = BuddyService.get();
        if (s != null) s.applyTheme();
    }

    static String key() {
        return applied;
    }

    static int parse(String hex, int fallback) {
        try {
            return Color.parseColor(hex.startsWith("#") ? hex : "#" + hex) | 0xFF000000;
        } catch (Exception e) {
            return fallback;
        }
    }

    static String hex(int c) {
        return String.format("#%06X", c & 0xFFFFFF);
    }

    static int withAlpha(int c, int a) {
        return (c & 0x00FFFFFF) | (a << 24);
    }

    static int blend(int a, int b, float t) {
        return Color.rgb(
                Math.round(Color.red(b) + (Color.red(a) - Color.red(b)) * t),
                Math.round(Color.green(b) + (Color.green(a) - Color.green(b)) * t),
                Math.round(Color.blue(b) + (Color.blue(a) - Color.blue(b)) * t));
    }

    static int darken(int c, float f) {
        return Color.rgb(Math.round(Color.red(c) * f), Math.round(Color.green(c) * f), Math.round(Color.blue(c) * f));
    }

    static double luminance(int c) {
        return (0.2126 * Color.red(c) + 0.7152 * Color.green(c) + 0.0722 * Color.blue(c)) / 255.0;
    }
}
