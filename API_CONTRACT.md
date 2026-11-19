# Proposed FastAPI bridge contract — v1

This is OUR proposed interface, not Trader2B's public API. Implement or adapt it
in your service. Send your actual OpenAPI schema later if you prefer other paths.

Base URL default: `http://127.0.0.1:8000`. A base path such as `/bookmap` is allowed.
Authentication: `Authorization: Bearer <bridge-token>` (omitted when UI is blank).
Require authentication in deployed configurations. Broker credentials stay server-side.
All responses should be small JSON documents; do not return credentials or secrets.

## 1. GET /health

Return HTTP 200 and for example:

```json
{"status":"ok","environment":"paper","broker_connected":true}
```

The initial client displays this body; it does not parse it or treat it as proof
of paper-account configuration. Enforce that configuration on every mutation.

## 2. POST /v1/orders

Header `Idempotency-Key` equals the client order UUID.

```json
{
  "client_order_id": "e0e45751-e1df-4e41-bdfe-763ee933fc61",
  "account_id": "paper-1",
  "environment": "paper",
  "symbol": "QQQ",
  "side": "buy",
  "quantity": 5,
  "order_type": "market",
  "time_in_force": "day"
}
```

`side`: buy or sell. Quantity is integer shares, 1–10000 in this prototype.
Validate symbol, account ownership, authorization, buying power and allowed order
size server-side; client limits are not a substitute. Reject live accounts.

Suggested response HTTP 202 (acknowledged, not necessarily filled):

```json
{
  "client_order_id": "e0e45751-e1df-4e41-bdfe-763ee933fc61",
  "broker_order_id": "broker-generated-id",
  "account_id": "paper-1",
  "environment": "paper",
  "status": "accepted",
  "filled_quantity": 0,
  "average_fill_price": null
}
```

Persist the client ID BEFORE broker submission and reconcile ambiguous broker
responses. Do not send an order twice if the HTTP response is lost. Same key and
same payload returns the existing order; same key with different payload returns
409. Enforce uniqueness durably and atomically, scoped to the authenticated owner
and account. Prefer forwarding the client ID to the broker if supported.

## 3. GET /v1/orders/by-client-id/{client_order_id}?account_id=paper-1&environment=paper

Return authoritative latest status and fill information, including broker order
ID. Suggested statuses: pending, accepted, partially_filled, filled,
cancel_pending, canceled, rejected, unknown. Return 404 if not found; a missing
record after timeout is NOT proof the broker never received the original order.
Verify ownership and account on lookup.

## 4. POST /v1/orders/by-client-id/{client_order_id}/cancel

Header `Idempotency-Key`: a new cancellation UUID.

```json
{"account_id":"paper-1","environment":"paper"}
```

Request cancellation of the mapped broker order; return its true status.
Use `cancel_pending` while cancellation is unconfirmed. Filled orders cannot be
uncanceled or flattened by this endpoint. Make cancellation idempotent and check
account ownership. Never cancel all orders implicitly.

## Errors

Use 401/403 for authentication/authorization, 422 for invalid requests, 409 for
conflicts, 429 for limits, and 5xx for backend failures. JSON can include a safe
`detail` and `request_id`. The add-on displays all HTTP responses without inferring
execution success; timeout/transport error never triggers automatic resubmission.

## Integration handoff

Supply the actual base URL, auth convention and OpenAPI document (without
credentials). If your paths differ, adapt the strings in Trader2BPanel.java.
This first version deliberately has no real-money mode.
