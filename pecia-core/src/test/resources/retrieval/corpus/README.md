# Quillmoor

Quillmoor is a small online bookshop. This repository holds the order service, the web shop, operational scripts,
configuration, and the team's documentation.

## Layout

- `services/orders` — the order service: checkout, payments, refunds, and the event outbox.
- `web` — the customer-facing shop written in TypeScript.
- `scripts` — maintenance and reporting jobs run by cron.
- `config` and `deploy` — service configuration and deployment manifests.
- `docs` — architecture notes, policies, runbooks, decisions, and incident reviews.
- `notes` — meeting notes kept as plain text.

## Getting started

Read `docs/onboarding.md` first. It explains how to start the stack locally and where to ask for help.
