package com.kidslauncher.mdm.lock

/*
 * Design 82 W3 (docs/design/82-meldinger-as-an-app.md §9): the per-phone switch "Buzz for a notification". Pure,
 * tested in LockWakeTest. The notices that may wake the screen ([PinLockRuntime.noticeCame]) make the locked phone
 * vibrate once, without a sound - decided before the wake and apart from it: neither the wake's switch, the lit
 * screen nor the proximity read is asked (a pocket is where a buzz matters, and there the screen never lights).
 */

/** The buzz's length: the SMS reaction buzz's (`SmsNotifier`). */
const val NOTICE_BUZZ_MS = 40L

/** At least this long between two notice buzzes (in memory: a new process starts over). */
const val BUZZ_GAP_MS = 10_000L

/** Whether the gap since the last buzz is over ([sinceLastBuzzMs], `null` = none in this process): asked first by
 * `PinLockRuntime.noticeBuzz`, before anything is read from the system, and by [noticeBuzzAllowed]. */
fun noticeBuzzGapOver(sinceLastBuzzMs: Long?): Boolean = sinceLastBuzzMs == null || sinceLastBuzzMs >= BUZZ_GAP_MS

/**
 * Whether a notice that came ([PinLockRuntime.noticeCame]) buzzes: the parent's [switchOn], the lock LOCKED ([mode]),
 * nothing [busy] (the wake's [NoticeBusy]: a call, a ring, the emergency flow, a ringing alarm, the boot's mark, the
 * first minutes of the process), the ringer not silent ([ringerMode]: normal or vibrate), no Do Not Disturb
 * ([interruptionFilter]: all), no silent time rule in force ([ruleSilent]) and at least [BUZZ_GAP_MS] since the last
 * buzz ([sinceLastBuzzMs], `null` = none in this process). A ringer mode or a filter that couldn't be read (`null`)
 * is no buzz.
 */
fun noticeBuzzAllowed(
    switchOn: Boolean,
    mode: LockMode,
    busy: NoticeBusy,
    ringerMode: Int?,
    interruptionFilter: Int?,
    ruleSilent: Boolean,
    sinceLastBuzzMs: Long?,
): Boolean =
    switchOn && mode == LockMode.LOCKED && !busy.any &&
        (ringerMode == RINGER_MODE_NORMAL || ringerMode == RINGER_MODE_VIBRATE) &&
        interruptionFilter == INTERRUPTION_FILTER_ALL && !ruleSilent && noticeBuzzGapOver(sinceLastBuzzMs)
