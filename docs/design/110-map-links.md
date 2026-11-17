# 110 - Google Maps and Apple Maps links open in Organic Maps, with the place's name and address (revision 3)

Round `map-links`, 2026-11-17. Launcher and server. **Status: design, revision 3.** Revision 2 went through design QA;
revision 3 isn't reviewed yet. PLAN: "Google Maps links open in Organic Maps" (approved 2026-11-17). The user asked:

- "Google Maps-lenker åpnes i Organic Maps ... kommer opp som et valg i app fanen i pwa, om man skal registrere [Vibb]
  som handler for alle google lenkene for å sikre at disse blir åpnet av organic maps"
- "da må vi åpne google maps lenkene i organic maps med litt mer metadata også, navn og addresse typ som google maps
  også har i sitt svar"
- (2026-11-17, later) "vi fikk til å åpne koordinater i organic maps, og så la vi bare til navnet senere i lenken og da
  ble det åpnet perfekt med en gang. det er slik det må være. vi må klare å finne koordinatene, hente ut navn og så
  bygger vi lenke med koordinater og navn og adresse. det er bra gui. at server ser hvor lenkene peker for en stund er
  greit nok, og 7 dager høres jo unødvendig langt ut. noen minutter burde vel holde"

**Revision 3** carries out the user's later message:

- **The output is always a pin.** It is `geo:lat,lon?z=17&q=lat,lon(Name, address)`, built from coordinates, name and
  address. Organic Maps' offline search is now only the honest fallback when the point can't be found. This supersedes
  2026-11-04's "search, one tap".
- **The server finds the point for Google's Android share (G9) and for G5.** It makes one request to Google's
  `/maps/preview/place` by the link's feature id (`ftid`), after the redirect. The answer gives the place's own point,
  its name and its address lines.
  - Measured from this VM and on the user's server (§4.2).
  - The parser reads the place's point field, checks it against the viewport field, and has fixtures from recorded
    answers, sanity checks, a fallback and a counter that shows when Google's format breaks (§8.3, §8.4).
- **The cache keeps an answer 10 minutes**, in memory.
- **Google's `tbm=map` text search** for a link without a feature id is left for later (§16).
- **Q2 (the title) is closed.** The user decided "navn og adresse". §15 has four questions.

**Revision 2** took every design QA finding. §17 maps each finding to its change:

- the coordinate rule: map-centre coordinates never give a pin;
- the resolver's own source kind and deadline;
- the browser named in "Open in browser";
- `target_state`;
- the switch-off row for Play services;
- the two filter groups;
- server versus phone resolving;
- migration `0155`;
- the design calls settled.

**Decided by the user on 2026-11-17:**

- **No reverse geocoding and no geocoding service** (no Nominatim, Photon or Kartverket). A link whose place Google's
  lookup can't find opens as a search, and a place without an address opens without one.
- **Apple Maps links are in scope**: `maps.apple.com` and Apple's short links (`maps.apple/p/...`).
- **Coordinates, name and address** make the link (above). The server may see where links point "for a while": the
  cache is minutes.

Agreed in the queue entry:

- Vibb One handles the links, pinned as the preferred activity as for UnifiedPush's link activity.
- Short links and the place lookup go through our server. The phone never talks to Google or Apple.
- Without Organic Maps the kid sees a calm note.
- The PWA switch sits on the phone's Apps page, offered only when Organic Maps is installed.

## 0. What came before

- **`docs/setup/android-settings.md`, "Google Maps links open in Organic Maps through GeoShare (2026-11-04)"**, commit
  `43f7f67b1`. This was the manual GeoShare path:
  - Play services holds `*.app.goo.gl` as a **system-configured** app link.
  - A user's selection doesn't beat that.
  - Turning off Play services' "Open supported links" made the link reach GeoShare, then Organic Maps.
  - This design supersedes that path. The section gets a line saying so, and that GeoShare should be removed (§6.3).
- **The 2026-11-04 field research** (the user's desktop in Norway, via the coordinator):
  - The Artbox Android share resolved to a `?q=<name, address>&ftid=` link without coordinates.
  - `geo:0,0?q=<address>` found the place offline.
  - Google's long HTML page carries the **requester's** IP location as `center=`.
- **The user's later research** (a conversation the user shared on 2026-11-17, read as data):
  - The user's own server got the place by feature id from `/maps/preview/place` in 239 ms. This is how the open-source
    `gmapsUnshortener` (`app/services/google.py`, `place_details()`) works.
  - `gmapsUnshortener`'s upstream (codeberg.org/retiolus/gmapsUnshortener) is **AGPL-3.0-only** according to its
    YunoHost package manifest (`retiolus/gmapsUnshortener_ynh`, `manifest.toml`). **No code is borrowed**: we take the
    facts (the endpoint and the answer's fields), and our code and fixtures are our own.
  - The conversation's resolver draft and tests (lines ~600-900 and ~1420-1820) were mined for forms only: `cid` as
    `0x0:0x<hex>`, `share.google`, directions with `!1d!2d`, and "never trust the page's HTML".
- **`docs/review/ideas-2026-11-03.md`** idea 3, **`docs/notes/server/notifications.md:574`** and
  **`docs/design/mockups/parent-pwa/Apps.dc.html:79`**: Organic Maps as a catalog app and its Apps row.
- **Design 07**: whether a web PPA beats a verified app link was open. §6.2 answers it from AOSP's source.

## 1. The flow

```
link tapped ──► MapLinkActivity (only while the parent's switch is on)
                 │  Meldinger's chip and Dagen's row start it directly; other apps' links
                 │  arrive through Android's link resolution (§6)
                 ├─ parse offline (§2, §3)
                 │    ├─ coordinates and an address already in the link ───────────────────────┐
                 │    ├─ a short link, or a Google link with a feature id but no point/address │
                 │    │     └─ POST /api/devices/map-links/resolve (§8)                        │
                 │    │          server: the short link's redirect (one GET per hop), then     │
                 │    │          one GET to /maps/preview/place by ftid → checked point,       │
                 │    │          name, address ─────────────────────────────────────────────── ┤
                 │    └─ nothing found ──► fallback: Organic Maps' offline search (§2.1) ──────┤
                 └─ unreadable / no target / offline ──► calm note (§7.4)                      ▼
                     geo:lat,lon?z=17&q=lat,lon(Name, address) → explicit VIEW to Organic Maps, NEW_TASK
```

- **The phone parses every link's own fields.** The server does three things only:
  - it turns a short link into its long link;
  - it extracts the feature id from that link (the shared `feature_ids` rows, §10);
  - it looks the place up by that id.
- **The host and feature-id checks exist on both sides**, each in its own code, tested against one shared file.
- **Nothing is fetched before a tap.**

## 2. Google Maps links: the forms and where the data sits

Sources: GeoShare's `GoogleMapsUriParser.kt` and its fixtures (`jakubvalenta/geoshare`, GPL-3.0, `main` pushed
2026-11-15), Google's "Maps URLs" [web], §4's measurements, and the user's research.

**The coordinate rule:**
- **A pin** comes from coordinates that name the place: `!3d<lat>!4d<lon>` (or `!1d<lon>!2d<lat>`); `q`/`query` as
  lat,lon; `ll` as lat,lon without a text `q`; `destination`/`daddr` as lat,lon; a `/place/<lat>,<lon>` segment; or the
  **server's checked lookup** (§8.3). G4 has one fallback: the `@` viewport, and only when a place segment exists.
- **`sll`, `center`, `viewpoint`, and `ll` next to a text** only centre a fallback search.

**The feature id** (`0x<16 hex>:0x<1-16 hex>`) sits in:
- `ftid=`;
- `data=…!1s0x…:0x…` in `/maps/place/…`;
- the destination's `!1s` in `/maps/dir/…`.

A `cid=<decimal>` is the id's second half, sent as `0x0:0x<hex>`. That is the user's research draft; it is unverified
here, so G1b measures it.

| # | Form | In the link | Opens as |
|---|---|---|---|
| G1 | `maps.app.goo.gl/?link=<encoded long URL>` | inside `link` | unwrapped offline |
| G2 | `/maps/dir/…`, `?api=1&destination=`, `?saddr=&daddr=` | the destination's name, point or id | **the destination** (decided: no route starts by itself) - a pin from its point; else a lookup by its id; else the fallback |
| G3 | `?q=<lat>,<lon>`, `?query=`, `?ll=` (no text `q`) | the point; a `/place/<seg>` label if any | pin |
| G4 | `/maps/place/<seg>/@…/data=…!1s<ftid>…!3d<lat>!4d<lon>…` | point, `<seg>` (a name, or name and address), the id | pin. **If `<seg>` holds no address** (no digit after a comma), the server's lookup adds name and address. The link's point stays |
| G5 | `/maps/place/<seg>/data=!4m2!3m1!1s<ftid>` (no point) | `<seg>`, the id | **lookup**: pin "name, address"; else the fallback |
| G6 | `/maps/place/<lat>,<lon>/…` (a dropped pin) | the point | pin, no label |
| G7 | `/maps/place/<plus code>` with no point | none | the "no place" note (decided: no decoder - a real dropped-pin share carries its point, as `Q6Zug…` did) |
| G8 | `/maps/search/<text or lat,lon>` | text or point | pin, or the fallback |
| G9 | **`maps.google.com?q=<name, address>&ftid=<id>&entry=gps`** (the Android app's share, the common case; also `maps.google.com/maps?q=…` and `www.google.com/maps?q=…&ucbcb=1`) | the text, the id | **lookup**: pin "name, address" from the answer; else the fallback |
| G10 | `/maps/@<lat>,<lon>,<z>z`, `map_action=map&center=`, Street View, with no text | the centre | pin, no label |
| G11 | `?cid=<n>` alone | the id's second half | lookup by `0x0:0x<hex>` if G1b confirms it; else the "no place" note |
| G12 | `/maps/placelists/…`, `/maps/@/data=…!2s…`, My Maps `/maps/d/…` | a list | the "list" note |

- **G10 keeps a pin.** QA 2 is taken in part: a shared map view with no text points only at its centre.
- **Hosts** (`long_hosts`):
  - `maps.google.<tld>` takes any path, and an empty path with `q`/`query`/`ll`/`cid`/`ftid`.
  - `www.google.<tld>` and `google.<tld>` need `/maps…`.
  - Google Search (`/?q=`, `/search`, `share.google`, `g.co/kgs`) isn't a map link here (§16).
- **The label.**
  - From a lookup: the answer's name and its address lines, joined with ", " ("Artbox AS, Drammensveien 133, 0277
    Oslo").
  - From a link: its text.
  - Cut to 120 characters at a word. Meldinger's chip shows the part before the first comma first.
- **China's GCJ-02 offset** isn't handled.

### 2.1 The fallback search

The fallback is used when no point is found: no feature id, a failed lookup or check, or the server unreachable.

- **The form** is `geo:0,0?q={query}`, or `geo:{lat},{lon}?q={query}` with a centre. Organic Maps searches offline and
  the kid taps the hit.
- **The centre**, in order:
  - the link's `sll`/`center`/`ll`-beside-text;
  - else the feature id's coarse S2 cell centre, kept to its first 20 bits, about 40 km. This is offline: measured
    against four places with known points (24, 31, 33 and 42 shared bits; §4.2).
  - else none.
- **The query candidates** (`map_link_rules.json`, `query_parts`):
  - (a) the whole text;
  - (b) the part after the first comma when it holds a digit ("Drammensveien 133, 0277 Oslo");
  - (c) the part before it.
  - The rule is (b), else (a): 2026-11-04 found Artbox's address first with (b). Gate G12 checks it.
- **Encoding**: spaces as `%20`, everything but `A-Za-z0-9-._~` percent-encoded as UTF-8.

## 3. Apple Maps links

Sources: GeoShare's `AppleMapsUriInput.kt` and its tests, and Apple's "Map Links" reference [web] (`sll` = search
location, `near` = a search hint).

| # | Form | Name / address | Opens as |
|---|---|---|---|
| A1 | `maps.apple.com/place?name=…&address=…&coordinate=<lat>,<lon>` (iOS 18+ shares) | `name`, `address` | pin, label "name, address" |
| A2 | `maps.apple.com/?ll=<lat>,<lon>[&q=<name>]`, `?coordinate=`, `?q=<lat>,<lon>` | `q` text, `name`, `address` | pin at `ll` > `coordinate` > `q` |
| A3 | `maps.apple.com/?q=<text>` / `?address=<text>`, with or without `sll`/`near`/`center` | the text | the fallback, centred at `sll`/`near`/`center` if given |
| A4 | `maps.apple.com/directions?…&destination=…`, `?daddr=` | the destination | the destination (as G2) |
| A5 | `maps.apple/p/<id>` | - | the server resolves it (§4): a 301 to an A1 URL with `coordinate` and `address` |
| A6 | `maps.apple.com/place?auid=…` / `?place-id=…` alone | none | the "no place" note (Apple's page isn't read) |

- The label drops a leading "name, " repeated in `address`. Apple's dropped-pin name ("Marked Location", "Markert
  sted") isn't a name.
- **Apple shares carry their coordinates**, so no lookup is needed for Apple.

## 4. What the server sends, measured

### 4.1 Short links (from the build VM, 2026-11-17)

| Short link | Request | Answer |
|---|---|---|
| `https://maps.app.goo.gl/ZtCcrVTKt54Dx2hVA` (the user's Artbox share) | GET, `User-Agent: vibb/1.0` | `302`, one hop → `https://maps.google.com?q=Artbox+AS,+Drammensveien+133,+0277+Oslo&ftid=0x46416db4338a09ff:0x7d7d788043d1f40e&entry=gps&…` (G9) |
| `https://maps.app.goo.gl/mBtbC6qXLK2baGTV9` (GeoShare's test link) | GET and HEAD | `302` → `/maps/place/Steak+no+Don/@…/data=…!1s0x3551565394d96b57:0x1c9bdbc5990908bf!8m2!3d34.5945482!4d133.7583428…` (G4) |
| `https://maps.app.goo.gl/FP3EV7tTUKYbmcVp7` | GET | `302` → `/maps/search/39.920439,+116.331538…` (G8) |
| `https://maps.app.goo.gl/Q6ZugPBVWvuiVb8e8` | GET | `302` → `/maps/place/42.578496,1.895566/data=…!3d42.5784957!4d1.8955661…` (G6) |
| `https://maps.apple/p/7E-Brjrk_THN14` | **HEAD: `404`**; GET with `vibb/1.0` | `301` → `https://maps.apple.com/place?address=Thomashöhe,+12053+Berlin,+Germany&coordinate=52.4737758,13.4373898` (A1) |

- **One GET per hop**, redirects not followed, `Location` read, the body not kept. Up to 3 hops. A hop to a short-link
  host is rebuilt through `short_links`. Anything else is `dead`.

### 4.2 The place lookup (this VM is geolocated to Norway: Google answers `gl=NO`)

| Request (`vibb/1.0`, no cookies) | Answer |
|---|---|
| The long page `maps.google.com?q=…&ftid=…` (G9's `Location`) | `302` → `maps.google.com/maps?q=…`, then **`302` → `consent.google.com/ml?continue=…&gl=NO`**. The same for `www.google.com/maps?q=…`, `/maps/?q=…`, `/maps/search/…` and `/maps/place/…`, and with a desktop or Android browser `User-Agent` |
| `https://www.google.com/maps/preview/place?authuser=0&hl=no&gl=no&q=<the text>&pb=!1m15!1s<ftid>!2s<the text>!3m12…` (the `<link>` Google's own page names, taken from a page fetched earlier in this session) | **`200`, no consent page, `application/json`, 23,839 bytes, 0.20 s**, `)]}'`-prefixed |
| The user's measurement on their server (via the coordinator): `…/maps/preview/place?pb=!1m2!1s0x46416db4338a09ff:0x7d7d788043d1f40e!2s&authuser=0&hl=en` | `)]}'` JSON in **239 ms**, with the same fields |

**What the answer holds** (this VM's recording, Artbox):

| Path | Value | What it is |
|---|---|---|
| `d[6][9]` | `[null, null, 59.922361499999994, 10.6868892]` | **the place's own point** (lat, lon) |
| `d[4][0]` | `[1999.589…, 10.6868892, 59.9223615]` | the viewport: scale, lon, lat. Here 0.0 m from `d[6][9]` |
| `d[6][10]` | `0x46416db4338a09ff:0x7d7d788043d1f40e` | the feature id, echoed |
| `d[6][11]` | `Artbox AS` | the name |
| `d[6][2]` | `["Drammensveien 133", "0277 Oslo"]` | the address lines |
| `d[6][18]` | `Artbox AS, Drammensveien 133, 0277 Oslo` | name and address joined |
| `d[6][13]`, `d[6][30]` | `["Grafisk designer"]`, `Europe/Oslo` | category, time zone (not used) |

**Is it the place, or the requester?**

- The earlier page from this VM (fetched once with a tool `User-Agent`, 222,963 bytes, 0.32 s) had **no point for the
  place**.
  - It had only `center=59.80735225,10.46334695` and `APP_INITIALIZATION_STATE`, which is **the requester's location**:
    this VM, near Asker, 17.9 km from the place. That matches 2026-11-04's Asker/Vollen.
  - It also had the generic `<meta content="Google Maps" itemprop="name">`, which is GeoShare's own sign of that trap.
- `preview/place`'s point is 17.9 km from that centre, at Drammensveien 133.
- Independently of Google's answer, the point shares **31 leading bits** with the S2 cell in the feature id's first
  half. The IP centre shares 17.
  - Across the four places with known points, the shares are 24 (RAI, Bucharest), 31 (Artbox), 33 (Central Park) and
    42 (Steak no Don).
- **Not verified yet**: the lookup for a place whose `!3d!4d` is known (Steak no Don, `0x3551565394d96b57:0x1c9bdbc5990908bf`
  → 34.5945482, 133.7583428; RAI → 44.4490541, 26.0888398).
  - This session's permission guard refused further requests to Google's endpoint from this VM.
  - **Gate G1b** runs them on the user's server, with the minimal `pb=!1m2!1s<ftid>!2s` form, `hl=no` and `hl=en`, and
    a `cid` as `0x0:0x<hex>`.
- **Not used:** the long HTML page (consent-walled from Norway for an honest `User-Agent`, 223 KB, and only the
  requester's location in it). The server **never sends consent cookies, never submits the consent form, and never
  takes a point from a page.**
- **robots.txt** (`www.google.com/robots.txt`, fetched 2026-11-17, `User-agent: *`): `Allow: /maps/preview`,
  `Allow: /maps/place/` and `Allow: /search?*tbm=map`. `/maps?q=` is allowed too. So the lookup isn't against
  Google's robots rules.
  - It is still an **undocumented** interface. It can change or refuse without notice, which is why §8.3 and §8.4 exist.

## 5. What Organic Maps takes and shows

Read from `organicmaps/organicmaps` at `a304448595da95e93b4dbcd85b736b6b05b053e0` (master, 2026-11-17):

- **The manifest**: `VIEW` takes `geo`, `geo.offline`, `ge0`, `om`, `geo.action` and `mapsme`; https takes `ge0.me`,
  `omaps.app` and `www.openstreetmap.org`. **No Google or Apple host.**
- **`libs/ge0/geo_url_parser.cpp`**:
  - a trailing `(label)` is cut at the last `(` of the raw encoded string, then decoded, so an encoded `(`, `)` or `&`
    is safe;
  - `q=` lat,lon gives the point, `q=` text a query;
  - `z=` is clamped to 1-20.
- **`libs/map/mwm_url.cpp`**: a label gives a Map point named by it. OM's own test is `geo:0,0?q=35.341714,33.32231
  (Custom%20Title)` → a point named "Custom Title" (`mwm_url_tests.cpp:423`). A query gives a Search, centred if lat,lon.
- **The card**: `Framework::FillApiMarkInfo` (`framework.cpp:995`) → `SelectionProcessor::FillPointInfo`
  (`selection_processor.cpp:149`).
  - An OSM feature under the pin fills the card (address, hours, phone, web), with our label as the title.
  - Otherwise the card has the label and the coordinates.
  - That matches what the user saw: coordinates, then the name added, "åpnet perfekt med en gang".
- **CoMaps** (`codeberg.org/comaps/comaps`, `main`): `geo`, `ge0`, `cm`, `comaps`, `ge0.me`, `comaps.at`. F-Droid id
  `app.comaps.fdroid`. Organic Maps is `app.organicmaps`.

**The one form:**

| Case | URI |
|---|---|
| a pin with a label (the goal) | `geo:{lat},{lon}?z={z}&q={lat},{lon}({label})` |
| a pin without one | `geo:{lat},{lon}?z={z}` |
| the fallback | `geo:0,0?q={query}` / `geo:{lat},{lon}?q={query}` |

- Six decimals. `z` is the link's zoom clamped to 3-19, else 17.
- The intent is `Intent(ACTION_VIEW, uri).setPackage(target).addCategory(BROWSABLE).addFlags(FLAG_ACTIVITY_NEW_TASK)`.

## 6. Where links reach the kid, and how they get to Vibb One

### 6.1 The four paths

1. **Meldinger.** With the switch on and an allowed target, a map link gets one map chip under the bubble's text (Q1).
   - No Android resolution is involved and no phone step is needed.
   - `calls.md:287` ("never … linked") gets this one exception.
2. **Element X and other apps.**
   - A plain `VIEW` goes through §6.2.
   - A **Custom Tab** is `setPackage`d and skips the resolution.
   - With Chrome hidden (design 102) apps fall back to `VIEW`. With Chrome as the login browser, a Custom Tab may open
     the link in Chrome. G11 and G11b measure Element X. The PWA says so if G11b shows it.
3. **Dagen.** A map link in an event's location or description gets a "Kart" row. A plain address doesn't (§16).
4. **A browser**: none needed.

### 6.2 Android's resolution, from AOSP's source (main, fetched 2026-11-17; the enforcer rechecked on `android14-release`)

- **The domain filter**: `CrossProfileIntentResolverEngine.filterCandidatesWithDomainPreferredActivitiesLPrBody`, for
  intents that `DomainVerificationUtils.isDomainVerificationIntent` accepts.
  - Approval levels: NONE 0 < LEGACY 1-2 < **SELECTION 3** < **VERIFIED 4**. The highest wins, and a tie is cut to one
    app.
  - Approved apps alone are candidates. With none approved, the default browser (or all browsers). Only if that is
    empty, every candidate.
- **The PPA** (`ResolveIntentHelper.chooseBestActivity`):
  - one candidate is returned at once;
  - with several, a higher filter priority wins first, and only then the PPA, among the candidates.
  - So a PPA can't beat an approved app or a browser.
- **Approval drops to NONE** for a disabled or **suspended** package, or with "link handling allowed" off
  (`approvalLevelForDomainInternal`). A **hidden** package isn't a candidate at all. This answers design 07: the Play
  link blocker wins because the Play Store is suspended.
- **Reading another app's state is allowed** (`getDomainVerificationUserState`, API 31, visibility only). Changing it
  needs a permission a device owner lacks.
- **Settings won't select a host another app holds VERIFIED.**

| Link | Approved today | What wins |
|---|---|---|
| `maps.app.goo.gl/…` | Play services, system-configured; never suspended (`neverRestrict`) | Play services - nothing Vibb can change from code |
| `www.google.com/maps/…`, `maps.google.com/…` | Google Maps if allowed; perhaps the Google app or Play services for some hosts (G2) | that app; else a browser; else every candidate (the PPA picks Vibb) |
| `maps.apple.com/…`, `maps.apple/p/…` | nobody | as above |

**The two one-time steps on the phone**, in a pause (Q2). Each is a row in Vibb One's PIN-gated Settings that opens
Android's page (`Settings.ACTION_APP_OPEN_BY_DEFAULT_SETTINGS`, `package:<pkg>`; `BatteryExemption.kt`'s
`systemPageIntent` path):

1. **Google Play services › Open by default › "Open supported links" off.**
   - All its declared links drop to NONE. G2 records the list, of which `*.app.goo.gl` (Firebase Dynamic Links, shut
     down August 2025) is known.
   - FCM and sign-in keep working.
2. **Vibb One › Open by default › "Open supported links" on, every selectable link ticked.**
   - Vibb then holds SELECTION and, after step 1, is the only approved app for a plain `VIEW`.
   - GeoShare's old selections move. The setup note says to remove GeoShare.

**The PPA** is set on every `apply()` and in `HomePin`'s re-seat: the sixth step, with two `IntentFilter`s,
`PlayInvariantsTest`.

### 6.3 Turning the switch off

- **Step 1 stays.** A short link then goes to a browser or nowhere, not to Play services.
- **The row to undo it.** While the switch is off and Play services' handling reads off, the PIN-gated Settings keeps
  one row, "Let Google Play services open links again", and the PWA's off-state line names it (Q4).
- **Step 2** is harmless with the activity disabled.
- G7 checks this.

## 7. The launcher

### 7.1 `map/MapLinkActivity`

- **Behaviour.** Translucent, main process, `noHistory`, `excludeFromRecents`, no `taskAffinity`, extending
  `Activity`. **Installed disabled**, and enabled only while `map_links.on` comes from a policy carrying the parent's
  choice (`parentChoice`, design 108 §5.2).
- **Input**: `intent.data`, at most 2,048 characters, https (http upgraded).
- **Two `<intent-filter>`s**, `VIEW`, `DEFAULT`, `BROWSABLE`, http/https, no `autoVerify`, with matching PPA
  `IntentFilter`s:
  - **any path**: `maps.app.goo.gl`, `maps.google.com`, `maps.google.{no,se,dk,fi,de,co.uk}`, `maps.apple.com`,
    `maps.apple`;
  - **path prefix `/maps`**: `www.google.com`, `google.com`, `www.google.{no,se,dk,fi,de,co.uk}`, `goo.gl`,
    `app.goo.gl`.
- **The order** (pure `mapLinkPlan`; each step a test row):
  1. If `LockReason != NONE`, Vibb is LOCKED, or `KeyguardManager.isKeyguardLocked`: finish, showing nothing.
  2. Parse offline (`MapLinks.parse`, `map_link_rules.json`). This gives a pin, a lookup need (`{url}` for a short
     link, `{ftid, text}` for G2/G4/G5/G9/G11 without a point or an address), the fallback, or a note.
  3. If a lookup is needed: `POST /api/devices/map-links/resolve` (§8).
     - After 300 ms, "Åpner kartet …" / "Opening the map …".
     - Give up after 6 s.
     - The request is cancelled in `onStop`, and nothing starts after it (a late start would be a blocked background
       start). The server's cache makes a second tap instant.
  4. Build the `geo:` URI.
     - The server's `place` gives a pin with "name, address".
     - The link's own point and text give a pin.
     - The server's `url` without a `place` is parsed like a long link: a pin if it has a point, else the fallback.
     - An unreachable server gives the fallback when the link has text, else the note.
  5. Start the target with `FLAG_ACTIVITY_NEW_TASK`, then `finish()`.
- **The target**:
  - `app.organicmaps`, else CoMaps (`app.comaps.fdroid`, `app.comaps`; Q3);
  - `target_state` is `allowed`, `hidden`, `suspended` or `missing`;
  - only `allowed` starts.

### 7.2 Not a restriction, and a pause

- The handler blocks nothing that worked before. The parent turns it off in the PWA.
- **During a pause or the parent code's override**, every note gains "Åpne i nettleseren" / "Open in browser" when a
  browser resolves.
- **The browser is named** (`resolveActivity` of `https://example.com` with `BROWSABLE`, else `CATEGORY_APP_BROWSER`;
  `setPackage(browser)`), and the original link is used. The button never loops into Vibb, which `mapLinkPlan`'s test
  pins.

### 7.3 Meldinger and Dagen

- `MapLinks.find(text)` finds a bubble's first two map links.
- **The chip**: a map glyph and the label (before the first comma, the rest smaller), or "Åpne stedet" / "Open the
  place" for a link without text. It shows for an `allowed` or `suspended` target.
- `KidScreensEscapeTest` adds `map/` to `guarded`. The Settings rows stay in the PIN-gated Settings.
- **Dagen**: each map link in an event's location or description gets a "Kart" / "Map" row.

### 7.4 The kid's words (one calm sheet, one OK; the kid's look, design 78)

| When | nb | en |
|---|---|---|
| target `missing` or `hidden` | "Kartet kan ikke åpnes her. Spør en voksen om Organic Maps." | "This map can't be opened here. Ask a grown-up about Organic Maps." |
| target `suspended` | "Organic Maps er ikke tilgjengelig akkurat nå." | "Organic Maps isn't available right now." |
| no place (G7, G11 without a lookup, A6) | "Fant ikke noe sted i denne lenken." | "Couldn't find a place in this link." |
| a list (G12) | "Dette er en liste med steder. Den kan ikke åpnes i Organic Maps." | "This is a list of places. It can't be opened in Organic Maps." |
| short link `dead` | "Denne lenken virker ikke lenger." | "This link doesn't work any more." |
| a short link while unreachable, `busy`, `off`, offline or timed out | "Fikk ikke åpnet lenken nå. Prøv igjen når du har nett." | "Couldn't open the link right now. Try again when you're online." |
| waiting | "Åpner kartet …" | "Opening the map …" |
| chip, no text | "Åpne stedet" | "Open the place" |
| Dagen's row | "Kart" | "Map" |
| pause only | "Åpne i nettleseren" | "Open in browser" |

- CoMaps' name replaces "Organic Maps" when it is the target.
- A 409 `off` also asks for a policy sync. The activity is never disabled on a 409 alone.
- **The fallback search shows no note.** Organic Maps' search list is the answer.

**The PIN-gated Settings' group "Kartlenker" / "Map links":**

| Row | nb (sub-line) | en (sub-line) |
|---|---|---|
| step 1 | "Google Play-tjenester" ("Slå av «Åpne støttede linker»" / "Ferdig") | "Google Play services" ("Turn off "Open supported links"" / "Done") |
| step 2 | "Vibb Ones lenker" ("Slå på «Åpne støttede linker» og kryss av alle" / "Ferdig") | "Vibb One's links" ("Turn on "Open supported links" and tick them all" / "Done") |
| §6.3, switch off | "Gi Google Play-tjenester lenkene tilbake" ("Slå på «Åpne støttede linker» igjen") | "Let Google Play services open links again" ("Turn "Open supported links" back on") |

### 7.5 The report and the policy

- **Capability `map_links_v1`.**
- **The status report's `map_links`** (managed phones):
  - `target` and `target_state`;
  - `play_services_links` (`true`, `false`, or `null` when unreadable);
  - `vibb_hosts` `{selected, selectable, declared}`, where a host is selectable unless `resolveActivity` lands on
    another app's VERIFIED handler;
  - `reach` `{short, google, apple}`.
- **`reach` moves** with Google Maps or Chrome being allowed, with a pause, and with the target's state. The PWA
  ignores `reach` from a pause-time report.
- **The policy**: top-level `map_links: {"on": bool}`, served only to a launcher claiming `map_links_v1`. Absent means
  off.

## 8. The server

### 8.1 The route `POST /api/devices/map-links/resolve`

- **Request.** Device-authenticated, body at most 1 KB, https only. One of:
  - `{"url": "https://maps.app.goo.gl/AbC_12"}`, a short link (`short_links`);
  - `{"ftid": "0x46416db4338a09ff:0x7d7d788043d1f40e", "text": "Artbox AS, Drammensveien 133, 0277 Oslo"}`, a long
    link's id, with its text (at most 200 characters) for §8.3's text check only.
- **Answers:**

| Status | Body | Phone |
|---|---|---|
| 200 | `{"url"?: "<long URL>", "place"?: {"lat", "lon", "name"?, "address"?}}` | `place`: pin "name, address". No `place`: parse `url` (pin or fallback) |
| 409 | `{"error": "off"}` | the "try again" note, a policy sync |
| 422 | `{"error": "not_short_link"}` / `{"error": "dead"}` | the "doesn't work" note |
| 429 | `{"error": "busy"}` | a `{url}` gets the "try again" note; an `{ftid}` gets the fallback |
| 503 | `{"error": "unreachable"}` | as 429 |

- A failed lookup is **not an error**. The answer is 200 without `place`, and the phone falls back.
- **The short link**: rebuilt from host and id (`[A-Za-z0-9_-]{1,64}`). Up to 3 hops, as `spotify::resolve_short_link`.
  The `Location` must be an absolute https map URL on `long_hosts`, at most 2,000 characters, without user info or a
  port. A short-link host is rebuilt again.
- **Then the lookup**: the server extracts the feature id from the long URL (`feature_ids`). If there is one, and the
  URL has no point or no address, it looks the place up (§8.3).
- **The clients** (`music_net`'s source; QA 3), with `Reach::Public`, `follow = false` and `net::USER_AGENT`
  (`vibb/1.0`); `network_error`'s `without_url()` keeps ids out of logs:
  - **`Kind::ShortLink`**: 16 KB body cap, 3 s connect, 5 s overall.
  - **`Kind::PlacePreview`**: 256 KB body cap (the answer was 24 KB), 3 s connect, 4 s overall.
  - The connect timeout becomes part of `Kind`; the rest keep 10 s.
- **One deadline**: hops and the lookup together inside one `tokio::time::timeout` of 5 s. A lookup that runs out
  returns the `url` without a `place`.
- **Its own gate** (a `Gated`, as `weather_fetch`): one request in flight, 200 ms apart. `busy` at once if one is
  already waiting (`try_acquire`).
- **Limits**:
  - 30 resolves per device an hour;
  - 100 lookups to Google server-wide an hour, which keeps the household IP far from any rate wall;
  - then `busy`, or for an `{ftid}`, a 200 without `place`.
- **The cache**: in memory, **10 minutes** for a found answer and 1 minute for a failure. Keyed by the rebuilt short
  URL or the feature id, at most 500 entries. It survives a phone that gave up.
- **The log** has the host and outcome only. No id, no text, no coordinates.

### 8.2 The switch and the store

- **Migration `0155_map_links.sql`** (`0153`/`0154` are `up-r2r3`'s; recheck at build time):
  - `ALTER TABLE devices ADD COLUMN map_links_on INTEGER NOT NULL DEFAULT 0`;
  - `CREATE TABLE map_link_lookups (day TEXT NOT NULL, outcome TEXT NOT NULL, n INTEGER NOT NULL, PRIMARY KEY (day,
    outcome))`, kept 30 days.
- **The save**: `POST /devices/{id}/map-links`, returning to `#map-links`.
- **The report's `map_links`** is cleaned field by field (`clean`). `intake_limits` keeps its keys.

### 8.3 The lookup's parser and checks

- **The request**: `GET https://www.google.com/maps/preview/place?pb=!1m2!1s<ftid>!2s&authuser=0&hl=<en|no>`.
  - This is the user's minimal form. G1b confirms it from the server and decides `hl` (the parent's language: "no" or
    "en").
  - Only the feature id leaves the server, never the text.
- **The parse.** `200`, `application/json`, the body starting `)]}'`, then JSON (`serde_json::Value`, no typed
  structs). Every read is a checked path; any miss means `format`. The fields:
  - **the point**: `d[6][9]` = `[null, null, lat, lon]`, the place's own point. It must be within 200 m of `d[4][0]` =
    `[scale, lon, lat]`, the viewport, which centres on the place;
    - only `d[4][0]` present: used only if every check below passes (the `gmapsUnshortener`-shaped answer);
    - over 200 m apart: `d[6][9]` wins, and the counter notes `disagree`;
  - **the echo**: `d[6][10]` must equal the requested feature id (for `0x0:0x<hex>`, the second half);
  - **the name**: `d[6][11]`, a string of at most 120 characters, else none;
  - **the address**: `d[6][2]`, a list of 1-4 strings, joined with ", ", else none.
- **The checks.** Any failure gives no `place`, the fallback, and a counter outcome:
  - `echo`: the feature id isn't echoed;
  - `range`: lat/lon out of range, or within 0.001° of 0,0;
  - `far`: the point shares fewer than **20** leading bits with the feature id's first half as an S2 cell id;
    - measured 24, 31, 33 and 42 for real places, and 17 for this VM's IP centre;
    - skipped for `0x0:` ids;
  - `text`: the link's text has a house number or postcode part, and the answer's address lines share neither its
    4-digit postcode nor its street word (case and diacritics folded). This is the "near what the text says" check
    without any geocoding.
- **What the phone gets**: `place.name` and `place.address` from the answer. The label is "name, address".

### 8.4 Noticing that it broke

- **The counter.** Every lookup adds one to `map_link_lookups(day, outcome)`. The outcomes are `found`, `consent` (a
  302 to `consent.google.com`), `http` (non-200), `format`, `echo`, `range`, `far`, `text`, `unreachable` and `busy`.
  No link, id or text is stored.
- **The PWA line.** The Map links group shows a server-wide line when, in the last 24 hours, there were at least 3
  lookups, none `found`, and the rest `consent`/`http`/`format`:
  - en "Google's place lookup isn't working right now. Map links open as a search in Organic Maps." (orange)
  - nb "Googles stedsoppslag virker ikke nå. Kartlenker åpnes som et søk i Organic Maps." (oransje)
- **The numbers.** The Settings hub's server page shows the last 7 days' counts by outcome.
- A broken format is fixed by a server release with a new recording, and nothing changes on the phones.

## 9. The PWA

- **The group.** Device › Apps gets "Kartlenker" / "Map links" (`partials/map_links_group.html`), after "App push".
  - It shows when the launcher claims `map_links_v1` and reports a `target` in any state, or when the switch is on.
- **The switch** is an auto-saving form to `#map-links` (design 103).
  - en "Open map links in Organic Maps", sub "Google Maps and Apple Maps links open as a pin with the place's name and
    address in Organic Maps. This server looks up short links and places; the phone never asks Google."
  - nb "Åpne kartlenker i Organic Maps", sub "Lenker fra Google Maps og Apple Maps åpnes som en nål med stedets navn og
    adresse i Organic Maps. Denne serveren slår opp korte lenker og steder; telefonen spør aldri Google."
- **Off, with `play_services_links: false`:**
  - en "Google Play services' links are still off on the phone. To give them back: during a pause, Vibb One's Settings
    › Map links."
  - nb "Google Play-tjenestenes lenker er fortsatt av på telefonen. For å gi dem tilbake: under en pause, Vibb Ones
    innstillinger › Kartlenker."
- **On**, first match, driven by `reach` first. Pause-time reports are skipped:

| Condition | en | nb |
|---|---|---|
| no report yet | "Waiting for the phone." | "Venter på telefonen." |
| `target_state` `missing` | "Organic Maps isn't on the phone any more." (orange) | "Organic Maps er ikke på telefonen lenger." (oransje) |
| `target_state` `hidden` | "Organic Maps isn't allowed on this phone. Allow it above." (orange) | "Organic Maps er ikke tillatt på telefonen. Tillat den over." (oransje) |
| `reach.short` false, `play_services_links` not `false` | "One step left on the phone: during a pause, open Vibb One's Settings › Map links › Google Play services." (orange) | "Ett steg igjen på telefonen: under en pause, åpne Vibb Ones innstillinger › Kartlenker › Google Play-tjenester." (oransje) |
| a class false, `selected < selectable` | "One step left on the phone: during a pause, open Vibb One's Settings › Map links › Vibb One's links." (orange) | "Ett steg igjen på telefonen: under en pause, åpne Vibb Ones innstillinger › Kartlenker › Vibb Ones lenker." (oransje) |
| `reach.google` false, `selectable < declared` | "Google Maps links open in another app on this phone (Google Maps is allowed)." | "Google Maps-lenker åpnes i en annen app på denne telefonen (Google Maps er tillatt)." |
| ready | "Ready." | "Klar." |

- **The lookup line** of §8.4 shows above these when it applies.
- **The info panel**:
  - en "Links in Meldinger already open in Organic Maps. The phone steps are for links from other apps. Turning off
    Google Play services' links also stops it opening its other short links."
  - nb "Lenker i Meldinger åpnes allerede i Organic Maps. Stegene på telefonen gjelder lenker fra andre apper. Når
    Google Play-tjenestenes lenker slås av, åpner den heller ikke sine andre korte lenker."
  - If G11b shows Custom Tabs: en "Element X may open map links in Chrome while Chrome is the sign-in browser.", nb
    "Element X kan åpne kartlenker i Chrome mens Chrome er nettleser for innlogging."
- **The footer**:
  - en "The place's name and address come from the link or from Google, looked up by this server. If Google can't find
    it, Organic Maps searches for it offline."
  - nb "Stedets navn og adresse kommer fra lenken eller fra Google, slått opp av denne serveren. Finner ikke Google
    stedet, søker Organic Maps etter det uten nett."

## 10. The contract

**`server/testdata/map_links.json`**, byte-identical in `launcher/app/src/test/resources/`:

- `capability`, `policy` (`off`, `on`), `older_server`;
- `report`, `report_steps_left`, `report_hidden`, `report_held_by_maps`;
- `clean`, `short_links`, `long_hosts`;
- **`feature_ids`** (URL → ftid or `null`: `ftid=`, `data=…!1s`, `/dir/`'s destination, `cid` → `0x0:0x<hex>`);
- `resolve` (`request` in both shapes, `answers` with and without `place`, and `phone`, each answer's action).

The server runs `feature_ids` to pick what to look up; the phone runs it to decide when to ask. Both compare the
copies. The key is pinned by `policy_json_keys_snapshot` and `PolicyResponseCompatTest`, and the root `CLAUDE.md` and
the two workflows get the path.

**The launcher's alone**: `map_link_rules.json` (`MapLinksTest`).

- Every §2/§3 form gives `{kind: pin|lookup|search|none|list, lat, lon, z, label, ftid, query, centre, uri}`.
- `query_parts`, and the S2 centre for the fallback.
- `?q=Kiwi&sll=…` → a centred fallback, never a pin.
- Labels with `(`, `)`, `&`, æøå and 200 characters.
- `mapLinkPlan` rows.

**The server's alone**: `server/testdata/map_links_server.json`.

- `hops`: §4.1's recordings, a 200, a 404, a private address, too many hops, a short link with a query.
- **`preview`**: recorded `/maps/preview/place` answers **cut down to the paths §8.3 reads**, the rest replaced by
  `null`, so Google's 24 KB of other content isn't copied into the repo. The row keeps the original's size and
  recording date.
  - Artbox, this VM, 2026-11-17: `d[6][9]` `[null,null,59.922361499999994,10.6868892]`, `d[4][0]`
    `[1999.5893423285174,10.6868892,59.9223615]`, the echo, the name and the address.
  - G1b's Steak no Don and RAI, once recorded on the user's server.
  - Built cases: only `d[4][0]`; `d[6][9]` 5 km from `d[4][0]`; a wrong echo; 0,0; a point in the wrong S2 cell (the IP
    centre, 17 bits); an address with another postcode; a `consent.google.com` 302; a 500; a body without `)]}'`; an
    array too short.
  - Each row gives the outcome and the `place`.
- `pwa_lines`, and `lookup_line` (counter rows → the §8.4 line or none).

## 11. Privacy, terms, and why the server resolves

**Server versus phone resolving** (QA 12). The server is recommended.

- **On the server:**
  - Google sees the household server's IP, not the kid's carrier IP.
  - The kid's DNS filter never lets a Google host through.
  - "The phone never talks to Google" holds.
  - Costs: about 1 s per tap that needs it; short links and lookups fail while the server is down (the link's own
    point, and the fallback, still work); and the server holds which places were opened, in memory, for 10 minutes.
- **On the phone**: faster and independent of the server. But Google hosts must pass the kid's filter, and Google sees
  the kid's IP on every tap.

**What leaves the server:**

- To Google: a short link's id, or a feature id; `User-Agent: vibb/1.0`; no cookie, text, kid or location.
- To Apple: an Apple short link's id.
- Google learns that someone at the household opened a place a sender shared.

**What is never done:**

- reading Google's or Apple's HTML pages: consent-walled, 223 KB, and their point is the requester's (§4.2);
- consent cookies, or submitting the consent form;
- `User-Agent` tricks to pass the consent wall: one tool-name `User-Agent` got past it once, which is recorded and not
  used;
- Google's Places or Geocoding APIs: keys and billing, and their terms forbid showing their data on a non-Google map;
- any geocoding service;
- `tbm=map` text search (§16).

**Terms.**

- `robots.txt` allows `/maps/preview` for every agent (§4.2).
- The endpoint is undocumented. Google's Terms of Service ask users not to access the services in ways other than the
  interfaces Google provides [web, paraphrased].
- One household's server, one lookup per tapped link, at most 100 an hour, behaves like a person opening the link.
- If Google changes or refuses, the counter shows it, the fallback keeps links working, and nothing on the phones
  changes.

**Organic Maps** works offline.

## 12. Android 14-17

| Version | What applies | Checked |
|---|---|---|
| 14 | domain verification v2; `CrossProfileIntentResolverEngine` (new in 14); `ACTION_APP_OPEN_BY_DEFAULT_SETTINGS`, `DomainVerificationManager` (31); BAL for a late start | gate on API 34; the enforcer by QA on `android14-release` |
| 15 | `enforceIntentFilterMatching`: the `geo:` start matches the targets' filters | by source; 14 and 16 bracket it |
| 16 | the Jelly Star; Play services' system-configured `*.app.goo.gl` (2026-11-04) | gate on API 36 with Google Play |
| 17 | AOSP main's code | by source only: this VM's 17 images don't boot (`docs/testing/emulator.md`, 2026-11-17) |

## 13. Tests and the emulator gate

- **Server.** `tests::map_links`:
  - the contract rows;
  - the refusals and limits;
  - `busy` at once;
  - the 5 s deadline with a slow fake;
  - a lookup that runs out returns `url` alone;
  - the 10-minute cache;
  - every `preview` row through §8.3;
  - the counter and §8.4's line;
  - the save on `#map-links`;
  - the Apps group in en and nb.
- **Launcher**: `MapLinksTest`, `MapLinksContractTest`, `mapLinkPlan`, `PlayInvariantsTest` (the sixth step, two
  filters), and `KidScreensEscapeTest` (`map/`).
- **Gate** (`docs/testing/emulator.md` §6az, API 34 and API 36 Google Play, screenshots on jelly36):
  - **G1**: §4.1's five short links with `vibb/1.0` from the user's server.
  - **G1b**: on the user's server, `/maps/preview/place` with the minimal `pb`, for:
    - Artbox;
    - Steak no Don (`!3d34.5945482!4d133.7583428`);
    - RAI (`44.4490541, 26.0888398`);
    - one Norwegian G9 share;
    - a `cid` as `0x0:0x<hex>`.
    - **Steak no Don confirmed 2026-11-17** on the user's server (a Raspberry Pi in Norway), `hl=no`: `d[6][9]` = `[null,null,34.5945482,133.75834279999998]` (the `!3d!4d` point to float noise - round to 7 decimals before use), `d[6][11]` = "Steak no Don", `d[6][2]` = `["4 Chome-7-28 Oimatsucho","Kurashiki, Okayama 710-0826","Japan"]` (address lines may be three, the country last). With Artbox measured here, the method holds on two known places.
    - Record the size and time, `hl=no` against `hl=en`, and §8.3's checks. Store the cut-down answers as `preview`
      rows.
    - The known-point rows confirm that `d[6][9]` is the place (within 50 m).
  - **G2**: `pm get-app-links` for Play services, the Google app and Google Maps.
  - **G3/G4**: a short link before and after the two steps: Play services, then Vibb → the server → Organic Maps with
    "Artbox AS, Drammensveien 133, 0277 Oslo" on the pin.
  - **G5**: G4, G9, A1 and A5 links each give the pin and label. `%2C`, æøå and `%20` decode right.
  - **G6**: Chrome as the login browser, plain `VIEW`: Vibb wins after step 2.
  - **G7**: switch off, and §6.3's row.
  - **G8**: each `target_state`.
  - **G9**: an SMS map chip opens Organic Maps in its own task.
  - **G10**: the label shows exactly, and Organic Maps' card shows the OSM data for a mapped place.
  - **G11 / G11b**: Element X with Chrome hidden, and with Chrome as the login browser.
  - **G12**: the fallback's (a)/(b) on four Norwegian texts, with the lookup forced off.
  - **G13**: the time lock and the keyguard show nothing. Leaving during the wait starts nothing.

## 14. Size

| Part | Size | What |
|---|---|---|
| Server | **M-** | the route; the short-link and place-lookup clients (two `Kind`s, the deadline, the gate); §8.3's parser and checks with the S2 encoder; the counter table and line; the 10-minute cache and limits; migration 0155; the policy key and report intake; the Apps group; i18n; contract and `preview` tests |
| Launcher | **M-** | the parser and rules (feature ids, the fallback's S2 centre), `MapLinkActivity` and `mapLinkPlan`, the notes, the two PPA filters, the Meldinger chip and Dagen's row, the Settings group, the report, i18n, tests |
| Gate | **S** | G1-G13, with G1b on the user's server and Oslo downloaded in Organic Maps |

About 1.75 rounds. One commit across both directories, and the server tags before the launcher's RC.

## 15. Questions for the user

1. **Map links you can tap in Meldinger.** Today nothing in a message can be tapped. Should a map link get a small "map"
   button under the message that opens the place in Organic Maps? All other text stays as it is. **I recommend yes.**
2. **Two steps on each phone, once, during a pause.** For links from Element X and other apps to reach Organic Maps,
   the parent must, in Vibb One's settings during a pause: (1) turn off Google Play services' "Open supported links",
   (2) turn on Vibb One's. Links in Meldinger work without this. The PWA shows an orange line until it is done. **I
   recommend yes, with two rows in Vibb One's settings that open the right pages.**
3. **CoMaps too.** CoMaps is a copy of Organic Maps. Should Vibb use CoMaps when Organic Maps isn't installed, and offer
   the switch for either app? **I recommend yes.**
4. **When the switch is turned off.** Google Play services' links stay off on the phone until someone turns them back
   on. Should Vibb One's settings then show a row that does it, with a line in the PWA? **I recommend yes.**

## 16. Not in this round

- **Google's `tbm=map` text search** for a link without a feature id (`?q=<text>` typed by hand, `/maps/search/<text>`,
  Apple's text-only links). `robots.txt` allows it, but it isn't as clean as the lookup by id:
  - it returns a list of results, ranked around the requester (the household server);
  - there is no echo to prove the hit is the sender's place.
  - These links are rare, and they get the offline fallback. Revisit if the counter shows many.
- `share.google`, `g.co/kgs`, and Google Search's place cards.
- Reverse geocoding or any geocoding (the user: not now).
- A plain-text address in Dagen as a "Vis i kart" row (QA 21).
- A plus-code decoder.
- Routes with waypoints, and My Maps lists.

## 17. QA findings (revision 2) and what changed

| # | Finding | Change |
|---|---|---|
| 1 H | the search is the common case; its query untested | rev 2: §2.1's rule and G12. **Rev 3**: G9 now gets a pin from the server's lookup (§4.2, §8.3), and the search is the fallback only |
| 2 M | map-centre coordinates taken as the place | §2's rule, A3: the centre is used only for the fallback |
| 3 M | the resolver's numbers | §8.1: `Kind::ShortLink` and `Kind::PlacePreview`, the connect timeout in `Kind`, one 5 s deadline, its own `Gated` with `busy` at once |
| 4 M | "Open in browser" loops | §7.2: the browser named, the original link |
| 5 M | hidden counted as installed | `target_state`; §7.3, §7.4, §9 |
| 6 M | switching off leaves Play services' links off | §6.3, the row, the PWA line, G7; Q4 |
| 7 M | the step line orange for ever | `selectable`; lines driven by `reach` |
| 8 M | path rules across authorities; Google Search | two filters, two PPA filters; `long_hosts` rows |
| 9 M | Element X with Chrome; Custom Tabs | §6.1, G11b, the panel line |
| 10 M | why not the long page | §4.2 and §11, measured again from this VM: consent wall, the IP-location trap, 223 KB |
| 11 L | migration | `0155_map_links.sql`, now with `map_link_lookups` |
| 12 L | server versus phone | §11; **rev 3**: the cache is 10 minutes (the user) |
| 13 L | which lock | §7.1 step 1, G13 |
| 14 L | task and late start | `NEW_TASK`, cancel in `onStop` |
| 15 L | the escape test | `map/` in `guarded`; `calls.md:287` |
| 16 L | the 409 | the note and a sync |
| 17 L | the report's churn | §7.5; pause-time reports skipped |
| 18 L | hidden isn't "not installed" | §6.2 |
| 19 L | `pwa_lines` shared | moved to `map_links_server.json` |
| 20 L | hop rebuild | §4.1, §8.1 |
| 21 L | over-built; design calls | settled in §2/§7.4; Dagen's address and plus codes to §16 |
| 22 L | i18n gaps | §7.4, §8.4, §9 |
| 23 L | smaller points | §1 "tested against"; https-only input; `%20` |
