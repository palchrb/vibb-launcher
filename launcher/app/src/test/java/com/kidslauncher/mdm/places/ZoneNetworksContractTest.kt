package com.kidslauncher.mdm.places

import com.kidslauncher.mdm.server.ServerJson
import com.kidslauncher.mdm.server.dto.PolicyZone
import com.kidslauncher.mdm.server.dto.ZoneNetwork
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.contentOrNull
import org.junit.Assert.assertEquals
import org.junit.Test

/** Design 138: `contracts/core/zone_networks.json` - the policy's zones with their routers as the server serves them,
 * and the router match both sides use (the BSSID's first five octets, or the same multi-link address). The server's
 * `tests::zone_networks` reads the same file. */
class ZoneNetworksContractTest {
    private val contract = ServerJson.parseToJsonElement(javaClass.classLoader!!.getResource("zone_networks.json")!!.readText()).jsonObject
    private val keys = PlaceKeys("test-key-not-a-secret".toByteArray())

    @Test
    fun `the policy's networks decode`() {
        val zones = ServerJson.decodeFromJsonElement(ListSerializer(PolicyZone.serializer()), contract["policy"]!!.jsonObject["zones"]!!)
        assertEquals(
            listOf(ZoneNetwork(7, "Net-A", "00:00:5e:00:53:01", null), ZoneNetwork(9, "Net-B", "00:00:5e:00:54:01", "00:00:5e:00:55:10")),
            zones[0].networks,
        )
        assertEquals(emptyList<ZoneNetwork>(), zones[1].networks)
        assertEquals(listOf(7L, 9L), confirmedAps(zones, keys).map { it.id })
    }

    @Test
    fun `the router match`() {
        for (row in contract["same_router"]!!.jsonArray) {
            val r = row.jsonObject
            fun k(b: String, mld: String?) = keys.device(b, null) to mld?.let { keys.device(b, it) }
            val (a, aMld) = k(r["a"]!!.jsonPrimitive.content, r["a_mld"]!!.jsonPrimitive.contentOrNull)
            val (b, bMld) = k(r["b"]!!.jsonPrimitive.content, r["b_mld"]!!.jsonPrimitive.contentOrNull)
            assertEquals(row.toString(), r["same"]!!.jsonPrimitive.boolean, sameRouter(a, aMld, b, bMld))
        }
    }
}
