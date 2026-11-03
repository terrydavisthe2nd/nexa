import { Copy, Trash2 } from "lucide-react";
import { useEffect, useId, useState, type FormEvent } from "react";
import { servers as serversApi, type VanityLink as Vanity } from "@/api/servers";
import { ApiError } from "@/api/client";
import { Button } from "@/components/common/Button";
import { LoadError } from "@/components/common/LoadError";
import { copyAndSay } from "@/lib/copyText";
import { describeError } from "@/lib/errors";
import { useUI } from "@/stores/ui";
import { t } from "@/i18n";
import st from "@/components/settings/Settings.module.css";
import s from "../ServerSettings.module.css";

/** What the server checks too: 3 to 32 of a-z, 0-9 and hyphens, a letter or number at each end. */
const VALID = /^[a-z0-9][a-z0-9-]{1,30}[a-z0-9]$/;

/** Typing towards a valid code: lowercase, spaces become hyphens, anything else is dropped. */
export function vanityDraft(raw: string): string {
  return raw.toLowerCase().replace(/\s+/g, "-").replace(/[^a-z0-9-]/g, "").slice(0, 32);
}

function vanityError(err: unknown): string {
  if (err instanceof ApiError) {
    if (err.code === "invalid_vanity") return t("server.invites.vanityInvalid");
    if (err.code === "vanity_taken") return t("server.invites.vanityTaken");
    if (err.code === "vanity_reserved") return t("server.invites.vanityReserved");
    if (err.code === "no_channel") return t("server.invites.vanityNoChannel");
  }
  return describeError(err, t("server.invites.vanityFailed"));
}

/**
 * Server Settings, Invites: the server's vanity link, one address it chooses
 * that never expires or runs out. The field reads as the whole link, the
 * instance's address and /invite/ quiet before the code being typed.
 */
export function VanityLink({ serverId }: { serverId: string }) {
  const toast = useUI((u) => u.toast);
  const openModal = useUI((u) => u.openModal);
  const [link, setLink] = useState<Vanity | null>(null);
  const [failed, setFailed] = useState(false);
  const [attempt, setAttempt] = useState(0);
  const [draft, setDraft] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [saving, setSaving] = useState(false);
  const errorId = useId();

  useEffect(() => {
    let alive = true;
    serversApi
      .vanity(serverId)
      .then((l) => {
        if (!alive) return;
        setLink(l);
        setDraft(l.code ?? "");
        setFailed(false);
      })
      .catch(() => alive && setFailed(true));
    return () => {
      alive = false;
    };
  }, [serverId, attempt]);

  const address = (code: string) => `${window.location.origin}/invite/${code}`;
  const current = link?.code ?? null;
  const changed = draft !== (current ?? "");

  const save = async (e: FormEvent) => {
    e.preventDefault();
    if (!changed || saving) return;
    if (!VALID.test(draft)) {
      setError(t("server.invites.vanityInvalid"));
      return;
    }
    setSaving(true);
    try {
      const next = await serversApi.setVanity(serverId, draft);
      setLink(next);
      setDraft(next.code ?? "");
      setError(null);
      toast(t("server.invites.vanitySaved"), "success");
    } catch (err) {
      setError(vanityError(err));
    } finally {
      setSaving(false);
    }
  };

  const remove = () => {
    if (!current) return;
    openModal("confirm", {
      title: t("server.invites.vanityRemoveTitle"),
      body: t("server.invites.vanityRemoveBody", { link: address(current) }),
      confirmLabel: t("server.invites.vanityRemove"),
      danger: true,
      onConfirm: async () => {
        try {
          const next = await serversApi.setVanity(serverId, null);
          setLink(next);
          setDraft("");
          setError(null);
          toast(t("server.invites.vanityRemoved"), "success");
        } catch (err) {
          toast(vanityError(err), "error");
        }
      },
    });
  };

  return (
    <section aria-labelledby={`${errorId}-title`} data-testid="vanity-link">
      <h3 className={st.h5} id={`${errorId}-title`}>{t("server.invites.vanityTitle")}</h3>
      <p className={st.hint}>{t("server.invites.vanityHint")}</p>
      {failed ? (
        <LoadError message={t("server.invites.vanityLoadFailed")} onRetry={() => setAttempt((n) => n + 1)} />
      ) : (
        <>
          <form className={s.vanity} onSubmit={(e) => void save(e)}>
            <label className={s.vanityField} data-invalid={error ? true : undefined} dir="ltr">
              <span className={s.vanityHost} aria-hidden="true">
                <span dir="ltr">{window.location.host}/invite/</span>
              </span>
              <input
                className={s.vanityInput}
                value={draft}
                disabled={!link}
                maxLength={32}
                spellCheck={false}
                autoCapitalize="off"
                autoComplete="off"
                autoCorrect="off"
                enterKeyHint="done"
                placeholder={t("server.invites.vanityPlaceholder")}
                aria-label={t("server.invites.vanityTitle")}
                aria-invalid={error ? true : undefined}
                aria-describedby={error ? errorId : undefined}
                onChange={(e) => {
                  setDraft(vanityDraft(e.target.value));
                  setError(null);
                }}
                data-testid="vanity-input"
              />
            </label>
            <Button type="submit" disabled={!changed || !draft} loading={saving} data-testid="vanity-save">{t("server.invites.vanitySave")}</Button>
          </form>
          {error ? <p className={s.vanityError} id={errorId} role="alert">{error}</p> : null}
          {current ? (
            <div className={s.vanityFoot}>
              <span className={s.vanityUses}>{t("server.invites.vanityJoins", { count: link?.uses ?? 0 })}</span>
              <div className={s.joinBtns}>
                <Button size="sm" variant="secondary" onClick={() => copyAndSay(address(current), t("server.invites.linkCopied"))} data-testid="vanity-copy">
                  <Copy size={14} aria-hidden="true" /> {t("server.invites.copyLink")}
                </Button>
                <Button size="sm" variant="ghost" onClick={remove} data-testid="vanity-remove">
                  <Trash2 size={14} aria-hidden="true" /> {t("server.invites.vanityRemove")}
                </Button>
              </div>
            </div>
          ) : null}
        </>
      )}
    </section>
  );
}
