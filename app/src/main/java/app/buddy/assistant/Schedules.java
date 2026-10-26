package app.buddy.assistant;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Scheduled tasks: a chat's "Schedule" (Chat options) sends its saved message into the chat at a time of day, on the
 * days picked, as if the user typed it: same agent, same chat options, the same approval cards. An exact alarm when
 * Android allows Buddy one, otherwise an inexact one (a few minutes late at most, usually); set again at boot, after
 * an update and when the clock or time zone changes.
 *
 * At its time the task starts, also with the phone locked: the background browser and Linux tools work without the
 * screen, and a partial wake lock lets the run finish with the screen off. Only when the agent needs the screen while
 * the phone is locked does the run wait (see awaitUnlock): a notification asks the user to unlock (with "Wake the
 * phone for this task" the screen comes on too, see WakeActivity), and after an hour without that the run stops. A chat that's still working, an agent that isn't signed in, or Buddy's service being
 * off skips the task, with a notification; another chat at work, a pause or no internet make it wait, for an hour at
 * most. Main thread, except awaitUnlock (a phone tool's thread).
 */
public final class Schedules extends BroadcastReceiver {
    /** A chat's schedule: when, and what to send. */
    static final class Plan {
        /** Days of the week as bits (1 << (Calendar.DAY_OF_WEEK - 1)), Sunday first. */
        static final int EVERY_DAY = 0x7F, WEEKDAYS = 0x3E;
        /** Minutes after midnight, the phone's time. */
        int minute = 9 * 60;
        int days = EVERY_DAY;
        /** The message, sent as the user's own. */
        String text = "";
        /**
         * Wake the phone for this task: when the run needs the screen while the phone sleeps, the screen comes on, and
         * a lock screen without a PIN, pattern or fingerprint goes (see WakeActivity). Off unless the user turns it on.
         */
        boolean wake;

        Plan copy() {
            Plan p = new Plan();
            p.minute = minute;
            p.days = days;
            p.text = text;
            p.wake = wake;
            return p;
        }

        JSONObject toJson() {
            try {
                return new JSONObject().put("minute", minute).put("days", days).put("text", text).put("wake", wake);
            } catch (Exception e) {
                return new JSONObject();
            }
        }

        static Plan of(JSONObject o) {
            if (o == null || o.optString("text").trim().isEmpty() || (o.optInt("days") & EVERY_DAY) == 0) return null;
            Plan p = new Plan();
            p.minute = Math.max(0, Math.min(24 * 60 - 1, o.optInt("minute")));
            p.days = o.optInt("days") & EVERY_DAY;
            p.text = o.optString("text");
            p.wake = o.optBoolean("wake");
            return p;
        }

        boolean on(int dayOfWeek) {
            return (days & (1 << (dayOfWeek - 1))) != 0;
        }

        /** The first time after this moment (wall clock), or 0 with no days picked. */
        long next(long after) {
            Calendar c = Calendar.getInstance();
            c.setTimeInMillis(after);
            for (int i = 0; i < 8; i++) {
                c.set(Calendar.HOUR_OF_DAY, minute / 60); // each day again: a clock change can move it
                c.set(Calendar.MINUTE, minute % 60);
                c.set(Calendar.SECOND, 0);
                c.set(Calendar.MILLISECOND, 0);
                if (c.getTimeInMillis() > after && on(c.get(Calendar.DAY_OF_WEEK))) return c.getTimeInMillis();
                c.add(Calendar.DAY_OF_YEAR, 1);
            }
            return 0;
        }

        /** The time of day in the user's format ("14:00", "2:00 PM"). */
        String time(Context c) {
            Calendar t = Calendar.getInstance();
            t.set(Calendar.HOUR_OF_DAY, minute / 60);
            t.set(Calendar.MINUTE, minute % 60);
            return android.text.format.DateFormat.getTimeFormat(c).format(t.getTime());
        }

        /** "Every day at 14:00", "Weekdays at 14:00", "Mon, Thu at 14:00". */
        String summary(Context c) {
            if (days == EVERY_DAY) return c.getString(R.string.schedule_summary_daily, time(c));
            if (days == WEEKDAYS) return c.getString(R.string.schedule_summary_weekdays, time(c));
            String[] names = new java.text.DateFormatSymbols(Ui.locale(c)).getShortWeekdays();
            StringBuilder b = new StringBuilder();
            for (int d : week()) if (on(d)) b.append(b.length() > 0 ? ", " : "").append(names[d]);
            return c.getString(R.string.schedule_summary_days, b.toString(), time(c));
        }

        /** For the agent: "every day at 14:00", in English like the rest of its instructions. */
        String english() {
            String at = String.format(java.util.Locale.ROOT, " at %02d:%02d", minute / 60, minute % 60);
            if (days == EVERY_DAY) return "every day" + at;
            if (days == WEEKDAYS) return "on weekdays" + at;
            String[] names = new java.text.DateFormatSymbols(java.util.Locale.ENGLISH).getShortWeekdays();
            StringBuilder b = new StringBuilder("on ");
            for (int d = Calendar.SUNDAY; d <= Calendar.SATURDAY; d++) if (on(d)) b.append(b.length() > 3 ? ", " : "").append(names[d]);
            return b + at;
        }
    }

    /** The days of the week in the order of the user's calendar (Calendar.DAY_OF_WEEK values). */
    static int[] week() {
        int first = Calendar.getInstance().getFirstDayOfWeek();
        int[] out = new int[7];
        for (int i = 0; i < 7; i++) out[i] = (first - 1 + i) % 7 + 1;
        return out;
    }

    private static final String ACTION_FIRE = "app.buddy.assistant.SCHEDULE", CHANNEL = "schedules", TAG = "schedule";
    /** How long a task waits for Buddy to be free, or a run for the user to unlock, before giving up. */
    private static final long WAIT_MS = 3600_000L, LOOK_MS = 30_000L, UNLOCK_WAIT_MS = 45_000L;
    private static final Handler main = new Handler(Looper.getMainLooper());
    /** Tasks whose time came while Buddy was busy: chat id → when they're given up (elapsedRealtime). Main thread. */
    private static final Map<String, Long> waiting = new LinkedHashMap<>();
    /** Chats in a scheduled task's run (the phone tools look them up from their own threads). */
    private static final Set<String> running = ConcurrentHashMap.newKeySet();
    /** Runs waiting for the user to unlock, by chat id: until when (elapsedRealtime). */
    private static final Map<String, Long> needScreen = new ConcurrentHashMap<>();
    private static PowerManager.WakeLock wake;
    private static Context app;
    private static boolean pumping;

    @Override
    public void onReceive(Context c, Intent i) {
        if (ACTION_FIRE.equals(i.getAction())) fire(c.getApplicationContext(), i.getStringExtra("id"), i.getLongExtra("at", 0));
    }

    /** Boot, an update of Buddy, a clock or time zone change, exact alarms allowed: every chat's alarm is set again. */
    public static final class Boot extends BroadcastReceiver {
        @Override
        public void onReceive(Context c, Intent i) {
            sync(c.getApplicationContext());
        }
    }

    // ---------------------------------------------------------------- alarms

    /** Sets every scheduled chat's next alarm (main thread). */
    static void sync(Context c) {
        for (Sessions.Session s : new ArrayList<>(ChatHub.get(c).sessions.all)) if (s.schedule != null) set(c, s);
    }

    /** Sets the chat's next alarm, or cancels it when the chat has no schedule. */
    static void set(Context c, Sessions.Session s) {
        set(c, s, System.currentTimeMillis());
    }

    private static void set(Context c, Sessions.Session s, long after) {
        AlarmManager am = c.getSystemService(AlarmManager.class);
        long at = s.schedule == null ? 0 : s.schedule.next(after);
        if (at == 0) {
            cancel(c, s.id);
            return;
        }
        PendingIntent pi = alarm(c, s.id, at);
        if (exact(c)) {
            try {
                am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
                return;
            } catch (SecurityException ignored) { // taken back meanwhile: the inexact one below
            }
        }
        am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi);
    }

    /** The chat's alarm goes (its schedule was removed, or the chat deleted), and whatever of it waited. */
    static void cancel(Context c, String id) {
        c.getSystemService(AlarmManager.class).cancel(alarm(c, id, 0));
        waiting.remove(id);
        clearNotice(c, id);
    }

    private static PendingIntent alarm(Context c, String id, long at) {
        // one alarm per chat: told apart by its data, which the extras don't do
        Intent i = new Intent(c, Schedules.class).setAction(ACTION_FIRE).setData(Uri.parse("buddy-schedule:" + id))
                .putExtra("id", id).putExtra("at", at);
        return PendingIntent.getBroadcast(c, 0, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }

    /** May Buddy set exact alarms? (Android 12+ asks the user; without, the alarm may come a few minutes late.) */
    static boolean exact(Context c) {
        return android.os.Build.VERSION.SDK_INT < 31 || c.getSystemService(AlarmManager.class).canScheduleExactAlarms();
    }

    /** A moment as the user reads it: weekday and time ("Tue 14:00"). */
    static String when(Context c, long at) {
        return android.text.format.DateUtils.formatDateTime(c, at, android.text.format.DateUtils.FORMAT_SHOW_WEEKDAY
                | android.text.format.DateUtils.FORMAT_ABBREV_WEEKDAY | android.text.format.DateUtils.FORMAT_SHOW_TIME);
    }

    // ------------------------------------------------------------------- runs

    /** A chat's time came: its next alarm is set at once, then the task starts, or waits until Buddy is free. */
    private static void fire(Context c, String id, long at) {
        Sessions.Session s = ChatHub.get(c).sessions.find(id);
        if (s == null || s.schedule == null) { // deleted meanwhile
            c.getSystemService(AlarmManager.class).cancel(alarm(c, String.valueOf(id), 0));
            return;
        }
        set(c, s, Math.max(System.currentTimeMillis(), at)); // never this same time again
        if (waiting.containsKey(id)) return; // yesterday's still waits? It can't: it gives up after an hour
        waiting.put(id, SystemClock.elapsedRealtime() + WAIT_MS);
        listen(c);
        pump();
    }

    private static final Runnable look = Schedules::pump;

    /** Starts what waits, skips what can't run, gives up on what waited too long. */
    private static void pump() {
        if (pumping || app == null) return;
        pumping = true;
        try {
            ChatHub hub = ChatHub.get(app);
            long now = SystemClock.elapsedRealtime();
            for (String id : new ArrayList<>(waiting.keySet())) {
                Long until = waiting.get(id);
                Sessions.Session s = hub.sessions.find(id);
                if (until == null || s == null || s.schedule == null) {
                    waiting.remove(id);
                    continue;
                }
                BuddyService svc = BuddyService.get();
                String skip = svc == null ? app.getString(R.string.rules_log_no_service) // its server is how the agent works
                        : !SetupState.signedIn(app, s.backend) ? app.getString(R.string.rules_log_not_signed_in, s.brainName())
                        : s.running ? app.getString(R.string.schedule_skip_running) : null;
                if (skip == null && busy(hub, svc)) {
                    if (now < until) continue;
                    skip = app.getString(R.string.schedule_skip_busy);
                }
                waiting.remove(id);
                if (skip != null) notice(app, s, app.getString(R.string.schedule_skipped_title, s.label()), skip);
                else start(s);
            }
            main.removeCallbacks(look);
            if (!waiting.isEmpty()) main.postDelayed(look, LOOK_MS);
        } finally {
            pumping = false;
        }
    }

    /**
     * Another chat at work (they'd share the screen), a pause, Stop's minute, a card waiting for the user or no
     * internet: the task waits, as notification rules' runs do.
     */
    private static boolean busy(ChatHub hub, BuddyService svc) {
        return hub.runningCount() > 0 || svc.paused() || (svc.bubble() != null && svc.bubble().confirming())
                || svc.controlRevoked() != null || !online();
    }

    private static void start(Sessions.Session s) {
        Plan p = s.schedule;
        boolean locked = Triggers.locked(app);
        StringBuilder prompt = new StringBuilder("[BUDDY_SCHEDULED] The user set this chat to send the message below ")
                .append(p.english()).append(", and Buddy sent it now on its own: they may not be watching. Do it as ")
                .append("if they had just typed it; approval cards still wait for them.\n");
        if (locked) {
            prompt.append("The phone is locked right now. The background browser and Linux tools work as usual; a ")
                    .append("phone tool that needs the screen asks the user to unlock and waits for them, so use the ")
                    .append("screen only when the task needs it.\n");
        }
        prompt.append('\n').append(p.text);
        String err = ChatHub.get(app).sendScheduled(s, p.text, prompt.toString());
        if (err != null) {
            notice(app, s, app.getString(R.string.schedule_skipped_title, s.label()), err);
            return;
        }
        clearNotice(app, s.id);
        running.add(s.id);
        holdWake();
    }

    /** Is there a network to reach the agent's model? */
    private static boolean online() {
        try {
            ConnectivityManager cm = app.getSystemService(ConnectivityManager.class);
            NetworkCapabilities nc = cm.getNetworkCapabilities(cm.getActiveNetwork());
            return nc != null && nc.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET);
        } catch (Exception e) {
            return true;
        }
    }

    /** Runs that ended let the wake lock go, and maybe a waiting task start. */
    private static final ChatHub.Listener hubListener = new ChatHub.Listener() {
        @Override
        public void onChatsChanged() {
            if (!running.isEmpty()) {
                ChatHub hub = ChatHub.get(app);
                for (String id : new ArrayList<>(running)) {
                    Sessions.Session s = hub.sessions.find(id);
                    if (s != null && s.running && s.scheduledRun) continue;
                    running.remove(id);
                    if (needScreen.remove(id) != null) clearNotice(app, id);
                }
                if (running.isEmpty()) {
                    releaseWake();
                    WakeActivity.close();
                }
            }
            if (!waiting.isEmpty()) pump();
        }

        @Override
        public void onMessage(Sessions.Session ses, Sessions.Msg msg) {
        }
    };

    private static void listen(Context c) {
        if (app != null) return;
        app = c.getApplicationContext();
        ChatHub.get(app).addListener(hubListener);
    }

    /** The service started (after boot, an update, or being switched on): alarms are set again (main thread). */
    static void serviceStarted(Context c) {
        listen(c);
        sync(c);
    }

    // --------------------------------------------------- the screen while locked

    /**
     * A phone tool that needs the screen was called while the phone is locked (a phone tool's thread). In a scheduled
     * task's run, the user is asked to unlock with a notification and the tool waits a little for that: null once
     * they have (the tool goes on), otherwise what the agent is told. Null too outside such runs, where the tools say
     * the phone is locked as before.
     */
    static String awaitUnlock(Context c, String chat, String tool) throws InterruptedException {
        if (chat == null || !running.contains(chat) || !Triggers.locked(c)) return null;
        long now = SystemClock.elapsedRealtime();
        Long until = needScreen.get(chat);
        if (until == null) {
            needScreen.put(chat, until = now + WAIT_MS);
            final long end = System.currentTimeMillis() + WAIT_MS;
            main.post(() -> {
                Sessions.Session s = ChatHub.get(c).sessions.find(chat);
                if (s != null && needScreen.containsKey(chat)) {
                    if (s.schedule != null && s.schedule.wake) wakeScreen(c);
                    notice(c, s, c.getString(R.string.schedule_screen_title),
                            c.getString(R.string.schedule_screen_text, s.label(), android.text.format.DateFormat.getTimeFormat(c)
                                    .format(new java.util.Date(end))));
                }
            });
        }
        long stop = Math.min(until, now + UNLOCK_WAIT_MS);
        while (Triggers.locked(c) && SystemClock.elapsedRealtime() < stop) {
            Thread.sleep(1000);
            main.post(() -> ChatHub.get(c).onToolActivity()); // waiting for the user isn't the agent hanging
        }
        if (!Triggers.locked(c)) {
            needScreen.remove(chat);
            main.post(() -> {
                clearNotice(c, chat);
                WakeActivity.close(); // still over the screen the agent is about to use
            });
            return null;
        }
        if (SystemClock.elapsedRealtime() < until) {
            return "LOCKED: this is a scheduled task and the phone is locked, so the screen can't be used. Buddy asked "
                    + "the user to unlock it. Nothing was done. Call " + tool + " again to keep waiting for them, or "
                    + "finish what you can without the screen.";
        }
        main.post(() -> { // an hour without the user: the run stops, and says so once
            Sessions.Session s = ChatHub.get(c).sessions.find(chat);
            if (s == null || !needScreen.containsKey(chat)) return;
            needScreen.remove(chat);
            s.scheduledRun = false; // this notification instead of the usual one at the end
            ChatHub.get(c).stop(s);
            notice(c, s, c.getString(R.string.schedule_stopped_title, s.label()), c.getString(R.string.schedule_skip_screen));
        });
        return "STOPPED: the phone stayed locked for an hour, so this scheduled task is stopped. Don't call any more tools.";
    }

    // ---------------------------------------------------------- notifications

    /** One notification per chat, opening it: waiting for the screen, skipped or stopped. */
    private static void notice(Context c, Sessions.Session s, String title, String text) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel(CHANNEL, c.getString(R.string.schedule_channel),
                NotificationManager.IMPORTANCE_DEFAULT));
        PendingIntent open = PendingIntent.getActivity(c, s.id.hashCode(), new Intent(c, ChatActivity.class)
                        .putExtra("id", s.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(c, CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(new Notification.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .setAutoCancel(true)
                .build();
        try {
            nm.notify(TAG, s.id.hashCode(), n);
        } catch (SecurityException ignored) { // notifications off
        }
    }

    private static void clearNotice(Context c, String id) {
        c.getSystemService(NotificationManager.class).cancel(TAG, id.hashCode());
    }

    // ---------------------------------------------------------------- helpers

    /**
     * Turns the screen on for a run that needs it (WakeActivity). Buddy's accessibility service lets it start an
     * activity from the background; it never unlocks a PIN, pattern or fingerprint itself.
     */
    private static void wakeScreen(Context c) {
        try {
            c.startActivity(new Intent(c, WakeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_ACTIVITY_NO_ANIMATION | Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS));
        } catch (Exception ignored) { // the notification still asks the user to unlock
        }
    }

    /** The run keeps working with the screen off; an hour's wait for the user fits in, with time to finish. */
    private static void holdWake() {
        if (wake == null) {
            wake = app.getSystemService(PowerManager.class).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "buddy:schedule");
            wake.setReferenceCounted(false);
        }
        wake.acquire(WAIT_MS + 30 * 60_000L);
    }

    private static void releaseWake() {
        if (wake != null && wake.isHeld()) wake.release();
    }

    /** The chat's first message of the user's, to offer as the schedule's message (or ""). */
    static String firstMessage(Sessions.Session s) {
        List<Sessions.Msg> ms = s.messages;
        for (Sessions.Msg m : ms) if (m.kind == Bubble.Kind.USER && !m.text.trim().isEmpty()) return m.text.trim();
        return "";
    }
}
