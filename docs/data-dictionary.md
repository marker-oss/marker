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
- [stocks_history](#stocks_history) — daily snapshot of `stocks` (RFC-13)

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
| Ozon | `/v3/finance/transaction/list` + `/v2/finance/realization` | `amount` → `for-pay` (transaction) / `payout` → `for-pay` (realization), `operation_type` → `operation`, `services[*].name` → field-mapped cost per service type (see `transform.clj`), `commission_amount` → `mp-commission`, `sku` → `article` via `ozon_sku_map` |
| YM | `/campaigns/{id}/stats/orders` + `/reports/united-netting` | `status` → `operation` (`DELIVERED` → `sale`, `CANCELLED` → `cancel`, etc.), `netting.*` → for-pay, `bidFee` → `ad-cost`, order item `price * count` → `retail-amount` |

### Field dictionary

| field | Malli type | nullable | unit | enum | meaning |
|---|---|---|---|---|---|
| `marketplace` | `[:enum :wb :ozon :ym]` | no | — | wb/ozon/ym | source marketplace (canonical keyword) |
| `rrd-id` | `[:or :int :double]` | no | — | — | composite PK second half — MP-assigned or synthesized |
| `date-from` | `:string` | no | ISO date | — | reporting period start |
| `date-to` | `:string` | no | ISO date | — | reporting period end |
| `article` | `:string` | yes | — | — | seller-facing article (offer_id for Ozon, supplierArticle for WB) |
| `operation` | `:string` | no | — | sale/return + raw subtypes (legacy) | **deprecated** — kept for back-compat with rows materialised before 2026-04-28. New writers always set it to `(name operation-kind)`. |
| `operation-kind` | `[:enum :sale :return :service :adjustment]` | yes (during rollout) | — | sale/return/service/adjustment | **canonical** classifier (RFC-3 closed 2026-04-28). L2 formulas filter on this. `nil` only for legacy rows or unrecognised raw values; transform logs a warning. |
| `operation-subtype` | `:string` | yes | — | — | raw MP classifier preserved verbatim ("Логистика" / "Хранение" / "DELIVERED" / "MarketplaceServiceItemReturnAfterDelivery" / …). Used by audit + UI drill-down. |
| `quantity` | int/double | yes | units | — | units moved; **always ≥ 0** (RFC-14 closed 2026-04-28). Direction encoded in `operation-kind`. |
| `for-pay` | int/double | no | RUB | — | net payout from MP; **always ≥ 0** (RFC-15 closed 2026-04-28). Direction encoded in `operation-kind` (sale=+, return=−, service/adjustment=0). |
| `retail-price` | int/double | yes | RUB | — | list price per unit |
| `retail-amount` | int/double | yes | RUB | — | retail price × quantity before discounts |
| `sale-percent` | int/double | yes | % | — | discount pct applied |
| `commission-pct` | int/double | yes | % | — | MP commission as declared in report |
| `mp-commission` | int/double | yes | RUB | — | MP commission per row (RUB). WB: `ppvz_sales_commission`; Ozon: `commission_amount` from realization or `sale_commission` from transaction; YM: `commissions[type=FEE].actual`. Renamed from `wb-commission` 2026-04-28 (RFC-6). |
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
| `nm-id` | int/double | yes | — | — | **MP internal product id** — WB `nmId`, Ozon `sku`, YM `marketSku`. WB-prefixed name is a legacy from single-MP days; the field is cross-MP. Rename to `mp-internal-id` was considered (RFC-1) but rejected 2026-04-28 due to ~256 cross-cutting references; semantics anchored here. |
| `barcode` | `:string` | yes | — | — | product barcode if available |
| `subject`, `brand`, `doc-type` | `:string` | yes | — | — | catalog metadata |
| `synced-at` | `:string` | yes | ISO timestamp | — | when sync wrote this row |

### Invariants

- `for-pay` is non-nil and **≥ 0** for rows produced after 2026-04-28 (RFC-15). Sign carried by `operation-kind`. Legacy rows may still hold negative values until re-materialised.
- `quantity` is **≥ 0** for rows produced after 2026-04-28 (RFC-14). Direction carried by `operation-kind`. Legacy rows may still hold negative values.
- For WB, `rrd-id` is always a positive integer.
- `marketplace` must be one of `:wb` `:ozon` `:ym`. Stale string values are rejected by schema.
- `operation-kind` is a keyword from `{:sale :return :service :adjustment}` (or `nil` for unrecognised raw values; the transform logs `::wb-unknown-op` / `::ym-unknown-status`).

### Edge cases

- **Legacy rows** materialised before 2026-04-28 retain raw signs (negative `quantity` / `for-pay` for returns) and `nil` `operation-kind`. Domain-layer predicates fall back to the legacy `:operation` string set (`#{"sale" "Продажа"}` / `#{"return" "Возврат"}`) so reports remain consistent until re-materialisation.
- **WB service rows** ("Логистика", "Хранение", "Платная приёмка", …) carry `operation-kind = :service`, `for-pay = 0`; the actual money lives in `delivery-cost` / `storage-fee` / `acceptance`.
- **WB adjustment rows** ("Компенсация ущерба", "Корректировка вознаграждения", "Штраф", …) carry `operation-kind = :adjustment`, `for-pay = 0`; cash impact lives in `additional-payment` / `penalty` / `deduction`.
- **Ozon cancellations** appear as transactions with `operation_type = OperationTypeRefund` — normalized to `operation-kind = :return` by `ozon/transform.clj`.
- **Ozon per-article service costs** live as separate rows with `quantity = 0` or `nil` and a single cost field populated (e.g. `delivery-cost`). See B-009 in `specs/002-calculation-audit/verdicts.md`.
- **YM cancelled orders** carry `operation-kind = :adjustment` with `for-pay = 0`; the bidFee loss lives in `ad-cost` (and any commissions still charged in `mp-commission` / `delivery-cost`). L2 `mp_payout` ignores adjustment rows.
- **Multi-article ad campaigns** (WB) allocate `ad-cost` proportionally to revenue per article (spec 003 US5).
- **Timezone:** all dates are in Moscow TZ as emitted by MPs; no UTC conversion.
- **`as-kebab-maps` namespacing:** `next.jdbc.result-set/as-kebab-maps` returns keys namespaced by table (`:finance/rrd-id`). Callers must strip the namespace qualifier before passing rows to `validate-rows`. See `strip-ns` helper in `finance_test.clj`.

### Price-field semantics (RFC-4 closed 2026-04-28)

Each MP has its own native «revenue base» convention. The L1 fields capture each MP's most natural value; full cross-MP alignment is **not** achievable without losing precision:

| Field | WB | Ozon | YM |
|---|---|---|---|
| `retail-price` | `retail_price` (gross list per unit, before any discount) | `seller_price_per_instance` (per-unit after seller discount) | `BUYER costPerItem` (per-unit post all discounts incl. coupons) |
| `retail-amount` | `retail_amount` (gross list × qty) | `q × delivery_commission.total` (total accrued to seller) | `BUYER total` (= buyer-paid × qty, post-coupon) |
| `price-with-disc` | `retail_price_withdisc_rub` (post-seller, pre-MP) | nil | nil |

**L2 `revenue := SUM(retail-amount)` interpretation**: «sum the seller's books recognise as gross sale before commissions and refunds» — *not* literally what buyers paid (WB's SPP and Ozon's bonuses are MP-side subsidies, not in retail-amount). For exact buyer-paid amounts, use endpoint-specific data (WB `supplier/sales.finishedPrice`, YM `prices[BUYER]`, Ozon `accruals_for_sale`).

### Known gaps

- ~~`wb-commission` field is reused by Ozon `transform.clj` to carry `commission_amount` from realization — semantically the same (MP commission in RUB) but the WB-prefixed name is misleading.~~ **Closed 2026-04-28 (RFC-6)** — renamed to `mp-commission`; DB column auto-migrates via `ALTER TABLE … RENAME COLUMN` on `init!`.
- **RFC-4 closed 2026-04-28 via documentation** — `retail-price` / `retail-amount` semantics differ per MP by design (WB gross / Ozon accrual / YM buyer-paid). Documented above; no field changes required.
- **RFC-5 closed 2026-04-28 (no decomposition)** — MP-sponsored discounts are heterogeneous: WB СПП is buyer-side (no impact on seller `for-pay`); Ozon `bonus`/`bank_coinvestment`/`stars`/`pick_up_point_coinvestment` and YM `subsidies[*]` are seller-side and **already included in `for-pay`**. WB SPP as % is in `:spp-prc`. No unified L1 sponsored-discount field. For per-source breakdown, query MP-specific raw fields.
- **`operation-kind` is currently optional** in the Malli schema. It becomes required once the existing finance table is re-materialised end-to-end. Tracked as part of the RFC-3 rollout.
- **`for-pay` and `quantity` schema invariants are not yet `[:>= 0]`** — legacy rows in the table may still violate. Schema tightening is deferred until backfill ships.
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

## region_sales

### Purpose

Per-region, per-period sales aggregates. Feeds Geography report.
This is a pre-aggregated snapshot — one row per (period, article, region, city)
rather than per-event.

### Grain

One row = one (date_from, date_to, nm_id, region, city) tuple.

### Source mapping

| marketplace | endpoint | raw → normalized |
|---|---|---|
| WB | `/api/v5/supplier/reportSalesByRegion` | `regionName` → `region`, `cityName` → `city`, `countryName` → `country`, `quantity` → `qty`, `priceWithDisc` → `sum-price`, `ppvz_for_pay_sum` → `sum-price-prc`, `nmID` → `nm-id` |
| Ozon | — | (not currently synced per-region; rows stay empty for Ozon) |
| YM | — | (not currently synced per-region) |

### Field dictionary

| field | Malli type | nullable | unit | meaning |
|---|---|---|---|---|
| `nm-id` | int/double | no | — | WB article numeric id (0 = rollup) |
| `article` | `:string` | no | — | seller article (empty = rollup) |
| `region` | `:string` | no | — | federal subject name |
| `city` | `:string` | no | — | city name |
| `date-from` | `:string` | no | ISO date | period start |
| `date-to` | `:string` | no | ISO date | period end |
| `country` | `:string` | yes | — | country (usually `Россия`) |
| `fo` | `:string` | yes | — | federal district (округ) |
| `qty` | int/double | yes | units | units sold in region |
| `sum-price` | int/double | yes | RUB | gross revenue in region |
| `sum-price-prc` | int/double | yes | RUB | seller payout in region |
| `synced-at` | `:string` | yes | ISO timestamp | last sync |

### Invariants

- `qty >= 0` when present.
- PK `(date_from, date_to, nm_id, region, city)`.

### Edge cases

- **Empty region/city rows** exist for period-rollup aggregations.
- **Cross-period overlaps** — consumer must filter by exact `date_from`/`date_to` match, not range.

### Known gaps

- **Only WB supplies regional data.** Ozon / YM regional cuts would require separate endpoints; currently out of scope.
- **No marketplace column** — table is implicitly WB-only. Future MP coverage should add this.
- **No mapping to standardized region codes** (e.g. OKATO); strings are free-form from API.

## cash_flow_periods

### Purpose

Period-level cash flow statement from Ozon `/v1/finance/cash-flow-statement`.
Used by P&L to compensate per-row `finance_rows` accounting for
account-level charges that don't map to individual SKUs (e.g. storage,
fines, platform subscription fees).

### Grain

One row = one (source, period_begin, period_end) tuple — typically one
Ozon weekly settlement period.

### Source mapping

| marketplace | endpoint | raw → normalized |
|---|---|---|
| Ozon | `/v1/finance/cash-flow-statement` | `period.begin` → `period-begin`, `period.end` → `period-end`, `orders_amount` → `orders-amount`, `returns_amount` → `returns-amount`, `commission_amount` → `commission-amount`, `delivery_amount`/`delivery_logistics` → same, `storage` → `storage`, `fines` → `fines`, `other_services` → `other-services`, `acquiring` → `acquiring`, `corrections` → `corrections`, `compensation` → `compensation`, `payment` → `payment`, `begin_balance`/`end_balance` → same |

### Field dictionary

All amount fields: `num-default-zero` (REAL with DEFAULT 0 in DDL, so
schema accepts both nil from app layer and 0 from DB).

| field | Malli type | nullable | unit | meaning |
|---|---|---|---|---|
| `source` | `[:enum :ozon]` | no | — | fixed to `:ozon` today |
| `period-begin` | `:string` | no | ISO date | settlement period start |
| `period-end` | `:string` | no | ISO date | settlement period end |
| `synced-at` | `:string` | no | ISO timestamp | when row was last synced |
| `id` | int/double | yes | — | DB autoincrement |
| `orders-amount` | RUB | yes | RUB | gross order amount in period |
| `returns-amount` | RUB | yes | RUB | returns amount |
| `commission-amount` | RUB | yes | RUB | MP commission total |
| `delivery-amount` | RUB | yes | RUB | delivery income |
| `delivery-logistics` | RUB | yes | RUB | delivery cost |
| `return-amount`, `return-logistics` | RUB | yes | RUB | return cash/logistics |
| `storage` | RUB | yes | RUB | storage charges |
| `packaging` | RUB | yes | RUB | packaging |
| `warehouse-movement` | RUB | yes | RUB | FBO warehouse transfers |
| `returns-cargo` | RUB | yes | RUB | returns freight |
| `subscription` | RUB | yes | RUB | Ozon Premium / platform fees |
| `fines` | RUB | yes | RUB | fines |
| `other-services` | RUB | yes | RUB | misc services |
| `acquiring` | RUB | yes | RUB | payment-processing fee |
| `corrections`, `compensation` | RUB | yes | RUB | MP adjustments |
| `payment` | RUB | yes | RUB | payout to seller |
| `begin-balance`, `end-balance` | RUB | yes | RUB | period opening/closing balance |
| `invoice-transfer` | RUB | yes | RUB | invoice transfers |

### Invariants

- `period-begin <= period-end` (ISO date string comparison).
- `UNIQUE(source, period_begin, period_end)`.

### Edge cases

- **Overlapping periods shouldn't happen** but are not enforced at DB level beyond the UNIQUE constraint.
- **Ozon sign convention**: some amounts (commission_amount, fines) arrive positive from API but represent costs to the seller. `domain/pnl.clj/load-cf-adjustments` subtracts them explicitly — canon is "positive amount = positive absolute value of cost / revenue, sign applied by consumer."
- **`begin-balance` / `end-balance`** are self-reconciling across contiguous periods; gaps indicate missing sync windows.

### Known gaps

- **WB has no equivalent endpoint** — WB provides settlement data per-row in `finance`, not per-period summary. `cash_flow_periods` stays Ozon-only for now.
- **YM has reports but no period-level cash-flow statement** — same situation.
- **Ozon partial months**: fields like `storage` cover only the reported period; for a full-month P&L you must sum multiple period rows.

## stocks_history

### Purpose

Daily per-(article, warehouse, marketplace) snapshot of stock levels.
Where `stocks` is overwritten on every sync, `stocks_history` accumulates
one row per `(snapshot_date, marketplace, article, warehouse)` so we can
compute trends, velocity, and days-of-supply.

Closed RFC-13 2026-04-28. Population is **forward-looking only** — we
cannot reconstruct historic snapshots before the table existed.

### Grain

One row = one (snapshot_date, marketplace, article, warehouse) tuple.

### Source mapping

| source | how | raw → normalized |
|---|---|---|
| Daily snapshot | `analitica.materialize/snapshot-stocks-history!` | reads from `stocks` table; same row shape with `:snapshot-date` added |

Future versions may also pull from MP-specific historic endpoints:
- WB `analytics/v1/stocks-report/wb-warehouses` (real history)
- Ozon `analytics/turnover/stocks` (turnover history)
- YM `reports/goods-turnover/generate` (downloadable XLSX, no OpenAPI)

### Field dictionary

| field | Malli type | nullable | unit | meaning |
|---|---|---|---|---|
| `snapshot-date` | `:string` | no | ISO date | day the snapshot was taken |
| `marketplace` | enum `:wb :ozon :ym` | no | — | source marketplace |
| `article` | `:string` | no | — | seller article |
| `warehouse` | `:string` | no | — | warehouse name (empty string `""` if absent — keeps PK stable) |
| `quantity` | int/double | yes | units | available for sale at snapshot time |
| `quantity-full` | int/double | yes | units | total including reserved |
| `in-way-to` | int/double | yes | units | inbound shipments |
| `in-way-from` | int/double | yes | units | customer returns in transit |
| `nm-id`, `barcode`, `tech-size`, `subject`, `brand` | various | yes | — | catalog metadata captured at snapshot |
| `synced-at` | `:string` | yes | ISO timestamp | when the snapshot was written |

### Invariants

- `quantity ≥ 0` (snapshots can't be negative).
- PK `(snapshot_date, marketplace, article, warehouse)` — INSERT OR IGNORE is used so re-running the snapshot for today is a no-op.

### Edge cases

- **Empty `warehouse`**: WB-cross-warehouse rows or test data may have warehouse = `""`. Stored as empty string (not NULL) so PK stays usable.
- **Same article in `stocks` multiple times** (legacy duplicates from re-syncs): IGNORE keeps only the first; this is acceptable for trend purposes since we only care about the canonical level per warehouse.
- **Backfill is impossible** — until snapshot has been running for `D` days, queries with windows > `D` return partial results.

### Known gaps

- **MP-native history endpoints not consumed** — current implementation just snapshots local `stocks`. Full-fidelity historical sync (e.g., WB stocks-report-wb-warehouses for past months) would require a separate ingest path. Deferred until business need.
