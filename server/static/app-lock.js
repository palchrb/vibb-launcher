// The app lock's page side (design 74 §3, step 3: "Lås med Face ID"). The lock is the server's (`lock::check`: past
// the time a navigation goes to /lock, anything else is 423); this script only keeps anything private from showing
// first. Loaded from the head on every page, not deferred: it decides before the body paints.
// - A page of a lock-on session carries `<html data-lock-ms="<lock time>" data-lock-idle="<ms since the session's
//   last use>">` (the server's numbers, relative, so the phone's clock doesn't matter). Without them nothing happens
//   here but `window.handyLock` (due() is false, lock() goes to /lock).
// - The last input (a pointer or key event) is kept in sessionStorage (`handy-used`), so it survives the page's own
//   reloads and navigations; the later of it and the server's last use counts (an unreadable store is the server's).
// - Due = the lock time since the last input. Then `html.locked` (style.css hides `body > :not(.top-strip)`) and
//   `location.replace("/lock?next=<this path>")` - at once at load, when the page becomes visible or comes back from
//   the back-forward cache, when the time runs out on screen, or at an input that comes too late.
// - A history load (Back/Forward) or a page served from the HTTP cache counts only the stored input, and none is due:
//   its server numbers are old (qa-passkeys-3 client M1; the server also serves a lock-on session's pages `no-store`).
// - Time within the page is the longer of the page's own clock (`performance.now()`) and the wall clock, so neither a
//   clock set back nor a sleeping phone holds the lock (L1). Only real input counts (`isTrusted`).
// - On a lock-on page `static/vibb-rest.svg` is fetched at once: style.css draws it on the night ground of a locked or
//   veiled page (the start-up splash's picture), and it must be there before the first lock.
// - Hidden: `html.veiled` too (best effort: the iOS app switcher's picture may be taken before it), and the title is
//   "Vibb" while hidden or locked.
// - The input beacon `POST /api/used` (the server's "used", D3.3): on an input while the page is visible, at most
//   once a minute (sessionStorage `handy-used-sent`); a 423 goes to the lock.
// - `window.handyLock.due()` / `.lock(next)`: auto-reload.js, install-now.js, pull-refresh.js and reconnect.js ask it
//   before a reload and go to the lock instead.
// ES5, no framework (Chrome/WebView 80, iOS Safari 14). Run its tests: node --test jstest/app-lock.test.js
(function (root) {
    "use strict";

    var USED_KEY = "handy-used";
    var SENT_KEY = "handy-used-sent";
    var BEACON_EVERY = 60 * 1000;
    var BEACON_URL = "/api/used";
    var LOCK_PATH = "/lock";
    var REST_MARK = "/static/vibb-rest.svg";
    // Away (hidden) longer than this, the PWA opens on Devices (2026-11-03): a lock due on the return goes to /lock with
    // `next` = "/", not the page left. The one rule of it: reconnect.js reads it as `window.handyLock.homeAfter` for its
    // own return to "/", so the two never differ. This script decides first (it is not deferred), hence it applies it.
    var HOME_AFTER = 10 * 60 * 1000;
    var HOME_PATH = "/";

    /** The lock screen's address for `next` (a path of this app). */
    function lockUrl(next) {
        return LOCK_PATH + (next ? "?next=" + encodeURIComponent(next) : "");
    }

    /** This page's path, query and #fragment. */
    function here(win) {
        var l = win.location;
        return (l.pathname || "/") + (l.search || "") + (l.hash || "");
    }

    function readTime(win, key) {
        try {
            var value = parseInt(win.sessionStorage.getItem(key), 10);
            return isNaN(value) ? null : value;
        } catch (e) {
            return null;
        }
    }

    function writeTime(win, key, at) {
        try {
            win.sessionStorage.setItem(key, String(at));
        } catch (e) {}
    }

    function addClass(el, name) {
        if (!el) return;
        if (el.classList) el.classList.add(name);
        else if ((" " + (el.className || "") + " ").indexOf(" " + name + " ") < 0) el.className = ((el.className || "") + " " + name).trim();
    }

    function removeClass(el, name) {
        if (!el) return;
        if (el.classList) el.classList.remove(name);
        else el.className = (" " + (el.className || "") + " ").replace(" " + name + " ", " ").trim();
    }

    /**
     * How this page was loaded: a history load (Back/Forward) or one served from the HTTP cache, whose server numbers
     * (`data-lock-idle`) are from when it was first served (qa-passkeys-3 client M1).
     */
    function oldPage(win) {
        var perf = win.performance;
        try {
            var entry = perf && typeof perf.getEntriesByType === "function" ? perf.getEntriesByType("navigation")[0] : null;
            if (entry) return entry.type === "back_forward" || (entry.transferSize === 0 && entry.decodedBodySize > 0);
            return !!(perf && perf.navigation && perf.navigation.type === 2);
        } catch (e) {
            return false;
        }
    }

    function install(win, opts) {
        opts = opts || {};
        var now = opts.now || function () { return Date.now(); };
        // Time within the page (qa-passkeys-3 client L1): the wall clock can be set back while the page is open, the
        // page's own clock stops while the phone sleeps - the longer of the two counts, so neither holds the lock.
        var mono = opts.mono || function () {
            var perf = win.performance;
            return perf && typeof perf.now === "function" ? perf.now() : now();
        };
        var doc = win.document;
        var html = doc.documentElement;
        var going = false;
        // The page's own title while it is hidden or locked: "Vibb" stands in (a device's name could show in the
        // recents or a tab).
        var title = null;

        function hideTitle() {
            if (title !== null || typeof doc.title !== "string") return;
            title = doc.title;
            doc.title = "Vibb";
        }

        function showTitle() {
            if (title === null) return;
            doc.title = title;
            title = null;
        }

        function lock(next) {
            if (going) return;
            going = true;
            addClass(html, "locked");
            hideTitle();
            win.location.replace(lockUrl(next || here(win)));
        }

        var api = {
            due: function () { return false; },
            lock: lock,
            homeAfter: HOME_AFTER
        };
        win.handyLock = api;

        var lockMs = parseInt(html && html.getAttribute ? html.getAttribute("data-lock-ms") : "", 10);
        if (!(lockMs > 0) || win.location.pathname === LOCK_PATH) return api;
        // The night picture of a locked or veiled page (style.css: the mark at rest, as the start-up splash): fetched now
        // on every page of a lock-on session, so the first lock never paints plain night first.
        try {
            if (win.Image) new win.Image().src = REST_MARK;
        } catch (e) {}

        var wallLoaded = now();
        var monoLoaded = mono();
        var stored = readTime(win, USED_KEY);
        // A stored time past this load (the clock was set back) counts as this load.
        var storedAgo = stored === null ? null : Math.max(0, wallLoaded - stored);
        var ago;
        if (oldPage(win)) {
            // A history or cached load: only the stored input counts; none is due (the server sees no such load).
            ago = storedAgo === null ? lockMs : storedAgo;
        } else {
            var idle = parseInt(html.getAttribute("data-lock-idle"), 10);
            ago = isNaN(idle) ? 0 : Math.max(0, idle);
            if (storedAgo !== null) ago = Math.min(ago, storedAgo);
        }
        // The last use, on both clocks.
        var lastWall = wallLoaded - ago;
        var lastMono = monoLoaded - ago;
        // The stored time this page wrote last (a newer one is another page's of this tab: an unlock).
        var written = null;
        var sent = 0;
        var timer = null;

        function elapsed() {
            return Math.max(mono() - lastMono, now() - lastWall);
        }

        function due() {
            return going || elapsed() >= lockMs;
        }
        api.due = due;

        function visible() {
            return doc.visibilityState !== "hidden";
        }

        function beacon(at) {
            if (!visible()) return;
            var before = Math.max(sent, readTime(win, SENT_KEY) || 0);
            if (before <= at && at - before < BEACON_EVERY) return;
            sent = at;
            writeTime(win, SENT_KEY, at);
            if (typeof win.fetch !== "function") return;
            try {
                win.fetch(BEACON_URL, {
                    method: "POST",
                    credentials: "same-origin",
                    keepalive: true,
                    cache: "no-store"
                }).then(function (res) {
                    if (res && res.status === 423) lock();
                }, function () {});
            } catch (e) {}
        }

        // An input the parent made (a script's synthetic event never counts): too late locks; else it is the last use
        // (kept for the next page) and maybe the beacon.
        function input(e) {
            if (going || (e && e.isTrusted === false)) return;
            if (due()) {
                lock();
                return;
            }
            lastMono = mono();
            lastWall = now();
            written = lastWall;
            writeTime(win, USED_KEY, lastWall);
            beacon(lastWall);
        }

        // Locks once due; until then a timer for the time left (a phone holds a hidden page's timers - the visible
        // page and the inputs check again).
        function check() {
            if (going) return;
            if (due()) {
                lock();
                return;
            }
            if (timer !== null && win.clearTimeout) win.clearTimeout(timer);
            timer = win.setTimeout(function () {
                timer = null;
                check();
            }, Math.max(1000, lockMs - elapsed() + 50));
        }

        // A newer input another page of this tab kept (an unlock writes one too).
        function reread() {
            var kept = readTime(win, USED_KEY);
            if (kept === null || kept === written || kept <= lastWall) return;
            var at = Math.min(kept, now());
            lastMono = mono() - (now() - at);
            lastWall = at;
        }

        // When the page was hidden (the veil), for the lock's `next` after a long time away.
        var hiddenAt = null;

        function shown() {
            var home = hiddenAt !== null && now() - hiddenAt > HOME_AFTER;
            hiddenAt = null;
            reread();
            if (due()) {
                lock(home ? HOME_PATH : undefined);
                return;
            }
            removeClass(html, "veiled");
            showTitle();
            check();
        }

        function veil() {
            if (hiddenAt === null) hiddenAt = now();
            addClass(html, "veiled");
            hideTitle();
        }

        doc.addEventListener("pointerdown", input, true);
        doc.addEventListener("keydown", input, true);
        doc.addEventListener("visibilitychange", function () {
            if (visible()) shown();
            else veil();
        });
        win.addEventListener("pagehide", function (e) {
            if (e && e.persisted) veil();
        });
        win.addEventListener("pageshow", function (e) {
            if (!e || !e.persisted) return;
            going = false;
            removeClass(html, "locked");
            shown();
        });

        check();
        return api;
    }

    var exported = {
        install: install,
        lockUrl: lockUrl,
        here: here,
        HOME_AFTER: HOME_AFTER,
        USED_KEY: USED_KEY,
        SENT_KEY: SENT_KEY,
        BEACON_EVERY: BEACON_EVERY,
        BEACON_URL: BEACON_URL,
        oldPage: oldPage
    };
    if (typeof module !== "undefined" && module.exports) {
        module.exports = exported;
    } else if (root.document) {
        install(root);
    }
})(this);
