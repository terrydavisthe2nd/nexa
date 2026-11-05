package fish.nexa.data

import fish.nexa.Nexa
import fish.nexa.net.Message
import fish.nexa.net.wireJson
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.*

@Serializable
data class SafetyReport(
    val id: String,
    @SerialName("reporter_id") val reporterId: String = "0",
    @SerialName("target_kind") val targetKind: String = "other",
    @SerialName("target_id") val targetId: String = "0",
    @SerialName("subject_id") val subjectId: String = "0",
    @SerialName("server_id") val serverId: String = "0",
    val part: String = "whole",
    @SerialName("custom_part") val customPart: String = "",
    val reason: String = "other",
    val description: String = "",
    val excerpt: String = "",
    val snapshot: JsonObject = JsonObject(emptyMap()),
    val status: String = "open",
    @SerialName("assigned_to") val assignedTo: String = "0",
    val version: Int = 1,
    @SerialName("created_at") val createdAt: Long = 0,
    @SerialName("updated_at") val updatedAt: Long = 0,
    /** Open or in-review reports about the same item, this one included. */
    val duplicates: Int = 1,
    /** The distinct reasons of those reports, when there is more than one. */
    val reasons: List<String> = emptyList(),
    /** What the reporter was told on closing it, and when (0: not told). */
    val outcome: String = "",
    @SerialName("outcome_at") val outcomeAt: Long = 0,
)

/** What a reporter sees of their own report: never notes or who reviewed it. */
@Serializable
data class ReportReceipt(
    val id: String,
    @SerialName("target_kind") val targetKind: String = "other",
    @SerialName("target_id") val targetId: String = "0",
    /** The item's name when it was reported ("" for a message or something else). */
    @SerialName("target_label") val targetLabel: String = "",
    val reason: String = "other",
    val description: String = "",
    val status: String = "open",
    val outcome: String = "",
    @SerialName("outcome_at") val outcomeAt: Long = 0,
    @SerialName("created_at") val createdAt: Long = 0,
    @SerialName("updated_at") val updatedAt: Long = 0,
)

@Serializable
data class SafetyReportTarget(
    val kind: String,
    val id: String,
    @SerialName("subject_id") val subjectId: String = "0",
    val parts: List<String>,
    val reasons: List<String> = emptyList(),
    val snapshot: JsonObject = JsonObject(emptyMap()),
    /** This person's own report about the item that still waits on staff. */
    @SerialName("already_reported") val alreadyReported: ReportReceipt? = null,
)

/** Where the responsible account stands, beside a report. */
@Serializable
data class ReportSubject(
    val id: String,
    val username: String = "",
    @SerialName("display_name") val displayName: String = "",
    @SerialName("avatar_id") val avatarId: String? = null,
    @SerialName("case_count") val caseCount: Int = 0,
    @SerialName("alt_count") val altCount: Int = 0,
    val mute: JsonElement? = null,
    val ban: JsonElement? = null,
    val blocked: String = "",
)

@Serializable
data class SafetyReportEvent(
    val id: String,
    @SerialName("actor_id") val actorId: String,
    val status: String,
    @SerialName("assigned_to") val assignedTo: String = "0",
    val note: String = "",
    @SerialName("created_at") val createdAt: Long,
)

@Serializable
data class SafetyReportDetail(
    val report: SafetyReport,
    val events: List<SafetyReportEvent> = emptyList(),
    val files: List<NexaFile> = emptyList(),
    val people: Map<String, NexaPerson> = emptyMap(),
    val related: List<SafetyReport> = emptyList(),
    @SerialName("reporter_stats") val reporterStats: Map<String, Int> = emptyMap(),
    val subject: ReportSubject? = null,
)

@Serializable
data class SafetyReportList(
    val reports: List<SafetyReport> = emptyList(),
    val counts: Map<String, Int> = emptyMap(),
    @SerialName("has_more") val hasMore: Boolean = false,
    val people: Map<String, NexaPerson> = emptyMap(),
)

@Serializable
data class ReportReceiptPage(
    val reports: List<ReportReceipt> = emptyList(),
    @SerialName("has_more") val hasMore: Boolean = false,
)

/** How urgent a reason is: urgent ones (someone may be in danger) carry the danger colour. */
enum class ReasonTone { URGENT, SERIOUS, ROUTINE }

data class ReportEvidence(val part: String, val label: String, val text: String)

data class ReportContext(
    val kind: String? = null,
    val id: String? = null,
    val serverId: String? = null,
    val part: String? = null,
    val evidence: List<ReportEvidence> = emptyList(),
)

object Reports {
    val kinds =
        listOf(
            "user",
            "server",
            "channel",
            "message",
            "emoji",
            "sticker",
            "pack",
            "note",
            "application",
            "role",
            "other",
        )
    /** In the order the forms list them (the server's own order). */
    val reasons =
        listOf(
            "harassment",
            "hate",
            "violence",
            "self_harm",
            "sexual",
            "child_safety",
            "underage",
            "spam",
            "scam",
            "privacy",
            "ban_evasion",
            "illegal",
            "other",
        )
    val statuses = listOf("open", "in_review", "actioned", "dismissed")

    /** lib/reportReasons.ts: each reason's Lucide icon and how urgent it is. */
    private val looks =
        mapOf(
            "harassment" to ("MessageSquareWarning" to ReasonTone.SERIOUS),
            "hate" to ("HeartCrack" to ReasonTone.SERIOUS),
            "violence" to ("Swords" to ReasonTone.URGENT),
            "self_harm" to ("LifeBuoy" to ReasonTone.URGENT),
            "sexual" to ("CircleOff" to ReasonTone.SERIOUS),
            "child_safety" to ("ShieldAlert" to ReasonTone.URGENT),
            "underage" to ("Baby" to ReasonTone.SERIOUS),
            "spam" to ("Mails" to ReasonTone.ROUTINE),
            "scam" to ("VenetianMask" to ReasonTone.SERIOUS),
            "privacy" to ("Fingerprint" to ReasonTone.SERIOUS),
            "ban_evasion" to ("UserX" to ReasonTone.ROUTINE),
            "illegal" to ("Gavel" to ReasonTone.SERIOUS),
            "other" to ("CircleHelp" to ReasonTone.ROUTINE),
        )

    fun icon(reason: String) = looks[reason]?.first ?: "CircleHelp"

    fun tone(reason: String) = looks[reason]?.second ?: ReasonTone.ROUTINE

    fun closing(status: String) = status == "actioned" || status == "dismissed"

    /** The reason a queue row speaks with when it stands for several reports: the most pressing (lib/reportReasons.ts worstReason). */
    fun worst(reason: String, others: List<String>): String {
        fun rank(r: String) = when (tone(r)) { ReasonTone.URGENT -> 2; ReasonTone.SERIOUS -> 1; ReasonTone.ROUTINE -> 0 }
        var w = reason
        for (r in others) if (rank(r) > rank(w)) w = r
        return w
    }

    /** The standard words a reporter is told when staff leave the message empty. */
    fun outcomeText(r: ReportReceipt) =
        r.outcome.ifEmpty { fish.nexa.ui.t(if (r.status == "dismissed") "reports.outcome.dismissed" else "reports.outcome.actioned") }

    /** "Profile: maya", "Message". */
    fun subjectLine(r: ReportReceipt): String {
        val kind = fish.nexa.ui.t("reports.kind.${r.targetKind}")
        return if (r.targetLabel.isNotEmpty()) fish.nexa.ui.t("reports.yours.about", "kind" to kind, "name" to r.targetLabel) else kind
    }

    // ---- the reporter's receipts (Settings, Your Reports) ----

    private val _mine = kotlinx.coroutines.flow.MutableStateFlow<List<ReportReceipt>?>(null)
    val mine: kotlinx.coroutines.flow.StateFlow<List<ReportReceipt>?> = _mine
    val mineMore = kotlinx.coroutines.flow.MutableStateFlow(false)

    suspend fun loadMine(older: Boolean = false) {
        val before = if (older) _mine.value?.lastOrNull()?.id ?: "" else ""
        val page = Nexa.api.get("/reports" + query(mapOf("before" to before)), ReportReceiptPage.serializer())
        _mine.value = if (older) (_mine.value ?: emptyList()) + page.reports.filter { r -> _mine.value?.none { it.id == r.id } != false } else page.reports
        mineMore.value = page.hasMore
    }

    /** report_update: a report this account sent moved (sent from any device, or decided). */
    fun onReceipt(r: ReportReceipt) {
        val list = _mine.value ?: return
        _mine.value = if (list.any { it.id == r.id }) list.map { if (it.id == r.id) r else it } else listOf(r) + list
    }

    fun reset() {
        _mine.value = null
        mineMore.value = false
        counts.value = null
    }

    // ---- staff ----

    /** How many safety reports hold each status: the Reports tab's badge. */
    val counts = kotlinx.coroutines.flow.MutableStateFlow<Map<String, Int>?>(null)

    /** mod_report: new or changed reports, for open queues and report pages. */
    private val _changes = kotlinx.coroutines.flow.MutableSharedFlow<Pair<List<SafetyReport>, Map<String, NexaPerson>>>(extraBufferCapacity = 32)
    val changes: kotlinx.coroutines.flow.SharedFlow<Pair<List<SafetyReport>, Map<String, NexaPerson>>> = _changes

    /** A report someone just sent, for staff notifications (notify/StaffAlerts.kt). */
    var onNewReport: ((SafetyReport, Map<String, NexaPerson>) -> Unit)? = null

    fun onStaffEvent(d: JsonElement?) {
        val o = d as? JsonObject ?: return
        runCatching {
            o["counts"]?.let { counts.value = wireJson.decodeFromJsonElement(kotlinx.serialization.builtins.MapSerializer(String.serializer(), Int.serializer()), it) }
            val list = wireJson.decodeFromJsonElement(kotlinx.serialization.builtins.ListSerializer(SafetyReport.serializer()), o["reports"] ?: JsonArray(emptyList()))
            val people = wireJson.decodeFromJsonElement(kotlinx.serialization.builtins.MapSerializer(String.serializer(), NexaPerson.serializer()), o["people"] ?: JsonObject(emptyMap()))
            NexaInbox.mergePeople(people)
            _changes.tryEmit(list to people)
            if ((o["new"] as? JsonPrimitive)?.booleanOrNull == true) list.firstOrNull()?.let { onNewReport?.invoke(it, people) }
        }
    }

    private fun query(values: Map<String, String>) =
        values.entries
            .filter { it.value.isNotEmpty() }
            .joinToString("&", "?") { (k, v) -> "$k=" + java.net.URLEncoder.encode(v, "UTF-8") }

    suspend fun target(kind: String, id: String, server: String?) =
        Nexa.api.get(
            "/reports/target" +
                query(mapOf("kind" to kind, "id" to id, "server_id" to (server ?: ""))),
            SafetyReportTarget.serializer(),
        )

    suspend fun send(body: JsonObject): ReportReceipt {
        val r = wireJson.decodeFromJsonElement(ReportReceipt.serializer(), Nexa.api.request("POST", "/reports", body).jsonObject["report"]!!)
        onReceipt(r)
        return r
    }

    suspend fun list(
        status: String,
        kind: String,
        reason: String,
        assigned: String,
        sort: String,
        search: String,
        cursor: String = "",
        /** "item" lists each reported thing once, the newest report about it speaking for it. */
        group: String = "item",
    ): SafetyReportList {
        val r =
            Nexa.api.get(
                "/moderation/reports" +
                    query(
                        mapOf(
                            "status" to status,
                            "kind" to kind,
                            "reason" to reason,
                            "assigned" to assigned,
                            "sort" to sort,
                            "q" to search,
                            "group" to group,
                            (if (sort == "oldest") "after" else "before") to cursor,
                        )
                    ),
                SafetyReportList.serializer(),
            )
        counts.value = r.counts
        return r
    }

    suspend fun detail(id: String) =
        Nexa.api.get("/moderation/reports/$id", SafetyReportDetail.serializer())

    suspend fun review(
        v: SafetyReport,
        status: String,
        note: String,
        assigned: String,
        told: String = "",
        related: Boolean = false,
    ): SafetyReportDetail =
        Nexa.api.request(
            "PATCH",
            "/moderation/reports/${v.id}",
            SafetyReportDetail.serializer(),
            buildJsonObject {
                put("version", v.version)
                put("status", status)
                put("note", note)
                put("assigned_to", assigned)
                if (closing(status)) {
                    put("reporter_message", told)
                    put("resolve_related", related)
                }
            },
        )

    suspend fun removeMessage(v: SafetyReport, reason: String, told: String) {
        Nexa.api.request(
            "POST",
            "/moderation/reports/${v.id}/remove-message",
            buildJsonObject {
                put("version", v.version)
                put("reason", reason)
                put("reporter_message", told)
            },
        )
    }

    /** Complete readable message and per-file keys only; never conversation keys. */
    suspend fun fullMessage(target: SafetyReportTarget, preview: (ChatMessage) -> Unit = {}): JsonObject {
        val channel = target.snapshot["channel_id"]!!.jsonPrimitive.content
        val raw = Nexa.api.get("/channels/$channel/messages/${target.id}", Message.serializer())
        val message = Nexa.messages.decrypt(raw)
        if (
            message.error != null ||
                (message.payload == null &&
                    message.hook == null &&
                    message.call == null &&
                    !raw.type.startsWith("system_"))
        )
            error(fish.nexa.ui.t("reports.messageUnavailable"))
        val files = buildList {
            message.payload?.att?.forEach { a ->
                add(
                    buildJsonObject {
                        put("blob_id", a.blobId)
                        put("name", a.name)
                        put("type", a.type)
                        put("size", a.size)
                        put("key", a.key)
                    }
                )
                a.preview?.let { p ->
                    add(
                        buildJsonObject {
                            put("blob_id", p.blobId)
                            put("name", a.name)
                            put("type", p.type)
                            put("size", p.size)
                            put("key", p.key)
                            put("preview", true)
                        }
                    )
                }
            }
        }
        if (
            raw.attachments.any { a ->
                files.none { it["blob_id"]?.jsonPrimitive?.content == a.blobId }
            }
        )
            error(fish.nexa.ui.t("reports.messageUnavailable"))
        fun readable(value: JsonElement): JsonElement =
            when (value) {
                is JsonObject ->
                    JsonObject(value.filterKeys { it != "key" }.mapValues { readable(it.value) })
                is JsonArray -> JsonArray(value.map { readable(it) })
                else -> value
            }
        preview(message)
        val content = buildJsonObject {
            message.payload?.toJson()?.forEach { (key, value) -> put(key, readable(value)) }
            put("type", raw.type)
            message.hook?.let { put("hook", it) }
            message.call?.let { put("call", it) }
        }
        return buildJsonObject {
            put("content", content)
            put("attachments", JsonArray(files))
            put("edited_at", raw.editedAt?.let { JsonPrimitive(it) } ?: JsonNull)
        }
    }

    fun evidence(message: ChatMessage): List<ReportEvidence> {
        val p = message.payload ?: return emptyList()
        return buildList {
            fun addPart(
                part: String,
                text: String,
                label: String = fish.nexa.ui.t("reports.part.$part"),
            ) {
                if (text.isNotBlank()) add(ReportEvidence(part, label, text))
            }
            addPart("text", listOfNotNull(p.fwdComment, p.t).joinToString("\n"))
            p.att?.forEach { addPart("attachment", it.name, it.name) }
            p.sticker?.let { addPart("sticker", "${it.name ?: ""} (${it.id})") }
            p.embeds?.forEach { e ->
                addPart(
                    "embed",
                    listOf("title", "description", "url")
                        .mapNotNull { (e[it] as? JsonPrimitive)?.contentOrNull }
                        .joinToString("\n"),
                )
            }
            p.poll?.let {
                addPart("poll", it.question + "\n" + it.answers.joinToString("\n") { a -> a.text })
            }
        }
    }
}
