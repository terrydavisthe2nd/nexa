import { Award, PenLine, Pipette, Plus, Trash2 } from "lucide-react";
import { lazy, Suspense, useCallback, useEffect, useState, type CSSProperties } from "react";
import { moderation as modApi } from "@/api/moderation";
import type { BadgeDef } from "@/api/types";
import { BadgePill } from "@/components/badges/Badges";
import { Button } from "@/components/common/Button";
import { ColourInput } from "@/components/common/ColourInput";
import { LoadError } from "@/components/common/LoadError";
import { Spinner } from "@/components/common/Spinner";
import { Field, TextInput } from "@/components/common/TextInput";
import { EVENT_COLOURS, EVENT_ICONS, EVENT_PREFIX, hexColour } from "@/lib/badges";
import { describeError } from "@/lib/errors";
import { useModEvents } from "@/stores/moderation";
import { useUI } from "@/stores/ui";
import { t } from "@/i18n";
import m from "./Moderation.module.css";
import b from "./BadgesTab.module.css";

const EventIcon = lazy(() => import("@/components/badges/EventIcon"));

const MAX_NAME = 32;
const NOBODY = { id: "0", flags: 0, plan: "" as const };

/**
 * The badges staff give by hand: Contributor, and the badges they make for an
 * event, each a name, a Lucide icon and a colour. They are given and taken on
 * a person's page (GrantBadgesModal); deleting one takes it from everyone.
 */
export function BadgesTab() {
  const toast = useUI((u) => u.toast);
  const openModal = useUI((u) => u.openModal);
  const [list, setList] = useState<BadgeDef[] | null>(null);
  const [contributors, setContributors] = useState(0);
  const [failed, setFailed] = useState(false);
  const [editing, setEditing] = useState<BadgeDef | null>(null);
  const [name, setName] = useState("");
  const [icon, setIcon] = useState<string>(EVENT_ICONS[0]);
  const [colour, setColour] = useState(EVENT_COLOURS[0]!);
  const [busy, setBusy] = useState(false);

  const load = useCallback(() => {
    setFailed(false);
    modApi
      .badges()
      .then((r) => {
        setList(r.badges);
        setContributors(r.contributors);
      })
      .catch(() => setFailed(true));
  }, []);
  useEffect(() => load(), [load]);
  // another member of staff gave, made or deleted one
  useModEvents((e) => {
    if (e.type === "log" && e.entry.action.startsWith("badge_")) load();
  });

  const startEdit = (d: BadgeDef | null) => {
    setEditing(d);
    setName(d?.name ?? "");
    setIcon(d?.icon ?? EVENT_ICONS[0]);
    setColour(d?.color ?? EVENT_COLOURS[0]!);
  };
  const nameLength = [...name.trim()].length;
  const ready = nameLength > 0 && nameLength <= MAX_NAME;

  const submit = async () => {
    setBusy(true);
    try {
      const body = { name: name.trim(), icon, color: colour };
      if (editing) {
        const d = await modApi.editBadge(editing.id, body);
        setList((cur) => cur?.map((x) => (x.id === d.id ? { ...d, holders: x.holders } : x)) ?? cur);
        toast(t("badges.staff.saved"), "success");
      } else {
        const d = await modApi.createBadge(body);
        setList((cur) => [{ ...d, holders: 0 }, ...(cur ?? [])]);
        toast(t("badges.staff.created"), "success");
      }
      startEdit(null);
    } catch (err) {
      toast(describeError(err, t("moderation.failed")), "error");
    } finally {
      setBusy(false);
    }
  };

  const remove = (d: BadgeDef) =>
    openModal("confirm", {
      title: t("badges.staff.deleteTitle"),
      body: t("badges.staff.deleteBody", { name: d.name }),
      confirmLabel: t("badges.staff.delete"),
      danger: true,
      onConfirm: async () => {
        try {
          await modApi.deleteBadge(d.id);
          setList((cur) => cur?.filter((x) => x.id !== d.id) ?? cur);
          if (editing?.id === d.id) startEdit(null);
          toast(t("badges.staff.deleted"), "success");
        } catch (err) {
          toast(describeError(err, t("moderation.failed")), "error");
        }
      },
    });

  const preview = { id: `${EVENT_PREFIX}0`, name: name.trim() || t("badges.staff.namePlaceholder"), icon, color: colour };
  return (
    <div className={m.scroll} data-testid="badges-tab">
      <div className={m.annLayout}>
        <section className={m.panel} aria-labelledby="badge-form">
          <h2 className={m.panelTitle} id="badge-form">
            <Award size={18} aria-hidden="true" />
            {editing ? t("badges.staff.edit") : t("badges.staff.new")}
          </h2>
          <p className={m.panelHint}>{t("badges.staff.hint")}</p>
          <div className={m.annForm}>
            <div className={b.previewRow} aria-hidden="true">
              <BadgePill badge={preview} user={NOBODY} still named />
            </div>
            <Field label={t("badges.staff.name")} hint={nameLength > MAX_NAME - 8 ? `${nameLength}/${MAX_NAME}` : undefined}>
              <TextInput value={name} onChange={(e) => setName(e.target.value)} placeholder={t("badges.staff.namePlaceholder")} maxLength={MAX_NAME + 8} data-testid="badge-name" />
            </Field>
            <div>
              <span className={b.label} id="badge-icon-label">{t("badges.staff.icon")}</span>
              <div className={b.icons} role="radiogroup" aria-labelledby="badge-icon-label">
                <Suspense fallback={<Spinner />}>
                  {EVENT_ICONS.map((name) => (
                    <button key={name} type="button" role="radio" aria-checked={icon === name} aria-label={name} title={name} className={b.icon} data-on={icon === name || undefined} onClick={() => setIcon(name)}>
                      <EventIcon name={name} size={18} />
                    </button>
                  ))}
                </Suspense>
              </div>
            </div>
            <div>
              <span className={b.label} id="badge-colour-label">{t("badges.staff.colour")}</span>
              <div className={b.colours} role="radiogroup" aria-labelledby="badge-colour-label">
                {EVENT_COLOURS.map((c) => (
                  <button key={c} type="button" role="radio" aria-checked={colour === c} aria-label={hexColour(c)} className={b.swatch} style={{ "--swatch": hexColour(c) } as CSSProperties} data-on={colour === c || undefined} onClick={() => setColour(c)} />
                ))}
                {/* any other colour: a pipette over the picker's own cover, so it is not taken for one more swatch */}
                <span className={b.custom} data-on={!EVENT_COLOURS.includes(colour) || undefined} style={{ "--swatch": hexColour(colour) } as CSSProperties}>
                  <Pipette size={15} aria-hidden="true" />
                  <ColourInput value={hexColour(colour)} onChange={(v) => setColour(parseInt(v.slice(1, 7), 16))} label={t("badges.staff.customColour")} />
                </span>
              </div>
            </div>
            <div className={m.annFooter}>
              {editing ? (
                <Button size="sm" variant="ghost" onClick={() => startEdit(null)}>{t("chat.editMessage.cancel")}</Button>
              ) : null}
              <span className={m.grow} />
              <Button size="sm" disabled={!ready} loading={busy} onClick={() => void submit()} data-testid="badge-save">
                {editing ? t("badges.staff.save") : <><Plus size={15} aria-hidden="true" /> {t("badges.staff.create")}</>}
              </Button>
            </div>
          </div>
        </section>

        <section aria-labelledby="badge-list">
          <h2 className={m.panelTitle} id="badge-list" style={{ marginBottom: 12 }}>
            {t("badges.staff.list")}
          </h2>
          {failed && !list ? (
            <LoadError message={t("moderation.loadFailed")} onRetry={load} />
          ) : list === null ? (
            <Spinner label={t("chat.pins.loading")} />
          ) : (
            <ul className={b.rows}>
              <li className={b.row} data-testid="badge-def-contributor">
                <BadgePill badge={{ id: "contributor" }} user={NOBODY} still named />
                <span className={b.meta}>{t("badges.staff.holders", { count: contributors })}</span>
                <span className={b.note}>{t("badges.staff.contributorNote")}</span>
              </li>
              {list.map((d) => (
                <li key={d.id} className={b.row} data-editing={editing?.id === d.id || undefined} data-testid="badge-def">
                  <BadgePill badge={{ id: `${EVENT_PREFIX}${d.id}`, name: d.name, icon: d.icon, color: d.color }} user={NOBODY} still named />
                  <span className={b.meta}>{t("badges.staff.holders", { count: d.holders })}</span>
                  <span className={b.tools}>
                    <Button size="sm" variant="ghost" onClick={() => startEdit(d)} aria-label={t("badges.staff.editNamed", { name: d.name })} title={t("badges.staff.edit")}>
                      <PenLine size={15} aria-hidden="true" />
                    </Button>
                    <Button size="sm" variant="ghost" onClick={() => remove(d)} aria-label={t("badges.staff.deleteNamed", { name: d.name })} title={t("badges.staff.delete")}>
                      <Trash2 size={15} aria-hidden="true" />
                    </Button>
                  </span>
                </li>
              ))}
              {list.length === 0 ? <li className={b.empty}>{t("badges.staff.none")}</li> : null}
            </ul>
          )}
        </section>
      </div>
    </div>
  );
}
