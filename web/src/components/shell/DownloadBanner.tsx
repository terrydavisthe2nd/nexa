import { Download, Monitor, X } from "lucide-react";
import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { desktop, isCurrentRelease, type DesktopFile } from "@/api/desktop";
import { useIsMobile } from "@/hooks/useIsMobile";
import { desktopBridge } from "@/lib/desktopBridge";
import { currentPlatform, isAndroidBrowser, pickDownload } from "@/lib/platform";
import { storage } from "@/lib/storage";
import { formatBytes } from "@/lib/time";
import { AndroidBanner } from "./AndroidBanner";
import s from "./DownloadBanner.module.css";

import { t } from "@/i18n";
const DISMISSED = "ec:desktopBanner";
const OS_NAME: Record<DesktopFile["os"], string> = { windows: "Windows", mac: "macOS", linux: "Linux" };

/**
 * Offers the app this device can run: the Android app to an Android browser
 * (AndroidBanner, back on every reload), the installer to a computer.
 */
export function DownloadBanner() {
  return isAndroidBrowser() ? <AndroidBanner /> : <DesktopBanner />;
}

/**
 * Offers the desktop app to a computer that can run it. Saying no thanks keeps
 * it away on that device; the desktop app itself, phones, tablets and anything
 * with no build never see it.
 */
function DesktopBanner() {
  const mobile = useIsMobile();
  // sure: the computer is known well enough to hand it a file straight away
  const [pick, setPick] = useState<{ file: DesktopFile; sure: boolean } | null>(null);
  const [closed, setClosed] = useState(() => storage.get(DISMISSED) !== null);

  useEffect(() => {
    if (closed || mobile || desktopBridge()) return;
    let alive = true;
    void Promise.all([desktop.latest(), currentPlatform()])
      .then(([rel, target]) => {
        const file = rel && isCurrentRelease(rel) ? pickDownload(rel, target) : null;
        if (alive && file) setPick({ file, sure: target.os !== "mac" || target.arch !== null });
      })
      .catch(() => {
        /* no banner is better than a broken one */
      });
    return () => {
      alive = false;
    };
  }, [closed, mobile]);

  const close = () => {
    storage.set(DISMISSED, String(Date.now()));
    setClosed(true);
  };

  if (closed || mobile || !pick) return null;
  const { file, sure } = pick;
  return (
    <div className={s.banner} role="region" aria-label={t("shell.downloadBanner.desktopApp")} data-testid="download-banner">
      <span className={s.icon}><Monitor size={18} /></span>
      <span className={s.text}>
        <b>{t("shell.downloadBanner.getNexaFor", { os: OS_NAME[file.os] })}</b>
        <span className={s.tagline}>{t("download.rpcSupportAndABetterExperience")}</span>
      </span>
      {sure ? (
        <>
          <a className={s.download} href={desktop.fileUrl(file.name)} data-testid="download-banner-button">
            <Download size={14} /> {t("shell.downloadBanner.download")} <span style={{ opacity: 0.7 }}>{formatBytes(file.size)}</span>
          </a>
          <Link className={s.other} to="/download">{t("shell.downloadBanner.otherPlatforms")}</Link>
        </>
      ) : (
        // a Mac that does not say which chip it has chooses between the two on the download page
        <Link className={s.download} to="/download" data-testid="download-banner-button">
          <Download size={14} /> {t("shell.downloadBanner.download")}
        </Link>
      )}
      <button className={s.close} aria-label={t("chat.attachments.close")} title={t("chat.attachments.close")} onClick={close} data-testid="download-banner-close"><X size={16} /></button>
    </div>
  );
}
