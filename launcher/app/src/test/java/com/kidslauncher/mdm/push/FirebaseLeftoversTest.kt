package com.kidslauncher.mdm.push

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Design 19: what the FCM era left is found - and nothing of ours. */
class FirebaseLeftoversTest {

    @Test
    fun `Firebase's and our FCM-era files are picked`() {
        val found = firebaseLeftovers(
            prefsFiles = listOf(
                "push_state.xml",
                "push_state.xml.bak",
                "com.google.firebase.messaging.xml",
                "com.google.firebase.common.prefs:W0RFRkFVTFRd+MToxMjM0NTY3ODkwOmFuZHJvaWQ6YWJj.xml",
                "com.google.android.gms.appid.xml",
                "FirebaseHeartBeatW0RFRkFVTFRd+MToxMjM0NTY3ODkwOmFuZHJvaWQ6YWJj.xml",
                "FirebaseAppHeartBeat.xml",
            ),
            files = listOf(
                "PersistedInstallation.W0RFRkFVTFRd+MToxMjM0NTY3ODkwOmFuZHJvaWQ6YWJj.json",
                "generatefid.lock",
                "com.google.android.gms.appid-no-backup",
            ),
            databases = listOf(
                "com.google.android.datatransport.events",
                "com.google.android.datatransport.events-journal",
            ),
        )
        assertEquals(
            listOf(
                "FirebaseAppHeartBeat",
                "FirebaseHeartBeatW0RFRkFVTFRd+MToxMjM0NTY3ODkwOmFuZHJvaWQ6YWJj",
                "com.google.android.gms.appid",
                "com.google.firebase.common.prefs:W0RFRkFVTFRd+MToxMjM0NTY3ODkwOmFuZHJvaWQ6YWJj",
                "com.google.firebase.messaging",
                "push_state",
            ),
            found.prefs,
        )
        assertEquals(
            listOf(
                "PersistedInstallation.W0RFRkFVTFRd+MToxMjM0NTY3ODkwOmFuZHJvaWQ6YWJj.json",
                "com.google.android.gms.appid-no-backup",
                "generatefid.lock",
            ),
            found.files,
        )
        assertEquals(listOf("com.google.android.datatransport.events"), found.databases)
        assertFalse(found.isEmpty)
    }

    @Test
    fun `our own files are never picked`() {
        val found = firebaseLeftovers(
            prefsFiles = listOf(
                "me.vibb.launcher_preferences.xml", "me.vibb.launcher.debug_preferences.xml",
                "pin_lock_state.xml", "play_state.xml", "update_fence.xml", "update_fence_last.xml",
                "app_downloads.xml", "camera_lock.xml", "wallpaper_state.xml", "time_rules_state.xml",
                "element_rooms.xml", "voip_call.xml", "firebase_cleanup.xml", "push_state_v2.xml",
            ),
            files = listOf(
                "contact_photos", "wallpapers", "tsnet", "self_update", "app_downloads", "blocklist.txt",
                "profileInstalled", "PersistedInstallation",
            ),
            databases = listOf("com.example.other", "datatransport"),
        )
        assertTrue(found.toString(), found.isEmpty)
    }

    @Test
    fun `only the data transport's job service counts`() {
        assertTrue(isFirebaseJobService("com.google.android.datatransport.runtime.scheduling.jobscheduling.JobInfoSchedulerService"))
        assertFalse(isFirebaseJobService("com.kidslauncher.mdm.push.BackstopReceiver"))
        assertFalse(isFirebaseJobService(null))
    }
}
