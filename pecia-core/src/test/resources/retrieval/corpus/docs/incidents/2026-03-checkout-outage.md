# Incident review: checkout outage, 14 March 2026

## Summary

For 47 minutes customers could browse the shop but every attempt to pay failed with an error page.

## Timeline

- 10:02 The gateway's certificate for the payment callback domain expired.
- 10:05 Error rate on payment callbacks reached 100 percent.
- 10:31 On-call found the expired certificate.
- 10:49 A renewed certificate was installed and payments recovered.

## Cause

Automatic renewal had been failing for three weeks because a firewall change blocked the ACME challenge. Nobody was
alerted.

## Actions

- Add an expiry check that alerts 14 days ahead (done: `scripts/check_certificates.py`).
- Add the callback domain to the certificate dashboard.
