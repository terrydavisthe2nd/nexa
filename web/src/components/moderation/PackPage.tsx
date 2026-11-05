import { useReportNavigation } from "@/lib/reportNavigation";
import { can, useMyRank } from "@/lib/staffRank";
import { PersonName } from "@/components/common/PersonName";
import { CalendarDays, ChevronLeft, ChevronRight, Copy, Download, Eraser, EyeOff, FolderOpen, Gavel, LockOpen, Smile, Sticker as StickerIcon } from "lucide-react";
import { useCallback, useEffect, useState, type ReactNode } from "react";
import { useParams } from "react-router-dom";
import { moderation as modApi, type ModPack, type ModServerRef, type PackField, type People, type StaffCase } from "@/api/moderation";
import { Avatar } from "@/components/common/Avatar";
import { Button } from "@/components/common/Button";
import { Spinner } from "@/components/common/Spinner";
import { promptFields } from "@/components/modals/PromptModal";
import { copyAndSay } from "@/lib/copyText";
import { describeError } from "@/lib/errors";
import { actionIcon, actionLabel, expressionSummary, fullDate, packFieldLabel, personName, severity } from "@/lib/moderation";
import { formatRelative, formatShortDate } from "@/lib/time";
import { useModEvents, useModeration } from "@/stores/moderation";
import { useUI } from "@/stores/ui";
import { t, Tr } from "@/i18n";
import { ExpressionPic, PackCover, PackMosaic, packBlockedText } from "./ExpressionsTab";
import { ModeratePackModal, type PackPreset } from "./ModeratePackModal";
import { ServerIcon } from "./ProfileSheet";
import m from "./Moderation.module.css";
import p from "./Sheet.module.css";
import x from "./Expressions.module.css";

/**
 * One pack, for the staff: the pack as everyone sees it, every sticker in it
 * to tick and take down, its name, description and public listing, who
 * answers for it, and the cases about it.
 */
export function PackPage() {
  const mine = useMyRank();
  const { packId = "" } = useParams();
  const {nav,back,returnTo} = useReportNavigation();
  const mergePeople = useModeration((s) => s.mergePeople);
  const toast = useUI((u) => u.toast);
  const [pack, setPack] = useState<ModPack | null>(null);
  const [cases, setCases] = useState<StaffCase[]>([]);
  const [people, setPeople] = useState<People>({});
  const [servers, setServers] = useState<Record<string, ModServerRef>>({});
  const [failed, setFailed] = useState(false);
  const [picked, setPicked] = useState<string[]>([]);
  const [moderating, setModerating] = useState<PackPreset | null>(null);

  const load = useCallback(async () => {
    try {
      const r = await modApi.pack(packId);
      setPack(r.pack);
      setCases(r.cases);
      setPeople(r.people);
      setServers(r.servers);
      mergePeople(r.people);
      // what was ticked and has gone since is no longer there to take
      setPicked((cur) => cur.filter((id) => r.pack.stickers.some((s) => s.id === id)));
    } catch {
      setFailed(true);
    }
  }, [packId, mergePeople]);

  useEffect(() => {
    setPack(null);
    setFailed(false);
    setPicked([]);
    void load();
  }, [load]);

  useModEvents((e) => {
    if (e.type === "case" && e.case.action === "expression_takedown" && e.case.details.pack_id === packId) void load();
  });

  if (failed) {
    return (
      <div className={m.empty}>
        <p>{t("moderation.packs.missing")}</p>
        <Button size="sm" variant="secondary" onClick={() => back("/channels/@admin/expressions")}>
          {t("moderation.packs.back")}
        </Button>
      </div>
    );
  }
  if (!pack) {
    return (
      <div className={m.loadingRow}>
        <Spinner label={t("chat.pins.loading")} />
      </div>
    );
  }
  const emoji = pack.kind === "emoji";
  const server = pack.server_id ? servers[pack.server_id] : undefined;
  const owner = people[pack.owner_id];
  const blocked = packBlockedText(pack.blocked);
  const toggle = (id: string) => setPicked((cur) => (cur.includes(id) ? cur.filter((y) => y !== id) : [...cur, id]));

  const relist = async () => {
    const values = await promptFields({ title: t("moderation.packs.allowPublicTitle"), fields: [{ key: "reason", label: t("moderation.form.reason"), placeholder: t("moderation.packs.allowPublicPlaceholder"), maxLength: 1000 }], confirmLabel: t("moderation.packs.allowPublic") });
    const reason = values?.["reason"]?.trim();
    if (!reason) return;
    try {
      await modApi.relistPack(pack.id, reason);
      toast(t("moderation.packs.allowedPublic", { name: pack.name }), "success");
      void load();
    } catch (err) {
      toast(describeError(err, t("moderation.failed")), "error");
    }
  };

  const rows: { field: PackField; value: ReactNode; empty: boolean; action: ReactNode }[] = [
    {
      field: "name",
      value: <bdi>{pack.name}</bdi>,
      empty: false,
      action: (
        <Button size="sm" variant="secondary" className={p.rowAction} disabled={!!blocked} onClick={() => setModerating({ fields: ["name"] })} data-testid="pack-sheet-name" aria-label={`${t("moderation.sheet.reset")}: ${packFieldLabel("name")}`}>
          <Eraser size={15} aria-hidden="true" /> {t("moderation.sheet.reset")}
        </Button>
      ),
    },
    {
      field: "description",
      value: pack.description ? <span className={p.clip2} dir="auto">{pack.description}</span> : t("moderation.sheet.notSet"),
      empty: !pack.description,
      action: (
        <Button size="sm" variant="secondary" className={p.rowAction} disabled={!!blocked || !pack.description} onClick={() => setModerating({ fields: ["description"] })} data-testid="pack-sheet-description" aria-label={`${t("moderation.sheet.clear")}: ${packFieldLabel("description")}`}>
          <Eraser size={15} aria-hidden="true" /> {t("moderation.sheet.clear")}
        </Button>
      ),
    },
    {
      field: "public",
      value: pack.discover_blocked ? t("moderation.packs.listingKeptPrivate") : pack.public ? t("moderation.packs.listingPublic") : t("moderation.packs.listingPrivate"),
      empty: !pack.public && !pack.discover_blocked,
      action: pack.discover_blocked ? (
        // letting a pack the staff kept private be public again is an admin's
        <Button size="sm" variant="secondary" className={p.rowAction} onClick={() => void relist()} disabled={!can(mine, "takeDownServers")} data-testid="pack-sheet-relist">
          <LockOpen size={15} aria-hidden="true" /> {t("moderation.packs.allowPublic")}
        </Button>
      ) : (
        <Button size="sm" variant="secondary" className={p.rowAction} disabled={!!blocked || !pack.public} onClick={() => setModerating({ fields: ["public"] })} data-testid="pack-sheet-public" aria-label={t("moderation.packs.makePrivate")}>
          <EyeOff size={15} aria-hidden="true" /> {t("moderation.packs.makePrivateShort")}
        </Button>
      ),
    },
  ];

  return (
    <>
      <div className={m.userHeader} data-testid="pack-header">
        <button type="button" className={m.iconBtn} onClick={() => back("/channels/@admin/expressions")} aria-label={t(returnTo ? "reports.returnToReport" : "moderation.packs.back")}>
          <ChevronLeft size={18} className={m.flip} />
        </button>
        <div className={m.userWho}>
          <PackCover pack={pack} big />
          <div className={m.userNames}>
            <span className={m.userName}>
              <span dir="auto">{pack.name}</span>
            </span>
            <span className={m.userSub}>
              {emoji ? t("moderation.packs.emojiCount", { count: pack.sticker_count }) : t("moderation.packs.stickerCount", { count: pack.sticker_count })} · {t("moderation.packs.installs", { count: pack.installs })} · {t("moderation.casesCount", { count: cases.length })}
            </span>
          </div>
        </div>
        <div className={m.userActions}>
          <Button size="sm" onClick={() => setModerating({})} disabled={!!blocked} title={blocked || undefined} data-testid="pack-moderate">
            <Gavel size={15} aria-hidden="true" /> {t("moderation.moderate")}
          </Button>
        </div>
      </div>

      <div className={p.scroll}>
        <div className={p.sheet} data-testid="pack-sheet">
          <section className={p.face} aria-label={t("moderation.sheet.asSeen")}>
            <span className={p.faceCaption}>{t("moderation.sheet.asSeen")}</span>
            <PackMosaic pack={pack} tall />
            <div className={`${p.faceBody} ${x.faceBody}`}>
              <div className={p.faceNames}>
                <span className={p.faceName} dir="auto">{pack.name}</span>
                <span className={p.faceHandle}>{server ? t("moderation.packs.inServer", { name: server.name }) : <Tr id="moderation.servers.ownedBy" values={{ name: <PersonName user={owner}>{personName(owner)}</PersonName> }} />}</span>
              </div>
              <div className={p.faceSection}>
                <h4 className={p.faceLabel}>{t("moderation.servers.description")}</h4>
                {pack.description ? <p className={`${p.faceText} ${x.faceDesc}`} dir="auto">{pack.description}</p> : <p className={p.faceEmpty}>{t("moderation.servers.noDescription")}</p>}
              </div>
              <div className={p.faceSection}>
                <h4 className={p.faceLabel}>{emoji ? t("moderation.packs.emojiCount", { count: pack.stickers.length }) : t("moderation.packs.stickerCount", { count: pack.stickers.length })}</h4>
                {pack.stickers.length ? (
                  <div className={`${x.pickGrid} ${x.faceGrid}`} data-kind={emoji ? "emoji" : "sticker"} role="group" aria-label={t("moderation.packs.pickHint")}>
                    {pack.stickers.map((s) => (
                      <label key={s.id} className={x.tile} data-picked={picked.includes(s.id)} data-disabled={!!blocked || undefined} data-testid="pack-sticker">
                        <input type="checkbox" checked={picked.includes(s.id)} disabled={!!blocked} onChange={() => toggle(s.id)} aria-label={s.name} />
                        <span className={x.tilePic}>
                          <ExpressionPic kind={emoji ? "emoji" : "sticker"} blobId={s.blob_id} format={s.format} name={s.name} size={emoji ? 48 : 128} />
                        </span>
                        <span className={x.tileName} dir="auto">{emoji ? `:${s.name}:` : s.name}</span>
                      </label>
                    ))}
                  </div>
                ) : (
                  <p className={p.faceEmpty}>{t("moderation.packs.emptyPack")}</p>
                )}
                <div className={x.pickBar} hidden={!picked.length} data-testid="pack-pick-bar">
                  <span className={x.pickCount}>{t("moderation.packs.pickedCount", { count: picked.length })}</span>
                  <Button size="sm" variant="ghost" onClick={() => setPicked([])}>
                    {t("moderation.packs.clearPicked")}
                  </Button>
                  <span className={x.grow} />
                  <Button size="sm" variant="danger" onClick={() => setModerating({ stickers: picked })} data-testid="pack-take-picked">
                    <Gavel size={15} aria-hidden="true" /> {t("moderation.packs.takeDownPicked")}
                  </Button>
                </div>
              </div>
            </div>
          </section>

          <div className={p.column}>
            <section className={p.panel} aria-labelledby="pack-public">
              <header className={p.panelHead}>
                <h3 id="pack-public" className={p.panelTitle}>{t("moderation.sheet.publicTitle")}</h3>
                <p className={p.panelHint}>{blocked || t("moderation.packs.publicHint")}</p>
              </header>
              <ul className={p.rows}>
                {rows.map((r) => (
                  <li key={r.field} className={p.row} data-empty={r.empty || undefined}>
                    <span className={p.rowLabel}>{packFieldLabel(r.field)}</span>
                    <span className={p.rowValue}>{r.value}</span>
                    {r.action}
                  </li>
                ))}
              </ul>
            </section>

            <section className={p.panel} aria-labelledby="pack-owner">
              <header className={p.panelHead}>
                <h3 id="pack-owner" className={p.panelTitle}>{server ? t("moderation.packs.serverTitle") : t("moderation.servers.ownerTitle")}</h3>
              </header>
              {server ? (
                <button type="button" className={p.serverRow} onClick={() => nav(`/channels/@admin/servers/${server.id}`)} data-testid="pack-server">
                  <ServerIcon id={server.id} iconId={server.icon_id} name={server.name} size={36} />
                  <span className={p.serverText}>
                    <span className={p.serverName}>{server.name}</span>
                    <span className={p.serverMeta}>{<Tr id="moderation.servers.ownedBy" values={{ name: <PersonName user={owner}>{personName(owner)}</PersonName> }} />}</span>
                  </span>
                  <ChevronRight size={18} className={p.flip} aria-hidden="true" />
                </button>
              ) : null}
              <button type="button" className={p.serverRow} onClick={() => nav(`/channels/@admin/users/${pack.owner_id}`)} data-testid="pack-owner">
                <Avatar id={pack.owner_id} avatarId={owner?.avatar_id ?? null} name={personName(owner)} size={36} />
                <span className={p.serverText}>
                  <PersonName user={owner} className={p.serverName}>{personName(owner)}</PersonName>
                  <span className={p.serverMeta}>{owner ? `@${owner.username}` : ""}</span>
                </span>
                <ChevronRight size={18} className={p.flip} aria-hidden="true" />
              </button>
            </section>

            <section className={p.panel} aria-labelledby="pack-cases">
              <header className={p.panelHead}>
                <h3 id="pack-cases" className={p.panelTitle}>{t("moderation.packs.casesTitle")}</h3>
              </header>
              {cases.length ? (
                <ul className={p.rows}>
                  {cases.map((c) => {
                    const Icon = actionIcon(c.action);
                    return (
                      <li key={c.id}>
                        <button type="button" className={p.serverRow} onClick={() => nav(`/channels/@admin/cases/${c.id}`)} data-testid="pack-case">
                          <span className={m.caseIcon} data-tone={severity(c.action)} aria-hidden="true">
                            <Icon size={17} />
                          </span>
                          <span className={p.serverText}>
                            <span className={p.serverName}>{t("moderation.case.titled", { number: c.number, action: actionLabel(c.action) })}</span>
                            <span className={p.serverMeta}>{expressionSummary(c.details)}</span>
                          </span>
                          <span className={p.serverMeta} title={fullDate(c.created_at)}>{formatRelative(c.created_at)}</span>
                        </button>
                      </li>
                    );
                  })}
                </ul>
              ) : (
                <p className={p.panelEmpty}>
                  <FolderOpen size={16} aria-hidden="true" /> {t("moderation.packs.noCases")}
                </p>
              )}
            </section>

            <section className={p.panel} aria-labelledby="pack-facts">
              <header className={p.panelHead}>
                <h3 id="pack-facts" className={p.panelTitle}>{t("moderation.packs.factsTitle")}</h3>
              </header>
              <dl className={p.facts}>
                <div>
                  <dt><CalendarDays size={14} aria-hidden="true" /> {t("moderation.servers.created")}</dt>
                  <dd title={fullDate(pack.created_at)}>{formatShortDate(pack.created_at)}</dd>
                </div>
                <div>
                  <dt>{emoji ? <Smile size={14} aria-hidden="true" /> : <StickerIcon size={14} aria-hidden="true" />} {t("moderation.packs.kindLabel")}</dt>
                  <dd>{emoji ? t("moderation.packs.emojiPack") : t("moderation.packs.stickerPack")}</dd>
                </div>
                <div>
                  <dt><Download size={14} aria-hidden="true" /> {t("moderation.packs.installsLabel")}</dt>
                  <dd>{pack.installs}</dd>
                </div>
                <div>
                  <dt>{t("moderation.packs.packId")}</dt>
                  <dd>
                    <button type="button" className={p.copy} onClick={() => copyAndSay(pack.id, t("moderation.copied"))} aria-label={t("moderation.packs.copyId")}>
                      <code>{pack.id}</code>
                      <Copy size={13} aria-hidden="true" />
                    </button>
                  </dd>
                </div>
              </dl>
            </section>
          </div>
        </div>
      </div>
      {moderating ? (
        <ModeratePackModal
          pack={pack}
          owner={owner}
          server={server}
          preset={moderating}
          onClose={() => setModerating(null)}
          onDone={(c, removed) => {
            setPicked((cur) => cur.filter((id) => !removed.includes(id)));
            // a pack taken down whole has no page any more
            if (c.details.deleted) back("/channels/@admin/expressions");
            else void load();
          }}
        />
      ) : null}
    </>
  );
}
