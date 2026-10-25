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
//   navigation again once the server answers after a failed ping (the network is back), so the
//   page arrives without another tap; a navigation that is only slow is started again once, 12 s
//   in, then left to come. A quiet "Try again" is offered only after 20 s. A save started meanwhile
//   (a submit, a data-autosave change) ends the wait: its restart would cancel the save;
// - when the page becomes visible after more than 10 minutes hidden, pings the server (the chip
//   only if that takes over 3 s) and, answered, reloads the page in place keeping its scroll place
//   (scroll-restore.js's record, as auto-reload.js leaves it - never the top: "Lagring i
//   foreldre-PWAen skal aldri hoppe til toppen av siden"); a signed-out session lands on the
//   login. It never reloads once a save has started, while a <details> is open (auto-reload.js's
//   rules), when a field was typed in or changed, or on a POST's answer (the server marks its
//   <body data-post-answer>, `admin_auth::mark_post_answer`); then the page stays as it is. Before
//   any reload the entry is made a GET of the address (`replaceState`), so a reload never resends
//   a form;
// - clears everything on `pageshow` (a page back from the back-forward cache is never left
//   "loading");
// - on the sign-in page (2026-10-25: after a Tailscale outage the return reload showed the login
//   while the session was valid), asks `GET /api/session` (204 / 401) at once and, signed in, goes
//   back to the page that was wanted (`loginReturn`); a ping redirected to the login asks it too.
// No service-worker fetch handler: the network is never faked. ES5, no framework (§5.7: Chrome/
// WebView 80, iOS Safari 14). Run its tests: node --test jstest/reconnect.test.js
(function (root) {
    "use strict";

    var CHIP_AFTER = 3000;
    var PING_EVERY = 5000;
    var PING_TIMEOUT = 4000;
    var OFFER_AFTER = 20000;
    var STUCK_AFTER = 12000;
    // 1 minute (the user, 2026-10-24; 10 minutes until then): back from the background after that long = a reload in place.
    var AWAY_MS = 60 * 1000;
    var PING_URL = "/ping";
    // Is this request's session signed in? 204 / 401, no body (2026-10-25, security::session_check).
    var SESSION_URL = "/api/session";
    // The address a navigation of this script was going to (sessionStorage), and when the sign-in
    // page last went back to one: a page that wanted 2 min ago is still wanted; within 30 s of
    // going back the sign-in page stays (never a loop).
    var WANTED_KEY = "handy-wanted";
    var BOUNCE_KEY = "handy-login-bounce";
    var WANTED_MS = 2 * 60 * 1000;
    var BOUNCE_MS = 30 * 1000;
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

    /**
     * `raw` as a page of this app to go back to (its path, query and #fragment), or null: another
     * origin, or a page that is the sign-in itself (`/login`, `/auth/…`, `/logout`, `/api/…`).
     */
    function appPath(raw, here) {
        if (!raw) return null;
        var to, from;
        try {
            to = new URL(raw, here);
            from = new URL(here);
        } catch (e) {
            return null;
        }
        if (to.protocol !== from.protocol || to.host !== from.host) return null;
        var path = to.pathname;
        if (path === "/login" || path === "/logout" || path.indexOf("/auth/") === 0 || path.indexOf("/api/") === 0) {
            return null;
        }
        return path + to.search + to.hash;
    }

    /**
     * The page the sign-in page goes back to once `GET /api/session` says the session lives
     * (2026-10-25: after a Tailscale outage the return reload landed on the login, while the
     * session was valid), or null to stay: not the sign-in page, or it went back less than 30 s ago
     * (`bounceAt`). In order: a `next` or `from` in the address, the page this script's navigation
     * wanted in the last 2 minutes (`wanted`: {href, t}), the referrer within the app, else `/`
     * (a signed-in session never needs the sign-in page).
     */
    function loginReturn(here, referrer, wanted, bounceAt, nowMs) {
        var url;
        try {
            url = new URL(here);
        } catch (e) {
            return null;
        }
        if (url.pathname !== "/login") return null;
        if (typeof bounceAt === "number" && nowMs - bounceAt >= 0 && nowMs - bounceAt < BOUNCE_MS) return null;
        var params = url.searchParams;
        var asked = params ? appPath(params.get("next"), here) || appPath(params.get("from"), here) : null;
        if (asked) return asked;
        if (wanted && typeof wanted.t === "number" && nowMs - wanted.t >= 0 && nowMs - wanted.t < WANTED_MS) {
            var back = appPath(wanted.href, here);
            if (back) return back;
        }
        return appPath(referrer, here) || "/";
    }

    /** `GET /api/session`: `done(status)`, `done(0)` for no answer in time. */
    function askSession(win, done) {
        var finished = false;
        var ctrl = null;
        var timer = null;
        function finish(status) {
            if (finished) return;
            finished = true;
            if (timer !== null) win.clearTimeout(timer);
            done(status);
        }
        if (typeof win.fetch !== "function") {
            finish(0);
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
            finish(0);
        }, PING_TIMEOUT);
        var options = { credentials: "same-origin", cache: "no-store" };
        if (ctrl) options.signal = ctrl.signal;
        try {
            win.fetch(SESSION_URL, options).then(function (res) {
                finish(res && res.status ? res.status : 0);
            }, function () {
                finish(0);
            });
        } catch (e) {
            finish(0);
        }
    }

    /** Whether a fetch's answer is the sign-in page it was redirected to. */
    function landedOnLogin(res) {
        if (!res || !res.redirected || !res.url) return false;
        try {
            return new URL(res.url).pathname === "/login";
        } catch (e) {
            return false;
        }
    }

    /**
     * One ping: `done(true)` for an answer (any status below 500), `done(false)` for none in time.
     * A ping redirected to the sign-in page asks `GET /api/session` first (2026-10-25): its answer,
     * 204 or 401, is the server's; none is none.
     */
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
                if (!finished && landedOnLogin(res)) {
                    askSession(win, function (status) {
                        finish(status > 0 && status < 500);
                    });
                    return;
                }
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
        var text = null;
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
            // Empty while hidden, its words set when shown: a live region announces a change of
            // its text, not one that appears with it (QA L9, VoiceOver).
            text = doc.createElement("span");
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
                text.textContent = on ? TEXT.connecting : "";
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

    /**
     * A form control that differs from what the page came with: text typed, a box ticked, an
     * option picked - a reload in place would lose it (qa-srv-sweep-resume-code L4).
     */
    function edited(doc) {
        if (!doc.querySelectorAll) return false;
        var controls = doc.querySelectorAll("input, textarea, select");
        for (var i = 0; i < controls.length; i++) {
            var el = controls[i];
            var tag = String(el.tagName || "").toUpperCase();
            if (tag === "SELECT") {
                var options = el.options || [];
                for (var j = 0; j < options.length; j++) {
                    if (options[j].selected !== options[j].defaultSelected) return true;
                }
                continue;
            }
            var type = String(el.type || "").toLowerCase();
            if (type === "hidden" || type === "submit" || type === "button" || type === "reset" || type === "image") continue;
            if (type === "checkbox" || type === "radio") {
                if (el.checked !== el.defaultChecked) return true;
            } else if (type === "file") {
                if (el.value) return true;
            } else if (el.value !== el.defaultValue) {
                return true;
            }
        }
        return false;
    }

    /** The address to reload: this one without a shown `notice=` (it would show again). */
    function reloadUrl(href) {
        var hash = "";
        var at = href.indexOf("#");
        if (at >= 0) {
            hash = href.slice(at);
            href = href.slice(0, at);
        }
        var q = href.indexOf("?");
        if (q < 0) return href + hash;
        var kept = [];
        var parts = href.slice(q + 1).split("&");
        for (var i = 0; i < parts.length; i++) {
            if (parts[i] && parts[i].split("=")[0] !== "notice") kept.push(parts[i]);
        }
        return href.slice(0, q) + (kept.length ? "?" + kept.join("&") : "") + hash;
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

        /** The address a navigation is going to, for the sign-in page's way back ([loginReturn]). */
        function remember(href) {
            try {
                win.sessionStorage.setItem(WANTED_KEY, JSON.stringify({ href: href, t: now() }));
            } catch (e) {}
        }

        /**
         * The address the last navigation wanted: sessionStorage's record, else (an installed
         * iPhone app can lose sessionStorage) the place record a reload in place left in
         * window.name - its path and time.
         */
        function wanted() {
            var record = null;
            try {
                record = JSON.parse(win.sessionStorage.getItem(WANTED_KEY));
            } catch (e) {}
            if (record && typeof record.href === "string") return record;
            try {
                var name = String(win.name || "");
                if (name.indexOf(NAME_PREFIX) === 0) {
                    var place = JSON.parse(name.slice(NAME_PREFIX.length));
                    if (place && typeof place.path === "string") return { href: place.path, t: place.t };
                }
            } catch (e) {}
            return null;
        }

        function bounced() {
            try {
                var at = parseInt(win.sessionStorage.getItem(BOUNCE_KEY), 10);
                return isNaN(at) ? null : at;
            } catch (e) {
                return null;
            }
        }

        /**
         * On the sign-in page (2026-10-25): ask `GET /api/session` whether the session lives and,
         * 204, go back to the wanted page ([loginReturn]) - `location.replace`, so Back doesn't
         * return to the sign-in page. A 401, no answer, a sign-in already being sent (a submit) or
         * a POST's answer (a refused password) keep the page.
         */
        function checkSignIn() {
            if (postAnswer()) return;
            var target = loginReturn(win.location.href, doc.referrer || "", wanted(), bounced(), now());
            if (target === null) return;
            askSession(win, function (status) {
                if (status !== 204 || saving) return;
                try {
                    win.sessionStorage.setItem(BOUNCE_KEY, String(now()));
                    win.sessionStorage.removeItem(WANTED_KEY);
                } catch (e) {}
                win.location.replace(target);
            });
        }

        /** The page is a POST's answer (the server marks its <body>): never reloaded (QA M1). */
        function postAnswer() {
            return !!(doc.body && doc.body.hasAttribute && doc.body.hasAttribute("data-post-answer"));
        }

        /**
         * The navigation again: the same address, or the reload in place. Before a reload the entry
         * is made a GET of this address (`replaceState`), so a reload can never resend a form - a
         * belt to the `data-post-answer` rule (QA M1) - and loses a `notice=` already shown.
         */
        function go(w) {
            w.restarted = now();
            w.failed = false;
            if (w.href === null) {
                keepPlace();
                try {
                    win.history.replaceState(win.history.state, "", reloadUrl(win.location.href));
                } catch (e) {}
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
                    // The server is there: the page fresh, in place - unless a save started, a
                    // <details> is open, something was typed or picked (it would be lost), or the
                    // page is a POST's answer.
                    if (saving || postAnswer() || (doc.querySelector && doc.querySelector("details[open]")) || edited(doc)) {
                        end();
                        return;
                    }
                    var reload = start("load", null, null);
                    go(reload);
                    return;
                }
                // Started again: at once once the network is back (a ping failed during the wait);
                // a page that is only slow, once after 12 s and then left to come, with "Try again"
                // (QA L2).
                if (w.failed) {
                    go(w);
                } else if (!w.slowRestarted && now() - w.restarted >= STUCK_AFTER) {
                    w.slowRestarted = true;
                    go(w);
                }
            });
        }

        /**
         * The wait's timers, from its start: the chip (and a navigation's first ping) at 3 s, "Try
         * again" at 20 s, a ping every 5 s from 8 s. Armed again when the page comes back after a
         * `pagehide` that stopped them (QA L5).
         */
        function arm(w) {
            var elapsed = now() - w.started;
            if (!w.chipShown) {
                w.timers.push(later(function () {
                    if (wait !== w) return;
                    w.chipShown = true;
                    view.chip(true);
                    if (w.kind === "load") check(w);
                }, Math.max(0, CHIP_AFTER - elapsed)));
            }
            if (!w.offered) {
                w.timers.push(later(function () {
                    if (wait !== w) return;
                    w.offered = true;
                    view.offer(function () {
                        if (wait === w) go(w);
                    });
                }, Math.max(0, OFFER_AFTER - elapsed)));
            }
            var every = function () {
                if (wait !== w) return;
                check(w);
                w.timers.push(later(every, PING_EVERY));
            };
            w.timers.push(later(every, Math.max(PING_EVERY, CHIP_AFTER + PING_EVERY - elapsed)));
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
                slowRestarted: false,
                asking: false,
                chipShown: false,
                offered: false,
                timers: []
            };
            wait = w;
            if (kind === "load") {
                remember(href === null ? win.location.href : href);
                view.mark(link, true);
                view.bar(true);
            }
            arm(w);
            if (kind === "return") check(w);
            return w;
        }

        win.addEventListener("click", function (e) {
            var link = linkOf(e && e.target);
            var href = navigationTarget(link, e, win.location.href);
            if (href !== null) start("load", href, link);
        });

        // A save leaves the page (auto-reload.js's rule): no reload in place from then on, and a
        // wait for an earlier tap ends - its restart would cancel the save's POST (QA L3).
        function saveStarted() {
            saving = true;
            end();
        }
        doc.addEventListener("submit", saveStarted, true);
        doc.addEventListener("change", function (e) {
            var el = e && e.target;
            if (el && el.hasAttribute && el.hasAttribute("data-autosave")) saveStarted();
        }, true);

        function cameBack() {
            var away = hiddenAt !== null && now() - hiddenAt > AWAY_MS;
            hiddenAt = null;
            if (wait) {
                if (!wait.timers.length) arm(wait);
                check(wait);
            } else if (away && !saving && !postAnswer()) {
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

        // The reload in place another script asks for (pull-refresh.js, 2026-10-25): the same rules
        // as the return's - no save on its way, no open <details>, nothing typed or picked - and the
        // same way: the "load" wait (the bar, the chip after 3 s, the ping and the restart) over
        // go()'s kept place and GET entry. A POST's answer may be reloaded when asked: go() makes
        // the entry a GET first, so nothing is sent again.
        win.handyReconnect = {
            mayReload: function () {
                return !saving && !(doc.querySelector && doc.querySelector("details[open]")) && !edited(doc);
            },
            reload: function () {
                if (!this.mayReload()) return false;
                go(start("load", null, null));
                return true;
            }
        };

        if (win.location.pathname === "/login") checkSignIn();
    }

    var api = {
        install: install,
        navigationTarget: navigationTarget,
        linkOf: linkOf,
        placeRecord: placeRecord,
        edited: edited,
        reloadUrl: reloadUrl,
        appPath: appPath,
        loginReturn: loginReturn,
        domView: domView,
        WANTED_MS: WANTED_MS,
        BOUNCE_MS: BOUNCE_MS,
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
