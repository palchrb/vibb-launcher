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
 * too (`checkReleaseHasNoDebugHook`, a dependency of assembleRelease).
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
            for (banned in listOf("LockTaskOverride", "lock_task_debug_override", "extra_lock_task_packages")) {
                assertFalse("${f.path} names $banned", f.readText().contains(banned))
            }
        }
    }

    @Test
    fun `only the debug manifest declares the receiver, guarded by DUMP`() {
        assertFalse(dir("main/AndroidManifest.xml").readText().contains("LockTaskOverrideReceiver"))
        val debug = code(dir("debug/AndroidManifest.xml"))
        val receiver = debug.substringAfter("android:name=\"com.kidslauncher.mdm.lock.LockTaskOverrideReceiver\"").substringBefore("/>")
        assertTrue(receiver, receiver.contains("android:permission=\"android.permission.DUMP\""))
        assertTrue(dir("debug/java/com/kidslauncher/mdm/lock/LockTaskOverrideReceiver.kt").exists())
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
