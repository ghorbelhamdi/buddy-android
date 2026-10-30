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
 * and performs the screen actions the agent (Claude Code, Codex, OpenCode, Antigravity or Grok) asks for.
 */
public class BuddyService extends AccessibilityService implements McpServer.Handler {
    private static volatile BuddyService instance;

    private final Handler main = new Handler(Looper.getMainLooper());
    private Bubble bubble;
    private ControlIndicator control;
    /** After the user taps Stop on the control indicator, phone actions are refused until then (elapsedRealtime). */
    private volatile long revokedUntil;
    /**
     * Pause (the hold menu, or on its own when the user takes the phone: see AutoPause): phone tools wait (see
     * awaitResume) while the user uses the phone.
     */
    private volatile boolean paused, rereadNeeded;
    /** A phone tool waited in this pause: an agent is still at work behind it. */
    private volatile boolean heldInPause;
    /** Phone tools waiting in awaitResume right now (guarded by pauseLock). */
    private int waiters;
    private final Object pauseLock = new Object();
    private static final long PAUSE_MAX_MS = 5 * 60_000, PAUSE_WAIT_MS = 45_000;
    private final Runnable pauseExpired = () -> {
        if (!paused) return;
        // nothing waited for it and no chat is running (a session outside Buddy that had finished): just end it
        if (!heldInPause && ChatHub.get(this).runningCount() == 0) {
            endPause();
            return;
        }
        takeBackControl();
        android.widget.Toast.makeText(this, R.string.service_pause_expired, android.widget.Toast.LENGTH_LONG).show();
    };
    private Tools tools;
    private McpServer server;
    private AutoPause autoPause;
    private final KeepAwake keepAwake = new KeepAwake(this);
    private Marks marks;
    /** Touches on the screen while a card waited for the user (see onScreenTouched). */
    private volatile int cardTouches;

    static BuddyService get() {
        return instance;
    }

    /** Update notices: Updates.maybeCheck asks GitHub at most once a day. */
    private final Runnable updateCheck = new Runnable() {
        @Override
        public void run() {
            if (!Flavor.PLAY) Updates.maybeCheck(BuddyService.this);
            main.postDelayed(this, 6 * 3600_000L);
        }
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        Theme.load(this);
        orientation = getResources().getConfiguration().orientation;
        Models.setCodex(Prefs.codexModels(this));
        Models.setOpencode(Prefs.opencodeModels(this));
        Models.setAgy(Prefs.agyModels(this));
        Models.setGrok(Prefs.grokModels(this));
        new Thread(() -> Attachments.cleanup(this)).start();
        tools = new Tools(this);
        autoPause = new AutoPause(this);
        marks = new Marks(this);
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
        main.postDelayed(() -> SshServer.ensure(this), 4000);
        main.postDelayed(() -> CodexRemote.ensure(this), 4000);
        main.postDelayed(updateCheck, 20_000);
        Schedules.serviceStarted(this); // scheduled tasks' alarms, again after an update or a restart
        // the helper's answer refreshes Codex's model list
        main.postDelayed(() -> {
            if (Linux.installed(this)) {
                try {
                    Helper.run(this, "ping");
                } catch (Exception ignored) {
                }
            }
        }, 8000);
    }

    private final BroadcastReceiver lockReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            keepAwake.update(); // locked: the screen may sleep; unlocked: a long task keeps it on again
            if (bubble == null) return;
            if (Intent.ACTION_USER_PRESENT.equals(i.getAction())) bubble.setLocked(false);
            else if (Intent.ACTION_SCREEN_OFF.equals(i.getAction())) bubble.setLocked(true);
            else bubble.setLocked(getSystemService(KeyguardManager.class).isKeyguardLocked());
        }
    };

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (bubble == null) return;
        int type = event.getEventType();
        if (type == AccessibilityEvent.TYPE_VIEW_CLICKED) { // only while a mark waits for a tap (listenForClicks)
            marks.onClicked(event.getSource(), event.getEventTime());
            return;
        }
        if (type != AccessibilityEvent.TYPE_WINDOWS_CHANGED && type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        int top = 0;
        AccessibilityWindowInfo app = null;
        for (AccessibilityWindowInfo w : getWindows()) {
            if (w.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                Rect r = new Rect();
                w.getBoundsInScreen(r);
                if (!r.isEmpty()) top = r.top;
            } else if (w.getType() == AccessibilityWindowInfo.TYPE_APPLICATION
                    && (app == null || w.getLayer() > app.getLayer())) {
                app = w;
            }
        }
        if (type == AccessibilityEvent.TYPE_WINDOWS_CHANGED) bubble.onKeyboardTop(top);
        // Opening Home or Recents folds the panel. Decided by the app actually in front changing: the home
        // screen also reports changes while it's already there (e.g. the keyboard opening or closing over it).
        android.view.accessibility.AccessibilityNodeInfo root = app == null ? null : app.getRoot();
        CharSequence pkg = root == null ? null : root.getPackageName();
        // Buddy's own windows don't count (the text cursor's handle over the bubble is one)
        if (pkg == null || pkg.toString().equals(getPackageName())) return;
        String front = pkg.toString();
        // Recents may belong to another launcher than Home (e.g. the phone's own while Nova handles Home)
        if (!front.equals(frontApp) && isLauncher(front)) bubble.onLauncherShown();
        if (!front.equals(frontApp)) { // what they pointed at was in the app that's gone
            marks.clear("LEFT: the user switched to another app before doing it.");
        }
        frontApp = front;
    }

    /** The app in front (the home screen and Recents count as apps). */
    private String frontApp;

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
        SshServer.stop();
        CodexRemote.stop();
        main.removeCallbacks(updateCheck);
        if (server != null) server.stop();
        if (autoPause != null) autoPause.stop();
        keepAwake.remove();
        if (marks != null) marks.clear();
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

    /** Buddy's look changed in Settings: the bubble shows the new face. */
    void applyFace() {
        main.post(() -> {
            if (bubble != null) bubble.applyFace();
        });
    }

    /** Restyle the bubble after the appearance settings (or the system dark mode) change. */
    void applyTheme() {
        main.post(() -> {
            if (bubble != null) bubble.applyTheme();
        });
    }

    private int orientation = android.content.res.Configuration.ORIENTATION_UNDEFINED;

    @Override
    public void onConfigurationChanged(android.content.res.Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (Theme.load(this)) applyTheme();
        if (newConfig.orientation != orientation) {
            orientation = newConfig.orientation;
            main.post(() -> {
                if (bubble != null) bubble.onRotated();
            });
        }
    }

    // ---------------------------------------- Claude / Antigravity sign-in (built-in Linux)

    private final Runnable codeWatch = new Runnable() {
        @Override
        public void run() {
            Account acc = Account.waiting();
            if (acc == null || System.currentTimeMillis() > codeWatchUntil) return;
            String code = null;
            try {
                for (android.view.accessibility.AccessibilityWindowInfo w : getWindows()) {
                    android.view.accessibility.AccessibilityNodeInfo r = w.getRoot();
                    if (r == null) continue;
                    code = scanForCode(acc, r, new int[]{0});
                    r.recycle();
                    if (code != null) break;
                }
            } catch (Exception ignored) {
            }
            if (code != null && acc.submit(code)) {
                startActivity(new Intent(BuddyService.this, SignInActivity.class).putExtra("agent", acc.agent)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
                return;
            }
            main.postDelayed(this, 700);
        }
    };

    /** While a Claude or Antigravity sign-in waits for its code, look for it on screen (the browser's confirmation page). */
    private long codeWatchUntil;

    void watchForLoginCode() {
        codeWatchUntil = System.currentTimeMillis() + 10 * 60_000L; // the old Termux watcher also gave up eventually
        main.removeCallbacks(codeWatch);
        main.postDelayed(codeWatch, 1500);
    }

    private static String scanForCode(Account acc, android.view.accessibility.AccessibilityNodeInfo n, int[] seen) {
        if (n == null || ++seen[0] > 4000) return null;
        String c = acc.findCode(n.getText());
        if (c == null) c = acc.findCode(n.getContentDescription());
        if (c != null) return c;
        for (int i = 0; i < n.getChildCount(); i++) {
            android.view.accessibility.AccessibilityNodeInfo k = n.getChild(i);
            if (k == null) continue;
            c = scanForCode(acc, k, seen);
            k.recycle(); // these are pooled before Android 13; scanning repeats every 0.7 s
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

    void runOnMain(Runnable r) {
        main.post(r);
    }

    /** The name of the app on screen (for approval cards), or null. */
    String foregroundAppName() {
        String pkg = foregroundPkg();
        if (pkg == null) return null;
        try {
            android.content.pm.PackageManager pm = getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (Exception e) {
            return pkg;
        }
    }

    /** The package of the app on screen, or null. */
    String foregroundPkg() {
        try {
            for (android.view.accessibility.AccessibilityWindowInfo w : getWindows()) {
                if (w.getType() != android.view.accessibility.AccessibilityWindowInfo.TYPE_APPLICATION || !w.isActive()) continue;
                android.view.accessibility.AccessibilityNodeInfo r = w.getRoot();
                if (r == null || r.getPackageName() == null) continue;
                return r.getPackageName().toString();
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    Bubble bubble() {
        return bubble;
    }

    KeepAwake keepAwake() {
        return keepAwake;
    }

    AutoPause autoPauser() {
        return autoPause;
    }

    /** The point_out tool's marks (main thread). */
    Marks marks() {
        return marks;
    }

    /**
     * Hear about taps on the screen (main thread): only while a mark waits for the user to tap it, so the rest of the
     * time Buddy gets no such events.
     */
    void listenForClicks(boolean on) {
        android.accessibilityservice.AccessibilityServiceInfo info = getServiceInfo();
        if (info == null) return;
        int types = on ? info.eventTypes | AccessibilityEvent.TYPE_VIEW_CLICKED
                : info.eventTypes & ~AccessibilityEvent.TYPE_VIEW_CLICKED;
        if (types == info.eventTypes) return;
        info.eventTypes = types;
        setServiceInfo(info);
    }

    /** The user did something on the screen the agent asked for: the agent re-reads it before acting. */
    void screenChangedByUser() {
        rereadNeeded = true;
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
            org.json.JSONArray om = event.optJSONArray("opencode_models");
            if (om != null && om.length() > 0) {
                Prefs.setOpencodeModels(this, om.toString());
                Models.setOpencode(om.toString());
            }
            org.json.JSONArray am = event.optJSONArray("agy_models");
            if (am != null && am.length() > 0) {
                Prefs.setAgyModels(this, am.toString());
                Models.setAgy(am.toString());
            }
            org.json.JSONArray gm = event.optJSONArray("grok_models");
            if (gm != null && gm.length() > 0) {
                Prefs.setGrokModels(this, gm.toString());
                Models.setGrok(gm.toString());
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
            // asking for approval, asking a question, posting a note, pointing something out or answering in a
            // notification isn't operating the phone
            // nor is waiting or listing apps
            if (!"confirm".equals(tool) && !"ask".equals(tool) && !"notify".equals(tool) && !"reply_notification".equals(tool)
                    && !"point_out".equals(tool) && !"wait".equals(tool) && !"list_apps".equals(tool)
                    && !Tools.SCHEDULING.contains(tool)
                    // installing: Android's installer asks the user, the agent doesn't touch the screen
                    && !"update_buddy".equals(tool) && !"install_apk".equals(tool) && !"wait_for_install".equals(tool)
                    && !tool.startsWith("browser_")) { // the background browser isn't the phone
                control.onActivity();
            }
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
        rereadNeeded = false;
    }

    /** Stop from the indicator or its notification: stop Buddy's chats and refuse phone actions for a minute. */
    void takeBackControl() {
        main.post(() -> {
            CodexLive.end(getString(R.string.live_ended_paused)); // the user took the phone back: no voice either
            revokedUntil = android.os.SystemClock.elapsedRealtime() + 60_000;
            endPause(); // after revoking, so tool calls waiting in a pause get "stopped", not go ahead
            ChatHub.get(this).stopAll();
            control.end();
            android.widget.Toast.makeText(this, R.string.service_stopped_toast, android.widget.Toast.LENGTH_SHORT).show();
        });
    }

    boolean paused() {
        return paused;
    }

    /** Pause (main thread): the agent's next phone action waits until resume, so the user can use the phone. */
    void pause() {
        pause(R.string.service_paused_note, R.string.service_paused_toast);
    }

    /**
     * The user took the phone while an agent operated it (AutoPause: a touch, a call; main thread): pause, saying why.
     * Only while a task is under way: a Buddy chat running, or another session that just acted. True if it paused.
     */
    boolean autoPause(int why) {
        if (paused) {
            pausedForBrowser = false; // paused for the browser, and now the phone too: the screen may have changed
            return false;
        }
        if (ChatHub.get(this).runningCount() == 0 && !control.recent(10_000)) return false;
        pause(why, why);
        return true;
    }

    /** The pause is the user using the background browser in a chat's live view (see pauseForBrowser). */
    private volatile boolean pausedForBrowser;

    /**
     * The user touched the background browser's live view while a chat works (main thread): pause like AutoPause does
     * for the phone, until they tap Continue or write. Not while a card waits: then the touch is them doing their part.
     */
    void pauseForBrowser(String key) {
        // not while they do what a question asks there (ask with browser_highlight): that's their part
        if (paused || (bubble != null && bubble.confirming()) || marks.waiting() || Browser.marks(key) != null) return;
        // only when an agent works in this very tab: signing in from Settings mustn't hold up another chat
        Sessions.Session ses = Browser.USER.equals(key) ? null : ChatHub.get(this).sessions.find(key);
        if (!Browser.agentBusy(key) || (ses != null && !ses.running)) return;
        pause(R.string.browser_paused_note, R.string.browser_paused_toast);
        pausedForBrowser = true;
    }

    boolean pausedForBrowser() {
        return paused && pausedForBrowser;
    }

    private void pause(int note, int toast) {
        if (paused) return;
        CodexLive.end(getString(R.string.live_ended_paused)); // the user takes the phone: the live call ends
        paused = true;
        pausedForBrowser = false;
        heldInPause = false;
        control.end();
        keepAwake.update();
        main.postDelayed(pauseExpired, PAUSE_MAX_MS);
        ChatHub hub = ChatHub.get(this);
        Sessions.Session ses = hub.latestRunning();
        if (ses != null) hub.note(ses, Bubble.Kind.STEP, getString(note));
        if (bubble != null) bubble.onPauseChanged();
        android.widget.Toast.makeText(this, toast, android.widget.Toast.LENGTH_SHORT).show();
    }

    /**
     * The user touched the screen outside Buddy while an agent operates the phone (AutoPause, main thread). While a card
     * waits the agent isn't acting anyway: it re-reads the screen after the answer. Typing in Buddy's panel or app
     * doesn't count.
     */
    void onScreenTouched() {
        if (bubble == null || paused || bubble.expanded()) return; // the keyboard over Buddy's panel, say
        // the agent waits on them (a card, marks, Android's installer): the touch is them doing their part
        if (bubble.confirming() || marks.waiting() || installWaits > 0 || installerInFront()) {
            cardTouches++;
            return;
        }
        if (getPackageName().equals(foregroundPkg()) || onlyRuleRuns()) return;
        autoPause(R.string.service_autopaused_touch);
    }

    /** Agents waiting for the user to confirm an install (wait_for_install, update_buddy): touches are theirs. */
    volatile int installWaits;

    /** Android's package installer is in front (its Install/Update dialog). */
    private boolean installerInFront() {
        String pkg = foregroundPkg();
        return pkg != null && pkg.contains("packageinstaller");
    }

    /** Only notification rules' runs are working: they start on their own, often while the user is on the phone. */
    private boolean onlyRuleRuns() {
        boolean any = false;
        for (Sessions.Session s : ChatHub.get(this).sessions.all) {
            if (!s.running) continue;
            if (!Triggers.ruleRun(s.id)) return false;
            any = true;
        }
        return any;
    }

    /** No Buddy chat is running anymore (main thread): a pause nothing waits in has nothing left to hold. */
    void onRunsEnded() {
        if (!paused) return;
        synchronized (pauseLock) {
            if (waiters > 0) return;
        }
        endPause();
    }

    /** Resume (main thread): waiting phone actions go on, after the agent re-reads the screen. */
    void resume() {
        if (!paused) return;
        if (!pausedForBrowser) rereadNeeded = true; // the phone wasn't touched when the pause was the browser's
        endPause();
        ChatHub hub = ChatHub.get(this);
        Sessions.Session ses = hub.latestRunning();
        if (ses != null) hub.note(ses, Bubble.Kind.STEP, getString(R.string.service_resumed_note));
    }

    /** A chat was stopped while paused (main thread): end the pause without letting waiting actions go ahead. */
    void stopPaused() {
        if (!paused) return;
        revokedUntil = android.os.SystemClock.elapsedRealtime() + 60_000;
        endPause();
    }

    private void endPause() {
        if (!paused) return;
        paused = false;
        heldInPause = false;
        main.removeCallbacks(pauseExpired);
        synchronized (pauseLock) {
            pauseLock.notifyAll();
        }
        autoPause.onPauseEnded();
        keepAwake.update();
        if (bubble != null) bubble.onPauseChanged();
        Triggers.get(this).wake(); // rule runs wait while paused
    }

    /**
     * For a phone tool (tool thread): while paused, wait for resume, at most PAUSE_WAIT_MS per call so agents
     * with a short tool timeout (Codex) don't fail the call. Null to go ahead, else the tool's answer.
     */
    String awaitResume(String tool) throws InterruptedException {
        return awaitResume(tool, true);
    }

    /**
     * As above; phone: a phone tool, which re-reads the screen first after the user used it. The background browser's
     * tools (phone false) have their own check for that (see Browser.takeTouched).
     */
    String awaitResume(String tool, boolean phone) throws InterruptedException {
        long until = android.os.SystemClock.elapsedRealtime() + PAUSE_WAIT_MS;
        if (paused && (phone || pausedForBrowser)) heldInPause = true;
        synchronized (pauseLock) {
            long left;
            waiters++;
            try {
                // the background browser doesn't use the screen: a pause for the phone doesn't hold it up
                while (paused && (phone || pausedForBrowser) && (left = until - android.os.SystemClock.elapsedRealtime()) > 0) {
                    pauseLock.wait(left);
                }
            } finally {
                waiters--;
            }
        }
        if (paused && pausedForBrowser) {
            return "PAUSED: the user is using the background browser in the live view. Nothing was done. Don't use other "
                    + "tools; call " + tool + " again to keep waiting until they tap Continue.";
        }
        if (paused && phone) {
            return "PAUSED: the user paused you to use the phone themselves. Nothing was done. Don't use other phone "
                    + "tools; call " + tool + " again to keep waiting until they resume.";
        }
        String revoked = controlRevoked();
        if (revoked != null) return revoked;
        if (rereadNeeded && phone) {
            if ("read_screen".equals(tool) || "screenshot".equals(tool)) rereadNeeded = false;
            else if (!"wait".equals(tool) && !"list_apps".equals(tool)) {
                return "SCREEN CHANGED: the user used the phone themselves (they paused you, or did something on the "
                        + "screen while you waited for their answer), so the screen may have changed and element ids are "
                        + "stale. Nothing was done. Call read_screen, then continue the task.";
            }
        }
        return null;
    }

    /** Short vibration to get the user's attention (e.g. "tap Update"). */
    void nudge() {
        try {
            android.os.Vibrator v = getSystemService(android.os.Vibrator.class);
            if (v != null) v.vibrate(android.os.VibrationEffect.createWaveform(new long[]{0, 60, 80, 60}, -1));
        } catch (Exception ignored) {
        }
    }

    /**
     * What a tool is doing right now, for the bubble's pill (see Bubble.setActivity); browserTab: the background
     * browser's tab it's in (Browser.key), or null.
     */
    void showActivity(final String text, final String browserTab) {
        main.post(() -> {
            if (bubble != null) bubble.setActivity(text, browserTab);
        });
    }

    void showStatus(final String text) {
        main.post(() -> bubble.setStatus(text));
    }

    void setBubblePassThrough(final boolean on) {
        final CountDownLatch done = new CountDownLatch(1);
        final boolean[] dimmed = {false};
        main.post(() -> {
            dimmed[0] = bubble.dimming();
            bubble.setPassThrough(on);
            marks.setPassThrough(on);
            done.countDown();
        });
        try {
            done.await(1, TimeUnit.SECONDS);
            if (on) Thread.sleep(dimmed[0] ? 350 : 120); // the compact panel's dimming fades out first
        } catch (InterruptedException ignored) {
        }
    }

    /**
     * Waits up to ms for a question's answer. Gives up sooner when the agent stops waiting (its tool call timed out or
     * it was stopped): a card nobody waits for would keep blocking every tap. Also as soon as stop has something to
     * say (see askQuestion).
     */
    private static boolean awaitAnswer(CountDownLatch done, long ms, java.util.function.Supplier<String> stop)
            throws InterruptedException {
        java.util.function.BooleanSupplier gone = McpServer.CALLER_GONE.get();
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            if (done.await(1, TimeUnit.SECONDS)) return true;
            if (gone != null && gone.getAsBoolean()) return false;
            if (stop != null && stop.get() != null) return false;
        }
        return false;
    }

    /** How a card that approved itself (a notification rule's countdown ran out) answers the agent. */
    static final String AUTO_APPROVED = "APPROVED after the countdown: the user didn't stop it, though they may not be "
            + "watching. Go ahead with this, and only this.";

    /** Blocks the calling (tool) thread until the user answers in the bubble. */
    String askConfirm(final String summary) throws InterruptedException {
        return askConfirm(summary, false);
    }

    /**
     * How an approval in a notification rule's run answers when the user isn't there: such a run doesn't wait on
     * them (everything else would wait behind it).
     */
    static final String NOT_NOW = "NOT ANSWERED: the user isn't answering right now, and a notification rule's run doesn't "
            + "wait for them. Don't do it; say in your last reply what you wanted to do. For a message, use "
            + "reply_notification instead: Buddy keeps the reply for them.";

    /** askRuleReply: the user didn't answer in time, so the reply goes to their Waiting list (the rest: why). */
    static final String LATER = "LATER:";
    /** askRuleReply: the conversation moved on while the card waited. */
    static final String STALE = "STALE";
    private static final String TIMED_OUT = "\u0000timed out";

    /**
     * As above. canAuto: the card may approve itself when its chat is a notification rule's run with a countdown
     * (sending a message may; paying, buying or deleting never does). In a rule's run the card waits a minute at most
     * after its countdown, and not at all while the phone is locked: then the answer is NOT_NOW.
     */
    String askConfirm(final String summary, boolean canAuto) throws InterruptedException {
        final String chat = McpServer.CALLER_CHAT.get();
        boolean rule = Triggers.ruleRun(chat);
        int cd = canAuto ? Triggers.countdown(chat) : 0;
        String shown = summary;
        if (cd > 0) { // a second agent checks it against the rule first; if it objects, the card waits for the user
            showStatus(getString(R.string.rules_status_checking));
            String why = Triggers.review(this, chat, summary);
            if (why != null) {
                cd = 0;
                shown = getString(R.string.tools_card_detail, summary, why);
            }
        }
        if (rule && cd == 0 && Triggers.locked(this)) return NOT_NOW;
        String answer = card(shown, chat, cd, rule ? (cd + 60) * 1000L : Math.max(5 * 60_000L, (cd + 60) * 1000L), null);
        if (!TIMED_OUT.equals(answer)) return answer;
        return rule ? NOT_NOW : "DENIED: the user did not answer within 5 minutes. Do not do it.";
    }

    /**
     * A notification rule's reply, for the user's OK, without making everything else wait on them: a countdown card
     * after the second check, or else a card that waits a minute at most, and only while the phone is in use.
     * Returns askConfirm's answers, LATER + the second check's objection ("" if none) when the user didn't answer in
     * time, or STALE when stale turns true first (they wrote again, or the user answered them).
     */
    String askRuleReply(String summary, String chat, java.util.function.BooleanSupplier stale) throws InterruptedException {
        int cd = Triggers.countdown(chat);
        String shown = summary, why = "";
        if (cd > 0) {
            showStatus(getString(R.string.rules_status_checking));
            String objection = Triggers.review(this, chat, summary);
            if (objection != null) {
                cd = 0;
                why = objection;
                shown = getString(R.string.tools_card_detail, summary, objection);
            }
        }
        if (stale.getAsBoolean()) return STALE;
        if (cd == 0 && Triggers.locked(this)) return LATER + why;
        String answer = card(shown, chat, cd, (cd + 60) * 1000L, stale);
        if (TIMED_OUT.equals(answer)) return LATER + why;
        return answer;
    }

    /**
     * Shows an approval card and waits for its answer, at most waitMs (TIMED_OUT, or sooner when the agent stops
     * waiting) or until stale turns true (STALE); the card is then taken down. countdown: seconds after which it
     * approves itself (0: none).
     */
    private String card(String text, String chat, int countdown, long waitMs, java.util.function.BooleanSupplier stale)
            throws InterruptedException {
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicReference<Boolean> answer = new AtomicReference<>();
        final AtomicReference<String> change = new AtomicReference<>();
        final java.util.concurrent.atomic.AtomicBoolean auto = new java.util.concurrent.atomic.AtomicBoolean();
        final int touches = cardTouches;
        final Bubble.ConfirmCallback cb = new Bubble.ConfirmCallback() {
            @Override
            public void onAnswer(boolean approved, String wanted) {
                answer.set(approved);
                change.set(wanted);
                done.countDown();
            }

            @Override
            public void onAutoApproved() {
                auto.set(true);
                onAnswer(true, null);
            }
        };
        main.post(() -> bubble.askConfirm(text, chat, countdown, cb));
        Triggers.approvalShown(this, chat, text, countdown);
        String ended = null;
        try {
            java.util.function.BooleanSupplier gone = McpServer.CALLER_GONE.get();
            long end = System.currentTimeMillis() + waitMs;
            while (ended == null && !done.await(1, TimeUnit.SECONDS)) {
                if (stale != null && stale.getAsBoolean()) ended = STALE;
                else if (System.currentTimeMillis() >= end || gone != null && gone.getAsBoolean()) ended = TIMED_OUT;
            }
        } finally {
            Triggers.approvalDone(this, chat);
        }
        if (cardTouches != touches) rereadNeeded = true; // they used the screen meanwhile: ids may be stale
        if (ended != null) {
            main.post(() -> bubble.cancelConfirm(cb));
            return ended;
        }
        if (auto.get()) return AUTO_APPROVED;
        if (change.get() != null) {
            return "CHANGES REQUESTED by the user: \u201c" + change.get() + "\u201d. Don't do it as it was; "
                    + "make these changes (for example, rewrite the text), then call confirm again with the "
                    + "updated version and wait for the answer.";
        }
        return Boolean.TRUE.equals(answer.get())
                ? "APPROVED by the user. Go ahead."
                : "DENIED by the user. Do not do it; ask what they want instead.";
    }

    /**
     * Blocks the calling (tool) thread until the user picks an option or types an answer in the bubble. handsOn: the
     * user has a step to do on the screen first (the question comes with marks): the card waits next to the bubble
     * instead of opening over the screen. stepDone (may be null) says once Buddy saw them do that step: then the
     * question closes without waiting for an answer.
     */
    String askQuestion(final String question, final java.util.List<String> options, boolean handsOn,
                       java.util.function.Supplier<String> stepDone) throws InterruptedException {
        final CountDownLatch done = new CountDownLatch(1);
        final AtomicReference<Boolean> picked = new AtomicReference<>();
        final AtomicReference<String> answer = new AtomicReference<>();
        final String chat = McpServer.CALLER_CHAT.get();
        boolean rule = Triggers.ruleRun(chat);
        if (rule && Triggers.locked(this)) {
            return "NO ANSWER: the user isn't on the phone right now, and a notification rule's run doesn't wait for "
                    + "them. Don't guess; say in your last reply what you need to know.";
        }
        final int touches = cardTouches;
        final Bubble.ConfirmCallback cb = (fromButton, text) -> {
            picked.set(fromButton);
            answer.set(text);
            done.countDown();
        };
        main.post(() -> bubble.askQuestion(question, options, chat, handsOn, cb));
        Triggers.approvalShown(this, chat, question, 0);
        boolean answered;
        try {
            answered = awaitAnswer(done, rule ? 60_000L : 5 * 60_000L, stepDone);
        } finally {
            Triggers.approvalDone(this, chat);
        }
        boolean usedScreen = handsOn || cardTouches != touches;
        if (usedScreen) rereadNeeded = true;
        if (!answered) {
            main.post(() -> bubble.cancelConfirm(cb));
            String step = stepDone == null ? null : stepDone.get();
            if (step != null) {
                return step + " Buddy saw it and closed the question for them. Call read_screen before going on.";
            }
            return "NO ANSWER: the user did not answer within " + (rule ? "a minute" : "5 minutes")
                    + ". Don't guess; stop and ask in your reply.";
        }
        if (answer.get() == null) {
            return "NO ANSWER: the user closed the question. Don't guess; stop and ask what they want.";
        }
        String then = usedScreen ? " The user may have changed the screen meanwhile: call read_screen before going on." : "";
        return (Boolean.TRUE.equals(picked.get())
                ? "ANSWER: \u201c" + answer.get() + "\u201d"
                : "ANSWER (typed by the user): \u201c" + answer.get() + "\u201d") + then;
    }
}
