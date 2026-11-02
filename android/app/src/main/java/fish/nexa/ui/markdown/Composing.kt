package fish.nexa.ui.markdown

import fish.nexa.net.PublicUser
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.text.Collator
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * The parts of a message the composer leaves exactly as written (mentions.ts
 * KEEP): code, anything already in angle brackets (a mention, an emoji, a
 * timestamp, a link that is not to be previewed) and bare links, whose paths
 * can hold an @ or a # of their own. Splits like JavaScript's `split` with a
 * capturing group: kept parts at the odd indices.
 */
internal object Keep {
    fun split(text: String): List<String> {
        val out = ArrayList<String>()
        var p = 0
        var q = 0
        while (q < text.length) {
            val e = matchAt(text, q)
            if (e < 0) {
                q++
                continue
            }
            out += text.substring(p, q)
            out += text.substring(q, e)
            p = e
            q = e
        }
        out += text.substring(p)
        return out
    }

    /** `(```[\s\S]*?```|`[^`\n]*`|<[^<>\s]+>|https?:\/\/[^\s<]+)` at exactly `q`, case-insensitive: its end, or -1. */
    private fun matchAt(s: String, q: Int): Int {
        if (s.startsWith("```", q)) {
            val close = s.indexOf("```", q + 3)
            if (close >= 0) return close + 3
        }
        if (s[q] == '`') {
            var j = q + 1
            while (j < s.length && s[j] != '`' && s[j] != '\n') j++
            if (j < s.length && s[j] == '`') return j + 1
        }
        if (s[q] == '<') {
            var j = q + 1
            while (j < s.length && s[j] != '<' && s[j] != '>' && !Js.isSpace(s[j])) j++
            if (j > q + 1 && j < s.length && s[j] == '>') return j + 1
        }
        if (Js.regionMatchesAsciiIgnoreCase(s, q, "http")) {
            var j = q + 4
            if (j < s.length && (s[j] == 's' || s[j] == 'S') && s.startsWith("://", j + 1)) j++
            if (s.startsWith("://", j)) {
                j += 3
                val start = j
                while (j < s.length && s[j] != '<' && !Js.isSpace(s[j])) j++
                if (j > start) return j
            }
        }
        return -1
    }
}

/**
 * What a message's `@name`, `#channel` and `:emoji:` stand for where it is
 * being written (components/composer/mentions.ts). Typed by hand rather than
 * picked from the suggestions, they are still meant as the real thing, so
 * they are turned into it on the way out.
 */
object Mentions {
    /** Someone in the conversation and the names that count for them (username, display name, server nickname). */
    data class Member(val id: String, val names: List<String>)

    data class Named(val id: String, val name: String)

    /** A custom emoji for `:name:` as the token that shows it; `fallback` ones only stand in for a name no unicode emoji has. */
    data class EmojiToken(val name: String, val token: String, val fallback: Boolean = false)

    class Scope(
        /** The people in the conversation. */
        val members: List<Member> = emptyList(),
        /** The roles this person may mention here. */
        val roles: List<Named> = emptyList(),
        /** The server's channels, for `#name`. */
        val channels: List<Named> = emptyList(),
        /** This server's own emoji first; the rest marked `fallback`. */
        val emoji: List<EmojiToken> = emptyList(),
        /** Whether a unicode emoji goes by this name; null (not knowing yet) counts as yes. */
        val isShortcode: ((String) -> Boolean)? = null,
    )

    /** The message as it is sent, and who and what it mentions. */
    data class Extracted(val text: String, val mentions: List<String>, val roles: List<String>, val everyone: Boolean)

    private class Candidate(val id: String, val name: String, val lower: String, val rank: Int)

    private fun isWord(c: Char?): Boolean = c != null && Js.isWordUnit(c)

    /** Something that can follow a name for the name to have ended. */
    private fun ends(c: Char?): Boolean = !isWord(c)

    /** Where a trigger may start: not inside a word, and not escaped. */
    private fun opens(prev: Char?, also: String = ""): Boolean = prev == null || (!isWord(prev) && prev != '\\' && prev != '<' && also.indexOf(prev) < 0)

    /** Names that belong to exactly one of their holders; a name two people share is nobody's. */
    private fun unique(list: List<Named>, rank: Int): List<Candidate> {
        val holders = LinkedHashMap<String, LinkedHashSet<String>>()
        val first = HashMap<String, String>()
        for ((id, name) in list) {
            val clean = Js.trim(name)
            if (clean.isEmpty()) continue
            val lower = Js.lower(clean)
            if (!holders.containsKey(lower)) {
                holders[lower] = LinkedHashSet()
                first[lower] = clean
            }
            holders[lower]!!.add(id)
        }
        return holders.filter { it.value.size == 1 }.map { (lower, ids) -> Candidate(ids.first(), first[lower]!!, lower, rank) }
    }

    /** The candidate that the text at `at` spells out, the longest first; one typed with the same capitals wins a tie. */
    private fun longest(seg: String, at: Int, names: List<Candidate>, boundary: (Char?) -> Boolean): Candidate? {
        var best: Candidate? = null
        var bestExact = false
        for (n in names) {
            if (best != null && n.name.length < best.name.length) continue
            val typed = seg.substring(minOf(at, seg.length), minOf(at + n.name.length, seg.length))
            if (typed.length != n.name.length || Js.lower(typed) != n.lower || !boundary(Js.at(seg, at + n.name.length))) continue
            val exact = typed == n.name
            if (best == null || n.name.length > best.name.length || (exact && !bestExact) || (exact == bestExact && n.rank < best.rank)) {
                best = n
                bestExact = exact
            }
        }
        return best
    }

    /**
     * Turns what a message says by name into what it means: `@username` (any
     * capitals), the display name or server nickname of someone in the
     * conversation, and `@Role` into mentions, `#channel` into a link to it,
     * and `:name:` into this server's own emoji of that name. Names two people
     * share, parts of longer words, escaped names and anything in code or a
     * link are left as they are. `users` is everyone known, by id.
     */
    fun extract(text: String, users: Map<String, PublicUser>, scope: Scope = Scope()): Extracted {
        val byUsername = HashMap<String, PublicUser>()
        for (u in users.values) if (u.username.isNotEmpty()) byUsername[Js.lower(u.username)] = u
        val people = unique(scope.members.flatMap { m -> m.names.map { Named(m.id, it) } }, 2)
        val roles = unique(scope.roles, 1)
        val channels = unique(scope.channels, 0)
        val emoji = scope.emoji
        val mentions = LinkedHashSet<String>()
        val roleIds = LinkedHashSet<String>()
        var everyone = false

        fun userAt(seg: String, at: Int): Candidate? {
            // the longest run a username can be, then without the dots that end a sentence
            var end = at
            while (end < seg.length && end - at < 32 && seg[end].let { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '_' || it == '.' }) end++
            var typed = if (end - at >= 2) seg.substring(at, end) else ""
            while (typed.length >= 2) {
                val u = byUsername[Js.lower(typed)]
                if (u != null && ends(Js.at(seg, at + typed.length))) return Candidate(u.id, u.username, Js.lower(typed), 0)
                if (!typed.endsWith(".")) break
                typed = typed.substring(0, typed.length - 1)
            }
            return null
        }

        fun emojiFor(name: String): String? {
            val lower = Js.lower(name)
            val own = emoji.filter { !it.fallback && Js.lower(it.name) == lower }
            val hit = own.firstOrNull { it.name == name } ?: own.firstOrNull()
            if (hit != null) return hit.token
            if (scope.isShortcode?.invoke(name) ?: true) return null
            val other = emoji.filter { it.fallback && Js.lower(it.name) == lower }
            return (other.firstOrNull { it.name == name } ?: other.firstOrNull())?.token
        }

        fun convert(seg: String): String {
            val out = StringBuilder()
            var i = 0
            while (i < seg.length) {
                val c = seg[i]
                val prev = if (i > 0) seg[i - 1] else null
                if (c == '@' && opens(prev, "@")) {
                    val word = everyoneWord(seg, i + 1)
                    if (word != null) {
                        everyone = true
                        out.append('@').append(word)
                        i += 1 + word.length
                        continue
                    }
                    val candidates = listOfNotNull(userAt(seg, i + 1), longest(seg, i + 1, roles, ::ends), longest(seg, i + 1, people, ::ends))
                    var pick: Candidate? = null
                    for (n in candidates) {
                        val exact = seg.regionMatches(i + 1, n.name, 0, n.name.length) && i + 1 + n.name.length <= seg.length
                        val pickExact = pick != null && seg.regionMatches(i + 1, pick.name, 0, pick.name.length)
                        if (pick == null || n.name.length > pick.name.length ||
                            (n.name.length == pick.name.length && ((exact && !pickExact) || (exact == pickExact && n.rank < pick.rank)))
                        ) pick = n
                    }
                    if (pick != null) {
                        if (pick.rank == 1) {
                            roleIds += pick.id
                            out.append("<@&").append(pick.id).append('>')
                        } else {
                            mentions += pick.id
                            out.append("<@").append(pick.id).append('>')
                        }
                        i += 1 + pick.name.length
                        continue
                    }
                } else if (c == '#' && channels.isNotEmpty() && opens(prev, "#&")) {
                    // a channel's name goes on through its dashes, so #dev is not the start of #dev-chat
                    val hit = longest(seg, i + 1, channels) { ends(it) && it != '-' }
                    if (hit != null) {
                        out.append("<#").append(hit.id).append('>')
                        i += 1 + hit.name.length
                        continue
                    }
                } else if (c == ':' && emoji.isNotEmpty()) {
                    // /^:([A-Za-z0-9_]{1,32}):/
                    var j = i + 1
                    while (j < seg.length && j - i - 1 < 33 && seg[j].let { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '_' }) j++
                    val len = j - i - 1
                    if (len in 1..32 && Js.at(seg, j) == ':') {
                        val token = emojiFor(seg.substring(i + 1, j))
                        if (token != null) {
                            out.append(token)
                            i = j + 1
                            continue
                        }
                    }
                }
                out.append(c)
                i++
            }
            return out.toString()
        }

        val parts = Keep.split(text)
        val out = StringBuilder()
        for ((k, seg) in parts.withIndex()) {
            if (k % 2 == 0) {
                out.append(convert(seg))
                continue
            }
            // a mention written out in full counts as one; code and links stay what they are
            userToken(seg)?.let { mentions += it }
            roleToken(seg)?.let { roleIds += it }
            out.append(seg)
        }
        return Extracted(out.toString(), mentions.toList(), roleIds.toList(), everyone)
    }

    /** `/^(everyone|here)(?![\p{L}\p{N}_])/u` at `at`: the word, or null. */
    private fun everyoneWord(seg: String, at: Int): String? {
        for (word in listOf("everyone", "here")) {
            if (!seg.startsWith(word, at)) continue
            val after = at + word.length
            if (after < seg.length && Js.isWordCodePoint(seg.codePointAt(after))) continue
            return word
        }
        return null
    }

    private fun allDigits(s: String, from: Int, to: Int) = to > from && (from until to).all { s[it] in '0'..'9' }

    /** `/^<@!?(\d+)>$/`: the id, or null. */
    private fun userToken(seg: String): String? {
        if (!seg.startsWith("<@") || !seg.endsWith(">")) return null
        val from = if (seg.startsWith("<@!")) 3 else 2
        return if (allDigits(seg, from, seg.length - 1)) seg.substring(from, seg.length - 1) else null
    }

    /** `/^<@&(\d+)>$/`: the id, or null. */
    private fun roleToken(seg: String): String? =
        if (seg.startsWith("<@&") && seg.endsWith(">") && allDigits(seg, 3, seg.length - 1)) seg.substring(3, seg.length - 1) else null

    /** `/^<#(\d+)>$/`: the id, or null. */
    private fun channelToken(seg: String): String? =
        if (seg.startsWith("<#") && seg.endsWith(">") && allDigits(seg, 2, seg.length - 1)) seg.substring(2, seg.length - 1) else null

    /** `/^<a?:(\w+):\d+>$/`: the emoji's name, or null. */
    private fun emojiTokenName(seg: String): String? {
        if (!seg.endsWith(">")) return null
        val from = if (seg.startsWith("<a:")) 3 else if (seg.startsWith("<:")) 2 else return null
        val colon = seg.indexOf(':', from)
        if (colon <= from) return null
        val name = seg.substring(from, colon)
        if (!name.all { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '_' }) return null
        return if (allDigits(seg, colon + 1, seg.length - 1)) name else null
    }

    /** Names to give mentions back when a message is read or edited as text. */
    class HumanNames(
        val roles: Map<String, String> = emptyMap(),
        val channels: Map<String, String> = emptyMap(),
        /** Whether a custom emoji token may go back to its `:name:` (for an edit, only when that name brings the same emoji back). */
        val emoji: ((token: String, name: String) -> Boolean)? = null,
    )

    /**
     * The inverse, for editing and copying: `<@id>` back to `@username`, and
     * roles and channels back to their names where the caller knows them (for
     * an edit, the ones that turn back into the same mention when saved). Code
     * is left alone.
     */
    fun humanize(text: String, users: Map<String, PublicUser>, names: HumanNames = HumanNames()): String {
        val parts = Keep.split(text)
        val out = StringBuilder()
        for ((k, seg) in parts.withIndex()) {
            if (k % 2 == 0) {
                out.append(seg)
                continue
            }
            var s = seg
            userToken(s)?.let { id -> users[id]?.let { s = "@" + it.username } }
            roleToken(s)?.let { id -> names.roles[id]?.takeIf { it.isNotEmpty() }?.let { s = "@$it" } }
            channelToken(s)?.let { id -> names.channels[id]?.takeIf { it.isNotEmpty() }?.let { s = "#$it" } }
            emojiTokenName(s)?.let { name -> if (names.emoji?.invoke(s, name) == true) s = ":$name:" }
            out.append(s)
        }
        return out.toString()
    }
}

/** How long a message may be (components/composer/limit.ts), counted the way the box counts it: UTF-16 units. */
object MessageLimit {
    /** The most a message can say; sending, editing and the formatting editor all hold to it. */
    const val MAX_MESSAGE_LENGTH = 4000

    /** The counter shows once this few characters are left. */
    private const val COUNTER_FROM = 200

    /** The catalogue key of the refusal, with `count` = [MAX_MESSAGE_LENGTH]. */
    const val TOO_LONG_KEY = "composer.composer.messagesAreLimitedTo"

    /** Characters left before the limit; below zero once past it. */
    fun charsLeft(text: String, max: Int = MAX_MESSAGE_LENGTH): Int = max - text.length

    /** Whether the counter shows: near the limit or past it. */
    fun counterShown(text: String, max: Int = MAX_MESSAGE_LENGTH): Boolean = charsLeft(text, max) < COUNTER_FROM

    /** Whether a message is too long to send or save, leading and trailing space aside, as sending trims them. */
    fun tooLong(text: String): Boolean = Js.trim(text).length > MAX_MESSAGE_LENGTH

    /** What the box says when a message is refused for its length. */
    fun tooLongMessage(tr: (String, Int) -> String): String = tr(TOO_LONG_KEY, MAX_MESSAGE_LENGTH)

    /** Whether pasting `pasted` over the selection from `start` to `end` of `value` would take it past the limit. */
    fun pasteOverflows(value: String, start: Int, end: Int, pasted: String): Boolean = value.length - (end - start) + pasted.length > MAX_MESSAGE_LENGTH
}

/** A `:name:` typed out in full turns into its emoji as its closing colon goes in (components/composer/shortcodeTyping.ts). */
object ShortcodeTyping {
    /** Whether the caret is inside code, where a :name: is only text: past an odd number of ``` fences, or an odd number of backticks on its own line. */
    fun inCode(before: String): Boolean {
        var fences = 0
        var k = before.indexOf("```")
        while (k >= 0) {
            fences++
            k = before.indexOf("```", k + 3)
        }
        if (fences % 2 == 1) return true
        val line = before.substring(before.lastIndexOf('\n') + 1).replace("```", "")
        return line.count { it == '`' } % 2 == 1
    }

    /** The `:name:` that ends `before` (RE_CLOSED): the name, or null. */
    fun closedName(before: String): String? {
        if (!before.endsWith(":")) return null
        var j = before.length - 1
        while (j > 0 && before[j - 1].let { it in 'a'..'z' || it in '0'..'9' || it == '_' || it == '+' || it == '-' }) j--
        val len = before.length - 1 - j
        if (len !in 1..60 || j < 1 || before[j - 1] != ':') return null
        return before.substring(j, before.length - 1)
    }

    /**
     * The box after the closing colon of a `:name:`: the name turned into its
     * emoji in the picker's skin tone, so the box shows what is sent. A name
     * the server has its own custom emoji for (`ownEmoji`) is left as it is.
     * Null when there is nothing to turn. Returns the new text and caret.
     */
    fun closeShortcode(value: String, caret: Int, tone: Int, ownEmoji: (String) -> Boolean = { false }): Pair<String, Int>? {
        val before = value.substring(0, caret)
        val name = closedName(before) ?: return null
        if (inCode(before)) return null
        if (ownEmoji(name)) return null
        val entry = EmojiData.byShortcode(name)
        val emoji = (if (entry != null) EmojiData.withTone(entry, tone) else null) ?: Shortcodes.toEmoji(name) ?: return null
        val start = caret - name.length - 2
        return (value.substring(0, start) + emoji + value.substring(caret)) to start + emoji.length
    }
}

/** What a message that starts with a slash means (components/composer/commands.ts). */
object Commands {
    /** Markdown eats one backslash as an escape and would read the arm's _ as emphasis, so both are escaped. */
    private val KAOMOJI = mapOf(
        "shrug" to "¯\\\\\\_(ツ)_/¯",
        "tableflip" to "(╯°□°)╯︵ ┻━┻",
        "unflip" to "┬─┬ ノ( ゜-゜ノ)",
        "lenny" to "( ͡° ͜ʖ ͡°)",
    )

    /** The commands the box offers, with each one's description key. */
    val LIST = listOf(
        "shrug" to "composer.commands.appendsShrug",
        "tableflip" to "composer.commands.appendsToYourMessage",
        "unflip" to "composer.commands.appendsToYourMessage2",
        "lenny" to "composer.commands.appendsToYourMessage3",
        "me" to "composer.commands.displaysTextWithEmphasis",
        "spoiler" to "composer.commands.marksYourMessageAsASpoiler",
    )

    sealed class Result {
        /** Not a command at all (a path like /usr/bin, or no slash): sent as typed. */
        object Text : Result()
        /** One of the app's commands, and what it becomes. */
        data class Command(val text: String) : Result()
        /** Looks like a command the app does not have (/ban alice): not sent. */
        data class Unknown(val name: String) : Result()
        /** /me or /spoiler with nothing after it. */
        data class Empty(val name: String) : Result()
    }

    fun run(text: String): Result {
        val t = Js.trim(text)
        // /^\/(\w+)(?:\s+([\s\S]*))?$/
        if (!t.startsWith("/")) return Result.Text
        var j = 1
        while (j < t.length && t[j].let { it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '_' }) j++
        if (j == 1) return Result.Text
        val name = t.substring(1, j)
        var rest = ""
        if (j < t.length) {
            if (!Js.isSpace(t[j])) return Result.Text
            var k = j
            while (k < t.length && Js.isSpace(t[k])) k++
            rest = Js.trim(t.substring(k))
        }
        val cmd = Js.lower(name)
        KAOMOJI[cmd]?.let { return Result.Command(if (rest.isNotEmpty()) "$rest $it" else it) }
        if (cmd == "me") return if (rest.isNotEmpty()) Result.Command("*$rest*") else Result.Empty(cmd)
        if (cmd == "spoiler") return if (rest.isNotEmpty()) Result.Command("||$rest||") else Result.Empty(cmd)
        return Result.Unknown(name)
    }

    /** Expands a leading slash command; null when the text is not one this app has. */
    fun apply(text: String): String? = (run(text) as? Result.Command)?.text
}

/**
 * The order @ suggestions come in (components/composer/rankPeople.ts): exactly
 * what was typed, then names that start with it, then names with a word that
 * starts with it, then names that contain it; inside each, the people who
 * spoke here most recently lead, then the rest by name.
 */
object People {
    data class Person(val id: String, val username: String, val displayName: String)

    private val SEPARATOR = Regex("[\\s\\u00A0\\u1680\\u2000-\\u200A\\u2028\\u2029\\u202F\\u205F\\u3000\\uFEFF._-]+")

    fun <T> rank(
        query: String,
        people: List<T>,
        person: (T) -> Person,
        nickname: (String) -> String,
        recent: Map<String, Int>,
        locale: Locale = Locale.getDefault(),
    ): List<T> {
        val q = query.lowercase(locale)
        class Scored(val p: T, val tier: Int, val recent: Double, val name: String)
        val scored = ArrayList<Scored>()
        for (p in people) {
            val who = person(p)
            val nick = nickname(who.id)
            val names = listOf(who.username, who.displayName, nick).filter { it.isNotEmpty() }.map { it.lowercase(locale) }
            val tier = when {
                q.isEmpty() -> 3
                names.any { it == q } -> 0
                names.any { it.startsWith(q) } -> 1
                names.any { n -> n.split(SEPARATOR).any { it.startsWith(q) } } -> 2
                names.any { it.contains(q) } -> 3
                else -> continue
            }
            scored += Scored(p, tier, recent[who.id]?.toDouble() ?: Double.POSITIVE_INFINITY, nick.ifEmpty { who.displayName }.ifEmpty { who.username }.lowercase(locale))
        }
        val collator = Collator.getInstance(locale)
        return scored.sortedWith { a, b ->
            val byTier = a.tier - b.tier
            if (byTier != 0) return@sortedWith byTier
            val diff = a.recent - b.recent
            // two who never spoke here (infinite minus infinite) fall through to their names
            if (!diff.isNaN() && diff != 0.0) return@sortedWith if (diff < 0) -1 else 1
            collator.compare(a.name, b.name)
        }.map { it.p }
    }

    /** Who spoke last, most recent first, as a rank per person, from a conversation's newest authors first. */
    fun recentSpeakers(authorsNewestFirst: Iterable<String>, skip: String? = null): Map<String, Int> {
        val out = LinkedHashMap<String, Int>()
        for (id in authorsNewestFirst) {
            if (id == skip || out.containsKey(id)) continue
            out[id] = out.size
        }
        return out
    }
}

/**
 * The link previews one composer has fetched (components/composer/linkPreviews.ts).
 * Only the part of a link before `#` is sent to the server: an invite carries
 * the server's key in its fragment, and fragments never leave the device. The
 * fragment is put back on the preview so an invite card still joins with the
 * key. A note's share link gets no preview (the app draws it from its key).
 */
class LinkPreviews<T : Any>(
    private val scope: CoroutineScope,
    /** The instance's address, to tell a note's share link. */
    private val here: String,
    private val fetch: suspend (String) -> T?,
    private val urlOf: (T) -> String,
    private val withUrl: (T, String) -> T,
) {
    private val done = ConcurrentHashMap<String, Optional<T>>()
    private val inflight = ConcurrentHashMap<String, Job>()

    private class Optional<T>(val value: T?)

    /** Starts fetching a preview for the link unless it is known or on its way. */
    fun start(url: String) {
        if (done.containsKey(url) || inflight.containsKey(url)) return
        // and a link to a channel or message is a pill in the text, with no card under it
        if (AppLinks.isNoteLink(url, here) || AppLinks.jumpTarget(url, here) != null) {
            done[url] = Optional(null)
            return
        }
        val u = WebUrl.parse(url) ?: return
        val hash = u.hash
        val request = u.withoutFragment().href
        val job = scope.launch(start = kotlinx.coroutines.CoroutineStart.LAZY) {
            try {
                val r = fetch(request)
                done[url] = Optional(if (r != null && hash.isNotEmpty() && !urlOf(r).contains('#')) withUrl(r, urlOf(r) + hash) else r)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (_: Exception) {
                // a failed fetch is forgotten so the next edit can try again
            } finally {
                inflight.remove(url)
            }
        }
        inflight[url] = job
        job.start()
    }

    /** The previews for these links, waiting up to `waitMs` for any still on their way. */
    suspend fun embedsFor(urls: List<String>, waitMs: Long): List<T> {
        val waiting = ArrayList<Job>()
        for (url in urls) {
            start(url)
            inflight[url]?.let { waiting += it }
        }
        if (waiting.isNotEmpty()) withTimeoutOrNull(waitMs) { waiting.joinAll() }
        return urls.mapNotNull { done[it]?.value }
    }
}
