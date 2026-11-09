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
        // The same as plain strings (qa-08-code.md #7).
        "\"android.settings.",
        "\"android.intent.action.SET_WALLPAPER",
        "\"android.service.wallpaper.",
        "\"android.intent.action.CHOOSER",
        // An app's details page (launcher round 7 §4: the inherited "can't open" dialog's OK).
        "startAppDetailsActivity",
        "openSettings(",
        // The system's sound picker: its "add a sound" opens a file browser (the kid's ringtone picker is our own).
        "ACTION_RINGTONE_PICKER",
        "\"android.intent.action.RINGTONE_PICKER",
    )

    private fun src(path: String) =
        listOf(File("src/main/java/com/kidslauncher/mdm/$path"), File("app/src/main/java/com/kidslauncher/mdm/$path"))
            .first { it.exists() }

    /** The kid screens: Home (its Settings tile and swipes), the phone book, kid Settings and
     * its Wi-Fi/Bluetooth subscreens (and `BluetoothAudio`, design 52), the wallpaper code, and the app launch actions (`actions/`,
     * what a Home tile's tap runs - launcher round 7 §4). */
    private val guarded = listOf(
        "ui/kidsettings", "ui/quickcontrols", "ui/wallpaper", "server/QuickControls.kt", "server/BluetoothAudio.kt",
        "ui/HomeActivity.kt", "ui/home", "calls/PhoneBookActivity.kt", "calls/PhoneBookAdapter.kt", "ui/day", "calendar",
        "actions", "ui/messages",
    )

    /** Design 77 §6.7: the one allowed way out - "Del" on a received picture opens the system's share sheet (a kiosk
     * helper; only allowed apps resolve), from this file alone. */
    private val shareSheet = mapOf("PictureShare.kt" to setOf("Intent.createChooser"))

    @Test
    fun `kid screens reference no system settings, picker or chooser`() {
        val files = guarded.mapNotNull { path ->
            listOf(File("src/main/java/com/kidslauncher/mdm/$path"), File("app/src/main/java/com/kidslauncher/mdm/$path"))
                .firstOrNull { it.exists() }
        }.flatMap { f -> if (f.isDirectory) f.walk().filter { it.isFile && it.name.endsWith(".kt") }.toList() else listOf(f) }
        assertTrue("guarded sources found", files.size >= 10)
        for (name in listOf("HomeActivity.kt", "PhoneBookActivity.kt", "KidSettingsActivity.kt", "KidSoundPickerActivity.kt", "KidSounds.kt", "HomeGridAdapter.kt", "AppAction.kt", "BluetoothAudio.kt", "MessagesActivity.kt", "ThreadActivity.kt", "PictureActivity.kt", "PictureShare.kt")) {
            assertTrue(name, files.any { it.name == name })
        }
        for (file in files) {
            // Code only: the doc comments may name what the code must never do.
            val text = file.readText()
                .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
                .replace(Regex("//[^\\n]*"), "")
            for (token in banned) {
                if (token in shareSheet[file.name].orEmpty()) continue
                if (text.contains(token)) fail("${file.name} references $token")
            }
        }
    }

    /** The message screens open nothing from a message: no links, no selectable text, a field without menus (design 77
     * §6.7) - and the share sheet carries only our own picture, with a read grant. */
    @Test
    fun `the message screens link nothing and share only the picture`() {
        val layouts = listOf("item_message_bubble", "activity_thread", "item_messages_row").map { name ->
            listOf(File("src/main/res/layout/$name.xml"), File("app/src/main/res/layout/$name.xml")).first { it.exists() }.readText()
        }
        for (layout in layouts) {
            assertTrue(!layout.contains("autoLink") && !layout.contains("textIsSelectable=\"true\"") && !layout.contains("linksClickable"))
        }
        val thread = src("ui/messages/ThreadActivity.kt").readText()
        assertTrue(thread.contains("NoTextActions.apply(binding.threadField)"))
        for (token in listOf("Linkify", "LinkMovementMethod", "ACTION_VIEW")) assertTrue(token, !thread.contains(token))
        val share = src("ui/messages/PictureShare.kt").readText()
        assertTrue(share.contains("Intent(Intent.ACTION_SEND)") && share.contains("setType(\"image/jpeg\")"))
        assertTrue(share.contains("FLAG_GRANT_READ_URI_PERMISSION") && !share.contains("FLAG_GRANT_WRITE_URI_PERMISSION"))
    }

    @Test
    fun `only app tiles have a long-press menu`() {
        val adapter = src("ui/home/HomeGridAdapter.kt").readText()
        assertTrue(adapter.contains("as? GridTile.App ?: return@setOnLongClickListener false"))
    }
}
