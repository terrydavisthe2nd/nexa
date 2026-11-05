import { Check } from "lucide-react";
import { useEffect, useMemo, useState } from "react";
import { users as usersApi } from "@/api/users";
import { GameCover } from "@/components/profile/Widgets";
import { Tr, t } from "@/i18n";
import { describeError } from "@/lib/errors";
import { useUI } from "@/stores/ui";
import s from "./GameChooser.module.css";

/** Covers shown to choose from: one row on a computer, two on a phone. */
const SHELF = 6;

export interface FoundGame {
  id: string;
  name: string;
  cover?: string;
  year?: number;
}

/** Names as a person would call them the same: no case, no punctuation, no trademark signs. */
export const sameName = (a: string, b: string): boolean => {
  const fold = (x: string) => x.toLowerCase().replace(/[^\p{L}\p{N}]+/gu, "");
  return fold(a) === fold(b);
};

export type GameForm = ReturnType<typeof useGameForm>;

/** What a game is called and which one it is on IGDB, which gives it a cover and a page. */
export function useGameForm(initialName = "", initialGame: FoundGame | null = null, initialDecided = !!initialName) {
  const [name, setName] = useState(initialName);
  const [chosen, setChosen] = useState<FoundGame | null>(initialGame);
  // once someone has chosen or unchosen a game, an exact match no longer chooses for them
  const [decided, setDecided] = useState(initialDecided);
  const [saving, setSaving] = useState(false);
  return {
    name,
    setName,
    chosen,
    decided,
    saving,
    choose(game: FoundGame | null) {
      setDecided(true);
      setChosen(game);
      if (game) setName(game.name);
    },
    /** The one game with exactly this name is what the server would find anyway, so it shows as chosen until someone says otherwise. */
    suggest(game: FoundGame) {
      setChosen(game);
    },
    reset(next: string, game: FoundGame | null = null) {
      setName(next);
      setChosen(game);
      setDecided(!!game);
    },
    async save<T>(run: (name: string, gameId: string | null) => Promise<T>, done: (result: T) => void) {
      const clean = name.trim();
      if (!clean || saving) return;
      setSaving(true);
      try {
        done(await run(clean, chosen?.id ?? null));
      } catch (err) {
        useUI.getState().toast(describeError(err, t("settings.privacy.couldNotSave")), "error");
        setSaving(false);
      }
    },
  };
}

/** Box art to say which game it is, searched from the name: people know a game by its cover. */
export function GameShelf({ form }: { form: GameForm }) {
  const [found, setFound] = useState<FoundGame[]>([]);
  const [searching, setSearching] = useState(false);
  const [ready, setReady] = useState(true);
  const query = form.name.trim();
  const { chosen, decided } = form;

  useEffect(() => {
    if (!query) {
      setFound([]);
      setSearching(false);
      return;
    }
    let alive = true;
    setSearching(true);
    const timer = setTimeout(() => {
      usersApi.searchGames(query).then(
        (res) => {
          if (!alive) return;
          setReady(res.ready);
          setFound(res.games.slice(0, SHELF));
          setSearching(false);
          if (!decided) {
            // two games sharing the name are for the person to tell apart
            const exact = res.games.filter((g) => sameName(g.name, query));
            if (exact.length === 1) form.suggest(exact[0]!);
          }
        },
        () => {
          if (!alive) return;
          setFound([]);
          setSearching(false);
        },
      );
    }, 280);
    return () => {
      alive = false;
      clearTimeout(timer);
    };
    // form's functions are made afresh each render; what they act on is in the list
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [query, decided]);

  // the game chosen stays in view, first, whatever a changed name now finds
  const shelf = useMemo(() => {
    if (!chosen) return found;
    const full = found.find((g) => g.id === chosen.id) ?? chosen;
    return [full, ...found.filter((g) => g.id !== chosen.id)].slice(0, SHELF);
  }, [found, chosen]);

  if (!ready) return null;
  return (
    <>
      <p className={s.label}>{t("settings.games.whichGame")}</p>
      {shelf.length ? (
        <ul className={s.shelf}>
          {shelf.map((g) => {
            const on = g.id === chosen?.id;
            return (
              <li key={g.id}>
                <button type="button" className={s.box} aria-pressed={on} onClick={() => form.choose(on ? null : g)} data-testid="game-form-result">
                  <span className={s.art}>
                    <GameCover game={{ id: g.id, name: g.name, ...(g.cover ? { cover: g.cover } : {}) }} css={84} className={s.cover} />
                    {on ? <span className={s.tick} aria-hidden="true"><Check size={13} strokeWidth={3} /></span> : null}
                  </span>
                  <span className={s.name} dir="auto">{g.name}</span>
                  {g.year ? <span className={s.year}>{g.year}</span> : null}
                </button>
              </li>
            );
          })}
        </ul>
      ) : (
        <p className={s.note}>{searching ? t("widgets.game.searching") : t("settings.games.noMatchYet")}</p>
      )}
      <p className={s.credit}><Tr id="widgets.game.credit" values={{ site: <a href="https://www.igdb.com" target="_blank" rel="noopener noreferrer">IGDB.com</a> }} /></p>
    </>
  );
}
