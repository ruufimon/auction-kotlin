/** Mirrors AccountResponse in features/user-onboarding (web/Dtos.kt). */
export type AccountState = "PENDING_VERIFICATION" | "PENDING_CREDIT_ELIGIBILITY" | "ACTIVE" | "SUSPENDED";

export interface Address {
  line1: string;
  city: string;
  region: string;
  postalCode: string;
  country: string;
}

export interface Account {
  userId: string;
  email: string;
  displayName: string | null;
  accountState: AccountState;
  emailVerified: boolean;
  sellerEnabled: boolean;
  sellerEnabledAt: string | null;
  creditEligibilityOutcome: "ELIGIBLE" | "NOT_ELIGIBLE" | null;
  eligibilityReason: string | null;
  assessmentPolicyVersion: string | null;
  assessedAt: string | null;
  phone: string | null;
  shippingAddress: Address | null;
  sellerProfile: Record<string, string>;
  originAddress: Record<string, string>;
  createdAt: string;
  updatedAt: string;
  version: number;
}

export interface ErrorDetail {
  field?: string | null;
  code: string;
  message?: string | null;
  value?: string | null;
}

/** Requirement §6.5 error body. */
export interface ApiErrorBody {
  correlationId: string | null;
  code: string;
  message: string;
  details: ErrorDetail[];
}

export interface ProfileUpdate {
  version: number;
  displayName?: string;
  phone?: string;
  shippingAddress?: Address;
  sellerProfile?: Record<string, string>;
  originAddress?: Record<string, string>;
  /** Money in integer minor units. */
  assessment?: { age: number; annualIncome: number; netWealth: number };
}
