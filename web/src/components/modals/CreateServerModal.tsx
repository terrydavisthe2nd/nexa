import { ChevronRight, Hash, Images, Lock, Megaphone, MessagesSquare, PanelsTopLeft, Volume2 } from "lucide-react";
import { useEffect, useRef, useState } from "react";
import { useNavigate } from "react-router-dom";
import { ApiError } from "@/api/client";
import { servers as serversApi, type DiscordTemplatePreview, type DiscordTemplatePreviewChannel } from "@/api/servers";
import type { ServerView } from "@/api/types";
import { Button } from "@/components/common/Button";
import { IconPicker, type PickedIcon } from "@/components/common/IconPicker";
import { Modal } from "@/components/common/Modal";
import { Field, TextInput } from "@/components/common/TextInput";
import { t } from "@/i18n";
import { initials } from "@/lib/avatarColor";
import { guarded } from "@/lib/captcha";
import { isDiscordTemplateUrl, parseDiscordTemplate } from "@/lib/discordTemplate";
import { useAuth } from "@/stores/auth";
import { useChannels } from "@/stores/channels";
import { useKeys } from "@/stores/keys";
import { useServers } from "@/stores/servers";
import { useUI } from "@/stores/ui";
import s from "./ServerModals.module.css";

function ChannelGlyph({ type }: { type: DiscordTemplatePreviewChannel["type"] }) {
  if (type === "voice") return <Volume2 size={13} />;
  if (type === "announcement") return <Megaphone size={13} />;
  if (type === "forum") return <MessagesSquare size={13} />;
  if (type === "media") return <Images size={13} />;
  return <Hash size={13} />;
}

function roleHex(color: number): string {
  if (!color || color <= 0) return "var(--text-muted)";
  return `#${color.toString(16).padStart(6, "0")}`;
}

export function CreateServerModal({ onClose }: { onClose: () => void }) {
  const nav = useNavigate();
  const me = useAuth((a) => a.me);
  const toast = useUI((u) => u.toast);
  const openModal = useUI((u) => u.openModal);
  const [mode, setMode] = useState<"create" | "join" | "template">("create");
  const [name, setName] = useState(() => (me ? t("modals.createServer.namedServer", { name: me.display_name || me.username }) : t("modals.createServer.myServer")));
  const [code, setCode] = useState("");
  const [icon, setIcon] = useState<PickedIcon | null>(null);
  const [busy, setBusy] = useState(false);

  const [templateUrl, setTemplateUrl] = useState("");
  const [templateName, setTemplateName] = useState("");
  const [templateNameEdited, setTemplateNameEdited] = useState(false);
  const [preview, setPreview] = useState<DiscordTemplatePreview | null>(null);
  const [previewLoading, setPreviewLoading] = useState(false);
  const [templateErr, setTemplateErr] = useState<string | null>(null);
  const previewSeq = useRef(0);

  useEffect(() => {
    if (mode !== "template") return;
    const raw = templateUrl.trim();
    if (!raw) {
      setPreview(null);
      setPreviewLoading(false);
      setTemplateErr(null);
      return;
    }
    const parsed = parseDiscordTemplate(raw);
    if (!parsed.ok) {
      setPreview(null);
      setPreviewLoading(false);
      setTemplateErr(parsed.reason === "invite" ? t("modals.createServer.inviteNotTemplate") : null);
      return;
    }
    setTemplateErr(null);
    setPreviewLoading(true);
    const seq = ++previewSeq.current;
    const timer = setTimeout(() => {
      void serversApi
        .previewDiscordTemplate(parsed.code)
        .then((res) => {
          if (seq !== previewSeq.current) return;
          setPreview(res);
          setTemplateName((prev) => (templateNameEdited && prev.trim() ? prev : res.server_name));
        })
        .catch((err) => {
          if (seq !== previewSeq.current) return;
          setPreview(null);
          setTemplateErr(err instanceof ApiError ? err.message : t("modals.createServer.couldNotCreateTheServer"));
        })
        .finally(() => {
          if (seq === previewSeq.current) setPreviewLoading(false);
        });
    }, 250);
    return () => clearTimeout(timer);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [mode, templateUrl]);

  const finishCreatedServer = async (view: ServerView) => {
    useChannels.getState().upsert(view.channels);
    useServers.getState().setFromView(view);
    for (const ch of view.channels) {
      if (ch.type !== "category" && ch.key_conv_id === ch.id) {
        await useKeys.getState().ensureConvKey(ch.id).catch(() => null);
      }
    }
    // the icon goes up once the server exists; a failure there still leaves the server made
    if (icon) {
      try {
        useServers.getState().updateServer(await serversApi.uploadIcon(view.id, icon.file, icon.crop));
      } catch (err) {
        toast(err instanceof ApiError ? t("modals.createServer.theServerWasMadeButItsIcon", { error: err.message }) : t("modals.createServer.theServerWasMadeButIts"), "error");
      }
    }
    onClose();
    const first =
      (view.system_channel_id ? view.channels.find((c) => c.id === view.system_channel_id) : undefined) ??
      view.channels.find((c) => c.type === "text" || c.type === "announcement" || c.type === "forum" || c.type === "media");
    nav(first ? `/channels/${view.id}/${first.id}` : `/channels/${view.id}`);
  };

  const create = async () => {
    const clean = name.trim();
    if (!clean || busy) return;
    setBusy(true);
    try {
      const view = await guarded("create_server", (captcha) => serversApi.create(clean, captcha));
      await finishCreatedServer(view);
    } catch (err) {
      toast(err instanceof ApiError ? err.message : t("modals.createServer.couldNotCreateTheServer"), "error");
    } finally {
      setBusy(false);
    }
  };

  const importTemplate = async () => {
    const raw = templateUrl.trim();
    if (!raw || busy) return;
    const parsed = parseDiscordTemplate(raw);
    if (!parsed.ok) {
      setTemplateErr(parsed.reason === "invite" ? t("modals.createServer.inviteNotTemplate") : t("modals.createServer.invalidTemplateLink"));
      return;
    }
    setBusy(true);
    setTemplateErr(null);
    try {
      const custom = templateName.trim();
      const view = await guarded("create_server", (captcha) => serversApi.importDiscordTemplate(raw, custom || undefined, captcha));
      await finishCreatedServer(view);
    } catch (err) {
      setTemplateErr(err instanceof ApiError ? err.message : t("modals.createServer.couldNotCreateTheServer"));
    } finally {
      setBusy(false);
    }
  };

  const join = () => {
    // a pasted link often ends in a space, which would cut the key off the end of the hash
    const c = code.trim();
    if (isDiscordTemplateUrl(c)) {
      setTemplateUrl(c);
      setMode("template");
      return;
    }
    const m = /(?:invite\/)?([A-Za-z0-9_-]{4,32})(?:[#?].*)?$/.exec(c);
    if (!m) return toast(t("modals.createServer.thatDoesnTLookLikeAn"), "error");
    const hash = c.includes("#") ? c.slice(c.indexOf("#")) : "";
    onClose();
    // the same dialog an invite link opens, over the app rather than instead of it
    openModal("joinInvite", { code: m[1], hash });
  };

  if (mode === "join") {
    return (
      <Modal open onClose={onClose} title={t("modals.createServer.joinAServer")} width={440} footer={<><Button variant="ghost" onClick={() => setMode("create")}>{t("modals.createServer.back")}</Button><Button onClick={join} disabled={!code.trim()}>{t("modals.createServer.joinServer")}</Button></>}>
        <div className={s.form}>
          <p className={s.hint}>{t("modals.createServer.enterAnInviteBelowToJoin")}</p>
          <Field label={t("modals.createServer.inviteLink")} required>
            <TextInput value={code} onChange={(e) => setCode(e.target.value)} placeholder={`${window.location.origin}/invite/hTKzmak`} autoFocus onKeyDown={(e) => e.key === "Enter" && join()} />
          </Field>
          <p className={s.fine}>{t("modals.createServer.invitesLookLike", { example: `${window.location.origin}/invite/hTKzmak` })}</p>
        </div>
      </Modal>
    );
  }

  if (mode === "template") {
    const categories = preview ? preview.channels.filter((c) => c.type === "category") : [];
    const loose = preview ? preview.channels.filter((c) => c.type !== "category" && c.parent_index < 0) : [];
    const chanCount = preview ? preview.channels.filter((c) => c.type !== "category").length : 0;
    const effectiveName = templateName.trim() || preview?.server_name || name || "S";
    return (
      <Modal
        open
        onClose={onClose}
        title={t("modals.createServer.importTemplate")}
        width={460}
        footer={
          <>
            <Button variant="ghost" onClick={() => setMode("create")}>
              {t("modals.createServer.back")}
            </Button>
            <Button onClick={() => void importTemplate()} loading={busy} disabled={!templateUrl.trim()} data-testid="discord-template-submit">
              {t("modals.createServer.create")}
            </Button>
          </>
        }
      >
        <div className={s.form}>
          <p className={s.hint}>{t("modals.createServer.importTemplateHint")}</p>
          <Field label={t("modals.createServer.templateLink")} required>
            <TextInput
              value={templateUrl}
              onChange={(e) => setTemplateUrl(e.target.value)}
              placeholder={t("modals.createServer.templatePlaceholder")}
              autoComplete="off"
              spellCheck={false}
              autoFocus
              onKeyDown={(e) => e.key === "Enter" && void importTemplate()}
              data-testid="discord-template-url"
            />
          </Field>
          {templateErr ? (
            <p role="alert" className={s.errorText} data-testid="discord-template-error">
              {templateErr}
            </p>
          ) : null}
          {previewLoading && !preview ? <p className={s.fine}>{t("modals.createServer.readingTemplate")}</p> : null}
          {!preview && !previewLoading && !templateErr ? <p className={s.fine}>{t("modals.createServer.templatesLookLike")}</p> : null}
          {preview ? (
            <>
              <div className={s.templateCard} data-testid="discord-template-preview">
                <div>
                  <p className={s.templateTitle}>{preview.name}</p>
                  <p className={s.templateMeta}>
                    {t("modals.createServer.templateSummary", {
                      channels: chanCount,
                      categories: categories.length,
                      roles: preview.roles.length,
                    })}
                  </p>
                  {preview.description ? <p className={s.templateDesc}>{preview.description}</p> : null}
                </div>
                {preview.roles.length > 0 ? (
                  <div className={s.templateRoles}>
                    {preview.roles.map((r, idx) => (
                      <span key={idx} className={s.rolePill}>
                        <span className={s.roleDot} style={{ background: roleHex(r.color) }} />
                        <span>{r.name}</span>
                      </span>
                    ))}
                  </div>
                ) : null}
                <div className={s.templateTree}>
                  {loose.length > 0 ? (
                    <div className={s.templateChans}>
                      {loose.map((ch) => (
                        <span key={ch.id} className={s.templateChan}>
                          <ChannelGlyph type={ch.type} />
                          <span>{ch.name}</span>
                          {ch.private ? <Lock size={11} /> : null}
                        </span>
                      ))}
                    </div>
                  ) : null}
                  {categories.map((cat) => {
                    const children = preview.channels.filter((c) => c.type !== "category" && c.parent_index === cat.id);
                    return (
                      <div key={cat.id} style={{ display: "flex", flexDirection: "column", gap: 4 }}>
                        <div className={s.templateCat}>{cat.name}</div>
                        {children.length > 0 ? (
                          <div className={s.templateChans}>
                            {children.map((ch) => (
                              <span key={ch.id} className={s.templateChan}>
                                <ChannelGlyph type={ch.type} />
                                <span>{ch.name}</span>
                                {ch.private ? <Lock size={11} /> : null}
                              </span>
                            ))}
                          </div>
                        ) : null}
                      </div>
                    );
                  })}
                </div>
              </div>
              <div style={{ display: "flex", justifyContent: "center" }}>
                <IconPicker currentUrl={null} picked={icon} onPick={setIcon} onRemove={() => setIcon(null)} initials={initials(effectiveName)} label={t("modals.createServer.serverIcon")} testId="template-server-icon" />
              </div>
              <Field label={t("modals.createServer.serverName")} required>
                <TextInput
                  value={templateName}
                  onChange={(e) => {
                    setTemplateNameEdited(true);
                    setTemplateName(e.target.value);
                  }}
                  maxLength={100}
                  onKeyDown={(e) => e.key === "Enter" && void importTemplate()}
                  data-testid="discord-template-name"
                />
              </Field>
            </>
          ) : null}
        </div>
      </Modal>
    );
  }

  return (
    <Modal open onClose={onClose} title={t("modals.createServer.createYourServer")} width={440} footer={<><Button variant="ghost" onClick={() => setMode("join")}>{t("modals.createServer.haveAnInviteAlready")}</Button><Button onClick={() => void create()} loading={busy} disabled={!name.trim()} data-testid="create-server-submit">{t("modals.createServer.create")}</Button></>}>
      <div className={s.form}>
        <p className={s.hint}>{t("modals.createServer.yourServerIsWhereYouAnd")}</p>
        <div style={{ display: "flex", justifyContent: "center" }}>
          <IconPicker currentUrl={null} picked={icon} onPick={setIcon} onRemove={() => setIcon(null)} initials={initials(name || "S")} label={t("modals.createServer.serverIcon")} testId="server-icon" />
        </div>
        <Field label={t("modals.createServer.serverName")} required>
          <TextInput value={name} onChange={(e) => setName(e.target.value)} maxLength={100} autoFocus onKeyDown={(e) => e.key === "Enter" && void create()} data-testid="server-name" />
        </Field>
        <button type="button" className={s.actionCard} onClick={() => setMode("template")} data-testid="open-discord-template">
          <span className={s.typeIcon}><PanelsTopLeft size={20} /></span>
          <span className={s.typeText}>
            <span className={s.typeLabel}>{t("modals.createServer.importTemplate")}</span>
            <span className={s.typeDesc}>{t("modals.createServer.importTemplateDesc")}</span>
          </span>
          <span className={s.typeIcon}><ChevronRight size={18} /></span>
        </button>
        <p className={s.fine}>{t("modals.createServer.byCreatingAServerYouAgree")}</p>
      </div>
    </Modal>
  );
}
