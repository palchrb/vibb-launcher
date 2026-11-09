package com.kidslauncher.mdm.calls

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.view.View
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.kidslauncher.mdm.R
import com.kidslauncher.mdm.appdisplay.AppDisplay
import com.kidslauncher.mdm.databinding.ActivityPhoneBookBinding
import com.kidslauncher.mdm.sms.SmsHome
import com.kidslauncher.mdm.ui.KidHeader
import com.kidslauncher.mdm.ui.KidInsets
import com.kidslauncher.mdm.ui.UIObjectActivity
import com.kidslauncher.mdm.ui.search.KidSearchField
import com.kidslauncher.mdm.ui.search.PhoneBookBack
import com.kidslauncher.mdm.ui.search.listCollator
import com.kidslauncher.mdm.ui.search.phoneBookBack
import com.kidslauncher.mdm.ui.search.searching
import com.kidslauncher.mdm.ui.wallpaper.KidInk
import com.kidslauncher.mdm.ui.wallpaper.WallpaperGround
import com.kidslauncher.mdm.ui.wallpaper.WallpaperStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The phone book - the kid's place to call from, and the DIAL / `tel:` handler the dialer role requires. Since design
 * 79 one list of solid night rows on the kid's wallpaper ([phoneBookRows]): a request's answer once at the top, the
 * contacts with an unseen missed call newest first, everyone else A-Å, the kid's open requests under "Venter på svar",
 * the emergency numbers last. Each row has Ring and Melding ([contactActions], the card's rule); a tap anywhere else
 * opens [ContactSheet]. The header's "+" opens the contact request ([ContactRequestForm]). There is no free keypad;
 * emergency numbers still work, from here (their Ring asks first) and from the lock screen's Emergency button.
 *
 * **Seen** (design 79 §3.2): the visit's order is fixed when the phone book opens ([visitTopAfter]: nothing leaves the
 * top while it is in front, a new missed call joins); a top row on screen for a second while the phone book is resumed
 * and focused ([TopSightings]) is marked seen when the kid leaves ([onStop], not for a rotation) - up to the call it
 * showed. The next visit lists it in its place. The card, Ring and Melding mark it seen at once.
 *
 * A `tel:` number from another app is checked with [decideOutgoing]: allowed asks "Call X?", anything else says it
 * isn't allowed.
 *
 * Missed calls (design 12): it is also the call-log viewer (`VIEW` `vnd.android.cursor.dir/calls`, pinned as a
 * persistent preferred activity) - while calls are unmanaged it passes that on to the system dialer. Our missed-call
 * notification opens it, with [EXTRA_MISSED_CONTACT] when the calls came from one contact (that contact's sheet opens,
 * once). Being opened clears the notification ([MissedCallNotifier.dismiss]).
 */
class PhoneBookActivity : UIObjectActivity() {

    private lateinit var binding: ActivityPhoneBookBinding
    private lateinit var adapter: PhoneBookAdapter
    /** Design 77 §14: the kid's contact request - the header's "+" and the form. */
    private lateinit var requestForm: ContactRequestForm
    /** Design 79 §4: the search pill (from 8 contacts). */
    private lateinit var search: KidSearchField

    /** Back (QA N4): the request form first, then the search's text, then the phone book leaves ([phoneBookBack]). */
    private val back = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            when (phoneBookBack(requestForm.isOpen, search.query)) {
                PhoneBookBack.CLOSE_FORM -> requestForm.close()
                PhoneBookBack.CLEAR_SEARCH -> search.clear()
                PhoneBookBack.LEAVE -> {}
            }
            render()
        }
    }
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

    /** The contact whose sheet the missed-call notification asked for, opened once the missed calls are loaded. */
    private var pendingSheet: String? = null
    private val photoListener: () -> Unit = {
        if (::adapter.isInitialized) adapter.photosChanged()
        if (::requestForm.isInitialized) requestForm.bindPicture()
    }

    /** Design 79 §5.5: the request form's picture comes back from [KidPhotoActivity] as a draft file. */
    private val draftLauncher = registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.StartActivityForResult()) { result ->
        val name = result.data?.getStringExtra(KidPhotoActivity.RESULT_DRAFT_FILE)
        if (result.resultCode == RESULT_OK && name != null && ::requestForm.isInitialized && requestForm.isOpen) requestForm.setDraft(name)
    }
    /** Design 77 §6.7: the rows' unread messages follow the engine. */
    private val messagesListener: () -> Unit = { render() }
    private val wallpaperListener: () -> Unit = { render() }
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
        CallPolicyStore.ensureLoaded(this)
        if (passOnCallLog(intent)) {
            finish()
            return
        }
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
        // The keyboard (the request form): the page's bottom takes it (design 79 §3.5, QA L3).
        KidInsets.apply(binding.root, ime = true)
        binding.phoneBookPlus.setOnClickListener { requestForm.open() }
        search = KidSearchField(binding.phoneBookSearch.root, R.string.phone_book_search) {
            // A new query shows its best hits from the top, once the list has them.
            toTop = true
            render()
        }
        requestForm = ContactRequestForm(this, binding, onModeChanged = { render() }, onSent = { search.clear() }, onPicture = ::requestPicture)
        // Design 79 §5.5: a draft older than a day goes.
        ContactRequests.sweepDrafts(this)
        onBackPressedDispatcher.addCallback(this, back)
        restoreVisit(savedInstanceState)
        requestForm.restore(savedInstanceState)
        // Once: not again after a recreation.
        if (savedInstanceState == null) takeMissedContact(intent)
        handleNumber(intent)
    }

    /** The kid's wallpaper behind the phone book, like Home (design 08 §3). */
    override fun showsSystemWallpaper() = true

    override fun onStart() {
        super.onStart()
        ContactPhotos.addListener(photoListener)
        WallpaperStore.addListener(wallpaperListener)
        ElementMissedCallStore.addListener(elementMissedListener)
        SmsHome.addListener(messagesListener)
        ContactRequests.addListener(requestsListener)
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
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        // Also refreshes whether the system wallpaper is still ours (in the background).
        WallpaperStore.refreshAsync(this)
        render()
        loadMissedCalls()
        // Opening the phone book deals with the missed-call notification (design 12, QA #3).
        MissedCallNotifier.dismiss(this)
        noteSightings()
    }

    override fun onPause() {
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
        WallpaperStore.removeListener(wallpaperListener)
        ElementMissedCallStore.removeListener(elementMissedListener)
        SmsHome.removeListener(messagesListener)
        ContactRequests.removeListener(requestsListener)
        contentResolver.unregisterContentObserver(callLogObserver)
        if (!isChangingConfigurations && ::requestForm.isInitialized) {
            // The visit ends: the top rows it showed are seen (design 79 §3.2 rule 1) - Home's badge goes now too.
            for ((number, upToMs) in sightings.seen(SystemClock.elapsedRealtime())) MissedCallsRepo.markSeen(this, number, upToMs)
            sightings = TopSightings()
            visitTop = null
            // Design 77 §14: an answer shows once - the ones shown go when the phone book is left.
            requestForm.left()
        }
        super.onStop()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (!::requestForm.isInitialized) return
        requestForm.save(outState)
        val top = visitTop ?: return
        outState.putStringArray(STATE_TOP_NUMBERS, top.keys.toTypedArray())
        outState.putLongArray(STATE_TOP_TIMES, top.values.toLongArray())
        val seen = sightings.state(SystemClock.elapsedRealtime())
        outState.putStringArray(STATE_SEEN_NUMBERS, seen.map { it.number }.toTypedArray())
        outState.putLongArray(STATE_SEEN_AT, seen.map { it.atMs }.toLongArray())
        outState.putLongArray(STATE_SEEN_MS, seen.map { it.accumulatedMs }.toLongArray())
        outState.putLongArray(STATE_SEEN_UP_TO, seen.map { it.seenUpToMs ?: Long.MIN_VALUE }.toLongArray())
    }

    /** A rotation keeps the visit's order and what it has seen (design 79 §3.2). */
    private fun restoreVisit(state: Bundle?) {
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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (passOnCallLog(intent)) return
        takeMissedContact(intent)
        handleNumber(intent)
    }

    /** The notification's contact, only when it is in the phone book now (QA #8); the extra is
     * removed, so it opens once. */
    private fun takeMissedContact(intent: Intent?) {
        val number = intent?.getStringExtra(EXTRA_MISSED_CONTACT) ?: return
        intent.removeExtra(EXTRA_MISSED_CONTACT)
        pendingSheet = number
    }

    /**
     * The call log asked for while calls are unmanaged: the system dialer's, explicitly - our pin
     * is permanent, so it stays ours to pass on (as the Play link blocker does). Managed (or
     * rules unknown): the phone book is the call log. A fresh intent: never the caller's extras.
     * The package comes from what `apply()` cached - no Telecom call on the main thread
     * (qa-12-code #4). Viewing the call log deals with our missed-call notification too.
     */
    private fun passOnCallLog(intent: Intent?): Boolean {
        if (intent == null || !isCallLogView(intent.action, intent.type, intent.dataString)) return false
        if (CallPolicyStore.effectiveState() != CallPolicyState.Unmanaged) return false
        MissedCallNotifier.dismiss(this)
        val dialer = CallPrefs.systemDialer(this)
        if (dialer == null) Log.w("PhoneBookActivity", "No system dialer known yet - the call log isn't passed on")
        if (dialer != null && dialer != packageName) {
            try {
                startActivity(
                    Intent(Intent.ACTION_VIEW).setType(CALL_LOG_TYPE).setPackage(dialer)
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                )
            } catch (e: Exception) {
                Log.w("PhoneBookActivity", "The system dialer has no call log to pass on to", e)
            }
        }
        return true
    }

    private fun loadMissedCalls() {
        val state = CallPolicyStore.effectiveState()
        CoroutineScope(Dispatchers.Main).launch {
            val result = withContext(Dispatchers.IO) { MissedCallsRepo.contactMissed(this@PhoneBookActivity, state) }
            if (!isDestroyed) {
                missed = result
                // The visit's top: fixed by its first load, joined by later ones (design 79 §3.2). A new visit starts at
                // the list's top - never wherever the last visit's rows took the scroll (emulator run: a call in between).
                if (visitTop == null) toTop = true
                visitTop = visitTopAfter(visitTop, result)
                render()
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

    private fun render() {
        if (!::requestForm.isInitialized) return
        // The same copy of the wallpaper as Home draws (qa-launcher-small-a-code Q-1): no scale step between the two.
        val ink = WallpaperGround.apply(this, binding.root, ownGround = true).ink
        KidInk.label(binding.phoneBookHeader.kidHeaderTitle, ink)
        KidInk.label(binding.phoneBookInfo, ink, dim = true)
        adapter.ink = ink
        val state = CallPolicyStore.effectiveState()
        val rules = (state as? CallPolicyState.Managed)?.rules
        val canAsk = requestForm.canAsk()
        if (requestForm.isOpen && !canAsk) requestForm.close()
        bindHeader(canAsk)
        val view = phoneBookView(state) { CallSystem.isEmergencyOutgoing(this, it) }
        val info = when {
            requestForm.isOpen -> null
            rules == null || !rules.callsEnabled -> getString(R.string.calls_off)
            view.contacts.isEmpty() -> getString(R.string.calls_phone_book_empty)
            else -> null
        }
        binding.phoneBookInfo.visibility = if (info == null) View.GONE else View.VISIBLE
        binding.phoneBookInfo.text = info
        adapter.layout = rowLayout(contentWidthDp(), resources.configuration.fontScale)
        // The list waits for the visit's first missed calls (its top), so it never reorders under the kid's finger.
        val top = visitTop ?: return
        // Computed once per render (design 79 §3.7, QA N7); a keystroke only filters.
        val emergency = view.contacts.mapNotNullTo(mutableSetOf()) { c -> c.number.takeIf { CallSystem.isEmergencyOutgoing(this, it) } }
        val unread = view.contacts.associate { it.number to SmsHome.unreadFrom(it.number) }
        val requests = if (canAsk) contactRequestRows(ContactRequests.records(this)) else emptyList()
        // The search: only while the kid can ask (calls managed and on); calls going off clears it.
        if (!canAsk && search.query.isNotEmpty()) search.clear()
        val query = search.query
        val list = phoneBookRows(
            view, emergency, missed, unread, top, requests, query, listCollator(resources.configuration.locales[0]), canAsk,
        )
        // The pill hides while the form is up (QA N4).
        search.setShown(list.search && !requestForm.isOpen)
        KidInk.label(search.countLine, ink, dim = true)
        val hits = list.rows.count { it is PhoneBookRow.Contact || it is PhoneBookRow.Waiting }
        search.announce(if (searching(query) && list.search) hits else null)
        bindEmpty(list, ink)
        binding.phoneBookList.visibility = if (requestForm.isOpen || list.nothingFound) View.GONE else View.VISIBLE
        back.isEnabled = phoneBookBack(requestForm.isOpen, query) != PhoneBookBack.LEAVE
        adapter.submitList(itemsOf(list.rows, rules, emergency)) {
            if (toTop) binding.phoneBookList.scrollToPosition(0)
            toTop = false
            noteSightings()
        }
        // Design 77 §14: an answer shows once - this visit has shown it.
        if (list.rows.any { it is PhoneBookRow.Answer }) requestForm.answersShown()
    }

    /** The request form's circle (design 79 §5.5): "Bilde av Lina" - the picker or the camera into a draft, or "Fjern
     * bildet" for the form's own draft. */
    private fun requestPicture() {
        val name = binding.phoneBookRequestForm.contactRequestName.text?.toString()?.trim().orEmpty()
        val title = if (name.isEmpty()) getString(R.string.contact_request_photo_add) else getString(R.string.kid_photo_title, name)
        KidPhotoSheet.show(
            this, title,
            onChoose = { camera -> draftLauncher.launch(KidPhotoActivity.forDraft(this, name, camera)) },
            onRemove = if (requestForm.draft != null) ({ requestForm.setDraft(null) }) else null,
            removeWords = R.string.kid_photo_remove_draft,
        )
    }

    /** "Fant ingenting" (design 79 §4.1), and "Be om å legge til «Lina»" while the kid can ask: the form with the
     * query as the name. */
    private fun bindEmpty(list: PhoneBookList, ink: com.kidslauncher.mdm.ui.wallpaper.InkChoice) {
        val empty = binding.phoneBookEmpty
        empty.root.visibility = if (list.nothingFound && !requestForm.isOpen) View.VISIBLE else View.GONE
        KidInk.label(empty.kidSearchEmptyText, ink)
        val ask = list.askName
        empty.kidSearchAsk.visibility = if (ask == null) View.GONE else View.VISIBLE
        empty.kidSearchAsk.text = ask?.let { getString(R.string.phone_book_ask_named, it) }
        empty.kidSearchAsk.setOnClickListener { if (ask != null) requestForm.open(ask) }
    }

    /** The rows with their buttons ([contactActions]: the card's rule), the apps' usability asked once per render. */
    private fun itemsOf(rows: List<PhoneBookRow>, rules: CallRules?, emergency: Set<String>): List<PhoneBookItem> {
        val defaultSms = CallSystem.defaultSmsPackage(this)
        val base = ContactSheet.usablePackages(this)
        val asked = HashMap<String, Boolean>()
        val usable: (String) -> Boolean = { pkg -> asked.getOrPut(pkg) { base(pkg) } }
        val rooms = ElementRoomStore.lookup(this)
        val calls = OngoingCalls.states
        return rows.map { row ->
            if (row !is PhoneBookRow.Contact) return@map PhoneBookItem(row)
            val actions = contactActions(row.contact, rules, row.contact.number in emergency, calls, defaultSms, usable, rooms)
            val app = (actions.message as? MessageAction.App)?.intent?.packageName?.let { AppDisplay.shownLabel(this, it).toString() }
            PhoneBookItem(row, actions, app)
        }
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
        val plus = canAsk && !requestForm.isOpen
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
        }
        sightings.update(visible, active, SystemClock.elapsedRealtime())
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
        AlertDialog.Builder(this, R.style.KidAlertDialog)
            .setTitle(getString(R.string.calls_confirm_title, contact.name))
            .setPositiveButton(R.string.calls_call) { _, _ ->
                MissedCallsRepo.markSeen(this, contact.number)
                CallSystem.placeCall(this, contact.number)
            }
            .setNegativeButton(R.string.calls_cancel, null)
            .show()
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

    private fun handleNumber(intent: Intent?) {
        if (intent == null || isCallLogView(intent.action, intent.type, intent.dataString)) return
        val data = intent.data ?: return
        val raw = PhoneNumbers.numberFromHandle(data.toString())
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

        /** The page's sides (`activity_phone_book.xml`). */
        private const val PAGE_SIDE_DP = 16f

        private const val STATE_TOP_NUMBERS = "visit_top_numbers"
        private const val STATE_TOP_TIMES = "visit_top_times"
        private const val STATE_SEEN_NUMBERS = "visit_seen_numbers"
        private const val STATE_SEEN_AT = "visit_seen_at"
        private const val STATE_SEEN_MS = "visit_seen_ms"
        private const val STATE_SEEN_UP_TO = "visit_seen_up_to"

        fun intent(context: Context) = Intent(context, PhoneBookActivity::class.java)

        /** The missed-call notification's tap: the call log, i.e. the phone book (with
         * [contactNumber]'s sheet) - or, while calls are unmanaged and our dialer role is still
         * held, passed on to the system dialer (qa-12-code #1). */
        fun missedCallsIntent(context: Context, contactNumber: String?): Intent =
            intent(context).setAction(Intent.ACTION_VIEW).setType(CALL_LOG_TYPE)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK).apply {
                    if (contactNumber != null) putExtra(EXTRA_MISSED_CONTACT, contactNumber)
                }
    }
}
