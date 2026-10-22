# 61 - Vibb boxes in Vibb Phone: an assessment

Assessment only (2026-10-22). Nothing is built. Design 60 is reserved for the hardware-bound token.

**The question, as the user framed it (2026-10-22):** move the vibb box's own parent PWA (the web UI every Pi serves
today, `pi/web/`) into the handy server. The always-on server becomes the one place to add music and tweak a box's
settings. Each box then runs a slim agent that pulls its library and settings, and keeps playing offline from its
last copy. The box's own web UI shrinks to what must stay local, or goes.

**Scope:** the library and the box's settings, mainly. Not the phones' other functions (policies, time rules,
Locate, app control, commands). Those are in §8, "Not in scope / later".

`V/` = palchrb/vibb, branch `pipewire`, commit `1fcadac`. `S/` = `server/src/`. Line numbers are today's.

## 1. A vibb box today, in short

- **Hardware:** Pi Zero 2 W, Pirate Audio HAT (240x240 screen, four buttons, speaker), optional PiSugar 3 (battery +
  RTC) (`V/README.md:90-100`).
- **Stack:** `vibbd` (`V/pi/daemon.py`, Python stdlib) owns playback and serves the PWA and API on `0.0.0.0:3679`
  (`daemon.py:333-338`, `:6098`), plus a captive-portal redirect on `:80` (`daemon.py:5758`). mpv plays NRK, RSS,
  Storytel and own files; go-librespot (or Soloist) plays Spotify; Sonos is a sidecar where the box is the
  controller, bound to 127.0.0.1 (`V/pi/sonosd.py:1-40`). PipeWire is an install toggle (`V/pi/install.sh:20-48`).
- **Internal clients use the same API:** the screen UI, the buttons, btwatchd, idle, RFID and MPRIS all call vibbd on
  `127.0.0.1:3679` through `V/pi/vibb/boxapi.py`. So vibbd's HTTP server is also the box's IPC.
- **Library:** `/etc/vibb/library.json`, `sections[] -> entries[] {id, name, target, order, cache, resume}`
  (`V/pi/vibb/library.py:20`, `:43-101`). Bookmarks keyed by `state_key(target)` (`library.py:29-35`).
- **Auth:** reads and playback are open on the LAN; privileged calls need a per-box token, linked by a QR on the
  screen, sent over plain HTTP (`V/SECURITY.md:28-60`, `:223-236`).
- **Storytel:** the family's e-mail and password, stored recoverable on the box (`V/pi/vibb/storytel.py:18-23`).
- **Updates:** SSH, `git pull && sudo ./pi/install.sh` (`V/README.md:231-237`).
- **Power:** off after 5 idle minutes by default (`V/pi/vibb/sysinfo.py:35`). A box is off most of the day.
- **Licence:** `V/LICENSE` is MIT; `V/README.md:322-324` still says "TBD".

vibb already chose this direction: "cloud-first ADMIN, local-first PLAYBACK" (`V/SPEC.md:92-121`, 2026-07-07). A
service owns the library and settings; the box syncs them and stays autonomous at runtime. The handy server can be
that service.

## 2. The box's PWA today, function by function

From `V/pi/web/index.html` (three tabs: Player, Library, Settings) and `V/pi/web/app.js`/`backup.html`'s API calls.
"handy" = what the handy PWA already has. "After" = where it lives once the box PWA is retired.

| Box PWA function | Box API | handy today | After |
|---|---|---|---|
| **Library: add by link** (Spotify, NRK, RSS), order, offline cache, resume | `PUT /library` | **Yes**: Music › Add, entry settings (`docs/notes/server/music.md:22-26`) | Server |
| Library: sections, rename, reorder, delete | `PUT /library` | **Yes**: categories (icon + colour) and entries | Server |
| Section logo (uploaded image) | `/library/section-logo` | Partly: a category has an icon and colour, no upload | Server (icon/colour) |
| **Own audio: upload, collections, delete** | `/media/upload`, `/media/delete` | **Yes**: own-files entries with uploads (`music.md:24-25`) | Server; the box downloads |
| **Storytel: account** | `/storytel/credentials`, `/logout` | **Yes**: Music › Settings, sealed on the server; design 58's token route | Server; the password leaves the box |
| **Storytel: pick series/books from the shelf** | `/storytel/shelf` | **Yes**: Music › Storytel add (`music.md:185-192`) | Server |
| Spotify: follow a profile's public playlists | `spotify_user` sections | **No** (the import skips them, `music.md:86-88`) | Gap (Q2) |
| NRK live channels | a `direkte` target | **No**: `parse_link` refuses them (`S/music.rs:2137`) | Gap (Q2) |
| Local folder as an entry (files copied onto the SD card) | a `/...` target | No; own files live on the server | Gap, small (upload instead) |
| Import a box library | - | **Yes**: `/music/import` (design 21a) | Server, one-off migration |
| **Settings** (13 keys, §3) | `GET`/`PUT /settings` | No | Server (the managed ones) |
| System info: battery, disk, version, Wi-Fi | `GET /system` | No | Server, from the agent's report |
| Player: now playing, play/pause/next/prev, shuffle, stop, volume, seek, queue | `/status`, `/play`... | No (out of scope) | Box screen, buttons, BT/AVRCP |
| Audio out: built-in / BT / Sonos room | `/output`, `/sonos` | No | Box screen (hold X, `V/pi/ui.py:2556-2569`) |
| Bluetooth: scan, pair nearest, pair from car, connect, forget | `/bt/*` | No | Box screen (`ui.py:2798-2800`) |
| Bluetooth: rename a speaker | `/bt/rename` | No | Lost, or a server setting later |
| Wi-Fi: scan, connect, forget, **add a network ahead of a trip**, hotspot | `/wifi/*`, `/system/wifi` | No | Hotspot setup page (§5); Q3 |
| Spotify: switch account, logout | `/spotify/logout` | No (the phones' Spotify is another model) | A screen row; Spotify app over zeroconf |
| Soloist: API key, pair, update | `/soloist/*` | No | SSH / install-time (opt-in engine) |
| Backup: rclone remote, now, snapshots, restore | `/backup/*` | No | SSH, or the server keeps the box's blob (Q5) |
| Restart, shut down | `/system/shutdown` | No | Box screen (`ui.py:2796-2797`) |
| "This phone": link with the box token | QR on the screen | n/a | Gone |

**The verdict:** the daily parent work - curating music, own files, Storytel - is already in handy, built from
vibb's own logic (designs 20, 21, 21a, 21b, 26b, 58). What is missing in handy is the box's settings, a box status
view, two source kinds (Spotify profile-follow, NRK live) and a decision on Wi-Fi and backup. Everything else is
hardware that the box's screen already does.

## 3. The box's settings: which are worth the server

One file, `/etc/vibb/settings.json`, 13 numeric keys (`V/pi/vibb/sysinfo.py:32-61`), validated by
`update_settings` (`sysinfo.py:92-115`) via `PUT /settings` (`daemon.py:3719-3723`). Five are also on the screen's
settings menu (`ui.py:2784-2789`, `:2905-2912`).

| Key | Range (default) | Screen | On the server? | Why |
|---|---|---|---|---|
| `volume_cap` | 30-100 (100) | yes | **Yes** | Child safety; matches the phones' music cap (`music.md:39`). |
| `idle_shutdown_min` | 0-240 (5) | yes | **Yes** | Battery vs. "it switched itself off". |
| `resume_window_h` | -1..168 (-1) | no | **Yes** | "Continue after power-on". |
| `simple_nav` | 0-2 (0) | yes | **Yes** | Browsing style: menus / carousel / categories. |
| `screen_timeout_s` | 0-600 (30) | yes | Yes | Cheap. |
| `screen_brightness` | 10-100 (100) | yes | Yes | Cheap. |
| `storytel_sync` | 0/1 (1) | no | Yes | Positions back to Storytel; tied to the server's Storytel. |
| `spotify_bitrate` | 96/160/320 (160) | no | Yes | Data use; a change restarts Spotify on the box. |
| `spotify_cache_gb` | 1-100 (20) | no | Maybe | Depends on the SD card. |
| `wifi_auto_off_min`, `wifi_ps_bt_off`, `wifi_probe` | see file | no | **No** | Radio tuning. A bad value can keep the box off Wi-Fi, and then it never fetches the fix. |

**Catch 1 - the screen can override.** The screen cycles `volume_cap` 60..100 (`ui.py:2907`); anyone holding the box,
the kid too, can raise it. A server-managed cap means something only if the box shows managed rows without letting
them cycle. That is a small vibb change.

**Catch 2 - late delivery.** The box is usually off. A change lands when the box is next on and online. The PWA says
"Waiting for the box" until the box reports the value, like Device › Time's lifts
(`docs/notes/server/time-rules.md:9`).

## 4. What the server needs

Most of it exists:
- **A per-device library:** `GET /api/devices/music/library` (`S/handlers/music_api.rs:66-84`), ETag/304, gzip,
  built from that device's ticks (`S/music.rs:1049-1061`, `:1089-1106`). Nothing Android-specific in it. The entry
  `key` is vibb's `state_key` (`music.md:10`), so bookmarks carry over where targets match.
- **Own files, covers, listings:** `S/device_routes.rs:88-103`.
- **Storytel JWT for a device:** `POST /api/devices/music/storytel/token` (`S/handlers/music_api.rs:421`, design 58).
- **Enrolment:** `{enrollment_code}` -> a bearer token (`S/models.rs:864-872`;
  `docs/notes/server/architecture.md:7-8`).

New:
- `devices.kind` (`phone`/`box`). Today the table is generic (`server/migrations/0001_init.sql:15-24`), but every
  page around it is a phone's.
- "Add a Vibb box" and a **Box page**: Music (the entry switches; no mobile-data, storage-limit or Sonos-room
  settings, which are the phone app's), Settings (§3), Status (§5).
- **Route scoping per kind.** Today any device token reaches every device route (`S/device_routes.rs:26-113`). A box
  token must reach only the music routes and the new box routes: never policy, contact photos, wallpapers, APKs,
  DNS lists.
- `box_settings` (the managed keys, vibb's ranges copied and pinned by a contract file),
  `GET /api/devices/box/settings` (ETag), `POST /api/devices/box/status`.
- The Spotify gate (`spotify_gate!()`, `S/music.rs:1059`) skipped for a box: a box plays with its own Spotify login,
  not a phone's "Plays as" account.
- Gaps, if wanted (Q2): NRK live channels and Spotify profile-follow in the catalog.

## 5. The box after: a slim agent, and can the web server go?

**The agent** (`vibb-handy`, Python stdlib, in vibb, a separate process that talks to vibbd through `boxapi.py`):
- enrols once with a code (SSH `sudo vibb-handy enroll <url> <code>`, or typed from the hotspot setup page);
- at boot, after Wi-Fi joins, and every ~15 min while on, pulls the library and settings (304 when unchanged);
- writes the library through vibbd's own `PUT /library` (normalise, prune, cache sweep as today,
  `daemon.py:3696-3718`): category -> section, entry -> `{id: "h<id>", name, target, order, cache, resume}`;
- downloads own files to `/var/lib/vibb/handy/<entry>/` and points the entry at that folder;
- applies settings through vibbd's `PUT /settings`, so vibb's validation still rules;
- reports version, battery, disk, the library version applied, the settings it now has, Storytel state and
  per-entry errors/downloads (the shape of `server/testdata/music_state.json`). No now-playing: the server drops it
  for phones on purpose (`S/music.rs:2508-2523`; design 20 decision 5).

vibbd changes little: Storytel asks the server's token route instead of logging in (`storytel.py:299-333`; the box
deletes `/etc/vibb/storytel.json`), and managed settings rows on the screen stop cycling.

**Offline:** the last `library.json` and `settings.json` stay on disk. Server down, cabin, car: the box plays as
today. It keeps its own NRK/RSS/Storytel/Spotify expansion (`V/pi/vibb/content.py`); it does not depend on the
server's listings. The analogue of "the phone never serves, the server owns rules".

**Can the box drop its web server? Yes, as a LAN service.** Not as a process: vibbd's HTTP API is the box's IPC, so it
stays, bound to loopback. `VIBB_BIND` already exists (`daemon.py:338`); set it to `127.0.0.1`. Then:
- `pi/web/` (about 2,500 lines: `app.js` 1,589, `index.html`, `style.css`, `backup.html`) and the token-linking
  flow can go. The PWA-only endpoints (`/library/section-logo`, `/media/*`, `/storytel/credentials|shelf|logout`,
  `/backup/*` UI) go or become internal.
- **One local web surface remains: Wi-Fi setup in hotspot mode.** A box that finds no known network starts its hotspot
  and captive portal (`V/README.md` §8). With no PWA, the screen can't type a password. So keep a tiny setup page
  that listens on `10.42.0.1` **only while the hotspot is up** (and with a per-box PSK, `V/SECURITY.md:130`).
  Adding the cabin's network ahead of a trip could become a server setting later (Q3).
- **What is lost:** LAN remote control from a phone ("Hey Siri, pause Vibb", the Player tab), Bluetooth rename, the
  backup UI, the Soloist key UI. The screen, the buttons, a BT speaker's or car's AVRCP keys, and Spotify Connect from
  the Spotify app still control playback.

**The security win, concretely:**
- The box no longer listens on the LAN. Gone from `V/SECURITY.md`'s surface table (`:28-36`): `vibbd :3679` on
  `0.0.0.0` (open playback and reads, token-gated privileged calls) and the `:80` portal outside hotspot mode.
- Gone with it: the token over plain HTTP (`SECURITY.md:223-236`), the CSRF class that rests on "no CORS, no OPTIONS,
  JSON content type" (`:41-45`, `:112-120`), the file-serving GET class that leaked licensed audio (`:60-70`), and the QR link
  any kid holding the box can show.
- **The Storytel password leaves the box** (design 58). Today it sits recoverable on an SD card the kid carries.
- The box becomes outbound-only, like the phones. What still listens: sshd (owner-managed), mDNS, go-librespot's
  zeroconf for Spotify Connect (locked to one account), and the setup page during hotspot only.
- New, and smaller: the server bearer token in a root-owned file on the SD card, scoped to that box's music and box
  routes. A copied card gets that box's library, own files and a Storytel JWT; deleting the box revokes it.

## 6. Costs and risks

- **Server:** a device kind, token scoping, a Box page with Music/Settings/Status, two routes, a settings table, a
  contract file. Moderate, mostly server and templates. No launcher or music app change.
- **vibb:** the agent, the Storytel token swap, locked screen rows, the hotspot-only setup page, loopback bind,
  deleting the PWA. Small to moderate.
- **Network path.** Phones reach the server over embedded tsnet. A box can use (1) the home LAN to the server's device
  listener (`DEVICE_BIND_ADDR`, `docs/notes/server/connectivity.md:7-10`; plain HTTP unless the server has a LAN
  certificate), (2) tailscaled on the box (battery; against vibb's "remote access on demand",
  `V/docs/remote-access.md:12-20`), or (3) a public HTTPS device listener if the user exposes one
  (`server/DEPLOY.md` §2). Config is not a runtime need, so LAN-only is acceptable: changes and new Storytel books
  arrive at home.
- **A parent away from home** edits on the server at once; the box picks it up when it is next home and on. Today
  the parent can't reach the box at all away from home, so this is no loss.
- **The cross-repo contract.** This repo's CI pins its contracts; vibb has no link to it. Mitigation: a `v` field, a
  box capability word, contract copies in vibb's `tests/`. Or bring `pi/` into this monorepo (Q6).
- **Migration:** the import exists (design 21a). Bookmarks survive where the normalised target gives the same
  `state_key`; the agent should map an imported entry to the box's old target by key so no position is lost. Live
  channels, folders and profile-follow sections need Q2.
- **Losing LAN control** (§5) is a real change for a family that uses the phone shortcut.
- **Licensing:** no conflict. vibb is MIT, handy GPL-3.0-or-later, and they only talk HTTP. Porting vibb code into
  handy is fine; GPL handy code must not go into vibb's MIT tree unless vibb is relicensed.

## 7. Options, smallest first

| | Gives | Costs | Box PWA |
|---|---|---|---|
| **(0) Status quo + import** | A one-off move (design 21a exists). | Two places to curate, forever. | Stays |
| **(a) Library from the server** | One catalog; own files from the server; the Storytel password off the box. | Box kind, scoping, Box page (server); agent, Storytel swap (vibb). | Stays, Library tab read-only |
| **(b) + settings and status** | Cap, auto-off, resume, browse style etc. in the PWA; battery, version, errors. | `box_settings`, two routes, Settings/Status cards; apply, report, locked screen rows. | Stays for hardware |
| **(c) Box PWA retired** | One parent UI; the box outbound-only; ~2,500 lines of web UI gone. | Hotspot-only setup page; decide Wi-Fi pre-add, backup, BT rename (Q3, Q5). | Gone; loopback only |

(a) and (b) are steps on the way to (c), and each is useful alone.

## 8. Not in scope / later

- **Time rules / quiet hours** for boxes: the box has none today; they would need a trusted clock (no RTC without a
  PiSugar). Later, if at all.
- **Remote commands** (play/pause/stop, sleep): the box is usually off, so a queue would mostly wait.
- **The phone casting to a box:** the phone's rooms are Sonos-only (SSDP `ZonePlayer`, `music/.../sonos/Ssdp.kt:25`;
  the Sonos root certificate, `SonosSocket.kt:148-154`). It would be a fourth renderer in the music app, and it needs
  a LAN API on the box, which (c) removes.
- **Box software updates from the server:** SSH stays; the PWA shows the version first.
- **Locate, app control, policies:** not applicable.

## 9. Recommendation

**Yes: move the box's parent UI into the handy server, and retire the box's PWA - option (c), reached through (a) and
(b).**

Why: the hard part is already built. handy's music catalog, own files and Storytel were ported from vibb and cover
the box PWA's daily work. What remains is a box kind, a settings card and a status card on the server, and a slim
agent on the box. The box keeps playing from its last copy, so the risk to playback is low. And the security gain is
large: the box stops serving on the LAN, and the family's Storytel password leaves the SD card.

Keep it narrow: the box keeps its own expansion, Spotify login and hardware controls on the screen. No commands,
time rules or casting.

Do it only if the user accepts three things: (1) phones lose LAN remote control of the box; (2) a hotspot-only setup
page stays for Wi-Fi; (3) managed settings rows on the screen stop cycling.

## 10. Step plan (rough)

1. **Design 61a - boxes in the server** (`server/`): `devices.kind`, "Add a Vibb box", route scoping, the Box page
   with Music ticks, the Spotify gate skipped for a box. Server tests.
2. **vibb: the agent, library only** (palchrb/vibb): enrol, poll, translate through `PUT /library`, own-file
   downloads, the Storytel token swap, the box PWA's Library tab read-only. Contract copies in vibb's `tests/`.
3. **Field trial on one box:** import its library, tick it, enrol, compare bookmarks, stop the server and play offline.
4. **Design 61b - settings and status** (both): `box_settings` and its route, the status route, the Box page's
   Settings and Status cards with "Waiting for the box"; in vibb, apply, report and lock the managed screen rows.
5. **Design 61c - retire the box PWA** (vibb, plus server gaps from Q2/Q3/Q5): `VIBB_BIND=127.0.0.1`, the
   hotspot-only setup page with a per-box PSK, delete `pi/web/` and the token-link flow, update `V/SECURITY.md`.
6. **Later, if wanted:** NRK live and Spotify profile-follow in the catalog, Wi-Fi pre-add from the server, the box's
   encrypted backup kept by the server.

## 11. Open questions for the user

1. **Network:** does a box sync over the home LAN only (simplest), a public HTTPS device listener, or tailscaled
   (battery)?
2. **Gaps:** add NRK live channels and Spotify profile-follow to the catalog, or drop them? Local folders become
   uploads - OK?
3. **Wi-Fi:** is the hotspot-only setup page enough, or should the parent pre-add a trip's network from the server
   (its password then passes through the server)?
4. **LAN control:** OK to lose "pause from the phone" and the Player tab, leaving the screen, buttons, AVRCP and
   Spotify Connect?
5. **Backup:** today the box backs up library, settings, logins and positions to rclone. With library, settings and
   Storytel on the server, keep rclone for the rest (positions, Spotify login, BT pairings) over SSH, or let the
   server keep the box's encrypted blob (under 1 MB)?
6. **Repo:** keep vibb separate with copied contract files, or bring `pi/` into this monorepo so CI pins both sides?
   Fix `V/README.md`'s "License: TBD" either way.
7. **Status:** are version, battery, disk and errors enough, or do you want now-playing for boxes (design 20 said no
   for phones)?
