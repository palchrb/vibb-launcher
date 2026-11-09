// SPDX-License-Identifier: GPL-3.0-or-later
// Written for Vibb One (design 77 D9), not from upstream: a plain-Java stand-in for the Android class of this name,
// with only what the vendored codec calls, so it runs in plain JVM tests and the isolated parse process. See README.md.
package com.kidslauncher.mdm.sms.vendor.pdu;

import java.util.HashMap;

/** android.util.SparseArray's get and put, over a HashMap. */
final class SparseArray<E> {
    private final HashMap<Integer, E> mMap = new HashMap<>();

    E get(int key) {
        return mMap.get(key);
    }

    E get(int key, E valueIfKeyNotFound) {
        E value = mMap.get(key);
        return value == null ? valueIfKeyNotFound : value;
    }

    void put(int key, E value) {
        mMap.put(key, value);
    }
}
