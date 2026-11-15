// A page that waits for the phone refreshes every 3 s: Device › Apps while an app installs (design 31
// step 6, for the percentage), Calls & Contacts while the phone confirms a change (step 8), the
// phone's summary for up to 2 minutes after Lock again or Unlock 2 h... (pwa-fixes-a), Avansert after "Åpne for
// oppsett", provisioning's launcher check. No push reaches the page. Since design 103 (§2.7) this script is in the head
// partial on every page and refreshes only while the page holds the server's marker `[data-waits]` (a hidden span the
// server draws on exactly the conditions it loaded this script on before), so the refreshing stops by itself:
// - armed at load when the marker is there, and on every `handy:swapped` that finds it with no timer running (Avansert
//   loads without it, and "Åpne nå" brings it in by a swap); disarmed on a `handy:swapped` that finds it gone;
// - the tick refreshes in place (`handySwap.refresh`, static/swap.js) and reloads only when that says no.
// It never refreshes
// - once a save has started: a switch saves on `change` (autosave.js calls form.submit(), which
//   fires no `submit` event), a form with a button on `submit` - until `handy:saved` says no save is left on its way
//   (`pending` 0), then the timer is armed again (qa-31-s6-code M1; design 103-4);
// - while a Quick Actions sheet is shown (design 93), or a <details> is open (a Name and icon or a contact's form, Add Contact, a confirm, an ⓘ
//   panel): what was typed or opened would be lost;
// - while a text field has the focus or any field differs from what the page came with (design 80's
//   code QA V1: the Calls page's Avansert drawn open from ?advanced=1 doesn't count as open, its
//   fields do) - only while it holds: undone or saved, the refresh goes on;
// - after `pagehide`; a page back from the back-forward cache arms again when the marker is there.
// A refusal's `?notice=` (and its `app=`) is dropped from the address once shown on a page that holds the marker at
// load (today's pages, today's rule), so a reload doesn't show it again (N4); anything else in the address stays
// (`from=`, Device › Apps opened from the Apps tab). Once the app lock is due (design 74 D3.3,
// `window.handyLock.due()` from static/app-lock.js) it never refreshes: it goes to the lock instead.
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

    // Design 93 §6.1: a Quick Actions sheet shown (the summary's tiles open them without a <details>) holds what was
    // picked in it.
    function sheetShown(doc) {
        return !!(doc.querySelector && doc.querySelector(".qa-sheet:not([hidden])"));
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

    /** The server's marker: this page waits for the phone. */
    function waits(doc) {
        return !!(doc.querySelector && doc.querySelector("[data-waits]"));
    }

    function install(win) {
        var doc = win.document;
        // A save on its way (until `handy:saved` with none left), and the page left (`pagehide`).
        var leaving = false;
        var away = false;
        var timer = null;

        function arm() {
            if (timer !== null || leaving || away || !waits(doc)) return;
            timer = win.setTimeout(tick, DELAY);
        }

        function disarm() {
            if (timer !== null && typeof win.clearTimeout === "function") win.clearTimeout(timer);
            timer = null;
        }

        function stop() {
            leaving = true;
            disarm();
        }

        doc.addEventListener("submit", stop, true);
        doc.addEventListener("change", function (e) {
            var el = e.target;
            if (el && el.hasAttribute && el.hasAttribute("data-autosave")) stop();
        }, true);
        win.addEventListener("pagehide", function () {
            away = true;
            disarm();
        });
        win.addEventListener("pageshow", function (e) {
            if (!e || !e.persisted) return;
            away = false;
            leaving = false;
            arm();
        });
        // Design 103-4: a refresh in place may bring the marker in or take it away.
        doc.addEventListener("handy:swapped", function () {
            if (waits(doc)) arm();
            else disarm();
        });
        doc.addEventListener("handy:saved", function (e) {
            if (e && e.detail && e.detail.pending) return;
            leaving = false;
            arm();
        });

        if (waits(doc) && win.history && win.history.replaceState && /[?&]notice=/.test(win.location.search)) {
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
            timer = null;
            if (leaving || away || !waits(doc)) return;
            var lock = win.handyLock;
            if (lock && typeof lock.due === "function" && lock.due()) {
                leaving = true;
                lock.lock();
                return;
            }
            if (shownOpen(doc) || sheetShown(doc) || typing(doc)) {
                arm();
                return;
            }
            var swap = win.handySwap;
            var taken = false;
            try {
                taken = !!(swap && typeof swap.refresh === "function" &&
                    swap.refresh(win.location.href, { fallback: reload }));
            } catch (e) {}
            // Taken: the next tick is armed by the `handy:swapped` that finds the marker still there.
            if (!taken) reload();
        }

        function reload() {
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
        arm();
    }

    var api = { install: install, DELAY: DELAY, shownOpen: shownOpen, sheetShown: sheetShown, typing: typing, waits: waits };
    if (typeof module !== "undefined" && module.exports) {
        module.exports = api;
    } else if (root.document) {
        install(root);
    }
})(this);
