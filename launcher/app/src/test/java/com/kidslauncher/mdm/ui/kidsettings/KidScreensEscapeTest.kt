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
        // Design 79 step 4: the kid's pictures - the screen, the framing, the sheet, the store and the picker's new home.
        "calls/KidPhotoActivity.kt", "calls/PhotoCropView.kt", "calls/KidPhotoSheet.kt", "calls/KidContactPhotos.kt", "ui/pictures",
    )

    /** Design 77 §6.7: the one allowed way out - "Del" on a received picture opens the system's share sheet (a kiosk
     * helper; only allowed apps resolve), from this file alone. */
    private val shareSheet = mapOf(
        "PictureShare.kt" to setOf("Intent.createChooser"),
        // D11: the compose bar's "+" and design 79's contact pictures - the system photo picker (a kiosk helper) and the
        // camera app the parent allows, from ui/pictures/PicturePick.kt alone.
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
        val pick = src("ui/pictures/PicturePick.kt").readText()
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
        // The message app (openMessage) and, since step 4, the kid's picture screen - nothing else.
        assertEquals(2, Regex("""startActivity\(""").findAll(sheet).count())
        assertTrue(sheet.contains("activity.startActivity(Intent(message.action, Uri.parse(uri)).setPackage(message.packageName))"))
        assertTrue(sheet.contains("activity.startActivity(KidPhotoActivity.forContact(activity, contact, camera))"))
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

    /** Design 79 §4.1 (QA L3): the search pill - no menu or text action, no autofill or name hint, no suggestions, and it
     * never takes focus by itself (its container takes the window's first focus; nothing asks for the field's). */
    @Test
    fun `the search pill opens nothing and never takes focus by itself`() {
        val field = src("ui/search/KidSearchField.kt").readText()
        assertTrue(field.contains("NoTextActions.apply(input)"))
        assertTrue(!field.contains("requestFocus") && !field.contains("showSoftInput"))
        val doc = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder()
            .parse(listOf(File("src/main/res/layout/view_kid_search.xml"), File("app/src/main/res/layout/view_kid_search.xml")).first { it.exists() })
        val ns = "http://schemas.android.com/apk/res/android"
        val all = doc.getElementsByTagName("*")
        val nodes = (0 until all.length).map { all.item(it) as org.w3c.dom.Element }
        val input = nodes.single { it.tagName == "EditText" }
        assertEquals("no", input.getAttributeNS(ns, "importantForAutofill"))
        assertEquals("text|textNoSuggestions", input.getAttributeNS(ns, "inputType"))
        assertTrue(nodes.none { it.tagName == "requestFocus" })
        val container = doc.documentElement
        assertEquals("true", container.getAttributeNS(ns, "focusableInTouchMode"))
        for (screen in listOf("calls/PhoneBookActivity.kt", "ui/messages/MessagesActivity.kt")) {
            val text = src(screen).readText()
            assertTrue(screen, text.contains("KidSearchField(") && !text.contains("kidSearchField.requestFocus"))
        }
    }

    /** Design 79 §5.2 (QA L12, §8): the kid's picture screen reads the picker's answer only through
     * `PicturePick.picked`, never a file URI; its picker and camera are PicturePick's; the "Bilde av …" sheet starts
     * nothing itself; the request form stays free of intents (its circle calls back into the phone book, QA M3); the
     * picture screen is no free screen for screen time. */
    @Test
    fun `the kid's picture screen takes only the picker's answer and the allowed camera`() {
        val screen = code("calls/KidPhotoActivity.kt")
        assertTrue(screen.contains("PicturePick.picked(this, result.data?.data)") && !screen.contains("Uri.fromFile"))
        assertTrue(screen.contains("PicturePick.pickIntent(this)") && screen.contains("PicturePick.captureIntent(this, camera)"))
        for (token in listOf("startActivity(", "MediaStore", "Intent.ACTION_")) assertTrue(token, !screen.contains(token))
        val sheet = code("calls/KidPhotoSheet.kt")
        for (token in listOf("startActivity", "registerForActivityResult")) assertTrue(token, !sheet.contains(token))
        assertTrue(!Regex("""(^|[^A-Za-z])Intent\(""").containsMatchIn(sheet))
        val form = code("calls/ContactRequestForm.kt")
        assertTrue(form.contains("onPicture()") && !form.contains("KidPhotoActivity") && !form.contains("Intent("))
        val book = code("calls/PhoneBookActivity.kt")
        assertTrue(book.contains("draftLauncher.launch(KidPhotoActivity.forDraft(this, name, camera))"))
        val free = src("timerules/ScreenTime.kt").readText().substringAfter("val FREE_SCREENS = setOf(").substringBefore(")")
        assertTrue(free.isNotBlank() && !free.contains("KidPhotoActivity"))
    }

    @Test
    fun `only app tiles have a long-press menu`() {
        val adapter = src("ui/home/HomeGridAdapter.kt").readText()
        assertTrue(adapter.contains("as? GridTile.App ?: return@setOnLongClickListener false"))
    }
}
