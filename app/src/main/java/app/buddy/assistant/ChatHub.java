package app.buddy.assistant;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Chats shared by the app screens and the bubble. Runs on the main thread. */
final class ChatHub {
    interface Listener {
        void onChatsChanged();

        void onMessage(Sessions.Session ses, Sessions.Msg msg);
    }

    private static final long NO_RESPONSE_MS = 30000;
    /** After this long with nothing from the agent, the run is given up on. */
    private static final long GIVE_UP_MS = 180000;
    /** A model on the user's own computer can read for minutes before its first word (no GPU big enough). */
    private static final long LOCAL_GIVE_UP_MS = 15 * 60_000;
    private static final String TOOL_PREFIX = "mcp__phone__";
    private static ChatHub instance;

    final Sessions sessions;
    private final Context app;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final List<Listener> listeners = new ArrayList<>();
    private final List<Sessions.Session> viewing = new ArrayList<>();
    private String flash;
    /** The chat whose reply the flash shows. */
    private Sessions.Session flashChat;
    /** Receives buddy_procs / buddy_recent / buddy_transcript replies for the monitor screens. */
    interface MonitorListener {
        void onMonitor(JSONObject ev);
    }

    MonitorListener monitor;

    void onMonitorEvent(JSONObject ev) {
        if (monitor != null) monitor.onMonitor(ev);
    }

    /** Turn an existing Claude Code / Codex session into a Buddy chat that continues it. */
    Sessions.Session adopt(String backend, String agentSessionId, String title) {
        Sessions.Session s = sessions.create(backend);
        s.title = title == null ? "" : title;
        try {
            Helper.run(app, "adopt", s.id, backend, agentSessionId);
        } catch (Exception e) {
            add(s, Bubble.Kind.ERROR, e.getMessage());
        }
        add(s, Bubble.Kind.STEP, app.getString(R.string.hub_continuing, s.brainName()));
        changed();
        return s;
    }

    /** The line a scheduled run with nothing to report ends with (see Schedules.start): no notification then. */
    static final String QUIET = "[QUIET]";

    static synchronized ChatHub get(Context c) {
        if (instance == null) instance = new ChatHub(c.getApplicationContext());
        return instance;
    }

    private ChatHub(Context app) {
        this.app = app;
        sessions = new Sessions(app);
        // a chat still marked working when Buddy starts was cut off: Android closed Buddy (an update, low memory), and
        // its agent with it
        for (String id : Prefs.busyChats(app)) {
            Sessions.Session s = sessions.find(id);
            Prefs.setBusy(app, id, false);
            if (s == null) continue;
            s.interrupted = true;
            sessions.add(s, Bubble.Kind.STEP, app.getString(R.string.hub_interrupted), null);
        }
        // incognito chats don't outlive Buddy: they go, with what their agent kept of them
        List<String> forgetting = new ArrayList<>();
        for (Sessions.Session s : new ArrayList<>(sessions.all)) {
            if (!s.incognito) continue;
            forgetting.add(s.id); // its folder is the delete's to remove: it reads it first
            sessions.remove(s);
            StepLog.deleteChat(app, s.id);
            if (Linux.installed(app)) {
                try {
                    Helper.run(app, "delete", s.id, "forget");
                } catch (Exception ignored) {
                }
            }
        }
        pruneLinux(forgetting);
    }

    /**
     * Removes the folders in Buddy's Linux of chats Buddy no longer has (before 2.2.26 the 21st chat dropped the oldest,
     * but its folder stayed), once per version of Buddy. Only when every chat was read, so one that failed to read
     * keeps its agent's session; chats being forgotten (incognito) keep theirs for their own delete, which reads it.
     */
    private void pruneLinux(List<String> forgetting) {
        if (!sessions.complete || sessions.all.isEmpty() || !Linux.installed(app)) return;
        if (Prefs.prunedFor(app) == BuildInfo.VERSION_CODE) return;
        StringBuilder ids = new StringBuilder();
        List<String> keep = new ArrayList<>(forgetting);
        for (Sessions.Session s : sessions.all) keep.add(s.id);
        for (String id : keep) {
            if (!id.matches("[0-9a-f]{12}")) return; // not one Buddy made: better keep everything
            if (ids.length() > 0) ids.append(',');
            ids.append(id);
        }
        try {
            Helper.run(app, "prune", ids.toString());
            Prefs.setPrunedFor(app, BuildInfo.VERSION_CODE);
        } catch (Exception ignored) {
        }
    }

    /** A chat's name in the list; empty goes back to its first message's. */
    void rename(Sessions.Session ses, String title) {
        String t = title.trim().replace('\n', ' ');
        if (t.isEmpty()) { // as it was named: the first message the chat still has (it keeps its last 150)
            for (Sessions.Msg m : ses.messages) {
                if (m.kind != Bubble.Kind.USER) continue;
                t = Sessions.autoTitle(m.text, m.files);
                break;
            }
        }
        ses.title = t;
        sessions.save(ses);
        changed();
    }

    /** Pinned chats stay at the top of the list; pinning brings an archived chat back. */
    void setPinned(Sessions.Session ses, boolean on) {
        ses.pinned = on;
        if (on) ses.archived = false;
        sessions.save(ses);
        changed();
    }

    /** An archived chat leaves the list, the bubble and sharing, and keeps its schedules and history. */
    void setArchived(Sessions.Session ses, boolean on) {
        ses.archived = on;
        if (on) ses.pinned = false;
        if (on && sessions.current == ses) { // the bubble shows the current chat: one that isn't archived
            for (Sessions.Session s : sessions.all) {
                if (s.archived) continue;
                sessions.select(s);
                break;
            }
        }
        sessions.save(ses);
        changed();
    }

    /** After a cut-off run: the agent checks what's done and goes on. */
    String resume(Sessions.Session ses) {
        return send(ses, app.getString(R.string.hub_resume_message), null, "(From Buddy, not the user) Android closed Buddy "
                + "in the middle of your last task, and stopped you with it. Check what is already done (the screen, the "
                + "files, the conversation so far), then carry on from where you stopped. Don't redo steps that already "
                + "happened, such as sending a message again.", false, false);
    }

    void addListener(Listener l) {
        if (!listeners.contains(l)) listeners.add(l);
    }

    void removeListener(Listener l) {
        listeners.remove(l);
    }

    /** The chat whose own screen (ChatActivity) is in front, or null. */
    private Sessions.Session onScreen;

    void setOnScreen(Sessions.Session ses, boolean on) {
        if (on) onScreen = ses;
        else if (onScreen == ses) onScreen = null;
    }

    /**
     * This chat's own screen is in front with nothing over it (a sheet or dialog takes its window's focus, and the
     * card under it would not be seen): a card for it shows there, so the bubble's panel need not open over it.
     */
    boolean onScreen(Sessions.Session ses) {
        return ses != null && ses == onScreen;
    }

    void setViewing(Sessions.Session ses, boolean on) {
        if (on) {
            viewing.add(ses);
            ses.unread = false;
        } else {
            viewing.remove(ses);
        }
        changed();
    }

    Sessions.Session create(String backend) {
        return create(backend, true);
    }

    /** A new chat; select: make it the one the bubble shows (a rule's chat doesn't take over the bubble). */
    Sessions.Session create(String backend, boolean select) {
        Sessions.Session s = sessions.create(backend, select);
        changed();
        return s;
    }

    /** A new chat from a New chat sheet, with Linux tools as its switch says (the others get the Settings default). */
    Sessions.Session create(String backend, boolean select, boolean dev) {
        Sessions.Session s = sessions.create(backend, select, dev);
        changed();
        return s;
    }

    void select(Sessions.Session s) {
        sessions.select(s);
        changed();
    }

    void delete(Sessions.Session s) {
        savedFiles.remove(s.id);
        if (CodexLive.on(s)) CodexLive.end(null);
        StepLog.deleteChat(app, s.id);
        try {
            Helper.run(app, "delete", s.id);
        } catch (Exception ignored) {
        }
        viewing.remove(s);
        if (!s.parent.isEmpty() && s.dev) { // a helper's own folder goes with it
            final String dir = Helpers.folder(s.id);
            new Thread(() -> {
                try {
                    Linux.exec(app, "rm -rf " + dir, 30);
                } catch (Exception ignored) {
                }
            }).start();
        }
        if (s.parent.isEmpty()) for (Sessions.Session h : Helpers.of(this, s.id)) delete(h); // its helpers go with it
        sessions.remove(s);
        Schedules.cancelAll(app, s);
        changed();
    }

    /** Sends a message. Returns an error text, or null when sent. */
    String send(final Sessions.Session ses, String text) {
        return send(ses, text, null);
    }

    /** Sends a message with attachments (ids from Attachments); the helper fetches them before the agent runs. */
    String send(final Sessions.Session ses, String text, List<String> files) {
        String skill = ses.running ? null : Skills.prompt(app, ses.backend, text); // "/name …": a skill picked with /
        if (skill != null) return send(ses, text, files, skill, false, false);
        return send(ses, text, files, null);
    }

    /** A notification rule's run (see Triggers): the chat shows a short line, the agent gets the whole prompt. */
    String sendFromRule(Sessions.Session ses, String shown, String prompt) {
        return send(ses, shown, null, prompt, false);
    }

    /**
     * "Check again" in a rule's chat (see Triggers.recheck): the user's own run, but over other people's messages, so
     * without shell, files or web like a rule's.
     */
    String sendRecheck(Sessions.Session ses, String shown, String prompt) {
        return send(ses, shown, null, prompt, false, true);
    }

    /**
     * A scheduled task's run (see Schedules): the user's saved message, shown as theirs and marked "Scheduled", with
     * the chat's own settings (Linux tools included). The agent gets it with a line saying when and why it came.
     */
    String sendScheduled(Sessions.Session ses, String text, String prompt) {
        if (ses.running) return app.getString(R.string.schedule_skip_running);
        return send(ses, text, null, prompt, true);
    }

    /** A helper's one run (see Helpers): the chat shows its task, the agent gets it with the helper's rules. */
    String sendHelper(Sessions.Session ses, String shown, String prompt) {
        return send(ses, shown, null, prompt, false, false);
    }

    private String send(final Sessions.Session ses, String text, List<String> files, String prompt) {
        return send(ses, text, files, prompt, false);
    }

    private String send(final Sessions.Session ses, String text, List<String> files, String prompt, boolean scheduled) {
        return send(ses, text, files, prompt, scheduled, prompt != null && !scheduled);
    }

    /** rule: a notification rule's run (someone else's message): no shell, files or web. */
    private String send(final Sessions.Session ses, String text, List<String> files, String prompt, boolean scheduled,
                        boolean rule) {
        if (ses.running) {
            if (files != null && !files.isEmpty()) return app.getString(R.string.hub_still_working_attachments);
            return addNote(ses, text);
        }
        boolean hasFiles = files != null && !files.isEmpty();
        if (text.isEmpty() && !hasFiles) return null;
        Providers.P service = Providers.of(ses.model); // the chat's own pick: said, not quietly swapped for another model
        if (service != null && service.off) return app.getString(R.string.hub_service_off, service.name);
        // the phone tools live in Buddy's accessibility service: without it the agent can't reach them and its run
        // ends a few seconds in, which looked like Buddy's Linux had failed. Said as what it is (issue #54)
        if (BuddyService.get() == null) {
            BuddyService.restoreAccess(app); // dropped by Android: back on for the next message, where Buddy may
            return app.getString(SetupState.accessibilityEnabled(app) ? R.string.hub_accessibility_dead
                    : R.string.hub_accessibility_off);
        }
        String forAgent = prompt != null ? prompt : text.isEmpty() && hasFiles ? "Have a look at what I attached." : text;
        // the chat was switched to this agent: it begins anew, with the chat so far (before this message is added)
        // or it's a fork, or the user edited a message (issue #44): the agent starts fresh with the chat up to here
        final boolean fresh = ses.handover != null || ses.reseed;
        if (fresh) forAgent = (ses.handover != null ? handover(ses) : reseedText(ses)) + forAgent;
        BuddyService svc = BuddyService.get();
        // only the user's own message lifts a Stop: a queued rule run must not undo it
        if (svc != null && prompt == null) svc.allowControl();
        final int[] run = {-1}; // this run's id, once it has started
        try {
            // a rule's run works for someone else's message: no shell, files or web, even with Linux tools on
            Helper.runFor(app, ses.id, code -> main.postDelayed(() -> helperGone(ses, run[0], code), HELPER_GONE_MS),
                    "ask", ses.id, ses.backend, rule ? "rule" : devArg(ses), forAgent,
                    model(ses), hasFiles ? android.text.TextUtils.join(",", files) : "", ses.effort, fresh ? "fresh" : "",
                    Lite.on(ses) ? "lite" : "");
        } catch (Exception e) {
            return e.getMessage();
        }
        ses.interrupted = false;
        // the user writing in an archived chat brings it back (not a sub-agent's: its parent's chat lists it)
        if (!scheduled && !rule && ses.parent.isEmpty()) ses.archived = false;
        sessions.add(ses, Bubble.Kind.USER, text, hasFiles ? new ArrayList<>(files) : null);
        if (!scheduled && !rule) SpeechPause.sent(app);
        Sessions.Msg um = ses.messages.get(ses.messages.size() - 1);
        if (scheduled) {
            um.scheduled = true;
            sessions.save(ses);
        }
        for (Listener l : new ArrayList<>(listeners)) l.onMessage(ses, um);
        startRun(ses);
        ses.scheduledRun = scheduled;
        ses.status = app.getString(R.string.hub_thinking);
        changed();
        final int id = ses.runId;
        run[0] = id;
        Providers.P lp = Providers.of(Models.effective(ses));
        final boolean local = lp != null && lp.local(), onPhone = lp != null && OnPhone.ID.equals(lp.id);
        final long giveUp = local ? LOCAL_GIVE_UP_MS : GIVE_UP_MS;
        // the agent is running but its model can be slow (free models especially): say so, keep waiting
        main.postDelayed(() -> {
            if (ses.running && id == ses.runId && SystemClock.elapsedRealtime() - ses.lastEventAt >= NO_RESPONSE_MS - 100) {
                add(ses, Bubble.Kind.STEP, app.getString(onPhone ? R.string.hub_still_reading_phone
                        : local ? R.string.hub_still_reading_local : R.string.hub_still_waiting));
                changed();
            }
        }, NO_RESPONSE_MS);
        main.postDelayed(() -> {
            if (ses.running && id == ses.runId && SystemClock.elapsedRealtime() - ses.lastEventAt >= giveUp - 100) {
                failRun(ses, app.getString(R.string.hub_no_answer));
            }
        }, giveUp);
        return null;
    }

    /** After the helper process of a run ended, its last events get this long to arrive. */
    private static final long HELPER_GONE_MS = 5000;
    /** After Stop, a run has this long to say it ended (the helper kills what is left of it after 5 s). */
    private static final long STOP_GIVE_UP_MS = 20000;

    /**
     * The helper that ran this chat's agent is gone. It says buddy_done however it ends, except when it is killed
     * outright (Android freeing memory, Buddy's Linux crashing): then the chat stayed "working" for good, and a chat
     * waiting for it as its sub-agent with it. The run ends here, as failed.
     */
    private void helperGone(Sessions.Session ses, int runId, int code) {
        if (!ses.running || ses.runId != runId) return;
        // the chat's earlier run is still going (this helper only said so): that run's own end ends the chat's work,
        // and until then Stop must still reach it
        if (code == Helper.BUSY) return;
        // with how it ended, for a report: 137 is a kill from outside (Android ending an app's child processes)
        // 137: killed from outside, which on a phone is Android ending an app's child processes when there are too
        // many (confirmed on a Pixel 10 Pro with sub-agents, issue #50): say what lets them run
        String why = app.getString(R.string.hub_helper_gone) + (code > 0 ? " (exit " + code + ")" : "")
                + (code == 137 ? "\n\n" + app.getString(R.string.hub_helper_killed) : "");
        // what the dead helper left running (its shell and the agent, without the proot they ran under) is ended:
        // it would spin at full CPU for good, and its pid file would have the chat's next message refused as busy.
        // The chat stays working until that is done: a message sent meanwhile would start a run the reap then ends
        try {
            Helper.run(app, reaped -> main.post(() -> {
                if (ses.running && ses.runId == runId) failRun(ses, why);
            }), "reap", ses.id);
        } catch (Exception e) {
            failRun(ses, why);
        }
    }

    /** The run is over without the helper's buddy_done: ended here as failed, with why and the agent's last error. */
    private void failRun(Sessions.Session ses, String why) {
        ses.running = false;
        ses.status = "";
        if (!sessions.all.contains(ses)) return; // deleted meanwhile: a message would bring the chat back
        String err = lastError(ses);
        add(ses, Bubble.Kind.ERROR, why + (err.isEmpty() ? "" : "\n\n" + ses.brainName() + ": " + err));
        if (ses.outcome == null) ses.outcome = "failed";
        runEnded(ses);
        changed();
    }

    private static long read(JSONObject modelUsage) {
        return modelUsage.optLong("inputTokens") + modelUsage.optLong("cacheReadInputTokens");
    }

    /** The end of what the agent wrote to stderr this run (buddy-run keeps it), or "": a hung agent often says why there. */
    private String lastError(Sessions.Session ses) {
        java.io.File f = new java.io.File(Linux.root(app), "root/.buddy/sessions/" + ses.id + "/last-error.log");
        try (java.io.RandomAccessFile r = new java.io.RandomAccessFile(f, "r")) {
            long n = Math.min(r.length(), 4000);
            byte[] b = new byte[(int) n];
            r.seek(r.length() - n);
            r.readFully(b);
            return new String(b, java.nio.charset.StandardCharsets.UTF_8).trim();
        } catch (Exception e) {
            return "";
        }
    }

    /** Change a chat's model; the next message uses it, and the conversation continues. */
    void setModel(Sessions.Session ses, String model) {
        ses.model = model == null ? "" : model.trim();
        // (no line in the chat: it showed up as "1 tool call", and the chip in the message box says the model and effort)
        sessions.save(ses);
        changed();
    }

    /**
     * Another agent takes this chat over from the next message on. It has no part in what the first one did, so it
     * starts a conversation of its own and is given the chat so far as text (see handover): what was said, not the
     * steps, files and tool results the other agent had in mind. The model and effort go back to the new agent's
     * defaults, as their names differ per agent.
     */
    void switchAgent(Sessions.Session ses, String backend) {
        if (ses.running || backend == null || backend.equals(ses.backend) || !ses.parent.isEmpty()) return;
        if (backend.equals(ses.handover)) { // straight back, before the other agent answered: as if never switched
            ses.backend = backend; // its own conversation is still there, with its steps and files
            ses.model = ses.handoverModel;
            ses.effort = ses.handoverEffort;
            ses.actualModel = ses.handoverActual;
            ses.ctxUsed = ses.handoverCtxUsed;
            ses.ctxWindow = ses.handoverCtxWindow;
            ses.cut = ses.handoverCut;
            ses.handover = null;
            for (int i = ses.messages.size() - 1; i >= 0 && ses.messages.get(i).kind == Bubble.Kind.NOTE; i--) {
                ses.messages.remove(i); // the lines that said it was switched
            }
            sessions.save(ses);
            changed();
            return;
        }
        if (ses.handover == null) {
            ses.handover = ses.backend;
            ses.handoverModel = ses.model;
            ses.handoverEffort = ses.effort;
            ses.handoverActual = ses.actualModel;
            ses.handoverCtxUsed = ses.ctxUsed;
            ses.handoverCtxWindow = ses.ctxWindow;
            ses.handoverCut = ses.cut;
        }
        ses.backend = backend;
        ses.model = "";
        ses.effort = "";
        ses.actualModel = "";
        ses.ctxUsed = 0;
        ses.ctxWindow = 0;
        ses.cut = false;
        ses.interrupted = false;
        add(ses, Bubble.Kind.NOTE, app.getString(R.string.hub_agent_switched, Account.nameOf(backend)));
        sessions.save(ses);
        changed();
    }

    /**
     * The most of a chat's text a new agent is handed, in UTF-8 bytes: the end of it, where the work stands. The
     * message goes to the agent as one argument, which Linux caps at 128 KB, and it shares that with the user's
     * own message and, for some agents, Buddy's instructions.
     */
    private static final int HANDOVER_MAX = 60_000;

    /** The end of s that fits in max UTF-8 bytes, cut between characters. */
    private static String tail(String s, int max) {
        int bytes = 0, i = s.length();
        while (i > 0) {
            int cp = s.codePointBefore(i);
            int n = cp < 0x80 ? 1 : cp < 0x800 ? 2 : cp < 0x10000 ? 3 : 4;
            if (bytes + n > max) break;
            bytes += n;
            i -= Character.charCount(cp);
        }
        return s.substring(i);
    }

    /** Who said what in the chat so far, oldest first, its end within HANDOVER_MAX; {text, "cut" or ""}. */
    private static String[] transcript(Sessions.Session ses) {
        StringBuilder b = new StringBuilder();
        for (Sessions.Msg m : new ArrayList<>(ses.messages)) {
            if (m.kind != Bubble.Kind.USER && m.kind != Bubble.Kind.BUDDY) continue;
            if (m.text == null || m.text.trim().isEmpty()) continue;
            b.append(m.kind == Bubble.Kind.USER ? "User: " : "Assistant: ").append(m.text.trim()).append("\n\n");
        }
        String all = b.toString(), end = tail(all, HANDOVER_MAX);
        return new String[]{end, end.length() < all.length() ? "cut" : ""};
    }

    /** A fork's chat so far, or the chat before an edited message, for its agent that starts anew (issue #44). */
    private static String reseedText(Sessions.Session ses) {
        String[] t = transcript(ses);
        if (t[0].isEmpty()) return "";
        return "[This chat starts from an earlier conversation: the user forked it here, or edited their message and "
                + "is asking again from this point. Below is the conversation so far" + (t[1].isEmpty() ? "" : " (its "
                + "earlier part is left out)") + ", oldest first. It is context: what was asked and answered. You don't "
                + "have the steps, files or tool results from then, so look again at anything you need (read the "
                + "screen, open the file) rather than assuming. Don't redo actions it reports as done.]\n\n" + t[0]
                + "[End of the earlier conversation. The user's message to you now:]\n\n";
    }

    /**
     * Fork from here (issue #44): a new chat with this one's messages up to and including index, the same agent,
     * model and Linux tools, and nothing approved or pending. Null when it can't be made.
     */
    Sessions.Session fork(Sessions.Session ses, int index) {
        if (!ses.parent.isEmpty() || index < 0 || index >= ses.messages.size()) return null;
        Sessions.Session f = sessions.copy(ses, index + 1, app.getString(R.string.chat_fork_title, ses.label()));
        if (f == null) return null;
        sessions.add(f, Bubble.Kind.NOTE, app.getString(R.string.chat_forked_from, ses.label()), null);
        sessions.select(f);
        changed();
        return f;
    }

    /**
     * The user edited their message at index (issue #44): it and everything after it go, and the edited text is sent
     * from there, to an agent that starts fresh with the chat before it. The chat as it was is kept, archived, as
     * "… (before edit)". Returns an error to show, or null.
     */
    String edit(Sessions.Session ses, int index, String text) {
        if (ses.running) return app.getString(R.string.chat_edit_running);
        if (!ses.parent.isEmpty() || index < 0 || index >= ses.messages.size()
                || ses.messages.get(index).kind != Bubble.Kind.USER || text.trim().isEmpty()) {
            return app.getString(R.string.chat_edit_failed); // (the message isn't there any more): said, not dropped
        }
        Sessions.Session before = sessions.copy(ses, ses.messages.size(),
                app.getString(R.string.chat_before_edit_title, ses.label()));
        if (before == null) return app.getString(R.string.chat_edit_failed);
        before.archived = true;
        sessions.save(before);
        java.util.List<String> files = new ArrayList<>(ses.messages.get(index).files);
        java.util.List<Sessions.Msg> cut = new ArrayList<>(ses.messages.subList(index, ses.messages.size()));
        boolean wasReseed = ses.reseed, wasInterrupted = ses.interrupted;
        String wasHandover = ses.handover;
        while (ses.messages.size() > index) ses.messages.remove(ses.messages.size() - 1);
        BuddyService svc = BuddyService.get();
        if (svc != null && svc.bubble() != null) svc.bubble().redrawLater(ses); // the bubble's panel shows it whole again
        String wasTitle = ses.title;
        // the first message edited: a chat named after it (not by the user) is named after the new one
        if (index == 0 && ses.title.equals(Sessions.autoTitle(cut.get(0).text, cut.get(0).files))) ses.title = "";
        ses.reseed = true;
        ses.handover = null;
        ses.interrupted = false;
        sessions.save(ses);
        String err = send(ses, text.trim(), files.isEmpty() ? null : files);
        if (err != null && !ses.running) { // it didn't go: the chat as it was, and no copy of it in Archived
            while (ses.messages.size() > index) ses.messages.remove(ses.messages.size() - 1);
            ses.messages.addAll(cut);
            ses.reseed = wasReseed;
            ses.title = wasTitle;
            ses.handover = wasHandover;
            ses.interrupted = wasInterrupted;
            sessions.save(ses);
            sessions.remove(before);
        } else if (err == null) {
            for (int i = ses.messages.size() - 1; i >= 0; i--) {
                Sessions.Msg m = ses.messages.get(i);
                if (m.kind != Bubble.Kind.USER) continue;
                m.edited = true;
                break;
            }
            sessions.save(ses);
        }
        changed();
        return err;
    }

    /** The chat so far, for the agent that takes it over: who said what, oldest first, marked as context. */
    private static String handover(Sessions.Session ses) {
        String[] t = transcript(ses);
        if (t[0].isEmpty()) return "";
        String all = t[0];
        boolean cutOff = !t[1].isEmpty();
        return "[This chat was with another agent (" + Account.nameOf(ses.handover) + ") until now, and the user switched it to you. "
                + "Below is the conversation so far" + (cutOff ? " (its earlier part is left out)" : "") + ", oldest "
                + "first. It is context: what was asked and answered. You don't have that agent's steps, files or tool "
                + "results, so look again at anything you need (read the screen, open the file) rather than assuming. "
                + "Don't redo actions it reports as done.]\n\n" + all + "[End of the earlier conversation. The user's "
                + "message to you now:]\n\n";
    }

    /** Change a chat's effort ("" = the agent's default); the next message uses it. */
    void setEffort(Sessions.Session ses, String effort) {
        ses.effort = effort == null ? "" : effort;
        // (no line in the chat: it showed up as "1 tool call", and the chip in the message box says the model and effort)
        sessions.save(ses);
        changed();
    }

    /** Turn a chat's Linux tools on or off; the next message uses them (a running one keeps what it started with). */
    void setDev(Sessions.Session ses, boolean on) {
        ses.dev = on;
        sessions.save(ses);
        changed();
    }

    /**
     * Set or remove (null) a chat's scheduled task, and its alarm with it. Removing it also ends the "Allowed without
     * asking" apps kept for it (a running chat keeps them until its run ends, as any chat).
     */
    void setSchedule(Sessions.Session ses, Schedules.Plan plan) {
        ses.schedule = plan;
        if (plan == null && !ses.running) ses.allowed.clear();
        sessions.save(ses);
        Schedules.set(app, ses);
        changed();
    }

    /** Long-task mode for a chat: the screen stays on while it works (see KeepAwake); a running one gets it at once. */
    void setIncognito(Sessions.Session ses, boolean on) {
        ses.incognito = on;
        sessions.save(ses);
        changed();
    }

    /** Chat options → Tools: this chat's own on or off for a tool (null: as in Settings again). */
    void setTool(Sessions.Session ses, String tool, Boolean on) {
        if (on == null) ses.tools.remove(tool);
        else ses.tools.put(tool, on);
        sessions.save(ses);
        changed();
    }

    /** Chat options → Tools → Same as Settings. */
    void clearTools(Sessions.Session ses) {
        ses.tools.clear();
        sessions.save(ses);
        changed();
    }

    /** Lite mode (see Lite): the user's pick for this chat, from its next message on. */
    void setLite(Sessions.Session ses, boolean on) {
        ses.lite = on;
        sessions.save(ses);
        changed();
    }

    /** Can this chat's missing start be looked up? Claude Code and Codex keep a record Buddy can read. */
    static boolean canRestore(Sessions.Session ses) {
        return ses.cut && ("claude".equals(ses.backend) || "codex".equals(ses.backend));
    }

    /**
     * A chat that lost its start to the old 150-message limit gets it back from its agent's own record of the
     * conversation (Claude Code's or Codex's session file in Buddy's Linux): what the user and the agent said before
     * the first message Buddy still has. The tool steps of that part aren't rebuilt, only the words. done gets how
     * many messages came back (0: none found), on the main thread.
     */
    void restoreEarlier(final Sessions.Session ses, final java.util.function.IntConsumer done) {
        final boolean codex = "codex".equals(ses.backend);
        final List<String> have = new ArrayList<>(); // Buddy's first messages with words, as "U…" or "B…"
        for (Sessions.Msg m : ses.messages) {
            if (m.kind != Bubble.Kind.USER && m.kind != Bubble.Kind.BUDDY || m.text.trim().length() < 8) continue;
            have.add((m.kind == Bubble.Kind.USER ? "U" : "B") + key(m.text));
            if (have.size() >= 8) break;
        }
        new Thread(() -> {
            final List<Sessions.Msg> early = new ArrayList<>();
            try {
                String dir = "$HOME/.buddy/sessions/" + ses.id;
                String words = codex
                        ? "select(.type==\"response_item\" and .payload.type==\"message\" and (.payload.role==\"user\" or "
                        + ".payload.role==\"assistant\")) | {role:.payload.role, text:([.payload.content[]? | .text // empty] | join(\"\\n\"))}"
                        : "select(.type==\"user\" or .type==\"assistant\") | {role:.message.role, text:(if (.message.content|type)==\"string\" "
                        + "then .message.content else ([.message.content[]? | select(.type==\"text\") | .text] | join(\"\\n\")) end)}";
                String find = codex
                        ? "id=$(cat " + dir + "/codex-thread 2>/dev/null); f=$(find $HOME/.codex/sessions -name \"*$id.jsonl\" 2>/dev/null | head -1); "
                        : "id=$(cat " + dir + "/claude-session 2>/dev/null); f=$(ls $HOME/.claude/projects/*/$id.jsonl 2>/dev/null | head -1); ";
                Linux.exec(app, find + "[ -n \"$id\" ] && [ -f \"$f\" ] && jq -c '" + words
                        + " | select(.text != \"\" and (.text|startswith(\"<\")|not))' \"$f\" 2>/dev/null"
                        + " | jq -cs 'map(.text |= .[0:8000])' > " + dir + "/history.json", 180);
                java.io.File f = new java.io.File(Linux.root(app), "root/.buddy/sessions/" + ses.id + "/history.json");
                byte[] b = java.nio.file.Files.readAllBytes(f.toPath());
                f.delete();
                JSONArray all = new JSONArray(new String(b, java.nio.charset.StandardCharsets.UTF_8));
                List<String> keys = new ArrayList<>();
                for (int i = 0; i < all.length(); i++) {
                    JSONObject o = all.getJSONObject(i);
                    keys.add(("user".equals(o.optString("role")) ? "U" : "B") + key(o.optString("text")));
                }
                // where Buddy's part begins in the record: its first message with words (or, if that one reads
                // differently there, the next ones, minus those before them)
                int from = -1;
                for (int j = 0; j < have.size() && from < 0; j++) {
                    int i = keys.indexOf(have.get(j));
                    if (i >= 0) from = Math.max(0, i - j);
                }
                for (int i = 0; i < from; i++) {
                    JSONObject o = all.getJSONObject(i);
                    early.add(new Sessions.Msg("user".equals(o.optString("role")) ? Bubble.Kind.USER : Bubble.Kind.BUDDY,
                            o.optString("text")));
                }
            } catch (Exception ignored) { // no record any more, or one that can't be read: nothing comes back
            }
            main.post(() -> {
                if (sessions.find(ses.id) == null) return;
                int room = Math.max(0, Sessions.MAX_MESSAGES - ses.messages.size());
                List<Sessions.Msg> add = early.size() > room ? early.subList(early.size() - room, early.size()) : early;
                ses.messages.addAll(0, add);
                ses.cut = false;
                sessions.save(ses);
                changed();
                done.accept(add.size());
            });
        }).start();
    }

    /** A message's words for comparing Buddy's copy with the agent's record: the start, spaces evened out. */
    private static String key(String text) {
        String t = text.trim().replaceAll("\\s+", " ");
        return t.length() > 160 ? t.substring(0, 160) : t;
    }

    /** Background mode for a chat; switched off, its display goes (with what's on it). */
    void setBackground(Sessions.Session ses, boolean on) {
        ses.background = on;
        sessions.save(ses);
        if (!on) Background.close(BuddyApp.context(), ses.id);
        changed();
    }

    void setAwake(Sessions.Session ses, boolean on) {
        ses.awake = on;
        sessions.save(ses);
        changed();
    }

    /**
     * Add or take away an app from a chat's "Allowed without asking" (until its run ends, see Guard.runRule; with a
     * schedule, kept for its scheduled runs).
     */
    void setAllowed(Sessions.Session ses, String pkg, boolean on) {
        if (on) ses.allowed.add(pkg);
        else ses.allowed.remove(pkg);
        if (ses.schedule != null) sessions.save(ses);
        changed();
    }

    /**
     * The calling chat's approvals choice (any thread, see Session.approvals): "", "routine" or "strict". Always ""
     * for a session outside Buddy's chats and for a sub-agent, which only looks things up.
     */
    String approvalsFor(String chat) {
        Sessions.Session s = byId(chat);
        return s != null && s.parent.isEmpty() ? s.approvals : "";
    }

    /** The chat with this id, for a tool's thread (a copy of the list: it changes on the main thread); or null. */
    private Sessions.Session byId(String chat) {
        if (chat == null) return null;
        for (Sessions.Session s : new ArrayList<>(sessions.all)) if (s.id.equals(chat)) return s;
        return null;
    }

    /** The calling chat's agent acted in this app (any thread): kept with the chat (see Session.apps), saved when new. */
    void appUsed(String chat, String pkg) {
        final Sessions.Session s = byId(chat);
        if (s == null || pkg == null || !s.apps.add(pkg)) return;
        main.post(() -> {
            if (sessions.all.contains(s)) sessions.save(s);
        });
    }

    /** The line under a chat's header applies to it: not a sub-agent's chat, nor a notification rule's. */
    boolean approvalsEditable(Sessions.Session ses) {
        return ses != null && ses.parent.isEmpty() && !Triggers.ruleChat(app, ses.id);
    }

    void setApprovals(Sessions.Session ses, String choice) {
        // what the old choice approved and the agent hasn't used yet doesn't carry over into the new one
        if (!choice.equals(ses.approvals)) Guard.forget(ses.id);
        ses.approvals = choice;
        sessions.save(ses);
        changed();
    }

    /** The calling chat's "Allowed without asking" apps (any thread); empty for a session outside Buddy's chats. */
    java.util.Set<String> allowedFor(String chat) {
        Sessions.Session s = byId(chat);
        if (s == null) return java.util.Collections.emptySet();
        synchronized (s.allowed) {
            return new java.util.HashSet<>(s.allowed);
        }
    }

    /**
     * Whether the agent behind a phone tool call has Linux tools (any thread): its chat's own, or for the user's own
     * sessions outside Buddy's chats (no chat), the Settings default.
     */
    boolean devFor(String chat) {
        if (chat == null) return Prefs.devMode(app);
        for (Sessions.Session s : new ArrayList<>(sessions.all)) if (s.id.equals(chat)) return s.dev;
        return false;
    }

    /** Stop every chat that is working (the bubble's stop button). */
    void stopAll() {
        for (Sessions.Session s : new java.util.ArrayList<>(sessions.all)) if (s.running) stop(s);
    }

    void stop(Sessions.Session ses) {
        if (ses.parent.isEmpty()) Helpers.stop(this, ses.id); // a stopped task's helpers stop with it
        if (!ses.running) return;
        BuddyService svc = BuddyService.get();
        if (svc != null) svc.stopPaused();
        try {
            Helper.run(app, "stop", ses.id);
            // set now: the run's own buddy_done can arrive before buddy_stopped, and must not send the notes
            ses.outcome = "stopped";
            ses.status = app.getString(R.string.hub_stopping);
            // a run that can no longer speak (see buddy-run's end_run) never says it has ended: the stop that killed
            // it is its end, and the chat doesn't stay on "Stopping" (issue #53)
            final int id = ses.runId;
            main.postDelayed(() -> {
                if (!ses.running || ses.runId != id) return;
                ses.running = false;
                ses.status = "";
                if (!sessions.all.contains(ses)) return; // deleted meanwhile: a line would bring the chat back
                String stopped = app.getString(R.string.hub_stopped);
                Sessions.Msg last = ses.messages.isEmpty() ? null : ses.messages.get(ses.messages.size() - 1);
                if (last == null || !stopped.equals(last.text)) add(ses, Bubble.Kind.STEP, stopped);
                runEnded(ses);
                changed();
            }, STOP_GIVE_UP_MS);
        } catch (Exception e) {
            add(ses, Bubble.Kind.ERROR, e.getMessage());
        }
        changed();
    }

    void note(Sessions.Session ses, Bubble.Kind kind, String text) {
        add(ses, kind, text);
        changed();
    }

    /** Called for any phone-tool use; keeps running chats from timing out. */
    void onToolActivity() {
        for (Sessions.Session s : sessions.all) if (s.running) s.lastEventAt = SystemClock.elapsedRealtime();
    }

    void onEvent(JSONObject ev) {
        Sessions.Session ses = sessions.find(ev.optString("buddy_session", null));
        if (ses == null) return; // unknown chat (deleted, or started outside Buddy)
        ses.lastEventAt = SystemClock.elapsedRealtime();
        String type = ev.optString("type");
        if (!ses.running && (("system".equals(type) && !"model".equals(ev.optString("subtype"))) || "assistant".equals(type))) startRun(ses);
        switch (type) {
            case "system":
                if ("model".equals(ev.optString("subtype"))) { // Codex, after its turn (see buddy-run)
                    String m = ev.optString("model", "");
                    if (!m.isEmpty() && !m.equals(ses.actualModel)) {
                        ses.actualModel = m;
                        sessions.save(ses);
                        changed();
                    }
                } else if ("status".equals(ev.optString("subtype"))) { // Claude Code: compacting the conversation
                    if ("compacting".equals(ev.optString("status"))) ses.status = app.getString(R.string.hub_compacting);
                } else if ("compact_boundary".equals(ev.optString("subtype"))) {
                    JSONObject cm = ev.optJSONObject("compact_metadata");
                    if (cm != null && cm.optLong("post_tokens") > 0) {
                        ses.ctxUsed = cm.optLong("post_tokens");
                        add(ses, Bubble.Kind.STEP, app.getString(R.string.hub_compacted,
                                Models.tokens(cm.optLong("pre_tokens")), Models.tokens(ses.ctxUsed)));
                    }
                } else if ("init".equals(ev.optString("subtype"))) {
                    ses.status = app.getString(R.string.hub_thinking);
                    // the model really in use (Claude Code says it here), shown when the chat is on "Default"
                    String m = ev.optString("model", "");
                    if (!m.isEmpty() && !m.equals(ses.actualModel)) {
                        ses.actualModel = m;
                        sessions.save(ses);
                    }
                }
                break;
            case "assistant": {
                JSONObject msg = ev.optJSONObject("message");
                JSONObject u = msg == null ? null : msg.optJSONObject("usage");
                // Claude Code: what this request sent and got is how full the context is now (not a subagent's)
                if (u != null && ev.isNull("parent_tool_use_id")) {
                    long used = u.optLong("input_tokens") + u.optLong("cache_creation_input_tokens")
                            + u.optLong("cache_read_input_tokens") + u.optLong("output_tokens");
                    if (used > 0) ses.ctxUsed = used;
                }
                JSONArray content = msg == null ? null : msg.optJSONArray("content");
                if (content == null) break;
                for (int i = 0; i < content.length(); i++) {
                    JSONObject c = content.optJSONObject(i);
                    if (c == null) continue;
                    if ("text".equals(c.optString("type"))) {
                        String t = c.optString("text").trim();
                        if (t.contains(QUIET)) { // the agent's "no news" for a quiet scheduled run: not shown
                            ses.quietSaid = true;
                            t = t.replace(QUIET, "").trim();
                        }
                        if (!t.isEmpty()) {
                            add(ses, Bubble.Kind.BUDDY, t, replyFiles(ses, t));
                            ses.lastReply = t;
                            ses.gotText = true;
                            CodexLive.agentSaid(ses, t); // a live voice call in this chat tells the user
                        }
                    } else if ("tool_use".equals(c.optString("type"))) {
                        // a step that isn't the phone's (a Linux command, a file edit): the screen is the user's again
                        String tn = c.optString("name");
                        if (tn.startsWith(TOOL_PREFIX)) ses.phoneToolAt = SystemClock.elapsedRealtime();
                        else if (!BOOKKEEPING.contains(tn)) ses.otherToolAt = SystemClock.elapsedRealtime();
                        ses.reseed = false; // its agent got the conversation and is at work on it
                        String step = describeTool(c.optString("name"), c.optJSONObject("input"));
                        if (step != null) {
                            add(ses, Bubble.Kind.STEP, describeTool(c.optString("name"), c.optJSONObject("input"), true));
                            ses.status = step;
                            String n = c.optString("name");
                            if (n.equals(TOOL_PREFIX + "confirm") || n.equals(TOOL_PREFIX + "ask")) CodexLive.agentWaiting(ses, step, c.optJSONObject("input"));
                        }
                    }
                }
                break;
            }
            case "result": {
                // Claude Code: the context size it works with for the chat's model (its own idea of an unknown one)
                JSONObject mu = ev.optJSONObject("modelUsage");
                if (mu != null) {
                    JSONObject m = mu.optJSONObject(ses.actualModel);
                    if (m == null) { // otherwise the one that did most of the reading (a helper model reads little)
                        for (java.util.Iterator<String> it = mu.keys(); it.hasNext(); ) {
                            JSONObject o = mu.optJSONObject(it.next());
                            if (o != null && (m == null || read(o) > read(m))) m = o;
                        }
                    }
                    if (m != null && m.optLong("contextWindow") > 0) ses.ctxWindow = m.optLong("contextWindow");
                    sessions.save(ses);
                }
                boolean error = ev.optBoolean("is_error") || !"success".equals(ev.optString("subtype", "success"));
                String r = ev.optString("result", "").trim();
                if (error && ses.outcome == null) ses.outcome = "failed";
                if (error) {
                    String shown = r.isEmpty() ? app.getString(R.string.hub_agent_stopped, ses.brainName()) : r;
                    add(ses, Bubble.Kind.ERROR, shown);
                    CodexLive.agentSaid(ses, shown);
                } else if (r.contains(QUIET)) {
                    ses.quietSaid = true;
                    r = r.replace(QUIET, "").trim();
                    if (!ses.gotText && !r.isEmpty()) {
                        add(ses, Bubble.Kind.BUDDY, r, replyFiles(ses, r));
                        ses.lastReply = r;
                    }
                } else if (!ses.gotText && !r.isEmpty()) {
                    add(ses, Bubble.Kind.BUDDY, r, replyFiles(ses, r));
                    ses.lastReply = r;
                    CodexLive.agentSaid(ses, r);
                }
                break;
            }
            case "buddy_pictures": { // Codex: pictures its image tool made, which the reply may not name
                // a reply this turn already showed a picture, often a copy saved in the project: not the same one twice
                boolean shown = false;
                for (int i = ses.messages.size() - 1; i >= 0 && !shown; i--) {
                    Sessions.Msg m = ses.messages.get(i);
                    if (m.kind == Bubble.Kind.USER) break;
                    if (m.kind != Bubble.Kind.BUDDY) continue;
                    for (String f : m.files) if (Attachments.isImage(f)) shown = true; // (a saved PDF's chip isn't one)
                }
                if (shown) break;
                JSONArray paths = ev.optJSONArray("paths");
                List<String> files = new ArrayList<>();
                for (int i = 0; paths != null && i < paths.length(); i++) {
                    String file = picture(paths.optString(i));
                    if (file != null) files.add(file);
                }
                if (!files.isEmpty()) add(ses, Bubble.Kind.BUDDY, "", files);
                break;
            }
            case "buddy_error": {
                // the helper's own word on a run whose agent said nothing: not after the run said why itself
                if (ev.optBoolean("quiet") && ses.outcome != null) break;
                if (ses.outcome == null) ses.outcome = "failed";
                String text = ev.optString("text", app.getString(R.string.hub_something_wrong));
                add(ses, Bubble.Kind.ERROR, text);
                CodexLive.agentSaid(ses, text);
                break;
            }
            case "buddy_stopped":
                ses.outcome = "stopped";
                add(ses, Bubble.Kind.STEP, app.getString(R.string.hub_stopped));
                break;
            case "rate_limit_event": { // Claude Code, Codex: how much of the plan's 5-hour and weekly limits is used
                String warning = Usage.onEvent(app, ses.backend, ev);
                if (warning != null) add(ses, Bubble.Kind.STEP, warning);
                break;
            }
            case "buddy_context": // Codex, after its turn (see buddy-run): how full the context is, and its size
                if (ev.optLong("used") > 0) ses.ctxUsed = ev.optLong("used");
                if (ev.optLong("window") > 0) ses.ctxWindow = ev.optLong("window");
                sessions.save(ses);
                break;
            case "buddy_step": // a line from the helper under the steps (Antigravity: a refused step)
                add(ses, Bubble.Kind.STEP, ev.optString("text"));
                break;
            case "buddy_done":
                if (ses.running) {
                    ses.running = false;
                    ses.status = "";
                    List<String> left0 = savedFiles.remove(ses.id); // saved, but no reply came to show them under
                    if (left0 != null && !left0.isEmpty()) add(ses, Bubble.Kind.BUDDY, "", left0);
                    runEnded(ses);
                    if (!ses.lastReply.isEmpty()) {
                        Prefs.setHadReply(app);
                        if (ses.parent.isEmpty()) flash(ses, ses.lastReply);
                    }
                    // what the user added but the agent never got (it made no phone action since): send it now
                    String left;
                    synchronized (ses.notes) {
                        left = ses.notes.isEmpty() ? null : android.text.TextUtils.join("\n", ses.notes);
                        ses.notes.clear();
                    }
                    if (left != null && "stopped".equals(ses.outcome)) {
                        // Stop means stop: no new run; the notes go back into the chat's message box
                        ses.unsent = ses.unsent == null ? left : ses.unsent + "\n" + left;
                        add(ses, Bubble.Kind.STEP, app.getString(R.string.hub_note_not_sent));
                    } else if (left != null) {
                        final String msg = left;
                        main.post(() -> {
                            if (!ses.running) sendNoteAsMessage(ses, msg);
                        });
                    }
                }
                break;
            default:
                break;
        }
        changed();
    }

    // ---------------------------------------------------------------- state

    int runningCount() {
        return sessions.runningCount();
    }

    /**
     * For the smart reviewer, from the chat that's working (call on the main thread): {agent backend, the
     * user's latest request, the agent's latest note}, or null when no chat is running (an outside agent).
     */
    String[] reviewContext() {
        Sessions.Session latest = latestRunning();
        if (latest == null) return null;
        String request = "", note = "";
        for (int i = latest.messages.size() - 1; i >= 0; i--) {
            Sessions.Msg m = latest.messages.get(i);
            if (note.isEmpty() && (m.kind == Bubble.Kind.BUDDY || m.kind == Bubble.Kind.STEP)) note = m.text;
            if (m.kind == Bubble.Kind.USER) {
                request = m.text;
                break;
            }
        }
        if (!latest.lastReply.isEmpty() && note.isEmpty()) note = latest.lastReply;
        return new String[]{latest.backend, request, note};
    }

    /**
     * Something the user says while the chat works (typed or dictated): shown in the chat now, passed to the
     * agent with its next phone action (see takeNotes), or sent as the next message if the task ends first.
     */
    String addNote(Sessions.Session ses, String text) {
        text = text == null ? "" : text.trim();
        if (text.isEmpty()) return null;
        if (!ses.running) return send(ses, text, null);
        // writing to the agent while paused in the browser's live view: the user is done there
        BuddyService svc = BuddyService.get();
        if (svc != null && svc.pausedForBrowser()) svc.resume();
        synchronized (ses.notes) {
            ses.notes.add(text);
        }
        add(ses, Bubble.Kind.USER, text);
        add(ses, Bubble.Kind.STEP, app.getString(R.string.hub_note_added, ses.brainName()));
        changed();
        return null;
    }

    /**
     * The model to run. OpenCode's own default free model can take over 30 seconds to answer, so without
     * connected providers Buddy uses Big Pickle, which answers in seconds.
     */
    /** The helper's mode for a chat's run: "1" with Linux tools, "0" without. */
    private static String devArg(Sessions.Session ses) {
        return ses.dev ? "1" : "0";
    }

    private static String model(Sessions.Session ses) {
        String eff = Models.effective(ses); // the chat's pick, or a service the agent uses by default
        if (!eff.isEmpty() || !"opencode".equals(ses.backend)) return eff;
        String plan = Account.OPENCODE.plan;
        // the quick free model (OpenCode's own free default took 36 s for a "Hi"), while OpenCode still lists it
        return (plan.isEmpty() || plan.equals("free models")) && Models.has("opencode", "opencode/big-pickle")
                ? "opencode/big-pickle" : "";
    }

    /** A note that arrived too late for the run: becomes the next message (already shown in the chat). */
    private void sendNoteAsMessage(Sessions.Session ses, String text) {
        final int[] run = {-1}; // this run's id, once it has started (as in send)
        try {
            Helper.runFor(app, ses.id, code -> main.postDelayed(() -> helperGone(ses, run[0], code), HELPER_GONE_MS),
                    "ask", ses.id, ses.backend, devArg(ses),
                    "(Added while you were working; if it asks something, answer it:) " + text, model(ses), "", ses.effort,
                    "", Lite.on(ses) ? "lite" : "");
        } catch (Exception e) {
            add(ses, Bubble.Kind.ERROR, e.getMessage());
            return;
        }
        startRun(ses);
        run[0] = ses.runId;
        ses.status = app.getString(R.string.hub_thinking);
        changed();
    }

    /** The user dismissed the offer to continue a cut-off run. */
    void dismissInterrupted(Sessions.Session ses) {
        ses.interrupted = false;
        sessions.save(ses);
        changed();
    }

    /** Notes the user added before stopping this chat, for its message box; taken once (null when none). */
    String takeUnsent(Sessions.Session ses) {
        String text = ses.unsent;
        ses.unsent = null;
        return text;
    }

    /** The newest working chat, or null. */
    /** The working chat with the latest step: one the user talks to, not a sub-agent (those work for their parent). */
    Sessions.Session latestRunning() {
        Sessions.Session latest = null;
        for (Sessions.Session s : sessions.all)
            if (s.running && s.parent.isEmpty() && (latest == null || s.lastEventAt > latest.lastEventAt)) latest = s;
        return latest;
    }

    /**
     * Notes for the agent that's working, to attach to a phone tool's result (any thread). Taken once: null
     * when there are none.
     */
    /** chat: the calling chat, whose notes these are (null, an agent outside Buddy's chats: any working chat's). */
    String takeNotes(String chat) {
        for (Sessions.Session s : new ArrayList<>(sessions.all)) {
            if (!s.running) continue;
            // (a sub-agent's phone step must not take what the user told its parent, which waits in delegate)
            if (chat != null ? !s.id.equals(chat) : !s.parent.isEmpty()) continue;
            String joined;
            synchronized (s.notes) {
                if (s.notes.isEmpty()) continue;
                joined = android.text.TextUtils.join("\n", s.notes);
                s.notes.clear();
            }
            main.post(() -> {
                add(s, Bubble.Kind.STEP, app.getString(R.string.hub_note_passed));
                changed();
            });
            return joined;
        }
        return null;
    }

    /** Text for the bubble's status pill, or null for none. */
    String pillText() {
        int n = runningCount();
        if (n > 0) {
            Sessions.Session latest = latestWorking();
            String st = latest == null || latest.status.isEmpty() ? app.getString(R.string.chat_working) : latest.status;
            return n > 1 ? app.getResources().getQuantityString(R.plurals.hub_pill_chats, n, n, st) : st;
        }
        return flash;
    }

    /**
     * The chat the pill's text is about (see pillText), or null: the working chat with the latest step (a sub-agent's
     * step: the chat it works for), else the one whose reply shows.
     */
    Sessions.Session pillChat() {
        Sessions.Session latest = latestWorking();
        if (latest == null) return flash != null && sessions.all.contains(flashChat) ? flashChat : null;
        Sessions.Session parent = latest.parent.isEmpty() ? null : sessions.find(latest.parent);
        return parent != null ? parent : latest;
    }

    /** The working chat or sub-agent with the latest step: the one whose status the pill shows. */
    private Sessions.Session latestWorking() {
        Sessions.Session latest = null;
        for (Sessions.Session s : sessions.all)
            if (s.running && (latest == null || s.lastEventAt > latest.lastEventAt)) latest = s;
        return latest;
    }

    boolean anyUnread() {
        return sessions.anyUnread();
    }

    private void flash(Sessions.Session ses, String reply) {
        flashChat = ses;
        final String text = Ui.linkText(reply).replace('\n', ' ');
        flash = text.length() > 60 ? text.substring(0, 59) + "…" : text;
        final String mine = flash;
        main.postDelayed(() -> {
            if (mine.equals(flash)) {
                flash = null;
                flashChat = null;
                changed();
            }
        }, 8000);
    }

    /** After its run a background chat's screen stays this long (for a follow-up, or Show on my screen), then closes. */
    private static final long BACKGROUND_RELEASE_MS = 60_000;

    private void startRun(Sessions.Session ses) {
        ses.running = true;
        ses.phoneToolAt = 0; // (what its earlier run did says nothing about this one)
        ses.otherToolAt = 0;
        ses.outcome = null;
        ses.gotText = false;
        ses.lastReply = "";
        ses.lastEventAt = SystemClock.elapsedRealtime();
        ses.runId++;
        Tools.runStarted(ses.id);
        StepLog.runStarted(app, ses);
        Prefs.setBusy(app, ses.id, true);
    }

    /**
     * A chat's run is over (finished, stopped or failed): what was allowed without asking ends with it, and in
     * long-task mode or a scheduled task's run (nobody may be watching) a notification says how it went.
     */
    private void runEnded(Sessions.Session ses) {
        Prefs.setBusy(app, ses.id, false);
        SpeechPause.runEnded();
        StepLog.runEnded(app, ses);
        if (!ses.parent.isEmpty()) { // a sub-agent has one run: its screen closes at once
            Background.close(app, ses.id);
        } else if (ses.background) {
            Background.rest(app, ses.id); // its apps aren't "on screen" between runs
            // and a minute later, unless the chat works again, its screen goes and its apps with it: an app left
            // there isn't on the user's screen either, and the chat kept showing it as if it still worked there
            final int run = ses.runId;
            main.postDelayed(() -> {
                if (!ses.running && ses.runId == run) {
                    Background.close(app, ses.id);
                    changed();
                }
            }, BACKGROUND_RELEASE_MS);
        }
        if (ses.schedule == null) ses.allowed.clear(); // a scheduled chat keeps them for its next scheduled run
        CodexLive.agentDone(ses, ses.outcome, !ses.lastReply.isEmpty());
        // a quiet scheduled run that found nothing new ends without a notification (a failed one always says so)
        boolean quiet = ses.quietRun && ses.quietSaid && ses.outcome == null;
        // nobody is looking: the phone is locked, or the chat worked in the background and isn't the one on screen.
        // Then its end is a notification too, as for a long task's or a scheduled one's
        boolean away = Triggers.locked(app) || (ses.background && !viewing.contains(ses));
        // (a helper's end is its parent's business: no notification)
        if ((ses.awake || ses.scheduledRun || away) && !quiet && ses.parent.isEmpty()) KeepAwake.notifyEnded(app, ses);
        ses.scheduledRun = false;
        ses.quietRun = false;
        ses.quietSaid = false;
    }

    private void add(Sessions.Session ses, Bubble.Kind kind, String text) {
        add(ses, kind, text, null);
    }

    private void add(Sessions.Session ses, Bubble.Kind kind, String text, List<String> files) {
        if (text == null) return;
        // the agent a chat was switched to has answered: the conversation is its own from here on
        if (kind == Bubble.Kind.BUDDY && ses.handover != null) ses.handover = null;
        if (kind == Bubble.Kind.BUDDY) ses.reseed = false; // (a fork's agent has the conversation now too)
        sessions.add(ses, kind, text, files);
        if (kind == Bubble.Kind.BUDDY && !viewing.contains(ses) && ses.parent.isEmpty()) ses.unread = true;
        // not during a live voice call: the voice tells the user itself (nor a helper's report, which is for its parent)
        if (kind == Bubble.Kind.BUDDY && !text.isEmpty() && Prefs.speakReplies(app) && !CodexLive.on() && ses.parent.isEmpty())
            Voice.speak(app, text);
        Sessions.Msg m = ses.messages.get(ses.messages.size() - 1);
        for (Listener l : new ArrayList<>(listeners)) l.onMessage(ses, m);
    }

    /** Files a chat's agent saved to Downloads (save_to_downloads), as attachments: its next reply shows them. */
    private final java.util.Map<String, List<String>> savedFiles = new java.util.concurrent.ConcurrentHashMap<>();

    /** An agent saved a file to Downloads in this chat (any thread): it comes as a chip under the chat's next reply. */
    void fileSaved(String chat, String attachment) {
        savedFiles.computeIfAbsent(chat, k -> java.util.Collections.synchronizedList(new ArrayList<>())).add(attachment);
    }

    /** What a reply shows under it: the pictures it names, and the files saved to Downloads since the last reply. */
    private List<String> replyFiles(Sessions.Session ses, String text) {
        List<String> out = pictures(text);
        List<String> saved = savedFiles.remove(ses.id);
        if (saved == null) return out;
        java.util.Set<String> named = new java.util.HashSet<>(); // (the same file has a new id each copy: by its name)
        for (String id : out) named.add(stem(Attachments.name(id)));
        for (String id : saved) if (named.add(stem(Attachments.name(id)))) out.add(id);
        return out;
    }

    /** "chart.png" → "chart": a photo saved as a copy becomes .jpg, so names are compared without it. */
    private static String stem(String name) {
        return name.replaceAll("\\.[A-Za-z0-9]{1,5}$", "").toLowerCase(java.util.Locale.ROOT);
    }

    /** A picture's path in Buddy's Linux, as agents write it: /root/.buddy/work/chart.png, ~/out/photo.jpg. */
    private static final java.util.regex.Pattern PICTURE = java.util.regex.Pattern.compile(
            "(?<![\\w/.~-])(~|/(?:root|tmp|home|srv|opt|var|mnt|media))(/[^\\s`'\"()\\[\\]<>|*]+?\\.(?i:png|jpe?g|webp|gif))(?![\\w/])");

    /**
     * Pictures a reply names in Buddy's Linux (a chart the agent drew, a photo it edited), copied to the attachments so
     * the chat shows them under it; at most 4. Only files inside Buddy's Linux, never through a link out of it.
     */
    private List<String> pictures(String text) {
        List<String> out = new ArrayList<>();
        java.util.regex.Matcher m = PICTURE.matcher(text);
        if (!m.find() || !Linux.installed(app)) return out;
        java.util.Set<String> seen = new java.util.HashSet<>();
        do {
            String path = ("~".equals(m.group(1)) ? "/root" : m.group(1)) + m.group(2);
            if (!seen.add(path)) continue;
            String file = picture(path);
            if (file != null) out.add(file);
        } while (m.find());
        return out;
    }

    /** A picture inside Buddy's Linux, never through a link out of it, copied to the attachments; null if it can't be. */
    private String picture(String path) {
        try {
            if (!Linux.installed(app)) return null;
            java.io.File root = Linux.root(app).getCanonicalFile(), f = new java.io.File(root, path).getCanonicalFile();
            if (!f.getPath().startsWith(root.getPath() + "/") || !f.isFile() || f.length() > Attachments.MAX_BYTES) return null;
            android.graphics.BitmapFactory.Options b = new android.graphics.BitmapFactory.Options();
            b.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeFile(f.getPath(), b);
            return b.outWidth > 0 ? Attachments.importFile(app, f) : null;
        } catch (Exception ignored) { // not there (yet), or unreadable: the path stays in the text
            return null;
        }
    }

    private boolean wasRunning;
    private long lastRunEndedAt;

    /**
     * An agent's own bookkeeping between phone steps (loading a tool, its to-do list): not work elsewhere, so it doesn't
     * end "Using your phone". Every other step that isn't a phone tool does (a Linux command, a file, another server).
     */
    private static final java.util.Set<String> BOOKKEEPING = new java.util.HashSet<>(java.util.Arrays.asList(
            "ToolSearch", "TodoWrite", "TodoRead", "EnterPlanMode", "ExitPlanMode", "update_plan", "todowrite", "todoread",
            "Task", "Agent", "TaskOutput")); // (a sub-agent may be the one using the phone next)

    /**
     * Every running chat's agent went on to a Linux or file step after since (elapsedRealtime): none of them is using
     * the phone now. False with none running.
     */
    boolean allMovedOn(long since) {
        boolean any = false;
        for (Sessions.Session s : new ArrayList<>(sessions.all)) {
            // only the chats that used the real screen in this run (a background chat works on a screen of its own)
            if (!s.running || s.phoneToolAt == 0 || Background.on(app, s.id)) continue;
            if (s.otherToolAt <= since || s.otherToolAt <= s.phoneToolAt) return false;
            any = true;
        }
        return any;
    }

    /** When the last running chat finished (elapsedRealtime). */
    long lastRunEndedAt() {
        return lastRunEndedAt;
    }

    void changed() {
        CodexLive.refresh(); // a live call's status follows the chat (Working on it…)
        boolean now = runningCount() > 0;
        if (wasRunning && !now) {
            lastRunEndedAt = SystemClock.elapsedRealtime();
            BuddyService svc = BuddyService.get();
            if (svc != null) svc.onRunsEnded();
        }
        wasRunning = now;
        BuddyService svc = BuddyService.get();
        if (svc != null) svc.keepAwake().update(); // a long-task chat started, ended or changed its switch
        for (Listener l : new ArrayList<>(listeners)) l.onChatsChanged();
    }

    static String describeTool(String name, JSONObject in) {
        return describeTool(name, in, false);
    }

    /**
     * full: for the chat, where a step is shown whole: what the agent types, sends, asks or gets approved, and the
     * commands and scripts it runs (the user reads there what was done in their name). The short form is for the
     * status line and the steps list.
     */
    static String describeTool(String name, JSONObject in, boolean full) {
        if (in == null) in = new JSONObject();
        String n = name.startsWith(TOOL_PREFIX) ? name.substring(TOOL_PREFIX.length()) : name;
        switch (n) {
            case "read_screen": return BuddyApp.str(R.string.hub_tool_read_screen);
            case "screenshot": return BuddyApp.str(R.string.hub_tool_screenshot);
            case "tap": return in.has("element") ? BuddyApp.str(R.string.hub_tool_tap_element, in.optInt("element"))
                    : BuddyApp.str(R.string.hub_tool_tap_point, in.optInt("x"), in.optInt("y"));
            case "long_press": return BuddyApp.str(R.string.hub_tool_long_press);
            case "type_text": return BuddyApp.str(R.string.hub_tool_type, said(full, in.optString("text"), 40));
            case "scroll": {
                String d = in.optString("direction"); // the tool's enum values get whole translated lines
                switch (d) {
                    case "up": return BuddyApp.str(R.string.hub_tool_scroll_up);
                    case "down": return BuddyApp.str(R.string.hub_tool_scroll_down);
                    case "left": return BuddyApp.str(R.string.hub_tool_scroll_left);
                    case "right": return BuddyApp.str(R.string.hub_tool_scroll_right);
                    default: return BuddyApp.str(R.string.hub_tool_scroll, d);
                }
            }
            case "swipe": return BuddyApp.str(R.string.hub_tool_swipe);
            case "press": {
                String b = in.optString("button"); // as for scroll
                switch (b) {
                    case "back": return BuddyApp.str(R.string.hub_tool_press_back);
                    case "home": return BuddyApp.str(R.string.hub_tool_press_home);
                    case "recents": return BuddyApp.str(R.string.hub_tool_press_recents);
                    case "notifications": return BuddyApp.str(R.string.hub_tool_press_notifications);
                    case "quick_settings": return BuddyApp.str(R.string.hub_tool_press_quick_settings);
                    default: return BuddyApp.str(R.string.hub_tool_press, b);
                }
            }
            case "open_app": return BuddyApp.str(R.string.hub_tool_open_app, in.optString("app"));
            case "list_apps": return BuddyApp.str(R.string.hub_tool_list_apps);
            case "open_url": return BuddyApp.str(R.string.hub_tool_open_url, said(full, in.optString("url"), 40));
            case "phone_shell": return BuddyApp.str(R.string.hub_tool_phone_shell, said(full, in.optString("command"), 60));
            case "wait":
            case "ToolSearch":
            case "TodoWrite":
                return null;
            case "confirm": {
                // OpenCode reports a step only once it's done, so its approval step carries the answer (_answer)
                String ans = in.optString("_answer", "");
                if (ans.startsWith("APPROVED")) return BuddyApp.str(R.string.hub_tool_approved, said(full, in.optString("summary"), 80));
                if (ans.startsWith("CHANGES")) {
                    int a = ans.indexOf('“'), b = ans.lastIndexOf('”');
                    return a >= 0 && b > a ? BuddyApp.str(R.string.hub_tool_change_requested_text, said(full, ans.substring(a + 1, b), 80))
                            : BuddyApp.str(R.string.hub_tool_change_requested);
                }
                if (ans.startsWith("DENIED")) return BuddyApp.str(R.string.hub_tool_denied);
                return BuddyApp.str(R.string.hub_tool_asking_approval);
            }
            case "ask": {
                String ans = in.optString("_answer", ""); // OpenCode, as for confirm
                if (ans.startsWith("ANSWER")) {
                    int a = ans.indexOf('“'), b = ans.lastIndexOf('”');
                    return a >= 0 && b > a ? BuddyApp.str(R.string.hub_tool_answered_text, said(full, ans.substring(a + 1, b), 80))
                            : BuddyApp.str(R.string.hub_tool_answered);
                }
                if (ans.startsWith("NO ANSWER")) return BuddyApp.str(R.string.hub_tool_question_skipped);
                return BuddyApp.str(R.string.hub_tool_asking, said(full, in.optString("question"), 80));
            }
            case "point_out": {
                JSONArray marks = in.optJSONArray("marks");
                StringBuilder labels = new StringBuilder();
                for (int i = 0; marks != null && i < marks.length(); i++) {
                    JSONObject m = marks.optJSONObject(i);
                    String l = m == null ? "" : m.optString("label", "").trim();
                    if (!l.isEmpty()) labels.append(labels.length() > 0 ? ", " : "").append(l);
                }
                return labels.length() > 0 ? BuddyApp.str(R.string.hub_tool_point_out, said(full, labels.toString(), 60))
                        : BuddyApp.str(R.string.hub_tool_point_out_any);
            }
            case "update_buddy": return BuddyApp.str(in.optBoolean("install") ? R.string.hub_tool_update_download : R.string.hub_tool_update_check);
            case "notify": return in.optString("text");
            case "delegate": {
                StringBuilder names = new StringBuilder();
                org.json.JSONArray ts = in.optJSONArray("tasks");
                for (int i = 0; ts != null && i < ts.length(); i++) {
                    JSONObject t = ts.optJSONObject(i);
                    String nm = t == null ? ts.optString(i) : t.optString("name", t.optString("task"));
                    names.append(names.length() == 0 ? "" : ", ").append(clip(nm, 28));
                }
                return BuddyApp.str(R.string.hub_tool_delegate, names.toString());
            }
            case "subagents": return BuddyApp.str(R.string.hub_tool_helpers);
            case "schedule_message": return BuddyApp.str(R.string.hub_tool_schedule_message, said(full, in.optString("message"), 40));
            case "add_notification_rule": return BuddyApp.str(R.string.hub_tool_add_rule, in.optString("app"));
            case "list_scheduled": return BuddyApp.str(R.string.hub_tool_list_scheduled);
            case "cancel_scheduled": return BuddyApp.str(R.string.hub_tool_cancel_scheduled);
            case "reply_notification": return BuddyApp.str(R.string.hub_tool_reply_notification, said(full, in.optString("text"), 40));
            case "browser_open": return BuddyApp.str(R.string.hub_tool_browser_open, said(full, in.optString("url"), 40));
            case "browser_read": return BuddyApp.str(R.string.hub_tool_browser_read);
            case "browser_click": return BuddyApp.str(R.string.hub_tool_browser_click, in.optInt("ref"));
            case "browser_type": return BuddyApp.str(R.string.hub_tool_browser_type, said(full, in.optString("text"), 40));
            case "browser_scroll": return BuddyApp.str(R.string.hub_tool_browser_scroll);
            case "browser_screenshot": return BuddyApp.str(R.string.hub_tool_browser_screenshot);
            case "browser_back": return BuddyApp.str(R.string.hub_tool_browser_back);
            case "browser_tabs": return BuddyApp.str(R.string.hub_tool_browser_tabs);
            case "clipboard_read": return BuddyApp.str(R.string.hub_tool_clipboard_read);
            case "clipboard_write": return BuddyApp.str(R.string.hub_tool_clipboard_write);
            case "set_alarm": return BuddyApp.str(R.string.hub_tool_set_alarm, in.optString("time"));
            case "set_timer": return BuddyApp.str(R.string.hub_tool_set_timer);
            case "share": return BuddyApp.str(R.string.hub_tool_share);
            case "save_to_downloads": return BuddyApp.str(R.string.hub_tool_save_downloads, said(full, in.optString("path").replaceAll(".*/", ""), 40));
            case "device_status": return BuddyApp.str(R.string.hub_tool_device_status);
            case "take_photo": return BuddyApp.str(R.string.hub_tool_take_photo);
            case "location": return BuddyApp.str(R.string.hub_tool_location);
            case "notifications": return BuddyApp.str(R.string.hub_tool_notifications);
            case "media": return BuddyApp.str(R.string.hub_tool_media);
            case "calendar_events": return BuddyApp.str(R.string.hub_tool_calendar_events);
            case "call_log": return BuddyApp.str(R.string.hub_tool_call_log);
            case "sms_list": return BuddyApp.str(R.string.hub_tool_sms_list);
            case "add_calendar_event": return BuddyApp.str(R.string.hub_tool_add_event, said(full, in.optString("title"), 40));
            case "wait_for": return BuddyApp.str(R.string.hub_tool_wait_for);
            case "do_steps": {
                JSONArray st = in.optJSONArray("steps");
                return BuddyApp.str(R.string.hub_tool_do_steps, st == null ? 0 : st.length());
            }
            case "collect": return BuddyApp.str(R.string.hub_tool_collect);
            case "find": // (its text can come a moment after the call itself: no “” meanwhile)
                return in.optString("text").trim().isEmpty() ? BuddyApp.str(R.string.hub_tool_read_screen)
                        : BuddyApp.str(R.string.hub_tool_find, said(full, in.optString("text"), 40));
            case "WebSearch": return BuddyApp.str(R.string.hub_tool_web_search, said(full, in.optString("query"), 40));
            case "WebFetch": return BuddyApp.str(R.string.hub_tool_web_fetch, said(full, in.optString("url"), 40));
            case "Bash": return in.has("command") ? BuddyApp.str(R.string.hub_tool_command, said(full, in.optString("command"), 40))
                    : BuddyApp.str(R.string.hub_tool_command_any);
            case "Edit":
            case "Write": return in.has("file_path") ? BuddyApp.str(R.string.hub_tool_edit, said(full, in.optString("file_path").replaceAll(".*/", ""), 40))
                    : BuddyApp.str(R.string.hub_tool_edit_any);
            case "Read": return BuddyApp.str(R.string.hub_tool_read_file, said(full, in.optString("file_path").replaceAll(".*/", ""), 40));
            default: {
                // a tool of one of the user's own MCP servers: which server, which tool, and what was sent to it
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("^mcp__([A-Za-z0-9_-]+?)__(.+)$").matcher(n);
                if (!m.find()) return BuddyApp.str(R.string.hub_tool_other, n);
                return BuddyApp.str(R.string.hub_tool_other, m.group(1) + " · " + m.group(2))
                        + (in.length() == 0 ? "" : " " + said(full, in.toString(), 60));
            }
        }
    }

    /** A step's text (a message, a command, an address): whole for the chat, else clipped to n. */
    private static String said(boolean full, String s, int n) {
        return full ? s.trim() : clip(s, n);
    }

    static String clip(String s, int n) {
        s = s.replace('\n', ' ');
        return s.length() > n ? s.substring(0, n - 1) + "…" : s;
    }
}
