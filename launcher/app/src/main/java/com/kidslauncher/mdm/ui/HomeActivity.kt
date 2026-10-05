package com.kidslauncher.mdm.ui

import android.app.ActivityManager
import android.content.Intent
import android.content.SharedPreferences
import android.database.ContentObserver
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.CallLog
import android.graphics.Bitmap
import android.graphics.Rect
import android.view.Gravity
import android.view.GestureDetector
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.lifecycle.Observer
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.app.role.RoleManager
import com.kidslauncher.mdm.Application
import com.kidslauncher.mdm.R
import com.kidslauncher.mdm.lock.LockMode
import com.kidslauncher.mdm.lock.PinLockRuntime
import com.kidslauncher.mdm.apps.AbstractDetailedAppInfo
import com.kidslauncher.mdm.apps.AppFilter
import com.kidslauncher.mdm.apps.AppInfo
import com.kidslauncher.mdm.badges.BadgeStore
import com.kidslauncher.mdm.calls.CallPolicyStore
import com.kidslauncher.mdm.calls.CallPrefs
import com.kidslauncher.mdm.calls.CallSystem
import com.kidslauncher.mdm.calls.ContactPhotos
import com.kidslauncher.mdm.calls.ContactSheet
import com.kidslauncher.mdm.calls.MissedCallsRepo
import com.kidslauncher.mdm.calls.MissedSummary
import com.kidslauncher.mdm.calls.RuleContact
import com.kidslauncher.mdm.calls.phoneBookView
import com.kidslauncher.mdm.calls.shouldPromptForRole
import com.kidslauncher.mdm.databinding.ActivityHomeBinding
import com.kidslauncher.mdm.server.CachedPolicy
import com.kidslauncher.mdm.server.cachedPolicy
import com.kidslauncher.mdm.server.LockReason
import com.kidslauncher.mdm.server.TsnetClient
import com.kidslauncher.mdm.server.reevaluateLockReasonFromCache
import com.kidslauncher.mdm.openAppsList
import com.kidslauncher.mdm.preferences.LauncherPreferences
import com.kidslauncher.mdm.requestNotificationPermission
import com.kidslauncher.mdm.setDefaultHomeScreen
import com.kidslauncher.mdm.ui.home.GRID_COLUMN_GAP_DP
import com.kidslauncher.mdm.ui.home.GRID_ROW_GAP_DP
import com.kidslauncher.mdm.ui.home.GridApp
import com.kidslauncher.mdm.ui.home.GridGapDecoration
import com.kidslauncher.mdm.ui.home.GridMetrics
import com.kidslauncher.mdm.ui.home.MOCKUP_CONTENT_DP
import com.kidslauncher.mdm.ui.home.contactRow
import com.kidslauncher.mdm.ui.home.gridMetrics
import com.kidslauncher.mdm.ui.home.HomeGridAdapter
import com.kidslauncher.mdm.ui.home.KidAvatars
import com.kidslauncher.mdm.ui.home.gridColumns
import com.kidslauncher.mdm.ui.home.homeGrid
import com.kidslauncher.mdm.ui.home.showPhoneBookTile
import com.kidslauncher.mdm.ui.wallpaper.InkChoice
import com.kidslauncher.mdm.ui.wallpaper.KidInk
import com.kidslauncher.mdm.ui.wallpaper.WallpaperGround
import com.kidslauncher.mdm.ui.wallpaper.WallpaperStore
import com.kidslauncher.mdm.ui.kidsettings.KidSettingsActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.abs

private const val SWIPE_UP_MIN_DISTANCE = 100
private const val SWIPE_UP_MIN_VELOCITY = 100
private const val SWIPE_LEFT_MIN_DISTANCE = 100
private const val SWIPE_LEFT_MIN_VELOCITY = 100
private const val BADGE_DEBOUNCE_MS = 300L

/**
 * [HomeActivity] is the actual application launcher (design 05-ui-photos-i18n.md, mockup
 * Main.dc.html, polish round 08-ui-polish.md): no clock (the status bar has it), the parent's Home
 * contacts as big round call buttons with missed-call badges, then a grid with the phone book,
 * every app the kid may use (the same [AppFilter] as the drawer) as coloured circles with unread
 * badges, and the kid's Settings. Swiping up still opens the drawer (nothing more than the grid,
 * plus the PIN-gated Settings); swiping left opens the kid's Settings. The lock screen, kiosk and
 * role checks in [onResume] run exactly as before the redesign.
 */
class HomeActivity : UIObjectActivity() {

    private lateinit var binding: ActivityHomeBinding
    private lateinit var gridAdapter: HomeGridAdapter
    private lateinit var gridLayout: GridLayoutManager
    private lateinit var gestureDetector: GestureDetector

    private val apps by lazy { (applicationContext as Application).apps }
    private val appsObserver = Observer<List<AbstractDetailedAppInfo>> { render() }
    // Chatty apps change their counts often: coalesce, and only rebuild the grid from the
    // already-filtered apps (QA step 5 #6).
    private val badgeRender = Runnable { renderGrid() }
    private val badgeListener: () -> Unit = {
        refreshHandler.removeCallbacks(badgeRender)
        refreshHandler.postDelayed(badgeRender, BADGE_DEBOUNCE_MS)
    }
    private val photoListener: () -> Unit = { renderCallParts() }
    private val wallpaperListener: () -> Unit = { renderWallpaper() }

    /** What [refreshApps] last produced off the main thread. */
    private class GridData(
        val apps: List<GridApp>,
        val infos: Map<String, AbstractDetailedAppInfo>,
        val icons: Map<String, Bitmap>,
        val metrics: GridMetrics,
        val columns: Int,
    )
    private var gridData = GridData(emptyList(), emptyMap(), emptyMap(), gridMetrics(MOCKUP_CONTENT_DP, 3), 3)
    private var showPhoneBook = false
    private var appsJob: Job? = null
    private var missed: Map<String, MissedSummary> = emptyMap()
    private val callLogObserver = object : ContentObserver(Handler(Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) = loadMissedCalls()
    }

    private var sharedPreferencesListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, prefKey ->
            if (prefKey?.startsWith("display.") == true) {
                recreate()
            } else if (prefKey == LauncherPreferences.mdm().keys().lockReason()) {
                redirectToLockScreenIfLocked()
            } else if (prefKey == LauncherPreferences.mdm().keys().kioskEnabled()) {
                reconcileKioskMode()
            } else if (prefKey == LauncherPreferences.mdm().keys().kidModePolicy()) {
                // A new policy may change the contacts, the columns or the wallpapers; the sync
                // refreshes the stores too, but this listener can run before it does.
                CallPolicyStore.refresh(this)
                WallpaperStore.refreshAsync(this)
                render()
                loadMissedCalls()
            } else {
                // apps.hidden (hidden from the long-press menu) and apps.custom_names (renamed).
                render()
            }
        }

    // Badge debounce. No schedule polling any more: rule boundaries come from one exact alarm
    // (timerules.TimeRuleAlarm), which updates lock_reason - the listener above redirects.
    private val refreshHandler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Initialise layout
        binding = ActivityHomeBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // No clock or date any more (design 08): the status bar shows the time.
        gridAdapter = HomeGridAdapter(this)
        gridLayout = GridLayoutManager(this, gridColumns(null))
        binding.homeGrid.layoutManager = gridLayout
        binding.homeGrid.adapter = gridAdapter
        binding.homeGrid.addItemDecoration(
            GridGapDecoration(
                KidAvatars.dp(this, GRID_COLUMN_GAP_DP.toFloat()),
                KidAvatars.dp(this, GRID_ROW_GAP_DP.toFloat()),
            ) { gridLayout.spanCount }
        )
        apps.observeForever(appsObserver)

        // Back does nothing on the home screen, same as stock Android launchers.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {}
        })

        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                if (e1 == null) return false
                val diffY = e2.y - e1.y
                val diffX = e2.x - e1.x
                // Only once the grid can't scroll further down, so scrolling a long grid
                // doesn't open the drawer. The drawer shows the same apps as the grid (same
                // AppFilter), plus the PIN-gated Settings button.
                if (abs(diffY) > abs(diffX) &&
                    -diffY > SWIPE_UP_MIN_DISTANCE &&
                    abs(velocityY) > SWIPE_UP_MIN_VELOCITY &&
                    !binding.homeGrid.canScrollVertically(1)
                ) {
                    openAppsList(this@HomeActivity)
                    return true
                }
                // The kid's own settings (also the grid's last tile) - the replacement for
                // Android's Quick Settings shade. A swipe that starts in the contacts row only
                // counts once that row can't scroll further (same rule as the grid's swipe-up).
                if (abs(diffX) > abs(diffY) &&
                    -diffX > SWIPE_LEFT_MIN_DISTANCE &&
                    abs(velocityX) > SWIPE_LEFT_MIN_VELOCITY &&
                    !(startedInContactsRow(e1) && binding.homeContactsScroll.canScrollHorizontally(1))
                ) {
                    startActivity(Intent(this@HomeActivity, KidSettingsActivity::class.java))
                    return true
                }
                return false
            }
        })

        // The Activity-level onTouchEvent() below only ever sees touches nobody else claimed -
        // it's a last resort, called only if the whole view hierarchy declines an event. The
        // grid's tiles are clickable, so a swipe starting on top of one (as opposed to the blank
        // space around them) gets consumed entirely by that tile's own click handling
        // and never reaches onTouchEvent() at all. RecyclerView.OnItemTouchListener is the
        // official hook for exactly this: it's invoked for every event that flows through the
        // RecyclerView, before it's dispatched to a child row. Always returning false here means
        // it's purely observing (not stealing the gesture from clicks/scrolling) - a real drag
        // already exceeds the framework's own touch-slop threshold, which independently cancels a
        // pending click on the row without any help from this listener.
        binding.homeGrid.addOnItemTouchListener(object : RecyclerView.OnItemTouchListener {
            override fun onInterceptTouchEvent(rv: RecyclerView, e: MotionEvent): Boolean {
                gestureDetector.onTouchEvent(e)
                return false
            }

            override fun onTouchEvent(rv: RecyclerView, e: MotionEvent) {}

            override fun onRequestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {}
        })
        // Same for the Home contacts row: observe only, the buttons keep their clicks.
        binding.homeContactsScroll.setOnTouchListener { _, event ->
            gestureDetector.onTouchEvent(event)
            false
        }
    }

    /** Whether a gesture's down event was inside the contacts row (screen coordinates: the
     * detector gets events from the row, the grid and the activity, each in its own space). */
    private fun startedInContactsRow(down: MotionEvent): Boolean {
        val row = binding.homeContactsScroll
        if (row.visibility != View.VISIBLE) return false
        val rect = Rect()
        if (!row.getGlobalVisibleRect(rect)) return false
        return rect.contains(down.rawX.toInt(), down.rawY.toInt())
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        gestureDetector.onTouchEvent(event)
        return true
    }

    override fun onStart() {
        super.onStart()

        // First launch: mark it done and try to set the default home screen
        if (!LauncherPreferences.internal().started()) {
            LauncherPreferences.internal().started(true)
            LauncherPreferences.internal().startedTime(System.currentTimeMillis() / 1000L)
            setDefaultHomeScreen(this, checkDefault = true)
            requestNotificationPermission(this)
        }

        LauncherPreferences.getSharedPreferences()
            .registerOnSharedPreferenceChangeListener(sharedPreferencesListener)
        BadgeStore.addListener(badgeListener)
        ContactPhotos.addListener(photoListener)
        WallpaperStore.addListener(wallpaperListener)
        try {
            contentResolver.registerContentObserver(CallLog.Calls.CONTENT_URI, true, callLogObserver)
        } catch (e: SecurityException) {
            // No READ_CALL_LOG (calls not managed): no missed-call badges.
        }
    }

    override fun onResume() {
        super.onResume()
        // Deliberately triggered here, not from Application.onCreate() - see TsnetClient's own
        // doc comment on the GrapheneOS hardened_malloc / native-crash risk this sidesteps by
        // waiting until the launcher has actually rendered instead of racing the very first UI
        // paint after unlock. connectFromPreferences() already no-ops once connected (or with no
        // auth key configured), so calling it on every resume - not just the first - is safe, not
        // wasteful; MdmSyncWorker's regular sync cycle is the retry-until-connected backstop
        // either way.
        CoroutineScope(Dispatchers.IO).launch { TsnetClient.connectFromPreferences(this@HomeActivity) }
        // Fresh check against the clock every time the home screen comes to the foreground, on
        // top of the boundary alarm - cheap, and covers an alarm that was late or refused.
        reevaluateLockReasonFromCache(this@HomeActivity)
        // Must run before the lock-screen check below: while the bedtime/screen-time block is
        // showing is exactly when kiosk pinning should also be engaged, so the kid can't use
        // recents/home/notification-shade to route around LockActivity.
        reconcileKioskMode()
        // Checked here (not just via the preference listener) so pressing Home while the lock
        // screen is showing can't be used to bounce back into the drawer/home list underneath it.
        if (redirectToLockScreenIfLocked()) return
        // Handy's PIN lock (step 10): Home in front while LOCKED means the lock lost the front.
        if (PinLockRuntime.mode == LockMode.LOCKED) {
            PinLockRuntime.show(this)
            return
        }
        // The parent's language choice, now that Home is in front (no call screen or dialog).
        LauncherLocales.applyIfSafe(this)
        // Also refreshes whether the system wallpaper is still ours (in the background).
        WallpaperStore.refreshAsync(this)
        renderWallpaper()
        render()
        loadMissedCalls()
        promptForCallRoleIfNeeded()
    }

    /**
     * The system's role prompts, at most once a day, for the parent to accept: the dialer role
     * when the device-owner call couldn't take it (an error was recorded), and call redirection,
     * which has no device-owner API (unless granted with adb at provisioning).
     */
    private fun promptForCallRoleIfNeeded() {
        val state = CallPolicyStore.state
        val now = System.currentTimeMillis()
        val dialerHeld = CallSystem.dialerRoleHeld(this)
        val role = when {
            !dialerHeld && CallPrefs.lastError(this) != null -> RoleManager.ROLE_DIALER
            dialerHeld && !CallSystem.redirectionRoleHeld(this) -> RoleManager.ROLE_CALL_REDIRECTION
            else -> return
        }
        if (!shouldPromptForRole(state, roleHeld = false, now, CallPrefs.rolePromptLastMs(this))) return
        CallPrefs.rolePromptLastMs(this, now)
        try {
            val roleManager = getSystemService(RoleManager::class.java) ?: return
            startActivity(roleManager.createRequestRoleIntent(role))
        } catch (e: Exception) {
            android.util.Log.w("HomeActivity", "Couldn't show the $role prompt", e)
        }
    }

    /** @return true if currently locked (and [LockActivity] was launched). */
    private fun redirectToLockScreenIfLocked(): Boolean {
        if (LauncherPreferences.mdm().lockReason() != LockReason.NONE) {
            // Brings the PIN lock back on top too while it is LOCKED.
            LockActivity.start(this)
            return true
        }
        return false
    }

    /**
     * Entering lock-task mode is never automatic on the OS side (only removing the pinned
     * package from DevicePolicyManager.setLockTaskPackages() auto-exits it) - AppEnforcer only
     * configures the DPM-side state from a background Worker, so an Activity has to actually
     * call startLockTask()/stopLockTask() to enter/exit. Runs on every onResume() since neither
     * the pinned state nor this check survives reboot/process death on their own.
     */
    private fun reconcileKioskMode() {
        val activityManager = getSystemService(ACTIVITY_SERVICE) as ActivityManager
        val currentlyLocked = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            activityManager.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE
        } else {
            @Suppress("DEPRECATION")
            activityManager.isInLockTaskMode
        }
        val shouldBeLocked = LauncherPreferences.mdm().kioskEnabled()

        if (shouldBeLocked && !currentlyLocked) {
            startLockTask()
        } else if (!shouldBeLocked && currentlyLocked && PinLockRuntime.mode != LockMode.LOCKED) {
            // With the kiosk off, a running lock task while LOCKED is the PIN lock's own (step
            // 10) - never stopped from here.
            stopLockTask()
        }
    }

    override fun onStop() {
        BadgeStore.removeListener(badgeListener)
        refreshHandler.removeCallbacks(badgeRender)
        ContactPhotos.removeListener(photoListener)
        WallpaperStore.removeListener(wallpaperListener)
        contentResolver.unregisterContentObserver(callLogObserver)
        super.onStop()
    }

    override fun onDestroy() {
        LauncherPreferences.getSharedPreferences()
            .unregisterOnSharedPreferenceChangeListener(sharedPreferencesListener)
        apps.removeObserver(appsObserver)
        super.onDestroy()
    }

    /** Missed calls per contact, from the call log on a background thread. */
    private fun loadMissedCalls() {
        val state = CallPolicyStore.state
        CoroutineScope(Dispatchers.Main).launch {
            val result = withContext(Dispatchers.IO) { MissedCallsRepo.summaries(this@HomeActivity, state) }
            if (result != missed && !isDestroyed) {
                missed = result
                renderCallParts()
            }
        }
    }

    /** Everything: the call parts now, the apps filtered again in the background. */
    private fun render() {
        if (!::gridAdapter.isInitialized) return
        renderCallParts()
        refreshApps()
    }

    /** Contacts row and phone-book tile from the call rules, missed calls and photos (cheap). */
    private fun renderCallParts() {
        if (!::gridAdapter.isInitialized) return
        val state = CallPolicyStore.state
        val view = phoneBookView(state) { CallSystem.isEmergencyOutgoing(this, it) }
        renderContacts(view.home)
        // Managed (even with calls off) or unknown: the tile is there and the phone book explains.
        showPhoneBook = showPhoneBookTile(state)
        renderGrid()
    }

    /** The grid's content width: the screen minus Home's 16 dp side padding. */
    private fun contentWidthDp(): Float = (resources.configuration.screenWidthDp - 32).toFloat()

    /**
     * [AppFilter] (a `isPackageSuspended` call per app), the cached policy (columns) and the
     * rendered icons ([KidAvatars.renderAppIcon]: loading, palette, drawing) off the main thread;
     * the newest run wins. `onBind` only reads the finished bitmaps (QA 08 #5).
     */
    private fun refreshApps() {
        val all = apps.value ?: return
        val context = applicationContext
        val width = contentWidthDp()
        appsJob?.cancel()
        appsJob = CoroutineScope(Dispatchers.Main).launch {
            val result = withContext(Dispatchers.Default) {
                val visible = AppFilter(context).invoke(all)
                    .filter { (it.getRawInfo() as? AppInfo)?.packageName != packageName }
                val infos = visible.associateBy { it.getRawInfo().serialize() }
                val list = infos.map { (key, info) ->
                    GridApp(key, info.getCustomLabel(context), (info.getRawInfo() as? AppInfo)?.packageName)
                }
                val columns = gridColumns((cachedPolicy() as? CachedPolicy.Ok)?.policy?.launcherUi?.homeColumns)
                val metrics = gridMetrics(width, columns)
                val sizePx = KidAvatars.dp(context, metrics.iconDp.toFloat())
                val icons = HashMap<String, Bitmap>()
                for ((key, info) in infos) {
                    ensureActive()
                    try {
                        icons[key] = KidAvatars.renderAppIcon(context, key, { info.getIcon(context) }, sizePx)
                    } catch (e: Exception) {
                        android.util.Log.w("HomeActivity", "Couldn't draw the icon of $key", e)
                    }
                }
                GridData(list, infos, icons, metrics, columns)
            }
            if (isDestroyed) return@launch
            gridData = result
            if (gridLayout.spanCount != result.columns) {
                gridLayout.spanCount = result.columns
                binding.homeGrid.invalidateItemDecorations()
            }
            renderGrid()
        }
    }

    /**
     * The wallpaper behind Home ([WallpaperGround]: transparent over the system wallpaper when it
     * is ours) and its ink on the labels and status-bar icons (design 08 §3). Cheap: the store
     * decoded everything in the background.
     */
    private fun renderWallpaper() {
        if (!::gridAdapter.isInitialized) return
        val state = WallpaperGround.apply(this, binding.root)
        if (state.ink != shownInk) {
            shownInk = state.ink
            renderCallParts()
        }
    }

    private var shownInk: InkChoice = WallpaperStore.state.ink

    /** The grid from the last filtered apps and the current badge counts (cheap). */
    private fun renderGrid() {
        if (!::gridAdapter.isInitialized) return
        val data = gridData
        gridAdapter.submit(
            homeGrid(data.apps, showPhoneBook, BadgeStore.counts),
            data.infos,
            data.icons,
            data.metrics,
            shownInk,
        )
    }

    private fun renderContacts(contacts: List<RuleContact>) {
        val row = binding.homeContacts
        row.removeAllViews()
        binding.homeContactsScroll.visibility = if (contacts.isEmpty()) View.GONE else View.VISIBLE
        val layout = contactRow(contacts.size, contentWidthDp())
        // Centred while everything fits, from the left (with the peek at the edge) when it scrolls.
        row.gravity = if (layout.scrolls) Gravity.START else Gravity.CENTER_HORIZONTAL
        val inflater = LayoutInflater.from(this)
        for (contact in contacts) {
            val item = inflater.inflate(R.layout.item_kid_contact, row, false)
            val emergency = CallSystem.isEmergencyOutgoing(this, contact.number)
            KidAvatars.sizeContact(item, layout.avatarDp)
            KidAvatars.bindContact(
                item.findViewById<ImageView>(R.id.contact_photo),
                item.findViewById<TextView>(R.id.contact_initial),
                contact, emergency, initialSp = 30f * layout.avatarDp / 76f,
            )
            item.findViewById<View>(R.id.contact_call_badge).visibility = View.VISIBLE
            val missedCount = missed[contact.number]?.count ?: 0
            KidAvatars.bindBadge(item.findViewById(R.id.contact_badge), missedCount)
            item.findViewById<TextView>(R.id.contact_name).apply {
                text = contact.name
                KidInk.label(this, shownInk)
                maxWidth = KidAvatars.dp(this@HomeActivity, layout.itemDp.toFloat())
            }
            item.contentDescription = if (missedCount > 0) {
                resources.getQuantityString(R.plurals.call_contact_missed, missedCount, contact.name, missedCount)
            } else {
                getString(R.string.call_contact, contact.name)
            }
            item.setOnClickListener {
                // Tapping calls straight away (02 decision); calling back deals with missed calls.
                MissedCallsRepo.markSeen(this, contact.number)
                CallSystem.placeCall(this, contact.number)
                loadMissedCalls()
            }
            item.setOnLongClickListener {
                ContactSheet.show(this, contact, missed[contact.number]) { loadMissedCalls() }
                true
            }
            // Each item is one avatar plus one gap wide, so avatars are a gap apart.
            item.layoutParams = LinearLayout.LayoutParams(
                KidAvatars.dp(this, layout.itemDp.toFloat()), LinearLayout.LayoutParams.WRAP_CONTENT,
            )
            row.addView(item)
        }
    }

    override fun isHomeScreen(): Boolean {
        return true
    }
}
