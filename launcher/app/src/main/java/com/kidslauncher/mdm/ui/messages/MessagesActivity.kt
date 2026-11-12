package com.kidslauncher.mdm.ui.messages

import android.app.ActivityManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.kidslauncher.mdm.R
import com.kidslauncher.mdm.calls.CallPolicyStore
import com.kidslauncher.mdm.calls.RuleContact
import com.kidslauncher.mdm.databinding.ActivityMessagesBinding
import com.kidslauncher.mdm.sms.SmsHome
import com.kidslauncher.mdm.sms.SmsNotifier
import com.kidslauncher.mdm.sms.SmsRuntime
import com.kidslauncher.mdm.sms.engine.Message
import com.kidslauncher.mdm.ui.KidHeader
import com.kidslauncher.mdm.ui.KidInsets
import com.kidslauncher.mdm.ui.day.LockWatch
import com.kidslauncher.mdm.calls.SEARCH_MIN_ROWS
import com.kidslauncher.mdm.ui.look.KidPageActivity
import com.kidslauncher.mdm.ui.search.KidSearchField
import com.kidslauncher.mdm.ui.search.MessagesBack
import com.kidslauncher.mdm.ui.search.SearchMatch
import com.kidslauncher.mdm.ui.search.highlightMatch
import com.kidslauncher.mdm.ui.search.listCollator
import com.kidslauncher.mdm.ui.search.messagesBack
import com.kidslauncher.mdm.ui.search.searchHits
import com.kidslauncher.mdm.ui.search.searchShown
import com.kidslauncher.mdm.ui.search.searching
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.time.ZoneId

/**
 * Meldinger (design 77 §6.7): one row per conversation with messages, newest first - the avatar, the name, the last
 * line ("Bilde" for a picture), the time and an unread dot. "Ny melding" lists only the contacts with "Kan få melding
 * fra" (D3) while the kid may text; a long-press (or TalkBack's action) asks "Slette samtalen med …?" in the screen
 * itself (D18). In "Av" and "Ikke styrt" the history is read-only: no "Ny melding", and the threads have no compose bar.
 *
 * A task of its own, as Dagen's (`MessagesScreens.listIntent`). Under a time rule it is reachable only where the rule
 * allows calls (as the phone book); a rule that doesn't, beginning while it is in front, brings the time-rule screen.
 * Everything it reads comes from the engine thread ([SmsRuntime.ask]); the engine's events and every rule change
 * ([SmsHome]) read it again.
 */
open class MessagesActivity : KidPageActivity() {

    private lateinit var binding: ActivityMessagesBinding
    private val adapter = RowsAdapter()

    /** "Ny melding"'s contact list is up instead of the conversations. */
    private var picking = false
    private var rows: List<ListRow> = emptyList()
    private var deleting: ListRow? = null
    private var events: Job? = null
    private val homeListener: () -> Unit = { load() }
    /** A rule beginning (or its key moving) while the list is in front: the time-rule screen if it doesn't allow calls,
     * else "Ny melding" follows the gate. */
    private val lockWatch = LockWatch(this) { if (!MessagesScreens.redirectIfLocked(it)) render() }

    /** Design 79 §4: the search pill from 8 rows, the shown names only. */
    private lateinit var search: KidSearchField

    /** Back: the keyboard first (stock Android, code QA L10), then the delete confirm, then the search's text, then
     * "Ny melding" back to the list ([messagesBack]). */
    private val back = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            val ime = binding.root.rootWindowInsets?.isVisible(android.view.WindowInsets.Type.ime()) == true
            when (messagesBack(ime, deleting != null, search.query, picking)) {
                MessagesBack.HIDE_KEYBOARD -> search.hideKeyboard()
                MessagesBack.CANCEL_DELETE -> cancelDelete()
                MessagesBack.CLEAR_SEARCH -> {
                    search.clear()
                    filter()
                }
                MessagesBack.STOP_PICKING -> {
                    picking = false
                    render()
                }
                MessagesBack.LEAVE -> {}
            }
        }
    }

    private fun updateBack() {
        back.isEnabled = messagesBack(false, deleting != null, search.query, picking) != MessagesBack.LEAVE
    }

    /** "Ny melding"'s contacts as the last [render] read them (a keystroke only filters, code QA M2). */
    private var pickContacts: List<RuleContact> = emptyList()

    /** Whether each shown number is an emergency number, asked once per render, not per bound row (code QA M2). */
    private val emergencyMemo = HashMap<String, Boolean>()
    private val isEmergency: (String) -> Boolean = { number ->
        emergencyMemo.getOrPut(number) { com.kidslauncher.mdm.calls.CallSystem.isEmergencyOutgoing(this, number) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (isFinishing) return // The other look's class: its task is closing (KidPageActivity).
        labelTask()
        binding = ActivityMessagesBinding.inflate(layoutInflater)
        setContentView(binding.root)
        KidInsets.apply(binding.root)
        binding.messagesList.layoutManager = LinearLayoutManager(this)
        binding.messagesList.adapter = adapter
        onBackPressedDispatcher.addCallback(this, back)
        search = KidSearchField(binding.messagesSearch.root, R.string.messages_search) { filter() }
        picking = savedInstanceState?.getBoolean(STATE_PICKING) == true
        render()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(STATE_PICKING, picking)
    }

    override fun onStart() {
        super.onStart()
        SmsHome.addListener(homeListener)
        events = lifecycleScope.launch { SmsRuntime.events(this@MessagesActivity).collect { load() } }
    }

    override fun onResume() {
        super.onResume()
        if (MessagesScreens.redirectIfLocked(this)) return
        lockWatch.start()
        load()
    }

    override fun onPause() {
        lockWatch.stop()
        super.onPause()
    }

    override fun onStop() {
        SmsHome.removeListener(homeListener)
        events?.cancel()
        // Design 79 §4.1 (code QA N3): the query is kept across a rotation only.
        if (!isChangingConfigurations && ::search.isInitialized) search.clear()
        super.onStop()
    }

    /** One read at a time, one more for whatever came meanwhile (code QA L2). */
    private val reload = Coalescer { done -> loadNow(done) }

    private fun load() = reload.request()

    /** The conversations with their newest message, from the engine thread. */
    private fun loadNow(done: () -> Unit) {
        SmsRuntime.ask(this, { engine ->
            engine.conversations().map { it to engine.messages(it.id, null, 1).firstOrNull() }
        }) { entries ->
            done()
            if (isDestroyed) return@ask
            rows = listRows(entries.orEmpty(), MessagesScreens.rules())
            dropKeptTexts(entries.orEmpty().associate { (c, _) -> c.id.value to c.participants.map { it.address } })
            if (deleting != null && rows.none { it.conversation.id == deleting?.conversation?.id }) cancelDelete()
            render()
        }
    }

    /** Design 78 §5.4.4: a kept text goes once the kid may no longer text there ([keptTextsToDrop]) - also for a
     * thread not opened again, a new thread's for a number, and a conversation gone. All of them go with SMS off or the
     * phone unmanaged ([ThreadDrafts.all]). [conversations]: every conversation's id and recipients. */
    private fun dropKeptTexts(conversations: Map<String, List<String>>) {
        val kept = ThreadDrafts.all(this)
        if (kept.isEmpty()) return
        val judge = SmsRuntime.judge(this)
        for (key in keptTextsToDrop(kept.keys, conversations) { keptTextDropped(composeState(judge, it)) }) ThreadDrafts.drop(this, key)
    }

    /** Whether the kid may start a conversation now: "Styrt av Vibb", the role ours, the gate not shut by a time rule
     * ([mayStartNew]: judged on a contact that isn't an emergency number - code QA N3). */
    private fun mayStart(): Boolean = mayStartNew(SmsRuntime.judge(this), MessagesScreens.rules())

    /** The task's label as it was last set ([labelTask]). */
    private var taskLabel: String? = null

    /** The task's label (Recents) is Meldinger's name (design 82 W1) - set again at every render, so a name the parent
     * changes while the screen is open reaches it as it reaches the header (code QA L4); only when it changed. */
    private fun labelTask() {
        val name = com.kidslauncher.mdm.appdisplay.AppDisplay.messagesName(this)
        if (name == taskLabel) return
        taskLabel = name
        setTaskDescription(
            ActivityManager.TaskDescription.Builder()
                .setLabel(name)
                .setIcon(R.mipmap.ic_launcher)
                .build(),
        )
    }

    private fun render() {
        labelTask()
        val readOnly = historyReadOnlyNow()
        val canStart = !readOnly && mayStart()
        if (picking && !canStart) setPicking(false)
        emergencyMemo.clear()
        if (picking) {
            KidHeader.bind(this, binding.messagesHeader, R.string.messages_new, back = R.string.kid_back)
            pickContacts = newMessageContacts(MessagesScreens.rules())
            info(if (pickContacts.isEmpty()) getString(R.string.messages_new_empty) else null)
            filter()
            return
        }
        KidHeader.bind(this, binding.messagesHeader, R.string.messages_title, action = R.string.messages_new.takeIf { canStart }, back = R.string.kid_back) {
            setPicking(true)
        }
        // Design 82 W1: under the parent's name for Meldinger, where there is one.
        binding.messagesHeader.kidHeaderTitle.text = com.kidslauncher.mdm.appdisplay.AppDisplay.messagesName(this)
        info(
            when {
                readOnly -> getString(R.string.messages_read_only)
                rows.isEmpty() -> getString(R.string.messages_empty)
                else -> null
            },
        )
        filter()
    }

    /** The rows for the search's text, from what the last [render] read: the search's keystroke runs only this. */
    private fun filter() {
        val collator = listCollator(resources.configuration.locales[0])
        if (picking) {
            val contacts = pickContacts
            val shown = searchShown(contacts.size, search.query, allowed = true, minRows = SEARCH_MIN_ROWS)
            val hits = if (shown && searching(search.query)) searchHits(contacts, { it.name }, search.query, collator) else null
            showList(shown, hits?.map { (c, m) -> Row.Contact(c, m) } ?: contacts.map { Row.Contact(it) }, hits != null)
            return
        }
        val shown = searchShown(rows.size, search.query, allowed = true, minRows = SEARCH_MIN_ROWS)
        val hits = if (shown && searching(search.query)) {
            searchConversations(rows, search.query, collator) { MessagesScreens.name(this, it) }
        } else {
            null
        }
        showList(shown, hits?.map { (r, m) -> Row.Conversation(r, m) } ?: rows.map { Row.Conversation(it) }, hits != null)
    }

    /** The list, the pill, the live count and "Fant ingenting" (design 79 §4.1; no request button here). */
    private fun showList(searchShown: Boolean, items: List<Row>, searchingNow: Boolean) {
        search.setShown(searchShown)
        val nothing = searchingNow && items.isEmpty()
        search.announce(if (searchingNow && !nothing) items.size else null)
        binding.messagesEmpty.root.visibility = if (nothing) View.VISIBLE else View.GONE
        binding.messagesList.visibility = if (nothing) View.GONE else View.VISIBLE
        adapter.submit(items)
        updateBack()
    }

    /** "Ny melding" and the list each start without a search. */
    private fun setPicking(on: Boolean) {
        if (picking == on) return
        picking = on
        search.clear()
        render()
    }

    /** SMS isn't "Styrt av Vibb" now: the history is only for reading ([ComposeState.READ_ONLY]). */
    private fun historyReadOnlyNow(): Boolean = composeState(SmsRuntime.judge(this), null) == ComposeState.READ_ONLY

    private fun info(text: String?) {
        binding.messagesInfo.visibility = if (text == null) View.GONE else View.VISIBLE
        binding.messagesInfo.text = text
    }

    private fun open(row: ListRow) {
        startActivity(MessagesScreens.threadIntent(this, row.conversation.id.value, null))
    }

    /** "Ny melding" for [contact]: their conversation if there is one, else a new thread for the number. */
    private fun startWith(contact: RuleContact) {
        setPicking(false)
        val existing = rows.firstOrNull { it.conversation.participants.singleOrNull()?.address == contact.number }
        startActivity(MessagesScreens.threadIntent(this, existing?.conversation?.id?.value, contact.number))
    }

    // ---- D18: delete a conversation ----

    private fun askDelete(row: ListRow) {
        deleting = row
        updateBack()
        val panel = binding.root.findViewById<View>(R.id.messages_confirm)
        MessagesScreens.showConfirm(
            panel,
            getString(R.string.messages_delete_conversation_title, MessagesScreens.name(this, row.who)),
            onCancel = ::cancelDelete,
        ) {
            val id = row.conversation.id
            cancelDelete()
            // Its unsent text goes with it (design 78 §5.4.4).
            ThreadDrafts.drop(this, id.value)
            SmsRuntime.ask(this, { engine -> MessageActions.delete(engine, DeleteTarget.WholeConversation(id)) }) {
                SmsNotifier.cancel(applicationContext, id.value)
                load()
            }
        }
    }

    private fun cancelDelete() {
        deleting = null
        MessagesScreens.hideConfirm(binding.root.findViewById(R.id.messages_confirm))
        updateBack()
    }

    // ---- the list ----

    private sealed interface Row {
        data class Conversation(val row: ListRow, val match: SearchMatch? = null) : Row

        data class Contact(val contact: RuleContact, val match: SearchMatch? = null) : Row
    }

    private inner class RowsAdapter : RecyclerView.Adapter<RowsAdapter.Holder>() {
        private var items: List<Row> = emptyList()

        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val photo: ImageView = view.findViewById(R.id.messages_row_photo)
            val initial: TextView = view.findViewById(R.id.messages_row_initial)
            val name: TextView = view.findViewById(R.id.messages_row_name)
            val last: TextView = view.findViewById(R.id.messages_row_last)
            val time: TextView = view.findViewById(R.id.messages_row_time)
            val unread: View = view.findViewById(R.id.messages_row_unread)

            /** TalkBack's "Slett samtale" on this row ([View.NO_ID]: none). */
            var deleteAction = View.NO_ID
        }

        @android.annotation.SuppressLint("NotifyDataSetChanged")
        fun submit(next: List<Row>) {
            items = next
            notifyDataSetChanged()
        }

        override fun getItemCount() = items.size

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_messages_row, parent, false))

        override fun onBindViewHolder(holder: Holder, position: Int) {
            when (val item = items[position]) {
                is Row.Conversation -> bindConversation(holder, item.row, item.match)
                is Row.Contact -> bindContact(holder, item.contact, item.match)
            }
        }

        private fun bindContact(holder: Holder, contact: RuleContact, match: SearchMatch?) {
            MessagesScreens.bindAvatar(holder.photo, holder.initial, contact, contact.name, contact.number, isEmergency)
            holder.name.text = highlightMatch(holder.itemView.context, contact.name, match)
            holder.last.visibility = View.GONE
            holder.time.text = null
            holder.unread.visibility = View.GONE
            holder.itemView.contentDescription = contact.name
            holder.itemView.setOnClickListener { startWith(contact) }
            holder.itemView.setOnLongClickListener(null)
            holder.itemView.isLongClickable = false
            removeDeleteAction(holder)
        }

        private fun bindConversation(holder: Holder, row: ListRow, match: SearchMatch?) {
            val context = holder.itemView.context
            val name = MessagesScreens.name(context, row.who)
            val address = row.conversation.participants.singleOrNull()?.address.orEmpty()
            MessagesScreens.bindAvatar(holder.photo, holder.initial, row.contact, name, address, isEmergency)
            holder.name.text = highlightMatch(context, name, match)
            val last = lastText(row.last)
            holder.last.visibility = if (last == null) View.GONE else View.VISIBLE
            holder.last.text = last
            val time = row.last?.let { MessagesScreens.timeText(context, listTimeLabel(it.at, System.currentTimeMillis(), ZoneId.systemDefault())) }
            holder.time.text = time
            val unread = row.conversation.unread
            holder.unread.visibility = if (unread > 0) View.VISIBLE else View.GONE
            holder.itemView.contentDescription = listOfNotNull(
                name,
                last,
                time,
                if (unread > 0) context.resources.getQuantityString(R.plurals.messages_unread, unread, unread) else null,
            ).joinToString(", ")
            holder.itemView.setOnClickListener { open(row) }
            holder.itemView.setOnLongClickListener {
                askDelete(row)
                true
            }
            // TalkBack's way to the same delete (a long-press is hard to find there).
            removeDeleteAction(holder)
            holder.deleteAction = ViewCompat.addAccessibilityAction(holder.itemView, getString(R.string.messages_delete_conversation)) { _, _ ->
                askDelete(row)
                true
            }
        }

        private fun removeDeleteAction(holder: Holder) {
            if (holder.deleteAction != View.NO_ID) ViewCompat.removeAccessibilityAction(holder.itemView, holder.deleteAction)
            holder.deleteAction = View.NO_ID
        }
    }

    private fun lastText(message: Message?): String? = when (val line = lastLine(message)) {
        is LastLine.Text -> if (line.mine) getString(R.string.messages_you_wrote, line.text) else line.text
        is LastLine.Picture -> if (line.mine) getString(R.string.messages_you_wrote, getString(R.string.messages_picture)) else getString(R.string.messages_picture)
        LastLine.Empty -> null
    }

    private companion object {
        const val STATE_PICKING = "picking"
    }
}
