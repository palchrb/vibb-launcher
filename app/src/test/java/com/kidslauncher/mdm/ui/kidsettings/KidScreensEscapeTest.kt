package com.kidslauncher.mdm.ui.kidsettings

import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * The kid's screens must not open any system surface (QA 08 #10): no Android Settings screen or
 * panel, no system wallpaper picker, no app-info page, no chooser. The picker is our own grid;
 * the switches are device-owner calls.
 */
class KidScreensEscapeTest {

    private val banned = listOf(
        "Settings.ACTION_",
        "Settings.Panel",
        "ACTION_SET_WALLPAPER",
        "getCropAndSetWallpaperIntent",
        "ACTION_APPLICATION_DETAILS_SETTINGS",
        "Intent.createChooser",
        "ACTION_CHANGE_LIVE_WALLPAPER",
        "ACTION_LIVE_WALLPAPER_CHOOSER",
    )

    private fun src(path: String) =
        listOf(File("src/main/java/com/kidslauncher/mdm/$path"), File("app/src/main/java/com/kidslauncher/mdm/$path"))
            .first { it.exists() }

    private val guarded = listOf("ui/kidsettings", "ui/quickcontrols", "ui/wallpaper", "server/QuickControls.kt")

    @Test
    fun `kid screens reference no system settings, picker or chooser`() {
        val files = guarded.mapNotNull { path ->
            listOf(File("src/main/java/com/kidslauncher/mdm/$path"), File("app/src/main/java/com/kidslauncher/mdm/$path"))
                .firstOrNull { it.exists() }
        }.flatMap { f -> if (f.isDirectory) f.walk().filter { it.isFile && it.name.endsWith(".kt") }.toList() else listOf(f) }
        assertTrue("guarded sources found", files.size >= 3)
        for (file in files) {
            // Code only: the doc comments may name what the code must never do.
            val text = file.readText()
                .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
                .replace(Regex("//[^\\n]*"), "")
            for (token in banned) {
                if (text.contains(token)) fail("${file.name} references $token")
            }
        }
    }

    @Test
    fun `only app tiles have a long-press menu`() {
        val adapter = src("ui/home/HomeGridAdapter.kt").readText()
        assertTrue(adapter.contains("as? GridTile.App ?: return@setOnLongClickListener false"))
    }
}
