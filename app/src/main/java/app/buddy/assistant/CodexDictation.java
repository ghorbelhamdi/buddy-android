package app.buddy.assistant;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.webkit.JavascriptInterface;
import android.webkit.PermissionRequest;
import android.webkit.WebChromeClient;
import android.webkit.WebView;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Voice input through ChatGPT's voice on the user's ChatGPT plan, via Codex. Codex's app server (in Buddy's
 * Linux) starts a realtime voice session over WebRTC; the phone side of the call is a small page in a WebView
 * (Android's WebRTC). Buddy only wants the transcript of what the user says: the voice's replies are never
 * played, the session runs read-only with no phone tools, and any Codex task it tries to start is stopped.
 */
final class CodexDictation {
    static final String DIR = "/root/dictation";
    private static final String PROMPT = "You are a dictation microphone. The user is dictating a message to send "
            + "elsewhere, not talking to you. Never answer, never ask questions, never hand anything off or start "
            + "tasks. Stay silent.";

    private static final Handler main = new Handler(Looper.getMainLooper());
    private static Process server;
    private static OutputStream toServer;
    private static String threadId;
    private static int nextId;
    private static final Map<Integer, JSONObject[]> replies = new HashMap<>();

    private static Voice.Listener listener;
    private static WebView web;
    private static boolean finishing, drained, connected;
    private static final StringBuilder done = new StringBuilder();
    private static String partial = "";
    private static long lastHeard;
    /** How far the call got (a string resource), for the message when it doesn't connect. */
    private static volatile int stage = R.string.dictation_stage_page;

    private CodexDictation() {
    }

    static boolean active() {
        return listener != null;
    }

    // ------------------------------------------------------------------ listening

    static String listen(Context c, Voice.Listener l) {
        if (!SetupState.signedIn(c, "codex")) return c.getString(R.string.dictation_codex_sign_in);
        final Context app = c.getApplicationContext();
        listener = l;
        finishing = drained = connected = false;
        stage = R.string.dictation_stage_page;
        done.setLength(0);
        partial = "";
        l.onPartial("");
        // Buddy records from now on (the WebView can't open the mic on some phones); ChatGPT hears it all once connected
        final Voice.EndOfSpeech eos = new Voice.EndOfSpeech();
        MicServer.levels = v -> main.post(() -> {
            if (listener != l || finishing) return;
            l.onLevel(v);
            if (eos.feed(v)) finish(); // the user paused: send, like the phone's recognizer does
        });
        if (!MicServer.startCapture(app)) {
            listener = null;
            MicServer.levels = null;
            return c.getString(R.string.dictation_mic_failed);
        }
        l.onStatus(null);
        try {
            openPage(c);
        } catch (Exception e) {
            listener = null;
            return c.getString(R.string.dictation_chatgpt_start_failed, e.getMessage());
        }
        new Thread(() -> {
            try {
                ensureServer(app);
            } catch (Exception e) {
                main.post(() -> end(l, app.getString(R.string.dictation_chatgpt_start_failed, e.getMessage())));
            }
        }, "codex-dictation").start();
        return null;
    }

    /** The user is done: let the page send what's still buffered, then collect the words. */
    static void finish() {
        if (listener == null || finishing) return;
        finishing = true;
        final Voice.Listener l = listener;
        l.onStatus(BuddyApp.str(R.string.dictation_transcribing));
        MicServer.stopCapture();
        final long asked = System.currentTimeMillis();
        main.post(new Runnable() {
            @Override
            public void run() {
                if (listener != l) return;
                long now = System.currentTimeMillis();
                if (!connected) {
                    if (now - asked > 30_000) end(l, BuddyApp.str(R.string.dictation_chatgpt_no_connect, BuddyApp.str(stage)));
                    else main.postDelayed(this, 100);
                    return;
                }
                // all audio sent, then the words settle (ChatGPT marks the end of a turn after a short silence)
                boolean settled = drained && partial.isEmpty() && now - lastHeard > 600 && now - asked > 1000;
                boolean quiet = drained && now - Math.max(lastHeard, asked) > 3500;
                if (settled && done.length() > 0 || quiet || now - asked > 20_000) {
                    String text = (done + " " + partial).replaceAll("\\s+", " ").trim();
                    stopSession();
                    listener = null;
                    if (text.isEmpty()) l.onEnd(BuddyApp.str(R.string.dictation_no_speech));
                    else l.onFinal(text);
                    return;
                }
                main.postDelayed(this, 100);
            }
        });
    }

    static void cancel() {
        Voice.Listener l = listener;
        listener = null;
        stopSession();
        if (l != null) l.onEnd(null);
    }

    private static void end(Voice.Listener l, String message) {
        if (listener == l) listener = null;
        stopSession();
        l.onEnd(message);
    }

    private static void stopSession() {
        finishing = false;
        MicServer.levels = null;
        MicServer.endCapture();
        if (web != null) {
            web.destroy();
            web = null;
        }
        final String t = threadId;
        if (t != null && server != null) {
            new Thread(() -> {
                try {
                    request("thread/realtime/stop", new JSONObject().put("threadId", t), 5000);
                } catch (Exception ignored) {
                }
            }, "codex-dictation-stop").start();
        }
    }

    // ------------------------------------------------------------------ the call (WebView)

    @SuppressLint({"SetJavaScriptEnabled", "AddJavascriptInterface"})
    private static void openPage(Context c) {
        if (web != null) web.destroy();
        web = new WebView(c.getApplicationContext());
        web.getSettings().setJavaScriptEnabled(true);
        web.getSettings().setMediaPlaybackRequiresUserGesture(false);
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public void onPermissionRequest(PermissionRequest r) {
                r.grant(new String[]{PermissionRequest.RESOURCE_AUDIO_CAPTURE});
            }
        });
        final WebView mine = web;
        web.addJavascriptInterface(new Object() {
            @JavascriptInterface
            public void offer(String sdp) {
                stage = R.string.dictation_stage_codex;
                new Thread(() -> startCall(mine, sdp), "codex-dictation-call").start();
            }

            /** The next bit of recorded audio (16-bit mono, 16 kHz) as base64; "" when there's none yet. */
            @JavascriptInterface
            public String pull(int bytes) {
                if (web != mine) return "";
                byte[] b = MicServer.pull(bytes);
                return android.util.Base64.encodeToString(b, android.util.Base64.NO_WRAP);
            }

            /** The page has played everything it was given. */
            @JavascriptInterface
            public void idle() {
                if (!MicServer.recording() && MicServer.pull(2).length == 0) main.post(() -> {
                    if (web == mine) drained = true;
                });
            }

            @JavascriptInterface
            public void connected() {
                main.post(() -> {
                    if (web == mine) connected = true;
                });
            }

            @JavascriptInterface
            public void failed(String why) {
                main.post(() -> {
                    // the page's own 'the connection failed' (see PAGE) in the user's language; other reasons come from the WebView
                    if (web == mine && listener != null) end(listener, BuddyApp.str(R.string.dictation_chatgpt_error,
                            "the connection failed".equals(why) ? BuddyApp.str(R.string.dictation_connection_failed) : why));
                });
            }
        }, "Buddy");
        web.loadDataWithBaseURL("https://buddy.local/", PAGE, "text/html", "utf-8", null);
    }

    /** Hand the page's offer to Codex, which places the call with ChatGPT; the answer comes back as a notification. */
    private static void startCall(WebView page, String sdp) {
        try {
            for (int i = 0; i < 600 && threadId == null; i++) Thread.sleep(100); // the server is still starting
            if (threadId == null) throw new Exception(BuddyApp.str(R.string.dictation_codex_no_start));
            stage = R.string.dictation_stage_call;
            JSONObject r = request("thread/realtime/start", new JSONObject()
                    .put("threadId", threadId)
                    .put("transport", new JSONObject().put("type", "webrtc").put("sdp", sdp))
                    .put("version", "v3") // ChatGPT's voice (AVAS) over WebRTC only accepts v1 headers as v3
                    .put("outputModality", "audio")
                    .put("delegationAckFiller", false)
                    .put("prompt", PROMPT)
                    .put("includeStartupContext", false), 30_000);
            if (r.has("error")) throw new Exception(r.getJSONObject("error").optString("message"));
            stage = R.string.dictation_stage_answer;
        } catch (Exception e) {
            main.post(() -> {
                if (web == page && listener != null) end(listener, BuddyApp.str(R.string.dictation_chatgpt_start_failed, e.getMessage()));
            });
        }
    }

    private static void js(String code) {
        if (web != null) web.evaluateJavascript(code, null);
    }

    // ------------------------------------------------------------------ Codex's app server

    static synchronized void ensureServer(Context c) throws Exception {
        if (server != null && server.isAlive() && threadId != null) return;
        if (server != null) server.destroy();
        threadId = null;
        appContext = c.getApplicationContext();
        // Buddy's phone tools are only given to chats (on their command line), never here; no app connectors either
        Linux.exec(c, "mkdir -p " + DIR, 30);
        Process p = Linux.builder(c, "/bin/sh", "-c", "cd " + DIR + " && exec codex app-server -c features.apps=false 2>" + DIR + "/server.log").start();
        server = p;
        toServer = p.getOutputStream();
        Thread reader = new Thread(() -> read(p), "codex-dictation-server");
        reader.setDaemon(true);
        reader.start();
        JSONObject init = request("initialize", new JSONObject()
                .put("clientInfo", new JSONObject().put("name", "buddy").put("version", BuildInfo.VERSION))
                .put("capabilities", new JSONObject().put("experimentalApi", true)), 60_000);
        if (init.has("error")) throw new Exception(init.getJSONObject("error").optString("message"));
        send(new JSONObject().put("method", "initialized"));
        threadId = newThread();
    }

    private static final Map<String, StringBuilder> reviews = new java.util.concurrent.ConcurrentHashMap<>();
    private static String reviewModel;

    /**
     * A temporary Codex session that answers one question (the smart reviewer): its own instructions in place
     * of Codex's coding ones, the smallest fast model, low effort, read-only and gone afterwards.
     */
    static String review(Context c, String system, String prompt, long timeoutMs) throws Exception {
        ensureServer(c);
        if (reviewModel == null) {
            String pick = "";
            try {
                org.json.JSONArray models = request("model/list", new JSONObject(), 20_000).getJSONObject("result").getJSONArray("data");
                for (int i = 0; i < models.length() && pick.isEmpty(); i++) {
                    String id = models.getJSONObject(i).optString("id", models.getJSONObject(i).optString("model"));
                    if (id.contains("luna") || id.contains("mini")) pick = id;
                }
            } catch (Exception ignored) {
            }
            reviewModel = pick;
        }
        JSONObject ts = new JSONObject().put("ephemeral", true).put("cwd", DIR).put("sandbox", "read-only")
                .put("approvalPolicy", "never").put("baseInstructions", system);
        String chosen = Prefs.reviewerModel(c, "codex"); // the user's pick, else the small model found above
        if (!chosen.isEmpty()) ts.put("model", chosen);
        else if (!reviewModel.isEmpty()) ts.put("model", reviewModel);
        JSONObject t = request("thread/start", ts, 30_000);
        if (t.has("error")) throw new Exception(t.getJSONObject("error").optString("message"));
        String tid = t.getJSONObject("result").getJSONObject("thread").getString("id");
        StringBuilder out = new StringBuilder();
        reviews.put(tid, out);
        try {
            JSONObject r = request("turn/start", new JSONObject().put("threadId", tid).put("effort", "low")
                    .put("input", new org.json.JSONArray().put(new JSONObject().put("type", "text").put("text", prompt))), 30_000);
            if (r.has("error")) throw new Exception(r.getJSONObject("error").optString("message"));
            long until = System.currentTimeMillis() + timeoutMs;
            synchronized (out) {
                while (out.indexOf("\u0000") < 0 && System.currentTimeMillis() < until) out.wait(200);
            }
            if (out.indexOf("\u0000") < 0) throw new Exception(c.getString(R.string.reviewer_timed_out)); // shown on the card
            return out.toString().replace("\u0000", "");
        } finally {
            reviews.remove(tid);
        }
    }

    /** The running app server (a new one means conversations made on the old one are gone). */
    static Process server() {
        return server;
    }

    /** A conversation that can't do anything (read-only, no tools); voice sessions are attached to one. */
    static String newThread() throws Exception {
        JSONObject t = request("thread/start", new JSONObject()
                .put("ephemeral", true)
                .put("cwd", DIR)
                .put("sandbox", "read-only")
                .put("approvalPolicy", "never")
                .put("developerInstructions", "Voice only. Do nothing: no commands, no file access, no tools. Reply \"ok\"."), 60_000);
        if (t.has("error")) throw new Exception(t.getJSONObject("error").optString("message"));
        return t.getJSONObject("result").getJSONObject("thread").getString("id");
    }

    /** Start Codex ahead of time (a chat opened) so the first tap on the mic is quick. */
    static void prepare(Context c) {
        if (!SetupState.signedIn(c, "codex") || (server != null && server.isAlive() && threadId != null)) return;
        final Context app = c.getApplicationContext();
        new Thread(() -> {
            try {
                ensureServer(app);
            } catch (Exception ignored) {
            }
        }, "codex-dictation-prep").start();
    }

    private static void read(Process p) {
        try (BufferedReader in = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
            String line;
            while ((line = in.readLine()) != null) {
                JSONObject m;
                try {
                    m = new JSONObject(line);
                } catch (Exception e) {
                    continue;
                }
                if (m.has("id") && m.has("method")) { // the server asks us something (an approval): always no
                    send(new JSONObject().put("id", m.get("id")).put("error",
                            new JSONObject().put("code", -32000).put("message", "declined")));
                } else if (m.has("id")) {
                    JSONObject[] slot;
                    synchronized (replies) {
                        slot = replies.get(m.optInt("id"));
                    }
                    if (slot != null) synchronized (slot) {
                        slot[0] = m;
                        slot.notifyAll();
                    }
                } else if (m.has("method")) {
                    notification(m.getString("method"), m.optJSONObject("params"));
                }
            }
        } catch (Exception ignored) {
        }
        if (server == p) {
            server = null;
            threadId = null;
        }
    }

    private static void notification(String method, JSONObject p) throws Exception {
        if (p == null) return;
        StringBuilder review = reviews.get(p.optString("threadId"));
        if (review != null) { // a reviewer's own turn: collect its answer (and don't interrupt it)
            synchronized (review) {
                if ("item/agentMessage/delta".equals(method)) review.append(p.optString("delta"));
                else if ("turn/completed".equals(method)) {
                    review.append('\u0000');
                    review.notifyAll();
                }
            }
            return;
        }
        if (CodexLive.owns(p.optString("threadId"))) { // a live call's conversation: logged and handled there
            CodexLive.notification(method, p);
            if (method.startsWith("thread/realtime/")) return;
        }
        if (method.startsWith("thread/realtime/") && CodexSpeech.owns(p.optString("threadId"))) {
            CodexSpeech.notification(method, p);
            return;
        }
        switch (method) {
            case "turn/started": { // the voice handed something to Codex: stop it, dictation never runs tasks (and live
                // voice hands it to the chat instead, see CodexLive)
                String turn = p.has("turn") ? p.getJSONObject("turn").optString("id") : p.optString("turnId");
                send(new JSONObject().put("id", ++nextId).put("method", "turn/interrupt")
                        .put("params", new JSONObject().put("threadId", p.optString("threadId", threadId)).put("turnId", turn)));
                break;
            }
            case "thread/realtime/sdp": {
                stage = R.string.dictation_stage_connecting;
                final String sdp = p.optString("sdp");
                main.post(() -> js("answer(" + JSONObject.quote(sdp) + ")"));
                break;
            }
            case "thread/realtime/transcript/delta":
                if (!"assistant".equals(p.optString("role"))) {
                    final String d = p.optString("delta");
                    main.post(() -> heard(d, false));
                }
                break;
            case "thread/realtime/transcript/done":
                if (!"assistant".equals(p.optString("role"))) {
                    final String t = p.optString("text");
                    main.post(() -> heard(t, true));
                }
                break;
            case "thread/realtime/error": {
                final String msg = p.optString("message");
                main.post(() -> {
                    if (listener != null) end(listener, BuddyApp.str(R.string.dictation_chatgpt_error, msg));
                });
                break;
            }
        }
    }

    private static void heard(String text, boolean final_) {
        if (listener == null) return;
        lastHeard = System.currentTimeMillis();
        if (final_) {
            if (done.length() > 0) done.append(' ');
            done.append(text.trim());
            partial = "";
        } else {
            partial += text;
        }
        listener.onPartial((done + " " + partial).replaceAll("\\s+", " ").trim());
    }

    private static void send(JSONObject m) throws Exception {
        byte[] b = (m.toString() + "\n").getBytes(StandardCharsets.UTF_8);
        synchronized (CodexDictation.class) {
            toServer.write(b);
            toServer.flush();
        }
    }

    private static final JSONObject DIED = new JSONObject();
    private static Context appContext;

    /** The end of Codex's error output, to say why it stopped. */
    private static String serverLog() {
        try {
            String t = Linux.exec(appContext, "tail -c 400 " + DIR + "/server.log | grep -v bubblewrap | tail -3", 20).trim();
            return t.isEmpty() ? BuddyApp.str(R.string.dictation_no_details) : t;
        } catch (Exception e) {
            return BuddyApp.str(R.string.dictation_no_details);
        }
    }

    static JSONObject request(String method, JSONObject params, long timeoutMs) throws Exception {
        int id;
        JSONObject[] slot = new JSONObject[1];
        synchronized (replies) {
            id = ++nextId;
            replies.put(id, slot);
        }
        try {
            send(new JSONObject().put("id", id).put("method", method).put("params", params));
            synchronized (slot) {
                long until = System.currentTimeMillis() + timeoutMs;
                while (slot[0] == null && System.currentTimeMillis() < until) {
                    slot.wait(Math.max(1, Math.min(300, until - System.currentTimeMillis())));
                    Process p = server;
                    if (slot[0] == null && (p == null || !p.isAlive())) slot[0] = DIED;
                }
            }
            // the messages reach the user (ChatGPT voice errors, the reviewer's card)
            if (slot[0] == null) throw new Exception(BuddyApp.str(R.string.dictation_request_timed_out, method));
            if (slot[0] == DIED) throw new Exception(BuddyApp.str(R.string.dictation_codex_stopped, serverLog()));
            return slot[0];
        } finally {
            synchronized (replies) {
                replies.remove(id);
            }
        }
    }

    /**
     * The phone side of the call. Buddy records from the tap (MicServer); once the call is connected the page
     * plays that recording into it at real time from the start, so nothing said while connecting is lost.
     */
    private static final String PAGE = "<!doctype html><html><body><script>\n"
            + "let pc, connected = false;\n"
            + "async function start() {\n"
            + "  try {\n"
            + "    const ctx = new AudioContext({sampleRate: 16000}); ctx.resume();\n"
            + "    const ratio = 16000 / ctx.sampleRate;\n" // the WebView may run at its own rate: resample
            + "    const proc = ctx.createScriptProcessor(1024, 1, 1);\n"
            + "    const dest = ctx.createMediaStreamDestination();\n"
            + "    const idle = ctx.createConstantSource(); idle.offset.value = 0; idle.connect(proc); idle.start();\n"
            + "    let fifo = new Float32Array(0), pos = 0, playing = false;\n"
            + "    proc.onaudioprocess = e => {\n"
            + "      const out = e.outputBuffer.getChannelData(0); out.fill(0);\n"
            + "      if (!connected) return;\n" // Buddy keeps recording meanwhile: nothing is lost
            + "      const bin = atob(Buddy.pull(6400));\n" // up to 200 ms per call: catches up on the backlog
            + "      if (bin.length > 1) {\n"
            + "        const add = new Float32Array(bin.length >> 1);\n"
            + "        for (let i = 0; i < add.length; i++) { let v = bin.charCodeAt(2 * i) | (bin.charCodeAt(2 * i + 1) << 8); if (v > 32767) v -= 65536; add[i] = v / 32768; }\n"
            + "        const f = new Float32Array(fifo.length + add.length); f.set(fifo); f.set(add, fifo.length); fifo = f;\n"
            + "      }\n"
            + "      const need = Math.ceil(out.length * ratio + pos) + 2;\n"
            + "      if (!playing && fifo.length < need + 2400) { if (!bin.length) Buddy.idle(); return; }\n" // keep a 150 ms cushion: no gaps
            + "      playing = true;\n"
            + "      if (fifo.length < need) { if (!bin.length) Buddy.idle(); return; }\n"
            + "      for (let i = 0; i < out.length; i++) { const x = i * ratio + pos, k = x | 0, t = x - k; out[i] = fifo[k] * (1 - t) + fifo[k + 1] * t; }\n"
            + "      const end = out.length * ratio + pos, used = Math.floor(end); pos = end - used; fifo = fifo.subarray(used);\n"
            + "    };\n"
            + "    proc.connect(dest);\n"
            + "    pc = new RTCPeerConnection();\n"
            + "    pc.addTrack(dest.stream.getAudioTracks()[0], dest.stream);\n"
            + "    pc.createDataChannel('oai-events');\n"
            + "    pc.ontrack = e => { e.track.enabled = false; };\n" // never play the voice's replies
            + "    pc.onconnectionstatechange = () => {\n"
            + "      if (pc.connectionState === 'connected') { connected = true; Buddy.connected(); }\n"
            + "      else if (pc.connectionState === 'failed') Buddy.failed('the connection failed');\n"
            + "    };\n"
            + "    await pc.setLocalDescription(await pc.createOffer());\n"
            + "    await new Promise(r => { if (pc.iceGatheringState === 'complete') r(); else { pc.onicegatheringstatechange = () => { if (pc.iceGatheringState === 'complete') r(); }; setTimeout(r, 1500); } });\n"
            + "    Buddy.offer(pc.localDescription.sdp);\n"
            + "  } catch (e) { Buddy.failed(String(e && e.message || e)); }\n"
            + "}\n"
            + "function answer(sdp) { pc.setRemoteDescription({type: 'answer', sdp}).catch(e => Buddy.failed(String(e.message || e))); }\n"
            + "start();\n"
            + "</script></body></html>";
}
