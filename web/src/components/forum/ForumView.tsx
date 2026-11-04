import { PersonName } from "@/components/common/PersonName";
import { Archive, ChevronDown, ChevronUp, Images, LayoutGrid, LayoutList, MessageSquare, MessagesSquare, Pin, Play, Plus, SlidersHorizontal, Tag as TagIcon } from "lucide-react";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useNavigate } from "react-router-dom";
import { threads as threadsApi } from "@/api/threads";
import type { Channel, ForumTag, ThreadListing } from "@/api/types";
import { Avatar } from "@/components/common/Avatar";
import { ChannelGlyph } from "@/components/common/ChannelGlyph";
import { menuAnchor } from "@/components/common/ContextMenu";
import { SearchBox } from "@/components/common/SearchBox";
import { PageHeaderButtons } from "@/components/shell/PageHeader";
import { dispatchHooks } from "@/gateway/dispatch";
import type { GatewayEvent } from "@/gateway/events";
import { useIsMobile } from "@/hooks/useIsMobile";
import { t } from "@/i18n";
import { useAutomodHeld } from "@/lib/automod/useHeld";
import { decryptForStore, type DecryptedMessage } from "@/lib/decrypt";
import { idTime } from "@/lib/ids";
import { useRenderCtx } from "@/lib/markdownCtx";
import { P } from "@/lib/perms";
import { storage } from "@/lib/storage";
import { formatRelative } from "@/lib/time";
import { Markdown, parse, plainText } from "@/markdown";
import { useChannels } from "@/stores/channels";
import { canI, useServers } from "@/stores/servers";
import { useUI } from "@/stores/ui";
import { button, defineMenu } from "@/lib/contextMenus/registry";
import { useUsers } from "@/stores/users";
import { useShownName } from "@/components/chat/useShownName";
import chat from "@/components/chat/Chat.module.css";
import { firstPicture, usePictureURL } from "./postPicture";
import { NewPostDialog } from "./NewPostDialog";
import s from "./Forum.module.css";

type Sort = "activity" | "created";
type Layout = "list" | "gallery";

/** A forum's View menu: how posts are sorted and laid out (a media channel is always a gallery). */
const forumViewMenu = defineMenu<{ sort: Sort; layout: Layout; media: boolean; setSort: (s: Sort) => void; setLayout: (l: Layout) => void }>("forumView", [
  [
    button("sortForumByActivity", ({ sort, setSort }) => ({ id: "activity", label: t("forum.sort.activity"), checked: sort === "activity", onSelect: () => setSort("activity") })),
    button("sortForumByCreated", ({ sort, setSort }) => ({ id: "created", label: t("forum.sort.created"), checked: sort === "created", onSelect: () => setSort("created") })),
  ],
  [
    button("forumListLayout", ({ layout, media, setLayout }) => !media && ({ id: "list", label: t("forum.layout.list"), checked: layout === "list", onSelect: () => setLayout("list") })),
    button("forumGalleryLayout", ({ layout, media, setLayout }) => !media && ({ id: "gallery", label: t("forum.layout.gallery"), checked: layout === "gallery", onSelect: () => setLayout("gallery") })),
  ],
]);

interface Post {
  thread: ThreadListing;
  first: DecryptedMessage | null;
}

/** When a post last had something said in it. */
function activityOf(th: Channel): number {
  return idTime(th.last_message_id ?? th.id);
}

/** A post's tags, by the forum's own list of them, in that order. */
function tagsOf(th: Channel, all: ForumTag[]): ForumTag[] {
  const on = new Set(th.thread?.applied_tags ?? []);
  return all.filter((tg) => on.has(tg.id));
}

function TagChips({ tags }: { tags: ForumTag[] }) {
  if (!tags.length) return null;
  return (
    <span className={s.tags}>
      {tags.map((tg) => (
        <span key={tg.id} className={s.tag}>
          {tg.emoji ? <span aria-hidden="true">{tg.emoji}</span> : null}
          <span dir="auto">{tg.name}</span>
        </span>
      ))}
    </span>
  );
}

/** One post as the forum lists it: what it is called, its tags, how it starts, and how lively it is. */
function PostCard({ post, tags, layout, pinned, media, onOpen }: { post: Post; tags: ForumTag[]; layout: Layout; pinned: boolean; media: boolean; onOpen: () => void }) {
  const th = useChannels((c) => c.byId[post.thread.id]) ?? post.thread;
  const author = th.owner_id ?? post.first?.author_id ?? "";
  const shown = useShownName(th.id, author || undefined);
  const avatarId = useUsers((u) => (author ? (u.byId[author]?.avatar_id ?? null) : null));
  const held = useAutomodHeld(post.first ?? ({ id: "", channel_id: th.id, author_id: author, type: "system_none", created_at: 0 } as unknown as DecryptedMessage));
  const words = useMemo(() => {
    const text = post.first?.payload?.t ?? "";
    return text ? plainText(parse(text)).replace(/\s+/g, " ").trim() : "";
  }, [post.first]);
  const pic = useMemo(() => (held ? null : firstPicture(post.first?.payload?.att)), [post.first, held]);
  // a media post that is a video with no picture shows that it plays
  const video = useMemo(() => (!held && !pic && media ? (post.first?.payload?.att ?? []).some((a) => a.type.startsWith("video/") && !a.spoiler) : false), [post.first, held, pic, media]);
  const url = usePictureURL(pic);
  const replies = Math.max(0, (th.thread?.message_count ?? 1) - 1);
  const closed = !!th.thread?.archived;
  return (
    <article className={s.post} data-layout={layout} data-media={media || undefined} data-testid="forum-post" data-post={th.id}>
      <button type="button" className={s.postHit} onClick={onOpen} aria-label={th.name} />
      {layout === "gallery" ? (
        <div className={s.galleryPic} data-empty={!pic || undefined} data-video={video || undefined}>
          {url ? <img src={url} alt="" loading="lazy" decoding="async" /> : video ? <span className={s.play}><Play size={22} aria-hidden="true" /></span> : media ? <Images size={28} aria-hidden="true" /> : <MessagesSquare size={28} aria-hidden="true" />}
        </div>
      ) : null}
      <div className={s.postMain}>
        <div className={s.postTop}>
          {pinned ? (
            <span className={s.pinned}>
              <Pin size={13} aria-hidden="true" />
              {t("forum.post.pinned")}
            </span>
          ) : null}
          {closed ? (
            <span className={s.closed}>
              <Archive size={13} aria-hidden="true" />
              {t("threads.chip.closed")}
            </span>
          ) : null}
          <TagChips tags={tags} />
        </div>
        <h3 className={s.postTitle} dir="auto">{th.name}</h3>
        <p className={s.postWords} dir="auto">
          {held ? (
            <span className={s.held}>{t("automod.held.text")}</span>
          ) : post.first?.decryptError ? (
            <span className={s.held}>{t("chat.messageItem.waitingForTheEncryptionKey")}</span>
          ) : (
            <>
              <PersonName userId={author} serverId={th.server_id} className={s.postAuthor}>{shown}</PersonName>
              {words ? <span className={s.postSep} aria-hidden="true">{": "}</span> : null}
              {words}
            </>
          )}
        </p>
        <div className={s.postFoot}>
          <span className={s.postWho}>
            <Avatar id={author} avatarId={avatarId} name={shown} size={18} />
          </span>
          <span className={s.stat} title={t("forum.post.repliesLong", { count: replies })}>
            <MessageSquare size={14} aria-hidden="true" />
            {replies}
          </span>
          <span className={s.when}>{formatRelative(activityOf(th))}</span>
        </div>
      </div>
      {layout === "list" && pic ? (
        <div className={s.thumb}>{url ? <img src={url} alt="" loading="lazy" decoding="async" /> : null}</div>
      ) : null}
    </article>
  );
}

/**
 * A forum: its posts as a list or a gallery, filed under tags, sorted by what
 * is lively or what is new, searchable by title. Its guidelines open at the
 * top, and anyone who can write here can start a post.
 */
export default function ForumView({ channel }: { channel: Channel }) {
  const nav = useNavigate();
  const mobile = useIsMobile();
  const serverId = channel.server_id!;
  const st = useServers((x) => x.byId[serverId]);
  const ctx = useRenderCtx(serverId);
  const forum = channel.forum;
  const allTags = useMemo(() => forum?.tags ?? [], [forum?.tags]);
  const layoutKey = `ec:forumLayout:${channel.id}`;
  // a media channel's posts are pictures and videos, always shown as a gallery
  const media = channel.type === "media";
  const [sort, setSort] = useState<Sort>(forum?.sort ?? "activity");
  const [chosenLayout, setLayoutState] = useState<Layout>(() => {
    const saved = storage.get(layoutKey);
    return saved === "list" || saved === "gallery" ? saved : (forum?.layout ?? "list");
  });
  const layout: Layout = media ? "gallery" : chosenLayout;
  const setLayout = (l: Layout) => {
    setLayoutState(l);
    storage.set(layoutKey, l);
  };
  const [q, setQ] = useState("");
  const [filter, setFilter] = useState<string[]>([]);
  const [posts, setPosts] = useState<Post[] | null>(null);
  const [pinnedId, setPinnedId] = useState<string | null>(null);
  const [more, setMore] = useState(false);
  const [failed, setFailed] = useState(false);
  const [closed, setClosed] = useState<Post[] | null>(null);
  const [closedMore, setClosedMore] = useState(false);
  const [guidelines, setGuidelines] = useState(() => storage.get(`ec:forumGuide:${channel.id}`) !== "0");
  const [writing, setWriting] = useState(false);
  const gen = useRef(0);
  // a media post is made of files, so posting there also needs Attach Files
  const canPost = canI(st, P.SEND_MESSAGES, channel.id) && (!media || canI(st, P.ATTACH_FILES, channel.id));

  const decrypt = useCallback(async (list: ThreadListing[]): Promise<Post[]> => {
    return Promise.all(list.map(async (th) => ({ thread: th, first: th.first_message ? await decryptForStore(th.first_message) : null })));
  }, []);

  const load = useCallback(
    async (reset: boolean) => {
      const mine = ++gen.current;
      setFailed(false);
      try {
        const last = reset ? undefined : posts?.[posts.length - 1]?.thread;
        const before = last ? (sort === "created" ? last.id : (last.last_message_id ?? last.id)) : undefined;
        const res = await threadsApi.list(channel.id, { sort, limit: 30, ...(q.trim() ? { q: q.trim() } : {}), ...(filter.length ? { tags: filter } : {}), ...(before ? { before } : {}) });
        useUsers.getState().upsert(res.users);
        const decrypted = await decrypt(res.threads);
        if (mine !== gen.current) return;
        setPosts((cur) => (reset || !cur ? decrypted : [...cur, ...decrypted]));
        setMore(res.has_more);
        if (reset) setPinnedId(res.pinned_post ?? null);
        if (reset) setClosed(null);
      } catch {
        if (mine === gen.current) setFailed(true);
      }
    },
    [channel.id, sort, q, filter, posts, decrypt],
  );

  // asked again when what is asked for changes, a moment after typing stops
  useEffect(() => {
    const timer = setTimeout(() => void load(true), q ? 300 : 0);
    return () => clearTimeout(timer);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [channel.id, sort, q, filter.join(",")]);

  // posts started, closed, or taken away while the forum is open
  useEffect(() => {
    let timer: ReturnType<typeof setTimeout> | null = null;
    const hook = (e: GatewayEvent) => {
      if (e.t !== "thread_create" && e.t !== "thread_delete") return;
      const parent = (e.d as { parent_id?: string | null }).parent_id;
      if (parent !== channel.id) return;
      if (timer) clearTimeout(timer);
      timer = setTimeout(() => void load(true), 400);
    };
    dispatchHooks.onUnhandled.push(hook);
    return () => {
      if (timer) clearTimeout(timer);
      dispatchHooks.onUnhandled.splice(dispatchHooks.onUnhandled.indexOf(hook), 1);
    };
  }, [channel.id, load]);

  const loadClosed = async () => {
    try {
      const last = closed?.[closed.length - 1]?.thread;
      const res = await threadsApi.list(channel.id, { archived: true, limit: 30, ...(q.trim() ? { q: q.trim() } : {}), ...(filter.length ? { tags: filter } : {}), ...(last?.thread?.archived_at ? { before: last.thread.archived_at } : {}) });
      useUsers.getState().upsert(res.users);
      const decrypted = await decrypt(res.threads);
      setClosed((cur) => [...(cur ?? []), ...decrypted]);
      setClosedMore(res.has_more);
    } catch {
      useUI.getState().toast(t("forum.closedFailed"), "error");
    }
  };

  // the pinned post first, wherever the order puts it
  const ordered = useMemo(() => {
    if (!posts) return null;
    const pin = pinnedId ? posts.find((p) => p.thread.id === pinnedId) : undefined;
    return pin ? [pin, ...posts.filter((p) => p !== pin)] : posts;
  }, [posts, pinnedId]);

  const open = (th: Channel) => {
    useChannels.getState().upsert(th);
    nav(`/channels/${serverId}/${th.id}`);
  };
  const toggleTag = (id: string) => setFilter((cur) => (cur.includes(id) ? cur.filter((x) => x !== id) : [...cur, id]));
  const viewMenu = (el: HTMLElement) => {
    const anchor = menuAnchor(el, "end");
    forumViewMenu.open({ sort, layout, media, setSort, setLayout }, { x: anchor.right, y: anchor.bottom + 4, anchor, label: t("forum.view.title") });
  };
  const empty = ordered !== null && ordered.length === 0;

  return (
    <div className={chat.chat}>
      <header className={chat.header} data-chat-header="">
        <PageHeaderButtons />
        <span className={chat.hIcon}><ChannelGlyph channel={channel} size={22} /></span>
        <span className={chat.hName} dir="auto">{channel.name}</span>
        <span className={chat.hSpacer} />
        {canPost ? (
          <button type="button" className={[s.newPost, s.headerNew].join(" ")} onClick={() => setWriting(true)} aria-label={t("forum.newPost")} data-testid="forum-new-post">
            <Plus size={18} aria-hidden="true" />
            <span>{t("forum.newPost")}</span>
          </button>
        ) : null}
      </header>
      <div className={s.scroll}>
        <div className={s.inner}>
          {channel.topic ? (
            <section className={s.guide} data-open={guidelines || undefined}>
              <button
                type="button"
                className={s.guideHead}
                aria-expanded={guidelines}
                onClick={() => {
                  setGuidelines((v) => !v);
                  storage.set(`ec:forumGuide:${channel.id}`, guidelines ? "0" : "1");
                }}
              >
                <span>{t("forum.settings.guidelines")}</span>
                {guidelines ? <ChevronUp size={18} aria-hidden="true" /> : <ChevronDown size={18} aria-hidden="true" />}
              </button>
              {guidelines ? (
                <div className={s.guideBody}>
                  <Markdown source={channel.topic} ctx={ctx} />
                </div>
              ) : null}
            </section>
          ) : null}
          <div className={s.tools}>
            <SearchBox className={s.search} value={q} onChange={setQ} placeholder={t("forum.search")} aria-label={t("forum.search")} />
            {mobile ? (
              <button type="button" className={s.toolBtn} onClick={(e) => viewMenu(e.currentTarget)} aria-label={t("forum.view.title")}>
                <SlidersHorizontal size={18} aria-hidden="true" />
              </button>
            ) : (
              <>
                <label className={s.sortLabel}>
                  <span>{t("forum.view.sortBy")}</span>
                  <select className={s.sortSelect} value={sort} onChange={(e) => setSort(e.target.value as Sort)}>
                    <option value="activity">{t("forum.sort.activity")}</option>
                    <option value="created">{t("forum.sort.created")}</option>
                  </select>
                </label>
                {media ? null : <div className={s.layoutSwitch} role="radiogroup" data-glide="" aria-label={t("forum.settings.layout")}>
                  <button type="button" role="radio" aria-checked={layout === "list"} aria-label={t("forum.layout.list")} title={t("forum.layout.list")} onClick={() => setLayout("list")}>
                    <LayoutList size={18} aria-hidden="true" />
                  </button>
                  <button type="button" role="radio" aria-checked={layout === "gallery"} aria-label={t("forum.layout.gallery")} title={t("forum.layout.gallery")} onClick={() => setLayout("gallery")}>
                    <LayoutGrid size={18} aria-hidden="true" />
                  </button>
                </div>}
              </>
            )}
          </div>
          {allTags.length ? (
            <div className={s.filter} role="group" aria-label={t("forum.filterByTag")}>
              <TagIcon size={16} className={s.filterIcon} aria-hidden="true" />
              {allTags.map((tg) => (
                <button key={tg.id} type="button" className={s.filterChip} aria-pressed={filter.includes(tg.id)} onClick={() => toggleTag(tg.id)}>
                  {tg.emoji ? <span aria-hidden="true">{tg.emoji}</span> : null}
                  <span dir="auto">{tg.name}</span>
                </button>
              ))}
              {filter.length ? (
                <button type="button" className={s.clearFilter} onClick={() => setFilter([])}>{t("forum.clearTags")}</button>
              ) : null}
            </div>
          ) : null}
          {failed ? (
            <div className={s.note}>
              {t("forum.failed")}{" "}
              <button type="button" className={chat.loadRetry} onClick={() => void load(true)}>{t("chat.messageList.tryAgain")}</button>
            </div>
          ) : null}
          {ordered === null && !failed ? <div className={s.note}>{t("forum.loading")}</div> : null}
          {empty ? (
            <div className={s.empty}>
              {media ? <Images size={36} aria-hidden="true" /> : <MessagesSquare size={36} aria-hidden="true" />}
              <h2>{q || filter.length ? t("forum.noMatches") : t("forum.noPosts")}</h2>
              <p>{q || filter.length ? t("forum.noMatchesHint") : canPost ? t(media ? "media.noPostsHint" : "forum.noPostsHint") : t(media ? "media.noPostsReadOnly" : "forum.noPostsReadOnly")}</p>
              {!q && !filter.length && canPost ? (
                <button type="button" className={s.newPost} onClick={() => setWriting(true)}>
                  <Plus size={18} aria-hidden="true" />
                  <span>{t("forum.newPost")}</span>
                </button>
              ) : null}
            </div>
          ) : null}
          {ordered?.length ? (
            <div className={s.list} data-layout={layout} data-media={media || undefined}>
              {ordered.map((p) => (
                <PostCard key={p.thread.id} post={p} tags={tagsOf(p.thread, allTags)} layout={layout} pinned={p.thread.id === pinnedId} media={media} onOpen={() => open(p.thread)} />
              ))}
            </div>
          ) : null}
          {more ? (
            <button type="button" className={s.more} onClick={() => void load(false)}>{t("forum.loadMore")}</button>
          ) : null}
          {ordered !== null && !more ? (
            closed === null ? (
              <button type="button" className={s.more} onClick={() => void loadClosed()} data-testid="forum-closed">
                <Archive size={16} aria-hidden="true" />
                {t("forum.showClosed")}
              </button>
            ) : (
              <>
                {closed.length ? <h2 className={s.closedHead}>{t("forum.closedPosts")}</h2> : <div className={s.note}>{t("forum.noClosed")}</div>}
                {closed.length ? (
                  <div className={s.list} data-layout={layout} data-media={media || undefined}>
                    {closed.map((p) => (
                      <PostCard key={p.thread.id} post={p} tags={tagsOf(p.thread, allTags)} layout={layout} pinned={false} media={media} onOpen={() => open(p.thread)} />
                    ))}
                  </div>
                ) : null}
                {closedMore ? <button type="button" className={s.more} onClick={() => void loadClosed()}>{t("forum.loadMore")}</button> : null}
              </>
            )
          ) : null}
        </div>
      </div>
      {writing ? <NewPostDialog forum={channel} onClose={() => setWriting(false)} /> : null}
    </div>
  );
}
