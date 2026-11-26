package com.kidslauncher.mdm.lock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.ceil

/**
 * The lock's Nødsamtale pills (design 34 P7): the one under the glance page (activity_pin_lock.xml) and the VoIP ring
 * card's. Design 132: our keypad, its dots and "Parent code" went - their layout budget with them.
 */
class PinLockLayoutTest {
    private companion object {
        /** Width of a character in em - a generous average for Nunito's bold weights. */
        const val CHAR_EM = 0.6f
        const val STATUS_BAR_DP = 48f
    }

    private val ns = "http://schemas.android.com/apk/res/android"
    private fun file(path: String) = listOf("src/main/$path", "app/src/main/$path").map(::File).first { it.exists() }

    private val root: Element = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        .newDocumentBuilder().parse(file("res/layout/activity_pin_lock.xml")).documentElement

    private fun Element.dim(name: String): Float =
        getAttributeNS(ns, name).removeSuffix("dp").removeSuffix("sp").toFloatOrNull() ?: 0f

    private fun kids(e: Element): List<Element> =
        (0 until e.childNodes.length).map { e.childNodes.item(it) }.filterIsInstance<Element>()

    private fun children(): List<Element> = kids(root)

    private fun id(e: Element) = e.getAttributeNS(ns, "id").substringAfter("/")

    private fun byId(name: String): Element = root.getElementsByTagName("*").let { all ->
        (0 until all.length).map { all.item(it) as Element }.single { id(it) == name }
    }

    /** The longest translation (nb, en) of a `@string/` text, in characters. */
    private fun longestText(e: Element): Int {
        val name = e.getAttributeNS(ns, "text").removePrefix("@string/")
        return listOf("res/values/strings.xml", "res/values-nb/strings.xml").maxOf { path ->
            Regex("<string name=\"$name\">([^<]*)</string>").find(file(path).readText())?.groupValues?.get(1)?.length ?: 0
        }
    }

    /**
     * Lines of a text at [scale] in [widthDp]: [CHAR_EM] em a character (Nunito is narrower); the
     * prompt always two (it wraps between the arrow's columns, QA Low 5).
     */
    private fun lines(e: Element, scale: Float, widthDp: Float): Int {
        if (id(e) == "pin_prompt") return 2
        val wide = longestText(e) * CHAR_EM * e.dim("textSize") * scale
        return maxOf(1, ceil(wide / widthDp).toInt())
    }

    /**
     * An element's height (dp) at font scale [scale] in [widthDp], margins not included. Text grows
     * linearly with the scale (Android 14's non-linear scale grows large text less, so this is the
     * upper bound): `max(minHeight, lines x sp x scale x 1.4 + padding)` (QA M6).
     */
    private fun height(e: Element, scale: Float, widthDp: Float): Float {
        e.getAttributeNS(ns, "layout_height").takeIf { it.endsWith("dp") }?.let { return it.removeSuffix("dp").toFloat() }
        val padding = e.dim("paddingTop") + e.dim("paddingBottom")
        val inner = widthDp - e.dim("paddingStart") - e.dim("paddingEnd")
        val content = when (e.tagName) {
            "TextView" -> lines(e, scale, inner) * e.dim("textSize") * scale * 1.4f
            "LinearLayout" -> if (e.getAttributeNS(ns, "orientation") == "horizontal") {
                // A wrapping child gets what its fixed-width siblings and the margins leave.
                val fixed = kids(e).sumOf { (it.dim("layout_marginStart") + it.dim("layout_marginEnd") + it.dim("layout_width")).toDouble() }
                kids(e).maxOfOrNull { height(it, scale, inner - fixed.toFloat()) } ?: 0f
            } else {
                kids(e).sumOf { (it.dim("layout_marginTop") + it.dim("layout_marginBottom") + height(it, scale, inner)).toDouble() }.toFloat()
            }
            "FrameLayout" -> kids(e).maxOfOrNull {
                height(it, scale, inner - it.dim("layout_marginStart") - it.dim("layout_marginEnd"))
            } ?: 0f
            else -> 0f
        }
        return maxOf(padding + content, e.dim("minHeight"))
    }

    private fun contentWidthDp(screenWidthDp: Float) = screenWidthDp - root.dim("paddingStart") - root.dim("paddingEnd")

    /** Height of everything but the keypad (dp) at [scale], margins and the root's padding included. */
    private fun fixedHeightDp(scale: Float = 1f, screenWidthDp: Float = 320f): Float {
        var total = root.dim("paddingTop") + root.dim("paddingBottom")
        for (child in children()) {
            total += child.dim("layout_marginTop") + child.dim("layout_marginBottom")
            if (id(child) != "pin_keypad") total += height(child, scale, contentWidthDp(screenWidthDp))
        }
        return total
    }

    private fun keypadHeightDp(screenHeightDp: Float, screenWidthDp: Float, statusBarDp: Float, navBarDp: Float, scale: Float) =
        screenHeightDp - statusBarDp - navBarDp - fixedHeightDp(scale, screenWidthDp)

    @Test
    fun `the emergency button is the tonal pill, its label and glyph the emergency red (design 34 P7)`() {
        val emergency = byId("pin_emergency")
        assertEquals("@drawable/bg_pin_emergency_tonal", emergency.getAttributeNS(ns, "background"))
        assertTrue(file("res/drawable/bg_pin_emergency_tonal.xml").readText().contains("@color/kid_emergency_tonal"))
        assertEquals("@color/kid_emergency_text", byId("pin_emergency_icon").getAttributeNS(ns, "tint"))
        assertEquals("@color/kid_emergency_text", byId("pin_emergency_label").getAttributeNS(ns, "textColor"))
        // The flow is untouched: the same clickable view, which the glance page's link clicks too.
        assertEquals("true", emergency.getAttributeNS(ns, "clickable"))
        val activity = file("java/com/kidslauncher/mdm/lock/PinLockActivity.kt").readText()
        assertTrue(activity.contains("binding.pinEmergency.performClick()"))
        assertTrue(activity.contains("binding.pinEmergency.setOnClickListener"))
    }

    @Test
    fun `the VoIP ring card's Nødsamtale follows P7 too (qa-34-s8-code L2)`() {
        val ring = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
            .newDocumentBuilder().parse(file("res/layout/view_voip_ring.xml")).documentElement
        val all = ring.getElementsByTagName("*").let { list -> (0 until list.length).map { list.item(it) as Element } }
        fun ringId(name: String) = all.single { id(it) == name }
        val pill = ringId("voip_ring_emergency")
        assertEquals("@drawable/bg_pin_emergency_tonal", pill.getAttributeNS(ns, "background"))
        assertTrue(pill.dim("minHeight") >= 48f)
        assertEquals("wrap_content", pill.getAttributeNS(ns, "layout_height"))
        assertEquals("@color/kid_emergency_text", ringId("voip_ring_emergency_icon").getAttributeNS(ns, "tint"))
        val label = ringId("voip_ring_emergency_label")
        assertEquals("@color/kid_emergency_text", label.getAttributeNS(ns, "textColor"))
        for (attr in listOf("maxLines", "singleLine", "lines", "ellipsize")) assertTrue(attr, label.getAttributeNS(ns, attr).isEmpty())
        // Same size as the keypad's pill: it grows with the text the same way.
        val width = contentWidthDp(320f)
        for (scale in listOf(1f, 1.3f, 2f)) assertEquals(height(byId("pin_emergency"), scale, width), height(pill, scale, width), 0.01f)
        // Its click path is unchanged, the lock's (design 73: emergency numbers only while locked).
        val activity = file("java/com/kidslauncher/mdm/lock/PinLockActivity.kt").readText()
        assertTrue(activity.contains("voipRing.voipRingEmergency.setOnClickListener {\n            trackDialog(EmergencyCall.confirm(this, locked = true) { PinLockRuntime.emergencyFlowStarted() })"))
    }

    @Test
    fun `both Nødsamtale pills announce as a button (qa-34-s8-code L3)`() {
        val activity = file("java/com/kidslauncher/mdm/lock/PinLockActivity.kt").readText()
        assertTrue(activity.contains("binding.pinEmergency.accessibilityDelegate = ButtonRole"))
        assertTrue(activity.contains("voipRing.voipRingEmergency.accessibilityDelegate = ButtonRole"))
        val role = file("java/com/kidslauncher/mdm/lock/ButtonRole.kt").readText()
        assertTrue(role.contains("super.onInitializeAccessibilityNodeInfo(host, info)"))
        assertTrue(role.contains("info.className = Button::class.java.name"))
        // Only the role: no click, action or state is touched.
        assertFalse(role.contains("performAccessibilityAction") || role.contains("addAction") || role.contains("isClickable"))
    }

}
