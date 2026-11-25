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
 * and performs the screen actions the agent (Claude Code, Codex, OpenCode, Antigravity, Grok, Copilot or Pi) asks
 * for.
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

    Tools tools() {
        return tools;
    }
    private McpServer server;
    private AutoPause autoPause;
    private final KeepAwake keepAwake = new KeepAwake(this);
    private final WorkNotice workNotice = new WorkNotice(this);

    /** An outside session started or stopped working on the phone (main thread): the notification follows. */
    void outsideChanged() {
        workNotice.update();
    }
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
            if (!Flavor.FDROID) Updates.maybeCheck(BuddyService.this);
            main.postDelayed(this, 6 * 3600_000L);
        }
    };

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        Prefs.setAccessWanted(this, true); // the user has it on (see restoreAccess)
        Theme.load(this);
        orientation = getResources().getConfiguration().orientation;
        Models.set("claude", Prefs.claudeModels(this));
        Models.setClaudeDefault(Prefs.claudeDefault(this));
        Models.set("codex", Prefs.codexModels(this));
        Models.set("opencode", Prefs.opencodeModels(this));
        Models.set("agy", Prefs.agyModels(this));
        Models.set("grok", Prefs.grokModels(this));
        Models.set("copilot", Prefs.copilotModels(this));
        Models.set("pi", Prefs.piModels(this));
        new Thread(() -> Attachments.cleanup(this)).start();
        new Thread(() -> { // the services saved in Buddy: the key saved per agent by earlier builds, then fresh model lists
            Providers.migrate(this);
            Providers.rewrite(this); // fields added by this build, before any run reads them
            OnPhone.sync(this); // models on this phone as this build lists them (a context size changed)
            OnPhone.killOrphans(); // an engine left by Buddy's earlier process holds GBs
            McpServers.rewrite(this);
            Providers.refresh(this);
        }, "providers").start();
        tools = new Tools(this);
        autoPause = new AutoPause(this);
        marks = new Marks(this);
        bubble = new Bubble(this);
        control = new ControlIndicator(this);
        ChatHub.get(this).addListener(workNotice);
        workNotice.update(); // (clears one left by a process that was killed mid-run)
        bubble.show();
        McpServer.load(this); // the tokens of runs that outlived an earlier process of Buddy's
        server = new McpServer(Prefs.token(this), this);
        server.start();

        // Hide the bubble on the lock screen.
        IntentFilter f = new IntentFilter();
        f.addAction(Intent.ACTION_SCREEN_OFF);
        f.addAction(Intent.ACTION_SCREEN_ON);
        f.addAction(Intent.ACTION_USER_PRESENT);
        registerReceiver(lockReceiver, f);
        // The same from the screen itself. A background chat's display has a power state of its own, and while one is
        // up Android may not announce that the real screen went off: the bubble would then stay on the lock screen.
        getSystemService(android.hardware.display.DisplayManager.class).registerDisplayListener(screenListener, main);
        // Approve and Deny on an approval's notification (the lock screen's way to answer a card)
        IntentFilter cards = new IntentFilter(Triggers.ACTION_CARD);
        if (android.os.Build.VERSION.SDK_INT >= 33) registerReceiver(cardReceiver, cards, Context.RECEIVER_NOT_EXPORTED);
        else registerReceiver(cardReceiver, cards);
        bubble.setLocked(getSystemService(KeyguardManager.class).isKeyguardLocked());
        if (!Prefs.onboarded(this)) main.postDelayed(this::showGuide, 600);
        main.postDelayed(() -> RemoteSession.ensure(this), 4000);
        main.postDelayed(() -> SshServer.ensure(this), 4000);
        main.postDelayed(() -> CodexRemote.ensure(this), 4000);
        main.postDelayed(updateCheck, 20_000);
        Schedules.serviceStarted(this); // scheduled tasks' alarms, again after an update or a restart
        Events.start(this); // and the events that start them (charger, Wi-Fi, Bluetooth, the day's first unlock)
        HiddenMute.check(this); // apps an earlier Buddy silenced on a hidden screen are heard again
        DebugLock.start(this); // Wireless debugging back on when the phone switches it off
        // background mode's helper, when the phone was restarted and the user asked for that (not right away: the
        // phone is still busy starting up, and Wi-Fi may not be there yet)
        main.postDelayed(() -> Background.afterRestart(this), 20_000);
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
            if (Intent.ACTION_USER_PRESENT.equals(i.getAction())) {
                Background.afterRestart(BuddyService.this); // (it waits for the phone to be unlocked)
                bubble.setLocked(false);
                bubble.replayFace(); // an animated picture plays once each time the phone is unlocked (issue #68)
            } else if (Intent.ACTION_SCREEN_OFF.equals(i.getAction())) {
                bubble.setLocked(true);
                bubble.noticeWaitingCard(); // a card that was on screen when it went dark: its notification now
            } else {
                boolean locked = getSystemService(KeyguardManager.class).isKeyguardLocked();
                bubble.setLocked(locked);
                if (!locked) bubble.replayFace(); // (a phone without a lock screen: on is unlocked)
            }
        }
    };

    private boolean screenWasOn = true;
    private long lockCheckedAt;

    /** Is the phone's own screen on (not off, and not the always-on display)? */
    static boolean screenOn(Context c) {
        android.view.Display d = c.getSystemService(android.hardware.display.DisplayManager.class)
                .getDisplay(android.view.Display.DEFAULT_DISPLAY);
        return d == null || d.getState() == android.view.Display.STATE_ON;
    }

    private final android.hardware.display.DisplayManager.DisplayListener screenListener =
            new android.hardware.display.DisplayManager.DisplayListener() {
                @Override
                public void onDisplayAdded(int id) {
                }

                @Override
                public void onDisplayRemoved(int id) {
                }

                @Override
                public void onDisplayChanged(int id) {
                    if (id != android.view.Display.DEFAULT_DISPLAY || bubble == null) return;
                    boolean on = screenOn(BuddyService.this);
                    if (on == screenWasOn) return;
                    screenWasOn = on;
                    keepAwake.update();
                    if (on) {
                        bubble.setLocked(getSystemService(KeyguardManager.class).isKeyguardLocked());
                    } else {
                        bubble.setLocked(true);
                        bubble.noticeWaitingCard();
                    }
                }
            };

    /** The lock screen came or went (seen in the windows changing): the bubble follows, whoever did or didn't say so. */
    private void recheckLock() {
        long now = android.os.SystemClock.uptimeMillis();
        if (now - lockCheckedAt < 400) return;
        lockCheckedAt = now;
        bubble.setLocked(!screenOn(this) || getSystemService(KeyguardManager.class).isKeyguardLocked());
    }

    private final BroadcastReceiver cardReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent i) {
            // only Buddy's own notification can send this (the receiver isn't exported, the intent names the package)
            if (bubble != null) bubble.answerFromNotice(i.getStringExtra("chat"), i.getStringExtra("text"),
                    i.getBooleanExtra("ok", false), i.getStringExtra("option"));
            Triggers.approvalDone(BuddyService.this, i.getStringExtra("chat"));
        }
    };

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (bubble == null) return;
        int type = event.getEventType();
        if (type == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) { // only toasts; notifications aren't read here
            noteToast(event);
            return;
        }
        if (type == AccessibilityEvent.TYPE_VIEW_CLICKED) { // only while a mark waits for a tap (listenForClicks)
            marks.onClicked(event.getSource(), event.getEventTime());
            return;
        }
        if (type != AccessibilityEvent.TYPE_WINDOWS_CHANGED && type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return;
        if (!Background.hiddenChats().isEmpty()) HiddenMute.check(this); // another app in front on a hidden screen?
        recheckLock();
        int top = 0, bottom = 0;
        AccessibilityWindowInfo app = null;
        for (AccessibilityWindowInfo w : getWindows()) {
            if (w.getType() == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                Rect r = new Rect();
                w.getBoundsInScreen(r);
                if (!r.isEmpty()) {
                    top = r.top;
                    bottom = r.bottom;
                }
            } else if (w.getType() == AccessibilityWindowInfo.TYPE_APPLICATION
                    && (app == null || w.getLayer() > app.getLayer())) {
                app = w;
            }
        }
        if (type == AccessibilityEvent.TYPE_WINDOWS_CHANGED) bubble.onKeyboardTop(top, bottom);
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

    private String toast;
    private long toastAt;

    private synchronized void noteToast(AccessibilityEvent event) {
        if (!String.valueOf(event.getClassName()).contains("Toast") || getPackageName().contentEquals(String.valueOf(event.getPackageName()))) return;
        StringBuilder b = new StringBuilder();
        for (CharSequence c : event.getText()) if (c != null) b.append(b.length() > 0 ? " " : "").append(c);
        if (b.toString().trim().isEmpty()) return;
        toast = b.toString().trim();
        toastAt = android.os.SystemClock.elapsedRealtime();
    }

    /** The toast waiting to be told to the agent, if any. */
    synchronized String peekToast() {
        return toast;
    }

    /** The last toast if it's at most maxAgeMs old (toasts are told once, and last only seconds); forgets it either way. */
    synchronized String takeToast(long maxAgeMs) {
        String t = toast != null && android.os.SystemClock.elapsedRealtime() - toastAt <= maxAgeMs ? toast : null;
        toast = null;
        return t;
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

    /**
     * Android unbinds the service in an orderly way when it is switched off in Settings: the user's own doing, so
     * Buddy won't switch it back on. A force stop, an update or a crash ends the process without coming here.
     */
    @Override
    public boolean onUnbind(Intent intent) {
        Prefs.setAccessWanted(this, false);
        return super.onUnbind(intent);
    }

    private static final String SECURE = "android.permission.WRITE_SECURE_SETTINGS";
    private static final long RESTORE_EVERY_MS = 3 * 60_000L;
    /** When restoreAccess last wrote the setting (uptime ms): the service connects a moment later. */
    private static volatile long restoredAt;

    /**
     * Android dropped Buddy's accessibility service although the user had it on (after a force stop; on some phones
     * after an update or a crash): Buddy puts it back in Android's list itself. Only with the right background mode's
     * set-up gave it (WRITE_SECURE_SETTINGS; without it this does nothing and the user is asked as before), only when
     * the service last ended without the user switching it off (Prefs.accessWanted), and at most once every few
     * minutes, so a service Android keeps dropping isn't fought over. The other services in the list stay as they are.
     * True when it was put back: the service connects a moment later.
     */
    static boolean restoreAccess(Context c) {
        try {
            if (instance != null) return false;
            if (!Prefs.accessWanted(c)) return not("it was switched off by you");
            if (!Background.setUp(c)) return not("background mode isn't set up");
            if (!stoppedFromOutside(c)) return not("Buddy last ended with reason " + lastEnd(c) + ", not a stop from outside");
            long now = System.currentTimeMillis(), last = Prefs.accessRestoredAt(c);
            if (last <= now && now - last < RESTORE_EVERY_MS) return not("it was put back less than 3 minutes ago");
            if (c.checkSelfPermission(SECURE) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                restoreThroughHelper(c.getApplicationContext());
                return false; // (it answers a moment later: see accessRestoring)
            }
            android.content.ContentResolver cr = c.getContentResolver();
            String me = new android.content.ComponentName(c, BuddyService.class).flattenToString();
            String list = android.provider.Settings.Secure.getString(cr, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            boolean anyOn = android.provider.Settings.Secure.getInt(cr, android.provider.Settings.Secure.ACCESSIBILITY_ENABLED, 0) == 1;
            // no list although accessibility is on, or although Android names services that are: this phone doesn't
            // let Buddy read it (issue #41), and writing one with only Buddy in it would switch the user's others off
            if ((list == null || list.isEmpty()) && (anyOn || !c.getSystemService(android.view.accessibility.AccessibilityManager.class)
                    .getEnabledAccessibilityServiceList(android.accessibilityservice.AccessibilityServiceInfo.FEEDBACK_ALL_MASK).isEmpty())) {
                return false;
            }
            if (list == null || list.isEmpty()) { // read as empty: Buddy doesn't trust that enough to write a list of its
                restoreThroughHelper(c.getApplicationContext()); // own (early after a boot the checks above say nothing
                return false;                                    // yet); the helper reads Android's real list
            }
            if (list != null) for (String s : list.split(":")) {
                android.content.ComponentName n = android.content.ComponentName.unflattenFromString(s);
                // listed: it's on and Android will bind it, or it's on and dead, which only off and on again mends
                if (n != null && n.flattenToString().equals(me)) return false;
            }
            Prefs.setAccessRestoredAt(c, now); // (before writing: a write that fails isn't tried over and over either)
            if (!android.provider.Settings.Secure.putString(cr, android.provider.Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,
                    list + ":" + me)) return false;
            android.provider.Settings.Secure.putInt(cr, android.provider.Settings.Secure.ACCESSIBILITY_ENABLED, 1);
            restoredAt = android.os.SystemClock.uptimeMillis();
            return true;
        } catch (Exception e) { // (a phone that refuses the write after all: as before, the user switches it on)
            return false;
        }
    }

    /**
     * Did Buddy's process last end by a stop from outside (a force stop, the package stopped or updated)? That is when
     * Android takes the service out of its list by itself. After a crash or a kill it stays listed, so "not listed"
     * then means the user switched it off, in Settings, while Buddy wasn't running to notice (onUnbind never came):
     * that choice stands. Unknown (an older Android, no record) counts as no.
     */
    private static boolean stoppedFromOutside(Context c) {
        int why = lastEnd(c);
        // REASON_USER_REQUESTED, REASON_USER_STOPPED, REASON_PACKAGE_STATE_CHANGE, REASON_PACKAGE_UPDATED
        return why == 10 || why == 11 || why == 15 || why == 16;
    }

    /** Why Buddy's process last ended (ApplicationExitInfo's reason), or -1 when Android doesn't say. */
    private static int lastEnd(Context c) {
        if (android.os.Build.VERSION.SDK_INT < 30) return -1;
        try {
            java.util.List<android.app.ApplicationExitInfo> ends = c.getSystemService(android.app.ActivityManager.class)
                    .getHistoricalProcessExitReasons(c.getPackageName(), 0, 1);
            return ends.isEmpty() ? -1 : ends.get(0).getReason();
        } catch (Exception e) {
            return -1;
        }
    }

    private static volatile String notRestored;

    /** Why Buddy didn't switch its service back on by itself, for Check Buddy's report (English, like other reasons). */
    static String notRestored() {
        return notRestored;
    }

    private static boolean not(String why) {
        notRestored = why;
        return false;
    }

    private static final java.util.concurrent.atomic.AtomicBoolean helperRestoring = new java.util.concurrent.atomic.AtomicBoolean();

    /**
     * The same on phones that don't give Buddy that right (OxygenOS refuses the grant): background mode's helper runs
     * as the shell user, which may write the setting, and it outlives the force stop or update that dropped the
     * service. Only a helper that already runs is asked (starting one would open Settings on the user's screen), so
     * not after a restart of the phone. The helper reads Android's real list, also where an app can't (issue #41).
     */
    private static void restoreThroughHelper(Context c) {
        if (!helperRestoring.compareAndSet(false, true)) return;
        new Thread(() -> {
            try {
                android.content.ComponentName n = new android.content.ComponentName(c, BuddyService.class);
                String me = n.flattenToString(), brief = n.flattenToShortString(); // (Android lists either form)
                String cmd = "l=$(settings get secure enabled_accessibility_services); "
                        + "case \":$l:\" in *\":" + me + ":\"*|*\":" + brief + ":\"*) exit 3;; esac; "
                        + "if [ -z \"$l\" ] || [ \"$l\" = null ]; then n='" + me + "'; else n=\"$l:" + me + "\"; fi; "
                        + "settings put secure enabled_accessibility_services \"$n\" && settings put secure accessibility_enabled 1";
                String[] r = Background.execIfRunning(c, cmd, 10);
                // no helper, back meanwhile, or listed after all (exit 3): nothing was written, the limit isn't spent
                not(r == null ? "background mode's helper isn't running" : "the helper answered " + r[0].trim() + " " + r[1].trim());
                if (r == null || instance != null || "3".equals(r[0].trim())) return;
                Prefs.setAccessRestoredAt(c, System.currentTimeMillis());
                if ("0".equals(r[0].trim())) restoredAt = android.os.SystemClock.uptimeMillis();
            } catch (Exception ignored) { // as before: the user switches it on
            } finally {
                helperRestoring.set(false);
            }
        }, "buddy-access").start();
    }

    /** restoreAccess put the service back within the last seconds: it may not have connected yet. */
    static boolean accessRestoring() {
        return instance == null && (helperRestoring.get() // (the helper is being asked: Check Buddy waits for it)
                || (restoredAt > 0 && android.os.SystemClock.uptimeMillis() - restoredAt < 5000));
    }

    @Override
    public void onDestroy() {
        try {
            Background.stopWifiWait(this);
            Events.stop(this);
            DebugLock.stop(this);
            unregisterReceiver(lockReceiver);
            unregisterReceiver(cardReceiver);
            getSystemService(android.hardware.display.DisplayManager.class).unregisterDisplayListener(screenListener);
        } catch (Exception ignored) {
        }
        RemoteSession.stop();
        SshServer.stop();
        CodexRemote.stop();
        main.removeCallbacks(updateCheck);
        if (server != null) server.stop();
        if (autoPause != null) autoPause.stop();
        keepAwake.remove();
        ChatHub.get(this).removeListener(workNotice);
        workNotice.remove();
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

    /**
     * The same for another sign-in (Pi's to Claude, see SignInActivity): the first text on screen that matches, to
     * found (main thread), for up to 10 minutes or until stopCodeWatch.
     */
    private java.util.regex.Pattern otherCode;
    private java.util.function.Consumer<String> otherFound;
    private long otherUntil;
    private final Runnable otherWatch = new Runnable() {
        @Override
        public void run() {
            java.util.regex.Pattern p = otherCode;
            if (p == null || System.currentTimeMillis() > otherUntil) return;
            String code = null;
            try {
                for (android.view.accessibility.AccessibilityWindowInfo w : getWindows()) {
                    android.view.accessibility.AccessibilityNodeInfo r = w.getRoot();
                    if (r == null || getPackageName().contentEquals(String.valueOf(r.getPackageName()))) continue;
                    code = scanFor(p, r, new int[]{0});
                    if (code != null) break;
                }
            } catch (Exception ignored) {
            }
            java.util.function.Consumer<String> f = otherFound;
            if (code != null && f != null) {
                otherCode = null;
                otherFound = null;
                f.accept(code);
                return;
            }
            main.postDelayed(this, 700);
        }
    };

    void watchForCode(java.util.regex.Pattern p, java.util.function.Consumer<String> found) {
        otherCode = p;
        otherFound = found;
        otherUntil = System.currentTimeMillis() + 10 * 60_000L;
        main.removeCallbacks(otherWatch);
        main.postDelayed(otherWatch, 1500);
    }

    void stopCodeWatch() {
        otherCode = null;
        otherFound = null;
        main.removeCallbacks(otherWatch);
    }

    private static String scanFor(java.util.regex.Pattern p, android.view.accessibility.AccessibilityNodeInfo n, int[] seen) {
        if (n == null || ++seen[0] > 4000) return null;
        for (CharSequence t : new CharSequence[]{n.getText(), n.getContentDescription()}) {
            if (t == null) continue;
            java.util.regex.Matcher m = p.matcher(t);
            if (m.find()) return m.group();
        }
        for (int i = 0; i < n.getChildCount(); i++) {
            String c = scanFor(p, n.getChild(i), seen);
            if (c != null) return c;
        }
        return null;
    }

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
        Sessions.Session lite = Lite.caller(this); // a small model's chat: a dozen tools, more on request
        JSONArray all = ToolSwitch.filter(this, tools.list()); // (the ones the user turned off aren't offered)
        return lite != null ? Lite.filter(all, lite) : all;
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
            org.json.JSONArray lm = event.optJSONArray("claude_models");
            if (lm != null && lm.length() > 0) {
                Prefs.setClaudeModels(this, lm.toString(), event.optString("claude_default"));
                Models.set("claude", lm.toString());
                Models.setClaudeDefault(event.optString("claude_default"));
            }
            org.json.JSONArray cm = event.optJSONArray("codex_models");
            if (cm != null && cm.length() > 0) {
                Prefs.setCodexModels(this, cm.toString());
                Models.set("codex", cm.toString());
            }
            org.json.JSONArray om = event.optJSONArray("opencode_models");
            if (om != null && om.length() > 0) {
                Prefs.setOpencodeModels(this, om.toString());
                Models.set("opencode", om.toString());
            }
            org.json.JSONArray am = event.optJSONArray("agy_models");
            if (am != null && am.length() > 0) {
                Prefs.setAgyModels(this, am.toString());
                Models.set("agy", am.toString());
            }
            org.json.JSONArray gm = event.optJSONArray("grok_models");
            if (gm != null && gm.length() > 0) {
                Prefs.setGrokModels(this, gm.toString());
                Models.set("grok", gm.toString());
            }
            org.json.JSONArray pm = event.optJSONArray("copilot_models");
            if (pm != null && pm.length() > 0) {
                Prefs.setCopilotModels(this, pm.toString());
                Models.set("copilot", pm.toString());
            }
            org.json.JSONArray im = event.optJSONArray("pi_models");
            if (im != null && im.length() > 0) {
                Prefs.setPiModels(this, im.toString());
                Models.set("pi", im.toString());
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

    /** background: the call works on a background chat's own display, not on the phone's screen. */
    void onToolActivity(final String tool, final boolean background) {
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
                    && !tool.startsWith("browser_") && !background) { // neither of those is the phone's screen
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

    /** An agent is using the phone's own screen (the control chip shows). */
    boolean controlActive() {
        return control != null && control.active();
    }

    /** The control chip came or went (main thread): the notice for work off the screen goes or comes back. */
    void onControlChanged() {
        workNotice.update();
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
        workNotice.update();
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
        // every agent at work has a hidden screen of its own (background mode): the user's touches aren't in its way
        if (!anyOnRealScreen()) return;
        autoPause(R.string.service_autopaused_touch);
    }

    /** Is an agent working on the user's real screen: a chat without background mode, or a session from outside? */
    private boolean anyOnRealScreen() {
        if (Background.outsideOnRealScreenWithin(10 * 60_000)) return true; // (one may think for minutes between taps)
        for (Sessions.Session s : ChatHub.get(this).sessions.all) {
            if (!s.running || Triggers.ruleRun(s.id)) continue;
            if (!Background.on(this, s.id)) return true;
        }
        return false;
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

    /**
     * Resume from a notification's button: the shade it was tapped in closes first (Android leaves it open, the agent's
     * next step saw it, and closing it by hand was a touch that paused again), then the task goes on.
     */
    void resumeFromShade() {
        if (!paused) return;
        performGlobalAction(GLOBAL_ACTION_DISMISS_NOTIFICATION_SHADE);
        main.removeCallbacks(shadeResume);
        main.postDelayed(shadeResume, 600); // (once it's gone: the agent reads the screen again first)
    }

    /** Resume from a notification, due once the shade is closed; called off when this pause ends some other way. */
    private final Runnable shadeResume = this::resume;

    /** A chat was stopped while paused (main thread): end the pause without letting waiting actions go ahead. */
    void stopPaused() {
        if (!paused) return;
        revokedUntil = android.os.SystemClock.elapsedRealtime() + 60_000;
        endPause();
    }

    private void endPause() {
        if (!paused) return;
        main.removeCallbacks(shadeResume); // (a Resume tap was for this pause: not for the next one)
        HiddenNotice.pauseEnded(this); // the hidden bubble's notice loses its Resume
        paused = false;
        heldInPause = false;
        main.removeCallbacks(pauseExpired);
        synchronized (pauseLock) {
            pauseLock.notifyAll();
        }
        autoPause.onPauseEnded();
        keepAwake.update();
        if (bubble != null) bubble.onPauseChanged();
        workNotice.update();
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
            // only what acts on the screen by what was read there: a phone command, the notifications, the clipboard
            // or opening an app don't depend on it, and go ahead (the flag stays for the next tap)
            else if (ON_SCREEN.contains(tool)) {
                return "SCREEN CHANGED: the user used the phone themselves (they paused you, or did something on the "
                        + "screen while you waited for their answer), so the screen may have changed and element ids are "
                        + "stale. Nothing was done. Call read_screen, then continue the task.";
            }
        }
        return null;
    }

    /** Tools that act on what the agent last read on the screen (element ids, points), so a stale read misleads them. */
    private static final java.util.Set<String> ON_SCREEN = new java.util.HashSet<>(java.util.Arrays.asList(
            "tap", "long_press", "type_text", "scroll", "swipe", "press", "do_steps", "collect", "find"));

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
        final String chat = McpServer.CALLER_CHAT.get(); // (asked on the tool's own thread)
        main.post(() -> {
            if (bubble != null) bubble.setActivity(text, browserTab, chat);
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

    /** How a message that needs no card (a notification rule set to "Don't ask") is approved, after the second check. */
    static final String RULE_APPROVED = "APPROVED by the user's rule: it sends messages without asking them, and a second "
            + "AI checked this one against the rule. Nobody may be watching. Go ahead with this, and only this.";

    /** Was it approved without the user (a rule's countdown, or its "Don't ask")? Such an OK covers sending only. */
    static boolean selfApproved(String answer) {
        return AUTO_APPROVED.equals(answer) || RULE_APPROVED.equals(answer);
    }

    /** A rule set to "Don't ask" let this through: the chat says so. */
    private void noteRuleApproved(String chat, String summary) {
        runOnMain(() -> {
            ChatHub hub = ChatHub.get(this);
            Sessions.Session s = chat == null ? null : hub.sessions.find(chat);
            if (s != null) hub.note(s, Bubble.Kind.STEP, getString(R.string.card_note_rule_approved, summary.trim()));
        });
    }

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
     * How many cards of a chat wait for the user now (a chat with a team has its members' too): the one notification
     * on a locked phone goes only once none waits.
     */
    private static final java.util.Map<String, Integer> openCards = new java.util.HashMap<>();

    private static synchronized int cardsOpen(String chat, int by) {
        String k = String.valueOf(chat);
        int n = Math.max(0, (openCards.containsKey(k) ? openCards.get(k) : 0) + by);
        if (n == 0) openCards.remove(k);
        else openCards.put(k, n);
        return n;
    }

    /**
     * As above. canAuto: the card may approve itself when its chat is a notification rule's run with a countdown
     * (sending a message may; paying, buying or deleting never does). In a rule's run the card waits a minute at most
     * after its countdown, and not at all while the phone is locked: then the answer is NOT_NOW.
     */
    String askConfirm(final String summary, boolean canAuto) throws InterruptedException {
        final String chat = McpServer.CALLER_CHAT.get();
        // a helper only looks things up: no card for it, whatever the step
        if (Helpers.lookOnly(this, chat)) return "DENIED: " + Helpers.refuse("sending, posting, buying, deleting or changing things");
        boolean rule = Triggers.ruleRun(chat);
        int cd = canAuto ? Triggers.countdown(chat) : 0;
        boolean now = canAuto && Triggers.instant(chat); // the rule's "Don't ask": no card when the second check agrees
        String shown = summary;
        if (cd > 0 || now) { // a second agent checks it against the rule first; if it objects, the card waits for the user
            showStatus(getString(R.string.rules_status_checking));
            String why = Triggers.review(this, chat, summary);
            if (why != null) {
                cd = 0;
                now = false;
                shown = getString(R.string.tools_card_detail, summary, why);
            }
        }
        if (now) {
            noteRuleApproved(chat, summary);
            return RULE_APPROVED;
        }
        if (rule && cd == 0 && Triggers.locked(this)) return NOT_NOW;
        // (a team member's or a working sub-agent's: in the chat the user talks to, saying who wants it)
        String answer = card(Helpers.cardText(this, chat, shown), Helpers.cardChat(this, chat), cd,
                rule ? (cd + 60) * 1000L : Math.max(5 * 60_000L, (cd + 60) * 1000L), null);
        if (!TIMED_OUT.equals(answer)) return answer;
        return rule ? NOT_NOW : "DENIED: the user did not answer within 5 minutes. Do not do it.";
    }

    /**
     * A notification rule's reply, for the user's OK, without making everything else wait on them: a countdown card
     * after the second check (no card at all when the rule says "Don't ask"), or else a card that waits a minute at
     * most, and only while the phone is in use.
     * Returns askConfirm's answers, LATER + the second check's objection ("" if none) when the user didn't answer in
     * time, or STALE when stale turns true first (they wrote again, or the user answered them).
     */
    String askRuleReply(String summary, String chat, java.util.function.BooleanSupplier stale) throws InterruptedException {
        int cd = Triggers.countdown(chat);
        boolean now = Triggers.instant(chat);
        String shown = summary, why = "";
        if (cd > 0 || now) {
            showStatus(getString(R.string.rules_status_checking));
            String objection = Triggers.review(this, chat, summary);
            if (objection != null) {
                cd = 0;
                now = false;
                why = objection;
                shown = getString(R.string.tools_card_detail, summary, objection);
            }
        }
        if (stale.getAsBoolean()) return STALE;
        if (now) {
            noteRuleApproved(chat, summary);
            return RULE_APPROVED;
        }
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
        // from outside Buddy: the card says which computer asks, and Stop on that session denies it
        final String key = Background.outsideCaller();
        final String from = McpServer.CALLER_REMOTE.get();
        final boolean phoneOnly = WebDevices.cardShown(text); // (before it shows: a paired browser never sees it)
        main.post(() -> bubble.askConfirm(text, chat, countdown, cb, key, from));
        Triggers.approvalShown(this, chat, text, countdown, true, null);
        cardsOpen(chat, 1);
        String ended = null;
        try {
            java.util.function.BooleanSupplier gone = McpServer.CALLER_GONE.get();
            long end = System.currentTimeMillis() + waitMs;
            while (ended == null && !done.await(1, TimeUnit.SECONDS)) {
                if (stale != null && stale.getAsBoolean()) ended = STALE;
                else if (System.currentTimeMillis() >= end || gone != null && gone.getAsBoolean()) ended = TIMED_OUT;
            }
        } finally {
            if (cardsOpen(chat, -1) == 0) Triggers.approvalDone(this, chat);
        }
        if (cardTouches != touches) rereadNeeded = true; // they used the screen meanwhile: ids may be stale
        if (ended != null) {
            main.post(() -> {
                bubble.cancelConfirm(cb);
                if (phoneOnly) WebDevices.cardGone(text);
            });
            return ended;
        }
        if (phoneOnly) WebDevices.cardGone(text); // (answered: it's off the bubble)
        if (auto.get()) return AUTO_APPROVED;
        if (change.get() != null) {
            return "CHANGES REQUESTED by the user: \u201c" + change.get() + "\u201d. Don't do it as it was; "
                    + "make these changes (for example, rewrite the text), then call confirm again with the "
                    + "updated version and wait for the answer.";
        }
        String stopped = key == null || Boolean.TRUE.equals(answer.get()) ? null : Background.outsideStopped(key);
        if (stopped != null) return stopped; // denied by the user's Stop on the whole session, not just this card
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
        final String key = Background.outsideCaller();
        final String from = McpServer.CALLER_REMOTE.get();
        main.post(() -> bubble.askQuestion(question, options, chat, handsOn, cb, key, from));
        Triggers.approvalShown(this, chat, question, 0, false, handsOn ? null : options);
        cardsOpen(chat, 1);
        boolean answered;
        try {
            answered = awaitAnswer(done, rule ? 60_000L : 5 * 60_000L, stepDone);
        } finally {
            if (cardsOpen(chat, -1) == 0) Triggers.approvalDone(this, chat);
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
            String stopped = key == null ? null : Background.outsideStopped(key);
            if (stopped != null) return stopped; // the user's Stop on the whole session closed it
            return "NO ANSWER: the user closed the question. Don't guess; stop and ask what they want.";
        }
        String then = usedScreen ? " The user may have changed the screen meanwhile: call read_screen before going on." : "";
        return (Boolean.TRUE.equals(picked.get())
                ? "ANSWER: \u201c" + answer.get() + "\u201d"
                : "ANSWER (typed by the user): \u201c" + answer.get() + "\u201d") + then;
    }
}
