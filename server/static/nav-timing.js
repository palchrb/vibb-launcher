// What a tab switch costs, measured in the parent's own browser (PLAN "PWA tab switches feel slow
// at times", step 1: the measurement; prefetch comes after the numbers). Off unless switched on:
// open any page with `?timing=1` (this browser remembers it, localStorage `handy-timing`) and
// `?timing=0` to stop. While on, every page load prints one line to the console and shows it in a
// small line at the top of the page:
//   tap 840 · wait 120 · dns 0 · connect 310 · ttfb 95 · download 12 · dom 60 · load 610 · navigate · assets 1/9
// - tap: from the tap on the previous page (the link's click, kept in sessionStorage) to this
//   page's load event; wait: from the tap until the browser started this navigation (unload,
//   a service worker, a frozen app waking);
// - dns, connect (TCP + TLS; 0 = a kept connection), ttfb (the request until the first byte:
//   the network both ways and the server), download, dom (the parse until DOMContentLoaded's end),
//   load (from the navigation's start to the load event's end): Navigation Timing (level 2, else
//   level 1's performance.timing for iOS 14);
// - navigate / reload / back_forward; assets N/M: the page's scripts, styles and images that came
//   over the network (transferSize > 0) of all it used - many means the browser's cache was gone.
// Reads only timings, sends nothing anywhere. ES5 (§5.7: Chrome/WebView 80, iOS Safari 14).
// Run its tests: node --test jstest/nav-timing.test.js
(function (root) {
    "use strict";

    var SWITCH_KEY = "handy-timing";
    var TAP_KEY = "handy-timing-tap";
    // A tap older than this isn't this page's (a link that opened nothing, a page left open).
    var TAP_MS = 60 * 1000;

    function read(storage, key) {
        try {
            return storage ? storage.getItem(key) : null;
        } catch (e) {
            return null;
        }
    }

    function write(storage, key, value) {
        try {
            if (!storage) return;
            if (value === null) storage.removeItem(key);
            else storage.setItem(key, value);
        } catch (e) {
            // Storage refused (a private window): the switch lasts for this page only.
        }
    }

    /** Whether timing is on, after `?timing=1|0` in `search` has been remembered in `storage`. */
    function enabled(search, storage) {
        var match = /[?&]timing=([01])(?:&|$)/.exec(search || "");
        if (match) {
            write(storage, SWITCH_KEY, match[1] === "1" ? "1" : null);
            return match[1] === "1";
        }
        return read(storage, SWITCH_KEY) === "1";
    }

    function ms(value) {
        return value > 0 ? Math.round(value) : 0;
    }

    /**
     * The phases of a navigation from a PerformanceNavigationTiming (times from its startTime 0)
     * or a level-1 performance.timing (epoch times; `navigationStart` is its zero) with `oldType`,
     * level 1's performance.navigation.type (0, 1, 2). Null when the load event hasn't ended yet.
     */
    function phases(nav, oldType) {
        if (!nav) return null;
        var zero = typeof nav.startTime === "number" ? nav.startTime : nav.navigationStart;
        if (!(nav.loadEventEnd > 0)) return null;
        return {
            dns: ms(nav.domainLookupEnd - nav.domainLookupStart),
            connect: ms(nav.connectEnd - nav.connectStart),
            ttfb: ms(nav.responseStart - nav.requestStart),
            download: ms(nav.responseEnd - nav.responseStart),
            dom: ms(nav.domContentLoadedEventEnd - nav.responseEnd),
            load: ms(nav.loadEventEnd - zero),
            type: typeof nav.type === "string" ? nav.type : ["navigate", "reload", "back_forward"][oldType] || "navigate"
        };
    }

    /** "assets 1/9": resources fetched over the network of all the page used; "" without data. */
    function assets(resources) {
        if (!resources || !resources.length) return "";
        var known = 0;
        var fetched = 0;
        for (var i = 0; i < resources.length; i++) {
            var size = resources[i].transferSize;
            if (typeof size !== "number") continue;
            known++;
            if (size > 0) fetched++;
        }
        return known ? "assets " + fetched + "/" + known : "";
    }

    /**
     * The one line: `p` from [phases], `origin` the navigation's zero as epoch ms, `tap` the
     * previous page's tap record `{href, t}` (or null), `href` this page's address.
     */
    function line(p, origin, tap, href, resources) {
        var parts = [];
        if (tap && tap.href === href && origin - tap.t < TAP_MS && origin >= tap.t) {
            parts.push("tap " + ms(origin + p.load - tap.t));
            parts.push("wait " + ms(origin - tap.t));
        }
        parts.push("dns " + p.dns, "connect " + p.connect, "ttfb " + p.ttfb,
            "download " + p.download, "dom " + p.dom, "load " + p.load, p.type);
        var a = assets(resources);
        if (a) parts.push(a);
        return parts.join(" · ");
    }

    /** The address a tapped link leads to, or null (another origin, a #fragment, a modified click). */
    function tapped(ev, here) {
        if (!ev || ev.button || ev.metaKey || ev.ctrlKey || ev.shiftKey || ev.altKey) return null;
        var el = ev.target;
        while (el && el.tagName !== "A") el = el.parentNode;
        if (!el || !el.getAttribute) return null;
        var raw = el.getAttribute("href");
        if (!raw || raw.charAt(0) === "#") return null;
        try {
            var to = new URL(raw, here);
            if (to.origin !== new URL(here).origin) return null;
            to.hash = "";
            return to.href;
        } catch (e) {
            return null;
        }
    }

    function show(doc, text) {
        var el = doc.createElement("div");
        el.className = "timing-line";
        el.setAttribute("aria-hidden", "true");
        el.textContent = text;
        doc.body.appendChild(el);
    }

    function install(win) {
        var doc = win.document;
        var local = null;
        var session = null;
        try {
            local = win.localStorage;
            session = win.sessionStorage;
        } catch (e) {
            // Storage blocked: the switch can't be remembered; `?timing=1` still works per page.
        }
        if (!enabled(win.location.search, local)) return;
        doc.addEventListener("click", function (ev) {
            var href = tapped(ev, win.location.href);
            if (href) write(session, TAP_KEY, JSON.stringify({ href: href, t: Date.now() }));
        }, true);
        function report() {
            var perf = win.performance;
            if (!perf) return;
            var nav = perf.getEntriesByType && perf.getEntriesByType("navigation")[0];
            var origin = typeof perf.timeOrigin === "number" ? perf.timeOrigin : null;
            if (!nav && perf.timing) {
                nav = perf.timing;
                origin = perf.timing.navigationStart;
            }
            var p = phases(nav, perf.navigation ? perf.navigation.type : 0);
            if (!p || origin === null) return;
            var tap = null;
            try {
                tap = JSON.parse(read(session, TAP_KEY) || "null");
            } catch (e) {
                tap = null;
            }
            write(session, TAP_KEY, null);
            var here = win.location.href.split("#")[0];
            var text = line(p, origin, tap, here, perf.getEntriesByType ? perf.getEntriesByType("resource") : null);
            if (win.console && win.console.info) win.console.info("[timing] " + win.location.pathname + " " + text);
            show(doc, text);
        }
        // loadEventEnd is set once the load event's handlers have run: report on the next turn.
        win.addEventListener("load", function () {
            win.setTimeout(report, 0);
        });
        // Back from the back-forward cache: no navigation was timed.
        win.addEventListener("pageshow", function (ev) {
            if (ev.persisted) show(doc, "back-forward cache: no request");
        });
    }

    var api = { enabled: enabled, phases: phases, line: line, assets: assets, tapped: tapped, install: install };
    if (typeof module !== "undefined" && module.exports) {
        module.exports = api;
    } else if (root.document) {
        install(root);
    }
})(this);
