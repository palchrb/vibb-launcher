package com.kidslauncher.mdm.server

import com.kidslauncher.mdm.calls.toRules
import com.kidslauncher.mdm.server.dto.CallPolicy
import com.kidslauncher.mdm.server.dto.CallState
import com.kidslauncher.mdm.server.dto.LauncherUi
import com.kidslauncher.mdm.server.dto.PolicyContact
import com.kidslauncher.mdm.server.dto.KidLock
import com.kidslauncher.mdm.server.dto.ParentPinEvent
import com.kidslauncher.mdm.server.dto.ParentPinEventBatch
import com.kidslauncher.mdm.server.dto.PolicyResponse
import com.kidslauncher.mdm.server.dto.StatusReportRequest
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

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
          "pending_command": {"id": 12, "command": "ring", "requested_at": "2026-10-11 18:00:00"},
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
        assertEquals("2026-10-11 18:00:00", policy.pendingCommand?.requestedAt)
        assertEquals("quad9", policy.dnsUpstreamProvider)
        assertEquals(listOf("org.example.old"), policy.packagesToUninstall)
        assertEquals(FreshDecode.Ok(policy), decodeFresh(serverResponse))
    }

    /** Design 68 §3.1: `app_permissions` (the server's `policy_json_keys_snapshot` literal) - read field by field by
     * `appPermissionsOf`, so nothing in it fails the policy; missing or not a list = touch nothing (`null`). */
    @Test
    fun `app_permissions decodes, never fails the policy, and missing means touch nothing`() {
        fun policy(value: String) = ServerJson.decodeFromString(PolicyResponse.serializer(), """{"app_permissions": $value}""")
        assertEquals(
            setOf("com.whatsapp"),
            appPermissionsOf(policy("""[{"package_name": "com.whatsapp", "camera": "deny"}]""").appPermissions),
        )
        // A bad package, another camera value, a wrong type, a newer key: dropped, the rest kept.
        assertEquals(
            setOf("io.element.android.x"),
            appPermissionsOf(
                policy(
                    """[{"package_name": "not a package", "camera": "deny"}, {"package_name": "com.whatsapp", "camera": "allow"},
                       {"package_name": "io.element.android.x", "camera": "deny", "microphone": "deny"}, 7, null]""",
                ).appPermissions,
            ),
        )
        assertEquals(emptySet<String>(), appPermissionsOf(policy("[]").appPermissions))
        assertNull(appPermissionsOf(policy("null").appPermissions))
        assertNull(appPermissionsOf(policy("{}").appPermissions))
        assertNull(appPermissionsOf(ServerJson.decodeFromString(PolicyResponse.serializer(), "{}").appPermissions))
        assertNull(appPermissionsOf(LastEnforcedPlan.of(policy("[]")).toPolicy().appPermissions))
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
    fun `the parent-PIN events capability is the string the server looks for (qa-24-code Low 6)`() {
        assertEquals("parent_pin_events_v1", PARENT_PIN_EVENTS_CAPABILITY)
        assertTrue(PARENT_PIN_EVENTS_CAPABILITY in STATUS_CAPABILITIES)
    }

    @Test
    fun `parent PIN events use the server's keys - design 24's literal`() {
        val batch = ParentPinEventBatch(
            listOf(ParentPinEvent("0123456789abcdef0123456789abcdef", "unlocked", "override", 1_760_000_000_000, 1_760_007_200_000, null, null)),
        )
        val literal = """{ "events": [ { "id": "0123456789abcdef0123456789abcdef", "kind": "unlocked", "entry": "override",
                "at_ms": 1760000000000, "until_ms": 1760007200000, "failures": null, "level": null } ] }"""
        assertEquals(
            ServerJson.parseToJsonElement(literal),
            ServerJson.parseToJsonElement(ServerJson.encodeToString(ParentPinEventBatch.serializer(), batch)),
        )
    }

    @Test
    fun `relock is reported under the server's keys - design 24b's literal`() {
        val relock = com.kidslauncher.mdm.server.dto.RelockReport(
            atMs = 1_760_100_000_000, trigger = "timer", windows = listOf("override"),
            proxiesCleared = 1, proxyLeft = false, caCertsRemoved = 0,
            credentialRemoved = false, credentialLeft = false,
            blocked = listOf("com.example.app"),
            blockedSigners = mapOf("com.example.app" to "3f2a" + "0".repeat(56) + "c0de"),
            accessibilityEnabled = emptyList(), adminsEnabled = emptyList(),
            appsDisabled = emptyList(), notificationListeners = emptyList(), ownNotificationsOff = false,
            failures = emptyList(),
        )
        val literal = """{ "at_ms": 1760100000000, "trigger": "timer", "windows": ["override"],
              "proxies_cleared": 1, "proxy_left": false, "ca_certs_removed": 0,
              "credential_removed": false, "credential_left": false,
              "blocked": ["com.example.app"],
              "blocked_signers": {"com.example.app": "3f2a00000000000000000000000000000000000000000000000000000000c0de"},
              "accessibility_enabled": [], "admins_enabled": [],
              "apps_disabled": [], "notification_listeners": [], "own_notifications_off": false,
              "failures": [] }"""
        val report = StatusReportRequest(lockReason = "NONE", kioskEngaged = true, relock = relock)
        val json = ServerJson.parseToJsonElement(ServerJson.encodeToString(StatusReportRequest.serializer(), report)).jsonObject
        assertEquals(ServerJson.parseToJsonElement(literal), json["relock"])
        // Before the first relock: absent, like an older launcher's report.
        val none = ServerJson.parseToJsonElement(
            ServerJson.encodeToString(StatusReportRequest.serializer(), StatusReportRequest(lockReason = "NONE", kioskEngaged = true)),
        ).jsonObject
        assertTrue("relock" !in none)
        assertEquals("window_relock_v1", WINDOW_RELOCK_CAPABILITY)
        assertTrue(WINDOW_RELOCK_CAPABILITY in STATUS_CAPABILITIES)
    }

    @Test
    fun `a cache with a redacted override_pin_hash keeps the parent PIN configured (24b S2)`() {
        val cached = decodeCached(serverResponse.replace(Regex("\"override_pin_hash\": \"[0-9a-f]+\""), "\"override_pin_hash\": \"\""))
        assertTrue(cached is CachedPolicy.Ok)
        val policy = (cached as CachedPolicy.Ok).policy
        assertEquals("", policy.overridePinHash)
        assertEquals("a3c1f0e2d4b6a8c0e2f4a6b8c0d2e4f6", policy.overridePinSalt)
        // The cache round trip keeps it redacted, and the vault keeps its tag for it.
        val encoded = ServerJson.encodeToString(PolicyResponse.serializer(), policy)
        assertTrue(encoded.contains("\"override_pin_hash\":\"\""))
        assertEquals(policy, (decodeCached(encoded) as CachedPolicy.Ok).policy)
    }

    @Test
    fun `unseen_boots is reported under the server's keys (24b S3)`() {
        val report = StatusReportRequest(lockReason = "NONE", kioskEngaged = true, unseenBoots = com.kidslauncher.mdm.server.dto.UnseenBootsReport(1, 1_760_100_000_000))
        val json = ServerJson.parseToJsonElement(ServerJson.encodeToString(StatusReportRequest.serializer(), report)).jsonObject
        assertEquals(ServerJson.parseToJsonElement("""{"count": 1, "last_at_ms": 1760100000000}"""), json["unseen_boots"])
        // A first boot of the witness's build: 0 and no time, both keys sent.
        val first = StatusReportRequest(lockReason = "NONE", kioskEngaged = true, unseenBoots = com.kidslauncher.mdm.server.dto.UnseenBootsReport(0, null))
        val firstJson = ServerJson.parseToJsonElement(ServerJson.encodeToString(StatusReportRequest.serializer(), first)).jsonObject
        assertEquals(ServerJson.parseToJsonElement("""{"count": 0, "last_at_ms": null}"""), firstJson["unseen_boots"])
        val none = ServerJson.parseToJsonElement(
            ServerJson.encodeToString(StatusReportRequest.serializer(), StatusReportRequest(lockReason = "NONE", kioskEngaged = true)),
        ).jsonObject
        assertTrue("unseen_boots" !in none)
    }

    @Test
    fun `relock_blocks is reported under the server's keys, every key sent (fix 2026-10-12)`() {
        val key = "3f2a" + "0".repeat(56) + "c0de"
        val report = StatusReportRequest(
            lockReason = "NONE",
            kioskEngaged = true,
            relockBlocks = listOf(
                com.kidslauncher.mdm.server.dto.RelockBlockReport("me.vibb.music.debug", key, "outside_play", "install", 1_760_100_000_000),
                com.kidslauncher.mdm.server.dto.RelockBlockReport("org.example.old", null, "unknown", "relock", null),
            ),
        )
        val json = ServerJson.parseToJsonElement(ServerJson.encodeToString(StatusReportRequest.serializer(), report)).jsonObject
        assertEquals(
            ServerJson.parseToJsonElement(
                """[{"package_name": "me.vibb.music.debug", "signer": "3f2a00000000000000000000000000000000000000000000000000000000c0de",
                     "reason": "outside_play", "found": "install", "at_ms": 1760100000000},
                    {"package_name": "org.example.old", "signer": null, "reason": "unknown", "found": "relock", "at_ms": null}]""",
            ),
            json["relock_blocks"],
        )
        // How many in all (QA of cmd-expiry, Low 2): `relock_blocks_total`, left out unknown.
        val counted = StatusReportRequest(lockReason = "NONE", kioskEngaged = true, relockBlocks = emptyList(), relockBlocksTotal = 18)
        assertEquals(
            ServerJson.parseToJsonElement("18"),
            ServerJson.parseToJsonElement(ServerJson.encodeToString(StatusReportRequest.serializer(), counted)).jsonObject["relock_blocks_total"],
        )
        assertTrue("relock_blocks_total" !in json)
        // None: an empty list, sent; not device owner: left out.
        val empty = StatusReportRequest(lockReason = "NONE", kioskEngaged = true, relockBlocks = emptyList())
        assertEquals(ServerJson.parseToJsonElement("[]"), ServerJson.parseToJsonElement(ServerJson.encodeToString(StatusReportRequest.serializer(), empty)).jsonObject["relock_blocks"])
        val none = ServerJson.parseToJsonElement(
            ServerJson.encodeToString(StatusReportRequest.serializer(), StatusReportRequest(lockReason = "NONE", kioskEngaged = true)),
        ).jsonObject
        assertTrue("relock_blocks" !in none)
    }

    @Test
    fun `foreign_access is reported under the server's keys, left out when nothing is found (24b fix round)`() {
        val report = StatusReportRequest(
            lockReason = "NONE",
            kioskEngaged = true,
            foreignAccess = com.kidslauncher.mdm.server.dto.ForeignAccessReport(listOf("com.example.admin"), emptyList()),
        )
        val json = ServerJson.parseToJsonElement(ServerJson.encodeToString(StatusReportRequest.serializer(), report)).jsonObject
        assertEquals(ServerJson.parseToJsonElement("""{"admins": ["com.example.admin"], "accessibility": []}"""), json["foreign_access"])
        val none = ServerJson.parseToJsonElement(
            ServerJson.encodeToString(StatusReportRequest.serializer(), StatusReportRequest(lockReason = "NONE", kioskEngaged = true)),
        ).jsonObject
        assertTrue("foreign_access" !in none)
    }

    @Test
    fun `the relock commands decode, and their answers`() {
        for (command in listOf("end_windows", "allow_blocked", "start_pause")) {
            val policy = ServerJson.decodeFromString(PolicyResponse.serializer(), """{"pending_command": {"id": 7, "command": "$command"}}""")
            assertEquals(command, policy.pendingCommand?.command)
            assertEquals(7L, policy.pendingCommand?.id)
        }
        // "Allow these apps" carries the apps the PWA listed (QA of cmd-expiry, Low 1); the server's
        // an_install_time_block_is_listed_allowed_and_cleared pins this shape (its first entry).
        val key = "d3b0" + "0".repeat(56) + "6a6e"
        val allow = ServerJson.decodeFromString(
            PolicyResponse.serializer(),
            """{"pending_command": {"id": 8, "command": "allow_blocked", "requested_at": "2026-10-12 10:00:00",
                "allow": [{"package_name": "me.vibb.music.debug", "signer": "$key"}, {"package_name": "org.example.old", "signer": null}]}}""",
        )
        assertEquals(
            listOf(
                com.kidslauncher.mdm.server.dto.AllowedBlock("me.vibb.music.debug", key),
                com.kidslauncher.mdm.server.dto.AllowedBlock("org.example.old", null),
            ),
            allow.pendingCommand?.allow,
        )
        assertEquals(null, ServerJson.decodeFromString(PolicyResponse.serializer(), """{"pending_command": {"id": 7, "command": "ring"}}""").pendingCommand?.allow)
        // Install Now (design 37): the release tag the row showed; left out for other commands.
        val install = ServerJson.decodeFromString(
            PolicyResponse.serializer(),
            """{"pending_command": {"id": 9, "command": "install_update", "requested_at": "2026-10-19 12:00:00", "release_tag": "launcher-v1.4.0@77"}}""",
        ).pendingCommand
        assertEquals("install_update", install?.command)
        assertEquals("launcher-v1.4.0@77", install?.releaseTag)
        assertEquals(null, ServerJson.decodeFromString(PolicyResponse.serializer(), """{"pending_command": {"id": 7, "command": "install_update"}}""").pendingCommand?.releaseTag)
        assertEquals("ended: pause, install mode", endWindowsResult(setOf(WindowKind.PAUSE), installMode = true))
        assertEquals("ended: override, pause", endWindowsResult(setOf(WindowKind.PAUSE, WindowKind.OVERRIDE), installMode = false))
        assertEquals("nothing was open", endWindowsResult(emptySet(), installMode = false))
    }

    @Test
    fun `battery_optimization_exempt is reported under the server's key and left out when unknown (19b)`() {
        for (exempt in listOf(true, false)) {
            val report = StatusReportRequest(lockReason = "NONE", kioskEngaged = true, batteryOptimizationExempt = exempt)
            val json = ServerJson.parseToJsonElement(ServerJson.encodeToString(StatusReportRequest.serializer(), report)).jsonObject
            assertEquals("$exempt", json["battery_optimization_exempt"].toString())
        }
        // Unreadable: absent, like a report from an older launcher (the server stores NULL).
        val unknown = StatusReportRequest(lockReason = "NONE", kioskEngaged = true)
        val unknownJson = ServerJson.parseToJsonElement(ServerJson.encodeToString(StatusReportRequest.serializer(), unknown)).jsonObject
        assertTrue("battery_optimization_exempt" !in unknownJson)
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

    /** Design 34 P12: `launcher_ui.clock_format` - today's values, missing (an older server), `null`
     * or an unknown string: the policy always decodes, the cache round trip keeps it, and the
     * launcher reads every unknown as "system". `clock_format_applied` goes out under the server's
     * key, and is left out when null ("Follow the phone"). */
    @Test
    fun `launcher_ui clock_format is read leniently and clock_format_applied uses the server's key`() {
        fun withClock(clock: String?) = serverResponse.replaceFirst(
            "{",
            """{"launcher_ui": {"language": "nb", "home_columns": 3, "wallpapers": []""" +
                (clock?.let { """, "clock_format": $it""" } ?: "") + "},",
        )
        for ((json, choice) in listOf(
            "\"24\"" to "24", "\"12\"" to "12", "\"system\"" to CLOCK_FORMAT_SYSTEM,
            null to CLOCK_FORMAT_SYSTEM, "null" to CLOCK_FORMAT_SYSTEM, "\"follow\"" to CLOCK_FORMAT_SYSTEM,
        )) {
            val cached = decodeCached(withClock(json))
            assertTrue(json.toString(), cached is CachedPolicy.Ok)
            assertTrue(json.toString(), decodeFresh(withClock(json)) is FreshDecode.Ok)
            val policy = (cached as CachedPolicy.Ok).policy
            assertEquals(json.toString(), choice, clockFormatChoice(policy.launcherUi!!.clockFormat))
            assertEquals(CachedPolicy.Ok(policy), decodeCached(ServerJson.encodeToString(PolicyResponse.serializer(), policy)))
        }
        for (applied in listOf(true, false)) {
            val report = StatusReportRequest(lockReason = "NONE", kioskEngaged = true, clockFormatApplied = applied)
            val json = ServerJson.parseToJsonElement(ServerJson.encodeToString(StatusReportRequest.serializer(), report)).jsonObject
            assertEquals(applied.toString(), json["clock_format_applied"].toString())
        }
        val follow = ServerJson.parseToJsonElement(
            ServerJson.encodeToString(StatusReportRequest.serializer(), StatusReportRequest(lockReason = "NONE", kioskEngaged = true)),
        ).jsonObject
        assertTrue("clock_format_applied" !in follow)
    }

    /** Launcher round 7 §3: `launcher_ui.lock_wake_on_notification` (the server's
     * `policy_json_keys_snapshot` pins it, always sent); missing (an older server) = off, and the
     * cache round trip keeps it. */
    @Test
    fun `launcher_ui lock_wake_on_notification decodes, missing is off`() {
        fun withWake(wake: String?) = serverResponse.replaceFirst(
            "{",
            """{"launcher_ui": {"language": "nb", "home_columns": 3, "wallpapers": []""" +
                (wake?.let { """, "lock_wake_on_notification": $it""" } ?: "") + "},",
        )
        for ((json, on) in listOf("true" to true, "false" to false, null to false)) {
            val cached = decodeCached(withWake(json))
            assertTrue(json.toString(), cached is CachedPolicy.Ok)
            val policy = (cached as CachedPolicy.Ok).policy
            assertEquals(json.toString(), on, policy.launcherUi!!.lockWakeOnNotification)
            assertEquals(FreshDecode.Ok(policy), decodeFresh(withWake(json)))
            assertEquals(CachedPolicy.Ok(policy), decodeCached(ServerJson.encodeToString(PolicyResponse.serializer(), policy)))
        }
        assertFalse(LauncherUi().lockWakeOnNotification)
    }

    /** Design 21 (step 1): the server's `music` object - today's shape, `null` (the server couldn't
     * read the library), missing (an older server), or anything else: the policy always decodes,
     * the cache round trip keeps it; `spotify` (design 26c §6.2) is read on its own. */
    @Test
    fun `music never fails the policy and is carried as is (21)`() {
        fun withMusic(music: String) = serverResponse.replaceFirst("{", "{\"music\": $music,")
        val today = withMusic(
            """{"library_version": "0123456789abcdef", "mobile_data": false, "stream_mobile_data": true, "download_mobile_data": false, """ +
                """"volume_cap_pct": null, "storage_limit_gb": 2, "storytel_generation": 0, "spotify": true}""",
        )
        val policy = (decodeCached(today) as CachedPolicy.Ok).policy
        val music = policy.music!!.jsonObject
        assertEquals("0123456789abcdef", music["library_version"]!!.jsonPrimitive.content)
        assertEquals(
            setOf(
                "library_version", "mobile_data", "stream_mobile_data", "download_mobile_data", "volume_cap_pct", "storage_limit_gb",
                "storytel_generation", "spotify",
            ),
            music.keys,
        )
        // Design 30: the music sync reads it field by field.
        assertEquals(
            com.kidslauncher.mdm.music.MusicPolicy("0123456789abcdef", true, false, null, 2, 0),
            com.kidslauncher.mdm.music.musicPolicy(policy.music),
        )
        // Design 26c §6.2: `spotify` (this phone plays Spotify) is read on its own, an older server's
        // music object without it is false.
        assertTrue(com.kidslauncher.mdm.music.musicSpotify(policy.music))
        assertFalse(com.kidslauncher.mdm.music.musicSpotify((decodeCached(withMusic("""{"library_version": null, "mobile_data": false, "volume_cap_pct": null, "storytel_generation": 0}""")) as CachedPolicy.Ok).policy.music))
        assertEquals(FreshDecode.Ok(policy), decodeFresh(today))
        assertEquals(CachedPolicy.Ok(policy), decodeCached(ServerJson.encodeToString(PolicyResponse.serializer(), policy)))
        for (odd in listOf(
            "null", "{}", "5", "\"x\"", "[]",
            """{"library_version": 5, "mobile_data": "yes", "volume_cap_pct": "60", "storytel_generation": null}""",
            """{"library_version": null, "mobile_data": false, "volume_cap_pct": null, "storytel_generation": 0, "spotify": "yes"}""",
            """{"library_version": null, "mobile_data": false, "stream_mobile_data": "yes", "storage_limit_gb": "2", "storytel_generation": 0}""",
        )) {
            assertTrue(odd, decodeCached(withMusic(odd)) is CachedPolicy.Ok)
            assertTrue(odd, decodeFresh(withMusic(odd)) is FreshDecode.Ok)
        }
        // An older server: no key, nothing to carry. The rest of the policy is the same either way.
        assertNull((decodeCached(serverResponse) as CachedPolicy.Ok).policy.music)
        assertEquals((decodeCached(serverResponse) as CachedPolicy.Ok).policy, policy.copy(music = null))
    }

    /** Design 25: `lock_glance` - the server's literal (per app since design 51), a missing key,
     * `null`, an array, a bad `apps` entry: the policy always decodes and the cache round trip keeps it. A missing key (or `null`)
     * means the server's own default in a fresh response, and unknown (missed calls only) in the
     * cache - a cache an older build wrote never has the key (QA Low 11). */
    @Test
    fun `lock_glance never fails the policy, and missing means default fresh, unknown cached (25)`() {
        fun withGlance(glance: String) = serverResponse.replaceFirst("{", "{\"lock_glance\": $glance,")
        // The server's literal for a fresh device (policy_json_keys_snapshot).
        val today = withGlance("""{"apps": [{"package": "io.element.android.x", "show_text": false}]}""")
        val policy = (decodeCached(today) as CachedPolicy.Ok).policy
        assertEquals(setOf("apps"), policy.lockGlance!!.jsonObject.keys)
        val expected = com.kidslauncher.mdm.badges.GlancePolicy(mapOf("io.element.android.x" to false))
        assertEquals(expected, com.kidslauncher.mdm.badges.lockGlancePolicy(policy.lockGlance, fresh = false))
        assertEquals(expected, com.kidslauncher.mdm.badges.lockGlancePolicy(policy.lockGlance, fresh = true))
        assertEquals(FreshDecode.Ok(policy), decodeFresh(today))
        assertEquals(CachedPolicy.Ok(policy), decodeCached(ServerJson.encodeToString(PolicyResponse.serializer(), policy)))
        val withText = (decodeCached(withGlance("""{"apps": [{"package": "org.example.chat", "show_text": true}, {"package": "io.element.android.x", "show_text": false}]}""")) as CachedPolicy.Ok).policy
        assertEquals(
            com.kidslauncher.mdm.badges.GlancePolicy(mapOf("io.element.android.x" to false, "org.example.chat" to true)),
            com.kidslauncher.mdm.badges.lockGlancePolicy(withText.lockGlance, fresh = true),
        )
        // Nothing in it fails the policy; what can't be used is dropped.
        for (odd in listOf(
            "null", "[]", "5", "\"x\"", "{}", """{"apps": null, "show_text": null}""", """{"apps": "io.element.android.x"}""",
            """{"apps": [5, null, "not a package", "io.element.android.x", {"a": 1}], "show_text": "true"}""",
        )) {
            assertTrue(odd, decodeCached(withGlance(odd)) is CachedPolicy.Ok)
            assertTrue(odd, decodeFresh(withGlance(odd)) is FreshDecode.Ok)
        }
        val bad = (decodeFresh(withGlance("""{"apps": [5, null, "not a package", "io.element.android.x"], "show_text": "true"}""")) as FreshDecode.Ok).policy
        assertEquals(expected, com.kidslauncher.mdm.badges.lockGlancePolicy(bad.lockGlance, fresh = true))
        val array = (decodeFresh(withGlance("""["io.element.android.x"]""")) as FreshDecode.Ok).policy
        assertEquals(com.kidslauncher.mdm.badges.GlancePolicy.UNKNOWN, com.kidslauncher.mdm.badges.lockGlancePolicy(array.lockGlance, fresh = true))
        // An older server: no key. Fresh = its default (Element X, sender only); cached = unknown.
        // The status report's capability the server's notes look for.
        assertEquals("lock_glance_v1", com.kidslauncher.mdm.lock.LOCK_GLANCE_CAPABILITY)
        assertTrue(com.kidslauncher.mdm.lock.LOCK_GLANCE_CAPABILITY in STATUS_CAPABILITIES)
        val older = (decodeFresh(serverResponse) as FreshDecode.Ok).policy
        assertNull(older.lockGlance)
        assertEquals(com.kidslauncher.mdm.badges.GlancePolicy.SERVER_DEFAULT, com.kidslauncher.mdm.badges.lockGlancePolicy(older.lockGlance, fresh = true))
        assertEquals(mapOf("io.element.android.x" to false), com.kidslauncher.mdm.badges.GlancePolicy.SERVER_DEFAULT.apps)
        assertEquals(com.kidslauncher.mdm.badges.GlancePolicy.UNKNOWN, com.kidslauncher.mdm.badges.lockGlancePolicy(older.lockGlance, fresh = false))
        val explicitNull = (decodeFresh(withGlance("null")) as FreshDecode.Ok).policy
        assertEquals(com.kidslauncher.mdm.badges.GlancePolicy.SERVER_DEFAULT, com.kidslauncher.mdm.badges.lockGlancePolicy(explicitNull.lockGlance, fresh = true))
        assertTrue(com.kidslauncher.mdm.badges.GlancePolicy.UNKNOWN.apps.isEmpty())
        // The rest of the policy is the same either way, and the fallback never carries it.
        assertEquals(older, policy.copy(lockGlance = null))
        assertNull(LastEnforcedPlan.of(policy).toPolicy().lockGlance)
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
        assertNull(old.signerSha256)
        // Security review M10: the pinned signing key (lower-case hex), null when not pinned.
        val pinned = ServerJson.decodeFromString(
            com.kidslauncher.mdm.server.dto.TrackedAppUpdate.serializer(),
            """{"id":4,"name":"Fixture","package_name":"me.vibb.testfixture","release_tag":"1.0","download_url":"/x","is_launcher":false,"sha256":"ab","signer_sha256":"0796f1809e476a2ce724f1341bcb32d03185b7a202c3469787c5775d65ef4be3"}""",
        )
        assertEquals("0796f1809e476a2ce724f1341bcb32d03185b7a202c3469787c5775d65ef4be3", pinned.signerSha256)
        assertEquals("me.vibb.testfixture", pinned.packageName)
        assertNull(listed.signerSha256)
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

    /** Design r10 §3.2: the cache holds the kid PIN redacted (`""`, not encoded); the policy passed on keeps it. */
    @Test
    fun `the cached policy holds no kid PIN hash or salt (r10)`() {
        val hash = "ab".repeat(32)
        val withLock = serverResponse.replaceFirst("{", """{"kid_lock":{"pin_hash":"$hash","pin_salt":"cdcd","pin_length":6,"pin_rev":7},""")
        val policy = (decodeFresh(withLock) as FreshDecode.Ok).policy
        val cached = ServerJson.encodeToString(PolicyResponse.serializer(), policyToCache(policy))
        assertFalse(cached, hash in cached || "cdcd" in cached)
        val back = (decodeCached(cached) as CachedPolicy.Ok).policy.kidLock!!
        assertEquals(KidLock("", "", 6, pinRev = 7), back)
        assertEquals(hash, policy.kidLock?.pinHash)
        assertTrue(policyToCache(policy.copy(kidLock = null)).kidLock == null)
    }

    /**
     * Design 59 §1: `kid_lock.keyguard`/`pin_rev` decode (an older server's missing keys are Vibb One's
     * lock and rev 0), and `set_kid_pin`'s `pin` decodes but never reaches the cache, a `toString` or
     * the policy applied after the dispatch.
     */
    @Test
    fun `kid_lock's keyguard and pin_rev decode, and set_kid_pin's pin stays out of the cache (59)`() {
        val withLock = serverResponse.replaceFirst(
            "{",
            """{"kid_lock":{"pin_hash":"ab","pin_salt":"cd","pin_length":4,"keyguard":"android","pin_rev":7},""",
        )
        assertEquals(
            KidLock("ab", "cd", 4, keyguard = "android", pinRev = 7),
            (decodeFresh(withLock) as FreshDecode.Ok).policy.kidLock,
        )
        val older = serverResponse.replaceFirst("{", """{"kid_lock":{"pin_hash":"ab","pin_salt":"cd","pin_length":4},""")
        val olderLock = (decodeFresh(older) as FreshDecode.Ok).policy.kidLock!!
        assertEquals(null to 0L, olderLock.keyguard to olderLock.pinRev)

        val withPin = serverResponse.replace(
            """"pending_command": {"id": 12, "command": "ring", "requested_at": "2026-10-11 18:00:00"}""",
            """"pending_command": {"id": 13, "command": "set_kid_pin", "requested_at": "2026-10-22 14:02:00", "pin": "4821"}""",
        )
        val policy = (decodeFresh(withPin) as FreshDecode.Ok).policy
        assertEquals("4821", policy.pendingCommand?.pin)
        assertFalse(policy.toString(), "4821" in policy.toString())
        assertTrue(policy.pendingCommand.toString().contains("pin=<redacted>"))
        val cached = ServerJson.encodeToString(PolicyResponse.serializer(), cacheablePolicy(policy))
        assertFalse(cached, "4821" in cached || "\"pin\"" in cached)
        val back = (decodeCached(cached) as CachedPolicy.Ok).policy
        assertEquals(policy.copy(pendingCommand = policy.pendingCommand?.copy(pin = null)), back)
        // Without a PIN the policy is passed through as it is.
        val plain = (decodeFresh(serverResponse) as FreshDecode.Ok).policy
        assertTrue(cacheablePolicy(plain) === plain)
        // The sync caches through it, and applies the stripped policy once the command ran.
        val src = listOf("src", "app/src").map(::File).first { File(it, "main/java").isDirectory }
        val worker = File(src, "main/java/com/kidslauncher/mdm/server/MdmSyncWorker.kt").readText()
        val store = worker.substringAfter("private fun storeAcceptedPolicy(").substringBefore("\n}\n")
        assertTrue(store, "ServerJson.encodeToString(PolicyResponse.serializer(), policyToCache(policy))" in store)
        val afterDispatch = worker.substringAfter("ranCommand = dispatchPendingCommand(").substringBefore("val decision = choosePolicy(")
        assertTrue("freshPolicy = cacheablePolicy(freshPolicy)" in afterDispatch)
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
        // The next accepted policy is cached without it (storeAcceptedPolicy re-encodes the DTO).
        assertFalse("push" in ServerJson.encodeToJsonElement(PolicyResponse.serializer(), policy).jsonObject)
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
                state = "managed", dialerRoleHeld = true, redirectionRoleHeld = false, screeningRoleHeld = true,
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
                "state", "dialer_role_held", "redirection_role_held", "screening_role_held", "default_dialer", "system_dialer",
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
            ], "lock_wallpaper": 7},
            "packages_to_uninstall"
            """.trimIndent()
        )
        val walls = (decodeCached(withWallpapers) as CachedPolicy.Ok).policy.launcherUi!!.wallpapers
        assertEquals(2, walls.size)
        assertEquals("navy", walls[0].builtinKey)
        assertEquals(photo, walls[1].image)
        assertTrue(walls[1].lockScreen)
        assertEquals(emptyList<Any>(), policy.launcherUi!!.wallpapers)
        // Design 25, "Lock background": the id, or null/missing (an older server) = Home's.
        assertEquals(7L, (decodeCached(withWallpapers) as CachedPolicy.Ok).policy.launcherUi!!.lockWallpaper)
        assertNull(policy.launcherUi!!.lockWallpaper)
        assertNull((decodeCached(withWallpapers.replace("\"lock_wallpaper\": 7", "\"lock_wallpaper\": null")) as CachedPolicy.Ok).policy.launcherUi!!.lockWallpaper)

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
                voipRang = emptyList(),
                keyguard = "own", pinRev = null, resetToken = false, keyguardRelocks = 0,
            ),
        )
        val json = ServerJson.parseToJsonElement(ServerJson.encodeToString(StatusReportRequest.serializer(), report)).jsonObject
        assertEquals(
            setOf(
                "active", "inactive", "locked", "failures", "backoff_until_ms", "exempt_yields", "voip_fsi_denied", "voip_rang",
                "keyguard", "pin_rev", "reset_token", "keyguard_relocks",
            ),
            json["lock_state"]!!.jsonObject.keys,
        )
        assertEquals("[]", json["lock_state"]!!.jsonObject["voip_fsi_denied"].toString())
        assertEquals("[]", json["lock_state"]!!.jsonObject["voip_rang"].toString())
        // Design 59 §1: the gate is always sent, the own word and an unknown rev included (the server tells an older
        // launcher by the capability, and queues the PIN for a phone reporting its own lock).
        assertEquals("\"own\"", json["lock_state"]!!.jsonObject["keyguard"].toString())
        assertEquals("null", json["lock_state"]!!.jsonObject["pin_rev"].toString())
        // Design r7 §1.1.3: an entry is the server's `{package, last_at_ms}`.
        val rang = com.kidslauncher.mdm.server.dto.LockStateReport(
            active = true, inactive = null, locked = true, failures = 0, backoffUntilMs = null, exemptYields = 0,
            voipFsiDenied = emptyList(),
            voipRang = listOf(com.kidslauncher.mdm.server.dto.VoipRang("org.thoughtcrime.securesms", 1_800_000_000_000L)),
            keyguard = "android", pinRev = 7, resetToken = true, keyguardRelocks = 2,
        )
        assertEquals(
            """{"keyguard":"android","pin_rev":7,"reset_token":true,"keyguard_relocks":2}""",
            ServerJson.parseToJsonElement(ServerJson.encodeToString(com.kidslauncher.mdm.server.dto.LockStateReport.serializer(), rang))
                .jsonObject.filterKeys { it in setOf("keyguard", "pin_rev", "reset_token", "keyguard_relocks") }.let {
                    kotlinx.serialization.json.JsonObject(it).toString()
                },
        )
        assertEquals(
            """[{"package":"org.thoughtcrime.securesms","last_at_ms":1800000000000}]""",
            ServerJson.parseToJsonElement(ServerJson.encodeToString(com.kidslauncher.mdm.server.dto.LockStateReport.serializer(), rang))
                .jsonObject["voip_rang"].toString(),
        )
        val failed = CallState(
            state = "managed", dialerRoleHeld = true, redirectionRoleHeld = true, screeningRoleHeld = false, defaultDialer = null, systemDialer = null,
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

    /** Design 64 step 2: `location_policy.zones` as the server's `policy_json_keys_snapshot` and
     * `zone_watch::the_policy_serves_the_zones_and_the_floor` pin it - `{id, latitude, longitude,
     * radius_m}`, never a name -, `[]`, and an older server's `location_policy` without the key
     * (no zones), all surviving the cache's round trip. */
    @Test
    fun `location_policy zones decode, and a missing key is no zones`() {
        fun withLocation(location: String) = serverResponse.replace(
            "\"packages_to_uninstall\"",
            "\"location_policy\": $location,\n\"packages_to_uninstall\"",
        )
        val withZones = withLocation(
            """{"mode": "interval", "interval_minutes": 30, "zones": [
                 {"id": 7, "latitude": 59.9139, "longitude": 10.7522, "radius_m": 150},
                 {"id": 9, "latitude": -33.8688, "longitude": 151.2093, "radius_m": 2000}]}""",
        )
        val policy = (decodeCached(withZones) as CachedPolicy.Ok).policy
        assertEquals(
            listOf(
                com.kidslauncher.mdm.server.dto.PolicyZone(7, 59.9139, 10.7522, 150),
                com.kidslauncher.mdm.server.dto.PolicyZone(9, -33.8688, 151.2093, 2000),
            ),
            policy.locationPolicy!!.zones,
        )
        val reencoded = ServerJson.encodeToString(PolicyResponse.serializer(), policy)
        assertEquals(CachedPolicy.Ok(policy), decodeCached(reencoded))
        val empty = (decodeCached(withLocation("""{"mode": "on_request", "interval_minutes": 30, "zones": []}""")) as CachedPolicy.Ok).policy
        assertEquals(emptyList<Any>(), empty.locationPolicy!!.zones)
        val older = (decodeCached(withLocation("""{"mode": "interval", "interval_minutes": 10}""")) as CachedPolicy.Ok).policy
        assertEquals(com.kidslauncher.mdm.server.dto.LocationPolicy("interval", 10), older.locationPolicy)
        assertEquals(emptyList<Any>(), older.locationPolicy!!.zones)
    }

    /** Design 36 §4.1: the server's `dns_upstream` (its `custom_server_in_the_policy` shapes) and anything
     * else - nothing in it can fail the policy, and only a whole, valid pair is a custom server. */
    @Test
    fun `dns_upstream never fails the policy and only a valid pair is a custom server`() {
        fun upstream(value: String): CustomUpstream? {
            val json = serverResponse.trimEnd('}', '\n', ' ') + ",\n\"dns_upstream\": $value}"
            val fresh = decodeFresh(json)
            assertTrue("$value: $fresh", fresh is FreshDecode.Ok)
            return customUpstreamFrom((fresh as FreshDecode.Ok).policy.dnsUpstream)
        }
        // An older server: no key, the provider word.
        val old = (decodeCached(serverResponse) as CachedPolicy.Ok).policy
        assertNull(old.dnsUpstream)
        assertNull(customUpstreamFrom(old.dnsUpstream))

        assertEquals(
            CustomUpstream(UpstreamTarget.Doh("https://dns.example/dns-query/abc123"), UpstreamTarget.Dot("1.1.1.3", 853), 4),
            upstream("""{"kind": "doh", "url": "https://dns.example/dns-query/abc123", "rev": 4,
                "backup": {"kind": "dot", "host": "1.1.1.3", "port": 853}}"""),
        )
        assertEquals(
            CustomUpstream(UpstreamTarget.Dot("abc123.dns.example", 8853), UpstreamTarget.Doh("https://[2606:4700:4700::1111]/dns-query"), 4),
            upstream("""{"kind": "dot", "host": "abc123.dns.example", "port": 8853, "rev": 4,
                "backup": {"kind": "doh", "url": "https://[2606:4700:4700::1111]/dns-query"}}"""),
        )
        val backup = """"backup": {"kind": "dot", "host": "1.1.1.3", "port": 853}"""
        for (bad in listOf(
            "null",
            "\"https://dns.example\"",
            """{"kind": "doq", "url": "https://dns.example/dns-query", "rev": 1, $backup}""",
            """{"kind": "doh", "url": "http://dns.example/dns-query", "rev": 1, $backup}""",
            """{"kind": "doh", "url": "https://", "rev": 1, $backup}""",
            """{"kind": "dot", "host": "dns.example", "port": "x", "rev": 1, $backup}""",
            """{"kind": "dot", "host": "dns.example", "port": "853", "rev": 1, $backup}""",
            """{"kind": "dot", "host": "dns.example", "port": 0, "rev": 1, $backup}""",
            """{"kind": "dot", "host": "", "port": 853, "rev": 1, $backup}""",
            """{"kind": "dot", "host": "dns.example", "port": 853, "rev": "1", $backup}""",
            """{"kind": "dot", "host": "dns.example", "port": 853, $backup}""",
            """{"kind": "dot", "host": "dns.example", "port": 853, "rev": 1}""",
            """{"kind": "dot", "host": "dns.example", "port": 853, "rev": 1, "backup": null}""",
            """{"kind": "dot", "host": "dns.example", "port": 853, "rev": 1, "backup": {"kind": "dot", "host": "1.1.1.3"}}""",
            """{"kind": 7, "host": "dns.example", "port": 853, "rev": 1, $backup}""",
        )) {
            assertNull(bad, upstream(bad))
        }
        // The targets are secrets: never in a log line through toString.
        val shown = upstream("""{"kind": "doh", "url": "https://abc123.dns.example/dns-query", "rev": 4, $backup}""").toString()
        assertFalse(shown, shown.contains("abc123") || shown.contains("1.1.1.3"))
    }
}
