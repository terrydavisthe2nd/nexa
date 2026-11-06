import { useEffect, useRef, useState, type ReactNode } from "react";
import { motionOff } from "@/lib/motion";
import s from "./Unfold.module.css";

/**
 * What an expander brings back, unfolding from nothing when it is pressed. It
 * opens already in place when first drawn, and nothing inside it is kept once
 * it has folded away.
 */
export function Unfold({ open, children }: { open: boolean; children: ReactNode }) {
  const [held, setHeld] = useState(open);
  const wasOpen = useRef(open);
  // decided while rendering: an effect would show a frame unfolded first
  const pressed = wasOpen.current !== open;
  if (open && !held) setHeld(true);
  useEffect(() => {
    wasOpen.current = open;
    if (!open && motionOff()) setHeld(false);
  }, [open]);
  if (!open && !held) return null;
  return (
    <div className={s.unfold} data-open={open} data-moved={pressed || !open || undefined} onTransitionEnd={(e) => { if (e.target === e.currentTarget && !open) setHeld(false); }}>
      <div className={s.inner}>{children}</div>
    </div>
  );
}
