// The Calls page's contact forms (design 80 D10). It changes nothing that is saved:
// - Every contact form (and Add Contact): its hidden `more` follows that form's "Avansert" (`details[data-adv]`) -
//   "1" while open, empty while closed - so any save from the form comes back as it was (D9: the server returns to
//   `&more=<id>` and draws the Avansert open). Without JS `more` keeps the value it was drawn with.
// - Add Contact only (no autosave there; H2): after any change to a channel's switches it makes the form agree with
//   itself. Flipping the two-way switch (`calls`/`texts`) sets both one-way switches of its pair (`data-pair`) to its
//   value; then `calls_shown`/`texts_shown` is set to the pair's class (`on`, `off`, `custom`), and the main row shows
//   the two-way switch with the pair's value when the pair is equal, "Tilpasset" (`data-custom`) when it isn't - the
//   switch is only hidden, never removed. So every Add Contact post has `*_shown` equal to the pair's class, and the
//   server's `calls_form::both_ways` resolves it to the pair as posted.
// - Not on the contact edit forms: each autosave there navigates at once with one change, which the server resolves;
//   a mirror would race the submit.
// ES5 (Chrome/WebView 80, iOS Safari 14). Run its tests: node --test jstest/contact-form.test.js
(function (root) {
    "use strict";

    var PAIRS = { calls: ["inbound", "outbound"], texts: ["sms_in", "sms_out"] };

    function field(form, name) {
        return form.querySelector("[name=\"" + name + "\"]");
    }

    /** Puts the form's `more` in step with its Avansert. */
    function followAdvanced(form) {
        var adv = form.querySelector("details[data-adv]");
        var more = form.querySelector("input[data-more]");
        if (!adv || !more) return;
        more.value = adv.hasAttribute("open") ? "1" : "";
    }

    /** The class of a channel's pair in Add Contact: "on", "off" or "custom". */
    function classOf(form, channel) {
        var pair = PAIRS[channel];
        var a = field(form, pair[0]);
        var b = field(form, pair[1]);
        if (!a || !b) return null;
        if (a.checked && b.checked) return "on";
        if (!a.checked && !b.checked) return "off";
        return "custom";
    }

    /** Draws a channel's main row and `*_shown` from its pair. */
    function agree(form, channel) {
        var kind = classOf(form, channel);
        if (!kind) return;
        var shown = form.querySelector("[data-shown=\"" + channel + "\"]");
        var twoWay = form.querySelector("[data-two-way=\"" + channel + "\"]");
        var custom = form.querySelector("[data-custom=\"" + channel + "\"]");
        var main = field(form, channel);
        if (shown) shown.value = kind;
        if (main && kind !== "custom") main.checked = kind === "on";
        if (twoWay) {
            if (kind === "custom") twoWay.setAttribute("hidden", "");
            else twoWay.removeAttribute("hidden");
        }
        if (custom) {
            if (kind === "custom") custom.removeAttribute("hidden");
            else custom.setAttribute("hidden", "");
        }
        var value = form.querySelector("[data-adv-value]");
        if (value) {
            var any = classOf(form, "calls") === "custom" || classOf(form, "texts") === "custom";
            if (any) value.removeAttribute("hidden");
            else value.setAttribute("hidden", "");
        }
    }

    /** Add Contact: the two-way switches and the one-way pairs kept in agreement after a change of `el`. */
    function mirror(form, e) {
        (function () {
            var el = e && e.target;
            if (!el || !el.name) return;
            var channel = null;
            if (PAIRS[el.name]) {
                channel = el.name;
                var pair = PAIRS[channel];
                for (var i = 0; i < pair.length; i++) {
                    var one = field(form, pair[i]);
                    if (one) one.checked = !!el.checked;
                }
            } else {
                for (var key in PAIRS) {
                    if (PAIRS.hasOwnProperty(key) && (PAIRS[key][0] === el.name || PAIRS[key][1] === el.name)) {
                        channel = key;
                    }
                }
            }
            if (channel) agree(form, channel);
        })();
    }

    var FORMS = "form[data-contact-form], form[data-add-contact]";

    function formOf(el) {
        for (var n = el; n; n = n.parentNode) {
            if (String(n.nodeName || "").toUpperCase() === "FORM") return n;
        }
        return null;
    }

    // The listeners are the document's (design 103): a refresh in place (static/swap.js) may bring new forms, and
    // brings the server's `more` and `*_shown` back - both are written again on `handy:swapped`.
    function install(doc) {
        function all() {
            var forms = doc.querySelectorAll(FORMS);
            for (var i = 0; i < forms.length; i++) {
                followAdvanced(forms[i]);
                if (forms[i].hasAttribute("data-add-contact")) {
                    agree(forms[i], "calls");
                    agree(forms[i], "texts");
                }
            }
        }
        // `toggle` doesn't bubble: captured.
        doc.addEventListener("toggle", function (e) {
            var adv = e && e.target;
            if (!adv || !adv.hasAttribute || !adv.hasAttribute("data-adv")) return;
            var form = formOf(adv);
            if (form && (form.hasAttribute("data-contact-form") || form.hasAttribute("data-add-contact"))) followAdvanced(form);
        }, true);
        doc.addEventListener("change", function (e) {
            var form = formOf(e && e.target);
            if (form && form.hasAttribute && form.hasAttribute("data-add-contact")) mirror(form, e);
        });
        doc.addEventListener("handy:swapped", all);
        var forms = doc.querySelectorAll(FORMS);
        for (var i = 0; i < forms.length; i++) followAdvanced(forms[i]);
    }

    var api = { install: install, classOf: classOf, agree: agree };
    if (typeof module !== "undefined" && module.exports) {
        module.exports = api;
    } else if (root.document) {
        install(root.document);
    }
})(this);
