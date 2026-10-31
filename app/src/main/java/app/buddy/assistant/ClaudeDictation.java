package app.buddy.assistant;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

/**
 * Voice input through Claude Code's own /voice (Anthropic's speech-to-text on the user's Claude plan).
 * /voice only exists in Claude Code's interactive screen, so Buddy keeps a hidden Claude Code session in
 * Buddy's Linux, holds its push-to-talk key (space) while the user talks, feeds it the phone's microphone
 * (MicServer + the stand-in arecord), then reads the words from the prompt and clears it. Nothing is ever
 * sent as a message in that session.
 */
final class ClaudeDictation {
    /** Languages Claude Code's dictation supports (its own list). */
    static final java.util.Set<String> SUPPORTED = new java.util.HashSet<>(java.util.Arrays.asList(
            "en", "es", "fr", "ja", "de", "pt", "it", "ko", "hi", "id", "ru", "pl", "tr", "nl", "uk", "el", "cs", "da", "sv", "no"));
    static final String DIR = "/root/dictation";

    private static final Handler main = new Handler(Looper.getMainLooper());
    private static TermSession session;
    private static String sessionLang;
    private static Voice.Listener listener;
    private static boolean holding, finishing;
    private static String lastText = "";
    private static long lastChange, holdStart;

    private ClaudeDictation() {
    }

    static boolean supports(String bcp47) {
        return SUPPORTED.contains(bcp47.split("-")[0].toLowerCase(java.util.Locale.ROOT));
    }

    static boolean active() {
        return listener != null;
    }

    /** Start listening (the hidden session starts first if needed: a few seconds the first time). */
    static String listen(Context c, String lang, Voice.Listener l) {
        if (!SetupState.planSignedIn(c, "claude")) return c.getString(R.string.dictation_claude_sign_in);
        final Context app = c.getApplicationContext();
        listener = l;
        holding = false;
        finishing = false;
        lastText = "";
        l.onPartial("");
        final String code = code(lang);
        final Voice.EndOfSpeech eos = new Voice.EndOfSpeech();
        MicServer.levels = v -> main.post(() -> {
            if (listener != l || finishing) return;
            l.onLevel(v);
            if (eos.feed(v)) finish(); // the user paused: send, like the phone's recognizer does
        });
        // recording starts now; Claude gets it all once its recorder is up
        l.onStatus(MicServer.startCapture(app) ? null
                : app.getString(ready(code) ? R.string.dictation_starting : R.string.dictation_starting_claude));
        new Thread(() -> {
            try {
                MicServer.start(app, null); // before voice mode: Claude Code tests the microphone when it turns on
                ensureSession(app, code);
                if (listener != l) return;
                main.post(() -> startHolding(l));
            } catch (Exception e) {
                main.post(() -> end(l, app.getString(R.string.dictation_claude_start_failed, e.getMessage())));
            }
        }, "claude-dictation").start();
        return null;
    }

    /** Start the hidden session ahead of time (a chat opened), so the first tap on the mic is quick. */
    static void prepare(Context c, String lang) {
        if (!SetupState.planSignedIn(c, "claude") || ready(code(lang))) return;
        final Context app = c.getApplicationContext();
        new Thread(() -> {
            try {
                MicServer.start(app, null);
                ensureSession(app, code(lang));
            } catch (Exception ignored) {
            } finally {
                if (listener == null) MicServer.stop();
            }
        }, "claude-dictation-prep").start();
    }

    private static String code(String lang) {
        return lang.split("-")[0].toLowerCase(java.util.Locale.ROOT);
    }

    private static boolean ready(String code) {
        return session != null && !session.exited && code.equals(sessionLang);
    }

    /** The user is done: stop the microphone, keep holding until Claude has every bit, then read the words. */
    static void finish() {
        if (listener == null || finishing) return;
        finishing = true;
        MicServer.stopCapture();
        final Voice.Listener l = listener;
        l.onStatus(BuddyApp.str(R.string.dictation_transcribing));
        final long asked = System.currentTimeMillis();
        main.post(new Runnable() {
            @Override
            public void run() {
                if (listener != l) return;
                long now = System.currentTimeMillis();
                if (!holding) { // Claude is still starting; the audio waits for it
                    if (now - asked > 45_000) end(l, BuddyApp.str(R.string.dictation_claude_no_start));
                    else main.postDelayed(this, 100);
                    return;
                }
                boolean delivered = now - holdStart > 800 && now - asked > 500 && (MicServer.drained || now - asked > 5000);
                if (!delivered && now - asked < 15_000) {
                    main.postDelayed(this, 50);
                    return;
                }
                holding = false; // releases the key: Claude transcribes
                readResult(l);
            }
        });
    }

    private static void readResult(final Voice.Listener l) {
        lastChange = System.currentTimeMillis();
        main.postDelayed(new Runnable() {
            final long started = System.currentTimeMillis();

            @Override
            public void run() {
                if (listener != l) return;
                String t = promptText();
                if (!t.equals(lastText)) {
                    lastText = t;
                    lastChange = System.currentTimeMillis();
                    l.onPartial(t);
                }
                long waited = System.currentTimeMillis() - started;
                boolean settled = System.currentTimeMillis() - lastChange > 1200 && !busy();
                if ((settled && (!t.isEmpty() || waited > 3000)) || waited > 20_000) {
                    clearPrompt();
                    stopMic();
                    listener = null;
                    finishing = false;
                    if (t.isEmpty()) l.onEnd(BuddyApp.str(R.string.dictation_no_speech));
                    else l.onFinal(t);
                    return;
                }
                main.postDelayed(this, 200);
            }
        }, 500);
    }

    private static void stopMic() {
        MicServer.levels = null;
        MicServer.endCapture();
        MicServer.stop();
    }

    static void cancel() {
        Voice.Listener l = listener;
        listener = null;
        holding = false;
        finishing = false;
        stopMic();
        if (session != null && !session.exited) clearPrompt();
        if (l != null) l.onEnd(null);
    }

    private static void end(Voice.Listener l, String message) {
        if (listener == l) listener = null;
        holding = false;
        finishing = false;
        stopMic();
        l.onEnd(message);
    }

    // ------------------------------------------------------------- the hidden session

    private static synchronized void ensureSession(Context c, String lang) throws Exception {
        if (session != null && !session.exited && lang.equals(sessionLang)) return;
        if (session != null) session.finish();
        session = null;
        // first-run answers, folder trust, and the dictation language for this folder only
        // Claude Code's /voice checks that SoX is installed; recording itself goes through Buddy's stand-in rec/arecord
        String out = Linux.exec(c, "set -e; command -v sox >/dev/null || apk add -q sox; mkdir -p " + DIR + "/.claude; f=/root/.claude.json; [ -s $f ] || echo '{}' > $f;"
                + " jq --arg d " + DIR + " '.hasCompletedOnboarding=true | .theme=(.theme // \"dark\")"
                + " | .fullscreenUpsellSeenCount=99 | .hasSeenAutoDefaultNotice=true"
                + " | .projects[$d].hasTrustDialogAccepted=true | .projects[$d].hasCompletedProjectOnboarding=true' $f"
                + " > /tmp/claude-d.json && cat /tmp/claude-d.json > $f;"
                + " echo '{\"language\": \"" + lang + "\", \"voiceEnabled\": true}' > " + DIR + "/.claude/settings.json; echo PREP_OK", 120);
        if (!out.contains("PREP_OK")) throw new Exception(out.trim());
        // stderr off the screen: the sound library's complaints would otherwise be read back as the words
        session = TermSession.startHidden(c, 30, 100, "cd " + DIR + " && exec claude 2>>/tmp/dictation.err");
        sessionLang = lang;
        // wait for the prompt, then switch voice mode on (hold to talk)
        long until = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < until && !session.exited && !session.emulator.screenText().contains("❯")) {
            Thread.sleep(300);
        }
        if (session.exited || !session.emulator.screenText().contains("❯")) throw new Exception(c.getString(R.string.dictation_claude_code_no_start));
        Thread.sleep(400);
        session.write("/voice hold\r");
        Thread.sleep(1500);
        clearPrompt();
    }

    /** Hold space: Claude Code's push-to-talk works from key repeat, so keep pressing it while the user talks. */
    private static void startHolding(final Voice.Listener l) {
        if (listener != l) return;
        clearPrompt(); // in case a cancelled recording's words arrived late
        MicServer.drained = false;
        holdStart = System.currentTimeMillis();
        holding = true;
        if (!finishing) l.onStatus(null);
        main.post(new Runnable() {
            int n;

            @Override
            public void run() {
                if (!holding || listener != l) return;
                session.write(" ");
                if (++n % 10 == 0) { // show what Claude has heard so far
                    String t = promptText();
                    if (!t.equals(lastText)) {
                        lastText = t;
                        l.onPartial(t);
                    }
                }
                main.postDelayed(this, 40);
            }
        });
    }

    private static boolean busy() {
        String s = session == null ? "" : session.emulator.screenText().toLowerCase(java.util.Locale.ROOT);
        return s.contains("transcribing") || s.contains("recording") || s.contains("listening");
    }

    /** Text in Claude Code's input box (after the ❯ prompt, up to the box's bottom rule). */
    static String promptText() {
        if (session == null) return "";
        String[] lines = session.emulator.screenText().split("\n");
        int start = -1;
        for (int i = lines.length - 1; i >= 0; i--) {
            if (lines[i].contains("❯")) {
                start = i;
                break;
            }
        }
        if (start < 0) return "";
        StringBuilder sb = new StringBuilder(lines[start].substring(lines[start].indexOf('❯') + 1));
        for (int i = start + 1; i < lines.length; i++) {
            String ln = lines[i];
            if (ln.trim().startsWith("─") || ln.trim().isEmpty()) break;
            sb.append(' ').append(ln.trim());
        }
        String t = sb.toString();
        if (t.contains("ALSA lib") || t.contains("keep holding")) return ""; // a status or error line, not words
        return t.replaceAll("\\s+", " ").trim();
    }

    private static void clearPrompt() {
        if (session == null) return;
        for (int i = 0; i < 4; i++) session.write("\u0015"); // ctrl+u: delete to line start
        lastText = "";
    }
}
