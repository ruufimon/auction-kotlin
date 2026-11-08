import type { Metadata } from "next";
import { WelcomeRouter } from "./WelcomeRouter";

export const metadata: Metadata = { title: "Signing you in" };

export default async function WelcomePage({ searchParams }: { searchParams: Promise<{ returnTo?: string }> }) {
  const { returnTo } = await searchParams;
  return <WelcomeRouter returnTo={returnTo} />;
}
