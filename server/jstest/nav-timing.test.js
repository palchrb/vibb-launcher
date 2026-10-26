// Behaviour of static/nav-timing.js (PLAN "PWA tab switches feel slow at times", step 1): the
// `?timing=1|0` switch, the phases from Navigation Timing (level 2 and iOS 14's level 1), the
// tap-to-load line and the page's wiring. Run: node --test jstest/nav-timing.test.js
"use strict";
const test = require("node:test");
const assert = require("node:assert");
const { enabled, phases, line, assets, tapped, toggle, install } = require("../static/nav-timing.js");

function memoryStorage(initial) {
    const map = new Map(Object.entries(initial || {}));
    return {
        map,
        getItem: (k) => (map.has(k) ? map.get(k) : null),
        setItem: (k, v) => map.set(k, String(v)),
        removeItem: (k) => map.delete(k),
    };
}

const throwing = {
    getItem() {
        throw new Error("blocked");
    },
    setItem() {
        throw new Error("blocked");
    },
    removeItem() {
        throw new Error("blocked");
    },
};

// A level-2 entry: times from 0, the navigation's start.
const NAV = {
    startTime: 0,
    type: "navigate",
    domainLookupStart: 10,
    domainLookupEnd: 10,
    connectStart: 10,
    connectEnd: 320,
    requestStart: 320,
    responseStart: 415,
    responseEnd: 427,
    domContentLoadedEventEnd: 487,
    loadEventEnd: 610,
};

test("the switch: ?timing=1 turns it on and is remembered, ?timing=0 turns it off", () => {
    const storage = memoryStorage();
    assert.strictEqual(enabled("", storage), false, "off by default");
    assert.strictEqual(enabled("?timing=1", storage), true);
    assert.strictEqual(storage.getItem("handy-timing"), "1");
    assert.strictEqual(enabled("?tab=apps", storage), true, "remembered");
    assert.strictEqual(enabled("?a=b&timing=0", storage), false);
    assert.strictEqual(storage.getItem("handy-timing"), null);
    assert.strictEqual(enabled("", storage), false);
    // Another parameter that only starts the same is no switch.
    assert.strictEqual(enabled("?timing=10", storage), false);
    // Blocked storage: the switch works for the page that has it.
    assert.strictEqual(enabled("?timing=1", throwing), true);
    assert.strictEqual(enabled("", throwing), false);
});

test("the phases of a level-2 entry, and nothing before the load event ends", () => {
    assert.deepStrictEqual(phases(NAV), {
        dns: 0,
        connect: 310,
        ttfb: 95,
        download: 12,
        dom: 60,
        load: 610,
        type: "navigate",
    });
    assert.strictEqual(phases(Object.assign({}, NAV, { loadEventEnd: 0 })), null);
    assert.strictEqual(phases(null), null);
});

test("iOS 14's level-1 timing: epoch times from navigationStart, the type from its number", () => {
    const base = 1_700_000_000_000;
    const old = {};
    for (const [key, value] of Object.entries(NAV)) {
        if (typeof value === "number" && key !== "startTime") old[key] = base + value;
    }
    old.navigationStart = base;
    const p = phases(old, 2);
    assert.strictEqual(p.load, 610);
    assert.strictEqual(p.ttfb, 95);
    assert.strictEqual(p.type, "back_forward");
    assert.strictEqual(phases(old, 1).type, "reload");
    assert.strictEqual(phases(old, undefined).type, "navigate");
});

test("the line: tap and wait only for this page's own recent tap, then the phases and assets", () => {
    const origin = 1_000_000;
    const href = "https://pi.example/devices";
    const p = phases(NAV);
    const tap = { href, t: origin - 120 };
    assert.strictEqual(
        line(p, origin, tap, href, null),
        "tap 730 · wait 120 · dns 0 · connect 310 · ttfb 95 · download 12 · dom 60 · load 610 · navigate"
    );
    const plain = "dns 0 · connect 310 · ttfb 95 · download 12 · dom 60 · load 610 · navigate";
    assert.strictEqual(line(p, origin, { href: "https://pi.example/apps", t: origin - 120 }, href, null), plain);
    assert.strictEqual(line(p, origin, { href, t: origin - 61_000 }, href, null), plain, "stale");
    assert.strictEqual(line(p, origin, null, href, null), plain);
    const resources = [{ transferSize: 0 }, { transferSize: 300 }, { transferSize: 0 }, {}];
    assert.strictEqual(line(p, origin, null, href, resources), plain + " · assets 1/3");
    assert.strictEqual(assets([{}]), "", "no transferSize, no claim");
    assert.strictEqual(assets([]), "");
});

test("a tap counts for a same-origin link, without its fragment", () => {
    const here = "https://pi.example/devices/1";
    const anchor = (href) => ({ tagName: "A", getAttribute: () => href, parentNode: null });
    const click = (target, extra) => Object.assign({ button: 0, target }, extra || {});
    assert.strictEqual(tapped(click(anchor("/apps#top")), here), "https://pi.example/apps");
    const icon = { tagName: "svg", parentNode: anchor("/locate") };
    assert.strictEqual(tapped(click(icon), here), "https://pi.example/locate", "a click inside the link");
    assert.strictEqual(tapped(click(anchor("#quick")), here), null);
    assert.strictEqual(tapped(click(anchor("https://elsewhere.example/")), here), null);
    assert.strictEqual(tapped(click(anchor("/apps"), { metaKey: true }), here), null);
    assert.strictEqual(tapped(click({ tagName: "BUTTON", parentNode: null }), here), null);
});

function fakeWindow(search, local, session) {
    const listeners = {};
    const docListeners = {};
    const appended = [];
    const logged = [];
    const timers = [];
    const win = {
        location: { search, href: "https://pi.example/devices" + search, pathname: "/devices" },
        localStorage: local,
        sessionStorage: session,
        console: { info: (m) => logged.push(m) },
        setTimeout: (fn) => timers.push(fn),
        addEventListener: (type, fn) => (listeners[type] = listeners[type] || []).push(fn),
        performance: {
            timeOrigin: 5_000,
            getEntriesByType: (kind) => (kind === "navigation" ? [NAV] : [{ transferSize: 10 }]),
        },
        document: {
            body: { appendChild: (el) => appended.push(el) },
            createElement: () => ({ setAttribute() {} }),
            addEventListener: (type, fn) => (docListeners[type] = docListeners[type] || []).push(fn),
        },
    };
    return { win, listeners, docListeners, appended, logged, timers };
}

test("switched off, the page gets nothing", () => {
    const f = fakeWindow("", memoryStorage(), memoryStorage());
    install(f.win);
    assert.deepStrictEqual(Object.keys(f.listeners), []);
    assert.deepStrictEqual(Object.keys(f.docListeners), []);
});

test("switched on: a tap is kept, and the next load prints and shows its line", () => {
    const session = memoryStorage();
    const f = fakeWindow("?timing=1", memoryStorage(), session);
    install(f.win);
    const link = { tagName: "A", getAttribute: () => "/devices?timing=1", parentNode: null };
    f.docListeners.click[0]({ button: 0, target: link });
    const kept = JSON.parse(session.getItem("handy-timing-tap"));
    assert.strictEqual(kept.href, "https://pi.example/devices?timing=1");
    // The tap's time, made this page's: 200 ms before its navigation started.
    session.setItem("handy-timing-tap", JSON.stringify({ href: kept.href, t: 4_800 }));
    f.listeners.load[0]();
    f.timers.forEach((fn) => fn());
    assert.strictEqual(f.logged.length, 1);
    assert.match(f.logged[0], /^\[timing\] \/devices tap 810 · wait 200 · dns 0 · connect 310 /);
    assert.strictEqual(f.appended.length, 1);
    assert.strictEqual(f.appended[0].className, "timing-line");
    assert.match(f.appended[0].textContent, /assets 1\/1$/);
    assert.strictEqual(session.getItem("handy-timing-tap"), null, "a tap counts once");
    // Back from the back-forward cache: said so in the same line, no numbers, no second line.
    f.listeners.pageshow[0]({ persisted: true });
    assert.strictEqual(f.appended.length, 1);
    assert.strictEqual(f.appended[0].textContent, "back-forward cache: no request");
});

test("About's Page timing row shows the state and links to the other one", () => {
    const attrs = {};
    const state = { textContent: "Off" };
    const row = { setAttribute: (k, v) => (attrs[k] = v), querySelector: () => state };
    const doc = { readyState: "complete", querySelector: () => row };
    toggle(doc, true);
    assert.strictEqual(state.textContent, "On");
    assert.strictEqual(attrs.href, "/updates?timing=0#timing");
    toggle(doc, false);
    assert.strictEqual(state.textContent, "Off");
    assert.strictEqual(attrs.href, "/updates?timing=1#timing");
    // A page without the row, and one still loading: nothing until it has loaded.
    toggle({ readyState: "complete", querySelector: () => null }, true);
    const waiting = [];
    toggle({ readyState: "loading", querySelector: () => row, addEventListener: (t, fn) => waiting.push([t, fn]) }, true);
    assert.strictEqual(waiting[0][0], "DOMContentLoaded");
    waiting[0][1]();
    assert.strictEqual(state.textContent, "On");
});
