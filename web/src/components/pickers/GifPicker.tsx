import { Star, X } from "lucide-react";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { expressions as expressionsApi } from "@/api/expressions";
import type { GifResult } from "@/api/types";
import type { AttachmentMeta } from "@/crypto/message";
import { removeFavoriteGifs, toggleFavoriteGif, type FavoriteGif } from "@/lib/gifFavorites";
import { attachmentObjectURL } from "@/lib/mediaCache";
import { BLANK_IMAGE, mediaKey, useMediaLinks } from "@/lib/mediaLinks";
import { useExpressions } from "@/stores/expressions";
import { atLeast, useMyRank } from "@/lib/staffRank";
import { useSettings } from "@/stores/settings";
import { favoriteGifMenu, gifResultMenu } from "./pickerMenus";
import { GifGrid, type GifTileData } from "./GifGrid";
import { focusSearchOnOpen } from "./EmojiPicker";
import { SearchBox } from "@/components/common/SearchBox";
import s from "./Picker.module.css";

import { t as tr_ } from "@/i18n";
type Pick = (gif: { url: string; w?: number; h?: number; att?: AttachmentMeta }) => void;

interface ResultTile extends GifTileData {
  gif: GifResult;
}

interface FavoriteTile extends GifTileData {
  fav: FavoriteGif;
}

/** The GIFs tab: favourites always; trending and search when the server has a key. */
export function GifPicker({ onPick }: { onPick: Pick }) {
  const favs = useSettings((st) => st.settings.favoriteGifs);
  const enabled = useExpressions((x) => x.gifEnabled);
  // the GIF key is the instance's, so its Developers are told how to set it up
  const admin = atLeast(useMyRank(), "developer");
  const setEnabled = useExpressions((x) => x.setGifEnabled);
  const [tab, setTab] = useState<"favorites" | "search">("favorites");
  const [q, setQ] = useState("");
  const [results, setResults] = useState<GifResult[]>([]);
  // the search the results are for, which lags what is typed while it waits
  const [shownFor, setShownFor] = useState<string | null>(null);
  const [next, setNext] = useState("");
  const [loading, setLoading] = useState(false);
  // Which load failed matters: a first page that failed used to fall through to
  // "No GIFs match", so a dead provider and an unlucky search word read exactly
  // the same. A later page that failed keeps what is on screen.
  const [failed, setFailed] = useState<null | "first" | "more">(null);
  // Favourites saved from a provider that is no longer used stop loading;
  // rather than a row of broken boxes, say so and offer the cross.
  const [dead, setDead] = useState<Set<string>>(new Set());
  const timer = useRef<ReturnType<typeof setTimeout> | null>(null);
  const seq = useRef(0);
  /** What is on screen, read where a state updater would run too late to decide anything. */
  const resultsRef = useRef(results);
  resultsRef.current = results;
  // by what each points at: a GIF's address changes every time it is signed
  const favSet = useMemo(() => new Set(favs.map((f) => mediaKey(f.url))), [favs]);
  const fresh = useMediaLinks();

  useEffect(() => {
    if (enabled !== null) return;
    expressionsApi
      .gifStatus()
      .then((r) => setEnabled(r.enabled))
      .catch(() => setEnabled(false));
  }, [enabled, setEnabled]);

  useEffect(() => {
    if (enabled && favs.length === 0) setTab("search");
  }, [enabled, favs.length]);

  const load = async (query: string, pos?: string) => {
    const mine = ++seq.current;
    setLoading(true);
    setFailed(null);
    try {
      const page = query.trim() ? await expressionsApi.gifSearch(query, pos) : await expressionsApi.gifTrending(pos);
      if (mine !== seq.current) return;
      let added = page.results.length;
      if (pos) {
        // pages can overlap as trending shifts; a GIF shown twice would share a key
        const have = new Set(resultsRef.current.map((g) => g.id));
        const fresh = page.results.filter((g) => !have.has(g.id));
        added = fresh.length;
        if (fresh.length) setResults((cur) => [...cur, ...fresh]);
      } else {
        setResults(page.results);
        setShownFor(query);
      }
      // A page that adds nothing ends it, even if the provider offers another:
      // one that repeats itself on a cursor that does not move would otherwise
      // be asked for again for as long as the picker stayed open.
      setNext(added ? page.next : "");
    } catch {
      if (mine !== seq.current) return;
      if (pos) {
        setFailed("more");
      } else {
        setResults([]);
        setShownFor(query);
        setNext("");
        setFailed("first");
      }
    } finally {
      if (mine === seq.current) setLoading(false);
    }
  };

  useEffect(() => {
    if (!enabled || tab !== "search") return;
    if (timer.current) clearTimeout(timer.current);
    timer.current = setTimeout(() => void load(q), q ? 300 : 0);
    return () => {
      if (timer.current) clearTimeout(timer.current);
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [q, enabled, tab]);

  // Uploaded favourites resolve through the attachment cache rather than the
  // link signer: their bytes are decrypted on this device, not re-signed.
  const [attUrls, setAttUrls] = useState<Record<string, string>>({});
  const [attGone, setAttGone] = useState<ReadonlySet<string>>(new Set());
  useEffect(() => {
    let alive = true;
    const missing = favs.filter((f) => f.att && !attUrls[f.att.blob_id] && !attGone.has(f.att.blob_id));
    if (missing.length === 0) return;
    void (async () => {
      for (const f of missing) {
        const att = f.att!;
        try {
          const url = await attachmentObjectURL(att);
          if (!alive) return;
          setAttUrls((m) => (m[att.blob_id] ? m : { ...m, [att.blob_id]: url }));
        } catch {
          if (!alive) return;
          setAttGone((d) => (d.has(att.blob_id) ? d : new Set(d).add(att.blob_id)));
        }
      }
    })();
    return () => {
      alive = false;
    };
  }, [favs, attUrls, attGone]);

  const loadMore = useCallback(() => {
    if (loading || failed !== null || !next || shownFor === null) return;
    void load(shownFor, next);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [loading, failed, next, shownFor]);


  const resultTiles = useMemo<ResultTile[]>(() => results.map((g) => ({ key: g.id, src: g.preview, label: g.title || "GIF", w: g.w, h: g.h, gif: g })), [results]);
  // A favourite is kept as the link it was saved with, whose signature runs out
  // after a day: it is shown, and sent, as that link signed again. One this
  // server will not sign again is gone.
  const favoriteTiles: FavoriteTile[] = favs.map((f) => {
    if (f.att) {
      const src = attUrls[f.att.blob_id];
      return {
        key: f.url,
        src: src ?? BLANK_IMAGE,
        label: tr_("pickers.gifPicker.favoriteGif"),
        w: f.w ?? f.att.w,
        h: f.h ?? f.att.h,
        dead: attGone.has(f.att.blob_id),
        fav: f,
      };
    }
    const src = fresh(f.url);
    return {
      key: f.url,
      src: src ?? BLANK_IMAGE,
      label: tr_("pickers.gifPicker.favoriteGif"),
      w: f.w,
      h: f.h,
      dead: dead.has(f.url) || src === null,
      fav: typeof src === "string" ? { ...f, url: src } : f,
    };
  });

  const sendResult = (g: GifResult) => onPick({ url: g.url, w: g.w, h: g.h });
  const onTabKey = (e: React.KeyboardEvent<HTMLButtonElement>) => {
    const step = e.key === "ArrowRight" ? 1 : e.key === "ArrowLeft" ? -1 : 0;
    if (!step) return;
    e.preventDefault();
    e.stopPropagation();
    const strip = e.currentTarget.parentElement;
    const buttons = strip ? [...strip.querySelectorAll<HTMLElement>('[role="tab"]')] : [];
    const at = buttons.indexOf(e.currentTarget);
    buttons[Math.min(buttons.length - 1, Math.max(0, at + step))]?.click();
  };

  return (
    <div className={s.body} style={{ flexDirection: "column" }}>
      <div className={s.searchRow}>
        {enabled ? (
          <SearchBox className={s.searchBox} value={q} onChange={(v) => { setQ(v); setTab("search"); }} placeholder={tr_("pickers.gifPicker.searchKlipy")} aria-label={tr_("pickers.gifPicker.searchGifs")} autoFocus={focusSearchOnOpen()} />
        ) : null}
        <div className={s.gifTabs} role="tablist" aria-label={tr_("pickers.expressionPicker.gifs")}>
          <button className={s.gifTab} role="tab" aria-selected={tab === "favorites"} tabIndex={tab === "favorites" ? 0 : -1} onKeyDown={onTabKey} data-on={tab === "favorites"} onClick={() => setTab("favorites")}>{tr_("pickers.gifPicker.favorites")}</button>
          {enabled ? <button className={s.gifTab} role="tab" aria-selected={tab === "search"} tabIndex={tab === "search" ? 0 : -1} onKeyDown={onTabKey} data-on={tab === "search"} onClick={() => setTab("search")}>{q.trim() ? tr_("pickers.gifPicker.results") : tr_("pickers.gifPicker.trending")}</button> : null}
        </div>
      </div>
      {tab === "favorites" ? (
        favs.length === 0 ? (
          <div className={s.empty}>{tr_("pickers.gifPicker.noFavoritesYet")} {enabled ? tr_("pickers.gifPicker.starInSearch") : tr_("pickers.gifPicker.starInChat")}</div>
        ) : (
          <GifGrid
            key="favorites"
            tiles={favoriteTiles}
            onPick={(t) => onPick(t.fav)}
            onDead={(t) => setDead((d) => (d.has(t.key) ? d : new Set(d).add(t.key)))}
            onMenu={(t, x, y) =>
              favoriteGifMenu.open({ dead: !!t.dead, send: () => onPick(t.fav), unfavorite: () => removeFavoriteGifs(new Set([t.key])) }, { x, y })
            }
            overlay={(t) => <button className={s.gifRemove} aria-label={tr_("pickers.gifPicker.removeFavorite")} onClick={() => removeFavoriteGifs(new Set([t.key]))}><X size={14} /></button>}
            footer={
              dead.size > 0 ? (
                <div className={s.gifNote}>
                  {tr_("pickers.gifPicker.favouritesNoLongerLoad", { count: dead.size })}{" "}
                  <button className={s.gifTab} onClick={() => removeFavoriteGifs(dead)}>{tr_("pickers.gifPicker.removeThem")}</button>
                </div>
              ) : null
            }
          />
        )
      ) : (
        <GifGrid
          key={`search:${shownFor ?? ""}`}
          tiles={resultTiles}
          onPick={(t) => sendResult(t.gif)}
          onNearEnd={loadMore}
          onMenu={(t, x, y) => {
            const fav = favSet.has(mediaKey(t.gif.url));
            gifResultMenu.open({ favorite: fav, send: () => sendResult(t.gif), toggleFavorite: () => toggleFavoriteGif({ url: t.gif.url, w: t.gif.w, h: t.gif.h }) }, { x, y });
          }}
          overlay={(t) => {
            const fav = favSet.has(mediaKey(t.gif.url));
            return (
              <button className={s.gifStar} data-on={fav} aria-label={fav ? tr_("pickers.gifPicker.removeFavorite") : tr_("pickers.gifPicker.addFavorite")} onClick={() => toggleFavoriteGif({ url: t.gif.url, w: t.gif.w, h: t.gif.h })}>
                <Star size={14} fill={fav ? "currentColor" : "none"} />
              </button>
            );
          }}
          footer={
            <>
              {loading ? <div className={s.gifNote}>{tr_("chat.pins.loading")}</div> : null}
              {!loading && !failed && results.length === 0 && shownFor !== null ? <div className={s.empty}>{shownFor.trim() ? tr_("pickers.gifPicker.noGifsMatch") : tr_("pickers.gifPicker.nothingTrendingRightNow")}</div> : null}
              {failed ? (
                <div className={s.gifNote} role="alert">
                  {failed === "first" ? tr_("pickers.gifPicker.gifsCouldNotBeLoaded") : tr_("pickers.gifPicker.moreGifsCouldNotBeLoaded")}{" "}
                  <button className={s.gifTab} onClick={() => { const at = failed === "more" ? next : undefined; setFailed(null); if (shownFor !== null) void load(shownFor, at); }}>{tr_("chat.messageList.tryAgain")}</button>
                </div>
              ) : null}
              {results.length > 0 ? <div className={s.gifNote}>{tr_("pickers.gifPicker.searchPoweredByKlipyThisServer")}</div> : null}
            </>
          }
        />
      )}
      {/* how to turn it on is for whoever runs the server; everyone else just learns it is not there */}
      {!enabled && enabled !== null ? <div className={s.gifNote}>{tr_(admin ? "pickers.gifPicker.gifSearchIsOffOnThis" : "pickers.gifPicker.gifSearchUnavailable")}</div> : null}
    </div>
  );
}
