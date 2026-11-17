package com.kidslauncher.mdm.push.up

import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/** Design 108 §5.4's delivery plan, from `up_rules.json`'s `delivery` rows: a suspended app gets its messages (Q5). */
class UpDeliveryPlanTest {
    @Test
    fun `every delivery row plans as written`() {
        for (case in UpFixtures.rules["delivery"]!!.jsonArray.map { it.jsonObject }) {
            val state = UpAppState.valueOf(case["app"]!!.jsonPrimitive.content.uppercase())
            val message = UpWireMessage(key = "deadbeef000000000000000000000001", reg = case["reg"]!!.jsonPrimitive.content, body = "AAEC")
            val text = when (val plan = UpDelivery.plan(message, UpFixtures.rows) { state }) {
                is UpPlan.Deliver -> "deliver"
                is UpPlan.Drop -> if (plan.removeRow != null) "drop_remove" else "drop"
            }
            assertEquals(case["case"]!!.jsonPrimitive.content, case["plan"]!!.jsonPrimitive.content, text)
        }
    }
}
