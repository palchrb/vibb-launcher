package com.kidslauncher.mdm.lock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Test

/** The camera stays unreachable while handy's PIN lock is up (emulator run: the power-button
 * gesture showed com.android.camera2 for a second before the lock came back). */
class CameraLockTest {
    private val own = "me.vibb.launcher"
    private val camera = "com.android.camera2"
    private val gcam = "com.google.android.GoogleCamera"

    @Test
    fun `allowed camera apps are the targets - never ours, dialers, keyboards, helpers or Play`() {
        val handlers = setOf(camera, gcam, own, "$own.debug", "com.google.android.dialer", "com.android.vending", "com.oem.headlesscam")
        val controllable = handlers - "com.oem.headlesscam"
        assertEquals(
            setOf(camera, gcam),
            cameraLockTargets(handlers, controllable, own, protected = setOf("com.google.android.dialer")),
        )
        // A headless system camera component (no launcher icon) is never touched (the boot-loop class).
        assertEquals(emptySet<String>(), cameraLockTargets(setOf("com.oem.headlesscam"), emptySet(), own, emptySet()))
        // Design 16d: never the recents provider, even if it answers a camera intent.
        assertEquals(setOf(camera), cameraLockTargets(setOf(camera, "com.oem.launcher"), setOf(camera, "com.oem.launcher"), own, emptySet(), recentsPackage = "com.oem.launcher"))
    }

    @Test
    fun `locked - suspend what isn't suspended, record it first, never claim enforcement's`() {
        val step = cameraLockStep(locked = true, recorded = emptySet(), targets = setOf(camera, gcam), suspendedNow = setOf(gcam), enforcementSuspends = setOf(gcam))
        assertEquals(CameraLockStep.Suspend(setOf(camera), setOf(camera)), step)
        // Already ours and suspended: nothing new.
        assertEquals(CameraLockStep.Nothing, cameraLockStep(true, setOf(camera), setOf(camera), setOf(camera), emptySet()))
        // A camera allowed while locked joins the record.
        assertEquals(CameraLockStep.Suspend(setOf(gcam), setOf(camera, gcam)), cameraLockStep(true, setOf(camera), setOf(camera, gcam), setOf(camera), emptySet()))
    }

    @Test
    fun `unlocked - release only ours, never what enforcement suspends now`() {
        assertEquals(CameraLockStep.Release(setOf(camera)), cameraLockStep(false, setOf(camera, gcam), emptySet(), setOf(camera, gcam), setOf(gcam)))
        assertEquals(CameraLockStep.Nothing, cameraLockStep(false, emptySet(), setOf(camera), emptySet(), emptySet()))
    }

    @Test
    fun `process start without the lock releases a record left by a dead process - all of it before any apply`() {
        assertEquals(CameraLockStep.Release(setOf(camera, gcam)), cameraLockStep(false, setOf(camera, gcam), emptySet(), setOf(camera), enforcementSuspends = null))
    }

    @Test
    fun `the prefs file and keys are pinned, an odd record still reads its set`() {
        assertEquals("camera_lock", CAMERA_LOCK_PREFS)
        assertEquals("v" to "suspended", CameraLockKeys.VERSION to CameraLockKeys.SUSPENDED)
        assertEquals(setOf(camera), decodeCameraLockRecord(mapOf("v" to 7, "suspended" to setOf(camera), "x" to 1)))
        assertEquals(emptySet<String>(), decodeCameraLockRecord(mapOf("suspended" to "not a set")))
    }

    @Test
    fun `of third-party cameras only the one the gesture opens is a handler`() {
        val messenger = "org.example.snapchat"
        // System handlers all count; a third-party one only as the gesture's default.
        assertEquals(setOf(camera), cameraHandlers(setOf(camera), listOf(null, null)))
        assertEquals(setOf(camera, messenger), cameraHandlers(setOf(camera), listOf(messenger, camera)))
        // The chooser (several cameras, no default) is not an app to suspend.
        assertEquals(setOf(camera), cameraHandlers(setOf(camera), listOf("android", "")))
    }

    @Test
    fun `a held camera stays in the app lists - unless enforcement suspends it too`() {
        assertFalse(suspendedForLists(camera, platformSuspended = true, cameraHeld = setOf(camera), enforcementSuspends = emptySet()))
        assertFalse(suspendedForLists(camera, true, setOf(camera), null))
        assertTrue(suspendedForLists(camera, true, setOf(camera), setOf(camera)))
        assertTrue(suspendedForLists(gcam, true, setOf(camera), emptySet()))
        assertFalse(suspendedForLists(gcam, false, emptySet(), setOf(gcam)))
    }

    @Test
    fun `callbacks only about the camera lock's packages don't reload the app list`() {
        assertTrue(cameraLockOnlyChange(listOf(camera), setOf(camera, gcam)))
        assertFalse(cameraLockOnlyChange(listOf(camera, "org.example.game"), setOf(camera)))
        assertFalse(cameraLockOnlyChange(emptyList(), setOf(camera)))
        assertFalse(cameraLockOnlyChange(null, setOf(camera)))
    }

    @Test
    fun `after a release apply looks again when there is no plan or one is still suspended`() {
        assertTrue(applyAfterRelease(emptySet(), enforcementSuspends = null))
        assertFalse(applyAfterRelease(emptySet(), emptySet()))
        // Refused or suspended again by a stale apply() in between: apply settles it.
        assertTrue(applyAfterRelease(setOf(camera), emptySet()))
        // Enforcement wants it suspended anyway: nothing to settle.
        assertFalse(applyAfterRelease(setOf(camera), setOf(camera)))
    }
}
