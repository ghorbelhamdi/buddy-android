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
public class ChatActivity extends Screen implements ChatHub.Listener {
    private ChatHub hub;
    private Sessions.Session ses;
    private LinearLayout messages, strip, approval;
    private ScrollView scroll;
    private TextView title, chipText, stripText;
    private Button mic;
    private ImageButton speaker, send;
    private EditText input;
    private int maxBubble;
    private ToolLog log; // the tool-call group being appended to, if the last message was a step
    private final java.util.List<String> pending = new java.util.ArrayList<>(); // attachments for the next message
    private android.widget.HorizontalScrollView trayScroll;
    private LinearLayout tray;
    private static final int REQ_CAMERA = 21, REQ_PHOTOS = 22, REQ_FILES = 23;

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
        top.setPadding(dp(this, 4), dp(this, 4), dp(this, 4), dp(this, 4));
        ImageButton back = iconButton(this, R.drawable.ms_arrow_back, "Back");
        back.setLayoutParams(new LinearLayout.LayoutParams(dp(this, 44), dp(this, 44)));
        back.setOnClickListener(v -> finish());
        top.addView(back);
        LinearLayout titles = column(this);
        titles.setPadding(dp(this, 4), 0, dp(this, 4), 0);
        title = single(title(this, ses.label(), 16));
        titles.addView(title);
        LinearLayout chip = chip(this, "");
        chipText = (TextView) chip.getChildAt(0);
        chip.setOnClickListener(v -> chooseModel());
        LinearLayout.LayoutParams cp = wrap();
        cp.topMargin = dp(this, 3);
        titles.addView(chip, cp);
        top.addView(titles, weight1());
        speaker = iconButton(this, R.drawable.ms_volume_off, "Read replies aloud", MUTED);
        speaker.setLayoutParams(new LinearLayout.LayoutParams(dp(this, 44), dp(this, 44)));
        speaker.setOnClickListener(v -> {
            Prefs.setSpeakReplies(this, !Prefs.speakReplies(this));
            if (!Prefs.speakReplies(this)) Voice.stopSpeaking();
            Toast.makeText(this, Prefs.speakReplies(this) ? "Replies will be read aloud" : "Replies won't be read aloud",
                    Toast.LENGTH_SHORT).show();
            refresh();
        });
        top.addView(speaker);
        root.addView(top, full());
        root.addView(divider(), new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(this, 1)));

        // running strip
        strip = row(this);
        strip.setPadding(dp(this, 12), dp(this, 6), dp(this, 6), dp(this, 6));
        strip.setBackground(rounded(this, CARD, 12));
        ProgressBar sp = spinner(this, 16);
        strip.addView(sp);
        stripText = single(monoText(this, "", 12, ACCENT_TEXT));
        stripText.setPadding(dp(this, 10), 0, dp(this, 8), 0);
        strip.addView(stripText, weight1());
        Button stop = button(this, "Stop", false);
        stop.setTextColor(ERR);
        stop.setBackground(rounded(this, ERR_SOFT, 10));
        stop.setMinHeight(dp(this, 34));
        stop.setMinimumHeight(dp(this, 34));
        stop.setPadding(dp(this, 14), 0, dp(this, 14), 0);
        stop.setOnClickListener(v -> hub.stop(ses));
        strip.addView(stop, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(this, 34)));
        LinearLayout.LayoutParams stp = full();
        stp.leftMargin = stp.rightMargin = dp(this, 12);
        stp.topMargin = dp(this, 8);
        root.addView(strip, stp);

        scroll = new ScrollView(this);
        messages = column(this);
        messages.setPadding(dp(this, 16), dp(this, 8), dp(this, 16), dp(this, 12));
        scroll.addView(messages);
        root.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        // inline approval card (when an agent asks for an OK)
        approval = column(this);
        LinearLayout.LayoutParams ap = full();
        ap.leftMargin = ap.rightMargin = dp(this, 12);
        ap.bottomMargin = dp(this, 8);
        root.addView(approval, ap);

        // composer
        trayScroll = new android.widget.HorizontalScrollView(this);
        trayScroll.setHorizontalScrollBarEnabled(false);
        tray = row(this);
        tray.setPadding(dp(this, 12), 0, dp(this, 12), dp(this, 6));
        trayScroll.addView(tray);
        trayScroll.setVisibility(View.GONE);
        root.addView(trayScroll, full());

        LinearLayout pill = row(this);
        pill.setPadding(dp(this, 4), dp(this, 4), dp(this, 4), dp(this, 4));
        ImageButton attach = iconButton(this, R.drawable.ms_attach_file, "Attach a photo or file", MUTED);
        attach.setLayoutParams(new LinearLayout.LayoutParams(dp(this, 40), dp(this, 40)));
        attach.setOnClickListener(v -> attachSheet());
        pill.addView(attach);
        pill.setBackground(outlined(this, CARD, 26, OUTLINE, 1));
        input = new EditText(this);
        input.setHint("Ask " + ses.brainName() + "…");
        input.setHintTextColor(MUTED);
        input.setTextColor(TEXT);
        input.setTextSize(15);
        input.setTypeface(sans(this, 400));
        input.setMaxLines(6);
        input.setBackground(null);
        input.setPadding(0, dp(this, 10), 0, dp(this, 10));
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_AUTO_CORRECT);
        pill.addView(input, weight1());
        mic = button(this, "", false);
        mic.setContentDescription("Talk");
        mic.setOnClickListener(v -> toggleMic());
        pill.addView(mic, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(this, 40)));
        micIdle();
        send = new ImageButton(this);
        send.setImageResource(R.drawable.ms_arrow_upward);
        send.setContentDescription("Send");
        send.setScaleType(ImageView.ScaleType.CENTER);
        send.setOnClickListener(v -> send());
        LinearLayout.LayoutParams snp = new LinearLayout.LayoutParams(dp(this, 40), dp(this, 40));
        snp.leftMargin = dp(this, 4);
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

        // the outer frame takes the system-bar insets
        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(BG);
        frame.setFitsSystemWindows(true);
        frame.addView(root);
        setContentView(frame);

        if (ses.messages.isEmpty()) {
            TextView hint = text(this, "New " + ses.brainName() + " chat. Ask it to do something on your phone"
                    + (Prefs.devMode(this) ? ", or to build an app (developer mode is on)." : "."), 14, MUTED);
            hint.setGravity(Gravity.CENTER);
            hint.setPadding(dp(this, 24), dp(this, 40), dp(this, 24), 0);
            messages.addView(hint, full());
        }
        for (Sessions.Msg m : ses.messages) show(m);
        if (log != null && !ses.running) log.finish();
        String draft = getIntent().getStringExtra("draft");
        if (draft != null && state == null) {
            input.setText(draft);
            input.setSelection(draft.length());
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (ses == null || isFinishing()) return;
        hub.addListener(this);
        hub.setViewing(ses, true);
        hub.select(ses);
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
            scrollToEnd();
        }
    }

    /** Pick this chat's model; the conversation continues with the new one. */
    private void chooseModel() {
        final String[][] options = Models.forBackend(ses.backend);
        LinearLayout c = column(this);
        TextView h = title(this, ses.brainName() + " model", 18);
        c.addView(h);
        TextView sub = text(this, "For this chat. The conversation continues with the new model.", 13, MUTED);
        sub.setPadding(0, dp(this, 2), 0, dp(this, 10));
        c.addView(sub);
        final Dialog[] d = new Dialog[1];
        boolean known = false;
        for (final String[] o : options) {
            boolean on = o[0].equals(ses.model);
            known |= on;
            LinearLayout r = row(this);
            r.setPadding(dp(this, 4), dp(this, 10), dp(this, 4), dp(this, 10));
            r.setBackgroundResource(ripple(this, false));
            r.addView(radio(on));
            LinearLayout t = column(this);
            t.setPadding(dp(this, 14), 0, 0, 0);
            t.addView(title(this, o[1], 15));
            t.addView(single(monoText(this, o[0].isEmpty() ? "agent default" : o[0], 12, MUTED)));
            r.addView(t, weight1());
            r.setOnClickListener(v -> {
                d[0].dismiss();
                if (!o[0].equals(ses.model)) hub.setModel(ses, o[0]);
            });
            c.addView(r, full());
        }
        LinearLayout other = row(this);
        other.setPadding(0, dp(this, 10), 0, 0);
        final EditText et = new EditText(this);
        et.setHint("other… e.g. " + ("claude".equals(ses.backend) ? "claude-opus-5-5" : "gpt-6.1-sol"));
        et.setHintTextColor(MUTED);
        et.setTextColor(TEXT);
        et.setTextSize(14);
        et.setTypeface(mono(this, 400));
        et.setSingleLine(true);
        if (!known) et.setText(ses.model);
        et.setPadding(dp(this, 12), dp(this, 10), dp(this, 12), dp(this, 10));
        et.setBackground(outlined(this, BG, 12, OUTLINE, 1));
        other.addView(et, weight1());
        Button use = button(this, "Use", true);
        LinearLayout.LayoutParams up = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(this, 44));
        up.leftMargin = dp(this, 8);
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
        String err = Voice.listen(this, new Voice.Listener() {
            @Override
            public void onPartial(String text) {
                input.setText(join(before, text));
                input.setSelection(input.getText().length());
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
        });
        if (err != null) {
            Toast.makeText(this, err, Toast.LENGTH_LONG).show();
            return;
        }
        mic.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ms_mic, 0, 0, 0);
        mic.setCompoundDrawableTintList(ColorStateList.valueOf(ON_ACCENT));
        mic.setCompoundDrawablePadding(dp(this, 4));
        mic.setText("Listening");
        mic.setTextColor(ON_ACCENT);
        mic.setPadding(dp(this, 10), 0, dp(this, 12), 0);
        mic.setBackground(rounded(this, ACCENT, 20));
        input.setHint("Speak now…");
    }

    private void micIdle() {
        mic.setText("");
        mic.setCompoundDrawablesRelativeWithIntrinsicBounds(R.drawable.ms_mic, 0, 0, 0);
        mic.setCompoundDrawableTintList(ColorStateList.valueOf(MUTED));
        mic.setCompoundDrawablePadding(0);
        mic.setPadding(dp(this, 8), 0, dp(this, 8), 0);
        mic.setBackgroundResource(ripple(this, true));
        input.setHint("Ask " + ses.brainName() + "…");
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
        chipText.setText(ses.brainName() + " · " + Models.label(ses.backend, ses.model)
                + (Prefs.devMode(this) ? " · dev" : ""));
        strip.setVisibility(ses.running ? View.VISIBLE : View.GONE);
        stripText.setText(ses.status.isEmpty() ? "Working…" : ses.status);
        if (log != null) {
            if (ses.running) log.setLive(true);
            else log.finish();
        }
        renderApproval();
    }

    private String shownApproval;

    private void renderApproval() {
        BuddyService svc = BuddyService.get();
        String ask = svc != null && svc.bubble() != null && hub.sessions.current == ses ? svc.bubble().pendingConfirmText() : null;
        if (ask == null ? shownApproval == null : ask.equals(shownApproval)) return;
        shownApproval = ask;
        approval.removeAllViews();
        if (ask == null) return;
        LinearLayout card = column(this);
        card.setPadding(dp(this, 16), dp(this, 14), dp(this, 16), dp(this, 14));
        card.setBackground(outlined(this, CARD, 18, Theme.withAlpha(ACCENT, 0x73), 1));
        LinearLayout hr = row(this);
        hr.addView(icon(this, R.drawable.ms_shield_person, ACCENT_TEXT, 18));
        TextView lab = text(this, "NEEDS YOUR OK", 12, ACCENT_TEXT);
        lab.setTypeface(sans(this, 600));
        lab.setLetterSpacing(0.06f);
        lab.setPadding(dp(this, 8), 0, 0, 0);
        hr.addView(lab);
        card.addView(hr);
        TextView well = text(this, ask, 14, TEXT);
        well.setMaxLines(8);
        well.setPadding(dp(this, 12), dp(this, 10), dp(this, 12), dp(this, 10));
        well.setBackground(rounded(this, BG, 12));
        LinearLayout.LayoutParams wp = full();
        wp.topMargin = dp(this, 10);
        wp.bottomMargin = dp(this, 12);
        card.addView(well, wp);
        LinearLayout btns = row(this);
        Button deny = button(this, "Deny", false);
        deny.setTextColor(ERR);
        deny.setBackground(rounded(this, ERR_SOFT, 12));
        Button ok = button(this, "Approve", true);
        btns.addView(deny, new LinearLayout.LayoutParams(0, dp(this, 44), 1));
        LinearLayout.LayoutParams op = new LinearLayout.LayoutParams(0, dp(this, 44), 1);
        op.leftMargin = dp(this, 10);
        btns.addView(ok, op);
        card.addView(btns, full());
        deny.setOnClickListener(v -> answer(false));
        ok.setOnClickListener(v -> answer(true));
        approval.addView(card, full());
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
                tv.setMaxWidth(maxBubble);
                tv.setBackground(corners(this, USER, 20, 20, 6, 20));
                tv.setPadding(dp(this, 14), dp(this, 10), dp(this, 14), dp(this, 10));
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
        TextView h = title(this, "Attach", 18);
        h.setPadding(0, 0, 0, dp(this, 8));
        c.addView(h);
        final Dialog[] d = new Dialog[1];
        Object[][] opts = {
                {R.drawable.ms_photo_camera, "Take a photo", "Use the camera"},
                {R.drawable.ms_image, "Photos", "Pick from your gallery"},
                {R.drawable.ms_description, "Files", "PDFs, documents, anything up to 25 MB"},
        };
        for (int i = 0; i < opts.length; i++) {
            final int which = i;
            LinearLayout r = row(this);
            r.setPadding(dp(this, 4), dp(this, 12), dp(this, 4), dp(this, 12));
            r.setBackgroundResource(ripple(this, false));
            FrameLayout ic = new FrameLayout(this);
            ic.setBackground(rounded(this, ACCENT_SOFT, 12));
            ic.addView(icon(this, (Integer) opts[i][0], ACCENT_TEXT, 22),
                    new FrameLayout.LayoutParams(dp(this, 22), dp(this, 22), Gravity.CENTER));
            r.addView(ic, new LinearLayout.LayoutParams(dp(this, 40), dp(this, 40)));
            LinearLayout t = column(this);
            t.setPadding(dp(this, 14), 0, 0, 0);
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
            Toast.makeText(this, "No app on this phone can do that.", Toast.LENGTH_LONG).show();
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
        if (uris.isEmpty()) return;
        Toast.makeText(this, uris.size() == 1 ? "Adding…" : "Adding " + uris.size() + " files…", Toast.LENGTH_SHORT).show();
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
            x.setPadding(dp(this, 3), dp(this, 3), dp(this, 3), dp(this, 3));
            x.setBackground(oval(0xB3000000));
            x.setContentDescription("Remove");
            x.setOnClickListener(v -> {
                pending.remove(id);
                renderTray();
            });
            FrameLayout.LayoutParams xp = new FrameLayout.LayoutParams(dp(this, 22), dp(this, 22), Gravity.TOP | Gravity.END);
            xp.setMargins(0, dp(this, 4), dp(this, 4), 0);
            f.addView(x, xp);
            LinearLayout.LayoutParams p = wrap();
            p.rightMargin = dp(this, 8);
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
            chip.setPadding(dp(this, 10), dp(this, 8), dp(this, 12), dp(this, 8));
            chip.setBackground(inThread ? corners(this, USER, 16, 16, 6, 16) : outlined(this, CARD, 14, OUTLINE, 1));
            chip.addView(icon(this, R.drawable.ms_description, inThread ? TEXT : ACCENT_TEXT, 20));
            TextView t = single(text(this, Attachments.name(id), 14, TEXT));
            t.setMaxWidth(dp(this, 200));
            t.setPadding(dp(this, 8), 0, inThread ? 0 : dp(this, 18), 0);
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
            Toast.makeText(this, "No app can open " + Attachments.name(id) + ".", Toast.LENGTH_SHORT).show();
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
                tv.setMaxWidth(maxBubble);
                tv.setBackground(corners(this, USER, 20, 20, 6, 20));
                tv.setPadding(dp(this, 14), dp(this, 10), dp(this, 14), dp(this, 10));
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
                face.setImageResource(R.drawable.bubble_face);
                av.addView(face, new FrameLayout.LayoutParams(dp(this, 20), dp(this, 20), Gravity.CENTER));
                LinearLayout.LayoutParams avp = new LinearLayout.LayoutParams(dp(this, 24), dp(this, 24));
                avp.rightMargin = dp(this, 8);
                r.addView(av, avp);
                TextView tv = text(this, text, 15, TEXT);
                tv.setTextIsSelectable(true);
                tv.setLineSpacing(0, 1.15f);
                tv.setMaxWidth(maxBubble - dp(this, 32));
                tv.setBackground(corners(this, CARD, 20, 20, 20, 6));
                tv.setPadding(dp(this, 14), dp(this, 10), dp(this, 14), dp(this, 10));
                r.addView(tv);
                v = r;
                break;
            }
            default: { // ERROR
                LinearLayout r = row(this);
                r.setGravity(Gravity.TOP);
                r.addView(icon(this, R.drawable.ms_error, ERR, 18));
                TextView tv = text(this, text, 13, ERR);
                tv.setPadding(dp(this, 8), 0, 0, 0);
                r.addView(tv, weight1());
                copyOnLongPress(r, text);
                p.width = LinearLayout.LayoutParams.MATCH_PARENT;
                v = r;
            }
        }
        messages.addView(v, p);
        while (messages.getChildCount() > Sessions.MAX_MESSAGES) messages.removeViewAt(0);
    }

    private void copyOnLongPress(View v, String text) {
        v.setOnLongClickListener(x -> {
            getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("Buddy", text));
            Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show();
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
            head.setPadding(dp(ChatActivity.this, 12), dp(ChatActivity.this, 10), dp(ChatActivity.this, 8), dp(ChatActivity.this, 10));
            head.setBackgroundResource(ripple(ChatActivity.this, false));
            head.addView(icon(ChatActivity.this, R.drawable.ms_terminal, MUTED, 16));
            count = monoText(ChatActivity.this, "", 12, MUTED);
            count.setPadding(dp(ChatActivity.this, 8), 0, 0, 0);
            head.addView(count, weight1());
            chevron = icon(ChatActivity.this, R.drawable.ms_expand_more, MUTED, 18);
            head.addView(chevron);
            head.setOnClickListener(v -> {
                userToggled = true;
                setOpen(!open);
            });
            view.addView(head, full());
            rows.setPadding(dp(ChatActivity.this, 12), 0, dp(ChatActivity.this, 12), dp(ChatActivity.this, 10));
            view.addView(rows, full());
            setOpen(false);
        }

        void add(String step) {
            n++;
            count.setText(n + (n == 1 ? " tool call" : " tool calls"));
            if (lastMark != null) swapToCheck(lastMark);
            String s = step.trim();
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
            r.setPadding(0, dp(ChatActivity.this, 4), 0, dp(ChatActivity.this, 4));
            FrameLayout mark = new FrameLayout(ChatActivity.this);
            r.addView(mark, new LinearLayout.LayoutParams(dp(ChatActivity.this, 16), dp(ChatActivity.this, 16)));
            TextView t = monoText(ChatActivity.this, "", 12, MUTED);
            android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder(name);
            sb.setSpan(new android.text.style.ForegroundColorSpan(TEXT), 0, name.length(), 0);
            sb.append(arg);
            t.setText(sb);
            t.setMaxLines(3);
            t.setEllipsize(android.text.TextUtils.TruncateAt.END);
            t.setPadding(dp(ChatActivity.this, 8), 0, 0, 0);
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
