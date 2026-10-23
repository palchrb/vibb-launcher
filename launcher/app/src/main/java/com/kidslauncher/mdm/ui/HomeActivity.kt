package com.kidslauncher.mdm.ui

import android.app.ActivityManager
import android.app.Dialog
import android.content.Intent
import android.content.SharedPreferences
import android.database.ContentObserver
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.CallLog
import android.graphics.Bitmap
import android.graphics.Color
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
import android.app.role.RoleManager
import com.kidslauncher.mdm.Application
import com.kidslauncher.mdm.R
import com.kidslauncher.mdm.lock.HomeFront
import com.kidslauncher.mdm.lock.LockAsk
import com.kidslauncher.mdm.lock.LockMode
import com.kidslauncher.mdm.lock.PinLockRuntime
import com.kidslauncher.mdm.lock.homeDarkResume
import com.kidslauncher.mdm.lock.homeShowsContent
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
import com.kidslauncher.mdm.calls.rolePromptFor
import com.kidslauncher.mdm.calls.shouldPromptForRole
import com.kidslauncher.mdm.databinding.ActivityHomeBinding
import com.kidslauncher.mdm.server.CachedPolicy
import com.kidslauncher.mdm.server.cachedPolicy
import com.kidslauncher.mdm.server.LockReason
import com.kidslauncher.mdm.server.TsnetClient
import com.kidslauncher.mdm.server.reevaluateLockReasonFromCache
import com.kidslauncher.mdm.openAppsList
import com.kidslauncher.mdm.play.PlayRuntime
import com.kidslauncher.mdm.play.playPackageOnHome
import com.kidslauncher.mdm.preferences.LauncherPreferences
import com.kidslauncher.mdm.requestNotificationPermission
import com.kidslauncher.mdm.setDefaultHomeScreen
import com.kidslauncher.mdm.ui.home.GRID_COLUMN_GAP_DP
import com.kidslauncher.mdm.ui.home.GRID_ROW_GAP_DP
import com.kidslauncher.mdm.ui.home.GridApp
import com.kidslauncher.mdm.ui.home.GridGapDecoration
import com.kidslauncher.mdm.ui.home.GridMetrics
import com.kidslauncher.mdm.ui.home.MOCKUP_CONTENT_DP
import com.kidslauncher.mdm.ui.home.contactItemPx
import com.kidslauncher.mdm.ui.home.contactsContentPx
import com.kidslauncher.mdm.ui.home.contactRow
import com.kidslauncher.mdm.ui.home.gridMetrics
import com.kidslauncher.mdm.ui.home.HomeGridAdapter
import com.kidslauncher.mdm.ui.home.KidAvatars
import com.kidslauncher.mdm.ui.home.NightGround
import com.kidslauncher.mdm.ui.home.PressFeedback
import com.kidslauncher.mdm.ui.home.gridColumns
import com.kidslauncher.mdm.ui.home.homeGrid
import com.kidslauncher.mdm.ui.home.homePressToTop
import com.kidslauncher.mdm.ui.home.showPhoneBookTile
import com.kidslauncher.mdm.ui.wallpaper.InkChoice
import com.kidslauncher.mdm.ui.wallpaper.KidInk
import com.kidslauncher.mdm.ui.wallpaper.WallpaperGround
import com.kidslauncher.mdm.ui.wallpaper.WallpaperStore
import com.kidslauncher.mdm.ui.home.HomeSwipe
import com.kidslauncher.mdm.ui.home.SwipeStart
import com.kidslauncher.mdm.ui.home.homeSwipe
import com.kidslauncher.mdm.ui.kidsettings.KidSettingsActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val BADGE_DEBOUNCE_MS = 300L
private const val MAX_OVERLAYS = 8

/**
 * [HomeActivity] is the actual application launcher (design 05-ui-photos-i18n.md, mockup
 * Main.dc.html, polish round 08-ui-polish.md): no clock (the status bar has it), the parent's Home
 * contacts as big round call buttons with missed-call badges, then a grid with the phone book,
 * every app the kid may use (the same [AppFilter] as the drawer) as coloured circles with unread
 * badges, and the kid's Settings. Swiping up still opens the drawer (nothing more than the grid,
 * plus the PIN-gated Settings); swiping left opens the kid's Settings. The lock screen, kiosk and
 * role checks in [onResume] run exactly as before the redesign.
 *
 * Design 16c: none of that exists while the PIN lock is LOCKED, or not decided yet with a kid PIN
 * set ([homeShowsContent]) - Home is then the night ground with the breathing Vibb mark
 * ([NightGround]), with nothing to touch, and its resume roots lock task and shows the lock. The
 * content is inflated and bound only on the UNLOCKED edge ([showContent]) and taken off the window
 * again on the LOCKED one ([showNight]).
 *
 * Design 32: at a lock-up while dark Home is brought under the lock, so what WM shows before the
 * lock's first frame at the next screen-on is Home's snapshot - plain black (no recents screenshot,
 * a black task description), never the app the kid was in. That resume only shows the night ground
 * ([homeDarkResume]); the rest waits for an interactive start or resume.
 */
class HomeActivity : UIObjectActivity() {

    /** Home's content - inflated on the first UNLOCKED edge only ([showContent], 16c). */
    private lateinit var binding: ActivityHomeBinding
    private lateinit var gridAdapter: HomeGridAdapter
    private lateinit var gridLayout: GridLayoutManager
    private lateinit var gestureDetector: GestureDetector
    /** The current touch's DOWN, read when it landed: where, and whether the grid and the row could
     * still scroll on (qa-r1-apps-code L1) - what [homeSwipe] decides the fling with. */
    private var downStart = SwipeStart.BLANK
    private var downGridCanScroll = false
    private var downRowCanScroll = false

    /** The night ground while locked (16c). */
    private var night: NightGround? = null
    /** The content is on the window (contacts, grid, call card, gestures); every render checks it. */
    private var contentShown = false
    private var nightShown = false
    private var resumedNow = false
    /** Home's content was resumed in front and Home hasn't stopped since (launcher round 7 §4): a
     * HOME intent then is the kid pressing Home on Home ([homePressToTop]). */
    private var contentUp = false
    /** What Home opened over its content - the contact sheet, an app's long-press menu and its
     * rename dialog: closed when the lock engages (16c, qa-16c-code #2). */
    private val overlays = ArrayDeque<() -> Unit>()

    /** LOCKED/UNLOCKED edges (16c): the night ground or the content, on the main thread. */
    private val modeListener: () -> Unit = {
        val before = contentShown
        if (gate() && !before) {
            if (started) renderCallCard()
            renderMusicCard()
            if (resumedNow) resumeContent()
        }
    }

    /** Vibb music's "Spilles nå" card (design 21 step 4): active only while started with the content shown. */
    private var musicCard: com.kidslauncher.mdm.music.MusicHomeCard? = null

    private fun renderMusicCard() = musicCard?.setActive(contentShown && started)

    private val apps by lazy { (applicationContext as Application).apps }
    private val appsObserver = Observer<List<AbstractDetailedAppInfo>> { render() }
    // Chatty apps change their counts often: coalesce, and only rebuild the grid from the
    // already-filtered apps (QA step 5 #6).
    private val badgeRender = Runnable { renderGrid() }

    /** The flashlight's state changed (design 41; its callback, main thread): the tile follows. */
    private val torchListener: () -> Unit = { renderGrid() }
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

    /** The ongoing-call card is showing instead of the contacts row. */
    private var callCardShown = false
    private var shownCallAvatar: String? = null
    private val callsListener: () -> Unit = { refreshHandler.post { renderCallCard() } }
    /** Between onStart and onStop: a render posted before onStop must not re-arm the ticker
     * after it (qa-11b-code #4). */
    private var started = false
    private val emergencyVerdict = com.kidslauncher.mdm.calls.EmergencyVerdictCache()
    /** Once a second while the card shows (the live mm:ss); [renderCallCard] re-arms it. */
    private val callTicker = Runnable { renderCallCard() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Design 32 (QA M3): Home lies under the PIN lock after a lock-up while dark - its snapshot,
        // which WM may show before the lock's first frame, is a plain black ground, never a
        // screenshot. The live bars and the launch splash (16e) stay as they are.
        setRecentsScreenshotEnabled(false)
        setTaskDescription(
            ActivityManager.TaskDescription.Builder()
                .setBackgroundColor(Color.BLACK)
                .setStatusBarColor(Color.BLACK)
                .setNavigationBarColor(Color.BLACK)
                .build(),
        )

        // Back does nothing on the home screen, same as stock Android launchers.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {}
        })
        apps.observeForever(appsObserver)
        // Design 59d §8.9 (a), JL-7: the system's Home creating us while LOCKED (a destroyed Home) is the same proof as
        // a new intent - a home gesture got through; heal lock task's flags. Not for a recreation.
        if (savedInstanceState == null && intent?.hasCategory(Intent.CATEGORY_HOME) == true) {
            PinLockRuntime.homeIntent(this, intent.getBooleanExtra(HomeFront.EXTRA_BROUGHT, false))
        }
        // Design 16c: the content only when the lock allows it - the night ground otherwise; the
        // lock's edges swap them.
        PinLockRuntime.addModeListener(modeListener)
        gate()
    }

    /** The lock allows the content now ([homeShowsContent]); a kid PIN is looked up only while the
     * runtime hasn't decided. */
    private fun contentAllowed(): Boolean {
        val known = PinLockRuntime.decided
        return homeShowsContent(PinLockRuntime.mode, pinActive = !known && PinLockRuntime.pinSetStored(this), known = known)
    }

    /** The content or the night ground, as the lock allows; whether the content is shown. */
    private fun gate(): Boolean {
        if (contentAllowed()) showContent() else showNight()
        return contentShown
    }

    /** The UNLOCKED edge (or no PIN lock): the content - inflated and wired the first time. */
    private fun showContent() {
        if (contentShown) return
        if (!::binding.isInitialized) bindContent()
        night?.stop()
        setContentView(binding.root)
        binding.root.requestApplyInsets()
        nightShown = false
        contentShown = true
    }

    /** LOCKED, or not decided with a kid PIN: the night ground - the content leaves the window, so
     * nothing of it can be seen or touched; an open contact sheet closes. */
    private fun showNight() {
        closeOverlays()
        contentShown = false
        contentUp = false
        renderMusicCard()
        appsJob?.cancel()
        refreshHandler.removeCallbacks(badgeRender)
        refreshHandler.removeCallbacks(callTicker)
        // Design 16e (qa-16e-fix2 #1): the boot's 3 s start once the drawn night ground has focus.
        val ground = night ?: NightGround(this) { PinLockRuntime.onHomeMarkShown(this, groundDrawn = true, focused = hasWindowFocus()) }
            .also { night = it }
        if (!nightShown) {
            setContentView(ground.root)
            nightShown = true
        }
        ground.applyBars()
        if (resumedNow) ground.start()
    }

    /** Home's content, created on the first UNLOCKED edge (16c) - never while locked. */
    private fun bindContent() {
        binding = ActivityHomeBinding.inflate(layoutInflater)
        // Status bar inset once, plus the layout's own <= 8 dp (fix round 2026-10-06).
        com.kidslauncher.mdm.ui.KidInsets.apply(binding.root)

        // No clock or date any more (design 08): the status bar shows the time.
        gridAdapter = HomeGridAdapter(this, ::trackOverlay)
        gridLayout = GridLayoutManager(this, gridColumns(null))
        binding.homeGrid.layoutManager = gridLayout
        binding.homeGrid.adapter = gridAdapter
        // Launcher round 7 §4: one-tile updates (DiffUtil), and nothing moves or fades.
        binding.homeGrid.itemAnimator = null
        binding.homeGrid.addItemDecoration(
            GridGapDecoration(
                KidAvatars.dp(this, GRID_COLUMN_GAP_DP.toFloat()),
                KidAvatars.dp(this, GRID_ROW_GAP_DP.toFloat()),
            ) { gridLayout.spanCount }
        )
        musicCard = com.kidslauncher.mdm.music.MusicHomeCard(this, binding.homeMusicCard)
        // Design 24b §1.3: "Lock again" ends a parent-PIN window - no PIN, it only takes access away.
        binding.homeWindowLockAgain.setOnClickListener {
            binding.homeWindowBanner.visibility = View.GONE
            com.kidslauncher.mdm.server.WindowRelock.lockAgainAsync(this, com.kidslauncher.mdm.server.RelockTrigger.LOCK_AGAIN)
        }
        // Back to our call screen from the ongoing-call card.
        binding.homeCallCard.setOnClickListener {
            try {
                startActivity(com.kidslauncher.mdm.calls.InCallActivity.intent(this))
            } catch (e: Exception) {
                android.util.Log.w("HomeActivity", "Couldn't bring the call screen back", e)
            }
        }

        // Home's swipes (HomeSwipe.kt): the detector is fed once, in dispatchTouchEvent, so each
        // swipe is decided from its own DOWN - also one that lands on a contact or a tile.
        gestureDetector = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float
            ): Boolean {
                if (e1 == null) return false
                val swipe = homeSwipe(
                    e1.x, e1.y, e2.x, e2.y, velocityX, velocityY,
                    start = downStart,
                    gridCanScrollDown = downGridCanScroll,
                    rowCanScrollRight = downRowCanScroll,
                    contentShown = contentShown,
                )
                when (swipe) {
                    // The drawer shows the same apps as the grid (same AppFilter), plus the PIN-gated Settings button.
                    HomeSwipe.DRAWER -> openAppsList(this@HomeActivity)
                    // The kid's own settings (also the grid's last tile) - the replacement for Android's Quick Settings shade.
                    HomeSwipe.KID_SETTINGS -> startActivity(Intent(this@HomeActivity, KidSettingsActivity::class.java))
                    HomeSwipe.NONE -> return false
                }
                return true
            }
        })
    }

    /**
     * Every touch passes the detector once, in window coordinates, before the views get it - it
     * only observes: the tiles, contacts, scrolls and long-presses get the touch as before. A swipe
     * that opened the drawer or kid Settings ends as a cancel for the views, so a short swipe that
     * ends on the contact or tile it started on isn't also its tap. Fed on the night ground too
     * (the content made the first time it shows): [homeSwipe] says nothing there, and a swipe whose
     * DOWN came before the content showed is still decided from that DOWN.
     */
    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        if (!::gestureDetector.isInitialized) return super.dispatchTouchEvent(ev)
        if (ev.actionMasked == MotionEvent.ACTION_DOWN) {
            downStart = swipeStart(ev)
            downGridCanScroll = binding.homeGrid.canScrollVertically(1)
            downRowCanScroll = binding.homeContactsScroll.canScrollHorizontally(1)
        }
        val swiped = gestureDetector.onTouchEvent(ev)
        if (!swiped || ev.actionMasked != MotionEvent.ACTION_UP) return super.dispatchTouchEvent(ev)
        val cancel = MotionEvent.obtain(ev).apply { action = MotionEvent.ACTION_CANCEL }
        try {
            super.dispatchTouchEvent(cancel)
        } finally {
            cancel.recycle()
        }
        return true
    }

    /** Where a swipe's DOWN landed ([down] in window coordinates, as [View.getGlobalVisibleRect]). */
    private fun swipeStart(down: MotionEvent): SwipeStart {
        fun hit(view: View): Boolean {
            if (!view.isShown) return false
            val rect = Rect()
            return view.getGlobalVisibleRect(rect) && rect.contains(down.x.toInt(), down.y.toInt())
        }
        return when {
            hit(binding.homeCallCard) || hit(binding.homeMusicCard.root) || hit(binding.homeWindowLockAgain) -> SwipeStart.CARD
            hit(binding.homeContactsScroll) -> SwipeStart.CONTACTS_ROW
            hit(binding.homeGrid) -> SwipeStart.GRID
            else -> SwipeStart.BLANK
        }
    }

    /** Design 32: Home brought under the lock at a lock-up, the screen still off ([homeDarkResume]). */
    private fun darkUnderLock(): Boolean =
        homeDarkResume(PinLockRuntime.darkLockUp, interactive(), PinLockRuntime.mode == LockMode.LOCKED)

    /** The screen is on (unknown counts as on: the usual path). */
    private fun interactive(): Boolean = getSystemService(PowerManager::class.java)?.isInteractive != false

    override fun onStart() {
        super.onStart()
        // Design 32 (QA M1): under the lock while dark, nothing - the card renders and listeners come
        // at the next interactive start or resume ([startWork]).
        if (darkUnderLock()) return
        startWork()
    }

    /** onStart's work - once per start, also when a dark start skipped it ([onResume]). */
    private fun startWork() {
        if (started) return
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
        com.kidslauncher.mdm.calls.OngoingCalls.addListener(callsListener)
        com.kidslauncher.mdm.server.WindowRelock.addListener(windowListener)
        com.kidslauncher.mdm.torch.Torch.addListener(torchListener)
        started = true
        renderCallCard()
        renderMusicCard()
        try {
            contentResolver.registerContentObserver(CallLog.Calls.CONTENT_URI, true, callLogObserver)
        } catch (e: SecurityException) {
            // No READ_CALL_LOG (calls not managed): no missed-call badges.
        }
    }

    override fun onResume() {
        super.onResume()
        resumedNow = true
        // Design 32 (QA M1): brought under the lock while dark - the night ground only. No update
        // fence, no time-rule screen over the lock, no lock start, no boot mark: the lock's own start
        // came right after Home's in the same pass, and nothing slow may come before its creation.
        // No lock task either (qa-32-s1-code L1): the lock's task is the focused one by now, so
        // `startLockTask` here could only throw; with the kiosk on and no lock task running, the
        // lock's own resume asks Home to root it (START_HOME), as before design 32.
        if (darkUnderLock()) {
            showNight()
            return
        }
        // Design 59d step B: brought by the face's unanimated leave - Home draws its own ground until it stops (Android's
        // wallpaper window isn't shown yet while the keyguard goes away; the rc.2 recording's 3 black frames).
        if (HomeFront.takeOwnGroundDue()) ownGround = true
        startWork()
        PinLockRuntime.onHomeResumed(interactive())
        // Deliberately triggered here, not from Application.onCreate() - see TsnetClient's own
        // doc comment on the GrapheneOS hardened_malloc / native-crash risk this sidesteps by
        // waiting until the launcher has actually rendered instead of racing the very first UI
        // paint after unlock. connectFromPreferences() already no-ops once connected (or with no
        // auth key configured), so calling it on every resume - not just the first - is safe, not
        // wasteful; MdmSyncWorker's regular sync cycle is the retry-until-connected backstop
        // either way.
        CoroutineScope(Dispatchers.IO).launch { TsnetClient.connectFromPreferences(this@HomeActivity) }
        // Design 59d step M (qa-59d-m-code M-6): a boot cover its own Home start disabled is enabled again now, not at
        // the next apply - an unarmed reboot in between would find no direct-boot-aware HOME. Off the main thread.
        CoroutineScope(Dispatchers.IO).launch { com.kidslauncher.mdm.lock.BootCover.afterHomeResumed(this@HomeActivity) }
        if (!gate()) {
            // Design 16c: locked (or not decided with a kid PIN) - the night ground, and the lock
            // in this same pass with nothing slow before it (no render, no policy decode; the
            // time rules are re-checked at the unlock). Lock task first: with the kiosk on Home
            // roots it, never the lock (design 16).
            reconcileKioskMode()
            com.kidslauncher.mdm.server.UpdateFence.onFront(this)
            // The time-rule screen from the stored reason: it brings the PIN lock back on top.
            if (redirectToLockScreenIfLocked()) return
            // Handy's PIN lock (step 10): Home in front while LOCKED means the lock lost the front.
            // At the first start of a boot it waits while the mark shows for 3 s (design 16e) -
            // only from here on, with the night ground resumed, does the mark count as up.
            if (PinLockRuntime.mode == LockMode.LOCKED) {
                PinLockRuntime.onHomeMarkUp()
                // A re-resume with the focus kept (a re-delivered HOME intent) gets no focus change.
                PinLockRuntime.onHomeMarkShown(this, groundDrawn = night?.drawn == true, focused = hasWindowFocus())
                PinLockRuntime.show(this, ask = LockAsk.BOOT)
            }
            return
        }
        // Fresh check against the clock every time the home screen comes to the foreground, on
        // top of the boundary alarm - cheap, and covers an alarm that was late or refused.
        reevaluateLockReasonFromCache(this@HomeActivity)
        // Must run before the lock-screen check below: while the bedtime/screen-time block is
        // showing is exactly when kiosk pinning should also be engaged, so the kid can't use
        // recents/home/notification-shade to route around LockActivity.
        reconcileKioskMode()
        // Step 11: our Home in front ends the update fence in the new build (finding 7: lock task
        // not required). A no-op without a fence.
        com.kidslauncher.mdm.server.UpdateFence.onFront(this)
        // Checked here (not just via the preference listener) so pressing Home while the lock
        // screen is showing can't be used to bounce back into the drawer/home list underneath it.
        if (redirectToLockScreenIfLocked()) return
        resumeContent()
    }

    override fun onPause() {
        // Design 16e (qa-16e-code #2): anything over the mark - even translucent - ends the boot's
        // wait, unless Home is resumed again at once (a re-delivered HOME intent pauses it).
        PinLockRuntime.onHomePaused(isChangingConfigurations)
        resumedNow = false
        night?.stop()
        super.onPause()
    }

    /** The content's part of a resume (and of an UNLOCKED edge while resumed). */
    private fun resumeContent() {
        if (!contentShown) return
        contentUp = true
        // The parent's language choice, now that Home is in front (no call screen or dialog).
        LauncherLocales.applyIfSafe(this)
        // Also refreshes whether the system wallpaper is still ours (in the background).
        WallpaperStore.refreshAsync(this)
        renderWallpaper()
        render()
        // Design 28 (QA L5): the music card from the clock - its window's recheck may have slept.
        renderMusicCard()
        loadMissedCalls()
        promptForCallRoleIfNeeded()
    }

    /**
     * The system's role prompts, at most once a day, for the parent to accept ([rolePromptFor]): the
     * dialer role when the device-owner call couldn't take it (an error was recorded), call
     * redirection and call screening (security review L7), which have no device-owner API (unless
     * granted with adb at provisioning).
     */
    private fun promptForCallRoleIfNeeded() {
        val state = CallPolicyStore.state
        val now = System.currentTimeMillis()
        val roles = CallSystem.roles(this)
        val role = rolePromptFor(
            state, roles.dialerHeld, CallPrefs.lastError(this) != null, roles.redirectionHeld, roles.screeningHeld,
        ) ?: return
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

        // Guarded (design 16, QA #3): the platform throws "Invalid task, not in foreground" when
        // Home and the lock start back to back (boot); a crash here would count for the lock's
        // crash guard. The next resume tries again, and the lock's own fallback starts it.
        try {
            if (shouldBeLocked && !currentlyLocked) {
                startLockTask()
            } else if (!shouldBeLocked && currentlyLocked && contentAllowed()) {
                // With the kiosk off, a running lock task while LOCKED is the PIN lock's own (step
                // 10) - never stopped from here; nor while not decided with a kid PIN (16c).
                stopLockTask()
            }
        } catch (e: Exception) {
            android.util.Log.w("HomeActivity", "Lock task change failed - retried at the next resume", e)
        }
    }

    /** Design 16e: the first focus of the drawn night ground starts the mark's 3 s (qa-16e-fix2 #1 -
     * focus goes only to a drawn, shown window); the assistant, the power menu or a dialog over the
     * mark takes the focus without pausing Home - the boot's wait ends (qa-16e-code #2). */
    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (!hasFocus) {
            PinLockRuntime.onHomeCovered(this, isChangingConfigurations, "Home lost focus")
        } else if (nightShown) {
            PinLockRuntime.onHomeMarkShown(this, groundDrawn = night?.drawn == true, focused = true)
        }
    }

    /**
     * Launcher round 7 §4: Home pressed while Home is up - back to the top (the grid and the contacts
     * row) and Home's menus closed; nothing for our own Home starts or under the lock
     * ([homePressToTop]). Android pauses Home for the delivery, so [contentUp] is what tells.
     */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val broughtByUs = intent.getBooleanExtra(HomeFront.EXTRA_BROUGHT, false)
        // Design 59d §8.9 (a): the system's Home while LOCKED - the home gesture got through - heals lock task's flags.
        PinLockRuntime.homeIntent(this, broughtByUs)
        val toTop = homePressToTop(
            broughtByUs = broughtByUs,
            contentWasUp = contentUp,
            contentShown = contentShown,
            darkLockUp = PinLockRuntime.darkLockUp,
            locked = PinLockRuntime.mode == LockMode.LOCKED,
        )
        if (!toTop) return
        closeOverlays()
        binding.homeGrid.smoothScrollToPosition(0)
        binding.homeContactsScroll.smoothScrollTo(0, 0)
    }

    override fun onStop() {
        contentUp = false
        // Design 59d step B: the next start is transparent over Android's wallpaper again.
        ownGround = false
        // Design 16e: something covers the mark - the boot's wait for it ends.
        PinLockRuntime.onHomeCovered(this, isChangingConfigurations, "Home stopped")
        started = false
        renderMusicCard()
        BadgeStore.removeListener(badgeListener)
        refreshHandler.removeCallbacks(badgeRender)
        com.kidslauncher.mdm.calls.OngoingCalls.removeListener(callsListener)
        com.kidslauncher.mdm.server.WindowRelock.removeListener(windowListener)
        com.kidslauncher.mdm.torch.Torch.removeListener(torchListener)
        refreshHandler.removeCallbacks(callTicker)
        ContactPhotos.removeListener(photoListener)
        WallpaperStore.removeListener(wallpaperListener)
        contentResolver.unregisterContentObserver(callLogObserver)
        super.onStop()
    }

    override fun onDestroy() {
        LauncherPreferences.getSharedPreferences()
            .unregisterOnSharedPreferenceChangeListener(sharedPreferencesListener)
        apps.removeObserver(appsObserver)
        PinLockRuntime.removeModeListener(modeListener)
        night?.stop()
        closeOverlays()
        super.onDestroy()
    }

    /** Missed calls per contact, from the call log on a background thread. */
    private fun loadMissedCalls() {
        if (!contentShown) return
        val state = CallPolicyStore.state
        CoroutineScope(Dispatchers.Main).launch {
            val result = withContext(Dispatchers.IO) { MissedCallsRepo.summaries(this@HomeActivity, state) }
            if (result != missed && !isDestroyed && contentShown) {
                missed = result
                renderCallParts()
            }
        }
    }

    /** Everything: the call parts now, the apps filtered again in the background. */
    private fun render() {
        if (!contentShown) return
        renderWindowBanner()
        renderCallParts()
        refreshApps()
    }

    /** Design 24b §1.3: "Open until 18:40 · Lock again" while a parent-PIN window is open. */
    private fun renderWindowBanner() {
        if (!contentShown) return
        val until = try {
            com.kidslauncher.mdm.server.WindowRelock.openUntilMs()
        } catch (e: Exception) {
            null
        }
        binding.homeWindowBanner.visibility = if (until == null) View.GONE else View.VISIBLE
        if (until != null) {
            binding.homeWindowText.text = getString(R.string.home_window_open, ClockFormat.formatUntil(this, until))
        }
    }

    private val windowListener: () -> Unit = { renderWindowBanner() }

    /** Contacts row and phone-book tile from the call rules, missed calls and photos (cheap). */
    private fun renderCallParts() {
        if (!contentShown) return
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
        if (!contentShown) return
        val all = apps.value ?: return
        val context = applicationContext
        val width = contentWidthDp()
        appsJob?.cancel()
        appsJob = CoroutineScope(Dispatchers.Main).launch {
            val result = withContext(Dispatchers.Default) {
                // The drawer's filter lists the Play Store in a parent-PIN window too; Home's grid
                // only in install mode (design 28). Unreadable = not in install mode.
                val installMode = try {
                    PlayRuntime.installModeActive(context)
                } catch (e: Exception) {
                    false
                }
                val visible = AppFilter(context).invoke(all)
                    .filter { (it.getRawInfo() as? AppInfo)?.packageName != packageName }
                    .filter { playPackageOnHome((it.getRawInfo() as? AppInfo)?.packageName, installMode) }
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
            if (isDestroyed || !contentShown) return@launch
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
    /** Design 59d step B: since the face's leave brought Home ([HomeFront.takeOwnGroundDue]) and until Home stops. */
    private var ownGround = false

    private fun renderWallpaper() {
        if (!contentShown) return
        val state = WallpaperGround.apply(this, binding.root, ownGround = ownGround)
        if (state.ink != shownInk) {
            shownInk = state.ink
            renderCallParts()
        }
    }

    private var shownInk: InkChoice = WallpaperStore.state.ink

    /** The grid from the last filtered apps and the current badge counts (cheap). */
    private fun renderGrid() {
        if (!contentShown) return
        val data = gridData
        gridAdapter.submit(
            homeGrid(data.apps, showPhoneBook, BadgeStore.counts, com.kidslauncher.mdm.torch.Torch.state),
            data.infos,
            data.icons,
            data.metrics,
            shownInk,
        )
    }

    /**
     * While a call exists the contacts row gives way to a green card (user request after the
     * emulator run): the caller's avatar as on Home, "Call with <name> · mm:ss" (live, from the
     * connect time) or "Call in progress", and "Tap to go back" - a tap brings our call screen
     * back. Gone when the call ends ([ongoingCallCard]).
     */
    private fun renderCallCard() {
        if (!contentShown || !started) {
            refreshHandler.removeCallbacks(callTicker)
            return
        }
        val call = com.kidslauncher.mdm.calls.OngoingCalls.current
        val number = com.kidslauncher.mdm.calls.PhoneNumbers.numberFromHandle(call?.details?.handle?.toString())
        val contact = (CallPolicyStore.state as? com.kidslauncher.mdm.calls.CallPolicyState.Managed)?.rules?.contactFor(number)
        val emergency = emergencyVerdict.isEmergency(number) { CallSystem.isEmergencyOutgoing(this, it) }
        val card = com.kidslauncher.mdm.calls.ongoingCallCard(
            liveCall = com.kidslauncher.mdm.calls.OngoingCalls.hasLiveCall,
            contactName = contact?.name,
            emergency = emergency,
            connectTimeMs = call?.details?.connectTimeMillis ?: 0L,
            nowMs = System.currentTimeMillis(),
        )
        val wasShown = callCardShown
        callCardShown = card != null
        if (card == null) {
            binding.homeCallCard.visibility = View.GONE
            refreshHandler.removeCallbacks(callTicker)
            shownCallAvatar = null
            // The contacts row comes back.
            if (wasShown) renderCallParts()
            return
        }
        binding.homeContactsScroll.visibility = View.GONE
        binding.homeCallCard.visibility = View.VISIBLE
        val title = card.name?.let { getString(R.string.home_call_with, it) } ?: getString(R.string.home_call_ongoing)
        binding.homeCallTitle.text = card.elapsedSec?.let { "$title · ${android.text.format.DateUtils.formatElapsedTime(it)}" } ?: title
        binding.homeCallCard.contentDescription = "${binding.homeCallTitle.text}. ${getString(R.string.home_call_back)}"
        val avatar = com.kidslauncher.mdm.calls.callAvatar(contact != null, emergency, unlocked = true)
        val key = if (avatar == com.kidslauncher.mdm.calls.CallAvatar.CONTACT && contact != null) {
            "c|${contact.id}|${contact.name}|${contact.photo}|${ContactPhotos.cached(this, contact.photo) != null}"
        } else {
            "silhouette"
        }
        if (key != shownCallAvatar) {
            shownCallAvatar = key
            if (avatar == com.kidslauncher.mdm.calls.CallAvatar.CONTACT && contact != null) {
                binding.homeCallPhoto.setBackgroundResource(R.drawable.bg_kid_circle)
                KidAvatars.bindContact(binding.homeCallPhoto, binding.homeCallInitial, contact, isEmergency = false, initialSp = 22f)
            } else {
                binding.homeCallPhoto.backgroundTintList = null
                binding.homeCallPhoto.background = null
                binding.homeCallPhoto.setImageResource(R.drawable.ic_call_avatar_placeholder)
                binding.homeCallInitial.visibility = View.GONE
            }
        }
        refreshHandler.removeCallbacks(callTicker)
        refreshHandler.postDelayed(callTicker, 1000)
    }

    private fun renderContacts(contacts: List<RuleContact>) {
        val row = binding.homeContacts
        row.removeAllViews()
        binding.homeContactsScroll.visibility = if (contacts.isEmpty() || callCardShown) View.GONE else View.VISIBLE
        val layout = contactRow(contacts.size, contentWidthDp())
        // Centred while everything fits, from the left (with the peek at the edge) when it scrolls.
        row.gravity = if (layout.scrolls) Gravity.START else Gravity.CENTER_HORIZONTAL
        val density = resources.displayMetrics.density
        // The row's real width once Home is laid out (qa-34-s4-code L2), else the estimate.
        val contentPx = contactsContentPx(
            binding.root.width - binding.root.paddingLeft - binding.root.paddingRight,
            resources.configuration.screenWidthDp,
            density,
        )
        val itemPx = contactItemPx(layout, contacts.size, contentPx, density)
        val inflater = LayoutInflater.from(this)
        for (contact in contacts) {
            val item = inflater.inflate(R.layout.item_kid_contact, row, false)
            val emergency = CallSystem.isEmergencyOutgoing(this, contact.number)
            KidAvatars.sizeContact(item, layout.avatarDp)
            PressFeedback.contact(item, KidAvatars.dp(this, layout.avatarDp.toFloat()))
            KidAvatars.bindContact(
                item.findViewById<ImageView>(R.id.contact_photo),
                item.findViewById<TextView>(R.id.contact_initial),
                contact, emergency, initialSp = 30f * layout.avatarDp / 76f,
            )
            KidAvatars.bindCallBadge(item.findViewById(R.id.contact_call_badge), shownInk)
            val missedCount = missed[contact.number]?.count ?: 0
            KidAvatars.bindBadge(item.findViewById(R.id.contact_badge), missedCount)
            item.findViewById<TextView>(R.id.contact_name).apply {
                text = contact.name
                KidInk.label(this, shownInk)
                maxWidth = itemPx
            }
            item.contentDescription = if (missedCount > 0) {
                resources.getQuantityString(R.plurals.contact_missed, missedCount, contact.name, missedCount)
            } else {
                contact.name
            }
            // Tap and long-press open the contact sheet (Call / Message / Close) - user decision in
            // the fix round 2026-10-06, replacing "tap calls straight away" (02/05 docs): a stray
            // tap on Home no longer starts a call.
            item.setOnClickListener { showContactSheet(contact) }
            item.setOnLongClickListener {
                showContactSheet(contact)
                true
            }
            // Each item is one avatar plus one gap wide, so avatars are a gap apart - but a row that
            // fits is never wider than the screen (design 34 P4: three 104 dp items in 288 dp
            // scrolled by 24 dp and cut the third contact's call badge).
            item.layoutParams = LinearLayout.LayoutParams(itemPx, LinearLayout.LayoutParams.WRAP_CONTENT)
            row.addView(item)
        }
    }

    private fun showContactSheet(contact: RuleContact) {
        val sheet: Dialog = ContactSheet.show(this, contact, missed[contact.number]) { loadMissedCalls() }
        trackOverlay { sheet.dismiss() }
    }

    /** Only the last few can still be open (a menu and its dialog); closing a closed one is a no-op. */
    private fun trackOverlay(close: () -> Unit) {
        overlays.addLast(close)
        while (overlays.size > MAX_OVERLAYS) overlays.removeFirst()
    }

    private fun closeOverlays() {
        while (overlays.isNotEmpty()) {
            val close = overlays.removeFirst()
            try {
                close()
            } catch (e: Exception) {
                android.util.Log.w("HomeActivity", "Couldn't close an overlay", e)
            }
        }
    }

    override fun isHomeScreen(): Boolean {
        return true
    }
}
