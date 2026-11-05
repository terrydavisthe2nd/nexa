package fish.nexa.voice

import fish.nexa.Nexa
import fish.nexa.data.P

/**
 * What a call's permissions let this person do in it (web voice/callPerms.ts).
 * A server's voice channel answers by its roles and overwrites; a DM call
 * lets them do everything. The server holds the same rules; these keep the
 * app from offering what it would refuse.
 */
object CallPerms {
    private fun allows(channelId: String?, need: Long): Boolean {
        if (channelId.isNullOrEmpty()) return true
        val serverId = Nexa.channels.get(channelId)?.serverId ?: return true
        return Nexa.servers.canI(Nexa.servers.get(serverId), need, channelId)
    }

    /** Whether this person may turn on a camera or share a screen here (Video). */
    fun mayStream(channelId: String?): Boolean = allows(channelId, P.STREAM)

    /**
     * How this person's voice goes out here: as they chose, or mono where the
     * channel denies Use Stereo Voice. The server's word for a call this device is
     * in (mono_only) counts as soon as it comes, roles here perhaps not yet loaded.
     */
    fun allowedChannels(chosen: String, channelId: String?): String {
        if (chosen == "mono" || !allows(channelId, P.USE_STEREO_VOICE)) return "mono"
        val mine = Voice.state.value.mine
        return if (mine?.channelId == channelId && mine?.monoOnly == true) "mono" else chosen
    }

    /** Whether a channel lets this person talk by voice activity; without Use Voice Activity they must push to talk. */
    fun allowsVoiceActivity(channelId: String?): Boolean = allows(channelId, P.USE_VAD)

    /** Whether push to talk is in force: chosen in settings, or required by the call's channel. */
    fun pushToTalkOn(): Boolean = Voice.setting("mode") == "ptt" || !allowsVoiceActivity(Voice.state.value.mine?.channelId)
}
