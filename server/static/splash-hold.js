// The start-up hold (2026-10-24, the user on the iPhone: the night splash "could have shown a second
// longer"): iOS shows the apple-touch-startup-image only until the page's first paint, so the page
// itself holds the same picture a moment - templates/partials/splash_hold.html, the night and the Vibb
// mark written by scripts/pwa-startup-images.py, first in the body of every page with the tab bar.
// It shows only on a cold start of the installed app on a phone:
// - standalone (the display-mode media query, or iOS's navigator.standalone): never in a browser tab;
// - a coarse pointer: never in a PWA installed on a PC;
// - the session's first document (a sessionStorage flag): a tab, a link, a reload or Back never shows
//   it again. Without a working sessionStorage it never shows (it would show on every page).
// It then stays until 1000 ms after the page's load, at most until 1600 ms after the navigation
// started (a slow load - images, the font - never adds a second hold; the cap is the fallback), then
// fades out over 250 ms and is removed; with prefers-reduced-motion it is removed at once, no fade. A
// tap ends it at once. Loaded in the head, not deferred, so the decision is made before the first
// paint (the overlay is `hidden` until style.css's `.splash-holding` shows it); it only sets a class
// and timers, so the page's own scripts run as before.
// ES5, no framework. Tested with node (jstest/splash-hold.test.js).
(function (root) {
    "use strict";

    var KEY = "vibb.splashHeld";
    var HOLD_MS = 1000;
    var CAP_MS = 1600;
    var FADE_MS = 250;
    var SHOWING = "splash-holding";

    // Whether this document is the session's first: true once (the flag set and read back), false
    // after, null when storage is missing or throws (a private mode, blocked site data).
    function firstInSession(storage) {
        if (!storage) return null;
        try {
            if (storage.getItem(KEY) !== null) return false;
            storage.setItem(KEY, "1");
            return storage.getItem(KEY) === "1" ? true : null;
        } catch (e) {
            return null;
        }
    }

    // The decision, pure: env = { standalone, coarse, firstInSession (true/false/null), now (ms since
    // the navigation started) }.
    function splashHoldDue(env) {
        return env.standalone === true && env.coarse === true && env.firstInSession === true &&
            typeof env.now === "number" && env.now < CAP_MS;
    }

    // When the hold ends, in ms since the navigation started: 1000 ms after load, never past the cap
    // (load not yet come: the cap).
    function splashHoldEnd(load) {
        if (typeof load !== "number") return CAP_MS;
        return Math.min(load + HOLD_MS, CAP_MS);
    }

    function matches(win, query) {
        try {
            return !!(win.matchMedia && win.matchMedia(query).matches);
        } catch (e) {
            return false;
        }
    }

    function storageOf(win) {
        try {
            return win.sessionStorage || null;
        } catch (e) {
            return null;
        }
    }

    function now(win) {
        return win.performance && win.performance.now ? win.performance.now() : 0;
    }

    function install(win) {
        var doc = win.document;
        var html = doc.documentElement;
        var env = {
            standalone: matches(win, "(display-mode: standalone)") ||
                !!(win.navigator && win.navigator.standalone === true),
            coarse: matches(win, "(pointer: coarse)"),
            firstInSession: firstInSession(storageOf(win)),
            now: now(win),
        };
        if (!splashHoldDue(env)) return false;
        var reduce = matches(win, "(prefers-reduced-motion: reduce)");
        html.className += (html.className ? " " : "") + SHOWING;
        var ended = false;

        function remove() {
            var overlay = doc.getElementById("splash-hold");
            if (overlay && overlay.parentNode) overlay.parentNode.removeChild(overlay);
            html.className = (" " + html.className + " ").replace(" " + SHOWING + " ", " ").trim();
        }

        function end() {
            if (ended) return;
            ended = true;
            var overlay = doc.getElementById("splash-hold");
            if (!overlay || reduce) return remove();
            overlay.className += " is-fading";
            win.setTimeout(remove, FADE_MS);
        }

        function endAt(at) {
            win.setTimeout(end, Math.max(0, at - now(win)));
        }

        endAt(CAP_MS);
        win.addEventListener("load", function () { endAt(splashHoldEnd(now(win))); });
        // The overlay takes taps (nothing under it is seen yet); a tap ends it at once.
        doc.addEventListener("pointerdown", end, true);
        doc.addEventListener("touchstart", end, { capture: true, passive: true });
        return true;
    }

    var api = {
        install: install,
        splashHoldDue: splashHoldDue,
        splashHoldEnd: splashHoldEnd,
        firstInSession: firstInSession,
        HOLD_MS: HOLD_MS,
        CAP_MS: CAP_MS,
        FADE_MS: FADE_MS,
    };
    if (typeof module !== "undefined" && module.exports) {
        module.exports = api;
    } else if (root.document) {
        install(root);
    }
})(this);
