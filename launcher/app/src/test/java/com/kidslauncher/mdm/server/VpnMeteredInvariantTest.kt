package com.kidslauncher.mdm.server

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Design 13 §1: our VPN must not make the phone metered. A Q+ VPN is metered by default, so on
 * home Wi-Fi every "unmetered only" job - ours, Play's - would wait forever. `KidVpnService`'s
 * builder calls `setMetered(false)` (inherit from the network below), and nothing ever calls
 * `setUnderlyingNetworks` (with none set, the system default network is the underlying one, so
 * cellular stays metered). Scans every non-test source set, like `BackupServiceInvariantsTest`.
 */
class VpnMeteredInvariantTest {
    private val src = listOf("src", "app/src").map(::File).first { File(it, "main/java").isDirectory }

    private val productionSources: List<File> = src.listFiles()!!
        .filter { it.isDirectory && !it.name.startsWith("test") && !it.name.startsWith("androidTest") }
        .flatMap { set -> set.walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "java") }.toList() }

    @Test
    fun `the VPN builder is not metered`() {
        val vpn = productionSources.first { it.name == "KidVpnService.kt" }.readText()
        val builder = vpn.substringAfter("val builder = Builder()").substringBefore(".establish()")
        assertTrue("KidVpnService's builder must call .setMetered(false)", ".setMetered(false)" in builder)
    }

    @Test
    fun `nothing sets the metered flag otherwise or the underlying networks`() {
        val setter = "setMetered"
        val offenders = mutableListOf<String>()
        var calls = 0
        for (file in productionSources) {
            val text = file.readText()
            var at = text.indexOf(setter)
            while (at >= 0) {
                val line = text.substring(0, at).count { it == '\n' } + 1
                val rest = text.substring(at + setter.length).trimStart()
                if (!rest.startsWith("(")) {
                    offenders += "${file.path}:$line (not a plain call)"
                } else {
                    val argument = rest.substring(1).substringBefore(')').trim()
                    if (argument != "false") offenders += "${file.path}:$line (argument `$argument`)"
                    calls++
                }
                at = text.indexOf(setter, at + setter.length)
            }
            if ("setUnderlyingNetworks" in text) offenders += "${file.path}: setUnderlyingNetworks"
        }
        assertEquals(emptyList<String>(), offenders)
        assertTrue("The scan must find KidVpnService's call", calls >= 1)
    }
}
