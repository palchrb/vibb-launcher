// The summary's Quick Actions sheets (design 93 §6.1, §6.2): a tile opens its sheet below the row in place - no
// reload, the address unchanged -, a tap on the open tile or on Lukk closes it, and a tap on the other rule tile while
// the Regler sheet is open only switches its mode. The focus moves to the sheet's head (back to the tile on Lukk)
// without a scroll. In the Regler sheet: "Alle regler" is hidden in Slå på nå, the rule's mode line and warnings follow
// the chosen rule (each <option>'s data-line / data-warn), a length fills Til and typing in Til clears the lengths,
// and every button says its action as a full sentence. Without JS the tiles are links the server answers with the sheet
// open (?open=), and the buttons say "Slå på" / "Sett på pause". The words come from #quick's data-w-*. ES5.
// Run its tests: node --test jstest/quick-sheets.test.js
(function (root) {
    "use strict";

    var DAY = 24 * 60;

    function pad(n) {
        return (n < 10 ? "0" : "") + n;
    }

    // A minute of the day as "18:30"; midnight or later is the `midnight` word.
    function clock(minute, midnight) {
        if (minute >= DAY) return midnight;
        return pad(Math.floor(minute / 60)) + ":" + pad(minute % 60);
    }

    // `template` with each {name} replaced from `params` (an unknown one stays).
    function words(template, params) {
        return String(template || "").replace(/\{(\w+)\}/g, function (whole, name) {
            return Object.prototype.hasOwnProperty.call(params, name) ? params[name] : whole;
        });
    }

    function trim(text) {
        return String(text || "").replace(/^\s+|\s+$/g, "");
    }

    function closest(el, test) {
        while (el && el.getAttribute) {
            if (test(el)) return el;
            el = el.parentNode;
        }
        return null;
    }

    function checkedOf(form, name) {
        var list = form.querySelectorAll("input[name=" + name + "]");
        for (var i = 0; i < list.length; i++) {
            if (list[i].checked) return list[i];
        }
        return null;
    }

    // A segment's words: its label's text.
    function faceText(input) {
        return input && input.parentNode ? trim(input.parentNode.textContent) : "";
    }

    function selectedOption(select) {
        var options = select.querySelectorAll("option");
        for (var i = 0; i < options.length; i++) {
            if (options[i].selected) return options[i];
        }
        return options[0] || null;
    }

    function install(doc) {
        var section = doc.getElementById("quick");
        if (!section) return;
        var nowMinute = Number(section.getAttribute("data-now-minute")) || 0;
        var midnight = section.getAttribute("data-w-midnight") || "00:00";
        var mOn = doc.getElementById("m-on");
        var mPause = doc.getElementById("m-pause");
        var rulesForm = mOn ? mOn.form : null;
        var timeSheet = doc.getElementById("time-sheet");
        var timeForm = timeSheet ? timeSheet.querySelector("form") : null;

        function mode() {
            return mOn && mOn.checked ? "on" : "pause";
        }

        function setMode(wanted) {
            if (!mOn || !mPause) return;
            var on = wanted === "on" && !mOn.disabled;
            mOn.checked = on;
            mPause.checked = !on;
        }

        // The end "Slå på" would have: Til when typed, else the chosen length.
        function onAt() {
            var til = rulesForm.querySelector("[data-qa-til]");
            var typed = til && /^(\d\d):(\d\d)$/.exec(til.value || "");
            if (typed) {
                var minute = Number(typed[1]) * 60 + Number(typed[2]);
                return minute === 0 ? midnight : til.value;
            }
            var length = checkedOf(rulesForm, "on_len");
            if (!length) return null;
            if (length.value === "day") return midnight;
            return clock(nowMinute + Number(length.value), midnight);
        }

        function updateRules() {
            if (!rulesForm) return;
            var select = rulesForm.querySelector("select[data-qa-rule]");
            var options = select.querySelectorAll("option");
            var all = options[0];
            var chosen = selectedOption(select);
            if (mode() === "on") {
                all.hidden = true;
                all.disabled = true;
                if (chosen === all) {
                    var pick = null;
                    for (var i = 1; i < options.length && !pick; i++) {
                        if (options[i].hasAttribute("data-school")) pick = options[i];
                    }
                    pick = pick || options[1];
                    if (pick) {
                        all.selected = false;
                        pick.selected = true;
                        chosen = pick;
                    }
                }
            } else {
                all.hidden = false;
                all.disabled = false;
            }
            var line = rulesForm.querySelector("[data-qa-line]");
            if (line && chosen.hasAttribute("data-line")) line.textContent = chosen.getAttribute("data-line");
            var old = rulesForm.querySelectorAll("[data-qa-warn]");
            for (var j = old.length - 1; j >= 0; j--) old[j].parentNode.removeChild(old[j]);
            var warnings = chosen.getAttribute("data-warn") ? chosen.getAttribute("data-warn").split("\n") : [];
            var after = line;
            for (var k = 0; after && k < warnings.length; k++) {
                var p = doc.createElement("p");
                p.className = "row row-note qa-warn when-on";
                p.setAttribute("data-qa-warn", "");
                p.textContent = warnings[k];
                after.parentNode.insertBefore(p, after.nextSibling);
                after = p;
            }
            var name = trim(chosen.textContent);
            var buttons = rulesForm.querySelectorAll("[data-qa-button]");
            for (var b = 0; b < buttons.length; b++) {
                var button = buttons[b];
                if (button.getAttribute("data-qa-button") === "on") {
                    var at = onAt();
                    if (at) button.textContent = words(section.getAttribute("data-w-on"), { rule: name, at: at });
                } else {
                    var duration = faceText(checkedOf(rulesForm, "pause_minutes"));
                    button.textContent = chosen === all
                        ? words(section.getAttribute("data-w-pause-all"), { duration: duration })
                        : words(section.getAttribute("data-w-pause"), { rule: name, duration: duration });
                }
            }
        }

        function updateTime() {
            if (!timeForm) return;
            var button = timeForm.querySelector("[data-qa-button]");
            var duration = faceText(checkedOf(timeForm, "minutes"));
            if (button && duration) button.textContent = words(section.getAttribute("data-w-extra"), { duration: duration });
        }

        function tiles() {
            return section.querySelectorAll("a.qa[data-sheet]");
        }

        // Each tile is chosen while its sheet is shown in its own mode.
        function mark() {
            var list = tiles();
            for (var i = 0; i < list.length; i++) {
                var tile = list[i];
                var sheet = doc.getElementById(tile.getAttribute("data-sheet"));
                var tileMode = tile.getAttribute("data-mode");
                var chosen = !!sheet && !sheet.hidden && (!tileMode || tileMode === mode());
                if (chosen) tile.classList.add("qa-chosen");
                else tile.classList.remove("qa-chosen");
                tile.setAttribute("aria-expanded", chosen ? "true" : "false");
            }
        }

        function focus(el) {
            if (el && el.focus) el.focus({ preventScroll: true });
        }

        function toggle(tile) {
            var sheet = doc.getElementById(tile.getAttribute("data-sheet"));
            if (!sheet) return;
            var tileMode = tile.getAttribute("data-mode");
            if (!sheet.hidden && (!tileMode || tileMode === mode())) {
                sheet.hidden = true;
                mark();
                return;
            }
            var wasHidden = sheet.hidden;
            var sheets = section.querySelectorAll(".qa-sheet");
            for (var i = 0; i < sheets.length; i++) sheets[i].hidden = sheets[i] !== sheet;
            if (tileMode) {
                setMode(tileMode);
                updateRules();
            }
            mark();
            if (wasHidden) focus(doc.getElementById(sheet.id + "-title"));
        }

        function close(sheet) {
            sheet.hidden = true;
            mark();
            var list = tiles();
            for (var i = 0; i < list.length; i++) {
                if (list[i].getAttribute("data-sheet") === sheet.id) {
                    focus(list[i]);
                    return;
                }
            }
        }

        doc.addEventListener("click", function (e) {
            var tile = closest(e.target, function (el) {
                return el.hasAttribute("data-sheet");
            });
            if (tile) {
                e.preventDefault();
                toggle(tile);
                return;
            }
            var lukk = closest(e.target, function (el) {
                return el.hasAttribute("data-qa-close");
            });
            var sheet = lukk && closest(lukk, function (el) {
                return el.classList && el.classList.contains("qa-sheet");
            });
            if (sheet) {
                e.preventDefault();
                close(sheet);
            }
        });

        doc.addEventListener("change", function (e) {
            var el = e.target;
            if (!el || !el.form) return;
            if (el.form === rulesForm) {
                if (el.name === "on_len") {
                    var til = rulesForm.querySelector("[data-qa-til]");
                    if (til) til.value = el.value === "day" ? "" : clock(nowMinute + Number(el.value), "00:00");
                }
                updateRules();
                mark();
            } else if (el.form === timeForm) {
                updateTime();
            }
        });

        doc.addEventListener("input", function (e) {
            var el = e.target;
            if (!el || el.form !== rulesForm || !el.hasAttribute("data-qa-til")) return;
            if (el.value) {
                var lengths = rulesForm.querySelectorAll("input[name=on_len]");
                for (var i = 0; i < lengths.length; i++) lengths[i].checked = false;
            }
            updateRules();
        });

        updateRules();
        updateTime();
    }

    var api = { install: install, clock: clock, words: words };
    if (typeof module !== "undefined" && module.exports) {
        module.exports = api;
    } else if (root.document) {
        install(root.document);
    }
})(this);
