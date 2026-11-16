# Code QA of design 97 (Mobilnett revision 4: "I utlandet", the roaming notice, the kid's Mobildata switch)

Branch `mobilnett-r4`, the build commits 372b99be..e7084c67 (steps 1-6; 9707dba4 and earlier are the design). Read
against `docs/design/97-mobilnett-r4.md` (revision 3, the user's decisions D1-D9 in §11), design 95 revision 3, the root
`CLAUDE.md`'s contract rules and `docs/notes/launcher/dns-and-vpn.md`. No Gradle and no emulator. The server was
compile-checked with its tests (`cargo check --tests`, clean, no warnings), but not run: every test finding below comes
from reading the code. Note: `git diff 160da3b4..HEAD` also shows design 96's files as deleted. The branch forked
before that merge (its base is 9228b85c), so they are not part of this round. No file is touched on both sides, so the
merge should be clean.

**Result: 2 High, 2 Medium, 8 Low.** The design's decisions are built as decided:
- **D1 and D2.** Migration 0145 sets every existing row explicitly. The D2 subquery is sound: `json_extract(...,
  '$.enabled')` reads JSON `true` as 1, and null or a missing report falls to `off`. A new phone gets the column's
  `vibb` through `insert_device_with_policy`. A missing policy row is served `DevicePolicy::default()`, whose `""` reads
  `off`.
- **D3.** Bit 32 is only in `DEFAULT_QUICK_CONTROLS`, which only the create path uses.
- **D6.** `roaming_edge` follows §6 row by row, and `dedup_key` is `<place>:<instant>`.
- **D8 and D9.** The country is always named. Nothing writes roaming on (`applyDataRoaming` only sets or relocks the
  restriction).
- **The contract.** The two copies of `mobile_network.json` are byte-identical. Every launcher row runs in
  `MobileNetworkTest`, and every server row (policy, report, clean) runs in `the_contract`. `policy_json_keys_snapshot`
  and `PolicyResponseCompatTest` pin `abroad`.
- **The PWA and kid-facing text.** Each group saves to its own anchor. There is no bell. Every new key is in both
  catalogs (the key sets are equal). No kid-facing text says "Spotify".

The two Highs are server tests that will fail in CI. They were never run, and both are one-line fixes.

## High

**H1. The seeded family no longer covers every kind: two tests fail.** `server/src/tests/seeded_family.rs:377` asserts
`kinds.len() == parent_events::Kind::ALL.len()` ("every kind"). `Kind::ALL` grew to 21 with `RoamingStarted`
(`parent_events.rs:109-131`), but the seed's event list still ends at `mmsc_host` (line 374). `seed()` panics, so both
of its callers fail:
- `tests::i18n_pages::nb_pages_have_no_english`;
- `tests::policy_etag::a_full_policy_has_a_stable_tag_and_a_list_matches`.

Fix: add `phone.roaming_started("se", ago(123))` (or similar) to the seed. That also lets the nb sweep check the
notice's words in the inbox.

**H2. The switch count is stale.** `parent_events.rs:4478-4481` (`two_kinds_share_the_zones_switch`) asserts
`switches.len() == 12` "every switch once". `push_roaming` makes it 13 (counted from `Kind::switch()`, lines 192-225),
so the test fails. Fix: 13.

## Medium

**M1. CI's `-PwarningsAsErrors=true` will probably fail on `kidRowNow`.** In `MobileNetworkRuntime.kt:81-90`, the
function begins `val shown = policy != null && ...; if (!shown) return KidMobileRow.HIDDEN`. Kotlin 2.1 (K2, with no
`languageVersion` override in `launcher/app/build.gradle.kts`) carries smart casts through local boolean variables, so
`policy` is non-null after that line. That makes `policy?.appsMobile` (line 88) and `policy?.abroad, policy?.dataRoaming`
(line 90) unnecessary safe calls. Each is a warning, and `launcher.yml:129` builds with `allWarningsAsErrors`. A local
`assembleDebug` without the property, as the emulator step would have used, does not show it. Fix: take `policy` as
non-null after the check (`val p = policy ?: return HIDDEN`), or drop the `?.`. Run the CI command once before
merging.

**M2. A foreign network that Android doesn't flag as roaming reads `home`: no abroad sink and no notice, under Ingen
data too.** The cause is `whereOf` at `MobileNetwork.kt:87`, `if (!roaming) return Where.HOME`. It runs before the
country check, and the contract pins the row (`roaming: false, network: 24001, net_cc: se, sim_cc: no -> home`).
Android clears the roaming flag by carrier config for listed partner networks (`non_roaming_operator_string_array`,
and `gsm_roaming_networks` the other way). On such a network:
- Ingen data's lock does not apply, since Android sees no roaming;
- Bare Vibb's sink does not arm;
- the server tells nothing.

This is the same class as Q97-H1 (the 901 network): the user's rule "Gjelder utenfor landet SIM-kortet hører til" is
about the country, not the flag. When both country codes are known and differ, `where` should be `abroad` whatever
the flag says. National roaming keeps the same country, and multi-MCC countries compare by ISO, so neither case is
affected. Fix: put the ISO comparison before `!roaming`, flip the contract row, and add a `shapes` row (Ingen data, a
non-flagged foreign network -> sink `abroad`). It is a design-level gap and, I'd say, worth the user's one line, but
the fix is small.

## Low

**L1. A held sink names `abroad` at home.** `reportedSink` (`MobileNetwork.kt:234-243`) names the first armed
reason on a doubt (no network, or `unsure`). Bare Vibb abroad is the default (D1), so `abroadArmed` is true on nearly
every new phone, also at home. Take a kid whose Off has sunk cellular in Norway. A tunnel or an out-of-service moment
reports `sink: "abroad"`, and the summary says "Mobildata: bare Vibb i utlandet" until the next network is seen. The
order follows §4.2 (Q97-M2), so this is a wording slip, not a leak. A cheap fix: on a doubt, keep the persisted reason
(store it next to `vpn_shape`).

**L2. A rollback reads a weaker first record than rc.1 would have written.** §4.2 says rc.1's `vpn_inputs` record is
"written as before". rc.1 derived `roamingLocked` from the served `data_roaming`, and the server serves `"off"` for
Bare Vibb. r4 writes `roamingLocked = choice == OFF` instead (`AppEnforcer.kt:364`), so under Bare Vibb the first
record says `0`.

The case is a rollback to rc.1 while abroad and sunk. rc.1's cold start checks `parentSink || roamingLocked` to keep
the sink, finds both false, and drops lockdown. Roaming data then passes until rc.1's first `apply()` locks roaming. A
rollback is rare and the window is short. If it matters, the first record could carry `roamingLocked ||
abroadArmed`, which is rc.1's own reading of the derived value; r4 would then take `roamingLocked` from the second
record.

**L3. A fallback apply drops the kid's Off.** `LastEnforcedPlan` (`PolicyGate.kt:142-192`) has no
`quickControlsMask`, so `toPolicy()` gives mask 0. On the corrupt-cache fallback, `AppEnforcer.kt:366`
(`MobileNetworkRuntime.kidOff(policy?.quickControlsMask)`) then reads `false`: the kid's own Off stops counting, and
the row is hidden too. This is rare, and the result is less restriction than the kid chose.

**L4. The kid's tap and `apply()` race on `vpn_inputs_r4`.** `setKidOff` (`MobileNetworkRuntime.kt:100-108`, main
thread) writes the pref and then the second record. An `apply()` running on the worker reads `kidMobileOff`, computes
`VpnInputs` and later calls `storeInputs` (line 77). If the tap lands between that read and that write, the old
`kidOff` overwrites the tap's record, and the service reshapes without it. The `requestSync` the tap sends runs another
`apply()`, so it heals within one sync. A `@Synchronized` around the read-modify-write in both places would close it.

**L5. On an older launcher under Bare Vibb, the switch line asks for a step that won't hold.** On a phone without
`mobile_network_v2`, the derived `data_roaming: "off"` keeps the old launcher's roaming lock, so its report reads
`enabled: false`. `switch_line` (`mobile_network.rs:421-452`) then shows "Androids roamingbryter: av – slå den på én
gang under Åpne for oppsett …", but the old launcher's lock writes 0 again after the pause. Next to it the
`vibb_update` hint says "Ingen data i utlandet til Vibb One er oppdatert". Suggestion: no switch line, or the update
line instead, while `!network_capable && abroad == "vibb"`.

**L6. The page says the kid's Off twice.** With `sink: "kid"` and `kid: "off"`, `card()` pushes both
`only-vibb-kid` ("Mobildata: bare Vibb – Emma har slått av") and `kid-off` ("Emma har slått mobildata av") into
`data_lines` (`mobile_network.rs:490-495`). §5's mockup has only the state line under the kid's switch on the page,
and the "bare Vibb" form on the summary.

**L7. `bestNetOf` makes several telephony binder calls on the main thread for every capabilities update.**
`MobileNetworkRuntime.kt:147` calls `whereNow`: the airplane setting, the operator, the SIM operator, two ISOs, the
roaming flag and `getServiceState()`. The watcher (`KidVpnService.kt:336`) calls it on every `onCapabilitiesChanged`,
and cellular sends those often, for bandwidth estimates and signal changes. It is not wrong, but it is heavier than
before. It could read telephony only when the cellular network or its roaming capability changed.

**L8. Small test and doc gaps.**
- The launcher test `this launcher claims the capabilities` (`MobileNetworkTest.kt:229-237`) compares
  `MOBILE_NETWORK_CAPABILITY` with the contract, but doesn't assert it is in `STATUS_CAPABILITIES`. It is there today.
- A cellular network that is `unsure` (where unknown, e.g. a transiently empty operator) with the roaming capability,
  under Bare Vibb with no sink persisted, gets the filter, not the sink. Only a later capabilities change re-decides.
  This is the same family as §8's "national to foreign without a callback", and could be closed by treating
  `unsure && roaming` as abroad while `abroadArmed`.
- The root `CLAUDE.md:279` still says the launcher runs the rows through `dataRoamingLocked`. It is `roamingLocked`
  now, beside `abroadChoice`, `whereOf` and `kidMobileRow`.

## Checked and fine

- **Fail-closed paths.**
  - Cold start after the upgrade from rc.1: an absent second record reads both false, and rc.1's `roamingLocked`
    still keeps a persisted sink.
  - Flaps: `net == null || net.unsure` with SINK persisted and `anyArmed` keeps the sink in `applyShape`, and through
    it in `serviceShape`.
  - The suspended-network fix: `unsure` comes from a missing `NOT_SUSPENDED`, and `abroad` is still read on a
    suspended network, so an abroad network sinks even when suspended.
  - An older server: `"phone"` or nothing reads `LEGACY`, any other value `OFF`.
  - An older launcher: the derived value is never more than the parent chose.
  - The pause and the override lift the abroad sink and the roaming lock but not the kid's Off (D4). Under a lift the
    kid's row is clickable, so the kid can undo their own Off.
- **The server.**
  - `roaming_edge`: every §6 rule, including `plain`, `ship` mid-trip, `unknown`, home clearing the place and the guard
    (`< 1 h`, entries pruned on write).
  - The compare-and-set binds the read values with `IS ?`, so NULL compares.
  - The raise only on `changed == 1`, in one transaction.
  - The intake is gated on the report's `mobile_network_v2`. The notice is pruned with `location_retention_days`.
  - The unit rows have no wall-clock dependence (fixed instants).
- **The migration.** SQLite's `ADD COLUMN ... NOT NULL DEFAULT 'vibb' CHECK (...)` is valid, and existing rows pass
  the CHECK. The migration test re-runs exactly the file's `UPDATE`: no `;` sits inside a comment, so the split is safe.
- **The PWA.**
  - The kid switch posts `group=kid-mobile` to `#mobile-data` and writes bit 32 alone (`CASE WHEN ? THEN col | ? ELSE
    col & ~? END`).
  - The Home Screen form keeps bit 32 on `quick` and on the whole form (`(mask & 32) | ?`, and NULL keeps the column).
  - The radios need only `data_roaming_v1`, and the kid's switch waits for v2.
- **The launcher.**
  - D7's reset sits in `storeAcceptedPolicy`.
  - A mask of 32 alone is a card.
  - The row's strings are in nb and en and calm.

## Resolution

Every finding is fixed; none is left.

- **H1.** The seeded family raises `phone.roaming_started("se", ago(123))`, so it covers all 21 kinds again and the nb
  sweep reads the notice ("Anna er på roaming i Sverige") in the inbox.
- **H2.** `two_kinds_share_the_zones_switch` counts 13 switches. The other enumerations were checked: the words and
  targets test and the wording sweep in `parent_events.rs` already list `RoamingStarted`, the Notify Me About settings
  read `Kind::switches()` (`push_roaming` has its words and its column in migration 0145), and the per-switch tests
  (`zones`, `time_request`, `app_updates`, `contact_requests`) filter the list rather than count it.
- **M1.** `kidRowNow` takes the policy as non-null once the bit is checked (`val p = policy?.takeIf { … } ?: return
  HIDDEN`), so it has no safe calls left. The launcher was built with `-PwarningsAsErrors=true`.
- **M2.** Decided by the orchestrator: when both countries are known they decide, whatever Android's roaming flag says.
  Another country than the SIM's is `abroad`, the same country is `home` (national roaming), a 901 network stays
  `abroad`, and an unknown country stays with the flag and then the MCC. The contract row `24001`, `se` vs `no`, not
  roaming, now reads `abroad` in both copies. A new row pins the fallback: the same network with the SIM's country
  unknown and no flag reads `home`. The existing `shapes` row (Ingen data, not roaming, `abroad` -> sink `abroad`)
  already covers the sink, so no new `shapes` row was needed.
  - The report names the country and network while abroad too (`roamingPlace`'s first argument is `roaming || where ==
    abroad`), so the notice names the country (D8).
  - The server words an unflagged `abroad` report as roaming on the page and in the header (`abroad_unflagged` in
    `roaming_words`, unit-tested).
  - Design §4.1 has the sentence, and §8 has the risk: a SIM from a foreign operator used at home reads abroad. This
    fails closed, and the parent can choose Som hjemme.
- **L1.** Fixed. Whenever a sink is decided with a placed cellular network, both `apply()` and the service hold its
  reason in `mdm.vpn_sink_reason` (`sinkReasonToHold`, written only on a change). On a doubt, `reportedSink(…, held)`
  names that reason while it is still configured and otherwise falls back to the order. A kid's Off at home stays
  `kid` through a tunnel. Unit test: `a held sink keeps naming the reason it was decided for`.
- **L2.** Fixed as suggested. The first record's third field is rc.1's own reading, `roamingLocked || abroadArmed`. The
  second record became `abroadArmed,kidOff,roamingLocked`, so r4 reads the lock itself from it. An absent second record
  still reads rc.1's rule. No r4 record exists outside the emulator, so the new shape needs no migration. Design §4.2
  and the unit test (`"1,0,1"`, `"1,1,0"`) are updated.
- **L3.** Fixed. `LastEnforcedPlan` carries `quickControlsMask` (an older record reads 0), so a fallback apply keeps the
  kid's Off. The row's visibility still follows the cached policy, which is the unusable one in that case. That part
  is UI only and was left alone.
- **L4.** Fixed. `storeInputs` and the kid's tap share one lock. `storeInputs` reads the kid's Off again under it, from
  the applied policy's mask, so a tap between `apply()`'s read and its write is never overwritten. The service
  reshapes from the stored record as before.
- **L5.** Fixed. With Bare Vibb on a launcher without `mobile_network_v2`, no switch line is shown outside a pause. The
  radio's "Ingen data i utlandet til Vibb One er oppdatert" hint stays. Page test added.
- **L6.** Fixed. The page shows only `kid-off` when the kid's Off is the sink's reason. The summary keeps the "bare
  Vibb" form, and the page test checks both.
- **L7.** Fixed with a throttle rather than a skip. The watcher reads telephony for a new network or when a capability
  that `BestNet` is made of changes (transports, `NOT_ROAMING`, `NOT_SUSPENDED`), and otherwise at most every 10 s
  (`readsTelephony`). A full skip would have given up the incidental catch of a country change without a capability
  change (§8), so the 10 s re-read keeps it. `wantedShape` also stops reading the live network when the watcher has
  one.
- **L8.**
  - `MOBILE_NETWORK_CAPABILITY in STATUS_CAPABILITIES` is asserted.
  - An `unsure` roaming cellular network counts as abroad while the abroad sink is armed (`sinkReason`, unit-tested;
    design §4.2).
  - The root `CLAUDE.md` names the functions the launcher's test runs (`abroadChoice`, `roamingLocked`, `whereOf`,
    `applyShape`, `reportedSink`, `kidMobileRow`, …) and the policy's four keys.
