package com.kidslauncher.mdm.server.dto

import kotlinx.serialization.Serializable

/**
 * Body of `POST /api/devices/contact-request` (design 77 §14): the kid's contact request from the phone book's "+".
 * [id] is the request's own 32 lowercase hex characters (the server's key, so a retried POST is one request), [name]
 * the name the kid typed (cleaned, at most 40 characters), [number] normalised on the phone, [matrixId] the kid's
 * optional Matrix ID (left out when there is none), [askedAtMs] when the kid asked. Pinned by
 * `contact_request.json`'s `request` and `request_without_matrix` (byte-identical with `server/testdata/`,
 * `ContactRequestsTest`).
 */
@Serializable
data class ContactRequestBody(
    val id: String,
    val name: String,
    val number: String,
    val matrixId: String? = null,
    val askedAtMs: Long,
)

/**
 * The server's 200 answer (`contact_request.json`'s `answers`): [accepted], or not with [reason] `capped` (three a
 * day, or five open), `invalid` (the server's checks refused it) or `known` (the number is on the phone already, or
 * asked for).
 */
@Serializable
data class ContactRequestAnswer(val accepted: Boolean, val reason: String? = null)

/**
 * One of `call_policy.contact_requests` (design 77 §14, `contact_request.json`'s `policy`): a request of the last 7
 * days and its [state] - `open`, `declined` (the phone shows "Ikke lagt til" once) or `added` (the contact arrives
 * on its own; the row lets the phone forget a request the parent added under another number).
 */
@Serializable
data class PolicyContactRequest(val id: String = "", val state: String = "")
