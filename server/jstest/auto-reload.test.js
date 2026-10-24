// Behaviour of static/auto-reload.js (design 31 steps 6 and 8, qa-31-s6-code M1/L1/N4).
// Run: node --test jstest/auto-reload.test.js
"use strict";
const test = require("node:test");
const assert = require("node:assert");
const { install, DELAY } = require("../static/auto-reload.js");

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
                selector === "details[open]" && win.openForm ? {} : null,
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
            // scroll-restore.js leaves "manual" after the save that led to this page.
            scrollRestoration: "manual",
            replaceState: (_state, _title, url) => {
                win.replaced.push(url);
            },
        },
        scrollY: 0,
        stored: {},
        sessionStorage: {
            setItem: (key, value) => {
                win.stored[key] = value;
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

test("an open disclosure (a Name and icon or a contact's form) pauses the reload until closed", () => {
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
    // Opened from the Apps tab: `from` stays, so the tab and the back link stay too.
    const fromApps = fakeWindow({ search: "?notice=from_play&app=app-org.example.game&from=app:7" });
    install(fromApps);
    assert.deepStrictEqual(fromApps.replaced, ["/devices/1/apps?from=app:7#app-org.example.game"]);
});

// qa-31-s8-code M1: a reload keeps the place. After a scroll-restored save the entry's
// scrollRestoration is "manual", so the browser alone would show the top on reload; the script
// hands it back ("auto") and leaves scroll-restore's own record, which the reloaded page restores.
test("a reload keeps the scroll position", () => {
    const scrollRestore = require("../static/scroll-restore.js");
    const win = fakeWindow();
    win.location.hash = "";
    win.scrollY = 800;
    install(win);
    win.advance();
    assert.strictEqual(win.reloads, 1);
    assert.strictEqual(win.history.scrollRestoration, "auto");
    const saved = JSON.parse(win.stored[scrollRestore.KEY]);
    assert.strictEqual(saved.path, "/devices/1/apps");
    assert.strictEqual(saved.y, 800);
    // The reloaded page (same path, no fragment, just now) restores exactly that.
    assert.strictEqual(scrollRestore.restoreTarget(saved, "/devices/1/apps", "", saved.t + 50), 800);
});

test("no record or reload once a save has started", () => {
    const win = fakeWindow();
    win.scrollY = 800;
    install(win);
    win.fire("doc", "submit", {});
    win.advance();
    assert.strictEqual(win.reloads, 0);
    assert.deepStrictEqual(win.stored, {});
});

// Design 43: in the app shell <main> scrolls, not the window; the record carries its position.
test("in the app shell the record holds <main>'s position", () => {
    const scrollRestore = require("../static/scroll-restore.js");
    const win = fakeWindow();
    win.location.hash = "";
    win.scrollY = 0;
    const main = { scrollTop: 640 };
    win.document.querySelector = (selector) => (selector === "main" ? main : null);
    win.getComputedStyle = (el) => ({ overflowY: el === main ? "auto" : "visible" });
    install(win);
    win.advance();
    assert.strictEqual(win.reloads, 1);
    assert.strictEqual(JSON.parse(win.stored[scrollRestore.KEY]).y, 640);
});
