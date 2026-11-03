package fish.nexa.data

import androidx.compose.runtime.mutableStateMapOf
import fish.nexa.Nexa
import fish.nexa.crypto.Bytes
import fish.nexa.crypto.Keys
import fish.nexa.net.ApiError
import fish.nexa.net.Channel
import fish.nexa.net.PublicUser
import fish.nexa.net.Relationship
import fish.nexa.net.wireJson
import fish.nexa.ui.describeError
import fish.nexa.ui.t
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/** lib/requestNote.ts: a short note sent with a friend request, sealed to the person it asks. */
object RequestNote {
    const val MAX = 200

    fun clean(text: String): String {
        val note = text.trim()
        if (note.length <= MAX) return note
        var end = MAX
        if (Character.isHighSurrogate(note[end - 1])) end -= 1
        return note.substring(0, end).trimEnd()
    }

    fun seal(recipientPub: ByteArray, note: String): String = Bytes.b64(Keys.seal(recipientPub, Bytes.utf8(note)))

    /** The note as its recipient reads it, or null when it cannot be opened (sealed to another key). */
    fun open(sealedB64: String?): String? {
        if (sealedB64.isNullOrEmpty()) return null
        val id = Nexa.keys.identity ?: return null
        return try {
            val bytes = Keys.open(id.privateKey, id.publicKey, Bytes.unb64(sealedB64))
            val text = java.nio.charset.StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes)).toString().trim()
            text.ifEmpty { null }
        } catch (_: Exception) {
            null
        }
    }
}

/**
 * lib/friendship.ts: what one does about another person (ask to be friends,
 * accept, cancel, unfriend, block, unblock), with the same words when it goes
 * wrong, and one thing at a time per person.
 */
object People {
    /** People with a change under way, so their buttons wait. */
    val busy = mutableStateMapOf<String, Boolean>()

    fun isBusy(userId: String) = busy[userId] == true

    private suspend fun <T> busyWith(userId: String, job: suspend () -> T): T? {
        if (busy[userId] == true) return null
        busy[userId] = true
        try {
            return job()
        } finally {
            busy.remove(userId)
        }
    }

    private fun setRel(user: PublicUser, type: String) {
        val had = Nexa.relationships.byId.value[user.id]
        Nexa.relationships.set(
            Relationship(
                user = user,
                type = type,
                nickname = if (type == "friend") had?.nickname ?: "" else "",
                createdAt = if (had?.type == type) had.createdAt else System.currentTimeMillis(),
            ),
        )
    }

    private fun toast(text: String, kind: String = "error") = Nexa.ui.toast(text, kind)

    fun requestError(e: Throwable): String = when ((e as? ApiError)?.code) {
        "user_not_found" -> t("friends.hmDidnTWorkDoubleCheck")
        "self" -> t("friends.youCanTAddYourselfBut")
        "blocked" -> t("friends.youCanTSendARequest")
        "already_friends" -> t("friends.youReAlreadyFriendsWithThat")
        "already_sent" -> t("friends.youAlreadySentARequestTo")
        else -> describeError(e, t("friends.thatFriendRequestCouldNotBe"))
    }

    private suspend fun request(username: String): Pair<String, PublicUser> {
        val res = Nexa.auth.guarded("friend_request") { captcha ->
            Nexa.api.request("POST", "/users/@me/relationships", buildJsonObject { put("username", username) }, captcha = captcha)
        } as JsonObject
        val user = wireJson.decodeFromJsonElement(PublicUser.serializer(), res["user"]!!)
        val type = if ((res["type"] as? JsonPrimitive)?.content == "friend") "friend" else "outgoing"
        setRel(user, type)
        return type to user
    }

    /** The Add Friend form: by username; throws so the form can say what went wrong under the field. */
    suspend fun requestByName(username: String): Pair<String, PublicUser> = request(username.trim().removePrefix("@").lowercase())

    /** "Add Friend" from a profile or a menu: the request, its outcome as a toast when it fails. */
    suspend fun sendRequest(user: PublicUser): String? = busyWith(user.id) {
        try {
            request(user.username).first
        } catch (e: ApiError) {
            when (e.code) {
                "already_sent" -> setRel(user, "outgoing").let { "outgoing" }
                "already_friends" -> setRel(user, "friend").let { "friend" }
                else -> {
                    toast(requestError(e)); null
                }
            }
        } catch (e: Exception) {
            toast(requestError(e)); null
        }
    }

    /** Seals the note to their key and attaches it to the request just sent. */
    suspend fun addRequestNote(user: PublicUser, note: String): Boolean = try {
        val key = Nexa.keys.sealingKeyOf(user.id) ?: throw IllegalStateException("their identity key changed")
        Nexa.api.request("PUT", "/users/@me/relationships/${user.id}/note", buildJsonObject { put("note", RequestNote.seal(key, note)) })
        true
    } catch (e: Exception) {
        toast(t("friends.noteNotAdded"))
        false
    }

    /** Accepts their request; the DM it opens comes back with it. */
    suspend fun accept(user: PublicUser): Channel? = busyWith(user.id) {
        try {
            val res = Nexa.api.request("PUT", "/users/@me/relationships/${user.id}") as JsonObject
            val u = wireJson.decodeFromJsonElement(PublicUser.serializer(), res["user"]!!)
            setRel(u, "friend")
            val ch = res["channel"]?.let { runCatching { wireJson.decodeFromJsonElement(Channel.serializer(), it) }.getOrNull() }
            if (ch != null) Nexa.channels.upsert(listOf(ch))
            ch
        } catch (e: Exception) {
            if (e is ApiError && e.code == "no_request" && Nexa.relationships.typeOf(user.id) == "incoming") Nexa.relationships.remove(user.id)
            toast(describeError(e, t("profile.profileCard.couldNotAccept")))
            null
        }
    }

    /** Cancels an outgoing request or ignores an incoming one. */
    suspend fun dropRequest(userId: String): Boolean {
        val was = Nexa.relationships.typeOf(userId)
        return busyWith(userId) {
            try {
                Nexa.api.request("DELETE", "/users/@me/relationships/$userId")
            } catch (e: Exception) {
                if (!(e is ApiError && e.status == 404)) {
                    toast(describeError(e, t("friends.couldNotUpdate")))
                    return@busyWith false
                }
            }
            Nexa.relationships.remove(userId)
            toast(if (was == "outgoing") t("friends.requestCancelled") else t("friends.requestIgnored"), "success")
            true
        } ?: false
    }

    suspend fun removeFriend(userId: String) {
        Nexa.api.request("DELETE", "/users/@me/relationships/$userId")
        Nexa.relationships.remove(userId)
        toast(t("friends.friendRemoved"), "success")
    }

    /** Blocks them, and keeps the two choices about their messages as the account's blocking settings. */
    suspend fun block(user: PublicUser, hideMessages: Boolean, hideReplies: Boolean) {
        Nexa.api.request("PUT", "/users/@me/blocks/${user.id}")
        setRel(user, "blocked")
        if (Nexa.settings.hideBlockedMessages != hideMessages || Nexa.settings.hideBlockedReplies != hideReplies) {
            Nexa.settings.patch(buildJsonObject {
                put("blocking", buildJsonObject {
                    put("hideMessages", hideMessages)
                    put("hideReplies", hideMessages && hideReplies)
                })
            })
        }
    }

    suspend fun unblock(userId: String): Boolean = busyWith(userId) {
        try {
            Nexa.api.request("DELETE", "/users/@me/blocks/$userId")
        } catch (e: Exception) {
            if (!(e is ApiError && e.status == 404)) {
                toast(describeError(e, t("friends.couldNotUpdate")))
                return@busyWith false
            }
        }
        Nexa.relationships.remove(userId)
        toast(t("friends.unblocked"), "success")
        true
    } ?: false

    /** A friend's private nickname (empty clears it); only you see it. */
    suspend fun setNickname(userId: String, nickname: String) {
        val res = Nexa.api.request("PATCH", "/users/@me/relationships/$userId", buildJsonObject { put("nickname", nickname) })
        runCatching { wireJson.decodeFromJsonElement(Relationship.serializer(), res) }.getOrNull()?.let { Nexa.relationships.set(it) }
    }

    /** Opens (or creates) the DM with someone; throws for the caller to report. */
    suspend fun openDm(userId: String): Channel = openConversation(listOf(userId), null)

    /** POST /users/@me/channels: one person is a DM, none or several a group. */
    suspend fun openConversation(ids: List<String>, name: String?): Channel {
        val res = Nexa.api.request("POST", "/users/@me/channels", buildJsonObject {
            putJsonArray("recipient_ids") { ids.forEach { add(JsonPrimitive(it)) } }
            if (!name.isNullOrBlank()) put("name", name.trim())
        })
        val ch = wireJson.decodeFromJsonElement(Channel.serializer(), res)
        Nexa.channels.upsert(listOf(ch))
        return ch
    }

    /** Runs one change for each person, then says how many went through and what stopped the rest. */
    suspend fun eachPerson(users: List<PublicUser>, job: suspend (PublicUser) -> Unit, done: (Int) -> String, failed: String) {
        var ok = 0
        var first: Throwable? = null
        for (u in users) {
            try {
                job(u)
                ok++
            } catch (e: Exception) {
                if (first == null) first = e
            }
        }
        if (ok > 0) toast(done(ok), "success")
        first?.let { toast(describeError(it, failed)) }
    }

    suspend fun acceptMany(users: List<PublicUser>) = eachPerson(users, { u ->
        val res = Nexa.api.request("PUT", "/users/@me/relationships/${u.id}") as JsonObject
        setRel(wireJson.decodeFromJsonElement(PublicUser.serializer(), res["user"]!!), "friend")
        res["channel"]?.let { el -> runCatching { wireJson.decodeFromJsonElement(Channel.serializer(), el) }.getOrNull()?.let { Nexa.channels.upsert(listOf(it)) } }
    }, { t("pick.accepted", "count" to it) }, t("profile.profileCard.couldNotAccept"))

    suspend fun dropMany(users: List<PublicUser>) = eachPerson(users, { u ->
        try {
            Nexa.api.request("DELETE", "/users/@me/relationships/${u.id}")
        } catch (e: ApiError) {
            if (e.status != 404) throw e
        }
        Nexa.relationships.remove(u.id)
    }, { t("pick.requestsDropped", "count" to it) }, t("friends.couldNotUpdate"))

    suspend fun unblockMany(users: List<PublicUser>) = eachPerson(users, { u ->
        try {
            Nexa.api.request("DELETE", "/users/@me/blocks/${u.id}")
        } catch (e: ApiError) {
            if (e.status != 404) throw e
        }
        Nexa.relationships.remove(u.id)
    }, { t("pick.unblocked", "count" to it) }, t("friends.couldNotUpdate"))

    suspend fun removeFriends(users: List<PublicUser>) = eachPerson(users, { u ->
        Nexa.api.request("DELETE", "/users/@me/relationships/${u.id}")
        Nexa.relationships.remove(u.id)
    }, { t("pick.friendsRemoved", "count" to it) }, t("friends.couldNotUpdate"))

    suspend fun blockMany(users: List<PublicUser>) = eachPerson(users, { u ->
        Nexa.api.request("PUT", "/users/@me/blocks/${u.id}")
        setRel(u, "blocked")
    }, { t("pick.blocked", "count" to it) }, t("friends.couldNotUpdate"))

    /** The person's public profile: mutual friends and servers and widgets come with it. */
    /** [serverWidgets]: the widgets are the ones they chose for the server asked about, not their main profile's. */
    data class Profile(val user: PublicUser, val mutualFriends: List<String>, val mutualServers: List<String>, val widgets: List<JsonObject>, val serverWidgets: Boolean = false, val badges: List<Badges.Badge>? = null)

    /** Someone's whole profile; opened in a server both are in, with the widgets they chose for it, if they did. */
    suspend fun profile(userId: String, serverId: String? = null): Profile {
        val res = Nexa.api.get("/users/$userId/profile" + (serverId?.let { "?server_id=$it" } ?: "")) as JsonObject
        val user = wireJson.decodeFromJsonElement(PublicUser.serializer(), res["user"]!!)
        Nexa.users.upsert(listOf(user))
        fun ids(k: String) = (res[k] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList()
        val widgets = (res["widgets"] as? JsonArray)?.mapNotNull { it as? JsonObject } ?: emptyList()
        return Profile(user, ids("mutual_friends"), ids("mutual_servers"), widgets, (res["server_widgets"] as? JsonPrimitive)?.booleanOrNull == true, Badges.parse(res["badges"]))
    }
}
