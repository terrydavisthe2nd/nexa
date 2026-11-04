/**
 * What a failure says to a person.
 *
 * `ApiError.message` is the server's own sentence whenever the server sent one,
 * and those are the most specific words anybody has — "You cannot ban someone
 * above you" beats any generic line this file could write, so they pass
 * through. The problem is everything else: a 500 with no envelope, a proxy that
 * answered HTML, a socket that closed. There `errorFrom` has only a status to
 * go on, and a call site that ends in `err.message` puts "Request failed with
 * status 500." in front of the reader at exactly the moment they understand
 * least.
 *
 * `describeError` is the one place that decides. Codes the client invents for
 * itself become words we wrote; a browser exception becomes the thing that
 * actually went wrong — a declined permission, a camera already in use, rather
 * than `NotAllowedError`; and anything with no sentence behind it gets the
 * caller's own line about what it was trying to do, which is always more use
 * than a status number.
 */

import { ApiError } from "@/api/client";
import { WeakKdfError } from "@/crypto/kdf";
import { t } from "@/i18n";
import { bannedSentence, mutedSentence } from "@/lib/moderation";
import { activeBan, activeMute, useNexa } from "@/stores/nexa";
import { ReadableError } from "./readableError";

/**
 * The codes the client makes up when the request never reached a server that
 * could explain itself. Looked up per call so it follows the language.
 */
function codes(): Record<string, string> {
  return {
    network: t("offline.couldNotReachTheServer"),
    timeout: t("errors.theServerTookTooLong"),
    aborted: t("errors.thatWasCancelledBeforeItFinished"),
    bad_response: t("errors.theServerSAnswerMadeNo"),
    rate_limited: t("errors.youAreDoingThatTooQuickly"),
    too_many_uploads: t("errors.tooManyUploads"),
    upload_quota: t("errors.uploadQuota"),
    no_space: t("errors.noSpace"),
    storage_budget: t("errors.storageBudget"),
    no_fetch: t("modals.confirm.somethingWentWrong"),
    no_xhr: t("modals.confirm.somethingWentWrong"),
  };
}

/** Browser exceptions, which name the fault in `name` and leave the message to the vendor. */
function exceptions(): Record<string, string> {
  return {
    NotAllowedError: t("errors.yourBrowserBlockedThat"),
    NotFoundError: t("errors.thereIsNoDeviceLikeThat"),
    NotReadableError: t("errors.somethingElseIsUsingThatDevice"),
    OverconstrainedError: t("errors.noDeviceHereCanDoThat"),
    SecurityError: t("errors.yourBrowserRefusedThatOnThis"),
    QuotaExceededError: t("errors.thereIsNoRoomLeftOnThis"),
  };
}

/**
 * One sentence for any failure, ready to show.
 *
 * `fallback` is what the caller was doing — "Could not create the channel" —
 * and is used for anything that carries no sentence of its own, because it is
 * always more use than the status code underneath it.
 */
export function describeError(err: unknown, fallback?: string): string {
  // A server that asked for argon2id parameters weak enough to give the
  // password back. Nothing was derived and nothing was sent, and the sentence
  // is already written for a reader — it must not be swallowed by a fallback
  // that blames the password.
  if (err instanceof WeakKdfError) return err.message;
  // written for the reader already: a passcode rule, a save that did not land
  if (err instanceof ReadableError) return err.message;
  if (err instanceof ApiError) {
    const mine = codes()[err.code];
    if (mine) return mine;
    // a mute or ban answers with a code the app can say better than the server
    if (err.code === "account_muted") return mutedSentence(activeMute(useNexa.getState().state));
    if (err.code === "account_banned") return bannedSentence(activeBan(useNexa.getState().state));
    // A code of the shape `http_<status>` is the one errorFrom writes when the
    // response carried no error envelope: there is no server sentence behind
    // it, only the status line, so the caller's own words win.
    if (!err.code.startsWith("http_") && err.message) return err.message;
    return fallback ?? err.message;
  }
  if (typeof DOMException !== "undefined" && err instanceof DOMException) {
    return exceptions()[err.name] ?? fallback ?? t("modals.confirm.somethingWentWrong");
  }
  // Anything else is one of ours or one of the platform's, and its message is
  // written for a developer: a WebCrypto OperationError, an IndexedDB
  // InvalidStateError. It goes to the console, not to the reader.
  return fallback ?? t("modals.confirm.somethingWentWrong");
}

/**
 * Why a direct message or a group could not be opened, or someone added to
 * one. A refusal is the other person's choice of who may message them or put
 * them in a group, or a block, and is said in the reader's language with what
 * to do about it; anything else goes to describeError.
 */
export function dmOpenError(err: unknown, fallback: string): string {
  if (err instanceof ApiError) {
    const refusals: Record<string, string> = {
      dm_friends_servers: t("errors.dmFriendsServers"),
      dm_friends_only: t("errors.dmFriendsOnly"),
      dm_closed: t("errors.dmClosed"),
      blocked: t("errors.dmBlocked"),
      link_only: t("errors.linkOnly"),
    };
    const said = refusals[err.code];
    if (said) return said;
  }
  return describeError(err, fallback);
}
