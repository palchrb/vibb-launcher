# 87 - The apps' real logos in the PWA

Status: design note by the architect, 2026-11-12, on `main` at 0e72d7a9, branch `design-87`. Not built. The three
questions in §12 were answered by the user on 2026-11-12, each as recommended. Design QA on 2026-11-12 (of the file
at 763e7773): "Revision 2: the design QA" at the end lists the findings; the sections above are corrected in place
and marked (R2-...).

What the user asked, 2026-11-12: "de ekte logoene til apk vi legger til (og helst egentlig også de standard google
appene som er pre-installed)", and the way chosen: **the launcher draws each installed app's own icon from
PackageManager as a small image, reports a hash per app in the status report it already sends, and uploads only the
icons the server asks for; the server keeps them by content, shared across phones, and the PWA shows them where it
draws a letter tile today.** An older launcher sends nothing and keeps today's look.

What was read: `server/CLAUDE.md` (Fail closed, the status report, the device API, migrations), `launcher/CLAUDE.md`,
`docs/notes/server/launcher-ui.md` "App names and icons", `docs/notes/launcher/ui-polish.md` "Six calm icon tones
and three icon states", `docs/notes/server/pwa.md` "Device › Apps", "The Apps tab", "A phone's own picture";
`server/src/photos.rs` (`Store`, `decode`, `process_limited`, `path_for`), `handlers/device_api.rs`
(`cap_installed_apps`, `status`), `models.rs` (`InstalledApp`), `device_routes.rs`, `templates/apps_list.html`,
`partials/device_app_row.html`, `partials/app_display_form.html`, `style.css` (`.app-row-tile`, `.app-tile`); the
launcher's `MdmSyncWorker.kt` (`collectInstalledApps`, the report's place in `performMdmSync`), `AppEnforcer.kt`
(`controllablePackages`), `ui/home/KidAvatars.kt` (`renderAppIcon`, `drawOwnLogo`), `server/MdmApi.kt`. A sentence
marked *(inference)* is not proven by the code.

Mockups (not committed; the session's scratchpad, `design-87/`): `apps-tab-before.png` / `apps-tab-after.png` and
`device-apps-before.png` / `device-apps-after.png`, 390 px at 2x, drawn with the PWA's own `style.css` and fonts.
The logos in them are plain made-up shapes, no real app's art.

## 1. The decisions in short

1. **The image is the phone's own "App logo" render**: `KidAvatars.drawOwnLogo` - an adaptive icon's background and
   foreground at 1.5x, clipped to the circle; a legacy icon inset 1/8 on the light disc. Never the monochrome
   (themed) layer, never tinted. **128 x 128 px, PNG** with alpha (transparent outside the circle).
2. **The name is the SHA-256 of the PNG bytes the phone sends**, lowercase hex, 64 characters.
3. **The status report carries `installed_apps[].logo`** (that hash) for every reported app the launcher has drawn;
   a launcher that sends it claims `app_logos_v1` in `capabilities`.
4. **The server answers the report with the hashes it wants** - `200 {"wanted_logos": [...]}` to a launcher claiming
   `app_logos_v1`, at most 40, else `204` as today. No policy key.
5. **The phone uploads each wanted logo** to `POST /api/devices/app-logos/{hash}` (raw `image/png`, at most 80 KiB,
   R2-L1), among the sync's cosmetic steps after the report (R2-M4), bounded in time; any network; never blocking or
   failing the report or the lock decision.
6. **The server checks every upload and re-encodes it** - the hash matches the bytes, the hash is one this phone
   reported, a real PNG of exactly 128 x 128, decoded under limits, written again by the server's own encoder (no
   chunk of the phone's survives). Anything else is refused and not asked for again for a day.
7. **Storage**: `data/app_logos/<hash>.png`, shared by every phone; which phone shows which logo is a new table,
   `device_app_logos` (migration **0140**). A file no phone names is deleted. At most 4,000 files and 64 MB in all,
   and per phone at most 300 files and 12 MB named by its rows (R2-M2). **Not in backups** - a phone uploads again
   what is missing.
8. **Served at `GET /app-logos/{hash}`, behind the parent's session**, only a hash some phone names; `image/png`,
   `private, max-age=31536000, immutable`.
9. **The PWA shows what the phone shows, with the logo standing in for the app's own look**: a chosen glyph still
   wins (phone row, else catalog default); "App logo" shows the logo; "Automatic" shows the logo too (Q1, decided).
   Where no phone has sent a logo, today's letter tile stays.
10. **Where**: Device › Apps' rows, the Apps tab's Katalog rows, an app's page ("Show on phones as" preview), the Name
    and icon preview on Device › Apps. Not the inbox's rows (their tile is the phone, §8.3).
11. **The contract file is `server/testdata/app_logos.json`**, byte-identical in `launcher/app/src/test/resources/`.
12. **Steps**: the server and the contract first (step 1, with the launcher's DTO and contract test in the same
    commit), then the launcher's drawing and uploads (step 2). Either side may ship first.

## 2. The image

### 2.1 What is drawn - the phone's own logo, as the phone draws it

The phone has three icon states (design 34 P1): Automatic ("Vibb style": a monochrome layer white on the app's tone,
else the logo), App logo (`own`: `drawOwnLogo`) and a chosen glyph. The PWA already draws the chosen glyph itself
(`static/app-icons/`). What it can't draw is the app's own art, so that is the one thing the phone sends.

**The launcher sends exactly `drawOwnLogo`'s output** (one function, shared - not a second drawing), at 128 px:

- an `AdaptiveIconDrawable`: background and foreground at 1.5x (the 108 dp layers with their 72 dp safe zone),
  centred, clipped to the circle - the shape the phone shows on Home in "App logo";
- a legacy icon: inset 1/8 on `kid_icon_fallback` (`#F1F3F5`), clipped to the circle;
- **never the monochrome layer** (Android 13's themed icon) and never a tint: the parent asked for the real logo, and
  the monochrome version is the phone's Automatic look, not the app's art.

Why the circle is baked in and not left to CSS: it is then the same drawing the kid sees under "App logo" (the same
function; not the same pixels - Home draws `getBadgedIcon(0)` at its own size, this draws the 640 dpi icon at 128 px,
R2-L3), and the PWA needs no knowledge of Android's mask. `drawOwnLogo`'s `clipPath` on a software canvas is not
anti-aliased; the PWA's round tile clips the image again (`overflow: hidden`), so the browser smooths the edge. The cost is that the PWA can't choose another shape later
without a new launcher; the round tile is already the phone's and the Name and icon preview's (`.app-tile` is
`border-radius: 50%`).

**Which drawable**: Home's - `LauncherApps.getActivityList(package, user).first().getIcon(DENSITY_XXXHIGH)` when the
app has a launcher activity (every app `controllablePackages` lists from the system has one), else
`pm.getApplicationIcon`. A hidden app may not be listed by `LauncherApps` *(inference)*; then the application icon is
used. The fixed density (640) makes the bytes independent of the phone's screen, so two phones with the same app
version and Android build send the same hash and the server stores one file *(inference: Android's PNG encoder is
deterministic for the same pixels)*.

**A fixed light configuration (R2-M3)**: the icon is loaded through
`createPackageContext(package, 0).createConfigurationContext(config)` with `uiMode` night `NO` and `densityDpi` 640,
`ResourcesCompat.getDrawable(resources, activityInfo.iconResource or applicationInfo.icon, null)` - so the phone's
dark mode never changes the drawing (an app with `drawable-night` icons would otherwise get a new hash at every
switch, the old file pruned and uploaded again the next morning, every day). When that load fails (a hidden app
without a package context), the `LauncherApps`/`getApplicationIcon` path above is used, and only while the phone is
not in night mode (else that app waits for a later sync). `kid_icon_fallback` has no night value.

**Determinism across phones** is not needed for correctness: two Android builds that encode the same pixels
differently give two hashes and two files. A family's phones are a handful of builds, so the cost is about 1-2 MB per
distinct build *(inference)*, inside the per-phone cap (§5.3). A system update changes `Build.FINGERPRINT` and redraws
every app: about 100 uploads once, inside the daily budget (§7).

### 2.2 Size and format

- **128 px**. The PWA's row tile is 36 CSS px (108 device px on a 3x iPhone) and the Name and icon preview 56 px
  (168 at 3x): 96 px is soft in the rows on a 3x screen; 128 covers the rows and is fair in the preview. A 128 px
  logo is about 8-20 KB as PNG *(inference)*.
- **PNG, not WebP.** Both are lossless with alpha and every browser shows both; WebP lossless saves perhaps a third
  of a few kilobytes. PNG wins on simplicity: `Bitmap.compress(PNG)` on the phone, the `image` crate's PNG decoder
  and encoder already built into the server (`features = ["jpeg", "png", "webp"]`), one format to check. The upload
  takes PNG only; WebP is refused like anything else.
- Never SVG, never a vector, never anything the browser would run or parse beyond a raster PNG.

## 3. The hash and when the launcher draws

- **The hash** is `SHA-256(png bytes)`, lowercase hex. It is the file's name on both sides, the dedupe key across
  phones, and what the upload route checks the bytes against.
- **A small cache on the phone** (`noBackupFilesDir/app_logos/`: `<hash>.png` and an `index.json`): per package, the
  key it was drawn under and the hash. The key is `(package, versionCode, lastUpdateTime, Build.FINGERPRINT,
  LOGO_RENDER_VERSION)` - an app update, a system update (preinstalled apps' icons) or a change to our own drawing
  redraws it. No night bit: the drawing is made in a fixed light configuration (§2.1, R2-M3). A package no longer
  installed leaves the index and its file goes unless another package has the same hash. The index is written to a
  temp file and renamed (a worker killed mid-write leaves the old index, R2-L10). A drawn PNG over the upload limit
  (§4.3) gets no hash and is never reported (R2-L1).
- **When it draws**: just before the status report is built, `AppLogos.refresh(context, packages)` draws the
  packages whose key changed, **at most 1.5 s of drawing per sync** (each about 5-10 ms *(inference)*); a cache hit
  costs a lookup. What didn't fit is drawn at the next sync and reported then. A failing draw (a broken drawable)
  leaves that app without a logo and is retried only when its key changes.
- **In the report**: `installed_apps[i].logo = hash` for each app with a cached logo; absent otherwise. Absent means
  "nothing to say this time", never "no logo" - the server keeps what it had for that package (§5.2).
- **The launcher itself** is left out of `installed_apps` (as today), so it sends no logo of its own; the PWA's Vibb
  One row uses the server's own Vibb mark (`static/icons/icon-192.png`, §8.1). Meldinger is a glyph by design 82.

## 4. The API

### 4.1 The report

`POST /api/devices/status`, `installed_apps[]` gains `logo` (string, optional). The intake (`cap_installed_apps`)
keeps it only when it is 64 lowercase hex characters (`photos::is_valid_hash`), else drops the field - never the app
or the report. `capabilities` gains `app_logos_v1`. The field is `#[serde(default, deserialize_with =
"lenient_text")]` like `version_name` (R2-M5): a plain `Option<String>` would refuse the **whole report** - lock
state, location and all - when a launcher sends a number or an object there. `cap_installed_apps` sets it
explicitly (its `..app` would otherwise carry it through unchecked).

**The logo is not stored in `device_status.installed_apps_json`**: that is a 48-hour history of every report, and 64
characters per app per report would add roughly 0.7 MB per phone for nothing *(inference: 100 apps, a report every
30 min)*. The intake strips `logo` from the stored snapshot and writes the per-package hashes to `device_app_logos`
(§5.2) - only the rows that changed.

### 4.2 The answer: the wanted list

To a report whose `capabilities` has `app_logos_v1`, the server answers **`200` with `{"wanted_logos": [hash, ...]}`**
(possibly empty); to every other report `204` as today. A hash is wanted when all hold:

- this report names it (on any app);
- no file `data/app_logos/<hash>.png` exists;
- it wasn't refused in the last 24 hours (`AppLogoRefusals`, in memory: a restart asks once more, which is harmless);
- the phone's daily upload budget has room (§7);
- the store and this phone's share of it are under their caps (§5.3).

At most **40** per answer, allowed apps first (the ones Device › Apps lists on top), then the rest by package name.
What is left is wanted at the next report.

Why the answer and not the policy: the server learns the hashes from the report, so the answer can ask at once, in
the same sync; a policy key would wait for the next sync and add a key to `policy_json_keys_snapshot` and
`PolicyResponseCompatTest` for no gain. An older launcher never claims `app_logos_v1` and keeps its `204` (its
`Response<Unit>` would discard a body anyway - Retrofit 2.11's built-in `Unit` converter closes it). The new launcher
reads the answer as a raw body (R2-L7): `Response<ResponseBody>`, always closed, at most 16 KiB read, decoded
leniently (`ignoreUnknownKeys`); a `204`, an empty body, one it can't read, or an older server's `204` is "nothing
wanted". Of the list it takes at most 40 entries, and uploads only a hash that is in its own cache - a hostile server
can never make the phone send anything but its own drawn logos. Every 2xx still counts as "the server took the report"
(design 76's outside sightings, the zone queue, the relocks): `sent.isSuccessful` is unchanged by a `200`.

### 4.3 The upload

`POST /api/devices/app-logos/{hash}` (bearer, the phones' listener only), body the raw PNG,
`Content-Type: image/png`, `DefaultBodyLimit::max(80 KiB)` (R2-L1: a 128 x 128 RGBA PNG that doesn't compress -
a photographic icon at worst - is about 65.7 KB, just over 64 KiB).

| Case | Answer |
|---|---|
| Stored, or already on disk | `204` |
| `{hash}` not 64 lowercase hex | `400` |
| Not `image/png` | `415` |
| Body over 80 KiB | `413` |
| `SHA-256(body) != {hash}` | `400` |
| No `device_app_logos` row of this phone names the hash, or the budget or the store's cap is spent | `409` |
| Not a PNG, not 128 x 128, or it doesn't decode | `422`, remembered as refused for 24 h |

The checks run in that order; nothing is decoded before the hash and the phone's own row agree. The decode is
`photos::decode`'s pattern with the PNG format only and `Limits { max_image_width: 128, max_image_height: 128,
max_alloc: 1 MiB }` - a PNG that says it is larger is refused before any allocation - then `to_rgba8()`, a check of
exactly 128 x 128, and **the server's own PNG encoder writes the file**. The stored file is therefore not the
phone's bytes: no text chunk, no ICC profile, nothing appended after `IEND` survives. Its name stays the phone's hash
(a name, not a checksum of the stored file). Decoding runs under `photos::PROCESSING`'s semaphore in
`spawn_blocking`, like the photos (`PROCESSING` is private today; it becomes `pub(crate)`).

Decode details (R2-L2): the body must start with the 8-byte PNG signature (else `422`, nothing parsed);
`image::codecs::png::PngDecoder::with_limits` - never a guessed format - so the IHDR's size is checked against 128 x
128 before any buffer exists; the inflate is bounded by the image's own buffer and the 80 KiB input (no zip bomb gets
past 1 MiB); an APNG yields its default image only, a 16-bit or palette PNG goes through `to_rgba8()` - the stored
file is always 8-bit RGBA, no animation. The budget (§7) is charged **before** the decode, so `422`s cost the phone
too.

The launcher treats every non-2xx as "not this time" and goes on; it never retries within the sync. A `409` or `422`
is not retried until the server wants the hash again.

### 4.4 The launcher's upload step

In `performMdmSync`, **among the cosmetic steps after the report** (R2-M4): after `reportBlockedDnsEvents` and before
`WeatherPull.afterSync` and the self-update commit (which stays last) - never ahead of the locate answer, the PIN
events, the kid's time and contact requests or `CommandAnswers.flush`, which up to 20 s of uploads on a slow network
would otherwise delay. The wanted list is kept from the report's answer in a local. For each wanted hash that is in
the cache, one POST; at most 40, **20 s in all**, each call with a 10 s
timeout; the first network error ends the step. All of it inside one `try`: a failure logs and changes nothing else.
Any network, not only Wi-Fi: a family's first round is about 100 logos, roughly 1-2 MB once, then a few KB when an
app updates *(inference)*; catalog downloads' Wi-Fi switch guards APKs of tens of MB, not this.

## 5. Storage

### 5.1 The files

`data/app_logos/<hash>.png` (`AppState.app_logo_dir`), one per distinct logo, shared by every phone. Written as the
photos are: a temp file, then a rename; an existing file is left alone. Its own lock (`APP_LOGO_FILES`) so a store
and a prune never cross. A new module, **`app_logos.rs`** - not `app_icons.rs`, which is design 14's Material glyphs,
and not `photos::Store`, whose zip entries and paths are `.jpg` and which is built around backups.

### 5.2 The table (migration `0140_device_app_logos.sql`)

```sql
CREATE TABLE device_app_logos (
    device_id    INTEGER NOT NULL REFERENCES devices(id) ON DELETE CASCADE,
    package_name TEXT    NOT NULL,
    logo_hash    TEXT    NOT NULL,
    updated_at   TEXT    NOT NULL,
    PRIMARY KEY (device_id, package_name)
);
CREATE INDEX device_app_logos_hash ON device_app_logos(logo_hash);
```

On each report: an app with a valid `logo` upserts its row when the hash differs; an app without `logo` keeps its
row; **a package missing from `installed_apps` loses its row** (uninstalled). A report with no `installed_apps` at all
changes nothing. 0140 is the next free number on `main` and on every local branch (checked 2026-11-12); if another
branch merges a migration first, this takes the next.

### 5.3 Pruning and caps

- A file no `device_app_logos` row names is deleted: after a report that changed rows (best effort, after the
  commit; only the hashes that lost a row are checked, not the directory - R2-L8), after a phone is deleted (the
  hashes its rows named, read before the cascade), and once in `retention::prune` with a full scan (a crash between
  commit and prune leaves no orphan for long). A stray temp file older than an hour goes there too.
- **At most 4,000 files and 64 MB** (`MAX_LOGO_FILES`, `MAX_LOGO_BYTES`): past either, nothing more is wanted, logged
  once a day as `app_logos_full`; the PWA keeps letter tiles for the rest. A family's phones hold a few hundred
  distinct logos *(inference)*, so the cap is only a fence against a broken or hostile phone.
- **Per phone (R2-M2)**: nothing more is wanted from a phone whose rows already name 300 files
  (`MAX_LOGOS_PER_PHONE`) or 12 MB (`MAX_LOGO_BYTES_PER_PHONE`) - shared files count for every phone naming them.
  Without it one phone (a stolen token, a rooted phone) reporting 1,500 made-up packages (`MAX_INSTALLED_APPS`) with
  noise PNGs of ~64 KB fills the 64 MB store in about four days and every other phone's new logos stop. With it, a
  hostile phone holds at most 12 MB and the honest phones keep the rest. Logged as `app_logos_phone_full`.

### 5.4 Backups

**Not backed up.** The logos are the phones' to send again, a backup zip would grow by a few MB for nothing, and the
table *is* in the database: after a restore the rows name files that aren't there, so the next report wants them
again (§4.2's "no file exists") and the phones upload them. Until then the PWA shows letter tiles: §8.4's fallback.

## 6. Serving

`GET /app-logos/{hash}` on the admin listener, **behind the parent's session** (unlike `/phone-avatars/`, which a
push icon needs without one; no push uses an app logo). Only a valid hash that some `device_app_logos` row names and
whose file exists, else 404 (no listing, no path from the request beyond the checked hash). `Content-Type:
image/png`, `X-Content-Type-Options: nosniff`, `Cache-Control: private, max-age=31536000, immutable` - the URL is the
content, so a new logo is a new URL. Never on the phones' listener
(`listeners::the_device_router_serves_nothing_but_the_device_api` gains the path).

## 7. Security and limits

A phone holds a bearer token, so a stolen or broken phone could send any bytes. What bounds it:

- **Size**: 64 KiB per upload (the body limit), 128 x 128 decoded, 1 MiB decode allocation.
- **Content**: PNG only, decoded completely and re-encoded by the server; never SVG, never served as the phone wrote
  it, `nosniff`. An image can still *show* anything 128 px can show; it is shown only to this family's parents, on a
  row of that phone's own apps or a catalog app a phone of theirs reports.
- **Count**: **300 uploads per phone per rolling day**, at most 40 wanted per answer, the store's and the phone's caps
  (§5.3). Not a `retention::Budget` key (R2-M1): that enum counts a table's rows received in a day, and an upload
  leaves no row. An in-memory `AppLogoUploads` (device -> the day's count, charged before the decode, like
  `AppLogoRefusals` bounded by `limits::evict_oldest` at `limits::MAX_KEYS`); a restart resets it, which the caps and
  the device API's token bucket keep harmless.
- **Only its own**: a phone can upload only a hash it reported on one of its apps; it can't fill the store with hashes
  no row names, and an upload for another phone's hash adds nothing (the hash fixes the bytes).
- **Cross-phone**: phone B reporting a hash phone A uploaded makes B's row show A's file - the same bytes by
  definition, so nothing is learned or spoofed; nothing is ever sent to a phone.
- **Refusals**: a refused hash is not asked for again for 24 h; refusals are logged at debug level, not in the
  Security Log (a broken drawable is not an attack, and the budget caps the noise).
- **Decoding cost**: one semaphore with the photos (`PROCESSING`), so a flood of uploads can't starve the Pi.
- **What the wanted list tells a phone (R2-L6, accepted)**: a hash missing from the answer is on disk, so a phone could
  learn whether some phone of the family has a given app at a given version and Android build. A sibling's app list
  is already less private than that inside one family, and the phone needs the exact PNG bytes to ask; accepted, not
  mitigated.
- **The catalog rows (R2-L4)**: a hostile phone's logo for a package could show on the Apps tab's catalog row; §8.1
  takes the hash most phones name, so one phone can't override two.

## 8. Where the PWA shows them

### 8.1 The rule (one function, `app_logos::tile`)

For an app on a phone, in order:

The resolved entry is design 14's: the phone's Name and icon row **as a whole**, else the catalog default (R2-L5 - a
phone row with "App logo" or Automatic beats a catalog glyph default, as on the phone).

1. if that entry's icon is a **chosen glyph**, it is shown as today (glyph on its colour): the phone shows that glyph,
   and the parent chose it;
2. else (the entry is `own`, Automatic, or there is no entry), if a logo is known for the package on **this** phone
   (`device_app_logos`) and its file exists: **the logo** - for "App logo" (`own`) and for "Automatic" (Q1, decided);
3. else today's tile (the letter).

Vibb One's row shows the server's own Vibb mark (`/static/icons/icon-192.png`). Meldinger keeps its glyph.

For a catalog app outside one phone (the Apps tab's Katalog rows, the app's page): a chosen catalog default glyph
wins; else the logo **most `device_app_logos` rows for its package name** (any phone; a tie goes to the most recently
updated, R2-L4) whose file exists; else the letter. A catalog app no phone has installed keeps its letter (the mockup's "Ordspill").

### 8.2 The places

- **Device › Apps** (`partials/device_app_row.html`): every row's tile - allowed, not allowed (the parent sees what
  they are switching on), Vibb One's. Under "Name and icon", the preview tile shows the logo when "App logo" is
  picked (`static/app-display.js` swaps `data-preview-own`'s word "Logo" for an `<img>` when the form carries
  `data-logo="/app-logos/<hash>"`). **For Automatic the preview keeps today's "Vibb style" tile** (R2-M6): it is
  "Show on phones as", and on the phone Automatic draws the app's white glyph on its tone where the app has one - a
  logo there would promise what the phone doesn't show. Q1 governs the rows' tiles, not this preview. The swap
  changes no layout height (the `<img>` fills the same 56 px tile), and no form or save is added: nothing here can move
  the page.
- **The Apps tab** (`apps_list.html`): the Katalog rows' tiles (§8.1's catalog rule). The phone rows keep the phones'
  avatars.
- **An app's page** (`tracked_app_detail.html`): the "Show on phones as" preview, as on Device › Apps (the logo for
  "App logo", "Vibb style" for Automatic), from the catalog rule.
- **Not the inbox**: an `app_updated` row's tile is the phone (its avatar), which is what the row is about first; the
  app's name is in the text. Not the lock screen's app lists or time rules' rows: they have no tiles today.

### 8.3 The look

- `<span class="app-row-tile app-row-logo">K<img src="/app-logos/<hash>" alt="" decoding="async"></span>`: the
  image filling the tile, a 1 px hairline ring (`box-shadow: 0 0 0 1px` the separator colour) so a
  white logo doesn't vanish on the white card (the mockup's Galleri and Filer) and a dark one on the night.
- The image is round (the phone's circle, §2.1), and so is every app tile in the PWA's lists (Q2, decided): today's
  glyph and letter tiles in the rows (`border-radius: 9px`) become `border-radius: 50%` with `overflow: hidden`, so one
  list has one shape and no letter colour shows at a logo's edge. The phones' avatars and the inbox's tiles are
  not app tiles and keep their shape.
- The letter and its tile stay under the image, as with the phones' pictures (`.avatar-img`: the `<img>` absolutely
  over the letter): a logo covers them completely (its circle fills the round tile), and one that fails to load -
  `alt=""`, so browsers draw nothing for it - leaves the letter tile showing. No script. `alt=""` is right: the row's
  text already names the app.
- **i18n (R2-L9)**: no new words anywhere - the tiles carry no text, the preview reuses `apps.display.*`, and the
  logs (`app_logos_full`, `app_logos_phone_full`) are operator logs, not the PWA's. Nothing to add to `nb` or `en`.
- The header is untouched (the bell stays last, top right).

### 8.4 Fallbacks

No launcher support, no logo drawn yet, a refused upload, a missing file after a restore: today's tile. Nothing on
any page waits for a logo.

## 9. Privacy

- The images are the apps' public art; the hash says nothing an app list doesn't already say, and the app list
  already reaches the server.
- **Nothing leaves the server**: no lookup on Play, F-Droid or any icon service; no logo is sent to any phone; the
  route is behind the session. The server never fetches an icon from anywhere - it only takes what its own phones
  draw.
- Not extracting the icon from a catalog APK on the server was considered and dropped: it would mean reading
  `resources.arsc` and adaptive XML vectors from untrusted APKs on the Pi, for a subset of apps (not Play's or the
  preinstalled ones the user asked for).

## 10. The contract and the tests

### 10.1 `server/testdata/app_logos.json`, byte-identical in `launcher/app/src/test/resources/`

```json
{
  "capability": "app_logos_v1",
  "size_px": 128,
  "max_bytes": 81920,
  "max_per_phone": 300,
  "max_wanted": 40,
  "png_base64": "<a 128 x 128 PNG, a plain disc drawn for the test>",
  "png_sha256": "<its hash>",
  "report": { "capabilities": ["app_logos_v1"], "installed_apps": [ {"package_name": "...", "label": "...", "logo": "<png_sha256>"}, {"package_name": "...", "label": "..."} ] },
  "answer": { "wanted_logos": ["<png_sha256>"] },
  "upload": { "path": "/api/devices/app-logos/<png_sha256>", "content_type": "image/png" },
  "answers": [ {"case": "stored", "status": 204}, {"case": "bad_hash", "status": 400}, {"case": "mismatch", "status": 400},
               {"case": "type", "status": 415}, {"case": "too_large", "status": 413}, {"case": "not_reported", "status": 409},
               {"case": "wrong_size", "status": 422}, {"case": "not_png", "status": 422} ]
}
```

- **Server** `app_logos::tests::the_contract`: posts `report` (instants moved), checks the answer is exactly `answer`,
  uploads `png_base64` to `upload.path` (204), then every `answers` row with its made-up body; a second report's
  answer is empty; the stored file decodes as 128 x 128 and is not byte-equal to an input with an appended chunk; the
  copies compare. `intake_limits::the_field_tables_keep_every_contract_key` reads `report`'s keys.
- **Launcher** `AppLogosTest`: encodes `report`'s apps exactly from `InstalledApp`, decodes `answer`, hashes
  `png_base64` to `png_sha256`, runs the pure upload plan (wanted ∩ cache, the cap of 40, stop on the first network
  error) and reads each `answers` status as "go on"; the copies compare.
- **Server, more**: `tests::app_logos` - an older launcher gets 204 and no rows; a report without `logo` keeps the
  row, without the package drops it and prunes the file; the budget and the caps stop the wanted list; a refused
  hash isn't wanted for 24 h; the route serves only a named hash (uppercase, 63/65 chars, `.png`, `../` all 404),
  needs the session, isn't on the phones' port; Device › Apps, the Apps tab and an app's page show the image, a
  chosen glyph wins, the letter without a logo; deleting a phone prunes its only logos; a restore wants the missing
  files again.
- **Server, R2 additions** (in `tests::app_logos`, not new files): a report whose `logo` is a number or an object is
  stored with the app and no logo (R2-M5); a PNG whose IHDR says 10,000 x 10,000 is `422` with no allocation, a body
  without the PNG signature `422`, an APNG stored as one 8-bit frame (R2-L2); the 301st upload of a day is `409` and a
  `422` counts (R2-M1); a phone over 300 named files is wanted nothing more while another phone still is (R2-M2);
  a phone row with `own` beats a catalog glyph default, and the catalog row takes the hash two phones name over a
  third phone's newer one (R2-L4, R2-L5); the Automatic preview keeps "Vibb style" (R2-M6).
- **Launcher, more**: `AppLogosCacheTest` (the key: each part changes it; a removed package leaves the index),
  and in `AppLogosTest` the answer's reading: a `204`, an empty or unreadable body is nothing wanted, a hash not in the
  cache is never uploaded, 41 entries upload 40 (R2-L7);
  and the existing `SyncScheduleTest` contract (`installed_apps_report.json`) unchanged - `logo` rides only in the
  new file.
- **CI**: `launcher.yml` and `server-ci.yml` gain `app_logos.json` (both copies) in their path filters, and the root
  `CLAUDE.md` its paragraph.

## 11. Steps

**Step 1 - server and contract** (branch `design-87-step1`): migration 0140, `app_logos.rs` (intake, wanted list,
upload, serving, prune, caps, refusals), the status answer, the device route and `DefaultBodyLimit`, the PWA's tiles
(§8), `style.css`, `app-display.js` (the preview), the contract file in both directories with the launcher's
`InstalledApp.logo` field, the answer's DTO and `AppLogosTest` (decode/encode only) - one commit for the contract,
the API rule. Notes: `server/CLAUDE.md` (the device API, the migration line, Fail closed), `docs/notes/server/pwa.md`
(a section "The apps' own logos"). Safe alone: no launcher sends `logo`, so nothing changes on any page.

**Step 2 - the launcher** (branch `design-87-step2`): `appdisplay/AppLogos.kt` (the cache, the drawing through
`drawOwnLogo` made callable off Home, the time budget), the report's field and `app_logos_v1`, `sendStatus` reading
the answer, the upload step among the cosmetic steps (R2-M4), the fixed light configuration (R2-M3). Emulator check on `jelly36`: a fresh phone's first two syncs fill
Device › Apps; an app update changes its logo; dark mode on and off changes no hash; Airplane mode during uploads
changes nothing else. Note:
`docs/notes/launcher/home-and-photos.md` "App names and icons".

Either order deploys safely: an old server ignores `logo` (`InstalledApp` has no `deny_unknown_fields`) and answers
204, which the new launcher reads as nothing wanted.

## 12. The user's questions - decided 2026-11-12

All three answered by the user on 2026-11-12, each as recommended.

**Q1. "Automatic" in the PWA: the real logo, or the phone's look?** On the phone, Automatic draws an app's
monochrome glyph white on a calm tone (when the app has one); the real logo only under "App logo". **Decided: the real
logo** in the PWA's rows for Automatic too - that is what was asked for, and it tells the parent at a glance which app
it is. (The other way needed a second image per app and showed Google's apps as white glyphs on slate/blue, much like
today's letter tiles.) The "Show on phones as" preview stays truthful to the phone (R2-M6).

**Q2. One shape in the lists?** **Decided: every app tile in the PWA's lists becomes round** (one list, one shape, the
same shape as the phone and the Name and icon preview), §8.3.

**Q3. Should one phone's logo also show on a sibling phone's rows when that phone hasn't sent one yet?** **Decided: per
phone** - a phone's rows show only that phone's own logo (an app can look different on two phones - another version or
another Android); the Apps tab's catalog rows take any phone's (§8.1, by the most phones, R2-L4).

## Revision 2: the design QA (2026-11-12)

Reviewed: `docs/design/87-app-logos.md` as committed at 763e7773 (the worktree was clean), against `main`'s code at
the branch's base (only PLAN commits on `main` since). Checked: `handlers/device_api.rs` (`status`, which answers
`204` from one place and has no early return; `cap_installed_apps` and its `..app`; `MAX_INSTALLED_APPS` = 1,500),
`models.rs` (`InstalledApp`: no `deny_unknown_fields`, lenient fields), `retention.rs` (`Budget`), `photos.rs`
(`PROCESSING` private, `is_valid_hash`), `handlers/backups.rs` (`build_backup_zip` adds named directories only, so
`data/app_logos/` is out by default; a restore swaps only the database), `security.rs`/`limits.rs` (the device token
bucket: 600 burst, 2/s - 40 uploads fit), `lock.rs` (the session-free paths), the launcher's `MdmApi.sendStatus`
(`Response<Unit>`, Retrofit 2.11), `performMdmSync`'s order after the report, `collectInstalledApps`,
`KidAvatars.drawOwnLogo`, `DetailedAppInfo` (Home's `getBadgedIcon(0)`), `colors_kid.xml`. Migration 0140 is free on
every local and remote branch (`main` ends at 0139).

### High

- **H1. Q3 used the names of real children.** The question named two kids from the household. Rewritten without
  names (§12). The repo never carries a kid's real name.

### Medium

- **M1. The upload budget named a mechanism that doesn't fit.** `retention::Budget` counts a table's rows received
  in a rolling day; an upload leaves no row, so a "`Budget` key `app_logos`" can't be built. Fixed in §7: an in-memory
  per-phone counter, charged before the decode (so `422`s count), bounded like the refusals.
- **M2. One hostile phone could fill the shared store for everyone.** A phone may report 1,500 apps; with made-up
  packages and noise PNGs (~64 KB each after the re-encode) at 300 a day it reaches the 64 MB cap in about four days,
  and from then no phone's new logo is wanted. Fixed in §5.3: a per-phone cap (300 files, 12 MB named by its rows).
- **M3. The night bit in the cache key churned every day.** An app with night icons gets a new hash at each dark-mode
  switch; the row moves, the old file is pruned, and the next switch uploads it again - twice a day, per such app, out
  of the same budget. Fixed in §2.1/§3: the icon is loaded in a fixed light configuration at 640 dpi, the night bit
  leaves the key; a hidden app without a package context is drawn only outside night mode.
- **M4. The uploads ran before time-critical steps.** "Right after the report and before anything else" put up to
  20 s of uploads ahead of the locate answer, the PIN events, the kid's time and contact requests and
  `CommandAnswers.flush`. Fixed in §4.4: among the cosmetic steps, after `reportBlockedDnsEvents`, before the weather
  pull and the self-update commit.
- **M5. A malformed `logo` would refuse the whole report.** The design said "drops the field, never the report", but a
  plain `Option<String>` fails the `Json` extractor on a number or object - losing the lock state and location of that
  report. Fixed in §4.1: `lenient_text`, and `cap_installed_apps` sets the field explicitly (its `..app` would carry it
  unchecked).
- **M6. The Automatic preview would misstate the phone.** The "Show on phones as" preview showing a logo for Automatic
  promises what the phone doesn't draw (its white glyph on the tone). Fixed in §8.2: Q1 governs the rows; the preview
  keeps "Vibb style" for Automatic. *The user may want the preview to follow Q1 too - one line to change.*

### Low

- **L1. 64 KiB was below the worst case.** A 128 x 128 RGBA PNG that doesn't compress is ~65.7 KB. The limit is
  80 KiB; the launcher reports no hash for a PNG over it (§3, §4.3, the contract's `max_bytes`).
- **L2. The decode spelled out.** Signature first, `PngDecoder::with_limits` (never a guessed format), APNG to one
  frame, 16-bit/palette to 8-bit RGBA, `PROCESSING` made `pub(crate)`. The decompression bomb is bounded twice: the
  IHDR is checked before any buffer, and the input is 80 KiB (§4.3).
- **L3. "Pixel for pixel" was too strong.** Home draws `getBadgedIcon(0)`, the logo the 640 dpi icon: the same drawing,
  not the same pixels; the software `clipPath` is aliased and the PWA's round tile clips it again (§2.1).
- **L4. The catalog rows followed the most recent phone.** One phone could set every catalog tile by reporting last.
  Now the hash most phones name, ties by the most recent (§8.1, §7).
- **L5. Rule 1 was ambiguous.** "Phone row, else catalog default" read as two glyph sources; design 14's phone row
  replaces the default as a whole, so a phone row with `own` or Automatic beats a catalog glyph (§8.1).
- **L6. The wanted list is an oracle** for "does some phone have this exact logo". Accepted inside one family, written
  down (§7).
- **L7. The launcher's reading of the answer** spelled out: `Response<ResponseBody>`, closed, bounded, lenient; only
  its own cached hashes are uploaded; `204`/empty/unreadable = nothing; a `200` is still a 2xx for design 76, the
  zone queue and the relocks (§4.2). An older server's `204` and an older launcher's `Response<Unit>` both hold.
- **L8. Pruning after a report** checks only the hashes that lost a row; the full scan stays in `retention::prune`
  (§5.3).
- **L9. i18n and the PWA rules** stated: no new words, no new form or save (nothing can jump the page), the header
  untouched (§8.3).
- **L10. The phone's cache index** is written by temp file and rename (§3).
- **L11. Determinism** is not needed for correctness: per Android build one file per logo, ~1-2 MB per build, inside
  the per-phone cap; a system update redraws once (§2.1).

### Unchanged after review

The `200`-with-body answer gated on `app_logos_v1` (an older launcher never sees a body), backups excluded with the
"no file -> wanted again" restore path, migration 0140, serving behind the session with `nosniff` and an immutable
cache, the contract file in both directories with both CI path filters, and the step order (either side may ship
first).
