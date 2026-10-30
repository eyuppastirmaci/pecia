"""Writes last month's sales per book to a CSV file in object storage.

Runs on the first day of each month. The report lists ISBN, title, units sold, revenue, and refunded amount.
"""

import csv
import datetime as dt
import io

from quillmoor.db import connect
from quillmoor.storage import upload

QUERY = """
    SELECT l.isbn, b.title, sum(l.quantity), sum(l.quantity * l.unit_price), coalesce(sum(r.amount), 0)
    FROM order_lines l
    JOIN books b ON b.isbn = l.isbn
    LEFT JOIN refunds r ON r.order_line_id = l.id
    WHERE l.created_at >= %s AND l.created_at < %s
    GROUP BY l.isbn, b.title
    ORDER BY 4 DESC
"""


def previous_month(today: dt.date) -> tuple[dt.date, dt.date]:
    first = today.replace(day=1)
    start = (first - dt.timedelta(days=1)).replace(day=1)
    return start, first


def main() -> None:
    start, end = previous_month(dt.date.today())
    buffer = io.StringIO()
    writer = csv.writer(buffer)
    writer.writerow(["isbn", "title", "units", "revenue", "refunded"])
    with connect() as connection:
        writer.writerows(connection.execute(QUERY, (start, end)))
    upload(f"reports/sales-{start:%Y-%m}.csv", buffer.getvalue())


if __name__ == "__main__":
    main()
