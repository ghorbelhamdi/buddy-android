package app.buddy.assistant;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebView;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Live voice (any agent's chat, with Codex signed in; both builds): a two-way ChatGPT voice call through Codex's app
 * server, like {@link CodexDictation}'s, where the user talks to the chat's agent (Claude, Codex, …) out loud and hears its answers. The voice is only
 * the voice: each thing the user says goes to the chat as if typed (a new task, or a note for the one under way), so
 * the chat's agent answers and does the work with Buddy's phone tools, its approval cards and Guard exactly as always;
 * what the agent says goes back to the call for the voice to say. (The voice's own hand-off tool is unreliable for short
 * commands, and it then claims things it never did, so Buddy doesn't depend on it: a hand-off is taken like the rest.)
 * One call at a time; it ends with End (the chat's live bar or the notification), when the screen goes off, when Buddy
 * is paused or stopped, or after a while with nothing said. Every call logs what happens to ~/.buddy/live.log in
 * Buddy's Linux.
 */
final class CodexLive {
    static final String ACTION_END = "app.buddy.assistant.END_LIVE";
    /** Marks the voice's own lines in the chat (the agent's replies have none). */
    static final String VOICE_MARK = "🔊 ";
    private static final String AGENT = "[Phone agent] ";
    private static final int NOTIFICATION_ID = 7;
    private static final long CONNECT_MS = 30_000, IDLE_MS = 5 * 60_000;
    /** No new words for this long after the user spoke: they're done, even if the call hasn't said so yet. */
    private static final long PAUSE_MS = 2_500;
    private static final String PROMPT = "You are only the voice of Buddy, an assistant that operates the user's "
            + "Android phone. You don't decide or do anything yourself: everything the user says goes to Buddy's phone "
            + "agent automatically, and the agent does the work and gives the answers. So when the user asks for "
            + "something, don't answer it and never say it's done; call remain_silent, or say only a very short "
            + "acknowledgement such as \"On it\" or \"Okay\". Messages starting with " + AGENT.trim() + " are the agent "
            + "speaking to the user through you: say them to the user naturally and briefly, in the user's language, "
            + "keeping the meaning exactly; don't read out long lists, code or links, and never add anything the agent "
            + "didn't say. When the agent waits for the user to approve something or to answer a question, say what it "
            + "is in a sentence and ask them to answer out loud (yes or no, or one of the choices) or on the screen. "
            + "Buddy takes their spoken answer itself: never say something is approved or chosen unless a "
            + AGENT.trim() + " message says so.";

    /** The chat screen showing the live bar. */
    interface Ui {
        void onLevel(float level);

        /** The words heard or said just now (the bar's line), or "". */
        void onLine(String text);

        /** Listening… / Thinking… / Working on it… / Speaking… (or Connecting…). */
        void onStatus(String status);
    }

    /** The screens showing a live bar (the chat screen, the bubble's panel). */
    private static final List<Ui> uis = new ArrayList<>();

    static void addUi(Ui u) {
        if (!uis.contains(u)) uis.add(u);
    }

    static void removeUi(Ui u) {
        uis.remove(u);
    }

    private static final Handler main = new Handler(Looper.getMainLooper());
    private static Context app;
    private static Sessions.Session ses;
    private static WebView web;
    private static volatile String thread;
    private static boolean connected;
    private static long started, lastActivity;
    /** The user's words not yet sent to the chat; the voice's current line. */
    private static final StringBuilder heard = new StringBuilder(), saying = new StringBuilder();
    private static long lastHeard;
    /** What the user said last was sent (a new word starts the next utterance); a reply to it is awaited. */
    private static boolean sent, awaiting;
    /** The voice's audio is audible (the page measures it). */
    private static boolean audible;
    /** For the voice, waiting for the call to connect. */
    private static final List<String> pending = new ArrayList<>();
    private static BroadcastReceiver screenOff;

    private CodexLive() {
    }

    /** Whether a chat can go live: any agent's chat, with Codex signed in (the call goes through it). */
    static boolean available(Context c, Sessions.Session s) {
        return s != null && SetupState.signedIn(c, "codex");
    }

    static boolean on() {
        return ses != null;
    }

    static boolean on(Sessions.Session s) {
        return s != null && s == ses;
    }

    static boolean connected() {
        return ses != null && connected;
    }

    /** When the call started (elapsedRealtime), for the bar's timer. */
    static long started() {
        return started;
    }

    static boolean owns(String t) {
        return t != null && t.equals(thread);
    }

    // ------------------------------------------------------------------ the log (~/.buddy/live.log in Buddy's Linux)

    private static final ExecutorService logger = Executors.newSingleThreadExecutor();
    private static volatile long logStart;

    private static File logFile() {
        return new File(Linux.root(app), "root/.buddy/live.log");
    }

    /** One line in the call's log, any thread. */
    static void log(String what) {
        if (app == null) return;
        final String line = String.format(java.util.Locale.ROOT, "%8.2f %s\n",
                (SystemClock.elapsedRealtime() - logStart) / 1000.0, what.replace('\n', ' '));
        logger.execute(() -> {
            try (FileOutputStream o = new FileOutputStream(logFile(), true)) {
                o.write(line.getBytes(StandardCharsets.UTF_8));
            } catch (Exception ignored) {
            }
        });
    }

    private static void logStartNew() {
        logStart = SystemClock.elapsedRealtime();
        logger.execute(() -> {
            try {
                File f = logFile();
                f.getParentFile().mkdirs();
                try (FileOutputStream o = new FileOutputStream(f, false)) {
                    o.write(("live voice call, " + new java.util.Date() + ", Buddy " + BuildInfo.VERSION + "\n")
                            .getBytes(StandardCharsets.UTF_8));
                }
            } catch (Exception ignored) {
            }
        });
    }

    private static String clip(String s, int n) {
        return s.length() > n ? s.substring(0, n) + "…" : s;
    }

    // ------------------------------------------------------------------ start and end

    /** Go live in this chat (main thread). Returns an error message, or null when starting. */
    static String start(Context c, Sessions.Session s) {
        if (!available(c, s)) return c.getString(R.string.dictation_codex_sign_in);
        if (!Voice.micAllowed(c)) return c.getString(R.string.voice_mic_needed);
        if (ses != null) end(null);
        Voice.stopListening();
        Voice.stopSpeaking();
        app = c.getApplicationContext();
        logStartNew();
        log("start: chat " + s.id);
        ses = s;
        connected = sent = awaiting = audible = false;
        started = lastActivity = SystemClock.elapsedRealtime();
        heard.setLength(0);
        saying.setLength(0);
        pending.clear();
        try {
            openPage();
        } catch (Exception e) {
            log("start failed: " + e);
            ses = null;
            return c.getString(R.string.dictation_chatgpt_start_failed, e.getMessage());
        }
        screenOff = new BroadcastReceiver() {
            @Override
            public void onReceive(Context x, Intent i) {
                log("screen off");
                end(app.getString(R.string.live_ended_screen_off));
            }
        };
        app.registerReceiver(screenOff, new IntentFilter(Intent.ACTION_SCREEN_OFF));
        notifyLive();
        main.postDelayed(watchdog, 250);
        ChatHub.get(app).note(s, Bubble.Kind.STEP, app.getString(R.string.live_started, s.brainName()));
        status();
        return null;
    }

    /**
     * End the call (main thread); why: shown in the chat, or null. The chat and the bar change at once; the microphone,
     * the page and the call are let go each on its own, so one that hangs can't hold up the rest.
     */
    static void end(String why) {
        final Sessions.Session s = ses;
        if (s == null) return;
        log("end: " + (why == null ? "End" : why));
        ses = null;
        connected = false;
        main.removeCallbacks(watchdog);
        ChatHub hub = ChatHub.get(app);
        try {
            hub.changed(); // the live bar goes now
        } catch (Exception e) {
            log("end: ui " + e);
        }
        for (Ui u : new ArrayList<>(uis)) {
            u.onLine("");
            u.onStatus(null);
        }
        stopCapture();
        if (web != null) {
            final WebView w = web;
            web = null;
            try {
                w.evaluateJavascript("hangUp()", null); // stops the microphone track and closes the connection
            } catch (Exception e) {
                log("end: page " + e);
            }
            main.postDelayed(() -> {
                try {
                    w.destroy();
                    log("end: page destroyed");
                } catch (Exception e) {
                    log("end: destroy " + e);
                }
            }, 300);
        }
        final String t = thread;
        thread = null;
        if (t != null) new Thread(() -> {
            try {
                CodexDictation.request("thread/realtime/stop", new JSONObject().put("threadId", t), 5000);
                log("end: call stopped");
            } catch (Exception e) {
                log("end: stop " + e.getMessage()); // the page already hung up: the call ends on ChatGPT's side anyway
            }
        }, "codex-live-stop").start();
        if (screenOff != null) {
            try {
                app.unregisterReceiver(screenOff);
            } catch (Exception ignored) {
            }
            screenOff = null;
        }
        try {
            app.getSystemService(NotificationManager.class).cancel(NOTIFICATION_ID);
        } catch (Exception ignored) {
        }
        pending.clear();
        String left = clean(heard); // said but never sent: shown, not sent
        heard.setLength(0);
        try {
            if (hub.sessions.find(s.id) != null) {
                if (!left.isEmpty()) hub.note(s, Bubble.Kind.USER, left);
                hub.note(s, Bubble.Kind.STEP, why == null ? app.getString(R.string.live_ended) : app.getString(R.string.live_ended_why, why));
            }
        } catch (Exception e) {
            log("end: note " + e);
        }
    }

    /** Sends what the user said once they paused; ends after a while with nothing said, or when it never connects. */
    private static final Runnable watchdog = new Runnable() {
        @Override
        public void run() {
            if (ses == null) return;
            long now = SystemClock.elapsedRealtime();
            if (!connected && now - started > CONNECT_MS) {
                end(app.getString(R.string.dictation_chatgpt_no_connect, app.getString(R.string.dictation_stage_connecting)));
                return;
            }
            if (!sent && heard.length() > 0 && now - lastHeard > PAUSE_MS) utterance("pause");
            if (ses.running) lastActivity = now; // the agent working counts: its reply is still to come
            if (now - lastActivity > IDLE_MS) {
                end(app.getString(R.string.live_ended_idle));
                return;
            }
            main.postDelayed(this, 250);
        }
    };

    private static void notifyLive() {
        ControlIndicator.channel(app);
        PendingIntent open = PendingIntent.getActivity(app, NOTIFICATION_ID, new Intent(app, ChatActivity.class)
                        .putExtra("id", ses.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        PendingIntent stop = PendingIntent.getBroadcast(app, NOTIFICATION_ID,
                new Intent(app, HiddenNotice.class).setAction(ACTION_END),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = new Notification.Builder(app, ControlIndicator.CHANNEL)
                .setSmallIcon(R.drawable.ic_launcher_monochrome)
                .setContentTitle(app.getString(R.string.live_notification_title, ses.brainName()))
                .setContentText(ses.label())
                .setContentIntent(open)
                .addAction(new Notification.Action.Builder(null, app.getString(R.string.live_end), stop).build())
                .setOngoing(true)
                .setUsesChronometer(true)
                .setWhen(System.currentTimeMillis())
                .build();
        try {
            app.getSystemService(NotificationManager.class).notify(NOTIFICATION_ID, n);
        } catch (SecurityException ignored) { // notifications off: the chat's live bar still shows it
        }
    }

    // ------------------------------------------------------------------ status

    private static String statusText;

    /** The bar's status line now, or null when there's no call. */
    static String statusText() {
        return ses == null ? null : statusText;
    }

    /** The bar's status from what's going on (main thread). */
    private static void status() {
        if (ses == null) {
            for (Ui u : new ArrayList<>(uis)) u.onStatus(null);
            return;
        }
        int s = !connected ? R.string.live_connecting
                : audible || saying.length() > 0 ? R.string.live_status_speaking
                : heard.length() > 0 && !sent ? R.string.live_status_listening
                : ses.running ? R.string.live_status_working
                : awaiting ? R.string.live_status_thinking
                : R.string.live_status_listening;
        String t = app.getString(s);
        if (!t.equals(statusText)) log("status: " + t);
        statusText = t;
        for (Ui u : new ArrayList<>(uis)) u.onStatus(t);
    }

    // ------------------------------------------------------------------ the chat's agent → the voice

    /** Something the chat's agent said (main thread): the voice says it. */
    static void agentSaid(Sessions.Session s, String text) {
        if (!on(s) || text == null || text.trim().isEmpty()) return;
        awaiting = false;
        String t = text.trim();
        if (t.length() > 4000) t = t.substring(0, 4000) + "…";
        tell(AGENT + t);
        status();
    }

    /** The agent waits for the user (an approval or a question; main thread): input is its confirm or ask call's. */
    static void agentWaiting(Sessions.Session s, String what, JSONObject input) {
        if (!on(s)) return;
        org.json.JSONArray o = input == null ? null : input.optJSONArray("options");
        if (o != null && o.length() > 0) {
            StringBuilder b = new StringBuilder();
            for (int i = 0; i < o.length(); i++) b.append(i == 0 ? "" : " / ").append(o.optString(i));
            tell(AGENT + "I'm asking you: " + input.optString("question") + " Choices: " + b + ". Answer out loud or on the screen.");
        } else if (input != null && input.has("summary")) {
            tell(AGENT + "I need your OK for: " + input.optString("summary") + ". Say yes or no, or answer on the screen.");
        } else {
            tell(AGENT + "I'm waiting for your answer: " + what);
        }
    }

    /** The agent's run ended (main thread); outcome as in Sessions.Session. */
    static void agentDone(Sessions.Session s, String outcome, boolean said) {
        if (!on(s)) return;
        log("agent done: " + outcome + (said ? "" : ", said nothing"));
        lastActivity = SystemClock.elapsedRealtime();
        awaiting = false;
        if ("stopped".equals(outcome)) tell(AGENT + "I stopped.");
        else if ("failed".equals(outcome)) tell(AGENT + "That didn't work.");
        else if (!said) tell(AGENT + "Done.");
        status();
    }

    private static void tell(String text) {
        lastActivity = SystemClock.elapsedRealtime();
        if (!connected || thread == null) {
            log("to voice (queued): " + clip(text, 200));
            pending.add(text);
            return;
        }
        log("to voice: " + clip(text, 200));
        final String t = thread;
        new Thread(() -> {
            try {
                JSONObject r = CodexDictation.request("thread/realtime/appendText", new JSONObject()
                        .put("threadId", t).put("role", "user").put("text", text), 15_000);
                if (r.has("error")) throw new Exception(r.getJSONObject("error").optString("message"));
            } catch (Exception e) {
                final String msg = e.getMessage();
                log("to voice failed: " + msg);
                main.post(() -> {
                    if (owns(t)) end(app.getString(R.string.dictation_chatgpt_error, msg));
                });
            }
        }, "codex-live-text").start();
    }

    // ------------------------------------------------------------------ the voice → the chat

    /** From Codex's app server (its reader thread): every notification about this call's conversation. */
    static void notification(String method, JSONObject p) {
        if (!method.equals("thread/realtime/outputAudio/delta") && !method.endsWith("transcript/delta")) {
            log("server " + method + " " + clip(String.valueOf(p), 300));
        }
        switch (method) {
            case "thread/realtime/sdp": {
                final String sdp = p.optString("sdp");
                main.post(() -> {
                    if (web != null) web.evaluateJavascript("answer(" + JSONObject.quote(sdp) + ")", null);
                });
                break;
            }
            case "thread/realtime/itemAdded": { // the voice handed something off: taken like anything said
                JSONObject item = p.optJSONObject("item");
                if (item == null || !"handoff_request".equals(item.optString("type"))) break;
                final String words = item.optString("input_transcript").trim();
                main.post(() -> {
                    if (ses == null) return;
                    if (heard.length() == 0 && !sent) heard.append(words);
                    utterance("hand-off");
                });
                break;
            }
            case "thread/realtime/transcript/delta": {
                final boolean user = !"assistant".equals(p.optString("role"));
                final String d = p.optString("delta");
                main.post(() -> {
                    if (ses == null) return;
                    long now = SystemClock.elapsedRealtime();
                    lastActivity = now;
                    if (user) {
                        if (sent) { // a new utterance
                            sent = false;
                            heard.setLength(0);
                        }
                        heard.append(d);
                        lastHeard = now;
                    } else {
                        saying.append(d);
                    }
                    String line = clean(user ? heard : saying);
                    for (Ui u : new ArrayList<>(uis)) u.onLine(line);
                    status();
                });
                break;
            }
            case "thread/realtime/transcript/done": {
                final boolean user = !"assistant".equals(p.optString("role"));
                final String text = p.optString("text").trim();
                main.post(() -> {
                    if (ses == null) return;
                    if (user) {
                        if (!sent) {
                            if (heard.length() == 0) heard.append(text);
                            utterance("turn done");
                        }
                        return;
                    }
                    saying.setLength(0);
                    if (!text.isEmpty()) ChatHub.get(app).note(ses, Bubble.Kind.BUDDY, VOICE_MARK + text);
                    status();
                });
                break;
            }
            case "thread/realtime/error": {
                final String msg = p.optString("message");
                main.post(() -> {
                    if (ses != null) end(app.getString(R.string.dictation_chatgpt_error, msg));
                });
                break;
            }
            case "thread/realtime/closed":
                main.post(() -> {
                    if (ses != null) end(null);
                });
                break;
        }
    }

    /** The user finished saying something (main thread): it goes to the chat, as if typed. */
    private static void utterance(String why) {
        Sessions.Session s = ses;
        String words = clean(heard);
        if (s == null || sent || words.isEmpty()) return;
        sent = awaiting = true;
        heard.setLength(0);
        lastActivity = SystemClock.elapsedRealtime();
        log("to chat (" + why + "): " + clip(words, 200));
        for (Ui u : new ArrayList<>(uis)) u.onLine("");
        if (answerCard(s, words)) {
            awaiting = false;
            status();
            return;
        }
        // a new task, or a note for the one under way; cards and Guard work as always
        String err = ChatHub.get(app).send(s, words);
        if (err != null) {
            log("to chat failed: " + err);
            awaiting = false;
            ChatHub.get(app).note(s, Bubble.Kind.ERROR, err);
            tell(AGENT + "That didn't start: " + err);
        }
        status();
    }

    // ------------------------------------------------- answering a card out loud

    private static final java.util.regex.Pattern YES = java.util.regex.Pattern.compile("(?:^| )(?:yes|yeah|yep|yup|sure|"
            + "ok|okay|approve|approved|confirm|confirmed|go ahead|do it|send it|post it|ja|jawohl|genau|oui|sí|si|sì|sim|"
            + "claro|tak|evet|da|да|так|ya|iya|haan|हाँ|हां|نعم|はい|ええ|네|예|是|是的|好|好的|对)(?: |$)"),
            NO = java.util.regex.Pattern.compile("(?:^| )(?:no|nope|nah|don t|dont|do not|cancel|deny|stop|nein|nicht|"
                    + "non|nee|nie|hayır|hayir|нет|ні|tidak|jangan|नहीं|لا|いいえ|だめ|아니|아니요|不|不要|别)(?: |$)");
    private static final String[] ORDINALS = {"first|1|one|1st", "second|2|two|2nd", "third|3|three|3rd", "fourth|4|four|4th"};

    /**
     * The user said something while this chat's approval or question waits (main thread): a clear answer answers it,
     * as a tap on the card would. Buddy decides from the transcript of what the user said, never from the voice. A
     * question takes a choice ("the second one", or its words) or, with more words, the user's own answer; an approval
     * takes only a short clear yes (three words at most) or no, and a longer sentence asks for that change. Returns whether the words were
     * taken (or the user is asked again), so they don't go to the chat as well.
     */
    private static boolean answerCard(Sessions.Session s, String words) {
        BuddyService svc = BuddyService.get();
        Bubble b = svc == null ? null : svc.bubble();
        if (b == null || b.pendingChat() != s) return false;
        String w = " " + words.toLowerCase(java.util.Locale.ROOT).replaceAll("[\\p{Punct}¿¡。、！？，]+", " ")
                .replaceAll("\\s+", " ").trim() + " ";
        int count = w.trim().isEmpty() ? 0 : w.trim().split(" ").length;
        List<String> options = b.pendingOptions();
        if (options != null) {
            String pick = null;
            int found = 0;
            for (int i = 0; i < options.size(); i++) {
                String o = " " + options.get(i).toLowerCase(java.util.Locale.ROOT).replaceAll("[\\p{Punct}]+", " ")
                        .replaceAll("\\s+", " ").trim() + " ";
                boolean named = o.trim().length() > 1 && w.contains(o);
                boolean ordinal = i < ORDINALS.length && count <= 4
                        && java.util.regex.Pattern.compile(" (?:" + ORDINALS[i] + ") ").matcher(w).find();
                if (named || ordinal) {
                    pick = options.get(i);
                    found++;
                }
            }
            if (found == 1) {
                log("voice picked: " + pick);
                b.pickFromApp(pick);
                tell(AGENT + "Got it: " + pick + ".");
            } else if (found == 0 && count >= 2) {
                log("voice answered: " + clip(words, 200));
                b.changeFromApp(words);
                tell(AGENT + "Got it.");
            } else {
                tell(AGENT + "I didn't catch which one. Say one of the choices again, or answer on the screen.");
            }
            return true;
        }
        boolean yes = YES.matcher(w).find(), no = NO.matcher(w).find();
        // approving takes a short yes ("yes", "okay go ahead"): "yes but change the time" is a change
        if (yes != no && count <= (yes ? 3 : 5)) {
            log("voice " + (yes ? "approved" : "denied") + ": " + clip(words, 100));
            b.answerFromApp(yes);
            tell(AGENT + (yes ? "Approved." : "Okay, I won't."));
        } else if (count >= 4) {
            log("voice change: " + clip(words, 200));
            b.changeFromApp(words);
            tell(AGENT + "Okay, I'll change that first.");
        } else {
            tell(AGENT + "I didn't catch a clear yes or no. Say yes or no, or answer on the screen.");
        }
        return true;
    }

    private static String clean(CharSequence s) {
        return s.toString().replaceAll("\\s+", " ").trim();
    }

    // ------------------------------------------------------------------ the call (WebView)

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    private static void openPage() {
        web = new WebView(app);
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setMediaPlaybackRequiresUserGesture(false);
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(PermissionRequest r) {
                log("page asks for " + java.util.Arrays.toString(r.getResources()) + (ses != null ? ": granted" : ": denied"));
                if (ses != null) r.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
                else r.deny();
            }

            @Override
            public boolean onConsoleMessage(android.webkit.ConsoleMessage m) {
                log("console " + m.messageLevel() + ": " + clip(m.message(), 300));
                return true;
            }
        });
        final WebView mine = web;
        web.addJavascriptInterface(new Object() {
            @JavascriptInterface
            public void offer(String sdp) {
                CodexLive.log("page: offer ready");
                new Thread(() -> startCall(mine, sdp), "codex-live-call").start();
            }

            /** The page couldn't open the microphone itself: Buddy records instead (see pull). */
            @JavascriptInterface
            public boolean ownMic() {
                boolean ok = web == mine && startCapture();
                CodexLive.log("Buddy's own recording (fallback): " + (ok ? "on" : "failed"));
                return ok;
            }

            /** The next bit of Buddy's recording (16-bit mono, 16 kHz) as base64; "" when there's none yet. */
            @JavascriptInterface
            public String pull(int bytes) {
                return web == mine ? android.util.Base64.encodeToString(pullCapture(bytes), android.util.Base64.NO_WRAP) : "";
            }

            /** Loudness of the microphone or the voice (0..1), for the bar's waveform; voice: the voice is audible. */
            @JavascriptInterface
            public void level(float v, boolean voice) {
                main.post(() -> {
                    if (web != mine) return;
                    if (voice != audible) {
                        audible = voice;
                        CodexLive.log("voice " + (voice ? "audible" : "quiet"));
                        status();
                    }
                    for (Ui u : new ArrayList<>(uis)) u.onLevel(v);
                });
            }

            /** A line for the log from the page (states, events on the call's data channel). */
            @JavascriptInterface
            public void log(String what) {
                CodexLive.log("page " + clip(what, 400)); // also after End (the hang-up's own lines)
            }

            @JavascriptInterface
            public void connected() {
                main.post(() -> {
                    if (web != mine) return;
                    connected = true;
                    lastActivity = SystemClock.elapsedRealtime();
                    List<String> waiting = new ArrayList<>(pending);
                    pending.clear();
                    for (String t : waiting) tell(t);
                    ChatHub.get(app).changed();
                    status();
                });
            }

            @JavascriptInterface
            public void failed(String why) {
                CodexLive.log("page failed: " + why);
                main.post(() -> {
                    if (web == mine) end(app.getString(R.string.dictation_chatgpt_error,
                            "the connection failed".equals(why) ? app.getString(R.string.dictation_connection_failed) : why));
                });
            }
        }, "Buddy");
        web.loadDataWithBaseURL("https://buddy.local/", PAGE, "text/html", "utf-8", null);
    }

    /** Codex places the call with ChatGPT on a conversation of its own; the answer comes back as a notification. */
    private static void startCall(WebView page, String sdp) {
        try {
            CodexDictation.ensureServer(app);
            String t = CodexDictation.newThread();
            if (web != page) return; // ended meanwhile
            thread = t;
            log("call: thread " + t);
            JSONObject r = CodexDictation.request("thread/realtime/start", new JSONObject()
                    .put("threadId", t)
                    .put("transport", new JSONObject().put("type", "webrtc").put("sdp", sdp))
                    .put("version", "v3")
                    .put("outputModality", "audio")
                    .put("voice", Prefs.speechVoice(app))
                    .put("clientManagedHandoffs", true) // Codex's own turn is stopped: the chat does the work
                    .put("delegationAckFiller", false)
                    .put("prompt", PROMPT)
                    .put("includeStartupContext", false), 30_000);
            log("call: start -> " + clip(r.toString(), 300));
            if (r.has("error")) throw new Exception(r.getJSONObject("error").optString("message"));
            if (!owns(t)) CodexDictation.request("thread/realtime/stop", new JSONObject().put("threadId", t), 5000); // ended meanwhile
        } catch (Exception e) {
            final String msg = e.getMessage();
            log("call failed: " + msg);
            main.post(() -> {
                if (web == page) end(app.getString(R.string.dictation_chatgpt_start_failed, msg));
            });
        }
    }

    // ------------------------------------------------------------------ Buddy's own recording (fallback)

    private static volatile boolean recording;
    private static final ArrayDeque<byte[]> chunks = new ArrayDeque<>();

    /** Echo-cancelled where the phone has it (voice-call source), so the voice doesn't hear itself. */
    private static boolean startCapture() {
        if (recording) return true;
        if (!Voice.micAllowed(app)) return false;
        int rate = 16000, ch = AudioFormat.CHANNEL_IN_MONO;
        int min = AudioRecord.getMinBufferSize(rate, ch, AudioFormat.ENCODING_PCM_16BIT);
        final AudioRecord r;
        try {
            r = new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, rate, ch, AudioFormat.ENCODING_PCM_16BIT, Math.max(min, rate));
            if (r.getState() != AudioRecord.STATE_INITIALIZED) {
                r.release();
                return false;
            }
            if (android.media.audiofx.AcousticEchoCanceler.isAvailable()) {
                android.media.audiofx.AcousticEchoCanceler aec = android.media.audiofx.AcousticEchoCanceler.create(r.getAudioSessionId());
                if (aec != null) aec.setEnabled(true);
                log("fallback recording: echo canceller " + (aec != null));
            }
            r.startRecording();
        } catch (Exception e) {
            log("fallback recording failed: " + e);
            return false;
        }
        synchronized (chunks) {
            chunks.clear();
        }
        recording = true;
        new Thread(() -> {
            byte[] buf = new byte[1280]; // 40 ms
            while (recording) {
                int n = r.read(buf, 0, buf.length);
                if (n < 0) break;
                if (n == 0) continue;
                synchronized (chunks) {
                    chunks.add(java.util.Arrays.copyOf(buf, n));
                    while (chunks.size() > 125) chunks.poll(); // keep 5 s at most
                }
            }
            recording = false;
            try {
                r.stop();
            } catch (Exception ignored) {
            }
            r.release();
            log("fallback recording released");
        }, "codex-live-mic").start();
        return true;
    }

    private static byte[] pullCapture(int max) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(max);
        synchronized (chunks) {
            while (!chunks.isEmpty() && out.size() + chunks.peek().length <= max) {
                byte[] c = chunks.poll();
                out.write(c, 0, c.length);
            }
        }
        return out.toByteArray();
    }

    private static void stopCapture() {
        recording = false;
        synchronized (chunks) {
            chunks.clear();
        }
    }

    /**
     * The phone side of the call: the microphone with the WebView's own echo cancellation (it knows what it plays,
     * so the user can talk over the voice), or Buddy's recording when the WebView can't open it; the voice plays aloud.
     * It logs its states and the data channel's events through Buddy.log.
     */
    private static final String PAGE = "<!doctype html><html><body><audio id=out autoplay></audio><script>\n"
            + "let pc, ctx, mic, timer, stats;\n"
            + "const L = s => { try { Buddy.log(s); } catch (e) {} };\n"
            + "function meter(stream) {\n"
            + "  const an = ctx.createAnalyser(); an.fftSize = 1024; ctx.createMediaStreamSource(stream).connect(an);\n"
            + "  const buf = new Float32Array(an.fftSize);\n"
            + "  return () => { an.getFloatTimeDomainData(buf); let m = 0; for (const v of buf) m = Math.max(m, Math.abs(v)); return m; };\n"
            + "}\n"
            + "async function ownTrack() {\n" // Buddy's recording played into the call, resampled to the page's rate
            + "  if (!Buddy.ownMic()) throw new Error('microphone');\n"
            + "  const ratio = 16000 / ctx.sampleRate;\n"
            + "  L('fallback: page rate ' + ctx.sampleRate);\n"
            + "  const proc = ctx.createScriptProcessor(1024, 1, 1), dest = ctx.createMediaStreamDestination();\n"
            + "  const idle = ctx.createConstantSource(); idle.offset.value = 0; idle.connect(proc); idle.start();\n"
            + "  let fifo = new Float32Array(0), pos = 0;\n"
            + "  proc.onaudioprocess = e => {\n"
            + "    const out = e.outputBuffer.getChannelData(0); out.fill(0);\n"
            + "    const bin = atob(Buddy.pull(6400));\n"
            + "    if (bin.length > 1) {\n"
            + "      const add = new Float32Array(bin.length >> 1);\n"
            + "      for (let i = 0; i < add.length; i++) { let v = bin.charCodeAt(2 * i) | (bin.charCodeAt(2 * i + 1) << 8); if (v > 32767) v -= 65536; add[i] = v / 32768; }\n"
            + "      const f = new Float32Array(fifo.length + add.length); f.set(fifo); f.set(add, fifo.length); fifo = f;\n"
            + "    }\n"
            + "    const need = Math.ceil(out.length * ratio + pos) + 2;\n"
            + "    if (fifo.length < need) return;\n"
            + "    for (let i = 0; i < out.length; i++) { const x = i * ratio + pos, k = x | 0, t = x - k; out[i] = fifo[k] * (1 - t) + fifo[k + 1] * t; }\n"
            + "    const end = out.length * ratio + pos, used = Math.floor(end); pos = end - used; fifo = fifo.subarray(used);\n"
            + "    if (fifo.length > 16000) fifo = fifo.subarray(fifo.length - 4000);\n" // fell behind: stay live
            + "  };\n"
            + "  proc.connect(dest);\n"
            + "  return dest.stream;\n"
            + "}\n"
            + "async function start() {\n"
            + "  try {\n"
            + "    ctx = new AudioContext(); await ctx.resume().catch(() => {});\n"
            + "    L('audio context ' + ctx.state + ', ' + ctx.sampleRate + ' Hz');\n"
            + "    let stream;\n"
            + "    try {\n"
            + "      stream = mic = await navigator.mediaDevices.getUserMedia({audio: {echoCancellation: true, noiseSuppression: true, autoGainControl: true}});\n"
            + "      const t = stream.getAudioTracks()[0];\n"
            + "      L('mic: ' + t.label + ' ' + JSON.stringify(t.getSettings ? t.getSettings() : {}));\n"
            + "      t.onended = () => L('mic track ended'); t.onmute = () => L('mic track muted'); t.onunmute = () => L('mic track unmuted');\n"
            + "    } catch (e) { L('getUserMedia failed: ' + (e && e.name) + ' ' + (e && e.message)); stream = await ownTrack(); }\n"
            + "    const heard = meter(stream);\n"
            + "    let said = () => 0, wasVoice = false;\n"
            + "    pc = new RTCPeerConnection();\n"
            + "    pc.addTrack(stream.getAudioTracks()[0], stream);\n"
            + "    const dc = pc.createDataChannel('oai-events');\n"
            + "    dc.onopen = () => L('data channel open'); dc.onclose = () => L('data channel closed');\n"
            + "    dc.onmessage = m => { try { const j = JSON.parse(m.data), ty = j.type || '?';\n"
            + "      if (/delta|transcript\\.added|audio/.test(ty) && !/error/.test(ty)) return;\n"
            + "      let more = '';\n"
            + "      if (j.turn) more = ' ' + j.turn.role + ': ' + (j.turn.transcript || '').slice(0, 80);\n"
            + "      else if (j.usage || j.usage_limit) more = ' ' + JSON.stringify({usage: j.usage, limit: j.usage_limit}).slice(0, 200);\n"
            + "      else if (j.error) more = ' ' + JSON.stringify(j.error).slice(0, 300);\n"
            + "      else if (j.item) more = ' ' + JSON.stringify(j.item).slice(0, 200);\n"
            + "      L('dc ' + ty + more); } catch (e) { L('dc raw ' + String(m.data).slice(0, 200)); } };\n"
            + "    pc.ontrack = e => {\n"
            + "      L('remote track ' + e.track.kind + ', muted ' + e.track.muted);\n"
            + "      e.track.onunmute = () => L('remote track unmuted'); e.track.onmute = () => L('remote track muted');\n"
            + "      const s = e.streams[0] || new MediaStream([e.track]);\n"
            + "      const out = document.getElementById('out'); out.muted = false; out.volume = 1; out.srcObject = s;\n"
            + "      out.onplaying = () => L('audio element playing'); out.onpause = () => L('audio element paused');\n"
            + "      out.play().then(() => L('audio element play ok')).catch(err => L('audio element play failed: ' + err));\n"
            + "      said = meter(s);\n"
            + "    };\n"
            + "    timer = setInterval(() => { const h = heard(), v = said(), voice = v > 0.02;\n"
            + "      Buddy.level(Math.min(1, Math.max(h, v) * 3), voice); }, 70);\n"
            + "    let lastIn = 0, lastOut = 0;\n"
            + "    stats = setInterval(async () => { if (!pc) return; let a = '', b = '';\n"
            + "      (await pc.getStats()).forEach(r => { if (r.type === 'outbound-rtp' && r.kind === 'audio') a = 'sent ' + (r.bytesSent - lastOut) + ' B', lastOut = r.bytesSent;\n"
            + "        if (r.type === 'inbound-rtp' && r.kind === 'audio') b = 'got ' + (r.bytesReceived - lastIn) + ' B, level ' + (r.audioLevel || 0).toFixed(3), lastIn = r.bytesReceived; });\n"
            + "      L('rtp 10 s: ' + a + '; ' + b + '; mic peak ' + heard().toFixed(3)); }, 10000);\n"
            + "    pc.oniceconnectionstatechange = () => L('ice ' + pc.iceConnectionState);\n"
            + "    pc.onconnectionstatechange = () => {\n"
            + "      L('connection ' + pc.connectionState);\n"
            + "      if (pc.connectionState === 'connected') Buddy.connected();\n"
            + "      else if (pc.connectionState === 'failed') Buddy.failed('the connection failed');\n"
            + "    };\n"
            + "    await pc.setLocalDescription(await pc.createOffer());\n"
            + "    await new Promise(r => { if (pc.iceGatheringState === 'complete') r(); else { pc.onicegatheringstatechange = () => { if (pc.iceGatheringState === 'complete') r(); }; setTimeout(r, 1500); } });\n"
            + "    Buddy.offer(pc.localDescription.sdp);\n"
            + "  } catch (e) { Buddy.failed(String(e && e.message || e)); }\n"
            + "}\n"
            + "function answer(sdp) { L('answer received'); pc.setRemoteDescription({type: 'answer', sdp}).catch(e => Buddy.failed(String(e.message || e))); }\n"
            + "function hangUp() {\n"
            + "  L('hang up');\n"
            + "  clearInterval(timer); clearInterval(stats);\n"
            + "  try { if (mic) mic.getTracks().forEach(t => t.stop()); } catch (e) {}\n"
            + "  try { if (pc) pc.close(); } catch (e) {}\n"
            + "  try { document.getElementById('out').srcObject = null; } catch (e) {}\n"
            + "  try { if (ctx) ctx.close(); } catch (e) {}\n"
            + "  L('hung up: microphone stopped');\n"
            + "}\n"
            + "start();\n"
            + "</script></body></html>";
}
