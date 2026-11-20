package app.buddy.assistant;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

/**
 * Models on this phone (issue #63): Buddy downloads llama.cpp's official Android build and a model, and runs the model
 * itself. The engine runs on the phone's NPU (Snapdragon's Hexagon), its GPU or its CPU: Auto takes the fastest that
 * starts. The models show up as the "On this phone" service (a local one: lite mode is on for its chats), and the
 * relay starts the engine with the model a request asks for, then stops it once nothing used it for a while.
 *
 * The engine is a downloaded program: Android lets an app run one only through the system's own loader (linker64),
 * which Buddy does from its Linux folder, where onphone-get puts it. Nothing is in the APK: F-Droid's build has the
 * same code, and the NPU part needs Qualcomm's SDK to build, which F-Droid doesn't have.
 */
final class OnPhone {
    /** The service's id in Providers. */
    static final String ID = "onphone";
    /** Where the engine listens (127.0.0.1 only, with a key of its own). */
    static final int PORT = 8772;
    /** The context the engine gives a chat (most models): OpenCode keeps 16k of it free for the answer. */
    static final int CONTEXT = 49152;

    /** llama.cpp's release: the Snapdragon build (CPU, Adreno GPU, Hexagon NPU), or the plain one for other phones. */
    static final String ENGINE = "b12549";
    private static final String ENGINE_URL = "https://github.com/ggml-org/llama.cpp/releases/download/" + ENGINE + "/llama-"
            + ENGINE + "-bin-android-arm64";
    private static final String SNAPDRAGON_SHA = "fd848ac5fdb1d43d5268b9bf4624fd7f02808d6ef2fccd9bd098bb0ae574ea2e",
            PLAIN_SHA = "c9857371a99c8175a5dc362251622fb7480c68a771e0dcac770d17588cad125b";
    private static final long SNAPDRAGON_SIZE = 67074518L, PLAIN_SIZE = 80212138L;

    /** A model Buddy offers: tested with the engine and Buddy's tools. */
    static final class Model {
        final String id, name, file, url, sha256, license;
        final long size;
        /** The context it runs with: tested to start on the NPU (a bigger one can't be mapped for it from an app). */
        final int context;
        /** Its strings: what it's good for (R.string). */
        final int note;

        Model(String id, String name, String repo, String rev, String file, long size, String sha256, String license, int note,
              int context) {
            this.id = id;
            this.context = context;
            this.name = name;
            this.file = file;
            this.url = "https://huggingface.co/" + repo + "/resolve/" + rev + "/" + file;
            this.size = size;
            this.sha256 = sha256;
            this.license = license;
            this.note = note;
        }
    }

    static final Model[] MODELS = {
            new Model("gemma-4-e2b", "Gemma 4 E2B", "google/gemma-4-E2B-it-qat-q4_0-gguf",
                    "675cff42a74c774d6cb76f76d8eacb49b48c9b93", "gemma-4-E2B_q4_0-it.gguf", 3349516256L,
                    "fa401b55b07ee70a54c6dae3903c783a6e65064312529ea57175cb5f8dec6634", "Apache-2.0",
                    R.string.onphone_note_gemma_e2b, CONTEXT),
            new Model("gemma-4-e4b", "Gemma 4 E4B", "google/gemma-4-E4B-it-qat-q4_0-gguf",
                    "4b4a2c1d584be7264f87aac328a1bc739ce81b6c", "gemma-4-E4B_q4_0-it.gguf", 5154941280L,
                    "676c35070db6dbe52f93e9c864ee0fba4eddea94b9c875d9cb10daff453fbaee", "Apache-2.0",
                    R.string.onphone_note_gemma_e4b, CONTEXT),
            new Model("qwen3.5-2b", "Qwen3.5 2B", "unsloth/Qwen3.5-2B-GGUF",
                    "f6d5376be1edb4d416d56da11e5397a961aca8ae", "Qwen3.5-2B-Q4_0.gguf", 1214873856L,
                    "cd70221bebaee0503e0f6717e174250cd7825aa88438b3aabec9ad55731d9bb1", "Apache-2.0",
                    R.string.onphone_note_qwen_2b, CONTEXT),
            new Model("qwen3.5-4b", "Qwen3.5 4B", "unsloth/Qwen3.5-4B-GGUF",
                    "e87f176479d0855a907a41277aca2f8ee7a09523", "Qwen3.5-4B-Q4_0.gguf", 2583221408L,
                    "298fcb5fe7a77ccc79745ae24751560c5ac56874caff4bb39b1f2055bd72b8bb", "Apache-2.0",
                    R.string.onphone_note_qwen_4b, 32768), // at 49152 its cache didn't map for the NPU
    };

    /** Where it runs: the user's pick ("auto" takes the first that starts, in this order). */
    static final String[] DEVICES = {"auto", "npu", "gpu", "cpu"};

    private OnPhone() {
    }

    private static SharedPreferences sp(Context c) {
        return c.getSharedPreferences("onphone", Context.MODE_PRIVATE);
    }

    static Model model(String id) {
        for (Model m : MODELS) if (m.id.equals(id)) return m;
        return null;
    }

    private static File dir(Context c) {
        return new File(Linux.root(c), "root/.buddy/onphone");
    }

    /** A Snapdragon phone: the build with the Adreno GPU and Hexagon NPU. Others get the plain build (CPU). */
    static boolean snapdragon() {
        String soc = Build.VERSION.SDK_INT >= 31 ? Build.SOC_MANUFACTURER : "";
        return "QTI".equalsIgnoreCase(soc) || "Qualcomm".equalsIgnoreCase(soc) || Build.HARDWARE.startsWith("qcom");
    }

    static long engineSize() {
        return snapdragon() ? SNAPDRAGON_SIZE : PLAIN_SIZE;
    }

    static boolean engineReady(Context c) {
        return new File(dir(c), "engine/bin/llama-server").isFile()
                && ENGINE.equals(sp(c).getString("engine", "")) && snapdragon() == sp(c).getBoolean("engine_snapdragon", false);
    }

    static boolean downloaded(Context c, Model m) {
        return new File(dir(c), "models/" + m.file).isFile();
    }

    static List<Model> downloaded(Context c) {
        List<Model> out = new ArrayList<>();
        for (Model m : MODELS) if (downloaded(c, m)) out.add(m);
        return out;
    }

    /** Bytes of it on the phone so far (a download in progress: its .part file). */
    static long progress(Context c, Model m) {
        File f = new File(dir(c), "models/" + m.file + ".part");
        return f.isFile() ? f.length() : 0;
    }

    static long engineProgress(Context c) {
        File f = new File(dir(c), "tmp/engine.tgz");
        return f.isFile() ? f.length() : 0;
    }

    static String device(Context c) {
        return sp(c).getString("device", "auto");
    }

    static void setDevice(Context c, String d) {
        if (d.equals(device(c))) return; // (the one already picked: a chat's model keeps running)
        sp(c).edit().putString("device", d).apply();
        stopWhenIdle(); // the next request starts it there (an answer under way finishes first)
    }

    /** Where it ran last ("npu", "gpu", "cpu"), or "" before its first start. */
    static String ranOn(Context c) {
        return sp(c).getString("ran_on", "");
    }

    // ------------------------------------------------------------------ downloads

    /** Runs onphone-get in Buddy's Linux; null when it went through, else what went wrong. */
    private static String get(Context c, String... args) {
        try {
            Helper.sync(c); // (the script comes with the app's Linux files)
            String[] cmd = new String[args.length + 2];
            cmd[0] = "/bin/bash";
            cmd[1] = "/root/bin/onphone-get";
            System.arraycopy(args, 0, cmd, 2, args.length);
            Process p = Linux.builder(c, cmd).redirectErrorStream(true).start();
            p.getOutputStream().close();
            StringBuilder out = new StringBuilder();
            try (InputStream in = p.getInputStream()) {
                byte[] b = new byte[4096];
                int n;
                while ((n = in.read(b)) > 0) if (out.length() < 4000) out.append(new String(b, 0, n, "UTF-8"));
            }
            int code = p.waitFor();
            String said = out.toString().trim();
            return code == 0 && said.endsWith("OK") ? null : said.isEmpty() ? "exit " + code : said;
        } catch (Exception e) {
            return String.valueOf(e.getMessage());
        }
    }

    /** Downloads and the engine each have a lock of their own: the screen never waits behind either. */
    private static final Object DOWNLOADS = new Object(), ENGINE_LOCK = new Object();

    /** Fetches the engine if it's missing or another version; null when it's there. */
    static String getEngine(Context c) {
        synchronized (DOWNLOADS) {
            return getEngine0(c);
        }
    }

    private static String getEngine0(Context c) {
        if (engineReady(c)) return null;
        boolean sd = snapdragon();
        String err = get(c, "engine", ENGINE_URL + (sd ? "-snapdragon" : "") + ".tar.gz", sd ? SNAPDRAGON_SHA : PLAIN_SHA);
        if (err == null) sp(c).edit().putString("engine", ENGINE).putBoolean("engine_snapdragon", sd).apply();
        return err;
    }

    /** Fetches the engine (if needed) and the model, then lists it as one of the service's; null when done. */
    static String download(Context c, Model m) {
        String err = getEngine(c);
        if (err == null && !downloaded(c, m)) err = get(c, "model", m.url, m.sha256, m.file);
        if (err == null) sync(c);
        return err;
    }

    /** Set when the user cancels a download: its end isn't shown as a failure. */
    static volatile boolean cancelled;

    /**
     * Stops the screen's download (in the background: it waits on Buddy's Linux). The model's only, unless the
     * engine is what it's fetching now: a chat may be fetching the engine too (ensure), and keeps it.
     */
    static void cancel(Context c) {
        cancelled = true;
        final Context app = c.getApplicationContext();
        final boolean engine = !engineReady(c);
        new Thread(() -> {
            try { // curl first, then the script; the [x] keeps each pattern from matching this command's own shell
                Linux.exec(app, engine ? "pkill -f '[c]url -sSfL --retry 3' ; pkill -f '[o]nphone-get'"
                        : "pkill -f '[c]url -sSfL --retry 3 .*[.]part' ; pkill -f '[o]nphone-get model'", 10);
            } catch (Exception ignored) {
            }
        }, "onphone-cancel").start();
    }

    static void delete(Context c, Model m) {
        if (m.id.equals(running)) stopWhenIdle();
        //noinspection ResultOfMethodCallIgnored
        new File(dir(c), "models/" + m.file).delete();
        //noinspection ResultOfMethodCallIgnored
        new File(dir(c), "models/" + m.file + ".part").delete();
        sync(c);
    }

    /**
     * The "On this phone" service, as the models on the phone are: there while there's one, with a key only Buddy
     * knows (another app on the phone can reach 127.0.0.1 too). Its models don't come from asking the server, which
     * runs only while a chat uses it (see Providers.refresh).
     */
    static synchronized void sync(Context c) {
        try {
            List<Model> have = downloaded(c);
            Providers.P p = Providers.find(ID);
            if (have.isEmpty()) {
                if (p != null) Providers.remove(c, ID);
                return;
            }
            JSONArray models = new JSONArray();
            for (Model m : have) {
                models.put(new JSONObject().put("id", m.id).put("name", m.name).put("window", m.context)
                        .put("efforts", new JSONArray()));
            }
            synchronized (Providers.class) { // (as Providers itself changes them: the relay reads them meanwhile)
                if (p == null) p = new Providers.P(ID);
                p.name = c.getString(R.string.onphone_service);
                p.openai = "http://127.0.0.1:" + PORT + "/v1";
                p.anthropic = "";
                p.models = models;
            }
            String key = Secrets.get(c, Providers.secretName(ID));
            if (key == null || key.isEmpty()) {
                byte[] b = new byte[24];
                new java.security.SecureRandom().nextBytes(b);
                key = android.util.Base64.encodeToString(b, android.util.Base64.NO_WRAP | android.util.Base64.URL_SAFE);
            }
            Providers.save(c, p, key);
        } catch (Exception e) {
            android.util.Log.w("Buddy", "onphone sync", e);
        }
    }

    // ------------------------------------------------------------------ heat

    /** Android's own reading: severe and up is where it starts closing apps to cool the phone down. */
    static boolean tooHot(Context c) {
        android.os.PowerManager pm = c.getSystemService(android.os.PowerManager.class);
        return pm != null && pm.getCurrentThermalStatus() >= android.os.PowerManager.THERMAL_STATUS_SEVERE;
    }

    private static boolean watching;

    /** While the engine runs: stopped as soon as the phone gets too hot (a chat then says why on its next step). */
    private static synchronized void watchHeat(Context c) {
        if (watching) return;
        android.os.PowerManager pm = c.getSystemService(android.os.PowerManager.class);
        if (pm == null) return;
        watching = true;
        pm.addThermalStatusListener(c.getMainExecutor(), status -> {
            if (status >= android.os.PowerManager.THERMAL_STATUS_SEVERE && proc != null) stopLater(); // (at once)
        });
    }

    // ------------------------------------------------------------------ the engine

    private static Process proc;
    private static volatile String running = "";
    private static volatile long lastUsed;
    /** Requests going through the relay now: the engine isn't idle while one is answered. */
    private static final java.util.concurrent.atomic.AtomicInteger inFlight = new java.util.concurrent.atomic.AtomicInteger();
    private static final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
    /** Stopped this long after its last request: it holds GBs of memory. */
    private static final long IDLE_MS = 10 * 60_000;
    private static final Runnable idleStop = () -> new Thread(() -> {
        synchronized (ENGINE_LOCK) { // (checked again here: a request may have begun meanwhile)
            if (inFlight.get() == 0 && android.os.SystemClock.elapsedRealtime() - lastUsed >= IDLE_MS) stop();
        }
    }, "onphone-idle").start();

    /** The idle stop counts from now. */
    static void touch() {
        lastUsed = android.os.SystemClock.elapsedRealtime();
        main.removeCallbacks(idleStop);
        main.postDelayed(idleStop, IDLE_MS + 1000);
    }

    private static final ThreadLocal<Boolean> BEGUN = new ThreadLocal<>();

    /** A request the engine answers starts (see ensure); endIfBegun() when its answer is through (see Relay). */
    private static void begin() {
        inFlight.incrementAndGet();
        BEGUN.set(true);
        touch();
    }

    static void endIfBegun() {
        if (!Boolean.TRUE.equals(BEGUN.get())) return;
        BEGUN.remove();
        inFlight.decrementAndGet();
        touch();
    }

    static void stop() {
        synchronized (ENGINE_LOCK) {
            if (proc != null) {
                proc.destroy();
                try {
                    if (!proc.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) proc.destroyForcibly();
                } catch (InterruptedException ignored) {
                }
            }
            proc = null;
            running = "";
        }
    }

    /** stop(), off the caller's thread (the main thread mustn't wait while a model is being started). */
    static void stopLater() {
        new Thread(OnPhone::stop, "onphone-stop").start();
    }

    /** stop() once no answer is under way (up to 15 minutes), off the caller's thread. */
    static void stopWhenIdle() {
        new Thread(() -> {
            while (true) {
                waitIdle(0);
                synchronized (ENGINE_LOCK) { // (a request that began meanwhile finishes first)
                    if (inFlight.get() == 0) {
                        stop();
                        return;
                    }
                }
            }
        }, "onphone-stop").start();
    }

    /** Waits until at most `others` requests are in flight (15 minutes at most: a local answer's own limit). */
    private static void waitIdle(int others) {
        long by = android.os.SystemClock.elapsedRealtime() + 15 * 60_000;
        while (inFlight.get() > others && android.os.SystemClock.elapsedRealtime() < by) {
            try {
                Thread.sleep(250);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    /**
     * Engines left from an earlier Buddy process (killed by Android, or updated) hold GBs and the port, and answer
     * health checks: they're found by their command line (this app's own processes) and ended.
     */
    static void killOrphans() {
        synchronized (ENGINE_LOCK) {
            killOrphans0();
        }
    }

    private static void killOrphans0() {
        File[] procs = new File("/proc").listFiles();
        if (procs == null) return;
        int me = android.os.Process.myPid();
        int own = proc != null && proc.isAlive() ? pidOf(proc) : 0;
        if (own == -1) return; // our own engine runs and can't be told apart: none is killed
        for (File p : procs) {
            if (!p.getName().matches("\\d+")) continue;
            int pid = Integer.parseInt(p.getName());
            if (pid == me || pid == own) continue;
            try {
                byte[] b = java.nio.file.Files.readAllBytes(new File(p, "cmdline").toPath());
                String cmd = new String(b, java.nio.charset.StandardCharsets.UTF_8).replace('\0', ' ');
                if (cmd.contains("llama-server") && cmd.contains("--port " + PORT + " ")) android.os.Process.killProcess(pid);
            } catch (Exception ignored) { // another app's process, or gone
            }
        }
    }

    private static int pidOf(Process p) {
        try {
            java.lang.reflect.Field f = p.getClass().getDeclaredField("pid");
            f.setAccessible(true);
            return f.getInt(p);
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * The engine running with this model (started, or restarted with it): null when it's ready, else why not. Called
     * by the relay before a request goes on, so it may take a minute (a few GB are read from storage).
     */
    /**
     * As below; when it's ready the request counts as one the engine is answering (begin), from inside the lock: an
     * idle stop or a model swap can't come in between. The caller then calls endIfBegun() when its answer is through.
     */
    static String ensure(Context c, String modelId) {
        synchronized (ENGINE_LOCK) {
            String err = ensure0(c, modelId);
            if (err == null) begin();
            return err;
        }
    }

    private static String ensure0(Context c, String modelId) {
        touch();
        watchHeat(c);
        if (tooHot(c)) { // a model works the chip hard: Android closes apps once the phone gets too hot
            stop();
            return c.getString(R.string.onphone_too_hot);
        }
        Model m = model(modelId);
        if (m == null) return "No model \"" + modelId + "\" on this phone.";
        if (!downloaded(c, m)) return m.name + " isn't downloaded. Download it again in Buddy's Settings → Models on this phone.";
        // running with it: used as it is (a health check can be slow while it answers another request)
        if (proc != null && proc.isAlive() && m.id.equals(running)) return null;
        // another model is answering another chat: that answer finishes first (requests waiting for this lock
        // haven't begun, so they don't count)
        if (proc != null && proc.isAlive()) waitIdle(0);
        stop();
        killOrphans();
        if (!engineReady(c)) { // missing, or another version than this Buddy's: fetched now
            String err = getEngine(c);
            if (err != null) return "The engine couldn't be downloaded: " + err;
        }
        String pick = snapdragon() ? device(c) : "cpu"; // the plain build has the CPU only
        List<String> order = new ArrayList<>();
        if (!"auto".equals(pick)) order.add(pick);
        else {
            String last = ranOn(c); // where it worked last time first
            if (!last.isEmpty()) order.add(last);
            for (String d : new String[]{"npu", "gpu", "cpu"}) if (!order.contains(d)) order.add(d);
        }
        StringBuilder why = new StringBuilder();
        for (String d : order) {
            if (!snapdragon() && !"cpu".equals(d)) continue; // the plain build has the CPU only
            String err = start(c, m, d);
            if (err == null) {
                sp(c).edit().putString("ran_on", d).apply();
                return null;
            }
            why.append(why.length() > 0 ? "\n" : "").append(d.toUpperCase(java.util.Locale.ROOT)).append(": ").append(err);
        }
        return "The model didn't start on this phone.\n" + why;
    }

    private static final String[] DEV = {"npu", "HTP0", "gpu", "GPUOpenCL", "cpu", "none"};

    private static String start(Context c, Model m, String device) {
        File engine = new File(dir(c), "engine");
        String dev = "none";
        for (int i = 0; i < DEV.length; i += 2) if (DEV[i].equals(device)) dev = DEV[i + 1];
        List<String> cmd = new ArrayList<>();
        cmd.add("/system/bin/linker64"); // Android runs a downloaded program only through its own loader
        cmd.add(new File(engine, "bin/llama-server").getAbsolutePath());
        String[] args = {"-m", new File(dir(c), "models/" + m.file).getAbsolutePath(), "--host", "127.0.0.1",
                "--port", String.valueOf(PORT), "--jinja", "-c", String.valueOf(m.context), "-np", "1",
                "-dev", dev, "-ngl", "none".equals(dev) ? "0" : "99", "--alias", m.id, "--no-webui",
                // small models can think in circles (Qwen3.5 2B spent 7 minutes on a chat's title): capped, and since
                // the engine answers one request at a time, the chat's own request no longer waits behind it
                "--reasoning-budget", "512", "--reasoning-budget-message", " Enough thinking: answer now."};
        for (String a : args) cmd.add(a);
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(true).directory(engine);
        String lib = new File(engine, "lib").getAbsolutePath();
        pb.environment().put("LD_LIBRARY_PATH", lib + ":/vendor/lib64");
        pb.environment().put("ADSP_LIBRARY_PATH", lib); // the NPU's half, which it loads from here
        pb.environment().put("GGML_HEXAGON_HOSTBUF", "0"); // else an app can't map the model for the NPU
        // the NPU's default is "burst" (top clocks, its own slowing down off): for benchmarks. A chat runs for minutes,
        // on a phone that's maybe charging: a mode that may slow down as the chip warms (a phone got too hot with burst)
        pb.environment().put("GGML_HEXAGON_POWER_MODE", "sustained_high_performance");
        pb.environment().put("LLAMA_API_KEY", Secrets.get(c, Providers.secretName(ID)));
        File log = new File(c.getCacheDir(), "onphone.log");
        pb.redirectOutput(log);
        try {
            proc = pb.start();
            proc.getOutputStream().close();
        } catch (Exception e) {
            proc = null;
            return String.valueOf(e.getMessage());
        }
        long by = android.os.SystemClock.elapsedRealtime() + 180_000;
        while (android.os.SystemClock.elapsedRealtime() < by) {
            if (!proc.isAlive()) {
                proc = null;
                return tail(log);
            }
            if (healthy() && proc.isAlive()) { // (a 200 from another engine on the port doesn't count)
                running = m.id;
                return null;
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                break;
            }
        }
        stop();
        return "It didn't get ready in 3 minutes.";
    }

    private static boolean healthy() {
        HttpURLConnection con = null;
        try {
            con = (HttpURLConnection) new URL("http://127.0.0.1:" + PORT + "/health").openConnection();
            con.setConnectTimeout(1000);
            con.setReadTimeout(2000);
            return con.getResponseCode() == 200;
        } catch (Exception e) {
            return false;
        } finally {
            if (con != null) con.disconnect();
        }
    }

    /** The end of the engine's log: why it stopped. */
    private static String tail(File log) {
        try {
            byte[] b = java.nio.file.Files.readAllBytes(log.toPath());
            String s = new String(b, java.nio.charset.StandardCharsets.UTF_8).trim();
            String[] lines = s.split("\n");
            StringBuilder out = new StringBuilder();
            for (int i = Math.max(0, lines.length - 3); i < lines.length; i++) out.append(lines[i].trim()).append(' ');
            return out.toString().trim();
        } catch (Exception e) {
            return "it stopped";
        }
    }
}
