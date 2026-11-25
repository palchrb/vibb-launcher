package com.kidslauncher.mdm.lock

import com.kidslauncher.mdm.server.ParentPinStoreTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Design 132 (QA L5): no PIN material left on the phone - the own lock's keys go once, the rest stays. */
class PinLockStoreTest {
    @Test
    fun `the own lock's keys are removed once, everything Android's lock keeps stays`() {
        val prefs = ParentPinStoreTest.FakePrefs()
        val editor = prefs.edit()
        for (key in PinLockStore.OLD_KEYS) editor.putString(key, "x")
        editor.putBoolean("active", true).putBoolean("keyguard_android", true).putLong("android_pin_rev", 4L).putInt("android_boot_count", 9)
        editor.commit()
        assertEquals(PinLockStore.OLD_KEYS.size to true, PinLockStore.dropOldKeysFrom(prefs))
        for (key in PinLockStore.OLD_KEYS) assertFalse(key, prefs.contains(key))
        for (key in listOf("active", "keyguard_android", "android_pin_rev", "android_boot_count")) assertTrue(key, prefs.contains(key))
        assertNull("the second start finds nothing", PinLockStore.dropOldKeysFrom(prefs))
        assertTrue("pin_hash" in PinLockStore.OLD_KEYS && "pin_salt" in PinLockStore.OLD_KEYS && "keyguard_requested" in PinLockStore.OLD_KEYS)
        // The keyguard cover's flag in its own prefs, Android's lock's bits kept.
        val cover = ParentPinStoreTest.FakePrefs()
        cover.edit().putBoolean("cover_written", true).putInt("cover_added", 0x1F).commit()
        assertEquals(1 to true, PinLockStore.dropOldKeysFrom(cover, PinLockStore.OLD_COVER_KEYS))
        assertFalse(cover.contains("cover_written"))
        assertTrue(cover.contains("cover_added"))
    }

    @Test
    fun `the one-shot runs at the process start, before the first apply`() {
        val runtime = listOf("src/main", "app/src/main").map { File(it, "java/com/kidslauncher/mdm/lock/PinLockRuntime.kt") }.first { it.exists() }.readText()
        val init = runtime.substringAfter("    fun init(context: Context) {").substringBefore("\n    }\n")
        assertTrue(init.indexOf("PinLockStore.dropOldKeys(app)") in 0 until init.indexOf("ContextCompat.registerReceiver("))
        assertFalse(runtime.contains("PinLockStore.config("))
    }
}
