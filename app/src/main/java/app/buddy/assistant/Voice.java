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

    static boolean listening() {
        return current != null;
    }

    /** Start listening. Returns an error message, or null when started. */
    static String listen(Context c, Listener l) {
        if (!micAllowed(c)) return "Buddy needs the microphone. Open the Buddy app and tap 🎙 once to allow it.";
        if (!SpeechRecognizer.isRecognitionAvailable(c)) return "No speech recognition service on this phone.";
        stopListening();
        stopSpeaking();
        current = l;
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
                else l.onEnd("Didn't catch that.");
            }

            @Override
            public void onError(int error) {
                finish();
                l.onEnd(error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                        ? "Didn't catch that." : error == SpeechRecognizer.ERROR_CLIENT ? null
                        : "Speech recognition error " + error + ".");
            }

            @Override public void onReadyForSpeech(Bundle b) {}
            @Override public void onBeginningOfSpeech() {}
            @Override public void onRmsChanged(float rms) {}
            @Override public void onBufferReceived(byte[] buf) {}
            @Override public void onEndOfSpeech() {}
            @Override public void onEvent(int type, Bundle b) {}
        });
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        recognizer.startListening(i);
        return null;
    }

    /** Stop listening now; whatever was heard so far is delivered as the final result. */
    static void finishListening() {
        if (recognizer != null) recognizer.stopListening();
    }

    static void stopListening() {
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
        String clean = text.replaceAll("```[\\s\\S]*?```", " code omitted. ")
                .replaceAll("[*_`#>|]", "")
                .replaceAll("\\[(.*?)\\]\\(.*?\\)", "$1")
                .replaceAll("https?://\\S+", "link")
                .trim();
        if (clean.isEmpty()) return;
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

    private static void say(String text) {
        Bundle p = new Bundle();
        tts.speak(text, TextToSpeech.QUEUE_ADD, p, "buddy-" + System.nanoTime());
    }

    static void stopSpeaking() {
        if (tts != null && ttsReady) tts.stop();
    }
}
