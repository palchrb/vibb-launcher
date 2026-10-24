// Behaviour of static/splash-hold.js (the start-up hold, 2026-10-24).
// Run: node --test jstest/splash-hold.test.js
"use strict";
const test = require("node:test");
const assert = require("node:assert");
const { install, splashHoldDue, splashHoldEnd, firstInSession, CAP_MS, FADE_MS } =
    require("../static/splash-hold.js");

const cold = { standalone: true, coarse: true, firstInSession: true, now: 120 };

function memoryStorage() {
    const items = {};
    return {
        getItem: (k) => (k in items ? items[k] : null),
        setItem: (k, v) => { items[k] = String(v); },
    };
}

// A window: `media` the queries that match, `storage` its sessionStorage (or a getter that throws).
function fakeWindow({ media = [], standalone, storage = memoryStorage(), start = 100 } = {}) {
    const listeners = {};
    const docListeners = {};
    const timers = [];
    let clock = start;
    let removed = 0;
    const overlay = {
        className: "splash-hold",
        parentNode: { removeChild: () => { removed++; overlay.parentNode = null; } },
    };
    const html = { className: "" };
    const doc = {
        documentElement: html,
        getElementById: (id) => (id === "splash-hold" && overlay.parentNode ? overlay : null),
        addEventListener: (type, fn) => (docListeners[type] = docListeners[type] || []).push(fn),
    };
    const win = {
        document: doc,
        navigator: standalone === undefined ? {} : { standalone },
        matchMedia: (q) => ({ matches: media.includes(q) }),
        performance: { now: () => clock },
        addEventListener: (type, fn) => (listeners[type] = listeners[type] || []).push(fn),
        setTimeout: (fn, ms) => timers.push({ fn, at: clock + ms }),
    };
    if (typeof storage === "function") {
        Object.defineProperty(win, "sessionStorage", { get: storage });
    } else {
        win.sessionStorage = storage;
    }
    // Moves the clock to `to`, running each timer due by then in order (and any they set).
    const advance = (to) => {
        for (;;) {
            timers.sort((a, b) => a.at - b.at);
            if (!timers.length || timers[0].at > to) break;
            const t = timers.shift();
            clock = Math.max(clock, t.at);
            t.fn();
        }
        clock = to;
    };
    const fire = (type) => (listeners[type] || []).forEach((fn) => fn({ type }));
    const tap = () => (docListeners.pointerdown || []).forEach((fn) => fn({ type: "pointerdown" }));
    return { win, html, overlay, advance, fire, tap, removed: () => removed };
}

const PHONE_APP = ["(display-mode: standalone)", "(pointer: coarse)"];

test("due only on a phone's installed app, on the session's first document", () => {
    assert.strictEqual(splashHoldDue(cold), true);
    assert.strictEqual(splashHoldDue({ ...cold, standalone: false }), false, "a browser tab");
    assert.strictEqual(splashHoldDue({ ...cold, coarse: false }), false, "an app installed on a PC");
    assert.strictEqual(splashHoldDue({ ...cold, firstInSession: false }), false, "a later page");
    assert.strictEqual(splashHoldDue({ ...cold, firstInSession: null }), false, "no storage: never");
    assert.strictEqual(splashHoldDue({ ...cold, now: CAP_MS }), false, "past the cap: nothing to hold");
});

test("the session's first document is told once; storage missing or throwing is never first", () => {
    const storage = memoryStorage();
    assert.strictEqual(firstInSession(storage), true);
    assert.strictEqual(firstInSession(storage), false);
    assert.strictEqual(firstInSession(null), null);
    const throwing = { getItem: () => { throw new Error("SecurityError"); }, setItem: () => {} };
    assert.strictEqual(firstInSession(throwing), null);
    // A storage that doesn't keep what it is given (it would be "first" on every page).
    const forgetful = { getItem: () => null, setItem: () => {} };
    assert.strictEqual(firstInSession(forgetful), null);
});

test("the hold ends 1000 ms after load, never past 1600 ms from the navigation's start", () => {
    assert.strictEqual(splashHoldEnd(300), 1300);
    assert.strictEqual(splashHoldEnd(600), 1600);
    assert.strictEqual(splashHoldEnd(1200), 1600, "a slow load: the cap");
    assert.strictEqual(splashHoldEnd(undefined), 1600, "no load yet: the cap");
});

test("a cold start shows it, fades it 1000 ms after load and removes it after the fade", () => {
    const w = fakeWindow({ media: PHONE_APP });
    assert.strictEqual(install(w.win), true);
    assert.strictEqual(w.html.className, "splash-holding");
    w.advance(300);
    w.fire("load");
    w.advance(1299);
    assert.strictEqual(w.overlay.className, "splash-hold", "still holding");
    w.advance(1300);
    assert.strictEqual(w.overlay.className, "splash-hold is-fading");
    assert.strictEqual(w.removed(), 0);
    w.advance(1300 + FADE_MS);
    assert.strictEqual(w.removed(), 1);
    assert.strictEqual(w.html.className, "");
    w.advance(5000);
    assert.strictEqual(w.removed(), 1, "the cap's timer finds it gone");
});

test("a slow load ends it at the cap", () => {
    const w = fakeWindow({ media: PHONE_APP });
    install(w.win);
    w.advance(CAP_MS - 1);
    assert.strictEqual(w.overlay.className, "splash-hold");
    w.advance(CAP_MS);
    assert.strictEqual(w.overlay.className, "splash-hold is-fading");
    w.fire("load");
    w.advance(CAP_MS + FADE_MS);
    assert.strictEqual(w.removed(), 1);
});

test("with reduced motion it is removed at once at the same moment, no fade", () => {
    const w = fakeWindow({ media: PHONE_APP.concat(["(prefers-reduced-motion: reduce)"]) });
    install(w.win);
    w.advance(200);
    w.fire("load");
    w.advance(1199);
    assert.strictEqual(w.removed(), 0);
    w.advance(1200);
    assert.strictEqual(w.removed(), 1);
    assert.strictEqual(w.overlay.className, "splash-hold", "never faded");
});

test("a tap ends it at once", () => {
    const w = fakeWindow({ media: PHONE_APP });
    install(w.win);
    w.tap();
    assert.strictEqual(w.overlay.className, "splash-hold is-fading");
});

test("iOS's navigator.standalone counts as the installed app", () => {
    const w = fakeWindow({ media: ["(pointer: coarse)"], standalone: true });
    assert.strictEqual(install(w.win), true);
});

test("never in a browser tab, on a PC's installed app, on a later page or without storage", () => {
    const cases = [
        fakeWindow({ media: ["(pointer: coarse)"] }),
        fakeWindow({ media: ["(display-mode: standalone)"] }),
        fakeWindow({ media: PHONE_APP, storage: null }),
        fakeWindow({ media: PHONE_APP, storage: () => { throw new Error("SecurityError"); } }),
        fakeWindow({ media: PHONE_APP, start: CAP_MS + 50 }),
    ];
    for (const w of cases) {
        assert.strictEqual(install(w.win), false);
        assert.strictEqual(w.html.className, "");
    }
    // The second document of the session (a tab, a link, a reload).
    const storage = memoryStorage();
    assert.strictEqual(install(fakeWindow({ media: PHONE_APP, storage }).win), true);
    const next = fakeWindow({ media: PHONE_APP, storage });
    assert.strictEqual(install(next.win), false);
    assert.strictEqual(next.html.className, "");
});
