/**
 * Conversation key material.
 *
 * A conversation has a root key per epoch; rotating membership bumps the epoch
 * and issues a new root. Per-channel keys are derived from the root so that one
 * grant covers every channel in the conversation.
 */

import { hkdf } from "@noble/hashes/hkdf";
import { sha256 } from "@noble/hashes/sha256";
import { randomBytes, utf8 } from "./bytes";

const KEY_LEN = 32;
const NO_SALT = new Uint8Array(0);

export function newRootKey(): Uint8Array {
  return randomBytes(KEY_LEN);
}

export function channelKey(root: Uint8Array, channelId: string): Uint8Array {
  return hkdf(sha256, root, NO_SALT, utf8.enc(`chan:${channelId}`), KEY_LEN);
}

/**
 * The key a call's audio and video frames are sealed with. Everyone who can
 * read the channel can hear its call; it is derived apart from the message
 * key so that one never stands in for the other.
 */
export function voiceFrameKey(root: Uint8Array, channelId: string): Uint8Array {
  return hkdf(sha256, root, NO_SALT, utf8.enc(`voice:${channelId}`), KEY_LEN);
}

/**
 * A public, one-way fingerprint of a conversation's key, which the epoch's maker
 * gives the server and every member checks the key they were handed against. Sealed
 * keys look alike to the server, so without this one person could hand each member
 * a different key, or junk, and nobody could tell until messages stopped opening.
 */
export function keyCommitment(root: Uint8Array, conv: string): Uint8Array {
  return hkdf(sha256, root, NO_SALT, utf8.enc(`commit:${conv}`), KEY_LEN);
}

/** In-memory cache of conversation root keys, keyed by conversation and epoch. */
export class KeyRing {
  private readonly rings = new Map<string, Map<number, Uint8Array>>();

  set(conv: string, epoch: number, root: Uint8Array): void {
    let epochs = this.rings.get(conv);
    if (!epochs) {
      epochs = new Map<number, Uint8Array>();
      this.rings.set(conv, epochs);
    }
    epochs.set(epoch, root);
  }

  get(conv: string, epoch: number): Uint8Array | undefined {
    return this.rings.get(conv)?.get(epoch);
  }

  latest(conv: string): { epoch: number; root: Uint8Array } | undefined {
    const epochs = this.rings.get(conv);
    if (!epochs) return undefined;
    let best: { epoch: number; root: Uint8Array } | undefined;
    for (const [epoch, root] of epochs) {
      if (!best || epoch > best.epoch) best = { epoch, root };
    }
    return best;
  }

  has(conv: string): boolean {
    return (this.rings.get(conv)?.size ?? 0) > 0;
  }

  convs(): string[] {
    return [...this.rings.keys()];
  }

  epochs(conv: string): number[] {
    return [...(this.rings.get(conv)?.keys() ?? [])].sort((a, b) => a - b);
  }

  clear(): void {
    this.rings.clear();
  }
}
