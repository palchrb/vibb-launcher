// The music cards' status in place (design 21b §3): while a card on /music (or an entry's page)
// says "Checking…" or "Waiting…", ask GET /music/cards?ids=... every 3 s, for at most 5 min, and
// swap only the status blocks (.music-status). A card that holds the focus (an open select, a
// focused button) is never touched, and the view is kept still: the top of the first visible card
// is noted before a swap and the page is scrolled by the difference afterwards (iOS Safari has no
// scroll anchoring). The page works without this - a reload shows the same.
(function () {
    "use strict";
    var EVERY_MS = 3000;
    var FOR_MS = 5 * 60 * 1000;
    var started = Date.now();

    function busyIds() {
        var ids = [];
        var blocks = document.querySelectorAll('.music-status[data-busy="1"]');
        for (var i = 0; i < blocks.length; i++) {
            var id = blocks[i].getAttribute("data-entry");
            if (id && ids.indexOf(id) < 0) ids.push(id);
        }
        return ids.slice(0, 200);
    }

    function holdsFocus(block) {
        var card = block.closest(".music-card, .card") || block;
        var active = document.activeElement;
        return !!active && active !== document.body && card.contains(active);
    }

    // The first card on screen; a card that holds the music cards doesn't count (its top doesn't
    // move when one of them grows).
    function firstVisible() {
        var cards = document.querySelectorAll(".music-card, .card");
        for (var i = 0; i < cards.length; i++) {
            if (cards[i].querySelector(".music-card")) continue;
            if (cards[i].getBoundingClientRect().bottom > 0) return cards[i];
        }
        return null;
    }

    function swap(cards) {
        var anchor = firstVisible();
        var before = anchor ? anchor.getBoundingClientRect().top : 0;
        for (var i = 0; i < cards.length; i++) {
            var blocks = document.querySelectorAll('.music-status[data-entry="' + cards[i].id + '"]');
            for (var j = 0; j < blocks.length; j++) {
                if (holdsFocus(blocks[j])) continue;
                var holder = document.createElement("div");
                holder.innerHTML = cards[i].html;
                var fresh = holder.firstElementChild;
                if (fresh && fresh.outerHTML !== blocks[j].outerHTML) blocks[j].replaceWith(fresh);
            }
        }
        if (anchor) {
            var after = anchor.getBoundingClientRect().top;
            if (after !== before) window.scrollBy(0, after - before);
        }
    }

    function tick() {
        var ids = busyIds();
        if (!ids.length || Date.now() - started > FOR_MS) return;
        fetch("/music/cards?ids=" + ids.join(","), {
            credentials: "same-origin",
            headers: { Accept: "application/json" }
        })
            .then(function (response) { return response.ok ? response.json() : null; })
            .then(function (data) {
                if (data && data.cards) swap(data.cards);
                setTimeout(tick, EVERY_MS);
            })
            .catch(function () { setTimeout(tick, EVERY_MS); });
    }

    setTimeout(tick, EVERY_MS);
})();
