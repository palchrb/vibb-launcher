package com.kidslauncher.mdm.ui

import android.view.ActionMode
import android.view.Menu
import android.view.MenuItem
import android.view.textclassifier.TextClassifier
import android.widget.EditText

/**
 * A text field with no selection, paste, share, "process text" or smart-action menu (revision check N4, and code QA M1
 * of calendar-in-rules: "Ny avtale" is reachable under a time rule that allows Kalender): nothing the field offers can
 * start an activity - a dialer from a typed number, a browser from a URL, the sharesheet. No text classifier either, so
 * no "Call"/"Open" chip. Always, not only under a rule (copy and paste in a kid's own event title is little loss).
 */
object NoTextActions {
    fun apply(field: EditText) {
        val none = object : ActionMode.Callback {
            override fun onCreateActionMode(mode: ActionMode, menu: Menu) = false
            override fun onPrepareActionMode(mode: ActionMode, menu: Menu) = false
            override fun onActionItemClicked(mode: ActionMode, item: MenuItem) = false
            override fun onDestroyActionMode(mode: ActionMode) = Unit
        }
        field.customSelectionActionModeCallback = none
        field.customInsertionActionModeCallback = none
        field.isLongClickable = false
        field.setTextClassifier(TextClassifier.NO_OP)
    }
}
