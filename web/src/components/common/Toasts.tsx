import { useLayoutEffect, useRef } from "react";
import { createPortal } from "react-dom";
import { leaveCopy } from "@/lib/leaveCopy";
import { usePortalRoot } from "@/hooks/usePortalRoot";
import { useUI } from "@/stores/ui";
import s from "./Toasts.module.css";

import { t } from "@/i18n";
export function Toasts() {
  const toasts = useUI((st) => st.toasts);
  const dismiss = useUI((st) => st.dismissToast);
  const hold = useUI((st) => st.holdToasts);
  const root = usePortalRoot();
  // The stack is always here, empty or not. A screen reader only announces
  // changes to a live region that was already in the accessibility tree, and
  // this one used to be created at the same instant as its first toast — so
  // every failed send, failed upload and slow-mode rejection in the app was
  // announced to nobody. It is `pointer-events: none` and has no box of its
  // own, so an empty one costs nothing on screen.
  return createPortal(
    <div className={s.stack} role="status" aria-live="polite" aria-relevant="additions text">
      {toasts.map((x) => (
        <Toast key={x.id} className={[s.toast, s[x.kind]].join(" ")} onClick={() => dismiss(x.id)} onHold={hold}>
          {/* Severity was carried by a 4px border colour and nothing else. */}
          <span className="visually-hidden">{x.kind === "error" ? t("toasts.error") : x.kind === "success" ? t("toasts.done") : x.kind === "warning" ? t("toasts.warning") : t("toasts.note")}{": "}</span>
          {/* An error is worth interrupting for; the rest wait for a pause.
              It goes on a span inside, not on the button, because `role="alert"`
              on the button would replace its own role and the toast would stop
              being something you can press to dismiss. */}
          {x.kind === "error" ? <span role="alert">{x.text}</span> : x.text}
          <span className="visually-hidden"> {t("toasts.dismiss")}</span>
        </Toast>
      ))}
    </div>,
    root,
  );
}

/**
 * One toast. Going, it rises a little and fades as the space it took closes,
 * so the ones under it slide up instead of jumping (lib/leaveCopy).
 */
function Toast({ className, onClick, onHold, children }: { className: string; onClick: () => void; onHold: (on: boolean) => void; children: React.ReactNode }) {
  const ref = useRef<HTMLButtonElement>(null);
  useLayoutEffect(() => {
    const el = ref.current;
    return () => {
      if (!el) return;
      const h = `${el.offsetHeight}px`;
      leaveCopy(el, {
        ms: 200,
        frames: [
          { boxSizing: "border-box", height: h, marginBottom: "0px", opacity: 1, transform: "none" },
          { boxSizing: "border-box", height: "0px", paddingTop: "0px", paddingBottom: "0px", marginBottom: "-8px", opacity: 0, transform: "translateY(-6px) scale(0.98)" },
        ],
      });
    };
  }, []);
  return (
    <button ref={ref} className={className} onClick={onClick} onMouseEnter={() => onHold(true)} onMouseLeave={() => onHold(false)} onFocus={() => onHold(true)} onBlur={() => onHold(false)}>
      {children}
    </button>
  );
}
