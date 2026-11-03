import { useMemo, useState, useSyncExternalStore } from "react";
import { useNavigate } from "react-router-dom";
import { Gem, HandCoins, Heart, HardDrive, MonitorUp, Sparkles, Upload } from "lucide-react";
import { Button } from "@/components/common/Button";
import { Modal } from "@/components/common/Modal";
import { idTime } from "@/lib/ids";
import { useInterrupt } from "@/lib/interrupt";
import { onTermsAccepted, termsAccepted } from "@/lib/legal";
import { limitsOf, plansInfo, PWYW_MIN } from "@/lib/plans";
import { formatBytes } from "@/lib/time";
import { RELEASES, releaseStart, unseenReleases } from "@/lib/whatsNew";
import { useAuth } from "@/stores/auth";
import { useBilling } from "@/stores/billing";
import { useSettings } from "@/stores/settings";
import { useUI } from "@/stores/ui";
import { useUpsell, type UpsellReason } from "@/stores/upsell";
import { t } from "@/i18n";
import s from "./Upsell.module.css";

/** Goes to the plans from wherever the app is, closing the dialogs in the way. */
function useSeePlans(): () => void {
  const nav = useNavigate();
  return () => {
    useUI.getState().closeAllModals();
    nav("/channels/@me/support");
    useUI.getState().setLeftOpen(false);
  };
}

function texts(r: UpsellReason): { title: string; body: string } {
  const free = limitsOf("");
  const sup = limitsOf("supporter");
  const pro = limitsOf("pro");
  switch (r.kind) {
    case "file":
      return {
        title: t("support.upsell.fileTitle"),
        body: t("support.upsell.fileBody", { name: r.name, size: formatBytes(r.size), free: formatBytes(free.file_max), supporter: formatBytes(sup.file_max), pro: formatBytes(pro.file_max) }),
      };
    case "storage":
      return { title: t("support.upsell.storageTitle"), body: t("support.upsell.storageBody", { free: formatBytes(free.month_upload_max), supporter: formatBytes(sup.month_upload_max), pro: formatBytes(pro.month_upload_max) }) };
    case "share":
      return { title: t("support.upsell.shareTitle"), body: t("support.upsell.shareBody") };
    case "effect":
      return { title: t("support.upsell.effectTitle"), body: t("support.upsell.effectBody") };
  }
}

/** The line both offers give pay what you want, at Stripe's month prices once the page has them, else the list prices. */
export function pwywLine(): string {
  const usd = (c: number) => new Intl.NumberFormat(undefined, { style: "currency", currency: "USD", minimumFractionDigits: c % 100 ? 2 : 0 }).format(c / 100);
  const prices = useBilling.getState().billing?.gift_prices;
  const month = (p: "supporter" | "pro", list: number) => {
    const got = prices?.[p]?.month;
    return got && got.currency.toLowerCase() === "usd" && got.amount > 0 ? got.amount : list;
  };
  return t("support.pwywShort", { min: usd(PWYW_MIN), supporter: usd(month("supporter", 500)), pro: usd(month("pro", 1000)) });
}

const ICONS = { file: Upload, storage: HardDrive, share: MonitorUp, effect: Sparkles } as const;

/** Draws the offer while there is one. Mounted once, in the app shell. */
export function UpsellHost() {
  const reason = useUpsell((u) => u.reason);
  const seePlans = useSeePlans();
  if (!reason) return null;
  const close = () => useUpsell.setState({ reason: null });
  const { title, body } = texts(reason);
  const Icon = ICONS[reason.kind];
  return (
    <Modal
      open
      centered
      onClose={close}
      title={title}
      width={420}
      footer={
        <div className={s.foot}>
          <Button variant="secondary" onClick={close}>{t("support.upsell.notNow")}</Button>
          <Button className={s.cta} onClick={() => { close(); seePlans(); }} data-testid="upsell-see-plans">{t("support.upsell.seePlans")}</Button>
        </div>
      }
    >
      <div className={s.offer} data-testid="upsell" data-reason={reason.kind}>
        <span className={s.glyph} aria-hidden="true"><Icon size={22} /></span>
        <div className={s.words}>
          <p className={s.body}>{body}</p>
          <p className={s.aside}>{pwywLine()}</p>
        </div>
      </div>
    </Modal>
  );
}

/**
 * Once each update, to an account without a plan: what running Nexa costs
 * and what a plan brings, with a small way to say no. It waits for What's
 * New, opens only when nothing else is (lib/interrupt), only while plans can
 * be bought, and only for an account that was there before the update.
 */
export function SupportPrompt() {
  const me = useAuth((a) => a.me);
  const loaded = useSettings((st) => st.loaded);
  const seen = useSettings((st) => st.settings.supportPromptSeen);
  const whatsNewSeen = useSettings((st) => st.settings.whatsNewSeen);
  const agreed = useSyncExternalStore(onTermsAccepted, () => !!me && termsAccepted(me.id));
  const newest = RELEASES[0]?.id ?? null;
  const due = useMemo(() => {
    if (!me || !loaded || !agreed || !newest || (me.plan ?? "") !== "" || !plansInfo().enabled) return false;
    if (seen === newest || idTime(me.id) >= releaseStart(newest)) return false;
    // What's New goes first
    return unseenReleases(whatsNewSeen, idTime(me.id)).length === 0;
  }, [me, loaded, agreed, newest, seen, whatsNewSeen]);
  const [shown, setShown] = useState(false);
  const open = useInterrupt("supportPrompt", due || shown);
  const seePlans = useSeePlans();
  if (open && !shown && due) setShown(true);
  if (!open || !shown) return null;
  const close = () => {
    useSettings.getState().patch({ supportPromptSeen: newest });
    setShown(false);
  };
  const sup = limitsOf("supporter");
  const pro = limitsOf("pro");
  const perks = [
    { icon: Upload, text: t("support.prompt.perkFiles", { supporter: formatBytes(sup.file_max), pro: formatBytes(pro.file_max) }) },
    { icon: HardDrive, text: t("support.prompt.perkStorage", { supporter: formatBytes(sup.month_upload_max), pro: formatBytes(pro.month_upload_max) }) },
    { icon: MonitorUp, text: t("support.prompt.perkShare") },
    { icon: Sparkles, text: t("support.prompt.perkEffects") },
    { icon: Gem, text: t("support.prompt.perkBoosts", { size: formatBytes(plansInfo().boost_file_max) }) },
  ];
  return (
    <Modal open centered onClose={close} ariaLabel={t("support.prompt.title")} width={440} bare className={s.promptCard}>
      <div className={s.prompt} data-testid="support-prompt">
        <span className={s.heart} aria-hidden="true"><Heart size={28} fill="currentColor" /></span>
        <h2 className={s.title}>{t("support.prompt.title")}</h2>
        <p className={s.lede}>{t("support.prompt.lede")}</p>
        <ul className={s.perks}>
          {perks.map(({ icon: Icon, text }) => (
            <li key={text}>
              <Icon size={18} aria-hidden="true" />
              <span>{text}</span>
            </li>
          ))}
        </ul>
        <p className={s.pwyw}><HandCoins size={18} aria-hidden="true" /><span>{pwywLine()}</span></p>
        <Button block className={s.cta} onClick={() => { close(); seePlans(); }} data-testid="support-prompt-plans">{t("support.prompt.cta")}</Button>
        <p className={s.refund}>{t("support.refunds.line")}</p>
        <button className={s.noThanks} onClick={close} data-testid="support-prompt-no">{t("support.prompt.noThanks")}</button>
      </div>
    </Modal>
  );
}
