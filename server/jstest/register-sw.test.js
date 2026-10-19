// Behaviour of static/register-sw.js: the worker is registered at load; the app badge follows the
// bell's unread count where there is a bell (design 35 §5.3).
// Run: node --test jstest/register-sw.test.js
"use strict";
const test = require("node:test");
const assert = require("node:assert");
const { install } = require("../static/register-sw.js");

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
