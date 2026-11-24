package app.buddy.assistant;

import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.List;
import static app.buddy.assistant.Ui.*;

/**
 * "Share to Buddy" from any app: a photo, a file, a link or text. The user picks a new chat or a recent one; the files
 * are copied into Buddy's storage at once (the share's read access ends with this screen) and the chat opens with them
 * attached and the text in its message box, to send when they're ready. Nothing is sent on its own. A file too big to
 * attach can go to the chat's Linux tools instead (LinuxFiles).
 */
public final class ShareActivity extends Screen {
    /** Recent chats offered besides new ones. */
    private static final int RECENT = 8;

    private ChatHub hub;
    private boolean going;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        hub = ChatHub.get(this);
        if (!SetupState.signedIn(this, Prefs.backend(this))) { // not set up yet: the main screen leads the way
            startActivity(new Intent(this, MainActivity.class));
            finish();
            return;
        }
        // a full screen that scrolls: the share may open in a small window (a chat bubble, split screen)
        LinearLayout root = column(this);
        root.setBackgroundColor(BG);
        root.setFitsSystemWindows(true);
        LinearLayout top = row(this);
        top.setPaddingRelative(dp(this, 4), dp(this, 4), dp(this, 16), dp(this, 4));
        android.widget.ImageButton close = iconButton(this, R.drawable.ms_arrow_back, getString(R.string.perms_back));
        close.setLayoutParams(new LinearLayout.LayoutParams(dp(this, 44), dp(this, 44)));
        close.setOnClickListener(v -> finish());
        top.addView(close);
        TextView h = title(this, getString(R.string.share_title), 18);
        h.setPaddingRelative(dp(this, 6), 0, 0, 0);
        top.addView(h, weight1());
        root.addView(top, full());
        android.widget.ScrollView sv = new android.widget.ScrollView(this);
        LinearLayout c = column(this);
        c.setPaddingRelative(dp(this, 16), 0, dp(this, 16), dp(this, 24));
        sv.addView(c);
        root.addView(sv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));
        setContentView(root);

        // a new chat with any agent that's signed in, the default first
        c.addView(sectionLabel(this, getString(R.string.main_new_chat)));
        String def = Prefs.backend(this);
        c.addView(choice(def, getString(R.string.share_new_chat, Account.of(def).name()), null), full());
        for (Account acc : Account.ALL) {
            if (acc.agent.equals(def) || !SetupState.signedIn(this, acc.agent)) continue;
            c.addView(choice(acc.agent, getString(R.string.share_new_chat, acc.name()), null), full());
        }
        // a working chat refuses attachments, so only idle ones are offered
        int n = 0;
        for (Sessions.Session s : new ArrayList<>(hub.sessions.all)) {
            if (n == RECENT) break;
            if (s.running || s.archived || !s.parent.isEmpty() || s.messages.isEmpty() || !SetupState.signedIn(this, s.backend)) continue;
            if (n == 0) c.addView(sectionLabel(this, getString(R.string.main_section_recent)));
            c.addView(choice(s.backend, s.label(), s), full());
            n++;
        }
    }

    private LinearLayout choice(String backend, String label, Sessions.Session ses) {
        LinearLayout r = row(this);
        r.setPaddingRelative(dp(this, 4), dp(this, 12), dp(this, 4), dp(this, 12));
        r.setBackgroundResource(ripple(this, false));
        r.addView(agentTile(this, backend));
        TextView t = single(title(this, label, 15));
        t.setPaddingRelative(dp(this, 14), 0, 0, 0);
        r.addView(t, weight1());
        r.setOnClickListener(v -> {
            if (going) return;
            going = true;
            open(ses != null ? ses : hub.create(backend, true, Prefs.devMode(this)));
        });
        return r;
    }

    /**
     * Copies the shared files (off the main thread), then opens the chat with them and the text. A file past the 25 MB
     * an attachment can be, shared to a chat with Linux tools, is offered to them instead (issue #76).
     */
    private void open(Sessions.Session ses) {
        Intent in = getIntent();
        List<Uri> uris = uris(in);
        String text = text(in);
        boolean linux = ses.dev && !Lite.on(ses) && ses.parent.isEmpty() && Linux.installed(this); // (lite: no Linux tools)
        new Thread(() -> {
            ArrayList<String> files = new ArrayList<>();
            List<Uri> big = new ArrayList<>();
            List<String> names = new ArrayList<>();
            String err = null;
            for (Uri u : uris) {
                Object[] a = LinuxFiles.about(this, u);
                try {
                    if (linux && (Long) a[1] > Attachments.MAX_BYTES) throw new Attachments.TooLarge("");
                    files.add(Attachments.importUri(this, u));
                } catch (Attachments.TooLarge e) {
                    if (!linux) {
                        err = e.getMessage();
                        continue;
                    }
                    big.add(u);
                    names.add(LinuxFiles.clean((String) a[0]));
                } catch (Exception e) {
                    err = e.getMessage();
                }
            }
            final String error = err;
            runOnUiThread(() -> {
                if (isDestroyed()) return;
                if (big.isEmpty()) go(ses, files, text, error);
                else offerLinux(ses, big, names, files, text, error);
            });
        }, "share").start();
    }

    /**
     * Too big to attach: sent straight to the chat's folder in Buddy's Linux instead, if the user wants. This screen
     * stays until the files are opened (the share's read access ends with it); the copy itself goes on after.
     */
    private void offerLinux(Sessions.Session ses, List<Uri> big, List<String> names, ArrayList<String> files, String text,
                            String error) {
        LinearLayout c = column(this);
        c.addView(title(this, getString(R.string.share_linux_title), 18));
        TextView m = Ui.text(this, getString(R.string.share_linux_text, android.text.TextUtils.join(", ", names)), 14, MUTED);
        m.setPaddingRelative(0, dp(this, 6), 0, dp(this, 18));
        c.addView(m);
        LinearLayout btns = row(this);
        android.widget.Button no = button(this, getString(android.R.string.cancel), false);
        android.widget.Button yes = button(this, getString(R.string.share_linux_send), true);
        btns.addView(no, new LinearLayout.LayoutParams(0, dp(this, 48), 1));
        LinearLayout.LayoutParams yp = new LinearLayout.LayoutParams(0, dp(this, 48), 1);
        yp.setMarginStart(dp(this, 10));
        btns.addView(yes, yp);
        c.addView(btns, full());
        android.app.Dialog d = bottomSheet(this, c);
        final boolean[] sent = {false};
        // not sent (Cancel, or the sheet swiped away): the chat opens as before, with what did fit
        d.setOnDismissListener(x -> {
            if (!sent[0]) go(ses, files, text, getString(R.string.attach_too_large, names.get(0)));
        });
        no.setOnClickListener(v -> d.dismiss());
        yes.setOnClickListener(v -> {
            sent[0] = true;
            d.dismiss();
            new Thread(() -> {
                String err = LinuxFiles.send(this, ses.id, big);
                runOnUiThread(() -> go(ses, files, text, err != null ? err : error));
            }, "share-linux").start();
        });
        d.show();
    }

    /** Opens the chat with the attachments and the text. */
    private void go(Sessions.Session ses, ArrayList<String> files, String text, String error) {
        if (error != null) Toast.makeText(this, error, Toast.LENGTH_LONG).show();
        hub.select(ses);
        // in Buddy's own task, not the sharing app's
        Intent i = new Intent(this, ChatActivity.class).putExtra("id", ses.id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (!text.isEmpty()) i.putExtra("draft", text);
        if (!files.isEmpty()) i.putStringArrayListExtra("files", files);
        startActivity(i);
        finish();
    }

    @SuppressWarnings("deprecation")
    private static List<Uri> uris(Intent in) {
        List<Uri> out = new ArrayList<>();
        if (Intent.ACTION_SEND_MULTIPLE.equals(in.getAction())) {
            ArrayList<Uri> list = in.getParcelableArrayListExtra(Intent.EXTRA_STREAM);
            if (list != null) for (Uri u : list) if (u != null) out.add(u);
        } else {
            Uri u = in.getParcelableExtra(Intent.EXTRA_STREAM);
            if (u != null) out.add(u);
        }
        return out;
    }

    /** The shared text or link, with its subject (a page's title) when that adds something. */
    private static String text(Intent in) {
        CharSequence t = in.getCharSequenceExtra(Intent.EXTRA_TEXT);
        String text = t == null ? "" : t.toString().trim();
        String subject = in.getStringExtra(Intent.EXTRA_SUBJECT);
        if (subject != null && !subject.trim().isEmpty() && !text.contains(subject.trim())) {
            text = text.isEmpty() ? subject.trim() : subject.trim() + "\n" + text;
        }
        return text;
    }
}
