package fish.nexa.voice

import org.webrtc.SessionDescription

/**
 * What calls change in SDP (web/src/voice/opus.ts): every Opus payload type of
 * every audio section in a description applied as the remote one asks for DTX
 * and a voice's bitrate, in two channels when this device sends them (Dual Mono
 * and True Stereo). An encoder takes all of it from the other side's description,
 * and each exchange applies them again, so one untuned description would undo it.
 * This device's own description asks to play what it pulls in stereo, as a
 * decoder plays stereo only when the local side says so.
 */
object Sdp {
    /** The bitrate a voice is sent at, in bits per second. */
    const val VOICE_BITRATE = 48_000

    /** The bitrate of a voice in two channels; the SFU meter charges it at this. */
    const val VOICE_STEREO_BITRATE = 128_000

    private val WANTED = linkedMapOf("usedtx" to "1", "maxaveragebitrate" to VOICE_BITRATE.toString())
    private val WANTED_STEREO = linkedMapOf("usedtx" to "1", "maxaveragebitrate" to VOICE_STEREO_BITRATE.toString(), "stereo" to "1", "sprop-stereo" to "1")
    private val LISTEN = linkedMapOf("stereo" to "1")
    private val RTPMAP = Regex("^a=rtpmap:(\\d+) opus/48000", RegexOption.IGNORE_CASE)
    private val FMTP = Regex("^a=fmtp:(\\d+) (.*)$")

    /** An fmtp line's parameters with ours set, in the order they came. */
    private fun withOurs(params: String, wanted: Map<String, String>): String {
        val out = ArrayList<String>()
        val seen = HashSet<String>()
        for (part in params.split(";")) {
            val p = part.trim()
            if (p.isEmpty()) continue
            val key = p.split("=")[0].trim().lowercase()
            if (key in wanted) {
                if (!seen.add(key)) continue
                out += "$key=${wanted[key]}"
            } else out += p
        }
        for ((key, value) in wanted) if (key !in seen) out += "$key=$value"
        return out.joinToString(";")
    }

    /** One media section's lines with its Opus payload types tuned. */
    private fun tuneSection(lines: List<String>, wanted: Map<String, String>): List<String> {
        val out = ArrayList<String>()
        val opus = LinkedHashMap<String, Int>()
        val tuned = HashSet<String>()
        for (line in lines) {
            val map = RTPMAP.find(line)
            if (map != null) {
                out += line
                opus[map.groupValues[1]] = out.size - 1
                continue
            }
            val fmtp = FMTP.find(line)
            if (fmtp != null && fmtp.groupValues[1] in opus) {
                tuned += fmtp.groupValues[1]
                out += "a=fmtp:${fmtp.groupValues[1]} ${withOurs(fmtp.groupValues[2], wanted)}"
                continue
            }
            out += line
        }
        // an Opus mapping with no fmtp line of its own gets one, just after it
        for ((pt, at) in opus.entries.filter { it.key !in tuned }.sortedByDescending { it.value }) out.add(at + 1, "a=fmtp:$pt ${withOurs("", wanted)}")
        return out
    }

    /** A description with each Opus section tuned as [wanted] says from its mid; null leaves one as it is. */
    private fun tuneSections(sdp: String, wanted: (String?) -> Map<String, String>?): String {
        if (!Regex("opus/48000", RegexOption.IGNORE_CASE).containsMatchIn(sdp)) return sdp
        val eol = if (sdp.contains("\r\n")) "\r\n" else "\n"
        val lines = sdp.split(eol).toMutableList()
        // a trailing empty line stays last
        val trailing = if (lines.isNotEmpty() && lines.last() == "") lines.removeAt(lines.size - 1) else null
        val out = ArrayList<String>()
        var section = ArrayList<String>()
        fun flush() {
            val mid = section.firstOrNull { it.startsWith("a=mid:") }?.substring(6)?.trim()
            val tune = if (section.firstOrNull()?.startsWith("m=") == true) wanted(mid) else null
            out += if (tune != null) tuneSection(section, tune) else section
            section = ArrayList()
        }
        for (line in lines) {
            if (line.startsWith("m=")) flush()
            section += line
        }
        flush()
        if (trailing != null) out += trailing
        return out.joinToString(eol)
    }

    fun tuneOpus(sdp: String, stereoVoice: Boolean = false): String = tuneSections(sdp) { if (stereoVoice) WANTED_STEREO else WANTED }

    /** A description to apply as the remote one, tuned. Rollbacks pass as they are. */
    fun tunedRemote(desc: SessionDescription, stereoVoice: Boolean = false): SessionDescription =
        if (desc.type == SessionDescription.Type.ROLLBACK || desc.description.isNullOrEmpty()) desc
        else SessionDescription(desc.type, tuneOpus(desc.description, stereoVoice))

    /**
     * This device's own description, playing in stereo every audio section whose
     * mid is in [mids] (all of them when null). Sound sent in one channel still
     * plays in both ears as it always did.
     */
    fun tunedLocal(desc: SessionDescription, mids: Set<String>? = null): SessionDescription =
        if (desc.type == SessionDescription.Type.ROLLBACK || desc.description.isNullOrEmpty() || mids?.isEmpty() == true) desc
        else SessionDescription(desc.type, tuneSections(desc.description) { mid -> if (mids == null || mid in mids) LISTEN else null })

    fun typeOf(wire: String): SessionDescription.Type? = when (wire) {
        "offer" -> SessionDescription.Type.OFFER
        "answer" -> SessionDescription.Type.ANSWER
        "pranswer" -> SessionDescription.Type.PRANSWER
        "rollback" -> SessionDescription.Type.ROLLBACK
        else -> null
    }

    fun wireOf(type: SessionDescription.Type): String = when (type) {
        SessionDescription.Type.OFFER -> "offer"
        SessionDescription.Type.ANSWER -> "answer"
        SessionDescription.Type.PRANSWER -> "pranswer"
        SessionDescription.Type.ROLLBACK -> "rollback"
    }
}
