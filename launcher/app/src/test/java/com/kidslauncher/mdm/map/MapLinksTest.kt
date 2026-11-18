package com.kidslauncher.mdm.map

import com.kidslauncher.mdm.server.ServerJson
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Design 110, the launcher's own rules (`map_link_rules.json`): every link form, the plan's first step, the step
 * after each answer, a message's links - and the manifest's filters against the persistent preferred activity's. */
class MapLinksTest {
    private val rules = ServerJson.parseToJsonElement(javaClass.classLoader!!.getResource("map_link_rules.json")!!.readText()).jsonObject

    private fun rows(key: String) = rules[key]!!.jsonArray.map { it.jsonObject }
    private fun JsonObject.text(key: String) = this[key]?.takeIf { it != JsonNull }?.jsonPrimitive?.content

    @Test
    fun `every form reads as its row says`() {
        for (row in rows("forms")) {
            val name = row.text("name")
            val parsed = MapLinkForms.parse(row.text("url")!!)
            assertEquals(name, row.text("kind"), parsed.kind.name)
            for (key in row.keys) {
                val want = row[key]!!
                when (key) {
                    "lat" -> assertEquals(name, (want as? kotlinx.serialization.json.JsonPrimitive)?.takeIf { want != JsonNull }?.double, parsed.lat)
                    "lon" -> assertEquals(name, want.jsonPrimitive.double, parsed.lon!!, 1e-9)
                    "z" -> assertEquals(name, want.jsonPrimitive.int, parsed.z)
                    "label" -> assertEquals(name, row.text("label"), parsed.label)
                    "ftid" -> assertEquals(name, row.text("ftid"), parsed.ftid)
                    "short" -> assertEquals(name, row.text("short"), parsed.short)
                    "query" -> assertEquals(name, row.text("query"), parsed.query)
                    "centre" -> assertEquals(
                        name,
                        if (want == JsonNull) null else want.jsonArray.let { it[0].jsonPrimitive.double to it[1].jsonPrimitive.double },
                        parsed.centre,
                    )
                    "uri" -> assertEquals(name, row.text("uri"), parsed.geoUri())
                }
            }
        }
    }

    private fun stepText(step: MapStep): String = when (step) {
        MapStep.Nothing -> "nothing"
        is MapStep.Open -> "open:${step.uri}"
        is MapStep.Ask -> if (step.request.url != null) "ask_short" else "ask_ftid"
        is MapStep.Note -> "note:${step.note.name}" + if (step.sync) "+sync" else ""
    }

    @Test
    fun `the plan's first step follows the rows`() {
        for (row in rows("plan")) {
            val step = mapLinkPlan(row.text("locked") == "true", TargetState.valueOf(row.text("target")!!), MapLinkForms.parse(row.text("url")!!))
            val text = stepText(step)
            assertEquals(row.text("name"), row.text("step"), if (text.startsWith("open:")) "open" else text)
        }
    }

    @Test
    fun `the step after each answer follows the rows`() {
        for (row in rows("after")) {
            val body = row["body"]!!.takeIf { it != JsonNull }?.let { ServerJson.decodeFromJsonElement(MapResolveAnswer.serializer(), it) }
            val step = mapLinkAfter(row.text("status")!!.toInt(), body, MapLinkForms.parse(row.text("url")!!))
            assertEquals(row.text("name"), row.text("step"), stepText(step))
        }
    }

    @Test
    fun `a message's web links are found, nothing else`() {
        for (row in rows("links")) {
            val text = row.text("text")!!
            val found = MapLinks.webLinks(text).map { text.substring(it.first, it.last + 1) }
            assertEquals(text, row["links"]!!.jsonArray.map { it.jsonPrimitive.content }, found)
        }
    }

    @Test
    fun `labels are cut at a word and the zoom is clamped`() {
        val long = (1..60).joinToString(" ") { "ord$it" }
        val cut = MapLinks.cutLabel(long)
        assertTrue(cut.length <= 120 && long.startsWith(cut) && !cut.endsWith(" "))
        assertEquals(3, MapLinks.zoom(1))
        assertEquals(19, MapLinks.zoom(22))
        assertEquals(17, MapLinks.zoom(null))
        assertEquals("Artbox AS, Drammensveien 133", MapLinks.placeLabel("Artbox AS", "Drammensveien 133"))
        assertEquals(null, MapLinks.placeLabel(" ", null))
    }

    @Test
    fun `the fallback's centre is the place id's coarse cell, offline`() {
        val (lat, lon) = MapLinks.cellCentre("0x46416db4338a09ff:0x7d7d788043d1f40e")!!
        assertEquals(59.94724649042136, lat, 1e-6)
        assertEquals(10.889122576340721, lon, 1e-6)
        assertEquals(null, MapLinks.cellCentre("0x0:0x7d"))
        // Steak no Don's cell centre is about 14 km from it (level 8, ~40 km cells).
        val (sLat, sLon) = MapLinks.cellCentre("0x3551565394d96b57:0x1c9bdbc5990908bf")!!
        assertTrue(Math.abs(sLat - 34.5945) < 0.3 && Math.abs(sLon - 133.7583) < 0.3)
    }

    @Test
    fun `the target is the first allowed app, then suspended, then hidden`() {
        assertEquals(null to TargetState.MISSING, pickTarget(emptyList()))
        assertEquals(
            "app.comaps.fdroid" to TargetState.ALLOWED,
            pickTarget(listOf("app.organicmaps" to TargetState.HIDDEN, "app.comaps.fdroid" to TargetState.ALLOWED)),
        )
        assertEquals("app.organicmaps" to TargetState.HIDDEN, pickTarget(listOf("app.organicmaps" to TargetState.HIDDEN)))
        assertEquals("CoMaps", MapLinksRuntime.targetName("app.comaps"))
        assertEquals("Organic Maps", MapLinksRuntime.targetName(null))
    }

    @Test
    fun `the Settings rows follow the switch and the steps`() {
        val hosts = VibbHosts(selected = 3, selectable = 19, declared = 19)
        val on = mapLinkRows(on = true, holderAllowed = true, hosts = hosts)
        assertTrue(on.shown && on.holder.shown && !on.holder.done && on.vibb.shown && !on.vibb.done && !on.back.shown)
        val done = mapLinkRows(on = true, holderAllowed = false, hosts = VibbHosts(11, 11, 19))
        assertTrue(done.holder.done && done.vibb.done)
        // Off: only the way back, only while the holder's links are off (Q4).
        assertTrue(mapLinkRows(on = false, holderAllowed = false, hosts = hosts).let { it.back.shown && !it.holder.shown && !it.vibb.shown })
        assertTrue(!mapLinkRows(on = false, holderAllowed = true, hosts = hosts).shown)
        assertTrue(!mapLinkRows(on = false, holderAllowed = null, hosts = hosts).shown)
    }

    @Test
    fun `the browser button never loops back to us`() {
        assertTrue(browserOffered(windowOpen = true, browser = "org.example.browser", ownPackage = "me.vibb.launcher"))
        assertTrue(!browserOffered(windowOpen = true, browser = "me.vibb.launcher", ownPackage = "me.vibb.launcher"))
        assertTrue(!browserOffered(windowOpen = true, browser = null, ownPackage = "me.vibb.launcher"))
        assertTrue(!browserOffered(windowOpen = false, browser = "org.example.browser", ownPackage = "me.vibb.launcher"))
    }

    /** §7.1: the manifest's two filters are exactly the PPA's ([MapLinkForms.ANY_PATH_HOSTS], `/maps` on
     * [MapLinkForms.MAPS_PATH_HOSTS]); disabled until the switch; no autoVerify. */
    @Test
    fun `the manifest's filters are the preferred activity's`() {
        val manifest = listOf(File("src/main/AndroidManifest.xml"), File("app/src/main/AndroidManifest.xml")).first { it.exists() }.readText()
        val activity = manifest.substringAfter("android:name=\".map.MapLinkActivity\"").substringBefore("</activity>")
        assertTrue(activity.contains("android:enabled=\"false\"") && !activity.contains("autoVerify"))
        val filters = activity.split("<intent-filter>").drop(1)
        assertEquals(2, filters.size)
        val hosts = filters.map { f -> Regex("android:host=\"([^\"]+)\"").findAll(f).map { it.groupValues[1] }.toList() }
        assertEquals(MapLinkForms.ANY_PATH_HOSTS, hosts[0])
        assertEquals(MapLinkForms.MAPS_PATH_HOSTS, hosts[1])
        assertTrue(!filters[0].contains("pathPrefix") && filters[1].contains("android:pathPrefix=\"/maps\""))
        for (f in filters) {
            for (needed in listOf("android.intent.action.VIEW", "android.intent.category.DEFAULT", "android.intent.category.BROWSABLE", "scheme=\"http\"", "scheme=\"https\"")) {
                assertTrue(needed, f.contains(needed))
            }
        }
        // Every host the handler takes is a map link by the shared rules.
        for (host in MapLinkForms.ANY_PATH_HOSTS) {
            val probe = if (host == "maps.app.goo.gl") "https://$host/AbC" else if (host == "maps.apple") "https://$host/p/AbC" else "https://$host/maps?q=1,2"
            assertTrue(host, MapLinkForms.isMapLink(probe))
        }
        for (host in MapLinkForms.MAPS_PATH_HOSTS) assertTrue(host, MapLinkForms.isMapLink("https://$host/maps/AbC"))
    }
}
