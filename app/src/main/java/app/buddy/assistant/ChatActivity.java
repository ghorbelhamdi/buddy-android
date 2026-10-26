package app.buddy.assistant;

import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import static app.buddy.assistant.Ui.*;

/** One chat, full screen. */
public class ChatActivity extends Screen implements ChatHub.Listener, Browser.Listener {
    private ChatHub hub;
    private Sessions.Session ses;
    private LinearLayout messages, strip, approval;
    private ScrollView scroll;
    private TextView title, chipText, stripText;
    /** The Linux tools chip beside the model chip (none in the Play build). */
    private LinearLayout linux;
    /** The empty-chat hint, while the chat has no messages. */
    private TextView hint;
    private Button mic;
    private LinearLayout pill;
    private VoiceBar voiceBar;
    /** In place of the composer while this chat has a live voice call (see CodexLive). */
    private VoiceBar liveBar;
    /** Starts live voice (Codex chats); the one-time hint about it above the composer. */
    private ImageButton live;
    private TextView liveHint;
    private ImageButton speaker, send;
    /** Chat options: long-task mode, the schedule and "Allowed without asking"; accent while any is on. */
    private ImageButton options;
    private EditText input;
    private int maxBubble;
    private ToolLog log; // the tool-call group being appended to, if the last message was a step
    private final java.util.List<String> pending = new java.util.ArrayList<>(); // attachments for the next message
    private android.widget.HorizontalScrollView trayScroll;
    private LinearLayout tray;
    private static final int REQ_CAMERA = 21, REQ_PHOTOS = 22, REQ_FILES = 23;
    /** The background browser's live card, while this chat has a tab there. */
    private BrowserPanel browser;
    private boolean resumed;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        hub = ChatHub.get(this);
        ses = hub.sessions.find(getIntent().getStringExtra("id"));
        if (ses == null) {
            finish();
            return;
        }
        maxBubble = (int) (getResources().getDisplayMetrics().widthPixels * 0.82f);

        LinearLayout root = column(this);
        root.setBackgroundColor(BG);

        // app bar
        LinearLayout top = row(this);
        top.setPaddingRelative(dp(this, 4), dp(this, 4), dp(this, 4), dp(this, 4));
        ImageButton back = iconButton(this, R.drawable.ms_arrow_back, getString(R.string.chat_back));
        back.setLayoutParams(new LinearLayout.LayoutParams(dp(this, 44), dp(this, 44)));
        back.setOnClickListener(v -> finish());
        top.addView(back);
        LinearLayout titles = column(this);
        titles.setPaddingRelative(dp(this, 4), 0, dp(this, 4), 0);
        title = single(title(this, ses.label(), 16));
        titles.addView(title);
        // the model chip, then Linux tools: in a row only as wide as its chips, the weight just lets the model chip
        // shrink (its text cut short) when a narrow screen has no room for both
        LinearLayout chips = row(this);
        LinearLayout chip = chip(this, "");
        chipText = (TextView) chip.getChildAt(0);
        chip.setOnClickListener(v -> chooseModel());
        chips.addView(chip, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        if (!Flavor.PLAY) {
            linux = protect(linuxChip(this)); // agents never switch it, even when they may tap Buddy's screens
            linux.setOnClickListener(v -> switchLinuxTools());
            LinearLayout.LayoutParams lp = wrap();
            lp.setMarginStart(dp(this, 6));
            chips.addView(linux, lp);
        }
        LinearLayout.LayoutParams cp = wrap();
        cp.topMargin = dp(this, 3);
        titles.addView(chips, cp);
        top.addView(titles, weight1());
        // agents never change these, even when they may tap Buddy's screens
        options = protect(iconButton(this, R.drawable.ms_tune, getString(R.string.chat_options), MUTED));
        options.setLayoutParams(new LinearLayout.LayoutParams(dp(this, 44), dp(this, 44)));
        options.setOnClickListener(v -> chatOptions());
        top.addView(options);
        speaker = iconButton(this, R.drawable.ms_volume_off, getString(R.string.chat_read_aloud), MUTED);
        speaker.setLayoutParams(new LinearLayout.LayoutParams(dp(this, 44), dp(this, 44)));
        speaker.setOnClickListener(v -> {
            Prefs.setSpeakReplies(this, !Prefs.speakReplies(this));
            if (!Prefs.speakReplies(this)) Voice.stopSpeaking();
            Toast.makeText(this, Prefs.speakReplies(this) ? R.string.chat_read_aloud_on : R.string.chat_read_aloud_off,
                    Toast.LENGTH_SHORT).show();
            refresh();
        });
        top.addView(speaker);
        root.addView(top, full());
        root.addView(divider(), new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(this, 1)));

        // running strip
        strip = row(this);
        strip.setPaddingRelative(dp(this, 12), dp(this, 6), dp(this, 6), dp(this, 6));
        strip.setBackground(rounded(this, CARD, 12));
        ProgressBar sp = spinner(this, 16);
        strip.addView(sp);
        stripText = single(monoText(this, "", 12, ACCENT_TEXT));
        stripText.setPaddingRelative(dp(this, 10), 0, dp(this, 8), 0);
        strip.addView(stripText, weight1());
        Button stop = button(this, getString(R.string.chat_stop), false);
        stop.setTextColor(ERR);
        stop.setBackground(rounded(this, ERR_SOFT, 10));
        stop.setMinHeight(dp(this, 34));
        stop.setMinimumHeight(dp(this, 34));
        stop.setPaddingRelative(dp(this, 14), 0, dp(this, 14), 0);
        stop.setOnClickListener(v -> hub.stop(ses));
        strip.addView(stop, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(this, 34)));
        LinearLayout.LayoutParams stp = full();
        stp.leftMargin = stp.rightMargin = dp(this, 12);
        stp.topMargin = dp(this, 8);
        root.addView(strip, stp);

        browser = new BrowserPanel(this, ses.id, false);
        browser.setVisibility(View.GONE);
        LinearLayout.LayoutParams bwp = full();
        bwp.leftMargin = bwp.rightMargin = dp(this, 12);
        bwp.topMargin = dp(this, 8);
        root.addView(browser, bwp);

        scroll = new ScrollView(this);
        messages = column(this);
        messages.setPaddingRelative(dp(this, 16), dp(this, 8), dp(this, 16), dp(this, 12));
        scroll.addView(messages);
        root.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        // inline approval card (when an agent asks for an OK)
        approval = protect(column(this)); // the inline approval card: only the user answers it
        LinearLayout.LayoutParams ap = full();
        ap.leftMargin = ap.rightMargin = dp(this, 12);
        ap.bottomMargin = dp(this, 8);
        root.addView(approval, ap);

        // composer
        trayScroll = new android.widget.HorizontalScrollView(this);
        trayScroll.setHorizontalScrollBarEnabled(false);
        tray = row(this);
        tray.setPaddingRelative(dp(this, 12), 0, dp(this, 12), dp(this, 6));
        trayScroll.addView(tray);
        trayScroll.setVisibility(View.GONE);
        root.addView(trayScroll, full());

        pill = row(this);
        pill.setPaddingRelative(dp(this, 4), dp(this, 4), dp(this, 4), dp(this, 4));
        ImageButton attach = iconButton(this, R.drawable.ms_attach_file, getString(R.string.chat_attach_description), MUTED);
        attach.setLayoutParams(new LinearLayout.LayoutParams(dp(this, 40), dp(this, 40)));
        attach.setOnClickListener(v -> attachSheet());
        pill.addView(attach);
        pill.setBackground(outlined(this, CARD, 26, OUTLINE, 1));
        RichEditText field = new RichEditText(this);
        field.onImages(this::addFiles); // pasted, or from the keyboard's clipboard (issue #6)
        input = field;
        input.setHint(getString(R.string.chat_input_hint, ses.brainName()));
        input.setHintTextColor(MUTED);
        input.setTextColor(TEXT);
        input.setTextSize(15);
        input.setTypeface(sans(this, 400));
        input.setMaxLines(6);
        input.setBackground(null);
        input.setPaddingRelative(0, dp(this, 10), 0, dp(this, 10));
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_AUTO_CORRECT);
        pill.addView(input, weight1());
        live = iconButton(this, R.drawable.ms_graphic_eq, getString(R.string.live_option), MUTED);
        live.setLayoutParams(new LinearLayout.LayoutParams(dp(this, 40), dp(this, 40)));
        live.setOnClickListener(v -> startLive());
        live.setVisibility(CodexLive.available(this, ses) ? View.VISIBLE : View.GONE);
        pill.addView(live);
        mic = button(this, "", false);
        mic.setContentDescription(getString(R.string.chat_talk));
        mic.setOnClickListener(v -> toggleMic());
        mic.setOnLongClickListener(v -> {
            languageSheet();
            return true;
        });
        pill.addView(mic, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(this, 40)));
        micIdle();
        send = new ImageButton(this);
        send.setImageResource(R.drawable.ms_arrow_upward);
        send.setContentDescription(getString(R.string.chat_send));
        send.setScaleType(ImageView.ScaleType.CENTER);
        send.setOnClickListener(v -> send());
        LinearLayout.LayoutParams snp = new LinearLayout.LayoutParams(dp(this, 40), dp(this, 40));
        snp.setMarginStart(dp(this, 4));
        pill.addView(send, snp);
        input.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(Editable e) {
                paintSend();
            }
        });
        paintSend();
        LinearLayout.LayoutParams pp = full();
        pp.leftMargin = pp.rightMargin = dp(this, 12);
        pp.bottomMargin = dp(this, 10);
        pp.topMargin = dp(this, 4);
        liveHint = text(this, getString(R.string.live_hint), 13, ACCENT_TEXT);
        liveHint.setBackground(rounded(this, ACCENT_SOFT, 12));
        liveHint.setPaddingRelative(dp(this, 12), dp(this, 6), dp(this, 12), dp(this, 6));
        liveHint.setOnClickListener(v -> liveHint.setVisibility(View.GONE));
        liveHint.setVisibility(View.GONE);
        LinearLayout.LayoutParams hp = wrap();
        hp.gravity = Gravity.END;
        hp.setMarginEnd(dp(this, 16));
        root.addView(liveHint, hp);
        root.addView(pill, pp);
        voiceBar = new VoiceBar(this, new VoiceBar.Actions() {
            @Override
            public void cancel() {
                Voice.stopListening();
            }

            @Override
            public void send() {
                Voice.finishListening();
            }
        });
        voiceBar.setVisibility(View.GONE);
        root.addView(voiceBar, pp);
        liveBar = new VoiceBar(this, new VoiceBar.Actions() {
            @Override
            public void cancel() {
                CodexLive.end(null);
            }

            @Override
            public void send() {
            }
        }, true);
        liveBar.setVisibility(View.GONE);
        LinearLayout.LayoutParams lbp = full();
        lbp.leftMargin = lbp.rightMargin = dp(this, 12);
        lbp.bottomMargin = dp(this, 10);
        lbp.topMargin = dp(this, 4);
        root.addView(liveBar, lbp);

        // the outer frame takes the system-bar insets
        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(BG);
        frame.setFitsSystemWindows(true);
        frame.addView(root);
        setContentView(frame);

        showAll();
        String draft = getIntent().getStringExtra("draft");
        if (draft != null && state == null) {
            input.setText(draft);
            input.setSelection(draft.length());
        }
        // from the bubble's attach button, or images pasted there (already copied into Buddy's storage)
        if (state == null && getIntent().getBooleanExtra("attach", false)) input.post(this::attachSheet);
        java.util.ArrayList<String> files = getIntent().getStringArrayListExtra("files");
        if (state == null && files != null) {
            pending.addAll(files);
            renderTray();
        }
    }

    /** The chat's messages from scratch. */
    private void showAll() {
        messages.removeAllViews();
        log = null;
        hint = null;
        if (ses.messages.isEmpty()) {
            hint = text(this, hintText(), 14, MUTED);
            hint.setGravity(Gravity.CENTER);
            hint.setPaddingRelative(dp(this, 24), dp(this, 40), dp(this, 24), 0);
            messages.addView(hint, full());
        }
        for (Sessions.Msg m : ses.messages) show(m);
        if (log != null && !ses.running) log.finish();
        lastShown = lastMsg();
    }

    /** The empty-chat hint mentions Linux tools when the chat has them. */
    private String hintText() {
        return getString(ses.dev ? R.string.chat_empty_hint_dev : R.string.chat_empty_hint, ses.brainName());
    }

    /** The last message shown here: if the chat moved on while this screen was hidden, it's redrawn on return. */
    private Sessions.Msg lastShown;

    private Sessions.Msg lastMsg() {
        return ses.messages.isEmpty() ? null : ses.messages.get(ses.messages.size() - 1);
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (ses == null || isFinishing()) return;
        hub.addListener(this);
        resumed = true;
        Browser.addListener(this);
        Waiting.listener = waitingChanged;
        hub.setViewing(ses, true);
        hub.select(ses);
        Voice.prepare(this); // so the mic is quick
        CodexLive.addUi(liveUi);
        boolean canLive = CodexLive.available(this, ses);
        live.setVisibility(canLive ? View.VISIBLE : View.GONE);
        if (canLive && !Prefs.liveHintShown(this)) { // once: the first Codex chat opened with live voice
            Prefs.setLiveHintShown(this);
            liveHint.setVisibility(View.VISIBLE);
            liveHint.postDelayed(() -> liveHint.setVisibility(View.GONE), 10_000);
        }
        if (lastMsg() != lastShown) showAll(); // messages came in while this screen was hidden
        refresh();
        scrollToEnd();
    }

    @Override
    protected void onPause() {
        if (Voice.listening()) Voice.stopListening();
        // a live call goes on (the agent works in other apps): it ends with End, the screen off or a pause
        CodexLive.removeUi(liveUi);
        if (ses != null) {
            hub.setViewing(ses, false);
            hub.removeListener(this);
        }
        resumed = false;
        Browser.removeListener(this);
        if (browser != null) browser.setShown(false);
        if (Waiting.listener == waitingChanged) Waiting.listener = null;
        super.onPause();
    }

    private View divider() {
        View v = new View(this);
        v.setBackgroundColor(LINE);
        return v;
    }

    private void paintSend() {
        boolean has = input.getText().toString().trim().length() > 0 || !pending.isEmpty();
        send.setBackground(oval(has ? ACCENT : CARD2));
        send.setImageTintList(ColorStateList.valueOf(has ? ON_ACCENT : MUTED));
    }

    private void send() {
        String text = input.getText().toString().trim();
        if (text.isEmpty() && pending.isEmpty()) return;
        String err = hub.send(ses, text, pending);
        if (err != null) {
            Toast.makeText(this, err, Toast.LENGTH_LONG).show();
            return;
        }
        input.setText("");
        pending.clear();
        renderTray();
    }

    @Override
    public void onChatsChanged() {
        if (hub.sessions.find(ses.id) == null) {
            finish();
            return;
        }
        refresh();
    }

    @Override
    public void onMessage(Sessions.Session s, Sessions.Msg msg) {
        if (s == ses) {
            if (ses.messages.size() == 1) { // drop the empty-chat hint
                messages.removeAllViews();
                hint = null;
            }
            show(msg);
            lastShown = msg;
            scrollToEnd();
        }
    }

    /** Pick this chat's model; the conversation continues with the new one. */
    private void chooseModel() {
        Models.refresh(this);
        final String[][] options = Models.forBackend(ses.backend);
        LinearLayout c = column(this);
        TextView h = title(this, getString(R.string.chat_model_title, ses.brainName()), 18);
        c.addView(h);
        TextView sub = text(this, getString(R.string.chat_model_subtitle), 13, MUTED);
        sub.setPaddingRelative(0, dp(this, 2), 0, dp(this, 10));
        c.addView(sub);
        final Dialog[] d = new Dialog[1];
        String[][] efforts = Models.efforts(ses.backend);
        if (efforts != null) {
            c.addView(sectionLabel(this, getString(R.string.chat_effort)));
            TextView es = text(this, getString(R.string.chat_effort_help), 13, MUTED);
            es.setPaddingRelative(0, dp(this, 2), 0, dp(this, 8));
            c.addView(es);
            c.addView(segmented(this, efforts, ses.effort, false, v -> {
                d[0].dismiss();
                hub.setEffort(ses, v);
            }), full());
            TextView ml = sectionLabel(this, getString(R.string.chat_model));
            ml.setPaddingRelative(0, dp(this, 16), 0, dp(this, 4));
            c.addView(ml);
        }
        boolean known = false;
        for (final String[] o : options) {
            boolean on = o[0].equals(ses.model);
            known |= on;
            LinearLayout r = row(this);
            r.setPaddingRelative(dp(this, 4), dp(this, 10), dp(this, 4), dp(this, 10));
            r.setBackgroundResource(ripple(this, false));
            r.addView(radio(on));
            LinearLayout t = column(this);
            t.setPaddingRelative(dp(this, 14), 0, 0, 0);
            t.addView(title(this, o[1], 15));
            t.addView(single(monoText(this, o[0].isEmpty() ? Models.defaultHint(ses) : o[0], 12, MUTED)));
            r.addView(t, weight1());
            r.setOnClickListener(v -> {
                d[0].dismiss();
                if (!o[0].equals(ses.model)) hub.setModel(ses, o[0]);
            });
            c.addView(r, full());
        }
        LinearLayout other = row(this);
        other.setPaddingRelative(0, dp(this, 10), 0, 0);
        final EditText et = new EditText(this);
        et.setHint(getString(R.string.chat_model_other_hint, "claude".equals(ses.backend) ? "claude-opus-5-5"
                : "opencode".equals(ses.backend) ? "anthropic/claude-sonnet-5-5" : "agy".equals(ses.backend) ? "gemini-3.8-flash-high"
                : "grok".equals(ses.backend) ? "grok-4.7" : "gpt-6.1-sol"));
        et.setHintTextColor(MUTED);
        et.setTextColor(TEXT);
        et.setTextSize(14);
        et.setTypeface(mono(this, 400));
        et.setSingleLine(true);
        if (!known) et.setText(ses.model);
        et.setPaddingRelative(dp(this, 12), dp(this, 10), dp(this, 12), dp(this, 10));
        et.setBackground(outlined(this, BG, 12, OUTLINE, 1));
        other.addView(et, weight1());
        Button use = button(this, getString(R.string.chat_model_use), true);
        LinearLayout.LayoutParams up = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(this, 44));
        up.setMarginStart(dp(this, 8));
        other.addView(use, up);
        use.setOnClickListener(v -> {
            d[0].dismiss();
            hub.setModel(ses, et.getText().toString().trim());
        });
        c.addView(other, full());
        d[0] = bottomSheet(this, c);
        d[0].show();
    }

    /**
     * The Linux tools chip: turning them on first says what the agent can then do and what could go wrong; turning
     * them off is immediate. Either way the chat's next message gets the change (a running one goes on as it began).
     */
    private void switchLinuxTools() {
        if (ses.dev) {
            hub.setDev(ses, false);
            return;
        }
        LinearLayout c = protect(column(this)); // only the user turns them on, as with the chip
        c.addView(title(this, getString(R.string.chat_linux_tools_title), 18));
        TextView what = text(this, getString(R.string.chat_linux_tools_what), 14, MUTED);
        what.setPaddingRelative(0, dp(this, 8), 0, 0);
        c.addView(what);
        TextView when = text(this, getString(R.string.chat_linux_tools_when), 14, MUTED);
        when.setPaddingRelative(0, dp(this, 8), 0, dp(this, 14));
        c.addView(when);
        c.addView(warning(this, getString(R.string.chat_linux_tools_risk)), full());
        LinearLayout btns = row(this);
        btns.setPaddingRelative(0, dp(this, 18), 0, 0);
        Button no = button(this, getString(android.R.string.cancel), false);
        Button yes = button(this, getString(R.string.chat_linux_tools_turn_on), true);
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(0, dp(this, 48), 1);
        btns.addView(no, bp);
        LinearLayout.LayoutParams yp = new LinearLayout.LayoutParams(0, dp(this, 48), 1);
        yp.setMarginStart(dp(this, 10));
        btns.addView(yes, yp);
        c.addView(btns, full());
        Dialog d = bottomSheet(this, c);
        no.setOnClickListener(v -> d.dismiss());
        yes.setOnClickListener(v -> {
            d.dismiss();
            hub.setDev(ses, true);
        });
        d.show();
    }

    /**
     * Chat options: long-task mode (the screen stays on while the chat works, and a notification says how it ended) and
     * "Allowed without asking", apps whose routine approvals go ahead on their own until the chat's run ends.
     */
    private void chatOptions() {
        LinearLayout c = protect(column(this)); // only the user changes these, as with the Linux tools chip
        c.addView(title(this, getString(R.string.chat_options), 18));
        LinearLayout sw = switchRow(this, getString(R.string.chat_keep_awake), getString(R.string.chat_keep_awake_info),
                ses.awake, on -> hub.setAwake(ses, on));
        sw.setPaddingRelative(dp(this, 4), dp(this, 12), dp(this, 4), dp(this, 12));
        c.addView(sw, full());
        final Dialog[] sheet = new Dialog[1];
        TextView sl = sectionLabel(this, getString(R.string.schedule_title));
        sl.setPaddingRelative(0, dp(this, 12), 0, dp(this, 4));
        c.addView(sl);
        LinearLayout sr = row(this); // what's set and when it next runs, or a row to set one
        sr.setGravity(Gravity.CENTER_VERTICAL);
        sr.setPaddingRelative(dp(this, 4), dp(this, 10), dp(this, 4), dp(this, 10));
        sr.setBackgroundResource(ripple(this, false));
        sr.addView(icon(this, R.drawable.ms_schedule, ses.schedule != null ? ACCENT_TEXT : MUTED, 22));
        LinearLayout st = column(this);
        st.setPaddingRelative(dp(this, 12), 0, 0, 0);
        if (ses.schedule == null) {
            st.addView(text(this, getString(R.string.schedule_set), 15, ACCENT_TEXT));
            st.addView(text(this, getString(R.string.schedule_set_info), 13, MUTED));
        } else {
            st.addView(title(this, ses.schedule.summary(this), 15));
            st.addView(text(this, getString(R.string.schedule_next,
                    Schedules.when(this, ses.schedule.next(System.currentTimeMillis()))), 13, MUTED));
        }
        sr.addView(st, weight1());
        sr.addView(icon(this, R.drawable.ms_chevron_right, MUTED, 22));
        sr.setOnClickListener(v -> {
            sheet[0].dismiss();
            scheduleSheet();
        });
        c.addView(sr, full());
        TextView label = sectionLabel(this, getString(R.string.chat_allowed));
        label.setPaddingRelative(0, dp(this, 12), 0, dp(this, 4));
        c.addView(label);
        TextView info = text(this, getString(R.string.chat_allowed_info), 13, MUTED);
        info.setPaddingRelative(0, 0, 0, dp(this, 8));
        c.addView(info);
        if (ses.schedule != null) { // kept between runs (see ChatHub.runEnded)
            TextView kept = text(this, getString(R.string.chat_allowed_kept), 13, ACCENT_TEXT);
            kept.setPaddingRelative(0, 0, 0, dp(this, 8));
            c.addView(kept);
        }
        LinearLayout list = column(this);
        c.addView(list, full());
        final Runnable[] fill = new Runnable[1];
        fill[0] = () -> {
            list.removeAllViews();
            android.content.pm.PackageManager pm = getPackageManager();
            for (String pkg : new java.util.ArrayList<>(ses.allowed)) {
                LinearLayout r = row(this);
                r.setGravity(Gravity.CENTER_VERTICAL);
                r.setPaddingRelative(dp(this, 4), dp(this, 6), 0, dp(this, 6));
                ImageView ic = new ImageView(this);
                String name = pkg;
                try {
                    android.content.pm.ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
                    ic.setImageDrawable(pm.getApplicationIcon(ai));
                    name = String.valueOf(pm.getApplicationLabel(ai));
                } catch (Exception ignored) {
                }
                r.addView(ic, new LinearLayout.LayoutParams(dp(this, 28), dp(this, 28)));
                TextView l = single(text(this, name, 15, TEXT));
                l.setPaddingRelative(dp(this, 12), 0, 0, 0);
                r.addView(l, weight1());
                ImageButton x = iconButton(this, R.drawable.ms_close, getString(R.string.chat_allowed_remove, name), MUTED);
                x.setOnClickListener(v -> {
                    hub.setAllowed(ses, pkg, false);
                    fill[0].run();
                });
                r.addView(x, new LinearLayout.LayoutParams(dp(this, 40), dp(this, 40)));
                list.addView(r, full());
            }
            LinearLayout add = row(this);
            add.setGravity(Gravity.CENTER_VERTICAL);
            add.setPaddingRelative(dp(this, 4), dp(this, 10), dp(this, 4), dp(this, 10));
            add.setBackgroundResource(ripple(this, false));
            add.addView(icon(this, R.drawable.ms_add, ACCENT_TEXT, 22));
            TextView at = text(this, getString(R.string.chat_allowed_add), 15, ACCENT_TEXT);
            at.setPaddingRelative(dp(this, 12), 0, 0, 0);
            add.addView(at, weight1());
            add.setOnClickListener(v -> pickAllowedApp(fill[0]));
            list.addView(add, full());
        };
        fill[0].run();
        sheet[0] = bottomSheet(this, c);
        sheet[0].show();
    }

    /**
     * The chat's schedule: a time of day, the days (every day, weekdays or picked ones), the message Buddy sends then as
     * if the user typed it (at first the chat's first message), and whether to wake the phone for it. Nothing changes
     * until Save.
     */
    private void scheduleSheet() {
        final Schedules.Plan p = ses.schedule != null ? ses.schedule.copy() : new Schedules.Plan();
        if (ses.schedule == null) p.text = Schedules.firstMessage(ses);
        LinearLayout c = protect(column(this)); // only the user sets what runs on its own
        c.addView(title(this, getString(R.string.schedule_title), 18));
        TextView info = text(this, getString(R.string.schedule_info), 13, MUTED);
        info.setPaddingRelative(0, dp(this, 4), 0, dp(this, 8));
        c.addView(info);

        TextView tl = sectionLabel(this, getString(R.string.schedule_time));
        tl.setPaddingRelative(0, dp(this, 8), 0, dp(this, 4));
        c.addView(tl);
        TextView time = title(this, p.time(this), 28);
        time.setTypeface(mono(this, 500));
        time.setPaddingRelative(dp(this, 12), dp(this, 6), dp(this, 12), dp(this, 6));
        time.setBackground(outlined(this, BG, 12, OUTLINE, 1));
        time.setOnClickListener(v -> new android.app.TimePickerDialog(this, (tp, h, m) -> {
            p.minute = h * 60 + m;
            time.setText(p.time(this));
        }, p.minute / 60, p.minute % 60, android.text.format.DateFormat.is24HourFormat(this)).show());
        c.addView(time, wrap());

        TextView dl = sectionLabel(this, getString(R.string.schedule_days));
        dl.setPaddingRelative(0, dp(this, 14), 0, dp(this, 4));
        c.addView(dl);
        FrameLayout modes = new FrameLayout(this);
        c.addView(modes, full());
        LinearLayout picks = row(this);
        LinearLayout.LayoutParams pp = full();
        pp.topMargin = dp(this, 8);
        c.addView(picks, pp);
        String[] names = new java.text.DateFormatSymbols(Ui.locale(this)).getShortWeekdays();
        final Runnable[] paint = new Runnable[1];
        final String[] mode = {p.days == Schedules.Plan.EVERY_DAY ? "daily" : p.days == Schedules.Plan.WEEKDAYS ? "weekdays" : "pick"};
        paint[0] = () -> {
            modes.removeAllViews();
            modes.addView(segmented(this, new String[][]{{"daily", getString(R.string.schedule_daily)},
                    {"weekdays", getString(R.string.schedule_weekdays)}, {"pick", getString(R.string.schedule_pick_days)}},
                    mode[0], false, v -> {
                        mode[0] = v;
                        if ("daily".equals(v)) p.days = Schedules.Plan.EVERY_DAY;
                        else if ("weekdays".equals(v)) p.days = Schedules.Plan.WEEKDAYS;
                        paint[0].run();
                    }));
            picks.removeAllViews();
            picks.setVisibility("pick".equals(mode[0]) ? View.VISIBLE : View.GONE);
            for (int d : Schedules.week()) {
                boolean on = p.on(d);
                TextView t = text(this, names[d], 13, on ? ON_ACCENT : MUTED);
                t.setGravity(Gravity.CENTER);
                t.setMaxLines(1);
                t.setPaddingRelative(0, dp(this, 8), 0, dp(this, 8));
                t.setBackground(on ? rounded(this, ACCENT, 10) : outlined(this, BG, 10, OUTLINE, 1));
                t.setOnClickListener(v -> {
                    p.days ^= 1 << (d - 1);
                    paint[0].run();
                });
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
                lp.setMarginEnd(dp(this, 4));
                picks.addView(t, lp);
            }
        };
        paint[0].run();

        TextView ml = sectionLabel(this, getString(R.string.schedule_message));
        ml.setPaddingRelative(0, dp(this, 14), 0, dp(this, 4));
        c.addView(ml);
        EditText msg = new EditText(this);
        msg.setText(p.text);
        msg.setHint(getString(R.string.schedule_message_hint));
        msg.setHintTextColor(MUTED);
        msg.setTextColor(TEXT);
        msg.setTextSize(15);
        msg.setTypeface(sans(this, 400));
        msg.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_AUTO_CORRECT);
        msg.setMinLines(2);
        msg.setMaxLines(6);
        msg.setGravity(Gravity.TOP | Gravity.START);
        msg.setBackground(outlined(this, BG, 12, OUTLINE, 1));
        msg.setPaddingRelative(dp(this, 12), dp(this, 10), dp(this, 12), dp(this, 10));
        c.addView(msg, full());

        LinearLayout wake = switchRow(this, getString(R.string.schedule_wake), getString(R.string.schedule_wake_info),
                p.wake, on -> p.wake = on);
        wake.setPaddingRelative(dp(this, 4), dp(this, 14), dp(this, 4), dp(this, 6));
        c.addView(wake, full());

        if (!Schedules.exact(this)) { // Android 14+ asks the user for exact alarms
            LinearLayout.LayoutParams wp = full();
            wp.topMargin = dp(this, 8);
            c.addView(warning(this, getString(R.string.schedule_inexact)), wp);
            Button allow = outlinedButton(this, getString(R.string.schedule_allow_exact), ACCENT_TEXT);
            allow.setOnClickListener(v -> {
                try {
                    startActivity(new android.content.Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                            android.net.Uri.parse("package:" + getPackageName())));
                } catch (Exception e) {
                    Toast.makeText(this, R.string.chat_no_app_for_action, Toast.LENGTH_SHORT).show();
                }
            });
            LinearLayout.LayoutParams ap = wrap();
            ap.topMargin = dp(this, 8);
            c.addView(allow, ap);
        }

        LinearLayout btns = row(this);
        btns.setPaddingRelative(0, dp(this, 18), 0, 0);
        final Dialog[] d = new Dialog[1];
        if (ses.schedule != null) {
            Button remove = button(this, getString(R.string.schedule_remove), false);
            remove.setTextColor(ERR);
            remove.setBackground(rounded(this, ERR_SOFT, 12));
            remove.setOnClickListener(v -> {
                d[0].dismiss();
                hub.setSchedule(ses, null);
            });
            LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(0, dp(this, 48), 1);
            rp.setMarginEnd(dp(this, 10));
            btns.addView(remove, rp);
        }
        Button save = button(this, getString(R.string.schedule_save), true);
        btns.addView(save, new LinearLayout.LayoutParams(0, dp(this, 48), 1));
        c.addView(btns, full());
        save.setOnClickListener(v -> {
            p.text = msg.getText().toString().trim();
            if ((p.days & Schedules.Plan.EVERY_DAY) == 0) {
                Toast.makeText(this, R.string.schedule_no_days, Toast.LENGTH_SHORT).show();
                return;
            }
            if (p.text.isEmpty()) {
                msg.requestFocus();
                Toast.makeText(this, R.string.schedule_no_message, Toast.LENGTH_SHORT).show();
                return;
            }
            d[0].dismiss();
            hub.setSchedule(ses, p);
            Toast.makeText(this, getString(R.string.schedule_next, Schedules.when(this, p.next(System.currentTimeMillis()))),
                    Toast.LENGTH_SHORT).show();
        });
        d[0] = bottomSheet(this, c);
        d[0].show();
    }

    /** Pick an app for "Allowed without asking": launchable apps, without those it never covers (Guard.allowable). */
    private void pickAllowedApp(Runnable done) {
        LinearLayout c = protect(column(this));
        c.addView(title(this, getString(R.string.rules_pick_app), 18));
        EditText search = new EditText(this);
        search.setHint(R.string.rules_search_apps);
        search.setHintTextColor(MUTED);
        search.setTextColor(TEXT);
        search.setTextSize(15);
        search.setSingleLine(true);
        search.setBackground(outlined(this, CARD, 12, OUTLINE, 1));
        search.setPaddingRelative(dp(this, 12), dp(this, 10), dp(this, 12), dp(this, 10));
        LinearLayout.LayoutParams sp = full();
        sp.topMargin = dp(this, 12);
        c.addView(search, sp);
        LinearLayout list = column(this);
        LinearLayout.LayoutParams lp = full();
        lp.topMargin = dp(this, 8);
        list.addView(spinner(this, 28));
        c.addView(list, lp);
        Dialog d = bottomSheet(this, c);
        final java.util.List<String[]> apps = new java.util.ArrayList<>(); // {pkg, label}
        final java.util.Map<String, android.graphics.drawable.Drawable> icons = new java.util.HashMap<>();
        final Runnable fill = () -> {
            list.removeAllViews();
            String q = search.getText().toString().trim().toLowerCase(Ui.locale(this));
            for (String[] a : apps) {
                if (ses.allowed.contains(a[0]) || (!q.isEmpty() && !a[1].toLowerCase(Ui.locale(this)).contains(q))) continue;
                LinearLayout r = row(this);
                r.setGravity(Gravity.CENTER_VERTICAL);
                r.setPaddingRelative(dp(this, 4), dp(this, 8), dp(this, 4), dp(this, 8));
                r.setBackgroundResource(ripple(this, false));
                ImageView ic = new ImageView(this);
                ic.setImageDrawable(icons.get(a[0]));
                r.addView(ic, new LinearLayout.LayoutParams(dp(this, 32), dp(this, 32)));
                TextView l = single(text(this, a[1], 15, TEXT));
                l.setPaddingRelative(dp(this, 12), 0, 0, 0);
                r.addView(l, weight1());
                r.setOnClickListener(v -> {
                    d.dismiss();
                    hub.setAllowed(ses, a[0], true);
                    done.run();
                });
                list.addView(r, full());
            }
        };
        search.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int a, int b, int n) {
            }

            @Override
            public void onTextChanged(CharSequence s, int a, int b, int n) {
            }

            @Override
            public void afterTextChanged(Editable e) {
                if (!apps.isEmpty()) fill.run();
            }
        });
        d.show();
        new Thread(() -> {
            android.content.pm.PackageManager pm = getPackageManager();
            java.util.List<String[]> out = new java.util.ArrayList<>();
            java.util.Map<String, android.graphics.drawable.Drawable> ics = new java.util.HashMap<>();
            for (android.content.pm.ResolveInfo ri : pm.queryIntentActivities(new android.content.Intent(
                    android.content.Intent.ACTION_MAIN).addCategory(android.content.Intent.CATEGORY_LAUNCHER), 0)) {
                String pkg = ri.activityInfo.packageName;
                if (ics.containsKey(pkg) || !Guard.allowable(this, pkg)) continue;
                out.add(new String[]{pkg, String.valueOf(ri.loadLabel(pm))});
                ics.put(pkg, ri.loadIcon(pm));
            }
            out.sort((x, y) -> x[1].compareToIgnoreCase(y[1]));
            runOnUiThread(() -> {
                if (isFinishing() || !d.isShowing()) return;
                apps.addAll(out);
                icons.putAll(ics);
                fill.run();
            });
        }, "allowed-apps").start();
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

    /** Talk: tap to start, speak, it sends when you stop. Tap again to send right away. */
    private void toggleMic() {
        if (Voice.listening()) {
            Voice.finishListening();
            return;
        }
        if (!Voice.micAllowed(this)) {
            requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, 3);
            return;
        }
        final String before = input.getText().toString().trim();
        showVoiceBar(true);
        String err = Voice.listen(this, new Voice.Listener() {
            @Override
            public void onPartial(String text) {
                voiceBar.setHeard(text);
            }

            @Override
            public void onFinal(String text) {
                input.setText(join(before, text));
                micIdle();
                send();
            }

            @Override
            public void onEnd(String message) {
                micIdle();
                if (message != null) Toast.makeText(ChatActivity.this, message, Toast.LENGTH_SHORT).show();
            }

            @Override
            public void onLevel(float level) {
                voiceBar.level(level);
            }

            @Override
            public void onStatus(String status) {
                voiceBar.setState(status);
            }
        });
        if (err != null) {
            showVoiceBar(false);
            Toast.makeText(this, err, Toast.LENGTH_LONG).show();
        }
    }

    /** While talking the composer becomes the recording bar (cancel, waveform, send). */
    private void showVoiceBar(boolean on) {
        if (on) {
            voiceBar.begin(null);
            ((android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
                    .hideSoftInputFromWindow(input.getWindowToken(), 0);
        }
        voiceBar.setVisibility(on ? View.VISIBLE : View.GONE);
        pill.setVisibility(on ? View.GONE : View.VISIBLE);
    }

    /** The live bar's waveform and line, while this chat has the call. */
    private final CodexLive.Ui liveUi = new CodexLive.Ui() {
        @Override
        public void onLevel(float level) {
            if (CodexLive.on(ses)) liveBar.level(level);
        }

        @Override
        public void onLine(String text) {
            if (CodexLive.on(ses)) liveBar.setHeard(text);
        }

        @Override
        public void onStatus(String status) {
            if (CodexLive.on(ses)) liveBar.setLiveStatus(status);
            else paintLive(); // ended: the composer comes back at once
        }
    };

    /** The live bar in place of the composer while this chat has the call. */
    private void paintLive() {
        boolean live = CodexLive.on(ses);
        boolean shown = liveBar.getVisibility() == View.VISIBLE;
        if (live) {
            if (!shown) {
                if (voiceBar.getVisibility() == View.VISIBLE) Voice.stopListening();
                liveBar.begin(null);
                ((android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
                        .hideSoftInputFromWindow(input.getWindowToken(), 0);
            }
            liveBar.setLiveStatus(CodexLive.statusText());
            liveBar.since(System.currentTimeMillis() - (android.os.SystemClock.elapsedRealtime() - CodexLive.started()));
            liveBar.setVisibility(View.VISIBLE);
            voiceBar.setVisibility(View.GONE);
            pill.setVisibility(View.GONE);
            liveHint.setVisibility(View.GONE);
        } else if (shown) {
            liveBar.setVisibility(View.GONE);
            pill.setVisibility(View.VISIBLE);
        }
    }

    /** Go live: talk with Codex out loud in this chat (long-press the mic). */
    private void startLive() {
        liveHint.setVisibility(View.GONE);
        if (!Voice.micAllowed(this)) {
            requestPermissions(new String[]{android.Manifest.permission.RECORD_AUDIO}, 4);
            return;
        }
        String err = CodexLive.start(this, ses);
        if (err != null) Toast.makeText(this, err, Toast.LENGTH_LONG).show();
        refresh();
    }

    /** Long-press the mic: which of your languages to listen for (and, in a Codex chat, live voice). */
    private void languageSheet() {
        LinearLayout c = column(this);
        final Dialog[] d = new Dialog[1];
        if (CodexLive.available(this, ses)) {
            LinearLayout r = row(this);
            r.setPaddingRelative(dp(this, 4), dp(this, 4), dp(this, 4), dp(this, 14));
            r.setBackgroundResource(ripple(this, false));
            FrameLayout ic = new FrameLayout(this);
            ic.setBackground(rounded(this, ACCENT_SOFT, 12));
            ic.addView(icon(this, R.drawable.ms_mic, ACCENT_TEXT, 22),
                    new FrameLayout.LayoutParams(dp(this, 22), dp(this, 22), Gravity.CENTER));
            r.addView(ic, new LinearLayout.LayoutParams(dp(this, 40), dp(this, 40)));
            LinearLayout t = column(this);
            t.setPaddingRelative(dp(this, 14), 0, 0, 0);
            t.addView(title(this, getString(R.string.live_option), 15));
            t.addView(text(this, getString(R.string.live_option_sub), 13, MUTED));
            r.addView(t, weight1());
            r.setOnClickListener(v -> {
                d[0].dismiss();
                startLive();
            });
            c.addView(r, full());
        }
        c.addView(title(this, getString(R.string.chat_speak_in), 18));
        TextView sub = text(this, getString(R.string.chat_speak_in_help), 13, MUTED);
        sub.setPaddingRelative(0, dp(this, 2), 0, dp(this, 8));
        c.addView(sub);
        String cur = Prefs.voiceLang(this);
        for (String tag : Prefs.voiceLangs(this).split(",")) {
            LinearLayout r = row(this);
            r.setPaddingRelative(dp(this, 4), dp(this, 12), dp(this, 4), dp(this, 12));
            r.setBackgroundResource(ripple(this, false));
            r.addView(radio(tag.equals(cur)));
            TextView t = title(this, Voice.languageName(tag), 15);
            t.setPaddingRelative(dp(this, 14), 0, 0, 0);
            r.addView(t, weight1());
            if ("claude".equals(Prefs.voiceEngine(this)) && !ClaudeDictation.supports(tag)) {
                r.addView(monoText(this, getString(R.string.chat_phone_voice), 11, MUTED));
            }
            r.setOnClickListener(v -> {
                Prefs.setVoiceLang(this, tag);
                d[0].dismiss();
                micIdle();
            });
            c.addView(r, full());
        }
        d[0] = bottomSheet(this, c);
        d[0].show();
    }

    private void micIdle() {
        if (voiceBar != null) showVoiceBar(false);
        String[] langs = Prefs.voiceLangs(this).split(",");
        if (langs.length > 1) { // show which language the mic listens for
            mic.setText(Prefs.voiceLang(this).split("-")[0].toUpperCase(java.util.Locale.ROOT));
            mic.setTextSize(10);
            mic.setTypeface(mono(this, 600));
            mic.setTextColor(MUTED);
            mic.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ms_mic, 0, 0, 0);
            mic.setCompoundDrawableTintList(ColorStateList.valueOf(MUTED));
            mic.setCompoundDrawablePadding(dp(this, 2));
            mic.setPaddingRelative(dp(this, 6), 0, dp(this, 8), 0);
            mic.setBackgroundResource(ripple(this, true));
            input.setHint(getString(R.string.chat_input_hint, ses.brainName()));
            return;
        }
        mic.setText("");
        mic.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ms_mic, 0, 0, 0);
        mic.setCompoundDrawableTintList(ColorStateList.valueOf(MUTED));
        mic.setCompoundDrawablePadding(0);
        mic.setPaddingRelative(dp(this, 8), 0, dp(this, 8), 0);
        mic.setBackgroundResource(ripple(this, true));
        input.setHint(getString(R.string.chat_input_hint, ses.brainName()));
    }

    private static String join(String a, String b) {
        return a.isEmpty() ? b : a + " " + b;
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (code == 3 && Voice.micAllowed(this)) toggleMic();
        if (code == 4 && Voice.micAllowed(this)) startLive();
    }

    private void refresh() {
        boolean speak = Prefs.speakReplies(this);
        speaker.setImageResource(speak ? R.drawable.ms_volume_up : R.drawable.ms_volume_off);
        speaker.setImageTintList(ColorStateList.valueOf(speak ? ACCENT : MUTED));
        title.setText(ses.label());
        chipText.setText(Models.summary(ses));
        if (linux != null) paintLinuxChip(this, linux, ses.dev);
        options.setImageTintList(ColorStateList.valueOf(ses.awake || ses.schedule != null || !ses.allowed.isEmpty() ? ACCENT : MUTED));
        if (hint != null) hint.setText(hintText());
        strip.setVisibility(ses.running ? View.VISIBLE : View.GONE);
        input.setHint(ses.running ? getString(R.string.chat_input_hint_running) : getString(R.string.chat_input_hint, ses.brainName()));
        stripText.setText(ses.status.isEmpty() ? getString(R.string.chat_working) : ses.status);
        if (log != null) {
            if (ses.running) log.setLive(true);
            else log.finish();
        }
        renderApproval();
        onBrowserChanged();
        paintLive();
    }

    /** The live card shows while the background browser runs and this chat has a tab in it. */
    @Override
    public void onBrowserChanged() {
        if (browser == null) return;
        boolean on = Browser.available(this) && Browser.running() && Browser.hasTab(ses.id);
        browser.setVisibility(on ? View.VISIBLE : View.GONE);
        browser.setShown(on && resumed);
        if (on) browser.refresh();
    }

    private String shownApproval;
    private final Runnable waitingChanged = this::renderApproval;

    /** This chat's reply waiting in the user's list (a notification rule's run), or null. */
    private Waiting.Item waiting() {
        for (Waiting.Item it : Waiting.all(this)) if (it.chat.equals(ses.id)) return it;
        return null;
    }

    private void renderApproval() {
        BuddyService svc = BuddyService.get();
        String ask = svc != null && svc.bubble() != null && svc.bubble().pendingChat() == ses ? svc.bubble().pendingConfirmText() : null;
        java.util.List<String> options = ask == null ? null : svc.bubble().pendingOptions();
        long[] countdown = ask == null ? new long[]{0, 0} : svc.bubble().countdown();
        Waiting.Item waiting = ask == null ? waiting() : null; // no card now: its reply may wait in the list
        String key = ask != null ? ask + (options == null ? "" : "\u0000" + options) + (countdown[0] > 0 ? "\u0001" : "")
                : waiting != null ? "\u0002" + waiting.at + Waiting.state(waiting) : null;
        if (key == null ? shownApproval == null : key.equals(shownApproval)) return;
        shownApproval = key;
        approval.removeAllViews();
        if (waiting != null) approval.addView(WaitingActivity.card(this, waiting, true), full());
        if (ask == null) return;
        LinearLayout card = column(this);
        card.setPaddingRelative(dp(this, 16), dp(this, 14), dp(this, 16), dp(this, 14));
        card.setBackground(outlined(this, CARD, 18, Theme.withAlpha(ACCENT, 0x73), 1));
        LinearLayout hr = row(this);
        hr.addView(icon(this, R.drawable.ms_shield_person, ACCENT_TEXT, 18));
        TextView lab = text(this, getString(options != null ? R.string.chat_card_question : R.string.chat_card_needs_ok), 12, ACCENT_TEXT);
        lab.setTypeface(sans(this, 600));
        lab.setLetterSpacing(0.06f);
        lab.setPaddingRelative(dp(this, 8), 0, 0, 0);
        hr.addView(lab);
        card.addView(hr);
        TextView well = text(this, ask, 14, TEXT);
        well.setPaddingRelative(dp(this, 12), dp(this, 10), dp(this, 12), dp(this, 10));
        // long texts scroll inside the card, so its buttons always stay on screen
        final int maxH = (int) (getResources().getDisplayMetrics().heightPixels * 0.3f);
        ScrollView ws = new ScrollView(this) {
            @Override
            protected void onMeasure(int w, int h) {
                super.onMeasure(w, MeasureSpec.makeMeasureSpec(maxH, MeasureSpec.AT_MOST));
            }
        };
        ws.setBackground(rounded(this, BG, 12));
        ws.addView(well);
        LinearLayout.LayoutParams wp = full();
        wp.topMargin = dp(this, 10);
        wp.bottomMargin = dp(this, 12);
        card.addView(ws, wp);
        if (options != null) {
            renderQuestion(card, options);
            approval.addView(card, full());
            return;
        }
        if (countdown[0] > 0) card.addView(countdownView(countdown), full());
        LinearLayout btns = row(this);
        Button deny = button(this, getString(R.string.chat_deny), false);
        deny.setTextColor(ERR);
        deny.setBackground(rounded(this, ERR_SOFT, 12));
        Button change = button(this, getString(R.string.chat_change), false);
        Button ok = button(this, getString(R.string.chat_approve), true);
        btns.addView(deny, new LinearLayout.LayoutParams(0, dp(this, 44), 1));
        LinearLayout.LayoutParams cp = new LinearLayout.LayoutParams(0, dp(this, 44), 1);
        cp.setMarginStart(dp(this, 10));
        btns.addView(change, cp);
        LinearLayout.LayoutParams op = new LinearLayout.LayoutParams(0, dp(this, 44), 1);
        op.setMarginStart(dp(this, 10));
        btns.addView(ok, op);
        card.addView(btns, full());
        deny.setOnClickListener(v -> answer(false));
        ok.setOnClickListener(v -> answer(true));
        change.setOnClickListener(v -> askChange(card, btns));
        approval.addView(card, full());
    }

    /** A question from the ask tool: a button per answer, then Skip and "Type my own answer". */
    private void renderQuestion(LinearLayout card, java.util.List<String> options) {
        LinearLayout opts = column(this);
        for (String o : options) {
            Button b = button(this, o, false);
            b.setSingleLine(false);
            b.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            b.setBackground(outlined(this, CARD2, 12, Theme.withAlpha(ACCENT, 0x40), 1));
            b.setOnClickListener(v -> {
                BuddyService svc = BuddyService.get();
                if (svc != null && svc.bubble() != null) svc.bubble().pickFromApp(o);
                renderApproval();
            });
            LinearLayout.LayoutParams bp = full();
            bp.bottomMargin = dp(this, 8);
            opts.addView(b, bp);
        }
        card.addView(opts, full());
        LinearLayout btns = row(this);
        Button skip = button(this, getString(R.string.chat_skip), false);
        Button own = button(this, getString(R.string.chat_type_own_answer), false);
        btns.addView(skip, new LinearLayout.LayoutParams(0, dp(this, 44), 1));
        LinearLayout.LayoutParams op = new LinearLayout.LayoutParams(0, dp(this, 44), 2);
        op.setMarginStart(dp(this, 10));
        btns.addView(own, op);
        LinearLayout.LayoutParams rp = full();
        rp.topMargin = dp(this, 4);
        card.addView(btns, rp);
        skip.setOnClickListener(v -> answer(false));
        own.setOnClickListener(v -> {
            opts.setVisibility(View.GONE);
            askChange(card, btns, true);
        });
    }

    /** Change: the user writes what to adjust, and the agent gets it back instead of a yes/no. */
    private void askChange(LinearLayout card, LinearLayout btns) {
        askChange(card, btns, false);
    }

    /** answering: the same box types an answer to a question instead. */
    private void askChange(LinearLayout card, LinearLayout btns, boolean answering) {
        BuddyService bs = BuddyService.get();
        if (bs != null && bs.bubble() != null) bs.bubble().stopCountdown(); // no approving itself while the user writes
        btns.setVisibility(View.GONE);
        EditText what = new EditText(this);
        what.setHint(answering ? R.string.chat_answer_hint : R.string.chat_change_hint);
        what.setHintTextColor(MUTED);
        what.setTextColor(TEXT);
        what.setTextSize(15);
        what.setMinLines(2);
        what.setMaxLines(6);
        what.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_AUTO_CORRECT);
        what.setPaddingRelative(dp(this, 12), dp(this, 10), dp(this, 12), dp(this, 10));
        what.setBackground(rounded(this, BG, 12));
        card.addView(what, full());
        LinearLayout row = row(this);
        Button back = button(this, getString(R.string.chat_back), false);
        Button send = button(this, getString(answering ? R.string.chat_send_answer : R.string.chat_send_change), true);
        row.addView(back, new LinearLayout.LayoutParams(0, dp(this, 44), 1));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(0, dp(this, 44), 1);
        sp.setMarginStart(dp(this, 10));
        row.addView(send, sp);
        LinearLayout.LayoutParams rp = full();
        rp.topMargin = dp(this, 10);
        card.addView(row, rp);
        back.setOnClickListener(v -> {
            card.removeView(what);
            card.removeView(row);
            btns.setVisibility(View.VISIBLE);
            if (answering) { // redraw the answer buttons
                shownApproval = null;
                renderApproval();
            }
        });
        send.setOnClickListener(v -> {
            String text = what.getText().toString().trim();
            if (text.isEmpty()) {
                what.requestFocus();
                return;
            }
            BuddyService svc = BuddyService.get();
            if (svc != null && svc.bubble() != null) svc.bubble().changeFromApp(text);
            renderApproval();
        });
        what.requestFocus();
        what.postDelayed(() -> getSystemService(android.view.inputmethod.InputMethodManager.class)
                .showSoftInput(what, 0), 150);
    }

    /** A notification rule's countdown on the card, as in the bubble: it approves itself at the end; a tap stops it. */
    private View countdownView(long[] cd) {
        LinearLayout box = column(this);
        box.setPaddingRelative(0, 0, 0, dp(this, 12));
        android.widget.ProgressBar bar = new android.widget.ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(1000);
        bar.setProgressTintList(android.content.res.ColorStateList.valueOf(ACCENT));
        bar.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(CARD2));
        box.addView(bar, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(this, 6)));
        TextView t = text(this, "", 13, MUTED);
        t.setPaddingRelative(0, dp(this, 6), 0, 0);
        box.addView(t);
        box.setOnClickListener(v -> {
            BuddyService svc = BuddyService.get();
            if (svc != null && svc.bubble() != null) svc.bubble().stopCountdown();
        });
        Runnable tick = new Runnable() {
            @Override
            public void run() {
                BuddyService svc = BuddyService.get();
                long[] now = svc == null || svc.bubble() == null ? new long[]{0, 0} : svc.bubble().countdown();
                long left = now[0] - android.os.SystemClock.elapsedRealtime();
                if (now[0] == 0 || left <= 0) {
                    box.setVisibility(View.GONE);
                    return;
                }
                bar.setProgress((int) (1000 * left / now[1]));
                t.setText(getString(R.string.card_countdown, (int) ((left + 999) / 1000)));
                if (box.isAttachedToWindow()) box.postDelayed(this, 50);
            }
        };
        tick.run(); // draws it now; the loop starts once it's on screen
        box.post(tick);
        return box;
    }

    private void answer(boolean approved) {
        BuddyService svc = BuddyService.get();
        if (svc != null && svc.bubble() != null) svc.bubble().answerFromApp(approved);
        renderApproval();
    }

    private void show(Sessions.Msg m) {
        if (m.scheduled) { // sent by Buddy at the chat's scheduled time: a small "Scheduled" over it
            if (log != null) {
                log.finish();
                log = null;
            }
            LinearLayout tag = row(this);
            tag.setGravity(Gravity.CENTER_VERTICAL);
            tag.addView(icon(this, R.drawable.ms_schedule, MUTED, 14));
            TextView tt = monoText(this, getString(R.string.schedule_marker), 11, MUTED);
            tt.setPaddingRelative(dp(this, 4), 0, 0, 0);
            tag.addView(tt);
            LinearLayout.LayoutParams tp = wrap();
            tp.gravity = Gravity.END;
            tp.topMargin = dp(this, 12);
            tp.bottomMargin = -dp(this, 8); // close to its message
            messages.addView(tag, tp);
        }
        if (m.kind == Bubble.Kind.USER && !m.files.isEmpty()) {
            if (log != null) {
                log.finish();
                log = null;
            }
            LinearLayout col = column(this);
            col.setGravity(Gravity.END);
            for (String id : m.files) {
                LinearLayout.LayoutParams fp = wrap();
                fp.gravity = Gravity.END;
                fp.bottomMargin = dp(this, 4);
                col.addView(attachmentView(id, true), fp);
            }
            if (!m.text.isEmpty()) {
                TextView tv = text(this, m.text, 15, TEXT);
                links(tv);
                tv.setMaxWidth(maxBubble);
                tv.setBackground(corners(this, USER, 20, 20, 6, 20));
                tv.setPaddingRelative(dp(this, 14), dp(this, 10), dp(this, 14), dp(this, 10));
                copyOnLongPress(tv, m.text);
                LinearLayout.LayoutParams tp = wrap();
                tp.gravity = Gravity.END;
                col.addView(tv, tp);
            }
            LinearLayout.LayoutParams p = full();
            p.topMargin = dp(this, 12);
            messages.addView(col, p);
            return;
        }
        show(m.kind, m.text);
    }

    // --------------------------------------------------------- attachments

    private void attachSheet() {
        LinearLayout c = column(this);
        TextView h = title(this, getString(R.string.chat_attach), 18);
        h.setPaddingRelative(0, 0, 0, dp(this, 8));
        c.addView(h);
        final Dialog[] d = new Dialog[1];
        Object[][] opts = {
                {R.drawable.ms_photo_camera, getString(R.string.chat_attach_camera), getString(R.string.chat_attach_camera_sub)},
                {R.drawable.ms_image, getString(R.string.chat_attach_photos), getString(R.string.chat_attach_photos_sub)},
                {R.drawable.ms_description, getString(R.string.chat_attach_files), getString(R.string.chat_attach_files_sub)},
        };
        for (int i = 0; i < opts.length; i++) {
            final int which = i;
            LinearLayout r = row(this);
            r.setPaddingRelative(dp(this, 4), dp(this, 12), dp(this, 4), dp(this, 12));
            r.setBackgroundResource(ripple(this, false));
            FrameLayout ic = new FrameLayout(this);
            ic.setBackground(rounded(this, ACCENT_SOFT, 12));
            ic.addView(icon(this, (Integer) opts[i][0], ACCENT_TEXT, 22),
                    new FrameLayout.LayoutParams(dp(this, 22), dp(this, 22), Gravity.CENTER));
            r.addView(ic, new LinearLayout.LayoutParams(dp(this, 40), dp(this, 40)));
            LinearLayout t = column(this);
            t.setPaddingRelative(dp(this, 14), 0, 0, 0);
            t.addView(title(this, (String) opts[i][1], 15));
            t.addView(text(this, (String) opts[i][2], 13, MUTED));
            r.addView(t, weight1());
            r.setOnClickListener(v -> {
                d[0].dismiss();
                pick(which);
            });
            c.addView(r, full());
        }
        d[0] = bottomSheet(this, c);
        d[0].show();
    }

    private void pick(int which) {
        try {
            if (which == 0) {
                android.content.Intent i = new android.content.Intent(android.provider.MediaStore.ACTION_IMAGE_CAPTURE);
                android.net.Uri out = FilesProvider.captureUri();
                FilesProvider.captureFile(this).delete();
                i.putExtra(android.provider.MediaStore.EXTRA_OUTPUT, out);
                i.setClipData(ClipData.newRawUri("photo", out));
                i.addFlags(android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION | android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
                startActivityForResult(i, REQ_CAMERA);
            } else if (which == 1) {
                android.content.Intent i;
                if (android.os.Build.VERSION.SDK_INT >= 33) {
                    i = new android.content.Intent(android.provider.MediaStore.ACTION_PICK_IMAGES)
                            .putExtra(android.provider.MediaStore.EXTRA_PICK_IMAGES_MAX, 10);
                } else {
                    i = new android.content.Intent(android.content.Intent.ACTION_GET_CONTENT).setType("image/*")
                            .putExtra(android.content.Intent.EXTRA_ALLOW_MULTIPLE, true);
                }
                startActivityForResult(i, REQ_PHOTOS);
            } else {
                android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_OPEN_DOCUMENT)
                        .addCategory(android.content.Intent.CATEGORY_OPENABLE).setType("*/*")
                        .putExtra(android.content.Intent.EXTRA_ALLOW_MULTIPLE, true);
                startActivityForResult(i, REQ_FILES);
            }
        } catch (android.content.ActivityNotFoundException e) {
            Toast.makeText(this, R.string.chat_no_app_for_action, Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int code, int result, android.content.Intent data) {
        super.onActivityResult(code, result, data);
        if (result != RESULT_OK) return;
        final java.util.List<android.net.Uri> uris = new java.util.ArrayList<>();
        if (code == REQ_CAMERA) {
            if (FilesProvider.captureFile(this).length() > 0) uris.add(FilesProvider.captureUri());
        } else if (data != null) {
            if (data.getClipData() != null) {
                for (int i = 0; i < data.getClipData().getItemCount(); i++) uris.add(data.getClipData().getItemAt(i).getUri());
            } else if (data.getData() != null) {
                uris.add(data.getData());
            }
        }
        if (!uris.isEmpty()) addFiles(uris, null);
    }

    /** Copies picked or pasted files into Buddy's storage and adds them to the next message; then done, if any. */
    private void addFiles(java.util.List<android.net.Uri> uris, Runnable done) {
        Toast.makeText(this, uris.size() == 1 ? getString(R.string.chat_adding)
                : getResources().getQuantityString(R.plurals.chat_adding_files, uris.size(), uris.size()), Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            java.util.List<String> ids = new java.util.ArrayList<>();
            String err = null;
            for (android.net.Uri u : uris) {
                try {
                    ids.add(Attachments.importUri(this, u));
                } catch (Exception e) {
                    err = e.getMessage();
                }
            }
            final String error = err;
            runOnUiThread(() -> {
                if (done != null) done.run();
                if (isFinishing()) return;
                pending.addAll(ids);
                renderTray();
                if (error != null) Toast.makeText(this, error, Toast.LENGTH_LONG).show();
            });
        }).start();
    }

    private void renderTray() {
        tray.removeAllViews();
        trayScroll.setVisibility(pending.isEmpty() ? View.GONE : View.VISIBLE);
        for (final String id : new java.util.ArrayList<>(pending)) {
            FrameLayout f = new FrameLayout(this);
            f.addView(attachmentView(id, false));
            ImageButton x = new ImageButton(this);
            x.setImageResource(R.drawable.ms_close);
            x.setImageTintList(ColorStateList.valueOf(0xFFFFFFFF));
            x.setScaleType(ImageView.ScaleType.FIT_CENTER);
            x.setPaddingRelative(dp(this, 3), dp(this, 3), dp(this, 3), dp(this, 3));
            x.setBackground(oval(0xB3000000));
            x.setContentDescription(getString(R.string.chat_attachment_remove));
            x.setOnClickListener(v -> {
                pending.remove(id);
                renderTray();
            });
            FrameLayout.LayoutParams xp = new FrameLayout.LayoutParams(dp(this, 22), dp(this, 22), Gravity.TOP | Gravity.END);
            xp.setMargins(0, dp(this, 4), dp(this, 4), 0);
            f.addView(x, xp);
            LinearLayout.LayoutParams p = wrap();
            p.setMarginEnd(dp(this, 8));
            tray.addView(f, p);
        }
        paintSend();
    }

    /** A photo thumbnail (rounded) or a file chip; tapping opens it in another app. */
    private View attachmentView(final String id, boolean inThread) {
        View v;
        if (Attachments.isImage(id)) {
            ImageView iv = new ImageView(this);
            int side = dp(this, inThread ? 180 : 64);
            android.graphics.Bitmap b = Attachments.thumb(this, id, side);
            if (b != null) iv.setImageBitmap(b);
            else iv.setBackground(rounded(this, CARD2, 14));
            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
            iv.setClipToOutline(true);
            iv.setOutlineProvider(new android.view.ViewOutlineProvider() {
                @Override
                public void getOutline(View view, android.graphics.Outline o) {
                    o.setRoundRect(0, 0, view.getWidth(), view.getHeight(), dp(ChatActivity.this, inThread ? 18 : 14));
                }
            });
            int h = side;
            if (inThread && b != null && b.getWidth() > 0) h = Math.min(dp(this, 240), side * b.getHeight() / b.getWidth());
            iv.setLayoutParams(new LinearLayout.LayoutParams(side, h));
            v = iv;
        } else {
            LinearLayout chip = row(this);
            chip.setPaddingRelative(dp(this, 10), dp(this, 8), dp(this, 12), dp(this, 8));
            chip.setBackground(inThread ? corners(this, USER, 16, 16, 6, 16) : outlined(this, CARD, 14, OUTLINE, 1));
            chip.addView(icon(this, R.drawable.ms_description, inThread ? TEXT : ACCENT_TEXT, 20));
            TextView t = single(text(this, Attachments.name(id), 14, TEXT));
            t.setMaxWidth(dp(this, 200));
            t.setPaddingRelative(dp(this, 8), 0, inThread ? 0 : dp(this, 18), 0);
            chip.addView(t);
            chip.setMinimumHeight(dp(this, inThread ? 0 : 64));
            v = chip;
        }
        v.setOnClickListener(x -> openAttachment(id));
        return v;
    }

    private void openAttachment(String id) {
        android.net.Uri u = FilesProvider.attachmentUri(id);
        android.content.Intent i = new android.content.Intent(android.content.Intent.ACTION_VIEW)
                .setDataAndType(u, getContentResolver().getType(u))
                .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, getString(R.string.chat_no_app_for_file, Attachments.name(id)), Toast.LENGTH_SHORT).show();
        }
    }

    private void show(Bubble.Kind kind, final String text) {
        if (kind == Bubble.Kind.STEP) {
            if (log == null) {
                log = new ToolLog();
                LinearLayout.LayoutParams lp = full();
                lp.topMargin = dp(this, 12);
                messages.addView(log.view, lp);
            }
            log.add(text);
            log.setLive(ses.running);
            return;
        }
        if (log != null) {
            log.finish();
            log = null;
        }
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(this, 12);
        View v;
        switch (kind) {
            case USER: {
                TextView tv = text(this, text, 15, TEXT);
                links(tv);
                tv.setMaxWidth(maxBubble);
                tv.setBackground(corners(this, USER, 20, 20, 6, 20));
                tv.setPaddingRelative(dp(this, 14), dp(this, 10), dp(this, 14), dp(this, 10));
                copyOnLongPress(tv, text);
                p.gravity = Gravity.END;
                v = tv;
                break;
            }
            case BUDDY: {
                LinearLayout r = row(this);
                r.setGravity(Gravity.BOTTOM);
                FrameLayout av = new FrameLayout(this);
                av.setBackground(oval(ACCENT_SOFT));
                ImageView face = new ImageView(this);
                Face.set(face, R.drawable.bubble_face);
                int fs = dp(this, Face.size(this, 20, 24));
                av.addView(face, new FrameLayout.LayoutParams(fs, fs, Gravity.CENTER));
                LinearLayout.LayoutParams avp = new LinearLayout.LayoutParams(dp(this, 24), dp(this, 24));
                avp.setMarginEnd(dp(this, 8));
                r.addView(av, avp);
                TextView tv = text(this, text, 15, TEXT);
                tv.setTextIsSelectable(true);
                tv.setText(Ui.markdownLinks(text, null)); // [text](url): the text, as a link
                links(tv);
                Ui.copyWithLinks(tv);
                tv.setLineSpacing(0, 1.15f);
                tv.setMaxWidth(maxBubble - dp(this, 32));
                tv.setBackground(corners(this, CARD, 20, 20, 20, 6));
                tv.setPaddingRelative(dp(this, 14), dp(this, 10), dp(this, 14), dp(this, 10));
                r.addView(tv);
                v = r;
                break;
            }
            default: { // ERROR
                LinearLayout r = row(this);
                r.setGravity(Gravity.TOP);
                r.addView(icon(this, R.drawable.ms_error, ERR, 18));
                TextView tv = text(this, text, 13, ERR);
                tv.setPaddingRelative(dp(this, 8), 0, 0, 0);
                r.addView(tv, weight1());
                copyOnLongPress(r, text);
                p.width = LinearLayout.LayoutParams.MATCH_PARENT;
                v = r;
            }
        }
        messages.addView(v, p);
        while (messages.getChildCount() > Sessions.MAX_MESSAGES) messages.removeViewAt(0);
    }

    /** Web links and email addresses become tappable (selectable text stays selectable). */
    private static void links(TextView tv) {
        tv.setAutoLinkMask(android.text.util.Linkify.WEB_URLS | android.text.util.Linkify.EMAIL_ADDRESSES);
        tv.setLinkTextColor(ACCENT_TEXT);
        tv.setText(tv.getText()); // the mask applies on setText
    }

    private void copyOnLongPress(View v, String text) {
        v.setOnLongClickListener(x -> {
            getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("Buddy", text));
            Toast.makeText(this, R.string.chat_copied, Toast.LENGTH_SHORT).show();
            return true;
        });
    }

    private void scrollToEnd() {
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }

    /** Consecutive tool steps, grouped into one collapsible log. */
    private final class ToolLog {
        final LinearLayout view = column(ChatActivity.this);
        final LinearLayout rows = column(ChatActivity.this);
        final TextView count;
        final ImageView chevron;
        int n;
        boolean open, live, userToggled;
        View lastMark;

        ToolLog() {
            view.setBackground(outlined(ChatActivity.this, 0x00000000, 14, LINE, 1));
            LinearLayout head = row(ChatActivity.this);
            head.setPaddingRelative(dp(ChatActivity.this, 12), dp(ChatActivity.this, 10), dp(ChatActivity.this, 8), dp(ChatActivity.this, 10));
            head.setBackgroundResource(ripple(ChatActivity.this, false));
            head.addView(icon(ChatActivity.this, R.drawable.ms_terminal, MUTED, 16));
            count = monoText(ChatActivity.this, "", 12, MUTED);
            count.setPaddingRelative(dp(ChatActivity.this, 8), 0, 0, 0);
            head.addView(count, weight1());
            chevron = icon(ChatActivity.this, R.drawable.ms_expand_more, MUTED, 18);
            head.addView(chevron);
            head.setOnClickListener(v -> {
                userToggled = true;
                setOpen(!open);
            });
            view.addView(head, full());
            rows.setPaddingRelative(dp(ChatActivity.this, 12), 0, dp(ChatActivity.this, 12), dp(ChatActivity.this, 10));
            view.addView(rows, full());
            setOpen(false);
        }

        void add(String step) {
            n++;
            count.setText(getResources().getQuantityString(R.plurals.chat_tool_calls, n, n));
            if (lastMark != null) swapToCheck(lastMark);
            String s = step.trim();
            if (s.startsWith("✓ ")) s = s.substring(2); // the row gets its own check mark
            int cut = -1;
            for (int i = 0; i < s.length(); i++) {
                char ch = s.charAt(i);
                if (ch == ' ' || ch == ':' || ch == '(') {
                    cut = i;
                    break;
                }
            }
            String name = cut > 0 ? s.substring(0, cut) : s, arg = cut > 0 ? s.substring(cut) : "";
            LinearLayout r = row(ChatActivity.this);
            r.setGravity(Gravity.TOP);
            r.setPaddingRelative(0, dp(ChatActivity.this, 4), 0, dp(ChatActivity.this, 4));
            FrameLayout mark = new FrameLayout(ChatActivity.this);
            r.addView(mark, new LinearLayout.LayoutParams(dp(ChatActivity.this, 16), dp(ChatActivity.this, 16)));
            TextView t = monoText(ChatActivity.this, "", 12, MUTED);
            android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder(name);
            sb.setSpan(new android.text.style.ForegroundColorSpan(TEXT), 0, name.length(), 0);
            sb.append(arg);
            t.setText(sb);
            t.setMaxLines(3);
            t.setEllipsize(android.text.TextUtils.TruncateAt.END);
            t.setPaddingRelative(dp(ChatActivity.this, 8), 0, 0, 0);
            r.addView(t, weight1());
            copyOnLongPress(r, step);
            rows.addView(r, full());
            lastMark = mark;
            paintLast();
        }

        private void swapToCheck(View mark) {
            FrameLayout f = (FrameLayout) mark;
            f.removeAllViews();
            ImageView ok = icon(ChatActivity.this, R.drawable.ms_check, OK, 16);
            f.addView(ok, new FrameLayout.LayoutParams(dp(ChatActivity.this, 16), dp(ChatActivity.this, 16)));
        }

        private void paintLast() {
            if (lastMark == null) return;
            FrameLayout f = (FrameLayout) lastMark;
            f.removeAllViews();
            if (live) {
                ProgressBar p = spinner(ChatActivity.this, 14);
                f.addView(p, new FrameLayout.LayoutParams(dp(ChatActivity.this, 14), dp(ChatActivity.this, 14), Gravity.CENTER));
            } else {
                swapToCheck(lastMark);
            }
        }

        void setLive(boolean on) {
            if (live == on) return;
            live = on;
            paintLast();
            if (!userToggled) setOpen(on);
        }

        void finish() {
            setLive(false);
            if (!userToggled) setOpen(false);
        }

        void setOpen(boolean o) {
            open = o;
            rows.setVisibility(o ? View.VISIBLE : View.GONE);
            chevron.setImageResource(o ? R.drawable.ms_expand_less : R.drawable.ms_expand_more);
        }
    }
}
