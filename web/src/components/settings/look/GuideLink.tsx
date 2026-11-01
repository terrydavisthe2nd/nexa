import { BookOpen, ExternalLink } from "lucide-react";
import { t } from "@/i18n";
import button from "@/components/common/Button.module.css";
import s from "./Look.module.css";

/**
 * The way to a written guide, under the warning on Plugins and Custom CSS:
 * what it covers, and a link that opens it on GitHub in a new tab.
 */
export function GuideLink({ title, body, href, testId }: { title: string; body: string; href: string; testId?: string }) {
  return (
    <div className={s.guide} data-testid={testId}>
      <span className={s.guideMark} aria-hidden="true">
        <BookOpen size={18} />
      </span>
      <div className={s.guideText}>
        <strong className={s.guideTitle}>{title}</strong>
        <span className={s.guideBody}>{body}</span>
      </div>
      <a className={`${button.btn} ${button.secondary} ${button.sm} ${s.guideOpen}`} href={href} target="_blank" rel="noopener noreferrer">
        <span className={button.label}>
          {t("look.guide.open")} <ExternalLink size={14} aria-hidden="true" />
        </span>
      </a>
    </div>
  );
}
