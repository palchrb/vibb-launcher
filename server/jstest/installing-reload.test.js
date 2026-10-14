// Behaviour of static/installing-reload.js (design 31 step 6, qa-31-s6-code M1/L1/N4).
// Run: node --test jstest/installing-reload.test.js
"use strict";
const test = require("node:test");
const assert = require("node:assert");
const { install, DELAY } = require("../static/installing-reload.js");

// A fake window: timers run by hand, listeners fired by hand, reloads and address changes counted.
function fakeWindow({ search = "", openForm = false } = {}) {
    const listeners = {};
    const timers = [];
    const win = {
        reloads: 0,
        replaced: [],
        openForm,
        document: {
            addEventListener: (type, fn) => {
                (listeners["doc:" + type] = listeners["doc:" + type] || []).push(fn);
            },
            querySelector: (selector) =>
                selector === "details.app-display[open]" && win.openForm ? {} : null,
        },
        addEventListener: (type, fn) => {
            (listeners["win:" + type] = listeners["win:" + type] || []).push(fn);
        },
        setTimeout: (fn, ms) => {
            timers.push({ fn, ms });
        },
        location: {
            search,
            pathname: "/devices/1/apps",
            hash: "#app-org.example.game",
            reload: () => {
                win.reloads += 1;
            },
        },
        history: {
            replaceState: (_state, _title, url) => {
                win.replaced.push(url);
            },
        },
    };
    // Runs the timers due now (each scheduled for DELAY), once.
    win.advance = () => {
        const due = timers.splice(0);
        due.forEach((t) => {
            assert.strictEqual(t.ms, DELAY);
            t.fn();
        });
    };
    win.fire = (where, type, target) => {
        (listeners[where + ":" + type] || []).forEach((fn) => fn({ type, target }));
    };
    return win;
}

const autosave = { hasAttribute: (name) => name === "data-autosave" };
const plain = { hasAttribute: () => false };

test("an installing page reloads after 3 s", () => {
    const win = fakeWindow();
    install(win);
    assert.strictEqual(win.reloads, 0);
    win.advance();
    assert.strictEqual(win.reloads, 1);
});

test("a switch that saves stops the reload, so the POST isn't cancelled", () => {
    const win = fakeWindow();
    install(win);
    win.fire("doc", "change", autosave);
    win.advance();
    win.advance();
    assert.strictEqual(win.reloads, 0);
});

test("a submitted form stops the reload", () => {
    const win = fakeWindow();
    install(win);
    win.fire("doc", "submit", {});
    win.advance();
    assert.strictEqual(win.reloads, 0);
});

test("leaving the page stops the reload", () => {
    const win = fakeWindow();
    install(win);
    win.fire("win", "pagehide", win);
    win.advance();
    assert.strictEqual(win.reloads, 0);
});

test("a change on a control that doesn't save keeps reloading", () => {
    const win = fakeWindow();
    install(win);
    win.fire("doc", "change", plain);
    win.advance();
    assert.strictEqual(win.reloads, 1);
});

test("an open Name and icon form pauses the reload until it is closed", () => {
    const win = fakeWindow({ openForm: true });
    install(win);
    win.advance();
    win.advance();
    assert.strictEqual(win.reloads, 0, "paused while the form is open");
    win.openForm = false;
    win.advance();
    assert.strictEqual(win.reloads, 1, "reloads once it is closed");
});

test("a refusal's notice leaves the address once shown, the row's fragment stays", () => {
    const win = fakeWindow({ search: "?notice=from_play&app=app-org.example.game" });
    install(win);
    assert.deepStrictEqual(win.replaced, ["/devices/1/apps#app-org.example.game"]);
    const quiet = fakeWindow();
    install(quiet);
    assert.deepStrictEqual(quiet.replaced, []);
});
