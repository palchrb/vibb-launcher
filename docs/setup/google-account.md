# Google account on the kid's phone

A Google account on the phone makes Play an app source (Element X, Vipps, Spotify - PLAN.md,
"Play"). The phone stays managed by handy: the account is only for Play, nothing else of it is
used, and Android's backup to Google stays off.

## 1. Which account

A **dedicated adult account owned by the parent** - not the kid's own account, and not a Family
Link account:

- Family Link is ruled out: it is a second parental-control system with its own Google-side
  supervision and data, next to handy, which is the one controller on this phone.
- Google requires Family Link for an account of a child under 13, so a child account isn't an
  option without it.

So: an ordinary account in the parent's name (e.g. "Handy <kid's name>"), with the parent's phone
for recovery and 2-Step Verification. The kid doesn't know the password.

## 2. Account settings (myaccount.google.com, on a computer)

- **Data & privacy**: Web & App Activity off; Timeline (location history) off; YouTube History
  (watch and search) off; Personalised ads off (My Ad Center).
- **Payments**: no payment method (pay.google.com -> Payment methods: none). A paid app is bought
  with a Play gift card on this account, never with a card on file.

## 3. Add the account to the phone

**Preferred with adb: right after `dpm set-device-owner`, before enrolling** (`docs/testing/emulator.md`
§4 and §6b). `set-device-owner` refuses a phone with any account on it, so the account always
comes after it; and before enrolling nothing is blocked yet (the first policy blocks account
changes and starts the kiosk).

**With the provisioning QR there is no "before enrolling":** the QR enrolls the phone by itself,
and its first policy blocks accounts and starts the kiosk at once. Untick "Block adding or removing
accounts" on the device row before scanning, then follow the enrolled-phone steps below.

**On an enrolled phone:**

1. PWA, device page, "Phone hardening": untick **"Block adding or removing accounts"** (it saves
   itself and nudges the phone; launcher Settings -> "Sync now" if in doubt).
2. Phone: launcher Settings (unlock code) -> **"Pause all restrictions"** ("Sett alle
   begrensninger på pause") -> unlock code. Kiosk, time rules and app restrictions are off for up to
   2 hours; the hardening switches stay, which is why step 1 comes first.
3. Phone: Android Settings (in the app list during the pause) -> Passwords, passkeys & accounts
   (older Android: Passwords & accounts, or Accounts) -> Add account -> Google, and sign in. Then sections 4 and 5.
4. Switch "Pause all restrictions" off again, and tick "Block adding or removing accounts" again
   in the PWA.

The **first sign-in needs the parent's 2-step confirmation** (prompt or code on the parent's
phone) - have it at hand.

## 4. Play settings (Play Store -> profile icon -> Settings, during setup)

handy's app list never shows the Play Store outside install mode. Open it with "Install from
Play" (section 8) on an enrolled phone, or from Android Settings -> Apps -> Google Play Store ->
Open before enrolling.

- Authentication -> Require authentication for purchases -> **"For all purchases through Google
  Play on this device"**.
- Family -> **Parental controls** on, with content ratings for the kid's age and a PIN (not one of
  handy's PINs; keep it with the credentials, section 6).
- Network preferences -> **Auto-update apps** on, **over Wi-Fi only** (handy un-suspends the Play
  Store from 02:00 to 04:00 while the screen is off, so Play can update in that window). This is
  Play's own setting - handy's "App updates only on Wi-Fi" on the device page covers only the apps
  from handy's catalog and the launcher. Before launcher design 13 handy's VPN made the whole phone
  "metered", so Play's Wi-Fi-only updates (and other apps' unmetered jobs) never ran behind it; the
  VPN now takes its meteredness from the network below (section 10).
- Profile icon -> **Play Protect** -> settings: "Scan apps with Play Protect" on.

## 5. Turn off the account's own sync

Android Settings -> Passwords, passkeys & accounts -> the Google account -> Account sync: turn off
**everything** (Contacts, Calendar, Gmail, Drive, Keep, ...). The phone book comes from the
server; the calendar is DAVx5's CalDAV account, which is a separate account and isn't affected.

## 6. Factory reset protection (FRP)

After a wipe - from the recovery menu, or the remote wipe on the device page (it doesn't clear the
reset protection, which helps with a lost phone) - Android's setup asks for the Google account that was on the
phone, and won't continue without it. **Keep the account's address, password and 2-Step backup
codes with the override PIN** (and the Play parental-controls PIN). After such a wipe, finish
setup with the account, remove it again in Settings, then `dpm set-device-owner`, then add it back
(section 3). Before a planned reset, remove the account first (section 3, steps 1-3, then remove
instead of add).

## 7. Backup to Google: always off

AOSP switches Android's backup service off when a device owner is set, and only the device owner
can switch it on - handy never does. On every policy apply while the phone is managed, the
launcher checks it and switches it off if anything turned it on; the override PIN and the pause
don't change that, and there is no switch for it in the PWA. The device page shows it in the
"Phone hardening" card: "Backup to Google: off", or a warning if the phone reports it on (nothing
from a launcher that doesn't report it yet). By hand, with adb allowed:
`adb shell bmgr activated` -> "Backup Manager currently deactivated" (`bmgr enabled` answers
"Error: Backup Manager is not activated for user 0").

## 8. Daily use: installing an app from Play

1. Phone: launcher Settings -> **"Install from Play"** ("Installer fra Play") -> unlock code. The
   Play Store opens for 15 minutes (notification with "End now"; not during a time rule's lock).
2. Install the app. It stays hidden on the phone until it is allowed.
3. PWA, device page, "Apps": tick the new app.
4. For **Element X**: on the "Calls & SMS" page, set the contacts' message button to "Element X"
   (per contact, or as the phone's default) and enter each contact's Matrix ID
   (`@name:matrix.org`).

## 9. Open device check: Play-services screens in install mode

Play may hand over to Play services (`com.google.android.gms`) during an install: Play Protect
prompts, terms of service, the purchase/authentication sheet, an account check. With the **"Kiosk
app block"** on ("Play and kiosk" card), those screens may be stopped and end on Android's
`BlockedAppActivity`, because install mode pins only the Play Store (`com.android.vending`).

How to see it (adb needs "Block USB debugging" off, or use the emulator, §6b):

```sh
adb shell dumpsys activity activities | grep -E 'topResumedActivity|mResumedActivity'
adb logcat -d | grep -iE 'BlockedAppActivity|LockTaskController|START u0.*com.google.android.gms'
```

A top activity `com.android.internal.app.BlockedAppActivity` right after Play asked for a
`com.google.android.gms/...` screen is this case. For now: switch "Kiosk app block" off for the
install and on again afterwards. Possible fix (not built): also pin Play services while install
mode is on (next to the Play Store in the kiosk packages, `EnforcementPlan.kt`, `installModePin`).

## 10. Open device check: Wi-Fi-only updates behind handy's VPN (design 13)

With the filter VPN on and the phone on Wi-Fi, the VPN must not be metered; on mobile data it must
be (adb needs "Block USB debugging" off, or use the emulator):

```sh
adb shell dumpsys connectivity | grep -A3 'VPN' | grep -oE 'Capabilities: [^]]*'
```

On Wi-Fi the VPN's capabilities list `NOT_METERED`; on mobile data they don't. Then, with Play's
"over Wi-Fi only" on: a Play update that is due goes in during the night window (02:00-04:00, screen
off) - Play Store -> Manage apps shows nothing pending the next morning.
