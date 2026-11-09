import { expect, test } from "@playwright/test";
import { KEYCLOAK, operator, registerAndVerify, registerAtKeycloak, signInAtKeycloak, uniqueEmail } from "./stack";

test.describe("onboarding", () => {
  test("protected pages send anonymous visitors to Keycloak sign-in", async ({ page }) => {
    for (const path of ["/onboarding/eligibility", "/sell"]) {
      await page.goto(path);
      await expect(page, path).toHaveURL(new RegExp(`^${KEYCLOAK}/realms/gavel/protocol/openid-connect/auth`));
    }
    await page.goto("/profile");
    await expect(page).toHaveURL(new RegExp(`^${KEYCLOAK}/realms/gavel/protocol/openid-connect/auth`));
    const url = new URL(page.url());
    expect(url.searchParams.get("code_challenge_method")).toBe("S256");
    expect(url.searchParams.get("client_id")).toBe("gavel-web");
  });

  test("a new email/password user must verify before reaching the app", async ({ page }) => {
    await registerAtKeycloak(page, uniqueEmail("unverified"));
    // Keycloak holds the user on its verify-email page; no app session exists yet.
    await expect(page).toHaveURL(new RegExp(`^${KEYCLOAK}/`));
    await expect(page.getByText(/verify your email/i).first()).toBeVisible();
  });

  test("register → verify → eligibility → profile → seller → sign out", async ({ page }) => {
    const email = uniqueEmail("journey");
    await registerAndVerify(page, email);

    await expect(page.getByRole("heading", { name: "A quick eligibility check" })).toBeVisible();
    await page.getByLabel("Your age").fill("35");
    await page.getByLabel("Annual income").fill("60,000");
    await page.getByLabel("Net wealth").fill("30000");
    await page.getByRole("button", { name: /Submit and activate/ }).click();

    await expect(page).toHaveURL(/\/profile\?activated=1$/);
    await expect(page.getByText("You’re in")).toBeVisible();
    await expect(page.getByLabel("Account status").getByText("ACTIVE", { exact: true })).toBeVisible();
    await expect(page.getByText("MEETS_POLICY")).toBeVisible();
    // Raw inputs are never shown back.
    await expect(page.getByText("60,000")).toHaveCount(0);

    await page.getByLabel("Display name").fill("Mina the Tester");
    await page.getByRole("button", { name: "Save changes" }).click();
    await expect(page.getByText("Saved")).toBeVisible();

    await page.reload();
    await expect(page.getByLabel("Display name")).toHaveValue("Mina the Tester");

    // The seller area is seller-only: an active buyer is sent to seller setup.
    await page.getByRole("navigation", { name: "Primary" }).getByRole("link", { name: "Sell" }).click();
    await expect(page).toHaveURL(/\/profile\/seller$/);
    await page.goto("/profile");

    await page.getByRole("link", { name: "Set up seller profile" }).click();
    await expect(page.getByRole("heading", { name: "Start selling on Gavel" })).toBeVisible();
    const enable = page.getByRole("button", { name: /Enable selling/ });
    await expect(enable).toBeDisabled();
    await page.getByLabel("Legal name").fill("Mina Tester");
    await page.getByLabel("Store name").fill("Mina's Finds");
    await page.getByLabel("Contact phone").fill("+66 81 234 5678");
    await page.getByLabel("Payout account").fill("TH-0001-2345");
    await page.getByLabel("Street address").fill("1 Market Road");
    await page.getByLabel("City").fill("Bangkok");
    await page.getByLabel("State / region").fill("Bangkok");
    await page.getByLabel("Postal code").fill("10200");
    await page.getByLabel("Country").selectOption("TH");
    await expect(enable).toBeEnabled();
    await enable.click();

    await expect(page).toHaveURL(/\/profile\?seller=enabled$/);
    await expect(page.getByText("Selling enabled", { exact: true })).toBeVisible();

    await page.getByRole("link", { name: "Go to your seller area" }).click();
    await expect(page).toHaveURL(/\/sell$/);
    await expect(page.getByRole("heading", { name: "Your seller area" })).toBeVisible();
    await expect(page.getByRole("navigation", { name: "Primary" }).getByRole("link", { name: "Sell" })).toHaveAttribute("aria-current", "page");

    await page.getByRole("button", { name: "Sign out" }).first().click();
    await expect(page).toHaveURL(/\/signed-out\?reason=SIGNED_OUT$/);
    await expect(page.getByRole("heading", { name: "You are signed out" })).toBeVisible();
    await page.goto("/profile");
    await expect(page).toHaveURL(new RegExp(`^${KEYCLOAK}/`));
  });

  test("a failed eligibility check shows the stable reason and the retry date", async ({ page }) => {
    await registerAndVerify(page, uniqueEmail("ineligible"));
    await page.getByLabel("Your age").fill("18");
    await page.getByLabel("Annual income").fill("0");
    await page.getByLabel("Net wealth").fill("0");
    await page.getByRole("button", { name: /Submit and activate/ }).click();
    await expect(page.getByText("The eligibility check did not pass")).toBeVisible();
    await expect(page.getByText("BELOW_MINIMUM_SCORE").first()).toBeVisible();
    // Inputs are cleared after submission.
    await expect(page.getByLabel("Annual income")).toHaveValue("");

    await page.getByLabel("Your age").fill("40");
    await page.getByLabel("Annual income").fill("90000");
    await page.getByLabel("Net wealth").fill("90000");
    await page.getByRole("button", { name: /Submit and activate/ }).click();
    await expect(page.getByText(/You can retry the check from/)).toBeVisible();

    await page.goto("/profile");
    await expect(page.getByText("The eligibility check did not pass")).toBeVisible();
    await expect(page.getByText("NOT_ELIGIBLE")).toBeVisible();
  });

  test("client-side validation catches bad inputs before submitting", async ({ page }) => {
    await registerAndVerify(page, uniqueEmail("validation"));
    await page.getByLabel("Your age").fill("17");
    await page.getByLabel("Annual income").fill("12.50");
    await page.getByLabel("Net wealth").fill("-5");
    await page.getByRole("button", { name: /Submit and activate/ }).click();
    await expect(page.getByText("You must be at least 18.")).toBeVisible();
    await expect(page.getByText("Enter a whole number without decimals.")).toBeVisible();
    await expect(page.getByText(/Net wealth cannot be negative/).first()).toBeVisible();
  });

  test("a suspended user is signed out with an explanation", async ({ page }) => {
    const email = uniqueEmail("suspended");
    await registerAndVerify(page, email);
    const me = await page.request.get("/api/v1/me").then((r) => r.json());
    await operator("suspend", me.userId);

    await page.goto("/profile/seller");
    await expect(page).toHaveURL(/\/signed-out\?reason=ACCOUNT_SUSPENDED$/);
    await expect(page.getByRole("heading", { name: "Your account is suspended" })).toBeVisible();

    // Signing in again does not get past the suspension.
    await page.goto("/auth/login");
    await signInAtKeycloak(page, email, "e2e-password-123456");
    await expect(page).toHaveURL(/\/signed-out\?reason=ACCOUNT_SUSPENDED$/);
  });

  test("the app's API proxy refuses calls without a session", async ({ request }) => {
    const res = await request.get("/api/v1/me");
    expect(res.status()).toBe(401);
    expect((await res.json()).code).toBe("SESSION_MISSING");
  });

  test("@phone onboarding pages fit a phone screen without horizontal scroll", async ({ page }) => {
    await registerAndVerify(page, uniqueEmail("phone"));
    for (const path of ["/onboarding/eligibility", "/profile", "/profile/seller", "/"]) {
      await page.goto(path);
      await page.waitForLoadState("networkidle");
      const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth);
      expect(overflow, `${path} scrolls horizontally`).toBeLessThanOrEqual(0);
    }
  });
});
