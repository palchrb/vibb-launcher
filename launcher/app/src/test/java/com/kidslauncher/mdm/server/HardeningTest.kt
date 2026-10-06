package com.kidslauncher.mdm.server

import com.kidslauncher.mdm.server.dto.HardeningPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HardeningTest {

    private val serverDefaults = HardeningPolicy(
        disallowFactoryReset = true,
        disallowAddUser = true,
        disallowModifyAccounts = true,
        disallowConfigVpn = true,
        disallowUsbFileTransfer = true,
        disallowDebuggingFeatures = true,
        disallowSafeBoot = false,
        lockLocation = true,
        disallowAirplaneMode = false,
        disallowConfigLocale = true,
    )

    @Test
    fun `defaults are on except safe boot and airplane mode`() {
        val defaults = HardeningRestriction.entries.associateWith {
            it != HardeningRestriction.SAFE_BOOT && it != HardeningRestriction.AIRPLANE_MODE
        }
        assertEquals(defaults, hardeningPlan(null, managed = true).restrictions)
        assertEquals(defaults, hardeningPlan(HardeningPolicy(), managed = true).restrictions)
        assertEquals(defaults, hardeningPlan(serverDefaults, managed = true).restrictions)
        assertTrue(hardeningPlan(null, managed = true).forceLocationOn)
    }

    @Test
    fun `explicit server values win, missing fields take the default`() {
        val plan = hardeningPlan(
            HardeningPolicy(disallowDebuggingFeatures = false, disallowSafeBoot = true, lockLocation = false),
            managed = true,
        )
        assertFalse(plan.isSet(HardeningRestriction.DEBUGGING_FEATURES))
        assertTrue(plan.isSet(HardeningRestriction.SAFE_BOOT))
        assertFalse(plan.isSet(HardeningRestriction.CONFIG_LOCATION))
        assertFalse(plan.forceLocationOn)
        assertTrue(plan.isSet(HardeningRestriction.FACTORY_RESET))
        assertTrue(plan.isSet(HardeningRestriction.CONFIG_VPN))

        val allOff = HardeningPolicy(false, false, false, false, false, false, false, false, false, false)
        // Every server switch off; the launcher-only wallpaper lock has no switch.
        assertEquals(
            setOf(HardeningRestriction.SET_WALLPAPER),
            hardeningPlan(allOff, managed = true).restrictions.filterValues { it }.keys,
        )

        // Airplane mode is only blocked when the parent says so, and like the rest only while managed.
        assertTrue(hardeningPlan(HardeningPolicy(disallowAirplaneMode = true), managed = true).isSet(HardeningRestriction.AIRPLANE_MODE))
        assertFalse(hardeningPlan(HardeningPolicy(disallowAirplaneMode = true), managed = false).isSet(HardeningRestriction.AIRPLANE_MODE))

        // The system language lock (fix round 2026-10-06): on by default, off only from the server.
        assertTrue(hardeningPlan(HardeningPolicy(), managed = true).isSet(HardeningRestriction.CONFIG_LOCALE))
        assertFalse(hardeningPlan(HardeningPolicy(disallowConfigLocale = false), managed = true).isSet(HardeningRestriction.CONFIG_LOCALE))
        assertFalse(hardeningPlan(null, managed = false).isSet(HardeningRestriction.CONFIG_LOCALE))
    }

    @Test
    fun `the wallpaper lock is set while managed and cleared when unmanaged`() {
        for (policy in listOf(null, serverDefaults, HardeningPolicy(false, false, false, false, false, false, false, false, false))) {
            assertTrue(hardeningPlan(policy, managed = true).isSet(HardeningRestriction.SET_WALLPAPER))
            assertFalse(hardeningPlan(policy, managed = false).isSet(HardeningRestriction.SET_WALLPAPER))
        }
    }

    @Test
    fun `unmanaged clears every restriction`() {
        for (policy in listOf(null, serverDefaults, HardeningPolicy(disallowSafeBoot = true))) {
            val plan = hardeningPlan(policy, managed = false)
            assertEquals(HardeningRestriction.entries.toSet(), plan.restrictions.keys)
            assertTrue(plan.restrictions.values.none { it })
            assertFalse(plan.forceLocationOn)
        }
    }

    @Test
    fun `managed means an enforced allowlist or managed calls`() {
        assertTrue(hardeningManaged(emptyList(), callsManaged = false))
        assertTrue(hardeningManaged(listOf("org.example.music"), callsManaged = false))
        assertTrue(hardeningManaged(null, callsManaged = true))
        assertFalse(hardeningManaged(null, callsManaged = false))
    }

    @Test
    fun `the fallback plan keeps the switches, and an old one means defaults`() {
        val policy = NOTHING_ALLOWED_FALLBACK.copy(hardening = HardeningPolicy(disallowDebuggingFeatures = false))
        val plan = LastEnforcedPlan.decode(LastEnforcedPlan.encode(LastEnforcedPlan.of(policy)))!!.toPolicy()
        assertEquals(false, plan.hardening?.disallowDebuggingFeatures)
        assertFalse(hardeningPlan(plan.hardening, hardeningManaged(plan.allowlist, false)).isSet(HardeningRestriction.DEBUGGING_FEATURES))

        // A plan stored by the previous launcher has no hardening: defaults, still managed.
        val old = LastEnforcedPlan.decode("""{"allowlist":["org.example.music"],"kiosk_desired":true}""")!!.toPolicy()
        assertEquals(null, old.hardening)
        assertTrue(hardeningPlan(old.hardening, hardeningManaged(old.allowlist, false)).isSet(HardeningRestriction.DEBUGGING_FEATURES))
    }

    @Test
    fun `the server's hardening object decodes`() {
        val json = """
            {"allowlist": [], "call_policy": {"managed": false}, "hardening": {
              "disallow_factory_reset": true, "disallow_add_user": true, "disallow_modify_accounts": true,
              "disallow_config_vpn": true, "disallow_usb_file_transfer": true,
              "disallow_debugging_features": false, "disallow_safe_boot": false, "lock_location": true,
              "disallow_airplane_mode": false, "disallow_config_locale": true}}
        """.trimIndent()
        val policy = (decodeFresh(json) as FreshDecode.Ok).policy
        assertEquals(serverDefaults.copy(disallowDebuggingFeatures = false), policy.hardening)
        // Round trip through the cache.
        val cached = decodeCached(ServerJson.encodeToString(com.kidslauncher.mdm.server.dto.PolicyResponse.serializer(), policy))
        assertEquals(policy, (cached as CachedPolicy.Ok).policy)
    }
}
