// The PWA never "does nothing" on a tap after being away (2026-10-21). After the app was in the
// background a while, the network to the server (often over Tailscale) may not be up yet, and an
// installed PWA shows no loading indicator of its own - a tapped tab looked dead until the app was
// restarted. This script, loaded from the head partial on every page:
// - marks a tapped same-origin link (the tab bar, an ordinary link) and shows a thin progress bar at
//   the top at once - not for a download, a `target`, a modified click or a #fragment on this page;
// - if the page hasn't been left after 3 s, shows a small calm chip "Connecting…" (no alarm colour,
//   no modal, the page stays usable) and heals by itself: it pings the server (`GET /ping`, 204
//   with a session, a redirect to the login without one: any answer means the server is there) at
//   once, every 5 s, on `online` and when the page becomes visible again, and starts the same
//   navigation again once the server answers (after a ping failed, or 12 s into a navigation
//   that still hasn't arrived), so the page arrives without another tap. A quiet "Try again" is
//   offered only after 20 s;
// - when the page becomes visible after more than 10 minutes hidden, pings the server (the chip
//   only if that takes over 3 s) and, answered, reloads the page in place keeping its scroll place
//   (scroll-restore.js's record, as auto-reload.js leaves it - never the top: "Lagring i
//   foreldre-PWAen skal aldri hoppe til toppen av siden"); a signed-out session lands on the
//   login. It never reloads once a save has started or while a <details> is open (auto-reload.js's
//   rules); then the page stays as it is;
// - clears everything on `pageshow` (a page back from the back-forward cache is never left
//   "loading").
// No service-worker fetch handler: the network is never faked. ES5, no framework (§5.7: Chrome/
// WebView 80, iOS Safari 14). Run its tests: node --test jstest/reconnect.test.js
(function (root) {
    "use strict";

    var CHIP_AFTER = 3000;
    var PING_EVERY = 5000;
    var PING_TIMEOUT = 4000;
    var OFFER_AFTER = 20000;
    var STUCK_AFTER = 12000;
    var AWAY_MS = 10 * 60 * 1000;
    var PING_URL = "/ping";
    // static/scroll-restore.js's form record (its KEY and NAME_PREFIX).
    var SCROLL_KEY = "handy-scroll";
    var NAME_PREFIX = "handy-scroll:";
    // The PWA is English (`<html lang="en">`), as every other string on its pages.
    var TEXT = { connecting: "Connecting…", retry: "Try again" };

    /**
     * The address a click on `link` navigates to, or null when this script leaves it alone: no
     * link, a click another script handled, not the main button, a modified click, a download, a
     * `target` other than _self, another origin or scheme, a #fragment on this page.
     */
    function navigationTarget(link, ev, here) {
        if (!link || !ev || ev.defaultPrevented) return null;
        if (ev.button || ev.metaKey || ev.ctrlKey || ev.shiftKey || ev.altKey) return null;
        if (link.hasAttribute("download") || link.hasAttribute("data-no-wait")) return null;
        var target = link.getAttribute("target");
        if (target && target !== "_self") return null;
        var raw = link.getAttribute("href");
        if (raw === null || raw === "" || raw.charAt(0) === "#") return null;
        var to, from;
        try {
            to = new URL(raw, here);
            from = new URL(here);
        } catch (e) {
            return null;
        }
        if (to.protocol !== "http:" && to.protocol !== "https:") return null;
        if (to.protocol !== from.protocol || to.host !== from.host) return null;
        if (to.hash && to.pathname === from.pathname && to.search === from.search) return null;
        return to.href;
    }

    /** The nearest <a href> at or above `el`. */
    function linkOf(el) {
        for (; el; el = el.parentNode) {
            if (el.tagName && String(el.tagName).toUpperCase() === "A" && el.hasAttribute && el.hasAttribute("href")) {
                return el;
            }
        }
        return null;
    }

    /** One ping: `done(true)` for an answer (any status below 500), `done(false)` for none in time. */
    function ping(win, now, done) {
        var finished = false;
        var ctrl = null;
        var timer = null;
        function finish(answered) {
            if (finished) return;
            finished = true;
            if (timer !== null) win.clearTimeout(timer);
            done(answered);
        }
        if (typeof win.fetch !== "function") {
            finish(true);
            return;
        }
        try {
            ctrl = typeof win.AbortController === "function" ? new win.AbortController() : null;
        } catch (e) {}
        timer = win.setTimeout(function () {
            timer = null;
            try {
                if (ctrl) ctrl.abort();
            } catch (e) {}
            finish(false);
        }, PING_TIMEOUT);
        var options = { credentials: "same-origin", cache: "no-store" };
        if (ctrl) options.signal = ctrl.signal;
        try {
            win.fetch(PING_URL + "?t=" + now(), options).then(function (res) {
                finish(!!res && res.status < 500);
            }, function () {
                finish(false);
            });
        } catch (e) {
            finish(false);
        }
    }

    /** The bar, the chip and the marked link, made on first use (this runs from the head). */
    function domView(win) {
        var doc = win.document;
        var bar = null;
        var chip = null;
        var retry = null;
        function ready() {
            if (chip) return true;
            if (!doc.body || !doc.createElement) return false;
            bar = doc.createElement("div");
            bar.className = "net-progress";
            bar.setAttribute("aria-hidden", "true");
            bar.hidden = true;
            chip = doc.createElement("div");
            chip.className = "net-chip";
            chip.setAttribute("role", "status");
            chip.hidden = true;
            var text = doc.createElement("span");
            text.textContent = TEXT.connecting;
            retry = doc.createElement("button");
            retry.type = "button";
            retry.className = "net-retry";
            retry.textContent = TEXT.retry;
            retry.hidden = true;
            chip.appendChild(text);
            chip.appendChild(retry);
            doc.body.appendChild(bar);
            doc.body.appendChild(chip);
            return true;
        }
        return {
            bar: function (on) {
                if (ready()) bar.hidden = !on;
            },
            chip: function (on) {
                if (!ready()) return;
                chip.hidden = !on;
                if (!on) retry.hidden = true;
            },
            offer: function (onRetry) {
                if (!ready()) return;
                retry.onclick = onRetry || null;
                retry.hidden = !onRetry;
            },
            mark: function (link, on) {
                if (!link || !link.classList) return;
                if (on) {
                    link.classList.add("is-pending");
                    link.setAttribute("aria-busy", "true");
                } else {
                    link.classList.remove("is-pending");
                    link.removeAttribute("aria-busy");
                }
            }
        };
    }

    // The position of what scrolls the page: <main> in the app shell (design 43), else the window
    // (scroll-restore.js's scrollerOf; this file stands alone, as auto-reload.js does).
    function scrollTop(win) {
        var main = win.document.querySelector ? win.document.querySelector("main") : null;
        var overflow = main && win.getComputedStyle ? win.getComputedStyle(main).overflowY : "";
        return overflow === "auto" || overflow === "scroll" ? main.scrollTop : (win.scrollY || 0);
    }

    /**
     * scroll-restore.js's form record for a reload in place: the path, the position and the time,
     * and over a #fragment where its element is on the screen (`tops`), so the reloaded page puts
     * it back there rather than at the anchor. In sessionStorage and in window.name (an installed
     * iPhone app can lose sessionStorage).
     */
    function placeRecord(win, now) {
        var record = { path: win.location.pathname, y: scrollTop(win), t: now };
        var hash = win.location.hash;
        if (hash && hash !== "#" && win.document.getElementById) {
            var id = hash.slice(1);
            try {
                id = decodeURIComponent(id);
            } catch (e) {}
            var el = win.document.getElementById(id);
            if (el && el.getBoundingClientRect) {
                record.tops = {};
                record.tops[id] = el.getBoundingClientRect().top;
            }
        }
        return record;
    }

    function install(win, opts) {
        opts = opts || {};
        var now = opts.now || function () { return Date.now(); };
        var view = opts.view || domView(win);
        var doc = win.document;
        var wait = null;
        var hiddenAt = null;
        var saving = false;

        function later(fn, ms) {
            return win.setTimeout(fn, ms);
        }

        function stop() {
            if (!wait) return;
            for (var i = 0; i < wait.timers.length; i++) win.clearTimeout(wait.timers[i]);
            wait.timers = [];
        }

        function end() {
            if (!wait) return;
            stop();
            view.mark(wait.link, false);
            view.bar(false);
            view.offer(null);
            view.chip(false);
            wait = null;
        }

        function keepPlace() {
            try {
                if (win.history && "scrollRestoration" in win.history) {
                    win.history.scrollRestoration = "auto";
                }
            } catch (e) {}
            var record = JSON.stringify(placeRecord(win, now()));
            try {
                win.sessionStorage.setItem(SCROLL_KEY, record);
            } catch (e) {}
            try {
                win.name = NAME_PREFIX + record;
            } catch (e) {}
        }

        /** The navigation again: the same address, or the reload in place. */
        function go(w) {
            w.restarted = now();
            w.failed = false;
            if (w.href === null) {
                keepPlace();
                win.location.reload();
            } else {
                win.location.assign(w.href);
            }
        }

        function check(w) {
            if (wait !== w || w.asking) return;
            w.asking = true;
            ping(win, now, function (answered) {
                w.asking = false;
                if (wait !== w) return;
                if (!answered) {
                    w.failed = true;
                    return;
                }
                if (w.kind === "return") {
                    // The server is there: the page fresh, in place - unless a save started or a
                    // <details> is open (what was typed or opened would be lost).
                    if (saving || (doc.querySelector && doc.querySelector("details[open]"))) {
                        end();
                        return;
                    }
                    var reload = start("load", null, null);
                    go(reload);
                    return;
                }
                if (w.failed || now() - w.restarted >= STUCK_AFTER) go(w);
            });
        }

        /**
         * A wait: "load" for a navigation on its way (`href` null = the reload in place), "return"
         * for the ping after being away (nothing shown unless it takes over 3 s).
         */
        function start(kind, href, link) {
            end();
            var w = {
                kind: kind,
                href: href,
                link: link,
                started: now(),
                restarted: now(),
                failed: false,
                asking: false,
                timers: []
            };
            wait = w;
            if (kind === "load") {
                view.mark(link, true);
                view.bar(true);
            }
            w.timers.push(later(function () {
                if (wait !== w) return;
                view.chip(true);
                if (kind === "load") check(w);
            }, CHIP_AFTER));
            w.timers.push(later(function () {
                if (wait !== w) return;
                view.offer(function () {
                    if (wait === w) go(w);
                });
            }, OFFER_AFTER));
            var every = function () {
                if (wait !== w) return;
                check(w);
                w.timers.push(later(every, PING_EVERY));
            };
            w.timers.push(later(every, CHIP_AFTER + PING_EVERY));
            if (kind === "return") check(w);
            return w;
        }

        win.addEventListener("click", function (e) {
            var link = linkOf(e && e.target);
            var href = navigationTarget(link, e, win.location.href);
            if (href !== null) start("load", href, link);
        });

        // A save leaves the page (auto-reload.js's rule): no reload in place from then on.
        doc.addEventListener("submit", function () { saving = true; }, true);
        doc.addEventListener("change", function (e) {
            var el = e && e.target;
            if (el && el.hasAttribute && el.hasAttribute("data-autosave")) saving = true;
        }, true);

        function cameBack() {
            var away = hiddenAt !== null && now() - hiddenAt > AWAY_MS;
            hiddenAt = null;
            if (wait) {
                check(wait);
            } else if (away && !saving) {
                start("return", null, null);
            }
        }

        doc.addEventListener("visibilitychange", function () {
            if (doc.visibilityState === "hidden") {
                if (hiddenAt === null) hiddenAt = now();
            } else {
                cameBack();
            }
        });
        win.addEventListener("online", function () {
            if (wait) check(wait);
        });
        win.addEventListener("pagehide", function () {
            stop();
            if (hiddenAt === null) hiddenAt = now();
        });
        // Back from the back-forward cache: nothing is "loading" any more, no save is on its way,
        // and a long time away counts as being away.
        win.addEventListener("pageshow", function (e) {
            if (!e || !e.persisted) return;
            end();
            saving = false;
            cameBack();
        });
    }

    var api = {
        install: install,
        navigationTarget: navigationTarget,
        linkOf: linkOf,
        placeRecord: placeRecord,
        CHIP_AFTER: CHIP_AFTER,
        PING_EVERY: PING_EVERY,
        PING_TIMEOUT: PING_TIMEOUT,
        OFFER_AFTER: OFFER_AFTER,
        STUCK_AFTER: STUCK_AFTER,
        AWAY_MS: AWAY_MS,
        TEXT: TEXT
    };
    if (typeof module !== "undefined" && module.exports) {
        module.exports = api;
    } else if (root.document) {
        install(root);
    }
})(this);
