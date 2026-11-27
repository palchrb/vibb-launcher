# Vibb One (the launcher) — Project Context for Claude Code

## What this is

An Android home screen for a kid's phone that is also, in the same process, the [Device Owner](https://developer.android.com/work/dpc/build-dpc) parental-control agent. Package (applicationId) `me.vibb.launcher` (debug `me.vibb.launcher.debug`) - the Kotlin namespace and every class name stay `com.kidslauncher.mdm.*`, so components are `me.vibb.launcher/com.kidslauncher.mdm.…`. It speaks directly to kid-phone-server, which lives next to it in this monorepo (`../server/`, repo `palchrb/vibb-launcher`; see the root `CLAUDE.md` for the layout) - there is no other server, protocol, or MDM stack involved.

Originated as a fork of Josia Pietsch's µLauncher (itself from Finn Glas's Launcher); what is still shared is the app-list screen (`ui/list/`, `apps/`, MIT - see `LICENSE-MIT-UPSTREAM`). Don't describe this project as "a fork" in user-facing copy; it's its own thing now built on a small inherited component, not vice versa.

This file is the core every launcher task needs; the area notes are in "Where the rest is" below - read only the ones your task touches.

## Tech stack

Kotlin, single Gradle module (`app/`). `minSdk = 34`, `compileSdk`/`targetSdk = 36`. Retrofit + kotlinx.serialization (`JsonNamingStrategy.SnakeCase`, matching the server's plain snake_case JSON) for the device-facing API client in `server/`. `androidx.preference` for Settings, `eu.jonahbauer.android.preference.annotations` (kapt-generated) for the typed `LauncherPreferences` accessor API.

- **Transport rule (security review S-M1):** the server address is `https://`, or `http://` to a Tailscale IP literal
  sent only through tsnet's proxy - nothing else, no fallback; every server client builds its OkHttp client with
  `toServer(serverPathNow(url))` (`server/ServerTransport.kt`, rule in `ServerUrlRule.kt`, the base
  `ServerClients.base(path)`). Cleartext is allowed app-wide only for the Wi-Fi sign-in window (design 102 D15); every
  other HTTP client is kept in named files by `ServerTransportTest`'s source scan. Once the server switches a phone's
  check on, https also needs a key of the built-in issuer set (`server/ServerPins.kt`, design 76).
  `docs/notes/launcher/security.md`, "The server address: https only", "The server's certificate issuer, pinned".
- **Two Java files, kept in Java**: `preferences/LauncherPreferences$Config.java` (kapt's annotation config - add new
  preference entries there, in Java) and the vendored MMS codec `sms/vendor/pdu/` (AOSP, Apache-2.0, hardened; only
  `sms/mms/MmsCodec.kt` calls it, parsing only in the isolated parse process). Why, and the codec's tests:
  `docs/notes/launcher/architecture.md`.

## Architecture (in one line each; in full: `docs/notes/launcher/architecture.md`)

- **Device Owner is the whole point.** `MdmDeviceAdminReceiver` + `dpm.isDeviceOwnerApp()` gate every enforcement
  call in `server/AppEnforcer.kt` - a no-op if not device owner.
- **`server/MdmSyncWorker.kt`**'s `performMdmSync()` is the heartbeat (policy, lock decision, `AppEnforcer.apply()`,
  status report), under a `syncMutex`; every background trigger (SSE nudge, `BackstopAlarm`, process start) goes
  through `push/SyncRunner` in the anchor service (`docs/notes/launcher/push-and-play.md`).
- **`server/AppEnforcer.kt`** is the enforcement engine; *what* to suspend and pin is the pure
  `computeEnforcementPlan` (`server/EnforcementPlan.kt`), `apply()` only diffs it against the live state.
  `LOCK_TASK_FEATURE_KEYGUARD` is always forced on (the boot-lockout incident: `docs/notes/launcher/gotchas-and-history.md`).
- **The parent PIN** is checked only through `server/ParentPin.check` (offline, its lockout and events; design 24,
  `docs/notes/launcher/security.md`). Nothing on the phone starts the offline override any more.
- **Settings is PIN-gated** (`SettingsGate.kt`): open only before the first policy; afterwards the parent PIN, and
  with no PIN on the server it doesn't open at all. **`restrictions_paused`** (`server/RestrictionsPause.kt`) is the
  PIN-gated, 2-hour emergency pause; it ends on reboot and can't be stretched by the clock.
- **`LockActivity.kt`** is the time-rule screen - one instance, no code entry (the parent lifts a rule from the PWA
  only); the phone book, Kalender, Meldinger and Kid Settings check the rule themselves when they resume.
- **Self-update is a tracked app** like any other (`tracked_apps` row, `include_prereleases` off); the launcher's own
  APK is committed last, in the night window behind the update fence (`docs/notes/launcher/kiosk-escapes-and-updates.md`).

## Fail closed (in full: `docs/notes/launcher/fail-closed.md`)

`AppEnforcer.apply(null)` means "no restrictions", so a "no usable policy" case (a corrupt cache, a server answering a
default policy after an error) must never become `null` on a managed phone:
- `server/PolicyGate.kt` (pure, `PolicyGateTest`) judges the cached and the fresh policy; `Apply(null)` only on a
  phone that has never applied a policy, otherwise `Fallback`, which enforces the `LastEnforcedPlan` (unreadable too:
  nothing allowed, kiosk on).
- Only an accepted fresh policy is cached, in one synchronous `commit()` with `policy_ever_applied` and the
  `LastEnforcedPlan`; every status report carries `policyState`. Offline callers use `currentPolicyDecision()`.
- The enrolment's witness (`server/EnrolmentWitness.kt`) keeps a managed phone from being read as new.
- `allowlist = []` means nothing allowed; `null` means unmanaged.
- The system dialer is never suspended or hidden; while managed and not allowlisted, `DISALLOW_OUTGOING_CALLS` is
  the gate. `DISALLOW_CONFIG_DATE_TIME` while managed.
- `ServerJson` has no `coerceInputValues`: one `null` in a non-nullable field fails the whole decode
  (`PolicyResponseCompatTest`; the server's `policy_json_keys_snapshot`).

## Established discipline

**The update fence's release code is never removed** (handy step 11): a phone that updated into a build carries that build's fence record, and every later build must still be able to release it - keep `UpdateFencePlan.kt`'s prefs file, keys, v1 parse and the release path, and only ever add keys. The rule was lifted only for round C1 and is back in force from v0.40.0.

**Never install a locally-built APK on a kid's phone.** Production installs come from GitHub Releases built by the root `.github/workflows/launcher.yml` (the jobs, the `release` environment and the signing checks: `docs/notes/launcher/build-and-release.md`). Third-party actions are pinned by SHA; never add `pull_request_target`. Before every `adb install`, `apksigner verify --print-certs` the downloaded APK against `RELEASE_CERT_SHA256`.

## Standing rule on restrictions

**Standing rule, going forward: every restriction added to `AppEnforcer` must be liftable via the offline-override PIN / pause-restrictions kill-switch, unless explicitly told otherwise for that specific case.** The call rules (see "Calls & SMS") are such an explicit exception: the override and pause never open calls or SMS. The hardening switches (see "Hardening (handy step 4)") are the second one: only the server lifts them. `applyPrivateDnsLock` predates this rule and is the other deliberate exception on record (still unconditional - see its own doc comment for why); don't treat it as a precedent for a *new* restriction without checking first. The underlying reasoning, confirmed live twice now (once for the VPN filter toggle, once for this sideload restriction): the whole point of those overrides is a guaranteed, fully-unblocked device when something's wrong, and a parent should never find a restriction they can't reach through their own offline PIN.

## Building and releasing (in full: `docs/notes/launcher/build-and-release.md`)

- Without `libs/tsnet.aar` the **debug** variant compiles a stub (`app/src/tsnetStub/java/tsembed/`, every connect
  fails); a **release** build never gets the stub (`checkReleaseHasTsnet`). If `mobile/tsembed`'s exported API
  changes, update the stub to match.
- Production phones run the **release** build, `me.vibb.launcher`; only one of debug and release can be device owner
  on a phone. The signing key comes from env vars or `~/.gradle/gradle.properties` - never the repo.
- **Never bump the versionCode/versionName literals for a release**: release CI derives them from the tag
  (`../scripts/release-version.sh`; the scheme is in the root `CLAUDE.md`). A release can't be downgraded, so a broken
  one is fixed forward under a higher tag; a mistyped high tag burns versionCodes for good. Losing the release key
  means no more silent self-updates and re-provisioning every phone.

## Where the rest is

Area notes, moved verbatim from this file on 2026-11-01 and 2026-11-27; read only the ones your task touches.
Every section name, as other docs and code comments cite it (`launcher/CLAUDE.md "..."`): `docs/notes/launcher/README.md`,
or grep the heading in `docs/notes/launcher/`.

- `architecture.md` - the two Java files; Architecture in full (sync, enforcement, PIN, Settings gate, pause, LockActivity, self-update)
- `fail-closed.md` - Fail closed in full
- `build-and-release.md` - the release pipeline, building without tsnet.aar, dependency verification, release builds
- `calls.md` - calls, SMS and MMS, Element X and VoIP, the emergency menu, the phone book, contact requests
- `hardening.md` - Hardening (handy step 4), sideload blocking, Mobilnett and roaming, Android 17's Settings
- `home-and-photos.md` - Home, photos, badges, language, Home at boot, app names and icons, the flashlight
- `kid-settings.md` - Kid Settings: sound, Bluetooth, hidden apps, Flymodus, Snarveier, as a task of its own
- `ui-polish.md` - design 34's look, the night drawer, one clock format, the kid's Mørk/Lys
- `time-rules.md` - time rules, screen time, lifts, location policy, the parent code's pause, the clock during a rule
- `push-and-play.md` - Push, battery and Play (handy step 7), SSE, the policy's 304, the heartbeat report
- `lock-and-glance.md` - Android's lock as the only lock, the glance page, frosted glass
- `kiosk-escapes-and-updates.md` - the update fence, the self-update window, Install Now, catalog downloads
- `music-bridge.md` - the music bridge, episode pictures, media surfaces
- `dns-and-vpn.md` - the on-device DNS filter and tsnet, a custom DNS server, the browsers' secure DNS, filter on/tunnel down
- `zones-and-power.md` - battery, phone off and on, safe zones, location while still
- `wifi-places.md` - Wi-Fi places, the Apple and cell lookups, after a small move, a zone's Wi-Fi
- `dagen-and-calendar.md` - Dagen and the calendar links
- `app-permissions.md` - background connection, per-app permissions, special accesses
- `security.md` - parent PIN hardening, relock windows, the Keystore seal, the security review, https only, the issuer pin, the hardware-bound key
- `logins.md` - Wi-Fi sign-in pages and Chrome only for logins
- `unified-push.md` - Vibb One as the UnifiedPush distributor
- `map-links.md` - map links
- `setup.md` - the phone's setup flow, "Koble til"
- `gotchas-and-history.md` - Non-obvious gotchas; Retention; Crash reports; MY_PACKAGE_REPLACED
