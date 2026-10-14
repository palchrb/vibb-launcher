package com.kidslauncher.mdm.lock

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element

/**
 * Emulator round 2026-10-14 on the lock: N8 (the glance and keypad follow a font-scale change
 * without a recreation; the glance date centred when it wraps) and N10 (the 112 confirm on the
 * lock is Vibb night, `KidAlertDialog`, in both system modes - like the phone book's).
 */
class LockFontScaleTest {
    private val ns = "http://schemas.android.com/apk/res/android"
    private val main = listOf("src/main", "app/src/main").map(::File).first { it.isDirectory }
    private fun src(path: String) = File(main, "java/com/kidslauncher/mdm/$path").readText()
        .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "").replace(Regex("//[^\n]*"), "")

    private fun elements(path: String): List<Element> {
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(File(main, path))
        val all = doc.getElementsByTagName("*")
        return (0 until all.length).map { all.item(it) as Element }
    }

    @Test
    fun `the pure parts`() {
        assertTrue(FontScaleRefresh.changed(1.0f, 2.0f))
        assertTrue(FontScaleRefresh.changed(2.0f, 1.0f))
        assertFalse(FontScaleRefresh.changed(1.3f, 1.3f))
        assertEquals(14f, FontScaleRefresh.roundSp(14.000001f), 0f)
        assertEquals(13.9f, FontScaleRefresh.roundSp(13.94f), 0f)
        // Back and forth many times never drifts.
        var sp = 15f
        repeat(50) { sp = FontScaleRefresh.roundSp(sp * 2f / 2f + 0.0004f) }
        assertEquals(15f, sp, 0f)
    }

    @Test
    fun `the lock follows a font-scale change in place, never by a recreation`() {
        // Still no recreation for a font-scale change (qa-10-code #6: crash guard, re-front).
        val manifest = File(main, "AndroidManifest.xml").readText()
        val lock = Regex("""<activity\s+android:name="\.lock\.PinLockActivity"[^>]*>""").find(manifest)!!.value
        assertTrue(Regex("""configChanges="[^"]*\bfontScale\b""").containsMatchIn(lock))
        val code = src("lock/PinLockActivity.kt")
        assertFalse("recreate(" in code)
        val body = code.substringAfter("override fun onConfigurationChanged(newConfig: Configuration) {").substringBefore("override fun ")
        val order = listOf(
            "super.onConfigurationChanged(newConfig)",
            "FontScaleRefresh.changed(textFontScale, newConfig.fontScale)",
            "FontScaleRefresh.rescale(root, old, keys)",
            "textMetrics = FontScaleRefresh.snapshot(resources.displayMetrics)",
            "textFontScale = newConfig.fontScale",
            "sizeKeys(binding.pinKeypad.height)",
            "glance?.refit()",
        )
        var at = -1
        for (step in order) {
            val i = body.indexOf(step)
            assertTrue("$step after the previous step", i > at)
            at = i
        }
        // A fault keeps the old sizes, never a crash that trips the guard.
        assertTrue("catch (e: Throwable)" in body)
        // The metrics the texts were sized at are taken when the content is set.
        val create = code.substringAfter("override fun onCreate(").substringBefore("override fun ")
        assertTrue(create.indexOf("textMetrics = FontScaleRefresh.snapshot(resources.displayMetrics)") > create.indexOf("setContentView(frame)"))
        // The rescale reads each text's sp at the old metrics (non-linear scale included) and skips the
        // keys, whose digits sizeKeys caps.
        val refresh = src("lock/FontScaleRefresh.kt")
        assertTrue("TypedValue.deriveDimension(TypedValue.COMPLEX_UNIT_SP, root.textSize, old)" in refresh)
        assertTrue("root.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)" in refresh)
        assertTrue("root !in skip" in refresh)
        // The glance fit starts again at the same height.
        val glance = src("lock/GlancePanel.kt")
        val refit = glance.substringAfter("fun refit() = guarded(\"refit\") {").substringBefore("}")
        assertTrue("fitFor = 0" in refit && "fitNow()" in refit)
    }

    @Test
    fun `the glance date is centred when it wraps`() {
        val date = elements("res/layout/view_lock_glance.xml").single { it.getAttributeNS(ns, "id") == "@+id/lock_glance_date" }
        assertEquals("center_horizontal", date.getAttributeNS(ns, "gravity"))
        // Its parent centres it too: centred in both directions, as the clock above it.
        val root = elements("res/layout/view_lock_glance.xml").first()
        assertEquals("center_horizontal", root.getAttributeNS(ns, "gravity"))
    }

    @Test
    fun `the lock's 112 confirm is Vibb night like the phone book's`() {
        val emergency = src("calls/EmergencyCall.kt")
        assertTrue("AlertDialog.Builder(activity, R.style.KidAlertDialog)" in emergency)
        assertFalse("AlertDialogCustom" in emergency)
        // Both of the PIN lock's pills (keypad and ring card) and the time-rule screen use it.
        assertEquals(2, Regex("""EmergencyCall\.confirm\(this\)""").findAll(src("lock/PinLockActivity.kt")).count())
        assertTrue("EmergencyCall.confirm(this)" in src("ui/LockActivity.kt"))
        // The flow is unchanged: confirm, then onCalling, then the call.
        val confirm = emergency.substringAfter("fun confirm(").substringBefore("fun call(")
        assertTrue(confirm.indexOf("onCalling()") in 0 until confirm.indexOf("call(activity)"))
        assertTrue(".setNegativeButton(R.string.calls_cancel, null)" in confirm)
    }
}
