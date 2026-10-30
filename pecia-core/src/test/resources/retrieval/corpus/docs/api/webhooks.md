# Webhooks

Partners can subscribe to order events such as `order.paid`, `order.dispatched`, and `order.refunded`.

## Signatures

Every delivery carries an `X-Quill-Signature` header: the hex HMAC-SHA256 of the raw request body, keyed with the
partner's webhook secret. Receivers must compute the same value and compare it in constant time before trusting the
payload. Deliveries without a valid signature should be rejected with status 401.

## Retries

A delivery that does not get a 2xx answer is tried again after 1, 5, and 30 minutes, then dropped.
