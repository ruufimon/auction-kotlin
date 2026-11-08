/** A real form POST: works without JavaScript and cannot be triggered by a cross-site link. */
export function SignOutButton({ className = "btn btn-secondary", reason = "SIGNED_OUT", label = "Sign out" }: { className?: string; reason?: string; label?: string }) {
  return (
    <form action="/auth/logout" method="post" style={{ display: "inline" }}>
      <input type="hidden" name="reason" value={reason} />
      <button type="submit" className={className}>{label}</button>
    </form>
  );
}
