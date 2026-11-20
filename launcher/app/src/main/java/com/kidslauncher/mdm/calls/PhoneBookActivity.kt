package com.kidslauncher.mdm.calls

import android.app.ActivityManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.kidslauncher.mdm.R
import com.kidslauncher.mdm.appdisplay.AppDisplay
import com.kidslauncher.mdm.databinding.ActivityPhoneBookBinding
import com.kidslauncher.mdm.preferences.LauncherPreferences
import com.kidslauncher.mdm.server.LockReason
import com.kidslauncher.mdm.sms.SmsHome
import com.kidslauncher.mdm.ui.KidHeader
import com.kidslauncher.mdm.ui.KidInsets
import com.kidslauncher.mdm.ui.LockActivity
import com.kidslauncher.mdm.ui.day.LockWatch
import com.kidslauncher.mdm.ui.look.KidPageActivity
import com.kidslauncher.mdm.ui.look.PageKind
import com.kidslauncher.mdm.ui.look.nightOver
import com.kidslauncher.mdm.ui.look.showNight
import com.kidslauncher.mdm.ui.search.KidSearchField
import com.kidslauncher.mdm.ui.search.PhoneBookBack
import com.kidslauncher.mdm.ui.search.listCollator
import com.kidslauncher.mdm.ui.search.phoneBookBack
import com.kidslauncher.mdm.ui.search.phoneBookBackEnabled
import com.kidslauncher.mdm.ui.search.searching
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The phone book - the kid's place to call from, and the DIAL / `tel:` handler the dialer role requires. Since design
 * 79 one list of night rows ([phoneBookRows]): a request's answer once at the top, the
 * contacts with an unseen missed call newest first, everyone else A-Å, the kid's open requests under "Venter på svar",
 * the emergency numbers last. It is on Dagen's plain night ground (the user, 2026-11-10): `kid_ground` and the inks in
 * the layouts, the bars the theme's - the kid's own picture stays behind Home and kid Settings. Each row has Ring and Melding ([contactActions], the card's rule); a tap anywhere else
 * opens [ContactSheet]. The header's "+" opens the contact request ([ContactRequestForm]). There is no free keypad;
 * emergency numbers still work, from here (their Ring asks first) and from the lock screen's Emergency button.
 *
 * **Seen** (design 79 §3.2): the visit's order is fixed when the phone book opens ([visitTopAfter]: nothing leaves the
 * top while it is in front, a new missed call joins); a top row on screen for a second while the phone book is resumed
 * and focused ([TopSightings]) is marked seen when the kid leaves ([onStop], not for a rotation) - up to the call it
 * showed. The next visit lists it in its place. The card, Ring and Melding mark it seen at once.
 *
 * **Siste** (design 106 §3, B2): a "Kontakter | Siste" switch under the header ([phoneBookSwitchShown]); Siste lists
 * the phone book's contacts' calls of the last 14 days by day ([recentCalls], [RecentCallsAdapter]) from the same read
 * as the badges ([MissedCallsRepo.load]). Every visit starts on Kontakter ([tabAtVisitStart]); only a configuration
 * change keeps Siste, and an ask by [onNewIntent] or calls going off moves the book back ([tabAfter]). A red row on
 * screen for a second marks its contact seen when the kid leaves Siste ([recentVisible], its own [TopSightings]).
 *
 * A `tel:` number from another app is checked with [decideOutgoing]: allowed asks "Call X?", anything else says it
 * isn't allowed.
 *
 * Missed calls (design 12): it is also the call-log viewer (`VIEW` `vnd.android.cursor.dir/calls`, pinned as a
 * persistent preferred activity on [PhoneBookEntryActivity], which passes it on to the system dialer while calls are
 * unmanaged). Our missed-call notification opens it, with [EXTRA_MISSED_CONTACT] when the calls came from one contact
 * (that contact's sheet opens, once). Being opened clears the notification ([MissedCallNotifier.dismiss]).
 *
 * **A task of its own** (2026-11-16, as Meldinger's and Kalender's; the user: Home should slide it away as Meldinger's,
 * and it should be in Recents): the `.phonebook` affinity, the night class and its light twin ([LightPhoneBookActivity],
 * design 78 §12.2 R2), in Recents as the Home tile's "Telefonbok" with the launcher's icon. Started only by [intent]
 * (Home's tile, the time-rule screen's row) and [forward] (the entry, for everything from outside the process and the
 * missed calls); since Recents can bring it over the time-rule screen, its resume yields to that screen under a rule
 * that doesn't allow calls ([redirectIfLocked]) and it watches the lock while in front.
 */
open class PhoneBookActivity : KidPageActivity() {

    private lateinit var binding: ActivityPhoneBookBinding
    private lateinit var adapter: PhoneBookAdapter
    /** Design 77 §14: the kid's contact request - the header's "+" and the form. */
    private lateinit var requestForm: ContactRequestForm
    /** Design 79 §4: the search pill (from 8 contacts). */
    private lateinit var search: KidSearchField

    /** Back (QA N4, code QA L10): the keyboard first (stock Android), then the request form, then the search's text,
     * then Siste back to Kontakter (design 106 Q8), then the phone book leaves ([phoneBookBack]). Enabled only while
     * there is a form, a query or Siste ([phoneBookBackEnabled]) - with none, the system closes the keyboard and leaves
     * as everywhere. */
    private val back = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            val ime = binding.root.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime()) == true
            when (phoneBookBack(ime, requestForm.isOpen, search.query, tab == PhoneBookTab.RECENT)) {
                PhoneBookBack.HIDE_KEYBOARD -> search.hideKeyboard()
                PhoneBookBack.CLOSE_FORM -> {
                    requestForm.close()
                    render()
                }
                PhoneBookBack.CLEAR_SEARCH -> search.clear()
                PhoneBookBack.SHOW_CONTACTS -> showTab(PhoneBookTab.CONTACTS)
                PhoneBookBack.LEAVE -> {}
            }
        }
    }

    /** The stray drafts' sweep waits for the first resume (code QA L3, second pass). */
    private var draftSweepDue = false

    /** The request answers this visit has counted as shown (code QA L9): no prefs read per scroll. */
    private val answersCounted = mutableSetOf<String>()

    /** A sync or a time rule changed the call rules while the phone book is started (code QA M2, second pass). */
    private val policyListener: (CallPolicyState) -> Unit = {
        runOnUiThread {
            if (!isDestroyed) {
                render()
                // Design 106 code QA L2: Siste's rows are built from the contacts of their load - a contact the parent
                // removed (or added) mid-visit leaves (or joins) Siste with a new one.
                loadMissedCalls()
            }
        }
    }

    /** A rule that doesn't allow calls beginning (or the rule changing) while the phone book is in front. */
    private val lockWatch = LockWatch(this) { redirectIfLocked(it) }

    /** Code QA L2: only the latest missed-call load is applied. */
    private val loads = LatestOnly()

    /** The request form's "Bilde av …" sheet, closed when the phone book stops (code QA M1). */
    private var requestPictureSheet: android.app.Dialog? = null
    private var missed: Map<String, ContactMissed> = emptyMap()

    /** This visit's top ([visitTopAfter]); `null` until the visit's first missed calls are loaded - the list waits for
     * them, so it never reorders under the kid's finger. */
    private var visitTop: Map<String, Long>? = null
    private var sightings = TopSightings()

    /** The search's text changed: the next list starts at its top. */
    private var toTop = false

    /** The visit came through a rotation: [onStart] keeps its top and sightings. */
    private var visitRestored = false
    private var resumed = false

    /** Design 106 §3: Siste's list, the tab the activity alone holds, Siste's own sightings and the last load. */
    private lateinit var recentAdapter: RecentCallsAdapter
    private var tab = PhoneBookTab.CONTACTS

    /** The tab came back through a configuration change: [onStart] keeps it (§3.5, M1). */
    private var tabRestored = false
    private var recentSightings = TopSightings()
    private var snapshot: CallsSnapshot? = null

    /** Siste was just shown: its list starts at its top. */
    private var recentToTop = false

    /** The switch is being set by the code, not the kid. */
    private var settingTab = false

    /** What [render] last decided: the kid can ask, and the Kontakter info line. */
    private var canAskNow = false
    private var contactsInfo: String? = null
    private var elementLabel = ""

    /** Design 106 §3.5: Siste's record of missed Element X calls changed. */
    private val recordListener: () -> Unit = { loadMissedCalls() }

    /** L7: "I dag" and "I går" move with the date and the zone. */
    private val dateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = loadMissedCalls()
    }

    /** The contact whose sheet the missed-call notification asked for, opened once the missed calls are loaded. */
    private var pendingSheet: String? = null
    private val photoListener: () -> Unit = {
        if (::adapter.isInitialized) adapter.photosChanged()
        if (::recentAdapter.isInitialized) recentAdapter.photosChanged()
        if (::requestForm.isInitialized) requestForm.bindPicture()
    }

    /** Design 79 §5.5: the request form's picture comes back from [KidPhotoActivity] as a draft file. */
    private val draftLauncher = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
        val name = result.data?.getStringExtra(KidPhotoActivity.RESULT_DRAFT_FILE)
        if (result.resultCode == RESULT_OK && name != null && ::requestForm.isInitialized && requestForm.isOpen) {
            requestForm.setDraft(name)
        } else if (name != null) {
            // Not taken (the form closed meanwhile, code QA L3): the picture of a person goes at once.
            ContactRequests.deleteDraft(this, name)
        }
    }
    /** Design 77 §6.7: the rows' unread messages follow the engine. */
    private val messagesListener: () -> Unit = { render() }
    private val requestsListener: () -> Unit = { render() }
    /** Round L5: a missed Element X call came or went. */
    private val elementMissedListener: () -> Unit = { loadMissedCalls() }

    /** Design 79 §3.2: a missed call that comes while the phone book is in front joins the visit's top (as Home's
     * badges follow the call log). */
    private val callLogObserver = object : android.database.ContentObserver(android.os.Handler(android.os.Looper.getMainLooper())) {
        override fun onChange(selfChange: Boolean) = loadMissedCalls()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (isFinishing) return // The other look's class: its task is closing (KidPageActivity).
        // Its Recents card: the name the kid reads on Home's tile and the launcher's icon, as Kalender's and Meldinger's
        // (the root's description serves the whole task; minSdk 34 always has the Builder).
        setTaskDescription(
            ActivityManager.TaskDescription.Builder()
                .setLabel(getString(R.string.calls_phone_book))
                .setIcon(R.mipmap.ic_launcher)
                .build(),
        )
        CallPolicyStore.ensureLoaded(this)
        binding = ActivityPhoneBookBinding.inflate(layoutInflater)
        setContentView(binding.root)
        adapter = PhoneBookAdapter(
            onContact = { row -> ContactSheet.show(this, row.contact, missed[row.contact.number]) { loadMissedCalls() } },
            onCall = ::call,
            onMessage = ::message,
            onEmergencyDialer = { EmergencyCall.confirm(this) },
        )
        val list = binding.phoneBookList
        list.layoutManager = LinearLayoutManager(this)
        list.adapter = adapter
        // As Home's grid: no item animator, rows don't fade or slide when the list changes.
        list.itemAnimator = null
        list.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) = noteSightings()
        })
        list.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> noteSightings() }
        // Design 106 §3.6: Siste - a row opens the contact's sheet, as a Kontakter row does; never a call.
        recentAdapter = RecentCallsAdapter { row -> ContactSheet.show(this, row.contact, missed[row.contact.number]) { loadMissedCalls() } }
        binding.phoneBookRecent.apply {
            layoutManager = LinearLayoutManager(this@PhoneBookActivity)
            adapter = recentAdapter
            itemAnimator = null
            addOnScrollListener(object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) = noteSightings()
            })
            addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> noteSightings() }
        }
        binding.phoneBookTabs.setOnCheckedChangeListener { _, id ->
            if (!settingTab) showTab(if (id == R.id.phone_book_tab_recent) PhoneBookTab.RECENT else PhoneBookTab.CONTACTS)
        }
        // The keyboard (the request form): the page's bottom takes it (design 79 §3.5, QA L3).
        KidInsets.apply(binding.root, ime = true)
        binding.phoneBookPlus.setOnClickListener { requestForm.open() }
        search = KidSearchField(binding.phoneBookSearch.root, R.string.phone_book_search) {
            // A new query shows its best hits from the top, once the list has them; a keystroke only filters (M2).
            toTop = true
            filter()
        }
        requestForm = ContactRequestForm(this, binding, onModeChanged = { render() }, onSent = { search.clear() }, onPicture = ::requestPicture)
        onBackPressedDispatcher.addCallback(this, back)
        restoreVisit(savedInstanceState)
        requestForm.restore(savedInstanceState)
        // Design 79 §5.5, code QA L3: the stray drafts go at the first resume - after a draft pick's result is
        // delivered (a framing screen recreated after a process death writes its draft before this one exists).
        draftSweepDue = true
        // Once: not again after a recreation, nor when Recents starts the task again from its first intent (the task
        // outlives its page: Back finishes the page, the card stays).
        if (savedInstanceState == null && (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY) == 0) takeAsked(intent)
    }

    override fun onStart() {
        super.onStart()
        // Code QA L3: nothing of the last visit's load shows before this visit's own.
        snapshot = null
        ContactPhotos.addListener(photoListener)
        ElementMissedCallStore.addListener(elementMissedListener)
        SmsHome.addListener(messagesListener)
        ContactRequests.addListener(requestsListener)
        CallPolicyStore.addListener(policyListener)
        ElementCallLogStore.addListener(recordListener)
        ContextCompat.registerReceiver(
            this, dateReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_DATE_CHANGED)
                addAction(Intent.ACTION_TIMEZONE_CHANGED)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        try {
            contentResolver.registerContentObserver(android.provider.CallLog.Calls.CONTENT_URI, true, callLogObserver)
        } catch (e: Exception) {
            // No call-log access (unmanaged): the list follows the visit's own loads.
        }
        SmsHome.refresh(this)
        // A new visit: its top is fixed by the first missed calls loaded (design 79 §3.2).
        if (!visitRestored) {
            visitTop = null
            sightings = TopSightings()
        }
        visitRestored = false
        // Design 106 §3.5 (Q5): every visit starts on Kontakter - Home's tile, Recents, back from a call or another
        // app, a reopen after the process died; only a configuration change brings Siste back.
        if (!tabRestored) recentSightings = TopSightings()
        val start = tabAtVisitStart(tabRestored, tab)
        tabRestored = false
        tab = start
        recentToTop = start == PhoneBookTab.RECENT
        applyTab()
    }

    override fun onResume() {
        super.onResume()
        // In its own task the phone book outlives Home's clearTaskOnLaunch, so Recents can bring it over the time-rule
        // screen (in Home's task): under a rule that doesn't allow calls that screen comes back on top, as Kalender's
        // and Meldinger's pages do.
        if (redirectIfLocked(this)) return
        lockWatch.start()
        resumed = true
        if (draftSweepDue) {
            draftSweepDue = false
            ContactRequests.sweepDrafts(this, keep = requestForm.draft)
        }
        render()
        loadMissedCalls()
        // Opening the phone book deals with the missed-call notification (design 12, QA #3).
        MissedCallNotifier.dismiss(this)
        noteSightings()
    }

    override fun onPause() {
        lockWatch.stop()
        resumed = false
        noteSightings()
        super.onPause()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        noteSightings()
    }

    override fun onStop() {
        ContactPhotos.removeListener(photoListener)
        ElementMissedCallStore.removeListener(elementMissedListener)
        SmsHome.removeListener(messagesListener)
        ContactRequests.removeListener(requestsListener)
        CallPolicyStore.removeListener(policyListener)
        ElementCallLogStore.removeListener(recordListener)
        unregisterReceiver(dateReceiver)
        contentResolver.unregisterContentObserver(callLogObserver)
        if (!isChangingConfigurations && ::requestForm.isInitialized) {
            // The visit ends: the top rows it showed are seen (design 79 §3.2 rule 1) - Home's badge goes now too, with
            // one reload for the whole visit (code QA N6).
            MissedCallsRepo.markSeen(this, sightings.seen(SystemClock.elapsedRealtime()))
            // Design 106 §3.4: and Siste's red rows the kid saw, if the visit ended on Siste.
            writeRecentMarks()
            recentAdapter.submitList(null)
            snapshot = null
            // Code QA L3: a load still running from this visit is never applied after it.
            loads.begin()
            sightings = TopSightings()
            visitTop = null
            answersCounted.clear()
            // Code QA L1: the next visit starts empty and fills once - no row of this visit can take a tap meanwhile.
            adapter.submitList(null)
            // Code QA N3: the query is kept across a rotation only.
            search.clear()
            requestPictureSheet?.dismiss()
            // Design 77 §14: an answer shows once - the ones shown go when the phone book is left.
            requestForm.left()
        }
        super.onStop()
    }

    override fun onDestroy() {
        requestPictureSheet?.dismiss()
        // Code QA L3: the phone book is closed for good with the form open - its picture goes.
        if (isFinishing && ::requestForm.isInitialized) requestForm.setDraft(null)
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (!::requestForm.isInitialized) return
        requestForm.save(outState)
        // Design 106 §3.5 (M1): the tab and Siste's sightings only for a configuration change (a font size or language) -
        // before the early return below; a process death brings nothing of Siste back.
        if (isChangingConfigurations) {
            outState.putString(STATE_TAB, tab.name)
            val seen = recentSightings.state(SystemClock.elapsedRealtime())
            outState.putStringArray(STATE_RECENT_NUMBERS, seen.map { it.number }.toTypedArray())
            outState.putLongArray(STATE_RECENT_AT, seen.map { it.atMs }.toLongArray())
            outState.putLongArray(STATE_RECENT_MS, seen.map { it.accumulatedMs }.toLongArray())
            outState.putLongArray(STATE_RECENT_UP_TO, seen.map { it.seenUpToMs ?: Long.MIN_VALUE }.toLongArray())
        }
        val top = visitTop ?: return
        outState.putStringArray(STATE_TOP_NUMBERS, top.keys.toTypedArray())
        outState.putLongArray(STATE_TOP_TIMES, top.values.toLongArray())
        val seen = sightings.state(SystemClock.elapsedRealtime())
        outState.putStringArray(STATE_SEEN_NUMBERS, seen.map { it.number }.toTypedArray())
        outState.putLongArray(STATE_SEEN_AT, seen.map { it.atMs }.toLongArray())
        outState.putLongArray(STATE_SEEN_MS, seen.map { it.accumulatedMs }.toLongArray())
        outState.putLongArray(STATE_SEEN_UP_TO, seen.map { it.seenUpToMs ?: Long.MIN_VALUE }.toLongArray())
    }

    /** A rotation keeps the visit's order and what it has seen (design 79 §3.2); a configuration change keeps the tab
     * and Siste's sightings (design 106 §3.5). */
    private fun restoreVisit(state: Bundle?) {
        restoreTab(state)
        val numbers = state?.getStringArray(STATE_TOP_NUMBERS) ?: return
        val times = state.getLongArray(STATE_TOP_TIMES) ?: return
        if (numbers.size != times.size) return
        visitTop = numbers.zip(times.toList()).toMap()
        val seenNumbers = state.getStringArray(STATE_SEEN_NUMBERS).orEmpty()
        val at = state.getLongArray(STATE_SEEN_AT) ?: LongArray(0)
        val ms = state.getLongArray(STATE_SEEN_MS) ?: LongArray(0)
        val upTo = state.getLongArray(STATE_SEEN_UP_TO) ?: LongArray(0)
        if (at.size == seenNumbers.size && ms.size == seenNumbers.size && upTo.size == seenNumbers.size) {
            sightings.restore(seenNumbers.indices.map { SightingState(seenNumbers[it], at[it], ms[it], upTo[it].takeIf { v -> v != Long.MIN_VALUE }) })
        }
        visitRestored = true
    }

    private fun restoreTab(state: Bundle?) {
        val saved = state?.getString(STATE_TAB)?.let { name -> PhoneBookTab.entries.firstOrNull { it.name == name } } ?: return
        tab = saved
        tabRestored = true
        val numbers = state.getStringArray(STATE_RECENT_NUMBERS).orEmpty()
        val at = state.getLongArray(STATE_RECENT_AT) ?: LongArray(0)
        val ms = state.getLongArray(STATE_RECENT_MS) ?: LongArray(0)
        val upTo = state.getLongArray(STATE_RECENT_UP_TO) ?: LongArray(0)
        if (at.size == numbers.size && ms.size == numbers.size && upTo.size == numbers.size) {
            recentSightings.restore(numbers.indices.map { SightingState(numbers[it], at[it], ms[it], upTo[it].takeIf { v -> v != Long.MIN_VALUE }) })
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        // Design 106 §3.5 (Q7, code QA M1): every new intent is a way in from outside - our notification (one caller or
        // several), the glance's row, the system call log, a tel: link - or the time-rule screen's row; reaching the
        // book in front, it writes Siste's marks and moves it to Kontakter first, whether or not it asks for anything.
        if (tabAfter(tab, switchShown = true, entered = true) != tab) showTab(PhoneBookTab.CONTACTS)
        takeAsked(intent)
    }

    /** The number and the missed-call contact the entry asks for ([forward]) - taken once (the extras are removed), and
     * dropped while the phone book yields to the time-rule screen ([redirectIfLocked], at the resume that follows): a
     * "Ring X?" or "not allowed" left behind it would be the first thing the kid sees when the phone book is next
     * opened, perhaps hours later. */
    private fun takeAsked(intent: Intent) {
        val number = intent.getStringExtra(EXTRA_ASK_NUMBER)
        intent.removeExtra(EXTRA_ASK_NUMBER)
        if (LauncherPreferences.mdm().lockReason() != LockReason.NONE && !usableNow(this)) {
            intent.removeExtra(EXTRA_MISSED_CONTACT)
            return
        }
        takeMissedContact(intent)
        handleNumber(number)
    }

    /** The notification's contact, only when it is in the phone book now (QA #8); the extra is
     * removed, so it opens once. */
    private fun takeMissedContact(intent: Intent?) {
        val number = intent?.getStringExtra(EXTRA_MISSED_CONTACT) ?: return
        intent.removeExtra(EXTRA_MISSED_CONTACT)
        pendingSheet = number
    }

    private fun loadMissedCalls() {
        val state = CallPolicyStore.effectiveState()
        val generation = loads.begin()
        CoroutineScope(Dispatchers.Main).launch {
            // Design 106 §3.1: one read for the badges and Siste.
            val loaded = withContext(Dispatchers.IO) {
                val view = phoneBookView(state) { CallSystem.isEmergencyOutgoing(this@PhoneBookActivity, it) }
                MissedCallsRepo.load(this@PhoneBookActivity, state, view)
            }
            if (!isDestroyed && loads.isLatest(generation)) {
                val result = loaded.missed
                missed = result
                snapshot = loaded
                // The visit's top: fixed by its first load, joined by later ones (design 79 §3.2). A new visit starts at
                // the list's top - never wherever the last visit's rows took the scroll (emulator run: a call in between).
                if (visitTop == null) toTop = true
                visitTop = visitTopAfter(visitTop, result)
                filter()
                bindRecent()
                openPendingSheet()
            }
        }
    }

    private fun openPendingSheet() {
        val number = pendingSheet ?: return
        pendingSheet = null
        if (isFinishing) return
        val contact = missedCallContact(number, phoneBookView(CallPolicyStore.effectiveState()) { CallSystem.isEmergencyOutgoing(this, it) })
            ?: return
        ContactSheet.show(this, contact, missed[contact.number]) { loadMissedCalls() }
    }

    /**
     * What a list works from, gathered once per [refresh] (design 79 §3.7, QA N7, code QA M2): the view, the emergency
     * numbers, the unread counts, the requests and every contact's buttons and Melding app label. A keystroke only runs
     * [filter] over it - no binder call, prefs decode or label lookup per character.
     */
    private class Inputs(
        val view: PhoneBookView,
        val emergency: Set<String>,
        val unread: Map<String, Int>,
        val requests: List<RequestRow>,
        val canAsk: Boolean,
        val actions: Map<String, ContactActions>,
        val messageApps: Map<String, String>,
    )

    private var inputs: Inputs? = null

    /** The page and the list's inputs again: at resume, from every listener (policy, messages, requests) and
     * when the form opens or closes - then [filter]. */
    private fun render() {
        if (!::requestForm.isInitialized) return
        val state = CallPolicyStore.effectiveState()
        val rules = (state as? CallPolicyState.Managed)?.rules
        val canAsk = requestForm.canAsk()
        if (requestForm.isOpen && !canAsk) requestForm.close()
        canAskNow = canAsk
        // Design 106 §3.5 (L1): calls going off (or the form opening) leaves Siste for Kontakter, its marks written.
        val switchShown = phoneBookSwitchShown(canAsk, requestForm.isOpen)
        binding.phoneBookTabs.visibility = if (switchShown) View.VISIBLE else View.GONE
        if (tabAfter(tab, switchShown, entered = false) != tab) showTab(PhoneBookTab.CONTACTS)
        bindHeader(canAsk)
        val view = phoneBookView(state) { CallSystem.isEmergencyOutgoing(this, it) }
        val info = when {
            requestForm.isOpen -> null
            rules == null || !rules.callsEnabled -> getString(R.string.calls_off)
            view.contacts.isEmpty() -> getString(R.string.calls_phone_book_empty)
            else -> null
        }
        contactsInfo = info
        bindInfo()
        // The name's real size: Android's font scaling (non-linear since Android 14) of the row's 16 sp, in dp.
        val metrics = resources.displayMetrics
        val nameDp = android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, ROW_NAME_SP, metrics) / metrics.density
        adapter.layout = rowLayout(contentWidthDp(), nameDp)
        val emergency = view.contacts.mapNotNullTo(mutableSetOf()) { c -> c.number.takeIf { CallSystem.isEmergencyOutgoing(this, it) } }
        // The apps' usability asked once per package, each app's label read once (code QA M2).
        val defaultSms = CallSystem.defaultSmsPackage(this)
        val base = ContactSheet.usablePackages(this)
        val asked = HashMap<String, Boolean>()
        val usable: (String) -> Boolean = { pkg -> asked.getOrPut(pkg) { base(pkg) } }
        val rooms = ElementRoomStore.lookup(this)
        elementLabel = AppDisplay.shownLabel(this, MessagePackages.ELEMENT_X).toString()
        val calls = OngoingCalls.states
        val labels = HashMap<String, String>()
        val actions = HashMap<String, ContactActions>()
        val messageApps = HashMap<String, String>()
        for (contact in view.contacts) {
            val key = rowKey(contact)
            val a = contactActions(contact, rules, contact.number in emergency, calls, defaultSms, usable, rooms)
            actions[key] = a
            (a.message as? MessageAction.App)?.intent?.packageName?.let { pkg ->
                messageApps[key] = labels.getOrPut(pkg) { AppDisplay.shownLabel(this, pkg).toString() }
            }
        }
        inputs = Inputs(
            view, emergency, view.contacts.associate { it.number to SmsHome.unreadFrom(it.number) },
            if (canAsk) contactRequestRows(ContactRequests.records(this)) else emptyList(), canAsk, actions, messageApps,
        )
        // The search: only while the kid can ask (calls managed and on); calls going off clears it.
        if (!canAsk && search.query.isNotEmpty()) search.clear()
        filter()
    }

    private fun rowKey(contact: RuleContact) = "${contact.id}:${contact.number}"

    /** The rows from the cached [inputs], the missed calls, the visit's top and the query - the search's keystroke,
     * a missed-call load. */
    private fun filter() {
        // Back follows the form, the query and Siste at once, also while the visit's first load is pending (code QA L10).
        back.isEnabled = phoneBookBackEnabled(requestForm.isOpen, search.query, tab == PhoneBookTab.RECENT)
        val inputs = inputs ?: return
        // The list waits for the visit's first missed calls (its top), so it never reorders under the kid's finger.
        val top = visitTop ?: return
        val query = search.query
        val list = phoneBookRows(
            inputs.view, inputs.emergency, missed, inputs.unread, top, inputs.requests, query,
            listCollator(resources.configuration.locales[0]), inputs.canAsk,
        )
        // The pill hides while the form is up (QA N4), and on Siste (design 106 §3.5: search only in Kontakter).
        val contacts = tab == PhoneBookTab.CONTACTS
        search.setShown(list.search && !requestForm.isOpen && contacts)
        val hits = list.rows.count { it is PhoneBookRow.Contact || it is PhoneBookRow.Waiting }
        search.announce(if (searching(query) && list.search) hits else null)
        bindEmpty(list)
        binding.phoneBookList.visibility = if (requestForm.isOpen || list.nothingFound || !contacts) View.GONE else View.VISIBLE
        val items = list.rows.map { row ->
            if (row !is PhoneBookRow.Contact) return@map PhoneBookItem(row)
            val key = rowKey(row.contact)
            PhoneBookItem(row, inputs.actions[key], inputs.messageApps[key])
        }
        adapter.submitList(items) {
            // With the offset: after a filter took the rows on screen away, a plain scrollToPosition(0) finds no view at
            // 0 and lays out from the end - a first hit taller than the list (the keyboard up, a large text size) then
            // shows with its top cut.
            if (toTop) {
                binding.phoneBookList.stopScroll()
                (binding.phoneBookList.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(0, 0)
            }
            toTop = false
            noteSightings()
        }
    }

    /** The request form's circle (design 79 §5.5): "Bilde av Lina" - the picker or the camera into a draft, or "Fjern
     * bildet" for the form's own draft. */
    private fun requestPicture() {
        val name = binding.phoneBookRequestForm.contactRequestName.text?.toString()?.trim().orEmpty()
        val title = if (name.isEmpty()) getString(R.string.contact_request_photo_add) else getString(R.string.kid_photo_title, name)
        requestPictureSheet = KidPhotoSheet.show(
            this, title,
            onChoose = { camera -> draftLauncher.launch(KidPhotoActivity.forDraft(this, name, camera)) },
            onRemove = if (requestForm.draft != null) ({ requestForm.setDraft(null) }) else null,
            removeWords = R.string.kid_photo_remove_draft,
        )
    }

    /** "Fant ingenting" (design 79 §4.1), and "Be om å legge til «Lina»" while the kid can ask: the form with the
     * query as the name. */
    private fun bindEmpty(list: PhoneBookList) {
        val empty = binding.phoneBookEmpty
        empty.root.visibility = if (list.nothingFound && !requestForm.isOpen && tab == PhoneBookTab.CONTACTS) View.VISIBLE else View.GONE
        val ask = list.askName
        empty.kidSearchAsk.visibility = if (ask == null) View.GONE else View.VISIBLE
        empty.kidSearchAsk.text = ask?.let { getString(R.string.phone_book_ask_named, it) }
        empty.kidSearchAsk.setOnClickListener { if (ask != null) requestForm.open(ask) }
    }

    /** The page's content width: the window less the page's 16 dp sides (288 dp on the Jelly Star). */
    private fun contentWidthDp(): Float = resources.configuration.screenWidthDp - 2 * PAGE_SIDE_DP

    /** The header: "Telefonbok" with the "+" ("Be om ny kontakt", design 79 §7) while the kid can ask, or the form's
     * title while it is open (Back closes it). */
    private fun bindHeader(canAsk: Boolean) {
        val header = binding.phoneBookHeader
        if (requestForm.isOpen) {
            KidHeader.bind(this, header, R.string.contact_request_title, back = R.string.kid_back)
        } else {
            KidHeader.bind(this, header, R.string.calls_phone_book, back = R.string.kid_back)
        }
        val plus = canAsk && !requestForm.isOpen && tab == PhoneBookTab.CONTACTS
        binding.phoneBookPlus.visibility = if (plus) View.VISIBLE else View.GONE
        // The title never runs under the "+" (font scale 2.0).
        header.root.setPaddingRelative(0, 0, if (plus) resources.getDimensionPixelSize(R.dimen.phone_book_plus_room) else 0, 0)
    }

    /** The rows on screen now (at least half of each), the top ones with a red line, for the seen rule (§3.2): only
     * while the phone book is resumed and focused and its list is up. */
    private fun noteSightings() {
        if (!::binding.isInitialized) return
        val list = binding.phoneBookList
        val active = resumed && hasWindowFocus() && list.visibility == View.VISIBLE
        val visible = mutableMapOf<String, Long>()
        if (active) {
            val rows = adapter.currentList
            for (i in 0 until list.childCount) {
                val child = list.getChildAt(i)
                val position = list.getChildAdapterPosition(child)
                val row = rows.getOrNull(position)?.row as? PhoneBookRow.Contact ?: continue
                if (!row.top) continue
                val at = row.shownMissedAtMs ?: continue
                val shown = minOf(child.bottom, list.height) - maxOf(child.top, 0)
                if (child.height > 0 && shown * 2 >= child.height) visible[row.contact.number] = at
            }
            // Design 77 §14, code QA L9: an answer counts as shown only once its row was laid out on the visible list.
            val answers = (0 until list.childCount).mapNotNull { rows.getOrNull(list.getChildAdapterPosition(list.getChildAt(it)))?.row as? PhoneBookRow.Answer }
            if (answers.any { answersCounted.add(it.request.id) }) requestForm.answersShown()
        }
        sightings.update(visible, active, SystemClock.elapsedRealtime())
        // Design 106 §3.4: Siste's red, unseen rows - per contact the newest of its visible ones.
        val recent = binding.phoneBookRecent
        val recentActive = resumed && hasWindowFocus() && tab == PhoneBookTab.RECENT && recent.visibility == View.VISIBLE
        val shown = mutableListOf<Pair<RecentRow, Boolean>>()
        if (recentActive) {
            for (i in 0 until recent.childCount) {
                val child = recent.getChildAt(i)
                val row = recentAdapter.rowAt(recent.getChildAdapterPosition(child)) ?: continue
                val onScreen = minOf(child.bottom, recent.height) - maxOf(child.top, 0)
                shown += row to (child.height > 0 && onScreen * 2 >= child.height)
            }
        }
        recentSightings.update(recentVisible(shown), recentActive, SystemClock.elapsedRealtime())
    }

    // ---- Siste (design 106 §3) ----

    /** The kid (the switch, Back) or the book (an ask, calls going off) moves to [next]. Leaving Siste writes its marks
     * and reloads, so the badges follow at once (L3); showing it clears the search and starts it at its top. */
    private fun showTab(next: PhoneBookTab) {
        if (next == tab) {
            applyTab()
            return
        }
        if (tab == PhoneBookTab.RECENT) writeRecentMarks()
        tab = next
        if (next == PhoneBookTab.RECENT) {
            search.clear()
            recentToTop = true
        }
        applyTab()
        if (next == PhoneBookTab.CONTACTS) loadMissedCalls()
    }

    /** Siste's sightings become seen marks (one write, up to each contact's newest row it showed), and start again. */
    private fun writeRecentMarks() {
        MissedCallsRepo.markSeen(this, recentSightings.seen(SystemClock.elapsedRealtime()))
        recentSightings = TopSightings()
    }

    /** The page for [tab]: the switch, the lists, the search and the "+", the info line, Back. */
    private fun applyTab() {
        if (!::requestForm.isInitialized) return
        settingTab = true
        binding.phoneBookTabs.check(if (tab == PhoneBookTab.RECENT) R.id.phone_book_tab_recent else R.id.phone_book_tab_contacts)
        settingTab = false
        binding.phoneBookRecent.visibility = if (tab == PhoneBookTab.RECENT && !requestForm.isOpen) View.VISIBLE else View.GONE
        bindHeader(canAskNow)
        filter()
        bindRecent()
    }

    /** The Siste tab's badge and description, the info line, and - on Siste - its rows from the last load. */
    private fun bindRecent() {
        val loaded = snapshot
        val count = loaded?.badge ?: 0
        val label = getString(R.string.phone_book_tab_recent)
        // The badge only while Kontakter is chosen (the mockup): on Siste the rows say it.
        val badge = if (tab == PhoneBookTab.CONTACTS) com.kidslauncher.mdm.ui.home.badgeText(count) else null
        binding.phoneBookTabRecent.text = if (badge == null) {
            label
        } else {
            android.text.SpannableStringBuilder(label).append("\u2060")
                .append(badge, RecentBadgeSpan(this), android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        binding.phoneBookTabRecent.contentDescription =
            if (badge == null) label else resources.getQuantityString(R.plurals.phone_book_recent_badge, count, count)
        bindInfo()
        if (tab != PhoneBookTab.RECENT || loaded == null) return
        recentAdapter.timeBelow = recentTimeBelow(resources.configuration.fontScale)
        recentAdapter.elementLabel = elementLabel
        val items = recentItems(loaded.recent, java.time.LocalDate.now(), inputs?.emergency.orEmpty())
        recentAdapter.submitList(items) {
            if (recentToTop) {
                binding.phoneBookRecent.stopScroll()
                (binding.phoneBookRecent.layoutManager as LinearLayoutManager).scrollToPositionWithOffset(0, 0)
            }
            recentToTop = false
            noteSightings()
        }
    }

    /** The info line: Kontakter's ("calls are off", an empty book), or on Siste "Ingen anrop de siste 14 dagene" - only
     * once the log was read (§3.1, L6: an unreadable log never says "no calls"). */
    private fun bindInfo() {
        val loaded = snapshot
        val info = when {
            tab == PhoneBookTab.CONTACTS -> contactsInfo
            loaded != null && loaded.logRead && loaded.recent.isEmpty() -> getString(R.string.recent_empty)
            else -> null
        }
        binding.phoneBookInfo.visibility = if (info == null) View.GONE else View.VISIBLE
        binding.phoneBookInfo.text = info
    }

    /** A row's Ring (design 79 §3.5): an emergency number asks first ("Ringe Ambulanse?" - a one-tap 112 from a
     * scrolling list costs more than a call to Mamma); every other contact is called at once. Either deals with the
     * contact's missed calls. */
    private fun call(item: PhoneBookItem) {
        val row = item.row as? PhoneBookRow.Contact ?: return
        val contact = row.contact
        if (!secondCallAllowed(OngoingCalls.states, row.emergency)) return
        if (!row.emergency) {
            MissedCallsRepo.markSeen(this, contact.number)
            CallSystem.placeCall(this, contact.number)
            return
        }
        // An emergency number's confirm is night in both looks, as the emergency menu's (design 78 §2).
        AlertDialog.Builder(nightOver(this), R.style.KidAlertDialog)
            .setTitle(getString(R.string.calls_confirm_title, contact.name))
            .setPositiveButton(R.string.calls_call) { _, _ ->
                MissedCallsRepo.markSeen(this, contact.number)
                CallSystem.placeCall(this, contact.number)
            }
            .setNegativeButton(R.string.calls_cancel, null)
            .let(::showNight)
    }

    /** A row's Melding: our own thread, or the app the card would open - and the contact's missed calls are dealt
     * with (design 79 §3.2 rule 3). */
    private fun message(item: PhoneBookItem) {
        val row = item.row as? PhoneBookRow.Contact ?: return
        when (val action = item.actions?.message) {
            MessageAction.OwnThread -> {
                MissedCallsRepo.markSeen(this, row.contact.number)
                com.kidslauncher.mdm.ui.messages.MessagesScreens.startThread(this, null, row.contact.number)
            }
            is MessageAction.App -> {
                MissedCallsRepo.markSeen(this, row.contact.number)
                ContactSheet.openMessage(this, action.intent)
            }
            MessageAction.None, null -> {}
        }
    }

    /** A `tel:`/`voicemail:` link from another app ([entryRoute]): checked against the call rules, then "Ring X?". */
    private fun handleNumber(link: String?) {
        if (link == null) return
        val raw = PhoneNumbers.numberFromHandle(link)
        val state = CallPolicyStore.effectiveState()
        if (decideOutgoing(raw, state, CallSystem.isEmergencyOutgoing(this, raw)) == Verdict.BLOCK || raw == null) {
            AlertDialog.Builder(this)
                .setMessage(R.string.calls_not_allowed)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }
        val name = (state as? CallPolicyState.Managed)?.rules?.contactFor(raw)?.name ?: raw
        // The stored number we checked, not the string in the link (QA step 2 #6).
        val dial = outgoingDialTarget(raw, state, CallSystem.isEmergencyOutgoing(this, raw)) ?: raw
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.calls_confirm_title, name))
            .setPositiveButton(R.string.calls_call) { _, _ -> CallSystem.placeCall(this, dial) }
            .setNegativeButton(R.string.calls_cancel, null)
            .show()
    }

    companion object {
        /** The phone number (as in the call rules) of the contact whose sheet to open. */
        const val EXTRA_MISSED_CONTACT = "com.kidslauncher.mdm.extra.MISSED_CONTACT"

        /** The link another app asked to call ([PhoneBookAsk.number]); the entry's forward to the page only. */
        private const val EXTRA_ASK_NUMBER = "me.vibb.launcher.extra.PHONE_BOOK_NUMBER"

        /** The page's sides (`activity_phone_book.xml`). */
        private const val PAGE_SIDE_DP = 16f

        private const val STATE_TOP_NUMBERS = "visit_top_numbers"
        private const val STATE_TOP_TIMES = "visit_top_times"
        private const val STATE_SEEN_NUMBERS = "visit_seen_numbers"
        private const val STATE_SEEN_AT = "visit_seen_at"
        private const val STATE_SEEN_MS = "visit_seen_ms"
        private const val STATE_SEEN_UP_TO = "visit_seen_up_to"
        private const val STATE_TAB = "phone_book_tab"
        private const val STATE_RECENT_NUMBERS = "recent_seen_numbers"
        private const val STATE_RECENT_AT = "recent_seen_at"
        private const val STATE_RECENT_MS = "recent_seen_ms"
        private const val STATE_RECENT_UP_TO = "recent_seen_up_to"

        /**
         * The one way to open the phone book from inside the process - Home's tile and the time-rule screen's row: a
         * task of its own (`FLAG_ACTIVITY_NEW_TASK` with the manifest's `.phonebook` affinity). From Home's tile
         * ([toPage] false) a task already there comes back as it was. The time-rule screen's row asks [toPage]:
         * `FLAG_ACTIVITY_CLEAR_TOP` lands on the page itself (singleTop: `onNewIntent`), so whatever was above it in the
         * task - the kid's picture screen, the camera - is closed and the page's own rule check runs (code QA H1). The
         * class is the applied look's (design 78 §12.2 R5); the intent goes straight into a start (R7) - a notification
         * or the system names [PhoneBookEntryActivity] instead. Never an action or data: the task's base intent stays
         * the same whoever started it (code QA M1).
         */
        fun intent(context: Context, toPage: Boolean = false): Intent =
            Intent(context, PageKind.PHONE_BOOK.current()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).apply {
                if (toPage) addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }

        /**
         * The entry's start ([PhoneBookEntryActivity]): [intent] to the page itself with [ask] as extras (the number,
         * the missed-call contact) - nothing else of an outside intent ([entryRoute]). On the page itself, also in a task
         * already there: a tel: link or a missed call closes the kid's picture screen above it, never a second phone book.
         */
        fun forward(context: Context, ask: PhoneBookAsk): Intent = intent(context, toPage = true).apply {
            if (ask.number != null) putExtra(EXTRA_ASK_NUMBER, ask.number)
            if (ask.contact != null) putExtra(EXTRA_MISSED_CONTACT, ask.contact)
        }

        /** The missed-call notification's tap and the glance's missed-calls row: the call log, i.e. the phone book
         * (with [contactNumber]'s sheet) - or, while calls are unmanaged and our dialer role is still held, passed on
         * to the system dialer (qa-12-code #1). It names the look-less entry, never a page class (R7): a
         * `PendingIntent` outlives the look it was made in. */
        fun missedCallsIntent(context: Context, contactNumber: String?): Intent =
            Intent(context, PhoneBookEntryActivity::class.java).setAction(Intent.ACTION_VIEW).setType(CALL_LOG_TYPE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).apply {
                    if (contactNumber != null) putExtra(EXTRA_MISSED_CONTACT, contactNumber)
                }

        /** Under a time rule the time-rule screen comes back on top unless the rule allows calls
         * ([phoneBookUsableUnder]); an error reading the rules is no (the time-rule screen). True when it redirected. */
        internal fun redirectIfLocked(activity: android.app.Activity): Boolean {
            if (LauncherPreferences.mdm().lockReason() == LockReason.NONE) return false
            if (usableNow(activity)) return false
            LockActivity.start(activity)
            return true
        }

        /** The time lock in force now leaves the phone book usable ([phoneBookUsableUnder]). */
        internal fun usableNow(context: Context): Boolean = try {
            phoneBookUsableUnder(
                com.kidslauncher.mdm.timerules.TimeRulesRuntime.snapshot(context, com.kidslauncher.mdm.server.currentPolicyDecision().policy).lock,
            )
        } catch (e: Exception) {
            Log.w("PhoneBookActivity", "Couldn't read the time rules: ${e.javaClass.simpleName}")
            false
        }
    }
}
