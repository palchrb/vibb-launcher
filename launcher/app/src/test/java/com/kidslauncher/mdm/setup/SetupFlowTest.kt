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
        assertEquals(1 to 3, flowProgress(setOf("a"), listOf(SetupStep.LOCATION)).let { it.first to 3 })
        val run = setOf("location", "background", "assistant")
        assertEquals(2 to 3, flowProgress(run, listOf(SetupStep.BACKGROUND, SetupStep.ASSISTANT)))
        assertEquals(3 to 3, flowProgress(run, listOf(SetupStep.ASSISTANT)))
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
}
