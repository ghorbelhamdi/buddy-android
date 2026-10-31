package app.buddy.assistant;

import android.content.Intent;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import static app.buddy.assistant.Ui.*;

/** Settings → Backup and move: a backup file in Downloads/Buddy, or a restore from one (see Backup). */
public class BackupActivity extends Screen {
    private static final int PICK = 43;
    private LinearLayout body;
    private TextView status;
    private Button backup, restore;

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
        TextView t = title(this, getString(R.string.backup_title), 18);
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
        setContentView(protect(frame)); // only the user backs up or restores

        body.addView(text(this, getString(R.string.backup_intro), 13, MUTED), full());
        body.addView(text(this, getString(R.string.backup_left_out), 13, MUTED), gap(8));
        backup = button(this, getString(R.string.backup_now), true);
        backup.setOnClickListener(v -> run(true, null));
        body.addView(backup, big(18));
        restore = button(this, getString(R.string.backup_restore), false);
        restore.setOnClickListener(v -> startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT)
                .addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"), PICK));
        body.addView(restore, big(8));
        status = text(this, "", 14, TEXT);
        status.setVisibility(View.GONE);
        body.addView(status, gap(16));
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != PICK || res != RESULT_OK || data == null || data.getData() == null) return;
        android.net.Uri uri = data.getData();
        confirmSheet(this, getString(R.string.backup_restore_title), getString(R.string.backup_restore_text),
                getString(R.string.backup_restore), true, () -> run(false, uri));
    }

    private void run(boolean make, android.net.Uri from) {
        backup.setEnabled(false);
        restore.setEnabled(false);
        status.setVisibility(View.VISIBLE);
        status.setText(make ? R.string.backup_making : R.string.backup_restoring);
        new Thread(() -> {
            String msg;
            boolean restart = false;
            try {
                if (make) {
                    msg = getString(R.string.backup_made, Backup.create(this));
                } else {
                    boolean all = Backup.restore(this, from);
                    msg = getString(all ? R.string.backup_restored : R.string.backup_restored_no_linux);
                    restart = true;
                }
            } catch (Exception e) {
                msg = getString(R.string.backup_failed, String.valueOf(e.getMessage()));
            }
            final String m = msg;
            final boolean again = restart;
            runOnUiThread(() -> {
                status.setText(m);
                backup.setEnabled(true);
                restore.setEnabled(true);
                if (again) status.postDelayed(() -> Backup.restart(this), 2500);
            });
        }, "backup").start();
    }

    private LinearLayout.LayoutParams big(int top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(this, 52));
        p.topMargin = dp(this, top);
        return p;
    }

    private LinearLayout.LayoutParams gap(int top) {
        LinearLayout.LayoutParams p = full();
        p.topMargin = dp(this, top);
        return p;
    }
}
