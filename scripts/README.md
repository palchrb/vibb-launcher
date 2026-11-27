# scripts/

One entry per script (moved here from the root `CLAUDE.md` on 2026-11-27). `§N` is a section of
`docs/testing/emulator.md`. Which workflow runs which `--selftest` and which script changes start which build job:
`docs/notes/ci.md`.

## Dev loop and releases

- `dev-rebuild.sh` - pull, build + install the debug launcher, run the server.
- `release-version.sh` - a `launcher-v`/`music-v` tag -> versionName, versionCode, prerelease: both apps' release
  workflows use it; `--selftest`, run by both workflows' always-run changes job and before every tag build.
- `push-all.sh` - push the current branch.
- `smoke-test.sh` - adb/emulator-console smoke test of lock and calls, remote-capable; §5b.
- `emulator-roaming.sh` - an emulator with a Norwegian SIM put on another country's network, real roaming for
  design 97's abroad rules; `docs/testing/2026-11-19-roaming-emulator.md`.

## Fakes for the emulator

- `fake-sonos.py` - fake Sonos speakers for the music app on the emulator, answering from the music app's Sonos test
  fixtures; §6h.
- `fake-storytel.py` - a fake Storytel - the login, the asset call's 302, a CDN of silent books - answering from the
  music app's Storytel test fixtures; §6i.
- `fake-spotify.py` - a fake Spotify - the accounts service with PKCE, the Web API the server and the phone use, a
  stub Web Playback SDK playing a tone - answering from `server/testdata/spotify/` and the music app's Spotify test
  fixtures; design 26c §9, §6l.
- `fake-mmsc.py` - a fake MMS server for the emulator, design 77 step 3b: an m-notification-ind as hex for the debug
  launcher's `MmsDebugReceiver`, m-retrieve-confs with a small JPEG, an m-send-conf for every send; standard library
  only, `--selftest`; §6v "step 3b".
- `fake-audio.py` - a fake podcast host of silent episodes with Range; `--vary` serves another length per request,
  design 30's changed-source check; §6f; `--feed N` an RSS feed of N items with their own pictures the server judges
  `own`, `--hls K` item K as HLS, design 39's list pictures and order flip; §6q.
- `fake-portal.py` - a fake captive portal for design 102's Wi-Fi sign-in window: one server answering the portal's
  name and another site's, with modes - a 302 portal, meta refresh, script, a soft portal, a hotspot that 302s to a
  search page, a Google walled garden, a full-page iframe, a same-site redirector, a form whose POST 302s to another
  site, a reached site's POSTs that 302 and 307 to a third site; standard library, `--selftest`, run by
  `launcher.yml`'s always-run changes job; §6ap.
- `fake-homeserver.py` - a fake Matrix homeserver and Web Push sender for design 108's emulator gate: the gateway's
  discovery, Synapse-shaped `event_id_only` notifies, Web-Push-like bytes to `/up/` with TTL/Urgency/Topic,
  `--burst`; every request to the phone listener with the endpoint's host as `Host`; standard library, `--selftest`
  against a loopback recorder, run by `server-ci.yml`'s always-run changes job; §6ay.
- `sonos-ws-probe.py` - design 53's spike: does a Sonos speaker push playback events over its local WebSocket
  (read-only unless `--do`, `--poll` compares with UPnP); its capture scrubber's `--selftest` runs in `music.yml`.
- `sonos-play-test.py` - design 53's hands-off Sonos play test: plays a podcast the way vibb and the music app do
  while the WebSocket and a 1 s UPnP poll watch, and says how fast each saw each step.

## Generated assets and test vectors

- `pwa-startup-images.py` - the PWA's iPhone start-up PNGs and head.html's links from
  `server/testdata/pwa_startup_screens.json`; `--selftest`; `docs/notes/server/pwa.md`, "Start-up splash".
- `yr-symbols.py` - the launcher's weather symbols, design 75 §4.1: yr's darkmode SVGs at a pinned commit, checked by
  one SHA-256 over a manifest, converted to `res/drawable/weather_*.xml` with `WeatherSymbols.kt` and the names;
  `--selftest`, no network, also converts the vendored `scripts/yr-symbols-11.svg` to exactly the committed
  `weather_11.xml`; Python's standard library, run with `-I`; run by `launcher.yml`'s always-run changes job.
- `material-symbols.sh` - the parent's app icons and the music category icons (designs 14, 21): Material Symbols
  Rounded at one pinned commit, checked by SHA-256 (Apache-2.0); run after changing `contracts/core/app_icons.json` or
  `contracts/music/music_icons.json`.
- `music-splash.py` - the music app's launch splash drawables: the Vibb mark read from the launcher's Home splash,
  "vibb music" from Nunito's outlines; `--selftest`.
- `music-icon.py` - the music app's icon and notification small icon, the user's drawing `vibb-music-e2`: the Vibb
  mark's outer three rings read from the launcher's `ic_launcher_foreground.xml`, each with its own scale and stroke,
  and a round-cornered play triangle, its geometry from the drawing's SVG; `--selftest` checks the outer ring inside
  the safe zone.
- `up-vapid-vectors.py` - design 108b's VAPID vectors `server/testdata/unified_push_vapid.json` and the two public
  keys of the contract's `body_vapid` rows, from a throwaway key derived from a fake phrase; pure Python, public data
  only.
- `webauthn-rs256-vector.py` - the server's RS256 passkey vector `server/testdata/webauthn/rs256.json`, made once with
  a throwaway key - public data only; design 74.
- `emoji-list.py` - the launcher's `assets/sms/emoji-16.0.txt` from Unicode's `emoji-test.txt` 16.0, its SHA-256
  pinned; design 81.
- `wifi-lookup-fixtures.py` - design 125 step 2: the server's `server/testdata/wifi_lookup/server.json` - the golden
  request for Apple's Wi-Fi location service, fake Apple answers with what the server must read from each, the
  failure histories behind the orange line - from its own protobuf encoder; documentation MACs, made-up coordinates,
  never a real capture; standard library, `--write`, `--selftest` run by `server-ci.yml`'s always-run changes job.
- `mmsc-hosts.py` - the server's `server/testdata/mmsc_hosts.txt`, design 83 §3: each operator's MMS server hosts
  from AOSP's `apns-full-conf.xml` at a pinned commit and SHA-256, in two classes - unasked and hints; its `UNASKED`
  stays `"none"` until design 77's step 0; `--selftest`, no network, converts `scripts/mmsc-hosts-sample.xml` and
  checks the committed list's lines against the SHA-256 the script holds; run by `server-ci.yml`'s always-run changes
  job.
- `grapheneos-boot-keys.py` - design 129 §9: the server's `server/src/attestation/grapheneos_boot_keys.json` from
  GrapheneOS's signed `attestation.json`, its OpenSSH signature checked with `ssh-keygen -Y verify` against the signer
  the script pins, an older `timestamp` refused; `--selftest`, no network, a vendored sample signed by a throwaway key
  in `scripts/grapheneos-boot-keys-sample/`; run by `server-ci.yml`'s always-run changes job.
- `device-key-vectors.py` - design 129 §7.1: the crypto rows of `contracts/core/device_key.json` from a throwaway
  P-256 key derived from a fake phrase - public data only, pure Python; `--check` run by `server-ci.yml`'s always-run
  changes job.
- `emulator-attestation-vectors.py` - design 129b §7: the `emulator_keys` profile's chains and rows in
  `server/testdata/attestation/emulator/`, from throwaway keys and made-up values in the emulator gate's shape, never
  a capture; pure Python; `--check` run by `server-ci.yml`'s always-run changes job (beside them, the gate's real
  chains from `emulator-attestation-captures/`, copied as they are).
