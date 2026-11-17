// The status bar's night after a return (2026-11-12, the user's iPhone 16: now and then after a resume a grey-to-night
// fade over the status bar). iOS colours the status area from the page's fixed top edge (style.css, `.top-strip`), and
// WebKit sends that edge to the UI process only when it changes. Design 104 nudged the strip's colour for a frame (and
// again at RETRY_MS after a return); design 109 (2026-11-16, the fade now STAYS until the app is killed) found that a
// repaint is most likely never scanned as an edge change, so a nudge is now layered and held: the strip's box changes
// (`left: -1px` off screen, a layout), a fresh clone replaces the strip (a new layer), `theme-color` moves one step,
// and all of it stays for two painted frames and HOLD_MS, so a commit carries it; then it all goes back. A hold always
// ends (HOLD_CAP_MS, hidden, pagehide, the start of every return). Returns: pageshow, visible, focus and a colour-scheme
// change (the app-switcher snapshots); the keyboard (a field's focusout, the visual viewport's and the window's resize)
// nudges once, KEYBOARD_MS after the last. Only in the installed iPhone app (`navigator.standalone`), the only place
// with the pocket. Deferred from the head partial. ES5, no dependencies. Tests: jstest/top-strip.test.js.
(function (root) {
    "use strict";

    var NUDGE = "top-strip-nudge";
    var NUDGE_COLOR = "#0C0C15";
    var RETRY_MS = [300, 1000, 3000];
    var KEYBOARD_MS = 300;
    var HOLD_MS = 150;
    var HOLD_CAP_MS = 1000;

    function install(win) {
        var doc = win.document;
        var nav = win.navigator;
        if (!nav || nav.standalone !== true) return function () {};
        var frame = win.requestAnimationFrame
            ? function (fn) { win.requestAnimationFrame(fn); }
            : function (fn) { win.setTimeout(fn, 16); };
        var retries = [];
        var keyboard = null;
        var holding = false;
        var heldStrip = null;
        var heldMeta = null;
        var oldContent = null;
        var capTimer = null;
        var holdTimer = null;
        var holdGen = 0;

        function themeMeta() {
            try {
                return doc.querySelector('meta[name="theme-color"]');
            } catch (e) {
                return null;
            }
        }

        // Undo every step; idempotent.
        function endHold() {
            if (!holding) return;
            holding = false;
            if (capTimer !== null) {
                win.clearTimeout(capTimer);
                capTimer = null;
            }
            if (holdTimer !== null) {
                win.clearTimeout(holdTimer);
                holdTimer = null;
            }
            try {
                if (heldStrip && heldStrip.classList) heldStrip.classList.remove(NUDGE);
            } catch (e) { /* next */ }
            try {
                if (heldMeta && oldContent !== null) heldMeta.setAttribute("content", oldContent);
            } catch (e) { /* next */ }
            heldStrip = null;
            heldMeta = null;
            oldContent = null;
        }

        function nudge() {
            if (holding) return;
            var strip = doc.querySelector(".top-strip");
            var meta = themeMeta();
            if (!strip && !meta) return;
            holding = true;
            var gen = ++holdGen;
            // 1 + 2: the layout change and a fresh strip, in one DOM operation.
            try {
                if (strip && strip.classList) {
                    var fresh = strip;
                    try {
                        if (strip.cloneNode && strip.parentNode && strip.parentNode.replaceChild) {
                            fresh = strip.cloneNode(false);
                            fresh.classList.add(NUDGE);
                            strip.parentNode.replaceChild(fresh, strip);
                        } else {
                            strip.classList.add(NUDGE);
                        }
                    } catch (e) {
                        fresh = strip;
                        strip.classList.add(NUDGE);
                    }
                    heldStrip = fresh;
                }
            } catch (e) { /* next */ }
            // 3: theme-color, one step off.
            try {
                if (meta) {
                    oldContent = meta.getAttribute("content");
                    meta.setAttribute("content", NUDGE_COLOR);
                    heldMeta = meta;
                }
            } catch (e) { /* next */ }
            // 4: the hold.
            capTimer = win.setTimeout(endHold, HOLD_CAP_MS);
            // A frame queued by a hold that has ended must not touch a later one (gen).
            frame(function () {
                if (!holding || gen !== holdGen) return;
                frame(function () {
                    if (!holding || gen !== holdGen) return;
                    holdTimer = win.setTimeout(endHold, HOLD_MS);
                });
            });
        }

        function cancelRetries() {
            for (var i = 0; i < retries.length; i++) win.clearTimeout(retries[i]);
            retries = [];
        }

        // A return: a nudge now and one at each of RETRY_MS, any earlier series dropped, a stale hold ended.
        function returned() {
            // The colour-scheme flip of an app-switcher snapshot fires while hidden: nothing to nudge.
            if (doc.visibilityState === "hidden") return;
            cancelRetries();
            endHold();
            nudge();
            for (var i = 0; i < RETRY_MS.length; i++) {
                retries.push(win.setTimeout(nudge, RETRY_MS[i]));
            }
        }

        // The keyboard's (or a resize's) move: one nudge once it has settled.
        function keyboardMoved() {
            if (keyboard !== null) win.clearTimeout(keyboard);
            keyboard = win.setTimeout(function () {
                keyboard = null;
                nudge();
            }, KEYBOARD_MS);
        }

        function leaving() {
            cancelRetries();
            endHold();
        }

        win.addEventListener("pageshow", returned);
        win.addEventListener("pagehide", leaving);
        win.addEventListener("focus", returned);
        win.addEventListener("resize", keyboardMoved);
        doc.addEventListener("visibilitychange", function () {
            if (doc.visibilityState === "visible") returned();
            else leaving();
        });
        doc.addEventListener("focusout", keyboardMoved);
        if (win.visualViewport && win.visualViewport.addEventListener) {
            win.visualViewport.addEventListener("resize", keyboardMoved);
        }
        try {
            var mq = win.matchMedia ? win.matchMedia("(prefers-color-scheme: dark)") : null;
            if (mq && mq.addEventListener) mq.addEventListener("change", returned);
            else if (mq && mq.addListener) mq.addListener(returned);
        } catch (e) { /* no colour-scheme trigger */ }
        return nudge;
    }

    if (typeof module !== "undefined" && module.exports) {
        module.exports = {
            install: install, NUDGE: NUDGE, RETRY_MS: RETRY_MS, KEYBOARD_MS: KEYBOARD_MS,
            HOLD_MS: HOLD_MS, HOLD_CAP_MS: HOLD_CAP_MS,
        };
    } else if (root.document) {
        install(root);
    }
})(this);
