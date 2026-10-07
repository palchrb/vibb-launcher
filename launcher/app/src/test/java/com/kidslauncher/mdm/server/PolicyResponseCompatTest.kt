package com.kidslauncher.mdm.server

import com.kidslauncher.mdm.calls.toRules
import com.kidslauncher.mdm.server.dto.CallPolicy
import com.kidslauncher.mdm.server.dto.CallState
import com.kidslauncher.mdm.server.dto.LauncherUi
import com.kidslauncher.mdm.server.dto.PolicyContact
import com.kidslauncher.mdm.server.dto.KidLock
import com.kidslauncher.mdm.server.dto.PolicyResponse
import com.kidslauncher.mdm.server.dto.StatusReportRequest
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The cached policy blob and the server response must keep decoding across launcher and server
 * versions. The fixture has exactly the 17 keys kid-phone-server's `policy_json_keys_snapshot`
 * test pins, in the server's field order, with realistic values.
 */
class PolicyResponseCompatTest {

    private val serverResponse = """
        {
          "allowlist": ["org.example.music", "org.example.chat"],
          "weekday_start_minutes": 420,
          "weekday_end_minutes": 1200,
          "weekend_start_minutes": 480,
          "weekend_end_minutes": 1260,
          "bedtime_start_minutes": 1260,
          "bedtime_end_minutes": 420,
          "kiosk_desired": true,
          "lock_task_features": 63,
          "override_pin_hash": "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08",
          "override_pin_salt": "a3c1f0e2d4b6a8c0e2f4a6b8c0d2e4f6",
          "quick_controls_mask": 3,
          "pending_command": {"id": 12, "command": "ring"},
          "vpn_filter_enabled": true,
          "dns_filter_version": "5d41402abc4b2a76b9719d911017c592",
          "dns_upstream_provider": "quad9",
          "packages_to_uninstall": ["org.example.old"]
        }
    """.trimIndent()

    @Test
    fun `todays server response decodes`() {
        val cached = decodeCached(serverResponse)
        assertTrue(cached is CachedPolicy.Ok)
        val policy = (cached as CachedPolicy.Ok).policy
        assertEquals(listOf("org.example.music", "org.example.chat"), policy.allowlist)
        assertEquals(1260, policy.bedtimeStartMinutes)
        assertEquals(true, policy.kioskDesired)
        assertEquals(63L, policy.lockTaskFeatures)
        assertEquals(3L, policy.quickControlsMask)
        assertEquals("ring", policy.pendingCommand?.command)
        assertEquals("quad9", policy.dnsUpstreamProvider)
        assertEquals(listOf("org.example.old"), policy.packagesToUninstall)
        assertEquals(FreshDecode.Ok(policy), decodeFresh(serverResponse))
    }

    @Test
    fun `a server without block_activity_start leaves the kiosk app block off (qa-09-code 7)`() {
        assertEquals(false, ServerJson.decodeFromString(PolicyResponse.serializer(), "{}").blockActivityStart)
        assertEquals(true, ServerJson.decodeFromString(PolicyResponse.serializer(), """{"block_activity_start":true}""").blockActivityStart)
        assertEquals(false, LastEnforcedPlan.decode("{}")!!.blockActivityStart)
    }

    @Test
    fun `screen_timeout_seconds decodes, is reported under the server's key, and survives the fallback`() {
        assertNull(ServerJson.decodeFromString(PolicyResponse.serializer(), "{}").screenTimeoutSeconds)
        val policy = ServerJson.decodeFromString(PolicyResponse.serializer(), """{"screen_timeout_seconds":120}""")
        assertEquals(120, policy.screenTimeoutSeconds)
        assertEquals(120, LastEnforcedPlan.of(policy).toPolicy().screenTimeoutSeconds)
        assertNull(LastEnforcedPlan.decode("{}")!!.screenTimeoutSeconds)
        val report = StatusReportRequest(lockReason = "NONE", kioskEngaged = true, screenTimeoutSeconds = 60)
        val json = ServerJson.parseToJsonElement(ServerJson.encodeToString(StatusReportRequest.serializer(), report)).jsonObject
        assertEquals("60", json["screen_timeout_seconds"].toString())
    }

    @Test
    fun `backup_service_enabled is reported under the server's key and left out when unknown`() {
        val off = StatusReportRequest(lockReason = "NONE", kioskEngaged = true, backupServiceEnabled = false)
        val offJson = ServerJson.parseToJsonElement(ServerJson.encodeToString(StatusReportRequest.serializer(), off)).jsonObject
        assertEquals("false", offJson["backup_service_enabled"].toString())
        val on = StatusReportRequest(lockReason = "NONE", kioskEngaged = true, backupServiceEnabled = true)
        val onJson = ServerJson.parseToJsonElement(ServerJson.encodeToString(StatusReportRequest.serializer(), on)).jsonObject
        assertEquals("true", onJson["backup_service_enabled"].toString())
        // Unknown (not device owner, unreadable): absent, like a report from an older launcher.
        val unknown = StatusReportRequest(lockReason = "NONE", kioskEngaged = true)
        val unknownJson = ServerJson.parseToJsonElement(ServerJson.encodeToString(StatusReportRequest.serializer(), unknown)).jsonObject
        assertTrue("backup_service_enabled" !in unknownJson)
    }

    @Test
    fun `ringer_mode and interruption_filter are reported under the server's keys and left out when unknown (18)`() {
        val report = StatusReportRequest(
            lockReason = "NONE", kioskEngaged = true,
            ringerMode = ringerModeName(1), interruptionFilter = interruptionFilterName(2),
        )
        val json = ServerJson.parseToJsonElement(ServerJson.encodeToString(StatusReportRequest.serializer(), report)).jsonObject
        assertEquals("\"vibrate\"", json["ringer_mode"].toString())
        assertEquals("\"priority\"", json["interruption_filter"].toString())
        // Unreadable: absent, like a report from an older launcher (the server stores NULL).
        val unknown = StatusReportRequest(
            lockReason = "NONE", kioskEngaged = true,
            ringerMode = ringerModeName(-1), interruptionFilter = interruptionFilterName(0),
        )
        val unknownJson = ServerJson.parseToJsonElement(ServerJson.encodeToString(StatusReportRequest.serializer(), unknown)).jsonObject
        assertTrue("ringer_mode" !in unknownJson)
        assertTrue("interruption_filter" !in unknownJson)
        // The policy shape is unchanged: the sound bit is just one more bit of the mask.
        assertEquals(15L, ServerJson.decodeFromString(PolicyResponse.serializer(), """{"quick_controls_mask":15}""").quickControlsMask)
    }

    @Test
    fun `step 11 switches - missing means off, and the fallback never carries them`() {
        val none = ServerJson.decodeFromString(PolicyResponse.serializer(), "{}")
        assertEquals(false, none.updateFence)
        assertEquals(false, none.notificationAutoCancel)
        val both = ServerJson.decodeFromString(PolicyResponse.serializer(), """{"update_fence":true,"notification_auto_cancel":true}""")
        assertEquals(true, both.updateFence)
        assertEquals(true, both.notificationAutoCancel)
        // With no usable cache the fence is off (released) and nothing is cancelled.
        val fallback = LastEnforcedPlan.of(both).toPolicy()
        assertEquals(false, fallback.updateFence)
        assertEquals(false, fallback.notificationAutoCancel)
    }

    @Test
    fun `the boot cover is off unless the server says on, and the fallback never carries it (16b)`() {
        assertEquals(false, ServerJson.decodeFromString(PolicyResponse.serializer(), "{}").bootCover)
        val on = ServerJson.decodeFromString(PolicyResponse.serializer(), """{"boot_cover":true}""")
        assertEquals(true, on.bootCover)
        assertEquals(false, LastEnforcedPlan.of(on).toPolicy().bootCover)
    }

    @Test
    fun `status report boot_cover uses the server's keys, always sent (16b)`() {
        val report = StatusReportRequest(
            lockReason = "NONE", kioskEngaged = true,
            bootCover = com.kidslauncher.mdm.server.dto.BootCoverReport(
                wanted = true, tripped = false, lastArmedAtMs = null, lastShownAtMs = 5L, lastHandover = "cover", lastHandoverAtMs = 6L,
            ),
        )
        val json = ServerJson.parseToJsonElement(ServerJson.encodeToString(StatusReportRequest.serializer(), report)).jsonObject
        assertEquals(
            setOf("wanted", "tripped", "last_armed_at_ms", "last_shown_at_ms", "last_handover", "last_handover_at_ms"),
            json["boot_cover"]!!.jsonObject.keys,
        )
        assertEquals("boot_cover_v1", com.kidslauncher.mdm.lock.BOOT_COVER_CAPABILITY)
    }

    @Test
    fun `the blocked-domain log is off unless the server says on`() {
        assertEquals(false, ServerJson.decodeFromString(PolicyResponse.serializer(), "{}").dnsLogEnabled)
        assertEquals(true, ServerJson.decodeFromString(PolicyResponse.serializer(), """{"dns_log_enabled":true}""").dnsLogEnabled)
        // No usable cache: no log.
        val on = ServerJson.decodeFromString(PolicyResponse.serializer(), """{"dns_log_enabled":true}""")
        assertEquals(false, LastEnforcedPlan.of(on).toPolicy().dnsLogEnabled)
    }

    @Test
    fun `step 11 status keys are the server's - update_fence and notification_cancels`() {
        val report = StatusReportRequest(
            lockReason = "NONE",
            kioskEngaged = true,
            updateFence = com.kidslauncher.mdm.server.dto.UpdateFenceReport(
                enabled = true, state = "fenced", unsuspendable = listOf("a.home"), lastRelease = "replaced",
                homeRoleHeld = true, pendingTag = "launcher-v1.2.3", pendingSinceMs = 5L, waitingFor = "outside_window",
                lastFencedAtMs = 6L, lastReleasedAtMs = 7L,
            ),
            notificationCancels = com.kidslauncher.mdm.server.dto.NotificationCancelsReport(
                active = true,
                entries = listOf(com.kidslauncher.mdm.server.dto.NotificationCancelEntry("com.google.android.gms", "nag", 2, 1)),
                dropped = 0,
            ),
        )
        val json = ServerJson.parseToJsonElement(ServerJson.encodeToString(StatusReportRequest.serializer(), report)).jsonObject
        val fence = json["update_fence"]!!.jsonObject
        assertEquals(
            setOf(
                "enabled", "state", "unsuspendable", "last_release", "home_role_held", "pending_tag", "pending_since_ms", "waiting_for",
                "last_fenced_at_ms", "last_released_at_ms",
            ),
            fence.keys,
        )
        val cancels = json["notification_cancels"]!!.jsonObject
        assertEquals(setOf("active", "entries", "dropped"), cancels.keys)
        val entry = cancels["entries"]!!.jsonArray.single().jsonObject
        assertEquals(setOf("package_name", "channel", "cancelled", "snoozed"), entry.keys)
    }

    /** Design 14 (QA #1): `launcher_ui.app_display` - present, absent, `null`, an unknown icon, a
     * `null` colour, a number for the label, not even a list: the policy always decodes, the map
     * takes what it can use, and the cache round trip keeps it. */
    @Test
    fun `launcher_ui app_display never fails the policy`() {
        fun withDisplay(display: String) = serverResponse.replaceFirst(
            "{",
            """{"launcher_ui": {"language": "nb", "home_columns": 3, "wallpapers": [], "app_display": $display},""",
        )
        val good = withDisplay(
            """[{"package_name": "io.element.android.x", "label": "Chat", "icon": "chat", "color": "peach"}]""",
        )
        val policy = (decodeCached(good) as CachedPolicy.Ok).policy
        assertEquals(
            mapOf("io.element.android.x" to com.kidslauncher.mdm.appdisplay.AppDisplayEntry("Chat", "chat", "peach")),
            com.kidslauncher.mdm.appdisplay.appDisplayMap(policy.launcherUi!!.appDisplay),
        )
        assertEquals(CachedPolicy.Ok(policy), decodeCached(ServerJson.encodeToString(PolicyResponse.serializer(), policy)))
        for (display in listOf(
            """[{"package_name": "io.element.android.x", "label": "Chat", "icon": "rocket", "color": "peach"}]""",
            """[{"package_name": "io.element.android.x", "label": "Chat", "icon": "chat", "color": null}]""",
            """[{"package_name": "io.element.android.x", "label": 5, "icon": "chat"}]""",
            """[null, 5, "x", {"package_name": null}]""",
            "null", "{}", "\"x\"", "[]",
        )) {
            val decoded = decodeCached(withDisplay(display))
            assertTrue(display, decoded is CachedPolicy.Ok)
            assertTrue(display, decodeFresh(withDisplay(display)) is FreshDecode.Ok)
        }
        val unknownIcon = (decodeCached(withDisplay("""[{"package_name": "io.element.android.x", "label": "Chat", "icon": "rocket"}]""")) as CachedPolicy.Ok).policy
        assertEquals(
            com.kidslauncher.mdm.appdisplay.AppDisplayEntry("Chat", null),
            com.kidslauncher.mdm.appdisplay.appDisplayMap(unknownIcon.launcherUi!!.appDisplay)["io.element.android.x"],
        )
        // An older server: no app_display.
        assertNull((decodeCached(withDisplay("[]").replace(", \"app_display\": []", "")) as CachedPolicy.Ok).policy.launcherUi!!.appDisplay)
    }

    /** Design 13: "App updates only on Wi-Fi". Missing (an older server) or `null` = off, as
     * before - nothing in this key can fail the policy; the fallback keeps the parent's choice. */
    @Test
    fun `app_updates_wifi_only - missing or null is off, and the fallback keeps it`() {
        assertNull(ServerJson.decodeFromString(PolicyResponse.serializer(), "{}").appUpdatesWifiOnly)
        assertNull((decodeCached(serverResponse) as CachedPolicy.Ok).policy.appUpdatesWifiOnly)
        val withNull = serverResponse.replaceFirst("{", "{\"app_updates_wifi_only\": null,")
        assertNull((decodeCached(withNull) as CachedPolicy.Ok).policy.appUpdatesWifiOnly)
        val on = (decodeCached(serverResponse.replaceFirst("{", "{\"app_updates_wifi_only\": true,")) as CachedPolicy.Ok).policy
        assertEquals(true, on.appUpdatesWifiOnly)
        val off = (decodeCached(serverResponse.replaceFirst("{", "{\"app_updates_wifi_only\": false,")) as CachedPolicy.Ok).policy
        assertEquals(false, off.appUpdatesWifiOnly)
        assertEquals(true, LastEnforcedPlan.of(on).toPolicy().appUpdatesWifiOnly)
        assertNull(LastEnforcedPlan.decode("{}")!!.appUpdatesWifiOnly)
        assertEquals(CachedPolicy.Ok(on), decodeCached(ServerJson.encodeToString(PolicyResponse.serializer(), on)))
    }

    /** Design 13: the list's `sha256` (nullable, missing from an older server) and the status
     * report's `app_downloads` keys - kid-phone-server's `app_downloads::AppDownloads`. */
    @Test
    fun `apps list sha256 and status app_downloads use the server's keys`() {
        val listed = ServerJson.decodeFromString(
            com.kidslauncher.mdm.server.dto.TrackedAppUpdate.serializer(),
            """{"id":4,"name":"Element X","package_name":"","release_tag":"v1@14","download_url":"/api/devices/apps/4/download","is_launcher":false,"sha256":null}""",
        )
        assertNull(listed.sha256)
        val old = ServerJson.decodeFromString(
            com.kidslauncher.mdm.server.dto.TrackedAppUpdate.serializer(),
            """{"id":4,"name":"Element X","package_name":"","release_tag":"v1@14","download_url":"/x","is_launcher":false}""",
        )
        assertNull(old.sha256)
        val report = StatusReportRequest(
            lockReason = "NONE",
            kioskEngaged = true,
            appDownloads = com.kidslauncher.mdm.server.dto.AppDownloadsReport(
                wifiOnly = true,
                network = "metered",
                entries = listOf(
                    com.kidslauncher.mdm.server.dto.AppDownloadEntry(4, "v1@14", "waiting_wifi", 120L, 326L, 5L, null),
                ),
            ),
        )
        val json = ServerJson.parseToJsonElement(ServerJson.encodeToString(StatusReportRequest.serializer(), report)).jsonObject
        val downloads = json["app_downloads"]!!.jsonObject
        assertEquals(setOf("wifi_only", "network", "entries"), downloads.keys)
        assertEquals(
            setOf("tracked_app_id", "release_tag", "state", "bytes", "total", "since_ms", "any_network_at_ms"),
            downloads["entries"]!!.jsonArray.single().jsonObject.keys,
        )
    }

    @Test
    fun `a cache without kid_lock is a phone without handy's lock (step 10)`() {
        assertNull((decodeCached(serverResponse) as CachedPolicy.Ok).policy.kidLock)
        val withLock = serverResponse.replaceFirst("{", """{"kid_lock":{"pin_hash":"ab","pin_salt":"cd","pin_length":6},""")
        val kidLock = (decodeCached(withLock) as CachedPolicy.Ok).policy.kidLock!!
        assertEquals(KidLock("ab", "cd", 6), kidLock)
        val withNull = serverResponse.replaceFirst("{", """{"kid_lock":null,""")
        assertNull((decodeCached(withNull) as CachedPolicy.Ok).policy.kidLock)
    }

    /**
     * Design 19: a 0.19 server (FCM) still sends `push`, and a launcher before the removal cached
     * it (encoded without defaults, as [storeAcceptedPolicy] writes it). Both keep decoding to the
     * same policy as without the key - after the removal it is simply an unknown key.
     */
    @Test
    fun `a 0_19 response and a cached blob that carry push decode (19)`() {
        val push = """"push": {"fcm_enabled": true, "fcm_ok": true, "fcm_token_hash": "0123456789abcdef"}"""
        val response019 = serverResponse.replaceFirst("{", "{$push,")
        val fresh = decodeFresh(response019)
        assertTrue(fresh is FreshDecode.Ok)
        val plain = (decodeFresh(serverResponse) as FreshDecode.Ok).policy
        fun withoutPush(policy: PolicyResponse) =
            ServerJson.encodeToJsonElement(PolicyResponse.serializer(), policy).jsonObject - "push"
        assertEquals(withoutPush(plain), withoutPush((fresh as FreshDecode.Ok).policy))
        assertTrue(decodeCached(response019) is CachedPolicy.Ok)

        val cached019 = """{"allowlist":["org.example.music"],"kiosk_desired":true,"lock_task_features":63,""" +
            """"push":{"fcm_enabled":true,"fcm_ok":true,"fcm_token_hash":"0123456789abcdef"},""" +
            """"block_activity_start":true,"screen_timeout_seconds":60}"""
        val cached = decodeCached(cached019)
        assertTrue(cached is CachedPolicy.Ok)
        val policy = (cached as CachedPolicy.Ok).policy
        assertEquals(listOf("org.example.music"), policy.allowlist)
        assertEquals(true, policy.kioskDesired)
        assertEquals(63L, policy.lockTaskFeatures)
        assertEquals(true, policy.blockActivityStart)
        assertEquals(60, policy.screenTimeoutSeconds)
        // An old server without FCM sent `fcm_enabled: false` - the same as no key at all.
        val off = serverResponse.replaceFirst("{", """{"push":{"fcm_enabled":false,"fcm_ok":false,"fcm_token_hash":null},""")
        assertEquals(withoutPush(plain), withoutPush((decodeFresh(off) as FreshDecode.Ok).policy))
    }

    @Test
    fun `unknown keys are ignored`() {
        val withExtra = serverResponse.replace(
            "\"packages_to_uninstall\"",
            "\"some_future_field\": {\"nested\": [1, 2]}, \"packages_to_uninstall\""
        )
        assertTrue(decodeCached(withExtra) is CachedPolicy.Ok)
    }

    @Test
    fun `a call_policy object from a newer server decodes`() {
        val withCalls = serverResponse.replace(
            "\"packages_to_uninstall\"",
            "\"call_policy\": {\"managed\": true, \"calls_enabled\": true, \"contacts\": []}, \"packages_to_uninstall\""
        )
        assertTrue(decodeCached(withCalls) is CachedPolicy.Ok)
    }

    @Test
    fun `a blob without call_policy gives null`() {
        assertNull((decodeCached(serverResponse) as CachedPolicy.Ok).policy.callPolicy)
    }

    @Test
    fun `an empty call_policy object is a deny-default managed policy`() {
        val withEmpty = serverResponse.replace(
            "\"packages_to_uninstall\"", "\"call_policy\": {}, \"packages_to_uninstall\""
        )
        val callPolicy = (decodeCached(withEmpty) as CachedPolicy.Ok).policy.callPolicy!!
        assertEquals(CallPolicy(managed = true, callsEnabled = false, smsEnabled = false), callPolicy)
        assertTrue(callPolicy.contacts.isEmpty())
    }

    /** The shape kid-phone-server's `managed_policy_lists_contacts_with_flags_in_order` pins. */
    @Test
    fun `the server's call_policy decodes`() {
        val withCalls = serverResponse.replace(
            "\"packages_to_uninstall\"",
            """
            "call_policy": {
              "managed": true, "calls_enabled": true, "sms_enabled": false, "default_country_code": "47",
              "contacts": [
                {"id": 3, "name": "Mamma", "number": "+4790000001", "inbound": true, "outbound": true,
                 "show_on_home": true, "message_app": "element", "message_address": "@mamma:example.org"},
                {"id": 4, "name": "Pappa", "number": "+4790000002", "inbound": true, "outbound": false,
                 "show_on_home": false, "message_app": "sms", "message_address": null}
              ]
            },
            "packages_to_uninstall"
            """.trimIndent()
        )
        val policy = (decodeCached(withCalls) as CachedPolicy.Ok).policy
        val callPolicy = policy.callPolicy!!
        assertTrue(callPolicy.managed)
        assertEquals(false, callPolicy.smsEnabled)
        assertEquals(
            PolicyContact(3, "Mamma", "+4790000001", true, true, true, "element", "@mamma:example.org"),
            callPolicy.contacts[0],
        )
        assertEquals(null, callPolicy.contacts[1].messageAddress)
        // And survives the cache round trip (defaults are not encoded).
        val reencoded = ServerJson.encodeToString(PolicyResponse.serializer(), policy)
        assertEquals(CachedPolicy.Ok(policy), decodeCached(reencoded))
    }

    @Test
    fun `explicit managed false decodes and round trips`() {
        val withCalls = serverResponse.replace(
            "\"packages_to_uninstall\"",
            "\"call_policy\": {\"managed\": false, \"calls_enabled\": true, \"sms_enabled\": true, \"default_country_code\": \"47\", \"contacts\": []}, \"packages_to_uninstall\""
        )
        val policy = (decodeCached(withCalls) as CachedPolicy.Ok).policy
        assertEquals(false, policy.callPolicy!!.managed)
        val reencoded = ServerJson.encodeToString(PolicyResponse.serializer(), policy)
        assertEquals(false, (decodeCached(reencoded) as CachedPolicy.Ok).policy.callPolicy!!.managed)
    }

    /** kid-phone-server stores `capabilities` and `call_state` from these keys. */
    @Test
    fun `status report call fields use the server's keys`() {
        val report = StatusReportRequest(
            lockReason = "NONE", kioskEngaged = true, capabilities = listOf("call_policy_v1"),
            callState = CallState(
                state = "managed", dialerRoleHeld = true, redirectionRoleHeld = false,
                defaultDialer = "x", systemDialer = "y", smsRestricted = true, outgoingRestricted = false,
                defaultSmsPackage = null, lastError = null, lastEmergencyCallAt = null, callbackWindowUntil = null,
                callLogReadable = false, bootPolicy = "ok",
            ),
        )
        val json = ServerJson.parseToJsonElement(ServerJson.encodeToString(StatusReportRequest.serializer(), report)).jsonObject
        assertEquals("[\"call_policy_v1\"]", json["capabilities"].toString())
        val callState = json["call_state"]!!.jsonObject
        assertEquals(
            setOf(
                "state", "dialer_role_held", "redirection_role_held", "default_dialer", "system_dialer",
                "sms_restricted", "outgoing_restricted", "default_sms_package", "last_error",
                "last_emergency_call_at", "callback_window_until", "call_log_readable", "boot_policy",
            ),
            callState.keys,
        )
    }

    /** Step 5 keys: `launcher_ui`, a contact's `photo`, `notification_listener_enabled`. */
    @Test
    fun `launcher_ui and contact photos decode, and their absence means defaults`() {
        val photo = "ab".repeat(32)
        val withUi = serverResponse.replace(
            "\"packages_to_uninstall\"",
            """
            "call_policy": {"managed": true, "calls_enabled": true, "sms_enabled": true, "default_country_code": "47",
              "contacts": [{"id": 3, "name": "Mamma", "number": "+4790000001", "inbound": true, "outbound": true,
                "show_on_home": true, "message_app": "sms", "message_address": null, "photo": "$photo"}]},
            "launcher_ui": {"language": "nb", "home_columns": 4},
            "packages_to_uninstall"
            """.trimIndent()
        )
        val policy = (decodeCached(withUi) as CachedPolicy.Ok).policy
        assertEquals(LauncherUi("nb", 4), policy.launcherUi)
        assertEquals(photo, policy.callPolicy!!.contacts[0].photo)
        assertEquals(photo, policy.callPolicy!!.toRules().contacts[0].photo)
        val reencoded = ServerJson.encodeToString(PolicyResponse.serializer(), policy)
        assertEquals(CachedPolicy.Ok(policy), decodeCached(reencoded))

        // An older server: no launcher_ui, no photo.
        val old = (decodeCached(serverResponse) as CachedPolicy.Ok).policy
        assertNull(old.launcherUi)
        assertEquals(LauncherUi("system", 3), LauncherUi())

        // Step 8: launcher_ui.wallpapers as the server sends it (every key, nulls included); a
        // step-5 launcher_ui without it decodes to no wallpapers (then navy).
        val withWallpapers = serverResponse.replace(
            "\"packages_to_uninstall\"",
            """
            "launcher_ui": {"language": "en", "home_columns": 3, "wallpapers": [
              {"id": 1, "kind": "color", "colors": ["#14213D"], "image": null, "label": "Navy", "builtin_key": "navy", "lock_screen": false},
              {"id": 7, "kind": "image", "colors": [], "image": "$photo", "label": "Hytta", "builtin_key": null, "lock_screen": true}
            ]},
            "packages_to_uninstall"
            """.trimIndent()
        )
        val walls = (decodeCached(withWallpapers) as CachedPolicy.Ok).policy.launcherUi!!.wallpapers
        assertEquals(2, walls.size)
        assertEquals("navy", walls[0].builtinKey)
        assertEquals(photo, walls[1].image)
        assertTrue(walls[1].lockScreen)
        assertEquals(emptyList<Any>(), policy.launcherUi!!.wallpapers)

        val report = StatusReportRequest(lockReason = "NONE", kioskEngaged = true, notificationListenerEnabled = false)
        val json = ServerJson.parseToJsonElement(ServerJson.encodeToString(StatusReportRequest.serializer(), report)).jsonObject
        assertEquals("false", json["notification_listener_enabled"].toString())
    }

    /** Documents the missing `coerceInputValues`: one null in a non-nullable field fails the whole
     * decode, which is why the server's snapshot test forbids it. */
    @Test
    fun `null in a non-nullable field is Corrupt`() {
        val withNull = serverResponse.replace("\"kiosk_desired\": true", "\"kiosk_desired\": null")
        assertTrue(decodeCached(withNull) is CachedPolicy.Corrupt)
        assertTrue(decodeFresh(withNull) is FreshDecode.Failed)
    }

    /** Step 10: kid-phone-server's `kid_lock::LockState` keys - and nothing else (no unlock
     * times, no PIN material); `in_call_ui_failed_at` in the call state only when it happened. */
    @Test
    fun `status report lock_state uses the server's keys`() {
        val report = StatusReportRequest(
            lockReason = "NONE", kioskEngaged = true,
            lockState = com.kidslauncher.mdm.server.dto.LockStateReport(
                active = true, inactive = null, locked = true, failures = 5, backoffUntilMs = 1L, exemptYields = 2,
                voipFsiDenied = emptyList(),
            ),
        )
        val json = ServerJson.parseToJsonElement(ServerJson.encodeToString(StatusReportRequest.serializer(), report)).jsonObject
        assertEquals(
            setOf("active", "inactive", "locked", "failures", "backoff_until_ms", "exempt_yields", "voip_fsi_denied"),
            json["lock_state"]!!.jsonObject.keys,
        )
        assertEquals("[]", json["lock_state"]!!.jsonObject["voip_fsi_denied"].toString())
        val failed = CallState(
            state = "managed", dialerRoleHeld = true, redirectionRoleHeld = true, defaultDialer = null, systemDialer = null,
            smsRestricted = false, outgoingRestricted = false, defaultSmsPackage = null, lastError = null,
            lastEmergencyCallAt = null, callbackWindowUntil = null, callLogReadable = true, bootPolicy = "ok",
            inCallUiFailedAt = "2026-10-05T08:00:00Z",
        )
        val callJson = ServerJson.parseToJsonElement(ServerJson.encodeToString(CallState.serializer(), failed)).jsonObject
        assertEquals("\"2026-10-05T08:00:00Z\"", callJson["in_call_ui_failed_at"].toString())
    }

    @Test
    fun `cache round trip keeps the policy`() {
        val policy = (decodeCached(serverResponse) as CachedPolicy.Ok).policy
        val reencoded = ServerJson.encodeToString(PolicyResponse.serializer(), policy)
        assertEquals(CachedPolicy.Ok(policy), decodeCached(reencoded))
    }

    /** Step 6 keys: `time_policy`, `location_policy`, status `time_state` - same names as
     * kid-phone-server's `policy_json_keys_snapshot` and `StatusReportRequest`. */
    @Test
    fun `time_policy and location_policy decode, and their absence means an older server`() {
        val withTime = serverResponse.replace(
            "\"packages_to_uninstall\"",
            """
            "time_policy": {
              "rules": [{"id": 4, "name": "Skole", "kind": "school", "calls_allowed": false,
                "exempt_apps": ["org.fossify.calendar"],
                "days": [{"start": 495, "end": 840}, {"start": 495, "end": 840}, {"start": 495, "end": 840},
                         {"start": 495, "end": 840}, {"start": 495, "end": 840}, null, null]}],
              "daily_budget_minutes": [60, 60, 60, 60, 60, 120, null],
              "lifts": [{"id": 9, "target": "rule", "rule_id": 4, "minutes": 30, "expires_at_ms": 1759650000000},
                        {"id": 10, "target": "budget", "rule_id": null, "minutes": 15, "expires_at_ms": 1759690000000}]
            },
            "location_policy": {"mode": "interval", "interval_minutes": 15},
            "packages_to_uninstall"
            """.trimIndent()
        )
        val policy = (decodeCached(withTime) as CachedPolicy.Ok).policy
        val time = policy.timePolicy!!
        assertEquals("school", time.rules[0].kind)
        assertEquals(false, time.rules[0].callsAllowed)
        assertEquals(listOf("org.fossify.calendar"), time.rules[0].exemptApps)
        assertEquals(com.kidslauncher.mdm.timerules.TimeWindow(495, 840), time.rules[0].days[0])
        assertNull(time.rules[0].days[5])
        assertEquals(listOf(60, 60, 60, 60, 60, 120, null), time.dailyBudgetMinutes)
        assertEquals(4L, time.lifts[0].ruleId)
        assertNull(time.lifts[1].ruleId)
        assertEquals(1759650000000L, time.lifts[0].expiresAtMs)
        assertEquals(com.kidslauncher.mdm.server.dto.LocationPolicy("interval", 15), policy.locationPolicy)
        val reencoded = ServerJson.encodeToString(PolicyResponse.serializer(), policy)
        assertEquals(CachedPolicy.Ok(policy), decodeCached(reencoded))

        val old = (decodeCached(serverResponse) as CachedPolicy.Ok).policy
        assertNull(old.timePolicy)
        assertNull(old.locationPolicy)

        val report = StatusReportRequest(
            lockReason = "SCHOOL", kioskEngaged = true, capabilities = listOf("call_policy_v1", TIME_RULES_CAPABILITY),
            timeState = com.kidslauncher.mdm.server.dto.TimeState(
                day = "2026-10-05", usedMinutes = 42, budgetMinutes = 90, extraMinutes = 30, activeRuleId = 4,
                activeRuleName = "Skole", callsBlocked = true, lockReason = "SCHOOL", liftsActive = listOf(10),
            ),
        )
        val json = ServerJson.parseToJsonElement(ServerJson.encodeToString(StatusReportRequest.serializer(), report)).jsonObject
        assertEquals(
            setOf("day", "used_minutes", "budget_minutes", "extra_minutes", "active_rule_id", "active_rule_name",
                "calls_blocked", "lock_reason", "lifts_active"),
            json["time_state"]!!.jsonObject.keys,
        )
        assertEquals("[\"call_policy_v1\",\"time_rules_v1\"]", json["capabilities"].toString())
    }
}
