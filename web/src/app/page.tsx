import Link from "next/link";
import { cookies } from "next/headers";
import { sessionUser } from "@/lib/server/session";
import { Steps } from "@/components/Steps";

export default async function HomePage() {
  const user = sessionUser(await cookies());
  return (
    <div className="stack-lg">
      <section className="hero">
        <h1>Bid with confidence. Sell without a second account.</h1>
        <p className="lead">
          Gavel is an online auction house. One account lets you bid and, once you add a seller profile, list your own items.
        </p>
        <div className="row">
          {user ? (
            <>
              <Link className="btn btn-primary" href="/welcome">Continue</Link>
              <Link className="btn btn-secondary" href="/profile">Your account</Link>
            </>
          ) : (
            <>
              <a className="btn btn-primary" href="/auth/register">Create account</a>
              <a className="btn btn-secondary" href="/auth/login">Sign in</a>
            </>
          )}
        </div>
      </section>
      <section className="card" aria-labelledby="how">
        <h2 id="how">How joining works</h2>
        <Steps current={-1} />
        <p className="muted">
          Register with Google or an email and password, verify your email, then answer three questions. Because a winning bid is a
          binding commitment, the eligibility check runs before your account can bid.
        </p>
      </section>
    </div>
  );
}
