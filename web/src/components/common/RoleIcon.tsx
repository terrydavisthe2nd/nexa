import { useMediaAnimation } from "@/lib/mediaAnimation";
import { emojiUrl, iconUrl } from "@/api/users";
import type { RoleIconInfo } from "@/lib/roleLook";
import { useExpressions } from "@/stores/expressions";
import { Twemoji } from "./Twemoji";

/** A role's picture beside a name, named for the role it stands for. */
export function RoleIcon({ icon, size = 18 }: { icon: RoleIconInfo; size?: number }) {
  useMediaAnimation();
  // one of the server's own emoji is drawn from its picture, which it is found by
  const custom = useExpressions((x) => {
    if (icon.kind !== "custom") return undefined;
    for (const list of Object.values(x.emojis)) {
      const e = list.find((em) => em.id === icon.id);
      if (e) return e;
    }
    return undefined;
  });
  // centred in a row that lines up by text baseline, where a picture would stand on the baseline, above the name
  const style = { width: size, height: size, flex: "none", objectFit: "contain" as const, verticalAlign: "middle", display: "inline-block", alignSelf: "center" };
  if (icon.kind === "image") {
    const url = iconUrl(icon.id, size);
    return url ? <img src={url} alt={icon.name} title={icon.name} style={style} loading="lazy" decoding="async" draggable={false} /> : null;
  }
  if (icon.kind === "custom") return custom ? <img src={emojiUrl(custom.blob_id, custom.animated, size)} alt={icon.name} title={icon.name} style={style} loading="lazy" decoding="async" draggable={false} /> : null;
  return (
    <span role="img" aria-label={icon.name} title={icon.name} style={{ ...style, fontSize: size * 0.9, lineHeight: `${size}px`, textAlign: "center" }}>
      <Twemoji text={icon.text} title="" />
    </span>
  );
}
