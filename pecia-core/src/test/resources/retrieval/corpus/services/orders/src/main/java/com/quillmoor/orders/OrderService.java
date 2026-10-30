package com.quillmoor.orders;

import java.util.UUID;

/** Turns a basket into a paid order: reserves stock, charges the card, and records events in the outbox. */
public final class OrderService {

    private final InventoryReservation inventory;
    private final PaymentGateway payments;
    private final PaymentRetryPolicy retryPolicy;
    private final OrderRepository orders;

    public OrderService(
            InventoryReservation inventory,
            PaymentGateway payments,
            PaymentRetryPolicy retryPolicy,
            OrderRepository orders) {
        this.inventory = inventory;
        this.payments = payments;
        this.retryPolicy = retryPolicy;
        this.orders = orders;
    }

    public Order checkout(Basket basket, String idempotencyKey) throws InterruptedException {
        Order existing = orders.findByIdempotencyKey(idempotencyKey);
        if (existing != null) {
            return existing;
        }

        Order order = orders.create(UUID.randomUUID(), basket, idempotencyKey);
        inventory.reserve(order);

        for (int attempt = 1; ; attempt++) {
            PaymentGateway.Result result = payments.charge(order);
            if (result.succeeded()) {
                return orders.transition(order, OrderStatus.PAID);
            }
            if (!retryPolicy.shouldRetry(attempt, result.failure())) {
                inventory.release(order);
                return orders.transition(order, OrderStatus.PAYMENT_FAILED);
            }
            Thread.sleep(retryPolicy.delayBefore(attempt + 1).toMillis());
        }
    }
}
