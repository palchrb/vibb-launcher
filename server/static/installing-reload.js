// Device › Apps (design 31 step 6): no push reaches this page, so while an app installs it reloads
// every 3 s for the percentage. The page loads this script only then, so the reloading stops once
// nothing installs. It never reloads
// - once a save has started: a switch saves on `change` (autosave.js calls form.submit(), which
//   fires no `submit` event), a Name and icon form on `submit` - a reload would cancel the POST on
//   its way (qa-31-s6-code M1);
// - while a Name and icon form is open: what was typed would be lost.
// A refused switch's `?notice=` is dropped from the address once shown, so a reload doesn't show
// it again (N4). Run its tests: node --test jstest/installing-reload.test.js
(function (root) {
    "use strict";

    var DELAY = 3000;

    function install(win) {
        var doc = win.document;
        var leaving = false;

        function stop() {
            leaving = true;
        }

        doc.addEventListener("submit", stop, true);
        doc.addEventListener("change", function (e) {
            var el = e.target;
            if (el && el.hasAttribute && el.hasAttribute("data-autosave")) stop();
        }, true);
        win.addEventListener("pagehide", stop);

        if (win.history && win.history.replaceState && /[?&]notice=/.test(win.location.search)) {
            win.history.replaceState(null, "", win.location.pathname + win.location.hash);
        }

        function tick() {
            if (leaving) return;
            if (doc.querySelector("details.app-display[open]")) {
                win.setTimeout(tick, DELAY);
                return;
            }
            win.location.reload();
        }
        win.setTimeout(tick, DELAY);
    }

    var api = { install: install, DELAY: DELAY };
    if (typeof module !== "undefined" && module.exports) {
        module.exports = api;
    } else if (root.document) {
        install(root);
    }
})(this);
