// Behaviour of static/app-display.js (design 127 §2.4): the Home tile beside the name follows Logo · Automatisk ·
// Symbol, the symbol and its colour, and the name.
// Run: node --test jstest/app-display.test.js
"use strict";
const test = require("node:test");
const assert = require("node:assert");
const { update, install, AUTO_PREVIEW, OWN_PREVIEW } = require("../static/app-display.js");

// A small DOM: enough elements, attributes and the script's selectors (`[attr]`, `input[name="x"]`, `:checked`).
class El {
    constructor(tag, attrs = {}, children = []) {
        this.tagName = tag.toUpperCase();
        this.attrs = {};
        for (const [k, v] of Object.entries(attrs)) if (!["text", "checked", "hidden"].includes(k)) this.attrs[k] = v;
        this.textContent = attrs.text || "";
        this.hidden = !!attrs.hidden;
        this.checked = !!attrs.checked;
        this.value = attrs.value || "";
        this.children = children;
        this.parent = null;
        children.forEach((c) => { c.parent = this; });
        const props = {};
        this.style = { setProperty: (k, v) => { props[k] = v; }, get: (k) => props[k] };
    }
    getAttribute(n) { return n in this.attrs ? String(this.attrs[n]) : null; }
    setAttribute(n, v) { this.attrs[n] = v; }
    removeAttribute(n) { delete this.attrs[n]; }
    all() { return this.children.flatMap((c) => [c, ...c.all()]); }
    matches(sel) {
        const m = /^([a-z]+)?(?:\[([\w-]+)(?:="([^"]*)")?\])?(:checked)?$/.exec(sel);
        if (!m) throw new Error("selector " + sel);
        return (!m[1] || this.tagName === m[1].toUpperCase()) &&
            (!m[2] || (m[2] in this.attrs && (m[3] === undefined || this.getAttribute(m[2]) === m[3]))) &&
            (!m[4] || this.checked);
    }
    querySelectorAll(sel) { return this.all().filter((e) => e.matches(sel)); }
    querySelector(sel) { return this.querySelectorAll(sel)[0] || null; }
    closest(sel) {
        let e = this;
        while (e) {
            if (sel === "form.app-display-form" ? e.tagName === "FORM" : e.matches(sel)) return e;
            e = e.parent;
        }
        return null;
    }
}

function form({ mode = "auto", glyph = "chat", colour = "auto", name = "", logo = false, builtin = false } = {}) {
    const radio = (n, v, tile) =>
        new El("input", Object.assign({ name: n, value: v, checked: v === ({ icon_mode: mode, icon: glyph, color: colour })[n] },
            tile ? { "data-tile": tile } : {}));
    const attrs = { class: "group app-display-form", "data-letter": "S", "data-w-own": "Logo" };
    if (logo) attrs["data-logo"] = "/logo.png";
    if (builtin) Object.assign(attrs, { "data-auto-glyph": "sms", "data-auto-tile": "#347470" });
    return new El("form", attrs, [
        new El("span", { "data-preview-tile": "" }, [
            new El("img", { "data-preview-glyph": "", hidden: true }),
            new El("span", { "data-preview-text": "" }),
            ...(logo ? [new El("img", { "data-preview-logo": "", hidden: true })] : []),
        ]),
        new El("span", { "data-preview-label": "" }),
        new El("input", { name: "label", value: name, "data-app-label": "Signal" }),
        ...(builtin ? [] : [radio("icon_mode", "logo")]),
        radio("icon_mode", "auto"),
        radio("icon_mode", "symbol"),
        new El("fieldset", { "data-symbols": "" }, [radio("icon", "chat"), radio("icon", "mail")]),
        new El("fieldset", { "data-colours": "" }, [radio("color", "auto", "#606B80"), radio("color", "sky", "#496C9C")]),
    ]);
}
const part = (f, attr) => f.querySelector("[" + attr + "]");

test("Symbol: the chosen glyph on the chosen colour, and the symbols' --pick", () => {
    const f = form({ mode: "symbol", glyph: "mail", colour: "sky" });
    update(f);
    const glyph = part(f, "data-preview-glyph");
    assert.strictEqual(glyph.getAttribute("src"), "/static/app-icons/mail.svg");
    assert.strictEqual(glyph.hidden, false);
    assert.strictEqual(part(f, "data-preview-text").hidden, true);
    assert.strictEqual(part(f, "data-preview-tile").style.background, "#496C9C");
    assert.strictEqual(part(f, "data-symbols").style.get("--pick"), "#496C9C");
    // The app's tone previews as slate.
    const tone = form({ mode: "symbol", colour: "auto" });
    update(tone);
    assert.strictEqual(part(tone, "data-preview-tile").style.background, AUTO_PREVIEW);
});

test("Automatisk: the letter on the darker slate; a part of Vibb One its own look", () => {
    const f = form({ mode: "auto", glyph: "mail" });
    update(f);
    assert.strictEqual(part(f, "data-preview-glyph").hidden, true);
    const text = part(f, "data-preview-text");
    assert.strictEqual(text.hidden, false);
    assert.strictEqual(text.textContent, "S");
    assert.strictEqual(part(f, "data-preview-tile").style.background, OWN_PREVIEW);
    const messages = form({ mode: "auto", builtin: true });
    update(messages);
    assert.strictEqual(part(messages, "data-preview-glyph").getAttribute("src"), "/static/app-icons/sms.svg");
    assert.strictEqual(part(messages, "data-preview-tile").style.background, "#347470");
});

test("Logo: the real logo where there is one, else the word", () => {
    const f = form({ mode: "logo", logo: true });
    update(f);
    assert.strictEqual(part(f, "data-preview-logo").hidden, false);
    assert.strictEqual(part(f, "data-preview-text").hidden, true);
    const none = form({ mode: "logo" });
    update(none);
    assert.strictEqual(part(none, "data-preview-text").textContent, "Logo");
    assert.notStrictEqual(part(none, "data-preview-text").getAttribute("data-word"), null, "the smaller word");
    assert.strictEqual(part(none, "data-preview-text").hidden, false);
    // Back to Automatisk hides the logo again.
    const back = form({ mode: "auto", logo: true });
    update(back);
    assert.strictEqual(part(back, "data-preview-logo").hidden, true);
});

test("the name follows the field, else the app's own", () => {
    const f = form({ name: "  Chat " });
    update(f);
    assert.strictEqual(part(f, "data-preview-label").textContent, "Chat");
    const empty = form({ name: " " });
    update(empty);
    assert.strictEqual(part(empty, "data-preview-label").textContent, "Signal");
});

test("a change in the form redraws it; a swap redraws every form", () => {
    const listeners = {};
    const f = form({ mode: "auto" });
    const doc = {
        readyState: "complete",
        addEventListener: (type, fn) => { listeners[type] = fn; },
        querySelectorAll: () => [f],
    };
    install(doc);
    assert.strictEqual(part(f, "data-preview-text").textContent, "S", "drawn at once");
    f.querySelectorAll("input").forEach((i) => { if (i.attrs.name === "icon_mode") i.checked = i.value === "symbol"; });
    listeners.change({ target: f.querySelector('input[name="label"]') });
    assert.strictEqual(part(f, "data-preview-glyph").getAttribute("src"), "/static/app-icons/chat.svg");
    f.querySelectorAll("input").forEach((i) => { if (i.attrs.name === "icon_mode") i.checked = i.value === "auto"; });
    listeners["handy:swapped"]();
    assert.strictEqual(part(f, "data-preview-glyph").hidden, true);
});
