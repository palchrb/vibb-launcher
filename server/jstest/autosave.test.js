// Behaviour of static/autosave.js (design 31 §5.2, L7). Run: node --test jstest/autosave.test.js
"use strict";
const test = require("node:test");
const assert = require("node:assert");
const { install } = require("../static/autosave.js");

function fakeDocument() {
    const listeners = {};
    const doc = {
        addEventListener: (type, fn) => {
            (listeners[type] = listeners[type] || []).push(fn);
        },
    };
    function fire(type, target, extra) {
        const event = Object.assign({ type, target, defaultPrevented: false }, extra || {});
        event.preventDefault = () => { event.defaultPrevented = true; };
        (listeners[type] || []).forEach((fn) => fn(event));
        return event;
    }
    return { doc, fire };
}

function control(tagName, { autosave = true } = {}) {
    const form = { submits: 0, submit() { this.submits += 1; } };
    return {
        tagName,
        form,
        hasAttribute: (name) => autosave && name === "data-autosave",
    };
}

test("a changed checkbox saves its form at once", () => {
    const { doc, fire } = fakeDocument();
    install(doc);
    const box = control("INPUT");
    fire("change", box);
    assert.strictEqual(box.form.submits, 1);
});

test("a control without data-autosave never saves", () => {
    const { doc, fire } = fakeDocument();
    install(doc);
    const box = control("INPUT", { autosave: false });
    fire("change", box);
    const select = control("SELECT", { autosave: false });
    fire("keydown", select, { key: "Enter" });
    fire("change", select);
    fire("focusout", select);
    assert.strictEqual(box.form.submits + select.form.submits, 0);
});

test("a select picked with a pointer (or a phone's picker) saves at once", () => {
    const { doc, fire } = fakeDocument();
    install(doc);
    const select = control("SELECT");
    fire("change", select);
    assert.strictEqual(select.form.submits, 1);
    // A key earlier, then a pointer press: the pointer's change saves at once.
    fire("keydown", select, { key: "ArrowDown" });
    fire("mousedown", select);
    fire("change", select);
    assert.strictEqual(select.form.submits, 2);
});

test("arrow keys on a select don't save on every key, Enter does", () => {
    const { doc, fire } = fakeDocument();
    install(doc);
    const select = control("SELECT");
    for (let i = 0; i < 3; i++) {
        fire("keydown", select, { key: "ArrowDown" });
        fire("change", select);
    }
    assert.strictEqual(select.form.submits, 0, "no save per arrow key");
    const enter = fire("keydown", select, { key: "Enter" });
    assert.strictEqual(select.form.submits, 1);
    assert.ok(enter.defaultPrevented);
    fire("focusout", select);
    assert.strictEqual(select.form.submits, 1, "saved once");
});

test("a choice confirmed with Enter in an open dropdown saves at once", () => {
    const { doc, fire } = fakeDocument();
    install(doc);
    const select = control("SELECT");
    // Space or Alt+Down opens the list, Down moves, Enter picks: Enter's keydown comes first.
    fire("keydown", select, { key: " " });
    fire("keydown", select, { key: "ArrowDown" });
    fire("keydown", select, { key: "Enter" });
    assert.strictEqual(select.form.submits, 0);
    fire("change", select);
    assert.strictEqual(select.form.submits, 1, "saved on the change Enter made");
    fire("focusout", select);
    assert.strictEqual(select.form.submits, 1, "once");
    // The next arrow key starts over: no save per key.
    fire("keydown", select, { key: "ArrowDown" });
    fire("change", select);
    assert.strictEqual(select.form.submits, 1);
});

test("an Enter with no change after it doesn't make a later arrow key save", () => {
    const { doc, fire } = fakeDocument();
    install(doc);
    const select = control("SELECT");
    fire("keydown", select, { key: "ArrowDown" });
    fire("keydown", select, { key: "Enter" });
    fire("keydown", select, { key: "ArrowDown" });
    fire("change", select);
    assert.strictEqual(select.form.submits, 0, "waits for Enter or blur");
    fire("focusout", select);
    assert.strictEqual(select.form.submits, 1);
});

test("a keyboard change saves when the select loses focus", () => {
    const { doc, fire } = fakeDocument();
    install(doc);
    const select = control("SELECT");
    fire("keydown", select, { key: "ArrowUp" });
    fire("change", select);
    fire("focusout", select);
    assert.strictEqual(select.form.submits, 1);
});

test("Enter or blur without a change saves nothing", () => {
    const { doc, fire } = fakeDocument();
    install(doc);
    const select = control("SELECT");
    const enter = fire("keydown", select, { key: "Enter" });
    assert.ok(!enter.defaultPrevented);
    fire("keydown", select, { key: "ArrowDown" });
    fire("focusout", select);
    assert.strictEqual(select.form.submits, 0);
    // After the blur the select is back to saving a pointer change at once.
    fire("change", select);
    assert.strictEqual(select.form.submits, 1);
});

// Design 77 step 4, code QA L6: a segmented control's radios share one form and one name.
function radioGroup(values) {
    const form = { submits: 0, submit() { this.submits += 1; } };
    return values.map((value) => ({
        tagName: "INPUT",
        type: "radio",
        name: "sms_mode",
        value,
        form,
        hasAttribute: (name) => name === "data-autosave",
    }));
}

test("a radio checked with a pointer saves at once", () => {
    const { doc, fire } = fakeDocument();
    install(doc);
    const [, managed] = radioGroup(["off", "managed", "unmanaged"]);
    fire("mousedown", { tagName: "SPAN" });
    fire("change", managed);
    assert.strictEqual(managed.form.submits, 1);
});

test("arrow keys through a radio group save only the radio left checked", () => {
    const { doc, fire } = fakeDocument();
    install(doc);
    const [off, managed, unmanaged] = radioGroup(["off", "managed", "unmanaged"]);
    // From "off" to "unmanaged" passes "managed": no save on the way.
    fire("keydown", off, { key: "ArrowRight" });
    fire("change", managed);
    fire("focusout", off, { relatedTarget: managed });
    fire("keydown", managed, { key: "ArrowRight" });
    fire("change", unmanaged);
    fire("focusout", managed, { relatedTarget: unmanaged });
    assert.strictEqual(off.form.submits, 0, "nothing saved while moving");
    // Enter (or Space) saves the checked one, once.
    const enter = fire("keydown", unmanaged, { key: "Enter" });
    assert.ok(enter.defaultPrevented);
    assert.strictEqual(off.form.submits, 1);
    fire("focusout", unmanaged, { relatedTarget: null });
    assert.strictEqual(off.form.submits, 1, "saved once");
});

test("leaving a radio group by the keyboard saves the radio left checked", () => {
    const { doc, fire } = fakeDocument();
    install(doc);
    const [off, managed] = radioGroup(["off", "managed"]);
    fire("keydown", off, { key: "ArrowDown" });
    fire("change", managed);
    fire("focusout", off, { relatedTarget: managed });
    assert.strictEqual(off.form.submits, 0);
    fire("focusout", managed, { relatedTarget: null });
    assert.strictEqual(off.form.submits, 1);
    // Back to the pointer: a click saves at once again.
    fire("mousedown", { tagName: "SPAN" });
    fire("change", off);
    assert.strictEqual(off.form.submits, 2);
});

test("a text field or file input saves on change (its change fires once, on commit)", () => {
    const { doc, fire } = fakeDocument();
    install(doc);
    const text = control("INPUT");
    fire("keydown", text, { key: "a" });
    fire("change", text);
    assert.strictEqual(text.form.submits, 1);
});
