package com.kidslauncher.mdm.ui.kidsettings

import org.junit.Assert.assertEquals
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
        // Design 77 D11: the photo picker and a camera - from PicturePick.kt alone; no document or file picker anywhere.
        "ACTION_PICK_IMAGES",
        "ACTION_IMAGE_CAPTURE",
        "ACTION_GET_CONTENT",
        "ACTION_OPEN_DOCUMENT",
        "PickVisualMedia",
        "TakePicture",
        "\"android.provider.action.PICK_IMAGES",
        "\"android.media.action.IMAGE_CAPTURE",
        "\"android.intent.action.GET_CONTENT",
        "\"android.intent.action.OPEN_DOCUMENT",
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
        // Design 79 (QA L4): the card, the list's model, the contact request, the search.
        "calls/ContactSheet.kt", "calls/PhoneBookList.kt", "calls/ContactRequestForm.kt", "calls/ContactRequests.kt", "ui/search",
    )

    /** Design 77 §6.7: the one allowed way out - "Del" on a received picture opens the system's share sheet (a kiosk
     * helper; only allowed apps resolve), from this file alone. */
    private val shareSheet = mapOf(
        "PictureShare.kt" to setOf("Intent.createChooser"),
        // D11: the compose bar's "+" - the system photo picker (a kiosk helper) and the camera app the parent allows.
        "PicturePick.kt" to setOf("ACTION_PICK_IMAGES", "ACTION_IMAGE_CAPTURE"),
    )

    @Test
    fun `kid screens reference no system settings, picker or chooser`() {
        val files = guarded.mapNotNull { path ->
            listOf(File("src/main/java/com/kidslauncher/mdm/$path"), File("app/src/main/java/com/kidslauncher/mdm/$path"))
                .firstOrNull { it.exists() }
        }.flatMap { f -> if (f.isDirectory) f.walk().filter { it.isFile && it.name.endsWith(".kt") }.toList() else listOf(f) }
        assertTrue("guarded sources found", files.size >= 10)
        for (name in listOf("HomeActivity.kt", "PhoneBookActivity.kt", "KidSettingsActivity.kt", "KidSoundPickerActivity.kt", "KidSounds.kt", "HomeGridAdapter.kt", "AppAction.kt", "BluetoothAudio.kt", "MessagesActivity.kt", "ThreadActivity.kt", "PictureActivity.kt", "PictureShare.kt", "PicturePick.kt")) {
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
        // D11's "+": the camera is only ever the one app the parent allows, writing one file of ours; the picker is
        // the system's photo picker, one image.
        val pick = src("ui/messages/PicturePick.kt").readText()
        assertTrue(pick.contains("Intent(MediaStore.ACTION_IMAGE_CAPTURE)\n            .setPackage(pkg)"))
        assertTrue(pick.contains("usable(it.packageName)"))
        assertTrue(pick.contains("FileProvider.getUriForFile(context, PictureShare.authority(context), file)"))
        assertTrue(pick.contains("val pick = Intent(MediaStore.ACTION_PICK_IMAGES).setType(\"image/*\")"))
        // Code QA M3: the system's own picker only, and its answer read only as another app's content URI.
        assertTrue(pick.contains("queryIntentActivities(pick, PackageManager.MATCH_SYSTEM_ONLY)"))
        assertTrue(pick.contains("return pick.setPackage(system)"))
        assertTrue(pick.contains("pickedUriAcceptable(it.scheme, it.authority, context.packageName)"))
        assertTrue(!pick.contains("FLAG_GRANT_READ_URI_PERMISSION"))
        val threadSource = thread
        assertTrue(threadSource.contains("PicturePick.picked(this, result.data?.data)") && !threadSource.contains("Uri.fromFile"))
        assertTrue(!pick.contains("EXTRA_PICK_IMAGES_MAX") && !pick.contains("EXTRA_ALLOW_MULTIPLE"))
    }

    private fun code(path: String) = src(path).readText()
        .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("//[^\\n]*"), "")

    /** Design 77 §14: the contact request's number field is the kid's only free number entry, and it only asks - the
     * form and its store call, text and open nothing, and none of its three fields has a menu or a text action. */
    @Test
    fun `the contact request only asks`() {
        val code = code("calls/ContactRequestForm.kt") + "\n" + code("calls/ContactRequests.kt")
        for (token in listOf(
            "startActivity", "Intent(", "placeCall", "ACTION_CALL", "ACTION_DIAL", "\"tel:", "smsto", "SmsManager",
            "SmsRuntime", "TelecomManager", "PendingIntent", "Linkify",
        )) {
            assertTrue(token, !code.contains(token))
        }
        val form = src("calls/ContactRequestForm.kt").readText()
        assertTrue(form.contains("form.contactRequestName, form.contactRequestNumber, form.contactRequestChat") && form.contains("NoTextActions.apply(field)"))
        val layout = listOf(File("src/main/res/layout/view_contact_request_form.xml"), File("app/src/main/res/layout/view_contact_request_form.xml"))
            .first { it.exists() }.readText()
        assertTrue(!layout.contains("autoLink") && !layout.contains("textIsSelectable=\"true\""))
        assertEquals(3, Regex("<EditText").findAll(layout).count())
        assertEquals(3, Regex("""android:importantForAutofill="no"""").findAll(layout).count())
    }

    /** Design 79 (QA L4): the card's ways out are a call, our own thread and the message app ([openMessage]); the list
     * and its model open nothing themselves - the activity's Ring and Melding go the card's ways. */
    @Test
    fun `the card and the list go only where the card always went`() {
        val sheet = code("calls/ContactSheet.kt")
        assertEquals(1, Regex("""startActivity\(""").findAll(sheet).count())
        assertTrue(sheet.contains("activity.startActivity(Intent(message.action, Uri.parse(uri)).setPackage(message.packageName))"))
        assertEquals(1, Regex("""CallSystem\.placeCall\(""").findAll(sheet).count())
        assertEquals(1, Regex("""MessagesScreens\.startThread\(""").findAll(sheet).count())
        for (path in listOf("calls/PhoneBookList.kt", "calls/PhoneBookAdapter.kt", "ui/search/SearchFold.kt")) {
            val text = code(path)
            for (token in listOf("startActivity", "Intent(", "placeCall", "PendingIntent")) assertTrue("$path $token", !text.contains(token))
        }
        val book = code("calls/PhoneBookActivity.kt")
        val ways = book.substringAfter("private fun call(").substringBefore("private fun handleNumber(")
        assertTrue(!ways.contains("startActivity") && !ways.contains("Intent("))
        assertTrue(ways.contains("CallSystem.placeCall(this, contact.number)") && ways.contains("ContactSheet.openMessage(this, action.intent)"))
    }

    @Test
    fun `only app tiles have a long-press menu`() {
        val adapter = src("ui/home/HomeGridAdapter.kt").readText()
        assertTrue(adapter.contains("as? GridTile.App ?: return@setOnLongClickListener false"))
    }
}
