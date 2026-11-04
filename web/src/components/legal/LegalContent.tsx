import { Database, Eye, Gavel, Lock, ScrollText, Sparkles } from "lucide-react";
import { CONTACT_EMAIL, GITHUB_URL, OWNER_USERNAME } from "@/lib/legal";
import { t } from "@/i18n";
import s from "./Legal.module.css";

/**
 * The pieces the consent dialog, Settings → About and the public pages share,
 * so what they say about the Terms can never drift apart. The documents
 * themselves are rendered whole by LegalDocument, from docs/legal.
 *
 * The legal.* strings are English in every language on purpose (lib/legal.ts),
 * so each block takes its direction from its own text (`dir="auto"`): inside
 * an Arabic page, English laid out right to left put every full stop at the
 * start of its line. A string that is ever translated still lays out right.
 */
export function ContactBlock() {
  return (
    <p className={s.body} dir="auto">
      {t("legal.contactHere", { user: OWNER_USERNAME })} <a href={`mailto:${CONTACT_EMAIL}`}>{CONTACT_EMAIL}</a>
      <br />
      {t("legal.inApp", { user: `@${OWNER_USERNAME}` })}
    </p>
  );
}

/** The same source and contribution invitation on the public and in-app About pages. */
export function OpenSourceBlock() {
  return (
    <section className={s.aboutGroup}>
      <h3 className={s.aboutLabel}>{t("project.openSource")}</h3>
      <p className={s.body}>{t("project.sourceBody")}</p>
      <p className={s.body}><strong>{t("project.contributions")}</strong><br />{t("project.contributeBody")}</p>
      <p className={s.body}><a href={GITHUB_URL} target="_blank" rel="noopener noreferrer">{t("project.viewGithub")}</a></p>
    </section>
  );
}

/** The Terms and the Privacy Policy at a glance: what matters most, in five cards. */
export function LegalHighlights() {
  const cards = [
    { icon: <Lock size={16} />, title: t("legal.hlPrivateTitle"), body: t("legal.hlPrivateBody") },
    { icon: <Eye size={16} />, title: t("legal.hlStaffTitle"), body: t("legal.hlStaffBody") },
    { icon: <Gavel size={16} />, title: t("legal.hlModerationTitle"), body: t("legal.hlModerationBody") },
    { icon: <ScrollText size={16} />, title: t("legal.hlRulesTitle"), body: t("legal.hlRulesBody") },
    { icon: <Database size={16} />, title: t("legal.hlDataTitle"), body: t("legal.hlDataBody") },
  ];
  return (
    <div className={s.highlights}>
      {cards.map((c) => (
        <div key={c.title} className={s.highlight} dir="auto">
          <span className={s.hlIcon} aria-hidden="true">
            {c.icon}
          </span>
          <span className={s.hlText}>
            <span className={s.hlTitle}>{c.title}</span>
            <span className={s.hlBody}>{c.body}</span>
          </span>
        </div>
      ))}
    </div>
  );
}

/**
 * What changed since the version `previous` (what this account agreed to
 * before): what this version changed, then what each older version
 * brought, for an agreement from before it.
 */
export function LegalChanges({ previous }: { previous: string }) {
  const changes = [t("legal.newFileStorage")];
  if (previous < "2026-11-02.1") changes.push(t("legal.newReportOutcomes"));
  if (previous < "2026-11-01.1") changes.push(t("legal.newTurnstile"));
  if (previous < "2026-10-31.1") changes.push(t("legal.newCallUsage"));
  if (previous < "2026-10-28.1") changes.push(t("legal.newNSFW"));
  if (previous < "2026-10-26.1") changes.push(t("legal.newPlans"));
  if (previous < "2026-10-25.1") changes.push(t("legal.newPasskeys"));
  if (previous < "2026-10-20.2") changes.push(t("legal.newAddresses"));
  if (previous < "2026-10-20.1") changes.push(t("legal.newCaptcha"));
  if (previous < "2026-10-19") changes.push(t("legal.new1"), t("legal.new2"), t("legal.new3"), t("legal.new4"), t("legal.new5"));
  return (
    <div className={s.news} data-testid="legal-changes" dir="auto">
      <p className={s.newsTitle}>
        <Sparkles size={14} aria-hidden="true" />
        {t("legal.newTitle")}
      </p>
      <ul className={s.list}>
        {changes.map((c) => (
          <li key={c}>{c}</li>
        ))}
      </ul>
    </div>
  );
}
