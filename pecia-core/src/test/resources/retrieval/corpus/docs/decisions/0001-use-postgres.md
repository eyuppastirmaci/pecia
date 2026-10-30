# 1. Use PostgreSQL for orders

## Status

Accepted.

## Context

Orders, payments, and refunds need transactions and strong consistency. The team already operates PostgreSQL for the
catalog.

## Decision

The order service stores its data in PostgreSQL with a streaming replica in a second availability zone.

## Consequences

We accept a single primary for writes and document how to promote the replica.
