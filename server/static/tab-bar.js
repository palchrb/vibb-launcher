// The tab bar comes back after an iOS resume. An installed app (standalone) that returns from the
// background can keep a stale viewport, and the fixed `bottom: 0` bar then sits below the visible
// area until the app is killed. CSS can't reach that: when the page shows again (`pageshow`, a
// `visibilitychange` to visible) or the viewport changes size, the bar is laid out once more
// against the current viewport. The bar is fixed - outside the page's flow - so nothing else moves
// and the page never scrolls; it happens within one frame, so nothing flickers.
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
        win.addEventListener("resize", queue);
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
