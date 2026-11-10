// Behaviour of static/contact-form.js (design 80 D10): `more` follows a form's Avansert; in Add Contact the two-way
// switches and the one-way pairs agree, so every post's `*_shown` is the pair's class.
// Run: node --test jstest/contact-form.test.js
"use strict";
const test = require("node:test");
const assert = require("node:assert");
const { install } = require("../static/contact-form.js");

// A tiny element: a tag, attributes, children, listeners; querySelector(All) for the simple selectors the script
// uses (`tag[attr]`, `tag[attr="v"]`, `[attr="v"]`, a list with ",").
function el(tag, attrs = {}, children = []) {
    const node = {
        nodeName: tag.toUpperCase(),
        attrs: Object.assign({}, attrs),
        children,
        listeners: {},
        checked: "checked" in attrs,
        value: attrs.value || "",
        get name() {
            return this.attrs.name;
        },
        hasAttribute(name) {
            return name in this.attrs;
        },
        setAttribute(name, value) {
            this.attrs[name] = value;
        },
        removeAttribute(name) {
            delete this.attrs[name];
        },
        addEventListener(type, fn) {
            (this.listeners[type] = this.listeners[type] || []).push(fn);
        },
        fire(type, target) {
            (this.listeners[type] || []).forEach((fn) => fn({ target }));
        },
        querySelectorAll(selector) {
            const found = [];
            const walk = (n) =>
                n.children.forEach((c) => {
                    if (selector.split(",").some((s) => matches(c, s.trim()))) found.push(c);
                    walk(c);
                });
            walk(this);
            return found;
        },
        querySelector(selector) {
            return this.querySelectorAll(selector)[0] || null;
        },
    };
    return node;
}

function matches(node, selector) {
    const m = /^([a-z]*)(?:\[([a-z_-]+)(?:="([^"]*)")?\])?$/.exec(selector);
    if (!m) throw new Error("selector " + selector);
    const [, tag, attr, value] = m;
    if (tag && node.nodeName !== tag.toUpperCase()) return false;
    if (attr && !(attr in node.attrs)) return false;
    if (value !== undefined && node.attrs[attr] !== value) return false;
    return true;
}

const box = (name, on, extra = {}) => el("input", Object.assign({ type: "checkbox", name, value: "on" }, on ? { checked: "" } : {}, extra));

// Add Contact as the server draws it: calls on, texts on, Avansert closed.
function addForm({ calls = [true, true], texts = [true, true] } = {}) {
    const kind = (pair) => (pair[0] && pair[1] ? "on" : !pair[0] && !pair[1] ? "off" : "custom");
    const parts = {
        callsShown: el("input", { type: "hidden", name: "calls_shown", value: kind(calls), "data-shown": "calls" }),
        callsRow: el("label", { "data-two-way": "calls" }),
        calls: box("calls", kind(calls) === "on"),
        callsCustom: el("div", { "data-custom": "calls", hidden: "" }),
        textsShown: el("input", { type: "hidden", name: "texts_shown", value: kind(texts), "data-shown": "texts" }),
        textsRow: el("label", { "data-two-way": "texts" }),
        texts: box("texts", kind(texts) === "on"),
        textsCustom: el("div", { "data-custom": "texts", hidden: "" }),
        more: el("input", { type: "hidden", name: "more", value: "", "data-more": "" }),
        advValue: el("span", { "data-adv-value": "", hidden: "" }),
        inbound: box("inbound", calls[0]),
        outbound: box("outbound", calls[1]),
        smsIn: box("sms_in", texts[0]),
        smsOut: box("sms_out", texts[1]),
    };
    parts.callsRow.children.push(parts.calls);
    parts.textsRow.children.push(parts.texts);
    parts.adv = el("details", { "data-adv": "" }, [
        el("summary", {}, [parts.advValue]),
        parts.inbound,
        parts.outbound,
        parts.smsIn,
        parts.smsOut,
    ]);
    parts.form = el("form", { "data-add-contact": "" }, [
        parts.more,
        parts.callsShown,
        parts.callsRow,
        parts.callsCustom,
        parts.textsShown,
        parts.textsRow,
        parts.textsCustom,
        parts.adv,
    ]);
    return parts;
}

function setup(form) {
    const doc = el("document", {}, [form]);
    install(doc);
}

function change(parts, input, on) {
    input.checked = on;
    parts.form.fire("change", input);
}

test("more follows the Avansert, opened and closed", () => {
    const parts = addForm();
    setup(parts.form);
    assert.strictEqual(parts.more.value, "");
    parts.adv.setAttribute("open", "");
    parts.adv.fire("toggle");
    assert.strictEqual(parts.more.value, "1");
    parts.adv.removeAttribute("open");
    parts.adv.fire("toggle");
    assert.strictEqual(parts.more.value, "");
});

test("narrowing one direction posts custom with the pair, and shows Tilpasset", () => {
    const parts = addForm();
    setup(parts.form);
    change(parts, parts.outbound, false);
    assert.strictEqual(parts.callsShown.value, "custom");
    assert.ok(parts.callsRow.hasAttribute("hidden"), "the switch is hidden, not removed");
    assert.ok(!parts.callsCustom.hasAttribute("hidden"));
    assert.ok(!parts.advValue.hasAttribute("hidden"), "Avansert says Tilpasset");
    assert.deepStrictEqual([parts.inbound.checked, parts.outbound.checked], [true, false]);
    // The texts channel is untouched.
    assert.strictEqual(parts.textsShown.value, "on");
});

test("the two-way switch off, then one direction on, posts custom with that direction", () => {
    const parts = addForm();
    setup(parts.form);
    change(parts, parts.texts, false);
    assert.deepStrictEqual([parts.smsIn.checked, parts.smsOut.checked], [false, false]);
    assert.strictEqual(parts.textsShown.value, "off");
    change(parts, parts.smsIn, true);
    assert.strictEqual(parts.textsShown.value, "custom");
    assert.deepStrictEqual([parts.smsIn.checked, parts.smsOut.checked], [true, false]);
    assert.ok(parts.textsRow.hasAttribute("hidden"));
});

test("back to equal draws the switch and posts on or off", () => {
    // From a held message: calls off, texts custom (only the contact texts in).
    const parts = addForm({ calls: [false, false], texts: [true, false] });
    setup(parts.form);
    change(parts, parts.smsOut, true);
    assert.strictEqual(parts.textsShown.value, "on");
    assert.ok(parts.texts.checked);
    assert.ok(!parts.textsRow.hasAttribute("hidden"));
    assert.ok(parts.textsCustom.hasAttribute("hidden"));
    assert.ok(parts.advValue.hasAttribute("hidden"));
    change(parts, parts.calls, true);
    assert.deepStrictEqual([parts.inbound.checked, parts.outbound.checked], [true, true]);
    assert.strictEqual(parts.callsShown.value, "on");
    change(parts, parts.smsIn, false);
    change(parts, parts.smsOut, false);
    assert.strictEqual(parts.textsShown.value, "off");
    assert.ok(!parts.texts.checked);
});

test("a contact's edit form only follows its Avansert - no mirror racing its autosave", () => {
    const more = el("input", { type: "hidden", name: "more", value: "1", "data-more": "" });
    const inbound = box("inbound", true);
    const adv = el("details", { "data-adv": "", open: "" }, [inbound]);
    const shown = el("input", { type: "hidden", name: "calls_shown", value: "on", "data-shown": "calls" });
    const form = el("form", { "data-contact-form": "" }, [more, shown, adv]);
    setup(form);
    assert.strictEqual(more.value, "1");
    inbound.checked = false;
    form.fire("change", inbound);
    assert.strictEqual(shown.value, "on", "the server resolves it");
    adv.removeAttribute("open");
    adv.fire("toggle");
    assert.strictEqual(more.value, "");
});
