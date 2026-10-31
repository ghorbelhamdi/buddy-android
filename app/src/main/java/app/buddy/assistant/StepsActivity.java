package app.buddy.assistant;

import android.app.Dialog;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.text.format.DateUtils;
import android.view.Gravity;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import java.io.File;
import java.util.Arrays;
import org.json.JSONObject;
import static app.buddy.assistant.Ui.*;

/** A chat's last runs, step by step: what each action did, when, and how the screen looked after it (see StepLog). */
public class StepsActivity extends Screen {
    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        String chat = getIntent().getStringExtra("id");
        LinearLayout root = column(this);
        root.setBackgroundColor(BG);
        LinearLayout top = row(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPaddingRelative(dp(this, 4), dp(this, 4), dp(this, 16), dp(this, 4));
        ImageButton back = iconButton(this, R.drawable.ms_arrow_back, getString(R.string.perms_back));
        back.setLayoutParams(new LinearLayout.LayoutParams(dp(this, 44), dp(this, 44)));
        back.setOnClickListener(v -> finish());
        top.addView(back);
        TextView t = title(this, getString(R.string.steps_title), 18);
        t.setPaddingRelative(dp(this, 6), 0, 0, 0);
        top.addView(t, weight1());
        root.addView(top, full());
        ScrollView sv = new ScrollView(this);
        LinearLayout body = column(this);
        body.setPaddingRelative(dp(this, 16), dp(this, 4), dp(this, 16), dp(this, 32));
        sv.addView(body);
        root.addView(sv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(BG);
        frame.setFitsSystemWindows(true);
        frame.addView(root);
        setContentView(protect(frame));

        body.addView(text(this, getString(R.string.steps_intro), 13, MUTED), full());
        File[] runs = chat == null ? null : StepLog.dir(this, chat).listFiles();
        if (runs == null || runs.length == 0) {
            body.addView(text(this, getString(R.string.steps_none), 15, TEXT), gap(18));
            return;
        }
        Arrays.sort(runs, (a, b) -> b.getName().compareTo(a.getName())); // newest first
        for (File run : runs) run(body, run);
    }

    private void run(LinearLayout body, File dir) {
        long start;
        try {
            start = Long.parseLong(dir.getName());
        } catch (NumberFormatException e) {
            return;
        }
        String[] lines = Skills.read(new File(dir, "steps.jsonl")).split("\n");
        JSONObject meta = null;
        try {
            meta = new JSONObject(Skills.read(new File(dir, "run.json")));
        } catch (Exception ignored) {
        }
        int actions = 0;
        for (String l : lines) if (!l.trim().isEmpty()) actions++;
        String when = DateUtils.formatDateTime(this, start, DateUtils.FORMAT_SHOW_TIME | DateUtils.FORMAT_SHOW_DATE
                | DateUtils.FORMAT_ABBREV_MONTH);
        String head = meta == null ? getString(R.string.steps_run_live, when, actions)
                : getString(R.string.steps_run, when, DateUtils.formatElapsedTime((meta.optLong("end") - start) / 1000),
                actions, outcome(meta.optString("outcome")));
        if (meta != null && meta.optLong("context") > 0) head += " · " + getString(R.string.steps_context, Models.tokens(meta.optLong("context")));
        TextView h = sectionLabel(this, head);
        body.addView(h, gap(20));
        if (actions == 0) {
            body.addView(text(this, getString(R.string.steps_no_actions), 13, MUTED), gap(4));
            return;
        }
        for (String l : lines) {
            if (l.trim().isEmpty()) continue;
            try {
                JSONObject s = new JSONObject(l);
                body.addView(step(dir, s, start), gap(6));
            } catch (Exception ignored) {
            }
        }
    }

    private String outcome(String o) {
        return getString("failed".equals(o) ? R.string.steps_failed : "stopped".equals(o) ? R.string.steps_stopped
                : R.string.steps_done);
    }

    private LinearLayout step(File dir, JSONObject s, long start) {
        LinearLayout r = row(this);
        r.setGravity(Gravity.TOP);
        r.setBackground(outlined(this, CARD, 12, OUTLINE, 1));
        r.setPaddingRelative(dp(this, 8), dp(this, 8), dp(this, 10), dp(this, 8));
        File shot = new File(dir, s.optInt("n") + ".jpg");
        if (shot.isFile()) {
            ImageView iv = new ImageView(this);
            Bitmap b = BitmapFactory.decodeFile(shot.getPath());
            iv.setImageBitmap(b);
            iv.setScaleType(ImageView.ScaleType.FIT_START);
            iv.setAdjustViewBounds(true);
            iv.setOnClickListener(v -> showFull(b));
            r.addView(iv, new LinearLayout.LayoutParams(dp(this, 64), LinearLayout.LayoutParams.WRAP_CONTENT));
        }
        LinearLayout t = column(this);
        t.setPaddingRelative(dp(this, 10), 0, 0, 0);
        long at = (s.optLong("t") - start) / 1000;
        t.addView(monoText(this, "+" + DateUtils.formatElapsedTime(at) + "  " + s.optString("label"), 13, TEXT));
        if (!s.optString("result").isEmpty()) t.addView(text(this, s.optString("result"), 12, MUTED));
        r.addView(t, weight1());
        return r;
    }

    private void showFull(Bitmap b) {
        if (b == null) return;
        Dialog d = new Dialog(this, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        ImageView iv = new ImageView(this);
        iv.setImageBitmap(b);
        iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
        iv.setOnClickListener(v -> d.dismiss());
        d.setContentView(iv);
        d.show();
    }

    private LinearLayout.LayoutParams gap(int top) {
        LinearLayout.LayoutParams p = full();
        p.topMargin = dp(this, top);
        return p;
    }
}
