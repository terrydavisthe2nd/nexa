import { useCallback, useEffect, useId, useMemo, useRef, useState, type MouseEvent } from "react";
import { useNavigate } from "react-router-dom";
import { mentions as mentionsApi, type MentionKind } from "@/api/mentions";
import { users as usersApi } from "@/api/users";
import { Avatar } from "@/components/common/Avatar";
import { ChannelGlyph } from "@/components/common/ChannelGlyph";
import { Modal } from "@/components/common/Modal";
import { Select } from "@/components/common/Select";
import { ServerIcon } from "@/components/moderation/ProfileSheet";
import { MessageBody } from "@/components/chat/MessageItem";
import { useShownName } from "@/components/chat/useShownName";
import { HookAvatar, HookTags } from "@/components/chat/webhook/HookHeader";
import { t } from "@/i18n";
import { decryptForStore, type DecryptedMessage } from "@/lib/decrypt";
import { compareIds } from "@/lib/ids";
import { RenderCtxProvider, useRenderCtx } from "@/lib/markdownCtx";
import { appendPage, dayOf, mentionOf } from "@/lib/mentions";
import { channelDisplayName } from "@/lib/names";
import { nameClass, nameStyle, roleColors } from "@/lib/roleLook";
import { formatDateDivider, formatFull, formatTimestamp } from "@/lib/time";
import { useMemberLook } from "@/lib/useMemberLook";
import { useAuth } from "@/stores/auth";
import { useChannels } from "@/stores/channels";
import { useReadStates } from "@/stores/readStates";
import { useServers } from "@/stores/servers";
import { useUI } from "@/stores/ui";
import { useUsers } from "@/stores/users";
import s from "./MentionsModal.module.css";

type Kind = "all" | MentionKind;
interface View {
  kind: Kind;
  /** "all", "@me", or a server's id */
  place: string;
  sort: "newest" | "oldest";
}

const PAGE = 25;
const VIEW_KEY = "nexa.mentionsView";
const DEFAULT_VIEW: View = { kind: "all", place: "all", sort: "newest" };

/** The filters last used on this device, a convenience only. */
function savedView(): View {
  try {
    const v = JSON.parse(localStorage.getItem(VIEW_KEY) ?? "null") as Partial<View> | null;
    if (!v) return DEFAULT_VIEW;
    return {
      kind: (["all", "me", "roles", "everyone"] as const).includes(v.kind as Kind) ? (v.kind as Kind) : "all",
      place: typeof v.place === "string" ? v.place : "all",
      sort: v.sort === "oldest" ? "oldest" : "newest",
    };
  } catch {
    return DEFAULT_VIEW;
  }
}

function saveView(v: View): void {
  try {
    localStorage.setItem(VIEW_KEY, JSON.stringify(v));
  } catch {
    // private windows: the filters reset next time
  }
}

/** Where a mention was: the server and channel, or the conversation. */
function Place({ channelId }: { channelId: string }) {
  const ch = useChannels((c) => c.byId[channelId]);
  const server = useServers((st) => (ch?.server_id ? st.byId[ch.server_id]?.server : undefined));
  const users = useUsers((u) => u.byId);
  const meId = useAuth((a) => a.me?.id);
  if (!ch) return <span className={s.place}>{t("mentions.unknownPlace")}</span>;
  if (server) {
    return (
      <span className={s.place}>
        <ServerIcon id={server.id} iconId={server.icon_id} name={server.name} size={18} />
        <span className={s.placeServer} dir="auto">{server.name}</span>
        <span className={s.placeChannel}>
          <ChannelGlyph channel={ch} size={14} />
          <span dir="auto">{ch.name}</span>
        </span>
      </span>
    );
  }
  const other = ch.type === "dm" ? ch.recipient_ids.find((id) => id !== meId) : undefined;
  const name = channelDisplayName(ch, users, meId);
  return (
    <span className={s.place}>
      {other ? <Avatar id={other} avatarId={users[other]?.avatar_id ?? null} name={name} size={18} /> : <ChannelGlyph channel={ch} size={16} />}
      <span className={s.placeServer} dir="auto">{name}</span>
    </span>
  );
}

/** Why this one is yours (you by name, a role of yours, or everyone), and the role's colour for its edge and tag. */
function useWhy(msg: DecryptedMessage): { kind: "me" | "role" | "everyone"; label: string; colour?: string | undefined } {
  const meId = useAuth((a) => a.me?.id ?? "");
  const serverId = useChannels((c) => c.byId[msg.channel_id]?.server_id ?? null);
  const st = useServers((x) => (serverId ? x.byId[serverId] : undefined));
  const why = mentionOf(msg, meId, st?.me?.role_ids ?? []);
  if (why.kind === "me") return { kind: "me", label: t("mentions.tagYou") };
  if (why.kind === "everyone") return { kind: "everyone", label: t("mentions.tagEveryone") };
  const role = st?.roles[why.roleId];
  return { kind: "role", label: `@${role?.name ?? t("mentions.deletedRole")}`, colour: role ? roleColors(role)[0] : undefined };
}

function MentionRow({ msg, unread, onJump }: { msg: DecryptedMessage; unread: boolean; onJump: () => void }) {
  const hook = msg.hook;
  const byHook = !!hook && !!msg.webhook_id;
  const serverId = useChannels((c) => c.byId[msg.channel_id]?.server_id ?? null);
  const avatarId = useUsers((u) => u.byId[msg.author_id]?.avatar_id ?? null);
  const shownName = useShownName(msg.channel_id, byHook ? undefined : msg.author_id);
  const name = byHook ? hook!.name : shownName;
  const look = useMemberLook(serverId, msg.webhook_id ? undefined : msg.author_id);
  const ctx = useRenderCtx(serverId);
  const whoId = useId();
  const why = useWhy(msg);
  // a press anywhere that is not a link, a button or a file goes to the message
  const onRow = (e: MouseEvent<HTMLElement>) => {
    const target = e.target as Element;
    if (target.closest("a, button, input, video, audio, img, [role=button], [data-no-jump]")) return;
    if (window.getSelection()?.toString()) return;
    onJump();
  };
  return (
    <article className={s.row} aria-labelledby={whoId} onClick={onRow} data-kind={why.kind} style={why.colour ? { ["--role" as string]: why.colour } : undefined} data-testid="mention" data-mention={msg.id} data-unread={unread || undefined}>
      <div className={s.rowHead}>
        <Place channelId={msg.channel_id} />
        <span className={s.tag} data-kind={why.kind}>{why.label}</span>
        {unread ? <span className={s.new}>{t("mentions.new")}</span> : null}
        <button type="button" className={s.jump} onClick={onJump} data-testid="mention-jump">
          {t("chat.pins.jump")}
        </button>
      </div>
      <div className={s.msg}>
        <span className={s.avatar} aria-hidden="true">
          {byHook ? <HookAvatar hook={hook!} webhookId={msg.webhook_id!} /> : <Avatar id={msg.author_id} avatarId={avatarId} name={name} size={36} />}
        </span>
        <div className={s.main}>
          <div className={s.who} id={whoId}>
            <span className={[s.name, byHook ? "" : nameClass(look)].join(" ")} style={byHook ? undefined : nameStyle(look)} dir="auto">{name}</span>
            {hook ? <HookTags app={!!hook.application_id} /> : null}
            <time className={s.time} dateTime={new Date(msg.created_at).toISOString()} title={formatFull(msg.created_at)}>
              {formatTimestamp(msg.created_at)}
            </time>
          </div>
          <RenderCtxProvider value={ctx}>
            <MessageBody msg={msg} name={name} />
          </RenderCtxProvider>
        </div>
      </div>
    </article>
  );
}

/**
 * Mentions: every message that pinged this account, from conversations and
 * servers, narrowed to who was pinged (you, your roles, everyone) and where,
 * newest or oldest first, under a line for each day. A press goes to the
 * message. The server finds them; this device opens them.
 */
export function MentionsModal({ onClose }: { onClose: () => void }) {
  const [view, setView] = useState<View>(savedView);
  const [items, setItems] = useState<DecryptedMessage[]>([]);
  const [state, setState] = useState<"loading" | "idle" | "failed" | "end">("loading");
  const [attempt, setAttempt] = useState(0);
  const req = useRef(0);
  const list = useRef<HTMLDivElement>(null);
  const sentinel = useRef<HTMLDivElement>(null);
  const nav = useNavigate();
  const servers = useServers((st) => st.byId);
  const order = useServers((st) => st.order);
  const reads = useReadStates((r) => r.byChannel);
  const kindLabel = useId();

  // a server left since the filter was chosen is not there to pick
  const place = view.place === "all" || view.place === "@me" || servers[view.place] ? view.place : "all";

  const load = useCallback(
    async (from: DecryptedMessage[]) => {
      const mine = ++req.current;
      setState("loading");
      try {
        const page = await mentionsApi.list({
          kinds: view.kind === "all" ? undefined : [view.kind],
          place: place === "all" ? undefined : place,
          sort: view.sort,
          cursor: from[from.length - 1]?.id,
          limit: PAGE,
        });
        const dec = await Promise.all(page.map(decryptForStore));
        if (mine !== req.current) return;
        setItems(appendPage(from, dec));
        setState(page.length < PAGE ? "end" : "idle");
        // an old mention can be by someone this device has not met yet
        const known = useUsers.getState().byId;
        for (const id of new Set(dec.filter((m) => !m.webhook_id).map((m) => m.author_id))) {
          if (!known[id]) void usersApi.get(id).then((u) => useUsers.getState().upsert(u), () => undefined);
        }
      } catch {
        if (mine === req.current) setState("failed");
      }
    },
    [view.kind, view.sort, place],
  );

  // a new filter starts the list again from the top
  useEffect(() => {
    setItems([]);
    list.current?.scrollTo({ top: 0 });
    void load([]);
    saveView({ ...view, place });
  }, [load, attempt]); // eslint-disable-line react-hooks/exhaustive-deps

  // the next page comes in as the end of the list comes into sight
  const itemsRef = useRef(items);
  itemsRef.current = items;
  useEffect(() => {
    const el = sentinel.current;
    if (!el || state !== "idle" || typeof IntersectionObserver === "undefined") return;
    const io = new IntersectionObserver((entries) => {
      if (entries.some((e) => e.isIntersecting)) void load(itemsRef.current);
    }, { root: list.current, rootMargin: "300px" });
    io.observe(el);
    return () => io.disconnect();
  }, [state, load]);

  const jump = (msg: DecryptedMessage) => {
    const ch = useChannels.getState().byId[msg.channel_id];
    const serverId = ch?.server_id;
    onClose();
    nav(serverId ? `/channels/${serverId}/${msg.channel_id}/${msg.id}` : `/channels/@me/${msg.channel_id}/${msg.id}`);
    useUI.getState().setJumpTo({ channelId: msg.channel_id, messageId: msg.id });
  };

  const placeOptions = useMemo(
    () => order.filter((id) => servers[id]).map((id) => ({ id, name: servers[id]!.server.name })),
    [order, servers],
  );
  const kinds: { value: Kind; label: string }[] = [
    { value: "all", label: t("mentions.kindAll") },
    { value: "me", label: t("mentions.kindMe") },
    { value: "roles", label: t("mentions.kindRoles") },
    { value: "everyone", label: t("mentions.kindEveryone") },
  ];
  const filtered = view.kind !== "all" || place !== "all";

  let lastDay = "";
  return (
    <Modal open onClose={onClose} title={t("mentions.title")} width={760} className={s.modal}>
      <div className={s.bar}>
        <div className={s.kinds} role="radiogroup" data-glide="" aria-labelledby={kindLabel}>
          <span id={kindLabel} className={s.hidden}>{t("mentions.kindLabel")}</span>
          {kinds.map((k) => (
            <button
              key={k.value}
              type="button"
              role="radio"
              aria-checked={view.kind === k.value}
              className={s.kind}
              data-kind={k.value}
              onClick={() => setView((v) => ({ ...v, kind: k.value }))}
              data-testid={`mentions-kind-${k.value}`}
            >
              {k.label}
            </button>
          ))}
        </div>
        <div className={s.pickers}>
          <label className={s.picker}>
            <span className={s.pickerLabel}>{t("mentions.placeLabel")}</span>
            <Select value={place} onChange={(e) => setView((v) => ({ ...v, place: e.target.value }))} data-testid="mentions-place">
              <option value="all">{t("mentions.placeAll")}</option>
              <option value="@me">{t("mentions.placeDms")}</option>
              {placeOptions.map((o) => (
                <option key={o.id} value={o.id}>{o.name}</option>
              ))}
            </Select>
          </label>
          <label className={s.picker}>
            <span className={s.pickerLabel}>{t("mentions.sortLabel")}</span>
            <Select value={view.sort} onChange={(e) => setView((v) => ({ ...v, sort: e.target.value === "oldest" ? "oldest" : "newest" }))} data-testid="mentions-sort">
              <option value="newest">{t("mentions.newest")}</option>
              <option value="oldest">{t("mentions.oldest")}</option>
            </Select>
          </label>
        </div>
      </div>
      <div className={s.list} ref={list} data-testid="mentions-list">
        {items.map((m) => {
          const day = dayOf(m.created_at);
          const divider = day !== lastDay ? <div className={s.day} role="separator"><span>{formatDateDivider(m.created_at)}</span></div> : null;
          lastDay = day;
          const lastRead = reads[m.channel_id]?.lastReadId;
          const unread = !lastRead || compareIds(m.id, lastRead) > 0;
          return (
            <div key={m.id}>
              {divider}
              <MentionRow msg={m} unread={unread} onJump={() => jump(m)} />
            </div>
          );
        })}
        {state === "failed" ? (
          <div className={s.empty}>
            {t("mentions.failed")}{" "}
            <button type="button" className={s.retry} onClick={() => (items.length ? void load(items) : setAttempt((n) => n + 1))} data-testid="mentions-retry">{t("chat.messageList.tryAgain")}</button>
          </div>
        ) : state === "loading" ? (
          <div className={s.empty}>{t("chat.pins.loading")}</div>
        ) : items.length === 0 ? (
          <div className={s.empty} data-testid="mentions-empty">
            <p className={s.emptyTitle}>{filtered ? t("mentions.emptyFiltered") : t("mentions.empty")}</p>
            {filtered ? (
              <button type="button" className={s.retry} onClick={() => setView((v) => ({ ...v, kind: "all", place: "all" }))}>{t("mentions.showAll")}</button>
            ) : null}
          </div>
        ) : state === "idle" ? (
          <div ref={sentinel} className={s.more}>
            <button type="button" className={s.retry} onClick={() => void load(items)}>{t("mentions.loadMore")}</button>
          </div>
        ) : (
          <div className={s.end}>{t("mentions.end")}</div>
        )}
      </div>
    </Modal>
  );
}

export default MentionsModal;
