// Service worker - templated so `{{ app_version }}` is baked into the
// script's own bytes on every request, which is what makes the browser's
// update-detection actually fire (it byte-diffs a refetched /sw.js against
// the installed one).
//
// It makes the PWA installable and shows the parent's notifications (design
// 35 §5.3): every push shows a visible notification (never an invisible one),
// then posts its receipt (the per-delivery token, no cookie) so the server can
// tell "shown" from "not confirmed"; a tap opens the event's page and marks it
// read. It does NOT cache pages or assets for offline use - this app handles
// admin session cookies and device bearer tokens, and offline browsing of the
// admin console isn't a stated need. Behaviour tests: jstest/sw.test.js.
const VERSION = "{{ app_version }}";

self.addEventListener("install", () => {
    self.skipWaiting();
});

self.addEventListener("activate", (event) => {
    event.waitUntil(self.clients.claim());
});

// A path on this server: a same-origin URL whose path starts with one "/",
// else the inbox (design 35 §2.2: never another site).
function localPath(url) {
    if (typeof url !== "string") return "/notifications";
    try {
        const parsed = new URL(url, self.location.origin);
        if (parsed.origin === self.location.origin && parsed.pathname.startsWith("/")
            && !parsed.pathname.startsWith("//")) {
            return parsed.pathname + parsed.search + parsed.hash;
        }
    } catch (e) {
        // not a URL
    }
    return "/notifications";
}

// The receipt (design 35 §7): a failed one is not kept.
// `event` names the worker event that led here (push, pushnotification, click) and `v` the
// worker's version: the server logs both, so a device whose pushes aren't confirmed can be read
// (which event Safari fires, whether the new worker is in use) without a debugger.
function receipt(data, action, event) {
    if (!data || !data.d || !data.t) return Promise.resolve();
    return fetch("/notifications/receipt", {
        method: "POST",
        credentials: "omit",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ d: data.d, t: data.t, a: action, e: event || action, v: VERSION }),
    }).catch(() => undefined);
}

// A probe of what the browser handed the worker (the iPhone diagnosis of 2026-10-19: every
// declarative push shown, none confirmed - whether this worker runs at all for one, and what
// shape the event has). Posted first, before anything that could throw, and needing none of
// our data: the event's name, which of the candidate properties exist, whether `data` is set,
// and the proposed notification's shape - the title's type, `data`'s type and keys, never a
// text. The server logs it as `push probe`. Temporary: goes once the shape is known.
function probe(event, kind) {
    try {
        const n = event && (event.notification || event.proposedNotification);
        const data = n ? n.data : undefined;
        const body = {
            p: 1,
            e: kind,
            v: VERSION,
            keys: ["data", "notification", "proposedNotification", "appBadge"].filter((k) => event && k in event),
            data: !event || event.data === undefined ? "absent" : event.data === null ? "null" : "set",
            n: n
                ? {
                    title: typeof n.title,
                    data: data === null ? "null" : typeof data,
                    dataKeys: data && typeof data === "object" ? Object.keys(data) : [],
                    navigate: typeof n.navigate,
                    tag: typeof n.tag,
                }
                : null,
        };
        return fetch("/notifications/receipt", {
            method: "POST",
            credentials: "omit",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify(body),
        }).catch(() => undefined);
    } catch (e) {
        return Promise.resolve();
    }
}

// The push's notification and where it came from: the payload (`event.data`, every browser's
// legacy path), else the notification Safari proposed (`event.notification`: Safari 18.4+ reads a
// mutable declarative payload itself, fires `push` with `data` null and its proposed Notification
// on the event - WWDC25 session 235, the Push API draft; confirmed on the user's iPhone,
// 2026-10-19, where reading only `data` left every push unconfirmed), else the inbox's fallback.
function notificationOf(event) {
    try {
        const message = event.data.json();
        const n = message.notification;
        if (n && typeof n.title === "string") return { n, via: "push" };
    } catch (e) {
        // no data, or not JSON
    }
    const proposed = event && event.notification;
    if (proposed && typeof proposed.title === "string") {
        return {
            n: {
                title: proposed.title,
                body: proposed.body,
                lang: proposed.lang,
                tag: proposed.tag,
                navigate: proposed.navigate,
                data: proposed.data,
            },
            via: "push-declarative",
        };
    }
    return {
        n: { title: "Vibb Phone", body: "Something new - open Notifications", navigate: "/notifications" },
        via: "push",
    };
}

// The probe's second step: whether showNotification settled, and how (the user's iPhone, iOS 26,
// 2026-10-19: the first probe arrived with the right shape and nothing after showNotification
// did). Temporary, with `probe`.
function probeShown(via, outcome) {
    try {
        return fetch("/notifications/receipt", {
            method: "POST",
            credentials: "omit",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ p: 2, e: via, shown: outcome, v: VERSION }),
        }).catch(() => undefined);
    } catch (e) {
        return Promise.resolve();
    }
}

// Shows the notification, the badge and the receipt. A declarative push's receipt goes FIRST:
// Safari shows the notification whatever happens here - our replacement, or its proposed one when
// nothing is shown in time, never two - and on the user's iPhone nothing of the worker ran after
// showNotification in that path (the probe, 2026-10-19), so a receipt after it never came. The
// legacy path keeps show, then badge, then receipt: a notification we fail to show isn't shown.
async function show(event) {
    const { n, via } = notificationOf(event);
    const data = n.data || {};
    // `navigate` as an option, absolute and on this origin: WebKit's declarative path refuses a
    // replacement without a valid navigate URL (ServiceWorkerRegistration::showNotification:
    // "did not include NotificationOptions that specify a valid defaultAction url" - the probe's
    // TypeError on the user's iPhone, 2026-10-19), and then shows its proposed notification
    // itself. Other browsers ignore the option; the click handler reads it from `data` as before.
    const navigate = new URL(localPath(n.navigate), self.location.origin).href;
    const options = {
        body: n.body || "",
        lang: n.lang || "en",
        icon: "/static/icons/icon-192.png",
        navigate,
        data: { navigate, d: data.d, t: data.t },
    };
    if (n.tag) options.tag = n.tag;
    const declarative = via === "push-declarative";
    if (declarative) await receipt(data, "shown", via);
    let outcome = "ok";
    try {
        await self.registration.showNotification(n.title, options);
    } catch (e) {
        outcome = "failed:" + ((e && e.name) || "error");
        if (!declarative) throw e;
    }
    await probeShown(via, outcome);
    if (typeof data.b === "number" && self.navigator && self.navigator.setAppBadge) {
        try {
            await (data.b > 0 ? self.navigator.setAppBadge(data.b) : self.navigator.clearAppBadge());
        } catch (e) {
            // the badge is a nicety
        }
    }
    if (!declarative) await receipt(data, "shown", via);
}

// Inside waitUntil, always: Safari shows its declarative fallback only when the
// worker shows nothing, so this never doubles a notification.
self.addEventListener("push", (event) => {
    event.waitUntil(Promise.all([probe(event, "push"), show(event)]));
});

// WebKit's explainer names a `pushnotification` event for a mutable declarative push; shipping
// Safari (18.4-18.5) fires `push` with the proposed notification instead (above). Kept for a
// WebKit that moves to the explainer's name: nothing to show here - only the badge and the
// receipt, from the notification Safari built (its `data` is the payload's).
async function confirmDeclarative(event) {
    const n = (event && (event.notification || event.proposedNotification)) || {};
    const data = n.data || {};
    if (typeof data.b === "number" && self.navigator && self.navigator.setAppBadge) {
        try {
            await (data.b > 0 ? self.navigator.setAppBadge(data.b) : self.navigator.clearAppBadge());
        } catch (e) {
            // the badge is a nicety
        }
    }
    await receipt(data, "shown", "pushnotification");
}

self.addEventListener("pushnotification", (event) => {
    event.waitUntil(Promise.all([probe(event, "pushnotification"), confirmDeclarative(event)]));
});

async function open(path) {
    const windows = await self.clients.matchAll({ type: "window", includeUncontrolled: true });
    for (const client of windows) {
        try {
            const focused = await client.focus();
            await (focused || client).navigate(path);
            return;
        } catch (e) {
            // a window we can't steer: open a new one
        }
    }
    await self.clients.openWindow(path);
}

self.addEventListener("notificationclick", (event) => {
    event.notification.close();
    const data = event.notification.data || {};
    // The receipt goes first (it marks the event read), the page opens beside it.
    event.waitUntil(Promise.all([receipt(data, "clicked", "click"), open(localPath(data.navigate))]));
});

// No fetch handler at all - every request passes straight through to the
// network untouched, exactly as if this service worker didn't exist.
