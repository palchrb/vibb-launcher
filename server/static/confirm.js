// A confirm's Cancel (PLAN "PWA small things", 2026-11-01): every `<details class="confirm">`'s form ends with
// `[data-confirm-cancel]` (partials/confirm_cancel.html), hidden without JS. This shows them and, on a click, closes
// the confirm it sits in and moves the focus to its summary, so the page stays where it is. One listener on the
// document; nothing is posted. Deferred from the head partial. ES5. Tests: jstest/confirm.test.js.
(function (root) {
    "use strict";

    function install(win) {
        var doc = win.document;
        var buttons = doc.querySelectorAll("[data-confirm-cancel]");
        for (var i = 0; i < buttons.length; i++) buttons[i].hidden = false;
        doc.addEventListener("click", function (e) {
            var button = e.target && e.target.closest ? e.target.closest("[data-confirm-cancel]") : null;
            if (!button) return;
            var details = button.closest("details");
            if (!details) return;
            e.preventDefault();
            details.open = false;
            var summary = details.querySelector("summary");
            if (summary && summary.focus) summary.focus({ preventScroll: true });
        });
    }

    if (typeof module !== "undefined" && module.exports) {
        module.exports = { install: install };
    } else if (root.document) {
        install(root);
    }
})(this);
