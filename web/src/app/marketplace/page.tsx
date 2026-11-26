import type { Metadata } from "next";

export const metadata: Metadata = { title: "Browse" };

/** Placeholder until marketplace discovery (M3, issue #33). */
export default function MarketplacePage() {
  return (
    <div className="narrow">
      <div className="page-head">
        <h1>Browse auctions</h1>
        <p className="lead">Auctions appear here once the marketplace launches. Your account is ready for it.</p>
      </div>
      <div className="card">
        <p className="muted">Nothing to browse yet.</p>
      </div>
    </div>
  );
}
