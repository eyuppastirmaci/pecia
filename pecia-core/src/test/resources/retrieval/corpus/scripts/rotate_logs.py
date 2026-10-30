"""Compresses application logs older than one day and deletes those older than 30 days."""

import gzip
import shutil
import time
from pathlib import Path

LOG_DIR = Path("/var/log/quillmoor")
COMPRESS_AFTER_DAYS = 1
DELETE_AFTER_DAYS = 30


def age_in_days(path: Path) -> float:
    return (time.time() - path.stat().st_mtime) / 86400


def main() -> None:
    for log in LOG_DIR.glob("*.log*"):
        age = age_in_days(log)
        if age > DELETE_AFTER_DAYS:
            log.unlink()
        elif age > COMPRESS_AFTER_DAYS and log.suffix == ".log":
            with log.open("rb") as source, gzip.open(f"{log}.gz", "wb") as target:
                shutil.copyfileobj(source, target)
            log.unlink()


if __name__ == "__main__":
    main()
