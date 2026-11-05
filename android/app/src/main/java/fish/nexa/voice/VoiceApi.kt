package fish.nexa.voice

import fish.nexa.Nexa
import fish.nexa.net.ApiError
import fish.nexa.net.IceServer
import fish.nexa.net.wireJson
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.webrtc.SessionDescription

@Serializable
data class IceConfig(
    @SerialName("ice_servers") val iceServers: List<IceServer> = emptyList(),
    val sfu: Boolean = false,
    val guests: Boolean = true,
)

@Serializable
data class SfuDescription(val type: String = "", val sdp: String = "") {
    fun toWebRtc(): SessionDescription? = Sdp.typeOf(type)?.let { SessionDescription(it, sdp) }
}

@Serializable
data class SfuPulled(
    val mid: String? = null,
    @SerialName("user_id") val userId: String = "",
    val kind: String = "",
    val tag: String? = null,
    val error: String? = null,
)

@Serializable
data class SfuSubscribeResult(val offer: SfuDescription? = null, val renegotiate: Boolean = false, val tracks: List<SfuPulled> = emptyList())

@Serializable
data class SfuPublishResult(val answer: SfuDescription? = null, val failed: Int = 0)

@Serializable
data class SfuAnswer(val answer: SfuDescription? = null)

@Serializable
data class GuestLink(
    val code: String = "",
    @SerialName("channel_id") val channelId: String = "",
    @SerialName("creator_id") val creatorId: String = "",
    val epoch: Int = 0,
    @SerialName("created_at") val createdAt: Long = 0,
    @SerialName("expires_at") val expiresAt: Long = 0,
    val guests: Int = 0,
)

/** The call routes (web/src/api/voice.ts and guests.ts), each SFU step given 15 s. */
object VoiceApi {
    private const val REQUEST_TIMEOUT_MS = 15_000L

    private suspend fun call(method: String, path: String, body: JsonElement? = null): JsonElement =
        withTimeoutOrNull(REQUEST_TIMEOUT_MS) { Nexa.api.request(method, path, body) }
            ?: throw ApiError(0, "timeout", "The server took too long to answer.")

    private inline fun <reified T> JsonElement.read(s: kotlinx.serialization.KSerializer<T>): T = wireJson.decodeFromJsonElement(s, this)

    private fun desc(d: SessionDescription) = buildJsonObject {
        put("type", Sdp.wireOf(d.type))
        put("sdp", d.description)
    }

    suspend fun ice(): IceConfig = call("GET", "/voice/ice").read(IceConfig.serializer())

    suspend fun sfuSession(): String = (call("POST", "/voice/sfu/session") as? JsonObject)?.get("session_id")?.let { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
        ?: throw ApiError(200, "bad_response", "The server's answer could not be read.")

    /** `tracks`: (mid, kind, bitrate for a screen or 0). */
    suspend fun sfuPublish(session: String, offer: SessionDescription, tracks: List<Triple<String, String, Int>>): SfuPublishResult =
        call("POST", "/voice/sfu/publish", buildJsonObject {
            put("session_id", session)
            put("offer", desc(offer))
            put("tracks", buildJsonArray {
                for ((mid, kind, bitrate) in tracks) add(buildJsonObject {
                    put("mid", mid)
                    put("kind", kind)
                    if (bitrate > 0) put("bitrate", bitrate)
                })
            })
        }).read(SfuPublishResult.serializer())

    suspend fun sfuSubscribe(session: String, tracks: List<Pair<String, String>>): SfuSubscribeResult =
        call("POST", "/voice/sfu/subscribe", buildJsonObject {
            put("session_id", session)
            put("tracks", buildJsonArray {
                for ((user, kind) in tracks) add(buildJsonObject {
                    put("user_id", user)
                    put("kind", kind)
                })
            })
        }).read(SfuSubscribeResult.serializer())

    suspend fun sfuRenegotiate(session: String, answer: SessionDescription) {
        call("PUT", "/voice/sfu/renegotiate", buildJsonObject {
            put("session_id", session)
            put("description", desc(answer))
        })
    }

    suspend fun sfuRestart(session: String, offer: SessionDescription, mid: String, kind: String): SfuAnswer =
        call("PUT", "/voice/sfu/restart", buildJsonObject {
            put("session_id", session)
            put("offer", desc(offer))
            put("mid", mid)
            put("kind", kind)
        }).read(SfuAnswer.serializer())

    suspend fun sfuClose(session: String, mids: List<String>, offer: SessionDescription): SfuAnswer =
        call("PUT", "/voice/sfu/close", buildJsonObject {
            put("session_id", session)
            put("mids", buildJsonArray { for (m in mids) add(kotlinx.serialization.json.JsonPrimitive(m)) })
            put("offer", desc(offer))
        }).read(SfuAnswer.serializer())

    suspend fun decline(channelId: String) {
        Nexa.api.request("POST", "/channels/$channelId/call/decline")
    }

    suspend fun callSummary(channelId: String, messageId: String, ciphertext: JsonObject, conv: String, epoch: Int) {
        Nexa.api.request("PUT", "/channels/$channelId/messages/$messageId/call", buildJsonObject {
            put("ciphertext", ciphertext)
            put("key_conv_id", conv)
            put("key_epoch", epoch)
        })
    }

    // --- guests ---------------------------------------------------------------

    suspend fun createGuestLink(channelId: String, epoch: Int, maxAge: Int?): GuestLink =
        Nexa.api.request("POST", "/channels/$channelId/guest-links", buildJsonObject {
            put("epoch", epoch)
            if (maxAge != null) put("max_age", maxAge)
        }).read(GuestLink.serializer())

    suspend fun guestLinks(channelId: String): List<GuestLink> =
        Nexa.api.request("GET", "/channels/$channelId/guest-links").let { if (it is JsonNull) emptyList() else it.read(ListSerializer(GuestLink.serializer())) }

    suspend fun revokeGuestLinks(channelId: String): Int =
        ((Nexa.api.request("DELETE", "/channels/$channelId/guest-links") as? JsonObject)?.get("revoked") as? kotlinx.serialization.json.JsonPrimitive)?.content?.toIntOrNull() ?: 0

    suspend fun removeGuest(channelId: String, guestId: String) {
        Nexa.api.request("DELETE", "/channels/$channelId/guests/$guestId")
    }
}
