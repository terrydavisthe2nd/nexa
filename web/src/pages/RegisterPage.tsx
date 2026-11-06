import { useEffect, useRef, useState } from "react";
import { admin as adminApi } from "@/api/admin";
import { Link, Navigate, useLocation, useNavigate } from "react-router-dom";
import { safeNext } from "@/lib/nextPath";
import { useAuth } from "@/stores/auth";
import { AuthLayout, RecoveryKeyPanel, usePageTitle } from "./AuthLayout";
import { NewAccountForm } from "./NewAccountForm";
import s from "./Auth.module.css";

import { t } from "@/i18n";
export default function RegisterPage() {
  const nav = useNavigate();
  const register = useAuth((a) => a.register);
  const status = useAuth((a) => a.status);
  const justRegistered = useRef(false);
  const loc = useLocation();
  const params = new URLSearchParams(loc.search);
  const next = params.get("next") || "/channels/@me";
  const [recovery, setRecovery] = useState<{ key: string; username: string } | null>(null);
  // Whether Nexa takes sign-ups at all, asked before the form is
  // filled in: it used to be learned after a whole form and a key derivation,
  // with the field it needed folded away under "Have an invite?".
  const [policy, setPolicy] = useState<"open" | "invite" | "closed" | null>(null);
  useEffect(() => {
    let alive = true;
    adminApi.instance().then(
      (info) => alive && setPolicy(info.registration),
      () => undefined,
    );
    return () => {
      alive = false;
    };
  }, []);
  const needsInvite = policy === "invite";
  usePageTitle(recovery ? t("register.saveYourRecoveryKey") : t("register.createAnAccount"));

  const create = async (username: string, displayName: string, password: string, extra?: { registration_key: string; invite_code: string }) => {
    justRegistered.current = true;
    try {
      const res = await register(username, displayName, password, extra);
      setRecovery({ key: res.recoveryKey, username });
    } catch (err) {
      justRegistered.current = false;
      throw err;
    }
  };

  // Someone already logged in who lands here goes to the app; a fresh registration stays to show the recovery key.
  if ((status === "ready" || status === "locked") && !recovery && !justRegistered.current) {
    return <Navigate to="/channels/@me" replace />;
  }

  if (policy === "closed" && !recovery) {
    return (
      <AuthLayout title={t("register.createAnAccount")} banner>
        <p className={s.note} role="status" style={{ textAlign: "center" }}>{t("register.thisServerIsNotAcceptingNew")}</p>
        <Link to="/login" className={s.center}>{t("register.alreadyHaveAnAccount")}</Link>
      </AuthLayout>
    );
  }

  if (recovery) {
    return (
      <AuthLayout title={t("register.saveYourRecoveryKey")} subtitle={t("register.yourMessagesAreEndToEnd")}>
        <RecoveryKeyPanel value={recovery.key} username={recovery.username} confirm={t("register.iHaveSavedMyRecoveryKey")} doneLabel={t("register.continueToNexa")} onDone={() => nav(safeNext(next), { replace: true })} />
      </AuthLayout>
    );
  }

  return (
    <AuthLayout title={t("register.createAnAccount")} banner>
      <NewAccountForm
        create={create}
        needsInvite={needsInvite}
        initialInvite={params.get("invite") ?? ""}
        foot={<div className={s.foot}><Link to="/login">{t("register.alreadyHaveAnAccount")}</Link></div>}
      />
    </AuthLayout>
  );
}
