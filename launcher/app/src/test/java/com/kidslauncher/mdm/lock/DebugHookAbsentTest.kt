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
 * vault-key receiver and design 28's fake headset. The debug manifest declares exactly these receivers. Design 134 §7:
 * the Wi-Fi places' made-up scan age, throttled scan and motion trigger live in the debug `PlacesDebugHooks`; its
 * release twin answers "nothing".
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
            for (banned in listOf("LockTaskOverride", "lock_task_debug_override", "extra_lock_task_packages", "PinVaultKeyReceiver", "HeadsetDebugReceiver", "MmsDebugReceiver", "ElementCallDebugReceiver", "UpDebugReceiver", "BtPairingDebugReceiver", "PlacesDebugReceiver", "push_hex", "fakeMotion", "scanAgeS", "refuseScan", "scan_age_s", "refuse_scan")) {
                assertFalse("${f.path} names $banned", f.readText().contains(banned))
            }
        }
    }

    @Test
    fun `only the debug manifest declares the receivers, guarded by DUMP`() {
        val main = dir("main/AndroidManifest.xml").readText()
        val debug = code(dir("debug/AndroidManifest.xml"))
        val receivers = listOf("lock" to "LockTaskOverrideReceiver", "server" to "PinVaultKeyReceiver", "music" to "HeadsetDebugReceiver", "sms" to "MmsDebugReceiver", "calls" to "ElementCallDebugReceiver", "push.up" to "UpDebugReceiver", "server" to "BtPairingDebugReceiver", "places" to "PlacesDebugReceiver")
        // No other hook (no other component of any kind): a new one is listed here (and in checkReleaseHasNoDebugHook) first.
        assertEquals(receivers.size, Regex("<(receiver|activity|activity-alias|service|provider)\\b").findAll(debug).count())
        for ((pkg, name) in receivers) {
            assertFalse(name, main.contains(name))
            val receiver = debug.substringAfter("android:name=\"com.kidslauncher.mdm.$pkg.$name\"", "").substringBefore("/>")
            assertTrue(name, receiver.contains("android:exported=\"true\""))
            assertTrue(name, receiver.contains("android:permission=\"android.permission.DUMP\""))
            assertTrue(name, dir("debug/java/com/kidslauncher/mdm/${pkg.replace('.', '/')}/$name.kt").exists())
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
    fun `release MmsDebug adds no MMS server and stands in for nothing`() {
        // Design 77 step 3b: the emulator's fake MMSC and its stand-in for the platform exist only in the debug build.
        val release = code(dir("release/java/com/kidslauncher/mdm/sms/MmsDebug.kt"))
        assertEquals(3, Regex("\\bfun ").findAll(release).count())
        assertTrue(release, release.contains("fun mmscRules(): List<MmscRule> = emptyList()\n"))
        assertTrue(release, release.contains("fun download(context: Context, location: String, uri: Uri, result: PendingIntent): Boolean = false\n"))
        assertTrue(release, release.contains("fun send(context: Context, uri: Uri, result: PendingIntent): Boolean = false\n"))
        assertFalse(release.contains("var "))
        val callers = sources("main").filter { code(it).contains("MmsDebug.") }.map { it.name }.sorted()
        assertEquals(listOf("MmsRuntime.kt", "SmsRuntime.kt"), callers)
    }

    @Test
    fun `release PlacesDebugHooks hears nothing, fakes nothing, and only the places and the motion gate call it`() {
        // Design 134 §7: no made-up scan or connection, no age, no throttled scan, no fake trigger in a release build.
        val release = code(dir("release/java/com/kidslauncher/mdm/places/PlacesDebugHooks.kt"))
        assertEquals(7, Regex("\\bfun ").findAll(release).count())
        for (line in listOf(
            "fun madeUpResults(): List<ScanAp>? = null\n",
            "fun madeUpConnected(): Connected? = null\n",
            "fun resultsAgeMs(): Long = 0L\n",
            "fun scanRefused(): Boolean = false\n",
            "fun motionSensor(): Boolean = false\n",
            "fun armTrigger(onFire: () -> Unit): Boolean = false\n",
            "fun disarmTrigger() {}\n",
        )) {
            assertTrue(line, release.contains(line))
        }
        assertFalse(release.contains("var ") || release.contains("val "))
        val callers = sources("main").filter { code(it).contains("PlacesDebugHooks") }.map { it.name }.sorted()
        assertEquals(listOf("WifiPlaces.kt", "ZoneSensors.kt"), callers)
        // The debug twin has every function the release one has.
        val debug = code(dir("debug/java/com/kidslauncher/mdm/places/PlacesDebugHooks.kt"))
        for (name in listOf("madeUpResults", "madeUpConnected", "resultsAgeMs", "scanRefused", "motionSensor", "armTrigger", "disarmTrigger")) {
            assertTrue(name, debug.contains("fun $name("))
        }
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
}
