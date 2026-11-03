import { Award } from "lucide-react";
import { DynamicIcon, iconNames, type IconName } from "lucide-react/dynamic";

const known = new Set<string>(iconNames);

/**
 * An event badge's Lucide icon by name, fetched the first time one shows
 * (this module, with the list of every name, loads only then too). A name
 * this build does not know draws a plain award.
 */
export default function EventIcon({ name, size = 12 }: { name: string | undefined; size?: number }) {
  if (!name || !known.has(name)) return <Award size={size} aria-hidden="true" />;
  return <DynamicIcon name={name as IconName} size={size} aria-hidden="true" fallback={() => <Award size={size} aria-hidden="true" />} />;
}
