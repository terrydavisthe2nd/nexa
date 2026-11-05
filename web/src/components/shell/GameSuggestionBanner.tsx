import { Gamepad2 } from "lucide-react";
import { desktopBridge } from "@/lib/desktopBridge";
import { useDesktop } from "@/stores/desktop";
import { t } from "@/i18n";
import s from "./DownloadBanner.module.css";

/**
 * Asks about a program that fills the screen or works the graphics card and has
 * a game's name (desktop games/candidates.ts). It waits here rather than in a
 * notification, which would interrupt the very game it is asking about. Nothing
 * about the program has left the computer until Show It is pressed, so there is
 * no cover to show yet: fetching one would be telling the server the name.
 */
export function GameSuggestionBanner() {
  const asked = useDesktop((x) => x.games?.suggestions?.[0]);
  const api = desktopBridge()?.games;
  if (!asked || !api?.answer) return null;
  const answer = (isGame: boolean) => void api.answer(asked.id, isGame).then((g) => useDesktop.getState().setGames(g));
  return (
    <div className={s.banner} role="region" aria-label={t("settings.games.suggested")} data-testid="game-suggestion-banner">
      <span className={s.icon}><Gamepad2 size={18} aria-hidden="true" /></span>
      <span className={s.text}>
        <b dir="auto">{t("settings.games.askTitle", { name: asked.name })}</b>
        <span className={s.tagline}>{t("settings.games.askShort")}</span>
      </span>
      <button type="button" className={s.answer} onClick={() => answer(false)} data-testid="game-suggestion-no">{t("settings.games.notAGame")}</button>
      <button type="button" className={s.download} onClick={() => answer(true)} data-testid="game-suggestion-yes">{t("settings.games.showIt")}</button>
    </div>
  );
}
