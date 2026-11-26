const STEPS = ["Register", "Verify email", "Eligibility check", "Start bidding"] as const;

/** Onboarding progress; `current` is the zero-based index of the step in progress. */
export function Steps({ current }: { current: number }) {
  return (
    <ol className="steps" aria-label="Onboarding steps">
      {STEPS.map((label, i) => {
        const status = i < current ? "done" : i === current ? "current" : "todo";
        return (
          <li key={label} className={status} aria-current={status === "current" ? "step" : undefined}>
            <span className="dot" aria-hidden="true">{status === "done" ? "✓" : i + 1}</span>
            {label}
            {status === "done" && <span className="visually-hidden"> (done)</span>}
          </li>
        );
      })}
    </ol>
  );
}
