package com.kidslauncher.mdm.map

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.lifecycle.lifecycleScope
import com.kidslauncher.mdm.R
import com.kidslauncher.mdm.preferences.LauncherPreferences
import com.kidslauncher.mdm.server.DeviceCredentials
import com.kidslauncher.mdm.server.LockReason
import com.kidslauncher.mdm.server.OfflineOverride
import com.kidslauncher.mdm.server.RestrictionsPause
import com.kidslauncher.mdm.server.ServerJson
import com.kidslauncher.mdm.server.createMdmApi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * The map links' handler (design 110 §7.1): a Google Maps or Apple Maps link tapped anywhere - Meldinger starts it
 * directly, other apps' links reach it through Android's link resolution - opens in Organic Maps (or CoMaps) as a pin
 * with the place's name and address, or as Organic Maps' offline search when no point is found.
 *
 * Translucent, never in Recents, no history, in the caller's task; the target starts in its own task, cleared unless
 * the target itself sent the link, so a running app opens every link ([MapLinks.clearsTask]). Installed disabled and
 * enabled only while the parent's switch is on ([MapLinksRuntime.setEnabled]). Over a time rule or the keyguard it
 * finishes showing nothing. A short link or a place id goes to the server (6 s at most, "Åpner kartet …" after 300 ms);
 * leaving cancels it, and nothing starts after that (a late start would be a blocked background start).
 */
class MapLinkActivity : AppCompatActivity() {
    private var job: Job? = null
    private var stopped = false
    private var dialog: AlertDialog? = null
    private var target: String? = null
    private var original: String? = null

    /** Not a page: night as a constant, as every such AppCompat activity (`PageLookScanTest`) - the kid's dialog look. */
    override fun attachBaseContext(newBase: Context) {
        delegate.localNightMode = AppCompatDelegate.MODE_NIGHT_YES
        super.attachBaseContext(newBase)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val raw = intent?.data?.toString()?.takeIf { it.length <= MAX_LINK }
        original = raw?.let(MapLinkForms::upgrade)
        val keyguard = getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
        val locked = keyguard || LauncherPreferences.mdm().lockReason() != LockReason.NONE
        val (pkg, state) = MapLinksRuntime.target(this)
        target = pkg
        val parsed = original?.let(MapLinkForms::parse) ?: MapParse.none()
        run(mapLinkPlan(locked, state, parsed), parsed)
    }

    private fun run(step: MapStep, parsed: MapParse) {
        if (stopped || isFinishing) return
        when (step) {
            MapStep.Nothing -> finish()
            is MapStep.Open -> open(step.uri)
            is MapStep.Note -> note(step)
            is MapStep.Ask -> ask(step.request, parsed)
        }
    }

    private fun open(uri: String) {
        val pkg = target ?: return note(MapStep.Note(MapNote.TARGET_MISSING))
        try {
            startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(uri)).setPackage(pkg)
                    .addCategory(Intent.CATEGORY_BROWSABLE)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    // A running app must still open every link with its card (MapLinks.clearsTask).
                    .addFlags(if (MapLinks.clearsTask(referrer?.host, pkg)) Intent.FLAG_ACTIVITY_CLEAR_TASK else 0)
                    .setIdentifier(MapLinks.startIdentity()),
            )
            finish()
        } catch (e: Exception) {
            Log.w(TAG, "Couldn't start the map app: ${e.javaClass.simpleName}")
            note(MapStep.Note(MapNote.TARGET_MISSING))
        }
    }

    private fun ask(request: MapResolveRequest, parsed: MapParse) {
        val app = applicationContext
        job = lifecycleScope.launch {
            val waiting = launch {
                delay(WAIT_NOTE_MS)
                dialog = AlertDialog.Builder(this@MapLinkActivity, R.style.KidAlertDialog)
                    .setMessage(R.string.map_links_waiting)
                    .setCancelable(true)
                    // Code QA L2: backing out stops the answer from starting anything.
                    .setOnCancelListener { leave() }
                    .show()
            }
            val (status, answer) = withContext(Dispatchers.IO) {
                withTimeoutOrNull(GIVE_UP_MS) { resolve(request) } ?: (0 to null)
            }
            waiting.cancel()
            dialog?.dismiss()
            if (status == 409) com.kidslauncher.mdm.push.SyncRunner.request(app, "map_links_off")
            run(mapLinkAfter(status, answer, parsed), parsed)
        }
    }

    /** The server's answer: its status and body (0 = no answer). */
    private suspend fun resolve(request: MapResolveRequest): Pair<Int, MapResolveAnswer?> = try {
        val url = LauncherPreferences.mdm().serverUrl()
        val token = DeviceCredentials.current(applicationContext)
        if (url.isNullOrBlank() || token == null) {
            0 to null
        } else {
            val body = ServerJson.encodeToString(MapResolveRequest.serializer(), request)
                .toRequestBody("application/json".toMediaType())
            val response = createMdmApi(url, token, callTimeoutMs = GIVE_UP_MS).resolveMapLink(body)
            val text = (if (response.isSuccessful) response.body() else response.errorBody())?.use { it.string() }
            response.code() to MapLinksRuntime.decodeAnswer(text)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "The map link's server call failed: ${e.javaClass.simpleName}")
        0 to null
    }

    private fun note(step: MapStep.Note) {
        if (stopped) return
        val name = MapLinksRuntime.targetName(target)
        val text = when (step.note) {
            MapNote.TARGET_MISSING -> getString(R.string.map_links_note_missing, name)
            MapNote.TARGET_SUSPENDED -> getString(R.string.map_links_note_suspended, name)
            MapNote.NO_PLACE -> getString(R.string.map_links_note_no_place)
            MapNote.LIST -> getString(R.string.map_links_note_list, name)
            MapNote.DEAD -> getString(R.string.map_links_note_dead)
            MapNote.RETRY -> getString(R.string.map_links_note_retry)
        }
        val builder = AlertDialog.Builder(this, R.style.KidAlertDialog)
            .setMessage(text)
            .setPositiveButton(android.R.string.ok) { _, _ -> finish() }
            .setOnCancelListener { finish() }
        // §7.2: during a pause or the parent code's override, the original link in a named browser.
        val windowOpen = RestrictionsPause.isActive() || OfflineOverride.isActive()
        val browser = if (windowOpen) MapLinksRuntime.browser(this) else null
        val link = original
        if (link != null && browserOffered(windowOpen, browser, packageName)) {
            builder.setNeutralButton(R.string.map_links_open_browser) { _, _ ->
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)).setPackage(browser).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (e: Exception) {
                    Log.w(TAG, "Couldn't open the browser: ${e.javaClass.simpleName}")
                }
                finish()
            }
        }
        dialog = builder.show()
    }

    private fun leave() {
        stopped = true
        job?.cancel()
        finish()
    }

    override fun onStop() {
        super.onStop()
        stopped = true
        job?.cancel()
        dialog?.dismiss()
        finish()
    }

    private companion object {
        const val TAG = "MapLinks"
        const val MAX_LINK = 2048
        const val WAIT_NOTE_MS = 300L
        const val GIVE_UP_MS = 6000L
    }
}
