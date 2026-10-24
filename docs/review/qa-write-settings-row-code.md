# Code QA: write-settings-row ("Modify system settings" and the fingerprint without adb, enrolment before Home's first start)

Branch `write-settings-row` (f14f2208; 81f74186, de57f1e0, 90996a11, f14f2208) against main `ed588e91`. Reviewer's
branch `qa-write-settings-row` = `write-settings-row` + this file. Design: PLAN "Fix backlog" ("Modify system
settings" without adb; enrolment before Home's first start), design 59d §8.4 and §8.17, `docs/setup/android-settings.md`.

## Setup

- Worktree on f14f2208. I read `git diff ed588e91..write-settings-row` in full. I also read the code around it:
  `SettingsActivity`'s gate, the whole `SettingsFragmentLauncher` (the pause switch, the battery row, both enrolment
  paths), `computeEnforcementPlan` and its `overrideActive` callers in `AppEnforcer`/`MdmSyncWorker`,
  `resetPreferences`/`migratePreferencesToNewVersion` and the legacy migrations, `applyProvisioningExtras`,
  `HomeActivity.startWork`, `AndroidLock.configure`/`owned`/`report`, `KeyguardCover`'s features, and the server's
  `view_about`, `clock_format_note`, the `DeviceStatus` readers (`SELECT *`) and the status INSERT.
- Launcher, full suite under the lock (`flock ~/build.lock ./gradlew --no-daemon --max-workers=2
  -PwarningsAsErrors=true assembleDebug testDebugUnitTest`): **BUILD SUCCESSFUL in 2m 18s, 1647 tests, 0 failures,
  0 errors, 0 skipped** (`WriteSettingsAccessTest` 4, `FirstLaunchResetTest` 2, `FingerprintEnrolTest` 4). The only
  warning is kapt's `RELEASE_11` line.
- Server (`CARGO_TARGET_DIR=~/.cache/handy-target/qa-write-settings-row CARGO_BUILD_JOBS=2 CARGO_PROFILE_DEV_DEBUG=0
  flock ~/build.lock ...`): `cargo test`: **test result: ok. 1210 passed; 0 failed; 0 ignored; 0 measured; 0 filtered
  out; finished in 441.13s**. `cargo fmt --check`: clean. `cargo clippy --all-targets`: 13 warnings, the known set
  (`models.rs` dead fields, `dns_engine.rs` doc lists, `devices.rs:1303`, `storytel.rs:144`), none new.
- Mutations: applied by an exact-replace script in four batches, at most one mutation per test method in a batch. Each
  run covered `FirstLaunchResetTest`, `WriteSettingsAccessTest` and `FingerprintEnrolTest`. The sources were put back
  with `git checkout -- launcher/app/src/main` after each batch, and `git status` was empty before this file.
- No emulator (as briefed).

## What was checked and holds

- **No adb step is left for the two features.** "Modify system settings" and the fingerprint both have an on-phone
  path now: a parent-PIN-gated Settings row that opens Android's own page during the pause. adb is only the
  documented shortcut (`android-settings.md`, the manifest comment, `ClockFormatSetting`'s KDoc, the server's
  `WRITE_SETTINGS_FIX`).
- **The pause really shows Android's pages.** `AppEnforcer` (291, 1188) and `MdmSyncWorker` (163) pass
  `overrideActive = OfflineOverride.isActive() || RestrictionsPause.isActive()`. With it, `computeEnforcementPlan` has
  `appsManaged = false` and `locked = false`, so `hide` is empty apart from SMS apps while SMS is off, `suspend` is
  that set plus Play's own rule, and `kioskPackages` is `null`. Android's Settings isn't an SMS package, so it is
  unhidden and unsuspended, and nothing is pinned. `lockTaskHelpers` never pins Settings, and the plan's comment says
  "Never Settings or Play". The rows use `systemPageIntent`: `MATCH_SYSTEM_ONLY`, then `dialogTarget` (system, not
  suspended, not ours). That is the battery row's check, moved out of `BatteryExemption.kt` without changing it.
  `windowOpen` covers both windows, as the plan does.
- **Outside a pause nothing opens.** On a managed phone Settings is hidden or suspended, so `pageIntents` is empty,
  the tap opens nothing and the row says to pause first. The rows live only in `SettingsFragmentLauncher`, behind
  `SettingsActivity`'s gate. That gate checks on every `onCreate`/`onStart` (drawer, the exported
  `APPLICATION_PREFERENCES`, explicit intents, recents) and closes in `onStop`. So the parent PIN is asked again when
  Android's page returns. Before the first policy Settings is open, but Android's Settings is not hidden then either,
  so the rows add no reach. If the parent allowlists Android's Settings, the kid already has these pages.
- **Nothing weakens the lock.** As Android's face the keyguard features (`ANDROID_KEYGUARD_FEATURES`) already leave
  the fingerprint enabled, and nothing in the branch writes keyguard features, the credential or lock-task state.
  Android's enrolment asks for the device credential itself. The kid knows the Child PIN and could enrol during any
  pause through Android's Settings anyway, so the row adds no new reach. The write-settings app-op lets Vibb write
  `Settings.System` only, which the touch row and the clock format already expect. It is not an `AppEnforcer`
  restriction, so the pause/override rule doesn't apply to it. No restriction was added or changed.
- **The API change is in one commit with both sides.** 81f74186 touches the launcher's
  `StatusReportRequest.writeSettingsGranted`, the server's `models.rs` field (`#[serde(default)] Option<bool>`),
  migration 0090, the INSERT (40 columns, 40 `?`, the new bind last) and `DeviceStatus` (read by `SELECT *` in all
  three readers, so `FromRow` gets the column). **No contract was missed.** The repo pins no general status shape, and
  the precedent field `battery_optimization_exempt` is covered the same way: a launcher encoding test (key present
  for true/false, absent for null) plus a server stored-and-shown test. `policy_json_keys_snapshot` and
  `PolicyResponseCompatTest` are about the policy, which doesn't change. No shared testdata file covers these
  booleans.
- **Strings.** All 14 new keys are in both `values/` and `values-nb/`, with the same `%1$s - %2$s` placeholders
  (`TranslationsTest` is green). The bokmål reads naturally ("et" agrees with "fingeravtrykk"; Android's own nb label
  is "Tillat endring av systeminnstillinger").
- **The migration test's rename.** `migrations_73_to_90_in_order` lists 0090 as "write settings granted" (sqlx's
  description from the file name). Every doc reference was moved: server/CLAUDE.md twice, `dns.md`,
  `hardening-and-pins.md`, `notifications.md`, `launcher-ui.md`. The one `migrations_73_to_89` left is the test's own
  "was" line. "The next free number is 0091" is right.
- **The first-launch rule, case by case** (`firstLaunchResetDue = !startedBefore && !enrolled && token blank`;
  `migratePreferencesToNewVersion` runs before it in every case):
  - *A fresh install*: no mark, no flag, no token, so the reset runs as before.
  - *QR provisioning*: the first process (`ProvisioningModeActivity`/the admin receiver) runs `initRest` and resets the
    fresh install, which writes `versionCode` 101 and, on a release build, hides Vibb from its own list. Then
    `applyProvisioningExtras` writes the token and `enrolled(true)`. Every later pre-Home start keeps them. This is the
    bug fixed.
  - *Settings opened without Home* (`APPLICATION_PREFERENCES`): the same order, so it is fixed the same way.
  - *An upgrade from a build before `mdm.enrolled`*: such a phone ran Home long ago (mark set), or it holds a token.
    Either way it is never reset.
  - *Enrolled by an older build: a token but no flag*: kept by the token arm (a test row covers it).
  - *Wiped and re-provisioned*: a fresh install again, so the QR case applies.
  - *The `versionCode` 101 reliance*: a skipped reset doesn't write `versionCode` or the self-hiding. Every phone with
    a token got both from the reset its first process ran before the enrolment could write anything: `initRest` runs
    before any component's code in the main process, and enrolment happens only in the main process. If a phone ever
    had a token without that first reset (none can today), the cost would be `migratePreferencesFromVersionUnknown`'s
    early return at every start ("No old preferences found") and, on release, Vibb in its own drawer
    (`AppFilter.kidListable` drops `OWN_PACKAGES` anyway since design 34 P5). So it is harmless (Q-8).
  - *A stale token after a server-side Reset*: nothing on the phone ever clears the token (401 keeps everything). So
    before Home's first start the phone now keeps its stale token, exactly as a phone that started Home always did.
    The old reset "healed" this only by accident, and the way out is the same as for any phone: re-enrol from Settings,
    which overwrites the token. That is no regression.
  - `started_before` stays Home's to set, so Home's first-launch work (the HOME role request, the notification
    permission) still runs on such a phone at Home's first start.
- **`credentialOurs` as the gate**, against 59d §8.4. *OWN_KEYPAD* (credential removed by hand): `deviceSecure` is
  false at once, so the row hides before `configure` clears `owned`. After the next Child PIN on our keypad
  (`migrateIfDue`) `owned && secure` holds again, and the row returns for the re-enrolment §8.4 asks for. That case
  is right. *Own mode* and *a credential someone else set* (`owned` false) are hidden. Two other cases show the row
  with a PIN that may not be the Child PIN (Q-4).

## Mutations

| # | Mutation | Caught by |
|---|---|---|
| A1 | `firstLaunchResetDue`: drop `!enrolled` | `the reset runs only on a phone that neither started Home nor enrolled` |
| B1 | `firstLaunchResetDue`: drop the token arm | the same |
| C1 | `firstLaunchResetDue`: drop `!startedBefore` | the same |
| C3 | glue: `initRest` resets on `firstLaunchResetDue(..) \|\| !startedBefore` | **not caught** |
| A2 | `writeSettingsRowState`: `pageAvailable` before `pageMissing` | `every row state` |
| B2 | `writeSettingsRowState`: drop `windowOpen -> NO_PAGE` | `every row state` |
| C2 | `writeSettingsRowState`: drop `granted == null -> UNKNOWN` | `every row state` |
| B4 | glue: the write-settings row's `windowOpen` without `OfflineOverride.isActive()` | **not caught** |
| C4 | glue: the row's state->string mapping, GRANTED and CAN_OPEN swapped | **not caught** |
| C5 | glue: no `SyncRunner.request(.., "write_settings")` after Android's page returns | **not caught** |
| D3 | glue: the tap opens the page when `granted != true` (unknown too) instead of `== false` | **not caught** |
| A3 | `fingerprintRowShown`: `hasSensor` alone | `the row shows only with our Android credential and a sensor` |
| B3 | `fingerprintRowShown`: `credentialOurs` alone | the same |
| D2 | `AndroidLock.credentialOurs`: `owned(app)` alone | `the row is hidden until the fragment decides, ...` (source pin) |
| A4 | `fingerprintEnrolled`: NONE_ENROLLED -> null | `enrolled only from what canAuthenticate can tell, never a count` |
| A5 | `fingerprintAccess`: window open, no page -> PAUSE_FIRST | `the page opens as the write-settings page does` |
| B5 | glue: the fingerprint state line, "Registered"/"Not registered" swapped | **not caught** |
| D1 | glue: the fingerprint row's `windowOpen = false` | **not caught** |

Every briefed pure-function mutation is caught: `writeSettingsRowState`, `firstLaunchResetDue`,
`fingerprintRowShown`, and the state-line rule `fingerprintEnrolled`. The glue around them is not: initRest's `if`,
both rows' `windowOpen` arguments, both state->string mappings, the sync on return and the tap's guard (Q-3).

## Findings

### Q-1 (Medium) - the fingerprint row always says "Unknown": `USE_BIOMETRIC` isn't declared

`launcher/app/src/main/java/com/kidslauncher/mdm/server/FingerprintEnrol.kt:48-53` (`enrolled`), and
`launcher/app/src/main/AndroidManifest.xml` (no `USE_BIOMETRIC`/`USE_FINGERPRINT`; the merged debug manifest has
neither either).

What's wrong: `BiometricManager.canAuthenticate(int)` requires `android.permission.USE_BIOMETRIC`. The SDK's
`platforms/android-36/data/annotations.zip` (`android/hardware/biometrics/annotations.xml`) carries
`@RequiresPermission("android.permission.USE_BIOMETRIC")` on it, and AOSP's `AuthService.canAuthenticate` enforces
it (USE_FINGERPRINT or USE_BIOMETRIC, else a `SecurityException`). Without the permission every call throws.
`runCatching` turns that into `null`, and `fingerprintEnrolled(null)` is `null`. So on every phone the row reads
"Unknown - tap to add one ..." ("Ukjent - ..."). It never says "Registered" or "Not registered", the half of the row
that tells the parent whether §8.4's re-enrolment is needed. Lint would flag `MissingPermission`, but
`abortOnError = false` and lint doesn't run locally. The unit tests feed `fingerprintEnrolled` constants, so they
can't see it.

Expected fix: declare `<uses-permission android:name="android.permission.USE_BIOMETRIC" />`. It is a normal
permission, granted at install, with no dialog. Pin it in `FingerprintEnrolTest` (the manifest has it, next to the
existing `canAuthenticate(...)` source pin). Device check: on the emulator during a pause the row reads "Not
registered", then after enrolling (`adb emu finger touch 1` at Android's prompts) "Registered".

### Q-2 (Low) - after the pause starts, the rows keep saying "pause first", and for a moment they can say "no page"

`SettingsFragmentLauncher.kt:260` (the pause's success callback only flips the switch), `:342-370`
(`updateFingerprintRow`), `:372-390` (`updateWriteSettingsRow`).

What's wrong:

1. The rows are re-read only in `onResume` and on their own tap. The parent-PIN dialog that starts the pause doesn't
   pause the fragment. So after the PIN, the three rows (battery included, which already had this) still say "Pause
   all restrictions first ..." until the parent taps one or leaves the screen. The docs' steps ("Pause, then tap the
   row") still work, because the tap re-resolves.
2. The pause's `apply()` runs on a background coroutine. Between the pause's start (`windowOpen` already true) and
   that apply unhiding Settings, a re-read finds no page and concludes `NO_PAGE`: "Not allowed - Android has no page
   for this on this phone" / "Android has no page for this on this phone". That is false and reads as final, and it
   stays until the next resume or tap. A quick tap right after the PIN hits it. The battery row has no NO_PAGE state,
   so this is new with these rows.

Expected fix: re-read all three rows once the pause's apply has run, e.g. through the window's existing listener
(`WindowRelock.addListener`, which already reloads the app list at a window's edges) or a callback from the pause's
start, posted to the main thread and guarded with `context ?: return`. Also derive "no page" only from what the last
applied plan says: the window is open **and** the enforced plan is the override's. Otherwise say "pause first" or
"a moment...". Keep it lean: one pure input (`settingsShown`) for both access rules, and a test row for "window open,
plan not applied yet".

### Q-3 (Low) - the glue around the pure rules is unpinned

`SettingsFragmentLauncher.kt:105-111, 219-231, 342-390`, `Application.kt:322-329`, `FirstLaunchResetTest`,
`WriteSettingsAccessTest`, `FingerprintEnrolTest`.

What's wrong: mutations C3, B4, C4, C5, D3, B5 and D1 survive.

- An extra `|| !startedBefore` on initRest's `if` (C3) brings back the de-enrolment this round fixed. The test pins
  that the call text appears and that `resetPreferences(` comes once after it, not the whole condition.
- Dropping `OfflineOverride.isActive()` from either `windowOpen` (B4, D1) misreports the row during the override:
  "pause first" while the page is there.
- Swapped state strings (C4, B5) show "Allowed"/"Registered" for the opposite state.
- A dropped sync on return (C5) leaves the PWA's warning until the next sync.
- `!= true` for `== false` (D3) opens the page when the grant can't be read.

Expected fix: pin the `if (firstLaunchResetDue(startedBefore, mdm.enrolled(), mdm.deviceToken())) {` line exactly,
with nothing else feeding the reset. Pin both `windowOpen = RestrictionsPause.isActive() ||
com.kidslauncher.mdm.server.OfflineOverride.isActive(),` lines (two occurrences) and both `when` mappings (each enum
value next to its string), e.g. the text between `when (state) {` and `}` against the expected lines. Also pin the
launcher callback's `SyncRunner.request(context.applicationContext, "write_settings")` and the tap's
`granted(context) == false`. A few `contains` checks, no new matrix.

### Q-4 (Low) - `credentialOurs` shows the row with a PIN that isn't the Child PIN in two cases

`launcher/app/src/main/java/com/kidslauncher/mdm/lock/AndroidLock.kt:297-300`, and the strings
`settings_mdm_fingerprint_open` ("Android asks for the Child PIN first").

What's wrong: the KDoc says that while `owned && deviceSecure` holds, "the PIN Android asks before its fingerprint
enrolment is the Child PIN". Two 59/59d states break that:

- **A change on the phone** (59 §3.7): Android's PIN was changed by hand, for example during a pause where Android's
  Settings is open. `onPasswordChanged` makes `android_pin_rev` unknown (`null`), but `keyguard_android` stays true
  while a credential is set. So the row shows and promises the Child PIN, and Android then asks for a PIN the parent
  may not know.
- **The rollback escrow** (§3.8, `AndroidRollback.NO_TOKEN`): the parent chose own mode, but with no active token
  Android's PIN stays the gate (`owned`, secure, not requested). Here the PIN is the Child PIN, but the escrow's next
  token activation clears the credential, and with it the fingerprint just enrolled (RC3-6). Enrolling there is
  wasted effort.

Expected fix: show the row only when the credential is the one Vibb set and still wants: `owned && deviceSecure &&
PinLockStore.androidPinRev(app) != null && requested` (as a pure `fingerprintRowShown(..)` input, with test rows for
both cases). Or, if the parent should be able to enrol in those states too, keep the gate and drop the "Child PIN"
claim from the string in those states. This is a product call, so ask the user if unsure. Not a lock weakening:
Android's own credential check still guards the enrolment.

### Q-5 (Low, docs) - `docs/testing/emulator.md` is now stale on "open Home once before enrolling"

`docs/testing/emulator.md:59` ("Open Vibb One's Home once before enrolling from its Settings") and `:194-198` ("Home
must have started on screen once **before** you enrol ... resets its preferences at the next process start ...").

What's wrong: with `firstLaunchResetDue` the reset skips an enrolled phone, so neither statement holds now. 59d
§8.17's last line says so ("no longer needed (not edited in this round)"). `:152-156` (§4's
`appops set ... WRITE_SETTINGS allow` step) could also name the on-phone row as the path a real phone takes.

Expected fix (the merger edits this file, as briefed): drop line 59's bullet, and replace §4's sentence with "Home
needn't start before enrolling since 2026-10-24 (design 59d §8.17); before that build a phone enrolled from Settings
was reset at the next process start". In the `appops` comment add "on a phone: Settings › Pause all restrictions ›
Modify system settings (docs/setup/android-settings.md)".

### Q-6 (Low, docs) - launcher/CLAUDE.md and two server notes don't know the new rows; one step in `android-settings.md` is wrong

- `launcher/CLAUDE.md`: not touched by the branch. Its "Background connection (design 19b phase 1)" section should
  say:
  - Settings has two more rows on the battery row's path: `WriteSettingsAccess` with `write_settings_granted`, and
    `FingerprintEnrol` (shown only with `credentialOurs` and a sensor).
  - `systemPageIntent` is now the shared resolver.
  - The first-launch reset now follows `firstLaunchResetDue` (no mark, no `enrolled`, no token; 59d §8.17).

  "One clock format" and "Touch sounds" still say the app-op is "granted once with adb ... or by the parent during a
  pause". That is true, but they should name the row.
- `server/CLAUDE.md:212` lists `device_status.battery_optimization_exempt (design 19b)` among the tables, but not
  `device_status.write_settings_granted`. `docs/notes/server/pwa.md:171` (About This Phone's Software group: "...,
  Battery optimisation") lacks the "Modify system settings" row.
- `docs/setup/android-settings.md`, step 2: "The row **"Modify system settings"** ... just above it". The order in
  `preferences.xml` is Background connection, Modify system settings, Fingerprint (when shown), Install from Play,
  Pause all restrictions, so the row is not just above the pause. Write "under "Background connection"", as the
  Fingerprint section's "under "Modify system settings"" does.

Expected fix: the lines above. No code change.

### Q-7 (Low) - no device check is recorded for the round

PLAN.md (both entries say "Done 2026-10-24"), 59d §8.17.

What's wrong: none of these was run, and nothing lists them for a later device run:

- `ACTION_MANAGE_WRITE_SETTINGS` and the biometric enrolment starting from our task during a pause on a managed phone
  (kiosk on before the pause).
- The return and the sync (the PWA's row flips to "Allowed" within seconds).
- `ACTION_BIOMETRIC_ENROLL` from a device owner with Android's keyguard features written.
- Q-1's state line.
- §8.17's fix: QR-provision or enrol from the exported Settings, kill the process before Home ever starts, then check
  that the token and policy survive the next start. Look for the log line `Home hasn't started yet, but the phone is
  enrolled - keeping its preferences`, then a reboot with the kiosk and the Android lock back.

Expected fix: add these as a "Device checks" line to both PLAN entries (or 59d §8.17), with the emulator steps
(`docs/testing/emulator.md` §4 and §6c).

### Q-8 (Note) - leftovers of the first-launch rule, no action needed

- A skipped reset relies on the first process's reset for `versionCode` 101 and the self-hiding. Every phone with a
  token had that first reset (see "What was checked").
- The QR path's failed enrolment still leaves `server_url` and the Tailscale key, and a pre-Home process start still
  clears them as before. The parent then types them in Settings again. That is unchanged and not a de-enrolment.
  Keeping them too (skip the reset with a server URL set) is possible, but it widens the rule for no real gain.
- 59d §8.17's aside: the QR path asks for no sync after its enrolment, unlike "Enroll now". It is a candidate backlog
  line: `SyncRunner.request(.., "enrolled")` after `applyProvisioningExtras` succeeds.
- About This Phone shows "Modify system settings · Not allowed" in the action colour on every phone that hasn't
  granted it, also where the parent uses neither touch sounds nor a fixed clock format. That is acceptable (a plain
  fact with its fix), but it is the parent's first new non-green row on most phones.

### Q-9 (Nit) - wording

- `FingerprintEnrol.enrolled` answers for any strong biometric (`BIOMETRIC_STRONG`), not the fingerprint alone. On a
  phone with only a fingerprint sensor (the Jelly Star) that is the same thing. The KDoc could say so.
- `clock_format_note`'s fix now reads "Vibb One needs Android's "Modify system settings" access, which a device owner
  can't grant. The touch sounds switches and the clock format need it. To fix it ...". The second sentence repeats the
  first inside the clock's own warning. It is fine on About This Phone, where the same text stands alone.

## Docs

- `android-settings.md` leads with the on-phone path, keeps adb as the shortcut and adds the fingerprint section. Its
  facts are right apart from Q-6's "just above it".
- 59d §8.4's addendum and §8.17 match the code: the one writer of `started_before`, `initRest`'s callers, what
  `resetPreferences` keeps, the QR and Settings paths, and adb `set-device-owner` enrolling nothing. §8.17's claim
  that both enrolment paths write the token and the flag together on success is pinned by `FirstLaunchResetTest`.
- `launcher-ui.md`'s touch-sounds bullet and server/CLAUDE.md's status line, migration chain and next free number are
  accurate. `play.rs::write_settings_grant_is_stored_and_shown` covers true, false and NULL, and the on-phone path
  before adb.

## Verdict

**Merge after a fix round.** Q-1 (Medium) is a one-line manifest fix plus a pin, but without it the fingerprint row's
state line never works on a phone. Q-2, Q-3 and Q-4 are small and belong in the same round (Q-4 may need the user's
call). Q-5 is the merger's edit, and Q-6/Q-7 are docs. The core holds: no adb step remains, the pages open only during
the pause or override and behind the parent PIN, nothing about the lock changes, the API change is in one commit on
both sides with the field tested like its precedent, and the first-launch rule keeps every enrolled phone while still
resetting a fresh install.

Suite results, verbatim: launcher `BUILD SUCCESSFUL in 2m 18s` - 1647 tests, 0 failures, 0 errors, 0 skipped; server
`test result: ok. 1210 passed; 0 failed; 0 ignored; 0 measured; 0 filtered out; finished in 441.13s`, `cargo fmt
--check` clean, `cargo clippy --all-targets` 13 known warnings, none new.
