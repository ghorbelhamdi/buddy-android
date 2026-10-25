package app.buddy.assistant;

import android.content.Context;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What agents learned about an app or a site, for the next run: short notes in Buddy's Linux at
 * /root/.buddy/tips/<package or site>.md ("com.reddit.frontpage.md", "reddit.com.md"), which the agents write
 * themselves (the system prompt says how). A chat gets an app's or site's notes once, the first time it reads it.
 */
final class Tips {
    private static final int MAX_CHARS = 1500;
    /** What each chat was shown already, by chat (null chat: a session outside Buddy). */
    private static final Map<String, Set<String>> shown = new ConcurrentHashMap<>();

    private Tips() {
    }

    /** The notes for an app's package or a site's host, once per chat: a block to add to what the agent reads, or "". */
    static String once(Context c, String chat, String name) {
        if (name == null) return "";
        name = name.toLowerCase(java.util.Locale.ROOT);
        if (name.startsWith("www.")) name = name.substring(4);
        if (!name.matches("[a-z0-9][a-z0-9._-]{0,120}")) return "";
        File f = new File(Linux.root(c), "root/.buddy/tips/" + name + ".md");
        if (!f.isFile()) return "";
        Set<String> seen = shown.computeIfAbsent(chat == null ? "" : chat, k -> Collections.newSetFromMap(new ConcurrentHashMap<>()));
        if (!seen.add(name)) return "";
        try {
            String s = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8).trim();
            if (s.isEmpty()) return "";
            if (s.length() > MAX_CHARS) s = s.substring(0, MAX_CHARS) + "…";
            return "\n\nTIPS for " + name + " (notes from earlier runs, in ~/.buddy/tips/" + name + ".md):\n" + s + "\n";
        } catch (Exception e) {
            return "";
        }
    }
}
