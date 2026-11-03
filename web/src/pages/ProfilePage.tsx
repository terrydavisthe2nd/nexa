import { useMediaAnimation } from "@/lib/mediaAnimation";
import { UserX } from "lucide-react";
import { useEffect, type CSSProperties } from "react";
import { Link, useLocation, useNavigate, useParams } from "react-router-dom";
import { avatarUrl, bannerUrl } from "@/api/users";
import { NexaWordmark } from "@/components/common/Brand";
import { Button } from "@/components/common/Button";
import { Spinner } from "@/components/common/Spinner";
import v from "@/components/invite/Invite.module.css";
import { ProfileCard } from "@/components/profile/ProfileCard";
import p from "@/components/profile/Profile.module.css";
import { AndroidBanner } from "@/components/shell/AndroidBanner";
import { t } from "@/i18n";
import { profileThemeOf } from "@/lib/avatarColor";
import { useDominantColor } from "@/lib/dominantColor";
import { loadSharedProfile, useSharedProfile } from "@/lib/profileLinks";
import LockPage from "@/pages/LockPage";
import NotFoundPage from "@/pages/NotFoundPage";
import OfflinePage from "@/pages/OfflinePage";
import PasscodePage from "@/pages/PasscodePage";
import { useAuth } from "@/stores/auth";
import { useUI } from "@/stores/ui";
import { usePageTitle } from "./AuthLayout";
import s from "./ProfilePage.module.css";

/**
 * A profile link's page, at /@username (lib/profileLinks). Someone signed in
 * goes straight to the profile in the app. Anyone else sees the person's own
 * profile card, the one the app shows, with a way to log in or join where
 * the card's buttons would be.
 */
export default function ProfilePage() {
  const { handle = "" } = useParams();
  // the route takes any one-part address; only one starting with @ is a profile
  if (!/^@[A-Za-z0-9_.]{2,32}$/.test(handle)) return <NotFoundPage />;
  return <ProfileLinkPage username={handle.slice(1).toLowerCase()} />;
}

function ProfileLinkPage({ username }: { username: string }) {
  useMediaAnimation();
  const loc = useLocation();
  const nav = useNavigate();
  const status = useAuth((a) => a.status);
  const bootstrap = useAuth((a) => a.bootstrap);
  const entry = useSharedProfile(username);
  const user = entry?.status === "ready" ? entry.user : null;
  const name = user ? user.display_name || user.username : null;

  // The page's glow takes the person's colour: their theme, or the tint of
  // their banner or picture, as their card works it out.
  const theme = user ? profileThemeOf(user.accent_color, user.accent_color_2) : null;
  const tint = useDominantColor(!user || theme ? null : (bannerUrl(user.banner_id, 64) ?? avatarUrl(user.avatar_id, 64)));
  const glow = theme?.top ?? tint;

  // the lock and offline screens drawn in its place name the tab themselves
  usePageTitle(entry?.status === "missing" ? t("profileLink.missingTitle") : name, status !== "locked" && status !== "pinlocked" && status !== "offline");

  // the page lives outside the authed shell, so it restores the session itself
  useEffect(() => {
    if (status === "idle") void bootstrap();
  }, [status, bootstrap]);

  // Signed in: the profile in the app, over the home page, where the
  // friend request and the message are a click away.
  const userId = status === "ready" && user ? user.id : null;
  useEffect(() => {
    if (!userId) return;
    nav("/channels/@me", { replace: true });
    useUI.getState().openModal("profile", { userId, full: true });
  }, [userId, nav]);

  if (status === "locked") return <LockPage />;
  if (status === "pinlocked") return <PasscodePage />;
  if (status === "offline") return <OfflinePage />;

  let body;
  if (entry?.status === "missing") {
    body = (
      <div className={v.view} data-variant="page" data-gone="true" data-testid="profile-link-missing">
        <div className={v.gone}>
          <span className={v.goneIcon} aria-hidden="true"><UserX size={28} /></span>
          <div className={v.goneText}>
            <h1 className={v.goneTitle}>{t("profileLink.missingTitle")}</h1>
            <p className={v.goneBody}>{t("profileLink.missingBody")}</p>
          </div>
        </div>
        <div className={v.goneMore}>
          <Link to={status === "ready" ? "/channels/@me" : "/"} className={v.link}>{t("profileLink.goToNexa")}</Link>
        </div>
      </div>
    );
  } else if (entry?.status === "failed") {
    body = (
      <div className={v.view} data-variant="page" data-gone="true" data-testid="profile-link-failed">
        <div className={v.gone}>
          <div className={v.goneText}>
            <h1 className={v.goneTitle}>{t("profileLink.couldNotLoad")}</h1>
            <p className={v.goneBody}>{t("invite.checkYourConnection")}</p>
          </div>
        </div>
        <div className={v.goneMore}>
          <Button block onClick={() => void loadSharedProfile(username, { force: true })}>{t("invite.tryAgain")}</Button>
        </div>
      </div>
    );
  } else if (!user || status === "idle" || status === "loading" || status === "ready") {
    // still asking, or on the way into the app
    body = <div className={s.waiting} data-testid="profile-link-loading"><Spinner /></div>;
  } else {
    const next = encodeURIComponent(loc.pathname);
    body = (
      <div className={s.profile} data-testid="profile-link-page">
        <ProfileCard
          userId={user.id}
          variant="modal"
          visitor={{
            user,
            widgets: user.widgets,
            badges: user.badges,
            actions: (
              <>
                <Link to={`/login?next=${next}`} className={p.primary} data-testid="profile-link-login">{t("profileLink.logInToAddFriend")}</Link>
                <Link to={`/register?next=${next}`} className={p.pill} data-testid="profile-link-register">{t("invite.createAnAccount")}</Link>
              </>
            ),
          }}
        />
      </div>
    );
  }

  return (
    <div className={s.page} style={glow ? ({ "--profile-glow": glow } as CSSProperties) : undefined} data-glow={glow ? "" : undefined}>
      <AndroidBanner bleed />
      {/* what this is, for someone who has never heard of it, and a way on from a dead link */}
      <Link to="/" className={s.brand} aria-label="Nexa"><NexaWordmark height={32} /></Link>
      <main className={s.main}>{body}</main>
    </div>
  );
}
