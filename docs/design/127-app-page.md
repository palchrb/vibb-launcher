# Design 127: An app's own page, and what each app may use

Status: design, revision 1, 2026-11-22 (branch `design-127`, from main fe3e96c84). Server and launcher. The user
approved the mockup (2026-11-22, "Appsiden ser bra ut, bare å implementere";
https://claude.ai/artifact/LtUfDhmfvCKzY7No7cC1i3) with its four choices as proposed: name and icon save at once,
approving an update acts on the catalog app and the page says so, the split list row, 44 px hit areas for the symbols.
Also approved earlier (the privacy round, A1): a per-app network switch on this page, on by default, and switches for
location, microphone and contacts like today's camera switch - never deny-by-default.

Marks: **[code]** read in the code at fe3e96c84; **[sdk]** read in the Android SDK's `api-versions.xml` for API 37
on this machine; **[assumed]** not verified, to be checked by step 0's probe or the emulator gate (§10).

## 0. Short answer

- Every allowed app gets a page, Device › Apps › <app> (`/devices/{id}/apps/{package}`). The list keeps one row per
  app: the name opens the page, and the allow switch stays on the row behind a hairline.
- The page has four groups: the head (logo, name, version, Tillatt, an update that waits for the parent), **Vises som**
  (the Home tile beside the name, Logo · Automatisk · Symbol, all saving at once), **Tilgang** (Nett, Kamera,
  Posisjon, Mikrofon, Kontakter - only what the app asks for - and one orange line for the approvals it lacks), and
  **Låseskjerm** (Mediespiller).
- Kamera, Posisjon, Mikrofon and Kontakter use today's camera mechanism, widened: `setPermissionGrantState` DENIED per
  permission while the parent has it off, DEFAULT otherwise. We never GRANT.
- **Nett uses `DevicePolicyManager.setPreferentialNetworkServiceConfigs`** with a config that includes the apps' uids,
  allows no fallback and blocks every non-matching network (§4.2). No network on a consumer phone carries the enterprise
  capability it asks for, so those uids get no network at all. It is the only device-owner tool that cuts one app's
  network on Wi-Fi and mobile alike. It does not touch our VPN, Bare Vibb or the pause. It is built only if step 0's
  probe shows it holds on Android 14, 16 and 17. If it fails, there is no Nett switch: no weaker DNS-only cut.
- All five lift in "pause all restrictions" and the offline override, like the camera. None of them is a hardening
  switch.

## 1. The problem

Device › Apps today puts everything inside the list **[code]** (`templates/partials/device_app_row.html`):
- An allowed app's row opens two `<details>` under it ("Navn og ikon", "Tillatelser"). One open app pushes every other
  app down.
- The icon choice is a wall: two text buttons, 16 glyphs and seven named colours. The colours show even when the
  logo is chosen.
- Name and icon need a Save button, while the switches save at once.
- The camera switch and the lock-screen media switch are loose rows. Four more switches would double the row.
- "Kamera av" is said twice (the row's sub-line and the switch).
- An update that waits for the parent shows only on the catalog app's page.

## 2. The page

### 2.1 Where it is, and who gets one

- **Route**: `GET /devices/{id}/apps/{package}`; the handler goes in a new module, `handlers/app_page.rs`.
  - axum 0.8 (matchit) ranks the static POST routes `/apps/toggle`, `/apps/display`, `/apps/permissions` and
    `/apps/lock-media` above the capture **[code]** (`Cargo.toml`: axum 0.8.9). A package always has a dot
    (`app_display::valid_package_name`), so it can never equal one of those words.
- **Who gets a page**: every row of the phone's list with a valid package name that is allowed (`row.checked`), and
  also an app that has just been turned off on its own page. That page then shows only its head, with Tillatt off, so
  the parent can turn it on again in place.
  - **No page**:
    - Vibb One: its row stays "Alltid på", and its versions are on its catalog page.
    - Kalender: it stays a link to Device › Calendar.
    - The managed dialer: it stays a link to Calls.
    - A catalog app with no package yet.
    - A not-allowed row: it stays a plain switch row.
- **An unknown package** (not in this phone's rows, e.g. uninstalled since): `303` to the list. Never a 404 page: a
  page in the history must not become an error.
- **The tab, the back link and `from`**:
  - The page takes the list's `?from=` (`AppsFrom`): the same tab, and every form carries it.
  - Back goes to Device › Apps at the app's row (`#app-<package>`), with `from` kept.
  - One exception: `from=app:<id>` where `<id>` is this very app's catalog row. Back then goes straight to that
    catalog page (`/apps/tracked/{id}#phone-<device>`).
  - The catalog page's phone rows link to this page (`tracked_apps::phone_apps_href`), so a parent who came from the
    catalog taps back once, not twice.
- **Redirects**: the old `GET /devices/{id}/apps?perms=<package>` goes `303` to the page (`#access`). A fragment
  (`#display-…`, `#perms-…`) never reaches the server, so the list's own `#app-<package>` anchors stay as they are.

### 2.2 The list

**The split row** is a new pattern, approved. An allowed app's row is
`<div class="row-split"><a class="row" href="…/apps/{package}?from=…">…</a><form class="split-sw">…switch…</form></div>`:
- The tile, the name and the sub-line are the link, with its chevron.
- The switch is its own auto-saving form (`/apps/toggle`, `back=apps`) behind a 1 px hairline. It has a visually
  hidden label "{app} tillatt".
- The switch's hit area is the whole cell right of the hairline, at least 44 px wide.

**The row's sub-line** keeps today's parts: the own name, installing / failed, no release, the dialer note, Chrome
for logins. It loses the permissions line and gains one orange part for what waits for the parent:
- an update held for approval;
- each special access not approved;
- each switch the phone refused (§3.2).

One such item shows its own words ("7.25.0 venter på deg"). Two or more show the count ("2 ting venter på deg").

A choice the parent made ("Nett av") is not on the list (Words in the UI: a choice is never a warning).

### 2.3 The head (`#app-head`)

- **The tile**: the real logo over the letter, else the chosen glyph on its colour, else the letter. This is the
  row's rule (design 87 §8.1). It is also the band's small picture, where the phone page shows the avatar.
- **The name and its sub-line**:
  - `installed_apps[].version_name` from the last report ("Versjon 7.24.1").
  - While the app installs, today's status label ("Installerer 42 %"); the page reloads itself as the list does
    (`data-waits`).
  - The dialer note and Chrome's login line, as in the list.
- **"Tillatt"**: the allow switch, `/apps/toggle` with `back=app`. Saved, it lands on the page (`#app-head`). The
  refusals' words show here as the list shows them under the row.
- **An update held for the parent** (design 90; a catalog app with a row in `tracked_app_held_releases`, read through
  `tracked_apps::held_release`):
  - An orange summary row "{version} venter på deg", with the sub-line "Ber om tilgang til {groups}".
  - Under it, **Godkjenn** and **Behold {served version}**. They post to the existing
    `/apps/tracked/{id}/held/approve|decline` with the release's `tag` and `asset_id`, plus a new
    `back=device:<device id>`.
  - `held_answer` then returns to `/devices/{d}/apps/{package}?held=<word>&from=…#app-head`, and the page shows
    `held_flash`'s words.
  - `back` is honoured only when the device exists and the catalog row's package is a valid one. Anything else is
    today's catalog redirect.
  - Its (i) holds:
    - the "{group} - nytt" lines (design 90's `new_lines`);
    - "Telefonene beholder {version} til du svarer.";
    - **"Gjelder appen på alle telefonene."**, with a link "Appens side i Apper" to the catalog page. This is the
      approved catalog-wide note.
  - A release that only waits its hours, or a declined one, says nothing here: nothing is wrong, and the catalog page
    has it.

### 2.4 Vises som (`#display`)

One auto-saving form; every control saves on change (`data-autosave`, `static/autosave.js`), and the Save button
goes. It is built on design 103's saves in place: a save keeps the place, never the top of the page (standing rule).

- **The top**: the Home tile preview (`home-peek`: the tile at Home's size with its label under it) beside the Name
  field.
  - `<input type="text" name="label" maxlength="20" data-autosave>`. A text field saves on change, i.e. on blur or
    Enter (precedent: `device_calendar_link.html`, `device_calls.html`).
  - `static/app-display.js` updates the preview while typing.
- **The icon** is a segmented control (design 77's `.segmented`/`.segment`, **[code]** `style.css` §3640):
  **Logo · Automatisk · Symbol**, radios `icon_mode` = `logo` | `auto` | `symbol`.
  - Logo is `icon=own`.
  - Automatisk is `icon` empty (Vibb style).
  - Symbol is the chosen glyph.
  - A part of Vibb One (Meldinger) has no Logo segment (design 82 D9).
- **The symbols** show only under Symbol:
  - 16 glyphs in 2-3 lines, each a radio with a 44 × 44 px hit area.
  - One glyph is always checked: the stored glyph, else the catalog default's, else the first (`chat`). So choosing
    Symbol saves at once with a real glyph, the preview shows it, and the parent taps another one. No half state, no
    special case in the script.
- **The colours** show only under Symbol: one line of seven swatches without names, 44 px hit areas, with
  `title`/visually hidden names. A colour applies only with a glyph today **[code]** (`app_display.rs`: "always
  [AUTO] for null and OWN"), so hiding it elsewhere hides nothing that works.
- **Without JS**: all three parts show, and a `<noscript>` Save button submits.
- **The server**:
  - `icon_mode` wins over `icon`: `logo` → `own`, `auto` → empty, `symbol` → the posted glyph (else the rule above).
    A missing `icon_mode` reads `icon` as today, which the catalog page and older pages post.
  - `app_display::validate` runs unchanged.
- **"På Hjem"**: a plain row in this group (`on_home_shown` as today).
- **"Tilbakestill"**: a row button, shown only when `can_reset`, with design 113's catalog-default hint.
- **A refused save** (a name over 20 characters past `maxlength`, the 200-row cap) renders this page as a 400. The
  error sits at the field, with `aria-invalid`.
- **The catalog app's page** (`tracked_app_detail.html`) moves to the same partial (`partials/app_display_group.html`)
  and the same script, so there is one look and one script. It has no "På Hjem", so it skips the `on_home` part.

### 2.5 Tilgang (`#access`)

The group shows only when the last report has the app's `access` (a third-party app, §5).
- **The rows**, in this order, only for what the app asks for (§3.1): Nett, Kamera, Posisjon, Mikrofon, Kontakter.
  Each is an icon tile, the name and an auto-saving switch (on = allowed). Each is its own form: `POST
  /devices/{id}/apps/permissions` with `package_name`, `key` and the checkbox `allow=on`; an unchecked switch posts
  without it.
- **A switch's sub-line**, only when the state isn't simply as set:
  - "Venter på telefonen": off here, but the report still says `requested` and no pause.
  - "På i pausen": off here, and the phone's last report says restrictions are paused.
  - Orange "Telefonen kan ikke slå av dette": the report says `refused`.
- **The special accesses not approved** (design 68's five) are **one** orange row at the group's end, with the
  indigo tile:
  - One missing: "{access} ikke godkjent". Two or more: "{n} tilganger ikke godkjent".
  - The fix is behind its (i): design 68's way on the phone, one line per access (Alarmer og påminnelser keeps "and
    open the app once").
  - Approved ones and `unknown` ones show nothing. `unknown` is an op the launcher can't read on that phone
    (design 68's probe), so an orange line there would never go away and would mean nothing the parent can fix.
- **One (i)** for the group, its paragraphs only for the rows shown (§6).

### 2.6 Låseskjerm (`#lock-screen`)

Today's "Mediespiller på låseskjermen" switch (2026-11-04), renamed "Mediespiller" under its own header. It is offered
as today (`app_permissions::lock_media_offered`) and keeps its route, which now lands on `#lock-screen`. Its (i) is
today's `apps.perms.lock_media_help`.

## 3. The contract

### 3.1 The five keys

| key | nb / en | Android permissions (requested = any of them) | mechanism |
|---|---|---|---|
| `network` | Nett / Network | `INTERNET` | preferential network config (§4.2) |
| `camera` | Kamera / Camera | `CAMERA` | grant state |
| `location` | Posisjon / Location | `ACCESS_FINE_LOCATION`, `ACCESS_COARSE_LOCATION`, `ACCESS_BACKGROUND_LOCATION` | grant state, each requested one |
| `microphone` | Mikrofon / Microphone | `RECORD_AUDIO` | grant state |
| `contacts` | Kontakter / Contacts | `READ_CONTACTS`, `WRITE_CONTACTS` | grant state, each requested one |

- `GET_ACCOUNTS` is left out. It is in the contacts group, but since Android 8 it shows only the app's own accounts.
- Body sensors, calendar, call log, SMS and nearby devices are not in this round.

### 3.2 The wire

- **The policy's `app_permissions`** (changed shape):
  - The list is `[{package_name, network?, camera?, location?, microphone?, contacts?}]`, sorted by package. Each key
    is `"deny"` or absent, and an entry exists only with at least one key.
  - `camera` stops being always present.
  - The launcher reads field by field (`appPermissionsOf`, now `Map<String, Set<AccessKey>>?`; missing or not a list
    is still `null` = touch nothing).
  - No backwards compatibility on phones (the user, 2026-11-18), so the old `{package_name, camera: "deny"}` simply
    reads the same.
- **The report's `installed_apps[].access`** gains `network`, `location`, `microphone` and `contacts`, beside
  `camera` and the five special accesses:
  - Each is present only when the app requests it. Its value is `requested` | `admin_denied` | `refused`.
  - `admin_denied`: every requested permission of the key is at grant state DENIED. For `network`, the app's uid is
    in our applied config.
  - `refused`: this process's last pass tried to apply the parent's off and Android said no (`setPermissionGrantState`
    false or a throw; the network write threw). It is kept in memory per package and key, and cleared by a pass that
    succeeds.
  - Since §4.2's step 3, `network` is reported only once the launcher applies it. The PWA shows the Nett switch only
    when the report has the key, so the server needs no flag.
- **The server keeps known keys and values only** (`AppAccess::of`, lenient as today).

### 3.3 The shared contract file: `server/testdata/app_access.json`

Byte-identical in `launcher/app/src/test/resources/app_access.json`:
- `keys`: §3.1's table as `[{key, permissions}]`, in display order.
- `policy`: `none` (`[]`) and `seeded` - the exact `app_permissions` the server serves after the page's saves.
  The saves are: one app with Kamera and Mikrofon off, one with Posisjon and Kontakter off, and one switched off and
  on again, which leaves no entry.
- `report`: the exact `installed_apps[]` entries with every key and each value (`requested`, `admin_denied`,
  `refused`), beside the special accesses.
- `clean`: the server's reading, row by row (an unknown key or value dropped, a non-object dropped).
- `never`: the packages no switch is offered or applied for (`me.vibb.launcher`, `.debug`, `me.vibb.music`,
  `.debug`).

The server's `app_permissions::tests::the_contract` saves through the real route and produces `policy.seeded`, posts
`report` and checks what is stored and what the page shows, runs `clean`, checks `keys` against its table, and
compares the copies. The launcher's `AppAccessContractTest` checks `AccessKey`'s permissions against `keys`, decodes
both policies, encodes `report` exactly from the pure report function and runs `never`, and compares the copies.

`network` joins the file in step 3 (§9), with the launcher code that applies it, in one commit.

The pins:
- `policy_json_keys_snapshot` and `PolicyResponseCompatTest` pin the entry's keys.
- `intake_limits::the_field_tables_keep_every_contract_key` keeps the report's keys.
- The root `CLAUDE.md` gets the file's paragraph.
- `launcher.yml` and `server-ci.yml` get its two paths in their change filters.

The launcher's own rules - the grant plan, the network plan and the report's readings - stay the launcher's, in
`launcher/app/src/test/resources/app_access_rules.json`. The UP drop row goes in `up_rules.json`.

### 3.4 The server's data

- **Migration `0164_app_denials.sql`**:
  - `device_app_denials (device_id REFERENCES devices ON DELETE CASCADE, package_name, permission TEXT CHECK
    (permission IN ('network','camera','location','microphone','contacts')), updated_at, PRIMARY KEY (device_id,
    package_name, permission))`.
  - It copies each `device_app_permissions` row as `camera`, then drops the old table.
  - A key row table needs no NULL columns and no table rebuild per new key. The old table's `camera NOT NULL CHECK
    (camera = 'deny')` **[code]** can't be widened in place in SQLite.
- **The cap**:
  - At most 200 packages a phone (`MAX_ROWS_PER_DEVICE`, counted as distinct packages other than this one).
  - A deny save first prunes the rows of packages that the last report doesn't list (an app removed since). This is
    `set_lock_media`'s pattern **[code]**.
  - Allowing always deletes the one row.
- **The save route** `POST /devices/{id}/apps/permissions` (`save_app_permissions`, widened):
  - `key` must be one of the five, else 400.
  - `allow=on` allows; its absence denies.
  - The target is checked as today (`perms_target`: the grammar, the phone, not Play's core, not Vibb). Vibb music
    is refused too.
  - A key the app doesn't request in the last report is refused (`notice=perms`): a stale form.
  - Saved, it lands on the page (`#access`) and nudges the phone. Refused, the page shows the refusal at the group.

## 4. The launcher

### 4.1 Kamera, Posisjon, Mikrofon, Kontakter

Today's camera mechanism **[code]** (`EnforcementPlan.kt` `cameraGrants`, `AppEnforcer.kt` `cameraPass`,
`enforceCameraOnPackage`), widened:

- **`AccessKey`** is `CAMERA`, `LOCATION`, `MICROPHONE`, `CONTACTS`, each with its permissions (§3.1).
- **`accessGrants(requested, own, denied, windowOpen, callLive, held, current)`** replaces `cameraGrants` and works
  per (package, permission):
  - DENIED when the package's key is in `denied` and no window is open; DEFAULT otherwise.
  - With `denied == null`, nothing is written, except that in a window every DENIED target goes back to DEFAULT.
  - Writes happen only on a change (`grantWrite`). We never GRANT.
- **Targets**: third-party apps but ours (and never the `never` list). A hidden app is skipped: the pass that unhides
  it comes first **[code]**.
- **The call deferral now covers all four.** Revoking any granted runtime permission kills the app. So while a call is
  live (`WindowRelock.liveCall`), a denial for an app that holds that permission now (`checkPermission`) waits, and
  `accessDeferred` replaces `cameraDeferred`.
  - Element X in a call holds the microphone and maybe the camera. Kontakter or Posisjon off mid-call would also kill
    it.
  - `BootHome.callMayHaveEnded` applies again as today.
- **`enforceAccessOnPackage`** replaces `enforceCameraOnPackage`. It runs at every install and package change, under
  `apply`'s lock, so an update that adds a permission is denied before its first start.
- **`refused`**:
  - Each write's `false` or throw is recorded per (package, key) in memory (`AppEnforcer.accessRefused`), for the
    report.
  - A permission Android won't let a device owner set (system-fixed) shows in the PWA instead of "Venter på
    telefonen" forever.
- **The global `setCameraDisabled`** stays the PIN lock's alone.

### 4.2 Nett: the choice

The honest comparison, for a switch that must really cut one app's network on Wi-Fi and mobile, on Android 14-17,
without breaking our VPN, Bare Vibb, the mobile-data rules or the pause:

| Option | Cuts Wi-Fi and mobile? | Bypassable by the app? | Fits our VPN? | Verdict |
|---|---|---|---|---|
| **A. `setPreferentialNetworkServiceConfigs`**: included uids, `setFallbackToDefaultConnectionAllowed(false)`, `setShouldBlockNonMatchingNetworks(true)` | yes [assumed], since no network matches | binding to another network is blocked by "block non-matching" [assumed] | the VPN is separate: [assumed] traffic outside the filter's one route falls through to the uid's own default, which is none | **chosen, behind step 0** |
| B. Our VPN refuses that app's DNS (`getConnectionOwnerUid` per query) | DNS only | yes: a literal IP, its own DoH, QUIC | yes | not a block |
| C. Lockdown, with the app outside the VPN | yes | no | **no**: the filter shape has no default route, so lockdown cut every app **[code]** (`dns-and-vpn.md`, the live bug) | out |
| D. A full-tunnel VPN that forwards everyone else in user space (tun2socks) | yes | no | replaces the filter, sink and pass shapes; heavy on battery | out |
| E. `setMeteredDataDisabledPackages` (API 28) **[sdk]** | **mobile only** (metered) | no | yes | half a switch: Wi-Fi stays open |
| F. `NetworkPolicyManager.setUidPolicy` | yes | no | yes | not a public API (`MANAGE_NETWORK_POLICY`) |
| G. Suspend or hide the app | - | - | - | the app should still run offline |

**Why A works, and why design 95 §2.5 dismissed it.** Design 95 wanted A's purpose: routing apps to an enterprise
slice, which Norwegian consumer SIMs don't offer. This design uses A's other half:
- With fallback off and non-matching networks blocked, an included uid may use only a network with
  `NET_CAPABILITY_ENTERPRISE` and our enterprise id.
- With none present, the uid has no default network, and an explicit bind to Wi-Fi or mobile is refused.
- We use `PREFERENTIAL_NETWORK_ID_5` **[sdk]**, the id least likely to meet a real slice.

The APIs:
- `setPreferentialNetworkServiceConfigs` (API 33) and `setShouldBlockNonMatchingNetworks` (API 34) are in the API 37
  SDK, not deprecated **[sdk]**. With minSdk 34, no version gate is needed (lint's NewApi checks).
- A device owner may call it on a fully managed phone when the config names its uids **[assumed: DPMS's check and
  ConnectivityService's "profile must be managed unless uids are given", both to probe]**.

**Our VPN and Bare Vibb:**
- **[assumed, probed]** The VPN's uid ranges have the highest preference order, 0. In the filter shape it routes only
  `192.0.2.2`, so a cut app's DNS still reaches our filter, while every other packet falls through to the uid's own
  default network rule. That rule is the "no service" network, so the packet is unreachable.
- The sink shape (lockdown) cuts the app anyway.
- The pass shape has no routes, so the app's traffic meets only the preference.
- The launcher's own uid is never included, and is outside the VPN as today, so the sync, SSE, tsnet and our
  downloads are untouched.
- Bare Vibb and the roaming lock are unchanged: two independent cuts, and an app is offline if either applies.

**The plan** (`AppNetwork.kt`, pure `networkUids`):
- From the policy's `network` denials, the installed third-party packages that request `INTERNET` and their uids
  (`getPackageUid`, `MATCH_UNINSTALLED_PACKAGES`), the window and `never`: the set of uids to include.
- A uid shared by several packages (`sharedUserId`) is included only when every package on it is denied. Otherwise
  each such package reports `refused`.
- With `denied == null` (a Fallback): nothing changes, except that a window clears it.
- **Hidden apps are included too**: the config is by uid and needs no unhide.

**The write**:
- Only on a change against `getPreferentialNetworkServiceConfigs()`: one enabled config with the uids, or
  `PreferentialNetworkServiceConfig.DEFAULT` (disabled) when none.
- It runs at every `apply()` after the grant pass, and in `enforceAccessOnPackage`, so a reinstall's new uid is cut
  before the app's first start, as for the camera.
- The config is Android's to keep (DPMS persists it across reboots and launcher crashes), so the cut holds even
  while our process is down. This is a fail-closed property the VPN can't give.

**Vibb's app push** (design 108): a push for a cut app is acked and dropped, not delivered (`up_rules.json` row
`net_off`, `UpDelivery.plan`):
- Its only users are Matrix apps, whose `event_id_only` push needs the network to fetch the message, so it would wake
  an app that can do nothing.
- When Nett is on again, the app syncs at its next start.
- Google's push (FCM) still wakes the app. It can fetch nothing.

**What it does not cut**: what the app hands to another app, such as a link opened in the browser or a share. That
other app's network is its own switch.

### 4.3 Android 14-17

| | 14 (API 34) | 15 (35) | 16 (36, the Jelly Star) | 17 (37) |
|---|---|---|---|---|
| `setPermissionGrantState` DENIED for camera, location, microphone, contacts | works for a DO [camera: code, not yet device-run (design 68 §6v owed); the others: assumed] | no change known [assumed] | no change known [assumed] | [assumed; the gate runs it] |
| a DO may not GRANT sensors (since 12) | we never grant | = | = | = |
| revoke kills the app | yes; the call deferral | = | = | = |
| preferential network, block non-matching | API present **[sdk]**; behaviour [probe] | [probe not needed if 14 and 16 agree; assumed] | [probe] | API present, not deprecated **[sdk]**; behaviour [probe] |

Android 16/17's new local-network permission (`ACCESS_LOCAL_NETWORK`, nearby-devices group) is not part of this round.
Nett off cuts the LAN too.

### 4.4 While paused

**All five lift** in "pause all restrictions" and the offline override, the window that `apply()` and
`enforceAccessOnPackage` compute today (`OfflineOverride.isActive() || RestrictionsPause.isActive()`) **[code]**:
- Every DENIED target goes to DEFAULT. The app asks again, and the kid's dialog decides.
- The network config is cleared.
- The relock's `apply()` denies and cuts again, through the call deferral.
- The PWA says "På i pausen" under each switch that is off.

**None of the five is a hardening switch.** The standing rule (launcher `CLAUDE.md`, "Standing rule on restrictions")
applies with no exception. The only exceptions remain the call rules, the hardening switches and the Private DNS lock.

## 5. Edge cases

- **A third-party app only** (decided):
  - The `access` report, and so the Tilgang group, stays third-party, as design 68 decided **[code]**
    (`AppAccessReader.report`).
  - Preinstalled apps include the phone's own plumbing (Play services, the Phone and Messages apps, Settings), where
    a wrong cut breaks calls, Play or the setup.
  - A preinstalled app the kid shouldn't use is better not allowed at all. Q1 asks only about Nett for preinstalled
    apps.
- **An app that later asks for a new permission**:
  - The report gains the key, and its switch appears, on.
  - A catalog release that newly asks for a sensitive group is already held for the parent (design 90). The head
    shows "Ber om tilgang til Mikrofon" with Godkjenn / Behold.
  - A denial row that is still stored from before applies at the update (`enforceAccessOnPackage`) before the first
    start.
- **A row for a key the app no longer requests** is ignored by the launcher and not shown. It is pruned when the app
  leaves the report (§3.4).
- **Vibb's own apps**:
  - Vibb One has no page.
  - Vibb music has a page with the head and Vises som only. It gets no Tilgang: it is in `never`, and its network is
    the music's (Spotify, Storytel, the courier). It gets no Låseskjerm either: its player always shows **[code]**
    (`lock_media_offered`).
- **Meldinger** (`me.vibb.builtin.messages`):
  - A page with a head with no version and no Tillatt switch (its value as today).
  - Vises som with Automatisk · Symbol (no Logo, design 82 D9).
- **No logo yet** (design 87):
  - The head's tile is the letter.
  - "Logo" can still be chosen: the phone draws the real icon, and the preview shows today's "Logo" text tile until
    the phone has sent it.
- **A catalog app ticked but not installed**: a page with the head (its install state) and Vises som, but no Tilgang
  until the report has it.
- **The parent cuts an app the kid's calls depend on** (Element X: microphone, Nett): the switch does it. The (i) says
  what stops (§6). The managed-calls rules are not touched.
- **Two phones**: every switch is per phone. Only Godkjenn / Behold act on the catalog app, and the (i) says so.

## 6. Words (nb / en), checked against "Words in the UI"

| Where | nb | en |
|---|---|---|
| list, the switch's hidden label | {app} tillatt | {app} allowed |
| list, one thing waits (orange) | (that thing's own words) | (that thing's own words) |
| list, several (orange) | {n} ting venter på deg | {n} things wait for you |
| head sub-line | Versjon {version} | Version {version} |
| head switch | Tillatt | Allowed |
| held (orange), existing `apps.update.held_title` | {version} venter på deg | {version} waits for you |
| held sub, existing `apps.update.asks` | Ber om tilgang til {groups} | Asks for {groups} |
| buttons, existing | Godkjenn · Behold {version} | Approve · Keep {version} |
| held (i), new line | Gjelder appen på alle telefonene. | Applies to the app on every phone. |
| held (i), link | Appens side i Apper | The app's page in Apps |
| group header | Vises som | Shown as |
| field | Navn | Name |
| segments | Logo · Automatisk · Symbol | Logo · Automatic · Symbol |
| row | På Hjem | On Home |
| row button | Tilbakestill | Reset |
| Vises som (i), existing `apps.on_home_sub` | Av: appen er fortsatt tillatt og kjører, … | (existing) |
| group header | Tilgang | Access |
| switches | Nett · Kamera · Posisjon · Mikrofon · Kontakter | Network · Camera · Location · Microphone · Contacts |
| a switch's sub | Venter på telefonen | Waiting for the phone |
| a switch's sub | På i pausen | On during the pause |
| a switch's sub (orange) | Telefonen kan ikke slå av dette | The phone can't turn this off |
| approvals (orange), one | {access} ikke godkjent | {access} not approved |
| approvals (orange), several | {n} tilganger ikke godkjent | {n} accesses not approved |
| Tilgang (i), first line | Bare det appen ber om, står her. Av kamera, posisjon, mikrofon eller kontakter lukker appen hvis den var i gang. | Only what the app asks for is listed. Turning off camera, location, microphone or contacts closes the app if it was running. |
| (i), Nett | Nett av: appen virker bare uten nett og får ingen varsler gjennom Vibb. | Network off: the app works only offline and gets no notifications through Vibb. |
| (i), Kamera (replaces `apps.perms.camera_help`) | Kamera av: videosamtaler og QR-skanning virker ikke. | Camera off: video calls and QR scanning stop working. |
| (i), Posisjon | Posisjon av: appen ser ikke hvor telefonen er. | Location off: the app can't see where the phone is. |
| (i), Mikrofon | Mikrofon av: samtaler og lydopptak i appen virker ikke. | Microphone off: calls and recordings in the app stop working. |
| (i), Kontakter | Kontakter av: appen ser ikke kontaktene på telefonen. | Contacts off: the app can't see the phone's contacts. |
| group header, row | Låseskjerm · Mediespiller | Lock screen · Media player |
| refusal, full | Denne telefonen har allerede 200 apper med noe slått av - slå noen på igjen først. | This phone already has 200 apps with something turned off - turn some back on first. |

The check against "Words in the UI":
- Headings and choices are a word or two.
- No sub-line restates a choice: an off switch says nothing unless it isn't applied.
- Explanations sit behind one (i) per group, with no "Om …" label. The ⓘs keep the existing aria labels (`common.more_info`,
  `common.how_to_fix`).
- Orange is only for what needs the parent: a held update, a missing approval, a refusal.
- The parent's own choices (Nett av, Kamera av) are never orange and are not repeated on the list.
- Dropped from the mockup: "Endringer lagres med en gang" (every switch in the PWA saves at once; saying it is
  over-explaining) and "Slik ser barnet appen på Hjem" (the preview says it).
- The removed keys are `apps.perms.title`, `camera_off`, `camera_waiting`, `camera_paused`, `access_state`, `approved`
  and `apps.display.title`. `i18n::tests` keeps both catalogs equal.
- Nothing on the kid's phone changes words.

## 7. Files, by step

- **Step 1 (server, the page)**:
  - `src/handlers/app_page.rs` (new), `handlers/mod.rs`, `main.rs` (route).
  - `handlers/device_pages.rs`: `AppView` loses `display`/`perms` and gains `waits` (the list's orange part);
    `render_apps` keeps the list only; `?perms=` redirects.
  - `handlers/devices.rs`: `toggle_app` `back=app`; `save_app_display` lands on and refuses with the page, and reads
    `icon_mode`; the camera and lock-media saves land on the page.
  - `handlers/tracked_apps.rs`: `back=device:` on approve/decline; `phone_apps_href` points to the page.
  - `app_display.rs`: the form's mode, the default glyph, no Save.
  - `app_permissions.rs`: `PermsView` becomes `AccessGroup`, with today's camera key, the specials and lock media.
  - Templates: `device_app.html` (new), `partials/device_app_row.html` (the split row),
    `partials/app_display_group.html` (replaces `app_display_form.html`), `partials/app_access_group.html` (replaces
    `app_permissions_form.html`), `tracked_app_detail.html`.
  - `static/style.css`: `.row-split`, `.split-sw`, `.home-peek`, the 44 px picks, the swatch line; head.html's `?v`.
  - `static/app-display.js`: segments, preview, `?v`; `jstest/app-display.test.js` (new).
  - `i18n/en.json`, `nb.json`.
  - Tests: `src/tests/app_page.rs` (new), `src/tests/device_pages.rs`.
  - `docs/notes/server/pwa.md`.
- **Step 2 (both directories, one commit: the API)**:
  - Server: `migrations/0164_app_denials.sql`; `app_permissions.rs` (four keys, policy, `AppAccess` values,
    `set_denied`, prune, `the_contract`); `handlers/devices.rs` (`save_app_permissions` by `key`/`allow`);
    `handlers/device_api.rs`; `models.rs`; the page's Tilgang with four switches; `intake_limits`;
    `policy_json_keys_snapshot`.
  - Contract: `server/testdata/app_access.json`, `launcher/app/src/test/resources/app_access.json`.
  - Launcher: `server/EnforcementPlan.kt` (`AccessKey`, `accessGrants`, `appPermissionsOf`); `server/AppEnforcer.kt`
    (the pass, the deferral, `enforceAccessOnPackage`, `accessRefused`); `server/AppAccess.kt` (the report);
    `lock/BootHome.kt`; `server/dto/PolicyResponse.kt` (doc).
  - Launcher tests: `EnforcementPlanTest`, `AppAccessTest`, `AppAccessContractTest` (new), `PolicyResponseCompatTest`,
    `app_access_rules.json`.
  - `.github/workflows/launcher.yml` and `server-ci.yml` (paths), root `CLAUDE.md`,
    `docs/notes/launcher/app-permissions.md`.
- **Step 3 (Nett, after step 0 passes; both directories, one commit)**:
  - Launcher: `server/AppNetwork.kt` (new: pure plan and writer); `AppEnforcer.kt` (the hook); `AppAccess.kt`
    (`network`); `push/up/` delivery (`net_off`).
  - Launcher tests: `AppNetworkTest`, `UpDeliveryPlanTest`, `up_rules.json`.
  - The contract's `network` rows; server: `network` in `app_permissions.rs`'s table.
  - `docs/notes/launcher/app-permissions.md`, `dns-and-vpn.md` (one paragraph: the cut beside the shapes).

## 8. Tests (lean)

- **Server**:
  - The page:
    - Every group shows only when it should: an app with nothing reported, a preinstalled app, Vibb music,
      Meldinger, an app turned off on its page.
    - The split row and the list's orange count (one item, two items).
    - The `?perms=` redirect, and the unknown package's 303.
    - The back link per `from`, including a catalog `from` for this app versus another.
  - The display form: `icon_mode` per mode, the default glyph, the 400 at the field; the catalog page still saves.
  - A held update: shown, with Approve and Keep and `back=device:` landing on the page with the flash; a bad `back` is
    the catalog's redirect.
  - `the_contract` (§3.3); the migration copies camera rows; the cap and the prune; a stale key refused.
  - `nb_pages_have_no_english` covers the new page.
- **Launcher**:
  - `accessGrants` rows: each key, the window, `null`, the call deferral per key, hidden skipped.
  - `networkUids` rows: denied, the window, `null`, a shared uid, `never`.
  - The report's readings (`admin_denied` only when every requested permission is DENIED; `refused`).
  - The contract test; the `net_off` UP row.
- **JS**: `app-display.test.js` (the segments show and hide the picks, the preview follows the name and the glyph).

## 9. Steps and sizes

| Step | What | Size | Needs |
|---|---|---|---|
| 0 | **Probe** (emulator: compat34, jelly36, the 17 image) | S | the user's OK for the emulator |
| 1 | The page, the list's split row, Vises som autosave, the held update, today's camera and lock media moved | L | nothing - can start now (server only) |
| 2 | Kamera/Posisjon/Mikrofon/Kontakter: migration, contract, server and launcher | L | step 1 merged (the page it fills) |
| 3 | Nett | M | step 0 passed on all three images; step 2 |
| 4 | Emulator gate and the Jelly Star check | S | the user's OK; steps 1-3 |

**Step 0, the probe**, on a throwaway branch, not merged:
- A debug-only receiver in the debug launcher sets and clears the config for a package's uid.
- The client is the debuggable `me.vibb.music.debug`, through `adb shell run-as … toybox nc -w 5 <ip> 443` and a
  name.
- Pass means each of these holds on all three images:
  - The cut uid can't connect on Wi-Fi or on mobile, in each VPN shape (filter, sink, pass), and with the filter off.
  - A `bindProcessToNetwork`/`Network.openConnection` to Wi-Fi fails. This needs the probe receiver to try it as the
    music app, through its debug bridge; or the probe drops this check and the gate does it with a test APK.
  - A `DownloadManager` request for the cut uid doesn't run.
  - Every other app and the launcher (sync, SSE, tsnet) are unaffected.
  - Clearing the config restores the cut app's network.
  - The cut survives a reboot.
  - `dumpsys connectivity` shows the uid's request with no network.
- One failure on any image: Nett is not built, and the user hears it.
- The probe also denies location, microphone and contacts for a test app on 14 and 17 (`dumpsys package`: granted
  false, `POLICY_FIXED`), and checks that DEFAULT clears the fix.

**Step 4, the gate** (`docs/testing/emulator.md`, a new §; the lowest and highest versions, 14 and 17; jelly36 for the
eye):
- Each switch off and on.
- A denial that kills a running app; a denial waiting for a live call's end.
- The pause lifts all five, and the relock applies them again.
- An update that adds `RECORD_AUDIO`, denied before its first start.
- Nett with Bare Vibb on mobile.
- The UP drop.
- The PWA page at 320 and 390 px, light and dark: the symbols' 44 px, and a save that keeps the place.

## 10. Questions for the user

**Q1 - Nett for preinstalled apps.** The Tilgang switches (Nett included) are offered only for apps that weren't on
the phone from the factory, as the camera switch is today. So Google's preinstalled apps, such as YouTube or Chrome
where they came with the phone, get no Nett switch. They can still be blocked entirely with Tillatt.
- **Recommendation: keep it so.** Among the preinstalled apps are the ones the phone needs to work, and cutting the
  wrong one breaks calls or Play.
- If you want Nett for preinstalled apps too, it can be offered for them, with Google Play services, the Phone and
  Messages apps, Settings and Vibb left out.
