import { Download, ExternalLink, Laptop, Monitor, Smartphone, Terminal } from "lucide-react";
import { useEffect, useState, type ReactNode } from "react";
import { Link } from "react-router-dom";
import { android, type AndroidFile, type AndroidRelease } from "@/api/android";
import { desktop, isCurrentRelease, type DesktopFile, type DesktopRelease } from "@/api/desktop";
import { Button } from "@/components/common/Button";
import b from "@/components/common/Button.module.css";
import { Spinner } from "@/components/common/Spinner";
import { currentAndroidAbi, currentPlatform, isAndroidBrowser, noDesktopBuild, pickAndroid, pickDownload, type AndroidAbi, type DesktopTarget } from "@/lib/platform";
import { formatBytes, formatDateDivider } from "@/lib/time";
import { GITHUB_URL } from "@/lib/legal";
import { copyText } from "@/lib/copyText";
import { useUI } from "@/stores/ui";
import { usePageTitle } from "./AuthLayout";
import s from "./DownloadPage.module.css";

import { t, Tr } from "@/i18n";
/** The name each build goes by, per render so it follows the language. */
function labels(): Record<string, string> {
  return {
    "windows:installer": "Windows",
    "mac:installer": "macOS",
    "linux:appimage": t("download.linuxAppImage"),
    "linux:deb": t("download.linuxDeb"),
  };
}
function arches(): Record<DesktopFile["arch"], Record<DesktopFile["os"], string>> {
  return {
    x64: { windows: t("download.arch64Bit"), mac: "Intel", linux: "x86-64" },
    arm64: { windows: "ARM", mac: t("download.appleSiliconM1"), linux: "ARM64" },
  };
}
/**
 * What each Android build is for, in words a person can act on: an APK's own
 * name for a processor, `armeabi-v7a`, says nothing about whether it is the
 * one their phone wants.
 */
function abiLabel(abi: string): string {
  const named: Record<string, string> = {
    "arm64-v8a": t("download.abi.arm64"),
    "armeabi-v7a": t("download.abi.arm32"),
    universal: t("download.abi.universal"),
  };
  return named[abi] ?? abi;
}
const ORDER: DesktopFile["os"][] = ["windows", "mac", "linux"];
const ABI_ORDER = ["arm64-v8a", "armeabi-v7a", "x86_64", "universal"];
/** Most Windows and Linux computers are x86-64 and most Macs sold now are Apple's own chips, so each system lists its common build first. */
const archRank = (f: DesktopFile) => (f.os === "mac" ? (f.arch === "arm64" ? 0 : 1) : f.arch === "x64" ? 0 : 1);

function icon(os: DesktopFile["os"]) {
  return os === "mac" ? <Laptop size={22} /> : os === "linux" ? <Terminal size={22} /> : <Monitor size={22} />;
}

/** One download in the list: what it is, its size and hash, and the link to it. */
interface Row {
  key: string;
  icon: ReactNode;
  name: string;
  /** said before the hash: a size, and for Android the oldest version it installs on */
  facts: string;
  sha256: string;
  href: string;
  mine: boolean;
}

/** Every download, with the visitor's own first: the Android app on an Android phone, the installer on a computer. Needs no account. */
export default function DownloadPage() {
  const [rel, setRel] = useState<DesktopRelease | null | undefined>(undefined);
  const [target, setTarget] = useState<DesktopTarget>({ os: null, arch: null });
  const [failed, setFailed] = useState(false);
  const [apk, setApk] = useState<AndroidRelease | null | undefined>(undefined);
  const [apkFailed, setApkFailed] = useState(false);
  // Which processor build this phone takes. Most browsers never say, and the
  // 64-bit build is what nearly every phone wants, so that is offered until
  // an answer says otherwise.
  const [abi, setAbi] = useState<AndroidAbi | null>(null);
  const [onAndroid] = useState(isAndroidBrowser);
  // A phone or a tablet runs Nexa in its browser; the page still lists every
  // installer, for the computer the visitor may be choosing one for.
  const [handheld] = useState(noDesktopBuild);
  // how a home-screen app is made differs by system, and the page can say which steps apply here
  const [homeSteps] = useState(() => {
    const ua = typeof navigator === "undefined" ? "" : navigator.userAgent;
    if (/iPhone|iPad|iPod/.test(ua) || (/Macintosh/.test(ua) && navigator.maxTouchPoints > 1)) return t("download.iosSteps");
    if (/Android/.test(ua)) return t("download.androidSteps");
    return null;
  });
  const toast = useUI((u) => u.toast);

  const loadDesktop = () => {
    setFailed(false);
    desktop.latest().then((r) => setRel(r && isCurrentRelease(r) ? r : null), () => setFailed(true));
  };
  const loadAndroid = () => {
    setApkFailed(false);
    android.latest().then(setApk, () => setApkFailed(true));
  };

  usePageTitle(t("home.nav.download"));
  useEffect(() => {
    void currentPlatform().then(setTarget);
    if (onAndroid) void currentAndroidAbi().then(setAbi);
    loadDesktop();
    loadAndroid();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // An Android phone sees the app first. Until the answer comes the page
  // expects one, since a published build is the usual case; with none, the
  // phone is told Nexa runs in its browser, as before.
  const androidFirst = onAndroid && apk !== null;
  const mine = rel ? pickDownload(rel, target) : null;
  // a Mac that would not say which chip it has gets the other one offered too
  const otherMac = rel && mine && target.os === "mac" && target.arch === null ? rel.files.find((f) => f.os === "mac" && f.kind === "installer" && f.arch !== mine.arch) : undefined;
  const shown = rel ? rel.files.filter((f) => f.kind !== "zip").sort((a, b) => ORDER.indexOf(a.os) - ORDER.indexOf(b.os) || a.kind.localeCompare(b.kind) || archRank(a) - archRank(b)) : [];
  const apkFiles = apk ? [...apk.files].sort((a, b) => ABI_ORDER.indexOf(a.abi) - ABI_ORDER.indexOf(b.abi)) : [];
  const myApk = apk && onAndroid ? pickAndroid(apk.files, abi) : null;
  const byAbi = (want: string) => apkFiles.find((f) => f.abi === want && f.name !== myApk?.name);
  const arm32 = byAbi("armeabi-v7a");
  const universal = byAbi("universal");
  // A clipboard write rejects in a non-secure context and when the permission
  // is refused; with no catch the success toast simply never came and nothing
  // was said at all.
  const copyHash = (sha256: string) =>
    void copyText(sha256).then((ok) => (ok ? toast(t("download.sha256Copied"), "success") : toast(t("download.theHashCouldNotBeCopied"), "error")));

  const desktopRows: Row[] = shown.map((f) => ({
    key: f.name,
    icon: icon(f.os),
    name: `${labels()[`${f.os}:${f.kind}`] ?? f.name} · ${arches()[f.arch][f.os]}`,
    facts: formatBytes(f.size),
    sha256: f.sha256,
    href: desktop.fileUrl(f.name),
    mine: !androidFirst && mine?.name === f.name,
  }));
  const androidRows: Row[] = apkFiles.map((f: AndroidFile) => ({
    key: f.name,
    icon: <Smartphone size={22} />,
    name: `${t("download.android")} · ${abiLabel(f.abi)}`,
    facts: `${formatBytes(f.size)} · ${t("download.androidMin", { version: apk?.min_android ?? "" })}`,
    sha256: f.sha256,
    href: android.fileUrl(f),
    mine: androidFirst && myApk?.name === f.name,
  }));
  const rows = androidFirst ? [...androidRows, ...desktopRows] : [...desktopRows, ...androidRows];
  const versionOf = (version: string, releasedAt: number) => `${t("download.versionLine", { version })}${releasedAt ? ` · ${t("download.released", { date: formatDateDivider(releasedAt) })}` : ""}`;
  const otherVersion = (f: AndroidFile, label: string) => (
    <a href={android.fileUrl(f)} data-testid={`download-android-${f.abi}`}>{label}</a>
  );

  return (
    <div className={s.page}>
      <main className={s.card}>
        <div className={s.head}>
          <img className={s.logo} src="/icons/icon-192.png" alt="" />
          <h1 className={s.title}>{androidFirst ? t("download.nexaForAndroid") : t("download.nexaForYourComputer")}</h1>
          <p className={s.sub}>{androidFirst ? t("download.androidSub") : t("download.rpcSupportAndABetterExperience")}</p>
        </div>

        {androidFirst ? (
          apk === undefined && !apkFailed ? (
            <div className={s.primary}><Spinner size={28} label={t("download.loadingDownloads")} /></div>
          ) : apkFailed || !apk ? (
            // the request failed: the app may well be published, and this page simply could not ask
            <div className={s.empty} role="alert" data-testid="download-android-failed">
              {t("download.androidCouldNotLoad")}
              <div style={{ marginTop: 8 }}><Button size="sm" variant="secondary" className={s.touchButton} onClick={loadAndroid}>{t("chat.messageList.tryAgain")}</Button></div>
            </div>
          ) : myApk ? (
            <>
              <div className={s.primary}>
                <a className={s.bigButton} href={android.fileUrl(myApk)} data-testid="download-android" data-abi={myApk.abi}>
                  <Download size={20} /> {t("download.downloadFor", { what: "Android" })}
                </a>
                <span className={s.meta}>{versionOf(apk.version, apk.released_at)} · {formatBytes(myApk.size)} · {t("download.androidMin", { version: apk.min_android })}</span>
                {/* the build for this phone's processor is the one nearly everyone wants; the rest are for a phone that refuses it */}
                {arm32 && universal ? (
                  <span className={s.meta}><Tr id="download.androidOtherVersions" values={{ arm32: otherVersion(arm32, t("download.arm32Version")), universal: otherVersion(universal, t("download.universalVersion")) }} /></span>
                ) : universal ? (
                  <span className={s.meta}><Tr id="download.androidUniversalInstead" values={{ universal: otherVersion(universal, t("download.universalVersion")) }} /></span>
                ) : null}
              </div>
              {/* the step every first install meets, said before it is met */}
              <div className={s.handheld} data-testid="download-android-steps">
                <Smartphone size={20} aria-hidden />
                <span>{t("download.androidAllowInstalls")}</span>
              </div>
            </>
          ) : null
        ) : (
          <>
            {handheld ? (
              <div className={s.handheld} data-testid="download-handheld">
                <Smartphone size={20} aria-hidden />
                <span>
                  {t("download.onAPhoneOrTablet")}
                  {homeSteps ? <span className={s.steps}>{homeSteps}</span> : null}
                </span>
              </div>
            ) : null}

            {rel === undefined && !failed ? (
              <div className={s.primary}><Spinner size={28} label={t("download.loadingDownloads")} /></div>
            ) : failed ? (
              <div className={s.empty} role="alert">
                {t("download.theDownloadsCouldNotBeLoaded")}
                <div style={{ marginTop: 8 }}><Button size="sm" variant="secondary" className={s.touchButton} onClick={loadDesktop}>{t("chat.messageList.tryAgain")}</Button></div>
                <div style={{ marginTop: 8 }}><a href={`${GITHUB_URL}/releases/latest`} target="_blank" rel="noreferrer">{t("project.viewGithub")}</a></div>
              </div>
            ) : !rel ? (
              <div className={s.empty}>
                {t("download.theDesktopAppHasNotBeen")}
                <div style={{ marginTop: 12 }}>
                  <a className={s.bigButton} href={`${GITHUB_URL}/releases/latest`} target="_blank" rel="noreferrer" data-testid="download-github">
                    <ExternalLink size={20} /> {t("project.viewGithub")}
                  </a>
                </div>
              </div>
            ) : mine ? (
              <div className={s.primary}>
                <a className={s.bigButton} href={desktop.fileUrl(mine.name)} data-testid="download-primary">
                  <Download size={20} /> {t("download.downloadFor", { what: labels()[`${mine.os}:${mine.kind}`]?.split(" (")[0] ?? mine.os })}
                </a>
                {/* when it came out: the one fact that says whether a project is alive, on the page where a stranger decides to trust an installer */}
                <span className={s.meta}>{versionOf(rel.version, rel.released_at)} · {arches()[mine.arch][mine.os]} · {formatBytes(mine.size)}</span>
                {otherMac ? (
                  <span className={s.meta}>
                    {otherMac.arch === "x64" ? `${t("download.onIntelMac")} ` : `${t("download.onAppleSilicon")} `}
                    <a href={desktop.fileUrl(otherMac.name)} data-testid="download-other-mac">{t("download.downloadThatVersion")}</a>
                  </span>
                ) : null}
              </div>
            ) : null}
          </>
        )}

        {rows.length > 0 ? (
          <div className={s.section}>
            <h2 className={s.label}>{t("download.allDownloads")}</h2>
            <div className={s.files}>
              {rows.map((f) => (
                <div key={f.key} className={s.file} data-mine={f.mine} data-testid="download-file">
                  <span className={s.fileIcon}>{f.icon}</span>
                  <span className={s.fileText}>
                    <span className={s.fileName}>{f.name}</span>
                    <span className={s.fileSub}>
                      {f.facts} · <button type="button" className={s.hash} dir="ltr" title={t("download.copySha256")} aria-label={t("download.copySha256")} onClick={() => copyHash(f.sha256)}>SHA-256 {f.sha256.slice(0, 16)}…</button>
                    </span>
                  </span>
                  {/* A link to the file, as the big button is: it can be opened in a new tab, its address copied, and a screen reader calls it a link. */}
                  <a className={[b.btn, b.sm, f.mine ? b.primary : b.secondary, s.touchButton, s.fileLink].join(" ")} href={f.href} download>
                    <Download size={16} /> {t("chat.attachments.download")}
                  </a>
                </div>
              ))}
            </div>
          </div>
        ) : null}

        {rel || apk ? (
          <div className={s.section}>
            <h2 className={s.label}>{t("download.theFirstTimeYouOpenIt")}</h2>
            <ul className={s.notes}>
              {/* on an Android phone the same step is already said beside its button */}
              {apk && !androidFirst ? <li><Tr id="download.firstRunAndroid" values={{ android: <b>{t("download.android")}</b> }} /></li> : null}
              {rel ? (
                <>
                  <li><Tr id="download.firstRunWindows" values={{ windows: <b>{t("download.windows")}</b>, moreInfo: <b>{t("download.moreInfo")}</b>, runAnyway: <b>{t("download.runAnyway")}</b> }} /></li>
                  <li><Tr id="download.firstRunMac" values={{ macos: <b>macOS</b>, openAnyway: <b>{t("download.openAnyway")}</b> }} /></li>
                  <li><Tr id="download.firstRunLinux" values={{ linux: <b>{t("download.linux")}</b>, chmod: <code>{t("download.chmodX")}</code> }} /></li>
                </>
              ) : null}
            </ul>
          </div>
        ) : null}

        <div className={s.foot}>
          <Link to="/channels/@me">{t("download.openNexaInYourBrowser")}</Link>
        </div>

      </main>
    </div>
  );
}
