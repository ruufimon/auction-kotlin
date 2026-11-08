"use client";
import { createContext, useCallback, useContext, useEffect, useState, type ReactNode } from "react";
import { api, ApiError } from "@/lib/api/client";
import type { Account } from "@/lib/api/types";

type AccountContextValue =
  | { status: "loading"; account: null; error: null; reload: () => Promise<void>; replace: (a: Account) => void }
  | { status: "ready"; account: Account; error: null; reload: () => Promise<void>; replace: (a: Account) => void }
  | { status: "error"; account: null; error: ApiError; reload: () => Promise<void>; replace: (a: Account) => void };

const AccountContext = createContext<AccountContextValue | null>(null);

/** Loads the signed-in account once (GET /api/v1/me also bootstraps it) and shares it with the page. */
export function AccountProvider({ children }: { children: ReactNode }) {
  const [account, setAccount] = useState<Account | null>(null);
  const [error, setError] = useState<ApiError | null>(null);

  const reload = useCallback(async () => {
    try {
      setAccount(await api.me());
      setError(null);
    } catch (e) {
      setError(e instanceof ApiError ? e : new ApiError(0, { correlationId: null, code: "UNKNOWN", message: String(e), details: [] }));
    }
  }, []);

  useEffect(() => { void reload(); }, [reload]);

  const replace = useCallback((a: Account) => setAccount(a), []);
  const value: AccountContextValue = account
    ? { status: "ready", account, error: null, reload, replace }
    : error
      ? { status: "error", account: null, error, reload, replace }
      : { status: "loading", account: null, error: null, reload, replace };
  return <AccountContext.Provider value={value}>{children}</AccountContext.Provider>;
}

export function useAccount(): AccountContextValue {
  const ctx = useContext(AccountContext);
  if (!ctx) throw new Error("useAccount must be used inside <AccountProvider>");
  return ctx;
}
