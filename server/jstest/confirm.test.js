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
