/**
 * How this device's voice is sent. Mono is what calls have always carried.
 * Dual Mono and True Stereo go out as two-channel Opus at a higher bitrate
 * (opus.ts): Dual Mono is one voice in both ears, True Stereo the microphone's
 * own left and right.
 *
 * Chromium's echo cancellation, noise suppression and automatic gain each turn
 * a microphone into one channel in both ears (measured on Chromium 153 with a
 * different tone in each channel), so True Stereo opens it without any of them.
 * Only RNNoise can still run, on each channel (denoise.ts). Dual Mono needs one
 * channel anyway and keeps them all.
 */
export const VOICE_CHANNELS = ["mono", "dual", "stereo"] as const;
export type VoiceChannels = (typeof VOICE_CHANNELS)[number];

export function isVoiceChannels(x: unknown): x is VoiceChannels {
  return x === "mono" || x === "dual" || x === "stereo";
}

/** Whether the encoder gets two channels. */
export function sendsTwoChannels(mode: VoiceChannels): boolean {
  return mode !== "mono";
}

/** Whether nothing may mix the microphone's channels together before it is sent. */
export function keepsChannelsApart(mode: VoiceChannels): boolean {
  return mode === "stereo";
}
