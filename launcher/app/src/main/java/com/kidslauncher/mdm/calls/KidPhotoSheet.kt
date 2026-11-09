package com.kidslauncher.mdm.calls

import android.app.Activity
import android.app.Dialog
import android.view.LayoutInflater
import android.view.View
import android.widget.TextView
import com.kidslauncher.mdm.R
import com.kidslauncher.mdm.ui.pictures.PicturePick

/**
 * "Bilde av Mamma" (design 79 §5.2): a [Dialog] in the contact sheet's night over the card or the request form - Velg
 * bilde (the system photo picker), Ta bilde (only with a camera the parent allows), Fjern bildet ditt (only while there
 * is one to remove), "Bildet blir bare på denne telefonen.", Avbryt. The two ways to a picture start
 * [KidPhotoActivity] (it opens the picker or the camera itself); this sheet starts nothing else.
 */
object KidPhotoSheet {

    /** Whether there is anything to take a picture from: the system photo picker, or a camera the parent allows. */
    fun canPick(activity: Activity): Boolean = PicturePick.pickIntent(activity) != null || PicturePick.cameraPackage(activity) != null

    /**
     * Shows the sheet titled [title] ("Bilde av Mamma"). [onChoose] gets the camera's package for Ta bilde, `null` for
     * Velg bilde; [onRemove] (`null` = no "Fjern" row) removes the picture - [removeWords] says which picture.
     */
    fun show(
        activity: Activity,
        title: String,
        onChoose: (camera: String?) -> Unit,
        onRemove: (() -> Unit)?,
        removeWords: Int = R.string.kid_photo_remove,
    ): Dialog {
        val view = LayoutInflater.from(activity).inflate(R.layout.sheet_kid_photo, null)
        val dialog = Dialog(activity, R.style.KidSheetDialog)
        dialog.setContentView(view)
        dialog.setUpKidSheet(view)
        view.findViewById<TextView>(R.id.kid_photo_sheet_title).text = title
        val pick = view.findViewById<View>(R.id.kid_photo_sheet_pick)
        pick.visibility = if (PicturePick.pickIntent(activity) != null) View.VISIBLE else View.GONE
        pick.setOnClickListener {
            dialog.dismiss()
            onChoose(null)
        }
        val camera = PicturePick.cameraPackage(activity)
        view.findViewById<View>(R.id.kid_photo_sheet_take).apply {
            visibility = if (camera != null) View.VISIBLE else View.GONE
            setOnClickListener {
                dialog.dismiss()
                onChoose(camera)
            }
        }
        view.findViewById<TextView>(R.id.kid_photo_sheet_remove).apply {
            visibility = if (onRemove != null) View.VISIBLE else View.GONE
            setText(removeWords)
            setOnClickListener {
                dialog.dismiss()
                onRemove?.invoke()
            }
        }
        view.findViewById<View>(R.id.kid_photo_sheet_cancel).setOnClickListener { dialog.dismiss() }
        dialog.show()
        return dialog
    }
}
