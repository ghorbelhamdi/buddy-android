package app.buddy.assistant;

import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import static app.buddy.assistant.Ui.*;

/** Settings: setup checklist, appearance, agents and experience. */
public class SetupActivity extends Screen implements Account.Listener {
    private ScrollView scroll;
    private LinearLayout list;
    /** Setup card open state; null = automatic (open until all steps are done). */
    private Boolean setupOpen;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        build();
    }

    private void build() {
        LinearLayout root = column(this);
        root.setBackgroundColor(BG);
        root.setFitsSystemWindows(true);
        scroll = new ScrollView(this);
        list = column(this);
        list.setPadding(0, dp(this, 12), 0, dp(this, 24));
        scroll.addView(list);
        root.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        root.addView(bottomNav(this, NAV_SETTINGS, ChatHub.get(this).runningCount()), full());
        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (isFinishing()) return;
        Account.addListener(this);
        Account.CLAUDE.refresh(this);
        Account.CODEX.refresh(this);
        render();
    }

    @Override
    protected void onPause() {
        Account.removeListener(this);
        super.onPause();
    }

    @Override
    public void onAccountChanged() {
        if (!isFinishing() && !isDestroyed()) render();
    }

    /** Appearance changed on this screen: restyle everything in place, keeping the scroll position. */
    private void themeChanged() {
        Theme.changed(this);
        rethemed();
        int y = scroll.getScrollY();
        build();
        render();
        scroll.post(() -> scroll.scrollTo(0, y));
    }

    private static final class Step {
        String title, body, action, action2;
        Boolean done;
        View.OnClickListener l, l2;
    }

    private void render() {
        if (isFinishing() || isDestroyed()) return;
        list.removeAllViews();

        LinearLayout head = column(this);
        head.setPadding(dp(this, 20), dp(this, 8), dp(this, 20), dp(this, 4));
        head.addView(title(this, "Settings", 28));
        TextView sub = text(this, "Buddy runs Claude Code (or Codex) on your own subscription, inside the app.", 14, MUTED);
        sub.setPadding(0, dp(this, 4), 0, 0);
        head.addView(sub);
        list.addView(head, full());

        // ---------------------------------------------------------------- setup
        boolean a11y = SetupState.accessibilityEnabled(this);
        String backend = Prefs.backend(this);
        Account acc = Account.of(backend);
        Step[] steps = new Step[5];

        String a11yText = "This shows the bubble and lets Buddy read the screen, tap and type for you.";
        if (!a11y && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            a11yText += "\n\nSwitch greyed out (\"Restricted setting\")? Android blocks this for apps installed from a file. "
                    + "Tap App info, then ⋮ in the top right → Allow restricted settings, and try again.";
        }
        steps[0] = step("Turn on the Buddy accessibility service", a11yText, a11y,
                a11y ? null : "Open settings", v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)),
                a11y || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ? null : "App info", v -> openAppInfo(getPackageName()));

        boolean installed = SetupState.installed(this, backend);
        steps[1] = step("Install " + acc.name(), installed ? acc.name() + " is installed in Buddy."
                        : acc.step == Account.Step.INSTALLING ? acc.detail
                        : "Buddy downloads its own small Linux and " + acc.name() + (acc.claude()
                        ? " from Anthropic (about 235 MB)." : " from OpenAI (about 150 MB)."),
                installed, installed || acc.step == Account.Step.INSTALLING ? null : "Install", v -> openAgent(backend),
                null, null);

        boolean signed = SetupState.signedIn(this, backend);
        steps[2] = step("Sign in", signed ? "Signed in" + (acc.email.isEmpty() ? "." : " as " + acc.email + ".")
                        : (acc.claude() ? "With your Claude account (Pro or Max)." : "With your ChatGPT account.")
                        + " Your browser opens; approve there and Buddy comes back by itself.",
                installed ? signed : null, installed && !signed ? "Sign in" : null, v -> openAgent(backend), null, null);

        PowerManager pm = getSystemService(PowerManager.class);
        boolean buddyBg = pm.isIgnoringBatteryOptimizations(getPackageName());
        steps[3] = step("Let Buddy run in the background", buddyBg ? "Allowed."
                        : "So your phone doesn't stop Buddy to save battery while the agent works.",
                buddyBg, buddyBg ? null : "Allow", v -> startActivity(new Intent(
                        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + getPackageName()))),
                null, null);

        boolean replied = Prefs.hadReply(this);
        steps[4] = step("Try it", replied
                        ? "Buddy has answered you. Tap the bubble anytime to ask for more."
                        : a11y && signed
                        ? "Tap the bubble on the edge of your screen and ask something like \"open Messenger\", or start a chat here."
                        : "Finish the steps above first.",
                replied ? Boolean.TRUE : null, null, null, null, null);
        setupCard(steps);

        // ----------------------------------------------------------- appearance
        list.addView(sectionLabel(this, "Appearance"));
        LinearLayout ap = card();
        ap.setPadding(dp(this, 16), dp(this, 14), dp(this, 16), dp(this, 16));
        ap.addView(fieldLabel("Mode", null));
        ap.addView(segmented(this, Theme.MODES, Prefs.themeMode(this), false, v -> {
            Prefs.setThemeMode(this, v);
            themeChanged();
        }), topGap(8));
        ap.addView(fieldLabel("Accent", Theme.hex(Prefs.accent(this))), topGap(18));
        ap.addView(swatches(), topGap(10));
        ap.addView(fieldLabel("Surface", null), topGap(18));
        ap.addView(segmented(this, Theme.SURFACES, Prefs.surface(this), false, v -> {
            Prefs.setSurface(this, v);
            themeChanged();
        }), topGap(8));
        TextView sn = text(this, "Warm is Buddy's original tone. Black (true black) applies in dark mode only.", 13, MUTED);
        ap.addView(sn, topGap(8));
        addCard(ap);

        // ---------------------------------------------------------------- agent
        list.addView(sectionLabel(this, "Agent"));
        LinearLayout ag = card();
        ag.setPadding(dp(this, 16), dp(this, 14), dp(this, 16), dp(this, 16));
        ag.addView(title(this, "Default brain", 15));
        TextView brainInfo = text(this, "Who answers new chats from the bubble or the New chat button. Claude Code uses "
                + "your Claude plan; Codex uses your ChatGPT plan.", 13, MUTED);
        brainInfo.setPadding(0, dp(this, 2), 0, 0);
        ag.addView(brainInfo);
        ag.addView(segmented(this, new String[][]{{"claude", "Claude Code"}, {"codex", "Codex"}}, backend, false, v -> {
            Prefs.setBackend(this, v);
            Toast.makeText(this, "New chats will use " + ("codex".equals(v) ? "Codex" : "Claude Code")
                    + ". You can pick per chat with the New chat button.", Toast.LENGTH_LONG).show();
            render();
        }), topGap(12));
        for (String a : new String[]{"claude", "codex"}) ag.addView(agentRow(a), topGap(a.equals("claude") ? 12 : 4));
        addCard(ag);

        // chats from the Termux days: their conversations stayed in Termux, so agents can't continue them
        final java.util.List<Sessions.Session> old = termuxChats();
        if (!old.isEmpty()) {
            LinearLayout oc = card();
            LinearLayout r = listRow(this);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPadding(dp(this, 16), dp(this, 14), dp(this, 12), dp(this, 14));
            LinearLayout t = column(this);
            t.addView(title(this, "Remove old Termux chats", 15));
            t.addView(text(this, old.size() + (old.size() == 1 ? " chat" : " chats")
                    + " from before Buddy had its own Linux. Agents can't continue them.", 13, MUTED));
            r.addView(t, weight1());
            r.addView(icon(this, R.drawable.ms_chevron_right, MUTED, 22));
            r.setOnClickListener(v -> confirmSheet(this, "Remove " + old.size() + (old.size() == 1 ? " old chat?" : " old chats?"),
                    "Removes them from Buddy's chat list. This can't be undone.", "Remove", true, () -> {
                        ChatHub hub = ChatHub.get(this);
                        for (Sessions.Session s : old) hub.delete(s);
                        Toast.makeText(this, "Removed.", Toast.LENGTH_SHORT).show();
                        render();
                    }));
            oc.addView(r, full());
            addCard(oc);
        }

        // ----------------------------------------------------------- experience
        list.addView(sectionLabel(this, "Experience"));
        LinearLayout ex = card();
        ex.addView(switchRow(this, "Chat bubble", "Floats over other apps. Drag it onto the ✕ to hide it; "
                + "approval cards still pop up when an agent needs your OK.", Prefs.bubbleEnabled(this), on -> {
            Prefs.setBubbleEnabled(this, on);
            if (on) HiddenNotice.cancel(this);
            BuddyService svc = BuddyService.get();
            if (svc != null) svc.applyBubbleSetting();
        }), full());
        ex.addView(divider(), lineParams());
        ex.addView(switchRow(this, "Read replies aloud", "Talk with the mic in any chat; it sends when you stop speaking.",
                Prefs.speakReplies(this), on -> {
                    Prefs.setSpeakReplies(this, on);
                    if (!on) Voice.stopSpeaking();
                }), full());
        ex.addView(divider(), lineParams());
        final boolean dev = Prefs.devMode(this);
        ex.addView(switchRow(this, "Developer mode", "The bubble's agent may run commands and edit files in "
                + "~/.buddy/work, so it can build and install apps.", dev, on -> {
            Prefs.setDevMode(this, on);
            render();
        }), full());
        if (dev) {
            LinearLayout warn = row(this);
            warn.setGravity(Gravity.TOP);
            warn.setPadding(dp(this, 12), dp(this, 10), dp(this, 12), dp(this, 10));
            warn.setBackground(rounded(this, ERR_SOFT, 12));
            warn.addView(icon(this, R.drawable.ms_warning, ERR, 18));
            TextView wt = text(this, "Leave it off for everyday use: with it on, a malicious message or web page on screen "
                    + "could try to trick the agent into running commands. Approval cards still guard sending, posting, "
                    + "buying and deleting.", 13, ERR);
            wt.setPadding(dp(this, 10), 0, 0, 0);
            warn.addView(wt, weight1());
            LinearLayout.LayoutParams wp = full();
            wp.setMargins(dp(this, 12), 0, dp(this, 12), dp(this, 12));
            ex.addView(warn, wp);
        }
        addCard(ex);

        TextView ver = monoText(this, "buddy " + BuildInfo.VERSION + " · " + getPackageName(), 12, MUTED);
        ver.setGravity(Gravity.CENTER);
        ver.setPadding(0, dp(this, 24), 0, 0);
        list.addView(ver, full());
    }

    // ------------------------------------------------------------- setup card

    private Step step(String title, String body, Boolean done, String action, View.OnClickListener l,
                      String action2, View.OnClickListener l2) {
        Step s = new Step();
        s.title = title;
        s.body = body;
        s.done = done;
        s.action = action;
        s.l = l;
        s.action2 = action2;
        s.l2 = l2;
        return s;
    }

    private void setupCard(Step[] steps) {
        int done = 0, next = -1;
        for (int i = 0; i < steps.length; i++) {
            if (Boolean.TRUE.equals(steps[i].done)) done++;
            else if (next < 0) next = i;
        }
        boolean complete = next < 0;
        boolean open = setupOpen != null ? setupOpen : !complete;

        LinearLayout c = card();
        c.setPadding(dp(this, 16), dp(this, 14), dp(this, 16), dp(this, 14));
        LinearLayout head = row(this);
        head.addView(title(this, "Setup", 17));
        TextView n = monoText(this, done + "/5", 12, complete ? OK : ACCENT_TEXT);
        n.setTypeface(mono(this, 600));
        n.setPadding(dp(this, 8), dp(this, 2), 0, 0);
        head.addView(n, weight1());
        head.addView(icon(this, open ? R.drawable.ms_expand_less : R.drawable.ms_expand_more, MUTED, 22));
        head.setOnClickListener(v -> {
            setupOpen = !open;
            render();
        });
        c.addView(head, full());

        FrameLayout bar = new FrameLayout(this);
        bar.setBackground(rounded(this, CARD2, 3));
        View fill = new View(this);
        fill.setBackground(rounded(this, complete ? OK : ACCENT, 3));
        bar.addView(fill, new FrameLayout.LayoutParams(0, FrameLayout.LayoutParams.MATCH_PARENT));
        final int doneF = done;
        bar.post(() -> {
            FrameLayout.LayoutParams fp = (FrameLayout.LayoutParams) fill.getLayoutParams();
            fp.width = bar.getWidth() * doneF / 5;
            fill.setLayoutParams(fp);
        });
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(this, 6));
        bp.topMargin = dp(this, 10);
        c.addView(bar, bp);

        if (!complete) {
            TextView guide = text(this, "Open the step-by-step guide", 14, ACCENT_TEXT);
            guide.setTypeface(sans(this, 600));
            guide.setPadding(0, dp(this, 12), 0, 0);
            guide.setOnClickListener(v -> startActivity(new Intent(this, OnboardingActivity.class)));
            c.addView(guide);
        }
        if (!open) {
            TextView s = text(this, complete ? "All set. Buddy is ready." : "Next: " + steps[next].title, 13, MUTED);
            s.setPadding(0, dp(this, 10), 0, 0);
            c.addView(single(s));
        } else {
            for (int i = 0; i < steps.length; i++) {
                Step st = steps[i];
                boolean isDone = Boolean.TRUE.equals(st.done), isNext = i == next;
                LinearLayout r = row(this);
                r.setGravity(Gravity.TOP);
                r.setPadding(0, dp(this, 14), 0, 0);
                FrameLayout circle = new FrameLayout(this);
                if (isDone) {
                    circle.setBackground(oval(OK));
                    ImageView ck = icon(this, R.drawable.ms_check, 0xFFFFFFFF, 16);
                    circle.addView(ck, new FrameLayout.LayoutParams(dp(this, 16), dp(this, 16), Gravity.CENTER));
                } else {
                    circle.setBackground(outlined(this, 0x00000000, 12, isNext ? ACCENT : CARD2, 2));
                    TextView num = monoText(this, String.valueOf(i + 1), 12, isNext ? ACCENT_TEXT : MUTED);
                    num.setTypeface(mono(this, 600));
                    num.setGravity(Gravity.CENTER);
                    circle.addView(num, new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT));
                }
                r.addView(circle, new LinearLayout.LayoutParams(dp(this, 24), dp(this, 24)));
                LinearLayout col = column(this);
                col.setPadding(dp(this, 12), dp(this, 2), 0, 0);
                TextView t = title(this, st.title, 15);
                if (!isDone && !isNext) t.setTextColor(MUTED);
                col.addView(t);
                if (isNext) {
                    TextView b = text(this, st.body, 14, MUTED);
                    b.setPadding(0, dp(this, 4), 0, 0);
                    col.addView(b);
                    if (st.action != null || st.action2 != null) {
                        LinearLayout btns = row(this);
                        if (st.action != null) {
                            Button a = button(this, st.action, true);
                            a.setOnClickListener(st.l);
                            btns.addView(a, wrap());
                        }
                        if (st.action2 != null) {
                            Button a2 = button(this, st.action2, false);
                            a2.setOnClickListener(st.l2);
                            LinearLayout.LayoutParams a2p = wrap();
                            if (st.action != null) {
                                btns.setOrientation(LinearLayout.VERTICAL); // two buttons side by side don't fit
                                btns.setGravity(Gravity.START);
                                a2p.topMargin = dp(this, 8);
                            }
                            btns.addView(a2, a2p);
                        }
                        LinearLayout.LayoutParams bt = full();
                        bt.topMargin = dp(this, 10);
                        col.addView(btns, bt);
                    }
                }
                r.addView(col, weight1());
                c.addView(r, full());
            }
        }
        LinearLayout.LayoutParams p = full();
        p.setMargins(dp(this, 16), dp(this, 14), dp(this, 16), 0);
        list.addView(c, p);
    }

    // ----------------------------------------------------------- appearance

    private View fieldLabel(String s, String right) {
        LinearLayout r = row(this);
        r.addView(title(this, s, 15), weight1());
        if (right != null) r.addView(monoText(this, right, 12, MUTED));
        return r;
    }

    private View swatches() {
        LinearLayout r = row(this);
        int current = Prefs.accent(this);
        boolean preset = false;
        for (String[] a : Theme.ACCENTS) {
            int col = Theme.parse(a[0], 0xFFD97757);
            boolean on = col == current;
            preset |= on;
            r.addView(swatch(col, on, 0, a[1], v -> {
                Prefs.setAccent(this, col);
                themeChanged();
            }), new LinearLayout.LayoutParams(0, dp(this, 40), 1));
        }
        r.addView(swatch(preset ? CARD2 : current, !preset, preset ? R.drawable.ms_add : 0, "Custom colour",
                v -> customAccent()), new LinearLayout.LayoutParams(0, dp(this, 40), 1));
        return r;
    }

    private View swatch(int color, boolean on, int iconRes, String desc, View.OnClickListener l) {
        FrameLayout f = new FrameLayout(this);
        f.setContentDescription(desc);
        FrameLayout ring = new FrameLayout(this);
        if (on) ring.setBackground(outlined(this, 0x00000000, 20, TEXT, 2));
        View dotV = new View(this);
        dotV.setBackground(oval(color));
        ring.addView(dotV, new FrameLayout.LayoutParams(dp(this, 30), dp(this, 30), Gravity.CENTER));
        if (iconRes != 0) {
            ImageView ic = icon(this, iconRes, MUTED, 18);
            ring.addView(ic, new FrameLayout.LayoutParams(dp(this, 18), dp(this, 18), Gravity.CENTER));
        }
        f.addView(ring, new FrameLayout.LayoutParams(dp(this, 40), dp(this, 40), Gravity.CENTER));
        f.setOnClickListener(l);
        return f;
    }

    private void customAccent() {
        LinearLayout c = column(this);
        c.addView(title(this, "Custom accent", 18));
        TextView s = text(this, "Any colour as a hex code, like #4F8BFF.", 13, MUTED);
        s.setPadding(0, dp(this, 2), 0, dp(this, 12));
        c.addView(s);
        LinearLayout r = row(this);
        final View preview = new View(this);
        preview.setBackground(oval(Prefs.accent(this)));
        r.addView(preview, new LinearLayout.LayoutParams(dp(this, 40), dp(this, 40)));
        final EditText et = new EditText(this);
        et.setText(Theme.hex(Prefs.accent(this)));
        et.setTextColor(TEXT);
        et.setTypeface(mono(this, 400));
        et.setTextSize(15);
        et.setSingleLine(true);
        et.setPadding(dp(this, 12), dp(this, 10), dp(this, 12), dp(this, 10));
        et.setBackground(outlined(this, BG, 12, OUTLINE, 1));
        LinearLayout.LayoutParams ep = weight1();
        ep.leftMargin = dp(this, 12);
        r.addView(et, ep);
        Button apply = button(this, "Apply", true);
        LinearLayout.LayoutParams app = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(this, 44));
        app.leftMargin = dp(this, 8);
        r.addView(apply, app);
        c.addView(r, full());
        et.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence x, int a, int b, int d) {
            }

            @Override
            public void onTextChanged(CharSequence x, int a, int b, int d) {
            }

            @Override
            public void afterTextChanged(Editable e) {
                String h = e.toString().trim();
                boolean ok = h.replace("#", "").length() == 6;
                int col = ok ? Theme.parse(h, 0) : 0;
                ok &= col != 0;
                preview.setBackground(oval(ok ? col : CARD2));
                apply.setEnabled(ok);
                apply.setAlpha(ok ? 1f : 0.4f);
            }
        });
        final Dialog d = bottomSheet(this, c);
        apply.setOnClickListener(v -> {
            int col = Theme.parse(et.getText().toString().trim(), 0);
            if (col == 0) return;
            d.dismiss();
            Prefs.setAccent(this, col);
            themeChanged();
        });
        d.show();
    }

    // ---------------------------------------------------------------- helpers

    private LinearLayout card() {
        LinearLayout c = column(this);
        c.setBackground(rounded(this, CARD, 16));
        return c;
    }

    private void addCard(View c) {
        LinearLayout.LayoutParams p = full();
        p.setMargins(dp(this, 16), dp(this, 6), dp(this, 16), 0);
        list.addView(c, p);
    }

    private View divider() {
        View v = new View(this);
        v.setBackgroundColor(LINE);
        return v;
    }

    private LinearLayout.LayoutParams lineParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(this, 1));
        p.leftMargin = p.rightMargin = dp(this, 16);
        return p;
    }

    private LinearLayout.LayoutParams topGap(int dpv) {
        LinearLayout.LayoutParams p = full();
        p.topMargin = dp(this, dpv);
        return p;
    }

    /** Chats with messages but no conversation folder in Buddy's Linux: they ran in Termux. */
    private java.util.List<Sessions.Session> termuxChats() {
        java.util.List<Sessions.Session> out = new java.util.ArrayList<>();
        if (!Linux.installed(this)) return out;
        java.io.File dir = new java.io.File(Linux.root(this), "root/.buddy/sessions");
        for (Sessions.Session s : ChatHub.get(this).sessions.all) {
            if (!s.messages.isEmpty() && !s.running && !new java.io.File(dir, s.id).isDirectory()) out.add(s);
        }
        return out;
    }

    private void openAgent(String agent) {
        startActivity(new Intent(this, SignInActivity.class).putExtra("agent", agent));
    }

    /** An agent with its state (not installed / signed out / signed in); tap to install or sign in. */
    private View agentRow(String agent) {
        Account a = Account.of(agent);
        String st = Prefs.agentState(this, agent);
        boolean busy = a.step == Account.Step.INSTALLING;
        String state = busy ? "installing…" : !Linux.installed(this) || st == null || "NEED_INSTALL".equals(st) ? "not installed"
                : "SIGNED_IN".equals(st) ? "signed in" + (a.email.isEmpty() ? "" : " · " + a.email) : "not signed in";
        LinearLayout r = row(this);
        r.setPadding(0, dp(this, 10), 0, dp(this, 10));
        r.setBackgroundResource(ripple(this, false));
        r.addView(agentTile(this, agent));
        LinearLayout t = column(this);
        t.setPadding(dp(this, 12), 0, dp(this, 8), 0);
        t.addView(title(this, a.name(), 15));
        t.addView(single(monoText(this, state, 12, "SIGNED_IN".equals(st) && !busy ? OK : MUTED)));
        r.addView(t, weight1());
        r.addView(icon(this, R.drawable.ms_chevron_right, MUTED, 22));
        r.setOnClickListener(v -> openAgent(agent));
        return r;
    }

    private void openAppInfo(String pkg) {
        startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + pkg)));
    }

    private void openUrl(String url) {
        startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)));
    }
}
