import { NextResponse, type NextRequest } from "next/server";
import { config } from "@/lib/server/config";
import { decodeClaims, exchangeCode } from "@/lib/server/oidc";
import { clearLoginState, oidc, readLoginState, writeTokens } from "@/lib/server/session";

export const dynamic = "force-dynamic";

function fail(reason: string) {
  const res = NextResponse.redirect(new URL(`/signed-out?reason=${reason}`, config().appOrigin));
  clearLoginState(res);
  return res;
}

export async function GET(req: NextRequest) {
  const params = req.nextUrl.searchParams;
  const login = readLoginState(req);
  // The user cancelled at the provider, or the provider refused (e.g. account disabled).
  if (params.get("error")) return fail(params.get("error") === "access_denied" ? "SIGN_IN_CANCELLED" : "SIGN_IN_FAILED");
  const code = params.get("code");
  if (!login || !code || params.get("state") !== login.state) return fail("SIGN_IN_EXPIRED");
  const c = config();
  try {
    const { tokens, idToken } = await exchangeCode(oidc(), c.oidcClientId, code, `${c.appOrigin}/auth/callback`, login.verifier);
    if (idToken && decodeClaims(idToken).nonce !== login.nonce) return fail("SIGN_IN_FAILED");
    const res = NextResponse.redirect(new URL(`/welcome?returnTo=${encodeURIComponent(login.returnTo)}`, c.appOrigin));
    writeTokens(res, tokens);
    clearLoginState(res);
    return res;
  } catch {
    return fail("SIGN_IN_FAILED");
  }
}
