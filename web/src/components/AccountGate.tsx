"use client";
import { useEffect, useRef, type ReactNode } from "react";
import { useRouter } from "next/navigation";
import { useAccount } from "./AccountProvider";
import { Alert } from "./Alert";
import { signOut } from "./signOut";
import type { Account, AccountState } from "@/lib/api/types";

/** Where each account state belongs after sign-in (issue #30). */
export function homeFor(state: AccountState): string {
  switch (state) {
    case "PENDING_VERIFICATION": return "/verify-email";
    case "PENDING_CREDIT_ELIGIBILITY": return "/onboarding/eligibility";
    case "ACTIVE": return "/marketplace";
    case "SUSPENDED": return "/signed-out?reason=ACCOUNT_SUSPENDED";
  }
}

/**
 * Renders children once the account is loaded and in an allowed state. Other states are sent
 * to their own page; a suspended account is signed out with an explanation (issue #31).
 */
export function AccountGate({ allow, redirect, children }: {
  allow?: AccountState[];
  /** Overrides where a disallowed state goes, e.g. the eligibility page sends a newly ACTIVE account to its profile. */
  redirect?: Partial<Record<AccountState, string>>;
  children: (account: Account) => ReactNode;
}) {
  const state = useAccount();
  const router = useRouter();
  const leaving = useRef(false);
  const accountState = state.account?.accountState;
  const allowed = !!accountState && (!allow || allow.includes(accountState));

  useEffect(() => {
    if (!accountState || allowed || leaving.current) return;
    leaving.current = true;
    if (accountState === "SUSPENDED") signOut("ACCOUNT_SUSPENDED");
    else router.replace(redirect?.[accountState] ?? homeFor(accountState));
  }, [accountState, allowed, router, redirect]);

  useEffect(() => {
    if (state.status === "error" && state.error.code === "ACCOUNT_SUSPENDED" && !leaving.current) {
      leaving.current = true;
      signOut("ACCOUNT_SUSPENDED");
    }
  }, [state]);

  if (state.status === "error") {
    return (
      <div className="narrow">
        <Alert kind="error" title="We could not load your account">{state.error.userMessage}</Alert>
        <div><button className="btn btn-secondary" onClick={() => void state.reload()}>Try again</button></div>
      </div>
    );
  }
  if (state.status === "loading" || !allowed) return <PageSkeleton />;
  return <>{children(state.account)}</>;
}

export function PageSkeleton() {
  return (
    <div className="narrow" aria-busy="true" aria-live="polite">
      <span className="visually-hidden">Loading your account…</span>
      <div className="skeleton" style={{ height: 36, width: "60%" }} />
      <div className="skeleton" style={{ height: 18, width: "90%" }} />
      <div className="skeleton" style={{ height: 220 }} />
    </div>
  );
}
