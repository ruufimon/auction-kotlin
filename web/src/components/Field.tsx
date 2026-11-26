"use client";
import { useId, type InputHTMLAttributes, type ReactNode } from "react";

interface FieldProps extends Omit<InputHTMLAttributes<HTMLInputElement>, "id"> {
  label: string;
  hint?: ReactNode;
  error?: string;
  prefix?: string;
}

/** Labelled input with hint and inline error, wired with aria-describedby / aria-invalid. */
export function Field({ label, hint, error, prefix, ...input }: FieldProps) {
  const id = useId();
  const hintId = hint ? `${id}-hint` : undefined;
  const errorId = error ? `${id}-error` : undefined;
  const control = (
    <input
      id={id}
      className="input"
      aria-invalid={error ? true : undefined}
      aria-describedby={[errorId, hintId].filter(Boolean).join(" ") || undefined}
      {...input}
    />
  );
  return (
    <div className="field">
      <label htmlFor={id}>{label}</label>
      {prefix ? <div className="input-wrap"><span className="input-prefix" aria-hidden="true">{prefix}</span>{control}</div> : control}
      {error && <p id={errorId} className="error" role="alert"><ErrorIcon />{error}</p>}
      {hint && <p id={hintId} className="hint">{hint}</p>}
    </div>
  );
}

function ErrorIcon() {
  return (
    <svg width="16" height="16" viewBox="0 0 16 16" fill="none" aria-hidden="true" style={{ flex: "none" }}>
      <circle cx="8" cy="8" r="6.5" stroke="currentColor" strokeWidth="1.5" />
      <path d="M8 4.5v4M8 11h.01" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" />
    </svg>
  );
}
