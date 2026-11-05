import { AppWindow, Gamepad2, Pencil, Plus, RefreshCw, Share2, Trash2 } from "lucide-react";
import { useEffect, useMemo, useRef, useState } from "react";
import { activities as activitiesApi } from "@/api/activities";
import type { Activity } from "@/api/types";
import { users as usersApi } from "@/api/users";
import { ActivityCard } from "@/components/activity/ActivityCard";
import { GameShelf, sameName, useGameForm, type GameForm } from "@/components/games/GameChooser";
import { Button } from "@/components/common/Button";
import { Modal } from "@/components/common/Modal";
import { SearchBox } from "@/components/common/SearchBox";
import { Field, TextInput } from "@/components/common/TextInput";
import { Toggle } from "@/components/common/Toggle";
import d from "@/components/desktop/Desktop.module.css";
import { t } from "@/i18n";
import { desktopBridge, type CustomGame, type DetectedGame, type GamesState, type GameSource, type RunningApp } from "@/lib/desktopBridge";
import { describeError } from "@/lib/errors";
import { formatRelative } from "@/lib/time";
import { useDesktop } from "@/stores/desktop";
import { useUI } from "@/stores/ui";
import a from "./Activity.module.css";
import st from "./Settings.module.css";
import s from "./GameDetection.module.css";

const FOUND_BY: Record<GameSource, string> = {
  steam: "settings.games.foundSteam",
  epic: "settings.games.foundEpic",
  gog: "settings.games.foundGog",
  xbox: "settings.games.foundXbox",
  itch: "settings.games.foundItch",
  store: "settings.games.foundStore",
  known: "settings.games.foundKnown",
  engine: "settings.games.foundEngine",
  system: "settings.games.foundSystem",
  custom: "settings.games.foundCustom",
};

/** Sends what the app says sharing this game would send: a file name and a name, never a path. */
export async function shareGame(id: string): Promise<boolean> {
  const api = desktopBridge()?.games;
  if (!api?.shareInfo || !api.markShared) return false;
  const info = await api.shareInfo(id);
  if (!info) return false;
  await usersApi.shareGame(info);
  useDesktop.getState().setGames(await api.markShared(id));
  return true;
}

/**
 * The desktop app's game detection: whether it looks at all, the games it sees
 * running, the ones added by hand and the ones kept off the profile. A program
 * reporting a game itself through Rich Presence is listed above and wins, so
 * such a game says so here rather than showing twice.
 */
export function GameDetectionSection({ share: sharing, rpcOn }: { share: boolean; rpcOn: boolean }) {
  const bridge = desktopBridge();
  const games = useDesktop((x) => x.games);
  const [dialog, setDialog] = useState<null | "add" | CustomGame>(null);
  const [previews, setPreviews] = useState<Record<string, Activity>>({});
  const api = bridge?.games;

  // the art and game pages as others will see them, from the server that finds them for everyone
  const running = games?.running;
  const previewKey = useMemo(() => JSON.stringify(running?.map((g) => g.activity) ?? []), [running]);
  useEffect(() => {
    if (!running?.length) {
      setPreviews({});
      return;
    }
    let alive = true;
    const timer = setTimeout(() => {
      activitiesApi
        .preview(running.map((g) => g.activity))
        .then((list) => {
          if (!alive) return;
          const next: Record<string, Activity> = {};
          for (const act of list) next[act.name] = act;
          setPreviews(next);
        })
        .catch(() => {});
    }, 300);
    return () => {
      alive = false;
      clearTimeout(timer);
    };
    // previewKey stands for running
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [previewKey]);

  if (!bridge) return null;
  if (!api) {
    return (
      <>
        <h3 data-setting="gameDetection" className={st.h5}>{t("settings.games.title")}</h3>
        <p className={st.hint}>{t("settings.games.updateApp")}</p>
      </>
    );
  }
  const on = !!games?.enabled;
  const share = async (id: string) => {
    try {
      if (await shareGame(id)) useUI.getState().toast(t("settings.games.shareDone"), "success");
    } catch (err) {
      useUI.getState().toast(describeError(err, t("settings.privacy.couldNotSave")), "error");
    }
  };
  const setHidden = (key: string, name: string, hidden: boolean) => void api.setHidden(key, name, hidden).then((g) => useDesktop.getState().setGames(g));
  const remove = (g: CustomGame) =>
    useUI.getState().openModal("confirm", {
      title: t("settings.games.removeTitle"),
      body: t("settings.games.removeBody", { name: g.name }),
      confirmLabel: t("settings.games.remove"),
      danger: true,
      onConfirm: () => {
        void api.remove(g.id).then((next) => useDesktop.getState().setGames(next));
      },
    });

  return (
    <>
      <h3 data-setting="gameDetection" className={st.h5}>{t("settings.games.title")}</h3>
      <div className={st.form}>
        <Toggle
          checked={on}
          disabled={!rpcOn}
          onChange={(v) => void api.setEnabled(v).then((g) => useDesktop.getState().setGames(g))}
          label={t("settings.games.detect")}
          description={t("settings.games.detectHint")}
        />
        {!rpcOn ? <p className={st.hint}>{t("settings.games.needsRpc")}</p> : !sharing ? <p className={st.hint}>{t("settings.activity.sharingOff")}</p> : null}

        {on && games?.suggestions.length ? (
          <div>
            <p className={[a.sub, s.listLabel].join(" ")}>{t("settings.games.suggested")}</p>
            <div className={s.rows}>
              {games.suggestions.map((g) => (
                <div key={g.id} className={s.row} data-testid="game-suggestion">
                  <span className={s.rowIcon} aria-hidden="true"><Gamepad2 size={18} /></span>
                  <div className={s.rowText}>
                    <span className={d.appName} dir="auto">{g.name}</span>
                    <span className={s.exe} dir="ltr">{g.exe}</span>
                  </div>
                  <Button size="sm" variant="ghost" onClick={() => void api.answer(g.id, false).then((next) => useDesktop.getState().setGames(next))}>{t("settings.games.notAGame")}</Button>
                  <Button size="sm" onClick={() => void api.answer(g.id, true).then((next) => useDesktop.getState().setGames(next))}>{t("settings.games.showIt")}</Button>
                </div>
              ))}
            </div>
            <p className={st.hint}>{t("settings.games.askHint")}</p>
          </div>
        ) : null}

        {on ? (
          <div>
            <p className={[a.sub, s.listLabel].join(" ")}>{t("settings.games.playingNow")}</p>
            {running?.length ? (
              <div className={d.apps}>
                {running.map((g) => (
                  <PlayingRow key={g.key} game={g} preview={previews[g.name]} onHide={(hidden) => setHidden(g.key, g.name, hidden)} />
                ))}
              </div>
            ) : (
              <p className={st.hint} data-testid="games-none-running">{t("settings.games.nothingRunning")}</p>
            )}
          </div>
        ) : null}

        <div>
          <div className={s.head}>
            <p className={a.sub}>{t("settings.games.added")}</p>
            <Button size="sm" variant="secondary" onClick={() => setDialog("add")} data-testid="game-add">
              <Plus size={15} aria-hidden="true" /> {t("settings.games.add")}
            </Button>
          </div>
          {games?.custom.length ? (
            <div className={s.rows}>
              {games.custom.map((g) => (
                <div key={g.id} className={s.row} data-testid="game-custom">
                  <div className={s.rowText}>
                    <span className={d.appName} dir="auto">{g.name}</span>
                    <span className={s.exe} dir="ltr">{g.exe}</span>
                  </div>
                  {g.shared ? <span className={s.tag}>{t("settings.games.shared")}</span> : <Button size="sm" variant="secondary" aria-label={t("settings.games.shareNamed", { name: g.name })} onClick={() => void share(g.id)} data-testid="game-share"><Share2 size={15} aria-hidden="true" /> {t("settings.games.share")}</Button>}
                  <Button size="sm" variant="ghost" aria-label={t("settings.games.editNamed", { name: g.name })} onClick={() => setDialog(g)}><Pencil size={15} aria-hidden="true" /></Button>
                  <Button size="sm" variant="ghost" aria-label={t("settings.games.removeNamed", { name: g.name })} onClick={() => remove(g)}><Trash2 size={15} aria-hidden="true" /></Button>
                </div>
              ))}
            </div>
          ) : (
            <p className={st.hint}>{t("settings.games.addedNone")}</p>
          )}
        </div>

        {games?.hidden.length ? (
          <div>
            <p className={[a.sub, s.listLabel].join(" ")}>{t("settings.games.hidden")}</p>
            <div className={s.rows}>
              {games.hidden.map((g) => (
                <div key={g.key} className={s.row} data-testid="game-hidden">
                  <div className={s.rowText}><span className={d.appName} dir="auto">{g.name}</span></div>
                  <Button size="sm" variant="secondary" onClick={() => setHidden(g.key, g.name, false)}>{t("settings.games.show")}</Button>
                </div>
              ))}
            </div>
          </div>
        ) : null}
      </div>
      {dialog === "add" ? <AddGameDialog onClose={() => setDialog(null)} /> : null}
      {dialog && dialog !== "add" ? <EditGameDialog game={dialog} onClose={() => setDialog(null)} /> : null}
    </>
  );
}

function PlayingRow({ game, preview, onHide }: { game: DetectedGame; preview: Activity | undefined; onHide: (hidden: boolean) => void }) {
  const found = t(FOUND_BY[game.source]);
  return (
    <div className={d.app} data-hidden={game.hidden} data-testid="game-running">
      <div className={d.appHead}>
        <div style={{ minWidth: 0 }}>
          <div className={d.appName} dir="auto">{game.name}</div>
          <div className={d.appSub}>{game.reporting ? t("settings.games.reporting") : found}</div>
        </div>
        {/* what shows is the program's own report, which has its switch under Programs; one here would change nothing */}
        {game.reporting ? null : <Toggle checked={!game.hidden} onChange={(on) => onHide(!on)} ariaLabel={t("settings.desktop.showOnMyProfile", { name: game.name })} />}
      </div>
      {game.reporting ? null : preview ? (
        <div className={d.preview}><ActivityCard activity={preview} /></div>
      ) : (
        <div className={d.appIdle}>{t("settings.games.startedAt", { when: formatRelative(game.startedAt) })}</div>
      )}
    </div>
  );
}

/** Which running program is the game, in one dialog that stays put while its steps change. */
function AddGameDialog({ onClose }: { onClose: () => void }) {
  const api = desktopBridge()?.games;
  const [apps, setApps] = useState<RunningApp[] | null>(null);
  const [failed, setFailed] = useState(false);
  const [q, setQ] = useState("");
  const [picked, setPicked] = useState<RunningApp | null>(null);
  const form = useGameForm();
  const [sharing, setSharing] = useState(false);
  const seq = useRef(0);

  const load = () => {
    if (!api) return;
    const n = ++seq.current;
    setFailed(false);
    setApps(null);
    api.runningApps().then(
      (list) => n === seq.current && setApps(list),
      () => n === seq.current && setFailed(true),
    );
  };
  // eslint-disable-next-line react-hooks/exhaustive-deps
  useEffect(load, [api]);

  const shown = useMemo(() => {
    const needle = q.trim().toLowerCase();
    if (!needle) return apps ?? [];
    return (apps ?? []).filter((x) => x.name.toLowerCase().includes(needle) || x.exe.toLowerCase().includes(needle));
  }, [apps, q]);

  if (!api) return null;
  const pick = (app: RunningApp) => {
    form.reset(app.detectedAs ?? app.name);
    setPicked(app);
  };
  const addIt = () =>
    picked
      ? form.save(async (name, gameId) => {
          const next = await api.add(picked.id, name, gameId);
          // the game is added either way; sharing can be tried again from its row
          const added = next.custom.find((g) => g.exe === picked.exe && g.name === name);
          if (sharing && added) {
            try {
              await shareGame(added.id);
              useUI.getState().toast(t("settings.games.shareDone"), "success");
              return;
            } catch (err) {
              useUI.getState().toast(describeError(err, t("settings.privacy.couldNotSave")), "error");
            }
          }
          useDesktop.getState().setGames(next);
        }, onClose)
      : undefined;
  return (
    <Modal
      open
      onClose={onClose}
      title={t("settings.games.add")}
      width={560}
      footer={picked ? <FormFooter form={form} label={t("settings.games.add")} onCancel={onClose} onSave={() => void addIt()} /> : undefined}
    >
      {picked ? (
        <>
          <div className={s.picked} data-testid="game-picked">
            <AppIcon app={picked} />
            <span className={s.pickText}>
              <span className={s.pickName} dir="auto">{picked.name}</span>
              <span className={s.exe} dir="ltr">{picked.exe}</span>
            </span>
            <Button size="sm" variant="ghost" onClick={() => setPicked(null)} data-testid="game-form-back">{t("settings.games.change")}</Button>
          </div>
          <GameFields form={form} exe={picked.exe} onSubmit={() => void addIt()} />
          <div className={s.shareBox}>
            <Toggle checked={sharing} onChange={setSharing} label={t("settings.games.shareToggle")} description={t("settings.games.shareHint", { exe: picked.exe })} />
          </div>
        </>
      ) : (
        <>
          <div className={s.pickHead}>
            <p className={st.hint}>{t("settings.games.pickHint")}</p>
            <Button size="sm" variant="ghost" onClick={load} disabled={apps === null && !failed} data-testid="game-apps-refresh">
              <RefreshCw size={15} aria-hidden="true" /> {t("settings.games.refresh")}
            </Button>
          </div>
          <SearchBox value={q} onChange={setQ} aria-label={t("settings.games.search")} placeholder={t("settings.games.search")} data-testid="game-apps-search" autoFocus />
          {failed ? (
            <p className={s.note}>{t("settings.games.listFailed")}</p>
          ) : apps === null ? (
            <p className={s.note}>{t("settings.games.listLoading")}</p>
          ) : shown.length === 0 ? (
            <p className={s.note}>{q.trim() ? t("settings.games.noneMatch") : t("settings.games.noneRunning")}</p>
          ) : (
            <ul className={s.apps}>
              {shown.map((app) => (
                <li key={app.id}>
                  <button type="button" className={s.pick} onClick={() => pick(app)} data-testid="game-app">
                    <AppIcon app={app} />
                    <span className={s.pickText}>
                      <span className={s.pickName} dir="auto">{app.name}</span>
                      {sameName(app.name, app.exe) || sameName(app.name, app.exe.replace(/\.[^.]+$/, "")) ? null : <span className={s.exe} dir="ltr">{app.exe}</span>}
                    </span>
                    {app.added ? <span className={s.tag}>{t("settings.games.alreadyAdded")}</span> : app.detectedAs ? <span className={s.tag} dir="auto">{t("settings.games.detectedAs", { name: app.detectedAs })}</span> : null}
                  </button>
                </li>
              ))}
            </ul>
          )}
        </>
      )}
    </Modal>
  );
}

function AppIcon({ app }: { app: RunningApp }) {
  if (app.icon) return <img className={s.icon} src={app.icon} alt="" draggable={false} />;
  return <span className={s.icon} aria-hidden="true"><AppWindow size={18} /></span>;
}

/** A game already added: what it is called, and which game it is. */
function EditGameDialog({ game, onClose }: { game: CustomGame; onClose: () => void }) {
  const api = desktopBridge()?.games;
  const form = useGameForm(game.name, game.gameId ? { id: game.gameId, name: game.name } : null);
  if (!api) return null;
  const save = () => void form.save(async (name, gameId) => useDesktop.getState().setGames(await api.update(game.id, name, gameId)), onClose);
  return (
    <Modal open onClose={onClose} title={t("settings.games.editTitle")} width={560} footer={<FormFooter form={form} label={t("settings.games.save")} onCancel={onClose} onSave={save} />}>
      <GameFields form={form} exe={game.exe} onSubmit={save} />
    </Modal>
  );
}

function FormFooter({ form, label, onCancel, onSave }: { form: GameForm; label: string; onCancel: () => void; onSave: () => void }) {
  return (
    <>
      <Button variant="ghost" onClick={onCancel}>{t("chat.editMessage.cancel")}</Button>
      <Button onClick={onSave} disabled={!form.name.trim()} loading={form.saving} data-testid="game-form-save">{label}</Button>
    </>
  );
}

/** The name, and box art to say which game it is. */
function GameFields({ form, exe, onSubmit }: { form: GameForm; exe: string; onSubmit: () => void }) {
  return (
    <>
      <Field label={t("settings.games.nameLabel")} hint={t("settings.games.nameHint", { exe })}>
        <TextInput
          value={form.name}
          maxLength={128}
          onChange={(e) => form.setName(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === "Enter") {
              e.preventDefault();
              onSubmit();
            }
          }}
          data-testid="game-form-name"
          autoFocus
          spellCheck={false}
          autoComplete="off"
        />
      </Field>
      <GameShelf form={form} />
    </>
  );
}
