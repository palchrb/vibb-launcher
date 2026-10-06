package com.kidslauncher.mdm.lock

import org.junit.Assert.assertEquals
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
}
