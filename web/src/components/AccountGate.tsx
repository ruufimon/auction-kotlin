"use client";
import { useEffect, useRef, type ReactNode } from "react";
import { useRouter } from "next/navigation";
import { useAccount } from "./AccountProvider";
import { Alert } from "./Alert";
import { signOut } from "./signOut";
import type { Account } from "@/lib/api/types";
import { gateDecision, type GateRule } from "@/lib/routes";

export { homeFor } from "@/lib/routes";

/**
 * Renders children once the account is loaded and the page's rule (src/lib/routes.ts) lets it in.
 * Other states are sent to their own page; a suspended account is signed out with an explanation (issue #31);
 * seller-only pages send accounts without selling enabled to seller setup (issue #27).
 */
export function AccountGate({ allow, seller, redirect, children }: GateRule & {
  children: (account: Account) => ReactNode;
}) {
  const state = useAccount();
  const router = useRouter();
  const leaving = useRef(false);
  const decision = state.account ? gateDecision(state.account, { allow, seller, redirect }) : null;
  const target = decision?.kind === "redirect" ? decision.to : decision?.kind ?? null;
  const allowed = target === "render";

  useEffect(() => {
    if (!target || target === "render" || leaving.current) return;
    leaving.current = true;
    if (target === "signOut") signOut("ACCOUNT_SUSPENDED");
    else router.replace(target);
  }, [target, router]);

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
