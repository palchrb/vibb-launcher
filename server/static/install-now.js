// Install Now on the launcher's page (design 37 §2.3, qa-37-design R-L3): while a phone's row
// waits for the phone, ask the server every 5 s what the phones do and swap each row's sub-line
// ("Downloading 40 of 80 MB", "Installing") in place (`textContent`, nothing moves). When a row's
// state word changes - fetched, confirmed, answered - the buttons change too (Cancel goes, Install
// Now comes back), so the page reloads, keeping the place as static/auto-reload.js does. It never
// reloads once a save has started or while a <details> is open, it polls only while the page is
// visible, and it gives up after 30 minutes of polling (a reload starts it again). The server loads
// it only while a row waits. Without JS a reload shows the same. `?install=` (a POST's answer) is
// dropped from the address once shown. Run its tests: node --test jstest/install-now.test.js
(function (root) {
    "use strict";

    var DELAY = 5000;
    // 30 minutes of visible polling.
    var MAX_POLLS = 360;
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
        var group = doc.querySelector("[data-install-poll]");
        if (!group) return;
        var url = group.getAttribute("data-install-poll");
        var leaving = false;
        var polls = 0;
        var timer = null;

        function stop() {
            leaving = true;
        }

        doc.addEventListener("submit", stop, true);
        doc.addEventListener("change", function (e) {
            var el = e.target;
            if (el && el.hasAttribute && el.hasAttribute("data-autosave")) stop();
        }, true);
        win.addEventListener("pagehide", stop);

        if (win.history && win.history.replaceState && /[?&]install=/.test(win.location.search)) {
            win.history.replaceState(null, "", win.location.pathname + win.location.hash);
        }

        function schedule() {
            if (leaving || timer !== null || doc.hidden || polls >= MAX_POLLS) return;
            timer = win.setTimeout(tick, DELAY);
        }

        doc.addEventListener("visibilitychange", function () {
            if (!doc.hidden) schedule();
        });

        function tick() {
            timer = null;
            if (leaving || doc.hidden) return;
            polls += 1;
            return win.fetch(url, { credentials: "same-origin", headers: { Accept: "application/json" } })
                .then(function (response) { return response.ok ? response.json() : null; })
                .then(apply)
                .catch(function () {})
                .then(schedule);
        }

        function apply(data) {
            if (!data || !data.phones) return;
            var changed = false;
            data.phones.forEach(function (phone) {
                var row = doc.getElementById("phone-" + phone.id);
                if (!row) return;
                if (row.getAttribute("data-install-state") !== phone.state) changed = true;
                var sub = row.querySelector("[data-install-sub]");
                if (sub && sub.textContent !== phone.sub) sub.textContent = phone.sub;
            });
            if (changed && !leaving && !doc.querySelector("details[open]")) {
                keepPlace();
                leaving = true;
                win.location.reload();
            }
        }

        // As static/auto-reload.js: the entry is the browser's ("auto") and scroll-restore's record
        // holds the position, so the reloaded page comes back where it was.
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

        schedule();
    }

    var api = { install: install, DELAY: DELAY, MAX_POLLS: MAX_POLLS };
    if (typeof module !== "undefined" && module.exports) {
        module.exports = api;
    } else if (root.document) {
        install(root);
    }
})(this);
