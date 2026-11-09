/*
 * Copyright (C) 2007-2008 Esmertec AG.
 * Copyright (C) 2007-2008 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Modified for Vibb One (palchrb/vibb-launcher), 2026: vendored from AOSP Messaging
 * (de315b762312dd1a5d2bbd16e62ef2bd123f61e5) into com.kidslauncher.mdm.sms.vendor.pdu;
 * pure JVM and hardened (design 77 §8.1); every change is listed in README.md.
 * See README.md in this package.
 */

package com.kidslauncher.mdm.sms.vendor.pdu;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.UnsupportedEncodingException;
import java.util.Arrays;

public class PduParser {
    /**
     * The log tag.
     */
    private static final String LOG_TAG = "PduParser";

    private static final boolean LOCAL_LOGV = false;

    /**
     * The next are WAP values defined in WSP specification.
     */
    private static final int QUOTE = 127;

    private static final int LENGTH_QUOTE = 31;

    private static final int TEXT_MIN = 32;

    private static final int TEXT_MAX = 127;

    private static final int SHORT_INTEGER_MAX = 127;

    private static final int SHORT_LENGTH_MAX = 30;

    private static final int LONG_INTEGER_LENGTH_MAX = 8;

    private static final int QUOTED_STRING_FLAG = 34;

    private static final int END_STRING_FLAG = 0x00;

    //The next two are used by the interface "parseWapString" to
    //distinguish Text-String and Quoted-String.
    private static final int TYPE_TEXT_STRING = 0;

    private static final int TYPE_QUOTED_STRING = 1;

    private static final int TYPE_TOKEN_STRING = 2;

    /**
     * Specify the part position.
     */
    private static final int THE_FIRST_PART = 0;

    private static final int THE_LAST_PART = 1;

    /** Vibb One (H1): the default input cap, 1 MB (design 77 §8.1); the caller passes the carrier's maximum. */
    public static final int DEFAULT_MAX_PDU_BYTES = 1024 * 1024;

    /** Vibb One (H9): at most this many parts in a body. */
    public static final int MAX_PARTS = 20;

    /** Vibb One (H9): at most this many header fields (the PDU's and every part's) in one PDU. */
    public static final int MAX_HEADER_FIELDS = 300;

    /** Vibb One (H8): a multipart/alternative part may hold one level of parts, no deeper. */
    public static final int MAX_DEPTH = 1;

    /** Vibb One (H3): a uintvar is at most 5 octets (WAP-230-WSP 8.1.2). */
    private static final int UINTVAR_MAX_OCTETS = 5;

    /**
     * The pdu data.
     */
    private ByteArrayInputStream mPduDataStream = null;

    /**
     * Store pdu headers
     */
    private PduHeaders mHeaders = null;

    /**
     * Store pdu parts.
     */
    private PduBody mBody = null;

    /**
     * Store the "type" parameter in "Content-Type" header field.
     * Vibb One (H12): per parser, not static (upstream's static leaked one message's
     * parameter into the next parse and was not thread-safe).
     */
    private byte[] mTypeParam = null;

    /**
     * Store the "start" parameter in "Content-Type" header field.
     */
    private byte[] mStartParam = null;

    /** Vibb One (H1): the input's length and the cap it must be within. */
    private final int mPduLength;

    private final int mMaxBytes;

    /** Vibb One (H9): header fields seen so far, the PDU's and its parts'. */
    private int mHeaderCount = 0;

    /**
     * Whether to parse content-disposition part header
     */
    private final boolean mParseContentDisposition;

    /**
     * Constructor.
     *
     * @param pduDataStream pdu data to be parsed
     * @param parseContentDisposition whether to parse the Content-Disposition part header
     */
    public PduParser(byte[] pduDataStream, boolean parseContentDisposition) {
        this(pduDataStream, parseContentDisposition, DEFAULT_MAX_PDU_BYTES);
    }

    /**
     * Vibb One (H1): a parser that refuses input longer than maxBytes before parsing anything.
     */
    public PduParser(byte[] pduDataStream, boolean parseContentDisposition, int maxBytes) {
        mPduDataStream = (pduDataStream == null) ? null : new ByteArrayInputStream(pduDataStream);
        mPduLength = (pduDataStream == null) ? 0 : pduDataStream.length;
        mMaxBytes = maxBytes;
        mParseContentDisposition = parseContentDisposition;
    }

    /**
     * Parse the pdu.
     *
     * @return the pdu structure if parsing successfully.
     * null if parsing error happened or mandatory fields are not set.
     */
    public GenericPdu parse() {
        try {
            return parseOrThrow();
        } catch (MalformedPduException e) {
            return null;
        }
    }

    /**
     * Vibb One (H17): parse() with the reason. Returns the pdu, or null where upstream did
     * (unsupported type, mandatory header missing); throws MalformedPduException when the bytes
     * break one of the hardening checks. Never throws anything else for any input (the fuzz test).
     */
    public GenericPdu parseOrThrow() {
        if (mPduDataStream == null) {
            return null;
        }
        if (mPduLength > mMaxBytes) {
            throw new MalformedPduException(MalformedPduException.Reason.TOO_LARGE,
                    "PDU larger than " + mMaxBytes);
        }

        /* parse headers */
        mHeaders = parseHeaders(mPduDataStream);
        if (null == mHeaders) {
            // Parse headers failed.
            return null;
        }

        /* get the message type */
        int messageType = mHeaders.getOctet(PduHeaders.MESSAGE_TYPE);

        /* check mandatory header fields */
        if (false == checkMandatoryHeader(mHeaders)) {
            log("check mandatory headers failed!");
            return null;
        }
        /*
         * Get retrieve status. If the header is not there, assuming it is OK status.
         * Some carriers may choose to not send this header.
         */
        int retrieveStatus = mHeaders.hasHeader(PduHeaders.RETRIEVE_STATUS) ?
                mHeaders.getOctet(PduHeaders.RETRIEVE_STATUS) : PduHeaders.RETRIEVE_STATUS_OK;

        if ((PduHeaders.MESSAGE_TYPE_SEND_REQ == messageType) ||
                (PduHeaders.MESSAGE_TYPE_RETRIEVE_CONF == messageType &&
                retrieveStatus == PduHeaders.RETRIEVE_STATUS_OK)) {
            /* need to parse the parts */
            mBody = parseParts(mPduDataStream, 0);
            if (null == mBody) {
                // Parse parts failed.
                return null;
            }
        }

        switch (messageType) {
            case PduHeaders.MESSAGE_TYPE_SEND_REQ:
                if (LOCAL_LOGV) {
                    Log.v(LOG_TAG, "parse: MESSAGE_TYPE_SEND_REQ");
                }
                SendReq sendReq = new SendReq(mHeaders, mBody);
                return sendReq;
            case PduHeaders.MESSAGE_TYPE_SEND_CONF:
                if (LOCAL_LOGV) {
                    Log.v(LOG_TAG, "parse: MESSAGE_TYPE_SEND_CONF");
                }
                SendConf sendConf = new SendConf(mHeaders);
                return sendConf;
            case PduHeaders.MESSAGE_TYPE_NOTIFICATION_IND:
                if (LOCAL_LOGV) {
                    Log.v(LOG_TAG, "parse: MESSAGE_TYPE_NOTIFICATION_IND");
                }
                NotificationInd notificationInd =
                        new NotificationInd(mHeaders);
                return notificationInd;
            case PduHeaders.MESSAGE_TYPE_NOTIFYRESP_IND:
                if (LOCAL_LOGV) {
                    Log.v(LOG_TAG, "parse: MESSAGE_TYPE_NOTIFYRESP_IND");
                }
                NotifyRespInd notifyRespInd =
                        new NotifyRespInd(mHeaders);
                return notifyRespInd;
            case PduHeaders.MESSAGE_TYPE_RETRIEVE_CONF:
                if (LOCAL_LOGV) {
                    Log.v(LOG_TAG, "parse: MESSAGE_TYPE_RETRIEVE_CONF");
                }
                RetrieveConf retrieveConf =
                        new RetrieveConf(mHeaders, mBody);
                if (retrieveStatus != PduHeaders.RETRIEVE_STATUS_OK) {
                    // For failure only no need to check content type
                    return retrieveConf;
                }
                byte[] contentType = retrieveConf.getContentType();
                if (null == contentType) {
                    return null;
                }
                String ctTypeStr = new String(contentType);
                if (ctTypeStr.equals(ContentType.MMS_MULTIPART_MIXED)
                        || ctTypeStr.equals(ContentType.MMS_MULTIPART_RELATED)
                        || ctTypeStr.equals(ContentType.MMS_MULTIPART_ALTERNATIVE)) {
                    // The MMS content type must be "application/vnd.wap.multipart.mixed"
                    // or "application/vnd.wap.multipart.related"
                    // or "application/vnd.wap.multipart.alternative"
                    return retrieveConf;
                } else if (ctTypeStr.equals(ContentType.MMS_MULTIPART_ALTERNATIVE)) {
                    // "application/vnd.wap.multipart.alternative"
                    // should take only the first part.
                    PduPart firstPart = mBody.getPart(0);
                    mBody.removeAll();
                    mBody.addPart(0, firstPart);
                    return retrieveConf;
                }
                return null;
            case PduHeaders.MESSAGE_TYPE_ACKNOWLEDGE_IND:
                if (LOCAL_LOGV) {
                    Log.v(LOG_TAG, "parse: MESSAGE_TYPE_ACKNOWLEDGE_IND");
                }
                AcknowledgeInd acknowledgeInd =
                        new AcknowledgeInd(mHeaders);
                return acknowledgeInd;
            // Vibb One (H15): m-delivery-ind, m-read-orig-ind and m-read-rec-ind are not parsed.
            default:
                log("Parser doesn't support this message type in this version!");
                return null;
        }
    }

    /**
     * Parse pdu headers.
     *
     * @param pduDataStream pdu data input stream
     * @return headers in PduHeaders structure, null when parse fail
     */
    protected PduHeaders parseHeaders(ByteArrayInputStream pduDataStream) {
        if (pduDataStream == null) {
            return null;
        }
        boolean keepParsing = true;
        PduHeaders headers = new PduHeaders();

        while (keepParsing && (pduDataStream.available() > 0)) {
            countHeaderField(); // Vibb One (H9)
            pduDataStream.mark(1);
            int headerField = extractByteValue(pduDataStream);
            /* parse custom text header */
            if ((headerField >= TEXT_MIN) && (headerField <= TEXT_MAX)) {
                pduDataStream.reset();
                byte[] bVal = parseWapString(pduDataStream, TYPE_TEXT_STRING);
                if (LOCAL_LOGV) {
                    Log.v(LOG_TAG, "TextHeader: " + new String(bVal));
                }
                /* we should ignore it at the moment */
                continue;
            }
            switch (headerField) {
                case PduHeaders.MESSAGE_TYPE: {
                    int messageType = extractByteValue(pduDataStream);
                    if (LOCAL_LOGV) {
                        Log.v(LOG_TAG, "parseHeaders: messageType: " + messageType +
                                " (" + Integer.toHexString(headerField) + ")");
                    }
                    switch (messageType) {
                        // We don't support these kind of messages now.
                        case PduHeaders.MESSAGE_TYPE_FORWARD_REQ:
                        case PduHeaders.MESSAGE_TYPE_FORWARD_CONF:
                        case PduHeaders.MESSAGE_TYPE_MBOX_STORE_REQ:
                        case PduHeaders.MESSAGE_TYPE_MBOX_STORE_CONF:
                        case PduHeaders.MESSAGE_TYPE_MBOX_VIEW_REQ:
                        case PduHeaders.MESSAGE_TYPE_MBOX_VIEW_CONF:
                        case PduHeaders.MESSAGE_TYPE_MBOX_UPLOAD_REQ:
                        case PduHeaders.MESSAGE_TYPE_MBOX_UPLOAD_CONF:
                        case PduHeaders.MESSAGE_TYPE_MBOX_DELETE_REQ:
                        case PduHeaders.MESSAGE_TYPE_MBOX_DELETE_CONF:
                        case PduHeaders.MESSAGE_TYPE_MBOX_DESCR:
                        case PduHeaders.MESSAGE_TYPE_DELETE_REQ:
                        case PduHeaders.MESSAGE_TYPE_DELETE_CONF:
                        case PduHeaders.MESSAGE_TYPE_CANCEL_REQ:
                        case PduHeaders.MESSAGE_TYPE_CANCEL_CONF:
                            return null;
                    }
                    try {
                        headers.setOctet(messageType, headerField);
                    } catch (InvalidHeaderValueException e) {
                        log("Set invalid Octet value: " + messageType +
                                " into the header filed: " + headerField);
                        return null;
                    } catch (RuntimeException e) {
                        log(headerField + "is not Octet header field!");
                        return null;
                    }
                    break;
                }
                /* Octect value */
                case PduHeaders.REPORT_ALLOWED:
                case PduHeaders.ADAPTATION_ALLOWED:
                case PduHeaders.DELIVERY_REPORT:
                case PduHeaders.DRM_CONTENT:
                case PduHeaders.DISTRIBUTION_INDICATOR:
                case PduHeaders.QUOTAS:
                case PduHeaders.READ_REPORT:
                case PduHeaders.STORE:
                case PduHeaders.STORED:
                case PduHeaders.TOTALS:
                case PduHeaders.SENDER_VISIBILITY:
                case PduHeaders.READ_STATUS:
                case PduHeaders.CANCEL_STATUS:
                case PduHeaders.PRIORITY:
                case PduHeaders.STATUS:
                case PduHeaders.REPLY_CHARGING:
                case PduHeaders.MM_STATE:
                case PduHeaders.RECOMMENDED_RETRIEVAL_MODE:
                case PduHeaders.CONTENT_CLASS:
                case PduHeaders.RETRIEVE_STATUS:
                case PduHeaders.STORE_STATUS:
                    /**
                     * The following field has a different value when
                     * used in the M-Mbox-Delete.conf and M-Delete.conf PDU.
                     * For now we ignore this fact, since we do not support these PDUs
                     */
                case PduHeaders.RESPONSE_STATUS: {
                    int value = extractByteValue(pduDataStream);
                    if (LOCAL_LOGV) {
                        Log.v(LOG_TAG, "parseHeaders: headerField: " + headerField +
                                " (" + Integer.toHexString(headerField) + ") Octect value: " +
                                value);
                    }

                    try {
                        headers.setOctet(value, headerField);
                    } catch (InvalidHeaderValueException e) {
                        log("Set invalid Octet value: " + value +
                                " into the header filed: " + headerField);
                        return null;
                    } catch (RuntimeException e) {
                        log(headerField + "is not Octet header field!");
                        return null;
                    }
                    break;
                }

                /* Long-Integer */
                case PduHeaders.DATE:
                case PduHeaders.REPLY_CHARGING_SIZE:
                case PduHeaders.MESSAGE_SIZE: {
                    try {
                        long value = parseLongInteger(pduDataStream);
                        if (LOCAL_LOGV) {
                            Log.v(LOG_TAG, "parseHeaders: headerField: " + headerField +
                                    " (" + Integer.toHexString(headerField) + ") longint value: " +
                                    value);
                        }
                        headers.setLongInteger(value, headerField);
                    } catch (MalformedPduException e) {
                        throw e; // Vibb One (H16): keep the reason
                    } catch (RuntimeException e) {
                        log(headerField + "is not Long-Integer header field!");
                        return null;
                    }
                    break;
                }

                /* Integer-Value */
                case PduHeaders.MESSAGE_COUNT:
                case PduHeaders.START:
                case PduHeaders.LIMIT: {
                    try {
                        long value = parseIntegerValue(pduDataStream);
                        if (LOCAL_LOGV) {
                            Log.v(LOG_TAG, "parseHeaders: headerField: " + headerField +
                                    " (" + Integer.toHexString(headerField) + ") integer value: " +
                                    value);
                        }
                        headers.setLongInteger(value, headerField);
                    } catch (MalformedPduException e) {
                        throw e; // Vibb One (H16): keep the reason
                    } catch (RuntimeException e) {
                        log(headerField + "is not Long-Integer header field!");
                        return null;
                    }
                    break;
                }

                /* Text-String */
                case PduHeaders.TRANSACTION_ID:
                case PduHeaders.REPLY_CHARGING_ID:
                case PduHeaders.AUX_APPLIC_ID:
                case PduHeaders.APPLIC_ID:
                case PduHeaders.REPLY_APPLIC_ID:
                    /**
                     * The next three header fields are email addresses
                     * as defined in RFC2822,
                     * not including the characters "<" and ">"
                     */
                case PduHeaders.MESSAGE_ID:
                case PduHeaders.REPLACE_ID:
                case PduHeaders.CANCEL_ID:
                    /**
                     * The following field has a different value when
                     * used in the M-Mbox-Delete.conf and M-Delete.conf PDU.
                     * For now we ignore this fact, since we do not support these PDUs
                     */
                case PduHeaders.CONTENT_LOCATION: {
                    byte[] value = parseWapString(pduDataStream, TYPE_TEXT_STRING);
                    if (null != value) {
                        try {
                            if (LOCAL_LOGV) {
                                Log.v(LOG_TAG, "parseHeaders: headerField: " + headerField +
                                        " (" + Integer.toHexString(headerField) + ") string value: "
                                        +
                                        new String(value));
                            }
                            headers.setTextString(value, headerField);
                        } catch (NullPointerException e) {
                            log("null pointer error!");
                        } catch (RuntimeException e) {
                            log(headerField + "is not Text-String header field!");
                            return null;
                        }
                    }
                    break;
                }

                /* Encoded-string-value */
                case PduHeaders.SUBJECT:
                case PduHeaders.RECOMMENDED_RETRIEVAL_MODE_TEXT:
                case PduHeaders.RETRIEVE_TEXT:
                case PduHeaders.STATUS_TEXT:
                case PduHeaders.STORE_STATUS_TEXT:
                    /* the next one is not support
                     * M-Mbox-Delete.conf and M-Delete.conf now */
                case PduHeaders.RESPONSE_TEXT: {
                    EncodedStringValue value =
                            parseEncodedStringValue(pduDataStream);
                    if (null != value) {
                        try {
                            if (LOCAL_LOGV) {
                                Log.v(LOG_TAG, "parseHeaders: headerField: " + headerField +
                                        " (" + Integer.toHexString(headerField)
                                        + ") encoded string: " +
                                        value.getString());
                            }
                            headers.setEncodedStringValue(value, headerField);
                        } catch (NullPointerException e) {
                            log("null pointer error!");
                        } catch (RuntimeException e) {
                            log(headerField + "is not Encoded-String-Value header field!");
                            return null;
                        }
                    }
                    break;
                }

                /* Addressing model */
                case PduHeaders.BCC:
                case PduHeaders.CC:
                case PduHeaders.TO: {
                    EncodedStringValue value =
                            parseEncodedStringValue(pduDataStream);
                    if (null != value) {
                        // Vibb One (H14): the address is kept whole, "/TYPE=PLMN" included
                        // (upstream cut it at the first '/'); the caller judges the type (D10).

                        try {
                            headers.appendEncodedStringValue(value, headerField);
                        } catch (NullPointerException e) {
                            log("null pointer error!");
                        } catch (RuntimeException e) {
                            log(headerField + "is not Encoded-String-Value header field!");
                            return null;
                        }
                    }
                    break;
                }

                /* Value-length
                 * (Absolute-token Date-value | Relative-token Delta-seconds-value) */
                case PduHeaders.DELIVERY_TIME:
                case PduHeaders.EXPIRY:
                case PduHeaders.REPLY_CHARGING_DEADLINE: {
                    /* parse Value-length */
                    int valueEnd = valueEnd(pduDataStream); // Vibb One (L8)

                    /* Absolute-token or Relative-token */
                    int token = extractByteValue(pduDataStream);

                    /* Date-value or Delta-seconds-value */
                    long timeValue;
                    try {
                        timeValue = parseLongInteger(pduDataStream);
                    } catch (MalformedPduException e) {
                        throw e; // Vibb One (H16): keep the reason
                    } catch (RuntimeException e) {
                        log(headerField + "is not Long-Integer header field!");
                        return null;
                    }
                    endValue(pduDataStream, valueEnd); // Vibb One (L8)
                    if (PduHeaders.VALUE_RELATIVE_TOKEN == token) {
                        /* need to convert the Delta-seconds-value
                         * into Date-value */
                        timeValue = System.currentTimeMillis() / 1000 + timeValue;
                    }

                    try {
                        if (LOCAL_LOGV) {
                            Log.v(LOG_TAG, "parseHeaders: headerField: " + headerField +
                                    " (" + Integer.toHexString(headerField) + ") time value: " +
                                    timeValue);
                        }
                        headers.setLongInteger(timeValue, headerField);
                    } catch (MalformedPduException e) {
                        throw e; // Vibb One (H16): keep the reason
                    } catch (RuntimeException e) {
                        log(headerField + "is not Long-Integer header field!");
                        return null;
                    }
                    break;
                }

                case PduHeaders.FROM: {
                    /* From-value =
                     * Value-length
                     * (Address-present-token Encoded-string-value | Insert-address-token)
                     */
                    EncodedStringValue from = null;
                    int valueEnd = valueEnd(pduDataStream); /* parse value-length */ // Vibb One (L8)

                    /* Address-present-token or Insert-address-token */
                    int fromToken = extractByteValue(pduDataStream);

                    /* Address-present-token or Insert-address-token */
                    if (PduHeaders.FROM_ADDRESS_PRESENT_TOKEN == fromToken) {
                        /* Encoded-string-value */
                        from = parseEncodedStringValue(pduDataStream);
                        // Vibb One (H14): the address is kept whole, "/TYPE=PLMN" included.
                    } else {
                        try {
                            from = new EncodedStringValue(
                                    PduHeaders.FROM_INSERT_ADDRESS_TOKEN_STR.getBytes());
                        } catch (NullPointerException e) {
                            log(headerField + "is not Encoded-String-Value header field!");
                            return null;
                        }
                    }
                    endValue(pduDataStream, valueEnd); // Vibb One (L8)

                    try {
                        if (LOCAL_LOGV) {
                            Log.v(LOG_TAG, "parseHeaders: headerField: " + headerField +
                                    " (" + Integer.toHexString(headerField) + ") from address: " +
                                    from.getString());
                        }
                        headers.setEncodedStringValue(from, PduHeaders.FROM);
                    } catch (NullPointerException e) {
                        log("null pointer error!");
                    } catch (RuntimeException e) {
                        log(headerField + "is not Encoded-String-Value header field!");
                        return null;
                    }
                    break;
                }

                case PduHeaders.MESSAGE_CLASS: {
                    /* Message-class-value = Class-identifier | Token-text */
                    pduDataStream.mark(1);
                    int messageClass = extractByteValue(pduDataStream);
                    if (LOCAL_LOGV) {
                        Log.v(LOG_TAG, "parseHeaders: headerField: " + headerField +
                                " (" + Integer.toHexString(headerField) + ") MESSAGE_CLASS: " +
                                messageClass);
                    }

                    if (messageClass >= PduHeaders.MESSAGE_CLASS_PERSONAL) {
                        /* Class-identifier */
                        try {
                            if (PduHeaders.MESSAGE_CLASS_PERSONAL == messageClass) {
                                headers.setTextString(
                                        PduHeaders.MESSAGE_CLASS_PERSONAL_STR.getBytes(),
                                        PduHeaders.MESSAGE_CLASS);
                            } else if (PduHeaders.MESSAGE_CLASS_ADVERTISEMENT == messageClass) {
                                headers.setTextString(
                                        PduHeaders.MESSAGE_CLASS_ADVERTISEMENT_STR.getBytes(),
                                        PduHeaders.MESSAGE_CLASS);
                            } else if (PduHeaders.MESSAGE_CLASS_INFORMATIONAL == messageClass) {
                                headers.setTextString(
                                        PduHeaders.MESSAGE_CLASS_INFORMATIONAL_STR.getBytes(),
                                        PduHeaders.MESSAGE_CLASS);
                            } else if (PduHeaders.MESSAGE_CLASS_AUTO == messageClass) {
                                headers.setTextString(
                                        PduHeaders.MESSAGE_CLASS_AUTO_STR.getBytes(),
                                        PduHeaders.MESSAGE_CLASS);
                            }
                        } catch (NullPointerException e) {
                            log("null pointer error!");
                        } catch (RuntimeException e) {
                            log(headerField + "is not Text-String header field!");
                            return null;
                        }
                    } else {
                        /* Token-text */
                        pduDataStream.reset();
                        byte[] messageClassString = parseWapString(pduDataStream, TYPE_TEXT_STRING);
                        if (null != messageClassString) {
                            try {
                                headers.setTextString(messageClassString, PduHeaders.MESSAGE_CLASS);
                            } catch (NullPointerException e) {
                                log("null pointer error!");
                            } catch (RuntimeException e) {
                                log(headerField + "is not Text-String header field!");
                                return null;
                            }
                        }
                    }
                    break;
                }

                case PduHeaders.MMS_VERSION: {
                    int version = parseShortInteger(pduDataStream);

                    try {
                        if (LOCAL_LOGV) {
                            Log.v(LOG_TAG, "parseHeaders: headerField: " + headerField +
                                    " (" + Integer.toHexString(headerField) + ") MMS_VERSION: " +
                                    version);
                        }
                        headers.setOctet(version, PduHeaders.MMS_VERSION);
                    } catch (InvalidHeaderValueException e) {
                        log("Set invalid Octet value: " + version +
                                " into the header filed: " + headerField);
                        return null;
                    } catch (RuntimeException e) {
                        log(headerField + "is not Octet header field!");
                        return null;
                    }
                    break;
                }

                case PduHeaders.PREVIOUSLY_SENT_BY: {
                    /* Previously-sent-by-value =
                     * Value-length Forwarded-count-value Encoded-string-value */
                    /* parse value-length */
                    int valueEnd = valueEnd(pduDataStream); // Vibb One (L8)

                    /* parse Forwarded-count-value */
                    try {
                        parseIntegerValue(pduDataStream);
                    } catch (MalformedPduException e) {
                        throw e; // Vibb One (H16): keep the reason
                    } catch (RuntimeException e) {
                        log(headerField + " is not Integer-Value");
                        return null;
                    }

                    /* parse Encoded-string-value */
                    EncodedStringValue previouslySentBy =
                            parseEncodedStringValue(pduDataStream);
                    endValue(pduDataStream, valueEnd); // Vibb One (L8)
                    if (null != previouslySentBy) {
                        try {
                            if (LOCAL_LOGV) {
                                Log.v(LOG_TAG, "parseHeaders: headerField: " + headerField +
                                        " (" + Integer.toHexString(headerField) +
                                        ") PREVIOUSLY_SENT_BY: " + previouslySentBy.getString());
                            }
                            headers.setEncodedStringValue(previouslySentBy,
                                    PduHeaders.PREVIOUSLY_SENT_BY);
                        } catch (NullPointerException e) {
                            log("null pointer error!");
                        } catch (RuntimeException e) {
                            log(headerField + "is not Encoded-String-Value header field!");
                            return null;
                        }
                    }
                    break;
                }

                case PduHeaders.PREVIOUSLY_SENT_DATE: {
                    /* Previously-sent-date-value =
                     * Value-length Forwarded-count-value Date-value */
                    /* parse value-length */
                    int valueEnd = valueEnd(pduDataStream); // Vibb One (L8)

                    /* parse Forwarded-count-value */
                    try {
                        parseIntegerValue(pduDataStream);
                    } catch (MalformedPduException e) {
                        throw e; // Vibb One (H16): keep the reason
                    } catch (RuntimeException e) {
                        log(headerField + " is not Integer-Value");
                        return null;
                    }

                    /* Date-value */
                    try {
                        long previouslySentDate = parseLongInteger(pduDataStream);
                        if (LOCAL_LOGV) {
                            Log.v(LOG_TAG, "parseHeaders: headerField: " + headerField +
                                    " (" + Integer.toHexString(headerField) +
                                    ") PREVIOUSLY_SENT_DATE: " + previouslySentDate);
                        }
                        headers.setLongInteger(previouslySentDate,
                                PduHeaders.PREVIOUSLY_SENT_DATE);
                    } catch (MalformedPduException e) {
                        throw e; // Vibb One (H16): keep the reason
                    } catch (RuntimeException e) {
                        log(headerField + "is not Long-Integer header field!");
                        return null;
                    }
                    endValue(pduDataStream, valueEnd); // Vibb One (L8)
                    break;
                }

                case PduHeaders.MM_FLAGS: {
                    /* MM-flags-value =
                     * Value-length
                     * ( Add-token | Remove-token | Filter-token )
                     * Encoded-string-value
                     */
                    if (LOCAL_LOGV) {
                        Log.v(LOG_TAG, "parseHeaders: headerField: " + headerField +
                                " (" + Integer.toHexString(headerField) + ") MM_FLAGS: " +
                                " NOT REALLY SUPPORTED");
                    }

                    /* parse Value-length */
                    int valueEnd = valueEnd(pduDataStream); // Vibb One (L8)

                    /* Add-token | Remove-token | Filter-token */
                    extractByteValue(pduDataStream);

                    /* Encoded-string-value */
                    parseEncodedStringValue(pduDataStream);
                    endValue(pduDataStream, valueEnd); // Vibb One (L8)

                    /* not store this header filed in "headers",
                     * because now PduHeaders doesn't support it */
                    break;
                }

                /* Value-length
                 * (Message-total-token | Size-total-token) Integer-Value */
                case PduHeaders.MBOX_TOTALS:
                case PduHeaders.MBOX_QUOTAS: {
                    if (LOCAL_LOGV) {
                        Log.v(LOG_TAG, "parseHeaders: headerField: " + headerField +
                                " (" + Integer.toHexString(headerField) + ") MBOX_");
                    }
                    /* Value-length */
                    int valueEnd = valueEnd(pduDataStream); // Vibb One (L8)

                    /* Message-total-token | Size-total-token */
                    extractByteValue(pduDataStream);

                    /*Integer-Value*/
                    try {
                        parseIntegerValue(pduDataStream);
                    } catch (MalformedPduException e) {
                        throw e; // Vibb One (H16): keep the reason
                    } catch (RuntimeException e) {
                        log(headerField + " is not Integer-Value");
                        return null;
                    }
                    endValue(pduDataStream, valueEnd); // Vibb One (L8)

                    /* not store these headers filed in "headers",
                    because now PduHeaders doesn't support them */
                    break;
                }

                case PduHeaders.ELEMENT_DESCRIPTOR: {
                    if (LOCAL_LOGV) {
                        Log.v(LOG_TAG, "parseHeaders: headerField: " + headerField +
                                " (" + Integer.toHexString(headerField) + ") ELEMENT_DESCRIPTOR");
                    }
                    parseContentType(pduDataStream, null);

                    /* not store this header filed in "headers",
                    because now PduHeaders doesn't support it */
                    break;
                }

                case PduHeaders.CONTENT_TYPE: {
                    SparseArray<Object> map = new SparseArray<Object>();
                    byte[] contentType =
                            parseContentType(pduDataStream, map);

                    if (null != contentType) {
                        try {
                            if (LOCAL_LOGV) {
                                Log.v(LOG_TAG, "parseHeaders: headerField: " + headerField +
                                        " (" + Integer.toHexString(headerField) + ") CONTENT_TYPE: "
                                        + Arrays.toString(contentType));
                            }
                            headers.setTextString(contentType, PduHeaders.CONTENT_TYPE);
                        } catch (NullPointerException e) {
                            log("null pointer error!");
                        } catch (RuntimeException e) {
                            log(headerField + "is not Text-String header field!");
                            return null;
                        }
                    }

                    /* get start parameter */
                    mStartParam = (byte[]) map.get(PduPart.P_START);

                    /* get charset parameter */
                    mTypeParam = (byte[]) map.get(PduPart.P_TYPE);

                    keepParsing = false;
                    break;
                }

                case PduHeaders.CONTENT:
                case PduHeaders.ADDITIONAL_HEADERS:
                case PduHeaders.ATTRIBUTES:
                default: {
                    if (LOCAL_LOGV) {
                        Log.v(LOG_TAG, "parseHeaders: Unknown header: " + headerField +
                                " (" + Integer.toHexString(headerField) + ")");
                    }
                    log("Unknown header");
                }
            }
        }

        return headers;
    }

    /**
     * Parse pdu parts.
     *
     * @param pduDataStream pdu data input stream
     * @return parts in PduBody structure
     */
    protected PduBody parseParts(ByteArrayInputStream pduDataStream, int depth) {
        if (pduDataStream == null) {
            return null;
        }

        // Vibb One: no bytes left for the count is an empty body, as upstream (its -1 ran no loop).
        if (pduDataStream.available() <= 0) {
            return new PduBody();
        }
        int count = parseUnsignedInt(pduDataStream); // get the number of parts
        if (count > MAX_PARTS) { // Vibb One (H9)
            throw new MalformedPduException(MalformedPduException.Reason.TOO_MANY_PARTS,
                    "parts: " + count);
        }
        PduBody body = new PduBody();

        for (int i = 0; i < count; i++) {
            int headerLength = parseUnsignedInt(pduDataStream);
            int dataLength = parseUnsignedInt(pduDataStream);
            // Vibb One (H5): both lengths within what remains, before anything is allocated.
            if ((long) headerLength + (long) dataLength > pduDataStream.available()) {
                throw new MalformedPduException(MalformedPduException.Reason.BAD_LENGTH,
                        "part lengths beyond the input");
            }
            PduPart part = new PduPart();
            int startPos = pduDataStream.available();
            if (startPos <= 0) {
                // Invalid part. Vibb One (L1): malformed, not merely unaccepted.
                throw new MalformedPduException(MalformedPduException.Reason.BAD_STRUCTURE,
                        "a part with no bytes");
            }

            /* parse part's content-type */
            SparseArray<Object> map = new SparseArray<Object>();
            byte[] contentType = parseContentType(pduDataStream, map);
            if (null != contentType) {
                part.setContentType(contentType);
            } else {
                part.setContentType((PduContentTypes.contentTypes[0]).getBytes()); //"*/*"
            }

            /* get name parameter */
            byte[] name = (byte[]) map.get(PduPart.P_NAME);
            if (null != name) {
                part.setName(name);
            }

            /* get charset parameter */
            Integer charset = (Integer) map.get(PduPart.P_CHARSET);
            if (null != charset) {
                part.setCharset(charset);
            }

            /* parse part's headers */
            int endPos = pduDataStream.available();
            int partHeaderLen = headerLength - (startPos - endPos);
            if (partHeaderLen > 0) {
                if (false == parsePartHeaders(pduDataStream, part, partHeaderLen)) {
                    // Parse part header faild. Vibb One (L1): BAD_STRUCTURE, not REJECTED.
                    throw new MalformedPduException(MalformedPduException.Reason.BAD_STRUCTURE,
                            "part headers overrun their length");
                }
            } else if (partHeaderLen < 0) {
                // Invalid length of content-type. Vibb One (L1)
                throw new MalformedPduException(MalformedPduException.Reason.BAD_STRUCTURE,
                        "content-type overruns the part header length");
            }

            /* TODO: check content-id, name, filename and content location,
             * if not set anyone of them, generate a default content-location
             */
            if ((null == part.getContentLocation())
                    && (null == part.getName())
                    && (null == part.getFilename())
                    && (null == part.getContentId())) {
                part.setContentLocation(Long.toOctalString(
                        System.currentTimeMillis()).getBytes());
            }

            /* get part's data */
            if (dataLength > 0) {
                // Vibb One (H5): checked again here, after the part's headers, and read in full.
                if (dataLength > pduDataStream.available()) {
                    throw new MalformedPduException(MalformedPduException.Reason.BAD_LENGTH,
                            "part data beyond the input");
                }
                byte[] partData = new byte[dataLength];
                String partContentType = new String(part.getContentType());
                if (pduDataStream.read(partData, 0, dataLength) != dataLength) {
                    throw new MalformedPduException(MalformedPduException.Reason.TRUNCATED,
                            "part data");
                }
                if (partContentType.equalsIgnoreCase(ContentType.MMS_MULTIPART_ALTERNATIVE)) {
                    // Vibb One (H8): one level of nesting at most, and a child body that fails
                    // or is empty ends the parse (upstream dereferenced null).
                    if (depth >= MAX_DEPTH) {
                        throw new MalformedPduException(MalformedPduException.Reason.TOO_DEEP,
                                "nested multipart");
                    }
                    // parse "multipart/vnd.wap.multipart.alternative".
                    PduBody childBody = parseParts(new ByteArrayInputStream(partData), depth + 1);
                    if ((null == childBody) || (childBody.getPartsNum() == 0)) {
                        throw new MalformedPduException(MalformedPduException.Reason.BAD_STRUCTURE,
                                "empty multipart/alternative");
                    }
                    // take the first part of children.
                    part = childBody.getPart(0);
                } else {
                    // Vibb One (H13): Content-Transfer-Encoding is not decoded; base64 and
                    // quoted-printable decoders are not vendored. Upstream's header parse drops
                    // the name's first byte, so a carrier's "Content-Transfer-Encoding" never
                    // matched there anyway: only a crafted PDU could reach those decoders.
                    if (null == partData) {
                        log("Decode part data error!");
                        return null;
                    }
                    part.setData(partData);
                }
            }

            /* add this part to body */
            if (THE_FIRST_PART == checkPartPosition(part)) {
                /* this is the first part */
                body.addPart(0, part);
            } else {
                /* add the part to the end */
                body.addPart(part);
            }
        }

        return body;
    }

    /**
     * Log status.
     *
     * @param text log information
     */
    private static void log(String text) {
        if (LOCAL_LOGV) {
            Log.v(LOG_TAG, text);
        }
    }

    /**
     * Parse unsigned integer.
     *
     * @param pduDataStream pdu data input stream
     * @return the integer, -1 when failed
     */
    protected static int parseUnsignedInt(ByteArrayInputStream pduDataStream) {
        /**
         * From wap-230-wsp-20010705-a.pdf
         * The maximum size of a uintvar is 32 bits.
         * So it will be encoded in no more than 5 octets.
         */
        // Vibb One (H2, H3): the end of input is TRUNCATED (upstream returned -1, which callers
        // took as a length), at most 5 octets, and the value must fit a non-negative int.
        long result = 0;
        int octets = 0;
        int temp;
        do {
            if (octets == UINTVAR_MAX_OCTETS) {
                throw new MalformedPduException(MalformedPduException.Reason.BAD_LENGTH,
                        "uintvar longer than 5 octets");
            }
            temp = readByte(pduDataStream);
            octets++;
            result = (result << 7) | (temp & 0x7F);
        } while ((temp & 0x80) != 0);

        if (result > Integer.MAX_VALUE) {
            throw new MalformedPduException(MalformedPduException.Reason.BAD_LENGTH,
                    "uintvar beyond 31 bits");
        }
        return (int) result;
    }

    /**
     * Vibb One (H2): one octet, or TRUNCATED at the end of input. Upstream read -1 as 0xFF
     * behind asserts that are off on Android.
     */
    private static int readByte(ByteArrayInputStream pduDataStream) {
        int temp = pduDataStream.read();
        if (temp == -1) {
            throw new MalformedPduException(MalformedPduException.Reason.TRUNCATED,
                    "end of input inside a value");
        }
        return temp;
    }

    /** Vibb One (H2): the next octet without consuming it, or TRUNCATED. */
    private static int peekByte(ByteArrayInputStream pduDataStream) {
        pduDataStream.mark(1);
        int temp = readByte(pduDataStream);
        pduDataStream.reset();
        return temp;
    }

    /** Vibb One (H4): a length must be within what remains. */
    private static int checkRemaining(ByteArrayInputStream pduDataStream, int length) {
        if ((length < 0) || (length > pduDataStream.available())) {
            throw new MalformedPduException(MalformedPduException.Reason.BAD_LENGTH,
                    "length " + length + " beyond the input");
        }
        return length;
    }

    /**
     * Vibb One (L8): reads a Value-length and returns where its value ends, as the
     * available() count at that point.
     */
    private static int valueEnd(ByteArrayInputStream pduDataStream) {
        int length = parseValueLength(pduDataStream);
        return pduDataStream.available() - length;
    }

    /**
     * Vibb One (L8): a value with a Value-length ends where the length says. Octets the
     * parse left inside it are passed over, never read as the next header (an octet after
     * a NUL inside a value can't become a header of its own); a parse that ran past it is
     * a BAD_LENGTH.
     */
    private static void endValue(ByteArrayInputStream pduDataStream, int valueEnd) {
        int left = pduDataStream.available();
        if (left < valueEnd) {
            throw new MalformedPduException(MalformedPduException.Reason.BAD_LENGTH,
                    "value ran past its Value-length");
        }
        if (skipWapValue(pduDataStream, left - valueEnd) == -1) {
            throw new MalformedPduException(MalformedPduException.Reason.BAD_LENGTH,
                    "value end beyond the input");
        }
    }

    /** Vibb One (H9): one more header field, within the cap. */
    private void countHeaderField() {
        mHeaderCount++;
        if (mHeaderCount > MAX_HEADER_FIELDS) {
            throw new MalformedPduException(MalformedPduException.Reason.TOO_MANY_HEADERS,
                    "header fields: " + mHeaderCount);
        }
    }

    /**
     * Parse value length.
     *
     * @param pduDataStream pdu data input stream
     * @return the integer
     */
    protected static int parseValueLength(ByteArrayInputStream pduDataStream) {
        /**
         * From wap-230-wsp-20010705-a.pdf
         * Value-length = Short-length | (Length-quote Length)
         * Short-length = <Any octet 0-30>
         * Length-quote = <Octet 31>
         * Length = Uintvar-integer
         * Uintvar-integer = 1*5 OCTET
         */
        assert (null != pduDataStream);
        int temp = readByte(pduDataStream); // Vibb One (H2)
        int first = temp & 0xFF;

        // Vibb One (H4): the length is checked against what remains.
        if (first <= SHORT_LENGTH_MAX) {
            return checkRemaining(pduDataStream, first);
        } else if (first == LENGTH_QUOTE) {
            return checkRemaining(pduDataStream, parseUnsignedInt(pduDataStream));
        }

        throw new MalformedPduException(MalformedPduException.Reason.BAD_LENGTH,
                "Value length > LENGTH_QUOTE!");
    }

    /**
     * Parse encoded string value.
     *
     * @param pduDataStream pdu data input stream
     * @return the EncodedStringValue
     */
    protected static EncodedStringValue parseEncodedStringValue(
            ByteArrayInputStream pduDataStream) {
        /**
         * From OMA-TS-MMS-ENC-V1_3-20050927-C.pdf
         * Encoded-string-value = Text-string | Value-length Char-set Text-string
         */
        assert (null != pduDataStream);
        pduDataStream.mark(1);
        EncodedStringValue returnValue = null;
        int charset = 0;
        int temp = readByte(pduDataStream); // Vibb One (H2)
        int first = temp & 0xFF;
        if (first == 0) {
            return null;    //  Blank subject, bail.
        }

        pduDataStream.reset();
        int valueEnd = -1;
        if (first < TEXT_MIN) {
            valueEnd = valueEnd(pduDataStream); // Vibb One (L8)

            charset = parseShortInteger(pduDataStream); //get the "Charset"
        }

        byte[] textString = parseWapString(pduDataStream, TYPE_TEXT_STRING);
        if (valueEnd >= 0) {
            endValue(pduDataStream, valueEnd); // Vibb One (L8)
        }

        try {
            if (0 != charset) {
                returnValue = new EncodedStringValue(charset, textString);
            } else {
                returnValue = new EncodedStringValue(textString);
            }
        } catch (Exception e) {
            return null;
        }

        return returnValue;
    }

    /**
     * Parse Text-String or Quoted-String.
     *
     * @param pduDataStream pdu data input stream
     * @param stringType    TYPE_TEXT_STRING or TYPE_QUOTED_STRING
     * @return the string without End-of-string in byte array
     */
    protected static byte[] parseWapString(ByteArrayInputStream pduDataStream,
            int stringType) {
        assert (null != pduDataStream);
        /**
         * From wap-230-wsp-20010705-a.pdf
         * Text-string = [Quote] *TEXT End-of-string
         * If the first character in the TEXT is in the range of 128-255,
         * a Quote character must precede it.
         * Otherwise the Quote character must be omitted.
         * The Quote is not part of the contents.
         * Quote = <Octet 127>
         * End-of-string = <Octet 0>
         *
         * Quoted-string = <Octet 34> *TEXT End-of-string
         *
         * Token-text = Token End-of-string
         */

        // Mark supposed beginning of Text-string
        // We will have to mark again if first char is QUOTE or QUOTED_STRING_FLAG
        pduDataStream.mark(1);

        // Check first char
        // Vibb One: the end of input ends the string (as upstream on Android, asserts off).
        int temp = pduDataStream.read();
        if ((TYPE_QUOTED_STRING == stringType) &&
                (QUOTED_STRING_FLAG == temp)) {
            // Mark again if QUOTED_STRING_FLAG and ignore it
            pduDataStream.mark(1);
        } else if ((TYPE_TEXT_STRING == stringType) &&
                (QUOTE == temp)) {
            // Mark again if QUOTE and ignore it
            pduDataStream.mark(1);
        } else {
            // Otherwise go back to origin
            pduDataStream.reset();
        }

        // We are now definitely at the beginning of string
        /**
         * Return *TOKEN or *TEXT (Text-String without QUOTE,
         * Quoted-String without QUOTED_STRING_FLAG and without End-of-string)
         */
        return getWapString(pduDataStream, stringType);
    }

    /**
     * Check TOKEN data defined in RFC2616.
     *
     * @param ch checking data
     * @return true when ch is TOKEN, false when ch is not TOKEN
     */
    protected static boolean isTokenCharacter(int ch) {
        /**
         * Token      = 1*<any CHAR except CTLs or separators>
         * separators = "("(40) | ")"(41) | "<"(60) | ">"(62) | "@"(64)
         *            | ","(44) | ";"(59) | ":"(58) | "\"(92) | <">(34)
         *            | "/"(47) | "["(91) | "]"(93) | "?"(63) | "="(61)
         *            | "{"(123) | "}"(125) | SP(32) | HT(9)
         * CHAR       = <any US-ASCII character (octets 0 - 127)>
         * CTL        = <any US-ASCII control character
         *            (octets 0 - 31) and DEL (127)>
         * SP         = <US-ASCII SP, space (32)>
         * HT         = <US-ASCII HT, horizontal-tab (9)>
         */
        if ((ch < 33) || (ch > 126)) {
            return false;
        }

        switch (ch) {
            case '"': /* '"' */
            case '(': /* '(' */
            case ')': /* ')' */
            case ',': /* ',' */
            case '/': /* '/' */
            case ':': /* ':' */
            case ';': /* ';' */
            case '<': /* '<' */
            case '=': /* '=' */
            case '>': /* '>' */
            case '?': /* '?' */
            case '@': /* '@' */
            case '[': /* '[' */
            case '\\': /* '\' */
            case ']': /* ']' */
            case '{': /* '{' */
            case '}': /* '}' */
                return false;
        }

        return true;
    }

    /**
     * Check TEXT data defined in RFC2616.
     *
     * @param ch checking data
     * @return true when ch is TEXT, false when ch is not TEXT
     */
    protected static boolean isText(int ch) {
        /**
         * TEXT = <any OCTET except CTLs,
         *      but including LWS>
         * CTL  = <any US-ASCII control character
         *      (octets 0 - 31) and DEL (127)>
         * LWS  = [CRLF] 1*( SP | HT )
         * CRLF = CR LF
         * CR   = <US-ASCII CR, carriage return (13)>
         * LF   = <US-ASCII LF, linefeed (10)>
         */
        if (((ch >= 32) && (ch <= 126)) || ((ch >= 128) && (ch <= 255))) {
            return true;
        }

        switch (ch) {
            case '\t': /* '\t' */
            case '\n': /* '\n' */
            case '\r': /* '\r' */
                return true;
        }

        return false;
    }

    protected static byte[] getWapString(ByteArrayInputStream pduDataStream,
            int stringType) {
        assert (null != pduDataStream);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int temp = pduDataStream.read();
        while ((-1 != temp) && ('\0' != temp)) {
            // check each of the character
            if (stringType == TYPE_TOKEN_STRING) {
                if (isTokenCharacter(temp)) {
                    out.write(temp);
                }
            } else {
                if (isText(temp)) {
                    out.write(temp);
                }
            }

            temp = pduDataStream.read();
        }

        if (out.size() > 0) {
            return out.toByteArray();
        }

        return null;
    }

    /**
     * Extract a byte value from the input stream.
     *
     * @param pduDataStream pdu data input stream
     * @return the byte
     */
    protected static int extractByteValue(ByteArrayInputStream pduDataStream) {
        assert (null != pduDataStream);
        int temp = readByte(pduDataStream); // Vibb One (H2)
        return temp & 0xFF;
    }

    /**
     * Parse Short-Integer.
     *
     * @param pduDataStream pdu data input stream
     * @return the byte
     */
    protected static int parseShortInteger(ByteArrayInputStream pduDataStream) {
        /**
         * From wap-230-wsp-20010705-a.pdf
         * Short-integer = OCTET
         * Integers in range 0-127 shall be encoded as a one
         * octet value with the most significant bit set to one (1xxx xxxx)
         * and with the value in the remaining least significant bits.
         */
        assert (null != pduDataStream);
        int temp = readByte(pduDataStream); // Vibb One (H2)
        return temp & 0x7F;
    }

    /**
     * Parse Long-Integer.
     *
     * @param pduDataStream pdu data input stream
     * @return long integer
     */
    protected static long parseLongInteger(ByteArrayInputStream pduDataStream) {
        /**
         * From wap-230-wsp-20010705-a.pdf
         * Long-integer = Short-length Multi-octet-integer
         * The Short-length indicates the length of the Multi-octet-integer
         * Multi-octet-integer = 1*30 OCTET
         * The content octets shall be an unsigned integer value
         * with the most significant octet encoded first (big-endian representation).
         * The minimum number of octets must be used to encode the value.
         * Short-length = <Any octet 0-30>
         */
        assert (null != pduDataStream);
        int temp = readByte(pduDataStream); // Vibb One (H2)
        int count = temp & 0xFF;

        if (count > LONG_INTEGER_LENGTH_MAX) {
            throw new MalformedPduException(MalformedPduException.Reason.BAD_LENGTH,
                    "Octet count greater than 8 and I can't represent that!");
        }

        long result = 0;

        for (int i = 0; i < count; i++) {
            temp = readByte(pduDataStream); // Vibb One (H2)
            result <<= 8;
            result += (temp & 0xFF);
        }

        return result;
    }

    /**
     * Parse Integer-Value.
     *
     * @param pduDataStream pdu data input stream
     * @return long integer
     */
    protected static long parseIntegerValue(ByteArrayInputStream pduDataStream) {
        /**
         * From wap-230-wsp-20010705-a.pdf
         * Integer-Value = Short-integer | Long-integer
         */
        assert (null != pduDataStream);
        int temp = peekByte(pduDataStream); // Vibb One (H2)
        if (temp > SHORT_INTEGER_MAX) {
            return parseShortInteger(pduDataStream);
        } else {
            return parseLongInteger(pduDataStream);
        }
    }

    /**
     * To skip length of the wap value.
     *
     * @param pduDataStream pdu data input stream
     * @param length        area size
     * @return the values in this area
     */
    protected static int skipWapValue(ByteArrayInputStream pduDataStream, int length) {
        assert (null != pduDataStream);
        // Vibb One (H6): passed over, never copied (upstream read it into new byte[length]).
        // A length outside 0..available() is refused before anything moves.
        boolean fits = (length >= 0) && (length <= pduDataStream.available());
        return (fits && (pduDataStream.skip(length) == length)) ? length : -1;
    }

    /**
     * Parse content type parameters. For now we just support
     * four parameters used in mms: "type", "start", "name", "charset".
     *
     * @param pduDataStream pdu data input stream
     * @param map           to store parameters of Content-Type field
     * @param length        length of all the parameters
     */
    protected static void parseContentTypeParams(ByteArrayInputStream pduDataStream,
            SparseArray<Object> map, Integer length) {
        /**
         * From wap-230-wsp-20010705-a.pdf
         * Parameter = Typed-parameter | Untyped-parameter
         * Typed-parameter = Well-known-parameter-token Typed-value
         * the actual expected type of the value is implied by the well-known parameter
         * Well-known-parameter-token = Integer-value
         * the code values used for parameters are specified in the Assigned Numbers appendix
         * Typed-value = Compact-value | Text-value
         * In addition to the expected type, there may be no value.
         * If the value cannot be encoded using the expected type, it shall be encoded as text.
         * Compact-value = Integer-value |
         * Date-value | Delta-seconds-value | Q-value | Version-value |
         * Uri-value
         * Untyped-parameter = Token-text Untyped-value
         * the type of the value is unknown, but it shall be encoded as an integer,
         * if that is possible.
         * Untyped-value = Integer-value | Text-value
         */
        assert (null != pduDataStream);
        assert (length > 0);

        int startPos = pduDataStream.available();
        int tempPos = 0;
        int lastLen = length;
        while (0 < lastLen) {
            int param = readByte(pduDataStream); // Vibb One (H2)
            lastLen--;

            switch (param) {
                /**
                 * From rfc2387, chapter 3.1
                 * The type parameter must be specified and its value is the MIME media
                 * type of the "root" body part. It permits a MIME user agent to
                 * determine the content-type without reference to the enclosed body
                 * part. If the value of the type parameter and the root body part's
                 * content-type differ then the User Agent's behavior is undefined.
                 *
                 * From wap-230-wsp-20010705-a.pdf
                 * type = Constrained-encoding
                 * Constrained-encoding = Extension-Media | Short-integer
                 * Extension-media = *TEXT End-of-string
                 */
                case PduPart.P_TYPE:
                case PduPart.P_CT_MR_TYPE:
                    pduDataStream.mark(1);
                    int first = extractByteValue(pduDataStream);
                    pduDataStream.reset();
                    if (first > TEXT_MAX) {
                        // Short-integer (well-known type)
                        int index = parseShortInteger(pduDataStream);

                        if (index < PduContentTypes.contentTypes.length) {
                            byte[] type = (PduContentTypes.contentTypes[index]).getBytes();
                            if (null != map) { // Vibb One (H10): map is null for Element-Descriptor
                                map.put(PduPart.P_TYPE, type);
                            }
                        } else {
                            //not support this type, ignore it.
                        }
                    } else {
                        // Text-String (extension-media)
                        byte[] type = parseWapString(pduDataStream, TYPE_TEXT_STRING);
                        if ((null != type) && (null != map)) {
                            map.put(PduPart.P_TYPE, type);
                        }
                    }

                    tempPos = pduDataStream.available();
                    lastLen = length - (startPos - tempPos);
                    break;

                    /**
                     * From oma-ts-mms-conf-v1_3.pdf, chapter 10.2.3.
                     * Start Parameter Referring to Presentation
                     *
                     * From rfc2387, chapter 3.2
                     * The start parameter, if given, is the content-ID of the compound
                     * object's "root". If not present the "root" is the first body part in
                     * the Multipart/Related entity. The "root" is the element the
                     * applications processes first.
                     *
                     * From wap-230-wsp-20010705-a.pdf
                     * start = Text-String
                     */
                case PduPart.P_START:
                case PduPart.P_DEP_START:
                    byte[] start = parseWapString(pduDataStream, TYPE_TEXT_STRING);
                    if ((null != start) && (null != map)) {
                        map.put(PduPart.P_START, start);
                    }

                    tempPos = pduDataStream.available();
                    lastLen = length - (startPos - tempPos);
                    break;

                    /**
                     * From oma-ts-mms-conf-v1_3.pdf
                     * In creation, the character set SHALL be either us-ascii
                     * (IANA MIBenum 3) or utf-8 (IANA MIBenum 106)[Unicode].
                     * In retrieval, both us-ascii and utf-8 SHALL be supported.
                     *
                     * From wap-230-wsp-20010705-a.pdf
                     * charset = Well-known-charset|Text-String
                     * Well-known-charset = Any-charset | Integer-value
                     * Both are encoded using values from Character Set
                     * Assignments table in Assigned Numbers
                     * Any-charset = <Octet 128>
                     * Equivalent to the special RFC2616 charset value "*"
                     */
                case PduPart.P_CHARSET:
                    pduDataStream.mark(1);
                    int firstValue = extractByteValue(pduDataStream);
                    pduDataStream.reset();
                    //Check first char
                    if (((firstValue > TEXT_MIN) && (firstValue < TEXT_MAX)) ||
                            (END_STRING_FLAG == firstValue)) {
                        //Text-String (extension-charset)
                        byte[] charsetStr = parseWapString(pduDataStream, TYPE_TEXT_STRING);
                        // Vibb One (H10): an empty name (null) and a null map no longer throw.
                        int charsetInt;
                        try {
                            charsetInt = (null == charsetStr) ? CharacterSets.ANY_CHARSET
                                    : CharacterSets.getMibEnumValue(new String(charsetStr));
                        } catch (UnsupportedEncodingException e) {
                            // Not a well-known charset, use "*".
                            charsetInt = CharacterSets.ANY_CHARSET;
                        }
                        if (null != map) {
                            map.put(PduPart.P_CHARSET, charsetInt);
                        }
                    } else {
                        //Well-known-charset
                        int charset = (int) parseIntegerValue(pduDataStream);
                        if (map != null) {
                            map.put(PduPart.P_CHARSET, charset);
                        }
                    }

                    tempPos = pduDataStream.available();
                    lastLen = length - (startPos - tempPos);
                    break;

                    /**
                     * From oma-ts-mms-conf-v1_3.pdf
                     * A name for multipart object SHALL be encoded using name-parameter
                     * for Content-Type header in WSP multipart headers.
                     *
                     * From wap-230-wsp-20010705-a.pdf
                     * name = Text-String
                     */
                case PduPart.P_DEP_NAME:
                case PduPart.P_NAME:
                    byte[] name = parseWapString(pduDataStream, TYPE_TEXT_STRING);
                    if ((null != name) && (null != map)) {
                        map.put(PduPart.P_NAME, name);
                    }

                    tempPos = pduDataStream.available();
                    lastLen = length - (startPos - tempPos);
                    break;
                default:
                    if (LOCAL_LOGV) {
                        Log.v(LOG_TAG, "Not supported Content-Type parameter");
                    }
                    if (-1 == skipWapValue(pduDataStream, lastLen)) {
                        Log.e(LOG_TAG, "Corrupt Content-Type");
                    }
                    // Vibb One (H11): ends the loop either way (upstream kept looping after a
                    // failed skip, up to 2^31 times).
                    lastLen = 0;
                    break;
            }
        }

        if (0 != lastLen) {
            Log.e(LOG_TAG, "Corrupt Content-Type");
        }
    }

    /**
     * Parse content type.
     *
     * @param pduDataStream pdu data input stream
     * @param map           to store parameters in Content-Type header field
     * @return Content-Type value
     */
    protected static byte[] parseContentType(ByteArrayInputStream pduDataStream,
            SparseArray<Object> map) {
        /**
         * From wap-230-wsp-20010705-a.pdf
         * Content-type-value = Constrained-media | Content-general-form
         * Content-general-form = Value-length Media-type
         * Media-type = (Well-known-media | Extension-Media) *(Parameter)
         */
        assert (null != pduDataStream);

        byte[] contentType = null;
        int temp = peekByte(pduDataStream); // Vibb One (H2)

        int cur = (temp & 0xFF);

        if (cur < TEXT_MIN) {
            int length = parseValueLength(pduDataStream); // Vibb One (H4): within the input
            int startPos = pduDataStream.available();
            pduDataStream.mark(1);
            temp = readByte(pduDataStream); // Vibb One (H2)
            pduDataStream.reset();
            int first = (temp & 0xFF);

            if ((first >= TEXT_MIN) && (first <= TEXT_MAX)) {
                contentType = parseWapString(pduDataStream, TYPE_TEXT_STRING);
            } else if (first > TEXT_MAX) {
                int index = parseShortInteger(pduDataStream);

                if (index < PduContentTypes.contentTypes.length) { //well-known type
                    contentType = (PduContentTypes.contentTypes[index]).getBytes();
                } else {
                    pduDataStream.reset();
                    contentType = parseWapString(pduDataStream, TYPE_TEXT_STRING);
                }
            } else {
                Log.e(LOG_TAG, "Corrupt content-type");
                return (PduContentTypes.contentTypes[0]).getBytes(); //"*/*"
            }

            int endPos = pduDataStream.available();
            int parameterLen = length - (startPos - endPos);
            if (parameterLen > 0) {//have parameters
                parseContentTypeParams(pduDataStream, map, parameterLen);
            }

            if (parameterLen < 0) {
                Log.e(LOG_TAG, "Corrupt MMS message");
                return (PduContentTypes.contentTypes[0]).getBytes(); //"*/*"
            }
        } else if (cur <= TEXT_MAX) {
            contentType = parseWapString(pduDataStream, TYPE_TEXT_STRING);
        } else {
            // Vibb One (H7): the well-known index is bounded; an unknown one is "*/*"
            // (upstream indexed the table unchecked).
            int index = parseShortInteger(pduDataStream);
            if (index < PduContentTypes.contentTypes.length) {
                contentType = (PduContentTypes.contentTypes[index]).getBytes();
            } else {
                contentType = (PduContentTypes.contentTypes[0]).getBytes(); //"*/*"
            }
        }

        return contentType;
    }

    /**
     * Parse part's headers.
     *
     * @param pduDataStream pdu data input stream
     * @param part          to store the header informations of the part
     * @param length        length of the headers
     * @return true if parse successfully, false otherwise
     */
    protected boolean parsePartHeaders(ByteArrayInputStream pduDataStream,
            PduPart part, int length) {
        assert (null != pduDataStream);
        assert (null != part);
        assert (length > 0);

        /**
         * From oma-ts-mms-conf-v1_3.pdf, chapter 10.2.
         * A name for multipart object SHALL be encoded using name-parameter
         * for Content-Type header in WSP multipart headers.
         * In decoding, name-parameter of Content-Type SHALL be used if available.
         * If name-parameter of Content-Type is not available,
         * filename parameter of Content-Disposition header SHALL be used if available.
         * If neither name-parameter of Content-Type header nor filename parameter
         * of Content-Disposition header is available,
         * Content-Location header SHALL be used if available.
         *
         * Within SMIL part the reference to the media object parts SHALL use
         * either Content-ID or Content-Location mechanism [RFC2557]
         * and the corresponding WSP part headers in media object parts
         * contain the corresponding definitions.
         */
        int startPos = pduDataStream.available();
        int tempPos = 0;
        int lastLen = length;
        while (0 < lastLen) {
            countHeaderField(); // Vibb One (H9)
            int header = readByte(pduDataStream); // Vibb One (H2)
            lastLen--;

            if (header > TEXT_MAX) {
                // Number assigned headers.
                switch (header) {
                    case PduPart.P_CONTENT_LOCATION:
                        /**
                         * From wap-230-wsp-20010705-a.pdf, chapter 8.4.2.21
                         * Content-location-value = Uri-value
                         */
                        byte[] contentLocation = parseWapString(pduDataStream, TYPE_TEXT_STRING);
                        if (null != contentLocation) {
                            part.setContentLocation(contentLocation);
                        }

                        tempPos = pduDataStream.available();
                        lastLen = length - (startPos - tempPos);
                        break;
                    case PduPart.P_CONTENT_ID:
                        /**
                         * From wap-230-wsp-20010705-a.pdf, chapter 8.4.2.21
                         * Content-ID-value = Quoted-string
                         */
                        byte[] contentId = parseWapString(pduDataStream, TYPE_QUOTED_STRING);
                        if (null != contentId) {
                            part.setContentId(contentId);
                        }

                        tempPos = pduDataStream.available();
                        lastLen = length - (startPos - tempPos);
                        break;
                    case PduPart.P_DEP_CONTENT_DISPOSITION:
                    case PduPart.P_CONTENT_DISPOSITION:
                        /*
                         * From wap-230-wsp-20010705-a.pdf, chapter 8.4.2.21
                         * Content-disposition-value = Value-length Disposition *(Parameter)
                         * Disposition = Form-data | Attachment | Inline | Token-text
                         * Form-data = <Octet 128>
                         * Attachment = <Octet 129>
                         * Inline = <Octet 130>
                         *
                         * some carrier mmsc servers do not support content_disposition
                         * field correctly
                        */
                        if (mParseContentDisposition) {
                            int len = parseValueLength(pduDataStream);
                            pduDataStream.mark(1);
                            int thisStartPos = pduDataStream.available();
                            int thisEndPos = 0;
                            int value = pduDataStream.read();

                            if (value == PduPart.P_DISPOSITION_FROM_DATA) {
                                part.setContentDisposition(PduPart.DISPOSITION_FROM_DATA);
                            } else if (value == PduPart.P_DISPOSITION_ATTACHMENT) {
                                part.setContentDisposition(PduPart.DISPOSITION_ATTACHMENT);
                            } else if (value == PduPart.P_DISPOSITION_INLINE) {
                                part.setContentDisposition(PduPart.DISPOSITION_INLINE);
                            } else {
                                pduDataStream.reset();
                                /* Token-text */
                                // Vibb One (H10): an empty token no longer throws.
                                byte[] disposition = parseWapString(pduDataStream
                                        , TYPE_TEXT_STRING);
                                if (null != disposition) {
                                    part.setContentDisposition(disposition);
                                }
                            }

                            /* get filename parameter and skip other parameters */
                            thisEndPos = pduDataStream.available();
                            if (thisStartPos - thisEndPos < len) {
                                value = pduDataStream.read();
                                if (value == PduPart.P_FILENAME) { //filename is text-string
                                    // Vibb One (H10): an empty filename no longer throws.
                                    byte[] filename = parseWapString(pduDataStream
                                            , TYPE_TEXT_STRING);
                                    if (null != filename) {
                                        part.setFilename(filename);
                                    }
                                }

                                /* skip other parameters */
                                thisEndPos = pduDataStream.available();
                                if (thisStartPos - thisEndPos < len) {
                                    int last = len - (thisStartPos - thisEndPos);
                                    // Vibb One (H6): skipped, not read into new byte[last].
                                    if (-1 == skipWapValue(pduDataStream, last)) {
                                        throw new MalformedPduException(
                                                MalformedPduException.Reason.BAD_LENGTH,
                                                "Content-Disposition beyond the input");
                                    }
                                }
                            }

                            tempPos = pduDataStream.available();
                            lastLen = length - (startPos - tempPos);
                        }
                        break;
                    default:
                        if (LOCAL_LOGV) {
                            Log.v(LOG_TAG, "Not supported Part headers: " + header);
                        }
                        if (-1 == skipWapValue(pduDataStream, lastLen)) {
                            Log.e(LOG_TAG, "Corrupt Part headers");
                            return false;
                        }
                        lastLen = 0;
                        break;
                }
            } else if ((header >= TEXT_MIN) && (header <= TEXT_MAX)) {
                // Not assigned header.
                byte[] tempHeader = parseWapString(pduDataStream, TYPE_TEXT_STRING);
                byte[] tempValue = parseWapString(pduDataStream, TYPE_TEXT_STRING);

                // Check the header whether it is "Content-Transfer-Encoding".
                // Vibb One (H10): an empty name or value no longer throws. The value is kept
                // but never decoded (H13).
                if ((null != tempHeader) && (null != tempValue) && (true ==
                        PduPart.CONTENT_TRANSFER_ENCODING
                                .equalsIgnoreCase(new String(tempHeader)))) {
                    part.setContentTransferEncoding(tempValue);
                }

                tempPos = pduDataStream.available();
                lastLen = length - (startPos - tempPos);
            } else {
                if (LOCAL_LOGV) {
                    Log.v(LOG_TAG, "Not supported Part headers: " + header);
                }
                // Skip all headers of this part.
                if (-1 == skipWapValue(pduDataStream, lastLen)) {
                    Log.e(LOG_TAG, "Corrupt Part headers");
                    return false;
                }
                lastLen = 0;
            }
        }

        if (0 != lastLen) {
            Log.e(LOG_TAG, "Corrupt Part headers");
            return false;
        }

        return true;
    }

    /**
     * Check the position of a specified part.
     *
     * @param part the part to be checked
     * @return part position, THE_FIRST_PART when it's the
     * first one, THE_LAST_PART when it's the last one.
     */
    private int checkPartPosition(PduPart part) {
        assert (null != part);
        if ((null == mTypeParam) &&
                (null == mStartParam)) {
            return THE_LAST_PART;
        }

        /* check part's content-id */
        if (null != mStartParam) {
            byte[] contentId = part.getContentId();
            if (null != contentId) {
                if (true == Arrays.equals(mStartParam, contentId)) {
                    return THE_FIRST_PART;
                }
            }
            // This is not the first part, so append to end (keeping the original order)
            // Check b/19607294 for details of this change
            return THE_LAST_PART;
        }

        /* check part's content-type */
        if (null != mTypeParam) {
            byte[] contentType = part.getContentType();
            if (null != contentType) {
                if (true == Arrays.equals(mTypeParam, contentType)) {
                    return THE_FIRST_PART;
                }
            }
        }

        return THE_LAST_PART;
    }

    /**
     * Check mandatory headers of a pdu.
     *
     * @param headers pdu headers
     * @return true if the pdu has all of the mandatory headers, false otherwise.
     */
    protected static boolean checkMandatoryHeader(PduHeaders headers) {
        if (null == headers) {
            return false;
        }

        /* get message type */
        int messageType = headers.getOctet(PduHeaders.MESSAGE_TYPE);

        /* check Mms-Version field */
        int mmsVersion = headers.getOctet(PduHeaders.MMS_VERSION);
        if (0 == mmsVersion) {
            // Every message should have Mms-Version field.
            return false;
        }

        /* check mandatory header fields */
        switch (messageType) {
            case PduHeaders.MESSAGE_TYPE_SEND_REQ:
                // Content-Type field.
                byte[] srContentType = headers.getTextString(PduHeaders.CONTENT_TYPE);
                if (null == srContentType) {
                    return false;
                }

                // From field.
                EncodedStringValue srFrom = headers.getEncodedStringValue(PduHeaders.FROM);
                if (null == srFrom) {
                    return false;
                }

                // Transaction-Id field.
                byte[] srTransactionId = headers.getTextString(PduHeaders.TRANSACTION_ID);
                if (null == srTransactionId) {
                    return false;
                }

                break;
            case PduHeaders.MESSAGE_TYPE_SEND_CONF:
                // Response-Status field.
                int scResponseStatus = headers.getOctet(PduHeaders.RESPONSE_STATUS);
                if (0 == scResponseStatus) {
                    return false;
                }

                // Transaction-Id field.
                byte[] scTransactionId = headers.getTextString(PduHeaders.TRANSACTION_ID);
                if (null == scTransactionId) {
                    return false;
                }

                break;
            case PduHeaders.MESSAGE_TYPE_NOTIFICATION_IND:
                // Content-Location field.
                byte[] niContentLocation = headers.getTextString(PduHeaders.CONTENT_LOCATION);
                if (null == niContentLocation) {
                    return false;
                }

                // Expiry field.
                long niExpiry = headers.getLongInteger(PduHeaders.EXPIRY);
                if (-1 == niExpiry) {
                    return false;
                }

                // Message-Class field.
                byte[] niMessageClass = headers.getTextString(PduHeaders.MESSAGE_CLASS);
                if (null == niMessageClass) {
                    return false;
                }

                // Message-Size field.
                long niMessageSize = headers.getLongInteger(PduHeaders.MESSAGE_SIZE);
                if (-1 == niMessageSize) {
                    return false;
                }

                // Transaction-Id field.
                byte[] niTransactionId = headers.getTextString(PduHeaders.TRANSACTION_ID);
                if (null == niTransactionId) {
                    return false;
                }

                break;
            case PduHeaders.MESSAGE_TYPE_NOTIFYRESP_IND:
                // Status field.
                int nriStatus = headers.getOctet(PduHeaders.STATUS);
                if (0 == nriStatus) {
                    return false;
                }

                // Transaction-Id field.
                byte[] nriTransactionId = headers.getTextString(PduHeaders.TRANSACTION_ID);
                if (null == nriTransactionId) {
                    return false;
                }

                break;
            case PduHeaders.MESSAGE_TYPE_RETRIEVE_CONF:
                // Content-Type field.
                byte[] rcContentType = headers.getTextString(PduHeaders.CONTENT_TYPE);
                if (null == rcContentType) {
                    return false;
                }

                // Date field.
                long rcDate = headers.getLongInteger(PduHeaders.DATE);
                if (-1 == rcDate) {
                    return false;
                }

                break;
            case PduHeaders.MESSAGE_TYPE_ACKNOWLEDGE_IND:
                // Transaction-Id field.
                byte[] aiTransactionId = headers.getTextString(PduHeaders.TRANSACTION_ID);
                if (null == aiTransactionId) {
                    return false;
                }

                break;
            default:
                // Parser doesn't support this message type in this version.
                return false;
        }

        return true;
    }
}
