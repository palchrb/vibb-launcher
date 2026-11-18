package com.kidslauncher.mdm.setup

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import com.kidslauncher.mdm.R
import com.kidslauncher.mdm.assistant.AirplaneOn
import com.kidslauncher.mdm.assistant.AirplaneRuntime
import com.kidslauncher.mdm.assistant.AssistantSetup
import com.kidslauncher.mdm.databinding.ActivitySetupFlowBinding
import com.kidslauncher.mdm.push.SyncRunner
import com.kidslauncher.mdm.server.AppEnforcer
import com.kidslauncher.mdm.server.ParentPinEntry
import com.kidslauncher.mdm.server.RelockTrigger
import com.kidslauncher.mdm.server.RestrictionsPause
import com.kidslauncher.mdm.server.SystemPageAccess
import com.kidslauncher.mdm.server.WindowRelock
import com.kidslauncher.mdm.server.systemPageAccess
import com.kidslauncher.mdm.ui.ParentPinDialog
import com.kidslauncher.mdm.ui.UIObjectActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val LOG_TAG = "SetupFlow"

/** The sync reason of the flow's returns, ticks and "Prøv igjen" (design 114 §4.1). */
const val SETUP_SYNC_REASON = "setup"

/**
 * Design 114 §6.2: the guided setup - one step a screen, in table order, over the missing steps that apply, read live.
 * In the setup hold Home leads here (guidance, not a lock: Back does nothing, Home brings this task back); from parent
 * Settings' "Oppsett" row and from the setup notification it opens too. Its own task (QA M2), so a Home press from
 * Android's page returns to the same screen; the run's skips live in [SetupRun]. Its only lasting acts are a tick of a
 * parent step and "Ferdig", which locks.
 */
class SetupFlowActivity : UIObjectActivity() {
    private lateinit var binding: ActivitySetupFlowBinding
    private var job: Job? = null
    private val handler = Handler(Looper.getMainLooper())

    /** The step whose page is open now (its window ends at the return). */
    private var openedStep: SetupStep? = null
    private var callFilterQueue: List<Intent> = emptyList()

    /** Design 98 §3.5: the Wi-Fi panel opened from here; its return (or a resume after a pause while it was up) ends
     * the step. The route's own voice activity pauses this screen before the panel opens - that resume must not. */
    private var wifiPanelShown = false
    private var wifiPanelPaused = false

    /** When the Wait screen first showed (its buttons come after [FLOW_WAIT_BUTTONS_MS], QA M3). */
    private var waitSinceMs: Long? = null

    /** The run was shown in the setup hold: when the hold ends, the flow closes and Home is the child's. */
    private var heldRun = false

    private val pageLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { pageReturned() }

    private val windowListener: () -> Unit = {
        if (!AssistantSetup.setupWindowOpen()) {
            SetupRun.reset()
            if (heldRun) finish() else refresh()
        } else {
            refresh()
        }
    }
    private val appliedListener: () -> Unit = { handler.post { refresh() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySetupFlowBinding.inflate(layoutInflater)
        setContentView(binding.root)
        if (intent?.getBooleanExtra(EXTRA_FROM_SETTINGS, false) == true && savedInstanceState == null) SetupRun.reset()
        // §6.2.1: Back does nothing while the hold lasts (a dispatcher callback: predictive back on 16 at targetSdk 36
        // never calls onBackPressed); otherwise it closes the flow.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!RestrictionsPause.holdActive()) finish()
            }
        })
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra(EXTRA_FROM_SETTINGS, false)) SetupRun.reset()
    }

    override fun onResume() {
        super.onResume()
        WindowRelock.addListener(windowListener)
        AppEnforcer.addAppliedListener(appliedListener)
        if (wifiPanelShown && wifiPanelPaused) endWifiStepNow("resume")
        refresh()
    }

    override fun onPause() {
        if (wifiPanelShown) wifiPanelPaused = true
        WindowRelock.removeListener(windowListener)
        AppEnforcer.removeAppliedListener(appliedListener)
        handler.removeCallbacksAndMessages(null)
        super.onPause()
    }

    /** One screen's state, read off the main thread. */
    private class State(
        val screen: FlowScreen,
        val access: SystemPageAccess?,
        val pages: List<Intent>,
        val progress: Pair<Int, Int>?,
        val windowOpen: Boolean,
        val hold: Boolean,
        val dialerRole: Boolean = false,
    )

    private fun compute(app: Context): State {
        val windowOpen = AssistantSetup.setupWindowOpen()
        val missing = SetupReadings.missing(app)
        val screens = flowScreens(missing, SetupRun.skipped(), windowOpen, SetupReadings.policyKnown())
        val first = screens.first()
        var pages = emptyList<Intent>()
        var access: SystemPageAccess? = null
        if (first is FlowScreen.Step) {
            val step = first.step
            SetupRun.markShown(step.key)
            pages = if (step == SetupStep.WIFI_AIRPLANE) {
                if (AirplaneRuntime.roleHeld(app)) SetupPages.intents(app, step) else emptyList()
            } else {
                SetupPages.intents(app, step)
            }
            access = systemPageAccess(
                pageAvailable = pages.isNotEmpty(),
                windowOpen = windowOpen,
                overrideApplied = AppEnforcer.lastEnforcedOverride == true,
                pageMissing = step.key in SetupRun.pageMissing(),
            )
        }
        val steps = screens.filterIsInstance<FlowScreen.Step>().map { it.step }
        val progress = if (first is FlowScreen.Step) flowProgress(SetupRun.shown(), steps) else null
        AssistantSetup.refreshNotice(app)
        val dialerRole = first is FlowScreen.Step && first.step == SetupStep.VIBB_NOTIFICATIONS &&
            com.kidslauncher.mdm.calls.CallSystem.dialerRoleHeld(app)
        return State(first, access, pages, progress, windowOpen, RestrictionsPause.holdActive(), dialerRole)
    }

    private fun refresh() {
        val app = applicationContext
        job?.cancel()
        job = lifecycleScope.launch {
            val state = withContext(Dispatchers.IO) {
                try {
                    compute(app)
                } catch (e: Exception) {
                    Log.w(LOG_TAG, "Reading the steps failed: ${e.javaClass.simpleName}")
                    null
                }
            } ?: return@launch
            render(state)
        }
    }

    private fun button(view: Button, text: Int?, action: (() -> Unit)?) {
        view.visibility = if (text == null) View.GONE else View.VISIBLE
        if (text == null) return
        view.setText(text)
        view.isEnabled = action != null
        view.setOnClickListener { action?.invoke() }
    }

    private fun render(state: State) {
        if (state.hold) heldRun = true
        binding.setupList.removeAllViews()
        binding.setupProgress.visibility = if (state.progress != null) View.VISIBLE else View.GONE
        state.progress?.let { (i, n) -> binding.setupProgress.text = getString(R.string.setup_flow_progress, i, n) }
        if (state.screen !is FlowScreen.Wait) waitSinceMs = null
        when (val screen = state.screen) {
            FlowScreen.NotOpen -> {
                binding.setupTitle.setText(R.string.settings_setup_title)
                binding.setupLine.text = ""
                button(binding.setupPrimary, R.string.setup_flow_pause) { pauseWithPin() }
                button(binding.setupSecondary, null, null)
                button(binding.setupTertiary, null, null)
            }
            FlowScreen.Wait -> {
                val since = waitSinceMs ?: SystemClock.elapsedRealtime().also { waitSinceMs = it }
                val waited = SystemClock.elapsedRealtime() - since
                binding.setupTitle.setText(R.string.setup_flow_wait)
                binding.setupLine.text = ""
                if (waited >= FLOW_WAIT_BUTTONS_MS) {
                    button(binding.setupPrimary, R.string.setup_flow_retry) {
                        SyncRunner.request(applicationContext, SETUP_SYNC_REASON)
                    }
                    button(binding.setupSecondary, R.string.setup_flow_finish) { finishFlow() }
                } else {
                    button(binding.setupPrimary, null, null)
                    button(binding.setupSecondary, null, null)
                    handler.postDelayed({ refresh() }, FLOW_WAIT_BUTTONS_MS - waited + 100)
                }
                button(binding.setupTertiary, null, null)
            }
            is FlowScreen.Step -> renderStep(screen.step, state)
            is FlowScreen.End -> {
                binding.setupTitle.text = if (screen.left == 0) {
                    getString(R.string.setup_flow_ready)
                } else {
                    resources.getQuantityString(R.plurals.setup_flow_left, screen.left, screen.left)
                }
                for (step in screen.leftSteps) {
                    val row = layoutInflater.inflate(android.R.layout.simple_list_item_1, binding.setupList, false) as TextView
                    row.setText(step.nameRes)
                    row.setOnClickListener {
                        SetupRun.unskip(step.key)
                        refresh()
                    }
                    binding.setupList.addView(row)
                }
                binding.setupLine.text = if (state.windowOpen) getString(R.string.setup_flow_finish_line) else ""
                button(binding.setupPrimary, R.string.setup_flow_finish) { finishFlow() }
                button(binding.setupSecondary, null, null)
                button(binding.setupTertiary, null, null)
            }
        }
    }

    private fun renderStep(step: SetupStep, state: State) {
        binding.setupTitle.setText(step.nameRes)
        val access = state.access
        binding.setupLine.text = when (access) {
            SystemPageAccess.WAIT -> getString(R.string.setup_flow_wait)
            SystemPageAccess.NO_PAGE -> getString(R.string.setup_flow_no_page)
            else -> listOfNotNull(
                step.howRes?.let { getString(it) },
                // Design 107's gate §6ax: while Vibb is the dialer Android greys an off channel's switch.
                getString(R.string.setup_how_vibb_notifications_role).takeIf { step == SetupStep.VIBB_NOTIFICATIONS && state.dialerRole },
            ).joinToString("\n")
        }
        val buttons = flowButtons(step, pageMissing = access == SystemPageAccess.NO_PAGE)
        val views = listOf(binding.setupPrimary, binding.setupSecondary, binding.setupTertiary)
        for ((i, view) in views.withIndex()) {
            val which = buttons.getOrNull(i)
            when (which) {
                null -> button(view, null, null)
                FlowButton.OPEN -> button(view, R.string.setup_flow_open, if (access == SystemPageAccess.CAN_OPEN) ({ open(step, state.pages) }) else null)
                FlowButton.LATER -> button(view, R.string.setup_flow_later) { skip(step) }
                FlowButton.SKIP -> button(view, R.string.setup_flow_skip) { skip(step) }
                FlowButton.DID_IT -> button(view, R.string.setup_flow_did_it) { tick(step) }
            }
        }
    }

    private fun skip(step: SetupStep) {
        SetupRun.skip(step.key)
        refresh()
    }

    /** §4.4: "Gjort" - the tick waits in the pending set (the row leaves at once) and goes with the next sync. */
    private fun tick(step: SetupStep) {
        val app = applicationContext
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { SetupTicks.add(app, step) }
            SyncRunner.request(app, SETUP_SYNC_REASON)
            refresh()
        }
    }

    /** "Sett på pause i 2 t": the parent PIN through the gate, as Settings' switch. */
    private fun pauseWithPin() {
        ParentPinDialog.show(
            this,
            lifecycleScope,
            ParentPinEntry.PAUSE,
            title = R.string.settings_mdm_restrictions_paused_pin_title,
            notConfigured = R.string.toast_mdm_pause_needs_pin,
        ) { refresh() }
    }

    /** "Ferdig" (§6.1.3): ends the window if one is open - the hold or a pause - before the flow finishes into Home
     * (QA M2: synchronous, so Home's resume can't still see the hold); without a window it only closes. */
    private fun finishFlow() {
        val app = applicationContext
        if (AssistantSetup.setupWindowOpen()) {
            try {
                WindowRelock.lockAgain(app, RelockTrigger.LOCK_AGAIN)
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Lock again failed", e)
            }
        }
        SetupRun.reset()
        heldRun = false
        finish()
    }

    /** "Åpne": the step's page, inside design 98's windows where the step needs one. */
    private fun open(step: SetupStep, pages: List<Intent>) {
        val app = applicationContext
        when (step) {
            SetupStep.ASSISTANT, SetupStep.DEFAULT_APPS -> lifecycleScope.launch {
                // Row 14 (QA H1): the window opened without a new snapshot, so the page is usable and the comparison
                // stays against the original holders.
                withContext(Dispatchers.IO) { AssistantSetup.openPickWindow(app, keepSnapshot = step == SetupStep.DEFAULT_APPS) }
                launchPages(step, pages)
            }
            SetupStep.CALL_FILTER -> lifecycleScope.launch {
                val requests = withContext(Dispatchers.IO) {
                    AssistantSetup.openPickWindow(app)
                    AssistantSetup.screeningRoleIntents(app)
                }
                callFilterQueue = requests.drop(1)
                val first = requests.firstOrNull()
                if (first == null || !launch(step, first)) AssistantSetup.endPickWindow(app, "call_filter")
            }
            SetupStep.WIFI_AIRPLANE -> lifecycleScope.launch {
                val result = withContext(Dispatchers.IO) { AssistantSetup.startWifiStep(app) }
                if (result != AirplaneOn.GO) {
                    Toast.makeText(app, R.string.settings_wifi_apm_failed, Toast.LENGTH_LONG).show()
                } else {
                    wifiPanelShown = true
                    wifiPanelPaused = false
                    if (!launch(step, AssistantSetup.wifiPanelIntent())) endWifiStepNow("no_panel")
                }
            }
            else -> launchPages(step, pages)
        }
    }

    private fun launch(step: SetupStep, intent: Intent): Boolean = try {
        openedStep = step
        pageLauncher.launch(intent)
        true
    } catch (e: Exception) {
        Log.w(LOG_TAG, "Couldn't open ${intent.action}: ${e.javaClass.simpleName}")
        openedStep = null
        false
    }

    /** The first of [pages] Android finds; every one missing makes the step NO_PAGE for the run. */
    private fun launchPages(step: SetupStep, pages: List<Intent>) {
        for (page in pages) {
            try {
                openedStep = step
                pageLauncher.launch(page)
                return
            } catch (e: ActivityNotFoundException) {
                Log.w(LOG_TAG, "No page for ${page.action}, trying the next")
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Couldn't open the page for ${page.action}: ${e.javaClass.simpleName}")
                return
            }
        }
        openedStep = null
        SetupRun.markPageMissing(step.key)
        refresh()
    }

    /** Back from Android's page (by Back, or Home bringing this task back): the step's window ends, the step is read
     * again - done moves on, not done stays - and the PWA hears of it at once. */
    private fun pageReturned() {
        val app = applicationContext
        val step = openedStep
        openedStep = null
        when (step) {
            SetupStep.ASSISTANT -> {
                AssistantSetup.endPickWindow(app, "page")
                AssistantSetup.roleMaybeChanged(app)
            }
            SetupStep.DEFAULT_APPS -> AssistantSetup.endPickWindow(app, "page")
            SetupStep.CALL_FILTER -> {
                val next = callFilterQueue.firstOrNull()
                callFilterQueue = callFilterQueue.drop(1)
                if (next != null && launch(SetupStep.CALL_FILTER, next)) return
                AssistantSetup.endPickWindow(app, "call_filter")
            }
            SetupStep.WIFI_AIRPLANE -> {
                endWifiStepNow("result")
                return
            }
            else -> Unit
        }
        SyncRunner.request(app, SETUP_SYNC_REASON)
        refresh()
    }

    private fun endWifiStepNow(why: String) {
        wifiPanelShown = false
        wifiPanelPaused = false
        val app = applicationContext
        lifecycleScope.launch {
            withContext(Dispatchers.IO) { AssistantSetup.endWifiStep(app, why) }
            refresh()
        }
    }

    companion object {
        private const val EXTRA_FROM_SETTINGS = "me.vibb.launcher.extra.SETUP_FROM_SETTINGS"

        /** The notifications' tap (the setup notice, the hold's window notice): its own task. */
        fun intent(context: Context): Intent =
            Intent(context, SetupFlowActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        /** §6.2.1: from Home's resume during the hold - a foreground start; the flow's task comes to the front. */
        fun startFromHome(activity: Activity) {
            try {
                activity.startActivity(intent(activity))
            } catch (e: Exception) {
                Log.w(LOG_TAG, "Couldn't start the setup flow: ${e.javaClass.simpleName}")
            }
        }

        /** §6.3: parent Settings' "Oppsett" row - a new run. */
        fun startFromSettings(activity: Activity) {
            activity.startActivity(Intent(activity, SetupFlowActivity::class.java).putExtra(EXTRA_FROM_SETTINGS, true))
        }
    }
}
