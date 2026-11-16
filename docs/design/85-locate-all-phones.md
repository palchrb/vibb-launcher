# 85 - Locate: every phone on one map, and a colour of its own for every phone

Status: design note by the architect, 2026-11-11, on `main` at 7f3262ec, branch `design-85`. O1-O3 decided by the
user 2026-11-12, as proposed (§10). Step 1 (the colours) built on `design-85`, 2026-11-12, as §5 and §9 say, migration
0138; step 2 (the Alle view) built on `design-85-step2`, 2026-11-12; revision 3 (one look for one phone) built on
`locate-one-look`, 2026-11-12. Server/PWA
only: one migration, `devices.rs`'s avatar helpers, Locate's handler and template, one new script, `style.css`,
i18n, tests. No API change, no policy key, no launcher or music change (§9 says why none is needed).

What the user chose, 2026-11-11, from the mockups A, B and C of an "Alle" view (the clickable lab in the session's
scratchpad, `locate-all/`): **B** - "Jeg likte B!". On freshness: "fersk bør vel henge litt sammen med valgte
oppdatering frekvens ... Den avgjør jo litt hvor ferskt det kan bli". On the avatars: "forskjellige farger generelt
som avatar frem til man setter et bilde ville jo vært god skikk".

What is read and what is inferred: everything below about today's code was read in this tree (`handlers/locate.rs`,
`device_locate.html`, `static/locate.js`, `zones.rs`, `handlers/devices.rs`, `handlers/notifications.rs`,
`style.css`, the launcher's `LocationPolicies.kt`, `SyncSchedule.kt`, `ZonePlan.kt`); mockup B's HTML, CSS and PNG
were read too. A sentence marked *(inference)* is not proven by either.

Revision 2 (after design QA, 2026-11-11): no High; five Mediums and the Lows taken, finding by finding at the end
("Revision 2: the design QA, finding by finding"). What moved: `phones.json` is no-store (§4.6); Oppdater alle is not
a save (§2); the chips and the hollow avatar have one light look in both schemes (§4.1); a refresh keeps an open
bubble (§4.4a); the wait counts the phones' answers and ends cleanly (§4.6); the open points carry the QA's leanings
and O1-O3 stay the user's (§10).

## 1. The decisions

1. **Locate opens on "Alle" when the family has two or more phones.** Today it opens on a list of the phones; the
   list goes and the Alle view takes its place at `/devices/locate` (no `?device=`, or an unknown one). One phone is
   still shown at once, as today; no phone keeps today's empty state ("No phones yet." and Add Phone). `?device=N`
   is today's one-phone view, unchanged except for §1.9.
2. **The Alle view is mockup B**: one large map over most of the sheet; a chip row over the map's top ("Alle |
   Emma | Jack", the chosen one dark) that scrolls sideways when it doesn't fit; each phone with a position is a
   marker of its round avatar with a small time tag under it ("2 min" on green when fresh; a clock and "3 t" on
   white when old, the avatar grey); an accuracy circle at its true radius (solid when fresh, dashed when old); a tap
   on a marker opens a bubble (name, "Sist sett for 3 t siden", "Innenfor 60 m · I sonen Skolen", "Vis Jack ›");
   a legend under the map ("Nylig" / "Eldre posisjon"); **Oppdater alle**, one Update Location per phone.
3. **Fresh is one rule, the one "Here now" already uses** (`zones::fresh_for`, design 64): a position is fresh while
   it is younger than **twice the interval the phone is served** under "Jevnlig", and younger than **1 hour** under
   "Bare når du spør" (under "Av" the history is pruned whole, so such a phone has no position and no marker at
   all). "Younger" counts from the phone's last word on that position: its own fix, or
   its re-send of that fix as still current (`resent_at`, design 64 Q-3). §3 shows why, and O1 asks whether 1 hour
   should be 30 minutes (for both places).
4. **A phone without a position has no marker; it has a row.** Under the map, a small group "Uten posisjon" lists
   each such phone with why: "Ingen posisjon ennå", "Posisjon er slått av", "Ikke satt opp" - a link to its own
   view. Its chip is in the row like any other, its avatar hollow (a `--dot-grey` dashed ring, no fill, the letter
   in `--label`).
5. **Markers that overlap become one group marker**: the avatars side by side, overlapping, one time tag (the
   freshest's); a tap opens one bubble with a part per phone, each with its own "Vis … ›". Grouped by screen pixels
   after every zoom, so zooming in pulls them apart (§4.4).
6. **The map's view**: fitted to every phone with a position, at most zoom 16; one phone alone at zoom 15. When that
   fit needs a zoom below 11 (wider than a region) and at least one phone is fresh, it fits the fresh ones only - an
   old position far away doesn't shrink today's town to a dot. A **fit-all** button (mockup B's corner button) fits
   everything at any time (§4.5).
7. **Every phone has a colour of its own** until a photo is set, in the whole PWA: seven calm pastels, peach first,
   with the night letter (9.7:1 or more in both schemes). A phone gets its colour once, when it is added: the first
   one no other phone has; past seven, the one fewest phones have. It is stored, so it never moves when another phone
   is removed. The first phone keeps today's peach, so a family with one phone sees no change. The parent can't pick
   a colour in this design (O5). A phone not set up stays grey, as today (§5).
8. **On the map, an old marker's avatar is grey** (as B), a photo greyed with it; the chip keeps the phone's own
   colour - a chip names the phone, the marker tells the age (O4: B greyed Jack's chip too).
9. **The one-phone view gets two small things**: today's phone switcher gets "Alle" as its first entry (as mockup
   A's one-phone screen, the user's "Trykk Emma: dagens visning"), and its age line says fresh or old with the
   legend's dot, by the same rule. Its map, its marker and everything below stay as they are.

## 2. What changes where

| Where | What |
|---|---|
| `server/migrations/0138_device_avatar_tone.sql` | `devices.avatar_tone INTEGER NOT NULL DEFAULT 0`, the existing phones numbered by id (§5.3) |
| `src/models.rs` | `Device.avatar_tone: i64` |
| `src/handlers/devices.rs` | `AVATAR_TONES`, `avatar_class` returns the tone's class, `next_tone` at insert (§5) |
| `src/handlers/notifications.rs` | the inbox's query reads the tone; `avatar_class_of(enrolled, tone)` |
| `src/handlers/locate.rs` | the Alle view (`AllView`), `phones.json`, `POST /devices/locate/all`, the one-phone switcher's "Alle", the age line's dot, and the one helper `Seen` both views read (§3.4), which calls `zones::fresh` |
| `src/zones.rs` | nothing: `fresh_for`, `fresh` and `freshness_inputs` are used as they stand |
| `src/main.rs` | two routes (§4.6) |
| `src/admin_auth.rs` | `no_store_path` gets the arm `["devices", "locate", "phones.json"]`, `tests::no_store_paths` the path (§4.6) |
| `src/settings_log.rs` | `NOT_SAVES` gets `/devices/locate/all` - Oppdater alle is a command, not a settings save (as `command/locate`); `every_skipped_route_is_a_route` covers it |
| `templates/device_locate.html` | the Alle view replaces the list; the switcher's first entry |
| `static/locate-all.js` (new) | the Alle map: markers, groups, bubble, fit, the wait after Oppdater alle |
| `static/style.css` | the tone classes; the Alle view's chips, markers, tags, legend |
| `src/i18n/en.json`, `nb.json` | the new words (§7) |
| `src/tests/`, `jstest/locate-all.test.js` | §8 |

Untouched: `static/locate.js` (the one-phone map and the Locations page), the band partial (the Alle view calls
`band::tab` as Locate does today), every other page's markup (the tone arrives through `avatar_class`).

## 3. Fresh

### 3.1 How the phone actually reports (read in the launcher)

- **"Jevnlig" (`interval`, every 10/15/30/60/120/240 min)**: at each sync the phone takes a fresh fix when the last
  active fix is at least the interval old (`locationAction`), else reports its cached one. Its backstop sync comes
  every 30 min (15 while the stream is down) but never later than the next due fix, and never sooner than 9 min
  (Doze's while-idle floor, `backstopDelayMs`). So a healthy phone's newest fix is between 0 and about interval +
  9 min old *(inference: plus the fix's own seconds)*. A fix that fails (indoors, no fused answer) leaves the
  report without one, and the next try is the next sync - up to another interval later.
- **With zones** the phone is served at least 30 min (`HEARTBEAT_FLOOR_MINUTES`) and watches the zones itself; while
  its motion sensor says it hasn't moved it re-sends its last fix as current for up to 4 h (`STILL_SKIP_MAX_MS`), and
  the server keeps that as `resent_at`.
- **"Bare når du spør" (`on_request`)**: a fix only for Update Location (and a zone crossing). A cached fix
  goes into a report only while it is younger than the policy's interval (30 min by default, P-L3) - the same fix,
  no news.
- **"Av" (`off`)**: no fix ever, and the history is pruned whole (`retention::LOCATION_OFF`: at the change to Av
  and in every hourly pass), so a phone under Av has no position at all - it is in "Uten posisjon", never a marker.

### 3.2 The rule

`zones::fresh_for(mode, served_interval)`, as it stands:

| Setting | Fresh while younger than | Example |
|---|---|---|
| Jevnlig, every 10 min | 20 min | |
| Jevnlig, every 15 min | 30 min | |
| Jevnlig, every 30 min, or any interval with zones watched | 60 min | Emma's phone with Hjemme and Skolen |
| Jevnlig, every 60 / 120 / 240 min | 2 / 4 / 8 h | |
| Bare når du spør | 1 h | Jack after Oppdater alle |
| Av | - (no position, §3.1) | |

Younger counts from `MAX(captured_at, resent_at)` of the newest fix (`zones::freshness_inputs`, today).

### 3.3 Why this one and not 1.5x / 15 min

- **It is already the rule for "Here now"** on the zone rows and on the phone's summary. A second rule on the same
  page would let the bubble say "I sonen Skolen" on a grey marker while the zone row below says "Her nå", or the
  other way round. One rule means one answer.
- **Twice the interval is one missed beat.** From §3.1 a phone on time is at most about interval + 9 min old; 1.5x
  covers that for 30 min and up but not for 10 or 15 (15 + 9 = 24 > 22.5), and a single failed indoor fix turns a
  healthy phone grey. 2x stays green through one late or failed fix and goes grey at the second - when something is
  actually wrong. For 120 and 240 min it is generous (a 7-hour-old fix on a 4-hour interval is green), but that is
  what the user said: the setting decides how fresh it can be, and the tag says "7 t" in plain sight either way.
- **Outside Jevnlig** nothing refreshes by itself, so freshness is time since someone asked. 1 hour is design 64's
  number; the user's example was 15 minutes, and the phone itself stops reporting a cached fix after 30 (§3.1). O1.

### 3.4 Where it is computed

The server says fresh or old; the page never judges. `locate.rs` gets one helper beside `Fix`, the only place
either view decides it; it reads `zones::freshness_inputs` and calls `zones::fresh` (no new helper in `zones.rs`):

```rust
/// A phone's newest position as both Locate views tell it: the fix, when the phone last vouched for it
/// (its own time, or a still re-send's), and fresh by `zones::fresh` at `now`.
pub(crate) struct Seen { fix: DeviceLocation, at: DateTime<Utc>, fresh: bool, still_since: Option<DateTime<Utc>> }
```

`still_since` is the fix's own time when `resent_at` is the newer: then the bubble and the one-phone view's age line
add "Har ikke flyttet seg siden 11:40" and the age is the re-send's ("for 2 min siden"). Without a re-send, nothing
changes in today's age line except the dot (§1.9).

## 4. The Alle view

### 4.1 The page

```
band::tab("Finn")
.sheet
  section#all > .map-all  (the map box, data-locate-all; the chips over its top; fit-all + zoom at bottom right)
  .legend   ● Nylig   ● Eldre posisjon
  group "Uten posisjon"   (only when some phone has none)
  group  [Oppdater alle]  and the wait line under it after a press
  group-footer  what "Nylig" means (§7)
```

- **The map box**: `height: clamp(280px, 55vh, 480px)` (since the age box went, design 88 revision 2; 45vh/420 for a day before) - about 470 px on a 393x852 iPhone, 312 px on a 320x568
  one; full screen shows the rest. *(Was `clamp(300px, 60vh, 520px)` until 2026-11-12, when the user found the age
  row and Update Location under the tab bar on an iPhone 15.)* Leaflet's zoom control moves to the bottom right (the chips have the top), the fit-all button
  is an `L.Control` above it, OSM's attribution under both as today.
- **The chips** (`nav.map-chips`, `aria-label` "Telefoner"): they lie on OSM's tiles, which are light in both
  schemes, so they have **one light look in both** (as the markers): a white chip with a `#1C1C1E` label (17.0:1)
  and a soft shadow, 44 px high at least (the tap target). "Alle" (`aria-current="true"`) is the dark one:
  `--night` with `--night-text` (16:1), in both schemes. Then each phone as a plain link to
  `/devices/locate?device=N`: its 26 px avatar and its name. A hollow avatar (§1.4) takes its letter from what it
  sits on (`color: inherit`): `#1C1C1E` on a chip, `--label` in the "Uten posisjon" rows; its ring is `--dot-grey`,
  dashed, in both. The row is `overflow-x: auto; overscroll-behavior-x: contain; scrollbar-width: none`, one line, a name cut
  at 10 em with an ellipsis; its right end fades into the map. That is the answer to five or more phones: no wrap,
  no shrinking, a sideways swipe inside the row - the page itself never scrolls sideways (§6). Each chip's
  `aria-label` carries what its marker shows: "Emma, for 2 min siden, innenfor 12 m", "Lily, ingen posisjon ennå" -
  the chips are the view's accessible list, and they work without JS.
- **No JS**: the chips, the "Uten posisjon" group and Oppdater alle are server-rendered and work; the map box shows
  its grey ground. The facts are in the chips' `aria-label`s and one tap away.

### 4.2 A marker

An `L.marker` with an `L.divIcon` whose `html` is a DOM node built with `textContent` (the name and words are the
phone's and the parent's - review H2), anchored at the avatar's centre:

```html
<span class="all-mk [all-old]">
  <span class="avatar tone-sky" aria-hidden="true">E[<img class="avatar-img" src="/phone-avatars/…" alt="">]</span>
  <span class="all-tag">2 min</span>            <!-- old: <svg clock/>3 t -->
</span>
```

- 40 px avatar with a 3 px white ring and a soft shadow (B's look); fresh: the phone's own tone; old: `#C9C4BF`
  with `#48484A` letter (5.3:1), a photo `filter: grayscale(1)`.
- The tag: fresh `#1E7A35` with white (5.4:1); old white with `#636366` and a clock (6.0:1). The tiles are light in
  both schemes, so the marker has one look in both (as `--map-trail`).
- The words: "nå" under a minute, "{n} min" under an hour, "{n} t" under a day, "{n} d" after - from the server
  (`phones.json`, §4.6), never formatted in the script.
- The accuracy circle: `L.circle` at the fix's own radius, fresh in `--map-trail` (solid, 10 % fill), old in grey
  dashed (`dashArray: "4 4"`); none when the accuracy is unknown.
- The marker is a keyboard stop (Leaflet's own `keyboard: true`); its icon gets `role="button"` and an
  `aria-label` equal to its chip's. Enter opens the bubble.
- Stacking: fresh markers over old (`zIndexOffset`), so an old position never hides a new one.

### 4.3 The bubble

A Leaflet popup with a DOM node (text nodes only). It keeps Leaflet's white popup in both schemes - it lies on the
light tiles, as the chips and markers do - with its words in `#1C1C1E` and `#636366` and "Vis … ›" in an explicit
`#A64B25` (the light scheme's `--tint`, 5.8:1 on white; never `var(--tint)`, which is peach on white in dark):

- **Name** (bold).
- "Sist sett {age}" - `time.min_ago` / `h_ago` / … as the one-phone view's age line ("Sist sett for 3 t siden").
- "Innenfor 60 m" (`locate.fix.within`) and, when the fix is surely inside zones, " · I sonen Skolen"
  (`fix_badge`, today's words). That is the "place word": a zone's name, never an address - there is no reverse
  geocoding and none comes with this design.
- Only when it applies, one line each: "Har ikke flyttet seg siden 11:40" (§3.4), "Siste posisjon før telefonen
  ble slått av" (`locate.fix.before_off`, today's). (No "location is off" line: under Av there is no fix, §3.1.)
- A hairline, then **"Vis Jack ›"**, a link to `/devices/locate?device=N`.

`autoPanPaddingTopLeft: [10, 64]` keeps a bubble clear of the chips. One bubble at a time.

### 4.4 Markers on top of each other

After the first draw and on every `zoomend`, the script projects each position to screen pixels
(`map.latLngToLayerPoint`) and joins markers whose centres are closer than 40 px (one avatar), greedily from the
freshest. A group:

- is placed at the mean of its points; each phone's accuracy circle stays at its own true point;
- shows up to three avatars overlapping by 14 px (each in its own fresh/old look), then a "+N" disc;
- has one tag: the freshest member's age and look;
- opens one bubble with a part per phone (the §4.3 lines and its own "Vis … ›"), freshest first;
- is spoken as the members' labels joined ("Emma, for 2 min siden …; Jack, for 3 t siden …").

Zooming in splits a group as soon as its members are 40 px apart; at zoom 19 two phones on the same spot stay one
group, which is the truth. No clustering library is vendored: the grouping is a pure function (`groups(points,
project, px)`), tested in node (§8).

### 4.4a A redraw keeps what the parent is looking at

The markers are redrawn by the 60 s refresh, by the wait after Oppdater alle (§4.6) and by the regroup on every
`zoomend`. None of them may close an open bubble or drop the keyboard focus:

- The script keeps its markers in a map keyed by **phone id** (a group by the sorted ids of its members). A redraw
  updates a marker in place - `setLatLng`, the icon's classes and texts, its circle's centre, radius and style - and
  never removes and re-adds it.
- A group's marker is rebuilt only when its membership changes; then a new key, and the old marker goes.
- An open bubble stays open: when its marker stays, its content node is replaced (`popup.setContent`) and it is
  moved to the marker's new point; when its marker goes (a group split or joined), the bubble moves to the marker
  that now holds the phone it showed first, open, with that marker's content.
- Focus: when the focused marker's element is replaced, focus moves to the element of the marker now holding the
  same phone (`focus({ preventScroll: true })`).
- Nothing pans or zooms on a redraw except the one fit at the end of the wait (§4.5).

### 4.5 The view

`fitPlan(phones)` (pure, tested):

1. No phone has a position: the world at zoom 2 (`phones.json` carries no zones; with nothing to show, the
   "Uten posisjon" group under the map says why for each phone).
2. One position: `setView(it, 15)`.
3. Else `fitBounds(all, { maxZoom: 16, paddingTopLeft: [40, 64], paddingBottomRight: [56, 40] })` (the chips; the
   tag and the controls) - unless `map.getBoundsZoom(bounds(all), false, padding)` is below 11 and at least one phone
   is fresh, `padding` the same 96 x 104 px the fit uses (Leaflet's `getBoundsZoom` takes one padding point: the sums
   of the two corners): then the fresh ones alone, by the same rule (2 or 3).

Fit-all (`aria-label` "Vis alle telefonene på kartet") always runs 3 without the exception. The view is set once at
load and after Oppdater alle; a redraw while the parent has panned or zoomed keeps their view (a `moved` flag set by
`movestart` from the parent, not by the script's own `fitBounds`).

### 4.6 The data and Oppdater alle

- **`GET /devices/locate/phones.json`** (the session's auth as `locations.json`; `?lang=` as `locate-result.json`).
  It is **no-store**: every phone's last position in one answer, so `admin_auth::no_store_path` gets the arm
  `["devices", "locate", "phones.json"]` beside `["devices", _, "locations.json"]` (it has three segments, so no
  `_` arm matches it today), and `tests::no_store_paths` lists `/devices/locate/phones.json`. The answer: one row per phone in the page's order - `id`, `name`, `initial`, `tone` (its class),
  `photo`, `href`, `label` (the chip's `aria-label`) and, with a position, `lat`, `lng`, `accuracy_m`, `fresh`,
  `at` (the seen instant), `tag`, and `lines` (the bubble's lines after the name, already in words); without one,
  `none` (`not_set_up`, `off`, `no_fix`). The server words everything; the script only places text. While a wait
  runs (§4.6, below) each row also has `answered` (true or false).
- The page loads it once at start, again every 60 s while the page is visible and on `visibilitychange` to visible
  (a tag left at "2 min" for an hour would lie), and every 5 s after Oppdater alle.
- **`POST /devices/locate/all`** (full auth, with the one-phone `command/locate` in `main.rs`): `queue_command(state,
  id, "locate", admin)` for every phone that can answer - set up, not Disconnected (`ConnectionState::Disconnected`,
  access removed), mode not Av (O3) - each logged as today's `device_command_queued` "locate". A waiting locate of a
  phone is replaced as today (`replaced_kinds`). It is a command, not a settings save: `settings_log::NOT_SAVES` lists
  it (§2). Back to `/devices/locate?requested=all&at=<instant>#all`: the map's section is `<section id="all">` at the
  page's top, where the result shows.
- **Who is skipped and why**: each skipped phone is in the "Uten posisjon" group or, with a position, gets a row
  note under the wait line (`locate.all.skipped`) - "Lily: ikke satt opp", "Jack: tilgangen er fjernet", "Emma: posisjon er av" (today's
  words: `devices.status.not_set_up`, `device.about.access_removed`, `locate.all.off`).
- **The wait** is rendered by the server only while `at` parses and is under 2 minutes old: a reload, Back or a
  bookmark later shows the plain page, no stale "Spør …". The line under the button, `role="status"`: "Spør 3
  telefoner …", then "2 av 3 har svart". **An answer** is the phone's newest `locate` command queued at or after
  `at` having left Asking (`locate::answer_state` is not `AnswerState::Asking`: a fix came, or a failure such as
  "Posisjon er av på telefonen", or it expired) - the server sets `answered` per row; fix times are not compared, so
  a still re-send or an old cached fix never counts and a failure does. Polled every 5 s for a minute (12 tries);
  then "2 av 3 svarte", the polling stops and `history.replaceState(null, "", "/devices/locate#all")` drops
  `requested` and `at` from the address. Markers move in place as their fixes come (§4.4a); the view is fitted once,
  at the end, unless the parent moved the map. With none to ask: no command, the line says "Ingen telefon
  kan spørres" with the skipped rows, and no wait.

### 4.7 The one-phone view

- The switcher (`nav.phone-switch`) gets "Alle" first, a link to `/devices/locate`, when there are two phones or
  more. Its other entries are unchanged.
- The age line (`.fix-age`) gets an 8 px dot before it, `#1E7A35` (the marker's fresh tag) when fresh, `#C9C4BF`
  when old - not `--dot-green`, which on Locate's switcher and Devices means "online": one meaning per green on a
  surface; the legend's dots are the same two, with the
  legend's word hidden for screen readers ("Nylig" / "Eldre posisjon" as a `visually-hidden` span after the age), and
  the "Har ikke flyttet seg siden" sub-line when §3.4 says so. Its marker stays Leaflet's pin (O6).

### 4.8 Full screen (decided by the user, 2026-11-12)

On the Alle view and the one-phone view, a ⤢ button on the map (bottom left, "Full screen" / "Fullskjerm") makes
the map box fill the screen by CSS - `position: absolute` in the body, as the tab bar is (design 43: no box
positioned against the viewport, which drifts in an iOS home-screen app), under the status bar's strip, over the
night band and the tab bar, every edge clear of the safe areas - not the Fullscreen API, which iPhone Safari lacks
for elements. The
chips stay at the top, their row ending short of a 44 px ✕ at the top right ("Close full screen" / "Lukk
fullskjerm"); Escape closes too - an open bubble first, then full screen. The legend ("● Nylig ● Eldre
posisjon"; on both views since revision 3) is a strip at the bottom left over the map, above OSM's attribution line, fit-all and zoom at the bottom
right above it. The page behind doesn't scroll while it is open, and Tab goes round inside the box. The map keeps its view: Leaflet re-measures on the window's `resize` (`trackResize`, `invalidateSize`
keeping the centre), which the script sends on entering and leaving. `static/map-full.js`, shared by both views;
without JS both buttons stay hidden. Tests: `locate_page::both_map_views_offer_full_screen`,
`jstest/map-full.test.js`.

## 5. The phones' colours

### 5.1 The palette

Seven tones, in the order they are handed out. All carry the night letter (`--avatar-text`, `#0C0C14`); the avatar
looks the same in both schemes, as today's peach does.

| # | Class | Colour | Night letter | On white surface | On dark surface (`#1B1B28`) |
|---|---|---|---|---|---|
| 0 | `tone-peach` | `#F0A884` (today's `--avatar-bg`) | 9.9:1 | 1.97 | 8.6 |
| 1 | `tone-sky` | `#9CC3EC` | 10.6:1 | 1.84 | 9.3 |
| 2 | `tone-butter` | `#EBD27F` | 13.0:1 | 1.49 | 11.4 |
| 3 | `tone-lilac` | `#D7AEDB` | 10.2:1 | 1.91 | 8.9 |
| 4 | `tone-sage` | `#B5D098` | 11.5:1 | 1.69 | 10.1 |
| 5 | `tone-teal` | `#7FCBC8` | 10.5:1 | 1.86 | 9.1 |
| 6 | `tone-rose` | `#EFA3AF` | 9.7:1 (9.76) | 1.99 | 8.5 |

Measured (WCAG 2 relative luminance, a script in the session's scratchpad): every letter is 9.7:1 or more - AAA at
any size, the 26 px chip's 12 px letter included. The disc against the surface is low in light (1.5-2.0), as
today's peach (1.97) is; the avatar is decorative (`aria-hidden`, the name always beside it), so no 3:1 is owed, and
the list's 46 px discs read by their hue. Apart: the closest pair is lilac/rose at ΔE 23.5 (CIELAB 76) and every
tone is ΔE 26 or more from the map's old grey `#C9C4BF`, so a fresh marker never looks old. With deuteranopia
(Machado 2009) peach/sage (8.5) and lilac/teal (7.3) come close; the order puts them fourth/fifth apart from each
other, the first three (peach, sky, butter) stay 15 or more apart for every common colour vision, and the initial
and the name always say who it is. Calm by construction: one lightness (L* 75-85), pastel chroma - none brighter
than today's peach next to it. Not used: a stone/sand (ΔE 5 from the old grey) and today's app-tile colours
(`avatar-indigo` …, white letters, louder), which stay the Apps tab's letter tiles.

In CSS each class sets only the variable, so `.avatar` keeps one rule:

```css
.tone-peach { --avatar-bg: #F0A884; }
.tone-sky { --avatar-bg: #9CC3EC; }
/* … */
```

### 5.2 How a phone gets its colour

At `insert_device_with_policy`, the device's own `INSERT` picks it - one statement, no read before the write, so two
phones added at once can't both read the same free tone:

```sql
-- the first tone no phone has; past seven, the one the fewest have (lowest number on a tie)
INSERT INTO devices (name, avatar_tone) VALUES (?,
    (WITH tones(n) AS (VALUES (0), (1), (2), (3), (4), (5), (6))
     SELECT t.n FROM tones t LEFT JOIN devices d ON d.avatar_tone = t.n
     GROUP BY t.n ORDER BY COUNT(d.id), t.n LIMIT 1))
RETURNING id
```

(The `VALUES` list is written from `AVATAR_TONES.len()`; a test holds them equal.) Stored, not derived from the id's rank: a rank would
recolour every later phone when an earlier one is removed - Jack would turn from sky to peach the day an old phone
goes. A removed phone's tone is free again for the next new one. Two phones of one family share a tone only at
the eighth phone.

### 5.3 The migration

`0138_device_avatar_tone.sql`: the column, default 0, then the phones there are numbered by id:

```sql
ALTER TABLE devices ADD COLUMN avatar_tone INTEGER NOT NULL DEFAULT 0;
UPDATE devices SET avatar_tone =
    ((SELECT COUNT(*) FROM devices AS earlier WHERE earlier.id < devices.id) % 7);
```

The oldest phone keeps peach; Emma (oldest) peach, Jack sky, Lily butter.

### 5.4 Where it shows

`avatar_class(device)` returns `tone-<name>` for a set-up phone - `AVATAR_TONES[tone.rem_euclid(len)]`, so a
stored value out of range (a hand-edited row, a later shorter palette) still maps to a tone and never panics - and
`avatar-grey` for one not set up, so every
surface that uses it changes with no template edit: Devices, the phone's summary (its large picker avatar), the
band's picture on its ten pages (`BandAvatar`), Apps' "On phones" and an app's page, Schedules, Music, Locate's
chips and markers. The inbox reads the tone in its query (`avatar_class_of(enrolled, tone)`); a catalog event keeps
its grey. Web Push is unaffected: a notification carries the phone's photo or the app's icon, never the letter
tile. Not changed: a contact's letter on Calls & Contacts (a person, not a phone: peach as today), the Apps
tab's app tiles. A photo covers the colour exactly as today.

Not set up stays grey (`#636366`, white letter): the grey says "not ready" on Devices today and keeps saying it;
the tone shows from the moment it is set up.

## 6. Standing rules kept

- **A save never jumps to the top.** Nothing here saves. Oppdater alle returns to the top on purpose: the map is
  there, it is the press's result (as Update Location returns to `#location`).
- **No sideways scroll at 320 px.** The chip row scrolls inside itself; the map box is the sheet's width; the
  bubble is at most `calc(100vw - 64px)` (Leaflet's `maxWidth`, set from the map's width), its lines wrap; the
  legend wraps (`flex-wrap`). Checked in the build's Chromium pass at 320 px with five phones of 20-letter names.
- **The night band.** `band::tab(self.t("nav.locate"), tab)` as today; no action on the band.
- **i18n.** Every word in `en.json` and `nb.json` (§7); the script's words come only from the server.
- **The phone's values are text.** Names reach the page escaped by askama and the script through `textContent`
  (review H2), as `locate.js` does.
- **The tiles send only our origin** (design 33): the same tile layer options as `locate.js`.

## 7. The words

| Key | en | nb |
|---|---|---|
| `locate.all.chip` | All | Alle |
| `locate.all.phones_aria` | Phones | Telefoner |
| `locate.all.tag_now` | now | nå |
| `locate.all.tag_min` | {n} min | {n} min |
| `locate.all.tag_h` | {n} h | {n} t |
| `locate.all.tag_d` | {n} d | {n} d |
| `locate.all.seen` | Last seen {age} | Sist sett {age} |
| `locate.all.still` | Hasn't moved since {at} | Har ikke flyttet seg siden {at} |
| `locate.all.off` | Location is off | Posisjon er slått av |
| `locate.all.show` | Show {name} | Vis {name} |
| `locate.all.fresh` | Recent | Nylig |
| `locate.all.old` | Older position | Eldre posisjon |
| `locate.all.none_title` | No position | Uten posisjon |
| `locate.all.none_yet` | No position yet | Ingen posisjon ennå |
| `locate.all.fit` | Show every phone on the map | Vis alle telefonene på kartet |
| `locate.all.update` | Update All | Oppdater alle |
| `locate.all.asking` | Asking {count} phones … | Spør {count} telefoner … |
| `locate.all.answered` | {done} of {count} have answered | {done} av {count} har svart |
| `locate.all.answered_end` | {done} of {count} answered | {done} av {count} svarte |
| `locate.all.none_to_ask` | No phone can be asked | Ingen telefon kan spørres |
| `locate.all.skipped` | {name}: {why} | {name}: {why} |
| `locate.all.footer` | A position is recent while the phone keeps to its own setting: twice its interval under Regularly, an hour otherwise. | En posisjon er ny så lenge telefonen holder sin egen innstilling: to ganger intervallet ved «Jevnlig», en time ellers. |

"Not set up" is `devices.status.not_set_up`. `asking` with one phone uses `t_count` (one/other) in both languages.
The footer's "Regularly"/"Jevnlig" and "an hour" follow O1 and the mode names (`locate.settings.interval`, `on_request`).

## 8. Tests

Rust (run in CI only):

- `tests/avatars.rs`: a new phone takes the first free tone, then the next; a removed phone's tone goes to the next
  new one; with seven phones the eighth takes the least used; the migration numbers existing phones by id (a test
  database seeded before 0138, as the migration tests do); the tone's class is on Devices, the summary, a phone
  page's band, Apps' "On phones", Schedules, Music, Locate and the inbox; a phone not set up is `avatar-grey`; a
  photo still covers; a stored tone of 9 or -1 still gives a tone's class (`rem_euclid`). `AVATAR_TONES` and
  `style.css` agree: every class has one `--avatar-bg` rule, the INSERT's `VALUES` list has `AVATAR_TONES.len()`
  rows, and a pure test computes each hex's contrast with `#0C0C14` and asserts 7:1 or more.
- `tests/locate_page.rs`: `the_phone_switcher_and_the_list` becomes "…and the Alle view": no phone - today's empty
  state; two phones and none chosen (or `?device=999`, `?device=x`) - the chips (Alle current, each phone a link
  with its `aria-label`), `<section id="all">` with the `data-locate-all` box, the legend, the "Uten posisjon" group
  naming the phone without a fix, not set up, or under Av, the Oppdater alle form, no `/command/` and no old list;
  one phone - its view at once; `?device=N` - "Alle" first in the switcher, and not with one phone; a `<b>` name
  escaped everywhere.
- `phones.json`: the rows and fields; fresh by the rule at its edges (Jevnlig 15: 29 min fresh, 31 min old; with a
  zone and a watch: served 30, 59 fresh; on_request 59 fresh, 61 old; a `resent_at` newer than the fix counts and
  gives `still`); a phone switched to Av has no position (the prune); the tags (`nå`, `2 min`, `3 t`, `2 d`); the
  zone word; `none` for each reason; words in nb with `?lang=nb`. Signed out it answers the login redirect, and
  its answer carries `Cache-Control: no-store` (one household per server: there is no other family to keep out;
  `admin_auth::tests::no_store_paths` holds the path).
- `POST /devices/locate/all`: one `locate` per phone that is set up, not Disconnected and not Av, none for the
  others and each of them named with its reason; each logged; a waiting one replaced; the redirect's address with
  `at`; a GET is 405; without full auth refused as `command/locate`; no settings-log row
  (`settings_log::every_skipped_route_is_a_route` holds it in `NOT_SAVES`).
- The wait: rendered with an `at` a minute old, not with one three minutes old nor with an `at` that doesn't parse;
  `answered` is false while the phone's newest locate since `at` is Asking, true after a fix and after a failure
  answer, and a fix newer than `at` with no new locate (a re-send, a regular fix) doesn't make it true.
- The one-phone age line: the dot's class fresh and old (`#1E7A35`, not the online dot's class), the hidden word,
  the still line.

`jstest/locate-all.test.js` (node, a fake Leaflet as `locate.test.js`'s): `groups` (two points 10 px apart join,
50 px apart don't, a zoom in splits them, the freshest leads); `fitPlan` (none - world zoom 2, one, all, the
far-old exception with the fit's padding, fit-all ignoring it); a marker and a bubble built from a row whose name is
`<b>Jack</b>` contain it as text; the bubble's "Vis … ›" link; a group's bubble has a part per phone; **a poll with
the bubble open keeps it open** (its content replaced, no marker removed); a `zoomend` regroup with the bubble open
keeps it open on the marker now holding that phone; a focused marker keeps the focus through a poll and a regroup;
the 60 s refresh asks only while visible; after `requested=all` it asks every 5 s, counts the rows' `answered`,
stops after 12, calls `history.replaceState` with `/devices/locate#all`, and fits once unless moved; the script
never formats a word (every text it sets comes from the row).

## 9. Steps

1. **Colours** (small, ships alone): migration 0138, `Device.avatar_tone`, `AVATAR_TONES`, `next_tone`,
   `avatar_class`, the inbox, the seven CSS classes, the tests. Visible everywhere at once; nothing else moves.
2. **The Alle view**: `Seen`, `phones.json`, `POST /devices/locate/all`, the template's Alle branch replacing the
   list, `locate-all.js`, the CSS, the words, the one-phone switcher's "Alle" and the age line's dot, the tests.
   Then the Chromium pass at 390 and 320 px, light and dark, with one, two and five phones (two at one spot, one
   with no fix, one old far away), and the user's iPhone.

No launcher step: the phone already reports what this needs (fix, accuracy, `resent_at`, the off moment); its
avatar colour lives only in the PWA (the launcher shows no picture of itself).

## 10. Points for the user

**Decided, 2026-11-12** (the user, O1-O3 exactly as proposed):

- **O1: 1 hour.** Fresh outside "Jevnlig" stays 1 hour, "Her nå"'s number (§3.2); nothing changes in `zones.rs`.
- **O2: the seven colours as listed**, in this order: peach `#F0A884`, sky `#9CC3EC`, butter `#EBD27F`, lilac
  `#D7AEDB`, sage `#B5D098`, teal `#7FCBC8`, rose `#EFA3AF`; the oldest phone keeps peach (§5).
- **O3: Oppdater alle skips** a phone under Av, one not set up and one Disconnected, each named with why (§4.6).

O4-O6 stand as the architect's and the QA's below; the user may still overrule them. The points as they were put:

- **O1 - Fresh outside "Jevnlig": 1 hour or 30 minutes?** The rule is "Her nå"'s (§3), and under "Bare når du spør"
  its number is 1 hour. The user's example was 15 minutes. The QA leans to keeping 1 hour; if shorter, **30
  minutes, for both places** - the phone itself stops sending a cached fix older than that (§3.1). Changing it
  changes "Her nå" on the zone rows the same way.
- **O2 - The seven colours** (§5.1): peach, sky, butter, lilac, sage, teal, rose, in that order, the oldest phone
  keeping peach. The QA leans to these seven in this order, with a picture of the swatch on the avatars in step 1's
  Chromium pass for the user to see before it ships.
- **O3 - Who Oppdater alle asks**: the QA leans to skipping a phone under Av, one not set up and one Disconnected
  (none of them can answer with a position), each named with why (§4.6); the alternative asks every phone, as the
  one-phone Update Location does.
- **O4 - The chip of an old phone**: B greyed Jack's chip as well as his marker; the chip keeps the phone's colour
  (the chip names, the marker dates). The QA agrees.
- **O5 - The parent picks a colour**: later; the stored tone makes it a small step (a row of seven swatches under
  the summary's picture).
- **O6 - The one-phone map's marker**: Leaflet's pin for now; the avatar marker of §4.2 could replace it later so
  both views look alike. *Decided by the user, 2026-11-12: alike - revision 3.*

## Revision 2: the design QA, finding by finding

No High.

- **M1 - `phones.json` was not no-store.** It is now: the arm `["devices", "locate", "phones.json"]` in
  `admin_auth::no_store_path` and the path in `tests::no_store_paths` (§2, §4.6). §8's "another family's session"
  was wrong for a one-household server: signed out it answers the login redirect, and the answer is no-store.
- **M2 - Oppdater alle would have been logged as a settings save.** `POST /devices/locate/all` is in
  `settings_log::NOT_SAVES` (with `every_skipped_route_is_a_route`); `settings_log.rs` is in §2.
- **M3 - Dark scheme.** The chips have one light look in both schemes (white, `#1C1C1E`, Alle `--night` /
  `--night-text`); the hollow avatar has an inherited letter (`--label` in the rows, `#1C1C1E` on a chip) and a
  `--dot-grey` dashed ring (§1.4, §4.1).
- **M4 - A refresh closed the bubble.** Markers are updated in place by phone id, a group rebuilt only when its
  members change, an open popup kept open with its content replaced, and the focus kept; the same for the `zoomend`
  regroup (§4.4a); the jstests in §8.
- **M5 - The wait.** Rendered only while `at` is under 2 minutes old; ended with `history.replaceState` to
  `/devices/locate#all`; answers counted from each phone's newest locate command since `at` leaving Asking, not
  from fix times; the map's section is `id="all"` (§4.6).
- **Lows.** One helper, `Seen` in `locate.rs`, calling `zones::fresh` (§2, §3.4). Av prunes every fix
  (`retention::LOCATION_OFF`), so Av never has a marker and the bubble's "location is off" line is gone (§1.3, §3.1,
  §4.3). The tone is chosen inside the device's `INSERT … RETURNING id` (§5.2). `avatar_class` maps a tone with
  `rem_euclid` (§5.4). No zones in `phones.json`: with no position the map shows the world at zoom 2 (§4.5).
  `getBoundsZoom` takes the fit's padding (§4.5). No phone keeps today's empty state (§1.1). Oppdater alle skips Av,
  not set up and Disconnected, each named (§4.6). The fresh dot and the legend use `#1E7A35`, not `--dot-green`
  (§4.7). The bubble keeps Leaflet's white popup in both schemes, "Vis … ›" in `#A64B25` (§4.3). Chips at least 44
  px high (§4.1). Rose is 9.76:1, so "9.7:1 or more" (§1.7, §5.1). O1 says "Bare når du spør" (§10). Web Push is
  unaffected (§5.4).

## Revision 3: one look for one phone (the user, 2026-11-12)

On server 0.75.0 with one phone the user saw the old one-phone page - Leaflet's blue pin, a lone segmented switcher
button above the map, the age row - and said: "ja selvsagt skal det se likt ut!". Locate looks the same with one
phone or several. This replaces §1.9 and §4.7's switcher and O6's pin; §4.8 now covers the legend on both views.

- **The marker** of the one-phone view (one phone in the family, or `?device=N`) is §4.2's: the avatar in the phone's
  tone (its picture when set), the time tag, fresh or old by `Seen` (§3.4), the accuracy circle in the Alle view's
  style (solid in `--map-trail` when fresh, grey dashed when old, none when the accuracy is unknown). The trail, its
  dots and the span stay as they are; the newest point is the marker. One script piece draws it for both views:
  `static/map-marker.js` (the avatar, the marker's node and tag, its icon, the circle's style), loaded before
  `locate-all.js` and `locate.js`.
- **Its data** is the phone's own `phones.json` row, rendered as `data-marker-*` on the map box (tone, initial,
  picture, tag, fresh, the chip's label) - no API change. The tag and fresh are the fix the page shows; a newer fix
  that comes while the page is open (the trail redrawn by the span pick) is drawn fresh without a tag, as its popup
  already says only "Newest location". The marker's popup keeps today's words ("10 min ago · within 40 m") in the
  Alle view's white bubble, opened by a tap, no longer by itself: the tag says the age at a glance, as on Alle.
- **The chips replace the switcher.** With two or more phones, §4.1's chip row lies over the map's top: "Alle" (a
  link to `/devices/locate`), then each phone with its avatar, name and label, the chosen one dark
  (`aria-current="true"`). With one phone there is no chip row and no switcher. The switcher's online dot goes with
  it: the phone's state is on Devices and the phone's summary, as the Alle view's chips already decided (§4.1).
- **The map box** takes the Alle view's height (`clamp(280px, 55vh, 480px)` since the age box went) and its zoom at the bottom right (the
  chips have the top); the fit keeps 64 px clear at the top while the chips are there.
- **The legend** ("● Nylig ● Eldre posisjon") is under the map in the one-phone view too, and the strip over the map
  at full screen (§4.8).
- Unchanged: 12 t / 24 t / 7 d / All kept, the age row (dot, age, within), Update Location and its wait, Locations,
  Zones, the actions, the settings. The Locations page keeps its pin (its map is a list's, not a phone's place).

Tests: `locate_page` - with one phone no switcher and no chips, with two the chips (All, the chosen one current), the
marker's tone, tag and fresh on the box, the legend and its strip on both views; `jstest/locate.test.js` - the
avatar marker, its tag and circle, the pin kept without marker data; `jstest/locate-all.test.js` as it stands.
