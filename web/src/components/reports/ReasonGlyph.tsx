import { reasonIcon, reasonTone } from "@/lib/reportReasons";
import s from "./Reports.module.css";

/** A reason's icon in a small tile tinted by how urgent it is. */
export function ReasonGlyph({ reason, size = "md" }: { reason: string; size?: "sm" | "md" }) {
  const Icon = reasonIcon(reason);
  return (
    <span className={s.glyph} data-tone={reasonTone(reason)} data-size={size} aria-hidden="true">
      <Icon size={size === "sm" ? 15 : 18} />
    </span>
  );
}
