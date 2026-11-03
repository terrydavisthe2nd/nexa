import { openReport } from "@/lib/reporting";
import { Bug, CodeXml, Download, Flag, LogOut, Smartphone } from "lucide-react";
import { GITHUB_URL } from "@/lib/legal";
import { Suspense, useEffect, useMemo, useState } from "react";
import { android } from "@/api/android";
import { SearchBox } from "@/components/common/SearchBox";
import { Spinner } from "@/components/common/Spinner";
import { lazyPage } from "@/lib/lazyChunk";
import { NavIcon, SettingsLayer, navIconOf, type NavGroup } from "@/components/server/SettingsLayer";
import { useAuth } from "@/stores/auth";
import { useUI } from "@/stores/ui";
import { desktopBridge } from "@/lib/desktopBridge";
import { isAndroidBrowser } from "@/lib/platform";
import { useIsMobile } from "@/hooks/useIsMobile";
import { searchSettings, settingsIndex, type SettingEntry } from "./settingsIndex";
import s from "./Settings.module.css";

import { t } from "@/i18n";
import { atLeast, useMyRank } from "@/lib/staffRank";
import { confirmLogout } from "@/lib/confirmLogout";
import { openReportBug } from "@/components/bugs/ReportBugButton";
const AccountPage = lazyPage(() => import("./AccountPage"));
const ProfilePage = lazyPage(() => import("./ProfilePage"));
const PrivacyPage = lazyPage(() => import("./PrivacyPage"));
const ActivityPage = lazyPage(() => import("./ActivityPage"));
const BlockedPage = lazyPage(() => import("./BlockedPage"));
const YourReportsPage = lazyPage(() => import("./YourReportsPage"));
const SecurityPage = lazyPage(() => import("./SecurityPage"));
const DevicesPage = lazyPage(() => import("./DevicesPage"));
const SubscriptionPage = lazyPage(() => import("@/components/support/SupportPage").then((m) => ({ default: () => <m.SupportPanel inSettings /> })));
const DataPage = lazyPage(() => import("./DataPage"));
const ChatPage = lazyPage(() => import("./ChatPage"));
const AppearancePage = lazyPage(() => import("./AppearancePage"));
const AccessibilityPage = lazyPage(() => import("./AccessibilityPage"));
const LanguagePage = lazyPage(() => import("./LanguagePage"));
const CustomCssPage = lazyPage(() => import("./CustomCssPage"));
const PluginsPage = lazyPage(() => import("./PluginsPage"));
const NotificationsPage = lazyPage(() => import("./NotificationsPage"));
const SoundsPage = lazyPage(() => import("./SoundsPage"));
const StickersPage = lazyPage(() => import("./StickersPage"));
const VoicePage = lazyPage(() => import("./VoicePage"));
const KeybindsPage = lazyPage(() => import("./KeybindsPage"));
const AdvancedPage = lazyPage(() => import("./AdvancedPage"));
const AdminPage = lazyPage(() => import("./AdminPage"));
const DesktopPage = lazyPage(() => import("./DesktopPage"));
const LicensesPage = lazyPage(() => import("./LicensesPage"));
const AboutPage = lazyPage(() => import("./AboutPage"));
const DeveloperPortalPage = lazyPage(() => import("./portal/DeveloperPortalPage"));
const AuthorizedAppsPage = lazyPage(() => import("./AuthorizedAppsPage"));

/**
 * The navigation, built per render so it follows the language. Grouped by
 * what people come looking for rather than by when each page was added:
 * who they are, who reaches them, how conversations read, how Nexa looks,
 * the rest of the app, and what Nexa is.
 */
function navGroups(opts: { inApp: boolean; admin: boolean }): NavGroup[] {
  const groups: NavGroup[] = [
    {
      head: t("settings.groups.account"),
      items: [
        { id: "account", label: t("settings.account.myAccount") },
        { id: "profiles", label: t("settings.profile.profiles") },
        { id: "security", label: t("settings.security.security") },
        { id: "devices", label: t("settings.devices.devices") },
        { id: "subscription", label: t("support.settingsLabel") },
      ],
    },
    {
      head: t("settings.privacy.privacySafety"),
      items: [
        { id: "privacy", label: t("settings.privacy.title") },
        { id: "blocked", label: t("people.blockedPage.title") },
        { id: "reports", label: t("reports.yours.title") },
        { id: "authorizedApps", label: t("apps.authorized.title") },
        { id: "data", label: t("settings.privacy.yourData") },
      ],
    },
    {
      head: t("settings.groups.chat"),
      items: [
        { id: "chat", label: t("settings.chat.title") },
        { id: "notifications", label: t("settings.notifications.notifications") },
        { id: "sounds", label: t("settings.sounds.title") },
        { id: "stickers", label: t("settings.groups.emojiStickers") },
      ],
    },
    {
      head: t("settings.groups.look"),
      items: [
        { id: "appearance", label: t("settings.appearance.appearance") },
        { id: "accessibility", label: t("settings.appearance.accessibility") },
        { id: "language", label: t("settings.appearance.language") },
        { id: "customCss", label: t("look.css.title") },
        { id: "plugins", label: t("look.plugins.title") },
      ],
    },
    {
      head: t("settings.groups.app"),
      items: [
        { id: "voice", label: t("settings.voice.voiceVideo") },
        { id: "keybinds", label: t("keybinds.title") },
        { id: "activity", label: t("settings.activity.title") },
        // only where there is a desktop app to have settings for
        ...(opts.inApp ? [{ id: "desktop", label: t("settings.desktop.desktopApp") }] : []),
        { id: "advanced", label: t("settings.privacy.advanced") },
        { id: "developers", label: t("apps.portal.title") },
      ],
    },
    {
      head: t("settings.groups.help"),
      items: [
        // About last of all: Terms, Privacy, the encryption note, and how to reach the owner
        { id: "about", label: t("settings.about.title") },
        { id: "licenses", label: t("settings.licenses.licenses") },
      ],
    },
  ];
  if (opts.admin) groups.push({ head: t("settings.settings.administration"), items: [{ id: "admin", label: t("settings.admin.nexaAdmin") }] });
  return groups;
}

/** Brings a setting into view once its page has drawn it, and lights it for a moment. */
function useFindSetting(page: string, anchor: string | null, done: () => void): void {
  useEffect(() => {
    if (!anchor) return;
    let frame = 0;
    const until = performance.now() + 4000;
    const look = () => {
      const el = document.querySelector<HTMLElement>(`[data-setting="${anchor}"]`);
      if (el) {
        el.scrollIntoView({ block: "center", behavior: "smooth" });
        el.setAttribute("data-found", "");
        window.setTimeout(() => el.removeAttribute("data-found"), 1800);
        done();
        return;
      }
      // a page is fetched the first time it opens, so it may take a moment to be there
      if (performance.now() < until) frame = requestAnimationFrame(look);
      else done();
    };
    frame = requestAnimationFrame(look);
    return () => cancelAnimationFrame(frame);
  }, [page, anchor, done]);
}

export default function SettingsModal({ onClose, page: initial, packId, modalId, resume, anchor: openAt, serverId }: { onClose: () => void; page: string; packId?: string | undefined; modalId?: number; resume?: string | undefined; anchor?: string | undefined; serverId?: string | undefined }) {
  // pages that were folded into others open where they went
  // The whole app is drawn again when the language changes, this with it: it
  // comes back on the page it was showing (kept with the modal), not the one
  // it was opened at.
  const [page, setPage] = useState(resume ?? (initial === "privacy-data" ? "data" : initial));
  useEffect(() => {
    if (modalId !== undefined) useUI.getState().setModalProps(modalId, { resume: page });
  }, [page, modalId]);
  // the instance's own settings are for developers and the owner
  const isAdmin = atLeast(useMyRank(), "developer");
  const inApp = !!desktopBridge();
  const mobile = useIsMobile();
  // an Android browser is offered the Android app instead, once one is published
  const [androidApp, setAndroidApp] = useState(false);
  useEffect(() => {
    if (!isAndroidBrowser()) return;
    let alive = true;
    android
      .latest()
      .then((rel) => {
        if (alive) setAndroidApp(!!rel);
      })
      .catch(() => undefined);
    return () => {
      alive = false;
    };
  }, []);
  const groups = navGroups({ inApp, admin: isAdmin });
  const [query, setQuery] = useState("");
  // opened to show one setting (a "Make Your Own" elsewhere), it is brought into view as a search result is
  const [anchor, setAnchor] = useState<string | null>(resume ? null : (openAt ?? null));
  const clearAnchor = useMemo(() => () => setAnchor(null), []);
  useFindSetting(page, anchor, clearAnchor);

  const pageLabels = useMemo(() => Object.fromEntries(groups.flatMap((g) => g.items.map((it) => [it.id, it.label]))), [groups]);
  const results = useMemo(() => (query.trim() ? searchSettings(settingsIndex({ pageLabels }), query) : null), [query, pageLabels]);
  const go = (entry: SettingEntry) => {
    setQuery("");
    setPage(entry.page);
    setAnchor(entry.anchor ?? null);
  };

  const body =
    page === "profiles" ? <ProfilePage anchor={anchor} serverId={serverId} /> :
    page === "privacy" ? <PrivacyPage /> :
    page === "data" ? <DataPage onDeleteAccount={() => go({ page: "account", label: "", anchor: "deleteAccount" })} /> :
    page === "activity" ? <ActivityPage /> :
    page === "security" ? <SecurityPage /> :
    page === "devices" ? <DevicesPage /> :
    page === "subscription" ? <SubscriptionPage /> :
    page === "chat" ? <ChatPage /> :
    page === "appearance" ? <AppearancePage /> :
    page === "accessibility" ? <AccessibilityPage onTextSize={() => go({ page: "appearance", label: "", anchor: "textSize" })} /> :
    page === "language" ? <LanguagePage /> :
    page === "customCss" ? <CustomCssPage /> :
    page === "plugins" ? <PluginsPage /> :
    page === "notifications" ? <NotificationsPage onSounds={() => setPage("sounds")} /> :
    page === "sounds" ? <SoundsPage /> :
    page === "stickers" ? <StickersPage initialPackId={packId} /> :
    page === "voice" ? <VoicePage /> :
    page === "keybinds" ? <KeybindsPage onVoice={() => setPage("voice")} /> :
    page === "advanced" ? <AdvancedPage /> :
    page === "blocked" ? <BlockedPage /> :
    page === "reports" ? <YourReportsPage /> :
    page === "licenses" ? <LicensesPage /> :
    page === "developers" ? <DeveloperPortalPage /> :
    page === "authorizedApps" ? <AuthorizedAppsPage /> :
    page === "about" ? <AboutPage /> :
    page === "admin" && isAdmin ? <AdminPage /> :
    page === "desktop" && inApp ? <DesktopPage onActivity={() => setPage("activity")} /> :
    <AccountPage onEditProfile={() => setPage("profiles")} />;

  // Log Out is an action rather than a page, so it closes the nav instead of joining a group.
  const footer = (
    <div className={s.navSection}>
      <div className={s.navSep} />
      <div className={s.navGroup}>
        {/* over the settings, which stay where they were */}
        <button className={s.navItem} onClick={() => window.open(GITHUB_URL, "_blank", "noopener,noreferrer")} data-nav="github">
          <NavIcon icon={CodeXml} />
          <span className={s.navLabel}>{t("project.github")}</span>
        </button>
        <button className={s.navItem} onClick={() => openReport()} data-nav="report-content"><NavIcon icon={Flag} /><span className={s.navLabel}>{t("reports.report")}</span></button>
        <button className={s.navItem} onClick={() => openReportBug("settings")} data-nav="report-bug">
          <NavIcon icon={Bug} />
          <span className={s.navLabel}>{t("bugs.report")}</span>
        </button>
        {androidApp ? (
          <button className={s.navItem} onClick={() => window.open("/download", "_blank", "noopener")} data-nav="get-app">
            <NavIcon icon={Smartphone} />
            <span className={s.navLabel}>{t("settings.settings.getTheAndroidApp")}</span>
          </button>
        ) : !inApp && !mobile ? (
          <button className={s.navItem} onClick={() => window.open("/download", "_blank", "noopener")} data-nav="get-app">
            <NavIcon icon={Download} />
            <span className={s.navLabel}>{t("settings.settings.getTheDesktopApp")}</span>
          </button>
        ) : null}
        <button className={[s.navItem, s.danger].join(" ")} onClick={confirmLogout} data-nav="logout">
          <NavIcon icon={LogOut} />
          <span className={s.navLabel}>{t("settings.settings.logOut")}</span>
        </button>
      </div>
    </div>
  );

  const search = (
    <div className={s.navSearch}>
      <SearchBox
        value={query}
        onChange={setQuery}
        placeholder={t("settings.search.placeholder")}
        aria-label={t("settings.search.placeholder")}
        onEscape={() => setQuery("")}
        onKeyDown={(e) => {
          if (e.key === "Enter" && results?.[0]) {
            e.preventDefault();
            go(results[0]);
          }
        }}
        data-testid="settings-search"
      />
    </div>
  );
  const found = results ? (
    <div className={s.navSection} role="group" aria-label={t("settings.search.results")} data-testid="settings-results">
      {results.length === 0 ? (
        <p className={s.navEmpty}>{t("settings.search.none", { query: query.trim() })}</p>
      ) : (
        <div className={s.navGroup}>
          {results.map((r) => {
            const Icon = navIconOf(r.page);
            return (
              <button key={`${r.page}|${r.label}`} className={s.navItem} onClick={() => go(r)} data-nav={`found-${r.page}`}>
                {Icon ? <NavIcon icon={Icon} /> : null}
                <span className={s.navLabel}>
                  <span className={s.foundLabel}>{r.label}</span>
                  {r.anchor ? <span className={s.foundPage}>{pageLabels[r.page]}</span> : null}
                </span>
              </button>
            );
          })}
        </div>
      )}
    </div>
  ) : null;

  return (
    // Opened at My Account is opened as a whole, from the user panel: a phone starts on the list.
    <SettingsLayer label={t("settings.settings.userSettings")} groups={groups} page={page} setPage={(id) => { setAnchor(null); setPage(id); }} onClose={onClose} footer={footer} startOnList={initial === "account" && !resume} navTop={search} navBody={found}>
      {/* A blank content column on a slow link is indistinguishable from a crash. */}
      <Suspense fallback={<div style={{ display: "flex", justifyContent: "center", padding: 40 }}><Spinner label={t("chat.pins.loading")} /></div>}>{body}</Suspense>
    </SettingsLayer>
  );
}
