// Behaviour of static/reveal.js (design 59 §2.2, qa-59-step2-code S2P-5/6; design 62 §3's one-time
// password). Run: node --test jstest/reveal.test.js
"use strict";
const test = require("node:test");
const assert = require("node:assert");
const { install } = require("../static/reveal.js");

function fakeWindow() {
    const listeners = {};
    const on = (type, fn) => (listeners[type] = listeners[type] || []).push(fn);
    const digits = [{ textContent: "4826" }, { textContent: "1357" }];
    const secret = { textContent: "Kp3x9mQaR7tuWv2yZ4bcDeFg" };
    const clicks = [];
    const copy = {
        hidden: true,
        textContent: "Copy",
        addEventListener: (type, fn) => clicks.push(fn),
    };
    const replaced = [];
    const timers = [];
    const doc = {
        visibilityState: "visible",
        addEventListener: on,
        querySelectorAll: (sel) =>
            sel === ".pin-digits, .pin-inline, .secret-once"
                ? digits.concat([secret])
                : sel === "[data-copy-secret]"
                  ? [copy]
                  : [],
        querySelector: (sel) => (sel === ".secret-once" ? secret : null),
    };
    const win = {
        document: doc,
        location: { href: "http://pi/devices/1/lock#show-pin" },
        history: { replaceState: (state, title, url) => replaced.push(url) },
        addEventListener: on,
        setTimeout: (fn, ms) => timers.push({ fn, ms }),
    };
    const fire = (type) => (listeners[type] || []).forEach((fn) => fn({ type }));
    const click = () => clicks.forEach((fn) => fn({ type: "click" }));
    return { win, doc, digits, secret, copy, click, replaced, fire, timers };
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

test("leaving the page removes the one-time password and its Copy", () => {
    const { win, secret, copy, fire } = fakeWindow();
    install(win);
    assert.strictEqual(copy.hidden, false, "Copy shows with JS");
    fire("pagehide");
    assert.strictEqual(secret.textContent, "");
    assert.strictEqual(copy.hidden, true);
});

test("Copy writes the one-time password without spaces", async () => {
    const { win, copy, click } = fakeWindow();
    const written = [];
    win.navigator = { clipboard: { writeText: (text) => (written.push(text), Promise.resolve()) } };
    install(win);
    click();
    await new Promise((resolve) => setImmediate(resolve));
    assert.deepStrictEqual(written, ["Kp3x9mQaR7tuWv2yZ4bcDeFg"]);
    assert.strictEqual(copy.textContent, "Copied");
});

test("Copy after the page was hidden copies nothing", () => {
    const { win, doc, copy, click, fire } = fakeWindow();
    const written = [];
    win.navigator = { clipboard: { writeText: (text) => (written.push(text), Promise.resolve()) } };
    install(win);
    doc.visibilityState = "hidden";
    fire("visibilitychange");
    click();
    assert.deepStrictEqual(written, []);
    assert.strictEqual(copy.textContent, "Select and copy it");
});

test("after 5 minutes the one-time password is gone for Copy too", () => {
    const { win, secret, copy, timers } = fakeWindow();
    install(win);
    assert.deepStrictEqual(timers.map((t) => t.ms), [300000]);
    assert.strictEqual(secret.textContent, "Kp3x9mQaR7tuWv2yZ4bcDeFg");
    timers[0].fn();
    assert.strictEqual(secret.textContent, "");
    assert.strictEqual(copy.hidden, true);
});
