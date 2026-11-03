import { ArrowDown, ArrowUp, ArrowUpRight, ExternalLink, LayoutGrid, LoaderCircle, Pause, Pencil, Pin, Play, Plus, Trash2 } from "lucide-react";
import { useEffect, useId, useState, type CSSProperties, type ReactNode } from "react";
import { Tooltip } from "@/components/common/Tooltip";
import { avatarColor, initials } from "@/lib/avatarColor";
import { gamePicture, isGameWidget, isMusicWidget, linkHost, MAX_WIDGETS, musicLink, musicPicture, songLength, widgetImageUrl, type CardWidget, type GameWidget, type MusicWidget, type MusicWidgetType, type Widget, type WidgetGame, type WidgetMusic } from "@/lib/widgets";
import { t } from "@/i18n";
import { canOpenGames, openGame } from "@/lib/games";
import { stopPreview, togglePreview, usePreview } from "./previews";
import { SongVolume } from "./SongVolume";
import s from "./Widgets.module.css";

type Variant = "modal" | "panel";

/**
 * The widgets as the profile designer draws them (Settings, Profiles): each
 * with its tools (change it, move it, take it off, put it on the profile
 * card) and, after them, the way to add another.
 */
export interface WidgetDesign {
  edit: (id: string) => void;
  move: (id: string, delta: number) => void;
  remove: (id: string) => void;
  /** Puts a widget first, which is the one the profile card shows. */
  feature: (id: string) => void;
  add: () => void;
}

/** One widget in the designer: pressing it changes it, and its tools sit along its top. */
function Designed({ w, index, count, design, children }: { w: Widget; index: number; count: number; design: WidgetDesign; children: ReactNode }) {
  const name = widgetName(w);
  return (
    <div className={s.designed} data-first={index === 0 || undefined} data-testid={`designed-${w.type}`}>
      {children}
      <button type="button" className={s.designSpot} aria-label={t("designer.widget.edit", { name })} onClick={() => design.edit(w.id)} />
      <div className={s.tools} role="toolbar" aria-label={t("designer.widget.tools", { name })}>
        {index === 0 ? (
          <span className={s.onCard}><Pin size={12} aria-hidden="true" /> {t("designer.widget.onCard")}</span>
        ) : (
          <button type="button" className={s.toolText} onClick={() => design.feature(w.id)}>
            <Pin size={12} aria-hidden="true" /> {t("designer.widget.showOnCard")}
          </button>
        )}
        <button type="button" className={s.tool} aria-label={t("designer.widget.editShort")} title={t("designer.widget.editShort")} onClick={() => design.edit(w.id)}><Pencil size={14} /></button>
        <button type="button" className={s.tool} aria-label={t("reorder.moveUp")} title={t("reorder.moveUp")} disabled={index === 0} onClick={() => design.move(w.id, -1)}><ArrowUp size={14} /></button>
        <button type="button" className={s.tool} aria-label={t("reorder.moveDown")} title={t("reorder.moveDown")} disabled={index === count - 1} onClick={() => design.move(w.id, 1)}><ArrowDown size={14} /></button>
        <button type="button" className={s.tool} data-danger="" aria-label={t("widgets.remove")} title={t("widgets.remove")} onClick={() => design.remove(w.id)}><Trash2 size={14} /></button>
      </div>
    </div>
  );
}

/** What each kind of widget is called, on a profile and in the editor. */
export function widgetTitle(w: Pick<Widget, "type">): string {
  switch (w.type) {
    case "favorite":
      return t("widgets.type.favorite");
    case "playing":
      return t("widgets.type.playing");
    case "wishlist":
      return t("widgets.type.wishlist");
    case "played":
      return t("widgets.type.played");
    case "artists":
      return t("widgets.type.artists");
    case "albums":
      return t("widgets.type.albums");
    case "songs":
      return t("widgets.type.songs");
    default:
      return t("widgets.type.custom");
  }
}

/** A widget's name where it is listed: a card's own title, else what its kind is called. */
export function widgetName(w: Widget): string {
  return w.type === "custom" ? w.title : widgetTitle(w);
}

/** How many a music widget holds, in the words for its kind. */
export function musicCount(type: MusicWidgetType, count: number): string {
  if (type === "artists") return t("widgets.artistCount", { count });
  if (type === "albums") return t("widgets.albumCount", { count });
  return t("widgets.songCount", { count });
}

/**
 * A game's box: its cover from IGDB, or the picture its owner put up, or its
 * name on a colour of its own when it has neither (or its picture would not
 * load). `named` says the box stands alone, so it carries the game's name;
 * beside the name it is only a picture.
 */
export function GameCover({ game, css, named, className }: { game: WidgetGame; css: number; named?: boolean; className?: string }) {
  const src = gamePicture(game, css);
  const [broken, setBroken] = useState<string | null>(null);
  const shown = src && broken !== src ? src : null;
  return (
    <span
      className={[s.cover, className ?? ""].join(" ")}
      style={{ "--cover-tint": avatarColor(game.name) } as CSSProperties}
      {...(named ? { role: "img", "aria-label": game.name } : { "aria-hidden": true })}
    >
      {shown ? <img src={shown} alt="" loading="lazy" draggable={false} onError={() => setBroken(shown)} /> : <span className={s.coverName} dir="auto">{game.name}</span>}
    </span>
  );
}

/**
 * A game that opens its page when pressed: one found on IGDB, for someone
 * signed in. A game added by name has no page, so it stays only a picture.
 */
function GameOpen({ game, className, children }: { game: WidgetGame; className?: string | undefined; children: ReactNode }) {
  if (!game.id || !canOpenGames()) return <>{children}</>;
  const id = game.id;
  return (
    <button type="button" className={[s.gameOpen, className ?? ""].join(" ")} onClick={() => openGame(id)} aria-label={t("games.page.openNamed", { name: game.name })} data-testid="game-open">
      {children}
    </button>
  );
}

function Favorite({ w, variant }: { w: GameWidget; variant: Variant }) {
  const game = w.games[0];
  if (!game) return null;
  const glow = gamePicture(game, 40);
  return (
    <section className={[s.widget, s.favorite].join(" ")} data-testid="widget-favorite">
      {glow ? <span className={s.glow} style={{ backgroundImage: `url("${glow}")` }} aria-hidden="true" /> : null}
      <GameOpen game={game}>
        <GameCover game={game} css={variant === "panel" ? 56 : 76} />
      </GameOpen>
      <div className={s.favText}>
        <h3>{t("widgets.type.favorite")}</h3>
        <span className={s.favName} dir="auto">{game.name}</span>
        {w.note ? <p className={s.favNote} dir="auto">{w.note}</p> : null}
      </div>
    </section>
  );
}

function Playing({ w }: { w: GameWidget }) {
  return (
    <section className={s.widget} data-testid="widget-playing">
      <h3>{t("widgets.type.playing")}</h3>
      <ul className={s.rows}>
        {w.games.map((g, i) => (
          <li key={`${g.id ?? g.name}-${i}`} className={s.row}>
            <GameOpen game={g}>
              <GameCover game={g} css={36} />
            </GameOpen>
            <div className={s.rowText}>
              <span className={s.rowName} dir="auto">{g.name}</span>
              {g.tags?.length ? (
                <ul className={s.tags} aria-label={t("widgets.howTheyPlay")}>
                  {g.tags.map((tag) => <li key={tag} className={s.tag} dir="auto">{tag}</li>)}
                </ul>
              ) : null}
            </div>
          </li>
        ))}
      </ul>
    </section>
  );
}

function Shelf({ w, variant, onShowAll }: { w: GameWidget; variant: Variant; onShowAll?: (() => void) | undefined }) {
  const [all, setAll] = useState(false);
  // two rows of boxes on the full profile, one row where the rest are on the full profile (onShowAll)
  const most = variant === "panel" ? 4 : onShowAll ? 6 : 12;
  const shown = all ? w.games : w.games.slice(0, most);
  const title = widgetTitle(w);
  return (
    <section className={[s.widget, s.shelfWrap].join(" ")} data-testid={`widget-${w.type}`}>
      <div className={s.head}>
        <h3>{title}</h3>
        <span className={s.count} aria-label={t("widgets.gameCount", { count: w.games.length })}>{w.games.length.toLocaleString()}</span>
      </div>
      <ul className={s.shelf} aria-label={title}>
        {shown.map((g, i) => (
          <li key={`${g.id ?? g.name}-${i}`}>
            <Tooltip text={g.name} className={s.tip}>
              <GameOpen game={g} className={s.shelfOpen}>
                <GameCover game={g} css={80} named={!(g.id && canOpenGames())} />
              </GameOpen>
            </Tooltip>
            <span className={s.caption} aria-hidden="true" dir="auto">{g.name}</span>
          </li>
        ))}
      </ul>
      {w.games.length > most ? (
        onShowAll ? (
          // the preview stays short: the rest are on the full profile
          <button className={s.more} onClick={onShowAll}>{t("widgets.showAll")}</button>
        ) : (
          <button className={s.more} onClick={() => setAll((v) => !v)} aria-expanded={all}>
            {all ? t("widgets.showFewer") : t("widgets.showAll")}
          </button>
        )
      ) : null}
    </section>
  );
}

/**
 * An artist's photo, round, or the cover of an album or of the album a song
 * is on: from Deezer, or the picture its owner put up, or its name on a
 * colour of its own when it has neither (or it would not load). An artist's,
 * or one drawn small, is only the first letter, as an avatar's is.
 */
export function MusicArt({ type, item, css, className }: { type: MusicWidgetType; item: WidgetMusic; css: number; className?: string | undefined }) {
  const src = musicPicture(type, item, css);
  const [broken, setBroken] = useState<string | null>(null);
  const shown = src && broken !== src ? src : null;
  const small = css < 64 || type === "artists";
  return (
    <span className={[s.art, className ?? ""].join(" ")} data-round={type === "artists" || undefined} style={{ "--cover-tint": avatarColor(item.name) } as CSSProperties} aria-hidden="true">
      {shown ? (
        <img src={shown} alt="" loading="lazy" draggable={false} onError={() => setBroken(shown)} />
      ) : (
        <span className={s.artName} data-small={small || undefined} style={small ? { fontSize: Math.round(css * 0.4) } : undefined} dir="auto">
          {small ? initials(item.name) : item.name}
        </span>
      )}
    </span>
  );
}

/** One artist, album or song: a link to it on Deezer when it was found there, else just itself. */
function Linked({ type, item, className, children }: { type: MusicWidgetType; item: WidgetMusic; className: string | undefined; children: ReactNode }) {
  const href = musicLink(type, item);
  if (!href) return <span className={className}>{children}</span>;
  return (
    <a className={className} href={href} target="_blank" rel="noopener noreferrer" title={t("widgets.music.onDeezer")} data-testid="music-link">
      {children}
    </a>
  );
}

function MusicHead({ w, extra }: { w: MusicWidget; extra?: ReactNode }) {
  return (
    <div className={s.head}>
      <h3>{widgetTitle(w)}</h3>
      {extra}
      <span className={s.count} aria-label={musicCount(w.type, w.music.length)}>{w.music.length.toLocaleString()}</span>
    </div>
  );
}

/** Show All past the first few: on the full profile where it is a preview (onShowAll), or here. */
function MoreMusic({ all, setAll, onShowAll, className }: { all: boolean; setAll: (v: boolean) => void; onShowAll?: (() => void) | undefined; className?: string | undefined }) {
  if (onShowAll) return <button className={s.more} onClick={onShowAll}>{t("widgets.showAll")}</button>;
  return (
    <button className={[s.more, className ?? ""].join(" ")} onClick={() => setAll(!all)} aria-expanded={all}>
      {all ? t("widgets.showFewer") : t("widgets.showAll")}
    </button>
  );
}

/** Favourite Artists: their faces in a row with their names under them, like a bill. */
function Artists({ w, variant, onShowAll }: { w: MusicWidget; variant: Variant; onShowAll?: (() => void) | undefined }) {
  const [all, setAll] = useState(false);
  const most = variant === "panel" ? 3 : onShowAll ? 6 : 12;
  const shown = all ? w.music : w.music.slice(0, most);
  return (
    <section className={[s.widget, s.shelfWrap].join(" ")} data-testid="widget-artists">
      <MusicHead w={w} />
      <ul className={s.lineup} aria-label={widgetTitle(w)}>
        {shown.map((m, i) => (
          <li key={`${m.id ?? m.name}-${i}`}>
            <Linked type="artists" item={m} className={s.artist}>
              <MusicArt type="artists" item={m} css={variant === "panel" ? 64 : 88} className={s.portrait} />
              <span className={s.artistName} dir="auto">{m.name}</span>
            </Linked>
          </li>
        ))}
      </ul>
      {w.music.length > most ? <MoreMusic all={all} setAll={setAll} onShowAll={onShowAll} /> : null}
    </section>
  );
}

/** Favourite Albums: a crate of sleeves, the edge of each one's record showing. */
function Albums({ w, variant, onShowAll }: { w: MusicWidget; variant: Variant; onShowAll?: (() => void) | undefined }) {
  const [all, setAll] = useState(false);
  const most = variant === "panel" ? 3 : onShowAll ? 5 : 12;
  const shown = all ? w.music : w.music.slice(0, most);
  return (
    <section className={[s.widget, s.shelfWrap].join(" ")} data-testid="widget-albums">
      <MusicHead w={w} />
      <ul className={s.crate} aria-label={widgetTitle(w)}>
        {shown.map((m, i) => (
          <li key={`${m.id ?? m.name}-${i}`}>
            <Linked type="albums" item={m} className={s.album}>
              <span className={s.sleeve}>
                <span className={s.record} aria-hidden="true" />
                <MusicArt type="albums" item={m} css={variant === "panel" ? 64 : 88} className={s.sleeveArt} />
              </span>
              <span className={s.albumName} dir="auto">{m.name}</span>
              {m.by ? <span className={s.albumBy} dir="auto">{m.by}</span> : null}
            </Linked>
          </li>
        ))}
      </ul>
      {w.music.length > most ? <MoreMusic all={all} setAll={setAll} onShowAll={onShowAll} /> : null}
    </section>
  );
}

/** How far through its thirty seconds the song playing is, as a line along its row. */
function Played() {
  const progress = usePreview((p) => p.progress);
  return (
    <span className={s.played} aria-hidden="true">
      <span style={{ transform: `scaleX(${progress})` }} />
    </span>
  );
}

/**
 * Favourite Songs: a tracklist, numbered in the order they were put, with
 * each song's length. One column shows five (four beside a chat); a wide one
 * sets them all in two, down the first and on down the second. Pressing a
 * song found on Deezer plays thirty seconds of it (previews.ts), its number
 * turning to bars while it plays; the arrow at its end opens it on Deezer.
 */
function Songs({ w, variant, onShowAll }: { w: MusicWidget; variant: Variant; onShowAll?: (() => void) | undefined }) {
  const [all, setAll] = useState(false);
  const owner = useId();
  const playing = usePreview((p) => p.id);
  const state = usePreview((p) => p.state);
  // a preview stops with the list that started it (a profile closed)
  useEffect(() => () => stopPreview(owner), [owner]);
  const n = w.music.length;
  const cap = variant === "panel" ? 4 : 5;
  const two = variant === "modal" && n > cap;
  return (
    <section className={[s.widget, s.shelfWrap].join(" ")} data-testid="widget-songs">
      <MusicHead w={w} extra={w.music.some((m) => !!m.id) ? <SongVolume /> : null} />
      <ol className={s.tracks} role="list" aria-label={widgetTitle(w)} data-two={two || undefined} data-all={all || undefined} style={{ "--rows": Math.ceil(n / 2) } as CSSProperties}>
        {w.music.map((m, i) => {
          const on = !!m.id && playing === m.id;
          const link = musicLink("songs", m);
          const body = (
            <>
              <span className={s.trackNo} aria-hidden="true">
                <span className={s.trackNum}>{(i + 1).toLocaleString()}</span>
                {m.id ? (
                  <span className={s.trackCue}>
                    {on && state === "loading" ? <LoaderCircle size={15} className={s.spin} /> : on && state === "playing" ? <Pause size={14} fill="currentColor" /> : <Play size={14} fill="currentColor" />}
                  </span>
                ) : null}
                {on && state === "playing" ? (
                  <span className={s.eq}>
                    <i />
                    <i />
                    <i />
                  </span>
                ) : null}
              </span>
              <MusicArt type="songs" item={m} css={40} className={s.trackArt} />
              <span className={s.trackText}>
                <span className={s.trackName} dir="auto">{m.name}</span>
                {m.by ? <span className={s.trackBy} dir="auto">{m.by}</span> : null}
              </span>
              {m.duration ? <span className={s.trackLen}>{songLength(m.duration)}</span> : null}
              {on ? <Played /> : null}
            </>
          );
          return (
            <li key={`${m.id ?? m.name}-${i}`} className={s.trackRow} data-past={i >= cap || undefined} data-state={on ? state : undefined}>
              {m.id ? (
                <button type="button" className={s.track} onClick={() => togglePreview(m, owner)} aria-label={t(on && state !== "paused" ? "widgets.music.pause" : "widgets.music.play", { name: m.name })} data-testid="song-preview">
                  {body}
                </button>
              ) : (
                <span className={s.track}>{body}</span>
              )}
              {link ? (
                <a className={s.trackOut} href={link} target="_blank" rel="noopener noreferrer" aria-label={t("widgets.music.openNamed", { name: m.name })} title={t("widgets.music.onDeezer")} data-testid="music-link">
                  <ArrowUpRight size={16} aria-hidden="true" />
                </a>
              ) : null}
            </li>
          );
        })}
      </ol>
      {n > cap ? <MoreMusic all={all} setAll={setAll} onShowAll={onShowAll} className={two ? s.moreNarrow : undefined} /> : null}
    </section>
  );
}

function Card({ w, variant }: { w: CardWidget; variant: Variant }) {
  const wide = w.image && w.shape !== "square" ? w.image : null;
  const square = w.image && w.shape === "square" ? w.image : null;
  const host = w.link ? linkHost(w.link.url) : "";
  // a link with words of its own also says where it goes, as a masked link in a message does
  const showHost = !!w.link?.label && !w.link.label.toLowerCase().includes(host.toLowerCase());
  return (
    <section className={[s.widget, s.card].join(" ")} data-testid="widget-custom">
      {wide ? <img className={s.cardImg} src={widgetImageUrl(wide, variant === "panel" ? 300 : 520)} alt="" loading="lazy" draggable={false} /> : null}
      <div className={s.cardBody}>
        <div className={s.cardTop}>
          {square ? <img className={s.cardSquare} src={widgetImageUrl(square, 56)} alt="" loading="lazy" draggable={false} /> : null}
          <h3 className={s.cardTitle} dir="auto">{w.title}</h3>
        </div>
        {w.text ? <p className={s.cardText} dir="auto">{w.text}</p> : null}
        {w.stats?.length ? (
          <dl className={s.stats}>
            {w.stats.map((st, i) => (
              <div key={i} className={s.stat}>
                <dt dir="auto">{st.label}</dt>
                <dd dir="auto">{st.value}</dd>
              </div>
            ))}
          </dl>
        ) : null}
        {w.link ? (
          <a className={s.link} href={w.link.url} target="_blank" rel="noopener noreferrer nofollow ugc" data-testid="widget-link">
            <ExternalLink size={14} aria-hidden="true" />
            <span className={s.linkText} dir="auto">{w.link.label || host}</span>
            {showHost ? <span className={s.linkHost} dir="ltr">{host}</span> : null}
          </a>
        ) : null}
      </div>
    </section>
  );
}

/**
 * The first of someone's widgets in a line, for the popout: what it is, what
 * is in it, and a few of its boxes (or its picture), the whole of it a press
 * away on the full profile.
 */
export function WidgetPeek({ widgets, onOpen }: { widgets: Widget[]; onOpen?: (() => void) | undefined }) {
  const w = widgets[0];
  if (!w) return null;
  const title = widgetName(w);
  let sub: string;
  let art: ReactNode = null;
  if (isGameWidget(w)) {
    sub = w.games.length === 1 ? w.games[0]!.name : t("widgets.gameCount", { count: w.games.length });
    art = w.games.slice(0, 3).map((g, i) => <GameCover key={i} game={g} css={30} className={s.peekCover} />);
  } else if (isMusicWidget(w)) {
    sub = w.music.length === 1 ? w.music[0]!.name : musicCount(w.type, w.music.length);
    art = w.music.slice(0, 3).map((m, i) => <MusicArt key={i} type={w.type} item={m} css={30} className={s.peekMusic} />);
  } else {
    sub = w.text ?? "";
    art = w.image ? <img className={s.peekImage} src={widgetImageUrl(w.image, 40)} alt="" draggable={false} /> : null;
  }
  const more = widgets.length - 1;
  const body = (
    <>
      <span className={s.peekText}>
        <span className={s.peekTitle} dir="auto">{title}</span>
        {sub ? <span className={s.peekSub} dir="auto">{sub}</span> : null}
      </span>
      <span className={s.peekArt} aria-hidden="true">{art}</span>
      {more > 0 ? <span className={s.peekMore}>+{more}</span> : null}
    </>
  );
  return onOpen ? (
    <button type="button" className={s.peek} onClick={onOpen} aria-label={t("profile.tabs.viewAllWidgets", { count: widgets.length })} data-testid="widget-peek">
      {body}
    </button>
  ) : (
    <div className={s.peek} data-testid="widget-peek">{body}</div>
  );
}

/**
 * Someone's widgets, in the order they put them, laid into their profile's
 * sheet. On their own full profile with none yet, what they could show and
 * the way to Settings. `onShowAll` sends a shelf's Show All somewhere else
 * (the full profile) instead of opening it where it is.
 */
export function ProfileWidgets({ widgets, variant, onAdd, onShowAll, design }: { widgets: Widget[]; variant: Variant; onAdd?: (() => void) | undefined; onShowAll?: (() => void) | undefined; design?: WidgetDesign | undefined }) {
  if (widgets.length === 0) {
    if (!onAdd) return null;
    return (
      <div className={s.empty} data-testid="profile-widgets-empty">
        <p className={s.emptyText}>{t("widgets.emptyOwn")}</p>
        <button className={s.emptyBtn} onClick={onAdd}>
          <LayoutGrid size={15} aria-hidden="true" /> {t("widgets.add")}
        </button>
      </div>
    );
  }
  const draw = (w: Widget) => {
    if (isMusicWidget(w)) {
      if (w.type === "artists") return <Artists key={w.id} w={w} variant={variant} onShowAll={onShowAll} />;
      if (w.type === "albums") return <Albums key={w.id} w={w} variant={variant} onShowAll={onShowAll} />;
      return <Songs key={w.id} w={w} variant={variant} onShowAll={onShowAll} />;
    }
    if (!isGameWidget(w)) return <Card key={w.id} w={w} variant={variant} />;
    if (w.type === "favorite") return <Favorite key={w.id} w={w} variant={variant} />;
    if (w.type === "playing") return <Playing key={w.id} w={w} />;
    return <Shelf key={w.id} w={w} variant={variant} onShowAll={onShowAll} />;
  };
  return (
    <div className={s.board} data-variant={variant} data-testid="profile-widgets">
      {design
        ? widgets.map((w, i) => (
            <Designed key={w.id} w={w} index={i} count={widgets.length} design={design}>
              {draw(w)}
            </Designed>
          ))
        : widgets.map(draw)}
      {design && widgets.length < MAX_WIDGETS ? (
        <button type="button" className={s.addTile} onClick={design.add} data-testid="designer-add-widget">
          <Plus size={16} aria-hidden="true" /> {t("widgets.addOne")}
        </button>
      ) : null}
    </div>
  );
}
