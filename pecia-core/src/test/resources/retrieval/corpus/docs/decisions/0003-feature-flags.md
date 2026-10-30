# 3. Ship risky changes behind feature flags

## Status

Accepted.

## Decision

Larger changes to checkout and payments ship disabled behind a flag in `config/feature-flags.json`. A flag is turned
on for staff first, then for a percentage of customers, then for everyone. Flags are removed within two releases of
being fully enabled.
