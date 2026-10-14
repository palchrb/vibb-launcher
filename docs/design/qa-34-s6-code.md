# QA 34 step 6 (code) - one clock format on the whole phone (P12)

Reviewer: code QA, 2026-10-14. Branch `design-34-s6`, `35676753..b7e0c5f7` (one commit, server, launcher and docs,
migration 0075). I read it against `docs/design/34-launcher-polish.md` (P12 as redesigned for the rule "the status bar
and Vibb One always show the same format, settable from the PWA", §5 step 6, §7 M5/M7/L10, "Step 6 implementation
status"), `qa-34-design.md`, the root `CLAUDE.md`, `server/CLAUDE.md` and `launcher/CLAUDE.md`.

In the code I read:
- the whole diff;
- `update_launcher_ui`, `build_policy`'s `launcher_ui`, the status insert, `clock_format_note` and `device_home.html`;
- `ClockFormat`, `ClockFormatSetting`, their call sites (`AppEnforcer.apply`, `performMdmSync`), `GlancePanel`'s
  start/resume/stop, `LockActivity`'s start/stop, the manifest's `configChanges`, and the seven surfaces in the
  inventory;
- a grep of `launcher/app/src/main` for other clock surfaces (`DateFormat.format`, `ofPattern`, `SimpleDateFormat`,
  `DateUtils`, `%d:%02d`, string resources with a time argument). I found none outside the inventory: the rest are
  durations (`PinBackoff`, `formatElapsedTime`, the music position) or dates (`dMMM`).

Line numbers are `b7e0c5f7`'s. No product code was changed. The mutations below were temporary and were reverted, and
`git status` was clean before this file was written. I did not use the emulator.

**Verdict: merge after a short fix round.** The contract is right in both directions:
- `launcher_ui.clock_format` is always sent, and the snapshot has it.
- An old launcher ignores the key, and a new launcher reads a missing, `null` or unknown key as "system".
- A POST without the field keeps the stored value (`COALESCE`, pinned).
- Migration 0075 is safe on an existing DB (`NOT NULL DEFAULT 'system'` passes its own CHECK), and the order test
  pins 73/74/75.

The writer's pure decision is correct and tested: it writes only when the value differs, and "Follow the phone"
clears only our own value, only while it is still in place. `WRITE_SETTINGS` adds no real risk, and the writer can only
touch `TIME_12_24`.

There is one Medium finding:
- **M1:** with `TIME_12_24` unset, "the system locale" is read from `Resources.getSystem()`. On Android 13+ with a
  per-app language, that object very likely carries Vibb's own locale. That would break the user's rule in the
  default case: "Follow the phone", Vibb in nb, the phone in en_US.

Counts: 0 High, 1 Medium, 2 Low, 6 Nits.

## What I ran

All runs were under `~/build.lock`, with `CARGO_TARGET_DIR=~/.cache/handy-target/qa-34-s6`.
- Server, from `server/`: `cargo fmt --check && cargo clippy --all-targets --locked && cargo test`.
  - All three passed, and **947 tests** passed.
  - The clippy warnings are all old ones (dead fields in `models.rs`, `dns_engine.rs`, `storytel.rs`, `devices.rs:1282`).
    None is in the new code.
- `node --test server/jstest/*.test.js`: **30 passed**.
- Launcher, from `launcher/`: `./gradlew --no-daemon --max-workers=2 assembleDebug testDebugUnitTest --rerun
  -PwarningsAsErrors=true` passed: **1195 tests in 127 classes**, 0 failures.
- I ran 13 mutations, listed in the table at the end. 7 were caught and 6 survived.

## Medium

### M1. "The system locale" is probably Vibb's per-app language

`launcher/.../ui/ClockFormat.kt:71-75` reads `Resources.getSystem().configuration.locales[0]`, with a fallback to
`Locale.getDefault()`.

The launcher sets its language with the framework's per-app locale API (`LauncherLocales.kt`,
`LocaleManager.setApplicationLocales`; minSdk 34). Here is how that API works:
- On Android 13+, the system applies the app-specific locale to the app **process's** configuration
  (`WindowProcessController.applyAppSpecificConfig`). That is why the Application context's resources carry it, not
  only the activities'.
- `ResourcesManager.applyConfigurationToResources` passes that same process configuration to
  `Resources.updateSystemConfiguration`. So `Resources.getSystem()` in Vibb's process most likely reports the per-app
  locale too.
- `Locale.getDefault()` is the per-app locale on these versions anyway.

Android added `LocaleManager.getSystemLocales()` (API 33) because there was no reliable in-process way to read the
system locale.

What goes wrong if this holds: the parent leaves Clock on "Follow the phone" (the default), the phone is en_US (12 h),
and Vibb is in nb. The status bar shows 6:15, while every Vibb clock shows 18:15. That is the case the step exists to
fix. The design lists it under "Deferred / not checked" ("Vibb in nb on an en_US system follows the status bar").
`ClockFormatTest` only checks that `getBestDateTimePattern(locale, "jm")` is spelled in the file, so the wrong source
passes (mutation 5: `context.resources.configuration.locales[0]` survived).

**Fix:**
1. Read `context.getSystemService(LocaleManager::class.java)?.systemLocales?.get(0)`. Fall back to
   `Resources.getSystem()` only when that is null.
2. Pin the call in the source test: `"systemLocales"` must appear in `ClockFormat.kt`, and
   `context.resources.configuration` must not appear in `systemIs24`.
3. On the emulator, with Vibb in nb and the system in en_US: log both sources once to confirm, then run the deferred
   check.

## Low

### L1. A 12 h "until" in a notification has no AM/PM

`ClockFormat.formatTime` (`ClockFormat.kt:55-59`) always drops AM/PM in 12 h, "as the status bar". It is now also used
for the "until" texts:
- the relock notification "All restrictions paused until %1$s" (`WindowRelock.kt:457`);
- install mode's notification "Play is open until %1$s" (`PlayRuntime.kt:243`);
- its Settings summary (`SettingsFragmentLauncher.kt:285`);
- Home's banner (`HomeActivity.kt:657`);
- the lock's "Until %1$s".

Before this step, `getTimeFormat`/`getTimeInstance(SHORT)` gave "9:30 PM". A pause set at 21:00 for 12 h now says
"until 9:00", which for a 12 h parent or kid could mean this evening or tomorrow morning. The status bar can leave out
AM/PM because it shows *now*. A future time can't.

**Fix:** a `formatTime(..., withAmPm = true)` variant (or `formatUntil`) for the five "until" strings in 12 h,
using `DateFormat.getBestDateTimePattern(systemLocale, "hm")` with the system locale from M1. Keep it inside
`ClockFormat.kt` so the scan still holds. The glance clock, row times and the missed-call text can stay without
AM/PM. Alternatively, document the choice in the design if the bare form is intended.

### L2. The wiring the design relies on isn't pinned (three survivors)

The pure rules are well tested, but the claims that live in the call sites are not:
- **"The write never lifts `DISALLOW_CONFIG_DATE_TIME`"** (design: "Not a restriction"). `ClockFormatSettingTest.wiring`
  (`:56-66`) only looks inside `ClockFormatSetting.kt`. Wrapping the call at `AppEnforcer.kt:484` in
  `applyDateTimeLock(dpm, admin, false)` … `applyDateTimeLock(dpm, admin, plan.lockDateTime)` survived the **full** unit
  suite (mutation 9).
- **"A policy without `launcher_ui` leaves the setting alone."** Replacing `ui ?: return`
  (`ClockFormatSetting.kt:67`) with `ui ?: LauncherUi()` survived (mutation 11). This one matters: the last-enforced
  fallback has no `launcher_ui`, so during an outage the fallback would read "system" and **clear** the parent's 24 h
  (`Clear`, since we wrote it).
- **"Bound at every start/resume."** Removing `ClockFormat.bind(glance.lockGlanceClock)` from `resumeNow`
  (`GlancePanel.kt:162`) survived (mutation 12). The TextClock check (`ClockFormatTest.kt:120`) accepts any `bind`
  call anywhere in the sources, and the one inside the `watch` lambda satisfies it. Without the resume bind, the glance
  clock follows the per-app language until `TIME_12_24` changes.

**Fix (tests only, plus one small refactor):**
1. Make the "no `launcher_ui`" case part of the pure decision: `clockFormatWrite(choice: String?, …)` with
   `null` → `Keep`, and `apply` passes `ui?.clockFormat?.let(::clockFormatChoice)`. Add a row to
   `follow the phone clears only our own value`.
2. In `wiring`: `applyDateTimeLock(` appears exactly once in `AppEnforcer.kt`, before `ClockFormatSetting.apply`, and
   no `UserRestriction`/`applyDateTimeLock` call follows it in `apply()`.
3. In the TextClock test: require the bind in the non-lambda path, e.g. `resumeNow`'s body for the glance clock and
   `onStart` before `watch` for the lock clock, by slicing the function text the way other source tests here do.

## Nits

- **N1. The CHECK constraint is unpinned.** Dropping `CHECK (clock_format IN ('system', '24', '12'))` from 0075
  survived the `launcher_ui`, `device_api`, `device_pages` and migration tests (mutation 1). The handler validates
  first, so it is defence in depth. Either add a one-line test (`UPDATE device_policy SET clock_format = '25'` is an
  error) or accept it.
- **N2. The migration-order test's name is stale.** `the_migrations_end_with_design_30_then_spotify`
  (`music_spotify.rs:175`) now pins 73/74/75 and no longer "ends with". Rename it (e.g.
  `migrations_73_74_75_in_order`) and update the assert message to mention 0075.
- **N3. "Kids Launcher" in the ⓘ** (`device_pages.rs:963`, the Settings path). It is right for today's label. Add it
  to P11's (step 12) grep list together with the `me.vibb.launcher` adb line, which stays correct.
- **N4. "Follow the phone" after our write resets to *unset*, not to what was there before.** A phone set to 12 h by
  hand, then 24 h from the PWA, then "Follow", ends on the locale default, not on 12 h. This is minor and the doc's
  wording ("clears our own value") matches the code. Remembering the earlier value in `clock_format_state` would make
  it exact, if wanted.
- **N5. The surface check is a substring.** `every clock surface formats through ClockFormat`
  (`ClockFormatTest.kt:135`) only requires `ClockFormat.formatTime(` somewhere in each file. A contrived
  `LocalTime.now()…toString()` beside a dead `formatTime(…).take(0)` survived (mutation 8). A realistic hard-coded
  `"HH:mm"` was caught (mutation 7). The scan is good enough. The only gap is `LocalTime`/`LocalDateTime.toString()`
  and string templates of `.hour`/`.minute`. `\.(hour|minute)\b` next to a `:` in a template could be added to the
  forbidden list if cheap.
- **N6. A system-locale change while the PIN lock is shown isn't rebound.** `PinLockActivity` handles `locale` itself
  (`configChanges`), and `watch` only observes `TIME_12_24`. With `TIME_12_24` unset, the glance clock keeps the old
  locale's 12/24 until the next resume. The case is rare. If wanted, call `bind` and `renderNow` from the host's
  `onConfigurationChanged`. Separately, a fixed `HH:mm` differs from the status bar's separator in a few locales (fi
  `H.mm`). This is outside nb/en and within the design's "one format" meaning, so noting it only.

## Mutations

| # | Mutation | Tests run | Result |
|---|---|---|---|
| 1 | 0075 without the CHECK constraint | server `launcher_ui`, `device_api`, `device_pages`, `migrat` (92) | **survived** (N1) |
| 2 | Missing `clock_format` field → "system" (`.or(Some("system"))`) | server `launcher_ui` | caught (`clock_format_form_keeps_a_missing_field`) |
| 3 | `clock_format` skipped when "system" (`skip_serializing_if`) | server `launcher_ui`, `device_api` | caught (3 tests incl. `policy_json_keys_snapshot`) |
| 4 | `clock_format_note` ignores "Follow the phone" | server `launcher_ui` | caught |
| 5 | `systemIs24` reads `context.resources` (the per-app locale) | `*ClockFormat*`, `PolicyResponseCompatTest` | **survived** (M1) |
| 6a | Writer puts even when `current == choice` | `ClockFormatSettingTest` | caught |
| 6b | "system" clears a value we didn't write | `ClockFormatSettingTest` | caught |
| 7 | Home banner: `DateTimeFormatter.ofPattern("HH:mm")` | `ClockFormatTest` | caught (the scan) |
| 8 | Home banner: `LocalTime…toString()` beside a dead `formatTime` | `ClockFormatTest` | **survived** (N5, contrived) |
| 9 | `AppEnforcer` lifts the date-time restriction around the write | full launcher unit suite | **survived** (L2) |
| 10 | `LauncherUi.clockFormat` defaults to "24" | `PolicyResponseCompatTest` | caught |
| 11 | `apply` without the "no `launcher_ui`" guard | `*ClockFormat*`, `PolicyResponseCompatTest` | **survived** (L2) |
| 12 | `GlancePanel.resumeNow` without `bind` | `*ClockFormat*`, `*Glance*` | **survived** (L2) |

## Checked and fine

- **Contract, old and new:**
  - An old launcher ignores unknown keys (lenient `ServerJson`).
  - A new launcher on an old server decodes, round-trips the cache, and reads "system" (`PolicyResponseCompatTest`, 6
    shapes).
  - `clock_format_applied` goes out under the server's key and is left out when null.
  - The server stores `Option<bool>`, and an absent field is NULL.
  - Both snapshots were updated in the same commit.
  - `models::clock_format` maps `DevicePolicy::default()`'s empty string to "system".
- **The migration:** SQLite allows `ADD COLUMN … NOT NULL DEFAULT … CHECK(…)`, and existing rows get 'system', which
  passes. `clock_format_applied` is a nullable INTEGER, and 0075 follows 0074.
- **The form:**
  - The select autosaves inside the same form as the other two selects, and the `<noscript>` Save posts all three.
  - The answer lands on `#layout` (`assert_lands`, and `check_forms`' redirect table includes `clock_format`).
  - A present unknown value (including "") is a 400 that writes nothing.
  - The warning shows only for 24/12 with the last report false. The command appears only inside the `<details>`.
- **The writer:**
  - It runs under `AppEnforcer`'s lock.
  - `canWrite` is checked before the write, and a refused write reads back false and keeps `clock_written`, so it is
    retried.
  - A Clear that can't be written keeps the pref, so it is retried.
  - Across reboots: before the first unlock the CE prefs throw, which is caught, and the next apply after the unlock
    does it.
  - A policy flip 24→12→system writes 12 and then clears 12. The `Forget` rule covers a hand-change.
  - `putString(…, null)` is valid: `TIME_12_24`'s validator allows null, and it is a public System setting.
  - `report()` is the read-back, so `true` really means "in place".
- **`WRITE_SETTINGS`:**
  - It is an appop-gated permission and denied by default. Declaring it only lists Vibb under "Modify system settings".
  - It doesn't touch `checkReleaseHasNoDebugHook` (a class check), the Play-services/Firebase guard or
    `OwnPermissionsTest` (not role-grantable).
  - Play policy isn't relevant (the APK ships from GitHub), and WRITE_SETTINGS isn't a Play-restricted permission anyway.
  - The only `Settings.System.put*` in the launcher is the hard-coded `TIME_12_24` in `ClockFormatSetting`, and no IPC
    reaches it.
- **TextClocks:** only `lock_clock` and `lock_glance_clock` have time formats (the test enumerates them). The glance
  is a plain view in `PinLockActivity`, not a RecyclerView. `LockActivity` is recreated on a locale change (no `locale`
  in its `configChanges`), so `onStart` binds again. `watch` and `unwatch` are paired on start and stop, and
  `GlancePanel.start`'s `started` guard prevents a double registration.
- **The source scan:** it caught the realistic hard-coded pattern. Durations (`%d:%02d`, `h:mm:ss`) don't trip it, and
  `dMMM` dates pass the hour-field regex.
- **Licensing:** the new files are in `ui/` and `server/` (GPL), not in the MIT `ui/list/` or `apps/`.
