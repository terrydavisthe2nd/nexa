import { personText } from "@/lib/personText";
import { PersonName } from "@/components/common/PersonName";
import { useEffect, useState } from "react";
import { moderation as modApi } from "@/api/moderation";
import type { BadgeDef } from "@/api/types";
import { BadgePill } from "@/components/badges/Badges";
import { Button } from "@/components/common/Button";
import { LoadError } from "@/components/common/LoadError";
import { Modal } from "@/components/common/Modal";
import { Spinner } from "@/components/common/Spinner";
import { CONTRIBUTOR, EVENT_PREFIX } from "@/lib/badges";
import { describeError } from "@/lib/errors";
import { useUI } from "@/stores/ui";
import { t, Tr } from "@/i18n";
import r from "./RankModal.module.css";
import b from "./BadgesTab.module.css";
import { Check } from "lucide-react";

const NOBODY = { id: "0", flags: 0, plan: "" as const };

/**
 * The badges staff give someone by hand: Contributor and every event badge,
 * each ticked when they hold it. Saving gives and takes the difference, in
 * one line of the log.
 */
export function GrantBadgesModal({ user, onClose }: { user: { id: string; display_name: string }; onClose: () => void }) {
  const toast = useUI((u) => u.toast);
  const [defs, setDefs] = useState<BadgeDef[] | null>(null);
  const [held, setHeld] = useState<string[] | null>(null);
  const [pick, setPick] = useState<Set<string>>(new Set());
  const [failed, setFailed] = useState(false);
  const [busy, setBusy] = useState(false);

  const load = () => {
    setFailed(false);
    Promise.all([modApi.badges(), modApi.userBadges(user.id)])
      .then(([all, mine]) => {
        setDefs(all.badges);
        setHeld(mine.granted);
        setPick(new Set(mine.granted));
      })
      .catch(() => setFailed(true));
  };
  useEffect(load, [user.id]); // eslint-disable-line react-hooks/exhaustive-deps

  const options = [{ id: CONTRIBUTOR }, ...(defs ?? []).map((d) => ({ id: `${EVENT_PREFIX}${d.id}`, name: d.name, icon: d.icon, color: d.color }))];
  const changed = held !== null && (held.length !== pick.size || held.some((b) => !pick.has(b)));
  const flip = (id: string) =>
    setPick((cur) => {
      const next = new Set(cur);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  const save = async () => {
    setBusy(true);
    try {
      await modApi.setUserBadges(user.id, [...pick]);
      toast(personText("badges.staff.givenDone", user.id, user.display_name), "success");
      onClose();
    } catch (err) {
      toast(describeError(err, t("moderation.failed")), "error");
    } finally {
      setBusy(false);
    }
  };

  return (
    <Modal
      open
      onClose={onClose}
      title={<Tr id="badges.staff.giveTitle" values={{ name: <PersonName user={user}>{user.display_name}</PersonName> }} />}
      width={460}
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>{t("chat.editMessage.cancel")}</Button>
          <Button onClick={() => void save()} loading={busy} disabled={!changed} data-testid="badges-give-save">
            {t("badges.staff.save")}
          </Button>
        </>
      }
    >
      <p className={r.lede}>{t("badges.staff.giveBody")}</p>
      {failed ? (
        <LoadError message={t("moderation.loadFailed")} onRetry={load} />
      ) : held === null ? (
        <Spinner label={t("chat.pins.loading")} />
      ) : (
        <div className={r.ladder} role="group" aria-label={t("badges.staff.giveTitle", { name: user.display_name })}>
          {options.map((o) => {
            const on = pick.has(o.id);
            return (
              <label key={o.id} className={r.rung} data-on={on || undefined} data-testid={`give-${o.id}`}>
                <input type="checkbox" className={r.input} checked={on} onChange={() => flip(o.id)} />
                <span className={b.tick} data-on={on || undefined} aria-hidden="true">{on ? <Check size={14} strokeWidth={3} /> : null}</span>
                <span className={r.text}>
                  <span className={r.name}>
                    <BadgePill badge={o} user={NOBODY} still named />
                    {held.includes(o.id) ? <span className={r.now}>{t("badges.staff.held")}</span> : null}
                  </span>
                  <span className={r.powers}>{o.id === CONTRIBUTOR ? t("badges.contributorTip") : t("badges.eventTip")}</span>
                </span>
              </label>
            );
          })}
          {defs?.length === 0 ? <p className={r.powers}>{t("badges.staff.noneYet")}</p> : null}
        </div>
      )}
    </Modal>
  );
}
