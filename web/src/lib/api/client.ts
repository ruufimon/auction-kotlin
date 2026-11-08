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
  const onSessionExpired = options.onSessionExpired ?? defaultSessionExpired;

  async function request<T>(method: string, path: string, body?: unknown, headers: Record<string, string> = {}): Promise<T> {
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
    me: () => request<Account>("GET", "/me"),
    getProfile: (userId: string) => request<Account>("GET", `/profile/${encodeURIComponent(userId)}`),
    updateProfile: (userId: string, update: ProfileUpdate) => request<Account>("PUT", `/profile/${encodeURIComponent(userId)}`, update),
    enableSeller: (userId: string) => request<Account>("POST", `/profile/${encodeURIComponent(userId)}/enable-seller`),
  };
}

export type ApiClient = ReturnType<typeof createApiClient>;
export const api = createApiClient();
