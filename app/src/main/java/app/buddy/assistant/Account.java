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
 * One agent (Claude Code, Codex, OpenCode, Antigravity or Grok) inside Buddy's built-in Linux: install it, sign in,
 * check the account. Claude Code's and Antigravity's sign-ins show a one-time code in the browser; Buddy reads it off
 * the screen (accessibility), the clipboard, or a paste box. Codex's sign-in returns to a local address inside Linux by
 * itself; Grok's is a device code the user confirms in the browser. OpenCode needs no sign-in (its free models work
 * right away); providers are connected in the terminal.
 */
final class Account {
    enum Step { CHECKING, NEED_INSTALL, INSTALLING, SIGNED_OUT, WAITING_BROWSER, FINISHING, SIGNED_IN, ERROR }

    interface Listener {
        void onAccountChanged();
    }

    static final Account CLAUDE = new Account("claude");
    static final Account CODEX = new Account("codex");
    static final Account OPENCODE = new Account("opencode");
    static final Account AGY = new Account("agy");
    static final Account GROK = new Account("grok");
    static final Account[] ALL = {CLAUDE, CODEX, OPENCODE, AGY, GROK};

    static Account of(String agent) {
        return "codex".equals(agent) ? CODEX : "opencode".equals(agent) ? OPENCODE : "agy".equals(agent) ? AGY
                : "grok".equals(agent) ? GROK : CLAUDE;
    }

    /** Is this one of Buddy's agents ("claude", "codex", "opencode", "agy" or "grok")? */
    static boolean known(String agent) {
        return of(agent).agent.equals(agent);
    }

    /** The agent's display name: "Claude Code", "Codex", "OpenCode", "Antigravity" or "Grok". */
    static String nameOf(String agent) {
        return of(agent).name();
    }

    /** Its command-line name, shown in mono labels: "claude-code", "codex", "opencode", "agy" or "grok". */
    static String cliOf(String agent) {
        return "codex".equals(agent) ? "codex" : "opencode".equals(agent) ? "opencode" : "agy".equals(agent) ? "agy"
                : "grok".equals(agent) ? "grok" : "claude-code";
    }

    final String agent;
    volatile Step step = Step.CHECKING;
    volatile String detail = "", email = "", plan = "";
    /** The sign-in page to open, and (Claude) the OAuth state that the confirmation code ends with. */
    volatile String url, state;
    /** Signing in from another device: show the link (and Codex's one-time code) instead of opening the browser here. */
    volatile boolean remote;
    /** Codex's device code ("ABCD-12345") in remote mode; Grok's ("ABCD-1234") always. */
    volatile String userCode;
    /** OpenCode 2 (npm @opencode/cli) rather than 1.x: its commands need --standalone, or it leaves a background service. */
    volatile boolean opencode2;
    /** The installed agent's version ("2.1.40"), or "" if unknown. */
    volatile String version = "";
    /** Updating rather than installing (same INSTALLING step), and what the last update did, for the screen. */
    volatile boolean updating;
    volatile String updateNote = "";
    /** A failed install's own output, behind Details when detail says it in plain words ("" otherwise). */
    volatile String errorDetail = "";

    private static final Handler main = new Handler(Looper.getMainLooper());
    private static volatile Context app;
    private static final List<Listener> listeners = new ArrayList<>();
    private Process login;
    private OutputStream loginIn;
    private volatile long codeSentAt;

    private Account(String agent) {
        this.agent = agent;
    }

    boolean claude() {
        return "claude".equals(agent);
    }

    boolean opencode() {
        return "opencode".equals(agent);
    }

    boolean agy() {
        return "agy".equals(agent);
    }

    boolean grok() {
        return "grok".equals(agent);
    }

    /** Usable without paying, within limits (OpenCode's free models, Antigravity's and Grok's free plans). */
    boolean freeTier() {
        return opencode() || agy() || grok();
    }

    String name() {
        return claude() ? "Claude Code" : opencode() ? "OpenCode" : agy() ? "Antigravity" : grok() ? "Grok" : "Codex";
    }

    /** The plan to show: OpenCode's "free models" marker in the user's language, anything else as it is. */
    String planLabel() {
        return "free models".equals(plan) ? BuddyApp.str(R.string.onboarding_agent_free_models) : plan;
    }

    /** One line on what the agent runs on, for pickers. */
    String tagline() {
        return BuddyApp.str(claude() ? R.string.account_tagline_claude : opencode() ? R.string.account_tagline_opencode
                : agy() ? R.string.account_tagline_agy : grok() ? R.string.account_tagline_grok : R.string.account_tagline_codex);
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
                            + "claude auth status 2>/dev/null; echo; echo VER=$(claude --version 2>/dev/null)", 60);
                    if (out.contains("NOTINSTALLED")) {
                        set(Step.NEED_INSTALL, "");
                        return;
                    }
                    version = ver(out);
                    int from = out.indexOf('{'), to = out.lastIndexOf('}');
                    if (from < 0 || to < from) { // no status: a slow phone hit the timeout, or claude failed
                        if (out.contains("[timed out")) throw new Exception("it took too long, please try again.");
                        set(Step.SIGNED_OUT, "");
                        return;
                    }
                    JSONObject j = new JSONObject(out.substring(from, to + 1));
                    if (j.optBoolean("loggedIn")) {
                        email = j.optString("email", j.optString("emailAddress", ""));
                        plan = j.optString("subscriptionType", "");
                        set(Step.SIGNED_IN, "");
                    } else {
                        set(Step.SIGNED_OUT, "");
                    }
                } else if (opencode()) {
                    // no sign-in needed: installed means ready. Connected providers (if any) are shown as the plan.
                    // 1.x keeps connected providers in auth.json; 2 in its database (it imports auth.json on upgrade)
                    String out = Linux.exec(app, "command -v opencode >/dev/null || { echo NOTINSTALLED; exit 0; }; "
                            + "if readlink -f \"$(command -v opencode)\" | grep -q /@opencode/cli/; then echo OC_V2; "
                            + "timeout 45 opencode auth list --standalone --format json 2>/dev/null | jq -r '[.[].name] | join(\", \")'; "
                            + "else jq -r 'keys | join(\", \")' /root/.local/share/opencode/auth.json 2>/dev/null; fi; echo OC_OK; "
                            + "echo VER=$(timeout 45 opencode --version 2>/dev/null)", 120);
                    if (out.contains("NOTINSTALLED")) {
                        set(Step.NEED_INSTALL, "");
                    } else {
                        opencode2 = out.contains("OC_V2");
                        version = ver(out);
                        // a slow phone may hit the timeout before OC_OK (issue #9): installed still means ready
                        int ok = out.indexOf("OC_OK");
                        String providers = ok < 0 ? "" : out.substring(0, ok).replace("OC_V2", "").trim();
                        email = "";
                        plan = providers.isEmpty() ? "free models" : providers;
                        set(Step.SIGNED_IN, "");
                        // its model list (free ones plus connected providers), for the model picker
                        String list = Linux.exec(app, "timeout 60 opencode models" + (opencode2 ? " --standalone" : "")
                                + " 2>/dev/null", 70);
                        org.json.JSONArray a = new org.json.JSONArray();
                        for (String line : list.split("\n")) {
                            String m = line.trim();
                            if (m.matches("[A-Za-z0-9._-]+/\\S+")) a.put(new org.json.JSONArray().put(m).put(m.substring(m.indexOf('/') + 1)));
                        }
                        if (a.length() > 0) {
                            Prefs.setOpencodeModels(app, a.toString());
                            Models.setOpencode(a.toString());
                        }
                    }
                } else if (agy()) {
                    // signed in = its saved Google login (a file: Buddy's Linux has no keyring)
                    String out = Linux.exec(app, "[ -x " + AGY_BIN + " ] || { echo NOTINSTALLED; exit 0; }; "
                            + "[ -s " + AGY_TOKEN + " ] && echo AGY_SIGNED; echo VER=$(" + AGY_BIN + " --version 2>/dev/null)", 60);
                    version = ver(out);
                    if (out.contains("NOTINSTALLED")) {
                        set(Step.NEED_INSTALL, "");
                    } else if (out.contains("AGY_SIGNED")) {
                        email = "";
                        plan = "";
                        set(Step.SIGNED_IN, "");
                        // its model list (depends on the plan), for the model picker: one lookup at a time, at most
                        // every 10 minutes (each screen refreshes the account; the helper's ping also updates it)
                        long now = System.currentTimeMillis();
                        if (agyModelsBusy || now - agyModelsAt < 600_000) return;
                        agyModelsBusy = true;
                        String list;
                        try {
                            list = Linux.exec(app, "timeout 40 " + AGY_BIN + " models 2>/dev/null", 50);
                        } finally {
                            agyModelsBusy = false;
                        }
                        org.json.JSONArray a = new org.json.JSONArray();
                        for (String line : list.split("\n")) {
                            String[] f = line.trim().split("\t");
                            if (f.length == 2 && f[0].matches("[A-Za-z0-9._-]+")) a.put(new org.json.JSONArray().put(f[0]).put(f[1].trim()));
                        }
                        if (a.length() > 0) {
                            agyModelsAt = now;
                            Prefs.setAgyModels(app, a.toString());
                            Models.setAgy(a.toString());
                        }
                    } else {
                        set(Step.SIGNED_OUT, "");
                    }
                } else if (grok()) {
                    // signed in = its saved login (auth.json, which also has the account's email)
                    String out = Linux.exec(app, "[ -x " + GROK_BIN + " ] || { echo NOTINSTALLED; exit 0; }; "
                            + "[ -s " + GROK_AUTH + " ] && echo GROK_SIGNED=$(jq -r '[.[].email // empty] | first // empty' "
                            + GROK_AUTH + " 2>/dev/null); echo VER=$(" + GROK_BIN + " --version 2>/dev/null)", 60);
                    version = ver(out);
                    Matcher signed = Pattern.compile("GROK_SIGNED=(\\S*)").matcher(out);
                    if (out.contains("NOTINSTALLED")) {
                        set(Step.NEED_INSTALL, "");
                    } else if (signed.find()) {
                        email = signed.group(1);
                        plan = "";
                        set(Step.SIGNED_IN, "");
                        // its model list ("  * grok-4.7 (default)" lines), for the model picker: as for Antigravity's,
                        // one lookup at a time, at most every 10 minutes
                        long now = System.currentTimeMillis();
                        if (grokModelsBusy || now - grokModelsAt < 600_000) return;
                        grokModelsBusy = true;
                        String list;
                        try {
                            list = Linux.exec(app, "timeout 40 " + GROK_BIN + " models 2>/dev/null", 50);
                        } finally {
                            grokModelsBusy = false;
                        }
                        org.json.JSONArray a = new org.json.JSONArray();
                        for (String line : list.split("\n")) {
                            Matcher m = Pattern.compile("^\\s*[*-]\\s+([A-Za-z0-9._-]+)").matcher(line);
                            if (m.find()) a.put(new org.json.JSONArray().put(m.group(1)).put(m.group(1)));
                        }
                        if (a.length() > 0) {
                            grokModelsAt = now;
                            Prefs.setGrokModels(app, a.toString());
                            Models.setGrok(a.toString());
                        }
                    } else {
                        set(Step.SIGNED_OUT, "");
                    }
                } else {
                    String out = Linux.exec(app, "command -v codex >/dev/null || { echo NOTINSTALLED; exit 0; }; "
                            + "codex login status 2>&1; echo VER=$(codex --version 2>/dev/null)", 60);
                    version = ver(out);
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
                set(Step.ERROR, app.getString(R.string.account_check_failed, name(), e.getMessage()));
            }
        }).start();
    }

    /** Installs Buddy's Linux (4 MB) if needed, then the agent: Claude Code (~230 MB) or Codex. */
    void install(Context c) {
        app = c.getApplicationContext();
        if (busy()) return;
        final Context app = c.getApplicationContext();
        errorDetail = "";
        // an agent, node and Alpine's packages take a few hundred MB: ask for about 1 GB before starting
        long free = new android.os.StatFs(app.getFilesDir().getPath()).getAvailableBytes();
        if (free < 1024L * 1024 * 1024) {
            set(Step.ERROR, app.getString(R.string.account_install_space_needed,
                    android.text.format.Formatter.formatShortFileSize(app, free)));
            return;
        }
        set(Step.INSTALLING, app.getString(R.string.account_setting_up_linux));
        new Thread(() -> {
            try {
                synchronized (Account.class) { // both agents use apk; don't run it twice at once
                    Linux.install(app);
                    Helper.sync(app);
                    set(Step.INSTALLING, app.getString(R.string.account_installing_tools));
                    String o = Linux.exec(app, "apk update -q && apk add -q bash curl jq libgcc libstdc++ ripgrep git "
                            + "ca-certificates procps coreutils findutils" + (claude() || agy() || grok() ? "" : " nodejs npm")
                            + (agy() ? " util-linux-misc" : "") // script: Antigravity's sign-in needs a terminal
                            + " && echo TOOLS_OK", 900);
                    if (!o.contains("TOOLS_OK")) throw new IOException(tail(o));
                }
                if (claude()) {
                    set(Step.INSTALLING, app.getString(R.string.account_downloading_claude));
                    String o = Linux.exec(app, "curl -fsSL https://claude.ai/install.sh -o /tmp/install.sh && bash /tmp/install.sh"
                            + " && /root/.local/bin/claude --version && echo AGENT_OK", 1800);
                    if (!o.contains("AGENT_OK")) throw new IOException(tail(o));
                } else if (agy()) {
                    set(Step.INSTALLING, app.getString(R.string.account_downloading, name()));
                    // Google's installer picks the Alpine (musl) build and puts agy in ~/.local/bin
                    String o = Linux.exec(app, "curl -fsSL https://antigravity.google/cli/install.sh -o /tmp/agy-install.sh"
                            + " && bash /tmp/agy-install.sh 2>&1 | tail -5 && " + AGY_BIN + " --version && echo AGENT_OK", 1800);
                    if (!o.contains("AGENT_OK")) throw new IOException(tail(o));
                } else if (grok()) {
                    set(Step.INSTALLING, app.getString(R.string.account_downloading, name()));
                    // xAI's installer fetches one static binary (it runs on Alpine as it is) and links it in ~/.local/bin
                    String o = Linux.exec(app, "curl -fsSL https://x.ai/cli/install.sh -o /tmp/grok-install.sh"
                            + " && bash /tmp/grok-install.sh 2>&1 | tail -5 && " + GROK_BIN + " --version && echo AGENT_OK", 1800);
                    if (!o.contains("AGENT_OK")) throw new IOException(tail(o));
                } else if (opencode()) {
                    set(Step.INSTALLING, app.getString(R.string.account_downloading, name()));
                    // OpenCode 2 (npm @opencode/cli), always a fresh install (OC_REMOVE)
                    String o = Linux.exec(app, OC_REMOVE + " npm install -g @opencode/cli >/tmp/npm.log 2>&1;"
                            + " tail -5 /tmp/npm.log;" + OC_GLIBC + " opencode --version && echo AGENT_OK", 1800);
                    if (!o.contains("AGENT_OK")) throw new IOException(tail(o));
                } else {
                    set(Step.INSTALLING, app.getString(R.string.account_downloading, name()));
                    String o = Linux.exec(app, "npm install -g @openai/codex >/tmp/npm.log 2>&1; tail -5 /tmp/npm.log;"
                            + " codex --version && echo AGENT_OK", 1800);
                    if (!o.contains("AGENT_OK")) throw new IOException(tail(o));
                }
                set(Step.SIGNED_OUT, "");
                refresh(app);
            } catch (Exception e) {
                String raw = String.valueOf(e.getMessage()), plain = plainError(app, raw);
                errorDetail = plain == null ? "" : raw;
                set(Step.ERROR, plain != null ? plain : app.getString(R.string.account_install_failed, raw));
            }
        }).start();
    }

    /**
     * Removes OpenCode (2 or 1.x) before npm installs it again. Its install hard-links the binary, and Buddy's
     * Linux fakes hard links with symlinks (proot --link2symlink): when npm replaces an installed copy and
     * deletes the old links, the new binary goes with them ("opencode: not found"). A fresh install is fine.
     * Twice: the first rm can leave the binary behind as a hidden .l2s file (~200 MB).
     */
    private static final String OC_REMOVE = " for i in 1 2; do rm -rf /usr/local/lib/node_modules/@opencode/cli"
            + " /usr/local/lib/node_modules/@opencode/.cli-* /usr/local/lib/node_modules/opencode-ai"
            + " /usr/local/bin/opencode /usr/local/bin/opencode2 2>/dev/null; done;";

    /** npm also fetches OpenCode's glibc build; Buddy's Alpine only runs the musl one, so drop the other (~200 MB). */
    private static final String OC_GLIBC = " rm -rf /usr/local/lib/node_modules/@opencode/cli/node_modules/@opencode/cli-linux-arm64;";

    private static final String AGY_BIN = "/root/.local/bin/agy";
    private static volatile boolean agyModelsBusy;
    private static volatile long agyModelsAt;
    private static final String AGY_TOKEN = "/root/.gemini/antigravity-cli/antigravity-oauth-token";

    private static final String GROK_BIN = "/root/.local/bin/grok";
    private static final String GROK_AUTH = "/root/.grok/auth.json";
    private static volatile boolean grokModelsBusy;
    private static volatile long grokModelsAt;

    private static String ver(String out) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("VER=\\D*(\\d+\\.\\d+\\.\\d+)").matcher(out);
        return m.find() ? m.group(1) : "";
    }

    /** OpenCode 1.x is installed (npm opencode-ai): its update moves it to OpenCode 2. */
    boolean oldOpencode() {
        return opencode() && !opencode2 && !version.isEmpty();
    }

    /** Updates the agent to its latest version; {@link #updateNote} says what happened. */
    void update(Context c) {
        app = c.getApplicationContext();
        if (busy()) return;
        final Context app = c.getApplicationContext();
        final String before = version;
        final boolean toV2 = oldOpencode();
        updating = true;
        updateNote = "";
        set(Step.INSTALLING, toV2 ? app.getString(R.string.account_downloading, "OpenCode 2")
                : app.getString(R.string.account_update_checking, name()));
        new Thread(() -> {
            String o;
            try {
                if (claude()) {
                    o = Linux.exec(app, "/root/.local/bin/claude update 2>&1 | tail -3; echo VER=$(/root/.local/bin/claude --version)", 900);
                } else if (agy()) {
                    o = Linux.exec(app, AGY_BIN + " update 2>&1 | tail -3; echo VER=$(" + AGY_BIN + " --version)", 900);
                } else if (grok()) {
                    o = Linux.exec(app, GROK_BIN + " update 2>&1 | tail -3; echo VER=$(" + GROK_BIN + " --version)", 900);
                } else if (opencode()) {
                    // Reinstalls only when npm has a newer version (1.x counts: OpenCode 2 is a new npm package that
                    // keeps 1.x's connected providers). The install removes the old copy first (OC_REMOVE), so it
                    // downloads into npm's cache before that: a failed download leaves the old one working.
                    o = Linux.exec(app, ": >/tmp/npm.log; v=$(npm view @opencode/cli version 2>>/tmp/npm.log);"
                            + " [ -n \"$v\" ] || { tail -3 /tmp/npm.log; exit 0; };"
                            + " if [ \"$(opencode --version 2>/dev/null | grep -oE '[0-9]+[.][0-9]+[.][0-9]+' | head -1)\" != \"$v\" ]; then"
                            + " npm cache add @opencode/cli@$v @opencode/cli-linux-arm64-musl@$v >>/tmp/npm.log 2>&1"
                            + " || { tail -3 /tmp/npm.log; exit 0; };" + OC_REMOVE
                            + " npm install -g @opencode/cli@$v >>/tmp/npm.log 2>&1; fi;"
                            + OC_GLIBC + " tail -3 /tmp/npm.log; echo VER=$(opencode --version)", 1800);
                } else {
                    o = Linux.exec(app, "npm install -g @openai/codex@latest >/tmp/npm.log 2>&1; tail -3 /tmp/npm.log;"
                            + " echo VER=$(codex --version)", 1800);
                }
                String after = ver(o);
                if (after.isEmpty()) throw new IOException(tail(o));
                updateNote = after.equals(before) ? app.getString(R.string.account_update_latest, after)
                        : before.isEmpty() ? app.getString(R.string.account_updated, after)
                        : app.getString(R.string.account_updated_from, after, before);
                if (toV2 && !after.startsWith("2") && !after.matches("[3-9].*")) updateNote = app.getString(
                        R.string.account_update_opencode2_failed, after);
            } catch (Exception e) {
                updateNote = app.getString(R.string.account_update_failed, e.getMessage());
            }
            updating = false;
            set(Step.CHECKING, "");
            refresh(app);
        }).start();
    }

    /** Starts the agent's login; the sign-in URL appears in {@link #url} when it's ready. */
    void startLogin(Context c) {
        startLogin(c, false);
    }

    /** remote = sign in on another device: Codex uses its device code; Claude and Antigravity show their link to open
     *  elsewhere. Antigravity has no login command: a one-line prompt asks for the sign-in first, then answers. It only
     *  offers the sign-in on a terminal ("no controlling terminal; cannot complete interactive login"), so it runs
     *  under script, which gives it one. Grok always signs in with a device code: its link carries the code, so here
     *  it opens in the browser and the user confirms the code there; remote shows the link and code as for Codex. */
    void startLogin(Context c, boolean onOtherDevice) {
        app = c.getApplicationContext();
        if (busy() || opencode()) return; // OpenCode: providers are connected in the terminal (opencode auth login)
        final Context app = c.getApplicationContext();
        url = null;
        state = null;
        userCode = null;
        remote = onOtherDevice;
        set(Step.WAITING_BROWSER, app.getString(R.string.signin_starting));
        new Thread(() -> {
            StringBuilder out = new StringBuilder();
            try {
                Process p = (claude()
                        ? Linux.builder(app, "/root/.local/bin/claude", "auth", "login", "--claudeai")
                        : agy() ? Linux.builder(app, "/bin/sh", "-c", "command -v script >/dev/null || apk add -q util-linux-misc;"
                                + " exec script -qec \"" + AGY_BIN + " -p 'Reply with just: ok'\" /dev/null")
                        : grok() ? Linux.builder(app, GROK_BIN, "login", "--device-auth")
                        : onOtherDevice ? Linux.builder(app, "codex", "login", "--device-auth")
                        : Linux.builder(app, "codex", "login")).start();
                synchronized (this) {
                    login = p;
                    loginIn = p.getOutputStream();
                }
                if (agy()) new Thread(() -> {
                    // signed in but agy still busy on its terminal (a first-run question nobody sees): stop it
                    try {
                        while (p.isAlive()) {
                            Thread.sleep(3000);
                            if (step == Step.FINISHING && System.currentTimeMillis() - codeSentAt > 20_000
                                    && Linux.exec(app, "[ -s " + AGY_TOKEN + " ] && echo AGY_SIGNED", 20).contains("AGY_SIGNED")) {
                                p.destroy();
                            }
                        }
                    } catch (Exception ignored) {
                    }
                }).start();
                boolean device = grok() || onOtherDevice && !claude() && !agy(); // Codex's or Grok's device code
                Pattern u = Pattern.compile(agy() ? "https://accounts\\.google\\.com/o/oauth2/auth\\?\\S+"
                        : grok() ? "https://accounts\\.x\\.ai/oauth2/device\\S*"
                        : device ? "https://\\S*/codex/device\\S*" : "https://\\S*/oauth/authorize\\?\\S+");
                Pattern code = Pattern.compile("one-time code[^\\n]*\\n\\s*([A-Z0-9][A-Z0-9-]{4,15})");
                try (InputStream in = p.getInputStream()) {
                    byte[] buf = new byte[4096];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.append(new String(buf, 0, n, StandardCharsets.UTF_8));
                        String plain = stripAnsi(out.toString());
                        if (url == null) {
                            Matcher m = u.matcher(plain);
                            if (m.find()) {
                                url = m.group();
                                state = Uri.parse(url).getQueryParameter("state");
                                // a device flow (Codex, Grok) also needs its code before the screen is useful
                                if (!device) set(Step.WAITING_BROWSER, "");
                            }
                        }
                        if (device && userCode == null) {
                            Matcher m = code.matcher(plain);
                            if (!m.find()) {
                                m = Pattern.compile("\\b([A-Z0-9]{4,5}-[A-Z0-9]{4,6})\\b").matcher(plain);
                                if (!m.find()) m = null;
                            }
                            if (m != null) {
                                userCode = m.group(1);
                                if (url != null) set(Step.WAITING_BROWSER, "");
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
                if (step == Step.SIGNED_OUT) set(Step.ERROR, app.getString(R.string.signin_not_finished, why));
            }, 4000);
        }).start();
    }

    /** Hands Claude's one-time code to Claude Code. Returns false if no sign-in is waiting. */
    synchronized boolean submit(String code) {
        if (loginIn == null || code == null || code.trim().isEmpty()) return false;
        try {
            // Antigravity reads it on a terminal (see startLogin), where Enter is a carriage return
            loginIn.write((code.trim() + (agy() ? "\r" : "\n")).getBytes(StandardCharsets.UTF_8));
            loginIn.flush();
            codeSentAt = System.currentTimeMillis();
            set(Step.FINISHING, BuddyApp.str(R.string.signin_finishing));
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
                if (agy()) Linux.exec(app, "rm -f " + AGY_TOKEN, 60); // agy only signs out from its own screen (/logout)
                else if (grok()) Linux.exec(app, GROK_BIN + " logout", 60);
                else if (!opencode()) Linux.exec(app, claude() ? "claude auth logout" : "codex logout", 60);
            } catch (Exception ignored) {
            }
            refresh(app);
        }).start();
    }

    /** The confirmation code if this text contains it, else null: Claude's "&lt;code&gt;#&lt;state&gt;", or the Google
     *  authorization code Antigravity's page shows ("4/0A…"; in the address bar it's URL-encoded, so not matched). */
    String findCode(CharSequence text) {
        if (text == null) return null;
        if (agy()) {
            Matcher m = Pattern.compile("(?<![A-Za-z0-9_/-])4/[0-9A-Za-z_-]{30,}").matcher(text);
            return m.find() ? m.group() : null;
        }
        String s = state;
        if (!claude() || s == null || s.isEmpty()) return null;
        Matcher m = Pattern.compile("[A-Za-z0-9_\\-]{16,}#" + Pattern.quote(s)).matcher(text);
        return m.find() ? m.group() : null;
    }

    /** Can run notification rules? A rule's run works for someone else's message and gets no shell, files or web;
     *  Buddy can't yet hold Antigravity to that (only its allow list is known), so its chats are for the user only. */
    boolean runsRules() {
        return !agy();
    }

    /** Signs in with a code pasted from the browser page (Claude Code, Antigravity). */
    boolean pastesCode() {
        return claude() || agy();
    }

    boolean waitingForCode() {
        return pastesCode() && step == Step.WAITING_BROWSER && url != null;
    }

    /** The account whose sign-in waits for its code, or null. */
    static Account waiting() {
        for (Account a : ALL) if (a.waitingForCode()) return a;
        return null;
    }

    private static String stripAnsi(String s) {
        return s.replaceAll("\u001B\\[[0-9;?]*[ -/]*[@-~]", "").replaceAll("\u001B\\][^\u0007]*\u0007", "");
    }

    /** The common ways an install fails, in plain words (null: none of them; the raw output is shown instead). */
    private static String plainError(Context c, String out) {
        if (out.contains("No space left")) return c.getString(R.string.account_install_no_space);
        if (out.matches("(?s).*(Could not resolve host|curl: \\((6|7|28)\\)|ENOTFOUND|ETIMEDOUT|EAI_AGAIN|ECONNRESET"
                + "|Network is unreachable|temporary error \\(try again later\\)).*")) {
            return c.getString(R.string.account_install_network);
        }
        if (out.matches("(?s).*(error: 403|error: 429|\\b403 Forbidden|\\b429 Too Many|E403|E429).*")) {
            return c.getString(R.string.account_install_refused);
        }
        return null;
    }

    private static String tail(String s) {
        s = s.trim();
        return s.length() > 300 ? "…" + s.substring(s.length() - 300) : s;
    }
}
