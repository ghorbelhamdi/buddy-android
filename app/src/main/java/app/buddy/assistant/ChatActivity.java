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
    private Button mic;
    private LinearLayout pill;
    private VoiceBar voiceBar;
    private ImageButton speaker, send;
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
        LinearLayout chip = chip(this, "");
        chipText = (TextView) chip.getChildAt(0);
        chip.setOnClickListener(v -> chooseModel());
        LinearLayout.LayoutParams cp = wrap();
        cp.topMargin = dp(this, 3);
        titles.addView(chip, cp);
        top.addView(titles, weight1());
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
        if (ses.messages.isEmpty()) {
            TextView hint = text(this, getString(Prefs.devMode(this) ? R.string.chat_empty_hint_dev : R.string.chat_empty_hint,
                    ses.brainName()), 14, MUTED);
            hint.setGravity(Gravity.CENTER);
            hint.setPaddingRelative(dp(this, 24), dp(this, 40), dp(this, 24), 0);
            messages.addView(hint, full());
        }
        for (Sessions.Msg m : ses.messages) show(m);
        if (log != null && !ses.running) log.finish();
        lastShown = lastMsg();
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
        if (lastMsg() != lastShown) showAll(); // messages came in while this screen was hidden
        refresh();
        scrollToEnd();
    }

    @Override
    protected void onPause() {
        if (Voice.listening()) Voice.stopListening();
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
            if (ses.messages.size() == 1) messages.removeAllViews(); // drop the empty-chat hint
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
                : "gpt-6.1-sol"));
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

    /** Long-press the mic: which of your languages to listen for. */
    private void languageSheet() {
        LinearLayout c = column(this);
        c.addView(title(this, getString(R.string.chat_speak_in), 18));
        TextView sub = text(this, getString(R.string.chat_speak_in_help), 13, MUTED);
        sub.setPaddingRelative(0, dp(this, 2), 0, dp(this, 8));
        c.addView(sub);
        final Dialog[] d = new Dialog[1];
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
    }

    private void refresh() {
        boolean speak = Prefs.speakReplies(this);
        speaker.setImageResource(speak ? R.drawable.ms_volume_up : R.drawable.ms_volume_off);
        speaker.setImageTintList(ColorStateList.valueOf(speak ? ACCENT : MUTED));
        title.setText(ses.label());
        chipText.setText(Prefs.devMode(this) ? getString(R.string.chat_chip_dev, Models.summary(ses)) : Models.summary(ses));
        strip.setVisibility(ses.running ? View.VISIBLE : View.GONE);
        input.setHint(ses.running ? getString(R.string.chat_input_hint_running) : getString(R.string.chat_input_hint, ses.brainName()));
        stripText.setText(ses.status.isEmpty() ? getString(R.string.chat_working) : ses.status);
        if (log != null) {
            if (ses.running) log.setLive(true);
            else log.finish();
        }
        renderApproval();
        onBrowserChanged();
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
                av.addView(face, new FrameLayout.LayoutParams(dp(this, 20), dp(this, 20), Gravity.CENTER));
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
