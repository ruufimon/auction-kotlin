import type { Metadata } from "next";
import Link from "next/link";

export const metadata: Metadata = { title: "Selling" };

/** Seller area home. Listing management arrives with M3 (issue #35). */
export default function SellPage() {
  return (
    <div className="stack-lg">
      <div className="page-head">
        <h1>Your seller area</h1>
        <p className="lead">Selling is enabled on your account. Listings you create and their auctions will be managed here.</p>
      </div>
      <section className="card" aria-labelledby="listings-h">
        <h2 id="listings-h">Your listings</h2>
        <p className="muted">You have no listings yet. Creating listings opens when the catalog launches.</p>
      </section>
      <p className="small muted">Store name, payout account or origin address changed? <Link href="/profile/seller">Update your seller profile</Link>.</p>
    </div>
  );
}
