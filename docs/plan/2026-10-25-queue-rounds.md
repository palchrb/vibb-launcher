# The queue in rounds and waves (2026-10-25)

Planner's note for the coordinator and the user. Input: PLAN.md "Fix backlog" (the queue "Approved, not built"
and its detailed entries), every design note in `docs/design/` with steps or a status section, the run docs
(`docs/testing/2026-10-25-evening-run.md`, `2026-10-24-home-round-run.md` "Owed after launcher-small-a",
`2026-10-25-r10-keystore-run.md`, `2026-10-25-r13-run.md`), `docs/testing/emulator.md` §6p/§6u, and
`git branch --no-merged main` with the remote branches. Main at c8bba7b3. Not planned here: the two rounds in build,
`launcher-glance-b` (contact photos on glance rows incl. WhatsApp by `tel:`, the sender in group rows, the Full-text
count, the provisioning window) and `pwa-fixes-d` (login after a Tailscale outage, Locate's answer row, the Home
Screen help line).

**The user's decision of 2026-10-25 applies throughout: nothing Spotify-related is built for now** ("vi lar det
ligge mtp spotify, ikke implementer noe mer der nå"). Every Spotify step found below is PARKED with that reason and
sits in no round.

Sizes: XS under an hour of implementer time, S a few hours, M a day, L several days. "Verifies": **unit** (Kotlin/Rust
tests only), **jstest** (the server's `server/jstest/`), **browser** (Playwright Chromium + WebKit pass), **emu** (an
emulator session, the coordinator's), **JS** (the user's Jelly Star), **iPhone** (the user's iPhone PWA).

---

## 0. Open user decisions (answer before the round that needs it; each has a default)

Asked once, in this order; a default is what the round does if there is no answer by the time it starts.

1. **Stable `launcher-v0.37.0`?** 0.37.0 has rc.5-rc.8 left (N = 1..8, 9 is the release). *Default:* cut the stable
   `launcher-v0.37.0` after Jelly Star session J1 and emulator session E1 pass, before any phase D work; phase D
   starts the 0.38.0 line. (A stable tag moves the provisioning QR's `releases/latest` URL.)
2. **The hybrid on the Jelly Star (design 59 §9) - when does J1 happen?** Phase D, design 60, 24b S4, the
   fingerprint help line, RC3-4, missed Element X calls and the App blocker all wait on J1. *Default:* J1 right after
   rc.5 (wave 3), with the checklist in §4 ("Where the Jelly Star sits").
3. **VoIP small fixes (PLAN queue):** (b) "no SOS guess at a VoIP wake" and (c) "no BlockedAppActivity flash" exist
   only in own mode (design 59's phase D deletes the code they live in). *Default:* drop (b) and (c) now, build (d)
   READ_PHONE_STATE for allowlisted apps in L2, and build (a) "Tilbake til samtalen" only if E1 reproduces "No VoIP call
   screen to bring back" in **android** mode; otherwise (a) goes too.
4. **Design 32b (one stale frame at screen-on):** own-mode lock only. *Default:* E1 checks the screen-on frame in
   android mode; if there is no stale frame there, 32b is dropped with phase D and no note is written.
5. **The dead-code review that never merged** (`origin/review-dead-code`, `docs/review/dead-code-and-duplication-2026-10-13.md`,
   its §9): D1 drop the pre-step-6 time-policy legacy (both sides, about 460 lines, a contract change) - *default yes*;
   D2 vestigial schema (`launcher_releases`, `push_state_json`) - *default leave*; D3 the one-shot cleanups
   (`FirebaseCleanup`/`FirebaseLeftovers`, `remove_journal_media`, the FCM warning) - *default yes* (the only phone runs a
   post-19 launcher); D4 the unreachable `POST /dns/blocklists/{id}/delete` - *default delete the route*; D5 the
   minimalist leftovers in the MIT code - *default delete*. D6 (Spotify/Sonos Wi-Fi locks) and D7 (`SPOTIFY_PLAYABLE`)
   are Spotify: PARKED.
6. **`KidHeader.SHOW_BACK_BUTTON`** (PLAN "Pending user decision" since 2026-10-06: drop the top-left back buttons on
   full pages?). *Default:* keep the buttons, delete the flag.
7. **The 11 kiosk-escape switches** (`update_fence`, `notification_auto_cancel`, both `DEFAULT 0` "until A3 and B5
   pass", never checked). *Default:* §6d A1-A5/B1-B5 in E1, A3 on the Jelly Star in J1, then the defaults go on in the
   next server round; if J1 fails A3, drop the fence.
8. **24b S4 (safe mode blocked by default)**, built 2026-10-11 on `origin/relock-24b-s4`, gated on design 04's check 6
   (safe boot on the Jelly Star). *Default:* check 6 in J1; on a pass, rebase, renumber its migration (0065 clashes
   with main's `0065_command_receipts.sql`), merge in the next server+launcher round; on a fail, delete the branch.
9. **Design 58 step 3 (retire the old Storytel login route and the bridge's `getStorytelLogin`).** Its title claim
   ("the password never leaves the server") is not true while the old route lives. It needs every phone on a music
   app with step 2 (`music-v0.5.0-rc.3`+). *Default:* yes, round B3.
10. **Settings saves in the security log - what counts as a save?** *Default:* a POST on an admin route answered with
    a 303 back to its page (the handlers' success path), never a 4xx or a re-rendered form with an error; `/music`
    included; coalesced per page for 10 minutes.
11. **Wi-Fi in Phone Setup, (A) or (B)?** *Default:* (A), the Wi-Fi in the setup QR only (write-only, out of
    backups), server XS-S. (B) waits.
12. **Configurable emergency menu:** the mockup needs (i) the default extra numbers for Norway (*default:* 112 first,
    then 113 "Ambulanse", 110 "Brann", 116 117 "Legevakt"; added by the parent, none by default), (ii) whether
    non-emergency numbers may be on it at all (*default:* yes, but they follow the call rules, and the PWA says so).
13. **Archive branches:** `proto-59-hybrid`, `proto-59-qa`, `proto-plain-lock` (cited by hash in design 59 and PLAN),
    `spotify-spike`, design 57's `worktree-agent-a689b601f638554b8`. *Default:* tag each as `archive/<name>` (so the
    cited hashes stay reachable), then delete the branches; design 57's prep is never merged (the hybrid replaced
    it). `spotify-spike` and the dormant 54-a1 code on main stay untouched (Spotify parked).
14. **Orphan assessments:** design 56 (Android 13/17: "run the API 37 emulator round now, plus §3.4's four items") and
    design 61 (vibb boxes, option (c)) have no PLAN entry. *Default:* both recorded as parked in PLAN ("Parked") with
    one line each; 56 is picked up when Android 17 is announced for the Jelly Star.
15. **Design 15 §1-3/§5** (a parent-set `message_room` per contact, the server fields, PWA help), never built; only
    the learned on-phone map (`ElementRoomStore`) exists. *Default:* drop; the note gets a "learned variant only"
    banner. (The queue's contact-photo item says "design 15's `message_room`" - see §1.4's note to `launcher-glance-b`.)
16. **Design 22 S5/L1 (Connection page, policy `connection`, QR v2, direct HTTPS for the phones) and S6+L2 (mode 2)**,
    never built. *Default:* park explicitly with a line in 22 ("the tailnet-only setup is the product"); S7 (CSP
    without `unsafe-inline`) moves into server security round S2 (it is server review L5 too).
17. **Design 09 part A (an SMS allowlist)**, postponed since step 9. *Default:* drop from the note into PLAN
    "Parked" as one line.
18. **Design 19b phase 2 (a battery prompt at QR provisioning, "if an OEM battery manager")** - its condition is met
    by the Jelly Star's App blocker. *Default:* no prompt; the App blocker is hidden by the device owner at enrolment
    (queue item, after the user's package dump).
19. **Design 42's "design 55 (to write)"** follow-up. *Default:* drop the reference.
20. **Immich wallpapers** ("scope not settled yet"): the user checks what Immich's share sends for a server-only photo
    (a file or a link). *Default:* parked until that check.
21. **Long-list items in the queue without a scope** - PWA i18n, Home Wi-Fi as a trigger, notifications phase 2
    (ntfy/Matrix). *Default:* stay where they are (queue, "Scope not settled"), no round.

---

## 1. Half-done: the inventory

Read the notes as of main c8bba7b3. The headline: **almost every designed step is merged; what drifted is the
checking and the paperwork.** No design note has a recorded Jelly Star pass; about 15 emulator.md sections or note
checklists were never run; a dozen PLAN entries and note headers still say "not merged" / "awaiting" / "not built" for
work on main, and a few claim more than exists. Real unbuilt steps: 22 S5-S7, 24b S4 (built, unmerged), 32 step 3,
54-a2/54-b (Spotify, parked), 58 step 3, 59 phase D, 15 §1-3/§5, 09 part A, 46 §1.1-1.3 (Spotify, parked).

Classes: **DONE** (merged and checked), **BUILT-UNCHECKED** (merged, checks owed), **PARTIAL** (steps designed and not
built), **SUPERSEDED**/**ASSESSMENT**/**PARKED**.

### 1.1 Designs 01-24b

| Note | Class | Owed / missing | Recommendation |
|---|---|---|---|
| 01 own build | BUILT-UNCHECKED | T6 device checks (01:470): 112 with outgoing calls restricted, `tel:`/`*21*`/USSD refused, kiosk with an empty allowlist reboots, recovery drill | J1 (safety) |
| 02 calls | BUILT-UNCHECKED | direct-boot checks 1-7 (02:671-683), BFU call screening (also owed in the d59 RC run) | J1 (safety-critical) |
| 03 tailscale app | PARTIAL, nothing built | §10 steps 1-12; superseded in part by 22 | Park: a "not built, parked" banner (Round 0a) |
| 04 hardening | BUILT-UNCHECKED | checklist 1-13; **check 6 (safe boot) gates 24b S4** | J1, check 6 first |
| 05 ui/photos/i18n | BUILT-UNCHECKED | checklist 1-8, `allow_listener` across reboots | J1 / E1 |
| 06 time rules | BUILT-UNCHECKED | device checklist 06:108-125 | E1 |
| 07 battery/FCM/Play | FCM half SUPERSEDED by 19; Play half BUILT-UNCHECKED | Play checklist 07:300-338 | J1 (Play auto-update of an allowed app) |
| 08 ui polish | BUILT-UNCHECKED | screenshots; `SHOW_BACK_BUTTON` flag (decision 6) | decision 6, then delete the flag (0b) |
| 09 sms and fixes | PARTIAL by decision | part A never built (no SMS code exists); part B checks D5, MMI/USSD | decision 17; part B checks in J1 |
| 10 lock and call UI | being SUPERSEDED by 59 | checklist never run; no banner pointing to 59 | banner (0a); no device time on own mode |
| 11 kiosk escapes | BUILT-UNCHECKED, **dormant** (switches default off) | §6d A1-A5/B1-B5; follow-ups never built (A5 as a release gate, canary, tsnet from the anchor) | decision 7 |
| 12 missed calls | BUILT-UNCHECKED | checks 1-7 (1-6 emulator-able) | E1 |
| 13 app downloads | BUILT-UNCHECKED | 6 checks, "none run yet" | E1 (Wi-Fi only, resume) |
| 14 app display | BUILT-UNCHECKED | 4 checks | E1 |
| 15 Element room link | PARTIAL | §1-3/§5 (server `message_room`, policy fields, PWA help) never built; checks 15:117 | decision 15; banner (0a) |
| 16/16b/16c/16e boot Home | BUILT-UNCHECKED | 16:148, 16b:201, 16e:395; `boot_cover` switch moot in android mode | android-mode boot checks in E1; the switch dies with D |
| 16d recents | BUILT-UNCHECKED | emulator 1-4, Jelly Star decision-3 checks; the `LockTaskOverride` debug hook is **in use** (keep) | E1 + J1 |
| 17/17b VoIP over the lock | BUILT, partly checked | precedence (phone call, emergency, alarm), every Jelly Star item; no banner pointing to 57/59 | banner (0a); only the hybrid's checks (J1) |
| 18 sound mode | BUILT-UNCHECKED | 6 checks | E1 |
| 19 remove FCM | BUILT-UNCHECKED | ring latency in Doze / after a server restart / network switch, battery 120 vs 240 s (decides parked design 40); `FirebaseCleanup` one-shot still runs | J1; cleanup by decision 5 D3 |
| 19b doze exemption | PARTIAL by decision | phase 2 (condition now met); §5 checks | decision 18 |
| 20 vibb music | SUPERSEDED in part by 26c (no banner) | phase-1 Jelly Star checks | banner (0a); fold checks into 21's |
| 21 music phase 1 | BUILT-UNCHECKED | Jelly Star list 21:609-615 (Doze, call, BT, battery); "music-v0.1.0 after these checks" never happened | J1, or record them as skipped |
| 21a library import | **DONE** | - | - |
| 21b music server sweep | BUILT-UNCHECKED | the Pi checks 21b:472-480 (the user runs a PVE VM now) | rewrite as VM checks or record as skipped (0a) |
| 22 connectivity + Docker | **PARTIAL** | S5, L1, L3 (strict network-security config), S6+L2, S7 (CSP) not built; §4.1/§4.2 describe `tailscale_app` and `ConnectionPlan` as if they existed | decision 16; fix §4.1/§4.2 (0a); S7 in S2 |
| 24 parent PIN | BUILT-UNCHECKED | §8, a reboot during a lockout, the budget-phone dialog | J1 |
| 24b override relock | **PARTIAL** | **S4 on `origin/relock-24b-s4` (6dcd982a), unmerged since 10-11, migration number clashes**; §7 checks | decision 8 |

### 1.2 Designs 25-39

| Note | Class | Owed / missing | Recommendation |
|---|---|---|---|
| 25 lock glance | BUILT-UNCHECKED, parts superseded by r11 | checklist 30 × `[ ]` never walked; Jelly Star redacted case, API 34/35 | replace the checklist with a pointer to r11/59b's (0a); Jelly Star in J1 |
| 26 music phases brief | SUPERSEDED by 26a/b/c | :13-29 says "built on branch" | historical banner (0a) |
| 26a Sonos | BUILT-UNCHECKED | **§6h never run**; the real-speaker list 26a:775-803 | §6h in E1 (with 53); real speakers with the user (J2) |
| 26b Storytel | BUILT-UNCHECKED, text stale | **§6i A+B never run**; its first checks (the phone's own login) are moot since 58 | banner "phone login superseded by 58" + prune (0a); §6i in E1 |
| 26c handover | SUPERSEDED (its merge is done) | :112 "not verified anywhere" | delete or fold into 26c (0a) |
| 26c Spotify | BUILT-UNCHECKED | part B (real SDK), QA checklist :1834, the 2.5 h Doze run, recheck R1-R4 | **PARKED (Spotify)**; PLAN:131's "DNS capture owed" is stale (dropped 2026-10-25) - fix in 0a |
| 27 episode art | BUILT-UNCHECKED | all of §6, QA H2 lock tile, TalkBack, animations off | E2 (sizes, TalkBack on) |
| 28 music card session | BUILT-UNCHECKED | §6j part A 3+, all of part B; `HeadsetDebugReceiver` exists only for these | §6j B in E1, then drop the receiver |
| 29 news / new entries | BUILT-UNCHECKED | §7, step 4's check text can't catch its case | fix the text (0a), run in E1 |
| 30 streaming data | BUILT-UNCHECKED | §8 checks 1-11 (cache-through, storage limit) never run; PLAN:152 "3b code QA owed" is stale | E1 with `fake-audio.py`; fix PLAN (0a) |
| 31 PWA redesign | DONE in code | "browser pass owed" lines in steps 7-10/15 stale; `time_state.zone` deferral closed by 34 P13 | one closing status line (0a) |
| 32 lock flash | **PARTIAL** | step 3 never built; §6k never run; **experiment code in the tree**: the COVER mode (`KeyguardCoverPlan` COVER/DISABLED, the `KeyguardCover` own-mode branch), `KeyguardCoverDebug`(+Receiver), `LockUpDebug`(+Receiver), their debug manifest receivers, `DebugHookAbsentTest` bans, emulator.md §6k. Careful: `KeyguardCover.kt` also writes the ANDROID mode | drop step 3; delete the debug experiments + §6k in 0b (debug-only, no release effect); the COVER/own branches go with D |
| 33 OSM tiles referrer | DONE | no status line | add "built, merged" (0a) |
| 34 launcher polish | BUILT-UNCHECKED; **header claims less** ("proposal… not built") | blocked rows of the 10-14 round (glance rows with notifications, N6 icon, sheet photo/outgoing call, Wi-Fi dialog, library without the bar, 112 path), step 9's check, R4 English VPN name; unused `palette-ktx` | fix :3 (0a); `palette` out in 0b; rows in E1/E2 |
| 35 parent notifications | BUILT-UNCHECKED; phase 2 parked | §6n never run; push matrix (Chrome, Firefox, 410, wrong clock); Jelly Star BATTERY_LOW threshold, Doze, emergency sync on mobile data | §6n + one Chrome and one Firefox push in E1; J1 |
| 36 custom DNS | BUILT-UNCHECKED (**shipped broken once**) | §6m 1-7 never run (real DoH/DoT by name and IP, home network on/off, 853 blocked, AdGuard REFUSED); :531 "step 3 not built" is stale | §6m in E3 (with L3, same area) against a real AdGuard Home |
| 37 install now | BUILT-UNCHECKED | §6o never run; Jelly Star/iPhone list 37:347-352 | §6o in E1 (quick, in use) |
| 38 phone off/on | BUILT-UNCHECKED, emulator half PASS | Jelly Star: Power off/Restart, a flat battery, the 5-min hold on Wi-Fi and mobile, the shutdown POST over tsnet | J1 |
| 39 list pictures + order | BUILT-UNCHECKED | §6q never run; Jelly Star rows, TalkBack | §6q in E1 (not the Spotify parts) |

### 1.3 Designs 41-64, r5-r13

| Note | Class | Owed / missing | Recommendation |
|---|---|---|---|
| 41 flashlight | BUILT-UNCHECKED | §4 on the Jelly Star (camera policy, camera gesture lit, 10-min off, a call, the camera app after) | J1 |
| 42 lock clock | BUILT-UNCHECKED | §4 FSI, timer Stopp, overnight alarm; "design 55 (to write)" never written | J1; decision 19 |
| 43 PWA app shell | BUILT-UNCHECKED | §5 iPhone checks | iPhone, with S1's browser pass |
| 44 Locate history | BUILT-UNCHECKED | §5 emulator `geo fix` trail, span kept across a relaunch | E1 |
| 45 inbox first | BUILT-UNCHECKED | §4 iPhone badge flow | iPhone |
| 46 context queue / play marks | PARTIAL | §1.1-1.3 + queue parts of §2/§3 never built (only §1.4 the play disc) | **PARKED (Spotify)** |
| 46b reader account | PARKED, no code | the user never decided | **PARKED (Spotify)** |
| 47 Spotify add-first | BUILT-UNCHECKED | §3 iPhone | **PARKED (Spotify)** - checks only if the user is in the PWA anyway |
| 48 activity log | BUILT-UNCHECKED | §3 | E1 (the PWA side in a browser pass) |
| 49 shuffle/repeat | BUILT-UNCHECKED | §3 on own music (Spotify half parked) | E1 |
| 50 player/library picks | BUILT-UNCHECKED, §1.1/§1.5 superseded by r12 | §1.2-1.4 checks | E1 |
| 51 glance text per app | BUILT-UNCHECKED | §3 two ticked apps | E1 (with `launcher-glance-b`'s rows) |
| 52 Bluetooth headphones | BUILT-UNCHECKED | §4 launcher + music halves; Jelly Star: Koble til connects (PLAN:1748 "most likely never worked"), HFP call in the headset | J1 (the user's headset) |
| 53 Sonos grouping/precision | BUILT-UNCHECKED | §5 checks 2-11 incl. **check 9 (DIDL: podcast, NRK, Storytel)**, 53-d list (a)-(f) | §6h-based checks in E1; real speakers J2 |
| 54 Spotify on Sonos | PARTIAL | 54-a2 and 54-b never built; dormant 54-a1 code on main (`ShareLink.kt`, `playShare`, `QueueMap`, `Kind.SPOTIFY_SHARELINK`) | **PARKED (Spotify)**; the dormant code stays as is |
| 56 Android 13/17 | ASSESSMENT, orphan | its recommendation has no PLAN entry | decision 14 |
| 57 VoIP screen over the lock | SUPERSEDED by 59 (held) | prep + QA on `worktree-agent-a689b601f638554b8`, 477 commits behind; no banner on main | decision 13; banner (0a) |
| 58 Storytel JWT courier | **PARTIAL** | **step 3 (retirement) not built**: `/api/devices/music/storytel` (device_routes.rs:111), `storytel_login_sent`, the bridge's `getStorytelLogin` (IMusicBridge.aidl:30), `MusicServer.storytel()` all live; §9 emulator + Jelly Star | decision 9 → round B3 |
| 59 assessment | ASSESSMENT | led to the hybrid | keep |
| 59 design | BUILT-UNCHECKED; **phase D not built** | §9 Jelly Star (BFU call screening, overnight reboot with a 07:00 Fossify alarm, fingerprint across `resetPasswordWithToken`, which call screens are `showWhenLocked`); cites `59-prototype.md`/`59c-…md`, which exist only on the archive branches | J1; decision 13 keeps the hashes reachable |
| 59b lock face | BUILT-UNCHECKED | §4.4 (re-posted ring) and R2 (no lock task of the glance's own with the kiosk off) not built | leave §4.4/R2 to D's inventory |
| 59d mode inventory | BUILT-UNCHECKED, gaps | step G moot, R1-8 not built, `android_pin_only` deliberately not built, Q3 open for D; `JELLY_STAR_CLOCK` (LockGlance.kt:442) copied from the emulator, unverified; RC3-4, RC3-8, six-inch F3/F4 open | RC3-8/F3/F4 in L1; the rest J1 → D |
| 60 hardware-bound token | reserved number, no file | - | after D (park) |
| 61 vibb boxes | ASSESSMENT, orphan | no PLAN entry | decision 14 |
| 62 assessment / 62 parent accounts | ASSESSMENT / BUILT-UNCHECKED | §10 browser pass 1-8 (OTP, reset, remove, per-parent push, iPhone confirms); PLAN:922 "awaiting code QA" stale | browser pass with S1; fix PLAN (0a) |
| 63 multi-family | PARKED by the user | - | keep |
| 64 safe zones | BUILT-UNCHECKED | **there is no step 3** (1a/1b/2a/2b only); emulator §9 5-9 (straddle, no flap, re-register, reboot + Doze, batterystats); the Jelly Star day; PLAN:1093-1096 "not merged" stale | §9 5-9 in E1; the zone series numbers after the user's day |
| r5 server small | BUILT-UNCHECKED | iPhone: a declarative tap lands without `?push`; the bad-picture investigation (PLAN:1651) open | iPhone; the investigation folds into S1's "episode pictures on demand" |
| r6 launcher small | BUILT-UNCHECKED | the tile recording again, the parent-PIN sync in the inbox | E1 |
| r7 launcher small | BUILT-UNCHECKED; §2 abandoned (removed the same day) | §1.3 (any app rings, the PWA row), §3.3 (notice wake, `lockNow` at 15 s) | E1 |
| r8 music small | BUILT-UNCHECKED; §1 superseded by r12 | §4.1-4.9 emulator, §4.10-4.11 Jelly Star | E1 / J1 |
| r9 glance small | BUILT-UNCHECKED; §2 superseded by r11 | §5 emulator list; PLAN:1128/1190 "not merged yet" stale | E1; fix PLAN (0a) |
| r10 keystore wrap | BUILT-UNCHECKED | **checks 3, 6, 8, 10, 11, 12** (3/10 a credential set in a window, 6 BFU, 8 a `*.ts.net` server, 11 a second enrolment on the old build, 12 screen off during the enrol call); F1's check (home-round owed 2); Q-9 (N4/N5); header "awaiting code QA" | 3/10/11 in E1, 6/8/12 in J1; header (0a) |
| r11 glance scroll | BUILT-UNCHECKED | checks 2, 3, 5, TalkBack, VoIP card mid-drag, the Jelly Star swipe feel | E1; feel in J1 |
| r12 favourites tile | BUILT-UNCHECKED | checks 4, 6-8, 10, 12, 13 (own/Storytel switches, Til slutten, a room, a removed entry); §3.5 is Spotify | E1 (the non-Spotify ones); §3.5 PARKED |
| r13 rule card / time left | BUILT-UNCHECKED | compact/scroll with music, the budget used up, cap of three, 30-min expiry, the new kind's web push; header "awaiting merge" | E1 (budget: set a 5-min limit); header (0a) |
| vibb-spotify-options | SUPERSEDED by 26c | no banner; PLAN:390-395 keeps option A's sentence | banner + trim (0a) |

### 1.4 "Approved for the next fix batch" entries with stale status

All merged on main, the entries still say otherwise (fix in 0a, one line each): `pwa-fixes-a` "not merged yet"
(PLAN:631, :754), `pwa-fixes-b` "not merged" (:581, :598), `pwa-fixes-c` "not merged" (:605, :614), `launcher-small-a`
"awaiting merge" (:589, :1354, :1420, :1485), r13 "awaiting merge" (:1054), r9 "not merged yet" (:1128, :1190), 62
"awaiting code QA" (:922), 64 "not merged" (:1093, :1096), the music "Status (2026-10-12)" block (:100-156: phases
1b/2/3, 29, 30 "built on branch", release plans for 0.24-0.26, 30's 3b code QA "owed", the Spotify DNS capture
"owed"), "Status (2026-10-05)" (:212-216 "Not built yet: Vibb integration"), :237 "Server 0.24.0 (not tagged yet)".
And `launcher/CLAUDE.md` "Architecture change in progress (2026-08-07)" (:1397) is upstream history that still says
"is being replaced", "not yet built", "not yet live-tested" and has its own "Phase D/E" (not design 59's) - retitle
as history.

**Note to `launcher-glance-b` (in build):** the queue item says "a phone-book contact's linked room (design 15's
`message_room`)". No `message_room` exists in the code (grep: none); the link is the learned map in
`calls/ElementRoomStore.kt` / `ElementRooms.kt`. The coordinator should tell the implementer, and the PLAN line is
corrected at that round's merge.

### 1.5 Branches

`git branch --no-merged main`: 80 local branches, plus three remote-only ones.

| Branches | Class | Action |
|---|---|---|
| 75 local QA/run/design branches (`qa-31-s*`, `qa-34-s*` except s9, `qa-*`, `59s*-qa`, `f3-*`, `rc2-*`, `rc3-run`, `design-64`, `r11-glance-design`, `r12-music-design`, `proto-59-run`, …; the list is in the audit, reproduced in Round 0a) | MERGED-ELSEWHERE (63 patch-equivalent, 12 whose doc is on main identical or longer) | delete (`git branch -D`; 14 are checked out in old worktrees - remove those first) |
| `qa-34-s9` | SUPERSEDED (a 2-nit QA doc never added; the merge says "QA'd without code findings") | delete |
| `proto-59-hybrid`, `proto-59-qa`, `proto-plain-lock` | SUPERSEDED, cited by hash in design 59 / PLAN:909 | tag `archive/*`, delete the branches (decision 13) |
| `worktree-agent-a689b601f638554b8` (design 57 prep + QA) | SUPERSEDED by the hybrid, held on purpose | tag `archive/d57-prep`, delete (decision 13) |
| `origin/relock-24b-s4` (24b S4, 1 commit, 13 files) | STILL-NEEDED if 04 check 6 passes | decision 8 |
| `origin/review-dead-code` (the 2026-10-13 dead-code review, 456 lines, never merged; its "safe to delete" list was never acted on - `tokio-util` and the unreachable route are still on main) | STILL-NEEDED (the doc) | merge the doc in 0a (re-verified line numbers in 0b/S1) |
| `origin/spotify-spike` (10 commits, 3 673 lines of spike code) | SUPERSEDED by 26c | tag `archive/spotify-spike`, delete the remote branch - or leave it untouched (Spotify parked); *default leave* |
| worktrees: 58 dirs under `.claude/worktrees/` whose branch is merged (14 locked) | - | `git status` in each, then `git worktree remove` (+ `unlock`), `git worktree prune`; their cargo target dirs under `~/.cache/handy-target/` too |

---

## 2. Every remaining queue item

| # | Item | Side | Size | Touches | Prerequisites | Verifies |
|---|---|---|---|---|---|---|
| Q1 | RC3-8: font 1.0 with music + three rows, a hint when a row is out of sight (or candidate 4 first) | launcher | S | `lock/LockGlance*`, `GlancePanel.kt`, `LockGlanceLayoutTest` pins | `launcher-glance-b` merged (same files) | unit + emu |
| Q2 | Six-inch F3: Home's contact labels touch at font 1.3 | launcher | XS | `res/layout/item_kid_contact.xml` | - | emu (E2) |
| Q3 | Six-inch F4: glance rows two lines on tall screens | launcher | S | `badges/GlanceRows.kt`, `GlancePanel.kt`, layout tests | `launcher-glance-b` merged | unit + emu |
| Q4 | Compact music tile a little lower | launcher | XS | the glance's compact tile layout, `LockGlanceLayoutTest` pins | - | unit + emu |
| Q5 | Fingerprint with the screen on: one help line in Kid Settings if the Jelly Star needs it | launcher | XS (conditional) | `ui/kidsettings/`, strings | J1's check | JS |
| Q6 | End of a pause: `killBackgroundProcesses` for the packages re-blocked | launcher | S | `server/AppEnforcer.kt` (`apply`), a pure plan + test | - | unit + emu (`dumpsys meminfo` before/after) |
| Q7 | VoIP small fixes: (d) READ_PHONE_STATE for allowlisted apps; (a) "Tilbake til samtalen" only if android mode needs it; (b)/(c) dropped | launcher | S | `AppEnforcer.kt` permission grants (`setPermissionGrantState`), `lock/VoipCalls.kt`, `VoipCallPlan.kt` | decision 3; (a) needs E1's repro | unit + emu (WhatsApp) |
| Q8 | Jelly Star "App blocker" hidden/suspended at enrolment | launcher + docs | XS-S | `AppEnforcer`/relock blocks, `docs/setup/` runbook | the user's package dump (J1) | unit + JS |
| Q9 | Browser "secure DNS" bypass: Chrome managed config `DnsOverHttpsMode=off`, a DoH-host block category, Firefox's canary NXDOMAIN | launcher | M | `AppEnforcer.kt` (`setApplicationRestrictions`), `server/DnsFilterEngine.kt`, `KidVpnService.kt` | - | unit + emu (Chrome, Firefox) |
| Q10 | Web Filter line "upstream can't be reached" | both | S | launcher `UpstreamTransport.kt`/status report, contract `server/testdata/dns_upstream_status.json` (both copies), server Web Filter template | Q9 in the same branch (same area) | unit + emu |
| Q11 | Camera off per app + a permissions row per catalog app (+ Media management, Modify system settings, usage access as "approve on the phone" with a pause-screen row) | both | M | policy keys (`policy_json_keys_snapshot`, `PolicyResponseCompatTest`), `AppEnforcer.kt` grants, pause-screen rows (`systemPageIntent` pattern), server `device_apps` page + migration | a short design note (the contract) | unit + browser + emu |
| Q12 | Configurable emergency menu | both | M | `calls/EmergencyCall.kt`, `EmergencyDialer`, the keypad/glance/ring card/time-rule screen/112 tile, policy list (both dirs), PWA page | decision 12; mockup + note | unit + emu + JS (a real 112 is never dialled) |
| Q13 | The launcher's security Lows (16; P-L5 done by r10 - re-verify each) | launcher | M | `lock/VoipCalls.kt`, `RefrontPolicy.kt`, `PinBackoff.kt`, `TimeRuleReceiver` (manifest), `MusicPlan.kt`, `KidVpnService.kt`, `UpstreamTransport.kt`, `TsnetClient.kt`, `Hardening.kt`, `NotificationRuleRuntime.kt`, `LocateCommands.kt`, `QuickControls.kt` | after Q9/Q10 (same VPN files) and L2 (VoipCalls) | unit + emu |
| Q14 | Missed Element X calls on the contact card and the phone book's badge | launcher | S + note | `badges/` Element X reader (design 15's), the phone book badge | the user's capture of the notification shape (J1) | unit + JS |
| Q15 | Screen-size matrix (4 AVDs × font 1.0/1.3 × 3-button/gesture) | launcher + music | M (session) | none (screenshots under `docs/testing/screens/sizes/`, a findings table) | L1 and M1 merged (so it judges the new glance and splash) | emu (E2) |
| Q16 | Design 38 device check, the Jelly Star half | - | - | - | - | JS (J1) |
| Q17 | Phase D (own lock removed) | both | L (2-3 rounds) | see "Phase D" in §3 | J1 proves the hybrid; 59d Q3 and the OWN_KEYPAD replacement decided; one release after the last phone moved | unit + emu + JS |
| Q18 | Design 32b | launcher | - | - | decision 4 | emu (E1) |
| Q19 | RC3-4 release-build setup timing | launcher | S-M after measuring | `Application.initRest`, `BootHome` | the release-build measurement on the Jelly Star (J1) | JS |
| Q20 | Immich wallpapers | both | M | share target, wallpaper picker, a server switch | decision 20 | - |
| Q21 | Design 60 (hardware-bound token) | both | L | - | after phase D; a design note | - |
| Q22 | The music app's "no network" text and the real cause in its log | music | XS-S | the player's error mapping (`onError`), strings | - | unit + emu (Wi-Fi off) |
| Q23 | The wordmark right under the rings; the rings breathe (optional) | music | S | `MainActivity.holdSplash`, `ui/MusicRoot.kt` first frame, `logic/Splash.kt`, the theme, `splash_vibb_breathe.xml` copy | - | unit + emu (§6u 1, 6-8 frame counts) |
| Q24 | The music app's security Lows (8 left; S-L3 CSP is Spotify: PARKED) | music | M | `sonos/Soap.kt` (size cap, deadline, DOCTYPE), `playback/PlaybackService.kt` (session commands), cleartext enclosures, `scripts/sonos-ws-probe.py` scrubber, release logs (`SonosHome.log`), speaker report (home network only), `data_extraction_rules.xml` | after M1 | unit + emu (§6h) |
| Q25 | `PHONE_SERVER_URL` in `.env` | server | XS | `config.rs`, Phone Setup template, DEPLOY | - | unit |
| Q26 | Episode pictures on demand: part (1) done (migration 0082); part (2) one bad URL must not hold the listing's pictures (`failures == 0` in the `Why::Art` rule) + the r5 investigation | server | XS-S | `music_sweep.rs` (:637) | - | unit |
| Q27 | GitHub asset digests into `ReleaseAsset.sha256` | server | S | `apk.rs`/release fetch, `stream_to_file` | - | unit |
| Q28 | PWA tab-switch timing (Navigation Timing behind a debug switch; prefetch after) | server | S | `static/` (a small timing script), a debug switch, `reconnect.js` | - | jstest + browser + iPhone |
| Q29 | Settings saves in the security log | server | S | one admin middleware, `security_log`, coalescing | decision 10 | unit |
| Q30 | The server's security Lows (10 security + 8 privacy; re-verify each, some may be done) + 22 S7 (CSP) | server | M + M | `device_api.rs`, `gate.rs`, `admin_auth.rs`, `retention`, `music_sources.rs`, migrations | - | unit + browser |
| Q31 | F-Droid: `entry.jar` signature + repo signer pin | server | M | `fdroid.rs`, a JAR/PKCS#7 verifier | - | unit (fixtures) |
| Q32 | F-Droid: the signer the index lists at the first pin | server | S | `fdroid.rs`, `apk.rs` `ApkIdentity` | with Q31 | unit |
| Q33 | F-Droid: the ABI filter | server | S | `fdroid.rs` pick | "waits for the first such app" | unit |
| Q34 | PWA i18n | server | L | every template and event text | decision 21 (park) | - |
| Q35 | Home Wi-Fi as a trigger | both | M | - | decision 21 (park) | - |
| Q36 | Wi-Fi in Phone Setup | server (A) | XS-S | Phone Setup, the QR builder, backups exclusion | decision 11 | unit + emu (QR enrol) |
| Q37 | Notifications phase 2 (ntfy/Matrix) | server | L | - | decision 21 (park) | - |
| Q38 | Zone series numbers after the user's Jelly Star day | launcher (+server) | XS-S | the zone series caps (design 64 step 2) | the Jelly Star day | JS |
| Q39 | Developer-verification registration | - | - | nothing in the repo | Google opens it for Norway | - |

PARKED with the Spotify decision (no round): the faster Spotify start (already in PLAN "Parked"), 54-a2 (Spotify on a
room), 54-b (reorder), 46 §1.1-1.3 and 46b, 26c part B and its recheck R1-R4, r12 §3.5, music S-L3 (the web player's
CSP), dead-code D6/D7, the `spotify-spike` branch, the dormant 54-a1 code.

From the inventory, added to rounds (not queue lines; added to PLAN's queue by the coordinator when approved):
**C1** Round 0a docs; **C2** the debug experiments and dead code (0b, S1); **C3** 58 step 3 (B3); **C4** 24b S4
(decision 8); **C5** the 11 switches' defaults (decision 7); **C6** drop `HeadsetDebugReceiver` after §6j B.

---

## 3. Rounds

Each round: implementer (Opus, own worktree on `git checkout -b <branch> <exact local main hash>`, own scratch
folder and `CARGO_TARGET_DIR`) → code QA (Opus, own worktree, one full suite + the named mutations) → the same
implementer fixes every High/Medium/real Low/nit (merging main into the branch first) → the coordinator merges, runs
the side's suite once on main, pushes, waits for CI, tags. "Note" = a design note (architect Opus + one design-QA
round) - only for M and cross-side contract work.

### Round 0 - cleanup

The user's sense of drift is the point of this round: it removes what is half-built or claims too much before new
work lands. Three parts: 0a (docs and branches, no build), 0b (dead code in the apps), and the server's dead code,
which rides in S1 (the same suite). Honest size: 0a is S of mechanical agent time; 0b is S (about 1 000-1 300 deleted
lines, two Gradle suites); the server part is XS inside S1. In all about one day of agent time and ~1.5-2 M tokens.

#### Round 0a - docs and branches (no build)
- **Who:** the coordinator with one Sonnet agent; docs-only commits (CI's build jobs skip them); can run now,
  alongside the two rounds in build, because it builds nothing. Touches PLAN.md - **one PLAN writer at a time**:
  do it between the two running rounds' merges, or let the Sonnet agent do the notes and the coordinator the PLAN
  lines.
- **Items:** (1) the stale status lines of §1.4 and §1.1-1.3's "(0a)" rows: banners on 03, 10, 15, 17, 20, 26,
  26b, 26c-handover, 57, vibb-spotify-options; headers of 34, r10, r13; 22 §4.1/§4.2; 25's checklist → a pointer; 29
  step 4's check text; 31/33 closing lines; 36 :531; PLAN:131/:152 and the old status blocks; `launcher/CLAUDE.md`'s
  2026-08-07 section retitled as history; (2) merge `origin/review-dead-code`'s doc onto main as it is (a header line
  "acted on in rounds 0b/S1, 2026-10-2x"); (3) the branches of §1.5: tag the archives, delete the 76 merged/superseded
  local branches and the remote `qa-*`/`proto-*` copies the user agrees to, prune the 58 worktrees and their target
  dirs. The 75 merged-elsewhere local branches (re-check each with `git cherry main <b>` right before deleting):
  `58s2-qa 59b-qa 59s1-qa 59s2-qa 59s3-qa 59s4-qa 62s12-qa 62s345-qa d59-rc-run design-64 f3-qa f3-run f3r2-qa
  proto-59-run pwa-browser-pass pwa-d-qa qa-26c-merge3 qa-31-s10 qa-31-s14 qa-31-s15 qa-31-s6 qa-31-s7 qa-31-s8 qa-31-s9
  qa-34-design qa-34-s1 qa-34-s10 qa-34-s11 qa-34-s12 qa-34-s13 qa-34-s2 qa-34-s3 qa-34-s4 qa-34-s5 qa-34-s6 qa-34-s7
  qa-34-s8 qa-art-asap qa-browser-fixes qa-design-35 qa-design-36 qa-dns-s1 qa-dns-s2 qa-dns-s3 qa-emu-launcher
  qa-emu-music qa-install-now qa-last-position qa-launcher-small-a qa-music-list-39 qa-music-r13 qa-notif-s1 qa-notif-s2
  qa-notif-s3 qa-phone-power qa-pwa-fixes-a qa-pwa-fixes-b qa-pwa-fixes-c qa-r1-apps qa-r1-server qa-r13-rule-card
  qa-r5-a qa-r5-b qa-r6-a qa-r6-b qa-seed qa-spotify-6l-fixes qa-vpn-fix r11-glance-design r12-music-design r8-qa r9-qa
  rc2-recheck rc2-run rc3-run`, plus `qa-34-s9`; (4) PLAN "Parked": one line each for designs 56 and 61, 09 part A, 22 S5/L1/S6 (after decisions 14, 16, 17);
  (5) optional, recommended: split `launcher/CLAUDE.md` (1 619 lines) into `docs/notes/launcher/*.md` with a short core
  and an index, as was done for the server on 2026-10-19 - every launcher brief then names one note instead of
  re-reading the whole file.
- **Size:** S of agent time (mostly mechanical), XS of coordinator time. **Checks left:** none. **Decisions first:**
  13, 14, 16, 17 (defaults are fine).

#### Round 0b - dead code in the apps (`cleanup-apps`)
- **Items:** design 32's debug experiments (`LockUpDebug`+Receiver, `KeyguardCoverDebug`+Receiver - the debug-only
  COVER selection; the release `KeyguardCoverDebug = false` stub; their manifest receivers; `DebugHookAbsentTest`'s
  entries; emulator.md §6k) - **not** `KeyguardCover.kt`'s android writer; `palette-ktx` (build files + its
  verification-metadata entries); the dead-code review's launcher list (`openInBrowser`, `ui/Helper.kt`, the 4 anim
  files, `dialog_choose_color.xml`, the unused strings in both locales, `kid_icon_fallback`, `app_icon_side`,
  `AlertDialogDanger`), D3's `FirebaseCleanup`/`FirebaseLeftovers` + test, D5's minimalist leftovers (MIT dir, deletion
  only), `SHOW_BACK_BUTTON` (decision 6); music: `SpeakerCache.scanNow` (+ KDoc). Each re-verified with a grep before
  deletion (the review is from 10-13).
- **Side/size:** launcher + music, S (deletions; about 1 000-1 300 lines). No design note.
- **QA:** both apps' full suites + `assembleRelease` (the debug-hook absence test); mutations: ~4 (put a deleted
  receiver back → `DebugHookAbsentTest` fails; the android-mode keyguard write still happens - `KeyguardCoverPlanTest`).
- **Tag:** none by itself; ships in rc.5. **Checks left:** E1's boot/lock smoke (the android-mode cover still arms).
- **Decisions first:** 5 (D3, D5), 6.

### Round S1 - server small items + server dead code (`server-small-e`)
- **Items:** Q25 `PHONE_SERVER_URL`, Q26 episode pictures part (2) (+ the r5 investigation), Q27 GitHub digests,
  Q28 tab-switch timing (step 1 only: the measurement), Q29 settings saves in the security log; the review's server
  list (9 unread fields + `DeviceBlocklistOverride`, `tokio-util`, the narrowed `allow(dead_code)`, the 3 clippy
  warnings, D4's route, the 28 `TestApp::policy`/`post_status` copies → one helper - test-only); D3's server half
  (`remove_journal_media`, the FCM warning). D1 is a policy contract change (both dirs in one commit), so it goes into
  **B3**, not here.
- **Side/size:** server, S×5 + XS deletions → about M in total; split off Q29 into S2 if the implementer runs long.
- **QA:** server suite + jstest; mutations ~8 (the digest mismatch refuses; an asset without a digest keeps today's
  path; one bad picture no longer holds the others; the security-log coalescing and the refusal not logged; the env
  default loses to the DB value).
- **Tag:** `server-v0.54.0` (0.53.0 is `pwa-fixes-d`'s). **Checks left:** a browser pass (the timing line behind the
  switch; WebKit), the iPhone tab-switch numbers (the user, after the tag), 62 §10's browser pass in the same run.
- **Decisions first:** 5 (D4), 10.

### Round L1 - glance and Home layout (`launcher-glance-c`)
- **Items:** Q1 RC3-8, Q2 F3, Q3 F4, Q4 the compact tile lower; Q5 the help line (only if J1 already said so; else a
  later XS).
- **Side/size:** launcher, S (four XS-S in the same files). Starts after `launcher-glance-b` merges (GlanceRows,
  GlancePanel, the layout pins).
- **QA:** launcher suite; mutations ~6 (the hint shows exactly when a row is out of sight; two lines only above the
  height threshold; the tile's pins).
- **Tag:** with M1, after E1: `launcher-v0.37.0-rc.5`. **Checks left:** E1 (font 1.0/1.3 with music + 3 rows), E2.

### Round M1 - music splash and words (`music-splash-b`)
- **Items:** Q23 (the wordmark under the rings in the held first frame; the breathing rings, dropped at the first
  stutter or jump), Q22 (the real cause logged, "no network" when it is the cause).
- **Side/size:** music, S. **QA:** music suite; mutations ~5 (the first frame's ring geometry equals the splash's;
  the hold ends at content-ready or 3 s; an `UnknownHostException` maps to the no-network words, others don't).
- **Tag:** `music-v0.5.0-rc.7` after E1. **Checks left:** §6u 1 and 6-8 (frame counts, empty library, font 1.3,
  3-button), the breathing at the hand-over (frame by frame).

### Emulator session E1 (the coordinator's; no Gradle meanwhile, at most one server build)
After `launcher-glance-b`, 0b, L1, M1 are merged and their suites green on main. One wiped `jelly36`, the debug
apps of main, the test server. Order (cheapest first, the lock/boot ones last):
1. `launcher-glance-b`'s own checks (contact photos incl. WhatsApp, the sender, the count, the provisioning storm's
   count), 51 §3, r11 2/3/5 + TalkBack + VoIP card mid-drag, L1's rows at font 1.0/1.3.
2. Home-round owed 1a-h with a **photo** wallpaper and 3-button navigation, 2 (F1), 3 (QR enrol sync), 4 (the trail),
   5 (installed versions), r6's tile recording, r13's leftovers (a 5-min budget used up, cap of three, 30-min expiry).
3. Music: §6u 1-4 and 6-10 (not 5, 11: Spotify parked), §6f/§6q, 29 §7, 30 §8 with `fake-audio.py`, §6h + 53's
   check 9, §6i A+B, §6j B (then C6), 49/50 §3, r8 §4.1-4.9, r12 4/6-8/10/12/13.
4. Launcher: §6d A1-A5/B1-B5 (decision 7), §6n, §6o, 12 checks 1-6, 13, 14, 18, 44 §5, 48, r7 §1.3/§3.3, 64 §9 5-9.
5. Lock/boot: r10 3/10/11, the android-mode screen-on frame (decision 4), VoIP "No VoIP call screen to bring back" in
   android mode (decision 3 (a)), android-mode boot checks (16, 16d 1-4), 0b's smoke.
- **Size:** a long day (split into E1a = 1-3 and E1b = 4-5 if needed; E1a is enough to tag M1's rc.7, E1b to tag
  rc.5). Run doc `docs/testing/2026-10-2x-e1-run.md`; findings become a fix round only if one is High/Medium.

### Emulator session E2 - the screen-size matrix (Q15)
Right after E1 (the AVDs exist except the narrow 4" and the 6.7"; create them in advance with `-wipe-data`). One
pass of every screen, 4 AVDs × font 1.0/1.3 × 3-button/gesture, the narrow one first (below the 320 dp floor);
27 §6 with TalkBack on, 34's blocked rows. Screenshots `docs/testing/screens/sizes/`, a findings table. **Size:** M of
coordinator time; a fix round (L1b, launcher+music, S) only for what it finds.

### Round L2 - pause end and enforcement (`launcher-enforce`)
- **Items:** Q6 the pause-end kill, Q7 per decision 3 ((d) always; (a) only with E1's repro), Q8 the App blocker (if
  J1's dump is in; else it waits for the next launcher round).
- **Side/size:** launcher, S. **QA:** launcher suite; mutations ~8 (never kill an app allowed outside the pause, never
  ours or the music app; READ_PHONE_STATE only to allowlisted requesters and only while the denial is set; back to
  default when calls go unmanaged; the App blocker matched by package + signer).
- **Tag:** `launcher-v0.37.0-rc.6` with L3, after E3. **Checks left:** E3 (`dumpsys meminfo` before/after a pause;
  WhatsApp's READ_PHONE_STATE), J2.

### Round S2 - server security Lows, security half (`server-sec-lows-a`)
- **Items:** server review L1-L10 (L1 flood limits, L2 LAN feeds, L3 dns-events fields, L4 per-device storage budget,
  L5 + 22 S7 the CSP without `unsafe-inline`, L6 absolute session lifetime, L7 counted password checks, L8 `http://`
  QR URL, L9 the pruning pass, L10 typed `call_state`/`time_state`), each re-verified first (some may be done); plus
  decision 7's defaults if J1/E1 passed.
- **Side/size:** server, M. **QA:** server suite + jstest + a browser pass (the CSP change breaks inline handlers -
  WebKit too); mutations ~10.
- **Tag:** `server-v0.55.0`. **Checks left:** browser pass, iPhone login once (session lifetime).

### Round L3 - DNS: the secure-DNS bypass and the upstream line (`dns-bypass`, both sides, one branch)
- **Items:** Q9 (M), Q10 (S, the contract file in both dirs in one commit).
- **Note:** a short one (which Chromium browsers read Chrome's keys; the DoH host list and how it never catches our
  own upstreams; the status field's shape).
- **QA:** launcher + server suites; mutations ~8 (our own upstream never blocked; the canary NXDOMAIN; Chrome's
  restriction only for an allowlisted Chrome; the "unreachable" state only after every attempt failed; the contract).
- **Tag:** rc.6 + `server-v0.56.0`. **Checks left:** E3 (Chrome and Firefox with Secure DNS, §6m 1-7 against a real
  AdGuard Home, the Web Filter line with the upstream blocked).

### Round S3 - server security Lows, privacy half (`server-sec-lows-b`)
- **Items:** server P1-P8 (location kept by policy, the unread 30-day history, the Spotify token scope - **PARKED,
  Spotify** -, the VAPID `sub`, the User-Agent, three unpruned tables, the security log's 180 days, the OSM referer);
  the launcher's P-L3 server half (skip an equal `captured_at`) goes with L4's launcher half in one commit - so P-L3
  moves to L4's branch.
- **Side/size:** server, M. **Tag:** `server-v0.56.0` or `.57.0`. **Checks left:** none on devices.

### Emulator session E3
After L2 + L3: the pause-end `dumpsys`, WhatsApp READ_PHONE_STATE, Chrome/Firefox secure DNS, §6m 1-7, the upstream
line. Short (half a day). Then `launcher-v0.37.0-rc.6`.

### Round B1 - per-app permissions (`app-permissions`, both sides)
- **Items:** Q11. **Note:** yes (the policy keys, the silent-grant vs "approve on the phone" split per Android 12+, the
  pause-screen rows by `systemPageIntent`, never adb - the no-adb rule).
- **Size:** M. **QA:** both suites + a browser pass; mutations ~10. **Tag:** a launcher RC + a server minor.
  **Checks left:** emulator (Immich's MANAGE_MEDIA page opens for that app; camera denied for one app only), J2.

### Round B2 - emergency menu (`emergency-menu`, both sides)
- **Items:** Q12. **Mockup** (artifact) → the user's yes → **note** → build.
- **Size:** M. **QA:** both suites; mutations ~10 (only real emergency numbers pass the call rules and the locks; any
  other number follows the call rules; the default first and largest; the sheet is the confirm). **Checks left:**
  emulator (the sheet from every entry point; the emulator's own emergency number), J2 (no real call).
- **Decisions first:** 12.

### Round B3 - cross-side retirements (`retire-legacy`, server + launcher + music)
- **Items:** C3 = 58 step 3 (the old Storytel route, `storytel_login_sent`, `getStorytelLogin` - an AIDL method
  **removed**, so the two `bridge/` copies and `bridge_contract.json` change together, `MusicServer.storytel()`), D1
  (the pre-step-6 time-policy legacy, a policy contract change: `policy_json_keys_snapshot` + `PolicyResponseCompatTest`
  in the same commit), `StorytelLegacy.kt` once no phone holds the old login.
- **Size:** S-M (deletions, three suites). **QA:** all three suites; mutations ~6. **Tag:** server minor + launcher
  RC + music RC. **Checks left:** §6i (a Storytel book plays through the courier only). **Decisions first:** 5 (D1), 9.

### Round L4 - the launcher's security Lows (`launcher-sec-lows`)
- **Items:** S-L1..S-L11 and P-L1..P-L4 (P-L5 done by r10), each re-verified; P-L3 with the server's half (one
  commit, both dirs). S-L5 (Bluetooth sharing) touches design 52's code; S-L8 (the music app by signature) touches the
  bridge.
- **Size:** M (split in two if the re-verification leaves more than ~10). **QA:** launcher suite (+ server for P-L3);
  mutations ~10. **Tag:** a launcher RC. **Checks left:** E4 (VPN flood, Bluetooth sharing blocked, the music app's
  signature check with the debug/release pair).

### Round M2 - the music app's security Lows (`music-sec-lows`)
- **Items:** S-L1, S-L2, S-L4, S-L5, P-L1, P-L2, P-L3 (S-L3: PARKED, Spotify; P-L4 done).
- **Size:** M. **QA:** music suite; mutations ~8 (a 1 MiB cap; a DOCTYPE refused; a foreign controller can't set
  items; only home-network speakers reported). **Tag:** a music RC. **Checks left:** §6h again (a fake speaker that
  trickles bytes).

### Round S4 - F-Droid trust (`fdroid-trust`)
- **Items:** Q31 + Q32 (Q33 stays waiting for its first app).
- **Size:** M. **Note:** a short one on the verifier (JAR v1 signature over `entry.json`, PKCS#7/CMS, which crates,
  the pinned fingerprints of f-droid.org and IzzyOnDroid). **QA:** server suite; mutations ~8. **Tag:** a server
  minor. **Checks left:** a live fetch from f-droid.org on the VM (as for 0.48.0).

### After J1: the blocked rounds
- **L5** missed Element X calls (Q14; the capture → a short note → S). **L6** RC3-4 (Q19, only if the release build
  is still seconds slow). **C4** 24b S4 (decision 8; server + launcher, XS-S after the rebase). **Q38** zone series
  numbers. **Q5** the help line. These five fit two small rounds: **L5+Q5+Q38** (`launcher-after-j1`) and
  **C4+L6** (`relock-s4`).
- **Phase D** (below) and then **design 60**.

### Phase D - what waits, and what its inventory must cover

Blocked on: J1's proof of the hybrid (59 §9: BFU call screening, an overnight reboot with a 07:00 Fossify alarm
before the PIN, fingerprint across `resetPasswordWithToken`, which call screens are `showWhenLocked`), 59d §8.4 item 8
on Android 16 and the Jelly Star, 59d Q3 and **what replaces OWN_KEYPAD when Android has no credential** (59d §4), and
"one release after the last phone moved". Also waiting for D: 32b (decision 4), 59b §4.4 and R2, design 60, the
`boot_cover` switch, "own mode: a cold SOS start can still be covered", the VoIP fixes (b)/(c) (decision 3).

Phase D deletes (about 5 100 lines by 59d §4; paths under `launcher/app/src/main/java/com/kidslauncher/mdm/`):
- **The switch:** `lock/PinLockStore.kt` (`KEYGUARD_ANDROID`, `keyguardAndroid()`, `keyguardRequested()`), policy
  `kid_lock.keyguard` (`server/dto/PolicyResponse.kt:143`), the report's `keyguard` (`StatusReportRequest.kt:305`),
  server `templates/device_lock.html:41-111` (the own/android select, the own-mode texts), `handlers/devices.rs`,
  `device_pages.rs`, `server/WindowRelock.kt:625`.
- **Keyguard cover:** `lock/KeyguardCover.kt`'s own-mode COVER and dismissal; `KeyguardCoverPlan.kt`'s COVER/DISABLED
  (keep ANDROID and its write); the debug/release `KeyguardCoverDebug` (if 0b has not removed it already).
- **Dark lock-up:** `lock/LockUpPlan.kt` (`lockUpStarts`, HOME_THEN_LOCK, `faceOnTop` :128-133), `PinLockRuntime`
  `darkLockUp`/`lockUp()` (:765-800), `LockUpDebug`.
- **Boot mark and night ground (16c/16e):** `lock/BootMarkPlan.kt`, `PinLockRuntime` boot-mark state (:115-160,
  :397-525, :682-701), `ui/home/NightGround.kt`, `activity_home_night.xml`, `homeShowsContent`.
- **Boot cover, own branch (16b):** `lock/BootCover.kt:79-90` (`switchOn` vs `androidGate`),
  `BootCoverPlan.bootCoverWanted`, `BootCoverActivity.kt:42,63,146,406`, `BootCoverGuard.kt:154`, the policy
  `boot_cover` switch (`PolicyResponse.kt:102`, `AppEnforcer.kt:503`, server `kiosk_escapes.rs`).
- **Camera lock:** `lock/CameraLock.kt`, `CameraLockPlan.kt`, `PinLockRuntime` `CameraLock.onLockChanged` (:651,
  :715); `AndroidLock.cameraGuarded` stays.
- **Our keypad:** `lock/PinBackoff.kt`, `PinKeypadLayout.kt`, `PinKeyHighlight.kt`, `PinLockActivity`'s keypad stage
  and parent-code dialog (`showParentCodeDialog`, :554), `PinLockStore`'s backoff, `LockGlance.ownKeypad()`,
  `FaceStep.OWN_KEYPAD` (`AndroidLockPlan.kt:82,93`; `PinLockActivity.kt:281-303,431`).
- **Own-mode leave rules:** `LockTaskRoot.leaveInFront` (:109) and `lockLeave` (:94-97), `LockGlance.glanceLeaveHides`
  (:50), `glancePrompt` UNUSABLE (:583), `StatusBarHealPlan.kt:99`, `GlancePanel.kt:577,1106`.
- **VoIP over our own lock (17/17b):** `lock/VoipCallPlan.kt:151-293` (the 17b FSI send, try/pause detection,
  `voipTryAfterPause`, the wake-counts-as-ours window), `voipPlatformRing` (:281), `voipReopenDue` (:340) own branches;
  `PinLockActivity.kt:106-141,526-660` (the ring card over the keypad, `voipFsiSend`, `voipAnswerCheck`,
  `voipHeldLockTask`); `PinLockRuntime` `sendVoipRingScreen`, `voipFsiDueNow`, `voipRingUiNow`, `voipAnswerRefused`
  (~:1004-1076), the `whenUnlocked` "VoIP call screen" block (:740-746); `lock/VoipWakeActivity.kt` (check whether
  59b's WAKE still needs it); `VoipRinger.kt` and the ring card stay.
- **SOS guess and re-front:** `PinLockRuntime` `sosGestureLikely`, `narrowForSos`, `SOS_FLOW_MS` (:182, :1674,
  :1769-1790), `LockTaskChrome.kt:109`, `lock/RefrontPolicy.kt`'s own rows (`androidLoss == null`, :64-69, :150).
- **State and plumbing:** `lock/PinLockState.kt:61-69,191`, `PinLockRuntime.kt:230,334`, `HomeFront.kt:56`, the mode
  reads in `Application.kt`, `MdmSyncWorker.kt`, `MdmDeviceAdminReceiver.kt`, `server/FingerprintEnrol.kt:12`,
  `music/MusicPlan.kt`, `appdisplay/AppDisplay.kt`.
- **Wallpaper:** `ui/wallpaper/Wallpapers.kt:99-116`, `WallpaperApplyPlan.wallpaperKey(androidFace)`,
  `WallpaperApplier.kt:72,175`.
- **Tests:** KeyguardCoverPlanTest, LockUpPlanTest, LockUpTest, BootMarkPlanTest, CameraLockTest, PinBackoffTest, parts
  of HomeGateTest, PinLockLayoutTest, SosYieldTest, VoipCallPlanTest. Not own-lock: `ui/LockActivity.kt` (the time-rule
  screen) stays.
- **Docs:** design 10 (the own lock), 17/17b, 32, 16b/c/e, emulator.md's own-mode sections, `launcher/CLAUDE.md`'s
  lock sections, the PWA's own-mode texts.

Shape: **D0** an inventory note (59d's §4 updated against this list, the OWN_KEYPAD replacement, the migration's last
release), **D1** server + policy (the switch, the templates, `boot_cover`, both dirs), **D2** the launcher's lock code,
**D3** docs. Each L-ish; one at a time; the 0.38.0 line.

---

## 4. The run order

Two building agents at most; an emulator session with at most one (server) build beside it.

| Wave | Slot A | Slot B | Coordinator | Releases |
|---|---|---|---|---|
| 1 (now) | `launcher-glance-b` (running) | `pwa-fixes-d` (running) | Round 0a (docs, branches); ask §0's questions | `server-v0.53.0` (pwa-fixes-d) |
| 2 | 0b `cleanup-apps` | S1 `server-small-e` | merge glance-b, 0b; browser pass of S1 | `server-v0.54.0` |
| 3 | L1 `launcher-glance-c` | M1 `music-splash-b` | merge both; then **E1** (S2 may build beside it) | `launcher-v0.37.0-rc.5`, `music-v0.5.0-rc.7` |
| 3½ | - | S2 `server-sec-lows-a` | **E2** (sizes); **the user's J1** on rc.5 | `server-v0.55.0` |
| 4 | L2 `launcher-enforce` | L3 `dns-bypass` (both sides) | then **E3** | `launcher-v0.37.0-rc.6`, `server-v0.56.0`; **stable `launcher-v0.37.0`** if J1 + E1-E3 passed (decision 1) |
| 5 | B1 `app-permissions` | S3 `server-sec-lows-b` | E-short for B1 | `launcher-v0.38.0-rc.1`, server minor |
| 6 | B3 `retire-legacy` | S4 `fdroid-trust` | E-short (§6i) | launcher rc, `music-v0.5.0-rc.8`, server minor |
| 7 | L4 `launcher-sec-lows` | M2 `music-sec-lows` | E4 | launcher rc, music rc (or stable `music-v0.5.0`) |
| 8 | B2 `emergency-menu` (after the mockup) | the after-J1 rounds (L5+Q5+Q38, C4+L6) | E5; **the user's J2** | launcher rc, server minor |
| 9+ | Phase D (D0 → D1 → D2 → D3), then design 60 | - | the user's Jelly Star on each D release | the 0.38.x/0.39.0 line |

Where the Jelly Star sits: **J1 after rc.5** (wave 3½) - the hybrid proof (59 §9), fingerprint with the screen on
(Q5), design 38's half (Q16), 04 check 6 (C4), 11 A3 (decision 7), 19's Doze ring latency, 02's BFU calls and 01's
112/USSD, the App blocker package dump (Q8), Element X's missed-call capture (Q14), RC3-4's release timing (Q19), r10
6/12, 52's headset, 41/42, 21's music list, 24 §8, 35's BATTERY_LOW, 7's Play auto-update, the zones day (Q38).
That is a lot for one session; the order above puts the items that unblock code first (59 §9, 04 check 6, the dump,
the capture, RC3-4). **J2 after rc.6/B1** - the new rounds' device halves and the real Sonos speakers (26a, 53).

**Blocked on J1:** Phase D (all of "Phase D" in §3), design 60, 32b (also decision 4), C4 (24b S4), Q5, Q8, Q14, Q19, Q38,
decision 7's defaults. The rest of the queue is not blocked.

If J1 is late: waves 4-7 do not need it (except Q8 in L2, which then waits), so the build carries on; only the
stable 0.37.0 and phase D wait.

---

## 5. Token and time hygiene

Rules, from what the last week's runs cost:
1. **One full Gradle suite per app per merge on main**, not per agent step: the implementer runs targeted tests while
   working and one full run at the end; the QA one full run; the coordinator one on main after the merge. Launcher and
   music suites (15-25 min each) never run at the same time as an emulator; at most two suites at once (`flock
   ~/build.lock`), always `--no-daemon --max-workers=2 -PwarningsAsErrors=true`.
2. **Mutations scale with risk, not size:** ~4 for a deletion round, ~6 for an S round, ~8-10 for an M or a
   security round, named in the QA brief on the guards that matter. No re-derivation of the design by the QA.
3. **Design notes only for M items and cross-side contracts** (L3, B1, B2, S4's verifier, D0); one design-QA round
   + one revision check, leftovers into the implementer's brief. S rounds go from the PLAN entry straight to the
   implementer.
4. **Briefs name line ranges and notes, never whole files**: server work names `docs/notes/server/<area>.md`; launcher
   work names the `launcher/CLAUDE.md` section until the split (0a item 5) lands. Each brief: the exact main hash, own
   worktree, own scratch subfolder, own `CARGO_TARGET_DIR`, "merge main before the fix round".
5. **Emulator sessions batch rounds:** one after every two launcher/music rounds (E1 after 0b+L1+M1, E3 after L2+L3);
   tag the RC from the session, so an RC number is spent per session, not per round (0.37.0 has four left). `touch
   ~/emu-up` while one runs so agents hold their Gradle runs.
6. **Disk:** delete a branch's `~/.cache/handy-target/<branch>` at its merge; delete
   `~/.cache/handy-target/main/debug/incremental` below ~15 GB free; `df -h /` before a suite (≥ 10 GB before an
   emulator); prune finished worktrees (0a item 3 frees most of the 58).
7. **CI gating:** chains push only on `failed 0` and `GRADLE_EXIT=0`; capture the SHA before the CI wait and tag that
   SHA; docs-only commits (0a) skip the build jobs by their path filters - group docs edits into one commit per wave.
8. **PLAN.md has one writer at a time;** the queue lines leave in the merge commit.
9. **Ask the user once per wave**, from §0, before the round starts; a default stands when there is no answer.
10. **Deletion rounds are cheap per line:** QA reads the diff's deleted side only for references; no new tests, only
    the guards that prove the remaining path (e.g. the android-mode keyguard write).

Rough cost per round type (agent time and tokens, from the r10-r13 rounds; the coordinator's merge/suite/CI wait is
~45-60 min wall on top):

| Round type | Implementer | Code QA | Fix | Wall clock | Tokens (all agents) |
|---|---|---|---|---|---|
| XS/S deletions (0b) | 1-1.5 h | 0.5-1 h | 0.5 h | ~3-4 h | ~0.8-1.2 M |
| S feature round (S1, L1, M1, L2) | 1.5-2.5 h | 1 h | 0.5-1 h | ~4-5 h | ~1.5-2.5 M |
| M round (L3, B1, B2, S2-S4, L4, M2) | + note 1 h; 3-4 h | 1.5 h | 1 h | ~7-9 h | ~3-5 M |
| Emulator session (E1, E2) | - | - | - | 4-8 h each | ~0.5-1.5 M (coordinator) |

Per wave: wave 2 ~6 h / ~3-4 M; wave 3 ~5 h + E1 a day / ~4-6 M; wave 3½ E2 + S2 ~8 h / ~4-5 M; wave 4 ~9 h + E3 /
~6-8 M; waves 5-8 ~9 h each / ~6-9 M each; phase D ~3-4 days / ~15-20 M. Rounds 0a and the PLAN edits ~0.3 M.
