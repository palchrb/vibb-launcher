// SPDX-License-Identifier: GPL-3.0-or-later
// Written for Vibb One (design 77 §8.1), not from upstream. GrapheneOS Messaging has a class of this name too
// (2026-06, "Throw/catch MalformedPduException"); this one is our own, with a reason the caller can count.
package com.kidslauncher.mdm.sms.vendor.pdu;

/**
 * The hardened parser's one failure: the bytes are not a PDU we accept. Thrown only by the parser's own checks, so
 * any other exception out of the codec is a bug (the fuzz test holds that line).
 */
public final class MalformedPduException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public enum Reason {
        /** The input is larger than the cap the caller set (the carrier's maximum, else 1 MB). */
        TOO_LARGE,
        /** The input ended inside a value. */
        TRUNCATED,
        /** A length or integer in the PDU is beyond the bytes that remain, or beyond 32 bits. */
        BAD_LENGTH,
        /** More parts than {@link PduParser#MAX_PARTS}. */
        TOO_MANY_PARTS,
        /** More header fields than {@link PduParser#MAX_HEADER_FIELDS}. */
        TOO_MANY_HEADERS,
        /** A multipart nested deeper than {@link PduParser#MAX_DEPTH}. */
        TOO_DEEP,
        /** The structure is inconsistent (an empty multipart/alternative child, a part header that overruns). */
        BAD_STRUCTURE,
    }

    private final Reason mReason;

    public MalformedPduException(Reason reason, String message) {
        super(message);
        mReason = reason;
    }

    public Reason getReason() {
        return mReason;
    }
}
