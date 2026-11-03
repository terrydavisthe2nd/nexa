import { useMediaAnimation } from "@/lib/mediaAnimation";
import { ChevronDown, ImagePlus, LoaderCircle } from "lucide-react";
import { useCallback, useEffect, useMemo, useRef, useState, type CSSProperties } from "react";
import { ApiError } from "@/api/client";
import { servers as serversApi, type MemberPatch } from "@/api/servers";
import type { PublicUser } from "@/api/types";
import { avatarUrl, bannerUrl, users as usersApi } from "@/api/users";
import { Avatar } from "@/components/common/Avatar";
import { Button } from "@/components/common/Button";
import { menuAnchor } from "@/components/common/ContextMenu";
import { ImageCropModal } from "@/components/common/ImageCropModal";
import { ProfileCard, type ProfileDesign, type ProfilePart, type ProfileTab } from "@/components/profile/ProfileCard";
import { type WidgetDesign } from "@/components/profile/Widgets";
import { SaveBar } from "@/components/server/SettingsLayer";
import { t } from "@/i18n";
import { button, defineMenu } from "@/lib/contextMenus/registry";
import { avatarColor, profileThemeOf } from "@/lib/avatarColor";
import { parse, plainText } from "@/markdown/parse";
import { useDominantColor } from "@/lib/dominantColor";
import { describeError } from "@/lib/errors";
import { P } from "@/lib/perms";
import { memberLook } from "@/lib/roleLook";
import { readTypingStyle } from "@/lib/typingStyle";
import { blankWidget, isBookWidget, isScreenWidget, isGameWidget, isMusicWidget, newWidgetId, readWidgets, type Widget, type WidgetType } from "@/lib/widgets";
import { useAuth } from "@/stores/auth";
import { useChannels } from "@/stores/channels";
import { canI, useServers } from "@/stores/servers";
import { useUI } from "@/stores/ui";
import { unlessUnsaved } from "@/stores/unsaved";
import { aboutToEdit, aboutToSave } from "./AboutMeEditor";
import { AboutField, ChatPreview, ColoursPicker, DesignerLayout, FormField, FormSection, hexOf, jumpToField, usePreviewLayout, ViewSwitch, WidgetList } from "./designerParts";
import { TextInput } from "@/components/common/TextInput";
import { AddWidgetDialog, BookWidgetDialog, ScreenWidgetDialog, CardDialog, CountdownDialog, GameWidgetDialog, LocalTimeDialog, MusicWidgetDialog, StickersDialog } from "./WidgetsEditor";
import d from "./Designer.module.css";

type View = "full" | "card" | "chat";
type Part = ProfilePart | "colours" | "widgets";

/** A status and a server tag are the account's, the same in every server. */
const FIXED: ProfilePart[] = ["status", "tag", "badges"];

const hex = (v: string | null) => (v ? parseInt(v.slice(1), 16) : null);

/**
 * Settings, Profiles, Server Profiles: the profile designer for one server.
 * The same stage as the main profile's, drawing the profile as people in
 * that server see it, with every part that can differ there: the nickname,
 * pronouns, picture, banner, theme, About Me and widgets. Whatever is left
 * empty is the main profile's, and the preview shows it so.
 */
export function ServerProfileDesigner({ serverId, onServer }: { serverId: string | null; onServer: (id: string) => void }) {
  const order = useServers((x) => x.order);
  const byId = useServers((x) => x.byId);
  const servers = useMemo(() => order.filter((id) => byId[id]), [order, byId]);
  const current = serverId && byId[serverId] ? serverId : (servers[0] ?? null);
  if (!current) {
    return (
      <div className={d.empty} data-testid="server-profiles-empty">
        <p className={d.emptyTitle}>{t("serverProfile.designer.noServers")}</p>
        <p className={d.emptyText}>{t("serverProfile.designer.noServersHint")}</p>
      </div>
    );
  }
  // a server of its own each: what was being edited for one never leaks into the next
  return <Designer key={current} serverId={current} servers={servers} onServer={onServer} />;
}

/** Which server's profile the designer edits. */
const serverProfilePickMenu = defineMenu<{ servers: string[]; current: string; pick: (id: string) => void }>("serverProfilePick", [
  [button("pickProfileServer", ({ servers, current, pick }) => {
    const byId = useServers.getState().byId;
    return servers.map((id) => ({ id, label: byId[id]?.server.name ?? id, checked: id === current, onSelect: () => pick(id) }));
  })],
]);

function ServerPicker({ serverId, servers, onServer }: { serverId: string; servers: string[]; onServer: (id: string) => void }) {
  const byId = useServers((x) => x.byId);
  const server = byId[serverId]?.server;
  if (!server) return null;
  const open = (el: HTMLElement) => {
    const anchor = menuAnchor(el, "start");
    serverProfilePickMenu.open({ servers, current: serverId, pick: (id) => unlessUnsaved(() => onServer(id))() }, { x: anchor.left, y: anchor.bottom + 4, anchor, label: t("serverProfile.designer.choose") });
  };
  return (
    <button type="button" className={d.serverPick} onClick={(e) => open(e.currentTarget)} aria-haspopup="menu" aria-label={t("serverProfile.designer.chooseNamed", { name: server.name })} data-testid="server-profile-picker">
      <Avatar id={server.id} avatarId={server.icon_id} name={server.name} size={28} kind="icon" square />
      <span className={d.serverPickName} dir="auto">{server.name}</span>
      <ChevronDown size={16} aria-hidden="true" />
    </button>
  );
}

function Designer({ serverId, servers, onServer }: { serverId: string; servers: string[]; onServer: (id: string) => void }) {
  useMediaAnimation();
  const me = useAuth((a) => a.me);
  const st = useServers((x) => x.byId[serverId]);
  const upsertMember = useServers((x) => x.upsertMember);
  const toast = useUI((u) => u.toast);
  const openModal = useUI((u) => u.openModal);
  const channels = useChannels((c) => c.byId);
  const member = st?.me;
  const [nick, setNick] = useState(member?.nickname ?? "");
  const [pronouns, setPronouns] = useState(member?.pronouns ?? "");
  const [about, setAbout] = useState(() => aboutToEdit(member?.bio ?? ""));
  const [accent, setAccent] = useState<string | null>(hexOf(member?.accent_color));
  const [accent2, setAccent2] = useState<string | null>(hexOf(member?.accent_color_2));
  // null: the main profile's widgets show here
  const [widgets, setWidgets] = useState<Widget[] | null>(null);
  const [savedWidgets, setSavedWidgets] = useState<Widget[] | null | undefined>(undefined);
  const [mainWidgets, setMainWidgets] = useState<Widget[]>([]);
  const [widgetsFailed, setWidgetsFailed] = useState(false);
  const [view, setView] = useState<View>("card");
  const layout = usePreviewLayout();
  const [tab, setTab] = useState<{ tab: ProfileTab; n: number } | null>(null);
  const [adding, setAdding] = useState(false);
  const [editing, setEditing] = useState<{ widget: Widget; isNew: boolean } | null>(null);
  const [crop, setCrop] = useState<{ file: File; kind: "avatar" | "banner" } | null>(null);
  const [uploading, setUploading] = useState<"avatar" | "banner" | null>(null);
  const [saving, setSaving] = useState(false);
  const formRef = useRef<HTMLDivElement>(null);
  const avatarRef = useRef<HTMLInputElement>(null);
  const bannerRef = useRef<HTMLInputElement>(null);
  const meId = me?.id;

  const loadWidgets = useCallback(() => {
    if (!meId) return;
    setWidgetsFailed(false);
    Promise.all([usersApi.profile(meId), usersApi.profile(meId, serverId)]).then(
      ([main, here]) => {
        setMainWidgets(readWidgets(main.widgets));
        const own = here.server_widgets ? readWidgets(here.widgets) : null;
        setWidgets(own);
        setSavedWidgets(own);
      },
      () => setWidgetsFailed(true),
    );
  }, [meId, serverId]);
  useEffect(loadWidgets, [loadWidgets]);

  const aboutOut = useMemo(() => aboutToSave(about), [about]);
  const savedAbout = useMemo(() => aboutToEdit(member?.bio ?? ""), [member?.bio]);
  const theme = profileThemeOf(accent ? hex(accent) : (me?.accent_color ?? null), accent ? hex(accent2) : (me?.accent_color_2 ?? null));
  const pictureId = member?.avatar_id ?? me?.avatar_id ?? null;
  const bannerId = member?.banner_id ?? me?.banner_id ?? null;
  const tint = useDominantColor(theme ? null : (bannerUrl(bannerId, 64) ?? avatarUrl(pictureId, 64)));

  if (!me || !st || !member) return null;
  const server = st.server;
  const canNick = canI(st, P.CHANGE_NICKNAME) || canI(st, P.MANAGE_NICKNAMES);
  const savedAccent = hexOf(member.accent_color);
  const savedAccent2 = hexOf(member.accent_color_2);
  const wordsChanged = nick !== (member.nickname ?? "") || pronouns !== (member.pronouns ?? "") || about !== savedAbout;
  const themeChanged = accent !== savedAccent || (accent !== null && accent2 !== savedAccent2);
  const widgetsChanged = savedWidgets !== undefined && JSON.stringify(widgets) !== JSON.stringify(savedWidgets);
  const dirty = wordsChanged || themeChanged || widgetsChanged;
  const shownName = nick.trim() || me.display_name;
  const glow = theme?.top ?? tint ?? "var(--brand)";
  const shownWidgets = widgets ?? mainWidgets;
  const hasOwn = !!(member.nickname || member.avatar_id || member.banner_id || member.bio || member.pronouns || typeof member.accent_color === "number" || savedWidgets);
  // the first text channel the server has, for the chat view's header
  const channelName = Object.values(channels).filter((c) => c.server_id === serverId && c.type === "text").sort((a, b) => a.position - b.position)[0]?.name ?? "general";

  const save = async () => {
    setSaving(true);
    try {
      const body: MemberPatch = {};
      if (nick !== (member.nickname ?? "")) body.nick = nick.trim() || null;
      if (pronouns !== (member.pronouns ?? "")) body.pronouns = pronouns.trim();
      if (about !== savedAbout) body.bio = aboutOut.trim();
      if (themeChanged) {
        body.accent_color = hex(accent);
        body.accent_color_2 = accent ? hex(accent2) : null;
      }
      if (widgetsChanged) body.widgets = widgets;
      const m = await serversApi.patchMember(serverId, "@me", body);
      upsertMember(m);
      // the server cleans what it keeps, so its copy is the one shown from now on
      setNick(m.nickname ?? "");
      setPronouns(m.pronouns ?? "");
      setAbout(aboutToEdit(m.bio ?? ""));
      setAccent(hexOf(m.accent_color));
      setAccent2(hexOf(m.accent_color_2));
      if (widgetsChanged) loadWidgets();
      toast(t("serverProfile.saved"), "success");
    } catch (err) {
      toast(err instanceof ApiError ? describeError(err, t("serverProfile.uploadFailed")) : t("serverProfile.uploadFailed"), "error");
    } finally {
      setSaving(false);
    }
  };
  const reset = () => {
    setNick(member.nickname ?? "");
    setPronouns(member.pronouns ?? "");
    setAbout(savedAbout);
    setAccent(savedAccent);
    setAccent2(savedAccent2);
    if (savedWidgets !== undefined) setWidgets(savedWidgets);
  };
  const upload = async (kind: "avatar" | "banner", file: File, crop?: { x: number; y: number; w: number; h: number } | null) => {
    if (file.size > 10 * 1024 * 1024) return toast(t("settings.profile.imagesAreLimitedTo10Mb"), "error");
    setUploading(kind);
    try {
      upsertMember(await serversApi.uploadMemberImage(serverId, kind, file, crop));
    } catch (err) {
      toast(err instanceof ApiError ? err.message : t("serverProfile.uploadFailed"), "error");
    } finally {
      setUploading(null);
    }
  };
  const remove = async (kind: "avatar" | "banner") => {
    setUploading(kind);
    try {
      upsertMember(await serversApi.deleteMemberImage(serverId, "@me", kind));
    } catch (err) {
      toast(err instanceof ApiError ? err.message : t("serverProfile.uploadFailed"), "error");
    } finally {
      setUploading(null);
    }
  };
  // everything set for this server goes, and the main profile shows there again
  const useMain = () =>
    openModal("confirm", {
      title: t("serverProfile.designer.useMainTitle", { name: server.name }),
      body: t("serverProfile.designer.useMainBody"),
      confirmLabel: t("serverProfile.designer.useMain"),
      onConfirm: async () => {
        try {
          let m = await serversApi.patchMember(serverId, "@me", { ...(canNick ? { nick: null } : {}), pronouns: "", bio: "", accent_color: null, accent_color_2: null, widgets: null });
          if (m.avatar_id) m = await serversApi.deleteMemberImage(serverId, "@me", "avatar");
          if (m.banner_id) m = await serversApi.deleteMemberImage(serverId, "@me", "banner");
          upsertMember(m);
          setNick(m.nickname ?? "");
          setPronouns("");
          setAbout("");
          setAccent(null);
          setAccent2(null);
          setWidgets(null);
          setSavedWidgets(null);
          toast(t("serverProfile.designer.useMainDone", { name: server.name }), "success");
        } catch (err) {
          toast(err instanceof ApiError ? err.message : t("serverProfile.uploadFailed"), "error");
        }
      },
    });

  const showTab = (next: ProfileTab) => setTab((cur) => ({ tab: next, n: (cur?.n ?? 0) + 1 }));
  // pressing a part of the preview: the form goes to what changes it
  const open = (part: Part) => {
    if (part === "name" && !canNick) return toast(t("serverProfile.designer.nickLocked"), "info");
    requestAnimationFrame(() => jumpToField(formRef.current, part));
  };
  // widgets changed here become this server's own, starting from the ones shown
  const changeWidgets = (fn: (list: Widget[]) => Widget[]) => setWidgets((cur) => fn(cur ?? mainWidgets));
  const widgetTools: WidgetDesign = {
    edit: (id) => {
      const w = shownWidgets.find((x) => x.id === id);
      if (w) setEditing({ widget: w, isNew: false });
    },
    move: (id, delta) =>
      changeWidgets((list) => {
        const at = list.findIndex((w) => w.id === id);
        const to = at + delta;
        if (at < 0 || to < 0 || to >= list.length) return list;
        const next = [...list];
        const [w] = next.splice(at, 1);
        next.splice(to, 0, w!);
        return next;
      }),
    remove: (id) => changeWidgets((list) => list.filter((w) => w.id !== id)),
    feature: (id) =>
      changeWidgets((list) => {
        const w = list.find((x) => x.id === id);
        return w ? [w, ...list.filter((x) => x.id !== id)] : list;
      }),
    add: () => setAdding(true),
  };
  const design: ProfileDesign = { edit: (part) => open(part), fixed: canNick ? FIXED : [...FIXED, "name"] };
  const startWidget = (type: WidgetType) => {
    setAdding(false);
    const id = newWidgetId();
    setEditing({ widget: blankWidget(type, id), isNew: true });
  };
  const doneWidget = (w: Widget) => {
    if (!editing) return;
    changeWidgets((list) => (editing.isNew ? [...list, w] : list.map((x) => (x.id === w.id ? w : x))));
    setEditing(null);
    if (editing.isNew && view === "full") showTab("widgets");
  };

  // whatever is left empty here is the main profile's, and is drawn so
  const preview: Partial<PublicUser> = {
    display_name: shownName,
    pronouns: pronouns.trim() || me.pronouns,
    about_me: aboutOut.trim() ? aboutOut : me.about_me,
    accent_color: accent ? hex(accent) : me.accent_color,
    accent_color_2: accent ? hex(accent2) : me.accent_color_2,
  };
  const views: { id: View; label: string }[] = [
    { id: "card", label: t("designer.view.card") },
    { id: "full", label: t("designer.view.full") },
    { id: "chat", label: t("designer.view.chat") },
  ];
  const look = memberLook(st, me.id);

  const form = (
    <div ref={formRef} className={d.formInner}>
      <FormSection title={t("designer.group.look")}>
        <FormField field="avatar" label={t("serverProfile.picture")} hint={member.avatar_id ? t("designer.imageHint") : t("serverProfile.designer.pictureMainHint")}>
          <div className={d.pictureRow}>
            <Avatar id={me.id} avatarId={pictureId} name={shownName} size={56} />
            <div className={d.popBtns}>
              <Button size="sm" variant="secondary" loading={uploading === "avatar"} onClick={() => avatarRef.current?.click()} data-testid="designer-upload-avatar">
                <ImagePlus size={15} aria-hidden="true" /> {t("serverProfile.changePicture")}
              </Button>
              {member.avatar_id ? <Button size="sm" variant="ghost" onClick={() => void remove("avatar")}>{t("serverProfile.useOwnPicture")}</Button> : null}
            </div>
          </div>
        </FormField>
        <FormField field="banner" label={t("serverProfile.banner")} hint={member.banner_id ? t("designer.imageHint") : t("serverProfile.designer.bannerMainHint")}>
          <div className={d.pictureRow}>
            <span className={d.bannerThumb} style={bannerId ? { backgroundImage: `url(${bannerUrl(bannerId, 340)})` } : { background: theme ? `linear-gradient(180deg, ${theme.top}, ${theme.bottom})` : avatarColor(me.id) }} aria-hidden="true" />
            <div className={d.popBtns}>
              <Button size="sm" variant="secondary" loading={uploading === "banner"} onClick={() => bannerRef.current?.click()} data-testid="designer-upload-banner">
                <ImagePlus size={15} aria-hidden="true" /> {member.banner_id ? t("settings.profile.changeBanner") : t("designer.addBanner")}
              </Button>
              {member.banner_id ? <Button size="sm" variant="ghost" onClick={() => void remove("banner")}>{t("serverProfile.useOwnBanner")}</Button> : null}
            </div>
          </div>
        </FormField>
        <FormField field="colours" label={t("settings.profile.profileTheme")} hint={t("serverProfile.designer.themeHint")}>
          <ColoursPicker accent={accent} accent2={accent2} setAccent={setAccent} setAccent2={setAccent2} />
        </FormField>
      </FormSection>

      <FormSection title={t("designer.group.name")}>
        <FormField field="name" label={<label htmlFor="designer-name">{t("modals.serverProfile.nickname")}</label>} hint={canNick ? undefined : t("serverProfile.designer.nickLocked")}>
          <TextInput id="designer-name" value={nick} onChange={(e) => setNick(e.target.value)} maxLength={32} placeholder={me.display_name} disabled={!canNick} data-testid="designer-name" />
        </FormField>
        <FormField field="pronouns" label={<label htmlFor="designer-pronouns">{t("settings.profile.pronouns")}</label>}>
          <TextInput id="designer-pronouns" value={pronouns} onChange={(e) => setPronouns(e.target.value)} maxLength={40} placeholder={me.pronouns || t("settings.profile.addYourPronouns")} data-testid="designer-pronouns" />
        </FormField>
      </FormSection>

      <FormSection title={t("designer.group.about")}>
        <FormField field="about" label={t("settings.profile.aboutMe")}>
          <AboutField value={about} onChange={setAbout} outLength={aboutOut.length} placeholder={me.about_me ? plainText(parse(me.about_me)).replace(/\s+/g, " ").trim() : undefined} />
        </FormField>
        <FormField
          field="widgets"
          label={t("widgets.title")}
          hint={widgets === null ? t("serverProfile.designer.widgetsMainHint") : undefined}
          aside={widgets !== null && savedWidgets !== undefined ? <button type="button" className={d.fieldLink} onClick={() => setWidgets(null)} data-testid="server-profile-main-widgets">{t("serverProfile.designer.mainWidgets")}</button> : null}
        >
          {widgetsFailed ? (
            <div className={d.notice} role="status">
              <span>{t("widgets.couldNotLoad")}</span>
              <Button size="sm" variant="secondary" onClick={loadWidgets}>{t("invite.tryAgain")}</Button>
            </div>
          ) : (
            <WidgetList widgets={shownWidgets} tools={widgetTools} disabled={savedWidgets === undefined} />
          )}
        </FormField>
      </FormSection>
    </div>
  );

  const previewPane = (
    <>
      <ViewSwitch views={views} value={view} onChange={setView} />
      <div className={d.stage} data-view={view} style={{ "--glow": glow } as CSSProperties} data-testid="designer-stage">
        {view === "full" ? (
          <ProfileCard key={tab ? `${tab.tab}-${tab.n}` : "profile"} userId={me.id} serverId={serverId} variant="modal" preview={preview} previewWidgets={shownWidgets} design={design} initialTab={tab?.tab} />
        ) : view === "card" ? (
          <div className={d.cardFrame}>
            <ProfileCard userId={me.id} serverId={serverId} variant="popout" preview={preview} previewWidgets={shownWidgets} design={design} />
          </div>
        ) : (
          <ChatPreview name={shownName} avatarId={pictureId} userId={me.id} typing={readTypingStyle(me.typing_style)} channel={channelName} look={look} />
        )}
      </div>
      <p className={d.caption}>{t(`serverProfile.caption.${view}`, { name: server.name })}</p>
    </>
  );

  return (
    <>
      <div className={d.serverHead}>
        <ServerPicker serverId={serverId} servers={servers} onServer={onServer} />
        {hasOwn ? (
          <Button size="sm" variant="ghost" onClick={useMain} data-testid="server-profile-use-main">{t("serverProfile.designer.useMain")}</Button>
        ) : null}
      </div>
      <p className={d.intro}>{t("serverProfile.designer.introForm", { name: server.name })}</p>
      <DesignerLayout
        layout={layout}
        form={form}
        preview={previewPane}
        summary={<><Avatar id={me.id} avatarId={pictureId} name={shownName} size={28} /><span className={d.summaryName} dir="auto">{shownName}</span></>}
      />
      {uploading ? (
        <p className={d.uploading} role="status"><LoaderCircle size={14} className={d.spin} aria-hidden="true" /> {t("common.working")}</p>
      ) : null}

      <input ref={avatarRef} type="file" accept="image/png,image/jpeg,image/gif,image/webp" hidden onChange={(e) => { const f = e.target.files?.[0]; if (f) setCrop({ file: f, kind: "avatar" }); e.target.value = ""; }} />
      <input ref={bannerRef} type="file" accept="image/png,image/jpeg,image/gif,image/webp" hidden onChange={(e) => { const f = e.target.files?.[0]; if (f) setCrop({ file: f, kind: "banner" }); e.target.value = ""; }} />


      {adding ? <AddWidgetDialog widgets={shownWidgets} onPick={startWidget} onClose={() => setAdding(false)} /> : null}
      {editing && isGameWidget(editing.widget) ? <GameWidgetDialog initial={editing.widget} onDone={doneWidget} onClose={() => setEditing(null)} /> : null}
      {editing && isMusicWidget(editing.widget) ? <MusicWidgetDialog initial={editing.widget} onDone={doneWidget} onClose={() => setEditing(null)} /> : null}
      {editing && editing.widget.type === "custom" ? <CardDialog initial={editing.widget} isNew={editing.isNew} onDone={doneWidget} onClose={() => setEditing(null)} /> : null}
      {editing && editing.widget.type === "countdown" ? <CountdownDialog initial={editing.widget} isNew={editing.isNew} onDone={doneWidget} onClose={() => setEditing(null)} /> : null}
      {editing && editing.widget.type === "localtime" ? <LocalTimeDialog initial={editing.widget} isNew={editing.isNew} onDone={doneWidget} onClose={() => setEditing(null)} /> : null}
      {editing && editing.widget.type === "stickers" ? <StickersDialog initial={editing.widget} isNew={editing.isNew} onDone={doneWidget} onClose={() => setEditing(null)} /> : null}
      {editing && isBookWidget(editing.widget) ? <BookWidgetDialog initial={editing.widget} onDone={doneWidget} onClose={() => setEditing(null)} /> : null}
      {editing && isScreenWidget(editing.widget) ? <ScreenWidgetDialog initial={editing.widget} onDone={doneWidget} onClose={() => setEditing(null)} /> : null}

      {crop ? (
        <ImageCropModal
          file={crop.file}
          aspect={crop.kind === "avatar" ? 1 : 600 / 240}
          round={crop.kind === "avatar"}
          maxSize={crop.kind === "avatar" ? 1024 : 1920}
          title={crop.kind === "avatar" ? t("settings.profile.cropYourAvatar") : t("settings.profile.cropYourBanner")}
          serverCrop
          onClose={() => setCrop(null)}
          onDone={({ blob, crop: area }) => {
            const kind = crop.kind;
            setCrop(null);
            void upload(kind, new File([blob], kind + (blob.type === "image/png" ? ".png" : ""), { type: blob.type }), area ?? null);
          }}
        />
      ) : null}
      <SaveBar dirty={dirty} busy={saving} onReset={reset} onSave={() => void save()} label={t("settings.profile.saveChanges")} />
    </>
  );
}
