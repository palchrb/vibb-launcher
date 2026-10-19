// Registers the service worker (templates/sw.js: installability and the parent's notifications),
// and sets the app's badge to the bell's unread count where the page has the bell and the browser
// has badges (design 35 §5.3). ES5, so an old WebView parses it (design 31 §5.7).
// Tests: jstest/register-sw.test.js.
(function (root) {
    "use strict";

    function install(win) {
        var nav = win.navigator;
        if ("serviceWorker" in nav) {
            win.addEventListener("load", function () {
                // Register, then ask for an update check at once: an installed app (iOS) can keep
                // an old worker for a day otherwise, and a worker fix must reach it at the next open.
                var registered = nav.serviceWorker.register("/sw.js");
                if (registered && registered.then) {
                    registered.then(function (reg) {
                        if (reg && reg.update) {
                            var updating = reg.update();
                            if (updating && updating.catch) updating.catch(function () {});
                        }
                    }).catch(function () {});
                }
            });
        }
        var bell = win.document.querySelector("[data-unread]");
        if (!bell || !nav.setAppBadge) return;
        var unread = parseInt(bell.getAttribute("data-unread"), 10) || 0;
        var done = unread > 0 ? nav.setAppBadge(unread) : nav.clearAppBadge();
        if (done && done.catch) done.catch(function () {});
    }

    if (typeof module !== "undefined" && module.exports) {
        module.exports = { install: install };
    } else if (root.document) {
        install(root);
    }
})(this);
