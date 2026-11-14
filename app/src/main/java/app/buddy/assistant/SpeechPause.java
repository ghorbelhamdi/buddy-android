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
 * While Buddy reads a reply aloud, pause what is playing and start it again afterwards (off unless
 * switched on). Only what Buddy paused is started again, and not when the user changed its playback
 * in the meantime.
 */
final class SpeechPause {
    private static final long POLL_MS = 300, QUIET_MS = 800, NEVER_SPOKE_MS = 10_000;
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
            began = System.currentTimeMillis();
            heard = false;
            quietSince = 0;
            main.removeCallbacks(poll);
            main.postDelayed(poll, POLL_MS);
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
        holding = false;
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
