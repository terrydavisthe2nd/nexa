package fish.nexa.voice

import org.webrtc.SessionDescription

/**
 * The RTP header extension ids a connection has given each mid, as the web's
 * voice/extmaps.ts keeps them. WebRTC refuses a description that gives an id a
 * mid has had another meaning for, and Cloudflare does that when it hands a
 * stopped mid out again for a new pull: watching a share a second time was
 * refused and made the whole call again, everyone on "Reconnecting" with their
 * voices gone for a moment. Such a line is left out instead; Cloudflare already
 * answers every close with no extension lines at all.
 */
class ExtmapHistory {
    private val byMid = HashMap<String, HashMap<String, String>>()

    /** Takes note of what a description the connection has taken says. */
    fun note(sdp: String?) {
        for (s in sections(sdp ?: return)) {
            val mid = s.mid ?: continue
            for (line in s.lines) {
                val (id, uri) = extmap(line) ?: continue
                byMid.getOrPut(mid) { HashMap() }[id] = uri
            }
        }
    }

    /** The description without the extension lines that would give an id a mid already used another meaning. */
    fun fit(desc: SessionDescription): SessionDescription {
        if (byMid.isEmpty()) return desc
        val out = fitSdp(desc.description) ?: return desc
        return SessionDescription(desc.type, out)
    }

    /** The SDP with clashing lines dropped, or null when nothing clashes. */
    fun fitSdp(sdp: String): String? {
        var dropped = false
        val eol = if (sdp.contains("\r\n")) "\r\n" else "\n"
        val out = sections(sdp, eol).flatMap { s ->
            val ids = s.mid?.let { byMid[it] } ?: return@flatMap s.lines
            s.lines.filter { line ->
                val ext = extmap(line)
                val clash = ext != null && ids[ext.first].let { it != null && it != ext.second }
                if (clash) dropped = true
                !clash
            }
        }
        return if (dropped) out.joinToString(eol) else null
    }

    private class Section(var mid: String?, val lines: MutableList<String>)

    private fun sections(sdp: String, eol: String = if (sdp.contains("\r\n")) "\r\n" else "\n"): List<Section> {
        val out = mutableListOf(Section(null, mutableListOf()))
        for (line in sdp.split(eol)) {
            if (line.startsWith("m=")) out += Section(null, mutableListOf())
            val cur = out.last()
            cur.lines += line
            if (out.size > 1 && line.startsWith("a=mid:")) cur.mid = line.substring(6).trim()
        }
        return out
    }

    private fun extmap(line: String): Pair<String, String>? =
        EXTMAP.find(line)?.let { it.groupValues[1] to it.groupValues[2] }

    private companion object {
        val EXTMAP = Regex("^a=extmap:(\\d+)(?:/\\S+)?\\s+(\\S+)")
    }
}
