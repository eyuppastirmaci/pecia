import { useState } from "react";
import { requestPasswordReset } from "../api/client";

/**
 * Lets a customer who forgot their password ask for a reset link. The answer is the same whether or not the email
 * belongs to an account, so the form cannot be used to find out who is a customer.
 */
export function PasswordReset() {
  const [email, setEmail] = useState("");
  const [sent, setSent] = useState(false);

  if (sent) {
    return <p>If an account exists for {email}, we have emailed a link to choose a new password. It works for 30 minutes.</p>;
  }

  return (
    <form
      onSubmit={async (event) => {
        event.preventDefault();
        await requestPasswordReset(email);
        setSent(true);
      }}
    >
      <label>
        Email
        <input type="email" value={email} onChange={(event) => setEmail(event.target.value)} />
      </label>
      <button type="submit">Send reset link</button>
    </form>
  );
}
