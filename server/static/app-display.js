// The "Name and icon" forms' live preview (design 14, templates/partials/app_display_form.html):
// the tile's colour (the phone's resolved tile colour; "the app's own" previews as grey), the
// glyph and the name follow the fields as they change. The form works without this.
(function () {
    "use strict";

    function update(form) {
        var tile = form.querySelector("[data-preview-tile]");
        var glyph = form.querySelector("[data-preview-glyph]");
        var own = form.querySelector("[data-preview-own]");
        var label = form.querySelector("[data-preview-label]");
        var input = form.querySelector('input[name="label"]');
        var icon = form.querySelector('input[name="icon"]:checked');
        var color = form.querySelector('input[name="color"]:checked');
        if (!tile || !glyph || !own || !label) return;
        tile.style.background = (color && color.getAttribute("data-tile")) || "#868E96";
        var key = icon ? icon.value : "";
        if (key) {
            glyph.src = "/static/app-icons/" + key + ".svg";
            glyph.hidden = false;
            own.hidden = true;
        } else {
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

    document.addEventListener("input", onEvent);
    document.addEventListener("change", onEvent);
})();
