package com.kidslauncher.mdm.server

/*
 * Find My Device's `ring` (LocateCommands.ring) - the pure part, tested in FindMyDeviceRingTest.
 *
 * Design 18, QA #2: the ring touches the alarm stream only. Setting the ringer mode as the device
 * owner works (it has notification-policy access) and so ends any Do Not Disturb or bedtime mode,
 * restoring Silent afterwards turns on a manual DND, and raising/restoring STREAM_RING or
 * STREAM_NOTIFICATION (ringer-affected streams) sets the ringer mode internally - restoring 0 left
 * the phone on Vibrate. The alarm stream isn't ringer-affected and plays through DND, which is all
 * the ring needs.
 */

/** `AudioManager.STREAM_ALARM`, duplicated to stay Android-free. */
const val STREAM_ALARM_ID = 4

/** The streams the ring raises to the maximum and puts back afterwards. */
val RING_RAISED_STREAMS: List<Int> = listOf(STREAM_ALARM_ID)

/**
 * The volumes to put back when the ring ends, after a `ring` read [current]: the values saved by
 * the first ring of a run win - a second `ring` while one plays reads the already-raised volumes,
 * which must never become what is restored.
 */
fun ringVolumesToRestore(saved: Map<Int, Int>, current: Map<Int, Int>): Map<Int, Int> = current + saved
