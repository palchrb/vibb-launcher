// Behaviour of static/confirm.js (PLAN "PWA small things", 2026-11-01): the Cancels show, a click on one closes its
// confirm and focuses its summary without a scroll, a click elsewhere does nothing.
// Run: node --test jstest/confirm.test.js
"use strict";
const test = require("node:test");
const assert = require("node:assert");
const { install } = require("../static/confirm.js");

function fakePage() {
    const focused = [];
    const summary = { focus: (options) => focused.push(options) };
    const details = { open: true, querySelector: (s) => (s === "summary" ? summary : null) };
    const cancel = {
        hidden: true,
        closest: (s) => (s === "[data-confirm-cancel]" ? cancel : s === "details" ? details : null),
    };
    const other = { closest: () => null };
    let listener = null;
    const win = {
        document: {
            querySelectorAll: (s) => (s === "[data-confirm-cancel]" ? [cancel] : []),
            addEventListener: (type, fn) => {
                if (type === "click") listener = fn;
            },
        },
    };
    const click = (target) => {
        let prevented = false;
        listener({ target, preventDefault: () => { prevented = true; } });
        return prevented;
    };
    return { win, cancel, details, other, focused, click };
}

test("the Cancels show once the script runs", () => {
    const page = fakePage();
    install(page.win);
    assert.strictEqual(page.cancel.hidden, false);
});

test("Cancel closes its confirm and focuses the summary without a scroll", () => {
    const page = fakePage();
    install(page.win);
    assert.ok(page.click(page.cancel));
    assert.strictEqual(page.details.open, false);
    assert.deepStrictEqual(page.focused, [{ preventScroll: true }]);
});

test("a click anywhere else leaves the confirm open", () => {
    const page = fakePage();
    install(page.win);
    assert.ok(!page.click(page.other));
    assert.ok(!page.click(null));
    assert.strictEqual(page.details.open, true);
    assert.deepStrictEqual(page.focused, []);
});

// Design 101: the picture's sheet (`details.pic-menu`) - Escape and a focus that leaves close it, opening focuses
// the first action, and the backdrop (a non-button [data-confirm-cancel]) closes it like Avbryt.
function fakeMenu() {
    const focused = [];
    const summary = { focus: (options) => focused.push(["summary", options]) };
    const action = { focus: (options) => focused.push(["action", options]) };
    const inside = {};
    const details = {
        open: false,
        querySelector: (s) => (s === "summary" ? summary : s === ".pic-file, .pic-action" ? action : null),
        matches: (s) => s === "details.pic-menu",
        contains: (n) => n === inside || n === details,
    };
    const closest = (s) => (s === "details.pic-menu" ? details : null);
    const backdrop = {
        hidden: true,
        closest: (s) => (s === "[data-confirm-cancel]" ? backdrop : s === "details" || s === "details.pic-menu" ? details : null),
    };
    const listeners = {};
    const win = {
        document: {
            querySelectorAll: (s) => (s === "[data-confirm-cancel]" ? [backdrop] : []),
            addEventListener: (type, fn, capture) => {
                listeners[type] = { fn, capture };
            },
        },
    };
    return { win, details, summary, backdrop, inside, focused, listeners, target: { closest } };
}

test("the backdrop closes the picture sheet like Avbryt", () => {
    const page = fakeMenu();
    install(page.win);
    assert.strictEqual(page.backdrop.hidden, false);
    page.details.open = true;
    page.listeners.click.fn({ target: page.backdrop, preventDefault() {} });
    assert.strictEqual(page.details.open, false);
});

test("Escape closes an open picture sheet and focuses the picture", () => {
    const page = fakeMenu();
    install(page.win);
    page.details.open = true;
    let prevented = false;
    page.listeners.keydown.fn({ key: "Escape", target: page.target, preventDefault: () => { prevented = true; } });
    assert.ok(prevented);
    assert.strictEqual(page.details.open, false);
    assert.deepStrictEqual(page.focused, [["summary", { preventScroll: true }]]);
});

test("a focus that leaves the panel closes the sheet, one inside does not", () => {
    const page = fakeMenu();
    install(page.win);
    page.details.open = true;
    page.listeners.focusout.fn({ target: page.target, relatedTarget: page.inside });
    assert.strictEqual(page.details.open, true);
    page.listeners.focusout.fn({ target: page.target, relatedTarget: {} });
    assert.strictEqual(page.details.open, false);
});

test("opening the picture sheet by keyboard focuses its first action, with the capture-phase toggle", () => {
    const page = fakeMenu();
    install(page.win);
    assert.strictEqual(page.listeners.toggle.capture, true);
    // Opened by a tap (a click with a detail): no script focus, which would draw the pressed look.
    page.listeners.click.fn({ target: page.other || {}, detail: 1 });
    page.details.open = true;
    page.listeners.toggle.fn({ target: page.details });
    assert.deepStrictEqual(page.focused, []);
    // Opened by Enter or Space (detail 0): the first action takes the focus.
    page.listeners.click.fn({ target: {}, detail: 0 });
    page.listeners.toggle.fn({ target: page.details });
    assert.deepStrictEqual(page.focused, [["action", { preventScroll: true }]]);
});

// Design 103: a refresh in place brings the server's hidden Cancels back; its pass runs again.
test("the Cancels are shown again on handy:swapped", () => {
    const shown = { hidden: true };
    const listeners = {};
    let buttons = [shown];
    const win = {
        document: {
            querySelectorAll: (s) => (s === "[data-confirm-cancel]" ? buttons : []),
            addEventListener: (type, fn) => { listeners[type] = fn; },
        },
    };
    install(win);
    assert.strictEqual(shown.hidden, false);
    const fresh = { hidden: true };
    buttons = [shown, fresh];
    listeners["handy:swapped"]({ type: "handy:swapped" });
    assert.strictEqual(fresh.hidden, false);
});
