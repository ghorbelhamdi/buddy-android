package app.buddy.assistant;

import android.content.Context;
import android.content.SharedPreferences;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Other devices: the user's chats and approval cards in a browser on their computer or another phone, straight from
 * this phone over Tailscale (no server, no account). Off until the user turns it on (Settings → Other devices).
 *
 * A device pairs from the page: the user approves it on a card on the phone, which shows the code the page shows.
 * It then gets a key of its own; Buddy keeps only that key's hash (so a copy of Buddy's files doesn't let anyone in),
 * and the user removes the device in Settings. Connections from the phone itself are refused: an agent working in
 * Buddy's Linux, or the background browser, shares the phone's network and must never answer its own cards.
 *
 * The page (assets/web/index.html) asks for /web/* on the Tailscale address; McpServer hands those here.
 */
final class WebDevices {
    private WebDevices() {
    }

    private static SharedPreferences sp(Context c) {
        // not one of Backup's: a backup restored elsewhere mustn't let these devices in there
        return c.getApplicationContext().getSharedPreferences("web_devices", Context.MODE_PRIVATE);
    }

    static boolean on(Context c) {
        return sp(c).getBoolean("on", false);
    }

    static void setOn(Context c, boolean on) {
        sp(c).edit().putBoolean("on", on).apply();
        changed();
    }

    /** A paired device: its name, its key's hash, when it was added and last seen. */
    static final class Device {
        final String id, name, hash;
        final long added;
        long seen;

        Device(String id, String name, String hash, long added, long seen) {
            this.id = id;
            this.name = name;
            this.hash = hash;
            this.added = added;
            this.seen = seen;
        }
    }

    static synchronized List<Device> devices(Context c) {
        List<Device> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(sp(c).getString("devices", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                out.add(new Device(o.getString("id"), o.optString("name"), o.getString("hash"), o.optLong("added"), o.optLong("seen")));
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private static synchronized void save(Context c, List<Device> ds) {
        JSONArray a = new JSONArray();
        try {
            for (Device d : ds) a.put(new JSONObject().put("id", d.id).put("name", d.name).put("hash", d.hash)
                    .put("added", d.added).put("seen", d.seen));
        } catch (Exception ignored) {
        }
        sp(c).edit().putString("devices", a.toString()).apply();
    }

    static void remove(Context c, String id) {
        synchronized (WebDevices.class) { // one read-change-write at a time: a removed device mustn't come back
            List<Device> ds = devices(c);
            ds.removeIf(d -> d.id.equals(id));
            save(c, ds);
        }
        changed();
    }

    private static String hash(String token) {
        try {
            byte[] h = java.security.MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8));
            StringBuilder b = new StringBuilder();
            for (byte x : h) b.append(String.format("%02x", x));
            return b.toString();
        } catch (Exception e) {
            return "";
        }
    }

    /** The paired device this key belongs to, or null. */
    private static Device device(Context c, String token) {
        if (token == null || !token.startsWith("wd-")) return null;
        byte[] shown = hash(token).getBytes(StandardCharsets.UTF_8);
        Device found = null;
        for (Device d : devices(c)) if (java.security.MessageDigest.isEqual(d.hash.getBytes(StandardCharsets.UTF_8), shown)) found = d;
        return found;
    }

    private static void seen(Context c, Device d) {
        if (System.currentTimeMillis() - d.seen < 60_000) return;
        synchronized (WebDevices.class) {
            List<Device> ds = devices(c);
            for (Device o : ds) if (o.id.equals(d.id)) o.seen = System.currentTimeMillis();
            save(c, ds);
        }
    }

    /**
     * Cards only the phone answers: letting a device or a computer in. A paired browser never sees them, nor answers
     * them (it could otherwise let in more devices, or a computer with the phone tools).
     */
    private static final java.util.Map<String, Integer> phoneOnly = new java.util.concurrent.ConcurrentHashMap<>();
    /** This thread's next card is the phone's only (askOnPhone); the card marks itself with the text it shows. */
    private static final ThreadLocal<Boolean> NEXT_PHONE_ONLY = new ThreadLocal<>();

    /**
     * BuddyService.card, as a card goes up: whether it's the phone's only. It is marked by the text it really shows
     * (a reviewer's reason may have been added to what was asked), and counted: two cards can say the same.
     */
    static boolean cardShown(String shownText) {
        if (!Boolean.TRUE.equals(NEXT_PHONE_ONLY.get())) return false;
        phoneOnly.merge(shownText, 1, Integer::sum);
        changed();
        return true;
    }

    static void cardGone(String shownText) {
        phoneOnly.computeIfPresent(shownText, (k, n) -> n <= 1 ? null : n - 1);
        changed();
    }

    /** The card waiting in the bubble is not for a paired browser: the phone's only, or an incognito chat's. */
    private static boolean hidden(Bubble b) {
        String text = b.pendingConfirmText();
        if (text == null) return false;
        Sessions.Session cs = b.pendingChat();
        return phoneOnly.containsKey(text) || (cs != null && cs.incognito);
    }

    static String askOnPhone(BuddyService svc, String text) throws InterruptedException {
        return askOnPhone(svc, text, false);
    }

    static String askOnPhone(BuddyService svc, String text, boolean canAuto) throws InterruptedException {
        NEXT_PHONE_ONLY.set(true);
        try {
            return svc.askConfirm(text, canAuto);
        } finally {
            NEXT_PHONE_ONLY.remove();
        }
    }

    /** Money: paying, buying, ordering, a transfer (the user chose: those are approved on the phone only). */
    private static final java.util.regex.Pattern MONEY = java.util.regex.Pattern.compile("(?i)\\b(pay|payment|paypal|buy|"
            + "purchase|order|checkout|check out|transfer|send money|subscribe|subscription|donate|top up|withdraw|"
            + "bezahlen|kaufen|bestellen|zahlung|überweisung|payer|acheter|commander|pagar|comprar|pedido|pagare|acquista)\\b");

    static boolean money(String text) {
        return text != null && MONEY.matcher(text).find();
    }

    /**
     * A computer added from the page: agents could then run commands on it, so the phone asks first (a card answered
     * there only, as pairing), and SSH is tried once it's allowed. Its own words when it can't get in.
     */
    private static final java.util.concurrent.atomic.AtomicBoolean adding = new java.util.concurrent.atomic.AtomicBoolean();

    private static JSONObject addComputer(Context c, JSONObject in) throws Exception {
        String n = in.optString("name").trim(), h = in.optString("host").trim(), u = in.optString("user").trim();
        int pt = in.optInt("port", 22);
        if (n.isEmpty() || n.length() > 40 || !Computers.validHost(h) || !Computers.validUser(u) || pt < 1 || pt > 65535)
            return new JSONObject().put("error", "Fill in a name, the computer's address and your user name there.");
        if (!Linux.installed(c)) return new JSONObject().put("error", "Adding a computer needs Buddy's Linux: install it on the phone first.");
        BuddyService svc = BuddyService.get();
        if (svc == null) return new JSONObject().put("error", "Buddy's accessibility isn't on on the phone.");
        if (!adding.compareAndSet(false, true)) return new JSONObject().put("error", "Another computer is being added: answer that card first.");
        try {
            Computers.Computer m = new Computers.Computer(Computers.newId(), n, h, u, pt, "each");
            String answer = askOnPhone(svc, c.getString(R.string.web_card_computer, n, m.where()));
            if (!answer.startsWith("APPROVED")) return new JSONObject().put("error", "Not added: it wasn't allowed on the phone.");
            String why = Computers.check(c, m);
            if (why != null) return new JSONObject().put("error", "Buddy couldn't get in: " + why);
            Computers.add(c, m);
            changed();
            return new JSONObject().put("ok", true);
        } finally {
            adding.set(false);
        }
    }

    /** One pairing at a time. */
    private static final java.util.concurrent.atomic.AtomicBoolean pairing = new java.util.concurrent.atomic.AtomicBoolean();

    /** A device asks to pair: the card names it, its address and the code its page shows. The key, or null. */
    private static String pair(Context c, String name, String code, InetAddress from) throws InterruptedException {
        BuddyService svc = BuddyService.get();
        if (svc == null) return null;
        String n = name == null ? "" : name.replaceAll("[^\\p{L}\\p{N} ._-]", "").trim();
        if (n.length() > 40) n = n.substring(0, 40);
        if (n.isEmpty()) n = c.getString(R.string.web_device_unnamed);
        String shownCode = code == null ? "" : code.replaceAll("[^0-9]", "");
        if (shownCode.length() != 6) return null;
        if (!pairing.compareAndSet(false, true)) return null;
        try {
            String answer = askOnPhone(svc, c.getString(R.string.web_card_pair, n,
                    shownCode.substring(0, 3) + " " + shownCode.substring(3), from.getHostAddress()));
            if (!answer.startsWith("APPROVED")) return null;
            java.util.function.BooleanSupplier gone = McpServer.CALLER_GONE.get();
            if (gone != null && gone.getAsBoolean()) return null; // it stopped waiting: no key nobody receives
            byte[] b = new byte[32];
            new java.security.SecureRandom().nextBytes(b);
            StringBuilder t = new StringBuilder("wd-");
            for (byte x : b) t.append(String.format("%02x", x));
            synchronized (WebDevices.class) {
                // two devices can have the same default name ("Chrome on Windows"): each keeps its own key, the
                // second gets a number; an old one is removed by the user, in Settings
                List<Device> ds = devices(c);
                String unique = n;
                for (int k = 2; ; k++) {
                    final String u = unique;
                    if (ds.stream().noneMatch(o -> o.name.equals(u))) break;
                    unique = n + " (" + k + ")";
                }
                ds.add(new Device(Long.toString(System.currentTimeMillis(), 36), unique, hash(t.toString()), System.currentTimeMillis(),
                        System.currentTimeMillis()));
                save(c, ds);
            }
            changed();
            return t.toString();
        } finally {
            pairing.set(false);
        }
    }

    // ------------------------------------------------------------ changes, for the page's long poll

    private static final Object lock = new Object();
    private static long version = 1;
    private static volatile boolean listening;

    static void changed() {
        synchronized (lock) {
            version++;
            lock.notifyAll();
        }
    }

    /** Follows the chats from now on (once). */
    private static void listen(Context c) {
        if (listening) return;
        onMain(() -> {
            if (listening) return null; // (two requests at once: one listener)
            listening = true; // (here, on the main thread: if this never ran, the next request tries again)
            ChatHub.get(c).addListener(new ChatHub.Listener() {
                @Override
                public void onChatsChanged() {
                    changed();
                }

                @Override
                public void onMessage(Sessions.Session ses, Sessions.Msg msg) {
                    changed();
                }
            });
            return null;
        });
    }

    static <T> T onMain(Callable<T> work) {
        BuddyService svc = BuddyService.get();
        AtomicReference<T> out = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicBoolean gaveUp = new java.util.concurrent.atomic.AtomicBoolean(),
                began = new java.util.concurrent.atomic.AtomicBoolean();
        Runnable r = () -> {
            try {
                began.set(true);
                // (told "Buddy didn't answer" already: a message sent now would come as a second one)
                if (!gaveUp.get()) out.set(work.call());
            } catch (Exception ignored) {
            } finally {
                done.countDown();
            }
        };
        if (svc != null) svc.runOnMain(r);
        else new android.os.Handler(android.os.Looper.getMainLooper()).post(r);
        try {
            if (!done.await(10, TimeUnit.SECONDS)) {
                gaveUp.set(true);
                if (began.get()) done.await(20, TimeUnit.SECONDS); // already at it: its answer is the true one
            }
        } catch (InterruptedException ignored) {
        }
        return out.get();
    }

    // ------------------------------------------------------------ requests

    /** A reply: status and JSON (or the page). */
    static final class Reply {
        final int code;
        final String type, body;
        /** Or bytes (a picture), or a file to stream (a download: its length and the name it's saved as). */
        byte[] data;
        java.io.InputStream stream;
        long length = -1;
        String filename;

        Reply(int code, String type, String body) {
            this.code = code;
            this.type = type;
            this.body = body;
        }
    }

    private static Reply bytes(String type, byte[] b) {
        Reply r = new Reply(200, type, null);
        r.data = b;
        return r;
    }

    private static Reply json(Object o) {
        return new Reply(200, "application/json", o.toString());
    }

    private static Reply error(int code, String why) {
        try {
            return new Reply(code, "application/json", new JSONObject().put("error", why).toString());
        } catch (Exception e) {
            return new Reply(code, "application/json", "{}");
        }
    }

    /** Is this connection from the phone itself (an agent in Buddy's Linux, the background browser)? */
    static boolean fromPhone(InetAddress from, InetAddress local) {
        return from == null || from.isLoopbackAddress() || from.isAnyLocalAddress() || from.equals(local) || own(from);
    }

    private static boolean own(InetAddress a) {
        try {
            for (java.net.NetworkInterface ni : java.util.Collections.list(java.net.NetworkInterface.getNetworkInterfaces())) {
                for (InetAddress x : java.util.Collections.list(ni.getInetAddresses())) if (x.equals(a)) return true;
            }
        } catch (Exception ignored) {
        }
        return false;
    }

    /**
     * Serves "/" (the page) and "/web/..." for a connection to the Tailscale address. auth: the Authorization header;
     * gone: whether the device stopped waiting.
     */
    static Reply handle(Context c, String method, String path, String query, String auth, byte[] body, InetAddress from,
                        String host, Runnable authed, java.io.InputStream raw, long rawLength) throws Exception {
        if (!on(c)) return error(404, "off");
        // only a device on the tailnet, asking for the phone by its Tailscale address: a web page open on that device
        // can't reach Buddy through a name of its own (DNS rebinding)
        InetAddress at = Tailnet.address(c);
        if (from == null || !Tailnet.inRange(from) || at == null || host == null
                || !host.equals(at.getHostAddress() + ":" + McpServer.PORT)) return error(403, "not here");
        if ("/".equals(path) && "GET".equals(method)) return new Reply(200, "text/html", page(c));
        if ("/web/pair".equals(path) && "POST".equals(method)) {
            JSONObject in = new JSONObject(body.length == 0 ? "{}" : new String(body, StandardCharsets.UTF_8));
            String key = pair(c, in.optString("name"), in.optString("code"), from);
            return key == null ? error(403, "not paired") : json(new JSONObject().put("key", key));
        }
        // a download by a plain link: its one-use ticket stands for the key a link can't carry (WebPlaces.ticket)
        if ("/web/dl".equals(path) && "GET".equals(method)) {
            Object[] f = WebPlaces.redeem(c, param(query, "t"));
            if (f == null) return error(404, "that link was used already, or is too old: click the file again");
            Reply r = new Reply(200, "application/octet-stream", null);
            r.stream = (java.io.InputStream) f[0];
            r.length = (Long) f[1];
            r.filename = (String) f[2];
            return r;
        }
        String key = auth != null && auth.startsWith("Bearer ") ? auth.substring(7) : null;
        Device d = device(c, key);
        if (d == null) return error(401, "unauthorized");
        authed.run();
        seen(c, d);
        listen(c);
        switch (path) {
            case "/web/state": {
                JSONObject st = state(c, param(query, "v"));
                // removed, or switched off, while it waited: nothing more for it
                if (!on(c) || device(c, key) == null) return error(401, "unauthorized");
                return json(st);
            }
            case "/web/chat": return json(chat(c, param(query, "id")));
            case "/web/send": {
                JSONObject in = new JSONObject(new String(body, StandardCharsets.UTF_8));
                return json(send(c, in.isNull("chat") ? null : in.optString("chat"), in.optString("text"), in)); // (JSON null: a new chat)
            }
            case "/web/answer": {
                JSONObject in = new JSONObject(new String(body, StandardCharsets.UTF_8));
                return json(answer(in));
            }
            case "/web/options": return json(options(c, param(query, "id")));
            case "/web/places": return json(WebPlaces.places(c, shownChat(c, param(query, "chat"))));
            case "/web/devices": { // the paired devices (this one marked) and the computers
                JSONArray ds = new JSONArray(), cs = new JSONArray();
                for (Device x : devices(c)) ds.put(new JSONObject().put("id", x.id).put("name", x.name).put("seen", Math.max(x.seen, x.added))
                        .put("me", x.id.equals(d.id)));
                for (Computers.Computer m : Computers.all(c)) cs.put(new JSONObject().put("id", m.id).put("name", m.name).put("where", m.where()));
                return json(new JSONObject().put("devices", ds).put("computers", cs).put("linux", Linux.installed(c)));
            }
            case "/web/device-remove": { // taking access away needs no card (this device too: then it's out)
                remove(c, new JSONObject(new String(body, StandardCharsets.UTF_8)).optString("id"));
                return json(new JSONObject());
            }
            case "/web/computer-remove": {
                Computers.remove(c, new JSONObject(new String(body, StandardCharsets.UTF_8)).optString("id"));
                return json(new JSONObject());
            }
            case "/web/computer-key": { // Buddy's public key for computers (made the first time): to paste on the computer
                if (!Linux.installed(c)) return json(new JSONObject().put("error", "Adding a computer needs Buddy's Linux: install it on the phone first."));
                try {
                    return json(new JSONObject().put("key", Computers.publicKey(c)));
                } catch (Exception e) {
                    return json(new JSONObject().put("error", String.valueOf(e.getMessage())));
                }
            }
            case "/web/computer-add": return json(addComputer(c, new JSONObject(new String(body, StandardCharsets.UTF_8))));
            case "/web/files": {
                Sessions.Session s = shownChat(c, param(query, "chat"));
                try {
                    return json(WebPlaces.list(c, s == null ? null : s.id, param(query, "path"), "1".equals(param(query, "hidden"))));
                } catch (java.io.IOException e) {
                    return json(new JSONObject().put("error", FilePlaces.why(e)));
                }
            }
            case "/web/fs": {
                JSONObject in = new JSONObject(new String(body, StandardCharsets.UTF_8));
                Sessions.Session s = shownChat(c, in.optString("chat", null));
                String why = WebPlaces.change(c, s == null ? null : s.id, in);
                return json(why == null ? new JSONObject() : new JSONObject().put("error", why));
            }
            case "/web/upload": {
                java.util.Map<String, String> q = WebPlaces.query(query);
                Sessions.Session s = shownChat(c, q.get("chat"));
                if (raw == null) return json(new JSONObject().put("error", "nothing came"));
                try {
                    return json(WebPlaces.upload(c, q, s, raw, rawLength));
                } catch (java.io.IOException e) {
                    return json(new JSONObject().put("error", FilePlaces.why(e)));
                }
            }
            case "/web/ticket": {
                JSONObject in = new JSONObject(new String(body, StandardCharsets.UTF_8));
                Sessions.Session s = shownChat(c, in.optString("chat", null));
                String t;
                try {
                    t = WebPlaces.ticket(c, s, in);
                } catch (java.io.IOException e) {
                    t = null;
                }
                return json(t == null ? new JSONObject().put("error", "that file isn't one this page can have") : new JSONObject().put("t", t));
            }
            case "/web/pic": { // a picture attached in a chat, shown in it
                byte[] b = WebPlaces.picture(c, shownChat(c, param(query, "chat")), param(query, "id"));
                return b == null ? error(404, "no such picture") : bytes("image/jpeg", b);
            }
            case "/web/plans": return json(WebPlaces.plans(c));
            case "/web/schedule": {
                String[] next = new String[1];
                String why = WebPlaces.setPlan(c, new JSONObject(new String(body, StandardCharsets.UTF_8)), next);
                return json(why == null ? new JSONObject().put("next", next[0]) : new JSONObject().put("error", why));
            }
            case "/web/plan": {
                String why = WebPlaces.plan(c, new JSONObject(new String(body, StandardCharsets.UTF_8)));
                return json(why == null ? new JSONObject() : new JSONObject().put("error", why));
            }
            case "/web/routine": {
                JSONObject in = new JSONObject(new String(body, StandardCharsets.UTF_8));
                String why = "delete".equals(in.optString("op")) ? null : WebPlaces.runRoutine(c, in);
                if ("delete".equals(in.optString("op"))) Routines.delete(c, in.optString("name"));
                return json(why == null ? new JSONObject() : new JSONObject().put("error", why));
            }
            case "/web/screens": return json(Prefs.webScreens(c) ? WebPlaces.screens(c)
                    : new JSONObject().put("off", true).put("phone", false).put("screens", new JSONArray()));
            case "/web/close-screen": { // a chat's hidden screen ends (not while its agent works on it)
                if (!Prefs.webScreens(c)) return json(new JSONObject().put("error", "Screens are off for other devices (Settings → Other devices on the phone)."));
                JSONObject in = new JSONObject(new String(body, StandardCharsets.UTF_8));
                String why = onMain(() -> {
                    Sessions.Session s = ChatHub.get(c).sessions.find(in.optString("chat"));
                    if (s == null || s.incognito) return "no such screen";
                    if (s.running) return "its agent is working on it: stop the chat first";
                    Background.close(c, s.id);
                    return "";
                });
                return json(why == null || !why.isEmpty() ? new JSONObject().put("error", why == null ? "Buddy didn't answer" : why) : new JSONObject());
            }
            case "/web/shot": { // a picture of a screen, to look at (never to touch)
                String which = param(query, "id");
                int w = 900;
                try {
                    w = Math.max(200, Math.min(1600, Integer.parseInt(param(query, "w"))));
                } catch (Exception ignored) {
                }
                if (which == null) return error(404, "which screen?");
                if (!Prefs.webScreens(c)) return new Reply(423, "application/json",
                        new JSONObject().put("error", "Screens are off for other devices (Settings → Other devices on the phone).").toString());
                if (!"phone".equals(which) && onMain(() -> { // (a sub-agent's hidden screen too, not an incognito chat's)
                    Sessions.Session s = ChatHub.get(c).sessions.find(which);
                    return s != null && !s.incognito;
                }) != Boolean.TRUE) return error(404, "no such screen");
                String why = WebPlaces.hiddenWhy(c, which);
                if (why != null) return new Reply(423, "application/json", new JSONObject().put("error", why).toString());
                byte[] b = "phone".equals(which) ? WebPlaces.phone(w) : Background.frame(c, which, w, 72);
                return b == null ? new Reply(404, "application/json", new JSONObject().put("error", "nothing to show yet").toString())
                        : bytes("image/jpeg", b);
            }
            case "/web/new": {
                JSONObject f = fresh(c, param(query, "agent"));
                return json(f == null ? new JSONObject().put("error", "Buddy didn't answer") : f);
            }
            case "/web/set": return json(set(c, new JSONObject(new String(body, StandardCharsets.UTF_8))));
            case "/web/team": return json(team(c, new JSONObject(new String(body, StandardCharsets.UTF_8))));
            case "/web/team-preset": { // a structure's members to start from, on the agents signed in (as the phone's setup)
                Sessions.Session s = shownChat(c, param(query, "chat"));
                if (s == null) return json(new JSONObject().put("error", "no such chat"));
                return json(new JSONObject().put("team", Team.preset(c, String.valueOf(param(query, "structure")), s.backend).json())
                        .put("screens", Background.setUp(c)));
            }
            case "/web/edit": { // a message of the user's own, edited: asked again from there (the chat as it was is kept, archived)
                JSONObject in = new JSONObject(new String(body, StandardCharsets.UTF_8));
                String said = onMain(() -> {
                    ChatHub hub = ChatHub.get(c);
                    Sessions.Session s = hub.sessions.find(in.optString("chat"));
                    if (s == null || s.incognito || !s.parent.isEmpty()) return "no such chat";
                    String e = hub.edit(s, in.optInt("index", -1), in.optString("text"));
                    return e == null ? "" : e;
                });
                return json(said == null || !said.isEmpty() ? new JSONObject().put("error", said == null ? "Buddy didn't answer" : said) : new JSONObject());
            }
            case "/web/delete": { // the page asked "Delete this chat?" first; a working chat is stopped on the phone before
                JSONObject in = new JSONObject(new String(body, StandardCharsets.UTF_8));
                String said = onMain(() -> {
                    ChatHub hub = ChatHub.get(c);
                    Sessions.Session s = hub.sessions.find(in.optString("chat"));
                    if (s == null || s.incognito || !s.parent.isEmpty()) return "no such chat";
                    if (s.running) return "the chat is working: stop it first";
                    hub.delete(s);
                    return "";
                });
                return json(said == null || !said.isEmpty() ? new JSONObject().put("error", said == null ? "Buddy didn't answer" : said) : new JSONObject());
            }
            case "/web/fork": {
                JSONObject in = new JSONObject(new String(body, StandardCharsets.UTF_8));
                String made = onMain(() -> {
                    ChatHub hub = ChatHub.get(c);
                    Sessions.Session s = hub.sessions.find(in.optString("chat")), was = hub.sessions.current;
                    if (s == null || s.incognito || !s.parent.isEmpty()) return "";
                    Sessions.Session f = hub.fork(s, in.optInt("index", -1));
                    if (f != null && was != null) hub.sessions.select(was); // (the phone's bubble stays on its chat)
                    return f == null ? "" : f.id;
                });
                return json(made == null || made.isEmpty() ? new JSONObject().put("error", "that message isn't there any more")
                        : new JSONObject().put("chat", made));
            }
            case "/web/run": { // what the chat's agent runs now (the page's "Running now")
                Sessions.Session s = shownOrMember(c, param(query, "id"));
                return s == null ? json(new JSONObject().put("error", "no such chat")) : json(CommandSheet.snapshot(c, s));
            }
            case "/web/stop": { // Stop, as on the phone: the whole run, or with a pid one process under the agent
                JSONObject in = new JSONObject(new String(body, StandardCharsets.UTF_8));
                Sessions.Session s = shownOrMember(c, in.optString("chat"));
                if (s == null) return json(new JSONObject().put("error", "no such chat"));
                String said = "";
                if (in.has("pid")) said = CommandSheet.stop(c, s, in.optLong("pid"));
                else onMain(() -> {
                    ChatHub.get(c).stop(s);
                    return "";
                });
                return json(said.isEmpty() ? new JSONObject() : new JSONObject().put("error", said));
            }
            default: return error(404, "unknown");
        }
    }

    /**
     * What the page's pickers offer for a chat: its agent and the others signed in, the models and efforts that agent
     * has, its name and pin, Linux tools and background mode. Approvals, apps allowed without asking, the project
     * folder, schedules and deleting stay on the phone.
     */
    private static JSONObject options(Context c, String id) throws Exception {
        JSONObject out = onMain(() -> {
            Sessions.Session s = id == null ? null : ChatHub.get(c).sessions.find(id);
            if (s == null || s.incognito || !s.parent.isEmpty()) return null;
            JSONArray agents = new JSONArray(), models = new JSONArray(), efforts = new JSONArray();
            for (Account a : Account.ALL) {
                if (a.agent.equals(s.backend) || SetupState.signedIn(c, a.agent)) agents.put(new JSONObject().put("id", a.agent).put("name", a.name()));
            }
            for (String[] m : Models.options(s.backend, true)) {
                models.put(m[0] == null ? new JSONObject().put("heading", m[1]) : new JSONObject().put("id", m[0]).put("name", m[1]));
            }
            String[][] e = Models.efforts(s);
            if (e != null) for (String[] l : e) efforts.put(new JSONObject().put("id", l[0]).put("name", l[1]));
            return new JSONObject().put("agent", s.backend).put("agents", agents).put("model", s.model).put("models", models)
                    .put("effort", s.effort).put("efforts", efforts).put("title", s.label()).put("pinned", s.pinned).put("archived", s.archived)
                    .put("running", s.running).put("dev", s.dev).put("folder", s.folder)
                    .put("background", s.background).put("canBackground", Prefs.backgroundPaired(c)).put("linux", Linux.installed(c));
        });
        return out == null ? new JSONObject().put("error", "no such chat") : out;
    }

    /**
     * What the page's New chat screen offers: the agents signed in (and which one new chats use), the models of the
     * one picked, and how Linux tools and background mode start, as the phone's settings say.
     */
    private static JSONObject fresh(Context c, String picked) throws Exception {
        return onMain(() -> {
            String def = Prefs.backend(c);
            JSONArray agents = new JSONArray(), models = new JSONArray();
            String agent = null;
            for (Account a : Account.ALL) {
                if (!SetupState.signedIn(c, a.agent)) continue;
                agents.put(new JSONObject().put("id", a.agent).put("name", a.name()).put("def", a.agent.equals(def)));
                if (a.agent.equals(picked) || (agent == null && a.agent.equals(def))) agent = a.agent;
            }
            if (agent == null && agents.length() > 0) agent = agents.getJSONObject(0).getString("id");
            if (agent != null) for (String[] m : Models.options(agent, true)) {
                models.put(m[0] == null ? new JSONObject().put("heading", m[1]) : new JSONObject().put("id", m[0]).put("name", m[1]));
            }
            return new JSONObject().put("agents", agents).put("agent", agent == null ? "" : agent).put("models", models)
                    .put("dev", Prefs.devMode(c)).put("linux", Linux.installed(c))
                    .put("background", Prefs.backgroundPaired(c) && Prefs.backgroundDefault(c)).put("canBackground", Prefs.backgroundPaired(c));
        });
    }

    /** One change from the page's pickers: model, effort, agent, title, pinned, archived, Linux tools or background mode. */
    private static JSONObject set(Context c, JSONObject in) throws Exception {
        String err = onMain(() -> {
            ChatHub hub = ChatHub.get(c);
            Sessions.Session s = hub.sessions.find(in.optString("chat"));
            if (s == null || s.incognito || !s.parent.isEmpty()) return "no such chat";
            if (in.has("agent")) {
                String a = in.optString("agent");
                if (s.running) return "the chat is working: switch its agent when it's done";
                boolean known = false;
                for (Account acc : Account.ALL) known |= acc.agent.equals(a) && SetupState.signedIn(c, a);
                if (!known) return "that agent isn't signed in on the phone";
                hub.switchAgent(s, a);
            } else if (in.has("model")) {
                String m = in.optString("model");
                boolean offered = m.isEmpty();
                for (String[] o : Models.options(s.backend, true)) offered |= m.equals(o[0]);
                if (!offered) return "that model isn't one this chat's agent has";
                hub.setModel(s, m);
                if (!s.effort.isEmpty()) { // (the new model may not have the level the chat had)
                    boolean has = false;
                    String[][] e = Models.efforts(s);
                    if (e != null) for (String[] l : e) has |= s.effort.equals(l[0]);
                    if (!has) hub.setEffort(s, "");
                }
            } else if (in.has("effort")) {
                String e = in.optString("effort");
                boolean has = e.isEmpty();
                String[][] levels = Models.efforts(s);
                if (levels != null) for (String[] l : levels) has |= e.equals(l[0]);
                if (!has) return "this model doesn't have that effort level";
                hub.setEffort(s, e);
            } else if (in.has("title")) {
                hub.rename(s, ChatHub.clip(in.optString("title"), 120));
            } else if (in.has("pinned")) {
                hub.setPinned(s, in.optBoolean("pinned"));
            } else if (in.has("unread")) { // read on the page (or marked unread again), as opening it in the app does
                s.unread = in.optBoolean("unread");
                hub.sessions.save(s);
                hub.changed();
            } else if (in.has("dev")) { // (the next message has them, as on the phone)
                if (in.optBoolean("dev") && !Linux.installed(c)) return "Buddy's Linux isn't installed on the phone";
                hub.setDev(s, in.optBoolean("dev"));
            } else if (in.has("background")) {
                if (in.optBoolean("background") && !Prefs.backgroundPaired(c)) return "background mode isn't set up on the phone";
                hub.setBackground(s, in.optBoolean("background"));
            } else if (in.has("archived")) {
                if (s.running && in.optBoolean("archived")) return "the chat is working: archive it when it's done";
                hub.setArchived(s, in.optBoolean("archived"));
            } else return "nothing to change";
            return "";
        });
        return err == null || !err.isEmpty() ? new JSONObject().put("error", err == null ? "Buddy didn't answer" : err) : new JSONObject();
    }

    /** A team's member's chat: its team's chat (the leader's), while the member is in the team; else null. Main thread. */
    private static Sessions.Session memberParent(Context c, Sessions.Session s) {
        if (s.member.isEmpty()) return null;
        Sessions.Session p = ChatHub.get(c).sessions.find(s.parent);
        return p == null || p.incognito || p.team == null || p.team.byId(s.member) == null ? null : p;
    }

    /** As shownChat, or a team's member's chat (to look at, write to and stop, as on the phone). */
    private static Sessions.Session shownOrMember(Context c, String id) throws Exception {
        return onMain(() -> {
            Sessions.Session s = id == null ? null : ChatHub.get(c).sessions.find(id);
            if (s == null || s.incognito) return null;
            return s.parent.isEmpty() || memberParent(c, s) != null ? s : null;
        });
    }

    /** A chat the page may show (as its list: not incognito or a sub-agent's), or null. */
    private static Sessions.Session shownChat(Context c, String id) throws Exception {
        return onMain(() -> {
            Sessions.Session s = id == null ? null : ChatHub.get(c).sessions.find(id);
            return s == null || s.incognito || !s.parent.isEmpty() ? null : s;
        });
    }

    private static String param(String query, String name) {
        if (query == null) return null;
        for (String p : query.split("&")) {
            int e = p.indexOf('=');
            if (e > 0 && p.substring(0, e).equals(name)) {
                try {
                    return java.net.URLDecoder.decode(p.substring(e + 1), "UTF-8");
                } catch (Exception ignored) {
                }
            }
        }
        return null;
    }

    /** The chats and the card waiting, once something changed since version v (or after 25 s). */
    private static JSONObject state(Context c, String v) throws Exception {
        long since = 0;
        try {
            since = v == null ? 0 : Long.parseLong(v);
        } catch (NumberFormatException ignored) {
        }
        long now;
        synchronized (lock) {
            long end = System.currentTimeMillis() + 25_000;
            while (version == since && System.currentTimeMillis() < end) lock.wait(Math.max(1, end - System.currentTimeMillis()));
            now = version;
        }
        final long ver = now;
        JSONObject out = onMain(() -> {
            ChatHub hub = ChatHub.get(c);
            BuddyService svc = BuddyService.get();
            Bubble bubble = svc == null ? null : svc.bubble();
            JSONArray chats = new JSONArray();
            for (Sessions.Session s : new ArrayList<>(hub.sessions.all)) {
                if (!s.parent.isEmpty() || s.incognito || (s.messages.isEmpty() && !s.running)) continue;
                Sessions.Msg last = s.messages.isEmpty() ? null : s.messages.get(s.messages.size() - 1);
                chats.put(new JSONObject().put("id", s.id).put("title", s.label()).put("agent", Account.cliOf(s.backend))
                        .put("running", s.running).put("waiting", bubble != null && bubble.waitingIn(s))
                        .put("pinned", s.pinned).put("archived", s.archived).put("unread", s.unread).put("updated", s.updated)
                        .put("preview", last == null ? "" : ChatHub.clip(last.preview().replace('\n', ' '), 120)));
            }
            JSONObject o = new JSONObject().put("v", ver).put("chats", chats);
            if (bubble != null && hidden(bubble)) {
                o.put("phoneCard", true); // the page says one waits on the phone, without what it says
            } else if (bubble != null && bubble.pendingConfirmText() != null) {
                Sessions.Session cs = bubble.pendingChat();
                java.util.List<String> opts = bubble.pendingOptions();
                o.put("card", new JSONObject().put("chat", cs == null ? JSONObject.NULL : cs.id)
                        .put("text", bubble.pendingConfirmText())
                        .put("options", opts == null ? JSONObject.NULL : new JSONArray(opts)));
            }
            return o;
        });
        return out == null ? new JSONObject().put("v", ver).put("chats", new JSONArray()) : out;
    }

    private static final int MAX_MESSAGES = 300;

    private static JSONObject chat(Context c, String id) throws Exception {
        JSONObject out = onMain(() -> {
            Sessions.Session s = id == null ? null : ChatHub.get(c).sessions.find(id);
            // an incognito chat stays on the phone; a sub-agent's chat isn't on the page's list either (a team's
            // member's opens from its team's chat, as on the phone)
            if (s == null || s.incognito || (!s.parent.isEmpty() && memberParent(c, s) == null)) return null;
            Sessions.Session lead = s.parent.isEmpty() ? null : memberParent(c, s);
            JSONArray msgs = new JSONArray();
            List<Sessions.Msg> all = s.messages;
            int first = Math.max(0, all.size() - MAX_MESSAGES);
            for (int i = first; i < all.size(); i++) {
                Sessions.Msg m = all.get(i);
                // (the text alone: the page shows the files as files, not as the "📎 name" line the label adds)
                JSONObject o = new JSONObject().put("k", m.kind.name().toLowerCase(java.util.Locale.ROOT))
                        .put("t", m.files.isEmpty() && m.linux.isEmpty() ? m.label() : m.text);
                if (m.scheduled) o.put("s", true); // (sent by a schedule: the page folds its long text)
                if (!m.files.isEmpty()) {
                    JSONArray f = new JSONArray();
                    for (String a : m.files) f.put(new JSONObject().put("id", a).put("name", Attachments.name(a)).put("image", Attachments.isImage(a)));
                    o.put("f", f);
                }
                if (!m.linux.isEmpty()) o.put("lx", new JSONArray(m.linux));
                // a chat with a team: who a message went to; who said this one (its colours: on dark, on light), and what it answers
                if (!m.to.isEmpty()) o.put("to", m.to);
                if (m.kind == Bubble.Kind.USER && !m.editable()) o.put("ne", true); // (went to members: not asked again)
                if (!m.fromName.isEmpty()) {
                    o.put("fn", m.fromName).put("fo", m.fromOn).put("q", m.quote).put("qf", m.quoteFrom);
                    if (!m.from.isEmpty()) o.put("c", new JSONArray(java.util.Arrays.asList(Team.hex(m.color))));
                    if (m.direct) o.put("dr", true); // (an answer to the user directly: never folded into a round)
                }
                msgs.put(o);
            }
            JSONObject team = null;
            String busy = "";
            // (a member's own chat shows its team's strip too: the team's chat's, with the leader's state)
            final Sessions.Session owner = lead != null ? lead : s;
            if (owner.team != null) { // its members as the app's strip shows them
                ChatHub hub = ChatHub.get(c);
                JSONArray members = new JSONArray();
                for (Team.Member m : owner.team.members) {
                    Sessions.Session h = Team.session(hub, owner.id, m.id);
                    boolean on = h != null && h.running;
                    String state = TeamUi.state(c, h);
                    if (on && busy.isEmpty() && lead == null) busy = c.getString(R.string.team_member_working, m.name, state);
                    String last = h == null ? "" : Ui.linkText(ChatHub.clip(h.lastReply, 600)).replace('\n', ' ').trim();
                    members.put(new JSONObject().put("id", m.id).put("chat", h == null ? "" : h.id).put("name", m.name).put("on", m.on()).put("goal", m.goal).put("state", state)
                            .put("running", on).put("c", new JSONArray(java.util.Arrays.asList(Team.hex(m.color))))
                            .put("last", last.length() > 200 ? last.substring(0, 200) + "…" : last)
                            // (an app on its hidden screen: the panel shows it through /web/shot, with its rules)
                            .put("screen", h != null && h.background && !h.incognito && Background.hasApp(h.id)));
                }
                team = new JSONObject().put("leader", new JSONObject().put("agent", Account.cliOf(owner.backend)).put("model", Models.chip(owner))
                                .put("running", owner.running).put("waiting", Team.waits(hub, owner)).put("chat", owner.id))
                        .put("structure", owner.team.structure).put("name", c.getString(Team.structureName(owner.team.structure)))
                        .put("members", members)
                        // the team in its shape (the page's own view of it): how it works, and what was set
                        .put("how", c.getString(Team.LEAD.equals(owner.team.structure) ? R.string.team_lead_info : Team.CHECK.equals(owner.team.structure)
                                ? R.string.team_check_info : Team.DOERS.equals(owner.team.structure) ? R.string.team_doers_info : R.string.team_pipe_info))
                        .put("workspace", c.getString(Team.SEP.equals(owner.team.workspace) ? R.string.team_ws_sep
                                : Team.LOOK.equals(owner.team.workspace) ? R.string.team_ws_look : R.string.team_ws_same))
                        .put("apps", c.getString(Team.TURN.equals(owner.team.apps) ? R.string.team_apps_turn
                                : Team.NONE.equals(owner.team.apps) ? R.string.team_apps_none : R.string.team_apps_diff))
                        .put("handoff", owner.team.handoff).put("rules", owner.team.rules);
            }
            if (owner.team == null) { // none: the setup the user asked for in the chat, waiting, and the last team, to start from
                if (s.teamDraft != null) team = new JSONObject().put("draft", s.teamDraft.json());
                else if (s.lastTeam != null) team = new JSONObject().put("last", s.lastTeam.json());
                if (team != null) team.put("off", true);
            }
            final JSONObject team0 = team;
            final String busy0 = busy;
            // first: where these begin in the chat, so the page knows a message again when earlier ones drop off
            return new JSONObject().put("id", s.id).put("title", s.label()).put("agent", Account.cliOf(s.backend))
                    .put("running", s.running).put("status", s.running && s.status != null ? s.status : "")
                    .put("model", Models.chip(s)).put("dev", s.dev).put("background", s.background).put("archived", s.archived)
                    .put("canBackground", Prefs.backgroundPaired(c)).put("linux", Linux.installed(c))
                    .put("screen", Prefs.webScreens(c) && Background.displayOf(s.id) != android.view.Display.DEFAULT_DISPLAY)
                    .put("first", first).put("messages", msgs).put("team", team0 == null ? JSONObject.NULL : team0).put("busy", busy0)
                    .put("memberOf", lead == null ? JSONObject.NULL : new JSONObject().put("id", lead.id).put("title", lead.label()));
        });
        return out == null ? new JSONObject().put("error", "no such chat") : out;
    }

    /**
     * The page's team setup (what the phone's Chat options → Team does): start, with the team as set up there
     * (draft: the one the user asked for in the chat, whose follow-ups the leader then asks only where they left them
     * open), off, or dismiss the waiting setup.
     */
    private static JSONObject team(Context c, JSONObject in) throws Exception {
        String a = in.optString("action");
        String err = onMain(() -> {
            ChatHub hub = ChatHub.get(c);
            Sessions.Session s = hub.sessions.find(in.optString("chat"));
            if (s == null || s.incognito || !s.parent.isEmpty()) return "no such chat";
            if ("dismiss".equals(a)) {
                s.teamDraft = null;
                hub.changed();
                return "";
            }
            if ("off".equals(a)) {
                if (Team.working(hub, s)) return "not while the chat or a member is working";
                if (s.team != null) hub.setTeam(s, null, null);
                return "";
            }
            if (s.team != null) return "this chat has a team already";
            String[] why = new String[1];
            Team t = Team.fromPage(c, s, in.optJSONObject("team") == null ? new JSONObject() : in.optJSONObject("team"), why);
            if (t == null) return why[0];
            Team.Draft d = in.optBoolean("draft") ? s.teamDraft : null;
            // (kept until the team is on, as on the phone, so a start that fails gives it back; not opened on the phone)
            if (d != null) d.shown = true;
            String ask = d != null ? Team.interview(t, !d.goals, !d.handoff, !d.rules) : Team.interview(t);
            // (once the leader is free, as on the phone: it may still be ending the turn that asked for this setup)
            hub.startTeamWhenFree(s, t, ask, s.backend, s.model == null ? "" : s.model);
            return "";
        });
        return err == null ? new JSONObject().put("error", "Buddy didn't answer") : err.isEmpty() ? new JSONObject() : new JSONObject().put("error", err);
    }

    /** A message from the page: into its chat, or a new chat with the default agent. */
    private static JSONObject send(Context c, String id, String text, JSONObject in) throws Exception {
        // files sent with it: attachments uploaded first (their ids), and files put in the chat's Linux tools (paths)
        java.util.List<String> files = new java.util.ArrayList<>(), linux = new java.util.ArrayList<>();
        for (String f : WebPlaces.list(in.optJSONArray("files"))) {
            if (Attachments.validId(f) && Attachments.file(c, f).isFile()) files.add(f);
        }
        for (String p : WebPlaces.list(in.optJSONArray("linux"))) if (p.startsWith("/root/.buddy/")) linux.add(p);
        if ((text == null || text.trim().isEmpty()) && files.isEmpty() && linux.isEmpty()) return new JSONObject().put("error", "empty");
        final String said = text == null ? "" : text.trim();
        String[] chat = new String[1];
        String err = onMain(() -> {
            ChatHub hub = ChatHub.get(c);
            if (id == null) for (String p : linux) { // (a new chat has no folder of its own yet: the workspace only)
                java.io.File real = FilePlaces.fromLinux(c, p).getCanonicalFile();
                if (!FilePlaces.inside(real, FilePlaces.work(c).getCanonicalFile())) return "that file isn't in this chat's places";
            }
            Sessions.Session s;
            if (id == null) { // a new chat, as the page's New chat screen set it up (else as the phone starts one)
                String agent = in.optString("agent", Prefs.backend(c));
                if (!SetupState.signedIn(c, agent)) return "that agent isn't signed in on the phone";
                String model = in.optString("model");
                boolean offered = model.isEmpty();
                for (String[] o : Models.options(agent, true)) offered |= model.equals(o[0]);
                if (!offered) return "that model isn't one this agent has";
                s = hub.create(agent, false, in.has("dev") ? in.optBoolean("dev") && Linux.installed(c) : Prefs.devMode(c));
                if (in.has("background")) s.background = in.optBoolean("background") && Prefs.backgroundPaired(c);
                if (!model.isEmpty()) hub.setModel(s, model);
            } else s = hub.sessions.find(id);
            Sessions.Session lead = s == null || s.parent.isEmpty() ? null : memberParent(c, s);
            if (lead != null) { // a team's member's own chat: as a message ticked for it in the team's chat
                if (said.trim().isEmpty() && files.isEmpty() && linux.isEmpty()) return ""; // (nothing to tell it)
                if (!linux.isEmpty() && Lite.on(s)) return "this chat runs on a model on the phone, which has no Linux tools for files";
                for (String p : linux) { // only what is in its own or its team's places, as for any chat
                    java.io.File real = FilePlaces.fromLinux(c, p).getCanonicalFile();
                    if (!FilePlaces.inside(real, FilePlaces.incoming(c, s.id).getCanonicalFile())
                            && !FilePlaces.inside(real, FilePlaces.incoming(c, lead.id).getCanonicalFile())
                            && !FilePlaces.inside(real, FilePlaces.work(c).getCanonicalFile())) return "that file isn't in this chat's places";
                }
                chat[0] = s.id;
                String e = Team.tell(hub, lead, lead.team.byId(s.member), null, said, files, linux.isEmpty() ? null : linux);
                return e == null ? "" : e;
            }
            if (s == null || s.incognito || !s.parent.isEmpty()) return "no such chat";
            if (!linux.isEmpty() && Lite.on(s)) return "this chat runs on a model on the phone, which has no Linux tools for files";
            // (a team chat takes a message while its leader works: as a note to it, and to the members ticked)
            if (s.running && s.team == null) return "the chat is still working: wait for it, or stop it on the phone";
            chat[0] = s.id;
            for (String p : linux) { // only what is in this chat's own places, as the app's chips are
                java.io.File real = FilePlaces.fromLinux(c, p).getCanonicalFile();
                if (!FilePlaces.inside(real, FilePlaces.incoming(c, s.id).getCanonicalFile())
                        && !FilePlaces.inside(real, FilePlaces.work(c).getCanonicalFile())) return "that file isn't in this chat's places";
            }
            String e = s.team != null ? hub.sendTeam(s, said, files, linux, WebPlaces.list(in.optJSONArray("to"))) // (who it goes to: the page's boxes)
                    : files.isEmpty() && linux.isEmpty() ? hub.send(s, said) : hub.send(s, said, files, linux);
            return e == null ? "" : e;
        });
        JSONObject o = new JSONObject().put("chat", chat[0] == null ? JSONObject.NULL : chat[0]);
        if (err == null || !err.isEmpty()) o.put("error", err == null ? "Buddy didn't answer" : err);
        return o;
    }

    /** An answer to the card the page showed (only if it's still the one waiting: its text is compared). */
    private static JSONObject answer(JSONObject in) throws Exception {
        String shown = in.optString("text");
        String result = onMain(() -> {
            BuddyService svc = BuddyService.get();
            Bubble b = svc == null ? null : svc.bubble();
            if (b == null || b.pendingConfirmText() == null || !b.pendingConfirmText().equals(shown)
                    || hidden(b)) return "that card isn't waiting any more";
            if (in.has("option")) b.pickFromApp(in.getString("option"));
            else if (in.has("change")) b.changeFromApp(in.getString("change"));
            else b.answerFromApp(in.optBoolean("approve"));
            return "";
        });
        JSONObject o = new JSONObject();
        if (result == null || !result.isEmpty()) o.put("error", result == null ? "Buddy didn't answer" : result);
        return o;
    }

    private static volatile String pageCache;

    private static String page(Context c) throws Exception {
        String p = pageCache;
        if (p != null) return p;
        try (InputStream in = c.getAssets().open("web/index.html")) {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            pageCache = p = b.toString("UTF-8");
        }
        return p;
    }

    /** The address to open on the other device, or null when Tailscale isn't up. */
    static String url(Context c) {
        InetAddress a = Tailnet.address(c);
        return a == null ? null : "http://" + a.getHostAddress() + ":" + McpServer.PORT + "/";
    }
}
