package com.quillmoor.orders;

/** The states of an order. Transitions only move forward, except that a refund can follow any paid state. */
public enum OrderStatus {
    CREATED,
    PAYMENT_PENDING,
    PAID,
    PAYMENT_FAILED,
    DISPATCHED,
    DELIVERED,
    CANCELLED,
    PARTIALLY_REFUNDED,
    REFUNDED
}
