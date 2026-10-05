package com.kidslauncher.mdm.push

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FcmHostsTest {
    @Test
    fun `FCM hosts are recognised`() {
        for (host in listOf(
            "mtalk.google.com", "mtalk4.google.com", "alt3-mtalk.google.com", "mtalk-staging.google.com",
            "MTALK.google.com.", "fcm.googleapis.com", "firebaseinstallations.googleapis.com",
            "android.apis.google.com", "android.googleapis.com", "android.clients.google.com",
            "fcmtoken.googleapis.com", "fcmregistrations.googleapis.com",
        )) {
            assertTrue(host, isFcmHost(host))
        }
    }

    @Test
    fun `telemetry and other hosts are not`() {
        for (host in listOf(
            "firebaselogging-pa.googleapis.com", "app-measurement.com", "google.com", "googleapis.com",
            "evil-mtalk.google.com", "mtalk.google.com.evil.org", "ads.google.com",
        )) {
            assertFalse(host, isFcmHost(host))
        }
    }
}
