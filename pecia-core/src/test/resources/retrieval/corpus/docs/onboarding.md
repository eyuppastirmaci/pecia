# Onboarding

Welcome to the team. This page gets a new developer from a fresh laptop to a running stack.

## Tools

Install a JDK 21, Node 20, Python 3.12, and Docker. Clone the repository and run `make bootstrap`, which installs the
web dependencies and builds the order service.

## Running locally

`docker compose -f deploy/docker-compose.yml up` starts PostgreSQL, the broker, and a fake payment provider. The web
shop then runs with `npm run dev` inside `web`.

## Accounts you need

Ask your lead for access to the issue tracker, the on-call schedule, and the shared password vault. Never store
credentials in the repository.

## First week

Pair with someone on a small bug, read the runbooks in `docs/runbooks`, and shadow one on-call handover.
