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
            switch (surface) {
                case "neutral":
                    set(0xFF141414, 0xFF222222, 0xFF383838, 0xFF1C1C1C, 0xFF0B0B0B);
                    break;
                case "black":
                    set(0xFF000000, 0xFF161514, 0xFF2C2A28, 0xFF0A0A0A, 0xFF000000);
                    break;
                default: // warm, the original palette
                    set(0xFF171513, 0xFF24211E, 0xFF3A3632, 0xFF1E1B19, 0xFF0F0E0D);
            }
            Ui.TEXT = 0xFFF4EFE6;
            Ui.MUTED = 0xFFA39E95;
            Ui.OK = 0xFF5CC08A;
            Ui.ERR = 0xFFE58A7A;
            Ui.USER = blend(accent, Ui.BG, 0.24f);
        } else {
            if ("neutral".equals(surface)) set(0xFFF5F5F4, 0xFFFFFFFF, 0xFFE7E7E5, 0xFFEFEFEE, 0xFF1C1C1C);
            else set(0xFFF7F3EE, 0xFFFFFFFF, 0xFFECE5DD, 0xFFF1EBE4, 0xFF24211E);
            Ui.TEXT = 0xFF221E1A;
            Ui.MUTED = 0xFF6F675F;
            Ui.OK = 0xFF2F9E66;
            Ui.ERR = 0xFFC5483A;
            Ui.USER = blend(accent, Ui.CARD, 0.2f);
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
        w.setNavigationBarColor(Ui.NAV);
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
