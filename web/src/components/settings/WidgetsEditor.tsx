import { BookOpen, Bookmark, Clapperboard, Tv, CalendarClock, LibraryBig, Sticker, ChartNoAxesColumn, Clock, Disc3, Palette, Gamepad2, GripVertical, Heart, ImagePlus, Library, LoaderCircle, MicVocal, Music, PanelTop, Plus, Search, Check, X, type LucideIcon } from "lucide-react";
import { useEffect, useId, useMemo, useRef, useState, type DragEvent, type KeyboardEvent } from "react";
import { users as usersApi } from "@/api/users";
import { expressions as expressionsApi } from "@/api/expressions";
import type { StickerPack } from "@/api/types";
import { LazySticker } from "@/components/stickers/StickerTile";
import { useAuth } from "@/stores/auth";
import { Button } from "@/components/common/Button";
import { ImageCropModal } from "@/components/common/ImageCropModal";
import { Modal } from "@/components/common/Modal";
import { ColourInput } from "@/components/common/ColourInput";
import { FormattedText } from "@/components/common/FormattedText";
import { Select } from "@/components/common/Select";
import { Toggle } from "@/components/common/Toggle";
import { Field, TextArea, TextInput } from "@/components/common/TextInput";
import { BookCover, ScreenPoster, GameCover, MusicArt, ProfileWidgets, widgetTitle } from "@/components/profile/Widgets";
import { useReorder } from "@/hooks/useReorder";
import { Tr, t } from "@/i18n";
import { describeError } from "@/lib/errors";
import { BOOK_TYPES, MOST_SCREEN, SCREEN_TYPES, TMDB_LOGO, type FoundScreen, type ScreenWidget, type WidgetScreen, GAME_TYPES, LIMITS, MOST_BOOKS, type BookWidget, type FoundBook, type WidgetBook, MAX_CARD_LINKS, MAX_CARDS, MAX_SHOWCASE_PACKS, MOST_GAMES, MOST_MUSIC, MUSIC_TYPES, songLength, widgetImageUrl, type CardWidget, type CountdownWidget, type FoundMusic, type LocalTimeWidget, type StickersWidget, type GameWidget, type MusicWidget, type Widget, type WidgetGame, type WidgetMusic, type WidgetStat, type WidgetType } from "@/lib/widgets";
import { allZones, zoneOffset, zonePlace } from "@/lib/timeWidgets";
import { useUI } from "@/stores/ui";
import s from "./WidgetsEditor.module.css";

const KIND_ICONS: Record<WidgetType, LucideIcon> = { favorite: Heart, playing: Gamepad2, wishlist: Bookmark, played: Library, artists: MicVocal, albums: Disc3, songs: Music, custom: PanelTop, countdown: CalendarClock, localtime: Clock, stickers: Sticker, reading: BookOpen, books: LibraryBig, watching: Tv, screen: Clapperboard };
/** The kinds of widget, in the groups Add a Widget lists them in. */
const KIND_GROUPS: { title: string; types: WidgetType[] }[] = [
  { title: "widgets.group.games", types: GAME_TYPES },
  { title: "widgets.group.music", types: MUSIC_TYPES },
  { title: "widgets.group.books", types: BOOK_TYPES },
  { title: "widgets.group.screen", types: SCREEN_TYPES },
  { title: "widgets.group.cards", types: ["custom"] },
  { title: "widgets.group.time", types: ["countdown", "localtime"] },
  { title: "widgets.group.stickers", types: ["stickers"] },
];
const IMAGE_TYPES = "image/png,image/jpeg,image/gif,image/webp";
/** What a dragged row carries, so a drop from anywhere else is ignored. */
const ROW_DRAG = "application/x-nexa-widget";

/**
 * Drag a row of a list over the others, with where it would land: the row
 * under the pointer and which half of it. Shared by the widgets in Settings
 * and the games in a widget, which are both short lists of rows. `row` goes
 * on each row, which is where a drop lands; `grip` on what is dragged, the
 * whole row where it holds only buttons, or just its handle where it holds a
 * text box (one inside something draggable cannot be clicked into in every
 * browser).
 */
function useRowDrag<T>(items: T[], keyOf: (item: T) => string, onChange: (next: T[]) => void) {
  const [drag, setDrag] = useState<string | null>(null);
  const [drop, setDrop] = useState<{ key: string; after: boolean } | null>(null);
  const end = () => {
    setDrag(null);
    setDrop(null);
  };
  const row = (key: string) => ({
    "data-dragging": drag === key ? "" : undefined,
    "data-drop": drop?.key === key ? (drop.after ? "after" : "before") : undefined,
    onDragOver: (e: DragEvent<HTMLElement>) => {
      if (!drag) return;
      e.preventDefault();
      e.dataTransfer.dropEffect = "move";
      if (drag === key) return setDrop(null);
      const r = e.currentTarget.getBoundingClientRect();
      const after = e.clientY > r.top + r.height / 2;
      if (drop?.key !== key || drop.after !== after) setDrop({ key, after });
    },
    onDrop: (e: DragEvent<HTMLElement>) => {
      e.preventDefault();
      if (drag && drop && drag !== drop.key) {
        const moving = items.find((x) => keyOf(x) === drag);
        if (moving !== undefined) {
          const rest = items.filter((x) => keyOf(x) !== drag);
          const at = rest.findIndex((x) => keyOf(x) === drop.key);
          rest.splice(at + (drop.after ? 1 : 0), 0, moving);
          onChange(rest);
        }
      }
      end();
    },
  });
  const grip = (key: string) => ({
    draggable: true,
    onDragStart: (e: DragEvent<HTMLElement>) => {
      e.dataTransfer.effectAllowed = "move";
      e.dataTransfer.setData(ROW_DRAG, key);
      // the whole row goes with the pointer, wherever it was picked up
      const li = e.currentTarget.closest("li");
      if (li && li !== e.currentTarget) {
        const r = li.getBoundingClientRect();
        e.dataTransfer.setDragImage(li, e.clientX - r.left, e.clientY - r.top);
      }
      setDrag(key);
    },
    onDragEnd: end,
  });
  return { row, grip };
}

/** Moves the item with `key` by `delta` places, or says it cannot. */
function moved<T>(items: T[], keyOf: (item: T) => string, key: string, delta: number): T[] | null {
  const at = items.findIndex((x) => keyOf(x) === key);
  const to = at + delta;
  if (at < 0 || to < 0 || to >= items.length) return null;
  const next = [...items];
  const [item] = next.splice(at, 1);
  next.splice(to, 0, item!);
  return next;
}

/** Add a Widget: every kind, in groups, each with what it shows, and why one cannot be added. */
export function AddWidgetDialog({ widgets, onPick, onClose }: { widgets: Widget[]; onPick: (type: WidgetType) => void; onClose: () => void }) {
  const cards = widgets.filter((w) => w.type === "custom").length;
  const ids = useId();
  return (
    <Modal open onClose={onClose} title={t("widgets.addOne")} width={480}>
      <div className={s.groups}>
        {KIND_GROUPS.map((group, g) => (
          <section key={group.title} aria-labelledby={`${ids}-${g}`}>
            <h3 className={s.groupTitle} id={`${ids}-${g}`}>{t(group.title)}</h3>
            <ul className={s.kinds}>
              {group.types.map((type) => {
                const Icon = KIND_ICONS[type];
                const taken = type === "custom" ? cards >= MAX_CARDS : widgets.some((w) => w.type === type);
                return (
                  <li key={type}>
                    <button className={s.kind} disabled={taken} onClick={() => onPick(type)} data-testid={`widget-kind-${type}`}>
                      <span className={s.icon} aria-hidden="true"><Icon size={20} /></span>
                      <span className={s.kindText}>
                        <span className={s.kindName}>{widgetTitle({ type })}</span>
                        <span className={s.kindAbout}>{taken ? (type === "custom" ? t("widgets.cardsFull") : t("widgets.onProfile")) : t(`widgets.about.${type}`)}</span>
                      </span>
                    </button>
                  </li>
                );
              })}
            </ul>
          </section>
        ))}
      </div>
    </Modal>
  );
}

interface FoundGame {
  id: string;
  name: string;
  cover?: string;
  year?: number;
}

const gameKey = (g: WidgetGame) => (g.id ? `g:${g.id}` : `n:${g.name.toLowerCase()}`);
const TAG_SUGGESTIONS = ["casual", "competitive", "coop", "solo", "ranked", "withFriends"];

/**
 * A games widget: a search of IGDB's games, through this server, that adds
 * what it finds, or a game typed in by name with a picture of its own; and
 * the games chosen, in order, with how each is played (Playing Now) or why
 * it is the one (Favourite Game).
 */
export function GameWidgetDialog({ initial, onDone, onClose }: { initial: GameWidget; onDone: (w: GameWidget) => void; onClose: () => void }) {
  const toast = useUI((u) => u.toast);
  const most = MOST_GAMES[initial.type];
  const single = most === 1;
  const [games, setGames] = useState<WidgetGame[]>(initial.games);
  const [note, setNote] = useState(initial.note ?? "");
  const [q, setQ] = useState("");
  const [found, setFound] = useState<{ q: string; games: FoundGame[] } | null>(null);
  const [searching, setSearching] = useState(false);
  const [failed, setFailed] = useState(false);
  // whether this server can search IGDB at all, asked once as the dialog opens
  const [ready, setReady] = useState<boolean | null>(null);
  const [crop, setCrop] = useState<{ file: File; key: string } | null>(null);
  const [uploading, setUploading] = useState<string | null>(null);
  const [tagFocus, setTagFocus] = useState<string | null>(null);
  const seq = useRef(0);
  const searchRef = useRef<HTMLInputElement>(null);
  const fileRef = useRef<HTMLInputElement>(null);
  const pictureFor = useRef<string | null>(null);
  const searchLabel = useId();
  const query = q.trim();

  useEffect(() => {
    let alive = true;
    usersApi.searchGames("").then(
      (res) => alive && setReady(res.ready),
      () => alive && setReady(true),
    );
    return () => {
      alive = false;
    };
  }, []);

  useEffect(() => {
    const n = ++seq.current;
    setFailed(false);
    if (!query || ready === false) {
      setFound(null);
      setSearching(false);
      return;
    }
    setSearching(true);
    const timer = setTimeout(() => {
      usersApi.searchGames(query).then(
        (res) => {
          if (n !== seq.current) return;
          setReady(res.ready);
          setFound({ q: query, games: res.games });
          setSearching(false);
        },
        () => {
          if (n !== seq.current) return;
          setFailed(true);
          setSearching(false);
        },
      );
    }, 280);
    return () => clearTimeout(timer);
  }, [query, ready]);

  const has = (g: WidgetGame) => games.some((x) => gameKey(x) === gameKey(g));
  const isFull = !single && games.length >= most;
  const add = (g: WidgetGame) => {
    if (single) return setGames([g]);
    if (has(g)) return setGames(games.filter((x) => gameKey(x) !== gameKey(g)));
    if (isFull) return;
    setGames([...games, g]);
  };
  const addByName = () => {
    const name = query.slice(0, LIMITS.game);
    if (!name) return;
    add({ name });
    setQ("");
    searchRef.current?.focus();
  };
  const patch = (key: string, change: Partial<WidgetGame>) => setGames((list) => list.map((g) => (gameKey(g) === key ? { ...g, ...change } : g)));
  const drag = useRowDrag(games, gameKey, setGames);
  const reorder = useReorder({
    ids: games.map(gameKey),
    move: (key, delta) => {
      const next = moved(games, gameKey, key, delta);
      if (next) setGames(next);
      return !!next;
    },
    name: (key) => games.find((g) => gameKey(g) === key)?.name ?? "",
  });
  const upload = async (key: string, file: Blob, area: { x: number; y: number; w: number; h: number } | null) => {
    setUploading(key);
    try {
      const res = await usersApi.uploadWidgetImage(file, area, "cover");
      patch(key, { image: res.id });
    } catch (err) {
      toast(describeError(err, t("composer.composer.uploadFailed")), "error");
    } finally {
      setUploading(null);
    }
  };
  const choosePicture = (key: string) => {
    pictureFor.current = key;
    fileRef.current?.click();
  };

  // a name typed that the search did not find exactly can go in as it is
  const exact = found?.q === query && found.games.some((g) => g.name.toLowerCase() === query.toLowerCase());
  const byName = !!query && !exact && !games.some((g) => !g.id && g.name.toLowerCase() === query.toLowerCase());
  const results = found && found.q === query ? found.games : [];
  const canSave = games.length > 0 && uploading === null;

  const resultRow = (g: FoundGame) => {
    const game: WidgetGame = { id: g.id, name: g.name, ...(g.cover ? { cover: g.cover } : {}) };
    const on = has(game);
    return (
      <li key={g.id}>
        <button className={s.result} aria-pressed={on} disabled={!on && isFull} onClick={() => add(game)} data-testid="game-result">
          <GameCover game={game} css={30} className={s.resultCover} />
          <span className={s.resultText}>
            <span className={s.resultName} dir="auto">{g.name}</span>
            {g.year ? <span className={s.resultYear}>{g.year}</span> : null}
          </span>
          <span className={s.resultMark} aria-hidden="true">{on ? <Check size={15} /> : <Plus size={15} />}</span>
        </button>
      </li>
    );
  };

  return (
    <Modal
      open
      onClose={onClose}
      title={widgetTitle(initial)}
      width={520}
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>{t("chat.editMessage.cancel")}</Button>
          <Button onClick={() => onDone({ ...initial, games, ...(single && note.trim() ? { note: note.trim() } : { note: undefined }) })} disabled={!canSave} data-testid="widget-done">{t("pick.done")}</Button>
        </>
      }
    >
      <input
        ref={fileRef}
        type="file"
        accept={IMAGE_TYPES}
        hidden
        onChange={(e) => {
          const f = e.target.files?.[0];
          const key = pictureFor.current;
          if (f && key) setCrop({ file: f, key });
          e.target.value = "";
        }}
      />
      <span className={s.label} id={searchLabel}>{single ? t("widgets.game.searchOne") : t("widgets.game.searchMany")}</span>
      <div className={s.searchBox}>
        <Search size={17} className={s.searchIcon} aria-hidden="true" />
        <TextInput
          ref={searchRef}
          value={q}
          onChange={(e) => setQ(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === "Enter") {
              e.preventDefault();
              const first = results[0];
              if (first && !has({ id: first.id, name: first.name })) add({ id: first.id, name: first.name, ...(first.cover ? { cover: first.cover } : {}) });
              else if (!first && byName && ready === false) addByName();
            }
          }}
          maxLength={LIMITS.game}
          placeholder={ready === false ? t("widgets.game.namePlaceholder") : t("widgets.game.searchPlaceholder")}
          aria-labelledby={searchLabel}
          autoComplete="off"
          spellCheck={false}
          data-testid="game-search"
          autoFocus
        />
      </div>
      {ready === false ? <p className={s.note}>{t("widgets.game.notSetUp")}</p> : null}
      {query ? (
        <ul className={s.results} aria-label={t("widgets.game.results")} aria-busy={searching || undefined}>
          {results.map(resultRow)}
          {searching && !results.length ? <li><p className={s.status}>{t("widgets.game.searching")}</p></li> : null}
          {!searching && failed ? <li><p className={s.status}>{t("widgets.game.unavailable")}</p></li> : null}
          {!searching && !failed && ready !== false && found?.q === query && !results.length ? <li><p className={s.status}>{t("widgets.game.noResults")}</p></li> : null}
          {byName && !searching ? (
            <li>
              <button className={s.result} disabled={isFull} onClick={addByName} data-testid="game-by-name">
                <span className={s.byName} aria-hidden="true"><Plus size={14} /></span>
                <span className={s.resultText}><span className={s.resultName} dir="auto">{t("widgets.game.addByName", { name: query })}</span></span>
              </button>
            </li>
          ) : null}
        </ul>
      ) : null}
      {ready ? (
        <p className={s.credit}>
          <Tr id="widgets.game.credit" values={{ site: <a href="https://www.igdb.com" target="_blank" rel="noopener noreferrer">IGDB.com</a> }} />
        </p>
      ) : null}

      <div className={s.labelRow}>
        <span className={s.label}>{single ? t("widgets.game.yourFavorite") : t("widgets.game.yours")}</span>
        {!single ? <span className={s.count}>{t("widgets.used", { count: games.length, max: most })}</span> : null}
      </div>
      {games.length === 0 ? (
        <p className={s.empty}>{single ? t("widgets.game.noneYetOne") : t("widgets.game.noneYet")}</p>
      ) : (
        <ul className={s.chosen} aria-label={single ? t("widgets.game.yourFavorite") : t("widgets.game.yours")}>
          {games.map((g) => {
            const key = gameKey(g);
            const row = single ? {} : { "data-reorder-id": key, onKeyDown: reorder.onKeyDown(key), ...drag.row(key) };
            return (
              <li key={key} className={s.chosenRow} data-single={single ? "" : undefined} {...row}>
                {!single ? <span className={s.grip} aria-hidden="true" {...drag.grip(key)}><GripVertical size={16} /></span> : null}
                <GameCover game={g} css={single ? 56 : 36} className={s.chosenCover} />
                <div className={s.chosenMain}>
                  <span className={s.chosenName} dir="auto">{g.name}</span>
                  {!g.cover ? (
                    <div className={s.chosenBtns}>
                      <button className={s.chip} disabled={uploading !== null} onClick={() => choosePicture(key)}>
                        {uploading === key ? <LoaderCircle size={14} className={s.spin} aria-hidden="true" /> : <ImagePlus size={14} aria-hidden="true" />}
                        {g.image ? t("widgets.changePicture") : t("widgets.addPicture")}
                      </button>
                      {g.image ? <button className={s.chip} onClick={() => patch(key, { image: undefined })}>{t("widgets.removePicture")}</button> : null}
                    </div>
                  ) : null}
                  {initial.type === "playing" ? (
                    <TagEditor
                      tags={g.tags ?? []}
                      onChange={(tags) => patch(key, { tags })}
                      game={g.name}
                      suggest={tagFocus === key}
                      onFocus={(on) => setTagFocus(on ? key : null)}
                    />
                  ) : null}
                </div>
                <button className={s.iconBtn} aria-label={t("widgets.game.remove", { name: g.name })} onClick={() => setGames(games.filter((x) => gameKey(x) !== key))}>
                  <X size={16} />
                </button>
              </li>
            );
          })}
        </ul>
      )}
      {!single && games.length > 1 ? <p className={s.small}>{reorder.hint}</p> : null}
      {reorder.live}

      {single ? (
        <Field label={t("widgets.game.noteLabel")} hint={t("composer.limit.left", { count: LIMITS.note - note.length })} className={s.noteField}>
          <TextArea value={note} onChange={(e) => setNote(e.target.value)} maxLength={LIMITS.note} rows={3} placeholder={t("widgets.game.notePlaceholder")} data-testid="widget-note" />
        </Field>
      ) : null}

      {crop ? (
        <ImageCropModal
          file={crop.file}
          aspect={3 / 4}
          maxSize={400}
          serverCrop
          title={t("widgets.game.cropTitle")}
          onClose={() => setCrop(null)}
          onDone={({ blob, crop: area }) => {
            const key = crop.key;
            setCrop(null);
            void upload(key, blob, area ?? null);
          }}
        />
      ) : null}
    </Modal>
  );
}

/** How someone plays a game they are playing now: up to three short tags, with a few to start from. */
function TagEditor({ tags, onChange, game, suggest, onFocus }: { tags: string[]; onChange: (tags: string[]) => void; game: string; suggest: boolean; onFocus: (on: boolean) => void }) {
  const [draft, setDraft] = useState("");
  const add = (raw: string) => {
    const tag = raw.trim().replace(/\s+/g, " ").slice(0, LIMITS.tag);
    setDraft("");
    if (!tag || tags.length >= LIMITS.tags || tags.some((x) => x.toLowerCase() === tag.toLowerCase())) return;
    onChange([...tags, tag]);
  };
  const onKey = (e: KeyboardEvent<HTMLInputElement>) => {
    if (e.key === "Enter" || e.key === ",") {
      e.preventDefault();
      add(draft);
    } else if (e.key === "Backspace" && !draft && tags.length) {
      onChange(tags.slice(0, -1));
    }
  };
  const room = tags.length < LIMITS.tags;
  const ideas = TAG_SUGGESTIONS.map((k) => t(`widgets.tag.${k}`)).filter((x) => !tags.some((tag) => tag.toLowerCase() === x.toLowerCase()));
  return (
    <>
      <div className={s.tags}>
        {tags.map((tag) => (
          <span key={tag} className={s.tag} dir="auto">
            <span>{tag}</span>
            <button aria-label={t("widgets.game.removeTag", { tag })} onClick={() => onChange(tags.filter((x) => x !== tag))}><X size={12} /></button>
          </span>
        ))}
        {room ? (
          <input
            className={s.tagInput}
            value={draft}
            maxLength={LIMITS.tag}
            placeholder={t("widgets.game.tagPlaceholder")}
            aria-label={t("widgets.game.tagLabel", { name: game })}
            onChange={(e) => setDraft(e.target.value.replace(/,/g, ""))}
            onKeyDown={onKey}
            onFocus={() => onFocus(true)}
            onBlur={() => {
              add(draft);
              onFocus(false);
            }}
          />
        ) : null}
      </div>
      {suggest && room && ideas.length ? (
        <div className={s.suggest}>
          {ideas.map((idea) => (
            // pressed without taking the focus from the box, which would put the box away first
            <button key={idea} onMouseDown={(e) => e.preventDefault()} onClick={() => add(idea)}>{idea}</button>
          ))}
        </div>
      ) : null}
    </>
  );
}

const musicKey = (m: WidgetMusic) => (m.id ? `d:${m.id}` : `n:${m.name.toLowerCase()}\u0000${(m.by ?? "").toLowerCase()}`);
const compact = (n: number) => {
  try {
    return new Intl.NumberFormat(undefined, { notation: "compact", maximumFractionDigits: 1 }).format(n);
  } catch {
    return String(n);
  }
};

/** What tells one found apart from others of its name: whose it is, what it is, how it was found. */
function foundAbout(type: MusicWidget["type"], r: FoundMusic): string {
  if (type === "artists") {
    if (r.via) return t(r.via.kind === "song" ? "widgets.music.viaSong" : "widgets.music.viaAlbum", { name: r.via.name });
    return r.fans ? t("widgets.music.fans", { count: r.fans, fans: compact(r.fans) }) : "";
  }
  const what = r.record === "single" ? t("widgets.music.single") : r.record === "ep" ? t("widgets.music.ep") : r.record === "compile" ? t("widgets.music.compilation") : "";
  return [r.by, type === "songs" ? r.album : what].filter(Boolean).join(" · ");
}

/**
 * A music widget: a search of Deezer through this server that finds what
 * was meant from a name, a song or an album, or one typed in by name with a
 * picture of its own; and the ones chosen, in order.
 */
export function MusicWidgetDialog({ initial, onDone, onClose }: { initial: MusicWidget; onDone: (w: MusicWidget) => void; onClose: () => void }) {
  const toast = useUI((u) => u.toast);
  const type = initial.type;
  const most = MOST_MUSIC[type];
  const [items, setItems] = useState<WidgetMusic[]>(initial.music);
  const [q, setQ] = useState("");
  const [found, setFound] = useState<{ q: string; results: FoundMusic[] } | null>(null);
  const [searching, setSearching] = useState(false);
  const [failed, setFailed] = useState(false);
  // whether this server can search Deezer at all, asked once as the dialog opens
  const [ready, setReady] = useState<boolean | null>(null);
  const [crop, setCrop] = useState<{ file: File; key: string } | null>(null);
  const [uploading, setUploading] = useState<string | null>(null);
  const searchRef = useRef<HTMLInputElement>(null);
  const fileRef = useRef<HTMLInputElement>(null);
  const pictureFor = useRef<string | null>(null);
  const searchLabel = useId();
  const query = q.trim();

  useEffect(() => {
    let alive = true;
    usersApi.searchMusic(type, "").then(
      (res) => alive && setReady(res.ready),
      () => alive && setReady(true),
    );
    return () => {
      alive = false;
    };
  }, [type]);

  useEffect(() => {
    setFailed(false);
    if (!query || ready === false) {
      setFound(null);
      setSearching(false);
      return;
    }
    setSearching(true);
    // a search a key press made stale is called off, not just ignored
    const ctrl = new AbortController();
    const timer = setTimeout(() => {
      usersApi.searchMusic(type, query, ctrl.signal).then(
        (res) => {
          setReady(res.ready);
          setFound({ q: query, results: res.results });
          setSearching(false);
        },
        () => {
          if (ctrl.signal.aborted) return;
          setFailed(true);
          setSearching(false);
        },
      );
    }, 280);
    return () => {
      clearTimeout(timer);
      ctrl.abort();
    };
  }, [query, ready, type]);

  const has = (m: WidgetMusic) => items.some((x) => musicKey(x) === musicKey(m));
  const isFull = items.length >= most;
  const add = (m: WidgetMusic) => {
    if (has(m)) return setItems(items.filter((x) => musicKey(x) !== musicKey(m)));
    if (isFull) return;
    setItems([...items, m]);
  };
  const fromFound = (r: FoundMusic): WidgetMusic => ({ id: r.id, name: r.name, ...(r.by && type !== "artists" ? { by: r.by } : {}), ...(r.art ? { art: r.art } : {}), ...(r.duration && type === "songs" ? { duration: r.duration } : {}) });
  const addByName = () => {
    const name = query.slice(0, LIMITS.music);
    if (!name) return;
    add({ name });
    setQ("");
    searchRef.current?.focus();
  };
  const patch = (key: string, change: Partial<WidgetMusic>) => setItems((list) => list.map((m) => (musicKey(m) === key ? { ...m, ...change } : m)));
  const drag = useRowDrag(items, musicKey, setItems);
  const reorder = useReorder({
    ids: items.map(musicKey),
    move: (key, delta) => {
      const next = moved(items, musicKey, key, delta);
      if (next) setItems(next);
      return !!next;
    },
    name: (key) => items.find((m) => musicKey(m) === key)?.name ?? "",
  });
  const upload = async (key: string, file: Blob, area: { x: number; y: number; w: number; h: number } | null) => {
    setUploading(key);
    try {
      const res = await usersApi.uploadWidgetImage(file, area, "square");
      patch(key, { image: res.id });
    } catch (err) {
      toast(describeError(err, t("composer.composer.uploadFailed")), "error");
    } finally {
      setUploading(null);
    }
  };
  const choosePicture = (key: string) => {
    pictureFor.current = key;
    fileRef.current?.click();
  };

  const results = found && found.q === query ? found.results : [];
  // a name typed that the search did not find exactly can go in as it is
  const exact = results.some((r) => r.name.toLowerCase() === query.toLowerCase());
  const byName = !!query && !exact && !items.some((m) => !m.id && m.name.toLowerCase() === query.toLowerCase());
  const canSave = items.length > 0 && uploading === null;
  const placeholder = ready === false ? t("widgets.music.namePlaceholder") : t(`widgets.music.placeholder.${type}`);

  const resultRow = (r: FoundMusic) => {
    const item = fromFound(r);
    const on = has(item);
    const about = foundAbout(type, r);
    return (
      <li key={r.id}>
        <button className={s.result} aria-pressed={on} disabled={!on && isFull} onClick={() => add(item)} data-testid="music-result">
          <MusicArt type={type} item={item} css={36} className={s.resultArt} />
          <span className={s.resultStack}>
            <span className={s.resultName} dir="auto">{r.name}</span>
            {about ? <span className={s.resultAbout} dir="auto">{about}</span> : null}
          </span>
          {r.duration && type === "songs" ? <span className={s.resultYear}>{songLength(r.duration)}</span> : null}
          <span className={s.resultMark} aria-hidden="true">{on ? <Check size={15} /> : <Plus size={15} />}</span>
        </button>
      </li>
    );
  };

  return (
    <Modal
      open
      onClose={onClose}
      title={widgetTitle(initial)}
      width={520}
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>{t("chat.editMessage.cancel")}</Button>
          <Button onClick={() => onDone({ ...initial, music: items })} disabled={!canSave} data-testid="widget-done">{t("pick.done")}</Button>
        </>
      }
    >
      <input
        ref={fileRef}
        type="file"
        accept={IMAGE_TYPES}
        hidden
        onChange={(e) => {
          const f = e.target.files?.[0];
          const key = pictureFor.current;
          if (f && key) setCrop({ file: f, key });
          e.target.value = "";
        }}
      />
      <span className={s.label} id={searchLabel}>{t(`widgets.music.search.${type}`)}</span>
      <div className={s.searchBox}>
        <Search size={17} className={s.searchIcon} aria-hidden="true" />
        <TextInput
          ref={searchRef}
          value={q}
          onChange={(e) => setQ(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === "Enter") {
              e.preventDefault();
              const first = results[0];
              if (first && !has(fromFound(first))) add(fromFound(first));
              else if (!first && byName && ready === false) addByName();
            }
          }}
          maxLength={LIMITS.music}
          placeholder={placeholder}
          aria-labelledby={searchLabel}
          autoComplete="off"
          spellCheck={false}
          data-testid="music-search"
          autoFocus
        />
      </div>
      {ready === false ? <p className={s.note}>{t("widgets.music.notSetUp")}</p> : null}
      {query ? (
        <ul className={s.results} aria-label={t("widgets.music.results")} aria-busy={searching || undefined}>
          {results.map(resultRow)}
          {searching && !results.length ? <li><p className={s.status}>{t("widgets.game.searching")}</p></li> : null}
          {!searching && failed ? <li><p className={s.status}>{t("widgets.music.unavailable")}</p></li> : null}
          {!searching && !failed && ready !== false && found?.q === query && !results.length ? <li><p className={s.status}>{t("widgets.music.noResults")}</p></li> : null}
          {byName && !searching ? (
            <li>
              <button className={s.result} disabled={isFull} onClick={addByName} data-testid="music-by-name">
                <span className={s.byName} data-square="" data-round={type === "artists" || undefined} aria-hidden="true"><Plus size={14} /></span>
                <span className={s.resultText}><span className={s.resultName} dir="auto">{t("widgets.game.addByName", { name: query })}</span></span>
              </button>
            </li>
          ) : null}
        </ul>
      ) : null}
      {ready ? (
        <p className={s.credit}>
          <Tr id="widgets.music.credit" values={{ site: <a href="https://www.deezer.com" target="_blank" rel="noopener noreferrer">Deezer</a> }} />
        </p>
      ) : null}

      <div className={s.labelRow}>
        <span className={s.label}>{t(`widgets.music.yours.${type}`)}</span>
        <span className={s.count}>{t("widgets.used", { count: items.length, max: most })}</span>
      </div>
      {items.length === 0 ? (
        <p className={s.empty}>{t("widgets.music.noneYet")}</p>
      ) : (
        <ul className={s.chosen} aria-label={t(`widgets.music.yours.${type}`)}>
          {items.map((m) => {
            const key = musicKey(m);
            return (
              <li key={key} className={s.chosenRow} data-reorder-id={key} onKeyDown={reorder.onKeyDown(key)} {...drag.row(key)}>
                <span className={s.grip} aria-hidden="true" {...drag.grip(key)}><GripVertical size={16} /></span>
                <MusicArt type={type} item={m} css={36} className={s.chosenArt} />
                <div className={s.chosenMain}>
                  <span className={s.chosenText}>
                    <span className={s.chosenName} dir="auto">{m.name}</span>
                    {m.by ? <span className={s.chosenBy} dir="auto">{m.by}</span> : null}
                  </span>
                  {!m.art && !m.id ? (
                    <div className={s.chosenBtns}>
                      <button className={s.chip} disabled={uploading !== null} onClick={() => choosePicture(key)}>
                        {uploading === key ? <LoaderCircle size={14} className={s.spin} aria-hidden="true" /> : <ImagePlus size={14} aria-hidden="true" />}
                        {m.image ? t("widgets.changePicture") : t("widgets.addPicture")}
                      </button>
                      {m.image ? <button className={s.chip} onClick={() => patch(key, { image: undefined })}>{t("widgets.removePicture")}</button> : null}
                    </div>
                  ) : null}
                </div>
                <button className={s.iconBtn} aria-label={t("widgets.game.remove", { name: m.name })} onClick={() => setItems(items.filter((x) => musicKey(x) !== key))}>
                  <X size={16} />
                </button>
              </li>
            );
          })}
        </ul>
      )}
      {items.length > 1 ? <p className={s.small}>{reorder.hint}</p> : null}
      {reorder.live}

      {crop ? (
        <ImageCropModal
          file={crop.file}
          aspect={1}
          round={type === "artists"}
          maxSize={512}
          serverCrop
          title={t("widgets.card.cropTitle")}
          onClose={() => setCrop(null)}
          onDone={({ blob, crop: area }) => {
            const key = crop.key;
            setCrop(null);
            void upload(key, blob, area ?? null);
          }}
        />
      ) : null}
    </Modal>
  );
}

/** A card's colour as the colour picker takes it. */
const hexColour = (c: number) => `#${c.toString(16).padStart(6, "0")}`;

/** A link as it was typed, with the https:// it was most likely meant to have. */
function withScheme(raw: string): string {
  const v = raw.trim();
  if (!v || /^[a-z][a-z0-9+.-]*:/i.test(v)) return v;
  return `https://${v.replace(/^\/+/, "")}`;
}

function linkProblem(raw: string): string | null {
  if (!raw) return null;
  try {
    const u = new URL(raw);
    if ((u.protocol !== "https:" && u.protocol !== "http:") || !u.hostname || u.username || u.password) return t("widgets.card.badLink");
    return null;
  } catch {
    return t("widgets.card.badLink");
  }
}

/**
 * A card of your own: a title, a picture across its top or beside the
 * title, a few words, up to six stats and a link, and the card as the
 * profile will show it.
 */
export function CardDialog({ initial, isNew, onDone, onClose }: { initial: CardWidget; isNew: boolean; onDone: (w: CardWidget) => void; onClose: () => void }) {
  const toast = useUI((u) => u.toast);
  const [title, setTitle] = useState(initial.title);
  const [text, setText] = useState(initial.text ?? "");
  const [image, setImage] = useState<{ id: string; shape: "wide" | "square" } | null>(initial.image ? { id: initial.image, shape: initial.shape ?? "wide" } : null);
  const [stats, setStats] = useState<WidgetStat[]>(initial.stats ?? []);
  const [links, setLinks] = useState<{ url: string; label: string }[]>(() => (initial.links ?? []).map((l) => ({ url: l.url, label: l.label ?? "" })));
  const [color, setColor] = useState<number | undefined>(initial.color);
  const [color2, setColor2] = useState<number | undefined>(initial.color2);
  const [crop, setCrop] = useState<{ file: File; shape: "wide" | "square" } | null>(null);
  const [uploading, setUploading] = useState<"wide" | "square" | null>(null);
  const [tried, setTried] = useState(false);
  const fileRef = useRef<HTMLInputElement>(null);
  const shapeRef = useRef<"wide" | "square">("wide");
  const ids = useId();

  const typed = links.map((l) => ({ url: withScheme(l.url), label: l.label.trim() })).filter((l) => l.url);
  const badLink = typed.map((l) => linkProblem(l.url)).find((p) => p) ?? null;
  const filled = stats
    .map((st): WidgetStat => (st.bar !== undefined ? { label: st.label.trim(), value: `${st.bar}%`, bar: st.bar } : { label: st.label.trim(), value: st.value.trim() }))
    .filter((st) => st.label || (st.bar === undefined && st.value));
  const halfStat = filled.some((st) => !st.label || !st.value);
  const something = !!text.trim() || !!image || filled.length > 0 || typed.length > 0;
  const card: CardWidget = {
    id: initial.id,
    type: "custom",
    title: title.trim(),
    ...(image ? { image: image.id, shape: image.shape } : {}),
    ...(text.trim() ? { text: text.trim() } : {}),
    ...(filled.length ? { stats: filled } : {}),
    links: typed.map((l) => ({ url: l.url, ...(l.label ? { label: l.label } : {}) })),
    ...(color !== undefined ? { color, ...(color2 !== undefined ? { color2 } : {}) } : {}),
  };
  const problem = !card.title ? t("widgets.card.needsTitle") : !something ? t("widgets.card.needsMore") : halfStat ? t("widgets.card.halfStat") : badLink;
  const finish = () => {
    setTried(true);
    if (problem || uploading) return;
    onDone(card);
  };

  const pick = (shape: "wide" | "square") => {
    shapeRef.current = shape;
    fileRef.current?.click();
  };
  const upload = async (file: Blob, area: { x: number; y: number; w: number; h: number } | null, shape: "wide" | "square") => {
    setUploading(shape);
    try {
      const res = await usersApi.uploadWidgetImage(file, area, shape);
      setImage({ id: res.id, shape });
    } catch (err) {
      toast(describeError(err, t("composer.composer.uploadFailed")), "error");
    } finally {
      setUploading(null);
    }
  };
  const setStat = (i: number, change: Partial<WidgetStat>) => setStats((list) => list.map((st, j) => (j === i ? { ...st, ...change } : st)));

  return (
    <Modal
      open
      onClose={onClose}
      title={isNew ? t("widgets.card.titleNew") : t("widgets.card.titleEdit")}
      width={540}
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>{t("chat.editMessage.cancel")}</Button>
          <Button onClick={finish} disabled={uploading !== null || (tried && !!problem)} data-testid="widget-done">{t("pick.done")}</Button>
        </>
      }
    >
      <input
        ref={fileRef}
        type="file"
        accept={IMAGE_TYPES}
        hidden
        onChange={(e) => {
          const f = e.target.files?.[0];
          if (f) setCrop({ file: f, shape: shapeRef.current });
          e.target.value = "";
        }}
      />
      <div className={s.stack}>
        <Field label={t("widgets.card.title")} error={tried && !card.title ? t("widgets.card.needsTitle") : null}>
          <TextInput value={title} onChange={(e) => setTitle(e.target.value)} maxLength={LIMITS.title} placeholder={t("widgets.card.titlePlaceholder")} autoFocus data-testid="card-title" />
        </Field>

        <div role="group" aria-labelledby={`${ids}-picture`}>
          <span className={s.label} id={`${ids}-picture`}>{t("widgets.card.picture")}</span>
          {image ? (
            <div className={s.picture}>
              <img className={s.pictureImg} data-shape={image.shape} src={widgetImageUrl(image.id, image.shape === "wide" ? 300 : 104)} alt="" />
              <div className={s.chosenBtns}>
                <button className={s.chip} disabled={uploading !== null} onClick={() => pick(image.shape)}>
                  {uploading ? <LoaderCircle size={14} className={s.spin} aria-hidden="true" /> : <ImagePlus size={14} aria-hidden="true" />} {t("widgets.changePicture")}
                </button>
                <button className={s.chip} onClick={() => setImage(null)}>{t("widgets.removePicture")}</button>
              </div>
            </div>
          ) : (
            <div className={s.pictures}>
              <button className={s.pick} data-shape="wide" disabled={uploading !== null} onClick={() => pick("wide")} data-testid="card-pick-wide">
                {uploading === "wide" ? <LoaderCircle size={20} className={s.spin} aria-hidden="true" /> : <ImagePlus size={20} aria-hidden="true" />}
                {t("widgets.card.wide")}
              </button>
              <button className={s.pick} data-shape="square" disabled={uploading !== null} onClick={() => pick("square")} data-testid="card-pick-square">
                {uploading === "square" ? <LoaderCircle size={20} className={s.spin} aria-hidden="true" /> : <ImagePlus size={20} aria-hidden="true" />}
                {t("widgets.card.square")}
              </button>
            </div>
          )}
        </div>

        <div role="group" aria-labelledby={`${ids}-text`}>
          <span className={s.label} id={`${ids}-text`}>{t("widgets.card.text")}</span>
          <FormattedText value={text} onChange={setText} max={LIMITS.text} rows={3} placeholder={t("widgets.card.textPlaceholder")} title={t("widgets.card.text")} testId="card-text" />
        </div>

        <div role="group" aria-labelledby={`${ids}-stats`}>
          <span className={s.label} id={`${ids}-stats`}>{t("widgets.card.stats")}</span>
          <p className={s.note} style={{ margin: "-4px 0 8px" }}>{t("widgets.card.statsHint")}</p>
          {stats.length ? (
            <div className={s.statRows}>
              {stats.map((st, i) => (
                <div key={i} className={s.statRow}>
                  <TextInput value={st.label} onChange={(e) => setStat(i, { label: e.target.value })} maxLength={LIMITS.label} placeholder={t("widgets.card.statLabel")} aria-label={t("widgets.card.statLabelN", { n: i + 1 })} />
                  {st.bar !== undefined ? (
                    <TextInput
                      type="number"
                      min={0}
                      max={100}
                      inputMode="numeric"
                      value={String(st.bar)}
                      onChange={(e) => setStat(i, { bar: Math.max(0, Math.min(100, Math.round(Number(e.target.value) || 0))) })}
                      placeholder={t("widgets.card.statPercent")}
                      aria-label={t("widgets.card.statPercentN", { n: i + 1 })}
                      data-testid="card-stat-percent"
                    />
                  ) : (
                    <TextInput value={st.value} onChange={(e) => setStat(i, { value: e.target.value })} maxLength={LIMITS.value} placeholder={t("widgets.card.statValue")} aria-label={t("widgets.card.statValueN", { n: i + 1 })} />
                  )}
                  <button
                    className={s.iconBtn}
                    aria-pressed={st.bar !== undefined}
                    aria-label={t("widgets.card.statBarN", { n: i + 1 })}
                    title={t("widgets.card.statBar")}
                    onClick={() => setStats((list) => list.map((x, j) => (j !== i ? x : x.bar !== undefined ? { label: x.label, value: "" } : { label: x.label, value: "", bar: Math.max(0, Math.min(100, parseInt(x.value, 10) || 50)) })))}
                    data-testid="card-stat-bar"
                  >
                    <ChartNoAxesColumn size={16} />
                  </button>
                  <button className={s.iconBtn} aria-label={t("widgets.card.removeStat", { n: i + 1 })} onClick={() => setStats(stats.filter((_, j) => j !== i))}><X size={16} /></button>
                </div>
              ))}
            </div>
          ) : null}
          {stats.length < LIMITS.stats ? (
            <button className={s.chip} style={{ marginTop: stats.length ? 8 : 0 }} onClick={() => setStats([...stats, { label: "", value: "" }])} data-testid="card-add-stat">
              <Plus size={14} aria-hidden="true" /> {t("widgets.card.addStat")}
            </button>
          ) : null}
        </div>

        <div role="group" aria-labelledby={`${ids}-link`}>
          <span className={s.label} id={`${ids}-link`}>{t("widgets.card.links")}</span>
          {links.length ? (
            <div className={s.linkRows}>
              {links.map((l, i) => {
                const bad = l.url.trim() ? linkProblem(withScheme(l.url)) : null;
                const setLink = (change: Partial<{ url: string; label: string }>) => setLinks((list) => list.map((x, j) => (j === i ? { ...x, ...change } : x)));
                return (
                  <div key={i}>
                    <div className={s.linkRow}>
                      <TextInput value={l.url} onChange={(e) => setLink({ url: e.target.value })} onBlur={() => setLink({ url: withScheme(l.url) })} maxLength={512} placeholder="https://" aria-label={t("widgets.card.linkUrlN", { n: i + 1 })} inputMode="url" autoComplete="off" spellCheck={false} invalid={!!bad} data-testid="card-link" />
                      <TextInput value={l.label} onChange={(e) => setLink({ label: e.target.value })} maxLength={LIMITS.linkLabel} placeholder={t("widgets.card.linkLabel")} aria-label={t("widgets.card.linkLabelN", { n: i + 1 })} disabled={!l.url.trim()} />
                      <button className={s.iconBtn} aria-label={t("widgets.card.removeLink", { n: i + 1 })} onClick={() => setLinks(links.filter((_, j) => j !== i))}><X size={16} /></button>
                    </div>
                    {bad ? <p className={s.error}>{bad}</p> : null}
                  </div>
                );
              })}
            </div>
          ) : null}
          {links.length < MAX_CARD_LINKS ? (
            <button className={s.chip} style={{ marginTop: links.length ? 8 : 0 }} onClick={() => setLinks([...links, { url: "", label: "" }])} data-testid="card-add-link">
              <Plus size={14} aria-hidden="true" /> {t("widgets.card.addLink")}
            </button>
          ) : null}
        </div>

        <div role="group" aria-labelledby={`${ids}-colour`}>
          <span className={s.label} id={`${ids}-colour`}>{t("widgets.card.colour")}</span>
          <p className={s.note} style={{ margin: "-4px 0 8px" }}>{t("widgets.card.colourHint")}</p>
          <div className={s.colourRow}>
            {color === undefined ? (
              <button className={s.chip} onClick={() => setColor(0x8000ff)} data-testid="card-add-colour">
                <Palette size={14} aria-hidden="true" /> {t("widgets.card.addColour")}
              </button>
            ) : (
              <>
                <span className={s.colourSwatch} style={{ background: hexColour(color) }}>
                  <ColourInput value={hexColour(color)} label={t("widgets.card.firstColour")} onChange={(v) => setColor(parseInt(v.slice(1, 7), 16))} testId="card-colour" />
                </span>
                {color2 !== undefined ? (
                  <span className={s.colourSwatch} style={{ background: hexColour(color2) }}>
                    <ColourInput value={hexColour(color2)} label={t("widgets.card.secondColour")} onChange={(v) => setColor2(parseInt(v.slice(1, 7), 16))} testId="card-colour-2" />
                  </span>
                ) : (
                  <button className={s.chip} onClick={() => setColor2(0x00b0f4)} data-testid="card-add-colour-2">
                    <Plus size={14} aria-hidden="true" /> {t("widgets.card.addSecondColour")}
                  </button>
                )}
                <button className={s.chip} onClick={() => { setColor(undefined); setColor2(undefined); }}>{t("widgets.card.noColour")}</button>
              </>
            )}
          </div>
        </div>

        <div>
          <span className={s.label}>{t("settings.profile.preview")}</span>
          <div className={s.previewSheet}>
            <ProfileWidgets widgets={[{ ...card, title: card.title || t("widgets.card.titlePlaceholder") }]} variant="modal" />
          </div>
        </div>
        {tried && problem ? <p className={s.error} role="alert">{problem}</p> : null}
      </div>

      {crop ? (
        <ImageCropModal
          file={crop.file}
          aspect={crop.shape === "wide" ? 12 / 5 : 1}
          maxSize={720}
          serverCrop
          title={t("widgets.card.cropTitle")}
          onClose={() => setCrop(null)}
          onDone={({ blob, crop: area }) => {
            const shape = crop.shape;
            setCrop(null);
            void upload(blob, area ?? null, shape);
          }}
        />
      ) : null}
    </Modal>
  );
}

/** A moment's date and time of day in this device's calendar, for the inputs ("2026-12-24", "18:30"). */
function localParts(ms: number): { date: string; time: string } {
  const d = new Date(ms);
  const two = (n: number) => String(n).padStart(2, "0");
  return { date: `${d.getFullYear()}-${two(d.getMonth() + 1)}-${two(d.getDate())}`, time: `${two(d.getHours())}:${two(d.getMinutes())}` };
}

/**
 * A countdown: what to, an emoji, the day, and a time of day if it counts to
 * a moment rather than a whole day; with the widget as the profile will show it.
 */
export function CountdownDialog({ initial, isNew, onDone, onClose }: { initial: CountdownWidget; isNew: boolean; onDone: (w: CountdownWidget) => void; onClose: () => void }) {
  const start = initial.at ? localParts(initial.at) : { date: initial.date ?? "", time: "" };
  const [title, setTitle] = useState(initial.title);
  const [emoji, setEmoji] = useState(initial.emoji ?? "");
  const [date, setDate] = useState(start.date);
  const [timed, setTimed] = useState(!!initial.at);
  const [time, setTime] = useState(start.time || "12:00");
  const [tried, setTried] = useState(false);
  const at = timed && date && time ? new Date(`${date}T${time}`).getTime() : NaN;
  const w: CountdownWidget = {
    id: initial.id,
    type: "countdown",
    title: title.trim(),
    ...(emoji.trim() ? { emoji: emoji.trim() } : {}),
    ...(timed ? (Number.isFinite(at) ? { at } : {}) : date ? { date } : {}),
  };
  const problem = !w.title ? t("widgets.countdown.needsTitle") : !w.date && !w.at ? t("widgets.countdown.needsDate") : null;
  const finish = () => {
    setTried(true);
    if (!problem) onDone(w);
  };
  return (
    <Modal
      open
      onClose={onClose}
      onSubmit={finish}
      title={isNew ? t("widgets.countdown.titleNew") : t("widgets.countdown.titleEdit")}
      width={480}
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>{t("chat.editMessage.cancel")}</Button>
          <Button onClick={finish} disabled={tried && !!problem} data-testid="widget-done">{t("pick.done")}</Button>
        </>
      }
    >
      <div className={s.stack}>
        <div className={s.momentRow}>
          <Field label={t("widgets.countdown.emoji")}>
            <TextInput value={emoji} onChange={(e) => setEmoji([...e.target.value].slice(0, 8).join(""))} placeholder="🎂" aria-label={t("widgets.countdown.emoji")} className={s.emojiInput} data-testid="countdown-emoji" />
          </Field>
          <Field label={t("widgets.countdown.what")} error={tried && !w.title ? t("widgets.countdown.needsTitle") : null}>
            <TextInput value={title} onChange={(e) => setTitle(e.target.value)} maxLength={LIMITS.title} placeholder={t("widgets.countdown.whatPlaceholder")} autoFocus data-testid="countdown-title" />
          </Field>
        </div>
        <div className={s.momentRow}>
          <Field label={t("widgets.countdown.date")} error={tried && !w.date && !w.at ? t("widgets.countdown.needsDate") : null}>
            <TextInput type="date" value={date} onChange={(e) => setDate(e.target.value)} data-testid="countdown-date" />
          </Field>
          {timed ? (
            <Field label={t("widgets.countdown.time")}>
              <TextInput type="time" value={time} onChange={(e) => setTime(e.target.value)} data-testid="countdown-time" />
            </Field>
          ) : null}
        </div>
        <Toggle checked={timed} onChange={setTimed} label={t("widgets.countdown.timed")} description={t("widgets.countdown.timedHint")} />
        <div>
          <span className={s.label}>{t("settings.profile.preview")}</span>
          <div className={s.previewSheet}>
            {w.date || w.at ? <ProfileWidgets widgets={[{ ...w, title: w.title || t("widgets.countdown.whatPlaceholder") }]} variant="modal" /> : <p className={s.note}>{t("widgets.countdown.pickDate")}</p>}
          </div>
        </div>
        {tried && problem ? <p className={s.error} role="alert">{problem}</p> : null}
      </div>
    </Modal>
  );
}

/** A zone as the picker lists it: its place and its offset now ("Anchorage, UTC−9"). */
function zoneLabel(zone: string, now: number): string {
  const off = zoneOffset(zone, now);
  const sign = off < 0 ? "−" : "+";
  const h = Math.floor(Math.abs(off) / 60);
  const m = Math.abs(off) % 60;
  const utc = off === 0 ? "UTC" : `UTC${sign}${h}${m ? `:${String(m).padStart(2, "0")}` : ""}`;
  return `${zonePlace(zone)}, ${utc}`;
}

/** Local Time: which time zone, starting at this device's, said plainly to be shown to everyone. */
export function LocalTimeDialog({ initial, isNew, onDone, onClose }: { initial: LocalTimeWidget; isNew: boolean; onDone: (w: LocalTimeWidget) => void; onClose: () => void }) {
  const [zone, setZone] = useState(initial.zone);
  const now = Date.now();
  const zones = useMemo(() => {
    const list = allZones();
    if (!list.includes(zone)) list.unshift(zone);
    return list.map((z) => ({ z, label: zoneLabel(z, now), off: zoneOffset(z, now) })).sort((a, b) => a.off - b.off || a.label.localeCompare(b.label));
    // the list is made once; offsets do not change while the dialog is open
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);
  const own = Intl.DateTimeFormat().resolvedOptions().timeZone;
  const w: LocalTimeWidget = { id: initial.id, type: "localtime", zone };
  return (
    <Modal
      open
      onClose={onClose}
      title={isNew ? t("widgets.localtime.titleNew") : t("widgets.localtime.titleEdit")}
      width={480}
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>{t("chat.editMessage.cancel")}</Button>
          <Button onClick={() => onDone(w)} data-testid="widget-done">{t("pick.done")}</Button>
        </>
      }
    >
      <div className={s.stack}>
        <Field label={t("widgets.localtime.zone")}>
          <Select value={zone} onChange={(e) => setZone(e.target.value)} data-testid="localtime-zone">
            {zones.map(({ z, label }) => (
              <option key={z} value={z}>{z === own ? t("widgets.localtime.yours", { zone: label }) : label}</option>
            ))}
          </Select>
        </Field>
        <p className={s.note} style={{ margin: 0 }}>{t("widgets.localtime.privacy")}</p>
        <div>
          <span className={s.label}>{t("settings.profile.preview")}</span>
          <div className={s.previewSheet}>
            <ProfileWidgets widgets={[w]} variant="modal" />
          </div>
        </div>
      </div>
    </Modal>
  );
}

/**
 * A sticker showcase: up to two of this account's own packs. Only public
 * packs can be shown; a private one is listed with how to change that.
 */
export function StickersDialog({ initial, isNew, onDone, onClose }: { initial: StickersWidget; isNew: boolean; onDone: (w: StickersWidget) => void; onClose: () => void }) {
  const meId = useAuth((a) => a.me?.id);
  const [packs, setPacks] = useState<StickerPack[] | null>(null);
  const [failed, setFailed] = useState(false);
  const [picked, setPicked] = useState<string[]>(initial.packs);
  const [tried, setTried] = useState(false);
  useEffect(() => {
    let alive = true;
    expressionsApi
      .packs()
      .then((list) => alive && setPacks(list.filter((p) => p.owner_user_id === meId)))
      .catch(() => alive && setFailed(true));
    return () => {
      alive = false;
    };
  }, [meId]);
  const toggle = (id: string) => setPicked((list) => (list.includes(id) ? list.filter((x) => x !== id) : list.length < MAX_SHOWCASE_PACKS ? [...list, id] : list));
  const problem = picked.length === 0 ? t("widgets.stickers.needsPack") : null;
  const finish = () => {
    setTried(true);
    if (!problem) onDone({ id: initial.id, type: "stickers", packs: picked });
  };
  return (
    <Modal
      open
      onClose={onClose}
      title={isNew ? t("widgets.stickers.titleNew") : t("widgets.stickers.titleEdit")}
      width={480}
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>{t("chat.editMessage.cancel")}</Button>
          <Button onClick={finish} disabled={tried && !!problem} data-testid="widget-done">{t("pick.done")}</Button>
        </>
      }
    >
      <div className={s.stack}>
        <p className={s.note} style={{ margin: 0 }}>{t("widgets.stickers.hint")}</p>
        {failed ? (
          <p className={s.error}>{t("stickerPack.couldNotLoadThePack")}</p>
        ) : packs === null ? (
          <p className={s.note}>{t("stickerPack.loading")}</p>
        ) : packs.length === 0 ? (
          <p className={s.note}>{t("widgets.stickers.none")}</p>
        ) : (
          <ul className={s.packList}>
            {packs.map((p) => {
              const on = picked.includes(p.id);
              const full = !on && picked.length >= MAX_SHOWCASE_PACKS;
              return (
                <li key={p.id}>
                  <button type="button" className={s.packRow} aria-pressed={on} disabled={!p.public || full} onClick={() => toggle(p.id)} data-testid="showcase-pack">
                    <span className={s.packCover}>{p.stickers[0] ? <LazySticker sticker={p.stickers[0]} pack={p} alt="" /> : p.name.slice(0, 1).toUpperCase()}</span>
                    <span className={s.packText}>
                      <span className={s.packName} dir="auto">{p.name}</span>
                      <span className={s.packAbout}>{p.public ? (p.kind === "emoji" ? t("stickerPack.emojiPack") : t("stickerPack.stickerPack")) : t("widgets.stickers.private")}</span>
                    </span>
                    {on ? <Check size={18} className={s.packTick} aria-hidden="true" /> : null}
                  </button>
                </li>
              );
            })}
          </ul>
        )}
        {tried && problem ? <p className={s.error} role="alert">{problem}</p> : null}
      </div>
    </Modal>
  );
}

const bookKey = (b: WidgetBook) => (b.id ? `b:${b.id}` : `n:${b.title.toLowerCase()}\u0000${(b.author ?? "").toLowerCase()}`);

/**
 * A books widget: a search of Open Library, through this server, that adds
 * what it finds, or a book typed in by name with a picture of its own; and the
 * books chosen, in order, with how far in each is (Reading Now).
 */
export function BookWidgetDialog({ initial, onDone, onClose }: { initial: BookWidget; onDone: (w: BookWidget) => void; onClose: () => void }) {
  const toast = useUI((u) => u.toast);
  const most = MOST_BOOKS[initial.type];
  const reading = initial.type === "reading";
  const [books, setBooks] = useState<WidgetBook[]>(initial.books);
  const [q, setQ] = useState("");
  const [found, setFound] = useState<{ q: string; books: FoundBook[] } | null>(null);
  const [searching, setSearching] = useState(false);
  const [failed, setFailed] = useState(false);
  const [ready, setReady] = useState<boolean | null>(null);
  const [crop, setCrop] = useState<{ file: File; key: string } | null>(null);
  const [uploading, setUploading] = useState<string | null>(null);
  const seq = useRef(0);
  const searchRef = useRef<HTMLInputElement>(null);
  const fileRef = useRef<HTMLInputElement>(null);
  const pictureFor = useRef<string | null>(null);
  const searchLabel = useId();
  const query = q.trim();

  useEffect(() => {
    let alive = true;
    usersApi.searchBooks("").then(
      (res) => alive && setReady(res.ready),
      () => alive && setReady(true),
    );
    return () => {
      alive = false;
    };
  }, []);

  useEffect(() => {
    const n = ++seq.current;
    setFailed(false);
    if (!query || ready === false) {
      setFound(null);
      setSearching(false);
      return;
    }
    setSearching(true);
    const timer = setTimeout(() => {
      usersApi.searchBooks(query).then(
        (res) => {
          if (n !== seq.current) return;
          setReady(res.ready);
          setFound({ q: query, books: res.books });
          setSearching(false);
        },
        () => {
          if (n !== seq.current) return;
          setFailed(true);
          setSearching(false);
        },
      );
    }, 320);
    return () => clearTimeout(timer);
  }, [query, ready]);

  const has = (b: WidgetBook) => books.some((x) => bookKey(x) === bookKey(b));
  const isFull = books.length >= most;
  const add = (b: WidgetBook) => {
    if (has(b)) return setBooks(books.filter((x) => bookKey(x) !== bookKey(b)));
    if (isFull) return;
    setBooks([...books, b]);
  };
  const addByName = () => {
    const title = query.slice(0, 200);
    if (!title) return;
    add({ title });
    setQ("");
    searchRef.current?.focus();
  };
  const patch = (key: string, change: Partial<WidgetBook>) => setBooks((list) => list.map((b) => (bookKey(b) === key ? { ...b, ...change } : b)));
  const drag = useRowDrag(books, bookKey, setBooks);
  const reorder = useReorder({
    ids: books.map(bookKey),
    move: (key, delta) => {
      const next = moved(books, bookKey, key, delta);
      if (next) setBooks(next);
      return !!next;
    },
    name: (key) => books.find((b) => bookKey(b) === key)?.title ?? "",
  });
  const upload = async (key: string, file: Blob, area: { x: number; y: number; w: number; h: number } | null) => {
    setUploading(key);
    try {
      const res = await usersApi.uploadWidgetImage(file, area, "cover");
      patch(key, { image: res.id });
    } catch (err) {
      toast(describeError(err, t("composer.composer.uploadFailed")), "error");
    } finally {
      setUploading(null);
    }
  };
  const choosePicture = (key: string) => {
    pictureFor.current = key;
    fileRef.current?.click();
  };

  const exact = found?.q === query && found.books.some((b) => b.title.toLowerCase() === query.toLowerCase());
  const byName = !!query && !exact && !books.some((b) => !b.id && b.title.toLowerCase() === query.toLowerCase());
  const results = found && found.q === query ? found.books : [];
  const canSave = books.length > 0 && uploading === null;
  const asBook = (f: FoundBook): WidgetBook => ({ id: f.id, title: f.title, ...(f.author ? { author: f.author } : {}), ...(f.cover ? { cover: f.cover } : {}) });

  return (
    <Modal
      open
      onClose={onClose}
      title={widgetTitle(initial)}
      width={520}
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>{t("chat.editMessage.cancel")}</Button>
          <Button onClick={() => onDone({ ...initial, books })} disabled={!canSave} data-testid="widget-done">{t("pick.done")}</Button>
        </>
      }
    >
      <input
        ref={fileRef}
        type="file"
        accept={IMAGE_TYPES}
        hidden
        onChange={(e) => {
          const f = e.target.files?.[0];
          const key = pictureFor.current;
          if (f && key) setCrop({ file: f, key });
          e.target.value = "";
        }}
      />
      <span className={s.label} id={searchLabel}>{t("widgets.books.search")}</span>
      <div className={s.searchBox}>
        <Search size={17} className={s.searchIcon} aria-hidden="true" />
        <TextInput
          ref={searchRef}
          value={q}
          onChange={(e) => setQ(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === "Enter") {
              e.preventDefault();
              const first = results[0];
              if (first && !has(asBook(first))) add(asBook(first));
              else if (!first && byName && ready === false) addByName();
            }
          }}
          maxLength={200}
          placeholder={ready === false ? t("widgets.books.namePlaceholder") : t("widgets.books.searchPlaceholder")}
          aria-labelledby={searchLabel}
          autoComplete="off"
          spellCheck={false}
          data-testid="book-search"
          autoFocus
        />
      </div>
      {ready === false ? <p className={s.note}>{t("widgets.books.notSetUp")}</p> : null}
      {query ? (
        <ul className={s.results} aria-label={t("widgets.books.results")} aria-busy={searching || undefined}>
          {results.map((f) => {
            const b = asBook(f);
            const on = has(b);
            return (
              <li key={f.id}>
                <button className={s.result} aria-pressed={on} disabled={!on && isFull} onClick={() => add(b)} data-testid="book-result">
                  <BookCover book={b} css={30} className={s.resultCover} />
                  <span className={s.resultText}>
                    <span className={s.resultName} dir="auto">{f.title}</span>
                    {f.author || f.year ? <span className={s.resultYear} dir="auto">{[f.author, f.year].filter(Boolean).join(", ")}</span> : null}
                  </span>
                  <span className={s.resultMark} aria-hidden="true">{on ? <Check size={15} /> : <Plus size={15} />}</span>
                </button>
              </li>
            );
          })}
          {searching && !results.length ? <li><p className={s.status}>{t("widgets.game.searching")}</p></li> : null}
          {!searching && failed ? <li><p className={s.status}>{t("widgets.books.unavailable")}</p></li> : null}
          {!searching && !failed && ready !== false && found?.q === query && !results.length ? <li><p className={s.status}>{t("widgets.books.noResults")}</p></li> : null}
          {byName && !searching ? (
            <li>
              <button className={s.result} disabled={isFull} onClick={addByName} data-testid="book-by-name">
                <span className={s.byName} aria-hidden="true"><Plus size={14} /></span>
                <span className={s.resultText}><span className={s.resultName} dir="auto">{t("widgets.game.addByName", { name: query })}</span></span>
              </button>
            </li>
          ) : null}
        </ul>
      ) : null}
      {ready ? (
        <p className={s.credit}>
          <Tr id="widgets.books.credit" values={{ site: <a href="https://openlibrary.org" target="_blank" rel="noopener noreferrer">Open Library</a> }} />
        </p>
      ) : null}

      <div className={s.labelRow}>
        <span className={s.label}>{t("widgets.books.yours")}</span>
        <span className={s.count}>{t("widgets.used", { count: books.length, max: most })}</span>
      </div>
      {books.length === 0 ? (
        <p className={s.empty}>{t("widgets.books.noneYet")}</p>
      ) : (
        <ul className={s.chosen} aria-label={t("widgets.books.yours")}>
          {books.map((b) => {
            const key = bookKey(b);
            return (
              <li key={key} className={s.chosenRow} data-reorder-id={key} onKeyDown={reorder.onKeyDown(key)} {...drag.row(key)}>
                <span className={s.grip} aria-hidden="true" {...drag.grip(key)}><GripVertical size={16} /></span>
                <BookCover book={b} css={40} className={s.chosenCover} />
                <div className={s.chosenMain}>
                  <span className={s.chosenName} dir="auto">{b.title}</span>
                  {b.id ? (b.author ? <span className={s.chosenBy} dir="auto">{b.author}</span> : null) : (
                    <TextInput value={b.author ?? ""} onChange={(e) => patch(key, { author: e.target.value.slice(0, 100) || undefined })} placeholder={t("widgets.books.authorPlaceholder")} aria-label={t("widgets.books.authorOf", { title: b.title })} className={s.authorInput} />
                  )}
                  {!b.cover ? (
                    <div className={s.chosenBtns}>
                      <button className={s.chip} disabled={uploading !== null} onClick={() => choosePicture(key)}>
                        {uploading === key ? <LoaderCircle size={14} className={s.spin} aria-hidden="true" /> : <ImagePlus size={14} aria-hidden="true" />}
                        {b.image ? t("widgets.changePicture") : t("widgets.addPicture")}
                      </button>
                      {b.image ? <button className={s.chip} onClick={() => patch(key, { image: undefined })}>{t("widgets.removePicture")}</button> : null}
                    </div>
                  ) : null}
                  {reading ? (
                    <div className={s.progressRow}>
                      <Select
                        value={b.progress?.kind ?? ""}
                        onChange={(e) => {
                          const kind = e.target.value;
                          patch(key, { progress: kind === "page" || kind === "percent" ? { kind, value: kind === "percent" ? Math.min(b.progress?.value ?? 0, 100) : Math.max(1, b.progress?.value ?? 1) } : undefined });
                        }}
                        aria-label={t("widgets.books.progressOf", { title: b.title })}
                      >
                        <option value="">{t("widgets.books.noProgress")}</option>
                        <option value="page">{t("widgets.books.byPage")}</option>
                        <option value="percent">{t("widgets.books.byPercent")}</option>
                      </Select>
                      {b.progress ? (
                        <TextInput
                          type="number"
                          inputMode="numeric"
                          min={b.progress.kind === "page" ? 1 : 0}
                          max={b.progress.kind === "page" ? 100000 : 100}
                          value={String(b.progress.value)}
                          onChange={(e) => {
                            const kind = b.progress!.kind;
                            const n = Math.round(Number(e.target.value) || 0);
                            patch(key, { progress: { kind, value: kind === "percent" ? Math.max(0, Math.min(100, n)) : Math.max(1, Math.min(100000, n)) } });
                          }}
                          aria-label={b.progress.kind === "page" ? t("widgets.books.byPage") : t("widgets.books.byPercent")}
                          className={s.progressInput}
                          data-testid="book-progress"
                        />
                      ) : null}
                    </div>
                  ) : null}
                </div>
                <button className={s.iconBtn} aria-label={t("widgets.game.remove", { name: b.title })} onClick={() => setBooks(books.filter((x) => bookKey(x) !== key))}>
                  <X size={16} />
                </button>
              </li>
            );
          })}
        </ul>
      )}
      {books.length > 1 ? <p className={s.small}>{reorder.hint}</p> : null}
      {reorder.live}

      {crop ? (
        <ImageCropModal
          file={crop.file}
          aspect={3 / 4}
          maxSize={400}
          serverCrop
          title={t("widgets.books.cropTitle")}
          onClose={() => setCrop(null)}
          onDone={({ blob, crop: area }) => {
            const key = crop.key;
            setCrop(null);
            void upload(key, blob, area ?? null);
          }}
        />
      ) : null}
    </Modal>
  );
}

const screenKey = (x: WidgetScreen) => (x.id ? `${x.kind}:${x.id}` : `n:${x.title.toLowerCase()}`);

/** The notice TMDB's terms ask for wherever its data is shown, with its logo. */
export function TmdbCredit() {
  return (
    <p className={s.tmdb}>
      <img src={TMDB_LOGO} alt="TMDB" className={s.tmdbLogo} draggable={false} />
      <span>{t("widgets.screen.tmdbNotice")}</span>
    </p>
  );
}

/**
 * A shows and films widget: a search of TMDB, through this server, or a title
 * typed in by name with a poster of its own; the titles chosen, in order, with
 * where they are in each show on Watching Now.
 */
export function ScreenWidgetDialog({ initial, onDone, onClose }: { initial: ScreenWidget; onDone: (w: ScreenWidget) => void; onClose: () => void }) {
  const toast = useUI((u) => u.toast);
  const most = MOST_SCREEN[initial.type];
  const watching = initial.type === "watching";
  const [items, setItems] = useState<WidgetScreen[]>(initial.screen);
  const [q, setQ] = useState("");
  const [found, setFound] = useState<{ q: string; titles: FoundScreen[] } | null>(null);
  const [searching, setSearching] = useState(false);
  const [failed, setFailed] = useState(false);
  const [ready, setReady] = useState<boolean | null>(null);
  const [crop, setCrop] = useState<{ file: File; key: string } | null>(null);
  const [uploading, setUploading] = useState<string | null>(null);
  const seq = useRef(0);
  const searchRef = useRef<HTMLInputElement>(null);
  const fileRef = useRef<HTMLInputElement>(null);
  const pictureFor = useRef<string | null>(null);
  const searchLabel = useId();
  const query = q.trim();

  useEffect(() => {
    let alive = true;
    usersApi.searchScreen("").then(
      (res) => alive && setReady(res.ready),
      () => alive && setReady(true),
    );
    return () => {
      alive = false;
    };
  }, []);

  useEffect(() => {
    const n = ++seq.current;
    setFailed(false);
    if (!query || ready === false) {
      setFound(null);
      setSearching(false);
      return;
    }
    setSearching(true);
    const timer = setTimeout(() => {
      usersApi.searchScreen(query).then(
        (res) => {
          if (n !== seq.current) return;
          setReady(res.ready);
          setFound({ q: query, titles: res.titles });
          setSearching(false);
        },
        () => {
          if (n !== seq.current) return;
          setFailed(true);
          setSearching(false);
        },
      );
    }, 280);
    return () => clearTimeout(timer);
  }, [query, ready]);

  const has = (x: WidgetScreen) => items.some((y) => screenKey(y) === screenKey(x));
  const isFull = items.length >= most;
  const add = (x: WidgetScreen) => {
    if (has(x)) return setItems(items.filter((y) => screenKey(y) !== screenKey(x)));
    if (isFull) return;
    setItems([...items, x]);
  };
  const addByName = () => {
    const title = query.slice(0, 200);
    if (!title) return;
    add({ kind: "tv", title });
    setQ("");
    searchRef.current?.focus();
  };
  const patch = (key: string, change: Partial<WidgetScreen>) => setItems((list) => list.map((x) => (screenKey(x) === key ? { ...x, ...change } : x)));
  const drag = useRowDrag(items, screenKey, setItems);
  const reorder = useReorder({
    ids: items.map(screenKey),
    move: (key, delta) => {
      const next = moved(items, screenKey, key, delta);
      if (next) setItems(next);
      return !!next;
    },
    name: (key) => items.find((x) => screenKey(x) === key)?.title ?? "",
  });
  const upload = async (key: string, file: Blob, area: { x: number; y: number; w: number; h: number } | null) => {
    setUploading(key);
    try {
      const res = await usersApi.uploadWidgetImage(file, area, "cover");
      patch(key, { image: res.id });
    } catch (err) {
      toast(describeError(err, t("composer.composer.uploadFailed")), "error");
    } finally {
      setUploading(null);
    }
  };
  const asItem = (f: FoundScreen): WidgetScreen => ({ id: f.id, kind: f.kind, title: f.title, ...(f.year ? { year: f.year } : {}), ...(f.poster ? { poster: f.poster } : {}) });
  const exact = found?.q === query && found.titles.some((x) => x.title.toLowerCase() === query.toLowerCase());
  const byName = !!query && !exact && !items.some((x) => !x.id && x.title.toLowerCase() === query.toLowerCase());
  const results = found && found.q === query ? found.titles : [];
  const canSave = items.length > 0 && uploading === null;
  const num = (v: string, max: number) => {
    const n = Math.round(Number(v) || 0);
    return n > 0 ? Math.min(n, max) : undefined;
  };

  return (
    <Modal
      open
      onClose={onClose}
      title={widgetTitle(initial)}
      width={520}
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>{t("chat.editMessage.cancel")}</Button>
          <Button onClick={() => onDone({ ...initial, screen: items })} disabled={!canSave} data-testid="widget-done">{t("pick.done")}</Button>
        </>
      }
    >
      <input
        ref={fileRef}
        type="file"
        accept={IMAGE_TYPES}
        hidden
        onChange={(e) => {
          const f = e.target.files?.[0];
          const key = pictureFor.current;
          if (f && key) setCrop({ file: f, key });
          e.target.value = "";
        }}
      />
      <span className={s.label} id={searchLabel}>{t("widgets.screen.search")}</span>
      <div className={s.searchBox}>
        <Search size={17} className={s.searchIcon} aria-hidden="true" />
        <TextInput
          ref={searchRef}
          value={q}
          onChange={(e) => setQ(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === "Enter") {
              e.preventDefault();
              const first = results[0];
              if (first && !has(asItem(first))) add(asItem(first));
              else if (!first && byName && ready === false) addByName();
            }
          }}
          maxLength={200}
          placeholder={ready === false ? t("widgets.screen.namePlaceholder") : t("widgets.screen.searchPlaceholder")}
          aria-labelledby={searchLabel}
          autoComplete="off"
          spellCheck={false}
          data-testid="screen-search"
          autoFocus
        />
      </div>
      {ready === false ? <p className={s.note}>{t("widgets.screen.notSetUp")}</p> : null}
      {query ? (
        <ul className={s.results} aria-label={t("widgets.screen.results")} aria-busy={searching || undefined}>
          {results.map((f) => {
            const x = asItem(f);
            const on = has(x);
            return (
              <li key={`${f.kind}${f.id}`}>
                <button className={s.result} aria-pressed={on} disabled={!on && isFull} onClick={() => add(x)} data-testid="screen-result">
                  <ScreenPoster item={x} css={30} className={s.resultCover} />
                  <span className={s.resultText}>
                    <span className={s.resultName} dir="auto">{f.title}</span>
                    <span className={s.resultYear}>{[f.kind === "tv" ? t("widgets.screen.show") : t("widgets.screen.film"), f.year].filter(Boolean).join(", ")}</span>
                  </span>
                  <span className={s.resultMark} aria-hidden="true">{on ? <Check size={15} /> : <Plus size={15} />}</span>
                </button>
              </li>
            );
          })}
          {searching && !results.length ? <li><p className={s.status}>{t("widgets.game.searching")}</p></li> : null}
          {!searching && failed ? <li><p className={s.status}>{t("widgets.screen.unavailable")}</p></li> : null}
          {!searching && !failed && ready !== false && found?.q === query && !results.length ? <li><p className={s.status}>{t("widgets.screen.noResults")}</p></li> : null}
          {byName && !searching ? (
            <li>
              <button className={s.result} disabled={isFull} onClick={addByName} data-testid="screen-by-name">
                <span className={s.byName} aria-hidden="true"><Plus size={14} /></span>
                <span className={s.resultText}><span className={s.resultName} dir="auto">{t("widgets.game.addByName", { name: query })}</span></span>
              </button>
            </li>
          ) : null}
        </ul>
      ) : null}
      {ready ? <TmdbCredit /> : null}

      <div className={s.labelRow}>
        <span className={s.label}>{t("widgets.screen.yours")}</span>
        <span className={s.count}>{t("widgets.used", { count: items.length, max: most })}</span>
      </div>
      {items.length === 0 ? (
        <p className={s.empty}>{t("widgets.screen.noneYet")}</p>
      ) : (
        <ul className={s.chosen} aria-label={t("widgets.screen.yours")}>
          {items.map((x) => {
            const key = screenKey(x);
            return (
              <li key={key} className={s.chosenRow} data-reorder-id={key} onKeyDown={reorder.onKeyDown(key)} {...drag.row(key)}>
                <span className={s.grip} aria-hidden="true" {...drag.grip(key)}><GripVertical size={16} /></span>
                <ScreenPoster item={x} css={40} className={s.chosenCover} />
                <div className={s.chosenMain}>
                  <span className={s.chosenName} dir="auto">{x.title}</span>
                  {x.id ? (
                    <span className={s.chosenBy}>{[x.kind === "tv" ? t("widgets.screen.show") : t("widgets.screen.film"), x.year].filter(Boolean).join(", ")}</span>
                  ) : (
                    <div className={s.progressRow}>
                      <Select value={x.kind} onChange={(e) => patch(key, { kind: e.target.value === "movie" ? "movie" : "tv", season: undefined, episode: undefined })} aria-label={t("widgets.screen.kindOf", { title: x.title })}>
                        <option value="tv">{t("widgets.screen.show")}</option>
                        <option value="movie">{t("widgets.screen.film")}</option>
                      </Select>
                    </div>
                  )}
                  {!x.poster ? (
                    <div className={s.chosenBtns}>
                      <button className={s.chip} disabled={uploading !== null} onClick={() => { pictureFor.current = key; fileRef.current?.click(); }}>
                        {uploading === key ? <LoaderCircle size={14} className={s.spin} aria-hidden="true" /> : <ImagePlus size={14} aria-hidden="true" />}
                        {x.image ? t("widgets.changePicture") : t("widgets.addPicture")}
                      </button>
                      {x.image ? <button className={s.chip} onClick={() => patch(key, { image: undefined })}>{t("widgets.removePicture")}</button> : null}
                    </div>
                  ) : null}
                  {watching && x.kind === "tv" ? (
                    <div className={s.progressRow}>
                      <label className={s.numLabel}>
                        <span>{t("widgets.screen.seasonLabel")}</span>
                        <TextInput type="number" inputMode="numeric" min={1} max={200} value={x.season ? String(x.season) : ""} onChange={(e) => patch(key, { season: num(e.target.value, 200) })} className={s.progressInput} data-testid="screen-season" />
                      </label>
                      <label className={s.numLabel}>
                        <span>{t("widgets.screen.episodeLabel")}</span>
                        <TextInput type="number" inputMode="numeric" min={1} max={10000} value={x.episode ? String(x.episode) : ""} onChange={(e) => patch(key, { episode: num(e.target.value, 10000) })} className={s.progressInput} data-testid="screen-episode" />
                      </label>
                    </div>
                  ) : null}
                </div>
                <button className={s.iconBtn} aria-label={t("widgets.game.remove", { name: x.title })} onClick={() => setItems(items.filter((y) => screenKey(y) !== key))}>
                  <X size={16} />
                </button>
              </li>
            );
          })}
        </ul>
      )}
      {items.length > 1 ? <p className={s.small}>{reorder.hint}</p> : null}
      {reorder.live}

      {crop ? (
        <ImageCropModal
          file={crop.file}
          aspect={3 / 4}
          maxSize={400}
          serverCrop
          title={t("widgets.screen.cropTitle")}
          onClose={() => setCrop(null)}
          onDone={({ blob, crop: area }) => {
            const key = crop.key;
            setCrop(null);
            void upload(key, blob, area ?? null);
          }}
        />
      ) : null}
    </Modal>
  );
}
