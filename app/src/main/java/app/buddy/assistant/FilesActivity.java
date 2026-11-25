package app.buddy.assistant;

import android.app.Dialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Bundle;
import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import android.text.format.DateUtils;
import android.text.format.Formatter;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static app.buddy.assistant.Ui.*;

/**
 * Files: what is in Buddy's Linux where the agents work, and the way to get files in and out of it. At the top are
 * the places: Projects (/root/.buddy/work, the agents' workspace) and, opened from a chat, This chat (what was sent to
 * its Linux tools, and its attachments). In a folder: upload files of any size from the phone (LinuxFiles copies them),
 * make a folder, and for each file open it in another app, share it, save it to Downloads, rename or delete it, copy
 * its path as the agent sees it, or mention it in the chat.
 * <p>
 * Agents write in these folders, so nothing here trusts what they hold: every path goes through FilePlaces, links are
 * shown as links and never followed, and the whole screen and its sheets are off-limits to agents (protect): only the
 * user moves files in or out, or deletes them.
 */
public class FilesActivity extends Screen {
    /** The chat it was opened from (its id), or none: opened from Settings. */
    static final String EXTRA_CHAT = "chat";
    /** The folder to open at, as the agent sees it. */
    static final String EXTRA_PATH = "path";
    /** In the result, for the chat: the paths (as the agent sees them) to add to the next message. */
    static final String EXTRA_MENTION = "mention";
    private static final int REQ_UPLOAD = 31;
    /** Rows drawn at once: a folder can hold thousands (node_modules), "Show more" draws the next ones. */
    private static final int PAGE = 200;
    /** How long a folder's measured size is shown without measuring again. */
    private static final long FRESH = 60_000;

    // Shared by every Files screen: reading folders, measuring them, and the one delete or save under way.
    private static final ExecutorService lister = Executors.newSingleThreadExecutor();
    private static final ExecutorService sizer = Executors.newSingleThreadExecutor();
    private static final ExecutorService worker = Executors.newSingleThreadExecutor();
    /** Folder sizes measured so far: path → bytes, when. */
    private static final Map<String, long[]> sizes = Collections.synchronizedMap(
            new LinkedHashMap<String, long[]>(256, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, long[]> eldest) {
                    return size() > 4000;
                }
            });
    /** Goes up when another folder is shown: sizes asked for before that aren't measured any more. */
    private static volatile int sizeRound;
    private static volatile FilePlaces.Tally measuring;

    /** A delete or a save to Downloads under way: it goes on when the screen is left. */
    private static final class Op {
        final boolean save;
        /** Deleting: items removed. Saving: bytes written. stop: Cancel. */
        final FilePlaces.Tally t = new FilePlaces.Tally();
        /** Saving: the file being copied now. */
        volatile String name = "";

        Op(boolean save) {
            this.save = save;
        }
    }

    private interface Work {
        /** Does it, off the main thread; returns what to tell the user at the end, or null. */
        String run(Op op);
    }

    private static volatile Op op;
    /** How the last one ended, until a Files screen says it (main thread). */
    private static String opSaid;
    /** The Files screen in front (main thread). */
    private static Runnable opWatcher;

    private String chat;
    private File work, chatDir, incoming, attachments;
    /** The chat's project folder (see Sessions.Session.folder), or null: it works in the workspace itself. */
    private File project;
    /** The folder shown; chatDir is "This chat" (its two folders), null the top (the places). */
    private File here;
    private boolean hidden;
    /** Goes up with each reading of a folder: a slow one doesn't overwrite the folder moved to since. */
    private int round;
    private File listed;
    private List<FilePlaces.Entry> items;
    private int hiddenCount, shown = PAGE;
    private String listError;
    /** Multi-select (a long press starts it): what is ticked. */
    private final Map<File, FilePlaces.Entry> picked = new LinkedHashMap<>();
    private boolean selecting, resumed;
    private int jobsHere;

    private TextView titleView;
    private ImageButton back, menu;
    private HorizontalScrollView crumbScroll;
    private LinearLayout crumbs, opRow, jobsBox, list, actions, selBar;
    private TextView opText;
    private ScrollView scroll;
    private View shareAction, saveAction;
    private final Runnable jobsChanged = this::paintJobs;
    private final Runnable opChanged = this::paintOp;
    private Object backCallback;
    private boolean backOn;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        String id = getIntent().getStringExtra(EXTRA_CHAT);
        chat = FilePlaces.validChat(id) && ChatHub.get(this).sessions.find(id) != null ? id : null;
        try {
            if (!Linux.installed(this)) throw new IOException();
            work = FilePlaces.work(this);
            if (chat != null) {
                chatDir = FilePlaces.chat(this, chat);
                incoming = FilePlaces.incoming(this, chat);
                attachments = FilePlaces.attachments(this, chat);
            }
        } catch (IOException e) {
            finish();
            return;
        }
        hidden = Prefs.filesHidden(this);
        build();
        String at = state != null ? state.getString("here") : getIntent().getStringExtra(EXTRA_PATH);
        // from a chat too it opens at the top: the workspace the agent works in, and this chat's two folders under it
        here = state == null && at == null ? null : place(at);
        if (here != null && here.equals(chatDir)) here = null;
        final Context app = getApplicationContext();
        lister.execute(() -> LinuxFiles.sweep(app));
    }

    /** The folder for a path as the agent sees it, if it is one of the places or inside one; else the top. */
    private File place(String at) {
        if (at == null || at.isEmpty()) return null;
        try {
            File f = FilePlaces.fromLinux(this, at);
            if (f.isFile() && f.getParentFile() != null) f = f.getParentFile(); // (a link to a file: its folder)
            if (f.equals(chatDir)) return chatDir;
            return FilePlaces.folder(this, chat, f);
        } catch (IOException e) {
            return null;
        }
    }

    @Override
    protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        out.putString("here", here == null ? "" : FilePlaces.linuxPath(this, here));
    }

    private void build() {
        LinearLayout root = column(this);
        root.setBackgroundColor(BG);
        LinearLayout top = row(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.setPaddingRelative(dp(this, 4), dp(this, 4), dp(this, 4), dp(this, 4));
        back = iconButton(this, R.drawable.ms_arrow_back, getString(R.string.perms_back));
        back.setLayoutParams(new LinearLayout.LayoutParams(dp(this, 44), dp(this, 44)));
        back.setOnClickListener(v -> {
            if (!goBack()) finish();
        });
        top.addView(back);
        titleView = single(title(this, "", 18));
        titleView.setPaddingRelative(dp(this, 6), 0, dp(this, 6), 0);
        top.addView(titleView, weight1());
        menu = iconButton(this, R.drawable.ms_more_vert, getString(R.string.files_menu));
        menu.setLayoutParams(new LinearLayout.LayoutParams(dp(this, 44), dp(this, 44)));
        menu.setOnClickListener(v -> menuSheet());
        top.addView(menu);
        root.addView(top, full());

        // where this is, as the agent sees it: a tap on a part goes up to it
        crumbScroll = new HorizontalScrollView(this);
        crumbScroll.setHorizontalScrollBarEnabled(false);
        crumbs = row(this);
        crumbs.setPaddingRelative(dp(this, 16), dp(this, 2), dp(this, 16), dp(this, 8));
        crumbScroll.addView(crumbs);
        root.addView(crumbScroll, full());

        // a delete or a save under way
        opRow = row(this);
        opRow.setGravity(Gravity.CENTER_VERTICAL);
        opRow.setPaddingRelative(dp(this, 12), dp(this, 6), dp(this, 4), dp(this, 6));
        opRow.setBackground(rounded(this, CARD, 14));
        opRow.addView(spinner(this, 18));
        opText = single(text(this, "", 13, TEXT));
        opText.setPaddingRelative(dp(this, 10), 0, 0, 0);
        opRow.addView(opText, weight1());
        opRow.addView(cancelText(v -> {
            Op o = op;
            if (o != null) o.t.stop = true;
        }));
        opRow.setVisibility(View.GONE);
        root.addView(opRow, boxParams());

        // the files on their way into this folder
        jobsBox = column(this);
        root.addView(jobsBox, full());

        final ScrollView sv = new ScrollView(this);
        scroll = sv;
        list = column(this);
        list.setPaddingRelative(dp(this, 8), 0, dp(this, 8), dp(this, 24));
        sv.addView(list);
        root.addView(sv, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        actions = row(this);
        actions.setPaddingRelative(dp(this, 16), dp(this, 8), dp(this, 16), dp(this, 12));
        Button up = button(this, getString(R.string.files_upload), true);
        up.setOnClickListener(v -> upload());
        actions.addView(up, new LinearLayout.LayoutParams(0, dp(this, 48), 1));
        Button nf = button(this, getString(R.string.files_new_folder), false);
        nf.setOnClickListener(v -> newFolder());
        LinearLayout.LayoutParams np = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(this, 48));
        np.setMarginStart(dp(this, 10));
        actions.addView(nf, np);
        root.addView(actions, full());

        selBar = row(this);
        selBar.setBackgroundColor(CARD);
        selBar.setPaddingRelative(dp(this, 8), dp(this, 6), dp(this, 8), dp(this, 8));
        selBar.addView(barAction(R.drawable.ms_delete, getString(R.string.files_delete), ERR,
                v -> deleteSheet(new ArrayList<>(picked.values()))), new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        shareAction = barAction(R.drawable.ms_share, getString(R.string.files_share), TEXT, v -> {
            if (onlyFiles()) share(new ArrayList<>(picked.values()));
        });
        selBar.addView(shareAction, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        saveAction = barAction(R.drawable.ms_download, getString(R.string.files_save), TEXT, v -> {
            if (onlyFiles()) save(new ArrayList<>(picked.values()));
        });
        selBar.addView(saveAction, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        selBar.setVisibility(View.GONE);
        root.addView(selBar, full());

        FrameLayout frame = new FrameLayout(this);
        frame.setBackgroundColor(BG);
        frame.setFitsSystemWindows(true);
        frame.addView(root);
        setContentView(protect(frame)); // only the user uploads, shares, moves or deletes files: agents can't tap here
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (work == null) return; // (closing: there's no Linux to show)
        resumed = true;
        syncProject();
        LinuxFiles.watch(jobsChanged);
        opWatcher = opChanged;
        jobsHere = here == null ? 0 : LinuxFiles.into(here).size();
        chrome();
        load(); // (an agent may have changed the folder meanwhile)
        paintJobs();
        paintOp();
    }

    @Override
    protected void onPause() {
        super.onPause();
        resumed = false;
        LinuxFiles.unwatch(jobsChanged);
        if (opWatcher == opChanged) opWatcher = null;
        if (opRow != null) opRow.removeCallbacks(opChanged);
    }

    private Sessions.Session session() {
        return chat == null ? null : ChatHub.get(this).sessions.find(chat);
    }

    /** Where the chat works now (chat options or this screen may have changed it). */
    private void syncProject() {
        Sessions.Session s = session();
        project = null;
        try {
            if (s != null && !s.folder.isEmpty()) project = FilePlaces.project(this, s.folder);
        } catch (IOException ignored) {
        }
    }

    /**
     * This folder becomes the chat's project (null: the workspace itself again). A chat that has messages is asked
     * first: its agent starts anew there, with the chat so far as text.
     */
    private void setProject(final File dir) {
        final Sessions.Session s = session();
        if (s == null) return;
        final String rel = dir == null ? "" : FilePlaces.projectOf(this, dir);
        if (rel == null) return;
        final Runnable set = () -> {
            String err = ChatHub.get(this).setFolder(s, rel);
            toast(err != null ? err : rel.isEmpty() ? getString(R.string.files_project_none_set) : getString(R.string.files_project_set, dir.getName()));
            syncProject();
            render();
        };
        if (s.messages.isEmpty()) set.run();
        else confirmSheet(this, rel.isEmpty() ? getString(R.string.files_project_none_title) : getString(R.string.files_project_title, dir.getName()),
                getString(R.string.files_project_text), getString(R.string.files_project_yes), false, set);
    }

    // ------------------------------------------------------------------ moving around

    /** A real folder (uploads, New folder), not the top or "This chat", which only list places. */
    private boolean inFolder() {
        return here != null && !here.equals(chatDir);
    }

    private boolean underAttachments() {
        for (File f = here; f != null && attachments != null; f = f.getParentFile()) if (f.equals(attachments)) return true;
        return false;
    }

    /** One level up from f; null is the top. */
    private File up(File f) {
        if (f.equals(work) || f.equals(chatDir)) return null;
        if (f.equals(incoming) || f.equals(attachments)) return null; // (this chat's folders are listed at the top)
        return f.getParentFile();
    }

    private void go(File to) {
        here = to;
        picked.clear();
        selecting = false;
        shown = PAGE;
        jobsHere = to == null ? 0 : LinuxFiles.into(to).size();
        scroll.scrollTo(0, 0); // (another folder starts at its top)
        chrome();
        load();
        paintJobs();
    }

    /** Back: out of the selection, else up one level. False at the top: the screen is left. */
    private boolean goBack() {
        if (selecting) {
            picked.clear();
            selecting = false;
            chrome();
            render();
            return true;
        }
        if (here == null) return false;
        go(up(here));
        return true;
    }

    @Override
    public void onBackPressed() { // Android 11 and 12
        if (!goBack()) super.onBackPressed();
    }

    /** On Android 13+ Back comes through a callback, wanted only while there is a level to go up to. */
    private void syncBack() {
        if (android.os.Build.VERSION.SDK_INT < 33) return;
        boolean want = selecting || here != null;
        if (want == backOn) return;
        backOn = want;
        if (backCallback == null) backCallback = (android.window.OnBackInvokedCallback) this::goBack;
        android.window.OnBackInvokedCallback cb = (android.window.OnBackInvokedCallback) backCallback;
        if (want) getOnBackInvokedDispatcher().registerOnBackInvokedCallback(android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, cb);
        else getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(cb);
    }

    private String nameOf(File f) {
        if (f == null) return getString(R.string.files_title);
        if (f.equals(work)) return getString(R.string.files_projects);
        if (f.equals(chatDir)) return getString(R.string.files_this_chat);
        if (f.equals(incoming)) return getString(R.string.files_incoming);
        if (f.equals(attachments)) return getString(R.string.files_attachments);
        return f.getName();
    }

    /** The title, the path bar and the bars at the bottom, for where the screen is. */
    private void chrome() {
        syncBack();
        titleView.setText(selecting ? getResources().getQuantityString(R.plurals.files_selected, picked.size(), picked.size())
                : nameOf(here));
        back.setImageResource(selecting ? R.drawable.ms_close : R.drawable.ms_arrow_back);
        back.setContentDescription(getString(selecting ? android.R.string.cancel : R.string.perms_back));
        menu.setVisibility(selecting ? View.INVISIBLE : View.VISIBLE);
        // (the chat's Attachments are copies of what its messages carried: a file put there would reach no message)
        actions.setVisibility(inFolder() && !selecting && !underAttachments() ? View.VISIBLE : View.GONE);
        selBar.setVisibility(selecting ? View.VISIBLE : View.GONE);
        float dim = onlyFiles() ? 1f : 0.4f; // folders and links can't be shared or saved
        shareAction.setAlpha(dim);
        saveAction.setAlpha(dim);

        crumbs.removeAllViews();
        List<File> chain = new ArrayList<>();
        for (File f = here; f != null; f = up(f)) chain.add(0, f);
        crumbs.addView(crumb(getString(R.string.files_title), here == null, v -> go(null)));
        for (final File f : chain) {
            crumbs.addView(text(this, "›", 13, MUTED));
            // a place by its whole path inside Linux, the folders under it by their names: together, the path
            String label = f.equals(work) || f.equals(chatDir) ? FilePlaces.linuxPath(this, f) : f.getName();
            crumbs.addView(crumb(label, f.equals(here), v -> go(f)));
        }
        crumbScroll.post(() -> crumbScroll.fullScroll(View.FOCUS_RIGHT));
    }

    private TextView crumb(String label, boolean last, View.OnClickListener l) {
        TextView t = single(monoText(this, label, 13, last ? TEXT : ACCENT_TEXT));
        t.setPaddingRelative(dp(this, 6), dp(this, 8), dp(this, 6), dp(this, 8));
        if (!last) {
            t.setBackgroundResource(ripple(this, false));
            t.setOnClickListener(l);
        }
        return t;
    }

    // ------------------------------------------------------------------ the list

    /** Reads the folder shown, off the main thread, and draws it. */
    private void load() {
        final int r = ++round;
        final File dir = here;
        sizeRound++; // (what was being measured for the rows before isn't wanted any more)
        FilePlaces.Tally m = measuring;
        if (m != null) m.stop = true;
        if (!inFolder()) {
            items = null;
            listed = dir;
            render();
            return;
        }
        if (!dir.equals(listed)) { // a wait is shown for another folder; the same one keeps its rows until the new ones come
            items = null;
            listed = dir;
            render();
        }
        final boolean all = hidden;
        final Context app = getApplicationContext();
        final String c = chat;
        lister.execute(() -> {
            final List<FilePlaces.Entry> l = new ArrayList<>();
            int hid = 0;
            String err = null;
            try {
                File real = FilePlaces.folder(app, c, dir);
                String[] names = real.list();
                if (names == null && FilePlaces.stat(real) != null) err = whyUnreadable(real);
                if (names != null) {
                    for (String n : names) {
                        FilePlaces.Entry e = FilePlaces.stat(new File(real, n));
                        if (e == null || LinuxFiles.isPart(e.file)) continue; // (a copy being written shows as its own row)
                        if (e.hidden() && !all) hid++;
                        else l.add(e);
                    }
                }
                Collections.sort(l, (a, b) -> a.dir != b.dir ? (a.dir ? -1 : 1) : a.name.compareToIgnoreCase(b.name));
            } catch (Exception e) {
                err = FilePlaces.why(e);
            }
            final int hiddenN = hid;
            final String error = err;
            runOnUiThread(() -> {
                if (r != round || isDestroyed()) return;
                items = l;
                hiddenCount = hiddenN;
                listError = error;
                if (!picked.isEmpty()) picked.keySet().retainAll(new java.util.HashSet<>(files(l))); // (what's gone can't stay ticked)
                if (selecting && picked.isEmpty()) selecting = false;
                chrome();
                render();
            });
        });
    }

    private static List<File> files(List<FilePlaces.Entry> l) {
        List<File> f = new ArrayList<>();
        for (FilePlaces.Entry e : l) f.add(e.file);
        return f;
    }

    /** Why a folder that is there can't be listed, in the system's words. */
    private static String whyUnreadable(File dir) {
        try {
            Os.close(Os.open(dir.getPath(), OsConstants.O_RDONLY | OsConstants.O_CLOEXEC, 0));
            return Os.strerror(OsConstants.EIO);
        } catch (ErrnoException e) {
            return Os.strerror(e.errno);
        }
    }

    private void render() {
        list.removeAllViews();
        if (here == null) {
            TextView intro = text(this, getString(R.string.files_intro), 13, MUTED);
            intro.setPaddingRelative(dp(this, 12), dp(this, 4), dp(this, 12), dp(this, 10));
            list.addView(intro, full());
            if (chatDir == null) {
                list.addView(placeRow(work, getString(R.string.files_projects_info), true), full());
                return;
            }
            // from a chat: where it works first (its project, or the workspace it shares), then its own two folders,
            // right here with no level of their own in between
            TextView label = sectionLabel(this, getString(R.string.files_this_chat));
            label.setPaddingRelative(dp(this, 12), dp(this, 8), dp(this, 12), dp(this, 6));
            list.addView(label, full());
            if (project != null) list.addView(placeRow(project, getString(R.string.files_project_info), true), full());
            else list.addView(placeRow(work, getString(R.string.files_projects_shared_info), true), full());
            list.addView(placeRow(incoming, getString(R.string.files_incoming_info), true), full());
            list.addView(placeRow(attachments, getString(R.string.files_attachments_info), true), full());
            if (project != null) {
                TextView all = sectionLabel(this, getString(R.string.files_all_chats));
                all.setPaddingRelative(dp(this, 12), dp(this, 18), dp(this, 12), dp(this, 6));
                list.addView(all, full());
                list.addView(placeRow(work, getString(R.string.files_projects_info), true), full());
            }
            return;
        }
        if (here.equals(chatDir)) {
            list.addView(placeRow(incoming, getString(R.string.files_incoming_info), true), full());
            list.addView(placeRow(attachments, getString(R.string.files_attachments_info), true), full());
            return;
        }
        if (items == null) {
            ProgressBar p = spinner(this, 22);
            LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(dp(this, 22), dp(this, 22));
            pp.gravity = Gravity.CENTER_HORIZONTAL;
            pp.topMargin = dp(this, 28);
            list.addView(p, pp);
            return;
        }
        if (underAttachments()) list.addView(note(getString(R.string.files_attachments_hint)), full()); // what this folder is
        if (here.equals(project)) list.addView(note(getString(R.string.files_project_here)), full());
        else if (chat != null && project == null && here.equals(work)) list.addView(note(getString(R.string.files_project_hint)), full());
        if (listError != null) list.addView(note(getString(R.string.files_list_failed, listError)), full());
        else if (items.isEmpty() && jobsHere == 0) list.addView(note(getString(R.string.files_empty)), full());
        int n = Math.min(shown, items.size());
        for (int i = 0; i < n; i++) list.addView(entryRow(items.get(i)), full());
        if (n < items.size()) {
            TextView more = text(this, getString(R.string.files_show_more, items.size() - n), 14, ACCENT_TEXT);
            more.setTypeface(sans(this, 600));
            more.setGravity(Gravity.CENTER);
            more.setPaddingRelative(dp(this, 12), dp(this, 14), dp(this, 12), dp(this, 14));
            more.setBackgroundResource(ripple(this, false));
            more.setOnClickListener(v -> {
                shown += PAGE;
                render();
            });
            list.addView(more, full());
        }
        if (hiddenCount > 0) {
            list.addView(note(getResources().getQuantityString(R.plurals.files_hidden_count, hiddenCount, hiddenCount)), full());
        }
    }

    private TextView note(String s) {
        TextView t = text(this, s, 13, MUTED);
        t.setPaddingRelative(dp(this, 12), dp(this, 14), dp(this, 12), dp(this, 6));
        return t;
    }

    /** One of the places: its name, what it is for, and how much it holds once that is measured. */
    private View placeRow(File dir, String about, boolean measure) {
        LinearLayout r = row(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPaddingRelative(dp(this, 8), dp(this, 10), dp(this, 4), dp(this, 10));
        r.setBackgroundResource(ripple(this, false));
        r.addView(tile(R.drawable.ms_folder, ACCENT_TEXT, ACCENT_SOFT), new LinearLayout.LayoutParams(dp(this, 40), dp(this, 40)));
        LinearLayout t = column(this);
        t.setPaddingRelative(dp(this, 12), 0, dp(this, 8), 0);
        t.addView(single(title(this, nameOf(dir), 15)));
        t.addView(text(this, about, 13, MUTED));
        r.addView(t, weight1());
        if (measure) {
            TextView size = single(text(this, "", 13, MUTED));
            size.setPaddingRelative(0, 0, dp(this, 6), 0);
            r.addView(size);
            wantSize(dir, bytes -> size.setText(Formatter.formatShortFileSize(this, bytes)));
        }
        r.addView(icon(this, R.drawable.ms_chevron_right, MUTED, 22));
        r.setOnClickListener(v -> go(dir));
        return r;
    }

    private View entryRow(final FilePlaces.Entry e) {
        final boolean on = picked.containsKey(e.file);
        LinearLayout r = row(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPaddingRelative(dp(this, 8), dp(this, 7), selecting ? dp(this, 8) : 0, dp(this, 7));
        if (on) r.setBackground(rounded(this, ACCENT_SOFT, 12));
        else r.setBackgroundResource(ripple(this, false));
        r.addView(on ? tile(R.drawable.ms_check, ON_ACCENT, ACCENT)
                        : e.dir ? tile(R.drawable.ms_folder, ACCENT_TEXT, ACCENT_SOFT) : tile(iconFor(e), TEXT2, CARD2),
                new LinearLayout.LayoutParams(dp(this, 40), dp(this, 40)));
        LinearLayout t = column(this);
        t.setPaddingRelative(dp(this, 12), 0, dp(this, 4), 0);
        TextView name = single(text(this, e.name, 15, TEXT));
        name.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE); // (the end of a long name says what it is)
        t.addView(name);
        final TextView sub = single(text(this, about(e, -1), 13, MUTED));
        t.addView(sub);
        r.addView(t, weight1());
        if (e.dir) wantSize(e.file, bytes -> sub.setText(about(e, bytes)));
        if (!selecting) {
            ImageButton more = iconButton(this, R.drawable.ms_more_vert, getString(R.string.files_menu), MUTED);
            more.setOnClickListener(v -> entrySheet(e));
            r.addView(more, new LinearLayout.LayoutParams(dp(this, 40), dp(this, 40)));
        }
        r.setOnClickListener(v -> {
            if (selecting) pick(e);
            else if (e.dir) go(e.file);
            else entrySheet(e);
        });
        r.setOnLongClickListener(v -> {
            if (!selecting) {
                selecting = true;
                pick(e);
            }
            return true;
        });
        return r;
    }

    private View tile(int icon, int color, int fill) {
        FrameLayout f = new FrameLayout(this);
        f.setBackground(rounded(this, fill, 12));
        f.addView(icon(this, icon, color, 22), new FrameLayout.LayoutParams(dp(this, 22), dp(this, 22), Gravity.CENTER));
        return f;
    }

    private static int iconFor(FilePlaces.Entry e) {
        if (e.link) return R.drawable.ms_link;
        String n = e.name.toLowerCase(java.util.Locale.ROOT);
        if (FilesProvider.type(n).startsWith("image/")) return R.drawable.ms_image;
        if (n.matches(".*\\.(zip|tar|gz|tgz|xz|bz2|zst|7z|rar)")) return R.drawable.ms_archive;
        return R.drawable.ms_description;
    }

    /** The line under a name: its size (a folder's once measured: bytes, or -1) and when it last changed; a link, where it points. */
    private String about(FilePlaces.Entry e, long bytes) {
        if (e.link) return getString(R.string.files_link, e.linkTo == null ? "?" : e.linkTo);
        String when = DateUtils.formatDateTime(this, e.time, DateUtils.FORMAT_SHOW_DATE | DateUtils.FORMAT_SHOW_TIME
                | DateUtils.FORMAT_ABBREV_ALL);
        long size = e.dir ? bytes : e.size;
        return size < 0 || (!e.dir && !e.regular) ? when : Formatter.formatShortFileSize(this, size) + " · " + when;
    }

    private interface Size {
        void is(long bytes);
    }

    /**
     * A folder's size, when it is known: at once if it was measured, and measured (again, when that was a while ago)
     * off the main thread, one folder at a time. Links in it aren't followed; hidden files count.
     */
    private void wantSize(final File dir, final Size then) {
        final String key = dir.getPath();
        long[] have = sizes.get(key);
        if (have != null) then.is(have[0]);
        if (have != null && System.currentTimeMillis() - have[1] < FRESH) return;
        final int r = sizeRound;
        sizer.execute(() -> {
            if (r != sizeRound) return;
            // (asked again by a redraw while it waited its turn: measured once)
            final long[] now = sizes.get(key);
            if (now != null && System.currentTimeMillis() - now[1] < FRESH) {
                runOnUiThread(() -> {
                    if (r == sizeRound && !isDestroyed()) then.is(now[0]);
                });
                return;
            }
            FilePlaces.Tally t = new FilePlaces.Tally();
            measuring = t;
            FilePlaces.tally(dir, t);
            measuring = null;
            if (t.stop) return;
            sizes.put(key, new long[]{t.bytes, System.currentTimeMillis()});
            runOnUiThread(() -> {
                if (r == sizeRound && !isDestroyed()) then.is(t.bytes);
            });
        });
    }

    // ------------------------------------------------------------------ selection

    private void pick(FilePlaces.Entry e) {
        if (picked.remove(e.file) == null) picked.put(e.file, e);
        if (picked.isEmpty()) selecting = false;
        chrome();
        render();
    }

    /** Whether everything ticked is a regular file: only those can be shared or saved. */
    private boolean onlyFiles() {
        for (FilePlaces.Entry e : picked.values()) if (!e.regular) return false;
        return true;
    }

    private View barAction(int icon, String label, int color, View.OnClickListener l) {
        LinearLayout c = column(this);
        c.setGravity(Gravity.CENTER_HORIZONTAL);
        c.setPaddingRelative(dp(this, 4), dp(this, 8), dp(this, 4), dp(this, 6));
        c.setBackgroundResource(ripple(this, true));
        ImageView i = icon(this, icon, color, 22);
        c.addView(i);
        TextView t = single(text(this, label, 12, color));
        t.setPaddingRelative(0, dp(this, 3), 0, 0);
        c.addView(t);
        c.setOnClickListener(l);
        return c;
    }

    private void endSelection() {
        picked.clear();
        selecting = false;
        chrome();
        render();
    }

    // ------------------------------------------------------------------ sheets

    /** A sheet of this screen: off-limits to agents like the screen itself (a sheet is a window of its own). */
    private Dialog sheet(View content) {
        return bottomSheet(this, protect(content));
    }

    private View action(int icon, String label, int color, Runnable onTap) {
        LinearLayout r = row(this);
        r.setGravity(Gravity.CENTER_VERTICAL);
        r.setPaddingRelative(dp(this, 4), dp(this, 13), dp(this, 4), dp(this, 13));
        r.setBackgroundResource(ripple(this, false));
        r.addView(icon(this, icon, color == TEXT ? TEXT2 : color, 22));
        TextView t = text(this, label, 16, color);
        t.setPaddingRelative(dp(this, 16), 0, 0, 0);
        r.addView(t, weight1());
        r.setOnClickListener(v -> onTap.run());
        return r;
    }

    /** The screen's menu: hidden files, and for a folder a new folder in it and its path. */
    private void menuSheet() {
        LinearLayout c = column(this);
        final Dialog[] d = new Dialog[1];
        LinearLayout sw = switchRow(this, getString(R.string.files_show_hidden), getString(R.string.files_show_hidden_info),
                hidden, on -> {
                    hidden = on;
                    Prefs.setFilesHidden(this, on);
                    load();
                });
        sw.setPaddingRelative(dp(this, 4), dp(this, 12), dp(this, 4), dp(this, 12));
        c.addView(sw, full());
        if (inFolder()) {
            c.addView(action(R.drawable.ms_create_new_folder, getString(R.string.files_new_folder), TEXT, () -> {
                d[0].dismiss();
                newFolder();
            }), full());
            c.addView(action(R.drawable.ms_content_copy, getString(R.string.files_copy_path), TEXT, () -> {
                d[0].dismiss();
                copyPath(here);
            }), full());
            projectAction(c, here, d);
        }
        d[0] = sheet(c);
        d[0].show();
    }

    /** What can be done with one file, folder or link. */
    private void entrySheet(final FilePlaces.Entry e) {
        LinearLayout c = column(this);
        TextView h = single(title(this, e.name, 18));
        h.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
        c.addView(h);
        long[] size = e.dir ? sizes.get(e.file.getPath()) : null;
        TextView sub = text(this, about(e, size == null ? -1 : size[0]), 13, MUTED);
        sub.setPaddingRelative(0, dp(this, 2), 0, dp(this, 8));
        c.addView(sub);
        if (e.link) { // where it points may be outside Buddy's Linux: it is never opened
            TextView why = text(this, getString(R.string.files_link_info), 13, MUTED);
            why.setPaddingRelative(0, 0, 0, dp(this, 8));
            c.addView(why);
        }
        final Dialog[] d = new Dialog[1];
        final List<FilePlaces.Entry> one = Collections.singletonList(e);
        if (e.regular) {
            c.addView(action(R.drawable.ms_open_in_new, getString(R.string.files_open), TEXT, () -> {
                d[0].dismiss();
                open(e);
            }), full());
            c.addView(action(R.drawable.ms_share, getString(R.string.files_share), TEXT, () -> {
                d[0].dismiss();
                share(one);
            }), full());
            c.addView(action(R.drawable.ms_download, getString(R.string.files_save), TEXT, () -> {
                d[0].dismiss();
                save(one);
            }), full());
            if (chat != null) c.addView(action(R.drawable.ms_alternate_email, getString(R.string.files_mention), TEXT, () -> {
                d[0].dismiss();
                mention(e);
            }), full());
        }
        if (e.dir) projectAction(c, e.file, d);
        c.addView(action(R.drawable.ms_edit, getString(R.string.files_rename), TEXT, () -> {
            d[0].dismiss();
            renameSheet(e);
        }), full());
        c.addView(action(R.drawable.ms_content_copy, getString(R.string.files_copy_path), TEXT, () -> {
            d[0].dismiss();
            copyPath(e.file);
        }), full());
        c.addView(action(R.drawable.ms_delete, getString(R.string.files_delete), ERR, () -> {
            d[0].dismiss();
            deleteSheet(one);
        }), full());
        d[0] = sheet(c);
        d[0].show();
    }

    /** For a folder of the workspace, opened from a chat: make it the chat's project, or end that. */
    private void projectAction(LinearLayout c, final File dir, final Dialog[] d) {
        if (chat == null || FilePlaces.projectOf(this, dir) == null) return;
        final boolean is = dir.equals(project);
        c.addView(action(is ? R.drawable.ms_close : R.drawable.ms_folder, getString(is ? R.string.files_project_end : R.string.files_project_make),
                TEXT, () -> {
                    d[0].dismiss();
                    setProject(is ? null : dir);
                }), full());
    }

    /** A sheet that asks for a name (a new folder's, or a new one for a file); then gets it when it is a usable one. */
    private void nameSheet(String heading, String name, boolean file, String action, final java.util.function.Consumer<String> then) {
        LinearLayout c = column(this);
        c.addView(title(this, heading, 18));
        final EditText field = new EditText(this);
        field.setText(name);
        field.setHint(R.string.files_name_hint);
        field.setHintTextColor(MUTED);
        field.setTextColor(TEXT);
        field.setTextSize(15);
        field.setSingleLine(true);
        field.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        field.setPaddingRelative(dp(this, 12), dp(this, 12), dp(this, 12), dp(this, 12));
        field.setBackground(outlined(this, BG, 12, OUTLINE, 1));
        LinearLayout.LayoutParams np = full();
        np.topMargin = dp(this, 12);
        c.addView(field, np);
        Button ok = button(this, action, true);
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(this, 52));
        sp.topMargin = dp(this, 14);
        c.addView(ok, sp);
        final Dialog d = sheet(c);
        ok.setOnClickListener(v -> {
            String n = field.getText().toString().trim();
            if (!FilePlaces.validName(n)) {
                toast(getString(R.string.files_bad_name));
                return;
            }
            d.dismiss();
            then.accept(n);
        });
        field.setOnEditorActionListener((v, id, ev) -> {
            ok.performClick();
            return true;
        });
        android.view.Window w = d.getWindow();
        if (w != null) w.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE
                | android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        d.show();
        field.requestFocus();
        // a file's name is picked up to its extension: that part is what's usually changed
        int dot = file ? name.lastIndexOf('.') : -1;
        field.setSelection(0, dot > 0 ? dot : name.length());
    }

    private void renameSheet(final FilePlaces.Entry e) {
        nameSheet(getString(R.string.files_rename), e.name, e.regular, getString(R.string.files_rename), n -> {
            if (n.equals(e.name)) return;
            try {
                File real = FilePlaces.entry(this, chat, e.file);
                if (LinuxFiles.under(real)) throw new IOException(getString(R.string.files_copying_into));
                File to = new File(real.getParentFile(), n);
                // (a rename would silently replace what has that name)
                if (FilePlaces.stat(to) != null) throw new IOException(Os.strerror(OsConstants.EEXIST));
                Os.rename(real.getPath(), to.getPath());
                String was = e.dir ? FilePlaces.projectOf(this, real) : null, now = e.dir ? FilePlaces.projectOf(this, to) : null;
                if (was != null && now != null) { // chats working in it (or in a folder of it) follow the new name
                    ChatHub.get(this).folderRenamed(was, now);
                    syncProject();
                }
            } catch (Exception x) {
                toast(getString(R.string.files_rename_failed, e.name, FilePlaces.why(x)));
            }
            changed();
        });
    }

    private void newFolder() {
        final File dir = here;
        if (!inFolder()) return;
        nameSheet(getString(R.string.files_new_folder), "", false, getString(R.string.files_create), n -> {
            try {
                File real = FilePlaces.folder(this, chat, dir);
                real.mkdirs(); // (a place that has nothing yet isn't there until its first file)
                Os.mkdir(new File(real, n).getPath(), 0700);
            } catch (Exception x) {
                toast(getString(R.string.files_folder_failed, n, FilePlaces.why(x)));
            }
            changed();
        });
    }

    /**
     * Asks before deleting, with what goes: how many files and folders and how much, counted while the sheet is up
     * (Delete waits for the count).
     */
    private void deleteSheet(final List<FilePlaces.Entry> what) {
        if (what.isEmpty()) return;
        LinearLayout c = column(this);
        TextView h = title(this, what.size() == 1 ? getString(R.string.files_delete_title, what.get(0).name)
                : getResources().getQuantityString(R.plurals.files_delete_many, what.size(), what.size()), 18);
        c.addView(h);
        final TextView m = text(this, getString(R.string.files_delete_counting), 14, MUTED);
        m.setPaddingRelative(0, dp(this, 6), 0, dp(this, 18));
        c.addView(m);
        LinearLayout btns = row(this);
        Button no = button(this, getString(android.R.string.cancel), false);
        final Button yes = button(this, getString(R.string.files_delete), false);
        yes.setTextColor(ERR);
        yes.setBackground(rounded(this, ERR_SOFT, 12));
        yes.setEnabled(false);
        yes.setAlpha(0.5f);
        btns.addView(no, new LinearLayout.LayoutParams(0, dp(this, 48), 1));
        LinearLayout.LayoutParams yp = new LinearLayout.LayoutParams(0, dp(this, 48), 1);
        yp.setMarginStart(dp(this, 10));
        btns.addView(yes, yp);
        c.addView(btns, full());
        final Dialog d = sheet(c);
        final FilePlaces.Tally t = new FilePlaces.Tally();
        d.setOnDismissListener(x -> t.stop = true);
        new Thread(() -> {
            for (FilePlaces.Entry e : what) FilePlaces.tally(e.file, t);
            runOnUiThread(() -> {
                if (t.stop) return;
                int n = (int) Math.min(Integer.MAX_VALUE, t.items);
                m.setText(getResources().getQuantityString(R.plurals.files_delete_info, n, n, Formatter.formatShortFileSize(this, t.bytes)));
                yes.setEnabled(true);
                yes.setAlpha(1f);
            });
        }, "files-count").start();
        no.setOnClickListener(v -> d.dismiss());
        yes.setOnClickListener(v -> {
            d.dismiss();
            delete(what);
        });
        d.show();
    }

    // ------------------------------------------------------------------ what the sheets do

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    /** Something here was changed: sizes are measured again, and the folder read again. */
    private void changed() {
        sizes.clear();
        load();
    }

    private void copyPath(File f) {
        getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText("Buddy", FilePlaces.linuxPath(this, f)));
        // (Android 13 and later say so themselves)
        if (android.os.Build.VERSION.SDK_INT < 33) Toast.makeText(this, R.string.chat_copied, Toast.LENGTH_SHORT).show();
    }

    /**
     * Opens the file in another app, by an address that app can only read (FilesProvider). An APK goes to Android's
     * installer this way, as from any file manager.
     */
    private void open(FilePlaces.Entry e) {
        Intent i = new Intent(Intent.ACTION_VIEW).setDataAndType(FilesProvider.linuxUri(e.file, chat), FilesProvider.type(e.name))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivity(i);
        } catch (android.content.ActivityNotFoundException x) {
            Toast.makeText(this, R.string.chat_no_app_for_action, Toast.LENGTH_LONG).show();
        }
    }

    /** The share sheet with the files: read in place by the app the user picks, whatever their size. */
    private void share(List<FilePlaces.Entry> what) {
        ArrayList<Uri> uris = new ArrayList<>();
        String type = null;
        for (FilePlaces.Entry e : what) {
            if (!e.regular) continue;
            uris.add(FilesProvider.linuxUri(e.file, chat));
            String t = FilesProvider.type(e.name);
            type = type == null || type.equals(t) ? t : "*/*";
        }
        if (uris.isEmpty()) return;
        Intent send = new Intent(uris.size() == 1 ? Intent.ACTION_SEND : Intent.ACTION_SEND_MULTIPLE).setType(type)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        if (uris.size() == 1) send.putExtra(Intent.EXTRA_STREAM, uris.get(0));
        else send.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
        // (the read grant goes with the addresses named here: all of them)
        ClipData clip = ClipData.newRawUri(what.get(0).name, uris.get(0));
        for (int i = 1; i < uris.size(); i++) clip.addItem(new ClipData.Item(uris.get(i)));
        send.setClipData(clip);
        startActivity(Intent.createChooser(send, null).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION));
        if (selecting) endSelection();
    }

    /** Back to the chat, with the file as a chip above its message box: its path goes to the agent with the next message. */
    private void mention(FilePlaces.Entry e) {
        ArrayList<String> paths = new ArrayList<>();
        paths.add(FilePlaces.linuxPath(this, e.file));
        if (getCallingActivity() != null) { // opened from the chat: it is right under this screen
            setResult(RESULT_OK, new Intent().putStringArrayListExtra(EXTRA_MENTION, paths));
        } else { // opened from an upload's notification
            startActivity(new Intent(this, ChatActivity.class).putExtra("id", chat).putStringArrayListExtra(EXTRA_MENTION, paths));
        }
        finish();
    }

    private void upload() {
        try {
            startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
                    .putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true), REQ_UPLOAD);
        } catch (android.content.ActivityNotFoundException e) {
            Toast.makeText(this, R.string.chat_no_app_for_action, Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int code, int result, Intent data) {
        super.onActivityResult(code, result, data);
        if (code != REQ_UPLOAD || result != RESULT_OK || data == null || !inFolder()) return;
        final List<Uri> uris = new ArrayList<>();
        if (data.getClipData() != null) {
            for (int i = 0; i < data.getClipData().getItemCount(); i++) uris.add(data.getClipData().getItemAt(i).getUri());
        } else if (data.getData() != null) {
            uris.add(data.getData());
        }
        if (uris.isEmpty()) return;
        final File dir = here;
        final Context app = getApplicationContext();
        final String c = chat;
        // (opened at once, off the main thread, while the picker's grant holds; the copy goes on when this screen is left)
        new Thread(() -> {
            String err = LinuxFiles.upload(app, c, dir, uris);
            if (err != null) runOnUiThread(() -> toast(err));
        }, "files-upload").start();
    }

    /** The rows of the files on their way into this folder, each with how far it is and Cancel. */
    private void paintJobs() {
        if (jobsBox == null) return;
        for (String s : LinuxFiles.takeSaid()) toast(s);
        List<LinuxFiles.Job> jobs = inFolder() ? LinuxFiles.into(here) : new ArrayList<LinuxFiles.Job>();
        jobsBox.removeAllViews();
        for (final LinuxFiles.Job j : jobs) {
            LinearLayout r = row(this);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.setPaddingRelative(dp(this, 12), dp(this, 6), dp(this, 4), dp(this, 6));
            r.setBackground(rounded(this, CARD, 14));
            LinearLayout t = column(this);
            t.addView(single(text(this, getString(R.string.linux_file_sending, j.name) + " · " + LinuxFiles.progress(this, j), 13, TEXT)));
            ProgressBar bar = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
            bar.setMax(1000);
            bar.setProgressTintList(ColorStateList.valueOf(ACCENT));
            bar.setProgressBackgroundTintList(ColorStateList.valueOf(CARD2));
            bar.setIndeterminateTintList(ColorStateList.valueOf(ACCENT));
            bar.setIndeterminate(j.size <= 0);
            if (j.size > 0) bar.setProgress((int) Math.min(1000, j.done * 1000 / j.size));
            LinearLayout.LayoutParams bp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(this, 4));
            bp.topMargin = dp(this, 6);
            t.addView(bar, bp);
            r.addView(t, weight1());
            r.addView(cancelText(v -> {
                j.cancelled = true;
                v.setEnabled(false);
            }));
            jobsBox.addView(r, boxParams());
        }
        if (jobs.size() < jobsHere) changed(); // one is done (or was stopped): the folder shows it
        jobsHere = jobs.size();
    }

    private TextView cancelText(View.OnClickListener l) {
        TextView t = text(this, getString(android.R.string.cancel), 14, ACCENT_TEXT);
        t.setTypeface(sans(this, 600));
        t.setPaddingRelative(dp(this, 12), dp(this, 8), dp(this, 12), dp(this, 8));
        t.setBackgroundResource(ripple(this, false));
        t.setOnClickListener(l);
        return t;
    }

    private LinearLayout.LayoutParams boxParams() {
        LinearLayout.LayoutParams p = full();
        p.leftMargin = p.rightMargin = dp(this, 12);
        p.bottomMargin = dp(this, 6);
        return p;
    }

    // ------------------------------------------------------------------ deleting and saving

    /** Starts a delete or a save, one at a time, off the main thread; its row shows how far it is. */
    private boolean begin(final Op o, final Work work) {
        if (op != null) {
            toast(getString(R.string.files_busy));
            return false;
        }
        op = o;
        if (selecting) endSelection();
        paintOp();
        worker.execute(() -> {
            String said = null;
            try {
                said = work.run(o);
            } catch (Exception e) {
                said = FilePlaces.why(e);
            }
            final String text = said;
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() -> {
                op = null;
                opSaid = text;
                sizes.clear();
                if (opWatcher != null) opWatcher.run();
            });
        });
        return true;
    }

    /** The row of the delete or save under way, twice a second while there is one; when it ends, how it went. */
    private void paintOp() {
        if (opRow == null) return;
        opRow.removeCallbacks(opChanged);
        Op o = op;
        boolean was = opRow.getVisibility() == View.VISIBLE;
        opRow.setVisibility(o == null ? View.GONE : View.VISIBLE);
        if (o == null) {
            if (opSaid != null) toast(opSaid);
            opSaid = null;
            if (was) load();
            return;
        }
        opText.setText(o.save ? getString(R.string.files_saving, o.name, Formatter.formatShortFileSize(this, o.t.bytes))
                : getString(R.string.files_deleting, o.t.items));
        if (resumed) opRow.postDelayed(opChanged, 500);
    }

    private void delete(List<FilePlaces.Entry> what) {
        final List<File> files = files(what);
        final Context app = getApplicationContext();
        final String c = chat;
        begin(new Op(false), o -> {
            String first = null;
            for (File f : files) {
                if (o.t.stop) break;
                try {
                    File real = FilePlaces.entry(app, c, f);
                    // a file on its way into the folder is stopped first, and its part is gone a moment later
                    LinuxFiles.cancelUnder(real);
                    for (int i = 0; i < 50 && LinuxFiles.writingUnder(real); i++) Thread.sleep(100);
                    FilePlaces.delete(real, o.t);
                } catch (Exception e) {
                    if (first == null) first = FilePlaces.why(e);
                }
            }
            return first == null ? null : app.getString(R.string.files_delete_failed, first);
        });
    }

    /** Copies the files to the phone's Downloads/Buddy, as the save_to_downloads tool does. */
    private void save(List<FilePlaces.Entry> what) {
        final List<File> files = files(what);
        final Context app = getApplicationContext();
        final String c = chat;
        begin(new Op(true), o -> {
            int n = 0;
            String last = null;
            for (File f : files) {
                if (o.t.stop) break;
                o.name = f.getName();
                try {
                    ParcelFileDescriptor pfd = FilePlaces.open(app, c, f);
                    Uri u = PhoneTools.toDownloads(app, new ParcelFileDescriptor.AutoCloseInputStream(pfd), f.getName(), o.t);
                    if (u == null) throw new IOException(Os.strerror(OsConstants.EIO));
                    n++;
                    last = f.getName();
                } catch (java.io.InterruptedIOException stopped) { // Cancel: what was written of this one is removed
                    break;
                } catch (Exception e) {
                    return app.getString(R.string.files_save_failed, f.getName(), FilePlaces.why(e));
                }
            }
            return n == 0 ? null : n == 1 ? app.getString(R.string.backup_made, last)
                    : app.getResources().getQuantityString(R.plurals.files_saved_many, n, n);
        });
    }
}
