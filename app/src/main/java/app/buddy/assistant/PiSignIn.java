package app.buddy.assistant;

import android.content.Context;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pi's sign-in to a plan, without its own screen: Buddy runs `pi auth login <provider> --no-browser` in its Linux (on
 * a pseudo-terminal: Pi asks its questions only on one) and reads what Pi prints: the page to open and the code for it
 * (ChatGPT, GitHub Copilot), or the page and then a question for the code the page shows (Claude). The screen shows
 * them; for Claude the code the user pastes goes back to Pi. Pi saves the sign-in itself (~/.pi/agent/auth.json).
 */
final class PiSignIn {
    interface Listener {
        /** The page, and the code to enter there (null when Pi asks for a code back instead: see answer). */
        void link(String url, String code);

        /** Pi waits for the code the page showed (Claude). */
        void wantsCode();

        /** Ended: null when signed in, else Pi's own words. */
        void done(String error);
    }

    private static final Pattern URL = Pattern.compile("https://\\S+");
    private static final Pattern CODE = Pattern.compile("(?i)(?:enter (?:the )?code|code)[:\\s]+([A-Z0-9]{4,}(?:-[A-Z0-9]{4,})+)");
    private static final Pattern ANSI = Pattern.compile("\\x1b\\[[0-9;?]*[a-zA-Z]|\\x1b\\][^\\x07]*\\x07|\\r");

    private final Process p;
    private final OutputStream in;
    private volatile boolean cancelled;

    private PiSignIn(Process p) {
        this.p = p;
        this.in = p.getOutputStream();
    }

    /** provider: Pi's name for it ("anthropic", "openai-codex", "github-copilot"). */
    static PiSignIn start(Context c, String provider, Listener l) throws Exception {
        if (!provider.matches("[a-z0-9-]+")) throw new IllegalArgumentException(provider);
        Process p = Linux.builder(c, "/bin/sh", "-c", "command -v script >/dev/null || apk add -q util-linux-misc >/dev/null 2>&1; "
                + "export TERM=dumb COLUMNS=4000; exec script -qefc 'pi auth login " + provider + " --no-browser' /dev/null").start();
        PiSignIn s = new PiSignIn(p);
        new Thread(() -> s.read(provider, l), "pi-signin").start();
        return s;
    }

    private void read(String provider, Listener l) {
        StringBuilder all = new StringBuilder(), line = new StringBuilder();
        String url = null;
        boolean linked = false, asked = false, enterprise = false;
        try (InputStream out = p.getInputStream()) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = out.read(buf)) > 0) {
                String chunk = ANSI.matcher(new String(buf, 0, n, StandardCharsets.UTF_8)).replaceAll("");
                all.append(chunk);
                if (all.length() > 20000) all.delete(0, all.length() - 20000);
                String text = all.toString();
                // GitHub's asks first whether it's GitHub Enterprise: blank is github.com
                if (!enterprise && text.contains("GitHub Enterprise")) {
                    enterprise = true;
                    send("");
                }
                if (url == null) {
                    Matcher m = URL.matcher(text);
                    while (m.find()) {
                        String u = m.group().replaceAll("[).,]+$", "");
                        if (u.contains("/device") || u.contains("authorize") || u.contains("login")) {
                            url = u;
                            break;
                        }
                    }
                }
                if (url != null && !linked) {
                    Matcher cm = CODE.matcher(text);
                    boolean pastes = text.contains("Paste the code") || text.contains("paste it here");
                    if (cm.find()) {
                        linked = true;
                        l.link(url, cm.group(1));
                    } else if (pastes) {
                        linked = true;
                        l.link(url, null);
                    }
                }
                if (linked && !asked && text.contains("Paste the code")) {
                    asked = true;
                    l.wantsCode();
                }
            }
        } catch (Exception ignored) {
        }
        try {
            p.waitFor();
        } catch (InterruptedException ignored) {
        }
        if (cancelled) return;
        String text = all.toString().trim();
        boolean ok = p.exitValue() == 0 && !text.toLowerCase(java.util.Locale.ROOT).contains("error");
        l.done(ok ? null : lastLines(text));
    }

    private static String lastLines(String text) {
        String[] ls = text.split("\n");
        StringBuilder b = new StringBuilder();
        for (int i = Math.max(0, ls.length - 4); i < ls.length; i++) if (!ls[i].trim().isEmpty()) b.append(ls[i].trim()).append('\n');
        return b.length() == 0 ? "Pi stopped without signing in." : b.toString().trim();
    }

    /** A line for Pi's question (the code the page showed, for Claude). */
    synchronized void send(String text) {
        try {
            in.write((text.replace("\n", "").trim() + "\r").getBytes(StandardCharsets.UTF_8));
            in.flush();
        } catch (Exception ignored) {
        }
    }

    void cancel() {
        cancelled = true;
        p.destroy();
    }
}
