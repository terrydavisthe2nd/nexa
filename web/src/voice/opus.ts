/**
 * How a call's voices are encoded: Opus with DTX, at a higher bitrate than
 * the browser picks on its own.
 *
 * DTX: a voice that voice activity, push to talk or mute has silenced
 * (voiceSend.ts) goes on sending, but only a packet every 400 ms, so a pause
 * costs about what not sending at all did, and the timestamps stay in step.
 *
 * The bitrate: browsers encode a voice at 32 kbps unless told otherwise, which
 * is thin next to what people are used to hearing from a call. 48 kbps
 * sounds full, and with DTX a call still sends less than the SFU's meter
 * counts for it (64 kbps a voice, internal/realtime/meter.go).
 *
 * An encoder takes both from the other side's description, which says what
 * that side would like to receive. So they are written into every remote
 * description before it is applied, offers as well as answers: each exchange
 * applies the parameters again, and one without them would switch DTX off.
 */

/** The bitrate a voice is sent at, in bits per second. */
export const VOICE_BITRATE = 48_000;

/**
 * The bitrate a screen share's sound is sent at: stereo, for the music and
 * games people share, which at a voice's 48 kbps in mono came out thin and
 * narrow. 128 kbps is where stereo Opus is transparent for most listening;
 * the SFU meter charges it at this rate (internal/realtime StreamAudioBps).
 */
export const STREAM_AUDIO_BITRATE = 128_000;

/**
 * The bitrate a voice is sent at in Dual Mono and True Stereo (channels.ts). The
 * same as a share's sound, and what the SFU meter charges for it.
 */
export const VOICE_STEREO_BITRATE = 128_000;

const VOICE: Record<string, string> = { usedtx: "1", maxaveragebitrate: String(VOICE_BITRATE) };
/** A voice in two channels. DTX stays: a shut microphone still sends next to nothing. */
const VOICE_STEREO: Record<string, string> = { usedtx: "1", maxaveragebitrate: String(VOICE_STEREO_BITRATE), stereo: "1", "sprop-stereo": "1" };
/** What a side says to play a section in stereo. Sound sent in one channel plays in both ears as it always did. */
const LISTEN: Record<string, string> = { stereo: "1" };
/** Plays a section in one channel, however many it is sent in: what holds Use Stereo Voice and Hear Everything in Mono. */
const LISTEN_MONO: Record<string, string> = { stereo: "0" };
/** A share's sound: in stereo, and without DTX, which would gate the quiet passages of music. */
const STREAM: Record<string, string> = { usedtx: "0", maxaveragebitrate: String(STREAM_AUDIO_BITRATE), stereo: "1", "sprop-stereo": "1" };

/** An fmtp line's parameters with ours set, in the order they came. */
function withOurs(params: string, wanted: Record<string, string>): string {
  const out: string[] = [];
  const seen = new Set<string>();
  for (const part of params.split(";")) {
    const p = part.trim();
    if (!p) continue;
    const key = p.split("=")[0]!.trim().toLowerCase();
    if (key in wanted) {
      if (seen.has(key)) continue;
      seen.add(key);
      out.push(`${key}=${wanted[key]}`);
    } else out.push(p);
  }
  for (const [key, value] of Object.entries(wanted)) if (!seen.has(key)) out.push(`${key}=${value}`);
  return out.join(";");
}

/** One media section's lines with its Opus payload types tuned, `wanted` deciding how. */
function tuneSection(lines: string[], wanted: Record<string, string>): string[] {
  const out: string[] = [];
  // the Opus payload types it maps, and those it gave an fmtp line
  const opus = new Map<string, number>();
  const tuned = new Set<string>();
  for (const line of lines) {
    const map = /^a=rtpmap:(\d+) opus\/48000/i.exec(line);
    if (map) {
      out.push(line);
      opus.set(map[1]!, out.length - 1);
      continue;
    }
    const fmtp = /^a=fmtp:(\d+) (.*)$/.exec(line);
    if (fmtp && opus.has(fmtp[1]!)) {
      tuned.add(fmtp[1]!);
      out.push(`a=fmtp:${fmtp[1]} ${withOurs(fmtp[2]!, wanted)}`);
      continue;
    }
    out.push(line);
  }
  // an Opus mapping with no fmtp line of its own gets one, just after it
  const missing = [...opus].filter(([pt]) => !tuned.has(pt)).sort((a, b) => b[1] - a[1]);
  for (const [pt, at] of missing) out.splice(at + 1, 0, `a=fmtp:${pt} ${withOurs("", wanted)}`);
  return out;
}

/** A description with each Opus section tuned as `wanted` says from its mid; null leaves one as it is. */
function tuneSections(sdp: string, wanted: (mid: string | undefined) => Record<string, string> | null): string {
  if (!/opus\/48000/i.test(sdp)) return sdp;
  const eol = sdp.includes("\r\n") ? "\r\n" : "\n";
  const lines = sdp.split(eol);
  // a trailing empty line stays last
  const trailing = lines.length > 0 && lines[lines.length - 1] === "" ? lines.pop() : undefined;
  const out: string[] = [];
  let section: string[] = [];
  const flush = () => {
    const mid = section.find((l) => l.startsWith("a=mid:"))?.slice(6).trim();
    const tune = section[0]?.startsWith("m=") ? wanted(mid) : null;
    out.push(...(tune ? tuneSection(section, tune) : section));
    section = [];
  };
  for (const line of lines) {
    if (line.startsWith("m=")) flush();
    section.push(line);
  }
  flush();
  if (trailing !== undefined) out.push(trailing);
  return out.join(eol);
}

/**
 * A description with every Opus payload type of every audio section asking
 * for DTX and the voice bitrate, except the sections whose mid is in
 * `streamMids` (a screen share's sound), which ask for stereo at the stream
 * bitrate instead. With `voices` false the other sections are left as they
 * are. `stereoVoice` sends this device's voice in two channels (channels.ts).
 */
export function tuneOpus(sdp: string, streamMids: ReadonlySet<string> = new Set(), voices = true, stereoVoice = false): string {
  return tuneSections(sdp, (mid) => (mid !== undefined && streamMids.has(mid) ? STREAM : voices ? (stereoVoice ? VOICE_STEREO : VOICE) : null));
}

/** A description to apply as the remote one, tuned. Rollbacks and empty ones pass as they are. */
export function tunedRemote<D extends { type?: RTCSdpType | string; sdp?: string | null }>(desc: D, streamMids?: ReadonlySet<string>, stereoVoice = false): D {
  if (!desc || !desc.sdp || desc.type === "rollback") return desc;
  return { ...desc, sdp: tuneOpus(desc.sdp, streamMids, true, stereoVoice) };
}

/**
 * This device's own description. A decoder plays stereo only when the local
 * side asks for it, so a share's sound asks here too, and so does every voice
 * in `listen` (a set of mids, or "all" for each audio section), whether its
 * sender uses two channels or not: nobody can say in advance who will. Those
 * in `mono` are decoded in one channel whatever their sender sends, which no
 * sender can get round. Anything else passes as it is.
 */
export function tunedLocal<D extends { type?: RTCSdpType | string; sdp?: string | null }>(desc: D, streamMids: ReadonlySet<string>, listen: ReadonlySet<string> | "all" = new Set(), mono: ReadonlySet<string> | "all" = new Set()): D {
  if (!desc || !desc.sdp || desc.type === "rollback") return desc;
  const among = (set: ReadonlySet<string> | "all", mid: string | undefined) => set === "all" || (mid !== undefined && set.has(mid));
  return { ...desc, sdp: tuneSections(desc.sdp, (mid) => (among(mono, mid) ? LISTEN_MONO : mid !== undefined && streamMids.has(mid) ? STREAM : among(listen, mid) ? LISTEN : null)) };
}
