package com.kidslauncher.mdm.ui.messages

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.telephony.SmsMessage
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.GridLayoutManager
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.recyclerview.widget.SimpleItemAnimator
import com.kidslauncher.mdm.R
import com.kidslauncher.mdm.calls.PhoneNumbers
import com.kidslauncher.mdm.databinding.ActivityThreadBinding
import com.kidslauncher.mdm.sms.SmsHome
import com.kidslauncher.mdm.sms.SmsNotifier
import com.kidslauncher.mdm.sms.SmsRuntime
import com.kidslauncher.mdm.sms.engine.Conversation
import com.kidslauncher.mdm.sms.engine.ConversationId
import com.kidslauncher.mdm.sms.engine.Direction
import com.kidslauncher.mdm.sms.engine.Message
import com.kidslauncher.mdm.sms.engine.Origin
import com.kidslauncher.mdm.sms.engine.ReactionCommand
import com.kidslauncher.mdm.sms.engine.ReactionOp
import com.kidslauncher.mdm.sms.engine.SendCommand
import com.kidslauncher.mdm.sms.engine.SendResult
import com.kidslauncher.mdm.sms.engine.SmsEngine
import com.kidslauncher.mdm.ui.ClockFormat
import com.kidslauncher.mdm.ui.KidHeader
import com.kidslauncher.mdm.ui.NoTextActions
import com.kidslauncher.mdm.ui.UIObjectActivity
import com.kidslauncher.mdm.ui.pictures.PicturePick
import com.kidslauncher.mdm.ui.day.LockWatch
import com.kidslauncher.mdm.ui.home.KidAvatars
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.io.File
import java.util.UUID

/**
 * One conversation (design 77 §6.7): bubbles - received ones on the tile surface, the kid's in the peach tint - with
 * day separators and times in the one clock format (P12), "Ukjent avsender" over a message the callback window let
 * in, and under the kid's messages "Sendt", "Levert" or "Ikke sendt - trykk for å prøve igjen" (a new `clientId`).
 * A long-press on a message (or TalkBack's action) asks "Slette meldingen?" in the screen (D18).
 *
 * The compose bar shows only while the gate would send ([composeState]); otherwise a line says why ("Du kan ikke sende
 * meldinger nå" under a time rule, the read-only history in "Av"/"Ikke styrt"). It sends through the engine's command
 * with the draft's own `clientId`, kept until the engine accepted that text (QA N2): two quick taps carry the same id
 * and send once. No field menu (`NoTextActions`), no links, no selectable text: nothing here opens another app.
 *
 * Opened on a conversation ([EXTRA_CONVERSATION]) or on a number ([EXTRA_NUMBER]: a contact's Message button, "Ny
 * melding", `smsto:`) - the first send makes the conversation. Reading it marks it read and clears its notification;
 * while it is in front its new messages get none ([SmsRuntime.threadInFront]).
 */
class ThreadActivity : UIObjectActivity() {

    private lateinit var binding: ActivityThreadBinding
    private val adapter = BubbleAdapter()

    private var conversationId: String? = null
    private var number: String? = null
    private var conversation: Conversation? = null
    private var messages: List<Message> = emptyList()
    private var limit = PAGE
    private var newestSeq = 0L

    /** Whether the list follows its newest message ([threadFollows]): at its end, until the kid scrolls up. */
    private var following = true
    private var compose = ComposeState.UNAVAILABLE
    private var resumed = false

    /** The draft's idempotency key (§6.2): one per text, new only once the engine accepted it. */
    private var clientId = UUID.randomUUID().toString()

    /** The message whose delete confirm is up. */
    private var selected: String? = null

    /** Design 81 D7: the message whose reaction panel is up, and whether the picker (D13) shows over the thread. */
    private var reacting: Message? = null
    private var picking = false

    /** D6: this screen's paint for [drawable] on the main thread (a `Paint` isn't shared between threads). */
    private val glyphPaint = android.graphics.Paint()
    private val pickerAdapter = PickerAdapter()

    /** D11: the picture picked for this draft (our own JPEG, held by the engine), and whether one is being made ready. */
    private var picture: PicturePick.Prepared? = null
    private var preparing = false

    /** The camera's output file while the camera is open (kept across a rebuild: a cache path, nothing more). */
    private var captureFile: java.io.File? = null

    private val pickLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode != RESULT_OK) return@registerForActivityResult
        // Code QA M3: only another app's content URI is read - never a file:// or one of our own providers.
        val source = PicturePick.picked(this, result.data?.data)
        if (source == null) {
            Toast.makeText(this, R.string.messages_picture_unusable, Toast.LENGTH_LONG).show()
            return@registerForActivityResult
        }
        usePicture(source, null)
    }

    private val captureLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val file = captureFile
        captureFile = null
        if (result.resultCode == RESULT_OK && file != null && file.length() > 0) usePicture(PicturePick.Source.Captured(file), file) else file?.delete()
    }

    /** Failed messages whose "Prøv igjen" was tapped, until the next read shows what became of them (code QA M1: the
     * link answers once; the retry's own id makes a second tap the engine's repeat anyway). */
    private val retrying = mutableSetOf<String>()

    /** One read at a time, one more for whatever came meanwhile (code QA L2). */
    private val reload = Coalescer { done -> loadNow(done) }

    /** Which thread the reads are for: a read started before [take] switched threads is dropped (verification N9). */
    private var generation = 0
    private var events: Job? = null
    private val homeListener: () -> Unit = { renderCompose() }
    private val lockWatch = LockWatch(this) { if (!MessagesScreens.redirectIfLocked(it)) renderCompose() }

    private val back = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            when {
                reacting != null -> hideReact()
                choosing() -> hideChoice()
                else -> cancelDelete()
            }
        }
    }

    /** Back closes whatever in-screen panel is up. */
    private fun updateBack() {
        back.isEnabled = selected != null || choosing() || reacting != null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityThreadBinding.inflate(layoutInflater)
        setContentView(binding.root)
        insets()
        val layout = LinearLayoutManager(this).apply { stackFromEnd = true }
        binding.threadList.layoutManager = layout
        binding.threadList.adapter = adapter
        (binding.threadList.itemAnimator as? SimpleItemAnimator)?.supportsChangeAnimations = false
        binding.threadList.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                // Only a scroll says where the kid reads (a layout reports 0): at the end the thread follows.
                if (dy != 0) follow(atEnd())
                // Older messages when the top is reached and the last page was full.
                if (dy < 0 && layout.findFirstVisibleItemPosition() == 0 && messages.size >= limit) {
                    limit += PAGE
                    load()
                }
            }
        })
        binding.threadList.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> afterLayout() }
        // Design 81 D7: a tap on the thread closes the reaction panel (and is spent on that).
        binding.threadList.addOnItemTouchListener(object : RecyclerView.SimpleOnItemTouchListener() {
            override fun onInterceptTouchEvent(rv: RecyclerView, e: android.view.MotionEvent): Boolean {
                if (reacting == null || e.actionMasked != android.view.MotionEvent.ACTION_DOWN) return false
                hideReact()
                return true
            }
        })
        val pickerGrid = binding.root.findViewById<RecyclerView>(R.id.messages_picker_grid)
        pickerGrid.layoutManager = GridLayoutManager(this, PICKER_COLUMNS).apply {
            spanSizeLookup = object : GridLayoutManager.SpanSizeLookup() {
                override fun getSpanSize(position: Int) = if (pickerAdapter.currentList.getOrNull(position) is PickerItem.Heading) PICKER_COLUMNS else 1
            }
        }
        pickerGrid.adapter = pickerAdapter
        binding.root.findViewById<View>(R.id.messages_picker_close).setOnClickListener { hideReact() }
        onBackPressedDispatcher.addCallback(this, back)
        NoTextActions.apply(binding.threadField)
        binding.threadField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = renderCounter()
        })
        binding.threadSend.setOnClickListener { send() }
        binding.threadPicture.setOnClickListener { addPicture() }
        binding.threadAttachmentRemove.setOnClickListener { removePicture() }
        captureFile = savedInstanceState?.getString(STATE_CAPTURE)?.let(::File)?.takeIf { it.parentFile?.name == "capture" }
        take(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // Only where the camera writes (a cache path): the draft's text and picture stay in memory (code QA N4).
        captureFile?.let { outState.putString(STATE_CAPTURE, it.path) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        keepDraft()
        take(intent)
        if (resumed) SmsRuntime.threadInFront = conversationId
        load()
    }

    /** The thread [intent] asks for, with its unsent draft and id from memory (a recreation keeps them: [keepDraft] ran
     * in `onPause`; the field saves nothing in the instance state - code QA N4). */
    private fun take(intent: Intent) {
        conversationId = intent.getStringExtra(EXTRA_CONVERSATION)
        number = intent.getStringExtra(EXTRA_NUMBER)
        conversation = null
        messages = emptyList()
        newestSeq = 0L
        following = true
        limit = PAGE
        cancelDelete()
        hideReact()
        generation++
        // The conversation's draft, else the number's (verification L8: a thread opened by number may have moved it).
        val kept = keptDraft(drafts, conversationId, number)
        val draft = intent.getStringExtra(EXTRA_DRAFT)?.take(SmsEngine.SEND_MAX)
        clientId = kept?.second ?: UUID.randomUUID().toString()
        binding.threadField.setText(draft ?: kept?.first.orEmpty())
        // Read once: a draft is never sent again by a recreation.
        intent.removeExtra(EXTRA_DRAFT)
        picture = conversationId?.let { pictureDrafts[it] } ?: pictureDrafts[draftKeyOf(null, number)]
        hideChoice()
        renderHeader()
        renderCompose()
        renderAttachment()
        adapter.submitList(emptyList())
    }

    override fun onStart() {
        super.onStart()
        SmsHome.addListener(homeListener)
        events = lifecycleScope.launch { SmsRuntime.events(this@ThreadActivity).collect { load() } }
    }

    override fun onResume() {
        super.onResume()
        if (MessagesScreens.redirectIfLocked(this)) return
        resumed = true
        lockWatch.start()
        SmsRuntime.threadInFront = conversationId
        renderCompose()
        load()
    }

    override fun onPause() {
        resumed = false
        SmsRuntime.threadInFront = null
        lockWatch.stop()
        keepDraft()
        super.onPause()
    }

    override fun onStop() {
        SmsHome.removeListener(homeListener)
        events?.cancel()
        super.onStop()
    }

    /** The root's bottom follows the keyboard (edge to edge, adjustResize alone doesn't), else the navigation bar. */
    private fun insets() {
        val root = binding.root
        val left = root.paddingLeft
        val top = root.paddingTop
        val right = root.paddingRight
        val bottom = root.paddingBottom
        root.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            val ime = insets.getInsets(WindowInsets.Type.ime())
            view.setPadding(left + bars.left, top + bars.top, right + bars.right, bottom + maxOf(bars.bottom, ime.bottom))
            WindowInsets.CONSUMED
        }
        root.requestApplyInsets()
    }

    // ---- reading ----

    private class Loaded(val conversation: Conversation?, val messages: List<Message>)

    /** A read, coalesced ([reload]): every engine event asks for one. */
    private fun load() = reload.request()

    /** The conversation (by its id, else the number's - one conversation, code QA L2) and its newest [limit] messages,
     * from the engine thread; read up to the newest while the thread is in front and follows ([threadReads]). */
    private fun loadNow(done: () -> Unit) {
        val id = conversationId
        val wanted = number?.let { normalised(it) }
        val count = limit
        val markRead = threadReads(resumed, following)
        val forThread = generation
        SmsRuntime.ask(this, { engine ->
            val found = id?.let { engine.conversation(ConversationId(it)) } ?: wanted?.let { engine.conversationWith(it) }
            val list = found?.let { engine.messages(it.id, null, count) }.orEmpty()
            // Not only with unread messages: a reaction (design 81) is never counted unread, and goes read with its bubble.
            if (markRead && found != null) engine.markRead(found.id, found.lastSeq, Origin.KID_SCREEN)
            Loaded(found, list)
        }) { loaded ->
            done()
            if (isDestroyed || loaded == null || forThread != generation) return@ask
            show(loaded, markRead)
        }
    }

    private fun show(loaded: Loaded, markedRead: Boolean) {
        val found = loaded.conversation
        if (found == null && conversationId != null && number == null) {
            // Deleted (here or elsewhere) and not a number to start again with: back to the list.
            finish()
            return
        }
        if (found != null) {
            if (conversationId != found.id.value) {
                drafts.remove(draftKey())?.let { drafts[found.id.value] = it }
                pictureDrafts.remove(draftKey())?.let { pictureDrafts[found.id.value] = it }
                conversationId = found.id.value
                // A rebuild of this screen finds the thread, and its draft, by the id now (verification L8).
                intent.putExtra(EXTRA_CONVERSATION, found.id.value)
                if (resumed) SmsRuntime.threadInFront = conversationId
            }
            found.participants.singleOrNull()?.address
                ?.takeUnless { it.startsWith(SmsEngine.NAME_PREFIX) || it.startsWith(SmsEngine.UNKNOWN_PREFIX) }
                ?.let { number = it }
            if (markedRead) SmsNotifier.cancel(applicationContext, found.id.value)
        } else {
            // Its last message deleted: the thread stays as a new one with the number.
            conversationId = null
            intent.removeExtra(EXTRA_CONVERSATION)
        }
        conversation = found
        messages = loaded.messages
        retrying.clear()
        if (selected != null && messages.none { it.id.value == selected }) cancelDelete()
        // Design 81 D7: the panel follows its bubble (a reaction came or went), and goes with it.
        reacting?.let { open ->
            val now = messages.firstOrNull { it.id == open.id }
            when {
                now == null -> hideReact()
                now != open && !picking -> showReact(now)
                else -> reacting = now
            }
        }
        renderHeader()
        renderCompose()
        val follows = threadFollows(following, newestSeq, messages)
        // The kid's own send while the list coasts: the fling's next step would say "not at the end" before this list is in.
        if (follows && !following) binding.threadList.stopScroll()
        follow(follows)
        newestSeq = messages.maxOfOrNull { it.seq } ?: 0L
        // Asked by every list, not only the one that brought the message: a list overtaken by the next read (the read
        // that marked it read, a delivery update) never gets its callback. A bubble taller than the list shows its
        // bottom after the layout ([afterLayout]).
        adapter.submitList(threadItems(messages, System.currentTimeMillis(), ZoneId.systemDefault())) {
            if (following && adapter.itemCount > 0) binding.threadList.scrollToPosition(adapter.itemCount - 1)
        }
    }

    /** [following] from now on. Turning on - the end reached, the kid's own send - reads once more: what came in while
     * the kid read older messages is read now ([threadReads]), and its notification goes. */
    private fun follow(now: Boolean) {
        val turnedOn = now && !following
        following = now
        if (turnedOn) load()
    }

    /** The last bubble's bottom in the list, `null` while that bubble isn't laid out. */
    private fun lastBottom(): Int? = binding.threadList.layoutManager?.let { layout ->
        layout.findViewByPosition(adapter.itemCount - 1)?.let(layout::getDecoratedBottom)
    }

    private fun listEnd(): Int = binding.threadList.height - binding.threadList.paddingBottom

    private fun atEnd(): Boolean = threadAtEnd(lastBottom(), listEnd(), KidAvatars.dp(this, END_SLOP_DP))

    /** Every layout of the list - a new bubble, one that grew or shrank, the keyboard, the compose bar's height: a
     * thread that follows goes on to its end ([threadEndGap]); one that doesn't follows again once its end shows (a
     * delete left less than a screen). */
    private fun afterLayout() {
        if (!following) follow(atEnd())
        val gap = threadEndGap(following, lastBottom(), listEnd())
        if (gap > 0) binding.threadList.scrollBy(0, gap)
    }

    private fun normalised(raw: String): String =
        PhoneNumbers.normalize(raw, MessagesScreens.rules()?.defaultCc ?: "47")?.value ?: raw

    private fun who(): Who {
        val rules = MessagesScreens.rules()
        conversation?.let { return whoOf(it, rules) }
        val wanted = number?.let(::normalised) ?: return Who.Unknown
        return rules?.contacts?.firstOrNull { it.number == wanted }?.name?.takeIf { it.isNotBlank() }?.let { Who.Name(it) } ?: Who.Number(wanted)
    }

    private fun renderHeader() {
        KidHeader.bind(this, binding.threadHeader, MessagesScreens.name(this, who()), null, null, back = R.string.kid_back, onAction = null)
    }

    // ---- the compose bar ----

    /** The bar while the gate would send now; else its line. The confirm takes the place of both. A change also
     * redraws the bubbles: "trykk for å prøve igjen" follows it ([retryOffered]). */
    @android.annotation.SuppressLint("NotifyDataSetChanged")
    private fun renderCompose() {
        val targets = targets()
        val judge = SmsRuntime.judge(this)
        val before = compose
        compose = composeState(judge, targets)
        if (before != compose && adapter.itemCount > 0) adapter.notifyDataSetChanged()
        // Design 81 D7 (L5): the panel was for the rules it was opened under - a change closes it.
        if (before != compose && reacting != null) closeReact()
        // D11: the "+" only while every recipient's "Bilder i meldinger" is on; a picture picked before a rule took that
        // away goes (the engine would refuse it anyway).
        val pictures = picturesAllowed(judge, targets)
        if (!pictures && compose == ComposeState.OPEN && picture != null) removePicture()
        if (!pictures && choosing()) hideChoice()
        binding.threadPicture.visibility = if (pictures) View.VISIBLE else View.GONE
        val covered = selected != null || choosing() || reacting != null
        val line = MessagesScreens.composeLine(compose)
        binding.threadCompose.visibility = if (!covered && line == null) View.VISIBLE else View.GONE
        binding.threadLine.visibility = if (!covered && line != null) View.VISIBLE else View.GONE
        if (line != null) binding.threadLine.setText(line)
        renderCounter()
    }

    /** Whom a send goes to: the conversation's participants (a group's, D12), else the number a new thread is for. */
    private fun targets(): List<String> =
        conversation?.participants?.map { it.address }?.takeIf { it.isNotEmpty() } ?: listOfNotNull(number?.let(::normalised))

    // ---- D11: a picture ----

    private fun choiceView(): View = binding.root.findViewById(R.id.messages_picture_choice)

    private fun choosing(): Boolean = ::binding.isInitialized && choiceView().visibility == View.VISIBLE

    /** "+": with a camera the parent allows, "Ta bilde" or "Velg bilde" in the screen; else the photo picker at once. */
    private fun addPicture() {
        if (compose != ComposeState.OPEN || preparing) return
        val camera = PicturePick.cameraPackage(this)
        if (camera == null) {
            openPicker()
            return
        }
        val panel = choiceView()
        panel.findViewById<View>(R.id.messages_picture_camera).setOnClickListener {
            hideChoice()
            openCamera(camera)
        }
        panel.findViewById<View>(R.id.messages_picture_library).setOnClickListener {
            hideChoice()
            openPicker()
        }
        panel.findViewById<View>(R.id.messages_picture_cancel).setOnClickListener { hideChoice() }
        panel.visibility = View.VISIBLE
        updateBack()
        renderCompose()
        val title = panel.findViewById<View>(R.id.messages_picture_choice_title)
        title.post { title.performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null) }
    }

    private fun hideChoice() {
        if (!::binding.isInitialized || !choosing()) return
        choiceView().visibility = View.GONE
        updateBack()
        renderCompose()
    }

    private fun openPicker() {
        val pick = PicturePick.pickIntent(this)
        if (pick == null) {
            Toast.makeText(this, R.string.messages_picture_not_now, Toast.LENGTH_LONG).show()
            return
        }
        try {
            pickLauncher.launch(pick)
        } catch (e: android.content.ActivityNotFoundException) {
            Toast.makeText(this, R.string.messages_picture_not_now, Toast.LENGTH_LONG).show()
        }
    }

    private fun openCamera(pkg: String) {
        val (intent, file) = PicturePick.captureIntent(this, pkg)
        captureFile = file
        try {
            captureLauncher.launch(intent)
        } catch (e: android.content.ActivityNotFoundException) {
            captureFile = null
            file.delete()
            Toast.makeText(this, R.string.messages_picture_not_now, Toast.LENGTH_LONG).show()
        }
    }

    /** The picked file to a picture the engine holds - off the main and the engine thread ([PicturePick.prepare]). */
    private fun usePicture(source: PicturePick.Source, file: File?) {
        val app = applicationContext
        val key = draftKey()
        val subId = conversation?.subId
        preparing = true
        renderAttachment()
        preparer.execute {
            val (prepared, problem) = try {
                PicturePick.prepare(app, source, subId)
            } finally {
                file?.delete()
            }
            if (prepared != null) pictureDrafts[key] = prepared
            runOnUiThread {
                preparing = false
                if (isDestroyed) return@runOnUiThread
                if (prepared != null && key == draftKey()) {
                    picture = prepared
                } else if (problem != null) {
                    val words = when (problem) {
                        PicturePick.Problem.TOO_LARGE -> R.string.messages_picture_too_large
                        PicturePick.Problem.UNAVAILABLE -> R.string.messages_picture_not_now
                        PicturePick.Problem.UNREADABLE -> R.string.messages_picture_unusable
                    }
                    Toast.makeText(this, words, Toast.LENGTH_LONG).show()
                }
                renderAttachment()
                renderCompose()
            }
        }
    }

    private fun removePicture() {
        picture = null
        pictureDrafts.remove(draftKey())
        renderAttachment()
    }

    /** The picked picture's place above the field: its preview, or "Gjør klar bildet …". */
    private fun renderAttachment() {
        val shown = picture
        binding.threadAttachment.visibility = if (shown != null || preparing) View.VISIBLE else View.GONE
        binding.threadAttachmentText.visibility = if (preparing) View.VISIBLE else View.GONE
        binding.threadAttachmentText.setText(R.string.messages_picture_preparing)
        binding.threadAttachmentRemove.visibility = if (shown != null && !preparing) View.VISIBLE else View.GONE
        binding.threadAttachmentImage.setImageBitmap(if (preparing) null else shown?.preview)
        binding.threadAttachmentImage.contentDescription = if (shown != null) getString(R.string.messages_picture) else null
    }

    /** "2 SMS" only past one message (§6.5: 160 GSM-7 or 70 UCS-2 characters). */
    private fun renderCounter() {
        val text = binding.threadField.text?.toString().orEmpty()
        val parts = if (text.isEmpty()) 1 else SmsMessage.calculateLength(text, false)[0]
        binding.threadCounter.visibility = if (parts > 1) View.VISIBLE else View.GONE
        binding.threadCounter.text = getString(R.string.messages_counter, parts)
    }

    /**
     * The send button: the field's text with the draft's [clientId] through the engine's send command (the gate inside
     * it). The field clears and the id moves on only when the engine accepted this text - a second tap before that
     * carries the same id and the same text, and the engine sends once (§6.2, QA N2).
     */
    private fun send(again: Boolean = false) {
        val text = binding.threadField.text?.toString()?.trim().orEmpty()
        val sentPicture = picture
        if ((text.isEmpty() && sentPicture == null) || preparing || compose != ComposeState.OPEN) return
        val app = applicationContext
        val key = draftKey()
        val command = SendCommand(
            clientId = clientId,
            conversationId = conversationId?.let(::ConversationId),
            to = if (conversationId == null) number else null,
            body = text,
            pictures = listOfNotNull(sentPicture?.ref),
            origin = Origin.KID_SCREEN,
        )
        SmsRuntime.ask(this, { SmsRuntime.send(app, command) }) { result ->
            when (result) {
                is SendResult.Accepted -> {
                    drafts.remove(key)
                    if (sentPicture != null) pictureDrafts.remove(key)
                    if (isDestroyed) return@ask
                    if (command.clientId == clientId) clientId = UUID.randomUUID().toString()
                    if (binding.threadField.text?.toString()?.trim() == text) binding.threadField.text = null
                    if (picture === sentPicture) {
                        picture = null
                        renderAttachment()
                    }
                    if (conversationId == null) conversationId = result.message.conversationId.value
                    load()
                }
                is SendResult.Refused -> if (!isDestroyed) refused(afterRefusal(result.reason), command.clientId, again)
                null -> if (!isDestroyed) refused(AfterRefusal.UNAVAILABLE, command.clientId, again)
            }
        }
    }

    /** A send the gate refused after all (the rules moved since the bar was drawn): its own words (code QA L5), and the
     * bar again. A draft id another text took (an edit between two taps) is replaced and the text sent once more. */
    private fun refused(after: AfterRefusal, sentId: String, again: Boolean) {
        if (after == AfterRefusal.SEND_AGAIN) {
            if (sentId == clientId) clientId = UUID.randomUUID().toString()
            if (!again) send(again = true)
            return
        }
        MessagesScreens.refusalLine(after)?.let { Toast.makeText(this, it, Toast.LENGTH_LONG).show() }
        renderCompose()
    }

    /** "Prøv igjen" ([MessageActions.retry]): the same text under the failed message's retry id; the failed one goes once
     * it is accepted. The link answers once until the next read (code QA M1). */
    @android.annotation.SuppressLint("NotifyDataSetChanged")
    private fun retry(failed: Message) {
        if (!retrying.add(failed.id.value)) return
        adapter.notifyDataSetChanged()
        val app = applicationContext
        SmsRuntime.ask(this, { engine ->
            MessageActions.retry({ SmsRuntime.send(app, it) }, { engine.delete(it, Origin.KID_SCREEN) }, failed)
        }) { result ->
            if (isDestroyed) return@ask
            if (result is SendResult.Refused) refused(afterRefusal(result.reason), "", again = true)
            load()
        }
    }

    private fun draftKey(): String = draftKeyOf(conversationId, number)

    /** The unsent text and its id stay in this process's memory while the kid looks elsewhere - never in the saved
     * instance state, which the system may write to disk (the field has `saveEnabled="false"`, code QA N4). */
    private fun keepDraft() {
        val text = binding.threadField.text?.toString().orEmpty()
        if (text.isBlank()) drafts.remove(draftKey()) else drafts[draftKey()] = text to clientId
    }

    // ---- D18: delete a message ----

    private fun askDelete(message: Message) {
        hideChoice()
        closeReact()
        selected = message.id.value
        updateBack()
        adapter.notifyDataSetChanged()
        renderCompose()
        MessagesScreens.showConfirm(binding.root.findViewById(R.id.messages_confirm), getString(R.string.messages_delete_message_title), ::cancelDelete) {
            val id = message.id
            cancelDelete()
            SmsRuntime.ask(this, { engine -> MessageActions.delete(engine, DeleteTarget.OneMessage(id)) }) { load() }
        }
    }

    @android.annotation.SuppressLint("NotifyDataSetChanged")
    private fun cancelDelete() {
        if (!::binding.isInitialized) return
        val was = selected
        selected = null
        updateBack()
        MessagesScreens.hideConfirm(binding.root.findViewById(R.id.messages_confirm))
        if (was != null) adapter.notifyDataSetChanged()
        renderCompose()
    }

    // ---- Design 81: reactions ----

    /** D6: whether the phone's font draws [emoji] - never an empty box; main thread. */
    private fun drawable(emoji: String): Boolean = drawable(emoji, glyphPaint)

    /** A reactor as TalkBack names them: "deg" for the kid, else the contact's name now, else the number. */
    private fun reactorName(address: String?): String =
        address?.let { MessagesScreens.name(this, whoOfAddress(it, MessagesScreens.rules())) } ?: getString(R.string.messages_reaction_you)

    private fun reactView(): View = binding.root.findViewById(R.id.messages_react)

    private fun pickerView(): View = binding.root.findViewById(R.id.messages_picker)

    /** D7: a long press opens the reaction panel - or, with nothing but "Slett" to offer, the delete confirm as before. */
    private fun longPress(m: Message) {
        if (reactPanel(m, compose) == null) askDelete(m) else showReact(m)
    }

    /** D7: the panel in the compose bar's place: the grid (the kid's reaction now in the peach ring), "Fjern
     * reaksjonene" when others reacted, "Slett". */
    @android.annotation.SuppressLint("NotifyDataSetChanged")
    private fun showReact(m: Message) {
        val panel = reactPanel(m, compose)
        if (panel == null) {
            hideReact()
            return
        }
        hideChoice()
        cancelDelete()
        reacting = m
        val view = reactView()
        val rows = listOf(view.findViewById<LinearLayout>(R.id.messages_react_row1), view.findViewById(R.id.messages_react_row2))
        rows.forEach { it.removeAllViews() }
        view.findViewById<View>(R.id.messages_react_grid).visibility = if (panel.cells == null) View.GONE else View.VISIBLE
        panel.cells.orEmpty().forEachIndexed { i, cell -> rows[i / 4].addView(cellView(m, cell)) }
        view.findViewById<View>(R.id.messages_react_remove).apply {
            visibility = if (panel.removeOthers) View.VISIBLE else View.GONE
            setOnClickListener { removeOthers(m) }
        }
        view.findViewById<View>(R.id.messages_react_delete).setOnClickListener { askDelete(m) }
        val wasShown = view.visibility == View.VISIBLE
        view.visibility = View.VISIBLE
        updateBack()
        adapter.notifyDataSetChanged()
        renderCompose()
        // TalkBack's focus moves into the panel when it opens (code QA N2 of step 2, as the confirm's).
        if (!wasShown) view.post { (rows[0].getChildAt(0) ?: view).performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null) }
    }

    /** A 56 dp cell of the grid: an emoji ("Reager med ❤️", or "Fjern din ❤️" for the kid's own now), "+" or empty. */
    private fun cellView(m: Message, cell: ReactCell): View = TextView(this).apply {
        layoutParams = LinearLayout.LayoutParams(KidAvatars.dp(context, 56f), KidAvatars.dp(context, 56f))
        gravity = Gravity.CENTER
        setTextColor(context.getColor(R.color.kid_core))
        when (cell) {
            is ReactCell.Pick -> {
                text = if (drawable(cell.emoji)) cell.emoji else MISSING_GLYPH
                textSize = 26f
                contentDescription = cellLabel(cell)
                setBackgroundResource(if (cell.current) R.drawable.bg_messages_react_current else R.drawable.bg_messages_react_cell)
                setOnClickListener { react(m, cell) }
            }
            ReactCell.More -> {
                text = "+"
                textSize = 26f
                typeface = androidx.core.content.res.ResourcesCompat.getFont(context, R.font.nunito_bold)
                contentDescription = getString(R.string.messages_more_emoji)
                setBackgroundResource(R.drawable.bg_messages_react_cell)
                setOnClickListener { showPicker() }
            }
            ReactCell.Empty -> importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }
    }

    private fun cellLabel(cell: ReactCell.Pick): String =
        getString(if (cell.current) R.string.messages_react_remove else R.string.messages_react_with, if (drawable(cell.emoji)) cell.emoji else getString(R.string.messages_reaction_unknown))

    /** The panel and the picker go, without redrawing the compose bar (for [renderCompose] itself). */
    @android.annotation.SuppressLint("NotifyDataSetChanged")
    private fun closeReact() {
        if (!::binding.isInitialized || reacting == null) return
        reacting = null
        picking = false
        reactView().visibility = View.GONE
        pickerView().visibility = View.GONE
        binding.threadList.visibility = View.VISIBLE
        updateBack()
        adapter.notifyDataSetChanged()
    }

    private fun hideReact() {
        if (reacting == null) return
        closeReact()
        renderCompose()
    }

    /** A cell's tap (D7): the kid's reaction now goes, any other replaces it (D4). */
    private fun react(m: Message, cell: ReactCell.Pick) = react(m, cell.emoji, if (cell.current) ReactionOp.REMOVE else ReactionOp.ADD)

    /**
     * D8: the reaction through the engine's send - the gate inside it, the body the engine's own, in the launcher's
     * language. The panel closes first; a refusal (a time rule began meanwhile, say) stores nothing and says why, as a
     * send's (L5). The badge shows from the engine's event, pending until sent.
     */
    private fun react(m: Message, emoji: String, op: ReactionOp, fromPicker: Boolean = false) {
        hideReact()
        val app = applicationContext
        val command = SendCommand(
            clientId = UUID.randomUUID().toString(),
            conversationId = m.conversationId,
            to = null,
            body = "",
            origin = Origin.KID_SCREEN,
            reaction = ReactionCommand(m.id, emoji, op, getString(R.string.messages_reaction_language)),
        )
        SmsRuntime.ask(this, { SmsRuntime.send(app, command) }) { result ->
            // D13 (code QA N6): "Nylig brukt" learns only an emoji that went - an add the engine took.
            if (fromPicker) {
                val recent = ReactionRecents.read(app)
                val next = recentAfterSend(recent, emoji, op, accepted = result is SendResult.Accepted)
                if (next != recent) ReactionRecents.write(app, next)
            }
            if (isDestroyed) return@ask
            val refusal = when (result) {
                is SendResult.Accepted -> null
                is SendResult.Refused -> MessagesScreens.refusalLine(afterRefusal(result.reason))
                null -> MessagesScreens.refusalLine(AfterRefusal.UNAVAILABLE)
            }
            refusal?.let { Toast.makeText(this, it, Toast.LENGTH_LONG).show() }
            renderCompose()
            load()
        }
    }

    /** D5: "Fjern reaksjonene" - everyone else's reactions on [m] go here; the senders aren't told. */
    private fun removeOthers(m: Message) {
        hideReact()
        SmsRuntime.ask(this, { engine -> engine.removeReactions(m.id, Origin.KID_SCREEN) }) { load() }
    }

    /** D13: every emoji, in the thread's place - read from the pinned list and filtered by the font off the main thread. */
    private fun showPicker() {
        if (reacting == null || picking) return
        picking = true
        reactView().visibility = View.GONE
        binding.threadList.visibility = View.GONE
        pickerView().visibility = View.VISIBLE
        updateBack()
        val app = applicationContext
        val title = pickerView().findViewById<View>(R.id.messages_picker_title)
        title.post { title.performAccessibilityAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS, null) }
        lifecycleScope.launch {
            val items = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                val list = SmsRuntime.emoji(app)
                val paint = android.graphics.Paint()
                val recent = ReactionRecents.read(app).filter { list.canonical(it) == it }
                pickerItems(list.groups, recent) { drawable(it, paint) }
            }
            if (isDestroyed || !picking) return@launch
            // Every visit opens at the top, where "Nylig brukt" is - not where the last one left the grid (emulator pass).
            val grid = pickerView().findViewById<RecyclerView>(R.id.messages_picker_grid)
            pickerAdapter.submitList(items) { grid.scrollToPosition(0) }
            bindGroups(items)
        }
    }

    /** The strip of groups: each group's icon, named for TalkBack; a tap brings its heading to the top. */
    private fun bindGroups(items: List<PickerItem>) {
        val strip = pickerView().findViewById<LinearLayout>(R.id.messages_picker_groups)
        val grid = pickerView().findViewById<RecyclerView>(R.id.messages_picker_grid)
        strip.removeAllViews()
        items.forEachIndexed { position, item ->
            val group = (item as? PickerItem.Heading)?.group?.let(EMOJI_GROUPS::get) ?: return@forEachIndexed
            strip.addView(
                TextView(this).apply {
                    layoutParams = LinearLayout.LayoutParams(KidAvatars.dp(context, 48f), KidAvatars.dp(context, 48f))
                    gravity = Gravity.CENTER
                    text = group.second
                    textSize = 22f
                    contentDescription = getString(group.first)
                    setBackgroundResource(R.drawable.bg_messages_react_cell)
                    setOnClickListener { (grid.layoutManager as GridLayoutManager).scrollToPositionWithOffset(position, 0) }
                },
            )
        }
    }

    /** D13: an emoji chosen in the picker is sent at once (the kid's own one now is taken away, as in the grid); once
     * sent it goes into "Nylig brukt". */
    private fun pick(emoji: String) {
        val m = reacting ?: return
        val mine = m.reactions.firstOrNull { it.by == null }
        val op = if (mine?.key == com.kidslauncher.mdm.sms.engine.EmojiList.key(emoji)) ReactionOp.REMOVE else ReactionOp.ADD
        react(m, emoji, op, fromPicker = true)
    }

    /** The picker's grid: a heading (a TalkBack heading) per group, then its emoji at 28 sp in 48 dp cells - each read
     * by TalkBack as Android names it. */
    private inner class PickerAdapter : ListAdapter<PickerItem, RecyclerView.ViewHolder>(PICKER_DIFF) {
        override fun getItemViewType(position: Int) = if (getItem(position) is PickerItem.Heading) TYPE_HEADING else TYPE_EMOJI

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val context = parent.context
            val view = TextView(context).apply {
                if (viewType == TYPE_HEADING) {
                    layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    setPadding(KidAvatars.dp(context, 8f), KidAvatars.dp(context, 12f), KidAvatars.dp(context, 8f), KidAvatars.dp(context, 4f))
                    textSize = 14f
                    typeface = androidx.core.content.res.ResourcesCompat.getFont(context, R.font.nunito_extrabold)
                    setTextColor(context.getColor(R.color.kid_core_dim))
                    isAccessibilityHeading = true
                } else {
                    layoutParams = RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, KidAvatars.dp(context, 48f))
                    gravity = Gravity.CENTER
                    textSize = 28f
                    setTextColor(context.getColor(R.color.kid_core))
                    setBackgroundResource(R.drawable.bg_messages_react_cell)
                }
            }
            return object : RecyclerView.ViewHolder(view) {}
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val view = holder.itemView as TextView
            when (val item = getItem(position)) {
                is PickerItem.Heading -> view.text = item.group?.let { EMOJI_GROUPS[it]?.first?.let(::getString) ?: it }
                    ?: getString(R.string.messages_picker_recent)
                is PickerItem.Emoji -> {
                    view.text = item.emoji
                    view.contentDescription = item.emoji
                    view.setOnClickListener { pick(item.emoji) }
                }
            }
        }
    }

    // ---- the bubbles ----

    private inner class BubbleAdapter : ListAdapter<ThreadItem, RecyclerView.ViewHolder>(DIFF) {
        inner class Bubble(view: View) : RecyclerView.ViewHolder(view) {
            val frame: android.widget.FrameLayout = view.findViewById(R.id.bubble_frame)
            val chip: LinearLayout = view.findViewById(R.id.bubble_reactions)
            val bubble: LinearLayout = view.findViewById(R.id.bubble)
            val from: TextView = view.findViewById(R.id.bubble_from)
            val unknown: TextView = view.findViewById(R.id.bubble_unknown)
            val parts: LinearLayout = view.findViewById(R.id.bubble_parts)
            val text: TextView = view.findViewById(R.id.bubble_text)
            val time: TextView = view.findViewById(R.id.bubble_time)
            val delivery: TextView = view.findViewById(R.id.bubble_delivery)
            /** TalkBack's actions on the bubble ("Slett", and design 81's reactions), removed at the next bind. */
            val actions = mutableListOf<Int>()
        }

        inner class Day(view: View) : RecyclerView.ViewHolder(view)

        override fun getItemViewType(position: Int) = if (getItem(position) is ThreadItem.Day) TYPE_DAY else TYPE_BUBBLE

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return if (viewType == TYPE_DAY) {
                Day(inflater.inflate(R.layout.item_message_day, parent, false))
            } else {
                Bubble(inflater.inflate(R.layout.item_message_bubble, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            when (val item = getItem(position)) {
                is ThreadItem.Day -> (holder.itemView as TextView).text = MessagesScreens.dayText(holder.itemView.context, item.label)
                is ThreadItem.Bubble -> bindBubble(holder as Bubble, item)
            }
        }

        private fun bindBubble(holder: Bubble, item: ThreadItem.Bubble) {
            val context = holder.itemView.context
            val m = item.message
            val mine = m.direction == Direction.OUT
            (holder.frame.layoutParams as LinearLayout.LayoutParams).gravity = if (mine) Gravity.END else Gravity.START
            holder.bubble.setBackgroundResource(if (mine) R.drawable.bg_message_out else R.drawable.bg_message_in)
            // The user's colours (2026-11-09): dark ink on the peach bubble, white on the lilac-grey; each time in its dim.
            holder.text.setTextColor(context.getColor(if (mine) R.color.kid_message_out_ink else R.color.kid_ink))
            holder.time.setTextColor(context.getColor(if (mine) R.color.kid_message_out_dim else R.color.kid_message_in_dim))
            val ring = if (mine) R.drawable.bg_message_selected_out else R.drawable.bg_message_selected_in
            val marked = m.id.value == selected || m.id == reacting?.id
            holder.bubble.foreground = if (marked) ContextCompat.getDrawable(context, ring) else null
            val maxWidth = (bubbleRoom(context) * 0.8f).toInt()
            holder.text.maxWidth = maxWidth - KidAvatars.dp(context, 28f)
            holder.unknown.visibility = if (m.unknownSender) View.VISIBLE else View.GONE
            // D12: in a group each received message says who in it wrote it.
            val groupSender = m.sender?.takeIf { !mine && (conversation?.participants?.size ?: 0) > 1 }
                ?.let { MessagesScreens.name(context, whoOfAddress(it, MessagesScreens.rules())) }
            holder.from.visibility = if (groupSender != null) View.VISIBLE else View.GONE
            holder.from.text = groupSender
            val body = m.body.orEmpty()
            holder.text.visibility = if (body.isEmpty()) View.GONE else View.VISIBLE
            holder.text.text = body
            val time = ClockFormat.formatClock(context, m.at)
            holder.time.text = time
            val partWords = bindParts(holder, m)
            // Code QA L5: the tap is offered only where the kid could send now, and once until the next read.
            val retry = retryOffered(item.delivery, compose) && m.id.value !in retrying
            val delivery = item.delivery?.let { deliveryText(it, retry) }
            holder.delivery.visibility = if (delivery == null) View.GONE else View.VISIBLE
            holder.delivery.text = delivery
            val failed = item.delivery == DeliveryLine.NOT_SENT || item.delivery == DeliveryLine.MAYBE_NOT_SENT
            holder.delivery.setTextColor(context.getColor(if (failed) R.color.kid_missed_text else R.color.kid_core_dim))
            holder.delivery.isClickable = retry
            holder.delivery.isFocusable = retry
            holder.delivery.setOnClickListener(if (retry) View.OnClickListener { retry(m) } else null)
            val whoText = if (mine) getString(R.string.messages_you) else groupSender ?: MessagesScreens.name(context, who())
            bindChip(holder, m, mine)
            // Design 81 D6 (N2): the reactions are part of the bubble's description; the chip itself isn't read.
            val reacted = reactionWords(m.reactions, ::drawable, getString(R.string.messages_reaction_unknown), ::reactorName) { emoji, by ->
                getString(R.string.messages_reaction_from, emoji, by)
            }.takeIf { it.isNotEmpty() }?.let { getString(R.string.messages_reactions, it.joinToString(", ")) }
            holder.bubble.contentDescription = listOfNotNull(
                getString(R.string.messages_bubble_description, whoText, time),
                getString(R.string.messages_unknown).takeIf { m.unknownSender },
                body.takeIf { it.isNotEmpty() },
                partWords,
                reacted,
            ).joinToString(", ")
            holder.bubble.setOnLongClickListener {
                longPress(m)
                true
            }
            bindActions(holder, m)
        }

        /** D6: the chip on the bubble's bottom edge, at the corner facing the middle - up to three emoji, the kid's own
         * in the peach ring (dimmed until sent), then "+N"; never wider than its bubble (the bubble widens instead). */
        private fun bindChip(holder: Bubble, m: Message, mine: Boolean) {
            val context = holder.itemView.context
            val chip = reactionChip(m.reactions, ::drawable)
            holder.chip.removeAllViews()
            holder.chip.visibility = if (chip == null) View.GONE else View.VISIBLE
            val bubbleParams = holder.bubble.layoutParams as android.widget.FrameLayout.LayoutParams
            if (chip == null) {
                bubbleParams.bottomMargin = 0
                holder.bubble.minimumWidth = 0
                return
            }
            (holder.chip.layoutParams as android.widget.FrameLayout.LayoutParams).gravity = Gravity.BOTTOM or if (mine) Gravity.START else Gravity.END
            for (e in chip.emoji) {
                holder.chip.addView(
                    TextView(context).apply {
                        text = if (e.drawable) e.emoji else MISSING_GLYPH
                        textSize = 14f
                        setTextColor(context.getColor(R.color.kid_ink))
                        val pad = KidAvatars.dp(context, 3f)
                        setPadding(pad, 0, pad, 0)
                        if (e.mine) setBackgroundResource(R.drawable.bg_message_reaction_mine)
                        alpha = if (e.pending) 0.5f else 1f
                    },
                )
            }
            if (chip.more > 0) {
                holder.chip.addView(
                    TextView(context).apply {
                        text = getString(R.string.messages_reactions_more, chip.more)
                        textSize = 12f
                        typeface = androidx.core.content.res.ResourcesCompat.getFont(context, R.font.nunito_bold)
                        setTextColor(context.getColor(R.color.kid_message_in_dim))
                        setPadding(KidAvatars.dp(context, 3f), 0, KidAvatars.dp(context, 3f), 0)
                    },
                )
            }
            holder.chip.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
            holder.bubble.minimumWidth = holder.chip.measuredWidth + 2 * KidAvatars.dp(context, 10f)
            // Code QA L3: the chip gets its own room under the bubble, its measured height (so at any text size) less a
            // little overlap that stays inside the bubble's bottom padding - never over the time or the delivery line.
            bubbleParams.bottomMargin = (holder.chip.measuredHeight - KidAvatars.dp(context, CHIP_OVERLAP_DP)).coerceAtLeast(0)
        }

        /** TalkBack's actions (D7): one per emoji of the grid, "Flere emojier", "Fjern reaksjonene" - as the panel would
         * offer them - and "Slett". */
        private fun bindActions(holder: Bubble, m: Message) {
            holder.actions.forEach { ViewCompat.removeAccessibilityAction(holder.bubble, it) }
            holder.actions.clear()
            fun action(label: String, run: () -> Unit) {
                holder.actions += ViewCompat.addAccessibilityAction(holder.bubble, label) { _, _ ->
                    run()
                    true
                }
            }
            val panel = reactPanel(m, compose)
            for (cell in panel?.cells.orEmpty()) {
                when (cell) {
                    is ReactCell.Pick -> action(cellLabel(cell)) { react(m, cell) }
                    ReactCell.More -> action(getString(R.string.messages_more_emoji)) {
                        showReact(m)
                        showPicker()
                    }
                    ReactCell.Empty -> Unit
                }
            }
            if (panel?.removeOthers == true) action(getString(R.string.messages_remove_reactions)) { removeOthers(m) }
            action(getString(R.string.messages_delete)) { askDelete(m) }
        }

        /** The message's pictures and part lines (D11, step 3b's parts; an SMS has none); returns their words for
         * TalkBack. A picture is our own JPEG, decoded off the main thread; until its bytes open, its place shows. */
        private fun bindParts(holder: Bubble, m: Message): String? {
            val context = holder.itemView.context
            holder.parts.removeAllViews()
            val views = partViews(m.parts, retry = fetchRetryOffered(m) && m.id.value !in retrying, unreadable = fetchUnreadable(m))
            holder.parts.visibility = if (views.isEmpty()) View.GONE else View.VISIBLE
            val words = mutableListOf<String>()
            for (view in views) {
                when (view) {
                    is PartView.Picture -> {
                        words += getString(R.string.messages_picture)
                        holder.parts.addView(pictureView(context, m, view))
                    }
                    else -> {
                        val text = getString(partLine(view))
                        words += text
                        val retry = view is PartView.Failed && view.retry
                        holder.parts.addView(
                            TextView(context).apply {
                                this.text = text
                                setTextColor(context.getColor(if (retry) R.color.kid_missed_text else dimOf(m)))
                                textSize = 14f
                                typeface = androidx.core.content.res.ResourcesCompat.getFont(context, R.font.nunito_semibold)
                                // D14: after five tries the kid may fetch it again; the tap answers once until the next read.
                                if (retry) {
                                    minHeight = KidAvatars.dp(context, 32f)
                                    gravity = Gravity.CENTER_VERTICAL
                                    setOnClickListener { retryFetch(m) }
                                }
                            },
                        )
                    }
                }
            }
            return words.takeIf { it.isNotEmpty() }?.joinToString(", ")
        }

        private fun pictureView(context: Context, m: Message, part: PartView.Picture): View {
            val width = (bubbleRoom(context) * 0.7f).toInt()
            val ratio = pictureRatio(part.width, part.height)
            val image = ImageView(context).apply {
                layoutParams = LinearLayout.LayoutParams(width, (width * ratio).toInt()).apply {
                    bottomMargin = KidAvatars.dp(context, 6f)
                }
                setBackgroundResource(R.drawable.bg_message_picture)
                clipToOutline = true
                scaleType = ImageView.ScaleType.CENTER_CROP
                contentDescription = getString(R.string.messages_open_picture)
                setOnClickListener { startActivity(PictureActivity.intent(this@ThreadActivity, m.id.value, part.index)) }
            }
            val key = "${m.id.value}:${part.index}"
            val cached = SmsPictures.cached(key)
            if (cached != null) {
                image.setImageBitmap(cached)
            } else {
                // Its place until the bytes open (step 3b brings them): the picture glyph, dim.
                image.scaleType = ImageView.ScaleType.CENTER
                image.setImageResource(R.drawable.app_glyph_photo_library)
                image.imageTintList = android.content.res.ColorStateList.valueOf(context.getColor(dimOf(m)))
                SmsRuntime.ask(context, { engine ->
                    SmsPictures.decode({ engine.openPart(m.id, part.index) }, width)?.also { SmsPictures.keep(key, it) }
                }) { bitmap ->
                    if (bitmap == null || isDestroyed) return@ask
                    image.imageTintList = null
                    image.scaleType = ImageView.ScaleType.CENTER_CROP
                    image.setImageBitmap(bitmap)
                }
            }
            return image
        }
    }

    /** A bubble's second tone (its time, a part's line, a picture's place): legible on its own surface. */
    private fun dimOf(m: Message): Int = if (m.direction == Direction.OUT) R.color.kid_message_out_dim else R.color.kid_message_in_dim

    private fun bubbleRoom(context: Context): Int =
        binding.threadList.width.takeIf { it > 0 } ?: (context.resources.displayMetrics.widthPixels - KidAvatars.dp(context, 32f))

    private fun deliveryText(line: DeliveryLine, retry: Boolean): String = getString(
        when (line) {
            DeliveryLine.SENDING -> R.string.messages_sending
            DeliveryLine.SENT -> R.string.messages_sent
            DeliveryLine.DELIVERED -> R.string.messages_delivered
            DeliveryLine.NOT_SENT -> if (retry) R.string.messages_not_sent else R.string.messages_not_sent_plain
            DeliveryLine.MAYBE_NOT_SENT -> if (retry) R.string.messages_maybe_not_sent else R.string.messages_maybe_not_sent_plain
        },
    )

    private fun partLine(view: PartView): Int = when (view) {
        PartView.Waiting -> R.string.messages_picture_waiting
        PartView.WaitingData -> R.string.messages_picture_waiting_data
        PartView.NotShown -> R.string.messages_picture_not_shown
        is PartView.Failed -> if (view.retry) R.string.messages_picture_retry else R.string.messages_picture_failed
        PartView.Gone -> R.string.messages_picture_gone
        PartView.Unreadable -> R.string.messages_unreadable
        PartView.Unsupported -> R.string.messages_attachment_unsupported
        is PartView.Picture -> R.string.messages_picture
    }

    /** D14's "Trykk for å prøve igjen": the fetch starts again (the engine's `retryDownload`, then an MMS pass). */
    @android.annotation.SuppressLint("NotifyDataSetChanged")
    private fun retryFetch(m: Message) {
        if (!retrying.add(m.id.value)) return
        adapter.notifyDataSetChanged()
        val app = applicationContext
        SmsRuntime.ask(this, { engine -> engine.mms.retryDownload(m.id.value) }) { started ->
            if (started == true) com.kidslauncher.mdm.sms.mms.MmsRuntime.kick(app)
            load()
        }
    }

    companion object {
        const val EXTRA_CONVERSATION = "me.vibb.launcher.extra.SMS_CONVERSATION"
        const val EXTRA_NUMBER = "me.vibb.launcher.extra.SMS_NUMBER"
        const val EXTRA_DRAFT = "me.vibb.launcher.extra.SMS_DRAFT"

        /** Messages read at a time; scrolling to the top reads the next page. */
        private const val PAGE = 100

        /** How near its end the list counts as at it ([threadAtEnd]). */
        private const val END_SLOP_DP = 8f

        /** D13: the picker's columns (48 dp each at 288 dp), and its two kinds of line. */
        private const val PICKER_COLUMNS = 5
        private const val TYPE_HEADING = 0
        private const val TYPE_EMOJI = 1

        /** D6 (code QA L3): how far the chip reaches up into its bubble - less than the bubble's 8 dp bottom padding. */
        private const val CHIP_OVERLAP_DP = 5f

        /** D6, D13 (code QA N5): whether the font draws an emoji, kept for the process - the font doesn't change while
         * it runs, so the picker's ~1,900 checks run once, off the main thread. */
        private val GLYPHS = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

        private fun drawable(emoji: String, paint: android.graphics.Paint): Boolean = GLYPHS.getOrPut(emoji) { paint.hasGlyph(emoji) }
        private const val TYPE_DAY = 0
        private const val TYPE_BUBBLE = 1

        /** Unsent drafts by conversation (or number): text and `clientId`, in memory only. */
        private val drafts = HashMap<String, Pair<String, String>>()

        /** D11: the picture picked for a draft, by the same key; in memory only (the JPEG itself is the engine's). */
        private val pictureDrafts = java.util.concurrent.ConcurrentHashMap<String, PicturePick.Prepared>()

        /** Pictures are made ready here: never on the main thread, never on the engine's (a parse can take seconds). */
        private val preparer = java.util.concurrent.Executors.newSingleThreadExecutor { Thread(it, "sms-picture") }

        private const val STATE_CAPTURE = "capture"

        /** D13: the picker's groups by Unicode's name - their words and the strip's icon. */
        private val EMOJI_GROUPS = mapOf(
            "Smileys & Emotion" to (R.string.messages_emoji_smileys to "😀"),
            "People & Body" to (R.string.messages_emoji_people to "👋"),
            "Animals & Nature" to (R.string.messages_emoji_animals to "🐶"),
            "Food & Drink" to (R.string.messages_emoji_food to "🍎"),
            "Travel & Places" to (R.string.messages_emoji_travel to "✈️"),
            "Activities" to (R.string.messages_emoji_activities to "⚽"),
            "Objects" to (R.string.messages_emoji_objects to "💡"),
            "Symbols" to (R.string.messages_emoji_symbols to "❤️"),
            "Flags" to (R.string.messages_emoji_flags to "🏁"),
        )

        private val PICKER_DIFF = object : DiffUtil.ItemCallback<PickerItem>() {
            override fun areItemsTheSame(oldItem: PickerItem, newItem: PickerItem): Boolean = oldItem == newItem

            override fun areContentsTheSame(oldItem: PickerItem, newItem: PickerItem): Boolean = oldItem == newItem
        }

        private val DIFF = object : DiffUtil.ItemCallback<ThreadItem>() {
            override fun areItemsTheSame(oldItem: ThreadItem, newItem: ThreadItem): Boolean = when {
                oldItem is ThreadItem.Day && newItem is ThreadItem.Day -> oldItem.date == newItem.date
                oldItem is ThreadItem.Bubble && newItem is ThreadItem.Bubble -> oldItem.message.id == newItem.message.id
                else -> false
            }

            override fun areContentsTheSame(oldItem: ThreadItem, newItem: ThreadItem): Boolean = oldItem == newItem
        }
    }
}
