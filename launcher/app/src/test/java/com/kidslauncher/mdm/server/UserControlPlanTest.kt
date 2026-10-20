package com.kidslauncher.mdm.server

import com.kidslauncher.mdm.apps.AppFilter
import com.kidslauncher.mdm.server.dto.InstalledApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Round 7 §2 (docs/design/r7-launcher-small-items.md §2.2): the catalog apps protected from
 * force-stop - the set, its lift by the override and the pause, the clear when unmanaged, where
 * `apply()` sets it - and the status report's `battery_exempt` for that set only.
 */
class UserControlPlanTest {
    private val own = AppFilter.OWN_PACKAGES
    private val clock = "org.fossify.clock"
    private val element = "io.element.android.x"
    private val game = "org.example.game"

    private val pins = setOf(clock, element, game, "me.vibb.launcher", "org.example.gone", "org.example.hidden")
    private val installed = setOf(clock, element, game, "me.vibb.launcher", "org.example.hidden", "org.example.unpinned")
    private val installers = mapOf(
        clock to "me.vibb.launcher",
        element to "me.vibb.launcher.debug",
        // A Play-installed twin of a catalog app: Play's to update, never ours to protect.
        game to "com.android.vending",
        "me.vibb.launcher" to "me.vibb.launcher",
        "org.example.hidden" to "me.vibb.launcher",
        "org.example.unpinned" to "me.vibb.launcher",
    )

    private fun protectedNow(hidden: Set<String> = setOf("org.example.hidden")) =
        protectedCatalogPackages(pins, installed, installers, hidden, own)

    @Test
    fun `the set is the pinned catalog apps installed here by us, never ours, never hidden`() {
        assertEquals(setOf(clock, element), protectedNow())
        // Unchecked in the catalog: the pins no longer name it.
        assertEquals(setOf(element), protectedCatalogPackages(pins - clock, installed, installers, emptySet(), own) - "org.example.hidden")
        // Not installed for this user, or no installer known: out.
        assertEquals(emptySet<String>(), protectedCatalogPackages(setOf("org.example.gone"), installed, installers, emptySet(), own))
        assertEquals(emptySet<String>(), protectedCatalogPackages(setOf(clock), setOf(clock), emptyMap(), emptySet(), own))
        // A hidden app is never protected; once shown again it is.
        assertTrue("org.example.hidden" in protectedNow(hidden = emptySet()))
    }

    @Test
    fun `lifted by the override and the pause, cleared when unmanaged`() {
        val set = protectedNow()
        assertEquals(set, userControlDisabledPackages(managed = true, overrideActive = false, protected = set))
        assertEquals(emptySet<String>(), userControlDisabledPackages(managed = true, overrideActive = true, protected = set))
        assertEquals(emptySet<String>(), userControlDisabledPackages(managed = false, overrideActive = false, protected = set))
        assertEquals(emptySet<String>(), userControlDisabledPackages(managed = false, overrideActive = true, protected = set))
    }

    @Test
    fun `battery_exempt for the protected set only, left out for the rest and when unreadable`() {
        val apps = listOf(
            InstalledApp(clock, "Clock", preinstalled = false, installer = "me.vibb.launcher"),
            InstalledApp(element, "Element X", preinstalled = false, installer = "me.vibb.launcher"),
            InstalledApp(game, "Game", preinstalled = false, installer = "com.android.vending"),
        )
        val exempt = mapOf(clock to false, element to null, game to true)
        val asked = mutableListOf<String>()
        val out = withBatteryExempt(apps, setOf(clock, element)) { asked += it; exempt[it] }
        assertEquals(listOf(false, null, null), out.map { it.batteryExempt })
        assertEquals("only the protected apps are read", listOf(clock, element), asked)
        assertEquals(apps.map { it.copy(batteryExempt = null) }, out.map { it.copy(batteryExempt = null) })
    }

    private val main = listOf(File("src/main"), File("app/src/main")).first { it.exists() }
    private val root = File(main, "java/com/kidslauncher/mdm")

    private fun code(file: File) = file.readText()
        .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("//[^\\n]*"), "")

    @Test
    fun `setUserControlDisabledPackages is called in apply only, after the hardening, in its own catch`() {
        val callers = root.walk().filter { it.isFile && it.name.endsWith(".kt") && "setUserControlDisabledPackages" in code(it) }
            .map { it.name }.toList()
        assertEquals(listOf("AppEnforcer.kt"), callers)
        val enforcer = code(File(root, "server/AppEnforcer.kt"))
        val start = enforcer.indexOf("fun apply(context: Context, policy: PolicyResponse?")
        val end = enforcer.indexOf("private fun essentialNotificationPackages(")
        assertTrue(start in 0 until end)
        val apply = enforcer.substring(start, end)
        assertEquals(1, Regex("""\.setUserControlDisabledPackages\(""").findAll(enforcer).count())
        val call = apply.indexOf("dpm.setUserControlDisabledPackages(admin, wanted.toList())")
        val hardening = apply.indexOf("applyHardening(context, dpm, admin, hardening, policy?.locationPolicy)")
        assertTrue("after the hardening", hardening in 0 until call)
        // Its own try: the nearest `try {` before the call comes after the hardening, and its catch
        // closes after the call with nothing else in between.
        val tryAt = apply.lastIndexOf("try {", call)
        assertTrue(tryAt > hardening)
        val block = apply.substring(tryAt, apply.indexOf("} catch (e: Exception) {", call))
        assertTrue(block.contains("userControlDisabledPackages(managedForHardening, overrideActive, protected)"))
        assertTrue(block.contains("protectedCatalogPackages(pins, installed,"))
        assertTrue(block.contains("phonePackage(pm, it) is PhonePackage.Installed"))
        assertTrue(block.contains("WindowRelock.store(context).catalogPins()"))
    }
}
