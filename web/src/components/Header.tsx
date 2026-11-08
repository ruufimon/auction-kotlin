import Link from "next/link";
import { Logo } from "./Logo";
import { SignOutButton } from "./SignOutButton";
import type { SessionUser } from "@/lib/server/session";

export function Header({ user }: { user: SessionUser | null }) {
  const label = user?.name || user?.email || "Account";
  return (
    <header className="header">
      <div className="container header-inner">
        <Link href="/" className="brand"><Logo />Gavel</Link>
        <nav aria-label="Primary" className="nav">
          <Link href="/marketplace">Browse</Link>
          <Link href={user ? "/profile/seller" : "/auth/login?returnTo=/profile/seller"} prefetch={false}>Sell</Link>
        </nav>
        <div className="header-actions">
          {user ? (
            <>
              <Link href="/profile" className="user-chip">
                <span className="avatar" aria-hidden="true">{label.slice(0, 1).toUpperCase()}</span>
                <span>{label}</span>
              </Link>
              <SignOutButton className="btn btn-ghost" />
            </>
          ) : (
            <>
              <a href="/auth/login" className="btn btn-ghost">Sign in</a>
              <a href="/auth/register" className="btn btn-primary">Create account</a>
            </>
          )}
        </div>
      </div>
    </header>
  );
}
