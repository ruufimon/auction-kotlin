import { defineConfig, devices } from "@playwright/test";

/**
 * Browser tests of the onboarding journeys against the real local stack: Keycloak, Mailpit,
 * PostgreSQL and the API (deploy/keycloak/README.md), plus this web app at WEB_URL.
 */
export default defineConfig({
  testDir: "e2e",
  timeout: 60_000,
  fullyParallel: false,
  workers: 1,
  reporter: [["list"]],
  use: {
    baseURL: process.env.WEB_URL ?? "http://localhost:8787",
    trace: "retain-on-failure",
    screenshot: "only-on-failure",
  },
  projects: [
    { name: "desktop", use: { ...devices["Desktop Chrome"] }, grepInvert: /@phone/ },
    { name: "phone", use: { ...devices["Pixel 7"] }, grep: /@phone/ },
  ],
});
