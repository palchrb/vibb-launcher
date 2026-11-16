// The status bar's night after a return (2026-11-12, the user's iPhone 16: now and then after a resume a grey-to-night
// fade over the status bar, gone after a restart). iOS colours the status area from the page's fixed top edge
// (style.css, `.top-strip`), and WebKit sends that edge's colour only when it changes - after a reload or a resume it
// can be lost, and iOS falls back to the page's canvas (style.css gives the installed iPhone app a night canvas for
// that). This makes the change: on `pageshow` (a load, or a return from the back-forward cache) and when the page
// becomes visible, the strip takes `top-strip-nudge` (#0C0C15 - one step off the night, invisible) for one painted
// frame, then is the night again. Design 104 (2026-11-15, the fade still came back now and then after a return): a
// return nudges again RETRY_MS after it (the web view may not be on screen yet at the first), a new return restarts
// that series and hidden cancels it, so retries never stack; and the keyboard (a field's `focusout`, the visual
// viewport's `resize`) nudges once, KEYBOARD_MS after the last such event. Nothing else; harmless where there is no
// pocket (Android, a desktop, a Safari tab). Deferred from the head partial. ES5, no dependencies.
// Tests: jstest/top-strip.test.js.
(function (root) {
    "use strict";

    var NUDGE = "top-strip-nudge";
    var RETRY_MS = [300, 1000, 3000];
    var KEYBOARD_MS = 300;

    function install(win) {
        var doc = win.document;
        // Two frames: the class is painted in the first, taken off before the second.
        var frame = win.requestAnimationFrame
            ? function (fn) { win.requestAnimationFrame(fn); }
            : function (fn) { win.setTimeout(fn, 16); };
        var retries = [];
        var keyboard = null;

        function nudge() {
            var strip = doc.querySelector(".top-strip");
            if (!strip || !strip.classList || strip.classList.contains(NUDGE)) return;
            strip.classList.add(NUDGE);
            frame(function () {
                frame(function () {
                    strip.classList.remove(NUDGE);
                });
            });
        }

        function cancelRetries() {
            for (var i = 0; i < retries.length; i++) win.clearTimeout(retries[i]);
            retries = [];
        }

        // A return: a nudge now and one at each of RETRY_MS, any earlier series dropped.
        function returned() {
            cancelRetries();
            nudge();
            for (var i = 0; i < RETRY_MS.length; i++) {
                retries.push(win.setTimeout(nudge, RETRY_MS[i]));
            }
        }

        // The keyboard's move: one nudge once it has settled.
        function keyboardMoved() {
            if (keyboard !== null) win.clearTimeout(keyboard);
            keyboard = win.setTimeout(function () {
                keyboard = null;
                nudge();
            }, KEYBOARD_MS);
        }

        win.addEventListener("pageshow", returned);
        doc.addEventListener("visibilitychange", function () {
            if (doc.visibilityState === "visible") returned();
            else cancelRetries();
        });
        doc.addEventListener("focusout", keyboardMoved);
        if (win.visualViewport && win.visualViewport.addEventListener) {
            win.visualViewport.addEventListener("resize", keyboardMoved);
        }
        return nudge;
    }

    if (typeof module !== "undefined" && module.exports) {
        module.exports = { install: install, NUDGE: NUDGE, RETRY_MS: RETRY_MS, KEYBOARD_MS: KEYBOARD_MS };
    } else if (root.document) {
        install(root);
    }
})(this);
