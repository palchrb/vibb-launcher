package com.kidslauncher.mdm.server

import android.content.SharedPreferences
import com.kidslauncher.mdm.lock.LockClocks
import com.kidslauncher.mdm.server.dto.ParentPinEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Design 24 §2.1-§2.3 end to end against a fake prefs map: ParentPin.check's steps
 * ([checkParentPin]), the store's migration and its failure modes (QA #2, #3).
 */
class ParentPinStoreTest {

    /** SharedPreferences as Android's: a commit updates memory first and never rolls it back,
     * whatever the disk does ([diskFails]); a getter on a value of another type throws. */
    class FakePrefs : SharedPreferences {
        val map = mutableMapOf<String, Any?>()
        var diskFails = false
        var commits = 0

        override fun getAll(): MutableMap<String, *> = map.toMutableMap()
        override fun getString(key: String, defValue: String?): String? = if (key in map) map[key] as String? else defValue
        override fun getStringSet(key: String, defValues: MutableSet<String>?): MutableSet<String>? = defValues
        override fun getInt(key: String, defValue: Int): Int = if (key in map) map[key] as Int else defValue
        override fun getLong(key: String, defValue: Long): Long = if (key in map) map[key] as Long else defValue
        override fun getFloat(key: String, defValue: Float): Float = if (key in map) map[key] as Float else defValue
        override fun getBoolean(key: String, defValue: Boolean): Boolean = if (key in map) map[key] as Boolean else defValue
        override fun contains(key: String): Boolean = key in map
        override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
        override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

        override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
            val changes = mutableMapOf<String, Any?>()
            override fun putString(key: String, value: String?) = apply { changes[key] = value }
            override fun putStringSet(key: String, values: MutableSet<String>?) = apply { changes[key] = values }
            override fun putInt(key: String, value: Int) = apply { changes[key] = value }
            override fun putLong(key: String, value: Long) = apply { changes[key] = value }
            override fun putFloat(key: String, value: Float) = apply { changes[key] = value }
            override fun putBoolean(key: String, value: Boolean) = apply { changes[key] = value }
            override fun remove(key: String) = apply { changes[key] = null }
            override fun clear() = apply { map.keys.forEach { changes[it] = null } }
            override fun commit(): Boolean {
                commits++
                for ((key, value) in changes) if (value == null) map.remove(key) else map[key] = value
                return !diskFails
            }
            override fun apply() {
                commit()
            }
        }
    }

    class FakeLegacy(var failed: Int = 0, var lockedUntil: Long = 0L) : LegacyParentPinCounter {
        var cleared = false
        override fun failedAttempts() = failed
        override fun lockedUntilWallMs() = lockedUntil
        override fun clear() {
            cleared = true
            failed = 0
            lockedUntil = 0L
        }
    }

    private val min = 60_000L
    private val salt = "00112233445566778899aabbccddeeff"
    private fun hashOf(pin: String, saltHex: String = salt) =
        PinHash.derive(pin, PinHash.parseHex(saltHex)!!).joinToString("") { "%02x".format(it) }
    private val pin = "246810"
    private val hash = hashOf(pin)

    private val prefs = FakePrefs()
    private val legacy = FakeLegacy()
    private val logged = mutableListOf<String>()
    private val store = ParentPinStore(prefs, legacy) { message, _ -> logged += message }
    private var ids = 0
    private var started = mutableListOf<WindowStart?>()
    private var full = false

    private fun at(wall: Long) = LockClocks(wall, wall, 3)

    private fun check(
        typed: String,
        now: LockClocks,
        entry: ParentPinEntry = ParentPinEntry.SETTINGS,
        hashHex: String = hash,
        saltHex: String = salt,
        window: WindowStart? = null,
    ): ParentPinResult = checkParentPin(
        store, entry, typed, hashHex, saltHex, now,
        newId = { "id${++ids}" },
        storageFull = { full },
        windowStart = { window },
        startWindow = { start ->
            // The `unlocked` event is committed before the window starts (design 24 §2).
            assertEquals(PIN_EVENT_UNLOCKED, store.queue().last().kind)
            started += start
        },
    )

    private fun state(now: LockClocks): ParentLockoutState =
        (store.load(now, parentPinFingerprint(hash, salt)) as ParentPinStore.Loaded.Ok).state

    private fun kinds() = store.queue().map { it.kind }

    @Test
    fun `five wrong tries lock every entry, and a lockout counts and logs nothing`() {
        val now = at(1_000_000L)
        for (n in 1..4) {
            assertEquals(ParentPinResult.Wrong(5 - n, 15 * min), check("000000", now, ParentPinEntry.entries[n]))
        }
        assertEquals(ParentPinResult.LockedOut(15 * min), check("000000", now, ParentPinEntry.OVERRIDE))
        assertEquals(listOf("wrong", "wrong", "wrong", "wrong", "lockout"), kinds())
        assertEquals(listOf("pin_lock", "settings", "pause", "install_mode", "override"), store.queue().map { it.entry })
        val before = prefs.map.toMap()
        // Even the right PIN: refused without hashing, nothing counted, no event.
        assertEquals(ParentPinResult.LockedOut(10 * min), check(pin, at(now.wallMs + 5 * min), ParentPinEntry.PIN_LOCK))
        assertEquals(before, prefs.map)
        assertEquals(15 * min - min, parentPinStatus(store, parentPinFingerprint(hash, salt), at(now.wallMs + min)).lockoutMs)
        assertTrue(started.isEmpty())
    }

    @Test
    fun `the pre-check warns before the fifth try, whichever dialog had the four (qa-24-code Low 1)`() {
        val fp = parentPinFingerprint(hash, salt)
        var now = at(1_000_000L)
        assertEquals(ParentPinStatus(true, 0L, 5, 15 * min), parentPinStatus(store, fp, now))
        repeat(4) { check("000000", now, ParentPinEntry.OVERRIDE) }
        // Days later, a dialog for Settings opens: one try left, and how long the next lockout is.
        now = at(now.wallMs + 3 * 24 * 60 * min)
        assertEquals(ParentPinStatus(true, 0L, 1, 15 * min), parentPinStatus(store, fp, now))
        check("000000", now)
        assertEquals(ParentPinStatus(true, 15 * min, 5, 30 * min), parentPinStatus(store, fp, now))
        // Unreadable: a 15-minute lockout here too.
        prefs.map[ParentPinStore.FAILURES] = "x"
        assertEquals(15 * min, parentPinStatus(store, fp, now).lockoutMs)
    }

    @Test
    fun `too short is refused and not counted`() {
        val now = at(1_000_000L)
        assertEquals(ParentPinResult.TooShort, check("", now))
        assertEquals(ParentPinResult.TooShort, check("24681", now))
        assertEquals(0, state(now).failures)
        assertTrue(store.queue().isEmpty())
    }

    @Test
    fun `a correct PIN clears the tries but keeps the level and its decay`() {
        var now = at(1_000_000L)
        repeat(5) { check("000000", now) }
        now = at(now.wallMs + 15 * min)
        val locked = state(now)
        assertEquals(1, levelNow(locked, now))
        val window = WindowStart(now.wallMs + 120 * min, now.elapsedMs, 3)
        assertEquals(ParentPinResult.Ok, check(pin, now, ParentPinEntry.OVERRIDE, window = window))
        assertEquals(listOf(window), started)
        val after = state(now)
        assertEquals(locked.copy(failures = 0), after)
        val used = store.queue().last()
        assertEquals(ParentPinEvent(used.id, "unlocked", "override", now.wallMs, window.untilWallMs, null, null), used)
        // The level is kept: four wrong tries later the warning names level 2's 30 minutes.
        repeat(3) { check("000000", now) }
        assertEquals(ParentPinResult.Wrong(1, 30 * min), check("000000", now))
        assertEquals(ParentPinResult.LockedOut(30 * min), check("000000", now))
    }

    @Test
    fun `a correct fifth try undoes its own lockout and event`() {
        val now = at(1_000_000L)
        repeat(4) { check("000000", now) }
        assertEquals(ParentPinResult.Ok, check(pin, now, ParentPinEntry.PIN_LOCK))
        assertEquals(listOf("wrong", "wrong", "wrong", "wrong", "unlocked"), kinds())
        assertNull(store.queue().last().untilMs)
        assertEquals(ParentLockoutState(pinFingerprint = parentPinFingerprint(hash, salt)), state(now))
        assertEquals(listOf<WindowStart?>(null), started)
    }

    @Test
    fun `a new PIN from the server resets the level and a running lockout`() {
        val now = at(1_000_000L)
        repeat(5) { check("000000", now) }
        assertTrue(check(pin, now) is ParentPinResult.LockedOut)
        val newSalt = "ffeeddccbbaa99887766554433221100"
        val newHash = hashOf("135792", newSalt)
        assertEquals(ParentPinResult.Ok, check("135792", now, hashHex = newHash, saltHex = newSalt))
        val reset = (store.load(now, parentPinFingerprint(newHash, newSalt)) as ParentPinStore.Loaded.Ok).state
        assertEquals(ParentLockoutState(pinFingerprint = parentPinFingerprint(newHash, newSalt)), reset)
        // The events stay: they are about what happened, not about the PIN.
        assertEquals(listOf("wrong", "wrong", "wrong", "wrong", "lockout", "unlocked"), kinds())
    }

    @Test
    fun `a type clash fails closed with a 15-minute lockout, then fresh tries (QA 2)`() {
        val now = at(1_000_000L)
        check("000000", now)
        prefs.map[ParentPinStore.FAILURES] = "1"
        assertEquals(ParentPinResult.LockedOut(15 * min), check(pin, now))
        assertTrue(logged.any { "unreadable" in it })
        // Rewritten with the right types: a normal state, level 1, the old queue kept.
        assertEquals(1, levelNow(state(now), now))
        assertEquals(listOf("wrong"), kinds())
        assertEquals(ParentPinResult.LockedOut(5 * min), check(pin, at(now.wallMs + 10 * min)))
        assertEquals(ParentPinResult.Ok, check(pin, at(now.wallMs + 15 * min)))
    }

    @Test
    fun `missing keys and values out of range are unreadable too`() {
        val now = at(1_000_000L)
        check("000000", now)
        for ((key, value) in listOf<Pair<String, Any?>>(
            ParentPinStore.FAILURES to 5, ParentPinStore.LO_DURATION to 25 * 60 * min,
            ParentPinStore.PIN_FP to null, ParentPinStore.DC_BOOT to null, ParentPinStore.VERSION to 2,
        )) {
            val good = prefs.map.toMap()
            if (value == null) prefs.map.remove(key) else prefs.map[key] = value
            assertTrue(key, store.load(now, parentPinFingerprint(hash, salt)) is ParentPinStore.Loaded.Unreadable)
            prefs.map.clear()
            prefs.map.putAll(good)
        }
    }

    @Test
    fun `a corrupt event queue is dropped and never decides the result (QA 2)`() {
        val now = at(1_000_000L)
        check("000000", now)
        prefs.map[ParentPinStore.EVENTS] = "{not json"
        assertEquals(ParentPinResult.Wrong(3, 15 * min), check("000000", now))
        assertEquals(listOf("wrong"), kinds())
        prefs.map[ParentPinStore.EVENTS] = 42
        assertEquals(ParentPinResult.Ok, check(pin, now))
        assertEquals(listOf("unlocked"), kinds())
    }

    @Test
    fun `a failed commit restores the state and the queue and records nothing (QA 3)`() {
        val now = at(1_000_000L)
        check("000000", now)
        val before = prefs.map.toMap()
        prefs.diskFails = true
        full = true
        repeat(10) { assertEquals(ParentPinResult.Unavailable(storageFull = true), check("000000", now)) }
        // Memory is as before the attempts: pressing OK again escalated nothing.
        assertEquals(before, prefs.map)
        full = false
        assertEquals(ParentPinResult.Unavailable(storageFull = false), check(pin, now))
        assertEquals(before, prefs.map)
        assertTrue(started.isEmpty())
        prefs.diskFails = false
        assertEquals(ParentPinResult.Wrong(3, 15 * min), check("000000", now))
    }

    @Test
    fun `the first load migrates the old counter once`() {
        val now = at(5_000_000L)
        legacy.failed = 2
        legacy.lockedUntil = now.wallMs + 7 * min
        assertEquals(ParentPinResult.LockedOut(7 * min), check(pin, now))
        assertTrue(legacy.cleared)
        assertEquals(ParentPinStore.STATE_VERSION, prefs.map[ParentPinStore.VERSION])
        assertEquals(1, levelNow(state(now), now))
        // Never again: a later legacy value is not read.
        legacy.lockedUntil = now.wallMs + 99 * min
        assertEquals(ParentPinResult.Ok, check(pin, at(now.wallMs + 7 * min)))
    }

    @Test
    fun `a migration without a running lockout carries the count`() {
        val now = at(5_000_000L)
        legacy.failed = 4
        legacy.lockedUntil = now.wallMs - 1
        assertEquals(ParentPinResult.LockedOut(15 * min), check("000000", now))
    }

    @Test
    fun `a reboot during a lockout keeps what the wall clock says is left`() {
        val now = at(1_000_000L)
        repeat(5) { check("000000", now) }
        val reboot = LockClocks(now.wallMs + 5 * min, 1_000L, 4)
        assertEquals(ParentPinResult.LockedOut(10 * min), check(pin, reboot))
        val back = LockClocks(now.wallMs - 60 * min, 2_000L, 5)
        assertEquals(ParentPinResult.LockedOut(10 * min), check(pin, back))
    }

    @Test
    fun `no event carries PIN material`() {
        val now = at(1_000_000L)
        val typed = "987654"
        repeat(5) { check(typed, now) }
        check(pin, at(now.wallMs + 15 * min), ParentPinEntry.PAUSE, window = WindowStart(now.wallMs + 135 * min, 0L, 3))
        val json = prefs.map[ParentPinStore.EVENTS] as String
        for (secret in listOf(typed, pin, hash, salt, parentPinFingerprint(hash, salt))) {
            assertFalse(secret, json.contains(secret))
        }
        // The event has these fields only: ids, enums, times and counts.
        assertEquals(
            setOf("id", "kind", "entry", "atMs", "untilMs", "failures", "level"),
            ParentPinEvent::class.java.declaredFields.filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) }.map { it.name }.toSet(),
        )
    }
}
