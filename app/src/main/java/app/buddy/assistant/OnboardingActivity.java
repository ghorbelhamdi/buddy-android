package app.buddy.assistant;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import static app.buddy.assistant.Ui.*;

/**
 * First-run guide: one step per screen. Steps that happen elsewhere (Termux, Android settings) are
 * detected when the user comes back; while they're in Termux, the service checks and brings this back.
 */
public class OnboardingActivity extends Screen {
    static final int WELCOME = 0, BRAIN = 1, TERMUX = 2, ACCESS = 3, PERMISSION = 4, CONNECT = 5, AGENTS = 6,
            BACKGROUND = 7, TRY = 8;
    private static final int LAST = TRY;

    private ScrollView scroll;
    private LinearLayout body, bottom;
    private TextView progress;
    private int step;
    private int accessTries;
    private boolean stuckOpen;
    /** After Back, stay on the (possibly done) step until the user moves on themselves. */
    private boolean noAutoAdvance;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        step = Math.min(Prefs.onboardStep(this), LAST);

        LinearLayout root = column(this);
        root.setBackgroundColor(BG);
        root.setFitsSystemWindows(true);

        LinearLayout top = row(this);
        top.setPadding(dp(this, 20), dp(this, 12), dp(this, 8), dp(this, 4));
        progress = monoText(this, "", 12, MUTED);
        top.addView(progress, weight1());
        Button skip = button(this, "Skip setup", false);
        skip.setBackgroundResource(ripple(this, false));
        skip.setTextColor(MUTED);
        skip.setOnClickListener(v -> confirmSheet(this, "Skip the setup guide?",
                "You can finish setup any time in Settings. Chats won't run until it's done.", "Skip", false, this::finishGuide));
        top.addView(skip);
        root.addView(top, full());

        scroll = new ScrollView(this);
        body = column(this);
        body.setPadding(dp(this, 24), dp(this, 16), dp(this, 24), dp(this, 24));
        scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        bottom = column(this);
        bottom.setPadding(dp(this, 20), dp(this, 8), dp(this, 20), dp(this, 16));
        root.addView(bottom, full());
        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (isFinishing()) return;
        render();
        if (step == CONNECT || step == AGENTS) ping();
        checkAdvance();
    }

    @Override
    public void onBackPressed() {
        if (step > WELCOME) {
            noAutoAdvance = true;
            go(step - 1);
        } else {
            super.onBackPressed();
        }
    }

    // ------------------------------------------------------------- state

    /** Whether the step the guide is on is done (the service asks this after each helper answer). */
    static boolean currentStepDone(Context c) {
        return done(c, Prefs.onboardStep(c));
    }

    static boolean done(Context c, int step) {
        switch (step) {
            case TERMUX:
                return Termux.installed(c);
            case ACCESS:
                return SetupState.accessibilityEnabled(c);
            case PERMISSION:
                return Termux.permitted(c);
            case CONNECT:
                return SetupState.helperCurrent(c);
            case AGENTS:
                if (Prefs.signedAgents(c) == null) return false;
                for (String a : wanted(c)) if (!SetupState.agentReady(c, a)) return false;
                return true;
            case BACKGROUND: {
                PowerManager pm = c.getSystemService(PowerManager.class);
                return pm.isIgnoringBatteryOptimizations(c.getPackageName())
                        && pm.isIgnoringBatteryOptimizations(Termux.PACKAGE);
            }
            default:
                return false;
        }
    }

    static String[] wanted(Context c) {
        String a = Prefs.agents(c);
        if ("both".equals(a)) return new String[]{"claude", "codex"};
        return new String[]{"codex".equals(a) ? "codex" : "claude"};
    }

    private void go(int s) {
        if (s > step) noAutoAdvance = false;
        step = Math.max(WELCOME, Math.min(LAST, s));
        Prefs.setOnboardStep(this, step);
        stuckOpen = false;
        render();
        scroll.scrollTo(0, 0);
        if (step == CONNECT || step == AGENTS) ping();
        checkAdvance();
    }

    /** If the current step is already done, show the check briefly and move on. */
    private void checkAdvance() {
        if (noAutoAdvance || !done(this, step)) return;
        final int at = step;
        body.postDelayed(() -> {
            if (step == at && done(this, at) && !isFinishing()) go(at + 1);
        }, 900);
    }

    private void ping() {
        if (BuddyService.get() == null || !Termux.permitted(this) || !Termux.installed(this)) return;
        try {
            Termux.run(this, "ping");
        } catch (Exception ignored) {
            return;
        }
        body.postDelayed(this::refreshIfDone, 1500);
        body.postDelayed(this::refreshIfDone, 4000);
    }

    private void refreshIfDone() {
        if (isFinishing() || isDestroyed()) return;
        render();
        checkAdvance();
    }

    /** The user is going to Termux: keep checking from the service and come back when the step is done. */
    private void watchTermux() {
        Prefs.setSetupWatchUntil(this, System.currentTimeMillis() + 15 * 60_000L);
        BuddyService svc = BuddyService.get();
        if (svc != null) svc.watchSetup();
    }

    private void finishGuide() {
        Prefs.setOnboarded(this, true);
        Prefs.setSetupWatchUntil(this, 0);
        startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP));
        finish();
    }

    // ------------------------------------------------------------ render

    private void render() {
        body.removeAllViews();
        bottom.removeAllViews();
        progress.setText(step == WELCOME ? "" : "step " + step + " of " + LAST);
        boolean ok = done(this, step);
        switch (step) {
            case WELCOME:
                welcome();
                break;
            case BRAIN:
                brain();
                break;
            case TERMUX:
                header(R.drawable.ms_terminal, "Install Termux", ok ? "Termux is installed." :
                        "Termux is a free terminal app. Buddy runs " + agentNames() + " inside it, on your phone.", ok);
                if (!ok) {
                    note("Get it from F-Droid or GitHub. The Play Store version is outdated and won't work.");
                    primary("Get Termux from F-Droid", v -> openUrl("https://f-droid.org/packages/com.termux/"));
                    secondary("GitHub releases instead", v -> openUrl("https://github.com/termux/termux-app/releases"));
                    stuck("Termux from the Play Store?", "Uninstall it first (its files are removed), then install the F-Droid or GitHub build.",
                            "Can't install the APK?", "Your browser asks to allow installing unknown apps. Allow it for this one install.");
                }
                break;
            case ACCESS:
                header(R.drawable.ms_shield_person, "Let Buddy see and tap", ok ? "Accessibility is on." :
                        "Buddy's accessibility service shows the bubble and lets the agent read the screen, tap and type, "
                                + "only when you ask it to do something.", ok);
                if (!ok) {
                    note("Nothing leaves the phone except what " + agentNames() + " sends to "
                            + ("codex".equals(Prefs.agents(this)) ? "OpenAI" : "Anthropic")
                            + " to answer you. Sending, posting, buying and deleting always ask for your OK first.");
                    if (accessTries > 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) restrictedHelp();
                    note("In the list: Downloaded apps → Buddy assistant → turn it on.");
                    primary("Open accessibility settings", v -> {
                        accessTries++;
                        startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
                    });
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        stuck("Switch greyed out?", "Android blocks this for apps installed from a file. Tap App info "
                                + "(below), then ⋮ in the top right → Allow restricted settings, and try again.", null, null);
                        secondary("App info", v -> openAppInfo(getPackageName()));
                    }
                }
                break;
            case PERMISSION:
                header(R.drawable.ms_terminal, "Allow Buddy to use Termux", ok ? "Allowed." :
                        "Buddy sends your messages to " + agentNames() + " by starting commands in Termux.", ok);
                if (!ok) {
                    primary("Allow", v -> requestPermissions(new String[]{Termux.PERMISSION}, 1));
                    stuck("No prompt appeared?", "App info → Permissions → Additional permissions → "
                            + "Run commands in Termux environment → Allow.", null, null);
                    secondary("App info", v -> openAppInfo(getPackageName()));
                }
                break;
            case CONNECT:
                header(R.drawable.ms_content_copy, "Connect Termux", ok ? "Termux answered. Connected." :
                        "One command installs Buddy's helper in Termux. Buddy copies it for you.", ok);
                if (!ok) {
                    numbered(new String[]{"Tap the button: Termux opens.", "Long-press an empty spot → Paste.",
                            "Press Enter and wait for \"Done\". Buddy comes back by itself."});
                    primary("Copy command and open Termux", v -> {
                        copy(this::setupCommand, "Copied. Long-press in Termux → Paste, then Enter.");
                        watchTermux();
                        openTermux();
                    });
                    secondary("Check again", v -> ping());
                    stuck("Termux mentions allow-external-apps?", "Close Termux fully (its notification → Exit), "
                            + "open it again and paste the command once more.",
                            "Nothing happens after Done?", "Come back to Buddy yourself and tap Check again.");
                }
                break;
            case AGENTS:
                agents(ok);
                break;
            case BACKGROUND:
                background(ok);
                break;
            case TRY:
                tryIt();
                break;
            default:
                break;
        }
        if (ok && step != WELCOME && step != BRAIN && step != TRY) {
            Button next = button(this, "Continue", true);
            next.setOnClickListener(v -> {
                noAutoAdvance = false;
                go(step + 1);
            });
            bottom.addView(next, bigButton());
        }
    }

    private void welcome() {
        LinearLayout c = column(this);
        c.setGravity(Gravity.CENTER_HORIZONTAL);
        c.setPadding(0, dp(this, 40), 0, 0);
        FrameLayout tile = new FrameLayout(this);
        tile.setBackgroundResource(R.drawable.ic_launcher_background);
        tile.setClipToOutline(true);
        tile.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View v, android.graphics.Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), dp(OnboardingActivity.this, 24));
            }
        });
        ImageView face = new ImageView(this);
        face.setImageResource(R.drawable.bubble_face);
        tile.addView(face, new FrameLayout.LayoutParams(dp(this, 64), dp(this, 64), Gravity.CENTER));
        c.addView(tile, new LinearLayout.LayoutParams(dp(this, 88), dp(this, 88)));
        TextView t = title(this, "Meet Buddy", 28);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, dp(this, 20), 0, dp(this, 6));
        c.addView(t);
        TextView s = text(this, "A chat bubble that can use your phone for you.", 16, MUTED);
        s.setGravity(Gravity.CENTER);
        c.addView(s);
        body.addView(c, full());
        LinearLayout list = column(this);
        list.setPadding(0, dp(this, 28), 0, 0);
        bullet(list, R.drawable.ms_chat_bubble, "Ask in plain words", "\"Reply to Sam that I'm late\", \"add milk to my basket\".");
        bullet(list, R.drawable.ms_shield_person, "Your own plan, no API key", "Runs Claude Code from your Claude subscription. "
                + "Codex with a ChatGPT plan works too.");
        bullet(list, R.drawable.ms_check, "You stay in control", "Sending, posting, buying and deleting always ask first.");
        body.addView(list, full());
        TextView time = monoText(this, "setup takes about 5 minutes", 12, MUTED);
        time.setGravity(Gravity.CENTER);
        time.setPadding(0, dp(this, 20), 0, 0);
        body.addView(time, full());
        primary("Get started", v -> go(BRAIN));
    }

    private void brain() {
        header(R.drawable.ms_forum, "Choose your agent", "Who answers your chats. You can add the other one later.", false);
        String cur = Prefs.agents(this);
        String[][] opts = {
                {"claude", "Claude Code", "Uses your Claude subscription (Pro or Max)."},
                {"codex", "Codex", "Uses your ChatGPT plan."},
                {"both", "Both", "Install both; pick per chat. Claude Code stays the default."},
        };
        for (String[] o : opts) {
            boolean on = o[0].equals(cur);
            LinearLayout r = row(this);
            r.setPadding(dp(this, 14), dp(this, 14), dp(this, 14), dp(this, 14));
            r.setBackground(outlined(this, CARD, 16, on ? ACCENT : OUTLINE, on ? 2 : 1));
            if ("both".equals(o[0])) {
                LinearLayout two = row(this);
                TextView a = agentTile(this, "claude");
                a.setLayoutParams(new LinearLayout.LayoutParams(dp(this, 28), dp(this, 40)));
                two.addView(a);
                TextView b = agentTile(this, "codex");
                LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(dp(this, 28), dp(this, 40));
                bp.leftMargin = dp(this, 2);
                two.addView(b, bp);
                r.addView(two);
            } else {
                r.addView(agentTile(this, o[0]));
            }
            LinearLayout t = column(this);
            t.setPadding(dp(this, 14), 0, dp(this, 8), 0);
            LinearLayout tr = row(this);
            tr.addView(title(this, o[1], 16));
            if ("claude".equals(o[0])) {
                TextView tag = monoText(this, "recommended", 11, ACCENT_TEXT);
                tag.setPadding(dp(this, 6), dp(this, 1), dp(this, 6), dp(this, 1));
                tag.setBackground(rounded(this, ACCENT_SOFT, 6));
                LinearLayout.LayoutParams tp = wrap();
                tp.leftMargin = dp(this, 8);
                tr.addView(tag, tp);
            }
            t.addView(tr);
            t.addView(text(this, o[2], 13, MUTED));
            r.addView(t, weight1());
            r.addView(radio(on));
            r.setOnClickListener(v -> {
                Prefs.setAgents(this, o[0]);
                Prefs.setBackend(this, "codex".equals(o[0]) ? "codex" : "claude");
                render();
            });
            LinearLayout.LayoutParams p = full();
            p.topMargin = dp(this, 10);
            body.addView(r, p);
        }
        primary("Continue", v -> go(TERMUX));
    }

    private void agents(boolean ok) {
        header(R.drawable.ms_terminal, ok ? "Ready" : "Install and sign in",
                ok ? agentNames() + " is set up and signed in." : "Install " + agentNames()
                        + " in Termux and sign in once. Buddy notices when you're done.", ok);
        boolean first = true;
        for (String a : wanted(this)) {
            boolean installed = SetupState.agentInstalled(this, a), ready = SetupState.agentReady(this, a)
                    && Prefs.signedAgents(this) != null;
            boolean claude = "claude".equals(a);
            LinearLayout card = column(this);
            card.setPadding(dp(this, 16), dp(this, 14), dp(this, 16), dp(this, 14));
            card.setBackground(rounded(this, CARD, 16));
            LinearLayout r = row(this);
            r.addView(agentTile(this, a));
            LinearLayout t = column(this);
            t.setPadding(dp(this, 12), 0, 0, 0);
            t.addView(title(this, claude ? "Claude Code" : "Codex", 15));
            t.addView(monoText(this, ready ? "installed · signed in" : installed ? "installed · not signed in" : "not installed",
                    12, ready ? OK : MUTED));
            r.addView(t, weight1());
            if (ready) r.addView(icon(this, R.drawable.ms_check, OK, 22));
            card.addView(r, full());
            if (!ready) {
                TextView how = text(this, installed
                        ? (claude ? "Run claude in Termux and log in with your Claude account (it opens your browser)."
                        : "Run codex login in Termux and sign in with your ChatGPT account.")
                        : (claude ? "The installer asks two yes/no questions and downloads about 230 MB. "
                        + "When it's done, Claude Code starts: log in with your Claude account."
                        : "Installs Codex with npm, then opens sign-in with your ChatGPT account."), 14, MUTED);
                how.setPadding(0, dp(this, 10), 0, dp(this, 10));
                card.addView(how);
                Button b = button(this, installed ? "Copy sign-in command" : "Copy install command", first);
                b.setOnClickListener(v -> {
                    copy(() -> installed ? (claude ? TermuxSetup.signInClaude() : TermuxSetup.signInCodex())
                            : (claude ? TermuxSetup.installClaude() : TermuxSetup.installCodex(this)),
                            "Copied. Long-press in Termux → Paste, then Enter.");
                    watchTermux();
                    openTermux();
                });
                card.addView(b, wrap());
                first = false;
            }
            LinearLayout.LayoutParams p = full();
            p.topMargin = dp(this, 12);
            body.addView(card, p);
        }
        if (!ok) {
            secondary("Check again", v -> ping());
            stuck("Sign-in page doesn't come back to Termux?", "Claude Code: copy the code shown in the browser and paste it into Termux. "
                            + "Codex: run ~/bin/codex login --device-auth and enter the code at the address it shows.",
                    "Install stopped or Termux closed?", "Open Termux and paste the same command again; it's safe to repeat.");
        }
    }

    private void background(boolean ok) {
        PowerManager pm = getSystemService(PowerManager.class);
        boolean buddyBg = pm.isIgnoringBatteryOptimizations(getPackageName());
        boolean termuxBg = pm.isIgnoringBatteryOptimizations(Termux.PACKAGE);
        header(R.drawable.ms_build_circle, "Keep it running", ok ? "Buddy and Termux can run in the background." :
                "So your phone doesn't stop Buddy or Termux to save battery while the agent works.", ok);
        statusLine("Buddy", buddyBg);
        statusLine("Termux", termuxBg);
        if (!ok) {
            if (!buddyBg) {
                primary("Allow for Buddy", v -> startActivity(new Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName()))));
            } else {
                note("For Termux: App info → Battery → Unrestricted (the name differs a little per phone).");
                primary("Open Termux app info", v -> openAppInfo(Termux.PACKAGE));
            }
            secondary("Skip for now", v -> go(TRY));
        }
    }

    private void tryIt() {
        header(R.drawable.ms_check, "You're all set", "Try a first task. Buddy will open Settings and read it for you.", true);
        TextView ex = monoText(this, "\"Open Settings and tell me my Android version\"", 13, TEXT);
        ex.setPadding(dp(this, 14), dp(this, 12), dp(this, 14), dp(this, 12));
        ex.setBackground(rounded(this, CARD, 12));
        LinearLayout.LayoutParams ep = full();
        ep.topMargin = dp(this, 16);
        body.addView(ex, ep);
        note("Tip: the bubble floats over other apps. Drag it onto the ✕ to hide it.");
        primary("Try it now", v -> {
            Prefs.setOnboarded(this, true);
            ChatHub hub = ChatHub.get(this);
            Sessions.Session s = hub.create(Prefs.backend(this));
            hub.select(s);
            startActivity(new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP));
            startActivity(new Intent(this, ChatActivity.class).putExtra("id", s.id)
                    .putExtra("draft", "Open Settings and tell me my Android version"));
            finish();
        });
        secondary("Go to my chats", v -> finishGuide());
    }

    // ------------------------------------------------------------ pieces

    private void header(int iconRes, String title, String sub, boolean ok) {
        FrameLayout ic = new FrameLayout(this);
        ic.setBackground(oval(ok ? Theme.withAlpha(OK, 0x2E) : ACCENT_SOFT));
        ic.addView(icon(this, ok ? R.drawable.ms_check : iconRes, ok ? OK : ACCENT_TEXT, 28),
                new FrameLayout.LayoutParams(dp(this, 28), dp(this, 28), Gravity.CENTER));
        LinearLayout.LayoutParams ip = new LinearLayout.LayoutParams(dp(this, 56), dp(this, 56));
        ip.topMargin = dp(this, 8);
        body.addView(ic, ip);
        TextView t = title(this, title, 26);
        t.setPadding(0, dp(this, 18), 0, dp(this, 6));
        body.addView(t);
        body.addView(text(this, sub, 16, MUTED));
    }

    private void note(String s) {
        TextView t = text(this, s, 14, MUTED);
        LinearLayout.LayoutParams p = full();
        p.topMargin = dp(this, 14);
        body.addView(t, p);
    }

    private void numbered(String[] lines) {
        LinearLayout box = column(this);
        box.setPadding(dp(this, 16), dp(this, 6), dp(this, 16), dp(this, 14));
        box.setBackground(rounded(this, CARD, 16));
        for (int i = 0; i < lines.length; i++) {
            LinearLayout r = row(this);
            r.setGravity(Gravity.TOP);
            r.setPadding(0, dp(this, 10), 0, 0);
            TextView n = monoText(this, String.valueOf(i + 1), 12, ACCENT_TEXT);
            n.setTypeface(mono(this, 600));
            n.setGravity(Gravity.CENTER);
            n.setBackground(outlined(this, 0x00000000, 11, ACCENT, 2));
            r.addView(n, new LinearLayout.LayoutParams(dp(this, 22), dp(this, 22)));
            TextView t = text(this, lines[i], 15, TEXT);
            t.setPadding(dp(this, 12), dp(this, 1), 0, 0);
            r.addView(t, weight1());
            box.addView(r, full());
        }
        LinearLayout.LayoutParams p = full();
        p.topMargin = dp(this, 18);
        body.addView(box, p);
    }

    private void bullet(LinearLayout list, int iconRes, String title, String sub) {
        LinearLayout r = row(this);
        r.setGravity(Gravity.TOP);
        r.setPadding(0, dp(this, 10), 0, dp(this, 10));
        r.addView(icon(this, iconRes, ACCENT_TEXT, 22));
        LinearLayout t = column(this);
        t.setPadding(dp(this, 14), 0, 0, 0);
        t.addView(title(this, title, 15));
        t.addView(text(this, sub, 14, MUTED));
        r.addView(t, weight1());
        list.addView(r, full());
    }

    private void statusLine(String what, boolean on) {
        LinearLayout r = row(this);
        r.setPadding(0, dp(this, 14), 0, 0);
        r.addView(dot(this, on ? OK : CARD2, 10));
        TextView t = text(this, what + (on ? ": allowed" : ": not yet"), 15, on ? TEXT : MUTED);
        t.setPadding(dp(this, 10), 0, 0, 0);
        r.addView(t);
        body.addView(r, full());
    }

    /** Shown after a first try at the accessibility switch on Android 13+. */
    private void restrictedHelp() {
        LinearLayout c = column(this);
        c.setPadding(dp(this, 16), dp(this, 14), dp(this, 16), dp(this, 14));
        c.setBackground(outlined(this, CARD, 16, Theme.withAlpha(ACCENT, 0x59), 1));
        c.addView(title(this, "Switch greyed out?", 15));
        TextView t = text(this, "1. Tap App info below.\n2. Tap ⋮ in the top right → Allow restricted settings.\n"
                + "3. Come back and open accessibility settings again.", 14, MUTED);
        t.setLineSpacing(0, 1.2f);
        t.setPadding(0, dp(this, 6), 0, 0);
        c.addView(t);
        LinearLayout.LayoutParams p = full();
        p.topMargin = dp(this, 16);
        body.addView(c, p);
    }

    private void stuck(String q1, String a1, String q2, String a2) {
        LinearLayout c = column(this);
        LinearLayout head = row(this);
        head.setPadding(0, dp(this, 10), 0, dp(this, 10));
        head.setBackgroundResource(ripple(this, false));
        TextView h = title(this, "Stuck?", 14);
        h.setTextColor(ACCENT_TEXT);
        head.addView(h, weight1());
        head.addView(icon(this, stuckOpen ? R.drawable.ms_expand_less : R.drawable.ms_expand_more, ACCENT_TEXT, 20));
        head.setOnClickListener(v -> {
            stuckOpen = !stuckOpen;
            render();
        });
        c.addView(head, full());
        if (stuckOpen) {
            qa(c, q1, a1);
            if (q2 != null) qa(c, q2, a2);
        }
        LinearLayout.LayoutParams p = full();
        p.topMargin = dp(this, 18);
        body.addView(c, p);
    }

    private void qa(LinearLayout c, String q, String a) {
        TextView qt = title(this, q, 14);
        qt.setPadding(0, dp(this, 6), 0, dp(this, 2));
        c.addView(qt);
        c.addView(text(this, a, 14, MUTED));
    }

    private View radio(boolean on) {
        FrameLayout f = new FrameLayout(this);
        f.setBackground(outlined(this, 0x00000000, 11, on ? ACCENT : MUTED, 2));
        if (on) {
            View in = new View(this);
            in.setBackground(oval(ACCENT));
            f.addView(in, new FrameLayout.LayoutParams(dp(this, 10), dp(this, 10), Gravity.CENTER));
        }
        f.setLayoutParams(new LinearLayout.LayoutParams(dp(this, 22), dp(this, 22)));
        return f;
    }

    private void primary(String label, View.OnClickListener l) {
        Button b = button(this, label, true);
        b.setOnClickListener(l);
        bottom.addView(b, bigButton());
    }

    private void secondary(String label, View.OnClickListener l) {
        Button b = button(this, label, false);
        b.setBackgroundResource(ripple(this, false));
        b.setTextColor(TEXT);
        b.setOnClickListener(l);
        LinearLayout.LayoutParams p = bigButton();
        p.topMargin = dp(this, 4);
        bottom.addView(b, p);
    }

    private LinearLayout.LayoutParams bigButton() {
        return new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(this, 52));
    }

    private String agentNames() {
        String a = Prefs.agents(this);
        return "both".equals(a) ? "Claude Code and Codex" : "codex".equals(a) ? "Codex" : "Claude Code";
    }

    interface Cmd {
        String get() throws Exception;
    }

    private void copy(Cmd cmd, String toast) {
        try {
            getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("Buddy setup", cmd.get()));
            Toast.makeText(this, toast, Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Toast.makeText(this, "Couldn't build the command: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }

    private String setupCommand() throws Exception {
        return TermuxSetup.command(this);
    }

    private void openTermux() {
        Intent t = getPackageManager().getLaunchIntentForPackage(Termux.PACKAGE);
        if (t != null) startActivity(t);
    }

    private void openAppInfo(String pkg) {
        startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + pkg)));
    }

    private void openUrl(String url) {
        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        render();
        checkAdvance();
    }
}
