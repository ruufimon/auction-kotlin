import type { Account, ApiErrorBody, ProfileUpdate } from "./types";
import { messageFor } from "./errors";

export class ApiError extends Error {
  constructor(readonly status: number, readonly body: ApiErrorBody) {
    super(body.message || body.code);
  }
  get code() { return this.body.code; }
  get details() { return this.body.details ?? []; }
  get userMessage() { return messageFor(this.body.code); }
}

/** Codes that mean the session is gone: the caller is sent to sign-in. */
export const SESSION_CODES = new Set(["SESSION_MISSING", "SESSION_EXPIRED", "TOKEN_MISSING", "TOKEN_INVALID", "TOKEN_EXPIRED", "TOKEN_WRONG_AUDIENCE"]);

/** Options for a single call. */
export interface SendOptions {
  body?: unknown;
  /**
   * For commands that create financial or auction state (requirement §6, BID-012): make the key
   * once per user action with idempotencyKey() from ./money and reuse it when retrying that action,
   * so the API returns the first result instead of applying the command twice.
   */
  idempotencyKey?: string;
}

export interface ClientOptions {
  fetchImpl?: typeof fetch;
  /** Called once when the session is gone; default navigates to sign-in. */
  onSessionExpired?: (code: string) => void;
}

function defaultSessionExpired() {
  if (typeof window === "undefined") return;
  const returnTo = window.location.pathname + window.location.search;
  window.location.assign(`/auth/login?returnTo=${encodeURIComponent(returnTo)}`);
}

/**
 * Typed client for /api/v1. Calls go to this web app's same-origin proxy, which attaches and
 * refreshes the access token, so the browser never holds tokens.
 */
export function createApiClient(options: ClientOptions = {}) {
  const fetchImpl = options.fetchImpl ?? ((...args: Parameters<typeof fetch>) => fetch(...args));
  const notifySessionExpired = options.onSessionExpired ?? defaultSessionExpired;
  // Several requests in flight can all find the session gone; send the user to sign-in once.
  let sessionExpiredNotified = false;
  const onSessionExpired = (code: string) => {
    if (sessionExpiredNotified) return;
    sessionExpiredNotified = true;
    notifySessionExpired(code);
  };

  async function send<T>(method: string, path: string, { body, idempotencyKey }: SendOptions = {}): Promise<T> {
    const headers: Record<string, string> = {};
    if (idempotencyKey !== undefined) {
      if (method === "GET" || method === "HEAD") throw new TypeError(`An idempotency key has no meaning on ${method} ${path}`);
      headers["Idempotency-Key"] = idempotencyKey;
    }
    let res: Response;
    try {
      res = await fetchImpl(`/api/v1${path}`, {
        method,
        headers: { Accept: "application/json", ...(body !== undefined ? { "Content-Type": "application/json" } : {}), ...headers },
        body: body !== undefined ? JSON.stringify(body) : undefined,
        credentials: "same-origin",
        cache: "no-store",
      });
    } catch {
      throw new ApiError(0, { correlationId: null, code: "API_UNAVAILABLE", message: "Network error", details: [] });
    }
    if (res.ok) return (res.status === 204 ? undefined : await res.json()) as T;
    let errorBody: ApiErrorBody;
    try {
      const parsed = await res.json();
      errorBody = { correlationId: parsed.correlationId ?? null, code: String(parsed.code ?? "UNKNOWN"), message: String(parsed.message ?? ""), details: parsed.details ?? [] };
    } catch {
      errorBody = { correlationId: null, code: res.status >= 500 ? "INTERNAL_ERROR" : "UNKNOWN", message: res.statusText, details: [] };
    }
    const error = new ApiError(res.status, errorBody);
    if (res.status === 401 && SESSION_CODES.has(error.code)) onSessionExpired(error.code);
    throw error;
  }

  return {
    /** Bootstraps the account on first call (GET /api/v1/me). */
    /** Any /api/v1 call; feature modules (catalog, bidding, settlement) build their typed methods on it. */
    send,
    me: () => send<Account>("GET", "/me"),
    getProfile: (userId: string) => send<Account>("GET", `/profile/${encodeURIComponent(userId)}`),
    updateProfile: (userId: string, update: ProfileUpdate) => send<Account>("PUT", `/profile/${encodeURIComponent(userId)}`, { body: update }),
    enableSeller: (userId: string) => send<Account>("POST", `/profile/${encodeURIComponent(userId)}/enable-seller`),
  };
}

export type ApiClient = ReturnType<typeof createApiClient>;
export const api = createApiClient();
