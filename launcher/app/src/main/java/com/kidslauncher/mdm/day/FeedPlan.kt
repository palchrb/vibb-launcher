package com.kidslauncher.mdm.day

/*
 * What a pass does to one link's calendar (design 72 §4): the diff of the new set against the rows the provider holds,
 * the batches it is written in, and the pass's outcome. Pure; FeedPlanTest.
 */

/** One row of a link's calendar as the provider holds it. */
data class StoredRow(
    val id: Long,
    val syncId: String?,
    /** Set on an override (its series' uid). */
    val originalSyncId: String?,
    val seriesRev: String?,
    val writtenZone: String?,
) {
    override fun toString() = "StoredRow($id)"
}

/** The rows to delete (by id) and the series to insert. */
data class FeedPlan(val deletes: List<Long>, val inserts: List<SeriesRows>) {
    val nothing: Boolean get() = deletes.isEmpty() && inserts.isEmpty()

    override fun toString() = "FeedPlan(${deletes.size} deletes, ${inserts.size} inserts)"
}

/**
 * The diff (§4, "The plan"): a series gone from the set -> its rows deleted (the master and, explicitly, its
 * overrides by ORIGINAL_SYNC_ID); a different rev, a floating series written in another zone, two masters of one uid
 * or a mapping bump ([mappingCurrent] false: every row) -> the series deleted and inserted; a row of no series
 * (half-made) -> deleted; else nothing.
 */
fun feedPlan(series: List<SeriesRows>, stored: List<StoredRow>, phoneZone: String, mappingCurrent: Boolean): FeedPlan {
    if (!mappingCurrent) return FeedPlan(stored.map { it.id }, series)
    val masters = stored.filter { it.originalSyncId == null }.groupBy { it.syncId }
    val overrides = stored.filter { it.originalSyncId != null }.groupBy { it.originalSyncId }
    val wanted = series.associateBy { it.uid }
    val deletes = LinkedHashSet<Long>()
    val inserts = ArrayList<SeriesRows>()
    for (s in series) {
        val have = masters[s.uid]
        val current = have?.singleOrNull()?.let { it.seriesRev == s.rev && (it.writtenZone == null || it.writtenZone == phoneZone) } == true
        if (current) continue
        have?.forEach { deletes += it.id }
        overrides[s.uid]?.forEach { deletes += it.id }
        inserts += s
    }
    for ((uid, rows) in masters) {
        if (uid != null && uid in wanted) continue
        rows.forEach { deletes += it.id }
        overrides[uid]?.forEach { deletes += it.id }
    }
    for ((uid, rows) in overrides) {
        if (masters[uid] == null) rows.forEach { deletes += it.id }
    }
    return FeedPlan(deletes.toList(), inserts)
}

/** Operations per `applyBatch` call (QA L8). */
const val FEED_BATCH_OPS = 200

/** The pass's calls: the deletes first, in chunks; then the inserts packed whole-series into batches of at most
 * [max] operations - a series is never split (atomic per series), one larger than [max] is a batch of its own. */
data class FeedBatches(val deleteChunks: List<List<Long>>, val insertBatches: List<List<SeriesRows>>)

fun feedBatches(plan: FeedPlan, max: Int = FEED_BATCH_OPS): FeedBatches {
    val batches = ArrayList<List<SeriesRows>>()
    var current = ArrayList<SeriesRows>()
    var ops = 0
    for (s in plan.inserts) {
        if (current.isNotEmpty() && ops + s.operations > max) {
            batches += current
            current = ArrayList()
            ops = 0
        }
        current += s
        ops += s.operations
    }
    if (current.isNotEmpty()) batches += current
    return FeedBatches(plan.deletes.chunked(max), batches)
}

/** What one provider call did. */
enum class BatchResult {
    OK,

    /** The provider refused the rows (IllegalArgumentException, SQLiteException): retried series by series. */
    REFUSED,

    /** The provider is gone (IllegalStateException, no client, a dead process): the pass ends. */
    GONE,
}

/** A pass's end (QA M2): the provider gone leaves the applied rev old (the next sync plans again - idempotent and
 * resumable); a pass whose only misses were refusals writes the rev, so a bad series doesn't make every sync GET the
 * set again. [refusedIndexes]: the refused series' places in the set's order (logged, never their text). */
data class PassResult(val gone: Boolean, val refused: Int, val refusedIndexes: List<Int> = emptyList()) {
    val writeRev: Boolean get() = !gone
}

/** Runs the batches (deletes first): a refused insert batch is retried one series at a time, one still refused is
 * counted; a refused delete chunk or the provider gone ends the pass without the rev.
 *
 * A refused delete is kept as an end (QA L4), not counted and passed over: the inserts that follow would put a new
 * master next to an old one the delete left (a uid twice), and the next pass plans the deletes again. It can't
 * happen in practice: every delete is by `_ID` through the sync-adapter URI, and the provider's refusals
 * (IllegalArgumentException, a constraint) are value checks on an insert or update - a delete has no values. */
fun runPass(
    batches: FeedBatches,
    indexOf: (SeriesRows) -> Int,
    delete: (List<Long>) -> BatchResult,
    insert: (List<SeriesRows>) -> BatchResult,
): PassResult {
    for (chunk in batches.deleteChunks) {
        if (delete(chunk) != BatchResult.OK) return PassResult(gone = true, refused = 0)
    }
    val refused = ArrayList<Int>()
    for (batch in batches.insertBatches) {
        when (insert(batch)) {
            BatchResult.OK -> {}
            BatchResult.GONE -> return PassResult(true, refused.size, refused)
            BatchResult.REFUSED -> for (s in batch) {
                when (if (batch.size == 1) BatchResult.REFUSED else insert(listOf(s))) {
                    BatchResult.OK -> {}
                    BatchResult.GONE -> return PassResult(true, refused.size, refused)
                    BatchResult.REFUSED -> refused += indexOf(s)
                }
            }
        }
    }
    return PassResult(false, refused.size, refused)
}

/** Why a link's set is fetched this sync; `null` = nothing to do (no request). [ifNoneMatch] only when the rev alone
 * moved: the server answers 304 when it hasn't. */
data class FeedFetch(val ifNoneMatch: String?)

/**
 * Whether a link's calendar needs its set (§3, §4): it doesn't exist yet, its applied rev isn't the policy's, it was
 * written by another mapping, or a floating series of it was written in another zone ([floatingZone] = `CAL_SYNC5`,
 * the zone it wrote floating series in, empty when none).
 */
fun feedFetch(exists: Boolean, appliedRev: String?, policyRev: String, mapping: String?, floatingZone: String?, phoneZone: String): FeedFetch? {
    if (!exists || mapping != FEED_MAPPING || (!floatingZone.isNullOrEmpty() && floatingZone != phoneZone)) return FeedFetch(null)
    if (appliedRev != policyRev) return FeedFetch(appliedRev?.let { "\"$it\"" })
    return null
}

/**
 * The link calendars to delete (§4, "Feeds gone"): those whose uid the policy doesn't name - only when the policy's
 * `feeds` is a fresh array every element of which read ([CalendarPolicy.feedsComplete], QA L9); else none.
 */
fun feedCalendarsGone(policy: CalendarPolicy, calendarUids: Collection<String>): List<String> {
    if (!policy.usable || !policy.feedsComplete) return emptyList()
    val named = policy.feeds.map { it.id }.toSet()
    return calendarUids.filter { it !in named }
}
