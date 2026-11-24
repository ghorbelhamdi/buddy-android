package app.buddy.assistant;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Computers agents can work on: the user's own computers, reached over SSH from Buddy's Linux (Settings → Other
 * devices). Buddy makes one key for them; the user adds its public half to each computer's authorized_keys.
 *
 * The private key is kept out of Buddy's Linux, where an agent with Linux tools could copy it: it stays in Buddy's own
 * storage and, for each command, goes through a pipe into an ssh-agent of that command's own, which confirms one use
 * only (ssh-add -c with a confirm program that says yes once, then ends the agent). Another process in Buddy's Linux
 * could still race for that one use; it's kept short, not impossible. Every command shows on an approval card first.
 */
final class Computers {
    private Computers() {
    }

    static final class Computer {
        final String id, name, host, user, mode;
        final int port;

        Computer(String id, String name, String host, String user, int port, String mode) {
            this.id = id;
            this.name = name;
            this.host = host;
            this.user = user;
            this.port = port;
            this.mode = mode;
        }

        String where() {
            return user + "@" + host + (port == 22 ? "" : ":" + port);
        }
    }

    private static SharedPreferences sp(Context c) {
        return c.getApplicationContext().getSharedPreferences("computers", Context.MODE_PRIVATE);
    }

    static synchronized List<Computer> all(Context c) {
        List<Computer> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(sp(c).getString("all", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                out.add(new Computer(o.getString("id"), o.getString("name"), o.getString("host"), o.getString("user"),
                        o.optInt("port", 22), o.optString("mode", "each")));
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    static synchronized void save(Context c, List<Computer> list) {
        JSONArray a = new JSONArray();
        try {
            for (Computer m : list) a.put(new JSONObject().put("id", m.id).put("name", m.name).put("host", m.host)
                    .put("user", m.user).put("port", m.port).put("mode", m.mode));
        } catch (Exception ignored) {
        }
        sp(c).edit().putString("all", a.toString()).apply();
    }

    static void add(Context c, Computer m) {
        List<Computer> list = all(c);
        list.removeIf(o -> o.name.equalsIgnoreCase(m.name));
        list.add(m);
        save(c, list);
    }

    static void remove(Context c, String id) {
        List<Computer> list = all(c);
        list.removeIf(o -> o.id.equals(id));
        save(c, list);
    }

    static Computer find(Context c, String name) {
        String want = name == null ? "" : name.trim();
        List<Computer> list = all(c);
        for (Computer m : list) if (m.name.equalsIgnoreCase(want)) return m;
        return list.size() == 1 && want.isEmpty() ? list.get(0) : null;
    }

    static String names(Context c) {
        List<String> n = new ArrayList<>();
        for (Computer m : all(c)) n.add("\"" + m.name + "\"");
        return android.text.TextUtils.join(", ", n);
    }

    // ------------------------------------------------------------ the key

    private static File keyFile(Context c) {
        return new File(c.getFilesDir(), "computers/key"); // Buddy's own storage, not Buddy's Linux
    }

    /** Buddy's public key for the computers, made the first time it's asked for (in Buddy's Linux). */
    static synchronized String publicKey(Context c) throws Exception {
        String pub = sp(c).getString("public", null);
        if (pub != null && keyFile(c).isFile()) return pub;
        String out = Linux.exec(c, "command -v ssh-keygen >/dev/null || apk add -q openssh-keygen >/dev/null 2>&1; "
                + "command -v ssh >/dev/null || apk add -q openssh-client-default >/dev/null 2>&1; d=$(mktemp -d) && ssh-keygen -q -t ed25519 -N '' -C buddy-phone -f $d/k >/dev/null "
                + "&& cat $d/k && echo ===PUB=== && cat $d/k.pub; rm -rf $d", 60);
        int at = out.indexOf("===PUB===");
        if (at < 0 || !out.contains("PRIVATE KEY")) throw new Exception(out.trim());
        String priv = out.substring(0, at).trim() + "\n";
        pub = out.substring(at + 9).replaceAll("\\[exit \\d+\\]", "").trim();
        File f = keyFile(c);
        f.getParentFile().mkdirs();
        try (OutputStream o = new java.io.FileOutputStream(f)) {
            o.write(priv.getBytes(StandardCharsets.US_ASCII));
        }
        f.setReadable(false, false);
        f.setReadable(true, true);
        sp(c).edit().putString("public", pub).apply();
        return pub;
    }

    // ------------------------------------------------------------ commands

    private static String quote(String s) {
        return "'" + s.replace("'", "'\\''") + "'";
    }

    /** Runs a command on a computer; its output (stdout and stderr), with the exit code. */
    static String run(Context c, Computer m, String command, int timeoutSec) throws Exception {
        File key = keyFile(c);
        if (!key.isFile()) return "FAILED: Buddy has no key for computers yet: add a computer in Settings → Other devices first.";
        String script = "command -v ssh >/dev/null || apk add -q openssh-client-default >/dev/null 2>&1 || "
                + "{ echo 'Could not install the SSH client (openssh-client-default) in Buddy'\\''s Linux.'; exit 255; }\n"
                + "d=$(mktemp -d) && chmod 700 $d || exit 255\n"
                // the confirm program says yes once; a watcher ends the agent 2 s after that use (or after 25 s)
                + "printf '#!/bin/sh\\nmkdir %s/used 2>/dev/null\\n' \"$d\" > $d/once && chmod 700 $d/once\n"
                + "eval $(SSH_ASKPASS=$d/once SSH_ASKPASS_REQUIRE=force DISPLAY=:0 ssh-agent -a $d/sock -s) >/dev/null\n"
                + "ssh-add -q -c - 2>/dev/null </dev/stdin\n"
                + "(i=0; while [ ! -d $d/used ] && [ $i -lt 125 ]; do sleep 0.2; i=$((i+1)); done; sleep 2; "
                + "kill $SSH_AGENT_PID; rm -rf $d) >/dev/null 2>&1 &\n"
                + "mkdir -p /root/.buddy\n"
                // (WarnWeakCrypto=no: Windows' OpenSSH has no post-quantum key exchange yet, and its warning came into every
                // answer as if it were the command's; SSH's real errors still show)
                + "ssh -n -o BatchMode=yes -o WarnWeakCrypto=no -o StrictHostKeyChecking=accept-new -o UserKnownHostsFile=/root/.buddy/computers_known_hosts "
                + "-o ConnectTimeout=15 -o ServerAliveInterval=30 -p " + m.port + " " + quote(m.user + "@" + m.host) + " -- "
                + quote(command) + "\n"
                // (the watcher above is killed with this script when a quick command returns: clean up here too)
                + "s=$?; kill $SSH_AGENT_PID >/dev/null 2>&1; rm -rf $d; exit $s\n";
        Process p = Linux.builder(c, "/bin/sh", "-c", script).start();
        try (OutputStream in = p.getOutputStream()) { // the key, through the pipe only, then the end of it
            in.write(java.nio.file.Files.readAllBytes(key.toPath()));
        }
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Thread reader = new Thread(() -> {
            try (InputStream is = p.getInputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) > 0) synchronized (out) {
                    if (out.size() < 400_000) out.write(buf, 0, n);
                }
            } catch (java.io.IOException ignored) {
            }
        });
        reader.start();
        boolean done = p.waitFor(timeoutSec, TimeUnit.SECONDS);
        if (!done) p.destroyForcibly();
        reader.join(2000);
        String text;
        synchronized (out) {
            text = new String(out.toByteArray(), StandardCharsets.UTF_8);
        }
        if (text.length() > 60_000) text = "[… the first " + (text.length() - 60_000) + " characters left out]\n" + text.substring(text.length() - 60_000);
        return text + (done ? "\n[exit " + p.exitValue() + "]" : "\n[timed out after " + timeoutSec + " s; the command may still run there]");
    }

    /** A quick test that the computer lets Buddy in: null when it does, else what SSH said. */
    static String check(Context c, Computer m) {
        try {
            String r = run(c, m, "echo buddy-ok", 40);
            return r.contains("buddy-ok") ? null : r.replace("\n[exit 255]", "").trim();
        } catch (Exception e) {
            return String.valueOf(e.getMessage());
        }
    }

    static String newId() {
        return Long.toString(System.currentTimeMillis(), 36);
    }

    static boolean validHost(String h) {
        return h != null && h.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,252}"); // (never "-…": ssh would read an option)
    }

    static boolean validUser(String u) {
        return u != null && u.matches("[^-@\\s'\"][^@\\s'\"]{0,63}");
    }
}
