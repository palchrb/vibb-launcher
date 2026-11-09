// SPDX-License-Identifier: GPL-3.0-or-later
// Written for Vibb One (design 77 D9), not from upstream: a plain-Java stand-in for the Android class of this name,
// with only what the vendored codec calls, so it runs in plain JVM tests and the isolated parse process. See README.md.
package com.kidslauncher.mdm.sms.vendor.pdu;

/** android.text.TextUtils.isEmpty. */
final class TextUtils {
    private TextUtils() {
    }

    static boolean isEmpty(CharSequence str) {
        return str == null || str.length() == 0;
    }
}
