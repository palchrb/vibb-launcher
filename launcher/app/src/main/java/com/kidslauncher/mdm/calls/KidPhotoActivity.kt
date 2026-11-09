package com.kidslauncher.mdm.calls

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.lifecycle.ViewModel
import com.kidslauncher.mdm.R
import com.kidslauncher.mdm.databinding.ActivityKidPhotoBinding
import com.kidslauncher.mdm.sms.parse.PictureEncoder
import com.kidslauncher.mdm.ui.KidHeader
import com.kidslauncher.mdm.ui.KidInsets
import com.kidslauncher.mdm.ui.UIObjectActivity
import com.kidslauncher.mdm.ui.day.LockWatch
import com.kidslauncher.mdm.ui.messages.MessagesScreens
import com.kidslauncher.mdm.ui.pictures.PicturePick
import java.io.File
import java.util.concurrent.Executors

/**
 * The kid's picture of a contact (design 79 §5.2-§5.3), or of a contact request's draft (§5.5): it opens the system
 * photo picker or the parent-allowed camera at once ([PicturePick]), has the picked file decoded in the isolated
 * process ([PicturePick.decode]), shows the framing ([PhotoCropView]) and on "Bruk bildet" re-encodes the square
 * ([PictureEncoder.forContact], no EXIF) - into [KidContactPhotos] for a contact, or into a draft file the request form
 * gets back. Its own activity, so the result lands in one place whether the card came from the phone book or Home.
 * Night, not on the wallpaper.
 *
 * **Guards** (QA L12): under a time rule it is reachable only where calls are, as the phone book (checked at resume and
 * watched); it finishes when, at a sync, the contact leaves the phone book or its number becomes an emergency number;
 * it stays out of the screen-time tracker's free screens; it reads the picker's answer only as
 * `PicturePick.picked(this, result.data?.data)` - never a `file://`.
 */
class KidPhotoActivity : UIObjectActivity() {

    /** What survives a rebuild (the picture and its framing; the camera's file). */
    class Held : ViewModel() {
        var picture: Bitmap? = null
        var crop: PhotoCrop? = null
        var launched = false
        var reading = false
    }

    private val held: Held by viewModels()
    private lateinit var binding: ActivityKidPhotoBinding
    private var captureFile: File? = null
    private val worker = Executors.newSingleThreadExecutor()
    private val lockWatch = LockWatch(this) { MessagesScreens.redirectIfLocked(it) }
    private val policyListener: (CallPolicyState) -> Unit = { runOnUiThread { if (!stillOffered()) finish() } }

    private val pickLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult finish()
        // Only another app's content URI is read - never a file:// or one of our own providers (design 77 code QA M3).
        val source = PicturePick.picked(this, result.data?.data) ?: return@registerForActivityResult failed()
        read(source, null)
    }

    private val captureLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val file = captureFile
        captureFile = null
        if (result.resultCode == RESULT_OK && file != null && file.length() > 0) {
            read(PicturePick.Source.Captured(file), file)
        } else {
            file?.delete()
            finish()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        CallPolicyStore.ensureLoaded(this)
        binding = ActivityKidPhotoBinding.inflate(layoutInflater)
        setContentView(binding.root)
        KidInsets.apply(binding.root)
        val name = intent.getStringExtra(EXTRA_NAME).orEmpty()
        KidHeader.bind(this, binding.kidPhotoHeader, getString(R.string.kid_photo_title, name), null, null, R.string.kid_back, null)
        binding.kidPhotoCancel.setOnClickListener { finish() }
        binding.kidPhotoUse.setOnClickListener { use() }
        captureFile = savedInstanceState?.getString(STATE_CAPTURE)?.let { File(it) }
        if (!stillOffered()) {
            finish()
            return
        }
        held.picture?.let { show(it) }
        // Launched once: not again after a rebuild (the picker's or the camera's answer still comes to this screen).
        if (savedInstanceState?.getBoolean(STATE_LAUNCHED) == true) held.launched = true
        if (!held.launched) {
            held.launched = true
            launch()
        }
        CallPolicyStore.addListener(policyListener)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        captureFile?.let { outState.putString(STATE_CAPTURE, it.path) }
        outState.putBoolean(STATE_LAUNCHED, held.launched)
        if (::binding.isInitialized) binding.kidPhotoCrop.crop?.let { held.crop = it }
    }

    override fun onResume() {
        super.onResume()
        if (MessagesScreens.redirectIfLocked(this)) return
        lockWatch.start()
    }

    override fun onPause() {
        lockWatch.stop()
        super.onPause()
    }

    override fun onDestroy() {
        CallPolicyStore.removeListener(policyListener)
        worker.shutdown()
        super.onDestroy()
    }

    /** A contact's picture is still wanted: the contact in the phone book, not an emergency number (a draft always is). */
    private fun stillOffered(): Boolean {
        if (intent.getBooleanExtra(EXTRA_DRAFT, false)) return true
        val rules = (CallPolicyStore.state as? CallPolicyState.Managed)?.rules ?: return false
        val contact = contact() ?: return false
        return rules.phoneBook.any { it.id == contact.id && it.number == contact.number } &&
            !CallSystem.isEmergencyOutgoing(this, contact.number)
    }

    private fun contact(): RuleContact? {
        val id = intent.getLongExtra(EXTRA_CONTACT_ID, Long.MIN_VALUE).takeIf { it != Long.MIN_VALUE } ?: return null
        val number = intent.getStringExtra(EXTRA_NUMBER) ?: return null
        return RuleContact(id = id, name = intent.getStringExtra(EXTRA_NAME).orEmpty(), number = number)
    }

    private fun launch() {
        val camera = intent.getStringExtra(EXTRA_CAMERA)
        try {
            if (camera != null) {
                val (capture, file) = PicturePick.captureIntent(this, camera)
                captureFile = file
                captureLauncher.launch(capture)
            } else {
                val pick = PicturePick.pickIntent(this) ?: return failed()
                pickLauncher.launch(pick)
            }
        } catch (e: android.content.ActivityNotFoundException) {
            captureFile?.delete()
            captureFile = null
            failed()
        }
    }

    /** The picked file to pixels in the isolated process, off the main thread. */
    private fun read(source: PicturePick.Source, file: File?) {
        held.reading = true
        binding.kidPhotoHint.setText(R.string.kid_photo_reading)
        val app = applicationContext
        worker.execute {
            val (picture, _) = try {
                PicturePick.decode(app, source)
            } finally {
                file?.delete()
            }
            runOnUiThread {
                held.reading = false
                if (isDestroyed) return@runOnUiThread
                if (picture == null) return@runOnUiThread failed()
                held.picture = picture
                held.crop = null
                show(picture)
            }
        }
    }

    private fun show(picture: Bitmap) {
        binding.kidPhotoHint.setText(R.string.kid_photo_frame_hint)
        binding.kidPhotoCrop.setPicture(picture, held.crop)
        binding.kidPhotoCrop.visibility = View.VISIBLE
        binding.kidPhotoUse.isEnabled = true
    }

    /** "Bruk bildet": the framed square (TalkBack's is the centred one: the frame isn't moved without touch) to our own
     * JPEG, off the main thread; then stored and done. */
    private fun use() {
        val picture = held.picture ?: return
        val cut = (binding.kidPhotoCrop.crop ?: PhotoCrop.centered(picture.width, picture.height)).cut()
        binding.kidPhotoUse.isEnabled = false
        val app = applicationContext
        val draft = intent.getBooleanExtra(EXTRA_DRAFT, false)
        val contact = contact()
        worker.execute {
            val encoded = try {
                val square = Bitmap.createBitmap(picture, cut.left, cut.top, cut.side, cut.side)
                PictureEncoder.forContact(square).also { if (square !== picture) square.recycle() }
            } catch (e: Exception) {
                null
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                if (encoded == null) return@runOnUiThread failed()
                if (draft) {
                    val file = writeDraft(app, encoded.bytes)
                    if (file == null) return@runOnUiThread failed()
                    setResult(Activity.RESULT_OK, Intent().putExtra(RESULT_DRAFT_FILE, file.name))
                    finish()
                } else if (contact != null) {
                    val phoneBook = (CallPolicyStore.state as? CallPolicyState.Managed)?.rules?.phoneBook?.size ?: 0
                    KidContactPhotos.set(app, contact, phoneBook, encoded.bytes) { ok -> if (ok) finish() else failed() }
                }
            }
        }
    }

    private fun failed() {
        Toast.makeText(this, R.string.kid_photo_failed, Toast.LENGTH_LONG).show()
        finish()
    }

    companion object {
        private const val EXTRA_CONTACT_ID = "contact_id"
        private const val EXTRA_NUMBER = "number"
        private const val EXTRA_NAME = "name"
        private const val EXTRA_DRAFT = "draft"
        private const val EXTRA_CAMERA = "camera"
        private const val STATE_CAPTURE = "capture"
        private const val STATE_LAUNCHED = "launched"

        /** The draft's file name in [ContactRequests.draftDir], in a draft's result. */
        const val RESULT_DRAFT_FILE = "draft_file"

        /** A contact's picture, from the picker or [camera] (the allowed camera's package; `null` = the picker). */
        fun forContact(context: Context, contact: RuleContact, camera: String?): Intent =
            Intent(context, KidPhotoActivity::class.java)
                .putExtra(EXTRA_CONTACT_ID, contact.id)
                .putExtra(EXTRA_NUMBER, contact.number)
                .putExtra(EXTRA_NAME, contact.name)
                .apply { if (camera != null) putExtra(EXTRA_CAMERA, camera) }

        /** A contact request's picture: a draft file, returned as [RESULT_DRAFT_FILE]. */
        fun forDraft(context: Context, name: String, camera: String?): Intent =
            Intent(context, KidPhotoActivity::class.java)
                .putExtra(EXTRA_DRAFT, true)
                .putExtra(EXTRA_NAME, name)
                .apply { if (camera != null) putExtra(EXTRA_CAMERA, camera) }

        private fun writeDraft(context: Context, jpeg: ByteArray): File? = try {
            val dir = ContactRequests.draftDir(context).apply { mkdirs() }
            File(dir, "d" + newKidPhotoFileName().removePrefix("k")).apply { writeBytes(jpeg) }
        } catch (e: Exception) {
            null
        }
    }
}
