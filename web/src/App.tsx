import { Suspense, useEffect, useState } from "react";
import { installTabGlide } from "@/lib/tabGlide";
import { ContextMenuHost } from "@/components/common/ContextMenu";
import { Toasts } from "@/components/common/Toasts";
import { AppLockEffect } from "@/components/shell/AppLockEffect";
import { AppLinks } from "@/components/shell/AppLinks";
import { ErrorBoundary, markHealthy } from "@/components/shell/ErrorBoundary";
import { lazyPage, markChunksHealthy } from "@/lib/lazyChunk";
import { ThemeEffect } from "@/components/shell/ThemeEffect";
import { LookEffect } from "@/components/shell/LookEffect";
import { I18nProvider } from "@/i18n";
import { useNexa } from "@/stores/nexa";
import { useVoice } from "@/stores/voice";
import { useAppLocale } from "@/lib/appLocale";
import { desktopBridge } from "@/lib/desktopBridge";
import type { RequiredUpdate as Need } from "@/lib/requiredDesktop";
import { AppRoutes } from "./routes";

// Shown once in an account's life, if ever; the front page and the sign-in
// page need not download it on the chance.
const DeletedNotice = lazyPage(() => import("@/components/nexa/NexaNotices").then((m) => ({ default: m.DeletedNotice })));

// Only the desktop app looks whether it is too old for Nexa, and only one that is shows the screen.
const RequiredUpdate = lazyPage(() => import("@/components/shell/RequiredUpdate").then((m) => ({ default: m.RequiredUpdate })));
const RequiredUpdateCheck = lazyPage(() => import("@/components/shell/RequiredUpdate").then((m) => ({ default: m.RequiredUpdateCheck })));

/** The notice a moderator's deletion leaves, read on the way out of the app. */
function AccountDeletedNotice() {
  const deleted = useNexa((n) => n.deleted);
  if (!deleted) return null;
  return (
    <Suspense fallback={null}>
      <DeletedNotice />
    </Suspense>
  );
}

export default function App() {
  useEffect(markHealthy, []);
  useEffect(markChunksHealthy, []);
  // every tab strip's pill slides to the tab chosen
  useEffect(() => installTabGlide(), []);
  // The account carries the choice, so signing in anywhere is in the same
  // language; with none set the browser's own preference decides.
  const locale = useAppLocale();
  // a desktop app too old for Nexa shows nothing but its update
  // (found while the app is open, for instance as the release goes out, it waits for a call to end)
  const [required, setRequired] = useState<Need | null>(null);
  const check = desktopBridge() ? (
    <Suspense fallback={null}>
      <RequiredUpdateCheck onChange={setRequired} />
    </Suspense>
  ) : null;
  const inCall = useVoice((v) => !!v.mine);
  if (required && (required.manual || !inCall)) {
    return (
      <ErrorBoundary>
        <I18nProvider locale={locale}>
          <ThemeEffect />
          {check}
          <Suspense fallback={null}>
            <RequiredUpdate need={required} />
          </Suspense>
        </I18nProvider>
      </ErrorBoundary>
    );
  }
  return (
    <ErrorBoundary>
      <I18nProvider locale={locale}>
        <ThemeEffect />
        {/* second in both trees, so moving between them keeps it as it is */}
        {check}
        <LookEffect />
        <AppLockEffect />
        <AppRoutes />
        <AppLinks />
        <ContextMenuHost />
        <AccountDeletedNotice />
        <Toasts />
      </I18nProvider>
    </ErrorBoundary>
  );
}
