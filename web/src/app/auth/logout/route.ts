import { NextResponse, type NextRequest } from "next/server";
import { config } from "@/lib/server/config";
import { endProviderSession } from "@/lib/server/oidc";
import { clearSession, oidc, readTokens } from "@/lib/server/session";

export const dynamic = "force-dynamic";

const REASONS = new Set(["SIGNED_OUT", "ACCOUNT_SUSPENDED", "SESSION_EXPIRED"]);

/** POST only (a form or fetch from this origin), so other sites cannot sign users out with a link. */
export async function POST(req: NextRequest) {
  const c = config();
  const origin = req.headers.get("origin");
  if (origin && origin !== c.appOrigin) return NextResponse.json({ code: "FORBIDDEN" }, { status: 403 });
  const form = await req.formData().catch(() => null);
  const requested = String(form?.get("reason") ?? "SIGNED_OUT");
  const reason = REASONS.has(requested) ? requested : "SIGNED_OUT";
  const tokens = readTokens(req.cookies);
  if (tokens?.refreshToken) await endProviderSession(oidc(), c.oidcClientId, tokens.refreshToken);
  const res = NextResponse.redirect(new URL(`/signed-out?reason=${reason}`, c.appOrigin), 303);
  clearSession(res);
  return res;
}
