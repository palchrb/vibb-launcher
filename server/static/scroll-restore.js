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
(function (root) {
    "use strict";
    var KEY = "handy-scroll";
    var NAME_PREFIX = "handy-scroll:";
    var MAX_AGE_MS = 20000;
    var PLACE_KEY = "handy-place";
    var MAX_PLACES = 50;
    var FROM_KEY = "handy-from";

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

    /** The referrer's path + search when it is a page of this origin, else null. */
    function referrerKey(win) {
        var ref = String((win.document && win.document.referrer) || "");
        if (!ref || typeof URL !== "function") return null;
        try {
            var url = new URL(ref);
            if (win.location.origin && url.origin !== win.location.origin) return null;
            return url.pathname + url.search;
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

        function remember(form) {
            carry({
                path: win.location.pathname,
                y: scrollerOf(win).top(),
                t: now(),
                tops: screenTops(form)
            });
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
        if (proto && !proto.handyScrollHooked) {
            var nativeSubmit = proto.submit;
            proto.submit = function () {
                remember(this);
                return nativeSubmit.apply(this, arguments);
            };
            proto.handyScrollHooked = true;
        }

        // The place record: this page's position when it goes away, for Back and reload.
        var placeKey = win.location.pathname + (win.location.search || "");
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

        // Where this page was opened from: a link from another page sets it; a save's answer (the
        // same path), a return by a child's back link, Back/Forward and a reload keep it.
        var backHere = !!(saved && saved.back === true && saved.path === win.location.pathname);
        if (storage && type === "navigate" && !backHere) {
            var from = referrerKey(win);
            if (from !== null && from.split("?")[0] !== win.location.pathname) {
                try {
                    storage.setItem(FROM_KEY, JSON.stringify(withEntry(readMap(FROM_KEY), win.location.pathname, { key: from, t: now() })));
                } catch (e) {}
            }
        }
        // A tap on the back link to the page this one came from: that page's place goes with it.
        doc.addEventListener("click", function (e) {
            if (!storage || !e || e.defaultPrevented) return;
            if (e.button || e.metaKey || e.ctrlKey || e.shiftKey || e.altKey) return;
            var link = backLinkOf(e.target);
            if (!link || typeof link.pathname !== "string") return;
            if (link.host !== undefined && win.location.host !== undefined && link.host !== win.location.host) return;
            var to = link.pathname + (link.search || "");
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
        var restore = function () {
            var y = saved ? restoreTarget(saved, path, hash, loadedAt, find) : null;
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
        FROM_KEY: FROM_KEY
    };
    if (typeof module !== "undefined" && module.exports) {
        module.exports = api;
    } else {
        install(root);
    }
})(this);
