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

    /** rawPath: /llm/deepseek/a/v1/messages?beta=true. Already checked to carry Buddy's token. */
    static void handle(Context c, String method, String rawPath, Map<String, String> headers, byte[] body,
                       OutputStream out) throws Exception {
        String[] parts = rawPath.substring(PATH.length()).split("/", 3);
        Providers.P p = parts.length >= 2 ? Providers.find(parts[0]) : null;
        boolean anthropic = parts.length >= 2 && "a".equals(parts[1]);
        String base = p == null ? "" : anthropic ? p.anthropic : "o".equals(parts[1]) ? p.openai : "";
        String key = p == null ? null : Secrets.get(c, Providers.secretName(p.id));
        if (key == null || base.isEmpty()) {
            error(out, 401, "No API key saved in Buddy for this service. Add one in Buddy's Settings, under API keys.");
            return;
        }
        String rest = parts.length == 3 ? "/" + parts[2] : "";
        HttpURLConnection con = (HttpURLConnection) new URL(trim(base) + rest).openConnection();
        con.setRequestMethod(method);
        con.setConnectTimeout(30_000);
        con.setReadTimeout(10 * 60_000);
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
        if (body.length > 0) {
            con.setDoOutput(true);
            con.setFixedLengthStreamingMode(body.length);
            try (OutputStream o = con.getOutputStream()) {
                o.write(body);
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

    private static void error(OutputStream out, int code, String message) throws Exception {
        byte[] b = new org.json.JSONObject().put("type", "error").put("error", new org.json.JSONObject()
                .put("type", "authentication_error").put("message", message)).toString().getBytes(StandardCharsets.UTF_8);
        out.write(("HTTP/1.1 " + code + " Error\r\nContent-Type: application/json\r\nContent-Length: " + b.length
                + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
        out.write(b);
        out.flush();
    }
}
