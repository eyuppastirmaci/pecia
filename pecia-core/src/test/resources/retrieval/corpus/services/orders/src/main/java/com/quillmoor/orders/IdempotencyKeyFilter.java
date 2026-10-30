package com.quillmoor.orders;

import java.io.IOException;

/**
 * Rejects order creation requests without an Idempotency-Key header, so that a client retrying after a timeout can
 * never create the same order twice.
 */
public final class IdempotencyKeyFilter implements HttpFilter {

    static final String HEADER = "Idempotency-Key";
    private static final int MAX_LENGTH = 64;

    @Override
    public void filter(HttpRequest request, HttpResponse response, FilterChain chain) throws IOException {
        if (request.method().equals("POST") && request.path().equals("/v1/orders")) {
            String key = request.header(HEADER);
            if (key == null || key.isBlank() || key.length() > MAX_LENGTH) {
                response.sendError(400, "Missing or invalid " + HEADER + " header");
                return;
            }
        }
        chain.next(request, response);
    }
}
