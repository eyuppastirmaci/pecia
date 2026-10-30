# Runbook: queue backlog

## Symptoms

The broker's `order-events` queue grows and emails or dispatch updates arrive late.

## Checks

1. Look at the consumer count of the notifier and the shipping adapter.
2. Look for a poison message: one message that fails again and again blocks its consumer.
3. Check that the outbox publisher is still running; if it stopped, events pile up in the outbox table instead.

## Fixes

Scale the notifier consumers up to six instances. Move a poison message to the dead-letter queue after recording its
id. Never purge the queue.
