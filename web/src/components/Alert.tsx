import type { ReactNode } from "react";

export function Alert({ kind = "info", title, children }: { kind?: "info" | "error" | "success" | "warning"; title?: string; children?: ReactNode }) {
  return (
    <div className={`alert alert-${kind}`} role={kind === "error" ? "alert" : "status"}>
      <div className="stack" style={{ gap: 2 }}>
        {title && <strong>{title}</strong>}
        {children && <div>{children}</div>}
      </div>
    </div>
  );
}
