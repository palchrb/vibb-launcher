package com.kidslauncher.mdm.server

import com.kidslauncher.mdm.apps.AppFilter
import com.kidslauncher.mdm.server.dto.InstalledApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Round 7 §2 (docs/design/r7-launcher-small-items.md): the catalog apps' battery state in the
 * report - the set ([catalogAppsInstalledByUs]), `battery_exempt` for it only - and that nothing sets
 * `setUserControlDisabledPackages` (qa-l7-b-code finding 1: a protected package can't be suspended,
 * hidden or uninstalled by the device owner either).
 */
class CatalogBatteryTest {
    private val own = AppFilter.OWN_PACKAGES
    private val clock = "org.fossify.clock"
    private val element = "io.element.android.x"
    private val game = "org.example.game"

    private val pins = setOf(clock, element, game, "me.vibb.launcher", "org.example.gone")
    private val installed = setOf(clock, element, game, "me.vibb.launcher", "org.example.unpinned")
    private val installers = mapOf(
        clock to "me.vibb.launcher",
        element to "me.vibb.launcher.debug",
        // A Play-installed twin of a catalog app: not ours.
        game to "com.android.vending",
        "me.vibb.launcher" to "me.vibb.launcher",
        "org.example.unpinned" to "me.vibb.launcher",
    )

    @Test
    fun `the set is the pinned catalog apps installed here by us, never ours`() {
        assertEquals(setOf(clock, element), catalogAppsInstalledByUs(pins, installed, installers, own))
        // Unchecked in the catalog: the pins no longer name it.
        assertEquals(setOf(element), catalogAppsInstalledByUs(pins - clock, installed, installers, own))
        // Not installed for this user, or no installer known: out.
        assertEquals(emptySet<String>(), catalogAppsInstalledByUs(setOf("org.example.gone"), installed, installers, own))
        assertEquals(emptySet<String>(), catalogAppsInstalledByUs(setOf(clock), setOf(clock), emptyMap(), own))
    }

    @Test
    fun `battery_exempt for the catalog apps only, left out for the rest and when unreadable`() {
        val apps = listOf(
            InstalledApp(clock, "Clock", preinstalled = false, installer = "me.vibb.launcher"),
            InstalledApp(element, "Element X", preinstalled = false, installer = "me.vibb.launcher"),
            InstalledApp(game, "Game", preinstalled = false, installer = "com.android.vending"),
        )
        val exempt = mapOf(clock to false, element to null, game to true)
        val asked = mutableListOf<String>()
        val out = withBatteryExempt(apps, setOf(clock, element)) { asked += it; exempt[it] }
        assertEquals(listOf(false, null, null), out.map { it.batteryExempt })
        assertEquals("only the catalog apps are read", listOf(clock, element), asked)
        assertEquals(apps.map { it.copy(batteryExempt = null) }, out.map { it.copy(batteryExempt = null) })
    }

    private val main = listOf(File("src/main"), File("app/src/main")).first { it.exists() }

    private fun code(file: File) = file.readText()
        .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
        .replace(Regex("//[^\\n]*"), "")

    @Test
    fun `nothing sets setUserControlDisabledPackages, and the report computes the set`() {
        val callers = main.walk().filter { it.isFile && it.name.endsWith(".kt") && "setUserControlDisabledPackages" in code(it) }
            .map { it.name }.toList()
        assertEquals(emptyList<String>(), callers)
        val sync = code(File(main, "java/com/kidslauncher/mdm/server/MdmSyncWorker.kt"))
        val collect = sync.substringAfter("private fun collectInstalledApps(").substringBefore("\n}\n")
        assertTrue(collect.contains("val pins = WindowRelock.store(context).catalogPins().keys"))
        assertTrue(collect.contains("phonePackage(pm, it) is PhonePackage.Installed"))
        assertTrue(collect.contains("withBatteryExempt(apps, catalogApps)"))
    }
}
