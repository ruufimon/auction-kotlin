import "server-only";
import { NextResponse, type NextRequest } from "next/server";
import { config } from "./config";
import { pkceChallenge, randomToken } from "./oidc";
import { oidc, safeReturnTo, writeLoginState } from "./session";

/** Starts Authorization Code + PKCE at Keycloak; `register` opens the registration page instead of login. */
export function startLogin(req: NextRequest, mode: "login" | "register"): NextResponse {
  const c = config();
  const ep = oidc();
  const state = randomToken();
  const verifier = randomToken(48);
  const nonce = randomToken();
  const returnTo = safeReturnTo(req.nextUrl.searchParams.get("returnTo"));
  const url = new URL(mode === "register" ? ep.registration : ep.authorization);
  url.search = new URLSearchParams({
    client_id: c.oidcClientId,
    response_type: "code",
    scope: "openid email profile",
    redirect_uri: `${c.appOrigin}/auth/callback`,
    state,
    nonce,
    code_challenge: pkceChallenge(verifier),
    code_challenge_method: "S256",
  }).toString();
  // `kc_idp_hint=google` skips the provider page when the app offers its own Google button.
  const idp = req.nextUrl.searchParams.get("idp");
  if (idp && /^[a-z0-9-]{1,40}$/.test(idp)) url.searchParams.set("kc_idp_hint", idp);
  const res = NextResponse.redirect(url);
  writeLoginState(res, { state, verifier, nonce, returnTo });
  return res;
}
