// Auto-saving controls (design 31 §5.2, L7). A control marked `data-autosave` saves its form when it
// changes, through form.submit() - which static/scroll-restore.js (loaded un-deferred before this)
// hooks, so the page that comes back keeps its position (a save never jumps to the top).
//
// A <select> changed from the keyboard (arrow keys, type-ahead on a closed select) does not save on
// every key: it saves on Enter or when it loses focus. Changed with a pointer (or a phone's own
// picker), it saves at once. Without JS every such control has a <noscript> Save button.
// Loaded deferred from the head partial. ES5. Tested with node (jstest/autosave.test.js).
(function (root) {
    "use strict";

    function isAutosave(el) {
        return !!(el && el.form && el.hasAttribute && el.hasAttribute("data-autosave"));
    }

    function isSelect(el) {
        return String(el.tagName).toUpperCase() === "SELECT";
    }

    function install(doc) {
        // The select a key went to since the last pointer press on it, and the one whose change
        // waits for Enter or blur.
        var keyed = null;
        var pending = null;

        function save(el) {
            if (pending === el) pending = null;
            el.form.submit();
        }

        doc.addEventListener("keydown", function (e) {
            var el = e.target;
            if (!isAutosave(el) || !isSelect(el)) return;
            if (e.key === "Enter") {
                if (pending === el) {
                    if (e.preventDefault) e.preventDefault();
                    save(el);
                }
                return;
            }
            if (e.key === "Tab") return;
            keyed = el;
        }, true);

        function pointer(e) {
            if (keyed === e.target) keyed = null;
        }
        doc.addEventListener("mousedown", pointer, true);
        doc.addEventListener("touchstart", pointer, true);

        doc.addEventListener("change", function (e) {
            var el = e.target;
            if (!isAutosave(el)) return;
            if (isSelect(el) && keyed === el) {
                pending = el;
                return;
            }
            save(el);
        }, true);

        doc.addEventListener("focusout", function (e) {
            var el = e.target;
            if (keyed === el) keyed = null;
            if (pending === el) save(el);
        }, true);
    }

    var api = { install: install };
    if (typeof module !== "undefined" && module.exports) {
        module.exports = api;
    } else if (root.document) {
        install(root.document);
    }
})(this);
