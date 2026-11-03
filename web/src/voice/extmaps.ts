/**
 * The RTP header extension ids a connection has given each mid. WebRTC
 * (Chromium's, and the library the Android app uses) refuses a description
 * that gives an id a mid has had another meaning for. Cloudflare does exactly
 * that when it hands a stopped mid out again for a new pull: someone who
 * stopped watching a share and watched again got an offer numbering the
 * extensions afresh, it was refused, and the whole call was made again from
 * nothing, everyone on "Reconnecting" with their voices gone for a moment.
 *
 * Such a line is left out of the description instead. Cloudflare already
 * answers every close with no extension lines at all, so the call goes on
 * without them either way.
 */
export class ExtmapHistory {
  private byMid = new Map<string, Map<string, string>>();

  /** Takes note of what a description the connection has taken says. */
  note(sdp: string | null | undefined): void {
    for (const { mid, lines } of sections(sdp ?? "")) {
      if (mid === null) continue;
      let ids = this.byMid.get(mid);
      for (const line of lines) {
        const ext = extmap(line);
        if (!ext) continue;
        if (!ids) this.byMid.set(mid, (ids = new Map()));
        ids.set(ext.id, ext.uri);
      }
    }
  }

  /** The description without the extension lines that would give an id a mid already used another meaning. */
  fit<D extends { type?: RTCSdpType | string; sdp?: string | null }>(desc: D): D {
    if (!desc.sdp || this.byMid.size === 0) return desc;
    let dropped = false;
    const out = sections(desc.sdp).map(({ mid, lines }) => {
      const ids = mid === null ? undefined : this.byMid.get(mid);
      if (!ids) return lines;
      return lines.filter((line) => {
        const ext = extmap(line);
        const clash = !!ext && ids.has(ext.id) && ids.get(ext.id) !== ext.uri;
        if (clash) dropped = true;
        return !clash;
      });
    });
    return dropped ? { ...desc, sdp: out.flat().join("\r\n") } : desc;
  }
}

/** The session part, then each media section, as lines, with each section's mid. */
function sections(sdp: string): { mid: string | null; lines: string[] }[] {
  const out: { mid: string | null; lines: string[] }[] = [{ mid: null, lines: [] }];
  for (const line of sdp.split("\r\n")) {
    if (line.startsWith("m=")) out.push({ mid: null, lines: [] });
    const cur = out[out.length - 1]!;
    cur.lines.push(line);
    if (out.length > 1 && line.startsWith("a=mid:")) cur.mid = line.slice(6).trim();
  }
  return out;
}

function extmap(line: string): { id: string; uri: string } | null {
  const m = /^a=extmap:(\d+)(?:\/\S+)?\s+(\S+)/.exec(line);
  return m ? { id: m[1]!, uri: m[2]! } : null;
}
