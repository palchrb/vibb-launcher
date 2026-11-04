package com.kidslauncher.mdm.ui.kidsettings

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.RingtoneManager
import android.net.Uri
import android.provider.MediaStore
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The phone's side of the kid's ringtone and message sound ([SoundPicks]): what is set, the built-in sounds, the write
 * and the preview. Reads and writes run off the main thread. The write is `RingtoneManager.setActualDefaultRingtoneUri`
 * (the public `RINGTONE` / `NOTIFICATION_SOUND` keys and the ringtone cache Android plays before the first unlock),
 * which needs the "Modify system settings" app-op the touch row already uses (`Settings.System.canWrite`); never the
 * system picker (`RingtoneManager.ACTION_RINGTONE_PICKER`, KidScreensEscapeTest).
 */
object KidSounds {
    private const val TAG = "KidSounds"

    /** One write at a time, in the order of the taps (each also copies the sound into Android's cache). */
    private val writes = Mutex()

    private fun type(kind: SoundKind) = when (kind) {
        SoundKind.RINGTONE -> RingtoneManager.TYPE_RINGTONE
        SoundKind.MESSAGE -> RingtoneManager.TYPE_NOTIFICATION
    }

    fun canWrite(context: Context): Boolean = try {
        Settings.System.canWrite(context.applicationContext)
    } catch (e: Exception) {
        false
    }

    /** The phone's current default for [kind] (`null` = none set, or unreadable). */
    fun current(context: Context, kind: SoundKind): String? = try {
        RingtoneManager.getActualDefaultRingtoneUri(context.applicationContext, type(kind))?.toString()
    } catch (e: Exception) {
        Log.w(TAG, "Couldn't read the default $kind sound", e)
        null
    }

    /** The current sound and its title as the row shows it ([soundValue]). */
    suspend fun currentValue(context: Context, kind: SoundKind): Pair<String?, SoundValue> = withContext(Dispatchers.IO) {
        val uri = current(context, kind)
        uri to soundValue(uri, uri?.let { title(context, it) })
    }

    /** A media sound's title from MediaStore (the internal volume needs no permission); `null` when it can't be read. */
    fun title(context: Context, uri: String): String? = try {
        val resolver = context.applicationContext.contentResolver
        resolver.query(
            plain(context, uri), arrayOf(MediaStore.MediaColumns.TITLE), null, null, null,
        )?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    } catch (e: Exception) {
        null
    }

    /** A canonical media URI (`?title=…&canonical=1`, what RingtoneManager hands out) as the row it names now. */
    private fun plain(context: Context, uri: String): Uri {
        val parsed = Uri.parse(uri)
        return try {
            context.applicationContext.contentResolver.uncanonicalize(parsed) ?: parsed
        } catch (e: Exception) {
            parsed
        }
    }

    /** RingtoneManager's list for [kind] (built-in and shared-storage sounds; [pickerRows] keeps the built-in ones). */
    suspend fun sounds(context: Context, kind: SoundKind): List<PickerSound> = withContext(Dispatchers.IO) {
        try {
            val manager = RingtoneManager(context.applicationContext).apply { setType(type(kind)) }
            manager.cursor.use { cursor ->
                val sounds = ArrayList<PickerSound>(cursor.count)
                while (cursor.moveToNext()) {
                    val uri = manager.getRingtoneUri(cursor.position)?.toString() ?: continue
                    sounds += PickerSound(uri, cursor.getString(RingtoneManager.TITLE_COLUMN_INDEX).orEmpty())
                }
                sounds
            }
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't list the phone's $kind sounds", e)
            emptyList()
        }
    }

    /**
     * Makes [uri] the phone's default [kind] sound; whether the phone now has it. Without the app-op Android refuses
     * (SecurityException); a sound whose type it can't read is skipped silently by Android - the read-back tells.
     */
    suspend fun set(context: Context, kind: SoundKind, uri: String): Boolean = withContext(Dispatchers.IO) {
        writes.withLock {
            try {
                RingtoneManager.setActualDefaultRingtoneUri(context.applicationContext, type(kind), Uri.parse(uri))
            } catch (e: SecurityException) {
                Log.w(TAG, "Setting the $kind sound was refused", e)
            } catch (e: IllegalArgumentException) {
                Log.w(TAG, "Setting the $kind sound was refused", e)
            }
            val written = sameSound(current(context, kind), uri)
            if (!written) Log.w(TAG, "The $kind sound didn't change")
            written
        }
    }

    /**
     * The picker's preview: one sound at a time on the media stream (`USAGE_MEDIA`), so it is heard while the phone is
     * in Lydløs, with a ducking focus request so music pauses or ducks. Main thread.
     */
    class Preview(context: Context) {
        private val app = context.applicationContext
        private val audio = app.getSystemService(AudioManager::class.java)
        private val attributes = AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
            .build()
        private val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(attributes)
            .build()
        private var player: MediaPlayer? = null

        fun play(uri: String) {
            stop()
            try {
                audio?.requestAudioFocus(focus)
                player = MediaPlayer().apply {
                    setAudioAttributes(attributes)
                    setDataSource(app, plain(app, uri))
                    setOnPreparedListener { it.start() }
                    setOnCompletionListener { if (player === it) stop() }
                    setOnErrorListener { mp, _, _ ->
                        if (player === mp) stop()
                        true
                    }
                    prepareAsync()
                }
            } catch (e: Exception) {
                Log.w(TAG, "Couldn't play the sound", e)
                stop()
            }
        }

        fun stop() {
            val current = player ?: run {
                audio?.abandonAudioFocusRequest(focus)
                return
            }
            player = null
            try {
                current.reset()
            } catch (e: Exception) {
                // Already released or in an error state - harmless.
            }
            current.release()
            audio?.abandonAudioFocusRequest(focus)
        }
    }
}
