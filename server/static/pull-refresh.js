// Pull down to refresh, on the Devices list and a phone's summary only (the user, 2026-10-25: "update
// gestures for devices taben? Om man drar ned så refresher den ... Kunne også vært når man er inne på
// en device"). Never on a page with a Leaflet map (its own drags), never elsewhere for now.
// - With the page at its very top (main, the app shell's scroller, at 0), a downward touch drag shows
//   a small round arrow at the top of the screen, in the title bar's area; past 70 px of finger
//   travel it turns over ("let go"), and a release there turns it into a spinner and reloads the page
//   - through reconnect.js's reload in place (`window.handyReconnect.reload`): the thin bar, the
//   "Connecting…" chip and the restart once the server answers, the scroll place kept, the entry
//   made a GET first. A release short of 70 px, a second finger, a cancel or a scroll up puts it
//   away.
// - Refused - nothing shown, nothing reloaded - while a save is on its way, a <details> is open or a
//   field holds what was typed or picked (reconnect.js's `mayReload`, auto-reload.js's rules plus
//   the typed text); asked at the start and again at the release.
// - Our own touch tracking: the drag's moves are `preventDefault`ed only once it is a pull, so a
//   scroll, a tap or a sideways swipe is the browser's as before; main keeps style.css's
//   `overscroll-behavior-y: contain` (no browser pull-to-refresh, no scroll chaining to the body).
// ES5, no framework (Chrome/WebView 80, iOS Safari 14). Run its tests: node --test jstest/pull-refresh.test.js
(function (root) {
    "use strict";

    // Finger travel (px) that arms the reload.
    var THRESHOLD = 70;
    // Travel before anything shows: a tap, or a wobble at the start of a scroll, stays that.
    var START_AFTER = 10;
    // The arrow follows the finger at this rate, at most MAX_SHIFT px.
    var RESIST = 0.5;
    var MAX_SHIFT = 40;

    // The position of what scrolls the page: <main> in the app shell (design 43), else the window
    // (scroll-restore.js's scrollerOf; this file stands alone, as reconnect.js does).
    function scrollTop(win) {
        var main = win.document.querySelector ? win.document.querySelector("main") : null;
        var overflow = main && win.getComputedStyle ? win.getComputedStyle(main).overflowY : "";
        return overflow === "auto" || overflow === "scroll" ? main.scrollTop : (win.scrollY || 0);
    }

    /** The arrow's shift (px) for `dy` px of finger travel. */
    function shiftOf(dy) {
        return Math.max(0, Math.min(MAX_SHIFT, (dy - START_AFTER) * RESIST));
    }

    /** The round arrow at the top, made on first use. */
    function domView(win) {
        var doc = win.document;
        var el = null;
        function ready() {
            if (el) return true;
            if (!doc.body || !doc.createElement) return false;
            el = doc.createElement("div");
            el.className = "pull-refresh";
            el.setAttribute("aria-hidden", "true");
            el.innerHTML =
                '<svg viewBox="0 0 20 20" focusable="false"><path d="M10 3.5v12M5 10.5l5 5 5-5" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"/></svg>' +
                '<span class="pull-spinner"></span>';
            doc.body.appendChild(el);
            return true;
        }
        function set(classes, shift) {
            if (!ready()) return;
            el.className = "pull-refresh" + classes;
            el.style.transform = shift === null ? "" : "translateY(" + shift + "px)";
            el.style.opacity = shift === null ? "" : String(Math.min(1, shift / 20));
        }
        return {
            pull: function (shift, armed) {
                set(" is-pulling" + (armed ? " is-armed" : ""), shift);
            },
            refreshing: function () {
                set(" is-refreshing", MAX_SHIFT);
            },
            hide: function () {
                set("", null);
            }
        };
    }

    function install(win, opts) {
        opts = opts || {};
        var view = opts.view || domView(win);
        var doc = win.document;
        var saving = false;
        var pull = null;

        // Without reconnect.js (it is on every page; a belt): auto-reload.js's two rules.
        doc.addEventListener("submit", function () {
            saving = true;
        }, true);
        doc.addEventListener("change", function (e) {
            var el = e && e.target;
            if (el && el.hasAttribute && el.hasAttribute("data-autosave")) saving = true;
        }, true);

        /** The page holds nothing a reload would lose, and no save is on its way. */
        function mayReload() {
            if (saving) return false;
            var shared = win.handyReconnect;
            if (shared && typeof shared.mayReload === "function") return shared.mayReload();
            return !(doc.querySelector && doc.querySelector("details[open]"));
        }

        function reload() {
            var shared = win.handyReconnect;
            if (shared && typeof shared.reload === "function") return shared.reload();
            win.location.reload();
            return true;
        }

        function reset() {
            if (pull && pull.shown) view.hide();
            pull = null;
        }

        doc.addEventListener("touchstart", function (e) {
            reset();
            var touches = e && e.touches;
            if (!touches || touches.length !== 1) return;
            if (scrollTop(win) > 0 || !mayReload()) return;
            pull = { x: touches[0].clientX, y: touches[0].clientY, dy: 0, on: false, shown: false };
        }, { passive: true });

        doc.addEventListener("touchmove", function (e) {
            if (!pull) return;
            var touches = e && e.touches;
            if (!touches || touches.length !== 1) {
                reset();
                return;
            }
            var dx = touches[0].clientX - pull.x;
            var dy = touches[0].clientY - pull.y;
            if (!pull.on) {
                // Up, or more sideways than down: the browser's scroll or swipe, not a pull.
                if (dy < 0 || (Math.abs(dx) > START_AFTER && Math.abs(dx) > dy)) {
                    reset();
                    return;
                }
                if (dy <= START_AFTER) return;
                if (scrollTop(win) > 0) {
                    reset();
                    return;
                }
                pull.on = true;
            }
            if (e.cancelable && e.preventDefault) e.preventDefault();
            pull.dy = dy;
            pull.shown = true;
            view.pull(shiftOf(dy), dy >= THRESHOLD);
        }, { passive: false });

        doc.addEventListener("touchend", function () {
            if (!pull) return;
            var go = pull.on && pull.dy >= THRESHOLD && mayReload();
            if (!go) {
                reset();
                return;
            }
            pull = null;
            view.refreshing();
            if (!reload()) view.hide();
        });

        doc.addEventListener("touchcancel", reset);
        // Back from the back-forward cache: never left spinning.
        win.addEventListener("pageshow", function (e) {
            if (e && e.persisted) {
                pull = null;
                saving = false;
                view.hide();
            }
        });
    }

    var api = {
        install: install,
        shiftOf: shiftOf,
        domView: domView,
        THRESHOLD: THRESHOLD,
        START_AFTER: START_AFTER,
        MAX_SHIFT: MAX_SHIFT
    };
    if (typeof module !== "undefined" && module.exports) {
        module.exports = api;
    } else if (root.document) {
        install(root);
    }
})(this);
