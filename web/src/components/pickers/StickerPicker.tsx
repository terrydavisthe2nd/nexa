import { ChevronDown, Clock, Ellipsis, Plus, Star } from "lucide-react";
import { memo, useCallback, useEffect, useMemo, useRef, useState, useSyncExternalStore, type MouseEvent, type ReactNode, type RefObject } from "react";
import { useNavigate } from "react-router-dom";
import { expressions as expressionsApi } from "@/api/expressions";
import type { Sticker, StickerPack } from "@/api/types";
import { moveFocusInGrid } from "@/lib/gridKeys";
import { toggleFavoriteSticker } from "@/lib/stickerFavorites";
import { groupPacks, searchStickersByPack, useExpressions } from "@/stores/expressions";
import { useAuth } from "@/stores/auth";
import { useServers } from "@/stores/servers";
import { useUI } from "@/stores/ui";
import { pickerStickerMenu, stickerPackMenu } from "./pickerMenus";
import { hoverPlays, moves, StillFace, stillSourceOf, useStickerStill } from "@/components/stickers/StickerTile";
import { StickerView } from "@/components/stickers/StickerView";
import { Unfold } from "@/components/stickers/Unfold";
import { fullStickerUrl } from "@/lib/stickerStills";
import { useScreenPlace } from "@/hooks/useNearScreen";
import { warmLottieLater } from "@/lib/stickerStills";
import { useSettings } from "@/stores/settings";
import { focusSearchOnOpen } from "./EmojiPicker";
import { SearchBox } from "@/components/common/SearchBox";
import s from "./Picker.module.css";

import { t } from "@/i18n";
import { cssValue } from "@/lib/cssEscape";

export interface StickerPick {
  sticker: Sticker;
  pack: StickerPack;
  /** Shift was held: send it and leave the picker open for another. */
  keepOpen?: boolean;
}

/** How wide a tile is at least, and the gap between tiles, as `.stickerGrid` has them. */
const TILE = 96;
const GAP = 8;
/** How many rows of Recent show before Show All. */
const RECENT_ROWS = 2;

/**
 * Who the pointer is on, kept outside React state: the bar under the grid
 * listens to this alone, so pointing at a sticker does not redraw the grid.
 */
function hoverStore() {
  let current: StickerPick | null = null;
  const subs = new Set<() => void>();
  return {
    get: () => current,
    set: (next: StickerPick | null) => {
      if (current?.sticker.id === next?.sticker.id) return;
      current = next;
      subs.forEach((f) => f());
    },
    subscribe: (f: () => void) => {
      subs.add(f);
      return () => subs.delete(f);
    },
  };
}
type HoverStore = ReturnType<typeof hoverStore>;

/**
 * One sticker in the grid, as a small still (see StickerTile.tsx): loaded
 * while the cell is on screen or close to it, the ones in view first, and
 * kept for the next time the picker opens. An animated one moves only while
 * it is pointed at or focused. A cell whose still could not load says so,
 * and pressing it tries again instead of sending something you cannot see.
 */
const StickerCell = memo(function StickerCell({ sticker, pack, onPick, onMenu, hover, root, still, active, favorite }: { sticker: Sticker; pack: StickerPack; onPick: (p: StickerPick) => void; onMenu: (p: StickerPick, e: MouseEvent) => void; hover: HoverStore; root: RefObject<HTMLDivElement | null>; still: boolean; active: boolean; favorite: boolean }) {
  const ref = useRef<HTMLButtonElement>(null);
  const place = useScreenPlace(ref, root, active);
  const src = useMemo(() => stillSourceOf(pack, sticker), [pack, sticker]);
  const [view, retry] = useStickerStill(src, place);
  const [hot, setHot] = useState(false);
  // a sticker sent with the picker staying open answers the press, since nothing else on it changes
  const [sent, setSent] = useState(false);
  const failed = view.status === "failed";
  const label = failed ? t("stickers.tile.couldNotLoadNamed", { name: sticker.name }) : sticker.name;
  const point = (on: boolean) => {
    setHot(on);
    hover.set(on ? { sticker, pack } : null);
  };
  return (
    <div className={s.stickerWrap} onContextMenu={(e) => onMenu({ sticker, pack }, e)}>
      <button
        ref={ref}
        className={s.stickerCell}
        title={label}
        aria-label={label}
        aria-busy={view.status === "loading" || undefined}
        data-sticker={sticker.id}
        data-sticker-state={view.status}
        data-sent={sent || undefined}
        onAnimationEnd={() => setSent(false)}
        tabIndex={-1}
        onClick={(e) => {
          if (failed) return retry();
          if (e.shiftKey) setSent(true);
          onPick({ sticker, pack, keepOpen: e.shiftKey });
        }}
        onPointerEnter={(e) => hoverPlays(e) && point(true)}
        onPointerLeave={() => point(false)}
        onFocus={() => point(true)}
        onBlur={() => point(false)}
      >
        <StillFace view={view} src={src} alt="" play={hot && !still && moves(src) && place !== "far"} />
      </button>
      <button type="button" className={s.stickerStar} data-on={favorite || undefined} tabIndex={-1} aria-label={favorite ? t("pickers.gifPicker.removeFromFavorites") : t("pickers.gifPicker.addToFavorites")} title={favorite ? t("pickers.gifPicker.removeFromFavorites") : t("pickers.gifPicker.addToFavorites")} onClick={() => toggleFavoriteSticker(sticker.id)}>
        <Star size={14} fill={favorite ? "currentColor" : "none"} aria-hidden="true" />
      </button>
    </div>
  );
});

/**
 * A pack's grid. Its cells watch where they are only while the pack is within
 * a screen of view, so a collection of thousands of stickers keeps the few
 * hundred near the screen watched rather than every one of them.
 */
function PackGrid({ stickers, pack, root, cell }: { stickers: Sticker[]; pack: StickerPack; root: RefObject<HTMLDivElement | null>; cell: (st: Sticker, pack: StickerPack, active: boolean) => ReactNode }) {
  const ref = useRef<HTMLDivElement>(null);
  const active = useScreenPlace(ref, root) !== "far";
  return (
    <div ref={ref} className={s.stickerGrid}>
      {stickers.map((st) => cell(st, pack, active))}
    </div>
  );
}

/** A pack's icon in the column beside the grid: its cover, as a still like the grid's. */
function PackIcon({ pack, cover, root }: { pack: StickerPack; cover: Sticker; root: RefObject<HTMLDivElement | null> }) {
  const ref = useRef<HTMLSpanElement>(null);
  const place = useScreenPlace(ref, root);
  const src = useMemo(() => stillSourceOf(pack, cover), [pack, cover]);
  // after the stickers in view, which are what people came for
  const [view] = useStickerStill(src, place, 1);
  return (
    <span ref={ref} className={s.catImg}>
      {/* a pack's icon stays still: a column of them all moving at once is noise, and costly */}
      <StillFace view={view} src={src} alt="" play={false} />
    </span>
  );
}

/** What a phone shows above a held sticker's menu: the sticker itself, moving, with its name and pack. */
function StickerPeek({ sticker, pack }: StickerPick) {
  const src = stillSourceOf(pack, sticker);
  return (
    <div className={s.stickerPeek} data-testid="sticker-peek">
      <StickerView format={src.format} src={fullStickerUrl(src)} alt="" className={s.stickerPeekArt} eager />
      <div className={s.stickerPeekName} dir="auto">{sticker.name}</div>
      <div className={s.stickerPeekPack} dir="auto">{pack.name}</div>
    </div>
  );
}

/** The strip under the grid: the sticker the pointer is on, and the way to send several. */
function NameBar({ hover }: { hover: HoverStore }) {
  const on = useSyncExternalStore(hover.subscribe, hover.get, hover.get);
  const src = on ? stillSourceOf(on.pack, on.sticker) : null;
  return (
    <div className={[s.inspector, s.stickerBar].join(" ")} data-testid="sticker-name-bar">
      {on && src ? (
        <>
          <img className={s.stickerBarArt} src={fullStickerUrl(src, 48) + (moves(src) ? "&still=1" : "")} alt="" draggable={false} />
          <span className={s.name} dir="auto">{on.sticker.name}</span>
          <span className={s.stickerBarPack} dir="auto">{on.pack.name}</span>
        </>
      ) : (
        <span className={s.stickerBarHint}>{t("pickers.stickerPicker.shiftClickHint")}</span>
      )}
    </div>
  );
}

function prefersStill(): boolean {
  return typeof window !== "undefined" && !!window.matchMedia?.("(prefers-reduced-motion: reduce)").matches;
}

/** How many tiles fit across a grid this wide. */
function tilesAcross(width: number): number {
  return Math.max(1, Math.floor((width + GAP) / (TILE + GAP)));
}

/** The Stickers tab: favorites, recents, then every pack on this account, searchable by name and tags. */
export function StickerPicker({ serverId, onPick, onlyServer = false }: { serverId: string | null | undefined; onPick: (p: StickerPick) => void; onlyServer?: boolean }) {
  const allPacks = useExpressions((x) => x.packs);
  // without Use External Stickers a server channel takes only the server's own
  const packs = useMemo(() => (onlyServer && serverId ? Object.fromEntries(Object.entries(allPacks).filter(([, p]) => p.server_id === serverId)) : allPacks), [allPacks, onlyServer, serverId]);
  const recents = useExpressions((x) => x.recents);
  const setRecents = useExpressions((x) => x.setRecents);
  const meId = useAuth((a) => a.me?.id);
  // whether a pack can be removed, and what another server's pack is called, depends on the servers you are in
  const servers = useServers((x) => x.byId);
  const openModal = useUI((u) => u.openModal);
  const toast = useUI((u) => u.toast);
  const navigate = useNavigate();
  const favoriteIds = useSettings((x) => x.settings.favoriteStickers);
  const collapsedIds = useSettings((x) => x.settings.collapsedStickerPacks);
  const patchSettings = useSettings((x) => x.patch);
  const [q, setQ] = useState("");
  const gridRef = useRef<HTMLDivElement>(null);
  const catsRef = useRef<HTMLDivElement>(null);
  const [hover] = useState(hoverStore);
  const [active, setActive] = useState<string | null>(null);
  const [allRecent, setAllRecent] = useState(false);
  const [across, setAcross] = useState(4);
  const still = useSettings((x) => x.settings.reducedMotion) || prefersStill();
  const groups = useMemo(() => groupPacks(packs, serverId, meId), [packs, serverId, meId]);
  const all = useMemo(() => groups.flatMap((g) => g.packs), [groups]);
  const results = useMemo(() => (q.trim() ? searchStickersByPack(all, q) : null), [all, q]);
  const favorites = useMemo(() => new Set(favoriteIds), [favoriteIds]);
  const collapsed = useMemo(() => new Set(collapsedIds), [collapsedIds]);
  /** Where a pack comes from, said beside its name. */
  const sourceOf = useCallback((p: StickerPack): string => {
    if (serverId && p.server_id === serverId) return t("expressions.thisServer");
    if (p.owner_user_id && p.owner_user_id === meId) return t("pickers.stickerPicker.yourPack");
    if (p.installed) return t("expressions.installed");
    return (p.server_id && servers[p.server_id]?.server.name) || t("expressions.otherServers");
  }, [serverId, meId, servers]);
  // a pack of Telegram animated stickers further down needs the Lottie player for its stills
  const anyLottie = useMemo(() => all.some((p) => p.stickers.some((st) => st.format === "tgs")), [all]);
  useEffect(() => {
    if (anyLottie) warmLottieLater();
  }, [anyLottie]);
  // Recently sent stickers, resolved against the packs on this device so each
  // cell keeps its pack for sending. Anything whose pack is gone (deleted,
  // uninstalled) simply stays out of the row.
  const recentItems = useMemo(() => {
    const out: StickerPick[] = [];
    for (const st of recents) {
      const pack = packs[st.pack_id];
      if (pack && (pack.kind ?? "sticker") === "sticker") out.push({ sticker: st, pack });
      if (out.length >= 24) break;
    }
    return out;
  }, [recents, packs]);
  const favoriteItems = useMemo(() => {
    const byId = new Map<string, StickerPick>();
    for (const pack of all) for (const sticker of pack.stickers) byId.set(sticker.id, { sticker, pack });
    return favoriteIds.flatMap((id) => byId.get(id) ?? []);
  }, [favoriteIds, all]);

  // how many fit across decides how many recents two rows hold
  useEffect(() => {
    const el = gridRef.current;
    if (!el || typeof ResizeObserver === "undefined") return;
    const ob = new ResizeObserver(() => {
      // the scroller's padding and the grid's own
      const style = getComputedStyle(el);
      setAcross(tilesAcross(el.clientWidth - parseFloat(style.paddingLeft) - parseFloat(style.paddingRight) - 16));
    });
    ob.observe(el);
    return () => ob.disconnect();
  }, []);

  // the rail follows the part scrolled to; a press on the rail says for itself where it went
  const jumped = useRef(0);
  useEffect(() => {
    const el = gridRef.current;
    if (!el) return;
    let frame = 0;
    const look = () => {
      frame = 0;
      if (performance.now() - jumped.current < 200) return;
      const top = el.getBoundingClientRect().top;
      let at: string | null = null;
      for (const part of el.querySelectorAll<HTMLElement>("[data-part]")) {
        if (part.getBoundingClientRect().top - top > 12) break;
        at = part.dataset["part"] ?? null;
      }
      setActive(at ?? el.querySelector<HTMLElement>("[data-part]")?.dataset["part"] ?? null);
    };
    const onScroll = () => {
      if (!frame) frame = requestAnimationFrame(look);
    };
    look();
    el.addEventListener("scroll", onScroll, { passive: true });
    return () => {
      el.removeEventListener("scroll", onScroll);
      if (frame) cancelAnimationFrame(frame);
    };
    // the parts themselves change with these
  }, [all, results, favoriteItems.length, recentItems.length, collapsedIds, allRecent]);
  // the chosen icon stays in sight in a rail longer than the picker
  useEffect(() => {
    const rail = catsRef.current;
    const on = rail?.querySelector<HTMLElement>('[data-selected="true"]');
    if (!rail || !on) return;
    const a = on.getBoundingClientRect();
    const b = rail.getBoundingClientRect();
    if (a.top < b.top) rail.scrollTop -= b.top - a.top + 4;
    else if (a.bottom > b.bottom) rail.scrollTop += a.bottom - b.bottom + 4;
  }, [active]);

  // what a search brings starts from its top, not wherever the list had been scrolled to
  const searching = q.trim();
  useEffect(() => {
    if (gridRef.current) gridRef.current.scrollTop = 0;
  }, [searching]);

  const jump = (id: string) => {
    jumped.current = performance.now();
    setActive(id);
    // braces: Chromium's scroll methods answer a promise, which an effect would take for its cleanup
    const part = gridRef.current?.querySelector<HTMLElement>(`[data-part="${cssValue(id)}"]`);
    if (part) part.scrollIntoView({ block: "start" });
  };
  const edit = (p: StickerPack) => openModal("settings", { page: "stickers", packId: p.id });
  const view = useCallback((pack: StickerPack, stickerId?: string) => openModal("stickerPack", { packId: pack.id, pack, ...(stickerId ? { stickerId } : {}) }), [openModal]);
  // right click, or a long press on a phone
  const packMenu = (p: StickerPack) => (e: MouseEvent) => {
    if (stickerPackMenu.open({ pack: p, meId, edit: () => edit(p), view: () => view(p) }, { x: e.clientX, y: e.clientY, label: p.name })) e.preventDefault();
  };
  const stickerMenu = useCallback((pick: StickerPick, e: MouseEvent) => {
    const favorite = useSettings.getState().settings.favoriteStickers.includes(pick.sticker.id);
    const opened = pickerStickerMenu.open(
      { sticker: pick.sticker, favorite, toggleFavorite: () => toggleFavoriteSticker(pick.sticker.id), viewPack: () => view(pick.pack, pick.sticker.id) },
      { x: e.clientX, y: e.clientY, label: pick.sticker.name, labelNode: <StickerPeek sticker={pick.sticker} pack={pick.pack} /> },
    );
    if (opened) e.preventDefault();
  }, [view]);
  const togglePack = (id: string) => {
    patchSettings({ collapsedStickerPacks: collapsed.has(id) ? collapsedIds.filter((x) => x !== id) : [...collapsedIds, id] });
  };
  const clearRecent = () => {
    setRecents([]);
    setAllRecent(false);
    void expressionsApi.clearRecentStickers().catch(() => toast(t("pickers.stickerPicker.couldNotClearRecent"), "error"));
  };
  const findMore = () => navigate("/channels/@discover?tab=stickers");

  // the same function every render, so typing elsewhere does not redraw every cell
  const pickRef = useRef(onPick);
  pickRef.current = onPick;
  const pick = useCallback((p: StickerPick) => pickRef.current(p), []);
  const cell = (sticker: Sticker, pack: StickerPack, on = true, slot = "") => <StickerCell key={slot + sticker.id} sticker={sticker} pack={pack} onPick={pick} onMenu={stickerMenu} hover={hover} root={gridRef} still={still} active={on} favorite={favorites.has(sticker.id)} />;
  const shownRecent = recentItems.slice(0, across * RECENT_ROWS);
  const moreRecent = recentItems.slice(across * RECENT_ROWS);
  const railPacks = results ? results.map((g) => g.pack) : all;

  return (
    <>
      <div className={s.searchRow}>
        <SearchBox className={s.searchBox} value={q} onChange={setQ} placeholder={t("pickers.stickerPicker.searchStickers")} aria-label={t("pickers.stickerPicker.searchStickers")} autoFocus={focusSearchOnOpen()} />
      </div>
      <div className={s.body}>
        <div className={s.cats} ref={catsRef} data-scroll-root="" data-testid="sticker-rail">
          {!results && favoriteItems.length > 0 ? <button className={s.cat} data-selected={active === "favorites"} title={t("pickers.stickerPicker.favorites")} aria-label={t("pickers.stickerPicker.favorites")} onClick={() => jump("favorites")}><Star size={18} aria-hidden="true" /></button> : null}
          {!results && recentItems.length > 0 ? <button className={s.cat} data-selected={active === "recent"} title={t("pickers.stickerPicker.recent")} aria-label={t("pickers.stickerPicker.recent")} onClick={() => jump("recent")}><Clock size={18} aria-hidden="true" /></button> : null}
          {railPacks.map((p) => {
            const cover = p.stickers.find((st) => st.id === p.cover_sticker_id) ?? p.stickers[0];
            return (
              <button key={p.id} className={s.cat} data-selected={active === p.id} aria-current={active === p.id || undefined} title={p.name} aria-label={p.name} onClick={() => jump(p.id)} onContextMenu={packMenu(p)}>
                {cover ? <PackIcon pack={p} cover={cover} root={catsRef} /> : <span className={s.catInitial}>{p.name.slice(0, 1).toUpperCase()}</span>}
              </button>
            );
          })}
          {!results && !onlyServer ? <button className={s.cat} title={t("pickers.stickerPicker.findMorePacks")} aria-label={t("pickers.stickerPicker.findMorePacks")} onClick={findMore} data-testid="sticker-find-more"><Plus size={18} aria-hidden="true" /></button> : null}
        </div>
        {/* One tab stop for the whole grid, with the arrows inside it: every
            cell was a stop of its own, which is hundreds in a full picker. */}
        <div
          className={s.grid}
          ref={gridRef}
          data-scroll-root=""
          tabIndex={0}
          role="group"
          aria-label={t("pickers.stickerPicker.searchStickers")}
          onKeyDown={(e) => {
            if (gridRef.current && moveFocusInGrid(gridRef.current, "[data-sticker]", e.key)) e.preventDefault();
          }}
        >
          {all.length === 0 ? (
            <div className={s.empty}>
              {t("pickers.stickerPicker.noStickerPacksYet")}
              <br />
              <button className={s.sectionLink} onClick={findMore}>{t("pickers.stickerPicker.findMorePacks")}</button>
              {" "}
              <button className={s.sectionLink} onClick={() => openModal("settings", { page: "stickers" })}>{t("settings.stickers.createStickerPack")}</button>
            </div>
          ) : null}
          {results ? (
            results.length === 0 ? <div className={s.empty}>{t("pickers.stickerPicker.noStickersMatch")}</div> : results.map((g) => (
              <section key={g.pack.id} data-part={g.pack.id} data-pack={g.pack.id}>
                <div className={[s.section, s.sectionRow].join(" ")} onContextMenu={packMenu(g.pack)}>
                  <span className={s.sectionName}>{g.pack.name}</span>
                  <span className={s.packSource}>{t("server.packEditor.stickerCount", { count: g.stickers.length })}</span>
                </div>
                <PackGrid stickers={g.stickers} pack={g.pack} root={gridRef} cell={cell} />
              </section>
            ))
          ) : (
            <>
              {favoriteItems.length > 0 ? (
                <section data-part="favorites">
                  <div className={s.section}>{t("pickers.stickerPicker.favorites")}</div>
                  <div className={s.stickerGrid}>{favoriteItems.map((r) => cell(r.sticker, r.pack, true, "f"))}</div>
                </section>
              ) : null}
              {recentItems.length > 0 ? (
                <section data-part="recent">
                  <div className={[s.section, s.sectionRow].join(" ")}>
                    <span className={s.sectionName}>{t("pickers.stickerPicker.recent")}</span>
                    {moreRecent.length > 0 ? <button className={s.sectionLink} onClick={() => setAllRecent((v) => !v)} aria-expanded={allRecent}>{allRecent ? t("pickers.stickerPicker.showLess") : t("pickers.stickerPicker.showAll", { count: recentItems.length })}</button> : null}
                    <button className={s.sectionLink} onClick={clearRecent} data-testid="clear-recent-stickers">{t("pickers.stickerPicker.clearRecent")}</button>
                  </div>
                  <div className={s.stickerGrid} data-part-grid="">{shownRecent.map((r) => cell(r.sticker, r.pack, true, "r"))}</div>
                  <Unfold open={allRecent}>
                    <div className={[s.stickerGrid, s.stickerGridMore].join(" ")}>{moreRecent.map((r) => cell(r.sticker, r.pack, true, "r"))}</div>
                  </Unfold>
                </section>
              ) : null}
              {all.map((p) => {
                const open = !collapsed.has(p.id);
                return (
                  <section key={p.id} data-pack={p.id} data-part={p.id} className={s.packSection} style={{ containIntrinsicSize: `auto ${open ? 40 + Math.ceil(Math.max(p.stickers.length, 1) / across) * (TILE + GAP + 8) : 40}px` }}>
                    <div className={[s.section, s.sectionRow, s.packHead].join(" ")} onContextMenu={packMenu(p)}>
                      <button type="button" className={s.packToggle} aria-expanded={open} onClick={() => togglePack(p.id)} data-testid="sticker-pack-toggle">
                        <ChevronDown size={14} className={s.packChevron} data-open={open} aria-hidden="true" />
                        <span className={s.sectionName}>{p.name}</span>
                      </button>
                      <span className={s.packSource}>{sourceOf(p)}</span>
                      <button type="button" className={s.packMore} aria-label={t("pickers.stickerPicker.packOptions", { name: p.name })} onClick={packMenu(p)}><Ellipsis size={16} aria-hidden="true" /></button>
                    </div>
                    <Unfold open={open}>
                      {p.stickers.length === 0 ? <div className={s.gifNote}>{t("pickers.stickerPicker.thisPackIsEmpty")}</div> : <PackGrid stickers={p.stickers} pack={p} root={gridRef} cell={cell} />}
                    </Unfold>
                  </section>
                );
              })}
            </>
          )}
        </div>
      </div>
      <NameBar hover={hover} />
    </>
  );
}
