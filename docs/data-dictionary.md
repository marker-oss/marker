# Data Dictionary — L1 Normalized Tables

> This document describes the canonical semantics of every normalized table
> that feeds the 10 reports. It is the **L1 layer** of the canon — the
> kirpichiki on top of which L2 formulas (in `canonical-formulas.md`) are
> built. Every field listed here has a corresponding Malli schema at
> `src/analitica/schema/normalized/<table>.clj`.
>
> Authored as part of the 2026-04-23 canon audit; see
> `docs/superpowers/specs/2026-04-23-canon-audit-l1-l2-design.md` for the
> design decisions behind this split.

## Index

- [finance](#finance) — per-row marketplace financial events
- [sales](#sales) — per-sale / per-return events (order-level)
- [stocks](#stocks) — warehouse stock snapshots
- [cost_prices](#cost_prices) — self-cost per article (from 1C)
- [ad_stats](#ad_stats) — WB advertising campaign daily statistics
- [paid_storage](#paid_storage) — WB paid-storage daily cost
- [region_sales](#region_sales) — per-region sales aggregates
- [cash_flow_periods](#cash_flow_periods) — Ozon period-level cash flow

## Contract Format (per table)

Each section below follows a fixed 7-point structure:

1. **Purpose** — what economic events the table captures.
2. **Grain** — what one row represents (e.g. per-article-per-day, per-operation).
3. **Source mapping** — per marketplace (WB / Ozon / YM / 1C): endpoint + raw field → normalized field.
4. **Field dictionary** — name, Malli type, nullability, unit (RUB / qty / %), enum values, economic meaning.
5. **Invariants** — what must hold between fields (e.g. `mp_commission <= revenue`).
6. **Edge cases** — cancellations, returns, multi-article operations, timezone, double-counting.
7. **Known gaps** — fields theoretically absent per marketplace (YM has no per-SKU storage, etc.).

---

<!-- Tasks 1-8 populate one section each, in order. -->

## finance

### Purpose

Normalized per-row financial events from marketplace settlement reports.
Captures every money-moving operation (sale, return, commission charge,
logistics fee, penalty, storage fee, ad spend) at its most granular
grain, regardless of marketplace. Primary source for Finance, P&L, UE,
and ABC reports.

### Grain

One row = one marketplace-reported financial event, keyed by
`(marketplace, rrd_id)`. For WB, `rrd_id` comes from the detail-by-period
report. For Ozon, `rrd_id` is synthesized by `transform.clj` from
`operation_id` + service index to keep the composite PK stable. For YM,
`rrd_id` is synthesized from order id + bidFee/event type.

### Source mapping

| marketplace | endpoint                                | raw field → normalized field |
|---|---|---|
| WB  | `/api/v5/supplier/reportDetailByPeriod` | `rrd_id` → `rrd-id`, `date_from` → `date-from`, `retail_amount` → `retail-amount`, `for_pay` → `for-pay`, `ppvz_for_pay` → `for-pay` (fallback), `bonus_type_name` → `operation`, `commission_percent` → `commission-pct`, `ppvz_vw` → `wb-reward`, `delivery_rub` → `delivery-cost`, `storage_fee` → `storage-fee`, `acceptance` → `acceptance`, `penalty` → `penalty`, `additional_payment` → `additional-payment`, `deduction` → `deduction`, `acquiring_fee` → `acquiring-fee` |
| Ozon | `/v3/finance/transaction/list` + `/v2/finance/realization` | `amount` → `for-pay` (transaction) / `payout` → `for-pay` (realization), `operation_type` → `operation`, `services[*].name` → field-mapped cost per service type (see `transform.clj`), `commission_amount` → `wb-commission` (reused field, see known gap), `sku` → `article` via `ozon_sku_map` |
| YM | `/campaigns/{id}/stats/orders` + `/reports/united-netting` | `status` → `operation` (`DELIVERED` → `sale`, `CANCELLED` → `cancel`, etc.), `netting.*` → for-pay, `bidFee` → `ad-cost`, order item `price * count` → `retail-amount` |

### Field dictionary

| field | Malli type | nullable | unit | enum | meaning |
|---|---|---|---|---|---|
| `marketplace` | `[:enum :wb :ozon :ym]` | no | — | wb/ozon/ym | source marketplace (canonical keyword) |
| `rrd-id` | `[:or :int :double]` | no | — | — | composite PK second half — MP-assigned or synthesized |
| `date-from` | `:string` | no | ISO date | — | reporting period start |
| `date-to` | `:string` | no | ISO date | — | reporting period end |
| `article` | `:string` | yes | — | — | seller-facing article (offer_id for Ozon, supplierArticle for WB) |
| `operation` | `:string` | no | — | sale/return/commission/logistics/storage/ad/penalty/cancel/other | normalized operation kind |
| `quantity` | int/double | yes | units | — | units moved (positive for sale, negative or separate row for return) |
| `for-pay` | int/double | no | RUB | — | net amount credited to seller for this row |
| `retail-price` | int/double | yes | RUB | — | list price per unit |
| `retail-amount` | int/double | yes | RUB | — | retail price × quantity before discounts |
| `sale-percent` | int/double | yes | % | — | discount pct applied |
| `commission-pct` | int/double | yes | % | — | MP commission as declared in report |
| `wb-commission` | int/double | yes | RUB | — | WB: from report; Ozon: `commission_amount` (reused field — see known gap) |
| `wb-reward` | int/double | yes | RUB | — | WB: `ppvz_vw`; other MP: nil |
| `wb-kvw-prc` | int/double | yes | % | — | WB-specific discount margin |
| `spp-prc` | int/double | yes | % | — | WB SPP percentage |
| `price-with-disc` | int/double | yes | RUB | — | discounted unit price |
| `delivery-amount` | int/double | yes | count | — | number of deliveries/returns in row |
| `return-amount` | int/double | yes | count | — | number of returned units |
| `delivery-cost` | int/double | yes | RUB | — | logistics fee for this row |
| `penalty` | int/double | yes | RUB | — | fines attributed to this row |
| `storage-fee` | int/double | yes | RUB | — | storage fee attributed to this row |
| `acceptance` | int/double | yes | RUB | — | acceptance/receiving fee |
| `additional-payment` | int/double | yes | RUB | — | surcharges |
| `deduction` | int/double | yes | RUB | — | other deductions |
| `acquiring-fee` | int/double | yes | RUB | — | payment-processing fee |
| `ad-cost` | int/double | yes | RUB | — | ad spend allocated to this row (per-article for YM/Ozon; proportional for WB — spec 003 US5) |
| `report-id` | int/double | yes | — | — | MP report batch id (for traceability) |
| `nm-id` | int/double | yes | — | — | WB-specific numeric article id |
| `barcode` | `:string` | yes | — | — | product barcode if available |
| `subject`, `brand`, `doc-type` | `:string` | yes | — | — | catalog metadata |
| `synced-at` | `:string` | yes | ISO timestamp | — | when sync wrote this row |

### Invariants

- `for-pay` must be numeric (not nil). All other money fields may be nil.
- For WB, `rrd-id` is always a positive integer.
- For a sale row, `quantity >= 1`. For a return row, either `quantity <= 0` or `return-amount >= 1` (marketplace-dependent — see edge cases).
- `marketplace` must be one of `:wb` `:ozon` `:ym`. Stale string values are rejected by schema.

### Edge cases

- **WB return rows** may have `quantity` negative or `return_amount > 0` depending on report version.
- **Ozon cancellations** appear as transactions with `operation_type = OperationTypeRefund` — normalized to `operation = "return"` by `ozon/transform.clj`.
- **Ozon per-article service costs** live as separate rows with `quantity = 0` or `nil` and a single cost field populated (e.g. `delivery-cost`). See B-009 in `specs/002-calculation-audit/verdicts.md`.
- **YM cancelled orders still accrue `ad-cost`** — bidFee charged even if order never delivered.
- **Multi-article ad campaigns** (WB) allocate `ad-cost` proportionally to revenue per article (spec 003 US5).
- **Timezone:** all dates are in Moscow TZ as emitted by MPs; no UTC conversion.
- **`as-kebab-maps` namespacing:** `next.jdbc.result-set/as-kebab-maps` returns keys namespaced by table (`:finance/rrd-id`). Callers must strip the namespace qualifier before passing rows to `validate-rows`. See `strip-ns` helper in `finance_test.clj`.

### Known gaps

- `wb-commission` field is reused by Ozon `transform.clj` to carry `commission_amount` from realization — semantically the same (MP commission in RUB) but the WB-prefixed name is misleading. Rename deferred.
- **Ozon finance_realization sometimes omits `return_commission` and `delivery_commission`** — wrapped in `[:maybe ...]` at the API schema layer; not a FinanceRow concern.
- **YM has no storage/acceptance fees at per-article grain** — those fields are nil for `:ym` rows.
- **WB advertising allocation** is proportional to revenue, not actual campaign-to-article attribution — approximation documented as B-003 in 002-audit verdicts.

## sales

### Purpose

Per-order and per-return events captured at the line-item grain. Feeds
Sales, Returns, Buyout, and Trends reports. This is an event stream:
one row per sale event, one row per return event. Regional geography
is denormalized into the row for per-region analytics.

### Grain

One row = one sale event OR one return event, keyed by `sale_id`
(unique across marketplaces — transform prefixes / concatenates IDs
when source IDs aren't globally unique).

### Source mapping

| marketplace | endpoint | raw → normalized |
|---|---|---|
| WB | `/api/v1/supplier/sales` | `saleID` → `sale-id`, `date` → `date`, `supplierArticle` → `article`, `odid` → (used in sale-id prefix), `totalPrice` → `total-price`, `forPay` → `for-pay`, `finishedPrice` → `finished-price`, `priceWithDisc` → `price-with-disc`, positive `sale/for_pay` → `:sale`, return report endpoint → `:return` |
| Ozon | `/v3/posting/fbo/list` + `/v3/posting/fbs/list` | `posting_number` → `sale-id`, `in_process_at` → `date`, `products[*].offer_id` → `article`, `status` → `:sale`/`:cancel`/`:return` |
| YM | `/campaigns/{id}/stats/orders` | `id` → `sale-id`, `creationDate` → `date`, `items[*].offerId` → `article`, `status` mapping: `DELIVERED` → `:sale`, `CANCELLED` → `:cancel`, return flows → `:return` |

### Field dictionary

| field | Malli type | nullable | unit | enum | meaning |
|---|---|---|---|---|---|
| `sale-id` | `:string` | no | — | — | globally unique event id |
| `date` | `:string` | no | ISO date | — | event date (order creation or return) |
| `article` | `:string` | no | — | — | seller-facing article |
| `type` | `[:enum :sale :return :cancel]` | no | — | sale/return/cancel | event kind |
| `marketplace` | `[:enum :wb :ozon :ym]` | no | — | wb/ozon/ym | source |
| `total-price` | int/double | yes | RUB | — | list price before discounts × qty |
| `for-pay` | int/double | yes | RUB | — | seller payout for this event |
| `finished-price` | int/double | yes | RUB | — | price after MP discounts, before SPP |
| `price-with-disc` | int/double | yes | RUB | — | final buyer price |
| `nm-id`, `barcode`, `tech-size`, `subject`, `category`, `brand`, `warehouse`, `region` | various | yes | — | — | catalog + geography metadata |
| `synced-at` | `:string` | yes | ISO timestamp | — | when sync wrote this row |

### Invariants

- `sale-id` is unique across all rows (PK).
- `type` is one of `:sale`, `:return`, `:cancel` — no other values.
- For a `:sale` row, `for-pay` should be > 0 (may be nil when MP report hasn't closed).
- For a `:return` or `:cancel` row, `for-pay` may be 0, negative, or nil.

### Edge cases

- **WB sales and returns come from separate endpoints** — transform merges into one table with `type` as discriminator.
- **Ozon partial returns** — one posting can have multiple return events; each gets its own row.
- **YM cancelled orders** never produce a `:sale` row; they appear only as `:cancel`.
- **Duplicate `sale_id` across marketplaces**: transform prefixes non-WB ids (e.g. `ozon-<posting>`) to guarantee global uniqueness.

### Known gaps

- **Warehouse attribution** for Ozon is via `warehouse_id` lookup; unknown warehouses land as nil.
- **Region** for YM orders often nil — YM exposes region only in specific report endpoints.

## stocks

### Purpose

Point-in-time warehouse stock snapshots per article per warehouse.
Feeds the Stock report and `unit_economics` days-of-supply calculation.
Not a time series — each sync overwrites prior rows for the same (article, warehouse, marketplace).

### Grain

One row = one (article, warehouse, marketplace) stock snapshot at the last sync.

### Source mapping

| marketplace | endpoint | raw → normalized |
|---|---|---|
| WB | `/api/v1/supplier/stocks` | `supplierArticle` → `article`, `nmId` → `nm-id`, `warehouseName` → `warehouse`, `quantity` → `quantity`, `quantityFull` → `quantity-full`, `inWayToClient` → `in-way-to`, `inWayFromClient` → `in-way-from` |
| Ozon | `/v3/product/info/stocks` | `offer_id` → `article`, `stocks[*].type` → `warehouse`, `stocks[*].present` → `quantity`, `stocks[*].reserved` (subtracted or separate key — check transform) |
| YM | `/businesses/{id}/offer-mappings` | `offer.id` → `article`, `stocks[*].warehouseId` → `warehouse`, stock counts → `quantity` |

### Field dictionary

| field | Malli type | nullable | unit | meaning |
|---|---|---|---|---|
| `article` | `:string` | no | — | seller article |
| `marketplace` | enum | no | — | source MP |
| `warehouse` | `:string` | yes | — | warehouse name/id |
| `quantity` | int/double | yes | units | available for sale |
| `quantity-full` | int/double | yes | units | total including reserved |
| `in-way-to` | int/double | yes | units | inbound shipment |
| `in-way-from` | int/double | yes | units | customer returns in transit |
| `nm-id`, `barcode`, `tech-size`, `subject`, `category`, `brand` | various | yes | — | catalog metadata |
| `id`, `synced-at` | int, string | yes | — | row id, last sync time |

### Invariants

- `quantity <= quantity-full` whenever both present.
- `quantity >= 0` always.

### Edge cases

- **Snapshot semantics** — old warehouses not in latest sync remain in the table until re-sync overwrites or manual cleanup. Consumers should filter by `synced-at >= now - 24h` if freshness matters.
- **Ozon `present` vs `reserved`** — transform subtracts reserved to get `quantity`. `quantity-full` preserves the raw total.
- **YM `warehouse` may be empty string** — YM exposes only FBY warehouses by id, not name.

### Known gaps

- No cross-MP warehouse ID unification — warehouse strings are MP-specific.
- No historical time series — consumer reports compute days-of-supply from sales history × current snapshot.
- **Tech-size** is WB-specific; nil for other MPs.

## cost_prices

### Purpose

Self-cost (COGS base) per article, imported from a 1C CSV export.
Used by Finance, P&L, and Unit Economics to compute gross profit.
Single-source (1C) — no marketplace dimension.

### Grain

One row = one (article, barcode) pair. Barcode may be empty string
to represent "article-level" cost (when 1C doesn't track per-barcode).

### Source mapping

| source | how | raw → normalized |
|---|---|---|
| 1C CSV | CLI import (`cost-price/load-from-1c`) or web upload | `Артикул` → `article`, `Штрихкод` → `barcode`, `Цена` → `cost-price`, `Номенклатура` → `nomenclature`, `Цвет` → `color`, `Количество` → `quantity-1c` |

### Field dictionary

| field | Malli type | nullable | unit | meaning |
|---|---|---|---|---|
| `article` | `:string` | no | — | seller article (must match article in finance/sales) |
| `barcode` | `:string` | no | — | product barcode; empty string if article-level |
| `cost-price` | int/double ≥ 0 | no | RUB | self-cost per unit |
| `nomenclature` | `:string` | yes | — | 1C product name |
| `color` | `:string` | yes | — | variant color |
| `quantity-1c` | int/double | yes | units | stock-on-hand per 1C at import time |
| `updated-at` | `:string` | yes | ISO timestamp | last import date |

### Invariants

- `cost-price >= 0` (never negative; cannot be zero in practice but schema allows it for "unknown cost" markers).
- PK `(article, barcode)` — one row per combo.

### Edge cases

- **BOM-prefixed 1C CSVs** — CSV loader strips UTF-8 BOM.
- **CSV delimiter** — 1C may emit `;` or `,`; loader auto-detects from header.
- **Decimal separator** — 1C uses `,` or `.` — loader normalizes to `.`.
- **Missing barcode** — loader writes empty string, not nil, to keep PK stable.
- **Fallback lookup** — if finance row's (article, barcode) has no cost_price, `cost_price/get-price` falls back to (article, "").

### Known gaps

- **No multi-version history** — a 1C re-import replaces old cost. Historical pricing for past periods is lost.
- **No marketplace dimension** — same cost applies to all MPs. Acceptable per current scope (1C is our single source of truth).
- **Atom-cached at load time** — `cost_price.clj` keeps prices in an atom. Rebuild on app restart requires explicit re-load.

## ad_stats

### Purpose

WB advertising campaign statistics per campaign per day per nm_id.
Used by Unit Economics as a legacy fallback path for `ad_spend_total`
when the canonical `finance.ad_cost` is not populated (see P&L §3.9).

### Grain

One row = one (campaign_id, date, nm_id) triple. `nm_id = 0` denotes
campaign-level rollup (multi-article campaigns with no per-article
breakdown from the API).

### Source mapping

| marketplace | endpoint | raw → normalized |
|---|---|---|
| WB | `/adv/v2/fullstats` | `campaignId` → `campaign-id`, `date` → `date`, `apps[*].nm` → `nm-id` (0 if absent), `views`/`clicks`/`ctr`/`cpc`/`sum` → same, `atbs`/`orders`/`cr`/`shks`/`sum_price` → same |

### Field dictionary

| field | Malli type | nullable | unit | meaning |
|---|---|---|---|---|
| `campaign-id` | int/double | no | — | WB campaign id |
| `date` | `:string` | no | ISO date | stat date |
| `nm-id` | int/double | no | — | WB article id; 0 = campaign-level |
| `views` | int/double | yes | count | impressions |
| `clicks` | int/double | yes | count | clicks |
| `ctr` | int/double | yes | % | views→clicks rate |
| `cpc` | int/double | yes | RUB | cost per click |
| `spend` | int/double | yes | RUB | ad spend for (campaign_id, date, nm_id) |
| `atbs` | int/double | yes | count | add-to-basket |
| `orders` | int/double | yes | count | orders attributed |
| `cr` | int/double | yes | % | clicks→orders rate |
| `shks` | int/double | yes | count | unique buyers |
| `sum-price` | int/double | yes | RUB | gross ad-attributed revenue |
| `synced-at` | `:string` | yes | ISO timestamp | last sync time |

### Invariants

- `(campaign-id, date, nm-id)` is unique (PK).
- `ctr` in [0, 100].
- `spend >= 0`.

### Edge cases

- **nm_id = 0 rows** represent campaigns with no per-article breakdown
  (multi-article campaign). Ad-cost allocation (spec 003 US5) treats
  these as allocable to covered articles proportionally by revenue.
- **No marketplace column** — ad_stats is WB-only; Ozon/YM don't go here.
- **Empty days** — WB skips days with zero impressions. Absence of a
  row means "no spend," not "unknown."

### Known gaps

- **Ozon advertising** lives in `finance.ad_cost` only; no per-campaign breakdown stored.
- **YM advertising** (bidFee) lives in `finance.ad_cost` only.
- **nm_id = 0** ambiguity: can mean "campaign-level" OR "transform couldn't resolve per-article". Historical rows may mix both meanings.

## paid_storage

### Purpose

WB paid-storage daily charge per (date, barcode, warehouse). Feeds
Unit Economics `storage_by_article` aggregate. One row per barcode
per warehouse per day.

### Grain

One row = one (date, barcode, warehouse, marketplace) quadruple.

### Source mapping

| marketplace | endpoint | raw → normalized |
|---|---|---|
| WB | `/api/v1/paid_storage` | `date` → `date`, `supplierArticle` → `article`, `barcode` → `barcode`, `warehouse` → `warehouse`, `warehousePrice` → `cost`, `volume` → `volume`, `barcodesCount` → `barcodes-count` |

### Field dictionary

| field | Malli type | nullable | unit | meaning |
|---|---|---|---|---|
| `date` | `:string` | no | ISO date | storage-charge date |
| `article` | `:string` | no | — | seller article |
| `barcode` | `:string` | no | — | barcode (empty string allowed) |
| `warehouse` | `:string` | no | — | warehouse name (empty string allowed) |
| `cost` | ≥0 int/double | no | RUB | storage charge for this (date, barcode, warehouse) |
| `marketplace` | enum | no | — | always `:wb` in practice |
| `nm-id`, `volume`, `barcodes-count` | various | yes | — | metadata |
| `synced-at` | `:string` | yes | ISO timestamp | last sync |

### Invariants

- `cost >= 0`.
- PK `(date, barcode, warehouse, marketplace)`.

### Edge cases

- **Empty barcode / warehouse** — WB returns `""` when it can't attribute. PK still holds because empty strings are distinct from NULL.
- **Back-filled dates** — WB paid-storage endpoint allows historical range queries; re-sync can overwrite rows for the same keys.

### Known gaps

- **Ozon per-barcode storage doesn't exist** — Ozon storage is reported only at period level in `cash_flow_periods.storage`. This table stays empty for Ozon.
- **YM has no per-SKU storage API** — same gap. YM storage is fully absent at per-article grain.
- **Volume units** — raw API returns liters; no conversion documented.
