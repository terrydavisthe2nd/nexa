import { Gamepad2 } from "lucide-react";
import { useCallback, useEffect, useMemo, useState } from "react";
import { moderation as modApi, type DecidedGame, type StaffGames, type WaitingGame } from "@/api/moderation";
import { Button } from "@/components/common/Button";
import { LoadError } from "@/components/common/LoadError";
import { Modal } from "@/components/common/Modal";
import { Spinner } from "@/components/common/Spinner";
import { Field, TextInput } from "@/components/common/TextInput";
import { GameShelf, useGameForm, type FoundGame } from "@/components/games/GameChooser";
import { GameCover } from "@/components/profile/Widgets";
import { t } from "@/i18n";
import { describeError } from "@/lib/errors";
import { formatRelative } from "@/lib/time";
import { useModEvents } from "@/stores/moderation";
import { useUI } from "@/stores/ui";
import m from "./Moderation.module.css";
import g from "./GamesTab.module.css";

type View = "waiting" | "listed" | "rejected";

/** What Review Game is about: something waiting, or something already on the list. */
interface Reviewing {
  process: string;
  name: string;
  game: FoundGame | null;
  others: string[];
  waiting: boolean;
}

/**
 * Games people shared from the desktop app. Each row leads with the program,
 * since that is what is being decided, with the game it was said to be below:
 * what staff add here reaches every desktop app. Only how many shared a program
 * shows, never who.
 */
export function GamesTab() {
  const toast = useUI((u) => u.toast);
  const [data, setData] = useState<StaffGames | null>(null);
  const [failed, setFailed] = useState(false);
  const [view, setView] = useState<View>("waiting");
  const [reviewing, setReviewing] = useState<Reviewing | null>(null);

  const load = useCallback(() => {
    setFailed(false);
    modApi.games().then(setData, () => setFailed(true));
  }, []);
  useEffect(() => load(), [load]);
  // someone shared one, or another member of staff decided
  useModEvents((e) => {
    if (e.type === "games" || (e.type === "log" && e.entry.action.startsWith("game_"))) load();
  });

  const gameOf = useCallback(
    (id: string | undefined, name: string): FoundGame | null => {
      if (!id) return null;
      const known = data?.games[id];
      return known ?? { id, name };
    },
    [data],
  );
  const listed = useMemo(() => data?.decided.filter((x) => x.status === "listed") ?? [], [data]);
  const rejected = useMemo(() => data?.decided.filter((x) => x.status === "rejected") ?? [], [data]);

  const act = async (run: () => Promise<void>, done: string) => {
    try {
      await run();
      toast(done, "success");
      load();
    } catch (err) {
      toast(describeError(err, t("moderation.failed")), "error");
    }
  };
  const turnDown = (x: { process: string; name: string }) => act(() => modApi.rejectGame({ process: x.process, name: x.name }), t("moderation.games.turnedDownDone"));

  if (failed) return <LoadError message={t("moderation.failed")} onRetry={load} />;
  if (!data) return <div className={m.loadingRow}><Spinner /></div>;

  const tabs: { id: View; label: string; count: number }[] = [
    { id: "waiting", label: t("moderation.games.waiting"), count: data.waiting.length },
    { id: "listed", label: t("moderation.games.onList"), count: listed.length },
    { id: "rejected", label: t("moderation.games.turnedDown"), count: rejected.length },
  ];

  return (
    <div className={m.scroll} data-testid="games-tab">
      <p className={g.hint}>{t("moderation.games.hint")}</p>
      <div className={g.views} role="tablist" aria-label={t("moderation.tabs.games")}>
        {tabs.map((x) => (
          <button key={x.id} type="button" role="tab" aria-selected={view === x.id} className={m.chip} data-selected={view === x.id} onClick={() => setView(x.id)} data-testid={`games-view-${x.id}`}>
            {x.label}{x.count ? <span className={g.count}>{x.count}</span> : null}
          </button>
        ))}
      </div>

      {view === "waiting" ? (
        data.waiting.length === 0 ? (
          <Empty text={t("moderation.games.waitingNone")} />
        ) : (
          <ul className={g.rows}>
            {data.waiting.map((x: WaitingGame) => (
              <li key={x.process}>
                <Row
                  process={x.process}
                  name={x.name}
                  game={gameOf(x.game_id, x.name)}
                  aside={t("moderation.games.sharedBy", { count: x.count })}
                  actions={
                    <>
                      <Button size="sm" variant="ghost" onClick={() => void turnDown(x)} data-testid="game-turn-down">{t("moderation.games.turnDown")}</Button>
                      <Button size="sm" onClick={() => setReviewing({ process: x.process, name: x.name, game: gameOf(x.game_id, x.name), others: x.other_names ?? [], waiting: true })} data-testid="game-review">{t("moderation.games.review")}</Button>
                    </>
                  }
                />
              </li>
            ))}
          </ul>
        )
      ) : view === "listed" ? (
        listed.length === 0 ? (
          <Empty text={t("moderation.games.listNone")} />
        ) : (
          <ul className={g.rows}>
            {listed.map((x: DecidedGame) => (
              <li key={x.process}>
                <Row
                  process={x.process}
                  name={x.name}
                  game={gameOf(x.game_id, x.name)}
                  aside={formatRelative(x.decided_at)}
                  actions={
                    <>
                      <Button size="sm" variant="ghost" onClick={() => void act(() => modApi.forgetGame(x.process), t("moderation.games.removedDone"))}>{t("moderation.games.remove")}</Button>
                      <Button size="sm" variant="secondary" onClick={() => setReviewing({ process: x.process, name: x.name, game: gameOf(x.game_id, x.name), others: [], waiting: false })}>{t("moderation.games.edit")}</Button>
                    </>
                  }
                />
              </li>
            ))}
          </ul>
        )
      ) : rejected.length === 0 ? (
        <Empty text={t("moderation.games.rejectedNone")} />
      ) : (
        <ul className={g.rows}>
          {rejected.map((x: DecidedGame) => (
            <li key={x.process}>
              <Row
                process={x.process}
                name={x.name}
                game={null}
                aside={formatRelative(x.decided_at)}
                actions={<Button size="sm" variant="secondary" onClick={() => void act(() => modApi.forgetGame(x.process), t("moderation.games.allowedDone"))}>{t("moderation.games.allow")}</Button>}
              />
            </li>
          ))}
        </ul>
      )}
      {reviewing ? (
        <ReviewDialog
          key={reviewing.process}
          item={reviewing}
          onClose={() => setReviewing(null)}
          onTurnDown={() => {
            const x = reviewing;
            setReviewing(null);
            void turnDown(x);
          }}
          onDone={() => {
            setReviewing(null);
            load();
          }}
        />
      ) : null}
    </div>
  );
}

function Empty({ text }: { text: string }) {
  return (
    <div className={m.empty}>
      <Gamepad2 size={28} aria-hidden="true" />
      <p className={g.emptyText}>{text}</p>
    </div>
  );
}

function Row({ process, name, game, aside, actions }: { process: string; name: string; game: FoundGame | null; aside: string; actions: React.ReactNode }) {
  return (
    <div className={g.row} data-testid="game-row">
      <GameCover game={{ ...(game ? { id: game.id } : {}), name, ...(game?.cover ? { cover: game.cover } : {}) }} css={56} className={g.cover} />
      <div className={g.text}>
        <span className={g.process} dir="ltr">{process}</span>
        <span className={g.game} dir="auto">{name}</span>
        <span className={g.link}>{game ? (game.year ? t("moderation.games.matchedYear", { year: String(game.year) }) : t("moderation.games.matched")) : t("moderation.games.noMatch")}</span>
      </div>
      <div className={g.side}>
        <span className={g.aside}>{aside}</span>
        <div className={g.actions}>{actions}</div>
      </div>
    </div>
  );
}

/** Staff have the last word on the name and the game, whatever was shared. */
function ReviewDialog({ item, onClose, onTurnDown, onDone }: { item: Reviewing; onClose: () => void; onTurnDown: () => void; onDone: () => void }) {
  // what is waiting with no game yet takes the one game of exactly its name, if there is one, as the add dialog does
  const form = useGameForm(item.name, item.game, !item.waiting || !!item.game);
  const add = () =>
    void form.save(
      (name, gameId) => modApi.listGame({ process: item.process, name, ...(gameId ? { game_id: gameId } : {}) }),
      () => {
        useUI.getState().toast(t("moderation.games.addedDone", { name: form.name.trim() }), "success");
        onDone();
      },
    );
  return (
    <Modal
      open
      onClose={onClose}
      title={t("moderation.games.reviewTitle")}
      width={560}
      footer={
        <>
          {item.waiting ? <Button variant="ghost" className={g.footerStart} onClick={onTurnDown}>{t("moderation.games.turnDown")}</Button> : null}
          <Button variant="ghost" onClick={onClose}>{t("chat.editMessage.cancel")}</Button>
          <Button onClick={add} disabled={!form.name.trim()} loading={form.saving} data-testid="game-add-to-list">{item.waiting ? t("moderation.games.addToList") : t("settings.games.save")}</Button>
        </>
      }
    >
      <div className={g.program}>
        <span className={g.programLabel}>{t("moderation.games.program")}</span>
        <span className={g.process} dir="ltr">{item.process}</span>
      </div>
      <Field label={t("settings.games.nameLabel")} hint={t("moderation.games.nameHint")}>
        <TextInput
          value={form.name}
          maxLength={128}
          onChange={(e) => form.setName(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === "Enter") {
              e.preventDefault();
              add();
            }
          }}
          data-testid="game-review-name"
          spellCheck={false}
          autoComplete="off"
        />
      </Field>
      {item.others.length ? (
        <div className={g.others}>
          <span className={g.othersLabel}>{t("moderation.games.otherNames")}</span>
          {item.others.map((n) => (
            <button key={n} type="button" className={m.chip} onClick={() => form.setName(n)} dir="auto">{n}</button>
          ))}
        </div>
      ) : null}
      <GameShelf form={form} />
    </Modal>
  );
}
