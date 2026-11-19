package app.buddy.assistant;

import android.app.Application;
import android.content.Context;

/** The app's context, for code with no Context at hand (static helpers) that needs a string in the user's language. */
public class BuddyApp extends Application {
    private static Context ctx;

    @Override
    public void onCreate() {
        super.onCreate();
        ctx = this;
        try {
            BankWatch.sync(this); // Bank mode's watch outlives the app: it starts again with it, when it's on
        } catch (Exception ignored) {
            // Android may refuse a foreground service started from the background; the next app start or boot does it
        }
    }

    static Context context() {
        return ctx;
    }

    /** String resource id in the app's language, formatted with args if there are any. */
    static String str(int id, Object... args) {
        return args.length == 0 ? ctx.getString(id) : ctx.getString(id, args);
    }

    /** Plural resource id for the count n, formatted with args (usually n itself). */
    static String plural(int id, int n, Object... args) {
        return ctx.getResources().getQuantityString(id, n, args);
    }
}
