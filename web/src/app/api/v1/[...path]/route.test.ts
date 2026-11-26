import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { NextRequest } from "next/server";

vi.mock("server-only", () => ({}));
const refreshTokens = vi.fn();
vi.mock("@/lib/server/oidc", async (importOriginal) => ({
  ...(await importOriginal<typeof import("@/lib/server/oidc")>()),
  refreshTokens: (...a: unknown[]) => refreshTokens(...a),
}));

const { POST, GET } = await import("./route");
const { ACCESS_COOKIE, REFRESH_COOKIE } = await import("@/lib/server/session");
const { sealer } = await import("@/lib/server/seal");
const { config } = await import("@/lib/server/config");

let counter = 0;
const apiFetch = vi.fn<typeof fetch>();

/** A browser request to the proxy carrying a session whose access token the API will call expired. */
function browserRequest(method: string, path: string, headers: Record<string, string> = {}, body?: string) {
  const s = sealer(config().sessionSecret);
  const now = Date.now();
  const cookie = [
    `${ACCESS_COOKIE}=${s.seal({ t: "old-at", e: now + 120_000 })}`,
    `${REFRESH_COOKIE}=${s.seal({ t: `rt-route-${counter}`, e: now + 1_800_000 })}`,
  ].join("; ");
  return new NextRequest(new URL(`/api/v1/${path}`, config().appOrigin), {
    method, body, headers: { cookie, origin: config().appOrigin, ...headers },
  });
}

const params = (path: string) => ({ params: Promise.resolve({ path: path.split("/") }) });
const json = (status: number, body: unknown) => new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });
const bearer = (call: Parameters<typeof fetch>) => (call[1]?.headers as Record<string, string>).Authorization;

describe("API proxy", () => {
  beforeEach(() => {
    counter++;
    refreshTokens.mockReset();
    apiFetch.mockReset();
    vi.stubGlobal("fetch", apiFetch);
  });
  afterEach(() => vi.unstubAllGlobals());

  it("forwards the Idempotency-Key so retried commands are not applied twice", async () => {
    apiFetch.mockResolvedValue(json(201, { ok: true }));
    const req = browserRequest("POST", "auctions/a1/bids", { "content-type": "application/json", "idempotency-key": "key-1" }, '{"amount":1500}');
    const res = await POST(req, params("auctions/a1/bids"));
    expect(res.status).toBe(201);
    const headers = apiFetch.mock.calls[0][1]?.headers as Record<string, string>;
    expect(headers["Idempotency-Key"]).toBe("key-1");
    expect(headers.Authorization).toBe("Bearer old-at");
  });

  it("refreshes once when concurrent requests get TOKEN_EXPIRED, then retries each with the new token", async () => {
    let release!: () => void;
    const gate = new Promise<void>((r) => { release = r; });
    refreshTokens.mockImplementation(async () => {
      await gate;
      const now = Date.now();
      return { accessToken: "new-at", accessExpiresAt: now + 300_000, refreshToken: "new-rt", refreshExpiresAt: now + 1_800_000 };
    });
    apiFetch.mockImplementation(async (...call) =>
      bearer(call) === "Bearer old-at" ? json(401, { code: "TOKEN_EXPIRED", message: "", details: [] }) : json(200, { userId: "usr_1" }));

    const pending = [1, 2, 3].map(() => GET(browserRequest("GET", "me"), params("me")));
    await vi.waitFor(() => expect(refreshTokens).toHaveBeenCalled());
    release();
    const responses = await Promise.all(pending);

    expect(refreshTokens).toHaveBeenCalledTimes(1);
    for (const res of responses) {
      expect(res.status).toBe(200);
      expect(res.headers.getSetCookie().some((c) => c.startsWith(`${ACCESS_COOKIE}=`))).toBe(true);
    }
    expect(apiFetch.mock.calls.filter((c) => bearer(c) === "Bearer new-at")).toHaveLength(3);
  });
});
