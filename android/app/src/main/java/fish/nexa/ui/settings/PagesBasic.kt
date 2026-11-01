package fish.nexa.ui.settings

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings as SystemSettings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import fish.nexa.Nexa
import fish.nexa.R
import fish.nexa.notify.Background
import fish.nexa.notify.Notifier
import fish.nexa.ui.I18n
import fish.nexa.ui.Icon
import fish.nexa.ui.components.ButtonKind
import fish.nexa.ui.components.ButtonSize
import fish.nexa.ui.components.NxButton
import fish.nexa.ui.components.Txt
import fish.nexa.ui.components.pressable
import fish.nexa.ui.t
import fish.nexa.ui.theme.Nx
import fish.nexa.ui.theme.Radii
import fish.nexa.ui.theme.mixSrgb
import fish.nexa.ui.theme.ring
import fish.nexa.ui.theme.sheen
import fish.nexa.ui.theme.topEdge
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

// ---- reading and writing the synced settings, as the pages need them ----

/** The settings document, recomposing whatever reads it when it changes. */
@Composable
fun settingsDoc(): JsonObject = Nexa.settings.doc.collectAsState().value

fun JsonObject.bool(key: String, def: Boolean): Boolean = (this[key] as? JsonPrimitive)?.booleanOrNull ?: def
fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.contentOrNull
fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject
fun JsonObject.strings(key: String): List<String> = (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()

fun setSetting(key: String, value: JsonElement) = Nexa.settings.set(key, value)
fun setSetting(key: String, value: Boolean) = setSetting(key, JsonPrimitive(value))
fun setSetting(key: String, value: String?) = setSetting(key, value?.let { JsonPrimitive(it) } ?: JsonNull)

/** Changes keys inside one of the nested objects (notifications, voice, blocking, sounds), keeping the rest. */
fun patchNested(key: String, change: Map<String, JsonElement>) {
    val cur = Nexa.settings.doc.value[key] as? JsonObject ?: JsonObject(emptyMap())
    setSetting(key, JsonObject(LinkedHashMap(cur).apply { putAll(change) }))
}

// ---------------------------------------------------------------------------

/** 3.8 Messages & Media. */
@Composable
fun ChatSettingsPage() {
    val doc = settingsDoc()
    PageTitle(t("settings.chat.title"))
    SectionHeading(t("settings.appearance.messageDisplay"), Modifier.settingAnchor("messageDisplay"), first = true)
    val compact = doc.str("messageDisplay") == "compact"
    RadioCard(t("settings.appearance.cozy"), !compact, { setSetting("messageDisplay", "cozy") }, t("settings.appearance.cozyDesc"))
    RadioCard(t("settings.appearance.compact"), compact, { setSetting("messageDisplay", "compact") }, t("settings.appearance.compactDesc"))

    SectionHeading(t("settings.chat.linksAndMedia"))
    ToggleRow(
        t("settings.privacy.fetchLinkPreviewsWhenISend"), doc.bool("linkPreviews", true), { setSetting("linkPreviews", it) },
        t("settings.privacy.yourDeviceAsksTheServerTo"), anchor = "linkPreviews",
    )
    ToggleRow(
        t("settings.privacy.showEmbeddedImagesAndGifsFrom"), doc.bool("imagePreviews", true), { setSetting("imagePreviews", it) },
        t("settings.privacy.picturesGifsAndVideosFromLinks"), anchor = "imagePreviews",
    )

    SectionHeading(t("settings.chat.messageBox"))
    ToggleRow(
        t("settings.appearance.spellCheck"), doc.bool("spellcheck", true), { setSetting("spellcheck", it) },
        t("android.settings.spellCheckHint"), anchor = "spellcheck",
    )
    val words = doc.strings("spellWords")
    if (words.isNotEmpty()) SpellWords(words)
    ToggleRow(
        t("settings.appearance.smartEmoji"), doc.bool("smartEmoji", true), { setSetting("smartEmoji", it) },
        t("settings.appearance.smartEmojiHint"), anchor = "smartEmoji",
    )

    SectionHeading(t("settings.chat.typingHeading"))
    ToggleRow(
        t("settings.chat.customTyping"), doc.bool("customTyping", true), { setSetting("customTyping", it) },
        t("settings.chat.customTypingHint"), anchor = "customTyping",
    )
    ToggleRow(
        t("settings.chat.typingAvatars"), doc.bool("typingAvatars", true), { setSetting("typingAvatars", it) },
        t("settings.chat.typingAvatarsHint"), anchor = "typingAvatars",
    )
    ToggleRow(
        t("settings.chat.typingChannels"), doc.bool("typingChannels", true), { setSetting("typingChannels", it) },
        t("settings.chat.typingChannelsHint"), anchor = "typingChannels",
    )

    SectionHeading(t("settings.chat.reading"))
    ToggleRow(
        t("settings.notifications.readTtsAloud"), doc.bool("tts", true), { setSetting("tts", it) },
        t("settings.notifications.readTtsAloudHint"), anchor = "tts",
    )
}

/** The words added to the dictionary, each with a way to take it out again. */
@Composable
private fun SpellWords(words: List<String>) {
    val c = Nx.colors
    Column(Modifier.fillMaxWidth().settingAnchor("spellWords").padding(top = 12.dp, bottom = 14.dp)) {
        Txt(t("settings.appearance.yourWords"), style = Nx.type.label.copy(fontSize = Nx.type.sp(12.5f), fontWeight = FontWeight(700)), color = c.textMuted, modifier = Modifier.padding(bottom = 8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (w in words) {
                Row(
                    Modifier.heightIn(min = 32.dp).background(c.bgInput, RoundedCornerShape(999.dp)).padding(start = 10.dp, end = 0.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Txt(w, style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(14f)), color = c.text)
                    Box(
                        Modifier
                            .size(32.dp)
                            .clip(RoundedCornerShape(999.dp))
                            .pressable(remember { MutableInteractionSource() }) {
                                setSetting("spellWords", JsonArray(words.filter { it != w }.map { JsonPrimitive(it) }))
                            }
                            .semantics { contentDescription = t("settings.appearance.removeWord", "word" to w) },
                        contentAlignment = Alignment.Center,
                    ) { Icon("X", c.textMuted, size = 14.dp) }
                }
            }
        }
    }
    Box(Modifier.fillMaxWidth().size(1.dp).background(c.borderSubtle))
}

// ---------------------------------------------------------------------------

/** Plays a sound for the settings pages; the app's sound player fills it in (Sounds.preview). */
object SoundPreview {
    /** Set by the app's sound player when there is one (Sounds.preview); until then this plays it itself. */
    var preview: (id: String, pack: String?) -> Unit = { id, pack -> play(id, pack) }

    private val FILES = mapOf(
        "message" to "message.m4a", "mention" to "mention.m4a", "friend" to "friend-request.m4a", "ring" to "incoming-call.m4a",
        "outgoing" to "outgoing-call.m4a", "leave" to "leave-call.m4a", "mute" to "mute.m4a", "unmute" to "unmute.m4a",
        "deafen" to "deafen.m4a", "undeafen" to "undeafen.m4a", "shareStart" to "share-start.m4a", "shareStop" to "share-stop.m4a",
    )

    private fun attributes() = android.media.AudioAttributes.Builder()
        .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION)
        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
        .build()

    /** The pack's own recording if it has one, else Nexa's, else the tones; once, at the chosen volume. */
    private fun play(id: String, pack: String?) {
        val s = soundSettingsOf(Nexa.settings.doc.value["sounds"])
        val volume = s.volume / 100f
        val file = s.packs.firstOrNull { it.id == (pack ?: s.pack) }?.files?.get(id)
        Nexa.scope.launch {
            val custom = file?.let { f -> runCatching { fish.nexa.media.Media.decrypted(f.blob, f.key, f.size.takeIf { it > 0 }) }.getOrNull() }
            withContext(Dispatchers.Main) {
                val played = runCatching {
                    val mp = android.media.MediaPlayer()
                    mp.setAudioAttributes(attributes())
                    val asset = FILES[id]
                    when {
                        custom != null -> mp.setDataSource(custom.path)
                        asset != null -> fish.nexa.data.Ota.setDataSource(mp, "sounds/$asset")
                        else -> {
                            mp.release()
                            return@runCatching false
                        }
                    }
                    // recordings are mastered near full scale
                    mp.setVolume(0.7f * volume, 0.7f * volume)
                    mp.setOnCompletionListener { it.release() }
                    mp.prepare()
                    mp.start()
                    true
                }.getOrDefault(false)
                if (!played) synth(id, volume)
            }
        }
    }

    private class Tone(val freq: Double, val start: Double, val dur: Double)

    /** The tones the web synthesises where there is no recording (Someone Joins has none). */
    private fun synth(id: String, volume: Float) {
        val (tones, wave, gain) = when (id) {
            "message" -> Triple(listOf(Tone(880.0, 0.0, 0.09), Tone(1174.7, 0.09, 0.12)), "sine", 0.18)
            "mention" -> Triple(listOf(Tone(784.0, 0.0, 0.08), Tone(987.8, 0.09, 0.08), Tone(1318.5, 0.18, 0.16)), "sine", 0.18)
            "friend" -> Triple(listOf(Tone(587.3, 0.0, 0.10), Tone(880.0, 0.10, 0.10), Tone(1174.7, 0.20, 0.22)), "triangle", 0.20)
            "join" -> Triple(listOf(Tone(523.3, 0.0, 0.08), Tone(784.0, 0.08, 0.12)), "triangle", 0.18)
            "leave" -> Triple(listOf(Tone(784.0, 0.0, 0.08), Tone(523.3, 0.08, 0.12)), "triangle", 0.18)
            "mute" -> Triple(listOf(Tone(440.0, 0.0, 0.07), Tone(330.0, 0.07, 0.10)), "square", 0.08)
            "unmute" -> Triple(listOf(Tone(330.0, 0.0, 0.07), Tone(440.0, 0.07, 0.10)), "square", 0.08)
            "deafen" -> Triple(listOf(Tone(392.0, 0.0, 0.07), Tone(261.6, 0.07, 0.12)), "square", 0.08)
            "undeafen" -> Triple(listOf(Tone(261.6, 0.0, 0.07), Tone(392.0, 0.07, 0.12)), "square", 0.08)
            "shareStart" -> Triple(listOf(Tone(659.3, 0.0, 0.08), Tone(880.0, 0.08, 0.08), Tone(1318.5, 0.16, 0.14)), "triangle", 0.18)
            "shareStop" -> Triple(listOf(Tone(1318.5, 0.0, 0.08), Tone(880.0, 0.08, 0.08), Tone(659.3, 0.16, 0.14)), "triangle", 0.18)
            else -> Triple(listOf(Tone(659.3, 0.0, 0.25), Tone(880.0, 0.30, 0.25)), "sine", 0.15)
        }
        val rate = 44_100
        val total = tones.maxOf { it.start + it.dur } + 0.03
        val pcm = ShortArray((total * rate).toInt())
        val peak = maxOf(0.0002, gain * volume)
        for (tone in tones) {
            val from = (tone.start * rate).toInt()
            val n = ((tone.dur + 0.02) * rate).toInt()
            for (i in 0 until n) {
                val tt = i.toDouble() / rate
                val phase = (tone.freq * tt) % 1.0
                val v = when (wave) {
                    "square" -> if (phase < 0.5) 1.0 else -1.0
                    "triangle" -> 1 - 4 * Math.abs(phase - 0.5)
                    else -> Math.sin(2 * Math.PI * phase)
                }
                // 10 ms up to the peak, then an exponential fall to 0.0001 at the tone's end
                val env = when {
                    tt < 0.010 -> peak * tt / 0.010
                    tt >= tone.dur -> 0.0
                    else -> peak * Math.pow(0.0001 / peak, (tt - 0.010) / (tone.dur - 0.010))
                }
                val k = from + i
                if (k < pcm.size) pcm[k] = (pcm[k] + v * env * 32767).toInt().coerceIn(-32768, 32767).toShort()
            }
        }
        runCatching {
            val track = android.media.AudioTrack.Builder()
                .setAudioAttributes(attributes())
                .setAudioFormat(android.media.AudioFormat.Builder().setEncoding(android.media.AudioFormat.ENCODING_PCM_16BIT).setSampleRate(rate).setChannelMask(android.media.AudioFormat.CHANNEL_OUT_MONO).build())
                .setTransferMode(android.media.AudioTrack.MODE_STATIC)
                .setBufferSizeInBytes(pcm.size * 2)
                .build()
            track.write(pcm, 0, pcm.size)
            track.play()
            Nexa.scope.launch {
                kotlinx.coroutines.delay((total * 1000).toLong() + 200)
                runCatching { track.release() }
            }
        }
    }
}

/** Whether the system lets Nexa post notifications right now (Android 13 asks first). */
private fun systemAllows(): Boolean {
    if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(Nexa.app, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
    return NotificationManagerCompat.from(Nexa.app).areNotificationsEnabled()
}

private fun openAppNotificationSettings(context: android.content.Context) {
    val i = Intent(SystemSettings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(SystemSettings.EXTRA_APP_PACKAGE, context.packageName)
    runCatching { context.startActivity(i) }
}

/** 3.9 Notifications, with what a phone adds: the system's permission and the background connection. */
@Composable
fun NotificationsPage(openPage: (String, String?) -> Unit) {
    val context = LocalContext.current
    val doc = settingsDoc()
    val n = doc.obj("notifications") ?: JsonObject(emptyMap())
    // re-read the system's answer whenever the app comes back to the front (after its settings screen)
    var tick by remember { mutableIntStateOf(0) }
    val owner = LocalLifecycleOwner.current
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_RESUME) tick++ }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }
    val allowed = remember(tick) { systemAllows() }
    val runsFreely = remember(tick) { Background.runsFreely() }
    var background by remember { mutableStateOf(Background.enabled) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        tick++
        if (granted) patchNested("notifications", mapOf("android" to JsonPrimitive(true)))
    }

    PageTitle(t("settings.notifications.notifications"))
    // the Android app's own switch: "app" is the desktop app's, and one must not silence the other
    val on = n.bool("android", true) && allowed
    ToggleRow(
        t("android.settings.notifications.show"),
        on,
        { want ->
            if (!want) patchNested("notifications", mapOf("android" to JsonPrimitive(false)))
            else if (allowed) patchNested("notifications", mapOf("android" to JsonPrimitive(true)))
            else if (Build.VERSION.SDK_INT >= 33 && !Nexa.app.getSharedPreferences("notify", 0).getBoolean("denied", false)) {
                Nexa.app.getSharedPreferences("notify", 0).edit().putBoolean("denied", true).apply()
                ask.launch(Manifest.permission.POST_NOTIFICATIONS)
            } else openAppNotificationSettings(context)
        },
        if (allowed) t("android.settings.notifications.showHint") else t("android.settings.notifications.blocked"),
        anchor = "desktopNotifications",
    )
    if (!allowed) Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.End) {
        NxButton(t("android.settings.notifications.openSystem"), { openAppNotificationSettings(context) }, kind = ButtonKind.SECONDARY, size = ButtonSize.SM)
    }
    ToggleRow(
        t("settings.notifications.sounds"), n.bool("sounds", true), { patchNested("notifications", mapOf("sounds" to JsonPrimitive(it))) },
        t("settings.notifications.playASoundForNewMessages"), anchor = "sounds",
    )
    SoundsLink { openPage("sounds", null) }

    SectionHeading(t("android.settings.notifications.backgroundHeading"), Modifier.settingAnchor("background"))
    ToggleRow(
        t("android.settings.notifications.background"),
        background,
        { v ->
            background = v
            Background.enabled = v
        },
        t("android.settings.notifications.backgroundHint"),
    )
    if (background) {
        if (!runsFreely) Column(Modifier.fillMaxWidth().padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Hint(t("android.settings.notifications.batteryHint"))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                NxButton(t("android.settings.notifications.allowBackground"), { Background.askToRunFreely(context) }, kind = ButtonKind.SECONDARY, size = ButtonSize.SM)
            }
        } else Hint(t("android.settings.notifications.batteryOk"), Modifier.padding(top = 8.dp))
    }

    SectionHeading(t("settings.notifications.previewHeading"), Modifier.settingAnchor("notificationPreview"))
    Hint(t("settings.notifications.previewIntro"), Modifier.padding(bottom = 12.dp))
    val preview = n.str("preview") ?: "full"
    for ((value, label, hint) in listOf(
        Triple("full", "settings.notifications.previewFull", "settings.notifications.previewFullHint"),
        Triple("name", "settings.notifications.previewName", "settings.notifications.previewNameHint"),
        Triple("none", "settings.notifications.previewNone", "settings.notifications.previewNoneHint"),
    )) RadioCard(t(label), preview == value, { patchNested("notifications", mapOf("preview" to JsonPrimitive(value))) }, t(hint))

    SectionHeading(t("settings.notifications.test"))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((id, key) in listOf(
            "message" to "profile.profileCard.message", "mention" to "settings.notifications.mention", "friend" to "settings.notifications.friendRequest",
            "ring" to "settings.notifications.incomingCall", "outgoing" to "settings.notifications.outgoingCall", "leave" to "settings.notifications.leaveCall",
            "mute" to "settings.notifications.mute", "unmute" to "settings.notifications.unmute",
            "deafen" to "settings.notifications.deafen", "undeafen" to "settings.notifications.undeafen",
            "shareStart" to "settings.notifications.shareStart", "shareStop" to "settings.notifications.shareStop",
        )) NxButton(t(key), { SoundPreview.preview(id, null) }, kind = ButtonKind.SECONDARY, size = ButtonSize.SM)
        NxButton(t("android.settings.notifications.testNotification"), { testNotification() }, kind = ButtonKind.SECONDARY, size = ButtonSize.SM)
    }
}

/** The row on Notifications that leads to the Sounds page. */
@Composable
private fun SoundsLink(onClick: () -> Unit) {
    val c = Nx.colors
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    val shape = Radii.Md
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .ring(shape, c.borderSubtle)
            .background(c.bgSurface, shape)
            .background(sheen(c), shape)
            .topEdge(shape, c.edgeLight)
            .clip(shape)
            .pressable(remember { MutableInteractionSource() }, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon("AudioLines", c.icon, size = 22.dp)
        Column(Modifier.weight(1f)) {
            Txt(t("settings.sounds.title"), style = Nx.type.body.copy(fontWeight = FontWeight(600)), color = c.text)
            Txt(t("settings.sounds.linkHint"), style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(13f)), color = c.textMuted)
        }
        Icon(if (rtl) "ChevronLeft" else "ChevronRight", c.textMuted, size = 18.dp)
    }
}

/** "Test Notification": a real notification, so the person sees how one looks. */
@android.annotation.SuppressLint("MissingPermission")
private fun testNotification() {
    if (!systemAllows()) {
        Nexa.ui.toast(t("android.settings.notifications.turnOnFirst"), "error")
        return
    }
    Notifier.installChannels()
    val n = NotificationCompat.Builder(Nexa.app, Notifier.CH_DIRECT)
        .setSmallIcon(R.drawable.ic_notify)
        .setColor(0xFF8000FF.toInt())
        .setContentTitle("Nexa")
        .setContentText(t("settings.notifications.notificationsAreWorking"))
        .setAutoCancel(true)
        .setSilent(true)
        .build()
    runCatching { NotificationManagerCompat.from(Nexa.app).notify("ec-test", 1, n) }
        .onFailure { Nexa.ui.toast(t("android.settings.notifications.couldNotShow"), "error") }
}

// ---------------------------------------------------------------------------

/** 3.13 Accessibility. */
@Composable
fun AccessibilityPage(openPage: ((String, String?) -> Unit)? = null) {
    val doc = settingsDoc()
    PageTitle(t("settings.appearance.accessibility"))
    SectionHeading(t("settings.accessibility.motion"), first = true)
    ToggleRow(
        t("settings.appearance.reducedMotion"), doc.bool("reducedMotion", false), { setSetting("reducedMotion", it) },
        t("settings.appearance.disablesAnimationsAndTransitions"), anchor = "reducedMotion",
    )
    SectionHeading(t("settings.accessibility.reading"))
    ToggleRow(
        t("settings.appearance.alwaysUnderlineLinks"), doc.bool("underlineLinks", false), { setSetting("underlineLinks", it) },
        t("settings.appearance.makesLinksEasierToSpotIn"), anchor = "underlineLinks",
    )
    Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Txt(t("settings.accessibility.textSizeIsIn"), style = Nx.type.bodySmall.copy(fontSize = Nx.type.sp(14f)), color = Nx.colors.textMuted, modifier = Modifier.weight(1f, fill = false))
        if (openPage != null) NxButton(t("settings.accessibility.goToTextSize"), { openPage("appearance", "textSize") }, kind = ButtonKind.LINK, inline = true)
    }
}

/** 3.14 Language: the phone's own first, then each language in its own words. */
@Composable
fun LanguagePage() {
    val c = Nx.colors
    val doc = settingsDoc()
    val chosen = doc.str("locale")
    PageTitle(t("settings.appearance.language"))
    Hint(t("settings.appearance.languageHint"), Modifier.padding(bottom = 16.dp).settingAnchor("language"))
    val device = remember { I18n.pick(I18n.systemPreferred()) }
    val deviceName = I18n.languages.firstOrNull { it.tag == device }?.endonym ?: "English"
    val tiles = listOf(Triple<String?, String, String>(null, t("android.settings.matchMyDevice"), deviceName)) +
        I18n.languages.map { Triple<String?, String, String>(it.tag, it.endonym, it.english) }
    for (row in tiles.chunked(2)) {
        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((tag, name, sub) in row) {
                val on = chosen == tag
                val shape = Radii.Md
                Column(
                    Modifier
                        .weight(1f)
                        .then(
                            if (on) Modifier.ring(shape, c.brand.copy(alpha = 0.8f), 2.dp).background(mixSrgb(c.bgSurface, c.brand, 0.12f), shape)
                            else Modifier.ring(shape, c.borderSubtle).background(c.bgSurface, shape),
                        )
                        .background(sheen(c), shape)
                        .topEdge(shape, c.edgeLight)
                        .clip(shape)
                        .pressable(remember { MutableInteractionSource() }, role = Role.RadioButton) { setSetting("locale", tag) }
                        .semantics { selected = on }
                        .heightIn(min = 56.dp)
                        .padding(horizontal = 13.dp, vertical = 11.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Txt(name, style = Nx.type.body.copy(fontSize = Nx.type.sp(15f), fontWeight = FontWeight(600)), color = c.textStrong, maxLines = 1)
                    Txt(sub, style = Nx.type.hint, color = c.textMuted, maxLines = 1)
                }
            }
            if (row.size == 1) Box(Modifier.weight(1f))
        }
    }
}

/** 3.21 Advanced. */
@Composable
fun AdvancedPage() {
    val doc = settingsDoc()
    PageTitle(t("settings.privacy.advanced"))
    ToggleRow(
        t("settings.privacy.developerMode"), doc.bool("developerMode", false), { setSetting("developerMode", it) },
        t("settings.privacy.addsCopyIdOptionsToContext"), anchor = "developerMode",
    )
}
