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
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.DiffUtil
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
import com.kidslauncher.mdm.sms.engine.SendCommand
import com.kidslauncher.mdm.sms.engine.SendResult
import com.kidslauncher.mdm.sms.engine.SmsEngine
import com.kidslauncher.mdm.ui.ClockFormat
import com.kidslauncher.mdm.ui.KidHeader
import com.kidslauncher.mdm.ui.NoTextActions
import com.kidslauncher.mdm.ui.UIObjectActivity
import com.kidslauncher.mdm.ui.day.LockWatch
import com.kidslauncher.mdm.ui.home.KidAvatars
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.time.ZoneId
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
    private var compose = ComposeState.UNAVAILABLE
    private var resumed = false

    /** The draft's idempotency key (§6.2): one per text, new only once the engine accepted it. */
    private var clientId = UUID.randomUUID().toString()

    /** The message whose delete confirm is up. */
    private var selected: String? = null

    /** Failed messages whose "Prøv igjen" was tapped, until the next read shows what became of them (code QA M1: the
     * link answers once; the retry's own id makes a second tap the engine's repeat anyway). */
    private val retrying = mutableSetOf<String>()

    /** One read at a time, one more for whatever came meanwhile (code QA L2). */
    private val reload = Coalescer { done -> loadNow(done) }
    private var events: Job? = null
    private val homeListener: () -> Unit = { renderCompose() }
    private val lockWatch = LockWatch(this) { if (!MessagesScreens.redirectIfLocked(it)) renderCompose() }

    private val back = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() = cancelDelete()
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
                // Older messages when the top is reached and the last page was full.
                if (dy < 0 && layout.findFirstVisibleItemPosition() == 0 && messages.size >= limit) {
                    limit += PAGE
                    load()
                }
            }
        })
        onBackPressedDispatcher.addCallback(this, back)
        NoTextActions.apply(binding.threadField)
        binding.threadField.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
            override fun afterTextChanged(s: Editable?) = renderCounter()
        })
        binding.threadSend.setOnClickListener { send() }
        take(intent)
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
        limit = PAGE
        cancelDelete()
        val kept = drafts[draftKey()]
        val draft = intent.getStringExtra(EXTRA_DRAFT)?.take(SmsEngine.SEND_MAX)
        clientId = kept?.second ?: UUID.randomUUID().toString()
        binding.threadField.setText(draft ?: kept?.first.orEmpty())
        // Read once: a draft is never sent again by a recreation.
        intent.removeExtra(EXTRA_DRAFT)
        renderHeader()
        renderCompose()
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
     * from the engine thread; read up to the newest while the thread is in front. */
    private fun loadNow(done: () -> Unit) {
        val id = conversationId
        val wanted = number?.let { normalised(it) }
        val count = limit
        val markRead = resumed
        SmsRuntime.ask(this, { engine ->
            val found = id?.let { engine.conversation(ConversationId(it)) } ?: wanted?.let { engine.conversationWith(it) }
            val list = found?.let { engine.messages(it.id, null, count) }.orEmpty()
            if (markRead && found != null && found.unread > 0) engine.markRead(found.id, found.lastSeq, Origin.KID_SCREEN)
            Loaded(found, list)
        }) { loaded ->
            done()
            if (isDestroyed || loaded == null) return@ask
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
                conversationId = found.id.value
                if (resumed) SmsRuntime.threadInFront = conversationId
            }
            found.participants.singleOrNull()?.address
                ?.takeUnless { it.startsWith(SmsEngine.NAME_PREFIX) || it.startsWith(SmsEngine.UNKNOWN_PREFIX) }
                ?.let { number = it }
            if (markedRead) SmsNotifier.cancel(applicationContext, found.id.value)
        } else {
            // Its last message deleted: the thread stays as a new one with the number.
            conversationId = null
        }
        conversation = found
        messages = loaded.messages
        retrying.clear()
        if (selected != null && messages.none { it.id.value == selected }) cancelDelete()
        renderHeader()
        renderCompose()
        val newest = messages.maxOfOrNull { it.seq } ?: 0L
        val scroll = newest > newestSeq
        newestSeq = newest
        adapter.submitList(threadItems(messages, System.currentTimeMillis(), ZoneId.systemDefault())) {
            if (scroll && adapter.itemCount > 0) binding.threadList.scrollToPosition(adapter.itemCount - 1)
        }
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
        val target = conversation?.participants?.let { if (it.size == 1) it.single().address else null } ?: number?.let(::normalised)
        val before = compose
        compose = composeState(SmsRuntime.judge(this), target)
        if (before != compose && adapter.itemCount > 0) adapter.notifyDataSetChanged()
        val confirming = selected != null
        val line = MessagesScreens.composeLine(compose)
        binding.threadCompose.visibility = if (!confirming && line == null) View.VISIBLE else View.GONE
        binding.threadLine.visibility = if (!confirming && line != null) View.VISIBLE else View.GONE
        if (line != null) binding.threadLine.setText(line)
        renderCounter()
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
        if (text.isEmpty() || compose != ComposeState.OPEN) return
        val app = applicationContext
        val key = draftKey()
        val command = SendCommand(
            clientId = clientId,
            conversationId = conversationId?.let(::ConversationId),
            to = if (conversationId == null) number else null,
            body = text,
            origin = Origin.KID_SCREEN,
        )
        SmsRuntime.ask(this, { SmsRuntime.send(app, command) }) { result ->
            when (result) {
                is SendResult.Accepted -> {
                    drafts.remove(key)
                    if (isDestroyed) return@ask
                    if (command.clientId == clientId) clientId = UUID.randomUUID().toString()
                    if (binding.threadField.text?.toString()?.trim() == text) binding.threadField.text = null
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

    private fun draftKey(): String = conversationId ?: "number:${number.orEmpty()}"

    /** The unsent text and its id stay in this process's memory while the kid looks elsewhere - never in the saved
     * instance state, which the system may write to disk (the field has `saveEnabled="false"`, code QA N4). */
    private fun keepDraft() {
        val text = binding.threadField.text?.toString().orEmpty()
        if (text.isBlank()) drafts.remove(draftKey()) else drafts[draftKey()] = text to clientId
    }

    // ---- D18: delete a message ----

    private fun askDelete(message: Message) {
        selected = message.id.value
        back.isEnabled = true
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
        back.isEnabled = false
        MessagesScreens.hideConfirm(binding.root.findViewById(R.id.messages_confirm))
        if (was != null) adapter.notifyDataSetChanged()
        renderCompose()
    }

    // ---- the bubbles ----

    private inner class BubbleAdapter : ListAdapter<ThreadItem, RecyclerView.ViewHolder>(DIFF) {
        inner class Bubble(view: View) : RecyclerView.ViewHolder(view) {
            val bubble: LinearLayout = view.findViewById(R.id.bubble)
            val unknown: TextView = view.findViewById(R.id.bubble_unknown)
            val parts: LinearLayout = view.findViewById(R.id.bubble_parts)
            val text: TextView = view.findViewById(R.id.bubble_text)
            val time: TextView = view.findViewById(R.id.bubble_time)
            val delivery: TextView = view.findViewById(R.id.bubble_delivery)
            var deleteAction = View.NO_ID
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
            (holder.bubble.layoutParams as LinearLayout.LayoutParams).gravity = if (mine) Gravity.END else Gravity.START
            holder.bubble.setBackgroundResource(if (mine) R.drawable.bg_message_out else R.drawable.bg_message_in)
            // The user's colours (2026-11-09): dark ink on the peach bubble, white on the lilac-grey; each time in its dim.
            holder.text.setTextColor(context.getColor(if (mine) R.color.kid_message_out_ink else R.color.kid_ink))
            holder.time.setTextColor(context.getColor(if (mine) R.color.kid_message_out_dim else R.color.kid_message_in_dim))
            val ring = if (mine) R.drawable.bg_message_selected_out else R.drawable.bg_message_selected_in
            holder.bubble.foreground = if (m.id.value == selected) ContextCompat.getDrawable(context, ring) else null
            val maxWidth = (bubbleRoom(context) * 0.8f).toInt()
            holder.text.maxWidth = maxWidth - KidAvatars.dp(context, 28f)
            holder.unknown.visibility = if (m.unknownSender) View.VISIBLE else View.GONE
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
            val whoText = if (mine) getString(R.string.messages_you) else MessagesScreens.name(context, who())
            holder.bubble.contentDescription = listOfNotNull(
                getString(R.string.messages_bubble_description, whoText, time),
                getString(R.string.messages_unknown).takeIf { m.unknownSender },
                body.takeIf { it.isNotEmpty() },
                partWords,
            ).joinToString(", ")
            holder.bubble.setOnLongClickListener {
                askDelete(m)
                true
            }
            if (holder.deleteAction != View.NO_ID) ViewCompat.removeAccessibilityAction(holder.bubble, holder.deleteAction)
            holder.deleteAction = ViewCompat.addAccessibilityAction(holder.bubble, getString(R.string.messages_delete)) { _, _ ->
                askDelete(m)
                true
            }
        }

        /** The message's pictures and part lines (D11, step 3b's parts; an SMS has none); returns their words for
         * TalkBack. A picture is our own JPEG, decoded off the main thread; until its bytes open, its place shows. */
        private fun bindParts(holder: Bubble, m: Message): String? {
            val context = holder.itemView.context
            holder.parts.removeAllViews()
            val views = partViews(m.parts)
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
                        holder.parts.addView(
                            TextView(context).apply {
                                this.text = text
                                setTextColor(context.getColor(dimOf(m)))
                                textSize = 14f
                                typeface = androidx.core.content.res.ResourcesCompat.getFont(context, R.font.nunito_semibold)
                            },
                        )
                    }
                }
            }
            return words.takeIf { it.isNotEmpty() }?.joinToString(", ")
        }

        private fun pictureView(context: Context, m: Message, part: PartView.Picture): View {
            val width = (bubbleRoom(context) * 0.7f).toInt()
            val ratio = if (part.width != null && part.height != null && part.width > 0) part.height.toFloat() / part.width else 0.75f
            val image = ImageView(context).apply {
                layoutParams = LinearLayout.LayoutParams(width, (width * ratio.coerceIn(0.4f, 1.6f)).toInt()).apply {
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
        PartView.NotShown -> R.string.messages_picture_not_shown
        PartView.Failed -> R.string.messages_picture_failed
        PartView.Unsupported -> R.string.messages_attachment_unsupported
        is PartView.Picture -> R.string.messages_picture
    }

    companion object {
        const val EXTRA_CONVERSATION = "me.vibb.launcher.extra.SMS_CONVERSATION"
        const val EXTRA_NUMBER = "me.vibb.launcher.extra.SMS_NUMBER"
        const val EXTRA_DRAFT = "me.vibb.launcher.extra.SMS_DRAFT"

        /** Messages read at a time; scrolling to the top reads the next page. */
        private const val PAGE = 100
        private const val TYPE_DAY = 0
        private const val TYPE_BUBBLE = 1

        /** Unsent drafts by conversation (or number): text and `clientId`, in memory only. */
        private val drafts = HashMap<String, Pair<String, String>>()

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
