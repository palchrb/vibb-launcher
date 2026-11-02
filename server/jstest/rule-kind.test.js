// Behaviour of static/rule-kind.js (security review L5: the rule editor's Type handler, out of the
// template). Run: node --test jstest/rule-kind.test.js
"use strict";
const test = require("node:test");
const assert = require("node:assert");
const { install } = require("../static/rule-kind.js");

function page() {
    const listeners = [];
    const calls = { checked: true };
    const silent = { checked: false };
    const fields = { "[name=calls_allowed]": calls, "[name=silent]": silent };
    const form = { querySelector: (sel) => fields[sel] || null };
    const select = (marked) => ({
        form,
        value: "rule",
        hasAttribute: (name) => marked && name === "data-presets-calls",
    });
    const doc = { addEventListener: (type, fn) => type === "change" && listeners.push(fn) };
    install(doc);
    const change = (target) => listeners.forEach((fn) => fn({ target }));
    return { calls, silent, select, change };
}

test("school turns the calls switch off and Silent on, another kind the other way again", () => {
    const { calls, silent, select, change } = page();
    const kind = select(true);
    kind.value = "school";
    change(kind);
    assert.strictEqual(calls.checked, false);
    assert.strictEqual(silent.checked, true);
    kind.value = "rule";
    change(kind);
    assert.strictEqual(calls.checked, true);
    assert.strictEqual(silent.checked, false);
});

test("another control's change leaves the switches alone", () => {
    const { calls, silent, select, change } = page();
    const other = select(false);
    other.value = "school";
    change(other);
    assert.strictEqual(calls.checked, true);
    assert.strictEqual(silent.checked, false);
});
