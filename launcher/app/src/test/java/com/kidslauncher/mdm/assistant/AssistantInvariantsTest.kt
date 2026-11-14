package com.kidslauncher.mdm.assistant

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Design 98 §8: Vibb One holds the assistant role and never reads the screen - no assist data, no screenshot, no
 * visible-activity or direct-action API, no microphone. Scans the production sources and the manifest. */
class AssistantInvariantsTest {
    private val main = listOf(File("src/main"), File("app/src/main")).first { it.exists() }
    private val sources = File(main, "java").walk().filter { it.isFile && (it.extension == "kt" || it.extension == "java") }.toList()

    @Test
    fun `setDisabledShowContext only with both flags, in onReady`() {
        val calls = sources.flatMap { f -> Regex("""setDisabledShowContext\(([^)]*)\)""").findAll(f.readText()).map { f.name to it.groupValues[1] }.toList() }
        assertEquals(1, calls.size)
        val (file, args) = calls.single()
        assertEquals("VibbAssistantService.kt", file)
        assertTrue(args, "SHOW_WITH_ASSIST" in args && "SHOW_WITH_SCREENSHOT" in args)
        val service = File(main, "java/com/kidslauncher/mdm/assistant/VibbAssistantService.kt").readText()
        assertTrue(service.substringAfter("override fun onReady()").substringBefore("override fun").contains("setDisabledShowContext("))
    }

    @Test
    fun `no screen-reading API anywhere, the assist callbacks empty`() {
        val banned = listOf(
            "requestAssist", "registerVisibleActivityCallback", "requestDirectActions", "performDirectAction",
            "VisibleActivityInfo", "getAssistStructure", "getAssistContent", "getAssistData",
        )
        for (f in sources) {
            val text = f.readText()
            for (name in banned) assertFalse("${f.name}: $name", name in text)
        }
        val session = File(main, "java/com/kidslauncher/mdm/assistant/VibbSessionService.kt").readText()
        for (callback in Regex("""override fun (onHandleAssist|onHandleAssistSecondary|onHandleScreenshot)\([^)]*\)( \{[^}]*\})?""").findAll(session)) {
            assertTrue(callback.value, callback.value.endsWith("{}"))
        }
        assertEquals(4, Regex("""override fun (onHandleAssist|onHandleAssistSecondary|onHandleScreenshot)\(""").findAll(session).count())
        // AssistStructure and AssistContent appear only in those empty overrides' parameter lists.
        for (f in sources.filter { it.name != "VibbSessionService.kt" }) {
            assertFalse(f.name, "AssistStructure" in f.readText() || "AssistContent" in f.readText())
        }
        assertTrue(session.contains("setUiEnabled(false)"))
    }

    @Test
    fun `no microphone - the manifest declares no RECORD_AUDIO and nothing self-grants it`() {
        val manifest = File(main, "AndroidManifest.xml").readText()
        assertFalse("RECORD_AUDIO" in manifest)
        // The self-grant list fixes only what the manifest requests: never the microphone.
        val requested = Regex("android:name=\"(android\\.permission\\.[A-Z_]+)\"").findAll(manifest).map { it.groupValues[1] }.toList()
        assertTrue(requested.isNotEmpty())
        assertFalse("android.permission.RECORD_AUDIO" in com.kidslauncher.mdm.server.ownPermissionsToFix(requested))
        for (f in sources) {
            for (line in f.readLines().filter { "selfGrantPermission" in it || "setPermissionGrantState" in it }) {
                assertFalse("${f.name}: $line", "RECORD_AUDIO" in line)
            }
        }
    }
}
