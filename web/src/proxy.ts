import { NextResponse, type NextRequest } from "next/server";
import { needsSession } from "@/lib/routes";

/**
 * Sends visitors without a session cookie to sign-in, keeping where they were going (issue #27).
 * Account-state and seller rules are applied in the page by AccountGate.
 */
export function proxy(req: NextRequest) {
  const { pathname, search } = req.nextUrl;
  if (!needsSession(pathname)) return NextResponse.next();
  if (req.cookies.get("gavel_rt")?.value) return NextResponse.next();
  const login = new URL("/auth/login", req.url);
  login.searchParams.set("returnTo", pathname + search);
  return NextResponse.redirect(login);
}

// Literal list: Next.js reads the matcher at build time. Keep it in step with SESSION_AREAS in src/lib/routes.ts.
export const config = { matcher: ["/welcome", "/verify-email", "/onboarding/:path*", "/profile/:path*", "/sell/:path*"] };
