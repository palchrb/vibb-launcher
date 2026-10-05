package com.kidslauncher.mdm.ui.list

import android.os.Bundle
import com.kidslauncher.mdm.apps.AppFilter
import com.kidslauncher.mdm.ui.UIObjectActivity


/**
 * This abstract class bundles common logic used in [AppListActivity].
 */
sealed class AbstractListActivity : UIObjectActivity() {
    // TODO: this should be a view model
    var hiddenVisibility: AppFilter.Companion.AppSetVisibility =
        AppFilter.Companion.AppSetVisibility.HIDDEN
    var pinnedVisibility: AppFilter.Companion.AppSetVisibility =
        AppFilter.Companion.AppSetVisibility.VISIBLE

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        intent.extras?.let { bundle ->
            @Suppress("deprecation") // required to support API level < 33
            hiddenVisibility = bundle.getSerializable(KEY_HIDDEN_VISIBILITY)
                    as? AppFilter.Companion.AppSetVisibility ?: hiddenVisibility
            @Suppress("deprecation") // required to support API level < 33
            pinnedVisibility = bundle.getSerializable(KEY_PINNED_VISIBILITY)
                    as? AppFilter.Companion.AppSetVisibility ?: pinnedVisibility
        }
    }

    override fun onPause() {
        super.onPause()

        // ensure that the activity closes then an app is launched
        // and when the user navigates to recent apps
        finish()
    }

    companion object {
        const val KEY_HIDDEN_VISIBILITY = "hiddenVisibility"
        const val KEY_PINNED_VISIBILITY = "pinnedVisibility"
    }
}
