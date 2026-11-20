# 122 - A short Bluetooth pairing window in the kiosk

Status: **built**, 2026-11-20 (branch `bt-pairing-window`), launcher only. Option A of the 2026-11-20 findings
(Bluetooth headset pairing in the kiosk), which the user approved with its limits: lock task allows whole packages, so
the window opens all of Settings for a few seconds.

## The problem, as seen on the Jelly Star

On the Jelly Star (Android 16, Vibb One 0.41.0), a tap on the Jabra Elite 85t in Kid Settings › Bluetooth produced this:

- `START u0 {act=android.bluetooth.device.action.PAIRING_REQUEST ... cmp=com.android.settings/.bluetooth.BluetoothPairingDialog}`
- That start was replaced at once by `android/com.android.internal.app.BlockedAppActivity`.
- `dumpsys package com.android.settings`: `hidden=false suspended=false`.
- Fast Pair played no part: GMS's `BlePairingRequestReceiver` dropped the broadcast.

The Bluetooth stack sends `ACTION_PAIRING_REQUEST` as an ordered broadcast. This was checked in `BondStateMachine`'s
`sendDisplayPinIntent` on `android14-release`, `android15-release`, `android16-release` and `main`. Settings'
`BluetoothPairingRequest` receiver (priority 0) starts `BluetoothPairingDialog`. That activity needs
`BLUETOOTH_PRIVILEGED`, so neither Vibb nor anyone else can start it. Lock task blocks the start because Settings is not
a lock-task package. On phones where AppEnforcer hides Settings (14, and 16's Google image), its receiver is not in the
broadcast's list at all, because the list is fixed when the broadcast is sent.

Android does not deliver the request again, and Vibb cannot send it again (it is a protected broadcast). So the window
has to be open **before** Settings' receiver runs.

## How it works

The code is `server/BtPairingWindow.kt` (the runtime) and `server/BtPairingPlan.kt` (the pure rules, tested in
`BtPairingPlanTest`).

1. **Arm.** The kid taps a device on Vibb's Bluetooth page. Before `createBond`, the window is armed for that address.
   This happens only when the kiosk is on, the phone is not LOCKED, and no pause or override is running
   (`btPairingArms`). If Settings is hidden, it is unhidden now, so its receiver is in the request's list. It is still
   not pinned, so lock task still blocks it. The arm also writes a DE marker (`bt_pairing_window`, the deadline) and
   registers one receiver on the window's own thread.
2. **The request.** Vibb's receiver has priority 999, ahead of Settings' 0, and gets the ordered `PAIRING_REQUEST`
   first. `btPairingRequest` decides what happens:
   - **OPEN**: the armed device (by address), not past its time, a confirm-only variant (`PASSKEY_CONFIRMATION`,
     `CONSENT`, `OOB_CONSENT`), our page started, not LOCKED, and the kiosk on. Settings goes into the kiosk's list
     through `LockTaskChrome.routeWindow(..., RouteOwner.BT_PAIRING)`, with the shade closed and blocked
     (`routeWindowFeatures`, as for Flymodus). Then `onReceive` returns, and Settings' receiver starts the dialog,
     which is now allowed.
   - **GROWN_UP**: the armed device wants a PIN or a passkey typed, or a code typed on the device itself (`PIN`,
     `PASSKEY`, `PIN_16_DIGITS`, `DISPLAY_*`, anything unknown). No window opens and the broadcast is aborted, so the
     kid never sees lock task's "not available" screen. The arm closes, and the row says "Trenger en voksen" /
     "Needs a grown-up". The bond ends at the stack's own timeout. Pairing such a device is a job for a pause.
   - **END**: the armed device, but the page has stopped, the phone is LOCKED, the kiosk is off, or the time is over.
     The arm closes and the request goes on as before (it is blocked).
   - **PASS**: any other device, or a second request while the window is open. Nothing changes.
3. **Close.** There is one closer, `closeLocked`, and it is idempotent. It runs on whichever of these comes first:
   - the device's bond goes BONDED or NONE (Avbryt in the dialog gives NONE);
   - `ACTION_PAIRING_CANCEL` for the device;
   - our page's `onStop`;
   - the screen goes off;
   - an apply under a time rule, a pause or the override;
   - the deadline: 30 s from the tap while armed, 30 s from the request once open (`BT_PAIRING_ARM_MS`,
     `BT_PAIRING_WINDOW_MS`), timed by a main-thread tick and checked again by every apply.

   The close takes Settings out in two passes (the shade stays blocked until Settings is out, as Q99-H1 requires) and
   hides it again if the arm unhid it (not during a pause or the override, when every app is unhidden). It then clears
   the marker and unregisters the receiver.
4. **A process death.** If the marker is still set at the next start (`Application.initRest`) or before the next sync,
   `putBackIfMarked` runs. It takes the window out of LockTaskChrome and runs an `apply()`, which writes the plan's list
   and features and hides Settings again. This works the way Flymodus' put-back does.

While a window holds Settings, the apply's hide step leaves it unhidden (`settingsHideStep`, under the window's lock),
and Home's list and the install receiver leave Settings' package events alone (`BtPairingWindow.inFlight`, next to
`AirplaneRoute.inFlight`). Locks are always taken in this order: AppEnforcer, then the window's own lock, then
LockTaskChrome. The receiver never waits for the enforcer, so an ordered broadcast is never held up by a running apply.

## What the kid can reach while the window is open

The window is open for at most 30 s, and in practice only until the kid answers the dialog.

- **The dialog.** It has the text, the phonebook switch ("Gi også tilgang til kontakter og anropsloggen"; off by
  default, it applies only to that one headset, and Vibb cannot hide it), Avbryt and Koble til. It has no links, no
  menu and no `startActivity`. Back cancels the pairing, which closes the window.
- **Any other Settings activity.** Something reachable would have to start it, and nothing is:
  - the shade is closed and blocked by the window's features (design 98's measured residual applies: a touch during
    SystemUI's ~300 ms collapse can keep it open);
  - our page has no Settings link;
  - leaving the page (Home, Back) stops it, and that closes the window.
- **While only armed** (tap to request, normally a second or two): Settings is unhidden but not pinned, so lock task
  blocks every Settings start. Settings' pairing notification, which appears when the screen is off, opens only the
  blocked screen.
- **Nothing outside the window changes.** Kiosk off, a pause or the override arm nothing, so the dialog shows as
  before. Every other device's request, and every request without an armed tap, is untouched.

## Versions checked

- **Android 14, 15 and 16 (AOSP).** Settings' receiver and the dialog's manifest entry are the same on all three
  (checked in the findings). The ordered broadcast was checked in the Bluetooth module on all three release branches.
  Lock-task pinning and `BlockedAppActivity` work the same way on 14-17.
- **Android 17.** There is no public source. Settings is never hidden there (compat-fix-1), so the arm's unhide and the
  close's re-hide do nothing, and the pin works as it does on 16. Still owed: the dialog's component on 17.
- **Jelly Star (Unihertz 16).** Settings is not hidden there (case b above), so only the pin matters. The real gate
  is the Jelly Star with the Jabra.

## Tests

- **`BtPairingPlanTest`** covers every rule (the arm, each answer, each closer, the deadlines, the apply's hold, the
  row's line), and by source the glue: the pin's owner, the arm before `createBond`, the priority and the abort, the
  close, the put-back and the hooks.
- **The emulator** (`docs/testing/emulator.md` §6bd, `jelly36`, Android 16) ran with the debug hook in place of a real
  bond. It covered the open and pin, each closer, the timeouts, a process death's put-back, an apply during the
  window, and Settings unreachable after every close.
- **The real gate** is the Jelly Star with the Jabra (below).

## Left and not done

- Whether our page stays started while the dialog shows. The dialog is floating (`Theme.AlertDialog`) and runs in its
  own task, so the page should only pause, not stop. If the page did stop, the window would close at once with
  "Closed: page_stopped" right after "Pairing request, variant 3: OPEN". The Jelly Star check reads exactly this.
- A pair tap during a Flymodus turn-on (under a second, from the shade) finds Settings unhidden by the route, which
  hides it again, so that tap's request can miss Settings' receiver. A second tap works. The other order is safe:
  the route unhides nothing that is already unhidden, so it re-hides nothing either. Each window keeps its own pin
  entry, so neither close ends the other's pin.
- The CompanionDeviceManager route (option C) was not built.
