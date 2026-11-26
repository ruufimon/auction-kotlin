import type { Metadata } from "next";
import { EligibilityForm } from "./EligibilityForm";

export const metadata: Metadata = { title: "Eligibility check" };

export default function EligibilityPage() {
  return <EligibilityForm />;
}
