package fish.nexa.ui.chat

import fish.nexa.crypto.StickerRef
import fish.nexa.data.ChatMessage
import kotlinx.coroutines.launch

/*
 * The expression picker as a message and the composer open it
 * (components/chat/ReactionPicker.tsx, components/composer/ExpressionPicker.tsx):
 * which tabs show, and what a pick does.
 */

/** Add Reaction: the emoji tab alone, closing on a pick, which toggles that reaction. */
fun openReactionPicker(cm: ChatMessage, ctx: ListCtx) = fish.nexa.ui.pickers.Pickers.open(
    fish.nexa.ui.pickers.Pickers.Opener(listOf(fish.nexa.ui.pickers.Pickers.Tab.EMOJI), ctx.server?.server?.id, title = fish.nexa.ui.t("chat.reactions.addReaction"), draggable = false, closeOnEmoji = true, onEmoji = { p ->
        val key = p.custom?.let { (if (it.animated) "a" else "") + ":" + it.name + ":" + it.blobId }
            ?: p.packEmoji?.let { (if (it.animated) "a" else "") + ":" + it.name + ":" + it.blobId } ?: p.text
        val mine = cm.m.reactions.any { it.emoji == key && it.me }
        fish.nexa.data.Send.react(cm.channelId, cm.id, key, add = !mine)
    }),
)

/** The composer's picker: emoji go in at the caret (it stays open); a sticker or GIF goes at once, with the reply waiting. */
fun openExpressionPicker(channelId: String, onPick: (String) -> Unit) {
    val serverId = fish.nexa.Nexa.channels.get(channelId)?.serverId
    fun reply(): fish.nexa.data.SendOptions {
        val r = ChatUi.reply.remove(channelId)
        return fish.nexa.data.SendOptions(replyTo = r?.messageId, ping = r?.ping)
    }
    fish.nexa.ui.pickers.Pickers.open(fish.nexa.ui.pickers.Pickers.Opener(
        // a picker GIF is a preview, which Embed Links holds back, so without it there is no tab
        listOfNotNull(
            fish.nexa.ui.pickers.Pickers.Tab.GIFS.takeIf { serverId == null || fish.nexa.Nexa.servers.canI(fish.nexa.Nexa.servers.get(serverId), fish.nexa.data.P.EMBED_LINKS, channelId) },
            fish.nexa.ui.pickers.Pickers.Tab.STICKERS, fish.nexa.ui.pickers.Pickers.Tab.EMOJI,
        ), serverId,
        // a custom emoji reads as its :name: in the box where that name brings it back
        onEmoji = { p ->
            val name = p.custom?.name ?: p.packEmoji?.name
            onPick(if (name != null) fish.nexa.data.Mentions.emojiText(p.text, name, channelId) else p.text)
        },
        onSticker = { s ->
            fish.nexa.data.Send.sendMessage(channelId, "", reply().copy(sticker = StickerRef(s.id, s.blobId, s.name, s.width?.toDouble(), s.height?.toDouble(), s.animated, s.format)))
            fish.nexa.Nexa.scope.launch { runCatching { fish.nexa.Nexa.api.request("POST", "/stickers/recent", kotlinx.serialization.json.buildJsonObject { put("sticker_id", kotlinx.serialization.json.JsonPrimitive(s.id)) }) } }
        },
        onGif = gif@{ g ->
            // an uploaded favourite: its file goes in the tray and up like one just chosen
            g.att?.let { att ->
                fish.nexa.Nexa.scope.launch {
                    val st = Staging.favorite(att, channelId)
                    if (st == null) fish.nexa.Nexa.ui.toast(fish.nexa.ui.t("composer.composer.uploadFailed"), "error")
                    else {
                        val cur = ChatUi.trays[channelId] ?: emptyList()
                        if (cur.size < Staging.MAX_FILES) ChatUi.trays[channelId] = cur + st else st.file.delete()
                    }
                }
                return@gif
            }
            val embed = kotlinx.serialization.json.buildJsonObject {
                put("url", kotlinx.serialization.json.JsonPrimitive(g.url)); put("type", kotlinx.serialization.json.JsonPrimitive("gif")); put("image", kotlinx.serialization.json.JsonPrimitive(g.url))
                g.w?.let { put("image_w", kotlinx.serialization.json.JsonPrimitive(it)) }; g.h?.let { put("image_h", kotlinx.serialization.json.JsonPrimitive(it)) }
            }
            fish.nexa.data.Send.sendMessage(channelId, g.url, reply().copy(embeds = listOf(embed)))
        },
    ))
}


