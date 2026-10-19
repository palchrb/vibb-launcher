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

// The push's notification and where it came from: the payload (`event.data`, every browser's
// legacy path), else the notification Safari proposed (`event.notification`: Safari 18.4+ reads a
// mutable declarative payload itself and fires `push` with `data` null and its proposed
// Notification on the event - WWDC25 session 235, the Push API draft, WebKit's PushEvent.idl;
// confirmed on the user's iPhone, 2026-10-19), else the inbox's fallback.
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

async function badge(data) {
    if (typeof data.b !== "number" || !self.navigator || !self.navigator.setAppBadge) return;
    try {
        await (data.b > 0 ? self.navigator.setAppBadge(data.b) : self.navigator.clearAppBadge());
    } catch (e) {
        // the badge is a nicety
    }
}

// A declarative push (Safari): the receipt and the badge, and NO notification of ours. Safari
// shows its proposed notification itself - the same words, link and tag as the payload's - and
// that is the one iOS displays: a replacement shown from here is accepted (0.29.4's probe:
// showNotification resolved) and never appears on the user's iPhone (iOS 26, 2026-10-19, six
// tests), while a receipt posted after showNotification never arrived (0.29.2/0.29.3). So the
// worker does what only it can do, and leaves the showing to the platform, as the model intends.
// The payload's top-level `app_badge` sets the badge too, for a worker ITP has removed.
async function confirmDeclarative(n, via) {
    const data = n.data || {};
    await receipt(data, "shown", via);
    await badge(data);
}

// The legacy path: show (a notification we fail to show isn't shown, so the throw stands), then
// the badge, then the receipt.
async function showLegacy(n, via) {
    const data = n.data || {};
    const navigate = new URL(localPath(n.navigate), self.location.origin).href;
    const options = {
        body: n.body || "",
        lang: n.lang || "en",
        icon: "/static/icons/icon-192.png",
        navigate,
        data: { navigate, d: data.d, t: data.t },
    };
    if (n.tag) options.tag = n.tag;
    await self.registration.showNotification(n.title, options);
    await badge(data);
    await receipt(data, "shown", via);
}

async function show(event) {
    const { n, via } = notificationOf(event);
    if (via === "push-declarative") return confirmDeclarative(n, via);
    return showLegacy(n, via);
}

// Inside waitUntil, always: Safari shows its declarative notification itself; the legacy path
// shows ours.
self.addEventListener("push", (event) => {
    event.waitUntil(show(event));
});

// WebKit's explainer names a `pushnotification` event for a mutable declarative push; shipping
// Safari fires `push` with the proposed notification instead (above). Kept for a WebKit that
// moves to the explainer's name: the same receipt and badge, nothing shown.
self.addEventListener("pushnotification", (event) => {
    const proposed = (event && (event.notification || event.proposedNotification)) || {};
    event.waitUntil(confirmDeclarative({ data: proposed.data }, "pushnotification"));
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
    // The receipt goes first (it marks the event read), the page opens beside it. A declarative
    // notification Safari showed has its link on the notification, not in our `data`.
    const target = data.navigate || event.notification.navigate;
    event.waitUntil(Promise.all([receipt(data, "clicked", "click"), open(localPath(target))]));
});

// No fetch handler at all - every request passes straight through to the
// network untouched, exactly as if this service worker didn't exist.
