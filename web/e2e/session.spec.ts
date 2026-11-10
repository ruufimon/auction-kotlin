import { expect, test } from "@playwright/test";
import { KEYCLOAK, registerAndVerify, uniqueEmail } from "./stack";

async function adminToken(): Promise<string> {
  const res = await fetch(`${KEYCLOAK}/realms/master/protocol/openid-connect/token`, {
    method: "POST",
    body: new URLSearchParams({ grant_type: "password", client_id: "admin-cli", username: "admin", password: "admin" }),
  });
  return (await res.json()).access_token;
}

test.describe("session", () => {
  test("an expired or missing access token is refreshed silently and the session survives reloads", async ({ page, context }) => {
    await registerAndVerify(page, uniqueEmail("refresh"));
    const before = (await context.cookies()).find((c) => c.name === "gavel_rt")!.value;
    await context.clearCookies({ name: "gavel_at" });
    await page.goto("/profile");
    await expect(page.getByRole("heading", { name: "Your account" })).toBeVisible();
    const cookies = await context.cookies();
    expect(cookies.find((c) => c.name === "gavel_at")).toBeTruthy();
    // The provider rotated the refresh token.
    expect(cookies.find((c) => c.name === "gavel_rt")!.value).not.toBe(before);
    for (const c of cookies.filter((c) => c.name.startsWith("gavel_"))) {
      expect(c.httpOnly, `${c.name} must be httpOnly`).toBe(true);
      expect(c.sameSite).toBe("Lax");
    }
  });

  test("a session revoked at the provider forces a new sign-in", async ({ page, context }) => {
    const email = uniqueEmail("revoked");
    await registerAndVerify(page, email);
    const token = await adminToken();
    const [user] = await fetch(`${KEYCLOAK}/admin/realms/gavel/users?email=${encodeURIComponent(email)}&exact=true`, { headers: { Authorization: `Bearer ${token}` } }).then((r) => r.json());
    await fetch(`${KEYCLOAK}/admin/realms/gavel/users/${user.id}/logout`, { method: "POST", headers: { Authorization: `Bearer ${token}` } });
    // Drop the access token so the next call must refresh, which the provider now refuses.
    await context.clearCookies({ name: "gavel_at" });
    await page.goto("/profile");
    await expect(page).toHaveURL(new RegExp(`^${KEYCLOAK}/realms/gavel/protocol/openid-connect/auth`), { timeout: 15_000 });
    expect((await context.cookies()).find((c) => c.name === "gavel_rt")).toBeUndefined();
  });
});
