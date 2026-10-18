// The tab bar back after an iOS resume. [needs device check: resume a standalone install on the
// iPhone after > 1 min in the background, the bar visible.] The bug it targets is a hypothesis: an
// installed app (standalone) that returns from the background may keep a stale viewport, and the
// fixed `bottom: 0` bar then sits below the visible area until the app is killed. When the page
// shows again (`pageshow`, a `visibilitychange` to visible) - and, in an installed app only, when
// the viewport changes size (Safari's toolbars resize it during every scroll) - the bar is laid out
// once more against the current viewport. The bar is fixed - outside the page's flow - so nothing
// else moves and the page never scrolls. Delete this file if the device check shows no effect.
// Run its tests: node --test jstest/tab-bar.test.js
(function (root) {
    "use strict";

    function install(win) {
        var doc = win.document;
        var queued = false;

        function relayout() {
            queued = false;
            var bar = doc.querySelector(".tab-bar");
            if (!bar) return;
            bar.style.display = "none";
            // Reading a layout value makes the browser apply the hidden state before it comes back.
            void bar.offsetHeight;
            bar.style.display = "";
        }

        function queue() {
            if (queued) return;
            queued = true;
            win.requestAnimationFrame(relayout);
        }

        win.addEventListener("pageshow", queue);
        var standalone = win.matchMedia && win.matchMedia("(display-mode: standalone)").matches;
        if (standalone) win.addEventListener("resize", queue);
        doc.addEventListener("visibilitychange", function () {
            if (doc.visibilityState === "visible") queue();
        });
    }

    var api = { install: install };
    if (typeof module !== "undefined" && module.exports) {
        module.exports = api;
    } else if (root.document && root.requestAnimationFrame) {
        install(root);
    }
})(this);
