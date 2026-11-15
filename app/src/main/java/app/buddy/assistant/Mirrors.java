package app.buddy.assistant;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;

import java.io.File;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * Where Buddy's Linux downloads from (Settings → Agents and models → Download server). By default Alpine's own server
 * and npm's, which answer from near the user almost everywhere. Not everywhere: in mainland China they are slow or
 * blocked, Iran's link abroad has been mostly cut since early 2026, and Russia has had outages of the network Alpine's
 * server is on; the public DNS servers Buddy's Linux asks fail in the same places. So a server in the country can be
 * chosen, or any other by its address.
 *
 * A mirror serves the same Alpine files: the system image is still checked against its SHA-256, and apk checks every
 * package's signature, so the mirror need not be trusted for those. What npm installs (Codex, OpenCode) comes from
 * the mirror's npm registry where one is named, and there the registry itself vouches for the packages. Buddy's Linux
 * then asks the phone's own DNS servers first.
 *
 * The ones offered were tested from outside (2026-11-15): each serves the image with the right SHA-256, the v3.24
 * and edge indexes; the npm mirrors gave the same versions and integrity hashes as npm's own for Codex and OpenCode.
 */
final class Mirrors {
    static final String DEFAULT = "https://dl-cdn.alpinelinux.org/alpine", CUSTOM = "custom";
    private static final String NPM_CN = "https://registry.npmmirror.com/", DNS_CN = "223.5.5.5 119.29.29.29";

    /** One server: region is a string resource (0: none), npm a registry address ("": npm's own), dns extra servers. */
    static final class M {
        final String id, name, alpine, npm, dns;
        final int region;

        M(String id, String name, int region, String alpine, String npm, String dns) {
            this.id = id;
            this.name = name;
            this.region = region;
            this.alpine = alpine;
            this.npm = npm;
            this.dns = dns;
        }
    }

    /** The default first, then the mirrors offered (the user's own, "custom", is made from what they entered). */
    static final M[] ALL = {
            new M("", "", 0, DEFAULT, "", ""),
            new M("ustc", "USTC", R.string.setup_mirror_china, "https://mirrors.ustc.edu.cn/alpine", NPM_CN, DNS_CN),
            new M("tuna", "TUNA", R.string.setup_mirror_china, "https://mirrors.tuna.tsinghua.edu.cn/alpine", NPM_CN, DNS_CN),
            new M("aliyun", "Aliyun", R.string.setup_mirror_china, "https://mirrors.aliyun.com/alpine", NPM_CN, DNS_CN),
            new M("yandex", "Yandex", R.string.setup_mirror_russia, "https://mirror.yandex.ru/mirrors/alpine", "", "77.88.8.8"),
            new M("mobinhost", "Mobinhost", R.string.setup_mirror_iran, "https://mirror.mobinhost.com/alpine",
                    "https://mirror.abrha.net/repository/npm/", ""),
    };

    private Mirrors() {
    }

    /** The server in use. */
    static M current(Context c) {
        String id = Prefs.mirror(c);
        if (CUSTOM.equals(id)) {
            String a = Prefs.mirrorCustom(c, false);
            if (!a.isEmpty()) return new M(CUSTOM, "", 0, a, Prefs.mirrorCustom(c, true), "");
        }
        for (M m : ALL) if (m.id.equals(id)) return m;
        return ALL[0];
    }

    static boolean on(Context c) {
        return !current(c).id.isEmpty();
    }

    /** The Alpine address to download from, without a slash at its end. */
    static String alpine(Context c) {
        return current(c).alpine;
    }

    /** An address the user typed, tidied (no slash at its end), or null when it isn't one. */
    static String address(String typed) {
        String t = typed == null ? "" : typed.trim();
        while (t.endsWith("/")) t = t.substring(0, t.length() - 1);
        return t.matches("https?://[A-Za-z0-9.\\-]+(:\\d+)?(/[A-Za-z0-9._~/\\-]*)?") ? t : null;
    }

    /**
     * Makes Buddy's Linux (if installed) use the chosen server: apk's list of repositories, npm's registry and the DNS
     * servers. Cheap when nothing changes; called before installs and each time the helper is synced, since the
     * phone's DNS servers change with the network.
     */
    static synchronized void apply(Context c) {
        File root = Linux.root(c);
        if (!Linux.installed(c)) return;
        try {
            M m = current(c);
            File repos = new File(root, "etc/apk/repositories");
            if (repos.exists()) {
                String was = read(repos);
                // each line is "[@tag ]<address>/<v3.24 or edge>/<main, community or testing>": the address changes
                String now = was.replaceAll("(?m)^(@\\S+\\s+)?\\S+?/(v\\d+\\.\\d+|edge)/(main|community|testing)[ \\t]*$",
                        "$1" + java.util.regex.Matcher.quoteReplacement(m.alpine) + "/$2/$3");
                if (!now.equals(was)) write(repos, now);
            }
            // npm's registry: only a line Buddy wrote itself is taken out again
            File npmrc = new File(root, "root/.npmrc");
            String rc = npmrc.exists() ? read(npmrc) : "", ours = Prefs.mirrorNpm(c);
            String line = m.npm.isEmpty() ? "" : "registry=" + m.npm;
            if (!ours.equals(line)) {
                if (!ours.isEmpty()) rc = ("\n" + rc).replace("\n" + ours + "\n", "\n").substring(1);
                if (!line.isEmpty()) rc += (rc.isEmpty() || rc.endsWith("\n") ? "" : "\n") + line + "\n";
                write(npmrc, rc);
                Prefs.setMirrorNpm(c, line);
            }
            // the DNS servers: only a list Buddy wrote itself is replaced (the user's own, set in a terminal, stays)
            File resolv = new File(root, "etc/resolv.conf");
            String has = resolv.exists() ? read(resolv) : "", mine = Prefs.mirrorDns(c);
            String dns = Linux.DNS;
            if (!m.id.isEmpty()) {
                StringBuilder b = new StringBuilder(phoneDns(c));
                for (String s : m.dns.split(" ")) if (!s.isEmpty()) b.append("nameserver ").append(s).append('\n');
                dns = b + Linux.DNS;
            }
            if ((has.isEmpty() || has.equals(Linux.DNS) || has.equals(mine)) && !dns.equals(has)) {
                write(resolv, dns);
                Prefs.setMirrorDns(c, m.id.isEmpty() ? "" : dns);
            }
        } catch (Exception e) {
            android.util.Log.w("Buddy", "download server not applied", e);
        }
    }

    /** The network's own DNS servers as resolv.conf lines (IPv4: musl's resolver in proot has no use for scoped IPv6). */
    private static String phoneDns(Context c) {
        StringBuilder b = new StringBuilder();
        try {
            ConnectivityManager cm = c.getSystemService(ConnectivityManager.class);
            LinkProperties lp = cm.getLinkProperties(cm.getActiveNetwork());
            if (lp != null) {
                for (InetAddress a : lp.getDnsServers()) {
                    if (a instanceof Inet4Address && !a.isLoopbackAddress()) b.append("nameserver ").append(a.getHostAddress()).append('\n');
                }
            }
        } catch (Exception ignored) { // no network right now: the public ones
        }
        return b.toString();
    }

    private static String read(File f) throws java.io.IOException {
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    private static void write(File f, String s) throws java.io.IOException {
        Files.write(f.toPath(), s.getBytes(StandardCharsets.UTF_8));
    }
}
