// A page that waits for the phone reloads every 3 s: Device › Apps while an app installs (design 31
// step 6, for the percentage), Calls & Contacts while the phone confirms a change (step 8), the
// phone's summary for up to 2 minutes after Lock again or Unlock 2 h... (pwa-fixes-a). No push
// reaches the page, and the server loads this script only while there is something to wait for,
// so the reloading stops by itself. It never reloads
// - once a save has started: a switch saves on `change` (autosave.js calls form.submit(), which
//   fires no `submit` event), a form with a button on `submit` - a reload would cancel the POST on
//   its way (qa-31-s6-code M1);
// - while a <details> is open (a Name and icon or a contact's form, Add Contact, a confirm, an ⓘ
//   panel): what was typed or opened would be lost.
// A refusal's `?notice=` (and its `app=`) is dropped from the address once shown, so a reload
// doesn't show it again (N4); anything else in the address stays (`from=`, Device › Apps opened
// from the Apps tab). Run its tests: node --test jstest/auto-reload.test.js
(function (root) {
    "use strict";

    var DELAY = 3000;
    // static/scroll-restore.js's sessionStorage key (its KEY).
    var SCROLL_KEY = "handy-scroll";

    // The position of what scrolls the page: <main> in the app shell (design 43), else the window
    // (scroll-restore.js's scrollerOf; this file stands alone).
    function scrollTop(win) {
        var main = win.document.querySelector("main");
        var overflow = main && win.getComputedStyle ? win.getComputedStyle(main).overflowY : "";
        return overflow === "auto" || overflow === "scroll" ? main.scrollTop : win.scrollY;
    }

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
            var kept = win.location.search.slice(1).split("&").filter(function (part) {
                var name = part.split("=")[0];
                return part && name !== "notice" && name !== "app";
            });
            win.history.replaceState(
                null,
                "",
                win.location.pathname + (kept.length ? "?" + kept.join("&") : "") + win.location.hash
            );
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

        // A reload must keep the place (qa-31-s8-code M1). Make sure the entry is the browser's
        // ("auto": it restores the position, also over a #fragment) - scroll-restore hands it back
        // at `load`, but a reload can come first - and leave a record under scroll-restore's key
        // with the path, position and time but no `tops`: with no #fragment the reloaded page
        // restores that position; over a #fragment scroll-restore leaves it to the browser.
        function keepPlace() {
            try {
                if (win.history && "scrollRestoration" in win.history) {
                    win.history.scrollRestoration = "auto";
                }
            } catch (e) {}
            try {
                win.sessionStorage.setItem(SCROLL_KEY, JSON.stringify({
                    path: win.location.pathname,
                    y: scrollTop(win),
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
