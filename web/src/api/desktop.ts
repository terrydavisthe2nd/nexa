import { api, ApiError } from "./client";

export interface DesktopFile {
  os: "windows" | "mac" | "linux";
  arch: "x64" | "arm64";
  /** installer (.exe, .dmg) | appimage | deb | zip (the Mac updater's) */
  kind: "installer" | "appimage" | "deb" | "zip";
  name: string;
  size: number;
  sha256: string;
}

export interface DesktopRelease {
  version: string;
  released_at: number;
  files: DesktopFile[];
}

/** Tauri releases start at 2.0.0. Older Electron builds stay downloadable from
 *  an old DESKTOP_DIR for copies already installed, but they are not offered to
 *  anyone new. */
export function isCurrentRelease(release: DesktopRelease): boolean {
  const major = Number.parseInt(release.version.replace(/^v/i, ""), 10);
  return Number.isFinite(major) && major >= 2;
}

export const desktop = {
  /** The newest published desktop app, or null when there is none yet. */
  async latest(): Promise<DesktopRelease | null> {
    try {
      return await api.request<DesktopRelease>("GET", "/desktop/latest");
    } catch (err) {
      if (err instanceof ApiError && err.status === 404) return null;
      throw err;
    }
  },
  /** Where a published file downloads from. */
  fileUrl(name: string): string {
    return `/desktop/${encodeURIComponent(name)}`;
  },
};
