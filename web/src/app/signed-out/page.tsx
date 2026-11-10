import type { Metadata } from "next";
import { messageFor } from "@/lib/api/errors";
import { Alert } from "@/components/Alert";

export const metadata: Metadata = { title: "Signed out" };

const TITLES: Record<string, string> = {
  SIGNED_OUT: "You are signed out",
  ACCOUNT_SUSPENDED: "Your account is suspended",
  SESSION_EXPIRED: "Your session has ended",
  SIGN_IN_CANCELLED: "Sign-in cancelled",
  SIGN_IN_EXPIRED: "Sign-in expired",
  SIGN_IN_FAILED: "Sign-in failed",
};

export default async function SignedOutPage({ searchParams }: { searchParams: Promise<{ reason?: string }> }) {
  const { reason = "SIGNED_OUT" } = await searchParams;
  const code = reason in TITLES ? reason : "SIGNED_OUT";
  const kind = code === "ACCOUNT_SUSPENDED" || code === "SIGN_IN_FAILED" ? "error" : code === "SIGNED_OUT" ? "success" : "info";
  return (
    <div className="narrow">
      <h1>{TITLES[code]}</h1>
      <Alert kind={kind}>
        {messageFor(code)}
        {code === "ACCOUNT_SUSPENDED" && <> Your orders and bids remain visible to support. Reference code <span className="mono">ACCOUNT_SUSPENDED</span>.</>}
      </Alert>
      <div className="row">
        <a className="btn btn-primary" href="/auth/login">Sign in</a>
        <a className="btn btn-secondary" href="/">Home</a>
      </div>
    </div>
  );
}
