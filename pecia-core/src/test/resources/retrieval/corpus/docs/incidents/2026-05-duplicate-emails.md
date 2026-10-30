# Incident review: duplicate confirmation emails, 3 May 2026

## Summary

About 1,200 customers received two or three identical order confirmations.

## Cause

The notifier timed out while talking to the email provider, but the provider had actually accepted the message. The
notifier then sent it again. Because consumers may see an event more than once, the notifier must remember which
messages it already sent, and it did not.

## Actions

- The notifier stores a sent-message key per order and event type and skips repeats.
- The provider timeout was raised from 2 to 10 seconds in `config/notifier.toml`.
