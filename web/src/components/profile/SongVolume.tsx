import { Volume1, Volume2, VolumeX } from "lucide-react";
import type { CSSProperties } from "react";
import { Tooltip } from "@/components/common/Tooltip";
import { t } from "@/i18n";
import { setPreviewVolume, usePreview, volumeAdjustable } from "./previews";
import s from "./SongVolume.module.css";

/**
 * How loud song previews play, on profiles and music cards alike: the speaker
 * mutes them and brings them back, and the slider sets the volume for every
 * preview at once and the one playing now. A browser that does not let a page
 * set its volume (an iPhone's) keeps the speaker alone.
 */
export function SongVolume({ className }: { className?: string | undefined }) {
  const volume = usePreview((p) => p.volume);
  const muted = usePreview((p) => p.muted);
  const silent = muted || volume === 0;
  const Icon = silent ? VolumeX : volume < 0.5 ? Volume1 : Volume2;
  const label = silent ? t("chat.media.unmute") : t("chat.media.mute");
  return (
    <span className={[s.volume, className ?? ""].join(" ")} data-testid="song-volume">
      <Tooltip text={label} side="top">
        <button
          type="button"
          className={s.volumeBtn}
          aria-label={label}
          aria-pressed={silent}
          // muted at nothing, it comes back at half rather than staying silent
          onClick={() => (silent ? setPreviewVolume(volume === 0 ? 0.5 : volume, false) : setPreviewVolume(volume, true))}
        >
          <Icon size={16} aria-hidden="true" />
        </button>
      </Tooltip>
      {volumeAdjustable() ? (
        <input
          className={s.volumeSlider}
          type="range"
          min={0}
          max={1}
          step={0.01}
          value={muted ? 0 : volume}
          style={{ "--fill": `${Math.round((muted ? 0 : volume) * 100)}%` } as CSSProperties}
          onChange={(e) => setPreviewVolume(Number(e.target.value), false)}
          aria-label={t("chat.audioPlayer.volume")}
          aria-valuetext={`${Math.round((muted ? 0 : volume) * 100)}%`}
          data-testid="song-volume-slider"
        />
      ) : null}
    </span>
  );
}
