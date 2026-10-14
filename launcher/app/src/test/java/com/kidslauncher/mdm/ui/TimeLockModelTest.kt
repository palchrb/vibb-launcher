package com.kidslauncher.mdm.ui

import com.kidslauncher.mdm.timerules.BudgetUse
import com.kidslauncher.mdm.timerules.KIND_BEDTIME
import com.kidslauncher.mdm.timerules.KIND_CUSTOM
import com.kidslauncher.mdm.timerules.KIND_SCHOOL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/** The time-rule screen as one calm card (design 34 P6, QA L4): its pure rules, its strings, its
 * glyphs, its layout and the wiring that keeps emergency calling and TalkBack as they must be. */
class TimeLockModelTest {
    private fun file(path: String): File =
        listOf("src/main/$path", "app/src/main/$path").map(::File).first { it.exists() }

    private fun strings(dir: String): Map<String, String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(file("res/$dir/strings.xml"))
        val nodes = doc.getElementsByTagName("string")
        return (0 until nodes.length).map { nodes.item(it) as Element }
            .associate { it.getAttribute("name") to it.textContent.replace("\\'", "'") }
    }

    private val en = strings("values")
    private val nb = strings("values-nb")

    private fun duration(minutes: Int, s: Map<String, String>) =
        durationText(minutes, s.getValue("duration_unit_hours"), s.getValue("duration_unit_minutes"))

    private fun sentence(minutes: Int, s: Map<String, String>) =
        s.getValue("lock_budget_allowance").replace("%1\$s", duration(minutes, s))

    private val ns = "http://schemas.android.com/apk/res/android"

    private fun layout(name: String): List<Element> {
        val doc = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder()
            .parse(file("res/layout/$name.xml"))
        val nodes = doc.getElementsByTagName("*")
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    private fun List<Element>.byId(id: String) = single { it.getAttributeNS(ns, "id") == "@+id/$id" }

    private fun Element.attr(name: String) = getAttributeNS(ns, name)

    private fun dp(value: String) = value.removeSuffix("dp").toFloat()

    @Test
    fun `the kind's icon - hourglass for screen time, moon for bedtime, book for school, a dot otherwise`() {
        assertEquals(LockGlyph.HOURGLASS, lockGlyph(null))
        assertEquals(LockGlyph.MOON, lockGlyph(KIND_BEDTIME))
        assertEquals(LockGlyph.BOOK, lockGlyph(KIND_SCHOOL))
        assertEquals(LockGlyph.DOT, lockGlyph(KIND_CUSTOM))
        assertEquals(LockGlyph.DOT, lockGlyph("homework"))
    }

    @Test
    fun `durations read as hours and minutes in both languages, one and many alike`() {
        val cases = mapOf(
            0 to ("0 min" to "0 min"), 1 to ("1 min" to "1 min"), 45 to ("45 min" to "45 min"),
            59 to ("59 min" to "59 min"), 60 to ("1 t" to "1 h"), 61 to ("1 t 1 min" to "1 h 1 min"),
            90 to ("1 t 30 min" to "1 h 30 min"), 120 to ("2 t" to "2 h"), 135 to ("2 t 15 min" to "2 h 15 min"),
            1440 to ("24 t" to "24 h"), -5 to ("0 min" to "0 min"),
        )
        for ((minutes, texts) in cases) {
            assertEquals("nb $minutes", texts.first, duration(minutes, nb))
            assertEquals("en $minutes", texts.second, duration(minutes, en))
        }
    }

    @Test
    fun `the sentence names today's allowance - budget plus extra minutes - in nb and en`() {
        assertEquals("Du har brukt dagens 1 t 30 min.", sentence(90, nb))
        assertEquals("You've used today's 1 h 30 min.", sentence(90, en))
        assertEquals("Du har brukt dagens 45 min.", sentence(45, nb))
        assertEquals("You've used today's 2 h.", sentence(120, en))
        // Budget 60 + 30 extra minutes from a parent's lift: 1 h 30 min.
        assertEquals(BudgetLine.Used(90), budgetLine(false, BudgetUse(usedMs = 90 * 60_000L, budgetMinutes = 60, extraMinutes = 30), true))
        assertEquals(BudgetLine.Used(60), budgetLine(false, BudgetUse(usedMs = 60 * 60_000L, budgetMinutes = 60, extraMinutes = 0), true))
    }

    @Test
    fun `an overrun reads the same sentence - never 6 av 1`() {
        // Six minutes counted against a budget of one (lowered during the day): the allowance, not the use.
        val line = budgetLine(false, BudgetUse(usedMs = 6 * 60_000L, budgetMinutes = 1, extraMinutes = 0), true)
        assertEquals(BudgetLine.Used(1), line)
        assertEquals("Du har brukt dagens 1 min.", sentence(1, nb))
        // Used far beyond the allowance: still the allowance.
        assertEquals(BudgetLine.Used(30), budgetLine(false, BudgetUse(usedMs = 500 * 60_000L, budgetMinutes = 20, extraMinutes = 10), true))
        // The old "%d of %d minutes" string is gone from both languages, and nothing counts used minutes.
        for (s in listOf(en, nb)) {
            assertFalse("lock_budget_used" in s)
            assertTrue(s.values.none { Regex("%1\\\$d (of|av) %2\\\$d minut").containsMatchIn(it) })
        }
        val activity = file("java/com/kidslauncher/mdm/ui/LockActivity.kt").readText()
        assertFalse("usedMs" in activity)
    }

    @Test
    fun `no budget today, a budget used up under a rule, and nothing to say`() {
        assertEquals(BudgetLine.NoneToday, budgetLine(false, BudgetUse(0, budgetMinutes = 0, extraMinutes = 0), true))
        assertEquals(BudgetLine.Used(15), budgetLine(false, BudgetUse(0, budgetMinutes = 0, extraMinutes = 15), false))
        assertEquals("Ingen skjermtid i dag.", nb.getValue("lock_budget_none"))
        assertEquals(BudgetLine.AlsoUsed, budgetLine(true, BudgetUse(70 * 60_000L, 60, 0), true))
        assertNull(budgetLine(true, BudgetUse(10 * 60_000L, 60, 0), false))
        assertNull(budgetLine(false, null, false))
        assertNull(budgetLine(false, BudgetUse(0, budgetMinutes = null, extraMinutes = 0), false))
    }

    @Test
    fun `rows - the phone book first while calls are managed and allowed, then the apps by name`() {
        val apps = listOf(
            LockApp("me.vibb.music", "Musikk"),
            LockApp("com.example.chat", "meldinger"),
            LockApp("com.example.books", "Bøker"),
            LockApp("me.vibb.music", "Musikk"),
        )
        val rows = timeLockRows(callsManaged = true, callsAllowed = true, phoneBookLabel = "Telefonbok", apps = apps)
        assertEquals(listOf("Telefonbok", "Bøker", "meldinger", "Musikk"), rows.map { it.label })
        assertEquals(LockRow.PhoneBook("Telefonbok"), rows.first())
        assertEquals(LockRow.App("me.vibb.music", "Musikk"), rows.last())
        // A no-calls rule (school) or unmanaged calls: no phone book, the apps stay.
        assertEquals(3, timeLockRows(true, false, "Telefonbok", apps).size)
        assertTrue(timeLockRows(false, true, "Telefonbok", apps).none { it is LockRow.PhoneBook })
        assertTrue(timeLockRows(false, true, "Telefonbok", emptyList()).isEmpty())
        assertEquals(listOf<LockRow>(LockRow.PhoneBook("Phone book")), timeLockRows(true, true, "Phone book", emptyList()))
    }

    @Test
    fun `the hourglass and the moon are converted Material Symbols, the book too, the dot our own`() {
        val converted = mapOf("hourglass" to "hourglass_bottom", "bedtime" to "bedtime", "school" to "menu_book")
        for ((key, symbol) in converted) {
            val text = file("res/drawable/lock_glyph_$key.xml").readText()
            assertTrue(key, "Converted from Material Symbols \"$symbol\"" in text)
            assertTrue(key, "Apache License 2.0" in text && "scripts/material-symbols.sh" in text)
        }
        assertTrue("lock_glyph_dot.xml" in (file("res/drawable/lock_glyph_dot.xml").name))
        val script = listOf(File("../scripts/material-symbols.sh"), File("../../scripts/material-symbols.sh")).first { it.exists() }.readText()
        assertTrue("hourglass_bottom 4de2c88bf639a1f5f114be2cdd2b2e36611500e8d3adb6d57402beae91b3aa17" in script)
        assertTrue("""LOCK_GLYPHS = {"hourglass": "hourglass_bottom", "bedtime": "bedtime", "school": "menu_book"}""" in script)
        // Every glyph the model can pick is wired to its drawable.
        val activity = file("java/com/kidslauncher/mdm/ui/LockActivity.kt").readText()
        val wired = mapOf(
            LockGlyph.HOURGLASS to "lock_glyph_hourglass", LockGlyph.MOON to "lock_glyph_bedtime",
            LockGlyph.BOOK to "lock_glyph_school", LockGlyph.DOT to "lock_glyph_dot",
        )
        assertEquals(LockGlyph.entries.toSet(), wired.keys)
        for ((glyph, res) in wired) assertTrue("$glyph", "LockGlyph.$glyph -> R.drawable.$res" in activity)
        assertTrue("setImageResource(glyphRes(lockGlyph(rule?.kind)))" in activity)
    }

    @Test
    fun `the screen - clock, icon, title, one card, and two quiet buttons at the bottom`() {
        val views = layout("activity_lock")
        val clock = views.byId("lock_clock")
        assertEquals("52sp", clock.attr("textSize"))
        assertEquals("@font/nunito_bold", clock.attr("fontFamily"))
        val icon = views.byId("lock_kind_icon")
        assertEquals("no", icon.attr("importantForAccessibility"))
        assertEquals("@drawable/bg_lock_kind_disc", icon.attr("background"))
        assertEquals("@color/kid_accent", icon.attr("tint"))
        assertEquals("true", views.byId("lock_title").attr("accessibilityHeading"))
        assertEquals("@drawable/bg_lock_card", views.byId("lock_rows").attr("background"))
        // No saturated full-width buttons any more: nothing on this screen is tinted a colour field.
        assertTrue(views.none { it.attr("backgroundTint").isNotEmpty() || it.attr("style").isNotEmpty() })
        // The order: clock, icon, title, message, card, then emergency and the code at the bottom.
        val ids = views.map { it.attr("id").removePrefix("@+id/") }.filter { it.isNotEmpty() }
        assertEquals(
            listOf("lock_clock", "lock_kind_icon", "lock_title", "lock_message", "lock_rows", "lock_emergency_button", "lock_unlock_code_button"),
            ids,
        )
    }

    @Test
    fun `emergency call is a red text button of at least 48 dp, like the glance page's`() {
        val views = layout("activity_lock")
        val emergency = views.byId("lock_emergency_button")
        assertEquals("@string/calls_emergency", emergency.attr("text"))
        assertEquals("@color/kid_emergency_text", emergency.attr("textColor"))
        assertTrue(dp(emergency.attr("minHeight")) >= 48f)
        assertEquals("wrap_content", emergency.attr("layout_height"))
        assertEquals("true", emergency.attr("clickable"))
        assertEquals("true", emergency.attr("focusable"))
        assertTrue(emergency.attr("visibility").isEmpty()) // always shown
        // The same red as the glance page's "Nødsamtale".
        val glance = layout("view_lock_glance").byId("lock_glance_emergency")
        assertEquals(glance.attr("textColor"), emergency.attr("textColor"))
        val code = views.byId("lock_unlock_code_button")
        assertEquals("@color/kid_core_dim", code.attr("textColor"))
        assertTrue(dp(code.attr("minHeight")) >= 48f)
    }

    @Test
    fun `each row is one button TalkBack reads by its name - icon, label and chevron are decorative`() {
        val views = layout("item_lock_row")
        val root = views.first()
        assertEquals("true", root.attr("clickable"))
        assertEquals("true", root.attr("focusable"))
        assertTrue(dp(root.attr("minHeight")) >= 48f)
        val children = views.drop(1)
        assertEquals(3, children.size)
        assertTrue(children.all { it.attr("importantForAccessibility") == "no" })
        assertEquals("2", views.byId("lock_row_label").attr("maxLines"))
        assertEquals("@color/kid_core", views.byId("lock_row_label").attr("textColor"))
        val activity = file("java/com/kidslauncher/mdm/ui/LockActivity.kt").readText()
        assertTrue("view.root.contentDescription = row.label" in activity)
        assertTrue("asButton(view.root)" in activity)
        assertTrue("info.className = Button::class.java.name" in activity)
        assertTrue("asButton(binding.lockEmergencyButton)" in activity)
    }

    @Test
    fun `the targets and the emergency flow are what they were`() {
        val activity = file("java/com/kidslauncher/mdm/ui/LockActivity.kt").readText()
        // Emergency: the same confirm (Telecom, then EmergencyDialer's fallback), always bound in onCreate.
        assertTrue("binding.lockEmergencyButton.setOnClickListener { EmergencyCall.confirm(this) }" in activity)
        assertTrue("binding.lockUnlockCodeButton.setOnClickListener { showUnlockCodeDialog() }" in activity)
        assertTrue("startActivity(PhoneBookActivity.intent(this))" in activity)
        assertTrue("startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))" in activity)
        // The rows come from the lock's own decision, filtered as before (launchable, not suspended).
        assertTrue("timeLockRows(callsManaged, lock?.callsAllowed != false," in activity)
        assertTrue("openableApps(lock?.usableApps.orEmpty())" in activity)
        assertTrue("CameraLock.suspendedForLists(pkg, pm.isPackageSuspended(pkg))" in activity)
        // Back stays off.
        assertTrue("override fun handleOnBackPressed() {}" in activity)
    }
}
