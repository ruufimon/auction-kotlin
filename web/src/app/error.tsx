"use client";

export default function ErrorPage({ error, reset }: { error: Error & { digest?: string }; reset: () => void }) {
  return (
    <div className="narrow">
      <h1>Something went wrong</h1>
      <p className="lead">The page failed to load. You can try again.</p>
      {error.digest && <p className="muted small">Reference <span className="mono">{error.digest}</span></p>}
      <div className="row">
        <button className="btn btn-primary" onClick={reset}>Try again</button>
        <a className="btn btn-secondary" href="/">Go home</a>
      </div>
    </div>
  );
}
