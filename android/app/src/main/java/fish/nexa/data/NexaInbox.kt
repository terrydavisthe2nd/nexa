package fish.nexa.data

import android.util.Log
import fish.nexa.Nexa
import fish.nexa.net.wireJson
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

// ---------------------------------------------------------------------------
// The account's side of Nexa (web/src/stores/nexa.ts, api/moderation.ts): its
// standing (a mute or ban in force, notices not dismissed yet), its Nexa
// conversation, and its case and bug report threads. The standing comes with
// the account and changes by gateway event; the conversation and the threads
// are fetched when opened and kept current by the same events. Nothing here is
// end-to-end encrypted, and the screens say so.
// ---------------------------------------------------------------------------

/** Someone a Nexa response names: a moderator, the reporter, Nexa itself. */
@Serializable
data class NexaPerson(
    val id: String,
    val username: String = "",
    @SerialName("display_name") val displayName: String = "",
    @SerialName("avatar_id") val avatarId: String? = null,
    val flags: Long = 0,
    val deleted: Boolean = false,
)

/** A mute or a ban in force. */
@Serializable
data class Restriction(
    val kind: String = "",
    @SerialName("case_id") val caseId: String = "",
    @SerialName("case_number") val caseNumber: Long = 0,
    val reason: String = "",
    @SerialName("moderator_id") val moderatorId: String = "",
    /** Null for one that does not end on its own. */
    @SerialName("expires_at") val expiresAt: Long? = null,
    @SerialName("created_at") val createdAt: Long = 0,
) {
    fun inForce(now: Long = System.currentTimeMillis()) = expiresAt == null || expiresAt > now
}

/** A file in a case thread or a bug report: fetched at /media/{cases|bugs}/{id}. */
@Serializable
data class NexaFile(
    val id: String,
    val name: String = "",
    val mime: String = "",
    val size: Long = 0,
    val width: Int? = null,
    val height: Int? = null,
)

/** A case as its member sees it. */
@Serializable
data class MemberCase(
    val id: String,
    val number: Long = 0,
    val action: String = "",
    val reason: String = "",
    val details: JsonObject = JsonObject(emptyMap()),
    @SerialName("moderator_id") val moderatorId: String = "",
    @SerialName("created_at") val createdAt: Long = 0,
    @SerialName("expires_at") val expiresAt: Long? = null,
    @SerialName("lifted_at") val liftedAt: Long? = null,
    @SerialName("lifted_by") val liftedBy: String? = null,
    @SerialName("lift_reason") val liftReason: String = "",
    val status: String = "open",
    @SerialName("in_effect") val inEffect: Boolean = false,
    val ended: Boolean = false,
    val unread: Int = 0,
    val acknowledged: Boolean = false,
    @SerialName("last_activity_at") val lastActivityAt: Long = 0,
)

/** A line of a case thread. */
@Serializable
data class CaseMessage(
    val id: String,
    @SerialName("case_id") val caseId: String = "",
    @SerialName("author_id") val authorId: String = "",
    val kind: String = "member",
    val event: String = "",
    val body: String = "",
    @SerialName("created_at") val createdAt: Long = 0,
    val files: List<NexaFile> = emptyList(),
)

@Serializable
data class Announcement(
    val id: String,
    val title: String = "",
    val body: String = "",
    @SerialName("author_id") val authorId: String = "",
    @SerialName("created_at") val createdAt: Long = 0,
)

/** A bug report as its reporter sees it. */
@Serializable
data class MemberBug(
    val id: String,
    val number: Long = 0,
    val subject: String = "",
    val description: String = "",
    val context: JsonObject = JsonObject(emptyMap()),
    val status: String = "open",
    @SerialName("status_at") val statusAt: Long? = null,
    @SerialName("status_by") val statusBy: String? = null,
    val awaiting: String = "none",
    val attachments: List<NexaFile> = emptyList(),
    val unread: Int = 0,
    @SerialName("last_activity_at") val lastActivityAt: Long = 0,
    @SerialName("created_at") val createdAt: Long = 0,
)

/** A line of a bug report's thread: the reporter's, the team's, or a status change. */
@Serializable
data class BugMessage(
    val id: String,
    @SerialName("report_id") val reportId: String = "",
    @SerialName("author_id") val authorId: String = "",
    val kind: String = "member",
    val event: String = "",
    val body: String = "",
    @SerialName("created_at") val createdAt: Long = 0,
    /** The pictures and recordings sent with it. */
    val files: List<NexaFile> = emptyList(),
)

/** One thing in the Nexa conversation. */
sealed class FeedItem {
    abstract val at: Long
    abstract val key: String

    data class Notice(override val at: Long, val case: MemberCase) : FeedItem() { override val key get() = "notice:${case.id}" }
    data class Lift(override val at: Long, val case: MemberCase) : FeedItem() { override val key get() = "lift:${case.id}" }
    data class News(override val at: Long, val announcement: Announcement) : FeedItem() { override val key get() = "a:${announcement.id}" }
    data class Bug(override val at: Long, val bug: MemberBug) : FeedItem() { override val key get() = "bug:${bug.id}" }
    data class BugLine(override val at: Long, val bug: MemberBug, val message: BugMessage, val status: Boolean) : FeedItem() { override val key get() = "bugline:${message.id}" }
    /** A safety report the member sent, or (outcome) what staff told them on closing it. */
    data class Report(override val at: Long, val report: ReportReceipt, val outcome: Boolean) : FeedItem() { override val key get() = (if (outcome) "report_outcome:" else "report:") + report.id }

    companion object {
        fun parse(el: JsonElement): FeedItem? {
            val o = el as? JsonObject ?: return null
            val at = (o["at"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: 0L
            return runCatching {
                when ((o["kind"] as? JsonPrimitive)?.contentOrNull) {
                    "notice" -> Notice(at, wireJson.decodeFromJsonElement(MemberCase.serializer(), o["case"]!!))
                    "lift" -> Lift(at, wireJson.decodeFromJsonElement(MemberCase.serializer(), o["case"]!!))
                    "announcement" -> News(at, wireJson.decodeFromJsonElement(Announcement.serializer(), o["announcement"]!!))
                    "bug" -> Bug(at, wireJson.decodeFromJsonElement(MemberBug.serializer(), o["bug"]!!))
                    "bug_reply", "bug_status" -> BugLine(
                        at,
                        wireJson.decodeFromJsonElement(MemberBug.serializer(), o["bug"]!!),
                        wireJson.decodeFromJsonElement(BugMessage.serializer(), o["message"]!!),
                        status = (o["kind"] as JsonPrimitive).content == "bug_status",
                    )
                    "report", "report_outcome" -> Report(at, wireJson.decodeFromJsonElement(ReportReceipt.serializer(), o["report"]!!), outcome = (o["kind"] as JsonPrimitive).content == "report_outcome")
                    else -> null
                }
            }.getOrNull()
        }
    }
}

/** The account's standing (NexaState). */
data class NexaStanding(
    val accountId: String = "",
    val mute: Restriction? = null,
    val ban: Restriction? = null,
    /** Notices not dismissed yet, oldest first. */
    val pending: List<MemberCase> = emptyList(),
    val unread: Int = 0,
    /** When Nexa last had anything for this account (0 for never). */
    val latest: Long = 0,
    val ownerId: String? = null,
) {
    companion object {
        fun parse(el: JsonElement?): Pair<NexaStanding, Map<String, NexaPerson>>? {
            val o = el as? JsonObject ?: return null
            fun s(k: String) = (o[k] as? JsonPrimitive)?.contentOrNull
            fun restriction(k: String) = (o[k] as? JsonObject)?.let { runCatching { wireJson.decodeFromJsonElement(Restriction.serializer(), it) }.getOrNull() }
            val pending = (o["pending"] as? JsonArray)?.mapNotNull { runCatching { wireJson.decodeFromJsonElement(MemberCase.serializer(), it) }.getOrNull() } ?: emptyList()
            val people = NexaInbox.peopleOf(o["people"])
            return NexaStanding(
                accountId = s("account_id") ?: "",
                mute = restriction("mute"),
                ban = restriction("ban"),
                pending = pending,
                unread = s("unread")?.toIntOrNull() ?: 0,
                latest = s("latest")?.toLongOrNull() ?: 0,
                ownerId = s("owner_id"),
            ) to people
        }
    }
}

object NexaInbox {
    private const val TAG = "NexaInbox"

    private val _standing = MutableStateFlow<NexaStanding?>(null)
    val standing: StateFlow<NexaStanding?> = _standing
    private val _people = MutableStateFlow<Map<String, NexaPerson>>(emptyMap())
    val people: StateFlow<Map<String, NexaPerson>> = _people
    /** The conversation, oldest first; null until it is opened. */
    private val _feed = MutableStateFlow<List<FeedItem>?>(null)
    val feed: StateFlow<List<FeedItem>?> = _feed
    val feedMore = MutableStateFlow(false)
    val feedLoading = MutableStateFlow(false)

    data class CaseThread(val case: MemberCase, val messages: List<CaseMessage>)
    data class BugThread(val report: MemberBug, val messages: List<BugMessage>)

    private val _threads = MutableStateFlow<Map<String, CaseThread>>(emptyMap())
    val threads: StateFlow<Map<String, CaseThread>> = _threads
    private val _bugThreads = MutableStateFlow<Map<String, BugThread>>(emptyMap())
    val bugThreads: StateFlow<Map<String, BugThread>> = _bugThreads

    /** A deletion notice: kept past sign-out so it can be read on the way out. */
    val deleted = MutableStateFlow<Pair<MemberCase, Map<String, NexaPerson>>?>(null)

    /** Bumps on sign-out, so an answer for the old account is dropped. */
    private var generation = 0

    // -----------------------------------------------------------------------
    // the standing

    fun peopleOf(el: JsonElement?): Map<String, NexaPerson> {
        val o = el as? JsonObject ?: return emptyMap()
        return runCatching { wireJson.decodeFromJsonElement(MapSerializer(String.serializer(), NexaPerson.serializer()), o) }.getOrDefault(emptyMap())
    }

    fun mergePeople(p: Map<String, NexaPerson>) {
        if (p.isNotEmpty()) _people.update { it + p }
    }

    private fun mergePeople(el: JsonElement?) = mergePeople(peopleOf(el))

    /** The standing as it arrives: with the account, at READY, and in every Nexa event. */
    fun setState(el: JsonElement?) {
        if (el == null || el is JsonNull) return
        val (st, people) = NexaStanding.parse(el) ?: return
        _standing.value = st
        mergePeople(people)
        // for staff, what is waiting on them comes with it
        Moderation.applyStaff(el)
    }

    /** A mute in force, or null. */
    fun mute(): Restriction? = _standing.value?.mute?.takeIf { it.inForce() }

    /** A ban in force, or null. */
    fun ban(): Restriction? = _standing.value?.ban?.takeIf { it.inForce() }

    fun unread(): Int = _standing.value?.unread ?: 0
    fun latest(): Long = _standing.value?.latest ?: 0
    fun closedAt(): Long = (Nexa.settings.doc.value["nexaClosed"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: 0L

    /** dmArrange nexaListed: listed once Nexa has said something, and after a close only once it says something new. */
    fun listed(): Boolean = latest() > 0 && latest() > closedAt()

    // -----------------------------------------------------------------------
    // the conversation

    private fun merged(feed: List<FeedItem>, items: List<FeedItem>): List<FeedItem> {
        val byKey = LinkedHashMap<String, FeedItem>()
        for (it in feed) byKey[it.key] = it
        for (it in items) byKey[it.key] = it
        return byKey.values.sortedBy { it.at }
    }

    private fun caseIntoFeed(c: MemberCase) {
        val feed = _feed.value ?: return
        val items = mutableListOf<FeedItem>(FeedItem.Notice(c.createdAt, c))
        c.liftedAt?.let { items += FeedItem.Lift(it, c) }
        _feed.value = merged(feed, items)
    }

    private fun bugIntoFeed(report: MemberBug, lines: List<BugMessage>) {
        val feed = _feed.value ?: return
        val items = mutableListOf<FeedItem>(FeedItem.Bug(report.createdAt, report))
        for (it in feed) if (it is FeedItem.BugLine && it.bug.id == report.id) items += it.copy(bug = report)
        for (m in lines) {
            if (m.kind == "staff") items += FeedItem.BugLine(m.createdAt, report, m, status = false)
            else if (m.kind == "system" && m.event == "status") items += FeedItem.BugLine(m.createdAt, report, m, status = true)
        }
        _feed.value = merged(feed, items)
    }

    private suspend fun fetchFeed(before: Long?): Pair<List<FeedItem>, Boolean> {
        val res = Nexa.api.get("/nexa/feed" + (before?.let { "?before=$it" } ?: "")) as JsonObject
        mergePeople(res["people"])
        val items = (res["items"] as? JsonArray)?.mapNotNull { FeedItem.parse(it) } ?: emptyList()
        return items to ((res["has_more"] as? JsonPrimitive)?.contentOrNull == "true")
    }

    fun loadFeed() {
        if (feedLoading.value) return
        feedLoading.value = true
        val gen = generation
        Nexa.scope.launch {
            try {
                val (items, more) = fetchFeed(null)
                if (gen != generation) return@launch
                _feed.value = merged(_feed.value ?: emptyList(), items)
                feedMore.value = more
            } catch (e: Exception) {
                Log.w(TAG, "feed failed", e)
                if (gen == generation && _feed.value == null) _feed.value = emptyList()
            } finally {
                feedLoading.value = false
            }
        }
    }

    fun loadOlder() {
        val feed = _feed.value ?: return
        if (!feedMore.value || feedLoading.value) return
        feedLoading.value = true
        val gen = generation
        Nexa.scope.launch {
            try {
                val (items, more) = fetchFeed(feed.firstOrNull()?.at)
                if (gen != generation) return@launch
                _feed.value = merged(_feed.value ?: emptyList(), items)
                feedMore.value = more
            } catch (e: Exception) {
                Log.w(TAG, "older failed", e)
            } finally {
                feedLoading.value = false
            }
        }
    }

    // -----------------------------------------------------------------------
    // case threads

    private fun appendLines(existing: List<CaseMessage>, lines: List<CaseMessage>): List<CaseMessage> {
        val seen = existing.mapTo(HashSet()) { it.id }
        return (existing + lines.filter { it.id !in seen }).sortedWith { a, b -> Ids.compare(a.id, b.id) }
    }

    suspend fun loadThread(caseId: String): CaseThread {
        val gen = generation
        val res = Nexa.api.get("/nexa/cases/$caseId") as JsonObject
        mergePeople(res["people"])
        val c = wireJson.decodeFromJsonElement(MemberCase.serializer(), res["case"]!!)
        val messages = wireJson.decodeFromJsonElement(ListSerializer(CaseMessage.serializer()), res["messages"] ?: JsonArray(emptyList()))
        val thread = CaseThread(c, messages)
        if (gen == generation) {
            _threads.update { it + (caseId to thread) }
            caseIntoFeed(c)
        }
        return thread
    }

    /** A line to a case thread, with the files already up. */
    suspend fun reply(caseId: String, body: String, files: List<NexaFile>) {
        val res = Nexa.api.request("POST", "/nexa/cases/$caseId/messages", buildJsonObject {
            put("body", body)
            put("files", JsonArray(files.map { f -> buildJsonObject { put("id", f.id); put("name", f.name) } }))
        }) as JsonObject
        val c = wireJson.decodeFromJsonElement(MemberCase.serializer(), res["case"]!!)
        val lines = wireJson.decodeFromJsonElement(ListSerializer(CaseMessage.serializer()), res["messages"] ?: JsonArray(emptyList()))
        _threads.update { m ->
            val t = m[caseId] ?: return@update m
            m + (caseId to CaseThread(c, appendLines(t.messages, lines)))
        }
        caseIntoFeed(c)
    }

    fun markThreadRead(caseId: String) {
        val t = _threads.value[caseId] ?: return
        if (t.case.unread <= 0) return
        val last = t.messages.lastOrNull() ?: return
        _threads.update { it + (caseId to t.copy(case = t.case.copy(unread = 0))) }
        Nexa.scope.launch { runCatching { Nexa.api.request("POST", "/nexa/cases/$caseId/read", buildJsonObject { put("message_id", last.id) }) } }
    }

    /** Dismisses a notice: it stops popping up on every device. */
    suspend fun ack(caseId: String) {
        _standing.update { s -> s?.copy(pending = s.pending.filter { it.id != caseId }) }
        Nexa.api.request("POST", "/nexa/cases/$caseId/ack")
    }

    // -----------------------------------------------------------------------
    // reading

    /**
     * Reads the whole conversation. `keepMarks` leaves the threads' unread
     * counts showing, for the page reading it right now; clearMarks takes
     * them off after.
     */
    fun markSeen(keepMarks: Boolean = false) {
        val before = _standing.value ?: return
        if (before.unread <= 0) return
        _standing.value = before.copy(unread = 0)
        if (!keepMarks) clearMarks()
        Nexa.scope.launch {
            try {
                Nexa.api.request("POST", "/nexa/seen", buildJsonObject {})
            } catch (_: Exception) {
                if (_standing.value == before.copy(unread = 0)) _standing.value = before
            }
        }
    }

    fun clearMarks() {
        _feed.update { feed ->
            feed?.map { it ->
                when (it) {
                    is FeedItem.Notice -> if (it.case.unread > 0) it.copy(case = it.case.copy(unread = 0)) else it
                    is FeedItem.Lift -> if (it.case.unread > 0) it.copy(case = it.case.copy(unread = 0)) else it
                    is FeedItem.Bug -> if (it.bug.unread > 0) it.copy(bug = it.bug.copy(unread = 0)) else it
                    is FeedItem.BugLine -> if (it.bug.unread > 0) it.copy(bug = it.bug.copy(unread = 0)) else it
                    else -> it
                }
            }
        }
        _threads.update { m -> m.mapValues { (_, t) -> if (t.case.unread > 0) t.copy(case = t.case.copy(unread = 0)) else t } }
        _bugThreads.update { m -> m.mapValues { (_, t) -> if (t.report.unread > 0) t.copy(report = t.report.copy(unread = 0)) else t } }
    }

    // -----------------------------------------------------------------------
    // bug reports

    suspend fun loadBugThread(reportId: String): BugThread {
        val gen = generation
        val res = Nexa.api.get("/bugs/$reportId") as JsonObject
        mergePeople(res["people"])
        val report = wireJson.decodeFromJsonElement(MemberBug.serializer(), res["report"]!!)
        val messages = wireJson.decodeFromJsonElement(ListSerializer(BugMessage.serializer()), res["messages"] ?: JsonArray(emptyList()))
        val thread = BugThread(report, messages)
        if (gen == generation) {
            _bugThreads.update { it + (reportId to thread) }
            bugIntoFeed(report, emptyList())
        }
        return thread
    }

    /** A line to a bug report's thread, with the files already up. */
    suspend fun replyBug(reportId: String, body: String, files: List<NexaFile>) {
        val res = Nexa.api.request("POST", "/bugs/$reportId/messages", buildJsonObject {
            put("body", body)
            put("files", JsonArray(files.map { f -> buildJsonObject { put("id", f.id); put("name", f.name) } }))
        }) as JsonObject
        mergePeople(res["people"])
        val report = res["report"]?.let { wireJson.decodeFromJsonElement(MemberBug.serializer(), it) }
        val lines = (res["messages"] ?: res["message"]?.let { JsonArray(listOf(it)) })?.let { wireJson.decodeFromJsonElement(ListSerializer(BugMessage.serializer()), it) } ?: emptyList()
        _bugThreads.update { m ->
            val t = m[reportId] ?: return@update m
            val seen = t.messages.mapTo(HashSet()) { it.id }
            m + (reportId to BugThread(report ?: t.report, (t.messages + lines.filter { it.id !in seen }).sortedWith { a, b -> Ids.compare(a.id, b.id) }))
        }
        report?.let { bugIntoFeed(it, emptyList()) }
    }

    fun markBugRead(reportId: String) {
        val t = _bugThreads.value[reportId] ?: return
        if (t.report.unread <= 0) return
        val last = t.messages.lastOrNull() ?: return
        _bugThreads.update { it + (reportId to t.copy(report = t.report.copy(unread = 0))) }
        Nexa.scope.launch { runCatching { Nexa.api.request("POST", "/bugs/$reportId/read", buildJsonObject { put("message_id", last.id) }) } }
    }

    // -----------------------------------------------------------------------
    // gateway events

    /** Something new to tell the account about (a sound and a notification are the shell's to decide). */
    var onAlert: ((Alert) -> Unit)? = null

    sealed interface Alert {
        data class Notice(val case: MemberCase) : Alert
        data class Reply(val case: MemberCase) : Alert
        data class News(val announcement: Announcement) : Alert
        data class BugReply(val report: MemberBug) : Alert
        data class BugStatus(val report: MemberBug, val status: String) : Alert
        data class ReportReviewed(val report: ReportReceipt) : Alert
    }

    /**
     * report_update: a safety report this account sent moved. Its items in the
     * conversation follow (an outcome taken back, the report reopened, leaves),
     * and a new outcome is news.
     */
    fun onReport(r: ReportReceipt, before: ReportReceipt?) {
        _feed.value?.let { feed ->
            val items = mutableListOf<FeedItem>(FeedItem.Report(r.createdAt, r, outcome = false))
            if (r.outcomeAt > 0) items += FeedItem.Report(r.outcomeAt, r, outcome = true)
            val kept = feed.filter { !(it is FeedItem.Report && it.outcome && it.report.id == r.id) || r.outcomeAt > 0 }
            _feed.value = merged(kept, items)
        }
        if (r.outcomeAt > 0 && before?.outcomeAt != r.outcomeAt) onAlert?.invoke(Alert.ReportReviewed(r))
    }

    fun onEvent(type: String, d: JsonElement?) {
        val o = d as? JsonObject ?: run {
            if (type == "nexa_state") setState(d)
            return
        }
        try {
            when (type) {
                "nexa_state" -> setState(o)
                "nexa_notice" -> {
                    mergePeople(o["people"])
                    setState(o["state"])
                    val c = wireJson.decodeFromJsonElement(MemberCase.serializer(), o["case"]!!)
                    caseIntoFeed(c)
                    _threads.update { m -> m[c.id]?.let { m + (c.id to it.copy(case = c)) } ?: m }
                    if (c.action == "delete") deleted.value = c to _people.value
                    onAlert?.invoke(Alert.Notice(c))
                }
                "nexa_case" -> {
                    mergePeople(o["people"])
                    setState(o["state"])
                    val c = wireJson.decodeFromJsonElement(MemberCase.serializer(), o["case"]!!)
                    val lines = wireJson.decodeFromJsonElement(ListSerializer(CaseMessage.serializer()), o["messages"] ?: JsonArray(emptyList()))
                    caseIntoFeed(c)
                    _threads.update { m -> m[c.id]?.let { t -> m + (c.id to CaseThread(c, appendLines(t.messages, lines))) } ?: m }
                    if (lines.any { it.kind == "staff" }) onAlert?.invoke(Alert.Reply(c))
                }
                "nexa_announcement" -> {
                    mergePeople(o["people"])
                    val a = wireJson.decodeFromJsonElement(Announcement.serializer(), o["announcement"]!!)
                    _feed.value?.let { f -> _feed.value = merged(f, listOf(FeedItem.News(a.createdAt, a))) }
                    _standing.update { s -> s?.copy(unread = s.unread + 1, latest = maxOf(s.latest, a.createdAt)) }
                    onAlert?.invoke(Alert.News(a))
                }
                "nexa_announcement_delete" -> {
                    val id = (o["id"] as? JsonPrimitive)?.contentOrNull ?: return
                    _feed.update { f -> f?.filter { !(it is FeedItem.News && it.announcement.id == id) } }
                }
                "nexa_bug" -> {
                    mergePeople(o["people"])
                    setState(o["state"])
                    val report = wireJson.decodeFromJsonElement(MemberBug.serializer(), o["report"]!!)
                    val lines = wireJson.decodeFromJsonElement(ListSerializer(BugMessage.serializer()), o["messages"] ?: JsonArray(emptyList()))
                    bugIntoFeed(report, lines)
                    _bugThreads.update { m ->
                        val t = m[report.id] ?: return@update m
                        val seen = t.messages.mapTo(HashSet()) { it.id }
                        m + (report.id to BugThread(report, (t.messages + lines.filter { it.id !in seen }).sortedWith { a, b -> Ids.compare(a.id, b.id) }))
                    }
                    val staff = lines.firstOrNull { it.kind == "staff" }
                    val status = lines.firstOrNull { it.kind == "system" && it.event == "status" }
                    if (staff != null) onAlert?.invoke(Alert.BugReply(report))
                    else if (status != null) onAlert?.invoke(Alert.BugStatus(report, status.body))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "event $type", e)
        }
    }

    /** Forgets the account's Nexa state on sign-out; a deletion notice stays. */
    fun reset() {
        generation++
        Moderation.reset()
        Reports.reset()
        _standing.value = null
        _people.value = emptyMap()
        _feed.value = null
        feedMore.value = false
        feedLoading.value = false
        _threads.value = emptyMap()
        _bugThreads.value = emptyMap()
    }
}
