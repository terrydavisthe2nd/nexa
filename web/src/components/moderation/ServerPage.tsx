import { useReportNavigation } from "@/lib/reportNavigation";
import { can, useMyRank } from "@/lib/staffRank";
import { PersonName } from "@/components/common/PersonName";
import { useMediaAnimation } from "@/lib/mediaAnimation";
import { CalendarDays, ChevronLeft, ChevronRight, Compass, Copy, Eraser, Eye, FolderOpen, Gavel, Hash, ImageOff, Smile, Users } from "lucide-react";
import { useCallback, useEffect, useState, type ReactNode } from "react";
import { useParams } from "react-router-dom";
import { moderation as modApi, type ModPack, type ModServer, type People, type ServerField, type StaffCase } from "@/api/moderation";
import { ServerTagChip } from "@/components/servertag/ServerTagChip";
import type { Emoji } from "@/api/types";
import { bannerUrl } from "@/api/users";
import { Avatar } from "@/components/common/Avatar";
import { Button } from "@/components/common/Button";
import { Spinner } from "@/components/common/Spinner";
import { profileTheme } from "@/lib/avatarColor";
import { copyAndSay } from "@/lib/copyText";
import { describeError } from "@/lib/errors";
import { actionIcon, actionLabel, expressionSummary, fieldLabel, fullDate, personName, previewText, severity } from "@/lib/moderation";
import { formatRelative, formatShortDate } from "@/lib/time";
import { useModEvents, useModeration } from "@/stores/moderation";
import { useUI } from "@/stores/ui";
import { t } from "@/i18n";
import { ExpressionPic, PackRows } from "./ExpressionsTab";
import { ModerateEmojiModal } from "./ModerateEmojiModal";
import { ModerateServerModal } from "./ModerateServerModal";
import { ServerIcon } from "./ProfileSheet";
import { serverBlockedText } from "./ServersTab";
import m from "./Moderation.module.css";
import p from "./Sheet.module.css";
import x from "./Expressions.module.css";

/**
 * One server, for the staff: what it shows everyone from outside, with a way
 * to take down any part of it, who owns it, and the cases about it. What is
 * said inside it is never shown: the panel has no way to see it.
 */
export function ServerPage() {
  const mine = useMyRank();
  useMediaAnimation();
  const { serverId = "" } = useParams();
  const {nav,back,returnTo} = useReportNavigation();
  const mergePeople = useModeration((s) => s.mergePeople);
  const toast = useUI((u) => u.toast);
  const openModal = useUI((u) => u.openModal);
  const [server, setServer] = useState<ModServer | null>(null);
  const [cases, setCases] = useState<StaffCase[]>([]);
  const [people, setPeople] = useState<People>({});
  const [failed, setFailed] = useState(false);
  const [moderating, setModerating] = useState<ServerField[] | null>(null);
  const [hl, setHl] = useState<ServerField | null>(null);
  const [emojis, setEmojis] = useState<Emoji[]>([]);
  const [packs, setPacks] = useState<ModPack[]>([]);
  const [picked, setPicked] = useState<string[]>([]);
  const [takingEmoji, setTakingEmoji] = useState(false);

  const load = useCallback(async () => {
    try {
      const r = await modApi.server(serverId);
      setServer(r.server);
      setCases(r.cases);
      setPeople(r.people);
      setEmojis(r.emojis ?? []);
      setPacks(r.packs ?? []);
      setPicked((cur) => cur.filter((id) => (r.emojis ?? []).some((e) => e.id === id)));
      mergePeople(r.people);
    } catch {
      setFailed(true);
    }
  }, [serverId, mergePeople]);

  useEffect(() => {
    setServer(null);
    setFailed(false);
    void load();
  }, [load]);

  useModEvents((e) => {
    if (e.type === "case" && (e.case.action === "server_reset" || e.case.action === "expression_takedown") && e.case.details.server_id === serverId) void load();
  });

  if (failed) {
    return (
      <div className={m.empty}>
        <p>{t("moderation.servers.missing")}</p>
        <Button size="sm" variant="secondary" onClick={() => back("/channels/@admin/servers")}>
          {t("moderation.servers.back")}
        </Button>
      </div>
    );
  }
  if (!server) {
    return (
      <div className={m.loadingRow}>
        <Spinner label={t("chat.pins.loading")} />
      </div>
    );
  }
  const owner = people[server.owner_id];
  const blocked = serverBlockedText(server.blocked);
  // a server's own parts are an admin's to take down; its emoji a mod's
  const serverBlocked = blocked || (can(mine, "takeDownServers") ? "" : t("staff.adminOnlyServers"));
  const banner = bannerUrl(server.banner_id, 680);
  // a server the staff hid from Discover is shown again here; one its owner lists is hidden through the case dialog
  const showOnDiscover = (sv: ModServer) =>
    openModal("confirm", {
      title: t("moderation.servers.discoveryShowTitle", { name: sv.name }),
      body: t("moderation.servers.discoveryShowBody"),
      confirmLabel: t("moderation.servers.discoveryShow"),
      onConfirm: async () => {
        try {
          await modApi.showOnDiscover(sv.id);
          toast(t("moderation.servers.discoveryShown", { name: sv.name }), "success");
          void load();
        } catch (err) {
          toast(describeError(err, t("moderation.failed")), "error");
        }
      },
    });
  const rows: { field: ServerField; value: ReactNode; empty: boolean; action: string; icon: ReactNode; onAction?: () => void }[] = [
    { field: "icon", value: server.icon_id ? <ServerIcon id={server.id} iconId={server.icon_id} name={server.name} size={36} /> : t("moderation.sheet.notSet"), empty: !server.icon_id, action: t("moderation.sheet.remove"), icon: <ImageOff size={15} aria-hidden="true" /> },
    {
      field: "banner",
      value: server.banner_id ? <span className={p.bannerThumb} style={{ backgroundImage: `url(${bannerUrl(server.banner_id, 240)})` }} role="img" aria-label={fieldLabel("banner")} /> : t("moderation.sheet.notSet"),
      empty: !server.banner_id,
      action: t("moderation.sheet.remove"),
      icon: <ImageOff size={15} aria-hidden="true" />,
    },
    { field: "name", value: <bdi>{server.name}</bdi>, empty: false, action: t("moderation.sheet.reset"), icon: <Eraser size={15} aria-hidden="true" /> },
    {
      field: "description",
      value: server.description ? <span className={p.clip2} dir="auto">{previewText(server.description)}</span> : t("moderation.sheet.notSet"),
      empty: !server.description,
      action: t("moderation.sheet.clear"),
      icon: <Eraser size={15} aria-hidden="true" />,
    },
    {
      field: "tag",
      value: server.tag ? <ServerTagChip tag={{ server_id: server.id, text: server.tag.text, badge: server.tag.badge, color: server.tag.color ?? null }} open={false} /> : t("moderation.sheet.notSet"),
      empty: !server.tag,
      action: t("moderation.sheet.remove"),
      icon: <Eraser size={15} aria-hidden="true" />,
    },
    server.discovery_hidden
      ? { field: "discovery", value: t("moderation.servers.discoveryHeld"), empty: false, action: t("moderation.servers.discoveryShow"), icon: <Eye size={15} aria-hidden="true" />, onAction: () => showOnDiscover(server) }
      : {
          field: "discovery",
          value: server.discoverable ? t("moderation.servers.discoveryListed") : t("moderation.servers.discoveryNotListed"),
          empty: !server.discoverable,
          action: t("moderation.servers.discoveryHide"),
          icon: <Compass size={15} aria-hidden="true" />,
        },
  ];

  return (
    <>
      <div className={m.userHeader} data-testid="server-header">
        <button type="button" className={m.iconBtn} onClick={() => back("/channels/@admin/servers")} aria-label={t(returnTo ? "reports.returnToReport" : "moderation.servers.back")}>
          <ChevronLeft size={18} className={m.flip} />
        </button>
        <div className={m.userWho}>
          <ServerIcon id={server.id} iconId={server.icon_id} name={server.name} size={48} />
          <div className={m.userNames}>
            <span className={m.userName}>
              <span dir="auto">{server.name}</span>
            </span>
            <span className={m.userSub}>
              {t("moderation.servers.members", { count: server.member_count })} · {t("moderation.servers.channels", { count: server.channel_count })} · {t("moderation.casesCount", { count: cases.length })}
            </span>
          </div>
        </div>
        <div className={m.userActions}>
          <Button size="sm" onClick={() => setModerating([])} disabled={!!serverBlocked} title={serverBlocked || undefined} data-testid="server-moderate">
            <Gavel size={15} aria-hidden="true" /> {t("moderation.moderate")}
          </Button>
        </div>
      </div>

      <div className={p.scroll}>
        <div className={p.sheet} data-testid="server-sheet">
          <section className={p.face} aria-label={t("moderation.sheet.asSeen")}>
            <span className={p.faceCaption}>{t("moderation.sheet.asSeen")}</span>
            <div className={p.faceBanner} data-hl={hl === "banner" || undefined} style={banner ? { backgroundImage: `url(${banner})` } : { background: profileTheme(null, null, server.id) }} />
            <div className={p.faceBody}>
              <div className={p.faceAvatar} data-square="" data-hl={hl === "icon" || undefined}>
                <ServerIcon id={server.id} iconId={server.icon_id} name={server.name} size={84} />
              </div>
              <div className={p.faceNames}>
                <span className={p.faceName} data-hl={hl === "name" || undefined} dir="auto">{server.name}</span>
                <span className={p.faceHandle}>
                  {t("moderation.servers.members", { count: server.member_count })}
                </span>
              </div>
              <div className={p.faceSection} data-hl={hl === "description" || undefined}>
                <h4 className={p.faceLabel}>{t("moderation.servers.description")}</h4>
                {server.description ? <p className={p.faceText} dir="auto" style={{ whiteSpace: "pre-wrap" }}>{server.description}</p> : <p className={p.faceEmpty}>{t("moderation.servers.noDescription")}</p>}
              </div>
              <div className={p.faceSection}>
                <h4 className={p.faceLabel}>{t("moderation.servers.created")}</h4>
                <p className={p.faceText}>{formatShortDate(server.created_at)}</p>
              </div>
            </div>
          </section>

          <div className={p.column}>
            <section className={p.panel} aria-labelledby="server-public">
              <header className={p.panelHead}>
                <h3 id="server-public" className={p.panelTitle}>{t("moderation.sheet.publicTitle")}</h3>
                <p className={p.panelHint}>{serverBlocked || t("moderation.servers.publicHint")}</p>
              </header>
              <ul className={p.rows}>
                {rows.map((r) => (
                  <li key={r.field} className={p.row} data-empty={r.empty || undefined} onMouseEnter={() => setHl(r.field)} onMouseLeave={() => setHl(null)} onFocus={() => setHl(r.field)} onBlur={() => setHl(null)}>
                    <span className={p.rowLabel}>{fieldLabel(r.field)}</span>
                    <span className={p.rowValue}>{r.value}</span>
                    <Button size="sm" variant="secondary" className={p.rowAction} disabled={!!serverBlocked || r.empty} onClick={r.onAction ?? (() => setModerating([r.field]))} data-testid={`server-sheet-${r.field}`} aria-label={`${r.action}: ${fieldLabel(r.field)}`}>
                      {r.icon} {r.action}
                    </Button>
                  </li>
                ))}
              </ul>
            </section>

            <section className={p.panel} aria-labelledby="server-owner">
              <header className={p.panelHead}>
                <h3 id="server-owner" className={p.panelTitle}>{t("moderation.servers.ownerTitle")}</h3>
              </header>
              <button type="button" className={p.serverRow} onClick={() => nav(`/channels/@admin/users/${server.owner_id}`)} data-testid="server-owner">
                <Avatar id={server.owner_id} avatarId={owner?.avatar_id ?? null} name={personName(owner)} size={36} />
                <span className={p.serverText}>
                  <PersonName user={owner} className={p.serverName}>{personName(owner)}</PersonName>
                  <span className={p.serverMeta}>{owner ? `@${owner.username}` : ""}</span>
                </span>
                <ChevronRight size={18} className={p.flip} aria-hidden="true" />
              </button>
            </section>

            <section className={p.panel} aria-labelledby="server-emoji">
              <header className={p.panelHead}>
                <h3 id="server-emoji" className={p.panelTitle}>{t("moderation.packs.serverEmojiTitle", { count: emojis.length })}</h3>
                {emojis.length ? <p className={p.panelHint}>{blocked || t("moderation.packs.serverEmojiHint")}</p> : null}
              </header>
              {emojis.length ? (
                <>
                  <div className={x.pickGrid} data-kind="emoji" role="group" aria-label={t("moderation.packs.pickHint")}>
                    {emojis.map((e) => (
                      <label key={e.id} className={x.tile} data-picked={picked.includes(e.id)} data-disabled={!!blocked || undefined} data-testid="server-emoji">
                        <input type="checkbox" checked={picked.includes(e.id)} disabled={!!blocked} onChange={() => setPicked((cur) => (cur.includes(e.id) ? cur.filter((y) => y !== e.id) : [...cur, e.id]))} aria-label={`:${e.name}:`} />
                        <span className={x.tilePic}>
                          <ExpressionPic kind="emoji" blobId={e.blob_id} name={e.name} size={48} />
                        </span>
                        <span className={x.tileName} dir="auto">:{e.name}:</span>
                      </label>
                    ))}
                  </div>
                  <div className={x.pickBar} hidden={!picked.length} data-testid="emoji-pick-bar">
                    <span className={x.pickCount}>{t("moderation.packs.pickedCount", { count: picked.length })}</span>
                    <Button size="sm" variant="ghost" onClick={() => setPicked([])}>
                      {t("moderation.packs.clearPicked")}
                    </Button>
                    <span className={x.grow} />
                    <Button size="sm" variant="danger" onClick={() => setTakingEmoji(true)} data-testid="emoji-take-picked">
                      <Gavel size={15} aria-hidden="true" /> {t("moderation.packs.takeDownPicked")}
                    </Button>
                  </div>
                </>
              ) : (
                <p className={p.panelEmpty}>
                  <Smile size={16} aria-hidden="true" /> {t("moderation.packs.noServerEmoji")}
                </p>
              )}
            </section>

            {packs.length ? (
              <section className={p.panel} aria-labelledby="server-packs">
                <header className={p.panelHead}>
                  <h3 id="server-packs" className={p.panelTitle}>{t("moderation.packs.serverPacksTitle", { count: packs.length })}</h3>
                </header>
                <PackRows packs={packs} onOpen={(id) => nav(`/channels/@admin/expressions/packs/${id}`)} />
              </section>
            ) : null}

            <section className={p.panel} aria-labelledby="server-cases">
              <header className={p.panelHead}>
                <h3 id="server-cases" className={p.panelTitle}>{t("moderation.servers.casesTitle")}</h3>
              </header>
              {cases.length ? (
                <ul className={p.rows}>
                  {cases.map((c) => {
                    const Icon = actionIcon(c.action);
                    return (
                      <li key={c.id}>
                        <button type="button" className={p.serverRow} onClick={() => nav(`/channels/@admin/cases/${c.id}`)} data-testid="server-case">
                          <span className={m.caseIcon} data-tone={severity(c.action)} aria-hidden="true">
                            <Icon size={17} />
                          </span>
                          <span className={p.serverText}>
                            <span className={p.serverName}>{t("moderation.case.titled", { number: c.number, action: actionLabel(c.action) })}</span>
                            <span className={p.serverMeta}>{c.action === "expression_takedown" ? expressionSummary(c.details) : previewText(c.reason)}</span>
                          </span>
                          <span className={p.serverMeta} title={fullDate(c.created_at)}>{formatRelative(c.created_at)}</span>
                        </button>
                      </li>
                    );
                  })}
                </ul>
              ) : (
                <p className={p.panelEmpty}>
                  <FolderOpen size={16} aria-hidden="true" /> {t("moderation.servers.noCases")}
                </p>
              )}
            </section>

            <section className={p.panel} aria-labelledby="server-facts">
              <header className={p.panelHead}>
                <h3 id="server-facts" className={p.panelTitle}>{t("moderation.servers.factsTitle")}</h3>
              </header>
              <dl className={p.facts}>
                <div>
                  <dt><CalendarDays size={14} aria-hidden="true" /> {t("moderation.servers.created")}</dt>
                  <dd title={fullDate(server.created_at)}>{formatShortDate(server.created_at)}</dd>
                </div>
                <div>
                  <dt><Users size={14} aria-hidden="true" /> {t("moderation.servers.membersLabel")}</dt>
                  <dd>{server.member_count}</dd>
                </div>
                <div>
                  <dt><Hash size={14} aria-hidden="true" /> {t("moderation.servers.channelsLabel")}</dt>
                  <dd>{server.channel_count}</dd>
                </div>
                <div>
                  <dt>{t("moderation.servers.serverId")}</dt>
                  <dd>
                    <button type="button" className={p.copy} onClick={() => copyAndSay(server.id, t("moderation.copied"))} aria-label={t("moderation.servers.copyId")}>
                      <code>{server.id}</code>
                      <Copy size={13} aria-hidden="true" />
                    </button>
                  </dd>
                </div>
              </dl>
            </section>
          </div>
        </div>
      </div>
      {moderating ? <ModerateServerModal server={server} owner={owner} preset={moderating} onClose={() => setModerating(null)} onDone={() => void load()} /> : null}
      {takingEmoji ? (
        <ModerateEmojiModal
          server={{ id: server.id, name: server.name, icon_id: server.icon_id, owner_id: server.owner_id }}
          owner={owner}
          emojis={emojis.filter((e) => picked.includes(e.id))}
          onClose={() => setTakingEmoji(false)}
          onDone={() => {
            setPicked([]);
            void load();
          }}
        />
      ) : null}
    </>
  );
}
