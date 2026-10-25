// Behaviour of static/focus-mode.js (the user's pick A, code QA Q-2/Q-3): a press marks <html>
// `pointer-focus` (no ring on a tapped field), the keyboard's moves clear it (the ring again),
// typing in the tapped field keeps it. Run: node --test jstest/focus-mode.test.js
"use strict";
const test = require("node:test");
const assert = require("node:assert");
const { install } = require("../static/focus-mode.js");

function fakeDocument() {
    const listeners = {};
    const classes = new Set();
    const doc = {
        activeElement: null,
        documentElement: {
            classList: {
                add: (c) => classes.add(c),
                remove: (c) => classes.delete(c),
            },
        },
        addEventListener: (type, fn) => {
            (listeners[type] = listeners[type] || []).push(fn);
        },
    };
    function fire(type, extra) {
        const event = Object.assign({ type }, extra || {});
        (listeners[type] || []).forEach((fn) => fn(event));
    }
    return { doc, fire, marked: () => classes.has("pointer-focus") };
}

const textField = { tagName: "INPUT", type: "text" };
const radio = { tagName: "INPUT", type: "radio" };
const select = { tagName: "SELECT" };
const textarea = { tagName: "TEXTAREA" };

test("each kind of press marks pointer mode", () => {
    for (const press of ["mousedown", "touchstart", "pointerdown"]) {
        const { doc, fire, marked } = fakeDocument();
        install(doc);
        assert.strictEqual(marked(), false, "no mark before a press");
        fire(press);
        assert.strictEqual(marked(), true, press);
    }
});

test("pointer, keyboard, pointer: Tab and Shift+Tab bring the ring back, a press drops it again", () => {
    const { doc, fire, marked } = fakeDocument();
    install(doc);
    fire("pointerdown");
    doc.activeElement = textField;
    assert.strictEqual(marked(), true);
    fire("keydown", { key: "Tab", shiftKey: false });
    assert.strictEqual(marked(), false, "Tab");
    fire("touchstart");
    assert.strictEqual(marked(), true);
    fire("keydown", { key: "Tab", shiftKey: true });
    assert.strictEqual(marked(), false, "Shift+Tab");
    fire("mousedown");
    assert.strictEqual(marked(), true);
    // An old engine's keyCode alone.
    fire("keydown", { keyCode: 9 });
    assert.strictEqual(marked(), false, "keyCode 9");
});

test("typing and moving the caret in a tapped text field keep pointer mode", () => {
    for (const field of [textField, textarea, { tagName: "INPUT", type: "number" }, { tagName: "DIV", isContentEditable: true }]) {
        const { doc, fire, marked } = fakeDocument();
        install(doc);
        fire("pointerdown");
        doc.activeElement = field;
        for (const key of ["a", "Backspace", "ArrowLeft", "ArrowRight", "Home", "End", " ", "Enter", "Unidentified"]) {
            fire("keydown", { key });
            assert.strictEqual(marked(), true, `${field.tagName} ${key}`);
        }
    }
});

test("arrow keys after a tapped radio or select bring the ring back", () => {
    for (const [field, key] of [[radio, "ArrowDown"], [select, "ArrowUp"], [radio, "ArrowRight"], [{ tagName: "BUTTON" }, "Enter"], [{ tagName: "INPUT", type: "checkbox" }, " "]]) {
        const { doc, fire, marked } = fakeDocument();
        install(doc);
        fire("pointerdown");
        doc.activeElement = field;
        fire("keydown", { key: "Shift" });
        assert.strictEqual(marked(), true, "a modifier alone changes nothing");
        fire("keydown", { key });
        assert.strictEqual(marked(), false, `${field.tagName} ${field.type || ""} ${key}`);
    }
});

test("an engine without classList is left alone", () => {
    const listeners = [];
    install({ documentElement: {}, addEventListener: (t) => listeners.push(t) });
    assert.deepStrictEqual(listeners, []);
});
