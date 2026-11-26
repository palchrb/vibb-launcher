// No POST may make the page jump to the top (server/CLAUDE.md, design 31 §5.1). Every form posts and
// redirects back to its own group's #fragment - so without JS the answer lands on the form. With JS
// this keeps the place instead: just before any form leaves the page - a submit button (submit
// event) or script (form.submit(), as static/autosave.js does for the data-autosave controls) - it
// remembers, per path in sessionStorage for 20 s, the scroll position and where on the screen each
// element around the form was (the form and its ancestors that have an id: its row, its group).
// The page that comes back
// - with no #fragment scrolls to the same position;
// - with a #fragment inside one of those elements (the form's own group, a row of it: a new
//   contact, the file next to a deleted one) puts the nearest of them back where it was on the
//   screen - the same position when nothing above it changed, a little lower when a warning came
//   in above it; but a row that left its group (an app switched to the other group) is NOT
//   followed: the page would scroll to the other group, which reads as a jump (the user,
//   2026-10-19) - the exact position instead, the row simply gone from where it was;
// - with a #fragment the form's elements don't hold (another group, a result shown elsewhere)
//   leaves the browser at that anchor.
// Another path, or a record older than 20 s, restores nothing. Loaded from the head partial on
// every page. No framework. Tested with node (jstest/scroll-restore.test.js).
//
// The record travels in sessionStorage and, as a second carrier, in window.name (prefixed): an
// installed iPhone app has been seen to lose sessionStorage across a form's navigation, which left
// the browser at the #fragment - the page "jumped" to the group's top (the user, 2026-10-19).
// The place is put back at DOMContentLoaded, at load, and on the two frames after load, because a
// browser may still scroll to the fragment late (after fonts or images moved the page).
//
// The app shell (design 43): on the tab pages the document doesn't scroll, <main class="container">
// does (style.css); the auth pages keep the document scroll. `scrollerOf` picks the one that
// scrolls each time it is needed - never at install: this runs from the head, before <main> exists.
// A browser puts an inner scroller back only from the back-forward cache, so this script keeps
// the place too: on `pagehide` (and when the page is hidden) the position goes under path + search
// into one sessionStorage map (`handy-place`, the newest 50), and a Back/Forward or a reload puts
// it back - over a #fragment as well, since after any save the address carries its group's one and
// Back must land where the parent was, not at the group. A plain navigation (a link) restores no
// place: the fragment or the top, as before. The form record wins over the place record. On the
// shell's pages the window is put back at 0 when iOS leaves it offset (the keyboard's pan).
//
// The band's back link (design 84, the user's iPhone, 2026-11-11): a page under a tab goes back by
// a plain link, often to the row it was opened from (`/apps#phone-3`), and with the band scrolling
// away the fragment's jump put that row at the screen's top - not where the parent was left. So a
// page reached by a link remembers which page it came from (`handy-from`, its path -> the
// referrer's path + search, kept across its own saves and a return to it by its child's back link),
// and a tap on its back link (`a.nav-back`) to exactly that page leaves a record like a save's,
// `back: true`, with the parent's place record: the parent then opens where it was left, over its
// #fragment. A parent never left in this session, a back link to another page than the one it came
// from, a link opened directly or no JS: the plain link, its fragment or the top, as before.
// A save's answer on another path takes the form page's entry (an Add's `/new` -> `/5`), never one
// that names the answer itself (a child's Delete back to its parent keeps the parent's); a link load
// with no referrer of this origin (a notification, a typed address) forgets the page's entry. Pages
// are compared without the one-shot query words a save adds (`notice`, `app`, `saved`), which
// reconnect.js and auto-reload.js take off the address again.
//
// A save leaves the history as it was (the user, 2026-11-14: Check now on an app, then Back, showed
// the same app page again - the POST and its 303 back had added an entry). A form that comes back
// to its page - a POST whose action has a #fragment, the forms that leave their page have none
// (settings_pages::check_forms) - is posted by this script (`fetch`, header `X-Handy-Stay`), and the
// server answers its redirect as a 204 naming the address, fragment and all (`X-Handy-Location`,
// admin_auth::stay_answer). The same page: the entry is replaced with that address and reloaded (a
// bare `location.replace` to the same page with another fragment would only scroll); another page:
// opened as a link would be. The place record above works as before; a #fragment the form's groups
// don't hold is the anchor (the record is marked `stay`, so the reload's place record doesn't win).
// While a form's save is out it is busy (`aria-busy`, its button disabled): a second tap does
// nothing, a switch's next save goes after it; the page lands once, when the last save in flight
// has answered. Left to the browser as before: no JS or no fetch; a form with a `target`,
// `data-native-post` (its POST is answered with a page, not a redirect: Show Child PIN, the music
// import's upload, Add Parent, a parent's Reset, the setup QR) or a password, file or passkey field
// (a refusal posted again would count a password twice, spend a passkey's challenge, send a file
// twice). A refusal (400, 403, 404, 409, 422, 423, a 200 refused form) is sent again the browser's
// way, which shows it; no answer, a 5xx, a redirect past the server's stay answer or an address
// that isn't an http(s) one of this origin never posts again - the server may have acted - and the
// page is loaded with a GET instead, showing what the server holds.
//
// Saves in place (design 103 §2.2): a save that lands on this page is no longer a reload. Its landing goes to
// static/swap.js (`handySwap.refresh`, with the saved forms and the buttons that sent them), which fetches the page and
// changes only what differs; an answer not had refreshes it in place too, with a red line (`lost`). The landed forms
// stay busy until that morph is done: a flip in the gap is queued (`again`) and sent right after it, and only the landed
// forms' entries are released, never the busy marks of another save started meanwhile. swap.js reads this script's
// bookkeeping through `window.handyStay` (`inflight`, `entry`, `isSubmitter`, `landed`). A save with no answer after
// 400 ms shows reconnect.js's thin bar (`handyReconnect.busy`), a counter of its own, until its form is released.
// No swap.js, or a page it can't swap: the reload as before.
(function (root) {
    "use strict";
    var KEY = "handy-scroll";
    var NAME_PREFIX = "handy-scroll:";
    var MAX_AGE_MS = 20000;
    var PLACE_KEY = "handy-place";
    var MAX_PLACES = 50;
    var FROM_KEY = "handy-from";
    // A save's one-shot words in the address: not part of which page it is.
    // (design 103's code QA L1: every flash's word, swap.js's ONE_SHOT too.)
    var ONE_SHOT = { notice: 1, app: 1, saved: 1, done: 1, cancel: 1, changed: 1, install: 1, music_notice: 1,
        music_entry: 1, avatar: 1, parents: 1, cleared: 1, removed: 1, held: 1, unbanned: 1 };
    // admin_auth::STAY_HEADER and STAY_LOCATION_HEADER.
    var STAY_HEADER = "X-Handy-Stay";
    var STAY_LOCATION = "X-Handy-Location";
    // Fields whose form the browser posts itself (a refusal posted again must not repeat them).
    var ONE_SHOT_TYPES = { password: 1, file: 1 };
    var ONE_SHOT_NAMES = { assertion: 1, credential: 1 };
    // The answers where the server refused before acting (a 200 is a refused form shown again: the forms
    // answered with a page on success are `data-native-post`): the only ones a save is sent again on.
    var REFUSED = { 200: 1, 400: 1, 403: 1, 404: 1, 409: 1, 422: 1, 423: 1 };
    // A save with no answer by then shows the thin bar (design 103 D5; reconnect.js's BAR_AFTER).
    var BAR_AFTER = 400;

    /** `el`'s attribute `name`, or null when it has none. */
    function attr(el, name) {
        return el && el.hasAttribute && el.hasAttribute(name) ? String(el.getAttribute(name)) : null;
    }

    /**
     * How a save that comes back to its page is posted by this script: `{ url, multipart }` (its action
     * as an absolute address, its fragment kept), or null when the browser posts it as before (see the
     * top). `submitter` is the button that sent it, or null; `href` the page's address.
     */
    function stayPost(form, submitter, href) {
        if (!form || typeof URL !== "function") return null;
        var method = attr(submitter, "formmethod") || attr(form, "method") || "get";
        if (method.toLowerCase() !== "post") return null;
        var target = attr(submitter, "formtarget") || attr(form, "target");
        if (target && target.toLowerCase() !== "_self") return null;
        if (attr(form, "data-native-post") !== null) return null;
        var fields = form.elements || [];
        for (var i = 0; i < fields.length; i++) {
            var type = String(fields[i].type || "").toLowerCase();
            if (ONE_SHOT_TYPES[type] === 1 || ONE_SHOT_NAMES[String(fields[i].name || "")] === 1) return null;
        }
        var action = attr(submitter, "formaction");
        if (action === null) action = attr(form, "action") || "";
        var url;
        var here;
        try {
            url = new URL(action, href);
            here = new URL(href);
        } catch (e) {
            return null;
        }
        if (url.origin !== here.origin || url.hash.length < 2) return null;
        var enctype = attr(submitter, "formenctype") || attr(form, "enctype") || "";
        return { url: url.href, multipart: enctype.toLowerCase() === "multipart/form-data" };
    }

    /**
     * Where a stayed save goes on to: `{ reload: true, url }` for this page (its entry replaced with
     * `url`, then reloaded), `{ reload: false, url }` for another page of this origin (opened as a link
     * would be), or null for anything but an http(s) address of this origin.
     */
    function stayLanding(to, href) {
        var url;
        var here;
        try {
            url = new URL(to, href);
            here = new URL(href);
        } catch (e) {
            return null;
        }
        // Only an http(s) address of this origin (code QA L3: never a `javascript:` one).
        if ((url.protocol !== "http:" && url.protocol !== "https:") || url.origin !== here.origin) return null;
        return { reload: url.pathname === here.pathname, url: url.href };
    }

    function has(tops, id) {
        return Object.prototype.hasOwnProperty.call(tops, id) && typeof tops[id] === "number";
    }

    /**
     * What scrolls the page now: <main> when its overflow-y scrolls (the shell), else the window
     * (the auth pages, a page without <main>). `top()` is its position, `scrollTo(y)` sets it,
     * `inner` says it is <main>.
     */
    function scrollerOf(win) {
        var doc = win.document;
        var main = doc && doc.querySelector ? doc.querySelector("main") : null;
        var overflow = "";
        if (main && win.getComputedStyle) {
            try {
                overflow = win.getComputedStyle(main).overflowY;
            } catch (e) {}
        }
        if (overflow === "auto" || overflow === "scroll") {
            return {
                inner: true,
                top: function () { return main.scrollTop; },
                scrollTo: function (y) { main.scrollTop = y; }
            };
        }
        return {
            inner: false,
            top: function () { return win.scrollY || 0; },
            scrollTo: function (y) { win.scrollTo(0, y); }
        };
    }

    /**
     * The navigation that loaded the page: "navigate", "reload" or "back_forward". iOS 14 has no
     * PerformanceNavigationTiming: `performance.navigation.type` (1 reload, 2 back/forward) there.
     */
    function navigationType(win) {
        var perf = win.performance;
        if (!perf) return "navigate";
        try {
            var entries = perf.getEntriesByType ? perf.getEntriesByType("navigation") : null;
            if (entries && entries.length && entries[0].type) return entries[0].type;
        } catch (e) {}
        var nav = perf.navigation;
        if (nav && nav.type === 1) return "reload";
        if (nav && nav.type === 2) return "back_forward";
        return "navigate";
    }

    /** `map` with `entry` (it has its time, `t`) under `key`, the newest MAX_PLACES kept. */
    function withEntry(map, key, entry) {
        var next = {};
        var keys = [];
        for (var k in map) {
            var p = map[k];
            if (k !== key && Object.prototype.hasOwnProperty.call(map, k) && p && typeof p.t === "number") {
                keys.push(k);
            }
        }
        keys.sort(function (a, b) { return map[b].t - map[a].t; });
        keys = keys.slice(0, MAX_PLACES - 1);
        for (var i = 0; i < keys.length; i++) next[keys[i]] = map[keys[i]];
        next[key] = entry;
        return next;
    }

    /** `places` with `key` at `y`, the newest MAX_PLACES kept. */
    function withPlace(places, key, y, t) {
        return withEntry(places, key, { y: y, t: t });
    }

    /** A page's key: its path and its query without a save's one-shot words. */
    function pageKey(path, search) {
        var parts = String(search || "").replace(/^\?/, "").split("&");
        var kept = [];
        for (var i = 0; i < parts.length; i++) {
            if (parts[i] && ONE_SHOT[parts[i].split("=")[0]] !== 1) kept.push(parts[i]);
        }
        return path + (kept.length ? "?" + kept.join("&") : "");
    }

    /** The referrer's page key when it is a page of this origin, else null. */
    function referrerKey(win) {
        var ref = String((win.document && win.document.referrer) || "");
        if (!ref || typeof URL !== "function") return null;
        try {
            var url = new URL(ref);
            if (win.location.origin && url.origin !== win.location.origin) return null;
            return pageKey(url.pathname, url.search);
        } catch (e) {
            return null;
        }
    }

    /** The band's back link (`a.nav-back`) at or above `el`, or null. */
    function backLinkOf(el) {
        for (; el; el = el.parentNode) {
            if (el.tagName && String(el.tagName).toUpperCase() === "A") {
                return (" " + el.className + " ").indexOf(" nav-back ") >= 0 ? el : null;
            }
        }
        return null;
    }

    /**
     * The y to restore, or null (the browser keeps its own place or anchor). `find(id, tops)` looks
     * the #fragment's element up: undefined when the page has none, else the nearest of it and its
     * ancestors whose id is in `tops`, as `{ id, top }` (where its top would be on the screen with
     * the page scrolled to 0), or null.
     */
    function restoreTarget(saved, path, hash, now, find) {
        if (!saved || typeof saved.y !== "number" || typeof saved.t !== "number") return null;
        if (saved.path !== path) return null;
        var age = now - saved.t;
        if (age < 0 || age >= MAX_AGE_MS) return null;
        // The band's back link: where the parent was left, over its #fragment.
        if (saved.back === true) return saved.y;
        if (!hash || hash === "#") return saved.y;
        var tops = saved.tops;
        if (!tops || typeof tops !== "object") return null;
        var id = hash.slice(1);
        try {
            id = decodeURIComponent(id);
        } catch (e) {}
        var found = find(id, tops);
        // The form's group is gone from the page that came back: the same position.
        if (found === undefined) return has(tops, id) ? saved.y : null;
        if (!found) return null;
        // The element is no longer inside every recorded ancestor it was in (the row moved to
        // another group): the same position, never the other group's.
        for (var key in tops) {
            if (has(tops, key) && found.chain.indexOf(key) < 0) return saved.y;
        }
        return found.top - tops[found.id];
    }

    /** The form and its ancestors that have an id, each with its top on the screen now. */
    function screenTops(form) {
        var tops = {};
        for (var el = form; el && el.getBoundingClientRect; el = el.parentNode) {
            if (el.id && !Object.prototype.hasOwnProperty.call(tops, el.id)) {
                tops[el.id] = el.getBoundingClientRect().top;
            }
        }
        return tops;
    }

    function install(win, now) {
        now = now || function () { return Date.now(); };
        var doc = win.document;
        var storage = null;
        try {
            storage = win.sessionStorage;
        } catch (e) {}

        // The last form record, marked `stay` when its save reloads the page (land).
        var lastRecord = null;
        function remember(form) {
            lastRecord = {
                path: win.location.pathname,
                y: scrollerOf(win).top(),
                t: now(),
                tops: screenTops(form)
            };
            carry(lastRecord);
        }

        /** The record for the next page, in both carriers. */
        function carry(value) {
            var record = JSON.stringify(value);
            if (storage) {
                try {
                    storage.setItem(KEY, record);
                } catch (e) {}
            }
            try {
                win.name = NAME_PREFIX + record;
            } catch (e) {}
        }

        doc.addEventListener("submit", function (e) { remember(e && e.target); }, true);
        var proto = win.HTMLFormElement && win.HTMLFormElement.prototype;
        // The browser's own form.submit(), kept on the prototype by the first install (never the hook).
        var nativeSubmit = proto && (proto.handyNativeSubmit || proto.submit);

        // A save that comes back to its page, posted here so the history stays as it was (see the top).
        // The form the browser is sending again after a refusal (it shows the refusal's page).
        var passing = null;
        // The stay saves in flight (one per form at a time), the outcome to act on once the last
        // settles, and each form's save asked for again while its first was out.
        var inflight = 0;
        var outcome = null;
        var busy = [];
        function isBusy(form) {
            for (var i = 0; i < busy.length; i++) if (busy[i].form === form) return busy[i];
            return null;
        }
        /** reconnect.js's bar counter (design 103 D5): +1 while a slow save is out, -1 when its form is released. */
        function bar(n) {
            var shared = win.handyReconnect;
            if (shared && typeof shared.busy === "function") shared.busy(n);
        }
        /**
         * The form's text fields as they were sent (design 103-9, code QA L2): after the save lands each takes the server's
         * value only while it still holds this one - typed on during the round trip, it keeps the parent's text.
         */
        var TEXTLESS = { hidden: 1, submit: 1, button: 1, reset: 1, image: 1, checkbox: 1, radio: 1, file: 1, range: 1, color: 1 };
        function sentOf(form) {
            var out = [];
            var fields = (form && form.elements) || [];
            for (var i = 0; i < fields.length; i++) {
                var el = fields[i];
                var tag = String(el.tagName || "").toUpperCase();
                if (tag === "TEXTAREA" || (tag === "INPUT" && TEXTLESS[String(el.type || "text").toLowerCase()] !== 1)) {
                    out.push([el, el.value]);
                }
            }
            return out;
        }
        function markBusy(form, submitter) {
            var entry = { form: form, submitter: submitter, again: false, sent: sentOf(form), bar: false, timer: null };
            busy.push(entry);
            if (typeof win.setTimeout === "function") {
                entry.timer = win.setTimeout(function () {
                    entry.timer = null;
                    entry.bar = true;
                    bar(1);
                }, BAR_AFTER);
            }
            try {
                form.setAttribute("aria-busy", "true");
                if (submitter) {
                    submitter.setAttribute("aria-busy", "true");
                    submitter.disabled = true;
                }
            } catch (e) {}
        }
        function unmark(entry) {
            if (entry.timer !== null && typeof win.clearTimeout === "function") win.clearTimeout(entry.timer);
            entry.timer = null;
            if (entry.bar) {
                entry.bar = false;
                bar(-1);
            }
            try {
                entry.form.removeAttribute("aria-busy");
                if (entry.submitter) {
                    entry.submitter.removeAttribute("aria-busy");
                    entry.submitter.disabled = false;
                }
            } catch (e) {}
        }
        function releaseAll() {
            for (var i = 0; i < busy.length; i++) unmark(busy[i]);
            busy = [];
        }
        /** The page with a GET: what the server holds now (`replaceState` first, so a POST's entry isn't sent again). */
        function reloadHere() {
            land({ reload: true, url: win.location.href });
        }
        /**
         * The landing on this page in place (design 103 §2.2): swap.js refreshes it with every busy form (all have
         * landed: nothing is in flight) and keeps them busy until its morph is done (`landed`). `lost`: the answer
         * never came, so the page shows what is stored with a red line. No swap.js, or it says no: `land` as before.
         */
        function landHere(to, lost) {
            var swap = win.handySwap;
            if (swap && typeof swap.refresh === "function") {
                var forms = [];
                var buttons = [];
                for (var i = 0; i < busy.length; i++) {
                    forms.push(busy[i].form);
                    if (busy[i].submitter) buttons.push(busy[i].submitter);
                }
                var taken = false;
                try {
                    taken = swap.refresh(to.url, {
                        forms: forms,
                        byButton: buttons,
                        lost: !!lost,
                        fallback: function () { land(to); }
                    });
                } catch (e) {}
                if (taken) return;
            }
            land(to);
        }
        function land(to) {
            if (!to.reload) {
                win.location.assign(to.url);
                return;
            }
            // The record goes with the reload marked: a #fragment the form's groups don't hold is the
            // browser's anchor, never the old place (code QA L2).
            if (lastRecord) {
                lastRecord.stay = true;
                carry(lastRecord);
            }
            try {
                win.history.replaceState(win.history.state, "", to.url);
            } catch (e) {
                win.location.replace(to.url);
                return;
            }
            win.location.reload();
        }
        /** A refused save sent again the browser's way, so its page shows; a send that throws: the page with a GET. */
        function browserPost(form, submitter, hooked) {
            try {
                if (hooked) {
                    nativeSubmit.call(form);
                    return;
                }
                // requestSubmit keeps the button's name, value and formaction; this listener lets it pass.
                passing = form;
                try {
                    form.requestSubmit(submitter || undefined);
                } finally {
                    passing = null;
                }
            } catch (e) {
                reloadHere();
            }
        }
        // What an answer asks for, by weight: a refusal must show (resend), an answer not had means a
        // GET of the page (reload), else where the last save landed (land).
        var WEIGHT = { land: 1, reload: 2, resend: 3 };
        function settle(next) {
            if (!outcome || WEIGHT[next.kind] >= WEIGHT[outcome.kind]) outcome = next;
            if (--inflight > 0) return;
            var o = outcome;
            outcome = null;
            if (o.kind === "land" && o.to.reload) {
                landHere(o.to, false);
                return;
            }
            if (o.kind === "reload") {
                landHere({ reload: true, url: win.location.href }, true);
                return;
            }
            releaseAll();
            if (o.kind === "resend") browserPost(o.form, o.submitter, o.hooked);
            else land(o.to);
        }
        /** What the answer to a stay save asks for (code QA H1, L3, L4). */
        function verdict(res) {
            var to = res && res.headers && res.headers.get(STAY_LOCATION);
            if (res && res.status === 204 && to) {
                var landing = stayLanding(to, win.location.href);
                // Saved, but an address that isn't an http(s) one of this origin: the page with a GET.
                return landing ? { kind: "land", to: landing } : { kind: "reload" };
            }
            // A redirect past the server's stay answer (a sign-in portal in front of it): saved or not is
            // unknown - the page with a GET, which the portal then takes.
            if (res && res.type === "opaqueredirect") return { kind: "reload" };
            // A refusal, before the server acted: sent again the browser's way, which shows its page.
            if (res && REFUSED[res.status] === 1) return { kind: "resend" };
            // A 5xx or anything else: the server may have acted - never posted again.
            return { kind: "reload" };
        }
        /**
         * Design 135 (code QA M1): a form that posts only what differs from the values it was drawn with (`data-diff`,
         * its hidden `was_<name>` fields) is about to send again before the page is redrawn - a switch flipped twice in
         * one round trip. Its `was_` fields take the values the save just out posted (`posted`, the body's pairs; a
         * checkbox `1` when it was sent, else `0`), so the next save diffs against what the server now has and ends at
         * the parent's last tap. Only for this resend: a refusal sent again the browser's way keeps the page's own.
         */
        function postedAsWas(form, posted) {
            if (!form || typeof form.hasAttribute !== "function" || !form.hasAttribute("data-diff")) return;
            var fields = form.elements || [];
            var byName = {};
            for (var i = 0; i < fields.length; i++) {
                if (fields[i].name && !byName[fields[i].name]) byName[fields[i].name] = fields[i];
            }
            var sent = {};
            for (var p = 0; p < posted.length; p++) {
                if (!(posted[p][0] in sent)) sent[posted[p][0]] = posted[p][1];
            }
            for (var j = 0; j < fields.length; j++) {
                var was = fields[j];
                var name = String(was.name || "");
                if (name.indexOf("was_") !== 0) continue;
                var key = name.slice(4);
                var target = byName[key];
                if (!target) continue;
                if (String(target.type || "").toLowerCase() === "checkbox") {
                    was.value = key in sent ? "1" : "0";
                } else if (key in sent) {
                    was.value = sent[key];
                }
            }
        }
        /** Sends `form`'s stay save now (its fields as they are). */
        function send(form, submitter, hooked, how) {
            var body = new win.FormData(form);
            if (submitter && submitter.name) body.append(submitter.name, submitter.value || "");
            var posted = [];
            body.forEach(function (value, key) { posted.push([key, value]); });
            if (!how.multipart) {
                var fields = new win.URLSearchParams();
                body.forEach(function (value, key) { fields.append(key, value); });
                body = fields;
            }
            var headers = {};
            headers[STAY_HEADER] = "1";
            var done = function (next) {
                var entry = isBusy(form);
                // Asked for again meanwhile (an auto-saving switch flipped twice): its next save goes
                // after this one, in order, before anything lands.
                if (entry && entry.again) {
                    entry.again = false;
                    postedAsWas(form, posted);
                    entry.sent = sentOf(form);
                    var nextHow = stayPost(form, null, win.location.href);
                    if (nextHow) {
                        try {
                            send(form, null, true, nextHow);
                        } catch (e) {}
                    }
                }
                next.form = form;
                next.submitter = submitter;
                next.hooked = hooked;
                settle(next);
            };
            var answer = win.fetch(how.url, {
                method: "POST",
                body: body,
                credentials: "same-origin",
                redirect: "manual",
                headers: headers
            });
            inflight++;
            answer.then(function (res) {
                done(verdict(res));
            }, function () {
                // No answer (the network, an app sent to the background): the server may have acted.
                done({ kind: "reload" });
            });
        }
        /** Posts `form` itself when it is a save that comes back to its page; false: the browser's way. */
        function stay(form, submitter, hooked) {
            if (typeof win.fetch !== "function" || typeof win.FormData !== "function") return false;
            if (!win.history || typeof win.history.replaceState !== "function") return false;
            if (hooked ? typeof nativeSubmit !== "function" : typeof form.requestSubmit !== "function") return false;
            var how = stayPost(form, submitter, win.location.href);
            if (!how) return false;
            var entry = isBusy(form);
            if (entry) {
                // Its save is out: a second tap does nothing; a switch's save goes after it (code QA M1, L1).
                if (hooked) entry.again = true;
                return true;
            }
            try {
                send(form, submitter, hooked, how);
            } catch (e) {
                return false;
            }
            markBusy(form, submitter);
            return true;
        }
        // swap.js's view of the saves (design 103 §2.2): how many are out, a form's busy entry, a busy button, and the
        // landed forms released once the morph is done - each one asked for again meanwhile is sent now.
        win.handyStay = {
            inflight: function () { return inflight; },
            entry: isBusy,
            isSubmitter: function (el) {
                for (var i = 0; i < busy.length; i++) if (el && busy[i].submitter === el) return true;
                return false;
            },
            landed: function (forms) {
                var again = [];
                var kept = [];
                for (var i = 0; i < busy.length; i++) {
                    var landed = false;
                    for (var j = 0; j < forms.length; j++) if (forms[j] === busy[i].form) landed = true;
                    // A form whose save started in the gap is not among them: it keeps its marks.
                    if (!landed) {
                        kept.push(busy[i]);
                        continue;
                    }
                    unmark(busy[i]);
                    if (busy[i].again) again.push(busy[i].form);
                }
                busy = kept;
                for (var k = 0; k < again.length; k++) {
                    // A form the morph took off the page is not sent again (code QA L8).
                    if (again[k].isConnected === false) continue;
                    var how = stayPost(again[k], null, win.location.href);
                    if (!how) continue;
                    try {
                        send(again[k], null, true, how);
                        markBusy(again[k], null);
                    } catch (e) {}
                }
            }
        };

        // On the window, after every other submit listener: one that took the submit over
        // (passkey.js, register-sw.js) has prevented it, and a browser without `submitter` posts as before.
        win.addEventListener("submit", function (e) {
            var form = e && e.target;
            if (!form || e.defaultPrevented || form === passing || !("submitter" in e)) return;
            if (stay(form, e.submitter || null, false)) e.preventDefault();
        });

        if (proto && !proto.handyScrollHooked) {
            proto.handyNativeSubmit = nativeSubmit;
            proto.submit = function () {
                remember(this);
                if (stay(this, null, true)) return;
                return nativeSubmit.apply(this, arguments);
            };
            proto.handyScrollHooked = true;
        }

        // The place record: this page's position when it goes away, for Back and reload.
        var placeKey = pageKey(win.location.pathname, win.location.search);
        function readMap(key) {
            try {
                var map = JSON.parse(storage.getItem(key) || "null");
                return map && typeof map === "object" ? map : {};
            } catch (e) {
                return {};
            }
        }
        function readPlaces() {
            return readMap(PLACE_KEY);
        }
        function keepPlace() {
            if (!storage) return;
            try {
                storage.setItem(PLACE_KEY, JSON.stringify(withPlace(readPlaces(), placeKey, scrollerOf(win).top(), now())));
            } catch (e) {}
        }
        win.addEventListener("pagehide", keepPlace);
        doc.addEventListener("visibilitychange", function () {
            if (doc.visibilityState === "hidden") keepPlace();
        });

        // iOS can leave the window panned after the keyboard closes, although the shell's body
        // doesn't scroll: put it back (a no-op everywhere else; the auth pages scroll the window).
        function unpan() {
            if (win.scrollY && scrollerOf(win).inner) win.scrollTo(0, 0);
        }
        win.addEventListener("pageshow", unpan);
        doc.addEventListener("focusout", unpan);
        if (win.visualViewport && win.visualViewport.addEventListener) {
            win.visualViewport.addEventListener("resize", unpan);
        }

        var saved = null;
        if (storage) {
            try {
                saved = JSON.parse(storage.getItem(KEY) || "null");
                storage.removeItem(KEY);
            } catch (e) {}
        }
        try {
            var named = String(win.name || "");
            if (named.slice(0, NAME_PREFIX.length) === NAME_PREFIX) {
                if (!saved) saved = JSON.parse(named.slice(NAME_PREFIX.length));
                win.name = "";
            }
        } catch (e) {}
        // The record taken, for reconnect.js's sign-in check (it runs deferred, after both
        // carriers were cleared here; qa-pwa-fixes-d Q-6).
        if (saved) win.handyPlaceRecord = saved;
        var place = null;
        var type = navigationType(win);

        // Where this page was opened from: a link from another page sets it, one with no referrer of
        // this origin clears it; a save's answer takes the form page's (another path) or keeps its
        // own; a return by a child's back link, Back/Forward and a reload keep it.
        var here = win.location.pathname;
        var backHere = !!(saved && saved.back === true && saved.path === here);
        var savedAge = saved && typeof saved.t === "number" ? now() - saved.t : -1;
        var answer = !!(saved && !saved.back && typeof saved.path === "string" && savedAge >= 0 && savedAge < MAX_AGE_MS);
        if (storage && type === "navigate" && !backHere) {
            var froms = readMap(FROM_KEY);
            var next;
            if (answer) {
                var formFrom = froms[saved.path];
                if (saved.path !== here && formFrom && typeof formFrom.key === "string" && formFrom.key.split("?")[0] !== here) {
                    next = { key: formFrom.key, t: now() };
                }
            } else {
                var from = referrerKey(win);
                if (from === null) next = null;
                else if (from.split("?")[0] !== here) next = { key: from, t: now() };
            }
            try {
                if (next) {
                    storage.setItem(FROM_KEY, JSON.stringify(withEntry(froms, here, next)));
                } else if (next === null && Object.prototype.hasOwnProperty.call(froms, here)) {
                    delete froms[here];
                    storage.setItem(FROM_KEY, JSON.stringify(froms));
                }
            } catch (e) {}
        }
        // A tap on the back link to the page this one came from: that page's place goes with it.
        doc.addEventListener("click", function (e) {
            if (!storage || !e || e.defaultPrevented) return;
            if (e.button || e.metaKey || e.ctrlKey || e.shiftKey || e.altKey) return;
            var link = backLinkOf(e.target);
            if (!link || typeof link.pathname !== "string") return;
            if (link.host !== undefined && win.location.host !== undefined && link.host !== win.location.host) return;
            var to = pageKey(link.pathname, link.search);
            var came = readMap(FROM_KEY)[win.location.pathname];
            var left = readPlaces()[to];
            if (!came || came.key !== to || !left || typeof left.y !== "number") return;
            carry({ path: link.pathname, href: to, y: left.y, t: now(), back: true });
        });
        if (storage && (type === "back_forward" || type === "reload")) {
            var stored = readPlaces()[placeKey];
            if (stored && typeof stored.y === "number") place = stored.y;
        }
        if (!saved && place === null) return;

        /**
         * The nearest of the fragment's element and its ancestors whose id was recorded, with its
         * top on the screen as if the page were scrolled to 0 (its top now plus the scroller's
         * position: the saved tops are screen tops, so where the scroller sits on the screen
         * cancels out), and `chain`: every recorded id among its ancestors (itself included).
         */
        function find(id, tops) {
            var el = doc.getElementById(id);
            if (!el) return undefined;
            var scrolled = scrollerOf(win).top();
            var found = null;
            var chain = [];
            for (; el && el.getBoundingClientRect; el = el.parentNode) {
                if (el.id && has(tops, el.id)) {
                    chain.push(el.id);
                    if (!found) found = { id: el.id, top: el.getBoundingClientRect().top + scrolled };
                }
            }
            if (found) found.chain = chain;
            return found;
        }
        var path = win.location.pathname;
        var hash = win.location.hash;
        var loadedAt = now();
        // Once the page is parsed (the #fragment's element exists), and again at `load`: the place
        // can still move while images and fonts come in.
        // A stay save's reload (land): a #fragment the form's groups don't hold is the anchor, as after
        // the browser's own 303 - never the place record a reload would otherwise bring (code QA L2).
        var stayed = !!(saved && saved.stay === true && saved.path === path && typeof saved.t === "number" &&
            loadedAt - saved.t >= 0 && loadedAt - saved.t < MAX_AGE_MS);
        var restore = function () {
            var y = saved ? restoreTarget(saved, path, hash, loadedAt, find) : null;
            if (y === null && stayed) {
                var id = hash ? hash.slice(1) : "";
                try {
                    id = decodeURIComponent(id);
                } catch (e) {}
                var anchor = id ? doc.getElementById(id) : null;
                if (anchor && anchor.scrollIntoView) {
                    if (win.history && "scrollRestoration" in win.history) win.history.scrollRestoration = "manual";
                    anchor.scrollIntoView(true);
                }
                return;
            }
            if (y === null) y = place;
            if (y === null) return;
            if (win.history && "scrollRestoration" in win.history) win.history.scrollRestoration = "manual";
            scrollerOf(win).scrollTo(y);
        };
        if (doc.readyState === "loading") {
            doc.addEventListener("DOMContentLoaded", restore);
        } else {
            restore();
        }
        // Then the entry goes back to the browser: the record is used up, so Back to this page later
        // must find the place the browser keeps, not the top (qa-browser-fixes-code M1). The place is
        // put back once more on each of the next two frames: a late scroll to the fragment (iOS) must
        // not win over it.
        win.addEventListener("load", function () {
            restore();
            var frames = 2;
            var tick = function () {
                restore();
                if (--frames > 0 && typeof win.requestAnimationFrame === "function") {
                    win.requestAnimationFrame(tick);
                    return;
                }
                if (win.history && "scrollRestoration" in win.history) win.history.scrollRestoration = "auto";
            };
            if (typeof win.requestAnimationFrame === "function") {
                win.requestAnimationFrame(tick);
            } else if (win.history && "scrollRestoration" in win.history) {
                win.history.scrollRestoration = "auto";
            }
        });
    }

    var api = {
        install: install,
        restoreTarget: restoreTarget,
        scrollerOf: scrollerOf,
        navigationType: navigationType,
        KEY: KEY,
        NAME_PREFIX: NAME_PREFIX,
        MAX_AGE_MS: MAX_AGE_MS,
        PLACE_KEY: PLACE_KEY,
        MAX_PLACES: MAX_PLACES,
        FROM_KEY: FROM_KEY,
        stayPost: stayPost,
        stayLanding: stayLanding,
        STAY_HEADER: STAY_HEADER,
        STAY_LOCATION: STAY_LOCATION
    };
    if (typeof module !== "undefined" && module.exports) {
        module.exports = api;
    } else {
        install(root);
    }
})(this);
