package app.buddy.assistant;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;

/** Starts the buddy-run helper in Termux through Termux's RUN_COMMAND service. */
final class Termux {
    static final String PACKAGE = "com.termux";
    static final String PERMISSION = "com.termux.permission.RUN_COMMAND";
    static final String HOME = "/data/data/com.termux/files/home";
    static final String HELPER = HOME + "/bin/buddy-run";

    private Termux() {
    }

    static boolean installed(Context c) {
        try {
            c.getPackageManager().getPackageInfo("com.termux", 0);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }

    static boolean permitted(Context c) {
        return c.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED;
    }

    /** Throws with a user-readable message if the command cannot be started. */
    static void run(Context c, String... args) {
        run(c, true, args);
    }

    /** Run the helper in a new, visible Termux terminal tab (for interactive sessions). */
    static void runInTerminal(Context c, String... args) {
        run(c, false, args);
    }

    private static void run(Context c, boolean background, String... args) {
        if (!installed(c)) throw new IllegalStateException("Termux is not installed.");
        if (!permitted(c)) throw new IllegalStateException("Buddy needs the Termux permission. Open the Buddy app to grant it.");
        String[] full = new String[args.length + 1];
        full[0] = Prefs.token(c);
        System.arraycopy(args, 0, full, 1, args.length);

        Intent i = new Intent("com.termux.RUN_COMMAND");
        i.setClassName("com.termux", "com.termux.app.RunCommandService");
        i.putExtra("com.termux.RUN_COMMAND_PATH", HELPER);
        i.putExtra("com.termux.RUN_COMMAND_ARGUMENTS", full);
        i.putExtra("com.termux.RUN_COMMAND_WORKDIR", HOME);
        i.putExtra("com.termux.RUN_COMMAND_BACKGROUND", background);
        // 0 = open the new terminal session in front
        i.putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "0");
        try {
            c.startService(i);
        } catch (IllegalStateException e) {
            c.startForegroundService(i);
        }
    }
}
