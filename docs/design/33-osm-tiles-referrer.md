# 33 - The Locate map's OSM tiles: a Referer for the tiles only

## Problem

The Locate page's map (`server/templates/device_locate.html`, vendored Leaflet 1.9.4) loads its tiles from
`https://tile.openstreetmap.org/{z}/{x}/{y}.png`. The admin pages send `Referrer-Policy: same-origin`
(`admin_auth::admin_headers`), so the browser sent no `Referer` with them. OSM now refuses browser tile requests
without one, so every tile came back as "403 Access blocked - App is not following the tile usage policy".

## The two options

1. **A tile proxy**: the server fetches and caches the tiles, and OSM sees only the server. This was built first
   (commit `7a4806a8`, reviewed in `docs/review/qa-33-code.md`). Code QA M1 found that OSM's tile usage policy
   (<https://operations.osmfoundation.org/policies/tiles/>, as published on 2026-10-13) discourages exactly that:
   - it discourages caching proxies in front of the tile servers;
   - it forbids stripping the `Referer`;
   - it lists one anonymous identity for many users as a "don't".

   The proxy also brought its own client, a disk cache with a size bound, a rate limit and their tests.
2. **A `Referer` for the tiles only**: the browser keeps loading tiles straight from OSM, and the tile images alone
   use a referrer policy that sends our origin.

## Decision (the user's, 2026-10-13): option 2

It follows OSM's policy as written: each parent's browser is a normal, identifiable OSM client with a `Referer`. It
is also far simpler: no code on the server, nothing cached, nothing to prune. The proxy was dropped completely. It
never shipped, so no install has a `data/tiles` to clean up.

## Behaviour

- **The tile layer**: `L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', {maxZoom: 19, referrerPolicy:
  'strict-origin-when-cross-origin', attribution: ...})`. The OSM attribution stays.
- **Leaflet passes the option on**: in Leaflet 1.9.4 (`static/leaflet/leaflet.js`), `TileLayer.createTile` does
  `if (typeof options.referrerPolicy === "string") img.referrerPolicy = options.referrerPolicy`. It does this before
  `img.src` is set, so the policy is on the element when the fetch starts. The default `false` sets nothing.
- **Why the element's policy wins over the header**:
  - HTML's image fetch ("update the image data") sets the request's referrer policy to the state of the `<img>`
    element's `referrerpolicy` attribute. The IDL property `img.referrerPolicy` reflects that attribute.
  - Fetch's "main fetch" uses the policy container's referrer policy only when the request's own policy is the empty
    string. That container policy is where the page's `Referrer-Policy` response header ends up.
  - So a non-empty per-element policy takes precedence for that one request. See:
    - <https://html.spec.whatwg.org/multipage/images.html#update-the-image-data>
    - <https://fetch.spec.whatwg.org/#main-fetch> (step "If request's referrer policy is the empty string, ...")
    - <https://w3c.github.io/webappsec-referrer-policy/#referrer-policy-delivery-nested>
    - MDN `HTMLImageElement.referrerPolicy` (<https://developer.mozilla.org/en-US/docs/Web/API/HTMLImageElement/referrerPolicy>)
    - MDN `Referrer-Policy` (<https://developer.mozilla.org/en-US/docs/Web/HTTP/Headers/Referrer-Policy>, "Integration
      with HTML")
  - `HTMLImageElement.referrerPolicy` is in Chrome 51+ and Safari 14+, inside the PWA's floor (Chrome/WebView 80,
    iOS Safari 14).
- **Unchanged**:
  - The document-wide `Referrer-Policy: same-origin`: every other link, form and fetch still sends nothing to
    another site.
  - `ADMIN_CSP`'s `img-src 'self' data: https://tile.openstreetmap.org`: one image host, nothing broader.
  - The service worker has no fetch handler, so tile requests pass straight through.
- **What OSM sees**:
  - the parent's IP address and browser `User-Agent`;
  - `Referer: <the PWA's origin>/`, for example `https://kidphone.tailnet-name.ts.net/` (the tailnet host name).
    The scheme, host and port go, but never the path (no device ids, no `?device=`) or the query.
  - the tile coordinates, so the area being looked at.

  `strict-origin-when-cross-origin` sends nothing on an HTTPS-to-HTTP downgrade, and OSM is HTTPS. A PWA served over
  plain HTTP (LAN testing) still sends its origin, since HTTP to HTTPS is no downgrade.
- **Network**: the parent's browser must reach `tile.openstreetmap.org` (HTTPS). The server never contacts OSM.

## Files

- `server/templates/device_locate.html`: the tile layer's `referrerPolicy`.
- `server/src/tests/admin_auth.rs`: the CSP's `img-src` is exactly `'self' data: https://tile.openstreetmap.org`.
- `server/src/tests/locations.rs`: `the_map_loads_osm_tiles_with_an_origin_only_referer`. It checks:
  - the Locate page's tile layer: the OSM URL, the `referrerPolicy` and the attribution;
  - the page's own `Referrer-Policy: same-origin`;
  - the CSP's image host.
- `server/CLAUDE.md` (the Find My Device bullet), `server/DEPLOY.md` (one line).
- Everything the proxy added (`src/tiles.rs`, its route, `AppState.tiles`, the pruning hook, its tests, the shared
  resolver) is gone. The server is as `main` had it, apart from the lines above.
