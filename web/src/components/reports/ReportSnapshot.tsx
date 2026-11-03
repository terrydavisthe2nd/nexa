import { StickerView } from "@/components/stickers/StickerView";
import { Fragment } from "react";
import { api } from "@/api/client";
import { avatarUrl, emojiUrl, iconUrl, stickerUrl } from "@/api/users";
import { useBugFileURL } from "@/lib/bugMedia";
import { t } from "@/i18n";
import s from "./Reports.module.css";
const LABELS: Record<string, string> = {
  avatar_id: "avatar",
  banner_id: "banner",
  icon_id: "icon",
  cover_blob_id: "image",
  blob_id: "image",
  status_text: "status",
  status_emoji: "status",
  discovery_category: "discovery",
  discovery_tags: "tags",
  server_profile: "server_profile",
};
export function ReportSnapshot({
  snapshot,
  kind,
  saved = false,
  compact = false,
}: {
  snapshot: Record<string, unknown>;
  kind: string;
  saved?: boolean;
  compact?: boolean;
}) {
  return (
    <dl className={s.snapshot}>
      {Object.entries(snapshot)
        .filter(
          ([name, value]) =>
            !compact ||
            (value != null &&
              value !== "" &&
              [
                "username",
                "display_name",
                "name",
                "description",
                "about_me",
                "pronouns",
                "status_text",
                "topic",
                "tags",
                "avatar_id",
                "banner_id",
                "icon_id",
                "blob_id",
                "cover_blob_id",
              ].includes(name)),
        )
        .map(([name, value]) => {
          const key = `reports.part.${LABELS[name] ?? name}`;
          const translated = t(key);
          const label =
            translated === key ? name.replaceAll("_", " ") : translated;
          const mediaKind =
            name === "avatar_id"
              ? "avatar"
              : name === "banner_id"
                ? "banner"
                : name === "icon_id"
                  ? "icon"
                  : name === "cover_blob_id"
                    ? "sticker"
                    : name === "blob_id"
                      ? kind === "emoji"
                        ? "emoji"
                        : "sticker"
                      : null;
          return (
            <Fragment key={name}>
              <dt>{label}</dt>
              <dd>
                {mediaKind && typeof value === "string" ? (
                  saved ? (
                    <SavedPicture
                      id={value}
                      label={label}
                      format={String(
                        snapshot[
                          name === "cover_blob_id" ? "cover_format" : "format"
                        ] ?? "",
                      )}
                    />
                  ) : (
                    <img
                      src={api.url(`/media/${mediaKind}/${value}`)}
                      alt={label}
                      loading="lazy"
                    />
                  )
                ) : value === null ? (
                  t("reports.unspecified")
                ) : Array.isArray(value) ? (
                  value
                    .map((v) =>
                      typeof v === "object" ? JSON.stringify(v) : String(v),
                    )
                    .join(", ")
                ) : typeof value === "object" ? (
                  <details>
                    <summary>{label}</summary>
                    <ReportSnapshot
                      snapshot={value as Record<string, unknown>}
                      kind={kind}
                      saved={saved}
                    />
                  </details>
                ) : (
                  String(value)
                )}
              </dd>
            </Fragment>
          );
        })}
    </dl>
  );
}
function SavedPicture({
  id,
  label,
  format,
  size,
}: {
  id: string;
  label: string;
  format: string;
  size?: number;
}) {
  const { url, failed, retry } = useBugFileURL(id, undefined, true, "reports");
  return url ? (
    <a href={url} target="_blank" rel="noopener noreferrer">
      <StickerView
        src={url}
        alt={label}
        format={format}
        style={size ? { width: size, height: size, objectFit: "cover" } : { width: 160, height: 120, objectFit: "contain" }}
      />
    </a>
  ) : (
    <button type="button" className={s.pictureRetry} onClick={retry}>
      {failed ? t("reports.retry") : t("reports.loading")}
    </button>
  );
}

/** The text a card shows under the name, longest first, as the item had it. */
const CARD_TEXT = ["about_me", "description", "topic", "status_text", "url"];

/**
 * What is being reported, as a person recognises it: its picture, its name,
 * what kind of thing it is, and the words it carries. `saved` draws the copy
 * kept with a report (staff); otherwise the live public picture.
 */
export function ReportTargetCard({ kind, snapshot, saved = false }: { kind: string; snapshot: Record<string, unknown>; saved?: boolean }) {
  const str = (k: string) => (typeof snapshot[k] === "string" ? (snapshot[k] as string) : "");
  const name = str("display_name") || str("name") || str("username") || t(`reports.kind.${kind}`);
  const handle = kind === "user" && str("username") ? `@${str("username")}` : "";
  const picture: { id: string; mediaKind: string; format?: string } | null = str("avatar_id")
    ? { id: str("avatar_id"), mediaKind: "avatar" }
    : str("icon_id")
      ? { id: str("icon_id"), mediaKind: "icon" }
      : str("blob_id")
        ? { id: str("blob_id"), mediaKind: kind === "emoji" ? "emoji" : "sticker", format: str("format") }
        : str("cover_blob_id")
          ? { id: str("cover_blob_id"), mediaKind: "sticker", format: str("cover_format") }
          : null;
  const round = picture?.mediaKind === "avatar";
  const text = CARD_TEXT.map(str).filter(Boolean);
  return (
    <div className={s.targetCard} data-testid="report-target">
      {picture ? (
        <span className={s.targetPicture} data-round={round || undefined}>
          {saved ? (
            <SavedPicture id={picture.id} label={name} format={picture.format ?? ""} size={48} />
          ) : (
            <img src={livePicture(picture.mediaKind, picture.id)} alt="" loading="lazy" />
          )}
        </span>
      ) : null}
      <span className={s.targetText}>
        <span className={s.targetName} dir="auto">
          {name}
        </span>
        <span className={s.targetKind}>
          {t(`reports.kind.${kind}`)}
          {handle ? <span dir="ltr"> {handle}</span> : null}
        </span>
        {text.slice(0, 2).map((x, i) => (
          <span key={i} className={s.targetQuote} dir="auto">
            {x}
          </span>
        ))}
      </span>
    </div>
  );
}

function livePicture(kind: string, id: string): string {
  if (kind === "avatar") return avatarUrl(id, 48) ?? "";
  if (kind === "icon") return iconUrl(id, 48) ?? "";
  if (kind === "emoji") return emojiUrl(id, false, 48);
  return stickerUrl(id, 96);
}
