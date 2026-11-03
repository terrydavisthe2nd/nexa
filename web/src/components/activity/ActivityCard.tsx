import { Gamepad2, Info, Music, Radio, Trophy, Tv } from "lucide-react";
import { useEffect, useState, type CSSProperties } from "react";
import { canOpenGames, matchGame, openGame } from "@/lib/games";
import type { Activity } from "@/api/types";
import { useNow } from "@/hooks/useNow";
import { activityHeading, activityProgress, formatClock, isSpotify } from "@/lib/activity";
import { ScrollGroup, ScrollingText } from "@/components/common/ScrollingText";
import s from "./Activity.module.css";

import { t } from "@/i18n";
import { useUI } from "@/stores/ui";
import { activityDestination } from "@/lib/activity";
/** Text that opens its link in a new tab when the activity gives one. */
function Linked({ href, className, title, children }: { href?: string | undefined; className?: string | undefined; title?: string | undefined; children: React.ReactNode }) {
  const destination = activityDestination(href);
  const review = (event: React.MouseEvent<HTMLAnchorElement>) => {
    if (!destination || (event.type === "auxclick" && event.button !== 1)) return;
    event.preventDefault();
    useUI.getState().openModal("confirm", {
      title: t("textMenu.openLink"),
      body: <span dir="ltr" style={{ overflowWrap: "anywhere", unicodeBidi: "isolate" }}>{destination.href}</span>,
      confirmLabel: t("textMenu.openLink"),
      onConfirm: () => { window.open(destination.href, "_blank", "noopener,noreferrer"); },
    });
  };
  return destination ? (
    <a className={className} href={destination.href} target="_blank" rel="noopener noreferrer" title={destination.href} onClick={review} onAuxClick={review}>
      {children}{className ? <span className={s.destination} dir="ltr">{destination.host}</span> : null}
    </a>
  ) : (
    <span className={className} title={title}>{children}</span>
  );
}

/**
 * IGDB's id for the game an activity is playing, once the server has found
 * one with exactly its name; null until then, and for anything else.
 */
function useGameMatch(a: Activity): string | null {
  const game = (a.type === 0 || a.type === 5) && canOpenGames() ? a.name.trim() : "";
  const [id, setId] = useState<string | null>(null);
  useEffect(() => {
    setId(null);
    if (!game) return;
    let alive = true;
    matchGame(game)
      .then((found) => alive && setId(found))
      .catch(() => {});
    return () => {
      alive = false;
    };
  }, [game]);
  return id;
}

/** The picture for a kind of activity: a song, a stream, a video, a match, or a game. */
const kindIcon = (type: Activity["type"]) => (type === 1 ? Radio : type === 2 ? Music : type === 3 ? Tv : type === 5 ? Trophy : Gamepad2);

function Fallback({ type }: { type: Activity["type"] }) {
  const Icon = kindIcon(type);
  return <span className={s.fallback}><Icon size={32} /></span>;
}

/**
 * One activity the way Discord shows Rich Presence in a profile: its art, what
 * and where, a progress bar for music and video or the time spent, the party
 * size, and up to two buttons.
 */
export function ActivityCard({ activity: a }: { activity: Activity }) {
  const timed = !!(a.timestamps?.start || a.timestamps?.end);
  const now = useNow(timed);
  const clock = timed ? activityProgress(a, now) : null;
  const large = a.assets?.large_image || a.icon_url;
  // music, video and streams lead with the track or title; games with their name
  const media = a.type === 1 || a.type === 2 || a.type === 3;
  const title = media ? a.details || a.name : a.name;
  const lines: { text: string; muted?: boolean }[] = [];
  if (media) {
    if (a.state) lines.push({ text: a.type === 2 ? t("activity.activityCard.byArtist", { name: a.state }) : a.state });
    if (a.assets?.large_text) lines.push({ text: a.type === 2 ? t("activity.activityCard.onAlbum", { name: a.assets.large_text }) : a.assets.large_text, muted: true });
  } else {
    if (a.details) lines.push({ text: a.details });
    const party = a.party?.size ? t("activity.activityCard.partySize", { count: a.party.size[0], max: a.party.size[1] }) : "";
    if (a.state || party) lines.push({ text: [a.state ?? "", party].filter(Boolean).join(" ") });
  }
  const KindIcon = kindIcon(a.type);
  const gameId = useGameMatch(a);
  // the art, blurred far out behind the card, tints it with whatever is playing
  const ambient = large ? ({ "--art": `url(${JSON.stringify(large)})` } as CSSProperties) : undefined;
  return (
    <ScrollGroup>
    <div className={s.card} data-testid="activity-card" style={ambient}>
      {large ? <span className={s.ambient} aria-hidden="true" /> : null}
      <h4 className={s.heading}>
        {/* a song part way through is playing: its note gives way to bars that move */}
        {a.type === 2 && clock?.kind === "progress" && clock.fraction < 1 ? (
          <span className={s.eq} data-spotify={isSpotify(a) || undefined} aria-hidden="true"><i /><i /><i /></span>
        ) : (
          <KindIcon size={13} className={s.headingIcon} data-kind={a.type} aria-hidden="true" />
        )}
        <ScrollingText className={s.headingText}>{activityHeading(a)}</ScrollingText>
        {a.source === "discord" ? <span className={s.source}>{t("activity.activityCard.fromDiscord")}</span> : null}
      </h4>
      <div className={s.row}>
        <div className={s.art}>
          {large ? (
            <Linked href={a.assets?.large_url} title={a.assets?.large_text}>
              <img className={s.large} src={large} alt={a.assets?.large_text ?? a.name} loading="lazy" draggable={false} />
            </Linked>
          ) : (
            <Fallback type={a.type} />
          )}
          {a.assets?.small_image ? (
            <Linked href={a.assets.small_url} title={a.assets.small_text}>
              <img className={s.small} src={a.assets.small_image} alt={a.assets.small_text ?? ""} loading="lazy" draggable={false} />
            </Linked>
          ) : null}
        </div>
        <div className={s.text}>
          <span className={s.title} title={title}><ScrollingText>{title}</ScrollingText></span>
          {lines.map((l, i) => (
            <span key={i} className={[s.line, l.muted ? s.muted : ""].join(" ")} title={l.text}><ScrollingText>{l.text}</ScrollingText></span>
          ))}
          {clock?.kind === "remaining" ? <span className={s.clock}>{t("activity.activityCard.timeLeft", { time: formatClock(clock.remaining) })}</span> : null}
          {clock?.kind === "elapsed" ? <span className={s.clock}>{t("activity.activityCard.timeElapsed", { time: formatClock(clock.elapsed) })}</span> : null}
        </div>
      </div>
      {clock?.kind === "progress" ? (
        <div className={s.progress} data-testid="activity-progress">
          <span>{formatClock(clock.elapsed)}</span>
          <div className={s.bar} role="progressbar" aria-valuemin={0} aria-valuemax={Math.round(clock.total / 1000)} aria-valuenow={Math.round(clock.elapsed / 1000)} aria-label={t("activity.activityCard.playback")}>
            <i style={{ width: `${clock.fraction * 100}%` }} />
          </div>
          <span>{formatClock(clock.total)}</span>
        </div>
      ) : null}
      {a.buttons?.length || gameId ? (
        <div className={s.buttons}>
          {a.buttons?.map((b) => (
            <Linked key={b.url + b.label} className={s.button} href={b.url}>{b.label}</Linked>
          ))}
          {gameId ? (
            <button type="button" className={[s.button, s.gameButton].join(" ")} onClick={() => openGame(gameId)} data-testid="activity-game">
              <Info size={15} aria-hidden="true" /> {t("games.page.viewGame")}
            </button>
          ) : null}
        </div>
      ) : null}
    </div>
    </ScrollGroup>
  );
}

/** Every activity of someone, newest first. */
export function ActivityList({ activities }: { activities: Activity[] }) {
  if (activities.length === 0) return null;
  return (
    <ScrollGroup>
      <div className={s.list}>
        {activities.map((a) => (
          <ActivityCard key={`${a.application_id ?? ""}:${a.name}:${a.created_at}`} activity={a} />
        ))}
      </div>
    </ScrollGroup>
  );
}
