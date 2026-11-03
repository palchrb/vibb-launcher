package com.kidslauncher.mdm.ui.kidsettings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Kid Settings' "Skjulte apper" (the user, 2026-11-03): the kid shows again what the kid hid on Home. */
class HiddenAppsTest {
    private fun code(path: String) =
        listOf("src/main/java/com/kidslauncher/mdm/$path", "app/src/main/java/com/kidslauncher/mdm/$path").map(::File).first { it.exists() }.readText()

    @Test
    fun `no row without a hidden app, the list only while open`() {
        assertEquals(HiddenAppsView(rowShown = false, open = false, listed = emptyList<String>()), hiddenAppsView(emptyList<String>(), open = false))
        assertEquals(HiddenAppsView(true, false, emptyList<String>()), hiddenAppsView(listOf("Kamera", "YouTube"), open = false))
        assertEquals(HiddenAppsView(true, true, listOf("Kamera", "YouTube")), hiddenAppsView(listOf("Kamera", "YouTube"), open = true))
    }

    @Test
    fun `the list closes with its last app, so the next hidden app brings back a closed row`() {
        // The last one shown again while open: no row, and closed.
        assertEquals(HiddenAppsView(false, false, emptyList<String>()), hiddenAppsView(emptyList<String>(), open = true))
        val closed = hiddenAppsView(emptyList<String>(), open = true).open
        assertEquals(HiddenAppsView(true, false, emptyList<String>()), hiddenAppsView(listOf("Kamera"), closed))
    }

    @Test
    fun `the listed apps are the hidden ones Home would otherwise show - Home's own filter`() {
        // One filter for the grid and the list: the list is AppFilter's EXCLUSIVE side of the kid's hidden set, with
        // Home's own extra rules (not Vibb One, the store only in install mode); the parent's off-Home apps stay out
        // through AppFilter's onHomeListable (AppDisplayTest).
        val home = code("ui/HomeActivity.kt").substringAfter("private fun refreshApps(").substringBefore("\n    }\n")
        assertTrue("val visible = homeApps(context, all)" in home)
        val kid = code("ui/kidsettings/KidSettingsActivity.kt")
        val load = kid.substringAfter("private fun loadHiddenApps(").substringBefore("\n    }\n")
        assertTrue("homeApps(context, all, AppSetVisibility.EXCLUSIVE)" in load)
        assertTrue("withContext(Dispatchers.Default)" in load)
        // The name the kid sees and the icon as Home draws it.
        assertTrue("info.getCustomLabel(context)" in load)
        assertTrue("KidAvatars.renderAppIcon(context, raw.serialize(), { info.getIcon(context) }, sizePx)" in load)
        val filter = code("ui/home/HomeApps.kt").substringAfter("fun homeApps(")
        assertTrue("AppFilter(context, visibility).invoke(all)" in filter)
        assertTrue("(it.getRawInfo() as? AppInfo)?.packageName != context.packageName" in filter)
    }

    @Test
    fun `a tap takes only that app off the kid's hidden set, and the list follows every change of it`() {
        val kid = code("ui/kidsettings/KidSettingsActivity.kt")
        val show = kid.substringAfter("private fun showAgain(").substringBefore("\n    }\n")
        assertTrue("LauncherPreferences.apps().hidden(LauncherPreferences.apps().hidden().orEmpty() - app.raw)" in show)
        assertTrue("LauncherPreferences.apps().keys().hidden() -> loadHiddenApps()" in kid)
        // TalkBack: each app row is one button with its own name, the row says whether the list is open.
        assertTrue("getString(R.string.kid_settings_hidden_show_app, app.label)" in kid)
        assertTrue("binding.kidSettingsHiddenRow.stateDescription =" in kid)
    }
}
