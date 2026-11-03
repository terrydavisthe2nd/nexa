package fish.nexa.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The web's extmaps.test.ts: what Cloudflare sent on the second watch of a share, which WebRTC refused. */
class ExtmapHistoryTest {
    private val rid = "urn:ietf:params:rtp-hdrext:sdes:rtp-stream-id"
    private val mid = "urn:ietf:params:rtp-hdrext:sdes:mid"
    private val twcc = "http://www.ietf.org/id/draft-holmer-rmcat-transport-wide-cc-extensions-01"

    private fun sdp(vararg sections: List<String>) = (listOf("v=0", "s=-") + sections.flatMap { it } + "").joinToString("\r\n")
    private fun audio(m: String, ext: List<String>) = listOf("m=audio 9 UDP/TLS/RTP/SAVPF 111", "a=mid:$m") + ext
    private fun video(m: String, ext: List<String>) = listOf("m=video 9 UDP/TLS/RTP/SAVPF 96", "a=mid:$m") + ext

    @Test
    fun dropsTheIdsARecycledMidUsedForSomethingElseAndNothingMore() {
        val h = ExtmapHistory()
        h.note(sdp(audio("0", listOf("a=extmap:3 $twcc")), video("3", listOf("a=extmap:1 $rid", "a=extmap:4 $mid"))))
        val offer = sdp(audio("0", listOf("a=extmap:3 $twcc")), video("3", listOf("a=extmap:1 $twcc", "a=extmap:4 $mid", "a=extmap:3 $rid")), video("5", listOf("a=extmap:1 $twcc")))
        assertEquals(
            sdp(audio("0", listOf("a=extmap:3 $twcc")), video("3", listOf("a=extmap:4 $mid", "a=extmap:3 $rid")), video("5", listOf("a=extmap:1 $twcc"))),
            h.fitSdp(offer),
        )
    }

    @Test
    fun leavesADescriptionWithNothingToDropAlone() {
        val h = ExtmapHistory()
        val d = sdp(audio("0", listOf("a=extmap:3 $twcc")))
        h.note(d)
        assertNull(h.fitSdp(d))
    }
}
