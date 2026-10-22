package com.kidslauncher.mdm.lock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The design 16d lock-task override hook exists only in the debug build (src/debug): nothing in
 * src/main or src/release names it, the main manifest doesn't declare its receiver, and the
 * release `LockTaskDebug` passes the computed setting through. The built release APK is checked
 * too (`checkReleaseHasNoDebugHook`, a dependency of assembleRelease). The same for design 24b's
 * vault-key receiver, design 28's fake headset, design 32's lock-up toggle (its release
 * `LockUpDebug` passes the plan through; only the receiver's name is banned - the release class
 * carries the object's name) and design 32 step 2's keyguard cover switch (the receiver and its prefs
 * file are banned by those exact strings, never the bare object name - QA L5; the release switch is
 * always off).
 */
class DebugHookAbsentTest {
    private fun dir(path: String) = listOf("src/$path", "app/src/$path").map(::File).first { it.exists() }

    private fun code(f: File) = f.readText()
        .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("//[^\\n]*"), "")
        .replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")

    private fun sources(path: String) = dir(path).walk().filter { it.isFile && (it.extension == "kt" || it.extension == "java" || it.extension == "xml") }.toList()

    @Test
    fun `nothing outside src-debug names the hook`() {
        val outside = sources("main") + sources("release")
        assertTrue(outside.size > 100)
        for (f in outside) {
            // Raw text, comments included: a KDoc naming a hook fails too (design 28 QA L3).
            for (banned in listOf("LockTaskOverride", "lock_task_debug_override", "extra_lock_task_packages", "PinVaultKeyReceiver", "HeadsetDebugReceiver", "LockUpDebugReceiver", "KeyguardCoverDebugReceiver", "keyguard_cover_debug", "HybridDebugReceiver", "hybrid59_debug")) {
                assertFalse("${f.path} names $banned", f.readText().contains(banned))
            }
        }
    }

    @Test
    fun `only the debug manifest declares the receivers, guarded by DUMP`() {
        val main = dir("main/AndroidManifest.xml").readText()
        val debug = code(dir("debug/AndroidManifest.xml"))
        for ((pkg, name) in listOf("lock" to "LockTaskOverrideReceiver", "server" to "PinVaultKeyReceiver", "music" to "HeadsetDebugReceiver", "lock" to "LockUpDebugReceiver", "lock" to "KeyguardCoverDebugReceiver", "lock" to "HybridDebugReceiver")) {
            assertFalse(name, main.contains(name))
            val receiver = debug.substringAfter("android:name=\"com.kidslauncher.mdm.$pkg.$name\"", "").substringBefore("/>")
            assertTrue(name, receiver.contains("android:exported=\"true\""))
            assertTrue(name, receiver.contains("android:permission=\"android.permission.DUMP\""))
            assertTrue(name, dir("debug/java/com/kidslauncher/mdm/$pkg/$name.kt").exists())
        }
    }

    @Test
    fun `the fake headset goes through the real headset step, one device per broadcast`() {
        // Design 28 §4 / QA L3: its own id and address each time, through MusicListening's own entry.
        val hook = code(dir("debug/java/com/kidslauncher/mdm/music/HeadsetDebugReceiver.kt"))
        assertTrue(hook.contains("MusicListening.devicesAdded(listOf(out))"))
        assertTrue(hook.contains("AudioOut(id = -999 - n, type = AudioDeviceInfo.TYPE_BLUETOOTH_A2DP, sink = true, address = \"debug-\$n\")"))
        assertTrue(hook.contains("val n = devices.incrementAndGet()"))
    }

    @Test
    fun `release LockTaskDebug passes the setting through, and LockTaskChrome is its only caller`() {
        val release = code(dir("release/java/com/kidslauncher/mdm/lock/LockTaskDebug.kt"))
        assertEquals(1, Regex("\\bfun ").findAll(release).count())
        assertTrue(release, release.contains("fun adjust(context: Context, setting: LockTaskSetting): LockTaskSetting = setting\n"))
        val callers = sources("main").filter { code(it).contains("LockTaskDebug") }.map { it.name }
        assertEquals(listOf("LockTaskChrome.kt"), callers)
        assertEquals(1, Regex("LockTaskDebug\\.adjust\\(context, computed\\)").findAll(code(dir("main/java/com/kidslauncher/mdm/lock/LockTaskChrome.kt"))).count())
    }

    @Test
    fun `release LockUpDebug passes the lock-up plan through, and the screen-off's lock-up is its only caller`() {
        // Design 32 step 1 item 4: the H2 experiment's toggle exists only in the debug build.
        val release = code(dir("release/java/com/kidslauncher/mdm/lock/LockUpDebug.kt"))
        assertEquals(1, Regex("\\bfun ").findAll(release).count())
        assertTrue(release, release.contains("fun adjust(start: LockUpStart, showLock: Boolean): LockUpStart = start\n"))
        assertFalse(release.contains("var "))
        val callers = sources("main").filter { code(it).contains("LockUpDebug") }.map { it.name }
        assertEquals(listOf("PinLockRuntime.kt"), callers)
        val runtime = code(dir("main/java/com/kidslauncher/mdm/lock/PinLockRuntime.kt"))
        assertEquals(1, Regex("LockUpDebug\\.adjust\\(lockUpStarts\\(").findAll(runtime).count())
        // The debug version only turns a skipped lock on top back into the lock's start.
        val debug = code(dir("debug/java/com/kidslauncher/mdm/lock/LockUpDebug.kt"))
        assertTrue(debug.contains("if (!skipRestart && showLock && start == LockUpStart.NONE) LockUpStart.LOCK else start"))
    }

    @Test
    fun `release HybridDebug is always off, and only Hybrid59's refresh reads it`() {
        // Design 59's prototype: exactly one fun, `= false`; in a release build the switch can't be on.
        val release = code(dir("release/java/com/kidslauncher/mdm/lock/HybridDebug.kt"))
        assertEquals(1, Regex("\\bfun ").findAll(release).count())
        assertTrue(release, release.contains("fun enabled(context: Context): Boolean = false\n"))
        assertFalse(release.contains("var ") || release.contains("val "))
        val callers = sources("main").filter { code(it).contains("HybridDebug") }.map { it.name }
        assertEquals(listOf("Hybrid59.kt"), callers)
        val hybrid = code(dir("main/java/com/kidslauncher/mdm/lock/Hybrid59.kt"))
        assertEquals(1, Regex("HybridDebug\\.enabled\\(").findAll(hybrid).count())
        assertTrue(hybrid.substringAfter("fun refresh(").substringBefore("\n    }\n").contains("HybridDebug.enabled("))
        // switchOn is set only by refresh; faceOn only from it (hybridFace needs switchOn).
        assertEquals(1, Regex("(?<!var )switchOn = ").findAll(hybrid).count())
        assertTrue(hybrid.contains("@Volatile\n    var switchOn = false\n        private set"))
        assertTrue(hybrid.contains("@Volatile\n    var faceOn = false\n        private set"))
        for (set in Regex("(?<!var )faceOn = (.*)").findAll(hybrid).map { it.groupValues[1] }) {
            assertTrue(set, set == "face" || set.startsWith("hybridFace(switchOn,"))
        }
        // The migration starts with the switch.
        assertTrue(hybrid.substringAfter("fun migrateIfDue(").substringBefore("\n    }\n").contains("{\n        if (!switchOn) return\n"))
        // The debug switch stores its own prefs file; "own" rolls back before its apply.
        val receiver = code(dir("debug/java/com/kidslauncher/mdm/lock/HybridDebugReceiver.kt"))
        assertTrue(receiver.indexOf("if (wanted == \"own\") Hybrid59.rollback(app)") in 0 until receiver.indexOf("AppEnforcer.apply(app, currentPolicyDecision().policy)"))
        assertTrue(code(dir("debug/java/com/kidslauncher/mdm/lock/HybridDebug.kt")).contains("private const val PREFS = \"hybrid59_debug\""))
    }

    @Test
    fun `release KeyguardCoverDebug is always off, and only the cover's apply and its cache read it`() {
        // Design 32 step 2 (L5): exactly one fun, `= false`; src/main reads the cached value only.
        val release = code(dir("release/java/com/kidslauncher/mdm/lock/KeyguardCoverDebug.kt"))
        assertEquals(1, Regex("\\bfun ").findAll(release).count())
        assertTrue(release, release.contains("fun enabled(context: Context): Boolean = false\n"))
        assertFalse(release.contains("var ") || release.contains("val "))
        val callers = sources("main").filter { code(it).contains("KeyguardCoverDebug") }.map { it.name }
        assertEquals(listOf("KeyguardCover.kt"), callers)
        val cover = code(dir("main/java/com/kidslauncher/mdm/lock/KeyguardCover.kt"))
        assertEquals(2, Regex("KeyguardCoverDebug\\.enabled\\(").findAll(cover).count())
        for (fn in listOf("fun atProcessStart(", "fun configure(")) {
            val body = cover.substringAfter(fn).substringBefore("\n    }\n")
            assertTrue(fn, body.contains("KeyguardCoverDebug.enabled("))
        }
        // The cached value, @Volatile, set nowhere else.
        assertTrue(cover.contains("@Volatile\n    var switchOn = false\n        private set"))
        assertEquals(2, Regex("switchOn = try \\{").findAll(cover).count())
        // The debug switch stores its own prefs file and runs a full apply.
        val receiver = code(dir("debug/java/com/kidslauncher/mdm/lock/KeyguardCoverDebugReceiver.kt"))
        assertTrue(receiver.contains("KeyguardCoverDebug.store(app, it)"))
        assertTrue(receiver.contains("AppEnforcer.apply(app, currentPolicyDecision().policy)"))
        assertTrue(code(dir("debug/java/com/kidslauncher/mdm/lock/KeyguardCoverDebug.kt")).contains("private const val PREFS = \"keyguard_cover_debug\""))
    }
}
