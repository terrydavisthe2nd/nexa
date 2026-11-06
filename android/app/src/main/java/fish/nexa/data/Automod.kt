package fish.nexa.data

import fish.nexa.Nexa
import fish.nexa.crypto.Aad
import fish.nexa.crypto.Conv
import fish.nexa.crypto.Envelope
import fish.nexa.crypto.MessageCrypto
import fish.nexa.crypto.MessagePayload
import fish.nexa.net.Channel
import fish.nexa.net.Snowflake
import fish.nexa.net.wireJson
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.util.concurrent.ConcurrentHashMap
import kotlin.random.Random

@Serializable
data class AutomodAction(
    val type: String,
    val message: String? = null,
    @SerialName("channel_id") val channelId: Snowflake? = null,
    val seconds: Int? = null,
    val after: Int? = null,
)

@Serializable
data class AutomodConfig(
    val lists: List<String>? = null,
    val words: List<String>? = null,
    val regex: List<String>? = null,
    val allow: List<String>? = null,
    val evasion: Boolean? = null,
    val mode: String? = null,
    val domains: List<String>? = null,
    val scams: Boolean? = null,
    val repeats: Boolean? = null,
    @SerialName("shady_links") val shadyLinks: Boolean? = null,
    val zalgo: Boolean? = null,
    val walls: Boolean? = null,
    val emails: Boolean? = null,
    val phones: Boolean? = null,
    val ips: Boolean? = null,
    val cards: Boolean? = null,
    val percent: Int? = null,
    @SerialName("min_length") val minLength: Int? = null,
    val max: Int? = null,
    val raid: Boolean? = null,
    val messages: Int? = null,
    val seconds: Int? = null,
    val extensions: List<String>? = null,
    @SerialName("allow_own") val allowOwn: Boolean? = null,
    val names: Boolean? = null,
    val bio: Boolean? = null,
)

/** One AutoMod rule (web AutomodRule). */
@Serializable
data class AutomodRule(
    val id: Snowflake = "",
    @SerialName("server_id") val serverId: Snowflake = "",
    val name: String = "",
    val kind: String = "",
    val enabled: Boolean = true,
    val config: AutomodConfig = AutomodConfig(),
    val actions: List<AutomodAction> = emptyList(),
    @SerialName("exempt_roles") val exemptRoles: List<Snowflake> = emptyList(),
    @SerialName("exempt_channels") val exemptChannels: List<Snowflake> = emptyList(),
    @SerialName("creator_id") val creatorId: Snowflake = "",
    @SerialName("created_at") val createdAt: Long = 0,
    @SerialName("updated_at") val updatedAt: Long = 0,
)

/** The ready-made lists and spam signs every app applies alike. */
@Serializable
data class AutomodPresets(
    val lists: Map<String, List<String>> = emptyMap(),
    @SerialName("spam_phrases") val spamPhrases: List<String> = emptyList(),
    val shorteners: List<String> = emptyList(),
    val impersonated: Map<String, List<String>> = emptyMap(),
    @SerialName("risky_extensions") val riskyExtensions: List<String> = emptyList(),
)

/** One time a rule acted, for the log. */
@Serializable
data class AutomodEvent(
    val id: Snowflake,
    @SerialName("server_id") val serverId: Snowflake = "",
    @SerialName("rule_id") val ruleId: Snowflake? = null,
    @SerialName("rule_name") val ruleName: String = "",
    @SerialName("rule_kind") val ruleKind: String = "",
    @SerialName("user_id") val userId: Snowflake = "",
    @SerialName("channel_id") val channelId: Snowflake? = null,
    val actions: List<String> = emptyList(),
    val content: JsonElement? = null,
    @SerialName("reporter_id") val reporterId: Snowflake? = null,
    @SerialName("created_at") val createdAt: Long = 0,
)

/**
 * AutoMod in this app (web/src/lib/automod/rules.ts). Messages are
 * end-to-end encrypted, so the server cannot read them: the rules that read
 * what a message says are applied by members' apps. The sender's app stops a
 * message before it goes (and tells its sender why), everyone's app keeps
 * hidden what got past anyway, and a moderator's app clears it away. The
 * server applies the rest itself (mentions, flooding, names and profiles,
 * thread and post titles).
 */
object Automod {
    /** The kinds members' apps apply. */
    private val CLIENT_KINDS = setOf("flagged_words", "words", "spam", "links", "invites", "personal", "caps", "emoji_spam", "attachments")

    private class Loaded(val rules: List<AutomodRule>, val stamp: Long)

    private val loaded = ConcurrentHashMap<String, Loaded>()
    private val loading = ConcurrentHashMap<String, Deferred<Unit>>()
    @Volatile private var presets: AutomodPresets? = null
    private var presetsJob: Deferred<Unit>? = null

    private val _version = MutableStateFlow(0)
    /** Bumped whenever the rules change, so what was worked out from the old ones is worked out again. */
    val version: StateFlow<Int> = _version

    private fun changed() {
        verdicts.clear()
        _version.value = _version.value + 1
    }

    /** The server's mark of when its rules last changed: 0 when it has none. */
    private fun stampOf(serverId: String): Long = Nexa.servers.get(serverId)?.server?.automodAt ?: 0L

    /** The rules of a server changed (or it was left): fetched again if it has any. */
    fun forget(serverId: String) {
        loaded.remove(serverId)
        if (stampOf(serverId) > 0) load(serverId)
        changed()
    }

    /** Fetches the rules of the servers that have any, one after another, after connecting. */
    fun warm(serverIds: List<String>) {
        Nexa.scope.launch {
            for (id in serverIds) {
                val l = loaded[id]
                if (l != null && l.stamp == stampOf(id)) continue
                load(id).await()
            }
        }
    }

    /** Signing out: nothing about the account's servers stays. */
    fun clear() {
        loaded.clear()
        loading.clear()
        synchronized(recent) { recent.clear() }
        arrived.clear()
        reported.clear()
        changed()
    }

    /** Fetches a server's rules (and the ready-made lists, when a rule uses them). */
    fun load(serverId: String): Deferred<Unit> {
        loading[serverId]?.let { return it }
        val done = CompletableDeferred<Unit>()
        if (loading.putIfAbsent(serverId, done) != null) return loading[serverId] ?: done
        val stamp = stampOf(serverId)
        Nexa.scope.launch {
            try {
                val rules = Nexa.api.get("/servers/$serverId/automod", ListSerializer(AutomodRule.serializer()))
                val kept = rules.filter { it.enabled && it.kind in CLIENT_KINDS }
                if (kept.any { it.kind == "flagged_words" || it.kind == "spam" }) loadPresets().await()
                loaded[serverId] = Loaded(kept, stamp)
                changed()
            } catch (_: Exception) {
            } finally {
                loading.remove(serverId)
                done.complete(Unit)
            }
        }
        return done
    }

    private fun loadPresets(): Deferred<Unit> {
        if (presets != null) return CompletableDeferred(Unit)
        synchronized(this) {
            presetsJob?.let { return it }
            val done = CompletableDeferred<Unit>()
            presetsJob = done
            Nexa.scope.launch {
                try {
                    presets = Nexa.api.get("/automod/presets", AutomodPresets.serializer())
                } catch (_: Exception) {
                } finally {
                    synchronized(this@Automod) { presetsJob = null }
                    done.complete(Unit)
                }
            }
            return done
        }
    }

    /** The ready-made lists, fetched once and kept. */
    suspend fun presets(): AutomodPresets? {
        loadPresets().await()
        return presets
    }

    /** A server's rules as this app holds them; null until they are loaded (which this starts). */
    fun rulesNow(serverId: String): List<AutomodRule>? {
        val stamp = stampOf(serverId)
        if (stamp == 0L) return emptyList()
        val l = loaded[serverId]
        if (l == null || l.stamp != stamp) load(serverId)
        return l?.rules
    }

    /** Whether a server's rules are here to be applied (a server without any counts as here). */
    private fun rulesHere(serverId: String) = stampOf(serverId) == 0L || loaded.containsKey(serverId)

    /** Waits (briefly) for a server's rules, so the first message sent after opening the app is checked like every other. */
    suspend fun ready(serverId: String, waitMs: Long = 2500) {
        val l = loaded[serverId]
        val stamp = stampOf(serverId)
        if (stamp == 0L || (l != null && l.stamp == stamp)) return
        val job = load(serverId)
        withTimeoutOrNull(waitMs) { job.await() }
    }

    private val matchers = ConcurrentHashMap<String, AutomodMatch.Matcher>()

    private fun matcherOf(rule: AutomodRule): AutomodMatch.Matcher {
        val key = "${rule.id}:${rule.updatedAt}:${if (presets != null) 1 else 0}"
        return matchers.getOrPut(key) {
            if (matchers.size > 200) matchers.clear()
            val words = ArrayList(rule.config.words ?: emptyList())
            for (name in rule.config.lists ?: emptyList()) words += presets?.lists?.get(name) ?: emptyList()
            AutomodMatch.Matcher(words, rule.config.regex ?: emptyList(), rule.config.allow ?: emptyList(), rule.config.evasion != false)
        }
    }

    /** What one rule caught, and what it does about it. */
    class Catch(val rule: AutomodRule, val matched: String, val blocks: Boolean, val message: String)

    /** What a message is made of, as AutoMod reads it. */
    class Readable(val text: String, val files: List<String>)

    /** The words of a message (with a poll's question and answers) and its files' names. */
    fun readable(p: MessagePayload?): Readable {
        if (p == null) return Readable("", emptyList())
        // a picker GIF's text is its signed path, which nobody reads and whose
        // digits and letters only look like phone numbers and words
        val parts = arrayListOf(p.fwdComment ?: "", (p.t ?: "").takeUnless { fish.nexa.media.isBareGifPath(it) } ?: "")
        p.poll?.let { poll ->
            parts += poll.question
            for (a in poll.answers) parts += a.text
        }
        return Readable(parts.filter { it.isNotEmpty() }.joinToString("\n"), p.att?.map { it.name } ?: emptyList())
    }

    /** Whether a member is known well enough here to say which rules hold them. */
    private fun known(st: ServerState, userId: String) = st.server.ownerId == userId || st.members.containsKey(userId)

    /** Anyone who may manage the server is never held by AutoMod. */
    private fun exemptPerson(st: ServerState, userId: String): Boolean {
        val p = Nexa.servers.serverPerms(st, userId)
        return P.has(p, P.MANAGE_SERVER) || P.has(p, P.ADMINISTRATOR)
    }

    private fun exemptHere(st: ServerState, rule: AutomodRule, userId: String, channel: Channel): Boolean {
        // a thread is left alone where its channel is
        if (channel.id in rule.exemptChannels || (channel.parentId != null && channel.parentId in rule.exemptChannels)) return true
        val roles = st.members[userId]?.roleIds ?: emptyList()
        return roles.any { it in rule.exemptRoles }
    }

    enum class Mode { SEND, ARRIVE, VIEW }

    /** The messages people sent lately, by server and person, for spotting the same one over and over. */
    private val recent = LinkedHashMap<String, MutableList<Pair<String, Long>>>()
    private val SPACES = Regex("\\s+")

    private fun repeated(serverId: String, userId: String, text: String, now: Long): Boolean {
        val norm = text.trim().lowercase().replace(SPACES, " ")
        if (norm.length < 4) return false
        synchronized(recent) {
            val key = "$serverId:$userId"
            val list = (recent[key] ?: mutableListOf()).filter { now - it.second < 60_000 }.toMutableList()
            val same = list.count { it.first == norm }
            list += norm to now
            recent[key] = list.takeLast(20).toMutableList()
            if (recent.size > 2000) recent.remove(recent.keys.first())
            // the third time in a minute
            return same >= 2
        }
    }

    private fun evaluate(rule: AutomodRule, serverId: String, authorId: String, msg: Readable, now: Long, mode: Mode): String? {
        val c = rule.config
        val text = msg.text
        return when (rule.kind) {
            "words" -> matcherOf(rule).match(text)
            "flagged_words" -> if (presets != null || (c.words?.size ?: 0) > 0) matcherOf(rule).match(text) else null
            "links" -> AutomodChecks.checkLinks(text, c)
            "invites" -> AutomodChecks.checkInvites(text)
            "personal" -> AutomodChecks.checkPersonal(text, c)
            "caps" -> AutomodChecks.checkCaps(text, c)
            "emoji_spam" -> AutomodChecks.checkEmoji(text, c)
            "attachments" -> AutomodChecks.checkAttachments(msg.files, c)
            "spam" -> {
                // repeats are counted as messages go and come, never over history
                if (c.repeats == true && mode != Mode.VIEW && repeated(serverId, authorId, text, now)) return "repeat"
                if (c.zalgo == true) AutomodChecks.zalgo(text)?.let { return it }
                if (c.walls == true) AutomodChecks.wall(text)?.let { return it }
                val pre = presets ?: return null
                if (c.scams == true) AutomodChecks.scam(text, pre)?.let { return it }
                if (c.shadyLinks == true) AutomodChecks.shadyLink(AutomodChecks.linksIn(text), pre) else null
            }
            else -> null
        }
    }

    /**
     * What a rule as it stands in the editor (saved or not) would catch in a
     * message, with no one exempt: for its Try It box. Repeats are not tried.
     */
    fun tryRule(rule: AutomodRule, text: String, files: List<String> = emptyList()): String? {
        val c = rule.config
        if (rule.kind == "words" || rule.kind == "flagged_words" || rule.kind == "profile") {
            val words = ArrayList(c.words ?: emptyList())
            for (name in c.lists ?: emptyList()) words += presets?.lists?.get(name) ?: emptyList()
            return AutomodMatch.Matcher(words, c.regex ?: emptyList(), c.allow ?: emptyList(), c.evasion != false).match(text)
        }
        val r = if (rule.kind == "spam") rule.copy(config = c.copy(repeats = false)) else rule
        return evaluate(r, "", "", Readable(text, files), System.currentTimeMillis(), Mode.VIEW)
    }

    /**
     * What a server's rules catch in a message by authorId in a channel. A
     * rule never reaches back past when it was last changed (writtenAt). An
     * empty list when nothing does, or when the rules or the author are not
     * known yet.
     */
    fun check(channel: Channel, authorId: String, msg: Readable, mode: Mode, writtenAt: Long? = null): List<Catch> {
        val serverId = channel.serverId ?: return emptyList()
        val rules = rulesNow(serverId)
        val st = Nexa.servers.get(serverId)
        if (rules == null || rules.isEmpty() || st == null || !known(st, authorId) || exemptPerson(st, authorId)) return emptyList()
        val now = System.currentTimeMillis()
        val out = ArrayList<Catch>()
        for (rule in rules) {
            if (writtenAt != null && writtenAt < rule.updatedAt) continue
            if (exemptHere(st, rule, authorId, channel)) continue
            val hit = evaluate(rule, serverId, authorId, msg, now, mode) ?: continue
            val block = rule.actions.firstOrNull { it.type == "block" }
            out += Catch(rule, hit, block != null, block?.message ?: "")
        }
        return out
    }

    /** What blocked a message, worked out once per message and version of the rules. */
    private val verdicts = ConcurrentHashMap<String, Any>()
    private object NotHeld

    /** What a moderator's app caught as messages arrived (repeats are only seen then). */
    private val arrived = ConcurrentHashMap<String, Catch>()

    /** Messages AutoMod reads: what members wrote, not notes, webhooks, apps or calls. */
    private fun readsMessage(cm: ChatMessage) = !cm.m.type.startsWith("system_") && cm.m.webhookId == null && cm.hook == null && cm.payload != null

    private fun serverChannel(channelId: String): Channel? = Nexa.channels.get(channelId)?.takeIf { it.serverId != null }

    /** The rule that keeps a message someone else sent hidden, or null: shown as held by AutoMod rather than as written. */
    fun heldBy(cm: ChatMessage, meId: String?): Catch? {
        if (cm.authorId == meId || !readsMessage(cm)) return null
        arrived[cm.id]?.let { return it }
        val key = "${_version.value}:${cm.id}:${cm.m.editedAt ?: 0}"
        verdicts[key]?.let { return it as? Catch }
        val ch = serverChannel(cm.channelId) ?: return null
        val sid = ch.serverId ?: return null
        val st = Nexa.servers.get(sid) ?: return null
        // without the rules or the author's roles, not yet: asked again once they are here
        if (!rulesHere(sid) || !known(st, cm.authorId)) return null
        val found = check(ch, cm.authorId, readable(cm.payload), Mode.VIEW, cm.m.createdAt).firstOrNull { it.blocks }
        verdicts[key] = found ?: NotHeld
        if (verdicts.size > 3000) verdicts.clear()
        return found
    }

    /** The server's key AutoMod seals what it caught with, which every member's app holds. */
    private fun seal(serverId: String, offenderId: String, text: String): Envelope? {
        val (epoch, root) = Nexa.keys.ring.latest(serverId) ?: return null
        val key = Conv.channelKey(root, serverId)
        return MessageCrypto.encrypt(key, Aad(serverId, epoch, serverId, offenderId), MessagePayload(t = text.take(4000)))
    }

    /** Opens what AutoMod caught, for a moderator reading an alert or the log; null when it cannot be read here. */
    fun openCaught(serverId: String, offenderId: String, content: JsonElement?): String? {
        val env = Envelope.from(content) ?: return null
        val key = Nexa.keys.channelKey(env.conv, env.epoch, serverId) ?: return null
        return try {
            MessageCrypto.decrypt(key, env, Aad(env.conv, env.epoch, serverId, offenderId)).t ?: ""
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Tells the server what a rule caught: this account's own message
     * (stopped before it went, or sent, messageId, when the rule lets it
     * through), or someone else's that got past, which a moderator's app
     * clears away. What was caught goes sealed with the server's key.
     */
    suspend fun report(channel: Channel, c: Catch, offenderId: String, text: String, messageId: String? = null): List<String> {
        val serverId = channel.serverId ?: return emptyList()
        return try {
            val content = seal(serverId, offenderId, text)
            val body = buildJsonObject {
                put("rule_id", c.rule.id)
                put("channel_id", channel.id)
                if (messageId != null) put("message_id", messageId)
                if (content != null) put("content", content.toJson())
            }
            val res = Nexa.api.request("POST", "/servers/$serverId/automod/report", body) as? JsonObject
            (res?.get("actions") as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** Messages a moderator's app has already reported. */
    private val reported: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /**
     * A message that just arrived. Repeats are counted, and in a moderator's
     * app what a rule catches is reported: cleared away when the rule stops
     * such messages, written down and alerted when it only watches for them.
     * After a moment, so the sender's own app (and other moderators') can go
     * first.
     */
    fun onArrive(cm: ChatMessage, meId: String?) {
        if (meId == null || cm.authorId == meId || !readsMessage(cm)) return
        // only what arrives as it is sent, never history coming back
        if (Math.abs(System.currentTimeMillis() - cm.m.createdAt) > 60_000) return
        val ch = serverChannel(cm.channelId) ?: return
        val sid = ch.serverId ?: return
        val st = Nexa.servers.get(sid) ?: return
        if (stampOf(sid) == 0L || !loaded.containsKey(sid)) return
        val text = readable(cm.payload)
        val caught = check(ch, cm.authorId, text, Mode.ARRIVE, cm.m.createdAt)
        caught.firstOrNull { it.blocks }?.let {
            arrived[cm.id] = it
            if (arrived.size > 500) arrived.clear()
            changed()
        }
        if (caught.isEmpty() || !P.has(Nexa.servers.channelPerms(st, ch.id, meId), P.MANAGE_MESSAGES)) return
        val (mine, mineOwner) = Nexa.servers.memberHighest(st, meId)
        val (theirs, theirsOwner) = Nexa.servers.memberHighest(st, cm.authorId)
        if (!Perms.canActOn(mine, theirs, mineOwner, theirsOwner)) return
        for (c in caught) {
            val key = "${c.rule.id}:${cm.id}"
            if (!reported.add(key)) continue
            if (reported.size > 2000) reported.clear()
            // a rule that blocks clears it away soon; one that lets it through waits for the sender's own report
            val wait = if (c.blocks) 400L + Random.nextLong(1600) else 5000L + Random.nextLong(5000)
            Nexa.scope.launch {
                delay(wait)
                if (Nexa.channels.get(ch.id) == null) return@launch
                report(ch, c, cm.authorId, text.text, cm.id)
            }
        }
    }

    /** What screening an outgoing message decided. */
    class Screen(val stopped: Catch?, val catches: List<Catch>, val afterSend: (String) -> Unit)

    /**
     * Checks a message this account is about to send. Returns what stops it,
     * if anything; reports every rule that caught it (what stopped it now, and
     * what lets it through once it has been sent, through afterSend). Null
     * when the rules are not here yet and onlyIfLoaded asks not to wait.
     */
    fun screenOutgoing(channel: Channel, meId: String, payload: MessagePayload, onlyIfLoaded: Boolean = false): Screen? {
        val serverId = channel.serverId
        if (onlyIfLoaded && serverId != null && !rulesHere(serverId)) return null
        val msg = readable(payload)
        val catches = check(channel, meId, msg, Mode.SEND)
        val blocking = catches.filter { it.blocks }
        if (blocking.isNotEmpty()) {
            // every rule that caught it acts, as the server would have had it read the message
            for (c in catches) Nexa.scope.launch { report(channel, c, meId, msg.text) }
            return Screen(blocking.first(), catches) {}
        }
        return Screen(null, catches) { messageId -> for (c in catches) Nexa.scope.launch { report(channel, c, meId, msg.text, messageId) } }
    }

    /** Every rule, for the people who write them. */
    suspend fun allRules(serverId: String): List<AutomodRule> = Nexa.api.get("/servers/$serverId/automod/rules", ListSerializer(AutomodRule.serializer()))

    fun ruleJson(rule: AutomodRule): JsonElement = wireJson.encodeToJsonElement(AutomodRule.serializer(), rule)
}
