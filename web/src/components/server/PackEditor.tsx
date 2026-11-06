import { Check, CheckSquare, ChevronLeft, ChevronRight, Copy, Eye, Globe, Lock, Plus, Star, Trash2, Upload, X } from "lucide-react";
import { useEffect, useMemo, useRef, useState, type DragEvent, type ReactNode } from "react";
import { ApiError } from "@/api/client";
import { expressions as expressionsApi } from "@/api/expressions";
import type { Sticker, StickerPack } from "@/api/types";
import { packImageUrl } from "@/stores/expressions";
import { Button } from "@/components/common/Button";
import { Modal } from "@/components/common/Modal";
import { SearchBox } from "@/components/common/SearchBox";
import { Field, TextArea, TextInput } from "@/components/common/TextInput";
import { Toggle } from "@/components/common/Toggle";
import { StickerView } from "@/components/stickers/StickerView";
import { LazySticker } from "@/components/stickers/StickerTile";
import { confirmRemovePack } from "@/lib/packRemoval";
import { useReorder } from "@/hooks/useReorder";
import { avatarColor } from "@/lib/avatarColor";
import { useDominantColor } from "@/lib/dominantColor";
import { describeError } from "@/lib/errors";
import { useExpressions } from "@/stores/expressions";
import { useUI, type ContextMenuItem } from "@/stores/ui";
import { button, defineMenu } from "@/lib/contextMenus/registry";

/** A sticker's menu in its pack: moving it up or down, as dragging does. */
const stickerOrderMenu = defineMenu<{ moves: ContextMenuItem[] }>("stickerOrder", [[button("moveSticker", ({ moves }) => moves)]]);
import { copyText } from "@/lib/copyText";
import { unlessUnsaved } from "@/stores/unsaved";
import { SaveBar } from "./SettingsLayer";
import s from "./PackEditor.module.css";

import { t } from "@/i18n";
const ACCEPT_IMAGE = "image/png,image/gif,image/webp,image/apng";
const ACCEPT_STICKER = `${ACCEPT_IMAGE},video/webm,.tgs`;
const MAX_STICKER = 5 * 1024 * 1024;
/** How many a pack holds before its page offers a search of its own. */
const SEARCH_FROM = 24;
/** How many of a pack's stickers its row in the list shows. */
const STRIP = 6;

export function packCover(p: StickerPack): Sticker | undefined {
  return p.stickers.find((st) => st.id === p.cover_sticker_id) ?? p.stickers[0];
}

/** "3 stickers", "1 sticker", "12 emoji", in the reader's language. */
export function packCount(p: StickerPack): string {
  const count = p.sticker_count ?? p.stickers.length;
  return (p.kind ?? "sticker") === "emoji" ? t("server.packEditor.emojiCount", { count }) : t("server.packEditor.stickerCount", { count });
}

export function packUrl(id: string): string {
  return `${window.location.origin}/stickers/pack/${id}`;
}

/** A part of the list: its heading, and the packs under it. */
export interface PackGroup {
  id: string;
  title?: string;
  packs: StickerPack[];
  /** What a row says after the count: a pack from a server names the server. */
  note?: (p: StickerPack) => string | null;
}

interface Props {
  groups: PackGroup[];
  selectedId: string | null;
  onSelect: (id: string | null) => void;
  canManage: (p: StickerPack) => boolean;
  /** Packs the user installed and may remove from the list itself. */
  canRemove?: (p: StickerPack) => boolean;
  /** What sits beside the search: making a pack, finding one. */
  actions?: ReactNode;
  emptyText?: string;
  /** Where the pack page's back button goes. */
  backLabel: string;
}

/** A list of packs where each is its stickers at a glance, and a page of its own for one you can change. */
export function PackEditor({ groups, selectedId, onSelect, canManage, canRemove, actions, emptyText = t("server.packEditor.noPacksYet"), backLabel }: Props) {
  const [q, setQ] = useState("");
  const packs = useMemo(() => groups.flatMap((g) => g.packs), [groups]);
  const selected = packs.find((p) => p.id === selectedId) ?? null;
  const openModal = useUI((u) => u.openModal);
  const query = q.trim().toLowerCase();
  if (selected && canManage(selected)) return <PackPage key={selected.id} pack={selected} backLabel={backLabel} onBack={() => onSelect(null)} />;
  const open = (p: StickerPack) => (canManage(p) ? onSelect(p.id) : openModal("stickerPack", { packId: p.id, pack: p }));
  const shown = groups.map((g) => ({ ...g, packs: query ? g.packs.filter((p) => p.name.toLowerCase().includes(query)) : g.packs })).filter((g) => g.packs.length > 0);
  return (
    <div className={s.library}>
      <div className={s.toolbar}>
        {packs.length > 3 ? <SearchBox className={s.search} value={q} onChange={setQ} placeholder={t("settings.stickers.searchYourPacks")} aria-label={t("settings.stickers.searchYourPacks")} /> : null}
        {actions ? <div className={s.toolbarActions}>{actions}</div> : null}
      </div>
      {packs.length === 0 ? <div className={s.empty}>{emptyText}</div> : null}
      {packs.length > 0 && shown.length === 0 ? <div className={s.empty}>{t("settings.stickers.noPacksMatch")}</div> : null}
      {shown.map((g) => (
        <section key={g.id} className={s.group} data-group={g.id}>
          {g.title ? (
            <h3 className={s.groupTitle}>
              <span>{g.title}</span>
              <span className={s.groupCount}>{g.packs.length}</span>
            </h3>
          ) : null}
          <div className={s.rows}>
            {g.packs.map((p) => <PackRow key={p.id} pack={p} manage={canManage(p)} removable={canRemove?.(p) ?? false} note={g.note?.(p) ?? null} onOpen={() => open(p)} />)}
          </div>
        </section>
      ))}
    </div>
  );
}

/** One pack in the list: its cover, what it is, and a strip of what is in it to know it by. */
function PackRow({ pack, manage, removable, note, onOpen }: { pack: StickerPack; manage: boolean; removable: boolean; note: string | null; onOpen: () => void }) {
  const cover = packCover(pack);
  const strip = pack.stickers.filter((st) => st.id !== cover?.id).slice(0, STRIP);
  return (
    <div className={s.row}>
      <button type="button" className={s.rowMain} onClick={unlessUnsaved(onOpen)} data-pack={pack.id} data-testid="pack-row">
        <span className={s.cover} data-kind={pack.kind}>{cover ? <LazySticker sticker={cover} pack={pack} alt="" /> : pack.name.slice(0, 1).toUpperCase()}</span>
        <span className={s.rowText}>
          <span className={s.packName} dir="auto">{pack.name}</span>
          <span className={s.packSub}>
            <span>{packCount(pack)}</span>
            {manage ? <span className={s.visibility} data-public={pack.public || undefined}>{pack.public ? <Globe size={12} aria-hidden="true" /> : <Lock size={12} aria-hidden="true" />}{pack.public ? t("settings.stickers.publicBadge") : t("settings.stickers.privateBadge")}</span> : null}
            {note ? <span className={s.note} dir="auto">{note}</span> : null}
          </span>
        </span>
        <span className={s.strip} aria-hidden="true" data-kind={pack.kind}>
          {strip.map((st) => <span key={st.id} className={s.stripItem}><LazySticker sticker={st} pack={pack} alt="" /></span>)}
        </span>
        {manage ? <ChevronRight className={s.rowChevron} size={18} aria-hidden="true" /> : null}
      </button>
      {removable ? (
        <button type="button" className={s.rowRemove} aria-label={t("pickers.stickerPicker.removeName", { name: pack.name })} title={t("server.packEditor.removePack")} onClick={() => confirmRemovePack(pack)} data-testid="pack-remove">
          <X size={16} aria-hidden="true" />
        </button>
      ) : null}
    </div>
  );
}

interface Staged {
  file: File;
  name: string;
  tags: string;
  preview: string;
  format?: string;
}

/** One pack you can change: its stickers first, since they are what you came for, then what it is called and who can have it. */
function PackPage({ pack, backLabel, onBack }: { pack: StickerPack; backLabel: string; onBack: () => void }) {
  const upsertPack = useExpressions((x) => x.upsertPack);
  const removePack = useExpressions((x) => x.removePack);
  const toast = useUI((u) => u.toast);
  const openModal = useUI((u) => u.openModal);
  const [name, setName] = useState(pack.name);
  const [description, setDescription] = useState(pack.description);
  const [busy, setBusy] = useState(false);
  const [copied, setCopied] = useState(false);
  const [drag, setDrag] = useState<string | null>(null);
  const [drop, setDrop] = useState<{ id: string; after: boolean } | null>(null);
  const [over, setOver] = useState(false);
  const [pending, setPending] = useState<Staged[]>([]);
  const [progress, setProgress] = useState<{ done: number; total: number } | null>(null);
  const [q, setQ] = useState("");
  const [picking, setPicking] = useState(false);
  const [chosen, setChosen] = useState<Set<string>>(() => new Set());
  const [editing, setEditing] = useState<string | null>(null);
  const [installs, setInstalls] = useState<number | null>(null);
  const fileRef = useRef<HTMLInputElement>(null);
  // previews stay alive while they are staged: each is revoked when it is removed or uploaded, and the rest on unmount
  const pendingRef = useRef(pending);
  pendingRef.current = pending;
  useEffect(() => () => pendingRef.current.forEach((p) => URL.revokeObjectURL(p.preview)), []);
  // only the pack's own page asks how many have it
  useEffect(() => {
    let alive = true;
    void expressionsApi.pack(pack.id).then((p) => alive && setInstalls(p.install_count ?? null)).catch(() => undefined);
    return () => {
      alive = false;
    };
  }, [pack.id]);
  const dirty = name !== pack.name || description !== pack.description;
  const isEmoji = (pack.kind ?? "sticker") === "emoji";
  const noun = isEmoji ? "emoji" : "sticker";
  const maxBytes = isEmoji ? 2 * 1024 * 1024 : MAX_STICKER;
  const cover = packCover(pack);
  const coverStill = cover ? packImageUrl(pack, cover, 96) + (cover.animated || cover.format === "webm" || cover.format === "tgs" ? "&still=1" : "") : null;
  const tint = useDominantColor(coverStill);
  const query = q.trim().toLowerCase();
  const shown = query ? pack.stickers.filter((st) => st.name.toLowerCase().includes(query) || st.tags.toLowerCase().includes(query)) : pack.stickers;

  const save = async () => {
    setBusy(true);
    try {
      const saved = await expressionsApi.patchPack(pack.id, { ...(name !== pack.name ? { name } : {}), ...(description !== pack.description ? { description } : {}) });
      upsertPack(saved);
      // the server tidies whitespace, so show what it kept or the save bar never goes away
      setName(saved.name);
      setDescription(saved.description);
      toast(t("server.packEditor.packSaved"), "success");
    } catch (err) {
      toast(err instanceof ApiError ? err.message : t("server.packEditor.couldNotSaveThePack"), "error");
    } finally {
      setBusy(false);
    }
  };
  // one change at a time: pressed again while one is on its way (or waits on a captcha), it waits
  const [publicBusy, setPublicBusy] = useState(false);
  const setPublic = async (v: boolean) => {
    if (publicBusy) return;
    setPublicBusy(true);
    try {
      upsertPack(await expressionsApi.patchPack(pack.id, { public: v }));
    } catch (err) {
      toast(err instanceof ApiError ? err.message : t("server.packEditor.couldNotChangeVisibility"), "error");
    } finally {
      setPublicBusy(false);
    }
  };
  const stage = (files: FileList | File[]) => {
    const list: typeof pending = [];
    // A .tgs file usually arrives with no usable MIME type, so it is
    // recognised by its extension; video stickers arrive as video/webm.
    // Emoji packs take pictures only, in either case.
    const accept = isEmoji ? ACCEPT_IMAGE : ACCEPT_STICKER;
    for (const f of Array.from(files)) {
      const tgs = f.name.toLowerCase().endsWith(".tgs");
      const format = f.type === "video/webm" ? "webm" : tgs ? "tgs" : undefined;
      if (!accept.split(",").includes(f.type) && !(format && !isEmoji)) {
        toast(t(isEmoji ? "server.packEditor.mustBeAnImage" : "server.packEditor.stickersMustBeImages", { name: f.name }), "error");
        continue;
      }
      if (f.size > maxBytes) {
        toast(t("server.packEditor.isLargerThan", { name: f.name, megabytes: Math.round(maxBytes / (1024 * 1024)) }), "error");
        continue;
      }
      const base = f.name.replace(/\.[a-z0-9]+$/i, "");
      const clean = isEmoji ? base.replace(/[^A-Za-z0-9_]+/g, "_").replace(/^_+|_+$/g, "").slice(0, 32) : base.slice(0, 30);
      list.push({ file: f, name: clean || noun, tags: "", preview: URL.createObjectURL(f), ...(format ? { format } : {}) });
    }
    setPending((cur) => [...cur, ...list]);
  };
  const unstage = (preview: string) => {
    URL.revokeObjectURL(preview);
    setPending((cur) => cur.filter((x) => x.preview !== preview));
  };
  const uploadAll = async () => {
    setBusy(true);
    // each upload leaves the list as soon as it is in, so a retry after a failure only sends what is left,
    // and files staged while this runs stay staged
    const batch = pending;
    let done = 0;
    setProgress({ done, total: batch.length });
    try {
      for (const p of batch) {
        await expressionsApi.uploadSticker(pack.id, p.name.trim() || "sticker", p.tags.trim(), p.file);
        unstage(p.preview);
        done++;
        setProgress({ done, total: batch.length });
      }
      upsertPack(await expressionsApi.pack(pack.id));
      toast(t(isEmoji ? "server.packEditor.emojiAdded" : "server.packEditor.stickersAdded", { count: done }), "success");
    } catch (err) {
      toast(err instanceof ApiError ? err.message : t("composer.composer.uploadFailed"), "error");
      upsertPack(await expressionsApi.pack(pack.id).catch(() => pack));
    } finally {
      setBusy(false);
      setProgress(null);
    }
  };
  const setCover = async (st: Sticker) => {
    try {
      upsertPack(await expressionsApi.patchPack(pack.id, { cover_sticker_id: st.id }));
    } catch (err) {
      toast(err instanceof ApiError ? err.message : t("server.packEditor.couldNotSetTheCover"), "error");
    }
  };
  /** Sends a whole new order, which is what the endpoint takes. */
  const applyOrder = async (list: Sticker[]): Promise<boolean> => {
    try {
      upsertPack(await expressionsApi.setStickerPositions(pack.id, list.map((x, i) => ({ id: x.id, position: i }))));
      return true;
    } catch (err) {
      toast(describeError(err, t("server.packEditor.couldNotReorder")), "error");
      return false;
    }
  };
  const moveBy = (id: string, delta: number): boolean | Promise<boolean> => {
    const at = pack.stickers.findIndex((x) => x.id === id);
    const to = at + delta;
    if (at < 0 || to < 0 || to >= pack.stickers.length) return false;
    const list = [...pack.stickers];
    const [moving] = list.splice(at, 1);
    list.splice(to, 0, moving!);
    return applyOrder(list);
  };
  // Stickers are a grid, so "earlier" and "later" rather than up and down.
  const reorder = useReorder({
    ids: pack.stickers.map((x) => x.id),
    axis: "grid",
    name: (id) => pack.stickers.find((x) => x.id === id)?.name ?? "",
    canMove: () => !query && !picking,
    move: moveBy,
  });

  const onDrop = async (e: DragEvent) => {
    e.preventDefault();
    const d = drag;
    const at = drop;
    setDrag(null);
    setDrop(null);
    if (!d || !at || d === at.id) return;
    const list = pack.stickers.filter((x) => x.id !== d);
    const moving = pack.stickers.find((x) => x.id === d);
    if (!moving) return;
    const idx = list.findIndex((x) => x.id === at.id) + (at.after ? 1 : 0);
    list.splice(idx, 0, moving);
    await applyOrder(list);
  };
  const copyLink = () => {
    void copyText(packUrl(pack.id)).then((ok) => {
      if (!ok) {
        useUI.getState().toast(t("common.copyFailed"), "error");
        return;
      }
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    });
  };
  const toggleChosen = (id: string) =>
    setChosen((cur) => {
      const next = new Set(cur);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  const stopPicking = () => {
    setPicking(false);
    setChosen(new Set());
  };
  const deleteChosen = () => {
    const list = [...chosen];
    openModal("confirm", {
      title: t("server.packEditor.deleteChosen", { count: list.length }),
      body: t("server.packEditor.chosenAreRemovedForEveryone"),
      confirmLabel: t("chat.messageItem.delete"),
      danger: true,
      onConfirm: async () => {
        try {
          for (const id of list) await expressionsApi.deleteSticker(pack.id, id);
        } finally {
          upsertPack(await expressionsApi.pack(pack.id));
          stopPicking();
        }
      },
    });
  };
  const deletePack = () =>
    openModal("confirm", {
      title: t("server.packEditor.deleteNamed", { name: pack.name }),
      body: t("server.packEditor.everyoneWhoInstalledThisPackLoses"),
      confirmLabel: t("server.packEditor.deletePack"),
      danger: true,
      onConfirm: async () => {
        await expressionsApi.deletePack(pack.id);
        removePack(pack.id);
        onBack();
      },
    });
  const openFiles = () => fileRef.current?.click();
  const onFileDrag = (e: DragEvent) => {
    // a sticker being moved inside the grid is not a file arriving
    if (drag || !e.dataTransfer.types.includes("Files")) return;
    e.preventDefault();
    setOver(true);
  };
  const onFileDrop = (e: DragEvent) => {
    if (drag || !e.dataTransfer.files.length) return;
    e.preventDefault();
    setOver(false);
    stage(e.dataTransfer.files);
  };
  const editingSticker = editing ? pack.stickers.find((x) => x.id === editing) : undefined;
  const addLabel = t(isEmoji ? "android.settings.stickers.addEmoji" : "android.settings.stickers.addStickers");
  const color = tint ?? avatarColor(pack.id);

  return (
    <div className={s.page} data-testid="pack-page" onDragOver={onFileDrag} onDragLeave={(e) => { if (!e.currentTarget.contains(e.relatedTarget as Node)) setOver(false); }} onDrop={onFileDrop} data-drop-zone>
      <button type="button" className={s.back} onClick={unlessUnsaved(onBack)} aria-label={t("server.settingsLayer.backTo", { name: backLabel })} data-testid="pack-back">
        <ChevronLeft size={18} aria-hidden="true" />
        <span>{backLabel}</span>
      </button>
      <header className={s.hero} style={{ "--tint": color } as React.CSSProperties}>
        <span className={s.heroCover} data-kind={pack.kind}>{cover ? <StickerView format={cover.format} src={packImageUrl(pack, cover)} alt="" /> : pack.name.slice(0, 1).toUpperCase()}</span>
        <div className={s.heroText}>
          <h3 className={s.title} dir="auto">{pack.name}</h3>
          <div className={s.heroFacts}>
            <span>{packCount(pack)}</span>
            <span className={s.visibility} data-public={pack.public || undefined}>{pack.public ? <Globe size={13} aria-hidden="true" /> : <Lock size={13} aria-hidden="true" />}{pack.public ? t("settings.stickers.publicBadge") : t("settings.stickers.privateBadge")}</span>
            {pack.public && installs !== null ? <span>{t("stickerPack.installs", { count: installs })}</span> : null}
            {pack.server_id ? <span>{t("server.packEditor.serverPack")}</span> : null}
          </div>
          <div className={s.heroActions}>
            <Button size="sm" onClick={openFiles}><Upload size={14} aria-hidden="true" /> {addLabel}</Button>
            <Button size="sm" variant="secondary" onClick={() => openModal("stickerPack", { packId: pack.id, pack })} data-testid="pack-preview"><Eye size={14} aria-hidden="true" /> {t("pickers.stickerPicker.viewPack")}</Button>
          </div>
        </div>
      </header>
      <input ref={fileRef} type="file" accept={isEmoji ? ACCEPT_IMAGE : ACCEPT_STICKER} multiple hidden onChange={(e) => { if (e.target.files) stage(e.target.files); e.target.value = ""; }} data-testid="sticker-file" />

      {pending.length ? (
        <section className={s.staged} data-testid="staged-stickers">
          <div className={s.stagedHead}>
            <h4 className={s.sectionTitle}>{t("server.packEditor.readyToUpload")}</h4>
            <Button size="sm" loading={busy} onClick={() => void uploadAll()} data-testid="upload-stickers">{isEmoji ? t("server.packEditor.uploadEmojiCount", { count: pending.length }) : t("server.packEditor.uploadStickerCount", { count: pending.length })}</Button>
          </div>
          {progress ? (
            <div className={s.uploading}>
              <div className={s.bar} role="progressbar" aria-valuemin={0} aria-valuemax={progress.total} aria-valuenow={progress.done}><i style={{ width: `${Math.round((progress.done / Math.max(1, progress.total)) * 100)}%` }} /></div>
              <span>{t("server.packEditor.uploadingProgress", { done: progress.done, total: progress.total })}</span>
            </div>
          ) : null}
          {pending.map((p) => (
            <div key={p.preview} className={s.pending}>
              <StickerView format={p.format} src={p.preview} alt="" />
              <TextInput value={p.name} maxLength={30} placeholder={t("modals.subchat.name")} aria-label={t("modals.subchat.name")} onChange={(e) => setPending((cur) => cur.map((x) => (x.preview === p.preview ? { ...x, name: e.target.value } : x)))} data-testid="sticker-name" />
              {isEmoji ? null : <TextInput value={p.tags} maxLength={200} placeholder={t("server.packEditor.tagsSpaceSeparated")} aria-label={t("server.packEditor.tags")} onChange={(e) => setPending((cur) => cur.map((x) => (x.preview === p.preview ? { ...x, tags: e.target.value } : x)))} />}
              <button type="button" className={s.pendingRemove} aria-label={t("pickers.stickerPicker.removeName", { name: p.name })} onClick={() => unstage(p.preview)}><X size={16} aria-hidden="true" /></button>
            </div>
          ))}
        </section>
      ) : null}

      <section className={s.stickers}>
        <div className={s.sectionHead}>
          <h4 className={s.sectionTitle}>{t(isEmoji ? "modals.customStatus.emoji" : "pickers.expressionPicker.stickers")}</h4>
          {pack.stickers.length >= SEARCH_FROM ? <SearchBox className={s.packSearch} value={q} onChange={setQ} placeholder={t("server.packEditor.searchThisPack")} aria-label={t("server.packEditor.searchThisPack")} /> : null}
          {pack.stickers.length > 0 ? <Button size="sm" variant={picking ? "secondary" : "ghost"} onClick={() => (picking ? stopPicking() : setPicking(true))} data-testid="pack-select">{picking ? t("chat.editMessage.cancel") : <><CheckSquare size={14} aria-hidden="true" /> {t("server.packEditor.select")}</>}</Button> : null}
        </div>
        {picking ? (
          <div className={s.pickBar} role="status" data-testid="pack-select-bar">
            <span className={s.pickCount}>{t("server.packEditor.selectedCount", { count: chosen.size })}</span>
            <Button size="sm" variant="ghost" onClick={() => setChosen(new Set(shown.map((x) => x.id)))}>{t("server.packEditor.selectAll")}</Button>
            <Button size="sm" variant="danger" disabled={chosen.size === 0} onClick={deleteChosen} data-testid="pack-delete-chosen"><Trash2 size={14} aria-hidden="true" /> {t("chat.messageItem.delete")}</Button>
          </div>
        ) : pack.stickers.length > 1 && !query ? <p className={s.sub}>{reorder.hint}</p> : null}
        {reorder.live}
        <div className={s.grid} data-kind={pack.kind} data-over={over || undefined} data-testid="pack-grid">
          {!picking && !query ? (
            <button type="button" className={s.addTile} onClick={openFiles} data-testid="pack-add-tile" title={t("server.packEditor.dropFilesHereOrClickTo", { size: Math.round(maxBytes / (1024 * 1024)) })}>
              <Plus size={26} aria-hidden="true" />
              <span>{addLabel}</span>
            </button>
          ) : null}
          {shown.map((st) => (
            <div
              key={st.id}
              className={s.cell}
              data-sticker={st.id}
              data-dragging={drag === st.id}
              data-chosen={chosen.has(st.id) || undefined}
              data-drop={drop?.id === st.id ? (drop.after ? "after" : "before") : undefined}
              draggable={!picking && !query}
              onDragStart={(e) => { if (picking || query) return e.preventDefault(); setDrag(st.id); e.dataTransfer.effectAllowed = "move"; }}
              onDragOver={(e) => { if (!drag || drag === st.id) return; e.preventDefault(); const r = e.currentTarget.getBoundingClientRect(); setDrop({ id: st.id, after: e.clientX > r.left + r.width / 2 }); }}
              onDrop={(e) => void onDrop(e)}
              onDragEnd={() => { setDrag(null); setDrop(null); }}
              onKeyDown={reorder.onKeyDown(st.id)}
              onContextMenu={(e) => {
                if (stickerOrderMenu.open({ moves: reorder.menuItems(st.id) }, { x: e.clientX, y: e.clientY, label: st.name })) e.preventDefault();
              }}
              data-reorder-id={st.id}
              tabIndex={-1}
            >
              <button type="button" className={s.cellHit} aria-label={st.name} aria-pressed={picking ? chosen.has(st.id) : undefined} onClick={() => (picking ? toggleChosen(st.id) : setEditing(st.id))} />
              {pack.cover_sticker_id === st.id ? <span className={s.coverBadge}>{t("server.packEditor.cover")}</span> : null}
              {picking ? <span className={s.tick} data-on={chosen.has(st.id) || undefined} aria-hidden="true">{chosen.has(st.id) ? <Check size={14} /> : null}</span> : null}
              <LazySticker sticker={st} pack={pack} alt={st.name} className={s.cellImg} />
              <span className={s.cellName} title={st.name} dir="auto">{st.name}</span>
            </div>
          ))}
        </div>
        {pack.stickers.length === 0 ? <p className={s.emptyHint}>{t("server.packEditor.dropFilesHereOrClickTo", { size: Math.round(maxBytes / (1024 * 1024)) })}{isEmoji ? ` ${t("server.packEditor.emojiNamesAreLettersNumbersAnd")}` : ` ${t("server.packEditor.stickerFormatsNote")}`}</p> : null}
        {query && shown.length === 0 ? <div className={s.empty}>{t("pickers.stickerPicker.noStickersMatch")}</div> : null}
      </section>

      <section className={s.details}>
        <h4 className={s.sectionTitle}>{t("server.packEditor.details")}</h4>
        <Field label={t("server.packEditor.packName")} required>
          <TextInput value={name} onChange={(e) => setName(e.target.value)} maxLength={100} data-testid="pack-name" />
        </Field>
        <Field label={t("server.packEditor.description")} hint={t("server.packEditor.shownWhenPeopleFindThePack")}>
          <TextArea value={description} onChange={(e) => setDescription(e.target.value.slice(0, 500))} maxLength={500} />
        </Field>
        <Toggle checked={pack.public} disabled={publicBusy} onChange={(v) => void setPublic(v)} label={t("server.packEditor.publicPack")} description={t("server.packEditor.anyoneWithTheLinkCanInstall")} />
        {pack.public ? (
          <div className={s.linkRow}>
            <TextInput readOnly value={packUrl(pack.id)} onFocus={(e) => e.currentTarget.select()} aria-label={t("server.packEditor.copyLink")} />
            <Button size="sm" variant={copied ? "positive" : "secondary"} onClick={copyLink}>{copied ? <><Check size={14} aria-hidden="true" /> {t("modals.groupInvite.copied")}</> : <><Copy size={14} aria-hidden="true" /> {t("server.packEditor.copyLink")}</>}</Button>
          </div>
        ) : null}
      </section>
      <div className={s.dangerRow}>
        <div className={s.dangerText}>
          <div className={s.sub}>{t("server.packEditor.everyoneWhoInstalledThisPackLoses")}</div>
        </div>
        <Button size="sm" variant="danger" onClick={deletePack} data-testid="pack-delete"><Trash2 size={14} aria-hidden="true" /> {t("server.packEditor.deletePack")}</Button>
      </div>
      <SaveBar dirty={dirty} busy={busy} onReset={() => { setName(pack.name); setDescription(pack.description); }} onSave={() => void save()} />
      {editingSticker ? <StickerDialog pack={pack} sticker={editingSticker} onClose={() => setEditing(null)} onCover={() => void setCover(editingSticker)} onMove={(by) => moveBy(editingSticker.id, by)} /> : null}
    </div>
  );
}

/** One sticker of a pack you can change: drawn big and moving, its name and tags, and everything else that can be done with it. */
function StickerDialog({ pack, sticker, onClose, onCover, onMove }: { pack: StickerPack; sticker: Sticker; onClose: () => void; onCover: () => void; onMove: (by: number) => boolean | Promise<boolean> }) {
  const isEmoji = pack.kind === "emoji";
  const upsertPack = useExpressions((x) => x.upsertPack);
  const toast = useUI((u) => u.toast);
  const openModal = useUI((u) => u.openModal);
  const [name, setName] = useState(sticker.name);
  const [tags, setTags] = useState(sticker.tags);
  const [busy, setBusy] = useState(false);
  const at = pack.stickers.findIndex((x) => x.id === sticker.id);
  const changed = name !== sticker.name || tags !== sticker.tags;
  const save = async () => {
    setBusy(true);
    try {
      await expressionsApi.patchSticker(pack.id, sticker.id, { name: name.trim().slice(0, 30) || sticker.name, tags: tags.trim().slice(0, 200) });
      upsertPack(await expressionsApi.pack(pack.id));
      onClose();
    } catch (err) {
      toast(err instanceof ApiError ? err.message : t("server.packEditor.couldNotRename"), "error");
    } finally {
      setBusy(false);
    }
  };
  const remove = () =>
    openModal("confirm", {
      title: t("server.packEditor.deleteNamed", { name: sticker.name }),
      body: t("server.packEditor.thisStickerIsRemovedFromThe"),
      confirmLabel: t("server.packEditor.deleteSticker"),
      danger: true,
      onConfirm: async () => {
        await expressionsApi.deleteSticker(pack.id, sticker.id);
        upsertPack(await expressionsApi.pack(pack.id));
        onClose();
      },
    });
  return (
    <Modal
      open
      onClose={onClose}
      title={isEmoji ? t("server.packEditor.editEmoji") : t("server.packEditor.editSticker")}
      width={560}
      onSubmit={changed ? () => void save() : undefined}
      footer={
        <>
          <Button variant="ghost" onClick={onClose}>{t("chat.editMessage.cancel")}</Button>
          <Button loading={busy} disabled={!changed} onClick={() => void save()} data-testid="sticker-save">{t("chat.editMessage.save")}</Button>
        </>
      }
    >
      <div className={s.stickerDialog} data-testid="sticker-dialog">
        <div className={s.stickerStage} data-kind={pack.kind}><StickerView format={sticker.format} src={packImageUrl(pack, sticker)} alt={sticker.name} eager /></div>
        <div className={s.stickerFields}>
          <Field label={t("modals.subchat.name")}>
            <TextInput value={name} maxLength={30} onChange={(e) => setName(e.target.value)} data-testid="sticker-edit-name" />
          </Field>
          <Field label={t("server.packEditor.tags")} hint={t("server.packEditor.spaceSeparatedUsedBySearch")}>
            <TextInput value={tags} maxLength={200} onChange={(e) => setTags(e.target.value)} data-testid="sticker-edit-tags" />
          </Field>
          <div className={s.stickerTools}>
            <Button size="sm" variant="secondary" disabled={pack.cover_sticker_id === sticker.id} onClick={onCover} data-testid="sticker-set-cover"><Star size={14} aria-hidden="true" /> {t("server.packEditor.setAsCover")}</Button>
            <Button size="sm" variant="secondary" onClick={remove} data-testid="sticker-delete"><Trash2 size={14} aria-hidden="true" /> {t("server.packEditor.deleteSticker")}</Button>
            <Button size="sm" variant="secondary" disabled={at <= 0} onClick={() => void onMove(-1)}>{t("reorder.moveEarlier")}</Button>
            <Button size="sm" variant="secondary" disabled={at >= pack.stickers.length - 1} onClick={() => void onMove(1)}>{t("reorder.moveLater")}</Button>
          </div>
        </div>
      </div>
    </Modal>
  );
}
