"use client";

/** Last-resort error page for failures in the root layout itself; it replaces the whole document. */
export default function GlobalError({ error, reset }: { error: Error & { digest?: string }; reset: () => void }) {
  return (
    <html lang="en">
      <body style={{ margin: 0, fontFamily: "Inter, system-ui, sans-serif", background: "#F6F7F9", color: "#16202A" }}>
        <main style={{ maxWidth: 520, margin: "0 auto", padding: "72px 16px", display: "flex", flexDirection: "column", gap: 16 }}>
          <h1 style={{ margin: 0, fontSize: 28 }}>Gavel is having trouble</h1>
          <p style={{ margin: 0, color: "#5B6673", lineHeight: 1.5 }}>The page could not be shown. Try again in a moment.</p>
          {error.digest && <p style={{ margin: 0, fontSize: 13, color: "#5B6673" }}>Reference <code>{error.digest}</code></p>}
          <div style={{ display: "flex", gap: 12 }}>
            <button onClick={reset} style={{ font: "inherit", fontWeight: 600, padding: "11px 16px", borderRadius: 8, border: 0, background: "#1F5FBF", color: "#fff", cursor: "pointer" }}>Try again</button>
            <a href="/" style={{ font: "inherit", fontWeight: 600, padding: "11px 16px", borderRadius: 8, border: "1px solid #D9DEE5", color: "#16202A", textDecoration: "none" }}>Go home</a>
          </div>
        </main>
      </body>
    </html>
  );
}
