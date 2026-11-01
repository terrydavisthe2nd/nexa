import { PersonName } from "@/components/common/PersonName";
import { useMediaAnimation } from "@/lib/mediaAnimation";
import { touchFirst } from "@/lib/touch";
import { NexaProfile } from "@/components/nexa/NexaProfile";
import { isSystemAccount } from "@/lib/moderation";
import { CirclePlus, Link2, LoaderCircle, Maximize2, MessageCircle, MoreHorizontal, NotebookPen, Pencil, Plus, SendHorizontal, UserPlus, X } from "lucide-react";
import { apps as appsApi, type AppSummary } from "@/api/apps";
import { HookTags } from "@/components/chat/webhook/HookHeader";
import { isBot } from "@/lib/botCopies";
import { useEffect, useId, useLayoutEffect, useMemo, useRef, useState, type CSSProperties, type KeyboardEvent, type MouseEvent, type PointerEvent, type ReactNode } from "react";
import { useLocation, useNavigate } from "react-router-dom";
import { ApiError } from "@/api/client";
import { dmOpenError } from "@/lib/errors";
import { channels as channelsApi } from "@/api/channels";
import { relationships as relApi } from "@/api/relationships";
import { sendFriendRequest, useBusyWith } from "@/lib/friendship";
import type { PublicUser } from "@/api/types";
import { avatarUrl, bannerUrl, iconUrl, users as usersApi } from "@/api/users";
import { ActivityList } from "@/components/activity/ActivityCard";
import { StatusEmoji } from "@/components/activity/StatusEmoji";
import { Avatar } from "@/components/common/Avatar";
import { Lightbox } from "@/components/chat/Lightbox";
import { PlanBadge, StaffBadge } from "@/components/common/OwnerBadge";
import { rankOf } from "@/lib/staffRank";
import { ServerTagChip } from "@/components/servertag/ServerTagChip";
import { ProfileWidgets, WidgetPeek, type WidgetDesign } from "./Widgets";
import { readWidgets, type Widget } from "@/lib/widgets";
import { Tooltip } from "@/components/common/Tooltip";
import { promptFields } from "@/components/modals/PromptModal";
import { KeyStatusChip } from "./KeyVerify";
import { RelationshipBar, relationshipLine } from "./RelationshipBar";
import { profileTheme, profileThemeOf } from "@/lib/avatarColor";
import { copyProfileLink } from "@/lib/profileLinks";
import { useDominantColor } from "@/lib/dominantColor";
import { formatShortDate } from "@/lib/time";
import { useRenderCtx } from "@/lib/markdownCtx";
import { Markdown } from "@/markdown";
import { useAuth } from "@/stores/auth";
import { useChannels } from "@/stores/channels";
import { activitiesOf, customStatus, statusOf, usePresence } from "@/stores/presence";
import { useRelationships } from "@/stores/relationships";
import { canI, memberHighest, rolesByPosition, useServers } from "@/stores/servers";
import { canActOn, canManageRole, P } from "@/lib/perms";
import { sendMessage } from "@/components/composer/send";
import { servers as serversApi } from "@/api/servers";
import { useSettings } from "@/stores/settings";
import { useUI } from "@/stores/ui";
import { displayName, useUsers } from "@/stores/users";
import { nameClass, nameStyle } from "@/lib/roleLook";
import { withOwn } from "@/lib/useMemberLook";
import s from "./Profile.module.css";
import { profileAddRoleMenu, profileMoreMenu } from "./profileMenus";

import { t, Tr } from "@/i18n";
// in the app's language, not the browser's (lib/time)
const dateOf = formatShortDate;

interface Props {
  userId: string;
  /**
   * The full profile ("modal": two columns with tabs, or one on a phone);
   * the preview in the column beside a DM ("panel"); or the popout a name or
   * a picture opens ("popout"): small, beside what was pressed, with only
   * what matters at a glance and the way to the full one.
   */
  variant: "modal" | "panel" | "popout";
  onClose?: () => void;
  /** Settings' live preview: unsaved changes over the account's own profile, with nothing to click. */
  preview?: Partial<PublicUser>;
  /** The widgets the preview shows, as they are being edited. */
  previewWidgets?: Widget[];
  /** The server it was opened from; otherwise the server being looked at, if any. */
  serverId?: string;
  /**
   * A profile opened from its link by someone who may have no account
   * (pages/ProfilePage): the card as the app draws it, from the profile the
   * link shows, with the visitor's own way in where the person's buttons go,
   * and nothing that needs an account (status, notes, mutuals, the key).
   */
  visitor?: { user: PublicUser; widgets?: unknown; actions: ReactNode };
  /** The full profile's tab to open on, when it is opened for one thing (all their widgets, what you have in common). */
  initialTab?: ProfileTab | undefined;
  /** Drawn in the profile designer, every part editable (with preview). */
  design?: ProfileDesign | undefined;
  /**
   * The popout laid in a message, under a link to the profile
   * (chat/ProfileCards): the same card, sitting in the chat instead of
   * floating over it.
   */
  embedded?: boolean;
}

/** The server whose page is open, which a profile opened from it belongs to. */
function useServerInView(explicit: string | undefined): string | null {
  const loc = useLocation();
  const fromPath = /^\/channels\/(\d+)(?:\/|$)/.exec(loc.pathname)?.[1] ?? null;
  const id = explicit ?? fromPath;
  return useServers((st) => (id && st.byId[id] ? id : null));
}

/**
 * Someone's roles in the server their profile was opened from, highest first,
 * each in its colour, and when they joined it. A big server's members arrive a
 * page at a time, so one who is not loaded yet is asked for. In a popout the
 * roles are all it says, and whoever may manage them takes one off with its x
 * or gives another with the + after them, as the member's Roles dialog would.
 */
function ServerRoles({ serverId, userId, popout }: { serverId: string; userId: string; popout?: boolean }) {
  const server = useServers((st) => st.byId[serverId]);
  const member = server?.members[userId];
  const upsertMember = useServers((st) => st.upsertMember);
  const meId = useAuth((a) => a.me?.id);
  const toast = useUI((u) => u.toast);
  const [busy, setBusy] = useState<string | null>(null);
  const [missing, setMissing] = useState(false);
  useEffect(() => {
    if (member || missing) return;
    let alive = true;
    serversApi
      .member(serverId, userId)
      .then((m) => alive && upsertMember(m))
      // not (or no longer) a member: there is nothing of theirs here to show
      .catch(() => alive && setMissing(true));
    return () => {
      alive = false;
    };
  }, [serverId, userId, member, missing, upsertMember]);
  if (!server || !member) return null;
  const roles = member.role_ids
    .map((id) => server.roles[id])
    .filter((r): r is NonNullable<typeof r> => !!r && r.id !== serverId)
    .sort((a, b) => b.position - a.position);
  const colour = (c: number | null | undefined) => (c ? { background: `#${c.toString(16).padStart(6, "0")}` } : undefined);
  if (popout) {
    // who may change them: Manage Roles, and above the member unless it is themselves, and then only roles below their own highest
    const mine = meId ? memberHighest(server, meId) : { highest: 0, isOwner: false };
    const theirs = memberHighest(server, userId);
    const canRoles = !!meId && canI(server, P.MANAGE_ROLES) && (meId === userId || canActOn(mine.highest, theirs.highest, mine.isOwner, theirs.isOwner));
    const allowed = (position: number) => canRoles && canManageRole(mine.highest, mine.isOwner, position);
    const addable = rolesByPosition(server).filter((r) => r.id !== serverId && !member.role_ids.includes(r.id) && allowed(r.position));
    const change = async (roleId: string, on: boolean) => {
      setBusy(roleId);
      try {
        upsertMember(on ? await serversApi.addRole(serverId, userId, roleId) : await serversApi.removeRole(serverId, userId, roleId));
      } catch (err) {
        toast(err instanceof ApiError ? err.message : t("server.memberAction.couldNotChangeRoles"), "error");
      } finally {
        setBusy(null);
      }
    };
    if (!roles.length && !(canRoles && addable.length)) return null;
    return (
      <ul className={s.roles} aria-label={t("profile.profileCard.rolesIn", { server: server.server.name })} data-testid="profile-roles">
        {roles.map((r) => (
          <li key={r.id} className={s.role} data-busy={busy === r.id || undefined}>
            <span className={s.roleDot} style={colour(r.color)} aria-hidden="true" />
            <span className={s.roleName} dir="auto">{r.name}</span>
            {allowed(r.position) ? (
              <button type="button" className={s.roleX} aria-label={t("profile.roles.remove", { role: r.name })} disabled={busy !== null} onClick={() => void change(r.id, false)} data-testid="role-remove">
                <X size={12} />
              </button>
            ) : null}
          </li>
        ))}
        {canRoles && addable.length ? (
          <li>
            <Tooltip text={t("profile.roles.add")} side="top">
              <button
                type="button"
                className={s.roleAdd}
                aria-label={t("profile.roles.add")}
                aria-haspopup="menu"
                disabled={busy !== null}
                onClick={(e) => {
                  const at = e.currentTarget.getBoundingClientRect();
                  profileAddRoleMenu.open({ roles: addable, add: (roleId) => void change(roleId, true) }, { x: at.left, y: at.bottom + 4 });
                }}
                data-testid="role-add"
              >
                <Plus size={14} />
              </button>
            </Tooltip>
          </li>
        ) : null}
      </ul>
    );
  }
  return (
    <>
      <div className={s.section} data-testid="profile-roles">
        <h3>{t("profile.profileCard.rolesIn", { server: server.server.name })}</h3>
        {roles.length ? (
          <ul className={s.roles}>
            {roles.map((r) => (
              <li key={r.id} className={s.role}>
                <span className={s.roleDot} style={colour(r.color)} aria-hidden="true" />
                <span className={s.roleName} dir="auto">{r.name}</span>
              </li>
            ))}
          </ul>
        ) : (
          <p className={s.noRoles}>{t("profile.profileCard.noRoles")}</p>
        )}
      </div>
      <div className={s.section}>
        <h3>{t("profile.profileCard.joinedServer", { server: server.server.name })}</h3>
        <p>{dateOf(member.joined_at)}</p>
      </div>
    </>
  );
}

/**
 * The popout's own way to say something: a message to them, sent in your DM
 * with them (begun if there is none yet), which then opens.
 */
function MessageBox({ user, name, onSent }: { user: PublicUser; name: string; onSent?: (() => void) | undefined }) {
  const nav = useNavigate();
  const upsertChannel = useChannels((c) => c.upsert);
  const toast = useUI((u) => u.toast);
  const [text, setText] = useState("");
  const [sending, setSending] = useState(false);
  const send = async () => {
    const body = text.trim();
    if (!body || sending) return;
    setSending(true);
    try {
      const ch = await channelsApi.createDM([user.id]);
      upsertChannel(ch);
      // sealed and sent as the message box would; it shows in the DM at once, and says so there if it fails
      void sendMessage(ch.id, body);
      setText("");
      onSent?.();
      nav(`/channels/@me/${ch.id}`);
    } catch (err) {
      toast(dmOpenError(err, t("profile.profileCard.couldNotOpenAConversation")), "error");
    } finally {
      setSending(false);
    }
  };
  return (
    <form
      className={s.msgBox}
      onSubmit={(e) => {
        e.preventDefault();
        void send();
      }}
    >
      <input
        className={s.msgInput}
        value={text}
        onChange={(e) => setText(e.target.value)}
        placeholder={t("profile.popout.message", { name })}
        aria-label={t("profile.popout.message", { name })}
        maxLength={4000}
        data-testid="popout-message"
      />
      <button type="submit" className={s.msgSend} aria-label={t("profile.popout.send")} disabled={!text.trim() || sending}>
        {sending ? <LoaderCircle size={16} className={s.spin} /> : <SendHorizontal size={16} />}
      </button>
    </form>
  );
}

/** A round button laid over the banner, as Discord's preview has them. */
function BannerButton({ label, onClick, children, testId, busy }: { label: string; onClick: (e: MouseEvent<HTMLButtonElement>) => void; children: ReactNode; testId?: string; busy?: boolean }) {
  return (
    <Tooltip text={label} side="bottom">
      <button className={s.bannerBtn} aria-label={label} onClick={onClick} data-testid={testId} disabled={busy} aria-busy={busy || undefined}>
        {children}
      </button>
    </Tooltip>
  );
}

/** A part of a profile the profile designer (Settings, Profiles) changes when it is pressed. */
export type ProfilePart = "banner" | "avatar" | "name" | "pronouns" | "tag" | "status" | "about";

/**
 * The profile as the profile designer draws it: every part a button that
 * opens what changes it, the ones being written drawn as their editors, and
 * each empty part showing what it could hold.
 */
export interface ProfileDesign {
  /** Opens what changes a part, beside the part pressed. */
  edit: (part: ProfilePart, anchor: HTMLElement) => void;
  /** Parts drawn as they are, with nothing to press: a server profile's status and tag are the account's. */
  fixed?: ProfilePart[];
  /** Parts being written where they are: the editor drawn in each one's place. */
  inline?: Partial<Record<"name" | "pronouns" | "about", ReactNode>>;
  /** The Widgets tab's tools. */
  widgets?: WidgetDesign;
}

/** A part of the profile the designer changes: an outline and a label on hover or focus (always on a touch screen). */
function Spot({ design, part, label, round }: { design: ProfileDesign; part: ProfilePart; label: string; round?: boolean }) {
  if (design.fixed?.includes(part)) return null;
  return (
    <button type="button" className={s.spot} data-round={round || undefined} data-spot={part} aria-label={label} onClick={(e) => design.edit(part, e.currentTarget)}>
      <span className={s.spotLabel} aria-hidden="true"><Pencil size={11} /><span className={s.spotText}>{label}</span></span>
    </button>
  );
}

/** The parts of the full profile behind its tabs. "about" is a tab only where the card is too narrow for two columns. */
export type ProfileTab = "about" | "widgets" | "activity" | "mutuals";

/** Below this width the full profile is one column, with what it says about the person behind a tab of its own. */
const TWO_COLUMNS = 520;
/** Below this width the two columns are tight: the left one is under 300px, so the avatar is drawn smaller. */
const ROOMY = 680;

/**
 * A custom status in a thought bubble beside the avatar. A long one is cut to
 * a few lines there, and opens to every word on a hover, a press or a key.
 */
function StatusBubble({ status, design }: { status: { text: string; emoji: string }; design: ProfileDesign | undefined }) {
  const words = useRef<HTMLSpanElement>(null);
  const [cut, setCut] = useState(false);
  const [open, setOpen] = useState(false);
  const mouse = useRef(false);
  // measured again whenever the bubble's size settles (a card that opens grows into its width)
  useLayoutEffect(() => {
    const el = words.current;
    if (!el || open) return;
    const measure = () => setCut(el.scrollHeight > el.clientHeight + 1);
    measure();
    if (typeof ResizeObserver === "undefined") return;
    const ro = new ResizeObserver(measure);
    ro.observe(el);
    return () => ro.disconnect();
  }, [status.text, status.emoji, open]);
  const opens = cut && !design;
  return (
    <div className={s.bubble} data-open={open || undefined} data-testid="profile-status">
      <div
        className={s.bubbleText}
        {...(opens
          ? {
              role: "button",
              tabIndex: 0,
              "aria-expanded": open,
              onPointerEnter: (e: PointerEvent) => {
                mouse.current = e.pointerType === "mouse";
                if (mouse.current) setOpen(true);
              },
              onPointerLeave: (e: PointerEvent) => {
                if (e.pointerType === "mouse") setOpen(false);
              },
              // a mouse opened it on the way in; a finger opens and closes it
              onClick: () => {
                if (!mouse.current) setOpen((o) => !o);
              },
              onBlur: () => setOpen(false),
              onKeyDown: (e: KeyboardEvent) => {
                if (e.key === "Enter" || e.key === " ") {
                  e.preventDefault();
                  setOpen((o) => !o);
                } else if (e.key === "Escape" && open) {
                  e.stopPropagation();
                  setOpen(false);
                }
              },
            }
          : {})}
      >
        {/* one inline run, so the emoji stays on the text's first line and the clamp counts lines */}
        <span ref={words} className={s.bubbleWords}>
          {status.emoji ? <StatusEmoji emoji={status.emoji} className={s.bubbleEmoji} /> : null}
          {status.text}
        </span>
        {design ? <Spot design={design} part="status" label={t("designer.spot.status")} /> : null}
      </div>
    </div>
  );
}

/**
 * How wide the full profile is, measured on the card itself: a dialog on a
 * desktop, a phone's sheet and the page a profile link opens all hold it at
 * different widths, and that decides one column or two.
 */
function useCardWidth(el: HTMLElement | null, on: boolean): number {
  const [width, setWidth] = useState(() => (typeof window !== "undefined" ? window.innerWidth - 64 : ROOMY));
  useLayoutEffect(() => {
    if (!on || !el) return;
    const measure = () => setWidth(el.clientWidth);
    measure();
    if (typeof ResizeObserver === "undefined") return;
    const ro = new ResizeObserver(measure);
    ro.observe(el);
    return () => ro.disconnect();
  }, [el, on]);
  return width;
}

/**
 * Someone's profile. The preview beside a DM is one column: the banner, the
 * avatar sitting in a hole cut out of it, a custom status in a bubble beside
 * the avatar, the names, and then everything else on a sheet, with one of
 * their widgets. The full profile keeps a steady size however much someone
 * has on it: the person on a card down the left, and their widgets, what
 * they are doing and what you have in common behind tabs on the right, each
 * scrolling on its own. Too narrow for that (a phone), it is one column, with
 * the tabs held at the top as it scrolls and what the left card said behind
 * an About tab. A profile with two theme colours is a gradient from top to
 * bottom, with its text and buttons turned to read on it.
 */
export function ProfileCard({ userId, variant, onClose, preview, previewWidgets, serverId, visitor, initialTab, design, embedded }: Props) {
  useMediaAnimation();
  const nav = useNavigate();
  const inServer = useServerInView(serverId);
  const me = useAuth((a) => a.me);
  const stored = useUsers((u) => u.byId[userId]);
  const upsertUser = useUsers((u) => u.upsert);
  const users = useUsers((u) => u.byId);
  const rel = useRelationships((r) => r.byUser[userId]);
  const setRel = useRelationships((r) => r.set);
  // a friend request can wait on a captcha check: the button says it is on its way
  const busy = useBusyWith(userId);
  const presence = usePresence((p) => p.byUser[userId]);
  const servers = useServers((st) => st.byId);
  const upsertChannel = useChannels((c) => c.upsert);
  const toast = useUI((u) => u.toast);
  const openModal = useUI((u) => u.openModal);
  const note = useSettings((st) => st.settings.userNotes[userId] ?? "");
  const allNotes = useSettings((st) => st.settings.userNotes);
  const patchSettings = useSettings((st) => st.patch);
  const ctx = useRenderCtx();
  const [mutual, setMutual] = useState<{ friends: string[]; servers: string[] }>({ friends: [], servers: [] });
  const [widgets, setWidgets] = useState<Widget[]>([]);
  const [noteOpen, setNoteOpen] = useState(false);
  const [viewing, setViewing] = useState<"avatar" | "banner" | null>(null);
  const [bioClamped, setBioClamped] = useState(false);
  const [chosenTab, setChosenTab] = useState<ProfileTab | null>(initialTab ?? null);
  const [cardEl, setCardEl] = useState<HTMLDivElement | null>(null);
  const bioRef = useRef<HTMLDivElement>(null);
  const noteRef = useRef<HTMLTextAreaElement>(null);
  const tabsRef = useRef<HTMLDivElement>(null);
  const ids = useId();
  const isMe = !visitor && me?.id === userId;
  const base: PublicUser | undefined = visitor ? visitor.user : isMe && me ? me : stored;
  // inside a server, what someone set for that server stands in for their own:
  // their name there, picture, banner, pronouns, About Me and colours
  const member = inServer && !visitor ? servers[inServer]?.members[userId] : undefined;
  const serverOwn: Partial<PublicUser> = member
    ? {
        ...(member.nickname ? { display_name: member.nickname } : {}),
        ...(member.avatar_id ? { avatar_id: member.avatar_id } : {}),
        ...(member.banner_id ? { banner_id: member.banner_id } : {}),
        ...(member.pronouns ? { pronouns: member.pronouns } : {}),
        ...(member.bio ? { about_me: member.bio } : {}),
        ...(typeof member.accent_color === "number" ? { accent_color: member.accent_color, accent_color_2: member.accent_color_2 ?? null } : {}),
      }
    : {};
  const user: PublicUser | undefined = base ? { ...base, ...serverOwn, ...preview } : undefined;
  // the preview and the popout are one column, with one widget and the way to the rest
  const compact = variant !== "modal";
  const panel = variant === "panel";
  const popout = variant === "popout";
  const cardWidth = useCardWidth(cardEl, !compact);
  const narrow = cardWidth < TWO_COLUMNS;
  const visitorWidgets = visitor?.widgets;
  const shownWidgets = useMemo(() => previewWidgets ?? (visitor ? readWidgets(visitorWidgets) : widgets), [previewWidgets, visitor, visitorWidgets, widgets]);

  useEffect(() => {
    if (preview || visitor) return;
    let alive = true;
    // The panel beside a DM is one card that changes person rather than a new
    // one, so without this it goes on showing the last person's mutual friends
    // and servers under the new name — forever if the request fails.
    setMutual({ friends: [], servers: [] });
    setWidgets([]);
    usersApi
      .profile(userId, inServer)
      .then((p) => {
        if (!alive) return;
        upsertUser(p.user);
        setMutual({ friends: p.mutual_friends, servers: p.mutual_servers ?? [] });
        setWidgets(readWidgets(p.widgets));
      })
      .catch(() => {
        /* a profile already on hand still shows; the dialog says when there is none */
      });
    return () => {
      alive = false;
    };
  }, [userId, upsertUser, preview, visitor, inServer]);

  // an app's bot: what the app says about itself, and whether it can be added from here
  const bot = isBot(base);
  const [app, setApp] = useState<AppSummary | null>(null);
  useEffect(() => {
    if (!bot || preview || visitor) return;
    let alive = true;
    appsApi
      .about(userId)
      .then((a) => alive && setApp(a))
      .catch(() => alive && setApp(null));
    return () => {
      alive = false;
    };
  }, [bot, userId, preview, visitor]);

  // the preview shows the start of a long bio and offers the rest
  const about = user?.about_me || (bot ? (app?.description ?? "") : "");
  useLayoutEffect(() => {
    const el = bioRef.current;
    if (!compact || !el) return;
    const measure = () => setBioClamped(el.scrollHeight > el.clientHeight + 1);
    measure();
    if (typeof ResizeObserver === "undefined") return;
    const ro = new ResizeObserver(measure);
    ro.observe(el);
    return () => ro.disconnect();
  }, [compact, about]);

  useEffect(() => {
    if (noteOpen) noteRef.current?.focus();
  }, [noteOpen]);

  // A profile without colours of its own takes a glow from its banner, or its
  // picture: every card then looks like the person's own, not like the next one.
  const themed = !!user && !!profileThemeOf(user.accent_color, user.accent_color_2);
  const tintFrom = !user || themed ? null : (bannerUrl(user.banner_id, 64) ?? avatarUrl(user.avatar_id, 64));
  const tint = useDominantColor(tintFrom);

  if (!user) return null;
  // the system account is not a person, and says so
  if (isSystemAccount(user.flags)) return <NexaProfile variant={panel ? "panel" : "modal"} onClose={onClose} />;
  // a visitor sees nothing of where someone is right now
  const st = visitor ? null : preview ? "online" : statusOf(presence);
  const cs = visitor ? null : preview ? (user.status_text || user.status_emoji ? { text: user.status_text, emoji: user.status_emoji } : null) : customStatus(presence);
  const activities = preview || visitor ? [] : activitiesOf(presence);
  const theme = profileThemeOf(user.accent_color, user.accent_color_2);
  // the left card of the full profile is about as wide as the preview
  const banner = bannerUrl(user.banner_id, panel || (!compact && !narrow) ? 340 : 600);
  const friend = !visitor && rel?.type === "friend";
  const interactive = !preview && !visitor;
  const avatarSize = panel || popout ? 80 : !narrow && cardWidth < ROOMY ? 92 : 120;

  const colours: Record<string, string> = theme ? { "--profile-top": theme.top, "--profile-bottom": theme.bottom } : tint ? { "--profile-tint": tint } : {};
  const cardStyle = { "--av": `${avatarSize}px`, ...colours } as CSSProperties;
  // with a theme and no picture the banner is see-through, so the gradient runs unbroken from the top
  const bannerStyle: CSSProperties = banner ? { backgroundImage: `url(${banner})` } : { background: theme ? "transparent" : profileTheme(user.accent_color, null, user.id) };

  const message = async () => {
    try {
      const ch = await channelsApi.createDM([userId]);
      upsertChannel(ch);
      onClose?.();
      nav(`/channels/@me/${ch.id}`);
    } catch (err) {
      toast(dmOpenError(err, t("profile.profileCard.couldNotOpenAConversation")), "error");
    }
  };
  const addFriend = () => void sendFriendRequest(user);
  // the app's own consent page, with its install settings
  const canAddApp = bot && !!app && (app.bot_public !== false || app.owner_id === useAuth.getState().me?.id);
  const addApp = () => {
    onClose?.();
    // the consent page brings them back here once they are done
    nav(`/oauth2/authorize?client_id=${userId}`, { state: { from: location.pathname + location.search } });
  };
  const editNickname = async () => {
    if (!rel || rel.type !== "friend") return;
    const values = await promptFields({ title: t("profile.profileCard.friendNickname"), fields: [{ key: "nickname", label: t("modals.serverProfile.nickname"), initial: rel.nickname, placeholder: user.display_name, maxLength: 32, hint: t("profile.profileCard.onlyYouSeeIt") }], confirmLabel: t("chat.editMessage.save"), allowEmpty: true });
    const next = values?.["nickname"]?.trim();
    if (next === undefined || next === rel.nickname) return;
    try {
      setRel(await relApi.setNickname(userId, next));
    } catch (err) {
      toast(err instanceof ApiError ? err.message : t("profile.profileCard.couldNotSaveTheNickname"), "error");
    }
  };
  const more = (e: MouseEvent<HTMLButtonElement>) => {
    const r = e.currentTarget.getBoundingClientRect();
    profileMoreMenu.open({ user, serverId, compact, popout, friend, viewFull: () => viewFull(), copyLink, editNickname: () => void editNickname() }, { x: r.left, y: r.bottom + 4 });
  };
  const setNote = (value: string) => {
    const userNotes = { ...allNotes };
    if (value) userNotes[userId] = value;
    else delete userNotes[userId];
    patchSettings({ userNotes });
  };

  const shownName = friend && rel.nickname ? rel.nickname : user.display_name;
  const ownLook = withOwn(undefined, user.name_style);
  const styledName = ownLook ? <span className={nameClass(ownLook)} style={nameStyle(ownLook)} data-testid="profile-styled-name">{shownName}</span> : shownName;
  const mutualServers = mutual.servers.filter((id) => servers[id]);
  const mutualCount = mutual.friends.length + mutualServers.length;
  // the whole profile, which the popout makes way for
  const viewFull = (tab?: ProfileTab) => {
    if (popout) onClose?.();
    openModal("profile", { userId, full: true, ...(tab ? { tab } : {}) });
  };
  const buttons = interactive && !compact;
  const copyLink = () => copyProfileLink(user.username);
  const editWidgets = () => {
    onClose?.();
    openModal("settings", { page: "profiles", anchor: "widgets" });
  };

  // The full profile's tabs: About only where the card is one column, What
  // They Are Doing for anyone with an account, and What You Have in Common
  // once there is something.
  const tabs: { id: ProfileTab; label: string; count?: number }[] = compact
    ? []
    : [
        ...(narrow ? [{ id: "about" as const, label: t("profile.tabs.about") }] : []),
        { id: "widgets", label: t("widgets.title") },
        ...(visitor ? [] : [{ id: "activity" as const, label: t("profile.tabs.activity") }]),
        ...(interactive && !isMe && mutualCount > 0 ? [{ id: "mutuals" as const, label: t("profile.tabs.mutuals"), count: mutualCount }] : []),
      ];
  // until one is picked: Widgets, or About on a phone, where it holds what the left card shows
  const fallback: ProfileTab = narrow ? "about" : "widgets";
  const tab: ProfileTab = chosenTab && tabs.some((x) => x.id === chosenTab) ? chosenTab : fallback;
  const pickTab = (next: ProfileTab) => {
    setChosenTab(next);
    // on a phone the tabs may be scrolled out of sight by the time something below picks one
    if (narrow) tabsRef.current?.scrollIntoView({ block: "nearest", behavior: "smooth" });
  };
  const onTabKey = (e: KeyboardEvent<HTMLButtonElement>) => {
    const step = e.key === "ArrowRight" ? 1 : e.key === "ArrowLeft" ? -1 : e.key === "Home" ? -tabs.length : e.key === "End" ? tabs.length : 0;
    if (!step) return;
    e.preventDefault();
    // right to left, the arrows go the way the strip reads
    const dir = document.documentElement.dir === "rtl" && (e.key === "ArrowRight" || e.key === "ArrowLeft") ? -1 : 1;
    const at = tabs.findIndex((x) => x.id === tab);
    const next = tabs[Math.min(tabs.length - 1, Math.max(0, at + step * dir))]!;
    setChosenTab(next.id);
    e.currentTarget.parentElement?.querySelector<HTMLElement>(`[data-tab="${next.id}"]`)?.focus();
  };

  // the full profile leads with one clear action; the preview keeps its buttons on the banner
  // (a request either way, or a block, is said by the bar under the names, with its own buttons)
  const primary = !buttons ? null : isMe ? (
    <button className={s.primary} onClick={() => { onClose?.(); openModal("settings", { page: "profiles" }); }}><Pencil size={16} /> {t("profile.profileCard.editProfile")}</button>
  ) : bot ? (
    canAddApp ? (
      <button className={s.primary} onClick={addApp} data-testid="profile-add-app"><CirclePlus size={16} aria-hidden="true" /> {t("apps.profile.addApp")}</button>
    ) : (
      <button className={s.primary} onClick={() => void message()}><MessageCircle size={16} /> {t("profile.profileCard.message")}</button>
    )
  ) : rel?.type === "blocked" ? null : rel ? (
    <button className={s.primary} onClick={() => void message()}><MessageCircle size={16} /> {t("profile.profileCard.message")}</button>
  ) : (
    <button className={s.primary} onClick={addFriend} disabled={busy} aria-busy={busy || undefined} data-testid="profile-add-friend">
      {busy ? <LoaderCircle size={16} className={s.spin} aria-hidden="true" /> : <UserPlus size={16} aria-hidden="true" />} {busy ? t("people.request.sending") : t("profile.profileCard.addFriend")}
    </button>
  );

  const top = (
    <div className={s.top}>
      {!compact && !design && !preview && user.banner_id ? (
        <button type="button" className={`${s.banner} ${s.bannerView}`} style={bannerStyle} aria-label={t("profile.profileCard.viewBanner")} onClick={() => setViewing("banner")} data-testid="profile-banner-view" />
      ) : (
        <div className={s.banner} style={bannerStyle} />
      )}
      {design ? <Spot design={design} part="banner" label={user.banner_id ? t("designer.spot.banner") : t("designer.spot.addBanner")} /> : null}
      <div className={s.bannerBtns}>
        {!interactive ? null : popout ? (
          <>
            {isMe ? (
              <BannerButton label={t("profile.profileCard.editProfile")} onClick={() => { onClose?.(); openModal("settings", { page: "profiles" }); }}><Pencil size={16} /></BannerButton>
            ) : bot ? null : !rel ? (
              <BannerButton label={busy ? t("people.request.sending") : t("profile.profileCard.addFriend")} onClick={addFriend} busy={busy} testId="profile-add-friend">
                {busy ? <LoaderCircle size={18} className={s.spin} /> : <UserPlus size={18} />}
              </BannerButton>
            ) : null}
            <BannerButton label={t("profile.profileCard.viewFullProfile")} onClick={() => viewFull()} testId="profile-full"><Maximize2 size={16} /></BannerButton>
            {!isMe ? <BannerButton label={t("chat.chat.more")} onClick={more} testId="profile-more"><MoreHorizontal size={18} /></BannerButton> : null}
          </>
        ) : panel ? (
          isMe ? (
            <>
              <BannerButton label={t("profile.profileCard.editProfile")} onClick={() => openModal("settings", { page: "profiles" })}><Pencil size={16} /></BannerButton>
              <BannerButton label={t("profileLink.copy")} onClick={copyLink} testId="profile-copy-link"><Link2 size={18} /></BannerButton>
            </>
          ) : (
            <>
              {bot ? (
                canAddApp ? <BannerButton label={t("apps.profile.addApp")} onClick={addApp} testId="profile-add-app"><CirclePlus size={18} /></BannerButton> : null
              ) : !rel ? (
                <BannerButton label={busy ? t("people.request.sending") : t("profile.profileCard.addFriend")} onClick={addFriend} busy={busy} testId="profile-add-friend">
                  {busy ? <LoaderCircle size={18} className={s.spin} /> : <UserPlus size={18} />}
                </BannerButton>
              ) : null}
              <BannerButton label={t("profileLink.copy")} onClick={copyLink} testId="profile-copy-link"><Link2 size={18} /></BannerButton>
              <BannerButton label={t("chat.chat.more")} onClick={more} testId="profile-more"><MoreHorizontal size={18} /></BannerButton>
            </>
          )
        ) : onClose && narrow ? (
          // two columns put the close button at the dialog's corner, beside the tabs
          <BannerButton label={t("chat.attachments.close")} onClick={onClose}><X size={18} /></BannerButton>
        ) : null}
      </div>
      <div className={s.avatar}>
        {compact && !design ? (
          <button className={s.avatarBtn} aria-label={t("profile.profileCard.viewFullProfileOf", { name: shownName })} onClick={() => viewFull()} data-testid="profile-avatar">
            <Avatar id={user.id} avatarId={user.avatar_id} name={user.display_name} size={avatarSize} status={st} dotSize={18} />
          </button>
        ) : !design && !preview && user.avatar_id ? (
          <button className={s.avatarBtn} aria-label={t("profile.profileCard.viewProfilePicture")} onClick={() => setViewing("avatar")} style={{ cursor: "zoom-in" }} data-testid="profile-avatar-view">
            <Avatar id={user.id} avatarId={user.avatar_id} name={user.display_name} size={avatarSize} status={st} dotSize={26} />
          </button>
        ) : (
          <Avatar id={user.id} avatarId={user.avatar_id} name={user.display_name} size={avatarSize} status={st} dotSize={26} />
        )}
        {viewing && (viewing === "avatar" ? user.avatar_id : user.banner_id) ? (
          <Lightbox
            items={[{ src: (viewing === "avatar" ? avatarUrl(user.avatar_id) : bannerUrl(user.banner_id)) ?? undefined, name: shownName || viewing }]}
            onClose={() => setViewing(null)}
          />
        ) : null}
        {design ? <Spot design={design} part="avatar" label={t("designer.spot.avatar")} round /> : null}
      </div>
      {cs ? (
        <StatusBubble status={cs} design={design} />
      ) : design && !design.fixed?.includes("status") ? (
        // in the designer, an empty status is a bubble waiting to be filled
        <div className={s.bubble}>
          <button type="button" className={[s.bubbleText, s.ghostBubble].join(" ")} data-spot="status" onClick={(e) => design.edit("status", e.currentTarget)}>
            <Plus size={14} aria-hidden="true" /> {t("designer.ghost.status")}
          </button>
        </div>
      ) : null}
    </div>
  );

  const identity = (
    <>
      <div className={s.ident}>
        <div className={s.nameRow}>
          <h2 className={s.display}>
            {design?.inline?.name ?? (compact && !design ? <button className={s.displayBtn} onClick={() => viewFull()} data-testid="profile-name">{styledName}</button> : styledName)}
            {design && !design.inline?.name ? <Spot design={design} part="name" label={t("designer.spot.name")} /> : null}
          </h2>
          {/* the server tag they wear, which opens the server it is from (not for a visitor, who has no account to ask with) */}
          {user.server_tag ? (
            design ? (
              <span className={s.spotWrap}>
                <ServerTagChip tag={user.server_tag} size="lg" open={false} />
                <Spot design={design} part="tag" label={t("designer.spot.tag")} />
              </span>
            ) : (
              <ServerTagChip tag={user.server_tag} size="lg" open={interactive} />
            )
          ) : design && !design.fixed?.includes("tag") ? (
            <button type="button" className={s.ghostChip} data-spot="tag" onClick={(e) => design.edit("tag", e.currentTarget)}>
              <Plus size={12} aria-hidden="true" /> {t("designer.ghost.tag")}
            </button>
          ) : null}
          {bot ? <HookTags app /> : null}
          {interactive && !isMe ? (
            <Tooltip text={note ? t("profile.profileCard.editNote") : t("profile.profileCard.addNote")} side="top">
              <button className={s.noteBtn} aria-label={note ? t("profile.profileCard.editNote2") : t("profile.profileCard.addNote2")} onClick={() => setNoteOpen((v) => !v)} data-testid="profile-note-toggle">
                <NotebookPen size={18} />
              </button>
            </Tooltip>
          ) : null}
        </div>
        <div className={s.user}>
          {/* A nickname you gave hides their own name, so it is added here; a name the line already shows is not said twice. */}
          {friend && rel.nickname && user.display_name && user.display_name !== rel.nickname && user.display_name.toLowerCase() !== user.username.toLowerCase() ? (
            <>
              <PersonName user={user}>{user.display_name}</PersonName>
              <span className={s.sep} aria-hidden="true">·</span>
            </>
          ) : null}
          <span>{user.username}</span>
          {design?.inline?.pronouns ? (
            <>
              <span className={s.sep} aria-hidden="true">·</span>
              {design.inline.pronouns}
            </>
          ) : user.pronouns ? (
            <>
              <span className={s.sep} aria-hidden="true">·</span>
              {design ? (
                <span className={s.spotWrap}>
                  <span className={s.pronouns} data-testid="profile-pronouns">{user.pronouns}</span>
                  <Spot design={design} part="pronouns" label={t("designer.spot.pronouns")} />
                </span>
              ) : (
                <Tooltip text={t("settings.profile.pronouns")} side="top">
                  <span className={s.pronouns} data-testid="profile-pronouns">{user.pronouns}</span>
                </Tooltip>
              )}
            </>
          ) : design ? (
            <>
              <span className={s.sep} aria-hidden="true">·</span>
              <button type="button" className={s.ghostInline} data-spot="pronouns" onClick={(e) => design.edit("pronouns", e.currentTarget)}>
                {t("designer.ghost.pronouns")}
              </button>
            </>
          ) : null}
          {rankOf(user.id, user.flags) ? <StaffBadge rank={rankOf(user.id, user.flags)!} /> : null}
          <PlanBadge plan={user.plan} />
        </div>
      </div>

      {interactive && !isMe && !bot ? <RelationshipBar user={user} rel={rel} /> : null}
      {/* read out when it changes: the button pressed has gone, and this is what took its place */}
      {interactive && !isMe ? <span className="visually-hidden" aria-live="polite">{relationshipLine(rel)}</span> : null}

      {visitor ? <div className={s.actions} data-testid="profile-visitor-actions">{visitor.actions}</div> : null}
      {buttons ? (
        <div className={s.actions}>
          {primary}
          {!isMe && !rel ? (
            <Tooltip text={t("profile.profileCard.message")} side="top">
              <button className={s.square} aria-label={t("profile.profileCard.message")} onClick={() => void message()}><MessageCircle size={18} /></button>
            </Tooltip>
          ) : null}
          <Tooltip text={t("profileLink.copy")} side="top">
            <button className={s.square} aria-label={t("profileLink.copy")} onClick={copyLink} data-testid="profile-copy-link"><Link2 size={18} /></button>
          </Tooltip>
          {!isMe ? (
            <Tooltip text={t("chat.chat.more")} side="top">
              <button className={s.square} aria-label={t("chat.chat.more")} onClick={more} data-testid="profile-more"><MoreHorizontal size={18} /></button>
            </Tooltip>
          ) : null}
        </div>
      ) : null}

      {/* what you have in common: the full profile's Mutuals tab, from the preview by way of the full profile */}
      {interactive && !isMe && mutualCount > 0 ? (
        <button className={s.mutualRow} onClick={() => (compact ? viewFull("mutuals") : pickTab("mutuals"))} data-testid="profile-mutual">
          {mutual.friends.length > 0 ? (
            <span className={s.stack} aria-hidden="true">
              {mutual.friends.slice(0, 3).map((id) => (
                <Avatar key={id} id={id} avatarId={users[id]?.avatar_id ?? null} name={displayName(users[id])} size={20} className={s.stackItem} />
              ))}
            </span>
          ) : null}
          <span>{[mutual.friends.length ? t("profile.profileCard.mutualFriendCount", { count: mutual.friends.length }) : "", mutualServers.length ? t("profile.profileCard.mutualServerCount", { count: mutualServers.length }) : ""].filter(Boolean).join(" • ")}</span>
        </button>
      ) : null}
    </>
  );

  // what the person says about themselves and what is known of them: the sheet of the preview, the left card of the full profile
  const noteSection =
    interactive && !isMe && (noteOpen || note) ? (
      <div className={s.section}>
        <h3>{t("profile.profileCard.note")}</h3>
        <textarea
          ref={noteRef}
          className={s.note}
          value={note}
          maxLength={256}
          rows={2}
          placeholder={t(touchFirst() ? "profile.profileCard.tapToAddANote" : "profile.profileCard.clickToAddANote")}
          aria-label={t("profile.profileCard.note")}
          onChange={(e) => setNote(e.target.value)}
          onBlur={() => setNoteOpen(false)}
        />
      </div>
    ) : null;
  const aboutSection = design?.inline?.about ? (
    <div className={s.section}>
      <h3>{t("settings.profile.aboutMe")}</h3>
      {design.inline.about}
    </div>
  ) : design && !user.about_me ? (
    <button type="button" className={[s.section, s.ghostSection].join(" ")} data-spot="about" onClick={(e) => design.edit("about", e.currentTarget)}>
      <span className={s.ghostTitle}>{t("settings.profile.aboutMe")}</span>
      <span className={s.ghostText}>{t("designer.ghost.about")}</span>
    </button>
  ) : about ? (
    <div className={[s.section, design ? s.spotWrap : ""].join(" ")} data-about="">
      {design ? <Spot design={design} part="about" label={t("designer.spot.about")} /> : null}
      <h3>{bot && !user.about_me ? t("apps.profile.about") : t("settings.profile.aboutMe")}</h3>
      <div ref={bioRef} className={s.about} data-clamp={compact || undefined} data-overflow={(compact && bioClamped) || undefined}>
        <Markdown source={about} ctx={ctx} />
      </div>
      {compact && bioClamped && interactive ? (
        <button className={s.moreLink} onClick={() => viewFull()} data-testid="view-full-bio">{t("profile.profileCard.viewFullBio")}</button>
      ) : null}
    </div>
  ) : null;
  const factSections = (
    <>
      {inServer && interactive ? <ServerRoles serverId={inServer} userId={userId} /> : null}
      {user.public_key && interactive ? (
        <div className={s.section} data-testid="profile-key">
          <h3>{t("encryption.encryptionKey")}</h3>
          <p><KeyStatusChip userId={userId} onClick={() => openModal("verifyKey", { userId })} /></p>
        </div>
      ) : null}
      <div className={s.sinceRow}>
        <div className={s.section}>
          <h3>{t("profile.profileCard.memberSince")}</h3>
          <p>{dateOf(user.created_at)}</p>
        </div>
        {friend && !isMe && rel.created_at ? (
          <div className={s.section} data-testid="friends-since">
            <h3>{t("profile.profileCard.friendsSince")}</h3>
            <p>{dateOf(rel.created_at)}</p>
          </div>
        ) : null}
      </div>
    </>
  );

  if (popout) {
    // The popout: who they are, what you have in common, a few lines of their
    // own, what they are doing, their first widget in a line, their roles
    // here, and a message to them. Everything else is on the full profile.
    return (
      <div
        className={s.card}
        data-variant="popout"
        data-embedded={embedded || undefined}
        data-design={design ? "" : undefined}
        data-themed={theme?.text}
        data-tinted={!theme && tint ? "" : undefined}
        style={cardStyle}
        data-testid="profile-popout-card"
      >
        {top}
        <div className={s.info}>
          {identity}
          {noteSection || aboutSection || activities.length || shownWidgets.length || (inServer && interactive) ? (
            <div className={s.popoutBody}>
              {noteSection}
              {aboutSection}
              {activities.length ? <ActivityList activities={activities} /> : null}
              {shownWidgets.length ? <WidgetPeek widgets={shownWidgets} onOpen={interactive ? () => viewFull("widgets") : undefined} /> : null}
              {inServer && interactive ? <ServerRoles serverId={inServer} userId={userId} popout /> : null}
            </div>
          ) : null}
          {/* an app's card leads with adding it, the one thing people open it for */}
          {interactive && canAddApp ? (
            <button className={[s.primary, s.popoutAddApp].join(" ")} onClick={addApp} data-testid="profile-add-app">
              <CirclePlus size={16} aria-hidden="true" /> {t("apps.profile.addApp")}
            </button>
          ) : null}
          {interactive && !isMe && rel?.type !== "blocked" ? <MessageBox user={user} name={shownName || user.username} onSent={onClose} /> : null}
        </div>
      </div>
    );
  }

  if (compact) {
    // The preview beside a DM: one widget, the first they put, and the way to
    // the rest, which is the whole profile.
    const first = shownWidgets.slice(0, 1);
    return (
      <div
        className={s.card}
        data-variant={variant}
        data-design={design ? "" : undefined}
        data-themed={theme?.text}
        data-tinted={!theme && tint ? "" : undefined}
        style={cardStyle}
        data-testid={`profile-${variant}`}
      >
        {top}
        <div className={s.info}>
          {identity}
          <div className={s.sheet}>
            {noteSection}
            {activities.length ? <ActivityList activities={activities} /> : null}
            {aboutSection}
            {first.length ? (
              <div className={s.section}>
                <ProfileWidgets widgets={first} variant="panel" onShowAll={interactive ? () => viewFull("widgets") : undefined} />
                {interactive && shownWidgets.length > 1 ? (
                  <button className={s.moreLink} onClick={() => viewFull("widgets")} data-testid="view-all-widgets">{t("profile.tabs.viewAllWidgets", { count: shownWidgets.length })}</button>
                ) : null}
              </div>
            ) : null}
            {factSections}
          </div>
          {interactive ? (
            <button className={s.fullBtn} onClick={() => viewFull()} data-testid="view-full-profile">{t("profile.profileCard.viewFullProfile")}</button>
          ) : null}
        </div>
      </div>
    );
  }

  const tabList = (
    <div className={s.tabs} role="tablist" aria-label={t("profile.tabs.label")}>
      {tabs.map((x) => (
        <button
          key={x.id}
          id={`${ids}-tab-${x.id}`}
          className={s.tab}
          role="tab"
          aria-selected={tab === x.id}
          aria-controls={`${ids}-panel`}
          tabIndex={tab === x.id ? 0 : -1}
          data-tab={x.id}
          data-selected={tab === x.id}
          onKeyDown={onTabKey}
          onClick={() => setChosenTab(x.id)}
          data-testid={`profile-tab-${x.id}`}
        >
          {x.label}
          {x.count ? <span className={s.tabCount}>{x.count.toLocaleString()}</span> : null}
        </button>
      ))}
    </div>
  );

  let content: ReactNode;
  if (tab === "about") {
    content = (
      <div className={s.sheet}>
        {noteSection}
        {aboutSection}
        {factSections}
      </div>
    );
  } else if (tab === "widgets") {
    content = (
      <div className={s.sheet} data-testid="profile-widgets-tab">
        {isMe && buttons && shownWidgets.length ? (
          <div className={s.panelHead}>
            <h3>{t("profile.tabs.yourWidgets")}</h3>
            <button className={s.panelBtn} onClick={editWidgets} data-testid="profile-edit-widgets"><Pencil size={14} aria-hidden="true" /> {t("profile.tabs.editWidgets")}</button>
          </div>
        ) : null}
        <ProfileWidgets widgets={shownWidgets} variant="modal" onAdd={design?.widgets ? design.widgets.add : isMe && buttons ? editWidgets : undefined} design={design?.widgets} />
        {!shownWidgets.length && !design && !(isMe && buttons) ? <p className={s.emptyTab}>{<Tr id="profile.tabs.noWidgets" values={{ name: <PersonName user={user}>{shownName}</PersonName> }} />}</p> : null}
      </div>
    );
  } else if (tab === "activity") {
    content = (
      <div className={s.sheet} data-testid="profile-activity-tab">
        {activities.length ? <ActivityList activities={activities} /> : <p className={s.emptyTab}>{isMe ? t("profile.tabs.noActivityOwn") : <Tr id="profile.tabs.noActivity" values={{ name: <PersonName user={user}>{shownName}</PersonName> }} />}</p>}
      </div>
    );
  } else {
    content = (
      <div className={s.sheet} data-testid="profile-mutuals-tab">
        {mutual.friends.length > 0 ? (
          <div className={s.section}>
            <h3>{t("profile.profileCard.mutualFriendsCount", { count: mutual.friends.length })}</h3>
            {mutual.friends.map((id) => (
              <button key={id} className={s.listItem} onClick={() => openModal("profile", { userId: id })}>
                <Avatar id={id} avatarId={users[id]?.avatar_id ?? null} name={displayName(users[id])} size={32} />
                <PersonName userId={id}>{displayName(users[id])}</PersonName>
              </button>
            ))}
          </div>
        ) : null}
        {mutualServers.length > 0 ? (
          <div className={s.section}>
            <h3>{t("profile.profileCard.mutualServersCount", { count: mutualServers.length })}</h3>
            {mutualServers.map((id) => {
              const server = servers[id]!.server;
              const icon = iconUrl(server.icon_id, 32);
              return (
                <button key={id} className={s.listItem} onClick={() => { onClose?.(); nav(`/channels/${id}`); }}>
                  {icon ? <img className={s.listIcon} src={icon} alt="" /> : <span className={s.listInitial}>{server.name.slice(0, 1).toUpperCase()}</span>}
                  <span className={s.listName}>{server.name}</span>
                </button>
              );
            })}
          </div>
        ) : null}
      </div>
    );
  }
  const tabPanel =
    tabs.length > 1 ? (
      <div className={s.tabPanel} id={`${ids}-panel`} role="tabpanel" aria-labelledby={`${ids}-tab-${tab}`}>
        {content}
      </div>
    ) : (
      <div className={s.tabPanel}>{content}</div>
    );

  return (
    <div
      ref={setCardEl}
      className={s.card}
      data-variant={variant}
      data-layout={narrow ? "narrow" : "wide"}
      data-design={design ? "" : undefined}
      data-themed={theme?.text}
      data-tinted={!theme && tint ? "" : undefined}
      style={cardStyle}
      data-testid={`profile-${variant}`}
    >
      {narrow ? (
        <>
          {top}
          <div className={s.info}>{identity}</div>
          <div className={s.tabsBar} ref={tabsRef}>{tabList}</div>
          {tabPanel}
        </>
      ) : (
        <>
          {/* the full profile wears the banner again, faint across the top of everything, in the theme's colours */}
          {variant === "modal" && banner ? <div className={s.backdrop} style={{ "--backdrop": `url(${banner})` } as CSSProperties} aria-hidden="true" data-testid="profile-backdrop" /> : null}
          <div className={s.side}>
            {top}
            <div className={s.info}>
              {identity}
              <div className={s.sheet}>
                {noteSection}
                {aboutSection}
                {factSections}
              </div>
            </div>
          </div>
          <div className={s.main}>
            {/* one tab alone (a visitor's view: widgets only) needs no strip to choose it */}
            {tabs.length > 1 || onClose ? (
              <div className={s.tabsBar} ref={tabsRef}>
                {tabs.length > 1 ? tabList : null}
                {onClose ? (
                  <button className={s.closeBtn} onClick={onClose} aria-label={t("chat.attachments.close")}>
                    <X size={20} />
                  </button>
                ) : null}
              </div>
            ) : null}
            {tabPanel}
          </div>
        </>
      )}
    </div>
  );
}
