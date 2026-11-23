package com.kidslauncher.mdm.server

import com.kidslauncher.mdm.server.dto.PolicyResponse
import com.kidslauncher.mdm.server.dto.StatusReportRequest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Android Auto hidden unless allowed, the other Home apps suspended (never the recents provider), the report of the
 * apps with notification access - and the shared contract `notification_listeners.json` (byte-identical with
 * `server/testdata/`, whose `notification_listeners` tests read the same rows). */
class SystemReadersTest {
    private val contractText = javaClass.classLoader!!.getResource("notification_listeners.json")!!.readText()
    private val contract = ServerJson.parseToJsonElement(contractText).jsonObject

    private val own = "me.vibb.launcher"
    private val recents = "com.vendor.quickstep"
    private val controllable = listOf(own, "org.example.game", "com.vendor.home.app")
    private val homes = listOf(
        HomeCandidate(own, system = false, persistent = false, priority = 0),
        HomeCandidate("com.android.launcher3", system = true, persistent = false, priority = 0),
        HomeCandidate(recents, system = true, persistent = false, priority = 0),
        HomeCandidate("com.android.settings", system = true, persistent = false, priority = -1000),
        HomeCandidate("com.vendor.fallback", system = true, persistent = false, priority = -1),
        HomeCandidate("com.vendor.persistent", system = true, persistent = true, priority = 0),
        HomeCandidate("com.vendor.home.app", system = true, persistent = false, priority = 0),
    )

    private fun plan(
        allowlist: List<String>? = listOf("org.example.game"),
        override: Boolean = false,
        auto: Boolean = true,
        allowAuto: Boolean = false,
    ) = computeEnforcementPlan(
        allowlist, true, 0, override, controllable, own, null,
        recentsPackage = recents, homeCandidates = homes, androidAutoInstalled = auto, allowAndroidAuto = allowAuto,
    )

    @Test
    fun `Android Auto is hidden while apps are managed, unless the parent allows it`() {
        assertEquals(ANDROID_AUTO, contract.getValue("android_auto").jsonPrimitive.content)
        val managed = plan()
        assertTrue(ANDROID_AUTO in managed.hide && ANDROID_AUTO in managed.suspend)
        assertTrue(ANDROID_AUTO in managed.extraPackages)
        for (open in listOf(plan(allowAuto = true), plan(override = true), plan(allowlist = null))) {
            assertFalse(ANDROID_AUTO in open.hide || ANDROID_AUTO in open.suspend)
            // Still visited, so a hide from before is undone.
            assertTrue(ANDROID_AUTO in open.extraPackages)
        }
        assertFalse(ANDROID_AUTO in plan(auto = false).extraPackages)
    }

    @Test
    fun `the other Homes are suspended, never hidden, and the recents provider and fallbacks stay`() {
        val managed = plan()
        assertTrue("com.android.launcher3" in managed.suspend)
        assertFalse("com.android.launcher3" in managed.hide)
        for (kept in listOf(own, recents, "com.android.settings", "com.vendor.fallback", "com.vendor.persistent")) {
            assertFalse(kept, kept in managed.suspend || kept in managed.extraPackages)
        }
        // A Home with a launcher icon is the allowlist's, not an extra.
        assertFalse("com.vendor.home.app" in managed.extraPackages)
        assertTrue("com.vendor.home.app" in managed.hide)
        assertEquals(setOf("com.android.launcher3", ANDROID_AUTO), managed.extraPackages)
        // The pause lifts it; the package is still visited, so the suspension is undone.
        val paused = plan(override = true)
        assertFalse("com.android.launcher3" in paused.suspend)
        assertTrue("com.android.launcher3" in paused.extraPackages)
        // One excluded HOME activity excludes the package.
        val twice = homes + HomeCandidate("com.android.launcher3", system = true, persistent = false, priority = -5)
        assertEquals(emptySet<String>(), otherHomes(twice, own, controllable.toSet(), setOf(recents)))
    }

    @Test
    fun `the policy key decodes and a missing one hides`() {
        val on = ServerJson.decodeFromString(PolicyResponse.serializer(), contract.getValue("policy").jsonObject.getValue("on").toString())
        val off = ServerJson.decodeFromString(PolicyResponse.serializer(), contract.getValue("policy").jsonObject.getValue("off").toString())
        assertTrue(on.allowAndroidAuto)
        assertFalse(off.allowAndroidAuto)
        assertFalse(ServerJson.decodeFromString(PolicyResponse.serializer(), "{}").allowAndroidAuto)
        assertTrue(LastEnforcedPlan.of(on).toPolicy().allowAndroidAuto)
    }

    @Test
    fun `the read rows`() {
        for (row in contract.getValue("read").jsonArray.map { it.jsonObject }) {
            val enabled = row.getValue("enabled").jsonArray.map { it.jsonPrimitive.content }
            val off = row.getValue("off").jsonArray.map { it.jsonPrimitive.content }.toSet()
            val report = notificationListenersReport(enabled, row.getValue("own").jsonPrimitive.content) { it in off }
            assertEquals(
                row.getValue("case").jsonPrimitive.content,
                row.getValue("report"),
                ServerJson.encodeToJsonElement(ListSerializer(NotificationListenerReport.serializer()), report),
            )
        }
    }

    @Test
    fun `the report encodes exactly as the contract's`() {
        for (name in listOf("report", "report_none")) {
            val rows = ServerJson.decodeFromJsonElement(ListSerializer(NotificationListenerReport.serializer()), contract.getValue(name))
            val body = ServerJson.encodeToJsonElement(
                StatusReportRequest.serializer(),
                StatusReportRequest(lockReason = "none", kioskEngaged = true, notificationListeners = rows),
            ).jsonObject
            assertEquals(name, contract.getValue(name), body.getValue("notification_listeners"))
        }
    }

    @Test
    fun `the two copies of the contract are identical`() {
        val serverCopy = listOf("../../server/testdata/notification_listeners.json", "../server/testdata/notification_listeners.json")
            .map(::File).first { it.exists() }
        assertEquals(serverCopy.readText(), contractText)
    }
}
