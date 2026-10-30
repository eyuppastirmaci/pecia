# Changelog

## 2026.06

- Checkout shows the delivery estimate before payment.
- Refund amounts now subtract the restocking fee for opened box sets.
- The notifier sends at most one order confirmation per order.

## 2026.04

- Webhooks carry a signature header; unsigned deliveries are rejected.
- The order service publishes events through the outbox instead of writing to the broker directly.

## 2026.02

- Password reset links expire after thirty minutes.
- Monthly sales export runs on the first day of each month.
