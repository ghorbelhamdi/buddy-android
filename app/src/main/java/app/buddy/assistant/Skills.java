package app.buddy.assistant;

import android.content.Context;
import android.net.Uri;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Skills shared by the agents (Settings → Skills): folders with a SKILL.md (a name and description up top, then the
 * instructions), the format Claude, ChatGPT and Codex use. They live in ~/.agents/skills in Buddy's Linux, where Codex
 * and OpenCode look; buddy-run links each into ~/.claude/skills for Claude Code. An agent uses one when a task fits, or
 * when the user picks it with / in the message box.
 */
final class Skills {
    static final class Skill {
        final String name, description;
        final File dir;

        Skill(String name, String description, File dir) {
            this.name = name;
            this.description = description;
            this.dir = dir;
        }
    }

    private Skills() {
    }

    static File dir(Context c) {
        return new File(Linux.root(c), "root/.agents/skills");
    }

    static List<Skill> all(Context c) {
        List<Skill> out = new ArrayList<>();
        File[] ds = dir(c).listFiles();
        if (ds == null) return out;
        java.util.Arrays.sort(ds);
        for (File d : ds) {
            File md = new File(d, "SKILL.md");
            if (!d.isDirectory() || !md.isFile()) continue;
            String[] fm = frontMatter(read(md));
            out.add(new Skill(d.getName(), fm[1], d));
        }
        return out;
    }

    static Skill find(Context c, String name) {
        for (Skill s : all(c)) if (s.name.equals(name)) return s;
        return null;
    }

    /** {name, description, body} of a SKILL.md ("" for what's missing). */
    static String[] frontMatter(String md) {
        String name = "", desc = "", body = md;
        if (md.startsWith("---")) {
            int end = md.indexOf("\n---", 3);
            if (end > 0) {
                for (String line : md.substring(3, end).split("\n")) {
                    int colon = line.indexOf(':');
                    if (colon < 0) continue;
                    String k = line.substring(0, colon).trim(), v = unquote(line.substring(colon + 1).trim());
                    if (k.equals("name")) name = v;
                    else if (k.equals("description")) desc = v;
                }
                int nl = md.indexOf('\n', end + 4);
                body = nl < 0 ? "" : md.substring(nl + 1);
            }
        }
        return new String[]{name, desc, body.trim()};
    }

    private static String unquote(String v) {
        if (v.length() >= 2 && (v.startsWith("\"") && v.endsWith("\"") || v.startsWith("'") && v.endsWith("'"))) {
            return v.substring(1, v.length() - 1);
        }
        return v;
    }

    /** "My Skill!" → "my-skill". */
    static String slug(String name) {
        String s = name.toLowerCase(java.util.Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("^-+|-+$", "");
        return s.length() > 64 ? s.substring(0, 64) : s;
    }

    /** A new skill written in Buddy. Returns its name. */
    static String create(Context c, String name, String description, String instructions) throws Exception {
        String n = slug(name);
        if (n.isEmpty()) throw new IllegalArgumentException(c.getString(R.string.skills_need_name));
        File d = new File(dir(c), n);
        if (d.exists()) throw new IllegalArgumentException(c.getString(R.string.skills_exists, n));
        d.mkdirs();
        String desc = description.replace('\n', ' ').trim();
        Linux.write(new File(d, "SKILL.md"), "---\nname: " + n + "\ndescription: " + (desc.contains(":") ? "\"" + desc.replace("\"", "'") + "\"" : desc)
                + "\n---\n\n" + instructions.trim() + "\n");
        return n;
    }

    /**
     * A skill from a file: a .zip or .skill (a folder with a SKILL.md, as Claude exports them) or a SKILL.md itself.
     * Returns its name.
     */
    static String importUri(Context c, Uri uri) throws Exception {
        byte[] data;
        try (InputStream in = c.getContentResolver().openInputStream(uri)) {
            if (in == null) throw new IllegalArgumentException(c.getString(R.string.skills_bad_file));
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                o.write(buf, 0, n);
                if (o.size() > 50 << 20) throw new IllegalArgumentException(c.getString(R.string.skills_bad_file));
            }
            data = o.toByteArray();
        }
        boolean zip = data.length > 4 && data[0] == 'P' && data[1] == 'K';
        if (!zip) { // a SKILL.md
            String md = new String(data, StandardCharsets.UTF_8);
            String[] fm = frontMatter(md);
            String n = slug(fm[0]);
            if (n.isEmpty() || fm[2].isEmpty()) throw new IllegalArgumentException(c.getString(R.string.skills_bad_file));
            File d = new File(dir(c), n);
            d.mkdirs();
            Linux.write(new File(d, "SKILL.md"), md);
            return n;
        }
        // the SKILL.md's folder inside the zip (the top one), and its name
        String prefix = null, md = null;
        try (ZipInputStream z = new ZipInputStream(new java.io.ByteArrayInputStream(data))) {
            for (ZipEntry e; (e = z.getNextEntry()) != null; ) {
                String p = e.getName();
                if (e.isDirectory() || !(p.equals("SKILL.md") || p.endsWith("/SKILL.md"))) continue;
                String pre = p.substring(0, p.length() - "SKILL.md".length());
                if (prefix == null || pre.length() < prefix.length()) {
                    prefix = pre;
                    md = new String(readAll(z), StandardCharsets.UTF_8);
                }
            }
        }
        if (md == null) throw new IllegalArgumentException(c.getString(R.string.skills_bad_file));
        String[] fm = frontMatter(md);
        String n = slug(!fm[0].isEmpty() ? fm[0] : prefix.replaceAll("/+$", "").replaceAll(".*/", ""));
        if (n.isEmpty()) throw new IllegalArgumentException(c.getString(R.string.skills_bad_file));
        File d = new File(dir(c), n);
        d.mkdirs();
        String root = d.getCanonicalPath() + File.separator;
        try (ZipInputStream z = new ZipInputStream(new java.io.ByteArrayInputStream(data))) {
            for (ZipEntry e; (e = z.getNextEntry()) != null; ) {
                String p = e.getName();
                if (e.isDirectory() || !p.startsWith(prefix)) continue;
                File out = new File(d, p.substring(prefix.length()));
                if (!out.getCanonicalPath().startsWith(root)) continue; // no paths out of the folder
                out.getParentFile().mkdirs();
                try (FileOutputStream f = new FileOutputStream(out)) {
                    f.write(readAll(z));
                }
            }
        }
        return n;
    }

    private static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        byte[] buf = new byte[64 * 1024];
        int n;
        while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
        return o.toByteArray();
    }

    static void delete(Skill s) {
        deleteTree(s.dir);
    }

    private static void deleteTree(File f) {
        File[] kids = f.isDirectory() && !isLink(f) ? f.listFiles() : null;
        if (kids != null) for (File k : kids) deleteTree(k);
        f.delete();
    }

    private static boolean isLink(File f) {
        return java.nio.file.Files.isSymbolicLink(f.toPath());
    }

    static String read(File f) {
        try {
            return new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * A message starting with /name for a skill: what the agent gets. Claude Code has /name itself (null: as typed);
     * the others get the skill's instructions with the message, as they can't always open the file (Codex has no
     * shell with Linux tools off).
     */
    static String prompt(Context c, String backend, String text) {
        if (!text.startsWith("/") || "claude".equals(backend)) return null;
        int sp = text.indexOf(' ');
        String name = sp < 0 ? text.substring(1) : text.substring(1, sp);
        Skill s = find(c, name);
        if (s == null) return null;
        String rest = sp < 0 ? "" : text.substring(sp + 1).trim();
        String body = frontMatter(read(new File(s.dir, "SKILL.md")))[2];
        return "Use the skill \"" + s.name + "\" for this. Its instructions (from " + "/root/.agents/skills/" + s.name
                + "/SKILL.md; other files it mentions are next to it):\n\n" + body + "\n\n---\n\n"
                + (rest.isEmpty() ? "Go ahead." : "User message: " + rest);
    }
}
