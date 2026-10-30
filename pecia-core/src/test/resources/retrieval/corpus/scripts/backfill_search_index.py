"""Rebuilds the catalog search index from the database.

Run it after changing how titles or authors are normalised. It reads books in pages of 500 and replaces the index
atomically when every page has been written.
"""

from quillmoor.catalog import books_in_pages
from quillmoor.search import IndexWriter

PAGE_SIZE = 500


def main() -> None:
    with IndexWriter.temporary() as writer:
        for page in books_in_pages(PAGE_SIZE):
            writer.add_all(page)
        writer.swap_into_place()


if __name__ == "__main__":
    main()
