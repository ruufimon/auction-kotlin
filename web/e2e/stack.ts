import { expect, type Page } from "@playwright/test";

export const KEYCLOAK = process.env.KEYCLOAK_URL ?? "http://localhost:8180";
export const MAILPIT = process.env.MAILPIT_URL ?? "http://localhost:8025";
export const API = process.env.API_URL ?? "http://localhost:8080";
export const OPERATOR_TOKEN = process.env.INTERNAL_API_TOKEN ?? "dev-operator-token";

export const uniqueEmail = (tag: string) => `${tag}-${Date.now()}-${Math.floor(Math.random() * 1e4)}@e2e.gavel.local`;
export const PASSWORD = "e2e-password-123456";

/** Fills Keycloak's registration form (email as username, realm password policy). */
export async function registerAtKeycloak(page: Page, email: string, first = "Mina", last = "Tester") {
  await page.goto("/auth/register");
  await expect(page).toHaveURL(new RegExp(`^${KEYCLOAK}/realms/gavel/`));
  await page.locator("#email").fill(email);
  await page.locator("#firstName").fill(first);
  await page.locator("#lastName").fill(last);
  await page.locator("#password").fill(PASSWORD);
  await page.locator("#password-confirm").fill(PASSWORD);
  await page.locator("input[type=submit], button[type=submit]").first().click();
}

export async function signInAtKeycloak(page: Page, email: string, password: string) {
  await expect(page).toHaveURL(new RegExp(`^${KEYCLOAK}/realms/gavel/`));
  await page.locator("#username").fill(email);
  await page.locator("#password").fill(password);
  await page.locator("input[type=submit], button[type=submit]").first().click();
}

/** Waits for Keycloak's verification email in Mailpit and returns the link. */
export async function verificationLink(email: string): Promise<string> {
  for (let i = 0; i < 30; i++) {
    const search = await fetch(`${MAILPIT}/api/v1/search?query=${encodeURIComponent(`to:"${email}"`)}`).then((r) => r.json());
    const id = search.messages?.[0]?.ID;
    if (id) {
      const message = await fetch(`${MAILPIT}/api/v1/message/${id}`).then((r) => r.json());
      const link = String(message.Text).match(/https?:\/\/\S+action-token\S+/)?.[0];
      if (link) return link.replace(/[)>\]]+$/, "");
    }
    await new Promise((r) => setTimeout(r, 500));
  }
  throw new Error(`no verification email for ${email}`);
}

export async function operator(action: "suspend" | "reactivate", userId: string) {
  const res = await fetch(`${API}/internal/accounts/${userId}/${action}`, {
    method: "POST",
    headers: { "Content-Type": "application/json", "X-Internal-Token": OPERATOR_TOKEN },
    body: action === "suspend" ? JSON.stringify({ reason: "e2e: policy check" }) : "{}",
  });
  if (!res.ok && res.status !== 409) throw new Error(`${action} failed: ${res.status} ${await res.text()}`);
}

/** Registers, verifies by email and lands on the eligibility check. */
export async function registerAndVerify(page: Page, email: string) {
  await registerAtKeycloak(page, email);
  const link = await verificationLink(email);
  await page.goto(link);
  // Keycloak may ask to confirm the action, then continues to the app.
  const proceed = page.getByRole("link", { name: /click here to proceed|back to application/i });
  if (await proceed.isVisible({ timeout: 3_000 }).catch(() => false)) await proceed.click();
  await expect(page).toHaveURL(/\/onboarding\/eligibility$/, { timeout: 20_000 });
}
