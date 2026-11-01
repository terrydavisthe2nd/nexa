import { Hash, Images, Link2, Lock, Megaphone, MessagesSquare, Volume2 } from "lucide-react";
import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { ApiError } from "@/api/client";
import { servers as serversApi } from "@/api/servers";
import type { Channel } from "@/api/types";
import { Button } from "@/components/common/Button";
import { Modal } from "@/components/common/Modal";
import { Field, TextInput } from "@/components/common/TextInput";
import { Toggle } from "@/components/common/Toggle";
import { linkDestination } from "@/lib/linkChannel";
import { P, permString } from "@/lib/perms";
import { useChannels } from "@/stores/channels";
import { useKeys } from "@/stores/keys";
import { useUI } from "@/stores/ui";
import s from "./ServerModals.module.css";

import { t } from "@/i18n";
/**
 * A text channel's name as it is typed: lower case, with a dash for a space,
 * the way the server will store it. The field used to show "Plans For The
 * Weekend" and the sidebar then "#plans-for-the-weekend", so people did not
 * recognise the channel they had just made.
 */
export function typedChannelName(value: string): string {
  return value.toLowerCase().replace(/\s+/g, "-").replace(/-{2,}/g, "-");
}

/** The finished name: as typed, without dashes left hanging at either end. */
export function slug(name: string): string {
  return typedChannelName(name.trim()).replace(/^-+|-+$/g, "");
}

type Kind = "text" | "voice" | "announcement" | "forum" | "media" | "link";

/** Kinds whose names keep their capitals and spaces, as a voice channel's do. */
const freeName = (k: Kind) => k === "voice" || k === "link";

/** The kinds of channel there are, each with what it is for. */
const KINDS: { kind: Kind; icon: typeof Hash; label: string; desc: string }[] = [
  { kind: "text", icon: Hash, label: "modals.createChannel.text", desc: "modals.createChannel.sendMessagesImagesGifsEmojiOpinions" },
  { kind: "voice", icon: Volume2, label: "modals.createChannel.voice", desc: "modals.createChannel.hangOutTogetherWithVoiceVideo" },
  { kind: "announcement", icon: Megaphone, label: "modals.createChannel.announcement", desc: "modals.createChannel.announcementDesc" },
  { kind: "forum", icon: MessagesSquare, label: "modals.createChannel.forum", desc: "modals.createChannel.forumDesc" },
  { kind: "media", icon: Images, label: "modals.createChannel.media", desc: "modals.createChannel.mediaDesc" },
  { kind: "link", icon: Link2, label: "modals.createChannel.link", desc: "modals.createChannel.linkDesc" },
];

const KIND_ICON: Record<Kind, typeof Hash> = { text: Hash, voice: Volume2, announcement: Megaphone, forum: MessagesSquare, media: Images, link: Link2 };

export function CreateChannelModal({ onClose, serverId, parentId }: { onClose: () => void; serverId: string; parentId?: string | undefined }) {
  const nav = useNavigate();
  const parent = useChannels((c) => (parentId ? c.byId[parentId] : undefined));
  const upsert = useChannels((c) => c.upsert);
  const toast = useUI((u) => u.toast);
  const [type, setTypeState] = useState<Kind>("text");
  // a name typed as a voice channel's becomes a text channel's when the type changes
  const setType = (next: Kind) => {
    setTypeState(next);
    if (!freeName(next)) setName((n) => typedChannelName(n));
  };
  const [name, setName] = useState("");
  const [url, setUrl] = useState("");
  const [urlProblem, setUrlProblem] = useState<string | null>(null);
  const link = type === "link";
  const [priv, setPriv] = useState(false);
  const [busy, setBusy] = useState(false);

  const create = async () => {
    const clean = !freeName(type) ? slug(name) : name.trim();
    if (!clean || busy) return;
    // a link channel opens its address, so it has to be one the apps will open
    const dest = link ? linkDestination(url) : null;
    if (link && !dest) {
      setUrlProblem(t("channelLink.badAddress"));
      return;
    }
    setBusy(true);
    let made: Channel | null = null;
    try {
      const body: { type: Kind; name: string; parent_id?: string | null; url?: string } = { type, name: clean };
      if (dest) body.url = dest.href;
      if (parentId) body.parent_id = parentId;
      const ch = await serversApi.createChannel(serverId, body);
      made = ch;
      upsert(ch);
      // made in a private category, it starts private like it: its key is made now
      if (ch.key_actions?.needs_own_key) {
        await useKeys.getState().ensureConvKey(ch.id);
        upsert(await serversApi.channels(serverId));
      }
      if (priv && !link) {
        // deny @everyone view, then give the channel its own key so members outside it never hold one
        const res = await serversApi.putOverwrite(ch.id, serverId, { type: "role", allow: "0", deny: permString(P.VIEW_CHANNEL) });
        upsert(await serversApi.channels(serverId));
        if (res.key_actions?.needs_own_key) await useKeys.getState().ensureConvKey(ch.id);
        upsert(await serversApi.channels(serverId));
      }
      onClose();
      // a link channel has no page of its own: it waits in the list to be opened
      if (!link) nav(`/channels/${serverId}/${ch.id}`);
    } catch (err) {
      if (made && priv) {
        // It exists but may still be open to everyone or lack its own key. Take it back down, so
        // nothing gets posted in a channel everyone can read and trying again does not make a second one.
        const ch = made;
        const gone = await serversApi.deleteChannel(ch.id).then(() => true, () => false);
        if (gone) {
          useChannels.getState().remove(ch.id);
        } else {
          onClose();
          nav(`/channels/${serverId}/${ch.id}`);
          toast(t("modals.createChannel.theChannelWasCreatedButMay"), "error");
          return;
        }
      }
      toast(err instanceof ApiError ? err.message : t("modals.createChannel.couldNotCreateTheChannel"), "error");
    } finally {
      setBusy(false);
    }
  };

  return (
    <Modal open onClose={onClose} title={t("modals.createChannel.createChannel")} width={460} footer={<><Button variant="ghost" onClick={onClose}>{t("chat.editMessage.cancel")}</Button><Button onClick={() => void create()} loading={busy} disabled={!name.trim() || (link && !url.trim())}>{t("modals.createChannel.createChannel")}</Button></>}>
      <div className={s.form}>
        {parent ? <p className={s.hint}>{t("modals.createChannel.inCategory", { name: parent.name })}</p> : null}
        {/* `as="div"`: the doc comment on Field says it in as many words — a
            label wrapping a group of radios passes a stray tap on its caption,
            or on any gap between the options, to the first radio. */}
        <Field as="div" label={t("modals.createChannel.channelType")}>
          <div className={s.types} role="radiogroup" aria-label={t("modals.createChannel.channelType")}>
            {KINDS.map(({ kind, icon: Icon, label, desc }) => (
              <label key={kind} className={s.type} data-on={type === kind}>
                <span className={s.typeIcon}><Icon size={22} /></span>
                <span className={s.typeText}><span className={s.typeLabel}>{t(label)}</span><span className={s.typeDesc}>{t(desc)}</span></span>
                <input type="radio" name="type" checked={type === kind} onChange={() => setType(kind)} data-testid={`channel-type-${kind}`} />
              </label>
            ))}
          </div>
        </Field>
        <Field label={t("modals.createChannel.channelName")} required>
          <div className={s.hashInput}>
            {(() => {
              const Icon = KIND_ICON[type];
              return <Icon size={16} />;
            })()}
            <TextInput value={name} onChange={(e) => setName(!freeName(type) ? typedChannelName(e.target.value) : e.target.value)} placeholder={link ? t("channelLink.namePlaceholder") : t("modals.createChannel.namePlaceholder")} maxLength={100} autoFocus autoCapitalize={!freeName(type) ? "none" : undefined} onKeyDown={(e) => e.key === "Enter" && void create()} data-testid="channel-name" />
          </div>
        </Field>
        {link ? (
          <Field label={t("channelLink.address")} required hint={t("channelLink.addressHint")} error={urlProblem ?? undefined}>
            <TextInput type="url" inputMode="url" value={url} onChange={(e) => { setUrl(e.target.value); setUrlProblem(null); }} placeholder="https://" maxLength={1024} autoCapitalize="none" spellCheck={false} dir="ltr" onKeyDown={(e) => e.key === "Enter" && void create()} data-testid="channel-url" />
          </Field>
        ) : (
          <>
            <Toggle checked={priv} onChange={setPriv} label={t("modals.createChannel.privateChannel")} description={t("modals.createChannel.onlySelectedMembersAndRolesWill")} />
            {priv ? <p className={s.fine}><Lock size={12} /> {t("modals.createChannel.addRolesOrMembersFromThe")}</p> : null}
          </>
        )}
      </div>
    </Modal>
  );
}
