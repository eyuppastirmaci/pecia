# Runbook: payment retries

## What happens when a charge fails

When the payment provider answers with a temporary error, the order stays in `PAYMENT_PENDING` and the order service
tries the charge again. `PaymentRetryPolicy` decides how often: up to five attempts, waiting two seconds before the
second attempt and doubling the wait each time, never more than one minute. A declined card is not retried; the
customer is asked for another card.

## When to act

Page the on-call engineer if more than 50 orders sit in `PAYMENT_PENDING` for over ten minutes. Check the provider's
status page first; if the provider is down, enable the `checkout-maintenance-banner` flag.

## Changing the limits

The limits live in `config/orders.yaml` under `payments.retry`. Change them only together with the payments lead.
