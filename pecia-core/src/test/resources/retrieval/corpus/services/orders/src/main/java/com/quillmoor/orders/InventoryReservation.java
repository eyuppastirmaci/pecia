package com.quillmoor.orders;

/**
 * Holds stock for an order between checkout and payment. A reservation expires after 15 minutes so that abandoned
 * checkouts do not block books forever.
 */
public final class InventoryReservation {

    private static final long HOLD_MINUTES = 15;

    private final StockRepository stock;

    public InventoryReservation(StockRepository stock) {
        this.stock = stock;
    }

    public void reserve(Order order) {
        for (OrderLine line : order.lines()) {
            if (!stock.hold(line.isbn(), line.quantity(), HOLD_MINUTES)) {
                throw new OutOfStockException(line.isbn());
            }
        }
    }

    public void release(Order order) {
        order.lines().forEach(line -> stock.release(line.isbn(), line.quantity()));
    }
}
