package com.kidslauncher.mdm.server.dto

import kotlinx.serialization.Serializable

/**
 * Body of `POST /api/devices/time-request` (design r13 §2.5): the kid tapped "Be om mer tid" in the
 * anchor's notification. [id] is the request's own 32 lowercase hex characters (the server's key, so
 * a retried POST is one event), [askedAtMs] when the kid asked. Nothing else - where the phone stands
 * the server reads from its own last report. Pinned by `time_request.json`'s `request`
 * (byte-identical with `server/testdata/`, `TimeRequestsTest`).
 */
@Serializable
data class TimeRequestBody(val id: String, val askedAtMs: Long)

/**
 * The server's 200 answer (`time_request.json`'s `answers`): [accepted], or not with [reason]
 * `capped` (the day's three were asked already - the server's cap wins) or `stale` (the day it was
 * asked is over on the server).
 */
@Serializable
data class TimeRequestAnswer(val accepted: Boolean, val reason: String? = null)
