package com.kidslauncher.mdm.torch

import com.kidslauncher.mdm.lock.VoipPhase
import com.kidslauncher.mdm.music.CallEdge
import com.kidslauncher.mdm.music.callEdge
import com.kidslauncher.mdm.music.musicCallActive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Design 41 §1.4/§1.5/§2: the flashlight's rules, and the glue's few promises by source. */
class TorchRulesTest {
    private val main = listOf(File("src/main"), File("app/src/main")).first { it.exists() }
    private val torch = File(main, "java/com/kidslauncher/mdm/torch/Torch.kt").readText()

    @Test
    fun `every on arms the 10-minute alarm, only the platform's dark cancels it, a refused off re-arms it`() {
        assertEquals(10 * 60_000L, TORCH_AUTO_OFF_MS)
        assertEquals(TorchStep(switchOff = false, alarm = TorchAlarm.ARM), torchStep(lit = true, TorchEvent.LIT))
        assertEquals(TorchStep(switchOff = false, alarm = TorchAlarm.CANCEL), torchStep(lit = false, TorchEvent.DARK))
        // A switch-off keeps the alarm until the platform says dark: a refused one must not drop it.
        assertEquals(TorchStep(switchOff = true, alarm = TorchAlarm.KEEP), torchStep(lit = true, TorchEvent.AUTO_OFF))
        assertEquals(TorchStep(switchOff = false, alarm = TorchAlarm.KEEP), torchStep(lit = false, TorchEvent.AUTO_OFF))
        assertEquals(TorchAlarm.ARM, torchAlarmAfterOff(switched = false))
        assertEquals(TorchAlarm.KEEP, torchAlarmAfterOff(switched = true))
        // The glue acts on both: the re-arm after a failed off, the cancel on DARK.
        assertTrue("if (torchAlarmAfterOff(switched) == TorchAlarm.ARM) armAutoOff(app)" in torch)
        assertTrue("TorchAlarm.CANCEL -> cancelAutoOff(app)" in torch)
        // The alarm is set from now, exact and while idle (Doze in a pocket), and only to our receiver.
        assertTrue("SystemClock.elapsedRealtime() + TORCH_AUTO_OFF_MS" in torch)
        assertTrue("setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP" in torch)
        assertTrue("Intent(app, TorchOffReceiver::class.java)" in torch)
        val manifest = File(main, "AndroidManifest.xml").readText()
        assertTrue(Regex("""android:name="\.torch\.TorchOffReceiver"\s+android:exported="false"""").containsMatchIn(manifest))
    }

    @Test
    fun `a call beginning from any of the three sources turns it off, its end never lights it`() {
        // VoIP (a ring included), our dialer's call, a system call: each is a BEGAN edge.
        for (begins in listOf(
            musicCallActive(VoipPhase.RINGING, ourCall = false, systemCall = false),
            musicCallActive(VoipPhase.NONE, ourCall = true, systemCall = false),
            musicCallActive(VoipPhase.NONE, ourCall = false, systemCall = true),
        )) {
            assertEquals(CallEdge.BEGAN, callEdge(wasInCall = false, inCall = begins))
        }
        assertEquals(CallEdge.NONE, callEdge(wasInCall = true, inCall = true))
        assertEquals(TorchStep(switchOff = true, alarm = TorchAlarm.KEEP), torchStep(lit = true, TorchEvent.CALL_BEGAN))
        assertEquals(CallEdge.ENDED, callEdge(wasInCall = true, inCall = false))
        assertEquals(TorchStep(switchOff = false, alarm = TorchAlarm.KEEP), torchStep(lit = false, TorchEvent.CALL_ENDED))
        // The rule hangs on the music's call edges, so the two never drift.
        // The edge listener does nothing but step the rule (no relight at a call's end).
        val listener = torch.substringAfter("MusicCalls.addCallEdgeListener { edge ->").substringBefore("\n        }\n").trim()
        assertEquals(
            "act(app, torchStep(state?.lit == true, if (edge == CallEdge.BEGAN) TorchEvent.CALL_BEGAN else TorchEvent.CALL_ENDED))",
            listener,
        )
        val calls = File(main, "java/com/kidslauncher/mdm/music/MusicCalls.kt").readText()
        // All three sources, the VoIP phase (a ring included) as it is, reach the one edge.
        assertTrue("musicCallActive(VoipCalls.phase, OngoingCalls.hasLiveCall, systemCall(ctx))" in calls)
        assertTrue("val edge = callEdge(state.inCall, inCall)" in calls)
        // Its own try, so the volume cap's setup can't take the call edges with it.
        val media = File(main, "java/com/kidslauncher/mdm/music/MusicSessions.kt").readText()
        assertTrue(Regex("""try \{\s+MusicCalls\.init\(app\)\s+\} catch""").containsMatchIn(media))
    }

    @Test
    fun `the screen going off changes nothing`() {
        // Never off at screen-off (as an iPhone): the torch listens to no screen event at all.
        for (event in listOf("ACTION_SCREEN_OFF", "ACTION_SCREEN_ON", "ACTION_USER_PRESENT", "isInteractive")) {
            assertFalse(event, event in torch)
        }
        // And nothing else switches it: only the alarm's receiver, Home's tile and the lock's button
        // call it (a screen-off receiver elsewhere calling autoOff would break the rule).
        val java = File(main, "java/com/kidslauncher/mdm")
        val callers = java.walk().filter { it.isFile && it.name.endsWith(".kt") }
            .filter { Regex("""Torch\.(autoOff|toggle)\(""").containsMatchIn(it.readText()) }
            .map { it.relativeTo(java).path }.toSet()
        assertTrue(callers.toString(), callers.isNotEmpty() && callers.all { it in setOf("torch/Torch.kt", "ui/home/HomeGridAdapter.kt", "lock/GlancePanel.kt") })
        assertEquals(1, Regex("""Torch\.autoOff\(""").findAll(torch).count())
        assertTrue("override fun onReceive(context: Context, intent: Intent) = Torch.autoOff(context)" in torch)
        val adapter = File(java, "ui/home/HomeGridAdapter.kt").readText()
        assertTrue("is GridTile.Flashlight -> Torch.toggle(activity)" in adapter)
    }

    @Test
    fun `an on-tap refused with CAMERA_DISABLED under the lock hides the lock's button until the next unlock`() {
        assertTrue(torchRefusedOnLock(refused = false, turningOn = true, cameraDisabled = true, locked = true))
        // A refused off never hides it: a lit torch keeps its off button.
        assertFalse(torchRefusedOnLock(refused = false, turningOn = false, cameraDisabled = true, locked = true))
        assertFalse(torchRefusedOnLock(refused = false, turningOn = true, cameraDisabled = true, locked = false))
        assertFalse(torchRefusedOnLock(refused = false, turningOn = true, cameraDisabled = false, locked = true))
        assertTrue(torchRefusedOnLock(refused = true, turningOn = false, cameraDisabled = false, locked = false))
        assertTrue(torchLockButton(TorchState(lit = false), refusedOnLock = false))
        assertFalse(torchLockButton(TorchState(lit = true), refusedOnLock = true))
        assertTrue("val lockButtonShown: Boolean get() = torchLockButton(state, refusedOnLock)" in torch)
        assertTrue("torchRefusedOnLock(refusedOnLock, on, e.reason == CameraAccessException.CAMERA_DISABLED, locked)" in torch)
        // The unlock clears it (the mode listener), so the next lock tries again.
        val unlock = torch.substringAfter("PinLockRuntime.addModeListener {").substringBefore("\n        }\n")
        assertTrue("refusedOnLock && PinLockRuntime.mode != LockMode.LOCKED" in unlock)
        assertTrue("refusedOnLock = false" in unlock)
    }

    @Test
    fun `no flash unit is no tile and no button`() {
        assertNull(torchCamera(emptyList()))
        assertNull(torchCamera(listOf(TorchCamera("0", back = true, flash = false), TorchCamera("1", back = false, flash = true))))
        assertEquals("2", torchCamera(listOf(TorchCamera("1", back = false, flash = true), TorchCamera("2", back = true, flash = true))))
        assertFalse(torchLockButton(null, refusedOnLock = false))
        // qa-f3-round2-code F3R-3: a Home that rendered before init (it runs in a posted message) hears of the tile.
        val init = torch.substringAfter("fun init(context: Context) {").substringBefore("\n    }\n")
        assertTrue(init.trimEnd().endsWith("changed()"))
    }

    @Test
    fun `the torch opens nothing and never touches the camera policy`() {
        val code = torch.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "").replace(Regex("//[^\\n]*"), "")
        for (token in listOf("startActivity", ".send(", "openCamera", "setCameraDisabled", "getActivity(", "getService(")) {
            assertFalse(token, token in code)
        }
        // Nothing stored: process memory and the platform's callback.
        for (token in listOf("SharedPreferences", "getSharedPreferences", "commit()")) {
            assertFalse(token, token in code)
        }
    }
}
