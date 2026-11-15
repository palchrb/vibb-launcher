// A confirm's Cancel (PLAN "PWA small things", 2026-11-01): every `<details class="confirm">`'s form ends with
// `[data-confirm-cancel]` (partials/confirm_cancel.html), hidden without JS. This shows them and, on a click, closes
// the confirm it sits in and moves the focus to its summary, so the page stays where it is. One listener on the
// document; nothing is posted. Deferred from the head partial; its load pass runs again on `handy:swapped` (design 103).
// ES5. Tests: jstest/confirm.test.js.
(function (root) {
    "use strict";

    function install(win) {
        var doc = win.document;
        function show() {
            var buttons = doc.querySelectorAll("[data-confirm-cancel]");
            for (var i = 0; i < buttons.length; i++) buttons[i].hidden = false;
        }
        show();
        // A refresh in place (design 103, static/swap.js) brings the server's `hidden` back: shown again.
        doc.addEventListener("handy:swapped", show);
        // Whether the last click was a key's (Enter or Space on a summary has `detail` 0): only then the sheet's first
        // action takes the focus when it opens - after a tap a script focus would draw the pressed look on it.
        var byKey = false;
        doc.addEventListener("click", function (e) {
            byKey = e.detail === 0;
            var button = e.target && e.target.closest ? e.target.closest("[data-confirm-cancel]") : null;
            if (!button) return;
            var details = button.closest("details");
            if (!details) return;
            e.preventDefault();
            details.open = false;
            var summary = details.querySelector("summary");
            if (summary && summary.focus) summary.focus({ preventScroll: true });
        });

        // Design 101 (the phone's picture sheet, `details.pic-menu`): it reads as a sheet over a dim, so Escape closes it
        // and moves the focus back to the picture, a focus that leaves the panel closes it too (a menu's way; it is not
        // a modal and keeps role="group"), and opening it by keyboard focuses its first action. `toggle` does not bubble: capture.
        function menu(node) {
            return node && node.closest ? node.closest("details.pic-menu") : null;
        }
        function close(details) {
            details.open = false;
            var summary = details.querySelector("summary");
            if (summary && summary.focus) summary.focus({ preventScroll: true });
        }
        doc.addEventListener("keydown", function (e) {
            if (e.key !== "Escape" && e.key !== "Esc") return;
            var details = menu(e.target);
            if (!details || !details.open) return;
            e.preventDefault();
            close(details);
        });
        doc.addEventListener("focusout", function (e) {
            var details = menu(e.target);
            if (!details || !details.open) return;
            var next = e.relatedTarget;
            // No related target (a tap, a blur of the window): leave it to the tap's own handler.
            if (!next || (details.contains && details.contains(next))) return;
            details.open = false;
        });
        doc.addEventListener(
            "toggle",
            function (e) {
                var details = e.target;
                if (!details || !details.matches || !details.matches("details.pic-menu") || !details.open || !byKey) return;
                var first = details.querySelector(".pic-file, .pic-action");
                if (first && first.focus) first.focus({ preventScroll: true });
            },
            true
        );
    }

    if (typeof module !== "undefined" && module.exports) {
        module.exports = { install: install };
    } else if (root.document) {
        install(root);
    }
})(this);
