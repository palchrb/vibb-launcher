// How the last focus came (the user's pick A, 2026-10-25): a tapped or clicked text field shows its
// own tinted border and no ring, a field reached with the keyboard keeps the 2 px ring. A browser
// counts every focus of a text field as :focus-visible - a tap too -, so CSS alone can't tell them
// apart: from a press (mouse, touch, pen) until the keyboard moves focus or a choice, <html> carries
// `pointer-focus`, which style.css's field rules read.
//
// What leaves pointer mode (code QA Q-2): Tab and Shift+Tab always; the navigation keys (arrows,
// Home, End, Page Up/Down, Space, Enter) when the focus is not in a field one types into - so a
// parent who tapped a radio or a select and goes on with the arrow keys gets the ring back, while
// typing in a tapped text field (an on-screen keyboard sends keys too) and moving its caret keep the
// calm border. Without this script the ring shows as before. Loaded un-deferred from the head
// partial (it only listens on the document). ES5. Tested with node (jstest/focus-mode.test.js).
(function (root) {
    "use strict";
    var CLASS = "pointer-focus";
    var NAVIGATION = {
        ArrowUp: 1, ArrowDown: 1, ArrowLeft: 1, ArrowRight: 1, Up: 1, Down: 1, Left: 1, Right: 1,
        Home: 1, End: 1, PageUp: 1, PageDown: 1, " ": 1, Spacebar: 1, Enter: 1
    };
    // The inputs one types into (their arrow keys move the caret).
    var TEXT_TYPES = {
        text: 1, search: 1, tel: 1, url: 1, email: 1, password: 1, number: 1, time: 1, date: 1,
        "datetime-local": 1, month: 1, week: 1
    };

    function typesInto(el) {
        if (!el || !el.tagName) return false;
        var tag = String(el.tagName).toUpperCase();
        if (tag === "TEXTAREA") return true;
        if (el.isContentEditable) return true;
        if (tag !== "INPUT") return false;
        var type = String(el.type || (el.getAttribute && el.getAttribute("type")) || "text").toLowerCase();
        return TEXT_TYPES[type] === 1;
    }

    /** Whether a keydown leaves pointer mode, given the element that has the focus. */
    function leaves(e, focused) {
        if (e.key === "Tab" || e.keyCode === 9) return true;
        if (NAVIGATION[e.key] !== 1) return false;
        return !typesInto(focused);
    }

    function install(doc) {
        var html = doc.documentElement;
        if (!html || !html.classList) return;
        function pressed() {
            html.classList.add(CLASS);
        }
        doc.addEventListener("mousedown", pressed, true);
        doc.addEventListener("touchstart", pressed, { capture: true, passive: true });
        doc.addEventListener("pointerdown", pressed, true);
        doc.addEventListener("keydown", function (e) {
            if (leaves(e, doc.activeElement)) html.classList.remove(CLASS);
        }, true);
    }

    var api = { install: install, leaves: leaves, CLASS: CLASS };
    if (typeof module !== "undefined" && module.exports) {
        module.exports = api;
    } else if (root.document) {
        install(root.document);
    }
})(this);
