import { createElement } from "react";
import type { NavigateFunction } from "react-router-dom";
import type { Channel } from "@/api/types";
import { followAppLink } from "@/components/shell/AppLinks";
import { t } from "@/i18n";
import { appLink } from "@/lib/appLinks";
import { useUI } from "@/stores/ui";

/**
 * Link channels: a name in a server's channel list that opens an address
 * instead of holding messages (type "link", its address in `url`). One that
 * leads to Nexa itself (an invite, a profile, a channel) opens inside the
 * app; anything else is shown in full first and opens in a new tab, or the
 * system browser in the desktop app.
 */

/** A link channel's address as the apps open it: a whole http or https address with a host and no password in it. */
export function linkDestination(raw: string | undefined | null): { href: string; host: string } | null {
  const text = raw?.trim();
  if (!text || text.length > 1024 || /[\s\u0000-\u001f\u007f]/.test(text)) return null;
  try {
    const url = new URL(text);
    if ((url.protocol !== "http:" && url.protocol !== "https:") || !url.hostname || url.username || url.password) return null;
    return { href: url.href, host: url.host };
  } catch {
    return null;
  }
}

/** Opens what a link channel leads to: in the app when it is Nexa's own, otherwise after showing the whole address. */
export function openLinkChannel(channel: Pick<Channel, "name" | "url">, nav: NavigateFunction): void {
  const dest = linkDestination(channel.url);
  const ui = useUI.getState();
  if (!dest) {
    ui.toast(t("channelLink.broken"), "error");
    return;
  }
  const own = appLink(dest.href);
  if (own) {
    followAppLink(own, nav);
    return;
  }
  ui.openModal("confirm", {
    title: t("channelLink.openTitle"),
    body: createElement(
      "span",
      null,
      t("channelLink.openBody", { name: channel.name }),
      createElement("span", { dir: "ltr", style: { display: "block", marginTop: 8, overflowWrap: "anywhere", unicodeBidi: "isolate", fontWeight: 600 } }, dest.href),
    ),
    confirmLabel: t("textMenu.openLink"),
    onConfirm: () => {
      window.open(dest.href, "_blank", "noopener,noreferrer");
    },
  });
}
