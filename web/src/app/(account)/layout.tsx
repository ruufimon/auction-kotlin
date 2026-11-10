import { AccountProvider } from "@/components/AccountProvider";

/** Pages that need the signed-in account. The request proxy (src/proxy.ts) already sent anonymous visitors to sign-in. */
export default function AccountLayout({ children }: { children: React.ReactNode }) {
  return <AccountProvider>{children}</AccountProvider>;
}
