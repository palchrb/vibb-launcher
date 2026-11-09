package com.kidslauncher.mdm.server.dto

import kotlinx.serialization.Serializable

/**
 * `StatusReportRequest.smsState` (design 77 §7) - what the message engine did, never what a message said. No defaults
 * on purpose: every field is always sent (the server keeps them through its known keys, `SMS_STATE_FIELDS`; the shape
 * is `sms_contract.json`'s `report`). Counters are cumulative, like `call_state`'s, so a failed report loses nothing.
 * [modeApplied] is "off", "managed", "unmanaged" or "unknown" (the rules can't be read).
 */
@Serializable
data class SmsState(
    val roleHeld: Boolean,
    val modeApplied: String,
    val received: Long,
    val sent: Long,
    val blocked: SmsBlockedCounts,
    val held: Long,
    val released: Long,
    val expired: Long,
    val parseFailures: Long,
    val mmsWaiting: Long,
    val mmsDataAllowed: Boolean,
    val mmscKnown: Boolean,
    val lastError: String?,
    val ownNumbers: List<SmsOwnNumber>,
    val holdFull: SmsHoldFull,
)

/** Blocked messages by sender class, and those over a hold cap (D16). */
@Serializable
data class SmsBlockedCounts(
    val number: Long,
    val short: Long,
    val alphanumeric: Long,
    val malformed: Long,
    val empty: Long,
    val group: Long,
    val badLocation: Long,
    val overCap: Long,
)

/** A SIM's own number as the SIM knows it (D12); `null` when it doesn't. */
@Serializable
data class SmsOwnNumber(val slot: Int, val number: String?)

/** D16: whether each hold pool is full. */
@Serializable
data class SmsHoldFull(val numbers: Boolean, val names: Boolean)

/** `POST /api/devices/sms-held` (design 77 §7): at most 50 rows - who and when, never what. */
@Serializable
data class SmsHeldBody(val rows: List<SmsHeldRow>)

@Serializable
data class SmsHeldRow(
    /** 32 lowercase hex, the phone's. */
    val id: String,
    /** Epoch milliseconds. */
    val at: Long,
    /** "sms" or "mms". */
    val kind: String,
    val senderClass: String,
    /** Cleaned by its class before it leaves the phone (QA R2-M2). */
    val sender: String,
    val expiresAt: Long,
    /** "held", "released", "expired", "dropped" or "blocked". */
    val state: String,
)

/** The 2xx answer: rows taken; [capped] past the server's 500 rows a phone in 30 days. */
@Serializable
data class SmsHeldAnswer(val accepted: Int = 0, val capped: Boolean = false)

/** The 400 answer: which row ([refused], its index) the intake refused and why. */
@Serializable
data class SmsHeldRefusal(val refused: Int = -1, val reason: String = "")
