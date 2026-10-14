// The "Name and icon" forms' live preview (design 14, templates/partials/app_display_form.html; the
// three icon states of design 34 P1): the tile's colour (the phone's tone for a chosen colour; "the
// app's tone" previews as slate), the glyph or the state's words ("Vibb style", "Its own logo") and
// the name follow the fields as they change, and the colours are off while "The app's own logo" is
// chosen (the logo has no tile; a disabled fieldset posts nothing, which the server reads as auto).
// The form works without this.
(function () {
    "use strict";

    // app_display::AUTO_PREVIEW and app_display::OWN_PREVIEW.
    var AUTO_PREVIEW = "#606B80";
    var OWN_PREVIEW = "#5C6370";

    function update(form) {
        var tile = form.querySelector("[data-preview-tile]");
        var glyph = form.querySelector("[data-preview-glyph]");
        var own = form.querySelector("[data-preview-own]");
        var label = form.querySelector("[data-preview-label]");
        var colors = form.querySelector("[data-colors]");
        var input = form.querySelector('input[name="label"]');
        var icon = form.querySelector('input[name="icon"]:checked');
        var color = form.querySelector('input[name="color"]:checked');
        if (!tile || !glyph || !own || !label) return;
        var key = icon ? icon.value : "";
        var logo = key === "own";
        if (colors) colors.disabled = logo;
        if (key && !logo) {
            tile.style.background = (color && color.getAttribute("data-tile")) || AUTO_PREVIEW;
            glyph.src = "/static/app-icons/" + key + ".svg";
            glyph.hidden = false;
            own.hidden = true;
        } else {
            // Without a glyph the tile only names the state: the darker grey keeps that text at 4.5:1.
            tile.style.background = OWN_PREVIEW;
            own.textContent = logo ? "Its own logo" : "Vibb style";
            glyph.hidden = true;
            own.hidden = false;
        }
        var text = input ? input.value.trim() : "";
        label.textContent = text || (input ? input.getAttribute("data-app-label") : "") || "";
    }

    function onEvent(e) {
        var form = e.target && e.target.closest ? e.target.closest("form.app-display-form") : null;
        if (form) update(form);
    }

    function updateAll() {
        var forms = document.querySelectorAll("form.app-display-form");
        for (var i = 0; i < forms.length; i++) update(forms[i]);
    }

    document.addEventListener("input", onEvent);
    document.addEventListener("change", onEvent);
    if (document.readyState === "loading") {
        document.addEventListener("DOMContentLoaded", updateAll);
    } else {
        updateAll();
    }
})();
