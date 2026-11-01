import { Check, Minus, X } from "lucide-react";
import { Toggle } from "@/components/common/Toggle";
import { P, permGroups, has, type PermName } from "@/lib/perms";
import type { Channel } from "@/api/types";
import s from "./PermissionGrid.module.css";

import { t } from "@/i18n";
/**
 * Which permissions a channel of this type exposes; categories show
 * everything channel-scoped. An announcement channel is a text channel to its
 * permissions.
 */
export function permsForChannel(type: string | undefined): PermName[] {
  const kind = type === "announcement" ? "text" : type === "media" ? "forum" : type;
  const out: PermName[] = [];
  for (const g of permGroups()) {
    for (const it of g.items) {
      if (!it.channel) continue;
      if (!kind || kind === "category" || (it.channel as string[]).includes(kind)) out.push(it.name);
    }
  }
  return out;
}

/**
 * On/off toggles for a role's server-wide permissions. `grantable`, when
 * given, is what the editor holds themself: nobody may hand out what they do
 * not have (the server refuses the save), so the rest are shown but fixed.
 */
export function RoleGrid({ value, onChange, editable, filter, grantable }: { value: bigint; onChange: (v: bigint) => void; editable: boolean; filter?: string; grantable?: bigint }) {
  const q = (filter ?? "").trim().toLowerCase();
  return (
    <div className={s.grid}>
      {permGroups().map((g) => {
        const items = g.items.filter((it) => !q || it.label.toLowerCase().includes(q) || it.description.toLowerCase().includes(q));
        if (items.length === 0) return null;
        return (
          <section key={g.group} className={s.group}>
            <h4 className={s.groupHead}>{g.group}</h4>
            {items.map((it) => {
              const bit = P[it.name];
              return (
                <div key={it.name} className={s.toggleRow} data-perm={it.name}>
                  <Toggle checked={has(value, bit)} disabled={!editable || (grantable !== undefined && !has(grantable, bit))} onChange={(on) => onChange(on ? value | bit : value & ~bit)} label={it.label} description={it.description} />
                </div>
              );
            })}
          </section>
        );
      })}
    </div>
  );
}

export type Tri = "allow" | "neutral" | "deny";

export function triOf(allow: bigint, deny: bigint, bit: bigint): Tri {
  if (has(allow, bit)) return "allow";
  if (has(deny, bit)) return "deny";
  return "neutral";
}

/** Allow / inherit / deny controls for a channel overwrite. */
export function OverwriteGrid({
  allow,
  deny,
  onChange,
  editable,
  channelType,
  grantable,
}: {
  allow: bigint;
  deny: bigint;
  onChange: (allow: bigint, deny: bigint) => void;
  editable: boolean;
  channelType: Channel["type"] | undefined;
  /** What the editor holds in this channel; the rest are shown but fixed. */
  grantable?: bigint;
}) {
  const names = new Set(permsForChannel(channelType));
  const set = (bit: bigint, v: Tri) => {
    let a = allow & ~bit;
    let d = deny & ~bit;
    if (v === "allow") a |= bit;
    if (v === "deny") d |= bit;
    onChange(a, d);
  };
  // Left and Right across the three, as a radiogroup moves. The chosen one is
  // the only tab stop, so the whole row is one stop rather than three.
  const ORDER: Tri[] = ["deny", "neutral", "allow"];
  const triKeys = (e: React.KeyboardEvent<HTMLDivElement>, bit: bigint, cur: Tri) => {
    const step = e.key === "ArrowRight" || e.key === "ArrowDown" ? 1 : e.key === "ArrowLeft" || e.key === "ArrowUp" ? -1 : 0;
    if (!step || !editable || (grantable !== undefined && !has(grantable, bit))) return;
    e.preventDefault();
    const next = ORDER[(ORDER.indexOf(cur) + step + ORDER.length) % ORDER.length]!;
    set(bit, next);
    const group = e.currentTarget;
    requestAnimationFrame(() => group.querySelector<HTMLElement>(`[data-kind="${next}"]`)?.focus());
  };
  return (
    <div className={s.grid}>
      {permGroups().map((g) => {
        const items = g.items.filter((it) => names.has(it.name));
        if (items.length === 0) return null;
        return (
          <section key={g.group} className={s.group}>
            <h4 className={s.groupHead}>{g.group}</h4>
            {items.map((it) => {
              const bit = P[it.name];
              const cur = triOf(allow, deny, bit);
              const rowEditable = editable && (grantable === undefined || has(grantable, bit));
              return (
                <div key={it.name} className={s.row} data-perm={it.name}>
                  <div className={s.text}>
                    <span className={s.label}>{it.label}</span>
                    <span className={s.desc}>{it.description}</span>
                  </div>
                  {/* A radiogroup's children have to be radios: `aria-pressed`
                      buttons announce as three unrelated toggles, and without a
                      roving tabIndex every one of forty rows cost three Tab
                      presses on the way to Save. */}
                  <div className={s.tri} role="radiogroup" aria-label={it.label} onKeyDown={(e) => triKeys(e, bit, cur)}>
                    <button type="button" className={s.triBtn} data-kind="deny" data-on={cur === "deny"} disabled={!rowEditable} role="radio" aria-checked={cur === "deny"} tabIndex={cur === "deny" ? 0 : -1} aria-label={t("server.permissionGrid.deny")} onClick={() => set(bit, "deny")}><X size={16} /></button>
                    <button type="button" className={s.triBtn} data-kind="neutral" data-on={cur === "neutral"} disabled={!rowEditable} role="radio" aria-checked={cur === "neutral"} tabIndex={cur === "neutral" ? 0 : -1} aria-label={t("server.permissionGrid.inherit")} onClick={() => set(bit, "neutral")}><Minus size={16} /></button>
                    <button type="button" className={s.triBtn} data-kind="allow" data-on={cur === "allow"} disabled={!rowEditable} role="radio" aria-checked={cur === "allow"} tabIndex={cur === "allow" ? 0 : -1} aria-label={t("server.permissionGrid.allow")} onClick={() => set(bit, "allow")}><Check size={16} /></button>
                  </div>
                </div>
              );
            })}
          </section>
        );
      })}
    </div>
  );
}
