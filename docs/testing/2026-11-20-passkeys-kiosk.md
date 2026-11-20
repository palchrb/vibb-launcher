# Passkeys from Bitwarden/Vaultwarden in the kiosk, 2026-11-20

PLAN's "Passkeys from Bitwarden/Vaultwarden in the kiosk" (approved 2026-11-19), design 119's emulator gate finding 3
(`docs/testing/2026-11-19-119-emulator-gate.md`): a passkey request in the login window opened Play services' sheet,
and lock task refused it.

Branch `passkeys-kiosk` from main 8383f9c9c. Run 23:48-00:35 UTC, one emulator at a time under `flock -o
~/emulator.lock`, port 5556; 5554 and the user's `jellystar2` not touched.

## Short answer

- **On a phone with Google Play (the Play image on Android 16, and almost certainly the Jelly Star), Android's
  passkey sheet is Play services' own activity.** That holds for every provider, Bitwarden included, and for apps and
  Chrome alike. Google's config overlay (`GoogleConfigOverlay`) sets `config_oemCredentialManagerDialogComponent` to
  `com.google.android.gms/.identitycredentials.ui.CredentialChooserActivity`. Android uses it instead of AOSP's
  `com.android.credentialmanager`.
- **Lock task refuses that sheet because Play services is never pinned.** Passkeys don't work in the kiosk, in an app
  or in the login window. They do work in a pause.
- **Pinning `com.android.credentialmanager` changes nothing there** (measured).
- **Pinning Play services makes passkeys work (measured), but it isn't safe as a standing pin.** It opens Google
  settings, Family Link setup and Google's Help WebView as tasks of their own. Every option is in "Options" below; the
  user decides.
- **Android 14 (first release) has no OEM override.** Its sheet is `com.android.credentialmanager`, so the small fix
  built here (pin the selector Android really uses, never Play services) covers 14, and phones without Google's
  overlay. It does not change the Jelly Star if the Jelly Star has the overlay.

## Rig (scratchpad, not in the repo)

- **Builds.** The branch's debug launcher (`assembleDebug -PwarningsAsErrors=true`, under the build lock).
  - For row 3 only: a local, uncommitted variant that added the packages in a debug file to the kiosk list. It was
    reverted afterwards. The existing debug hook `LockTaskOverrideReceiver` (`docs/testing/emulator.md` §6e) does the
    same and is the one to use next time.
  - The rig's server: main's debug server binary (0.86.1) on a copy of the 119 gate's data, admin 3130 and phones
    3131, bound to 127.0.0.1. Device 2 is `jelly36`.
- **The provider.** Bitwarden 2026.11.1 (the F-Droid build from Bitwarden's GitHub release, SHA-256 checked against
  the release's own file).
  - It used a local **Vaultwarden 1.37.5**. The static musl binary was taken from the `vaultwarden/server:1.37.5-alpine`
    image's layers over the registry API, each layer's digest checked; no Docker.
  - Vaultwarden ran on 127.0.0.1:8443, reached from the emulator as `https://10.0.2.2:8443`.
  - The account was `kid@example.test` on that Vaultwarden alone, created in the Bitwarden app. Bitwarden's
    "check known data breaches" was turned off. No account anywhere else.
  - KeePassDX 4.5.8 (F-Droid) was installed as a second provider, not used for signing in.
  - Bitwarden needs https, so a throwaway CA was used: P-256, 7 days, `CN=Throwaway Emulator Test CA`. It went onto
    `jelly36` as a user CA through Settings, with the screen-lock PIN, before Vibb One became Device Owner. All
    downloads, the CA's keys and Vaultwarden's data are deleted.
- **The WebAuthn page.** A local page (`pk_site.py`) with "Lag passkey" (`navigator.credentials.create`, rp = the
  page's host, a discoverable credential) and "Logg inn med passkey" (`get`, empty allow list).
  - It was served as `https://pk.localhost:8461` (the throwaway CA) through `adb reverse`; Chrome resolves
    `*.localhost` to loopback.
  - `pk.localhost:8461` was added as an own site under Sign-in so the login window opens it.
  - No outside service: webauthn.io was not used.
- **Probes.**
  - "Passkey Probe": plain Java, Android's own `android.credentials.CredentialManager` API, sent as
    `androidx.credentials` sends it on 34+ (create a passkey, get a passkey, get a password).
  - The 119 gate's Login Probe opens the login window as a session-less Custom Tab (`ct`).
- **Phones.**
  - **Android 16:** `jelly36` (Google Play image `BE2A.250530.026.D1`, Chrome 133.0.6943.137, Play services
    26.45.31). It equalled `~/kid-look-1-avd-pre-start` before (22 of 22 sums, the same file list, `jelly36.ini` equal)
    and was restored from it afterwards (the same three checks).
    - Provisioned with the branch's debug launcher as Device Owner and enrolled as device 2.
    - Bitwarden, KeePassDX and both probes were ticked as apps. Bitwarden was made Android's preferred service for
      passwords, passkeys and autofill, during 114's setup hold. The hold was then ended ("Lås igjen").
    - The kiosk was LOCKED with the app block on (`mFlags=127`).
  - **Android 14:** `compat34` (Google APIs image `UE1A.230829.050`, Chrome 113, Play services 23.18.18). It was
    started with `-read-only`, so nothing was saved; its images are unchanged.
- **The emulator crashed once** (qemu exit 139, a SwiftShader segfault) while Google settings opened in row 3's
  safety walk. It was started again on the same state.

## Rows

| # | Row | Android 16 (`jelly36`) | Android 14 (`compat34`) |
|---|---|---|---|
| 0 | Baseline, no kiosk (setup hold): Chrome, Bitwarden as the preferred service | **Works.** Create and get on `https://pk.localhost:8461`: Play services' sheet, then Bitwarden's `CredentialProviderActivity` and `MainActivity` in Chrome's task, then Android's screen-lock prompt as user verification. The page got the credential | not run with Bitwarden |
| 1a | Passkey in an app, kiosk on (Passkey Probe, ticked and pinned) | **Blocked.** Play services' `CredentialChooserActivity` was swapped for `BlockedAppActivity`: "App is not available - Google Play services is not available right now". Bitwarden is never reached | The sheet is `com.android.credentialmanager/.CredentialSelectorActivity`, in the app's task (measured without the kiosk; see row 3b) |
| 1b | Passkey through the login window (gate → Custom Tab) | **Blocked**, the same. The window stays open. The page's request then stays pending: a second tap gives `OperationError: A request is already pending` until the tab is closed | Chrome 113 goes to Play services' FIDO activity directly (the 119 gate) |
| 1c | Which package shows the selector | **Play services** (`CredManIntentHelper: Found enabled oem CredMan UI component`), set by `GoogleConfigOverlay` | **`com.android.credentialmanager`**: 14's framework has no `config_oemCredentialManagerDialogComponent` |
| 2 | What lock task refuses | The sheet's start (no `NEW_TASK`, into the caller's task) meets the app block. `ActivityStartInterceptor` swaps it for `BlockedAppActivity` because Play services isn't a lock-task package. No `LockTaskController` "violation" line: that one is for `NEW_TASK` starts | - |
| 3a | `com.android.credentialmanager` added to the kiosk list | **No change**: still Play services' sheet, still blocked | It is the sheet. The branch's build pins it with the block (`mLockTaskPackages` holds `com.android.credentialmanager`). The end-to-end in the kiosk was not run (below) |
| 3b | Play services added to the kiosk list (experiment only) | **Works**, in the login window and in an app. Login window: the sheet, Bitwarden's unlock (its own PIN) in the tab's task, then the passkey; the page got it. App: the sheet and Bitwarden came up. Bitwarden then refused the probe, which has no Digital Asset Links: "Passkeys not supported for this app", Bitwarden's own check | - |
| 3c | The kiosk's app block off (the PWA's existing switch), Play services not pinned | **Works** in the login window: the sheet and Bitwarden start in the tab's task, which lock task allows without the block | - |
| 4 | Avoiding Play services' sheet | **Not possible** for a device owner (see "Question 4") | Not needed: 14's sheet is AOSP's |

## Logcat (trimmed)

Android 16. The login window's passkey, kiosk on, nothing extra pinned:

```
I CredentialManager: starting executeGetCredential with callingPackage: com.android.chrome
I CredentialManager: Provider session created and being added for: ComponentInfo{com.x8bit.bitwarden/com.x8bit.bitwarden.Autofill.CredentialProviderService}
I CredentialManager: Provider session created and being added for: ComponentInfo{com.google.android.gms/com.google.android.gms.auth.api.credentials.credman.service.RemoteService}
I CredManIntentHelper: Found enabled oem CredMan UI component.com.google.android.gms/.identitycredentials.ui.CredentialChooserActivity
I ActivityTaskManager: START u0 {act=<request id> xflg=0x4 cmp=com.google.android.gms/.identitycredentials.ui.CredentialChooserActivity (has extras)} with LAUNCH_SINGLE_TOP from uid 1000 (realCallingUid=10150)
I ActivityTaskManager: Displayed android/com.android.internal.app.BlockedAppActivity for user 0: +277ms
```

The same from the Passkey Probe (`realCallingUid` the probe's, `triggerTask` the probe's task 16):

```
I PkProbe : start get rp=pk.localhost
I CredManIntentHelper: Found enabled oem CredMan UI component.com.google.android.gms/.identitycredentials.ui.CredentialChooserActivity
I ActivityTaskManager: START u0 {act=<request id> xflg=0x4 cmp=com.google.android.gms/.identitycredentials.ui.CredentialChooserActivity (has extras)} with LAUNCH_SINGLE_TOP from uid 1000 (realCallingUid=10220)
I ActivityManager: Start proc 10795:android:ui/1000 for next-top-activity {android/com.android.internal.app.BlockedAppActivity}
```

Where the component comes from:

```
$ adb shell cmd overlay lookup --verbose android android:string/config_oemCredentialManagerDialogComponent
Best matching is from default configuration of com.google.android.overlay.googleconfig   (/product/overlay/GoogleConfigOverlay.apk)
com.google.android.gms/.identitycredentials.ui.CredentialChooserActivity
```

Row 3b, Play services pinned: the sheet, then the provider in the same task, and the page's result:

```
I ActivityTaskManager: START u0 {act=com.x8bit.bitwarden.credentials.ACTION_UNLOCK_ACCOUNT ... cmp=com.x8bit.bitwarden/.CredentialProviderActivity (has extras)}
I ActivityTaskManager: START u0 {flg=0x24000000 xflg=0x4 cmp=com.x8bit.bitwarden/.MainActivity} ... result code=0
I ActivityTaskManager: START u0 {act=com.x8bit.bitwarden.credentials.ACTION_GET_PASSKEY ... cmp=com.x8bit.bitwarden/.CredentialProviderActivity (has extras)}
pk_site: RESULT got <credential id>
```

Android 14 (no kiosk; the Device Owner's files moved away in the read-only session):

```
$ adb shell cmd overlay lookup android android:string/config_oemCredentialManagerDialogComponent
Error: failed to get the resource ...
I ActivityTaskManager: START u0 {act=<request id> cmp=com.android.credentialmanager/.CredentialSelectorActivity (has extras)} with LAUNCH_SINGLE_TOP from uid 1000 (realCallingUid=10196)
```

## The safety walk

Every button and link, on 16 (Play services' sheet) and on 14 (AOSP's sheet):

| Sheet | Button or link | Where it goes | In the kiosk |
|---|---|---|---|
| 16, sign-in | Close; the passkey row; Continue | closes; Bitwarden (`CredentialProviderActivity` → `MainActivity`, the tab's task) | Bitwarden only if allowed (pinned) |
| 16, sign-in | Sign-in options → "Use a different device" | Play services' `RemoteProxyActivity` → `Fido2FullScreenActivity` ("Use another device", "More options" greyed) → `HybridAuthenticateActivity` (a QR code for a phone's passkey) | Play services only; Cancel goes back to the tab |
| 16, create | "Save another way" → "Other password managers" (KeePassDX, Google Password Manager listed) | `android.settings.CREDENTIAL_PROVIDER` → **Settings** | Settings is hidden on 14-16 → `result code=-91`, nothing opens, and Chrome got `NotReadableError`. On **17**, Settings can't be hidden; the start goes into the task and the block refuses it, since Settings isn't pinned. Without the block it would open (not measured on 17) |
| 16, create | "Another device" | the hybrid QR (above) | Play services only |
| 14, intro | "Learn more" | a text page inside the sheet | nothing outside |
| 14, create | "Save another way" → "Other password managers" | Settings' `AccountDashboardActivity`, in the task | Settings hidden (14-16) or refused by the block (17) |
| 14, create | "Another device" | Play services' `RemoteActivity` → `HybridAuthenticateActivity` | refused unless Play services is pinned |
| 14, create | "Select to unlock", Continue | the provider's own activity (KeePassDX) | only if the provider is an allowed app |
| both | Bitwarden's `MainActivity` during the flow | Bitwarden's whole UI (vault, settings, "Add item") | the parent's choice: Bitwarden is an allowed app |

**AOSP's sheet (`com.android.credentialmanager`) reaches nothing new.** That is why the branch pins it. Its only ways
out are:

- Settings, hidden on 14-16 and refused by the block on 17;
- Play services, refused;
- the provider, which is reachable only when it is allowed.

**Play services is a different matter.** Pinned, any of its exported activities can start, as a task of its own too.
`am start -n com.google.android.gms/.app.settings.GoogleSettingsLink` stood in for "an allowed app starts it":

- **Google services** opened in the kiosk. Its lists:
  - "Recommended": Sign in (add a Google account).
  - "All services": Usage & diagnostics, Autofill & passwords, Passkeys and devices, Phone number sharing, Backup &
    restore, Kids & family, Parental controls, Find Hub, Theft protection, Driving, and more.
- **"Parental controls"** opened Family Link's setup (`kids.settings.DeviceSupervisionOptInActivity`, "Set up parental
  controls").
- **"More options → Help"** opened `googlehelp.helpactivities.HelpActivity`: Google's help centre in a WebView, with
  search and articles.

This was not found from the passkey sheet itself. A pin is not per entry point, though: while Play services is pinned,
whatever starts one of these screens gets through. That includes an allowed app's "Manage your Google account", a Play
services app link (`maps.app.goo.gl` goes to Play services, design 110), and a login page's link.

## Question 4: can Play services' sheet be avoided?

On a Google phone, no. A device owner has none of these levers:

- **The sheet's component** comes from `GoogleConfigOverlay`. A device owner can't disable an overlay
  (`OverlayManager` is signature-only) or another package's component.
- **Hiding Play services** is out: Vibb never restricts Play core (FCM, Element X).
- **Chrome's "another service" setting isn't the lever.** Chrome 133 already sent the request to Android's Credential
  Manager (`executeGetCredential with callingPackage: com.android.chrome`) once Bitwarden was Android's preferred
  service; Chrome's own settings were not touched. Chrome 113 (the 14 image) goes to Play services' FIDO activity in
  any case. The setting decides who answers, not who draws the sheet.
- **The provider order.** Making Bitwarden preferred dropped Google's `PasswordAndPasskeyService` from
  `credential_service`. Play services' `RemoteService` (the "another device" provider) stays, and so does the sheet.

## Options (for the user; nothing of this is built)

1. **Leave it.** Passkeys from Bitwarden work in a pause, and the kid's passwords through Bitwarden's autofill (not
   tested here). The cost: a site or an app that needs a passkey needs the parent.
2. **Pin Play services only while a login window is open** (at most 5 minutes, Chrome's tab in front).
   - It makes passkeys work in the login window: row 3b, measured with a standing pin.
   - The cost: for those minutes every Play services screen above can start. The tab itself only reaches the
     parent's login sites, but a login page's own link to a Play services screen would open.
   - It needs a small design: `computeEnforcementPlan` gains Play services while `loginWindowOpen`, against
     today's "never Play" rule.
   - It does not help an app's own passkey sign-in.
3. **Pin Play services whenever the kiosk is on.** Passkeys work everywhere, but the Google settings, Family Link
   setup and Help WebView above are open from any allowed app. **Not recommended.**
4. **Turn off the app block** (the PWA's existing switch, Advanced). Measured to work in the login window (row 3c).
   - The cost is the block itself, for every app: any app may start any package's screen inside its own task.
   - On Android 17 that includes Settings' credential page from "Other password managers" (by reasoning, not measured).
   - **Not recommended.**
5. **A per-request pin can't work.** Android blocks the sheet before Vibb could learn that one is coming.

**Recommendation:** 1 for now, and 2 only if a real login the kid needs depends on a passkey.

What would change this: Google moving the sheet out of Play services' activity, or a phone without Google's
overlay. Recheck with the `cmd overlay lookup` line below on a new Android or Play services version.

## What was built

`HelperKind.CREDENTIAL_SELECTOR` (`server/LockTaskHelpers.kt`, `AppEnforcer.resolveHelpers`).

- **What it pins.** With the kiosk's app block on, Vibb pins the package Android itself uses for the Credential
  Manager sheet. Pure `credentialSelectorPackage` reads AOSP's rule: the OEM's component when set and enabled, else
  `config_credentialManagerDialogComponent`. The two strings come from `Resources.getSystem()`, as
  `config_recentsComponentName` already is.
- **It goes through `lockTaskHelpers`**, so the same rules hold: system apps only, never Settings or the camera,
  never Play core.
- **Where it changes something:**
  - On Android 14 it is `com.android.credentialmanager`, now pinned (measured in `mLockTaskPackages` on `compat34`).
  - On a phone with Google's overlay it resolves to Play services, which is dropped: nothing changes there.
  - Android 15-17 phones without Google's overlay get AOSP's sheet pinned.
- **No SDK gate**: the strings exist from 34 on, and a missing one names nothing.
- **Tests.** Two new rows in `LockTaskHelpersTest`:
  - the selector by AOSP's rule (14, the Play image, a disabled OEM component, blank and malformed strings);
  - pinned only with the block, never Play services, never a non-system app or Settings.
- **The launcher suite** (`assembleDebug testDebugUnitTest -PwarningsAsErrors=true`): 2857 tests, 0 failed.

**Not run:**

- **The end-to-end on 14 in the kiosk.** `compat34`'s stored enrolment couldn't reach the rig's server: "Command
  stream connection failed", although the shell reached 10.0.2.2:3131. So no newly installed app could be let out. The
  build's own list was checked instead.
- **The final build on 16.** It resolves to Play services and drops it (unit test). On 16 the run used the experiment
  variant.

## Owed on the Jelly Star

- **Which sheet Android uses**, by either of these:
  - `adb shell cmd overlay lookup android android:string/config_oemCredentialManagerDialogComponent`;
  - logcat's `CredManIntentHelper` line during a passkey attempt.

  If it names Play services, as expected for a Google phone, the build changes nothing there and the options above
  stand. If it names nothing, the branch's pin should make passkeys work: try a Bitwarden passkey in the login window.
- **Whether the parent can set Bitwarden as the preferred service in a pause.** Here it was set before Vibb was Device
  Owner. The managed `compat34` carries `DISALLOW_CONFIG_CREDENTIALS`, which may gray out that Settings page.
- **Android 17**, and Bitwarden's autofill for passwords in the kiosk.

## The Jelly Star, 2026-11-20 (0.41.0), and the fix (`jelly-round-2`)

- **Found on the phone:** Android 16 started `com.android.credentialmanager/.CredentialSelectorActivity` (AOSP's
  sheet, no Google overlay), and lock task swapped it for `BlockedAppActivity`. The log's "Kiosk app block helpers"
  had `CREDENTIAL_SELECTOR=null`: the OEM string is the placeholder "com.android.config oem CredentialManager Dialog
  Component" (no "/"), and the platform's string gave nothing usable to the launcher either.
- **Fix:** `credentialSelectorPackage` takes an `installedSystem` check. When neither config names a component whose
  package is an installed system app, the selector is `com.android.credentialmanager` if that is an installed system
  app. The forbidden and Play-core rules still apply after it (`lockTaskHelpers`), so Play services is never pinned.
- **The log:** the "Kiosk app block helpers" line now ends with both raw config values
  (`config_oemCredentialManagerDialogComponent="..."`, `config_credentialManagerDialogComponent=...`; `<no resource>`
  or `<unreadable: ...>` when the launcher can't read one), once per apply.
- **Tests:** `LockTaskHelpersTest`'s fallback row: the Jelly Star's placeholder, both configs unreadable, a malformed
  string, a config naming a package that isn't installed, AOSP's selector missing, and the fallback through the
  forbidden rule.
- **The new log line on `jellykeys36`** (Android 16 Play image, 12:35 UTC): `config_oemCredentialManagerDialogComponent=
  "com.google.android.gms/.identitycredentials.ui.CredentialChooserActivity", config_credentialManagerDialogComponent=
  <no resource>` - the platform's string has no resource of that name on 16, so on the Jelly Star (OEM placeholder)
  neither named anything, which is the `null`. Here it still resolves to Play services (never pinned, as before);
  `com.android.credentialmanager` is an installed system app on the image.
- **Owed on the Jelly Star:** after the next release, the log line's two raw values, and a Bitwarden passkey in the
  login window with the kiosk on.
