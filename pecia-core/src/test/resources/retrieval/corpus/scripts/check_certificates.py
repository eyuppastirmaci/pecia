"""Alerts when a public TLS certificate is close to expiry.

Checks every host in HOSTS and sends an alert when a certificate has fewer than ALERT_DAYS days left.
"""

import datetime as dt
import socket
import ssl

from quillmoor.alerts import send_alert

HOSTS = ["shop.quillmoor.example", "api.quillmoor.example", "pay-callback.quillmoor.example"]
ALERT_DAYS = 14


def days_left(host: str) -> int:
    context = ssl.create_default_context()
    with socket.create_connection((host, 443), timeout=10) as raw:
        with context.wrap_socket(raw, server_hostname=host) as tls:
            not_after = dt.datetime.strptime(tls.getpeercert()["notAfter"], "%b %d %H:%M:%S %Y %Z")
    return (not_after - dt.datetime.utcnow()).days


def main() -> None:
    for host in HOSTS:
        remaining = days_left(host)
        if remaining < ALERT_DAYS:
            send_alert(f"Certificate for {host} expires in {remaining} days")


if __name__ == "__main__":
    main()
