package app.buddy.assistant;

import android.os.Bundle;
import android.view.WindowManager;
import android.widget.FrameLayout;

import static app.buddy.assistant.Ui.*;

/**
 * The background browser full screen (see BrowserPanel): a chat's tab, from its live card, or the user's own tab from
 * Settings, where they sign in to sites for the agents.
 */
public class BrowserActivity extends Screen implements Browser.Listener {
    /** The tab shown full screen while this is in front, or null (the bubble then leaves out its thumbnail). */
    static volatile String front;
    private BrowserPanel panel;
    /** Opened for an agent's question (ask with browser_highlight): it closes once the question is answered. */
    private boolean guided;
    private String key;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        if (!Browser.available(this)) {
            finish();
            return;
        }
        key = getIntent().getStringExtra("key");
        if (key == null) key = Browser.USER;
        guided = getIntent().getBooleanExtra("guided", false);
        panel = new BrowserPanel(this, key, true);
        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(BG);
        frame.setFitsSystemWindows(true);
        frame.addView(panel);
        setContentView(frame);
        Browser.start(this);
        if (guided) Browser.addListener(this);
    }

    @Override
    public void onBrowserChanged() {
        if (guided && Browser.marks(key) == null) finish(); // the user answered: back to where they were
    }

    @Override
    protected void onDestroy() {
        Browser.removeListener(this);
        super.onDestroy();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (panel != null) panel.setShown(true);
        front = key;
        bubbleRefresh();
    }

    @Override
    protected void onPause() {
        if (panel != null) panel.setShown(false);
        front = null;
        bubbleRefresh();
        super.onPause();
    }

    private static void bubbleRefresh() {
        BuddyService svc = BuddyService.get();
        Bubble b = svc == null ? null : svc.bubble();
        if (b != null) b.onBrowserScreen();
    }
}
