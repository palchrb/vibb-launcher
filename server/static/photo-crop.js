// A contact photo's crop (Device › Calls, templates/device_calls.html): on Home a contact is a 76 dp circle, and
// in a phone photo the face is small, so the parent picks the part the circle shows (the user, 2026-11-03).
// Choosing a file for a contact opens a dialog instead of uploading at once: the photo in a square frame with the
// phone's circle over it (the phone's circle is the square's inscribed one, `centerCrop`); a drag moves it, a pinch
// or the slider (or the wheel, + and -) zooms, the arrow keys move it. Zoom 1 is "cover": the square spans the
// photo's shorter side, so the circle never shows anything outside the photo, and it can't go below that. Save
// draws the chosen square (at most the server's `photos::MAX_SIDE`, `data-side`; shrunk by halves with high-quality
// smoothing, so it doesn't alias) on white, encodes it as JPEG, puts that file into the form's own file input
// (DataTransfer) and submits the form - the same route and the same answer as without this script: the redirect
// to the contact's row, kept on the screen where it was by static/scroll-restore.js's hooked form.submit() (a save
// never jumps to the top), or the 400 page with the reason under the photo. Cancel empties the input and uploads
// nothing. "Adjust crop" (`data-photo-adjust`, shown only here) opens the stored square the same way: a refinement
// of it, not a re-crop of the original (the server keeps only the square), so it can only zoom in, and only while
// the chosen square stays at least MIN_ADJUST_SIDE px - the phone's circle at 3x - so adjusting never makes the
// photo soft on the phone.
//
// The picture: decoded by an <img> (with decode(), off the main thread) from a blob: URL (the admin CSP's img-src
// allows blob:), which applies the EXIF orientation in Chrome 81+, Firefox 77+ and Safari 13.1+ (iOS 13.4 is when
// drawImage began to draw photos upright), then drawn once into a working canvas of at most WORK_PIXELS, so a 10 MB
// photo doesn't stall every move. createImageBitmap's `imageOrientation` isn't used: for a Blob in WebKit it is not
// documented to follow EXIF. A file over the server's limit (`data-max-bytes`) or one the browser can't decode goes
// up as it is, and the server says why. Without JS, or without canvas.toBlob or a settable file input, nothing here
// runs and the input autosaves as before (its `data-autosave`, which this removes only where it takes over).
//
// The dialog is the page's `[data-crop-dialog]`, its words the template's. While it is open the page behind is
// `inert`; Tab and Shift-Tab go round the dialog's controls, Escape cancels, a press on the veil keeps the focus in
// the dialog, the slider is labelled. ES5. Run its tests: node --test jstest/photo-crop.test.js
(function (root) {
    "use strict";

    // How far in the slider and a pinch go: a quarter of the shorter side across.
    var MAX_ZOOM = 4;
    // The least side of the square an adjust may choose: the 76 dp circle on a 3x phone (228 px).
    var MIN_ADJUST_SIDE = 230;
    // The working copy's bounds: its pixels, and its longer side (the server refuses more than 8000 px anyway).
    var WORK_PIXELS = 8000000;
    var WORK_LONG = 8192;
    // An arrow key's move in CSS px.
    var STEP = 10;
    var QUALITY = 0.95;
    var JPEG = "image/jpeg";

    function clamp(n, lo, hi) {
        return n < lo ? lo : (n > hi ? hi : n);
    }

    /**
     * `view` ({zoom, cx, cy}: the zoom over cover and the square's centre in the picture's pixels) made valid
     * for a `w`×`h` picture: the zoom within [1, maxZoom], the centre where the square stays inside it.
     */
    function clampView(view, w, h, maxZoom) {
        var zoom = Number(view.zoom);
        if (!isFinite(zoom)) zoom = 1;
        zoom = clamp(zoom, 1, maxZoom || MAX_ZOOM);
        var half = Math.min(w, h) / zoom / 2;
        var cx = isFinite(view.cx) ? view.cx : w / 2;
        var cy = isFinite(view.cy) ? view.cy : h / 2;
        return { zoom: zoom, cx: clamp(cx, half, w - half), cy: clamp(cy, half, h - half) };
    }

    /** The first view: cover, centred - what the server's own centre crop gives. */
    function centred(w, h) {
        return { zoom: 1, cx: w / 2, cy: h / 2 };
    }

    /** The square the circle's frame shows: {x, y, side} in the picture's pixels, always inside it. */
    function sourceRect(view, w, h) {
        var v = clampView(view, w, h, Infinity);
        var side = Math.min(w, h) / v.zoom;
        return { x: v.cx - side / 2, y: v.cy - side / 2, side: side };
    }

    /** The view after a drag of (dx, dy) CSS px on a frame `frame` px wide: the picture follows the finger. */
    function pan(view, dx, dy, frame, w, h, maxZoom) {
        var v = clampView(view, w, h, maxZoom);
        var k = Math.min(w, h) / v.zoom / frame;
        return clampView({ zoom: v.zoom, cx: v.cx - dx * k, cy: v.cy - dy * k }, w, h, maxZoom);
    }

    /**
     * The view at `zoom`, the picture's point under (px, py) - CSS px from the frame's centre - staying there
     * (a pinch's midpoint; the slider's is the centre), as far as the clamp allows.
     */
    function zoomAt(view, zoom, px, py, frame, w, h, maxZoom) {
        var v = clampView(view, w, h, maxZoom);
        var short = Math.min(w, h);
        var before = short / v.zoom / frame;
        var ix = v.cx + px * before;
        var iy = v.cy + py * before;
        var z = clampView({ zoom: zoom, cx: v.cx, cy: v.cy }, w, h, maxZoom).zoom;
        var after = short / z / frame;
        return clampView({ zoom: z, cx: ix - px * after, cy: iy - py * after }, w, h, maxZoom);
    }

    /**
     * A `w`×`h` picture's size shrunk (never enlarged, the shape kept) to at most `pixels` pixels and `long` on its
     * longer side - by area, so a banner keeps its short side.
     */
    function fitWithin(w, h, pixels, long) {
        var s = Math.min(1, Math.sqrt(pixels / (w * h)), long / Math.max(w, h));
        return { w: Math.max(1, Math.round(w * s)), h: Math.max(1, Math.round(h * s)) };
    }

    /** The uploaded square's side: the chosen square's own pixels, at most `max` (never enlarged). */
    function outputSide(side, max) {
        return Math.max(1, Math.min(max, Math.round(side)));
    }

    /**
     * How far an adjust may zoom into a `w`×`h` stored photo: the square stays at least MIN_ADJUST_SIDE px (a
     * smaller photo can't zoom at all), never past MAX_ZOOM.
     */
    function adjustZoom(w, h) {
        return Math.max(1, Math.min(MAX_ZOOM, Math.min(w, h) / MIN_ADJUST_SIDE));
    }

    /**
     * The sizes a `from`-px side goes through on its way to `to`: halves while it is more than twice the target,
     * then the target - so no single draw shrinks by more than 2x (a browser's one-step shrink skips pixels).
     */
    function halvings(from, to) {
        var sizes = [];
        var side = from;
        while (side > 2 * to) {
            side = Math.ceil(side / 2);
            sizes.push(side);
        }
        sizes.push(to);
        return sizes;
    }

    function smooth(ctx) {
        ctx.imageSmoothingEnabled = true;
        ctx.imageSmoothingQuality = "high";
        return ctx;
    }

    /**
     * A new `tw`×`th` canvas holding `source`'s (sx, sy, sw, sh) part, shrunk by halves (`halvings`, on the longer
     * side) on white.
     */
    function shrink(doc, source, sx, sy, sw, sh, tw, th) {
        var steps = halvings(Math.max(sw, sh), Math.max(tw, th));
        var from = source;
        var rect = [sx, sy, sw, sh];
        for (var i = 0; i < steps.length; i++) {
            var last = i === steps.length - 1;
            var k = steps[i] / Math.max(sw, sh);
            var cw = last ? tw : Math.max(1, Math.round(sw * k));
            var ch = last ? th : Math.max(1, Math.round(sh * k));
            var canvas = doc.createElement("canvas");
            canvas.width = cw;
            canvas.height = ch;
            var ctx = smooth(canvas.getContext("2d"));
            // JPEG has no alpha: a transparent PNG goes on white, as the server flattens it.
            ctx.fillStyle = "#FFFFFF";
            ctx.fillRect(0, 0, cw, ch);
            ctx.drawImage(from, rect[0], rect[1], rect[2], rect[3], 0, 0, cw, ch);
            if (from !== source) {
                from.width = 0;
                from.height = 0;
            }
            from = canvas;
            rect = [0, 0, cw, ch];
        }
        return from;
    }

    /** Can this browser crop and hand the result to the form: a 2D canvas with toBlob, and a settable file input. */
    function supported(win, doc) {
        try {
            var canvas = doc.createElement("canvas");
            if (!canvas.getContext || !canvas.getContext("2d") || typeof canvas.toBlob !== "function") return false;
            if (!win.URL || typeof win.URL.createObjectURL !== "function" || typeof win.Promise !== "function") return false;
            var transfer = new win.DataTransfer();
            transfer.items.add(new win.File([""], "probe.jpg", { type: JPEG }));
            var probe = doc.createElement("input");
            probe.type = "file";
            probe.files = transfer.files;
            return !!(probe.files && probe.files.length === 1);
        } catch (e) {
            return false;
        }
    }

    /** The picture at `src`, decoded (its EXIF orientation applied by the browser). */
    function loadImage(win, src) {
        return new win.Promise(function (resolve, reject) {
            var img = new win.Image();
            if (typeof img.decode === "function") {
                img.src = src;
                img.decode().then(function () { resolve(img); }, reject);
                return;
            }
            img.onload = function () { resolve(img); };
            img.onerror = reject;
            img.src = src;
        });
    }

    function install(doc, win) {
        win = win || root;
        var dialog = doc.querySelector("[data-crop-dialog]");
        if (!dialog || !supported(win, doc)) return null;
        var frame = dialog.querySelector("[data-crop-frame]");
        var preview = dialog.querySelector("[data-crop-canvas]");
        var slider = dialog.querySelector("[data-crop-zoom]");
        var cancelButton = dialog.querySelector("[data-crop-cancel]");
        var saveButton = dialog.querySelector("[data-crop-save]");
        var maxSide = Number(dialog.getAttribute("data-side")) || 512;
        var maxBytes = Number(dialog.getAttribute("data-max-bytes")) || 10 * 1024 * 1024;
        slider.min = "1";

        // The open crop: the input it fills, what had focus, whether it came from a picked file, the working
        // canvas and its size, the view, its zoom limit and the frame's width. `null` while closed.
        var session = null;
        var pointers = {};
        var queued = false;
        // Set from Save until the page goes: a change the file input fires for the cropped file starts nothing.
        var submitting = false;
        // The page behind the dialog, inert while it is open.
        var behind = [];

        function setInert(on) {
            if (on) {
                behind = [doc.querySelector("main"), doc.querySelector(".tab-bar")].filter(function (el) { return !!el; });
            }
            for (var i = 0; i < behind.length; i++) {
                if (on) {
                    behind[i].setAttribute("inert", "");
                } else {
                    behind[i].removeAttribute("inert");
                }
            }
            if (!on) behind = [];
        }

        function setBusy(busy) {
            frame.setAttribute("aria-busy", busy ? "true" : "false");
            slider.disabled = busy;
            saveButton.disabled = busy;
        }

        function draw() {
            queued = false;
            if (!session || !session.work) return;
            var r = sourceRect(session.view, session.w, session.h);
            var ctx = smooth(preview.getContext("2d"));
            ctx.drawImage(session.work, r.x, r.y, r.side, r.side, 0, 0, preview.width, preview.height);
        }

        function redraw() {
            if (queued) return;
            queued = true;
            if (typeof win.requestAnimationFrame === "function") {
                win.requestAnimationFrame(draw);
            } else {
                draw();
            }
        }

        function setView(view) {
            if (!session || !session.work) return;
            session.view = view;
            var zoom = Math.round(view.zoom * 100) / 100;
            slider.value = String(zoom);
            slider.setAttribute("aria-valuetext", Math.round(view.zoom * 100) + " %");
            redraw();
        }

        function close() {
            dialog.hidden = true;
            setInert(false);
            pointers = {};
            var s = session;
            session = null;
            if (s && s.work) {
                s.work.width = 0;
                s.work.height = 0;
            }
            return s;
        }

        function cancel() {
            var s = close();
            if (!s) return;
            if (s.picked) s.input.value = "";
            if (s.opener && s.opener.focus) s.opener.focus();
        }

        /** The upload as the page did it before this script: the input's own file, through the hooked submit. */
        function plainUpload(input) {
            submitting = true;
            input.form.submit();
        }

        function open(input, opener, src, picked, revoke) {
            session = { input: input, opener: opener, picked: picked, work: null, maxZoom: MAX_ZOOM };
            var mine = session;
            setBusy(true);
            slider.value = "1";
            setInert(true);
            dialog.hidden = false;
            frame.focus();
            loadImage(win, src).then(function (img) {
                if (revoke) revoke();
                if (session !== mine) return;
                var nw = img.naturalWidth || img.width;
                var nh = img.naturalHeight || img.height;
                var size = fitWithin(nw, nh, WORK_PIXELS, WORK_LONG);
                var work = shrink(doc, img, 0, 0, nw, nh, size.w, size.h);
                mine.work = work;
                mine.w = size.w;
                mine.h = size.h;
                // A stored square is all there is to adjust: zoom only as far as it stays sharp on the phone.
                mine.maxZoom = picked ? MAX_ZOOM : adjustZoom(size.w, size.h);
                slider.max = String(Math.floor(mine.maxZoom * 100) / 100);
                var box = frame.getBoundingClientRect();
                mine.frame = box.width || 280;
                var ratio = win.devicePixelRatio || 1;
                preview.width = Math.round(mine.frame * ratio);
                preview.height = preview.width;
                setBusy(false);
                setView(centred(size.w, size.h));
            }, function () {
                if (revoke) revoke();
                if (session !== mine) return;
                close();
                // A file the browser can't read goes up as it is: the server's answer says what is wrong.
                if (picked) {
                    plainUpload(input);
                } else if (opener && opener.focus) {
                    opener.focus();
                }
            });
        }

        function save() {
            var s = session;
            if (!s || !s.work || submitting) return;
            var r = sourceRect(s.view, s.w, s.h);
            var side = outputSide(r.side, maxSide);
            var out = shrink(doc, s.work, r.x, r.y, r.side, r.side, side, side);
            submitting = true;
            setBusy(true);
            out.toBlob(function (blob) {
                if (!blob) {
                    // Out of memory, as a rule. A picked file goes up as it is; an adjust stays open to try again.
                    submitting = false;
                    if (s.picked) {
                        close();
                        plainUpload(s.input);
                    } else if (session === s) {
                        setBusy(false);
                        saveButton.focus();
                    }
                    return;
                }
                close();
                var transfer = new win.DataTransfer();
                transfer.items.add(new win.File([blob], "photo.jpg", { type: JPEG }));
                s.input.files = transfer.files;
                s.input.form.submit();
            }, JPEG, QUALITY);
        }

        function framePoint(x, y) {
            var box = frame.getBoundingClientRect();
            return { x: x - (box.left + box.width / 2), y: y - (box.top + box.height / 2) };
        }

        function ids() {
            var out = [];
            for (var id in pointers) {
                if (Object.prototype.hasOwnProperty.call(pointers, id)) out.push(id);
            }
            return out;
        }

        frame.addEventListener("pointerdown", function (e) {
            if (!session || !session.work) return;
            // Only the main button drags: a right click's pointerup can go to the context menu.
            if (e.button > 0) return;
            if (e.preventDefault) e.preventDefault();
            try {
                if (frame.setPointerCapture) frame.setPointerCapture(e.pointerId);
            } catch (err) {}
            pointers[e.pointerId] = { x: e.clientX, y: e.clientY };
            // The frame's width again: the phone may have turned since the dialog opened.
            session.frame = frame.getBoundingClientRect().width || session.frame;
        });

        frame.addEventListener("pointermove", function (e) {
            var last = pointers[e.pointerId];
            if (!last || !session || !session.work) return;
            // A mouse whose release went elsewhere: no button is down any more, the drag is over.
            if (e.pointerType === "mouse" && e.buttons === 0) {
                delete pointers[e.pointerId];
                return;
            }
            var s = session;
            var held = ids();
            if (held.length === 1) {
                pointers[e.pointerId] = { x: e.clientX, y: e.clientY };
                setView(pan(s.view, e.clientX - last.x, e.clientY - last.y, s.frame, s.w, s.h, s.maxZoom));
                return;
            }
            // Two fingers: the midpoint pans, the distance zooms about it.
            var other = pointers[held[0] === String(e.pointerId) ? held[1] : held[0]];
            var midBefore = { x: (last.x + other.x) / 2, y: (last.y + other.y) / 2 };
            var distBefore = Math.sqrt(Math.pow(last.x - other.x, 2) + Math.pow(last.y - other.y, 2));
            pointers[e.pointerId] = { x: e.clientX, y: e.clientY };
            var midAfter = { x: (e.clientX + other.x) / 2, y: (e.clientY + other.y) / 2 };
            var distAfter = Math.sqrt(Math.pow(e.clientX - other.x, 2) + Math.pow(e.clientY - other.y, 2));
            var view = pan(s.view, midAfter.x - midBefore.x, midAfter.y - midBefore.y, s.frame, s.w, s.h, s.maxZoom);
            if (distBefore > 0) {
                var at = framePoint(midAfter.x, midAfter.y);
                view = zoomAt(view, view.zoom * distAfter / distBefore, at.x, at.y, s.frame, s.w, s.h, s.maxZoom);
            }
            setView(view);
        });

        function lift(e) {
            delete pointers[e.pointerId];
        }
        frame.addEventListener("pointerup", lift);
        frame.addEventListener("pointercancel", lift);

        frame.addEventListener("wheel", function (e) {
            if (!session || !session.work) return;
            if (e.preventDefault) e.preventDefault();
            var s = session;
            // Pixels, or lines (Firefox's mouse wheel) or pages as pixels.
            var dy = e.deltaY * (e.deltaMode === 1 ? 16 : (e.deltaMode === 2 ? 400 : 1));
            var at = framePoint(e.clientX, e.clientY);
            setView(zoomAt(s.view, s.view.zoom * Math.exp(-dy * 0.002), at.x, at.y, s.frame, s.w, s.h, s.maxZoom));
        });

        frame.addEventListener("keydown", function (e) {
            if (!session || !session.work) return;
            var s = session;
            var step = e.shiftKey ? STEP * 4 : STEP;
            // An arrow moves the photo as a drag that way would.
            var moves = { ArrowLeft: [-step, 0], ArrowRight: [step, 0], ArrowUp: [0, -step], ArrowDown: [0, step] };
            var view = null;
            if (Object.prototype.hasOwnProperty.call(moves, e.key)) {
                view = pan(s.view, moves[e.key][0], moves[e.key][1], s.frame, s.w, s.h, s.maxZoom);
            } else if (e.key === "+" || e.key === "=") {
                view = zoomAt(s.view, s.view.zoom * 1.1, 0, 0, s.frame, s.w, s.h, s.maxZoom);
            } else if (e.key === "-") {
                view = zoomAt(s.view, s.view.zoom / 1.1, 0, 0, s.frame, s.w, s.h, s.maxZoom);
            }
            if (!view) return;
            if (e.preventDefault) e.preventDefault();
            setView(view);
        });

        slider.addEventListener("input", function () {
            if (!session || !session.work) return;
            var s = session;
            setView(zoomAt(s.view, Number(slider.value), 0, 0, s.frame, s.w, s.h, s.maxZoom));
        });

        cancelButton.addEventListener("click", cancel);
        saveButton.addEventListener("click", save);

        // A press on the veil around the dialog would take the focus out of it: keep it on the frame.
        dialog.addEventListener("mousedown", function (e) {
            if (!session || e.target !== dialog) return;
            if (e.preventDefault) e.preventDefault();
            frame.focus();
        });

        // While it is open the dialog keeps the keys, wherever the focus went: Tab and Shift-Tab go round its
        // controls, Escape cancels.
        doc.addEventListener("keydown", function (e) {
            if (!session) return;
            if (e.key === "Escape" || e.key === "Esc") {
                if (e.preventDefault) e.preventDefault();
                if (!submitting) cancel();
                return;
            }
            if (e.key !== "Tab") return;
            var stops = [frame, slider, cancelButton, saveButton].filter(function (el) { return !el.disabled; });
            var at = stops.indexOf(doc.activeElement);
            var next = e.shiftKey ? (at <= 0 ? stops.length - 1 : at - 1) : (at < 0 || at === stops.length - 1 ? 0 : at + 1);
            if (e.preventDefault) e.preventDefault();
            stops[next].focus();
        }, true);

        var inputs = doc.querySelectorAll("input[type=file][data-photo-crop]");
        Array.prototype.forEach.call(inputs, function (input) {
            // autosave.js would upload the file as picked; from here the dialog's Save does.
            input.removeAttribute("data-autosave");
            input.addEventListener("change", function () {
                if (session || submitting) return;
                var file = input.files && input.files[0];
                if (!file) return;
                if (file.size > maxBytes) {
                    plainUpload(input);
                    return;
                }
                var url = win.URL.createObjectURL(file);
                open(input, input, url, true, function () { win.URL.revokeObjectURL(url); });
            });
        });

        var adjusters = doc.querySelectorAll("[data-photo-adjust]");
        Array.prototype.forEach.call(adjusters, function (button) {
            var input = button.form && button.form.querySelector("input[type=file][data-photo-crop]");
            if (!input) return;
            button.hidden = false;
            button.addEventListener("click", function () {
                if (session || submitting) return;
                open(input, button, button.getAttribute("data-photo-adjust"), false, null);
            });
        });

        // Back to this page from the back-forward cache after a save: it can crop again.
        if (win.addEventListener) {
            win.addEventListener("pageshow", function () {
                submitting = false;
            });
        }
        return { open: open, cancel: cancel, save: save };
    }

    var api = {
        install: install,
        clampView: clampView,
        centred: centred,
        sourceRect: sourceRect,
        pan: pan,
        zoomAt: zoomAt,
        fitWithin: fitWithin,
        outputSide: outputSide,
        adjustZoom: adjustZoom,
        halvings: halvings,
        supported: supported,
        MAX_ZOOM: MAX_ZOOM,
        MIN_ADJUST_SIDE: MIN_ADJUST_SIDE
    };
    if (typeof module !== "undefined" && module.exports) {
        module.exports = api;
    } else if (root.document) {
        install(root.document, root);
    }
})(this);
