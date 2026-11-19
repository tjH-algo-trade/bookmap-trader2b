# TradingAssist Bookmap → Trader2B bridge (paper prototype)

A Java L1 add-on with custom Buy, Sell, Cancel order, Check API, and Lookup order
controls. It calls your FastAPI service; it does not call Trader2B directly.

## Included

- `dist/tradingassist-trader2b-paper.jar`: compiled add-on (Java 21).
- Java source and dependency-free HTTP integration tests.
- `API_CONTRACT.md`: proposed interface for your FastAPI implementation.
- `build.sh`: downloads Bookmap compile-time dependencies, compiles, tests, packages.

## Requirements and installation

1. Use a Bookmap installation with Java 21 runtime and API compatibility with
   `api-core` / `api-simplified` **7.6.0.20**. Check your actual installation before
   loading. This artifact has not been tested inside the Bookmap desktop app.
2. Open Bookmap's add-on/API plugin configuration window (Settings → Configure
   Addons, or API plugins configuration depending on version).
3. Add `dist/tradingassist-trader2b-paper.jar`; select
   **TradingAssist Trader2B Bridge — PAPER** and enable it for the chart.
4. Open its settings panel. Enter your FastAPI URL and bearer token, then click
   **Check API**. Default URL: `http://127.0.0.1:8000`.
5. Enter broker symbol (e.g. QQQ) and paper account ID explicitly. The chart alias
   is displayed for reference but is deliberately not converted into a symbol.
6. Select quantity and enable external PAPER orders. Buy/Sell shows a confirmation
   with side, symbol, quantity, account and endpoint before submitting.
7. Inspect the response. Use **Lookup order** to retrieve status by client ID.
   After every mutation, new Buy/Sell is locked until you verify the order/account
   and click **I verified the order/account**. This is deliberately manual until
   your real backend response schema is integrated.

Cancel applies only to the specified client order ID; it does not flatten positions.
Sell may open a short. This prototype supports integer shares, market/day orders only.

## API behavior

Requests run on one background worker; Swing updates run on its event thread.
Each new order gets a UUID, sent as `client_order_id` and `Idempotency-Key`.
No mutation is automatically retried. A timeout is an unknown outcome, not a
rejection. Reconcile against your broker before creating another order.

The server must durably implement idempotency, including across crashes and
simultaneous requests. Merely receiving the key does not prevent duplicates.

Raw HTTP code and JSON response are shown. HTTP 200 is never labeled as filled.
Order status is refreshed manually. No WebSocket, position reconciliation,
persistent order journal, native Bookmap order markers or P&L sync are implemented.
Keep backend/broker records as the source of truth. The UI log and client order ID
are lost when the add-on is unloaded; use backend records to recover them.

## Paper boundary

Every order and cancellation carries `environment: "paper"`. The backend must
reject any account that is not actually a paper account, and must never silently
route it to live. The client cannot independently prove how your backend routes.
Bookmap SIM/replay does not govern these external requests. Do not point this
prototype at a service that ignores the environment field.

Tokens are not written to disk. Use a bridge token, not broker credentials.
Plain HTTP is permitted only for localhost or 127.0.0.1; use HTTPS elsewhere.
No HTTP redirects are followed. Configuration is intentionally not persisted yet.

## Build/test on macOS or Linux

Install a full JDK 21, then run:

```bash
bash build.sh
```

The tests run a local in-process HTTP server. They cover buy/sell payloads,
authentication, idempotency headers, lookup, cancellation, HTTP rejection,
redirect refusal, timeout/no retry, and invalid URL/symbol/quantity inputs.
They do not place real orders.

## Status

Compiled and HTTP integration tests passed in a Linux Java 21 environment.
Not yet tested inside Bookmap, against your FastAPI service, or against Trader2B.
Before live support, integrate actual response schemas, durable reconciliation,
account/environment verification, and lifecycle testing in Bookmap.

## Official references used

- https://bookmap.com/knowledgebase/docs/API
- https://bookmap.com/knowledgebase/docs/API-Tutorial
- https://github.com/BookmapAPI/DemoStrategies
- https://bookmap.com/addons/api/velox/api/layer1/simplified/CustomSettingsPanelProvider.html

Bookmap demo source snapshot consulted during implementation is recorded in
`BOOKMAP_REFERENCE_COMMIT.txt`. API JARs are compile-time dependencies, not bundled
in the release archive or add-on JAR.
