package app.buddy.assistant;

import android.content.Context;
import android.net.Uri;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * An agent's requests to another service's API with an API key saved in Buddy (Settings → API keys): the agent is
 * pointed at http://127.0.0.1:8765/llm/&lt;service&gt;/a/… (its Anthropic-style address) or …/o/… (its OpenAI-style one),
 * with Buddy's per-install token, and this sends each request on with the real key, streaming the reply back. The key
 * stays in Buddy's encrypted storage (Secrets) and never enters Buddy's Linux, so no agent, command or page it reads
 * can see it or send it anywhere.
 */
final class Relay {
    static final String PATH = "/llm/";

    private Relay() {
    }

    /** Where an agent in Buddy's Linux sends its requests for this service: its Anthropic-style or OpenAI-style API. */
    static String url(String service, boolean anthropic) {
        return "http://127.0.0.1:" + McpServer.PORT + PATH + service + (anthropic ? "/a" : "/o");
    }

    private static String trim(String b) {
        return b.endsWith("/") ? b.substring(0, b.length() - 1) : b;
    }

    /** Anthropic's own API takes the key as x-api-key; other services (and OpenAI) as a bearer token. */
    private static boolean xApiKey(boolean anthropic, String base) {
        return anthropic && "api.anthropic.com".equals(Uri.parse(base).getHost());
    }

    /**
     * The service's models, straight from the app (the key is here): [{id, name, window, efforts}], window the context
     * size in tokens (0 if not said), efforts the reasoning levels it lists. Services keep the list in different places
     * (DeepSeek's Anthropic-style address has none, its main one has), so the usual ones are tried in turn. Throws with
     * the service's own error when none answers.
     */
    static org.json.JSONArray models(boolean anthropic, String key, String given) throws Exception {
        String b = trim(given);
        Uri u = Uri.parse(b);
        String origin = u.getScheme() + "://" + u.getEncodedAuthority();
        java.util.Set<String> tries = new java.util.LinkedHashSet<>(java.util.Arrays.asList(
                b + "/v1/models", b + "/models", origin + "/v1/models", origin + "/models"));
        String error = null;
        for (String t : tries) {
            HttpURLConnection con = (HttpURLConnection) new URL(t).openConnection();
            con.setConnectTimeout(15_000);
            con.setReadTimeout(30_000);
            con.setInstanceFollowRedirects(false);
            if (anthropic) con.setRequestProperty("anthropic-version", "2023-06-01");
            if (xApiKey(anthropic, b)) con.setRequestProperty("x-api-key", key);
            else if (!key.isEmpty()) con.setRequestProperty("Authorization", "Bearer " + key); // a local server needs none
            try {
                int code = con.getResponseCode();
                InputStream in = code >= 400 ? con.getErrorStream() : con.getInputStream();
                String body = in == null ? "" : read(in);
                if (code >= 400) {
                    if (error == null || code != 404) error = code + " " + hideKey(body.trim(), key);
                    continue;
                }
                org.json.JSONArray list = parseModels(body);
                if (list.length() > 0) return list;
            } catch (org.json.JSONException ignored) { // not a model list
            } finally {
                con.disconnect();
            }
        }
        throw new Exception(error != null ? error : BuddyApp.str(R.string.signin_api_models_none));
    }

    /** What a test request to a service's API found: it answered, it has no such API, or no clear answer. */
    static final String YES = "yes", NO = "no", UNSURE = "unsure";

    /** What a test request found; detail is the service's own answer when it wasn't a clear yes. */
    static final class Probe {
        String result = UNSURE, detail = "";
        int code;
        /** The service turned down the model, which says nothing about the API. */
        boolean model;
    }

    /** Does this OpenAI-style address have the Responses API, which Codex needs? */
    static Probe responses(String key, String base, String model) throws Exception {
        return probe(false, key, trim(base) + "/responses", new org.json.JSONObject().put("model", model)
                .put("input", "Hi").put("max_output_tokens", 16).toString());
    }

    /** Does this OpenAI-style address have Chat Completions? */
    static Probe chat(String key, String base, String model) throws Exception {
        return probe(false, key, trim(base) + "/chat/completions", new org.json.JSONObject().put("model", model)
                .put("max_tokens", 1).put("messages", new org.json.JSONArray()
                        .put(new org.json.JSONObject().put("role", "user").put("content", "Hi"))).toString());
    }

    /** Is this an Anthropic-style address? */
    static Probe messages(String key, String base, String model) throws Exception {
        return probe(true, key, trim(base) + "/v1/messages", new org.json.JSONObject().put("model", model)
                .put("max_tokens", 1).put("messages", new org.json.JSONArray()
                        .put(new org.json.JSONObject().put("role", "user").put("content", "Hi"))).toString());
    }

    /**
     * One very small request (it may use a tiny bit of API credit). Only a real answer means yes, and only "there's
     * nothing at this address" means no: a limit, an outage or a rejected model says nothing about the API itself.
     */
    private static Probe probe(boolean anthropic, String key, String url, String json) {
        Probe p = new Probe();
        HttpURLConnection con = null;
        try {
            con = (HttpURLConnection) new URL(url).openConnection();
            con.setRequestMethod("POST");
            con.setConnectTimeout(15_000);
            con.setReadTimeout(30_000);
            con.setInstanceFollowRedirects(false);
            con.setDoOutput(true);
            con.setRequestProperty("Content-Type", "application/json");
            if (anthropic) con.setRequestProperty("anthropic-version", "2023-06-01");
            if (xApiKey(anthropic, url)) con.setRequestProperty("x-api-key", key);
            else if (!key.isEmpty()) con.setRequestProperty("Authorization", "Bearer " + key); // a local server needs none
            try (OutputStream o = con.getOutputStream()) {
                o.write(json.getBytes(StandardCharsets.UTF_8));
            }
            p.code = con.getResponseCode();
            if (p.code >= 200 && p.code < 300) {
                p.result = YES;
                return p;
            }
            InputStream in = con.getErrorStream();
            String body = in == null ? "" : read(in);
            String said = errorText(body);
            p.detail = hideKey(p.code + " " + (said.isEmpty() ? body.trim() : said), key);
            if (p.detail.length() > 300) p.detail = p.detail.substring(0, 300);
            p.model = said.toLowerCase(Locale.ROOT).matches("(?s).*(model|deployment).*");
            // a 404 about the model (OpenAI's for an unknown one) doesn't mean the API isn't there
            if ((p.code == 404 || p.code == 405 || p.code == 501) && !p.model) p.result = NO;
            return p;
        } catch (Exception e) {
            p.detail = hideKey(String.valueOf(e.getMessage()), key);
            return p;
        } finally {
            if (con != null) con.disconnect();
        }
    }

    /** The error a service's JSON answer states, "" if it isn't one: a web page's words about models mean nothing. */
    private static String errorText(String body) {
        try {
            org.json.JSONObject o = new org.json.JSONObject(body.trim());
            org.json.JSONObject e = o.optJSONObject("error");
            if (e != null) return (e.optString("code") + " " + e.optString("message")).trim();
            String s = o.optString("error");
            return s.isEmpty() ? o.optString("message") : s;
        } catch (Exception e) {
            return "";
        }
    }

    /** A service's words with the key taken out: some repeat it in their errors ("Your api key: ... is invalid"). */
    static String hideKey(String text, String key) {
        return key == null || key.trim().length() < 4 ? text : text.replace(key.trim(), "•••");
    }

    private static String read(InputStream in) throws Exception {
        java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
        byte[] buf = new byte[16 * 1024];
        int n;
        while ((n = in.read(buf)) > 0 && o.size() < 4 << 20) o.write(buf, 0, n);
        return o.toString("UTF-8");
    }

    /** {data: [...]} (OpenAI, Anthropic, most others), {models: [...]} or a bare array, as [{id, name, window, efforts}]. */
    private static org.json.JSONArray parseModels(String body) throws org.json.JSONException {
        String t = body.trim();
        org.json.JSONArray a;
        if (t.startsWith("[")) a = new org.json.JSONArray(t);
        else {
            org.json.JSONObject o = new org.json.JSONObject(t);
            a = o.optJSONArray("data");
            if (a == null) a = o.optJSONArray("models");
        }
        org.json.JSONArray out = new org.json.JSONArray();
        if (a == null) return out;
        for (int i = 0; i < a.length() && out.length() < 300; i++) {
            org.json.JSONObject m = a.optJSONObject(i);
            String id = m == null ? "" : m.optString("id", "");
            if (id.isEmpty()) continue;
            String name = m.optString("display_name", m.optString("name", id));
            long window = 0;
            for (String k : new String[]{"context_window", "context_length", "max_input_tokens", "max_context_length"}) {
                window = m.optLong(k, 0);
                if (window > 0) break;
            }
            org.json.JSONObject effort = m.optJSONObject("effort");
            org.json.JSONArray levels = effort == null ? null : effort.optJSONArray("supported_levels");
            out.put(new org.json.JSONObject().put("id", id).put("name", name.isEmpty() ? id : name).put("window", window)
                    .put("efforts", levels == null ? new org.json.JSONArray() : levels));
        }
        return out;
    }

    /**
     * Does the relay change this request's body (see handle)? Then it needs all of it in memory; any other request's
     * body goes on to the service as it comes in, whatever its size.
     */
    static boolean rewrites(String rawPath) {
        String[] parts = rawPath.substring(PATH.length()).split("/", 3);
        Providers.P p = parts.length >= 2 ? Providers.find(parts[0]) : null;
        if (p == null || !"o".equals(parts[1])) return false;
        return parts.length == 3 && ("/" + parts[2]).startsWith("/responses") && !isOpenAi(p.openai);
    }

    /** rawPath: /llm/deepseek/a/v1/messages?beta=true. Already checked to carry Buddy's token. */
    static void handle(Context c, String method, String rawPath, Map<String, String> headers, byte[] body,
                       OutputStream out) throws Exception {
        handle(c, method, rawPath, headers, body, null, body.length, out);
    }

    /** As above; with body null, the request's body (length bytes) is still to be read, and goes on as it comes. */
    static void handle(Context c, String method, String rawPath, Map<String, String> headers, byte[] body,
                       InputStream from, long length, OutputStream out) throws Exception {
        String[] parts = rawPath.substring(PATH.length()).split("/", 3);
        Providers.P p = parts.length >= 2 ? Providers.find(parts[0]) : null;
        boolean anthropic = parts.length >= 2 && "a".equals(parts[1]);
        String base = p == null ? "" : anthropic ? p.anthropic : "o".equals(parts[1]) ? p.openai : "";
        String key = p == null ? null : Secrets.get(c, Providers.secretName(p.id));
        if (p != null && p.off) { // the user switched this service off (Settings → API keys): nothing goes to it
            if (body == null) drain(from, length);
            error(out, 403, "permission_error", p.name + " is switched off in Buddy. Switch it on in Buddy's Settings, "
                    + "under API keys, or pick another model for this chat.");
            return;
        }
        if (key == null || base.isEmpty()) {
            if (body == null) drain(from, length); // the caller reads the answer only once its request is through
            error(out, 401, "No API key saved in Buddy for this service. Add one in Buddy's Settings, under API keys.");
            return;
        }
        String rest = parts.length == 3 ? "/" + parts[2] : "";
        // Codex sends MCP tools grouped as "namespace" tools, which only OpenAI's own API knows; other services
        // drop them and the model never sees the phone tools. Those get them as plain tools, and calls back as Codex
        // wants them (flat name → namespace + name).
        Map<String, String[]> flat = null;
        if (body != null && !anthropic && rest.startsWith("/responses") && !isOpenAi(base) && body.length > 0) {
            flat = new java.util.HashMap<>();
            byte[] b = flattenTools(body, flat);
            if (flat.isEmpty()) flat = null;
            else body = b;
        }
        HttpURLConnection con = (HttpURLConnection) new URL(trim(base) + rest).openConnection();
        con.setRequestMethod(method);
        con.setConnectTimeout(30_000);
        con.setReadTimeout((Providers.isLocal(base) ? 15 : 10) * 60_000); // a local model can read for long (ChatHub waits as long)
        con.setInstanceFollowRedirects(false);
        for (Map.Entry<String, String> h : headers.entrySet()) {
            String n = h.getKey();
            // the agent's stand-in token and the connection's own headers don't go on
            if (n.equals("authorization") || n.equals("x-api-key") || n.equals("host") || n.equals("content-length")
                    || n.equals("connection") || n.equals("transfer-encoding") || n.equals("accept-encoding")) continue;
            con.setRequestProperty(n, h.getValue());
        }
        if (xApiKey(anthropic, base)) con.setRequestProperty("x-api-key", key);
        else if (!key.isEmpty()) con.setRequestProperty("Authorization", "Bearer " + key); // a local server needs none
        if (body != null && body.length > 0) {
            con.setDoOutput(true);
            con.setFixedLengthStreamingMode(body.length);
            try (OutputStream o = con.getOutputStream()) {
                o.write(body);
            }
        } else if (body == null && length > 0) { // as it comes in: a chat's pictures make a request tens of MB
            con.setDoOutput(true);
            con.setFixedLengthStreamingMode(length);
            long left = length;
            try (OutputStream o = con.getOutputStream()) {
                byte[] buf = new byte[64 * 1024];
                while (left > 0) {
                    int n = from.read(buf, 0, (int) Math.min(buf.length, left));
                    if (n == -1) throw new java.io.IOException("unexpected end of body");
                    o.write(buf, 0, n);
                    left -= n;
                }
            } catch (java.io.IOException e) { // the service hung up early (it answers "too large" that way): its answer follows
                if (left > 0) drain(from, left);
            }
        }
        int code = con.getResponseCode();
        StringBuilder h = new StringBuilder("HTTP/1.1 ").append(code).append(" Relayed\r\n");
        for (Map.Entry<String, List<String>> e : con.getHeaderFields().entrySet()) {
            String n = e.getKey();
            if (n == null) continue;
            String l = n.toLowerCase(Locale.ROOT);
            if (l.equals("content-length") || l.equals("content-encoding") || l.equals("transfer-encoding")
                    || l.equals("connection") || l.startsWith("x-android")) continue;
            for (String v : e.getValue()) h.append(n).append(": ").append(v).append("\r\n");
        }
        h.append("Transfer-Encoding: chunked\r\nConnection: close\r\n\r\n");
        out.write(h.toString().getBytes(StandardCharsets.UTF_8));
        out.flush();
        // as it arrives: replies stream token by token
        try (InputStream in = code >= 400 ? con.getErrorStream() : con.getInputStream()) {
            if (in != null && code >= 400) { // an error may quote the key back, and the agent must never see it
                byte[] said = hideKey(read(in), key).getBytes(StandardCharsets.UTF_8);
                if (said.length > 0) {
                    out.write((Integer.toHexString(said.length) + "\r\n").getBytes(StandardCharsets.US_ASCII));
                    out.write(said);
                    out.write("\r\n".getBytes(StandardCharsets.US_ASCII));
                    out.flush();
                }
            } else if (in != null && flat != null) {
                String type = con.getContentType();
                if (type != null && type.contains("json")) { // not streamed: the whole reply, however it's laid out
                    byte[] b = unflatten(read(in), flat).getBytes(StandardCharsets.UTF_8);
                    out.write((Integer.toHexString(b.length) + "\r\n").getBytes(StandardCharsets.US_ASCII));
                    out.write(b);
                    out.write("\r\n".getBytes(StandardCharsets.US_ASCII));
                    out.flush();
                } else unflattenStream(in, out, flat);
            } else if (in != null) {
                byte[] buf = new byte[16 * 1024];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write((Integer.toHexString(n) + "\r\n").getBytes(StandardCharsets.US_ASCII));
                    out.write(buf, 0, n);
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

    /** OpenAI's own API, which knows Codex's namespace tools. */
    static boolean isOpenAi(String base) {
        String h = Uri.parse(base).getHost();
        return h != null && (h.equals("api.openai.com") || h.endsWith(".openai.azure.com"));
    }

    /** A Codex request with its namespace tools (and earlier calls to them) made plain; flat: plain name → {namespace, name}. */
    static byte[] flattenTools(byte[] body, Map<String, String[]> flat) {
        try {
            org.json.JSONObject req = new org.json.JSONObject(new String(body, StandardCharsets.UTF_8));
            org.json.JSONArray tools = req.optJSONArray("tools");
            if (tools == null) return body;
            org.json.JSONArray out = new org.json.JSONArray();
            for (int i = 0; i < tools.length(); i++) {
                org.json.JSONObject t = tools.optJSONObject(i);
                if (t == null || !"namespace".equals(t.optString("type"))) {
                    out.put(t);
                    continue;
                }
                String ns = t.optString("name");
                org.json.JSONArray sub = t.optJSONArray("tools");
                for (int j = 0; sub != null && j < sub.length(); j++) {
                    org.json.JSONObject f = sub.optJSONObject(j);
                    if (f == null) continue;
                    String name = f.optString("name"), plain = plainName(ns, name);
                    flat.put(plain, new String[]{ns, name});
                    org.json.JSONObject g = new org.json.JSONObject(f.toString()).put("type", "function").put("name", plain);
                    g.remove("defer_loading");
                    out.put(g);
                }
            }
            if (flat.isEmpty()) return body;
            req.put("tools", out);
            org.json.JSONArray input = req.optJSONArray("input");
            for (int i = 0; input != null && i < input.length(); i++) {
                org.json.JSONObject it = input.optJSONObject(i);
                if (it != null && it.has("namespace") && it.optString("type").endsWith("_call")) {
                    it.put("name", plainName(it.optString("namespace"), it.optString("name")));
                    it.remove("namespace");
                }
            }
            return req.toString().getBytes(StandardCharsets.UTF_8);
        } catch (org.json.JSONException e) {
            return body; // not what Codex sends: passed on as it is
        }
    }

    /** mcp__phone + open_app → mcp__phone__open_app (Codex's apps already start their names with "_"). */
    private static String plainName(String ns, String name) {
        String n = name.startsWith("_") ? ns + name : ns + "__" + name;
        // OpenAI-style services take names of up to 64 characters
        return n.length() <= 64 ? n : n.substring(0, 55) + "_" + Integer.toHexString(n.hashCode() & 0xfffffff);
    }

    /** The reply's events, line by line, with each call to a flattened tool given back its namespace. */
    private static void unflattenStream(InputStream in, OutputStream out, Map<String, String[]> flat) throws Exception {
        java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
        StringBuilder chunk = new StringBuilder();
        String line;
        while ((line = r.readLine()) != null) {
            if (line.startsWith("data:")) line = "data: " + unflatten(line.substring(5).trim(), flat);
            chunk.append(line).append('\n');
            if (line.isEmpty() || !r.ready()) { // an event ends: send it on now
                byte[] b = chunk.toString().getBytes(StandardCharsets.UTF_8);
                out.write((Integer.toHexString(b.length) + "\r\n").getBytes(StandardCharsets.US_ASCII));
                out.write(b);
                out.write("\r\n".getBytes(StandardCharsets.US_ASCII));
                out.flush();
                chunk.setLength(0);
            }
        }
    }

    static String unflatten(String json, Map<String, String[]> flat) {
        if (!json.contains("_call\"") || !json.contains("\"name\"")) return json; // argument deltas carry no name
        try {
            org.json.JSONObject o = new org.json.JSONObject(json);
            boolean[] changed = {false};
            fix(o.optJSONObject("item"), flat, changed);
            org.json.JSONObject resp = o.optJSONObject("response");
            org.json.JSONArray items = resp != null ? resp.optJSONArray("output") : o.optJSONArray("output");
            for (int i = 0; items != null && i < items.length(); i++) fix(items.optJSONObject(i), flat, changed);
            return changed[0] ? o.toString() : json;
        } catch (org.json.JSONException e) {
            return json;
        }
    }

    private static void fix(org.json.JSONObject item, Map<String, String[]> flat, boolean[] changed)
            throws org.json.JSONException {
        if (item == null || item.has("namespace")) return;
        String[] real = flat.get(item.optString("name"));
        if (real == null) return;
        item.put("namespace", real[0]).put("name", real[1]);
        changed[0] = true;
    }

    /**
     * An agent's request was larger than the relay passes on (and than the services take). The agent shows this to
     * the user as its own API error, so it says what helps.
     */
    static void tooLarge(OutputStream out, long size, int max) throws Exception {
        error(out, 413, "request_too_large", "This request is " + (size >> 20) + " MB; Buddy has to rewrite requests to this "
                + "service and can do that up to " + (max >> 20) + " MB. A chat's pictures travel with every request: "
                + "use Compact now in the chat, or start a new chat.");
    }

    /** Reads n bytes and throws them away. */
    private static void drain(InputStream in, long n) {
        try {
            byte[] buf = new byte[64 * 1024];
            while (n > 0) {
                int r = in.read(buf, 0, (int) Math.min(buf.length, n));
                if (r == -1) return;
                n -= r;
            }
        } catch (java.io.IOException ignored) {
        }
    }

    private static void error(OutputStream out, int code, String message) throws Exception {
        error(out, code, "authentication_error", message);
    }

    private static void error(OutputStream out, int code, String type, String message) throws Exception {
        byte[] b = new org.json.JSONObject().put("type", "error").put("error", new org.json.JSONObject()
                .put("type", type).put("message", message)).toString().getBytes(StandardCharsets.UTF_8);
        out.write(("HTTP/1.1 " + code + " Error\r\nContent-Type: application/json\r\nContent-Length: " + b.length
                + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        out.write(b);
        out.flush();
    }
}
