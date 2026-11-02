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
        )
        val rows = timeLockRows(callsManaged = true, callsAllowed = true, phoneBookLabel = "Telefonbok", apps = apps)
        assertEquals(listOf("Telefonbok", "Bøker", "meldinger", "Musikk"), rows.map { it.label })
        assertEquals(LockRow.PhoneBook("Telefonbok"), rows.first())
        assertEquals(LockRow.App("me.vibb.music", "Musikk"), rows.last())
        // Two apps the parent named alike are two rows (qa-34-s7-code N2), ordered by package.
        val alike = timeLockRows(false, true, "Telefonbok", listOf(LockApp("com.b.chat", "Chat"), LockApp("com.a.chat", "Chat")))
        assertEquals(listOf<LockRow>(LockRow.App("com.a.chat", "Chat"), LockRow.App("com.b.chat", "Chat")), alike)
        // A no-calls rule (school) or unmanaged calls: no phone book, the apps stay.
        assertEquals(3, timeLockRows(true, false, "Telefonbok", apps).size)
        assertTrue(timeLockRows(false, true, "Telefonbok", apps).none { it is LockRow.PhoneBook })
        assertTrue(timeLockRows(false, true, "Telefonbok", emptyList()).isEmpty())
        assertEquals(listOf<LockRow>(LockRow.PhoneBook("Phone book")), timeLockRows(true, true, "Phone book", emptyList()))
        // Kalender (PLAN 2026-11-02): after the phone book, before the apps.
        val withCalendar = timeLockRows(true, true, "Telefonbok", apps, calendarLabel = "Kalender")
        assertEquals(listOf("Telefonbok", "Kalender", "Bøker", "meldinger", "Musikk"), withCalendar.map { it.label })
        assertEquals(LockRow.Calendar("Kalender"), withCalendar[1])
        assertEquals(listOf<LockRow>(LockRow.Calendar("Kalender")), timeLockRows(true, false, "Telefonbok", emptyList(), "Kalender"))
    }

    /** PLAN "one usable thing opens straight after the PIN" (2026-11-02): only the card's only row, only on the unlock's
     * fresh mark, never over a glance row the lock handed over. */
    @Test
    fun `after an unlock the only usable thing opens, once`() {
        val music = LockRow.App("me.vibb.music", "Musikk")
        val calendar = LockRow.Calendar("Kalender")
        val phoneBook = LockRow.PhoneBook("Telefonbok")
        assertEquals(music, unlockOpenRow(marked = true, handedOver = false, rows = listOf(music)))
        assertEquals(calendar, unlockOpenRow(true, false, listOf(calendar)))
        assertEquals("the phone book counts as one", phoneBook, unlockOpenRow(true, false, listOf(phoneBook)))
        assertNull("two things: the card stays", unlockOpenRow(true, false, listOf(phoneBook, music)))
        assertNull(unlockOpenRow(true, false, listOf(calendar, music)))
        assertNull("nothing usable", unlockOpenRow(true, false, emptyList()))
        assertNull("no unlock (Back, Home, a redirect)", unlockOpenRow(false, false, listOf(music)))
        assertNull("the glance's row opens instead", unlockOpenRow(true, true, listOf(music)))
        // The mark is fresh for the screen's coming up only.
        assertTrue(unlockMarkFresh(1_000, 1_000 + UNLOCK_OPEN_MS))
        assertFalse(unlockMarkFresh(1_000, 1_001 + UNLOCK_OPEN_MS))
        assertFalse("none", unlockMarkFresh(0, 5_000))
        assertFalse("a clock before the mark", unlockMarkFresh(5_000, 4_000))
        // Wiring: the mark is set where the unlock brings the time-rule screen, taken once in onResume after the
        // hand-over, and the open is a row's tap.
        val runtime = code("java/com/kidslauncher/mdm/lock/PinLockRuntime.kt")
        val after = runtime.substringAfter("private fun afterUnlock(context: Context)").substringBefore("\n    }\n")
        assertTrue(after, after.indexOf("LockActivity.markUnlock()") in 0 until after.indexOf("LockActivity.start(context)"))
        assertEquals(1, Regex("""markUnlock\(\)""").findAll(runtime).count())
        val activity = code("java/com/kidslauncher/mdm/ui/LockActivity.kt")
        val resume = activity.substringAfter("override fun onResume()").substringBefore("\n    }\n")
        val pending = resume.indexOf("GlanceOpener.handOverPending()")
        assertTrue(resume, pending in 0 until resume.indexOf("GlanceOpener.openHandedOver(this)"))
        assertTrue(resume, resume.indexOf("takeUnlockMark()") > resume.indexOf("GlanceOpener.openHandedOver(this)"))
        assertTrue(resume.contains("unlockOpenRow(marked, handedOver, shownRows)"))
        assertTrue(resume.contains("open(only)"))
        assertTrue(activity.contains("view.root.setOnClickListener { open(row) }"))
        assertEquals("taken in onResume alone", 1, Regex("""(?<!fun )takeUnlockMark\(\)""").findAll(activity).count())
        // Kalender's row only when the lock leaves it usable and it is on for the phone.
        assertTrue(activity.contains("lock?.calendarUsable == true && CalendarStore.dagenOn()"))
        assertTrue(activity.contains("is LockRow.Calendar -> startActivity(com.kidslauncher.mdm.ui.day.DayActivity.intent(this))"))
    }

    @Test
    fun `the hourglass and the moon are converted Material Symbols, the book too, the dot our own`() {
        val converted = mapOf("hourglass" to "hourglass_bottom", "bedtime" to "bedtime", "school" to "menu_book")
        for ((key, symbol) in converted) {
            val text = file("res/drawable/lock_glyph_$key.xml").readText()
            assertTrue(key, "Converted from Material Symbols \"$symbol\"" in text)
            assertTrue(key, "Apache License 2.0" in text && "scripts/material-symbols.sh" in text)
        }
        // The dot is drawn by hand (qa-34-s7-code N5): no converted symbol, one filled white circle path.
        val dot = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder()
            .parse(file("res/drawable/lock_glyph_dot.xml"))
        assertFalse("Converted from" in file("res/drawable/lock_glyph_dot.xml").readText())
        assertEquals("vector", dot.documentElement.tagName)
        val paths = dot.getElementsByTagName("path")
        assertEquals(1, paths.length)
        val path = paths.item(0) as Element
        assertEquals("#FFFFFFFF", path.getAttributeNS(ns, "fillColor"))
        assertTrue(Regex("""^M12,7a5,5 0,1 1,0 10a5,5 0,1 1,0 -10z$""").matches(path.getAttributeNS(ns, "pathData")))
        assertEquals("24", dot.documentElement.getAttributeNS(ns, "viewportWidth"))
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
        assertTrue("this, binding.lockRuleCard, glyphRes(lockGlyph(rule?.kind))," in activity)
    }

    /** Launcher round L6 (the user, 2026-10-26: the screen looked dated next to the lock glance): the glance's face. */
    @Test
    fun `the screen - the glance's clock face and rule card, one card of rows, Emergency call at the bottom`() {
        val views = layout("activity_lock")
        // No saturated full-width buttons: nothing on this screen is tinted a colour field or styled apart.
        assertTrue(views.none { it.attr("backgroundTint").isNotEmpty() || it.attr("style").isNotEmpty() })
        // The order: the clock, the date, the alarm line, the rule card, the rows' card, then emergency at the bottom - and
        // no code entry (2026-11-01, the parent lifts a rule from the PWA only), no title or icon of its own any more.
        val ids = views.map { it.attr("id").removePrefix("@+id/") }.filter { it.isNotEmpty() }
        assertEquals(
            listOf("lock_top", "lock_clock", "lock_date", "lock_alarm", "lock_rule_card", "lock_rows", "lock_emergency_button"),
            ids,
        )
        // The rule name is the screen's heading (TalkBack's heading navigation), on this screen's card only.
        val activity = code("java/com/kidslauncher/mdm/ui/LockActivity.kt")
        assertTrue("binding.lockRuleCard.root.isAccessibilityHeading = true" in body(activity, "override fun onCreate("))
        val glance = code("java/com/kidslauncher/mdm/lock/GlancePanel.kt") + code("java/com/kidslauncher/mdm/lock/RuleCardView.kt")
        assertFalse("isAccessibilityHeading" in glance)
        assertEquals("", layout("view_lock_rule_card").first().attr("accessibilityHeading"))
        // The rule card is the glance's own view, included by both screens.
        val card = views.byId("lock_rule_card")
        assertEquals("include", card.tagName)
        assertEquals("@layout/view_lock_rule_card", card.getAttribute("layout"))
        assertEquals(card.getAttribute("layout"), layout("view_lock_glance").byId("lock_rule_card").getAttribute("layout"))
        // The rows sit 14 dp under it, as the glance's rows under its card (GlancePanel's ROWS_TOP_DP).
        assertEquals("14dp", views.byId("lock_rows").attr("layout_marginTop"))
        assertTrue("const val ROWS_TOP_DP = 14f" in file("java/com/kidslauncher/mdm/lock/GlancePanel.kt").readText())
    }

    /** L6: one design - each part the glance also has is drawn with the glance's attributes, so a change to one face
     * fails here until the other follows. */
    @Test
    fun `the clock, the date, the alarm line and Emergency call are the glance's`() {
        val screen = layout("activity_lock")
        val glance = layout("view_lock_glance")
        val look = listOf(
            "fontFamily", "textColor", "textSize", "includeFontPadding", "gravity", "format12Hour", "format24Hour",
            "layout_width", "layout_height", "layout_marginTop", "visibility",
        )
        for ((ours, theirs) in listOf("lock_clock" to "lock_glance_clock", "lock_date" to "lock_glance_date", "lock_alarm" to "lock_glance_alarm")) {
            val a = screen.byId(ours)
            val b = glance.byId(theirs)
            assertEquals(ours, b.tagName, a.tagName)
            for (attr in look) assertEquals("$ours $attr", b.attr(attr), a.attr(attr))
        }
        // The emergency button: the same text button; the glance's side margins are room for its flashlight.
        val button = listOf("fontFamily", "textColor", "textSize", "gravity", "minHeight", "paddingStart", "paddingEnd", "background", "text", "clickable", "focusable")
        for (attr in button) assertEquals("emergency $attr", glance.byId("lock_glance_emergency").attr(attr), screen.byId("lock_emergency_button").attr(attr))
        assertEquals(glance.byId("lock_glance_bottom").attr("layout_marginTop"), screen.byId("lock_emergency_button").attr("layout_marginTop"))
        // The code: both screens build the one face and bind the one card; the ground is the glance's.
        val activity = code("java/com/kidslauncher/mdm/ui/LockActivity.kt")
        assertTrue("clockFace = LockClockFace(this, binding.root, binding.lockTop, binding.lockClock, binding.lockDate, binding.lockAlarm," in activity)
        assertTrue("clockFace.follow()" in activity)
        assertTrue("clockFace.bindAlarm()" in body(activity, "private fun render()"))
        assertTrue("RuleCardView.bind(" in body(activity, "private fun render()"))
        assertTrue("if (state.lockReady) binding.root.background = lockGround(state)" in activity)
        assertTrue("WallpaperStore.addListener(wallpaperListener)" in activity && "WallpaperStore.removeListener(wallpaperListener)" in activity)
        // A cold start holds the first frame for the ground as the PIN lock does (at most LOCK_GROUND_WAIT_MS).
        assertTrue("if (holdForLockGround(WallpaperStore.state.lockReady, since, now)) return false" in body(activity, "override fun onStart()"))
        // The profile clock's box may reach into the status bar's padding: the root never clips it.
        assertEquals("false", screen.first().attr("clipToPadding"))
        assertTrue("AlarmManager.ACTION_NEXT_ALARM_CLOCK_CHANGED" in activity && "unregisterReceiver(alarmChanged)" in activity)
        val panel = code("java/com/kidslauncher/mdm/lock/GlancePanel.kt")
        assertTrue("LockClockFace(" in panel && "RuleCardView.bind(" in panel)
    }

    @Test
    fun `emergency call is a red text button of at least 48 dp, like the glance page's`() {
        val emergency = layout("activity_lock").byId("lock_emergency_button")
        assertEquals("@string/pin_lock_emergency", emergency.attr("text"))
        assertEquals("@color/kid_emergency_text", emergency.attr("textColor"))
        assertTrue(dp(emergency.attr("minHeight")) >= 48f)
        assertEquals("wrap_content", emergency.attr("layout_height"))
        assertTrue(emergency.attr("visibility").isEmpty()) // always shown
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
        // The role is step 8's shared ButtonRole, on the rows and the emergency text button (qa-34-s7-code N1).
        assertTrue("view.root.accessibilityDelegate = ButtonRole" in activity)
        assertTrue("binding.lockEmergencyButton.accessibilityDelegate = ButtonRole" in activity)
        assertTrue("info.className = Button::class.java.name" in file("java/com/kidslauncher/mdm/lock/ButtonRole.kt").readText())
        // The card clips the rows' ripple to its rounded corners (N3).
        assertEquals("true", layout("activity_lock").byId("lock_rows").attr("clipToOutline"))
    }

    @Test
    fun `the targets and the emergency flow are what they were`() {
        val activity = file("java/com/kidslauncher/mdm/ui/LockActivity.kt").readText()
        // Emergency: the same confirm (Telecom, then EmergencyDialer's fallback), always bound in onCreate.
        assertTrue("binding.lockEmergencyButton.setOnClickListener { EmergencyCall.confirm(this) }" in activity)
        // No code entry on this screen (the user, 2026-10-26: PWA only): no button, no dialog, no override.
        for (gone in listOf("lockUnlockCode", "showUnlockCodeDialog", "ParentPinDialog", "ParentPinEntry", "OfflineOverride")) {
            assertFalse(gone, gone in activity)
        }
        for (dir in listOf("values", "values-nb")) {
            val s = strings(dir)
            for (name in listOf("lock_enter_unlock_code", "lock_unlock_code_dialog_title", "lock_unlock_code_not_configured")) {
                assertFalse("$dir $name", name in s)
            }
        }
        assertTrue("startActivity(PhoneBookActivity.intent(this))" in activity)
        assertTrue("startActivity(launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))" in activity)
        // The rows come from the lock's own decision, filtered as before (launchable, not suspended).
        assertTrue("timeLockRows(callsManaged, lock?.callsAllowed != false," in activity)
        assertTrue("openableApps(lock?.usableApps.orEmpty())" in activity)
        assertTrue("CameraLock.suspendedForLists(pkg, pm.isPackageSuspended(pkg))" in activity)
        // Back stays off.
        assertTrue("override fun handleOnBackPressed() {}" in activity)
    }

    /** E6's L5: the redirects stacked a LockActivity per call in Home's task - the one way in reuses the instance. */
    @Test
    fun `every start reuses the screen in Home's task - one instance, never a stack`() {
        val activity = code("java/com/kidslauncher/mdm/ui/LockActivity.kt")
        val startNow = body(activity, "private fun startNow(")
        assertTrue(
            "intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)" in startNow,
        )
        // A reused instance that is already resumed gets no onStart: its new intent draws a moved end or budget.
        val newIntent = body(activity, "override fun onNewIntent(intent: Intent)")
        assertTrue("super.onNewIntent(intent)" in newIntent && "setIntent(intent)" in newIntent && "render()" in newIntent)
        // No other intent for it anywhere: every redirect goes through LockActivity.start.
        val main = listOf(File("src/main/java"), File("app/src/main/java")).first { it.exists() }
        val builders = main.walkTopDown().filter { it.extension == "kt" }
            .filter { Regex("""Intent\([^)]*\bLockActivity::class""").containsMatchIn(it.readText()) }.map { it.name }.toList()
        assertEquals(listOf("LockActivity.kt"), builders)
        // A standard activity in Home's task: no own affinity, no launch mode the flags would fight.
        val entry = file("AndroidManifest.xml").readText().substringAfter("android:name=\".ui.LockActivity\"").substringBefore("/>")
        assertFalse("launchMode" in entry || "taskAffinity" in entry)
    }

    private fun code(path: String): String =
        file(path).readText().replace(Regex("""/\*[\s\S]*?\*/"""), "").replace(Regex("""(?m)//.*$"""), "")

    /** A function's body: from its signature to the first line that closes it at its indent. */
    private fun body(code: String, signature: String): String {
        val start = code.indexOf(signature)
        assertTrue(signature, start >= 0)
        val indent = code.lastIndexOf('\n', start).let { code.substring(it + 1, start) }
        return code.substring(start, code.indexOf("\n$indent}", start))
    }

    /** qa-34-s7-code L1: Emergency call can't be hidden at runtime - every use of the view is one of
     * three known lines, and the one that touches its visibility makes it visible, on every render. */
    @Test
    fun `emergency call is visible in every state - nothing in the code hides it`() {
        val activity = code("java/com/kidslauncher/mdm/ui/LockActivity.kt")
        val uses = Regex("""lockEmergencyButton[^\n]*""").findAll(activity).map { it.value.trim() }.toList()
        assertEquals(
            listOf(
                "lockEmergencyButton.setOnClickListener { EmergencyCall.confirm(this) }",
                "lockEmergencyButton.accessibilityDelegate = ButtonRole",
                "lockEmergencyButton.visibility = View.VISIBLE",
            ),
            uses,
        )
        // Set in render() itself, before anything that could return early after it.
        val render = body(activity, "private fun render()")
        assertTrue("binding.lockEmergencyButton.visibility = View.VISIBLE" in render)
        assertTrue(render.indexOf("lockEmergencyButton.visibility") < render.indexOf("renderRows("))
        // No hiding of the whole screen's column, nor of the binding root, anywhere.
        assertFalse(Regex("""binding\.root\.(visibility|isVisible|isGone|alpha)""").containsMatchIn(activity))
    }

    /** qa-34-s7-code L2: drawn icons change in place; the rows (TalkBack's focus, a press) stay. */
    @Test
    fun `icons arrive in place - the rows are not rebuilt`() {
        val activity = code("java/com/kidslauncher/mdm/ui/LockActivity.kt")
        assertTrue("icons.load(::iconsDrawn)" in body(activity, "private fun renderRows("))
        val drawn = body(activity, "private fun iconsDrawn(")
        assertTrue("rowIcons[pkg]" in drawn && "showAppIcon(icon, icons.cached(pkg))" in drawn)
        for (rebuild in listOf("render(", "renderRows(", "removeAllViews", "inflate", "addView", "snapshot")) {
            assertFalse(rebuild, rebuild in drawn)
        }
        // The map is refilled with the rows it points into, so a stale ImageView is never updated.
        val rows = body(activity, "private fun renderRows(")
        assertTrue(rows.indexOf("rowIcons.clear()") in 0 until rows.indexOf("for (row in rows)"))
        assertTrue("rowIcons[row.packageName] = view.lockRowIcon" in rows)
        // Exactly one place calls render() besides the events: none from icons.
        assertFalse(Regex("""icons\.load\s*\{[^}]*render\(\)""").containsMatchIn(activity))
    }

    /** qa-34-s7-code N4: one loader for the glance page and this screen; the failed set is touched
     * only on the main thread (the background pass returns its failures). */
    @Test
    fun `one shared icon loader, its failures merged on the main thread`() {
        val loader = code("java/com/kidslauncher/mdm/ui/home/AppIconLoader.kt")
        val load = body(loader, "fun load(")
        val background = load.substring(load.indexOf("withContext(Dispatchers.Default)"), load.indexOf("failed += pass.failed"))
        assertFalse("no shared set written off the main thread", Regex("""\b(failed|missing)\s*(\+=|\.add|\.remove|\.clear)""").containsMatchIn(background))
        assertTrue("catch (e: Throwable)" in load)
        for (screen in listOf("java/com/kidslauncher/mdm/ui/LockActivity.kt", "java/com/kidslauncher/mdm/lock/GlancePanel.kt")) {
            val text = code(screen)
            assertTrue(screen, "AppIconLoader(" in text)
            assertFalse(screen, "renderAppIcon(" in text || "failedIcons" in text || "withContext(" in text)
        }
        // The pure pass: drawn and failed apart, a throw is a failure, nothing shared.
        val pass = com.kidslauncher.mdm.ui.home.iconPass(listOf("a", "b", "c")) { pkg ->
            when (pkg) {
                "a" -> true
                "b" -> false
                else -> throw IllegalStateException("broken icon")
            }
        }
        assertEquals(setOf("a"), pass.drawn)
        assertEquals(setOf("b", "c"), pass.failed)
        assertEquals(com.kidslauncher.mdm.ui.home.IconPass(emptySet(), emptySet()), com.kidslauncher.mdm.ui.home.iconPass(emptyList()) { true })
    }
}
