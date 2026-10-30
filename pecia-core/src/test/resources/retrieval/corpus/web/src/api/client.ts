const BASE_URL = "/api";
const RETRIES = 2;

/** Calls the gateway, retrying network failures twice. Order creation reuses one idempotency key across retries. */
async function request<T>(path: string, init: RequestInit): Promise<T> {
  let lastError: unknown;
  for (let attempt = 0; attempt <= RETRIES; attempt++) {
    try {
      const response = await fetch(BASE_URL + path, init);
      if (!response.ok) {
        throw new Error(`Request failed with ${response.status}`);
      }
      return (await response.json()) as T;
    } catch (error) {
      lastError = error;
    }
  }
  throw lastError;
}

export function createOrder(body: object) {
  const key = crypto.randomUUID();
  return request("/v1/orders", {
    method: "POST",
    headers: { "Content-Type": "application/json", "Idempotency-Key": key },
    body: JSON.stringify(body),
  });
}

export function requestPasswordReset(email: string) {
  return request("/v1/password-resets", { method: "POST", body: JSON.stringify({ email }) });
}
