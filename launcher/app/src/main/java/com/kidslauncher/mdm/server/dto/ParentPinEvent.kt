package com.kidslauncher.mdm.server.dto

import kotlinx.serialization.Serializable

/**
 * One use of the parent PIN on the phone (design 24 §3): [kind] `unlocked`, `wrong` (tries 1-4 of
 * a round, [failures]) or `lockout` (the 5th, [level] and [untilMs]); [entry] where it was typed
 * (`ParentPinEntry.wire`); [atMs] the phone's wall clock; [untilMs] the end of the window an
 * `unlocked` started (override, pause, install mode). [id]: 128 random bits, 32 lowercase hex
 * characters. Never digits, a length or other PIN material. No defaults: every key is always
 * sent, nulls included (the literal is pinned in PolicyResponseCompatTest and on the server).
 * Also how ParentPinStore queues them (`events_v1`).
 */
@Serializable
data class ParentPinEvent(
    val id: String,
    val kind: String,
    val entry: String,
    val atMs: Long,
    val untilMs: Long?,
    val failures: Int?,
    val level: Int?,
)

/** Body of `POST /api/devices/pin-events`. */
@Serializable
data class ParentPinEventBatch(val events: List<ParentPinEvent>)
