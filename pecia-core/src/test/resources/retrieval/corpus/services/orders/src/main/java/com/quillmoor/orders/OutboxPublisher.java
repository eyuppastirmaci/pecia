package com.quillmoor.orders;

import java.util.List;

/**
 * Sends unsent outbox rows to the broker in insertion order and marks them as sent. It runs every second; when the
 * broker is unreachable the rows stay unsent and are tried again on the next run.
 */
public final class OutboxPublisher implements Runnable {

    private static final int BATCH_SIZE = 100;

    private final OutboxRepository outbox;
    private final Broker broker;

    public OutboxPublisher(OutboxRepository outbox, Broker broker) {
        this.outbox = outbox;
        this.broker = broker;
    }

    @Override
    public void run() {
        List<OutboxEvent> events = outbox.findUnsent(BATCH_SIZE);
        for (OutboxEvent event : events) {
            try {
                broker.publish("order-events", event.type(), event.payload());
                outbox.markSent(event.id());
            } catch (BrokerUnavailableException unavailable) {
                return;
            }
        }
    }
}
