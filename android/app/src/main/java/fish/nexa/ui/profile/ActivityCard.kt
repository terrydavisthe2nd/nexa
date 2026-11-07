package fish.nexa.ui.profile

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import fish.nexa.media.Media
import fish.nexa.Nexa
import fish.nexa.net.Activity
import fish.nexa.ui.Icon
import androidx.compose.ui.text.AnnotatedString
import fish.nexa.ui.components.ScrollGroup
import fish.nexa.ui.components.ScrollingText
import fish.nexa.ui.components.Txt
import fish.nexa.ui.components.pressable
import fish.nexa.ui.t
import fish.nexa.ui.theme.Nx
import fish.nexa.ui.theme.Radii
import fish.nexa.ui.theme.boxShadow
import fish.nexa.ui.theme.mixOklab
import fish.nexa.ui.theme.mixSrgb
import fish.nexa.ui.theme.ring
import fish.nexa.ui.theme.topEdge
import kotlinx.coroutines.delay

/** Opens a link from someone's profile or activity in the browser (or the app that owns it). */
fun openLink(url: String) {
    try {
        Nexa.app.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    } catch (_: Exception) {
        Nexa.ui.toast(t("modals.confirm.somethingWentWrong"), "error")
    }
}

private fun openActivityLink(raw: String) {
    val target = Activities.destination(raw) ?: return
    fish.nexa.ui.shell.confirm(t("textMenu.openLink"), target.href, t("textMenu.openLink"), danger = false) { openLink(target.href) }
}

private fun activityHost(url: String?): String? = Activities.destination(url)?.host

private fun Modifier.linked(url: String?): Modifier = if (activityHost(url) == null) this else this.pressable(MutableInteractionSource()) { openActivityLink(url!!) }

/**
 * ActivityCard: what someone is playing, hearing, watching or streaming, on
 * a card lit by its own art, with how far along it is.
 */
@Composable
fun ActivityCard(a: Activity) = ScrollGroup { ActivityCardBody(a) }

@Composable
private fun ActivityCardBody(a: Activity) {
    val c = Nx.colors
    val timed = a.timestamps?.start != null || a.timestamps?.end != null
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(timed) {
        while (timed) {
            delay(1000)
            now = System.currentTimeMillis()
        }
    }
    val clock = if (timed) Activities.progress(a, now) else null
    // the server's own addresses (the media proxy, game covers) come without a host
    val large = (a.assets?.largeImage ?: a.iconUrl)?.let(Media::absolute)
    val media = a.type == 1 || a.type == 2 || a.type == 3
    val title = if (media) a.details?.ifEmpty { null } ?: a.name else a.name
    data class Line(val text: String, val muted: Boolean = false)
    val lines = ArrayList<Line>()
    if (media) {
        a.state?.takeIf { it.isNotEmpty() }?.let { lines += Line(if (a.type == 2) t("activity.activityCard.byArtist", "name" to it) else it) }
        a.assets?.largeText?.takeIf { it.isNotEmpty() }?.let { lines += Line(if (a.type == 2) t("activity.activityCard.onAlbum", "name" to it) else it, muted = true) }
    } else {
        a.details?.takeIf { it.isNotEmpty() }?.let { lines += Line(it) }
        val size = a.party?.size
        val party = if (size != null && size.size >= 2) t("activity.activityCard.partySize", "count" to size[0], "max" to size[1]) else ""
        val state = listOf(a.state ?: "", party).filter { it.isNotEmpty() }.joinToString(" ")
        if (state.isNotEmpty()) lines += Line(state)
    }
    val green = Activities.green(c.isLight)
    val shape = Radii.Md
    // IGDB's id for the game being played: the one it came with, or once the server has found one with exactly its name
    val game = if (a.gameId == null && (a.type == 0 || a.type == 5)) a.name.trim() else ""
    var gameId by remember(game, a.gameId) { androidx.compose.runtime.mutableStateOf(a.gameId) }
    LaunchedEffect(game) {
        if (game.isNotEmpty()) gameId = try { fish.nexa.data.Games.match(game) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (_: Throwable) { null }
    }
    Box(
        Modifier
            .fillMaxWidth()
            .ring(shape, c.borderSubtle)
            .clip(shape)
            .background(c.bgSurface.copy(alpha = c.bgSurface.alpha * 0.72f), shape)
            .topEdge(shape, c.edgeLight),
    ) {
        if (large != null) {
            AsyncImage(
                large, null, Media.loader,
                Modifier.matchParentSize().graphicsLayer { alpha = 0.42f; scaleX = 1.8f; scaleY = 1.8f }.blur(34.dp),
                contentScale = ContentScale.Crop,
            )
        }
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (a.type == 2 && clock is Activities.Clock.Progress && clock.fraction < 1f) ActivityBars(green)
                else Icon(Activities.icon(a.type), if (a.type == 0 || a.type == 2) green else c.textBrand, size = 13.dp)
                ScrollingText(AnnotatedString(Activities.heading(a)), Nx.type.label.copy(fontSize = Nx.type.sp(12.5f), fontWeight = FontWeight(700), color = c.textSubtle), Modifier.weight(1f, fill = false))
                if (a.source == "discord") Txt(
                    t("activity.activityCard.fromDiscord"),
                    style = Nx.type.hint.copy(fontSize = Nx.type.sp(10.5f), fontWeight = FontWeight(650)), color = c.textMuted,
                    modifier = Modifier.background(c.bgActive, RoundedCornerShape(999.dp)).padding(horizontal = 7.dp, vertical = 1.dp),
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(76.dp)) {
                    if (large != null) AsyncImage(
                        large, a.assets?.largeText ?: a.name, Media.loader,
                        Modifier.size(76.dp).boxShadow(Radii.Sm, 8f, 20f, Color.Black.copy(alpha = 0.55f), spread = -8f).clip(Radii.Sm).background(c.bgActive).linked(a.assets?.largeUrl),
                        contentScale = ContentScale.Crop,
                    ) else Box(
                        Modifier.size(76.dp).clip(Radii.Sm).background(Brush.linearGradient(listOf(mixSrgb(c.bgActive, c.brand, 0.3f), c.bgActive))).topEdge(Radii.Sm, c.edgeLight),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Activities.icon(a.type), c.textSubtle, size = 32.dp) }
                    a.assets?.smallImage?.let { small ->
                        AsyncImage(
                            Media.absolute(small), a.assets.smallText ?: "", Media.loader,
                            Modifier.align(Alignment.BottomEnd).offset(6.dp, 6.dp).size(30.dp).clip(CircleShape).background(Color(10, 6, 20).copy(alpha = 0.6f)).padding(2.dp).clip(CircleShape).linked(a.assets.smallUrl),
                            contentScale = ContentScale.Crop,
                        )
                    }
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    ScrollingText(AnnotatedString(title), Nx.type.bodySmall.copy(fontSize = Nx.type.sp(14.5f), fontWeight = FontWeight(700), lineHeight = Nx.type.sp(18f), color = c.textStrong))
                    for (l in lines) Column {
                        ScrollingText(AnnotatedString(l.text), Nx.type.bodySmall.copy(fontSize = Nx.type.sp(14f), lineHeight = Nx.type.sp(18f), color = if (l.muted) c.textMuted else c.text))
                    }
                    when (clock) {
                        is Activities.Clock.Remaining -> Txt(t("activity.activityCard.timeLeft", "time" to Activities.clock(clock.remaining)), style = Nx.type.hint.copy(fontSize = Nx.type.sp(13f), fontFeatureSettings = "tnum"), color = c.textMuted)
                        is Activities.Clock.Elapsed -> Txt(t("activity.activityCard.timeElapsed", "time" to Activities.clock(clock.elapsed)), style = Nx.type.hint.copy(fontSize = Nx.type.sp(13f), fontFeatureSettings = "tnum"), color = c.textMuted)
                        else -> Unit
                    }
                }
            }
            if (clock is Activities.Clock.Progress) {
                Row(
                    Modifier.semantics { contentDescription = t("activity.activityCard.playback") },
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    val style = Nx.type.hint.copy(fontFeatureSettings = "tnum")
                    Txt(Activities.clock(clock.elapsed), style = style, color = c.textMuted)
                    val width by animateFloatAsState(clock.fraction, tween(1000), label = "bar")
                    Box(Modifier.weight(1f).height(5.dp).clip(RoundedCornerShape(3.dp)).background(c.textStrong.copy(alpha = 0.14f))) {
                        Box(
                            Modifier
                                .fillMaxWidth(width.coerceIn(0f, 1f))
                                .fillMaxHeight()
                                .boxShadow(RoundedCornerShape(3.dp), 0f, 10f, c.brand.copy(alpha = 0.7f))
                                .background(Brush.horizontalGradient(listOf(mixOklab(c.brand, Color.White, 0.25f), c.brand)), RoundedCornerShape(3.dp)),
                        )
                    }
                    Txt(Activities.clock(clock.total), style = style, color = c.textMuted)
                }
            }
            val buttons = a.buttons.orEmpty()
            if (buttons.isNotEmpty() || gameId != null) {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    for (b in buttons) Box(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .background(c.bgSurface)
                            .border(1.dp, c.border, RoundedCornerShape(999.dp))
                            .pressable(remember { MutableInteractionSource() }) { openActivityLink(b.url) }
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        contentAlignment = Alignment.Center,
                    ) { Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Txt(b.label, style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(14f), fontWeight = FontWeight(600)), color = c.text, maxLines = 1)
                        activityHost(b.url)?.let { Txt(it, style = Nx.type.hint.copy(fontSize = Nx.type.sp(11f)), color = c.textMuted) }
                    } }
                    gameId?.let { id ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .clip(RoundedCornerShape(999.dp))
                                .background(c.bgSurface)
                                .border(1.dp, c.border, RoundedCornerShape(999.dp))
                                .pressable(remember { MutableInteractionSource() }) { openGamePage(id) }
                                .padding(horizontal = 12.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(7.dp, Alignment.CenterHorizontally),
                        ) {
                            Icon("Info", c.textMuted, size = 15.dp)
                            Txt(t("games.page.viewGame"), style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(14f), fontWeight = FontWeight(600)), color = c.text, maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

/** Three green bars that move: a song part way through. */
@Composable
private fun ActivityBars(color: Color) {
    val reduced = Nx.motion.reduced
    val specs = listOf(900 to 0.4f, 1100 to 0.85f, 800 to 0.6f)
    Row(Modifier.width(13.dp).height(11.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom) {
        for ((ms, base) in specs) {
            val h = if (reduced) base else 0.3f + 0.7f * fish.nexa.ui.components.decorSwing(ms)
            Box(Modifier.weight(1f).fillMaxHeight(h).background(color, RoundedCornerShape(1.dp)))
        }
    }
}

/** ActivityList: every activity, one card each. */
@Composable
fun ActivityList(activities: List<Activity>) {
    if (activities.isEmpty()) return
    ScrollGroup { Column(verticalArrangement = Arrangement.spacedBy(10.dp)) { for (a in activities) ActivityCard(a) } }
}
