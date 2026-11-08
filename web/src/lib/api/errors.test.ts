import { describe, expect, it } from "vitest";
import { fieldErrors, GENERIC_ERROR, messageFor, reasonLabel } from "./errors";

describe("error mapping", () => {
  it("maps stable codes to messages", () => {
    expect(messageFor("ACCOUNT_SUSPENDED")).toMatch(/suspended/);
    expect(messageFor("RATE_LIMITED")).toMatch(/Too many requests/);
  });

  it("falls back to a generic message for unknown or missing codes", () => {
    expect(messageFor("SOMETHING_NEW")).toBe(GENERIC_ERROR);
    expect(messageFor(undefined)).toBe(GENERIC_ERROR);
  });

  it("labels eligibility reasons and keeps unknown reasons neutral", () => {
    expect(reasonLabel("BELOW_MINIMUM_SCORE")).toMatch(/minimum score/);
    expect(reasonLabel("NEW_REASON")).toBe("Not met");
  });

  it("turns details into one message per field", () => {
    expect(fieldErrors([
      { field: "displayName", code: "TOO_LONG" },
      { field: "displayName", code: "REQUIRED" },
      { field: "country", code: "INVALID" },
      { field: "originAddress.city", code: "REQUIRED" },
      { code: "PRECONDITION", message: "no field" },
    ])).toEqual({
      displayName: "Too long.",
      country: "Use a two-letter country code, for example US.",
      "originAddress.city": "Required.",
    });
  });
});
