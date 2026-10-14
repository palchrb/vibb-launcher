// No POST may make the page jump to the top (server/CLAUDE.md, design 31 §5.1). Every form posts and
// redirects back to its own group's #fragment - so without JS the answer lands on the form. With JS
// this keeps the place instead: just before any form leaves the page - a submit button (submit
// event) or script (form.submit(), as static/autosave.js does for the data-autosave controls) - it
// remembers, per path in sessionStorage for 20 s, the scroll position and where on the screen each
// element around the form was (the form and its ancestors that have an id: its row, its group).
// The page that comes back
// - with no #fragment scrolls to the same position;
// - with a #fragment inside one of those elements (the form's own group, a row of it: a new
//   contact, the file next to a deleted one) puts the nearest of them back where it was on the
//   screen - the same position when nothing above it changed, and the row still under the finger
//   when the save moved it (an app switched to the other group);
// - with a #fragment the form's elements don't hold (another group, a result shown elsewhere)
//   leaves the browser at that anchor.
// Another path, or a record older than 20 s, restores nothing. Loaded from the head partial on
// every page. No framework, no DOM writes. Tested with node (jstest/scroll-restore.test.js).
(function (root) {
    "use strict";
    var KEY = "handy-scroll";
    var MAX_AGE_MS = 20000;

    function has(tops, id) {
        return Object.prototype.hasOwnProperty.call(tops, id) && typeof tops[id] === "number";
    }

    /**
     * The y to restore, or null (the browser keeps its own place or anchor). `find(id, tops)` looks
     * the #fragment's element up: undefined when the page has none, else the nearest of it and its
     * ancestors whose id is in `tops`, as `{ id, top }` (its top in the document), or null.
     */
    function restoreTarget(saved, path, hash, now, find) {
        if (!saved || typeof saved.y !== "number" || typeof saved.t !== "number") return null;
        if (saved.path !== path) return null;
        var age = now - saved.t;
        if (age < 0 || age >= MAX_AGE_MS) return null;
        if (!hash || hash === "#") return saved.y;
        var tops = saved.tops;
        if (!tops || typeof tops !== "object") return null;
        var id = hash.slice(1);
        try {
            id = decodeURIComponent(id);
        } catch (e) {}
        var found = find(id, tops);
        // The form's group is gone from the page that came back: the same position.
        if (found === undefined) return has(tops, id) ? saved.y : null;
        return found ? found.top - tops[found.id] : null;
    }

    /** The form and its ancestors that have an id, each with its top on the screen now. */
    function screenTops(form) {
        var tops = {};
        for (var el = form; el && el.getBoundingClientRect; el = el.parentNode) {
            if (el.id && !Object.prototype.hasOwnProperty.call(tops, el.id)) {
                tops[el.id] = el.getBoundingClientRect().top;
            }
        }
        return tops;
    }

    function install(win, now) {
        now = now || function () { return Date.now(); };
        var doc = win.document;
        var storage = null;
        try {
            storage = win.sessionStorage;
        } catch (e) {}

        function remember(form) {
            if (!storage) return;
            try {
                storage.setItem(KEY, JSON.stringify({
                    path: win.location.pathname,
                    y: win.scrollY,
                    t: now(),
                    tops: screenTops(form)
                }));
            } catch (e) {}
        }

        doc.addEventListener("submit", function (e) { remember(e && e.target); }, true);
        var proto = win.HTMLFormElement && win.HTMLFormElement.prototype;
        if (proto && !proto.handyScrollHooked) {
            var nativeSubmit = proto.submit;
            proto.submit = function () {
                remember(this);
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
        if (!saved) return;

        function find(id, tops) {
            var el = doc.getElementById(id);
            if (!el) return undefined;
            for (; el && el.getBoundingClientRect; el = el.parentNode) {
                if (el.id && has(tops, el.id)) {
                    return { id: el.id, top: el.getBoundingClientRect().top + win.scrollY };
                }
            }
            return null;
        }
        var path = win.location.pathname;
        var hash = win.location.hash;
        var loadedAt = now();
        // Once the page is parsed (the #fragment's element exists), and again at `load`: the place
        // can still move while images and fonts come in.
        var restore = function () {
            var y = restoreTarget(saved, path, hash, loadedAt, find);
            if (y === null) return;
            if (win.history && "scrollRestoration" in win.history) win.history.scrollRestoration = "manual";
            win.scrollTo(0, y);
        };
        if (doc.readyState === "loading") {
            doc.addEventListener("DOMContentLoaded", restore);
        } else {
            restore();
        }
        // Then the entry goes back to the browser: the record is used up, so Back to this page later
        // must find the place the browser keeps, not the top (qa-browser-fixes-code M1).
        win.addEventListener("load", function () {
            restore();
            if (win.history && "scrollRestoration" in win.history) win.history.scrollRestoration = "auto";
        });
    }

    var api = { install: install, restoreTarget: restoreTarget, KEY: KEY, MAX_AGE_MS: MAX_AGE_MS };
    if (typeof module !== "undefined" && module.exports) {
        module.exports = api;
    } else {
        install(root);
    }
})(this);
