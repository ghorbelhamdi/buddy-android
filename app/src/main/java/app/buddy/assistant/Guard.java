package app.buddy.assistant;

import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The hard limit behind "Buddy asks before anything risky", for the apps the user puts on it (Settings →
 * App permissions; trusted apps leave asking to the agent). Enforced here, in Buddy: before a tap lands on a
 * send / pay / delete / … button (by its text, icon description or id), or text is typed with "submit", the
 * user must have approved it: either through the agent's own confirm call just
 * before (one approval covers one action), or on a card Buddy shows right then. The agent can't tap Buddy's
 * own bubble or approval card, so it can't approve for the user.
 */
final class Guard {
    /** Words on buttons that send, pay, delete or otherwise can't be taken back, and add-to-cart buttons, so every
     * agent asks the same way before shopping, in each of Buddy's languages. */
    private static final Pattern RISKY = Pattern.compile("(?iu)^[^\\p{L}]*(?:(?:proceed|continue|go) to |passer (?:à la|au) |weiter zur |zur )?("
            + "send|post|publish|share now|tweet|reply all|submit|confirm|pay|pay now|buy|buy now|purchase|"
            + "order|place order|checkout|check out|payment|add to cart|add to basket|add to bag|add to trolley|subscribe|transfer|donate|delete|remove|erase|uninstall|"
            + "unfriend|block|report|unsubscribe|deactivate|factory reset|reset|sign out|log out|logout|call|"
            + "envoyer|envoi|publier|partager maintenant|répondre à tous|valider|confirmer|payer|acheter|commander|"
            + "ajouter au panier|passer la commande|passer commande|caisse|s'abonner|virement|transférer|supprimer|effacer|désinstaller|bloquer|signaler|se désabonner|"
            + "désactiver|réinitialiser|se déconnecter|déconnexion|appeler|"
            + "senden|absenden|bezahlen|kaufen|bestellen|löschen|entfernen|in den einkaufswagen|in den warenkorb|kasse|"
            + "enviar|pagar|comprar|eliminar|borrar|añadir al carrito|agregar al carrito|inviare|pagare|acquista|elimina|"
            + "aggiungi al carrello|invia|pubblica|paga|paga ora|compra|acquista ora|ordina|conferma|rimuovi|cancella|"
            + "disinstalla|blocca|segnala|esci|disconnetti|chiama|abbonati|iscriviti|trasferisci|procedi all'acquisto|"
            + "publicar|confirmar|pedir|realizar pedido|finalizar compra|tramitar pedido|suscribirse|transferir|"
            + "desinstalar|bloquear|denunciar|cerrar sesión|llamar|quitar|"
            + "posten|veröffentlichen|jetzt kaufen|zahlungspflichtig bestellen|abonnieren|überweisen|deinstallieren|"
            + "blockieren|melden|abmelden|anrufen|bestätigen|"
            // Portuguese, Dutch, Polish, Russian, Ukrainian, Turkish, Hindi, Indonesian, Japanese, Korean, Chinese
            + "comprar agora|fazer pedido|adicionar ao carrinho|excluir|apagar|remover|assinar|inscrever-se|denunciar|sair|ligar|"
            + "verzenden|versturen|verstuur|plaatsen|publiceren|betalen|nu betalen|kopen|nu kopen|bestellen|afrekenen|"
            + "in winkelwagen|toevoegen aan winkelwagen|verwijderen|wissen|bevestigen|overmaken|abonneren|blokkeren|"
            + "uitloggen|afmelden|bellen|"
            + "wyślij|opublikuj|zapłać|kup|kup teraz|zamów|kupuję|zamawiam|do kasy|dodaj do koszyka|usuń|potwierdź|"
            + "przelej|subskrybuj|odinstaluj|zablokuj|zgłoś|wyloguj|zadzwoń|"
            + "отправить|опубликовать|оплатить|купить|заказать|оформить заказ|в корзину|добавить в корзину|удалить|"
            + "подтвердить|перевести|подписаться|заблокировать|пожаловаться|выйти|позвонить|"
            + "надіслати|відправити|опублікувати|сплатити|купити|замовити|оформити замовлення|у кошик|додати в кошик|"
            + "додати до кошика|видалити|підтвердити|переказати|підписатися|заблокувати|поскаржитися|вийти|зателефонувати|"
            + "gönder|paylaş|yayınla|öde|şimdi öde|satın al|hemen al|sipariş ver|siparişi tamamla|sepete ekle|sil|"
            + "kaldır|onayla|abone ol|engelle|şikayet et|çıkış yap|"
            + "भेजें|पोस्ट करें|भुगतान करें|खरीदें|अभी खरीदें|ऑर्डर करें|कार्ट में जोड़ें|हटाएं|हटाएँ|मिटाएं|"
            + "पुष्टि करें|कॉल करें|"
            + "kirim|bayar|bayar sekarang|beli|beli sekarang|pesan sekarang|buat pesanan|tambah ke keranjang|"
            + "masukkan keranjang|hapus|konfirmasi|berlangganan|blokir|laporkan|telepon|"
            + "送信|送信する|投稿|投稿する|支払う|支払い|購入|購入する|今すぐ購入|注文|注文する|注文を確定する|"
            + "カートに入れる|カートに追加|削除|削除する|確定|確定する|送金|購読|アンインストール|ブロック|報告|"
            + "ログアウト|発信|"
            + "보내기|전송|게시|결제|결제하기|구매|구매하기|바로 구매|주문|주문하기|장바구니 담기|장바구니에 담기|"
            + "삭제|삭제하기|송금|이체|구독|구독하기|차단|신고|로그아웃|전화 걸기|"
            + "发送|发布|支付|付款|立即支付|立即付款|购买|立即购买|下单|提交订单|加入购物车|删除|移除|确认|转账|"
            + "订阅|卸载|拉黑|举报|退出登录|拨打|"
            + "إرسال|ارسال|أرسل|دفع|ادفع|شراء|اشتر|حذف|احذف|تأكيد|اتصال|أضف إلى السلة|إضافة إلى السلة"
            + ")(?![\\p{L}])");
    /** Of those, the ones that send or post a message, in each of Buddy's languages: a notification rule's countdown
     * may approve these (see Triggers), never paying, buying, deleting and the rest. */
    private static final Pattern SEND = Pattern.compile("(?iu)^[^\\p{L}]*("
            + "send|send now|post|publish|share now|tweet|reply|reply all|"
            + "envoyer|envoi|publier|partager maintenant|répondre|répondre à tous|senden|absenden|posten|veröffentlichen|antworten|"
            + "enviar|publicar|responder|inviare|invia|pubblica|rispondi|verzenden|versturen|verstuur|plaatsen|publiceren|"
            + "beantwoorden|wyślij|opublikuj|odpowiedz|отправить|опубликовать|ответить|надіслати|відправити|опублікувати|"
            + "відповісти|gönder|paylaş|yayınla|yanıtla|भेजें|पोस्ट करें|जवाब दें|kirim|balas|送信|送信する|投稿|投稿する|返信|"
            + "보내기|전송|게시|답장|发送|发布|回复|إرسال|ارسال|أرسل|رد"
            + ")(?![\\p{L}])");
    /** A send button known only by its id (see RISKY_ID), as riskyLabel words it ("send", "btn send", "send button"). */
    private static final Pattern SEND_ID = Pattern.compile("(?i)(^|\\s)(send|sendbutton|post|reply)(\\s|$)");
    /** Resource ids of send buttons that only have an icon (e.g. WhatsApp's "send"). */
    private static final Pattern RISKY_ID = Pattern.compile("(?i)[:/_](send|send_button|sendbutton|btn_send|delete|pay|buy|post|submit|add-to-cart|add_to_cart|addtocart)\\b");
    // long enough for a slow multi-step task between the plan's approval and its risky tap
    private static final long APPROVAL_VALID_MS = 600_000;

    private static long approvedAt, planAt;
    /** The pending approval came from a rule's countdown, not from the user: it covers sending only. */
    private static boolean approvedAuto;
    /** The last approved confirm's text: the plan the user OK'd, for the smart reviewer. */
    private static String plan;
    /**
     * The chat whose confirm the approval and the plan were (null: a session outside Buddy): they cover that chat's
     * actions only, never another agent's, least of all a notification rule's run for someone else's message.
     */
    private static String approvedFor, planFor;
    /** What the pending approval's confirm said: a message whose text is known must be that one (see takeApproval). */
    private static String approvedText;

    /** Apps that start on "hard limit": messaging, email, social, calls, shopping, payments, banking. */
    private static final java.util.Set<String> HARD_BY_DEFAULT = new java.util.HashSet<>(java.util.Arrays.asList(
            "com.whatsapp", "com.whatsapp.w4b", "com.google.android.apps.messaging", "com.samsung.android.messaging",
            "com.android.mms", "org.telegram.messenger", "org.thoughtcrime.securesms", "com.facebook.orca",
            "com.facebook.katana", "com.instagram.android", "com.snapchat.android", "com.twitter.android",
            "com.zhiliaoapp.musically", "com.linkedin.android", "com.discord", "com.Slack", "com.viber.voip",
            "com.google.android.gm", "com.microsoft.office.outlook", "com.yahoo.mobile.client.android.mail",
            "com.google.android.dialer", "com.android.dialer", "com.samsung.android.dialer", "com.oneplus.dialer",
            "com.android.vending", "com.amazon.mShop.android.shopping", "com.ebay.mobile", "com.paypal.android.p2pmobile",
            "com.revolut.revolut", "de.number26.android", "com.google.android.apps.walletnfcrel", "com.wise.android",
            "com.transferwise.android", "com.klarna.android", "com.coinbase.android", "com.binance.dev"));
    private static final Pattern HARD_NAME = Pattern.compile(
            "(?i)(bank|banking|pay|wallet|finance|credit|sparkasse|volksbank|boursorama|revolut|n26|crypto|trading|broker)");

    // ------------------------------------------------------------------ the user's button lists

    /** A rule: a button label, in one app (pkg) or in all apps (pkg null). */
    static final class Rule {
        final String label, pkg, app;

        Rule(String label, String pkg, String app) {
            this.label = label;
            this.pkg = pkg;
            this.app = app;
        }

        org.json.JSONObject json() throws org.json.JSONException {
            return new org.json.JSONObject().put("label", label).put("pkg", pkg == null ? "" : pkg).put("app", app == null ? "" : app);
        }

        static Rule of(org.json.JSONObject o) {
            String pkg = o.optString("pkg");
            return new Rule(o.optString("label"), pkg.isEmpty() ? null : pkg, o.optString("app"));
        }

        String describe() {
            return pkg == null ? BuddyApp.str(R.string.guard_rule_all_apps, label) : label + " · " + (app == null || app.isEmpty() ? pkg : app);
        }
    }

    /** "allow" (never ask) or "ask" (always ask), or "seen" (buttons the agent tapped, newest first). */
    static java.util.List<Rule> rules(android.content.Context c, String list) {
        java.util.List<Rule> out = new java.util.ArrayList<>();
        try {
            org.json.JSONArray a = new org.json.JSONArray(Prefs.guardList(c, list));
            for (int i = 0; i < a.length(); i++) out.add(Rule.of(a.getJSONObject(i)));
        } catch (Exception ignored) {
        }
        return out;
    }

    static void saveRules(android.content.Context c, String list, java.util.List<Rule> rules) {
        org.json.JSONArray a = new org.json.JSONArray();
        try {
            for (Rule r : rules) a.put(r.json());
        } catch (Exception ignored) {
        }
        Prefs.setGuardList(c, list, a.toString());
    }

    static void addRule(android.content.Context c, String list, Rule r) {
        java.util.List<Rule> rules = rules(c, list);
        for (java.util.Iterator<Rule> it = rules.iterator(); it.hasNext(); ) {
            Rule x = it.next();
            if (norm(x.label).equals(norm(r.label)) && java.util.Objects.equals(x.pkg, r.pkg)) it.remove();
        }
        rules.add(0, r);
        saveRules(c, list, rules);
        // a button is on one list at most
        String other = "allow".equals(list) ? "ask" : "allow";
        java.util.List<Rule> o = rules(c, other);
        boolean changed = false;
        for (java.util.Iterator<Rule> it = o.iterator(); it.hasNext(); ) {
            Rule x = it.next();
            if (norm(x.label).equals(norm(r.label)) && java.util.Objects.equals(x.pkg, r.pkg)) {
                it.remove();
                changed = true;
            }
        }
        if (changed) saveRules(c, other, o);
    }

    /** Remember a button the agent tapped, for the lists' suggestions. */
    static void remember(android.content.Context c, String label, String pkg, String app) {
        if (label == null || pkg == null) return;
        java.util.List<Rule> seen = rules(c, "seen");
        for (java.util.Iterator<Rule> it = seen.iterator(); it.hasNext(); ) {
            Rule x = it.next();
            if (norm(x.label).equals(norm(label)) && pkg.equals(x.pkg)) it.remove();
        }
        seen.add(0, new Rule(label, pkg, app));
        while (seen.size() > 150) seen.remove(seen.size() - 1);
        saveRules(c, "seen", seen);
    }

    /** Is this button on the list, for this app or all apps? */
    static boolean listed(android.content.Context c, String list, String label, String pkg) {
        if (label == null) return false;
        String l = norm(label);
        for (Rule r : rules(c, list)) {
            if (r.pkg != null && !r.pkg.equals(pkg)) continue;
            String x = norm(r.label);
            if (!x.isEmpty() && (l.equals(x) || l.startsWith(x + " "))) return true;
        }
        return false;
    }

    static String norm(String s) {
        return s == null ? "" : s.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    /** Labels that say nothing about what a button does (web form buttons are often just "Submit"). */
    private static final Pattern GENERIC = Pattern.compile("(?i)submit|button|btn|input|ok|go|enter|envoyer|absenden|enviar|invia");

    /** A short name for what a tap would press. A generic one ("Submit") is replaced by the button's id
     * ("add-to-cart-button" → "add to cart") or the visible text next to it, so the card says what it does. */
    static String buttonLabel(AccessibilityNodeInfo n) {
        String l = rawButtonLabel(n);
        if (l != null && !GENERIC.matcher(l.trim()).matches()) return l;
        AccessibilityNodeInfo cur = n;
        for (int i = 0; cur != null && i < 4; i++) {
            String id = cur.getViewIdResourceName();
            if (id != null) {
                String w = id.substring(id.lastIndexOf('/') + 1).replaceAll("[-_.]+", " ")
                        .replaceAll("(?i)\\b(submit|button|btn|input)\\b", " ").trim().replaceAll("\\s+", " ");
                if (w.matches("[\\p{L} ]{3,}") && !w.contains("autoid")) return w; // not generated ids ("a-autoid-3")
            }
            if (cur.isClickable()) break;
            cur = cur.getParent();
        }
        AccessibilityNodeInfo p = n.getParent(); // the visible text beside it (a form button and its label)
        for (int up = 0; p != null && up < 2; up++, p = p.getParent()) {
            for (int c = 0; c < Math.min(p.getChildCount(), 8); c++) {
                AccessibilityNodeInfo ch = p.getChild(c);
                String h = ch == null ? null : shortLabel(ch);
                if (h != null && !GENERIC.matcher(h).matches()) return h;
            }
        }
        return l;
    }

    private static String rawButtonLabel(AccessibilityNodeInfo n) {
        AccessibilityNodeInfo cur = n;
        for (int i = 0; cur != null && i < 4; i++) {
            String l = shortLabel(cur);
            if (l != null) return l;
            if (cur.isClickable()) {
                for (int c = 0; c < Math.min(cur.getChildCount(), 6); c++) {
                    AccessibilityNodeInfo ch = cur.getChild(c);
                    String h = ch == null ? null : shortLabel(ch);
                    if (h != null) return h;
                }
                String id = cur.getViewIdResourceName();
                if (id != null) return id.substring(id.lastIndexOf('/') + 1).replace('_', ' ');
                break;
            }
            cur = cur.getParent();
        }
        return null;
    }

    private static String shortLabel(AccessibilityNodeInfo n) {
        for (CharSequence cs : new CharSequence[]{n.getText(), n.getContentDescription()}) {
            if (cs == null) continue;
            String s = cs.toString().trim().replaceAll("\\s+", " ");
            if (s.isEmpty()) continue;
            return s.length() > 40 ? s.substring(0, 40) + "…" : s;
        }
        return null;
    }

    /** Trust: the agent works on its own in this app, without asking the user. */
    static boolean autonomous(android.content.Context c, String pkg) {
        return pkg != null && "trust".equals(mode(c, pkg, null));
    }

    /** "hard": Buddy enforces approval in this app; "smart": the lists, then a reviewer AI for every tap;
     *  "trust": the agent works on its own, without asking. */
    static String mode(android.content.Context c, String pkg, CharSequence label) {
        return modeWhy(c, pkg, label)[0];
    }

    /** {mode, why}: the user's choice, or the suggestion and its reason. */
    static String[] modeWhy(android.content.Context c, String pkg, CharSequence label) {
        if (pkg == null) return new String[]{"trust", ""};
        String set = Prefs.appSafety(c, pkg);
        if (set != null) return new String[]{set, c.getString(R.string.guard_why_choice)};
        if (HARD_BY_DEFAULT.contains(pkg)) return new String[]{"hard", c.getString(R.string.guard_why_category)};
        java.util.regex.Matcher m = HARD_NAME.matcher(pkg);
        if (m.find()) return new String[]{"hard", c.getString(R.string.guard_why_name, m.group(1))};
        if (label != null && (m = HARD_NAME.matcher(label)).find()) return new String[]{"hard", c.getString(R.string.guard_why_name, m.group(1))};
        try {
            android.content.pm.ApplicationInfo ai = c.getPackageManager().getApplicationInfo(pkg, 0);
            if (ai.category == android.content.pm.ApplicationInfo.CATEGORY_SOCIAL) return new String[]{"hard", c.getString(R.string.guard_why_social)};
            if (label == null) {
                CharSequence l = c.getPackageManager().getApplicationLabel(ai);
                if (l != null && (m = HARD_NAME.matcher(l)).find()) return new String[]{"hard", c.getString(R.string.guard_why_name, m.group(1))};
            }
        } catch (Exception ignored) {
        }
        // the rest follow the first-setup choice: work on its own, or ask first
        return Prefs.autonomousDefault(c) ? new String[]{"trust", c.getString(R.string.guard_why_suggested)}
                : new String[]{"hard", c.getString(R.string.guard_why_setup)};
    }

    private Guard() {
    }

    /** The agent's confirm was approved: that covers the next risky action. */
    static synchronized void approved(String summary) {
        approved(summary, false);
    }

    /**
     * auto: a notification rule's countdown approved it, not the user. Then it covers the next action only if that
     * sends something, and it isn't a plan the user OK'd (the smart reviewer doesn't get it as one).
     */
    static synchronized void approved(String summary, boolean auto) {
        approvedAt = System.currentTimeMillis();
        approvedAuto = auto;
        approvedFor = McpServer.CALLER_CHAT.get();
        approvedText = summary;
        if (auto) return;
        planAt = approvedAt;
        plan = summary;
        planFor = approvedFor;
    }

    /**
     * What the user approved for the calling chat in the last 10 minutes (it stays known after the approval is
     * used), or null.
     */
    static synchronized String approvedPlan() {
        return plan != null && System.currentTimeMillis() - planAt < APPROVAL_VALID_MS
                && java.util.Objects.equals(planFor, McpServer.CALLER_CHAT.get()) ? plan : null;
    }

    /** A chat's run starts or ends: what was approved for it before doesn't carry over. */
    static synchronized void forget(String chat) {
        if (java.util.Objects.equals(approvedFor, chat)) approvedAt = 0;
        if (java.util.Objects.equals(planFor, chat)) plan = null;
    }

    /** Use the pending approval, if any (one approval, one action). */
    static synchronized boolean takeApproval() {
        return takeApproval(true);
    }

    /**
     * As above; send: the action sends something (a countdown's approval covers nothing else). Only the chat that
     * got the approval can use it: another chat's attempt leaves it in place.
     */
    static synchronized boolean takeApproval(boolean send) {
        return takeApproval(send, null);
    }

    /**
     * As above, for an action whose content Buddy knows (a reply's text, a link): the approval covers it only if
     * the approved confirm named exactly that, so an OK for one message never sends another. Used up either way.
     */
    static synchronized boolean takeApproval(boolean send, String content) {
        if (approvedAt == 0 || !java.util.Objects.equals(approvedFor, McpServer.CALLER_CHAT.get())) return false;
        boolean ok = System.currentTimeMillis() - approvedAt < APPROVAL_VALID_MS && (send || !approvedAuto)
                && (content == null || approvedText != null && plain(approvedText).contains(plain(content)));
        approvedAt = 0;
        return ok;
    }

    /** For comparing what was approved with what's sent: case, spacing and quote styles don't matter. */
    private static String plain(String s) {
        return s.toLowerCase(java.util.Locale.ROOT).replaceAll("[\u201c\u201d\u201e\u00ab\u00bb\u2018\u2019\"']", "")
                .replaceAll("\\s+", " ").trim();
    }

    /**
     * Does tapping this send or post a message (and nothing more, like paying or deleting)? Never in an app for money
     * or shopping, where "Send" can mean sending money.
     */
    static boolean sends(android.content.Context c, AccessibilityNodeInfo n) {
        String l = n == null ? null : riskyLabel(n);
        if (l == null || moneyApp(c, n.getPackageName())) return false;
        return SEND.matcher(l.trim()).find() || SEND_ID.matcher(l.trim()).find();
    }

    private static final java.util.Set<String> MONEY = new java.util.HashSet<>(java.util.Arrays.asList(
            "com.android.vending", "com.amazon.mShop.android.shopping", "com.ebay.mobile", "com.paypal.android.p2pmobile",
            "com.revolut.revolut", "de.number26.android", "com.google.android.apps.walletnfcrel", "com.wise.android",
            "com.transferwise.android", "com.klarna.android", "com.coinbase.android", "com.binance.dev"));

    /** A payment, banking, crypto or shopping app (or one Buddy can't tell). */
    static boolean moneyApp(android.content.Context c, CharSequence pkg) {
        if (pkg == null) return true;
        String p = pkg.toString();
        if (MONEY.contains(p) || HARD_NAME.matcher(p).find()) return true;
        try {
            android.content.pm.PackageManager pm = c.getPackageManager();
            CharSequence label = pm.getApplicationLabel(pm.getApplicationInfo(p, 0));
            return label != null && HARD_NAME.matcher(label).find();
        } catch (Exception e) {
            return false;
        }
    }

    /** What a tap at (x, y) would hit: the topmost app window's deepest node there. */
    static AccessibilityNodeInfo nodeAt(List<AccessibilityWindowInfo> windows, int x, int y) {
        AccessibilityWindowInfo top = null;
        Rect r = new Rect();
        for (AccessibilityWindowInfo w : windows) {
            // Buddy's floating bubble and panel let taps through while a gesture runs (see
            // BuddyService.setBubblePassThrough), so what counts is the app underneath
            if (w.getType() == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) continue;
            w.getBoundsInScreen(r);
            if (!r.contains(x, y)) continue;
            if (top == null || w.getLayer() > top.getLayer()) top = w;
        }
        if (top == null) return null;
        AccessibilityNodeInfo n = top.getRoot();
        if (n == null) return null;
        for (int depth = 0; depth < 60; depth++) {
            AccessibilityNodeInfo next = null;
            for (int i = n.getChildCount() - 1; i >= 0; i--) { // later children draw on top
                AccessibilityNodeInfo ch = n.getChild(i);
                if (ch == null || !ch.isVisibleToUser()) continue;
                ch.getBoundsInScreen(r);
                if (r.contains(x, y)) {
                    next = ch;
                    break;
                }
            }
            if (next == null) break;
            n = next;
        }
        return n;
    }

    /** Is this Buddy's own UI (its screens, the bubble, its approval card)? */
    static boolean isBuddy(AccessibilityNodeInfo n) {
        return n != null && n.getPackageName() != null
                && BuildInfo.class.getPackage().getName().contentEquals(n.getPackageName());
    }

    /**
     * Must the agent keep out of this node? All of Buddy's UI, unless the user turned on "Let agents tap Buddy's
     * screens"; even then the protected parts (Ui.protect: approval cards, App permissions, the developer
     * switches) stay off-limits. The bubble and its card are overlays that taps never land on (see nodeAt).
     */
    static boolean offLimits(android.content.Context c, AccessibilityNodeInfo n) {
        if (!isBuddy(n)) return false;
        if (!Prefs.agentsTapBuddy(c)) return true;
        AccessibilityNodeInfo cur = n;
        for (int i = 0; cur != null && i < 60; i++) {
            if (cur.getExtras().getBoolean(Ui.PROTECTED, false)) return true;
            if (cur.getWindow() != null && cur.getWindow().getType() == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) return true;
            cur = cur.getParent();
        }
        return false;
    }

    /**
     * The risky label of what would be tapped (the node, the button it belongs to, and that button's own
     * labels), or null when it looks harmless.
     */
    static String riskyLabel(AccessibilityNodeInfo n) {
        AccessibilityNodeInfo cur = n;
        for (int i = 0; cur != null && i < 4; i++) {
            String hit = risky(cur);
            if (hit != null) return hit;
            if (cur.isClickable()) {
                for (int c = 0; c < Math.min(cur.getChildCount(), 6); c++) { // an icon button's label child
                    AccessibilityNodeInfo ch = cur.getChild(c);
                    String h = ch == null ? null : risky(ch);
                    if (h != null) return h;
                }
                break; // the button itself: don't look at its surroundings
            }
            cur = cur.getParent();
        }
        return null;
    }

    /** A web page's button label (the background browser, see BrowserTools), when it's a risky one; else null. */
    static String riskyText(String label) {
        if (label == null) return null;
        String s = label.trim();
        // as for a button on the phone: starts with the word and is short
        if (s.isEmpty() || s.length() > 30 || s.split("\\s+").length > 4) return null;
        return RISKY.matcher(s).find() ? s : null;
    }

    /** A web page button's id, name or test id says it sends, pays, buys or deletes ("add-to-cart-button"). */
    static boolean riskyId(String ids) {
        if (ids == null) return false;
        java.util.regex.Matcher m = RISKY_ID.matcher("_" + ids.toLowerCase(Locale.ROOT).replace(' ', '_'));
        while (m.find()) if (!"submit".equals(m.group(1))) return true; // on the web, every form's button is "submit"
        return false;
    }

    /** A web page button that sends or posts a message (see sends). */
    static boolean sendsText(String label) {
        String l = riskyText(label);
        return l != null && SEND.matcher(l).find();
    }

    private static String risky(AccessibilityNodeInfo n) {
        for (CharSequence cs : new CharSequence[]{n.getText(), n.getContentDescription(), n.getTooltipText()}) {
            if (cs == null) continue;
            String s = cs.toString().trim();
            // a button label ("Send", "Pay now", "Delete chat"): starts with the word and is short, so a chat
            // preview like "Sarah, the audio call ended" isn't mistaken for a Call button
            if (s.isEmpty() || s.length() > 30 || s.split("\\s+").length > 4) continue;
            if (RISKY.matcher(s).find()) return s;
        }
        String id = n.getViewIdResourceName();
        if (id != null && RISKY_ID.matcher(id.toLowerCase(Locale.ROOT)).find()) {
            return id.substring(id.lastIndexOf('/') + 1).replace('_', ' ');
        }
        return null;
    }
}
