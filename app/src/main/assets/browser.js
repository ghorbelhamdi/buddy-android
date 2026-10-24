// Buddy's background browser: run in the page by Browser.java (Runtime.evaluate, in an isolated world the page's own
// scripts can't reach) for the agents' browser tools.
// It reads the whole page as text with numbered items ([12]), and finds an item again by its number. The numbers
// are kept on the elements (data-buddy-ref), so they stay valid until the page changes.
(function () {
  if (window.__buddy) return;
  var B = window.__buddy = {seq: 0};
  // a new world on a page Buddy already numbered: go on from its numbers, so none is given twice
  document.querySelectorAll('[data-buddy-ref]').forEach(function (el) {
    B.seq = Math.max(B.seq, +el.getAttribute('data-buddy-ref') || 0);
  });
  var SKIP = {SCRIPT: 1, STYLE: 1, NOSCRIPT: 1, TEMPLATE: 1, HEAD: 1, META: 1, LINK: 1, SVG: 1, CANVAS: 1};
  var BLOCK = /^(block|flex|grid|list-item|table|table-row|table-caption|flow-root)$/;
  var ROLES = /^(button|link|checkbox|radio|tab|menuitem|menuitemcheckbox|menuitemradio|switch|option|combobox|textbox|searchbox|slider|spinbutton)$/;

  function clean(s, n) {
    s = (s || '').replace(/\s+/g, ' ').trim();
    return n && s.length > n ? s.slice(0, n - 1) + '…' : s;
  }

  function shown(el) {
    var s = getComputedStyle(el);
    if (s.display === 'none' || s.visibility === 'hidden' || s.visibility === 'collapse') return false;
    if (parseFloat(s.opacity) === 0 && !el.matches('input,select,textarea')) return false;
    return true;
  }

  function role(el) {
    var r = (el.getAttribute('role') || '').toLowerCase(), t = el.tagName;
    if (ROLES.test(r)) return r;
    if (t === 'A' && el.hasAttribute('href')) return 'link';
    if (t === 'BUTTON' || t === 'SUMMARY') return 'button';
    if (t === 'SELECT') return 'select';
    if (t === 'TEXTAREA') return 'textbox';
    if (t === 'INPUT') {
      var ty = (el.type || 'text').toLowerCase();
      if (ty === 'hidden') return null;
      if (ty === 'submit' || ty === 'button' || ty === 'reset' || ty === 'image') return 'button';
      if (ty === 'checkbox' || ty === 'radio') return ty;
      if (ty === 'range') return 'slider';
      if (ty === 'file') return 'file';
      return ty === 'search' ? 'searchbox' : 'textbox';
    }
    if (el.isContentEditable && !(el.parentElement && el.parentElement.isContentEditable)) return 'textbox';
    if (el.hasAttribute('onclick') || (el.tabIndex >= 0 && getComputedStyle(el).cursor === 'pointer' && t !== 'BODY')) return 'button';
    return null;
  }

  function labelled(el) {
    var ids = el.getAttribute('aria-labelledby');
    if (!ids) return '';
    return ids.split(/\s+/).map(function (id) {
      var e = document.getElementById(id);
      return e ? e.innerText || e.textContent : '';
    }).join(' ');
  }

  // what the item says: its own words first, then what describes it
  function label(el) {
    var l = el.getAttribute('aria-label') || labelled(el);
    if (!l && el.labels && el.labels.length) l = el.labels[0].innerText;
    if (!l && el.tagName === 'INPUT' && /^(submit|button|reset)$/i.test(el.type)) l = el.value;
    if (!l && el.tagName !== 'INPUT' && el.tagName !== 'TEXTAREA' && el.tagName !== 'SELECT') l = el.innerText;
    if (!l) l = el.getAttribute('placeholder') || el.getAttribute('title') || el.getAttribute('alt');
    if (!l) {
      var img = el.querySelector && el.querySelector('img[alt],[aria-label],svg title');
      if (img) l = img.getAttribute('alt') || img.getAttribute('aria-label') || img.textContent;
    }
    if (!l && el.tagName === 'INPUT' && el.type === 'image') l = el.alt;
    if (!l) l = el.getAttribute('name') || el.id || '';
    return clean(l, 80);
  }

  function refOf(el) {
    var r = el.getAttribute('data-buddy-ref');
    if (!r) {
      r = String(++B.seq);
      el.setAttribute('data-buddy-ref', r);
    }
    return r;
  }

  // read() numbers items inside shadow roots too (web components, e.g. Reddit's forms): look there as well
  B.find = function (ref) {
    var sel = '[data-buddy-ref="' + String(ref).replace(/[^0-9]/g, '') + '"]';
    return (function deep(root) {
      var el = root.querySelector(sel);
      if (el) return el;
      var all = root.querySelectorAll('*');
      for (var i = 0; i < all.length; i++) {
        if (all[i].shadowRoot && (el = deep(all[i].shadowRoot))) return el;
      }
      return null;
    })(document);
  };

  // Where items are on screen (CSS pixels of the viewport), for the marks the user sees; the first scrolled into view
  B.boxes = function (refs, reveal) {
    return refs.map(function (r, i) {
      var el = B.find(r);
      if (!el) return null;
      if (reveal && i === 0) el.scrollIntoView({block: 'center', behavior: 'instant'});
      var b = el.getBoundingClientRect();
      return {x: b.left, y: b.top, w: b.width, h: b.height};
    });
  };

  // Fields Buddy never types into: passwords, one-time codes, payment details (the user does those)
  var SENSITIVE = /passw|passcode|kennwort|contrase|senha|пароль|mot de passe|one.?time|\botp\b|2fa|two.?factor|verification code|security code|confirmation code|sms code|login code|cvv|cvc|csc|card.?number|cardnum|cc.?num|credit.?card|expir|iban|routing number|sort code|kartennummer|numéro de carte|número de tarjeta|\bpin\b/i;
  B.sensitive = function (el) {
    if (el.tagName === 'INPUT' && (el.type || '').toLowerCase() === 'password') return 'password';
    var ac = (el.getAttribute('autocomplete') || '').toLowerCase();
    if (/current-password|new-password/.test(ac)) return 'password';
    if (/one-time-code/.test(ac)) return 'one-time code';
    if (/cc-|transaction/.test(ac)) return 'payment';
    var words = [el.name, el.id, el.getAttribute('aria-label'), el.getAttribute('placeholder'), el.labels && el.labels.length ? el.labels[0].innerText : '', labelled(el)].join(' ');
    var m = SENSITIVE.exec(words);
    if (!m) return null;
    return /passw|passcode|kennwort|contrase|senha|пароль|mot de passe|\bpin\b/i.test(m[0]) ? 'password'
      : /cvv|cvc|csc|card|cc.?num|expir|iban|routing|sort code|carte|tarjeta/i.test(m[0]) ? 'payment' : 'one-time code';
  };

  function describe(el, r) {
    var ref = refOf(el), out = '[' + ref + '] ' + r, l = label(el);
    if (l) out += ' "' + l.replace(/"/g, "'") + '"';
    var t = el.tagName;
    if (r === 'textbox' || r === 'searchbox') {
      var kind = B.sensitive(el);
      if (kind) out += ' (' + kind + ' field: the user fills it in)';
      else {
        var v = el.isContentEditable ? el.innerText : el.value;
        if (v) out += ' value="' + clean(v, 60).replace(/"/g, "'") + '"';
      }
      if (t === 'INPUT' && el.type && !/^(text|search)$/i.test(el.type)) out += ' type=' + el.type;
    } else if (r === 'select') {
      var o = el.options[el.selectedIndex];
      if (o) out += ' selected="' + clean(o.text, 40) + '"';
      var opts = [];
      for (var i = 0; i < el.options.length && i < 25; i++) opts.push(clean(el.options[i].text, 30));
      out += ' options: ' + opts.join(' | ') + (el.options.length > 25 ? ' …' : '');
    } else if (r === 'checkbox' || r === 'radio' || r === 'switch') {
      var on = el.checked !== undefined ? el.checked : el.getAttribute('aria-checked') === 'true';
      out += on ? ' (checked)' : ' (unchecked)';
    } else if (r === 'link') {
      var h = el.getAttribute('href') || '';
      if (h && !/^(#|javascript:)/i.test(h)) out += ' -> ' + clean(h, 100);
    }
    if (el.disabled || el.getAttribute('aria-disabled') === 'true') out += ' (disabled)';
    return out;
  }

  // The whole page as lines of text, items as [ref] role "label"
  B.read = function () {
    var lines = [], cur = '';
    function flush() {
      var s = clean(cur);
      if (s) lines.push(s);
      cur = '';
    }
    function walk(n, depth) {
      if (depth > 60) return;
      if (n.nodeType === 3) {
        cur += n.nodeValue;
        return;
      }
      if (n.nodeType !== 1 || SKIP[n.tagName.toUpperCase()]) return;
      if (!shown(n)) return;
      var t = n.tagName;
      if (t === 'IFRAME') {
        var src = n.getAttribute('src') || '';
        var host = '';
        try { host = src ? new URL(src, location.href).host : ''; } catch (e) {}
        if (host && host !== location.host) {
          flush();
          lines.push('(embedded frame from ' + host + ': Buddy can\'t read or use it; the user can, in the live view)');
        }
        return;
      }
      var r = role(n);
      if (r) {
        cur += ' ' + describe(n, r) + ' ';
        // a link or button that wraps a whole card: its label says enough
        if (r !== 'button' && r !== 'link' || !n.querySelector('a[href],button,input,select,textarea')) return;
      }
      var disp = getComputedStyle(n).display;
      var block = BLOCK.test(disp) || t === 'BR' || /^H[1-6]$/.test(t);
      if (block) flush();
      if (/^H[1-6]$/.test(t) && !r) cur += '#'.repeat(+t[1]) + ' ';
      else if (t === 'LI' && !r) cur += '- ';
      if (t === 'IMG' && !r) {
        var alt = clean(n.getAttribute('alt'), 80);
        if (alt) cur += ' (image: ' + alt + ') ';
      }
      if (n.shadowRoot) for (var s = n.shadowRoot.firstChild; s; s = s.nextSibling) walk(s, depth + 1);
      for (var c = n.firstChild; c; c = c.nextSibling) walk(c, depth + 1);
      if (block || disp === 'table-cell') {
        if (disp === 'table-cell') cur += ' | ';
        else flush();
      }
    }
    walk(document.body || document.documentElement, 0);
    flush();
    // the same line again and again (menus repeated for mobile and desktop) once is enough
    var out = [], prev = null;
    for (var i = 0; i < lines.length; i++) {
      if (/^[-|#\s]*$/.test(lines[i])) continue; // an empty list item or table cell
      if (lines[i] !== prev) out.push(prev = lines[i]);
    }
    return out.join('\n');
  };

  // What the page asks for: a sign-in, a bot check
  B.signals = function () {
    var pw = false, f = document.querySelectorAll('input[type=password]');
    for (var i = 0; i < f.length; i++) if (shown(f[i]) && f[i].getBoundingClientRect().width > 0) pw = true;
    // only a challenge the user can see: many sign-in pages load an invisible reCAPTCHA that never asks anything
    function visible(x) {
      var r = x.getBoundingClientRect();
      return shown(x) && r.width >= 60 && r.height >= 40;
    }
    var frames = Array.prototype.filter.call(document.querySelectorAll('iframe'), function (x) {
      return visible(x) && !/size=invisible/i.test(x.src || '');
    }).map(function (x) { return x.src || ''; }).join(' ');
    var widget = Array.prototype.some.call(document.querySelectorAll('#challenge-form,#cf-challenge-running,.g-recaptcha,.h-captcha,#px-captcha'), visible);
    var text = clean(document.body ? document.body.innerText : '', 3000);
    var bot = /recaptcha|hcaptcha|challenges\.cloudflare|turnstile|arkoselabs|funcaptcha|captcha-delivery|px-captcha|geo\.captcha/i.test(frames)
      || /^just a moment/i.test(document.title) || widget
      || /verify (that )?you are (a )?human|are you a robot|unusual traffic|pardon our interruption|press (&|and) hold|checking your browser|confirm you are not a robot|bot detection/i.test(text);
    var blocked = /^(access denied|forbidden|403)/i.test(document.title) || /you don't have permission to access|request blocked|access to this page has been denied/i.test(text.slice(0, 600));
    return {login: pw, bot: bot, blocked: blocked};
  };

  // An item for a tap: where it is now (scrolled into view) and what it does
  // is a inside b, counting a web component's inner elements as inside it
  function within(a, b) {
    for (var n = a; n; n = n.parentNode || n.host) if (n === b) return true;
    return false;
  }

  B.target = function (ref) {
    var el = B.find(ref);
    if (!el) return {missing: true};
    el.scrollIntoView({block: 'center', inline: 'center', behavior: 'instant'});
    var rs = el.getClientRects(), r = rs.length ? rs[0] : el.getBoundingClientRect();
    var x = r.left + Math.min(r.width / 2, 40 + r.width / 4), y = r.top + r.height / 2;
    if (r.width < 80) x = r.left + r.width / 2;
    // the topmost element there, inside web components too (elementFromPoint stops at their outer element)
    var hit = document.elementFromPoint(x, y), covered = null;
    while (hit && hit.shadowRoot) {
      var inner = hit.shadowRoot.elementFromPoint(x, y);
      if (!inner || inner === hit) break;
      hit = inner;
    }
    if (hit && hit !== el && !within(hit, el) && !within(el, hit)) {
      var h = hit;
      while (h && !role(h)) h = h.parentElement;
      covered = h ? describe(h, role(h)) : clean(hit.innerText || hit.tagName, 60);
    }
    var form = el.form || el.closest('form');
    var submits = el.tagName === 'BUTTON' ? (el.type || 'submit').toLowerCase() === 'submit' && !!form
      : el.tagName === 'INPUT' && /^(submit|image)$/i.test(el.type);
    var pay = false;
    if (form) {
      var fs = form.querySelectorAll('input,select');
      for (var i = 0; i < fs.length; i++) if (B.sensitive(fs[i]) === 'payment') pay = true;
    }
    return {
      x: x, y: y, w: r.width, h: r.height, covered: covered, label: label(el), role: role(el) || el.tagName.toLowerCase(),
      ids: clean([el.id, el.getAttribute('name'), el.getAttribute('data-testid'), el.className && el.className.baseVal === undefined ? el.className : ''].join(' '), 200),
      title: clean(el.getAttribute('title') || el.getAttribute('aria-label') || el.value || '', 60),
      submits: submits, payForm: pay, form: form ? clean((form.getAttribute('action') || '') + ' ' + (form.id || '') + ' ' + (form.getAttribute('name') || ''), 200) : '',
      href: el.getAttribute('href') || '',
      // what's typed in the field that has the focus (a comment about to be posted), for the approval card
      draft: (function (a) {
        if (!a || a === document.body || a === el) return '';
        return clean(a.isContentEditable ? a.innerText : /^(INPUT|TEXTAREA)$/.test(a.tagName) && !B.sensitive(a) ? a.value : '', 300);
      })(document.activeElement)
    };
  };

  // Ready an item for typing: focused, its text selected (to be replaced) or the cursor at its end
  B.focus = function (ref, append) {
    var el = B.find(ref);
    if (!el) return {missing: true};
    var r = role(el);
    if (r === 'select') return {select: true};
    // a text box can carry another role (Google's search box is a textarea with role=combobox)
    var editable = el.tagName === 'TEXTAREA' || el.isContentEditable
      || (el.tagName === 'INPUT' && !/^(checkbox|radio|submit|button|file|range|image|reset|hidden)$/i.test(el.type));
    if (r !== 'textbox' && r !== 'searchbox' && !editable) {
      return {notField: true, label: label(el), role: r || el.tagName.toLowerCase()};
    }
    var kind = B.sensitive(el);
    if (kind) return {sensitive: kind, label: label(el)};
    el.scrollIntoView({block: 'center', behavior: 'instant'});
    el.focus();
    if (el.isContentEditable) {
      var range = document.createRange();
      range.selectNodeContents(el);
      if (append) range.collapse(false);
      var sel = getSelection();
      sel.removeAllRanges();
      sel.addRange(range);
    } else if (append) {
      try { el.setSelectionRange(el.value.length, el.value.length); } catch (e) {}
    } else {
      try { el.select(); } catch (e) {}
    }
    var form = el.form || el.closest('form'), btn = null;
    if (form) {
      var bs = form.querySelectorAll('button,input[type=submit],input[type=image],[role=button]');
      for (var i = 0; i < bs.length; i++) {
        var b = bs[i];
        if (b.tagName === 'BUTTON' && (b.type || 'submit').toLowerCase() !== 'submit') continue;
        btn = label(b);
        break;
      }
    }
    var search = r === 'searchbox' || el.getAttribute('role') === 'searchbox' || /search|query|^q$|suche|recherche|buscar/i.test((el.name || '') + ' ' + (el.id || '') + ' ' + (el.getAttribute('aria-label') || '') + ' ' + (el.getAttribute('placeholder') || ''))
      || !!(form && /search/i.test(form.getAttribute('role') || form.getAttribute('action') || ''));
    return {ok: true, label: label(el), multiline: el.tagName === 'TEXTAREA' || el.isContentEditable, search: search, submitLabel: btn};
  };

  // Pick an option of a select by its text or value
  B.choose = function (ref, want) {
    var el = B.find(ref);
    if (!el || el.tagName !== 'SELECT') return {missing: true};
    var w = clean(want).toLowerCase(), pick = -1;
    for (var i = 0; i < el.options.length; i++) {
      var t = clean(el.options[i].text).toLowerCase();
      if (t === w || el.options[i].value.toLowerCase() === w) { pick = i; break; }
      if (pick < 0 && t.indexOf(w) >= 0) pick = i;
    }
    if (pick < 0) return {none: true};
    el.selectedIndex = pick;
    el.dispatchEvent(new Event('input', {bubbles: true}));
    el.dispatchEvent(new Event('change', {bubbles: true}));
    return {chosen: clean(el.options[pick].text, 60)};
  };
})();
