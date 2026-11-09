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
import com.kidslauncher.mdm.ui.UIObjectActivity
import com.kidslauncher.mdm.ui.day.LockWatch
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
class MessagesActivity : UIObjectActivity() {

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

    private val back = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            when {
                deleting != null -> cancelDelete()
                picking -> {
                    picking = false
                    render()
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTaskDescription(
            ActivityManager.TaskDescription.Builder()
                .setLabel(getString(R.string.messages_title))
                .setIcon(R.mipmap.ic_launcher)
                .build(),
        )
        binding = ActivityMessagesBinding.inflate(layoutInflater)
        setContentView(binding.root)
        KidInsets.apply(binding.root)
        binding.messagesList.layoutManager = LinearLayoutManager(this)
        binding.messagesList.adapter = adapter
        onBackPressedDispatcher.addCallback(this, back)
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
            if (deleting != null && rows.none { it.conversation.id == deleting?.conversation?.id }) cancelDelete()
            render()
        }
    }

    /** Whether the kid may start a conversation now: "Styrt av Vibb", the role ours, the gate not shut by a time rule
     * ([mayStartNew]: judged on a contact that isn't an emergency number - code QA N3). */
    private fun mayStart(): Boolean = mayStartNew(SmsRuntime.judge(this), MessagesScreens.rules())

    private fun render() {
        val readOnly = historyReadOnlyNow()
        val canStart = !readOnly && mayStart()
        if (picking && !canStart) picking = false
        back.isEnabled = picking || deleting != null
        if (picking) {
            KidHeader.bind(this, binding.messagesHeader, R.string.messages_new, back = R.string.kid_back)
            val contacts = newMessageContacts(MessagesScreens.rules())
            info(if (contacts.isEmpty()) getString(R.string.messages_new_empty) else null)
            adapter.submit(contacts.map { Row.Contact(it) })
            return
        }
        KidHeader.bind(this, binding.messagesHeader, R.string.messages_title, action = R.string.messages_new.takeIf { canStart }, back = R.string.kid_back) {
            picking = true
            render()
        }
        info(
            when {
                readOnly -> getString(R.string.messages_read_only)
                rows.isEmpty() -> getString(R.string.messages_empty)
                else -> null
            },
        )
        adapter.submit(rows.map { Row.Conversation(it) })
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
        picking = false
        render()
        val existing = rows.firstOrNull { it.conversation.participants.singleOrNull()?.address == contact.number }
        startActivity(MessagesScreens.threadIntent(this, existing?.conversation?.id?.value, contact.number))
    }

    // ---- D18: delete a conversation ----

    private fun askDelete(row: ListRow) {
        deleting = row
        back.isEnabled = true
        val panel = binding.root.findViewById<View>(R.id.messages_confirm)
        MessagesScreens.showConfirm(
            panel,
            getString(R.string.messages_delete_conversation_title, MessagesScreens.name(this, row.who)),
            onCancel = ::cancelDelete,
        ) {
            val id = row.conversation.id
            cancelDelete()
            SmsRuntime.ask(this, { engine -> MessageActions.delete(engine, DeleteTarget.WholeConversation(id)) }) {
                SmsNotifier.cancel(applicationContext, id.value)
                load()
            }
        }
    }

    private fun cancelDelete() {
        deleting = null
        MessagesScreens.hideConfirm(binding.root.findViewById(R.id.messages_confirm))
        back.isEnabled = picking
    }

    // ---- the list ----

    private sealed interface Row {
        data class Conversation(val row: ListRow) : Row

        data class Contact(val contact: RuleContact) : Row
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
                is Row.Conversation -> bindConversation(holder, item.row)
                is Row.Contact -> bindContact(holder, item.contact)
            }
        }

        private fun bindContact(holder: Holder, contact: RuleContact) {
            MessagesScreens.bindAvatar(holder.photo, holder.initial, contact, contact.name, contact.number)
            holder.name.text = contact.name
            holder.last.visibility = View.GONE
            holder.time.text = null
            holder.unread.visibility = View.GONE
            holder.itemView.contentDescription = contact.name
            holder.itemView.setOnClickListener { startWith(contact) }
            holder.itemView.setOnLongClickListener(null)
            holder.itemView.isLongClickable = false
            removeDeleteAction(holder)
        }

        private fun bindConversation(holder: Holder, row: ListRow) {
            val context = holder.itemView.context
            val name = MessagesScreens.name(context, row.who)
            val address = row.conversation.participants.singleOrNull()?.address.orEmpty()
            MessagesScreens.bindAvatar(holder.photo, holder.initial, row.contact, name, address)
            holder.name.text = name
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
