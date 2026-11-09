// SPDX-License-Identifier: GPL-3.0-or-later
// Written for Vibb One (design 77 D9), not from upstream: a plain-Java stand-in for the Android class of this name,
// with only what the vendored codec calls, so it runs in plain JVM tests and the isolated parse process. See README.md.
package com.kidslauncher.mdm.sms.vendor.pdu;

import java.util.HashMap;

/** androidx.collection.SimpleArrayMap's get and put, over a HashMap. */
final class SimpleArrayMap<K, V> {
    private final HashMap<K, V> mMap = new HashMap<>();

    V get(K key) {
        return mMap.get(key);
    }

    void put(K key, V value) {
        mMap.put(key, value);
    }
}
