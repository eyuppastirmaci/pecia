# Runbook: TLS certificate renewal

Our public certificates are issued for 90 days and renewed automatically 30 days before they expire.

## When renewal fails

The `check_certificates.py` job alerts when a certificate has fewer than 14 days left. Renew by hand with the ACME
client on the gateway host, then reload the gateway.

## After renewing

Confirm the new expiry date from outside the network, and note the cause of the failed automatic renewal in the
on-call log.
