package app.buddy.assistant;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.provider.MediaStore;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * A see-through screen for the phone tools that need Buddy in front: reading the clipboard (Android allows only the
 * app in front), taking a photo with the camera, and asking for the location permission. It does its one thing,
 * hands the result to the waiting tool and closes.
 */
public class PhoneToolActivity extends Activity {
    static final String CLIPBOARD = "clipboard", PHOTO = "photo", LOCATION_PERMISSION = "location";
    private static final int REQ = 7;

    private static CompletableFuture<Object> pending;
    private boolean done;

    /** Opens the screen for this job and waits for its result (null when it didn't come in time). One at a time. */
    static synchronized Object run(Context c, String job, String arg, int timeoutSeconds) throws InterruptedException {
        CompletableFuture<Object> f = new CompletableFuture<>();
        pending = f;
        c.startActivity(new Intent(c, PhoneToolActivity.class).putExtra("job", job).putExtra("arg", arg)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_NO_ANIMATION));
        try {
            return f.get(timeoutSeconds, TimeUnit.SECONDS);
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
            return null;
        } finally {
            pending = null;
        }
    }

    private void finish(Object result) {
        if (done) return;
        done = true;
        CompletableFuture<Object> f = pending;
        if (f != null) f.complete(result);
        finish();
        overridePendingTransition(0, 0);
    }

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        String job = getIntent().getStringExtra("job");
        if (state != null) return; // recreated: the first one already started its job
        if (PHOTO.equals(job)) {
            FilesProvider.captureFile(this).delete();
            Intent cam = new Intent(MediaStore.ACTION_IMAGE_CAPTURE).putExtra(MediaStore.EXTRA_OUTPUT, FilesProvider.captureUri())
                    .addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION | Intent.FLAG_GRANT_READ_URI_PERMISSION);
            cam.setClipData(ClipData.newRawUri("photo", FilesProvider.captureUri()));
            try {
                startActivityForResult(cam, REQ);
            } catch (Exception e) {
                finish(false);
            }
        } else if (LOCATION_PERMISSION.equals(job)) {
            requestPermissions(new String[]{android.Manifest.permission.ACCESS_FINE_LOCATION,
                    android.Manifest.permission.ACCESS_COARSE_LOCATION}, REQ);
        } else if (!CLIPBOARD.equals(job)) {
            finish(null);
        }
    }

    @Override
    public void onWindowFocusChanged(boolean focus) {
        super.onWindowFocusChanged(focus);
        if (!focus || !CLIPBOARD.equals(getIntent().getStringExtra("job"))) return;
        String text = "";
        try {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            ClipData clip = cm.getPrimaryClip();
            if (clip != null && clip.getItemCount() > 0) {
                CharSequence t = clip.getItemAt(0).coerceToText(this);
                if (t != null) text = t.toString();
            }
        } catch (Exception ignored) {
        }
        finish(text);
    }

    @Override
    protected void onActivityResult(int req, int result, Intent data) {
        super.onActivityResult(req, result, data);
        finish(result == RESULT_OK);
    }

    @Override
    public void onRequestPermissionsResult(int req, String[] perms, int[] results) {
        super.onRequestPermissionsResult(req, perms, results);
        boolean any = false;
        for (int r : results) any |= r == PackageManager.PERMISSION_GRANTED;
        finish(any);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (!done && isFinishing()) {
            CompletableFuture<Object> f = pending;
            if (f != null) f.complete(null);
        }
    }
}
