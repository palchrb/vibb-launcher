# Design 127: An app's own page, and what each app may use

Status: design, revision 2, 2026-11-22 (branch `design-127`, from main fe3e96c84): the design QA's findings are folded
in (§11), and the user's answer to Q1 ("1 ok", 2026-11-22) is decided in §5. Server and launcher. The user
approved the mockup (2026-11-22, "Appsiden ser bra ut, bare å implementere";
https://claude.ai/artifact/LtUfDhmfvCKzY7No7cC1i3) with its four choices as proposed: name and icon save at once,
approving an update acts on the catalog app and the page says so, the split list row, 44 px hit areas for the symbols.
Also approved earlier (the privacy round, A1): a per-app network switch on this page, on by default, and switches for
location, microphone and contacts like today's camera switch - never deny-by-default.

Marks: **[code]** read in the code at fe3e96c84; **[sdk]** read in the Android SDK's `api-versions.xml` for API 37
on this machine; **[assumed]** not verified, to be checked by step 0's probe or the emulator gate (§9).

## 0. Short answer

- Every allowed app gets a page, Device › Apps › <app> (`/devices/{id}/apps/{package}`). The list keeps one row per
  app: the name opens the page, and the allow switch stays on the row behind a hairline.
- The page has four groups: the head (logo, name, version, Tillatt, an update that waits for the parent), **Vises som**
  (the Home tile beside the name, Logo · Automatisk · Symbol, all saving at once), **Tilgang** (Nett, Kamera,
  Posisjon, Mikrofon, Kontakter - only what the app asks for - with the special accesses as quiet values), and
  **Låseskjerm** (Mediespiller).
- Kamera, Posisjon, Mikrofon and Kontakter use today's camera mechanism, widened: `setPermissionGrantState` DENIED per
  permission while the parent has it off, DEFAULT otherwise. We never GRANT.
- **Nett uses `DevicePolicyManager.setPreferentialNetworkServiceConfigs`** with a config that includes the apps' uids,
  allows no fallback and blocks every non-matching network (§4.2). No network on a consumer phone carries the enterprise
  capability it asks for, so those uids get no network at all. It is the only device-owner tool that cuts one app's
  network on Wi-Fi and mobile alike.
  - It needs one change in our VPN: a cut app also leaves the VPN (`addDisallowedApplication`) in every shape, so the
    VPN can't become its way out (§4.2).
  - Bare Vibb and the pause keep their rules.
  - It is built only if step 0's probe shows one of its two variants holding on Android 14, 16 and 17. If neither
    holds, there is no Nett switch: no weaker DNS-only cut.
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
- **Who gets a page**: every row of the phone's list with a valid package name that is allowed (`row.checked`).
  - **Tillatt off removes the app.** A not-preinstalled app is queued for uninstall
    (`device_pending_uninstalls`, **[code]** `toggle_app`), and a preinstalled one is hidden. So turning it off on its
    page lands on the list at its row (`#app-<package>`), now under "Not allowed". There is no head-only page to turn
    it on again from.
  - **No page**:
    - Vibb One: its row stays "Alltid på", and its versions are on its catalog page.
    - Kalender: a part of Vibb One with no package; its row stays a link to Device › Calendar (`#dagen`) with its
      On Home value, and the phone reads no Vises som for it.
    - The managed dialer: it stays a link to Calls.
    - A catalog app with no package yet.
    - A not-allowed row: it stays a plain switch row.
- **An unknown or not-allowed package** (not among this phone's allowed rows, e.g. removed since): `303` to the list,
  at its row when it has one. Never a 404 page: a page in the history must not become an error.
- **The tab, the back link and `from`**:
  - The page takes the list's `?from=`, read only through `AppsFrom::parse` **[code]** (`device_pages.rs`: `apps` or
    `app:<digits>`, anything else ignored). It keeps the same tab, and every form carries it.
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
for logins. It loses the permissions line, and gains one orange part for an update held for approval ("7.25.0 venter
på deg"). That is the only thing on the list that waits for the parent and that the parent can act on.

Left off the list, on purpose:
- A special access not approved is often the parent's own choice, and stays a quiet value on the page (§2.5).
- A switch the phone refused has no fix the parent can make, and shows only on the page.
- A choice the parent made ("Nett av") is never a warning (Words in the UI).

### 2.3 The head (`#app-head`)

- **The tile**: the real logo over the letter, else the chosen glyph on its colour, else the letter. This is the
  row's rule (design 87 §8.1). It is also the band's small picture, where the phone page shows the avatar.
- **The name and its sub-line**:
  - `installed_apps[].version_name` from the last report ("Versjon 7.24.1").
  - While the app installs, today's status label ("Installerer 42 %"); the page reloads itself as the list does
    (`data-waits`).
  - The dialer note and Chrome's login line, as in the list.
- **"Tillatt"**: the allow switch, `/apps/toggle` with `back=app`.
  - Saved on, it lands on the page (`#app-head`). Saved off, it lands on the list (§2.1).
  - The refusals' words show here as the list shows them under the row.
  - For a not-preinstalled app, its (i) says that off removes the app: today's `device.apps.footer`, "Slår du av en
    app, skjules den, og den avinstalleres der det går".
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
  - `<input type="text" name="label" data-autosave>`, no `maxlength` (the browser counts UTF-16 units, the server
    characters; qa-14-code #4 - corrected in step 1's code QA). A text field saves on change, i.e. on blur or
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
- **A refused save** (a name over 20 characters, the 200-row cap) renders this page as a 400. The
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
  - Orange "Telefonen kan ikke slå av dette": the report says `refused`. The parent's off isn't applied, which is
    wrong. It is shown here only, never counted on the list (§2.2).
- **The special accesses** (design 68's five) stay quiet values, as today **[code]** (`app_permissions.rs`,
  `words::state`):
  - One row per access the app declares, with the indigo tile, the access's name, and its value "Godkjent", "Ikke
    godkjent" or "Ukjent", in plain text.
  - The way to approve one on the phone is behind the group's (i), one line per access not approved (design 68's
    words; Alarmer og påminnelser keeps "and open the app once").
  - Not orange, and not counted on the list. Full-screen notifications are declared by most messengers and not granted
    by default on Android 14+, so orange would be permanent noise. And not approving is often the parent's choice.
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
  - From step 3 (§9), `network` is reported only once the launcher applies it. The PWA shows the Nett switch only
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

- **Migration `0166_app_denials.sql`** (0164 is `upstream-fixes`' `0164_install_failure_reason.sql`):
  - `device_app_denials (device_id REFERENCES devices ON DELETE CASCADE, package_name, permission TEXT CHECK
    (permission IN ('network','camera','location','microphone','contacts')), updated_at, PRIMARY KEY (device_id,
    package_name, permission))`.
  - It copies each `device_app_permissions` row as `camera`, then drops the old table.
  - A key row table needs no NULL columns and no table rebuild per new key. The old table's `camera NOT NULL CHECK
    (camera = 'deny')` **[code]** can't be widened in place in SQLite.
- **The cap**:
  - At most 200 packages a phone (`MAX_ROWS_PER_DEVICE`, counted as distinct packages other than this one), as
    `set_camera` today **[code]**.
  - **No prune.** A removed app keeps its rows, so a catalog app turned off and on again (uninstalled, then
    reinstalled) comes back with its denials before its first start. The cap bounds the table, and its refusal tells
    the parent.
  - Allowing always deletes the one row.
- **The save route** `POST /devices/{id}/apps/permissions` (`save_app_permissions`, widened):
  - `key` must be one of the five, else 400.
  - `allow=on` allows; its absence denies.
  - The target is checked as today (`perms_target`: the grammar, the phone, not Play's core, not Vibb). Vibb music
    is refused too.
  - **A deny** of a key the app doesn't request in the last report is refused (`notice=perms`): a stale form.
    **An allow is always accepted**, whatever the app is now, so any row can be cleared. This is `save_lock_media`'s
    rule **[code]**.
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
- **The call deferral now covers all four, and Nett (§4.2).** Revoking any granted runtime permission kills the app. So while a call is
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
| **A. `setPreferentialNetworkServiceConfigs`**: included uids, `setFallbackToDefaultConnectionAllowed(false)`, `setShouldBlockNonMatchingNetworks(true)` | yes [assumed], since no network matches | binding to another network is blocked by "block non-matching" [assumed] | only with the cut app outside our VPN (variant A2 below): inside it, the VPN is a way out | **chosen, behind step 0** |
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

**Our VPN: the cut app leaves it (design QA H1).** Every app but the launcher is inside our VPN today **[code]**
(`KidVpnService.kt`: `addDisallowedApplication(packageName)` for ours alone). The filter shape routes only
`192.0.2.2/32`, the pass shape routes nothing, and the filter is the shape whenever the filter is on
(`MobileNetwork.kt`'s `vpnShape`). A cut app left inside the VPN very likely leaks:
- The VPN's uid ranges outrank the enterprise preference, so the VPN becomes the cut uid's default network.
- netd's fall-through for a packet the VPN doesn't route goes to the system default network, not the uid's
  "no service" network.
- The blocking allowlist is checked when the network is selected, and the VPN's netId passes first.

So the design has two variants, and step 0 probes both:
- **A1, the preference alone**: kept only to learn from, in case it does hold.
- **A2, the preference and the cut app outside the VPN** (the design): every cut package also goes into
  `addDisallowedApplication` in every shape, outside a window.
  - Its default network is then decided by the preference alone: the "no service" network.
  - Its DNS no longer reaches our filter, which it can't use anyway.
  - In the sink shape, lockdown blocks it as well: an app outside the VPN gets nothing under lockdown, unless it is on
    the lockdown allowlist, which it never is.

**A2's costs:**
- **Coupling**: `KidVpnService`'s `Builder` reads the cut set that `AppNetwork` stores (`app_net_cut`, sorted
  packages), beside the `vpn_inputs` it reads today.
- **Re-establish**: a change in the cut set re-establishes the tunnel (`switchTo` with the same shape). That is an
  in-place handover: the same network, a new uid range, and other apps' sockets untouched **[assumed, probed]**. The
  cut app's open sockets end, as they should.
- **The order, so no moment leaves an app outside the VPN uncut**:
  - Cutting: the preference first, then the tunnel without the app.
  - Lifting: the tunnel with the app first, then the preference cleared.
- **Probe both variants, the filter shape first**: it is the normal one, and where A1 would leak.
- **Untouched**: the launcher's own uid is never included and is outside the VPN as today, so the sync, SSE, tsnet and
  our downloads are untouched. Bare Vibb and the roaming lock keep their rules: two independent cuts, and an app is
  offline if either applies.

**The plan** (`AppNetwork.kt`, pure `networkUids`):
- From the policy's `network` denials, the installed third-party packages that request `INTERNET` and their uids
  (`getPackageUid`, `MATCH_UNINSTALLED_PACKAGES`), the window and `never`: the set of uids to include.
- A uid shared by several packages (`sharedUserId`) is included only when every package on it is denied. Otherwise
  each such package reports `refused`.
- With `denied == null` (a Fallback): nothing changes, except that a window clears it.
- **The SDK Runtime**: on Android 15+ each cut uid's SDK sandbox uid is included too
  (`Process.getSdkSandboxUidForAppUid`, API 35 **[sdk]**, behind `SDK_INT`). On 14 the sandbox uid has no public
  API, and it is out of scope (§4.2, "What it does not cut").
- **The call deferral (design QA L1)**: while `WindowRelock.liveCall` holds **[code]** (`WindowRelock.kt`: ours,
  Telecom's, a VoIP call, the lock's emergency flow), uids the write would *add* wait. Removals go at once.
  `accessDeferred` covers it, and `BootHome.callMayHaveEnded` applies again, as for the grant keys. Cutting Element X
  mid-call would end the call.

**The write**:
- **Always the whole set (design QA L2)**: every `apply()` (after the grant pass) and every package event recomputes
  all cut uids from the installed packages. The events are install, change and **removal** (`PACKAGE_REMOVED`
  included, through `Application.onPackageChanged` and `enforceAccessOnPackage`).
  - Android reuses a freed appId, so a removed cut app's uid must leave the config before another app can get it.
  - A reinstall's new uid is cut before the app's first start, as for the camera.
- **Written only on a change**: the sorted uids are compared with `getPreferentialNetworkServiceConfigs()`. The write
  is one enabled config with the uids, or `PreferentialNetworkServiceConfig.DEFAULT` (disabled) when none. Then the
  stored cut set goes to the VPN in A2's order.
- **Persistence [assumed, probed]**: the config is Android's to keep (DPMS persists it across reboots and launcher
  crashes), so the cut would hold while our process is down. Our VPN can't give that fail-closed property.

**Vibb's app push** (design 108): a push for a cut app is acked and dropped, not delivered (`up_rules.json` row
`net_off`, `UpDelivery.plan`):
- Its only users are Matrix apps, whose `event_id_only` push needs the network to fetch the message, so it would wake
  an app that can do nothing.
- When Nett is on again, the app syncs at its next start.
- Google's push (FCM) still wakes the app. It can fetch nothing.

**What it does not cut** (design QA L5) - each runs under another uid:
- What the app hands to another app: a link opened in the browser, a share.
- A **Custom Tab** runs in the browser's uid, so an app can still show web pages through Chrome.
- APIs that Google Play services proxies for the app (Firebase, Maps, fused location, Play Integrity) run in Play
  services' uid.
- The **SDK Runtime's** sandbox uid on Android 14 (it is covered on 15+, above).

Each is another app's network, and not this switch's.

### 4.3 Android 14-17

| | 14 (API 34) | 15 (35) | 16 (36, the Jelly Star) | 17 (37) |
|---|---|---|---|---|
| `setPermissionGrantState` DENIED for camera, location, microphone, contacts | works for a DO [camera: code, not yet device-run (design 68 §6v owed); the others: assumed] | no change known [assumed] | no change known [assumed] | [assumed; the gate runs it] |
| a DO may not GRANT sensors (since 12) | we never grant | = | = | = |
| revoke kills the app | yes; the call deferral | = | = | = |
| preferential network, block non-matching, with A2's VPN exclusion | API present **[sdk]**; behaviour [probe] | [probe not needed if 14 and 16 agree; assumed] | [probe] | API present, not deprecated **[sdk]**; behaviour [probe] |
| SDK sandbox uid | no public API: out of scope | `getSdkSandboxUidForAppUid` **[sdk]** | = | = |

Android 16/17's new local-network permission (`ACCESS_LOCAL_NETWORK`, nearby-devices group) is not part of this round.
Nett off cuts the LAN too.

### 4.4 While paused

**All five lift** in "pause all restrictions" and the offline override, the window that `apply()` and
`enforceAccessOnPackage` compute today (`OfflineOverride.isActive() || RestrictionsPause.isActive()`) **[code]**:
- Every DENIED target goes to DEFAULT. The app asks again, and the kid's dialog decides.
- The network config is cleared, and the cut apps go back into the VPN (lifting order, §4.2).
- The relock's `apply()` denies and cuts again, through the call deferral.
- The PWA says "På i pausen" under each switch that is off.

**None of the five is a hardening switch.** The standing rule (launcher `CLAUDE.md`, "Standing rule on restrictions")
applies with no exception. The only exceptions remain the call rules, the hardening switches and the Private DNS lock.

## 5. Edge cases

- **A third-party app only** (decided by the user, 2026-11-22: "1 ok"):
  - **Every Tilgang switch** (Nett, Kamera, Posisjon, Mikrofon, Kontakter) is offered only for apps the parent
    installed, not preinstalled ones. This is what design 68 decided for the camera **[code]**
    (`AppAccessReader.report`).
  - Preinstalled apps include the phone's own plumbing (Play services, the Phone and Messages apps, Settings), where
    a wrong cut breaks calls, Play or the setup.
  - A preinstalled Camera, Maps or YouTube can only be switched off entirely (Tillatt).
- **An app that later asks for a new permission**:
  - The report gains the key, and its switch appears, on.
  - A catalog release that newly asks for a sensitive group is already held for the parent (design 90). The head
    shows "Ber om tilgang til Mikrofon" with Godkjenn / Behold.
  - A denial row that is still stored from before applies at the update (`enforceAccessOnPackage`) before the first
    start.
- **A row for a key the app no longer requests**, or for an app removed since, is kept (§3.4). The launcher ignores
  it, and the page doesn't show it. It applies again if the app asks for that key again, or is reinstalled.
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
| list, a held update (orange) | {version} venter på deg | {version} waits for you |
| head sub-line | Versjon {version} | Version {version} |
| head switch | Tillatt | Allowed |
| head switch (i), not preinstalled, existing `device.apps.footer` | Slår du av en app, skjules den, og den avinstalleres der det går. | (existing) |
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
| a special access, its value (quiet), existing `apps.perms.*` | Godkjent · Ikke godkjent · Ukjent | Approved · Not approved · Unknown |
| Tilgang (i), first line | Av kamera, posisjon, mikrofon eller kontakter lukker appen hvis den var i gang. | Turning off camera, location, microphone or contacts closes the app if it was running. |
| Tilgang (i), per access not approved, existing `apps.perms.how` / `how_alarm` | På telefonen: Innstillinger (foreldrekode) › … | (existing) |
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
- Orange is only for what is really wrong: a held update, and on the page a switch the phone refused. A special
  access not approved is a quiet value, as in design 68.
- "Bare det appen ber om, står her" is dropped: the rows show it.
- The parent's own choices (Nett av, Kamera av) are never orange and are not repeated on the list.
- Dropped from the mockup: "Endringer lagres med en gang" (every switch in the PWA saves at once; saying it is
  over-explaining) and "Slik ser barnet appen på Hjem" (the preview says it).
- The removed keys are `apps.perms.title`, `camera_off`, `camera_waiting`, `camera_paused`, `access_state` and
  `apps.display.title`. `i18n::tests` keeps both catalogs equal.
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
  - Server: `migrations/0166_app_denials.sql`; `app_permissions.rs` (four keys, policy, `AppAccess` values,
    `set_denied`, `the_contract`); `src/tests/device_api.rs` (its table list: `device_app_permissions` becomes
    `device_app_denials`, design QA L8); `src/tests/device_pages.rs` (the camera rows' table); `handlers/devices.rs` (`save_app_permissions` by `key`/`allow`);
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
  - Launcher: `server/AppNetwork.kt` (new: the pure plan, the writer, the stored cut set); `KidVpnService.kt` (the cut
    set into `addDisallowedApplication` in every shape, a re-establish on its change, A2's order); `AppEnforcer.kt`
    (the hook, the call deferral); `Application.kt` (package removal); `AppAccess.kt` (`network`); `push/up/`
    delivery (`net_off`).
  - Launcher tests: `AppNetworkTest`, `UpDeliveryPlanTest`, `up_rules.json`.
  - The contract's `network` rows; server: `network` in `app_permissions.rs`'s table.
  - `docs/notes/launcher/app-permissions.md`, `dns-and-vpn.md` (one paragraph: the cut beside the shapes).

## 8. Tests (lean)

- **Server**:
  - The page:
    - Every group shows only when it should: an app with nothing reported, a preinstalled app, Vibb music,
      Meldinger; Tillatt off on the page lands on the list; a not-allowed package's 303.
    - The split row.
    - The `?perms=` redirect, and the unknown package's 303.
    - The back link per `from`, including a catalog `from` for this app versus another.
  - The display form: `icon_mode` per mode, the default glyph, the 400 at the field; the catalog page still saves.
  - A held update: shown, with Approve and Keep and `back=device:` landing on the page with the flash; a bad `back` is
    the catalog's redirect.
  - `the_contract` (§3.3); the migration copies camera rows; the cap; a deny of an unrequested key refused, an allow
    always accepted.
  - The list's orange part: a held update only (not a special access, not a refusal).
  - `nb_pages_have_no_english` covers the new page.
- **Launcher**:
  - `accessGrants` rows: each key, the window, `null`, the call deferral per key, hidden skipped.
  - `networkUids` rows: denied, the window, `null`, a shared uid, `never`, a removed package's uid leaving, the
    sandbox uid on 35+, a call deferring an addition but not a removal.
  - The VPN builder's disallowed list from the stored cut set, per shape; the cut and lift orders.
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
- **Both variants** are probed, A1 (the preference alone) and A2 (the preference and the app outside the VPN), the
  filter shape first. The receiver also restarts the VPN with the package disallowed, for A2.
- **Pass rule: one variant holds on Android 14, 16 and 17.** Holding means each of these is true:
  - The cut uid can't connect on Wi-Fi or on mobile, in each VPN shape (filter, sink, pass), and with the filter off.
  - A `bindProcessToNetwork`/`Network.openConnection` to Wi-Fi fails. This needs the probe receiver to try it as the
    music app, through its debug bridge; or the probe drops this check and the gate does it with a test APK.
  - A `DownloadManager` request for the cut uid doesn't run.
  - Every other app and the launcher (sync, SSE, tsnet) are unaffected.
  - Clearing the config restores the cut app's network.
  - The cut survives a reboot (§4.2's persistence assumption).
  - Under A2, another app's open connection survives the re-establish.
  - `dumpsys connectivity` shows the uid's request with no network.
- If neither variant holds on all three images, Nett is not built, and the user hears it. If only A1 holds, the build
  still uses A2 (it is the safer of the two), unless the probe shows A2 costing something A1 doesn't.
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

None open. Q1 (Tilgang for preinstalled apps) was answered by the user on 2026-11-22 with "1 ok": the switches are
offered only for apps the parent installed (§5).

## 11. QA revision 2

How each design QA finding was handled:

- **H1** (Nett leaks through the VPN in the filter and pass shapes): taken. §4.2 adds variant A2: every cut package
  goes into `addDisallowedApplication` in every shape, outside a window.
  - The costs are named: the coupling to `KidVpnService` and a re-establish when the cut set changes.
  - The cut and lift orders are set.
  - Step 0 probes A1 and A2, the filter shape first. Its pass rule is "one variant holds on 14, 16 and 17".
  - §0's "does not touch our VPN" is reworded.
- **M1** (special accesses): a special access not approved stays a quiet value, as in design 68. The way to approve
  is behind the (i), and it isn't counted on the list (§2.5, §2.2, §6).
- **M2** (Tillatt off uninstalls): §2.1 and §2.3 say that off removes the app and lands on the list. The head-only
  page is dropped. The prune is dropped (§3.4), so a re-ticked catalog app comes back with its denials. "Hidden apps
  are included too" is removed.
- **L1**: the network write defers additions while `WindowRelock.liveCall` holds (§4.2).
- **L2**: every install, change and removal recomputes the whole uid set. The sorted uids are compared with the
  config. Freed appIds are named (§4.2).
- **L3**: persistence across reboots and crashes is marked [assumed, probed] and is in the probe.
- **L4**: only a deny of an unrequested key is refused; an allow is always accepted (§3.4).
- **L5**: Custom Tabs, Play services' proxied APIs and the SDK Runtime are listed. The sandbox uid is included on
  15+, and is out of scope on 14 (§4.2, §4.3).
- **L6**: `from` goes through `AppsFrom::parse` (§2.1).
- **L7**: "Bare det appen ber om, står her" is dropped. The list counts no refusal; its only orange part is a held
  update (§2.2, §6).
- **L8**: step 2 renames the table in `src/tests/device_api.rs` (§7).
- **The migration number**: 0166, since 0164 is `upstream-fixes`' and 0165 design 126's `block_2g` (§3.4, §7).
- **Q1**: decided by the user's "1 ok", for all Tilgang switches (§5, §10).
