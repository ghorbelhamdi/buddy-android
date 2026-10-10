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
 * First-run guide: one step per screen. Steps that happen elsewhere (Android settings, the browser for
 * sign-in) are detected when the user comes back.
 */
public class OnboardingActivity extends Screen implements Account.Listener {
    static final int WELCOME = 0, BRAIN = 1, ACCESS = 2, AGENTS = 3, BACKGROUND = 4, TRY = 5;
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
        Account.addListener(this);
        for (String a : wanted(this)) Account.of(a).refresh(this);
        render();
        checkAdvance();
    }

    @Override
    protected void onPause() {
        Account.removeListener(this);
        super.onPause();
    }

    @Override
    public void onAccountChanged() {
        if (isFinishing() || isDestroyed()) return;
        render();
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

    static boolean done(Context c, int step) {
        switch (step) {
            case ACCESS:
                return SetupState.accessibilityEnabled(c);
            case AGENTS:
                for (String a : wanted(c)) if (!SetupState.signedIn(c, a)) return false;
                return true;
            case BACKGROUND:
                return c.getSystemService(PowerManager.class).isIgnoringBatteryOptimizations(c.getPackageName());
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

    private void finishGuide() {
        Prefs.setOnboarded(this, true);
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
        primary("Continue", v -> go(ACCESS));
    }

    private void agents(boolean ok) {
        header(R.drawable.ms_terminal, ok ? "Ready" : "Install and sign in",
                ok ? agentNames() + " is installed and signed in." : "Buddy installs " + agentNames()
                        + " inside the app, then you sign in once. No Termux needed.", ok);
        boolean first = true;
        for (String a : wanted(this)) {
            Account acc = Account.of(a);
            boolean installed = SetupState.installed(this, a), signed = SetupState.signedIn(this, a);
            boolean installing = acc.step == Account.Step.INSTALLING;
            LinearLayout card = column(this);
            card.setPadding(dp(this, 16), dp(this, 14), dp(this, 16), dp(this, 14));
            card.setBackground(rounded(this, CARD, 16));
            LinearLayout r = row(this);
            r.addView(agentTile(this, a));
            LinearLayout t = column(this);
            t.setPadding(dp(this, 12), 0, 0, 0);
            t.addView(title(this, acc.name(), 15));
            t.addView(single(monoText(this, signed ? "signed in" + (acc.email.isEmpty() ? "" : " · " + acc.email)
                    : installing ? "installing…" : installed ? "installed · not signed in" : "not installed", 12,
                    signed ? OK : MUTED)));
            r.addView(t, weight1());
            if (signed) r.addView(icon(this, R.drawable.ms_check, OK, 22));
            else if (installing) r.addView(spinner(this, 20));
            card.addView(r, full());
            if (!signed) {
                TextView how = text(this, installing ? acc.detail
                        : acc.step == Account.Step.ERROR ? acc.detail
                        : installed ? (acc.claude() ? "Sign in with your Claude account. Your browser opens; tap Authorize "
                        + "and Buddy comes back by itself." : "Sign in with your ChatGPT account in the browser, then come back.")
                        : (acc.claude() ? "Downloads about 235 MB once (Buddy's Linux and Claude Code from Anthropic)."
                        : "Downloads about 150 MB once (Buddy's Linux, Node.js and Codex from OpenAI)."), 14,
                        acc.step == Account.Step.ERROR ? ERR : MUTED);
                how.setPadding(0, dp(this, 10), 0, installing ? 0 : dp(this, 10));
                card.addView(how);
                if (!installing) {
                    Button b = button(this, installed ? (acc.claude() ? "Sign in with Claude" : "Sign in with ChatGPT")
                            : "Install " + acc.name(), first);
                    b.setOnClickListener(v -> {
                        if (installed) startActivity(new Intent(this, SignInActivity.class).putExtra("agent", a));
                        else acc.install(this);
                    });
                    card.addView(b, wrap());
                    first = false;
                }
            }
            LinearLayout.LayoutParams p = full();
            p.topMargin = dp(this, 12);
            body.addView(card, p);
        }
        if (!ok) {
            note("Use Wi-Fi if you can. You can leave this screen while it installs.");
            stuck("Sign-in didn't come back?", "Claude: on the Claude page tap Copy Code, then come back to Buddy; "
                            + "it picks the code up from the clipboard.",
                    "Install failed?", "Check your connection and tap Install again; it continues where it stopped.");
        }
    }

    private void background(boolean ok) {
        header(R.drawable.ms_build_circle, "Keep it running", ok ? "Buddy can run in the background." :
                "So your phone doesn't stop Buddy to save battery while the agent works.", ok);
        if (!ok) {
            primary("Allow", v -> startActivity(new Intent(
                    Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName()))));
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
