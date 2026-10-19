// Behaviour of static/install-now.js (design 37 §2.3, qa-37-design R-L3).
// Run: node --test jstest/install-now.test.js
"use strict";
const test = require("node:test");
const assert = require("node:assert");
const { install, DELAY, MAX_POLLS } = require("../static/install-now.js");

function fakeRow(state, sub) {
    const subEl = { textContent: sub };
    return {
        sub: subEl,
        getAttribute: (name) => (name === "data-install-state" ? state : null),
        querySelector: (selector) => (selector === "[data-install-sub]" ? subEl : null),
    };
}

// A fake window: timers and fetches run by hand.
function fakeWindow({ search = "", rows = {}, answer = null, poll = true } = {}) {
    const listeners = {};
    const timers = [];
    const win = {
        reloads: 0,
        fetches: 0,
        replaced: [],
        openDetails: false,
        answer,
        document: {
            hidden: false,
            addEventListener: (type, fn) => {
                (listeners["doc:" + type] = listeners["doc:" + type] || []).push(fn);
            },
            querySelector: (selector) => {
                if (selector === "[data-install-poll]") {
                    return poll ? { getAttribute: () => "/apps/tracked/1/install-now.json" } : null;
                }
                if (selector === "details[open]") return win.openDetails ? {} : null;
                return null;
            },
            getElementById: (id) => rows[id] || null,
        },
        addEventListener: (type, fn) => {
            (listeners["win:" + type] = listeners["win:" + type] || []).push(fn);
        },
        setTimeout: (fn, ms) => {
            timers.push({ fn, ms });
        },
        fetch: () => {
            win.fetches += 1;
            return Promise.resolve({ ok: true, json: () => Promise.resolve(win.answer) });
        },
        location: {
            search,
            pathname: "/apps/tracked/1",
            hash: "#phone-3",
            reload: () => {
                win.reloads += 1;
            },
        },
        history: {
            scrollRestoration: "manual",
            replaceState: (_s, _t, url) => {
                win.replaced.push(url);
            },
        },
        scrollY: 0,
        sessionStorage: { setItem: () => {} },
    };
    win.pending = () => timers.length;
    // Runs the timers due now (each scheduled for DELAY) and waits for their fetches.
    win.advance = async () => {
        const due = timers.splice(0);
        for (const t of due) {
            assert.strictEqual(t.ms, DELAY);
            await t.fn();
        }
    };
    win.fire = (where, type, target) => {
        (listeners[where + ":" + type] || []).forEach((fn) => fn({ type, target }));
    };
    return win;
}

test("the sub-line follows the phone without a reload", async () => {
    const row = fakeRow("working", "The phone got it (14:02)");
    const win = fakeWindow({
        rows: { "phone-3": row },
        answer: { phones: [{ id: 3, state: "working", sub: "Downloading 40 of 80 MB" }] },
    });
    install(win);
    assert.strictEqual(win.fetches, 0);
    await win.advance();
    assert.strictEqual(win.fetches, 1);
    assert.strictEqual(row.sub.textContent, "Downloading 40 of 80 MB");
    assert.strictEqual(win.reloads, 0);
    assert.strictEqual(win.pending(), 1, "polls again");
});

test("a changed state reloads the page, keeping the place", async () => {
    const win = fakeWindow({
        rows: { "phone-3": fakeRow("waiting", "Install Now: waiting for the phone") },
        answer: { phones: [{ id: 3, state: "working", sub: "Installing" }] },
    });
    install(win);
    await win.advance();
    assert.strictEqual(win.reloads, 1);
    assert.strictEqual(win.history.scrollRestoration, "auto");
    assert.strictEqual(win.pending(), 0, "no poll after the reload");
});

test("no reload while a <details> is open; the next poll does it", async () => {
    const win = fakeWindow({
        rows: { "phone-3": fakeRow("sent", "") },
        answer: { phones: [{ id: 3, state: "idle", sub: "1.4.0 is waiting" }] },
    });
    install(win);
    win.openDetails = true;
    await win.advance();
    assert.strictEqual(win.reloads, 0);
    win.openDetails = false;
    await win.advance();
    assert.strictEqual(win.reloads, 1);
});

test("a hidden page stops polling and a visible one starts again", async () => {
    const win = fakeWindow({
        rows: { "phone-3": fakeRow("working", "") },
        answer: { phones: [{ id: 3, state: "working", sub: "Installing" }] },
    });
    install(win);
    win.document.hidden = true;
    await win.advance();
    assert.strictEqual(win.fetches, 0);
    assert.strictEqual(win.pending(), 0);
    win.document.hidden = false;
    win.fire("doc", "visibilitychange");
    assert.strictEqual(win.pending(), 1);
    await win.advance();
    assert.strictEqual(win.fetches, 1);
});

test("a save stops it, so the POST isn't cancelled", async () => {
    const win = fakeWindow({
        rows: { "phone-3": fakeRow("waiting", "") },
        answer: { phones: [{ id: 3, state: "working", sub: "" }] },
    });
    install(win);
    win.fire("doc", "submit", {});
    await win.advance();
    assert.strictEqual(win.fetches, 0);
    assert.strictEqual(win.reloads, 0);
});

test("it gives up after 30 minutes", async () => {
    const win = fakeWindow({
        rows: { "phone-3": fakeRow("working", "") },
        answer: { phones: [{ id: 3, state: "working", sub: "Installing" }] },
    });
    install(win);
    for (let i = 0; i < MAX_POLLS + 5; i++) await win.advance();
    assert.strictEqual(win.fetches, MAX_POLLS);
    assert.strictEqual(win.pending(), 0);
});

test("the POST's answer leaves the address; no poll group, nothing runs", () => {
    const win = fakeWindow({ search: "?install=sent&phone=3" });
    install(win);
    assert.deepStrictEqual(win.replaced, ["/apps/tracked/1#phone-3"]);
    const idle = fakeWindow({ poll: false });
    install(idle);
    assert.strictEqual(idle.pending(), 0);
});
