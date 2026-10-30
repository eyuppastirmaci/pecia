# Architecture

Quillmoor runs four services behind one gateway.

## Order service

Accepts checkouts, reserves stock, charges the customer through the payment provider, and records refunds. It is a
Java application backed by PostgreSQL. Every state change is written to an outbox table in the same transaction and
published to the message broker afterwards.

## Catalog service

Serves book metadata, prices, and availability to the web shop. It reads a search index that a nightly job rebuilds.

## Notifier

Consumes order events and sends emails: confirmations, dispatch notices, and refund receipts. It enforces a rate limit
per recipient so that a burst of events cannot flood an inbox.

## Web shop

A TypeScript single-page application. It talks to the gateway only, never to a service directly.

## Data stores

PostgreSQL holds orders, payments, and the outbox. A streaming replica in a second zone can be promoted when the
primary fails. Object storage holds nightly backups and exported reports.
