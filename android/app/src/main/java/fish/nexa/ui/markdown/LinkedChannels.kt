package fish.nexa.ui.markdown

import fish.nexa.Nexa
import fish.nexa.net.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

/**
 * Channels a link names that this device has not loaded (an archived thread,
 * say), asked of the server once each; it answers only for one this account
 * can see. The web's `linkedChannels` in markdown/render.tsx.
 */
object LinkedChannels {
    private val asked = ConcurrentHashMap.newKeySet<String>()
    private val _found = MutableStateFlow<Map<String, Channel>>(emptyMap())
    val found: StateFlow<Map<String, Channel>> = _found

    /** The channel, from the stores or an earlier answer; else asks for it and says null for now. */
    fun get(id: String): Channel? {
        Nexa.channels.get(id)?.let { return it }
        _found.value[id]?.let { return it }
        if (asked.add(id)) Nexa.scope.launch {
            val c = runCatching { Nexa.api.get("/channels/$id", Channel.serializer()) }.getOrNull() ?: return@launch
            _found.value = _found.value + (id to c)
        }
        return null
    }

    /** Forgets every answer: another account may see other channels. */
    fun clear() {
        asked.clear()
        _found.value = emptyMap()
    }
}
