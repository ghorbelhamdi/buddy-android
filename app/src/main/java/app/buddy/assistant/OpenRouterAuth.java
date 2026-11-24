package app.buddy.assistant;

import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Sign in with OpenRouter, no key to copy: OpenRouter's OAuth with PKCE (openrouter.ai/docs, "OAuth PKCE"). The
 * browser opens OpenRouter's page; once the user allows it, OpenRouter sends the browser back to
 * http://localhost:8765/openrouter-callback, which Buddy's own server on this phone answers (McpServer), and Buddy
 * trades the code for a key of its own. The key is saved where a pasted OpenRouter key goes (Providers, Secrets).
 * OpenRouter's docs allow localhost callbacks on any port.
 */
final class OpenRouterAuth {
    static final String PATH = "/openrouter-callback";
    static final String CALLBACK = "http://localhost:" + McpServer.PORT + PATH;
    /** OpenRouter's codes last 10 minutes; a sign-in left longer than that is over. */
    private static final long TTL_MS = 10 * 60_000L;

    /** The one sign-in waiting for its callback (a new one replaces it). */
    private static String state, verifier;
    private static long startedAt;
    /** Started from "OpenRouter's free models": the pickers then show only those. */
    private static boolean freeOnly;
    /** A trade in progress, for the screens. */
    static volatile boolean busy;
    /** The last free sign-in found no free model in OpenRouter's list. */
    private static volatile boolean noFree;

    private OpenRouterAuth() {
    }

    /**
     * The address to open in the browser for a new sign-in, or null when Buddy's server isn't running (it runs with
     * Buddy's accessibility service): nothing would answer the callback.
     */
    static synchronized String start(boolean free) {
        if (BuddyService.get() == null) return null;
        verifier = random(32);
        state = random(16);
        startedAt = System.currentTimeMillis();
        freeOnly = free;
        // the state rides in the callback address itself: OpenRouter's documented redirect carries only the code
        return "https://openrouter.ai/auth?callback_url=" + enc(CALLBACK + "?state=" + state) + "&code_challenge="
                + challenge(verifier) + "&code_challenge_method=S256&key_label=Buddy";
    }

    /** 32 random bytes → 43 characters, as RFC 7636 asks of a verifier. */
    static String random(int bytes) {
        byte[] b = new byte[bytes];
        new SecureRandom().nextBytes(b);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    /** S256: the verifier's SHA-256, base64url without padding (RFC 7636, 4.2). */
    static String challenge(String verifier) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(h);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String enc(String s) {
        try {
            return java.net.URLEncoder.encode(s, "UTF-8");
        } catch (Exception e) {
            return s;
        }
    }

    /**
     * The browser came back (McpServer, no token: it's the browser): only the pending sign-in's own state is taken,
     * once, within its 10 minutes. The page says nothing of what came in; Buddy says how it went.
     */
    static String callback(Context c, String query) {
        String code = null, got = null;
        for (String kv : query == null ? new String[0] : query.split("&")) {
            int e = kv.indexOf('=');
            if (e <= 0) continue;
            String k = kv.substring(0, e), v;
            try {
                v = java.net.URLDecoder.decode(kv.substring(e + 1), "UTF-8");
            } catch (Exception x) {
                continue;
            }
            if ("code".equals(k)) code = v;
            else if ("state".equals(k)) got = v;
        }
        final String v;
        final boolean free;
        synchronized (OpenRouterAuth.class) {
            boolean ok = state != null && state.equals(got) && code != null && !code.isEmpty()
                    && System.currentTimeMillis() - startedAt < TTL_MS;
            if (!ok) return page(c.getString(R.string.conn_or_page_expired));
            v = verifier;
            free = freeOnly;
            state = verifier = null; // single use
        }
        busy = true;
        Connections.changed();
        final String theCode = code;
        final Context app = c.getApplicationContext();
        new Thread(() -> exchange(app, theCode, v, free), "openrouter-sign-in").start();
        return page(c.getString(R.string.conn_or_page_done));
    }

    private static String page(String text) {
        return "<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,"
                + "initial-scale=1\"><title>Buddy</title></head><body style=\"font-family:sans-serif;margin:32px;font-size:18px\">"
                + "<p>" + android.text.TextUtils.htmlEncode(text) + "</p></body></html>";
    }

    /** The code for a key (POST /api/v1/auth/keys), then the key saved and checked; any error in OpenRouter's words. */
    private static void exchange(Context c, String code, String verifier, boolean free) {
        Handler main = new Handler(Looper.getMainLooper());
        noFree = false;
        try {
            String key = trade(code, verifier);
            Providers.P p = save(c, key, free);
            main.post(() -> {
                busy = false;
                Connections.saved(c, p, true);
                if (noFree) Toast.makeText(c, R.string.conn_or_no_free, Toast.LENGTH_LONG).show();
                // back to Buddy from the browser (Buddy's accessibility service may start a screen from the background)
                c.startActivity(new Intent(c, ConnectionActivity.class).putExtra("id", p.id)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            });
        } catch (Exception e) {
            String why = String.valueOf(e.getMessage());
            main.post(() -> {
                busy = false;
                Connections.changed();
                Toast.makeText(c, c.getString(R.string.conn_or_failed, why), Toast.LENGTH_LONG).show();
            });
        }
    }

    /** The request OpenRouter's docs give for the trade. */
    static JSONObject tradeBody(String code, String verifier) throws Exception {
        return new JSONObject().put("code", code).put("code_verifier", verifier).put("code_challenge_method", "S256");
    }

    private static String trade(String code, String verifier) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL("https://openrouter.ai/api/v1/auth/keys").openConnection();
        try {
            con.setRequestMethod("POST");
            con.setConnectTimeout(15_000);
            con.setReadTimeout(30_000);
            con.setDoOutput(true);
            con.setRequestProperty("Content-Type", "application/json");
            try (OutputStream o = con.getOutputStream()) {
                o.write(tradeBody(code, verifier).toString().getBytes(StandardCharsets.UTF_8));
            }
            int status = con.getResponseCode();
            InputStream in = status >= 400 ? con.getErrorStream() : con.getInputStream();
            String body = in == null ? "" : Connections.read(in);
            String key = status < 300 ? new JSONObject(body).optString("key") : "";
            if (key.isEmpty()) {
                String said = Relay.errorText(body);
                String text = said.isEmpty() ? body.trim() : said;
                // (OpenRouter's error repeats the status as its code: "400 Invalid code" rather than "400 400 …")
                throw new Exception(text.startsWith(status + " ") ? text : (status + " " + text).trim());
            }
            return key;
        } finally {
            con.disconnect();
        }
    }

    /**
     * Saves the key as the OpenRouter service: the one there is gets the new key (no second OpenRouter), or a new one
     * with the preset's addresses. From "OpenRouter's free models", the pickers show only the free ones (":free").
     */
    private static Providers.P save(Context c, String key, boolean free) throws Exception {
        Providers.P p = null;
        for (Providers.P q : Providers.all()) if (Connections.isOpenRouter(q)) p = q;
        String[] preset = null;
        for (String[] pr : Providers.PRESETS) if ("OpenRouter".equals(pr[0])) preset = pr;
        String an = p != null ? p.anthropic : preset[1], oa = p != null ? p.openai : preset[2];
        JSONArray models = Providers.list(key, an, oa);
        boolean adding = p == null;
        if (adding) {
            p = new Providers.P(Providers.newId("OpenRouter"));
            p.name = "OpenRouter";
            p.anthropic = an;
            p.openai = oa;
        }
        p.models = models;
        if (free && adding) { // (an OpenRouter already there keeps the models the user picked)
            p.shown.clear();
            for (String m : Providers.chatModels(models)) if (m.endsWith(":free")) p.shown.add(m);
            if (!p.shown.isEmpty()) p.model = p.shown.iterator().next();
            // none listed (none today, or past the 300 Buddy keeps): a normal connection, and the user is told so
            else noFree = true;
        }
        Providers.save(c, p, key);
        Connections.setSignedIn(c, p.id, true);
        // the free models were picked for OpenCode (the tag on that row): its chats use them, unless it had a default
        // (only a free model: an OpenRouter saved before keeps its own choice, which may be a paid one)
        if (free && !p.shown.isEmpty() && p.model.endsWith(":free") && Prefs.agentDefault(c, "opencode").isEmpty()) {
            Prefs.setAgentDefault(c, "opencode", Providers.ref(p, p.model));
        }
        return p;
    }
}
