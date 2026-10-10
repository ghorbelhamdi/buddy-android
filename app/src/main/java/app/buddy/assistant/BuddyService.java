package app.buddy.assistant;

import android.accessibilityservice.AccessibilityService;
import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityWindowInfo;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Accessibility service: draws the Buddy bubble, hosts the local MCP server,
 * and performs the screen actions the agent (Claude Code or Codex) asks for.
 */
public class BuddyService extends AccessibilityService implements McpServer.Handler {
    private static volatile BuddyService instance;

    private final Handler main = new Handler(Looper.getMainLooper());
    private Bubble bubble;
    private ControlIndicator control;
    /** After the user taps Stop on the control indicator, phone actions are refused until then (elapsedRealtime). */
    private volatile long revokedUntil;
    private Tools tools;
    private McpServer server;

    static BuddyService get() {
        return instance;
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        Theme.load(this);
        Models.setCodex(Prefs.codexModels(this));
        new Thread(() -> Attachments.cleanup(this)).start();
        tools = new Tools(this);
        bubble = new Bubble(this);
        control = new ControlIndicator(this);
        bubble.show();
        server = new McpServer(Prefs.token(this), this);
        server.start();

        // Hide the bubble on the lock screen.
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_SCREEN_OFF);
        f.addAction(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_USER_PRESENT);
        registerReceiver(lockReceiver, f);
        bubble.setLocked(getSystemService(KeyguardManager.class).isKeyguardLocked());
        if (!Prefs.onboarded(this)) main.postDelayed(this::showGuide, 600);
        main.postDelayed(() -> RemoteSession.ensure(this), 4000);
    }

    private final BroadcastReceiver lockReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            if (bubble == null) return;
            if (Intent.ACTION_USER_PRESENT.equals(i.getAction())) bubble.setLocked(false);
            else if (Intent.ACTION_SCREEN_OFF.equals(i.getAction())) bubble.setLocked(true);
            else bubble.setLocked(getSystemService(KeyguardManager.class).isKeyguardLocked());
        }
    };

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (bubble == null) return;
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            CharSequence pkg = event.getPackageName();
            if (pkg != null && isLauncher(pkg.toString())) bubble.onLauncherShown();
            return;
        }
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOWS_CHANGED) return;
        int top = 0;
        for (AccessibilityWindowInfo w : getWindows()) {
            if (w.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                Rect r = new Rect();
                w.getBoundsInScreen(r);
                if (!r.isEmpty()) top = r.top;
            }
        }
        bubble.onKeyboardTop(top);
    }

    private java.util.Set<String> launchers;

    /**
     * Every installed home app. Recents is often shown by the phone's built-in launcher even when
     * another one (e.g. Nova) handles Home, so all of them count.
     */
    private boolean isLauncher(String pkg) {
        if (launchers == null) {
            launchers = new java.util.HashSet<>();
            try {
                for (android.content.pm.ResolveInfo r : getPackageManager().queryIntentActivities(
                        new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)) {
                    String p = r.activityInfo.packageName;
                    if (!"com.android.settings".equals(p)) launchers.add(p); // Settings' fallback home
                }
            } catch (Exception ignored) {
            }
        }
        return launchers.contains(pkg);
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onDestroy() {
        try {
            unregisterReceiver(lockReceiver);
        } catch (Exception ignored) {
        }
        RemoteSession.stop();
        if (server != null) server.stop();
        if (bubble != null) bubble.remove();
        if (control != null) control.end();
        instance = null;
        super.onDestroy();
    }

    Handler mainHandler() {
        return main;
    }

    /** Show or hide the bubble after the setting changes in the app. */
    void applyBubbleSetting() {
        main.post(() -> {
            if (bubble != null) bubble.applyEnabled();
        });
    }

    /** Restyle the bubble after the appearance settings (or the system dark mode) change. */
    void applyTheme() {
        main.post(() -> {
            if (bubble != null) bubble.applyTheme();
        });
    }

    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (Theme.load(this)) applyTheme();
    }

    // ---------------------------------------- Claude sign-in (built-in Linux)

    private final Runnable codeWatch = new Runnable() {
        @Override
        public void run() {
            if (!Account.CLAUDE.waitingForCode()) return;
            String code = null;
            try {
                for (android.view.accessibility.AccessibilityWindowInfo w : getWindows()) {
                    android.view.accessibility.AccessibilityNodeInfo r = w.getRoot();
                    if (r != null && (code = scanForCode(r, new int[]{0})) != null) break;
                }
            } catch (Exception ignored) {
            }
            if (code != null && Account.CLAUDE.submit(code)) {
                startActivity(new Intent(BuddyService.this, SignInActivity.class)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
                return;
            }
            main.postDelayed(this, 700);
        }
    };

    /** While a Claude sign-in waits for its code, look for it on screen (the browser's confirmation page). */
    void watchForLoginCode() {
        main.removeCallbacks(codeWatch);
        main.postDelayed(codeWatch, 1500);
    }

    private static String scanForCode(android.view.accessibility.AccessibilityNodeInfo n, int[] seen) {
        if (n == null || ++seen[0] > 4000) return null;
        String c = Account.CLAUDE.findCode(n.getText());
        if (c == null) c = Account.CLAUDE.findCode(n.getContentDescription());
        if (c != null) return c;
        for (int i = 0; i < n.getChildCount(); i++) {
            c = scanForCode(n.getChild(i), seen);
            if (c != null) return c;
        }
        return null;
    }

    // ------------------------------------------------- first-run guide

    void showGuide() {
        try {
            startActivity(new Intent(this, OnboardingActivity.class)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
        } catch (Exception ignored) {
        }
    }

    Bubble bubble() {
        return bubble;
    }

    // ------------------------------------------------------- MCP handler

    @Override
    public JSONArray listTools() throws JSONException {
        return tools.list();
    }

    @Override
    public JSONObject callTool(String name, JSONObject args) throws Exception {
        return tools.call(name, args);
    }

    @Override
    public java.io.File attachment(String id) {
        return Attachments.file(this, id);
    }

    @Override
    public void onEvent(final JSONObject event) {
        if ("buddy_pong".equals(event.optString("type"))) {
            Prefs.setHelperStatus(this, event.optString("text"));
            Prefs.setHelperVersion(this, event.optInt("helper", 1));
            if (event.has("signed")) Prefs.setSignedAgents(this, event.optString("signed"));
            org.json.JSONArray cm = event.optJSONArray("codex_models");
            if (cm != null && cm.length() > 0) {
                Prefs.setCodexModels(this, cm.toString());
                Models.setCodex(cm.toString());
            }
            return;
        }
        String t = event.optString("type");
        if ("buddy_procs".equals(t) || "buddy_recent".equals(t) || "buddy_transcript".equals(t)) {
            main.post(() -> ChatHub.get(this).onMonitorEvent(event));
            return;
        }
        main.post(() -> ChatHub.get(this).onEvent(event));
    }

    // ------------------------------------------- called from tool threads

    void onToolActivity(final String tool) {
        main.post(() -> {
            bubble.onToolActivity(tool);
            // asking for approval or posting a note isn't operating the phone
            if (!"confirm".equals(tool) && !"notify".equals(tool)) control.onActivity();
        });
    }

    /** Null if phone actions are allowed, else why not (the user took back control). */
    String controlRevoked() {
        long left = revokedUntil - android.os.SystemClock.elapsedRealtime();
        if (left <= 0) return null;
        return "The user tapped Stop to take back control of the phone. Don't use the phone tools for now; "
                + "stop and ask the user what they want.";
    }

    /** The user asked for something new: phone actions are allowed again. */
    void allowControl() {
        revokedUntil = 0;
    }

    /** Stop from the indicator or its notification: stop Buddy's chats and refuse phone actions for a minute. */
    void takeBackControl() {
        main.post(() -> {
            revokedUntil = android.os.SystemClock.elapsedRealtime() + 60_000;
            ChatHub.get(this).stopAll();
            control.end();
            android.widget.Toast.makeText(this, "Stopped. Your phone is yours again.", android.widget.Toast.LENGTH_SHORT).show();
        });
    }

    /** Short vibration to get the user's attention (e.g. "tap Update"). */
    void nudge() {
        try {
            android.os.Vibrator v = getSystemService(android.os.Vibrator.class);
            if (v != null) v.vibrate(android.os.VibrationEffect.createWaveform(new long[]{0, 60, 80, 60}, -1));
        } catch (Exception ignored) {
        }
    }

    void showStatus(final String text) {
        main.post(() -> bubble.setStatus(text));
    }

    void setBubblePassThrough(final boolean on) {
        final CountDownLatch done = new CountDownLatch(1);
        main.post(() -> {
            bubble.setPassThrough(on);
            done.countDown();
        });
        try {
            done.await(1, TimeUnit.SECONDS);
            if (on) Thread.sleep(120);
        } catch (InterruptedException ignored) {
        }
    }

    /** Blocks the calling (tool) thread until the user answers in the bubble. */
    String askConfirm(final String summary) throws InterruptedException {
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicReference<Boolean> answer = new AtomicReference<>();
        main.post(() -> bubble.askConfirm(summary, approved -> {
            answer.set(approved);
            done.countDown();
        }));
        if (!done.await(5, TimeUnit.MINUTES)) {
            main.post(() -> bubble.cancelConfirm());
            return "DENIED: the user did not answer within 5 minutes. Do not do it.";
        }
        return Boolean.TRUE.equals(answer.get())
                ? "APPROVED by the user. Go ahead."
                : "DENIED by the user. Do not do it; ask what they want instead.";
    }
}
