# 01: Our own build installs on the Jelly Star

Status: design, 2026-10-04. Scope: PLAN phase 0 (fork plumbing) and phase 1 (fail closed, the minimum part).
Forks: `L` = `kids-launcher-mdm` @ `build-without-tsnet` (4bec459), `S` = `kid-phone-server` @ `test-harness` (a14b40f).
Launcher Kotlin paths are relative to `app/src/main/java/com/kidslauncher/mdm/`.
Tags: **[verified]** = checked in code, AOSP source or docs during this design. **[device]** = needs a test on the Jelly Star.

Package/applicationId stays `com.kidslauncher.mdm` (user decision). Release builds use exactly that id. Debug builds keep the
`.debug` suffix (`app/build.gradle.kts:66-70`). Only one of the two can be device owner on a phone. Production phones run release.

## 1. Release signing (L)

### 1.1 Keystore (user runs once, keeps it offline + in a password manager)

```sh
keytool -genkeypair -v -storetype PKCS12 -keystore handy-release.p12 -alias handy \
  -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=handy launcher, O=palchrb"
# PKCS12 uses one password for store and key; give the same value to both env vars below.
base64 -w0 handy-release.p12 | gh secret set ANDROID_RELEASE_KEYSTORE_B64 -R palchrb/kids-launcher-mdm
gh secret set ANDROID_RELEASE_KEYSTORE_PASSWORD -R palchrb/kids-launcher-mdm   # prompts
gh secret set ANDROID_RELEASE_KEY_ALIAS -R palchrb/kids-launcher-mdm -b handy
```
Losing this key means no more silent self-updates (PackageInstaller needs the same cert) and a re-provision of every phone.
Back it up twice. Key rotation later is possible with APK Signature Scheme v3 (`apksigner rotate`). Out of scope here.

### 1.2 Gradle (`app/build.gradle.kts`)

Add a `signingConfigs` block before `buildTypes`. Each value comes from the env var first, then from a Gradle property
(put local values in `~/.gradle/gradle.properties`, never in the repo):

| Value | Env var | Gradle property |
|---|---|---|
| store file | `ANDROID_RELEASE_KEYSTORE_FILE` | `handy.release.storeFile` |
| store password | `ANDROID_RELEASE_KEYSTORE_PASSWORD` | `handy.release.storePassword` |
| key alias | `ANDROID_RELEASE_KEY_ALIAS` | `handy.release.keyAlias` |
| key password | `ANDROID_RELEASE_KEY_PASSWORD` (falls back to the store password) | `handy.release.keyPassword` |

```kotlin
fun secret(env: String, prop: String) =
    providers.environmentVariable(env).orElse(providers.gradleProperty(prop)).orNull
val releaseStoreFile = secret("ANDROID_RELEASE_KEYSTORE_FILE", "handy.release.storeFile")
android {
  signingConfigs {
    if (releaseStoreFile != null) create("release") {
      storeFile = file(releaseStoreFile)
      storePassword = secret("ANDROID_RELEASE_KEYSTORE_PASSWORD", "handy.release.storePassword")
      keyAlias = secret("ANDROID_RELEASE_KEY_ALIAS", "handy.release.keyAlias")
      keyPassword = secret("ANDROID_RELEASE_KEY_PASSWORD", "handy.release.keyPassword") ?: storePassword
    }
  }
  buildTypes.getByName("release") { signingConfig = signingConfigs.findByName("release") }
}
if (releaseStoreFile == null && providers.gradleProperty("requireReleaseSigning").orNull == "true")
    throw GradleException("release signing requested but ANDROID_RELEASE_KEYSTORE_FILE is not set")
```
- Without a keystore, `assembleRelease` keeps producing `app-release-unsigned.apk`, as today. Local `assembleDebug` is unchanged.
- The release build type is non-debuggable by default (it has no `isDebuggable` line). This closes the adb `run-as` vector from
  qa-security #3.
- versionCode/versionName come from `-PversionCode` / `-PversionName` when given, else the literals at `:48-49`
  (see 1.5).

### 1.3 R8 / ProGuard (checked by building, not guessed)

`release` already has `isMinifyEnabled = true` (`app/build.gradle.kts:60`), and `app/proguard-rules.pro:2-3` sets
`-dontobfuscate -dontoptimize`, so R8 only **shrinks**. I ran `./gradlew assembleRelease` locally (stub tsnet, lint-vital
skipped because it is offline). It succeeds. In `app/build/outputs/mapping/release/` I checked: **[verified]**
- **kotlinx.serialization DTOs** (`server/dto/*`, the cached-policy blob): the library ships R8 rules
  (`kotlinx-serialization-common.pro`/`-r8.pro`, present in `configuration.txt`). Only unused constructors and
  `componentN()` are removed from the DTOs, and the serializers are kept. The project uses kotlinx.serialization, **not Gson**
  (`server/MdmApi.kt:36-39,132`), so there are no Gson rules to write.
- **Retrofit**: 2.11 ships `META-INF/proguard/retrofit2.pro` (interfaces with `@retrofit2.http.*`, `Continuation`
  signatures). `MdmApi` is kept.
- **kapt preferences**: the generated `LauncherPreferences` uses plain `new XSerializer()` and
  `EnumPreferenceSerializer(X.class)` (`app/build/generated/source/kapt/release/.../LauncherPreferences.java:191-358`). That
  is not reflection by name. Enum `valueOf` is covered by the default rules. Only `LauncherPreferences$Config` (a
  compile-time-only annotation holder) is removed, which is correct.
- **tsnet/gomobile**: `gomobile bind` writes `proguard.txt` into the aar with `-keep class go.** { *; }` and
  `-keep class tsembed.** { *; }` (golang/mobile `cmd/gomobile/bind_androidapp.go`) **[verified, source]**. AGP applies aar
  consumer rules automatically.
- **pcap4j**: it finds its packet factories through `ServiceLoader`
  (`META-INF/services/org.pcap4j.packet.factory.PacketFactoryBinderProvider`). R8 kept the service file and
  `StaticPacketFactoryBinder`.
- `QuickControls.kt:396,547,568,581` reflects only on framework classes (`BluetoothDevice`), which R8 does not touch.

Add these keep rules to `app/proguard-rules.pro` anyway. They are cheap, and they protect against a JNI or ServiceLoader
path the shrinker cannot see:
```
-keep class go.** { *; }
-keep class tsembed.** { *; }
-keep class org.pcap4j.packet.** { *; }          # factories are instantiated per packet type at runtime
-keepattributes SourceFile,LineNumberTable       # readable crash traces (names are kept already)
```
Remaining risk **[device]**: the DNS filter (pcap4j/dnsjava) and tsnet only run on a device. The first release-build
smoke test must cover a DNS lookup through `KidVpnService` and a tsnet connect (§4 step 8).

### 1.4 CI (`.github/workflows/android.yml`)

- Delete `Read Debug Keystore` (`:64-70`). Our fork has no `ANDROID_DEBUG_KEYSTORE` secret, and an empty decode only
  produces a broken file.
- Delete `Delete Old Pre-Release` and `Create Pre-Release` (`:88-108`). They publish an upstream-style debug build that
  nobody should install.
- Pin tsnet: replace `go get tailscale.com/tsnet@latest` / `wlynxg/anet@latest` (`:47,51`) and `gomobile@latest`
  (`:43-44`) with versions committed in `mobile/go.mod`/`go.sum` (`go get` with no `@latest`, just `go mod download`).
  Record the gomobile version in a `GOMOBILE_VERSION` env at the top of the workflow.
- On pushes and PRs: `./gradlew build -PrequireTsnet=true`, as today (`:72`). That covers debug, unsigned release, lint
  and unit tests. Upload the debug APK as an artifact (`:74-78`).
- New job `release`, triggered by `on: push: tags: ['v*']` (plus `workflow_dispatch`):
  1. Same toolchain and tsnet steps (factor them into a composite action or a reusable job).
  2. `echo "$ANDROID_RELEASE_KEYSTORE_B64" | base64 -d > $RUNNER_TEMP/release.p12`, then export
     `ANDROID_RELEASE_KEYSTORE_FILE=$RUNNER_TEMP/release.p12`, the password and the alias from secrets.
  3. Derive the version from the tag (see 1.5). Run
     `./gradlew assembleRelease testReleaseUnitTest -PrequireTsnet=true -PrequireReleaseSigning=true -PversionName=$V -PversionCode=$C`.
  4. Verify: `apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk`. Fail if the SHA-256 digest is
     not the one in the repo variable `RELEASE_CERT_SHA256` (set once from §2.3). Also fail if `aapt2 dump badging` shows
     `application-debuggable`. Also run `unzip -l app/libs/tsnet.aar | grep proguard.txt`.
  5. Rename the APK to the stable asset name **`kids-launcher-mdm.apk`**. Create a normal (non-prerelease) GitHub Release
     for the tag with `ncipollo/release-action` and attach the APK plus `kids-launcher-mdm.apk.sha256`.
     A stable asset name makes `https://github.com/palchrb/kids-launcher-mdm/releases/latest/download/kids-launcher-mdm.apk`
     a permanent URL. The QR provisioning and the server's tracked-app row both use it.

### 1.5 versionCode strategy

- Tags are `vMAJOR.MINOR.PATCH`, and `versionCode = MAJOR*1_000_000 + MINOR*1_000 + PATCH`. For example `v0.24.0` gives
  24000. This is deterministic from the tag, monotonic while tags only go up, and above upstream's 116, so it can never
  be refused as `INSTALL_FAILED_VERSION_DOWNGRADE`. A CI step computes it with `bash` parameter expansion and rejects tags
  that do not match `^v[0-9]+\.[0-9]+\.[0-9]+$`.
- Local and debug builds use the literals in `build.gradle.kts`. Never bump those by hand for a release.
- The first release is `v0.24.0` (continuing from upstream 0.23.6).

## 2. Server points at our builds (S)

### 2.1 Configuration surface

All values are env vars, read once at start into a new `src/config.rs` (`pub struct ForkConfig`, stored in `AppState`
as `config: Arc<ForkConfig>`). `dotenvy` already loads `.env` (`src/main.rs:43`). Defaults are ours:

| Env var | Default | Replaces |
|---|---|---|
| `SERVER_RELEASE_REPO` | `palchrb/kid-phone-server` | `system_update.rs:29-30` (`REPO_API_URL`), `security.rs:305` (`REINSTALL_HINT`, becomes a fn that formats the repo) |
| `LAUNCHER_ADMIN_COMPONENT` | `com.kidslauncher.mdm/com.kidslauncher.mdm.server.MdmDeviceAdminReceiver` | `provisioning.rs:39-40` |
| `LAUNCHER_APK_URL` | `https://github.com/palchrb/kids-launcher-mdm/releases/latest/download/kids-launcher-mdm.apk` | `provisioning.rs:54-55` |
| `LAUNCHER_SIGNATURE_CHECKSUM` | **none** | `provisioning.rs:52` |

- `LAUNCHER_SIGNATURE_CHECKSUM` has no default on purpose. While it is unset, `/devices/{id}/provision` renders the page
  with a "launcher signing checksum not configured (see DEPLOY.md)" banner and **no QR**. It never falls back to upstream's
  value. This is a new `missing_checksum: bool` on `ProvisionQrTemplate` (`provisioning.rs:98-104`).
- `ProvisioningPayload` fields (`provisioning.rs:70-76`) change from `&'static str` to `String`.
- Validation at start: the component must contain `/`, the URL must start with `https://`, and the checksum must match
  `^[A-Za-z0-9_-]{43}$` (32 bytes as base64url without padding). If a value is invalid, log an error and treat it as unset.
- `install.sh` writes the three `LAUNCHER_*` lines into a fresh `.env` (commented out for the checksum, with the command
  from 2.3).
- The root-side scripts must not source `.env`, because it is writable by the service user and they run as root.
  Instead:
  - `deploy/install.sh:14` becomes `REPO="${KPS_REPO:-palchrb/kid-phone-server}"`.
  - The heredoc'd `actions.sh` (`install.sh:168-177`) gets the value substituted at install time:
    `sed -i "s|@REPO@|$REPO|" "$UPDATER_DIR/actions.sh"` with `REPO="@REPO@"` in the template.
  - `deploy/update.sh:11` gets the same default with a `KPS_REPO` override.
  - Usage lines in all three scripts, plus README/DEPLOY.md, change to `palchrb`.
- Bump `WATCHER_SCHEMA_VERSION` (`install.sh:25`) and `REQUIRED_WATCHER_SCHEMA` (`security.rs:283`) from 4 to 5. The admin
  UI then tells existing installs to re-run the installer, which rewrites `actions.sh` with our repo.
- Self-update of the launcher stays data, not code. In `/apps`, the launcher row gets:
  - `github_repo = palchrb/kids-launcher-mdm`
  - asset filter `kids-launcher-mdm.apk`
  - `include_prereleases` off
  - `is_launcher` on (`tracked_apps.rs:508` `set_is_launcher`)
  - package `com.kidslauncher.mdm`

  Document this in DEPLOY.md. There is no migration because there is no row to rewrite on a fresh install.
- Tests (TestApp):
  - `provision_page_without_checksum_has_no_qr`
  - `provision_payload_uses_configured_values`, which checks the JSON in the SVG `alt`/data or the rendered payload. To make
    this testable, extract `fn provisioning_payload(cfg, extras, wifi) -> serde_json::Value`.
  - `ForkConfig::from_env` table tests (valid, invalid, missing). Pass a `HashMap` into the parser rather than mutating
    the process env.

  `TestApp::new` (`src/tests/mod.rs:42-67`) builds `AppState`, so give it `ForkConfig::for_tests()`.

### 2.2 Out of scope here (tracked for PLAN phase 6)

The root updater still does `curl …/master/deploy/update.sh | bash` (`install.sh:176`). Pointing it at our fork removes the
dependency on upstream, but master still runs as root. Follow-ups:
- fetch `update.sh` from the release tag instead of `master`;
- publish a `SHA256SUMS` with each server release and check it in `update.sh`.

### 2.3 Computing the checksum

`PROVISIONING_DEVICE_ADMIN_SIGNATURE_CHECKSUM` is the SHA-256 of the **signing certificate**, base64url-encoded without
padding (not the APK hash; see `provisioning.rs:42-51`). You can compute it from the keystore or from a signed APK:
```sh
keytool -exportcert -alias handy -keystore handy-release.p12 \
  | openssl dgst -binary -sha256 | openssl base64 -A | tr '+/' '-_' | tr -d '='
# or, from a release APK:
apksigner verify --print-certs kids-launcher-mdm.apk | sed -n 's/.*SHA-256 digest: //p' \
  | xxd -r -p | openssl base64 -A | tr '+/' '-_' | tr -d '='
```
Both commands must print the same 43 characters. Put the value in `/opt/kid-phone-server/.env` as
`LAUNCHER_SIGNATURE_CHECKSUM=…`, and put the hex SHA-256 in the launcher repo variable `RELEASE_CERT_SHA256` (§1.4).

## 3. Fail closed, the minimum (paired S+L PRs, branch `fail-closed`)

### 3.1 Server: never answer an open default

The current behaviour is in `src/handlers/device_api.rs:64-192`:
- A DB error or a missing row becomes `DevicePolicy { vpn_filter_enabled: true, ..Default }` (`:68-82`).
- Bad `allowlist_json` becomes `None` (`:84-87`).
- A global schedule DB error becomes the default (`:108-114`).
- The command is marked delivered before the response exists (`:129-151`).

New behaviour:
- Extract `pub(crate) async fn build_policy(state: &AppState, device_id: i64) -> Result<PolicyResponse, PolicyError>`.
  `PolicyError` is `Db(sqlx::Error) | MissingRow | CorruptAllowlist(serde_json::Error)`. The handler maps every error to
  `500` with `tracing::error!(device_id, ?err)` and an empty body. The launcher treats non-2xx as "no fresh policy"
  (`MdmSyncWorker.kt:409`, `Response.body()` is null) and keeps its cache **[verified]**.
- Inside `build_policy`:
  - Use `fetch_optional(..).await?` with `ok_or(MissingRow)`.
  - Parse the allowlist with `serde_json::from_str` and `?`. A `NULL` column stays `None`, because that is a genuinely
    unmanaged new device before the first heartbeat bootstrap.
  - The global schedule query uses `?`. A missing singleton row stays `unwrap_or_default` (it is seeded by its migration).
  - `dns_filter_settings` and `packages_to_uninstall` use `?`.
  - `compute_dns_filter_version` (`:199-232`) returns `Result`.
- Pop the command **last**, after every read has succeeded, in one statement:
  `UPDATE device_commands SET delivered_at = datetime('now') WHERE id = (SELECT id FROM device_commands WHERE device_id = ? AND delivered_at IS NULL ORDER BY requested_at LIMIT 1) RETURNING id, command`.
  A 500 then never eats a command, and two concurrent polls cannot get the same command.
- `devices::create_device` (`devices.rs:108-115`): the `device_policy` insert must not be `.ok()`. Wrap the device and
  policy inserts in one transaction and return 500 on error, so a device without a policy row cannot exist.
- Tests in `src/tests/device_api.rs`:
  - Remove the `#[ignore]` at `:147` (`missing_policy_row_does_not_unlock`). Tighten it to `assert_eq!(status, 500)`.
  - `corrupt_allowlist_json_is_500`: set `allowlist_json = 'not json'`.
  - `db_error_is_500_not_default`: `DROP TABLE dns_filter_settings` (or `global_schedule`) and then GET the policy.
  - `failed_policy_does_not_consume_command`: queue `ring`, corrupt the allowlist and get a 500, fix it, then GET
    delivers `ring` once.
  - `null_allowlist_stays_null` (a new device is still unmanaged).
  - `policy_json_keys_snapshot`: a sorted key list, as a contract test against the launcher DTO.

### 3.2 Launcher: keep the last good policy

Current behaviour:
- A cache decode failure gives `null` (`MdmSyncWorker.kt:420-427`). `null` reaches `AppEnforcer.apply(null)` (`:102,109`),
  which unsuspends everything and unpins (`AppEnforcer.kt:94-98,182`).
- The same happens in `reevaluateLockReasonFromCache` (`:445-454`, which ends bedtime) and in the Settings pause toggle
  (`SettingsFragmentLauncher.kt:168`).
- Any 2xx body is cached before it is looked at (`:407-418`), and it overwrites the PIN hash (`:83-84`).
- `allowlist = []` means "open" (`AppEnforcer.kt:98,439`).

New pure file `server/PolicyGate.kt`. It has no Android imports. Move `ServerJson` out of `MdmApi.kt:35-39` into
`server/ServerJson.kt` so JVM tests do not load Retrofit wiring.
```kotlin
sealed interface CachedPolicy { data object Absent; data class Ok(val policy: PolicyResponse); data class Corrupt(val error: String) }
fun decodeCached(json: String?): CachedPolicy            // null/blank -> Absent, exception -> Corrupt
enum class FreshVerdict { ACCEPT, REJECT_SUSPECT }
fun judgeFresh(fresh: PolicyResponse, cached: CachedPolicy): FreshVerdict
  // REJECT_SUSPECT iff fresh.allowlist == null && cached is Ok && cached.policy.allowlist != null.
  // The server never sets a managed allowlist back to NULL (unchecking the last app writes "[]",
  // devices.rs:562-599), so this pattern only comes from an old/buggy server's default policy.
sealed interface PolicyToApply { data class Apply(val policy: PolicyResponse?); data object KeepCurrentState }
fun choosePolicy(fresh: PolicyResponse?, cached: CachedPolicy): PolicyToApply
  // fresh accepted -> Apply(fresh); else Ok -> Apply(cached); Absent -> Apply(null) (never synced: setup must work);
  // Corrupt -> KeepCurrentState
```
Wiring:
- `fetchPolicy` (`MdmSyncWorker.kt:407-418`) stops writing the cache.
- `performMdmSync` (`:74-109`):
  1. `cached = decodeCached(mdm.kidModePolicy())`.
  2. If `fresh != null && judgeFresh(fresh, cached) == REJECT_SUSPECT`, log a warning and treat it as `fresh = null`.
     The PIN hash, cache, command dispatch and uninstalls are all skipped.
  3. Only for an accepted fresh policy: write the cache, then run the existing `:79-100` block.
  4. `when (choosePolicy(..))`: on `KeepCurrentState`, skip `lockReason` and `AppEnforcer.apply`. DPM suspensions, lock-task
     packages and user restrictions persist in the OS **[verified: they are DPM state]**. Then continue with the status
     report.
- `cachedPolicy()` (`:436`) returns `CachedPolicy`. Callers:
  - `reevaluateLockReasonFromCache` (`:445`): on `Corrupt`, do not touch `lockReason`.
  - `SettingsFragmentLauncher.kt:168`: on `Corrupt`, skip `apply`.
  - `AppEnforcer.enforceOnNewPackage` (`:439`): on `Corrupt`, suspend the new package (fail closed).
  - `QuickControlsActivity.kt:50`: `(… as? Ok)?.policy?.quickControlsMask ?: 0`.
- `StatusReportRequest` gets `policyState: String? = null` (`"ok"|"cache_corrupt"|"rejected_suspect"`). On the server,
  `#[serde(default)] policy_state: Option<String>` plus a `device_status.policy_state TEXT` column (one small migration,
  or fold it into the calls migration in 02 if that lands first). The device page shows a warning when it is not `ok`.
- **Empty allowlist** (`AppEnforcer.kt:98`): drop `.takeIf { it.isNotEmpty() }`. `[]` then suspends and hides every
  controllable package except our own. Kiosk with `[]` (`:182`) pins only our package. That is safe: our package is HOME
  and holds LockActivity/Settings. Do the same at `:439`. **[device]**: reboot with kiosk on and `[]`, and confirm
  there is no boot deadlock (CLAUDE.md:37-41 history).

Extract `AppEnforcer.kt:98-138,182` into a pure `computeEnforcementPlan(allowlist: List<String>?, overrideActive: Boolean, controllable: List<String>, ownPackage: String): EnforcementPlan(suspend: Set<String>, kioskPackages: Set<String>?)`.
`apply()` then only diffs the plan against `pm.isPackageSuspended`. 02-calls extends the same function with the
system-dialer rules.

JVM unit tests (`app/src/test/java/com/kidslauncher/mdm/server/`):

**`PolicyGateTest`**:
- `decodeCached`: null gives Absent, `""` gives Absent, valid JSON gives Ok, truncated JSON gives Corrupt, and `{}`
  gives Ok (all defaults).
- `judgeFresh`: the suspect case, a legit `[]`, a legit PIN removal (allowlist present, `override_pin_hash: null`) is
  accepted, and an Absent cache is accepted.
- `choosePolicy`: all four branches.

**`PolicyResponseCompatTest`**:
- A fixture blob with today's 17 fields (`dto/PolicyResponse.kt:34-52`), captured from a real server response, decodes.
- A blob plus unknown keys decodes.
- A blob plus `call_policy` decodes (02).
- A blob with `"kiosk_desired": null` gives Corrupt. This documents the missing `coerceInputValues`.

**`EnforcementPlanTest`**:
- null allowlist means nothing is suspended and there is no kiosk.
- `[]` suspends everything except own and pins only own.
- Override active means nothing is suspended and there is no kiosk.
- Our own package is never in `suspend`.

## 4. Runbook: Jelly Star, adb provisioning

QR provisioning is not the path. It failed on a GMS Moto (S `CLAUDE.md:67`), and L lacks
`GET_PROVISIONING_MODE`/`ADMIN_POLICY_COMPLIANCE` activities (launcher review §2.8). Use adb:

1. Server side:
   - set `LAUNCHER_SIGNATURE_CHECKSUM` and restart;
   - add the launcher catalog row (§2.1);
   - set provisioning settings (server URL `https://<pi>.ts.net`, Tailscale auth key: one-off, tagged);
   - create the device (`/devices/new`) and note the enrollment code (valid 30 min; "regenerate" if needed).
2. On the phone: factory reset. In the setup wizard, **skip Google account sign-in** (and Unihertz/OEM accounts).
   `dpm set-device-owner` refuses if any account exists. Finish setup, then set a screen-lock PIN. Without it,
   `LOCK_TASK_FEATURE_KEYGUARD` has nothing to show (L `CLAUDE.md:46`). Lock-task emergency calling also depends on the
   keyguard feature (02 §2.6).
3. Enable Developer options, then USB debugging, and authorise this computer.
4. Check:
   - `adb shell dumpsys account | grep -c 'Account {'` gives `0`;
   - `adb shell pm list users` shows only user 0.
5. Get the release APK:
   - `gh release download v0.24.0 -R palchrb/kids-launcher-mdm -p kids-launcher-mdm.apk`
   - `apksigner verify --print-certs kids-launcher-mdm.apk` must print the SHA-256 recorded in `RELEASE_CERT_SHA256`.
   - `adb install kids-launcher-mdm.apk`
6. `adb shell dpm set-device-owner com.kidslauncher.mdm/com.kidslauncher.mdm.server.MdmDeviceAdminReceiver`. Use the
   fully qualified receiver (L `CLAUDE.md:59-63`). Expect `Success: Device owner set to package …`.
7. Press Home and pick Kids Launcher if asked. `AppEnforcer.enforceDefaultHome` pins it afterwards. Then enrol: swipe up,
   Settings, then:
   - server URL;
   - Tailscale auth key;
   - "Enroll now" with the code.

   Or use "Scan setup QR" on the server's provision page, which fills all three. Then "Sync now" must toast success.
8. Smoke test (release build, first time):
   - The device appears on the server with an app list.
   - Toggle an app: it is suspended within seconds (SSE).
   - A blocked domain is blocked (DNS filter, pcap4j under R8).
   - The server is reachable over tsnet away from Wi-Fi (mobile data).
   - `adb shell run-as com.kidslauncher.mdm true` fails ("package not debuggable").
   - `adb logcat | grep -iE 'ClassNotFound|NoSuchMethod|Serializer'` shows nothing.
9. Fail-closed check:
   - Stop the server (or `sqlite3 kidphone.db "DELETE FROM device_policy WHERE device_id=N"` on a test server), then
     "Sync now". The toast says failure, and the apps stay suspended and kiosk stays on.
   - Restore the row.
10. Leave USB debugging on during the test phase. Turning it off and setting `DISALLOW_DEBUGGING_FEATURES` is PLAN
    phase 2, after a tested recovery path.

Recovery if the launcher boot-loops: `adb shell dpm remove-active-admin` does not work for a DO.
`adb uninstall` is blocked for a DO. The path is to install a fixed APK over it with `adb install -r` (same key), or a
factory reset from recovery.

## 5. Ordered tasks

| # | Fork | Task | Verify |
|---|---|---|---|
| 1 | L | signingConfig + version properties + keep rules (§1.2, 1.3, 1.5) | local: `assembleRelease` with a throwaway keystore; `apksigner verify`; `aapt2 dump badging` has no debuggable flag |
| 2 | L | CI: pin tsnet/gomobile, drop debug pre-release, add tag `release` job (§1.4) | CI: push tag `v0.24.0-rc` to a test branch with a temp variable |
| 3 | S | `ForkConfig` + provisioning/system_update/security/install/update changes + tests (§2) | local: `cargo test` |
| 4 | S | `build_policy` fail-closed + command pop last + transactional `create_device` + tests (§3.1) | local: `cargo test`, ignored test now passes |
| 5 | L | `PolicyGate`, `computeEnforcementPlan`, wiring, `policyState` + tests (§3.2) | local: `testDebugUnitTest`; device: §4 step 9 |
| 6 | both | First release `v0.24.0`, provision Jelly Star (§4) | device |

## Open questions

- Is the GitHub owner for both forks `palchrb` (from the git remotes)? Defaults assume it.
- `policy_state` in the status report: add it now (small migration), or fold it into 02's migration?
- Kiosk with `allowlist = []` pinning only the launcher: acceptable, or keep "never pin with zero allowed apps"?

## Decisions after QA review (qa-01-02.md), 2026-10-04

QA findings override this doc where they conflict. Binding for implementation:

- **System dialer is never hidden or suspended** (blocker 1), in this step already and on
  every phone, managed or not: `TelecomManager.getSystemDialerPackage()` is excluded in
  `computeEnforcementPlan`. It is NOT added to the lock-task packages (keypad bypass via
  `tel:` links); the keyguard lock-task feature stays forced on so lock-screen emergency
  calls work.
- **"Pause all restrictions" requires the PIN** even when none is configured on the server
  (then it is unavailable), is time-limited, and is reported in the status report.
- **CI signing:** release secrets live in a GitHub Environment; signing runs in its own job,
  only for `v*` tags whose commit is on `main`/`master`. Release candidates use a separate
  pre-release and are never served as `releases/latest`.
- **Rollback:** `update.sh` backs up the SQLite DB before migrating; the runbook documents
  that a release launcher can't be downgraded, so a broken release is fixed forward with a
  higher versionCode.
