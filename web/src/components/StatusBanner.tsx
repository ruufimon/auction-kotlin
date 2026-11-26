import Link from "next/link";
import type { Account } from "@/lib/api/types";
import { reasonLabel } from "@/lib/api/errors";
import { formatDate } from "./format";

/** Account status banner for each state (issue #32), with the stable state and reason codes visible. */
export function StatusBanner({ account }: { account: Account }) {
  switch (account.accountState) {
    case "PENDING_VERIFICATION":
      return (
        <section className="banner warning" aria-label="Account status">
          <div className="banner-body">
            <span className="badge warning">PENDING_VERIFICATION</span>
            <h2>Verify your email to continue</h2>
            <p>Bidding and selling stay locked until you open the link we sent to {account.email}.</p>
          </div>
          <Link className="btn btn-secondary" href="/verify-email">Resend email</Link>
        </section>
      );
    case "PENDING_CREDIT_ELIGIBILITY":
      if (account.creditEligibilityOutcome === "NOT_ELIGIBLE") {
        return (
          <section className="banner danger" aria-label="Account status">
            <div className="banner-body">
              <span className="badge danger">PENDING_CREDIT_ELIGIBILITY</span>
              <h2>The eligibility check did not pass</h2>
              <p>
                Reason <span className="badge">{account.eligibilityReason}</span> ({reasonLabel(account.eligibilityReason)}) under
                policy {account.assessmentPolicyVersion}. You can browse, but not bid or sell.
              </p>
            </div>
            <Link className="btn btn-secondary" href="/onboarding/eligibility">Retry the check</Link>
          </section>
        );
      }
      return (
        <section className="banner info" aria-label="Account status">
          <div className="banner-body">
            <span className="badge info">PENDING_CREDIT_ELIGIBILITY</span>
            <h2>One step left: the eligibility check</h2>
            <p>Your email is verified. Answer three questions to activate bidding and selling.</p>
          </div>
          <Link className="btn btn-primary" href="/onboarding/eligibility">Start the check</Link>
        </section>
      );
    case "ACTIVE":
      return (
        <section className="banner success" aria-label="Account status">
          <div className="banner-body">
            <span className="badge success">ACTIVE</span>
            <h2>Your account is active.</h2>
            <p>
              {account.sellerEnabled
                ? `You can bid on any auction and list items. Selling enabled ${formatDate(account.sellerEnabledAt)}.`
                : "You can bid on any auction. Complete a seller profile to list items too."}
            </p>
          </div>
        </section>
      );
    case "SUSPENDED":
      return (
        <section className="banner danger" aria-label="Account status">
          <div className="banner-body">
            <span className="badge danger">SUSPENDED</span>
            <h2>This account is suspended</h2>
            <p>Bidding, listing and fulfilment are disabled. Your orders and bids remain visible for reference.</p>
          </div>
        </section>
      );
  }
}
