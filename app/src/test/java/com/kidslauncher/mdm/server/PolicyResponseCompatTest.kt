package com.kidslauncher.mdm.server

import com.kidslauncher.mdm.server.dto.CallPolicy
import com.kidslauncher.mdm.server.dto.CallState
import com.kidslauncher.mdm.server.dto.PolicyContact
import com.kidslauncher.mdm.server.dto.PolicyResponse
import com.kidslauncher.mdm.server.dto.StatusReportRequest
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
            ),
        )
        val json = ServerJson.parseToJsonElement(ServerJson.encodeToString(StatusReportRequest.serializer(), report)).jsonObject
        assertEquals("[\"call_policy_v1\"]", json["capabilities"].toString())
        val callState = json["call_state"]!!.jsonObject
        assertEquals(
            setOf(
                "state", "dialer_role_held", "redirection_role_held", "default_dialer", "system_dialer",
                "sms_restricted", "outgoing_restricted", "default_sms_package", "last_error",
                "last_emergency_call_at", "callback_window_until",
            ),
            callState.keys,
        )
    }

    /** Documents the missing `coerceInputValues`: one null in a non-nullable field fails the whole
     * decode, which is why the server's snapshot test forbids it. */
    @Test
    fun `null in a non-nullable field is Corrupt`() {
        val withNull = serverResponse.replace("\"kiosk_desired\": true", "\"kiosk_desired\": null")
        assertTrue(decodeCached(withNull) is CachedPolicy.Corrupt)
        assertTrue(decodeFresh(withNull) is FreshDecode.Failed)
    }

    @Test
    fun `cache round trip keeps the policy`() {
        val policy = (decodeCached(serverResponse) as CachedPolicy.Ok).policy
        val reencoded = ServerJson.encodeToString(PolicyResponse.serializer(), policy)
        assertEquals(CachedPolicy.Ok(policy), decodeCached(reencoded))
    }
}
