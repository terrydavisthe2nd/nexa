import { useId, useMemo, useRef, useState, type FormEvent, type ReactNode } from "react";
import { Link } from "react-router-dom";
import { ApiError } from "@/api/client";
import { Button } from "@/components/common/Button";
import { Field, TextInput } from "@/components/common/TextInput";
import { describeError } from "@/lib/errors";
import { t } from "@/i18n";
import { passwordStrength, usernameProblem } from "./AuthLayout";
import s from "./Auth.module.css";

/**
 * The new account form, on the sign-up page and in Add Another Account. `create`
 * makes the account; what follows (the recovery key) is the caller's.
 */
export function NewAccountForm({ create, needsInvite, initialInvite = "", foot, autoFocus }: {
  create: (username: string, displayName: string, password: string, extra?: { registration_key: string; invite_code: string }) => Promise<void>;
  needsInvite: boolean;
  initialInvite?: string;
  foot: ReactNode;
  autoFocus?: boolean;
}) {
  const [username, setUsername] = useState("");
  const [displayName, setDisplayName] = useState("");
  const [password, setPassword] = useState("");
  const [confirm, setConfirm] = useState("");
  const [inviteKey, setInviteKey] = useState(initialInvite);
  const [busy, setBusy] = useState(false);
  const [progress, setProgress] = useState("");
  const [error, setError] = useState<string | null>(null);
  // what the server said about a name, shown on that name's field until it changes
  const [nameTaken, setNameTaken] = useState<string | null>(null);
  const [displayTaken, setDisplayTaken] = useState<string | null>(null);
  // arriving from an invite, the key is already filled in: show it rather than hide it
  const [inviteOpen] = useState(() => !!initialInvite);
  const usernameInput = useRef<HTMLInputElement>(null);
  const displayInput = useRef<HTMLInputElement>(null);
  const passwordInput = useRef<HTMLInputElement>(null);
  const confirmInput = useRef<HTMLInputElement>(null);
  const strengthId = useId();

  const uname = username.trim().toLowerCase();
  const uProblem = uname ? usernameProblem(uname) : null;
  const strength = useMemo(() => passwordStrength(password), [password]);
  const mismatch = !!confirm && confirm !== password;
  const ready = !uProblem && uname.length >= 2 && displayName.trim().length > 0 && password.length >= 8 && password === confirm;

  const onSubmit = async (e: FormEvent) => {
    e.preventDefault();
    if (busy) return;
    // The button stays pressable, so Enter always answers: the browser's own
    // check has already stopped an empty field or a short password, and what
    // it cannot check is already said on its field; this takes you there.
    if (!ready) {
      if (uProblem || uname.length < 2) usernameInput.current?.focus();
      else if (!displayName.trim()) displayInput.current?.focus();
      else if (password.length < 8) passwordInput.current?.focus();
      else confirmInput.current?.focus();
      return;
    }
    setBusy(true);
    setError(null);
    setProgress(t("register.securingYourAccountThisTakesA"));
    try {
      await create(uname, displayName.trim(), password, inviteKey ? { registration_key: inviteKey, invite_code: inviteKey } : undefined);
    } catch (err) {
      const code = err instanceof ApiError ? err.code : "";
      if (code === "username_taken" || code === "invalid_username" || code === "reserved_username") {
        setNameTaken(code === "username_taken" ? t("register.thatUsernameIsTaken") : code === "invalid_username" ? t("register.thatUsernameIsNotAllowed") : t("register.thatNameIsReserved"));
        usernameInput.current?.focus();
      } else if (code === "reserved_display_name") {
        setDisplayTaken(t("register.thatNameIsReserved"));
        displayInput.current?.focus();
      } else if (err instanceof ApiError) {
        const map: Record<string, string> = {
          registration_closed: t("register.thisServerIsNotAcceptingNew"),
          invite_required: t("register.youNeedAnInviteOrRegistration"),
          rate_limited: t("register.tooManySignUpsFromYour"),
          too_many_signups: t("register.twoAccountsADay"),
        };
        setError(map[code] ?? describeError(err, t("register.somethingWentWrongPleaseTryAgain")));
      } else {
        setError(t("register.somethingWentWrongPleaseTryAgain"));
      }
    } finally {
      setBusy(false);
      setProgress("");
    }
  };

  return (
  <form className={s.form} onSubmit={(e) => void onSubmit(e)}>
    {error ? <div className={s.error} role="alert">{error}</div> : null}
    <Field label={t("settings.account.username")} required error={uProblem ?? nameTaken} hint={t("register.lowercaseLettersNumbersUnderscoresAndDots")}>
      <TextInput ref={usernameInput} value={username} onChange={(e) => { setUsername(e.target.value); setNameTaken(null); }} autoComplete="username" autoCapitalize="none" spellCheck={false} maxLength={32} required autoFocus={autoFocus} />
    </Field>
    <Field label={t("register.displayName")} required error={displayTaken} hint={t("register.howYourNameAppearsToOthers")}>
      <TextInput ref={displayInput} value={displayName} onChange={(e) => { setDisplayName(e.target.value); setDisplayTaken(null); }} autoComplete="nickname" maxLength={32} required />
    </Field>
    {/* The meter is always there, so typing the first letters does not
        push the rest of the form down, and it sits outside the field's
        label so the box's name is not remade on every key. */}
    <div className={s.group}>
      <Field label={t("chat.lockedChannel.password")} required>
        <TextInput ref={passwordInput} type="password" reveal value={password} onChange={(e) => setPassword(e.target.value)} autoComplete="new-password" minLength={8} aria-describedby={strengthId} required />
      </Field>
      <div className={s.strength} data-score={password ? strength.score : 0} id={strengthId}>
        {[1, 2, 3, 4].map((n) => <i key={n} data-on={!!password && strength.score >= n} />)}
        <span className={s.strengthLabel}>{password ? strength.label : t("settings.account.atLeast8Characters")}</span>
      </div>
    </div>
    <Field label={t("register.confirmPassword")} required error={mismatch ? t("settings.account.passwordsDoNotMatch") : null}>
      <TextInput ref={confirmInput} type="password" reveal value={confirm} onChange={(e) => setConfirm(e.target.value)} autoComplete="new-password" required />
    </Field>
    {needsInvite ? (
      // Nexa only takes people who were invited, so the field is part of the form
      <Field label={t("register.inviteRegistrationKey")} required hint={t("register.youNeedAnInviteOrRegistration")}>
        <TextInput value={inviteKey} onChange={(e) => setInviteKey(e.target.value)} autoCapitalize="none" spellCheck={false} required />
      </Field>
    ) : (
      <details className={s.invite} open={inviteOpen || undefined}>
        <summary className={s.foot}>{t("register.haveAnInviteOrRegistrationKey")}</summary>
        <Field label={t("register.inviteRegistrationKey")}>
          <TextInput value={inviteKey} onChange={(e) => setInviteKey(e.target.value)} autoCapitalize="none" spellCheck={false} />
        </Field>
      </details>
    )}
    <Button type="submit" size="lg" block loading={busy}>{t("settings.security.continue")}</Button>
    {/* under the button, so the button does not move while it is being pressed */}
    {progress ? <p className={s.progress} role="status">{progress}</p> : null}
    {/* English in every language (the legal.* strings are), so it lays itself out as English */}
    <div className={s.foot} dir="auto">
      {t("legal.gatePrivacy")}{" "}
      <Link className={s.nowrap} to="/terms" target="_blank" rel="noreferrer noopener" data-new-tab="">{t("legal.termsTitle")}</Link>
      {" · "}
      <Link className={s.nowrap} to="/privacy" target="_blank" rel="noreferrer noopener" data-new-tab="">{t("legal.privacyTitle")}</Link>
    </div>
    {foot}
  </form>
  );
}
