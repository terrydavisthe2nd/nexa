import { ArrowLeft, ExternalLink, Play, RotateCw, Star, X } from "lucide-react";
import { useEffect, useMemo, useState, type CSSProperties } from "react";
import { Modal } from "@/components/common/Modal";
import { Spinner } from "@/components/common/Spinner";
import { Lightbox, type LightboxItem } from "@/components/chat/Lightbox";
import { YouTubeVideo } from "@/components/chat/YouTubePlayer";
import { GameCover } from "@/components/profile/Widgets";
import { Tr, t } from "@/i18n";
import { avatarColor } from "@/lib/avatarColor";
import { gameCoverUrl, gamePictureUrl, loadGame, STORE_NAMES, type GameDetails, type GameLinkKind } from "@/lib/games";
import { useMediaLinks } from "@/lib/mediaLinks";
import { linkHost } from "@/lib/widgets";
import { useUI } from "@/stores/ui";
import s from "./GamePage.module.css";

/** What the stage shows: a trailer, or a screenshot. */
type Shown = { kind: "video"; id: string; name: string; thumb?: string | undefined; poster?: string | undefined } | { kind: "shot"; id: string; index: number };

function linkName(kind: GameLinkKind): string {
  return kind === "official" ? t("games.page.officialSite") : STORE_NAMES[kind];
}

/** Opens a store or the game's site once the full address has been seen, as activity links do. */
function openOut(url: string) {
  useUI.getState().openModal("confirm", {
    title: t("textMenu.openLink"),
    body: <span dir="ltr" style={{ overflowWrap: "anywhere", unicodeBidi: "isolate" }}>{url}</span>,
    confirmLabel: t("textMenu.openLink"),
    onConfirm: () => {
      window.open(url, "_blank", "noopener,noreferrer");
    },
  });
}

/** IGDB lists a game's videos in no order: trailers go first, the launch or official one before others. */
function trailersFirst<T extends { name?: string | undefined }>(videos: T[]): T[] {
  const rank = (v: T) => {
    const n = (v.name ?? "").toLowerCase();
    if (!/trailer|teaser/.test(n)) return 3;
    if (/launch|official|release|reveal|announce/.test(n)) return 0;
    return /gameplay/.test(n) ? 2 : 1;
  };
  return videos.map((v, i) => ({ v, i })).sort((a, b) => rank(a.v) - rank(b.v) || a.i - b.i).map((x) => x.v);
}

/**
 * The picture across the top: a wide piece of artwork when the game has one
 * big enough (IGDB's artwork is as often a logo or a small sketch), else its
 * first screenshot, else any artwork.
 */
function backdropOf(game: GameDetails): string | undefined {
  const wide = game.artworks.find((a) => (a.w ?? 0) >= 1280 && (a.w ?? 0) >= (a.h ?? 1) * 1.4);
  return (wide ?? game.screenshots[0] ?? game.artworks[0])?.id;
}

/** A paragraph of IGDB's that folds to a few lines until opened. */
function Folded({ text, testId }: { text: string; testId: string }) {
  const [open, setOpen] = useState(false);
  const long = text.length > 360;
  return (
    <div className={s.folded}>
      <p className={s.prose} data-open={open || !long || undefined} dir="auto" data-testid={testId}>{text}</p>
      {long ? (
        <button type="button" className={s.more} onClick={() => setOpen((v) => !v)} aria-expanded={open}>
          {open ? t("games.page.showLess") : t("games.page.readMore")}
        </button>
      ) : null}
    </div>
  );
}

/** One line of the facts: a name and its values. */
function Fact({ label, values }: { label: string; values: string[] }) {
  if (values.length === 0) return null;
  return (
    <div className={s.fact}>
      <dt>{label}</dt>
      <dd dir="auto">{values.join(", ")}</dd>
    </div>
  );
}

/**
 * The stage: the picked trailer or screenshot, large, and under it a strip of
 * everything there is to see. Trailers play through this server's YouTube
 * player; a screenshot opens the picture viewer, with the rest beside it.
 */
function Media({ game }: { game: GameDetails }) {
  const items = useMemo<Shown[]>(
    () => [
      ...trailersFirst(game.videos).map((v): Shown => ({ kind: "video", id: v.id, name: v.name || game.name, thumb: v.thumb, poster: v.poster })),
      ...game.screenshots.map((p, index): Shown => ({ kind: "shot", id: p.id, index })),
    ],
    [game],
  );
  const media = useMediaLinks();
  const [at, setAt] = useState(0);
  const [viewing, setViewing] = useState<number | null>(null);
  useEffect(() => setAt(0), [game.id]);
  const shown = items[at] ?? items[0];
  if (!shown) return null;
  const shots: LightboxItem[] = game.screenshots.map((p, i) => ({ name: t("games.page.screenshotOf", { name: game.name, n: i + 1 }), src: gamePictureUrl(p.id, "b") }));
  return (
    <section className={s.media} aria-label={t("games.page.media")}>
      <div className={s.stage} data-letterboxed={(shown.kind === "video" && !!shown.poster) || undefined} data-testid="game-stage">
        {shown.kind === "video" ? (
          <YouTubeVideo key={shown.id} id={shown.id} title={shown.name} poster={media(shown.poster ?? shown.thumb)} />
        ) : (
          <button type="button" className={s.shot} onClick={() => setViewing(shown.index)} aria-label={t("games.page.viewScreenshot")}>
            <img key={shown.id} src={gamePictureUrl(shown.id, "b")} alt="" draggable={false} />
          </button>
        )}
      </div>
      {items.length > 1 ? (
        <ul className={s.strip} aria-label={t("games.page.media")}>
          {items.map((it, i) => (
            <li key={`${it.kind}-${it.id}`}>
              <button
                type="button"
                className={s.thumb}
                data-on={i === at || undefined}
                aria-pressed={i === at}
                aria-label={it.kind === "video" ? t("games.page.trailerNamed", { name: it.name }) : t("games.page.screenshotOf", { name: game.name, n: it.index + 1 })}
                onClick={() => setAt(i)}
              >
                {it.kind === "video" ? (
                  <>
                    {it.thumb ? <img src={media(it.thumb) ?? ""} alt="" loading="lazy" referrerPolicy="no-referrer" draggable={false} /> : null}
                    <span className={s.thumbPlay} aria-hidden="true"><Play size={14} fill="currentColor" /></span>
                  </>
                ) : (
                  <img src={gamePictureUrl(it.id, "t")} alt="" loading="lazy" draggable={false} />
                )}
              </button>
            </li>
          ))}
        </ul>
      ) : null}
      {viewing !== null ? <Lightbox items={shots} start={viewing} onClose={() => setViewing(null)} /> : null}
    </section>
  );
}

function Page({ game, onOpen }: { game: GameDetails; onOpen: (id: string) => void }) {
  const backdrop = backdropOf(game);
  const released = game.released ? new Date(game.released).toLocaleDateString(undefined, { year: "numeric", month: "long", day: "numeric", timeZone: "UTC" }) : null;
  const byline = [game.year ? String(game.year) : "", game.developers[0] ?? ""].filter(Boolean);
  return (
    <>
      <div className={s.hero} style={{ "--cover-tint": avatarColor(game.name) } as CSSProperties}>
        {backdrop ? <img className={s.backdrop} src={gamePictureUrl(backdrop, "b")} alt="" draggable={false} /> : game.cover ? <span className={s.glow} style={{ backgroundImage: `url("${gameCoverUrl(game.cover, "s")}")` }} /> : null}
      </div>
      <header className={s.head}>
        <GameCover game={game} css={120} className={s.box} />
        <div className={s.titles}>
          <h2 className={s.name} dir="auto">{game.name}</h2>
          {byline.length ? <p className={s.byline} dir="auto">{byline.map((b, i) => <span key={i}>{b}</span>)}</p> : null}
          {game.rating ? (
            <p className={s.score} title={t("games.page.ratingFrom", { count: game.rating_count ?? 0 })}>
              <Star size={14} fill="currentColor" aria-hidden="true" />
              <span>{t("games.page.ratingOutOf", { score: game.rating })}</span>
            </p>
          ) : null}
        </div>
      </header>

      <div className={s.body}>
        <Media game={game} />
        <div className={s.columns}>
          <div className={s.main}>
            {game.summary ? (
              <section>
                <h3 className={s.heading}>{t("games.page.about")}</h3>
                <Folded text={game.summary} testId="game-summary" />
              </section>
            ) : null}
            {game.storyline ? (
              <section>
                <h3 className={s.heading}>{t("games.page.storyline")}</h3>
                <Folded text={game.storyline} testId="game-storyline" />
              </section>
            ) : null}
            {game.links.length ? (
              <section>
                <h3 className={s.heading}>{t("games.page.whereToGet")}</h3>
                <ul className={s.links}>
                  {game.links.map((l) => (
                    <li key={l.kind}>
                      <a className={s.link} href={l.url} target="_blank" rel="noopener noreferrer" onClick={(e) => { e.preventDefault(); openOut(l.url); }} data-testid={`game-link-${l.kind}`}>
                        <ExternalLink size={14} aria-hidden="true" />
                        <span className={s.linkName}>{linkName(l.kind)}</span>
                        <span className={s.linkHost} dir="ltr">{linkHost(l.url)}</span>
                      </a>
                    </li>
                  ))}
                </ul>
              </section>
            ) : null}
          </div>
          <aside className={s.side}>
            <h3 className={s.heading}>{t("games.page.details")}</h3>
            <dl className={s.facts}>
              <Fact label={t("games.page.released")} values={released ? [released] : []} />
              <Fact label={t("games.page.genres")} values={game.genres} />
              <Fact label={t("games.page.themes")} values={game.themes} />
              <Fact label={t("games.page.modes")} values={game.modes} />
              <Fact label={t("games.page.platforms")} values={game.platforms} />
              <Fact label={t("games.page.developers")} values={game.developers} />
              <Fact label={t("games.page.publishers")} values={game.publishers} />
              <Fact label={t("games.page.ageRatings")} values={game.age_ratings.map((a) => `${a.board} ${a.rating}`)} />
            </dl>
          </aside>
        </div>

        {game.similar.length ? (
          <section>
            <h3 className={s.heading}>{t("games.page.similar")}</h3>
            <ul className={s.similar}>
              {game.similar.map((g) => (
                <li key={g.id}>
                  <button type="button" className={s.similarBtn} onClick={() => onOpen(g.id)} data-testid="game-similar">
                    <GameCover game={g} css={96} />
                    <span className={s.similarName} dir="auto">{g.name}</span>
                  </button>
                </li>
              ))}
            </ul>
          </section>
        ) : null}

        <p className={s.credit}>
          <Tr id="widgets.game.credit" values={{ site: <a href="https://www.igdb.com" target="_blank" rel="noopener noreferrer">IGDB.com</a> }} />
        </p>
      </div>
    </>
  );
}

/**
 * A game's page, over whatever opened it: the game's artwork and box, its
 * trailers and screenshots, what it is about, the facts, where to get it and
 * games like it. A similar game opens in its place, and Back returns.
 */
export default function GamePage({ gameId, onClose }: { gameId: string; onClose: () => void }) {
  const [trail, setTrail] = useState<string[]>([gameId]);
  const id = trail[trail.length - 1] ?? gameId;
  const [game, setGame] = useState<GameDetails | null>(null);
  const [failed, setFailed] = useState(false);
  const [attempt, setAttempt] = useState(0);
  useEffect(() => setTrail([gameId]), [gameId]);
  useEffect(() => {
    let alive = true;
    setFailed(false);
    setGame(null);
    loadGame(id)
      .then((g) => alive && setGame(g))
      .catch(() => alive && setFailed(true));
    return () => {
      alive = false;
    };
  }, [id, attempt]);
  // a new page starts at its top
  const [scroller, setScroller] = useState<HTMLDivElement | null>(null);
  // in braces: Chromium's scroll methods now answer a promise, which React would take for a cleanup
  useEffect(() => {
    scroller?.scrollIntoView({ block: "start" });
  }, [id, scroller]);

  return (
    <Modal open onClose={onClose} width={820} bare ariaLabel={game?.name ?? t("games.page.title")}>
      <div className={s.page} ref={setScroller} data-testid="game-page">
        <div className={s.bar}>
          {trail.length > 1 ? (
            <button type="button" className={s.barBtn} onClick={() => setTrail((tr) => tr.slice(0, -1))} aria-label={t("games.page.back")} title={t("games.page.back")} data-testid="game-back">
              <ArrowLeft size={18} />
            </button>
          ) : null}
          <button type="button" className={s.barBtn} data-end="" onClick={onClose} aria-label={t("chat.attachments.close")} title={t("chat.attachments.close")}>
            <X size={18} />
          </button>
        </div>
        {game ? (
          <Page game={game} onOpen={(next) => setTrail((tr) => (tr[tr.length - 1] === next ? tr : [...tr.slice(-19), next]))} />
        ) : failed ? (
          <div className={s.state}>
            <p>{t("games.page.failed")}</p>
            <button type="button" className={s.retry} onClick={() => setAttempt((a) => a + 1)}>
              <RotateCw size={14} aria-hidden="true" /> {t("games.page.retry")}
            </button>
          </div>
        ) : (
          <div className={s.state} aria-busy="true">
            <Spinner />
          </div>
        )}
      </div>
    </Modal>
  );
}
