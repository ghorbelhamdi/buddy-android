package app.buddy.assistant;

import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * The background browser's tools (browser_*), next to the phone tools in Buddy's MCP server, so Buddy's rules apply
 * there too: a tap on a Post / Send / Pay / Delete / … button, or Enter that sends what was typed, needs the user's OK
 * (the agent's confirm just before, or an approval card), and agents never type into password, one-time code or
 * payment fields: the user does that in the chat's live view. The browser itself is in Browser.
 */
final class BrowserTools {
    /** How much page text one browser_read returns; the rest comes with from. */
    private static final int PAGE_CHARS = 14_000;
    /** A generic submit button ("Continue", "Submit") on a checkout or payment page pays. */
    private static final Pattern CHECKOUT = Pattern.compile("(?i)checkout|payment|/pay\\b|purchase|billing|kasse|paiement|pago|pagamento|zahlung|bestell");
    private static final Pattern GENERIC = Pattern.compile("(?i)\\s*(submit|continue|next|ok|go|done|weiter|continuer|suivant|continuar|siguiente|avanti|doorgaan)\\s*");
    /** Words in a page's own "Are you sure?" dialog that make it worth the user's OK. */
    private static final Pattern RISKY_DIALOG = Pattern.compile("(?iu)\\b(delete|remove|pay|purchase|buy|order|send|post|publish|"
            + "unsubscribe|cancel|transfer|erase|supprimer|payer|envoyer|löschen|bezahlen|senden|eliminar|pagar|enviar)\\b");

    private final BuddyService svc;
    /** When the agent was told a page warns that leaving loses what's typed, by chat: going again within 5 min leaves. */
    private final Map<String, Long> leaveWarned = new ConcurrentHashMap<>();

    BrowserTools(BuddyService svc) {
        this.svc = svc;
    }

    // ---------------------------------------------------------------- schema

    static void list(JSONArray t) throws JSONException {
        t.put(tool("browser_open",
                "Open a web page in Buddy's background browser (a separate browser the user can watch in this chat; "
                        + "it doesn't use the phone's screen). Returns the page as text, like browser_read.",
                new JSONObject().put("url", str("The address, e.g. https://example.com"))
                        .put("new_tab", bool("Open it in a new tab, keeping the current one")), "url"));
        t.put(tool("browser_read",
                "Read the whole page in the background browser as text. Items you can use are numbered, e.g. "
                        + "[12] button \"Add to cart\"; the numbers stay valid until the page changes. Long pages come in "
                        + "parts: pass from to read on.",
                new JSONObject().put("from", integer("Character to start at, from the previous part's note"))));
        t.put(tool("browser_click", "Tap an item on the page by its number from browser_read (a link, button, checkbox, …).",
                new JSONObject().put("ref", integer("The item's number")), "ref"));
        t.put(tool("browser_type",
                "Type into a text field by its number from browser_read (replaces its text unless append is true), or pick "
                        + "an option of a select by its text. Set submit to press Enter afterwards.",
                new JSONObject().put("ref", integer("The field's number")).put("text", str("Text to type, or the option to pick"))
                        .put("append", bool("Add to the field's text instead of replacing it"))
                        .put("submit", bool("Press Enter afterwards")), "ref", "text"));
        t.put(tool("browser_scroll", "Scroll the page in the background browser (loads more on endless pages).",
                new JSONObject().put("direction", new JSONObject().put("type", "string")
                        .put("enum", new JSONArray().put("down").put("up"))), "direction"));
        t.put(tool("browser_screenshot",
                "A picture of what the background browser shows, for images, maps or layouts browser_read can't describe.",
                new JSONObject()));
        t.put(tool("browser_back",
                "Go back to the previous page in the current tab of the background browser, like a browser's back "
                        + "button. If the page warns that leaving loses unsaved changes, Buddy stays the first time and "
                        + "says so. Returns where the tab is now, or that there's no previous page; then call "
                        + "browser_read.", new JSONObject()));
        t.put(tool("browser_tabs",
                "Manage the background browser's tabs (this chat's own): with no arguments, list them as numbered "
                        + "lines with title and address, the current one marked; with switch_to, make that tab current "
                        + "(the browser_* tools act on the current tab); with close, close it. Tabs open with "
                        + "browser_open new_tab or when a page opens a link in a new tab.",
                new JSONObject().put("switch_to", integer("Number of the tab to switch to, from the list"))
                        .put("close", integer("Number of the tab to close, from the list"))));
    }

    private static JSONObject tool(String name, String desc, JSONObject properties, String... required) throws JSONException {
        JSONObject schema = new JSONObject().put("type", "object").put("properties", properties);
        if (required.length > 0) {
            JSONArray r = new JSONArray();
            for (String s : required) r.put(s);
            schema.put("required", r);
        }
        return new JSONObject().put("name", name).put("description", desc).put("inputSchema", schema);
    }

    private static JSONObject str(String d) throws JSONException {
        return new JSONObject().put("type", "string").put("description", d);
    }

    private static JSONObject integer(String d) throws JSONException {
        return new JSONObject().put("type", "integer").put("description", d);
    }

    private static JSONObject bool(String d) throws JSONException {
        return new JSONObject().put("type", "boolean").put("description", d);
    }

    // -------------------------------------------------------------- dispatch

    /** A browser tool call (tool thread): text, or for browser_screenshot an image. */
    JSONObject call(String name, JSONObject a) throws Exception {
        String chat = McpServer.CALLER_CHAT.get();
        if (Triggers.ruleRun(chat)) {
            // a rule's run works for someone else's message: a page could carry the user's data away
            return text("The background browser isn't available in a notification rule's run.");
        }
        if (!Browser.available(svc)) {
            return text("The background browser is off. Use the phone instead, or ask the user to turn on Background "
                    + "browser in Buddy's Settings.");
        }
        // the user took over (the live view, or the phone): wait like the phone tools do
        String held = svc.awaitResume(name, false);
        if (held != null) return text(held);
        String key = Browser.key(chat);
        if (Browser.takeTouched(key) && !"browser_read".equals(name) && !"browser_screenshot".equals(name)
                && !"browser_tabs".equals(name)) {
            return text("PAGE CHANGED: the user used the browser in the live view, so the page may have changed and item "
                    + "numbers are stale. Nothing was done. Call browser_read, then continue.");
        }
        String why = Browser.ensure(svc, 150_000);
        if (why != null) return text(why);
        Browser.used();
        Browser.agentUsed(key);
        String dialog = pendingDialog(key, false);
        if (dialog != null && !"browser_read".equals(name)) return text(dialog);
        switch (name) { // the bubble says what's going on (a tap says its button once it's known)
            case "browser_open": step(key, svc.getString(R.string.activity_browser_opening), a.optString("url")); break;
            case "browser_read":
            case "browser_screenshot": step(key, svc.getString(R.string.activity_browser_reading)); break;
            case "browser_type": step(key, svc.getString(R.string.activity_typing)); break;
            case "browser_scroll": step(key, svc.getString(R.string.activity_scrolling)); break;
            default: break;
        }
        switch (name) {
            case "browser_open": return text(open(key, a.getString("url"), a.optBoolean("new_tab")));
            case "browser_read": return text((dialog == null ? "" : dialog + "\n\n") + read(key, Math.max(0, a.optInt("from", 0))));
            case "browser_click": return text(click(key, a.getInt("ref")));
            case "browser_type": return text(type(key, a.getInt("ref"), a.optString("text", ""), a.optBoolean("append"), a.optBoolean("submit")));
            case "browser_scroll": return text(scroll(key, "up".equals(a.optString("direction"))));
            case "browser_screenshot": return screenshot(key);
            case "browser_back": {
                if (!Browser.back(key)) return text("There's no previous page in this tab.");
                String d = pendingDialog(key, false);
                return text(d != null ? d : after(key, "Went back.", null));
            }
            case "browser_tabs": return text(tabs(key, a));
            default: throw new IllegalArgumentException("Unknown tool " + name);
        }
    }

    private static JSONObject text(String s) throws JSONException {
        return new JSONObject().put("content", new JSONArray().put(new JSONObject().put("type", "text").put("text", s)));
    }

    // ----------------------------------------------------------------- tools

    private String open(String key, String url, boolean newTab) throws Exception {
        url = url == null ? "" : url.trim();
        if (!url.matches("(?i)^[a-z][a-z0-9+.-]*:.*")) url = "https://" + url;
        if (!url.matches("(?i)^https?://.+")) return "Only http and https pages open in the background browser.";
        if (newTab && Browser.hasTab(key)) {
            String t = Browser.call("Target.createTarget", new JSONObject().put("url", "about:blank"), null, 10_000).getString("targetId");
            Thread.sleep(200);
            Browser.useTab(key, t);
        }
        String err = Browser.navigate(key, url);
        if (err != null) return "Couldn't open " + url + ": " + err + ". Check the address, or use the phone instead.";
        String dialog = pendingDialog(key, false);
        return (dialog == null ? "" : dialog + "\n\n") + read(key, 0);
    }

    /** The page's text from character from, with what it asks for (a sign-in, a bot check) on top. */
    private String read(String key, int from) throws Exception {
        Browser.settle(key, 5000);
        String all = String.valueOf(Browser.eval(key, "__buddy.read()"));
        String[] page = Browser.page(key);
        // the tab's title in Chromium's list can lag behind the page (it shows the address until it catches up)
        Object title = Browser.eval(key, "document.title");
        StringBuilder sb = new StringBuilder();
        sb.append("Page: ").append(title instanceof String && !((String) title).isEmpty() ? title : page == null ? "" : page[1]).append("\nURL: ").append(page == null ? "" : page[0]).append('\n');
        String sig = signals(key);
        if (sig != null) sb.append('\n').append(sig).append('\n');
        String tips = Tips.once(svc, McpServer.CALLER_CHAT.get(), host(key));
        if (!tips.isEmpty()) sb.append(tips);
        if (from >= all.length() && from > 0) return sb.append("\n(The page has no more text after character ").append(from).append(".)").toString();
        int end = Math.min(all.length(), from + PAGE_CHARS);
        if (end < all.length()) { // end on a line
            int nl = all.lastIndexOf('\n', end);
            if (nl > from + PAGE_CHARS / 2) end = nl;
        }
        sb.append('\n');
        if (from > 0) sb.append("(…from character ").append(from).append(")\n");
        sb.append(all.isEmpty() ? "(The page shows no text yet. Wait a moment and read again, or take a browser_screenshot.)" : all.substring(from, end));
        if (end < all.length()) {
            sb.append("\n\n(Part of the page: characters ").append(from).append('-').append(end).append(" of ").append(all.length())
                    .append(". Call browser_read with from=").append(end).append(" for more.)");
        }
        return sb.toString();
    }

    /** What the page asks of the agent: a sign-in or a bot check (both are the user's to do), or null. */
    private String signals(String key) {
        try {
            JSONObject s = (JSONObject) Browser.eval(key, "__buddy.signals()");
            String site = host(key);
            if (s.optBoolean("bot")) {
                return "BOT CHECK: " + site + " shows a captcha or a robot check. Don't try to solve it. Ask the user (ask) to "
                        + "solve it, with browser_highlight on the captcha's item (Buddy opens the browser for them with it "
                        + "marked) and options like [\"Done\", \"Use the phone instead\"], or switch to the phone (open_url).";
            }
            if (s.optBoolean("blocked")) {
                return "BLOCKED BY THE SITE: " + site + " refuses this browser. Use the phone instead (open_url).";
            }
            if (s.optBoolean("login")) {
                return "SIGN-IN: this page asks to sign in. Never type passwords or codes. Ask the user (ask) to sign in "
                        + "themselves, e.g. \"Please sign in to " + site + ", then tap Done\" with options [\"Done\", \"Use "
                        + "the phone instead\"] and browser_highlight on the fields and the sign-in button (call browser_read "
                        + "for their numbers): Buddy opens the browser for them with those marked. Signing in "
                        + "with Google often fails in this browser: email and password works better. Or do this on the "
                        + "phone (open_url), where the user may be signed in already.";
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private String click(String key, int ref) throws Exception {
        if (svc.bubble() != null && svc.bubble().confirming()) {
            return "BLOCKED: a card is waiting for the user's answer. Wait for it; don't tap anything.";
        }
        Object o = Browser.eval(key, "__buddy.target(" + ref + ")");
        if (!(o instanceof JSONObject) || ((JSONObject) o).optBoolean("missing")) return stale(ref);
        JSONObject t = (JSONObject) o;
        String label = t.optString("label", "").trim();
        if (!t.isNull("covered") && !t.optString("covered").isEmpty()) {
            return "Not tapped: [" + ref + "] is covered by " + t.optString("covered") + ". Close or tap that first (a cookie "
                    + "banner or a pop-up, say), then try again.";
        }
        String[] before = Browser.page(key);
        String host = host(key);
        boolean approved = false;
        String risky = riskyTap(t, before == null ? "" : before[0]);
        boolean allow = Guard.listed(svc, "allow", label, null); // the user said: never ask for this one
        if (!allow && (Guard.listed(svc, "ask", label, null) || (Prefs.guardBuiltin(svc) && risky != null))) {
            String name = label.isEmpty() ? risky : label;
            String blocked = approve("Tap “" + name + "”", svc.getString(R.string.browser_card_tap, name, host),
                    t.optString("draft", ""), Guard.sendsText(name));
            if (blocked != null) return blocked;
            approved = true;
        }
        step(key, label.isEmpty() ? svc.getString(R.string.activity_tapping_plain)
                : svc.getString(R.string.activity_tapping, label));
        Browser.eval(key, "__buddy.armClick(" + ref + ")");
        Browser.click(key, t.getDouble("x"), t.getDouble("y"));
        Thread.sleep(500);
        String dialog = pendingDialog(key, approved), again = "";
        if (dialog == null && lostClick(key, before)) {
            // the page re-drew under the pointer (a text box losing its focus re-renders the form), so the item never
            // got the click: once more where it is now (drawn again as a new element, it's the one with the same role
            // and label), unless it changed (disabled, busy, gone or covered)
            Browser.eval(key, "__buddy.adopt(" + ref + ", " + JSONObject.quote(t.optString("role")) + ", " + JSONObject.quote(label) + ")");
            Object o2 = Browser.eval(key, "__buddy.target(" + ref + ")");
            JSONObject t2 = o2 instanceof JSONObject ? (JSONObject) o2 : null;
            if (t2 != null && !t2.optBoolean("missing") && !t2.optBoolean("disabled")
                    && (t2.isNull("covered") || t2.optString("covered").isEmpty())) {
                Browser.eval(key, "__buddy.armClick(" + ref + ")");
                Browser.click(key, t2.getDouble("x"), t2.getDouble("y"));
                Thread.sleep(500);
                dialog = pendingDialog(key, approved);
                again = dialog == null && lostClick(key, before)
                        ? " The tap didn't reach it, twice (the page moved under it); take a browser_screenshot to see why."
                        : " (The first tap didn't reach it, as the page moved under it, so Buddy tapped once more.)";
            } else {
                again = " The tap may not have reached it (the page changed under it): check with browser_read before trying again.";
            }
        }
        if (dialog == null) Browser.settle(key, 10_000);
        return after(key, "Tapped [" + ref + "]" + (label.isEmpty() ? "" : " “" + label + "”") + "." + again, before)
                + (dialog == null ? "" : "\n" + dialog);
    }

    /** No click event reached the item armed with armClick, and the tab is still on the same page. */
    private static boolean lostClick(String key, String[] before) {
        try {
            String[] now = Browser.page(key);
            if (before == null || now == null || !now[0].equals(before[0])) return false;
            return Boolean.FALSE.equals(Browser.eval(key, "__buddy.clickReached()"));
        } catch (Exception e) {
            return false;
        }
    }

    /** Why a tap on this item needs the user's OK (the label that makes it risky), or null. */
    private static String riskyTap(JSONObject t, String url) {
        String label = t.optString("label", "");
        String r = Guard.riskyText(label);
        if (r == null) r = Guard.riskyText(t.optString("title", ""));
        // an icon button known only by its id or class ("send-button"), as on the phone
        boolean bare = label.isEmpty() || GENERIC.matcher(label).matches() || label.equals(t.optString("ids").trim());
        if (r == null && bare && !"link".equals(t.optString("role")) && Guard.riskyId(t.optString("ids", ""))) {
            r = label.isEmpty() ? ChatHub.clip(t.optString("ids"), 30) : label;
        }
        // a plain "Continue" or "Submit" that sends a payment form, or any form on a checkout page
        if (r == null && t.optBoolean("submits") && (label.isEmpty() || GENERIC.matcher(label).matches())
                && (t.optBoolean("payForm") || CHECKOUT.matcher(url + " " + t.optString("form")).find())) {
            r = label.isEmpty() ? "Submit" : label;
        }
        return r;
    }

    private String type(String key, int ref, String text, boolean append, boolean submit) throws Exception {
        JSONObject f = (JSONObject) Browser.eval(key, "__buddy.focus(" + ref + ", " + append + ")");
        if (f.optBoolean("missing")) return stale(ref);
        if (f.optBoolean("select")) {
            JSONObject c = (JSONObject) Browser.eval(key, "__buddy.choose(" + ref + ", " + JSONObject.quote(text) + ")");
            if (c.optBoolean("none")) return "No option of [" + ref + "] matches “" + text + "”. Call browser_read to see its options.";
            return "Picked “" + c.optString("chosen") + "” in [" + ref + "].";
        }
        if (f.optBoolean("notField")) {
            return "[" + ref + "] is a " + f.optString("role") + " “" + f.optString("label") + "”, not a text field. Use browser_click for it.";
        }
        if (f.has("sensitive")) {
            return "BLOCKED: [" + ref + "] is a " + f.optString("sensitive") + " field. Agents never type passwords, one-time "
                    + "codes or payment details. Ask the user (ask) to fill it in themselves, e.g. \"Please type "
                    + "it, then tap Done\" with options [\"Done\", \"I can't\"] and browser_highlight [{\"ref\": " + ref
                    + "}] (Buddy opens the browser for them with it marked), or do this step on the phone.";
        }
        if (!text.isEmpty()) Browser.insertText(key, text);
        else if (!append) Browser.key(key, "Backspace"); // empties the selected text
        String result = (text.isEmpty() ? "Emptied" : "Typed " + text.length() + " characters into") + " [" + ref + "]"
                + (f.optString("label").isEmpty() ? "" : " “" + f.optString("label") + "”") + ".";
        if (!submit) return result;
        // Enter in a search box only searches; in a message box or a form sent by a risky button it sends
        String sendLabel = f.optString("submitLabel", "");
        boolean risky = !f.optBoolean("search") && (f.optBoolean("multiline") || Guard.riskyText(sendLabel) != null);
        if (risky && Prefs.guardBuiltin(svc)) {
            String blocked = approve("Press Enter to send", svc.getString(R.string.browser_card_enter, host(key)), text, true);
            if (blocked != null) return result + " Not sent: " + blocked;
        }
        String[] before = Browser.page(key);
        Browser.key(key, "Enter");
        Thread.sleep(500);
        String dialog = pendingDialog(key, risky);
        if (dialog == null) Browser.settle(key, 10_000);
        return after(key, result + " Pressed Enter.", before) + (dialog == null ? "" : "\n" + dialog);
    }

    private String scroll(String key, boolean up) throws Exception {
        Browser.wheel(key, Browser.WIDTH / 2.0, Browser.HEIGHT / 2.0, 0, up ? -650 : 650);
        Thread.sleep(700);
        JSONArray p = (JSONArray) Browser.eval(key, "[Math.round(scrollY), document.documentElement.scrollHeight, innerHeight]");
        return "Scrolled " + (up ? "up" : "down") + String.format(Locale.ROOT, " (now at %d of %d px, %d px visible). ",
                p.optInt(0), p.optInt(1), p.optInt(2)) + "browser_read already has the whole page; new items may have loaded.";
    }

    private JSONObject screenshot(String key) throws Exception {
        byte[] jpg = Browser.screenshot(key);
        String[] page = Browser.page(key);
        return new JSONObject().put("content", new JSONArray()
                .put(new JSONObject().put("type", "image").put("mimeType", "image/jpeg")
                        .put("data", android.util.Base64.encodeToString(jpg, android.util.Base64.NO_WRAP)))
                .put(new JSONObject().put("type", "text").put("text", "The visible part of " + (page == null ? "the page" : page[0])
                        + ". To use something you see, find its number with browser_read.")));
    }

    private String tabs(String key, JSONObject a) throws Exception {
        List<JSONObject> mine = new java.util.ArrayList<>();
        String current = Browser.tab(key, false);
        for (JSONObject p : Browser.pages()) {
            String id = p.optString("targetId");
            if (id.equals(current) || !Browser.ownedByOther(key, id)) mine.add(p); // other chats' tabs are theirs
        }
        int sw = a.optInt("switch_to", 0), cl = a.optInt("close", 0);
        if (sw > 0 || cl > 0) {
            int n = sw > 0 ? sw : cl;
            if (n > mine.size()) return "There's no tab " + n + ". Call browser_tabs to list them.";
            String id = mine.get(n - 1).optString("targetId");
            if (sw > 0) {
                Browser.useTab(key, id);
                return after(key, "Switched to tab " + n + ".", null);
            }
            Browser.call("Target.closeTarget", new JSONObject().put("targetId", id), null, 5000);
            return "Closed tab " + n + ".";
        }
        if (mine.isEmpty()) return "No tabs open. Use browser_open.";
        StringBuilder sb = new StringBuilder("Tabs:\n");
        for (int i = 0; i < mine.size(); i++) {
            JSONObject p = mine.get(i);
            sb.append(i + 1).append(". ").append(p.optString("title")).append(" — ").append(p.optString("url"))
                    .append(p.optString("targetId").equals(current) ? " (current)" : "").append('\n');
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- helpers

    /** A short line after an action: where the tab is now when it moved, and what the page asks for. */
    private String after(String key, String done, String[] before) {
        String[] now = Browser.page(key);
        StringBuilder sb = new StringBuilder(done);
        boolean moved = now != null && (before == null || !now[0].equals(before[0]));
        if (moved) {
            Object title = null;
            try {
                title = Browser.eval(key, "document.title");
            } catch (Exception ignored) {
            }
            String t = title instanceof String && !((String) title).isEmpty() ? (String) title : now[1];
            // a page with no title gives its address as the title: say it once, and keep long ones short
            sb.append(" Now on ").append(t == null || t.isEmpty() || t.equals(now[0]) ? "" : "“" + clip(t, 120) + "” ")
                    .append("(").append(clip(now[0], 150)).append(").");
            // what the new page asks for (a sign-in, a bot check); the same page said it already
            String sig = signals(key);
            if (sig != null) sb.append("\n").append(sig);
        }
        return sb.append(" Call browser_read to see the page.").toString();
    }

    private static String clip(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }

    /** Shows the step in the bubble: "Browsing reddit.com · Typing" (url: where it's going, when not there yet). */
    private void step(String key, String what) {
        step(key, what, null);
    }

    private void step(String key, String what, String url) {
        String site = host(key);
        if (url != null) {
            String h = android.net.Uri.parse(url.contains("://") ? url : "https://" + url).getHost();
            if (h != null) site = h.startsWith("www.") ? h.substring(4) : h;
        }
        svc.showActivity("?".equals(site) ? what : svc.getString(R.string.activity_browsing, site, what), key);
    }

    private static String stale(int ref) {
        return "There's no item [" + ref + "] on the page now (it changed). Call browser_read again.";
    }

    /** The site of the chat's tab, for cards and messages ("amazon.de"). */
    private static String host(String key) {
        String[] p = Browser.page(key);
        try {
            String h = p == null ? null : new java.net.URI(p[0]).getHost();
            return h == null ? "?" : h.startsWith("www.") ? h.substring(4) : h;
        } catch (Exception e) {
            return "?";
        }
    }

    /**
     * A risky action needs the user's OK: the agent's confirm just before, or a card now. Null when it may go ahead,
     * otherwise the tool's answer. what: the action in English, for the agent; card: the card's line; draft: text it
     * would send, shown on the card (may be empty).
     */
    private String approve(String what, String card, String draft, boolean send) throws InterruptedException {
        if (Guard.takeApproval(send)) return null;
        String summary = draft == null || draft.trim().isEmpty() ? card
                : svc.getString(R.string.tools_card_detail, card, svc.getString(R.string.tools_card_quote, ChatHub.clip(draft.trim(), 300)));
        String answer = svc.askConfirm(summary, false);
        if (answer.startsWith("APPROVED")) return null;
        if (answer.startsWith("CHANGES")) return "Not done: the user wants changes first. " + answer;
        return "BLOCKED by Buddy's safety rule: the user did not approve “" + what + "”. Don't retry it; ask the user what they want.";
    }

    /**
     * A JavaScript dialog the page opened (alert, confirm, leave-page): an alert is just closed, a leave-page warning keeps
     * the page the first time, an "Are you sure?" about something risky goes to the user unless the tap that opened it was
     * approved. What happened, or null for none.
     */
    private String pendingDialog(String key, boolean approved) throws Exception {
        JSONObject d = Browser.dialog(key);
        if (d == null) return null;
        String type = d.optString("type"), msg = ChatHub.clip(d.optString("message", ""), 300);
        if ("alert".equals(type)) {
            Browser.answerDialog(key, true);
            return "The page showed a message (Buddy closed it): “" + msg + "”";
        }
        if ("beforeunload".equals(type)) {
            // leaving loses what's typed on the page (an unsent post, a form): stay, unless the agent was told and goes again
            Long told = leaveWarned.remove(key);
            boolean leave = told != null && SystemClock.elapsedRealtime() - told < 5 * 60_000;
            Browser.answerDialog(key, leave);
            if (leave) {
                Thread.sleep(800);
                Browser.settle(key, 20_000);
                return "The page warned that leaving loses unsaved changes; this was the second try, so Buddy left.";
            }
            leaveWarned.put(key, SystemClock.elapsedRealtime());
            return "NOT LEFT: the page warns that leaving now loses unsaved changes (an unsent draft or a filled-in form), "
                    + "so Buddy stayed on it. Finish or send it first, or use another tab (browser_open with new_tab). To "
                    + "leave anyway and lose it, do the same again.";
        }
        if ("prompt".equals(type)) {
            Browser.answerDialog(key, false);
            return "The page asked for text in a dialog (“" + msg + "”); Buddy cancelled it. Ask the user if it's needed.";
        }
        boolean ok = approved || !RISKY_DIALOG.matcher(msg).find();
        if (!ok) {
            String answer = svc.askConfirm(svc.getString(R.string.browser_card_dialog, host(key), msg), false);
            ok = answer.startsWith("APPROVED");
        }
        Browser.answerDialog(key, ok);
        return "The page asked “" + msg + "”: Buddy answered " + (ok ? "OK" : "Cancel (the user didn't approve)") + ".";
    }
}
