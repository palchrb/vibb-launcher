// Vises som's live preview (design 127 §2.4, templates/partials/app_display_group.html; the icon's three modes of
// design 34 P1): the Home tile beside the name follows the fields as they change - the name, Logo · Automatisk ·
// Symbol, the symbol and its colour. The server draws the saved state and every control saves on change
// (autosave.js); this only answers the tap at once. Which rows show (the symbols and the colours under Symbol only)
// is CSS's, from the radios before them - no script needed.
// - Symbol: the chosen glyph on the chosen colour's tile (`data-tile`; "the app's tone" previews as slate, a part of
//   Vibb One's own tile, the form's `data-auto-tile`); the symbols' `--pick` is that colour too.
// - Automatisk: the app's letter on the darker slate (`data-letter`); a part of Vibb One (design 82 D9: Meldinger) its
//   own glyph on its own tile (`data-auto-glyph`, `data-auto-tile`).
// - Logo: the app's real logo where a phone has sent it (design 87, the form's `data-logo`), else the word "Logo"
//   (`data-w-own`) on the slate.
// Every preview is drawn again after a refresh in place (`handy:swapped`, design 103). ES5. Tested with node
// (jstest/app-display.test.js).
(function (root) {
    "use strict";

    // app_display::AUTO_PREVIEW and app_display::OWN_PREVIEW.
    var AUTO_PREVIEW = "#606B80";
    var OWN_PREVIEW = "#5C6370";

    // A word of the form's own (`data-w-<name>`, design 70 §5.3): a missing one shows its name.
    function w(form, name) {
        return form.getAttribute("data-w-" + name) || name;
    }

    function checked(form, name) {
        return form.querySelector('input[name="' + name + '"]:checked');
    }

    function update(form) {
        var tile = form.querySelector("[data-preview-tile]");
        var glyph = form.querySelector("[data-preview-glyph]");
        var text = form.querySelector("[data-preview-text]");
        var logo = form.querySelector("[data-preview-logo]");
        var label = form.querySelector("[data-preview-label]");
        if (!tile || !glyph || !text || !label) return;
        var modeInput = checked(form, "icon_mode");
        var mode = modeInput ? modeInput.value : "auto";
        var icon = checked(form, "icon");
        var colour = checked(form, "color");
        var autoGlyph = form.getAttribute("data-auto-glyph");
        var autoTile = form.getAttribute("data-auto-tile");
        var pick = (colour && colour.value !== "auto" && colour.getAttribute("data-tile")) || autoTile || AUTO_PREVIEW;
        var symbols = form.querySelector("[data-symbols]");
        if (symbols && symbols.style && symbols.style.setProperty) symbols.style.setProperty("--pick", pick);

        var showLogo = mode === "logo" && !!logo && !!form.getAttribute("data-logo");
        var key = null;
        if (mode === "symbol" && icon) {
            key = icon.value;
            tile.style.background = pick;
        } else if (mode === "auto" && autoGlyph) {
            key = autoGlyph;
            tile.style.background = autoTile || AUTO_PREVIEW;
        } else {
            // Without a glyph the tile only names the state: the darker slate keeps its white text at 4.5:1.
            tile.style.background = OWN_PREVIEW;
            text.textContent = mode === "logo" ? w(form, "own") : (form.getAttribute("data-letter") || "");
            if (mode === "logo") text.setAttribute("data-word", ""); else text.removeAttribute("data-word");
        }
        if (key) glyph.setAttribute("src", "/static/app-icons/" + key + ".svg");
        glyph.hidden = !key;
        text.hidden = !!key || showLogo;
        if (logo) logo.hidden = !showLogo;

        var input = form.querySelector('input[name="label"]');
        var name = input ? String(input.value).replace(/^\s+|\s+$/g, "") : "";
        label.textContent = name || (input ? input.getAttribute("data-app-label") : "") || "";
    }

    function formOf(el) {
        return el && el.closest ? el.closest("form.app-display-form") : null;
    }

    function install(doc) {
        function onEvent(e) {
            var form = formOf(e.target);
            if (form) update(form);
        }
        function updateAll() {
            var forms = doc.querySelectorAll("form.app-display-form");
            for (var i = 0; i < forms.length; i++) update(forms[i]);
        }
        doc.addEventListener("input", onEvent);
        doc.addEventListener("change", onEvent);
        // A refresh in place (design 103, static/swap.js) brings the server's tile back: the previews again.
        doc.addEventListener("handy:swapped", updateAll);
        if (doc.readyState === "loading") {
            doc.addEventListener("DOMContentLoaded", updateAll);
        } else {
            updateAll();
        }
    }

    var api = { install: install, update: update, AUTO_PREVIEW: AUTO_PREVIEW, OWN_PREVIEW: OWN_PREVIEW };
    if (typeof module !== "undefined" && module.exports) {
        module.exports = api;
    } else if (root.document) {
        install(root.document);
    }
})(this);
