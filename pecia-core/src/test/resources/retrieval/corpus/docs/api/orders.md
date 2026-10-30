# Orders API

## Create an order

`POST /v1/orders` creates an order from the customer's basket. Clients must send an `Idempotency-Key` header; a
repeated request with the same key returns the original order instead of creating a second one.

## Read an order

`GET /v1/orders/{id}` returns the order with its lines, payment state, and shipments.

## Cancel an order

`POST /v1/orders/{id}/cancel` cancels an order that has not been dispatched yet. Cancelling a paid order starts a full
refund automatically.
