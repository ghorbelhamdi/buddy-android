package app.buddy.assistant;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.location.Location;
import android.location.LocationManager;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.PowerManager;
import android.os.StatFs;
import android.provider.AlarmClock;
import android.provider.MediaStore;
import android.util.Base64;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Phone tools that don't drive the screen: the clipboard, alarms and timers, the share sheet, saving to Downloads, the
 * phone's status, a photo the user takes and the phone's location. Reading the clipboard and the location ask the user
 * first, every time; a notification rule's run (someone else's message) can't use the ones that read or send out.
 */
final class PhoneTools {
    static final java.util.Set<String> NAMES = new java.util.HashSet<>(java.util.Arrays.asList(
            "clipboard_read", "clipboard_write", "set_alarm", "set_timer", "share", "save_to_downloads", "device_status",
            "take_photo", "location"));
    /** Not in a notification rule's run: they read the user's things or send something out. */
    private static final java.util.Set<String> NOT_IN_RULES = new java.util.HashSet<>(java.util.Arrays.asList(
            "clipboard_read", "share", "save_to_downloads", "take_photo", "location"));

    private final BuddyService svc;

    PhoneTools(BuddyService svc) {
        this.svc = svc;
    }

    /** The tools' descriptions, for the agent. */
    static void list(JSONArray t) throws org.json.JSONException {
        t.put(Tools.tool("clipboard_read", "Read the text the user last copied (the clipboard). Buddy asks the user "
                + "first, every time. Returns the text, or why not.", Tools.props()));
        t.put(Tools.tool("clipboard_write", "Put text on the clipboard, so the user can paste it in any app.",
                Tools.props().put("text", Tools.strProp("The text to copy")), "text"));
        t.put(Tools.tool("set_alarm", "Set an alarm in the phone's Clock app, without opening it.",
                Tools.props().put("time", Tools.strProp("The time, 24-hour: \"07:30\""))
                        .put("label", Tools.strProp("Optional: what it's for"))
                        .put("days", Tools.strProp("Optional: repeat on these days, e.g. \"mon,tue,wed,thu,fri\"")),
                "time"));
        t.put(Tools.tool("set_timer", "Start a countdown timer in the phone's Clock app, without opening it.",
                Tools.props().put("seconds", Tools.intProp("Length in seconds (1 to 86400)"))
                        .put("label", Tools.strProp("Optional: what it's for")),
                "seconds"));
        t.put(Tools.tool("share", "Open Android's share sheet with a text or a file, so the user picks the app to send "
                + "it with (a chat, an email, a cloud drive). The user chooses; nothing is sent by opening it. The file "
                + "must be in your work folder (/root/.buddy/work) or this chat's files.",
                Tools.props().put("text", Tools.strProp("Text to share (or a caption with the file)"))
                        .put("path", Tools.strProp("Optional: full path of a file in Buddy's Linux"))
                        .put("title", Tools.strProp("Optional: the share sheet's title"))));
        t.put(Tools.tool("save_to_downloads", "Copy a file from Buddy's Linux into the phone's Downloads folder "
                + "(Downloads/Buddy), where the user and their apps can open it. The file must be in your work folder "
                + "(/root/.buddy/work) or this chat's files.",
                Tools.props().put("path", Tools.strProp("Full path of the file in Buddy's Linux"))
                        .put("name", Tools.strProp("Optional: the file's name in Downloads")),
                "path"));
        t.put(Tools.tool("device_status", "The phone's battery, charging, network, local date and time with time zone, "
                + "free storage and ringer mode.", Tools.props()));
        t.put(Tools.tool("take_photo", "Open the camera so the user takes a photo; returns the picture (and where it's "
                + "saved in this chat's files). The user presses the shutter; it waits up to 3 minutes.",
                Tools.props().put("why", Tools.strProp("Optional: one line shown to the user, e.g. \"the label on the box\""))));
        if (!Flavor.PLAY) {
            t.put(Tools.tool("location", "The phone's current location: latitude, longitude, accuracy and the address "
                    + "when known. Buddy asks the user first, every time.", Tools.props()));
        }
    }

    JSONObject call(String name, JSONObject a, boolean ruleRun) throws Exception {
        if (ruleRun && NOT_IN_RULES.contains(name)) {
            return text("Not available in a notification rule's run. Say in your reply what you wanted to do.");
        }
        switch (name) {
            case "clipboard_read": return text(clipboardRead());
            case "clipboard_write": return text(clipboardWrite(a.getString("text")));
            case "set_alarm": return text(setAlarm(a));
            case "set_timer": return text(setTimer(a));
            case "share": return text(share(a));
            case "save_to_downloads": return text(saveToDownloads(a));
            case "device_status": return text(deviceStatus());
            case "take_photo": return takePhoto(a.optString("why"));
            case "location": return text(location());
            default: throw new IllegalArgumentException("Unknown tool " + name);
        }
    }

    private static JSONObject text(String s) throws Exception {
        return new JSONObject().put("content", new JSONArray().put(new JSONObject().put("type", "text").put("text", s)));
    }

    // ------------------------------------------------------------- clipboard

    private String clipboardRead() throws Exception {
        String answer = svc.askConfirm(svc.getString(R.string.tools_card_clipboard), false);
        if (!answer.startsWith("APPROVED")) return "Not read. " + answer;
        // Android lets only the app in front read the clipboard: a see-through screen of Buddy's reads it and closes
        Object r = PhoneToolActivity.run(svc, PhoneToolActivity.CLIPBOARD, null, 10);
        if (r == null) return "Couldn't read the clipboard (Buddy couldn't come to the front). Ask the user to paste it.";
        String s = r.toString();
        return s.isEmpty() ? "The clipboard is empty, or holds no text." : "Clipboard:\n" + s;
    }

    private String clipboardWrite(String text) throws Exception {
        AtomicReference<String> err = new AtomicReference<>();
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        svc.runOnMain(() -> {
            try {
                ClipboardManager cm = (ClipboardManager) svc.getSystemService(Context.CLIPBOARD_SERVICE);
                cm.setPrimaryClip(ClipData.newPlainText("Buddy", text));
            } catch (Exception e) {
                err.set(String.valueOf(e.getMessage()));
            }
            done.countDown();
        });
        done.await(5, TimeUnit.SECONDS);
        return err.get() == null ? "Copied: the user can paste it now." : "Couldn't copy: " + err.get();
    }

    // ------------------------------------------------------------- clock

    private String setAlarm(JSONObject a) throws Exception {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^(\\d{1,2}):(\\d{2})$").matcher(a.getString("time").trim());
        if (!m.find()) return "Give the time as HH:MM, 24-hour, e.g. \"07:30\".";
        int h = Integer.parseInt(m.group(1)), min = Integer.parseInt(m.group(2));
        if (h > 23 || min > 59) return "Give the time as HH:MM, 24-hour, e.g. \"07:30\".";
        Intent i = new Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR, h)
                .putExtra(AlarmClock.EXTRA_MINUTES, min).putExtra(AlarmClock.EXTRA_SKIP_UI, true);
        if (!a.optString("label").isEmpty()) i.putExtra(AlarmClock.EXTRA_MESSAGE, a.optString("label"));
        java.util.ArrayList<Integer> days = days(a.optString("days"));
        if (!days.isEmpty()) i.putExtra(AlarmClock.EXTRA_DAYS, days);
        if (!start(i)) return "The phone's Clock app doesn't take alarms from other apps. Open it with open_app instead.";
        return String.format(Locale.ROOT, "Alarm set for %02d:%02d%s in the Clock app.", h, min,
                days.isEmpty() ? "" : " (repeating)");
    }

    private static java.util.ArrayList<Integer> days(String s) {
        java.util.ArrayList<Integer> out = new java.util.ArrayList<>();
        String[] names = {"sun", "mon", "tue", "wed", "thu", "fri", "sat"};
        for (String d : s.toLowerCase(Locale.ROOT).split("[,\\s]+")) {
            for (int i = 0; i < names.length; i++) {
                if (d.startsWith(names[i]) && !out.contains(i + 1)) out.add(i + 1); // Calendar.SUNDAY is 1
            }
        }
        return out;
    }

    private String setTimer(JSONObject a) throws Exception {
        int s = a.getInt("seconds");
        if (s < 1 || s > 86400) return "Give 1 to 86400 seconds.";
        Intent i = new Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, s)
                .putExtra(AlarmClock.EXTRA_SKIP_UI, true);
        if (!a.optString("label").isEmpty()) i.putExtra(AlarmClock.EXTRA_MESSAGE, a.optString("label"));
        if (!start(i)) return "The phone's Clock app doesn't take timers from other apps. Open it with open_app instead.";
        return "Timer started: " + (s >= 60 ? s / 60 + " min " : "") + (s % 60 > 0 || s < 60 ? s % 60 + " s" : "").trim() + ".";
    }

    private boolean start(Intent i) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (i.resolveActivity(svc.getPackageManager()) == null) return false;
        svc.runOnMain(() -> svc.startActivity(i));
        return true;
    }

    // ------------------------------------------------------------- files

    /** A file the agent may hand out: in the Linux tools' work folder or this chat's files, nowhere else. */
    private File agentFile(String path) throws Exception {
        if (path == null || !path.startsWith("/")) throw new IllegalArgumentException("Give the file's full path.");
        File root = Linux.root(svc).getCanonicalFile();
        File f = new File(root, path.substring(1)).getCanonicalFile();
        String chat = McpServer.CALLER_CHAT.get();
        File work = new File(root, "root/.buddy/work").getCanonicalFile();
        File files = chat == null ? null : new File(root, "root/.buddy/sessions/" + chat + "/files").getCanonicalFile();
        boolean ok = f.getPath().startsWith(work.getPath() + "/")
                || (files != null && f.getPath().startsWith(files.getPath() + "/"));
        if (!ok) throw new IllegalArgumentException("Only files in /root/.buddy/work or this chat's files can leave Buddy's Linux.");
        if (!f.isFile()) throw new IllegalArgumentException("There's no file at " + path + ".");
        return f;
    }

    private String share(JSONObject a) throws Exception {
        String text = a.optString("text"), path = a.optString("path");
        if (text.isEmpty() && path.isEmpty()) return "Give a text, a file path, or both.";
        Intent send = new Intent(Intent.ACTION_SEND);
        if (!path.isEmpty()) {
            File f = agentFile(path);
            File out = FilesProvider.shareFile(svc, f.getName());
            copy(new FileInputStream(f), new java.io.FileOutputStream(out));
            Uri u = FilesProvider.shareUri(f.getName());
            send.setType(svc.getContentResolver().getType(u)).putExtra(Intent.EXTRA_STREAM, u)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            send.setClipData(ClipData.newRawUri(f.getName(), u));
        } else {
            send.setType("text/plain");
        }
        if (!text.isEmpty()) send.putExtra(Intent.EXTRA_TEXT, text);
        Intent chooser = Intent.createChooser(send, a.optString("title", null));
        chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_GRANT_READ_URI_PERMISSION);
        svc.runOnMain(() -> svc.startActivity(chooser));
        return "The share sheet is open: the user picks the app. Nothing has been sent; if they asked you to send it "
                + "somewhere, the approval rules still apply in that app.";
    }

    private String saveToDownloads(JSONObject a) throws Exception {
        File f = agentFile(a.getString("path"));
        String name = a.optString("name", f.getName()).replaceAll("[/\\\\]", "_");
        if (name.isEmpty()) name = f.getName();
        ContentValues v = new ContentValues();
        v.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        v.put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/Buddy");
        v.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri u = svc.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
        if (u == null) return "Couldn't save to Downloads.";
        try (OutputStream o = svc.getContentResolver().openOutputStream(u)) {
            copy(new FileInputStream(f), o);
        }
        v.clear();
        v.put(MediaStore.MediaColumns.IS_PENDING, 0);
        svc.getContentResolver().update(u, v, null, null);
        return "Saved to Downloads/Buddy/" + name + ".";
    }

    private static void copy(InputStream in, OutputStream out) throws Exception {
        try (InputStream i = in; OutputStream o = out) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = i.read(buf)) > 0) o.write(buf, 0, n);
        }
    }

    // ------------------------------------------------------------- status

    private String deviceStatus() {
        StringBuilder b = new StringBuilder();
        Intent bat = svc.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (bat != null) {
            int level = bat.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), scale = bat.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
            int plugged = bat.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0);
            b.append("Battery: ").append(level * 100 / Math.max(1, scale)).append('%')
                    .append(plugged != 0 ? ", charging" : ", not charging");
            PowerManager pm = (PowerManager) svc.getSystemService(Context.POWER_SERVICE);
            if (pm != null && pm.isPowerSaveMode()) b.append(", battery saver on");
            b.append('\n');
        }
        ConnectivityManager cm = (ConnectivityManager) svc.getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkCapabilities nc = cm == null ? null : cm.getNetworkCapabilities(cm.getActiveNetwork());
        String net = nc == null ? "offline" : nc.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ? "Wi-Fi"
                : nc.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ? "mobile data"
                : nc.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) ? "Ethernet" : "connected";
        if (nc != null && nc.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) net += " (VPN)";
        b.append("Network: ").append(net).append('\n');
        b.append("Local time: ").append(new SimpleDateFormat("EEEE yyyy-MM-dd HH:mm", Locale.ENGLISH).format(new Date()))
                .append(" (").append(java.util.TimeZone.getDefault().getID()).append(")\n");
        StatFs fs = new StatFs(android.os.Environment.getDataDirectory().getPath());
        b.append(String.format(Locale.ROOT, "Storage: %.1f GB free of %.1f GB\n", fs.getAvailableBytes() / 1e9, fs.getTotalBytes() / 1e9));
        AudioManager am = (AudioManager) svc.getSystemService(Context.AUDIO_SERVICE);
        if (am != null) {
            int mode = am.getRingerMode();
            b.append("Ringer: ").append(mode == AudioManager.RINGER_MODE_SILENT ? "silent"
                    : mode == AudioManager.RINGER_MODE_VIBRATE ? "vibrate" : "normal");
        }
        return b.toString().trim();
    }

    // ------------------------------------------------------------- camera

    private JSONObject takePhoto(String why) throws Exception {
        if (!why.isEmpty()) svc.showStatus(svc.getString(R.string.tools_photo_why, why));
        Object r = PhoneToolActivity.run(svc, PhoneToolActivity.PHOTO, null, 180);
        File shot = FilesProvider.captureFile(svc);
        if (!Boolean.TRUE.equals(r) || shot.length() == 0) return text("No photo: the user closed the camera, or it timed out.");
        // a copy in this chat's files, which the agent can open later
        String chat = McpServer.CALLER_CHAT.get();
        String saved = null;
        if (chat != null && chat.matches("[A-Za-z0-9_-]+")) {
            File dir = new File(Linux.root(svc), "root/.buddy/sessions/" + chat + "/files");
            if (dir.isDirectory() || dir.mkdirs()) {
                String name = "photo-" + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date()) + ".jpg";
                copy(new FileInputStream(shot), new java.io.FileOutputStream(new File(dir, name)));
                saved = "/root/.buddy/sessions/" + chat + "/files/" + name;
            }
        }
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(shot.getPath(), o);
        o.inSampleSize = 1;
        while (Math.max(o.outWidth, o.outHeight) / o.inSampleSize > 3000) o.inSampleSize *= 2;
        o.inJustDecodeBounds = false;
        Bitmap full = BitmapFactory.decodeFile(shot.getPath(), o);
        if (full == null) return text("The camera saved no picture.");
        float scale = Math.min(1f, 1568f / Math.max(full.getWidth(), full.getHeight()));
        Bitmap small = scale < 1f ? Bitmap.createScaledBitmap(full, Math.round(full.getWidth() * scale),
                Math.round(full.getHeight() * scale), true) : full;
        ByteArrayOutputStream jpg = new ByteArrayOutputStream();
        small.compress(Bitmap.CompressFormat.JPEG, 80, jpg);
        return new JSONObject().put("content", new JSONArray()
                .put(new JSONObject().put("type", "image").put("mimeType", "image/jpeg")
                        .put("data", Base64.encodeToString(jpg.toByteArray(), Base64.NO_WRAP)))
                .put(new JSONObject().put("type", "text").put("text", "The user's photo." + (saved == null ? ""
                        : " Saved as " + saved + "."))));
    }

    // ------------------------------------------------------------- location

    private String location() throws Exception {
        if (Flavor.PLAY) return "Location isn't available in this build of Buddy.";
        String answer = svc.askConfirm(svc.getString(R.string.tools_card_location), false);
        if (!answer.startsWith("APPROVED")) return "Not shared. " + answer;
        if (svc.checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED
                && svc.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Object r = PhoneToolActivity.run(svc, PhoneToolActivity.LOCATION_PERMISSION, null, 120);
            if (!Boolean.TRUE.equals(r)) return "The user didn't allow Buddy to use the location.";
        }
        LocationManager lm = (LocationManager) svc.getSystemService(Context.LOCATION_SERVICE);
        if (lm == null || !lm.isLocationEnabled()) return "Location is turned off on the phone. Ask the user to turn it on.";
        String provider = lm.hasProvider(LocationManager.FUSED_PROVIDER) ? LocationManager.FUSED_PROVIDER
                : lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) ? LocationManager.NETWORK_PROVIDER : LocationManager.GPS_PROVIDER;
        AtomicReference<Location> got = new AtomicReference<>();
        java.util.concurrent.CountDownLatch done = new java.util.concurrent.CountDownLatch(1);
        android.os.CancellationSignal cancel = new android.os.CancellationSignal();
        try {
            lm.getCurrentLocation(provider, cancel, svc.getMainExecutor(), l -> {
                got.set(l);
                done.countDown();
            });
            done.await(25, TimeUnit.SECONDS);
        } catch (SecurityException e) {
            return "Buddy isn't allowed to use the location.";
        } finally {
            cancel.cancel();
        }
        Location l = got.get();
        if (l == null) {
            try {
                l = lm.getLastKnownLocation(provider);
            } catch (SecurityException ignored) {
            }
        }
        if (l == null) return "The phone couldn't find its location just now. Try again outdoors or with Wi-Fi on.";
        StringBuilder b = new StringBuilder(String.format(Locale.ROOT, "Latitude %.6f, longitude %.6f (within about %d m)",
                l.getLatitude(), l.getLongitude(), Math.round(l.getAccuracy())));
        long age = (System.currentTimeMillis() - l.getTime()) / 60000;
        if (age >= 2) b.append(", ").append(age).append(" min old");
        try {
            @SuppressWarnings("deprecation")
            java.util.List<android.location.Address> ad = new android.location.Geocoder(svc, Locale.getDefault())
                    .getFromLocation(l.getLatitude(), l.getLongitude(), 1);
            if (ad != null && !ad.isEmpty() && ad.get(0).getAddressLine(0) != null) {
                b.append("\nAddress: ").append(ad.get(0).getAddressLine(0));
            }
        } catch (Exception ignored) {
        }
        b.append(String.format(Locale.ROOT, "\nMap: https://maps.google.com/?q=%.6f,%.6f", l.getLatitude(), l.getLongitude()));
        return b.toString();
    }
}
