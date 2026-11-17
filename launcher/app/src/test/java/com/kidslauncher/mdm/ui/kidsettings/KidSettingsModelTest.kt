package com.kidslauncher.mdm.ui.kidsettings

import com.kidslauncher.mdm.server.CachedPolicy
import com.kidslauncher.mdm.server.LockReason
import com.kidslauncher.mdm.server.dto.PolicyResponse
import com.kidslauncher.mdm.ui.wallpaper.INK_LIGHT
import com.kidslauncher.mdm.ui.wallpaper.InkChoice
import com.kidslauncher.mdm.ui.wallpaper.NAVY
import com.kidslauncher.mdm.ui.wallpaper.Wallpaper
import com.kidslauncher.mdm.ui.wallpaper.WallpaperFill
import com.kidslauncher.mdm.ui.wallpaper.inkForColours
import org.junit.Assert.assertEquals
import org.junit.Test

class KidSettingsModelTest {

    private fun ok(mask: Long) = CachedPolicy.Ok(PolicyResponse(allowlist = emptyList(), quickControlsMask = mask))

    @Test
    fun `switches follow the mask of an accepted policy`() {
        assertEquals(ControlsSection.Rows(wifi = true, bluetooth = true, brightness = true, sound = true, touch = true), controlsSection(true, ok(31)))
        assertEquals(ControlsSection.Rows(wifi = true, bluetooth = true, brightness = true, sound = true, touch = false), controlsSection(true, ok(15)))
        assertEquals(ControlsSection.Rows(wifi = true, bluetooth = true, brightness = true, sound = false, touch = false), controlsSection(true, ok(7)))
        assertEquals(ControlsSection.Rows(wifi = true, bluetooth = false, brightness = false, sound = false, touch = false), controlsSection(true, ok(1)))
        assertEquals(ControlsSection.Rows(wifi = false, bluetooth = true, brightness = false, sound = false, touch = false), controlsSection(true, ok(2)))
        assertEquals(ControlsSection.Rows(wifi = false, bluetooth = false, brightness = true, sound = false, touch = false), controlsSection(true, ok(4)))
        // The sound bit (design 18) alone is a card, not NoneEnabled (QA #6); so is the touch bit (design r9 §4).
        assertEquals(ControlsSection.Rows(wifi = false, bluetooth = false, brightness = false, sound = true, touch = false), controlsSection(true, ok(8)))
        assertEquals(ControlsSection.Rows(wifi = false, bluetooth = false, brightness = false, sound = false, touch = true), controlsSection(true, ok(16)))
        // Design 97 (Q97-L1): the kid's Mobildata bit alone is a card too.
        assertEquals(
            ControlsSection.Rows(wifi = false, bluetooth = false, brightness = false, sound = false, touch = false, mobileData = true),
            controlsSection(true, ok(32)),
        )
        // Unknown bits alone are nothing.
        assertEquals(ControlsSection.NoneEnabled, controlsSection(true, ok(64)))
        // Design 98 §4.1: bit 64 alone without the role is no card; with the role the full row; airplane mode on with
        // mask 0 is a card with the Off-only row - also before any policy.
        assertEquals(ControlsSection.NoneEnabled, controlsSection(true, ok(64), roleHeld = false, airplaneOn = false))
        assertEquals(ControlsSection.Rows(false, false, false, false, false, airplane = true), controlsSection(true, ok(64), roleHeld = true))
        assertEquals(ControlsSection.Rows(false, false, false, false, false, airplaneOffOnly = true), controlsSection(true, ok(0), airplaneOn = true))
        assertEquals(ControlsSection.Rows(false, false, false, false, false, airplaneOffOnly = true), controlsSection(true, CachedPolicy.Absent, airplaneOn = true))
        assertEquals(ControlsSection.NoneEnabled, controlsSection(true, ok(0)))
    }

    @Test
    fun `every reason for no switches is its own case`() {
        // Not the device owner wins over everything (also a second build beside the real one).
        assertEquals(ControlsSection.NotOwner, controlsSection(false, ok(7)))
        assertEquals(ControlsSection.NotOwner, controlsSection(false, CachedPolicy.Absent))
        // No accepted policy yet (or only the last enforced plan, which has no mask).
        assertEquals(ControlsSection.NoPolicyYet, controlsSection(true, CachedPolicy.Absent))
        // Corrupt cache: fail closed.
        assertEquals(ControlsSection.Unreadable, controlsSection(true, CachedPolicy.Corrupt("x")))
        // Each case says what it is in the log.
        val all = listOf(
            ControlsSection.NotOwner, ControlsSection.NoPolicyYet, ControlsSection.Unreadable,
            ControlsSection.NoneEnabled, ControlsSection.Rows(true, false, true, false, true),
        )
        assertEquals(all.size, all.map { it.describe() }.toSet().size)
    }

    @Test
    fun `picker shows the choices with the shown one selected, nothing when there is no choice`() {
        val forest = Wallpaper(2, WallpaperFill.Solid(0xFF1E4D3A.toInt()), "Forest", "forest")
        val tiles = wallpaperTiles(listOf(NAVY, forest), forest)
        assertEquals(listOf(WallpaperTile(NAVY, false), WallpaperTile(forest, true)), tiles)
        assertEquals(emptyList<WallpaperTile>(), wallpaperTiles(listOf(NAVY), NAVY))
        assertEquals(emptyList<WallpaperTile>(), wallpaperTiles(emptyList(), NAVY))
        val model = kidSettingsModel(true, ok(1), listOf(NAVY, forest), NAVY)
        assertEquals(ControlsSection.Rows(wifi = true, bluetooth = false, brightness = false, sound = false, touch = false), model.controls)
        assertEquals(2, model.wallpapers.size)
    }

    /** Design 64 step 2 (the user's decision 6): the calm line while the phone shares its location. */
    @Test
    fun `the location line shows while location isn't off`() {
        fun located(mode: String?) = CachedPolicy.Ok(
            PolicyResponse(allowlist = emptyList(), locationPolicy = mode?.let { com.kidslauncher.mdm.server.dto.LocationPolicy(it, 30) }),
        )
        assertEquals(true, sharesLocation(true, located("interval")))
        assertEquals(true, sharesLocation(true, located("on_request")))
        // An older server's policy without location_policy: the old every-10-minutes trail.
        assertEquals(true, sharesLocation(true, located(null)))
        assertEquals(false, sharesLocation(true, located("off")))
        assertEquals(false, sharesLocation(false, located("interval")))
        assertEquals(false, sharesLocation(true, CachedPolicy.Absent))
        assertEquals(false, sharesLocation(true, CachedPolicy.Corrupt("x")))
        assertEquals(true, kidSettingsModel(true, located("interval"), listOf(NAVY), NAVY).sharesLocation)
        // The words, calm and the same in both languages' structure; no control on the line.
        val layout = java.io.File("src/main/res/layout/activity_kid_settings.xml").readText()
        val line = Regex("""<TextView\s+android:id="@\+id/kid_settings_location_line"[^>]*/>""").find(layout)!!.value
        assertEquals(false, line.contains("clickable") || line.contains("onClick"))
        val en = java.io.File("src/main/res/values/strings.xml").readText()
        val nb = java.io.File("src/main/res/values-nb/strings.xml").readText()
        assertEquals(true, en.contains(">This phone shares where it is with mum and dad.</string>"))
        assertEquals(true, nb.contains(">Telefonen deler hvor den er med mamma og pappa.</string>"))
    }

    /** Design 111 §2.2: the four sub-pages' one gate - the rule first, then the page's own row as Kid Settings shows it. */
    @Test
    fun `a sub-page yields to any rule, then stays only while Kid Settings shows its row`() {
        data class Row(val lock: LockReason, val section: ControlsSection, val page: SubPage, val mask: Long?, val writable: Boolean, val gate: SubPageGate)
        val all = ok(159)
        val rows = listOf(
            // Every lock reason redirects every page, whatever its row; a pause or Foreldrekode is NONE.
            Row(LockReason.RULE, controlsSection(true, all), SubPage.WIFI, 159, true, SubPageGate.REDIRECT),
            Row(LockReason.BEDTIME, controlsSection(true, all), SubPage.BLUETOOTH, 159, true, SubPageGate.REDIRECT),
            Row(LockReason.SCHOOL, controlsSection(true, all), SubPage.SOUND_PICKER, 159, true, SubPageGate.REDIRECT),
            Row(LockReason.SCREEN_TIME, controlsSection(true, all), SubPage.SHORTCUTS, 159, true, SubPageGate.REDIRECT),
            Row(LockReason.RULE, ControlsSection.NotOwner, SubPage.WIFI, null, false, SubPageGate.REDIRECT),
            // No lock, the row there: open.
            Row(LockReason.NONE, controlsSection(true, all), SubPage.WIFI, 159, false, SubPageGate.OPEN),
            Row(LockReason.NONE, controlsSection(true, all), SubPage.BLUETOOTH, 159, false, SubPageGate.OPEN),
            Row(LockReason.NONE, controlsSection(true, all), SubPage.SOUND_PICKER, 159, true, SubPageGate.OPEN),
            Row(LockReason.NONE, controlsSection(true, ok(128)), SubPage.SHORTCUTS, 128, false, SubPageGate.OPEN),
            // The parent's Wi-Fi or Bluetooth bit gone (the Wi-Fi page checked no mask before design 111).
            Row(LockReason.NONE, controlsSection(true, ok(2)), SubPage.WIFI, 2, false, SubPageGate.FINISH),
            Row(LockReason.NONE, controlsSection(true, ok(1)), SubPage.BLUETOOTH, 1, false, SubPageGate.FINISH),
            Row(LockReason.NONE, controlsSection(true, ok(0)), SubPage.WIFI, 0, false, SubPageGate.FINISH),
            // The sound picker: the sound bit and the app-op, both.
            Row(LockReason.NONE, controlsSection(true, all), SubPage.SOUND_PICKER, 159, false, SubPageGate.FINISH),
            Row(LockReason.NONE, controlsSection(true, ok(7)), SubPage.SOUND_PICKER, 7, true, SubPageGate.FINISH),
            // Snarveier: bit 128 of an accepted policy on a device-owner phone.
            Row(LockReason.NONE, controlsSection(true, ok(31)), SubPage.SHORTCUTS, 31, false, SubPageGate.FINISH),
            Row(LockReason.NONE, controlsSection(true, CachedPolicy.Absent), SubPage.SHORTCUTS, null, false, SubPageGate.FINISH),
            Row(LockReason.NONE, controlsSection(false, ok(128)), SubPage.SHORTCUTS, 128, false, SubPageGate.FINISH),
            // No owner, no policy or an unreadable one: no page.
            Row(LockReason.NONE, ControlsSection.NotOwner, SubPage.WIFI, null, false, SubPageGate.FINISH),
            Row(LockReason.NONE, ControlsSection.NoPolicyYet, SubPage.BLUETOOTH, null, false, SubPageGate.FINISH),
            Row(LockReason.NONE, ControlsSection.Unreadable, SubPage.SOUND_PICKER, null, true, SubPageGate.FINISH),
            // Airplane mode's Off-only row without a policy carries no page.
            Row(LockReason.NONE, controlsSection(true, CachedPolicy.Absent, airplaneOn = true), SubPage.WIFI, null, false, SubPageGate.FINISH),
        )
        for (row in rows) {
            assertEquals("$row", row.gate, subPageGate(row.lock, row.section, row.page, row.mask, row.writable))
        }
        // Each page and each outcome has a row.
        assertEquals(SubPage.entries.toSet(), rows.map { it.page }.toSet())
        assertEquals(SubPageGate.entries.toSet(), rows.map { it.gate }.toSet())
    }

    @Test
    fun `the Wi-Fi page is allowed exactly with the Wi-Fi row, as the Bluetooth page with its own`() {
        val sections = listOf(
            controlsSection(true, ok(1)), controlsSection(true, ok(2)), controlsSection(true, ok(3)), controlsSection(true, ok(0)),
            ControlsSection.NotOwner, ControlsSection.NoPolicyYet, ControlsSection.Unreadable,
        )
        assertEquals(listOf(true, false, true, false, false, false, false), sections.map(::wifiPageAllowed))
        assertEquals(listOf(false, true, true, false, false, false, false), sections.map(::bluetoothPageAllowed))
    }

    /** Design 111 §2.2 (L1): system Location only while the Wi-Fi page is shown, and only ever turned off by the page
     * that turned it on; a running connect poll is dropped before Location goes. */
    @Test
    fun `the Wi-Fi page holds Location only while shown`() {
        data class Row(val event: WifiPageEvent, val enabledNow: Boolean, val byUs: Boolean, val polling: Boolean, val step: WifiLocationStep)
        val rows = listOf(
            // A start: off -> on and ours, then the scan; on already -> left as it is (the parent's stays the parent's).
            Row(WifiPageEvent.START, false, false, false, WifiLocationStep(stopPoll = false, location = true, byUs = true, scan = true)),
            Row(WifiPageEvent.START, true, false, false, WifiLocationStep(stopPoll = false, location = null, byUs = false, scan = true)),
            Row(WifiPageEvent.START, true, true, false, WifiLocationStep(stopPoll = false, location = null, byUs = true, scan = true)),
            Row(WifiPageEvent.START, false, true, false, WifiLocationStep(stopPoll = false, location = true, byUs = true, scan = true)),
            // A stop: ours and on -> off; the poll first when one runs, with no toast.
            Row(WifiPageEvent.STOP, true, true, false, WifiLocationStep(stopPoll = false, location = false, byUs = false, scan = false)),
            Row(WifiPageEvent.STOP, true, true, true, WifiLocationStep(stopPoll = true, location = false, byUs = false, scan = false)),
            // The parent's Location is never touched; one already turned off by someone else is left.
            Row(WifiPageEvent.STOP, true, false, true, WifiLocationStep(stopPoll = true, location = null, byUs = false, scan = false)),
            Row(WifiPageEvent.STOP, true, false, false, WifiLocationStep(stopPoll = false, location = null, byUs = false, scan = false)),
            Row(WifiPageEvent.STOP, false, true, false, WifiLocationStep(stopPoll = false, location = null, byUs = false, scan = false)),
        )
        for (row in rows) {
            assertEquals("$row", row.step, wifiLocationStep(row.event, row.enabledNow, row.byUs, row.polling))
        }
        // Code QA L4: ours, but the parent's policy wants it on now (Find my device, the zone watch): left on, no longer
        // ours; the poll still dropped.
        assertEquals(
            WifiLocationStep(stopPoll = true, location = null, byUs = false, scan = false),
            wifiLocationStep(WifiPageEvent.STOP, enabledNow = true, byUs = true, polling = true, policyKeepsOn = true),
        )
        assertEquals(
            WifiLocationStep(stopPoll = false, location = true, byUs = true, scan = true),
            wifiLocationStep(WifiPageEvent.START, enabledNow = false, byUs = false, polling = false, policyKeepsOn = true),
        )
        // A start then a stop leaves Location as it was found, whichever it was.
        for (found in listOf(true, false)) {
            val start = wifiLocationStep(WifiPageEvent.START, found, byUs = false, polling = false)
            val on = start.location ?: found
            val stop = wifiLocationStep(WifiPageEvent.STOP, on, start.byUs, polling = true)
            assertEquals("$found", found, stop.location ?: on)
        }
    }

    /** Design 111 Q1: Kid Settings' plain night ground takes white ink with no scrim and no shadow. */
    @Test
    fun `the plain night ground takes white ink without a scrim`() {
        val kidGround = 0xFF0C0C14.toInt()
        assertEquals(InkChoice(INK_LIGHT, 0.0, false), inkForColours(listOf(kidGround)))
        val colours = java.io.File("src/main/res/values/colors_kid.xml").readText()
        assertEquals(true, colours.contains("<color name=\"kid_ground\">#0C0C14</color>"))
    }
}

