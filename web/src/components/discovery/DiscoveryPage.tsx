import { openReport } from "@/lib/reporting";
import { PersonName } from "@/components/common/PersonName";
import { useMediaAnimation } from "@/lib/mediaAnimation";
import { TriangleAlert, Compass, Users, X } from "lucide-react";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { useNavigate } from "react-router-dom";
import { ApiError } from "@/api/client";
import { discovery as discoveryApi, type DiscoveryQuery } from "@/api/discovery";
import type { DiscoveryCard, PublicUser } from "@/api/types";
import { bannerUrl, emojiUrl, iconUrl } from "@/api/users";
import { Toggle } from "@/components/common/Toggle";
import { Button } from "@/components/common/Button";
import { Modal } from "@/components/common/Modal";
import { SearchBox } from "@/components/common/SearchBox";
import { PageHeaderButtons } from "@/components/shell/PageHeader";
import { t, Tr } from "@/i18n";
import { LANGUAGES } from "@/i18n/core";
import { avatarColor, initials } from "@/lib/avatarColor";
import { guarded } from "@/lib/captcha";
import { useDominantColor } from "@/lib/dominantColor";
import { useRenderCtx } from "@/lib/markdownCtx";
import { Markdown, parse, plainText } from "@/markdown";
import { useChannels } from "@/stores/channels";
import { useServers } from "@/stores/servers";
import { useUI } from "@/stores/ui";
import { useUsers } from "@/stores/users";
import chat from "@/components/chat/Chat.module.css";
import s from "./Discovery.module.css";

type Sort = NonNullable<DiscoveryQuery["sort"]>;
const SIZES: { id: string; min?: number; max?: number }[] = [{ id: "any" }, { id: "small", max: 50 }, { id: "medium", min: 50, max: 500 }, { id: "large", min: 500 }];
const LANGUAGE_NAMES: Record<string, string> = Object.fromEntries(LANGUAGES.map((l) => [l.tag, l.endonym]));

function count(n: number): string {
  return new Intl.NumberFormat(undefined, { notation: n >= 10_000 ? "compact" : "standard" }).format(n);
}

/** A server's colour where it has no banner: from its icon, or its own steady colour. */
function useBand(card: Pick<DiscoveryCard, "id" | "icon_id" | "banner_id">): React.CSSProperties {
  const icon = iconUrl(card.icon_id, 64);
  const tint = useDominantColor(card.banner_id ? null : icon);
  const banner = bannerUrl(card.banner_id, 600);
  if (banner) return { backgroundImage: `url(${banner})` };
  const c = tint ?? avatarColor(card.id);
  return { "--band": `linear-gradient(135deg, ${c}, color-mix(in srgb, ${c} 55%, #000))` } as React.CSSProperties;
}

function ServerIcon({ card, className }: { card: DiscoveryCard; className: string | undefined }) {
  useMediaAnimation();
  const icon = iconUrl(card.icon_id, 96);
  return (
    <div className={className} style={{ "--icon-bg": avatarColor(card.id) } as React.CSSProperties} aria-hidden="true">
      {icon ? <img src={icon} alt="" loading="lazy" decoding="async" /> : initials(card.name)}
    </div>
  );
}

export function ServerCard({ card, onOpen, onTag }: { card: DiscoveryCard; onOpen: () => void; onTag: (tag: string) => void }) {
  const band = useBand(card);
  const words = useMemo(() => plainText(parse(card.description)).replace(/\s+/g, " ").trim(), [card.description]);
  return (
    <article className={s.card} data-testid="discovery-card">
      <button type="button" className={s.hit} onClick={onOpen} aria-label={card.name} />
      <div className={s.band} style={band} />
      <ServerIcon card={card} className={s.icon} />
      <div className={s.body}>
        <h3 className={s.name} dir="auto">
          <span>{card.name}</span>
          {card.nsfw ? <span className={s.badge} data-nsfw="">{t("nsfw.label")}</span> : null}
          {card.member ? <span className={s.badge}>{t("discovery.joined")}</span> : card.joins_paused ? <span className={s.badge} data-paused="">{t("discovery.paused")}</span> : null}
        </h3>
        {words ? <p className={s.desc} dir="auto">{words}</p> : null}
        {card.tags.some((tg) => tg.toLowerCase() !== "nsfw") ? (
          <div className={s.cardTags}>
            {card.tags.filter((tg) => tg.toLowerCase() !== "nsfw").map((tg) => (
              <button key={tg} type="button" className={s.tagBtn} style={{ position: "relative", zIndex: 2 }} onClick={() => onTag(tg)}>{tg.toLowerCase() === "nsfw" ? t("nsfw.label") : tg}</button>
            ))}
          </div>
        ) : null}
        <div className={s.stats}>
          <span className={s.stat}><span className={s.dot} data-online="" />{t("discovery.online", { count: card.online_count, shown: count(card.online_count) })}</span>
          <span className={s.stat}><span className={s.dot} />{t("discovery.members", { count: card.member_count, shown: count(card.member_count) })}</span>
        </div>
      </div>
    </article>
  );
}

/** One server, opened from Discovery: everything its listing says, and the way in. */
function ServerSheet({ card, onClose }: { card: DiscoveryCard; onClose: () => void }) {
  useMediaAnimation();
  const nav = useNavigate();
  const ctx = useRenderCtx();
  const band = useBand(card);
  const [emojis, setEmojis] = useState<{ id: string; blob_id: string; name: string; animated: boolean }[]>([]);
  const [owner, setOwner] = useState<PublicUser | null>(null);
  const [busy, setBusy] = useState(false);
  useEffect(() => {
    let alive = true;
    void discoveryApi
      .server(card.id, !!card.nsfw)
      .then((res) => {
        if (!alive) return;
        setEmojis(res.emojis ?? []);
        setOwner(res.owner ?? null);
      })
      .catch(() => undefined);
    return () => {
      alive = false;
    };
  }, [card.id]);
  const firstChannel = () => {
    const chans = Object.values(useChannels.getState().byId).filter((c) => c.server_id === card.id && (c.type === "text" || c.type === "announcement"));
    chans.sort((a, b) => a.position - b.position);
    return chans[0];
  };
  const join = async () => {
    if (card.member) {
      onClose();
      const first = firstChannel();
      nav(first ? `/channels/${card.id}/${first.id}` : `/channels/${card.id}`);
      return;
    }
    setBusy(true);
    try {
      const res = await guarded("join", (captcha) => discoveryApi.join(card.id, captcha, !!card.nsfw));
      useUsers.getState().upsert(res.users);
      useChannels.getState().upsert(res.server.channels);
      useServers.getState().setFromView(res.server);
      onClose();
      const first = res.server.channels.find((c) => c.type === "text" || c.type === "announcement");
      nav(first ? `/channels/${card.id}/${first.id}` : `/channels/${card.id}`);
      useUI.getState().toast(t("discovery.welcome", { name: card.name }), "success");
    } catch (err) {
      useUI.getState().toast(err instanceof ApiError ? err.message : t("discovery.joinFailed"), "error");
    } finally {
      setBusy(false);
    }
  };
  return (
    <Modal
      open
      onClose={onClose}
      ariaLabel={card.name}
      width={560}
      footer={
        <>
          <Button variant="ghost" onClick={() => openReport({ kind: "server", id: card.id })}>{t("reports.report")}</Button>
          <Button variant="ghost" onClick={onClose}>{t("discovery.close")}</Button>
          <Button onClick={() => void join()} loading={busy} disabled={card.joins_paused && !card.member} data-testid="discovery-join">
            {card.member ? t("discovery.open") : t("discovery.join")}
          </Button>
        </>
      }
    >
      <div className={s.sheetBand} style={band} />
      <div className={s.sheetHead}>
        <ServerIcon card={card} className={s.sheetIcon} />
      </div>
      <div className={s.sheetBody}>
        <div>
          <h2 className={s.sheetName} dir="auto">{card.name} {card.nsfw ? <span className={s.badge} data-nsfw="">{t("nsfw.label")}</span> : null}</h2>
          <div className={s.facts}>
            <span className={s.stat}><span className={s.dot} data-online="" />{t("discovery.online", { count: card.online_count, shown: count(card.online_count) })}</span>
            <span className={s.stat}><Users size={14} aria-hidden="true" />{t("discovery.members", { count: card.member_count, shown: count(card.member_count) })}</span>
            {card.category ? <span>{t(`discovery.cat.${card.category}`)}</span> : null}
            {card.language ? <span>{LANGUAGE_NAMES[card.language] ?? card.language}</span> : null}
          </div>
        </div>
        {card.description ? (
          <div className={s.sheetDesc} dir="auto">
            <Markdown source={card.description} ctx={ctx} />
          </div>
        ) : null}
        {card.tags.some((tg) => tg.toLowerCase() !== "nsfw") ? (
          <div className={s.cardTags}>
            {card.tags.filter((tg) => tg.toLowerCase() !== "nsfw").map((tg) => <span key={tg} className={s.cardTag}>{tg.toLowerCase() === "nsfw" ? t("nsfw.label") : tg}</span>)}
          </div>
        ) : null}
        {emojis.length ? (
          <div className={s.emojis} aria-label={t("discovery.emojis")}>
            {emojis.map((e) => <img key={e.id} src={emojiUrl(e.blob_id, e.animated, 32)} alt={`:${e.name}:`} title={`:${e.name}:`} loading="lazy" />)}
          </div>
        ) : null}
        {owner ? <div className={s.facts}>{<Tr id="discovery.ownedBy" values={{ name: <PersonName user={owner}>{owner.display_name}</PersonName> }} />}</div> : null}
        {card.joins_paused && !card.member ? <div className={s.facts}>{t("discovery.pausedLong")}</div> : null}
      </div>
    </Modal>
  );
}

/**
 * Discovery: every server whose owners listed it, searched by name, words
 * and tags, filed by category, and sorted by size or by how new it is to the
 * list. Anyone can list a server; nothing has to be earned first.
 */
export default function DiscoveryPage() {
  const [q, setQ] = useState("");
  const [showNSFW, setShowNSFW] = useState(false);
  const [category, setCategory] = useState("");
  const [tag, setTag] = useState("");
  const [language, setLanguage] = useState("");
  const [size, setSize] = useState("any");
  const [sort, setSort] = useState<Sort>("members");
  const [cards, setCards] = useState<DiscoveryCard[] | null>(null);
  const [next, setNext] = useState<number | null>(null);
  const [failed, setFailed] = useState(false);
  const [meta, setMeta] = useState<{ categories: string[]; counts: Record<string, number>; total: number; tags: string[]; languages: string[] } | null>(null);
  const [open, setOpen] = useState<DiscoveryCard | null>(null);
  const gen = useRef(0);

  useEffect(() => {
    let alive = true;
    void discoveryApi.categories(showNSFW).then((meta) => { if (alive) setMeta(meta); }).catch(() => undefined);
    return () => { alive = false; };
  }, [showNSFW]);

  const query = useMemo((): DiscoveryQuery => {
    const sz = SIZES.find((x) => x.id === size);
    return {
      ...(q.trim() ? { q: q.trim() } : {}),
      ...(category ? { category } : {}),
      ...(tag ? { tag } : {}),
      ...(language ? { language } : {}),
      ...(sz?.min ? { min_members: sz.min } : {}),
      ...(sz?.max ? { max_members: sz.max } : {}),
      sort,
      nsfw: showNSFW ? 1 : undefined,
      limit: 24,
    };
  }, [q, category, tag, language, size, sort, showNSFW]);

  const load = useCallback(
    async (offset: number) => {
      const mine = ++gen.current;
      setFailed(false);
      try {
        const res = await discoveryApi.search({ ...query, ...(offset ? { offset } : {}) });
        if (mine !== gen.current) return;
        setCards((cur) => (offset && cur ? [...cur, ...res.servers] : res.servers));
        setNext(res.has_more ? (res.next_offset ?? null) : null);
      } catch {
        if (mine === gen.current) setFailed(true);
      }
    },
    [query],
  );
  useEffect(() => {
    ++gen.current;
    const timer = setTimeout(() => void load(0), q ? 300 : 0);
    return () => clearTimeout(timer);
  }, [load, q]);

  const changeNSFW = (on: boolean) => {
    const apply = () => {
      ++gen.current;
      setCards(null);
      setNext(null);
      setOpen(null);
      setMeta(null);
      setShowNSFW(on);
    };
    if (!on) { apply(); return; }
    useUI.getState().openModal("confirm", {
      title: t("nsfw.warningTitle"),
      body: t("nsfw.discoveryWarning"),
      confirmLabel: t("nsfw.confirm"),
      onConfirm: apply,
    });
  };

  const filtered = !!(q.trim() || category || tag || language || size !== "any");
  return (
    <div className={s.page}>
      <header className={chat.header}>
        <PageHeaderButtons />
        <span className={chat.hIcon}><Compass size={22} /></span>
        <span className={chat.hName}>{t("discovery.title")}</span>
      </header>
      <div className={s.scroll}>
        <div className={s.inner}>
          <div className={s.lead}>
            <h1>{t("discovery.heading")}</h1>
            <p>{t("discovery.lead")}</p>
          </div>
          <SearchBox className={s.searchBig} value={q} onChange={setQ} placeholder={t("discovery.search")} aria-label={t("discovery.search")} data-testid="discovery-search" />
          <div className={s.cats} role="group" aria-label={t("discovery.categories")}>
            <button type="button" className={s.cat} aria-pressed={!category} onClick={() => setCategory("")}>
              {t("discovery.all")} {meta ? <span className={s.catCount}>{meta.total}</span> : null}
            </button>
            {(meta?.categories ?? []).map((c) => (
              <button key={c} type="button" className={s.cat} aria-pressed={category === c} onClick={() => setCategory(category === c ? "" : c)}>
                {t(`discovery.cat.${c}`)} {meta?.counts[c] ? <span className={s.catCount}>{meta.counts[c]}</span> : null}
              </button>
            ))}
          </div>
          <div className={s.controls}>
            <div className={s.sorts} role="radiogroup" data-glide="" aria-label={t("discovery.sortLabel")}>
              {(["members", "newest", "created"] as const).map((x) => (
                <button key={x} type="button" role="radio" aria-checked={sort === x} onClick={() => setSort(x)}>{t(`discovery.sort.${x}`)}</button>
              ))}
            </div>
            <label className={s.control}>
              <span>{t("discovery.size")}</span>
              <select value={size} onChange={(e) => setSize(e.target.value)}>
                {SIZES.map((x) => <option key={x.id} value={x.id}>{t(`discovery.sizes.${x.id}`)}</option>)}
              </select>
            </label>
            <label className={s.control}>
              <span>{t("discovery.language")}</span>
              <select value={language} onChange={(e) => setLanguage(e.target.value)}>
                <option value="">{t("discovery.anyLanguage")}</option>
                {(meta?.languages ?? Object.keys(LANGUAGE_NAMES)).map((l) => <option key={l} value={l}>{LANGUAGE_NAMES[l] ?? l}</option>)}
              </select>
            </label>
            <div className={s.nsfwSwitch}>
              <TriangleAlert size={16} aria-hidden="true" />
              <span>{t("nsfw.label")}</span>
              <Toggle checked={showNSFW} onChange={changeNSFW} ariaLabel={t("nsfw.label")} />
            </div>
            {tag ? (
              <span className={s.activeTag}>
                <span>{t("discovery.taggedWith", { tag })}</span>
                <button type="button" aria-label={t("discovery.clearTag")} onClick={() => setTag("")}><X size={14} /></button>
              </span>
            ) : null}
          </div>
          {!tag && meta?.tags.some((tg) => tg.toLowerCase() !== "nsfw") ? (
            <div className={s.popular}>
              <span>{t("discovery.popularTags")}</span>
              {meta.tags.filter((tg) => tg.toLowerCase() !== "nsfw").slice(0, 12).map((tg) => (
                <button key={tg} type="button" className={s.tagBtn} onClick={() => setTag(tg)}>{tg.toLowerCase() === "nsfw" ? t("nsfw.label") : tg}</button>
              ))}
            </div>
          ) : null}
          {failed ? (
            <div className={s.note}>
              {t("discovery.failed")}{" "}
              <button type="button" className={chat.loadRetry} onClick={() => void load(0)}>{t("chat.messageList.tryAgain")}</button>
            </div>
          ) : null}
          {cards === null && !failed ? <div className={s.note}>{t("discovery.loading")}</div> : null}
          {cards && cards.length === 0 ? (
            <div className={s.empty}>
              <Compass size={36} aria-hidden="true" />
              <h2>{filtered ? t("discovery.noMatches") : t("discovery.nothingYet")}</h2>
              <p>{filtered ? t("discovery.noMatchesHint") : t("discovery.nothingYetHint")}</p>
            </div>
          ) : null}
          {cards?.length ? (
            <div className={s.grid}>
              {cards.map((c) => <ServerCard key={c.id} card={c} onOpen={() => setOpen(c)} onTag={setTag} />)}
            </div>
          ) : null}
          {next !== null ? <button type="button" className={s.more} onClick={() => void load(next)}>{t("discovery.more")}</button> : null}
        </div>
      </div>
      {open ? <ServerSheet card={open} onClose={() => setOpen(null)} /> : null}
    </div>
  );
}
