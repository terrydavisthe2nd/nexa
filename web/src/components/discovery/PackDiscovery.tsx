import { Check, PackagePlus, Sticker as StickerIcon } from "lucide-react";
import { useCallback, useEffect, useRef, useState } from "react";
import { ApiError } from "@/api/client";
import { expressions as expressionsApi } from "@/api/expressions";
import type { StickerPack } from "@/api/types";
import { Button } from "@/components/common/Button";
import { PersonName } from "@/components/common/PersonName";
import { SearchBox } from "@/components/common/SearchBox";
import { packCount, packCover } from "@/components/server/PackEditor";
import { LazySticker } from "@/components/stickers/StickerTile";
import { t, Tr } from "@/i18n";
import { avatarColor } from "@/lib/avatarColor";
import { useDominantColor } from "@/lib/dominantColor";
import { setSharedPack } from "@/lib/packLinks";
import { useAuth } from "@/stores/auth";
import { packImageUrl, useExpressions } from "@/stores/expressions";
import { useUI } from "@/stores/ui";
import { displayName } from "@/stores/users";
import chat from "@/components/chat/Chat.module.css";
import d from "./Discovery.module.css";
import s from "./PackDiscovery.module.css";

type Sort = "popular" | "newest" | "updated";
const SORTS: Sort[] = ["popular", "newest", "updated"];

/** A pack on Discovery: a sheet of its stickers on the pack's own colour, what it is, and the button to have it. */
function PackCard({ pack, onChange }: { pack: StickerPack; onChange: (pack: StickerPack) => void }) {
  const meId = useAuth((a) => a.me?.id);
  const stored = useExpressions((x) => x.packs[pack.id]);
  const upsertPack = useExpressions((x) => x.upsertPack);
  const openModal = useUI((u) => u.openModal);
  const toast = useUI((u) => u.toast);
  const [busy, setBusy] = useState(false);
  const emoji = pack.kind === "emoji";
  const cover = packCover(pack);
  const moving = cover && (cover.animated || cover.format === "webm" || cover.format === "tgs");
  const tint = useDominantColor(cover ? packImageUrl(pack, cover, 96) + (moving ? "&still=1" : "") : null);
  const installed = stored?.installed ?? pack.installed;
  const mine = pack.owner_user_id === meId;
  const count = pack.sticker_count ?? pack.stickers.length;
  const shown = pack.stickers.slice(0, emoji ? 6 : 4);
  const left = count - shown.length;
  const open = () => openModal("stickerPack", { packId: pack.id, pack });
  const install = async () => {
    setBusy(true);
    try {
      const res = await expressionsApi.install(pack.id);
      upsertPack(res);
      setSharedPack(res);
      onChange({ ...pack, installed: true, install_count: (pack.install_count ?? 0) + 1 });
    } catch (err) {
      toast(err instanceof ApiError ? err.message : t("modals.stickerPack.couldNotInstallThePack"), "error");
    } finally {
      setBusy(false);
    }
  };
  return (
    <article className={s.card} data-testid="pack-discovery-card" data-pack={pack.id} data-kind={pack.kind} style={{ "--tint": tint ?? avatarColor(pack.id) } as React.CSSProperties}>
      <button type="button" className={s.hit} onClick={open} aria-label={t("stickerPack.viewNamed", { name: pack.name })} data-testid="pack-discovery-open" />
      <div className={s.sheet}>
        {shown.map((st, i) => (
          <span key={st.id} className={s.item} data-at={i}>
            <LazySticker sticker={st} pack={pack} alt="" className={s.art} />
          </span>
        ))}
        {left > 0 ? <span className={s.more} aria-hidden="true">+{left}</span> : null}
      </div>
      <div className={s.body}>
        <h3 className={s.name} dir="auto">{pack.name}</h3>
        <div className={s.facts}>
          <span>{packCount(pack)}</span>
          {pack.install_count ? <span>{t("stickerPack.installs", { count: pack.install_count })}</span> : null}
        </div>
        {pack.owner ? <div className={s.by}><Tr id="stickerPack.madeBy" values={{ name: <PersonName user={pack.owner}>{displayName(pack.owner)}</PersonName> }} /></div> : null}
        {pack.description ? <p className={s.desc} dir="auto">{pack.description}</p> : null}
        <div className={s.action}>
          {mine ? (
            <Button size="sm" variant="secondary" block onClick={open}>{t("pickers.stickerPicker.viewPack")}</Button>
          ) : installed ? (
            <Button size="sm" variant="secondary" block disabled><Check size={14} aria-hidden="true" /> {t("settings.stickers.installed")}</Button>
          ) : (
            <Button size="sm" block loading={busy} onClick={() => void install()} data-testid="pack-discovery-install"><PackagePlus size={14} aria-hidden="true" /> {t("settings.stickers.install")}</Button>
          )}
        </div>
      </div>
    </article>
  );
}

/** Discovery's sticker or emoji part: every public pack of that kind, searched by its words and its stickers' names and tags. */
export function PackDiscovery({ kind }: { kind: "sticker" | "emoji" }) {
  const [q, setQ] = useState("");
  const [sort, setSort] = useState<Sort>("popular");
  const [packs, setPacks] = useState<StickerPack[] | null>(null);
  const [next, setNext] = useState<number | null>(null);
  const [failed, setFailed] = useState(false);
  const gen = useRef(0);
  const load = useCallback(async (offset: number) => {
    const mine = ++gen.current;
    setFailed(false);
    try {
      const res = await expressionsApi.discoverPacks({ kind, sort, limit: 24, ...(q.trim() ? { q: q.trim() } : {}), ...(offset ? { offset } : {}) });
      if (mine !== gen.current) return;
      setPacks((cur) => (offset && cur ? [...cur, ...res.packs] : res.packs));
      setNext(res.has_more ? (res.next_offset ?? null) : null);
    } catch {
      if (mine === gen.current) setFailed(true);
    }
  }, [kind, sort, q]);
  useEffect(() => {
    ++gen.current;
    const timer = setTimeout(() => void load(0), q ? 300 : 0);
    return () => clearTimeout(timer);
  }, [load, q]);
  const emoji = kind === "emoji";
  const searched = !!q.trim();
  return (
    <>
      <div className={d.lead}>
        <h1>{t(emoji ? "discovery.packs.emojiHeading" : "discovery.packs.stickerHeading")}</h1>
        <p>{t(emoji ? "discovery.packs.emojiLead" : "discovery.packs.stickerLead")}</p>
      </div>
      <SearchBox className={d.searchBig} value={q} onChange={setQ} placeholder={t(emoji ? "discovery.packs.searchEmoji" : "discovery.packs.searchStickers")} aria-label={t(emoji ? "discovery.packs.searchEmoji" : "discovery.packs.searchStickers")} data-testid="pack-discovery-search" />
      <div className={d.controls}>
        <div className={d.sorts} role="radiogroup" data-glide="" aria-label={t("discovery.packs.sortLabel")}>
          {SORTS.map((x) => (
            <button key={x} type="button" role="radio" aria-checked={sort === x} onClick={() => setSort(x)}>{t(`discovery.packs.sort.${x}`)}</button>
          ))}
        </div>
      </div>
      {failed ? (
        <div className={d.note}>
          {t("discovery.packs.failed")}{" "}
          <button type="button" className={chat.loadRetry} onClick={() => void load(0)}>{t("chat.messageList.tryAgain")}</button>
        </div>
      ) : null}
      {packs === null && !failed ? <div className={d.note}>{t("discovery.packs.loading")}</div> : null}
      {packs && packs.length === 0 ? (
        <div className={d.empty}>
          <StickerIcon size={36} aria-hidden="true" />
          <h2>{searched ? t("discovery.packs.noMatches") : t("discovery.packs.nothingYet")}</h2>
          <p>{searched ? t("discovery.packs.noMatchesHint") : t("discovery.packs.nothingYetHint")}</p>
        </div>
      ) : null}
      {packs?.length ? (
        <div className={s.grid}>
          {packs.map((p) => <PackCard key={p.id} pack={p} onChange={(next) => setPacks((cur) => cur?.map((x) => (x.id === next.id ? next : x)) ?? null)} />)}
        </div>
      ) : null}
      {next !== null ? <button type="button" className={d.more} onClick={() => void load(next)}>{t("discovery.packs.more")}</button> : null}
    </>
  );
}
