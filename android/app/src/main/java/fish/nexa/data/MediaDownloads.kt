package fish.nexa.data

import fish.nexa.Nexa

/**
 * web lib/mediaDownloads.ts: a media channel can hide the download buttons on
 * its pictures and videos (`forum.hideDownloads`). A courtesy the apps keep,
 * not a lock: whoever can see a picture can still keep it.
 */
object MediaDownloads {
    /** Whether a channel's pictures and videos offer no downloads: a post in a media channel that hides them. */
    fun hidden(channelId: String?): Boolean {
        if (channelId.isNullOrEmpty()) return false
        val ch = Nexa.channels.get(channelId) ?: return false
        val parent = if (ch.isThread) ch.parentId?.let { Nexa.channels.get(it) } else ch
        return parent?.isMedia == true && parent.forum?.hideDownloads == true
    }
}
