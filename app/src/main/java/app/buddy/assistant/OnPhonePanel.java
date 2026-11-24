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

/**
 * The models on this phone, with what the user does with them: where a model runs, thinking, download and remove (see
 * OnPhone). One panel for the two places that show it: Add a connection's "This phone" tab, and the screen of its own
 * that a row in Agents and models opens.
 */
final class OnPhonePanel {
    /** The download in progress (one at a time), and how the last one of each model ended; outlive the screen. */
    static volatile String busy;
    private static final Map<String, String> failed = new java.util.concurrent.ConcurrentHashMap<>();

    private final android.app.Activity a;
    private final LinearLayout body;
    /** With the paragraph on what these models are (the tab says it its own way). */
    private final boolean intro;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            build(); // (once more after it ended: this screen may not be the one that started it)
            if (busy != null) main.postDelayed(this, 1000);
        }
    };

    OnPhonePanel(android.app.Activity a, LinearLayout body, boolean intro) {
        this.a = a;
        this.body = body;
        this.intro = intro;
    }

    void resume() {
        build();
        main.removeCallbacks(tick);
        if (busy != null) main.postDelayed(tick, 1000);
    }

    void pause() {
        main.removeCallbacks(tick);
    }

    void build() {
        body.removeAllViews();
        if (intro) body.addView(text(a, a.getString(R.string.onphone_intro), 13, MUTED), full());

        TextView runs = sectionLabel(a, a.getString(R.string.onphone_runs_on));
        runs.setPaddingRelative(0, dp(a, 20), 0, dp(a, 8));
        body.addView(runs);
        boolean sd = OnPhone.snapdragon();
        String[][] devs = {{"auto", a.getString(R.string.onphone_dev_auto)}, {"npu", a.getString(R.string.onphone_dev_npu)},
                {"gpu", a.getString(R.string.onphone_dev_gpu)}, {"cpu", a.getString(R.string.onphone_dev_cpu)}};
        java.util.Set<String> off = new java.util.HashSet<>();
        if (!sd) {
            off.add("npu");
            off.add("gpu");
        }
        body.addView(segmented(a, devs, sd ? OnPhone.device(a) : "cpu", false, d -> {
            OnPhone.setDevice(a, d);
            build();
        }, off, d -> android.widget.Toast.makeText(a, R.string.onphone_not_snapdragon, android.widget.Toast.LENGTH_LONG).show()), full());
        String ran = OnPhone.ranOn(a);
        String info = !sd ? a.getString(R.string.onphone_not_snapdragon) : a.getString(R.string.onphone_runs_on_info);
        if (!ran.isEmpty()) info += " " + a.getString(R.string.onphone_ran_on, label(ran));
        TextView ri = text(a, info, 12, MUTED);
        ri.setPaddingRelative(dp(a, 4), dp(a, 8), dp(a, 4), 0);
        body.addView(ri, full());
        LinearLayout think = switchRow(a, a.getString(R.string.onphone_think), a.getString(R.string.onphone_think_info),
                OnPhone.think(a), on -> OnPhone.setThink(a, on));
        think.setPaddingRelative(dp(a, 4), dp(a, 16), dp(a, 4), 0);
        body.addView(think, full());

        TextView ml = sectionLabel(a, a.getString(R.string.onphone_models));
        ml.setPaddingRelative(0, dp(a, 20), 0, dp(a, 8));
        body.addView(ml);
        LinearLayout g = group(a);
        for (OnPhone.Model m : OnPhone.MODELS) addToGroup(a, g, modelRow(m), 20);
        body.addView(g, full());

        TextView src = text(a, downloadNote(a, a.getString(R.string.onphone_source, OnPhone.ENGINE)), 12, MUTED);
        src.setPaddingRelative(dp(a, 4), dp(a, 16), dp(a, 4), 0);
        body.addView(src, full());
    }

    private String label(String dev) {
        switch (dev) {
            case "npu": return a.getString(R.string.onphone_dev_npu);
            case "gpu": return a.getString(R.string.onphone_dev_gpu);
            default: return a.getString(R.string.onphone_dev_cpu);
        }
    }

    private View modelRow(OnPhone.Model m) {
        LinearLayout r = column(a);
        r.setPaddingRelative(dp(a, 20), dp(a, 14), dp(a, 16), dp(a, 14));
        LinearLayout head = row(a);
        head.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout names = column(a);
        names.addView(text(a, m.name, 16, TEXT));
        names.addView(monoText(a, a.getString(R.string.onphone_size_license,
                Formatter.formatShortFileSize(a, m.size), m.license), 12, MUTED));
        head.addView(names, weight1());
        boolean have = OnPhone.downloaded(a, m), going = m.id.equals(busy);
        Button b = going ? outlinedButton(a, a.getString(R.string.onphone_cancel), TEXT)
                : have ? outlinedButton(a, a.getString(R.string.onphone_delete), ERR)
                : button(a, a.getString(R.string.onphone_download), busy == null);
        b.setEnabled(going || have || busy == null);
        b.setOnClickListener(v -> {
            if (going) {
                OnPhone.cancel(a);
            } else if (have) {
                confirmSheet(a, a.getString(R.string.onphone_delete_title, m.name), a.getString(R.string.onphone_delete_text),
                        a.getString(R.string.onphone_delete), true, () -> {
                            OnPhone.delete(a, m);
                            build();
                        });
            } else {
                start(m);
            }
        });
        head.addView(b, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(a, 40)));
        r.addView(head, full());
        TextView note = text(a, a.getString(m.note), 13, MUTED);
        note.setPaddingRelative(0, dp(a, 6), 0, 0);
        r.addView(note, full());
        String status = null;
        if (going) {
            long eng = OnPhone.engineReady(a) ? 0 : OnPhone.engineProgress(a);
            if (!OnPhone.engineReady(a)) {
                status = a.getString(R.string.onphone_engine_downloading, pct(eng, OnPhone.engineSize()));
            } else {
                status = a.getString(R.string.onphone_downloading, pct(OnPhone.progress(a, m), m.size));
            }
            ProgressBar bar = new ProgressBar(a, null, android.R.attr.progressBarStyleHorizontal);
            bar.setMax(100);
            bar.setProgress(OnPhone.engineReady(a) ? pct(OnPhone.progress(a, m), m.size) : pct(eng, OnPhone.engineSize()));
            bar.setProgressTintList(android.content.res.ColorStateList.valueOf(ACCENT));
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(a, 6));
            bp.topMargin = dp(a, 8);
            r.addView(bar, bp);
        } else if (have) {
            status = a.getString(R.string.onphone_downloaded);
        } else if (failed.containsKey(m.id)) {
            status = a.getString(R.string.onphone_failed, failed.get(m.id));
        }
        if (status != null) {
            TextView st = text(a, status, 12, failed.containsKey(m.id) && !have && !going ? ERR : have ? OK : MUTED);
            st.setPaddingRelative(0, dp(a, 6), 0, 0);
            r.addView(st, full());
        }
        return r;
    }

    private static int pct(long done, long all) {
        return all <= 0 ? 0 : (int) Math.min(100, done * 100 / all);
    }

    /** Before a download: enough room, and a word first when it would go over mobile data. */
    private void start(OnPhone.Model m) {
        long need = m.size + (OnPhone.engineReady(a) ? 0 : 300L * 1024 * 1024);
        long free = Linux.root(a).getUsableSpace();
        if (free < need + 500L * 1024 * 1024) {
            android.widget.Toast.makeText(a, a.getString(R.string.onphone_no_space, Formatter.formatShortFileSize(a, need),
                    Formatter.formatShortFileSize(a, free)), android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        android.net.ConnectivityManager cm = a.getSystemService(android.net.ConnectivityManager.class);
        if (cm != null && cm.isActiveNetworkMetered()) {
            confirmSheet(a, a.getString(R.string.onphone_metered_title), a.getString(R.string.onphone_metered_text, m.name,
                    Formatter.formatShortFileSize(a, need)), a.getString(R.string.onphone_download), false, () -> go(m));
            return;
        }
        go(m);
    }

    private void go(OnPhone.Model m) {
        if (!Linux.installed(a)) {
            android.widget.Toast.makeText(a, R.string.helper_linux_missing, android.widget.Toast.LENGTH_LONG).show();
            return;
        }
        busy = m.id;
        failed.remove(m.id);
        OnPhone.cancelled = false;
        final android.content.Context app = a.getApplicationContext();
        new Thread(() -> {
            String err = OnPhone.download(app, m);
            if (err != null && !OnPhone.downloaded(app, m) && !OnPhone.cancelled) failed.put(m.id, err);
            busy = null;
            main.post(() -> {
                if (!a.isFinishing()) build();
            });
        }, "onphone-download").start();
        build();
        main.postDelayed(tick, 1000);
    }
}
