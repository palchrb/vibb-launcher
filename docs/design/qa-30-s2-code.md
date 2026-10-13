# QA 30 step 2 (code) - the two mobile-data switches and the storage limit in the launcher

Reviewer: code QA, 2026-10-13. Branch `design-30-s2` (`97b197f1..777e4434`, 1 commit). Reviewed against
`docs/design/30-music-streaming-data.md` (§1.1, §1.3, §4, §6 launcher, §7 step 2, §9, and the step 1 and step 2 status
sections) and its design QA `qa-30-design.md`. Also read against the code the step builds on:
- launcher: `music/MusicPlan.kt` (`musicPolicy`, `restrictionsFor`, `artToFetch`), `music/MusicSync.kt`
  (`writeRestrictions`, `sameBundle`, `downloadMobileData`, `MusicCourier.artFetchable`), `music/MusicFileDownloads.kt`
  (`gate`), `music/MusicBridgeService.kt` (`wantArtChanged`, `answerStorytel`), `music/VolumeCap.kt` (`refresh`);
- music: `bridge/BridgeConfig.kt` (`bridgeConfig`), `BridgeTest`;
- server (step 1): `music.rs` `MusicPolicy` and `storage_limit`.

QA left no product change behind: the mutation edits below were reverted before this commit (`git status` clean after
each run).

**Result: mergeable as is. No High and no Medium findings; 2 Low.** The parse follows §1.3 exactly:
- `mobile_data` is still required;
- each new switch is a JSON Boolean, falls back to `mobile_data` when absent, and refuses the object otherwise;
- the limit is 2 when absent, none when `null`, an integer 1..1000, and refuses the object otherwise.

The managed config writes `mobile_data` as both switches, then the three new keys, all storable types. Both contract
files are byte-identical and only appended to. Own files and `later` pictures follow the downloads switch; `now`
pictures are unchanged. Neither Low finding is reachable with today's server:
- L1: a refused music object costs more than the music settings. The phone's own volume cap is lifted and the library
  stops updating. This is old behaviour (design 26a), but the step adds three more fields that can trigger it.
- L2: the two glue pins are `contains` checks. A mutation that forces own files to Wi-Fi only stays green.

## What ran (under the shared build lock)

- Launcher `./gradlew testDebugUnitTest` at `777e4434`: 1078 tests, 0 failures.
- Music `./gradlew testDebugUnitTest -PwarningsAsErrors=true`: 614 tests, 0 failures. (`assembleRelease` was not
  re-run here. The implementer's status reports it green with the stub tsnet, and the step changes no build file.)
- **Launcher mutations**, one at a time, with `--tests` on `*MusicPlanTest`, `*MusicFileDownloadsTest` and
  `*PolicyResponseCompatTest`. Fresh results each run. 15 of 16 went red:

| # | Mutation | Red |
|---|---|---|
| M1 | `mobileSwitch`: absent -> `false` (no fallback) | `own files follow the downloads switch`, `the managed config carries both switches, ...`, both `the policy's music object - ...` |
| M2 | a switch accepts a JSON string (`isString` guard dropped) | `the policy's music object - two switches, ...` |
| M3 | derived `mobileData` = OR | `the managed config carries both switches, ...`, `the policy's music object - two switches, ...` |
| M4 | `mobile_data` restriction = the streaming switch | `the managed config carries both switches, ...` |
| M5 | `storage_limit_gb` not written | `the managed config carries both switches, ...`, `... carries the keys the music app reads`, `the bridge's names match the shared contract` |
| M6 | absent limit -> no limit | 4 tests, `the storage limit - absent is 2 GB, ...` among them |
| M7 | limit range `0..1000` | `the storage limit - absent is 2 GB, null is none, junk refuses` |
| M8 | limit accepts a JSON string | the same |
| M9 | `storageLimitRestriction(null)` = 2 | `the managed config carries both switches, ...` |
| M10 | `MusicSync.downloadMobileData()` reads the streaming switch | `own files follow the downloads switch` (the pin) |
| M11 | `artFetchable` passes the streaming switch | `later pictures follow the downloads switch` (the pin) |
| M12 | `MusicFileDownloads.gate`: `!MusicSync.downloadMobileData() \|\| true` (Wi-Fi only, always) | **none: green** (L2) |
| M13 | `artToFetch`: `later` only when unmetered | `later pictures follow ...`, `art to fetch - now on any network, ...` |
| M14 | stream and download swapped in `musicPolicy`'s result | 4 tests, `PolicyResponseCompatTest` among them |
| M15 | key typo `"stream_mobile"` | `music never fails the policy ...`, `the policy's music object - two switches, ...` |
| M16 | `stream_mobile_data` restriction = the downloads switch | `the managed config carries both switches, ...` |

- **Music mutations** (`--tests *BridgeTest`, `-PwarningsAsErrors=true`): `RestrictionKeys.STORAGE_LIMIT_GB =
  "storage_limit"` and the music copy's `"storage_limit_gb": "boolean"`. Both red: `the bridge's names match the
  shared contract`.

## Checked and sound

- **`musicPolicy`** (`MusicPlan.kt:107-140`).
  - `mobile_data` is still required (`:114`), with today's lenient `booleanOrNull`. A test pins that its absence
    refuses the object.
  - `mobileSwitch` (`:137`):
    - absent -> `mobile_data`;
    - a non-string `JsonPrimitive` whose content is `true`/`false` -> that value;
    - everything else -> null. `JsonNull` is a `JsonPrimitive` whose content is `"null"`, so `booleanOrNull` gives
      null; objects and arrays fall to `else`.
  - The fallback is per key. When only one key is missing, that switch alone takes `mobile_data`. A test pins it.
    Only a server that is neither old nor new would send that, and it is the sensible reading.
  - Limit (`:122-127`): `JsonNull` is matched before `JsonPrimitive`, so `null` = no limit. `intOrNull` on a
    non-string primitive rejects `2.5`, `true`, `1e3` and anything above `Int`. The test covers 10^10.
  - **The range against the server.** Step 1's server sends only 1, 2, 5, 10 or `null`. `storage_limit` maps any other
    stored value to `Some(2)`, and the field is a plain `Option<i64>`, serialized as `null`, never skipped. So nothing
    the server sends today can be refused. 1..1000 leaves room for any whole-GB choice a later server adds. A sub-GB
    choice would need another unit, so another key, so it doesn't belong in this range anyway.
- **What a refusal does on the phone.** `musicPolicy` returns null, and:
  - `writeRestrictions` skips the write (`MusicSync.kt:110`): the music app keeps its last managed config;
  - no courier run (`:53`, `wantArtChanged`), so no library, listing, cover or art fetch;
  - `answerStorytel` answers nothing;
  - `downloadMobileData()` is false, so own files wait for Wi-Fi;
  - and `VolumeCap.refresh` lifts the phone's cap (L1).

  For the switches, strict is right. A switch the launcher can't read must not be guessed into "on". Refusing keeps
  the last config the music app was given, which is the direction that never spends new data.
- **`restrictionsFor`** (`:226-238`).
  - `mobile_data` is `policy.mobileData`, the derived `stream && download` (`:98`). The three new keys come after
    `volume_cap_pct`, in the contract's order. The order has no effect on a `Bundle`, and `sameBundle` compares key sets
    and values.
  - The first sync after the update rewrites the config once, because the key set grew, and nudges once. After that,
    a change of either switch or of the limit changes the bundle and nudges, as with every other key.
  - `storageLimitRestriction` turns `null` into 0. `musicPolicy` never produces 0 or a negative limit, so 0 means only
    "no limit".
  - Every value is a `Boolean` or an `Int`. The H1 test includes a `null` and a 1000 limit.
- **The shared contract.**
  - `cmp` of the two `bridge_contract.json` files: identical.
  - The diff appends three keys at the end of `restrictions` and touches nothing else. The AIDL is untouched.
  - The music app's three `RestrictionKeys` constants are in `BridgeTest`'s set. The set is compared with the
    contract's keys, and the contract with the launcher's written keys (`MusicPlanTest:440`). A typo on either side
    goes red (MM1, M5).
- **Launcher fetches.**
  - `MusicFileDownloads.gate` (`:195`) passes `!MusicSync.downloadMobileData()` as `wifiOnly`.
  - `artFetchable` (`MusicSync.kt:321`) passes `music.downloadMobileData && notRoaming`.
  - `artToFetch` keeps `now` on any network and `later` only unmetered or with the downloads switch. Only the
    parameter's name changed.
  - `downloadMobileData()` was `mobileData()`, and nothing else in the launcher read the old one (grep).
  - The launcher never streams, so the streaming switch has no reader here, and it needs none.
- **Compatibility.**
  - **Today's music app with this launcher.** `bridgeConfig` reads keys by name and ignores the rest
    (`BridgeConfig.kt:78-89`), and the app declares no restrictions schema. It reads `mobile_data` = both, which is
    §1.3's AND. With only streaming on, it doesn't stream off Wi-Fi, and the step 1 card's older-phone note says so.
  - **An old launcher with the new server.** Its `musicPolicy` reads only its four keys by name, so the new keys are
    ignored. `mobile_data` = both reaches its own files, `later` art and the managed config. Downloads on and streaming
    off reads as off there, the safe side.
  - **This launcher with an old server.** No new keys, so both switches are `mobile_data` and the limit is 2.
    `restrictionsFor` then writes the same Boolean four times and 2 GB. A test pins this end to end.
- **The tests.**
  - Every new behaviour test goes red under its mutation, except for M12.
  - `PolicyResponseCompatTest` now reads today's shape through `musicPolicy` and checks that a malformed new key
    still decodes the policy.
  - The old `MusicPlanTest` literals moved to the six-field constructor without losing a case.
- **The implementer's interpretations.**
  - **Strict Booleans: agreed.** A switch from a server is always a `serde` `bool`. A string `"true"` would be a bug
    or a forgery, and treating it as "on" could spend data. Leaving `mobile_data` lenient changes nothing for it.
  - **The strict limit: agreed, with L1's caveat.** It is §1.3 to the letter. The cost of a refusal is the shared
    refusal path, not the range.
  - **`mobileData` derived: agreed.** There is one place for the AND. It is a getter, so it is outside the data class's
    `equals`/`copy`, and no caller can build a `MusicPolicy` whose old key disagrees with its switches.
  - **No `streamMobileData()` reader: agreed.** The launcher fetches nothing that streams. A reader would be dead
    code, and a later reader of the wrong switch is what M10/M11's pins catch.
- **Docs.**
  - `launcher/CLAUDE.md`: the keys and the `musicPolicy` rules in `MusicSync`, the downloads switch in
    `MusicFileDownloads` and `syncArt`. `PolicyResponse.music`'s KDoc names the keys.
  - The spec's step 2 status matches the code and the test counts.
  - `music/CLAUDE.md:94` still names only `mobile_data`. That is correct until 3a, which reads the new keys.

## Low

1. **L1: a refused music object lifts the phone's volume cap and stops the library, not only the new settings**
   (`VolumeCap.kt:100`, `MusicSync.kt:53,110`, `MusicBridgeService.kt:160,176`). This is old behaviour, made reachable
   from three more fields.
   - **Scenario.** A later server sends a value this launcher refuses: `"storage_limit_gb": 0.5` for a new "500 MB"
     choice, `2000`, or a switch as `null`. `musicPolicy` returns null everywhere.
     - `VolumeCap.refresh` computes `capPctInForce(null, ...)` = no cap. The kid's `STREAM_MUSIC` is no longer clamped
       on the phone.
     - `writeRestrictions` keeps the old bundle, so the music app's `volume_cap_pct` (its Sonos clamp) stays. The
       phone's two caps then disagree.
     - The courier doesn't run either. New episodes, listings and the Storytel login stop until the launcher is
       updated.

     "Keep what is there" (qa-21-step3-code M4) holds for the managed config, but not for the cap.
   - **Why only Low.** Step 1's server can't send any of these values (above). Server and launcher ship from one repo,
     and a new value would come with a launcher that reads it.
   - **Fix** (a follow-up, outside step 2's spec):
     - `VolumeCap.refresh` keeps its last pct when `policy?.music` is present but `musicPolicy` refuses it. That is
       what M4 means. Only a policy without `music`, or an unmanaged phone, lifts the cap.
     - Optionally, the spec could read a limit that isn't a choice as the default 2 GB. The server's `storage_limit`
       and the music app's §1.3 rule already do that. The storage limit is the least important field, so it would no
       longer be able to take the rest of the object down with it.
     - Strict switches stay as they are.
2. **L2: the glue pins are `contains` checks, so a wrong gate can pass them** (`MusicFileDownloadsTest.kt:184-185`,
   `MusicPlanTest.kt:245-246`).
   - **Scenario (M12, run).** `MusicFileDownloads.gate` is edited to `!MusicSync.downloadMobileData() || true`. Every
     test stays green. On the phone, the parent's own files then never download off Wi-Fi with downloads on. The
     `artFetchable` pin has the same weakness: `... && caps?.notRoaming == true || true` still contains the pinned
     text.
   - **Fix.** Pin the whole argument in its place:
     - the gate: `downloadGate(AppDownloads.currentCaps(app), !MusicSync.downloadMobileData(), null,`;
     - `artFetchable`: `downloadMobileData = music.downloadMobileData && caps?.notRoaming == true,` with the trailing
       comma.

     Better, move the choice into a pure function the gate calls, for example `ownFileWifiOnly(music: MusicPolicy?)`,
     and test that function.
