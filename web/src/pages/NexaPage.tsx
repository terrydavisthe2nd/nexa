import { Info, LockOpen, Megaphone } from "lucide-react";
import { Fragment, useEffect, useLayoutEffect, useRef, type ReactNode } from "react";
import { useNavigate } from "react-router-dom";
import type { FeedItem, People } from "@/api/moderation";
import { Button } from "@/components/common/Button";
import { Spinner } from "@/components/common/Spinner";
import { NexaAvatar, OfficialBadge, PersonChip } from "@/components/nexa/NexaBits";
import { NoticeCard } from "@/components/nexa/NoticeCard";
import { BugFeedEntry, bugFeedKind, isBugItem, type BugItem } from "@/components/bugs/BugFeed";
import { ReportBugButton } from "@/components/bugs/ReportBugButton";
import { ReportFeedEntry, isReportItem, type ReportItem } from "@/components/reports/ReportFeed";
import { PageHeaderButtons } from "@/components/shell/PageHeader";
import { useRenderCtx } from "@/lib/markdownCtx";
import { fullDate } from "@/lib/moderation";
import { formatDateDivider, formatTimestamp, sameDay } from "@/lib/time";
import { Markdown } from "@/markdown";
import { useNexa } from "@/stores/nexa";
import { useUI } from "@/stores/ui";
import { t } from "@/i18n";
import s from "@/components/nexa/Nexa.module.css";
import chat from "@/components/chat/Chat.module.css";

/**
 * The Nexa conversation: what the moderation team has sent this account —
 * notices about actions on it, lifted mutes and bans, and announcements to
 * everyone. Nobody replies here; each notice opens onto its own case thread.
 */
export default function NexaPage() {
  const nav = useNavigate();
  const feed = useNexa((n) => n.feed);
  const more = useNexa((n) => n.feedMore);
  const loading = useNexa((n) => n.feedLoading);
  const people = useNexa((n) => n.people);
  const unread = useNexa((n) => n.state?.unread ?? 0);
  const focused = useUI((u) => u.focused);
  const scroller = useRef<HTMLDivElement>(null);
  const atBottom = useRef(true);
  const readHere = useRef(false);

  useEffect(() => {
    if (feed === null) void useNexa.getState().loadFeed().catch(() => undefined);
  }, [feed]);
  // Looking at the conversation is reading it, the threads it counts included,
  // as it is for any conversation: only while it is in front of someone. The
  // threads keep their counts on screen until the page is left.
  useEffect(() => {
    if (feed === null || unread === 0 || !focused) return;
    readHere.current = true;
    useNexa.getState().markSeen({ keepMarks: true });
  }, [feed, unread, focused]);
  useEffect(
    () => () => {
      if (readHere.current) useNexa.getState().clearMarks();
    },
    [],
  );

  const newest = feed?.[feed.length - 1];
  const newestKey = newest ? `${newest.kind}:${newest.at}` : "";
  useLayoutEffect(() => {
    const el = scroller.current;
    if (el && atBottom.current) el.scrollTop = el.scrollHeight;
  }, [newestKey]);

  const onScroll = () => {
    const el = scroller.current;
    if (!el) return;
    atBottom.current = el.scrollHeight - el.scrollTop - el.clientHeight < 64;
  };

  const loadOlder = async () => {
    const el = scroller.current;
    const before = el ? el.scrollHeight - el.scrollTop : 0;
    await useNexa.getState().loadOlder();
    // keep the reader where they were rather than jumping to the new top
    requestAnimationFrame(() => {
      if (el) el.scrollTop = el.scrollHeight - before;
    });
  };

  return (
    <div className={s.page} data-testid="nexa-page">
      <header className={s.header}>
        <PageHeaderButtons />
        <NexaAvatar size={24} />
        <span className={s.headerText}>
          <span className={s.headerLine}>
            <span className={s.headerName}>Nexa</span>
            <OfficialBadge />
          </span>
          <span className={s.headerSub}>{t("nexa.headerSub")}</span>
        </span>
        <span className={s.spacer} />
        <ReportBugButton from="nexa" labelled />
      </header>
      <div className={s.feed} ref={scroller} onScroll={onScroll}>
        {more ? (
          <div className={s.more}>
            <Button size="sm" variant="ghost" loading={loading} onClick={() => void loadOlder()}>
              {t("nexa.loadOlder")}
            </Button>
          </div>
        ) : (
          <div className={s.intro}>
            <NexaAvatar size={80} />
            <h1 className={s.introTitle}>
              Nexa <OfficialBadge />
            </h1>
            <p className={s.introText}>{t("nexa.intro")}</p>
          </div>
        )}
        {feed === null ? (
          <div className={s.more}>
            <Spinner label={t("chat.pins.loading")} />
          </div>
        ) : feed.length === 0 ? (
          <p className={s.empty}>{t("nexa.empty")}</p>
        ) : (
          feed.map((it, i) => {
            const prev = feed[i - 1];
            // a line with the date wherever a new day starts, as in any conversation
            const day = !prev || !sameDay(prev.at, it.at) ? (
              <div className={chat.dateDivider} role="separator" data-testid="nexa-date">
                <span>{formatDateDivider(it.at)}</span>
              </div>
            ) : null;
            if (isReportItem(it))
              return (
                <Fragment key={`${it.kind}${it.report.id}`}>
                  {day}
                  <FeedFrame kind={it.kind} kindLabel={t(it.kind === "report" ? "reports.feed.kindReport" : "reports.feed.kindOutcome")} at={it.at}>
                    <ReportFeedEntry item={it} />
                  </FeedFrame>
                </Fragment>
              );
            const rest = it as Exclude<FeedItem, ReportItem>;
            return isBugItem(rest) ? (
              <Fragment key={rest.kind === "bug" ? `bug${rest.bug.id}` : `bugline${rest.message.id}`}>
                {day}
                <FeedFrame kind={rest.kind} kindLabel={bugFeedKind(rest)} at={rest.at}>
                  <BugFeedEntry item={rest} people={people} onOpen={(id) => nav(`/channels/@me/nexa/bugs/${id}`)} />
                </FeedFrame>
              </Fragment>
            ) : (
              <Fragment key={rest.kind === "announcement" ? `a${rest.announcement.id}` : `${rest.kind}${rest.case.id}`}>
                {day}
                <FeedEntry item={rest} people={people} onOpen={(id) => nav(`/channels/@me/nexa/cases/${id}`)} />
              </Fragment>
            );
          })
        )}
      </div>
      <p className={s.systemFooter} role="note">
        <Info size={18} aria-hidden="true" />
        {t("nexa.footer")}
      </p>
    </div>
  );
}

/** One item of the conversation from Nexa, with the kind of item it is and when it came. */
function FeedFrame({ kind, kindLabel, at, children }: { kind: string; kindLabel: string; at: number; children: ReactNode }) {
  return (
    <article className={s.feedItem} data-kind={kind} data-testid={`nexa-item-${kind}`}>
      <NexaAvatar size={40} />
      <div className={s.feedBody}>
        <div className={s.feedHead}>
          <span className={s.lineName}>Nexa</span>
          <OfficialBadge compact />
          <span className={s.feedKind}>{kindLabel}</span>
          <time className={s.lineTime} dateTime={new Date(at).toISOString()} title={fullDate(at)}>
            {formatTimestamp(at)}
          </time>
        </div>
        {children}
      </div>
    </article>
  );
}

function FeedEntry({ item, people, onOpen }: { item: Exclude<FeedItem, BugItem | ReportItem>; people: People; onOpen: (caseId: string) => void }) {
  const ctx = useRenderCtx();
  const kind = item.kind === "announcement" ? t("nexa.kind.announcement") : item.kind === "lift" ? t("nexa.kind.update") : t("nexa.kind.notice");
  return (
    <article className={s.feedItem} data-kind={item.kind} data-testid={`nexa-item-${item.kind}`}>
      <NexaAvatar size={40} />
      <div className={s.feedBody}>
        <div className={s.feedHead}>
          <span className={s.lineName}>Nexa</span>
          <OfficialBadge compact />
          <span className={s.feedKind}>{kind}</span>
          <time className={s.lineTime} dateTime={new Date(item.at).toISOString()} title={fullDate(item.at)}>
            {formatTimestamp(item.at)}
          </time>
        </div>
        {item.kind === "notice" ? <NoticeCard c={item.case} people={people} perspective="member" onOpenThread={() => onOpen(item.case.id)} /> : null}
        {item.kind === "lift" ? (
          <div className={s.liftItem}>
            <LockOpen size={18} aria-hidden="true" />
            <span>{item.case.action === "ban" ? t("nexa.liftedBan", { number: item.case.number }) : t("nexa.liftedMute", { number: item.case.number })}</span>
            {item.case.lifted_by ? <PersonChip id={item.case.lifted_by} person={people[item.case.lifted_by]} size={16} /> : null}
            {item.case.lift_reason ? <span className={s.liftReason}>“{item.case.lift_reason}”</span> : null}
          </div>
        ) : null}
        {item.kind === "announcement" ? (
          <div className={s.announcement}>
            {item.announcement.title ? (
              <h3 className={s.announcementTitle}>
                <Megaphone size={18} aria-hidden="true" />
                <span>{item.announcement.title}</span>
              </h3>
            ) : null}
            <div className={s.announcementBody}>
              <Markdown source={item.announcement.body} ctx={ctx} />
            </div>
            <div className={s.announcementFoot}>
              {t("nexa.postedBy")} <PersonChip id={item.announcement.author_id} person={people[item.announcement.author_id]} size={18} withUsername />
            </div>
          </div>
        ) : null}
      </div>
    </article>
  );
}
