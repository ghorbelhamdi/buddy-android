package app.buddy.assistant;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * The user's own MCP servers (Settings → MCP servers, issue #25): servers reached over HTTP whose tools the agents
 * get next to the phone's. Buddy stands between an agent and a server: every agent is given the same kind of
 * address on Buddy itself (http://127.0.0.1:8765/mcpx/&lt;name&gt;, with Buddy's own token), and Buddy passes the
 * requests on with the server's key. So a key never enters Buddy's Linux, the five agents need no setup of their
 * own, and a server switched off is off for all of them at once. Not for a notification rule's run.
 */
final class McpServers {
    static final String PATH = "/mcpx/";

    static final class S {
        /** Its name for the agents: lower-case letters, digits and "_" (tools show as mcp__name__tool). */
        final String id;
        String url = "";
        boolean on = true;
        /** [{name, description, sig}] as the server listed them at the last check. */
        JSONArray tools = new JSONArray();

        S(String id) {
            this.id = id;
        }

        String host() {
            String h = android.net.Uri.parse(url).getHost();
            return h == null ? "" : h;
        }
    }

    private static List<S> cache;

    private McpServers() {
    }

    static synchronized List<S> all() {
        if (cache != null) return cache;
        cache = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(Prefs.mcpServers(BuddyApp.context()));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                S s = new S(o.getString("id"));
                s.url = o.optString("url");
                s.on = o.optBoolean("on", true);
                JSONArray t = o.optJSONArray("tools");
                if (t != null) s.tools = t;
                cache.add(s);
            }
        } catch (Exception ignored) {
        }
        return cache;
    }

    static synchronized S find(String id) {
        for (S s : all()) if (s.id.equals(id)) return s;
        return null;
    }

    static String secretName(String id) {
        return "mcp_" + id;
    }

    /** A name the agents can use, from what the user typed: "My Notes!" → "my_notes". "" when nothing is left. */
    static String slug(String name) {
        String s = name.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
        if (s.length() > 32) s = s.substring(0, 32);
        return s;
    }

    /** Is the name free? "phone" is Buddy's own server. */
    static synchronized boolean free(String id) {
        return !id.isEmpty() && !"phone".equals(id) && find(id) == null;
    }

    /** An address Buddy connects to: https, or plain http on this phone or the user's own network. */
    static boolean addressOk(String url) {
        return url.startsWith("https://") || url.startsWith("http://") && Providers.isLocal(url);
    }

    /** Adds or updates a server. key: null keeps the saved one, "" removes it. */
    static synchronized void save(Context c, S s, String key) throws Exception {
        if (key != null) {
            if (key.trim().isEmpty()) Secrets.remove(c, secretName(s.id));
            else Secrets.put(c, secretName(s.id), key.trim());
        }
        if (find(s.id) == null) all().add(s);
        store(c);
    }

    static synchronized void remove(Context c, String id) {
        S s = find(id);
        if (s == null) return;
        all().remove(s);
        Secrets.remove(c, secretName(id));
        try {
            store(c);
        } catch (Exception ignored) {
        }
    }

    static synchronized void setOn(Context c, S s, boolean on) {
        s.on = on;
        try {
            store(c);
        } catch (Exception ignored) {
        }
    }

    /** The file for Buddy's Linux again as this build writes it, once at start. */
    static void rewrite(Context c) {
        try {
            store(c);
        } catch (Exception ignored) {
        }
    }

    /**
     * Saves the list (no keys) and tells Buddy's Linux which servers are on: their names and their tools (the helper
     * builds each agent's settings from it). No address and no key goes there: an agent only ever talks to Buddy.
     */
    private static synchronized void store(Context c) throws Exception {
        JSONArray a = new JSONArray(), on = new JSONArray();
        for (S s : all()) {
            a.put(new JSONObject().put("id", s.id).put("url", s.url).put("on", s.on).put("tools", s.tools));
            if (!s.on) continue;
            JSONArray names = new JSONArray();
            StringBuilder sigs = new StringBuilder();
            for (int i = 0; i < s.tools.length(); i++) {
                JSONObject t = s.tools.getJSONObject(i);
                names.put(t.optString("name"));
                sigs.append(sigs.length() == 0 ? "" : ", ").append(t.optString("sig"));
            }
            on.put(new JSONObject().put("name", s.id).put("tools", names).put("sigs", sigs.toString()));
        }
        Prefs.setMcpServers(c, a.toString());
        if (!Linux.installed(c)) return;
        Linux.write(new File(Linux.root(c), "root/.buddy/mcp-servers.json"), on.toString());
    }

    /**
     * The header that carries the server's key, or null without one. A key is sent as "Authorization: Bearer …";
     * a server that wants another header takes it written out, "X-Api-Key: …".
     */
    static String[] header(Context c, String id) {
        return header(Secrets.get(c, secretName(id)));
    }

    static String[] header(String key) {
        if (key == null || key.trim().isEmpty()) return null;
        key = key.trim();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("^([A-Za-z][A-Za-z0-9-]{0,63}):\\s*(.+)$").matcher(key);
        if (m.find()) return new String[]{m.group(1), m.group(2)};
        return new String[]{"Authorization", key.regionMatches(true, 0, "Bearer ", 0, 7) ? key : "Bearer " + key};
    }

    // ------------------------------------------------------------ asking a server what it offers

    /**
     * Connects as an MCP client would (initialize, then tools/list) and returns the server's tools as
     * [{name, description, sig}]. Throws with the server's own words when it refuses.
     */
    static JSONArray discover(String url, String[] header) throws Exception {
        String[] session = new String[1];
        JSONObject init = rpc(url, header, session, new JSONObject().put("jsonrpc", "2.0").put("id", 1)
                .put("method", "initialize").put("params", new JSONObject().put("protocolVersion", "2025-06-18")
                        .put("capabilities", new JSONObject())
                        .put("clientInfo", new JSONObject().put("name", "Buddy").put("version", BuildInfo.VERSION))));
        if (init == null || !init.has("result")) throw new Exception(said(init, "The server didn't answer \"initialize\"."));
        rpc(url, header, session, new JSONObject().put("jsonrpc", "2.0").put("method", "notifications/initialized"));
        JSONArray out = new JSONArray();
        String cursor = null;
        for (int page = 0; page < 50; page++) { // a server pages a long list: every page is asked for
            JSONObject params = new JSONObject();
            if (cursor != null) params.put("cursor", cursor);
            JSONObject r = rpc(url, header, session, new JSONObject().put("jsonrpc", "2.0").put("id", 2 + page)
                    .put("method", "tools/list").put("params", params));
            JSONObject result = r == null ? null : r.optJSONObject("result");
            if (result == null) throw new Exception(said(r, "The server didn't list its tools."));
            JSONArray tools = result.optJSONArray("tools");
            for (int i = 0; tools != null && i < tools.length(); i++) {
                JSONObject t = tools.getJSONObject(i);
                String name = t.optString("name");
                if (name.isEmpty()) continue;
                out.put(new JSONObject().put("name", name).put("description", t.optString("description").trim())
                        .put("sig", sig(name, t.optJSONObject("inputSchema"))));
            }
            cursor = result.optString("nextCursor", "");
            if (cursor.isEmpty()) break;
        }
        return out;
    }

    /** "search(query, limit?)": a tool's arguments, optional ones marked. */
    private static String sig(String name, JSONObject schema) {
        StringBuilder b = new StringBuilder(name).append('(');
        JSONObject props = schema == null ? null : schema.optJSONObject("properties");
        JSONArray req = schema == null ? null : schema.optJSONArray("required");
        java.util.Set<String> must = new java.util.HashSet<>();
        for (int i = 0; req != null && i < req.length(); i++) must.add(req.optString(i));
        boolean first = true;
        if (props != null) for (java.util.Iterator<String> it = props.keys(); it.hasNext(); ) {
            String k = it.next();
            b.append(first ? "" : ", ").append(k).append(must.contains(k) ? "" : "?");
            first = false;
        }
        return b.append(')').toString();
    }

    private static String said(JSONObject r, String otherwise) {
        JSONObject e = r == null ? null : r.optJSONObject("error");
        return e == null ? otherwise : e.optString("message", e.toString());
    }

    /** One JSON-RPC message to the server; its answer (plain JSON or the first one in an event stream), or null. */
    private static JSONObject rpc(String url, String[] header, String[] session, JSONObject msg) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        con.setRequestMethod("POST");
        con.setConnectTimeout(20_000);
        con.setReadTimeout(40_000);
        con.setInstanceFollowRedirects(false);
        con.setRequestProperty("Content-Type", "application/json");
        con.setRequestProperty("Accept", "application/json, text/event-stream");
        if (!"initialize".equals(msg.optString("method"))) con.setRequestProperty("MCP-Protocol-Version", "2025-06-18");
        if (session[0] != null) con.setRequestProperty("Mcp-Session-Id", session[0]);
        if (header != null) con.setRequestProperty(header[0], header[1]);
        byte[] body = msg.toString().getBytes(StandardCharsets.UTF_8);
        con.setDoOutput(true);
        con.setFixedLengthStreamingMode(body.length);
        try (OutputStream o = con.getOutputStream()) {
            o.write(body);
        }
        try {
            int code = con.getResponseCode();
            String sid = con.getHeaderField("Mcp-Session-Id");
            if (sid != null && !sid.isEmpty()) session[0] = sid;
            InputStream in = code >= 400 ? con.getErrorStream() : con.getInputStream();
            String text = in == null ? "" : read(in, msg.has("id"));
            if (code >= 400) {
                String key = header == null ? "" : header[1];
                if (!key.isEmpty()) text = text.replace(key, "…"); // an error may quote the key back
                throw new Exception("HTTP " + code + (text.trim().isEmpty() ? "" : ": " + text.trim()));
            }
            if (!msg.has("id")) return null; // a notification has no answer
            String type = String.valueOf(con.getContentType());
            if (type.contains("text/event-stream")) {
                for (String line : text.split("\n")) {
                    if (!line.startsWith("data:")) continue;
                    try {
                        JSONObject o = new JSONObject(line.substring(5).trim());
                        if (o.has("result") || o.has("error")) return o;
                    } catch (org.json.JSONException ignored) { // not a message
                    }
                }
                return null;
            }
            return text.trim().isEmpty() ? null : new JSONObject(text);
        } finally {
            con.disconnect();
        }
    }

    /** What the server sent; of an event stream, up to its first answer (a server may keep the stream open). */
    private static String read(InputStream in, boolean answer) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        try {
            for (int n; (n = in.read(buf)) > 0; ) {
                b.write(buf, 0, n);
                if (!answer) continue;
                String s = b.toString("UTF-8");
                if (s.startsWith("event:") || s.startsWith("data:") || s.startsWith(":")) {
                    if (s.matches("(?s).*data:[^\\n]*\"(result|error)\"[^\\n]*\\n.*")) break;
                }
            }
        } finally {
            in.close();
        }
        return b.toString("UTF-8");
    }

    // ------------------------------------------------------------ between an agent and a server

    /**
     * An agent's request for one of the user's servers (rawPath: /mcpx/&lt;name&gt;, already checked to carry Buddy's
     * token): on to the server with its key, and the answer back as it arrives.
     */
    static void relay(Context c, String method, String rawPath, Map<String, String> headers, byte[] body, OutputStream out)
            throws Exception {
        String id = rawPath.substring(PATH.length());
        int cut = id.indexOf('?');
        if (cut >= 0) id = id.substring(0, cut);
        cut = id.indexOf('/');
        if (cut >= 0) id = id.substring(0, cut);
        S s = find(id);
        if (s == null || !s.on) {
            error(out, 404, s == null ? "No MCP server named \"" + id + "\" is set up in Buddy (Settings → MCP servers)."
                    : "The MCP server \"" + id + "\" is switched off in Buddy (Settings → MCP servers).");
            return;
        }
        String[] key = header(c, s.id);
        HttpURLConnection con = (HttpURLConnection) new URL(s.url).openConnection();
        con.setRequestMethod(method);
        con.setConnectTimeout(30_000);
        con.setReadTimeout(10 * 60_000); // a tool may take long; the agents wait as long for the phone's
        con.setInstanceFollowRedirects(false);
        for (Map.Entry<String, String> h : headers.entrySet()) {
            String n = h.getKey();
            // Buddy's own token and the connection's own headers don't go on
            if (n.equals("authorization") || n.equals("x-api-key") || n.equals("host") || n.equals("content-length")
                    || n.equals("connection") || n.equals("transfer-encoding") || n.equals("accept-encoding")) continue;
            if (key != null && n.equalsIgnoreCase(key[0])) continue;
            con.setRequestProperty(n, h.getValue());
        }
        if (key != null) con.setRequestProperty(key[0], key[1]);
        if (body.length > 0 && !"GET".equals(method)) {
            con.setDoOutput(true);
            con.setFixedLengthStreamingMode(body.length);
            try (OutputStream o = con.getOutputStream()) {
                o.write(body);
            }
        }
        int code;
        try {
            code = con.getResponseCode();
        } catch (java.io.IOException e) {
            error(out, 502, "Buddy couldn't reach the MCP server \"" + id + "\": " + e.getMessage());
            return;
        }
        StringBuilder h = new StringBuilder("HTTP/1.1 ").append(code).append(" Relayed\r\n");
        for (Map.Entry<String, List<String>> e : con.getHeaderFields().entrySet()) {
            String n = e.getKey();
            if (n == null) continue;
            String l = n.toLowerCase(Locale.ROOT);
            if (l.equals("content-length") || l.equals("content-encoding") || l.equals("transfer-encoding")
                    || l.equals("connection") || l.equals("set-cookie") || l.startsWith("x-android")) continue;
            for (String v : e.getValue()) h.append(n).append(": ").append(v).append("\r\n");
        }
        h.append("Transfer-Encoding: chunked\r\nConnection: close\r\n\r\n");
        out.write(h.toString().getBytes(StandardCharsets.UTF_8));
        out.flush();
        try (InputStream in = code >= 400 ? con.getErrorStream() : con.getInputStream()) {
            if (in != null) {
                byte[] buf = new byte[16 * 1024];
                for (int n; (n = in.read(buf)) > 0; ) {
                    byte[] part = buf;
                    int len = n;
                    if (code >= 400 && key != null) { // an error may quote the key back, and the agent must never see it
                        part = new String(buf, 0, n, StandardCharsets.UTF_8).replace(key[1], "…").getBytes(StandardCharsets.UTF_8);
                        len = part.length;
                    }
                    out.write((Integer.toHexString(len) + "\r\n").getBytes(StandardCharsets.US_ASCII));
                    out.write(part, 0, len);
                    out.write("\r\n".getBytes(StandardCharsets.US_ASCII));
                    out.flush();
                }
            }
        } finally {
            con.disconnect();
        }
        out.write("0\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
        out.flush();
    }

    private static void error(OutputStream out, int code, String message) throws Exception {
        byte[] b = new JSONObject().put("jsonrpc", "2.0").put("id", JSONObject.NULL).put("error", new JSONObject()
                .put("code", -32000).put("message", message)).toString().getBytes(StandardCharsets.UTF_8);
        out.write(("HTTP/1.1 " + code + " Error\r\nContent-Type: application/json\r\nContent-Length: " + b.length
                + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        out.write(b);
        out.flush();
    }

    /** Servers whose tools aren't known yet (just imported) are asked for them, in the background; then done runs. */
    static void fillIn(final Context c, final Runnable done) {
        final Context app = c.getApplicationContext();
        new Thread(() -> {
            List<S> todo;
            synchronized (McpServers.class) {
                todo = new ArrayList<>();
                for (S s : all()) if (s.tools.length() == 0) todo.add(s);
            }
            for (S s : todo) {
                try {
                    JSONArray t = discover(s.url, header(app, s.id));
                    synchronized (McpServers.class) {
                        s.tools = t;
                        store(app);
                    }
                } catch (Exception ignored) { // the screen shows 0 tools: opening the server says why
                }
            }
            if (done != null) done.run();
        }, "mcp-tools").start();
    }

    // ------------------------------------------------------------ moving the list

    /** The list as other MCP clients write it ({"mcpServers": {name: {url}}}), without the keys. */
    static synchronized String exported() {
        try {
            JSONObject servers = new JSONObject();
            for (S s : all()) servers.put(s.id, new JSONObject().put("type", "http").put("url", s.url));
            return new JSONObject().put("mcpServers", servers).toString(2);
        } catch (Exception e) {
            return "{}";
        }
    }

    /**
     * Takes servers from such a list (a "mcpServers" or "servers" object, or the object of servers itself): each
     * one with an address is added, with its first header as its key. Returns {added, skipped}: a server that runs
     * as a command on a computer, one with an address Buddy doesn't connect to, or a name already taken is skipped.
     */
    static synchronized int[] imported(Context c, String json) throws Exception {
        JSONObject root = new JSONObject(json.trim());
        JSONObject servers = root.optJSONObject("mcpServers");
        if (servers == null) servers = root.optJSONObject("servers");
        if (servers == null) servers = root;
        int added = 0, skipped = 0;
        for (java.util.Iterator<String> it = servers.keys(); it.hasNext(); ) {
            String name = it.next();
            JSONObject o = servers.optJSONObject(name);
            String url = o == null ? "" : o.optString("url", o.optString("serverUrl", o.optString("httpUrl"))).trim();
            String id = slug(name);
            if (o == null || url.isEmpty() || !addressOk(url) || !free(id)) {
                skipped++;
                continue;
            }
            S s = new S(id);
            s.url = url;
            String key = null;
            JSONObject hs = o.optJSONObject("headers");
            if (hs != null && hs.length() > 0) {
                String hn = hs.keys().next();
                key = hn.equalsIgnoreCase("Authorization") ? hs.optString(hn) : hn + ": " + hs.optString(hn);
            }
            save(c, s, key);
            added++;
        }
        return new int[]{added, skipped};
    }
}
