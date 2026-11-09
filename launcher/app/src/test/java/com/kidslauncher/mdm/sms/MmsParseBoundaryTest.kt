package com.kidslauncher.mdm.sms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Design 77 D8, §6.1 (QA L1) and the codec QA's note for step 3b: MMS bytes are parsed, and received pictures decoded,
 * only in the isolated process. Its manifest entry can't lose `isolatedProcess` or gain `exported` silently, the
 * `Application` and the component factory return at once in it, only `sms/parse/` calls the codec's parsers (they catch
 * every `Throwable` - right there alone), and the main process never runs a decoder on bytes it didn't encode.
 */
class MmsParseBoundaryTest {
    private val ns = "http://schemas.android.com/apk/res/android"
    private fun file(path: String) = listOf("src/main/$path", "app/src/main/$path").map(::File).first { it.exists() }

    private fun code(f: File) = f.readText()
        .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("//[^\\n]*"), "")

    private fun element(tag: String, name: String): Element {
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(file("AndroidManifest.xml"))
        val nodes = doc.getElementsByTagName(tag)
        return (0 until nodes.length).map { nodes.item(it) as Element }.first { it.getAttributeNS(ns, "name") == name }
    }

    @Test
    fun `the parse service is isolated, its own process, not exported, with no permission to grant`() {
        val service = element("service", ".sms.parse.MmsParseService")
        assertEquals("true", service.getAttributeNS(ns, "isolatedProcess"))
        assertEquals("false", service.getAttributeNS(ns, "exported"))
        assertEquals(":mmsparse", service.getAttributeNS(ns, "process"))
        assertEquals("", service.getAttributeNS(ns, "permission"))
        assertEquals(0, service.getElementsByTagName("intent-filter").length)
    }

    @Test
    fun `the MMS provider and result receiver are not exported`() {
        val provider = element("provider", ".sms.mms.MmsFileProvider")
        assertEquals("false", provider.getAttributeNS(ns, "exported"))
        assertEquals("true", provider.getAttributeNS(ns, "grantUriPermissions"))
        assertEquals("\${applicationId}.mms", provider.getAttributeNS(ns, "authorities"))
        val receiver = element("receiver", ".sms.mms.MmsResultReceiver")
        assertEquals("false", receiver.getAttributeNS(ns, "exported"))
        assertEquals(0, receiver.getElementsByTagName("intent-filter").length)
    }

    @Test
    fun `nothing of ours runs in the isolated process`() {
        val app = code(file("java/com/kidslauncher/mdm/Application.kt"))
        val onCreate = app.substringAfter("override fun onCreate() {")
        val guard = onCreate.indexOf("if (android.os.Process.isIsolated()) return")
        assertTrue("the Application returns at once", guard in 0..120)
        val factory = code(file("java/com/kidslauncher/mdm/KidAppComponentFactory.kt"))
        val before = factory.substringAfter("private fun beforeCreating(className: String) {").trimStart()
        assertTrue("the component factory returns at once", before.startsWith("if (android.os.Process.isIsolated()) return"))
    }

    @Test
    fun `only sms-parse calls the codec's parsers`() {
        val root = file("java/com/kidslauncher/mdm")
        val callers = root.walk().filter { it.isFile && it.extension == "kt" }
            .filter { f -> listOf("parseNotification(", "parseRetrieveConf(", "parseSendConf(").any { code(f).contains("MmsCodec.$it") } }
            .map { it.relativeTo(root).path }.toList()
        assertEquals(listOf("sms/parse/MmsParseService.kt"), callers)
    }

    @Test
    fun `the main process decodes only its own JPEGs - pixels come back from the isolated process`() {
        val root = file("java/com/kidslauncher/mdm")
        for (dir in listOf("sms/mms", "sms/engine", "sms/store")) {
            for (f in File(root, dir).walk().filter { it.isFile && it.extension == "kt" }) {
                val text = code(f)
                for (decoder in listOf("BitmapFactory", "ImageDecoder", "decodeStream", "decodeByteArray")) {
                    assertFalse("${f.name} uses $decoder", text.contains(decoder))
                }
            }
        }
        // The parse client builds bitmaps from the shared pixels only, after checking their size.
        val parser = code(file("java/com/kidslauncher/mdm/sms/parse/MmsParser.kt"))
        assertFalse(parser.contains("BitmapFactory") || parser.contains("ImageDecoder"))
        assertTrue(parser.contains("it.size.toLong() != width.toLong() * height * 4"))
        assertTrue(parser.contains("bitmap.copyPixelsFromBuffer(buffer)"))
        // The picked picture goes to the isolated process before anything reads it as an image.
        val pick = code(file("java/com/kidslauncher/mdm/ui/messages/PicturePick.kt"))
        assertFalse(pick.contains("BitmapFactory") || pick.contains("ImageDecoder"))
        assertTrue(pick.contains("MmsParser.decodeImage(app, bytes, MmsParseProtocol.PICK_MAX_PX)"))
    }

    @Test
    fun `answers are read with each length checked first`() {
        val parser = code(file("java/com/kidslauncher/mdm/sms/parse/MmsParser.kt"))
        // No unchecked string or count from the other side.
        assertFalse(parser.contains("reply.readString()"))
        assertFalse(parser.contains("createStringArrayList") || parser.contains("readParcelable("))
        val protocol = code(file("java/com/kidslauncher/mdm/sms/parse/MmsParseProtocol.kt"))
        assertTrue(protocol.contains("if (length < 0 || length > maxChars) throw Malformed"))
    }
}
