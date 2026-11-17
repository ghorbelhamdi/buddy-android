package app.buddy.assistant;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

/**
 * "Phone tools on Tailscale": Buddy's phone tools for agents on the user's own computers, over Tailscale (issue #48).
 * Off by default. Each computer the user pairs gets a token of its own, which they can remove; only those tokens are
 * heard on the Tailscale address, never Buddy's own or a chat's (see McpServer.handle), and only for /mcp.
 */
final class Tailnet {
    private Tailnet() {
    }

    /** A paired computer: its name, its token, when it was added. */
    static final class Device {
        final String id, name, token;
        final long added;

        Device(String id, String name, String token, long added) {
            this.id = id;
            this.name = name;
            this.token = token;
            this.added = added;
        }
    }

    static List<Device> devices(Context c) {
        List<Device> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(Prefs.tailnetDevices(c));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                out.add(new Device(o.getString("id"), o.optString("name"), o.getString("token"), o.optLong("added")));
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    private static void save(Context c, List<Device> ds) {
        JSONArray a = new JSONArray();
        try {
            for (Device d : ds) {
                a.put(new JSONObject().put("id", d.id).put("name", d.name).put("token", d.token).put("added", d.added));
            }
        } catch (Exception ignored) {
        }
        Prefs.setTailnetDevices(c, a.toString());
    }

    /** A new computer, with a token of its own. */
    static Device add(Context c, String name) {
        byte[] b = new byte[24];
        new java.security.SecureRandom().nextBytes(b);
        StringBuilder t = new StringBuilder("tn-");
        for (byte x : b) t.append(String.format("%02x", x));
        Device d = new Device(Long.toString(System.currentTimeMillis(), 36), name, t.toString(), System.currentTimeMillis());
        List<Device> ds = devices(c);
        ds.removeIf(o -> o.name.equals(name)); // the same computer paired again: its old key stops working
        ds.add(d);
        save(c, ds);
        return d;
    }

    static void remove(Context c, String id) {
        List<Device> ds = devices(c);
        ds.removeIf(d -> d.id.equals(id));
        save(c, ds);
    }

    /** One pairing at a time: a second computer asking meanwhile is turned away. */
    private static final java.util.concurrent.atomic.AtomicBoolean pairing = new java.util.concurrent.atomic.AtomicBoolean();

    /**
     * A computer on the tailnet asks to be paired (POST /pair?name=…): the user approves it on a card that names it and
     * its Tailscale address, which comes from the connection itself. Approved, it gets a key of its own, answered as
     * the JSON `claude mcp add-json` takes; null when it isn't paired.
     */
    static String pair(Context c, String name, InetAddress from, InetAddress phone) throws InterruptedException {
        BuddyService svc = BuddyService.get();
        if (svc == null || !Prefs.tailnetTools(c) || from == null || !cgnat(from.getAddress())) return null;
        String n = name == null ? "" : name.replaceAll("[^A-Za-z0-9 ._-]", "").trim();
        if (n.length() > 40) n = n.substring(0, 40);
        if (n.isEmpty()) n = c.getString(R.string.setup_tailnet_computer, devices(c).size() + 1);
        if (!pairing.compareAndSet(false, true)) return null;
        try {
            String answer = svc.askConfirm(c.getString(R.string.tools_card_pair, n, from.getHostAddress()), false);
            if (!answer.startsWith("APPROVED")) return null;
            // the computer gave up waiting (or its connection was closed): a key nobody receives isn't made
            java.util.function.BooleanSupplier gone = McpServer.CALLER_GONE.get();
            if (gone != null && gone.getAsBoolean()) return null;
            Device d = add(c, n);
            return new JSONObject().put("type", "http")
                    .put("url", "http://" + phone.getHostAddress() + ":" + McpServer.PORT + "/mcp")
                    .put("headers", new JSONObject().put("Authorization", "Bearer " + d.token))
                    .put("timeout", 600000).toString();
        } catch (org.json.JSONException e) {
            return null;
        } finally {
            pairing.set(false);
        }
    }

    /** The paired computer this token belongs to, or null (compared in constant time). */
    static Device device(Context c, String token) {
        if (token == null || !Prefs.tailnetTools(c)) return null;
        byte[] shown = token.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        Device found = null;
        for (Device d : devices(c)) {
            if (java.security.MessageDigest.isEqual(d.token.getBytes(java.nio.charset.StandardCharsets.UTF_8), shown)) found = d;
        }
        return found;
    }

    /**
     * The phone's Tailscale address, while the switch is on; else null. Only an address in
     * Tailscale's range (100.64.0.0/10) on a VPN network counts: carriers hand out that range on mobile data too.
     */
    static InetAddress address(Context c) {
        if (!Prefs.tailnetTools(c)) return null;
        try {
            ConnectivityManager cm = c.getSystemService(ConnectivityManager.class);
            for (Network n : cm.getAllNetworks()) {
                NetworkCapabilities nc = cm.getNetworkCapabilities(n);
                if (nc == null || !nc.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue;
                LinkProperties lp = cm.getLinkProperties(n);
                if (lp == null) continue;
                for (LinkAddress la : lp.getLinkAddresses()) {
                    InetAddress a = la.getAddress();
                    if (a instanceof Inet4Address && cgnat(a.getAddress())) return a;
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private static boolean cgnat(byte[] b) {
        return (b[0] & 0xff) == 100 && (b[1] & 0xc0) == 64; // 100.64.0.0/10
    }
}
