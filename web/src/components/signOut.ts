/** Programmatic sign-out (e.g. on suspension): posts the same form the Sign out button does. */
export function signOut(reason: "SIGNED_OUT" | "ACCOUNT_SUSPENDED" | "SESSION_EXPIRED" = "SIGNED_OUT") {
  const form = document.createElement("form");
  form.method = "post";
  form.action = "/auth/logout";
  const input = document.createElement("input");
  input.type = "hidden";
  input.name = "reason";
  input.value = reason;
  form.appendChild(input);
  document.body.appendChild(form);
  form.submit();
}
