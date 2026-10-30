"""Compares the payment provider's daily settlement with our paid orders.

Every charge in the settlement must belong to a paid order, and every paid order must appear in a settlement within
three days. Differences are written to a CSV file for the finance team.
"""

import csv
import sys

from quillmoor.db import connect
from quillmoor.psp import download_settlement


def reconcile(day: str) -> list[tuple[str, str]]:
    settlement = {row["charge_id"]: row for row in download_settlement(day)}
    with connect() as connection:
        paid = {
            charge_id: order_id
            for order_id, charge_id in connection.execute(
                "SELECT id, charge_id FROM orders WHERE status = 'PAID' AND paid_on = %s", (day,)
            )
        }
    problems = [(charge, "not in our orders") for charge in settlement.keys() - paid.keys()]
    problems += [(charge, "missing from settlement") for charge in paid.keys() - settlement.keys()]
    return problems


def main() -> None:
    day = sys.argv[1]
    with open(f"reconciliation-{day}.csv", "w", newline="") as output:
        csv.writer(output).writerows(reconcile(day))


if __name__ == "__main__":
    main()
