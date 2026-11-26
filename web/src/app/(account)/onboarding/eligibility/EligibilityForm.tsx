"use client";
import { useState, type FormEvent } from "react";
import Link from "next/link";
import { AccountGate } from "@/components/AccountGate";
import { useAccount } from "@/components/AccountProvider";
import { Alert } from "@/components/Alert";
import { Field } from "@/components/Field";
import { Steps } from "@/components/Steps";
import { formatDate } from "@/components/format";
import { signOut } from "@/components/signOut";
import { api, ApiError } from "@/lib/api/client";
import { fieldErrors, messageFor, reasonLabel } from "@/lib/api/errors";
import { parseWholeUnits } from "@/lib/api/money";
import { CURRENCY } from "@/lib/api/settings";
import type { Account } from "@/lib/api/types";

type Outcome =
  | { kind: "none" }
  | { kind: "not-eligible"; reason: string }
  | { kind: "retry-later"; earliestRetryAt: string | null }
  | { kind: "error"; message: string };

/** A passing assessment makes the account ACTIVE; the gate then moves the user to the profile once. */
const AFTER_ASSESSMENT = { ACTIVE: "/profile?activated=1" } as const;

const MONEY_MESSAGES = { REQUIRED: "Required.", INVALID: "Enter a whole number without decimals.", NEGATIVE: "Cannot be negative.", TOO_LARGE: "That amount is too large." };

/** Credit-eligibility assessment (design screen 5, USR-011, USR-012). Inputs are sent once and never shown back. */
export function EligibilityForm() {
  return (
    <AccountGate allow={["PENDING_CREDIT_ELIGIBILITY"]} redirect={AFTER_ASSESSMENT}>
      {(account) => <Form account={account} />}
    </AccountGate>
  );
}

function Form({ account }: { account: Account }) {
  const { replace, reload } = useAccount();
  const [age, setAge] = useState("");
  const [income, setIncome] = useState("");
  const [wealth, setWealth] = useState("");
  const [errors, setErrors] = useState<Record<string, string>>({});
  const [busy, setBusy] = useState(false);
  const [outcome, setOutcome] = useState<Outcome>(
    account.creditEligibilityOutcome === "NOT_ELIGIBLE" ? { kind: "not-eligible", reason: account.eligibilityReason ?? "" } : { kind: "none" },
  );

  function validate() {
    const next: Record<string, string> = {};
    const ageNumber = /^\d{1,3}$/.test(age.trim()) ? Number(age.trim()) : NaN;
    if (age.trim() === "") next.age = "Required.";
    else if (Number.isNaN(ageNumber)) next.age = "Enter your age in whole years.";
    else if (ageNumber < 18) next.age = "You must be at least 18.";
    else if (ageNumber > 130) next.age = "Enter a realistic value.";
    const incomeMinor = parseWholeUnits(income, CURRENCY);
    if (!incomeMinor.ok) next.annualIncome = MONEY_MESSAGES[incomeMinor.code];
    const wealthMinor = parseWholeUnits(wealth, CURRENCY);
    if (!wealthMinor.ok) next.netWealth = wealthMinor.code === "NEGATIVE" ? "Net wealth cannot be negative. Enter 0 if your debts exceed your assets." : MONEY_MESSAGES[wealthMinor.code];
    setErrors(next);
    if (Object.keys(next).length > 0 || !incomeMinor.ok || !wealthMinor.ok) return null;
    return { age: ageNumber, annualIncome: incomeMinor.minor, netWealth: wealthMinor.minor };
  }

  async function submit(e: FormEvent) {
    e.preventDefault();
    const assessment = validate();
    if (!assessment) return;
    setBusy(true);
    try {
      replace(await api.updateProfile(account.userId, { version: account.version, assessment }));
    } catch (err) {
      if (!(err instanceof ApiError)) { setOutcome({ kind: "error", message: messageFor(null) }); return; }
      switch (err.code) {
        case "CREDIT_ELIGIBILITY_NOT_MET":
          // The outcome is recorded; reload so the version and reason are current.
          setOutcome({ kind: "not-eligible", reason: err.details[0]?.code ?? "" });
          setAge(""); setIncome(""); setWealth("");
          await reload();
          break;
        case "ASSESSMENT_RETRY_NOT_YET_ALLOWED":
          setOutcome({ kind: "retry-later", earliestRetryAt: err.details.find((d) => d.field === "earliestRetryAt")?.value ?? null });
          break;
        case "VALIDATION_FAILED":
          setErrors(fieldErrors(err.details));
          break;
        case "VERSION_CONFLICT":
          await reload();
          setOutcome({ kind: "error", message: messageFor(err.code) });
          break;
        case "ACCOUNT_SUSPENDED":
          signOut("ACCOUNT_SUSPENDED");
          break;
        default:
          setOutcome({ kind: "error", message: err.userMessage });
      }
    } finally {
      setBusy(false);
    }
  }

  return (
    <div className="narrow">
      <Steps current={2} />
      <div className="page-head">
        <h1>A quick eligibility check</h1>
        <p className="lead">
          Because winning a bid is a binding commitment, we ask three questions before activating your account. The result is
          calculated on our servers against a published policy.
        </p>
      </div>

      {outcome.kind === "not-eligible" && (
        <Alert kind="error" title="The eligibility check did not pass">
          Reason <span className="mono">{outcome.reason}</span>: {reasonLabel(outcome.reason)}. You can keep browsing. You may retry after the
          policy’s cooling-off period.
        </Alert>
      )}
      {outcome.kind === "retry-later" && (
        <Alert kind="warning" title="Not yet">
          {outcome.earliestRetryAt ? <>You can retry the check from {formatDate(outcome.earliestRetryAt, true)}.</> : messageFor("ASSESSMENT_RETRY_NOT_YET_ALLOWED")}
        </Alert>
      )}
      {outcome.kind === "error" && <Alert kind="error">{outcome.message}</Alert>}

      <form className="card" onSubmit={submit} noValidate>
        <Field label="Your age" inputMode="numeric" autoComplete="off" value={age} onChange={(e) => setAge(e.target.value)}
          error={errors.age} hint="You must be at least 18 to use Gavel." style={{ maxWidth: 140 }} />
        <Field label="Annual income" prefix={CURRENCY} inputMode="numeric" autoComplete="off" value={income}
          onChange={(e) => setIncome(e.target.value)} error={errors.annualIncome}
          hint="Before tax, whole units. Stored encrypted and never shown again." />
        <Field label="Net wealth" prefix={CURRENCY} inputMode="numeric" autoComplete="off" value={wealth}
          onChange={(e) => setWealth(e.target.value)} error={errors.netWealth}
          hint="Assets minus debts, whole units. Enter 0 if your debts exceed your assets." />
        <Alert kind="info">
          These answers are encrypted at rest, excluded from logs, and kept only as long as our retention policy requires. We store the
          outcome, the policy version and the time of the check, not a score.
        </Alert>
        <div className="form-actions">
          <button type="submit" className="btn btn-primary" disabled={busy} aria-busy={busy}>
            {busy && <span className="spinner" aria-hidden="true" />} Submit and activate
          </button>
          <Link href="/profile" className="btn btn-secondary">Do this later</Link>
        </div>
      </form>

      <section className="stack" aria-labelledby="if-not">
        <h2 id="if-not">If the check does not pass</h2>
        <p className="muted">
          Your account stays pending with a reason you can see on your profile, for example <span className="mono">BELOW_MINIMUM_SCORE</span>.
          You can keep browsing, and you may be able to retry after the policy’s cooling-off period.
        </p>
      </section>
    </div>
  );
}
