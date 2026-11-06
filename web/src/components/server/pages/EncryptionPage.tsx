import { PersonName } from "@/components/common/PersonName";
import { KeyRound, RefreshCw, ShieldCheck, TriangleAlert } from "lucide-react";
import { useEffect, useState } from "react";
import { servers as serversApi } from "@/api/servers";
import type { ServerKeyInfo } from "@/api/types";
import { Button } from "@/components/common/Button";
import { LoadError } from "@/components/common/LoadError";
import { Spinner } from "@/components/common/Spinner";
import { describeError } from "@/lib/errors";
import { formatFull } from "@/lib/time";
import { ring } from "@/lib/keyring";
import { P } from "@/lib/perms";
import { useKeys } from "@/stores/keys";
import { canI, useServers } from "@/stores/servers";
import { useUI } from "@/stores/ui";
import { displayName, useUsers } from "@/stores/users";
import st from "@/components/settings/Settings.module.css";
import s from "../ServerSettings.module.css";

import { t, Tr } from "@/i18n";
export default function EncryptionPage({ serverId }: { serverId: string }) {
  const users = useUsers((u) => u.byId);
  const toast = useUI((u) => u.toast);
  const rotate = useKeys((k) => k.rotate);
  const managesServer = canI(useServers((x) => x.byId[serverId]), P.MANAGE_SERVER);
  const reconcile = useKeys((k) => k.reconcile);
  const [info, setInfo] = useState<ServerKeyInfo[] | null>(null);
  // Without this a failed fetch rendered no rows and no message at all: a page
  // about encryption keys that quietly says there are none.
  const [failed, setFailed] = useState(false);
  const [busy, setBusy] = useState<string | null>(null);

  const load = () =>
    serversApi
      .keys(serverId)
      .then((list) => {
        setInfo(list);
        setFailed(false);
      })
      .catch(() => {
        setInfo(null);
        setFailed(true);
      });
  useEffect(() => {
    void load();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [serverId]);

  const doRotate = async (conv: string) => {
    setBusy(conv);
    try {
      await rotate(conv, "manual");
      toast(t("server.encryption.aNewKeyWasCreatedAnd"), "success");
      await load();
    } catch (err) {
      toast(describeError(err, t("server.encryption.couldNotRotateTheKey")), "error");
    } finally {
      setBusy(null);
    }
  };
  const doReconcile = async () => {
    setBusy("reconcile");
    try {
      await reconcile();
      toast(t("server.encryption.checkedEveryoneSKeys"), "success");
    } catch (err) {
      // Its sibling above catches; this one only had `finally`, so a rejected
      // reconcile cleared the spinner and said nothing whatsoever.
      toast(describeError(err, t("server.encryption.couldNotCheckEveryoneSKeys")), "error");
    } finally {
      setBusy(null);
    }
  };

  const needsRotation = info?.some((c) => c.flags?.needs_rotation) ?? false;
  return (
    <>
      <h2 className={st.title}>{t("server.encryption.encryption")}</h2>
      <p className={st.hint}>{t("server.encryption.everyMessageHereIsEndTo")}</p>
      <div style={{ height: 16 }} />
      {needsRotation ? (
        <div className={s.warn}>
          <TriangleAlert size={16} style={{ verticalAlign: -3, marginInlineEnd: 6 }} />
          {t("server.encryption.someoneLeftRotate")}
        </div>
      ) : null}
      {info === null && !failed ? <Spinner label={t("server.encryption.loadingKeys")} /> : null}
      {failed ? <LoadError message={t("server.encryption.theKeysForThisServerCould")} onRetry={() => void load()} /> : null}
      {info?.map((c) => {
        const latest = c.epochs.length ? c.epochs[c.epochs.length - 1]! : null;
        const held = ring.latest(c.conv_id);
        const holdLatest = !!held && !!latest && held.epoch === latest.epoch;
        return (
          <div key={c.conv_id} className={s.keyCard} data-conv={c.conv_id}>
            <KeyRound size={22} style={{ color: c.flags?.needs_rotation ? "var(--warning)" : "var(--positive)" }} />
            <div className={s.keyText}>
              <span className={s.keyName}>{c.name}</span>
              <span className={s.keySub}>
                {latest ? <><Tr id="server.encryption.keyEpochCreatedBy" values={{ epoch: latest.epoch, when: formatFull(latest.created_at), who: <PersonName userId={latest.created_by} serverId={serverId}>{displayName(users[latest.created_by])}</PersonName> }} />{latest.reason ? ` (${latest.reason})` : ""}</> : t("server.encryption.noKeyYet")}
                {" · "}
                {holdLatest ? t("server.encryption.youHoldTheCurrentKey") : held ? t("server.encryption.youHoldAnOlderKeyWaiting") : t("server.encryption.youDonTHoldThisKey")}
              </span>
            </div>
            {/* the server takes a new key by hand only from those who manage it; anyone can answer a pending replacement */}
            {managesServer || c.flags?.needs_rotation ? (
              <Button size="sm" variant="secondary" disabled={!holdLatest || busy === c.conv_id} loading={busy === c.conv_id} onClick={() => void doRotate(c.conv_id)} title={holdLatest ? t("server.encryption.createAFreshKey") : t("server.encryption.onlyHoldersCanRotate")}>
                <RefreshCw size={14} /> {t("server.encryption.rotate")}
              </Button>
            ) : null}
          </div>
        );
      })}
      <div className={st.sep} />
      <div className={s.keyCard}>
        <ShieldCheck size={22} style={{ color: "var(--positive)" }} />
        <div className={s.keyText}>
          <span className={s.keyName}>{t("server.encryption.shareMissingKeys")}</span>
          <span className={s.keySub}>{t("server.encryption.membersWhoJoinedWhileNobodyWith")}</span>
        </div>
        <Button size="sm" variant="secondary" loading={busy === "reconcile"} onClick={() => void doReconcile()}>{t("server.encryption.checkNow")}</Button>
      </div>
    </>
  );
}
