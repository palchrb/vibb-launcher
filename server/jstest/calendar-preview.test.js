// Behaviour of static/calendar-preview.js (design 69 §3.3: the rule editor's preview). Run: node --test jstest/calendar-preview.test.js
"use strict";
const test = require("node:test");
const assert = require("node:assert");
const { install, body, DELAY_MS } = require("../static/calendar-preview.js");

function page() {
    const listeners = {};
    const fields = [
        { name: "rule", value: "7", type: "hidden" },
        { name: "pattern", value: "Gruppe 2-", type: "text" },
        { name: "keep_rest", value: "on", type: "checkbox", checked: false },
        { name: "preview_title", value: "Gruppe 2- Matte & gym", type: "text" },
    ];
    const form = { elements: fields, getAttribute: (n) => (n === "data-preview-url" ? "/devices/1/calendar/rules/preview" : null) };
    fields.forEach((f) => (f.form = form));
    const target = { innerHTML: "old" };
    const doc = {
        getElementById: (id) => (id === "rule-form" ? form : null),
        querySelector: (sel) => (sel === "[data-preview-result]" ? target : null),
        addEventListener: (type, fn) => (listeners[type] = listeners[type] || []).push(fn),
    };
    const timers = { queue: [], set(fn, ms) { this.queue.push({ fn, ms }); return this.queue.length; }, clear(id) { this.queue[id - 1].fn = null; } };
    const calls = [];
    const answers = [];
    const fetchFn = (url, opts) => {
        calls.push({ url, opts });
        return new Promise((resolve) => answers.push(resolve));
    };
    install(doc, fetchFn, timers);
    const fire = (type, el) => (listeners[type] || []).forEach((fn) => fn({ target: el }));
    const run = () => timers.queue.splice(0).forEach((t) => t.fn && t.fn());
    const answer = (i, html) => answers[i]({ ok: true, text: () => Promise.resolve(html) });
    return { fields, target, calls, fire, run, answer, timers };
}

test("the form's fields go with fragment=1, unchecked boxes left out", () => {
    const { fields } = page();
    assert.strictEqual(
        body(fields[0].form),
        "rule=7&pattern=Gruppe%202-&preview_title=Gruppe%202-%20Matte%20%26%20gym&fragment=1",
    );
});

test("a burst of typing asks once, 400 ms after the last key, and swaps only the result", async () => {
    const p = page();
    p.fire("input", p.fields[1]);
    p.fire("input", p.fields[3]);
    assert.strictEqual(p.timers.queue.filter((t) => t.fn).length, 1);
    assert.strictEqual(p.timers.queue[1].ms, DELAY_MS);
    p.run();
    assert.strictEqual(p.calls.length, 1);
    assert.strictEqual(p.calls[0].url, "/devices/1/calendar/rules/preview");
    assert.strictEqual(p.calls[0].opts.method, "POST");
    p.answer(0, "<p>new</p>");
    await new Promise((r) => setImmediate(r));
    assert.strictEqual(p.target.innerHTML, "<p>new</p>");
});

test("an older answer never overwrites a newer one; another form's input asks nothing", async () => {
    const p = page();
    p.fire("input", { form: {} });
    p.run();
    assert.strictEqual(p.calls.length, 0);
    p.fire("input", p.fields[1]);
    p.run();
    p.fire("change", p.fields[2]);
    p.run();
    assert.strictEqual(p.calls.length, 2);
    p.answer(1, "newer");
    await new Promise((r) => setImmediate(r));
    p.answer(0, "older");
    await new Promise((r) => setImmediate(r));
    assert.strictEqual(p.target.innerHTML, "newer");
});
