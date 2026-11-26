"use client";
import { useId, useMemo, useState, type FormEvent } from "react";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { AccountGate } from "@/components/AccountGate";
import { useAccount } from "@/components/AccountProvider";
import { AddressFields, toAddressValue, type AddressValue } from "@/components/AddressFields";
import { Alert } from "@/components/Alert";
import { Field } from "@/components/Field";
import { signOut } from "@/components/signOut";
import { SaveFeedback, useProfileSave } from "@/components/useProfileSave";
import { api, ApiError } from "@/lib/api/client";
import { messageFor } from "@/lib/api/errors";
import { SELLER_FIELDS } from "@/lib/api/settings";
import type { Account } from "@/lib/api/types";

type SellerValue = Record<string, string>;

/** Seller profile and enable selling (design screen 7, issue #32, USR-008, USR-009). */
export function SellerSetup() {
  return (
    <AccountGate allow={["PENDING_VERIFICATION", "PENDING_CREDIT_ELIGIBILITY", "ACTIVE"]}>
      {(account) => <Setup account={account} />}
    </AccountGate>
  );
}

function initialSeller(account: Account): SellerValue {
  const value: SellerValue = { sellerType: account.sellerProfile.sellerType ?? "individual" };
  for (const f of SELLER_FIELDS) value[f.key] = account.sellerProfile[f.key] ?? "";
  return value;
}

function Setup({ account }: { account: Account }) {
  const router = useRouter();
  const { replace } = useAccount();
  const [seller, setSeller] = useState<SellerValue>(() => initialSeller(account));
  const [origin, setOrigin] = useState<AddressValue>(() => toAddressValue(account.originAddress));
  const [sameAsShipping, setSameAsShipping] = useState(false);
  const { save, status, errors, setErrors } = useProfileSave();
  const [enableError, setEnableError] = useState<string | null>(null);
  const [enabling, setEnabling] = useState(false);
  const typeName = useId();

  const effectiveOrigin = sameAsShipping && account.shippingAddress ? toAddressValue(account.shippingAddress) : origin;
  const dirty = useMemo(() => {
    const savedSeller = initialSeller(account);
    const savedOrigin = toAddressValue(account.originAddress);
    return Object.keys(seller).some((k) => seller[k].trim() !== (savedSeller[k] ?? "").trim())
      || (Object.keys(effectiveOrigin) as (keyof AddressValue)[]).some((k) => effectiveOrigin[k].trim() !== savedOrigin[k].trim());
  }, [account, seller, effectiveOrigin]);

  const sellerMissing = SELLER_FIELDS.filter((f) => !seller[f.key]?.trim()).length;
  const originMissing = (Object.keys(effectiveOrigin) as (keyof AddressValue)[]).filter((k) => !effectiveOrigin[k].trim()).length;
  const active = account.accountState === "ACTIVE";
  const canEnable = active && !account.sellerEnabled && sellerMissing === 0 && originMissing === 0;

  async function persist(): Promise<Account | null> {
    return save(account, { sellerProfile: seller, originAddress: effectiveOrigin });
  }

  function submit(e: FormEvent) {
    e.preventDefault();
    void persist();
  }

  async function enable() {
    setEnableError(null);
    setEnabling(true);
    try {
      const current = dirty ? await persist() : account;
      if (!current) return;
      const updated = await api.enableSeller(current.userId);
      replace(updated);
      router.push("/profile?seller=enabled");
    } catch (err) {
      const e = err instanceof ApiError ? err : null;
      if (e?.code === "ACCOUNT_SUSPENDED") { signOut("ACCOUNT_SUSPENDED"); return; }
      if (e?.code === "SELLER_PROFILE_INCOMPLETE") {
        // Server-side requirements are configurable; show exactly which fields it wants.
        const missing: Record<string, string> = {};
        for (const d of e.details) if (d.field) missing[d.field] = "Required to enable selling (SELLER_PROFILE_FIELD_MISSING).";
        setErrors(missing);
      }
      setEnableError(messageFor(e?.code));
    } finally {
      setEnabling(false);
    }
  }

  const readyItems = [
    { ok: account.emailVerified, label: "Email verified" },
    { ok: active, label: "Account active (eligibility passed)", action: !active && account.accountState === "PENDING_CREDIT_ELIGIBILITY" ? <Link href="/onboarding/eligibility">Start the check</Link> : null },
    { ok: sellerMissing === 0, label: "Seller details complete", note: sellerMissing ? `${sellerMissing} field${sellerMissing > 1 ? "s" : ""} missing` : null },
    { ok: originMissing === 0, label: "Origin address complete", note: originMissing ? `${originMissing} field${originMissing > 1 ? "s" : ""} missing` : null },
  ];

  return (
    <div className="stack-lg">
      <div className="page-head">
        <p className="small muted"><Link href="/profile">Your account</Link> / Seller profile</p>
        <h1>Start selling on Gavel</h1>
        <p className="lead">Your buyer account becomes a seller account once these details are complete. Nothing else to register, and you keep bidding as usual.</p>
      </div>
      {account.sellerEnabled && <Alert kind="success" title="Selling is enabled">You can update these details at any time. <Link href="/sell">Go to your seller area</Link></Alert>}
      <div className="profile-layout">
        <form className="stack-lg" onSubmit={submit} noValidate>
          <section className="card" aria-labelledby="seller-h">
            <h2 id="seller-h">Seller details</h2>
            <fieldset className="stack" style={{ gap: 8 }}>
              <legend className="small" style={{ fontWeight: 600, marginBottom: 8 }}>Selling as</legend>
              <div className="grid-2">
                {[["individual", "Individual", "Occasional sales of your own items"], ["business", "Business", "Registered trader with a tax ID"]].map(([value, label, hint]) => (
                  <label key={value} className="card" style={{ padding: 14, gap: 4, cursor: "pointer", borderColor: seller.sellerType === value ? "var(--primary)" : undefined }}>
                    <span className="row" style={{ gap: 8 }}>
                      <input type="radio" name={typeName} value={value} checked={seller.sellerType === value}
                        onChange={() => setSeller({ ...seller, sellerType: value })} style={{ accentColor: "var(--primary)" }} />
                      <strong>{label}</strong>
                    </span>
                    <span className="muted small">{hint}</span>
                  </label>
                ))}
              </div>
            </fieldset>
            {SELLER_FIELDS.map((f) => (
              <Field key={f.key} label={f.label} hint={f.hint} autoComplete={f.autoComplete} value={seller[f.key] ?? ""}
                type={f.key === "contactPhone" ? "tel" : "text"} error={errors[f.key]}
                onChange={(e) => setSeller({ ...seller, [f.key]: e.target.value })} />
            ))}
          </section>

          <section className="card" aria-labelledby="origin-h">
            <div className="card-head">
              <h2 id="origin-h">Origin address</h2>
              <span className="muted small">Where items ship from · shown as city and country on listings</span>
            </div>
            {account.shippingAddress && (
              <label className="row small" style={{ gap: 8 }}>
                <input type="checkbox" checked={sameAsShipping} onChange={(e) => setSameAsShipping(e.target.checked)} style={{ width: 18, height: 18, accentColor: "var(--primary)" }} />
                Same as my shipping address
              </label>
            )}
            <AddressFields value={effectiveOrigin} onChange={setOrigin} errors={{ ...prefixed(errors) }} errorPrefix="originAddress." disabled={sameAsShipping} />
          </section>

          <div className="form-actions">
            <button type="submit" className="btn btn-secondary" disabled={status.kind === "saving" || !dirty}>Save seller profile</button>
            <Link href="/profile" className="btn btn-ghost">Cancel</Link>
            <SaveFeedback status={status} />
          </div>
        </form>

        <aside className="card" aria-labelledby="ready-h" style={{ position: "sticky", top: 16 }}>
          <h2 id="ready-h">Ready to sell?</h2>
          <ul className="checklist">
            {readyItems.map((item) => (
              <li key={item.label}>
                <span className={`check ${item.ok ? "ok" : ""}`} aria-hidden="true">✓</span>
                <span>
                  {item.label}
                  <span className="visually-hidden">{item.ok ? ": done" : ": not done"}</span>
                  {item.note && <span className="muted"> · {item.note}</span>}
                  {item.action && <> · {item.action}</>}
                </span>
              </li>
            ))}
          </ul>
          {enableError && <Alert kind="error">{enableError}</Alert>}
          {account.sellerEnabled ? (
            <span className="badge success" style={{ alignSelf: "flex-start" }}>Selling enabled</span>
          ) : (
            <button type="button" className="btn btn-primary btn-block" disabled={!canEnable || enabling} onClick={() => void enable()}>
              {enabling && <span className="spinner" aria-hidden="true" />} Enable selling
            </button>
          )}
          <p className="muted small">Available once every item above is complete. Enabling is immediate and does not need a review.</p>
        </aside>
      </div>
    </div>
  );
}

/** Origin-address errors arrive as "originAddress.city" from enable-seller and as "city" from a PUT. */
function prefixed(errors: Record<string, string>): Record<string, string> {
  const out = { ...errors };
  for (const k of ["line1", "city", "region", "postalCode", "country"]) if (errors[k] && !out[`originAddress.${k}`]) out[`originAddress.${k}`] = errors[k];
  return out;
}
