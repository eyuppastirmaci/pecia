# Runbook: database failover

Use this when the PostgreSQL primary of the order service is down and will not come back quickly.

## Confirm the outage

Check that the primary does not answer from two different hosts. A network problem on one host is not a reason to
fail over.

## Promote the replica

1. Stop the order service so that nothing writes during the switch.
2. Run `pg_ctl promote` on the replica in the second zone.
3. Point the `orders-db` DNS record at the promoted host.
4. Start the order service and watch the error rate.

## Afterwards

Rebuild a new replica from the promoted primary before the end of the day, and write an incident review.
