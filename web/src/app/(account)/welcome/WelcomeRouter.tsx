"use client";
import { useEffect, useRef } from "react";
import { useRouter } from "next/navigation";
import { useAccount } from "@/components/AccountProvider";
import { PageSkeleton } from "@/components/AccountGate";
import { homeFor } from "@/lib/routes";
import { Alert } from "@/components/Alert";
import { signOut } from "@/components/signOut";

/** Destinations an account may ask for after sign-in, by state; anything else goes to the state's home. */
function destination(state: string, returnTo: string | undefined): string {
  const safe = returnTo && returnTo.startsWith("/") && !returnTo.startsWith("//") && !returnTo.startsWith("/auth/") && returnTo !== "/welcome" ? returnTo : undefined;
  if (state === "ACTIVE") return safe ?? "/marketplace";
  if (safe?.startsWith("/profile")) return safe;
  return homeFor(state as never);
}

/**
 * Post-login landing (issue #30): GET /api/v1/me bootstraps the account, then the user is routed
 * by accountState. Suspended accounts are signed out with an explanation (issue #31).
 */
export function WelcomeRouter({ returnTo }: { returnTo?: string }) {
  const state = useAccount();
  const router = useRouter();
  const done = useRef(false);

  useEffect(() => {
    if (done.current || state.status !== "ready") return;
    done.current = true;
    if (state.account.accountState === "SUSPENDED") signOut("ACCOUNT_SUSPENDED");
    else router.replace(destination(state.account.accountState, returnTo));
  }, [state, router, returnTo]);

  if (state.status === "error") {
    return (
      <div className="narrow">
        <Alert kind="error" title="We could not finish signing you in">{state.error.userMessage}</Alert>
        <div className="row">
          <button className="btn btn-primary" onClick={() => void state.reload()}>Try again</button>
          <a className="btn btn-secondary" href="/auth/login">Sign in again</a>
        </div>
      </div>
    );
  }
  return <PageSkeleton />;
}
