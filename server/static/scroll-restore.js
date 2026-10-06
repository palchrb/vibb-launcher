// No POST may make the page jump to the top (server/CLAUDE.md). Every form posts and redirects
// back, which used to land at the top. Remember the scroll position just before any form leaves
// the page - a submit button (submit event) or script (form.submit(), as the auto-saving
// onchange="this.form.submit()" controls do) - per path in sessionStorage for 20 s, and restore
// it on the page that comes back. A redirect to another path or with a #fragment keeps its own
// position. Loaded from the head partial on every page. No framework, no DOM writes.
// Tested with node (jstest/scroll-restore.test.js).
(function (root) {
    "use strict";
    var KEY = "handy-scroll";
    var MAX_AGE_MS = 20000;

    /** The y to restore, or null: same path, no #fragment, saved less than MAX_AGE_MS ago. */
    function restoreTarget(saved, path, hash, now) {
        if (!saved || typeof saved.y !== "number" || typeof saved.t !== "number") return null;
        if (saved.path !== path || hash) return null;
        var age = now - saved.t;
        if (age < 0 || age >= MAX_AGE_MS) return null;
        return saved.y;
    }

    function install(win, now) {
        now = now || function () { return Date.now(); };
        var doc = win.document;
        var storage = null;
        try {
            storage = win.sessionStorage;
        } catch (e) {}

        function remember() {
            if (!storage) return;
            try {
                storage.setItem(KEY, JSON.stringify({ path: win.location.pathname, y: win.scrollY, t: now() }));
            } catch (e) {}
        }

        doc.addEventListener("submit", remember, true);
        var proto = win.HTMLFormElement && win.HTMLFormElement.prototype;
        if (proto && !proto.handyScrollHooked) {
            var nativeSubmit = proto.submit;
            proto.submit = function () {
                remember();
                return nativeSubmit.apply(this, arguments);
            };
            proto.handyScrollHooked = true;
        }

        var saved = null;
        if (storage) {
            try {
                saved = JSON.parse(storage.getItem(KEY) || "null");
                storage.removeItem(KEY);
            } catch (e) {}
        }
        var y = restoreTarget(saved, win.location.pathname, win.location.hash, now());
        if (y === null) return;
        if (win.history && "scrollRestoration" in win.history) win.history.scrollRestoration = "manual";
        var restore = function () { win.scrollTo(0, y); };
        if (doc.readyState === "loading") {
            doc.addEventListener("DOMContentLoaded", restore);
        } else {
            restore();
        }
        win.addEventListener("load", restore);
    }

    var api = { install: install, restoreTarget: restoreTarget, KEY: KEY, MAX_AGE_MS: MAX_AGE_MS };
    if (typeof module !== "undefined" && module.exports) {
        module.exports = api;
    } else {
        install(root);
    }
})(this);
