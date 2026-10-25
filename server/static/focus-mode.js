// How the last focus came (the user's pick A, 2026-10-25): a tapped or clicked text field shows its
// own tinted border and no ring, a field reached with the keyboard keeps the 2 px ring. A browser
// counts every focus of a text field as :focus-visible - a tap too -, so CSS alone can't tell them
// apart: from a press (mouse, touch, pen) until the next Tab, <html> carries `pointer-focus`, which
// style.css's field rule reads. Typing in the tapped field keeps it (only Tab moves focus by key).
// Without this script the ring shows as before. Loaded un-deferred from the head partial (it only
// listens on the document). ES5.
(function (root) {
    "use strict";
    var CLASS = "pointer-focus";

    function install(doc) {
        var html = doc.documentElement;
        if (!html || !html.classList) return;
        function pressed() {
            html.classList.add(CLASS);
        }
        doc.addEventListener("mousedown", pressed, true);
        doc.addEventListener("touchstart", pressed, true);
        doc.addEventListener("pointerdown", pressed, true);
        doc.addEventListener("keydown", function (e) {
            if (e.key === "Tab" || e.keyCode === 9) html.classList.remove(CLASS);
        }, true);
    }

    if (root.document) install(root.document);
})(this);
