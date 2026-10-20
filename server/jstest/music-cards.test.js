// Behaviour of static/music-cards.js's scroll anchoring in the app shell (design 43, qa-r5-a-code
// L2): a card that grows above the view moves <main>, never the window. Run: node --test jstest/
"use strict";
const test = require("node:test");
const assert = require("node:assert");
const { scrollBy } = require("../static/music-cards.js");

function fakeWindow(overflowY) {
    const main = { scrollTop: 300, overflowY };
    const windowScrolls = [];
    const win = {
        document: { querySelector: (selector) => (selector === "main" ? main : null) },
        getComputedStyle: (el) => ({ overflowY: el.overflowY }),
        scrollBy: (x, y) => windowScrolls.push([x, y]),
    };
    return { win, main, windowScrolls };
}

test("in the shell the view is kept still by scrolling <main>", () => {
    for (const overflow of ["auto", "scroll"]) {
        const w = fakeWindow(overflow);
        scrollBy(w.win, 42);
        assert.strictEqual(w.main.scrollTop, 342, overflow);
        assert.deepStrictEqual(w.windowScrolls, [], "the window stays put");
    }
});

test("a page whose window scrolls scrolls the window", () => {
    const w = fakeWindow("visible");
    scrollBy(w.win, -12);
    assert.deepStrictEqual(w.windowScrolls, [[0, -12]]);
    assert.strictEqual(w.main.scrollTop, 300);
});
