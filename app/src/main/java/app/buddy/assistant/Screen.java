package app.buddy.assistant;

import android.app.Activity;
import android.os.Bundle;

/** Base for Buddy's screens: applies the theme and rebuilds when the appearance settings change. */
abstract class Screen extends Activity {
    private String themeKey;

    @Override
    protected void onCreate(Bundle state) {
        Theme.apply(this);
        themeKey = Theme.key();
        super.onCreate(state);
        STACK.add(this);
    }

    /** Buddy's screens that are there, in the order they were made (main thread). */
    private static final java.util.List<Screen> STACK = new java.util.ArrayList<>();

    /** The screen right under this one in its task, if it is one of Buddy's and still there; else null. */
    Screen under() {
        for (int i = STACK.indexOf(this) - 1; i >= 0; i--) {
            Screen u = STACK.get(i);
            if (u.isFinishing() || u.isDestroyed()) continue;
            return u.getTaskId() == getTaskId() ? u : null;
        }
        return null;
    }

    @Override
    protected void onResume() {
        super.onResume();
        BuddyService.restoreAccess(this); // before the screen says the service is off: Buddy may switch it back on itself
        Theme.load(this);
        if (!Theme.key().equals(themeKey)) recreate();
    }

    /** How many of Buddy's screens the user sees right now (started ones; main thread). */
    private static int open;

    /** One of Buddy's own screens is open: the bubble can keep out of its way (see Bubble.applyEnabled). */
    static boolean anyOpen() {
        return open > 0;
    }

    /** This screen is one of the counted ones. */
    private boolean counted;

    @Override
    protected void onStart() {
        super.onStart();
        if (counted) return; // (it stayed counted while the phone's screen was off: see onStop)
        // only a screen that has the user's whole display: not Buddy in half of a split screen or in a small window,
        // where the bubble belongs to the app beside it, and not a screen an agent opened on its hidden display
        android.view.Display d = getDisplay();
        if ((d != null && d.getDisplayId() != android.view.Display.DEFAULT_DISPLAY) || isInMultiWindowMode()) return;
        counted = true;
        if (open++ == 0) bubbleFollows(0);
    }

    @Override
    protected void onStop() {
        super.onStop();
        // the phone's screen went off with Buddy in front: it is still the screen the user comes back to, and uncounted
        // the bubble would pop in over it at the unlock, before onStart counts it again
        android.os.PowerManager pm = getSystemService(android.os.PowerManager.class);
        if (counted && !isFinishing() && !isChangingConfigurations() && pm != null && !pm.isInteractive()) return;
        uncount();
    }

    @Override
    protected void onDestroy() {
        STACK.remove(this);
        uncount(); // (one that was kept counted over a dark screen)
        super.onDestroy();
    }

    private void uncount() {
        if (!counted) return;
        counted = false;
        // a moment later: a screen that rebuilds itself (a new theme) is gone for an instant, and the bubble would blink.
        // Going from one of Buddy's screens to the next needs no wait: the next one starts before this one stops.
        if (--open <= 0) {
            open = 0;
            bubbleFollows(300);
        }
    }

    private static void bubbleFollows(long ms) {
        BuddyService svc = BuddyService.get();
        if (svc != null) svc.mainHandler().postDelayed(() -> {
            if (BuddyService.get() == svc) svc.applyBubbleSetting();
        }, ms);
    }

    /** Re-apply the theme in place (after a change made on this screen). */
    protected void rethemed() {
        Theme.apply(this);
        themeKey = Theme.key();
    }
}
