import type { Account, AccountState } from "@/lib/api/types";

/**
 * The app's three areas (issue #27):
 * - public: home, marketplace, signed-out, auth routes; anyone can open them;
 * - account: needs a session, then an account state the page allows (onboarding, profile);
 * - seller: needs a session, an ACTIVE account and selling enabled.
 */
export const SESSION_AREAS = ["/welcome", "/verify-email", "/onboarding", "/profile", "/sell"] as const;

/** Whether a path needs a session; checked by the request proxy before the page loads. */
export function needsSession(pathname: string): boolean {
  return SESSION_AREAS.some((p) => pathname === p || pathname.startsWith(`${p}/`));
}

/** Where each account state belongs after sign-in (issue #30). */
export function homeFor(state: AccountState): string {
  switch (state) {
    case "PENDING_VERIFICATION": return "/verify-email";
    case "PENDING_CREDIT_ELIGIBILITY": return "/onboarding/eligibility";
    case "ACTIVE": return "/marketplace";
    case "SUSPENDED": return "/signed-out?reason=ACCOUNT_SUSPENDED";
  }
}

/** The page where an account that is not a seller yet can enable selling. */
export const SELLER_SETUP = "/profile/seller";

export interface GateRule {
  /** Account states the page renders for; all states when omitted. */
  allow?: AccountState[];
  /** Seller-only page: also needs selling enabled. */
  seller?: boolean;
  /** Overrides where a disallowed state goes, e.g. the eligibility page sends a newly ACTIVE account to its profile. */
  redirect?: Partial<Record<AccountState, string>>;
}

export type GateDecision = { kind: "render" } | { kind: "redirect"; to: string } | { kind: "signOut" };

/** What a guarded page does with the loaded account: account state first, then the seller rule. */
export function gateDecision(account: Account, rule: GateRule): GateDecision {
  const state = account.accountState;
  if (rule.allow && !rule.allow.includes(state)) {
    return state === "SUSPENDED" ? { kind: "signOut" } : { kind: "redirect", to: rule.redirect?.[state] ?? homeFor(state) };
  }
  if (rule.seller && !account.sellerEnabled) return { kind: "redirect", to: SELLER_SETUP };
  return { kind: "render" };
}

/** Whether a header link belongs to the current page's section. */
export function isNavActive(href: string, pathname: string): boolean {
  return pathname === href || pathname.startsWith(`${href}/`);
}
