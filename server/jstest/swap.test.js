// Behaviour of static/swap.js (design 103 §2: saves in place). A small fake DOM below stands in for the browser: the
// controls' dirty flags, <details>, a top-to-bottom layout of `data-h` heights. What only a real engine can show (the
// dirty flags of a real input, DOMParser's <noscript>, form.elements after importNode, getClientRects in a closed
// <details>) is jstest/real/swap-real.js, run by hand in Chromium and WebKit.
// Run: node --test jstest/swap.test.js
"use strict";
const test = require("node:test");
const assert = require("node:assert");
const swap = require("../static/swap.js");
const { morph, keyOf, unswappable, withoutOneShot, anchorChain, anchorDelta } = swap;

// ---- A fake DOM ----

class FNode {
    constructor(type, name) {
        this.nodeType = type;
        this.nodeName = name;
        this.parentNode = null;
        this.childNodes = [];
        this.nodeValue = null;
    }
    get firstChild() { return this.childNodes[0] || null; }
    get nextSibling() {
        if (!this.parentNode) return null;
        const at = this.parentNode.childNodes.indexOf(this);
        return this.parentNode.childNodes[at + 1] || null;
    }
    get ownerDocument() {
        let n = this;
        while (n.parentNode) n = n.parentNode;
        return n.isDocument ? n : null;
    }
    get isConnected() { return !!this.ownerDocument; }
    insertBefore(node, before) {
        if (node.parentNode) node.parentNode.removeChild(node);
        const at = before ? this.childNodes.indexOf(before) : -1;
        if (at < 0) this.childNodes.push(node);
        else this.childNodes.splice(at, 0, node);
        node.parentNode = this;
        return node;
    }
    appendChild(node) { return this.insertBefore(node, null); }
    removeChild(node) {
        const at = this.childNodes.indexOf(node);
        assert.ok(at >= 0, "removeChild of a node that isn't a child");
        this.childNodes.splice(at, 1);
        node.parentNode = null;
        return node;
    }
    get textContent() {
        if (this.nodeType !== 1 && !this.isDocument) return this.nodeValue;
        return this.childNodes.map((c) => c.textContent).join("");
    }
    set textContent(text) {
        this.childNodes.slice().forEach((c) => this.removeChild(c));
        if (text) this.appendChild(new FText(text));
    }
}

class FText extends FNode {
    constructor(text) {
        super(3, "#text");
        this.nodeValue = text;
    }
    clone() { return new FText(this.nodeValue); }
}

class FElement extends FNode {
    constructor(tag) {
        super(1, tag.toUpperCase());
        this.attrs = new Map();
        this.dirtyValue = false;
        this.dirtyChecked = false;
        this.dirtySelected = false;
    }
    get attributes() { return Array.from(this.attrs, ([name, value]) => ({ name, value })); }
    getAttribute(n) { return this.attrs.has(n) ? this.attrs.get(n) : null; }
    hasAttribute(n) { return this.attrs.has(n); }
    setAttribute(n, v) { this.attrs.set(n, String(v)); }
    removeAttribute(n) { this.attrs.delete(n); }
    get id() { return this.getAttribute("id") || ""; }
    get type() { return (this.getAttribute("type") || "text").toLowerCase(); }
    get name() { return this.getAttribute("name") || ""; }
    // Value: follows the attribute until set (a hidden input's value is the attribute).
    get value() {
        if (this.nodeName === "TEXTAREA") return this.dirtyValue ? this._value : this.textContent;
        if (this.nodeName === "SELECT") {
            const on = this.options.find((o) => o.selected);
            return on ? on.getAttribute("value") : "";
        }
        if (this.type === "hidden") return this.getAttribute("value") || "";
        return this.dirtyValue ? this._value : this.getAttribute("value") || "";
    }
    set value(v) {
        if (this.type === "hidden" && this.nodeName === "INPUT") {
            this.setAttribute("value", v);
            return;
        }
        this.dirtyValue = true;
        this._value = String(v);
        this.log && this.log.push(["value", v]);
    }
    get defaultValue() { return this.nodeName === "TEXTAREA" ? this.textContent : this.getAttribute("value") || ""; }
    get checked() { return this.dirtyChecked ? this._checked : this.hasAttribute("checked"); }
    set checked(on) {
        this.dirtyChecked = true;
        this._checked = !!on;
        if (on && this.type === "radio") {
            const form = this.closest("form") || this.ownerDocument;
            if (form) {
                all(form, (e) => e !== this && e.nodeName === "INPUT" && e.type === "radio" && e.name === this.name)
                    .forEach((e) => { e.dirtyChecked = true; e._checked = false; });
            }
        }
    }
    get defaultChecked() { return this.hasAttribute("checked"); }
    get selected() { return this.dirtySelected ? this._selected : this.hasAttribute("selected"); }
    set selected(on) {
        this.dirtySelected = true;
        this._selected = !!on;
    }
    get defaultSelected() { return this.hasAttribute("selected"); }
    get options() { return all(this, (e) => e.nodeName === "OPTION"); }
    get form() { return this.closest("form"); }
    closest(selector) {
        for (let n = this; n && n.nodeType === 1; n = n.parentNode) if (matches(n, selector)) return n;
        return null;
    }
    querySelector(selector) { return query(this, selector)[0] || null; }
    querySelectorAll(selector) { return query(this, selector); }
    setSelectionRange(a, b) { this.caret = [a, b]; }
    // Layout (see `layout`): a closed <details> draws only its summary.
    getBoundingClientRect() {
        const doc = this.ownerDocument;
        doc.layout();
        return { top: this._top, bottom: this._top + (this._h || 0) };
    }
    getClientRects() {
        const doc = this.ownerDocument;
        if (!doc) return [];
        doc.layout();
        return this._drawn ? [{}] : [];
    }
    clone(deep) {
        const c = new FElement(this.nodeName.toLowerCase());
        this.attrs.forEach((v, k) => c.attrs.set(k, v));
        if (deep) this.childNodes.forEach((ch) => c.appendChild(ch.clone(true)));
        return c;
    }
}

class FDocument extends FNode {
    constructor() {
        super(9, "#document");
        this.isDocument = true;
        this.readyState = "complete";
        this.listeners = {};
        this.events = [];
        this.title = "";
        this.scrollTop = 0;
    }
    get documentElement() { return this.childNodes.find((c) => c.nodeName === "HTML"); }
    getElementById(id) { return all(this, (e) => e.getAttribute("id") === id)[0] || null; }
    querySelector(selector) { return query(this, selector)[0] || null; }
    querySelectorAll(selector) { return query(this, selector); }
    importNode(node, deep) { return node.clone(deep); }
    createElement(tag) { return new FElement(tag); }
    addEventListener(type, fn) { (this.listeners[type] = this.listeners[type] || []).push(fn); }
    dispatchEvent(e) {
        this.events.push([e.type, e.detail]);
        (this.listeners[e.type] || []).forEach((fn) => fn(e));
    }
    fire(type) { (this.listeners[type] || []).forEach((fn) => fn({ type })); }
    // Tops in document order: each element's own `data-h` px, from main's scroll.
    layout() {
        let y = -this.scrollTop;
        const walk = (n, drawn) => {
            if (n.nodeType !== 1) return;
            n._drawn = drawn && !n.hasAttribute("hidden");
            n._top = y;
            n._h = Number(n.getAttribute("data-h") || 0);
            if (n._drawn) y += n._h;
            const closed = n.nodeName === "DETAILS" && !n.hasAttribute("open");
            n.childNodes.forEach((c) => walk(c, n._drawn && (!closed || c.nodeName === "SUMMARY")));
        };
        this.childNodes.forEach((c) => walk(c, true));
    }
}

function all(rootNode, test) {
    const out = [];
    const walk = (n) => n.childNodes.forEach((c) => {
        if (c.nodeType === 1) {
            if (test(c)) out.push(c);
            walk(c);
        }
    });
    walk(rootNode);
    return out;
}

// Selectors: lists of `tag.class#id[attr][attr=value]` with an optional `a > b`.
function simple(el, sel) {
    const m = /^([a-z]*)((?:[.#][\w-]+|\[[^\]]+\])*)$/i.exec(sel.trim());
    assert.ok(m, "selector " + sel);
    if (m[1] && el.nodeName !== m[1].toUpperCase()) return false;
    const parts = m[2].match(/[.#][\w-]+|\[[^\]]+\]/g) || [];
    return parts.every((p) => {
        if (p[0] === ".") return (" " + (el.getAttribute("class") || "") + " ").includes(" " + p.slice(1) + " ");
        if (p[0] === "#") return el.getAttribute("id") === p.slice(1);
        const [name, value] = p.slice(1, -1).split("=");
        if (value === undefined) return el.hasAttribute(name);
        return el.getAttribute(name) === value.replace(/^"|"$/g, "");
    });
}
function matches(el, selector) {
    return selector.split(",").some((one) => {
        const steps = one.trim().split(/\s*>\s*/);
        if (steps.length === 1) return simple(el, steps[0]);
        return simple(el, steps[1]) && el.parentNode && el.parentNode.nodeType === 1 && simple(el.parentNode, steps[0]);
    });
}
function query(rootNode, selector) {
    return all(rootNode, (e) => matches(e, selector));
}

/** An element: `h("form", { action: "/x#x" }, children...)`; a string child is a text node. */
function h(tag, attrs, ...children) {
    const el = new FElement(tag);
    Object.keys(attrs || {}).forEach((k) => el.setAttribute(k, attrs[k] === true ? "" : attrs[k]));
    children.flat().forEach((c) => el.appendChild(typeof c === "string" ? new FText(c) : c));
    return el;
}

/** A document: html[lang] > head (its links and scripts) + body > main (the children). */
function page(mainChildren, { lang = "en", head = [], lock = null, bodyExtra = [] } = {}) {
    const doc = new FDocument();
    const html = h("html", Object.assign({ lang }, lock === null ? {} : { "data-lock-ms": lock }),
        h("head", {}, head.length ? head : [h("link", { rel: "stylesheet", href: "/static/style.css?v=1" }),
            h("script", { src: "/static/swap.js?v=1" })]),
        h("body", {}, h("div", { class: "top-strip", "data-h": "0" }), h("main", { class: "container" }, mainChildren),
            ...bodyExtra));
    doc.appendChild(html);
    return doc;
}

/** A plain ctx: nothing saved, nothing busy. */
function ctxFor(doc, over = {}) {
    return Object.assign({
        doc,
        focused: null,
        flashes: [],
        formOf: (el) => (el && el.closest ? el.closest("form") : null),
        saved: () => false,
        again: () => false,
        busy: () => false,
        busySubmitter: () => false,
        serverOpen: () => false,
        unchangedSince: () => true,
    }, over);
}

const mainOf = (doc) => doc.querySelector("main");
const shape = (el) => el.childNodes.filter((c) => c.nodeType === 1).map(keyOf);

// ---- The morph ----

test("the keys: the id, else the tag and its first soft key, else the tag; text by type", () => {
    assert.strictEqual(keyOf(h("div", { id: "a", "data-row": "x" })), "#a");
    assert.strictEqual(keyOf(h("form", { action: "/d/1/hardening#restrictions", name: "n" })),
        "FORM[action=/d/1/hardening#restrictions]");
    assert.strictEqual(keyOf(h("p", { "data-flash": "setup" })), "P[data-flash=setup]");
    assert.strictEqual(keyOf(h("label", { for: "x" })), "LABEL[for=x]");
    assert.strictEqual(keyOf(h("div", { class: "group" })), "DIV");
    assert.strictEqual(keyOf(new FText(" ")), "@3");
});

test("attributes and text are synced; an unchanged image is never touched", () => {
    const doc = page([h("p", { class: "row-value", "data-row": "v" }, "til 17:10"), h("img", { src: "/a.png", alt: "" })]);
    const img = doc.querySelector("img");
    let touched = 0;
    const set = img.setAttribute.bind(img);
    img.setAttribute = (n, v) => { touched++; set(n, v); };
    const p = doc.querySelector("p");
    const next = page([h("p", { class: "row-value is-new", "data-row": "v" }, "til 17:40"), h("img", { src: "/a.png", alt: "" })]);
    morph(mainOf(doc), mainOf(next), ctxFor(doc));
    assert.strictEqual(doc.querySelector("p"), p, "the same node");
    assert.strictEqual(p.getAttribute("class"), "row-value is-new");
    assert.strictEqual(p.textContent, "til 17:40");
    assert.strictEqual(touched, 0);
});

test("103-1: an Attention line inserted before a form keeps that form's node, and the anchor moves by its height", () => {
    const form = () => h("form", { action: "/devices/1/hardening#restrictions", "data-h": "300" },
        h("input", { type: "checkbox", name: "a", value: "on" }));
    const doc = page([h("section", { id: "restrictions" }, h("h2", { "data-h": "30" }, "Restrictions"), form())]);
    const kept = doc.querySelector("form");
    const chain = anchorChain(doc, mainOf(doc), kept);
    const next = page([h("section", { id: "restrictions" }, h("h2", { "data-h": "30" }, "Restrictions"),
        h("div", { class: "attention", "data-warning": "google-backup", "data-h": "64" }, "Backup is on"), form())]);
    morph(mainOf(doc), mainOf(next), ctxFor(doc));
    assert.strictEqual(doc.querySelector("form"), kept, "the form's node stays");
    assert.deepStrictEqual(shape(doc.getElementById("restrictions")),
        ["H2", "DIV[data-warning=google-backup]", "FORM[action=/devices/1/hardening#restrictions]"]);
    assert.strictEqual(anchorDelta(doc, chain), 64, "main scrolls by the line's height");
});

test("103-1: a line removed before a form, an id'd row moved, two id-less forms told apart by action", () => {
    const formA = () => h("form", { action: "/x/a#g" }, h("input", { name: "on", type: "checkbox" }));
    const formB = () => h("form", { action: "/x/b#g" }, h("input", { name: "on", type: "checkbox" }));
    const doc = page([h("div", { class: "group", id: "g" },
        h("p", { class: "flash", "data-flash": "g" }, "Saved."), formA(), formB(),
        h("div", { id: "row-1" }, "one"), h("div", { id: "row-2" }, "two"))]);
    const [a, b] = doc.querySelectorAll("form");
    const row1 = doc.getElementById("row-1");
    const row2 = doc.getElementById("row-2");
    // The flash goes, form B comes first, the rows swap.
    const next = page([h("div", { class: "group", id: "g" }, formB(), formA(),
        h("div", { id: "row-2" }, "two"), h("div", { id: "row-1" }, "one!"))]);
    morph(mainOf(doc), mainOf(next), ctxFor(doc));
    const forms = doc.querySelectorAll("form");
    assert.deepStrictEqual(forms.map((f) => f.getAttribute("action")), ["/x/b#g", "/x/a#g"]);
    assert.ok(forms.includes(a) && forms.includes(b), "both form nodes kept");
    assert.strictEqual(doc.getElementById("row-1"), row1, "moved, never rebuilt");
    assert.strictEqual(doc.getElementById("row-2"), row2);
    assert.strictEqual(row1.textContent, "one!");
    assert.strictEqual(doc.querySelector("[data-flash]"), null);
});

test("<noscript> is kept and never descended into; an imported subtree's <noscript> is emptied (103-10)", () => {
    const doc = page([h("form", { action: "/f#f" }, h("noscript", {}))]);
    const noscript = doc.querySelector("noscript");
    const next = page([
        h("form", { action: "/f#f" }, h("noscript", {}, h("button", { type: "submit" }, "Save"))),
        h("form", { action: "/g#g" }, h("input", { name: "x" }), h("noscript", {}, h("button", { type: "submit" }, "Save")),
            h("script", { src: "/x.js" })),
    ]);
    morph(mainOf(doc), mainOf(next), ctxFor(doc));
    assert.strictEqual(doc.querySelector("noscript"), noscript);
    assert.strictEqual(noscript.childNodes.length, 0);
    const added = doc.querySelectorAll("form")[1];
    assert.strictEqual(added.querySelector("noscript").childNodes.length, 0, "no real Save button joins the form");
    assert.strictEqual(added.querySelector("script"), null, "no script is inserted");
});

test("[data-flash] is always imported fresh, so the same words twice are a new line", () => {
    const flash = () => h("p", { class: "flash", role: "status", "data-flash": "setup" }, "Sent.");
    const doc = page([h("div", { class: "group" }, h("details", { id: "setup-sheet" }), flash())]);
    const old = doc.querySelector("[data-flash]");
    const ctx = ctxFor(doc);
    morph(mainOf(doc), mainOf(page([h("div", { class: "group" }, h("details", { id: "setup-sheet" }), flash())])), ctx);
    const now = doc.querySelector("[data-flash]");
    assert.notStrictEqual(now, old);
    assert.strictEqual(now.textContent, "Sent.");
    assert.deepStrictEqual(ctx.flashes, [now], "collected for #swap-status");
});

test("data-swap-keep: a script's own element stays where it is", () => {
    const banner = h("div", { id: "push-banner", "data-swap-keep": true }, "Notifications are off");
    const doc = page([h("div", { class: "sheet" }, banner, h("p", { id: "a" }, "a"))]);
    morph(mainOf(doc), mainOf(page([h("div", { class: "sheet" }, h("p", { id: "a" }, "a2"))])), ctxFor(doc));
    assert.strictEqual(doc.getElementById("push-banner"), banner);
    assert.deepStrictEqual(shape(doc.querySelector(".sheet")), ["#push-banner", "#a"]);
});

test("D4: an open <details> stays open, a closed one closed; one holding the button that saved takes the server's", () => {
    const doc = page([
        h("details", { id: "contact-1", open: true }, h("summary", {}), h("form", { action: "/c/1#c" })),
        h("details", { id: "info" }, h("summary", {})),
        h("details", { id: "setup-sheet", open: true }, h("summary", {}),
            h("form", { action: "/d/1/start-pause#setup" }, h("button", { type: "submit", id: "go" }, "Open now"))),
    ]);
    const button = doc.getElementById("go");
    const next = page([
        h("details", { id: "contact-1" }, h("summary", {}), h("form", { action: "/c/1#c" })),
        h("details", { id: "info", open: true }, h("summary", {})),
        h("details", { id: "setup-sheet" }, h("summary", {}),
            h("form", { action: "/d/1/start-pause#setup" }, h("button", { type: "submit", id: "go" }, "Open now"))),
    ]);
    const around = [doc.getElementById("setup-sheet")];
    morph(mainOf(doc), mainOf(next), ctxFor(doc, { serverOpen: (d) => around.includes(d) }));
    assert.ok(doc.getElementById("contact-1").hasAttribute("open"), "the parent's open row stays");
    assert.ok(!doc.getElementById("info").hasAttribute("open"), "a closed one stays closed");
    assert.ok(!doc.getElementById("setup-sheet").hasAttribute("open"), "the sheet the button sat in closes");
    assert.strictEqual(doc.getElementById("go"), button);
});

test("a busy form's aria-busy and data-held and its button's disabled are never synced (103-3)", () => {
    const doc = page([h("form", { action: "/f#f", "aria-busy": "true", "data-held": true },
        h("button", { id: "b", disabled: true, "aria-busy": "true" }, "Go"))]);
    const form = doc.querySelector("form");
    const button = doc.getElementById("b");
    morph(mainOf(doc), mainOf(page([h("form", { action: "/f#f" }, h("button", { id: "b" }, "Go"))])),
        ctxFor(doc, { busy: (f) => f === form, busySubmitter: (el) => el === button }));
    assert.strictEqual(form.getAttribute("aria-busy"), "true");
    assert.ok(form.hasAttribute("data-held"));
    assert.ok(button.hasAttribute("disabled") && button.getAttribute("aria-busy") === "true");
    // Not busy any more: the server's.
    morph(mainOf(doc), mainOf(page([h("form", { action: "/f#f" }, h("button", { id: "b" }, "Go"))])), ctxFor(doc));
    assert.ok(!form.hasAttribute("aria-busy") && !button.hasAttribute("disabled"));
});

test("controls: a saved form takes the server's, an again or dirty one keeps the parent's, defaults follow the server", () => {
    const build = (on, text, choice) => [
        h("form", { action: "/saved#s" },
            h("input", Object.assign({ type: "checkbox", name: "sw", value: "on" }, on ? { checked: true } : {})),
            h("input", { type: "text", name: "t", value: text }),
            h("select", { name: "sel" }, ...["a", "b"].map((v) => h("option", Object.assign({ value: v }, v === choice ? { selected: true } : {}))))),
        h("form", { action: "/again#a" },
            h("input", Object.assign({ type: "checkbox", name: "sw", value: "on" }, on ? { checked: true } : {}))),
        h("form", { action: "/other#o" },
            h("input", { type: "text", name: "t", value: text }),
            h("input", { type: "hidden", name: "h", value: text }),
            h("input", { type: "radio", name: "r", value: "1", checked: true }), h("input", { type: "radio", name: "r", value: "2" })),
    ];
    const doc = page(build(false, "old", "a"));
    const [saved, again, other] = doc.querySelectorAll("form");
    // The parent flipped the saved switch (it was saved as on), flipped the queued one, typed in another form.
    saved.querySelector("input").checked = true;
    saved.querySelector("select").options[1].selected = true;
    again.querySelector("input").checked = true;
    other.querySelector("input[type=text]").value = "typed";
    other.querySelectorAll("input[type=radio]")[1].checked = true;
    // The server refused the saved switch (it stays off) and normalised the text; every default moves.
    const next = page(build(false, "server", "a"));
    morph(mainOf(doc), mainOf(next), ctxFor(doc, {
        saved: (f) => f === saved,
        again: (f) => f === again,
    }));
    assert.strictEqual(saved.querySelector("input").checked, false, "the server's refusal shows");
    assert.strictEqual(saved.querySelector("input[type=text]").value, "server");
    assert.strictEqual(saved.querySelector("select").value, "a");
    assert.strictEqual(again.querySelector("input").checked, true, "the queued flip is kept");
    assert.strictEqual(other.querySelector("input[type=text]").value, "typed", "the typed text is kept");
    assert.strictEqual(other.querySelector("input[type=text]").defaultValue, "server", "its default is the server's");
    assert.strictEqual(other.querySelector("input[type=hidden]").value, "server", "a hidden field is its attribute");
    assert.strictEqual(other.querySelectorAll("input[type=radio]")[1].checked, true, "the picked radio is kept");
    // An untouched control elsewhere follows the server.
    const calm = page([h("form", { action: "/x#x" }, h("input", { type: "checkbox", name: "c" }))]);
    morph(mainOf(calm), mainOf(page([h("form", { action: "/x#x" }, h("input", { type: "checkbox", name: "c", checked: true }))])),
        ctxFor(calm));
    assert.strictEqual(calm.querySelector("input").checked, true);
});

test("103-9: the focused field - in the saved form the server's when it still holds what was sent, elsewhere untouched", () => {
    const form = (v) => h("form", { action: "/n#n" }, h("input", { type: "text", name: "name", value: v }));
    let doc = page([form("Ola")]);
    let input = doc.querySelector("input");
    input.value = "ola ";
    let f = doc.querySelector("form");
    morph(mainOf(doc), mainOf(page([form("Ola")])), ctxFor(doc, {
        focused: input, saved: (x) => x === f, unchangedSince: () => true,
    }));
    assert.strictEqual(input.value, "Ola", "the server's normalised value");
    assert.deepStrictEqual(input.caret, [3, 3], "the caret at the end");
    // Typed on after the save went: the parent's text stays.
    doc = page([form("Ola")]);
    input = doc.querySelector("input");
    f = doc.querySelector("form");
    input.value = "Ola N";
    morph(mainOf(doc), mainOf(page([form("Ola")])), ctxFor(doc, {
        focused: input, saved: (x) => x === f, unchangedSince: () => false,
    }));
    assert.strictEqual(input.value, "Ola N");
    // Elsewhere: not even its value attribute moves.
    doc = page([form("Ola")]);
    input = doc.querySelector("input");
    morph(mainOf(doc), mainOf(page([form("Kari")])), ctxFor(doc, { focused: input }));
    assert.strictEqual(input.getAttribute("value"), "Ola");
    assert.strictEqual(input.value, "Ola");
});

// ---- The place ----

test("103-2: a form in a sheet that closes anchors on the sheet's row; one that left its group scrolls nothing", () => {
    const sheet = (open) => h("section", { id: "setup" }, h("div", { class: "group" },
        h("details", Object.assign({ id: "setup-sheet" }, open ? { open: true } : {}),
            h("summary", { "data-h": "44" }), h("p", { "data-h": "40" }),
            h("form", { action: "/d/1/start-pause#setup", "data-h": "44" }))));
    const doc = page([h("div", { "data-h": "200" }), sheet(true)]);
    doc.scrollTop = 100;
    const form = doc.querySelector("form");
    const chain = anchorChain(doc, mainOf(doc), form);
    assert.deepStrictEqual(chain.map((c) => c.id), [null, "setup-sheet", "setup"]);
    morph(mainOf(doc), mainOf(page([h("div", { "data-h": "200" }), sheet(false)])),
        ctxFor(doc, { serverOpen: () => true }));
    assert.strictEqual(form.getClientRects().length, 0, "the form is not drawn");
    assert.strictEqual(anchorDelta(doc, chain), 0, "the sheet's row stays where it was");
    // A warning above it as it closes: the row moves by the warning.
    const doc2 = page([h("div", { "data-h": "200" }), sheet(true)]);
    const chain2 = anchorChain(doc2, mainOf(doc2), doc2.querySelector("form"));
    morph(mainOf(doc2), mainOf(page([h("div", { "data-h": "200" }), h("div", { "data-warning": "w", "data-h": "50" }), sheet(false)])),
        ctxFor(doc2, { serverOpen: () => true }));
    assert.strictEqual(anchorDelta(doc2, chain2), 50);
    // A row moved to another group: never followed.
    const groups = (where) => [
        h("div", { id: "on", "data-h": "10" }, where === "on" ? h("form", { id: "app-7", action: "/a/7#app-7", "data-h": "40" }) : []),
        h("div", { id: "off", "data-h": "10" }, h("div", { "data-h": "500" }), where === "off" ? h("form", { id: "app-7", action: "/a/7#app-7", "data-h": "40" }) : []),
    ];
    const doc3 = page(groups("on"));
    const chain3 = anchorChain(doc3, mainOf(doc3), doc3.getElementById("app-7"));
    morph(mainOf(doc3), mainOf(page(groups("off"))), ctxFor(doc3));
    assert.ok(doc3.getElementById("off").querySelector("#app-7"), "moved");
    assert.strictEqual(anchorDelta(doc3, chain3), 0);
});

test("103-15: without a save the anchor is the first id'd element at or below main's visible top", () => {
    const doc = page([h("div", { id: "a", "data-h": "100" }), h("div", { id: "b", "data-h": "100" }), h("div", { id: "c", "data-h": "100" })]);
    doc.scrollTop = 150;
    const main = mainOf(doc);
    // Main starts at 0; the strip covers the top 20 px.
    doc.querySelector(".top-strip").setAttribute("data-h", "20");
    const strip = doc.querySelector(".top-strip");
    strip.getBoundingClientRect = () => ({ top: 0, bottom: 20 });
    main.getBoundingClientRect = () => ({ top: 0, bottom: 800 });
    const chain = anchorChain(doc, main, null);
    assert.deepStrictEqual(chain.map((c) => c.id), ["c"]);
});

// ---- The flow ----

function html(doc) {
    return doc; // the fake "HTML" is the parsed page itself (`parse` below returns it)
}

/** A window with handySwap installed; `answers` gives each GET's answer. */
function flowWindow({ doc = page([h("p", { id: "x" }, "old")]), stay = null } = {}) {
    const fetched = [];
    const went = [];
    const timers = [];
    let answer = () => ({ status: 200, type: "basic", headers: { get: () => "text/html; charset=utf-8" }, text: () => Promise.resolve(page([h("p", { id: "x" }, "new")])) });
    const win = {
        document: doc,
        name: "handy-scroll:{}",
        location: { href: "https://vibb.example/devices/1/advanced", pathname: "/devices/1/advanced", search: "", hash: "", reload: () => went.push(["reload"]), replace: (u) => went.push(["replace", u]) },
        history: { state: { s: 1 }, replaceState: (s, t, u) => went.push(["replaceState", u]) },
        sessionStorage: { removeItem: (k) => went.push(["removeItem", k]) },
        DOMParser: function () {},
        CustomEvent: function (type, init) { this.type = type; this.detail = init.detail; },
        getComputedStyle: () => ({ overflowY: "auto" }),
        setTimeout: (fn) => timers.push(fn),
        fetch: (url, init) => {
            fetched.push([url, init]);
            return new Promise((resolve, reject) => {
                const a = answer(url);
                if (a instanceof Error) reject(a);
                else Promise.resolve(a).then(resolve, reject);
            });
        },
        handyStay: stay,
    };
    const api = swap.install(win, { parse: (x) => x });
    return {
        win, api, fetched, went, timers,
        answer: (fn) => { answer = fn; },
        pageAnswer: (p) => () => ({ status: 200, type: "basic", headers: { get: () => "text/html" }, text: () => Promise.resolve(p) }),
    };
}
const settle = () => new Promise((r) => setImmediate(r));

test("a refresh: the page morphed in place, the address without one-shot words, the record cleared, the events", async () => {
    const stay = { inflight: () => 0, entry: () => null, landed: (forms) => stay.released.push(forms), released: [] };
    const doc = page([h("form", { id: "f", action: "/x#f" }), h("p", { id: "x" }, "old")],
        { bodyExtra: [h("div", { id: "swap-status" })] });
    const w = flowWindow({ doc, stay });
    const p = doc.getElementById("x");
    const form = doc.getElementById("f");
    w.answer(w.pageAnswer(page([h("form", { id: "f", action: "/x#f" }),
        h("p", { class: "flash", "data-flash": "f" }, "Saved."), h("p", { id: "x" }, "new")])));
    assert.strictEqual(w.api.refresh("https://vibb.example/devices/1/advanced?notice=saved_x&from=apps#f", { forms: [form] }), true);
    await settle();
    await settle();
    assert.strictEqual(w.fetched[0][0], "https://vibb.example/devices/1/advanced?notice=saved_x&from=apps");
    assert.deepStrictEqual(w.fetched[0][1], { credentials: "same-origin", redirect: "manual", cache: "no-store" });
    assert.strictEqual(doc.getElementById("x"), p);
    assert.strictEqual(p.textContent, "new");
    assert.deepStrictEqual(w.went, [
        ["replaceState", "https://vibb.example/devices/1/advanced?from=apps#f"],
        ["removeItem", "handy-scroll"],
    ]);
    assert.strictEqual(w.win.name, "", "window.name's record cleared");
    assert.deepStrictEqual(doc.events.map((e) => e[0]), ["handy:swapped", "handy:saved"]);
    assert.deepStrictEqual(doc.events[1][1], { forms: [form], pending: 0 });
    assert.deepStrictEqual(stay.released, [[form]]);
    w.timers.forEach((fn) => fn());
    assert.strictEqual(doc.getElementById("swap-status").textContent, "Saved.", "the flash is spoken");
    assert.strictEqual(withoutOneShot("/a?saved=1&app=x&b=2#g"), "/a?b=2#g");
});

test("what can't be swapped is reloaded for real: lang, lock, CSS, scripts, an unready page script, no main", async () => {
    const base = page([]);
    assert.strictEqual(unswappable(base, page([])), null);
    assert.strictEqual(unswappable(base, page([], { lang: "nb" })), "lang");
    assert.strictEqual(unswappable(base, page([], { lock: "600000" })), "lock");
    assert.strictEqual(unswappable(base, page([], { head: [h("link", { rel: "stylesheet", href: "/static/style.css?v=2" }), h("script", { src: "/static/swap.js?v=1" })] })), "css");
    assert.strictEqual(unswappable(base, page([], { head: [h("link", { rel: "stylesheet", href: "/static/style.css?v=1" }), h("script", { src: "/static/swap.js?v=2" })] })), "scripts");
    const noMain = new FDocument();
    noMain.appendChild(h("html", { lang: "en" }, h("body", {})));
    assert.strictEqual(unswappable(base, noMain), "main");
    const withPage = (ready) => page([h("script", Object.assign({ src: "/static/quick-sheets.js?v=2" }, ready ? { "data-swap-ready": true } : {}))]);
    assert.strictEqual(unswappable(withPage(false), withPage(false)), "unready");
    assert.strictEqual(unswappable(withPage(true), withPage(true)), null);
    // The flow: each one is the caller's real reload.
    const w = flowWindow();
    const fallbacks = [];
    w.answer(w.pageAnswer(page([], { lang: "nb" })));
    w.api.refresh("https://vibb.example/devices/1/advanced", { fallback: () => fallbacks.push("lang") });
    await settle(); await settle();
    for (const [name, res] of [
        ["500", { status: 500, type: "basic", headers: { get: () => "text/html" } }],
        ["json", { status: 200, type: "basic", headers: { get: () => "application/json" } }],
        ["signed out", { status: 0, type: "opaqueredirect", headers: { get: () => null } }],
    ]) {
        w.answer(() => res);
        w.api.refresh("https://vibb.example/devices/1/advanced", { fallback: () => fallbacks.push(name) });
        await settle(); await settle();
    }
    assert.deepStrictEqual(fallbacks, ["lang", "500", "json", "signed out"]);
    // A 423 is the lock.
    const locked = [];
    w.win.handyLock = { lock: (next) => locked.push(next) };
    w.answer(() => ({ status: 423, type: "basic", headers: { get: () => "text/html" } }));
    w.api.refresh("https://vibb.example/devices/1/advanced", { fallback: () => fallbacks.push("423") });
    await settle(); await settle();
    assert.deepStrictEqual(locked, ["/devices/1/advanced"]);
    assert.strictEqual(fallbacks.length, 4);
});

test("103-3: the newest GET wins and inherits a superseded landing's forms; a GET answered while a save is out is dropped", async () => {
    let out = 0;
    const stay = { inflight: () => out, entry: () => null, landed: () => {} };
    const doc = page([h("form", { id: "a", action: "/a#a" }), h("form", { id: "b", action: "/b#b" }), h("p", { id: "x" }, "old")]);
    const w = flowWindow({ doc, stay });
    const resolvers = [];
    w.answer(() => new Promise((r) => resolvers.push(r)));
    const [a, b] = doc.querySelectorAll("form");
    w.api.refresh("https://vibb.example/devices/1/advanced#a", { forms: [a] });
    w.api.refresh("https://vibb.example/devices/1/advanced#b", { forms: [b] });
    const answerWith = (text) => ({ status: 200, type: "basic", headers: { get: () => "text/html" },
        text: () => Promise.resolve(page([h("form", { id: "a", action: "/a#a" }), h("form", { id: "b", action: "/b#b" }), h("p", { id: "x" }, text)])) });
    resolvers[1](answerWith("second"));
    resolvers[0](answerWith("first"));
    await settle(); await settle();
    assert.strictEqual(doc.getElementById("x").textContent, "second", "only the newest is used");
    const saved = doc.events.find((e) => e[0] === "handy:saved");
    assert.deepStrictEqual(saved[1].forms, [a, b], "the first landing's form is inherited");
    // A save on its way: the answer is dropped, nothing reloads; the next refresh carries on.
    doc.events.length = 0;
    out = 1;
    const fallbacks = [];
    w.api.refresh("https://vibb.example/devices/1/advanced", { forms: [a], fallback: () => fallbacks.push("x") });
    resolvers[2]({ status: 500, type: "basic", headers: { get: () => "text/html" } });
    await settle(); await settle();
    assert.deepStrictEqual(doc.events, []);
    assert.deepStrictEqual(fallbacks, [], "no real reload under a POST on its way");
    assert.strictEqual(w.api.pending(), true, "the landing record waits for the next landing");
    out = 0;
    w.api.refresh("https://vibb.example/devices/1/advanced", { forms: [b] });
    resolvers[3](answerWith("third"));
    await settle(); await settle();
    assert.deepStrictEqual(doc.events.find((e) => e[0] === "handy:saved")[1].forms, [a, b]);
});

test("103-6: an answer while the page is still parsing waits for DOMContentLoaded", async () => {
    const doc = page([h("p", { id: "x" }, "old")]);
    doc.readyState = "loading";
    const w = flowWindow({ doc });
    w.api.refresh("https://vibb.example/devices/1/advanced");
    await settle(); await settle();
    assert.strictEqual(doc.getElementById("x").textContent, "old");
    doc.readyState = "interactive";
    doc.fire("DOMContentLoaded");
    assert.strictEqual(doc.getElementById("x").textContent, "new");
});

test("D1 (a): a GET that fails holds the forms and hands over to reconnect.js's swap wait", async () => {
    const doc = page([h("form", { id: "f", action: "/f#f", "aria-busy": "true" })]);
    const w = flowWindow({ doc, stay: { inflight: () => 0, entry: () => ({ again: false }), landed: () => {} } });
    const waits = [];
    w.win.handyReconnect = { waitSwap: (url) => waits.push(url) };
    w.answer(() => new Error("Load failed"));
    const form = doc.getElementById("f");
    w.api.refresh("https://vibb.example/devices/1/advanced#f", { forms: [form], lost: true });
    await settle(); await settle();
    assert.deepStrictEqual(waits, ["https://vibb.example/devices/1/advanced#f"]);
    assert.ok(form.hasAttribute("data-held"), "dimmed: neither state is saved");
    assert.deepStrictEqual(doc.events.map((e) => e[0]), ["handy:swapwait"]);
    // The server answers: the wait's refresh lands with the forms, the held mark goes, the red line comes.
    const words = h("div", { id: "js-words", "data-w-save-unknown": "No answer from the server. This is what is saved now." });
    doc.querySelector("body").appendChild(words);
    w.answer(w.pageAnswer(page([h("div", { class: "group" }, h("form", { id: "f", action: "/f#f" }))])));
    w.api.refresh("https://vibb.example/devices/1/advanced#f");
    await settle(); await settle();
    assert.ok(!form.hasAttribute("data-held"));
    const events = doc.events.map((e) => e[0]);
    assert.deepStrictEqual(events, ["handy:swapwait", "handy:swapped", "handy:saved"]);
    const line = doc.querySelector(".flash-error");
    assert.ok(line, "the red line");
    assert.strictEqual(line.textContent, "No answer from the server. This is what is saved now.");
    assert.strictEqual(line.parentNode, doc.querySelector(".group"), "in the form's group");
});

// ---- The code QA's fix round ----

test("M1: a focused switch outside the saved forms follows the server; a focused text field keeps its own", () => {
    const form = (on, v) => h("form", { action: "/f#f" },
        h("input", Object.assign({ type: "checkbox", name: "sw", value: "on" }, on ? { checked: true } : {})),
        h("input", { type: "text", name: "t", value: v }));
    const doc = page([form(false, "a")]);
    const sw = doc.querySelector("input[type=checkbox]");
    sw.checked = true; // tapped and saved earlier: its dirty flag is set, the page came back with it on
    sw.setAttribute("checked", "");
    morph(mainOf(doc), mainOf(page([form(false, "a")])), ctxFor(doc, { focused: sw }));
    assert.strictEqual(sw.checked, false, "the server's off");
    assert.strictEqual(sw.defaultChecked, false);
    const text = doc.querySelector("input[type=text]");
    morph(mainOf(doc), mainOf(page([form(false, "b")])), ctxFor(doc, { focused: text }));
    assert.strictEqual(text.getAttribute("value"), "a", "a focused text field's attribute stays");
});

test("L2: in the saved form a field typed on during the round trip keeps the parent's text", () => {
    const form = (v, w) => h("form", { action: "/f#f" }, h("input", { type: "text", name: "a", value: v }),
        h("input", { type: "text", name: "b", value: w }));
    const doc = page([form("x", "y")]);
    const f = doc.querySelector("form");
    const [a, b] = f.querySelectorAll("input");
    a.value = "x2";
    b.value = "typed";
    // `a` was sent as "x2"; `b` changed after the save went (sent "y").
    const sent = [[a, "x2"], [b, "y"]];
    morph(mainOf(doc), mainOf(page([form("X2", "y")])), ctxFor(doc, {
        saved: (x) => x === f,
        unchangedSince: (_form, el) => { const s = sent.find((p) => p[0] === el); return s ? s[1] === el.value : el.value === el.defaultValue; },
    }));
    assert.strictEqual(a.value, "X2", "sent and unchanged: the server's");
    assert.strictEqual(b.value, "typed", "typed on: the parent's");
});

test("L3: a focused textarea outside the saved forms keeps its text; L4: a changed select is put back by value", () => {
    const build = (text, opts) => [h("form", { action: "/f#f" }, h("textarea", { name: "n" }, text),
        h("select", { name: "s" }, ...opts.map(([v, sel]) => h("option", Object.assign({ value: v }, sel ? { selected: true } : {}), v))))];
    const doc = page(build("old", [["a", true], ["b", false], ["c", false]]));
    const area = doc.querySelector("textarea");
    const select = doc.querySelector("select");
    select.options[0].selected = false; // the parent picked c (a single select: a goes)
    select.options[2].selected = true;
    morph(mainOf(doc), mainOf(page(build("new", [["z", false], ["a", true], ["b", false], ["c", false]]))), ctxFor(doc, { focused: area }));
    assert.strictEqual(area.textContent, "old", "its default text didn't move under the caret");
    assert.strictEqual(select.value, "c", "the parent's c, though the server added an option before it");
});

test("L6: under the app lock's veil the new title goes to app-lock.js, not the tab", async () => {
    const doc = page([h("p", { id: "x" }, "old")]);
    doc.documentElement.setAttribute("class", "veiled");
    doc.title = "Vibb";
    const w = flowWindow({ doc });
    const titles = [];
    w.win.handyLock = { setTitle: (t) => titles.push(t) };
    const next = page([h("p", { id: "x" }, "new")]);
    next.title = "Avansert · Vibb Phone";
    w.answer(w.pageAnswer(next));
    w.api.refresh("https://vibb.example/devices/1/advanced");
    await settle(); await settle();
    assert.strictEqual(doc.title, "Vibb");
    assert.deepStrictEqual(titles, ["Avansert · Vibb Phone"]);
});

test("L1: a plain refresh asks without the one-shot words and speaks nothing; L8: another origin is refused", async () => {
    const doc = page([h("p", { class: "flash", "data-flash": "q" }, "Sent.")], { bodyExtra: [h("div", { id: "swap-status" })] });
    const w = flowWindow({ doc });
    w.answer(w.pageAnswer(page([h("p", { class: "flash", "data-flash": "q" }, "Sent.")])));
    w.api.refresh("https://vibb.example/devices/1?done=lock&cancel=x&from=apps#quick");
    await settle(); await settle();
    assert.strictEqual(w.fetched[0][0], "https://vibb.example/devices/1?from=apps");
    w.timers.forEach((fn) => fn());
    assert.strictEqual(doc.getElementById("swap-status").textContent, "", "nothing spoken");
    w.win.URL = URL;
    assert.strictEqual(w.api.refresh("https://other.example/devices/1"), false);
});

test("L5: the red line goes in before the place is kept, in the touched control's group", async () => {
    const groups = () => [h("div", { class: "group", "data-h": "100" }, h("label", { "data-h": "40" }, h("input", { type: "checkbox", name: "a" }))),
        h("div", { class: "group", "data-h": "100" }, h("label", { "data-h": "40" }, h("input", { type: "checkbox", name: "b" })))];
    const doc = page([h("form", { id: "r", action: "/r#r" }, groups())], { bodyExtra: [h("div", { id: "js-words", "data-w-save-unknown": "No answer." })] });
    const form = doc.getElementById("r");
    const b = form.querySelectorAll("input")[1];
    const stay = { inflight: () => 0, entry: () => null, landed: () => {} };
    const w = flowWindow({ doc, stay });
    doc.activeElement = b;
    w.answer(w.pageAnswer(page([h("form", { id: "r", action: "/r#r" }, groups())])));
    w.api.refresh("https://vibb.example/devices/1/advanced#r", { forms: [form], lost: true });
    await settle(); await settle();
    const line = doc.querySelector(".flash-error");
    assert.strictEqual(line.parentNode, b.closest(".group"), "the second group, the touched switch's");
});
