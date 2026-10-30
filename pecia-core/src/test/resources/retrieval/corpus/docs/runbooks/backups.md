# Runbook: backups

## What is backed up

The order database, the catalog database, and the uploaded cover images.

## Schedule

A full PostgreSQL dump runs every night at 02:30 UTC. Write-ahead logs are archived continuously, so any moment of the
last seven days can be recovered.

## Where backups go

Dumps are encrypted with the backup key and copied to object storage in another region. Thirty nightly dumps are kept,
then one per month for a year.

## Monitoring

The backup job reports to the monitoring system. A missing report after 04:00 pages the on-call engineer.

## Restoring

### Choosing the point in time

Decide with the incident lead which moment to return to. For a bad deploy, that is usually one minute before the
deploy started.

### Restoring into a scratch instance

Never restore over the live database. Create a scratch instance, download the nightly dump taken before the chosen
moment, restore it with `pg_restore`, then replay the archived write-ahead logs up to the chosen time.

### Switching over

Compare row counts of the main tables with the live database, stop the order service, swap the DNS record to the
restored instance, and start the service again.

## Testing restores

Once a month someone restores the latest dump into a scratch instance and records how long it took.
