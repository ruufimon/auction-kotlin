"use client";
import { AccountGate } from "@/components/AccountGate";

/**
 * The seller area (issue #27): every page under it needs an ACTIVE account with selling enabled.
 * Accounts without selling enabled are sent to seller setup; other states go to their own page.
 */
export default function SellerAreaLayout({ children }: { children: React.ReactNode }) {
  return <AccountGate allow={["ACTIVE"]} seller>{() => children}</AccountGate>;
}
