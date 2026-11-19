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
import org.junit.Assert.assertFalse
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
        assertEquals(59.920121, lat, 1e-6)
        assertEquals(10.650758, lon, 1e-6)
        assertEquals(null, MapLinks.cellCentre("0x0:0x7d"))
        // Level 10 (~10 km cells): Artbox at Skøyen within about 2 km (level 8 put it 12 km off, over Groruddalen),
        // Steak no Don within 1 km.
        assertTrue(km(lat, lon, 59.9223615, 10.6868892) < 2.1)
        val (sLat, sLon) = MapLinks.cellCentre("0x3551565394d96b57:0x1c9bdbc5990908bf")!!
        assertTrue(km(sLat, sLon, 34.5945482, 133.7583428) < 1.0)
    }

    private fun km(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val h = Math.sin(dLat / 2).let { it * it } + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2)) * Math.sin(dLon / 2).let { it * it }
        return 6371.0 * 2 * Math.asin(Math.sqrt(h))
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
    fun `the steps read done as the report does, and Settings keeps only the way back`() {
        assertEquals(false, playLinksDone(holderAllowed = true))
        assertEquals(true, playLinksDone(holderAllowed = false))
        assertEquals(null, playLinksDone(holderAllowed = null))
        assertFalse(vibbLinksDone(VibbHosts(selected = 3, selectable = 19, declared = 19)))
        assertTrue(vibbLinksDone(VibbHosts(11, 11, 19)))
        assertFalse(vibbLinksDone(VibbHosts(0, 0, 0)))
        // Off: only the way back, only while the holder's links are off (Q4); on: never (the steps are the setup flow's).
        assertTrue(mapLinkBackShown(on = false, holderAllowed = false))
        assertFalse(mapLinkBackShown(on = false, holderAllowed = true))
        assertFalse(mapLinkBackShown(on = false, holderAllowed = null))
        assertFalse(mapLinkBackShown(on = true, holderAllowed = false))
    }

    @Test
    fun `step 2 suspends the apps holding our hosts verified, and counts what the rest hold`() {
        val verified = android.content.pm.verify.domain.DomainVerificationUserState.DOMAIN_STATE_VERIFIED
        val selected = android.content.pm.verify.domain.DomainVerificationUserState.DOMAIN_STATE_SELECTED
        val none = android.content.pm.verify.domain.DomainVerificationUserState.DOMAIN_STATE_NONE
        // The emulator check's Android 16 (finding 1): Maps, the Google app, Calendar, Photos; the Play Store holds
        // play.google.com and Android refuses to suspend it.
        val states = mapOf(
            "com.google.android.apps.maps" to mapOf("maps.google.com" to verified, "www.google.com" to verified, "goo.gl" to verified),
            "com.google.android.googlequicksearchbox" to mapOf("www.google.com" to verified, "google.com" to verified),
            "com.google.android.apps.photos" to mapOf("goo.gl" to verified, "photos.app.goo.gl" to verified),
            "com.android.vending" to mapOf("play.google.com" to verified),
            "com.android.chrome" to mapOf("www.google.com" to none),
            "org.example.other" to mapOf("maps.google.com" to selected),
        )
        val ours = mapOf("maps.google.com" to none, "www.google.com" to none, "google.com" to none, "goo.gl" to none,
            "play.google.com" to none, "maps.apple.com" to selected)
        assertEquals(
            setOf("com.google.android.apps.maps", "com.google.android.googlequicksearchbox", "com.google.android.apps.photos"),
            stepHolders(ours.keys, states, never = setOf("com.android.vending")),
        )
        assertEquals(5, selectableHosts(ours, states, unsuspendable = setOf("com.android.vending")))
        // A host already ours counts, whoever holds it.
        assertEquals(6, selectableHosts(ours + ("play.google.com" to selected), states, setOf("com.android.vending")))
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

    /** 2026-11-19: Organic Maps (and CoMaps) already running in the background stayed where it was: a link opened
     * before was dropped (its task's root intent `filterEquals` the start), and on Android 16 a new pin's card didn't
     * open while the previous card was up. The target's task is cleared, except when the target itself sent the link
     * (it is on screen, and recreating it there blacked out its map); every start has its own identity. */
    @Test
    fun `the map app is started cleared, with its own identity`() {
        assertEquals(50, (1..50).map { MapLinks.startIdentity() }.toSet().size)
        for (target in MapLinks.TARGETS) {
            assertTrue(MapLinks.clearsTask("me.vibb.launcher", target))
            assertTrue(MapLinks.clearsTask("io.element.android.x", target))
            assertTrue(MapLinks.clearsTask(null, target))
            assertFalse(MapLinks.clearsTask(target, target))
        }
        assertTrue(MapLinks.clearsTask("app.comaps.fdroid", "app.organicmaps"))
        val main = listOf(File("src/main/java"), File("app/src/main/java")).first { it.exists() }
        val open = main.resolve("com/kidslauncher/mdm/map/MapLinkActivity.kt").readText()
            .substringAfter("private fun open(").substringBefore("private fun ask(")
        for (needed in listOf(
            ".setPackage(pkg)",
            ".addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)",
            ".addFlags(if (MapLinks.clearsTask(referrer?.host, pkg)) Intent.FLAG_ACTIVITY_CLEAR_TASK else 0)",
            ".setIdentifier(MapLinks.startIdentity())",
        )) {
            assertTrue(needed, open.contains(needed))
        }
        // The handler is the one place the launcher starts a map app.
        val starters = main.walk().filter { it.extension == "kt" }.filter { f ->
            val text = f.readText()
            MapLinks.TARGETS.any { text.contains("setPackage(\"$it\")") } || text.contains("Uri.parse(\"geo:")
        }.toList()
        assertEquals(emptyList<File>(), starters)
    }
}
