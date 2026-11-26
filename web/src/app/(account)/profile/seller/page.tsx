import type { Metadata } from "next";
import { SellerSetup } from "./SellerSetup";

export const metadata: Metadata = { title: "Seller profile" };

export default function SellerPage() {
  return <SellerSetup />;
}
