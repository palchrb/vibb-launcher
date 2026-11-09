// Auto-saving controls (design 31 §5.2, L7). A control marked `data-autosave` saves its form when it
// changes, through form.submit() - which static/scroll-restore.js (loaded un-deferred before this)
// hooks, so the page that comes back keeps its position (a save never jumps to the top).
//
// A <select> changed from the keyboard (arrow keys, type-ahead on a closed select) does not save on
// every key: it saves on Enter or when it loses focus. A choice confirmed with Enter in an open
// dropdown (Enter's keydown comes before its change) saves at once. Changed with a pointer (or a
// phone's own picker), it saves at once. Without JS every such control has a <noscript> Save
// button. Loaded un-deferred from the head partial (it only listens on the document), so a control
// changed while the page is still loading saves too. ES5. Tested with node (jstest/autosave.test.js).
//
// A radio group (design 77's segmented control) is the same (code QA L6 of design 77 step 4): the
// arrow keys move the focus and the check through the group, firing a change on each radio, so a
// radio checked from the keyboard saves on Enter or Space, or when the focus leaves the group - never
// a state passed on the way. Checked with a pointer, it saves at once.
(function (root) {
    "use strict";

    var ARROWS = { ArrowUp: 1, ArrowDown: 1, ArrowLeft: 1, ArrowRight: 1, Up: 1, Down: 1, Left: 1, Right: 1 };

    function isAutosave(el) {
        return !!(el && el.form && el.hasAttribute && el.hasAttribute("data-autosave"));
    }

    function isSelect(el) {
        return String(el.tagName).toUpperCase() === "SELECT";
    }

    function isRadio(el) {
        return !!el && String(el.tagName).toUpperCase() === "INPUT" && String(el.type).toLowerCase() === "radio";
    }

    function sameGroup(a, b) {
        return !!a && !!b && isRadio(a) && isRadio(b) && a.form === b.form && a.name === b.name;
    }

    function install(doc) {
        // The select a key went to since the last pointer press on it, the one whose change waits
        // for Enter or blur, and the one where Enter came before its change (an open dropdown).
        var keyed = null;
        var pending = null;
        var entered = null;
        // A radio group the arrow keys moved in since the last pointer press (any radio of it), and
        // the radio its keyboard change left checked, waiting for Enter, Space or the focus to leave.
        var keyedRadio = null;
        var pendingRadio = null;

        function save(el) {
            if (pending === el) pending = null;
            if (pendingRadio === el) pendingRadio = null;
            el.form.submit();
        }

        doc.addEventListener("keydown", function (e) {
            var el = e.target;
            if (!isAutosave(el)) return;
            if (isRadio(el)) {
                if (ARROWS[e.key]) {
                    keyedRadio = el;
                } else if ((e.key === "Enter" || e.key === " " || e.key === "Spacebar") && sameGroup(pendingRadio, el)) {
                    if (e.preventDefault) e.preventDefault();
                    save(pendingRadio);
                }
                return;
            }
            if (!isSelect(el)) return;
            if (e.key === "Enter") {
                if (pending === el) {
                    if (e.preventDefault) e.preventDefault();
                    save(el);
                } else if (keyed === el) {
                    entered = el;
                }
                return;
            }
            if (e.key === "Tab") return;
            keyed = el;
            entered = null;
        }, true);

        function pointer(e) {
            if (keyed === e.target) keyed = null;
            // A press anywhere (the radio's label is the usual target) ends the keyboard's turn.
            keyedRadio = null;
        }
        doc.addEventListener("mousedown", pointer, true);
        doc.addEventListener("touchstart", pointer, true);

        doc.addEventListener("change", function (e) {
            var el = e.target;
            if (!isAutosave(el)) return;
            if (isRadio(el) && sameGroup(keyedRadio, el)) {
                pendingRadio = el;
                return;
            }
            if (isSelect(el) && keyed === el && entered !== el) {
                pending = el;
                return;
            }
            if (entered === el) entered = null;
            save(el);
        }, true);

        doc.addEventListener("focusout", function (e) {
            var el = e.target;
            if (isRadio(el)) {
                // Moving within the group keeps the wait; leaving it saves the radio left checked.
                if (sameGroup(e.relatedTarget, el)) return;
                if (sameGroup(keyedRadio, el)) keyedRadio = null;
                if (sameGroup(pendingRadio, el)) save(pendingRadio);
                return;
            }
            if (keyed === el) keyed = null;
            if (entered === el) entered = null;
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
