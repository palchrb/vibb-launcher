package com.kidslauncher.mdm.setup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Design 114 §6.2: the flow's screens and buttons, and §6.1's hold - the phone's alone (pure, plus source checks of
 * the hold's order). */
class SetupFlowTest {
    private val everything = SetupInputs(64, "24", callsManaged = true, androidLock = true, mapLinksOn = false, mapAppInstalled = false)

    /** A fresh phone in the hold: nothing done, no Parent PIN reading either. */
    private val fresh = mapOf(
        "parent_pin" to false, "device_owner" to true, "location" to false, "notification_access" to false,
        "mobile_data" to false, "data_saver" to false, "background" to false, "vibb_notifications" to false,
        "full_screen_intent" to false, "write_settings" to false, "assistant" to false, "call_filter" to false,
        "default_apps" to false, "fingerprint" to false,
    )

    private fun keys(screens: List<FlowScreen>) = screens.map {
        when (it) {
            is FlowScreen.Step -> it.step.key
            is FlowScreen.End -> "end"
            FlowScreen.Wait -> "wait"
            FlowScreen.NotOpen -> "not_open"
        }
    }

    @Test
    fun `a fresh phone in the hold - table order, no Parent PIN or Device Owner screen`() {
        val missing = setupMissing(everything, fresh, emptySet())
        assertTrue(SetupStep.PARENT_PIN in missing)
        val screens = flowScreens(missing, emptySet(), windowOpen = true, policyKnown = true)
        assertEquals(
            listOf(
                "location", "notification_access", "mobile_data", "data_saver", "background", "vibb_notifications",
                "full_screen_intent", "write_settings", "assistant", "call_filter", "default_apps", "fingerprint",
                "vibration", "touch_feedback", "lock_clock", "media_on_lock", "message_banners", "end",
            ),
            keys(screens),
        )
        val end = screens.last() as FlowScreen.End
        assertEquals(11, end.left)
    }

    @Test
    fun `skips wait for the end, a step done meanwhile leaves the run`() {
        val missing = setupMissing(everything, fresh, emptySet())
        val skipped = flowScreens(missing, setOf("location", "fingerprint"), windowOpen = true, policyKnown = true)
        assertEquals("notification_access", keys(skipped).first())
        assertFalse("fingerprint" in keys(skipped))
        // The skipped required step is still counted and listed at the end; the optional one isn't.
        val end = skipped.last() as FlowScreen.End
        assertTrue(SetupStep.LOCATION in end.leftSteps && SetupStep.FINGERPRINT !in end.leftSteps)
        val done = setupMissing(everything, fresh + ("location" to true), emptySet())
        assertEquals("notification_access", keys(flowScreens(done, emptySet(), true, true)).first())
        // A tick on the phone (pending) leaves the run too.
        val ticked = setupMissing(everything, fresh, setOf("vibration"))
        assertFalse("vibration" in keys(flowScreens(ticked, emptySet(), true, true)))
    }

    @Test
    fun `outside a window only Not open`() {
        val missing = setupMissing(everything, fresh, emptySet())
        assertEquals(listOf("not_open"), keys(flowScreens(missing, emptySet(), windowOpen = false, policyKnown = true)))
    }

    @Test
    fun `before the first policy the always steps run, Wait only with nothing runnable (QA M3)`() {
        val missing = setupMissing(SetupInputs.NONE, fresh, emptySet())
        val screens = keys(flowScreens(missing, emptySet(), windowOpen = true, policyKnown = false))
        assertEquals("location", screens.first())
        assertTrue("default_apps" in screens && "vibration" in screens && "message_banners" in screens)
        assertFalse("assistant" in screens || "lock_clock" in screens || "fingerprint" in screens)
        val allSkipped = missing.map { it.key }.toSet()
        assertEquals(listOf("wait"), keys(flowScreens(missing, allSkipped, windowOpen = true, policyKnown = false)))
        assertEquals(listOf("wait"), keys(flowScreens(emptyList(), emptySet(), windowOpen = true, policyKnown = false)))
        assertTrue(FLOW_WAIT_BUTTONS_MS == 30_000L)
    }

    @Test
    fun `the end - Klar at none left, else the left required steps`() {
        val ready = flowScreens(emptyList(), emptySet(), windowOpen = true, policyKnown = true)
        assertEquals(listOf(FlowScreen.End(0, emptyList())), ready)
        val oneLeft = flowScreens(listOf(SetupStep.BACKGROUND, SetupStep.VIBRATION), setOf("background", "vibration"), true, true)
        assertEquals(listOf(FlowScreen.End(1, listOf(SetupStep.BACKGROUND))), oneLeft)
    }

    @Test
    fun `the buttons per need and reader`() {
        assertEquals(listOf(FlowButton.OPEN, FlowButton.LATER), flowButtons(SetupStep.BACKGROUND, pageMissing = false))
        assertEquals(listOf(FlowButton.OPEN, FlowButton.SKIP), flowButtons(SetupStep.FINGERPRINT, pageMissing = false))
        assertEquals(listOf(FlowButton.OPEN, FlowButton.DID_IT, FlowButton.SKIP), flowButtons(SetupStep.VIBRATION, pageMissing = false))
        assertEquals(listOf(FlowButton.LATER), flowButtons(SetupStep.DATA_SAVER, pageMissing = true))
        assertEquals(listOf(FlowButton.SKIP), flowButtons(SetupStep.LOCK_CLOCK, pageMissing = true))
    }

    @Test
    fun `the progress counts the run's screens`() {
        // Code QA M1: the steps ahead count - the first screen of seven reads "1 av 7".
        val seven = listOf(
            SetupStep.LOCATION, SetupStep.NOTIFICATION_ACCESS, SetupStep.MOBILE_DATA, SetupStep.DATA_SAVER,
            SetupStep.BACKGROUND, SetupStep.ASSISTANT, SetupStep.CALL_FILTER,
        )
        assertEquals(1 to 7, flowProgress(setOf("location"), seven))
        // Two done (shown, gone from the run), five ahead: "3 av 7".
        assertEquals(3 to 7, flowProgress(setOf("location", "notification_access", "mobile_data"), seven.drop(2)))
        assertEquals(7 to 7, flowProgress(seven.map { it.key }.toSet(), seven.takeLast(1)))
    }

    @Test
    fun `the hold starts only on a fresh device-owner phone`() {
        assertTrue(setupHoldStarts(deviceOwner = true, policyEverApplied = false, cachedPolicy = false))
        assertFalse(setupHoldStarts(deviceOwner = false, policyEverApplied = false, cachedPolicy = false))
        assertFalse(setupHoldStarts(deviceOwner = true, policyEverApplied = true, cachedPolicy = false))
        assertFalse(setupHoldStarts(deviceOwner = true, policyEverApplied = false, cachedPolicy = true))
        assertFalse(setupHoldStarts(deviceOwner = false, policyEverApplied = true, cachedPolicy = true))
    }

    private val main = listOf("src/main", "app/src/main").map(::File).first { it.isDirectory }

    private fun code(path: String) = File(main, "java/com/kidslauncher/mdm/$path").readText()

    /** QA L10: "fresh" is decided before the token is stored; the hold starts in the success path. */
    @Test
    fun `the enrolment decides before the token and starts the hold after it`() {
        val enroll = code("server/Enrollment.kt").substringAfter("suspend fun enroll(")
        val decide = enroll.indexOf("setupHoldStarts(")
        val token = enroll.indexOf("DeviceToken.set(")
        val hold = enroll.indexOf("RestrictionsPause.startHold(context)")
        assertTrue(decide in 0 until token)
        assertTrue(hold > token)
    }

    /** §6.2.1: Home leads to the flow after the lock checks, in place of its content; the flow is its own task. */
    @Test
    fun `Home leads to the flow in the hold, the flow in its own task`() {
        val home = code("ui/HomeActivity.kt").substringAfter("override fun onResume()").substringBefore("override fun onPause()")
        assertTrue(home.indexOf("redirectToLockScreenIfLocked()") < home.indexOf("SetupFlowActivity.startFromHome(this)"))
        assertTrue(home.indexOf("SetupFlowActivity.startFromHome(this)") < home.lastIndexOf("resumeContent()"))
        val manifest = File(main, "AndroidManifest.xml").readText().substringAfter(".setup.SetupFlowActivity\"").substringBefore("/>")
        assertTrue("android:taskAffinity=\"\${applicationId}.setup\"" in manifest)
        assertTrue("android:launchMode=\"singleTask\"" in manifest && "android:exported=\"false\"" in manifest)
        // Back in the hold through the dispatcher (predictive back on 16 never calls onBackPressed).
        assertTrue("OnBackPressedCallback" in code("setup/SetupFlowActivity.kt"))
    }

    /** The flow's pages (the moved Settings rows' glue): the window and the applied plan decide the access, the next page
     * on ActivityNotFoundException, a return read again and reported at once. */
    @Test
    fun `the flow opens Android's pages as the Settings rows did`() {
        val flow = code("setup/SetupFlowActivity.kt")
        assertTrue("overrideApplied = AppEnforcer.lastEnforcedOverride == true," in flow)
        val launch = flow.substringAfter("private fun launchPages(").substringBefore("private fun pageReturned(")
        assertTrue("for (page in pages)" in launch && "catch (e: ActivityNotFoundException)" in launch)
        assertTrue("SetupRun.markPageMissing(step.key)" in launch)
        assertTrue("SyncRunner.request(app, SETUP_SYNC_REASON)" in flow.substringAfter("private fun pageReturned("))
        // The window ends after its page: the assistant, the call filter's queue, the default apps, the Wi-Fi step.
        val back = flow.substringAfter("private fun pageReturned(").substringBefore("private fun endWifiStepNow(")
        for (end in listOf("endPickWindow(app, \"page\")", "endPickWindow(app, \"call_filter\")", "endWifiStepNow(\"result\")")) assertTrue(end, end in back)
    }

    @Test
    fun `Home brings the flow back after its own pause, once and only soon after (gate 2026-11-19, row 13)`() {
        assertTrue(flowReturnDue(1_000, 2_300, windowOpen = true))
        assertTrue(flowReturnDue(1_000, 1_000 + FLOW_RETURN_MS, windowOpen = true))
        assertFalse(flowReturnDue(1_000, 1_001 + FLOW_RETURN_MS, windowOpen = true))
        assertFalse(flowReturnDue(1_000, 2_300, windowOpen = false))
        assertFalse(flowReturnDue(null, 2_300, windowOpen = true))
        assertFalse(flowReturnDue(5_000, 2_300, windowOpen = true))
        SetupRun.markPausedFromFlow(1_000)
        assertTrue(SetupRun.takeReturnAfterPause(2_300, windowOpen = true))
        assertFalse(SetupRun.takeReturnAfterPause(2_400, windowOpen = true))
        // The parent closed the flow ("Ferdig", Back): nothing comes back.
        SetupRun.markPausedFromFlow(1_000)
        SetupRun.clearReturnAfterPause()
        assertFalse(SetupRun.takeReturnAfterPause(2_300, windowOpen = true))
        // Both ways back: Home's resume (Android 14) and the destroyed flow itself (Android 16, Settings left in front).
        val flow = code("setup/SetupFlowActivity.kt")
        assertTrue("SetupRun.takeReturnAfterPause(" in flow.substringAfter("override fun onDestroy()"))
        assertTrue("SetupRun.takeReturnAfterPause(" in code("ui/HomeActivity.kt"))
        assertTrue("SetupRun.clearReturnAfterPause()" in flow.substringAfter("private fun finishFlow()"))
    }

    @Test
    fun `the flow reads its step again when it gets its focus back (Android 16's Internet panel, gate 2026-11-19)`() {
        val flow = code("setup/SetupFlowActivity.kt").substringAfter("override fun onWindowFocusChanged(").substringBefore("override fun ")
        assertTrue("refresh()" in flow)
    }

    @Test
    fun `the borderless buttons are drawn in the text colour, never the ground's (gate 2026-11-19)`() {
        val layout = File(main, "res/layout/activity_setup_flow.xml").readText()
        for (id in listOf("setup_secondary", "setup_tertiary")) {
            val button = layout.substringAfter("@+id/$id\"").substringBefore("/>")
            assertTrue(id, "android:textColor=\"?android:textColor\"" in button)
        }
    }

    @Test
    fun `mobile data opens the Internet panel first from Android 16, the SIM page before (gate 2026-11-19)`() {
        val actions = SetupStep.MOBILE_DATA.pageActions!!
        val sim = "android.settings.NETWORK_OPERATOR_SETTINGS"
        val panel = "android.settings.panel.action.INTERNET_CONNECTIVITY"
        assertEquals(listOf(sim, panel), actions)
        assertEquals(listOf(sim, panel), mobileDataActions(actions, 34))
        assertEquals(listOf(sim, panel), mobileDataActions(actions, 35))
        assertEquals(listOf(panel, sim), mobileDataActions(actions, 36))
        assertEquals(listOf(panel, sim), mobileDataActions(actions, 37))
    }

    /** Design 110's finding 3: from Android 16 the map steps name "Open by default"'s two choices, not 14's switch. */
    @Test
    fun `the map steps' line follows Android 16's Open by default page`() {
        for (sdk in listOf(34, 35)) {
            assertEquals(com.kidslauncher.mdm.R.string.setup_how_map_play_links, setupHowRes(SetupStep.MAP_PLAY_LINKS, sdk))
            assertEquals(com.kidslauncher.mdm.R.string.setup_how_map_vibb_links, setupHowRes(SetupStep.MAP_VIBB_LINKS, sdk))
        }
        for (sdk in listOf(36, 37)) {
            assertEquals(com.kidslauncher.mdm.R.string.setup_how_map_play_links_16, setupHowRes(SetupStep.MAP_PLAY_LINKS, sdk))
            assertEquals(com.kidslauncher.mdm.R.string.setup_how_map_vibb_links_16, setupHowRes(SetupStep.MAP_VIBB_LINKS, sdk))
        }
        assertEquals(SetupStep.LOCATION.howRes, setupHowRes(SetupStep.LOCATION, 36))
    }

    /** Code QA L1-L3 (map links x 114): step 2's holders are never left suspended without its page, nor given back
     * under a second one. */
    @Test
    fun `the map step opens one page and gives the holders back whenever none is open`() {
        val flow = code("setup/SetupFlowActivity.kt")
        val open = flow.substringAfter("SetupStep.MAP_VIBB_LINKS -> if (").substringBefore("else -> launchPages(")
        assertTrue(open.startsWith("!mapStepOpening && openedStep == null)"))
        assertTrue(open.substringAfter("} finally {").contains("if (openedStep == null) withContext(NonCancellable + Dispatchers.IO) { MapLinkStep.end(app) }"))
        val launch = flow.substringAfter("private fun launchPages(").substringBefore("private fun pageReturned(")
        assertTrue("openedStep = null" in launch.substringAfter("catch (e: Exception)").substringBefore("return"))
    }
}
