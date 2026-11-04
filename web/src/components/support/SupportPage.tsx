import { personText } from "@/lib/personText";
import { PersonName } from "@/components/common/PersonName";
import { useMediaAnimation } from "@/lib/mediaAnimation";
import { useEffect, useMemo, useState, type ReactNode } from "react";
import { useLocation, useNavigate } from "react-router-dom";
import { Check, Copy, Gift, HandCoins, Heart, Link2, RotateCcw, Rocket, X } from "lucide-react";
import { ApiError, iconUrl } from "@/api";
import { billing as billingApi } from "@/api/billing";
import type { Plan, PlanLimits } from "@/api/types";
import { Avatar } from "@/components/common/Avatar";
import { Button } from "@/components/common/Button";
import { SearchBox } from "@/components/common/SearchBox";
import { Select } from "@/components/common/Select";
import { PlanBadge } from "@/components/common/OwnerBadge";
import { PageHeaderButtons } from "@/components/shell/PageHeader";
import { shareOrigin } from "@/lib/appLinks";
import { isBot } from "@/lib/botCopies";
import { copyText } from "@/lib/clipboard";
import { centsOf, limitsOf, PAID_PLANS, payWhatYouWant, plansInfo, PWYW_MAX, PWYW_MIN } from "@/lib/plans";
import { CONTACT_EMAIL, OWNER_USERNAME } from "@/lib/legal";
import { formatBytes, formatShortDate } from "@/lib/time";
import { fold } from "@/search/text";
import { useAuth } from "@/stores/auth";
import { selectDMs, useChannels } from "@/stores/channels";
import { useRelationships } from "@/stores/relationships";
import { displayName, useUsers } from "@/stores/users";
import { useBilling } from "@/stores/billing";
import { useServers } from "@/stores/servers";
import { useUI } from "@/stores/ui";
import { useIsMobile } from "@/hooks/useIsMobile";
import { t } from "@/i18n";
import s from "./Support.module.css";

type Paid = (typeof PAID_PLANS)[number];
type Period = "month" | "year";

/** What each plan costs when Stripe has not said (US dollars, in cents). */
const LIST_PRICES: Record<Paid, Record<Period, number>> = { supporter: { month: 500, year: 4800 }, pro: { month: 1000, year: 9600 } };

const PLANS: Plan[] = ["", "supporter", "pro"];

export function planName(plan: Plan): string {
  return plan === "pro" ? t("support.plan.pro") : plan === "supporter" ? t("support.plan.supporter") : t("support.plan.free");
}

function money(cents: number, currency: string): string {
  return new Intl.NumberFormat(undefined, { style: "currency", currency: currency.toUpperCase(), minimumFractionDigits: cents % 100 ? 2 : 0 }).format(cents / 100);
}

/** How far along a gauge a value sits, on a scale of powers so 100 MB and 4 GB both read. */
function gauge(v: number, lo: number, hi: number): number {
  if (hi <= lo) return 1;
  return 0.14 + 0.86 * Math.max(0, Math.min(1, Math.log(v / lo) / Math.log(hi / lo)));
}

interface Row {
  id: string;
  label: string;
  cell: (plan: Plan, l: PlanLimits) => ReactNode;
}

/**
 * Nexa's plans, the account's own and how to get one: a page of the DM list
 * (/channels/@me/support) and Settings, Subscription, both this component.
 * Only what a plan changes is listed; the line under the table says the rest
 * stays free.
 */
export function SupportPanel({ inSettings = false }: { inSettings?: boolean }) {
  const me = useAuth((a) => a.me);
  const plan: Plan = me?.plan ?? "";
  const billing = useBilling((b) => b.billing);
  const load = useBilling((b) => b.load);
  const toast = useUI((u) => u.toast);
  const mobile = useIsMobile();
  const loc = useLocation();
  const [period, setPeriod] = useState<Period>("month");
  const [busy, setBusy] = useState<string | null>(null);
  // on a phone one plan is shown at a time; it starts on the next one up
  const [shown, setShown] = useState<Plan>(plan === "" ? "supporter" : plan);
  const info = plansInfo();
  const enabled = billing?.enabled ?? info.enabled;
  const justPaid = new URLSearchParams(loc.search).get("checkout") === "done";

  useEffect(() => {
    void load().catch(() => undefined);
  }, [load, plan]);
  // Back from paying: the webhook usually lands first, but not always, so
  // look again for a little while until the plan shows.
  useEffect(() => {
    if (!justPaid || plan !== "") return;
    let n = 0;
    const id = window.setInterval(() => {
      n += 1;
      void load().catch(() => undefined);
      if (n >= 10) window.clearInterval(id);
    }, 3000);
    return () => window.clearInterval(id);
  }, [justPaid, plan, load]);

  const price = (p: Paid, per: Period): string => {
    const got = billing?.prices[p]?.[per];
    return got ? money(got.amount, got.currency) : money(LIST_PRICES[p][per], "usd");
  };

  const go = async (key: string, fetchUrl: () => Promise<{ url: string }>) => {
    setBusy(key);
    try {
      const { url } = await fetchUrl();
      // the desktop app opens it in the browser, the web goes there and comes back
      window.location.assign(url);
    } catch (err) {
      if (err instanceof ApiError && err.code === "already_subscribed") {
        await go("manage", () => billingApi.portal());
        return;
      }
      toast(err instanceof ApiError ? err.message : t("support.error"), "error");
    } finally {
      setBusy(null);
    }
  };
  const buy = (p: Paid) => go(p, () => billingApi.checkout(p, period));
  const manage = () => go("manage", () => billingApi.portal());

  const rows: Row[] = useMemo(() => {
    const lo = info.free;
    const hi = info.pro;
    const bar = (v: number, a: number, b: number) => <span className={s.gauge} aria-hidden="true"><i style={{ inlineSize: `${gauge(v, a, b) * 100}%` }} /></span>;
    return [
      { id: "file", label: t("support.row.file"), cell: (_p, l) => <span className={s.value}><b>{formatBytes(l.file_max)}</b>{bar(l.file_max, lo.file_max, hi.file_max)}</span> },
      { id: "storage", label: t("support.row.storage"), cell: (_p, l) => <span className={s.value}><b>{formatBytes(l.month_upload_max)}</b>{bar(l.month_upload_max, lo.month_upload_max, hi.month_upload_max)}</span> },
      {
        id: "share",
        label: t("support.row.share"),
        cell: (p, l) => (
          <span className={s.value}>
            <b>{l.share_quality ? t("support.share.quality") : t("support.share.performance")}</b>
            <small>{p === "" ? t("support.share.performanceHint") : t("support.share.upTo", { n: Math.round(l.share_max_bps / 1e6) })}</small>
          </span>
        ),
      },
      { id: "effects", label: t("support.row.effects"), cell: (_p, l) => (l.name_effects ? <span className={s.value}><b>{t("support.effects.list")}</b></span> : <None />) },
      { id: "badge", label: t("support.row.badge"), cell: (p) => (p === "" ? <None /> : <PlanBadge plan={p} />) },
      {
        id: "boosts",
        label: t("support.row.boosts"),
        cell: (_p, l) => (l.boosts ? <span className={s.value}><b>{l.boosts}</b><small>{t("support.boosts.hint", { size: formatBytes(info.boost_file_max) })}</small></span> : <None />),
      },
    ];
  }, [info]);

  const action = (p: Plan) => {
    if (p === plan) return <span className={s.current}>{t("support.current")}</span>;
    if (p === "") return null;
    if (plan !== "") {
      // a plan already running changes in the portal
      return billing?.manageable ? <Button variant="secondary" size="sm" block onClick={manage} loading={busy === "manage"}>{t("support.switch", { plan: planName(p) })}</Button> : null;
    }
    return (
      <Button size="sm" block className={s.buy} data-plan={p} onClick={() => void buy(p)} loading={busy === p} disabled={!enabled || busy !== null} data-testid={`buy-${p}`}>
        {t("support.get", { plan: planName(p) })}
      </Button>
    );
  };

  const columns = mobile ? [shown] : PLANS;
  const used = billing?.month_used ?? 0;
  const room = (billing?.limits ?? limitsOf(plan)).month_upload_max;
  const freesAt = billing?.month_frees_at ?? 0;

  return (
    <div className={s.panel} data-in-settings={inSettings || undefined}>
      <section className={s.hero} aria-labelledby="support-title">
        <span className={s.heart} aria-hidden="true"><Heart size={26} fill="currentColor" /></span>
        <h1 className={s.title} id="support-title">{t("support.title")}</h1>
        <p className={s.lede}>{t("support.lede")}</p>
        <div className={s.standing} data-testid="support-standing">
          {plan === "" ? (
            <span>{t("support.onFree")}</span>
          ) : (
            <>
              <PlanBadge plan={plan} />
              <span>
                {billing?.period_end
                  ? billing.cancel_at_end
                    ? t("support.endsOn", { date: formatShortDate(billing.period_end) })
                    : t("support.renewsOn", { date: formatShortDate(billing.period_end) })
                  : t("support.thanks")}
              </span>
            </>
          )}
          {billing?.manageable ? (
            <Button variant="secondary" size="sm" onClick={manage} loading={busy === "manage"} data-testid="manage-subscription">
              {t("support.manage")}
            </Button>
          ) : null}
        </div>
        {justPaid && plan === "" ? <p className={s.notice} role="status">{t("support.paidWaiting")}</p> : null}
        {!enabled ? <p className={s.notice}>{t("support.notOpen")}</p> : null}
      </section>

      {plan === "" ? (
        <div className={s.period} role="radiogroup" data-glide="" aria-label={t("support.period")}>
          {(["month", "year"] as const).map((per) => (
            <button key={per} role="radio" aria-checked={period === per} className={s.periodBtn} onClick={() => setPeriod(per)} data-testid={`period-${per}`}>
              {per === "month" ? t("support.monthly") : t("support.yearly")}
              {per === "year" ? <span className={s.save}>{t("support.twoMonthsFree")}</span> : null}
            </button>
          ))}
        </div>
      ) : null}

      {mobile ? (
        <div className={s.picker} role="tablist" aria-label={t("support.plans")}>
          {PLANS.map((p) => (
            <button key={p || "free"} role="tab" aria-selected={shown === p} className={s.pick} data-plan={p || "free"} onClick={() => setShown(p)}>
              {planName(p)}
            </button>
          ))}
        </div>
      ) : null}

      <div className={s.table} role="table" aria-label={t("support.plans")} style={{ ["--cols" as string]: columns.length }} data-testid="plans-table">
        <div className={s.corner} />
        {columns.map((p) => (
          <div key={p || "free"} className={s.head} data-plan={p || "free"} data-mine={p === plan || undefined}>
            <span className={s.planName}>{planName(p)}</span>
            <span className={s.price}>
              {p === "" ? t("support.freePrice") : period === "month" || plan !== "" ? t("support.perMonth", { price: price(p, "month") }) : t("support.perYear", { price: price(p, "year") })}
            </span>
          </div>
        ))}
        {rows.map((r) => (
          <div key={r.id} className={s.row} role="row">
            <div className={s.label} role="rowheader">{r.label}</div>
            {columns.map((p) => (
              <div key={p || "free"} className={s.cell} data-plan={p || "free"} role="cell">
                {r.cell(p, limitsOf(p))}
              </div>
            ))}
          </div>
        ))}
        <div className={s.corner} />
        {columns.map((p) => (
          <div key={p || "free"} className={s.foot} data-plan={p || "free"}>
            {action(p)}
          </div>
        ))}
      </div>
      <p className={s.free}>{t("support.everythingElse")}</p>

      {enabled ? <PayWhatYouWant /> : null}
      <Refunds inSettings={inSettings} />

      <section className={s.mine} aria-label={t("support.yourStorage")}>
        <div className={s.meterHead}>
          <h2 className={s.h2}>{t("support.yourStorage")}</h2>
          <span className={s.meterText}>{t("support.storageOf", { used: formatBytes(used), room: formatBytes(room) })}</span>
        </div>
        <span className={s.meter} role="meter" aria-valuemin={0} aria-valuemax={room} aria-valuenow={used} aria-label={t("support.yourStorage")}>
          <i style={{ inlineSize: `${Math.min(100, (used / Math.max(1, room)) * 100)}%` }} data-full={used >= room || undefined} />
        </span>
        <p className={s.hint}>{used >= room && freesAt ? t("support.storageFreesAt", { date: formatShortDate(freesAt) }) : t("support.storageHint")}</p>
      </section>

      {plan === "pro" ? <Boosts /> : null}
      {billing?.gifts?.length ? <GiftsGot /> : null}
      {enabled ? <GiveAPlan /> : null}
      {billing?.gift_links?.length ? <GiftLinksMade /> : null}
    </div>
  );
}

function None() {
  return <span className={s.none} aria-label={t("support.notIncluded")}>—</span>;
}

/** A Pro account's two boosts: where they are, and putting one on a server. */
function Boosts() {
  useMediaAnimation();
  const billing = useBilling((b) => b.billing);
  const load = useBilling((b) => b.load);
  const servers = useServers((st) => st.byId);
  const order = useServers((st) => st.order);
  const toast = useUI((u) => u.toast);
  const [pick, setPick] = useState("");
  const boosts = billing?.boosts ?? [];
  const max = billing?.limits.boosts ?? 2;
  const act = async (f: () => Promise<void>) => {
    try {
      await f();
      await load();
    } catch (err) {
      toast(err instanceof ApiError ? err.message : t("support.error"), "error");
    }
  };
  return (
    <section className={s.mine} aria-labelledby="support-boosts">
      <div className={s.meterHead}>
        <h2 className={s.h2} id="support-boosts">{t("support.boosts.title")}</h2>
        <span className={s.meterText}>{t("support.boosts.used", { used: boosts.length, max })}</span>
      </div>
      <p className={s.hint}>{t("support.boosts.about", { size: formatBytes(plansInfo().boost_file_max) })}</p>
      <ul className={s.boosts}>
        {boosts.map((b) => {
          const icon = iconUrl(b.server_icon_id, 32);
          return (
            <li key={b.id} className={s.boost}>
              <span className={s.boostIcon} aria-hidden="true">{icon ? <img src={icon} alt="" /> : <Rocket size={16} />}</span>
              <span className={s.boostName}>{b.server_name}</span>
              <button className={s.unboost} aria-label={t("support.boosts.remove", { server: b.server_name })} title={t("support.boosts.remove", { server: b.server_name })} onClick={() => void act(() => billingApi.unboost(b.id))}>
                <X size={16} />
              </button>
            </li>
          );
        })}
      </ul>
      {boosts.length < max ? (
        <div className={s.boostAdd}>
          <Select value={pick} onChange={(e) => setPick(e.target.value)} aria-label={t("support.boosts.pick")}>
            <option value="">{t("support.boosts.pick")}</option>
            {order.filter((id) => servers[id]).map((id) => (
              <option key={id} value={id}>{servers[id]!.server.name}</option>
            ))}
          </Select>
          <Button size="sm" disabled={!pick} onClick={() => void act(() => billingApi.boost(pick)).then(() => setPick(""))}>
            {t("support.boosts.add")}
          </Button>
        </div>
      ) : null}
    </section>
  );
}

/** Plans other people gave this account, while they last. */
function GiftsGot() {
  const gifts = useBilling((b) => b.billing?.gifts) ?? [];
  const meId = useAuth((a) => a.me?.id);
  // time this account paid for itself (pay what you want) is kept as a gift from itself
  const own = gifts.some((g) => g.from_id === meId);
  return (
    <section className={s.mine} aria-labelledby="support-gifts-got">
      <h2 className={s.h2} id="support-gifts-got">{own ? t("support.gift.timeTitle") : t("support.gift.got")}</h2>
      <ul className={s.boosts}>
        {gifts.map((g) => (
          <li key={`${g.from_id}-${g.starts_at}`} className={s.boost} data-plan={g.plan}>
            <span className={s.giftIcon} aria-hidden="true">{g.from_id === meId ? <HandCoins size={16} /> : <Gift size={16} />}</span>
            <span className={s.boostName}>{g.from_id === meId ? t("support.gift.yours", { plan: planName(g.plan) }) : personText("support.gift.from", g.from_id, g.from_name, { plan: planName(g.plan) })}</span>
            <span className={s.meterText}>{t("support.gift.until", { date: formatShortDate(g.ends_at) })}</span>
          </li>
        ))}
      </ul>
    </section>
  );
}

/** The address of a gift link, as it is shared. */
export function giftLinkUrl(code: string): string {
  return `${shareOrigin()}/gift/${code}`;
}

/** How long a gift lasts, as its title says it ("A Month of Pro"). */
export function giftTitle(plan: Plan, days: number): string {
  return t(days >= 365 ? "support.giftLink.yearOf" : "support.giftLink.monthOf", { plan: planName(plan) });
}

/** The gift links this account bought: copy one to send it, and who has claimed which. */
function GiftLinksMade() {
  const links = useBilling((b) => b.billing?.gift_links) ?? [];
  return (
    <section className={s.mine} aria-labelledby="support-gift-links" data-testid="gift-links">
      <h2 className={s.h2} id="support-gift-links">{t("support.giftLink.yours")}</h2>
      <p className={s.hint}>{t("support.giftLink.yoursHint")}</p>
      <ul className={s.boosts}>
        {links.map((l) => (
          <li key={l.code} className={s.boost} data-plan={l.plan}>
            <span className={s.giftIcon} aria-hidden="true">{l.claimed ? <Check size={16} /> : <Link2 size={16} />}</span>
            <span className={s.names}>
              <span className={s.boostName}>{giftTitle(l.plan, l.days)}</span>
              <span className={s.giftState}>{l.claimed ? personText("support.giftLink.claimedBy", l.claimed_by, l.claimed_name || t("support.giftLink.someone")) : t("support.giftLink.waiting")}</span>
            </span>
            {l.claimed ? null : (
              <Button variant="secondary" size="sm" onClick={() => void copyText(giftLinkUrl(l.code), t("support.giftLink.copied"))} data-testid="gift-link-copy">
                <Copy size={15} aria-hidden="true" /> {t("support.giftLink.copy")}
              </Button>
            )}
          </li>
        ))}
      </ul>
    </section>
  );
}

/**
 * Gift a Plan: a month or a year of Supporter or Pro for a friend or someone
 * this account talks to, or as a link for anyone, paid once on Stripe's page
 * and never renewed. /channels/@me/support?gift=<id> starts with that person
 * picked.
 */
function GiveAPlan() {
  const loc = useLocation();
  const meId = useAuth((a) => a.me?.id);
  const rels = useRelationships((r) => r.byUser);
  const users = useUsers((u) => u.byId);
  const channels = useChannels((c) => c.byId);
  const prices = useBilling((b) => b.billing?.gift_prices);
  const toast = useUI((u) => u.toast);
  const params = new URLSearchParams(loc.search);
  const back = params.get("gift");
  const [to, setTo] = useState<string | null>(back && back !== "done" && back !== "link" ? back : null);
  const [query, setQuery] = useState("");
  const [plan, setPlan] = useState<Paid>("supporter");
  const [period, setPeriod] = useState<Period>("month");
  const [busy, setBusy] = useState(false);
  const [asLink, setAsLink] = useState(false);
  const given = back === "done";

  // friends first, then the people of one-to-one conversations
  const people = useMemo(() => {
    const ids = new Set<string>();
    for (const r of Object.values(rels)) if (r.type === "friend") ids.add(r.user.id);
    for (const c of selectDMs(channels)) if (c.type === "dm") for (const id of c.recipient_ids) ids.add(id);
    if (meId) ids.delete(meId);
    return [...ids].map((id) => users[id] ?? rels[id]?.user).filter((u): u is NonNullable<typeof u> => !!u && !isBot(u));
  }, [rels, channels, users, meId]);
  const q = fold(query.trim());
  const found = (q ? people.filter((u) => fold(displayName(u)).includes(q) || fold(u.username).includes(q)) : people).slice(0, 6);
  const picked = to ? (users[to] ?? rels[to]?.user ?? null) : null;
  const got = prices?.[plan]?.[period];
  const cost = got ? money(got.amount, got.currency) : money(LIST_PRICES[plan][period], "usd");

  const give = async () => {
    if (!to && !asLink) return;
    setBusy(true);
    try {
      const { url } = asLink ? await billingApi.giftLink(plan, period) : await billingApi.gift(to!, plan, period);
      window.location.assign(url);
    } catch (err) {
      toast(err instanceof ApiError ? err.message : t("support.error"), "error");
      setBusy(false);
    }
  };

  return (
    <section className={`${s.mine} ${s.gift}`} aria-labelledby="support-gift" data-testid="gift-plan">
      <div className={s.giftHead}>
        <span className={s.giftIcon} aria-hidden="true"><Gift size={18} /></span>
        <div>
          <h2 className={s.h2} id="support-gift">{t("support.gift.title")}</h2>
          <p className={s.hint}>{t("support.gift.about")}</p>
        </div>
      </div>
      {given ? <p className={s.notice} role="status">{t("support.gift.done")}</p> : null}
      {back === "link" ? <p className={s.notice} role="status">{t("support.giftLink.done")}</p> : null}
      <div className={s.period} role="radiogroup" data-glide="" aria-label={t("support.gift.who")}>
        <button role="radio" aria-checked={!asLink} className={s.periodBtn} onClick={() => setAsLink(false)} data-testid="gift-to-friend">
          {t("support.giftLink.toFriend")}
        </button>
        <button role="radio" aria-checked={asLink} className={s.periodBtn} onClick={() => setAsLink(true)} data-testid="gift-as-link">
          {t("support.giftLink.asLink")}
        </button>
      </div>
      {asLink ? (
        <p className={s.hint}>{t("support.giftLink.about")}</p>
      ) : picked ? (
        <div className={s.giftTo}>
          <Avatar id={picked.id} avatarId={picked.avatar_id} name={displayName(picked)} size={32} />
          <span className={s.names}>
            <PersonName user={picked} className={s.boostName}>{displayName(picked)}</PersonName>
            <span className={s.meterText}>@{picked.username}</span>
          </span>
          <Button variant="ghost" size="sm" onClick={() => setTo(null)}>{t("support.gift.change")}</Button>
        </div>
      ) : (
        <>
          <SearchBox value={query} onChange={setQuery} placeholder={t("support.gift.search")} aria-label={t("support.gift.search")} />
          <div className={s.people} role="radiogroup" aria-label={t("support.gift.who")}>
            {found.length === 0 ? <p className={s.hint}>{people.length ? t("forward.none") : t("support.gift.nobody")}</p> : null}
            {found.map((u) => (
              <button key={u.id} role="radio" aria-checked={false} className={s.person} onClick={() => setTo(u.id)} data-testid={`gift-to-${u.username}`}>
                <Avatar id={u.id} avatarId={u.avatar_id} name={displayName(u)} size={28} />
                <PersonName user={u} className={s.boostName}>{displayName(u)}</PersonName>
              </button>
            ))}
          </div>
        </>
      )}
      <div className={s.giftChoices}>
        <div className={s.period} role="radiogroup" data-glide="" aria-label={t("support.plans")}>
          {PAID_PLANS.map((p) => (
            <button key={p} role="radio" aria-checked={plan === p} className={s.periodBtn} data-plan={p} onClick={() => setPlan(p)}>{planName(p)}</button>
          ))}
        </div>
        <div className={s.period} role="radiogroup" data-glide="" aria-label={t("support.period")}>
          {(["month", "year"] as const).map((per) => (
            <button key={per} role="radio" aria-checked={period === per} className={s.periodBtn} onClick={() => setPeriod(per)}>
              {per === "month" ? t("support.gift.aMonth") : t("support.gift.aYear")}
            </button>
          ))}
        </div>
      </div>
      <Button className={s.buy} data-plan={plan} onClick={() => void give()} disabled={!to && !asLink} loading={busy} data-testid="gift-buy">
        <Gift size={16} aria-hidden="true" /> {t("support.gift.buy", { price: cost })}
      </Button>
      <p className={s.hint}>{t("support.refunds.line")}</p>
    </section>
  );
}

/** The month prices pay what you want divides by, in cents: the one-time gift prices, else the list prices. */
function useMonthPrices(): { supporter: number; pro: number } {
  const prices = useBilling((b) => b.billing?.gift_prices);
  const usd = (p: Paid) => {
    const got = prices?.[p]?.month;
    return got && got.currency.toLowerCase() === "usd" && got.amount > 0 ? got.amount : LIST_PRICES[p].month;
  };
  return { supporter: usd("supporter"), pro: usd("pro") };
}

/** How long an amount buys, in words ("2 months of Pro, then 1 month of Supporter"). */
function timeBought(pro: number, supporter: number): string {
  const p = pro ? t("support.pwyw.monthsPro", { count: pro }) : "";
  const sup = supporter ? t("support.pwyw.monthsSupporter", { count: supporter }) : "";
  return p && sup ? t("support.pwyw.then", { first: p, second: sup }) : p || sup;
}

const QUICK = [300, 500, 1000, 2500, 5000];
/** The most month tiles drawn before the rest are counted. */
const TILES = 24;

/**
 * Pay What You Want: one payment of any amount from a dollar, never renewed.
 * What it buys (payWhatYouWant, the server's own split) is shown as it is
 * typed, month by month, before Stripe's page opens.
 */
function PayWhatYouWant() {
  const loc = useLocation();
  const plan = useAuth((a) => a.me?.plan ?? "");
  const billing = useBilling((b) => b.billing);
  const toast = useUI((u) => u.toast);
  const month = useMonthPrices();
  const [text, setText] = useState("10");
  const [busy, setBusy] = useState(false);
  const cents = centsOf(text);
  const valid = cents !== null && cents >= PWYW_MIN && cents <= PWYW_MAX;
  const { pro, supporter } = payWhatYouWant(valid ? cents : 0, month.supporter, month.pro);
  const months = pro + supporter;
  const subscribed = plan !== "" && !!billing && ["active", "trialing", "past_due"].includes(billing.status);
  const done = new URLSearchParams(loc.search).get("paid") === "done";
  const usd = (c: number) => money(c, "usd");

  const pay = async () => {
    if (!valid) return;
    setBusy(true);
    try {
      const { url } = await billingApi.payWhatYouWant(cents);
      window.location.assign(url);
    } catch (err) {
      toast(err instanceof ApiError ? err.message : t("support.error"), "error");
      setBusy(false);
    }
  };

  const tiles = Array.from({ length: Math.min(months, TILES) }, (_, i) => (i < pro ? "pro" : "supporter"));
  if (months > TILES) tiles.length = TILES - 1;

  let outcome: string;
  if (cents === null || cents < PWYW_MIN) outcome = t("support.pwyw.tooLow", { min: usd(PWYW_MIN) });
  else if (cents > PWYW_MAX) outcome = t("support.pwyw.tooHigh", { max: usd(PWYW_MAX) });
  else if (months === 0) outcome = t("support.pwyw.thanksOnly", { price: usd(month.supporter) });
  else outcome = t("support.pwyw.gets", { time: timeBought(pro, supporter) });

  return (
    <section className={`${s.mine} ${s.pwyw}`} aria-labelledby="support-pwyw" data-testid="pay-what-you-want">
      <div className={s.giftHead}>
        <span className={s.giftIcon} aria-hidden="true"><HandCoins size={18} /></span>
        <div>
          <h2 className={s.h2} id="support-pwyw">{t("support.pwyw.title")}</h2>
          <p className={s.hint}>{t("support.pwyw.about", { min: usd(PWYW_MIN) })}</p>
        </div>
      </div>
      {done ? <p className={s.notice} role="status">{t("support.pwyw.done")}</p> : null}
      <div className={s.amountRow}>
        <label className={s.amount}>
          <span className={s.dollar} aria-hidden="true">$</span>
          <input
            className={s.amountInput}
            inputMode="decimal"
            autoComplete="off"
            value={text}
            onChange={(e) => setText(e.target.value)}
            onKeyDown={(e) => { if (e.key === "Enter") void pay(); }}
            aria-label={t("support.pwyw.amount")}
            aria-invalid={!valid || undefined}
            data-testid="pwyw-amount"
          />
        </label>
        <div className={s.quick} role="group" aria-label={t("support.pwyw.quick")}>
          {QUICK.map((c) => (
            <button key={c} type="button" className={s.quickBtn} aria-pressed={cents === c} onClick={() => setText(String(c / 100))}>{usd(c)}</button>
          ))}
        </div>
      </div>
      <div className={s.receipt} data-plan={pro ? "pro" : supporter ? "supporter" : "free"}>
        {months ? (
          <div className={s.months} aria-hidden="true">
            {tiles.map((p, i) => <i key={i} data-plan={p} />)}
            {months > TILES ? <span className={s.moreMonths}>+{months - tiles.length}</span> : null}
          </div>
        ) : null}
        <p className={s.outcome} role="status" aria-live="polite" data-testid="pwyw-outcome">{outcome}</p>
        <p className={s.hint}>{t("support.pwyw.rule", { pro: usd(month.pro), supporter: usd(month.supporter) })}</p>
        {subscribed && months ? <p className={s.hint}>{t("support.pwyw.alongside", { plan: planName(plan) })}</p> : null}
        <p className={s.hint}>{t("support.pwyw.once")}</p>
      </div>
      <div className={s.payRow}>
        <Button className={s.buy} data-plan={pro ? "pro" : "supporter"} onClick={() => void pay()} disabled={!valid} loading={busy} data-testid="pwyw-pay">
          <HandCoins size={16} aria-hidden="true" /> {valid ? t("support.pwyw.pay", { price: usd(cents) }) : t("support.pwyw.title")}
        </Button>
        <span className={s.hint}>{t("support.refunds.line")}</span>
      </div>
    </section>
  );
}

/** How refunds and cancelling work, said on the page itself rather than only in the Terms. */
function Refunds({ inSettings }: { inSettings: boolean }) {
  const nav = useNavigate();
  return (
    <section className={`${s.mine} ${s.refunds}`} aria-labelledby="support-refunds" data-testid="support-refunds">
      <div className={s.giftHead}>
        <span className={s.refundIcon} aria-hidden="true"><RotateCcw size={18} /></span>
        <div className={s.refundText}>
          <h2 className={s.h2} id="support-refunds">{t("support.refunds.title")}</h2>
          <p className={s.refundBody}>{t("support.refunds.body")}</p>
          <p className={s.hint}>{t("support.refunds.cancel")}</p>
          <p className={s.hint}>{t("support.refunds.contact", { email: CONTACT_EMAIL, username: OWNER_USERNAME })}</p>
          {inSettings ? null : (
            <p className={s.hint}>
              <button className={s.linkish} onClick={() => nav("/terms")}>{t("support.termsLink")}</button>
            </p>
          )}
        </div>
      </div>
    </section>
  );
}

/** The page in the DM list. */
export default function SupportPage() {
  return (
    <div className={s.page} data-testid="support-page">
      <header className={s.header}>
        <PageHeaderButtons />
        <Heart size={20} className={s.headerIcon} aria-hidden="true" />
        <span className={s.headerName}>{t("support.nav")}</span>
      </header>
      <div className={s.scroll}>
        <SupportPanel />
      </div>
    </div>
  );
}
