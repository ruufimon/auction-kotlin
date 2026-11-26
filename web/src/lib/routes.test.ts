import { describe, expect, it } from "vitest";
import { gateDecision, homeFor, isNavActive, needsSession } from "./routes";
import type { Account, AccountState } from "@/lib/api/types";

const account = (accountState: AccountState, sellerEnabled = false) => ({ accountState, sellerEnabled }) as Account;

describe("homeFor", () => {
  it("sends each account state to its own page", () => {
    expect(homeFor("PENDING_VERIFICATION")).toBe("/verify-email");
    expect(homeFor("PENDING_CREDIT_ELIGIBILITY")).toBe("/onboarding/eligibility");
    expect(homeFor("ACTIVE")).toBe("/marketplace");
    expect(homeFor("SUSPENDED")).toBe("/signed-out?reason=ACCOUNT_SUSPENDED");
  });
});

describe("gateDecision", () => {
  it("renders any non-suspended state when the page allows all states", () => {
    expect(gateDecision(account("PENDING_VERIFICATION"), {})).toEqual({ kind: "render" });
  });

  it("sends a state the page does not allow to that state's home", () => {
    expect(gateDecision(account("PENDING_CREDIT_ELIGIBILITY"), { allow: ["ACTIVE"] }))
      .toEqual({ kind: "redirect", to: "/onboarding/eligibility" });
  });

  it("lets the page override where a disallowed state goes", () => {
    expect(gateDecision(account("ACTIVE"), { allow: ["PENDING_CREDIT_ELIGIBILITY"], redirect: { ACTIVE: "/profile?activated=1" } }))
      .toEqual({ kind: "redirect", to: "/profile?activated=1" });
  });

  it("renders a suspended account on a page that allows every state, like the read-only profile", () => {
    expect(gateDecision(account("SUSPENDED"), {})).toEqual({ kind: "render" });
  });

  it("signs a suspended account out instead of redirecting", () => {
    expect(gateDecision(account("SUSPENDED"), { allow: ["ACTIVE"] })).toEqual({ kind: "signOut" });
  });

  it("keeps seller-only pages from active buyers and sends them to seller setup", () => {
    expect(gateDecision(account("ACTIVE", false), { allow: ["ACTIVE"], seller: true }))
      .toEqual({ kind: "redirect", to: "/profile/seller" });
  });

  it("applies the account-state rule before the seller rule", () => {
    expect(gateDecision(account("PENDING_VERIFICATION", false), { allow: ["ACTIVE"], seller: true }))
      .toEqual({ kind: "redirect", to: "/verify-email" });
  });

  it("renders seller-only pages for an active seller", () => {
    expect(gateDecision(account("ACTIVE", true), { allow: ["ACTIVE"], seller: true })).toEqual({ kind: "render" });
  });
});

describe("needsSession", () => {
  it.each(["/welcome", "/verify-email", "/onboarding/eligibility", "/profile", "/profile/seller", "/sell", "/sell/listings/new"])(
    "protects %s", (path) => expect(needsSession(path)).toBe(true));

  it.each(["/", "/marketplace", "/signed-out", "/selling-tips", "/profiles", "/auth/login"])(
    "leaves %s public", (path) => expect(needsSession(path)).toBe(false));
});

describe("isNavActive", () => {
  it("marks a section active on its own page and below it", () => {
    expect(isNavActive("/sell", "/sell")).toBe(true);
    expect(isNavActive("/sell", "/sell/listings")).toBe(true);
    expect(isNavActive("/marketplace", "/marketplace")).toBe(true);
  });

  it("does not match a different path that shares the prefix", () => {
    expect(isNavActive("/sell", "/selling-tips")).toBe(false);
    expect(isNavActive("/marketplace", "/")).toBe(false);
  });
});
