// Behaviour of static/splash-hold.js (the start-up hold, 2026-10-24; qa-pwa-splash-hold-code Q-1..Q-3).
// Run: node --test jstest/splash-hold.test.js
"use strict";
const test = require("node:test");
const assert = require("node:assert");
const { install, splashHoldDue, splashHoldEnd, firstInSession, CAP_MS, FADE_MS } =
    require("../static/splash-hold.js");

const cold = {
    standalone: true,
    coarse: true,
    firstInSession: true,
    navigation: "navigate",
    referrer: "",
    now: 120,
};

function memoryStorage() {
    const items = {};
    return {
        getItem: (k) => (k in items ? items[k] : null),
        setItem: (k, v) => { items[k] = String(v); },
    };
}

// A window: iOS's installed app on a phone, launched from the home screen, unless told otherwise.
// `media` the queries that match, `storage` its sessionStorage (or a getter that throws),
// `localStorage` one that may be shared between windows, `nav` the Navigation Timing type ("" for
// no entry), `oldNav` performance.navigation's type, `referrer` document.referrer.
function fakeWindow({
    media = ["(pointer: coarse)"],
    standalone = true,
    storage = memoryStorage(),
    localStorage = memoryStorage(),
    nav = "navigate",
    oldNav,
    referrer = "",
    start = 100,
} = {}) {
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
        referrer,
        getElementById: (id) => (id === "splash-hold" && overlay.parentNode ? overlay : null),
        addEventListener: (type, fn) => (docListeners[type] = docListeners[type] || []).push(fn),
    };
    const win = {
        document: doc,
        navigator: standalone === null ? {} : { standalone },
        matchMedia: (q) => ({ matches: media.includes(q) }),
        performance: {
            now: () => clock,
            getEntriesByType: (t) => (t === "navigation" && nav ? [{ type: nav }] : []),
            navigation: oldNav === undefined ? undefined : { type: oldNav },
        },
        localStorage,
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
    const touch = (type) => (docListeners[type] || []).forEach((fn) => fn({ type }));
    return { win, html, overlay, advance, fire, touch, removed: () => removed };
}

test("due only on iOS's installed app on a phone, launched from the home screen, once a session", () => {
    assert.strictEqual(splashHoldDue(cold), true);
    assert.strictEqual(splashHoldDue({ ...cold, standalone: false }), false, "a browser tab, Android");
    assert.strictEqual(splashHoldDue({ ...cold, coarse: false }), false, "a fine pointer");
    assert.strictEqual(splashHoldDue({ ...cold, firstInSession: false }), false, "a later page");
    assert.strictEqual(splashHoldDue({ ...cold, firstInSession: null }), false, "no storage: never");
    assert.strictEqual(splashHoldDue({ ...cold, navigation: "reload" }), false, "a reload");
    assert.strictEqual(splashHoldDue({ ...cold, navigation: "back_forward" }), false, "Back");
    assert.strictEqual(splashHoldDue({ ...cold, referrer: "https://pi/devices" }), false, "a link");
    assert.strictEqual(splashHoldDue({ ...cold, referrer: null }), false, "no referrer to read");
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
    const w = fakeWindow();
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
    const w = fakeWindow();
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
    const w = fakeWindow({ media: ["(pointer: coarse)", "(prefers-reduced-motion: reduce)"] });
    install(w.win);
    w.advance(200);
    w.fire("load");
    w.advance(1199);
    assert.strictEqual(w.removed(), 0);
    w.advance(1200);
    assert.strictEqual(w.removed(), 1);
    assert.strictEqual(w.overlay.className, "splash-hold", "never faded");
});

test("a tap ends it at once, by a pointer or a touch", () => {
    for (const type of ["pointerdown", "touchstart"]) {
        const w = fakeWindow();
        install(w.win);
        w.touch(type);
        assert.strictEqual(w.overlay.className, "splash-hold is-fading", type);
    }
});

test("ended twice while it fades (a tap, the load's timer, the cap): one fade, one removal", () => {
    const w = fakeWindow({ start: 1000 });
    install(w.win);
    w.advance(1100);
    w.fire("load"); // its end at the cap, 1600
    w.advance(1590);
    w.touch("pointerdown");
    w.advance(1600); // the load's timer and the cap's, inside the fade
    assert.strictEqual(w.overlay.className, "splash-hold is-fading");
    assert.strictEqual(w.removed(), 0);
    w.advance(1590 + FADE_MS);
    assert.strictEqual(w.removed(), 1);
    assert.strictEqual(w.html.className, "");
});

test("the flag is the session's: two launches sharing localStorage both hold", () => {
    const shared = memoryStorage();
    assert.strictEqual(install(fakeWindow({ localStorage: shared }).win), true);
    assert.strictEqual(install(fakeWindow({ localStorage: shared }).win), true, "a new session holds again");
});

test("never in a browser tab, on Android's installed app, with a fine pointer or without storage", () => {
    const cases = [
        fakeWindow({ standalone: null }),
        fakeWindow({ standalone: false }),
        // Android's installed app: display-mode standalone, no navigator.standalone (Q-2).
        fakeWindow({ standalone: null, media: ["(pointer: coarse)", "(display-mode: standalone)"] }),
        fakeWindow({ media: [] }),
        fakeWindow({ storage: null }),
        fakeWindow({ storage: () => { throw new Error("SecurityError"); } }),
        fakeWindow({ start: CAP_MS + 50 }),
    ];
    for (const w of cases) {
        assert.strictEqual(install(w.win), false);
        assert.strictEqual(w.html.className, "");
    }
});

test("never on a later document of the session (a tab, a link, a reload)", () => {
    const storage = memoryStorage();
    assert.strictEqual(install(fakeWindow({ storage }).win), true);
    const next = fakeWindow({ storage });
    assert.strictEqual(install(next.win), false);
    assert.strictEqual(next.html.className, "");
});

// Q-1: even when sessionStorage was lost (a fresh one, as the iPhone app has done across a form's
// navigation), a document reached inside the app never holds.
test("never on a reload, Back or Forward, an in-app link or a form's 303, even with a fresh storage", () => {
    const cases = {
        "a reload": fakeWindow({ nav: "reload" }),
        "back/forward": fakeWindow({ nav: "back_forward" }),
        "a reload, old API": fakeWindow({ nav: "", oldNav: 1 }),
        "back/forward, old API": fakeWindow({ nav: "", oldNav: 2 }),
        "an in-app link": fakeWindow({ referrer: "https://pi.tailnet.ts.net/devices" }),
        "a form's 303": fakeWindow({ referrer: "https://pi.tailnet.ts.net/devices/3/lock" }),
    };
    for (const [name, w] of Object.entries(cases)) {
        assert.strictEqual(install(w.win), false, name);
        assert.strictEqual(w.html.className, "", name);
    }
    assert.strictEqual(install(fakeWindow({ nav: "", oldNav: 0 }).win), true, "old API, a launch");
});
