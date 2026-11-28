# 142 - The setup QR made robust: what is still open

Status: built 2026-11-28 on branch `qr-robust` (§10), not gated, no code QA yet; design rev 2 (rev 1 1ce646a22 on
`qr-robust-design`; its design QA 2H 4M 6L, all taken, one corrected - §9; the owner's answers in §8). The locked
list's item 9 ("QR setup made robust", approved 2026-11-12). An audit of the whole path, then the fixes for what is
still open.

Goal: a parent with a stock Android 14-17 phone with Google Play services (the Jelly Star, Android 16) scans the
Phone Setup QR and ends with an enrolled, locked-down kid's phone, without adb; every way it fails shows one short line
and a way forward. For any family, not only ours.

## 0. Short answer

- Most of the path is built and correct in code. The failure stories of 2026-11-20 are fixed (§1).
- **No phone can be set up from the QR today, on any server from 0.94.0 on** (`TooOld` first shipped in
  `server-v0.94.0`, f817c62e0). GitHub's "latest" is `launcher-v0.41.0` (versionCode 410009, 2026-11-20 08:43 UTC,
  before the extras fix); design 129b's floor is `ENROL_ATTEMPT_FIRST_CODE = 430003`
  (`server/src/launcher_check.rs:70`), so the QR check answers `TooOld` and the Connect page shows no QR and makes no
  QR code (`launcher_check.rs:620-626`, `provision_qr.html:66-67`, `provisioning.rs`'s `launcher_blocks` check before
  `new_code`). A typed setup code is still made (`devices::regenerate_code` doesn't read the check). The fix is a
  release, not code: a stable `launcher-v0.43.0` (M1). Until then the stopgap is `LAUNCHER_APK_URL` pointed at an rc
  asset (`settings.provisioning.launcher.too_old_sub` says so on the page).
- The QR has never been run end to end since the fix (M2: the real-device list in §5).
- Code fixes: the apply at enrolment that blocks USB debugging (PLAN 1m(b), M3 - a race between two applies), Koble
  til saying why the QR didn't connect (M4), and setup over mobile data (N3, a must since the owner's Q3).

## 1. Already handled (one line each)

| Item | Where |
|---|---|
| The admin extras on a Google phone arrive only at `ADMIN_POLICY_COMPLIANCE` (CloudDPC role holder) | `fe8c497fe` (`qr-compliance-extras`): `ProvisioningActivities.kt:57` hands them to `QrExtras.deliver`, sealed before it returns (`QrExtras.kt:91-94`). |
| Every path enrols through one apply | Compliance (`ProvisioningActivities.kt:57`), `onProfileProvisioningComplete` without a role holder (`MdmDeviceAdminReceiver.kt:82`), the in-app scanner (`QrExtras.applyScanned`), Koble til (`QrExtras.enrolTyped`): one mutex, the code's hash marks it applied (`QrExtras.kt:273-279`), so a double delivery sends the code once. `GET_PROVISIONING_MODE` passes the bundle on (`ProvisioningActivities.kt:31`). |
| "Server address not set" after a failed enrolment | The kept extras are retried at every sync run (`QrExtras.kt:122`); the first-launch reset doesn't touch their file. |
| No way to enrol the same phone entry with a code after a failed QR | Koble til on the phone (`setup/ConnectActivity.kt`, `fe8c497fe`); in the PWA, "Ny oppsettkode" on a never-connected phone's summary (`device_detail.html:109`) and in Connect's "Allerede satt opp" group (`provision_qr.html:130`). |
| The Parent PIN made before the QR not seen by the phone | The cause was the extras bug: no enrolment, so no policy. The Parent PIN is the policy's override PIN (`override_pin_hash`, `handlers/device_api.rs:937`), in the first policy, and since design 114 §5.8 no QR or code is made without it (`provisioning.rs:219`, `:599`). Vibb's own PIN lock is gone (phase D). |
| `LEAVE_ALL_SYSTEM_APPS_ENABLED` | Always true in the QR (`provisioning.rs:109`, `:735`; design 86 §9). |
| A 64-hex Wi-Fi key; the Wi-Fi saved for the QR | Refused (`provisioning.rs:516`); the network saved sealed (`1628e0727`, migration 0162, `provisioning.rs:360`). |
| The QR's launcher checked before a QR shows | Design 86: signer, package, Android version; `TooOld` (129b D13); `NeedsServer` (140 §6); checked again when `releases/latest` moves (`provisioning.rs:215`). |
| Code expiry and reuse | A QR code lives 30 min, once; a new QR or code replaces it (`enrollment.rs:31-32`, `:125`). A code that was used but whose answer got lost is answered again within its life (129b, `device_api.rs`'s `repeat_enrolment`). |
| A phone that can't prove its hardware key | Design 129b: the phone waits with a six-digit number (`EnrolWait`), the Connect page shows the confirm (`provision_qr.html:36`). |
| A phone locked down before it talks to the server | `ProvisioningBaseline.arm` in the compliance step (`ProvisioningBaseline.kt`): no USB debugging, no new users, no safe mode, no user certificates, no unknown sources until the first policy. |
| Play Protect | The Jelly's QR run of 2026-11-20 installed and provisioned 0.41.0 with no block (`docs/notes/server/gotchas.md`, the bullet "QR-code zero-touch provisioning", its Jelly Star sentence); the owner has been a verified developer with the packages registered since 2026-11-17. Design 92 (the bootstrap DPC) stays parked. |

## 2. Interrupted setups (what happens, all handled but one line)

| Case | Today |
|---|---|
| Wi-Fi drop or download failure before Device Owner | Android's own error screen ("Try again" or reset). Nothing of ours ran, the code isn't used: scan the same QR again within 30 min. |
| Reboot after Device Owner, before the compliance step | The extras never arrive, and `ProvisioningBaseline.arm` never runs either: the phone comes up Device Owner with no baseline until its first policy (USB debugging is off after a reset anyway, but a kid could turn it on in that gap). Home shows Koble til (Device Owner, not enrolled, `ConnectScreen.kt:21`); the parent types a new code. Not built: arming at the first process start would need a sign of a QR setup, and there is none here (no extras arrived); "Device Owner and never enrolled" alone is also adb's `dpm set-device-owner`, the dev path that must keep adb. |
| No network or tailnet at the enrolment | Kept, retried at every sync run (process start, nudges, the 30-min backstop). Koble til says so (M4). |
| The retries outlive the 30-min code, or the QR was old when scanned | 401: marked refused (`QrExtras.kt:243`), Koble til shows - with M4's line. |
| Process death right after the compliance result | Sealed before the result (`QrExtras.kt:91`), retried at the next start. |

## 3. Must-haves

### M1 - A stable Vibb One new enough to be "latest" (release, no code)

`launcher-v0.43.0` on the rc.8 commit (61a70c73e) once rc.8 has run a day on the Jelly and `server-v0.96.0` is live on
the owner's server (Q1: yes). 0.96.0 is on the same commit, so 140's `NeedsServer` passes on 0.96.0 and blocks older
servers with its own orange line (as designed). Until then every family's QR is blocked by `TooOld`, whose line is
right ("Vibb One i QR-koden er for gammel") and names the stopgap (§0). A release, not this branch's work.

### M2 - One end-to-end run on a real phone (owner, §5)

No emulator image has Google's setup wizard, the QR reader or the CloudDPC role holder (2026-11-12 emulator test), so
the fix of 2026-11-20 has only been checked in code. §5 is the list, on the Jelly at a planned time (Q2).

### M3 - The apply at enrolment blocks USB debugging (PLAN 1m(b)) - S, launcher

**The cause (design QA H1; traced in code, not yet confirmed by a log): a race between the setup hold's apply and the
"enrolled" sync's.** On a first enrolment (a fresh image: no policy ever, so the hold starts) `Enrollment.enroll`
starts the hold (`Enrollment.kt:133` -> `RestrictionsPause.startHold` -> `reapply`), whose coroutine calls
`AppEnforcer.apply(app, currentPolicyDecision())` (`RestrictionsPause.kt:200`): the decision, `Apply(null)`, is read
*before* `@Synchronized apply` takes its lock (`AppEnforcer.kt:358-365`). Meanwhile the callers' "enrolled" sync
stores the first policy (`storeAcceptedPolicy`, with `calls_managed_last`, `MdmSyncWorker.kt:190`, `:1631-1658`) and
runs `CallPolicyStore.refresh` (`:228`) before its own apply (`:298`). The hold's pass, holding its stale
`Apply(null)`, reads `CallPolicyStore.state.managed` mid-pass (`AppEnforcer.kt:432`) = true, so
`hardeningManaged(null, true)` says managed and `hardeningPlan(null, managed = true)` takes each switch's `defaultOn`
(`Hardening.kt:126`): `DEBUGGING_FEATURES` on. The baseline is already lifted then (a policy was applied). AOSP writes
`adb_enabled = 0` when `DISALLOW_DEBUGGING_FEATURES` is set, and lifting it a moment later (the sync's own pass, with
"Block USB debugging" off) doesn't switch adb back on. A race fits "2 of 4" on the gate.

Rev 1's cause (the device-protected boot copy putting back `calls_managed_last` on a second enrolment) doesn't hold: the
enrolment's witness (DE) is never cleared, so a second enrolment on the same install is `everManaged`, starts no hold
(`Enrollment.kt:69-73`) and decides `Apply(cached)` or `Fallback` (`PolicyGate.kt:305-309`), never `Apply(null)`.

**Fix: the decision and the call state read together, inside the lock, in an order that can't pair them wrong.**
- `AppEnforcer.applyCurrent(context)`, `@Synchronized`: reads the call state (`ensureLoaded`, then
  `CallPolicyStore.state`) first and `currentPolicyDecision()` second (`callsThenDecision`), then runs the pass with
  that one call state - the hardening, the call roles, the fence and the rest of the pass read it, not
  `CallPolicyStore` again. The order matters: the sync's store and refresh run outside `AppEnforcer`'s lock, and the
  sync commits the policy before it refreshes the call state, so a managed call state read first means the policy is
  already in the cache when the decision is read. (Reading both inside the lock in the other order would leave the
  same window, only smaller.)
- Every `AppEnforcer.apply(x, currentPolicyDecision())` (23 sites) becomes `AppEnforcer.applyCurrent(x)`. This also
  stops an older cached policy, read before a running pass, re-applying over a newer one. The sync keeps
  `apply(context, decision, fromSync = true)` with its fresh decision; its pass reads the call state inside the lock,
  after its own refresh.
- `hardeningManaged` stays as it is (design QA H2): a `policy != null` guard would break fail closed for a managed
  phone whose call state comes from the DE copy (`CallPolicyStore.kt:194`, `repairManagedLast`). With the race closed
  no null-policy guard is needed.
- One `Log.i` per pass: the decision's kind (`Apply`, `Fallback`, a bare policy), policy present, the call state and its
  source (`CallPolicyStore.describe()`), managed for hardening, the baseline - for the gate.
- Test (`HardeningTest`): every point at which the sync's two writes can land around `callsThenDecision`'s two reads
  gives a pair that never has `Apply(null)` with managed calls, and none reaches `hardeningPlan(managed = true)` without
  a policy; a source scan that no `apply(..., currentPolicyDecision())` is left and the pass reads `CallPolicyStore`
  once.
- Gate row (Android 14 and 16): enrol a fresh image with "Block USB debugging" off, adb stays up; repeat 4 times.

### M4 - Koble til says why the QR didn't connect - S, launcher

Koble til after a failed QR shows the empty fields and the hint only (`ConnectActivity.kt:126`). One line in the
existing `connectError` slot, from `QrExtrasStore` (no new state), recomputed at every render - and `render()` already
runs whenever `QrExtras.running` changes (design QA M-a):
- still retrying: `hasPending()` and nothing running;
- refused: `appliedRefused()`, only while `EnrolmentWitness.enrolled()` is false (design QA M-b: the flag is cleared only
  by a QR path's success, so on a witnessed phone whose preferences were lost it would be an old QR's word).
`EnrolWait`'s lines and a typed failure keep priority. Nothing while an apply runs (the button says busy).

| Case | nb | en |
|---|---|---|
| The QR's code refused (expired, used, wrong server) | `QR-koden virket ikke. Bruk en ny oppsettkode.` | `The QR code didn't work. Use a new setup code.` |
| The QR's code kept for a retry (no network, the tailnet not up, 5xx, 426 `update_app`, the certificate check, the token not stored: `QrExtras.kt:289-294`'s `RETRY`) | `Ikke koblet til ennå. Prøver igjen.` | `Not connected yet. Trying again.` |

Rev 1's second line ("Får ikke kontakt med serveren") named one cause of several (M-a). Checked against "Words in the
UI": one short line, only when wrong, on the kid's phone; the hint under the fields already says where the code is.
Test: a pure `qrConnectLine(refused, pending, running, witnessEnrolled)` in `ConnectScreenTest`, the render's order by
source scan, both strings in nb and en.

### N3 - Setup over mobile data (`PROVISIONING_USE_MOBILE_DATA`) - S, server (a must since Q3)

`android.app.extra.PROVISIONING_USE_MOBILE_DATA`, boolean, default false, API 29+ (all of 14-17). With a Wi-Fi SSID in
the QR, Wi-Fi is used; without one and with the flag, QR provisioning uses mobile data and offers no Wi-Fi picker
(design QA M-c) - so it is an explicit choice per QR, never the default.
- Server: `ProvisioningPayload` gets `use_mobile_data: bool` next to `leave_all_system_apps_enabled`
  (`#[serde(rename = "android.app.extra.PROVISIONING_USE_MOBILE_DATA", skip_serializing_if = "std::ops::Not::not")]`),
  set as `mobile && wifi.is_none()` in `setup_payload`, passed through `payload_for`.
- Connect's QR group: one choice above the Wi-Fi fields, "Nett": "Wi-Fi" / "Mobildata" (en "Network": "Wi-Fi" /
  "Mobile data"), Wi-Fi checked. Mobildata hides the Wi-Fi fields (radios before the rows, sibling selectors, no script),
  skips the SSID/password checks (`wifi_refusal`), never reads or writes the saved network (`qr_password`,
  `save_wifi`). (i) after the choice: "Telefonen trenger et SIM-kort med data." / "The phone needs a SIM card with
  data." A form field, not stored (no migration); the POST answers in the QR group (`#qr`), never at the top.
- Tests: mobile with no SSID -> the key true; any SSID -> no key; Wi-Fi -> no key; a POST with Mobildata and a Wi-Fi
  field that would be refused makes the QR, saves nothing, and comes back with Mobildata chosen.
- Whether CloudDPC honours the flag is unverified: §5 row 11.

## 4. Nice-to-haves

- **N1 - A fork with its own signing key** (XS, docs): Play Protect's provisioning check applies to a DPC that isn't
  registered to a verified developer; families using our release APK share our registration, a family that signs its
  own build doesn't. One line in DEPLOY.md, "Launcher provisioning settings", pointing to design 92.
- **N2 - A WPA3-only network in the QR** (check first): the QR sends `PROVISIONING_WIFI_SECURITY_TYPE = "WPA"`
  (`provisioning.rs:730`); whether a WPA3-only (SAE) router joins is unknown. If §5 row 9 fails, one line behind the
  Wi-Fi field's (i): "Et WPA3-nett: velg nettet på telefonen i stedet." / "A WPA3 network: pick it on the phone
  instead."

## 5. The real-device list (the Jelly Star on 16, at a planned time - Q2)

Before: server 0.96.0, a stable 0.43.0 as "latest" (M1), the Parent PIN set, a fresh phone entry kept for the re-setup.
A factory reset loses the phone's data. A phone fresh from a reset has USB debugging off and the setup wizard can't
turn it on, so a failure before the compliance step leaves only the screen: photograph it.

1. Phone Setup's launcher row: "New phones get Vibb One 0.43.0"; Connect shows a QR.
2. Reset, tap 6 times on the welcome screen, scan. Record each screen; note any Play Protect screen word for word.
3. With the saved Wi-Fi in the QR: the phone joins without asking.
4. It ends on Vibb One; the PWA shows the phone connected within a minute; the setup hold and the flow start.
5. About This Phone: the hardware key, not "Uten maskinvarenøkkel" (if the phone waits with a number: 129b's confirm).
6. Dialer, Messages and the camera are still there (`LEAVE_ALL_SYSTEM_APPS_ENABLED`); language and time zone as set.
7. USB debugging stays blocked until the first policy. Once the baseline lifts, adb stays off until the parent turns
   it on (AOSP doesn't switch adb back on): "Block USB debugging" off in the PWA makes it *possible* to switch on in
   Developer options, nothing more (design QA M-d).
8. A QR made more than 30 minutes before the scan: Vibb One installs, Koble til shows M4's refused line, a typed code
   enrols.
9. (If the router can) a WPA3-only network in the QR (N2).
10. (A spare phone on 14, if there is one) rows 2-4 again.
11. N3: a SIM without a SIM PIN and with data, no Wi-Fi anywhere in reach or the saved one forgotten; Connect with
    "Mobildata", scan. The phone downloads Vibb One over mobile data with no Wi-Fi screen and enrols. Note the data the
    setup used (Android's data usage for the day).

Android 17: no device; nothing in M3/M4/N3 is version-dependent (no API above 34 on the phone; N3's extra is API 29).

## 6. Android 14-17

The role-holder path (CloudDPC) is used on Google phones from Android 13 on, so 14-17 alike; the extras reach the
compliance step on each. M3 and M4 use no new API; N3's extra is read by ManagedProvisioning from Android 10.
Checked in code against 14 and 16 (the emulator gates' images); the setup wizard itself only on the real phone (§5).

## 7. Sizes and order

M1 release (owner). M3 S, M4 S and N3 S on one branch (`qr-robust`, launcher and server), code QA, the M3 gate row on
14 and 16, in the next launcher RC and server release; M2 after M1 (it checks M4 and N3 too if they are in that RC).
N1 XS with them; N2 only on §5's finding.

## 8. Questions for the owner (answered 2026-11-28)

- **Q1 - Tag `launcher-v0.43.0` stable from rc.8's commit after a day on the Jelly?** Yes, with `server-v0.96.0` live.
  A release, not this branch's work.
- **Q2 - Which phone for §5?** The Jelly, at a planned time.
- **Q3 - Let the setup download over mobile data (N3)?** Yes: N3 is a must (§3).
- **Q4 - PLAN's "Wi-Fi in Phone Setup" (A or B)?** Closed: (A), the saved network for the QR (0162), is done.

## 9. Design QA (2026-11-28, 2H 4M 6L) and rev 2

- **H1** M3's cause is the race, not the boot copy: §3 M3 rewritten; the fix is `applyCurrent` at every site. Taken with
  one refinement: reading both inside the lock isn't enough by itself (the sync's store and refresh run outside it),
  the call state must be read before the decision.
- **H2** no `policy != null` guard in `hardeningManaged` (fail closed): taken, none added.
- **M-a** M4's retry line covers every `RETRY` cause: "Ikke koblet til ennå. Prøver igjen."; recomputed at every render.
- **M-b** the refused line only while the witness says not enrolled.
- **M-c** N3's shape, server and UI: §3 N3.
- **M-d** §5 row 11 (N3) added; row 7 says adb stays off once the baseline lifts.
- **Low** §0's server version (0.94.0) and the stopgap; the citations (`MdmDeviceAdminReceiver.kt:82`, the gotchas
  bullet by name, `device_api.rs:937` as the override PIN); §2 row 2's missing baseline. One part corrected: "TooOld
  also makes no typed code" doesn't hold - `provisioning.rs:599` is the QR's check; `devices::regenerate_code` makes a
  typed code whatever the launcher check says (§0). That is right as it is: a typed code serves a phone whose Vibb One
  came another way.

## 10. The build (2026-11-28, branch `qr-robust`)

- **M3**: `AppEnforcer.applyCurrent` (`@Synchronized`, placed before `apply`), `callsThenDecision` and
  `PolicyToApply.kind` in `PolicyGate.kt`; `apply`'s bare form takes `calls` and `kind`, and `applyHeld` reads
  `calls ?: CallPolicyStore.state` once, where the hardening is decided, and uses it for the call roles, the fence,
  the PIN lock's `managed`, the screen timeout and the notification rule (the second `ensureLoaded` + read is gone).
  The 23 offline applies in 14 files call `applyCurrent`. The log line: `Apply (<kind>): policy present|none, calls
  <state> (store: <describe()>), managed <b>, baseline <b>`. Tests: `HardeningTest` (the six orders of the sync's two
  writes around the two reads; the old order shown to fail; the source scans), and the scans in `HomeRound7Test`,
  `AndroidLockPlanTest` and `BtPairingPlanTest` follow the new text.
- **M4**: `qrConnectLine` (`ConnectScreen.kt`), `QrExtras.keptState` (`QrKeptState`, no Keystore call), Koble til's
  `qrLine()` after `EnrolWait.lines` and `lastError`; `connect_qr_refused`, `connect_qr_retrying` in nb and en.
  `ConnectScreenTest`.
- **N3**: `ProvisioningPayload.use_mobile_data`, `setup_payload(.., mobile, ..)`, `provisioning_payload(.., use_mobile_data)`,
  `ProvisionForm.network`, `ProvisionQrTemplate.mobile`; the template's two radios, the "Nett" row with its (i), the
  Wi-Fi fields in `.qr-wifi`; `style.css` (`?v=136`); `devices.qr.network*` in nb and en. Tests in
  `tests::provisioning`. Notes: `docs/notes/server/connectivity.md`, `docs/notes/launcher/setup.md`, `hardening.md`,
  `fail-closed.md`.
- Not built: N1 (docs, with the release), N2 (on §5's finding), §2 row 2's baseline at first start (§2).
