import { personText } from "@/lib/personText";
import { PersonName } from "@/components/common/PersonName";
import { UserRound } from "lucide-react";
import { useState } from "react";
import { moderation as modApi } from "@/api/moderation";
import { Button } from "@/components/common/Button";
import { Modal } from "@/components/common/Modal";
import { describeError } from "@/lib/errors";
import { RANK_ICON, rankName, rankPowers, type GrantableRank, type StaffRank } from "@/lib/staffRank";
import { useUI } from "@/stores/ui";
import { t, Tr } from "@/i18n";
import r from "./RankModal.module.css";

/** The ranks from the top of the ladder down, then none. */
const LADDER: (GrantableRank | null)[] = ["developer", "admin", "mod", "helper", null];

/**
 * The owner's choice of someone's rank: the ladder of what can be given, each
 * rung with what it can do, the one they hold now marked. Only the owner sees
 * it; the server refuses anyone else.
 */
export function RankModal({ user, current, onClose, onDone }: { user: { id: string; display_name: string }; current: StaffRank | null; onClose: () => void; onDone: () => void }) {
  const toast = useUI((u) => u.toast);
  const [pick, setPick] = useState<GrantableRank | null>(current === "owner" ? null : current);
  const [busy, setBusy] = useState(false);
  const save = async () => {
    setBusy(true);
    try {
      await modApi.access(user.id, { rank: pick ?? "" });
      toast(personText("staff.rankModal.done", user.id, user.display_name, { rank: pick ? rankName(pick) : t("staff.rankModal.none") }), "success");
      onDone();
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
      title={<Tr id="staff.rankModal.title" values={{ name: <PersonName user={user}>{user.display_name}</PersonName> }} />}
      width={460}
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>{t("chat.editMessage.cancel")}</Button>
          <Button onClick={() => void save()} loading={busy} disabled={pick === (current === "owner" ? null : current)} data-testid="rank-save">
            {t("staff.rankModal.save")}
          </Button>
        </>
      }
    >
      <p className={r.lede}>{t("staff.rankModal.body")}</p>
      <div className={r.ladder} role="radiogroup" aria-label={t("staff.rankModal.title", { name: user.display_name })}>
        {LADDER.map((rank) => {
          const Icon = rank ? RANK_ICON[rank] : UserRound;
          const on = pick === rank;
          return (
            <label key={rank ?? "none"} className={r.rung} data-on={on || undefined} data-rank={rank ?? "none"} data-testid={`rank-${rank ?? "none"}`}>
              <input type="radio" name="rank" className={r.input} checked={on} onChange={() => setPick(rank)} />
              <span className={r.mark} aria-hidden="true"><Icon size={18} /></span>
              <span className={r.text}>
                <span className={r.name}>
                  {rank ? rankName(rank) : t("staff.rankModal.none")}
                  {(current === "owner" ? null : current) === rank ? <span className={r.now}>{t("staff.rankModal.current")}</span> : null}
                </span>
                <span className={r.powers}>{rank ? rankPowers(rank) : t("staff.rankModal.nonePowers")}</span>
              </span>
            </label>
          );
        })}
      </div>
    </Modal>
  );
}
