package app.buddy.assistant;

import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.format.Formatter;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.HashMap;
import java.util.Map;

import static app.buddy.assistant.Ui.*;

/** Settings → Agents and models → Models on this phone: download a model and pick where it runs (see OnPhone). */
public class OnPhoneActivity extends Screen {
    /** The download in progress (one at a time), and how the last one of each model ended; outlive the screen. */
    private static volatile String busy;
    private static final Map<String, String> failed = new java.util.concurrent.ConcurrentHashMap<>();

    private LinearLayout body;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            build(); // (once more after it ended: this screen may not be the one that started it)
            if (busy != null) main.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = column(this);
        root.setBackgroundColor(BG);
        LinearLayout top = row(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPaddingRelative(dp(this, 4), dp(this, 4), dp(this, 16), dp(this, 4));
        ImageButton back = iconButton(this, R.drawable.ms_arrow_back, getString(R.string.perms_back));
        back.setLayoutParams(new LinearLayout.LayoutParams(dp(this, 44), dp(this, 44)));
        back.setOnClickListener(v -> finish());
        top.addView(back);
        TextView t = title(this, getString(R.string.onphone_title), 18);
        t.setPaddingRelative(dp(this, 6), 0, 0, 0);
        top.addView(t, weight1());
        root.addView(top, full());
        ScrollView sv = new ScrollView(this);
        body = column(this);
        body.setPaddingRelative(dp(this, 20), dp(this, 4), dp(this, 20), dp(this, 32));
        sv.addView(body);
        root.addView(sv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(BG);
        frame.setFitsSystemWindows(true);
        frame.addView(root);
        setContentView(protect(frame)); // the user's own: downloads of GBs and the engine
    }

    @Override
    protected void onResume() {
        super.onResume();
        build();
        if (busy != null) main.postDelayed(tick, 1000);
    }

    @Override
    protected void onPause() {
        super.onPause();
        main.removeCallbacks(tick);
    }

    private void build() {
        body.removeAllViews();
        body.addView(text(this, getString(R.string.onphone_intro), 13, MUTED), full());

        TextView runs = sectionLabel(this, getString(R.string.onphone_runs_on));
        runs.setPaddingRelative(0, dp(this, 20), 0, dp(this, 8));
        body.addView(runs);
        boolean sd = OnPhone.snapdragon();
        String[][] devs = {{"auto", getString(R.string.onphone_dev_auto)}, {"npu", getString(R.string.onphone_dev_npu)},
                {"gpu", getString(R.string.onphone_dev_gpu)}, {"cpu", getString(R.string.onphone_dev_cpu)}};
        java.util.Set<String> off = new java.util.HashSet<>();
        if (!sd) {
            off.add("npu");
            off.add("gpu");
        }
        body.addView(segmented(this, devs, sd ? OnPhone.device(this) : "cpu", false, d -> {
            OnPhone.setDevice(this, d);
            build();
        }, off, d -> android.widget.Toast.makeText(this, R.string.onphone_not_snapdragon, android.widget.Toast.LENGTH_LONG).show()), full());
        String ran = OnPhone.ranOn(this);
        String info = !sd ? getString(R.string.onphone_not_snapdragon) : getString(R.string.onphone_runs_on_info);
        if (!ran.isEmpty()) info += " " + getString(R.string.onphone_ran_on, label(ran));
        TextView ri = text(this, info, 12, MUTED);
        ri.setPaddingRelative(dp(this, 4), dp(this, 8), dp(this, 4), 0);
        body.addView(ri, full());

        TextView ml = sectionLabel(this, getString(R.string.onphone_models));
        ml.setPaddingRelative(0, dp(this, 20), 0, dp(this, 8));
        body.addView(ml);
        LinearLayout g = group(this);
        for (OnPhone.Model m : OnPhone.MODELS) addToGroup(this, g, modelRow(m), 20);
        body.addView(g, full());

        TextView src = text(this, downloadNote(this, getString(R.string.onphone_source, OnPhone.ENGINE)), 12, MUTED);
        src.setPaddingRelative(dp(this, 4), dp(this, 16), dp(this, 4), 0);
        body.addView(src, full());
    }

    private String label(String dev) {
        switch (dev) {
            case "npu": return getString(R.string.onphone_dev_npu);
            case "gpu": return getString(R.string.onphone_dev_gpu);
            default: return getString(R.string.onphone_dev_cpu);
        }
    }

    private View modelRow(OnPhone.Model m) {
        LinearLayout r = column(this);
        r.setPaddingRelative(dp(this, 20), dp(this, 14), dp(this, 16), dp(this, 14));
        LinearLayout head = row(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout names = column(this);
        names.addView(text(this, m.name, 16, TEXT));
        names.addView(monoText(this, getString(R.string.onphone_size_license,
                Formatter.formatShortFileSize(this, m.size), m.license), 12, MUTED));
        head.addView(names, weight1());
        boolean have = OnPhone.downloaded(this, m), going = m.id.equals(busy);
        Button b = going ? outlinedButton(this, getString(R.string.onphone_cancel), TEXT)
                : have ? outlinedButton(this, getString(R.string.onphone_delete), ERR)
                : button(this, getString(R.string.onphone_download), busy == null);
        b.setEnabled(going || have || busy == null);
        b.setOnClickListener(v -> {
            if (going) {
                OnPhone.cancel(this);
            } else if (have) {
                confirmSheet(this, getString(R.string.onphone_delete_title, m.name), getString(R.string.onphone_delete_text),
                        getString(R.string.onphone_delete), true, () -> {
                            OnPhone.delete(this, m);
                            build();
                        });
            } else {
                start(m);
            }
        });
        head.addView(b, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(this, 40)));
        r.addView(head, full());
        TextView note = text(this, getString(m.note), 13, MUTED);
        note.setPaddingRelative(0, dp(this, 6), 0, 0);
        r.addView(note, full());
        String status = null;
        if (going) {
            long eng = OnPhone.engineReady(this) ? 0 : OnPhone.engineProgress(this);
            if (!OnPhone.engineReady(this)) {
                status = getString(R.string.onphone_engine_downloading, pct(eng, OnPhone.engineSize()));
            } else {
                status = getString(R.string.onphone_downloading, pct(OnPhone.progress(this, m), m.size));
            }
            ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
            bar.setMax(100);
            bar.setProgress(OnPhone.engineReady(this) ? pct(OnPhone.progress(this, m), m.size) : pct(eng, OnPhone.engineSize()));
            bar.setProgressTintList(android.content.res.ColorStateList.valueOf(ACCENT));
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(this, 6));
            bp.topMargin = dp(this, 8);
            r.addView(bar, bp);
        } else if (have) {
            status = getString(R.string.onphone_downloaded);
        } else if (failed.containsKey(m.id)) {
            status = getString(R.string.onphone_failed, failed.get(m.id));
        }
        if (status != null) {
            TextView st = text(this, status, 12, failed.containsKey(m.id) && !have && !going ? ERR : have ? OK : MUTED);
            st.setPaddingRelative(0, dp(this, 6), 0, 0);
            r.addView(st, full());
        }
        return r;
    }

    private static int pct(long done, long all) {
        return all <= 0 ? 0 : (int) Math.min(100, done * 100 / all);
    }

    /** Before a download: enough room, and a word first when it would go over mobile data. */
    private void start(OnPhone.Model m) {
        long need = m.size + (OnPhone.engineReady(this) ? 0 : 300L * 1024 * 1024);
        long free = Linux.root(this).getUsableSpace();
        if (free < need + 500L * 1024 * 1024) {
            android.widget.Toast.makeText(this, getString(R.string.onphone_no_space, Formatter.formatShortFileSize(this, need),
                    Formatter.formatShortFileSize(this, free)), android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        android.net.ConnectivityManager cm = getSystemService(android.net.ConnectivityManager.class);
        if (cm != null && cm.isActiveNetworkMetered()) {
            confirmSheet(this, getString(R.string.onphone_metered_title), getString(R.string.onphone_metered_text, m.name,
                    Formatter.formatShortFileSize(this, need)), getString(R.string.onphone_download), false, () -> go(m));
            return;
        }
        go(m);
    }

    private void go(OnPhone.Model m) {
        if (!Linux.installed(this)) {
            android.widget.Toast.makeText(this, R.string.helper_linux_missing, android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        busy = m.id;
        failed.remove(m.id);
        OnPhone.cancelled = false;
        final android.content.Context app = getApplicationContext();
        new Thread(() -> {
            String err = OnPhone.download(app, m);
            if (err != null && !OnPhone.downloaded(app, m) && !OnPhone.cancelled) failed.put(m.id, err);
            busy = null;
            main.post(() -> {
                if (!isFinishing()) build();
            });
        }, "onphone-download").start();
        build();
        main.postDelayed(tick, 1000);
    }
}
