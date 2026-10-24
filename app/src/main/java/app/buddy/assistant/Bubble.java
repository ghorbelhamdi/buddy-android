package app.buddy.assistant;

import android.animation.ValueAnimator;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.OvershootInterpolator;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.text.InputType;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.os.Build;
import android.window.BackEvent;
import android.window.OnBackAnimationCallback;
import android.window.OnBackInvokedCallback;
import android.window.OnBackInvokedDispatcher;

import org.json.JSONArray;
import org.json.JSONObject;

/** The floating Buddy bubble and its mini chat panel, drawn as an accessibility overlay. */
final class Bubble implements ChatHub.Listener {
    interface ConfirmCallback {
        /**
         * change: what the user wants changed first (approved is then false), or null.
         * For a question (ask): change is the answer, and approved says it's one of the buttons (not typed).
         */
        void onAnswer(boolean approved, String change);

        /** A notification rule's countdown ran out: approved without the user. */
        default void onAutoApproved() {
            onAnswer(true, null);
        }
    }


    private final BuddyService svc;
    private final WindowManager wm;
    private final Vibrator vibrator;

    private FrameLayout root;
    private WindowManager.LayoutParams lp;

    // collapsed
    private LinearLayout collapsed;
    private ImageView face;
    private TextView badge, pill;
    private ValueAnimator breathing;
    private RingView ring;
    private boolean noteListening;
    // expanded
    private LinearLayout panel, messages, confirmCard;
    private ScrollView scroll;
    /** The card's scrolling part (text and answers), and the most it may take so the panel fits (see buildPanel). */
    private ScrollView cardScroll;
    private int cardRoom = Integer.MAX_VALUE;
    private TextView header, confirmText, confirmLabel;
    private LinearLayout confirmOptions, confirmRow, countdownRow;
    private android.widget.ProgressBar countdownBar;
    private TextView countdownText;
    /** A card's countdown (a notification rule's): when it ends and how long it is (elapsedRealtime ms), or 0. */
    private long countdownEnd, countdownLength;
    private Button confirmDeny, confirmChange, confirmApprove, confirmOwn;
    /** The header's Open in the app and Close, and the chat heads' buttons below the panel (see placePanel). */
    private android.widget.ImageButton expandIcon, closeIcon;
    private View headButtons;
    /** The card asks a question (the ask tool) rather than for approval; askOptions are its buttons. */
    private boolean asking;
    private java.util.List<String> askOptions;
    private EditText input;

    private boolean expanded, running, passThrough, showingList;
    private int imeTop;
    private ConfirmCallback pendingConfirm;
    private ChatHub hub;
    private Sessions sessions;
    private Sessions.Session viewingSes;
    private ScrollView listScroll;
    private LinearLayout sessionList;

    Bubble(BuddyService svc) {
        this.svc = svc;
        this.wm = svc.getSystemService(WindowManager.class);
        this.vibrator = svc.getSystemService(Vibrator.class);
    }

    // ------------------------------------------------------------- layout

    void show() {
        root = new FrameLayout(svc) {
            @Override
            public boolean dispatchKeyEvent(KeyEvent e) {
                if (expanded && (e.getKeyCode() == KeyEvent.KEYCODE_BACK || e.getKeyCode() == KeyEvent.KEYCODE_ESCAPE)) {
                    if (e.getAction() == KeyEvent.ACTION_UP) onBack();
                    return true;
                }
                return super.dispatchKeyEvent(e);
            }
        };
        buildCollapsed();
        buildPanel();
        followKeyboard();
        root.setOnTouchListener((v, e) -> {
            if (e.getAction() == MotionEvent.ACTION_OUTSIDE && expanded && pendingConfirm == null) {
                // An edge swipe for Back also lands outside the panel; wait briefly so Back can cancel this.
                root.removeCallbacks(outsideCollapse);
                root.postDelayed(outsideCollapse, 350);
            }
            return false;
        });
        lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, 0, PixelFormat.TRANSLUCENT);
        root.addView(collapsed);
        applyCollapsedParams();
        createArcs();
        createDismissTarget();
        wm.addView(root, lp);
        hub = ChatHub.get(svc);
        radial = new RadialMenu(svc, wm);
        sessions = hub.sessions;
        hub.addListener(this);
        renderCurrent();
        applyEnabled();
    }

    void remove() {
        if (arcs != null) {
            try {
                wm.removeView(arcs);
            } catch (Exception ignored) {
            }
        }
        if (dismissWindow != null) {
            try {
                wm.removeView(dismissWindow);
            } catch (Exception ignored) {
            }
        }
        if (hub != null) hub.removeListener(this);
        if (breathing != null) breathing.cancel();
        try {
            wm.removeView(root);
        } catch (Exception ignored) {
        }
    }

    private void buildCollapsed() {
        collapsed = new LinearLayout(svc);
        collapsed.setOrientation(LinearLayout.HORIZONTAL);
        collapsed.setGravity(Gravity.CENTER_VERTICAL);

        FrameLayout bubbleFrame = new FrameLayout(svc);
        face = new ImageView(svc);
        face.setScaleType(ImageView.ScaleType.FIT_CENTER);
        face.setBackground(null);
        face.setElevation(dp(5));
        showFace(R.drawable.bubble_face);
        face.setContentDescription("Buddy");
        // room around the face for the working ring
        bubbleFrame.setPaddingRelative(dp(4), dp(4), dp(4), dp(4));
        bubbleFrame.setClipToPadding(false);
        bubbleFrame.setClipChildren(false);
        FrameLayout faceBox = new FrameLayout(svc);
        faceBox.setClipChildren(false);
        ring = new RingView(svc);
        ring.setVisibility(View.GONE);
        faceBox.addView(ring, new FrameLayout.LayoutParams(dp(74), dp(74), Gravity.CENTER));
        faceBox.addView(face, new FrameLayout.LayoutParams(dp(60), dp(60), Gravity.CENTER));
        holdRing = new HoldRing(svc);
        faceBox.addView(holdRing, new FrameLayout.LayoutParams(dp(74), dp(74), Gravity.CENTER));
        // 4. screen readers: "double-tap and hold for actions"
        face.setContentDescription(svc.getString(R.string.bubble_face_description));
        face.setLongClickable(true);
        face.setOnLongClickListener(v -> { // TalkBack's double-tap-and-hold (touches go to DragListener)
            openMenu();
            return true;
        });
        face.setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override
            public void onInitializeAccessibilityNodeInfo(View host, android.view.accessibility.AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                info.addAction(new android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction(
                        android.view.accessibility.AccessibilityNodeInfo.ACTION_LONG_CLICK, svc.getString(R.string.bubble_show_actions)));
            }
        });
        LinearLayout column = new LinearLayout(svc);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        column.setClipChildren(false);
        column.addView(faceBox, new LinearLayout.LayoutParams(dp(74), dp(74)));
        // Stop, Add instruction, Stop voice… are in the hold menu (RadialMenu), not chips under the bubble
        bubbleFrame.addView(column);

        badge = new TextView(svc);
        badge.setBackground(circle(0xFF34C17B, 0xFFFFF8F1, 2));
        badge.setVisibility(View.GONE);
        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(dp(14), dp(14), Gravity.TOP | Gravity.END);
        bubbleFrame.addView(badge, blp);
        collapsed.addView(bubbleFrame);

        pill = new TextView(svc);
        pill.setTextColor(Ui.TEXT);
        pill.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        pill.setTypeface(Ui.sans(svc, 500));
        pill.setMaxWidth(dp(250));
        pill.setMaxLines(2); // status lines are often longer than one line
        pill.setLineSpacing(0, 1.05f);
        pill.setEllipsize(TextUtils.TruncateAt.END);
        pill.setPaddingRelative(dp(12), dp(8), dp(12), dp(8));
        pill.setBackground(rounded(Ui.BG, 18, Ui.ACCENT, 1));
        pill.setVisibility(View.GONE);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        plp.gravity = Gravity.TOP;
        plp.topMargin = dp(22); // level with the face's centre, not the stop button below it
        plp.setMarginStart(dp(6));
        plp.setMarginEnd(dp(6));
        collapsed.addView(pill, plp);
        pill.setOnClickListener(v -> { // paused, the pill is the Resume button
            if (svc.paused() && pendingConfirm == null) svc.resume();
            else expand();
        });

        face.setOnTouchListener(new DragListener());
    }

    private void buildPanel() {
        panel = new LinearLayout(svc) {
            @Override
            protected void onMeasure(int w, int h) {
                cardRoom = Integer.MAX_VALUE;
                super.onMeasure(w, h);
                if (confirmCard == null || confirmCard.getVisibility() != VISIBLE) return;
                // a card taller than the room left (landscape, or with the keyboard up) would push the composer
                // out of the panel: the card's scrolling part gives up the difference instead
                int over = getPaddingTop() + getPaddingBottom() - getMeasuredHeight();
                for (int i = 0; i < getChildCount(); i++) {
                    View v = getChildAt(i);
                    if (v.getVisibility() == GONE) continue;
                    LinearLayout.LayoutParams p = (LinearLayout.LayoutParams) v.getLayoutParams();
                    over += v.getMeasuredHeight() + p.topMargin + p.bottomMargin;
                }
                if (over <= 0) return;
                cardRoom = Math.max(dp(48), cardScroll.getMeasuredHeight() - over);
                confirmCard.forceLayout();
                cardScroll.forceLayout();
                super.onMeasure(w, h);
            }
        };
        panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPaddingRelative(dp(12), dp(10), dp(12), dp(12));
        panel.setBackground(rounded(Ui.BG, 24, Ui.OUTLINE, 1));
        panel.setElevation(dp(10));

        // header like the chat screen: the chat's title (tap: all chats) and agent · model, then plain icons
        LinearLayout top = new LinearLayout(svc);
        top.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout titles = new LinearLayout(svc);
        titles.setOrientation(LinearLayout.VERTICAL);
        titles.setPaddingRelative(dp(4), dp(2), dp(4), dp(2));
        titles.setOnClickListener(v -> toggleSessionList());
        header = new TextView(svc);
        header.setText("Buddy");
        header.setTextColor(Ui.TEXT);
        header.setTypeface(Ui.sans(svc, 600));
        header.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
        header.setSingleLine(true);
        header.setEllipsize(TextUtils.TruncateAt.END);
        header.setCompoundDrawablesRelativeWithIntrinsicBounds(0, 0, R.drawable.ms_expand_more, 0);
        header.setCompoundDrawableTintList(android.content.res.ColorStateList.valueOf(Ui.MUTED));
        header.setCompoundDrawablePadding(dp(2));
        titles.addView(header, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        // agent · model · effort, as a chip like the chat screen's: tap to change the model or effort
        LinearLayout chip = Ui.chip(svc, "");
        subtitle = (TextView) chip.getChildAt(0);
        chip.setOnClickListener(v -> showModelPicker());
        modelChip = chip;
        LinearLayout.LayoutParams chp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        chp.topMargin = dp(3);
        titles.addView(chip, chp);
        top.addView(titles, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        top.addView(toolIcon(R.drawable.ms_add, svc.getString(R.string.bubble_new_chat), Ui.MUTED, v -> showNewChat()));
        expandIcon = toolIcon(R.drawable.ms_open_in_full, svc.getString(R.string.bubble_open_in_app), Ui.MUTED, v -> openInApp(false));
        top.addView(expandIcon);
        closeIcon = toolIcon(R.drawable.ms_close, svc.getString(R.string.bubble_close), Ui.MUTED, v -> collapse());
        top.addView(closeIcon);
        panel.addView(top, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        View line = new View(svc);
        line.setBackgroundColor(Ui.OUTLINE);
        LinearLayout.LayoutParams llp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1));
        llp.topMargin = dp(8);
        llp.leftMargin = llp.rightMargin = -dp(12);
        panel.addView(line, llp);

        // running strip, as in the chat screen: what it's doing, and Stop
        strip = new LinearLayout(svc);
        strip.setGravity(Gravity.CENTER_VERTICAL);
        strip.setPaddingRelative(dp(12), dp(6), dp(6), dp(6));
        strip.setBackground(rounded(Ui.CARD, 12, 0, 0));
        strip.addView(Ui.spinner(svc, 16));
        stripText = Ui.single(Ui.monoText(svc, "", 12, Ui.ACCENT_TEXT));
        stripText.setPaddingRelative(dp(10), 0, dp(8), 0);
        strip.addView(stripText, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        Button stopBtn = Ui.button(svc, svc.getString(R.string.bubble_stop), false);
        stopBtn.setTextColor(Ui.ERR);
        stopBtn.setBackground(rounded(Ui.ERR_SOFT, 10, 0, 0));
        stopBtn.setMinHeight(dp(34));
        stopBtn.setMinimumHeight(dp(34));
        stopBtn.setPaddingRelative(dp(14), 0, dp(14), 0);
        stopBtn.setOnClickListener(v -> stop());
        strip.addView(stopBtn, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(34)));
        strip.setVisibility(View.GONE);
        LinearLayout.LayoutParams stp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        stp.topMargin = dp(8);
        panel.addView(strip, stp);

        // Tapping the conversation hides the keyboard, messages included: they take the touch themselves (a
        // listener on the scroll view only saw taps between them), so it's checked before they get it.
        scroll = new ScrollView(svc) {
            @Override
            public boolean dispatchTouchEvent(MotionEvent e) {
                if (e.getActionMasked() == MotionEvent.ACTION_DOWN && keyboardVisible()) hideKeyboard();
                return super.dispatchTouchEvent(e);
            }
        };
        messages = new LinearLayout(svc);
        messages.setOrientation(LinearLayout.VERTICAL);
        messages.setPaddingRelative(0, dp(8), 0, dp(8));
        scroll.addView(messages);
        panel.addView(scroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        listScroll = new ScrollView(svc);
        sessionList = new LinearLayout(svc);
        sessionList.setOrientation(LinearLayout.VERTICAL);
        sessionList.setPaddingRelative(0, dp(8), 0, dp(8));
        listScroll.addView(sessionList);
        listScroll.setVisibility(View.GONE);
        panel.addView(listScroll, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        confirmCard = new LinearLayout(svc);
        confirmCard.setOrientation(LinearLayout.VERTICAL);
        confirmCard.setPaddingRelative(dp(12), dp(10), dp(12), dp(10));
        confirmCard.setBackground(rounded(Ui.CARD, 18, Theme.withAlpha(Ui.ACCENT, 0x73), 1));
        confirmLabel = new TextView(svc);
        confirmLabel.setText(R.string.card_label_approval);
        confirmLabel.setLetterSpacing(0.06f);
        confirmLabel.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        confirmLabel.setTextColor(Ui.ACCENT_TEXT);
        confirmLabel.setTypeface(Ui.sans(svc, 600));
        confirmCard.addView(confirmLabel);
        confirmText = new TextView(svc);
        confirmText.setTextColor(Ui.TEXT);
        confirmText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        confirmText.setPaddingRelative(0, dp(6), 0, dp(10));
        // long texts and a question's answers scroll inside the card, so the card's buttons and the composer
        // always stay in the panel, landscape included (cardRoom)
        ScrollView cs = new ScrollView(svc) {
            @Override
            protected void onMeasure(int w, int h) {
                // smaller while the user types an answer or change, so the text field stays above the keyboard
                float cap = changing ? 0.12f : 0.5f;
                int most = Math.min((int) (screen().height() * cap), cardRoom);
                super.onMeasure(w, MeasureSpec.makeMeasureSpec(most, MeasureSpec.AT_MOST));
            }
        };
        cardScroll = cs;
        LinearLayout body = new LinearLayout(svc);
        body.setOrientation(LinearLayout.VERTICAL);
        body.addView(confirmText);
        // a question's answers, one button each (filled in by showCard)
        confirmOptions = new LinearLayout(svc);
        confirmOptions.setOrientation(LinearLayout.VERTICAL);
        body.addView(confirmOptions, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        cs.addView(body);
        confirmCard.addView(cs, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        // a notification rule's countdown: the card approves itself when it runs out; a tap stops it
        countdownRow = new LinearLayout(svc);
        countdownRow.setOrientation(LinearLayout.VERTICAL);
        countdownRow.setPaddingRelative(0, 0, 0, dp(10));
        countdownBar = new android.widget.ProgressBar(svc, null, android.R.attr.progressBarStyleHorizontal);
        countdownBar.setMax(1000);
        countdownBar.setProgressTintList(android.content.res.ColorStateList.valueOf(Ui.ACCENT));
        countdownBar.setProgressBackgroundTintList(android.content.res.ColorStateList.valueOf(Ui.CARD2));
        countdownRow.addView(countdownBar, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(6)));
        countdownText = new TextView(svc);
        countdownText.setTextColor(Ui.MUTED);
        countdownText.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        countdownText.setTypeface(Ui.sans(svc, 500));
        countdownText.setPaddingRelative(0, dp(6), 0, 0);
        countdownRow.addView(countdownText);
        countdownRow.setOnClickListener(v -> stopCountdown());
        countdownRow.setVisibility(View.GONE);
        confirmCard.addView(countdownRow, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        LinearLayout row = new LinearLayout(svc);
        confirmRow = row;
        row.setGravity(Gravity.END);
        Button deny = actionButton(svc.getString(R.string.card_deny), Ui.ERR_SOFT);
        confirmDeny = deny;
        deny.setTextColor(Ui.ERR);
        deny.setOnClickListener(v -> answerConfirm(false));
        // Change: the user types or says what to change, and the agent gets that back instead of a yes/no
        Button change = actionButton(svc.getString(R.string.card_change), Ui.CARD);
        confirmChange = change;
        change.setTextColor(Ui.TEXT);
        change.setOnClickListener(v -> startChange());
        Button approve = actionButton(svc.getString(R.string.card_approve), Ui.ACCENT);
        confirmApprove = approve;
        approve.setOnClickListener(v -> answerConfirm(true));
        // a question's "Type my own answer…" shares the bottom row with Skip, so it takes no row of its own
        confirmOwn = optionButton(svc.getString(R.string.card_type_own_answer), true, v -> startChange());
        LinearLayout.LayoutParams ownLp = new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1);
        ownLp.setMarginEnd(dp(8));
        confirmOwn.setVisibility(View.GONE);
        row.addView(confirmOwn, ownLp);
        row.addView(deny);
        LinearLayout.LayoutParams changeLp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        changeLp.setMarginStart(dp(8));
        row.addView(change, changeLp);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        alp.setMarginStart(dp(8));
        row.addView(approve, alp);
        confirmCard.addView(row);
        confirmCard.setVisibility(View.GONE);
        LinearLayout.LayoutParams clp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        clp.bottomMargin = dp(8);
        panel.addView(confirmCard, clp);

        // composer: one pill with the text field, a mic and a round send button (like the chat screen)
        LinearLayout inputRow = new LinearLayout(svc);
        inputRow.setGravity(Gravity.CENTER_VERTICAL);
        inputRow.setPaddingRelative(dp(4), dp(4), dp(4), dp(4));
        inputRow.setBackground(rounded(Ui.CARD, 26, Ui.OUTLINE, 1));
        // attachments are picked in the app (camera, gallery, files), with this chat and draft open
        android.widget.ImageButton attach = Ui.iconButton(svc, R.drawable.ms_attach_file, svc.getString(R.string.bubble_attach), Ui.MUTED);
        attach.setOnClickListener(v -> openInApp(true));
        inputRow.addView(attach, new LinearLayout.LayoutParams(dp(40), dp(40)));
        RichEditText field = new RichEditText(svc);
        field.onImages(this::pasteImages); // from the keyboard's clipboard, say (issue #6)
        input = field;
        input.setHint(svc.getString(R.string.bubble_input_hint, "Buddy"));
        input.setHintTextColor(Ui.MUTED);
        input.setTextColor(Ui.TEXT);
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        input.setTypeface(Ui.sans(svc, 400));
        input.setMaxLines(4);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE
                | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES | InputType.TYPE_TEXT_FLAG_AUTO_CORRECT);
        input.setImeOptions(EditorInfo.IME_ACTION_SEND);
        input.setPaddingRelative(0, dp(10), 0, dp(10));
        input.setBackground(null);
        input.setOnFocusChangeListener((v, has) -> {
            refit();
            updatePaste();
        });
        root.getViewTreeObserver().addOnWindowFocusChangeListener(has -> {
            refit();
            updatePaste();
        });
        inputRow.addView(input, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        // Android shows no Copy/Paste menu in an overlay window: a Paste button shows while something is copied
        paste = Ui.iconButton(svc, R.drawable.ms_content_paste, svc.getString(R.string.bubble_paste), Ui.MUTED);
        paste.setOnClickListener(v -> pasteClip());
        paste.setVisibility(View.GONE);
        inputRow.addView(paste, new LinearLayout.LayoutParams(dp(40), dp(40)));
        svc.getSystemService(ClipboardManager.class).addPrimaryClipChangedListener(() -> input.post(this::updatePaste));
        // holding the field would select a word with handles, which misbehave over the keyboard in an overlay
        input.setLongClickable(false);
        mic = new ImageButton(svc);
        mic.setImageResource(R.drawable.ms_mic);
        mic.setContentDescription(svc.getString(R.string.bubble_talk));
        mic.setScaleType(ImageView.ScaleType.CENTER);
        mic.setOnClickListener(v -> toggleMic());
        inputRow.addView(mic, new LinearLayout.LayoutParams(dp(40), dp(40)));
        mic.setBackground(null); // idle look; micIdle() needs the current chat, which isn't loaded yet
        mic.setImageTintList(android.content.res.ColorStateList.valueOf(Ui.MUTED));
        final ImageButton send = new ImageButton(svc);
        send.setImageResource(R.drawable.ms_arrow_upward);
        send.setContentDescription(svc.getString(R.string.bubble_send));
        send.setScaleType(ImageView.ScaleType.CENTER);
        send.setOnClickListener(v -> send());
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(dp(40), dp(40));
        slp.setMarginStart(dp(4));
        inputRow.addView(send, slp);
        Runnable paintSend = () -> {
            boolean has = input.getText().toString().trim().length() > 0;
            send.setBackground(circle(has ? Ui.ACCENT : Ui.CARD2, 0, 0));
            send.setImageTintList(android.content.res.ColorStateList.valueOf(has ? Ui.ON_ACCENT : Ui.MUTED));
        };
        paintSend.run();
        input.addTextChangedListener(new android.text.TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence x, int a, int b, int c) {
            }

            @Override
            public void onTextChanged(CharSequence x, int a, int b, int c) {
            }

            @Override
            public void afterTextChanged(android.text.Editable e) {
                paintSend.run();
            }
        });
        panel.addView(inputRow, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        composer = inputRow;
        voiceBar = new VoiceBar(svc, new VoiceBar.Actions() {
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
        panel.addView(voiceBar, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    private LinearLayout composer;
    private VoiceBar voiceBar;

    private void showVoiceBar(boolean on) {
        if (on) voiceBar.begin(null);
        voiceBar.setVisibility(on ? View.VISIBLE : View.GONE);
        composer.setVisibility(on ? View.GONE : View.VISIBLE);
    }

    private void applyCollapsedParams() {
        Rect screen = screen();
        int[] pos = Prefs.bubblePos(svc);
        boolean right = pos[0] != 0;
        lp.width = WindowManager.LayoutParams.WRAP_CONTENT;
        lp.height = WindowManager.LayoutParams.WRAP_CONTENT;
        // Anchor to the nearest edge so the status pill grows toward the middle of the screen.
        lp.gravity = Gravity.TOP | (right ? Gravity.END : Gravity.START);
        collapsed.setLayoutDirection(right ? View.LAYOUT_DIRECTION_RTL : View.LAYOUT_DIRECTION_LTR);
        lp.x = dp(6);
        lp.y = Math.max(dp(40), Math.min(pos[1], screen.height() - dp(120)));
        lp.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | (passThrough ? WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE : 0);
    }

    /** Called when windows change; keeps the expanded panel above the on-screen keyboard. */
    /**
     * Keep the panel above the keyboard frame by frame: the window gets the keyboard's height as it
     * slides (insets animation), so the panel shrinks with it instead of jumping afterwards.
     */
    private void followKeyboard() {
        root.setWindowInsetsAnimationCallback(new android.view.WindowInsetsAnimation.Callback(
                android.view.WindowInsetsAnimation.Callback.DISPATCH_MODE_STOP) {
            @Override
            public android.view.WindowInsets onProgress(android.view.WindowInsets insets,
                                                         java.util.List<android.view.WindowInsetsAnimation> running) {
                applyIme(insets);
                return insets;
            }
        });
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            applyIme(insets);
            return insets;
        });
    }

    /** The keyboard's height as the window last reported it (0: hidden, or not reported). */
    private int insetIme;
    /**
     * How much the keyboard covered the panel when it was last up, per orientation and panel size (0: not seen yet),
     * and until when (uptimeMillis) that much room stays for a keyboard the panel just asked for (issue #7).
     */
    private final int[] lastKeyboard = new int[4];
    private long keyboardDue;
    private final Runnable keyboardLate = this::fitKeyboard;

    private int keyboardSlot() {
        Rect sc = screen();
        return (sc.width() > sc.height() ? 2 : 0) + ("compact".equals(Prefs.panelSize(svc)) ? 1 : 0);
    }

    private void applyIme(android.view.WindowInsets insets) {
        insetIme = insets.getInsets(android.view.WindowInsets.Type.ime()).bottom;
        fitKeyboard();
    }

    /** Where the accessibility service sees the keyboard's top (0: no keyboard). */
    void onKeyboardTop(int top) {
        if (top == imeTop) return;
        imeTop = top;
        fitKeyboard();
        // focus may still be settling (the panel just opened): check again shortly
        root.removeCallbacks(lateFit);
        if (top > 0) root.postDelayed(lateFit, 350);
    }

    private final Runnable lateFit = this::fitKeyboard;

    /**
     * Keep the composer above the keyboard: pad the open panel by the keyboard's height. The window's own
     * insets slide it smoothly, but they come late (or not at all) over some apps, so the keyboard's position
     * on screen counts too (only for Buddy's own text field, not when another app's is focused).
     */
    private void fitKeyboard() {
        if (!expanded || passThrough) return;
        int kb = insetIme;
        if (imeTop > 0 && input != null && input.hasFocus() && root.hasWindowFocus()) {
            int[] loc = new int[2];
            root.getLocationOnScreen(loc);
            kb = Math.max(kb, loc[1] + root.getHeight() - imeTop);
        }
        int slot = keyboardSlot();
        if (SystemClock.uptimeMillis() < keyboardDue) kb = Math.max(kb, lastKeyboard[slot]); // still on its way
        else if (kb > 0) lastKeyboard[slot] = kb;
        int pad = kb > 0 ? kb + dp(8) : 0;
        if (headButtons != null) headButtons.setVisibility(pad > 0 ? View.GONE : View.VISIBLE); // room for the chat
        if (root.getPaddingBottom() != pad) {
            root.setPaddingRelative(0, 0, 0, pad);
            scrollToEnd();
        }
    }

    /** Buddy's text field or window gained or lost focus: check the composer is above the keyboard. */
    private void refit() {
        root.removeCallbacks(lateFit);
        root.postDelayed(lateFit, 350);
    }

    private void applyExpandedParams() {
        // between the status bar and the navigation bar: an accessibility overlay is drawn above the navigation
        // bar, so a panel reaching into it hid Back, Home and Recents with 3-button navigation (issue #1)
        Rect usable = usable();
        // "full" goes edge to edge (Android's own Back swipe still works over it: the system takes it first);
        // "compact" keeps clear of the edges, past the system's gesture areas, for apps with their own side gestures
        // (issue #3)
        android.graphics.Insets edges = wm.getCurrentWindowMetrics().getWindowInsets()
                .getInsets(android.view.WindowInsets.Type.systemGestures());
        boolean compact = "compact".equals(Prefs.panelSize(svc));
        int side = compact ? Math.max(Math.max(edges.left, edges.right) + dp(8), usable.width() / 8) : 0;
        lp.width = usable.width() - 2 * side;
        // full height (in "compact", in portrait, that has Buddy's face above the panel and two buttons below it); the
        // keyboard is made room for by padding (fitKeyboard), only when it's for Buddy's own text field (not another
        // app's, e.g. while the agent types in Messenger)
        lp.height = Math.max(dp(220), usable.height() - dp(8) - dp(8));
        lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        lp.x = 0;
        lp.y = dp(8); // just under the status bar, so no strip of the app behind shows above the panel
        lp.flags = WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH
                | (passThrough ? WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE : 0) | dimFlags();
        lp.dimAmount = 0.45f;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) lp.setBlurBehindRadius(dp(16));
        // Buddy moves the panel itself (followKeyboard), so the system must not resize the window too
        lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING;
    }

    /**
     * "compact" in portrait looks like a messenger's chat heads: Buddy's face above the panel, Close and Open in the
     * app below it, and the screen behind dimmed. Only the look: the window stays clear of the screen edges, and a
     * touch outside it still reaches the app behind, so side gestures keep working (issue #3).
     */
    private boolean heads() {
        Rect sc = screen();
        return "compact".equals(Prefs.panelSize(svc)) && sc.height() > sc.width();
    }

    /** The open chat heads dim (and, where the phone can, blur) what's behind; never while an agent looks or acts. */
    private int dimFlags() {
        if (!expanded || passThrough || !heads()) return 0;
        int f = WindowManager.LayoutParams.FLAG_DIM_BEHIND;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && wm.isCrossWindowBlurEnabled()) {
            f |= WindowManager.LayoutParams.FLAG_BLUR_BEHIND;
        }
        return f;
    }

    /** The screen behind is dimmed now (an agent's screenshot waits for that to go). */
    boolean dimming() {
        return (lp.flags & WindowManager.LayoutParams.FLAG_DIM_BEHIND) != 0;
    }

    /** Puts the panel in the window: on its own, or between Buddy's face and the two buttons (heads()). */
    private void placePanel() {
        boolean heads = heads();
        if (panel.getParent() != null) ((android.view.ViewGroup) panel.getParent()).removeView(panel);
        root.removeAllViews();
        expandIcon.setVisibility(heads ? View.GONE : View.VISIBLE);
        closeIcon.setVisibility(heads ? View.GONE : View.VISIBLE);
        headButtons = null;
        if (!heads) {
            root.addView(panel);
            return;
        }
        LinearLayout col = new LinearLayout(svc);
        col.setOrientation(LinearLayout.VERTICAL);
        ImageView head = new ImageView(svc);
        Face.set(head, R.drawable.bubble_face);
        head.setContentDescription(svc.getString(R.string.bubble_close));
        head.setOnClickListener(v -> collapse());
        LinearLayout.LayoutParams hp = new LinearLayout.LayoutParams(dp(52), dp(52));
        hp.gravity = Gravity.CENTER_HORIZONTAL;
        hp.bottomMargin = dp(10);
        col.addView(head, hp);
        col.addView(panel, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        LinearLayout buttons = new LinearLayout(svc);
        buttons.addView(headButton(svc.getString(R.string.bubble_close), v -> collapse()));
        buttons.addView(new View(svc), new LinearLayout.LayoutParams(0, 1, 1));
        buttons.addView(headButton(svc.getString(R.string.bubble_open_in_app), v -> openInApp(false)));
        LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        bp.topMargin = dp(12);
        col.addView(buttons, bp);
        headButtons = buttons;
        root.addView(col);
    }

    private Button headButton(String label, View.OnClickListener l) {
        Button b = actionButton(label, Ui.CARD2);
        b.setBackground(rounded(Ui.CARD2, 24, Ui.OUTLINE, 1));
        b.setPaddingRelative(dp(20), dp(10), dp(20), dp(10));
        b.setOnClickListener(l);
        return b;
    }

    /**
     * Where the system puts the panel's window differs by phone and orientation (in landscape it can start at the
     * top of the screen instead of under the status bar, leaving a gap at the bottom): once it's placed, a full-height
     * panel is made to end just above the navigation bar.
     */
    private void fitPanelHeight() {
        root.getViewTreeObserver().addOnGlobalLayoutListener(new android.view.ViewTreeObserver.OnGlobalLayoutListener() {
            @Override
            public void onGlobalLayout() {
                root.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                if (!expanded) return;
                int[] loc = new int[2];
                root.getLocationOnScreen(loc);
                int h = Math.max(dp(220), usable().bottom - dp(8) - loc[1]);
                if (Math.abs(h - lp.height) <= 1) return;
                lp.height = h;
                wm.updateViewLayout(root, lp);
            }
        });
    }

    /** The screen rotated: size the panel (or place the bubble) for the new orientation. */
    void onRotated() {
        layoutOptions();
        if (expanded) {
            placePanel(); // compact's chat heads are for portrait only
            applyExpandedParams();
        } else {
            applyCollapsedParams();
        }
        wm.updateViewLayout(root, lp);
        if (expanded) {
            fitPanelHeight();
            scrollToEnd();
        }
    }

    void expand() {
        if (expanded) return;
        expanded = true;
        svc.marks().setPanelOpen(true);
        root.setVisibility(View.VISIBLE);
        badge.setVisibility(View.GONE);
        placePanel();
        // with Back/Home/Recents buttons the keyboard would cover them (it's layered above Buddy's overlay, so
        // above the navigation bar): open it only when the text field is tapped
        boolean keyboard = pendingConfirm == null && !sessions.current.running && !showingList && !navButtons();
        int room = keyboard ? lastKeyboard[keyboardSlot()] : 0;
        if (room > 0) {
            // room for the keyboard before it slides in, as much as it took last time: it can be slow to say where it
            // is, and the composer would sit under it until then (issue #7)
            keyboardDue = SystemClock.uptimeMillis() + 1200;
            root.setPaddingRelative(0, 0, 0, room + dp(8));
            root.removeCallbacks(keyboardLate);
            root.postDelayed(keyboardLate, 1250); // it didn't come after all: the room goes
        }
        applyExpandedParams();
        Voice.prepare(svc); // so the mic is quick
        wm.updateViewLayout(root, lp);
        fitPanelHeight();
        boolean right = Prefs.bubblePos(svc)[0] != 0;
        panel.setPivotX(headButtons != null ? lp.width / 2f : right ? lp.width : 0); // chat heads: from Buddy's face
        panel.setPivotY(0);
        panel.setScaleX(0.85f);
        panel.setScaleY(0.85f);
        panel.setAlpha(0f);
        panel.animate().scaleX(1f).scaleY(1f).alpha(1f)
                .setInterpolator(new OvershootInterpolator(0.9f)).setDuration(260).start();
        scrollToEnd();
        root.post(this::registerBack);
        updateViewing();
        if (keyboard) {
            input.requestFocus();
            input.postDelayed(() -> {
                InputMethodManager imm = svc.getSystemService(InputMethodManager.class);
                imm.showSoftInput(input, 0);
            }, 150);
        }
    }

    void collapse() {
        if (!expanded) return;
        if (Voice.listening()) Voice.stopListening();
        expanded = false;
        svc.marks().setPanelOpen(false);
        updateViewing();
        unregisterBack();
        hideKeyboard();
        keyboardDue = 0;
        root.removeCallbacks(keyboardLate);
        root.removeAllViews();
        root.setPaddingRelative(0, 0, 0, 0);
        root.addView(collapsed);
        applyCollapsedParams();
        wm.updateViewLayout(root, lp);
        applyEnabled();
        popIn();
        if (themeDirty) restyle();
    }

    private boolean themeDirty;

    /** Appearance changed: rebuild the panel now, or on the next collapse if it's open. */
    void applyTheme() {
        themeDirty = true;
        if (!expanded) restyle();
    }

    private void restyle() {
        themeDirty = false;
        if (Voice.listening()) Voice.stopListening();
        CharSequence draft = input.getText();
        buildPanel();
        input.setText(draft);
        pill.setTextColor(Ui.TEXT);
        pill.setTypeface(Ui.sans(svc, 500));
        pill.setBackground(rounded(Ui.BG, 18, Ui.ACCENT, 1));
        renderedFor = null;
        if (showingList) renderSessionList();
        renderCurrent();
    }

    /** Recents or Home opened (the launcher came to the front): fold the panel back into the bubble. */
    void onLauncherShown() {
        if (expanded && pendingConfirm == null) collapse();
    }

    /** The bubble can be turned off in the app; it then only appears for approval cards. */
    void applyEnabled() {
        if (locked && expanded && pendingConfirm == null) collapse();
        boolean show = !locked && (Prefs.bubbleEnabled(svc) || expanded || pendingConfirm != null || controlling || svc.paused());
        boolean wasHidden = root.getVisibility() != View.VISIBLE;
        root.setVisibility(show ? View.VISIBLE : View.GONE);
        if (show && wasHidden && !expanded) popIn();
        if (!show) showDismissTarget(false);
    }

    private boolean locked, controlling;

    /** An agent is operating the phone: show the bubble (even if hidden) in its "in control" state. */
    void setControlling(boolean on) {
        if (controlling == on) return;
        controlling = on;
        applyEnabled();
        refreshStatus();
    }

    /**
     * Paused or resumed (the hold menu, the pill, or on its own when the user took the phone): the pill and face say
     * so, and the bubble stays up while paused.
     */
    void onPauseChanged() {
        applyEnabled();
        refreshStatus();
    }

    /** Hide everything on the lock screen; the service calls this on screen off / unlock. */
    void setLocked(boolean on) {
        if (locked == on) return;
        locked = on;
        applyEnabled();
    }

    // ------------------------------------------------- drag-down to dismiss

    // A roomy window (so the grow animation never clips) holding a circle with a drawn ✕.
    // It is added before the bubble's window, so the bubble always stays on top of it.
    private FrameLayout dismissWindow;
    private ImageView dismissCircle;
    private boolean overTarget;

    // Full-screen, untouchable layer behind the target and bubble for the Tesla-coil effect.
    private ArcView arcs;

    private void createArcs() {
        arcs = new ArcView(svc);
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.TOP | Gravity.START;
        arcs.setVisibility(View.GONE);
        wm.addView(arcs, p);
    }

    private void feedArcs() {
        if (arcs == null || dismissCircle == null) return;
        arcs.setVisibility(View.VISIBLE);
        int[] t = targetCenter();
        arcs.update(lp.x + faceOffX, lp.y + lpToScreenY + faceOffY, t[0], t[1], overTarget);
    }

    /** Let the arcs die down, then hide the layer. */
    private void endArcs(boolean explode) {
        if (arcs == null) return;
        if (explode) arcs.explode();
        else arcs.stop();
        arcs.postDelayed(new Runnable() {
            @Override
            public void run() {
                if (arcs.idle()) arcs.setVisibility(View.GONE);
                else arcs.postDelayed(this, 300);
            }
        }, 300);
    }

    /** The dismiss target drawn as an electrode: dark core and cyan rim; white-blue when Buddy is on it. */
    private GradientDrawable electrode(boolean hot) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        d.setGradientRadius(dp(hot ? 34 : 32));
        d.setColors(hot ? new int[]{0xFFFFFFFF, 0xFFBDF4FF, 0xFF4F8BFF}
                : new int[]{0xFF0B1424, 0xFF13284A, 0xFF2F7DD1});
        d.setStroke(dp(hot ? 2 : 1), hot ? 0xFFE8FBFF : 0xCC7DF9FF);
        return d;
    }

    private void createDismissTarget() {
        dismissWindow = new FrameLayout(svc);
        dismissCircle = new ImageView(svc);
        dismissCircle.setImageResource(R.drawable.ic_dismiss);
        dismissCircle.setScaleType(ImageView.ScaleType.CENTER);
        dismissCircle.setBackground(electrode(false));
        dismissCircle.setElevation(dp(4));
        dismissWindow.addView(dismissCircle, new FrameLayout.LayoutParams(dp(60), dp(60), Gravity.CENTER));
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(dp(110), dp(110),
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL;
        p.y = dp(40);
        dismissWindow.setVisibility(View.GONE);
        wm.addView(dismissWindow, p);
    }

    private void showDismissTarget(boolean show) {
        if (dismissWindow == null) return;
        if (show && dismissWindow.getVisibility() == View.VISIBLE) return;
        if (show) {
            overTarget = false;
            dismissCircle.setScaleX(1f);
            dismissCircle.setScaleY(1f);
            dismissWindow.setAlpha(0f);
            dismissWindow.setVisibility(View.VISIBLE);
            dismissWindow.animate().alpha(1f).setDuration(120).start();
            dismissCircle.setBackground(electrode(false));
            dismissCircle.setColorFilter(0xFFFFFFFF);
            dismissCircle.setTranslationY(dp(70));
            dismissCircle.setScaleX(0.6f);
            dismissCircle.setScaleY(0.6f);
            dismissCircle.animate().translationY(0f).scaleX(1f).scaleY(1f)
                    .setInterpolator(new OvershootInterpolator(2.2f)).setDuration(320).start();
        } else {
            dismissWindow.animate().cancel();
            dismissCircle.animate().cancel();
            dismissWindow.setAlpha(0f);
            dismissWindow.setVisibility(View.GONE);
            dismissCircle.setTranslationY(0f);
        }
    }

    /** Centre of the target, measured on screen (accounts for nav bar and insets). */
    private int[] targetCenter() {
        int[] loc = new int[2];
        dismissCircle.getLocationOnScreen(loc);
        return new int[]{loc[0] + dismissCircle.getWidth() / 2, loc[1] + dismissCircle.getHeight() / 2};
    }

    /** Offset from the window position (lp.x, lp.y) to the face's centre on screen, measured at drag start. */
    private int faceOffX, faceOffY, lpToScreenY;

    /** Is the bubble's top-left (x,y) near the target? Highlights it and returns true when close. */
    private boolean overDismiss(int x, int y) {
        if (dismissWindow == null || dismissWindow.getVisibility() != View.VISIBLE) return false;
        int[] t = targetCenter();
        boolean over = Math.hypot(x + faceOffX - t[0], y + lpToScreenY + faceOffY - t[1]) < dp(90);
        if (over != overTarget) {
            overTarget = over;
            dismissCircle.animate().scaleX(over ? 1.28f : 1f).scaleY(over ? 1.28f : 1f)
                    .setInterpolator(new OvershootInterpolator(3f)).setDuration(220).start();
            dismissCircle.setBackground(electrode(over));
            dismissCircle.setColorFilter(over ? 0xFF16305C : 0xFFFFFFFF); // dark ✕ on white-blue, light ✕ on the electrode
            showFace(over ? R.drawable.bubble_face_brace : R.drawable.bubble_face_fired);
            if (over) vibrate(15);
        }
        return over;
    }

    private void dismissToNotification() {
        vibrate(40);
        final View head = (View) face.getParent();
        // spin and shrink into the target, which pops and drops away
        head.animate().scaleX(0f).scaleY(0f).rotation(180f).alpha(0f)
                .setInterpolator(new AccelerateInterpolator(1.6f)).setDuration(240)
                .withEndAction(() -> {
                    Prefs.setBubbleEnabled(svc, false);
                    HiddenNotice.show(svc);
                    applyCollapsedParams();
                    wm.updateViewLayout(root, lp);
                    applyEnabled();
                    head.setScaleX(1f);
                    head.setScaleY(1f);
                    head.setRotation(0f);
                    head.setAlpha(1f);
                    setWorking(!svc.paused() && (running || controlling), true);
                }).start();
        dismissCircle.animate().scaleX(1.45f).scaleY(1.45f).setDuration(110)
                .setInterpolator(new DecelerateInterpolator())
                .withEndAction(() -> dismissCircle.animate().scaleX(0f).scaleY(0f).translationY(dp(60))
                        .setInterpolator(new AccelerateInterpolator(1.8f)).setDuration(200)
                        .withEndAction(() -> showDismissTarget(false)).start())
                .start();
    }

    // ------------------------------------------------------------ motion

    private ValueAnimator windowAnim;

    /** Animate the bubble window to (x, y) in window coordinates. */
    private void animateWindowTo(int x, int y, long ms, android.animation.TimeInterpolator in, Runnable end) {
        if (windowAnim != null) windowAnim.cancel();
        final int x0 = lp.x, y0 = lp.y;
        windowAnim = ValueAnimator.ofFloat(0f, 1f);
        windowAnim.setDuration(ms);
        windowAnim.setInterpolator(in);
        windowAnim.addUpdateListener(a -> {
            float f = (float) a.getAnimatedValue();
            lp.x = Math.round(x0 + (x - x0) * f);
            lp.y = Math.round(y0 + (y - y0) * f);
            try {
                wm.updateViewLayout(root, lp);
            } catch (Exception ignored) {
            }
            followWaves();
        });
        windowAnim.addListener(new android.animation.AnimatorListenerAdapter() {
            boolean cancelled;

            @Override
            public void onAnimationCancel(android.animation.Animator a) {
                cancelled = true;
            }

            @Override
            public void onAnimationEnd(android.animation.Animator a) {
                if (!cancelled && end != null) end.run();
            }
        });
        windowAnim.start();
    }

    /** Pop the bubble in from nothing (after unhiding, or closing the panel). */
    private void popIn() {
        View head = (View) face.getParent();
        head.setScaleX(0.2f);
        head.setScaleY(0.2f);
        head.setAlpha(0f);
        head.animate().scaleX(1f).scaleY(1f).alpha(1f).rotation(0f)
                .setInterpolator(new OvershootInterpolator(2.6f)).setDuration(380).start();
    }

    /*
     * Android 16 (targetSdk 36) delivers Back through OnBackInvokedDispatcher instead of
     * KEYCODE_BACK, so the panel registers a callback while it is open. Back first closes
     * the keyboard, then denies a pending approval, then collapses the panel.
     */
    private final Runnable outsideCollapse = () -> {
        if (expanded && pendingConfirm == null) collapse();
    };
    private OnBackInvokedCallback backCallback;
    private OnBackInvokedDispatcher backDispatcher;

    private void registerBack() {
        // Android 13+ only; older versions deliver KEYCODE_BACK to dispatchKeyEvent.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return;
        if (!expanded || backDispatcher != null) return;
        if (backCallback == null) {
            backCallback = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
                    ? new OnBackAnimationCallback() {
                        @Override
                        public void onBackStarted(BackEvent e) {
                            root.removeCallbacks(outsideCollapse); // it's a back gesture, not a tap outside
                        }

                        @Override
                        public void onBackInvoked() {
                            onBack();
                        }
                    }
                    : (OnBackInvokedCallback) this::onBack;
        }
        backDispatcher = root.findOnBackInvokedDispatcher();
        if (backDispatcher != null)
            // Overlay priority so Buddy sees every Back (including gesture start) and closes the keyboard itself.
            backDispatcher.registerOnBackInvokedCallback(OnBackInvokedDispatcher.PRIORITY_OVERLAY, backCallback);
    }

    private void unregisterBack() {
        if (backDispatcher != null) backDispatcher.unregisterOnBackInvokedCallback(backCallback);
        backDispatcher = null;
    }

    private boolean keyboardVisible() {
        if (imeTop > 0) return true;
        WindowInsets insets = root.getRootWindowInsets();
        return insets != null && insets.isVisible(WindowInsets.Type.ime());
    }

    private void onBack() {
        root.removeCallbacks(outsideCollapse);
        if (keyboardVisible()) hideKeyboard();
        else if (changing) endChange();
        else if (pendingConfirm != null) answerConfirm(false);
        else collapse();
    }

    private void hideKeyboard() {
        InputMethodManager imm = svc.getSystemService(InputMethodManager.class);
        imm.hideSoftInputFromWindow(input.getWindowToken(), 0);
        input.clearFocus();
    }

    /** Make the overlay invisible and untouchable while a gesture or screenshot runs. */
    void setPassThrough(boolean on) {
        if (passThrough == on) return;
        passThrough = on;
        root.setAlpha(on ? 0f : 1f);
        if (on) lp.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        else lp.flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        lp.flags = (lp.flags & ~(WindowManager.LayoutParams.FLAG_DIM_BEHIND | WindowManager.LayoutParams.FLAG_BLUR_BEHIND))
                | dimFlags();
        try {
            wm.updateViewLayout(root, lp);
        } catch (Exception ignored) {
        }
    }

    // ------------------------------------------------------------ actions

    private void send() {
        String text = input.getText().toString().trim();
        if (text.isEmpty()) return;
        if ((changing || asking) && pendingConfirm != null) { // typing while a question waits answers it
            input.setText("");
            answerChange(text);
            return;
        }
        String err = hub.send(sessions.current, text);
        if (err != null) {
            toast(err);
            return;
        }
        input.setText("");
        collapse();
    }

    private ImageButton mic;
    private RadialMenu radial;
    private HoldRing holdRing;
    private boolean wasWorking;

    /** 2. A ring that fills while the bubble is held: the menu opens when it's full. */
    private final class HoldRing extends View {
        private final android.graphics.Paint p = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
        private float progress;
        private ValueAnimator anim;

        HoldRing(android.content.Context c) {
            super(c);
            p.setStyle(android.graphics.Paint.Style.STROKE);
            p.setStrokeCap(android.graphics.Paint.Cap.ROUND);
            p.setStrokeWidth(dp(4));
        }

        void begin(long ms) {
            cancel();
            anim = ValueAnimator.ofFloat(0f, 1f);
            anim.setStartDelay(90); // a quick tap shows nothing
            anim.setDuration(ms - 90);
            anim.addUpdateListener(a -> {
                progress = (float) a.getAnimatedValue();
                invalidate();
            });
            anim.start();
        }

        void cancel() {
            if (anim != null) anim.cancel();
            progress = 0;
            invalidate();
        }

        @Override
        protected void onDraw(android.graphics.Canvas c) {
            if (progress <= 0) return;
            float in = dp(3);
            android.graphics.RectF r = new android.graphics.RectF(in, in, getWidth() - in, getHeight() - in);
            p.setColor(Theme.withAlpha(Ui.ACCENT, 0x40));
            c.drawArc(r, 0, 360, false, p);
            p.setColor(Ui.ACCENT);
            c.drawArc(r, -90, 360 * progress, false, p);
        }
    }

    /** 1. The first few times a task starts: the hold menu peeks out ("Hold me") and slides back. */
    private void maybePeek() {
        if (expanded || Prefs.menuHinted(svc) || Prefs.peekCount(svc) >= 3 || radial.isOpen() || root.getVisibility() != View.VISIBLE) return;
        Prefs.addPeek(svc);
        face.postDelayed(() -> {
            if (expanded || radial.isOpen()) return;
            int[] loc = new int[2];
            face.getLocationOnScreen(loc);
            int cx = loc[0] + face.getWidth() / 2, cy = loc[1] + face.getHeight() / 2;
            java.util.List<RadialMenu.Action> acts = new java.util.ArrayList<>();
            acts.add(new RadialMenu.Action(R.drawable.ic_stop, svc.getString(R.string.menu_peek_stop), true, () -> { }));
            acts.add(new RadialMenu.Action(R.drawable.ms_mic, svc.getString(R.string.menu_peek_add), false, () -> { }));
            acts.add(new RadialMenu.Action(R.drawable.ms_volume_off, svc.getString(R.string.menu_peek_voice), false, () -> { }));
            radial.peek(cx, cy, cx > screen().width() / 2, acts);
        }, 900);
    }

    /** 3. Tapped (not held) while it talks or works: say, a few times, that holding gives Stop. */
    private void maybeTip() {
        if (Prefs.menuHinted(svc) || Prefs.tipCount(svc) >= 3) return;
        boolean speaking = Voice.isSpeaking(), working = running || controlling;
        if (!speaking && !working) return;
        Prefs.addTip(svc);
        toast(svc.getString(speaking ? R.string.bubble_tip_stop_voice : R.string.bubble_tip_hold_menu));
    }

    /** Hold the bubble: what can be done right now, fanned out around it. */
    private void openMenu() {
        vibrate(30);
        Prefs.setMenuHinted(svc);
        java.util.List<RadialMenu.Action> acts = new java.util.ArrayList<>();
        boolean paused = svc.paused(), working = running || controlling || paused;
        if (working) acts.add(new RadialMenu.Action(R.drawable.ic_stop, svc.getString(R.string.menu_stop_task), true, () -> {
            if (controlling || paused) svc.takeBackControl();
            else {
                hub.stopAll();
                toast(svc.getString(R.string.bubble_stopping));
            }
        }));
        if (paused) acts.add(new RadialMenu.Action(R.drawable.ic_play, svc.getString(R.string.menu_resume), false, svc::resume));
        else if (working) acts.add(new RadialMenu.Action(R.drawable.ic_pause, svc.getString(R.string.menu_pause), false, svc::pause));
        if (hub.runningCount() > 0) acts.add(new RadialMenu.Action(R.drawable.ms_mic, svc.getString(R.string.menu_add_instruction), false, this::dictateNote));
        if (Voice.isSpeaking()) acts.add(new RadialMenu.Action(R.drawable.ms_volume_off, svc.getString(R.string.menu_stop_voice), false, Voice::stopSpeaking));
        // listens with the chat closed, like Tap the bubble to talk (opening the chat first lost the mic)
        if (hub.runningCount() == 0) acts.add(new RadialMenu.Action(R.drawable.ms_mic, svc.getString(R.string.bubble_talk), false, this::talk));
        acts.add(new RadialMenu.Action(R.drawable.ms_forum, svc.getString(R.string.menu_open_chat), false, this::expand));
        acts.add(new RadialMenu.Action(R.drawable.ms_close, svc.getString(R.string.menu_hide), false, this::dismissToNotification));
        int[] loc = new int[2];
        face.getLocationOnScreen(loc);
        int cx = loc[0] + face.getWidth() / 2, cy = loc[1] + face.getHeight() / 2;
        ((View) face.getParent()).animate().scaleX(1.12f).scaleY(1.12f).setDuration(160).start();
        radial.open(cx, cy, cx > screen().width() / 2, acts,
                () -> ((View) face.getParent()).animate().scaleX(1f).scaleY(1f).setDuration(160).start());
    }

    /** The bubble's 🎙 while a task runs: say something, it goes to the working agent (ChatHub.addNote). */
    private void dictateNote() {
        if (noteListening) {
            Voice.finishListening();
            return;
        }
        final Sessions.Session ses = hub.latestRunning();
        if (ses == null) {
            toast(svc.getString(R.string.bubble_nothing_running));
            return;
        }
        if (!Voice.micAllowed(svc)) {
            toast(svc.getString(R.string.bubble_allow_mic_tap_mic));
            svc.startActivity(new android.content.Intent(svc, MainActivity.class)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("askMic", true));
            return;
        }
        String err = Voice.listen(svc, new Voice.Listener() {
            @Override
            public void onPartial(String text) {
                if (!text.isEmpty()) noteStatus(svc.getString(R.string.bubble_heard, text));
            }

            @Override
            public void onFinal(String text) {
                noteDone();
                String e = hub.addNote(ses, text);
                toast(e != null ? e : svc.getString(R.string.bubble_note_added, ses.brainName()));
            }

            @Override
            public void onEnd(String message) {
                noteDone();
                if (message != null) toast(message);
            }

            @Override
            public void onStatus(String status) {
                noteStatus(status == null ? svc.getString(R.string.bubble_listening_tap_done) : status);
            }
        });
        if (err != null) {
            toast(err);
            return;
        }
        noteListening = true;
        noteStatus(svc.getString(R.string.bubble_listening_tap_done));
    }

    // ------------------------------------------------- tap to talk, with the chat closed

    private boolean talkListening;
    private VoiceWaves waves;

    /** Listen without opening the chat: waves under the bubble, the words beside it, sent when you pause. */
    private void talk() {
        if (!Voice.micAllowed(svc)) {
            toast(svc.getString(R.string.bubble_allow_mic_tap_bubble));
            svc.startActivity(new android.content.Intent(svc, MainActivity.class)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("askMic", true));
            return;
        }
        String err = Voice.listen(svc, new Voice.Listener() {
            @Override
            public void onPartial(String text) {
                if (!text.isEmpty()) noteStatus(svc.getString(R.string.bubble_heard, text));
            }

            @Override
            public void onFinal(String text) {
                talkDone();
                Sessions.Session ses = sessions.current;
                if (ses == null) {
                    ses = hub.create(Prefs.backend(svc));
                    hub.select(ses);
                }
                String e = hub.send(ses, text);
                if (e != null) toast(e);
            }

            @Override
            public void onEnd(String message) {
                talkDone();
                if (message != null) toast(message);
            }

            @Override
            public void onLevel(float level) {
                if (waves != null) waves.level(level);
            }

            @Override
            public void onStatus(String status) {
                if (waves != null) waves.setLive(status == null);
                noteStatus(status == null ? svc.getString(R.string.bubble_listening) : status);
            }
        });
        if (err != null) {
            toast(err);
            return;
        }
        talkListening = true;
        noteListening = true; // the pill shows what's heard
        noteStatus(svc.getString(R.string.bubble_listening));
        showWaves(true);
    }

    private void talkDone() {
        talkListening = false;
        showWaves(false);
        noteDone();
    }

    /** The waves' own small window, centred under the bubble's face; touches go through it. */
    private void showWaves(boolean on) {
        if (!on) {
            if (waves != null) {
                waves.setVisibility(View.GONE);
                try {
                    wm.removeView(waves);
                } catch (Exception ignored) {
                }
                waves = null;
            }
            return;
        }
        showWaves(false);
        waves = new VoiceWaves(svc);
        waves.setLive(true);
        int w = dp(150), h = dp(44);
        int[] loc = new int[2];
        face.getLocationOnScreen(loc);
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(w, h,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.TOP | Gravity.START;
        p.x = Math.max(0, Math.min(screen().width() - w, loc[0] + face.getWidth() / 2 - w / 2));
        p.y = loc[1] + face.getHeight() + dp(8);
        wm.addView(waves, p);
    }

    /** Moves the waves along when the bubble is dragged or snaps to an edge while listening. */
    private void followWaves() {
        if (waves == null) return;
        face.postOnAnimation(() -> {
            if (waves == null) return;
            WindowManager.LayoutParams p = (WindowManager.LayoutParams) waves.getLayoutParams();
            int[] loc = new int[2];
            face.getLocationOnScreen(loc);
            p.x = Math.max(0, Math.min(screen().width() - p.width, loc[0] + face.getWidth() / 2 - p.width / 2));
            p.y = loc[1] + face.getHeight() + dp(8);
            try {
                wm.updateViewLayout(waves, p);
            } catch (Exception ignored) {
            }
        });
    }

    private void noteStatus(String s) {
        pill.setText(s);
        pill.setVisibility(View.VISIBLE);
    }

    private void noteDone() {
        noteListening = false;
        refreshStatus();
    }

    /** Talk in the bubble: tap, speak, it sends when you stop. Tap again to send right away. */
    private void toggleMic() {
        if (Voice.listening()) {
            Voice.finishListening();
            return;
        }
        if (!Voice.micAllowed(svc)) {
            // only an activity can ask for the microphone permission
            toast(svc.getString(R.string.bubble_allow_mic_tap_mic));
            svc.startActivity(new android.content.Intent(svc, MainActivity.class)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK).putExtra("askMic", true));
            collapse();
            return;
        }
        final String before = input.getText().toString().trim();
        hideKeyboard();
        showVoiceBar(true);
        String err = Voice.listen(svc, new Voice.Listener() {
            @Override
            public void onPartial(String text) {
                voiceBar.setHeard(text);
            }

            @Override
            public void onFinal(String text) {
                input.setText(before.isEmpty() ? text : before + " " + text);
                micIdle();
                send();
            }

            @Override
            public void onEnd(String message) {
                micIdle();
                if (message != null) toast(message);
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
            toast(err);
        }
    }

    private void micIdle() {
        if (voiceBar != null) showVoiceBar(false);
        mic.setBackground(null);
        mic.setImageTintList(android.content.res.ColorStateList.valueOf(Ui.MUTED));
        input.setHint(changing ? svc.getString(R.string.card_change_hint)
                : svc.getString(R.string.bubble_input_hint, sessions.current.brainName()));
    }

    private void stop() {
        if (!sessions.current.running) toast(svc.getString(R.string.bubble_nothing_running_chat));
        else hub.stop(sessions.current);
    }

    private void newChat(String backend) {
        hub.create(backend);
        showingList = false;
        renderCurrent();
    }

    private void deleteChat(Sessions.Session ses) {
        hub.delete(ses);
        renderCurrent();
        if (showingList) renderSessionList();
    }

    /** The chats list or the new-chat picker is showing: no composer, like the app's Chats tab. */
    private void showListMode() {
        listScroll.setVisibility(showingList ? View.VISIBLE : View.GONE);
        scroll.setVisibility(showingList ? View.GONE : View.VISIBLE);
        if (composer != null && voiceBar != null && voiceBar.getVisibility() != View.VISIBLE) {
            composer.setVisibility(showingList ? View.GONE : View.VISIBLE);
        }
    }

    /** "+": pick the agent for a new chat, as in the app's New chat sheet. */
    private boolean picking;

    /** The chip: pick this chat's model and effort (the next message uses them). */
    private boolean pickingModel;

    private void showModelPicker() {
        hideKeyboard();
        showingList = true;
        picking = false;
        pickingModel = true;
        renderSessionList();
        showListMode();
        updateViewing();
        updateHeader();
    }

    private void closePicker() {
        pickingModel = false;
        showingList = false;
        renderCurrent();
        updateHeader();
    }

    private void renderModelPicker() {
        final Sessions.Session ses = sessions.current;
        TextView note = Ui.text(svc, svc.getString(R.string.bubble_model_note), 13, Ui.MUTED);
        note.setPaddingRelative(dp(8), dp(4), dp(8), dp(10));
        sessionList.addView(note);
        String[][] efforts = Models.efforts(ses.backend);
        if (efforts != null) {
            TextView el = Ui.sectionLabel(svc, svc.getString(R.string.bubble_effort));
            el.setPaddingRelative(dp(8), dp(4), 0, dp(2));
            sessionList.addView(el);
            TextView es = Ui.text(svc, svc.getString(R.string.bubble_effort_note), 13, Ui.MUTED);
            es.setPaddingRelative(dp(8), 0, dp(8), dp(8));
            sessionList.addView(es);
            LinearLayout seg = Ui.segmented(svc, efforts, ses.effort, false, v -> {
                hub.setEffort(ses, v);
                closePicker();
            });
            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            sp.leftMargin = sp.rightMargin = dp(4);
            sessionList.addView(seg, sp);
        }
        TextView ml = Ui.sectionLabel(svc, svc.getString(R.string.bubble_model));
        ml.setPaddingRelative(dp(8), dp(16), 0, dp(4));
        sessionList.addView(ml);
        Models.refresh(svc);
        for (final String[] o : Models.forBackend(ses.backend)) {
            boolean on = o[0].equals(ses.model);
            LinearLayout r = Ui.listRow(svc);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPaddingRelative(dp(8), dp(10), dp(8), dp(10));
            FrameLayout radio = new FrameLayout(svc);
            radio.setBackground(rounded(0x00000000, 11, on ? Ui.ACCENT : Ui.MUTED, 2));
            if (on) {
                View in = new View(svc);
                in.setBackground(Ui.oval(Ui.ACCENT));
                radio.addView(in, new FrameLayout.LayoutParams(dp(10), dp(10), Gravity.CENTER));
            }
            r.addView(radio, new LinearLayout.LayoutParams(dp(22), dp(22)));
            LinearLayout t = new LinearLayout(svc);
            t.setOrientation(LinearLayout.VERTICAL);
            t.setPaddingRelative(dp(14), 0, 0, 0);
            t.addView(Ui.title(svc, o[1], 15));
            t.addView(Ui.single(Ui.monoText(svc, o[0].isEmpty() ? Models.defaultHint(ses) : o[0], 12, Ui.MUTED)));
            r.addView(t, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
            r.setOnClickListener(v -> {
                if (!o[0].equals(ses.model)) hub.setModel(ses, o[0]);
                closePicker();
            });
            sessionList.addView(r, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        }
    }

    private void showNewChat() {
        hideKeyboard();
        showingList = true;
        pickingModel = false;
        picking = true;
        renderSessionList();
        showListMode();
        updateViewing();
        updateHeader();
    }

    // ------------------------------------------------------------ sessions

    private void toggleSessionList() {
        showingList = !showingList || picking || pickingModel;
        picking = false;
        pickingModel = false;
        if (showingList) {
            hideKeyboard();
            renderSessionList();
        }
        showListMode();
        updateViewing();
        updateHeader();
    }

    private void renderSessionList() {
        sessionList.removeAllViews();
        if (pickingModel) {
            renderModelPicker();
            return;
        }
        if (picking) {
            renderNewChat();
            return;
        }
        for (final Sessions.Session ses : sessions.all) sessionList.addView(chatRow(ses), new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
    }

    /** A chat, laid out like the app's Chats tab: agent tile, title and time, agent · model, last message. */
    private View chatRow(final Sessions.Session ses) {
        LinearLayout r = Ui.listRow(svc);
        r.setPaddingRelative(dp(8), dp(12), dp(8), dp(12));
        r.addView(Ui.agentTile(svc, ses.backend));
        LinearLayout col = new LinearLayout(svc);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPaddingRelative(dp(14), 0, 0, 0);
        LinearLayout first = new LinearLayout(svc);
        first.setGravity(Gravity.CENTER_VERTICAL);
        first.addView(Ui.single(Ui.title(svc, ses.label(), 15)), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        TextView time = Ui.monoText(svc, MainActivity.ago(ses.updated), 12, Ui.MUTED);
        time.setPaddingRelative(dp(8), 0, 0, 0);
        first.addView(time);
        if (ses.unread) {
            View d = Ui.dot(svc, Ui.OK, 8);
            ((LinearLayout.LayoutParams) d.getLayoutParams()).setMarginStart(dp(6));
            first.addView(d);
        }
        col.addView(first);
        col.addView(Ui.single(Ui.monoText(svc, Account.cliOf(ses.backend) + " · "
                + Models.shownModel(ses).toLowerCase(Ui.locale(svc)), 12, Ui.MUTED)));
        if (ses.running) {
            TextView st = Ui.single(Ui.monoText(svc, ses.status.isEmpty() ? svc.getString(R.string.bubble_chat_row_working) : ses.status, 12, Ui.ACCENT_TEXT));
            st.setPaddingRelative(0, dp(4), 0, dp(6));
            col.addView(st);
            col.addView(Ui.thinBar(svc));
        } else {
            String last = "";
            for (int i = ses.messages.size() - 1; i >= 0; i--) {
                Sessions.Msg m = ses.messages.get(i);
                if (m.kind == Kind.BUDDY || m.kind == Kind.USER) {
                    String t = Ui.linkText(m.label()).replace('\n', ' ');
                    last = m.kind == Kind.USER ? svc.getString(R.string.bubble_chat_row_you, t) : t;
                    break;
                }
            }
            if (!last.isEmpty()) {
                TextView l = Ui.text(svc, last, 14, Ui.MUTED);
                l.setMaxLines(2);
                l.setEllipsize(TextUtils.TruncateAt.END);
                l.setPaddingRelative(0, dp(4), 0, 0);
                col.addView(l);
            }
        }
        r.addView(col, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        r.setOnClickListener(v -> {
            hub.select(ses);
            showingList = false;
            renderCurrent();
        });
        // long-press to delete, as in the app (asked right in the row: the bubble can't show a dialog)
        r.setOnLongClickListener(v -> {
            vibrate(20);
            r.removeAllViews();
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.addView(Ui.text(svc, svc.getString(R.string.bubble_delete_chat), 15, Ui.TEXT), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
            Button cancel = actionButton(svc.getString(android.R.string.cancel), Ui.CARD);
            cancel.setOnClickListener(x -> renderSessionList());
            r.addView(cancel);
            Button del = actionButton(svc.getString(R.string.bubble_delete), Ui.ERR_SOFT);
            del.setTextColor(Ui.ERR);
            del.setOnClickListener(x -> deleteChat(ses));
            LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            dlp.setMarginStart(dp(8));
            r.addView(del, dlp);
            r.setOnClickListener(null);
            r.setOnLongClickListener(null);
            return true;
        });
        return r;
    }

    /** The agents for a new chat, like the app's New chat sheet (the ones not set up open their setup). */
    private void renderNewChat() {
        TextView h = Ui.title(svc, svc.getString(R.string.bubble_new_chat), 18);
        h.setPaddingRelative(dp(8), dp(8), 0, dp(8));
        sessionList.addView(h);
        String def = Prefs.backend(svc);
        for (Account acc : Account.ALL) {
            boolean ready = SetupState.signedIn(svc, acc.agent);
            LinearLayout r = Ui.listRow(svc);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPaddingRelative(dp(8), dp(12), dp(8), dp(12));
            if (!ready) r.setAlpha(0.45f);
            r.addView(Ui.agentTile(svc, acc.agent));
            LinearLayout t = new LinearLayout(svc);
            t.setOrientation(LinearLayout.VERTICAL);
            t.setPaddingRelative(dp(14), 0, 0, 0);
            LinearLayout tr = new LinearLayout(svc);
            tr.setGravity(Gravity.CENTER_VERTICAL);
            tr.addView(Ui.title(svc, acc.name(), 15));
            if (acc.agent.equals(def)) {
                TextView tag = Ui.monoText(svc, svc.getString(R.string.bubble_agent_default), 11, Ui.ACCENT_TEXT);
                tag.setPaddingRelative(dp(6), dp(1), dp(6), dp(1));
                tag.setBackground(rounded(Ui.ACCENT_SOFT, 6, 0, 0));
                LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                tp.setMarginStart(dp(8));
                tr.addView(tag, tp);
            }
            t.addView(tr);
            t.addView(Ui.text(svc, ready ? acc.tagline() : svc.getString(SetupState.installed(svc, acc.agent)
                    ? R.string.bubble_agent_not_signed_in : R.string.bubble_agent_not_installed), 13, Ui.MUTED));
            r.addView(t, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
            r.addView(Ui.icon(svc, R.drawable.ms_chevron_right, Ui.MUTED, 22));
            r.setOnClickListener(v -> {
                picking = false;
                if (ready) {
                    newChat(acc.agent);
                } else {
                    collapse();
                    svc.startActivity(new android.content.Intent(svc, SignInActivity.class).putExtra("agent", acc.agent)
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK));
                }
            });
            sessionList.addView(r, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        }
    }

    /** Show the current chat's history in the panel. */
    private void renderCurrent() {
        if (!showingList) picking = pickingModel = false;
        showListMode();
        messages.removeAllViews();
        log = null;
        renderedFor = sessions.current;
        Sessions.Session ses = sessions.current;
        if (ses.messages.isEmpty()) {
            TextView hint = Ui.text(svc, svc.getString(R.string.bubble_empty_chat, ses.brainName()), 14, Ui.MUTED);
            hint.setGravity(Gravity.CENTER);
            hint.setPaddingRelative(dp(24), dp(32), dp(24), 0);
            hint.setTag("hint"); // removed by the first message
            messages.addView(hint, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT));
        }
        for (Sessions.Msg m : ses.messages) showMessage(m.kind, m.label());
        if (log != null && !ses.running) log.finish();
        input.setHint(svc.getString(R.string.bubble_input_hint, ses.brainName()));
        updateViewing();
        refreshStatus();
        scrollToEnd();
    }

    private Sessions.Session renderedFor;

    /** Tell the hub which chat the open panel is showing, so its replies aren't marked unread. */
    private void updateViewing() {
        Sessions.Session now = expanded && !showingList ? sessions.current : null;
        if (now == viewingSes) return;
        if (viewingSes != null) hub.setViewing(viewingSes, false);
        viewingSes = now;
        if (now != null) hub.setViewing(now, true);
    }

    private void updateHeader() {
        Sessions.Session ses = sessions.current;
        header.setText(pickingModel ? svc.getString(R.string.bubble_model_effort_title)
                : picking ? svc.getString(R.string.bubble_new_chat)
                : showingList ? svc.getString(R.string.bubble_chats_title) : ses.label());
        String summary = Models.summary(ses);
        subtitle.setText(Prefs.devMode(svc) ? svc.getString(R.string.bubble_summary_dev, summary) : summary);
        modelChip.setVisibility(showingList ? View.GONE : View.VISIBLE);
        boolean working = !showingList && (ses.running || controlling);
        strip.setVisibility(working ? View.VISIBLE : View.GONE);
        stripText.setText(!ses.status.isEmpty() ? ses.status
                : svc.getString(ses.running ? R.string.bubble_working : R.string.bubble_using_phone));
        if (log != null) log.setLive(ses.running);
    }

    /** Open this chat in the app (with what's typed so far); attach: straight to the attachment sheet. */
    private void openInApp(boolean attach) {
        openInApp(attach, null);
    }

    /** files: attachments already in Buddy's storage (pasted images), to go with the draft. */
    private void openInApp(boolean attach, java.util.ArrayList<String> files) {
        String draft = input.getText().toString();
        android.content.Intent i = new android.content.Intent(svc, ChatActivity.class)
                .putExtra("id", sessions.current.id)
                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
        if (!draft.trim().isEmpty()) i.putExtra("draft", draft);
        if (attach) i.putExtra("attach", true);
        if (files != null) i.putStringArrayListExtra("files", files);
        input.setText("");
        collapse();
        svc.startActivity(i);
    }

    // ------------------------------------------------------- hub listener

    @Override
    public void onChatsChanged() {
        if (renderedFor != sessions.current && !showingList) {
            renderCurrent();
            return;
        }
        refreshStatus();
        if (showingList) renderSessionList();
    }

    @Override
    public void onMessage(Sessions.Session ses, Sessions.Msg msg) {
        if (ses == sessions.current && renderedFor == ses) showMessage(msg.kind, msg.label());
        if (msg.kind == Kind.BUDDY && !(expanded && ses == sessions.current && !showingList)) vibrate(30);
    }

    /** The approval being asked right now (shown inline in the chat screen too), or null. */
    String pendingConfirmText() {
        return pendingConfirm == null ? null : confirmText.getText().toString();
    }

    /** The answers of the question being asked right now, or null when it's an approval (or nothing). */
    java.util.List<String> pendingOptions() {
        return pendingConfirm != null && asking ? askOptions : null;
    }

    /** An answer picked on the chat screen's inline question card. */
    void pickFromApp(String option) {
        if (pendingConfirm != null && asking) answerOption(option);
    }

    /** Answer from the chat screen's inline approval card. */
    void answerFromApp(boolean approved) {
        if (pendingConfirm != null) answerConfirm(approved);
    }

    /** "Change" from the chat screen's inline approval card. */
    void changeFromApp(String change) {
        if (pendingConfirm != null) answerChange(change);
    }

    /** The user tapped Change: the composer (typing or the mic) now answers the card with what to change. */
    private boolean changing;

    private void startChange() {
        if (pendingConfirm == null) return;
        stopCountdown();
        changing = true;
        // the answer buttons make the card too tall to leave the text field above the keyboard
        confirmOptions.setVisibility(View.GONE);
        confirmOwn.setVisibility(View.GONE);
        confirmCard.requestLayout();
        input.setHint(svc.getString(asking ? R.string.card_answer_hint : R.string.card_change_hint));
        input.requestFocus();
        input.postDelayed(() -> {
            InputMethodManager imm = svc.getSystemService(InputMethodManager.class);
            imm.showSoftInput(input, 0);
        }, 150);
    }

    private void endChange() {
        changing = false;
        input.setHint(svc.getString(R.string.bubble_input_hint, sessions.current.brainName()));
        hideKeyboard();
        if (asking) {
            confirmOptions.setVisibility(View.VISIBLE);
            confirmOwn.setVisibility(View.VISIBLE);
        }
        confirmCard.requestLayout();
    }

    private void answerChange(String change) {
        ConfirmCallback cb = pendingConfirm;
        pendingConfirm = null;
        endCountdown();
        endChange();
        confirmCard.setVisibility(View.GONE);
        // OpenCode chats show the answer on the approval step itself (it arrives after the answer)
        if (cardChat != null && !"opencode".equals(cardChat.backend)) {
            hub.note(cardChat, Kind.STEP, svc.getString(asking ? R.string.card_note_answered : R.string.card_note_change_requested,
                    clip(change, 80)));
        }
        if (cb != null) cb.onAnswer(false, change);
        refreshStatus();
        collapse();
        hub.changed();
    }

    void askConfirm(String summary, String chat, ConfirmCallback cb) {
        askConfirm(summary, chat, 0, cb);
    }

    /** countdown: seconds after which the card approves itself unless the user stops it (0: it waits for the user). */
    void askConfirm(String summary, String chat, int countdown, ConfirmCallback cb) {
        showCard(summary, null, chat, cb);
        if (countdown > 0) startCountdown(countdown);
    }

    // ------------------------------------------------------------ countdown

    private final Runnable countdownTick = new Runnable() {
        @Override
        public void run() {
            if (pendingConfirm == null || countdownEnd == 0) return;
            long left = countdownEnd - SystemClock.elapsedRealtime();
            if (left <= 0) {
                autoApprove();
                return;
            }
            countdownBar.setProgress((int) (1000 * left / countdownLength));
            countdownText.setText(svc.getString(R.string.card_countdown, (int) ((left + 999) / 1000)));
            // a handler, not an animator: animations turned off in the phone's settings would end it at once
            root.postDelayed(this, 50);
        }
    };

    private void startCountdown(int seconds) {
        countdownLength = seconds * 1000L;
        countdownEnd = SystemClock.elapsedRealtime() + countdownLength;
        confirmLabel.setText(R.string.card_label_countdown);
        countdownRow.setVisibility(View.VISIBLE);
        root.removeCallbacks(countdownTick);
        countdownTick.run();
        hub.changed(); // the chat screen's card shows it too
    }

    /** The user stopped the countdown (a tap on it, or Change): the card now waits for them like any other. */
    void stopCountdown() {
        if (countdownEnd == 0) return;
        endCountdown();
        confirmLabel.setText(cardChat == null ? svc.getString(R.string.card_label_other_session, svc.getString(R.string.card_label_approval))
                : svc.getString(R.string.card_label_approval));
        if (cardChat != null) hub.note(cardChat, Kind.STEP, svc.getString(R.string.card_note_countdown_stopped));
        hub.changed();
    }

    private void endCountdown() {
        countdownEnd = 0;
        root.removeCallbacks(countdownTick);
        countdownRow.setVisibility(View.GONE);
    }

    /** When the card's countdown ends (elapsedRealtime ms) and its length, or {0, 0} without one (for the chat screen). */
    long[] countdown() {
        return pendingConfirm == null || countdownEnd == 0 ? new long[]{0, 0} : new long[]{countdownEnd, countdownLength};
    }

    private void autoApprove() {
        ConfirmCallback cb = pendingConfirm;
        pendingConfirm = null;
        endCountdown();
        if (changing) endChange();
        confirmCard.setVisibility(View.GONE);
        if (cardChat != null && !"opencode".equals(cardChat.backend)) {
            hub.note(cardChat, Kind.STEP, svc.getString(R.string.card_note_auto_approved, clip(confirmText.getText().toString(), 80)));
        }
        if (cb != null) cb.onAutoApproved();
        refreshStatus();
        collapse();
        hub.changed();
    }

    /**
     * The ask tool: the same card, with a button per answer and one to type an answer instead. handsOn: the user has a
     * step to do on the screen first (marks show where), so the card waits by the bubble, the question in its pill,
     * and opens when they tap it.
     */
    void askQuestion(String question, java.util.List<String> options, String chat, boolean handsOn, ConfirmCallback cb) {
        showCard(question, options, chat, cb, !handsOn);
    }

    /** The waiting card has a step for the user to do on the screen first (see askQuestion). */
    private boolean handsOn;

    /** The Buddy chat the waiting card belongs to, or null when it comes from another session (a terminal…). */
    private Sessions.Session cardChat;

    /** The chat the waiting card belongs to (null: none, or another session). */
    Sessions.Session pendingChat() {
        return pendingConfirm == null ? null : cardChat;
    }

    private void showCard(String text, java.util.List<String> options, String chat, ConfirmCallback cb) {
        showCard(text, options, chat, cb, true);
    }

    private void showCard(String text, java.util.List<String> options, String chat, ConfirmCallback cb, boolean open) {
        if (pendingConfirm != null) pendingConfirm.onAnswer(false, null);
        endCountdown();
        if (changing) endChange();
        pendingConfirm = cb;
        handsOn = !open;
        cardChat = chat == null ? null : sessions.find(chat);
        if (cardChat != null && cardChat != sessions.current) { // show the chat that's asking
            sessions.select(cardChat);
            showingList = false;
        }
        asking = options != null;
        askOptions = options;
        String label = svc.getString(asking ? R.string.card_label_question : R.string.card_label_approval);
        confirmLabel.setText(cardChat == null ? svc.getString(R.string.card_label_other_session, label) : label);
        confirmOptions.removeAllViews();
        if (asking) for (String o : options) confirmOptions.addView(optionButton(o, false, v -> answerOption(o)));
        layoutOptions();
        confirmOptions.setVisibility(asking ? View.VISIBLE : View.GONE);
        confirmOwn.setVisibility(asking ? View.VISIBLE : View.GONE);
        // a question only needs Skip in the bottom row
        confirmDeny.setText(asking ? R.string.card_skip : R.string.card_deny);
        confirmDeny.setTextColor(asking ? Ui.TEXT : Ui.ERR);
        confirmDeny.setBackground(rounded(asking ? Ui.CARD : Ui.ERR_SOFT, 16, 0, 0));
        confirmChange.setVisibility(asking ? View.GONE : View.VISIBLE);
        confirmApprove.setVisibility(asking ? View.GONE : View.VISIBLE);
        if (asking) confirmRow.setPaddingRelative(0, dp(8), 0, 0);
        else confirmRow.setPaddingRelative(0, 0, 0, 0);
        confirmText.setText(text);
        confirmCard.setVisibility(View.VISIBLE);
        refreshStatus();
        vibrate(60);
        if (open) expand();
        else collapse(); // the screen stays free for the step; the pill asks
        // on the lock screen the card waits out of sight (a notification says so): approving needs the phone unlocked
        applyEnabled();
        hub.changed();
    }

    /** An approval card is waiting for the user (the agent must not tap anything meanwhile). */
    boolean confirming() {
        return pendingConfirm != null;
    }

    /** The chat panel is open (the user is talking to Buddy). */
    boolean expanded() {
        return expanded;
    }

    void cancelConfirm() {
        pendingConfirm = null;
        endCountdown();
        if (changing) endChange();
        confirmCard.setVisibility(View.GONE);
        refreshStatus();
        hub.changed();
    }

    private Button optionButton(String label, boolean muted, View.OnClickListener onClick) {
        Button b = actionButton(label, muted ? Ui.CARD : Ui.CARD2);
        b.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        b.setTextColor(muted ? Ui.MUTED : Ui.TEXT);
        if (!muted) b.setBackground(rounded(Ui.CARD2, 16, Theme.withAlpha(Ui.ACCENT, 0x40), 1));
        b.setOnClickListener(onClick);
        return b;
    }

    /** A question's answers: one under the other, or side by side in landscape, where the panel is short. */
    private void layoutOptions() {
        Rect sc = screen();
        boolean side = sc.width() > sc.height();
        confirmOptions.setOrientation(side ? LinearLayout.HORIZONTAL : LinearLayout.VERTICAL);
        for (int i = 0; i < confirmOptions.getChildCount(); i++) {
            LinearLayout.LayoutParams p = side
                    ? new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1)
                    : new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
            p.topMargin = dp(6);
            if (side && i > 0) p.setMarginStart(dp(6));
            confirmOptions.getChildAt(i).setLayoutParams(p);
        }
    }

    private void answerOption(String option) {
        ConfirmCallback cb = pendingConfirm;
        pendingConfirm = null;
        endCountdown();
        if (changing) endChange();
        confirmCard.setVisibility(View.GONE);
        if (cardChat != null && !"opencode".equals(cardChat.backend)) {
            hub.note(cardChat, Kind.STEP, svc.getString(R.string.card_note_answered, clip(option, 80)));
        }
        if (cb != null) cb.onAnswer(true, option);
        refreshStatus();
        collapse();
        hub.changed();
    }

    private void answerConfirm(boolean approved) {
        ConfirmCallback cb = pendingConfirm;
        pendingConfirm = null;
        endCountdown();
        if (changing) endChange();
        confirmCard.setVisibility(View.GONE);
        if (cardChat != null && !"opencode".equals(cardChat.backend)) {
            hub.note(cardChat, Kind.STEP, asking ? svc.getString(R.string.card_note_skipped)
                    : approved ? svc.getString(R.string.card_note_approved, clip(confirmText.getText().toString(), 80))
                    : svc.getString(R.string.card_note_denied));
        }
        if (cb != null) cb.onAnswer(approved, null);
        refreshStatus();
        collapse();
        hub.changed();
    }

    // ------------------------------------------------------ claude events

    void onToolActivity(String tool) {
        hub.onToolActivity();
    }

    // --------------------------------------------------------------- views

    enum Kind { USER, BUDDY, STEP, ERROR }

    /** What a tool is doing right now (see setActivity), shown in the pill for a few seconds. */
    private String activity;
    private boolean activityBrowser;
    private long activityAt;
    private static final long ACTIVITY_MS = 8_000;
    private final Runnable activityExpired = this::refreshStatus;

    /** A tool's step ("Tapping “Send”"); browser: in the background browser, which isn't using the phone. */
    void setActivity(String text, boolean browser) {
        activity = text;
        activityBrowser = browser;
        activityAt = android.os.SystemClock.elapsedRealtime();
        svc.mainHandler().removeCallbacks(activityExpired);
        svc.mainHandler().postDelayed(activityExpired, ACTIVITY_MS + 100);
        refreshStatus();
    }

    /** External status (the notify tool): shown on the current chat until its next step. */
    void setStatus(String text) {
        if (text != null && !text.isEmpty()) sessions.current.status = text;
        refreshStatus();
    }

    /** Pill, face, badge and header from the hub's state. */
    private void refreshStatus() {
        running = hub.runningCount() > 0;
        boolean working = running || controlling;
        if (working && !wasWorking) maybePeek();
        wasWorking = working;
        boolean paused = svc.paused();
        String text = pendingConfirm == null ? hub.pillText()
                : handsOn ? svc.getString(R.string.bubble_pill_hands_on, clip(confirmText.getText().toString(), 90))
                : svc.getString(asking ? R.string.bubble_pill_waiting_answer : R.string.bubble_pill_waiting_approval);
        boolean fresh = activity != null && android.os.SystemClock.elapsedRealtime() - activityAt < ACTIVITY_MS;
        boolean browsing = fresh && activityBrowser && !controlling; // only the background browser: the phone is free
        if (paused && pendingConfirm == null) {
            text = svc.getString(R.string.bubble_pill_paused);
        } else if (controlling && pendingConfirm == null) {
            String step = fresh && !activityBrowser ? activity : text; // the tool's own step is the most precise
            text = step == null || step.isEmpty() ? svc.getString(R.string.bubble_using_phone)
                    : svc.getString(R.string.bubble_using_phone_status, step);
        } else if (browsing && pendingConfirm == null) {
            text = activity;
        }
        if (!paused && (controlling || browsing || hub.runningCount() > 0) && pendingConfirm == null && !Prefs.menuHinted(svc)) {
            text = text == null ? svc.getString(R.string.bubble_pill_hold_hint)
                    : svc.getString(R.string.bubble_pill_status_hold_hint, text);
        }
        if (!noteListening) { // while dictating a note the pill shows what's heard
            pill.setText(text == null ? "" : text);
            pill.setVisibility(text == null ? View.GONE : View.VISIBLE);
        }
        // in control: a solid accent pill, so it's clear not to touch the phone
        pill.setTextColor(controlling ? Ui.ON_ACCENT : Ui.TEXT);
        pill.setBackground(rounded(controlling ? Ui.ACCENT : Ui.BG, 18, Ui.ACCENT, 1));
        badge.setVisibility(!expanded && hub.anyUnread() ? View.VISIBLE : View.GONE);
        setWorking(!paused && (running || controlling || browsing));
        updateHeader();
        if (!expanded) {
            try {
                wm.updateViewLayout(root, lp);
            } catch (Exception ignored) {
            }
        }
    }

    /** Thinking face plus a gentle pulse while Claude is working; smiling face when idle. */
    private void setWorking(boolean on) {
        setWorking(on, false);
    }

    private boolean faceDragging() {
        return dismissWindow != null && dismissWindow.getVisibility() == View.VISIBLE;
    }

    /** The drawing on the floating face now (thinking, paused, fired up…). */
    private int faceRes;

    // the drawn face's shadow follows its rounded speech-bubble body (drawable spans 2..54 of 56 units wide, 3..42 tall)
    private static final android.view.ViewOutlineProvider BODY = new android.view.ViewOutlineProvider() {
        @Override
        public void getOutline(View v, android.graphics.Outline o) {
            float u = v.getWidth() / 56f;
            o.setRoundRect(Math.round(2 * u), Math.round(3 * u), Math.round(54 * u), Math.round(42 * u), 13 * u);
        }
    };

    /** Shows the drawing for this state, or the user's picture (issue #10) in every state: it has its shadow drawn in. */
    private void showFace(int res) {
        faceRes = res;
        android.graphics.Bitmap own = Face.bubble(svc);
        if (own != null) face.setImageBitmap(own);
        else face.setImageResource(res);
        android.view.ViewOutlineProvider outline = own != null ? null : BODY;
        if (face.getOutlineProvider() != outline) face.setOutlineProvider(outline);
    }

    /** Buddy's look changed in Settings: the new face now, and in the chat panel's replies. */
    void applyFace() {
        showFace(faceRes);
        if (!expanded && !showingList) renderCurrent();
    }

    private void setWorking(boolean on, boolean force) {
        ring.setSpinning(on);
        // keep the fired-up / bracing face while the bubble is being dragged
        if (force || !faceDragging()) showFace(on ? R.drawable.bubble_face_thinking
                : svc.paused() ? R.drawable.bubble_face_paused : R.drawable.bubble_face);
        if (on && breathing == null) {
            breathing = ValueAnimator.ofFloat(1f, 0.9f);
            breathing.setDuration(750);
            breathing.setRepeatMode(ValueAnimator.REVERSE);
            breathing.setRepeatCount(ValueAnimator.INFINITE);
            breathing.addUpdateListener(a -> {
                float s = (float) a.getAnimatedValue();
                face.setScaleX(s);
                face.setScaleY(s);
            });
            breathing.start();
        } else if (!on && breathing != null) {
            breathing.cancel();
            breathing = null;
            face.setScaleX(1f);
            face.setScaleY(1f);
        }
    }

    /** The open run of tool steps (grouped like the chat screen's log), or null. */
    private StepLog log;

    private void showMessage(Kind kind, String text) {
        if (text == null) return;
        View hint = messages.findViewWithTag("hint");
        if (hint != null) messages.removeView(hint);
        if (kind == Kind.STEP) {
            if (log == null) {
                log = new StepLog();
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
                lp.topMargin = dp(10);
                messages.addView(log.view, lp);
            }
            log.add(text);
            log.setLive(sessions.current.running);
            scrollToEnd();
            return;
        }
        if (log != null) {
            log.finish();
            log = null;
        }
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(10);
        View v;
        switch (kind) {
            case USER: {
                TextView tv = Ui.text(svc, text, 15, Ui.TEXT);
                tv.setBackground(Ui.corners(svc, Ui.USER, 20, 20, 6, 20));
                tv.setPaddingRelative(dp(14), dp(10), dp(14), dp(10));
                Ui.linkifyForService(tv, this::collapse);
                p.gravity = Gravity.END;
                p.setMarginStart(dp(40));
                v = tv;
                break;
            }
            case BUDDY: {
                LinearLayout r = new LinearLayout(svc);
                r.setGravity(Gravity.BOTTOM);
                FrameLayout av = new FrameLayout(svc);
                av.setBackground(Ui.oval(Ui.ACCENT_SOFT));
                ImageView f = new ImageView(svc);
                Face.set(f, R.drawable.bubble_face);
                av.addView(f, new FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER));
                LinearLayout.LayoutParams avp = new LinearLayout.LayoutParams(dp(24), dp(24));
                avp.setMarginEnd(dp(8));
                r.addView(av, avp);
                TextView tv = Ui.text(svc, text, 15, Ui.TEXT);
                tv.setText(Ui.markdownLinks(text, this::collapse)); // [text](url): the text, as a link
                tv.setLineSpacing(0, 1.15f);
                tv.setBackground(Ui.corners(svc, Ui.CARD, 20, 20, 20, 6));
                tv.setPaddingRelative(dp(14), dp(10), dp(14), dp(10));
                Ui.linkifyForService(tv, this::collapse);
                copyOnLongPress(tv, Ui.linkCopy(text)); // links as "text (address)"
                r.addView(tv);
                p.setMarginEnd(dp(24));
                v = r;
                break;
            }
            default: { // ERROR
                LinearLayout r = new LinearLayout(svc);
                r.setGravity(Gravity.TOP);
                r.addView(Ui.icon(svc, R.drawable.ms_error, Ui.ERR, 18));
                TextView tv = Ui.text(svc, text, 13, Ui.ERR);
                tv.setPaddingRelative(dp(8), 0, 0, 0);
                r.addView(tv, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
                p.width = LinearLayout.LayoutParams.MATCH_PARENT;
                v = r;
            }
        }
        copyOnLongPress(v, text);
        messages.addView(v, p);
        while (messages.getChildCount() > Sessions.MAX_MESSAGES) messages.removeViewAt(0);
        scrollToEnd();
    }

    private ImageButton paste;

    /** Show Paste while the field is focused and text is copied (the clipboard is only readable then). */
    private void updatePaste() {
        ClipboardManager cm = svc.getSystemService(ClipboardManager.class);
        android.content.ClipDescription d = input.hasFocus() ? cm.getPrimaryClipDescription() : null;
        boolean can = d != null && (d.hasMimeType("text/*") || d.hasMimeType("text/uri-list") || d.hasMimeType("image/*"));
        paste.setVisibility(can ? View.VISIBLE : View.GONE);
    }

    private void pasteClip() {
        ClipData clip = svc.getSystemService(ClipboardManager.class).getPrimaryClip();
        if (clip == null || clip.getItemCount() == 0) return;
        // a copied screenshot or photo is attached, not pasted as its address
        java.util.List<android.net.Uri> images = new java.util.ArrayList<>();
        for (int i = 0; i < clip.getItemCount(); i++) {
            android.net.Uri u = clip.getItemAt(i).getUri();
            if (u != null && RichEditText.isImage(svc, u, clip.getDescription())) images.add(u);
        }
        if (!images.isEmpty()) {
            pasteImages(images, () -> { });
            return;
        }
        CharSequence t = clip.getItemAt(0).coerceToText(svc);
        if (t == null || t.length() == 0) return;
        int a = Math.max(0, Math.min(input.getSelectionStart(), input.getSelectionEnd()));
        int b = Math.max(0, Math.max(input.getSelectionStart(), input.getSelectionEnd()));
        input.getText().replace(a, b, t);
        input.setSelection(a + t.length());
    }

    /**
     * Images pasted here, or picked in the keyboard's clipboard: copied into Buddy's storage, then the chat opens in
     * the app with them attached and the draft kept, to check or remove them before sending (issue #6).
     */
    private void pasteImages(java.util.List<android.net.Uri> uris, Runnable done) {
        toast(uris.size() == 1 ? svc.getString(R.string.chat_adding)
                : svc.getResources().getQuantityString(R.plurals.chat_adding_files, uris.size(), uris.size()));
        new Thread(() -> {
            java.util.ArrayList<String> ids = new java.util.ArrayList<>();
            String err = null;
            for (android.net.Uri u : uris) {
                try {
                    ids.add(Attachments.importUri(svc, u));
                } catch (Exception e) {
                    err = e.getMessage();
                }
            }
            final String error = err;
            input.post(() -> {
                done.run();
                if (error != null) toast(error);
                if (!ids.isEmpty()) openInApp(false, ids);
            });
        }).start();
    }

    private void copyOnLongPress(View v, String text) {
        v.setOnLongClickListener(x -> {
            ClipboardManager cm = svc.getSystemService(ClipboardManager.class);
            cm.setPrimaryClip(ClipData.newPlainText("Buddy", text));
            toast(svc.getString(R.string.bubble_copied));
            return true;
        });
    }

    /** Consecutive tool steps in one collapsible card ("3 tool calls"), as in the chat screen. */
    private final class StepLog {
        final LinearLayout view = new LinearLayout(svc), rows = new LinearLayout(svc);
        final TextView count;
        final ImageView chevron;
        int n;
        boolean open, live, userToggled;
        FrameLayout lastMark;

        StepLog() {
            view.setOrientation(LinearLayout.VERTICAL);
            rows.setOrientation(LinearLayout.VERTICAL);
            view.setBackground(rounded(0x00000000, 14, Ui.OUTLINE, 1));
            LinearLayout head = new LinearLayout(svc);
            head.setGravity(Gravity.CENTER_VERTICAL);
            head.setPaddingRelative(dp(12), dp(9), dp(8), dp(9));
            head.addView(Ui.icon(svc, R.drawable.ms_terminal, Ui.MUTED, 16));
            count = Ui.monoText(svc, "", 12, Ui.MUTED);
            count.setPaddingRelative(dp(8), 0, 0, 0);
            head.addView(count, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
            chevron = Ui.icon(svc, R.drawable.ms_expand_more, Ui.MUTED, 18);
            head.addView(chevron);
            head.setOnClickListener(v -> {
                userToggled = true;
                setOpen(!open);
            });
            view.addView(head);
            rows.setPaddingRelative(dp(12), 0, dp(12), dp(8));
            view.addView(rows);
            setOpen(false);
        }

        void add(String step) {
            n++;
            count.setText(svc.getResources().getQuantityString(R.plurals.bubble_tool_calls, n, n));
            if (lastMark != null) mark(lastMark, false);
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
            LinearLayout r = new LinearLayout(svc);
            r.setGravity(Gravity.TOP);
            r.setPaddingRelative(0, dp(3), 0, dp(3));
            FrameLayout m = new FrameLayout(svc);
            r.addView(m, new LinearLayout.LayoutParams(dp(16), dp(16)));
            TextView t = Ui.monoText(svc, "", 12, Ui.MUTED);
            android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder(name);
            sb.setSpan(new android.text.style.ForegroundColorSpan(Ui.TEXT), 0, name.length(), 0);
            sb.append(arg);
            t.setText(sb);
            t.setMaxLines(3);
            t.setEllipsize(TextUtils.TruncateAt.END);
            t.setPaddingRelative(dp(8), 0, 0, 0);
            r.addView(t, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
            copyOnLongPress(r, step);
            rows.addView(r);
            lastMark = m;
            mark(m, live);
        }

        private void mark(FrameLayout f, boolean spinning) {
            f.removeAllViews();
            if (spinning) f.addView(Ui.spinner(svc, 14), new FrameLayout.LayoutParams(dp(14), dp(14), Gravity.CENTER));
            else f.addView(Ui.icon(svc, R.drawable.ms_check, Ui.OK, 16), new FrameLayout.LayoutParams(dp(16), dp(16)));
        }

        void setLive(boolean on) {
            if (live == on) return;
            live = on;
            if (lastMark != null) mark(lastMark, on);
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

    private void scrollToEnd() {
        scroll.post(() -> scroll.fullScroll(View.FOCUS_DOWN));
    }

    private TextView subtitle, stripText;
    private View modelChip;
    private LinearLayout strip;

    /** A small header icon (20dp glyph in a 36dp target), in the app's muted style. */
    private android.widget.ImageButton toolIcon(int icon, String description, int tint, View.OnClickListener l) {
        android.widget.ImageButton b = Ui.iconButton(svc, icon, description, tint);
        b.setScaleType(ImageView.ScaleType.FIT_CENTER);
        b.setPaddingRelative(dp(8), dp(8), dp(8), dp(8));
        b.setLayoutParams(new LinearLayout.LayoutParams(dp(36), dp(36)));
        b.setOnClickListener(l);
        return b;
    }

    private android.widget.ImageButton panelIcon(int icon, String description, View.OnClickListener l) {
        android.widget.ImageButton b = Ui.iconButton(svc, icon, description, Ui.MUTED);
        b.setLayoutParams(new LinearLayout.LayoutParams(dp(40), dp(40)));
        b.setOnClickListener(l);
        return b;
    }

    private Button smallButton(String label, View.OnClickListener l) {
        Button b = new Button(svc);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(Ui.MUTED);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        b.setMinWidth(0);
        b.setMinimumWidth(0);
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPaddingRelative(dp(10), dp(6), dp(10), dp(6));
        b.setBackground(rounded(Ui.CARD, 12, 0, 0));
        b.setOnClickListener(l);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p.setMarginStart(dp(6));
        b.setLayoutParams(p);
        return b;
    }

    private Button actionButton(String label, int color) {
        Button b = new Button(svc);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(Ui.TEXT);
        b.setTypeface(Ui.sans(svc, 600));
        b.setMinHeight(0);
        b.setMinimumHeight(0);
        b.setPaddingRelative(dp(16), dp(10), dp(16), dp(10));
        b.setBackground(rounded(color, 16, 0, 0));
        return b;
    }

    private GradientDrawable rounded(int fill, int radiusDp, int stroke, int strokeDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(radiusDp));
        if (strokeDp > 0) d.setStroke(dp(strokeDp), stroke);
        return d;
    }

    private GradientDrawable circle(int fill, int stroke, int strokeDp) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(fill);
        if (strokeDp > 0) d.setStroke(dp(strokeDp), stroke);
        return d;
    }

    private Rect screen() {
        return wm.getCurrentWindowMetrics().getBounds();
    }

    /** Navigation with Back/Home/Recents buttons (3- or 2-button), not gestures: the bar is a full button row. */
    private boolean navButtons() {
        android.graphics.Insets nav = wm.getCurrentWindowMetrics().getWindowInsets()
                .getInsetsIgnoringVisibility(android.view.WindowInsets.Type.navigationBars());
        return Math.max(nav.bottom, Math.max(nav.left, nav.right)) >= dp(40);
    }

    /** The screen without the status bar (or camera cutout) and the navigation bar, wherever they are. */
    private Rect usable() {
        android.view.WindowMetrics m = wm.getCurrentWindowMetrics();
        android.graphics.Insets in = m.getWindowInsets().getInsetsIgnoringVisibility(
                android.view.WindowInsets.Type.systemBars() | android.view.WindowInsets.Type.displayCutout());
        Rect r = new Rect(m.getBounds());
        r.left += in.left;
        r.top += in.top;
        r.right -= in.right;
        r.bottom -= in.bottom;
        return r;
    }

    private int dp(int v) {
        return Math.round(v * svc.getResources().getDisplayMetrics().density);
    }

    private static String clip(String s, int n) {
        s = s.replace('\n', ' ');
        return s.length() > n ? s.substring(0, n - 1) + "…" : s;
    }

    private void vibrate(long ms) {
        try {
            if (vibrator != null) vibrator.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE));
        } catch (Exception ignored) {
        }
    }

    private void toast(String s) {
        Toast.makeText(svc, s, Toast.LENGTH_SHORT).show();
    }

    /** Drags the collapsed bubble; a tap without movement expands it. */
    private final class DragListener implements View.OnTouchListener {
        private final int slop = ViewConfiguration.get(svc).getScaledTouchSlop();
        private float downX, downY;
        private int startX, startY;
        private boolean dragging, menu;
        private final Runnable hold = () -> {
            menu = true;
            holdRing.cancel();
            openMenu();
        };

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downX = e.getRawX();
                    downY = e.getRawY();
                    startX = lp.x;
                    startY = lp.y;
                    dragging = false;
                    menu = false;
                    if (radial.isOpen()) radial.close(true);
                    face.postDelayed(hold, ViewConfiguration.getLongPressTimeout());
                    holdRing.begin(ViewConfiguration.getLongPressTimeout());
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float dx = e.getRawX() - downX, dy = e.getRawY() - downY;
                    if (menu) { // held: slide onto an action
                        radial.hover(e.getRawX(), e.getRawY());
                        return true;
                    }
                    if (!dragging && Math.hypot(dx, dy) > slop) {
                        face.removeCallbacks(hold);
                        holdRing.cancel();
                        dragging = true;
                        // Switch to left-anchored coordinates for free dragging.
                        int[] loc = new int[2];
                        face.getLocationOnScreen(loc);
                        lp.gravity = Gravity.TOP | Gravity.START;
                        collapsed.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
                        startX = loc[0];
                        // where the face's centre is relative to the window position, in screen pixels
                        int[] rootLoc = new int[2];
                        root.getLocationOnScreen(rootLoc);
                        faceOffX = loc[0] - rootLoc[0] + face.getWidth() / 2;
                        faceOffY = loc[1] - rootLoc[1] + face.getHeight() / 2;
                        startX = rootLoc[0];
                        lpToScreenY = rootLoc[1] - startY; // window y vs screen y (status bar, insets)
                        if (windowAnim != null) windowAnim.cancel();
                        showFace(R.drawable.bubble_face_fired);
                        ((View) face.getParent()).animate().scaleX(1.14f).scaleY(1.14f)
                                .setInterpolator(new OvershootInterpolator(3f)).setDuration(220).start();
                    }
                    if (dragging) {
                        int x = startX + (int) dx, y = startY + (int) dy;
                        showDismissTarget(true);
                        boolean wasOver = overTarget;
                        if (overDismiss(x, y)) {
                            // elastic snap onto the target, like chat heads
                            if (!wasOver) {
                                int[] t = targetCenter();
                                animateWindowTo(t[0] - faceOffX, t[1] - faceOffY - lpToScreenY, 200,
                                        new OvershootInterpolator(2.4f), null);
                            }
                            feedArcs();
                            return true;
                        }
                        if (wasOver && windowAnim != null) windowAnim.cancel();
                        lp.x = x;
                        lp.y = y;
                        wm.updateViewLayout(root, lp);
                        followWaves();
                        feedArcs();
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                    face.removeCallbacks(hold);
                    holdRing.cancel();
                    if (menu) {
                        radial.release();
                        return true;
                    }
                    if ((noteListening || talkListening) && !dragging) { // dictating: a tap ends it
                        Voice.finishListening();
                        return true;
                    }
                    if (dragging) {
                        ((View) face.getParent()).animate().scaleX(1f).scaleY(1f).setDuration(160).start();
                        if (overTarget) {
                            endArcs(true);
                            dismissToNotification();
                            return true;
                        }
                        endArcs(false);
                        showDismissTarget(false);
                        setWorking(!svc.paused() && (running || controlling), true);
                        // fling to the nearest edge with a little bounce
                        final boolean right = lp.x + faceOffX > screen().width() / 2;
                        int edgeX = right ? screen().width() - root.getWidth() - dp(6) : dp(6);
                        final int y = Math.max(dp(40), Math.min(lp.y, screen().height() - dp(160)));
                        animateWindowTo(edgeX, y, 340, new OvershootInterpolator(1.4f), () -> {
                            Prefs.saveBubblePos(svc, right ? 1 : 0, lp.y);
                            applyCollapsedParams();
                            wm.updateViewLayout(root, lp);
                            followWaves();
                        });
                    } else {
                        maybeTip();
                        // the user's choice: talk (listen with the chat closed, waves under the bubble, then send),
                        // the chat in the app, or the panel; a waiting card always opens the panel
                        String action = Prefs.tapAction(svc);
                        if (pendingConfirm != null) expand();
                        else if ("talk".equals(action) && hub.runningCount() == 0) talk();
                        else if ("app".equals(action)) openInApp(false);
                        else expand();
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    face.removeCallbacks(hold);
                    holdRing.cancel();
                    if (menu) return true;
                    endArcs(false);
                    showDismissTarget(false);
                    setWorking(!svc.paused() && (running || controlling), true);
                    return true;
                default:
                    return false;
            }
        }
    }
}
