package fish.nexa.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import fish.nexa.Nexa
import fish.nexa.data.Ids
import fish.nexa.ui.components.ButtonSize
import fish.nexa.ui.components.NxButton
import fish.nexa.ui.components.Txt
import fish.nexa.ui.layers.Layers
import fish.nexa.ui.layers.openDialog
import fish.nexa.ui.theme.Nx
import fish.nexa.ui.theme.boxShadow
import fish.nexa.ui.theme.brandGradient
import fish.nexa.ui.theme.topEdge
import kotlinx.coroutines.delay
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Calendar
import java.util.TimeZone

/**
 * What's New (lib/whatsNew.ts + WhatsNewDialog.tsx), read from the web's own
 * release list, which the build copies into the app: one list for every
 * device, and one "seen" marker that follows the account.
 */
object WhatsNew {
    data class Item(val icon: String, val title: String, val body: String)
    data class Release(val id: String, val items: List<Item>)

    const val MAX_SHOWN = 6

    /** The releases, newest first, parsed from whatsNew.ts (its RELEASES literal). */
    val releases: List<Release> by lazy {
        val src = runCatching { fish.nexa.data.Ota.open("whatsnew/whatsNew.ts").bufferedReader().readText() }.getOrDefault("")
        val body = src.substringAfter("export const RELEASES", "")
        val out = ArrayList<Release>()
        val idRx = Regex("""id:\s*"([^"]+)"""")
        val itemRx = Regex("""\{\s*icon:\s*(\w+),\s*title:\s*"([^"]+)",\s*body:\s*"([^"]+)"\s*\}""")
        val ids = idRx.findAll(body).toList()
        for ((i, m) in ids.withIndex()) {
            val end = if (i + 1 < ids.size) ids[i + 1].range.first else body.length
            val chunk = body.substring(m.range.last, end)
            val items = itemRx.findAll(chunk).map { Item(it.groupValues[1], it.groupValues[2], it.groupValues[3]) }.toList()
            if (items.isNotEmpty()) out += Release(m.groupValues[1], items)
        }
        out
    }

    private val RELEASE_ID = Regex("""^(\d{4})-(\d{2})-(\d{2})(?:\.(\d+))?$""")

    /** A release id as its day (UTC midnight) and its number that day. */
    private fun parse(id: String): Pair<Long, Int>? {
        val m = RELEASE_ID.find(id) ?: return null
        val cal = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear()
            set(m.groupValues[1].toInt(), m.groupValues[2].toInt() - 1, m.groupValues[3].toInt())
        }
        return cal.timeInMillis to (m.groupValues[4].toIntOrNull() ?: 1)
    }

    fun compare(a: String, b: String): Int {
        val x = parse(a)
        val y = parse(b)
        if (x == null || y == null) return (if (x != null) 1 else 0) - (if (y != null) 1 else 0)
        return x.first.compareTo(y.first).takeIf { it != 0 } ?: x.second.compareTo(y.second)
    }

    /** The later marker, so a device on an older list never moves it back. */
    fun later(a: String?, b: String?): String? {
        if (a == null || parse(a) == null) return if (b != null && parse(b) != null) b else a
        if (b == null || parse(b) == null) return a
        return if (compare(a, b) >= 0) a else b
    }

    /** When this copy of the app reached the phone: no release can have reached the person before that. */
    private fun builtAt(): Long = runCatching { Nexa.app.packageManager.getPackageInfo(Nexa.app.packageName, 0).lastUpdateTime }.getOrDefault(Long.MAX_VALUE)

    private fun releaseStart(id: String): Long = minOf(parse(id)?.first ?: Long.MIN_VALUE, builtAt())

    /** Those after the last one this account saw, out after the account was made; the newest six. */
    fun unseen(seen: String?, accountCreated: Long): List<Release> =
        releases.filter { (seen == null || compare(it.id, seen) > 0) && accountCreated < releaseStart(it.id) }.take(MAX_SHOWN)

    private fun dayOf(id: String): String {
        val p = parse(id) ?: return id
        val f = android.icu.text.DateFormat.getInstanceForSkeleton("yMMMMd", I18n.locale).apply { timeZone = android.icu.util.TimeZone.getTimeZone("UTC") }
        return f.format(java.util.Date(p.first))
    }

    private var shownThisRun = false

    /**
     * The interrupt: once settings have loaded, when nothing else is open,
     * the releases this account has not seen yet.
     */
    suspend fun maybeShow() {
        if (shownThisRun) return
        val me = Nexa.users.me.value ?: return
        while (!Nexa.settings.loaded.value) delay(1000)
        val list = unseen(Nexa.settings.whatsNewSeen, Ids.createdAt(me.id))
        if (list.isEmpty()) return
        while (!Layers.isEmpty) delay(1000)
        shownThisRun = true
        open(list, intro = true)
    }

    /** Settings, About shows every release on demand. */
    fun openAll() = open(releases, intro = false)

    private fun open(list: List<Release>, intro: Boolean) {
        val newest = list.firstOrNull()?.id ?: return
        openDialog(
            "whats-new",
            t("whatsNew.title"),
            onClosed = {
                val next = later(Nexa.settings.whatsNewSeen, newest)
                if (next != Nexa.settings.whatsNewSeen) Nexa.settings.patch(buildJsonObject { put("whatsNewSeen", next) })
            },
            footer = { close -> NxButton(t("whatsNew.gotIt"), close, size = ButtonSize.MD, modifier = Modifier.height(44.dp)) },
        ) {
            val c = Nx.colors
            if (intro) Txt(t("whatsNew.intro"), style = Nx.type.body.copy(fontSize = Nx.type.sp(15f), lineHeight = Nx.type.sp(22.5f)), color = c.textSubtle, modifier = Modifier.padding(bottom = 20.dp))
            // one section per day: releases of the same day read as one
            val days = list.groupBy { it.id.take(10) }.entries.toList()
            var index = 0
            days.forEachIndexed { d, (day, rels) ->
                if (d > 0) Box(Modifier.padding(top = 24.dp, bottom = 20.dp).fillMaxWidth().height(1.dp).background(c.borderSubtle))
                Txt(dayOf(day), style = Nx.type.label.copy(fontWeight = FontWeight(600)), color = c.textMuted, modifier = Modifier.padding(bottom = 14.dp))
                Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    for (item in rels.flatMap { it.items }) {
                        ItemRow(item, index)
                        index += 1
                    }
                }
            }
        }
    }

    @Composable
    private fun ItemRow(item: Item, index: Int) {
        val c = Nx.colors
        val reduced = Nx.motion.reduced
        val ease = Nx.motion.easeOut
        val enter = remember { Animatable(if (reduced) 1f else 0f) }
        LaunchedEffect(Unit) {
            delay(minOf(index, 5) * 60L)
            enter.animateTo(1f, tween(420, easing = ease))
        }
        Row(
            Modifier.graphicsLayer {
                alpha = enter.value
                translationY = (1f - enter.value) * 8f * density
            },
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            val shape = RoundedCornerShape(12.dp)
            Box(
                Modifier
                    .size(32.dp)
                    .boxShadow(shape, 6f, 16f, c.brand.copy(alpha = 0.8f), spread = -6f)
                    .background(brandGradient(c), shape)
                    .topEdge(shape, Color.White.copy(alpha = 0.22f)),
                contentAlignment = Alignment.Center,
            ) { Icon(item.icon, c.onBrand, size = 18.dp) }
            Column(Modifier.weight(1f)) {
                Txt(t(item.title), style = Nx.type.body.copy(fontSize = Nx.type.sp(15.5f), lineHeight = Nx.type.sp(20.9f), fontWeight = FontWeight(700)), color = c.textStrong, modifier = Modifier.padding(top = 1.dp, bottom = 3.dp))
                Txt(t(item.body), style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(14f), lineHeight = Nx.type.sp(21.7f)), color = c.textSubtle, modifier = Modifier.widthIn(max = 560.dp))
            }
        }
    }
}
