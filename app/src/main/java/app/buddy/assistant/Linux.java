package app.buddy.assistant;

import android.content.Context;
import android.system.Os;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPInputStream;

/**
 * Buddy's built-in Linux: an Alpine root filesystem in the app's storage, run through proot.
 * proot and its loader ship inside the APK as native libraries (lib*.so), because Android only lets
 * apps targeting SDK 29+ execute files from the APK's library folder, not from their own storage.
 */
final class Linux {
    static final String ALPINE_URL =
            "https://dl-cdn.alpinelinux.org/alpine/v3.24/releases/aarch64/alpine-minirootfs-3.24.2-aarch64.tar.gz";
    static final String ALPINE_SHA256 = "9bf70a7f18ea44094cbb5f70c58f9af129c8214745743db0e68e5502cc2ce773";

    private Linux() {
    }

    static File root(Context c) {
        return new File(c.getFilesDir(), "linux/alpine");
    }

    static boolean installed(Context c) {
        return new File(root(c), "etc/alpine-release").exists();
    }

    /** Downloads, verifies and unpacks Alpine. Safe to call again; it starts over if a previous try didn't finish. */
    static synchronized void install(Context c) throws IOException {
        if (installed(c)) return;
        File root = root(c);
        deleteTree(root);
        root.mkdirs();
        File tgz = new File(c.getCacheDir(), "alpine.tar.gz");
        download(ALPINE_URL, tgz);
        String sum = sha256(tgz);
        if (!ALPINE_SHA256.equals(sum)) {
            tgz.delete();
            throw new IOException("Alpine download is corrupt (sha256 " + sum + ")");
        }
        File staging = new File(root.getParentFile(), "alpine.partial");
        deleteTree(staging);
        staging.mkdirs();
        try (InputStream in = new GZIPInputStream(new BufferedInputStream(new FileInputStream(tgz)))) {
            untar(in, staging);
        }
        write(new File(staging, "etc/resolv.conf"), "nameserver 8.8.8.8\nnameserver 1.1.1.1\n");
        write(new File(staging, "etc/hosts"), "127.0.0.1 localhost\n::1 localhost\n");
        new File(staging, "root").mkdirs();
        new File(staging, "tmp").mkdirs();
        tgz.delete();
        deleteTree(root);
        if (!staging.renameTo(root)) throw new IOException("Couldn't move Alpine into place");
    }

    /** The proot command line for running {@code cmd} inside Alpine. */
    static List<String> command(Context c, String... cmd) {
        String lib = c.getApplicationInfo().nativeLibraryDir;
        List<String> a = new ArrayList<>(Arrays.asList(lib + "/libproot.so",
                "--kill-on-exit", "--link2symlink", "-0",
                "-r", root(c).getAbsolutePath(),
                "-b", "/dev", "-b", "/proc", "-b", "/sys",
                "-w", "/root",
                "/usr/bin/env", "-i", "HOME=/root", "LANG=C.UTF-8", "TERM=xterm-256color",
                "PATH=/root/.local/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin"));
        a.addAll(Arrays.asList(cmd));
        return a;
    }

    static ProcessBuilder builder(Context c, String... cmd) {
        String lib = c.getApplicationInfo().nativeLibraryDir;
        File tmp = new File(c.getCacheDir(), "proot");
        tmp.mkdirs();
        ProcessBuilder pb = new ProcessBuilder(command(c, cmd));
        pb.environment().put("PROOT_LOADER", lib + "/libproot-loader.so");
        pb.environment().put("PROOT_TMP_DIR", tmp.getAbsolutePath());
        pb.environment().put("LD_LIBRARY_PATH", lib);
        pb.redirectErrorStream(true);
        return pb;
    }

    /** Runs a shell command inside Alpine and returns its output (stdout and stderr), with the exit code. */
    static String exec(Context c, String shell, int timeoutSec) throws Exception {
        Process p = builder(c, "/bin/sh", "-c", shell).start();
        p.getOutputStream().close();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Thread reader = new Thread(() -> {
            try (InputStream in = p.getInputStream()) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    synchronized (out) {
                        if (out.size() < 200_000) out.write(buf, 0, n);
                    }
                }
            } catch (IOException ignored) {
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
        return text + (done ? "\n[exit " + p.exitValue() + "]" : "\n[timed out after " + timeoutSec + "s]");
    }

    // ------------------------------------------------------------------ helpers

    private static void download(String url, File to) throws IOException {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setConnectTimeout(20000);
        con.setReadTimeout(60000);
        if (con.getResponseCode() != 200) throw new IOException("Download failed: HTTP " + con.getResponseCode());
        try (InputStream in = con.getInputStream(); OutputStream out = new FileOutputStream(to)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        } finally {
            con.disconnect();
        }
    }

    private static String sha256(File f) throws IOException {
        try (InputStream in = new FileInputStream(f)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) md.update(buf, 0, n);
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    /** Minimal tar reader: files, directories, symlinks, hard links, GNU long names and pax paths. */
    private static void untar(InputStream in, File dest) throws IOException {
        byte[] h = new byte[512];
        String longName = null, longLink = null;
        while (readFully(in, h)) {
            if (isZero(h)) break;
            String name = longName != null ? longName : str(h, 0, 100);
            String prefix = str(h, 345, 155);
            if (longName == null && !prefix.isEmpty()) name = prefix + "/" + name;
            String link = longLink != null ? longLink : str(h, 157, 100);
            longName = null;
            longLink = null;
            int mode = (int) octal(h, 100, 8);
            long size = octal(h, 124, 12);
            char type = (char) h[156];
            if (type == 'L' || type == 'K' || type == 'x' || type == 'g') {
                byte[] data = readData(in, size);
                if (type == 'L') longName = cstr(data);
                else if (type == 'K') longLink = cstr(data);
                else if (type == 'x') {
                    String[] pax = paxPathAndLink(data);
                    longName = pax[0];
                    longLink = pax[1];
                }
                continue;
            }
            while (name.startsWith("./")) name = name.substring(2);
            if (name.isEmpty() || name.equals(".")) {
                skip(in, size);
                continue;
            }
            if (name.startsWith("/") || ("/" + name + "/").contains("/../")) {
                throw new IOException("Unsafe path in archive: " + name);
            }
            File f = new File(dest, name);
            f.getParentFile().mkdirs();
            switch (type) {
                case '5':
                    f.mkdirs();
                    chmod(f, mode == 0 ? 0755 : mode);
                    skip(in, size);
                    break;
                case '2':
                    f.delete();
                    try {
                        Os.symlink(link, f.getPath());
                    } catch (Exception e) {
                        throw new IOException("symlink " + name + ": " + e.getMessage());
                    }
                    skip(in, size);
                    break;
                case '1': {
                    File src = new File(dest, link.startsWith("./") ? link.substring(2) : link);
                    copy(src, f);
                    chmod(f, mode);
                    skip(in, size);
                    break;
                }
                case '0':
                case '\0':
                case '7': {
                    try (OutputStream out = new FileOutputStream(f)) {
                        long left = size;
                        byte[] buf = new byte[64 * 1024];
                        while (left > 0) {
                            int n = in.read(buf, 0, (int) Math.min(buf.length, left));
                            if (n < 0) throw new IOException("Truncated archive");
                            out.write(buf, 0, n);
                            left -= n;
                        }
                    }
                    skipPadding(in, size);
                    chmod(f, mode);
                    break;
                }
                default:
                    skip(in, size); // devices, fifos: not needed
            }
        }
    }

    private static String[] paxPathAndLink(byte[] data) {
        String path = null, link = null;
        String s = new String(data, StandardCharsets.UTF_8);
        int i = 0;
        while (i < s.length()) {
            int sp = s.indexOf(' ', i);
            if (sp < 0) break;
            int len = Integer.parseInt(s.substring(i, sp));
            String rec = s.substring(sp + 1, Math.min(s.length(), i + len - 1));
            if (rec.startsWith("path=")) path = rec.substring(5);
            else if (rec.startsWith("linkpath=")) link = rec.substring(9);
            i += len;
        }
        return new String[]{path, link};
    }

    private static void chmod(File f, int mode) {
        try {
            Os.chmod(f.getPath(), mode & 07777);
        } catch (Exception ignored) {
        }
    }

    private static void copy(File src, File dst) throws IOException {
        try (InputStream in = new FileInputStream(src); OutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
    }

    private static byte[] readData(InputStream in, long size) throws IOException {
        byte[] d = new byte[(int) size];
        if (!readFully(in, d)) throw new IOException("Truncated archive");
        skipPadding(in, size);
        return d;
    }

    private static void skip(InputStream in, long size) throws IOException {
        long total = size + ((512 - size % 512) % 512);
        while (total > 0) {
            long n = in.skip(total);
            if (n <= 0) {
                if (in.read() < 0) throw new IOException("Truncated archive");
                n = 1;
            }
            total -= n;
        }
    }

    private static void skipPadding(InputStream in, long size) throws IOException {
        long pad = (512 - size % 512) % 512;
        while (pad > 0) {
            long n = in.skip(pad);
            if (n <= 0) {
                if (in.read() < 0) return;
                n = 1;
            }
            pad -= n;
        }
    }

    private static boolean readFully(InputStream in, byte[] b) throws IOException {
        int off = 0;
        while (off < b.length) {
            int n = in.read(b, off, b.length - off);
            if (n < 0) return false;
            off += n;
        }
        return true;
    }

    private static boolean isZero(byte[] b) {
        for (byte x : b) if (x != 0) return false;
        return true;
    }

    private static String str(byte[] b, int off, int len) {
        int end = off;
        while (end < off + len && b[end] != 0) end++;
        return new String(b, off, end - off, StandardCharsets.UTF_8);
    }

    private static String cstr(byte[] b) {
        int end = 0;
        while (end < b.length && b[end] != 0) end++;
        return new String(b, 0, end, StandardCharsets.UTF_8);
    }

    private static long octal(byte[] b, int off, int len) {
        long v = 0;
        for (int i = off; i < off + len; i++) {
            byte x = b[i];
            if (x == 0 || x == ' ') {
                if (v > 0) break;
                continue;
            }
            if (x < '0' || x > '7') break;
            v = v * 8 + (x - '0');
        }
        return v;
    }

    private static void write(File f, String s) throws IOException {
        f.getParentFile().mkdirs();
        if (f.exists() || isLink(f)) f.delete();
        try (OutputStream out = new FileOutputStream(f)) {
            out.write(s.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static boolean isLink(File f) {
        try {
            return android.system.OsConstants.S_ISLNK(Os.lstat(f.getPath()).st_mode);
        } catch (Exception e) {
            return false;
        }
    }

    static void deleteTree(File f) {
        if (!f.exists() && !isLink(f)) return;
        if (f.isDirectory() && !isLink(f)) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteTree(k);
        }
        f.delete();
    }
}
