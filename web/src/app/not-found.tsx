import Link from "next/link";

export default function NotFound() {
  return (
    <div className="narrow">
      <h1>Page not found</h1>
      <p className="lead">The page you asked for does not exist or has moved.</p>
      <div><Link className="btn btn-primary" href="/">Go home</Link></div>
    </div>
  );
}
