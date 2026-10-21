# Usage access for Vibb One ("Bruksdata")

Design 57 (`docs/design/57-voip-call-screen-over-lock.md` §6, §7.4). From the gating release (`launcher-v0.36.0`)
a call in an app (WhatsApp, Signal, Element X) shows over the PIN lock only when Vibb One has **Usage access** -
Android's `GET_USAGE_STATS` app-op - and the phone runs Android 15 or newer. Without it the locked phone rings
quietly and the call screen opens after unlocking.

The prep release (`launcher-v0.35.0`, with `server-v0.34.0`) changes nothing for the kid. It declares the
permission, reports the grant (`lock_state.usage_access_granted`) and adds the Settings row, so the parent can
turn it on **before** the gating release arrives. The PWA's Lock Screen & PINs page says "Turn on Usage access for
Vibb One" (an orange Attention row on the phone's summary too) until the phone reports it on.

## Order matters

Grant it only **after** the phone runs `launcher-v0.35.0` or later. On an older Vibb One, which doesn't declare
`PACKAGE_USAGE_STATS`, Android resets the grant at once (`appops set` then `appops get` gave `default` on the
emulator, 2026-10-21). A grant made on 0.35.0 survives later updates.

## Way 1: on the phone, during a pause

1. On the phone: Vibb One's Settings (the Parent PIN), switch on **Pause all restrictions** (Android's own
   Settings is hidden and suspended while the phone is managed).
2. In the same Settings, tap **Calls over the lock** / **Samtaler over låsen**. It opens Android's Usage access
   page for Vibb One (or the list, then pick Vibb One).
3. Switch **Permit usage access** on, go back. The row now reads "On - the call screen can show over the lock".
4. Switch the pause off.

Without Vibb One's row: Android Settings › Apps › Special app access › Usage access › Vibb One › On (during the
pause).

**If the switch is greyed out** (Android 15+ "restricted setting" for an app installed from a file or an untrusted
installer): tap the greyed switch, OK on the dialog, then App info › ⋮ › **Allow restricted settings**, then back
to the switch. The order matters: Android offers "Allow
restricted settings" only after the greyed switch was tapped. Which install paths are guarded (the QR release,
`adb install`, a self-update) is design 57's check 8.

The row's other words: "Off - pause the phone first" (outside a pause - Android's page can't open), "Needs
Android 15" (an Android 14 phone: nothing to grant, see below), "Unknown" (Android didn't answer).

## Way 2: with adb

Only where USB debugging works (Advanced › Restrictions › "Block USB debugging" off on the PWA, or before the
first managed policy):

```sh
adb shell appops set me.vibb.launcher GET_USAGE_STATS allow
adb shell appops get me.vibb.launcher GET_USAGE_STATS    # GET_USAGE_STATS: allow
```

A debug build is `me.vibb.launcher.debug`. `adb shell pm grant me.vibb.launcher android.permission.PACKAGE_USAGE_STATS`
works too (the op's default mode then counts the permission). `appops set` from adb isn't affected by the
restricted-setting guard. The phone reports the change by itself within seconds (it watches the op and syncs).

## Android 14

Nothing to grant: the gating release lets no app call over the lock below Android 15 (the per-package event query
is API 35). The phone reports `voip_over_lock_unsupported`, and the PWA says "Calls over the lock will need
Android 15".

## What Vibb One does with it

In the prep release: nothing but read whether it is on. From the gating release: it reads usage events only while
an app's call is ringing or live over the PIN lock, only that app's events, only for the last seconds, in memory,
never sent or stored (design 57 §0.11, §3.2). The parent can take it back any time on the same page.
