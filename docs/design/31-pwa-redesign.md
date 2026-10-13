# 31 - The parent PWA redesign: inventory and information architecture

Information architect, 2026-10-13. Status: **for review** - mockups come next, then the build, one page at a time.
No code changes in this step.

Sources: every template in `server/templates/` (and `partials/`), `server/static/style.css`, `scroll-restore.js`,
`app-display.js`, `music-cards.js`, `manifest.webmanifest`, `templates/sw.js`, the admin routes in `server/src/main.rs`,
and the handlers that render and save the pages (`server/src/handlers/*.rs`). Runtime texts (the lines and warnings
that Rust builds) come from the same handlers and from `server/src/{streams,play,kiosk_escapes,window_relock,...}.rs`.
Word counts are counted on `main` at `5c1f40f6`.

**The user's brief (final decisions):**
- The PWA is "incredibly wordy, with DEBUG-like descriptions of every switch". It should be simpler, tidier and much
  more polished, in the style of iOS Settings and Screen Time.
- **The bottom bar has 5 tabs: Devices · Apps · Locate · Music · Settings**, in that order (the user's final decision,
  2026-10-13; it replaces the earlier "Devices · Apps · Music · Time · More").
  - **Settings** holds Time Rules (near the top), Web Filter (today's DNS/Filters), Blocked Activity, Wallpapers,
    Account, Security Log, Backups, Software Updates, Phone Setup, About and Sign Out.
  - There is no More tab.
  - **Locate** is a tab of its own, at `/devices/locate`.
  - Per-phone time controls stay on the device page.
- **The language stays English** (no i18n). Every text is rewritten: a short label, at most one short line of help
  where it is really needed, and the details behind an "i" or on a sub-page.
- Mockups come first, for the user's review. The build follows page by page.

**How to read this doc:**
- §1 lists everything a parent can see today.
- §2 lists what is wrong with it.
- §3 is the new structure, and §3.9 says where every current control ends up.
- §4 is the copy rules, with before → after examples.
- §5 lists what the rebuild must keep.
- §6 lists the screens the mockups must show.
- §7 holds the questions for the user. Nothing is dropped without one.

---

## 0. The short version

- **The device page is the problem.**
  - It has 21 cards and about **3,300 words of fixed text** (`device_detail.html`; the next largest page, `music.html`,
    has 820).
  - The runtime lines and warnings come on top of that.
  - Nine paragraphs on it run past 80 words. The longest has 136 words (`device_detail.html:571`).
  - The things a parent does every day sit below "Launcher crashes", halfway down the page. Those are: see whether
    the phone is locked, add screen time, pause a rule, and lock the phone again.
- **The settings are scattered.** A topic is spread over several places:
  - The lock screen: the Apps card, the Wallpapers card, the Screen lock card and the Screen timeout card.
  - Filtering: the device page's VPN switch, the DNS page's per-device overrides, and the DNS log page's per-phone
    switch.
  - Location: the Hardening card ("Keep location on") and the Locate tab (mode, interval, history).
  - Language: the Launcher card, Hardening, and Provisioning.
- **Every warning looks the same.** Every warning, note and form error is the same red `.error` box. "Do Not Disturb
  is on", "the parent PIN is easy to guess" and "Ticked under Apps: it shows again after…" all look identical.
- **Implementation detail is everywhere.** The pages show:
  - adb commands and Android component names;
  - package names and env var names;
  - "tsnet", "kiosk", "MDM", "token", "policy" and "sync";
  - raw UTC timestamps.
- **The device page opens with the wrong thing.** Its first card ("Status") leads with "Time lock at the last
  report: none". After that it shows:
  - "App version: 0.23.6-debug";
  - "Sound at the last sync: on.";
  - "Last reported 2026-10-13 10:59:13".

  A parent first wants to know whether the phone is connected and how long ago it checked in (the user's feedback,
  2026-10-13).
- **The proposal:**
  - Each tab opens on a short, grouped list in the iOS style: rows with a value and a chevron, switches, one-line
    footers and badges.
  - The device page becomes a **summary**. Its header says **Online / Away / Offline · seen 3 min ago** (signal and
    thresholds in §3.10).
  - The time lock shows only while it is locking ("Bedtime until 07:00").
  - The summary also has a few quick actions and an "Attention" group, plus **9 sub-pages**. **About This Phone**
    holds sound, version and exact times. **Advanced** holds what a parent sets once or never. The summary's
    Location row opens the **Locate** tab on that phone.
  - The tabs are **Devices · Apps · Locate · Music · Settings**. Time Rules and Web Filter are rows in Settings.
  - Every existing control keeps a place (§3.9). Seven questions for the user are in §7.

---

## 1. Inventory

**Frequency key:** **D** daily · **W** weekly or when something comes up · **S** setup-only (once per phone or server) ·
**X** rare or expert (recovery, debugging, a one-off fix).

**Length:** the visible words of fixed text, counted from the template. "dyn" means Rust builds the text at runtime;
its length is given where it is known.

### 1.0 On every page

| Element | Where | Purpose | Freq | Text now |
|---|---|---|---|---|
| Header "Kids Device MDM" | `partials/app_header.html:2` | Brand; links to `/` | - | 3 words. The brand differs from the manifest's "Kids MDM" and from the phone's "Vibb" |
| Bottom nav (6 items) | `app_header.html:5-30` | Time rules · Devices · Apps · Locate · DNS/Filters · Settings | D | 1-2 words each, 0.68rem (~11px) |
| Active-tab script | `app_header.html:32-42` | Marks the tab with `data-match` regexes. Without JS, no tab is highlighted | - | - |
| `<title>` | `partials/head.html:3` | "{title} · Kids Device MDM" | - | - |
| Scroll restore | `partials/head.html:15-17`, `static/scroll-restore.js` | Keeps the position after any POST to the same path | - | - |
| Password warning (live) | `partials/login_warning.html:1-6` | The password is the generated one, or a known weak one | S | dyn + "Change the password" |
| Sign-in warning | `login_warning.html:7-17` | Failed 2FA or sign-in attempts since the last sign-in; Dismiss | X | dyn, ~15-25 words ("13 failed 2FA attempts since your last sign-in (from 127.0.0.1)") |

### 1.1 Devices list (`/devices`, `devices_list.html`)

| Element | Purpose | Freq | Text now |
|---|---|---|---|
| "Add a device" button | New phone | S | 3 |
| Intro paragraph (`:14`) | Explains provisioning by QR or adb, and Provisioning settings | S | 35, on every visit |
| Device rows | Name + `status_text`: "Not enrolled yet" / "Enrolled, not seen yet" / "Last seen {raw timestamp}" (`handlers/devices.rs:43-49`) | D | dyn. The timestamp is the raw DB value |
| Empty state | "No devices yet. Add one to get started." | S | 7 |

There is nothing about the state of a phone: locked or not, paused, a warning, screen time used.

### 1.2 Device page (`/devices/{id}`, `device_detail.html`, `handlers::devices::view_device`)

The cards are listed in page order. Unless a row says "Save button", every control saves itself (`onchange` submit
with a `<noscript>` Save).

**Not enrolled yet** (`:13-32`, shown instead of Status and Access until the phone enrolls)

| Element | Purpose | Freq | Text now |
|---|---|---|---|
| `access.notice` / `access.typed_paused` | Code-entry pause after failed tries, revoke notices | X | dyn |
| `access.live_code` | "A code is live until …" | S | dyn |
| Explainer `:25` | How to enroll by code or QR | S | 34 |
| New code · Provision by QR code instead | Make a code / go to the QR page | S | - |

**Status** (`:34-122`)

| Element | Purpose | Freq | Text now |
|---|---|---|---|
| "Time lock at the last report: none/bedtime/school/screen time used up/a time rule" + footer `:38` | Is a time rule locking the phone. It shows **first**, also when it says "none" | D | 8 + 26-word footer |
| App version | Launcher version, e.g. "0.23.6-debug" | X | 2 + dyn |
| `sound_lines` (`sound_mode.rs:31-63`) | "Sound at the last sync: on."; "… silent (vibrate) - calls and notifications only vibrate."; "fully silent" (32 words); "Do Not Disturb at the last sync: on (priority only) - …" (26-30 words, red) | W | dyn, 6-32 |
| "Last reported {time}" | Freshness, e.g. "Last reported 2026-10-13 10:59:13": the raw `reported_at`, no time zone, last in the card | D | 2 + raw time |
| *(missing)* | Is the phone connected now? That is only in Play and kiosk ("Instant changes: connected since … UTC", `streams.rs:158-172`), four cards down | D | - |
| `policy_problem` | The phone rejected or couldn't read its policy | X | dyn |
| Restrictions paused (2 variants, `:51`, `:53`) | A PIN pause is running | W | 30 / 18 |
| `relock.foreign` + steps | A foreign app may have read the PIN (design 24b) | X | dyn, list |
| `unseen_boots` | Boots the server didn't see | X | dyn |
| `relock.notes.lines` | Notes about windows that relock | W | dyn |
| `relock.blocks` + items + "Allow these apps" | Apps blocked after a pause; one-tap allow | W | dyn + 27-word per-item tail (`:78`) |
| `relock.notice` (green) | Result of the last action | W | dyn |
| **Lock the phone again now** + footer `:99` | Ends a pause or Play install mode | W | 32-word footer |
| **Pause restrictions for 2 hours** + footer `:105` | Opens the phone remotely | W | 43-word footer |
| `relock.answers` | The phone's answers to pause/relock | W | dyn |
| `parent_pin.notices` + "Typed where your child could see?" | Parent-PIN use reported by the phone | W | dyn + 9 |
| Easy parent PIN (`:120`) | Warning | S | 11 |

**Access** (`:124-158`)

| Element | Purpose | Freq | Text now |
|---|---|---|---|
| `last_access` | "Last access: …" (IP, time) | X | dyn |
| Revoked warning `:131` | The phone can't sync | X | 25 |
| Footer `:139` | What a new code does | X | 25 |
| New code · Provision by QR code | Re-enroll | X | - |
| Revoke access… (details, confirm checkbox, `:148-156`) | Cut the phone off | X | 26 |

**No parent PIN** (`:161-165`, its own card): a 61-word red warning. Freq **S**.

**Play and kiosk** (`:168-184`, `#play-kiosk`)

| Element | Purpose | Freq | Text now |
|---|---|---|---|
| `play.lines` / `play.warnings` | Play state, install mode, "Instant changes: connected since … UTC …" (`streams.rs:162-169`), "Restart the phone once: …" | X | dyn, 25-35 words each |
| Kiosk app block switch `:179` | Blocks other apps' screens | X | **60** |
| Footer `:183` | Delivery, check-in interval, Play Store pause, Play services | X | **85** |

**Launcher updates and notifications** (`:186-210`, `#kiosk-escapes`)

| Element | Purpose | Freq | Text now |
|---|---|---|---|
| `escapes.lines` / `warnings` | State of the three switches | X | dyn |
| Update fence `:197` | Hides Android's home screen during a self-update | X | 46 |
| Notification filter `:201` | Removes notifications that open blocked screens | S | 35 |
| Boot cover `:205` | Vibb logo while booting | X | 54 |
| Footer `:209` | Update window, notification listener privacy | X | **81** |

**Launcher crashes** (`:213-226`): an empty line, or crash `<details>` rows with the exception, count, build and hash,
and the raw stack trace. Freq **X**. Footer: 36 words.

**Time rules** (`:228-292`)

| Element | Purpose | Freq | Text now |
|---|---|---|---|
| Follows global/own rules + "Emergency calls always work." | Which rules apply | W | ~12 |
| 4 conditional warnings | No time rules support; incoming calls not blocked (32 words); unreadable ledger; rules error | X | 8-32 |
| "Now: **Skole is active - calls blocked**" | Current state | D | dyn |
| "Screen time 2026-10-05: 42 of 90 min (incl. 30 extra)" + "As of {time}" | Today's use | D | dyn; ISO date |
| Lift a rule: rule select + duration select + Lift | Pause a rule for 15 min-2 h | D | - |
| +15 / +30 / +60 min screen time | Extra time today | D | - |
| Lifts table (Lift, When, Status, End now) | Running and past lifts | D | dyn |
| "Edit time rules and screen time" | Goes to `/schedules?device=` | W | - |

**Calls & SMS** (`:294-304`): `calls_summary`, `call_warnings`, `call_hints` and a link to `/devices/{id}/calls`.
Freq **W**. Text: dyn; the warnings name package names ("Only emergency calls work on this phone: its phone app
(com.maker.dialer) …").

**Apps** (`:306-390`)

| Element | Purpose | Freq | Text now |
|---|---|---|---|
| Corrupt allowlist `:309` | The stored list is unreadable | X | 54 |
| Intro `:311` | How ticking, installing and uninstalling work, and the new-phone defaults | - | **94** |
| Keyboard/accessibility note `:312` | Only a ticked app's keyboard or accessibility service can be turned on | S | 36 |
| App updates only on Wi-Fi `:316` | Catalog downloads wait for Wi-Fi | S | **66** |
| `app_downloads_line` | Download state | W | dyn |
| App list: launcher row (disabled) + one switch per app, status label, "(no release yet …)" | Allow / install / uninstall | W | dyn status |
| **On the lock screen** `#lock-screen`: two paragraphs `:352-353` | What the lock's first page shows | S | 62 + 37 |
| `lock_screen.notes` (red) | Notes per app | S | dyn |
| Show the message text too | Lock screen privacy | S | 18 |
| One switch per message app + note | Which apps show on the lock | S | dyn |
| **Names and icons on the phone** `:384` + one `app_display_form` per allowed app | Rename and re-icon apps | S | 43 + 44 per form (`app_display_form.html:37`) |
| Auto-reload every 3 s while an app installs `:392-401` | Progress | - | - |

**Music** (`:403-512`, `#music`)

| Element | Purpose | Freq | Text now |
|---|---|---|---|
| Intro `:405` | Points to the library page | - | 32 |
| `music.notice` / `music.warnings` | Problems ("hasn't reported on music yet", version code) | W | dyn |
| Music app switch `:420` | Install the music app | S | 17 + status ("Music app: me.vibb.music (version code 1000)") |
| In the library on this phone: one switch per entry + detail/notice | Which entries the phone has | W | dyn |
| "This phone's library: …" | Size and sync state | X | dyn |
| Music may use mobile data `:454` | Streaming off Wi-Fi | S | 32 |
| Volume cap select | Max volume | S | 7 |
| Storytel on this phone `:465` | Gives the phone the family login | S | 28 |
| `storytel_note` | Storytel state | W | dyn |
| **Sonos speakers** `#music-sonos`: empty text `:478` (60), All rooms `:483` (56), one switch per room, "No room ticked" | Which speakers it may play on | S | 56-60 |
| `music.lines` | Library state per phone | X | dyn |
| "Music library" link | To `/music` | W | - |

**Launcher** (`:514-532`): a no-notification-access warning `:518` (**96 words, with an adb command and a component
name**), Language (S), Apps per row (S).

**Wallpapers** (`:534-564`): intro (35), a thumbnail grid of switches (S/W), and **Lock screen background** `:554`
(37 + select, S).

**Screen lock** (`:566-595`, `#screen-lock`)

| Element | Purpose | Freq | Text now |
|---|---|---|---|
| `lock_notice` | Result of a save | S | dyn |
| Paragraph `:571` | How the lock, emergency calls, the parent code and the back-off work | S | **136** |
| Paragraph `:572` | Security trade-offs: encryption, bank apps, safe mode | S | **108** |
| `lock.lines` / `lock.warnings` | Lock state | W | dyn |
| "A kid PIN is set (4 digits)" / "Set a parent PIN first" | State | S | 7-11 |
| Kid's PIN field + Save PIN (**Save button**) | Set or change | S | 13 |
| Remove the kid's PIN (own card) | Turns the lock off | X | 15 |

**Parent PIN** (`:597-636`, `#parent-pin`)

| Element | Purpose | Freq | Text now |
|---|---|---|---|
| `parent_pin.notice` | Result of a save | S | dyn |
| 3 paragraphs `:602-604` | What it does, the lockout ladder, "typed where your child could see?" | S | **87 + 85 + 27** |
| Easy-PIN warning `:606` | | S | 34 |
| "Offline override was used…" (old launchers) | | X | 10 |
| New parent PIN + hint + Remove instead (checkbox) + **Save button** | Set, change or remove | S | 10 + 12 + 36 |
| Reported by the phone: events list | PIN use and lockouts | W | dyn |

**Screen timeout** (`:638-651`): a 37-word intro, a select, and "The phone last reported: 2 minutes." Freq **S**.

**Phone hardening** (`:653-706`)

| Element | Purpose | Freq | Text now |
|---|---|---|---|
| Intro `:655` | | S | 33 |
| 9 switches: factory reset, add users, accounts, VPN, USB files, location, airplane, language, USB debugging | Android restrictions | S/X | 3-16 each |
| USB debugging footer `:692` | Recovery risk | X | 52 |
| Block safe mode + footer `:697` | | X | **109** |
| Backup to Google (on: red, 70 words `:700`; off: "Backup to Google: off") | Status | X | 70 / 4 |

**Quick Controls (swipe-left screen) + Ad/content filtering (VPN)** (`:708-736`, one form, **explicit "Save changes"**)

| Element | Purpose | Freq | Text now |
|---|---|---|---|
| Intro `:710` | Why there is a swipe-left screen | S | 57 |
| Wi-Fi, Bluetooth, Brightness, Sound (`:725`, 50 words) | What the child may change | S | 2-50 |
| VPN intro `:729` | On-device filter, link to the log | S | **72** |
| Enable ad/content filtering | Filter on or off | S | 6 |

**Remove this device…** (`:738-746`): a `<details>` confirm. Freq **X**.

### 1.2b What the server knows about a phone's connection

This is the input for the header the user asked for (§3.10):

| Signal | Where | Updated when | Kept | Today shown as |
|---|---|---|---|---|
| **Command stream open** | `AppState.command_streams` (`src/streams.rs`, `StreamState { open, since }`) | `GET /api/devices/commands/stream` opens (count up, `since` = now); its response drops (count down, `since` = now) | Memory only; empty after a server restart; phones reconnect within 5-15 s | "Instant changes: connected since 2026-10-13 08:12:04 UTC, as far as this server can tell. …" in Play and kiosk |
| **Last authenticated request** | `limits::last_access` (`src/limits.rs:267-349`, set in `security.rs:563`) | **Every** bearer request: status report, policy fetch, stream open, music, app downloads | Memory only, since the server started | "Last access: 2026-10-13 10:59:13 UTC over the tailnet." in Access |
| **Last status report** | `devices.last_seen_at` (`device_api.rs:1196`) and `device_status.reported_at` | Every sync's `POST /api/devices/status` | SQLite (UTC, no zone marker) | "Last seen …" (list), "Last reported …" (Status) |
| **Report contents** | `device_status`: `lock_reason`, `time_state_json`, ringer/DND, `app_version`, `policy_state`, `battery_optimization_exempt`, … | Same | SQLite | Spread over Status, Time rules, Play and kiosk |
| Battery level | - | **Not reported.** Only `battery_optimization_exempt` (power-save allowlist) is | - | "Battery optimisation: off for the launcher." |

How often the phone talks (`launcher/.../push/SyncSchedule.kt`):
- It syncs, and so reports status, on every nudge.
- It also syncs on a backstop alarm: every **30 min** while the stream is up, and every **15 min** while it is down
  (never below 9 min in Doze).
- The server sends a stream keepalive every **240 s** (`SSE_KEEPALIVE_SECS`). The phone treats 300 s of silence as a
  dead stream and reconnects.

A phone that vanishes without closing the connection (switched off, out of coverage) **still counts as connected**
until the server's TCP gives up on it. That can take many minutes; `streams.rs:6-8` says so. So "stream open" alone
cannot be trusted as "online".

### 1.3 Calls & SMS (`/devices/{id}/calls`, `device_calls.html`)

| Element | Purpose | Freq | Text now |
|---|---|---|---|
| "Check this phone" / "About this phone" card | `warnings` / `hints` from `calls::call_report` | W | dyn, often with package names |
| Settings: Manage calls and SMS, Calls on, SMS on (`:46`, RCS note), Message button opens (select) | Call and SMS policy | S | 4-12 each |
| Footer `:54` | Emergency numbers | S | 36 |
| Footer `:55` | Voicemail `##004#`, directory services, spoofing | S | **69** |
| Contacts list: photo, upload, remove photo, name, number, In/Out/Home, message app select, Matrix ID field, Remove | Per-contact rules | W | Legend `:64`: 65 |
| Add a contact: name, number, "+47 is used" | | W | 7 |
| **Default country code** (`:122-128`, global, **Save button**) | Applies to every phone | S | 13 |
| Auto-refresh every 5 s (`auto_refresh`) | While the phone confirms | - | - |

### 1.4 Adding and enrolling a phone

| Page | Elements | Freq | Text now |
|---|---|---|---|
| `device_add.html` | Name field, "Create device" | S | 11 |
| `enroll_code.html` | Big code, validity (UTC), "enrolled now" warning, Back | S | 67 |
| `provision_qr.html` | Intro (56), missing-checksum (35, names `LAUNCHER_SIGNATURE_CHECKSUM` and `/opt/...env`), missing-URL (35), "Two ways to scan it" (54 + 50), Wi-Fi SSID/password (44-word intro), QR + validity (UTC) | S | **302** |
| `provisioning_settings.html` (under Settings) | Server URL (+18), Tailscale auth key (+80, "tag:kid", "tsnet"), Phone language (+49, `PROVISIONING_LOCALE`), Time zone (+28, `PROVISIONING_TIME_ZONE`), Save | S | 253 |

### 1.5 Apps catalog (`/apps`, `apps_list.html`, `tracked_app_add.html`, `tracked_app_detail.html`)

| Element | Purpose | Freq | Text now |
|---|---|---|---|
| "Apps \| Music" segmented switch (`apps_list.html:14-17`, its own inline CSS) | Music's only entrance | W | - |
| App rows: name (+ "· Launcher"), latest tag or "Not synced yet", "· disabled" | Catalog | W | dyn |
| Add an app: name, package (optional, 28-word hint), GitHub/manual radio, repo, asset filter (2 hints, 26), tag prefix (39), pre-releases (29) | | X | 209 |
| App page: Details form (name, repo, filter, prefix + hints) | Edit the source | X | ~80 |
| Show on the kid's phones as (40 + `app_display_form`) | Default name and icon | S | 84 |
| Status: source, synced release, file, Android package ("from the APK, or confirmed by you - never typed in"), package candidates (41), signing key (SHA-256 hex) + 25-word note, "Forget the package and key", last checked | Provenance and pinning | X | ~150 |
| Check now + running / failed (UTC) | Sync | W | 26 |
| Include pre-releases (+29) | | X | 31 |
| Upload a new release (manual apps) | | X | - |
| Enabled | | X | 7 |
| This is the launcher app (+40) | | X | 44 |
| Remove this app… | | X | 15 |

### 1.6 Music (`/music`, `music.html`, `partials/music_card.html`, `music_entry.html`, `music_storytel.html`)

| Element | Purpose | Freq | Text now |
|---|---|---|---|
| Intro `:59` | What the library is | - | 60 |
| Add from a link: link, category (Automatic), Add, Storytel hint | New NRK/RSS entry | W | 32 + 14 |
| Own files: name, category, "Make the entry" | New own-files entry | W | 43 |
| Files no entry uses (conditional): Delete them | Clean-up after a restore | X | 37 |
| New episodes: "Check for new episodes every N hours" + 2 hints `:117-118` | Sweep interval | S | 67 + 17 |
| Library (N of 200): one `music_card` per entry: cover/tile, name, source title, "kind · category · on N phones · …", episodes line, notes, live status (`music-cards.js`), ↑ ↓, Edit, Check now / "Check again after", Offline select, per-phone lines | The library | W | dyn; 3-6 lines per entry |
| Categories: one `<details>` per category (name, icon grid, colour grid, Save, Earlier/Later, Delete) + Add a category | Category buttons on the phone | S | 29 |
| Storytel: login status, "Pick books from the shelf", Remove, e-mail + password, 2 hints; key-missing error (39, names `data/keys/music-secret.key`, `MUSIC_SECRET_KEY`) | Family login | S | 45 + 21 |
| The music app: catalog state or "Add the music app to the catalog" | One-time setup | S | 27 / 35 |
| Import from Vibb (`<details>`): file, Preview, preview table, category per section, phones, Import | One-time migration | X | 72 + table |
| **Entry page**: kind + source link; Episodes card (source title, count, notes, status, Check now, phone lines, "Times are UTC."); Settings (name, category, play order, offline count, resume); Cover (upload, use the source's); Files (own entries: upload, list, delete); On these phones (links); Delete | One entry | W | ~260 |
| **Storytel shelf**: intro (61), kids/all filter, one row per series/book with books `<details>`, category, "Add checked" | Pick books | W | 188 |

### 1.7 Time rules (`/schedules`, `schedules.html`)

| Element | Purpose | Freq | Text now |
|---|---|---|---|
| Intro `:98` | What rules are | - | 37 |
| Global rules: one `<details>` per rule (summary "Skole - school, calls off" + days) with an inline editor: name, kind (school/bedtime/other, a JS-linked calls box), calls allowed, 7 day rows from/until, apps that stay usable (`<details>`), Save, Delete (confirm) | Rules for everyone | W | 32-word hint per editor |
| Add a rule (same editor) | | S | |
| Global screen time: 7 number fields + Save (**Save button**) | Daily minutes | W | 33 |
| Per-device rules: a device select (GET, `onchange`, no `<noscript>`) | Choose a phone | W | 14 |
| {Device}: own rules switch + screen time + Save; {Device}'s own rules (same editors) | Override | W | |

Lifts and extra time are **not** on this page; they are on the device page.

### 1.8 Locate (`/devices/locate`, `device_locate.html`)

| Element | Purpose | Freq | Text now |
|---|---|---|---|
| Intro `:13` | Delivery and expiry rules | - | **70** |
| Device select (`onchange` → `location.href`, **no-JS: dead**) | | W | |
| Location: last fix, the phone's last answer, "Waiting…", Update location now | Where is it | W | dyn |
| Location off warning `:51` | | X | 27 |
| When the phone takes a fix (Off / Only when asked / Regularly) + Every N | Location policy | S | 12 + 18 |
| Keep the location history for N days | Retention | S | 25 |
| Map (Leaflet, OSM tiles) | | W | - |
| Ring · Stop ringing · Lock · Wipe… (type the name) | Remote commands | X (lost phone) | wipe 36 |
| Wipe-not-ready warning `:105` | | X | 39 |
| Lock footer `:107` | What Lock does | X | 44 |
| Recent commands table (Command, Requested UTC + Cancel, Status) + cancel notice | Delivery state | X | dyn (`DeviceCommand::status_line`) |

### 1.9 DNS/Filters (`/dns`, `dns_filter.html`) and Blocked activity (`/dns/log`, `dns_log.html`)

| Element | Purpose | Freq | Text now |
|---|---|---|---|
| Intro `:12` | "Blocking now happens on-device…" | - | 37 |
| Upstream resolver (Cloudflare / Quad9) | Where unblocked lookups go | X | 9 |
| Blocklists: switch + status line each; Add a custom blocklist URL… | Shared lists | S | dyn |
| Custom blocked domains / Custom allowed domains: list + Remove + add field | Shared domain rules | W | 12 |
| Per-device overrides: device select (JS only), blocklist switches + "Use default", blocked/allowed just on {device}, log link | Per-phone exceptions | X | ~60 |
| Blocked activity: intro (40), device select (JS only), log switch (+42), table Domain/Category/When | Per-phone log | W | 120 |

### 1.10 Settings (`/settings`, `settings_hub.html`) and its pages

| Page | Elements | Freq | Text now |
|---|---|---|---|
| Hub | 6 rows with emoji icons and one-line subtitles: Backups, Software updates, Security, Account, Wallpapers, Provisioning; Sign out | S | 61 |
| Backups | Create now, Upload… (details), table (Download, Delete…, Restore… with type-the-filename), Scheduled backups (how often, day of week, day of month, time, keep N, external copy, continuous mirror, Save), External drive (Format with type-the-size) | S/X | 317 |
| Software updates | App version (+ helper-script version, reinstall hints 36-40 words), Update now…, Restart app…, OS (check / install / reboot), Tailscale, Automatic app updates (schedule form), Automatic OS/Tailscale checks (schedule form); a Docker variant | X | 488 |
| Security log | Banned IPs table + Unban; Recent activity table (When, Event, Username, IP, Detail) + 54-word intro; paging | X | 86 + raw event ids |
| Account | Change password; Two-factor (Reset… with 48-word warning; CLI recovery line with `kid_phone_server reset-2fa`); Sessions: Sign out everywhere | S/X | 147 |
| Wallpapers | Add a photo (79-word intro, name, "Also on the lock screen", file); All wallpapers grid (per photo: "Also on the lock screen", Delete) | S | 152 |

### 1.11 Sign-in and onboarding

`login.html` (14 words), `verify_2fa.html` (14), `setup_2fa.html` (33, QR + secret key), `change_password.html` (25).
They are short and clear already; only the brand changes.

### 1.12 Every warning and banner, by kind

| Kind | Examples (file:line or source) | Today | Proposed (§3.0) |
|---|---|---|---|
| **Alarm**: the phone is less protected than you think | Restrictions paused (`device_detail.html:51`); foreign app may have read the PIN (`:57`); Do Not Disturb on; Backup to Google on (`:700`); no parent PIN (`:163`); easy PIN (`:120`, `:606`); access revoked (`:131`); policy problem (`:47`); unseen boots | red box | Red row in **Attention** on the summary, linking to the fix; the full text on the sub-page |
| **Needs action**: something waits for the parent | Blocked apps + "Allow these apps" (`:73-94`); restart the phone once (`play.warnings`); wipe not ready (`device_locate.html:105`); no notification access (`:518`); helper scripts outdated (`updates.html:39`); Storytel login stale (`music.html:211`) | red box | Orange row in **Attention**, with a button |
| **State**: a fact, not a problem | "Backup to Google: off"; "A kid PIN is set (4 digits)"; "A parent PIN is set."; "Instant changes: connected since …"; "No crashes reported in the last 30 days."; "Sound at the last sync: …"; app status labels | grey text, sometimes red | Value on the right of a row, or a badge (green/grey dot) |
| **Result**: what the last save did | `relock.notice`, `lock_notice`, `parent_pin.notice`, music import summary, backup success | green or red box | A one-line flash at the top of the sub-page that was saved, gone on the next load |
| **Form error** | Field validation (add link, category name, PIN pattern) | red box above the form | Red text under the field (`aria-describedby`), unchanged logic |
| **Server problem** | "Couldn't read the time rules - check the server log." (`:241`); "The music settings couldn't be read - check the server log." (`:509`); corrupt allowlist (`:309`) | red box | A single "Something went wrong" row with the detail behind "i". Rare, so the wording matters less than the shape |

---

## 2. Problems

### 2.1 The wordiest texts (top 15)

| # | Words | file:line | Opening | What is wrong |
|---|---|---|---|---|
| 1 | 136 | `device_detail.html:571` | "Handy's lock replaces Android's screen lock: when the screen turns off…" | A manual: the back-off ladder, the parent code, emergency calls, "Lås opp" |
| 2 | 109 | `device_detail.html:697` | "Off by default, and switched on when you set the kid's PIN…" | Recovery paths for a broken launcher, under one switch |
| 3 | 108 | `device_detail.html:572` | "It keeps people around him out, but the phone no longer has an Android code…" | A threat model; also gendered ("him") |
| 4 | 96 | `device_detail.html:518` | "The launcher can't read notifications…" | An `adb shell cmd notification allow_listener me.vibb.launcher/com.kidslauncher.mdm.badges.BadgeListenerService` command |
| 5 | 94 | `device_detail.html:311` | "Every app currently on this device, plus anything from the Apps catalog…" | Explains a switch list that explains itself |
| 6 | 87 | `device_detail.html:602` | "The parent PIN works on the phone even when it can't reach this server…" | Four features in one paragraph |
| 7 | 85 | `device_detail.html:603` | "After 5 wrong tries the phone stops accepting the parent PIN for 15 minutes…" | The lockout algorithm |
| 8 | 85 | `device_detail.html:183` | "Changes reach the phone at once over its own connection to this server…" | Transport details (check-in intervals) under a kiosk switch |
| 9 | 81 | `device_detail.html:209` | "Launcher updates are downloaded at once and installed at night (02:00-05:00)…" | Install window and listener privacy |
| 10 | 80 | `provisioning_settings.html:29` | "A one-time, tagged (tag:kid), pre-approved auth key…" | Tailscale ACL detail and "tsnet" |
| 11 | 79 | `wallpapers.html:27` | "A photo (JPEG, PNG or WebP, up to 10 MB) is turned upright, cut to the shape…" | Image-processing steps |
| 12 | 72 | `device_detail.html:729` | "The launcher runs its own on-device VPN to filter ads/content locally…" | Architecture talk ("VPN", "syncs normally") |
| 13 | 72 | `music.html:252` | "Bring a Vibb box's library over in one go… `scp <pi>:/etc/vibb/library.json .`" | A shell command for a one-off import |
| 14 | 70 | `device_locate.html:13` | "Locate, ring, lock, or wipe a device. A phone that's online gets a command within seconds…" | Expiry rules as page intro |
| 15 | 70 | `device_detail.html:700` | "Backup to Google is on: Android may copy…" | Asks the parent to "report it" |

Runtime texts make it worse:
- "Instant changes: connected since 2026-10-13 08:12:04 UTC, as far as this server can tell. The phone also checks in
  by itself every 30 minutes." (`streams.rs:162`)
- The calls report names package names ("its phone app (com.maker.dialer)").
- The music card says "Music app: me.vibb.music (version code 1000)".

### 2.2 Debug-like texts (implementation leaking into the UI)

| Pattern | Examples | Fix |
|---|---|---|
| Shell, adb, CLI | `device_detail.html:518` (adb), `music.html:252` (scp), `account.html:55` (`kid_phone_server reset-2fa`), `updates.html:25` (`docker compose pull`), `updates.html:39/44` (reinstall hint) | Move behind "i" or into a "Help" sub-page; keep the command copyable there |
| Package and component names | Calls warnings, music app status, `schedules.html:45` (package under every exempt app), tracked app Status card | Show the app's name; the package goes behind "i" (Advanced) |
| Env vars and paths | `provision_qr.html:16` (`LAUNCHER_SIGNATURE_CHECKSUM`, `/opt/kid-phone-server/.env`), `music.html:208` (`data/keys/music-secret.key`, `MUSIC_SECRET_KEY`), `provisioning_settings.html:33/37` (`PROVISIONING_LOCALE`) | "Server setup needed" + "i" with the detail |
| Internal words | "kiosk", "MDM", "launcher", "policy", "sync", "token", "enrol(l)", "tsnet", "VPN", "upstream resolver", "tracked app", "release tag prefix", "asset filename filter", "watcher", "system helper scripts", "check-in" | Plain words (§4.3 glossary); expert words only inside Advanced |
| Raw time | "Last seen {raw DB stamp}" (`devices.rs:47`), every "… UTC" (enroll code, QR, commands, sync failures, "Times are UTC." on the entry page), "Screen time 2026-10-05:" | Relative or local time ("2 min ago", "Today 14:05"); see Q5 |
| Hashes | Crash hashes, the signing key SHA-256, backup filenames as confirm strings | Advanced only |
| Design-doc language | "parent-code opening on the time-lock screen" (`:99`), "accessibility services stay limited to checked apps" (`:105`), "the phone uses the new PIN as soon as it checks in" | Rewrite as an outcome |

### 2.2b Runtime texts that need fixing in Rust, not only in templates

The template rewrite alone won't make the pages quiet. These strings are built in Rust and need the same rewrite.
The ones marked † are 40 words or more.

| Where | What the parent sees | Problem |
|---|---|---|
| `calls.rs` `call_report` | "Phone app role not active: the launcher isn't the phone's default phone app, so … Open the launcher's home screen on the phone and accept the "default phone app" prompt." † (47-55 words) | Longest calls warning; "role" jargon |
| `calls.rs` | "Call-redirection role not active: … Run `adb shell cmd role add-role-holder android.app.role.CALL_REDIRECTION <launcher package>`."; the call-screening hint, same pattern † | adb with a placeholder |
| `calls.rs` | "Only emergency calls work on this phone: its phone app (com.maker.dialer) isn't allowed in Apps …"; "The phone hides its Messages app (com.maker.sms) …" | Package names |
| `devices.rs:913-923` | "Battery optimisation: on - … (or, with USB debugging allowed: adb shell dumpsys deviceidle whitelist +me.vibb.launcher)." † | adb + package |
| `kid_lock.rs:227-250` | "Remove the Android screen lock … adb shell locksettings clear --old <PIN>." † (50 words); the VoIP line "adb shell appops set {package} USE_FULL_SCREEN_INTENT allow" | adb |
| `kiosk_escapes.rs` | "Grant it with adb before enrolling (see the emulator/provisioning notes)."; "Removed since the last report: {package} ({channel}) - 3 notifications"; "Launcher update launcher-v0.32.0@123 is downloaded …" | adb, package and channel ids, raw tag |
| `window_relock.rs:628-743` | "Steps that failed on the phone: credential, signer_check, apply, …"; "Blocked: com.x.y (signing key 3f2a1b0c…9d8e7f60) …" † | Raw step codes, key hashes |
| `window_relock.rs:485-545` | The foreign-app alarm, ~45 words + package names + a 4-step list | Right content, too long for the summary: title + "What to do ›" |
| `boot_witness.rs:29-60` | "The phone started 2 times without the launcher running, last noticed 2026-10-12 21:04 UTC (14 h ago). That happens in safe mode, …" † (55-65 words) | UTC + length |
| `devices.rs:994-1000` | "Locked (BEDTIME)" (a raw enum when no rule name is known) | Raw code |
| `devices.rs:961-981` | Lift status "Waiting for the phone - server expiry 2026-10-13 14:05:00 (UTC)", "Ended early {raw}" | Internal timing |
| `devices.rs:1621-1641` | Policy problems: "Check this server's log ("failed to build device policy")" | Log string |
| Fallbacks | "The phone reported a policy problem: {code}", "The lock is off on the phone: {code}", "The last update fence ended: {code}", "Storytel on the phone: {code}", "The launcher can't reach the music app (unbound)" | Raw codes reach the page |
| `music.rs:3521` | "Music app: me.vibb.music (version code 1000)"; "This phone's library: 6 entries · 12.3 kB" | Package, version code, JSON size |
| `models.rs:608-718` | Every command status carries a full UTC stamp ("Delivered 2026-10-13 10:58:31 UTC, not confirmed - offered again …"); the command column shows raw `stop_ring`, `start_pause`, `end_windows` | Raw ids + UTC |
| `security.html:71` | The Event column shows raw ids (`device_command_cancelled`, `lock_wallpaper_changed`, `music_entry_added`, …) | Needs a label map |
| `app_display_form.html:34`, `app_display.rs:59-74` | Colour choices labelled with raw keys ("peach", "sky", "night"); summary "Chat, with the chat icon on peach" | Keys as labels (mild) |
| `app_downloads.rs:116-217` | "Waiting - the phone is roaming since 6 Oct · 5 MB so far"; "refused - no package and signing key on the server yet" | Grammar; jargon |
| `photos.rs:167-180` | "That photo is too detailed to send to a phone (over 3 MB as a wallpaper)" shown for contact photos and music covers | Wrong context |
| `tracked_apps.rs` | Sync failures pass raw reqwest/sqlx/regex errors and file paths ("can't write {path}: {e}", "HTTP status client error (404 Not Found) for url (…)") | Raw errors; keep behind ⓘ |
| `music_entry.html:29`, `music.rs` | Storytel target shown as `storytel:series:<id>`; Sonos rooms without a name as `RINCON_…`; lock-screen rows for missing apps as raw package names | Raw ids |
| `backups.rs:1016`, `:1044` | "That doesn't look like a Kids Device MDM backup (couldn't find kidphone.db inside the zip)."; "Uploaded … as backup-20261013-105901-uploaded-1728816000123456789.zip" | Brand, file internals |
| `auth.rs:828-850`, `admin_auth.rs:271-302` | Sign-in warnings with UTC lock time and up to 5 raw IPs | Keep the IPs, behind ⓘ |

### 2.3 Structural problems

1. **One page does everything.** The device page has 21 cards: daily, setup and recovery controls side by side. A
   parent who wants "+30 min" scrolls past Status, Access, Play and kiosk, Launcher updates and Crashes first.
2. **The top card answers the wrong question.** "Status" leads with the time lock (even "none", with a 26-word
   footer), then the app version and the sound. It ends with "Last reported 2026-10-13 10:59:13": an exact,
   zone-less timestamp the parent must subtract from the clock.
   - Whether the phone is connected right now is in a different card ("Instant changes", Play and kiosk).
   - The time of the last request is in a third card ("Last access", Access).
   - The list page shows only "Last seen {raw timestamp}".
3. **Daily actions are split up.**
   - Time state and lifts are on the device page.
   - Rule editing is on `/schedules?device=`.
   - Remote lock is on Locate.
   - "Lock the phone again now" and "Pause for 2 hours" are in Status.
4. **The same thing is in two places.**
   - **Enrolment**: "Not enrolled yet" and "Access" repeat the same New code / QR buttons and notices (`:13-32` vs
     `:124-158`).
   - **The music app**: it is a switch in the Apps list and also a switch in the Music card (same form, `apps/toggle`).
   - **The parent PIN**: warnings appear in Status (`:116-121`), in a separate "No parent PIN" card (`:161`), and in
     the Parent PIN card (`:605-610`).
   - **The lock-screen picture**: per photo on `/wallpapers` ("Also on the lock screen", `wallpapers.html:31,55`) and
     per phone ("Lock screen background", `device_detail.html:552-563`). It is unclear to a parent how the two
     combine (Q1).
   - **Device pickers**: Locate, DNS per-device overrides, DNS log and Time rules each have their own "Choose a
     device…" select. Every one loses the phone you came from.
5. **Some controls are on the wrong page.**
   - The **default country code** (global) is on a per-phone page (`device_calls.html:122`).
   - **Location mode and history** (per phone, set once) are on the Locate tab, not with the phone.
   - **The blocked-domain log switch** (per phone) is on the DNS log page; **the filter on/off switch** (per phone) is
     in the device page's Quick Controls form.
   - **Wallpapers** (photo library) are under Settings, but they are content, like music.
   - **Provisioning** (server URL, Tailscale key) is under Settings, but you only need it when adding a phone.
   - **Crashes** and **Access** are expert sections sitting on top of the daily page.
6. **Two ways to save.** Almost everything saves on change, but these have explicit Save buttons:
   - Quick Controls + VPN (`/devices/{id}/policy`);
   - the PINs;
   - the time-rule editors;
   - screen time;
   - the country code;
   - the app details;
   - provisioning;
   - schedules on Backups and Updates.

   And "Each choice saves itself immediately" is repeated in 9 places. The Quick Controls + VPN form looks like the
   other switches, so a parent who toggles one and leaves loses the change.
7. **Warnings have no levels.** Six kinds of message (§1.12) share one red box. Some red boxes are only information
   ("Ticked under Apps: it shows again after…"). Real alarms don't stand out.
8. **The device list says nothing.** "Last seen {timestamp}" is all it shows. Paused, locked by Skole, screen time,
   and a warning count are only visible one level down.
9. **Navigation.**
   - Six tabs, and the labels are 11px.
   - "Locate" is a device picker.
   - "DNS/Filters" is rarely touched.
   - Music is hidden behind a sub-switch in Apps, with copy-pasted inline CSS in two templates
     (`apps_list.html:5-9`, `music.html:6-8`).
   - The Settings hub uses emoji icons.
10. **No-JS gaps.**
   - The device selects on Locate, DNS and DNS log navigate by `onchange` only (no `<noscript>`).
   - The Time rules device picker is a GET form with no submit button.
   - The active nav tab needs JS.
   - Deletes that use `onsubmit="return confirm(…)"` happen without a question when JS is off.
11. **Accessibility.**
    - Auto-submitting `<select>`s fire on every arrow key in some browsers.
    - Several `<summary>` elements are styled as buttons.
    - The nav icons have no `aria-hidden`.
    - Long red text on a pink background (`.error`) is the main reading surface.
    - Tables (security log, commands, lifts) scroll sideways on a phone.
    - Inline styles override focus and size in places (`style="font-size:12px"`, `:494`).
12. **The brand is inconsistent.** "Kids Device MDM" (header, `<title>`, manifest `name`), "Kids MDM" (short name),
    "handy" (lock text "Handy's lock"), "Vibb" (boot cover, music app, phone). See Q6.

---

## 3. Proposed information architecture

### 3.0 Patterns used everywhere (the iOS Settings vocabulary)

- **Grouped list.**
  - Sections of rows on a rounded surface ("inset grouped").
  - A small grey **section header** in capitals is optional.
  - A one-line grey **footer** under the group at most.
- **Row types:**
  - *navigation*: label · value · chevron, leading to a sub-page;
  - *switch*: label + switch, which saves itself;
  - *value*: label · read-only value;
  - *action*: a blue text button row; destructive actions in red, at the bottom of their page;
  - *picker*: label · current value. A native `<select>` sits under the row; without JS it shows with a Save button.
- **Badges.** A small coloured dot or pill on a row or a device: green "Online", grey "Not seen for 3 h", orange
  "Paused", red "2 issues". State lines become badges or row values instead of sentences.
- **"i" disclosure.**
  - A small ⓘ button at the end of a row or a footer. It is a native `<details>`/`<summary>`, so it works without JS.
  - It opens an inline panel of at most 80 words under the row.
  - Longer reference text (lockout ladder, recovery paths, Tailscale key how-to) goes to a **Help** sub-page that the
    panel links to.
- **Attention group.** On the summary pages, alarms and needs-action messages from §1.12 come first, as red or orange
  rows: one line, a chevron to the fix.
- **Flash.** After a save that has a visible result (PIN saved, code made, import done), the target page shows one
  line at the top, as today's notices do.
- **Sub-page header.**
  - A back link ("‹ Anna") at the top left.
  - The page title, large, under it.
  - No other header chrome; the brand header goes away on inner pages.
- **Switches** are `<input type="checkbox" role="switch">` styled as iOS switches, inside a `<label>`.
- **The bottom bar** has 5 items, with labels at least 12px and active state from `aria-current="page"`. The server
  sets `aria-current` when it renders, so no JS is needed.

### 3.1 Tab bar and URLs

| Tab | Landing route | Highlights for (server-side) | Icon |
|---|---|---|---|
| **Devices** | `/devices` | `/devices…` except `/devices/locate…` | phone |
| **Apps** | `/apps` | `/apps…` | grid |
| **Locate** | `/devices/locate` | `/devices/locate…` (with or without `?device=`) | map pin |
| **Music** | `/music` | `/music…` | note |
| **Settings** | `/settings` (the hub, rebuilt) | `/settings…`, `/schedules…`, `/dns…`, `/wallpapers`, `/account`, `/security`, `/backups`, `/updates` | gear |

Rules for routes:
- Existing POST routes stay as they are. Only the redirect targets change (§5.1).
- New GET routes are added for the device sub-pages (§3.2).
- `/` still redirects to `/devices`.
- The tab is chosen on the server, from the request path, with the same prefixes as the table.
- The device sub-pages under `/devices/{id}/…` stay in the **Devices** tab, including `/devices/{id}/time` and
  `/devices/{id}/filter`, even though their global counterparts live under Settings.
- `/devices/locate` must be matched **before** `/devices/{id}`. Today the router has the literal route at
  `main.rs:840`, and the JS regex excludes it (`^/devices(?!/locate)`). The server-side matcher needs the same rule.
- `/schedules` keeps its URL. Only its tab changes, from its own tab to Settings. No `/time` alias and no `/more`
  route are needed.

### 3.2 Devices

**Devices list (`/devices`).** One row per phone:
- an initial avatar with the connection dot (§3.10);
- the **name**;
- a **status line** built from what the server already knows: "Online · Skole until 14:30" or "Offline · seen 3 h
  ago" (the lock part only while locking);
- a thin screen-time bar;
- a red badge with the number of Attention items.

Below the rows:
- An **"Add phone"** row.
- The intro paragraph moves into the Add flow.
- The live password and sign-in warnings stay at the top, as now.

**Device summary (`/devices/{id}`).** What a parent opens daily. Top to bottom:

1. **Header**, answering "is it connected, and since when" first (the user's feedback):
   - **Line 1:** the name, with the connection state as a coloured dot and word: **Online** (green), **Away**
     (orange) or **Offline** (grey). The rules are in §3.10.
   - **Line 2:** the relative time, "Seen just now" / "Seen 3 min ago" / "Seen 2 h ago" / "Seen yesterday 21:40".
     While Away, it adds "· changes may take up to 15 min". While Offline: "· changes wait until it's back".
   - **Line 3, only while something is locking:** a badge with the rule and its end, "Bedtime until 07:00",
     "Skole until 14:30", or "Screen time used up". Calls blocked adds "· calls off". "No rule active" shows
     **nothing**.
     - When the phone is Offline, the badge says "at last report" so a stale lock doesn't read as now.
     - The end time comes from the rule's own day window on the server (the report has the rule name, not its end).
       That is a small handler addition; until it lands, the badge shows the rule name alone.
   - **Line 4:** today's screen time as a thin bar, "42 of 90 min". It is hidden when there is no limit and nothing
     is reported.
   - **Not in the header:** sound, app version, exact timestamps, policy state, "Instant changes". They move to
     **About This Phone** (below). Do Not Disturb is the exception: it becomes an Attention row, because calls won't
     ring.
   - **Battery:** the phone doesn't report a battery level today, only whether it is exempt from battery
     optimisation. A battery percentage needs a launcher and API change, which is out of scope here. The header
     leaves room for a battery value if one is added later.
2. **Attention.** Only the rows that apply. Each row takes the first sentence of today's text and links to the
   sub-page that fixes it.
   - Alarms: restrictions paused, possible PIN leak, Do Not Disturb on, Backup to Google on, no parent PIN, easy
     parent PIN, access revoked, phone can't apply its settings, unseen restarts.
   - Needs action: blocked apps waiting, restart once, no notification access, calls problems from `call_report`.
3. **Quick actions.** A row of large buttons that post to today's routes:
   - **+15 min**, **+30 min**, **+1 h** (`lifts`, target budget);
   - **Pause a rule…** (`lifts`, target rule; opens a small sheet with the rule and duration selects);
   - **Unlock for 2 h** (`start-pause`), or **Lock again now** (`end-windows`) while a pause runs;
   - **Allow these apps** while blocked apps wait.
4. **Today.** Running and recent lifts with **End now**, the answers from the phone (`relock.answers`), and
   "Rules: Everyone's ›" or "Own rules ›".
5. **Sections.** Navigation rows, each with a value that sums it up:

| Row | Value example | Sub-page | Holds (from today's page) |
|---|---|---|---|
| Calls & Contacts | "8 contacts · SMS on" | `/devices/{id}/calls` (exists) | The Calls & SMS page, minus the country code (→ Settings › Phone Setup) |
| Apps | "14 allowed · 1 installing" | `/devices/{id}/apps` (new) | Allowed apps list (switches + status), App updates on Wi-Fi only, Names and icons (each app row → its own name/icon sheet), keyboard and accessibility note behind ⓘ |
| Music | "6 in library · Storytel on" | `/devices/{id}/music` (new) | The Music card: music app, library ticks, mobile data, volume cap, Storytel, Sonos (its own sub-page when there are rooms) |
| Time | "Everyone's rules · 90 min" | `/devices/{id}/time` (new) | Rule state, lifts table, extra time, own-rules switch, this phone's own rules and screen time (from `/schedules?device=`) |
| Lock Screen & PINs | "Child PIN on · 1 min" | `/devices/{id}/lock` (new) | Child PIN, parent PIN (+ reported events), screen timeout, lock-screen notifications (text switch + apps), lock-screen background |
| Home Screen | "4 per row · Norsk" | `/devices/{id}/home` (new) | Language, apps per row, wallpapers, Quick controls (swipe-left screen) |
| Location | "Seen 10 min ago" | **Not a sub-page.** It links to the Locate tab: `/devices/locate?device={id}` (§3.6) | Nothing moves here; the row is a shortcut |
| Web Filter | "On · log off" | `/devices/{id}/filter` (new) | Filter on/off, this phone's blocklist overrides and domains (from `/dns?device=`), blocked log switch + log (from `/dns/log?device=`) |
| About This Phone | "Vibb 0.23.6" | `/devices/{id}/about` (new) | See below |
| Advanced | - | `/devices/{id}/advanced` (new) | See below |

**About This Phone (`/devices/{id}/about`)** is read-only, like iOS Settings › General › About. It holds the facts
the old Status card led with. Value rows:

| Group | Row | Value (example) | From |
|---|---|---|---|
| Connection | Status | "Online since 08:12" | `streams.rs` `StreamState` |
| | Last request | "10:59 today, over the tailnet" (ⓘ: addresses outside the tailnet) | `limits::last_access` |
| | Last report | "10:59 today (3 min ago)" | `device_status.reported_at` |
| | Instant changes | "Connected" / "Not connected since 09:40" | `instant_changes_line`, rewritten |
| | Battery optimisation | "Off" (good) / "On" + ⓘ with the fix | `battery_optimization_exempt` |
| Sound | Ringer | "On" / "Vibrate" / "Silent" | `sound_lines` |
| | Do Not Disturb | "Off" / "Priority only" | `sound_lines` |
| Software | Vibb | "0.23.6-debug" | `app_version` |
| | Music app | "Installed · 0.3.0" (ⓘ: package, version code) | music lines |
| | Settings from the server | "Applied" / the policy problem's first sentence | `policy_state` |
| Diagnostics | Crash reports | "None in 30 days" ›, or "2 kinds" › (`<details>` per crash, unchanged) | `device_crashes` |
| | Time lock (raw) | "None" / "Bedtime" | `lock_reason`, for checking what the header says |

All times are local (Q5), with the exact date and time in a `title` tooltip and behind ⓘ, never as the visible
value.

**Advanced (`/devices/{id}/advanced`).** Expert and set-once controls, in groups:
- **Restrictions**: the 9 hardening switches, Block safe mode, "Backup to Google: Off" as a value row.
- **App screens**: Kiosk app block (today "Play and kiosk"), the Play state lines.
- **Updates & notifications**: Update fence, Notification filter, Boot cover.
- **Connection**: New code, Set up with QR code, **Disconnect phone…** (today "Revoke access").
- **Remove phone…** at the bottom, in red.

**A phone that hasn't enrolled** shows only the header, a "Connect this phone" group (New code / Set up with QR code,
with today's notices) and Remove. The sub-page rows show greyed out with "After setup".

### 3.3 Apps

The Apps tab is the **catalog**, plus quick access to each phone's allowed list:

- **On phones.** One row per phone: "Anna · 14 allowed ›", leading to `/devices/{id}/apps`. This is the daily
  "allow an app" path, one tap shorter than today.
- **Catalog.** One row per catalog app:
  - the icon tile (`app_display`) and name;
  - the value: version + "on 2 phones";
  - a badge: "Update failed" (red), "No release yet" (grey), "Off" (grey).
- **Add app** row.

**The app page (`/apps/tracked/{id}`):**
- **Top:** name, version, "Last checked 2 h ago", **Check now** (with the running/failed state in one line).
- **Show on phones as:** a navigation row to the name/icon sheet (the `app_display_form` partial, unchanged logic).
- **On phones:** a read-only list of the phones that have it ticked, each linking to that phone's Apps page. The data
  is there (`device_tracked_apps`); this list is new.
- **Source & signing** (a navigation row → sub-section or `<details>`): repo, file filter, tag prefix, include
  pre-releases, enabled, package (+ candidates), signing key, Forget, "This is the launcher app", Upload (manual apps).
- **Remove app…** in red.

**Add app:** a short form (name, source GitHub/upload, repo). The filter, prefix, pre-releases and package go under
"More options" (`<details>`).

### 3.4 Music

`/music` loses the Apps|Music switch. Top to bottom:

1. **Library** (the main list). The cards are simplified to a list row:
   - cover · name · "Podcast · Podkast · 3 phones";
   - the live status as one line, coloured, still swapped by `music-cards.js`;
   - row actions in a compact trailing area: Check now and the Offline picker. Reordering moves into an **Edit order**
     mode (a "Edit" link in the header that shows ↑ ↓ on every row; plain forms, no drag).
2. **"+ Add" group**, in four rows:
   - **Podcast or NRK link** (form inline or sheet);
   - **Own files**;
   - **Storytel shelf ›** (`/music/storytel`);
   - **Import from Vibb ›** (moved to `/music/import`, a sub-page: one-off use).
3. **On phones.** One row per phone: "Anna · 6 of 9 · Storytel on ›", leading to `/devices/{id}/music`.
4. **Settings** (a navigation row → `/music/settings`, new): check for new episodes every N hours (the hints behind ⓘ),
   Storytel login, the music app catalog row, "Files no entry uses" (only when there are some).
5. **Categories ›** (`/music/categories`, new): the category list and editor, today's `<details>` blocks unchanged
   inside.

The **entry page** keeps its cards, retitled and reordered: Cover + Name → Episodes (status, Check now) → Settings
(category, order, offline, resume) → Files → On phones → Delete. "Times are UTC." goes away with relative times (Q5).

The **Storytel shelf** keeps its shape. Its intro shrinks to one line, with the rest behind ⓘ.

### 3.5 Settings › Time Rules

Time Rules is not a tab. It is the **first content row in Settings** ("Time Rules · 2 rules ›"). It opens `/schedules`,
which keeps its URL and becomes a Screen-Time-like sub-page, with the back link "‹ Settings":

1. **Everyone** (the global scope):
   - **Rules**: one navigation row per rule, "Skole · Mon-Fri 08:15-14:30 · calls off ›", plus **Add rule**. The rule
     editor moves to its own page (`/schedules/rules/{id}`, GET; the POST routes stay). Its 7 day rows stay; a "Same
     times every weekday" helper is out of scope.
   - **Screen time**: one navigation row, "Mon-Fri 90 min · Sat-Sun 120 min ›", leading to the 7 fields + Save.
2. **Phones.** One row per phone:
   - "Anna · Everyone's rules ›" / "Emil · Own rules ›";
   - each leads to `/devices/{id}/time`, the same sub-page as from the device summary;
   - the sub-page holds the lifts, extra time, the own-rules switch, its own screen time and its own rules.
3. A one-line footer: "Emergency calls always work."

The device picker select goes away; each phone is a link.

**Per-phone time stays on the device page.** The daily actions (add time, pause a rule, end a lift) are on the
device summary. The full per-phone view is the device's **Time** sub-page (`/devices/{id}/time`). It holds:
- the lifts;
- extra time;
- the own-rules switch;
- this phone's own screen time and rules.

The Phones rows above are links into that sub-page; they open in the Devices tab. Settings › Time Rules owns only the
rules and screen time that apply to everyone.

### 3.6 Settings and the Locate tab

**Settings (`/settings`)** replaces the old hub. It holds everything the first draft's More tab held, minus Locate (now a tab), plus Time
Rules near the top. It is a
grouped list with outline icons on tinted squares, not emoji:

| Group | Row | Value (example) | Goes to |
|---|---|---|---|
| **Rules** | Time Rules | "2 rules · 90 min" | `/schedules` (§3.5) |
| | Web Filter | "On · 3 lists" | `/dns`: shared blocklists, blocked and allowed domains, the DNS server. The per-phone parts are on each phone's Web Filter page |
| | Blocked Activity | "Log on for 1 phone" | `/dns/log`: a list of phones (log on/off) › each phone's Web Filter page |
| **Content** | Wallpapers | "8 · 2 photos" | `/wallpapers` |
| **Server** | Account | "admin" | `/account` (password, two-factor, sign out everywhere) |
| | Security Log | - | `/security` |
| | Backups | "Last: today 03:00" | `/backups` |
| | Software Updates | "Update available" (badge) | `/updates` |
| | Phone Setup | - | `/settings/provisioning` (server URL, Tailscale key, language, time zone), plus the **default country code** (moved from the calls page) |
| | About | "0.24.0" | Server version (from `/updates`), links to the project |
| | **Sign Out** | - | `POST /logout` |

`/settings` keeps its URL, so bookmarks still work.

**Locate (`/devices/locate`)** is a tab of its own. It is the one place for a phone's location and its remote
commands. There is no separate device sub-page for location.

- **Phone switcher.** A segmented control or a row of links at the top, one per phone: "Anna · Emil".
  - These are plain links to `?device={id}`. Today's `onchange` select needs JS and goes away.
  - With one phone, that phone is shown at once.
  - With several and no `?device=`, the page lists the phones with "Seen 10 min ago" ›.
  - The device summary's Location row opens `/devices/locate?device={id}`, which switches to the Locate tab. That
    tab switch is deliberate: location is one tap from both places.
- **Map** (Leaflet, unchanged), with the last fix "10 min ago · ±12 m" under it, and **Update Location**.
- **Actions**: Ring · Stop Ringing · Lock · Erase Phone… (the type-the-name confirm stays). One footer: "An offline
  phone gets Ring and Locate within an hour, Lock and Erase whenever it's back."
- **Location settings**, as a navigation row "Location · Only when asked · 7 days ›". It opens a section on the same
  page or a `<details>` with the mode, interval and history. These are set once, so they go below the actions.
- **Recent commands** as stacked rows: "Lock · today 10:58 · Done", Cancel on a waiting one. Raw command ids are
  replaced by labels (§2.2b).
- The wipe-not-ready and location-off warnings stay, as orange rows above the actions.

### 3.7 Login, setup and enrolment

- Sign-in, two-factor and the forced password change keep their single-card layout, with the new brand and type.
- **Add phone** becomes a 3-step flow on the pages that exist today:
  1. **Name** (`/devices/new`).
  2. **How to set it up**: two large choices, "QR code (new or reset phone)" and "Code (phone already set up)".
     Today's "Two ways to scan it" text becomes these two choices, with the GrapheneOS and adb detail behind ⓘ.
  3. **The QR or the code** (`/devices/{id}/provision`, `/devices/{id}/regenerate-code` → the code page).
- Missing server setup (no checksum, no server URL) shows as one red row, "Server setup needed ›", with the env
  var detail behind ⓘ.

### 3.8 Badges and status: what the server already has

Every badge and status value above comes from data that `view_device` and `list_devices` already load:
- `latest_status`;
- `time.now_line` / `screen_line`;
- `restrictions_paused`, `relock`, `parent_pin`;
- `play`, `escapes`;
- `call_warnings`, `music.warnings`;
- `streams.rs` for online.

The list page needs a lighter version of the same reads per phone. That is a handler change (one query per phone),
listed in §5.4.

### 3.9 Where every current control goes

Nothing is dropped. "→" names the new home; ⓘ means the text moves behind the disclosure.

| Today | New home |
|---|---|
| Status: time lock state | Summary header badge, only while locking; About This Phone › Time lock (raw) |
| Status: sound lines, last reported, app version | About This Phone (Sound, Last report, Software); Do Not Disturb also → Attention |
| Access: last access line · Play: "Instant changes" and battery line · Crashes | About This Phone › Connection, Diagnostics |
| Status: paused / foreign app / unseen boots / relock notes / blocks | Summary Attention + Quick actions |
| Lock the phone again now · Pause restrictions for 2 hours · Allow these apps | Summary Quick actions |
| Parent-PIN notices, easy PIN, no-PIN card | Summary Attention → Lock Screen & PINs |
| Access card / Not enrolled card | Advanced › Connection (enrolled) / Summary "Connect this phone" (not enrolled) |
| Play and kiosk (lines, warnings, kiosk app block) | Advanced › App screens; "Restart once" warning → Attention |
| Launcher updates and notifications (3 switches) | Advanced › Updates & notifications |
| Launcher crashes | About This Phone › Diagnostics |
| Time rules card (state, lift, +min, lifts table, edit link) | Summary (state, quick actions, today) + Device › Time sub-page (full); the edit link → Settings › Time Rules for everyone's rules |
| Calls & SMS card | Summary row → Calls & Contacts |
| Calls page: default country code | Settings › Phone Setup |
| Apps card: list, Wi-Fi only, downloads line, corrupt warning | Apps sub-page |
| Apps card: On the lock screen | Lock Screen & PINs › Notifications |
| Apps card: Names and icons | Apps sub-page, per app row |
| Music card (everything, incl. Sonos) | Music sub-page (Sonos as its own group or sub-page) |
| Launcher card: language, apps per row, no-notification-access warning | Home Screen; warning → Attention |
| Wallpapers card + Lock screen background | Home Screen (wallpapers) + Lock Screen & PINs (background) |
| Screen lock card (kid PIN, remove) | Lock Screen & PINs |
| Parent PIN card (+ events) | Lock Screen & PINs |
| Screen timeout | Lock Screen & PINs |
| Phone hardening (9 switches, safe mode, backup to Google) | Advanced › Restrictions |
| Quick Controls (4 boxes) | Home Screen › Quick controls |
| Ad/content filtering (VPN) switch | Web Filter sub-page |
| Remove this device | Advanced, bottom |
| Locate page (all of it) | **Locate tab** (`/devices/locate`, same URL); the summary's Location row links to it with `?device=` |
| DNS: upstream, blocklists, custom lists | Settings › Web Filter |
| DNS: per-device overrides | Device › Web Filter |
| DNS log (switch + table) | Device › Web Filter; Settings › Blocked Activity as the entrance |
| Schedules: global rules, global screen time | Settings › Time Rules (`/schedules`, same URL) |
| Schedules: per-device switch, screen time, rules | Device › Time (also linked from Settings › Time Rules › Phones) |
| Apps\|Music switch | Gone - Music is a tab |
| Music page: add link, own files, Storytel shelf link | Music › Add |
| Music page: sweep interval, Storytel login, music app row, orphans | Music › Settings |
| Music page: categories | Music › Categories |
| Music page: Import from Vibb | Music › Add › Import from Vibb |
| Tracked app page (all cards) | App page top + Source & signing |
| Settings hub (6 rows + Sign out) | Settings (rebuilt, §3.6) |
| Wallpapers page | Settings › Wallpapers |
| Provisioning page | Settings › Phone Setup (and linked from Add phone) |
| Backups, Updates, Security, Account | Settings › Server, unchanged inside apart from copy |

### 3.10 "Online": which signal, which thresholds

No single signal is enough (§1.2b):
- The stream count can stay up for many minutes after a phone has gone.
- The last report can be 30 minutes old on a perfectly connected phone.
- Both in-memory signals are empty after a server restart.

So the state combines them:

**Last contact** = the newest of three times:
- `limits::last_access(device)`: any authenticated request;
- `devices.last_seen_at`: the last status report;
- the `since` of an **open** stream.

The first is the freshest. The second survives a restart.

| State | Rule | Header text | Why these numbers |
|---|---|---|---|
| **Online** (green) | A stream is open **and** last contact ≤ **45 min** ago | "Online · seen 3 min ago" | With the stream up the phone syncs (and reports) at least every 30 min, plus 9-15 min of Doze slack. A stream that has been silent longer than that is a dead TCP connection the server hasn't noticed. |
| **Away** (orange) | No open stream (or the stream rule failed) **and** last contact ≤ **45 min** ago | "Away · seen 12 min ago · changes may take up to 15 min" | Without the stream the phone syncs every 15 min, so it is reachable, only slowly. |
| **Offline** (grey) | Last contact > **45 min** ago, or never | "Offline · seen 2 h ago · changes wait until it's back" | Three missed 15-minute backstops: the phone is off, out of coverage, or broken. |
| **Not set up** (grey) | Not enrolled | "Not connected yet" | - |

Notes for the build:
- **Right after a server restart**, the memory is empty. A phone with a fresh `last_seen_at` shows **Away** for the
  5-15 s until its stream reconnects. That is honest and harmless; no special case.
- **The 45 min** is one constant (`ONLINE_WINDOW_MINUTES`) next to `instant_changes_line` in `streams.rs`. It is
  derived from the launcher's `BACKSTOP_INTERVAL_MS` (30 min). A comment ties the two, since there is no compile-time
  link between them.
- **Relative time** comes from the server's clock only. The phone's clock is never used, so a phone with a wrong
  clock can't fake "just now".
  - Under 1 min: "just now".
  - Under 60 min: "N min ago".
  - Under 24 h: "N h ago".
  - Yesterday: "yesterday HH:MM".
  - Older: "12 Oct".
- **The list page** uses the same function per phone. It is one in-memory lookup plus the `last_seen_at` it already
  reads.
- **Tests**: the state function is pure (inputs: stream state, last access, last seen, now) and gets table tests for
  each row above, including the restart and dead-stream cases.

---

## 4. Copy guidelines

### 4.1 Tone

- Talk to a parent, not to a developer. Say what happens to the child and the phone, not how the system does it.
- Calm and plain. No "Note:", no capitals for emphasis, no exclamation marks.
- Second person for the parent ("you"). "The phone" or the phone's name for the device. "Your child" in help text
  (gender-neutral: no "him").
- State outcomes: "Calls from unknown numbers are rejected." Not: "The launcher's CallScreeningService rejects…".

### 4.2 Length limits

| Text | Limit |
|---|---|
| Row label | 1-4 words, Title Case on navigation rows, sentence case on switches |
| Row value | 1-4 words or a number |
| Section footer | 1 sentence, at most 15 words; only when the label alone misleads |
| ⓘ panel | At most 80 words, 3 short paragraphs or a list; link to Help for more |
| Attention row | One line, at most 8 words, starting with the fact ("Restrictions paused", "Parent PIN easy to guess") |
| Warning on a sub-page | A bold title of up to 6 words + 1 sentence + a button |
| Confirm dialog (`<details>`) | 1 sentence saying what is lost + the button naming the action ("Erase Anna's phone") |
| Button | A verb + an object, at most 3 words ("Add 15 min", "Check now", "Lock again") |
| Empty state | 1 sentence + an action ("No contacts yet." + "Add contact") |

Things never shown outside an ⓘ panel or Advanced:
- package names, component names, file paths, env vars, shell commands;
- hashes, version codes, UTC suffixes;
- the words kiosk, MDM, tsnet, token, policy, sync, enrol(l), watcher, upstream.

### 4.3 Naming (glossary)

| Today | New |
|---|---|
| device | phone (the tab stays "Devices", the user's decision) |
| launcher / Handy's lock | the phone, or "Vibb" where the software must be named (Q6) |
| kid / the kid's PIN | child / **Child PIN** |
| parent PIN / parent code / offline override / unlock code | **Parent PIN** everywhere in the PWA (the phone's own words are quoted only in ⓘ) |
| Time rules | **Time Rules** (a Settings row), **rules** (things in it); a phone's own view is **Time** |
| Lift a rule | **Pause a rule** |
| +15 min screen time | **Add 15 min** |
| Pause restrictions for 2 hours | **Unlock for 2 hours** |
| Lock the phone again now | **Lock again now** |
| Phone hardening | **Restrictions** (in Advanced) |
| Kiosk app block | **Block other apps' screens** |
| Quick Controls (swipe-left screen) | **Quick controls** |
| Ad/content filtering (VPN), DNS/Filters | **Web Filter** |
| Blocked activity | **Blocked Activity** (keep) |
| Enroll / enrollment code / Provision by QR | **Connect** / **Setup code** / **Set up with QR code** |
| Revoke access | **Disconnect phone** |
| Wipe | **Erase phone** (Apple's word) |
| Tracked app / catalog | **App** / **App catalog** |
| Release tag prefix / asset filename filter | **Tag starts with** / **File name contains** (Advanced only) |
| Sweep / check for new episodes | **Check for new episodes** |
| Provisioning | **Phone Setup** |

### 4.4 The "i" disclosure pattern

```html
<div class="row">
  <label class="row-label" for="sms">SMS</label>
  <input type="checkbox" role="switch" id="sms" name="sms_enabled" …>
</div>
<details class="info">
  <summary aria-label="About SMS">ⓘ</summary>
  <p>Turning SMS off also hides the Messages app, since it can chat over RCS.</p>
</details>
```

How it behaves:
- The `<details>` sits right after the row it explains, inside the same group, with no JS.
- The summary is a 44×44 px target and has an `aria-label` naming the topic.
- Open, the panel is grey text on the group's surface, indented to the label.
- It never holds a control, so a save never depends on it being open.
- A save keeps the scroll position, so a panel that closes after a reload is fine.

### 4.5 Before → after (20)

| # | Where | Before | After |
|---|---|---|---|
| 1 | `device_detail.html:571` (136 words) | "Handy's lock replaces Android's screen lock: when the screen turns off, the phone locks and the kid opens it with a PIN only they and you know. … 4 wrong PINs in a row are free; after the 5th the phone waits 30 s, then 1, 2, 5 and 15 minutes …" | Row **Child PIN** · "On (4 digits)". Footer: "Locks the phone when the screen turns off." ⓘ: "Calls from allowed contacts and emergency calls work while locked. After 5 wrong PINs the phone waits longer between tries. It never erases itself. Your Parent PIN always opens it." |
| 2 | `:572` (108) | "It keeps people around him out, but the phone no longer has an Android code: its storage is encrypted with a key that doesn't depend on a code …" | ⓘ on the Child PIN row, second paragraph: "This lock keeps people nearby out. It does not protect the phone's data from a thief with forensic tools, and some bank or wallet apps won't work." |
| 3 | `:518` (96) | "The launcher can't read notifications, so apps on the home screen show no unread counts … run `adb shell cmd notification allow_listener me.vibb.launcher/…`" | Attention row: **"Notification access off"** ›. Sub-page warning: "Unread counts, the lock-screen notifications and the music card are off." ⓘ: the adb command, copyable. |
| 4 | `:311` (94) | "Every app currently on this device, plus anything from the Apps catalog it doesn't have yet. Checking a preinstalled or already-installed app allows it; … Each checkbox saves itself immediately …" | Footer: "Turning an app off hides it and uninstalls it where possible." |
| 5 | `:602` (87) | "The parent PIN works on the phone even when it can't reach this server. It opens the launcher's Settings …" | Row **Parent PIN** · "Set". Footer: "Opens the phone's settings and lifts limits, even offline." ⓘ: what it can and can't open (calls and restrictions stay). |
| 6 | `:603` (85) | "After 5 wrong tries the phone stops accepting the parent PIN for 15 minutes. Each further lockout doubles …" | ⓘ on Parent PIN: "After 5 wrong tries it waits 15 minutes, then longer each time, up to a day. Saving a new PIN here ends the wait." |
| 7 | `:183` (85) | "Changes reach the phone at once over its own connection to this server, which it keeps open. While that connection is down …" | Header status: **Online** / **Offline · changes wait**. ⓘ: "An offline phone gets changes when it reconnects, at the latest within 30 minutes of being online." |
| 8 | `:179` (60) | "Kiosk app block: in kiosk mode, screens of apps that aren't allowed never open - also when an allowed app opens them (Play Store, Google sign-in and payment sheets). …" | Switch **Block other apps' screens**. Footer: "Turn off only if something breaks." ⓘ: what still works (emergency calls, share sheet, file pickers). |
| 9 | `:697` (109) | "Off by default, and switched on when you set the kid's PIN (safe mode would skip handy's lock). Safe mode starts the phone without the launcher …" | Switch **Block safe mode**. Footer: "On when a Child PIN is set: safe mode skips the lock." ⓘ: recovery paths. |
| 10 | `:316` (66) | "App updates only on Wi-Fi: apps from the catalog download only on Wi-Fi (a network that isn't metered) and wait otherwise …" | Switch **Download apps on Wi-Fi only**. ⓘ: "Phone updates wait up to 3 days for Wi-Fi. Nothing downloads while roaming." |
| 11 | `:729` (72) | "The launcher runs its own on-device VPN to filter ads/content locally (see the DNS/Filters tab …) …" | Switch **Web filter**. Footer: "Blocks ads and adult sites on this phone." Row **Blocked activity log** · Off ›. |
| 12 | `:710` (57) | "These appear on a screen the kid can reach by swiping left from the home screen. This is the intended way for a kid to reach these controls instead of Android's own Quick Settings shade …" | Group header **Quick controls**, footer: "Shown when your child swipes left on the home screen." |
| 13 | `:725` (50) | "Sound: "Sound" or "Silent (vibrate)" for calls and notifications. This box only shows the choice on the kid's screen …" | Switch **Sound or silent**. ⓘ: "The volume buttons work either way. Do Not Disturb isn't offered." |
| 14 | `:700` (70) | "Backup to Google is on: Android may copy this phone's app data, call history, SMS and settings to its Google account. … report it: something on the phone keeps turning it on." | Attention row **"Backup to Google is on"**. Sub-page: "The phone turns it off at its next sync. If it stays on, something on the phone keeps turning it on." |
| 15 | `device_locate.html:13` (70) | "Locate, ring, lock, or wipe a device. A phone that's online gets a command within seconds; one that's off or without a connection gets it the next time it checks in …" | No intro. Footer under the actions: "An offline phone gets Ring and Locate within an hour, Lock and Erase whenever it's back." |
| 16 | `provisioning_settings.html:29` (80) | "A one-time, tagged (tag:kid), pre-approved auth key from your Tailscale admin console, used by the phone's embedded tsnet client. …" | Field **Tailscale key** · "Set (…abc)". Footer: "Make a new one-time key before each phone setup." ⓘ: tag, pre-approval, DEPLOY.md link. |
| 17 | `calls.rs` `call_report` (47-55) | "Phone app role not active: the launcher isn't the phone's default phone app, so incoming calls are not filtered - anyone can call this phone - and it can only call emergency numbers. Open the launcher's home screen on the phone and accept the "default phone app" prompt." | Attention row **"Anyone can call this phone"** ›. Calls page: "Vibb isn't the phone app yet. On the phone, open the home screen and accept "Set as default phone app"." |
| 18 | `streams.rs:162` (dyn, ~25) | "Instant changes: connected since 2026-10-13 08:12:04 UTC, as far as this server can tell. The phone also checks in by itself every 30 minutes." | Header **Online · seen 3 min ago**; About This Phone › Instant changes "Connected since 08:12". |
| 19 | Status card `device_detail.html:37-45` | "Time lock at the last report: **none**" + "Whether a time rule (bedtime, school, screen time) was locking the phone. The kid's PIN lock is shown under "Screen lock"." + "App version: 0.23.6-debug" + "Sound at the last sync: on." + "Last reported 2026-10-13 10:59:13" | Header: **● Online** · "Seen 3 min ago". The lock badge only while locking: "Bedtime until 07:00". Sound, version and the exact time are in About This Phone. |
| 20 | `music.html:117` (67) | "6 hours is what the Vibb Pi uses (NRK podcasts every 12 hours). Shorter means more requests to NRK and the podcast sites: about 2× the Pi at 3 hours …" | Picker **Check for new episodes** · "Every 6 hours". ⓘ: "More often means more requests to NRK and podcast sites. Shows nobody has are checked once a day." |

---

## 5. Constraints the redesign must keep

### 5.1 A save never jumps to the top (the user's rule)

How it works today:
- `scroll-restore.js` (loaded un-deferred from `partials/head.html`) remembers the scroll position on any submit or
  `form.submit()`.
- It restores the position when the response lands on the **same path** within 20 s **and without a #fragment**.
- Redirects with a fragment (`#music`, `#parent-pin`, `#access`, `#screen-lock`, `#play-kiosk`, `#kiosk-escapes`,
  `#music-entry-{id}`, `#music-settings`, `#music-sonos`, `#app-{package}`) land at that anchor instead.

What the split must do:
- **Every handler that redirects back to `/devices/{id}` must redirect to the sub-page the form now lives on.**
  Otherwise a switch on `/devices/{id}/lock` lands on the summary, which is both a jump and the wrong page.
- Today's targets are listed in `handlers/devices.rs`, `music.rs:1798,3717,3766,3851,3860`, `parent_pin.rs:86`,
  `kid_lock`, `window_relock`, `play`, `kiosk_escapes`, `calls` and `lifts`.
- The cleanest way is a hidden `back` field that every form sends, validated against an allow-list of the device's
  sub-paths. That is the pattern `music_entry.html:51` already uses (`back=entry`).
- Anchors that other text links to (`#parent-pin` in `window_relock.rs`, `#app-{package}` in the blocks list,
  `/devices/{id}#music` from the music entry page) must point at the new sub-page and anchor.
- Quick actions on the summary post and come back to the summary at the same scroll position.
- `jstest/scroll-restore.test.js` and `device_api::pages_restore_scroll_after_auto_save` must stay green. The latter
  requires every template to include the head partial.

### 5.2 Without JS

Today, without JS:
- Every auto-saving control has a `<noscript>` Save button.
- The confirms use `<details>` (wipe, revoke, restore, format, remove).

The redesign keeps both and **fixes the gaps** (§2.3 item 10):
- Device pickers become links, so they need no JS.
- The active tab is rendered on the server.
- `onsubmit confirm()` deletes get a `<details>` confirm or stay as they are (Q7).
- The ⓘ panels are `<details>`.
- Switches are real checkboxes.
- No new behaviour may depend on JS. JS stays limited to these extras:
  - `app-display.js` (preview);
  - `music-cards.js` (live status);
  - the Leaflet map;
  - the locate poll;
  - the installing auto-reload.

### 5.3 PWA manifest and offline

What stays:
- `manifest.webmanifest` keeps `start_url "/"`, `scope "/"` and `display standalone`.
- A **name change** (Q6) changes what an installed home-screen icon says only after the browser refreshes the
  manifest. That is harmless but slow.
- `theme_color` and `background_color` should follow the new palette, in light and dark.
- `templates/sw.js` stays **deliberately without a cache and without a fetch handler**. The admin handles session
  cookies, so there is no offline mode and the redesign adds none.

The apple-touch-icon and the status-bar style (`apple-mobile-web-app-status-bar-style`) are worth revisiting for the
iOS look, for example `black-translucent` with safe-area padding.

### 5.4 Accessibility

- Every control has a visible or programmatic label. Switches use `role="switch"` inside a `<label>`.
- Touch targets are at least 44×44 px, including the ⓘ summary and the tab bar items.
- Contrast is at least 4.5:1 for text, in both themes. The grey footer text and the red-on-pink `.error` must be
  rechecked.
- Font sizes are in `rem`, never below 0.75rem (12px). The tab labels go up from 0.68rem.
- The current tab has `aria-current="page"`. Nav icons get `aria-hidden="true"`.
- Status changes that `music-cards.js` swaps in keep `role="status"`.
- Auto-submitting `<select>`s: keyboard users must not save on every arrow key. Use a picker sub-page (iOS style: a
  list of options, each a submit button), or keep `onchange` only for pointer input. This is a deliberate decision
  in the build.
- `prefers-color-scheme` theming stays, with no manual toggle, as now. `prefers-reduced-motion` turns off any
  transitions.
- Tables (security log, commands, lifts, backups) become stacked rows on a narrow screen.

### 5.5 Tests that read page text

Many `src/tests/*.rs` render pages (`get_page`, `view_device`, `show_*`) and assert on text with `contains(…)`:
- music 35;
- music_storytel 35;
- time_rules 25;
- admin_auth 19;
- window_relock 18;
- hardening 13;
- lock_glance 11;
- tracked_apps 11;
- parent_pin 8;
- cleanup 8;
- calls 7;
- music_sweep 7;
- and a few each in about 20 more files.

Some assert on the exact wording of runtime lines, for example:
- "Skole is active - calls blocked";
- "Screen time 2026-10-05: 42 of 90 min (incl. 30 extra)";
- "Sound at the last sync: silent (vibrate)";
- "The phone last reported: 2 minutes.";
- "Last access: none since this server started.";
- "6 hours is what the Vibb Pi uses";
- "This box only shows the choice";
- "The phone hasn't seen any speakers yet.".

Others assert on markup:
- `<a href="#parent-pin">Change the parent PIN</a>` (`window_relock.rs:687`);
- redirect `Location`s with anchors (`enrollment.rs:355,372`, `parent_pin.rs:68`, `music.rs:906,924,1460,2370,2529,2597,2606`);
- `app_display.rs:308` and `device_api.rs:802` read template files directly.

How to handle them:
- Each page's rewrite updates its tests in the same commit.
- A test that checks a **fact** ("the page says the phone is paused") should assert on a stable hook, such as a
  `data-` attribute or an id, plus a short phrase. It should not assert on a whole sentence, so the next copy edit
  doesn't break it.
- Tests that pin **security** behaviour (no free text from the phone, escaping, H2) must keep their exact assertions:
  `crash_reports_reach_the_device_page_without_free_text` and the `js_string` tests.

### 5.6 Risky to change

| Risk | Why | How to handle |
|---|---|---|
| `POST /devices/{id}/policy` (Quick controls + filter) | `update_policy` treats a **missing** checkbox as off for all five fields (`devices.rs:2009-2023`). Splitting the form across Home Screen and Web Filter, or making it auto-save per switch, would turn the other fields off. | Split the handler into two routes (quick controls, filter), or send the other fields as hidden inputs. Test both. |
| Allowlist forms (`apps/toggle`) | Per-app mini-forms carry `tracked_app_id`/`package_name`; `id="app-{package}"` is a link target from the blocks list | Keep the form fields and ids; move as a block |
| Hardening form | One form, every switch posts all 10 fields; a missing one means off | Keep as one form on one page (Advanced › Restrictions) |
| Sonos form | "All rooms" on posts hidden `room` inputs to keep the ticks | Move as a block |
| Music settings form (`/devices/{id}/music`) | One form: mobile data, volume cap, Storytel; missing = off | Keep the three together |
| Parent PIN / Child PIN | The parent-PIN form can also clear (`clear_pin`); the kid-PIN form posts to the same route for set and clear | Keep the route semantics; only move |
| The 3 s auto-reload while installing | Lives on the device page; must follow the Apps sub-page and must not run on the summary | Move with the list |
| `music-cards.js` | Finds `.music-status[data-entry]` and anchors on `.music-card, .card` | Keep the classes or update the script in the same commit |
| `app-display.js` | Finds `form.app-display-form` and `data-preview-*` | Keep the partial's markup |
| Locate JS | `locations.json`, `locate-result.json`, `location.replace('/devices/locate?device=…')` | The URL stays, so the script keeps working; recheck it after the layout change |
| Askama | Templates compile into the binary; a missing variable is a build error | Build after every page |
| `style.css?v=7` | Browsers cache `/static` hard | Bump `v` with every CSS change |
| The list page's status | New per-phone reads on `/devices` | One query per phone is fine at 1-3 phones; no N+1 beyond that |
| Security events | Some routes log security events from the same handlers | Moving forms doesn't change them; renaming routes would - don't rename POST routes |

---

## 6. Mockup list (9 screens)

Every screen is a phone-width frame (390 pt), light mode first, with one dark-mode frame of the device summary. The
data is realistic and taken from the code and tests: phones **Anna** and **Emil**; rules **Skole** and **Leggetid**;
library entries **Abels tårn**, **Ukas nyheter**, **Godnatt**; categories **Musikk**, **Podkast**,
**Eventyr og sånt**; the app **Element X shown as "Chat"**.

### M1 - Devices (list)

- Large title "Devices".
- **Anna**:
  - avatar "A" with a green dot;
  - "Online · Skole until 14:30";
  - a thin screen-time bar "42 of 90 min";
  - a red badge "1".
- **Emil**:
  - avatar "E" with a grey dot;
  - "Offline · seen 3 h ago" (no lock part: nothing is locking);
  - "15 of 120 min".
- A third row, to show the orange state: **Ella** "Away · seen 12 min ago".
- **Add Phone** row.
- Above the list, the password banner as it will look (an orange inset row "Change the generated password ›"), so the
  banner style is reviewed too.
- Tab bar: **Devices** active · Apps · Locate · Music · Settings.

### M2 - Device summary (Anna)

1. **Header** (§3.2, §3.10):
   - "Anna" with a green dot and **Online**;
   - "Seen 3 min ago";
   - the badge **Skole until 14:30 · calls off**;
   - a bar "42 of 90 min today".
   - No app version, no sound, no exact timestamp.
2. **Attention** (1):
   - a red row "Restrictions paused · ends 15:10 ›".
3. **Quick actions**, as a 2×3 grid of rounded buttons:
   - Add 15 min · Add 30 min · Add 1 h;
   - Pause a rule… · **Lock again now** (shown because a pause runs) · Locate.
4. **Today**:
   - "Skole paused for 30 min · by you at 13:05 · ends 13:35 · End now";
   - "+30 min · 12:10".
5. **Sections**, each with a value:
   - Calls & Contacts "8 contacts · SMS on";
   - Apps "14 allowed";
   - Music "6 in library · Storytel on";
   - Time "Everyone's rules";
   - Lock Screen & PINs "Child PIN on";
   - Home Screen "4 per row";
   - Location "Seen 10 min ago" (opens the Locate tab on Anna);
   - Web Filter "On";
   - About This Phone "Vibb 0.23.6";
   - Advanced.
6. A second frame of the same screen in **dark mode**.
7. Two **header variants** as small strips:
   - **Emil**, Offline: a grey dot, "Offline · seen 3 h ago · changes wait until it's back", no lock badge;
   - **Anna** at night: "Online · seen just now", the badge "Leggetid until 07:00".
8. A small frame of **About This Phone** for Anna (§3.2 table):
   - Connection: Status "Online since 08:12", Last request "10:59 today, over the tailnet", Last report "10:59 today
     (3 min ago)", Battery optimisation "Off";
   - Sound: Ringer "On", Do Not Disturb "Off";
   - Software: Vibb "0.23.6-debug", Music app "Installed · 0.3.0", Settings from the server "Applied";
   - Diagnostics: Crash reports "None in 30 days".

### M3 - Device sub-page: Lock Screen & PINs (Anna)

This page best shows the ⓘ pattern and the PIN forms.

- "‹ Anna", large title "Lock Screen & PINs".
- **Child PIN** group:
  - "Child PIN · On (4 digits) ›" (opens the change form);
  - the footer "Locks the phone when the screen turns off.";
  - an ⓘ, **shown open** in the mockup with the text from §4.5 #1.
- **Parent PIN** group:
  - "Parent PIN · Set ›";
  - "Recent use" with 2 events ("Today 07:58 · opened Settings", "Yesterday · 5 wrong tries, waited 15 min").
- **Screen**:
  - "Turn off after · 1 minute ›".
- **Lock screen** group:
  - "Background · Same as Home ›";
  - switch "Show message text" (off) + footer "Anyone holding the phone can read it.";
  - a list of switches: Meldinger (on), Chat (on), Signal (off, note "not installed").

### M4 - Apps (tab)

- Large title "Apps".
- **On phones**:
  - "Anna · 14 allowed ›";
  - "Emil · 11 allowed ›".
- **Catalog**:
  - Vibb (launcher) "1.4.0 · all phones";
  - Musikk "0.3.0 · 2 phones";
  - Chat (Element X) "25.10.1 · 1 phone";
  - Tailscale "1.76.0 · 2 phones";
  - one row with a red badge "Update failed".
- **Add App** row.
- A second, small frame: **Anna › Apps**:
  - switch rows with icon tiles: Klokke, Kalkulator, Kamera, Telefon, Meldinger, Musikk, Chat;
  - one row "Installing 40 %";
  - one off row "Firefox · not allowed";
  - the footer "Turning an app off hides it and uninstalls it where possible.".

### M5 - Music (tab)

- Large title "Music"; a header action "Edit".
- **Library (9 of 200)**:
  - Abels tårn (NRK serie, cover) "NRK series · Eventyr og sånt · 2 phones", status "98 episodes · newest 9 Oct";
  - Ukas nyheter "Podcast · Podkast · 1 phone", status in orange "Checking…";
  - Godnatt (own files) "Own files · Musikk · 12 files";
  - one Storytel series row "Storytel · 4 books · Anna".
- **Add**:
  - "Podcast or NRK link";
  - "Own files";
  - "Storytel shelf ›";
  - "Import from Vibb ›".
- **On phones**:
  - "Anna · 6 of 9 · Storytel on ›";
  - "Emil · 4 of 9 ›".
- **Settings ›** and **Categories ›**.

### M6 - Settings › Time Rules

- "‹ Settings", large title "Time Rules".
- **Everyone › Rules**:
  - "Skole · Mon-Fri 08:15-14:30 · calls off ›";
  - "Leggetid · Every day 20:30-07:00 · calls on ›";
  - Add Rule.
- **Everyone › Screen time**:
  - "Mon-Fri 90 min · Sat-Sun 120 min ›".
- **Phones** (links into the Devices tab):
  - "Anna · Everyone's rules ›";
  - "Emil · Own rules ›".
- The footer "Emergency calls always work."
- Tab bar: **Settings** active.
- A second, small frame: **the rule editor for Skole**: name, kind, the "Calls with contacts" switch, the 7 day rows
  (Sat/Sun empty), "Apps that stay usable ›", Save, Delete Rule in red.

### M7 - Settings

- Large title "Settings". The grouped list from §3.6, with SF-Symbol-like outline icons on tinted squares (not emoji):
  - **Rules**: Time Rules "2 rules · 90 min" · Web Filter "On · 3 lists" · Blocked Activity "Log on for 1 phone";
  - **Content**: Wallpapers "8 · 2 photos";
  - **Server**: Account "admin" · Security Log · Backups "Last: today 03:00" · Software Updates "Update available"
    (blue badge) · Phone Setup · About "0.24.0";
  - **Sign Out** in red.
- Tab bar: Devices · Apps · Locate · Music · **Settings** active.

### M8 - Locate (tab)

- Large title "Locate".
- Phone switcher at the top: **Anna** (selected) · Emil.
- The map, about 45 % of the screen height: Anna's trail for today and a pin at the last fix. Under it: "10 min ago ·
  ±12 m" and **Update Location**.
- **Actions**: Ring · Stop Ringing · Lock · **Erase Phone…** in red. Footer: "An offline phone gets Ring and Locate
  within an hour, Lock and Erase whenever it's back."
- **Location** · "Only when asked · 7 days ›".
- **Recent**:
  - "Locate · today 10:58 · Done (±12 m)";
  - "Ring · today 10:41 · Done";
  - "Lock · waiting for the phone" with **Cancel**.
- A second, small frame: Emil selected while he is Offline. The orange row "Erasing works after the phone restarts
  once" above the actions; the map shows the last fix "3 h ago".
- Tab bar: Devices · Apps · **Locate** active · Music · Settings.

### M9 - Patterns sheet

One frame of the pieces, so the reviewer can judge each of them alone:
- a switch row with a one-line footer;
- an ⓘ open and closed;
- the four Attention levels (red alarm, orange action, grey state, green flash);
- a destructive confirm (`<details>`) open: "Erase Anna's phone? Everything on it is deleted." + "Erase Phone";
- a field with a form error under it;
- a picker row and its option list;
- the tab bar with 5 items, in light and dark.

---

## 7. Questions for the user

1. **The two lock-screen picture settings.**
   - Per photo: "Also on the lock screen" (`/wallpapers`).
   - Per phone: "Lock screen background" (design 25).

   Should the per-photo switch go, now that each phone picks its own lock background? This is the only control the
   proposal would like to drop. It stays until you answer.
2. **The DNS server choice (Cloudflare / Quad9).** It moves to Settings › Web Filter, at the bottom, unchanged. Is it still
   worth showing at all? It is sent to the phones as `dns_upstream_provider`.
3. **"This is the launcher app" switch** on a catalog app. It moves into Source & signing. It should only ever be set
   once. Keep it visible, or hide it once a row is marked?
4. **A Locate quick action on the summary.** Locate is now a tab of its own (your decision), and the summary's
   Location row already opens it on that phone. So a "Locate" quick action is **optional**: it saves nothing over
   the tab, but it puts the lost-phone path next to Lock and Ring. The proposal keeps it in the quick-action grid
   (M2). Keep it, or drop it to make room?
5. **Times.** Can the PWA show local time instead of UTC? Today the server labels times "UTC" because the Pi may
   have no time zone set. The proposal is to use the time zone from Phone Setup (`settings.time_zone`, e.g.
   `Europe/Oslo`) for display, and show relative times ("12 min ago") where they fit.
6. **The name.**
   - Header, `<title>` and manifest say "Kids Device MDM" / "Kids MDM".
   - The phone says "Vibb", and the lock says "Handy's lock".

   Which name should the PWA use? The proposal is "Vibb". Changing the manifest name renames an installed home-screen
   icon after the browser refreshes the manifest.
7. **Deletes without JS.** Deletes that ask with `confirm()` (rules, categories, wallpapers, music files and entries,
   orphans) delete at once when JS is off. Should they get the same `<details>` confirm as wipe and restore? The
   proposal says yes, for consistency.

---

## 8. Build order (suggested, page by page)

1. **The shell.** The CSS tokens and components: grouped list, rows, switch, ⓘ, badges, Attention, flash. The
   5-tab bar (Devices · Apps · Locate · Music · Settings) rendered on the server, and the rebuilt `/settings`
   hub. The pages keep their content.
2. **Devices list and device summary**, with the 9 sub-pages split out of `device_detail.html` and the redirect
   targets updated (§5.1). This is the largest step. QA it alone.
3. **Settings › Time Rules** (`/schedules`) and the device Time sub-page.
4. **Apps** (catalog + the app page).
5. **Music** (library, settings, categories, import sub-page, entry page).
6. **Locate** (the tab, with the phone switcher as links), then the rest of **Settings**: Web Filter, Blocked
   Activity, Wallpapers, Account, Security, Backups, Updates, Phone Setup.
7. **Add phone flow** and the sign-in pages.

Each step rewrites its copy to §4, updates its tests (§5.5), keeps the no-JS path, and is checked at 390 px in light
and dark mode.
