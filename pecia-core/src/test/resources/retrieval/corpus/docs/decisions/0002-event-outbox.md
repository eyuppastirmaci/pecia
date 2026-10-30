# 2. Publish events through an outbox

## Status

Accepted.

## Context

The order service used to write to the database and then send a message to the broker. When the process crashed in
between, other services never heard about the change, or heard about a change that was rolled back.

## Decision

Every event is inserted into an outbox table in the same transaction as the change. A separate publisher reads
unsent rows, sends them to the broker, and marks them as sent.

## Consequences

Consumers may receive an event more than once and must handle duplicates.
