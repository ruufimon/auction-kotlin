import { NextResponse, type NextRequest } from "next/server";
import { config } from "@/lib/server/config";
import { clearSession, ensureSession, writeTokens, type SessionResult } from "@/lib/server/session";

export const dynamic = "force-dynamic";

/**
 * Same-origin proxy to the Gavel API. Attaches the session's access token, refreshes it once
 * when the API answers TOKEN_EXPIRED, and clears the session when the provider refuses to
 * refresh, so the browser never handles tokens (refresh token stays in an httpOnly cookie).
 */
async function proxy(req: NextRequest, ctx: { params: Promise<{ path: string[] }> }) {
  const c = config();
  if (req.method !== "GET" && req.method !== "HEAD") {
    const origin = req.headers.get("origin");
    if (origin && origin !== c.appOrigin) return problem(403, "FORBIDDEN", "Cross-origin request refused");
  }
  const { path } = await ctx.params;
  const target = new URL(`/api/v1/${path.map(encodeURIComponent).join("/")}`, c.apiOrigin);
  target.search = req.nextUrl.search;
  const body = req.method === "GET" || req.method === "HEAD" ? undefined : await req.text();

  let session = await ensureSession(req.cookies);
  if (session.kind !== "valid") return unauthenticated(session);

  const send = (token: string) => {
    const headers: Record<string, string> = { Authorization: `Bearer ${token}`, Accept: "application/json" };
    const contentType = req.headers.get("content-type");
    if (contentType) headers["Content-Type"] = contentType;
    // Commands that create financial or auction state carry the browser's key so retries apply once.
    const idempotencyKey = req.headers.get("idempotency-key");
    if (idempotencyKey) headers["Idempotency-Key"] = idempotencyKey;
    const requestId = req.headers.get("x-request-id");
    if (requestId) headers["X-Request-Id"] = requestId;
    const forwardedFor = req.headers.get("x-forwarded-for");
    if (forwardedFor) headers["X-Forwarded-For"] = forwardedFor;
    return fetch(target, { method: req.method, headers, body, cache: "no-store", redirect: "manual" });
  };

  let upstream: Response;
  try {
    upstream = await send(session.tokens.accessToken);
    if (upstream.status === 401 && (await peekCode(upstream)) === "TOKEN_EXPIRED") {
      session = await ensureSession(req.cookies, true);
      if (session.kind !== "valid") return unauthenticated(session);
      upstream = await send(session.tokens.accessToken);
    }
  } catch {
    return problem(502, "API_UNAVAILABLE", "The Gavel API could not be reached");
  }

  const headers = new Headers({ "Cache-Control": "no-store" });
  for (const name of ["content-type", "retry-after", "x-request-id", "www-authenticate"]) {
    const v = upstream.headers.get(name);
    if (v) headers.set(name, v);
  }
  const res = new NextResponse(upstream.status === 204 ? null : await upstream.arrayBuffer(), { status: upstream.status, headers });
  if (session.refreshed) writeTokens(res, session.tokens);
  return res;
}

async function peekCode(res: Response): Promise<string | undefined> {
  try {
    return (await res.clone().json())?.code;
  } catch {
    return undefined;
  }
}

function problem(status: number, code: string, message: string) {
  return NextResponse.json({ correlationId: null, code, message, details: [] }, { status, headers: { "Cache-Control": "no-store" } });
}

function unauthenticated(session: Exclude<SessionResult, { kind: "valid" }>) {
  const code = session.kind === "none" ? "SESSION_MISSING" : session.reason;
  const status = code === "IDENTITY_PROVIDER_UNAVAILABLE" ? 503 : 401;
  const res = problem(status, code, status === 401 ? "Sign in to continue" : "Sign-in service unavailable, try again shortly");
  if (status === 401) clearSession(res);
  return res;
}

export { proxy as GET, proxy as POST, proxy as PUT, proxy as PATCH, proxy as DELETE };
