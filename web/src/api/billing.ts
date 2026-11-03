import { api } from "./client";
import type { Billing, GiftLinkView, Snowflake } from "./types";

/** Subscriptions (api/billing.go): buying goes to Stripe Checkout, managing to Stripe's portal. */
export const billing = {
  get(): Promise<Billing> {
    return api.request<Billing>("GET", "/billing");
  },
  /** The address of a Checkout page for the plan. */
  checkout(plan: "supporter" | "pro", period: "month" | "year"): Promise<{ url: string }> {
    return api.request("POST", "/billing/checkout", { plan, period });
  },
  /** The address of the portal, where a plan is changed or cancelled. */
  portal(): Promise<{ url: string }> {
    return api.request("POST", "/billing/portal");
  },
  /** The address of a Checkout page for a month or a year of a plan, paid once, for someone else. */
  gift(to: Snowflake, plan: "supporter" | "pro", period: "month" | "year"): Promise<{ url: string }> {
    return api.request("POST", "/billing/gift", { to, plan, period });
  },
  /** The address of a Checkout page for a gift link: once it is paid, whoever opens the link first gets the plan. */
  giftLink(plan: "supporter" | "pro", period: "month" | "year"): Promise<{ url: string }> {
    return api.request("POST", "/billing/gift", { link: true, plan, period });
  },
  /** The address of a Checkout page for one payment of an amount in US cents (pay what you want), with the plan time it buys. */
  payWhatYouWant(amount: number): Promise<{ url: string; pro_months: number; supporter_months: number }> {
    return api.request("POST", "/billing/pay-what-you-want", { amount });
  },
  /** A gift link as someone who opened it sees it. */
  giftLinkInfo(code: string): Promise<GiftLinkView> {
    return api.request<GiftLinkView>("GET", `/gifts/${encodeURIComponent(code)}`);
  },
  /** Takes the plan a gift link holds, if nobody has yet. */
  claimGift(code: string): Promise<GiftLinkView> {
    return api.request<GiftLinkView>("POST", `/gifts/${encodeURIComponent(code)}/claim`);
  },
  boost(serverId: Snowflake): Promise<void> {
    return api.request<void>("POST", "/billing/boosts", { server_id: serverId });
  },
  unboost(boostId: Snowflake): Promise<void> {
    return api.request<void>("DELETE", `/billing/boosts/${boostId}`);
  },
};
