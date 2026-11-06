import { api } from "./client";
import type { KeyEpoch, KeyFlags, KeyGrant, KeyNeed, Snowflake } from "./types";

export interface GrantUpload {
  conv_id: Snowflake;
  epoch: number;
  user_id: Snowflake;
  wrapped_key: string;
}

export const keys = {
  grants(): Promise<KeyGrant[]> {
    return api.request<KeyGrant[]>("GET", "/keys/grants");
  },
  needs(): Promise<KeyNeed[]> {
    return api.request<KeyNeed[]>("GET", "/keys/needs");
  },
  putGrants(list: GrantUpload[]): Promise<{ inserted: number }> {
    return api.request("POST", "/keys/grants", list);
  },
  createEpoch(conv_id: Snowflake, reason?: string, commitment?: string): Promise<{ conv_id: Snowflake; epoch: number; user_ids: Snowflake[] }> {
    return api.request("POST", "/keys/epochs", { conv_id, reason: reason ?? "", ...(commitment ? { commitment } : {}) });
  },
  epochs(conv_id: Snowflake): Promise<{ epochs: KeyEpoch[]; flags: KeyFlags; entitled: Snowflake[] }> {
    return api.request("GET", "/keys/epochs", undefined, { query: { conv_id } });
  },
  /** Asks the members holding a conversation's keys, who are online, to hand them over again. */
  request(conv_id: Snowflake, rejected?: Pick<KeyGrant, "epoch" | "wrapped_key">): Promise<void> {
    return api.request("POST", "/keys/request", { conv_id, ...(rejected ? { bad_epoch: rejected.epoch, bad_wrapped_key: rejected.wrapped_key } : {}) });
  },
};
