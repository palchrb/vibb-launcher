// Behaviour of static/register-sw.js: the worker is registered at load; the app badge follows the
// bell's unread count where there is a bell (design 35 §5.3).
// Run: node --test jstest/register-sw.test.js
"use strict";
const test = require("node:test");
const assert = require("node:assert");
const { install, SIGN_OUT_WAIT } = require("../static/register-sw.js");

function fakeWindow({ unread, badge = true } = {}) {
    const log = [];
    const listeners = {};
    const navigator = {
        serviceWorker: {
            register: (url) => {
                log.push(["register", url]);
                return Promise.resolve({ update: () => { log.push(["update"]); return Promise.resolve(); } });
            },
        },
    };
    if (badge) {
        navigator.setAppBadge = (n) => {
            log.push(["badge", n]);
            return Promise.resolve();
        };
        navigator.clearAppBadge = () => {
            log.push(["badge", 0]);
            return Promise.resolve();
        };
    }
    const bell = unread === undefined ? null : { getAttribute: () => String(unread) };
    const win = {
        navigator,
        document: { querySelector: (selector) => (selector === "[data-unread]" ? bell : null) },
        addEventListener: (type, fn) => {
            listeners[type] = fn;
        },
    };
    return { win, log, load: () => listeners.load() };
}

test("registers the worker at load and asks for an update check at once", async () => {
    const w = fakeWindow();
    install(w.win);
    assert.deepStrictEqual(w.log, []);
    w.load();
    assert.deepStrictEqual(w.log, [["register", "/sw.js"]]);
    await new Promise((resolve) => setTimeout(resolve, 0));
    assert.deepStrictEqual(w.log, [["register", "/sw.js"], ["update"]]);
    // A browser whose register() returns nothing (an old WebView stub): no throw.
    const bare = fakeWindow();
    bare.win.navigator.serviceWorker.register = (url) => { bare.log.push(["register", url]); };
    install(bare.win);
    bare.load();
    assert.deepStrictEqual(bare.log, [["register", "/sw.js"]]);
});

test("the badge follows the bell, and stays untouched without one", () => {
    let w = fakeWindow({ unread: 3 });
    install(w.win);
    assert.deepStrictEqual(w.log, [["badge", 3]]);
    w = fakeWindow({ unread: 0 });
    install(w.win);
    assert.deepStrictEqual(w.log, [["badge", 0]]);
    w = fakeWindow();
    install(w.win);
    assert.deepStrictEqual(w.log, []);
    w = fakeWindow({ unread: 3, badge: false });
    install(w.win);
    assert.deepStrictEqual(w.log, []);
});

// Sign Out (the user, 2026-10-25): the form gets this browser's subscription endpoint, and the
// browser unsubscribes before the form goes - at most SIGN_OUT_WAIT ms later.
function signOutWindow({ subscription, worker = true, unsubscribe } = {}) {
    const log = [];
    const timers = [];
    const listeners = {};
    const field = { value: "" };
    const form = {
        querySelector: (selector) => (selector === "input[name=endpoint]" ? field : null),
        addEventListener: (type, fn) => {
            listeners[type] = fn;
        },
        submit: () => log.push(["submit", field.value]),
    };
    const sub = subscription && {
        endpoint: subscription,
        unsubscribe: unsubscribe || (() => {
            log.push(["unsubscribe"]);
            return Promise.resolve(true);
        }),
    };
    const navigator = {};
    if (worker) {
        navigator.serviceWorker = {
            register: () => Promise.resolve({}),
            ready: Promise.resolve({ pushManager: { getSubscription: () => Promise.resolve(sub || null) } }),
        };
    }
    const win = {
        navigator,
        document: { querySelector: (selector) => (selector === "form[data-sign-out]" ? form : null) },
        addEventListener: () => {},
        setTimeout: (fn, ms) => timers.push({ fn, ms }),
    };
    const submit = () => {
        const event = { prevented: false, preventDefault() { this.prevented = true; } };
        if (listeners.submit) listeners.submit(event);
        return event;
    };
    return { win, log, field, timers, submit, listening: () => !!listeners.submit };
}

const ticks = () => new Promise((resolve) => setTimeout(resolve, 0));
const FAKE_ENDPOINT = "https://web.push.apple.com/QK1-fake-endpoint";

test("sign out sends the endpoint and unsubscribes first", async () => {
    const w = signOutWindow({ subscription: FAKE_ENDPOINT });
    install(w.win);
    await ticks();
    assert.strictEqual(w.field.value, FAKE_ENDPOINT);
    const event = w.submit();
    assert.strictEqual(event.prevented, true);
    await ticks();
    assert.deepStrictEqual(w.log, [["unsubscribe"], ["submit", FAKE_ENDPOINT]]);
    // The cap fires later: the form went once.
    assert.strictEqual(w.timers[0].ms, SIGN_OUT_WAIT);
    assert.strictEqual(SIGN_OUT_WAIT, 300);
    w.timers[0].fn();
    assert.strictEqual(w.log.filter((l) => l[0] === "submit").length, 1);
});

test("a slow unsubscribe never holds the sign-out past the cap", async () => {
    const w = signOutWindow({ subscription: FAKE_ENDPOINT, unsubscribe: () => new Promise(() => {}) });
    install(w.win);
    await ticks();
    w.submit();
    await ticks();
    assert.deepStrictEqual(w.log, []);
    w.timers[0].fn();
    assert.deepStrictEqual(w.log, [["submit", FAKE_ENDPOINT]]);
});

test("without a subscription the form goes with an empty field; without a worker it is left alone", async () => {
    const w = signOutWindow();
    install(w.win);
    await ticks();
    w.submit();
    await ticks();
    assert.deepStrictEqual(w.log, [["submit", ""]]);
    const bare = signOutWindow({ worker: false, subscription: FAKE_ENDPOINT });
    install(bare.win);
    await ticks();
    assert.strictEqual(bare.listening(), false, "the browser's own submit");
    assert.strictEqual(bare.field.value, "");
});
