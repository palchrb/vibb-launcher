// Saves in place (design 103 §2): a save that comes back to its page, the 3 s refresh of a page that waits for the
// phone, pull to refresh and the reload after being away no longer load a new document. The page is fetched, read
// off-screen (DOMParser) and only the nodes that differ in the page on screen are changed - the flash, rows that come
// or go, badges, the Attention list, counts. Nothing else is redrawn; a switch, an open row, a decoded image and the
// focus stay. The server's rendering stays the only truth: the page fetched is the one a reload would show.
//
// `window.handySwap.refresh(url, opts)` returns true when it has taken the job, false when the caller does what it did
// before (the reload). Later, a page it can't swap (another language, the app lock turned on or off, a new stylesheet
// or script list - a server update -, a page script not marked `data-swap-ready`, no <main>, a sign-in redirect,
// anything but a 200 text/html) is reloaded for real, as before; a 423 goes to the lock. `opts`:
// - `forms`, `byButton`: the saved forms and the buttons that sent them (static/scroll-restore.js's landing);
// - `lost`: the save's answer never came (a 5xx, no answer) - the page shows what is stored, with a red line;
// - `fallback`: the caller's own real reload, used when the page can't be swapped.
// Only the newest GET is used; a refresh asked for while one is pending inherits its saved forms (one landing
// record). Nothing is swapped or reloaded while a save is on its way (scroll-restore's `inflight`): that save's
// landing refreshes again and inherits the forms. A GET that fails (offline) hands over to static/reconnect.js's
// wait of kind "swap", which calls this again once the server answers; the saved forms stay busy meanwhile.
//
// The morph (§2.3): a node keeps its identity when it can. Children are matched by key - the id, else the tag and the
// first of `action`, `data-flash`, `data-warning`, `data-mobile-line`, `data-choice`, `data-row`, `data-setup-step`,
// `for` or `name`, else the tag - with a lookahead, so a line that comes in before a form never replaces that form.
// A control takes the server's value in a saved form, keeps the parent's when changed and not saved; an open
// <details> stays open unless the button that saved sat in it. `<noscript>` and `<script>` are left alone,
// `data-swap-keep` is a script's own, `[data-flash]` is always new and spoken through `#swap-status`.
//
// The place (§2.4): the saved form, or its nearest still drawn id'd ancestor, keeps its top on the screen; a refresh
// without a save keeps the first id'd element below the top strip. Then `handy:swapped` (every script that changes the
// page at load runs that pass again) and, after a save, `handy:saved` with the saves still out (`pending`).
// Loaded un-deferred from the head partial after scroll-restore.js. ES5 (Safari 14, Chrome 80). Tests:
// jstest/swap.test.js; the browser semantics: node jstest/real/swap-real.js (by hand, Chromium and WebKit).
(function (root) {
    "use strict";

    var SOFT_KEYS = ["action", "data-flash", "data-warning", "data-mobile-line", "data-choice", "data-row",
        "data-setup-step", "for", "name"];
    // A save's one-shot words in the address (scroll-restore.js's ONE_SHOT).
    var ONE_SHOT = { notice: 1, app: 1, saved: 1 };
    // scroll-restore.js's form record (its KEY and NAME_PREFIX).
    var SCROLL_KEY = "handy-scroll";
    var NAME_PREFIX = "handy-scroll:";
    // The head partial's own scripts: ready by design (§2.5). Every other page script must carry data-swap-ready.
    var HEAD_SCRIPTS = { "app-lock": 1, "register-sw": 1, "scroll-restore": 1, "swap": 1, "autosave": 1,
        "focus-mode": 1, "reconnect": 1, "nav-timing": 1, "confirm": 1, "top-strip": 1, "auto-reload": 1 };
    var TEXTLESS = { hidden: 1, submit: 1, button: 1, reset: 1, image: 1, checkbox: 1, radio: 1, file: 1 };

    function tagOf(node) {
        return String((node && node.nodeName) || "").toUpperCase();
    }

    function attr(el, name) {
        return el && el.getAttribute ? el.getAttribute(name) : null;
    }

    /** A node's key (103-1): `#id`, else the tag and its first soft key, else the tag; text and comments by type. */
    function keyOf(node) {
        if (!node) return "";
        if (node.nodeType !== 1) return "@" + node.nodeType;
        var id = attr(node, "id");
        if (id) return "#" + id;
        var tag = tagOf(node);
        for (var i = 0; i < SOFT_KEYS.length; i++) {
            var value = attr(node, SOFT_KEYS[i]);
            if (value !== null && value !== undefined) return tag + "[" + SOFT_KEYS[i] + "=" + value + "]";
        }
        return tag;
    }

    function isKeep(node) {
        return !!(node && node.nodeType === 1 && node.hasAttribute && node.hasAttribute("data-swap-keep"));
    }

    function isFlash(node) {
        return !!(node && node.nodeType === 1 && node.hasAttribute && node.hasAttribute("data-flash"));
    }

    /** `url` without a save's one-shot words (`notice`, `app`, `saved`), its #fragment kept. */
    function withoutOneShot(url) {
        var hash = "";
        var at = url.indexOf("#");
        if (at >= 0) {
            hash = url.slice(at);
            url = url.slice(0, at);
        }
        var q = url.indexOf("?");
        if (q < 0) return url + hash;
        var kept = [];
        var parts = url.slice(q + 1).split("&");
        for (var i = 0; i < parts.length; i++) {
            if (parts[i] && ONE_SHOT[parts[i].split("=")[0]] !== 1) kept.push(parts[i]);
        }
        return url.slice(0, q) + (kept.length ? "?" + kept.join("&") : "") + hash;
    }

    function list(doc, selector, read) {
        var out = [];
        var found = doc.querySelectorAll ? doc.querySelectorAll(selector) : [];
        for (var i = 0; i < found.length; i++) out.push(read(found[i]));
        return out.join("\n");
    }

    function scriptName(src) {
        var m = /\/static\/([^\/?#]+)\.js(?:[?#]|$)/.exec(String(src || ""));
        return m ? m[1] : null;
    }

    /**
     * Why `next` can't replace `doc` in place (§2.2 step 4), or null when it can: no <main>, another language, the app
     * lock turned on or off, other stylesheets or scripts, or a page script on this page not marked ready.
     */
    function unswappable(doc, next) {
        if (!next || !next.querySelector || !next.querySelector("main")) return "main";
        if (!doc.querySelector("main")) return "main";
        var a = doc.documentElement;
        var b = next.documentElement;
        if (attr(a, "lang") !== attr(b, "lang")) return "lang";
        if (attr(a, "data-lock-ms") !== attr(b, "data-lock-ms")) return "lock";
        var href = function (el) { return attr(el, "href"); };
        if (list(doc, "link[rel=stylesheet]", href) !== list(next, "link[rel=stylesheet]", href)) return "css";
        var src = function (el) { return attr(el, "src"); };
        if (list(doc, "script[src]", src) !== list(next, "script[src]", src)) return "scripts";
        var scripts = doc.querySelectorAll("script[src]");
        for (var i = 0; i < scripts.length; i++) {
            var name = scriptName(attr(scripts[i], "src"));
            if (name && HEAD_SCRIPTS[name] === 1) continue;
            if (!scripts[i].hasAttribute("data-swap-ready")) return "unready";
        }
        return null;
    }

    function contains(outer, inner) {
        for (var el = inner; el; el = el.parentNode) if (el === outer) return true;
        return false;
    }

    function connected(doc, el) {
        if (!el) return false;
        if (typeof el.isConnected === "boolean") return el.isConnected;
        return contains(doc, el);
    }

    /**
     * Whether `el` is drawn: it has boxes and no closed <details> hides it (outside that details' summary). Chromium
     * and WebKit still give a closed <details>' content boxes (its slot is `content-visibility: hidden`, found by
     * jstest/real/swap-real.js), so the boxes alone don't tell.
     */
    function drawn(el) {
        if (!el || !el.getClientRects) return false;
        var rects = el.getClientRects();
        if (!rects || !rects.length) return false;
        for (var n = el, child = null; n; child = n, n = n.parentNode) {
            if (tagOf(n) === "DETAILS" && n !== el && !n.hasAttribute("open") && tagOf(child) !== "SUMMARY") return false;
        }
        return true;
    }

    function topOf(el) {
        return el.getBoundingClientRect().top;
    }

    /**
     * The chain of tops (103-2, 103-15): the saved form and each of its id'd ancestors, by node and by id; for a
     * refresh without a save, the first id'd element in `main` whose top is at or below main's visible top (below the
     * fixed top strip where it covers main).
     */
    function anchorChain(doc, main, form) {
        var chain = [];
        if (form) {
            for (var el = form; el && el !== doc && el.getBoundingClientRect; el = el.parentNode) {
                if (el === form || attr(el, "id")) chain.push({ el: el, id: attr(el, "id"), top: topOf(el) });
                if (el === main) break;
            }
            return chain;
        }
        if (!main || !main.getBoundingClientRect || !main.querySelectorAll) return chain;
        var visible = main.getBoundingClientRect().top;
        var strip = doc.querySelector ? doc.querySelector(".top-strip") : null;
        if (strip && strip.getBoundingClientRect) visible = Math.max(visible, strip.getBoundingClientRect().bottom);
        var ids = main.querySelectorAll("[id]");
        for (var i = 0; i < ids.length; i++) {
            if (!drawn(ids[i])) continue;
            var top = topOf(ids[i]);
            if (top >= visible) {
                chain.push({ el: ids[i], id: attr(ids[i], "id"), top: top });
                break;
            }
        }
        return chain;
    }

    /**
     * How far the page must scroll so the anchor keeps its top: the first element of the chain that is still
     * connected and drawn. 0 when none is, or when the saved form has left a recorded ancestor (a row moved to another
     * group is never followed, scroll-restore.js's rule of 2026-10-19).
     */
    function anchorDelta(doc, chain) {
        if (!chain.length) return 0;
        var form = chain[0].el;
        if (!connected(doc, form) && chain[0].id && doc.getElementById) form = doc.getElementById(chain[0].id);
        if (form && connected(doc, form)) {
            for (var i = 1; i < chain.length; i++) {
                var now = chain[i].id && doc.getElementById ? doc.getElementById(chain[i].id) : null;
                if (now && !contains(now, form)) return 0;
            }
        }
        for (var j = 0; j < chain.length; j++) {
            var el = chain[j].el;
            if (!connected(doc, el) && chain[j].id && doc.getElementById) el = doc.getElementById(chain[j].id);
            if (!el || !connected(doc, el) || !drawn(el)) continue;
            return topOf(el) - chain[j].top;
        }
        return 0;
    }

    // ---- The morph ----

    function controlKind(el) {
        var tag = tagOf(el);
        if (tag === "SELECT") return "select";
        if (tag === "TEXTAREA") return "text";
        if (tag !== "INPUT") return null;
        var type = String(attr(el, "type") || "text").toLowerCase();
        if (type === "checkbox" || type === "radio") return "check";
        if (type === "file") return "file";
        if (type === "hidden") return "hidden";
        if (TEXTLESS[type] === 1) return null;
        return "text";
    }

    /** The control differs from what its page came with (auto-reload.js's `typing` rule). */
    function dirty(el, kind) {
        if (kind === "check") return el.checked !== el.defaultChecked;
        if (kind === "text") return el.value !== el.defaultValue;
        if (kind === "select") {
            var options = el.options || [];
            for (var i = 0; i < options.length; i++) if (options[i].selected !== options[i].defaultSelected) return true;
        }
        return false;
    }

    function selectedOf(el) {
        var out = [];
        var options = el.options || [];
        for (var i = 0; i < options.length; i++) out.push(options[i].selected);
        return out;
    }

    function syncAttrs(cur, next, ctx, skip) {
        var name;
        var attrs = next.attributes || [];
        for (var i = 0; i < attrs.length; i++) {
            name = attrs[i].name;
            if (skip[name] === 1) continue;
            if (cur.getAttribute(name) !== attrs[i].value) cur.setAttribute(name, attrs[i].value);
        }
        var mine = cur.attributes || [];
        var gone = [];
        for (var j = 0; j < mine.length; j++) {
            name = mine[j].name;
            if (skip[name] !== 1 && !next.hasAttribute(name)) gone.push(name);
        }
        for (var k = 0; k < gone.length; k++) cur.removeAttribute(gone[k]);
    }

    /** The attributes the morph leaves as they are on `cur`. */
    function skipped(cur, ctx) {
        var skip = {};
        var tag = tagOf(cur);
        if (tag === "DETAILS" && !ctx.serverOpen(cur)) skip.open = 1;
        if (tag === "FORM" && ctx.busy(cur)) {
            skip["aria-busy"] = 1;
            skip["data-held"] = 1;
        }
        if (ctx.busySubmitter(cur)) {
            skip["aria-busy"] = 1;
            skip.disabled = 1;
        }
        if (cur === ctx.focused && !ctx.saved(ctx.formOf(cur))) skip.value = 1;
        if (tag === "INPUT" && controlKind(cur) === "file") skip.value = 1;
        return skip;
    }

    function morphElement(cur, next, ctx, form) {
        if (isKeep(cur)) return;
        var tag = tagOf(cur);
        if (tag === "NOSCRIPT" || tag === "SCRIPT") return;
        if (tag === "FORM") form = cur;
        var kind = controlKind(cur);
        var formOfCur = kind ? (form || null) : null;
        var keepParent = false;
        var kept = null;
        if (kind && kind !== "hidden" && kind !== "file") {
            var savedForm = ctx.saved(formOfCur);
            if (cur === ctx.focused) {
                // 103-9: in the saved form, the server's value when the field still holds what was sent.
                keepParent = !savedForm || (kind === "text" && !ctx.unchangedSince(formOfCur, cur));
            } else {
                keepParent = !savedForm && (ctx.again(formOfCur) || dirty(cur, kind));
            }
            if (keepParent) kept = kind === "check" ? cur.checked : kind === "select" ? selectedOf(cur) : cur.value;
        }
        syncAttrs(cur, next, ctx, skipped(cur, ctx));
        if (tag === "TEXTAREA") {
            var text = next.textContent;
            if (cur.textContent !== text) cur.textContent = text;
        } else {
            morphChildren(cur, next, ctx, form);
        }
        if (!kind || kind === "hidden" || kind === "file") return;
        if (cur === ctx.focused && !ctx.saved(formOfCur)) return;
        if (keepParent) {
            if (kind === "check") cur.checked = kept;
            else if (kind === "select") {
                for (var i = 0; i < kept.length && i < cur.options.length; i++) cur.options[i].selected = kept[i];
            } else cur.value = kept;
            return;
        }
        // The server's, set explicitly: a control with its dirty flag set no longer follows its attribute.
        if (kind === "check") {
            var on = next.hasAttribute("checked");
            if (cur.checked !== on) cur.checked = on;
        } else if (kind === "select") {
            var options = cur.options || [];
            for (var j = 0; j < options.length; j++) {
                if (options[j].selected !== options[j].defaultSelected) options[j].selected = options[j].defaultSelected;
            }
        } else {
            var value = tag === "TEXTAREA" ? next.textContent : (attr(next, "value") || "");
            if (cur.value !== value) cur.value = value;
            if (cur === ctx.focused && cur.setSelectionRange) {
                try {
                    cur.setSelectionRange(value.length, value.length);
                } catch (e) {}
            }
        }
    }

    function morphNode(cur, next, ctx, form) {
        if (cur.nodeType === 1) {
            morphElement(cur, next, ctx, form);
        } else if (cur.nodeValue !== next.nodeValue) {
            cur.nodeValue = next.nodeValue;
        }
    }

    /** `next` imported into the page: `<noscript>` emptied (103-10), `<script>` never inserted. */
    function imported(next, ctx) {
        if (tagOf(next) === "SCRIPT") return null;
        var node = ctx.doc.importNode(next, true);
        if (node.nodeType === 1) {
            var inner = node.querySelectorAll ? node.querySelectorAll("noscript, script") : [];
            for (var i = inner.length - 1; i >= 0; i--) {
                if (tagOf(inner[i]) === "SCRIPT") inner[i].parentNode.removeChild(inner[i]);
                else while (inner[i].firstChild) inner[i].removeChild(inner[i].firstChild);
            }
            if (tagOf(node) === "NOSCRIPT") while (node.firstChild) node.removeChild(node.firstChild);
            if (isFlash(node)) ctx.flashes.push(node);
            else if (node.querySelectorAll) {
                var flashes = node.querySelectorAll("[data-flash]");
                for (var j = 0; j < flashes.length; j++) ctx.flashes.push(flashes[j]);
            }
        }
        return node;
    }

    function insert(parent, next, before, ctx) {
        var node = imported(next, ctx);
        if (node) parent.insertBefore(node, before);
    }

    /** `node` out of the page; an id'd one the new page still has is parked, to be moved in where it comes. */
    function remove(parent, node, ctx) {
        if (isKeep(node) || tagOf(node) === "SCRIPT") return;
        parent.removeChild(node);
        var id = node.nodeType === 1 ? attr(node, "id") : null;
        if (id && ctx.nextIds[id] === 1) ctx.parked[id] = node;
    }

    /** The first sibling from `from` on whose key is `key`, or null. */
    function findKey(from, key) {
        for (var n = from; n; n = n.nextSibling) if (keyOf(n) === key) return n;
        return null;
    }

    /**
     * Whether a node from `from` up to `to` (not included) is still wanted further on in the new children (from
     * `nNext`): then the lookahead must not remove it (two forms swapped in order, an id'd row among weak keys).
     * Text and comments never count: whitespace is everywhere.
     */
    function wanted(from, to, nNext) {
        for (var n = from; n && n !== to; n = n.nextSibling) {
            if (n.nodeType === 1 && findKey(nNext, keyOf(n))) return true;
        }
        return false;
    }

    /** The children of `cur` made `next`'s, keyed with a lookahead (103-1). */
    function morphChildren(cur, next, ctx, form) {
        var nc = next.firstChild;
        var cc = cur.firstChild;
        while (nc) {
            var nNext = nc.nextSibling;
            var nKey = keyOf(nc);
            // A script's own element with no counterpart further on stays where it is.
            while (cc && isKeep(cc) && !findKey(nc, keyOf(cc))) cc = cc.nextSibling;
            if (tagOf(nc) === "SCRIPT") {
                if (cc && tagOf(cc) === "SCRIPT") cc = cc.nextSibling;
                nc = nNext;
                continue;
            }
            if (cc && keyOf(cc) === nKey) {
                var ccNext = cc.nextSibling;
                if (isFlash(nc)) {
                    insert(cur, nc, cc, ctx);
                    remove(cur, cc, ctx);
                } else {
                    morphNode(cc, nc, ctx, form);
                }
                cc = ccNext;
                nc = nNext;
                continue;
            }
            var later = cc && nc.nodeType === 1 ? findKey(cc.nextSibling, nKey) : null;
            if (later && !wanted(cc, later, nNext)) {
                // The new child is further on (the form after a removed line): what lies between goes.
                while (cc && cc !== later) {
                    var gone = cc;
                    cc = cc.nextSibling;
                    remove(cur, gone, ctx);
                }
                continue;
            }
            if (later) {
                // Further on, but what lies between is still wanted (two forms that changed places): moved here.
                cur.insertBefore(later, cc);
                morphNode(later, nc, ctx, form);
                nc = nNext;
                continue;
            }
            if (nKey.charAt(0) === "#") {
                // An id'd element elsewhere in the page being morphed is moved, never rebuilt. What is already done
                // holds only matched nodes, and ids are unique, so it can only come from what is still to come.
                var id = nKey.slice(1);
                var moved = ctx.parked[id] || (ctx.doc.getElementById ? ctx.doc.getElementById(id) : null);
                delete ctx.parked[id];
                if (moved && moved !== cc && (!moved.parentNode || contains(ctx.root, moved)) && !contains(moved, cur)) {
                    cur.insertBefore(moved, cc);
                    morphNode(moved, nc, ctx, form);
                    nc = nNext;
                    continue;
                }
            }
            if (cc && findKey(nNext, keyOf(cc))) {
                // The current child comes later in the new page (a warning inserted before a form): insert before it.
                insert(cur, nc, cc, ctx);
                nc = nNext;
                continue;
            }
            insert(cur, nc, cc, ctx);
            nc = nNext;
        }
        while (cc) {
            var rest = cc;
            cc = cc.nextSibling;
            remove(cur, rest, ctx);
        }
    }

    /**
     * `cur` made `next` (§2.3). `ctx`: `doc` (for importNode), `focused`, `saved(form)`, `again(form)`, `busy(form)`,
     * `busySubmitter(el)`, `serverOpen(details)`, `unchangedSince(form, el)`, `formOf(el)`; `flashes` collects the
     * imported `[data-flash]` elements.
     */
    function morph(cur, next, ctx) {
        ctx.flashes = ctx.flashes || [];
        ctx.root = cur;
        ctx.parked = {};
        ctx.nextIds = {};
        var ids = next.querySelectorAll ? next.querySelectorAll("[id]") : [];
        for (var i = 0; i < ids.length; i++) ctx.nextIds[attr(ids[i], "id")] = 1;
        morphElement(cur, next, ctx, null);
        return ctx;
    }

    function closestForm(el) {
        for (var n = el; n; n = n.parentNode) if (tagOf(n) === "FORM") return n;
        return null;
    }

    function detailsAround(nodes) {
        var out = [];
        for (var i = 0; i < nodes.length; i++) {
            for (var n = nodes[i]; n; n = n.parentNode) if (tagOf(n) === "DETAILS") out.push(n);
        }
        return out;
    }

    function has(arr, x) {
        for (var i = 0; i < arr.length; i++) if (arr[i] === x) return true;
        return false;
    }

    function dispatch(win, type, detail) {
        var doc = win.document;
        var event;
        try {
            event = new win.CustomEvent(type, { detail: detail });
        } catch (e) {
            event = doc.createEvent("CustomEvent");
            event.initCustomEvent(type, false, false, detail);
        }
        doc.dispatchEvent(event);
    }

    function install(win, opts) {
        opts = opts || {};
        var doc = win.document;
        var parse = opts.parse || function (html) {
            return new win.DOMParser().parseFromString(html, "text/html");
        };
        // The one pending landing record (103-3) and the newest GET's number.
        var pending = null;
        var seq = 0;

        function stay() {
            return win.handyStay || null;
        }
        function inflight() {
            var s = stay();
            return s && typeof s.inflight === "function" ? s.inflight() : 0;
        }
        function entry(form) {
            var s = stay();
            return form && s && typeof s.entry === "function" ? s.entry(form) : null;
        }

        function supported() {
            return typeof win.fetch === "function" && typeof win.DOMParser === "function" && !!win.history &&
                typeof win.history.replaceState === "function";
        }

        /** The real reload, today's way: the caller's, else the entry made a GET of `url` and reloaded. */
        function reloadFor(record) {
            if (record.fallback) {
                record.fallback();
                return;
            }
            try {
                win.history.replaceState(win.history.state, "", record.url);
            } catch (e) {
                win.location.replace(record.url);
                return;
            }
            win.location.reload();
        }

        function words(name) {
            var el = doc.getElementById ? doc.getElementById("js-words") : null;
            return (el && el.getAttribute("data-w-" + name)) || "";
        }

        function say(text) {
            var region = doc.getElementById ? doc.getElementById("swap-status") : null;
            if (!region || !text) return;
            // A live region speaks a change of its text: emptied first, so the same words twice are said twice.
            region.textContent = "";
            win.setTimeout(function () {
                region.textContent = text;
            }, 50);
        }

        function scroller(main) {
            var overflow = "";
            try {
                overflow = win.getComputedStyle(main).overflowY;
            } catch (e) {}
            if (overflow === "auto" || overflow === "scroll") {
                return function (dy) { main.scrollTop = main.scrollTop + dy; };
            }
            return function (dy) { win.scrollTo(0, (win.scrollY || 0) + dy); };
        }

        /** The page on screen made `next`, the place kept, the address and the scripts told (§2.2 steps 5-7). */
        function swap(next, record) {
            var main = doc.querySelector("main");
            var landedForms = record.forms;
            var lastForm = null;
            for (var i = landedForms.length - 1; i >= 0 && !lastForm; i--) {
                if (connected(doc, landedForms[i])) lastForm = landedForms[i];
            }
            var chain = anchorChain(doc, main, lastForm);
            var serverOpen = detailsAround(record.byButton);
            var ctx = {
                doc: doc,
                focused: doc.activeElement || null,
                flashes: [],
                formOf: closestForm,
                saved: function (form) {
                    if (!form || !has(landedForms, form)) return false;
                    var e = entry(form);
                    return !(e && e.again);
                },
                again: function (form) {
                    var e = entry(form);
                    return !!(e && e.again);
                },
                busy: function (form) {
                    return !!entry(form);
                },
                busySubmitter: function (el) {
                    var s = stay();
                    return !!(s && typeof s.isSubmitter === "function" && s.isSubmitter(el));
                },
                serverOpen: function (details) {
                    return has(serverOpen, details);
                },
                unchangedSince: function (form, el) {
                    var e = entry(form);
                    var sent = e && e.sent;
                    if (!sent) return true;
                    for (var k = 0; k < sent.length; k++) if (sent[k][0] === el) return sent[k][1] === el.value;
                    return true;
                }
            };
            morph(main, next.querySelector("main"), ctx);
            var band = doc.querySelector("body > header.band");
            var nextBand = next.querySelector("body > header.band");
            if (band && nextBand) morph(band, nextBand, ctx);
            if (next.title && doc.title !== next.title) doc.title = next.title;
            // A POST's answer marks its body (reconnect.js never reloads it): the fetched page is a GET's.
            if (doc.body && next.body && doc.body.hasAttribute("data-post-answer") && !next.body.hasAttribute("data-post-answer")) {
                doc.body.removeAttribute("data-post-answer");
            }
            var dy = anchorDelta(doc, chain);
            if (dy) scroller(main)(dy);
            try {
                win.history.replaceState(win.history.state, "", withoutOneShot(record.url));
            } catch (e) {}
            try {
                win.sessionStorage.removeItem(SCROLL_KEY);
            } catch (e) {}
            try {
                if (String(win.name || "").indexOf(NAME_PREFIX) === 0) win.name = "";
            } catch (e) {}
            if (record.lost) lostLine(nowOf(lastForm));
            var spoken = [];
            for (var f = 0; f < ctx.flashes.length; f++) spoken.push(ctx.flashes[f].textContent);
            if (record.lost) spoken.push(words("save-unknown"));
            say(spoken.join(" ").replace(/\s+/g, " ").replace(/^ | $/g, ""));
            dispatch(win, "handy:swapped", { root: main });
            if (landedForms.length) {
                var s = stay();
                if (s && typeof s.landed === "function") s.landed(landedForms);
                dispatch(win, "handy:saved", { forms: landedForms.slice(), pending: inflight() });
            }
        }

        /** `form` as it is on the page after a morph: itself, else the form with its id or its action. */
        function nowOf(form) {
            if (!form || connected(doc, form)) return form;
            var id = attr(form, "id");
            if (id) return doc.getElementById(id);
            var action = attr(form, "action");
            var forms = doc.querySelectorAll("form");
            for (var i = 0; i < forms.length; i++) if (attr(forms[i], "action") === action) return forms[i];
            return null;
        }

        /** §2.6: the save's answer never came - a red line in its group: what is shown now is what is stored. */
        function lostLine(form) {
            var text = words("save-unknown");
            if (!text || !doc.createElement) return;
            var line = doc.createElement("p");
            line.setAttribute("class", "flash flash-error");
            line.setAttribute("role", "status");
            line.setAttribute("data-flash", "swap-lost");
            line.textContent = text;
            // The group the form sits in, or the one it holds (Avansert's restrictions: a form around its groups).
            var group = form && form.closest ? form.closest(".group") || form.querySelector(".group") : null;
            if (group) group.appendChild(line);
            else if (form && form.parentNode) form.parentNode.insertBefore(line, form.nextSibling);
        }

        function held(record, on) {
            for (var i = 0; i < record.forms.length; i++) {
                try {
                    if (on) record.forms[i].setAttribute("data-held", "");
                    else record.forms[i].removeAttribute("data-held");
                } catch (e) {}
            }
        }

        /** The GET failed (offline, D1 a): the forms stay busy and dimmed, reconnect.js's wait takes over. */
        function offline(record) {
            held(record, true);
            dispatch(win, "handy:swapwait", { url: record.url });
            var shared = win.handyReconnect;
            if (shared && typeof shared.waitSwap === "function") {
                shared.waitSwap(record.url);
                return;
            }
            pending = null;
            reloadFor(record);
        }

        function answered(res, my) {
            if (my !== seq || !pending) return;
            // A save is on its way: its landing refreshes again and inherits this record (103-3).
            if (inflight() > 0) return;
            var record = pending;
            if (res && res.status === 423) {
                pending = null;
                var lock = win.handyLock;
                var here = win.location.pathname + win.location.search + win.location.hash;
                if (lock && typeof lock.lock === "function") lock.lock(here);
                else reloadFor(record);
                return;
            }
            var type = res && res.headers && res.headers.get ? String(res.headers.get("Content-Type") || "") : "";
            if (!res || res.type === "opaqueredirect" || res.status !== 200 || type.indexOf("text/html") < 0) {
                pending = null;
                reloadFor(record);
                return;
            }
            res.text().then(function (html) {
                if (my !== seq || pending !== record || inflight() > 0) return;
                var go = function () {
                    if (my !== seq || pending !== record || inflight() > 0) return;
                    var next;
                    try {
                        next = parse(html);
                    } catch (e) {
                        next = null;
                    }
                    pending = null;
                    if (unswappable(doc, next)) {
                        reloadFor(record);
                        return;
                    }
                    held(record, false);
                    try {
                        swap(next, record);
                    } catch (e) {
                        // A morph that throws leaves the page half made: the real page instead.
                        reloadFor(record);
                    }
                };
                // 103-6: a page still parsing would get the rest of its main appended twice.
                if (doc.readyState === "loading") doc.addEventListener("DOMContentLoaded", go);
                else go();
            }, function () {
                if (my !== seq || pending !== record) return;
                offline(record);
            });
        }

        function refresh(url, o) {
            if (!supported()) return false;
            o = o || {};
            if (!pending) pending = { url: url, forms: [], byButton: [], lost: false, fallback: null };
            var record = pending;
            record.url = url;
            var forms = o.forms || [];
            for (var i = 0; i < forms.length; i++) if (forms[i] && !has(record.forms, forms[i])) record.forms.push(forms[i]);
            var buttons = o.byButton || [];
            for (var j = 0; j < buttons.length; j++) if (buttons[j] && !has(record.byButton, buttons[j])) record.byButton.push(buttons[j]);
            if (o.lost) record.lost = true;
            // A save's own real reload (it carries scroll-restore's record) wins over a refresh's.
            if (o.fallback && (forms.length || !record.fallback)) record.fallback = o.fallback;
            var my = ++seq;
            var answer;
            try {
                answer = win.fetch(withoutHash(url), { credentials: "same-origin", redirect: "manual", cache: "no-store" });
            } catch (e) {
                pending = null;
                seq++;
                return false;
            }
            answer.then(function (res) {
                answered(res, my);
            }, function () {
                if (my !== seq || pending !== record) return;
                offline(record);
            });
            return true;
        }

        win.handySwap = {
            refresh: refresh,
            /** Whether a refresh is waiting for its answer (reconnect.js's wait asks). */
            pending: function () { return !!pending; }
        };
        return win.handySwap;
    }

    function withoutHash(url) {
        var at = String(url).indexOf("#");
        return at >= 0 ? String(url).slice(0, at) : String(url);
    }

    var api = {
        install: install,
        morph: morph,
        keyOf: keyOf,
        unswappable: unswappable,
        withoutOneShot: withoutOneShot,
        anchorChain: anchorChain,
        anchorDelta: anchorDelta,
        drawn: drawn,
        HEAD_SCRIPTS: HEAD_SCRIPTS
    };
    if (typeof module !== "undefined" && module.exports) {
        module.exports = api;
    } else if (root.document) {
        // The pure parts too, for the real-engine check (jstest/real/swap-real.js).
        install(root).api = api;
    }
})(this);
