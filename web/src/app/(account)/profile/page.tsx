import type { Metadata } from "next";
import { ProfileView } from "./ProfileView";

export const metadata: Metadata = { title: "Your account" };

export default async function ProfilePage({ searchParams }: { searchParams: Promise<{ activated?: string; seller?: string }> }) {
  const params = await searchParams;
  return <ProfileView activated={params.activated === "1"} sellerEnabled={params.seller === "enabled"} />;
}
