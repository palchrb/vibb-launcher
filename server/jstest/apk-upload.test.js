// Behaviour of static/apk-upload.js (upstream review 2026-11-20: a file over the upload limit is stopped at the field).
// Run: node --test jstest/apk-upload.test.js
"use strict";
const test = require("node:test");
const assert = require("node:assert");
const { install, check } = require("../static/apk-upload.js");

function field(size) {
    const attrs = { "data-max-bytes": "2000000000", "data-w-too-big": "Filen er over 2 GB" };
    const input = {
        files: size === null ? [] : [{ size }],
        validity: "",
        reported: 0,
        getAttribute: (name) => (name in attrs ? attrs[name] : null),
        setCustomValidity(text) {
            this.validity = text;
        },
        reportValidity() {
            this.reported++;
        },
        matches: (sel) => sel === "input[type=file][data-max-bytes]",
    };
    return input;
}

test("a file over the limit gets the server's words; one at the limit or none is valid", () => {
    const over = field(2_000_000_001);
    assert.strictEqual(check(over), true);
    assert.strictEqual(over.validity, "Filen er over 2 GB");
    for (const size of [2_000_000_000, 12, null]) {
        const input = field(size);
        input.validity = "stale";
        assert.strictEqual(check(input), false);
        assert.strictEqual(input.validity, "", String(size));
    }
});

test("choosing a file checks it and shows the message at once only when it is over", () => {
    const listeners = {};
    install({ document: { addEventListener: (type, fn) => (listeners[type] = fn) } });
    const over = field(3_000_000_000);
    listeners.change({ target: over });
    assert.strictEqual(over.reported, 1);
    const fine = field(5);
    listeners.change({ target: fine });
    assert.strictEqual(fine.reported, 0);
    // Another field's change is not this script's.
    const other = { matches: () => false };
    listeners.change({ target: other });
});
