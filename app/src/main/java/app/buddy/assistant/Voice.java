package app.buddy.assistant;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.media.AudioAttributes;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.speech.tts.TextToSpeech;

import java.util.ArrayList;

/** Speech in (Android's recognizer) and speech out (text-to-speech) for chats. Main thread only. */
final class Voice {
    interface Listener {
        void onPartial(String text);

        void onFinal(String text);

        /** Stopped without a result (silence, error, cancelled). message may be null. */
        void onEnd(String message);

        /** Microphone loudness, 0..1, many times a second while recording. */
        default void onLevel(float level) {
        }

        /** What's happening when not simply recording ("Starting Claude…", "Transcribing…"); null: recording. */
        default void onStatus(String status) {
        }
    }

    /**
     * Notices that the user stopped talking, from the microphone's loudness (Claude's and ChatGPT's dictation have no
     * end-of-speech of their own; Android's recognizer does). Follows the room's noise so a car or a street works.
     */
    static final class EndOfSpeech {
        private static final long PAUSE_MS = 1800, NOTHING_SAID_MS = 8000;
        private final long start = System.currentTimeMillis();
        private float floor = -1;
        private boolean spoke;
        private long quietSince;

        /** A level (0..1); true once the user has spoken and then paused. */
        boolean feed(float v) {
            long now = System.currentTimeMillis();
            // the noise floor: follows quiet levels at once, louder ones only slowly
            floor = floor < 0 || v < floor ? v : floor + (v - floor) * 0.003f;
            if (v > floor + 0.18f) {
                spoke = true;
                quietSince = 0;
            } else if (v < floor + 0.10f) {
                if (quietSince == 0) quietSince = now;
            }
            if (spoke) return quietSince != 0 && now - quietSince > PAUSE_MS;
            return now - start > NOTHING_SAID_MS;
        }
    }

    private static SpeechRecognizer recognizer;
    private static Listener current;
    private static TextToSpeech tts;
    private static boolean ttsReady;
    private static String pending;

    private Voice() {
    }

    static boolean micAllowed(Context c) {
        return c.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * Asks for the microphone, saying first which answer to give: one given "Only this time" ends when Buddy has been
     * out of sight for a minute, which Android does by stopping Buddy, a task that is running included (issue #54).
     */
    static void askMic(android.app.Activity a, int code) {
        android.widget.Toast.makeText(a, R.string.voice_mic_while_using, android.widget.Toast.LENGTH_LONG).show();
        a.requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, code);
    }

    static boolean listening() {
        return current != null || ClaudeDictation.active() || CodexDictation.active();
    }

    /** Languages offered for voice: {BCP-47 tag, name}. */
    static final String[][] LANGUAGES = {
            {"en-US", "English"}, {"fr-FR", "Français"}, {"de-DE", "Deutsch"}, {"ar-TN", "العربية · تونس"},
            {"ar-SA", "العربية"}, {"es-ES", "Español"}, {"it-IT", "Italiano"}, {"pt-PT", "Português"},
            {"nl-NL", "Nederlands"}, {"tr-TR", "Türkçe"}, {"ru-RU", "Русский"}, {"ja-JP", "日本語"},
    };

    static String languageName(String tag) {
        for (String[] l : LANGUAGES) if (l[0].equals(tag)) return l[1];
        return tag;
    }

    /** Warm up the chosen engine (a chat or the bubble opened) so the first tap on the mic is quick. */
    static void prepare(Context c) {
        String engine = Prefs.voiceEngine(c), lang = Prefs.voiceLang(c);
        if ("claude".equals(engine) && ClaudeDictation.supports(lang)) ClaudeDictation.prepare(c, lang);
        else if ("chatgpt".equals(engine)) CodexDictation.prepare(c);
    }

    /** Start listening. Returns an error message, or null when started. */
    static String listen(Context c, Listener given) {
        if (!micAllowed(c)) return c.getString(R.string.voice_mic_needed);
        if (CodexLive.on()) return c.getString(R.string.live_mic_busy); // the call has the microphone
        stopListening();
        stopSpeaking();
        final Listener l = endpoint(c, given);
        SpeechPause.listen(c); // before the microphone starts, so it hears the user and not the video (#56)
        String lang = Prefs.voiceLang(c);
        // Claude's dictation (on the user's Claude plan) for the languages it supports; otherwise the phone's
        if ("claude".equals(Prefs.voiceEngine(c)) && ClaudeDictation.supports(lang)) {
            return unwatchOnError(l, ClaudeDictation.listen(c, lang, l));
        }
        if ("chatgpt".equals(Prefs.voiceEngine(c))) return unwatchOnError(l, CodexDictation.listen(c, l));
        if (!SpeechRecognizer.isRecognitionAvailable(c)) return unwatchOnError(l, c.getString(R.string.voice_no_recognizer));
        current = l;
        l.onStatus(c.getString(R.string.voice_starting));
        recognizer = SpeechRecognizer.createSpeechRecognizer(c.getApplicationContext());
        recognizer.setRecognitionListener(new RecognitionListener() {
            @Override
            public void onPartialResults(Bundle b) {
                String t = best(b);
                if (t != null && current == l) l.onPartial(t);
            }

            @Override
            public void onResults(Bundle b) {
                String t = best(b);
                finish();
                if (t != null && !t.trim().isEmpty()) l.onFinal(t.trim());
                else l.onEnd(c.getString(R.string.voice_didnt_catch));
            }

            @Override
            public void onError(int error) {
                finish();
                l.onEnd(error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                        ? c.getString(R.string.voice_didnt_catch) : error == SpeechRecognizer.ERROR_CLIENT ? null
                        : c.getString(R.string.voice_error, error));
            }

            @Override
            public void onReadyForSpeech(Bundle b) {
                if (current == l) l.onStatus(null);
            }

            @Override
            public void onRmsChanged(float rms) { // about -2 (quiet) to 10 dB (loud)
                if (current == l) l.onLevel(Math.max(0f, Math.min(1f, (rms + 2f) / 12f)));
            }

            @Override public void onBeginningOfSpeech() {}
            @Override public void onBufferReceived(byte[] buf) {}
            @Override
            public void onEndOfSpeech() {
                if (current == l) l.onStatus(c.getString(R.string.voice_finishing));
            }
            @Override public void onEvent(int type, Bundle b) {}
        });
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, lang)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, lang);
        ArrayList<String> langs = new ArrayList<>(java.util.Arrays.asList(Prefs.voiceLangs(c).split(",")));
        if (langs.size() > 1 && android.os.Build.VERSION.SDK_INT >= 33) {
            // detect which of the user's languages is being spoken
            i.putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_DETECTION, true)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_DETECTION_ALLOWED_LANGUAGES, langs);
        }
        if (langs.size() > 1 && android.os.Build.VERSION.SDK_INT >= 34) {
            // and follow a switch of language mid-sentence
            i.putExtra(RecognizerIntent.EXTRA_ENABLE_LANGUAGE_SWITCH, RecognizerIntent.LANGUAGE_SWITCH_BALANCED)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_SWITCH_ALLOWED_LANGUAGES, langs);
        }
        recognizer.startListening(i);
        return null;
    }

    /** The listening that endpoint() watches now: an earlier one's timer must not end a later one. */
    private static Listener watched;
    private static final android.os.Handler ends = new android.os.Handler(android.os.Looper.getMainLooper());

    /**
     * Buddy's own end of listening (issue #60), on top of the engine's: done once the words heard so far haven't
     * changed for the chosen pause after the user started speaking, or at the longest recording. Noise around (music,
     * a car) keeps the engine listening, as it waits for quiet; it doesn't make new words, so this pause still ends.
     */
    private static Listener endpoint(Context c, final Listener l) {
        final long pause = Prefs.voicePauseMs(c), max = Prefs.voiceMaxMs(c);
        if (pause <= 0 && max <= 0) return l; // Auto: the engine decides, as before
        final long started = android.os.SystemClock.elapsedRealtime();
        final Listener[] me = new Listener[1];
        final String[] last = {""};
        final long[] changed = {0};
        final Runnable tick = new Runnable() {
            @Override
            public void run() {
                if (watched != me[0]) return;
                long now = android.os.SystemClock.elapsedRealtime();
                boolean pauseDone = pause > 0 && !last[0].trim().isEmpty() && now - changed[0] >= pause;
                if ((max > 0 && now - started >= max) || pauseDone) {
                    watched = null;
                    finishListening(); // what was heard goes out, as a tap would send it
                    return;
                }
                ends.postDelayed(this, 200);
            }
        };
        me[0] = new Listener() {
            @Override
            public void onPartial(String text) {
                String t = text == null ? "" : text;
                if (!t.equals(last[0])) {
                    last[0] = t;
                    changed[0] = android.os.SystemClock.elapsedRealtime();
                }
                l.onPartial(text);
            }

            @Override
            public void onFinal(String text) {
                if (watched == me[0]) watched = null;
                l.onFinal(text);
            }

            @Override
            public void onEnd(String message) {
                if (watched == me[0]) watched = null;
                l.onEnd(message);
            }

            @Override
            public void onLevel(float level) {
                l.onLevel(level);
            }

            @Override
            public void onStatus(String status) {
                l.onStatus(status);
            }
        };
        watched = me[0];
        ends.postDelayed(tick, 200);
        return me[0];
    }

    /** Listening didn't start (error): endpoint()'s timer has nothing to end. */
    private static String unwatchOnError(Listener l, String error) {
        if (error != null && watched == l) watched = null;
        return error;
    }

    /** Stop listening now; whatever was heard so far is delivered as the final result. */
    static void finishListening() {
        if (ClaudeDictation.active()) {
            ClaudeDictation.finish();
            return;
        }
        if (CodexDictation.active()) {
            CodexDictation.finish();
            return;
        }
        if (recognizer != null) recognizer.stopListening();
    }

    static void stopListening() {
        if (ClaudeDictation.active()) {
            ClaudeDictation.cancel();
            return;
        }
        if (CodexDictation.active()) {
            CodexDictation.cancel();
            return;
        }
        Listener l = current;
        if (recognizer != null) recognizer.cancel();
        finish();
        if (l != null) l.onEnd(null);
    }

    private static void finish() {
        current = null;
        if (recognizer != null) {
            recognizer.destroy();
            recognizer = null;
        }
    }

    private static String best(Bundle b) {
        ArrayList<String> r = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        return r == null || r.isEmpty() ? null : r.get(0);
    }

    // ------------------------------------------------------------- speaking

    static void speak(Context c, String text) {
        // the words spoken in place of code and links are plain text, not regex replacement syntax
        String code = " " + java.util.regex.Matcher.quoteReplacement(c.getString(R.string.voice_code_omitted)) + " ";
        String link = java.util.regex.Matcher.quoteReplacement(c.getString(R.string.voice_link));
        String clean = text.replaceAll("```[\\s\\S]*?```", code)
                .replaceAll("[*_`#>|]", "")
                .replaceAll("\\[(.*?)\\]\\(.*?\\)", "$1")
                .replaceAll("https?://\\S+", link)
                .trim();
        if (clean.isEmpty()) return;
        SpeechPause.begin(c);
        if ("chatgpt".equals(Prefs.speechEngine(c)) && SetupState.planSignedIn(c, "codex")) {
            final Context app = c.getApplicationContext();
            CodexSpeech.speak(app, clean, t -> { // the phone's voice if ChatGPT's fails
                SpeechPause.begin(app); // what was paused stays so while that voice gets ready
                phoneSpeak(app, t);
            });
            return;
        }
        phoneSpeak(c, clean);
    }

    private static void phoneSpeak(Context c, String clean) {
        appCtx = c.getApplicationContext();
        if (tts == null) {
            pending = clean;
            tts = new TextToSpeech(c.getApplicationContext(), status -> {
                ttsReady = status == TextToSpeech.SUCCESS;
                if (ttsReady) {
                    tts.setAudioAttributes(new AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANT)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
                    if (pending != null) say(pending);
                }
                pending = null;
            });
        } else if (ttsReady) {
            say(clean);
        } else {
            pending = clean;
        }
    }

    /** The user's region for a language ("BR" for pt), or "": from the TTS engine's own voice, then the phone's languages. */
    private static String region(String language) {
        try {
            android.speech.tts.Voice def = tts.getDefaultVoice();
            if (def != null && language.equals(def.getLocale().getLanguage()) && !def.getLocale().getCountry().isEmpty())
                return def.getLocale().getCountry();
        } catch (Exception ignored) { // (some engines throw before a voice is loaded)
        }
        android.os.LocaleList phone = android.os.LocaleList.getDefault();
        for (int i = 0; i < phone.size(); i++) {
            java.util.Locale l = phone.get(i);
            if (language.equals(l.getLanguage()) && !l.getCountry().isEmpty()) return l.getCountry();
        }
        return "";
    }

    /** Speak in the reply's own language: detect it (on a worker thread), then pick a matching voice. */
    private static void say(String text) {
        final Context c = appCtx;
        new Thread(() -> {
            java.util.Locale loc = c == null ? null : detectLanguage(c, text);
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                if (tts == null || !ttsReady) return;
                if (loc != null && tts.isLanguageAvailable(loc) >= TextToSpeech.LANG_AVAILABLE) {
                    // the language comes from the text, the region from the user: the voice set in the phone's
                    // text-to-speech settings, else the phone's own languages (pt-BR, not pt-PT, for a Brazilian user:
                    // issue #84). Same region first, then quality
                    String region = region(loc.getLanguage());
                    tts.setLanguage(region.isEmpty() ? loc : new java.util.Locale(loc.getLanguage(), region));
                    android.speech.tts.Voice best = null;
                    for (android.speech.tts.Voice v : tts.getVoices()) {
                        if (!v.getLocale().getLanguage().equals(loc.getLanguage())) continue;
                        if (v.getFeatures().contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)) continue;
                        boolean mine = !region.isEmpty() && region.equalsIgnoreCase(v.getLocale().getCountry());
                        boolean bestMine = best != null && !region.isEmpty() && region.equalsIgnoreCase(best.getLocale().getCountry());
                        if (best == null || (mine && !bestMine) || (mine == bestMine && (v.getQuality() > best.getQuality()
                                || (v.getQuality() == best.getQuality() && v.getLocale().equals(loc))))) best = v;
                    }
                    if (best != null) tts.setVoice(best);
                }
                tts.speak(text, TextToSpeech.QUEUE_ADD, new Bundle(), "buddy-" + System.nanoTime());
            });
        }, "tts-language").start();
    }

    private static Context appCtx;

    private static java.util.Locale detectLanguage(Context c, String text) {
        try {
            android.view.textclassifier.TextClassificationManager m =
                    c.getSystemService(android.view.textclassifier.TextClassificationManager.class);
            android.view.textclassifier.TextLanguage r = m.getTextClassifier().detectLanguage(
                    new android.view.textclassifier.TextLanguage.Request.Builder(text).build());
            if (r.getLocaleHypothesisCount() > 0 && r.getConfidenceScore(r.getLocale(0)) > 0.5f) {
                return r.getLocale(0).toLocale();
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** Buddy is reading something aloud (phone voice or ChatGPT's). */
    static boolean isSpeaking() {
        return CodexSpeech.active() || (tts != null && ttsReady && tts.isSpeaking());
    }

    static void stopSpeaking() {
        CodexSpeech.stop();
        if (tts != null && ttsReady) tts.stop();
    }
}
