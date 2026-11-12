// The status bar's night after a return (2026-11-12, the user's iPhone 16: now and then after a resume a grey-to-night
// fade over the status bar, gone after a restart). iOS colours the status area from the page's fixed top edge
// (style.css, `.top-strip`), and WebKit sends that edge's colour only when it changes - after a reload or a resume it
// can be lost, and iOS falls back to the page's canvas (style.css gives the installed iPhone app a night canvas for
// that). This makes the change: on `pageshow` (a load, or a return from the back-forward cache) and when the page
// becomes visible, the strip takes `top-strip-nudge` (#0C0C15 - one step off the night, invisible) for one painted
// frame, then is the night again. Nothing else; harmless where there is no pocket (Android, a desktop, a Safari tab).
// Deferred from the head partial. ES5, no dependencies. Tests: jstest/top-strip.test.js.
(function (root) {
    "use strict";

    var NUDGE = "top-strip-nudge";

    function install(win) {
        var doc = win.document;
        // Two frames: the class is painted in the first, taken off before the second.
        var frame = win.requestAnimationFrame
            ? function (fn) { win.requestAnimationFrame(fn); }
            : function (fn) { win.setTimeout(fn, 16); };

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

        win.addEventListener("pageshow", nudge);
        doc.addEventListener("visibilitychange", function () {
            if (doc.visibilityState === "visible") nudge();
        });
        return nudge;
    }

    if (typeof module !== "undefined" && module.exports) {
        module.exports = { install: install, NUDGE: NUDGE };
    } else if (root.document) {
        install(root);
    }
})(this);
