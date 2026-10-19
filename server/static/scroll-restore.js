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
//   screen - the same position when nothing above it changed, a little lower when a warning came
//   in above it; but a row that left its group (an app switched to the other group) is NOT
//   followed: the page would scroll to the other group, which reads as a jump (the user,
//   2026-10-19) - the exact position instead, the row simply gone from where it was;
// - with a #fragment the form's elements don't hold (another group, a result shown elsewhere)
//   leaves the browser at that anchor.
// Another path, or a record older than 20 s, restores nothing. Loaded from the head partial on
// every page. No framework, no DOM writes. Tested with node (jstest/scroll-restore.test.js).
//
// The record travels in sessionStorage and, as a second carrier, in window.name (prefixed): an
// installed iPhone app has been seen to lose sessionStorage across a form's navigation, which left
// the browser at the #fragment - the page "jumped" to the group's top (the user, 2026-10-19).
// The place is put back at DOMContentLoaded, at load, and on the two frames after load, because a
// browser may still scroll to the fragment late (after fonts or images moved the page).
(function (root) {
    "use strict";
    var KEY = "handy-scroll";
    var NAME_PREFIX = "handy-scroll:";
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
        if (!found) return null;
        // The element is no longer inside every recorded ancestor it was in (the row moved to
        // another group): the same position, never the other group's.
        for (var key in tops) {
            if (has(tops, key) && found.chain.indexOf(key) < 0) return saved.y;
        }
        return found.top - tops[found.id];
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
            var record = JSON.stringify({
                path: win.location.pathname,
                y: win.scrollY,
                t: now(),
                tops: screenTops(form)
            });
            if (storage) {
                try {
                    storage.setItem(KEY, record);
                } catch (e) {}
            }
            try {
                win.name = NAME_PREFIX + record;
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
        try {
            var named = String(win.name || "");
            if (named.slice(0, NAME_PREFIX.length) === NAME_PREFIX) {
                if (!saved) saved = JSON.parse(named.slice(NAME_PREFIX.length));
                win.name = "";
            }
        } catch (e) {}
        if (!saved) return;

        /**
         * The nearest of the fragment's element and its ancestors whose id was recorded, with its
         * top in the document, and `chain`: every recorded id among its ancestors (itself included).
         */
        function find(id, tops) {
            var el = doc.getElementById(id);
            if (!el) return undefined;
            var found = null;
            var chain = [];
            for (; el && el.getBoundingClientRect; el = el.parentNode) {
                if (el.id && has(tops, el.id)) {
                    chain.push(el.id);
                    if (!found) found = { id: el.id, top: el.getBoundingClientRect().top + win.scrollY };
                }
            }
            if (found) found.chain = chain;
            return found;
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
        // must find the place the browser keeps, not the top (qa-browser-fixes-code M1). The place is
        // put back once more on each of the next two frames: a late scroll to the fragment (iOS) must
        // not win over it.
        win.addEventListener("load", function () {
            restore();
            var frames = 2;
            var tick = function () {
                restore();
                if (--frames > 0 && typeof win.requestAnimationFrame === "function") {
                    win.requestAnimationFrame(tick);
                    return;
                }
                if (win.history && "scrollRestoration" in win.history) win.history.scrollRestoration = "auto";
            };
            if (typeof win.requestAnimationFrame === "function") {
                win.requestAnimationFrame(tick);
            } else if (win.history && "scrollRestoration" in win.history) {
                win.history.scrollRestoration = "auto";
            }
        });
    }

    var api = { install: install, restoreTarget: restoreTarget, KEY: KEY, NAME_PREFIX: NAME_PREFIX, MAX_AGE_MS: MAX_AGE_MS };
    if (typeof module !== "undefined" && module.exports) {
        module.exports = api;
    } else {
        install(root);
    }
})(this);
