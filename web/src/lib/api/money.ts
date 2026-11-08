/**
 * Money helpers. Amounts are integer minor units end to end; parsing and formatting work on
 * digit strings and BigInt, never on floating point.
 */

export function minorDigits(currency: string): number {
  return new Intl.NumberFormat("en", { style: "currency", currency }).resolvedOptions().maximumFractionDigits ?? 2;
}

export type ParseResult = { ok: true; minor: number } | { ok: false; code: "REQUIRED" | "INVALID" | "NEGATIVE" | "TOO_LARGE" };

/**
 * Parses an amount typed in whole currency units ("12,500" or "12500") into minor units.
 * Decimals are refused: the forms ask for whole units.
 */
export function parseWholeUnits(input: string, currency: string): ParseResult {
  const text = input.trim().replace(/[\s,_]/g, "");
  if (text === "") return { ok: false, code: "REQUIRED" };
  if (text.startsWith("-")) return { ok: false, code: "NEGATIVE" };
  if (!/^\d+$/.test(text)) return { ok: false, code: "INVALID" };
  const minor = BigInt(text) * 10n ** BigInt(minorDigits(currency));
  if (minor > BigInt(Number.MAX_SAFE_INTEGER)) return { ok: false, code: "TOO_LARGE" };
  return { ok: true, minor: Number(minor) };
}

/** Formats integer minor units, e.g. formatMinor(123456, "USD") → "$1,234.56". */
export function formatMinor(minor: number | bigint, currency: string, locale?: string): string {
  if (typeof minor === "number" && !Number.isSafeInteger(minor)) throw new RangeError("minor units must be a safe integer");
  const value = BigInt(minor);
  const digits = minorDigits(currency);
  const negative = value < 0n;
  const abs = negative ? -value : value;
  const scale = 10n ** BigInt(digits);
  const decimal = digits === 0 ? `${abs}` : `${abs / scale}.${(abs % scale).toString().padStart(digits, "0")}`;
  const fmt = new Intl.NumberFormat(locale, { style: "currency", currency, minimumFractionDigits: digits, maximumFractionDigits: digits });
  // Intl formats decimal strings exactly (no float conversion).
  return fmt.format(`${negative ? "-" : ""}${decimal}` as unknown as number);
}

/** A fresh key for financial and auction commands (Idempotency-Key header). */
export function idempotencyKey(): string {
  return globalThis.crypto.randomUUID();
}
