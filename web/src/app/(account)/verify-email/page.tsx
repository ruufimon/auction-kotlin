"use client";
import { AccountGate } from "@/components/AccountGate";
import { Steps } from "@/components/Steps";

/**
 * "Check your email" (design screen 2). Keycloak owns the link and the resend: signing in again
 * shows its verify-email page with a resend action, so the app needs no endpoint (issue #30).
 */
export default function VerifyEmailPage() {
  return (
    <AccountGate allow={["PENDING_VERIFICATION"]}>
      {(account) => (
        <div className="narrow">
          <Steps current={1} />
          <div className="page-head">
            <span className="badge warning" style={{ alignSelf: "flex-start" }}>Pending verification</span>
            <h1>Check your email</h1>
            <p className="lead">
              We sent a verification link to <strong>{account.email}</strong>. The link works once and expires in 24 hours.
            </p>
          </div>
          <section className="card">
            <h2>What happens next</h2>
            <ol className="stack" style={{ margin: 0, paddingLeft: 20, gap: 8 }}>
              <li>Open the email and select <strong>Verify my email</strong>.</li>
              <li>Answer three short eligibility questions.</li>
              <li>Once approved, your account is active and you can bid or sell.</li>
            </ol>
          </section>
          <p className="muted">
            Didn’t get it? Check your spam folder, or{" "}
            <a href="/auth/login?returnTo=/welcome">resend the verification email</a> from the sign-in page.
          </p>
          <p className="muted small">
            Already verified? <a href="/auth/login?returnTo=/welcome">Continue</a>
          </p>
        </div>
      )}
    </AccountGate>
  );
}
