# Release process

We deploy the order service and the web shop every Tuesday morning after the staging checks pass.

## Steps

1. Merge to main closes the release branch at Monday 18:00.
2. The pipeline deploys to staging and runs the end-to-end suite.
3. The release captain checks the dashboards and promotes the build to production.
4. If error rates rise, roll back with the previous image tag; rollbacks take about ten minutes.

## Deploy freeze

No production deploys happen from the 20th of December to the 2nd of January, or during the week of a large sale.
Urgent fixes during a freeze need approval from two leads and are announced in the incident channel.
