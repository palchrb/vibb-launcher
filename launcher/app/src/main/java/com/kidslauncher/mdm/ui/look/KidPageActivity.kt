package com.kidslauncher.mdm.ui.look

import android.content.res.Resources
import android.os.Bundle
import com.kidslauncher.mdm.R
import com.kidslauncher.mdm.ui.UIObjectActivity

/**
 * A page: a screen drawn in the kid's look (design 78 §5). Its manifest entry names `KidPageTheme` and - unlike
 * every other activity - no `uiMode` in `configChanges` (PageManifestTest); its layouts and drawables name page
 * tokens only (PageTokensScanTest). The class gives it the applied look as its AppCompat night mode, set before the
 * base context is attached so the configuration is right from the start (one `onCreate` per open, no `recreate`),
 * the page colours over the launcher's theme, and its place in [PageLook]'s registry and started count.
 */
abstract class KidPageActivity : UIObjectActivity() {
    override fun lookNightMode(): Int = PageLook.pageNightMode()

    /** `PageColors` over what [modifyTheme] applied, on every call: it applies the launcher's colour theme each time. */
    override fun getTheme(): Resources.Theme? = super.getTheme()?.also { it.applyStyle(R.style.PageColors, true) }

    override fun onCreate(savedInstanceState: Bundle?) {
        PageLook.pageCreated(this)
        super.onCreate(savedInstanceState)
    }

    override fun onStart() {
        super.onStart()
        PageLook.pageTaskStarted()
    }

    override fun onStop() {
        PageLook.pageTaskStopped()
        super.onStop()
    }

    override fun onDestroy() {
        PageLook.pageDestroyed(this)
        super.onDestroy()
    }
}
