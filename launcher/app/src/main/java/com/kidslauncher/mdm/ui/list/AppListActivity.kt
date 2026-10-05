package com.kidslauncher.mdm.ui.list

import android.os.Build
import android.os.Bundle
import android.window.OnBackInvokedDispatcher
import com.kidslauncher.mdm.R
import com.kidslauncher.mdm.apps.AppFilter
import com.kidslauncher.mdm.databinding.ActivityListBinding
import com.kidslauncher.mdm.openSettings

/**
 * The [AppListActivity] is used to view all apps and edit their settings.
 */
class AppListActivity : AbstractListActivity() {
    private lateinit var binding: ActivityListBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                OnBackInvokedDispatcher.PRIORITY_OVERLAY
            ) {
                finish()
            }
        }

        // Initialise layout
        binding = ActivityListBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.listSettings.setOnClickListener {
            openSettings(this@AppListActivity)
        }
    }

    fun updateTitle() {
        val titleResource =
            if (hiddenVisibility == AppFilter.Companion.AppSetVisibility.EXCLUSIVE) {
                R.string.list_title_hidden
            } else {
                R.string.list_title_view
            }
        binding.listHeading.text = getString(titleResource)
    }

    override fun setOnClicks() {
        binding.listClose.setOnClickListener { finish() }
    }

    override fun adjustLayout() {
        updateTitle()
    }
}
