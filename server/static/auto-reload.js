// A page that waits for the phone reloads every 3 s: Device › Apps while an app installs (design 31
// step 6, for the percentage), Calls & Contacts while the phone confirms a change (step 8). No push
// reaches the page, and the server loads this script only while there is something to wait for,
// so the reloading stops by itself. It never reloads
// - once a save has started: a switch saves on `change` (autosave.js calls form.submit(), which
//   fires no `submit` event), a form with a button on `submit` - a reload would cancel the POST on
//   its way (qa-31-s6-code M1);
// - while a <details> is open (a Name and icon or a contact's form, Add Contact, a confirm, an ⓘ
//   panel): what was typed or opened would be lost.
// A refusal's `?notice=` is dropped from the address once shown, so a reload doesn't show it again
// (N4). Run its tests: node --test jstest/auto-reload.test.js
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
            if (doc.querySelector("details[open]")) {
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
