package com.kidslauncher.mdm.sms

import com.kidslauncher.mdm.calls.ChannelMode
import com.kidslauncher.mdm.calls.RoleAction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Design 77 D1, §2.5/§8.6 and D16's sync coalescing - the pure parts of the SMS role and its neighbours. */
class SmsRoleTest {
    @Test
    fun `the role is taken for managed only, handed back for unmanaged only if we took it, kept otherwise`() {
        assertEquals(RoleAction.TAKE, smsRoleAction(ChannelMode.MANAGED, roleHeld = false, takenByUs = false))
        assertEquals(RoleAction.NONE, smsRoleAction(ChannelMode.MANAGED, roleHeld = true, takenByUs = true))
        assertEquals(RoleAction.RELEASE, smsRoleAction(ChannelMode.UNMANAGED, roleHeld = true, takenByUs = true))
        assertEquals("a role the parent gave by hand stays", RoleAction.NONE, smsRoleAction(ChannelMode.UNMANAGED, roleHeld = true, takenByUs = false))
        assertEquals("off keeps it", RoleAction.NONE, smsRoleAction(ChannelMode.OFF, roleHeld = true, takenByUs = true))
        assertEquals("off never takes it", RoleAction.NONE, smsRoleAction(ChannelMode.OFF, roleHeld = false, takenByUs = false))
        assertEquals("unknown rules: neither", RoleAction.NONE, smsRoleAction(null, roleHeld = false, takenByUs = false))
    }

    @Test
    fun `the role goes back to the holder before us, else a known SMS app`() {
        val installed = setOf("com.google.android.apps.messaging", "org.fossify.messages", "me.vibb.launcher")
        assertEquals("org.fossify.messages", smsRoleReturnTo("org.fossify.messages", "me.vibb.launcher", installed))
        assertEquals("com.google.android.apps.messaging", smsRoleReturnTo("gone.app", "me.vibb.launcher", installed))
        assertEquals("com.google.android.apps.messaging", smsRoleReturnTo("me.vibb.launcher", "me.vibb.launcher", installed))
        assertNull(smsRoleReturnTo(null, "me.vibb.launcher", setOf("me.vibb.launcher")))
    }

    @Test
    fun `default apps are locked while either managed channel's role is ours`() {
        assertTrue(lockDefaultAppsFor(callsManaged = false, dialerHeld = false, smsMode = ChannelMode.MANAGED, smsRoleHeld = true))
        assertTrue(lockDefaultAppsFor(callsManaged = true, dialerHeld = true, smsMode = ChannelMode.UNMANAGED, smsRoleHeld = false))
        assertFalse(lockDefaultAppsFor(callsManaged = false, dialerHeld = true, smsMode = ChannelMode.OFF, smsRoleHeld = true))
        assertFalse(lockDefaultAppsFor(callsManaged = false, dialerHeld = false, smsMode = ChannelMode.MANAGED, smsRoleHeld = false))
    }

    @Test
    fun `every other app's SMS permissions are the targets - Play services by its own switch, never us`() {
        val requested = mapOf(
            "org.example.chat" to listOf("android.permission.RECEIVE_SMS", "android.permission.INTERNET"),
            "org.example.game" to listOf("android.permission.INTERNET"),
            "me.vibb.launcher" to listOf("android.permission.SEND_SMS"),
        )
        val gms = listOf("android.permission.RECEIVE_SMS", "android.permission.READ_SMS", "android.permission.CAMERA")
        assertEquals(
            mapOf(
                "org.example.chat" to listOf("android.permission.RECEIVE_SMS"),
                PLAY_SERVICES to listOf("android.permission.RECEIVE_SMS", "android.permission.READ_SMS"),
            ),
            smsPermissionTargets(requested, gms, "me.vibb.launcher", denyPlayServices = true),
        )
        assertFalse(PLAY_SERVICES in smsPermissionTargets(requested, gms, "me.vibb.launcher", denyPlayServices = false))
        // §8.6: the user's rule, until step 0 says what breaks.
        assertTrue(DENY_PLAY_SERVICES_SMS)
    }

    @Test
    fun `fifty holds ask for one sync a minute (QA R2-M4)`() {
        assertEquals(0L, heldSyncDelay(null, 1_000))
        var last: Long? = null
        var requests = 0
        var queued = false
        for (t in 0L until 50) {
            val now = 10_000 + t * 100
            val delay = heldSyncDelay(last, now)
            if (delay == 0L) {
                requests++
                last = now
            } else {
                queued = true
                assertEquals(last!! + HELD_SYNC_INTERVAL_MS - now, delay)
            }
        }
        assertEquals(1, requests)
        assertTrue("the rest come with one delayed request", queued)
        assertEquals(0L, heldSyncDelay(10_000, 10_000 + HELD_SYNC_INTERVAL_MS))
    }

    @Test
    fun `the role's four components are guarded and the rest not exported (§8_4)`() {
        val manifest = listOf("src/main/AndroidManifest.xml", "app/src/main/AndroidManifest.xml").map(::File).first { it.exists() }.readText()
        fun element(name: String) = Regex("<(receiver|service|activity)[^>]*android:name=\"\\.sms\\.$name\"[^>]*>").find(manifest)!!.value
        assertTrue(element("SmsDeliverReceiver").contains("android.permission.BROADCAST_SMS"))
        assertTrue(element("MmsPushReceiver").contains("android.permission.BROADCAST_WAP_PUSH"))
        assertTrue(element("RespondViaMessageService").contains("android.permission.SEND_RESPOND_VIA_MESSAGE"))
        for (name in listOf("SmsSentReceiver", "SmsReplyReceiver")) assertTrue(name, element(name).contains("android:exported=\"false\""))
        for (name in listOf("SmsDeliverReceiver", "MmsPushReceiver", "RespondViaMessageService", "ComposeActivity")) {
            assertFalse(name, element(name).contains("directBootAware"))
        }
    }
}
