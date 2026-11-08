import type { ErrorDetail } from "./types";

/**
 * User-facing text for the API's stable codes (requirement §6.5). The code is the contract;
 * these messages may change freely. Unknown codes fall back to a generic message.
 */
const MESSAGES: Record<string, string> = {
  // Session and tokens
  SESSION_MISSING: "Please sign in to continue.",
  SESSION_EXPIRED: "Your session has ended. Please sign in again.",
  TOKEN_MISSING: "Please sign in to continue.",
  TOKEN_INVALID: "Your sign-in could not be verified. Please sign in again.",
  TOKEN_EXPIRED: "Your session has ended. Please sign in again.",
  TOKEN_WRONG_AUDIENCE: "Your sign-in is not valid for Gavel. Please sign in again.",
  TOKEN_MISSING_CLAIMS: "Your sign-in is missing your email address. Please sign in again.",
  IDENTITY_PROVIDER_UNAVAILABLE: "The sign-in service is unavailable. Please try again in a moment.",
  SIGN_IN_CANCELLED: "Sign-in was cancelled.",
  SIGN_IN_EXPIRED: "The sign-in attempt expired. Please try again.",
  SIGN_IN_FAILED: "We could not complete sign-in. Please try again.",
  SIGNED_OUT: "You have been signed out.",
  // Account states
  ACCOUNT_NOT_BOOTSTRAPPED: "We are still setting up your account. Please reload the page.",
  ACCOUNT_PENDING_VERIFICATION: "Verify your email address to continue.",
  ACCOUNT_PENDING_CREDIT_ELIGIBILITY: "Complete the eligibility check to bid or sell.",
  ACCOUNT_ACTIVE: "Your account is already active, so this step is not needed.",
  ACCOUNT_SUSPENDED: "This account is suspended. Bidding, listing and fulfilment are disabled. Contact support if you think this is a mistake.",
  // Profile and assessment
  VALIDATION_FAILED: "Some fields need attention.",
  VERSION_CONFLICT: "Your profile changed in another window. We loaded the latest version; please review and save again.",
  CREDIT_ELIGIBILITY_NOT_MET: "The eligibility check did not pass.",
  ASSESSMENT_RETRY_NOT_YET_ALLOWED: "You can retry the eligibility check later.",
  // Selling
  SELLER_PROFILE_INCOMPLETE: "Complete the missing seller details first.",
  SELLER_ALREADY_ENABLED: "Selling is already enabled on this account.",
  // Generic
  FORBIDDEN: "You do not have access to this.",
  NOT_FOUND: "We could not find that.",
  RATE_LIMITED: "Too many requests. Please wait a moment and try again.",
  MALFORMED_REQUEST: "Something went wrong with that request. Please reload and try again.",
  API_UNAVAILABLE: "Gavel is unreachable right now. Please try again in a moment.",
  INTERNAL_ERROR: "Something went wrong on our side. Please try again.",
};

/** Eligibility reason codes (CreditPolicy). */
const REASONS: Record<string, string> = {
  MEETS_POLICY: "Meets the eligibility policy",
  BELOW_MINIMUM_SCORE: "Below the minimum score required by the policy",
  UNDER_MINIMUM_AGE: "Under the minimum age",
};

/** Field-level codes. */
const FIELD_MESSAGES: Record<string, string> = {
  REQUIRED: "Required.",
  TOO_LONG: "Too long.",
  INVALID: "Not valid.",
  INTEGER_REQUIRED: "Enter a whole number.",
  OUT_OF_RANGE: "Enter a realistic value.",
  BELOW_MINIMUM: "You must be at least 18.",
  NEGATIVE: "Cannot be negative.",
  READ_ONLY: "This is managed by your sign-in provider.",
  STALE: "Changed elsewhere.",
};

export const GENERIC_ERROR = "Something went wrong. Please try again.";

export function messageFor(code: string | null | undefined): string {
  return (code && MESSAGES[code]) || GENERIC_ERROR;
}

export function reasonLabel(code: string | null | undefined): string {
  return (code && REASONS[code]) || "Not met";
}

export function fieldMessage(detail: ErrorDetail): string {
  if (detail.field === "country" && detail.code === "INVALID") return "Use a two-letter country code, for example US.";
  if (detail.field === "phone" && detail.code === "INVALID") return "Digits only, with an optional + prefix.";
  return FIELD_MESSAGES[detail.code] ?? detail.message ?? "Not valid.";
}

/** Maps error details to `{ field: message }` for inline form errors. */
export function fieldErrors(details: ErrorDetail[] | undefined): Record<string, string> {
  const out: Record<string, string> = {};
  for (const d of details ?? []) if (d.field && !out[d.field]) out[d.field] = fieldMessage(d);
  return out;
}
