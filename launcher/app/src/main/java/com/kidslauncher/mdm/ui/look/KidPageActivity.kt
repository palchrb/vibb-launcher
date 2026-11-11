package com.kidslauncher.mdm.ui.look

import android.content.res.Resources
import android.os.Bundle
import com.kidslauncher.mdm.R
import com.kidslauncher.mdm.ui.UIObjectActivity

/**
 * A page: a screen drawn in the kid's look (design 78 §5, §12). A page that can top a task of its own is two manifest
 * activities - its night class on `KidPageNightTheme` and an empty light twin on `KidPageLightTheme` ([PageKind]) -
 * so the window Android draws before any code of ours is in the look; like every activity it declares `uiMode` in
 * `configChanges`, so the phone's theme never restarts it (PageManifestTest). Its layouts and drawables name page
 * tokens only (PageTokensScanTest). The class gives it its variant's look as its AppCompat night mode, set before the
 * base context is attached so the configuration is right from the start (one `onCreate` per open, no `recreate`),
 * the page colours over the launcher's theme, and its place in [PageLook]'s registry and - for a page of a task of its
 * own - its started count.
 */
abstract class KidPageActivity : UIObjectActivity() {
    override fun lookNightMode(): Int = PageLook.pageNightMode(javaClass)

    /** `PageColors` over what [modifyTheme] applied, on every call: it applies the launcher's colour theme each time. */
    override fun getTheme(): Resources.Theme? = super.getTheme()?.also { it.applyStyle(R.style.PageColors, true) }

    /** A page of the other look's class is finishing when this returns ([PageLook.closeIfOtherLook]): a page's own
     * `onCreate` returns then, before it sets any content. */
    override fun onCreate(savedInstanceState: Bundle?) {
        PageLook.pageCreated(this)
        super.onCreate(savedInstanceState)
        PageLook.closeIfOtherLook(this)
    }

    /** A page that can top a task of its own (a row in [PageKind]); a page with one class is in Home's task (R9)
     * and is not counted for [mayChange]. */
    private val ownTask: Boolean get() = PageKind.lookOf(javaClass) != null

    override fun onStart() {
        super.onStart()
        if (ownTask) PageLook.pageTaskStarted()
    }

    override fun onStop() {
        if (ownTask) PageLook.pageTaskStopped()
        super.onStop()
    }

    override fun onDestroy() {
        PageLook.pageDestroyed(this)
        super.onDestroy()
    }
}
