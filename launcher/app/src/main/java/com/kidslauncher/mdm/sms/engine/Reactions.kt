package com.kidslauncher.mdm.sms.engine

import java.text.BreakIterator

/*
 * Design 81: SMS reactions - an iPhone's Tapback text ("Likte «…»", "Loved “…”", "Reacted 🎉 to “…”") shown as a small
 * emoji on the bubble it names, and the kid's own reactions sent in the same words. Pure: the forms (D1), which
 * messages may be one (D2's H1 rule), the strict whole-message parse (D2), the one bubble a reaction names (D3), the
 * reactions in effect (D4) and the body the kid sends (D8). The forms are pinned with their source by the vectors file
 * `sms_reactions.json` (SmsReactionsTest), so a capture from a real phone (step 0) changes rows, not code. A body is
 * anyone's input: every length is bounded, and anything that isn't exactly one form naming exactly one bubble stays a
 * plain message.
 */

/** What a reaction names: a quoted text ([cut]: it ends in "…", D2), or a picture ("an image" / "et bilde"). */
sealed interface ReactionTarget {
    data class Quote(val text: String, val cut: Boolean) : ReactionTarget

    data object Image : ReactionTarget
}

/** A body that is exactly one known form (D2): [emoji] fully-qualified, [key] without selectors. */
data class ParsedReaction(val emoji: String, val key: String, val op: ReactionOp, val target: ReactionTarget)

/** A bubble a reaction may name (D3): who wrote it ([sender], `null` for the kid), its text, its image parts' states,
 * and the reactions in effect on it ([Reactions.state]). */
data class ReactionCandidate(
    val id: String,
    val sender: String?,
    val body: String?,
    val pictures: List<PartState>,
    val reactions: List<Reaction>,
)

/**
 * One form (D1): Apple's words with `{e}` for the emoji of the iOS 18 form and `{q}` for the quoted text in the
 * language's own quotes ([emoji]: the classic Tapback's emoji, `null` when it is part of the text). [send]: the form the
 * kid's reaction goes in (en (US) and nb); the en_GB spellings are only read.
 */
class ReactionForm(val lang: String, val op: ReactionOp, val emoji: String?, val template: String, val send: Boolean = true) {
    val image: Boolean get() = QUOTE !in template

    /** The words before the emoji (or before the quote, or the whole picture form), and those after the emoji up to
     * the opening quote or the end. */
    internal val head: String = template.substring(0, template.indexOf(EMOJI).takeIf { it >= 0 } ?: template.indexOf(QUOTE).takeIf { it >= 0 }?.minus(1) ?: template.length)
    internal val tail: String = if (EMOJI !in template) "" else template.substringAfter(EMOJI).let { if (image) it else it.substringBefore(QUOTE).dropLast(1) }

    internal companion object {
        const val EMOJI = "{e}"
        const val QUOTE = "{q}"
    }
}

object Reactions {
    /** D2 (N4): a longer body isn't parsed at all. */
    const val BODY_MAX = 2000

    /** D8 (L4): the whole reaction the kid sends, at most three UCS-2 parts. */
    const val SEND_MAX = 200

    /** D3: the candidates (bubbles only, the query filters, M2), and the newest of them a picture reaction looks at. */
    const val CANDIDATES = 100
    const val PICTURE_WINDOW = 10

    /** D3 (L3): a cut quote matches by its start only with at least this many code points before the "…". */
    const val CUT_MIN = 10
    const val CUT = "…"

    private const val HEART = "❤️"
    private const val LIKE = "👍"
    private const val DISLIKE = "👎"
    private const val LAUGH = "😂"
    private const val EMPHASIS = "‼️"
    private const val QUESTION = "❓"

    /** D7: the six, in the panel's order - fully-qualified. */
    val SIX = listOf(HEART, LIKE, DISLIKE, LAUGH, EMPHASIS, QUESTION)

    /** The quote pairs accepted with every form (D2): carriers and old clients may change quotes. */
    private val QUOTES = mapOf('“' to '”', '"' to '"', '«' to '»', '”' to '”')

    /**
     * D3 (L2; code QA M1): a picture reaction attaches only to a picture that is there. An MMS still being fetched has
     * a placeholder picture whatever it turns out to be - a text, a reaction itself, or dropped - so a reaction on it
     * could end on the wrong bubble or hidden with it; it stays a plain message instead. A placeholder never carries a
     * reaction, so nothing moves, drops or turns into a reaction with one on it.
     */
    private val PICTURE_TARGET = setOf(PartState.AVAILABLE)

    /** The kid's rows that count but aren't sent yet (D4). */
    private val PENDING = setOf(Delivery.QUEUED, Delivery.SENDING, Delivery.UNCERTAIN)

    private val ADD = ReactionOp.ADD
    private val REMOVE = ReactionOp.REMOVE

    /** D1's forms, from Apple's `IMSharedUtilities.loctable` (§1 of the note; none captured on the wire yet but nb's
     * heart). The same rows, with their source, are the vectors file's `forms`. */
    val FORMS: List<ReactionForm> = listOf(
        // English: a text.
        ReactionForm("en", ADD, HEART, "Loved “{q}”"),
        ReactionForm("en", ADD, LIKE, "Liked “{q}”"),
        ReactionForm("en", ADD, DISLIKE, "Disliked “{q}”"),
        ReactionForm("en", ADD, LAUGH, "Laughed at “{q}”"),
        ReactionForm("en", ADD, EMPHASIS, "Emphasized “{q}”"),
        ReactionForm("en", ADD, EMPHASIS, "Emphasised “{q}”", send = false),
        ReactionForm("en", ADD, QUESTION, "Questioned “{q}”"),
        ReactionForm("en", ADD, null, "Reacted {e} to “{q}”"),
        ReactionForm("en", REMOVE, HEART, "Removed a heart from “{q}”"),
        ReactionForm("en", REMOVE, LIKE, "Removed a like from “{q}”"),
        ReactionForm("en", REMOVE, DISLIKE, "Removed a dislike from “{q}”"),
        ReactionForm("en", REMOVE, LAUGH, "Removed a laugh from “{q}”"),
        ReactionForm("en", REMOVE, EMPHASIS, "Removed an exclamation from “{q}”"),
        ReactionForm("en", REMOVE, EMPHASIS, "Removed an exclamation mark from “{q}”", send = false),
        ReactionForm("en", REMOVE, QUESTION, "Removed a question mark from “{q}”"),
        ReactionForm("en", REMOVE, null, "Removed {e} from “{q}”"),
        // English: a picture.
        ReactionForm("en", ADD, HEART, "Loved an image"),
        ReactionForm("en", ADD, LIKE, "Liked an image"),
        ReactionForm("en", ADD, DISLIKE, "Disliked an image"),
        ReactionForm("en", ADD, LAUGH, "Laughed at an image"),
        ReactionForm("en", ADD, EMPHASIS, "Emphasized an image"),
        ReactionForm("en", ADD, EMPHASIS, "Emphasised an image", send = false),
        ReactionForm("en", ADD, QUESTION, "Questioned an image"),
        ReactionForm("en", ADD, null, "Reacted {e} to an image"),
        ReactionForm("en", REMOVE, HEART, "Removed a heart from an image"),
        ReactionForm("en", REMOVE, LIKE, "Removed a like from an image"),
        ReactionForm("en", REMOVE, DISLIKE, "Removed a dislike from an image"),
        ReactionForm("en", REMOVE, LAUGH, "Removed a laugh from an image"),
        ReactionForm("en", REMOVE, EMPHASIS, "Removed an exclamation from an image"),
        ReactionForm("en", REMOVE, EMPHASIS, "Removed an exclamation mark from an image", send = false),
        ReactionForm("en", REMOVE, QUESTION, "Removed a question mark from an image"),
        ReactionForm("en", REMOVE, null, "Removed {e} from an image"),
        // Norwegian Bokmål (Apple's `no`): a text.
        ReactionForm("nb", ADD, HEART, "Satte et hjerte ved «{q}»"),
        ReactionForm("nb", ADD, LIKE, "Likte «{q}»"),
        ReactionForm("nb", ADD, DISLIKE, "Mislikte «{q}»"),
        ReactionForm("nb", ADD, LAUGH, "Lo av «{q}»"),
        ReactionForm("nb", ADD, EMPHASIS, "Satte utropstegn ved «{q}»"),
        ReactionForm("nb", ADD, QUESTION, "Satte spørsmålstegn ved «{q}»"),
        ReactionForm("nb", ADD, null, "Reagerte med {e} på «{q}»"),
        ReactionForm("nb", REMOVE, HEART, "Fjernet et hjerte fra «{q}»"),
        ReactionForm("nb", REMOVE, LIKE, "Liker ikke lenger «{q}»"),
        ReactionForm("nb", REMOVE, DISLIKE, "Misliker ikke lenger «{q}»"),
        ReactionForm("nb", REMOVE, LAUGH, "Fjernet latter fra «{q}»"),
        ReactionForm("nb", REMOVE, EMPHASIS, "Fjernet et utropstegn fra «{q}»"),
        ReactionForm("nb", REMOVE, QUESTION, "Fjernet et spørsmålstegn fra «{q}»"),
        ReactionForm("nb", REMOVE, null, "Fjernet {e} fra «{q}»"),
        // Norwegian Bokmål: a picture (the two "ikke lenger" say "bildet").
        ReactionForm("nb", ADD, HEART, "Satte et hjerte ved et bilde"),
        ReactionForm("nb", ADD, LIKE, "Likte et bilde"),
        ReactionForm("nb", ADD, DISLIKE, "Mislikte et bilde"),
        ReactionForm("nb", ADD, LAUGH, "Lo av et bilde"),
        ReactionForm("nb", ADD, EMPHASIS, "Satte utropstegn ved et bilde"),
        ReactionForm("nb", ADD, QUESTION, "Satte spørsmålstegn ved et bilde"),
        ReactionForm("nb", ADD, null, "Reagerte med {e} på et bilde"),
        ReactionForm("nb", REMOVE, HEART, "Fjernet et hjerte fra et bilde"),
        ReactionForm("nb", REMOVE, LIKE, "Liker ikke lenger bildet"),
        ReactionForm("nb", REMOVE, DISLIKE, "Misliker ikke lenger bildet"),
        ReactionForm("nb", REMOVE, LAUGH, "Fjernet latter fra et bilde"),
        ReactionForm("nb", REMOVE, EMPHASIS, "Fjernet et utropstegn fra et bilde"),
        ReactionForm("nb", REMOVE, QUESTION, "Fjernet et spørsmålstegn fra et bilde"),
        ReactionForm("nb", REMOVE, null, "Fjernet {e} fra et bilde"),
    )

    /** D2 (H1): only a message that holds nothing but its text may be a reaction - an SMS always (it is one text), an
     * MMS only with exactly one text part and no other part. */
    fun candidate(textParts: Int, otherParts: Int): Boolean = textParts == 1 && otherParts == 0

    /**
     * D2's emoji rule over [list] - and the six (D7) whatever the list holds: they are constants, so an unreadable asset
     * ([EmojiList.EMPTY]) still lets them be received and sent (code QA N1). Within [EmojiList.MAX_UNITS] either way.
     */
    fun emoji(raw: String, list: EmojiList): String? {
        list.canonical(raw)?.let { return it }
        if (raw.isEmpty() || raw.length > EmojiList.MAX_UNITS) return null
        val key = EmojiList.key(raw)
        return SIX.firstOrNull { EmojiList.key(it) == key }
    }

    /** D2 (L3): what both sides of a comparison get - CRLF to LF, the outer whitespace trimmed, nothing else. */
    fun normalise(text: String): String = text.replace("\r\n", "\n").trim()

    /** D2: [body] as exactly one form, its emoji part a sequence of [list] (the classic six need none); else `null`. */
    fun parse(body: String, list: EmojiList): ParsedReaction? {
        if (body.length > BODY_MAX) return null
        return FORMS.firstNotNullOfOrNull { match(body, it, list) }
    }

    private fun match(body: String, form: ReactionForm, list: EmojiList): ParsedReaction? {
        if (!body.startsWith(form.head)) return null
        var at = form.head.length
        val emoji = form.emoji ?: run {
            // The emoji runs to the first place the words after it start (a quote may hold them too), at most 16 units.
            val end = if (form.image) body.length - form.tail.length else body.indexOf(form.tail, at + 1)
            if (end - at !in 1..EmojiList.MAX_UNITS || !body.startsWith(form.tail, end)) return null
            val raw = body.substring(at, end)
            at = end + form.tail.length
            emoji(raw, list) ?: return null
        }
        val target = if (form.image) {
            if (at != body.length) return null
            ReactionTarget.Image
        } else {
            // An opening quote right here, its own closing one as the very last character; anything between.
            if (body.length - at < 2 || QUOTES[body[at]] != body.last()) return null
            val text = normalise(body.substring(at + 1, body.length - 1))
            if (text.isEmpty()) return null
            ReactionTarget.Quote(text, text.endsWith(CUT))
        }
        return ParsedReaction(emoji, EmojiList.key(emoji), form.op, target)
    }

    /**
     * D3: the bubble [parsed] names among [candidates] - its conversation's newest bubbles older than the reaction,
     * newest first - from [reactor]; `null` when none or ambiguous (the message stays plain text). An add goes to the
     * newest equal bubble the reactor didn't write (or the newest, if they wrote them all); a removal to the newest
     * one carrying the reactor's reaction with that emoji (M4), and with nothing to remove there is no target.
     */
    fun resolve(parsed: ParsedReaction, reactor: String, candidates: List<ReactionCandidate>): String? {
        val pool = when (val target = parsed.target) {
            ReactionTarget.Image -> {
                // L2: every image part among the newest few, in any state - exactly one, and there (M1).
                val pictures = candidates.take(PICTURE_WINDOW).filter { it.pictures.isNotEmpty() }
                if (pictures.size != 1 || pictures.single().pictures.singleOrNull() !in PICTURE_TARGET) return null
                pictures
            }
            is ReactionTarget.Quote -> candidates.filter { it.body?.let(::normalise) == target.text }.ifEmpty { cutMatches(target, candidates) }
        }
        if (pool.isEmpty()) return null
        return when (parsed.op) {
            ReactionOp.ADD -> (pool.firstOrNull { it.sender != reactor } ?: pool.first()).id
            ReactionOp.REMOVE -> pool.firstOrNull { c -> c.reactions.any { it.by == reactor && it.key == parsed.key } }?.id
        }
    }

    /** D3: a quote cut with "…" and at least [CUT_MIN] code points before it names the bubbles that start with it - only
     * when they all say the same. */
    private fun cutMatches(target: ReactionTarget.Quote, candidates: List<ReactionCandidate>): List<ReactionCandidate> {
        if (!target.cut) return emptyList()
        val start = target.text.removeSuffix(CUT)
        if (start.codePointCount(0, start.length) < CUT_MIN) return emptyList()
        val hits = candidates.filter { it.body?.let(::normalise)?.startsWith(start) == true }
        return hits.takeIf { list -> list.map { normalise(it.body!!) }.distinct().size == 1 }.orEmpty()
    }

    /**
     * D4: the reactions in effect on one bubble from its reaction [rows] - per person their newest add, unless a later
     * removal of the same emoji (by key) took it away. The kid's failed rows don't count (the earlier state shows
     * again); queued, sending and uncertain ones do, as pending. Oldest first.
     */
    fun state(rows: List<MessageRow>): List<Reaction> {
        val current = LinkedHashMap<String?, MessageRow>()
        for (row in rows.sortedBy { it.seq }) {
            if (row.direction == Direction.OUT && row.delivery == Delivery.FAILED) continue
            val by = if (row.direction == Direction.IN) row.sender else null
            when (row.reactionOp) {
                ReactionOp.ADD -> {
                    current.remove(by)
                    current[by] = row
                }
                ReactionOp.REMOVE -> if (current[by]?.reactionKey == row.reactionKey) current.remove(by)
                null -> Unit
            }
        }
        return current.map { (by, row) ->
            Reaction(MessageId(row.id), row.reactionEmoji.orEmpty(), row.reactionKey.orEmpty(), by, pending = by == null && row.delivery in PENDING)
        }
    }

    /**
     * D8: the body of the kid's reaction - Apple's form in [lang] (`nb`, else en (US)): the six in their classic words,
     * any other emoji in the iOS 18 form. [quote] is the bubble's text, `null` for a picture without text. A long quote is
     * cut at a grapheme boundary and ends in "…", so the whole stays within [SEND_MAX].
     */
    fun format(emoji: String, op: ReactionOp, quote: String?, lang: String): String {
        val language = if (lang == "nb") "nb" else "en"
        val key = EmojiList.key(emoji)
        val forms = FORMS.filter { it.send && it.lang == language && it.op == op && it.image == (quote == null) }
        val form = forms.firstOrNull { it.emoji != null && EmojiList.key(it.emoji) == key } ?: forms.first { it.emoji == null }
        val words = form.template.replace(ReactionForm.EMOJI, emoji)
        if (quote == null) return words
        val text = normalise(quote)
        val whole = words.replace(ReactionForm.QUOTE, text)
        if (whole.length <= SEND_MAX) return whole
        return words.replace(ReactionForm.QUOTE, cut(text, SEND_MAX - (words.length - ReactionForm.QUOTE.length) - CUT.length) + CUT)
    }

    /** [text]'s longest start of whole grapheme clusters within [max] UTF-16 units, without trailing whitespace. */
    fun cut(text: String, max: Int): String {
        if (text.length <= max) return text
        val boundaries = BreakIterator.getCharacterInstance().apply { setText(text) }
        var end = 0
        while (true) {
            val next = boundaries.next()
            if (next == BreakIterator.DONE || next > max) break
            end = next
        }
        return text.substring(0, end).trimEnd()
    }
}
