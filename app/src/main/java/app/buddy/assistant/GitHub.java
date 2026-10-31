package app.buddy.assistant;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * GitHub for the Linux tools (Settings → GitHub): installs git and the GitHub CLI in Buddy's Linux and signs gh in with
 * its own device flow (a one-time code the user enters on github.com), then sets git to the account's name and noreply
 * email. Agents with Linux tools can then clone, commit, push and open pull requests as the user.
 */
final class GitHub {
    enum Step { IDLE, INSTALLING, WAITING, FINISHING, ERROR }

    static volatile Step step = Step.IDLE;
    /** The signed-in account's login, "" when signed out, null before the first check. */
    static volatile String login;
    static volatile String code = "", error = "";
    private static Process process;
    private static Runnable listener;
    private static final Handler main = new Handler(Looper.getMainLooper());

    private GitHub() {
    }

    static void setListener(Runnable l) {
        listener = l;
    }

    private static void changed() {
        main.post(() -> {
            Runnable l = listener;
            if (l != null) l.run();
        });
    }

    /** Who gh is signed in as. */
    static void refresh(Context c) {
        final Context app = c.getApplicationContext();
        new Thread(() -> {
            try {
                String out = Linux.exec(app, "command -v gh >/dev/null && gh auth status -h github.com >/dev/null 2>&1 "
                        + "&& gh api user -q .login 2>/dev/null", 40);
                String first = out.split("\n")[0].trim();
                login = out.contains("[exit 0]") && first.matches("[A-Za-z0-9-]+") ? first : "";
            } catch (Exception e) {
                login = "";
            }
            changed();
        }, "github").start();
    }

    static synchronized void signIn(Context c) {
        if (step == Step.INSTALLING || step == Step.WAITING || step == Step.FINISHING) return;
        final Context app = c.getApplicationContext();
        step = Step.INSTALLING;
        code = "";
        error = "";
        changed();
        new Thread(() -> {
            try {
                String inst = Linux.exec(app, "command -v gh >/dev/null && command -v git >/dev/null || apk add -q git github-cli 2>&1; "
                        + "command -v gh >/dev/null && echo HAVE_GH", 600);
                if (!inst.contains("HAVE_GH")) throw new Exception(tail(inst));
                Process p = Linux.builder(app, "/bin/sh", "-c",
                        "gh auth login --web -h github.com -p https --skip-ssh-key 2>&1").start();
                synchronized (GitHub.class) {
                    process = p;
                }
                StringBuilder out = new StringBuilder();
                Pattern codeRe = Pattern.compile("one-time code: ([A-Z0-9]{4}-[A-Z0-9]{4})");
                try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                    for (String line; (line = r.readLine()) != null; ) {
                        out.append(line).append('\n');
                        Matcher m = codeRe.matcher(line);
                        if (m.find()) {
                            code = m.group(1);
                            step = Step.WAITING;
                            changed();
                        }
                    }
                }
                int exit = p.waitFor();
                if (step == Step.IDLE) return; // cancelled
                if (exit != 0) throw new Exception(tail(out.toString()));
                step = Step.FINISHING;
                changed();
                // git pushes through gh's sign-in, as the account's name and its private noreply address
                Linux.exec(app, "gh auth setup-git -h github.com; u=$(gh api user); "
                        + "l=$(echo \"$u\" | jq -r .login); i=$(echo \"$u\" | jq -r .id); n=$(echo \"$u\" | jq -r '.name // .login'); "
                        + "git config --global user.name \"$n\"; git config --global user.email \"$i+$l@users.noreply.github.com\"; "
                        + "git config --global init.defaultBranch main", 60);
                step = Step.IDLE;
                refresh(app);
            } catch (Exception e) {
                if (step == Step.IDLE) return;
                error = String.valueOf(e.getMessage());
                step = Step.ERROR;
                changed();
            } finally {
                synchronized (GitHub.class) {
                    process = null;
                }
            }
        }, "github-login").start();
    }

    static synchronized void cancel() {
        step = Step.IDLE;
        if (process != null) process.destroy();
        changed();
    }

    static void signOut(Context c) {
        final Context app = c.getApplicationContext();
        new Thread(() -> {
            try {
                Linux.exec(app, "gh auth logout -h github.com 2>&1; git config --global --unset credential.https://github.com.helper; "
                        + "git config --global --unset credential.https://gist.github.com.helper; true", 40);
            } catch (Exception ignored) {
            }
            refresh(app);
        }, "github").start();
    }

    private static String tail(String s) {
        s = s.replace("[exit 1]", "").trim();
        return s.length() > 400 ? s.substring(s.length() - 400) : s;
    }
}
