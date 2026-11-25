# handy (repo `palchrb/vibb-launcher`)

A kid's phone setup: an Android launcher that is also the Device Owner agent, and the self-hosted
admin server it talks to. Old commit hashes in the docs still resolve.

## Layout

- `launcher/` - Android app, called **Vibb One** for users (debug "Vibb One (debug)", design 34 P11) - Kotlin,
  Gradle; applicationId `me.vibb.launcher`, debug `me.vibb.launcher.debug`, since 2026-10-06 - the Kotlin
  namespace/class names stay `com.kidslauncher.mdm` and the release asset stays `kids-launcher-mdm.apk`. Details: `launcher/CLAUDE.md`.
- `server/` - admin server + device API (Rust/Axum/SQLite).
  Details: `server/CLAUDE.md`, deployment `server/DEPLOY.md` (Pi + systemd, and the Docker image
  from `server/Dockerfile`).
- `docs/notes/server/` - the server's area notes, moved out of `server/CLAUDE.md` (which keeps the core and an index)
- `docs/notes/launcher/` - the launcher's area notes, moved out of `launcher/CLAUDE.md` on 2026-11-01 (which keeps the core
  and an index "Where the rest is"; read only the notes your task touches)
- `docs/notes/music/` - the music app's area notes, moved out of `music/CLAUDE.md` on 2026-11-01 (the same pattern)
- `docs/plan/plan-archive.md` - PLAN.md's history, moved out on 2026-11-01: the fork decision, repos, early status,
  phases, the old fix batches and the older "Done" entries; PLAN.md keeps the queue, what was asked for and not settled,
  the Done entries that ship in the next release, and the open questions
- `music/` - the Vibb music app (Kotlin, Compose, Media3; applicationId `me.vibb.music`, debug
  `me.vibb.music.debug`), designs 20/21/21b, Sonos 26a, Storytel 26b, Spotify 26c. Details: `music/CLAUDE.md`.
- `docs/design/`, `docs/review/` - design notes and reviews, one per step. Historical: `S` =
  `kid-phone-server` = `server/`, `L` = `kids-launcher-mdm` = `launcher/`, "branch `handy`" = what is
  now `main` here. `docs/testing/emulator.md` - the emulator test loop. `docs/setup/` - setup
  runbooks (`google-account.md`: the phone's Google account for Play, backup to Google off;
  `android-settings.md`: Android's settings an app can't set back - Touch feedback on, the "Modify system settings" grant).
- `scripts/` - `dev-rebuild.sh` (pull, build + install the debug launcher, run the server),
  `release-version.sh` (a `launcher-v`/`music-v` tag -> versionName, versionCode, prerelease: both apps' release
  workflows use it; `--selftest`, run by both workflows' always-run changes job and before every tag build),
  `push-all.sh` (push the current branch), `smoke-test.sh` (adb/emulator-console smoke test of lock and
  calls, remote-capable; `docs/testing/emulator.md` §5b), `fake-sonos.py` (fake Sonos speakers for the
  music app on the emulator, answering from the music app's Sonos test fixtures; §6h), `fake-storytel.py` (a fake
  Storytel - the login, the asset call's 302, a CDN of silent books - answering from the music app's Storytel test
  fixtures; §6i), `fake-spotify.py` (a fake Spotify - the accounts service with PKCE, the Web API the server and the
  phone use, a stub Web Playback SDK playing a tone - answering from `server/testdata/spotify/` and the music app's
  Spotify test fixtures; design 26c §9, `docs/testing/emulator.md` §6l), `fake-mmsc.py` (a fake MMS server for the emulator, design 77 step 3b: an
  m-notification-ind as hex for the debug launcher's `MmsDebugReceiver`, m-retrieve-confs with a small JPEG, an m-send-conf
  for every send; standard library only, `--selftest`; `docs/testing/emulator.md` §6v "step 3b"), `fake-audio.py` (a fake podcast host of
  silent episodes with Range; `--vary` serves another length per request, design 30's changed-source check; §6f;
  `--feed N` an RSS feed of N items with their own pictures the server judges `own`, `--hls K` item K as HLS, design
  39's list pictures and order flip; §6q), `pwa-startup-images.py` (the PWA's iPhone start-up PNGs and head.html's
  links from `server/testdata/pwa_startup_screens.json`; `--selftest`; `docs/notes/server/pwa.md`, "Start-up splash"),
  `yr-symbols.py` (the launcher's weather symbols, design 75 §4.1: yr's darkmode SVGs at a pinned commit, checked by
  one SHA-256 over a manifest, converted to `res/drawable/weather_*.xml` with `WeatherSymbols.kt` and the names;
  `--selftest`, no network, also converts the vendored `scripts/yr-symbols-11.svg` to exactly the committed
  `weather_11.xml`; Python's standard library, run with `-I`; run by `launcher.yml`'s always-run changes job),
  `fake-portal.py` (a fake captive portal for design 102's Wi-Fi sign-in window: one server answering the portal's
  name and another site's, with modes - a 302 portal, meta refresh, script, a soft portal, a hotspot that 302s to a
  search page, a Google walled garden, a full-page iframe, a same-site redirector, a form whose POST 302s to another
  site, a reached site's POSTs that 302 and 307 to a third site; standard library, `--selftest`, run by `launcher.yml`'s always-run changes job; `docs/testing/emulator.md`
  §6ap),
  `music-splash.py` (the music app's launch splash drawables: the Vibb mark read from the launcher's Home splash, "vibb
  music" from Nunito's outlines; `--selftest`), `music-icon.py` (the music app's icon and notification small icon, the
  user's drawing `vibb-music-e2`: the Vibb mark's outer three rings read from the launcher's `ic_launcher_foreground.xml`,
  each with its own scale and stroke, and a round-cornered play triangle, its geometry from the drawing's SVG; `--selftest` checks the outer ring inside the
  safe zone), `up-vapid-vectors.py` (design 108b's VAPID vectors `server/testdata/unified_push_vapid.json` and the two public keys of the
  contract's `body_vapid` rows, from a throwaway key derived from a fake phrase; pure Python, public data only),
  `webauthn-rs256-vector.py` (the server's RS256 passkey vector
  `server/testdata/webauthn/rs256.json`, made once with a throwaway key - public data only; design 74), `emoji-list.py`
  (the launcher's `assets/sms/emoji-16.0.txt` from Unicode's `emoji-test.txt` 16.0, its SHA-256 pinned; design 81),
  `fake-homeserver.py` (a fake Matrix homeserver and Web Push sender for design 108's emulator gate: the gateway's
  discovery, Synapse-shaped `event_id_only` notifies, Web-Push-like bytes to `/up/` with TTL/Urgency/Topic, `--burst`;
  every request to the phone listener with the endpoint's host as `Host`; standard library, `--selftest` against a
  loopback recorder, run by `server-ci.yml`'s always-run changes job; `docs/testing/emulator.md` §6ay),
  `wifi-lookup-fixtures.py` (design 125 step 2: the server's `server/testdata/wifi_lookup/server.json` - the golden
  request for Apple's Wi-Fi location service, fake Apple answers with what the server must read from each, the failure
  histories behind the orange line - from its own protobuf encoder; documentation MACs, made-up coordinates, never a
  real capture; standard library, `--write`, `--selftest` run by `server-ci.yml`'s always-run changes job),
  `mmsc-hosts.py` (the server's `server/testdata/mmsc_hosts.txt`, design 83 §3: each operator's MMS server hosts from
  AOSP's `apns-full-conf.xml` at a pinned commit and SHA-256, in two classes - unasked and hints; its `UNASKED` stays
  `"none"` until design 77's step 0; `--selftest`, no network, converts `scripts/mmsc-hosts-sample.xml` and checks the
  committed list's lines against the SHA-256 the script holds; run by `server-ci.yml`'s always-run changes job),
  `grapheneos-boot-keys.py` (design 129 §9: the server's `server/src/attestation/grapheneos_boot_keys.json` from
  GrapheneOS's signed `attestation.json`, its OpenSSH signature checked with `ssh-keygen -Y verify` against the signer
  the script pins, an older `timestamp` refused; `--selftest`, no network, a vendored sample signed by a throwaway key
  in `scripts/grapheneos-boot-keys-sample/`; run by `server-ci.yml`'s always-run changes job), `device-key-vectors.py`
  (design 129 §7.1: the crypto rows of `contracts/core/device_key.json` from a throwaway P-256 key derived from a fake
  phrase - public data only, pure Python; `--check` run by `server-ci.yml`'s always-run changes job),
  `emulator-attestation-vectors.py` (design 129b §7: the `emulator_keys` profile's chains and rows in
  `server/testdata/attestation/emulator/`, from throwaway keys and made-up values in the emulator gate's shape, never a
  capture; pure Python; `--check` run by `server-ci.yml`'s always-run changes job).
- `PLAN.md` - the plan and status, and the fix backlog ("Fix backlog": keep it current as items come in and are done).
- `.github/` - CI for both parts (see below).
- `kids-launcher-mdm/`, `kid-phone-server/` (gitignored, if present) - the old standalone clones.
  Don't edit them; work in `launcher/` and `server/`.

## Words in the UI

The user (2026-11-17): "vi bør ikke være så wordy og over-explaining hele tiden". This holds for the PWA, the
launcher and the music app, in nb and en alike:
- A heading or a choice is a word or two ("Roaming": "På", "Av", "Bare Vibb"); no sub-line under a choice that
  restates or explains it.
- An explanation goes behind an (i) alone (the PWA's `<details class="info">`), with no "About ..." label beside it.
- A state line says the state, in a few words; how to change it goes behind the (i).
- Say it once: no footer that repeats the page, no sentence that repeats the label above it.
- Speak up only when something is wrong: a state that is as it should be shows nothing (or a quiet value), and
  what needs the parent - a switch that stops the page's controls working, a step left to do - is one short orange
  line, how to fix it behind the (i) (the user, 2026-11-17, of Android's roaming switch: "si ifra om denne IKKE er
  skrudd på", and "dette er et likt prinsipp som burde være over alt").
- A choice the parent made is never a warning (the user, 2026-11-18: "foreldre pin som ikke er satt - det er galt. men
  å velge å ikke bruke funksjon x eller y - som vi gir valg om, det er ikke galt"): what follows from a choice the PWA
  offers is at most a quiet note at that choice, behind its (i); Attention and orange lines are for what is wrong or undone.
- On the kid's phone fewer words still. A design note's copy table is checked against this before the build.

## Build and test

**Supported Android: 14 to 17** (the user, 2026-11-16: "alt fremover skal være 14-17 kompatibelt"). Both apps are minSdk 34 and every change must work on Android 14, 15, 16 and 17: an API above 34 behind a `Build.VERSION.SDK_INT` gate with a sane path on 14 (lint's NewApi in CI catches a missing gate, never suppress it), and a platform behaviour that differs between them (Device Owner, lock task, roles, notifications and Modes, VPN, telephony, location, WebView) checked against each version's behaviour changes. A design note names the versions it was checked on; an emulator gate that depends on the version runs on the lowest and the highest it touches. The Jelly Star runs Android 16 (Unihertz's update, from September 2026).

**No backwards compatibility on the phones yet** (the user, 2026-11-18: "Husk dette sånn genrelt ift backwards compatibility; det er enda ikke viktig" - only emulators run Vibb so far): no migration path for phone-side data or stored state and no code for older launcher/music versions; a new format drops or resets the old one. The server's database migrations still run (the user's server holds real data), and a release still ships the server first.

Server (from `server/`): `cargo test`, `cargo fmt --check`, `cargo clippy --all-targets`.

Launcher (from `launcher/`, JDK 17, `local.properties` with `sdk.dir=...`):
`./gradlew assembleDebug assembleRelease testDebugUnitTest`. Music app (from `music/`, the same JDK and
`local.properties`): `./gradlew assembleDebug assembleRelease testDebugUnitTest -PwarningsAsErrors=true`.
Without `app/libs/tsnet.aar` the debug build falls
back to a stub and a release build fails (security review L13; `-PrequireTsnet=true` fails any build). No Firebase config is needed (the
SSE stream is the only nudge), and `assembleRelease` fails if Firebase or Play services comes back on the
release classpath.

Gradle build chain (security review L9): both wrappers pin the distribution (`distributionSha256Sum`), CI validates
both `gradle-wrapper.jar`s (`gradle/actions/wrapper-validation`, pinned by commit) before Gradle runs, and both apps
verify every dependency's SHA-256 against `gradle/verification-metadata.xml` (no signatures; sources/javadoc jars
trusted for the IDE). A new or bumped dependency or plugin fails the build until its checksums are added: run
`./gradlew --write-verification-metadata sha256 --continue help build` (launcher: with `libs/tsnet.aar` present) and
review the diff - new entries are trust on first use, so compare them with the publisher's `.sha1`/`.sha256` where it
has them. CI's aapt2 (`com.android.tools.build:aapt2:<AGP>-<build>:linux`) is never resolved on an aarch64 machine
with the `aapt2FromMavenOverride`: an AGP bump needs its entry by hand (resolve it in a throwaway build, as on
2026-10-11).

Shared between the two and checked by tests on both sides: `contracts/core/phone_vectors.json` and
`launcher/app/src/test/resources/phone_vectors.json` must be identical (server `phone::tests`,
launcher `PhoneNumbersTest`). The policy JSON shape is pinned on both sides
(`policy_json_keys_snapshot` / `PolicyResponseCompatTest`). A change to the API goes into both
directories in the same commit. The music library and an entry's episode list are pinned by
`contracts/music/music_library.json` and `contracts/music/music_listing.json` (`music_library_snapshot`,
`music_listing_snapshot`, regenerated with `MUSIC_LIBRARY_SNAPSHOT_WRITE=1` / `MUSIC_LISTING_SNAPSHOT_WRITE=1`); the
music app keeps byte-identical copies in `music/app/src/test/resources/` (with `music_icons.json`;
`ContractFilesTest`), and a change goes into both in one commit. The launcher-music bridge's AIDL
(`*/app/src/main/aidl/me/vibb/bridge/`) and the names both apps spell (`bridge_contract.json` in both apps'
test resources) are byte-identical in `launcher/` and `music/` (`MusicPlanTest`, `BridgeTest`); new methods are
appended. `contracts/music/music_state.json` is every `music_state` field: the server keeps them all, the launcher
keeps the music app's, the music app sends exactly those. Spotify's host list `contracts/music/spotify_hosts.json`
(design 26c §6.2; provisional until the DNS capture) is byte-identical in both apps' test resources
(`spotify::tests::hosts_match_the_contract`, `ContractFilesTest`, `MusicPlanTest`); a change goes into all three in
one commit, with each side's constants (`HOST_SUFFIXES`/`EXACT_HOSTS`, `SPOTIFY_HOST_SUFFIXES`/`SPOTIFY_EXACT_HOSTS`).
The launcher's status body with `time_state.zone` (design 34 P13; since 2026-11-25 with `storage_full`) is `contracts/core/time_status_report.json`,
byte-identical in `launcher/app/src/test/resources/`: the launcher's `TimeStateZoneTest` encodes exactly it, the
server's `devices_list::a_launcher_report_with_its_zone_shows_the_rules_end` posts it, and both compare the copies.
The launcher's status body with `dns_upstream` (design 36 §4.2) is `contracts/core/dns_upstream_status.json`,
byte-identical in `launcher/app/src/test/resources/`: the launcher's `DnsUpstreamStatusTest` encodes exactly it, the
server's `custom_dns::dns_upstream_status_contract` posts it, and both compare the copies. The policy's `dns_upstream`
is pinned by `policy_json_keys_snapshot` and the launcher's `PolicyResponseCompatTest`.
The versionCode a launcher release tag was built with is `contracts/core/launcher_tag_codes.json` (tag -> code or null),
byte-identical in `launcher/app/src/test/resources/`: the launcher's `SelfUpdatePlanTest` (``a release tag's
versionCode is the shared contract's``, ``the tag codes are identical to the server's copy``) and the server's
`install_now::tests::launcher_tags_read_as_the_shared_contract` / `launcher_tag_codes_match_the_launchers_copy` read
every `codes` row and compare the copies; the server's `device_pages::tests::music_versions_read_as_the_shared_contract`
reads the `music_codes` rows (the music app's version from its code), and `scripts/release-version.sh --selftest`
checks both lists' rows against what the workflows build.
The launcher's `POST /api/devices/power` body (design 38 §2; since 2026-10-25 its `off` with the shutdown's last stored fix,
`location`, since design 131 with the fix's `check`) is `contracts/core/power_report.json`, byte-identical in
`launcher/app/src/test/resources/`: the launcher's `PowerEventsTest` encodes exactly it, the server's
`power::a_launcher_report_raises_both` posts it (its instants moved to the last hour), and both compare the copies.
The launcher's status body with `extra_locations` and `zone_watch` (design 64 step 2; design 131: a Wi-Fi fix's
`check` - a `vibb_wifi` location with `link`, a `vibb_apple` extra without one - and `zone_watch.wifi_disagreed`) is
`contracts/core/zone_status_report.json`, byte-identical in `launcher/app/src/test/resources/`: the launcher's
`ZoneReportTest` encodes exactly it, the server's `zone_watch::a_launcher_report_raises_the_arrival` posts it (its
instants moved to the last minutes), and both compare the copies. The policy's `location_policy.zones` is pinned by
`policy_json_keys_snapshot` and the launcher's `PolicyResponseCompatTest`. `server/testdata/zone_vectors.json` stays the
server's alone: the phone judges no zone (design 64 decision 1).
The launcher's status body with `installed_apps[].version_code`/`version_name` (2026-10-25, the `app_updated` of every
checked catalog app) and `other_signer` (2026-11-24: a Vibb music package not signed with Vibb One's key, which the
phone refuses - Device › Music's orange line) is `contracts/core/installed_apps_report.json`, byte-identical in
`launcher/app/src/test/resources/`: the launcher's `SyncScheduleTest` encodes exactly it, the server's
`app_updates::the_installed_apps_contract_raises_app_updated` posts it (`tests::music` posts its music row and reads
the line), and both compare the copies.
The launcher's `POST /api/devices/time-request` body and the three answers (design r13 §2.5), and the parent's answer
as the policy carries it (design 100 §14.5), are `contracts/core/time_request.json`, byte-identical in
`launcher/app/src/test/resources/`: `request`, `answers`, `policy_no`/`policy_yes`/`policy_unnamed`/`policy_lift_only`/
`policy_ended` (the `time_policy` fragment the server serves: `request_answers: true`, `answer {request_id, answer, by?,
at_ms, lift_id?, minutes?}`, lifts with `by?`/`at_ms`; `policy_ended` a yes whose pause was ended early - no `lift_id`,
no `minutes`) and `states` (the launcher's
rows `{name, record, count, policy, day, now_ms} -> {state, shade_note, screen_note, can_ask}`). The launcher's
`TimeRequestsTest` encodes exactly its `request`, decodes each answer and every policy row, and runs every `states` row;
the server's `time_request::the_contract_answers` posts the request (its instant moved to a minute ago) and produces every
answer row, and `time_request::the_contract_policies` produces every `policy_*` row from a real flow (the instants the
run's); both compare the copies. These rows pin `time_policy.answer`'s and a lift's `by`/`at_ms` keys;
`policy_json_keys_snapshot` pins `time_policy`'s own keys (`request_answers` among them) and the launcher's
`PolicyResponseCompatTest` decodes them.
Dagen's title rules (design 69 §3.4) are `contracts/core/calendar_title_rules.json` (`{"titles": [{rules, calendar,
title, description?, shown}]}`, `shown: null` = hidden), byte-identical in `launcher/app/src/test/resources/`: the
server's `calendar::tests::the_title_rules_vectors` and the launcher's `TitleRulesTest` run every row and compare the
copies. The launcher's status body with `calendar` (design 69 §4.2) is `contracts/core/calendar_status_report.json`,
byte-identical there too: the launcher's `CalendarReportTest` encodes exactly it, the server's
`calendar::a_launcher_report_lists_the_calendars` posts it, and both compare the copies. The policy's `calendar` is pinned
by `policy_json_keys_snapshot` and the launcher's `PolicyResponseCompatTest`.
The answers of `POST /api/devices/music/storytel/token` (design 58 §2.2) are `contracts/music/storytel_token_answers.json`,
byte-identical in both apps' test resources: the server's `music_storytel::the_token_route_answers_the_shared_contract`
produces every row, the launcher's `StorytelTokenCourierTest` and the music app's `StorytelBridgeTokenTest` read each,
and all three compare the copies (`MusicPlanTest`, `ContractFilesTest`); a change goes into the three in one commit.
`scripts/fake-storytel.py` reads its login and bookshelf from `server/testdata/storytel/` (`fake_login_*.json`,
`aes_vectors.json`, `bookshelf.json`; the server's `fake_login_fixtures_match`) and answers its `/fake/jwt` (the phone
alone, design 58 §5.6) from `storytel_token_answers.json`'s rows; since design 58 step 2 the music app has no login
fixtures (the phone never logs in).
The launcher's built-in secure-DNS hosts `launcher/app/src/main/assets/dns/doh_hosts.txt` (design 67 §2.2) are
byte-identical in `server/testdata/doh_hosts.txt`, which the server compiles in for the Allowed Domains notice
(`dns_server::is_builtin_doh_host`): the server's `dns_server::tests::doh_hosts_match_the_launchers_asset` and the
launcher's `DnsFilterEngineTest` compare the copies; a change goes into both in one commit.
The calendar links' contract (design 72 §3) is `contracts/core/calendar_feeds.json`, byte-identical in
`launcher/app/src/test/resources/`: `feed` (the document `GET /api/devices/calendar/feeds/{uid}` serves for
`server/testdata/calendar_feeds/contract.ics`), `keys` (a link's calendar key), `policy` (a `calendar.feeds` row),
`report` (a status report's `calendar.feeds` row), `zone` and `provider` (the Events rows the phone writes in that
zone). The server's `calendar_feeds::tests::the_contract` produces `feed` and `keys` (`CALENDAR_FEEDS_CONTRACT_WRITE=1`
rewrites them), `intake_limits` keeps `report`'s keys; the launcher's `CalendarFeedsTest` maps `feed` to exactly
`provider`, checks the key and decodes and encodes the rows; both compare the copies. The policy's `calendar.feeds` is
pinned by `policy_json_keys_snapshot` and the launcher's `PolicyResponseCompatTest`.
The fixed emergency list (design 73 §4: `always`, `by_country`, `vectors` of a raw number and a country code) is
`contracts/core/emergency_numbers.json`, byte-identical in `launcher/app/src/test/resources/`: the server's
`emergency::tests` and the launcher's `EmergencyMenuTest` check their constants (`emergency::ALWAYS`/`BY_COUNTRY`,
`Emergency.ALWAYS`/`BY_COUNTRY`) against it, run every vector and compare the copies. The policy's
`call_policy.emergency_menu` is pinned by `policy_json_keys_snapshot` and the launcher's `PolicyResponseCompatTest`.
The weather's contract (design 75 §7.1) is `contracts/core/weather.json`, byte-identical in
`launcher/app/src/test/resources/`: `forms` and `now_keys` (every key and form the server may send), the rule vectors
(`words`, `periods`, `dark`, `cells`, `nowcast`) and the answers (`answer`, `answer_now_ms`, `answer_line`,
`now_answers`, `statuses`). The server's `weather::tests` runs every row and writes the computed parts from the
recorded MET answers in `server/testdata/weather/` (`WEATHER_CONTRACT_WRITE=1`); the launcher's
`WeatherContractTest` decodes the answers into its DTOs and checks every symbol code; both compare the copies. The
policy's `launcher_ui.lock_weather` is pinned by `policy_json_keys_snapshot` and the launcher's
`PolicyResponseCompatTest`.
The `leave_tailnet` command's answers (security review S-L10, round L4b) are `contracts/core/leave_tailnet.json`
(`{command, answers: [{success, message}], report}`), byte-identical in `launcher/app/src/test/resources/`: the
launcher's `SecurityLowsBTest` produces every row from `leaveTailnetAnswer`/`leaveTailnetRefusal` and encodes the
status report's `report` keys (`tailnet_joined`) exactly; the server's `tests::leave_tailnet` words each answer
(`locate::reword`), posts `report` and shows Leave Tailnet only then; both compare the copies.
The kid's own events written to the marked CalDAV calendar (design 72b §8) are `contracts/core/calendar_kid_events.json`,
byte-identical in `launcher/app/src/test/resources/`: `provider` ("Min kalender"'s rows) and the exact POST body
`snapshot` (and `cut_variant` past the limits), `canonical` (D2.7's form and hash, row by row), `policy` /
`policy_waiting` (a `calendar.kid_caldav`), `answers` (every answer of `POST /api/devices/calendar/kid-events`) and
`back_provider` (the provider values each hand-back writes). The server's `calendar_kid_events::tests::the_contract`
posts `snapshot` against the stateful fake CalDAV (`tests::fake_dav`), checks each PUT's body against
`server/testdata/calendar_kid_events/<uid>.ics` (server-only) and produces `answers` and `back_provider`
(`CALENDAR_KID_EVENTS_CONTRACT_WRITE=1` rewrites them); the launcher's `KidCaldavTest` encodes `provider` to exactly
`snapshot`, hashes `canonical`, maps each hand-back to exactly `back_provider`; both recompute the hand-back ids and
compare the copies. The policy's `calendar.kid_caldav` is pinned by `policy_json_keys_snapshot` and the launcher's
`PolicyResponseCompatTest`.

The phones' server address rule (launcher security review S-M1, 2026-11-04: `https://`, or `http://` to a Tailscale IP
literal) is `contracts/core/server_url_vectors.json` (`{url, verdict}`: `https`, `tailnet_http`, `refused`),
byte-identical in `launcher/app/src/test/resources/`: the server's `config::tests::the_server_url_vectors`
(`config::check_server_url`, Phone Setup and `PHONE_SERVER_URL`) and the launcher's `ServerTransportTest`
(`serverUrlVerdict`, the QR, the Settings edit and every server client) run every row and compare the copies.
The longest "pause all restrictions" (2026-11-04, QA of pause-end-report M1) is `contracts/core/restrictions_pause.json`
(`{pause_max_ms}`), byte-identical in `launcher/app/src/test/resources/`: the server's
`models::paused_at_tests::the_longest_pause_is_the_shared_contracts` (`RESTRICTIONS_PAUSE_MAX_MS`: a paused report older
than it is over) and the launcher's `RestrictionsPauseTest` (`RESTRICTIONS_PAUSE_DURATION_MS`) check their constant against
it and compare the copies.

The launcher's certificate check (design 76: the server's issuer pinned once the server switches it on) is
`contracts/core/server_tls_report.json`, byte-identical in `launcher/app/src/test/resources/`: `report` (the status
body's `server_tls`: `enforcing`, `seen`, `outside` rows of both kinds), `policy` (the policy's `server_tls` per server
state: `{}` watching, `{"enforce": true}` on, `{"enforce": false}` off), `flags` (the phone's stored flag from each
policy value - absent and malformed included - start state and connection: `passing`, `failing`, `tailnet`,
`debug_http`), `drops` (the outside rows leave only after a 2xx over a passing or a tailnet connection) and
`recovery` (the stored tailnet recovery address from a policy's `server_tls.recovery_url`: taken only over a passing or
tailnet connection, only as tailnet http). The launcher's `ServerPinsTest` encodes `report` exactly and runs every
`flags`, `drops` and `recovery` row; the server's
`tests::server_tls::a_launcher_report_moves_the_state` posts `report` (its instants moved to the last hour) and produces
every `policy` row; both compare the copies. The built-in set itself stays the launcher's (`ServerPins.kt`): the server
never judges a chain. The policy's `server_tls` is pinned by `policy_json_keys_snapshot` and `ServerPinsTest`.

The SMS engine's contract (design 77 §7, step 1) is `contracts/core/sms_contract.json`, byte-identical in
`launcher/app/src/test/resources/`: `modes` (each mode pair: the server sends it, the launcher applies it), `policy` (a seeded `call_policy` with the SMS keys:
the per-contact `sms_in`/`sms_out`/`mms`, `sms_names`, `own_numbers`, `mmsc_hosts`, `sms_held_drop`, design 80's
`sms_groups`; `groups_off` the switch off; design 83's `mmsc_hosts_row`, a SIM's row of entries), `report` (the exact
`sms_state` with design 80's `own_learned`, design 82's `has_history` and design 83's `sim_operators` and
`mmsc_unlisted`, and `own_learned_conflict`), `mmsc_entries` (design 83 §1: an MMSC entry, `host` or `host:port`, to
valid or not - the server's `mmsc_hosts::valid_entry`, the launcher's `MmsGate.validEntry`), `held` (the exact `POST /api/devices/sms-held` body, its answers and a refusal), `clean` (the
sender cleaned by its class before it leaves the phone), `intake` (the server's sender check), `names` and
`name_matches` (D17's letter rule and fold), and `app` (design 82, Meldinger as an app: its `key`
`me.vibb.builtin.messages`; `lock`, the server's stored choice and `sms_mode` to `lock_glance.sms`; `lock_read`, the
launcher's reading of a `lock_glance` to the word and the level shown, a missing key or word Off; `display`, the
key's `launcher_ui.app_display` entry - a name, a glyph on a colour, off Home). The server's `sms::tests` produce the
policy, post the report and the held body and run every row (`the_app_lock_rows` the `app` rows;
`tests::app_display::meldinger_has_a_row_and_a_name_and_icon_of_its_own` saves and sends exactly `display`); the
launcher's `SmsContractTest` decodes and encodes them exactly and runs the rows (`display` read as Meldinger's look);
both compare the copies. The receive and send rules (D4/D5) stay the launcher's alone, in
`launcher/app/src/test/resources/sms_rules.json` (`SmsRulesTest`). The policy's new `call_policy` keys are pinned by
`policy_json_keys_snapshot` and the launcher's `PolicyResponseCompatTest`.

The kid's contact request from the phone book (design 77 §14) is `contracts/core/contact_request.json`, byte-identical
in `launcher/app/src/test/resources/`: `request` and `request_without_matrix` (the exact `POST
/api/devices/contact-request` bodies), `answers` (accepted, `capped`, `invalid`, `known`), the check vectors `valid`
(with the cleaned name and normalised number) and `invalid`, `photo` (the request's picture, Calls & Contacts tidied
(6): `POST /api/devices/contact-request/{id}/photo`'s route, content types and limits, the server's `answers` by case
and the phone's `phone_answers`, done or retry by status) and `policy` (`call_policy.contact_requests` rows: `open`,
`declined`, `added`). The server's `tests::contact_requests` posts the requests and produces every answer (the
picture's in `the_photo_answers`, on both listeners), and
`handlers::contact_requests::tests` runs the vectors; the launcher's `ContactRequestsTest` encodes the requests exactly,
reads each answer and policy row, maps every `phone_answers` row and runs the vectors through its own check; both
compare the copies. The policy's
`call_policy.contact_requests` is pinned by `policy_json_keys_snapshot` and the launcher's `PolicyResponseCompatTest`.

The apps' real logos (design 87) are `contracts/core/app_logos.json`, byte-identical in
`launcher/app/src/test/resources/`: the limits (`size_px`, `max_bytes`, `max_per_phone`,
`max_wanted`), a test PNG (`png_base64`, a disc with a text chunk the server's re-encode drops) and its `png_sha256`,
design 87b's Automatic tile (`tile_png_base64`, `tile_png_sha256`), `report` (the exact status body with
`installed_apps[].logo` and `auto_tile` - an app without a monochrome layer names its logo as its tile), `answer` (the
report's `200 {"wanted_logos"}`), `upload`
(`POST /api/devices/app-logos/{hash}`, `image/png`) and `answers` (each upload case's status). The server's
`tests::app_logos::the_contract` posts `report`, uploads the PNG and makes every `answers` row; `intake_limits` keeps
`report`'s keys; the launcher's `AppLogosTest` encodes `report` exactly from `InstalledApp`, decodes `answer` into
`WantedLogos`, hashes the PNG and reads the rows; both compare the copies. No policy key: the wanted list rides on the
report's answer.

A rule turned on now (design 93, "Slå på nå") is `contracts/core/lock_now.json`, byte-identical in
`launcher/app/src/test/resources/`: `policy` (a `time_policy` the server serves: a copy of
Leggetid first - negative `id`, one weekday's window, `until_ms`, `card_first: true` -, a scheduled Skole and Leggetid
with `until_ms: null` and `card_first: false`, a pause of Leggetid and its twin for the copy - every lift with design 100's
`by` and `at_ms`, and the `time_policy`'s `request_answers: true`), `policy_end_locked` (the
same with a Middag from the copy's end), design 123's `policy_on_demand` (a rule without days, Skjermstopp, turned on:
its copy and the rule itself) and `policy_off_rule` (a rule switched off for the phone turned on: its copy alone) and
`cases` (`{policy, at, zone, locked, card_rule, card_until}`). The server's
`lock_now::tests::the_contract` produces the four policies from seeded rows at a fixed now (`LOCK_NOW_CONTRACT_WRITE=1`
rewrites them); the launcher's `LockNowTest` decodes them and runs every case through `inForceAt`, `decideTimeLock`,
`cardRule` and `lockEnd`; both compare the copies. Every rule's `until_ms` and `card_first` are pinned by
`policy_json_keys_snapshot` and the launcher's `PolicyResponseCompatTest`.

The round "Mobilnett" (design 94, data roaming; design 95 revision 3, Bare Vibb and the mobile-network settings lock;
design 97, "I utlandet", `where` and the kid's switch) is
`contracts/core/mobile_network.json`, byte-identical in `launcher/app/src/test/resources/`: `policy` (the top-level `abroad`,
`apps_mobile` and `mobile_settings_locked` for a new phone and after each save), `abroad_choice` (the launcher's
reading of `abroad`, a missing one Ingen data, no policy `none`), the rule rows `roaming_locked` and `settings_locked`
(each with the pause's lift), `where` (the launcher's home/abroad/unknown from telephony), `shapes` (the VPN's shape,
the sink's reason and lockdown from the filter switch, the choices, the kid's switch, a lift, the network and a held
sink with no network - the launcher's alone), `kid_row` (the kid's Mobildata row), `report` (the exact `data_roaming`
and `mobile_data` of a status report) and `clean`/`clean_mobile` (the server's field-by-field cleaning), and design 116's `vpn_filter` ("filter on, tunnel
down": `report_after_ms`, the exact `reports`, the `states`, `periods`, `alarm` and `sync` rows the launcher runs, the
`raise` and `clean` rows the server runs). The server's
`tests::mobile_network::the_contract` saves the choices through `POST /devices/{id}/mobile` and produces each
`policy` row, posts `report` and checks what is stored, runs the cleaning rows; the launcher's `MobileNetworkTest`
decodes the policy rows, runs every rule, `where`, shape and kid-row row through `abroadChoice`, `roamingLocked`,
`mobileSettingsLocked`, `whereOf`, `parentSinkOn`, `applyShape`, `sinkReason`, `reportedSink`, `alwaysOnFor` and
`kidMobileRow`, and encodes `report` exactly; both compare the copies. The policy's three keys are pinned by `policy_json_keys_snapshot` and the launcher's `PolicyResponseCompatTest`.

The heartbeat report (design 95 §3.5, step 95-T2) is `contracts/core/status_heartbeat.json`, byte-identical in
`launcher/app/src/test/resources/`: `policy` (the top-level `status_heartbeat`,
`status_heartbeat_fields` and `status_gzip`), `heartbeat_fields` (what a heartbeat carries and the phone's hash leaves
out) and `fields_tag` (their name in a heartbeat), `full` (a full report with its `hash`), `heartbeat` (the heartbeat
that names it), `heartbeat_other_fields` (one naming another list) and `answers` (`{"full_report": true}`). The hash is the
phone's alone (`StatusReports.kt`'s `reportHash`); the server keeps the full body under it (`device_report_base`) and
expands a heartbeat into that report before the one intake runs (`status_heartbeat`). The launcher's `StatusReportsTest`
encodes `full` and `heartbeat` exactly from one report, checks the hash and the fields list and reads the answer; the
server's `tests::status_heartbeat` posts `full` and `heartbeat` and checks what is stored and every refusal; both
compare the copies. The policy's three keys are pinned by `policy_json_keys_snapshot` and `StatusReportsTest`.

Flymodus through the Digital assistant role (design 98) is `contracts/core/airplane_mode.json`, byte-identical in
`launcher/app/src/test/resources/`: `bit` (64 in `quick_controls_mask`), `rows`
(the kid's Flymodus row and the section from `{mask, role, restricted, on, wifi_up, busy, last_error, error_age_ms}`,
the Off-only rows included), `report` and `report_open` (the exact `airplane_mode` of a status report), `clean` (the
server's field-by-field cleaning), `emergency` (`line`: a call and a period to the emergency event's airplane line;
`off_by`: the phone's attribution of an end), `rules` (`on`: the turn-on plan, a rule in force changing nothing;
`edges`: no airplane action at any time-rule edge), `home_line` (Home's "Flymodus er på") and `sheet` (the time-rule
screen's "···" and its rows). The launcher's `AirplaneModeTest` encodes the reports exactly and runs every row through
its pure functions; the server's `tests::airplane_mode` posts the reports and checks what is stored, runs `clean` and
`emergency.line`, and `intake_limits` keeps the reports' keys; both compare the copies. No policy key: bit 64 rides in
`quick_controls_mask`.

The parent code's pause of the rule in force on the phone (design 98 §4.4, Foreldrekode) is
`contracts/core/rule_pause.json`, byte-identical in `launcher/app/src/test/resources/`:
`lengths` (the PWA's `RULE_LIFT_MINUTES`), `policy` (the top-level `rule_pause: true`, a copy's `copy_of` and the lifts
with `phone_key` - since design 100 every lift with `at_ms`, the PWA's with `by`, and `time_policy.request_answers`), `request` (the exact `POST /api/devices/rule-pause` body), `answers` (each body's status and answer:
accepted, a retry's same ids, `over`, `unknown_rule`, the refused 400s; its instants are moved to the last minutes),
`phone_answers` (the phone's action for each: `acked`, `done`, `retry`), `plan_zone`/`plan_policy`/`plans` (the
offered lengths and each rule's own end, a copy paused with the rule it copies) and `adopt` (whether a pause still
lifts: before the post, acked, ended in the app before any policy carried it, a reboot, pruned, the clock set back).
The launcher's `RulePauseTest` encodes `request` exactly and runs every row through `rulePausePlan`,
`rulePauseAction` and `phonePauseLifts`; the server's `tests::rule_pause` posts the bodies and produces every answer,
checks the `time_lifts` rows and the security event, and serves `policy`'s lift keys; both compare the copies.
`lock_now.json`'s copy rows carry `copy_of`. The policy's `rule_pause`, `lifts[].phone_key` and `rules[].copy_of` are
pinned by `policy_json_keys_snapshot` and the launcher's `PolicyResponseCompatTest`.

Snarveier, the kid's quick panel in the shade (design 99), is `contracts/core/shortcuts.json`, byte-identical in
`launcher/app/src/test/resources/`: `bit` (128 in `quick_controls_mask`), `max_items`
(4), `items` (each key with its gating bit, `null` for none, and its names in nb and en), `defaults` (the default order),
`report` and `report_hidden` (the exact `shortcuts` of a status report) and `clean` (the server's cleaning: unknown keys
and duplicates dropped, cut to four, never shown while hidden, a non-object dropped). The launcher's `ShortcutsTest`
checks its keys, bits and default order against `items` and `defaults` and encodes the reports exactly; the server's
`tests::shortcuts` posts the reports and checks what is stored, runs every `clean` row and renders the Home Screen
page's sub-lines from the names, and `intake_limits` keeps the report's keys; both compare the copies. No policy key:
bit 128 rides in `quick_controls_mask`.

Wi-Fi sign-in and Chrome only for logins (design 102) is `contracts/core/login_windows.json`, byte-identical in
`launcher/app/src/test/resources/`: `policy` (the top-level
`captive_portal` and `login_browser` for a new phone and after the page's saves: the switch, Advanced's parent-code
requirement, "one more", the role, a preset ticked and unticked with an own entry equal to its host kept, Chrome
ticked), `older_server` (the launcher's reading of a policy with neither key: on, `require_code: true`, the role off),
`report` and `report_off` (the exact `captive_portal` and `login_browser` of a status report), `clean` (the server's
field-by-field cleaning) and `entries` (the add field's input to an entry or a refusal). The server's `tests::logins`
saves through `POST /devices/{id}/logins` and produces each `policy` row, posts the reports and checks what is stored,
and runs `clean` and `entries` (`logins::valid_entry`); `intake_limits` keeps the reports' keys; the launcher's
`LoginWindowsTest` decodes `policy` and `older_server`, encodes `report` exactly and runs `entries` through its own
check (`loginEntry`); both compare the copies. The window's and the role's own rules stay the launcher's alone, in
`launcher/app/src/test/resources/login_rules.json` (`PortalRulesTest`, `LoginBrowserTest`). The policy's
`captive_portal` and `login_browser` are pinned by `policy_json_keys_snapshot` and the launcher's
`PolicyResponseCompatTest`.

The blocked notification channels (design 107) are `contracts/core/notification_channels.json`, byte-identical in
`launcher/app/src/test/resources/`: `channels` (the table in display order:
`{key, id, nb, en, feature, orange, attention}`, the names the phone's own strings), `report`, `report_none` and
`report_all_off` (the exact `notification_channels` of a status report), `read` (the launcher's reading of Android's
channels, groups and app-wide switch, Snarveier's channel in use, Android 16's reserved `android.app.*` skipped),
`clean` (the server's cleaning) and `pages` (`{sms_mode, calls_mode, language, shortcuts_bit, report}` to About ›
Sound's value, its orange and the Attention title). The server's `notification_channels::tests` runs `clean` and
checks its table, `tests::notification_channels` posts the reports and checks what is stored, runs every `pages` row
through About and the summary and checks the Devices list's badge, and `intake_limits` keeps the reports' keys; the
launcher's `NotificationChannelsTest` checks its table and names against `channels` and its strings, runs every `read`
row through `channelsReport` and encodes the reports exactly; both compare the copies. No policy key.

The apps with notification access and Android Auto (the policy's `allow_android_auto`, Advanced › Android Auto) are
`contracts/core/notification_listeners.json`, byte-identical in `launcher/app/src/test/resources/`: `android_auto`
(the package), `policy` (`off`, a new phone's, and `on`), `report`/`report_none` (the exact `notification_listeners` of
a status report: `[{package, off, system}]`, Vibb One left out, `off` only while hidden), `read` (the launcher's reading of Android's enabled listeners),
`clean` (the server's cleaning) and `unknown` (the listeners About This Phone names in orange: reading, not a system app,
not Android Auto, and neither an allowed app nor a catalog app ticked for the phone). The server's `notification_listeners::tests` runs `clean` and
`unknown`, `tests::notification_listeners` saves the switch and produces `policy`, posts the reports and checks About,
and `intake_limits` keeps the reports' keys; the launcher's `SystemReadersTest` decodes `policy`, runs `read` through
`notificationListenersReport` and encodes the reports exactly; both compare the copies. The other Home apps' and
Android Auto's plan rows stay the launcher's (`SystemReadersTest`). The policy's `allow_android_auto` is pinned by
`policy_json_keys_snapshot` and `SystemReadersTest`.

App push (design 108, Vibb as the UnifiedPush distributor) is `contracts/core/unified_push.json`, byte-identical in
`launcher/app/src/test/resources/`: `policy` (the policy's `unified_push`
`{on, origin, regs, stopped, refresh}` in the rows `off`, `on_empty`, `on_two`, `stopped`, `origin_changed`, and design 108b's
`refresh_outside` and `refresh_refused` - `refresh` is `[{reg, why: outside|refused}]`, an unknown `why` read as `outside`), `older_server`
(a policy without the key: UP off, the receiver disabled), `register` (the exact `POST /api/devices/up/registrations`
bodies, every answer - `ok`, a retry, the 409 refusals `off`/`stopped`/`not_allowed`/`limit`/`busy`/`conflict`, the 400
- and `failed_reason`, each refusal to the connector's `ACTION_REQUIRED` or `NETWORK`; design 108b's VAPID rows `body_vapid`,
`body_vapid_changed` (the same `reg`, another valid key: the same endpoint, the key updated) and `body_vapid_not_a_point`
with the answers `ok_vapid`, `vapid_changed` and `vapid_not_a_point`), `unregister` (the `DELETE`'s
204), `messages` (the exact `POST /api/devices/up/messages` body with acks and an answer with a minimised Matrix body
and a binary one, base64; design 115's `body_held`, the first pull after a hold), `event` (the SSE event `up`/`up`),
`endpoint` (the endpoint's form), and design 115's `report`/`report_open` (the exact `unified_push` of a status report,
held and not) and `clean` (the server's `unified_push::clean_report` into `device_status.up_held`). The server's
`tests::unified_push` produces the policy rows and the answers from seeded data; the launcher's
`UnifiedPushContractTest` encodes the bodies, decodes every answer and policy row and maps each refusal; both compare
the copies. The server-only rows of the push routes are `server/testdata/unified_push_gateway.json`
(`tests::unified_push::gateway_rows`) and the VAPID check's vectors `server/testdata/unified_push_vapid.json`
(`unified_push::vapid::tests::the_rows`; made by `scripts/up-vapid-vectors.py` with a throwaway test key, public data only).
The policy's `unified_push` is pinned by `policy_json_keys_snapshot` and the
launcher's `PolicyResponseCompatTest`. The distributor's own rules (register, unregister and ack decisions, the delivery
plan, the `regs` reconcile, the origin rewrite) stay the launcher's alone, in
`launcher/app/src/test/resources/up_rules.json` (`UpRulesTest`, `UpDeliveryPlanTest`, `UpReconcileTest`), with design
115's hold rows (`hold`: the VPN's views to held or not).

The map links (design 110, the integration-boundary pilot: `server/src/integrations/map_links/`, the Cargo feature
`map_links`, `docs/notes/server/integrations.md`) have their own contract folder: `contracts/integrations/map_links/contract.json`,
byte-identical in `launcher/app/src/test/resources/map_links/`: `policy` (`map_links: {on}`, `off` and `on`), the
report rows (`report`, `report_steps_left`, `report_hidden`, `report_held_by_maps`: the status report's exact
`map_links`), `clean` (the server's field-by-field cleaning), the link rules `short_links`, `long_hosts` and
`feature_ids` (each side's own code runs every row) and `resolve` (the two request shapes of `POST
/api/devices/map-links/resolve`, every answer and the phone's action for it). The server's
`integrations::map_links::tests` runs every row through the real route and compares the copies; the launcher's
`MapLinksContractTest` runs the link rows through `MapLinkForms`, encodes the report rows exactly, decodes the policy
and maps every answer through `mapLinkAnswer`. The server's own rows (`server/testdata/map_links/server.json`: the
hops, the place lookup's answers cut down to the paths it reads, the PWA's lines) and the launcher's
(`launcher/app/src/test/resources/map_link_rules.json`, `MapLinksTest`: every link form to its pin, search or note)
stay each side's. The policy's `map_links` is pinned by `policy_json_keys_snapshot` and `PolicyResponseCompatTest`.

The Apple Wi-Fi lookup (design 125 step 2, an integration behind design 110's boundary:
`server/src/integrations/wifi_lookup/`, the Cargo feature `wifi_lookup`) has its own contract folder:
`contracts/integrations/wifi_lookup/contract.json`, byte-identical in `launcher/app/src/test/resources/wifi_lookup/`: `rules`
(the server's cache months - `fresh_months`, `negative_months` - and the phone's `stale_grace_h`), `policy`
(`wifi_lookup: {on}`, `off` and `on`), `request` (the exact `POST /api/devices/wifi-lookup` body), `answers` (each case
the route answers - only asked BSSIDs with `fresh_months_left` and the `unknown` ones: fresh, cached, stale rows while
Apple is paused with their wait, the cache alone over the server-wide limit or busy with its wait, `off`, `limit`,
`busy`, `unavailable` with `retry_after_s`, a 400 per bad form), `phone_answers` (each status and body to the phone's
action: use with a wait or none, a wait in seconds, or off until a policy says on) and `phone_rows` (what the phone
keeps from a 200 at a given instant, and until when). The server's `integrations::wifi_lookup::tests` produces every
`answers` row through the real route and compares the copies; the launcher's `WifiLookupContractTest` checks `rules`
against its constants, encodes `request`, decodes `policy`, maps every row through `appleStep` and runs `phone_rows`
through `storeApple`. The server's own rows (`server/testdata/wifi_lookup/server.json`, written by
`scripts/wifi-lookup-fixtures.py`) and the launcher's Apple rules (`PlacesTest`) stay each side's. The policy's
`wifi_lookup` is pinned by `policy_json_keys_snapshot` and `PolicyResponseCompatTest`. Apple's cell lookup (design
125b) added rows, none changed: `rules`' `cells_max`, `cells_a_day`, `cell_answer_after_s`, `cell_row_days`,
`cell_negative_days`; `request_cells` (the exact cells body); `cell_forms` (a cell's wire form to valid or not - the
server's `cells::Cell::parse`, the launcher's `parseCellWire`); `cell_answers` (each cells case: no age, the same body
from the cache as from Apple, the 503 while Apple can't be asked, the day's brake, the 400s - produced through the real
route, each 200 and 503 no sooner than the answer time); `cell_phone_answers` (each to the phone's action:
`appleCellStep`, the cell wait alone, a 409 both) and `cell_phone_rows` (`storeAppleCells`: 90 days, a negative 30, the
range). `server.json` gained `golden_cells` and `apple_cells`; the launcher's own cell rules are `CellPlanTest`'s.

Why an install failed (upstream review 2026-11-20) is `contracts/core/install_failure.json`, byte-identical in
`launcher/app/src/test/resources/`: `kinds` (the launcher's own reasons), `report` and `report_progress` (the exact
`POST /api/devices/apps/progress` bodies), `parse` (an Android result's status and message to the code the launcher
sends - never the message's text) and `clean` (the server's keeping of a sent `reason`). The launcher's
`InstallFailureTest` encodes the reports exactly and runs every `parse` row through `installFailureReason`; the
server's `install_failure::tests` runs `clean`, words every code made and compares the copies, and
`tests::tracked_apps::an_install_failures_reason_is_stored_and_worded` posts the reports. No policy key.

The phone setup status (design 114) is `contracts/core/setup_steps.json`, byte-identical in
`launcher/app/src/test/resources/`: `steps` (the one step table in display order: `key`, `need`, `read_by`, `when`,
`summary`, `level`, `features`, `phone_page`, `names`), `features` (each PWA choice a step stops and its page and
section), `report`/`report_no_hold` (the exact `setup` of a status report, the setup hold's `hold_until_ms` and
`hold_left_ms` in the first), `policy`/`policy_none` (the policy's `setup: {ticked}`), `tick` (the exact `POST
/api/devices/setup/ticks` body, each answer, the phone's `done`/`retry` per status), `needed` (the need rule's inputs
and readings to `missing`, `count`, `alarm`, `setup_row`) and `clean` (the server's cleaning). The server's
`setup_status::tests` runs every row, checks its table and names and compares the copies; `tests::setup_status` posts
`report` and the ticks; `intake_limits` keeps the report's keys; the launcher's `SetupStepsTest` encodes the report and
the tick exactly, decodes the policy and runs the rows. `restrictions_pause.json` also carries the setup hold's ceiling
(`setup_hold_max_ms`: `models::SETUP_HOLD_MAX_MS`, the launcher's `SETUP_HOLD_DURATION_MS`). The policy's `setup` is
pinned by `policy_json_keys_snapshot` and the launcher's `PolicyResponseCompatTest`.

What each app may use (design 127 step 2: Kamera, Posisjon, Mikrofon, Kontakter on the app's own page) is
`contracts/core/app_access.json`, byte-identical in `launcher/app/src/test/resources/`: `keys` (each key with the
Android permissions it covers, in display order), `policy` (`none` and `seeded`: the exact `app_permissions` -
`[{package_name, camera?, location?, microphone?, contacts?}]`, each `"deny"` - after the page's saves), `report` (the
exact `installed_apps[]` with each key's `requested`/`admin_denied`/`refused` beside the special accesses), `clean` (the
server's `AppAccess::of`) and `never` (Vibb One and Vibb music, either build). The server's
`app_permissions::tests::the_contract` saves through `POST /devices/{id}/apps/permissions` (`key`, `allow=on`) and
produces `seeded`, posts `report` and checks what is stored and what the page shows, runs `clean` and refuses `never`;
`intake_limits` keeps the report's keys; the launcher's `AppAccessContractTest` checks `AccessKey` against `keys`,
decodes both policies, encodes `report` exactly from `accessReports`/`appAccessReport` and runs `never`; both compare
the copies. The launcher's own rules (the grant plan, the report's readings, the refusals kept) stay its alone, in
`launcher/app/src/test/resources/app_access_rules.json` (`AppAccessRulesTest`). The policy's `app_permissions` is
pinned by `policy_json_keys_snapshot` and `PolicyResponseCompatTest`. `network` (Nett) joins in step 3.

Android's blocked-numbers list, its rows that hit the rules cleared while managed, is `contracts/core/blocked_numbers.json`, byte-identical in
`launcher/app/src/test/resources/`: `classes` (a cleared entry's class: `contact` - a contact's or an own number -,
`emergency` - never the number), `call_state` and `call_state_unreadable` (the exact `blocked_*` keys of a status report's `call_state`: readable,
the rows the rule clears left (a failed delete), `blocked_kept` (the kid's own blocks, never worded), the counts per class since the install, the last clearing, and `blocked_cleared_initial` - the first
sweep after the phone became managed, counted apart and never worded as a warning) and `lines` (the previous and this
report's counts to the security-log lines, one per class that rose). The server's `blocked_numbers::tests` runs the rows and
`tests::blocked_numbers` posts the reports (the `phone_blocked_cleared` lines, the Calls page's orange lines); the
launcher's `BlockedNumbersTest` encodes both reports exactly from `withBlocked` and checks its classes; both compare the
copies. The plan (`blockedNumbersPlan`: while managed, a row cleared only when its number, normalised, is a rules contact's,
an own number or an emergency number; the rest kept) stays the launcher's. No policy key.

The phone's clock as its decisions see it (time rules keep their own clock, `server/TrustedClock.kt`): the status
report's `time_state.clock_skew_ms`, `clock_trust` and `unanchored_ms` ride in `time_status_report.json` (above); the rows
are `contracts/core/clock_skew.json`, byte-identical in `launcher/app/src/test/resources/`: `round` (a raw offset and
the last reported step to the reported step: 0 within five minutes, five-minute steps, a minute's hysteresis), `clean`
(the server's keeping of the three keys), `pages` (`{skew, trust, unanchored, lang}` to the one orange line or none),
`fix_age` (a fix's `{received, captured_at, age_ms}` to the stored time and `phone_offset_ms`: receipt minus the age,
whatever the phone's clock says) and `phone_age` (the launcher's `fixAgeMs`: a fix's elapsed time and boot to its age). The
server's `tests::clock_skew` runs `clean`, `pages` and `fix_age` and posts reports (Device › Time's line, the Attention
row, the Devices list's badge, a fix stored from its age, a refused fix on Locate); the launcher's `ClockSkewContractTest`
runs `round` and `phone_age` and encodes a fix's `age_ms`; both compare the copies. The anchor, reboot and alarm rules stay
the launcher's alone, in `launcher/app/src/test/resources/clock_rules.json` (`TrustedClockTest`); `TrustedClockScanTest`
fails on a new wall-clock read where the phone decides. No policy key.

The hardware-bound phone key (design 129; server S1 `docs/notes/server/device-key.md`, launcher L1
`docs/notes/launcher/security.md`, "The hardware-bound phone key") is `contracts/core/device_key.json`, written by
`scripts/device-key-vectors.py` (a throwaway key from a fake phrase, public data only; `--check` in `server-ci.yml`'s
changes job) and byte-identical in `launcher/app/src/test/resources/`: the headers, `canonical` (the signed bytes of a
request), `signed` and `proof` (signatures by the throwaway key), `enroll_challenge`, `clock` (the signature clock and
the phone's offset), `window`, `edge`, `answers` (each `Vibb-Auth` reason to the phone's action), `enroll` and `register`
(the routes' bodies and answers), `policy` (the policy's `device_key`), `phone` (the registration flow's table: step 1's
answer and the phone's keys to adopt, keep, register, report a conflict, wait or a strike towards "lost"),
`phone_nudge` (the policy's `device_key` to asking step 1 or not) and `lost_verdict` (the strikes: three over an hour of
elapsed realtime make a key lost). Design 129b added: `enroll`'s bodies with `attempt` and `body_no_key` (`key_error`),
every E1 answer with the phone's action (`answers`; S1's in `answers_s1`), `wait_reasons`, `exception_reasons`,
`wait_pace`, `numbers` (an attempt to its hash and six digits), `enrol_wait` (the waiting enrolment's steps, the
launcher's `enrolWaitStep`), the `exempt` key state (`register.get_key_exempt`, `policy.exempt`), `register`'s
`address_history` and the `phone` rows' `exempt_since_last_try_ms` and `challenge`/`challenge_action`
(`keyChallengeStep`); the server's `tests::enrol_key` and `enrol_key::tests` produce and run them. The server's `tests::device_key` verifies the crypto rows and
produces the route rows through the real routes; the launcher's `DeviceKeyContractTest` builds `canonical` and `proof`
exactly (and verifies the signatures over what it built), derives the enrolment challenge, computes `clock`, maps every
answer, decodes the policy and the bodies, and runs `phone` through `keyStep`, `phone_nudge` through `keyNudge` and `lost_verdict`
through `lostVerdict`; both compare the copies. The attestation's own vectors (`server/testdata/attestation/`, Google's
test chains, Apache-2.0) stay the server's. A row added is the script's, then `--write`, then the launcher's copy. The policy's `device_key` is
pinned by `policy_json_keys_snapshot` and `PolicyResponseCompatTest`.

## CI and releases

- `.github/workflows/launcher.yml`, `server-ci.yml` and `music.yml` run on every push/PR, but their build job
  (`launcher-build`, `server-build`, `music-build`) only runs when its own directory or workflow changed
  (`launcher.yml` also on `.github/actions/build-tsnet/` and the music files its tests read: the bridge, the
  shared snapshots, the three `spotify_hosts.json`, `contracts/core/time_status_report.json`,
  `dns_upstream_status.json`, `power_report.json`, `zone_status_report.json`, `launcher_tag_codes.json`,
  `time_request.json`, `installed_apps_report.json`, the four `calendar_*.json`, `emergency_numbers.json`, `weather.json`, `leave_tailnet.json`, `server_url_vectors.json`, `restrictions_pause.json`, `server_tls_report.json`, `sms_contract.json`, `contact_request.json`, `app_logos.json`, `lock_now.json`, `mobile_network.json`, `status_heartbeat.json`, `airplane_mode.json`, `rule_pause.json`, `shortcuts.json`, `login_windows.json`, `notification_channels.json`, `notification_listeners.json`, `unified_push.json`, `setup_steps.json`, `install_failure.json`, `app_access.json`, `blocked_numbers.json`, `clock_skew.json` and `device_key.json`, `server/testdata/map_links/contract.json` and
  `server/testdata/wifi_lookup/contract.json`, `music/gradle/` and `music.yml`, and the server's and music's `storytel_token_answers.json`, and `server/testdata/doh_hosts.txt`, and `scripts/yr-symbols.py` with its vendored `yr-symbols-11.svg` and `scripts/fake-portal.py` (the changes job runs both `--selftest`s always); `music.yml` also on the shared
  `server/testdata/music_*.json` and `spotify_hosts.json`, the launcher's copy of the bridge AIDL,
  `bridge_contract.json` and `spotify_hosts.json`, `server/testdata/spotify/`, `scripts/fake-sonos.py`,
  `scripts/fake-storytel.py`, `scripts/fake-spotify.py`, `scripts/fake-audio.py`, `scripts/music-splash.py`
  (with the launcher's `splash_vibb_breathe.xml` it draws from), `scripts/music-icon.py` (with the launcher's
  `ic_launcher_foreground.xml` it reads the rings from) and `scripts/sonos-ws-probe.py` (its capture
  scrubber), whose `--selftest`s it runs after the build, the server's and the launcher's `storytel_token_answers.json` and `server/testdata/storytel/`;
  `server-ci.yml`'s build job also on the apps' `spotify_hosts.json` and `storytel_token_answers.json` copies and
  the launcher's `time_status_report.json`,
  `dns_upstream_status.json`, `power_report.json`, `zone_status_report.json`, `launcher_tag_codes.json`,
  `time_request.json`, `installed_apps_report.json`, the four `calendar_*.json`, `emergency_numbers.json`, `weather.json`, `leave_tailnet.json`, `server_url_vectors.json`, `restrictions_pause.json`, `server_tls_report.json`, `sms_contract.json`, `contact_request.json`, `app_logos.json`, `lock_now.json`, `mobile_network.json`, `status_heartbeat.json`, `airplane_mode.json`, `rule_pause.json`, `shortcuts.json`, `login_windows.json`, `notification_channels.json`, `notification_listeners.json`, `unified_push.json`, `setup_steps.json`, `install_failure.json`, `app_access.json`, `blocked_numbers.json`, `clock_skew.json` and `device_key.json`, the launcher's `map_links/` and `wifi_lookup/` folders, and the launcher's `assets/dns/doh_hosts.txt` and `ui/home/HomeModel.kt` (the avatar palette the PWA mirrors), and `scripts/wifi-lookup-fixtures.py` (the changes job runs its `--selftest` always), and
  `scripts/mmsc-hosts.py` with its `scripts/mmsc-hosts-sample.xml` (the changes job runs its `--selftest` always, and `server/deploy/update-image.sh --selftest`, after a bounded `apt-get install minisign`: the signed image statement's checks with a throwaway key, the image update's release pick and version rules, DEPLOY.md "Automatic, verified updates", with `deploy/handy-update.service`/`.timer`);
  `server-ci.yml`'s
  `server-image` job only when `server/Dockerfile`, `server/.dockerignore`, `server/deploy/image-smoke.sh`, `server/deploy/distrusted-roots.txt` or
  the workflow changed). The server's Rust (`toolchain: 1.99.0`) is pinned in `server-ci.yml` and
  `server-release.yml` (security review L9) - move it on deliberately, both files at once. Both build the static musl
  binaries natively, each architecture on its own runner (`aarch64-unknown-linux-musl` on `ubuntu-24.04-arm`,
  `x86_64-unknown-linux-musl` on `ubuntu-24.04`; plain `cargo` with `musl-tools`, no `cross`, no QEMU; since 2026-10-25).
  Require the always-running gate jobs `launcher-ci`, `server-ci` and `music-ci` in
  branch protection, not the build jobs.
- `launcher-vX.Y.Z` (or `launcher-vX.Y.Z-rc.N`, a prerelease) on a `main` commit -> signed APK
  release (`kids-launcher-mdm.apk`), versionCode `X*10_000_000 + Y*10_000 + Z*10 + N` (N = the RC number
  1..8, 9 for the release: every build its own code, `scripts/release-version.sh`; X <= 199, Y and Z <= 999).
  The first tag under it is `launcher-v0.36.0-rc.4` (360004), and the script refuses a lower one; the launcher and
  the server read any tag by it (`launcher_tag_codes.json`). Only
  stable launcher releases become GitHub's "latest", so
  `releases/latest/download/kids-launcher-mdm.apk` is the provisioning QR's URL.
- `music-vX.Y.Z` (or `-rc.N`) on a `main` commit -> `vibb-music.apk` + `.sha256`, signed with the
  launcher's key in the `release` environment (its deployment rule must allow `music-v*` tags too; the same
  `RELEASE_CERT_SHA256` check), versionCode as the launcher's (the same script; the first tag under it is
  `music-v0.5.0-rc.4`, 50004; the server reads the music app's version from its code by it), never "latest". Deploy a server with the music
  catalog row (0.21.0+) before the first stable `music-v*` (design 21 QA #11).
- `server-vX.Y.Z` (must equal `server/Cargo.toml`'s version) on a `main` commit -> fmt + tests,
  then the aarch64 and x86_64 tarballs and their `.sha256`, never "latest". Since 0.24.0 the tarballs and `update.sh` are signed
  with minisign, since 0.92.0 also `update-image.sh` and the image statement `handy-server-image.txt` (the index's
  digest and the tag; `update-image.sh` checks it before an image runs), all before any image is tagged
  (`server-release-sign`, in the `release-server` environment - the server's alone, tag rule `server-v*`; `release`
  stays the launcher's and music's -, its `SERVER_MINISIGN_KEY` secret; the public key pinned in
  `server/deploy/*.sh`; server/DEPLOY.md, "Release signing") - no secret, no release. The server's update check and `install.sh`/`update.sh` find it by tag
  (the scripts take the tarball for `uname -m`; the update check reads only the tag).
  Since 0.23.0 (design 22) the same run builds the multi-arch image `ghcr.io/<owner>/handy-server`,
  each architecture natively on its own runner (`ubuntu-24.04`, `ubuntu-24.04-arm`; no QEMU) from that architecture's
  tarball, pushes it by digest (untagged),
  smoke-tests it there; a keyless job plans the index, the sign job signs its digest, and the merge job checks the
  signed statement and the digest before it tags `X.Y.Z`, and `latest` when it is
  the highest `server-v*` tag; the GitHub release is published last. The GHCR package must be made public once by
  the owner (server/DEPLOY.md, "Docker"). `workflow_dispatch` is always a dry run, on a tag ref too.

## Licensing

Everything is GPL-3.0 (or later) - see `LICENSE` and `NOTICE.md`. All new code is GPL-3.0; any
third-party code or asset brought in must be GPL-compatible (the launcher's app-list code stays MIT,
`launcher/LICENSE-MIT-UPSTREAM`; Nunito is OFL 1.1; the app icons from Material Symbols are Apache-2.0,
`scripts/material-symbols.sh`). New GPL code doesn't go into the MIT directories (`ui/list/`, `apps/`).
