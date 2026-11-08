import { describe, expect, it, vi } from "vitest";
import { ApiError, createApiClient } from "./client";

const json = (status: number, body: unknown) => new Response(JSON.stringify(body), { status, headers: { "Content-Type": "application/json" } });

describe("api client", () => {
  it("calls the same-origin proxy and returns the body", async () => {
    const fetchImpl = vi.fn(async () => json(200, { userId: "usr_1" }));
    const client = createApiClient({ fetchImpl });
    expect(await client.me()).toEqual({ userId: "usr_1" });
    expect(fetchImpl).toHaveBeenCalledWith("/api/v1/me", expect.objectContaining({ method: "GET", credentials: "same-origin" }));
  });

  it("sends JSON bodies for updates", async () => {
    const fetchImpl = vi.fn(async (_url: RequestInfo | URL, _init?: RequestInit) => json(200, {}));
    await createApiClient({ fetchImpl }).updateProfile("usr_1", { version: 3, displayName: "Mina" });
    const init = fetchImpl.mock.calls[0][1]!;
    expect(init.method).toBe("PUT");
    expect(JSON.parse(String(init.body))).toEqual({ version: 3, displayName: "Mina" });
  });

  it("raises ApiError with the stable code and details", async () => {
    const fetchImpl = vi.fn(async () => json(422, { correlationId: "c1", code: "SELLER_PROFILE_INCOMPLETE", message: "x", details: [{ field: "legalName", code: "REQUIRED" }] }));
    const error = await createApiClient({ fetchImpl }).enableSeller("usr_1").catch((e) => e);
    expect(error).toBeInstanceOf(ApiError);
    expect(error.code).toBe("SELLER_PROFILE_INCOMPLETE");
    expect(error.details).toEqual([{ field: "legalName", code: "REQUIRED" }]);
    expect(error.userMessage).toMatch(/seller details/);
  });

  it("sends the user to sign-in once the session is gone", async () => {
    const onSessionExpired = vi.fn();
    const fetchImpl = vi.fn(async () => json(401, { code: "SESSION_EXPIRED", message: "", details: [] }));
    await expect(createApiClient({ fetchImpl, onSessionExpired }).me()).rejects.toBeInstanceOf(ApiError);
    expect(onSessionExpired).toHaveBeenCalledWith("SESSION_EXPIRED");
  });

  it("does not treat a 403 account-state refusal as a lost session", async () => {
    const onSessionExpired = vi.fn();
    const fetchImpl = vi.fn(async () => json(403, { code: "ACCOUNT_SUSPENDED", message: "", details: [] }));
    const error = await createApiClient({ fetchImpl, onSessionExpired }).enableSeller("usr_1").catch((e) => e);
    expect(error.code).toBe("ACCOUNT_SUSPENDED");
    expect(onSessionExpired).not.toHaveBeenCalled();
  });

  it("maps network failures and non-JSON errors to stable codes", async () => {
    const down = createApiClient({ fetchImpl: vi.fn(async () => { throw new TypeError("fetch failed"); }) });
    expect((await down.me().catch((e) => e)).code).toBe("API_UNAVAILABLE");
    const html = createApiClient({ fetchImpl: vi.fn(async () => new Response("<h1>Bad gateway</h1>", { status: 502 })) });
    expect((await html.me().catch((e) => e)).code).toBe("INTERNAL_ERROR");
  });
});
