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
    // static/scroll-restore.js's sessionStorage key (its KEY).
    var SCROLL_KEY = "handy-scroll";

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
            keepPlace();
            win.location.reload();
        }

        // A reload must keep the place (qa-31-s8-code M1): scroll-restore set this history entry's
        // scrollRestoration to "manual" after the save that led here, and a reload stays on the
        // entry, so the browser alone would show the top. Hand the position back to the browser
        // ("auto": it restores it, also over a #fragment) and leave scroll-restore's own record
        // (same key and shape), which the reloaded page restores as after a save.
        function keepPlace() {
            try {
                if (win.history && "scrollRestoration" in win.history) {
                    win.history.scrollRestoration = "auto";
                }
            } catch (e) {}
            try {
                win.sessionStorage.setItem(SCROLL_KEY, JSON.stringify({
                    path: win.location.pathname,
                    y: win.scrollY,
                    t: Date.now()
                }));
            } catch (e) {}
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
