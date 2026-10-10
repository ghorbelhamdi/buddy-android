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
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * One agent (Claude Code or Codex) inside Buddy's built-in Linux: install it, sign in, check the account.
 * Claude Code's sign-in shows a one-time code in the browser; Buddy reads it off the screen (accessibility),
 * the clipboard, or a paste box. Codex's sign-in returns to a local address inside Linux by itself.
 */
final class Account {
    enum Step { CHECKING, NEED_INSTALL, INSTALLING, SIGNED_OUT, WAITING_BROWSER, FINISHING, SIGNED_IN, ERROR }

    interface Listener {
        void onAccountChanged();
    }

    static final Account CLAUDE = new Account("claude");
    static final Account CODEX = new Account("codex");

    static Account of(String agent) {
        return "codex".equals(agent) ? CODEX : CLAUDE;
    }

    final String agent;
    volatile Step step = Step.CHECKING;
    volatile String detail = "", email = "", plan = "";
    /** The sign-in page to open, and (Claude) the OAuth state that the confirmation code ends with. */
    volatile String url, state;

    private static final Handler main = new Handler(Looper.getMainLooper());
    private static volatile Context app;
    private static final List<Listener> listeners = new ArrayList<>();
    private Process login;
    private OutputStream loginIn;

    private Account(String agent) {
        this.agent = agent;
    }

    boolean claude() {
        return "claude".equals(agent);
    }

    String name() {
        return claude() ? "Claude Code" : "Codex";
    }

    static void addListener(Listener l) {
        listeners.add(l);
    }

    static void removeListener(Listener l) {
        listeners.remove(l);
    }

    private void set(Step s, String d) {
        step = s;
        detail = d == null ? "" : d;
        Context a = app;
        // remembered, so screens can show progress right away before the next check finishes
        if (a != null && (s == Step.NEED_INSTALL || s == Step.SIGNED_OUT || s == Step.SIGNED_IN)) {
            Prefs.setAgentState(a, agent, s.name());
        }
        main.post(() -> {
            for (Listener l : new ArrayList<>(listeners)) l.onAccountChanged();
        });
    }

    boolean busy() {
        return step == Step.INSTALLING || step == Step.WAITING_BROWSER || step == Step.FINISHING;
    }

    boolean ready() {
        return step == Step.SIGNED_IN;
    }

    /** Works out where things stand: installed? signed in? */
    void refresh(Context c) {
        app = c.getApplicationContext();
        if (busy()) return;
        final Context app = c.getApplicationContext();
        new Thread(() -> {
            try {
                if (!Linux.installed(app)) {
                    set(Step.NEED_INSTALL, "");
                    return;
                }
                if (claude()) {
                    String out = Linux.exec(app, "[ -x /root/.local/bin/claude ] || { echo NOTINSTALLED; exit 0; }; "
                            + "claude auth status 2>/dev/null", 60);
                    if (out.contains("NOTINSTALLED")) {
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
                } else {
                    String out = Linux.exec(app, "command -v codex >/dev/null || { echo NOTINSTALLED; exit 0; }; "
                            + "codex login status 2>&1", 60);
                    String low = out.toLowerCase(Locale.ROOT);
                    if (out.contains("NOTINSTALLED")) {
                        set(Step.NEED_INSTALL, "");
                    } else if (low.contains("logged in") && !low.contains("not logged in")) {
                        email = "";
                        plan = out.contains("ChatGPT") ? "ChatGPT" : "";
                        set(Step.SIGNED_IN, "");
                    } else {
                        set(Step.SIGNED_OUT, "");
                    }
                }
            } catch (Exception e) {
                set(Step.ERROR, "Couldn't check " + name() + ": " + e.getMessage());
            }
        }).start();
    }

    /** Installs Buddy's Linux (4 MB) if needed, then the agent: Claude Code (~230 MB) or Codex. */
    void install(Context c) {
        app = c.getApplicationContext();
        if (busy()) return;
        final Context app = c.getApplicationContext();
        set(Step.INSTALLING, "Setting up Buddy's Linux…");
        new Thread(() -> {
            try {
                synchronized (Account.class) { // both agents use apk; don't run it twice at once
                    Linux.install(app);
                    Helper.sync(app);
                    set(Step.INSTALLING, "Installing tools…");
                    String o = Linux.exec(app, "apk update -q && apk add -q bash curl jq libgcc libstdc++ ripgrep git "
                            + "ca-certificates procps coreutils findutils" + (claude() ? "" : " nodejs npm")
                            + " && echo TOOLS_OK", 900);
                    if (!o.contains("TOOLS_OK")) throw new IOException(tail(o));
                }
                if (claude()) {
                    set(Step.INSTALLING, "Downloading Claude Code (about 230 MB)…");
                    String o = Linux.exec(app, "curl -fsSL https://claude.ai/install.sh -o /tmp/install.sh && bash /tmp/install.sh"
                            + " && /root/.local/bin/claude --version && echo AGENT_OK", 1800);
                    if (!o.contains("AGENT_OK")) throw new IOException(tail(o));
                } else {
                    set(Step.INSTALLING, "Downloading Codex…");
                    String o = Linux.exec(app, "npm install -g @openai/codex >/tmp/npm.log 2>&1; tail -5 /tmp/npm.log;"
                            + " codex --version && echo AGENT_OK", 1800);
                    if (!o.contains("AGENT_OK")) throw new IOException(tail(o));
                }
                set(Step.SIGNED_OUT, "");
                refresh(app);
            } catch (Exception e) {
                set(Step.ERROR, "Install failed: " + e.getMessage());
            }
        }).start();
    }

    /** Starts the agent's login; the sign-in URL appears in {@link #url} when it's ready. */
    void startLogin(Context c) {
        app = c.getApplicationContext();
        if (busy()) return;
        final Context app = c.getApplicationContext();
        url = null;
        state = null;
        set(Step.WAITING_BROWSER, "Starting sign-in…");
        new Thread(() -> {
            StringBuilder out = new StringBuilder();
            try {
                Process p = (claude()
                        ? Linux.builder(app, "/root/.local/bin/claude", "auth", "login", "--claudeai")
                        : Linux.builder(app, "codex", "login")).start();
                synchronized (this) {
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
                if (p.waitFor() == 0) {
                    // signed in: bring Buddy back from the browser
                    main.post(() -> {
                        try {
                            app.startActivity(new android.content.Intent(app, SignInActivity.class).putExtra("agent", agent)
                                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK
                                            | android.content.Intent.FLAG_ACTIVITY_REORDER_TO_FRONT));
                        } catch (Exception ignored) {
                        }
                    });
                }
            } catch (Exception e) {
                out.append("\n").append(e.getMessage());
            } finally {
                synchronized (this) {
                    login = null;
                    loginIn = null;
                }
            }
            url = null;
            step = Step.CHECKING;
            refresh(app);
            final String why = tail(stripAnsi(out.toString()));
            main.postDelayed(() -> {
                if (step == Step.SIGNED_OUT) set(Step.ERROR, "Sign-in didn't finish. " + why);
            }, 4000);
        }).start();
    }

    /** Hands Claude's one-time code to Claude Code. Returns false if no sign-in is waiting. */
    synchronized boolean submit(String code) {
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

    synchronized void cancelLogin() {
        if (login != null) login.destroy();
    }

    void logout(Context c) {
        final Context app = c.getApplicationContext();
        new Thread(() -> {
            try {
                Linux.exec(app, claude() ? "claude auth logout" : "codex logout", 60);
            } catch (Exception ignored) {
            }
            refresh(app);
        }).start();
    }

    /** Claude's confirmation code if this text contains it ("&lt;code&gt;#&lt;state&gt;"), else null. */
    String findCode(CharSequence text) {
        String s = state;
        if (!claude() || text == null || s == null || s.isEmpty()) return null;
        Matcher m = Pattern.compile("[A-Za-z0-9_\\-]{16,}#" + Pattern.quote(s)).matcher(text);
        return m.find() ? m.group() : null;
    }

    boolean waitingForCode() {
        return claude() && step == Step.WAITING_BROWSER && url != null;
    }

    private static String stripAnsi(String s) {
        return s.replaceAll("\u001B\\[[0-9;?]*[ -/]*[@-~]", "").replaceAll("\u001B\\][^\u0007]*\u0007", "");
    }

    private static String tail(String s) {
        s = s.trim();
        return s.length() > 300 ? "…" + s.substring(s.length() - 300) : s;
    }
}
