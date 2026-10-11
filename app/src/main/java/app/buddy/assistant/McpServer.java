package app.buddy.assistant;

import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Minimal MCP server (Streamable HTTP, JSON responses only) bound to 127.0.0.1.
 * Also accepts POST /event from the helper in Buddy's Linux to stream Claude Code output to the bubble.
 * Every request must carry "Authorization: Bearer <token>".
 */
final class McpServer {
    static final int PORT = 8765;
    private static final String TAG = "BuddyMcp";
    private static final String DEFAULT_PROTOCOL = "2025-06-18";

    interface Handler {
        JSONArray listTools() throws JSONException;

        JSONObject callTool(String name, JSONObject args) throws Exception;

        void onEvent(JSONObject event);

        /** The attachment file for an id, or null. */
        java.io.File attachment(String id);
    }

    private final String token;
    private final Handler handler;
    private final ExecutorService pool = Executors.newCachedThreadPool();
    private volatile ServerSocket socket;
    private volatile boolean running;

    McpServer(String token, Handler handler) {
        this.token = token;
        this.handler = handler;
    }

    void start() {
        running = true;
        Thread t = new Thread(this::acceptLoop, "buddy-mcp");
        t.setDaemon(true);
        t.start();
    }

    void stop() {
        running = false;
        try {
            if (socket != null) socket.close();
        } catch (IOException ignored) {
        }
        pool.shutdownNow();
    }

    private void acceptLoop() {
        while (running) {
            try {
                ServerSocket ss = new ServerSocket();
                ss.setReuseAddress(true);
                ss.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), PORT));
                socket = ss;
                while (running) {
                    final Socket s = ss.accept();
                    pool.execute(() -> handle(s));
                }
            } catch (IOException e) {
                if (!running) return;
                Log.w(TAG, "server error, retrying", e);
                try {
                    Thread.sleep(2000);
                } catch (InterruptedException ie) {
                    return;
                }
            }
        }
    }

    private void handle(Socket s) {
        try {
            s.setSoTimeout(30000);
            InputStream in = new BufferedInputStream(s.getInputStream());
            OutputStream out = s.getOutputStream();

            String requestLine = readLine(in);
            if (requestLine == null || requestLine.isEmpty()) return;
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) return;
            String method = parts[0];
            String path = parts[1];
            int q = path.indexOf('?');
            if (q >= 0) path = path.substring(0, q);

            Map<String, String> headers = new HashMap<>();
            String line;
            while ((line = readLine(in)) != null && !line.isEmpty()) {
                int c = line.indexOf(':');
                if (c > 0) {
                    headers.put(line.substring(0, c).trim().toLowerCase(Locale.ROOT), line.substring(c + 1).trim());
                }
            }
            byte[] body = readBody(in, headers);

            if (!("Bearer " + token).equals(headers.get("authorization"))) {
                send(out, 401, "application/json", "{\"error\":\"unauthorized\"}");
                return;
            }

            if ("/event".equals(path) && "POST".equals(method)) {
                try {
                    handler.onEvent(new JSONObject(new String(body, StandardCharsets.UTF_8)));
                } catch (JSONException e) {
                    Log.w(TAG, "bad event", e);
                }
                send(out, 204, null, null);
            } else if ("/mcp".equals(path) && "POST".equals(method)) {
                String text = new String(body, StandardCharsets.UTF_8).trim();
                if (text.startsWith("[")) {
                    JSONArray batch = new JSONArray(text);
                    JSONArray replies = new JSONArray();
                    for (int i = 0; i < batch.length(); i++) {
                        JSONObject r = rpc(batch.getJSONObject(i));
                        if (r != null) replies.put(r);
                    }
                    if (replies.length() == 0) send(out, 202, null, null);
                    else send(out, 200, "application/json", replies.toString());
                } else {
                    JSONObject r = rpc(new JSONObject(text));
                    if (r == null) send(out, 202, null, null);
                    else send(out, 200, "application/json", r.toString());
                }
            } else if ("/mcp".equals(path) && "DELETE".equals(method)) {
                send(out, 200, null, null);
            } else if ("/mcp".equals(path)) {
                // No server-initiated SSE stream.
                send(out, 405, null, null);
            } else if (path.startsWith("/file/") && "GET".equals(method)) {
                // an attachment, fetched by the helper before the agent runs
                java.io.File f = handler.attachment(path.substring(6));
                if (f == null || !f.isFile()) send(out, 404, null, null);
                else sendFile(out, f);
            } else if ("/health".equals(path)) {
                send(out, 200, "application/json", "{\"ok\":true}");
            } else {
                send(out, 404, null, null);
            }
        } catch (Exception e) {
            Log.w(TAG, "request failed", e);
        } finally {
            try {
                s.close();
            } catch (IOException ignored) {
            }
        }
    }

    /** Returns null for notifications. */
    private JSONObject rpc(JSONObject req) throws JSONException {
        if (!req.has("id")) return null;
        Object id = req.get("id");
        String method = req.optString("method");
        JSONObject params = req.optJSONObject("params");
        if (params == null) params = new JSONObject();

        JSONObject res = new JSONObject().put("jsonrpc", "2.0").put("id", id);
        try {
            switch (method) {
                case "initialize": {
                    JSONObject result = new JSONObject()
                            .put("protocolVersion", params.optString("protocolVersion", DEFAULT_PROTOCOL))
                            .put("capabilities", new JSONObject().put("tools", new JSONObject()))
                            .put("serverInfo", new JSONObject().put("name", "buddy-phone").put("version", BuildInfo.VERSION))
                            .put("instructions", "Tools to see and operate this Android phone's screen. "
                                    + "Prefer read_screen over screenshot. Call confirm before anything irreversible or outward-facing.");
                    return res.put("result", result);
                }
                case "ping":
                    return res.put("result", new JSONObject());
                case "tools/list":
                    return res.put("result", new JSONObject().put("tools", handler.listTools()));
                case "tools/call": {
                    String name = params.optString("name");
                    JSONObject args = params.optJSONObject("arguments");
                    if (args == null) args = new JSONObject();
                    JSONObject result;
                    try {
                        result = handler.callTool(name, args);
                    } catch (Exception e) {
                        result = new JSONObject()
                                .put("content", new JSONArray().put(new JSONObject()
                                        .put("type", "text").put("text", "Error: " + e.getMessage())))
                                .put("isError", true);
                    }
                    return res.put("result", result);
                }
                default:
                    return res.put("error", new JSONObject().put("code", -32601).put("message", "Method not found: " + method));
            }
        } catch (Exception e) {
            return res.put("error", new JSONObject().put("code", -32603).put("message", String.valueOf(e.getMessage())));
        }
    }

    private static void send(OutputStream out, int code, String type, String body) throws IOException {
        byte[] b = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
        StringBuilder h = new StringBuilder();
        h.append("HTTP/1.1 ").append(code).append(' ').append(reason(code)).append("\r\n");
        if (type != null) h.append("Content-Type: ").append(type).append("; charset=utf-8\r\n");
        h.append("Content-Length: ").append(b.length).append("\r\n");
        h.append("Connection: close\r\n\r\n");
        out.write(h.toString().getBytes(StandardCharsets.US_ASCII));
        out.write(b);
        out.flush();
    }

    private static void sendFile(OutputStream out, java.io.File f) throws IOException {
        String h = "HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nContent-Length: " + f.length()
                + "\r\nConnection: close\r\n\r\n";
        out.write(h.getBytes(StandardCharsets.US_ASCII));
        try (java.io.InputStream in = new java.io.FileInputStream(f)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
        out.flush();
    }

    private static String reason(int code) {
        switch (code) {
            case 200: return "OK";
            case 202: return "Accepted";
            case 204: return "No Content";
            case 401: return "Unauthorized";
            case 404: return "Not Found";
            case 405: return "Method Not Allowed";
            default: return "Status";
        }
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') break;
            if (b != '\r') buf.write(b);
            if (buf.size() > 65536) throw new IOException("line too long");
        }
        if (b == -1 && buf.size() == 0) return null;
        return buf.toString("UTF-8");
    }

    private static byte[] readBody(InputStream in, Map<String, String> headers) throws IOException {
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        String te = headers.get("transfer-encoding");
        if (te != null && te.toLowerCase(Locale.ROOT).contains("chunked")) {
            while (true) {
                String sizeLine = readLine(in);
                if (sizeLine == null) break;
                int semi = sizeLine.indexOf(';');
                int size = Integer.parseInt((semi >= 0 ? sizeLine.substring(0, semi) : sizeLine).trim(), 16);
                if (size == 0) {
                    String l;
                    while ((l = readLine(in)) != null && !l.isEmpty()) { /* trailers */ }
                    break;
                }
                readFully(in, body, size);
                readLine(in);
            }
        } else {
            String cl = headers.get("content-length");
            if (cl != null) readFully(in, body, Integer.parseInt(cl.trim()));
        }
        return body.toByteArray();
    }

    private static void readFully(InputStream in, ByteArrayOutputStream out, int n) throws IOException {
        if (n > 8 * 1024 * 1024) throw new IOException("body too large");
        byte[] buf = new byte[8192];
        while (n > 0) {
            int r = in.read(buf, 0, Math.min(buf.length, n));
            if (r == -1) throw new IOException("unexpected end of body");
            out.write(buf, 0, r);
            n -= r;
        }
    }
}
