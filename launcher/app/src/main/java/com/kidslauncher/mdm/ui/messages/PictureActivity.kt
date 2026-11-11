package com.kidslauncher.mdm.ui.messages

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import com.kidslauncher.mdm.R
import com.kidslauncher.mdm.databinding.ActivityPictureBinding
import com.kidslauncher.mdm.sms.SmsRuntime
import com.kidslauncher.mdm.sms.engine.MessageId
import com.kidslauncher.mdm.ui.KidHeader
import com.kidslauncher.mdm.ui.KidInsets
import com.kidslauncher.mdm.ui.UIObjectActivity
import com.kidslauncher.mdm.ui.day.LockWatch
import com.kidslauncher.mdm.ui.look.PageLook

/**
 * A received picture, fit to the screen, with "Lagre" and "Del" (design 77 §6.7; the user, 2026-11-09). The bytes are
 * the engine's own JPEG of that part ([SmsPictures], [PictureShare]) - never the raw MMS part. Reachable only where the
 * thread is: a time rule that blocks it brings the time-rule screen here too. With nothing to show (a part that isn't
 * there, or not yet - pictures come with step 3b) it closes.
 */
class PictureActivity : UIObjectActivity() {
    /** Design 78 §5.4.1: no page - a photo is seen best on the night, in both looks - but in Meldinger's task, so it
     * counts among the started activities a change of look waits for. */
    override fun onStart() {
        super.onStart()
        PageLook.pageTaskStarted()
    }

    override fun onStop() {
        PageLook.pageTaskStopped()
        super.onStop()
    }


    private lateinit var binding: ActivityPictureBinding
    private var message = MessageId("")
    private var index = 0
    private val lockWatch = LockWatch(this) { MessagesScreens.redirectIfLocked(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val id = intent.getStringExtra(EXTRA_MESSAGE)
        if (id == null) {
            finish()
            return
        }
        message = MessageId(id)
        index = intent.getIntExtra(EXTRA_PART, 0)
        binding = ActivityPictureBinding.inflate(layoutInflater)
        setContentView(binding.root)
        KidInsets.apply(binding.root)
        KidHeader.bind(this, binding.pictureHeader, R.string.picture_title, back = R.string.kid_back)
        binding.pictureSave.setOnClickListener { save() }
        binding.pictureShare.setOnClickListener { share() }
        PictureShare.prune(this)
        val maxPx = maxOf(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels)
        SmsRuntime.ask(this, { engine -> SmsPictures.decode({ engine.openPart(message, index) }, maxPx) }) { bitmap ->
            if (isDestroyed) return@ask
            if (bitmap == null) finish() else binding.pictureImage.setImageBitmap(bitmap)
        }
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

    private fun save() {
        val app = applicationContext
        SmsRuntime.ask(this, { engine -> PictureShare.save(app) { engine.openPart(message, index) } }) { saved ->
            Toast.makeText(app, if (saved == true) R.string.picture_saved else R.string.picture_save_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun share() {
        val app = applicationContext
        SmsRuntime.ask(this, { engine -> PictureShare.copyForShare(app) { engine.openPart(message, index) } }) { file ->
            if (file != null && !isDestroyed) PictureShare.share(this, file)
        }
    }

    companion object {
        private const val EXTRA_MESSAGE = "me.vibb.launcher.extra.SMS_MESSAGE"
        private const val EXTRA_PART = "me.vibb.launcher.extra.SMS_PART"

        fun intent(context: Context, messageId: String, part: Int): Intent =
            Intent(context, PictureActivity::class.java).putExtra(EXTRA_MESSAGE, messageId).putExtra(EXTRA_PART, part)
    }
}
