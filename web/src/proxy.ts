import { NextResponse, type NextRequest } from "next/server";

/** Pages that need a session. Account-state rules are applied in the page by AccountGate. */
const PROTECTED = ["/welcome", "/verify-email", "/onboarding", "/profile"];

/** Sends visitors without a session cookie to sign-in, keeping where they were going (issue #27). */
export function proxy(req: NextRequest) {
  const { pathname, search } = req.nextUrl;
  if (!PROTECTED.some((p) => pathname === p || pathname.startsWith(`${p}/`))) return NextResponse.next();
  if (req.cookies.get("gavel_rt")?.value) return NextResponse.next();
  const login = new URL("/auth/login", req.url);
  login.searchParams.set("returnTo", pathname + search);
  return NextResponse.redirect(login);
}

export const config = { matcher: ["/welcome", "/verify-email", "/onboarding/:path*", "/profile/:path*", "/profile"] };
