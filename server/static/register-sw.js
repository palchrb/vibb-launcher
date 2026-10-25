// Registers the service worker (templates/sw.js: installability and the parent's notifications),
// and sets the app's badge to the bell's unread count where the page has the bell and the browser
// has badges (design 35 §5.3), and lets Sign Out take this browser's subscription with it
// (signOut below). ES5, so an old WebView parses it (design 31 §5.7).
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
        signOut(win);
        var bell = win.document.querySelector("[data-unread]");
        if (!bell || !nav.setAppBadge) return;
        var unread = parseInt(bell.getAttribute("data-unread"), 10) || 0;
        var done = unread > 0 ? nav.setAppBadge(unread) : nav.clearAppBadge();
        if (done && done.catch) done.catch(function () {});
    }

    // Sign Out takes this browser's notifications with it (the user, 2026-10-25): the form
    // (`form[data-sign-out]`, Settings) gets the browser's subscription endpoint in its hidden
    // `endpoint` once it is known - the server deletes that row with the session -, and on submit
    // the browser unsubscribes first. The form goes when that is done or after SIGN_OUT_WAIT ms,
    // whichever is first. No worker or no subscription: the field stays empty, as without JS.
    var SIGN_OUT_WAIT = 300;

    function signOut(win) {
        var nav = win.navigator;
        var form = win.document.querySelector("form[data-sign-out]");
        if (!form || !("serviceWorker" in nav) || !nav.serviceWorker.ready) return;
        var field = form.querySelector("input[name=endpoint]");
        if (!field) return;
        var found = null;
        var lookup = Promise.resolve(nav.serviceWorker.ready)
            .then(function (reg) {
                return reg && reg.pushManager ? reg.pushManager.getSubscription() : null;
            })
            .then(function (sub) {
                if (sub && sub.endpoint) {
                    found = sub;
                    field.value = sub.endpoint;
                }
            })
            .catch(function () {});
        var sending = false;
        form.addEventListener("submit", function (e) {
            if (sending) return;
            sending = true;
            e.preventDefault();
            var gone = false;
            function go() {
                if (gone) return;
                gone = true;
                form.submit();
            }
            win.setTimeout(go, SIGN_OUT_WAIT);
            lookup.then(function () {
                if (!found) return go();
                try {
                    var done = found.unsubscribe();
                    if (done && done.then) done.then(go, go);
                    else go();
                } catch (err) {
                    go();
                }
            });
        });
    }

    if (typeof module !== "undefined" && module.exports) {
        module.exports = { install: install, SIGN_OUT_WAIT: SIGN_OUT_WAIT };
    } else if (root.document) {
        install(root);
    }
})(this);
