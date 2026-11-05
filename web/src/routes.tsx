import { Suspense } from "react";
import { Navigate, Route, Routes } from "react-router-dom";
import { lazyPage } from "@/lib/lazyChunk";
import { FullScreenSpinner } from "@/components/common/Spinner";
import { RedirectIfAuthed, RequireAuth } from "@/components/shell/RequireAuth";
import { hasSavedSession } from "@/stores/auth";

const HomePage = lazyPage(() => import("@/pages/HomePage"));
const LoginPage = lazyPage(() => import("@/pages/LoginPage"));
const QrApprovePage = lazyPage(() => import("@/pages/QrApprovePage"));
const RegisterPage = lazyPage(() => import("@/pages/RegisterPage"));
const RecoverPage = lazyPage(() => import("@/pages/RecoverPage"));
const FriendsPage = lazyPage(() => import("@/pages/FriendsPage"));
const DMPage = lazyPage(() => import("@/pages/DMPage"));
const ServerPage = lazyPage(() => import("@/pages/ServerPage"));
const InvitePage = lazyPage(() => import("@/pages/InvitePage"));
const StickerPackPage = lazyPage(() => import("@/pages/StickerPackPage"));
const DownloadPage = lazyPage(() => import("@/pages/DownloadPage"));
const GuestCallPage = lazyPage(() => import("@/pages/GuestCallPage"));
const LegalPage = lazyPage(() => import("@/pages/LegalPage"));
const NexaPage = lazyPage(() => import("@/pages/NexaPage"));
const NexaCasePage = lazyPage(() => import("@/pages/NexaCasePage"));
const NexaBugPage = lazyPage(() => import("@/pages/NexaBugPage"));
const NotesPage = lazyPage(() => import("@/pages/NotesPage"));
const SupportPage = lazyPage(() => import("@/components/support/SupportPage"));
const NoteLinkPage = lazyPage(() => import("@/pages/NoteLinkPage"));
const DiscordConnectedPage = lazyPage(() => import("@/pages/DiscordConnectedPage"));
const NotFoundPage = lazyPage(() => import("@/pages/NotFoundPage"));
const ProfilePage = lazyPage(() => import("@/pages/ProfilePage"));
const DiscoveryPage = lazyPage(() => import("@/components/discovery/DiscoveryPage"));
const AuthorizePage = lazyPage(() => import("@/pages/AuthorizePage"));
const GiftPage = lazyPage(() => import("@/pages/GiftPage"));
// The app itself: its sidebars, conversations, calls and every store behind
// them. The public pages (the front page, signing in, an invite) never load it.
const loadAppShell = () => import("@/components/shell/AppShell");
const AppShell = lazyPage(() => loadAppShell().then((m) => ({ default: m.AppShell })));
// Someone with a session opening the app is on their way into it: fetch it
// alongside the check of that session rather than only after it.
if (typeof location !== "undefined" && location.pathname.startsWith("/channels") && hasSavedSession()) {
  void loadAppShell().catch(() => undefined);
  void import("@/gateway/session").catch(() => undefined);
}
// The panel and its tabs come in one file; the tabs are routes of their own
// so "@admin/cases/:caseId" outranks ":serverId/:channelId/:messageId".
const adminPanel = () => import("@/pages/AdminPanelPage");
const AdminPanelPage = lazyPage(adminPanel);
const AdminUsers = lazyPage(() => adminPanel().then((m) => ({ default: m.UserGrid })));
const AdminUser = lazyPage(() => adminPanel().then((m) => ({ default: m.PersonPage })));
const AdminServers = lazyPage(() => adminPanel().then((m) => ({ default: m.ServersTab })));
const AdminBots = lazyPage(() => adminPanel().then((m) => ({ default: m.BotsTab })));
const AdminCalls = lazyPage(() => adminPanel().then((m) => ({ default: m.CallsTab })));
const AdminServer = lazyPage(() => adminPanel().then((m) => ({ default: m.ServerPage })));
const AdminExpressions = lazyPage(() => adminPanel().then((m) => ({ default: m.ExpressionsTab })));
const AdminPack = lazyPage(() => adminPanel().then((m) => ({ default: m.PackPage })));
const AdminCases = lazyPage(() => adminPanel().then((m) => ({ default: m.CasesTab })));
const AdminLogs = lazyPage(() => adminPanel().then((m) => ({ default: m.LogsTab })));
const AdminAnnouncements = lazyPage(() => adminPanel().then((m) => ({ default: m.AnnouncementsTab })));
const AdminBadges = lazyPage(() => adminPanel().then((m) => ({ default: m.BadgesTab })));
const AdminGames = lazyPage(() => adminPanel().then((m) => ({ default: m.GamesTab })));
const AdminReports = lazyPage(() => import("@/components/reports/ReportsTab").then((m) => ({ default: m.ReportsTab })));
const AdminBugs = lazyPage(() => adminPanel().then((m) => ({ default: m.BugsTab })));

export function AppRoutes() {
  return (
    <Suspense fallback={<FullScreenSpinner />}>
      <Routes>
        <Route path="/" element={<HomePage />} />
        <Route path="/login" element={<RedirectIfAuthed><LoginPage /></RedirectIfAuthed>} />
        <Route path="/login/qr" element={<QrApprovePage />} />
        <Route path="/register" element={<RegisterPage />} />
        <Route path="/recover" element={<RecoverPage />} />
        <Route path="/invite/:code" element={<InvitePage />} />
        {/* a note's share link: its key is in the part after the #, which never leaves the device */}
        <Route path="/note/:token" element={<NoteLinkPage />} />
        <Route path="/download" element={<DownloadPage />} />
        <Route path="/terms" element={<LegalPage page="terms" />} />
        <Route path="/privacy" element={<LegalPage page="privacy" />} />
        <Route path="/about" element={<LegalPage page="about" />} />
        {/* a guest link: the call and nothing else, with no account */}
        <Route path="/call/:code" element={<GuestCallPage />} />
        <Route path="/stickers/pack/:packId" element={<RequireAuth><StickerPackPage /></RequireAuth>} />
        {/* a link that adds an app: what it is, where it goes and what it may do there */}
        {/* a plan bought as a link: whoever opens it first claims it */}
        <Route path="/gift/:code" element={<GiftPage />} />
        <Route path="/oauth2/authorize" element={<RequireAuth><AuthorizePage /></RequireAuth>} />
        {/* where the browser lands after linking Discord from the desktop app: how it went, signed in or not */}
        <Route path="/connected/discord" element={<DiscordConnectedPage />} />
        <Route
          path="/channels/*"
          element={
            <RequireAuth>
              <AppShell>
                <Routes>
                  <Route path="@me" element={<FriendsPage />} />
                  <Route path="@me/nexa" element={<NexaPage />} />
                  <Route path="@me/nexa/cases/:caseId" element={<NexaCasePage />} />
                  <Route path="@me/nexa/bugs/:reportId" element={<NexaBugPage />} />
                  {/* the list and the note open beside it: one page, so moving between notes keeps the list */}
                  <Route path="@me/support" element={<SupportPage />} />
                  <Route path="@me/notes" element={<NotesPage />} />
                  <Route path="@me/notes/:noteId" element={<NotesPage />} />
                  <Route path="@admin" element={<AdminPanelPage />}>
                    <Route index element={<Navigate to="reports" replace />} />
                    <Route path="users" element={<AdminUsers />} />
                    <Route path="users/:userId" element={<AdminUser />} />
                    <Route path="users/:userId/cases" element={<AdminUser />} />
                    <Route path="users/:userId/cases/:caseId" element={<AdminUser />} />
                    <Route path="bots" element={<AdminBots />} />
                    <Route path="calls" element={<AdminCalls />} />
                    <Route path="servers" element={<AdminServers />} />
                    <Route path="discovery" element={<Navigate to="/channels/@admin/servers?discover=all" replace />} />
                    <Route path="servers/:serverId" element={<AdminServer />} />
                    <Route path="expressions" element={<AdminExpressions />} />
                    <Route path="expressions/uploads" element={<AdminExpressions />} />
                    <Route path="expressions/packs/:packId" element={<AdminPack />} />
                    <Route path="cases" element={<AdminCases />} />
                    <Route path="cases/:caseId" element={<AdminCases />} />
                    <Route path="logs" element={<AdminLogs />} />
                    <Route path="announcements" element={<AdminAnnouncements />} />
                    <Route path="badges" element={<AdminBadges />} />
                    <Route path="games" element={<AdminGames />} />
                    <Route path="reports" element={<AdminReports />} />
                    <Route path="reports/:reportId" element={<AdminReports />} />
                    <Route path="bugs" element={<AdminBugs />} />
                    <Route path="bugs/:reportId" element={<AdminBugs />} />
                    <Route path="*" element={<Navigate to="reports" replace />} />
                  </Route>
                  <Route path="@discover" element={<DiscoveryPage />} />
                  <Route path="@me/:channelId" element={<DMPage />} />
                  <Route path="@me/:channelId/:messageId" element={<DMPage />} />
                  <Route path=":serverId" element={<ServerPage />} />
                  <Route path=":serverId/:channelId" element={<ServerPage />} />
                  <Route path=":serverId/:channelId/:messageId" element={<ServerPage />} />
                  {/* inside the app an unknown address is usually a conversation that went, and Friends is the place for it */}
                  <Route path="*" element={<Navigate to="/channels/@me" replace />} />
                </Routes>
              </AppShell>
            </RequireAuth>
          }
        />
        {/* a profile link, /@username; any other one-part address is not found there too */}
        <Route path="/:handle" element={<ProfilePage />} />
        <Route path="*" element={<NotFoundPage />} />
      </Routes>
    </Suspense>
  );
}
