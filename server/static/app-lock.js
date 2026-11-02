// The app lock's page side (design 74 §3, step 3: "Lås med Face ID"). The lock is the server's (`lock::check`: past
// the time a navigation goes to /lock, anything else is 423); this script only keeps anything private from showing
// first. Loaded from the head on every page, not deferred: it decides before the body paints.
// - A page of a lock-on session carries `<html data-lock-ms="<lock time>" data-lock-idle="<ms since the session's
//   last use>">` (the server's numbers, relative, so the phone's clock doesn't matter). Without them nothing happens
//   here but `window.handyLock` (due() is false, lock() goes to /lock).
// - The last input (a pointer or key event) is kept in sessionStorage (`handy-used`), so it survives the page's own
//   reloads and navigations; the later of it and the server's last use counts (an unreadable store is the server's).
// - Due = the lock time since the last input. Then `html.locked` (style.css hides `body > *`) and
//   `location.replace("/lock?next=<this path>")` - at once at load, when the page becomes visible or comes back from
//   the back-forward cache, when the time runs out on screen, or at an input that comes too late.
// - Hidden: `html.veiled` too (best effort: the iOS app switcher's picture may be taken before it).
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

    function install(win, opts) {
        opts = opts || {};
        var now = opts.now || function () { return Date.now(); };
        var doc = win.document;
        var html = doc.documentElement;
        var going = false;

        function lock(next) {
            if (going) return;
            going = true;
            addClass(html, "locked");
            win.location.replace(lockUrl(next || here(win)));
        }

        var api = {
            due: function () { return false; },
            lock: lock
        };
        win.handyLock = api;

        var lockMs = parseInt(html && html.getAttribute ? html.getAttribute("data-lock-ms") : "", 10);
        if (!(lockMs > 0) || win.location.pathname === LOCK_PATH) return api;

        var loaded = now();
        var idle = parseInt(html.getAttribute("data-lock-idle"), 10);
        var last = isNaN(idle) ? loaded : loaded - Math.max(0, idle);
        var stored = readTime(win, USED_KEY);
        // A stored time past this load (the clock was set back) counts as this load.
        if (stored !== null) last = Math.max(last, Math.min(stored, loaded));
        var sent = 0;
        var timer = null;

        function due() {
            return going || now() - last >= lockMs;
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

        // An input: too late locks; else it is the last use (kept for the next page) and maybe the beacon.
        function input() {
            if (going) return;
            if (due()) {
                lock();
                return;
            }
            last = now();
            writeTime(win, USED_KEY, last);
            beacon(last);
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
            }, Math.max(1000, lockMs - (now() - last) + 50));
        }

        // The newest input this tab's other pages kept (an unlock writes one too).
        function reread() {
            var kept = readTime(win, USED_KEY);
            if (kept !== null) last = Math.max(last, Math.min(kept, now()));
        }

        function shown() {
            reread();
            if (due()) {
                lock();
                return;
            }
            removeClass(html, "veiled");
            check();
        }

        doc.addEventListener("pointerdown", input, true);
        doc.addEventListener("keydown", input, true);
        doc.addEventListener("visibilitychange", function () {
            if (visible()) shown();
            else addClass(html, "veiled");
        });
        win.addEventListener("pagehide", function (e) {
            if (e && e.persisted) addClass(html, "veiled");
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
        USED_KEY: USED_KEY,
        SENT_KEY: SENT_KEY,
        BEACON_EVERY: BEACON_EVERY,
        BEACON_URL: BEACON_URL
    };
    if (typeof module !== "undefined" && module.exports) {
        module.exports = exported;
    } else if (root.document) {
        install(root);
    }
})(this);
