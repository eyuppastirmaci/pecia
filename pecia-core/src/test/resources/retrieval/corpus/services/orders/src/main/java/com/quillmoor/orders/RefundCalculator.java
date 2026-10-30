package com.quillmoor.orders;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Computes how much money a returned order line gives back. */
public final class RefundCalculator {

    static final BigDecimal RESTOCKING_FEE = new BigDecimal("0.15");

    /**
     * Returns the refund for a line: the full price for damaged or wrong items, the price minus the restocking fee for
     * opened box sets, and the full price otherwise.
     */
    public BigDecimal refundFor(OrderLine line, ReturnCondition condition) {
        BigDecimal price = line.unitPrice().multiply(BigDecimal.valueOf(line.quantity()));

        return switch (condition) {
            case DAMAGED, WRONG_ITEM -> price;
            case OPENED_BOX_SET -> price.subtract(price.multiply(RESTOCKING_FEE)).setScale(2, RoundingMode.HALF_UP);
            case UNOPENED -> price;
        };
    }

    /** Returns the status an order moves to after a refund of the given lines. */
    public OrderStatus statusAfterRefund(Order order, int refundedLines) {
        return refundedLines == order.lines().size() ? OrderStatus.REFUNDED : OrderStatus.PARTIALLY_REFUNDED;
    }

    public enum ReturnCondition {
        UNOPENED,
        OPENED_BOX_SET,
        DAMAGED,
        WRONG_ITEM
    }
}
