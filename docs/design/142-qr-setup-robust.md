# 142 - The setup QR made robust: what is still open

Status: design note, 2026-11-28, on `main` at 61a70c73e, branch `qr-robust-design`. Docs only. The locked list's item
9 ("QR setup made robust", approved 2026-11-12). An audit of the whole path, then the fixes for what is still open.

Goal: a parent with a stock Android 14-17 phone with Google Play services (the Jelly Star, Android 16) scans the
Phone Setup QR and ends with an enrolled, locked-down kid's phone, without adb; every way it fails shows one short line
and a way forward. For any family, not only ours.

## 0. Short answer

- Most of the path is built and correct in code. The failure stories of 2026-11-20 are fixed (§1).
- **No phone can be set up from the QR today, on any server from 0.93.0 on.** GitHub's "latest" is
  `launcher-v0.41.0` (versionCode 410009, 2026-11-20 08:43 UTC, before the extras fix); design 129b's floor is
  `ENROL_ATTEMPT_FIRST_CODE = 430003` (`server/src/launcher_check.rs:70`), so the QR check answers `TooOld` and the
  Connect page shows no QR (`launcher_check.rs:620-626`, `provision_qr.html:66-67`). The fix is a release, not
  code: a stable `launcher-v0.43.0` (M1).
- The QR has never been run end to end since the fix (M2: the real-device list in §5).
- Two small code fixes: the apply at enrolment that blocks USB debugging (PLAN 1m(b), M3) and Koble til saying
  why the QR didn't connect (M4).

## 1. Already handled (one line each)

| Item | Where |
|---|---|
| The admin extras on a Google phone arrive only at `ADMIN_POLICY_COMPLIANCE` (CloudDPC role holder) | `fe8c497fe` (`qr-compliance-extras`): `ProvisioningActivities.kt:57` hands them to `QrExtras.deliver`, sealed before it returns (`QrExtras.kt:91-94`). |
| Every path enrols through one apply | Compliance (`ProvisioningActivities.kt:57`), `onProfileProvisioningComplete` without a role holder (`MdmDeviceAdminReceiver.kt:590`), the in-app scanner (`QrExtras.applyScanned`), Koble til (`QrExtras.enrolTyped`): one mutex, the code's hash marks it applied (`QrExtras.kt:273-279`), so a double delivery sends the code once. `GET_PROVISIONING_MODE` passes the bundle on (`ProvisioningActivities.kt:31`). |
| "Server address not set" after a failed enrolment | The kept extras are retried at every sync run (`QrExtras.kt:122`); the first-launch reset doesn't touch their file. |
| No way to enrol the same phone entry with a code after a failed QR | Koble til on the phone (`setup/ConnectActivity.kt`, `fe8c497fe`); in the PWA, "Ny oppsettkode" on a never-connected phone's summary (`device_detail.html:109`) and in Connect's "Allerede satt opp" group (`provision_qr.html:130`). |
| The Parent PIN made before the QR not seen by the phone | The cause was the extras bug: no enrolment, so no policy. The PIN goes in the first policy (`handlers/device_api.rs:937`), and since design 114 §5.8 no QR or code is made without it (`provisioning.rs:219`, `:599`). Vibb's own PIN lock is gone (phase D). |
| `LEAVE_ALL_SYSTEM_APPS_ENABLED` | Always true in the QR (`provisioning.rs:109`, `:735`; design 86 §9). |
| A 64-hex Wi-Fi key; the Wi-Fi saved for the QR | Refused (`provisioning.rs:516`); the network saved sealed (`1628e0727`, migration 0162, `provisioning.rs:360`). |
| The QR's launcher checked before a QR shows | Design 86: signer, package, Android version; `TooOld` (129b D13); `NeedsServer` (140 §6); checked again when `releases/latest` moves (`provisioning.rs:215`). |
| Code expiry and reuse | A QR code lives 30 min, once; a new QR or code replaces it (`enrollment.rs:31-32`, `:125`). A code that was used but whose answer got lost is answered again within its life (129b, `device_api.rs`'s `repeat_enrolment`). |
| A phone that can't prove its hardware key | Design 129b: the phone waits with a six-digit number (`EnrolWait`), the Connect page shows the confirm (`provision_qr.html:36`). |
| A phone locked down before it talks to the server | `ProvisioningBaseline.arm` in the compliance step (`ProvisioningBaseline.kt`): no USB debugging, no new users, no safe mode, no user certificates, no unknown sources until the first policy. |
| Play Protect | The Jelly's QR run of 2026-11-20 installed and provisioned 0.41.0 with no block (`docs/notes/server/gotchas.md:13`); the owner has been a verified developer with the packages registered since 2026-11-17. Design 92 (the bootstrap DPC) stays parked. |

## 2. Interrupted setups (what happens, all handled but one line)

| Case | Today |
|---|---|
| Wi-Fi drop or download failure before Device Owner | Android's own error screen ("Try again" or reset). Nothing of ours ran, the code isn't used: scan the same QR again within 30 min. |
| Reboot after Device Owner, before the compliance step | The extras never arrive; Home shows Koble til (Device Owner, not enrolled, `ConnectScreen.kt:21`); the parent types a new code. |
| No network or tailnet at the enrolment | Kept, retried at every sync run (process start, nudges, the 30-min backstop). |
| The retries outlive the 30-min code, or the QR was old when scanned | 401: marked refused (`QrExtras.kt:243`), Koble til shows - **with no word on why** (M4). |
| Process death right after the compliance result | Sealed before the result (`QrExtras.kt:91`), retried at the next start. |

## 3. Must-haves

### M1 - A stable Vibb One new enough to be "latest" (release, no code)

`launcher-v0.43.0` on the rc.8 commit (61a70c73e) once rc.8 has run on the Jelly; `server-v0.96.0` is on the same
commit, so 140's `NeedsServer` passes on 0.96.0 and blocks older servers with its own orange line (as designed). Until
then every family's QR is blocked by `TooOld`, whose line is right ("Vibb One i QR-koden er for gammel"). Owner
question Q1.

### M2 - One end-to-end run on a real phone (owner, §5)

No emulator image has Google's setup wizard, the QR reader or the CloudDPC role holder (2026-11-12 emulator test), so
the fix of 2026-11-20 has only been checked in code. §5 is the list.

### M3 - The apply at enrolment blocks USB debugging (PLAN 1m(b)) - S, launcher

Traced in code (not yet confirmed by a log): Koble til's enrolment starts the setup hold, which applies at once,
before the first sync (`Enrollment.kt:133` -> `RestrictionsPause.startHold` -> `reapply`, `RestrictionsPause.kt:200`)
with `currentPolicyDecision()` = `Apply(null)` (no policy yet). `hardeningManaged(policy?.allowlist,
CallPolicyStore.state.managed)` (`AppEnforcer.kt:432`) can still say managed: the call state comes from
`calls_managed_last`, which the device-protected boot copy puts back after an earlier enrolment on the same install
(`calls/CallPolicyStore.kt:174-199`). Managed with no policy, `hardeningPlan` takes each switch's `defaultOn`
(`Hardening.kt:126`), and `DEBUGGING_FEATURES` is on by default. That fits "2 of 4": the gate's emulators enrol more
than once. On a factory-reset phone the boot copy doesn't exist, and the QR's baseline blocks USB debugging until the
first policy anyway, so it is very likely an emulator-only path; on Koble til after a cut it is real.

Fix: an `Apply(null)` (a phone that never applied a policy) is never managed for hardening - `hardeningManaged` takes
"a policy is enforced" too (`policy != null && (allowlist != null || callsManaged)`); the QR's baseline still covers a
new phone. A `Fallback` always carries a policy, so fail closed is unchanged. One `Log.i` of the inputs at the
hold's apply (managed, policy present, baseline) for the gate. Test: `HardeningTest` with `Apply(null)` and managed
calls. Gate row (Android 14 and 16): enrol twice on one image with "Block USB debugging" off, adb stays up.

### M4 - Koble til says why the QR didn't connect - S, launcher

Koble til after a failed QR shows the empty fields and the hint only (`ConnectActivity.kt:126`). One line in the
existing `connectError` slot, from `QrExtrasStore` (no new state): refused (`appliedRefused()` and not enrolled) or
still retrying (`hasPending()` and nothing running). Typed failures and `EnrolWait`'s lines keep priority.

| Case | nb | en |
|---|---|---|
| The QR's code refused (expired, used, wrong server) | `QR-koden virket ikke. Bruk en ny oppsettkode.` | `The QR code didn't work. Use a new setup code.` |
| The QR's code kept, the server not reached yet | `Får ikke kontakt med serveren. Prøver igjen.` | `Can't reach the server. Trying again.` |

Checked against "Words in the UI": one short line, only when wrong, on the kid's phone; the hint under the fields
already says where the code is. Test: a pure `qrConnectLine(refused, pending, running, enrolled)` in
`ConnectScreenTest`.

## 4. Nice-to-haves

- **N1 - A fork with its own signing key** (XS, docs): Play Protect's provisioning check applies to a DPC that isn't
  registered to a verified developer; families using our release APK share our registration, a family that signs its
  own build doesn't. One line in DEPLOY.md, "Launcher provisioning settings", pointing to design 92.
- **N2 - A WPA3-only network in the QR** (check first): the QR sends `PROVISIONING_WIFI_SECURITY_TYPE = "WPA"`
  (`provisioning.rs:730`); whether a WPA3-only (SAE) router joins is unknown. If §5 row 9 fails, one line behind the
  Wi-Fi field's (i): "Et WPA3-nett: velg nettet på telefonen i stedet." / "A WPA3 network: pick it on the phone
  instead."
- **N3 - `PROVISIONING_USE_MOBILE_DATA`** (XS, server): lets the download run over a SIM without Wi-Fi (Android 10+).
  Owner question Q3; not recommended.

## 5. The real-device list (the Jelly Star on 16, or a spare Google phone on 14/15)

Before: server 0.96.0, a stable 0.43.0 as "latest" (M1), the Parent PIN set, a fresh phone entry. A factory reset
loses the phone's data. A phone fresh from a reset has USB debugging off and the setup wizard can't turn it on, so a
failure before the compliance step leaves only the screen: photograph it.

1. Phone Setup's launcher row: "New phones get Vibb One 0.43.0"; Connect shows a QR.
2. Reset, tap 6 times on the welcome screen, scan. Record each screen; note any Play Protect screen word for word.
3. With the saved Wi-Fi in the QR: the phone joins without asking.
4. It ends on Vibb One; the PWA shows the phone connected within a minute; the setup hold and the flow start.
5. About This Phone: the hardware key, not "Uten maskinvarenøkkel" (if the phone waits with a number: 129b's confirm).
6. Dialer, Messages and the camera are still there (`LEAVE_ALL_SYSTEM_APPS_ENABLED`); language and time zone as set.
7. USB debugging stays blocked until the first policy, then follows the PWA's switch.
8. A QR made more than 30 minutes before the scan: Vibb One installs, Koble til shows M4's line, a typed code enrols.
9. (If the router can) a WPA3-only network in the QR (N2).
10. (A spare phone on 14, if there is one) rows 2-4 again.

Android 17: no device; nothing in M3/M4 is version-dependent (no API above 34).

## 6. Android 14-17

The role-holder path (CloudDPC) is used on Google phones from Android 13 on, so 14-17 alike; the extras reach the
compliance step on each. M3 and M4 use no new API. Checked in code against 14 and 16 (the emulator gates' images);
the setup wizard itself only on the real phone (§5).

## 7. Sizes and order

M1 release (owner). M3 S and M4 S together on one launcher branch, code QA, the M3 gate row on 14 and 16, in the
next launcher RC; M2 after M1 (it checks M4 too if M4 is in that RC). N1 XS with them; N2 only on §5's finding; N3
on Q3.

## 8. Questions for the owner

- **Q1 - Tag `launcher-v0.43.0` stable from rc.8's commit after a day on the Jelly?** Recommended: yes, after
  `server-v0.96.0` is approved and on your server. Until then no family can set up a phone from the QR.
- **Q2 - Which phone for §5?** It needs a factory reset. Recommended: a spare Google phone if you have one (also gives
  14 or 15); else the Jelly at a planned time, with its phone entry kept for the re-setup.
- **Q3 - Let the setup download over mobile data (N3)?** Recommended: no - the QR's saved Wi-Fi covers it, and
  downloads stay on Wi-Fi as elsewhere.
- **Q4 - PLAN's "Wi-Fi in Phone Setup" (A or B)?** (A), the saved network for the QR, is built (0162). Recommended:
  close it as done with (A); (B), the family's networks pushed by the policy, not now.
