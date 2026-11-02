package fish.nexa.ui.markdown

import java.util.Base64

/**
 * Links to Nexa's own pages told apart from links to anywhere else
 * (lib/appLinks.ts, lib/inviteLink.ts): an invite, a note's share link, a
 * profile or one of the app's routes opens inside the app. The instance's own
 * address and its public one ([setPublicUrl], from GET /instance) both count,
 * and plain http counts because it only redirects to https.
 */
object AppLinks {
    /** Where a link to this app leads. `hash` keeps an invite's or a note's key, which never leaves the device. */
    sealed class AppLink {
        data class Invite(val code: String, val hash: String) : AppLink()
        data class Note(val token: String, val hash: String) : AppLink()
        data class Profile(val username: String) : AppLink()
        data class Route(val path: String) : AppLink()
    }

    data class InviteLinkRef(val code: String, val hash: String, val url: String)
    data class NoteLinkRef(val token: String, val hash: String, val url: String)
    data class ProfileLinkRef(val username: String, val url: String)

    /** How many invite cards one message shows at most, how many note cards, and how many profile cards. */
    const val MAX_INVITE_CARDS = 3
    const val MAX_NOTE_CARDS = 3
    const val MAX_PROFILE_CARDS = 3

    @Volatile private var publicHost: String? = null
    @Volatile private var publicOrigin: String? = null

    /** Remembers Nexa's public address, as GET /instance reports it. */
    fun setPublicUrl(url: String?) {
        val u = url?.let { WebUrl.parse(it) }
        publicHost = u?.host?.lowercase()
        publicOrigin = u?.origin
    }

    /** The address a link made to be shared starts with: Nexa's public one, else the instance's own (`here`). */
    fun shareOrigin(here: String): String = publicOrigin ?: (WebUrl.parse(here)?.origin ?: here)

    /** Paths the server answers itself (files, media, the API). */
    private val SERVER_PATH = Regex("^/(?:api|media|assets|twemoji|icons|home|sounds|__download|\\.well-known)(?:/|$)")
    /** A file: anything with an extension. Nothing of the app's has one. */
    private val FILE_PATH = Regex("\\.[a-zA-Z0-9]{2,12}$")
    /** A guest call link starts a guest session of its own. */
    private val OWN_SESSION = Regex("^/call/")
    private val INVITE_PATH = Regex("^/invite/([A-Za-z0-9_-]{1,64})/?$")
    private val NOTE_PATH = Regex("^/note/([A-Za-z0-9_-]{16,128})/?$")
    private val MESSAGE_PATH = Regex("^/channels/(@me|[0-9]+)/([0-9]+)/([0-9]+)/?$")
    private val PROFILE_PATH = Regex("^/@([A-Za-z0-9_.]{2,32})/?$")

    /**
     * What a link leads to when it is one of the app's own pages, or null for
     * everything else. `here` is the instance's address (the page the web
     * would be on); a relative link is read against it.
     */
    fun appLink(href: String, here: String): AppLink? {
        val cur = WebUrl.parse(here) ?: return null
        val u = WebUrl.parse(href, cur) ?: return null
        val host = u.host.lowercase()
        if (host != cur.host.lowercase() && host != publicHost) return null
        val path = u.pathname
        // before the file test: "@maya.dev" is a username, not a file
        PROFILE_PATH.find(path)?.let { return AppLink.Profile(it.groupValues[1].lowercase()) }
        if (SERVER_PATH.containsMatchIn(path) || FILE_PATH.containsMatchIn(path) || OWN_SESSION.containsMatchIn(path)) return null
        // a footnote or a table of contents on the page being read
        if (u.hash.isNotEmpty() && path == cur.pathname && u.search == cur.search) return null
        INVITE_PATH.find(path)?.let { return AppLink.Invite(it.groupValues[1], u.hash) }
        NOTE_PATH.find(path)?.let { return AppLink.Note(it.groupValues[1], u.hash) }
        return AppLink.Route(path + u.search + u.hash)
    }

    /** Whether a link is a note's share link to this app, which nothing is asked about but the app itself. */
    fun isNoteLink(href: String, here: String): Boolean = appLink(href, here) is AppLink.Note

    private val JUMP_PATH = Regex("^/channels/(@me|[0-9]{1,20})/([0-9]{1,20})(?:/([0-9]{1,20}))?/?$")

    /**
     * The channel, and message if any, a link to a conversation in this app
     * points to, drawn as a small pill instead of its address (Render). Only a
     * bare path counts, as on the web (lib/appLinks.ts jumpTarget).
     */
    fun jumpTarget(href: String, here: String): Pair<String, String?>? {
        val link = appLink(href, here) as? AppLink.Route ?: return null
        val m = JUMP_PATH.find(link.path) ?: return null
        return m.groupValues[2] to m.groupValues[3].ifEmpty { null }
    }

    /** The channel and message a message link's path points to. */
    fun messageTarget(path: String): Pair<String, String>? {
        val head = path.split('?', '#')[0]
        val m = MESSAGE_PATH.find(head) ?: return null
        return m.groupValues[2] to m.groupValues[3]
    }

    /** The note share links a message has, in order and each once. */
    fun noteLinksIn(nodes: List<Node>, here: String): List<NoteLinkRef> {
        val out = ArrayList<NoteLinkRef>()
        val seen = HashSet<String>()
        walkLinks(nodes) { url ->
            val link = appLink(url, here)
            if (link is AppLink.Note && seen.add(link.token)) out += NoteLinkRef(link.token, link.hash, url)
            out.size < MAX_NOTE_CARDS
        }
        return out
    }

    /** The invites to Nexa a message links to, in order and each once. */
    fun inviteLinksIn(nodes: List<Node>, here: String): List<InviteLinkRef> {
        val out = ArrayList<InviteLinkRef>()
        val seen = HashSet<String>()
        walkLinks(nodes) { url ->
            val link = appLink(url, here)
            if (link is AppLink.Invite && seen.add(link.code)) out += InviteLinkRef(link.code, link.hash, url)
            out.size < MAX_INVITE_CARDS
        }
        return out
    }

    /** The profiles a message links to, in order and each once. */
    fun profileLinksIn(nodes: List<Node>, here: String): List<ProfileLinkRef> {
        val out = ArrayList<ProfileLinkRef>()
        val seen = HashSet<String>()
        walkLinks(nodes) { url ->
            val link = appLink(url, here)
            if (link is AppLink.Profile && seen.add(link.username)) out += ProfileLinkRef(link.username, url)
            out.size < MAX_PROFILE_CARDS
        }
        return out
    }

    /**
     * eachLink: every link that asks for a preview, in document order, with
     * the web's depth limit (each array and each object is a level).
     */
    internal fun walkLinks(nodes: List<Node>, fn: (String) -> Boolean) {
        var stop = false
        fun visit(value: Any?, depth: Int) {
            if (stop || depth > 64 || value == null) return
            when (value) {
                is List<*> -> for (v in value) visit(v, depth + 1)
                is Footnote -> visit(value.children, depth + 1)
                is Node -> {
                    if (value is Node.Code || value is Node.CodeBlock) return
                    if (value is Node.Link && !value.noEmbed && !fn(value.url)) {
                        stop = true
                        return
                    }
                    when (value) {
                        is Node.Container -> visit(value.children, depth + 1)
                        is Node.ListBlock -> visit(value.items, depth + 1)
                        is Node.Table -> {
                            visit(value.head, depth + 1)
                            visit(value.rows, depth + 1)
                        }
                        is Node.Details -> {
                            visit(value.summary, depth + 1)
                            visit(value.children, depth + 1)
                        }
                        is Node.Footnotes -> visit(value.notes, depth + 1)
                        else -> Unit
                    }
                }
            }
        }
        visit(nodes, 0)
    }

    /**
     * How an invite link to this instance reads in a message: its address
     * without the key, which is most of its length. Null for any other address,
     * or an invite with no key to hide. `origin` is the instance's (scheme://host).
     */
    fun shownInvite(url: String, origin: String): String? {
        val u = WebUrl.parse(url) ?: return null
        if (u.origin != origin || !Regex("^/invite/[A-Za-z0-9_-]+/?$").containsMatchIn(u.pathname) || u.hash.isEmpty()) return null
        return u.host + u.pathname.removeSuffix("/")
    }

    /** The server key an invite link carries in its fragment (`#k=<epoch>.<base64url root>`). */
    class InviteKey(val epoch: Long, val root: ByteArray)

    private val INVITE_FRAGMENT = Regex("(?:^|[#&])k=([0-9]+)\\.([A-Za-z0-9_-]{40,50})(?:&|$)")

    fun parseInviteFragment(hash: String): InviteKey? {
        val m = INVITE_FRAGMENT.find(hash) ?: return null
        return try {
            val s = m.groupValues[2].replace('-', '+').replace('_', '/')
            val root = Base64.getDecoder().decode(s + "=".repeat((4 - s.length % 4) % 4))
            if (root.size != 32) null else InviteKey(m.groupValues[1].toBigInteger().toLong(), root)
        } catch (_: Exception) {
            null
        }
    }

    fun encodeInviteKey(key: InviteKey): String =
        "k=" + key.epoch + "." + Base64.getEncoder().encodeToString(key.root).replace('+', '-').replace('/', '_').trimEnd('=')

    /** An invite's link: the instance's origin, the code, and the key after # when there is one. */
    fun inviteUrl(origin: String, code: String, key: InviteKey? = null): String {
        val base = "$origin/invite/$code"
        return if (key != null) base + "#" + encodeInviteKey(key) else base
    }
}
