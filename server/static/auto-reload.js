// A page that waits for the phone reloads every 3 s: Device › Apps while an app installs (design 31
// step 6, for the percentage), Calls & Contacts while the phone confirms a change (step 8), the
// phone's summary for up to 2 minutes after Lock again or Unlock 2 h... (pwa-fixes-a). No push
// reaches the page, and the server loads this script only while there is something to wait for,
// so the reloading stops by itself. It never reloads
// - once a save has started: a switch saves on `change` (autosave.js calls form.submit(), which
//   fires no `submit` event), a form with a button on `submit` - a reload would cancel the POST on
//   its way (qa-31-s6-code M1);
// - while a <details> is open (a Name and icon or a contact's form, Add Contact, a confirm, an ⓘ
//   panel): what was typed or opened would be lost;
// - while a text field has the focus or any field differs from what the page came with (design 80's
//   code QA V1: the Calls page's Avansert drawn open from ?advanced=1 doesn't count as open, its
//   fields do) - only while it holds: undone or saved, the reload goes on.
// A refusal's `?notice=` (and its `app=`) is dropped from the address once shown, so a reload
// doesn't show it again (N4); anything else in the address stays (`from=`, Device › Apps opened
// from the Apps tab). Once the app lock is due (design 74 D3.3, `window.handyLock.due()` from
// static/app-lock.js) it never reloads: it goes to the lock instead.
// Run its tests: node --test jstest/auto-reload.test.js
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

    // Design 80 D11 (M1): only an open <details> the reader can see counts - one with no closed <details> around it.
    // A contact's Avansert drawn open inside its closed row holds nothing that would be lost. No layout read.
    // Code QA N1: the same function is in static/pull-refresh.js and static/reconnect.js (each script stands alone) - change all three
    // together.
    function shownOpen(doc) {
        if (!doc.querySelectorAll) return !!(doc.querySelector && doc.querySelector("details[open]"));
        var open = doc.querySelectorAll("details[open]");
        for (var i = 0; i < open.length; i++) {
            // Code QA L3: a <details> the server draws open again after a reload (`data-reopens`: the Calls page's
            // Avansert from `?advanced=1`) loses nothing by one.
            if (open[i].hasAttribute && open[i].hasAttribute("data-reopens")) continue;
            var node = open[i].parentNode;
            var shown = true;
            while (node && node !== doc) {
                if (String(node.nodeName).toUpperCase() === "DETAILS" && node.hasAttribute && !node.hasAttribute("open")) {
                    shown = false;
                    break;
                }
                node = node.parentNode;
            }
            if (shown) return true;
        }
        return false;
    }

    // Code QA V1 of design 80: what a reload would lose - a text field with the focus, or any field that differs from
    // what the page came with (reconnect.js's `edited`, the same rule). The tick waits while it holds and reloads once
    // the edit is saved or undone, so a page never stops refreshing for good.
    var TEXTLESS = { hidden: 1, submit: 1, button: 1, reset: 1, image: 1, checkbox: 1, radio: 1, file: 1, range: 1, color: 1 };
    function typing(doc) {
        var active = doc.activeElement;
        var tag = String((active && active.tagName) || "").toUpperCase();
        if (tag === "TEXTAREA" || (tag === "INPUT" && !TEXTLESS[String(active.type || "text").toLowerCase()])) return true;
        if (!doc.querySelectorAll) return false;
        var controls = doc.querySelectorAll("input, textarea, select");
        for (var i = 0; i < controls.length; i++) {
            var el = controls[i];
            if (String(el.tagName || "").toUpperCase() === "SELECT") {
                var options = el.options || [];
                for (var j = 0; j < options.length; j++) {
                    if (options[j].selected !== options[j].defaultSelected) return true;
                }
                continue;
            }
            var type = String(el.type || "").toLowerCase();
            if (type === "hidden" || type === "submit" || type === "button" || type === "reset" || type === "image") continue;
            if (type === "checkbox" || type === "radio") {
                if (el.checked !== el.defaultChecked) return true;
            } else if (type === "file") {
                if (el.value) return true;
            } else if (el.value !== el.defaultValue) {
                return true;
            }
        }
        return false;
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
            var lock = win.handyLock;
            if (lock && typeof lock.due === "function" && lock.due()) {
                leaving = true;
                lock.lock();
                return;
            }
            if (shownOpen(doc) || typing(doc)) {
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

    var api = { install: install, DELAY: DELAY, shownOpen: shownOpen, typing: typing };
    if (typeof module !== "undefined" && module.exports) {
        module.exports = api;
    } else if (root.document) {
        install(root);
    }
})(this);
