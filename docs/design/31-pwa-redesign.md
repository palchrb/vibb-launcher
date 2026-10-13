# 31 - The parent PWA redesign: inventory and information architecture

Information architect, 2026-10-13. Status: **buildable**; step 1 (the shell) built on branch `design-31-s1`, step 2 (display helpers + Devices list) on `design-31-s2`, step 3 (About This Phone + Advanced) on `design-31-s3`, step 4 (Lock Screen & PINs) on `design-31-s4`, step 5 (Home Screen + Web Filter) on `design-31-s5`, step 11 (the Apps tab) on `design-31-s11`, see the implementation status sections at the end.
- The user approved the mockups (`docs/design/mockups/parent-pwa/`).
- This revision fixes every finding of the design QA (`docs/design/qa-31-design.md`); what changed per finding is
  listed in §9.
- The build follows the 15 steps in §8.
- No code changes in this doc.

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
- Mockups come first, for the user's review. The build follows page by page. The mockups are approved; they decide
  the look, and this doc decides the content (§6.0).
- **The name is "Vibb Phone"** (§5.3).

**How to read this doc:**
- §1 lists everything a parent can see today.
- §2 lists what is wrong with it.
- §3 is the new structure, and §3.9 says where every current control ends up.
- §4 is the copy rules, with before → after examples.
- §5 lists what the rebuild must keep.
- §6 says how the approved mockups and this spec combine.
- §7 records the answers to the questions for the user.
- §8 is the build, in 15 steps.
- §9 lists the design QA findings and what changed for each.

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
  - The device page becomes a **summary**. Its header says **Online / Away / Offline · seen 3 min ago**. The signals
    and thresholds are in §3.10: Online is an open stream with contact within 60 min; Away is no stream with
    contact within 45 min.
  - The time lock shows only while it is locking ("Leggetid until 07:00 tomorrow").
  - The summary also has a few quick actions (risky ones behind a confirm), an "Attention" group, and **9
    sub-pages**:
    - **About This Phone** holds sound, version and exact times;
    - **Advanced** holds what a parent sets once or never.
  - Its Locate quick action opens the **Locate** tab on that phone.
  - The tabs are **Devices · Apps · Locate · Music · Settings**. Time Rules and Web Filter are rows in Settings.
  - Every existing control keeps a place (§3.9). A save never jumps (§5.1), and no form loses fields when it is
    split (§5.6).
  - The questions for the user are answered (§7).

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
    "handy" (lock text "Handy's lock"), "Vibb" (boot cover, music app, phone). The new name is "Vibb Phone"
    (§5.3).

---

## 3. Proposed information architecture

### 3.0 Patterns used everywhere (the iOS Settings vocabulary)

- **Grouped list.**
  - Sections of rows on a rounded surface ("inset grouped").
  - A small grey **section header** in capitals is optional.
  - A one-line grey **footer** under the group at most.
- **Row types:**
  - *navigation*: label · value · chevron. It only ever **goes to another page**; it never opens a page whose only
    job is to save one value (H3).
  - *switch*: label + switch. It saves its form on change. A switch is its own form only when its handler writes
    only that field, or the form has a section marker (§5.6, H1). Otherwise the switches of one form share one
    group and each change saves the whole form.
  - *value*: label · read-only value.
  - *picker*: label · an **inline native `<select>`** at the end of the row. It saves its form on change (§5.2 says
    how the keyboard is handled), with a `<noscript>` Save. A phone's OS picker is already the iOS-style list, so
    there are no picker pages (H3).
  - *action*: a blue text button row. Destructive or risky ones are red and sit behind a confirm (below).
- **Badges.** A small coloured dot or pill on a row or a device: green "Online", grey "Offline", orange "Away",
  red "2". State lines become badges or row values instead of sentences.
- **"i" disclosure.**
  - A small ⓘ button at the end of a row or a footer. It is a native `<details>`/`<summary>`, so it works without JS.
  - It opens an inline panel of at most 80 words under the row.
  - Longer reference text (lockout ladder, recovery paths, Tailscale key how-to) goes to a **Help** sub-page that the
    panel links to.
- **Attention group.**
  - On the device summary, alarms and needs-action messages (§1.12) come first, as red or orange rows: one line, a
    chevron to the fix.
  - The rows come from one function, `attention::collect` (§3.8). Each row's title comes from a typed value, never
    from the first sentence of today's text (M5).
- **Flash.**
  - After a save that has a visible result (PIN saved, code made, import done), one line shows **inside the group of
    the form that saved, under its rows**, not at the page top (H3).
  - Its group's id is the anchor, for the no-JS path. With JS, scroll restore keeps the parent where they were, and
    the flash is right there.
- **Confirm.**
  - A risky or destructive action is a `<details>`: the summary is the button ("Erase Phone…"). Open, it holds one
    sentence and a POST button naming the action.
  - This works without JS. No GET ever changes state.
  - The list of actions that need it is in §5.6.
- **Sub-page header.**
  - A back link ("‹ Anna") at the top left.
  - The page title, large, under it.
  - No other header chrome; the brand header goes away on inner pages, page by page as each is rebuilt, and from
    the tab landing pages in step 15 (the clean-up).
- **Switches** are a real `<input type="checkbox" role="switch">` with a sibling `<span class="knob">`, inside a
  `<label>` (§5.7). The mockups draw the knob as `::before` on the input; the build uses the sibling span.
- **The bottom bar** has 5 items, with labels at least 12px and active state from `aria-current="page"`. The server
  sets `aria-current` when it renders, so no JS is needed.

### 3.1 Tab bar and URLs

| Tab | Landing route | Its templates | Icon |
|---|---|---|---|
| **Devices** | `/devices` | `devices_list`, `device_add`, `device_detail` (the summary), every `device_*` sub-page, `device_calls`, `provision_qr`, `enroll_code` | phone |
| **Apps** | `/apps` | `apps_list`, `tracked_app_add`, `tracked_app_detail` | grid |
| **Locate** | `/devices/locate` | `device_locate` | map pin |
| **Music** | `/music` | `music`, `music_entry`, `music_storytel`, and the new `music_settings`, `music_categories`, `music_import` | note |
| **Settings** | `/settings` (the hub, rebuilt) | `settings_hub`, `schedules`, the new `schedule_rule` and `schedule_budget`, `dns_filter`, `dns_log`, `wallpapers`, `account`, `security`, `backups`, `updates`, `provisioning_settings` | gear |

The tab is set **per template**, not by matching the path (L1):
- Askama inlines includes at compile time. So each page sets `{% let tab = "settings" %}` before
  `{% include "partials/app_header.html" %}`, and the header marks that tab `aria-current="page"`.
- Every template belongs to exactly one tab (the table above). No request plumbing is needed, and the inline
  active-tab script (`app_header.html:32-42`) is deleted.
- The device sub-pages stay in the **Devices** tab, including `/devices/{id}/time` and `/devices/{id}/filter`, even
  though their global counterparts live under Settings.
- A test GETs one route per tab and checks `nav_current` (§5.5).

Rules for routes:
- Existing POST routes stay as they are. Only the redirect targets change (§5.1).
- New GET routes are added for the device sub-pages (§3.2) and for the music and time sub-pages.
- A new GET on a path that already has a POST is chained on one `.route(path, get(..).post(..))`, as
  `main.rs:677-682` does. This applies to `/devices/{id}/music`, `/music/categories`, `/music/import` and
  `/schedules/rules/{id}` (L5).
- `/` still redirects to `/devices`. `/schedules` and `/settings` keep their URLs. No `/time` and no `/more` routes.
- Old per-device URLs redirect (L5), with tests:
  - `/schedules?device=X` → `/devices/X/time`;
  - `/dns?device=X` and `/dns/log?device=X` → `/devices/X/filter`.

### 3.2 Devices

**Devices list (`/devices`).** One row per phone:
- an initial avatar with the connection dot (§3.10);
- the **name**;
- a **status line**: the connection state and "seen …", plus the lock part only while a rule is locking
  ("Online · Skole until 14:30", "Offline · seen 3 h ago");
- a thin screen-time bar, only when the report is from the server's local today (M4);
- a red badge with the number of Attention items, from `attention::collect` (§3.8).

Below the rows:
- An **"Add Phone"** row.
- The intro paragraph moves into the Add flow.
- The live password and sign-in warnings stay at the top, as an orange row ("Change the generated password ›").

**Device summary (`/devices/{id}`).** What a parent opens daily. Top to bottom:

1. **Header**, answering "is it connected, and since when" first (the user's feedback):
   - **Line 1:** the name, with the connection state as a coloured dot and a word: **Online** (green), **Away**
     (orange), **Offline** (grey), **Disconnected** (grey) or **Not seen yet** (grey). The rules are in §3.10.
   - **Line 2:** the relative time, "seen just now", "seen 3 min ago", "seen 2 h ago" or "seen yesterday 21:40".
     - While Away it adds "· changes may be delayed", with no number (H2).
     - While Offline it adds "· changes wait until it's back".
     - While Disconnected, it shows "Access removed" and a link "Connect again ›" to Advanced › Connection (L2).
   - **Line 3, only while something is locking:** a badge with the rule, "Skole until 14:30", "Leggetid until 07:00
     tomorrow", or "Screen time used up". Calls blocked adds "· calls off". "No rule active" shows **nothing**.
     The wording rules for the end time are in §3.10 (M4).
   - **Line 4:** today's screen time as a thin bar, "42 of 90 min today". It shows only when the report's `day` is
     the server's local today (M4), and is hidden when there is no limit and nothing is reported.
   - **Not in the header:** sound, app version, exact timestamps, policy state, "Instant changes". They move to
     **About This Phone** (below). Do Not Disturb is the exception: it becomes an Attention row, because calls won't
     ring.
   - **Battery:** the phone doesn't report a battery level today, only whether it is exempt from battery
     optimisation. A battery percentage needs a launcher and API change, which is out of scope here.
2. **Attention.** Only the rows that apply, from `attention::collect`. Each row has a short title of at most 8
   words, built from a typed value (M5), and links to the page that fixes it.
   - **Which rows count** (the user's decision, qa-31-s2-code L5): a phone not set up has none (its "Not set up" is
     enough). An Offline or Disconnected phone keeps the rows read from its last report, marked "At last report";
     the server's own facts (no or easy Parent PIN, access removed, incoming calls not blocked) stay unmarked. The
     list's count and the summary count the same rows.
   - **Alarms (red):**
     - restrictions paused;
     - possible PIN leak (foreign app);
     - Do Not Disturb on;
     - Backup to Google on;
     - no parent PIN;
     - easy parent PIN;
     - access revoked;
     - the phone can't apply its settings (policy problem);
     - unseen restarts;
     - incoming calls aren't blocked (L2);
     - a Child PIN is set but the lock is off on the phone (`lock.warnings`, L2).
   - **Needs action (orange):**
     - blocked apps waiting;
     - restart once;
     - Play install mode on, "Play Store open (install mode)" → Advanced › App screens;
     - Play can't be paused while "Block other apps' screens" is off, "Play Store can be opened" → App screens;
     - "Vibb isn't the Home app" (the update fence is partial) → Advanced › Updates & notifications;
     - "Boot cover wasn't shown" (another home screen came up at boot) → Updates & notifications.

       These four were added in step 3's fix round (qa-31-s3-code M4, L4). The coordinator decided yes on the user's
       behalf, since each lets other screens reach the child; **the user can overrule** it, and the rows go.
     - no notification access;
     - calls problems from `call_report`;
     - the screen-time record unreadable (`ledger_unreadable`, L2);
     - a launcher without time rules (`launcher_without_rules`, L2).
3. **Quick actions.** A 3-column grid of buttons. Labels are the short ones from §4.3:
   - **+15 min**, **+30 min**, **+1 h** (`lifts`, target budget). One tap each.
   - **Pause a rule…**: a `<details>` sheet with the rule and duration selects and a "Pause" button (`lifts`,
     target rule).
   - **Unlock for 2 h…**: a confirm (M6), "Unlock Anna's phone for 2 hours? Apps, Settings and the Play Store
     open." plus **Unlock** (`start-pause`).
   - **Lock again**, one tap, instead of Unlock while a pause or Play install mode runs (`end-windows`).
   - **Locate**: a link to `/devices/locate?device={id}` (Q4: kept).
   - **Allow these apps** is **not** a quick action. It sits under the blocked-apps list it lets out, on the
     Attention row's target, behind a confirm that names the apps (M6).
4. **Today.** Running and recent lifts with **End now**, the answers from the phone (`relock.answers`), and the
   flash after a quick action (`relock.notice`, L6).
5. **Sections.** Navigation rows, each with a value that sums it up. The summary has **no Location row**: the Locate
   quick action is its entrance, and both lead to the same tab (M2).

| Row | Value example | Sub-page | Holds (from today's page) |
|---|---|---|---|
| Calls & Contacts | "8 contacts" | `/devices/{id}/calls` (exists) | The Calls & SMS page, minus the country code (→ Settings › Phone Setup) |
| Apps | "14 allowed" | `/devices/{id}/apps` (new) | The allowlist split into **Allowed** and **Not allowed** (switches + status; `#app-{pkg}` kept), Download on Wi-Fi only, the downloads line, Names and icons (each app's `app_display_form` in a `<details>` under its row), the keyboard and accessibility note behind ⓘ. No search field (M2). |
| Music | "6 in library" | `/devices/{id}/music` (new) | The Music card **as design 30 step 1 leaves it** (M7): the music app switch, library ticks, the 5-field settings form with `v=2` as one group, Sonos |
| Time | "Everyone's rules" | `/devices/{id}/time` (new) | Rule state, lifts table, extra time, the own-rules switch, this phone's own screen time and rules |
| Lock Screen & PINs | "PIN on" | `/devices/{id}/lock` (new) | Child PIN, Parent PIN (+ reported events, `offline_override_used`), screen timeout, lock-screen background, lock-screen notifications |
| Home Screen | "4 per row" | `/devices/{id}/home` (new) | Language and apps per row (one form), wallpapers, Quick controls |
| Web Filter | "On" | `/devices/{id}/filter` (new) | Filter on/off, this phone's blocklist overrides and domains, the blocked log switch + log |
| About This Phone | "Vibb 0.23.6" | `/devices/{id}/about` (new) | See below |
| Advanced | - | `/devices/{id}/advanced` (new) | See below |

**The forms on the sub-pages** (H1, H3; the full table is §5.6):
- **Time:** the own-rules switch and this phone's 7 daily limits post to `/schedules/device/{id}`. With the new
  `section` marker, the switch saves alone (`section=switch`) and the limits save alone (`section=budget`).
- **Lock Screen & PINs:**
  - The Child PIN row ("Child PIN · On · 4 digits") opens a `<details>` under it with the change field and **Save**.
    **Remove Child PIN…** is a confirm (M6).
  - The Parent PIN row works the same way. Its "Remove instead" stays a checkbox in the form.
  - Screen timeout and Background are inline selects.
  - The lock-screen notifications are the text switch (one form) and one switch per app (one form each, as today).
- **Home Screen:** language and apps per row stay **one form** (`/devices/{id}/launcher`), shown as two inline
  selects in one group.
- **Web Filter:** the filter switch posts to `/devices/{id}/policy` with `section=filter`. On Home Screen, the Quick
  controls post with `section=quick`.

**About This Phone (`/devices/{id}/about`)** is read-only, like iOS Settings › General › About. It holds the facts
the old Status card led with. The rows follow the approved mockup (M2):

| Group | Row | Value (example) | From |
|---|---|---|---|
| Connection | Status | "Online since 08:12" | `connection_state` (§3.10) |
| | Last contact | "10:59 · 3 min ago" | the newest of the §3.10 signals |
| | Last full report | "10:59" | `device_status.reported_at` |
| | Network | "Over the tailnet" (ⓘ: addresses outside the tailnet) | `limits::last_access` |
| | Footer | "Online means the phone has a live link to this server. Changes reach it within seconds." | - |
| Sound | Ringer | "On" / "Vibrate" / "Silent" | `sound_mode::state` (typed, M5) |
| | Do Not Disturb | "Off" / "Priority only" / "Alarms only" / "Total silence" | same |
| Software | Vibb | "0.23.6" (ⓘ: the full version, e.g. "0.23.6-debug") | `app_version` |
| | Music app | "0.3.0" (ⓘ: package, version code) | music state |
| | Settings from server | "Applied" / the policy problem's short title | `policy_state` |
| | Battery optimisation | "Off" / "On" + ⓘ with the fix | `battery_optimization_exempt` |
| Diagnostics | Crash reports | "None in 30 days", or "2 kinds" + a `<details>` per crash (unchanged) | `device_crashes` |
| | Starts without Vibb | "None" / "2 in 30 days" + ⓘ | `boot_witness` |
| | Time lock | "None" / "Bedtime" | `lock_reason`, to check what the header says |

All times are local (Q5, M4), with the exact date and time in a `title` attribute and behind ⓘ, never as the visible
value.

**Advanced (`/devices/{id}/advanced`).** Expert and set-once controls, in groups:
- **Restrictions**: the 9 hardening switches and Block safe mode as **one form** (H1), and "Backup to Google: Off" as
  a value row.
- **App screens**: Block other apps' screens (`kiosk-block`), the Play state lines.
- **Updates & notifications**: Update fence, Notification filter and Boot cover as **one form** (`kiosk-escapes`,
  H1).
- **Connection**:
  - **New setup code** (a POST, which shows the code once on the code page);
  - **Set up with QR code ›**;
  - **Disconnect phone…** (today "Revoke access", with its `required` box).
- **Remove phone…** at the bottom, in red.

**A phone that hasn't enrolled** shows:
- the header ("Not set up");
- a "Connect this phone" group with **New setup code** (a POST button; the code can't be shown again, M2), **Set up
  with QR code ›**, and today's notices, `access.typed_paused` included (L6);
- Remove.

The sub-page rows show greyed out with "After setup", except Lock Screen & PINs: the PINs can be set first (step 4,
choice 11), so the phone's first policy already has its Parent PIN. Step 10 keeps that row live.

### 3.3 Apps

The Apps tab is the **catalog**, plus quick access to each phone's allowed list:

- **On phones.** One row per phone: "Anna · 14 allowed ›", leading to `/devices/{id}/apps`. This is the daily
  "allow an app" path, one tap shorter than today.
- **Catalog.** One row per catalog app:
  - the icon tile (`app_display`) and name;
  - the value: version + "2 phones";
  - a badge: "Update failed" (red), "No release yet" (grey), "Off" (grey).
- A footer: "Apps here are kept up to date on every phone that has them."
- **Add App** row.

**The app page (`/apps/tracked/{id}`):**
- **Top:** name, version, "Last checked 2 h ago", **Check now** (with the running or failed state in one line; the
  raw error behind ⓘ).
- **Show on phones as:** the `app_display_form` partial in a `<details>` under the row (unchanged logic).
- **On phones:** a read-only list of the phones that have it ticked, each linking to that phone's Apps page. The data
  is there (`device_tracked_apps`); this list is new.
- **Source & signing**, a `<details>` group:
  - the Details form (name, repo, file filter, tag prefix) as one form;
  - include pre-releases;
  - enabled;
  - the package (+ candidates) and the signing key;
  - **Forget package and key…** behind a confirm (M6);
  - "This is the launcher app" (Q3: kept visible);
  - Upload (manual apps).
- **Remove App…** in red, behind its existing confirm.

**Add App:** a short form (name, source GitHub/upload, repo). The filter, prefix, pre-releases and package go under
"More options" (`<details>`).

### 3.4 Music

`/music` loses the Apps|Music switch. Top to bottom:

1. **Library**, the whole list (no "Show all N" cut-off: the mockup's is dropped, M2). Each card becomes a list row:
   - cover · name · "NRK series · 98 episodes · 2 phones";
   - the live status as one line, coloured, still swapped by `music-cards.js`;
   - row actions in a compact trailing area: **Check now** and the **Offline** select, both kept (M2).
   - Reordering moves into an **Edit** mode: an "Edit" link in the header shows ↑ ↓ on every row. They are plain
     forms, with no drag.
2. **Add**, four rows:
   - **Podcast or NRK link** (an inline form in a `<details>`);
   - **Upload your own files** (an inline form in a `<details>`);
   - **From Storytel ›** (`/music/storytel`), with the value "Signed in" or "No login";
   - **Import from Vibb ›** (`/music/import`, a sub-page: one-off use). The mockup leaves this row out; it is kept
     (M2).
3. **On phones.** One row per phone: "Anna · 6 of 9 ›", leading to `/devices/{id}/music`.
4. **Categories ›** (`/music/categories`), with the count. The category list and editor, today's `<details>` blocks
   unchanged inside.
5. **Settings ›** (`/music/settings`): check for new episodes every N hours (the hints behind ⓘ), the Storytel
   login, the music app catalog row, and "Files no entry uses" (only when there are some).

Links and redirects that point at today's anchors move with their sections (L5):
- `/music#storytel` → `/music/settings#storytel`;
- `#app` and `#sweep` → `/music/settings`;
- `#import` → `/music/import`;
- `#categories` and `#category-{id}` → `/music/categories`.

`POST /music/import` (the preview) renders the new import page, not the whole Music page.

The **entry page** keeps its cards, retitled and reordered: Cover + Name → Episodes (status, Check now) → Settings
(category, order, offline, resume; **one form**) → Files → On phones → Delete (confirm). "Times are UTC." goes away
with local times (M4). Its hint about mobile data says "(the phone's Music page)" (M7).

The **Storytel shelf** keeps its shape. Its intro shrinks to one line, with the rest behind ⓘ.

**Music wording waits for design 30 step 3a** (M7). Until that is in main, the device page's music lines
(`storage_line`, library, the older-phone note) are moved and restyled but not reworded.

### 3.5 Settings › Time Rules

Time Rules is not a tab. It is the **first row in Settings** ("Time Rules · 2 rules ›"). It opens `/schedules`, which
keeps its URL and becomes a Screen-Time-like sub-page, with the back link "‹ Settings":

1. **Everyone · rules:**
   - one navigation row per rule, "Skole · Mon-Fri 08:15-14:30 · calls off ›";
   - **Add Rule**.
2. **Everyone · screen time:** one navigation row, "Daily limit · Mon-Fri 90 min · Sat-Sun 2 h ›". It opens
   `/schedules/screen-time` (new GET). That page holds the 7 fields as **one form** with **Save**. The POST
   (`/schedules/global`) redirects back to that page, with the flash in its group (H3).
3. **Phones.** One row per phone, "Anna · Everyone's rules ›" / "Emil · Own rules ›". Each links to
   `/devices/{id}/time`, not the summary (M2); it opens in the Devices tab.
4. A one-line footer: "Emergency calls always work."

The device picker select goes away; each phone is a link.

**The rule editor (`/schedules/rules/{id}`, new GET; `/schedules/rules/new` for a new rule)** is one form with an
explicit **Save** in the header:
- name;
- **Type** as an inline select (School / Bedtime / Other). The mockup's "Type ›" chevron becomes a select (H3); its
  JS link to the calls switch stays;
- the "Calls with contacts" switch;
- the 7 day rows (from / until). An empty day shows two empty time fields; the mockup's "Off" and "Add" are look
  only, so no link is needed;
- **Exceptions · Apps that still work** as a `<details>` **inside the form**, not a linked page (H1);
- **Delete Rule…** as a confirm (Q7).

Its redirects:
- **Save** redirects to the rule's own page, with the flash in its group.
- **Add** redirects to the new rule's page.
- **Delete** redirects to `/schedules`, with the flash in the Rules group.

The `scope` field still says whether the rule is a phone's own or everyone's. A phone's own rule goes back to
`/devices/{id}/time`.

**Per-phone time stays on the device page.** The daily actions (add time, pause a rule, end a lift) are on the
device summary. The full per-phone view is the device's **Time** sub-page (`/devices/{id}/time`). It holds:
- the lifts with End now (`back=time`, §5.1);
- extra time;
- the own-rules switch (`section=switch`);
- this phone's own daily limits (`section=budget`);
- this phone's own rules, with links to the same editor.

Settings › Time Rules owns only the rules and screen time that apply to everyone.

The rules' error lines (`corrupt_rules`, `budget_corrupt`) stay on whichever of these pages shows the broken item (L6).

### 3.6 Settings and the Locate tab

**Settings (`/settings`)** replaces the old hub. It holds everything the first draft's More tab held, minus Locate
(now a tab), plus Time Rules at the top. It is a grouped list with outline icons on tinted squares, not emoji.

Every value comes from the database or memory, never from the network (M3):

| Group | Row | Value (example) | Goes to |
|---|---|---|---|
| **Rules** | Time Rules | "2 rules" (count of everyone's rules) | `/schedules` (§3.5) |
| | Web Filter | "3 lists" (count of enabled `dns_blocklists`) | `/dns`: shared blocklists, blocked and allowed domains, and the DNS server at the bottom (Q2: kept). The per-phone parts are on each phone's Web Filter page |
| | Blocked Activity | "12 today" (a count of today's `device_dns_events`) | `/dns/log`: a list of phones (log on/off) › each phone's Web Filter page |
| **Content** | Wallpapers | "5" (count) | `/wallpapers`; delete becomes a confirm (Q7); the per-photo "Also on the lock screen" stays (Q1) |
| **Server** | Phone Setup | - | `/settings/provisioning`: the provisioning form (one form) and the **default country code** as a **second form** with its own Save (L6). `/settings/calls` posts with `back=provisioning` and returns there (H3) |
| | Backups | "Today 03:00" (the newest backup file's mtime) | `/backups` |
| | Software Updates | **No value** | `/updates`. The hub never calls GitHub. No cached result of a check exists today (`data/app_update_last_check` holds only a date), so the row shows nothing (M3) |
| | Account & Password | - | `/account` (password, two-factor, sign out everywhere) |
| | Security Log | - | `/security`, with event labels instead of raw ids (§2.2b) |
| | About | "Server 0.24.0" (`APP_VERSION`, compiled in) | `/updates`. There is no separate About page (M3) |
| | **Sign Out** | - | `POST /logout` |

`/settings` keeps its URL, so bookmarks still work.

**Locate (`/devices/locate`)** is a tab of its own. It is the one place for a phone's location and its remote
commands, and the only page that shows them. Its content is today's Locate page (M2); the mockup decides only the
look:

- **Phone switcher** at the top, one item per phone: "Anna · Emil · Ella".
  - These are plain links to `?device={id}`. Today's `onchange` select needs JS and goes away.
  - The state shows as a dot, so each item's `aria-label` also says it ("Anna, online", L3).
  - With one phone, that phone is shown at once.
  - With several and no `?device=`, the page lists the phones with "seen 10 min ago" ›.
  - The device summary's **Locate** quick action opens `/devices/locate?device={id}`, which switches to the Locate
    tab.
- **Map** (Leaflet, OSM tiles, unchanged) with the trail.
- **Under the map**, what today's page shows, with no place name (no reverse geocoding: a child's location never
  goes to a third party, M2):
  - the last fix as age and accuracy: "10 min ago · within 40 m";
  - the phone's answer to the last "Update Location" (`locate_result`);
  - the "Waiting for a fresh fix…" line and its JS poll;
  - **Update Location**.
- **Warnings** as orange rows above the actions: "Location is off for this phone", "Erasing works after the phone
  restarts once" (wipe not ready).
- **Actions**:
  - **Ring**, **Stop Ringing** and **Lock**, one tap each;
  - **Erase Phone…**, the type-the-name confirm, unchanged.
  - The footer: "An offline phone gets Ring and Locate within an hour, Lock and Erase whenever it's back." The Lock
    note goes behind ⓘ.
- **Location settings**, a group below the actions:
  - "When to locate" (Off / Only when asked / Regularly) and "Every" (interval) as two inline selects in **one form**.
    `location-policy` needs `mode` on every post (400 without it), so they can't be two forms (H1).
  - "Keep history for" as an inline select (its own form, `location-retention`).
- **Recent**: stacked rows, "Lock · today 10:58 · Done", with **Cancel** on a command that is waiting. Raw command
  ids become labels; times are local (§2.2b, M4).

### 3.7 Login, setup and enrolment

- Sign-in, two-factor and the forced password change keep their single-card layout, with the new name "Vibb Phone"
  (L4).
- **Add Phone** becomes a 3-step flow on the pages that exist today:
  1. **Name** (`/devices/new`).
  2. **How to set it up**: two large choices, "QR code (new or reset phone)" and "Code (phone already set up)".
     Today's "Two ways to scan it" text becomes these two choices, with the GrapheneOS and adb detail behind ⓘ.
  3. **The QR or the code**:
     - `/devices/{id}/provision`;
     - **New setup code** is a POST to `/devices/{id}/regenerate-code`, which shows the code page.
- Missing server setup (no checksum, no server URL) shows as one red row, "Server setup needed", with the env var
  detail behind ⓘ.
- `enroll_code.html`'s "Back to …" goes to Advanced › Connection (`/devices/{id}/advanced#connection`, where New
  Setup Code is for an enrolled phone; step 3, qa-31-s3-code #12) or the "Connect this phone" group, not
  `#access` (L5).

### 3.8 Shared builders: card builders, `attention::collect`, typed states

- **Card builders.** `render_device` builds all 21 cards in one function (`devices.rs:1139-1540`). It is split into
  card builders, one per sub-page. Some already exist: `time_card`, `play_card`, `parent_pin::card` and
  `music::device_card`. Each sub-page handler calls only its own builders (M1).
- **`attention::collect(state, device) -> Vec<AttentionItem>`.**
  - Each item has a kind, a level (alarm / action), a short title and a target URL.
  - It reads about 10 sources per phone: relock, parent PIN, play, `call_report`, sound, boots, policy problem,
    notification access, Google backup, kid lock, time state, and since step 3's fix round the Play notes and the
    escapes notes (Advanced's own builders, so the row and the page agree). Music has no row in §3.2's list.
  - As built (step 2): `attention::facts` gathers them into typed `Facts`, `attention::items` makes the rows; the
    device page passes the cards it has already built to `facts_of`.
  - The summary renders it. The Devices list shows its length as the badge.
  - A test checks that the list's count equals the summary's rows.
  - On the list this is about 10 small reads per phone. That is fine at 1-5 phones. Do not attempt a single query.
- **Typed states (M5).** Each builder that today returns a finished sentence gets a typed sibling. The sentence
  function stays until its last caller is gone; the step that moves the card switches the caller, then deletes the
  old function and its unit tests. The siblings:
  - `sound_mode::state() -> SoundState`;
  - `connection_state() -> ConnectionState` (§3.10);
  - `limits::LastAccess` (already typed);
  - typed results for `play`, `escapes`, `kid_lock`, `boot_witness`, `calls::call_report` and the music lines.
- **`display_time` (M4).** One module turns stored times into local text (§3.10).

### 3.9 Where every current control goes

Nothing is dropped. "→" names the new home; ⓘ means the text moves behind the disclosure.

| Today | New home |
|---|---|
| Status: time lock state | Summary header badge, only while locking; About This Phone › Time lock |
| Status: sound lines, last reported, app version | About This Phone (Sound, Last full report, Software); Do Not Disturb also → Attention |
| Access: last access line · Play: "Instant changes" and battery line · Crashes · unseen boots | About This Phone › Connection, Software, Diagnostics |
| Status: paused / foreign app / unseen boots / relock notes / blocks | Summary Attention + Quick actions |
| Lock the phone again now · Pause restrictions for 2 hours | Summary Quick actions ("Lock again", "Unlock for 2 h…" behind a confirm) |
| Allow these apps | Under the blocked-apps list on the Attention row's target, behind a confirm |
| `relock.notice` | The summary's flash, in Today (L6) |
| Parent-PIN notices, easy PIN, no-PIN card | Summary Attention → Lock Screen & PINs |
| Access card / Not enrolled card (incl. `access.typed_paused`) | Advanced › Connection (enrolled) / Summary "Connect this phone" (not enrolled) |
| Play and kiosk (lines, warnings, kiosk app block) | Advanced › App screens; "Restart once" warning → Attention |
| Launcher updates and notifications (3 switches, one form) | Advanced › Updates & notifications |
| Launcher crashes | About This Phone › Diagnostics |
| Time rules card (state, lift, +min, lifts table, edit link) | Summary (state, quick actions, today) + Device › Time (full); the edit link → Settings › Time Rules for everyone's rules |
| Time card warnings (incoming calls, ledger, launcher without rules, rules error) | Attention (L2) + Device › Time |
| Calls & SMS card | Summary row → Calls & Contacts |
| Calls page: default country code | Settings › Phone Setup (its own form) |
| Calls page: contact Remove | Calls & Contacts, behind a confirm (M6) |
| Apps card: list, Wi-Fi only, downloads line, corrupt warning | Device › Apps |
| Apps card: On the lock screen (incl. `lock_screen.unreadable`, `empty_text`, notes) | Lock Screen & PINs › Notifications (L6) |
| Apps card: Names and icons | Device › Apps, a `<details>` per app |
| Music card (everything, incl. Sonos) | Device › Music, as design 30 step 1 leaves it (M7) |
| Launcher card: language, apps per row (one form), no-notification-access warning | Home Screen; warning → Attention |
| Wallpapers card + Lock screen background | Home Screen (wallpapers) + Lock Screen & PINs (background) |
| Screen lock card (kid PIN; remove) | Lock Screen & PINs (remove behind a confirm) |
| Parent PIN card (+ events, `offline_override_used`) | Lock Screen & PINs (L6) |
| Screen timeout | Lock Screen & PINs |
| Phone hardening (9 switches, safe mode, backup to Google; one form) | Advanced › Restrictions |
| Quick Controls (4 boxes) | Home Screen › Quick controls (`section=quick`) |
| Ad/content filtering (VPN) switch | Device › Web Filter (`section=filter`) |
| Remove this device | Advanced, bottom |
| Locate page (all of it, incl. Stop ringing, Wipe, location mode / interval / history, Cancel, both warnings) | **Locate tab** (`/devices/locate`, same URL); the summary's Locate quick action links to it with `?device=` |
| DNS: upstream, blocklists, custom lists | Settings › Web Filter (the DNS server at the bottom) |
| DNS: per-device overrides | Device › Web Filter (`back=filter`) |
| DNS log (switch + table) | Device › Web Filter; Settings › Blocked Activity as the entrance |
| Schedules: global rules, global screen time | Settings › Time Rules (`/schedules`, same URL; the limits on `/schedules/screen-time`) |
| Schedules: per-device switch, screen time, rules (incl. `corrupt_rules`, `budget_corrupt`) | Device › Time (also linked from Settings › Time Rules › Phones) (L6) |
| Apps\|Music switch | Gone - Music is a tab |
| Music page: add link, own files, Storytel shelf link, Import from Vibb | Music › Add |
| Music page: per-entry Check now, Offline, ↑ ↓, Edit | Music › Library rows; ↑ ↓ in Edit mode |
| Music page: sweep interval, Storytel login, music app row, orphans | Music › Settings |
| Music page: categories | Music › Categories |
| Tracked app page (all cards) | App page top + Source & signing |
| Settings hub (6 rows + Sign out) | Settings (rebuilt, §3.6) |
| Wallpapers page (incl. the per-photo lock-screen switch) | Settings › Wallpapers (Q1: kept) |
| Provisioning page | Settings › Phone Setup (and linked from Add Phone) |
| Backups, Updates, Security, Account | Settings › Server, unchanged inside apart from copy |

### 3.10 "Online": which signal, which thresholds

No single signal is enough (§1.2b):
- A stream whose phone has vanished stays "open" until the server's TCP gives up. With a keepalive every 240 s,
  that is about 20-30 min (Linux `tcp_retries2`).
- The last report can be 50 minutes old on a perfectly connected phone (below).
- Both in-memory signals are empty after a server restart.

So the state combines them.

**Last contact** = the newest of three times:
- `limits::last_access(device)`: any authenticated request;
- `devices.last_seen_at`: the last status report;
- the `since` of an **open** stream.

The first is the freshest. The second survives a restart.

**The launcher's cadence, checked in the code** (H2):
- `push/SyncSchedule.kt`:
  - `BACKSTOP_INTERVAL_MS` = 30 min with the stream up;
  - `BACKSTOP_SSE_DOWN_INTERVAL_MS` = 15 min with it down;
  - `MIN_BACKSTOP_MS` = 9 min;
  - a reopened stream syncs at most every 10 min.
- `push/BackstopAlarm.kt:56` arms the backstop with `setAndAllowWhileIdle(ELAPSED_REALTIME_WAKEUP, …)`. That is an
  **inexact** alarm: the launcher targets SDK 36, and Android may deliver an inexact alarm up to 0.75 × its delay late.
- So a 30-min backstop can fire **52.5 min** after the last sync. A 15-min one can fire **26.25 min** after it, plus
  up to 9 min of while-idle quota in Doze.
- With the stream up, nothing else reaches the server on a quiet night. The keepalive goes from the server to the
  phone and updates nothing on the server.

| State | Rule | Header text | Why these numbers |
|---|---|---|---|
| **Online** (green) | A stream is open **and** last contact ≤ **60 min** ago | "Online · seen 3 min ago" | 30 × 1.75 = 52.5 min, plus margin. A healthy idle phone stays Online all night |
| **Away** (orange) | No open stream **and** last contact ≤ **45 min** ago | "Away · seen 12 min ago · changes may be delayed" | 15 × 1.75 = 26.25, plus the 9-min quota and margin. No number in the text: the real delay varies too much to promise one |
| **Offline** (grey) | Anything older, or a stream open but last contact > 60 min | "Offline · seen 2 h ago · changes wait until it's back" | The phone is off, out of coverage, or broken |
| **Disconnected** (grey) | Enrolled, but its access is revoked (no token) | "Disconnected · access removed" + "Connect again ›" | It can't come back by itself (L2) |
| **Not seen yet** (grey) | Enrolled, no contact ever | "Not seen yet" | (L2) |
| **Not set up** (grey) | Not enrolled | "Not set up" | - |

Notes for the build:
- **Two constants**, `ONLINE_WINDOW_MINUTES = 60` and `AWAY_WINDOW_MINUTES = 45`, next to the state function in
  `streams.rs`.
  - Each has its derivation in a comment that names `BACKSTOP_INTERVAL_MS`, `BACKSTOP_SSE_DOWN_INTERVAL_MS` and the
    0.75 inexact window. Nothing links the two files at compile time.
  - A launcher change to the backstop must revisit them.
- **Right after a server restart**, the memory is empty. A phone shows **Away**, or **Offline** if its last report
  is over 45 min old (as an idle phone's often is), until its stream reconnects: seconds after a clean stop, up to
  5 min after a crash or a power cut (no FIN reaches the phones; they reconnect after 300 s of silence). That is
  honest and harmless, so there is no special case (qa-31-s2-code L4).
- **The lock badge's end time and "stale" wording** (M4; the step 2 fix round, qa-31-s2-code M1-M3):
  - **The end shows only when it is provably right** (the parent's rule). The report has the rule's name and the
    phone's local `day`, not the window's end, so the end comes from the rules' days on the server, and only when
    all of these hold:
    - the phone reports its own time zone (`time_state.zone`) and it is the Phone Setup zone, since the windows are
      the phone's wall clock. **No launcher sends a zone yet, so no end shows today**; a launcher `zone` (or better,
      `active_until`) field is an API change in both directories, left out of design 31;
    - the report is fresh, from the server's local today, and its local time lies inside one window of the active
      rule (a report a few seconds after the end is outside it);
    - at that window's end no effective rule has a window running or starting (the same rule's next day, an all-day
      rule, a legacy morning/evening pair, an adjacent or overlapping rule), and the screen time isn't used up: the
      end is a real unlock.
  - An end on the next local day says so: "Leggetid until 07:00 tomorrow". On the same day it is "Skole until 14:30".
  - When the report's `day` isn't the server's local today, neither the badge's end nor the screen-time bar shows.
    The badge then says "Leggetid · at last report (yesterday 21:40)". An end already passed does the same.
  - Offline always adds "at last report". Away adds it once the report is older than 15 min (L2). A stale badge
    never has an end.
  - **The fallback zone** (Phone Setup's zone empty or unknown): no end; the report's `day` counts as current within
    a day of the UTC date, and the bar names the phone's day ("42 of 90 min on 13 Oct") instead of "today" (M2).
  - **"· calls off"** only while calls are managed and the launcher screens them (no "Calls aren't screened" or
    "Anyone can call this phone" in the call report), as the device page says (M3).
- **Local and relative time** come from `display_time` (M4):
  - It uses `chrono-tz` (MIT/Apache-2.0; the IANA data is compiled in, so the distroless image needs no
    `zoneinfo`), with the zone from Phone Setup (`provisioning_settings.time_zone`).
  - **An empty or invalid zone falls back to UTC**, and the times are then labelled "UTC". `valid_time_zone` checks
    only the characters, so "Europe/Olso" passes it and reaches this fallback.
  - The functions: `parse_db_utc`, `local(&tz)`, `relative(now)`, and an exact time for `title=`.
  - Relative time comes from the server's clock only. The phone's clock is never used, so a phone with a wrong clock
    can't fake "just now". The steps:
    - under 1 min: "just now";
    - under 60 min: "N min ago";
    - today: "N h ago";
    - yesterday: "yesterday HH:MM";
    - older: "12 Oct".
  - `parent_pin::when_text` is folded in.
- **The list page** uses the same function per phone: one in-memory lookup plus the `last_seen_at` it already reads.
- **Tests:** the state function is pure. Its inputs are the stream state, last access, last seen, the token and now.
  It gets table tests for each row above, and:
  - stream open with last contact 50 min ago = Online;
  - 61 min = Offline;
  - no stream at 30 min = Away;
  - no stream at 46 min = Offline;
  - revoked = Disconnected;
  - the restart case.

  `display_time` gets tests for:
  - Oslo DST in both directions;
  - an empty zone;
  - an unknown zone (UTC, labelled);
  - yesterday;
  - a stored time 2 min in the future.
- **On the emulator:** `adb shell dumpsys alarm | grep -A3 BACKSTOP` shows the alarm's window. Leave a phone idle for
  an hour, and expect Online throughout.

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
| Button | A verb + an object, at most 3 words ("Check now", "Lock again"); the quick actions may be shorter ("+15 min") |
| Empty state | 1 sentence + an action ("No contacts yet." + "Add contact") |

Things never shown outside an ⓘ panel or Advanced:
- package names, component names, file paths, env vars, shell commands;
- hashes, version codes, UTC suffixes;
- the words kiosk, MDM, tsnet, token, policy, sync, enrol(l), watcher, upstream.

### 4.3 Naming (glossary)

| Today | New |
|---|---|
| device | phone (the tab stays "Devices", the user's decision) |
| launcher / Handy's lock | the phone, or "Vibb" where the software must be named; the parent app is **Vibb Phone** |
| kid / the kid's PIN | child / **Child PIN** |
| parent PIN / parent code / offline override / unlock code | **Parent PIN** everywhere in the PWA (the phone's own words are quoted only in ⓘ) |
| Time rules | **Time Rules** (a Settings row), **rules** (things in it); a phone's own view is **Time** |
| Lift a rule | **Pause a rule** |
| +15 min screen time | **+15 min** (the quick-action grid; M2) |
| Pause restrictions for 2 hours | **Unlock for 2 h…** (behind a confirm, M6) |
| Lock the phone again now | **Lock again** (M2) |
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
| Needs attention / problems | **Attention** (M2) |
| Screen Time (as a device row) | **Time**; "Screen time" only for the daily limit (M2) |
| Show setup code | **New setup code** (a POST; the code shows once) |

### 4.4 The "i" disclosure pattern

```html
<label class="row">
  <span class="row-label">SMS</span>
  <input type="checkbox" role="switch" class="sw" name="sms_enabled" data-autosave …><span class="knob" aria-hidden="true"></span>
</label>
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
| 7 | `:183` (85) | "Changes reach the phone at once over its own connection to this server, which it keeps open. While that connection is down …" | Header status: **Online** / **Away · changes may be delayed** / **Offline · changes wait until it's back** (§3.10). ⓘ: "An offline phone gets changes as soon as it reconnects." |
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
- It restores the position when the response lands on the **same path** within 20 s **and without a #fragment**
  (`restoreTarget`, `scroll-restore.js:14-19`). It compares the path only, so `?notice=` redirects still restore.
- A redirect with a fragment lands at that anchor instead.

The rules for the redesign (H3):
1. **No picker pages for saving.** A single value is an inline `<select>` in its row (§3.0). A navigation row never
   leads to a page that exists only to save one value.
2. **A form's POST redirects to the one page its form lives on**, with no extra field. This covers:
   - a sub-page form → that sub-page;
   - the screen-time form → `/schedules/screen-time`;
   - the rule editor → the rule's page.
3. **Shared routes get a `back` field.** These are the routes that two pages post to. The handler checks `back`
   against an allow-list built from the device id (or a fixed list), and anything else falls back to the route's
   default. That is the pattern `music_entry.html:51` already uses (`back=entry`).

   | Route | Posted from | `back` values | Default |
   |---|---|---|---|
   | `/devices/{id}/lifts`, `/lifts/{lift}/end` (`lifts.rs:123,154`) | summary, Device › Time | `summary`, `time` | summary |
   | `/devices/{id}/end-windows`, `start-pause`, `allow-blocked` (`window_relock.rs:244`) | summary, the blocked-apps target page | `summary`, `apps` | summary |
   | `/devices/{id}/apps/toggle` (today `anchor=music`, `devices.rs:1797`) | Device › Apps, Device › Music | `apps`, `music` | apps |
   | `/dns/domains/new`, `/dns/domains/{id}/delete`, the blocklist override (`dns_filter.rs:292-311,505`) | Settings › Web Filter, Device › Web Filter | `dns`, `filter` (with the device id) | `/dns` |
   | `/settings/calls` (today → `/devices` without `device_id`, `calls.rs:946-950`) | Settings › Phone Setup | `provisioning` | `/settings/provisioning` |
   | `/schedules/rules[/{id}]`, `/delete` | the rule editor (everyone's or a phone's own) | the existing `scope` field decides | as §3.5 |

4. **Anchors only where they help.**
   - Keep a fragment where the redirect lands on a **different** page (a delete that returns to its list).
   - Keep one where the no-JS path needs it to find the row in a long list: `#app-{pkg}`, `#music-entry-{id}`,
     `#entry-{id}`.
   - Same-page auto-saves on short pages redirect without a fragment, so the script restores the exact position.
   - The flash's group id is the anchor when there is a flash (§3.0).
5. **Links that point at today's anchors** move with their targets (L5):
   - `#parent-pin` in `window_relock.rs` → `/devices/{id}/lock#parent-pin`;
   - `#app-{package}` in the blocks list → `/devices/{id}/apps#app-{package}`;
   - `music_entry.html:164` (`/devices/{id}#music`) → `/devices/{id}/music`;
   - `enroll_code.html:22` (`#access`) → §3.7;
   - `handlers/music.rs:3688` (`?music_notice=too_big…#music-entry-…`) → `/devices/{id}/music…`;
   - the `/music#…` anchors → §3.4.
6. **A redirect-table test** (H3, M8):
   - It renders every page and collects each form's `action` and hidden fields.
   - It posts each with valid values, and compares the `Location` against a table of
     `(form route, page) → expected Location`.
   - It is built from the rendered `action`s, so a moved form can't keep a stale target.
   - Each step adds its pages' rows.
7. `jstest/scroll-restore.test.js` and `device_api::pages_restore_scroll_after_auto_save` stay green. The latter
   walks **subfolders** of `templates/` too (M8), so a template in `templates/device/` can't skip the head partial.

### 5.2 Without JS

Today, without JS:
- Every auto-saving control has a `<noscript>` Save button.
- The confirms use `<details>` (wipe, revoke, restore, format, remove).

The redesign keeps both and **fixes the gaps** (§2.3 item 10):
- Device pickers become links, so they need no JS.
- The active tab is set by the template (§3.1).
- The `onsubmit confirm()` deletes become `<details>` confirms (Q7, §5.6).
- The ⓘ panels and the "Pause a rule…" and "Unlock for 2 h…" sheets are `<details>`.
- Switches are real checkboxes.
- **`autosave.js` (L7):**
  - The 54 inline `onchange="this.form.submit()"` become `data-autosave`, handled by one
    `static/autosave.js`. It calls `form.submit()`, which `scroll-restore.js` already hooks; that script loads
    un-deferred first, and `autosave.js` right after it, also un-deferred, so a control changed while a long page
    is still loading saves too (qa-31-s1-code L1).
  - A `<select>` saves on `change` from a pointer. From the keyboard it saves on Enter or blur, not on every arrow
    key; a choice confirmed with Enter in an open dropdown saves at once. This settles §5.4's open decision.
  - CSP still allows inline script "until S7" (`admin_auth.rs:442-447`). This removes most of what needs it.
- No new behaviour may depend on JS. JS stays limited to these extras:
  - `autosave.js`;
  - `app-display.js` (preview);
  - `music-cards.js` (live status);
  - the Leaflet map;
  - the locate poll;
  - the installing auto-reload;
  - the rule editor's kind → calls link.

### 5.3 PWA manifest, name and offline

**The name is "Vibb Phone"** (the user's final decision, replacing Q6). L4 says what changes:
- `<title>{{ title }} · Vibb Phone`, the header brand, `login.html`'s heading;
- `apple-mobile-web-app-title`;
- the manifest:
  - `name` **and** `short_name` both become "Vibb Phone". It is 10 characters, under the about 12 that Android and
    iOS show beneath an icon.
  - `description` becomes "Parent controls for the family's Vibb phones".
- README, `DEPLOY.md`, and the backup error message's brand (`backups.rs:1016`);
- the log line in `main.rs:179`, which may change freely.

What does **not** change:
- **Add `"id": "/"`** to the manifest. It equals today's implicit id (taken from `start_url`), so nothing
  reinstalls, and later changes can't orphan the app.
- **The manifest URL stays** `/static/manifest.webmanifest`, with no `?v=`. Older Chrome keys a WebAPK to it.
- `start_url "/"`, `scope "/"` and `display standalone` stay.
- **The 2FA issuer** (`security.rs:155`, "Kids Device MDM") stays (the user's decision). It only labels *new*
  authenticator entries, and changing it never breaks codes.

**What installed apps see**, and the **release note** to ship with the rename step:
> The parent app is now called **Vibb Phone**. On Android, the home-screen name updates by itself within a few days
> (Chrome may ask you to accept it). **On iPhone and iPad the icon keeps its old name**: remove it and add it again
> from Safari (Share › Add to Home Screen), or rename it by hand. Nothing else changes: you stay signed in, and your
> bookmarks keep working.

**Theme colour:** `theme_color` becomes the bar colour. Add two `<meta name="theme-color" media="(prefers-color-scheme:
…)">`, light `#F9F9FA` and dark `#161618`.

**Offline:** `templates/sw.js` stays **deliberately without a cache and without a fetch handler**. The admin handles
session cookies, so there is no offline mode and the redesign adds none. The rename and the CSS can't serve stale
pages.

The status-bar style (`apple-mobile-web-app-status-bar-style`) stays `default`. If it ever becomes
`black-translucent`, add `viewport-fit=cover` and top and bottom safe-area padding, and test on a real iPhone (M9).

### 5.4 Accessibility

- Every control has a visible or programmatic label. Switches use `role="switch"` inside a `<label>`.
- **Touch targets** are at least 44×44 px, including the ⓘ summary (the mockup's 28 px ⓘ gets a padded hit area, L3)
  and the tab bar items.
- **Contrast:**
  - Text is at least 4.5:1 in both themes. QA checked the mockups' text colours and they pass.
  - These fixes from L3 apply:
    - **The strong button in dark mode:** white on `#4A9BFF` is 2.83:1 ("Lock again"). Use `#0B66D6` in both themes
      (5.4:1).
    - **Switch tracks** need 3:1 for the state (WCAG 1.4.11). On = `#248A3D` (4.4:1). Off keeps its fill and gets a
      1px `#8E8E93` border.
    - **The lock badge's** colours become tokens with dark values; the mockup hard-codes `#EEF0FF`/`#3A36B8` in the
      dark frame too.
- **Locate's phone switcher** says the state in each item's `aria-label` ("Anna, online"), not only as a dot (L3).
- Font sizes are in `rem`, never below 0.75rem (12px). The tab labels go up from 0.68rem.
- The current tab has `aria-current="page"`. Nav icons get `aria-hidden="true"`.
- Status changes that `music-cards.js` swaps in keep `role="status"`.
- Auto-saving selects follow `autosave.js` (§5.2): no save on every arrow key.
- Theming follows `prefers-color-scheme`, with no manual toggle. `prefers-reduced-motion` turns off the switch
  transition and any other transitions.
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

The tests fetch `/devices/{id}` about 110 times across 24 files (M1). Many builders in `src/*.rs` are also pinned by
`#[cfg(test)]` unit tests, which these counts don't include (M5).

**Step 1 adds helpers to `tests/mod.rs`** (M8), and converts the fragile assertions to them:
- `input_checked(html, name, value) -> Option<bool>`. This replaces assertions on attribute order and whitespace,
  such as `checked onchange="this.form.submit()"` (`hardening.rs:173-180`, `music.rs:2395-2557`,
  `lock_glance.rs:497`) and `value="on"  onchange` (with two spaces).
- `autosaves(html, name) -> bool`, true for `onchange` or `data-autosave` (L7).
- `form_action_of(html, input_name)`.
- `nav_current(html) -> &str` (§3.1).
- `device_page(id, Section) -> String`, which returns the URL, so a moved test changes one argument (M1).
- `assert_redirect(response, path, fragment)`, which drives §5.1's redirect table and replaces the pinned `Location`s
  (`enrollment.rs:355,372`, `parent_pin.rs:68`, `music.rs:906-2606`).

How to handle the rest:
- Each step rewrites its pages' tests in the same commit, through these helpers.
- A test that checks a **fact** ("the page says the phone is paused") asserts on a stable `data-` hook plus a short
  phrase, never a whole sentence.
- The unit tests of a sentence builder go when the builder goes, in the step that switches its last caller to the
  typed sibling (M5).
- Tests that pin **security** behaviour stay exact: `crash_reports_reach_the_device_page_without_free_text`, the
  `js_string` tests, and H2's escaping tests.
- `app_display.rs:308` and `device_api.rs:802` read template files directly; they follow the partials.

### 5.6 Risky to change

**Forms whose handler writes every field (H1).** An unticked checkbox sends nothing, so these handlers read a
missing field as off, empty or default. **A form is split only if its handler writes only the sections it was
sent.**
- A split form gets a hidden section marker, `<input type="hidden" name="section" value="…">`, as design 30 did
  with `v=2`. The handler then updates only that section's columns.
- Without a marker, today's "write all" stays, for old tabs and the existing tests.
- Every other form moves **as one block**: one group, one form, and each switch in it still auto-saves the whole
  form.
- Each split gets a test that saving one section leaves the other section's stored values unchanged.

| Route | Fields written | Absent means | In the redesign |
|---|---|---|---|
| `/devices/{id}/policy` (`devices.rs:2009-2023`) | 4 quick controls + web filter | off | **Split**: `section=quick` (Home Screen) and `section=filter` (Web Filter). `kiosk_desired = 1` is kept in both |
| `/schedules/device/{id}` (`schedules.rs:665-680`, `parse_budget_form` `:623`) | own-rules switch **and** 7 daily limits | a limit `""` = **no limit** | **Split**: `section=switch` and `section=budget`. Test: a switch-only post keeps the limits |
| `/devices/{id}/launcher` (`devices.rs:2235-2239`) | language, apps per row | `system`, `3` | One form (two inline selects in one group). Gets `section` only if a later page separates them |
| `/devices/{id}/hardening` | 10 restrictions | off | One form (Advanced › Restrictions) |
| `/devices/{id}/kiosk-escapes` (`device_detail.html:194-208`) | update fence, notification filter, boot cover | off | One form (Advanced › Updates & notifications) |
| `/devices/{id}/music` (design 30 step 1: `v=2`, `mobile_switches`) | stream data, download data, storage limit, volume cap, Storytel | off / none | One form with `v=2` (M7) |
| `/devices/{id}/music/sonos-rooms` | all + rooms | off | One form |
| `/devices/{id}/calls/settings` (`calls.rs:535-550`) | managed, calls, SMS, message button | off | One form |
| `/devices/{id}/contacts/{cid}` (`calls.rs:732-734`) | in, out, home, message app, Matrix ID | off | One form per contact row; no sheet for the Matrix ID |
| `/schedules/rules[/{id}]` (`schedules.html:9-48`) | name, kind, calls, 7 days, exempt apps | off / not exempt | One form; the exempt apps in a `<details>` inside it |
| `/schedules/global` | 7 daily limits | no limit | One form (`/schedules/screen-time`) |
| `/devices/{id}/location-policy` (`locate.rs:530-560`) | mode (required: 400 without it), interval | interval kept | One form with two inline selects |
| `/devices/{id}/wallpapers` | the ticked wallpapers (repeated `wallpaper`) | unticked | One form (the grid) |
| `/settings/provisioning` (`settings.rs:91-117`) | server URL, Tailscale key, language, time zone | empty (the key: kept) | One form; the country code is a second form beside it (L6) |
| `/music/entries/{id}` | name, category, order, offline, resume | resume off | One form |
| `/apps/tracked/{id}` | name, repo, file filter, tag prefix | empty | One form |
| `/update/schedule`, `/system/schedule`, `/backups/schedule` | frequency, day, time, checkboxes | off | One form each |

**Actions behind a confirm (M6, Q7).** These get a `<details>` confirm: one sentence plus a POST button naming the
action.
- **One tap today, risky:**
  - **Unlock for 2 h…** (`start-pause`): opens apps, Settings and the Play Store.
  - **Remove Child PIN…**: turns the lock off.
  - **Remove Contact…**: their calls are then blocked.
  - **Allow these apps…**: lets out apps that may be signed with a foreign key. The confirm names them.
  - **Forget Package and Key…** on a catalog app: the next release's key is then trusted on first use.
  - **Sign Out Everywhere…**: signs this browser out too.
- **Today a JS `confirm()`, so they act at once without JS:**
  - delete rule;
  - delete category;
  - delete wallpaper;
  - delete music file;
  - delete music entry;
  - delete orphans;
  - remove the Storytel login.
- **Already `<details>`, unchanged:** wipe, revoke, restore, format, remove phone, remove app, reset 2FA, update,
  restart, reboot.
- **One tap on purpose**, because they protect the phone or can be undone: Lock again, Lock, Ring, Stop Ringing,
  +15/+30/+1 h, End now, Remove photo (re-upload), domain Remove (re-add), Unban.

**Other risks:**

| Risk | Why | How to handle |
|---|---|---|
| Design 30 step 1 (M7) | It changes the Music card's form (`v=2`, two mobile-data switches, a storage limit, `data_note`), and step 3a changes `MusicState` and `storage_line` | Merge `design-30-s1` into main **before** the device Music step. Build that step on it. Don't reword music lines until step 3a is in main. Repoint QA-30's tests (`tests/music.rs`) in the same step |
| Allowlist forms (`apps/toggle`) | Per-app mini-forms carry `tracked_app_id`/`package_name`; `id="app-{package}"` is a link target | Keep the fields and ids; add `back` (§5.1) |
| The app-display 400 path | Re-renders the whole device page with the entered values (`devices.rs:685`); tests assert `id="display" open` | Re-render Device › Apps instead, in the Apps step |
| Parent PIN / Child PIN | The parent-PIN form can also clear (`clear_pin`); the kid-PIN form posts to the same route for set and clear | Keep the route semantics; only move |
| The 3 s auto-reload while installing | Lives on the device page | Moves to Device › Apps; never runs on the summary |
| `music-cards.js` | Finds `.music-status[data-entry]` and anchors on `.music-card, .card` | Keep the classes, or update the script in the same commit |
| `app-display.js` | Finds `form.app-display-form` and `data-preview-*` | Keep the partial's markup |
| Locate JS | `locations.json`, `locate-result.json`, `location.replace('/devices/locate?device=…')` | The URL stays; recheck after the layout change |
| Local time (M4) | No tz database today (`Cargo.toml:21` has `chrono` only); DB stamps have no zone | `chrono-tz` + `display_time` (§3.10); new dependency checksums are not an issue on the server (Cargo.lock) |
| Askama | Templates compile into the binary; a missing variable is a build error | Build after every page |
| `style.css?v=7` | Browsers cache `/static` hard | Bump `v` with every CSS change |
| The list page's status | About 10 reads per phone (§3.8) | Fine at 1-5 phones |
| Security events | Some routes log security events from the same handlers | Moving forms doesn't change them; don't rename POST routes |
| CSRF | `csrf_guard` covers every non-GET (`main.rs:1171`) | Fine as long as **no GET changes state** (no saving picker pages, "New setup code" is a POST) |

### 5.7 The browser floor (M9)

- **The floor:** Chrome/WebView 80+ and iOS Safari 14+ render the design. Anything older gets plain, working
  controls. Today's CSS already assumes custom properties, flexbox `gap`, grid and `env()` (`style.css:1-28,228,287,370`).
- **Not used:** `:has()`, `color-mix()`, CSS nesting, `@layer`, container queries, or `dvh` without a `vh`
  fallback.
- **The switch:**
  - a real checkbox (`-webkit-appearance:none; appearance:none`) followed by a sibling `<span class="knob">` drawn by
    CSS. Never a pseudo-element on the input: the mockups' `::before` knob becomes this sibling span.
  - `@media (forced-colors: active) { .sw { appearance: auto } .knob { display: none } }`, so high-contrast modes
    show a real checkbox.
  - Focus is `:focus` with an outline, reset by `:focus:not(:focus-visible)`, so Safari before 15.4 still shows
    focus.
- **Hairlines** are 1px in a lighter colour. 0.5px is used only under `(min-resolution: 2dppx)`.
- **Dark mode:** `:root { color-scheme: light dark }` and `<meta name="color-scheme" content="light dark">`, so native
  selects, time inputs and scrollbars follow it.
- `prefers-reduced-motion` turns off the switch transition.
- **The translucent tab bar** has an opaque fallback colour.
- `register-sw.js` is rewritten in ES5 (it has an arrow function today).

---

## 6. Mockups

The user approved 14 artboards in `docs/design/mockups/parent-pwa/` (`canvas.json` lists them):
- Main (Devices);
- DeviceSummary;
- HeaderStates;
- About;
- LockScreen;
- Apps;
- AnnaApps;
- Locate;
- Music;
- Settings;
- Time;
- RuleEditor;
- Patterns;
- TabBar.

The data is realistic and taken from the code and tests: phones **Anna**, **Emil** and **Ella**; rules **Skole** and
**Leggetid**; library entries **Abels tårn**, **Ukas nyheter**, **Godnatt**; the app **Element X shown as "Chat"**.

### 6.0 The rule: the mockups decide the look, §3 decides the content (M2)

- **Layout, type, colour, spacing and grouping** come from the mockups.
- **Which controls exist, what they do and what the facts say** come from §3 and §3.9.
- **Copy facts** come from today's text or the code, never from a mockup's placeholder text.
- **Every control the mockups leave out is restored.** Every control they invent is dropped, unless the code already
  has it under another name.
- **Where a mockup's label and the spec's differ**, §4.3 picks one.

What this means, artboard by artboard:

| Artboard | In the mockup | In the build | Why |
|---|---|---|---|
| DeviceSummary | "Needs attention" | **Attention** | §4.3 |
| | "Screen Time · Everyone's rules" | **Time** | "Screen Time" reads as the limit alone (§4.3) |
| | "+15 min", "Lock again", "Locate" | Kept as drawn | §4.3 adopts the short labels |
| | "Unlock for 2 h" not drawn (a pause is running) | **Unlock for 2 h…** behind a confirm when no pause runs | M6 |
| | No Location row | Kept out; the Locate quick action is the entrance | M2, Q4 |
| | "PIN on" | Kept | Fits the value column |
| HeaderStates | "Away · seen 12 min ago" | Kept; "· changes may be delayed" only on the summary | H2 |
| | "Waiting for setup" + **"Show setup code"** | **New setup code** (a POST that makes a new code and shows it once) | The code can't be shown again (M2) |
| | - | Two more states: **Disconnected** and **Not seen yet** | L2 |
| About | Last contact / Last full report / Network, Starts without Vibb | Kept, plus **Time lock** | M2 |
| LockScreen | "After 5 wrong tries the phone waits 1 minute … unlock it from here with **Unlock Now**" | "After 5 wrong PINs the phone waits 30 s, then 1, 2, 5 and 15 minutes between tries. It never erases itself. Your Parent PIN ("Parent code" on the lock) always opens it." | Facts from `device_detail.html:571`. No "Unlock Now" control exists in the PWA; today's text names the Parent PIN as the way in |
| | Parent PIN footer "Opens settings and the Play Store on the phone for 15 minutes." | "Opens the phone's settings and lifts limits, even offline." ⓘ: install mode, the time-lock lift "for up to two hours", and the 15-minute lockout after 5 wrong tries | Facts from `device_detail.html:602-603` |
| | **"Show what's playing"** switch | **Dropped.** The lock always shows what's playing; the footer says so | No such setting (M2) |
| | "Turn off after · 1 minute ›", "Background · Same as Home ›" | Inline selects | H3 |
| | "Child PIN · On · 4 digits ›" | The row opens a `<details>` with the change form; Remove Child PIN… behind a confirm | H3, M6 |
| | "Missed calls always show." | Kept | True (`device_detail.html:352`) |
| AnnaApps | **Search** field | **Dropped** | Not in the code, and it needs JS (M2) |
| | Allowed / Not allowed groups | Kept | - |
| Apps | Catalog rows, footer | Kept; badges as §3.3 | - |
| Locate | **"Rosenhoff skole"** | **"10 min ago · within 40 m"**, no place name | No reverse geocoding: the location never goes to a third party (M2) |
| | Ring, Lock, Update, Recent | Plus **Stop Ringing**, **Erase Phone…**, the **Location settings** group, **Cancel** on a waiting command, the two warnings, and the phone's last answer | Restored (M2) |
| Music | "Show all 9" | **Dropped**; the whole library is listed | Invented |
| | Add: link, own files, Storytel | Plus **Import from Vibb ›** | Restored (M2) |
| | Library rows without row actions | Plus **Check now** and the **Offline** select on each row | Restored (M2) |
| | "Categories · 3", "Settings" | Kept | - |
| Settings | "Software Update · 0.25.0" pill | **No value** | M3 |
| | "About · Server 0.24.0" | Kept; links to `/updates` | M3 |
| | "Blocked Activity · 12 today", "Wallpapers · 5", "Backups · Today 03:00" | Kept, from the database | M3 |
| Time | Phone rows link to the summary | They link to `/devices/{id}/time` | M2 |
| | "Daily limit ›" | Kept: a page with the 7 fields and Save, redirecting to itself | H3 |
| RuleEditor | "Type · School ›" | An inline select | H3 |
| | "Exceptions · Apps that still work ›" | A `<details>` inside the form | H1 |
| | "Off" / "Add" on empty days | Look only: empty time fields | No GET may change state |
| Patterns | "Phone not locked: **Vibb lost device owner**" | **Dropped**, replaced by a real alarm, "Lock is off on the phone" (`lock.warnings`) | No device-owner signal exists (M2) |
| | "**2 new apps wait for your OK**" | **"2 blocked apps wait"**, from `relock.blocks` | Exists under another name: apps that stay hidden until "Allow these apps" |
| | "New child PIN · Use 4 to 8 digits." | "Use 4 to 6 digits." | `pattern="[0-9]{4,6}"` |
| | Switch knob as `::before` on the input | A sibling `<span class="knob">` | M9 |
| | Picker "Music storage limit" (inline select) | Kept: this is the picker pattern | H3, M7 |
| All | Strong button `#4A9BFF` in dark, switch on `#34C759`, ⓘ 28 px, hard-coded lock-pill colours | As §5.4 | L3 |

### 6.1 What each artboard shows (for reference)

- **M1 Devices (Main):** the password row, Anna (Online · Skole until 14:30, bar, badge 1), Emil (Offline · seen 3 h
  ago), Ella (Away · seen 12 min ago), Add Phone. Tab bar: Devices · Apps · Locate · Music · Settings.
- **M2 Device summary (DeviceSummary):** header, Attention, Quick actions, Today, Sections, as §3.2.
- **M2b Header states (HeaderStates):** Online with a lock badge, Away, Offline, Not set up. Disconnected and Not seen
  yet are added in the build (L2).
- **M2c About This Phone (About):** as §3.2's table.
- **M3 Lock Screen & PINs (LockScreen):** as §3.2, with the fact fixes above.
- **M4 Apps (Apps) and Anna › Apps (AnnaApps):** as §3.3, without search.
- **M5 Music (Music):** as §3.4, with Import from Vibb and the row actions.
- **M6 Settings › Time Rules (Time) and the rule editor (RuleEditor):** as §3.5.
- **M7 Settings (Settings):** as §3.6.
- **M8 Locate (Locate):** as §3.6, with every restored control.
- **M9 Patterns and the tab bar (Patterns, TabBar):** as §3.0 and §5.7.

---

## 7. Questions for the user: answered

1. **The two lock-screen picture settings.** **Keep both.** The per-photo "Also on the lock screen" switch stays on
   Settings › Wallpapers, and the per-phone "Background" stays on Lock Screen & PINs.
2. **The DNS server choice.** **Kept**, at the bottom of Settings › Web Filter. `dns_upstream_provider` stays in the
   policy.
3. **"This is the launcher app".** **Kept visible** in Source & signing (`is-launcher` unchanged).
4. **A Locate quick action on the summary.** **Kept**, as a plain link to `/devices/locate?device={id}`. Locate is
   also a tab, so the summary has no separate Location row (M2).
5. **Times.** **Local time**, from the Phone Setup time zone, through `chrono-tz` (M4). An empty or invalid zone falls
   back to UTC, labelled "UTC".
6. **The name.** **"Vibb Phone"** (the user's decision), with the manifest identity kept (§5.3, L4).
7. **Deletes without JS.** **Yes.** They get the `<details>` confirm, along with the risky one-tap actions in §5.6
   (M6).

---

## 8. Build steps

There are 15 steps, adopted from the design QA with small adjustments. Each step ships together:
- its routes and templates, with their copy rewritten to §4;
- the Rust texts its pages show, switched to typed values (M5);
- its forms' rows in the redirect-table test (§5.1);
- its tests, repointed through the helpers (§5.5);
- these checks:
  - **390 px wide, light and dark, and once with JS off**: every save stays in place, and every confirm works;
  - `cargo fmt --check`, `cargo clippy --all-targets`, `cargo test`, and `node --test` for the jstests;
  - the docs its pages rename (L8: `server/DEPLOY.md`, `docs/testing/emulator.md`, `docs/setup/google-account.md`,
    `server/CLAUDE.md`, `launcher/CLAUDE.md`, `PLAN.md`).

Between steps the PWA mixes old-style and new-style pages; each page is whole. The device page shrinks one card group
at a time. Each moved card leaves a navigation row behind until step 10 turns what remains into the summary (M1).

1. **Shell.**
   - **Routes:** `/settings` rebuilt as the hub.
   - **Templates:**
     - `partials/head.html`: the name, the theme-colour metas, `color-scheme`, and `style.css?v=8`;
     - `partials/app_header.html`: 5 tabs, set per template (L1), with the active-tab script deleted;
     - every template sets `tab`;
     - `settings_hub.html`;
     - `apps_list.html` and `music.html` lose the Apps|Music switch;
     - `login.html`.
   - **Static files:**
     - `style.css`: the new tokens (light and dark) and components (grouped list, rows, switch, ⓘ, badge, Attention
       levels, flash, confirm). Today's `.card`, `.error` and `.button-*` are restyled with the tokens, so old pages
       look like the same app. Plus the browser floor (§5.7) and the L3 colours.
     - `manifest.webmanifest`: "Vibb Phone" and `"id": "/"`.
     - `register-sw.js` in ES5.
     - `autosave.js`, with every `onchange` turned into `data-autosave` (L7).
   - **Rust:**
     - the hub's cheap values (M3);
     - `APP_VERSION` for About;
     - the backup error message's brand;
     - the `main.rs:179` log line.
   - **Tests:**
     - the helpers (M8);
     - `nav_current` per tab;
     - the head check walks subfolders;
     - a test that the hub makes no network call (no GitHub).
   - **Release note:** the iOS rename note (§5.3).
2. **Display helpers + Devices list.**
   - **Rust:**
     - `display_time` with `chrono-tz` (M4);
     - `connection_state` with the H2 windows and the L2 states (§3.10);
     - `attention::collect` (§3.8).
   - **Templates:** `devices_list.html`: the dot, state, "seen …", lock part, bar and badge.
   - **Tests:**
     - the pure table tests of §3.10;
     - the `display_time` tests;
     - attention count = summary rows (a stub summary until step 10).
3. **About This Phone + Advanced.**
   - **Routes:** GET `/devices/{id}/about` and `/devices/{id}/advanced`.
   - **Forms moved:** `hardening` (one form), `kiosk-block`, `kiosk-escapes` (one form), `regenerate-code`,
     `revoke`, `delete`.
   - **Templates:** `device_about.html`, `device_advanced.html`; their cards on `device_detail.html` become two rows.
   - **Rust texts** (typed siblings, M5):
     - `instant_changes_line` → `ConnectionState`;
     - `last_access_line` → `LastAccess` rows;
     - `sound_mode::state`;
     - the play and escapes lines;
     - `boot_witness`;
     - the policy problem titles.
   - **Tests:** `hardening.rs`, `step11.rs`, `play.rs`, `sound_mode.rs`, `cleanup.rs` (crashes; its security pin
     stays exact), `enrollment.rs` (the `#access` redirects).
4. **Lock Screen & PINs.**
   - **Routes:** GET `/devices/{id}/lock`.
   - **Forms moved:** `kid-lock` (set; clear behind a confirm, M6), `parent-pin`, `screen-timeout` (inline select),
     `lock-wallpaper` (inline select), `lock-screen`, `lock-screen/apps`.
   - **Templates:** `device_lock.html`.
   - **Rust texts:**
     - `kid_lock` lines and warnings, typed;
     - the `parent_pin` notices and events in local time;
     - `lock_glance` notes;
     - flashes inside their groups.
   - **Tests:** `parent_pin.rs` (`#parent-pin` → this page), `lock_glance.rs`, `step10.rs`, `window_relock.rs:687`'s
     link.
5. **Home Screen + Web Filter.**
   - **Routes:** GET `/devices/{id}/home` and `/devices/{id}/filter`.
   - **Forms:**
     - `policy` gets `section` (H1);
     - `launcher` stays one form;
     - `wallpapers`;
     - the `dns` overrides and domains get `back` (§5.1);
     - `/dns/log/{id}`.
   - **Redirects:** `/dns?device=` and `/dns/log?device=` redirect to the filter page (L5).
   - **Templates:** `device_home.html`, `device_filter.html`.
   - **Tests:**
     - `launcher_ui.rs`, `wallpapers.rs`, `dns_blocklist.rs`;
     - a section test for `policy`;
     - the redirect tests.
6. **Device › Apps.**
   - **Routes:** GET `/devices/{id}/apps`.
   - **Forms:** `apps/toggle` (with `back`), `app-updates`, `apps/display` (its 400 re-render targets this page).
   - **Templates:** `device_apps.html`. It holds the installing reload. Allowed / Not allowed; `#app-{pkg}` kept; no
     search.
   - **Rust texts:** `app_downloads` labels (grammar fixed), and the relock blocks' links point here.
   - **Tests:** `app_display.rs`, `tracked_apps.rs` (device rows), `catalog_pins.rs`, `window_relock.rs`
     (`#app-…`).
7. **Device › Music**, built on main **after `design-30-s1` is merged** (M7).
   - **Routes:** GET `/devices/{id}/music`, chained with its POST.
   - **Forms:** the 5-field form with `v=2` as one group, the ticks, Sonos, and `apps/toggle` with `back=music`.
   - **Redirects:** `music.rs` device redirects (`#music-entry-…`, `?music_notice=…`); the `music_entry.html` link and
     its hint "(the phone's Music page)".
   - **Templates:** `device_music.html`.
   - **Rust texts:** moved and restyled, **not reworded**, until design 30 step 3a is in main.
   - **Tests:** `music.rs` (including QA-30's additions), `music_storytel.rs` (device parts).
8. **Calls & Contacts.**
   - **Routes:** unchanged.
   - **Forms:**
     - `calls/settings` and each contact row stay one form each (H1);
     - Remove Contact… gets a confirm (M6);
     - the country code moves to Phone Setup as a second form, with `/settings/calls` taking `back=provisioning` (L6).
   - **Templates:** `device_calls.html` in the new components; `provisioning_settings.html` gains the country code.
   - **Rust texts:** `calls::call_report` typed, with no package names (§2.2b).
   - **Tests:** `calls.rs`.
9. **Settings › Time Rules + Device › Time.**
   - **Routes:**
     - GET `/schedules/screen-time`;
     - GET `/schedules/rules/{id}` and `/schedules/rules/new`;
     - GET `/devices/{id}/time`;
     - `/schedules?device=` redirects (L5).
   - **Forms:**
     - `schedules/device` gets `section` (H1);
     - the rule editor is one form (kind as an inline select, exempt apps in a `<details>`, Delete Rule… confirm);
     - `lifts` with `back`.
   - **Templates:** `schedules.html` (the Everyone page), `schedule_budget.html`, `schedule_rule.html`,
     `device_time.html`.
   - **Rust texts:** `time_state_lines` → typed state; the badge rules of §3.10; lift statuses in local time (M4).
   - **Tests:**
     - `time_rules.rs`;
     - a section test (a switch-only post keeps the limits);
     - redirect tests.
10. **The summary.**
    - **Routes:** `/devices/{id}`.
    - **Templates:** `device_detail.html` becomes the summary:
      - header;
      - Attention from `attention::collect`;
      - Quick actions ("Unlock for 2 h…" and "Allow these apps…" behind confirms, "Pause a rule…" as a sheet, the
        Locate link);
      - Today (with `relock.notice`);
      - Sections;
      - the not-enrolled variant.
    - **Rust:**
      - the card builders nothing uses are deleted;
      - the foreign-app and blocks texts shortened and typed;
      - the old sentence functions and their unit tests deleted (M5).
    - **Tests:** `window_relock.rs`, `device_api.rs`, `command_receipts.rs` (restart once), `step9.rs`, and the
      attention count.
11. **Apps tab.**
    - **Templates:** `apps_list.html` (On phones, catalog rows, badges, footer, Add App), `tracked_app_detail.html`
      (top, Show as, On phones, Source & signing, the Forget… confirm, Remove), `tracked_app_add.html` (More
      options).
    - **Rust texts:** sync failures as a short title, with the raw error behind ⓘ.
    - **Tests:** `tracked_apps.rs`, `catalog_pins.rs`.
12. **Music tab.** May be split into 12a (the tab) and 12b (the entry page and the shelf).
    - **Routes:** GET `/music/settings`, `/music/categories` and `/music/import`, each chained with its POST.
      `POST /music/import` renders the import page. The `/music#…` redirects move (L5).
    - **Templates:** `music.html` (library rows with Check now and Offline, Edit mode, Add, On phones, links),
      `music_settings.html`, `music_categories.html`, `music_import.html`, `music_entry.html`, `music_storytel.html`.
      `music-cards.js` selectors are kept, or updated together.
    - **Confirms:** every `confirm()` delete becomes a `<details>` confirm.
    - **Tests:** `music.rs`, `music_storytel.rs`, `music_sweep.rs`, `music_art.rs`.
13. **Locate tab.**
    - **Routes:** `/devices/locate` (unchanged).
    - **Templates:** `device_locate.html`:
      - phone links with `aria-label`s;
      - the map, the last fix as age and accuracy, the phone's answer, the waiting line, Update Location;
      - warnings as orange rows;
      - Ring, Stop Ringing, Lock, Erase Phone…;
      - Location settings (mode + interval as one form, history);
      - Recent with Cancel.
    - **Rust texts:** `DeviceCommand::status_line` and the command names → labels in local time; `describe_fix`.
    - **Tests:** `locations.rs`, `command_receipts.rs`, `command_expiry.rs`.
14. **The rest of Settings.**
    - **Templates:** `dns_filter.html` (global; the DNS server at the bottom), `dns_log.html` (a list of phones),
      `wallpapers.html` (`<details>` delete; per-photo switch kept), `provisioning_settings.html` (Phone Setup),
      `account.html` (the Sign Out Everywhere… confirm), `security.html` (event labels), `backups.html`,
      `updates.html`.
    - **Rust texts:** security event labels; the update and backup messages.
    - **Tests:** `admin_auth.rs`, `intake_limits.rs`, `backup_secrets.rs`, `docker.rs`, `provisioning.rs`.
15. **Add phone, sign-in, clean-up.**
    - **Templates:** the 3-step add flow (`device_add.html`, `provision_qr.html`, `enroll_code.html`, with "Server
      setup needed" + ⓘ), and the sign-in pages.
    - **Static files:** the old CSS classes nothing uses are deleted.
    - **The brand bar** ("Vibb Phone", 44 px, kept on every page since step 1) leaves the tab landing pages, which
      open on their large title as the mockups draw them; by then every inner page has its sub-page header.
    - **Docs:** the remaining cross-references (L8).
    - **Tests:** `provisioning.rs`, `enrollment.rs`, `edge.rs`.

---

## 9. Design QA

The design QA (`docs/design/qa-31-design.md`, 2026-10-13) found 3 High, 9 Medium and 8 Low issues. All were accepted.
This revision makes the following changes:

| # | Finding | Change |
|---|---|---|
| H1 | Forms whose handler reads a missing field as "off" can't be split into self-saving rows | §5.6: the rule "a form is split only if its handler writes only the sections it was sent", the full table of such forms (17 rows, 19 forms: QA's 10 + `location-policy`, `wallpapers`, `provisioning`, `schedules/global`, `music/entries`, `apps/tracked`, and the three schedule forms), and a section marker for the two splits (`policy`: quick/filter; `schedules/device`: switch/budget). Every other form moves as one block. §3.0 switch row, §3.2 sub-page forms, §3.5 rule editor (exempt apps in a `<details>`), §3.6 Locate (mode + interval one form). |
| H2 | The 45-minute Online window flags healthy phones as Offline | §3.10: Online = an open stream and last contact ≤ 60 min. Away = no stream and ≤ 45 min, with no number in the text ("changes may be delayed"). Anything older is Offline. Checked against the code: `SyncSchedule.kt` (30/15/9 min) and `BackstopAlarm.kt:56` (`setAndAllowWhileIdle`, inexact, up to 0.75 × the delay late; targetSdk 36). Two constants with their derivation, the new table tests, and an emulator check. |
| H3 | Picker pages, a top-of-page flash and shared handlers break "never jump" | §3.0: picker = an inline native select; navigation rows never save; the flash sits inside the saved group. §5.1 rewritten: redirect to the form's own page; a `back` table for the six shared routes; anchors only where they help; a redirect-table test. LockScreen, RuleEditor, Locate pickers → inline selects. |
| M1 | Build step 2 is too large | §8: 15 steps, one sub-page per step, with the old page shrinking. §3.8: card builders and a shared `attention::collect`, with a count test. `device_page(id, Section)` helper (§5.5). |
| M2 | The mockups drop controls and invent others | §6.0: "the mockups decide the look, §3 decides the content", plus the artboard-by-artboard table. Restored: Erase, Stop Ringing, location settings, Cancel, warnings, Import from Vibb, the per-entry Check now and Offline. Dropped: "Show what's playing", the Search field, "Show all", "Unlock Now", "Vibb lost device owner", reverse geocoding ("Rosenhoff skole" → "10 min ago · within 40 m"). Renamed to what exists: "Show setup code" → New setup code (a POST), "2 new apps wait for your OK" → "2 blocked apps wait". Wrong facts fixed: the PIN back-off, the Parent PIN footer, "4 to 8 digits". Label drift settled in §4.3. |
| M3 | A Settings value calls GitHub | §3.6: every hub value comes from the database or memory. Software Updates shows no value: there is no cached check result today (`app_update_last_check` holds only a date). About shows `APP_VERSION` and links to `/updates`. A test checks that the hub makes no network call. |
| M4 | Local time needs a tz database; the badge's end needs the phone's day | §3.10: `chrono-tz` and `display_time`. An empty or invalid zone falls back to UTC, labelled "UTC". The badge's end shows only when it is unambiguous, and says "until 07:00 tomorrow" across midnight. A stale report says "at last report (yesterday 21:40)" and shows no bar. Tests listed. |
| M5 | Rust builds sentences where rows need values; unit tests too | §3.8: typed siblings for each builder, the old one deleted with its unit tests when its last caller moves. Attention titles come from typed values. §5.5 counts the unit tests. §8 assigns each builder to a step. |
| M6 | Risky actions in one tap | §3.0 confirm pattern, §5.6 list: Unlock for 2 h…, Remove Child PIN…, Remove Contact…, Allow these apps…, Forget Package and Key…, Sign Out Everywhere…, and the seven `confirm()` deletes. The protective actions stay one tap. |
| M7 | Design 30 changes the Music card | §5.6 and §8 step 7: `design-30-s1` merges into main before the device Music step. The 5-field `v=2` form is one group. Music wording waits for design 30 step 3a. QA-30's tests are repointed. |
| M8 | The test plan needs helpers | §5.5: `input_checked`, `autosaves`, `form_action_of`, `nav_current`, `device_page`, `assert_redirect`, all in step 1. The head check walks subfolders. |
| M9 | The CSS must work on old phones | §5.7, the browser floor: Chrome/WebView 80+ and iOS Safari 14+. Not used: `:has()` and the rest. The switch's knob is a sibling span, not the mockups' `::before`. Forced colours, the focus fallback, hairlines, `color-scheme`, reduced motion, ES5 `register-sw.js`. |
| L1 | The tab can be set per template | §3.1: `{% let tab = … %}` per template; the path matcher and the active-tab script go. |
| L2 | Attention and the header miss some states | §3.2 Attention: incoming calls not blocked, the lock off with a PIN set, the unreadable ledger, a launcher without rules. §3.10: Disconnected and Not seen yet; when "at last report" shows. |
| L3 | Contrast and target sizes in the mockups | §5.4: the dark strong button `#0B66D6`, switch on `#248A3D` with a bordered off track, lock-pill tokens, a 44 px ⓘ hit area, `aria-label`s on Locate's switcher. |
| L4 | The rename and installed PWAs | §5.3: "Vibb Phone" for `name` and `short_name`, `"id": "/"`, the same manifest URL, the 2FA issuer unchanged (the user's decision), theme-colour metas, and the iOS release note. |
| L5 | Old bookmarks and in-page links | §3.1 (redirects of `?device=` URLs, chained GET+POST routes), §3.4 (the music anchors and the import preview), §5.1 item 5 (every link that moves). |
| L6 | Gaps in §3.9's map | §3.9 adds `offline_override_used`, `relock.notice`, `lock_screen.unreadable`/`empty_text`, `corrupt_rules`/`budget_corrupt` and `access.typed_paused`. The country code becomes a second form on Phone Setup. The per-photo wallpaper switch is kept (Q1). |
| L7 | Move the inline `onchange` to one script | §5.2: `autosave.js` with `data-autosave`, in step 1. It also settles the keyboard behaviour of selects. |
| L8 | Docs and cross-references | §8: each step updates the docs its pages rename (the six files listed). §5.1 now cites `devices.rs:1797` for `anchor=music`, not `music.rs:1798`. |

---

## Step 1 implementation status (2026-10-13)

Built on branch `design-31-s1` (from `69aee48b`); server only, no new crate, no migration.
- **Shell.** `partials/head.html`: "{title} · Vibb Phone", `color-scheme` meta, the two `theme-color` metas
  (`#F9F9FA` / `#161618`), `apple-mobile-web-app-title` "Vibb Phone", `style.css?v=9`, `autosave.js` (un-deferred, right
  after `scroll-restore.js`). `partials/app_header.html`: the brand "Vibb Phone" and the 5-tab bar
  (Devices · Apps · Locate · Music · Settings, 12px labels, `aria-hidden` icons from the TabBar mockup,
  `data-tab` per item); every page sets `{% let tab = "…" %}` right before the include and the server writes
  `aria-current="page"`; the active-tab script is gone. The Apps|Music switch and its inline CSS are gone from
  `apps_list.html` and `music.html`.
- **Settings hub** (`handlers::settings_hub`, `settings_hub.html`): the Settings mockup's four groups - Time Rules,
  Web Filter, Blocked Activity, Wallpapers · Phone Setup, Backups, Software Updates · Account & Password, Security
  Log, About · Sign Out (`POST /logout`) - with outline icons on tinted tiles. Values (M3): "2 rules" (everyone's
  rules), "3 lists" / "No lists" (enabled blocklists), "12 today" / "None today" (`device_dns_events.received_at` since
  local midnight), the wallpaper count, the newest backup zip's time ("Today 03:00"), "Server 0.24.0"; Software
  Updates has none. The hub's module takes only `state.db` and the backup directory.
- **Rename** (§5.3): manifest `id "/"`, `name` and `short_name` "Vibb Phone", the new description, `theme_color`
  `#F9F9FA`; `login.html`; `backups.rs`'s "not a Vibb Phone backup"; the start log line; `README.md`, `DEPLOY.md`
  (with the release note under "The parent app is Vibb Phone"), `server/CLAUDE.md`. The 2FA issuer is unchanged.
- **CSS** (`style.css`): tokens for light and dark (§5.4's L3 colours: the strong button `#0B66D6` in both themes,
  switch on `#248A3D` with a bordered off track, lock-badge tokens), the components (`.group`, `.group-header`,
  `.group-footer`, `.row` with `.row-label`/`.row-sub`/`.row-value`/`.chev`/`.tile`, `.cell` for a form or
  `<details>` holding a row, `.row-button`/`.row-danger`, the switch `.sw` + sibling `.knob`, `select.pick`,
  `details.info` + `.info-panel`, `.badge`/`.dot`/`.count`/`.meter`, `.attention-alarm|action|state|result`,
  `.flash`, `details.confirm` + `.button-destructive`, `.field-error`, `.nav-back`, `.page-title`), and today's
  `.card`, `.error`, `.success`, `.button-*`, inputs (3:1 borders), tables and the device/app lists restyled with the
  tokens. Browser floor (§5.7) in `style.css`: no `:has()`, nesting, `color-mix()`, `aspect-ratio` or flex `gap`
  (grid `gap` only, Safari 12+); `word-break: break-word` before every `overflow-wrap: anywhere`; `:focus` +
  `:focus:not(:focus-visible)`, drawn inside the row in a grouped list; 1px hairlines, 0.5px under 2dppx; forced
  colours show a real checkbox; reduced motion drops transitions; the tab bar is opaque unless `backdrop-filter`
  works. `register-sw.js` is ES5. `wallpapers.html`'s swatch has a padding fallback for `aspect-ratio`.
  **Left below the floor, for the steps that rebuild those pages:** inline flex `gap` in `device_detail.html`
  (steps 3-10), `dns_filter.html` (14), `device_locate.html` (13) and the `music*.html` style
  blocks (12); `overflow-wrap: anywhere` without the fallback in the `music*.html` pages (12). On Safari 14.0 those
  items only touch, and a long word can widen the page.
- **`autosave.js`** (L7): the 54 `onchange="this.form.submit()"` are `data-autosave`; one delegated listener calls
  `form.submit()` (so `music-cards.js`'s swapped cards keep working). A `<select>` changed from the keyboard waits
  for Enter or blur; Enter in an open dropdown saves its choice at once. Loaded un-deferred.
  `jstest/autosave.test.js` (9 tests).
- **Tests**: `tests/mod.rs` helpers `input_checked`, `autosaves`, `form_action_of`, `nav_current`,
  `device_page(id, Section)` (Summary, Calls so far), `assert_redirect`; the `onchange` assertions in `hardening.rs`,
  `music.rs` and `lock_glance.rs` and the pinned Locations in `enrollment.rs` and `parent_pin.rs` use them.
  `tests/shell.rs`: `nav_current` on a landing and an inner page per tab, every template sets a valid tab, the name
  and manifest, no inline auto-submit, the helpers, the hub's links and values, and that the hub names no network
  client and fetches nothing. `pages_restore_scroll_after_auto_save` walks subfolders of `templates/`
  (`template_files`, tested on a temp tree). `cargo test`: 676 passed; `node --test jstest/*.test.js`: 16
  passed; fmt clean; clippy: no new warnings. Code QA: `qa-31-s1-code.md` (0 High, 1 Medium, 8 Low), all fixed - see
  its "Fixes" table.

Choices made while building:
1. The brand bar ("Vibb Phone", 44px, no border) stays on every page until the sub-page header replaces it page by
   page (§3.0); step 15 removes it from the tab landing pages (§8). The mockups' tab pages start with the large
   title, which the hub now has (`.page-title`).
2. The hub follows the mockup's grouping (four groups, no headers) rather than the table's Rules/Content/Server
   labels; the labels are the spec's ("Software Updates", "Web Filter" with the list count, not the mockup's "On").
3. "Today" and the backup time use the server's local time (as the Backups page does) until step 2's
   `display_time` takes the Phone Setup zone. On the Docker image (TZ = UTC) "today" starts at UTC midnight until
   then.
4. Pages whose name left the navigation were renamed in place: `/dns` is titled "Web Filter", `/settings/provisioning`
   "Phone Setup", and links saying "DNS/Filters tab" / "Provisioning settings" point at Settings › Web Filter / Phone
   Setup. Their content is unchanged (steps 14 and 15).
5. `/updates` isn't fetched by the tab test (it asks GitHub when it renders); its tab is checked in the template.
6. Not checked here: the 390 px light/dark/no-JS pass in a browser (no browser in the sandbox) - owed on a phone or
   desktop browser before merging.

## Step 2 implementation status (2026-10-13)

Built on branch `design-31-s2` (from `60872f24`); server only, no migration. New crate: `chrono-tz` 0.10.4
(MIT/Apache-2.0, `std` only; adds `phf`, `phf_shared`, `siphasher` to `Cargo.lock`).
- **`display_time`** (`src/display_time.rs`): `Zone::load` reads the Phone Setup zone; empty or unknown (case-sensitive
  IANA ids, so "Europe/Olso" and "europe/oslo") is UTC and every clock time says "UTC". `parse_db_utc`
  (`datetime('now')` text with or without a fraction, and RFC 3339), `local`, `clock` ("14:30"), `exact` for `title=`
  ("2026-10-13 10:59:13 CEST"), `relative` (§3.10's steps; "12 Oct 2025" in another year; up to 5 min ahead is "just
  now", further ahead its date). Unit tests: Oslo DST both ways, an empty and an unknown zone, yesterday, a time
  2 min ahead, the steps. The Settings hub's "today" and backup time now use the Phone Setup zone (step 1 choice 3).
- **`connection_state`** (`streams.rs`, pure, `ONLINE_WINDOW_MINUTES` 60 / `AWAY_WINDOW_MINUTES` 45 with their
  derivation): Online, Away, Offline, Disconnected, Not seen yet, Not set up; last contact is the newest of last
  access, `last_seen_at` and an open stream's `since`. `devices::connection_of` feeds it. Table tests for every row,
  50 min / 61 min with a stream, 30 / 45 / 46 min without, revoked, the restart case.
- **Lock badge and screen time** (`src/time_state.rs`): the typed `time_state` (`TimeReport`), `LockBadge` and
  `ScreenTime` with §3.10's rules (M4; the end time as tightened by the fix round below); another day's report shows
  no end and no bar and says "at last report (yesterday 21:40)"; Offline always and Away after 15 min add "at last
  report"; an end already passed drops the end and says "at last report".
- **`attention::facts` + `items`** (`src/attention.rs`; `collect` in the spec): §3.2's 11 alarms and 6 needs-action kinds from typed sources -
  `kid_lock::lock_off` (new: a Child PIN set but the lock off on the phone), `calls::CallProblem` (new: the call
  report's warnings typed, one per warning, each with a title of at most 8 words), the relock card's foreign access
  and blocks, `admin_policy_notice`, the sound filter, the backup flag, the policy state, the boots, the rules.
  `devices::managed_without_pin` and `launcher_without_rules` are shared with the device page.
- **Devices list** (`devices_list.html`, `devices::list_row`): large title, the sign-in warnings as orange rows
  (live: a link to Account; after sign-in: with Dismiss), "Phones" group (avatar with initial and connection dot,
  name, "Online · seen 3 min ago" with the exact time in `title`, the lock part, the bar and "42 of 90 min today",
  the red count), "No phones yet.", the Add Phone row. The intro paragraph moved to `device_add.html`.
  `style.css?v=10` (avatar, dot, row, `.visually-hidden`, the warning rows' sub-line and Dismiss).
- **Stub summary** (until step 10): the device page shows the Attention rows in an "Attention" group under its
  title, linking to the cards (`#status`, `#time`, `#launcher`, `#hardening` are new ids; `#parent-pin`, `#access`,
  `#screen-lock`, `#play-kiosk`; calls rows to `/devices/{id}/calls`). Each later step repoints its targets
  (`attention::target`).
- **Tests**: unit tests in `display_time`, `streams`, `time_state`, `attention`, `kid_lock`, `settings_hub`;
  `tests/devices_list.rs` (every connection state on the list, the lock part and bar, yesterday's report, the badge
  equal to the device page's rows, the UTC fallback, the empty list). `cargo test`: 703 passed; `node --test
  jstest/*.test.js`: 16 passed; fmt clean; clippy: the same 13 warnings as before, none in the step's files.

Choices made while building:
1. **The list's status line**: Online with a lock shows the lock instead of "seen ..." (the Main mockup's "Online ·
   Skole until 14:30"); Away and Offline keep "seen ..." and add the lock. When "at last report" would repeat the
   seen time, the list leaves the time out ("Skole · at last report").
2. **The bar**: shown with a limit; without a limit "42 min today" without a bar, and nothing at 0 min.
3. **Calls rows** are orange (§3.2 lists "calls problems" under needs action), one per warning but not "waiting for
   the phone"; "Anyone can call this phone" is the §4.5 #17 title.
4. **`parent_pin::when_text`** is not folded in yet: its callers (Status notices, PIN events, relock and boot notes)
   switch to `display_time` in the steps that rebuild their cards (3, 4, 10), and it goes with the last.
5. **Attention's music and escapes sources**: §3.8 names them, but §3.2's list has no row for them; nothing added.
6. **The avatars** use darker shades of the mockup's hues (white text 4.5:1 or more; decorative, `aria-hidden`); a
   phone not set up is grey. The mockup's "+" in the top bar is left out: the brand bar is still there (step 15),
   and the Add Phone row does the same.
7. Not checked here: the 390 px light/dark/no-JS pass in a browser (no browser in the sandbox).

**Fix round after the code QA** (`qa-31-s2-code.md`: 3 Medium, 5 Low, nits; all fixed, with `main` merged first -
designs 30, 32 step 1 and 33):

| # | Fix |
|---|---|
| M1 | `time_state::provable_end`: an end only when the phone reports its zone (`time_state.zone`) and it is Phone Setup's, the report is fresh and today's and lies inside one window of the active rule, and at that end no effective rule locks (same rule's next day, all-day, legacy pair, adjacent or overlapping rules) and the screen time isn't used up. A stale badge never has an end. No launcher sends a zone yet, so **no end time shows today**; a launcher `zone`/`active_until` field is an API change left out of design 31. Tests: the three QA probes (a report at 14:30:05, a London phone, windows that meet), all-day every day, a one-day 24 h window, the day before's 24 h window, overlap, adjacency, used-up screen time; the devices-list test now expects "Skole" with no end for an every-day all-day rule. |
| M2 | The fallback zone shows no end (its `name()` is none); the report's `day` counts as current within a day of the UTC date, and the bar says "42 of 90 min on 13 Oct" instead of "today". Test: a New York phone at 20:30 local. |
| M3 | "· calls off" only with `Facts::calls_enforced` (calls managed, no `NotEnforced`/`AnyoneCanCall` in the call report); a list test with calls unmanaged. |
| L1 | `tests/attention.rs`: one test per fact (each report field or setting raises exactly its row on an otherwise clean phone, and nothing on the clean one), and every call warning has its typed problem (a report that sets every flag). |
| L2 | Away's stale boundary tested at 15 and 16 min; the report's date check and the day before's 24 h window each have a case. |
| L3 | The sign-in warnings on Devices are short rows (`auth::live_warning_row`: "5 wrong two-factor codes since sign-in", "Someone knows your password - change it · sign-ins locked until 14:05" in the Phone Setup zone; `login_warning_row`); the full sentences stay on Account. Kept orange, as §3.2 says. |
| L4 | §3.10, `server/CLAUDE.md` and the `connection_state` doc: Away, or Offline past 45 min, until the stream is back - seconds after a clean stop, up to 5 min after a crash. |
| L5 | The user's decision (§3.2 item 2): no rows for a phone not set up; an Offline or Disconnected phone's report-based rows say "At last report" and still count; list and summary count the same `items`. |
| Checks | `cargo test` 725 passed (with `main` merged); `node --test jstest/*.test.js` 16 passed; fmt clean; clippy: the same 13 warnings, none in the step's files. |
| Nits | A time more than 5 min ahead reads "today 16:00" on the same day; the key test uses a set and documents that calls rows share "calls"; the device page passes its built cards to `attention::facts_of` (`call_warnings` is gone, the page uses `call_report`); the avatar letter is the first letter or digit, one character ("ß" kept, emoji and flags skipped); the Backups page labels its times "server time" (it matches the file names; the hub uses the Phone Setup zone); the `title=` on the list's "seen" stays (the header's ⓘ is step 10's). §3.8 no longer names music and escapes as sources. |

## Step 3 implementation status (2026-10-13)

Built on branch `design-31-s3` (from `main` at `4d225da4`, step 2 merged); server only, no migration, no new crate.
- **Routes:** `GET /devices/{id}/about` and `GET /devices/{id}/advanced` (`handlers::device_pages`). Unknown phone: 404.
  Every POST route is unchanged; only where the forms return moved.
- **About This Phone** (`device_about.html`, read-only, the About mockup plus Time lock): Connection (Status "Online
  since 08:12" / Away / Offline / Disconnected "access removed" / Not seen yet / Not set up; Last contact "10:59 · 3
  min ago"; Last full report; Network from `LastAccess`, the outside addresses behind ⓘ; the mockup's footer, the
  check-in cadence behind its ⓘ), Sound (Ringer, Do Not Disturb red while on), Software (Vibb "0.23.6", full version
  behind ⓘ; Music app; Settings from server "Applied" or a `PolicyProblem` title, the old sentence behind ⓘ; Battery
  optimisation, the fix behind ⓘ), Diagnostics (Crash reports "None in 30 days" / "N kinds" with one row per crash,
  the trace behind its ⓘ; Starts without Vibb; Time lock). Local times, the exact one in `title`.
- **Advanced** (`device_advanced.html`): Restrictions (all 10 switches as **one** `hardening` form, H1, in three
  groups so each has its footer and ⓘ, then the Backup to Google row, with the alarm above the group while it is on);
  App screens (`kiosk-block` "Block other apps' screens" + the Play state); Updates & notifications (`kiosk-escapes`,
  one form: Update fence, Notification filter, Boot cover, each with a one-line subtitle, the details behind the
  footer's ⓘ; the phone's state as rows, warnings above; "Removed since last report"); Connection (live code row,
  New Setup Code, Set Up with QR Code ›, **Disconnect Phone…** as a `<details>` confirm keeping the required box, the
  flash in the group); **Remove Phone…** as a `<details>` confirm. Sub-page header (back link + large title), the
  tab bar alone (`partials/tab_bar.html`, split out of `app_header.html`).
- **Redirects** (§5.1): `hardening`, `kiosk-block` and `kiosk-escapes` → `/devices/{id}/advanced` with no fragment
  (since the fix round, M3); `revoke` → `/advanced?notice=…#connection`; `delete` → `/devices`.
  `enroll_code.html`'s Back: `/advanced#connection` (enrolled), `#access` (the not-enrolled card).
- **The device page:** the Access, Play and kiosk, Launcher updates and notifications, Launcher crashes and Phone
  hardening cards and Remove are gone; the Status card lost the time lock line, app version, sound lines, "Last
  reported", the policy problem and the unseen-boots text. Two rows at the bottom: **About This Phone · Vibb 0.23.6 ›**
  and **Advanced ›** (the DeviceSummary mockup's icon tiles). Attention targets moved (Do Not Disturb `/about#sound`,
  policy problem `/about#software`, unseen restarts `/about#diagnostics`, Backup to Google `/advanced#restrictions`,
  access removed `/advanced#connection`, restart once `/advanced#app-screens`).
- **Rust texts (M5):** typed siblings, and the sentence builders deleted with their unit tests: `ConnectionState`
  (+ `online_since`) for `instant_changes_line`; `Via::label` / `LastAccess::detail` for `last_access_line`;
  `sound_mode::state` (`Ringer`, `Dnd`) for `status_lines`; `boot_witness::unseen` (`UnseenBoots`, `BootAdvice`) for
  `warning`; `device_pages::PolicyProblem` for `policy_problem_text`; `PlayNote` for the play lines and
  `battery_line`; `models::admin_policy_effects` for `admin_policy_notice`; `kiosk_escapes::EscapeNote` (`Release`,
  `Handover`) for the escapes card's lines, warnings, `release_text` and the boot-cover notes. `Zone::when` ("10:59",
  "yesterday 21:40", "12 Oct") is new in `display_time`. CSS `?v=11` (the footer ⓘ inline, alarm/action values, the
  trace in a panel).
- **Tests:** `tests/device_pages.rs` (About's rows and their values for a full report and a healthy one, a phone not
  set up, 404s, the **redirect table** built from the rendered forms on Advanced, the `<details>` confirms and the
  unconfirmed revoke, a live code shown without the code); repointed through `device_page(id, Section::About |
  Advanced)`: `hardening.rs`, `step11.rs`, `step9.rs`, `play.rs`, `sound_mode.rs`, `cleanup.rs` (the crash pin keeps
  its no-free-text assertions), `enrollment.rs` (the `#access` redirects → `#connection`), `edge.rs`, `device_api.rs`,
  `command_receipts.rs`, `window_relock.rs`, `devices_list.rs`, `shell.rs` (the sub-pages' tab, a sub-page includes the
  bar alone and has a back link); unit tests in `sound_mode`, `boot_witness`, `kiosk_escapes`, `limits`, `streams`,
  `display_time`, `attention`, `device_pages`. `cargo test`: 735 passed; `node --test jstest/*.test.js`: 16 passed;
  fmt clean; clippy: the same 13 warnings, none in the step's files.
- **Browser pass** (headless Chromium, 390 px, light and dark, JS on and off, every `<details>` opened): no
  horizontal scroll on About, Advanced, the device page, or a phone not set up. Still owed on a real phone (iOS Safari
  14's inline `<details>` in a footer).

Choices made while building:
1. **A phone not set up:** its "Not enrolled yet" card (New code, QR, notices, `typed_paused`) stays on the device page
   until step 10 makes it "Connect this phone"; Advanced shows Restrictions (they can be set before setup, as the
   launcher docs advise for USB debugging) and Remove Phone only, not App screens, Updates or Connection; About shows
   "Not set up". The About and Advanced rows are live, not greyed "After setup" (§3.2): Restrictions and Remove must
   stay reachable.
2. **Where the two rows sit:** at the bottom of the device page, where the DeviceSummary mockup has them, though other
   cards still follow the Status card until step 10.
3. **Restrictions in three groups of one form:** a switch row can't hold an ⓘ (§4.4: a control never sits in a
   summary), so the explanations are footers with ⓘ: the plain switches; Block USB debugging; Block safe mode. Labels:
   Block factory reset, Block new users and guests, Block account changes, Block VPN settings, Block USB file transfer,
   Keep location on, Block airplane mode, Lock the phone's language, Block USB debugging, Block safe mode. The old
   per-switch notes moved into the footers' ⓘ.
4. **Dropped as redundant:** the escapes card's "Update fence off: …" line (the switch says it) and the Status card's
   "Whether a time rule was locking" footer. The "Update waiting" value is the version without `launcher-v` (the tag in
   the ⓘ).
5. **"Starts without Vibb"** shows "2 times", not §3.2's "2 in 30 days": the phone's count is all-time; the row shows
   while the last one is under 30 days old, its time behind ⓘ.
6. **Music app version** comes from its versionCode (the phone reports no version name) by the repo's scheme; a
   release candidate reads one patch below (2999 → "0.2.999"); the package and code are behind ⓘ.
7. **Battery optimisation** "Off" means the launcher is exempt (good), "On" (orange) that it may lag, as the old line
   said.
8. **Time lock** shows the reported rule's name ("Skole") when the phone sends one, else the kind (Bedtime, School,
   Screen time used up, A time rule, or "Locked" for an unknown code - no raw code).
9. **Play install mode** is an orange warning ("Play Store open until 14:30"), as it was red before; the update window
   is a row. **Restart the phone once** shows on App screens with its reasons, and stays an Attention row.
10. **New Setup Code** stays one tap: making a code changes nothing, using it does (§5.6 doesn't list it).
11. **Remove Phone** now redirects to `/devices` (was `/`, which redirects there).
12. **The enroll code page's Back** goes to Advanced › Connection for an enrolled phone (where New Setup Code is),
    not to `/devices/{id}` as §3.7 says.
13. **Crash rows** are labelled with the exception's class name ("IllegalStateException"); the full trace, build and
    hash are in the row's ⓘ.
14. References to the moved cards were renamed where they name a place: the kid-lock warning and the Screen lock
    card ("Advanced › Restrictions"), Phone Setup's language hint, `server/CLAUDE.md`, `DEPLOY.md`,
    `docs/testing/emulator.md`, `docs/setup/google-account.md`, `launcher/CLAUDE.md`.
15. `play_card` moved from `devices.rs` to `device_pages.rs` (with the pure `play_notes`); `DeviceStatus` derives
    `Default` for the pure note tests.

**Fix round after the code QA** (`qa-31-s3-code.md`: 4 Medium, 6 Low, nits; all fixed, with `main` merged first -
step 11 at `293ecb82`):

| # | Fix |
|---|---|
| L6 (merge) | Step 3's header split kept: the app page and Add App include `partials/tab_bar.html` alone, step 11's `subpage` flag is gone; `style.css?v=12`; both status sections and both `server/CLAUDE.md` sections kept. `shell` counts 4 sub-pages with the bar alone, each with a back link. |
| M1 | About's Last contact comes from the signals (`streams::last_contact` over `devices::connection_inputs`), not the state, so a Disconnected phone keeps its time. Tests: `streams` (a revoked phone's contact; a closed stream is none), `device_pages::about_keeps_a_disconnected_phones_last_contact`. |
| M2 | `Zone::until`: "14:05" today, "00:05 tomorrow", "Mon 14:30" within the week, "20 Oct 14:30" beyond; a past time reads as `when`. Used for the live code, the typed-code pause and Play install mode. Unit test at 23:50 Oslo. |
| M3 | `kiosk-block` and `kiosk-escapes` redirect to `/devices/{id}/advanced` with no fragment, as `hardening` does, so `scroll-restore.js` keeps the position. Pinned in the redirect table, `step9` and `step11`. |
| M4, L4 | Four orange Attention rows (§3.2), decided yes on the user's behalf by the coordinator (the user can overrule): "Play Store open (install mode)" and "Play Store can be opened" (not pausable, "Block other apps' screens" off) → `/advanced#app-screens`; "Vibb isn't the Home app" and "Boot cover wasn't shown" → `/advanced#updates`. Facts from Advanced's own `play_notes` / `escapes_card`. Tests: `tests/attention.rs` (each raises exactly its row; the switch alone, or Play blocked, raises none), the unit tests' order, titles and targets. "Filter has no notification access" and "Some home screens weren't paused" stay on Advanced only. |
| L1 | The Play warning names the switch: "Play is blocked only while Block other apps' screens is on." / "Block other apps' screens is off, so apps can open the Play Store." |
| L2 | Block safe mode's ⓘ ends "Turn it on once this version of Vibb has been tested on the phone." |
| L3 | `cleanup::crash_traces_are_escaped_on_about`: a `<b>Boom` exception and a `<init>` frame are stored, and About shows them escaped, never as tags. |
| L5 | Tests: the typed-codes-paused warning on Connection (`state.enrollment`), a disconnected phone's last contact, the live code's end has a clock time, `about#software` among the Attention anchors. |
| N1 | "Disconnected · access removed". |
| N2 | A versionCode ending in 999 reads as its release, "0.3.0 (pre-release)". |
| N3 | `device_pages::crash_label`: the class name, or the whole first line when it is a frame or a cause. |
| N4 | `load_device`: 404 for an unknown phone, 500 (logged) on a DB error. |
| N5 | The Backup-on warning has "(Vibb's Settings › Sync now)" and "report it" again. |
| N6 | No change: the `launcher/CLAUDE.md` edit only starts the launcher's CI job. |
| §3.7 | Now says the code page's Back goes to Advanced › Connection (choice 12). |
| Checks | `cargo test` 750 passed (with `main` merged); `node --test jstest/*.test.js` 16 passed; fmt clean; clippy: the same 13 warnings, none in the step's files. Headless 390 px, light/dark, JS on/off: About, Add App and the Apps tab after the merge, no horizontal scroll, sub-pages without the brand bar. |

## Step 11 implementation status (2026-10-13)

Built on branch `design-31-s11` (from `4d225da4`, with steps 1 and 2 in), in parallel with step 3; server only, no
migration, no new crate, `style.css?v=11`.
- **Apps tab** (`apps_list.html`, `tracked_apps::list_apps`): the large title; **On phones**, one row per phone
  (36px avatar from step 2's `devices::avatar_class`/`initial`, the name, "14 allowed" or "Not set up" or "List
  unreadable", a chevron); **Catalog**, one row per app: a 36px tile (the catalog icon on its colour, else the first
  letter), the name phones show ("Chat"), a sub-line ("Launcher · all phones", "Element X · 1 phone", "No phones"),
  the version from the tag (`version_of`: "launcher-v0.32.0" -> "0.32.0", "v26.10.1" -> "26.10.1") or a badge
  instead: **Off** (grey, disabled), **Update failed** (red, the last sync failed), **No release yet** (grey); the
  launcher first, then by name; the footer and **Add App**.
- **The app page** (`tracked_app_detail.html`): the sub-page header ("‹ Apps", the large title, no brand bar); the top
  group (Version, "Last checked 2 h ago" with the exact time in `title`, the running state "Checking now…" or the
  failure's short title with the raw error and its exact time behind ⓘ, **Check Now**); **Show on phones as** (the
  `app_display_form` partial unchanged, as one row of a group, with a one-line footer); **On phones** (new: the phones
  with it ticked - every set-up phone for the launcher - each linking to its row on the phone's page); **Source &
  Signing**, a `<details>` (closed; open when a refused save or upload is in it) holding Release (GitHub link or
  "Uploaded by you", the full tag, the file, the package with the confirm-a-candidate forms, the signing key short
  with the whole key behind ⓘ, **Forget Package and Key…** as a `<details>` confirm - M6), Details (the one form:
  name, repo, "File name contains", "Tag starts with", their hints behind ⓘ, the error under the field with
  `aria-invalid`/`aria-describedby`), Updates (Include pre-releases, Enabled, This is the launcher app: switches,
  one form each as before, now with a `<noscript>` Save) and, for an uploaded app, Upload a new version; **Remove
  App…** in red behind its confirm.
- **Add App** (`tracked_app_add.html`): name, the source as two rows (GitHub releases / Upload APKs yourself), the
  repo; "File name contains", "Tag starts with", the package and Include pre-releases under **More Options**, open
  when one holds something or was refused. The source's inline `onchange` script is gone (§5.2): the two radios sit
  before the rows, so CSS marks the chosen row and hides the GitHub fields for an upload.
- **Rust texts**: a sync failure is typed (`tracked_apps::SyncProblem`, 13 kinds, each with a title of at most 6
  words; `Failure { problem, detail }`), set where each error happens - GitHub unreachable / 404 / other answers, no
  matching release, download, unreadable APK, another app, another key, can't check, a package taken, server, removed.
  `check_pins` and `check_new_package` return it; a refused release keeps its kind ("not downloaded again" keeps
  the title). Upload and package refusals show the same way in their group (a red title, the reason behind ⓘ).
  The field errors say "The file name filter is ..." and "The tag prefix may only hold ...".
- **Every form keeps its POST target and behaviour**; each redirects to the app's page as before (Display to
  `#display`, Remove to `/apps`, Add to the new app). `tests/tracked_apps.rs`
  `every_form_on_the_app_pages_returns_to_its_page` is this step's part of §5.1's redirect table: it posts every form
  the GitHub and the uploaded app's pages render and fails on a form it has no row for.
- **Tests**: `tests/tracked_apps.rs` (the tab: rows, count equal to the phone page's ticks, order, badges, icon, footer;
  the app page: no brand, its phones and their links, the switches' forms, the two confirms, the launcher's and an
  uploaded app's page; Add App's More Options and radios; the redirect table; the existing tests through the step 1
  helpers, the failure as title + ⓘ and the catalog's "Update failed"), `tests/catalog_pins.rs` (each refused upload's
  title, the raw reason kept, Source & Signing open), unit tests `a_release_tag_reads_as_a_version`,
  `sync_problems_have_short_titles` and the typed failures in the sync tests. Checked in headless Chromium at 390 px,
  light and dark, with and without JS: no horizontal scroll, the source rows switch the GitHub fields with CSS alone.

Choices made while building:
1. **"On phones" links** (the tab and the app page) go to the phone's page - at the app's own row
   (`#app-<package>`) from an app page - until step 6 builds `/devices/{id}/apps`; one function,
   `tracked_apps::phone_apps_href`, to repoint then. `device_detail.html` is untouched (step 3 works on it).
2. **"N allowed"** counts what the phone's Apps card ticks (`tracked_apps::allowed_count`): reported apps on its
   allowlist (not Play's core) plus catalog apps chosen for it and not reported installed; the launcher isn't
   counted. A test checks the two agree.
3. **The brand bar** leaves the two rebuilt inner pages (§3.0): they set `{% let subpage = true %}` before the tab, and
   `partials/app_header.html` skips the bar when it is defined (`is defined`, Askama 0.16). Step 3's sub-pages can use
   the same flag; if it chose another one, the merge picks one. **The merge picked step 3's** (qa-31-s3-code L6): the
   two pages include `partials/tab_bar.html` alone, the flag is gone, and `style.css` is at `?v=12`.
4. **The catalog row's name** is the name phones show (the mockup's "Chat") with the admin name in the sub-line;
   the app page keeps the admin name as its title.
5. **"Update failed"** reads the in-memory sync status, so it clears on a restart until the next hourly check, like
   the app page's failure line.
6. **The shared `app_display_form` partial** is not reworded: the device page uses it too, and step 6 restyles it
   there. Its summary keeps the native disclosure marker.
7. **The add form's package field** is kept under More Options as §3.3 says, though the server never stored it; its
   ⓘ now says so.
8. **Button and row case**: action rows and confirms in Title Case as the mockups draw them ("Check Now", "Add App",
   "Remove App…", "Forget Package and Key…").
9. Not checked here: a real phone (iOS Safari 14, Chrome 80) - owed before merging, as for steps 1 and 2.

**Fix round after the code QA** (`qa-31-s11-code.md`: 1 Medium, 7 Low, nits; all fixed):

| # | Fix |
|---|---|
| M1 | The review's option (b), which fits §5.1 item 4 (an anchor where the result is): the Upload form posts to `.../upload#upload` and each package confirm to `.../confirm-package#release`, so a refusal rendered in the POST's answer lands on its group - Source & Signing open, the red title and its reason behind ⓘ in view, where the parent was. A success redirects to `?notice=uploaded#upload` / `?notice=package_confirmed#release`, which opens Source & Signing and shows a green line in that group ("New version uploaded", "Package confirmed"; `tracked_apps::note_of`, any other `notice` ignored). One partial for both groups' results, `partials/flash_note.html`. Tests: `a_form_result_shows_in_its_group_where_the_parent_was`, and the redirect table now asserts each action's fragment and both success redirects. |
| L1 | `stream_to_file` and `download_asset` return a typed `Failure`: temp-file, write, flush and rename errors are `Server`, an asset over 1 GB is the new `TooBig` ("Release file is over 1 GB"), the rest `Download`. Tests: `a_failed_download_leaves_no_file` checks `Download` for each case, `a_disk_error_is_the_servers_not_the_downloads`, and the over-the-limit test checks `TooBig`. |
| L2 | `github_problem(StatusCode)` (404 `RepoNotFound`, anything else `GithubError`) and `release_from` (an invalid stored filter `Filter`, no match `NoRelease`), unit-tested in `github_answers_are_classified`. |
| L3 | The redirect table seeds a candidate (a phone with the uploaded app ticked reports the fixture's package installed by Vibb), so `/confirm-package` is rendered and posted; it also checks that Upload, Confirm, Forget and Check were all seen. The confirm test asserts the redirect. |
| L4 | Forget and Remove: each form must lie inside its confirm, after `</summary>` and before that `</details>`. |
| L5 | `outside_text_is_escaped_on_the_apps_pages`: `<b>` in an app name, a display label, a phone name, a sync failure's detail and a refused confirm's package, escaped on `/apps` and the app page. |
| L6 | The tab test allows and reports `com.google.android.gms`: the phone's page still ticks 3 and the row still says "3 allowed". |
| L7 | Add App's wrapper has no `role="radiogroup"`/`aria-labelledby` any more: the two radios with one name are the group. |
| Nits | (1) the header partial says only definition counts ("set it to anything"); (2) `style.css?v=11` kept, `v=12` if step 3's CSS lands first; (3) `DEPLOY.md`'s launcher setup says Add App, "File name contains", "Tag starts with" and that the package is pinned from the APK; (4) "Check Now" in the refused-release detail; (5) `version_of` strips a leading "v" first, then takes the first "-v" before a digit (`v2.1.0-v8a` -> "2.1.0-v8a"); (6) phones sort by name the same way on the tab and the app page (the Devices list's binary order); (7) the launcher's row says "no phones" until one is set up (`the_launcher_is_on_all_phones_only_once_there_are_some`); (8) the two result ⓘ are labelled "More about the refused upload/package"; (9) `.group-tiles` is gone: step 2's `.group-avatars` hairline reads `--avatar-inset` (72px), `.group-small` sets 64px; the unstyled `.flash-line`, `.flash-info` and `.catalog-empty` hooks are gone; (10) the tab's phone rows still open the device page at its top until step 6 (choice 1); `device_detail.html` is step 3's. |
| Mutations | Each surviving mutation the review lists now fails a test: confirm redirecting to `/apps` (the redirect table, the confirm test), 404 as `GithubError` (`github_answers_are_classified`), download errors re-kinded (`a_failed_download_leaves_no_file`), Forget's form outside its confirm (the app page test), `\|safe` on the failure detail, the catalog label or the shared result partial (the escaping test), Play's core counted (the tab test); and dropping the `#upload` fragment fails three tests. |

## Step 4 implementation status (2026-10-13)

Built on branch `design-31-s4` (from `main` at `571f86fa`, steps 1-3 and 11 merged); server only, no migration, no
new crate, no API change (the policy JSON and the launcher are untouched).
- **Route:** `GET /devices/{id}/lock` (`device_pages::view_lock`, `device_lock.html`). Unknown phone: 404, a DB
  error 500 (`load_device`). Every POST route is unchanged; only where the forms return moved.
- **Lock Screen & PINs**, in the LockScreen mockup's order:
  - **Child PIN:** the row "Child PIN · On · 4 digits" / "Off" opens a `<details>` with the field and **Save Child
    PIN** (`kid-lock`); "Set a Parent PIN first" sits in it while there is none. The phone's lock as rows (On the
    phone · Locked/Unlocked/Waiting for a report, Wrong PINs in a row, Next try in, Stepped aside) and warnings
    above the group (Safe mode is allowed, Vibb needs an update, the `inactive` reasons, Calls can't ring over the
    lock). **Remove Child PIN…** is a `<details>` confirm (M6). The flash in the group. Footer "Locks the phone when
    the screen turns off." with §4.5 #1/#2 and §6.0's back-off facts behind its ⓘ.
  - **Parent PIN:** the row "Parent PIN · Set / Not set" opens its form (field, hint, "Remove it instead" kept as a
    checkbox, **Save Parent PIN**); warnings above (No Parent PIN set - when managed, as the Attention alarm; easy to
    guess; the older launcher's "used offline"); the flash in the group; the footer "Opens the phone's settings and
    lifts limits, even offline." with what it opens, the lockout ladder and "typed where your child could see?"
    behind its ⓘ. **Recent use** (its own group under it): the last 10 events as rows, title · local time (exact in
    `title`), the details under the title, a lockout red.
  - **Screen:** "Turn off after" as an inline select (`screen-timeout`); "On the phone now" only while the phone
    reports another value. Footer: "With a Child PIN, the phone locks when the screen turns off."
  - **Lock screen:** "Background" as an inline select (`lock-wallpaper`: Same as Home or one of the phone's ticked
    wallpapers) and the "Show message text" switch (`lock-screen`); footer "Anyone holding the phone can read the
    message text." with what the lock's first page shows, Low 8's names/login-codes caution and the photo caution
    behind its ⓘ.
  - **Notifications on the lock screen:** one switch per app (`lock-screen/apps`, `#lock-screen-app-{pkg}` kept), a
    ticked app outside the list with "Not allowed" / "Not on the phone" under its name, the empty and unreadable
    states, the warnings above; footer "Missed calls always show."
- **Redirects** (§5.1): `kid-lock` → `/devices/{id}/lock?notice=…#child-pin`, `parent-pin` →
  `/lock?notice=…#parent-pin` (each lands on its group, where its flash is); `screen-timeout`, `lock-wallpaper`,
  `lock-screen`, `lock-screen/apps` → `/devices/{id}/lock` with **no fragment**, so `scroll-restore.js` keeps the
  exact place (§5.1.4, qa-31-s3-code M3). Pinned by the redirect table built from the rendered forms.
- **The device page:** the Screen lock, Remove the kid's PIN, Parent PIN and Screen timeout cards, the Wallpapers
  card's "Lock screen background" and the Apps card's "On the lock screen" are gone. A row **Lock Screen & PINs ·
  PIN on/off** (the mockup's lock tile) heads the bottom group, before About This Phone and Advanced. The Status
  card's Parent PIN links (foreign app, notices, easy PIN) and the no-PIN card point to `/lock#parent-pin`.
  Attention targets moved: no / easy Parent PIN → `/lock#parent-pin`, "Lock is off on the phone" → `/lock#child-pin`.
- **Rust texts (M5):** `kid_lock::LockNote` and `kid_lock::Inactive` replace `LockCard.lines`/`warnings` and
  `inactive_warning` (deleted, with their unit tests rewritten); `lock_glance::Note` replaces `notes`' sentences;
  `parent_pin::event_row` (typed rows, local times, an end past midnight with its date and clock) replaces
  `event_line` for the PWA, and `parent_pin::notices` takes the zone (local times; "Parent PIN" spelled as §4.3);
  the security log keeps its UTC sentences (`log_line`, step 14 relabels it). `kid_lock::flash_text` and
  `parent_pin::flash_text` reworded (Child PIN / Parent PIN, no "sync"). `device_pages::Note` gained `fix`: an
  expert fix (an adb command, a package name) shown only behind an ⓘ after a warning's sentence (§4.2).
- **Shared builder:** `devices::app_rows` (the Apps list's rows, parsed allowlist, installed apps, downloads) is cut
  out of `render_device`; the device page and Lock Screen & PINs (`lock_screen_card`) use it, and step 6 can too.
- **CSS** `?v=13`: `details.edit` (a row that opens its form, the chevron turning), `.edit-body`, `.field-hint`, the
  warning's inline ⓘ (`.attention-sub details.info`).
- **Docs (L8):** `server/CLAUDE.md` (step 10's PIN lock, the screen timeout, Parent PIN, the lock's glance page, the
  scroll rule's example, and a "Lock Screen & PINs" section), `docs/testing/emulator.md` (setup and the lock runbook),
  `PLAN.md` (the glance page), the Locate page's Lock hint.
- **Tests:** `tests/device_pages.rs` (the moved forms gone from the device page, its row and value, the groups'
  order, the PIN forms inside their `<details>`, the Remove confirm, the redirect table, a `<noscript>` Save for every
  auto-save, a phone not set up, the 404); repointed through `device_page(id, Section::Lock)` (new): `parent_pin.rs`,
  `step10.rs` (the lock-state warnings, the adb fix behind its ⓘ), `lock_glance.rs`, `window_relock.rs` (the
  foreign-app link), `device_api.rs` (the screen timeout), `wallpapers.rs` (the background), `devices_list.rs` (the
  Attention targets exist), `shell.rs`; unit tests in `kid_lock`, `parent_pin` (rows and notices in Oslo time),
  `lock_glance`, `attention`. `cargo test` 757 passed; `node --test jstest/*.test.js` 16 passed; fmt clean; clippy:
  the same 13 warnings, none in the step's files.

Choices made while building:
1. **The PIN rows open a form, not a picker:** §3.2 says the row "opens a `<details>` under it with the change field
   and Save". The forms keep `required` off the Parent PIN field (an empty field with "Remove it instead" ticked is
   a valid post) and put it on the Child PIN's (its remove has its own form now).
2. **A refusal opens the form again** (and an easy Parent PIN, which is saved but invites a better one), so the
   parent can retype without hunting for the row.
3. **"Recent use" is its own group** under the Parent PIN's footer, not rows in the PIN group: up to 10 rows would
   push the footer and its ⓘ a screen away.
4. **"On the phone now"** for the screen timeout shows only while the reported value differs from the chosen one
   (the old card always repeated it).
5. **The device page keeps the Status card's Parent PIN notices** and the no-PIN card until step 10 builds the
   summary; their links go to this page.
6. **Warnings with an adb fix** (`android_credential`, a VoIP app without full-screen intents) show the command and
   the package name only behind the warning's ⓘ (§4.2), via `Note.fix`. Step 3's Unsuspendable note still lists its
   packages in the sentence; it can move to `fix` when Advanced is next touched.
7. **"No Parent PIN set"** is a warning on this page too (only when managed, as the Attention alarm), so the
   Attention row lands on its explanation.
8. **Labels:** "Turn off after", "Background", "Show message text", "Notifications on the lock screen" and the
   footers follow the LockScreen mockup and §6.0's table; "Show what's playing" is not built (no such setting; the
   ⓘ says the lock shows it).
9. **The Child PIN's ⓘ** drops the old card's fingerprint, safe-mode and Locate sentences: safe mode has its own
   warning while it is allowed, and the rest is §4.5's cut.
10. **A ticked app outside the list** still shows its package name as its label (the server knows no other name for
    it), as before.
11. **A phone not set up has the page** (qa-31-s4-code L6): its PINs can be set before setup, so the first policy the
    phone fetches already has a Parent PIN and it is never managed without one (the old device page offered the PIN
    cards before setup too). §3.2's not-enrolled paragraph now makes this row the exception to "After setup".

Deferred, with the reason:
- **The browser pass** (390 px, light and dark, JS off) is owed: this VM has no browser. The page uses only step 1-3
  components plus the four small rules above.

**Fix round after the code QA** (`qa-31-s4-code.md`: 0 High, 0 Medium, 6 Low, 4 nits; all fixed):

| # | Fix |
|---|---|
| L1 | `parent_pin::flashes_render_inside_their_groups` checks that a refused Child PIN (`needs_override`) opens its form and a saved one (`saved`) doesn't. The mutation `child_open: false` now fails it. |
| L2 | `device_pages::lock_screen_and_pins_holds_the_moved_forms` checks `new_pin` is `type="password"` with `autocomplete="new-password"` and `new_kid_pin` plain text. The mutation `type="text"` now fails it. |
| L3 | "On the phone" reads "Locked · 10:59" / "Unlocked · yesterday 21:40" (`Zone::when` of the report's `reported_at`), "At the phone's last report." behind its ⓘ; `LockNote::note` takes the time. Test: `device_pages::the_lock_state_says_when_it_was_reported` (a report from yesterday). |
| L4 | `device_pages::load_policy` returns a `Result`: a missing row is the defaults, a DB error the 500 "Couldn't read this phone - check the server log." on Lock Screen & PINs, About and Advanced. Test: `device_pages::an_unreadable_policy_is_an_error_not_off`. |
| L5 | CrashGuard: "…tries it again within 10 minutes of its next check-in…"; KeyguardNotDisabled: "Vibb may no longer manage this phone.", the device-owner check (`adb shell dpm list-owners`) in its `fix`. The warnings test hides "sync", "device owner" and an unknown code. |
| L6 | Choice 11 above, and §3.2's not-enrolled paragraph amended. |
| N1 | `server/CLAUDE.md`: the lock background returns to `/devices/{id}/lock`; wrong tries are on Lock Screen & PINs › Recent use and counted in the Status card. |
| N2 | `parent_pin::card(…, with_events)`: the device page passes `false` and loads no event list. |
| N3 | `device_pages::has_capability`, used by Lock Screen & PINs and `escapes_card`. |
| N4 | An unknown `inactive` code: "Vibb reported a reason this server doesn't know.", the code behind the ⓘ. |
| Checks | `cargo test` 759 passed; `node --test jstest/*.test.js` 16 passed; fmt clean; clippy: the same 13 warnings, none in the step's files. |

## Step 5 implementation status (2026-10-13)

Built on branch `design-31-s5` (from `main` at `58e97c1f`, steps 1-4 and 11 merged); server only, no migration, no
new crate, no API change (the policy JSON, its snapshot and the launcher are untouched).
- **Routes:** `GET /devices/{id}/home` (`device_pages::view_home`, `device_home.html`) and `GET /devices/{id}/filter`
  (`device_pages::view_filter`, `device_filter.html`). Unknown phone 404, a DB error 500 (`load_device`,
  `load_policy`, and every read the page needs). Every POST route is unchanged.
- **Home Screen:** Language and Apps per row as two inline selects in **one** form (`/launcher`, §5.6); the
  "Notification access off" warning above that group (§4.5 #3: the sentence visible, the adb command behind its ⓘ);
  **Wallpapers**, the thumbnail grid as one form (`/wallpapers`), the upload page and the lock's background behind the
  footer's ⓘ; **Quick controls**, the four switches as one form posting `/policy` with `section=quick` (Wi-Fi,
  Bluetooth, Screen brightness, Sound or silent; §4.5 #12/#13 footer and ⓘ).
- **Web Filter:** the **Web filter** switch (`/policy` with `section=filter`; §4.5 #11's footer, the rest behind its
  ⓘ with a link to Settings › Web Filter); **Blocklists**, one inline select per list - "Default (On)" / On / Off
  (`/dns/devices/{id}/blocklists/{list}/override`, `back=filter`) - with a list's download problem under its name
  ("Not loaded yet", "Last download failed"); **Blocked on this phone** and **Allowed on this phone**, each domain a row
  with Remove (one tap, §5.6) and an add row (`/dns/domains/new` with `device_id` and `back=filter`; the field has
  `pattern`, the server checks again); **Blocked Activity**, the "Keep a log" switch (`/dns/log/{id}`) and, while on,
  the newest 200 entries (domain, list, local time with the exact one in `title`).
- **Forms (§5.1, §5.6):** `policy` takes `section` (H1): `quick` writes only `quick_controls_mask`, `filter` only
  `vpn_filter_enabled` (`COALESCE` of a NULL bind keeps the other), no section writes both as before, another value is
  a 400; `kiosk_desired = 1` and the lock-task features in every case. The domain and list routes take `back`
  (`dns_filter::back_to`): `filter` → the phone's page, the phone from the path or the deleted row, never from the
  form; anything else → `/dns`. Every form on both pages returns to its page with **no fragment**, so
  `scroll-restore.js` keeps the exact place; each auto-save has a `<noscript>` Save.
- **Redirects (L5):** `/dns?device=X` → `/devices/X/filter`, `/dns/log?device=X` → `/devices/X/filter#log`.
- **The device page:** the Launcher, Wallpapers and "Quick Controls + Ad/content filtering" cards are gone; two rows,
  **Home Screen · 3 per row** and **Web Filter · On/Off**, follow Lock Screen & PINs. The notification-access
  Attention row targets `/home#layout`, from the same `device_pages::notification_access_note` the page shows.
- **Rust texts (M5):** the notification-access warning is a typed `Note` (it was template text); a feed's state is
  `dns_filter::FeedState` (`problem()` for a phone's row, `sentence()` for the global page until step 14); the log's
  times go through `display_time` (a time the phone sent garbled reads "Time unknown", never the raw text).
- **The global pages** (step 14 rebuilds them): `/dns` lost its per-device card for an "Each phone" group linking each
  phone's Web Filter page; `/dns/log` is a list of phones, "Log on/off" ›, linking to `#log`.
- **CSS** `?v=15` (step 12 takes `v=14`): `.row-action` (a text button at a row's end), `input.row-input` (a field that
  is the row), `.wallpaper-grid`/`.wallpaper-choice`/`.wallpaper-thumb`.
- **Docs (L8):** `server/CLAUDE.md` (launcher_ui, the listener warning, wallpapers, the VPN switch, the DNS log, sound
  mode, Parent PIN, the lock's glance page, and a "Home Screen and Web Filter" section), `server/DEPLOY.md` (a feed's
  state on a phone's page). `docs/testing/emulator.md`, `docs/setup/google-account.md`, `launcher/CLAUDE.md` and
  `PLAN.md` name none of the moved cards.
- **Tests:** `tests/device_pages.rs` (both pages, the device page's two rows and their values, the moved forms gone
  from it, the section test `a_policy_section_leaves_the_other_section_alone`, the redirect table built from the
  rendered forms of both pages `home_and_filter_forms_return_to_their_page`, the old URLs, bad input on the shared
  routes, unreadable parts as 500, a phone not set up, the log's local times and escaping); repointed through
  `device_page(id, Section::Home | Filter)` (new): `launcher_ui.rs` (the form, the warning with its fix behind ⓘ and
  the Attention link), `wallpapers.rs`, `sound_mode.rs` (`section=quick`), `cleanup.rs` (the log switch),
  `dns_blocklist.rs` (a list's problem on the phone's page, no raw error), `lock_glance.rs`, `devices_list.rs`
  (`home#layout` exists), `shell.rs`; `attention`'s unit test. `cargo test` 768 passed; `node --test
  jstest/*.test.js` 16 passed; fmt clean; clippy: the same 13 warnings, none in the step's files.

Choices made while building:
1. **A list's setting is an inline select** (Default / On / Off), not today's switch plus "Use default" button: one
   control per row, the picker pattern of §3.0. The route takes `setting`; an older page's `clear`/`enabled` still
   works.
2. **The domain routes check the input:** a name that isn't a domain (`dns_engine::looks_like_a_domain`, made
   `pub(crate)`) is a 400 and nothing is added (before, anything was stored); a `device_id` that isn't a number is a
   400 instead of silently "every phone"; an unknown phone or list is a 404 (a foreign key); a DB error is a 500
   (before: logged nowhere, redirected as if saved). The same for `update_policy` (it logged and redirected).
3. **A second tap on Remove** (the row already gone) changes nothing and returns to `/dns`, since the phone comes only
   from the row.
4. **Home Screen and Web Filter answer for a phone not set up** (as About and Advanced): what is saved reaches the phone
   with its first settings. Whether step 10 greys their rows "After setup" is its call.
5. **A failed wallpaper read is a 500**, not an empty grid: the grid's `<noscript>` Save would untick every wallpaper.
6. **The first group on Home Screen has no header** (the page title says it); its section is `#layout`, the Attention
   row's anchor. The footers: "The language of Vibb's own screens on the phone.", "Your child picks from these on the
   phone.", §4.5 #12 for Quick controls.
7. **The notification-access sentence** keeps the old card's facts: unread counts and the music card off, the lock
   screen shows only missed calls (`lock_glance::the_home_screen_warning_and_the_lock_page_name_the_lock_screen`).
8. **The log** shows only while it is on (off: the switch and its footer only), at the bottom, so 200 rows push no form
   away.
9. **The global `/dns` and `/dns/log`** got the smallest change that removes their per-phone parts (a phone list each);
   their restyle stays step 14's.

Deferred, with the reason:
- **The browser pass** (390 px, light and dark, JS off) is owed: this VM has no browser. New CSS is the few small
  rules above; the pages otherwise use step 1-4 components.
