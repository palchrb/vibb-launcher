// Behaviour of static/reveal.js (design 59 §2.2, qa-59-step2-code S2P-5/6). Run: node --test jstest/reveal.test.js
"use strict";
const test = require("node:test");
const assert = require("node:assert");
const { install } = require("../static/reveal.js");

function fakeWindow() {
    const listeners = {};
    const on = (type, fn) => (listeners[type] = listeners[type] || []).push(fn);
    const digits = [{ textContent: "4826" }, { textContent: "1357" }];
    const replaced = [];
    const doc = {
        visibilityState: "visible",
        addEventListener: on,
        querySelectorAll: (sel) => (sel === ".pin-digits, .pin-inline" ? digits : []),
    };
    const win = {
        document: doc,
        location: { href: "http://pi/devices/1/lock#show-pin" },
        history: { replaceState: (state, title, url) => replaced.push(url) },
        addEventListener: on,
    };
    const fire = (type) => (listeners[type] || []).forEach((fn) => fn({ type }));
    return { win, doc, digits, replaced, fire };
}

test("the answer's history entry becomes a GET of the same address", () => {
    const { win, replaced } = fakeWindow();
    install(win);
    assert.deepStrictEqual(replaced, ["http://pi/devices/1/lock#show-pin"]);
});

test("leaving the page removes the digits", () => {
    const { win, digits, fire } = fakeWindow();
    install(win);
    assert.strictEqual(digits[0].textContent, "4826");
    fire("pagehide");
    assert.deepStrictEqual(digits.map((d) => d.textContent), ["", ""]);
});

test("hiding the page removes the digits, showing it again doesn't", () => {
    const { win, doc, digits, fire } = fakeWindow();
    install(win);
    fire("visibilitychange");
    assert.strictEqual(digits[0].textContent, "4826");
    doc.visibilityState = "hidden";
    fire("visibilitychange");
    assert.strictEqual(digits[0].textContent, "");
});

test("a browser without replaceState still hides the digits", () => {
    const { win, digits, fire } = fakeWindow();
    win.history = {};
    install(win);
    fire("pagehide");
    assert.strictEqual(digits[1].textContent, "");
});
