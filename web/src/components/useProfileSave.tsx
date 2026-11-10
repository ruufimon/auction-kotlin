"use client";
import type { ReactElement } from "react";
import { useState } from "react";
import { api, ApiError } from "@/lib/api/client";
import { fieldErrors, messageFor } from "@/lib/api/errors";
import type { Account, ProfileUpdate } from "@/lib/api/types";
import { useAccount } from "./AccountProvider";
import { signOut } from "./signOut";

export type SaveStatus = { kind: "idle" } | { kind: "saving" } | { kind: "saved" } | { kind: "error"; message: string };

/**
 * PUT /api/v1/profile/{userId} with optimistic versioning. Field errors are returned for inline
 * display; a version conflict reloads the account; suspension signs the user out.
 */
export function useProfileSave() {
  const { replace, reload } = useAccount();
  const [status, setStatus] = useState<SaveStatus>({ kind: "idle" });
  const [errors, setErrors] = useState<Record<string, string>>({});

  async function save(account: Account, update: Omit<ProfileUpdate, "version">): Promise<Account | null> {
    setStatus({ kind: "saving" });
    setErrors({});
    try {
      const updated = await api.updateProfile(account.userId, { ...update, version: account.version });
      replace(updated);
      setStatus({ kind: "saved" });
      return updated;
    } catch (err) {
      const e = err instanceof ApiError ? err : null;
      if (e?.code === "ACCOUNT_SUSPENDED") { signOut("ACCOUNT_SUSPENDED"); return null; }
      if (e?.code === "VALIDATION_FAILED") setErrors(fieldErrors(e.details));
      if (e?.code === "VERSION_CONFLICT") await reload();
      setStatus({ kind: "error", message: messageFor(e?.code) });
      return null;
    }
  }

  return { save, status, errors, setErrors, reset: () => setStatus({ kind: "idle" }) };
}

export function SaveFeedback({ status }: { status: SaveStatus }): ReactElement | null {
  if (status.kind === "saved") return <span className="small" role="status" style={{ color: "var(--success)" }}>Saved</span>;
  if (status.kind === "error") return <span className="small" role="alert" style={{ color: "var(--danger)" }}>{status.message}</span>;
  return null;
}
