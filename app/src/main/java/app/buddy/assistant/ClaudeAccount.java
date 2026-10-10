package app.buddy.assistant;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Claude Code inside Buddy's built-in Linux: install it, sign in, check the account.
 * Sign-in runs `claude auth login`; the user approves in the browser, and Buddy picks the one-time
 * code off the confirmation page (accessibility), the clipboard, or a paste box, and feeds it back.
 */
final class ClaudeAccount {
    enum Step { CHECKING, NEED_INSTALL, INSTALLING, SIGNED_OUT, WAITING_BROWSER, FINISHING, SIGNED_IN, ERROR }

    interface Listener {
        void onAccountChanged();
    }

    static volatile Step step = Step.CHECKING;
    static volatile String detail = "", email = "", plan = "";
    /** The sign-in page to open, and the OAuth state that the confirmation code ends with. */
    static volatile String url, state;

    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final List<Listener> listeners = new ArrayList<>();
    private static Process login;
    private static OutputStream loginIn;

    private ClaudeAccount() {
    }

    static void addListener(Listener l) {
        listeners.add(l);
    }

    static void removeListener(Listener l) {
        listeners.remove(l);
    }

    private static void set(Step s, String d) {
        step = s;
        detail = d == null ? "" : d;
        main.post(() -> {
            for (Listener l : new ArrayList<>(listeners)) l.onAccountChanged();
        });
    }

    private static boolean busy() {
        return step == Step.INSTALLING || step == Step.WAITING_BROWSER || step == Step.FINISHING;
    }

    /** Works out where things stand: Linux and Claude Code installed? Signed in? */
    static void refresh(Context c) {
        if (busy()) return;
        final Context app = c.getApplicationContext();
        new Thread(() -> {
            try {
                if (!Linux.installed(app)) {
                    set(Step.NEED_INSTALL, "");
                    return;
                }
                String out = Linux.exec(app, "[ -x /root/.local/bin/claude ] || { echo NOCLAUDE; exit 0; }; "
                        + "claude auth status 2>/dev/null", 60);
                if (out.contains("NOCLAUDE")) {
                    set(Step.NEED_INSTALL, "");
                    return;
                }
                JSONObject j = new JSONObject(out.substring(out.indexOf('{'), out.lastIndexOf('}') + 1));
                if (j.optBoolean("loggedIn")) {
                    email = j.optString("email", j.optString("emailAddress", ""));
                    plan = j.optString("subscriptionType", "");
                    set(Step.SIGNED_IN, "");
                } else {
                    set(Step.SIGNED_OUT, "");
                }
            } catch (Exception e) {
                set(Step.ERROR, "Couldn't check Claude Code: " + e.getMessage());
            }
        }).start();
    }

    /** Installs Buddy's Linux (4 MB) and Claude Code with Anthropic's official installer (~230 MB). */
    static void install(Context c) {
        if (busy()) return;
        final Context app = c.getApplicationContext();
        set(Step.INSTALLING, "Setting up Buddy's Linux…");
        new Thread(() -> {
            try {
                Linux.install(app);
                set(Step.INSTALLING, "Installing tools…");
                String o = Linux.exec(app, "apk update -q && apk add -q bash curl jq libgcc libstdc++ ripgrep git ca-certificates"
                        + " && echo TOOLS_OK", 600);
                if (!o.contains("TOOLS_OK")) throw new IOException(tail(o));
                set(Step.INSTALLING, "Downloading Claude Code (about 230 MB)…");
                o = Linux.exec(app, "curl -fsSL https://claude.ai/install.sh -o /tmp/install.sh && bash /tmp/install.sh"
                        + " && /root/.local/bin/claude --version && echo CLAUDE_OK", 1200);
                if (!o.contains("CLAUDE_OK")) throw new IOException(tail(o));
                set(Step.SIGNED_OUT, "");
                refresh(app);
            } catch (Exception e) {
                set(Step.ERROR, "Install failed: " + e.getMessage());
            }
        }).start();
    }

    /** Starts `claude auth login`; the sign-in URL appears in {@link #url} when it's ready. */
    static void startLogin(Context c) {
        if (busy()) return;
        final Context app = c.getApplicationContext();
        url = null;
        state = null;
        set(Step.WAITING_BROWSER, "Starting sign-in…");
        new Thread(() -> {
            StringBuilder out = new StringBuilder();
            try {
                Process p = Linux.builder(app, "/root/.local/bin/claude", "auth", "login", "--claudeai").start();
                synchronized (ClaudeAccount.class) {
                    login = p;
                    loginIn = p.getOutputStream();
                }
                Pattern u = Pattern.compile("https://\\S*/oauth/authorize\\?\\S+");
                try (InputStream in = p.getInputStream()) {
                    byte[] buf = new byte[4096];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.append(new String(buf, 0, n, StandardCharsets.UTF_8));
                        if (url == null) {
                            Matcher m = u.matcher(stripAnsi(out.toString()));
                            if (m.find()) {
                                url = m.group();
                                state = Uri.parse(url).getQueryParameter("state");
                                set(Step.WAITING_BROWSER, "");
                            }
                        }
                    }
                }
                p.waitFor();
            } catch (Exception e) {
                out.append("\n").append(e.getMessage());
            } finally {
                synchronized (ClaudeAccount.class) {
                    login = null;
                    loginIn = null;
                }
            }
            url = null;
            step = Step.CHECKING;
            refresh(app);
            // if it didn't work, say why once the status check is back
            final String why = tail(stripAnsi(out.toString()));
            main.postDelayed(() -> {
                if (step == Step.SIGNED_OUT) set(Step.ERROR, "Sign-in didn't finish. " + why);
            }, 4000);
        }).start();
    }

    /** Hands the one-time code to Claude Code. Returns false if no sign-in is waiting. */
    static synchronized boolean submit(String code) {
        if (loginIn == null || code == null || code.trim().isEmpty()) return false;
        try {
            loginIn.write((code.trim() + "\n").getBytes(StandardCharsets.UTF_8));
            loginIn.flush();
            set(Step.FINISHING, "Finishing sign-in…");
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    static synchronized void cancelLogin() {
        if (login != null) login.destroy();
    }

    /** The confirmation code if this text contains it ("&lt;code&gt;#&lt;state&gt;"), else null. */
    static String findCode(CharSequence text) {
        String s = state;
        if (text == null || s == null || s.isEmpty()) return null;
        Matcher m = Pattern.compile("[A-Za-z0-9_\\-]{16,}#" + Pattern.quote(s)).matcher(text);
        return m.find() ? m.group() : null;
    }

    static boolean waitingForCode() {
        return step == Step.WAITING_BROWSER && url != null;
    }

    private static String stripAnsi(String s) {
        return s.replaceAll("\u001B\\[[0-9;?]*[ -/]*[@-~]", "").replaceAll("\u001B\\][^\u0007]*\u0007", "");
    }

    private static String tail(String s) {
        s = s.trim();
        return s.length() > 300 ? "…" + s.substring(s.length() - 300) : s;
    }
}
