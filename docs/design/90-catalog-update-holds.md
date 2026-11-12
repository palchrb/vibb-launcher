# Design 90: catalog update holds - a two-day wait, and a release that asks for more waits for the parent

Status: design, 2026-11-12; **revision 2 the same day** (the design QA and the user's decisions, §17) - where §17 and
an earlier section differ, §17 is the design. Server only (§11). Migration `0141_catalog_update_holds.sql` (the next
free number on `main` at `68f5e4b7`; no local branch takes 0141 - recheck at merge).

## 1. What and why

The user (2026-11-12, "jeg støtter ditt forslag på versionkontroll av apper!"), after weighing that auto-update brings
security fixes fast but lets a hijacked developer account push something bad, while manual review is safe but leaves
holes open:

- **Auto-update stays** for a release that keeps the package and the signer (pinned and enforced today:
  `apk.rs`, `tracked_apps::check_pins`, the launcher's `CatalogApkCheck`) **and asks for no new sensitive permission**.
- **A release that adds a sensitive permission is held**: the phones keep the release they have until the parent
  approves the new one from a push / inbox row ("Signal 7.3.0 ber om tilgang til kontakter") with one tap.
- **A GitHub release waits two days** before the phones get it, so a pulled or hijacked release rarely reaches a
  phone. f-droid.org's releases don't wait (F-Droid builds them itself). "Update Now" on the app's page skips the wait.
- Optional: a per-app "Ask me before every update" switch.

What this guards against, honestly: the pins already stop a release signed by someone else. What is left is the
developer's own key or account turned against the kid - a hijacked GitHub account uploading a doctored APK signed with
a stolen key, or a developer who sells out. The two-day wait catches the loud case (the community notices, the release
is pulled before our server takes it); the permission hold catches the quiet case that needs a new door (contacts,
SMS, an accessibility service). Neither catches malice inside permissions the app already had: that is the residual
risk of any auto-update, stated in §12.

Upstream `siesta5787/kid-phone-server` b508eb2ce2 (its 0.21.0) built the same idea: every release read, held when a
new app, another package, an older version, another key, unsigned, new sensitive permissions, no arm64 build or
unreadable; otherwise held for a per-app `hold_days` counted from when the server found it. What we take and what we
don't is §13.

## 2. Which rows it applies to

| Row | Two-day wait | Permission hold | Why |
|---|---|---|---|
| GitHub app | yes | yes | the case this design is for |
| F-Droid, `f-droid.org` | **no** | yes | F-Droid builds from source and signs (or reproducibly verifies) itself; its build queue is already days behind a tag. A malicious *source* change still needs a new permission to reach the contacts - the hold is there for that. |
| F-Droid, any other repo (IzzyOnDroid, self-hosted) | **yes** (D2) | yes | IzzyOnDroid ships the developer's own APK from their GitHub release: the hijack path is GitHub's. A self-hosted repo is the parent's, but it may mirror anything. |
| Manual upload | no | no | the parent's own act, with the file in hand. It widens the accepted set (§5). |
| Vibb One (`is_launcher`) | no | no | ours: signed with our release key in the `release` environment (reviewer rule), pinned on the phone by `SelfUpdate`; it is the device owner and grants itself what it needs - a hold would only delay our own fixes. Its own update path (3 days on any network, the update fence) is unchanged. |
| Vibb music (`package_name` in `music::MUSIC_PACKAGES`) | no | no | ours, the same key and the same release path. |
| Install Now (design 37) | - | - | offered only on the launcher's row (404 for any other app), so it is outside both rules. Nothing changes. |

"Ours" is decided by the row (`is_launcher`, or a pinned package in `MUSIC_PACKAGES`), never by the tag or the repo:
a catalog row pointed at another repo with a `music-v` prefix gets no exemption without the pinned package.

## 3. Which permissions are sensitive

**The rule**: Android's *dangerous* (runtime) permissions that reach the kid's data, sensors or calls, plus the
*special accesses* and *bound services* that give an app power over the phone itself. The list is fixed in code
(`catalog_holds::SENSITIVE`, one row per permission name: its group key and why), reviewed with each Android release;
an unknown permission is not sensitive (a hold for every new `FOREGROUND_SERVICE_*` would teach the parent to tap
Approve blind).

Why the runtime ones matter on *our* phones: design 68 never grants a sensor; the kid's own dialog decides
(`GRANT_STATE_DEFAULT`, camera aside). So a new runtime permission is one tap by a 9-year-old away. The special
accesses need Android's page, which on a managed phone opens only in the PIN-gated pause - so they are a weaker door,
but newly declaring one is the classic sign of a compromised app, and the hold costs one tap.

The diff is **by permission name**, the words by group (§5): an app that had `READ_PHONE_STATE` and adds `CALL_PHONE`
is held, although both are "Phone" - Android auto-grants within a group once one is granted, which is exactly why the
new name matters.

| Group key | Permissions (`android.permission.` left out) | Words en / nb | Why |
|---|---|---|---|
| `sms` | `READ_SMS`, `SEND_SMS`, `RECEIVE_SMS`, `RECEIVE_MMS`, `RECEIVE_WAP_PUSH` | Text messages / SMS og MMS | the kid's messages; Vibb One's SMS engine and contact rules (design 77) assume no other reader or sender; sending costs money |
| `contacts` | `READ_CONTACTS`, `WRITE_CONTACTS`, `GET_ACCOUNTS` | Contacts / kontakter | the family's numbers; the classic data grab |
| `call_log` | `READ_CALL_LOG`, `WRITE_CALL_LOG`, `PROCESS_OUTGOING_CALLS` | Call history / anropslogg | who the kid talks to |
| `phone` | `CALL_PHONE`, `ANSWER_PHONE_CALLS`, `READ_PHONE_NUMBERS`, `READ_PHONE_STATE`, `USE_SIP`, `ADD_VOICEMAIL`, `ACCEPT_HANDOVER` | Phone calls / telefonsamtaler | calls outside the managed call rules; the phone's own number |
| `location` | `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION` | Location / posisjon | where the kid is |
| `location_background` | `ACCESS_BACKGROUND_LOCATION` | Location in the background / posisjon i bakgrunnen | where the kid is, all day - its own grant on Android |
| `media_location` | `ACCESS_MEDIA_LOCATION` | Where photos were taken / hvor bildene ble tatt | home and school from the photos' GPS |
| `camera` | `CAMERA` | Camera / kamera | sensor (design 68 can switch it off per phone, but only for a parent who looks) |
| `microphone` | `RECORD_AUDIO` | Microphone / mikrofon | sensor |
| `body` | `BODY_SENSORS`, `BODY_SENSORS_BACKGROUND`, `ACTIVITY_RECOGNITION` | Body sensors and activity / kroppssensorer og aktivitet | health data; walking/driving tells routines |
| `calendar` | `READ_CALENDAR`, `WRITE_CALENDAR` | Calendar / kalender | dangerous level; Dagen (design 69) puts the family's events there |
| `nearby` | `BLUETOOTH_SCAN`, `BLUETOOTH_CONNECT`, `BLUETOOTH_ADVERTISE`, `NEARBY_WIFI_DEVICES`, `UWB_RANGING` | Nearby devices / enheter i nærheten | location by beacons; pairing past the Bluetooth rule (`bluetooth_any_device`) |
| `media` | `READ_MEDIA_IMAGES`, `READ_MEDIA_VIDEO`, `READ_MEDIA_AUDIO`, `READ_MEDIA_VISUAL_USER_SELECTED`, `READ_EXTERNAL_STORAGE`, `WRITE_EXTERNAL_STORAGE` | Photos, videos and music / bilder, videoer og musikk | the kid's photos |
| `all_files` | `MANAGE_EXTERNAL_STORAGE` | All files / alle filer | every file on the phone |
| `overlay` | `SYSTEM_ALERT_WINDOW` | Shows over other apps / vises over andre apper | fake screens over Vibb One, a kiosk escape aid |
| `install` | `REQUEST_INSTALL_PACKAGES` | Installs apps / installere apper | an app store inside an app (the model forbids one) |
| `usage` | `PACKAGE_USAGE_STATS` | App usage data / data om appbruk | design 68's name; what the kid does all day |
| `notifications` | `BIND_NOTIFICATION_LISTENER_SERVICE` **on a component** | Reads notifications / leser varsler | every message's text, every app's |
| `accessibility` | `BIND_ACCESSIBILITY_SERVICE` on a component | Controls the screen (accessibility) / styrer skjermen (tilgjengelighet) | reads and taps everything; the malware favourite |
| `device_admin` | `BIND_DEVICE_ADMIN` on a component | Device admin / enhetsadministrator | can lock or wipe; fights the device owner |
| `vpn` | `BIND_VPN_SERVICE` on a component | VPN | all traffic; ours is always-on with `DISALLOW_CONFIG_VPN` (S-L11), so this is a signal more than a door |
| `keyboard` | `BIND_INPUT_METHOD` on a component | A keyboard / tastatur | sees everything typed, the PINs too |
| `internet` | `INTERNET` | Internet / internett | **D3**: only an app that had none - an offline app going online is how data leaves |

**Not sensitive** (shown in the full list, never held): `QUERY_ALL_PACKAGES` (D4: it tells the app the list of
installed apps - fingerprinting, not the kid's data; common in the apps that would trip it), `POST_NOTIFICATIONS`,
`WRITE_SETTINGS`, `MANAGE_MEDIA`, `SCHEDULE_EXACT_ALARM`/`USE_EXACT_ALARM`, `USE_FULL_SCREEN_INTENT` (design 68's
special accesses are approved per app on the phone in the pause - that approval is the gate), every normal-level
permission, every permission outside `android.permission.` (another app's or Google's: they grant nothing of
Android's).

**What counts as declared**: every `<uses-permission>`, `<uses-permission-sdk-23>` and `<uses-permission-sdk-m>`
anywhere in the manifest (Android honours them only under `<manifest>`; counting them anywhere over-counts, which is
the safe side) - except one whose `android:maxSdkVersion` is below 34, the launcher's `minSdk` (our phones are Android
14+, so it is never requested; `catalog_holds::PHONE_MIN_SDK`, with a test that reads the launcher's
`app/build.gradle.kts` `minSdk` so the two can't drift). And the `android:permission` of **any** element (service,
receiver, activity, provider, application) whose value is one of the five `BIND_*` above: Android binds an
accessibility service, notification listener, VPN, keyboard or device admin only when the component carries that
permission, so it is the reliable mark (an intent filter alone does nothing).

## 4. Reading the manifest (`apk.rs`)

`ApkIdentity` gains `permissions: Option<Vec<String>>` - every declared name per §3 (the bind ones as their bare
names, so they never clash with a `uses-permission`), sorted, deduplicated; `None` when it can't be read.

The walk today stops at `<uses-sdk>` and keeps "what was read" after a damaged chunk - right for the package, **wrong
for permissions**: a name after the damage would be missed. So the permission read is all-or-nothing:

- `walk_manifest` goes on to the end of the document (the `<uses-sdk>` early return goes); the package, code, name and
  `min_sdk` keep their lenient reading.
- `permissions` is `Some` only when every chunk parsed and the walk ended exactly at the document's end, and for every
  counted element: `android:name` (resource id `0x01010003`; `android:permission` `0x01010006`;
  `android:maxSdkVersion` `0x01010271`) found **by resource id** (Android reads attributes by id; our name fallback
  would be a parser differential an attacker could use - a `name` attribute without the id that Android ignores and we
  read), present once, a string whose typed value and raw value agree, and a `maxSdkVersion` that is a number when
  present. Anything else - a reference, a duplicate, a damaged chunk, more than `MAX_PERMISSIONS` (500) names, a name
  over 200 bytes - is `None`.
- Element names are matched with or without a namespace prefix (a `foo:uses-permission` counts).

`None` is never "no permissions": an unreadable list holds the release (§6), shown as "Couldn't read what it asks
for".

## 5. The baseline and the diff

**The accepted set** (`tracked_apps.accepted_permissions`, JSON of sensitive names): what the parent has let through
for this app. It grows only by the parent - never by a sync:

- the app's **first release** (GitHub, F-Droid or upload): its sensitive names. The first release is never held: the
  app reaches no phone until the parent ticks it on a phone, and the app's page shows what it asks for before that
  (§9.1). A first release whose list can't be read leaves the set `[]` (so its next release is judged against nothing
  and held if it asks for anything sensitive).
- **Approve**: the held release's sensitive names are added.
- **A manual upload**: its sensitive names are added (the parent chose the file).
- A release served without a hold adds nothing - by definition it brought nothing new.

So a release that drops Contacts and a later one that brings it back is not asked about again (the parent said yes
once for this app), and nothing a developer does can widen the set. "Forget Package and Key…" clears the set with the
pins (another package or key is another app; its next first release seeds it again).

**Existing apps** at the migration: `accepted_permissions` NULL; `backfill_release_permissions` at startup (after
`backfill_version_codes`, the same pattern: read the cached served file, store only while the row still names that
file) writes `latest_release_permissions` and seeds `accepted_permissions` with the served release's sensitive names.
A file that can't be read leaves `accepted_permissions` NULL: the next release is then held if it asks for anything
sensitive ("the release on the phones couldn't be read" in the ⓘ). Fail closed.

**The diff** (`catalog_holds::judge`, pure): for a candidate release with `permissions` P and the app's accepted set A:

| Case | Verdict |
|---|---|
| ours (§2) or a manual upload | `Serve` |
| P is `None` | `Hold(Unreadable)` |
| `sensitive(P) \ A` non-empty (A NULL counts as empty) | `Hold(Permissions(new names))` |
| the app's `always_ask` is on | `Hold(AlwaysAsk)` |
| otherwise | `Serve` |

Shown by group (the group's words once, in the table's order): "Asks for Contacts and Location" - with the exact names
behind the ⓘ.

## 6. The two-day wait

`catalog_holds::RELEASE_WAIT` = 48 h, fixed (D5: no per-app field).

**Counted from** the moment the release became public at the source, not when our server saw it:

- GitHub: the later of the release's `published_at` and the chosen asset's `updated_at` (`GithubRelease` and
  `ReleaseAsset` gain them, `#[serde(default)]`). The asset's own time matters: a hijacked account can swap the APK on
  a release published months ago, and that swap is then two days young.
- F-Droid other than f-droid.org: the index version's `added` (ms; `IndexVersion` gains `added: Option<i64>`, parsed
  with the version - the index is signed, §play-and-updates "The repo's signature").
- No readable time (a field missing or unparseable): the first time this server saw that exact `(tag, asset id)` -
  stored as `waiting_release_ready_at` = seen + 48 h and kept while the same release is seen again, so a restart or a
  sweep never moves it later or earlier.
- A time in the future (skew) stays as it is: the release waits until it + 48 h.

**Which release the sync takes** (`release_from` / the F-Droid pick): today the newest matching one. Now the newest
matching release that is **ripe** (its wait over) and ahead of the declined one (§7); the newest overall, when it isn't
ripe, is noted as **waiting** (`waiting_release_tag`, `_asset_id`, `_ready_at`) - listed, **not downloaded**. A
project that releases every day still reaches the phones: yesterday's-but-one release is ripe while today's waits.
The `OlderVersion` guard and `release_ahead` are unchanged: a ripe release that isn't ahead of the served one is
nothing to do. A waiting release that disappears from the list (pulled) clears the waiting columns at the next sync -
the page simply stops showing it. That is the point of the wait.

**Check Now** honours the wait: it finds and shows. It never downloads a waiting release.

**Update Now** (on the waiting row, §9.1) is the parent's one-shot override (the standing rule: acts at once on the
exact target, no window): the form posts the waiting `tag` and `asset_id`; the handler takes the app's sync guard,
lists the releases again, and syncs that exact release with the wait skipped. If it is no longer listed: "7.3.0 isn't
offered any more" and nothing else. **It does not skip the hold** (D6): if the release asks for something new, the
parent sees Approve at once, one more tap - Update Now was pressed before anyone knew what the release asks for.

## 7. The held release

**One held release per app**, kept on disk next to the served one (`data/tracked_apps/<id>/<tag>-<asset>.apk`, the
same naming), its row in `tracked_app_held_releases`. The served release (`latest_release_*`) is untouched, so
`/api/devices/apps`, the download, `X-Release-Tag`, `app_updated` and Install Now see nothing of it.

`sync_release` after the checks it has today (download, hash, package, pins, `check_new_package`) asks `judge`:
`Serve` stores as today; `Hold` writes the held row instead (`INSERT ... ON CONFLICT(tracked_app_id) DO UPDATE`),
removes the previous held file, keeps the remembered-refusal logic out of it (a hold isn't a refusal - Approve must
always work), raises `app_update_held` (§8) and nudges no phone. A `(tag, asset id)` equal to the held one is
"nothing to do", like the served one.

**Several in a row** (7.2.0 served, 7.3.0 held, 7.4.0 ripens):

- 7.4.0 brings nothing new against the accepted set → served at once; 7.3.0's held row and file go (it is superseded,
  and older). The phones skip 7.3.0.
- 7.4.0 also brings something new → it replaces 7.3.0 as the held one (the newer is what the parent should approve);
  a new event and push for 7.4.0 with *its* new groups. The phones stay on 7.2.0.
- While a release is held, an older ripe one between the served and the held one is not offered (the iterator stops
  at the newest ripe). The parent's answer is one tap away; D7 if the user wants the in-between release served.

**Approve** (`POST /apps/tracked/{id}/held/approve`, hidden `tag` + `asset_id`): under the app's sync guard (a running
check answers `busy`, "A check is running - try again in a moment"), one transaction: the held row must still be that
release, else `stale` ("That release isn't waiting any more", nothing done); move its values into `latest_release_*`,
add its sensitive names to `accepted_permissions`, delete the held row; then remove the old served file
(`replace_cached_file`'s order), nudge the phones (`nudge_phones_with_app`). No `app_update_available` for it - the
parent just did it. Security Log: `catalog_release_approved` "<app> <version>: approved, asks for <groups>".

**Keep the old** (`.../held/decline`, the same exact-target fields): the same guard and staleness rule; the held file
and row go; `declined_release_tag`/`_asset_id` = that release. The sync never takes a release at or behind the
declined one (`release_ahead` against it) - it is not downloaded again hourly. The next release ahead of it is judged
afresh against the unchanged accepted set, so if it still asks for Contacts the parent is asked again. Security Log:
`catalog_release_declined`. **Ask Again** (on the "Kept 7.2.0" row) clears the declined columns; the next sync (it
starts one, as Check Now does) holds it again.

**Removal of the cause**: "Forget Package and Key…" and a change of source or repo (where allowed today) clear the
held row and file, the waiting and declined columns. Deleting the app removes the directory (today) and the row
(cascade). A disabled app (`enabled = 0`) keeps its held release; it reaches no phone anyway.

**The hold on an app on no phone**: the page and the Apps tab show it, but no inbox row or push (nothing is waiting on
any phone). Ticking the app on a phone while a release is held gives that phone the served release, as today.

## 8. The inbox event and its push

New kind **`app_update_held`** (`parent_events::Kind::AppUpdateHeld`, last in `Kind::ALL`), the catalog's like
`app_update_available`: `device_id` NULL, key `<app id>:<tag>@<asset id>` under `idx_parent_events_catalog`, so a
sweep that finds the same held release raises nothing. Normal urgency, TTL 86400 (a held release keeps; the page is
the truth), foldable per app. Params `HoldParams {app_id, app, from?, to, groups: [key], reason}` worded by `told` /
`pushed`, target `/apps/tracked/{id}#update`. Raised only when the app is ticked on at least one phone (§7).

| | en | nb |
|---|---|---|
| inbox title | Signal 7.3.0 waits for you | Signal 7.3.0 venter på deg |
| inbox line | 14:02 · asks for Contacts and Location | 14:02 · ber om tilgang til kontakter og posisjon |
| line, unreadable | 14:02 · couldn't read what it asks for | 14:02 · fikk ikke lest hva den ber om |
| line, always ask | 14:02 · 7.2.0 → 7.3.0 | 14:02 · 7.2.0 → 7.3.0 |
| push title | Signal 7.3.0 asks for Contacts | Signal 7.3.0 ber om tilgang til kontakter |
| push title, 3+ groups | Signal 7.3.0 asks for Contacts and 2 more | Signal 7.3.0 ber om tilgang til kontakter og 2 til |
| push body | Approve it or keep 7.2.0 | Godkjenn eller behold 7.2.0 |

(The push title names the first group in §3's order - the table puts the heaviest first.)

**The switch**: its own, **"An app update waits for you" / "En appoppdatering venter på deg"**,
`notification_prefs.push_app_held`, **on by default** (D1). Not the App updates switch: that one is off by default,
so the parent who never turned it on would never hear of a hold, and the app would silently stop getting security
fixes - the worst of both worlds. Not "always pushed" either: that is kept for the account's own safety (passkey,
push lost, the certificate check); a parent who checks the Apps tab weekly may turn this off. Per parent (design 62).

## 9. The PWA

The standing rules: a save never jumps to the top (every form returns to its group's fragment, `#update`,
`#updates`, `#release`; `tests::tracked_apps::every_form_on_the_app_pages_returns_to_its_page` posts the new forms
too); the night band unchanged, the bell last top right; every word in `i18n` (en + nb, `i18n::tests`).

### 9.1 The app's page (`tracked_app_detail.html`)

A new group **`#update`** right after the Version / Check Now group, rendered only when something is held, waiting or
declined (never on the launcher's or the music app's page):

- **Held**: row title "7.3.0 waits for you" / "7.3.0 venter på deg", sub-line "Asks for Contacts and Location" /
  "Ber om tilgang til kontakter og posisjon" (unreadable: "Couldn't read what it asks for" / "Fikk ikke lest hva den
  ber om"; always ask: "You approve every update of this app" / "Du godkjenner hver oppdatering av denne appen"),
  orange like the page's other states. Two buttons on the row: **Approve** / **Godkjenn** (filled, `button-small`) and
  **Keep 7.2.0** / **Behold 7.2.0** (`button-secondary`) - no confirm on either (one tap, the user's ask; Keep is
  undone by Ask Again). An ⓘ under it: "What 7.3.0 asks for" - the new groups first, marked new, then the ones it
  already had, then the exact names; the phones keep 7.2.0 until you answer.
- **Waiting**: "7.4.0 reaches the phones Thu 14:02" / "7.4.0 kommer til telefonene tor. 14:02" (Phone Setup's zone,
  `display_time`), sub-line "Published Tue 14:02 · new releases wait two days" / "Publisert tir. 14:02 · nye versjoner
  venter i to døgn", and **Update Now** / **Oppdater nå** (`button-small`). The ⓘ: "The wait gives time for a broken or
  hijacked release to be noticed and pulled before it reaches the phones. Update Now skips it; a release that asks for
  something new still waits for your approval."
- **Kept** (declined): "Kept 7.2.0 · 7.3.0 declined" / "Beholdt 7.2.0 · 7.3.0 avslått", **Ask Again** /
  **Spør igjen** (`button-secondary`).

A form's answer is a `?held=<word>#update` flash in the group (`approved`, `declined`, `stale`, `busy`, `gone` for
Update Now's vanished release, `asked_again`), like Install Now's.

**Source & Signing › Release** gets one row, **Asks for** / **Ber om**: the served release's sensitive groups
("Contacts, Camera" / "kontakter, kamera"; "Nothing sensitive" / "Ingenting sensitivt"; "Couldn't be read" / "Kunne
ikke leses"), with an ⓘ listing every declared permission. For an app whose first release just arrived this is what
the parent reads before ticking it on a phone.

**Updates** (`#updates`) gets the switch **Ask me before every update** / **Spør meg før hver oppdatering**
(`always_ask`, one form, the group's autosave, `<noscript>` Save, back to `#updates`); its footer: "Off: an update
that asks for nothing new reaches the phones by itself." Not on the launcher's or music app's page. Turning it off
while a release is held for `AlwaysAsk` alone leaves it held (the parent answers it) - simpler than a re-judge.

### 9.2 The Apps tab

The catalog row of an app with a held release shows the badge **Waits for you** / **Venter på deg** (orange, the
`Off` / `Update failed` slot, ranked first: it is the only one the parent must act on). A waiting release shows
nothing extra (normal life). No badge on the tab bar: the inbox is where things wait.

### 9.3 Notify Me About

The new switch in the app-updates part, above "App updates", with its default on.

## 10. Data model (migration `0141_catalog_update_holds.sql`)

```sql
-- Design 90: the served release's declared permissions, the set the parent accepted, the per-app ask switch,
-- the release waiting out its two days, and the one the parent declined.
ALTER TABLE tracked_apps ADD COLUMN latest_release_permissions TEXT;  -- JSON array or NULL (unread)
ALTER TABLE tracked_apps ADD COLUMN accepted_permissions TEXT;        -- JSON array of sensitive names; NULL = unknown
ALTER TABLE tracked_apps ADD COLUMN always_ask INTEGER NOT NULL DEFAULT 0;
ALTER TABLE tracked_apps ADD COLUMN waiting_release_tag TEXT;
ALTER TABLE tracked_apps ADD COLUMN waiting_release_asset_id INTEGER;
ALTER TABLE tracked_apps ADD COLUMN waiting_release_published_at TEXT; -- the source's time, for the page; NULL = unknown
ALTER TABLE tracked_apps ADD COLUMN waiting_release_ready_at TEXT;     -- UTC RFC 3339
ALTER TABLE tracked_apps ADD COLUMN declined_release_tag TEXT;
ALTER TABLE tracked_apps ADD COLUMN declined_release_asset_id INTEGER;

CREATE TABLE tracked_app_held_releases (
    tracked_app_id INTEGER PRIMARY KEY REFERENCES tracked_apps(id) ON DELETE CASCADE,
    release_tag TEXT NOT NULL,
    asset_id INTEGER NOT NULL,
    file_path TEXT NOT NULL,
    asset_name TEXT,
    asset_size INTEGER,
    sha256 TEXT NOT NULL,
    version_code INTEGER,
    permissions TEXT,                       -- JSON array or NULL (unread)
    reason TEXT NOT NULL CHECK (reason IN ('permissions', 'unreadable', 'always_ask')),
    new_permissions TEXT NOT NULL DEFAULT '[]',
    held_at TEXT NOT NULL
);

ALTER TABLE notification_prefs ADD COLUMN push_app_held INTEGER NOT NULL DEFAULT 1;
```

`sync_release` writes `latest_release_permissions` with every stored release; a manual upload too. The held file is
in the app's directory, so `remove_partial_downloads` and the app's removal need nothing new; startup deletes a held
file whose row is gone and a held row whose file is gone (the row: logged, the next sync downloads it again).
`server/CLAUDE.md`'s migration list and the `tracked_apps` bullet, `docs/notes/server/play-and-updates.md` (a new
"Update holds" section) and `notifications.md` ("App update notices") are updated with the build.

## 11. The phone

**Nothing.** The phone only ever sees `latest_release_*` through `/api/devices/apps` and the download; a held or
waiting release is not in either, `scoped_apps` is unchanged, the policy's keys are unchanged
(`policy_json_keys_snapshot` untouched), no contract file changes. Confirmed against `device_api::scoped_apps`
(selects on `latest_release_tag`) and `apps/{id}/download` (serves `latest_release_file_path`). The launcher's own
self-update and Install Now read only the launcher's row, which is exempt. Server-only build and release.

## 12. Security

- **No escape by renaming**: permission names are Android's own strings - the system grants by exact name, so a
  renamed one grants nothing. The diff is against the accepted set, not the previous tag, so a new tag, a re-upload of
  the same build, a rolling tag's new asset or a drop-then-re-add can't sneak a name through: only the parent widens
  the set. The bind permissions are read off any element, by resource id.
- **Fail closed**: an unreadable permission list holds (§4: all-or-nothing, by resource id, a parser differential
  refused); an unreadable served release leaves the baseline unknown and the next sensitive release held; the pins
  are checked before the hold (a release with another key is still refused, never offered for approval - unlike
  upstream, where a key change was something to approve).
- **The wait can't be shortened by the attacker**: GitHub's `published_at` and the asset's `updated_at` are set by
  GitHub; swapping an asset on an old release restarts the clock. A missing time waits from first sight.
- **Races**: approve, decline, Ask Again and Update Now take the app's sync guard and act only on the exact release the
  form names.
- **What remains**: malicious code inside permissions the app already had, after two quiet days. The phone's
  `CatalogApkCheck` and the pins keep it to the developer's own key. That is the price of updates arriving at all -
  "Ask me before every update" is there for the app a parent trusts less.
- `sharedUserId` (a release joining another app of the same signer to share its grants) is deprecated and rare; noted,
  not handled.

## 13. Upstream b508eb2ce2 - what we take, what we don't

Taken: the permission diff against what phones run, the label-per-group idea, a two-day wait, no wait for the
launcher or an upload, the held release kept beside the served one so the device API is unchanged, a declined release
not offered again.

Not taken: (a) its schema - a dozen `pending_*` columns and reasons stored as English sentences; ours is a held table,
reasons as keys, words at render (i18n). (b) Holding for "another key", "another package", "unsigned" or "older": ours
already **refuses** those (M10 pins, the `OlderVersion` guard) - turning a refusal into an approvable hold would weaken
the pins. (c) The no-arm64 hold: an F-Droid per-ABI pick is a known gap waiting for its first app (play-and-updates,
"The ABI"); D8. (d) The wait counted from the server's discovery: ours from the source's publication. (e) A per-app
`hold_days` field: one fixed wait. (f) Its sensitive list counted `INTERNET` always and `QUERY_ALL_PACKAGES`; ours
(§3) adds the bound services, which upstream missed (they are not `<uses-permission>`).

## 14. Tests

- `apk::tests`: a fixture with `<uses-permission>` (one with `maxSdkVersion="32"`), `uses-permission-sdk-23`, a
  service with `BIND_ACCESSIBILITY_SERVICE` and a receiver with `BIND_DEVICE_ADMIN` (built with aapt2 into
  `testdata/apk/`, a throwaway key, no code, like the others) reads exactly its names; the hand-built `manifest_xml`
  cases: a name by the name fallback only, a reference, a duplicate `name`, a damaged chunk after `<uses-sdk>`, typed
  and raw values that disagree - each `None`, never a panic; the existing package/code reading unchanged.
- `catalog_holds::tests` (pure): every `SENSITIVE` row has words in both languages; `judge`'s table (§5) row by row;
  the group order and the "and 2 more" title; the wait's anchor (published, asset updated later, `added`, none) and
  the ripe/waiting split over a newest-first list (a daily project still serves the release two days old);
  `PHONE_MIN_SDK` equals the launcher's `minSdk` read from `launcher/app/build.gradle.kts`.
- `tracked_apps::tests` through a fake GitHub and the in-process F-Droid repo: a release with nothing new is served
  after the wait and not before; f-droid.org isn't waited for, IzzyOnDroid is; a new permission holds (phones' list
  unchanged, no nudge, one event over three sweeps); a newer clean release supersedes the held one (file gone); a
  newer sensitive one replaces it; an unreadable list holds; the first release seeds the set and is served; an upload
  is served and widens the set; the launcher's and the music app's rows are never waited for or held; a pulled
  waiting release clears; an asset swapped on an old release waits.
- `tests::catalog_holds` (the admin router): Approve serves and nudges, with the exact-target `stale` and `busy`
  answers; Keep declines, isn't downloaded again, the next release is asked about again; Ask Again; Update Now syncs
  the exact release and still holds a sensitive one, `gone` for a vanished one; every answer lands on `#update`
  (`assert_lands`); the switch saves to `#updates`; the Apps tab badge; the inbox row, the push only with
  `push_app_held` on (default on with no row) and only for an app on a phone; the Security Log lines; the backfill
  seeds the set and leaves it NULL for an unreadable file; migration 0141 on a copy of a 0140 database keeps every
  row.
- `i18n::tests` keep both catalogs equal (the new keys). No launcher or music test changes; no contract file.

## 15. Steps

One server round (`design-90`), in this order, each green before the next:

1. `apk.rs` permissions (§4) and `catalog_holds.rs` (the list, `judge`, the wait's anchor) - pure, with their tests.
2. Migration 0141, `latest_release_permissions` written by every store and the backfill seeding the accepted set.
3. The sync: ripe/waiting pick (GitHub and F-Droid's other repos), the hold in `sync_release`, supersede, decline
   skipping, Update Now's exact sync.
4. The event, the switch, the page's `#update` group, the Release row, the Always-ask switch, the Apps tab badge, the
   words.
5. Notes and CLAUDE.md; code QA; then a live check on the VM: an app with a clean release served after two days, a
   test repo's release adding `READ_CONTACTS` held, approved from the push.

No launcher or music release needed.

## 16. Decisions for the user

The questions as first put. **The user's answers (2026-11-12) are in §17.1**; D1 and D5 went another way than
recommended here.

| # | Question | Recommendation |
|---|---|---|
| D1 | The push for a held update: its own switch, on by default - or the existing "App updates" (off by default), or always pushed? | **Its own switch, on.** With "App updates" off by default nobody would hear of a hold, and the app would quietly stop getting fixes. |
| D2 | Which F-Droid repos skip the wait? | **Only f-droid.org.** IzzyOnDroid ships the developer's own APK from GitHub - the same hijack path. A self-hosted repo waits too. |
| D3 | `INTERNET` newly asked for by an app that had none: hold? | **Yes.** Rare and telling: an offline app going online is how data leaves. |
| D4 | `QUERY_ALL_PACKAGES` (sees the list of installed apps): hold? | **No.** It reveals the app list, not the kid's data, and is common; it shows in the full list. |
| D5 | The wait: fixed two days, or a per-app number of days (upstream's field)? | **Fixed two days.** One fewer setting; "Ask me before every update" covers the app a parent trusts less. |
| D6 | Does "Update Now" also approve new permissions? | **No.** It was pressed before anyone saw what the release asks for; a held release shows Approve at once - one more tap. |
| D7 | While a release is held, serve an older clean release that came between (7.2.1 between 7.2.0 and a held 7.3.0)? | **No, not now.** More downloads and states for a rare case; the hold is one tap away. |
| D8 | Hold a release with no arm64 build (upstream did)? | **No, not in this design.** Our phones are all arm64 and Android refuses the install; F-Droid's per-ABI pick waits for its first app as planned. |
| D9 | The per-app "Ask me before every update" switch: build it now? | **Yes** - cheap (one column, one switch), and the one answer for an app the parent trusts less. |
| D10 | The first release of a new app: hold it too? | **No.** It reaches no phone until the parent ticks it, and the page shows what it asks for first. |

## 17. Revision 2: the design QA and the user's decisions (2026-11-12)

### 17.1 The user's decisions

The user, 2026-11-12, on the questions of §16:

> "På app oppdateringer, ønsker ikke andre varsel enn det som er i dag når en oppdatering venter. I dag kommer det et
> varsel i innboks om oppdatering for app x, og så kommer det et likt et når den er oppdatert. Forskjellen nå er bare
> at den holdes i to dager? Og om du vil ha den ASAP så trykker man på knappen? Fint om dette kan være konfigurerbart i
> antall timer et sted kanskje.. Egne apk kan vel også gå med en gang akkurat som for f droid"

and, later the same day, replacing the one global wait:

> "Lurer på om vi skal la det være konfigurerbart per pakke istedet; når vi legger til et nytt repo etc, så kan du
> velge x timers delay? ... Som en del av gui? Så kan man selv velge hva man er mer eller mindre komfortabel med?"

| # | Decided |
|---|---|
| D1 | **No new push switch, no new event kind.** A waiting or held release is told only through today's inbox events: `app_update_available` (worded for the wait or the hold, §17.3) and `app_updated` when a phone has it. Their push stays under today's "App updates" switch (off by default). §8's `app_update_held`, its switch `push_app_held` and §9.3 go. |
| D2 | As recommended, now as the **default** of the per-app wait (D5): f-droid.org 0 hours, every other F-Droid repo 48. |
| D3 | As recommended: `INTERNET` newly asked for is held. |
| D4 | As recommended: `QUERY_ALL_PACKAGES` isn't held. |
| D5 | **Changed: the wait is per app, in hours** - chosen in the Add App form with a default by source (GitHub and other F-Droid repos 48, f-droid.org 0), changeable on the app's page under Updates, 0 = no wait, at most 336 (14 days). No global setting (§17.2). "Update Now" skips it. Manual uploads, Vibb One and Vibb music have none. |
| D6 | As recommended: "Update Now" never approves new permissions. |
| D7 | As recommended: no in-between release served while one is held. |
| D8 | As recommended: no arm64 hold. |
| D9 | As recommended: "Ask me before every update" is built. |
| D10 | As recommended: the first release isn't held. |
| - | **Own uploads go at once**, like f-droid.org's releases: no wait, no hold (§2's table, made plain in §17.2). |

### 17.2 What the design is now, in short

- **GitHub app, F-Droid app from another repo**: a new release waits the app's hours (default 48) from when the source
  published it; the inbox tells of it when the server finds it ("Signal 7.3.0 is available · reaches the phones Thu
  14:02"); "Update Now" on the app's page sends it at once. When it ripens it is downloaded and read: nothing new
  sensitive -> served (no second inbox row); something new -> held, one inbox row "Signal 7.3.0 asks for Contacts ·
  approve it on the app's page", the Apps tab badge "Waits for you", and the phones keep the old release until the
  parent taps Approve.
- **f-droid.org**: the same, with a default wait of 0 hours (the parent may set one).
- **Own uploads (a manual APK), Vibb One, Vibb music**: at once, as today - no wait and no hold. An upload is the
  parent's own act with the file in hand, and it widens the accepted set (§5).
- **No global setting.** The per-source default the Add App form fills in is the one place a default lives
  (`catalog_holds::default_wait_hours`); a server-wide "default wait" would be a second number the parent must keep in
  mind and that changes nothing for the apps already added. If the user wants one later, it is one more row.

### 17.3 The findings and their fixes

**High**

- **H1 - The held release would never reach the inbox (dedup collision).** With D1 the waiting release raises
  today's `app_update_available` under today's key `<app id>:<tag>@<asset id>` (`parent_events::raise` is `ON CONFLICT
  DO NOTHING` on `idx_parent_events_catalog`). The same release is judged only when it ripens; if it is held then, an
  event with the same key is dropped - the one case the parent must act on would be silent. **Fix**: the hold is told
  by the same kind under its own key, `<app id>:<tag>@<asset id>:held`. So per release: one row when found (or when
  served, for an app with no wait), and one more only if it is held; the release served after its wait raises nothing
  (same key as the found row); Approve raises nothing (the parent just acted); a newer held release replacing an older
  one gets its own `:held` row. A sweep or Check Now that finds the same release again raises nothing (both keys).
- **H2 - The ripe pick would step back, and "waiting" would show a release already on the phones.** Today
  `sync_release` stores any `(tag, asset id)` that differs from the served one (GitHub has no older-version guard;
  `release_ahead` decides only the event), and F-Droid's hourly sync refuses a lower versionCode as `OlderVersion`.
  After "Update Now" (or an Approve) the served release is the newest, unripe one; the next hourly sync's newest *ripe*
  release is older - GitHub would store it over the newer one (and the phones can't even install a downgrade), F-Droid
  would show "Update failed" every hour. §6's "the `OlderVersion` guard and `release_ahead` are unchanged" is wrong.
  **Fix** (`catalog_holds::pick`, pure): the candidates are the matching releases newest first; one that isn't
  `ahead` of the served release (`release_ahead`, generalised to `ahead(source, prefix, tag, asset, of_tag, of_asset)`
  so it can compare with the held and the declined one too) is **nothing to do** while the served release is still
  in the list - only when the served release is gone from the list (pulled, prereleases turned off) does today's
  behaviour stay (GitHub takes the newest ripe; F-Droid's `OlderVersion` guard, Check Now past it). The **waiting**
  release is the newest one that is ahead of the served, the held and the declined release and not ripe; none -> the
  waiting columns are cleared. Tests: Update Now then three sweeps keep the served release and show no waiting row and
  no failure, on GitHub and IzzyOnDroid.

**Medium**

- **M1 - The wait per app (the user's D5) changes the clock's storage.** §6 stored `waiting_release_ready_at` =
  anchor + 48 h; with a per-app number the parent can change, the stored ready time would be stale. **Fix**: store the
  anchor, compute the ripeness: `waiting_release_since` (the source's time per §6, or the first sight when it has none;
  kept while the same `(tag, asset id)` is seen again) and `ready = since + the app's hours`, read at every sync and
  every page render. Lowering the wait ripens a waiting release at the next hourly sync (or Check Now); raising it
  moves its time later. An older release in the list without a source time is never ripe (only the newest one has a
  stored first sight); GitHub gives every published release a `published_at`, the index every version an `added`, so
  this is the damaged-answer case and fails towards waiting.
- **M2 - Where the wait lives and how it is chosen** (the user's second message). `tracked_apps.release_wait_hours
  INTEGER` with `CHECK (release_wait_hours IS NULL OR release_wait_hours BETWEEN 0 AND 336)`; **NULL = the source's
  default** (`catalog_holds::default_wait_hours(app)`: 0 for an F-Droid row whose repo is f-droid.org - keyed on
  `fdroid::Repo::api.is_some()`, the same `API_HOSTS` the sync uses, so the tests' in-process repo counts - 48 for
  every other GitHub or F-Droid row; `None` for manual, Vibb One and Vibb music). NULL rather than a copied number so
  that a repo changed from f-droid.org to IzzyOnDroid under Details picks up the 48 instead of keeping a 0 meant for
  F-Droid's own builds; a number the parent typed stays. Existing rows: NULL at the migration, so their source's
  default.
  - **Add App** (`tracked_app_add.html`): one field **Wait before an update reaches the phones** / **Vent før en
    oppdatering kommer til telefonene**, `type="number" inputmode="numeric" min="0" max="336" step="1"`, a suffix
    "hours" / "timer", **left empty** with the placeholder "48" and the footer "Empty: 48 hours for GitHub and other
    repos, none for f-droid.org (F-Droid builds the app itself). 0 = no wait." / "Tom: 48 timer for GitHub og andre
    kilder, ingen for f-droid.org (F-Droid bygger appen selv). 0 = ingen venting." - the form has no JavaScript that
    knows the source before the post, so empty means the source's default. Not shown for a manual upload's source.
  - **The app's page, Updates (`#updates`)**: a row **Wait before updates** / **Vent før oppdateringer** with the
    same number field, empty = default, the sub-line "48 hours (default for GitHub)" / "48 timer (standard for GitHub)"
    or "None (default for f-droid.org)" / "Ingen (standard for f-droid.org)" when empty; its own form `POST
    /apps/tracked/{id}/release-wait` with a Save button (`button-small`; a number field doesn't autosave - a half-typed
    "4" must not save as four hours), back to `/apps/tracked/{id}#updates`. Not on a manual, Vibb One or Vibb music
    page.
  - **Refused** (both forms): not a whole number, below 0 or above 336 - "Enter a whole number of hours from 0 to 336
    (14 days)" / "Skriv et helt antall timer fra 0 til 336 (14 døgn)" (`apps.wait.refused`, `{max}` filled in): 400,
    the page with the typed value, the error by the field, the field focused, at `#updates` (or the Add form's field) -
    never the top.
  - **Security Log**: `catalog_release_wait_changed` "<app>: updates wait 24 hours (was 48)" / "...vent 24 timer (var
    48)" - shortening the wait is a security choice the other parent should be able to see.
- **M3 - Approve must redo what a store does, against the row as it is now.** Between the hold and the tap the row can
  change (a phone ticked with a foreign app of that package - `check_new_package`; the pins first stored by an
  upload). **Fix**: Approve, under the guard, reads the held file's identity again (`apk_identity`), runs
  `check_pins` and `check_new_package`, checks the file's SHA-256 against the held row's, and only then moves it into
  `latest_release_*` (with `latest_release_version_code` and `latest_release_permissions`), then `store_pins`,
  `set_refused(None)`, `replace_cached_file`, the nudge - in `sync_release`'s order. A failure: the held row and file
  go, the flash names the sync's failure, nothing served. The UPDATE matching no row (the app deleted meanwhile)
  removes the held file, as `replace_cached_file(false, …)` does.
- **M4 - "Forget Package and Key…" and the accepted set.** §5 said the next "first release" seeds the set again, but
  Forget keeps the served release (`forget_package_name` clears only the pins), so there is no first release after it.
  **Fix, worded plainly**: Forget clears the set to NULL; the next release with any sensitive permission is held
  ("asks for Contacts, Camera and 2 more") - right for its use, a developer's changed key: a new key is a new trust, the
  parent sees the whole list once. The ⓘ of Forget's confirm says so: "The next update that asks for anything sensitive
  waits for your approval." / "Neste oppdatering som ber om noe sensitivt venter på godkjenningen din."
- **M5 - The inbox words for the wait and the hold, on the existing kind.** `AppUpdateParams` gains two optional
  fields, `#[serde(default, skip_serializing_if = "Option::is_none")]` so the rows raised since 2026-10-25 read as
  before: `ready` (RFC 3339; the waiting release's ready time when found) and `held` (`{reason: "permissions" |
  "unreadable" | "always_ask", groups: [key]}`). `app_update_told` words them:

  | | en | nb |
  |---|---|---|
  | inbox title (found, as today) | Signal 7.3.0 is available | Signal 7.3.0 er tilgjengelig |
  | inbox line, waiting | 14:02 · 7.2.0 → 7.3.0 · reaches the phones Thu 14:02 | 14:02 · 7.2.0 → 7.3.0 · kommer til telefonene tor. 14:02 |
  | inbox title, held | Signal 7.3.0 waits for your approval | Signal 7.3.0 venter på godkjenningen din |
  | inbox line, held | 14:02 · asks for Contacts and Location · approve it on the app's page | 14:02 · ber om tilgang til kontakter og posisjon · godkjenn på appens side |
  | line, held unreadable | 14:02 · couldn't read what it asks for · approve it on the app's page | 14:02 · fikk ikke lest hva den ber om · godkjenn på appens side |
  | line, held always ask | 14:02 · 7.2.0 → 7.3.0 · approve it on the app's page | 14:02 · 7.2.0 → 7.3.0 · godkjenn på appens side |
  | push title, held (only with "App updates" on) | Signal 7.3.0 asks for Contacts (and 2 more) | Signal 7.3.0 ber om tilgang til kontakter (og 2 til) |
  | push body, held | Approve it or keep 7.2.0 | Godkjenn eller behold 7.2.0 |

  The waiting line's time is the one known when it was found (a later "Update Now" or a changed wait leaves the row's
  words as they were; the row leads to the app's page, which is the truth). Target `/apps/tracked/{id}#update` for
  both. **A held release without a push** (D1): it reaches the parent through the inbox row (counted on the bell like
  every unread row), the app's page and the Apps tab badge "Waits for you", ranked first among the badges after
  "Off". The phones stay safe meanwhile (they keep the release they have); the cost of a parent who never looks is an
  app that stops getting updates, which the badge and the inbox make visible. Raised for every catalog app as today's
  `app_update_available` is (§7's "no inbox row for an app on no phone" goes: one rule for both rows; the launcher and
  the music app are never held anyway).
- **M6 - The data model, revised** (migration 0141; replaces §10's block):

  ```sql
  -- Design 90: the served release's declared permissions, the set the parent accepted, the per-app ask switch and
  -- wait, the release waiting out its wait, and the one the parent declined.
  ALTER TABLE tracked_apps ADD COLUMN latest_release_permissions TEXT;   -- JSON array or NULL (unread)
  ALTER TABLE tracked_apps ADD COLUMN accepted_permissions TEXT;         -- JSON array of sensitive names; NULL = unknown
  ALTER TABLE tracked_apps ADD COLUMN always_ask INTEGER NOT NULL DEFAULT 0;
  ALTER TABLE tracked_apps ADD COLUMN release_wait_hours INTEGER
      CHECK (release_wait_hours IS NULL OR release_wait_hours BETWEEN 0 AND 336); -- NULL = the source's default
  ALTER TABLE tracked_apps ADD COLUMN waiting_release_tag TEXT;
  ALTER TABLE tracked_apps ADD COLUMN waiting_release_asset_id INTEGER;
  ALTER TABLE tracked_apps ADD COLUMN waiting_release_published_at TEXT; -- the source's time, for the page; NULL = none
  ALTER TABLE tracked_apps ADD COLUMN waiting_release_since TEXT;        -- the wait's anchor, UTC RFC 3339
  ALTER TABLE tracked_apps ADD COLUMN declined_release_tag TEXT;
  ALTER TABLE tracked_apps ADD COLUMN declined_release_asset_id INTEGER;

  CREATE TABLE tracked_app_held_releases ( ... as §10 ... );
  ```

  No `notification_prefs.push_app_held`. SQLite takes a CHECK on an added column with a NULL default.

**Low**

- **L1 - The wait's default by source and the tests**: "f-droid.org" is decided by `Repo::api.is_some()`, not by a
  host string in `catalog_holds` (M2), so `tracked_apps::tests`' in-process repo (an API host in tests) is the
  f-droid.org case and an index-only repo the IzzyOnDroid case.
- **L2 - Element names** (§4): binary XML keeps an element's namespace apart from its name; Android's parser matches
  the name alone. So: an element counts by its name whatever its namespace - not "with or without a prefix".
- **L3 - Typed and raw values** (§4): aapt2 writes both for a string; "agree" applies when both are present, else the
  one present is read (a typed non-string - a reference - is still `None`).
- **L4 - The bind marks' form** (§4): stored as `bind:android.permission.BIND_ACCESSIBILITY_SERVICE` (a component's
  `android:permission`), so a meaningless `<uses-permission>` of a BIND name (signature-level, never granted) can't
  be mistaken for the component mark; `SENSITIVE` lists the `bind:` forms for the five groups.
- **L5 - Missing from `SENSITIVE`**: `bind:…BIND_AUTOFILL_SERVICE` and `bind:…BIND_CREDENTIAL_PROVIDER_SERVICE` (a
  password filler sees what is typed into forms: group `keyboard`, worded "A keyboard or password filler" / "Tastatur
  eller passordutfyller"), and Android 16's `android.permission.health.*` (by prefix, group `body`: Android 16 moved
  the body sensors there).
- **L6 - §3's "Not sensitive" paragraph**: it gives design 68's special accesses as gated in the pause, but
  `PACKAGE_USAGE_STATS` (design 68's `usage_access`) is in the table. Reworded: "the other four of design 68's special
  accesses (`manage_media`, `exact_alarm`, `write_settings`, `full_screen`) are approved per app in the pause"; usage
  stays sensitive (what the kid does all day).
- **L7 - The orphan sweep at startup** (§10): "any `.apk` in an app's directory named by neither
  `latest_release_file_path` nor its held row" is removed - the served file is never touched, and a row whose held file
  is missing is deleted (logged; the next sync downloads it again).
- **L8 - An upload while a release is held**: the upload is served and widens the set; the held release stays held
  (no re-judge) until answered or superseded. Approve's re-check (M3) still runs.
- **L9 - The Apps tab badge order**: "Off" first (a disabled app reaches no phone, its hold isn't urgent), then
  "Waits for you", then "Update failed", then "No release".
- **L10 - Decline's comparison**: the sync skips a release that isn't `ahead` of the declined `(tag, asset id)` (H2's
  generalised `ahead`), so the declined release isn't downloaded again hourly; a release ahead of it is judged afresh.

### 17.4 What changes in the sections above

- §1, the second bullet: the parent approves from the inbox row or the app's page - no push of its own (D1).
- §2's table: the wait column is the **default** of the app's own wait (M2): GitHub 48, f-droid.org 0, other F-Droid
  repos 48; manual, Vibb One and Vibb music - none, not settable.
- §6: "48 h, fixed" becomes the app's hours (M1, M2); the pick follows H2.
- §7: "raises `app_update_held`" becomes the `:held` row of `app_update_available` (H1, M5); Approve follows M3; "no
  inbox row for an app on no phone" goes (M5).
- §8 is replaced by H1 and M5; §9.1's waiting sub-line reads "Published Tue 14:02 · updates of this app wait 48 hours"
  / "Publisert tir. 14:02 · oppdateringer av denne appen venter 48 timer"; §9.2 follows L9; §9.3 goes; §10 by M6.
- §13 (e): we take upstream's per-app wait after all, in hours, with a per-source default.
- §14, Tests, add: the wait form's validation (empty = default, 0, 336, -1, 337, "4.5", "x") on both forms, each
  refusal landing at its field (`assert_lands`); a repo changed from f-droid.org to another keeps NULL and gets 48; a
  changed wait re-times the waiting release; the Security Log line; H1's rows (found -> served raises nothing more;
  found -> held raises the `:held` row once over three sweeps; Approve raises nothing); H2's no-step-back cases; M3's
  Approve refused when the pins changed; `parent_events`: a 2026-10-25 row without `ready`/`held` reads as before,
  each new line in both languages. Gone: `push_app_held` and `app_update_held`.
- §15's step 4: the `:held` row and the words instead of a new kind and switch; the wait's field on Add App and the
  app's page.

### 17.5 Left to the user

Nothing blocks the build. Two choices made here the user may want to see: the wait's upper bound of 336 hours (14
days; a longer wait mostly delays security fixes) and that a held release with "App updates" off reaches the parent
only through the inbox and the Apps tab badge (D1 as decided - the phones are safe meanwhile, the app just stops
updating until the parent looks).
