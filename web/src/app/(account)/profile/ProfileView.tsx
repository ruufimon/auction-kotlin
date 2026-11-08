"use client";
import { useState, type FormEvent } from "react";
import Link from "next/link";
import { AccountGate } from "@/components/AccountGate";
import { AddressFields, toAddressValue, type AddressValue } from "@/components/AddressFields";
import { Alert } from "@/components/Alert";
import { Field } from "@/components/Field";
import { formatDate } from "@/components/format";
import { SignOutButton } from "@/components/SignOutButton";
import { StatusBanner } from "@/components/StatusBanner";
import { SaveFeedback, useProfileSave } from "@/components/useProfileSave";
import { reasonLabel } from "@/lib/api/errors";
import type { Account } from "@/lib/api/types";

/** Profile and account status (design screen 6, issue #32). */
export function ProfileView({ activated, sellerEnabled }: { activated: boolean; sellerEnabled: boolean }) {
  return (
    <AccountGate>
      {(account) => (
        <div className="stack-lg">
          <div className="page-head">
            <h1>Your account</h1>
            <p className="muted small">
              User ID <span className="mono">{account.userId}</span> · member since {formatDate(account.createdAt)}
            </p>
          </div>
          {activated && account.accountState === "ACTIVE" && <Alert kind="success" title="You’re in">The eligibility check passed and your account is active.</Alert>}
          {sellerEnabled && account.sellerEnabled && <Alert kind="success" title="Selling enabled">You can now list items from this same account.</Alert>}
          <StatusBanner account={account} />
          <div className="profile-layout">
            <div className="stack-lg">
              <ProfileCard account={account} />
              <ShippingCard account={account} />
            </div>
            <div className="stack-lg">
              <EligibilityCard account={account} />
              <SellingCard account={account} />
              <section className="card" aria-labelledby="sessions">
                <h2 id="sessions">Security and sessions</h2>
                <p className="muted small">Password, Google sign-in and devices are managed by your sign-in provider.</p>
                <div className="row">
                  <SignOutButton />
                </div>
              </section>
            </div>
          </div>
        </div>
      )}
    </AccountGate>
  );
}

function ProfileCard({ account }: { account: Account }) {
  const [displayName, setDisplayName] = useState(account.displayName ?? "");
  const [phone, setPhone] = useState(account.phone ?? "");
  const { save, status, errors } = useProfileSave();
  const readOnly = account.accountState === "SUSPENDED";

  function submit(e: FormEvent) {
    e.preventDefault();
    const update: { displayName?: string; phone?: string } = {};
    if (displayName.trim() !== (account.displayName ?? "")) update.displayName = displayName;
    if (phone.trim() !== (account.phone ?? "")) update.phone = phone;
    void save(account, update);
  }

  return (
    <form className="card" onSubmit={submit} noValidate aria-labelledby="profile-h">
      <h2 id="profile-h">Profile</h2>
      <Field label="Display name" value={displayName} onChange={(e) => setDisplayName(e.target.value)} maxLength={60}
        autoComplete="nickname" hint="Shown on bids and listings." error={errors.displayName} disabled={readOnly} />
      <Field label="Email" value={account.email} readOnly
        hint={account.emailVerified ? "Verified. Change it with your sign-in provider." : "Not verified yet."} />
      <Field label="Phone (optional)" type="tel" value={phone} onChange={(e) => setPhone(e.target.value)} autoComplete="tel"
        hint="Used by sellers to arrange delivery." error={errors.phone} disabled={readOnly} />
      {!readOnly && (
        <div className="form-actions">
          <button className="btn btn-primary" type="submit" disabled={status.kind === "saving"}>Save changes</button>
          <SaveFeedback status={status} />
        </div>
      )}
    </form>
  );
}

function ShippingCard({ account }: { account: Account }) {
  const [address, setAddress] = useState<AddressValue>(toAddressValue(account.shippingAddress));
  const { save, status, errors } = useProfileSave();
  const readOnly = account.accountState === "SUSPENDED";

  function submit(e: FormEvent) {
    e.preventDefault();
    void save(account, { shippingAddress: address });
  }

  return (
    <form className="card" onSubmit={submit} noValidate aria-labelledby="ship-h">
      <div className="card-head">
        <h2 id="ship-h">Shipping address</h2>
        <span className="muted small">Where won items are sent</span>
      </div>
      <AddressFields value={address} onChange={setAddress} errors={errors} disabled={readOnly} />
      {!readOnly && (
        <div className="form-actions">
          <button className="btn btn-primary" type="submit" disabled={status.kind === "saving"}>Save address</button>
          <SaveFeedback status={status} />
        </div>
      )}
    </form>
  );
}

function EligibilityCard({ account }: { account: Account }) {
  const outcome = account.creditEligibilityOutcome;
  return (
    <section className="card" aria-labelledby="elig-h">
      <h2 id="elig-h">Eligibility</h2>
      {outcome ? (
        <dl className="facts">
          <dt>Outcome</dt>
          <dd><span className={`badge ${outcome === "ELIGIBLE" ? "success" : "danger"}`}>{outcome}</span></dd>
          <dt>Reason</dt>
          <dd><span className="mono">{account.eligibilityReason}</span><br /><span className="muted small">{reasonLabel(account.eligibilityReason)}</span></dd>
          <dt>Policy version</dt>
          <dd className="mono">{account.assessmentPolicyVersion}</dd>
          <dt>Assessed</dt>
          <dd>{formatDate(account.assessedAt, true)}</dd>
        </dl>
      ) : (
        <p className="muted">Not assessed yet.</p>
      )}
      <p className="muted small">
        We keep the outcome, reason, policy version and time for audit. The age, income and wealth you entered are encrypted and are
        never displayed here or returned by the API.
      </p>
      {account.accountState === "PENDING_CREDIT_ELIGIBILITY" && (
        <div><Link className="btn btn-secondary" href="/onboarding/eligibility">{outcome ? "Retry the check" : "Start the check"}</Link></div>
      )}
    </section>
  );
}

function SellingCard({ account }: { account: Account }) {
  return (
    <section className="card" aria-labelledby="sell-h">
      <h2 id="sell-h">Selling</h2>
      {account.sellerEnabled ? (
        <p>Selling is enabled since {formatDate(account.sellerEnabledAt)}.</p>
      ) : (
        <p className="muted">Same account, no separate registration. Add your seller details and origin address to start listing items.</p>
      )}
      {account.accountState !== "SUSPENDED" && (
        <div><Link className="btn btn-secondary" href="/profile/seller">{account.sellerEnabled ? "Edit seller profile" : "Set up seller profile"}</Link></div>
      )}
    </section>
  );
}
