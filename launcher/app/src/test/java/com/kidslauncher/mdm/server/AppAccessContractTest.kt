package com.kidslauncher.mdm.server

import com.kidslauncher.mdm.server.dto.InstalledApp
import com.kidslauncher.mdm.server.dto.PolicyResponse
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Design 127 §3.3: the shared contract `app_access.json`, in `contracts/core/` (the
 * server's `app_permissions::tests::the_contract` saves through the page and posts the report): [AccessKey]'s
 * permissions against `keys`, both policies decoded, `report` encoded exactly from the pure report functions, `never`
 * never a target while signed with our own certificate (another signer is denied),
 * the name alone exempting nothing.
 */
class AppAccessContractTest {
    private val text = javaClass.classLoader!!.getResource("app_access.json")!!.readText()
    private val contract = ServerJson.parseToJsonElement(text).jsonObject

    @Test
    fun `the keys are the servers`() {
        val keys = contract["keys"]!!.jsonArray.map { row ->
            row.jsonObject["key"]!!.jsonPrimitive.content to row.jsonObject["permissions"]!!.jsonArray.map { it.jsonPrimitive.content }
        }
        assertEquals(keys, AccessKey.entries.map { it.key to it.permissions })
    }

    @Test
    fun `both policies decode`() {
        val policy = contract["policy"]!!.jsonObject
        fun decode(name: String) = appPermissionsOf(
            ServerJson.decodeFromString(PolicyResponse.serializer(), """{"app_permissions": ${policy[name]}}""").appPermissions,
        )
        assertEquals(emptyMap<String, Set<AccessKey>>(), decode("none"))
        assertEquals(
            mapOf(
                "io.element.android.x" to setOf(AccessKey.CAMERA, AccessKey.MICROPHONE),
                "org.example.maps" to setOf(AccessKey.LOCATION, AccessKey.CONTACTS),
            ),
            decode("seeded"),
        )
    }

    @Test
    fun `the report encodes exactly`() {
        fun states(vararg denied: String): (String) -> Int = { if (it in denied) GRANT_STATE_DENIED else GRANT_STATE_DEFAULT }
        val camera = "android.permission.CAMERA"
        val mic = "android.permission.RECORD_AUDIO"
        val fine = "android.permission.ACCESS_FINE_LOCATION"
        val coarse = "android.permission.ACCESS_COARSE_LOCATION"
        val read = "android.permission.READ_CONTACTS"
        val write = "android.permission.WRITE_CONTACTS"
        val photosLocation = "android.permission.ACCESS_MEDIA_LOCATION"
        val apps = listOf(
            InstalledApp(
                "io.element.android.x", "Element X", false,
                access = appAccessReport(
                    accessReports(listOf(camera, mic, read, "android.permission.INTERNET"), states(camera), setOf(AccessKey.MICROPHONE)),
                    listOf(SpecialAccess.FULL_SCREEN to AccessState.DENIED),
                ),
            ),
            InstalledApp(
                "org.example.maps", "Maps", false,
                access = appAccessReport(accessReports(listOf(fine, coarse, read, write), states(fine, coarse, read, write), emptySet()), emptyList()),
            ),
            InstalledApp(
                "org.example.notes", "Notes", false,
                access = appAccessReport(
                    accessReports(listOf(camera, fine, mic, read), states(), emptySet()),
                    listOf(SpecialAccess.EXACT_ALARM to AccessState.GRANTED),
                ),
            ),
            // Asks only for the location stored in photos: Posisjon.
            InstalledApp(
                "org.example.gallery", "Gallery", false,
                access = appAccessReport(
                    accessReports(listOf("android.permission.READ_MEDIA_IMAGES", photosLocation), states(photosLocation), emptySet()),
                    emptyList(),
                ),
            ),
        )
        assertEquals(
            contract["report"]!!.jsonObject["installed_apps"].toString(),
            ServerJson.encodeToString(ListSerializer(InstalledApp.serializer()), apps),
        )
    }

    @Test
    fun `never is never a target`() {
        for (pkg in contract["never"]!!.jsonArray.map { it.jsonPrimitive.content }) {
            assertTrue(pkg, pkg in ACCESS_NEVER)
            assertFalse(pkg, accessTarget(pkg, "me.vibb.launcher") { true })
            fun plan(ours: Boolean) = accessGrants(
                mapOf(pkg to listOf("android.permission.CAMERA")), "org.example.other", mapOf(pkg to setOf(AccessKey.CAMERA)),
                windowOpen = false, callLive = false, held = emptySet(), current = emptyMap(),
            ) { ours }
            assertEquals(pkg, emptyMap<Grant, Int>(), plan(ours = true).writes)
            // The name alone exempts nothing: signed by another key, it is denied.
            assertEquals(pkg, mapOf(Grant(pkg, "android.permission.CAMERA") to GRANT_STATE_DENIED), plan(ours = false).writes)
        }
        assertEquals(contract["never"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet(), ACCESS_NEVER)
    }
}
