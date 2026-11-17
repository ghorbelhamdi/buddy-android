package app.buddy.assistant;

import android.content.ComponentName;
import android.content.Context;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;

/**
 * While Buddy reads a reply aloud, and (a setting of its own, issue #56) while it listens for the user's voice, pause
 * what is playing and start it again afterwards (both off unless switched on). Only what Buddy paused is started
 * again, and not when the user changed its playback in the meantime. A message sent by voice whose reply will be read
 * aloud keeps it paused until that reply has been read.
 */
final class SpeechPause {
    private static final long POLL_MS = 300, QUIET_MS = 800, NEVER_SPOKE_MS = 10_000;
    /** After listening: a message sent this soon is the one that was spoken. */
    private static final long LISTEN_QUIET_MS = 1500;
    /** How long a reply that will be read aloud is waited for (an agent can work a while). */
    private static final long AWAIT_REPLY_MS = 5 * 60_000L;
    private static final Handler main = new Handler(Looper.getMainLooper());

    /** One app's playback that Buddy paused. */
    private static final class Held extends MediaController.Callback {
        final MediaController mc;
        boolean sawPause, touched;

        Held(MediaController mc) {
            this.mc = mc;
        }

        @Override
        public void onPlaybackStateChanged(PlaybackState ps) {
            int s = ps == null ? PlaybackState.STATE_NONE : ps.getState();
            if (s == PlaybackState.STATE_PAUSED) sawPause = true;
            else if (sawPause) touched = true; // the user played, stopped or skipped it themselves
        }

        @Override
        public void onSessionDestroyed() {
            touched = true;
        }
    }

    private static final ArrayList<Held> held = new ArrayList<>();
    private static boolean holding, heard;
    /** Held for listening (not yet for a reply read aloud); and a reply to read aloud is coming. */
    private static boolean listenHold, awaitReply;
    private static long began, quietSince;

    private SpeechPause() {
    }

    /**
     * Buddy is about to speak (again: also when one voice failed and another takes over, so the wait for the first
     * word starts anew). Needs notification access: that is how Buddy sees which app plays, pauses that one, and knows
     * afterwards whether the user touched it. A media key can't tell any of that, so without access nothing is paused.
     */
    static void begin(Context c) {
        if (!Prefs.pauseMediaForSpeech(c) || !NotificationWatcher.enabled(c)) return;
        final Context app = c.getApplicationContext();
        main.post(() -> {
            if (!holding) {
                holding = true;
                pause(app);
            }
            listenHold = awaitReply = false; // from here, it's the speaking that holds it
            began = System.currentTimeMillis();
            heard = false;
            quietSince = 0;
            main.removeCallbacks(poll);
            main.postDelayed(poll, POLL_MS);
        });
    }

    /**
     * Buddy is about to listen for the user's voice (dictation, Talk, a card answered by voice, a live voice call).
     * Called before the microphone starts; on the main thread the pause is sent at once.
     */
    static void listen(Context c) {
        if (!Prefs.pauseMediaForListening(c) || !NotificationWatcher.enabled(c)) return;
        final Context app = c.getApplicationContext();
        Runnable r = () -> {
            if (holding && !listenHold) return; // a reply is being read aloud: that hold covers it, and ends it
            if (!holding) {
                holding = true;
                pause(app);
            }
            listenHold = true;
            awaitReply = false;
            quietSince = 0;
            main.removeCallbacks(poll);
            main.postDelayed(poll, POLL_MS);
        };
        if (Looper.myLooper() == Looper.getMainLooper()) r.run();
        else main.post(r);
    }

    /**
     * The user sent a message. Just after listening, it's the one they spoke: when its reply will be read aloud with
     * media paused for that too, what's paused stays so until the reply has been read.
     */
    static void sent(Context c) {
        if (!Prefs.speakReplies(c) || !Prefs.pauseMediaForSpeech(c)) return;
        main.post(() -> {
            if (holding && listenHold) awaitReply = true;
        });
    }

    /** A chat's run ended: a reply still awaited won't come (it was read aloud already, or there was none). */
    static void runEnded() {
        main.post(() -> {
            if (awaitReply && !Voice.isSpeaking()) {
                awaitReply = false;
                quietSince = System.currentTimeMillis() - LISTEN_QUIET_MS; // resume at the next look
            }
        });
    }

    private static void pause(Context app) {
        try {
            MediaSessionManager msm = app.getSystemService(MediaSessionManager.class);
            for (MediaController mc : msm.getActiveSessions(new ComponentName(app, NotificationWatcher.class))) {
                PlaybackState ps = mc.getPlaybackState();
                if (ps == null || ps.getState() != PlaybackState.STATE_PLAYING) continue;
                Held h = new Held(mc);
                mc.registerCallback(h, main);
                mc.getTransportControls().pause();
                held.add(h);
            }
        } catch (Exception ignored) { // access taken back meanwhile: nothing is paused
        }
    }

    private static final Runnable poll = new Runnable() {
        @Override
        public void run() {
            long now = System.currentTimeMillis();
            if (listenHold) { // listening (or a live voice call), then maybe a reply to be read aloud
                if (Voice.listening() || CodexLive.on()) {
                    quietSince = 0;
                } else {
                    if (quietSince == 0) quietSince = now;
                    if (now - quietSince > (awaitReply ? AWAIT_REPLY_MS : LISTEN_QUIET_MS)) {
                        resume();
                        return;
                    }
                }
                main.postDelayed(this, POLL_MS);
                return;
            }
            // a card that was read aloud may be listening for its answer: stay quiet for that too
            if (Voice.isSpeaking() || (heard && Voice.listening())) {
                heard = true;
                quietSince = 0;
            } else {
                if (quietSince == 0) quietSince = now;
                if (heard ? now - quietSince > QUIET_MS : now - began > NEVER_SPOKE_MS) {
                    resume();
                    return;
                }
            }
            main.postDelayed(this, POLL_MS);
        }
    };

    private static void resume() {
        holding = listenHold = awaitReply = false;
        for (Held h : held) {
            try {
                h.mc.unregisterCallback(h);
                PlaybackState ps = h.mc.getPlaybackState();
                if (!h.touched && ps != null && ps.getState() == PlaybackState.STATE_PAUSED) {
                    h.mc.getTransportControls().play();
                }
            } catch (Exception ignored) {
            }
        }
        held.clear();
    }
}
