// SPDX-License-Identifier: GPL-3.0-or-later
// Written for Vibb One (design 77 D9), not from upstream: a plain-Java stand-in for the Android class of this name,
// with only what the vendored codec calls, so it runs in plain JVM tests and the isolated parse process. See README.md.
package com.kidslauncher.mdm.sms.vendor.pdu;

/** android.util.Log, silenced: the codec never logs, so no PDU content (numbers, text) reaches logcat. */
final class Log {
    private Log() {
    }

    static void v(String tag, String msg) {
    }

    static void v(String tag, String msg, Throwable tr) {
    }

    static void e(String tag, String msg) {
    }

    static void e(String tag, String msg, Throwable tr) {
    }
}
