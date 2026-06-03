# Marker

Marketplace analytics tool for e-commerce sellers. Built in Clojure.

Supports **Wildberries**, **Ozon**, and **Yandex.Market** through a unified protocol-based architecture. Collects data from marketplace APIs, stores in SQLite, generates financial reports, and provides a web dashboard with interactive charts and tables. Integrates with 1C for cost price data.

## Features

- **Multi-Marketplace** — unified protocol for WB, Ozon, and Yandex.Market
- **Web Dashboard** — HTMX-powered UI with Chart.js visualizations and Tabulator interactive tables
- **Sales Dashboard** — revenue, orders, returns by day/article/category/warehouse
- **Financial Report** — marketplace financial breakdown per article (commission, logistics, storage, penalties)
- **Unit Economics** — profit per SKU with cost price from 1C; filterable by marketplace and article
- **P&L Report** — full profit & loss statement
- **ABC Analysis** — categorize products by revenue/profit (80/15/5 rule)
- **Stock & Turnover** — stock levels, turnover rate, out-of-stock risk
- **Returns Analysis** — return rates by article, dynamics
- **Buyout Rate** — which products get returned most
- **Geography** — sales by region and city
- **Trend Analysis** — WoW and MoM comparisons, daily dynamics
- **Price Monitoring** — current prices and discounts
- **Sales Funnel** — views, cart, orders, buyouts, conversion rates
- **Excel/CSV Export** — all reports exportable from both CLI and web UI
- **Calculation Audit** — reconciliation vs. bank statement, Accuracy KPI baseline (WB), hypothesis verdicts, ground-truth fixtures for regression
- **Per-Article Ad Cost** — unified FinanceRow `:ad-cost` slot populated from YM `bidFee`, Ozon promotion transactions, and WB ad_stats allocated proportionally to article revenue

## Requirements

- Java 11+
- [Clojure CLI](https://clojure.org/guides/install_clojure)
- Wildberries API token (from seller cabinet)
- (Optional) Ozon Client ID + API Key
- (Optional) Yandex.Market API Key + Campaign ID + Business ID

## Setup

1. Clone the repository
2. Copy config template:
   ```bash
   cp config.example.edn config.edn
   ```
3. Edit `config.edn` with your API credentials:
   ```clojure
   {:marketplaces
    {:wb   {:api-token   "YOUR_WB_TOKEN"
            :rate-limits {:statistics 1, :analytics 3, :advert 300,
                          :marketplace 300, :content 100, :prices 100}}

     :ozon {:client-id   "YOUR_OZON_CLIENT_ID"      ;; optional
            :api-key     "YOUR_OZON_API_KEY"
            :rate-limits {:default 60}}

     :ym   {:oauth-token  "YOUR_YM_API_KEY"          ;; optional
            :campaign-id  "YOUR_CAMPAIGN_ID"
            :business-id  "YOUR_BUSINESS_ID"
            :rate-limits  {:default 600}}}

    :cost-prices
    {:source :edn
     :path   "data/cost-prices.edn"}}
   ```
   Ozon and YM sections are optional — remove them if you only sell on Wildberries.
4. (Optional) Place 1C cost price export at `1c/units.csv`

## Usage

### Web UI

```bash
clojure -M -m analitica.web.server
# Opens at http://localhost:3000
```

Pages:
- `/` — dashboard: KPIs, revenue chart, marketplace breakdown
- `/sync` — sync management: trigger sync, live SSE progress log, data coverage table
- `/reports/sales` — sales dynamics (line chart + table)
- `/reports/finance` — financial breakdown per article
- `/reports/ue` — unit economics per article; filterable by marketplace and article
- `/reports/pnl` — P&L statement
- `/reports/abc` — ABC analysis with pareto curve
- `/reports/stock` — stock levels by warehouse
- `/reports/returns` — return rates
- `/reports/buyout` — buyout rates by article
- `/reports/geo` — geography of sales
- `/reports/trends` — WoW/MoM trend comparison

All report pages include period and marketplace filters, an Excel/CSV export button, an interactive Tabulator table (sortable, filterable, paginated), and a Chart.js visualization. Filters update the table via HTMX without a full page reload.

### CLI

```bash
# Sync all data for last 30 days
clojure -M -m analitica.cli sync all -p last-30-days

# Sync specific data
clojure -M -m analitica.cli sync finance -f 2026-03-01 -t 2026-03-31
clojure -M -m analitica.cli sync stocks
clojure -M -m analitica.cli sync 1c

# Ingest raw data and materialize analytical tables separately
# (ingest = API → raw_data; materialize = raw_data → analytical tables)
clojure -M -m analitica.cli ingest finance -f 2026-03-01 -t 2026-03-31 -m ozon
clojure -M -m analitica.cli materialize finance -m ozon
# WB advertising stats (required input for :ad-cost allocation on WB)
clojure -M -m analitica.cli ingest ad-stats -p last-30-days -m wb
clojure -M -m analitica.cli materialize ad-stats -m wb

# Generate reports
clojure -M -m analitica.cli report pnl -f 2026-03-01 -t 2026-03-31
clojure -M -m analitica.cli report sales -p last-7-days
clojure -M -m analitica.cli report ue -p last-30-days

# Export to Excel
clojure -M -m analitica.cli report sales -p last-30-days -e reports/sales.xlsx
clojure -M -m analitica.cli report pnl -f 2026-03-01 -t 2026-03-31 -e reports/pnl.xlsx

# Calculation audit: reconciliation, KPI, hypothesis verdicts, ground-truth fixtures
# (see specs/002-calculation-audit/contracts/cli-audit.md)
clojure -M -m analitica.cli audit help
clojure -M -m analitica.cli audit reconcile -m wb -p last-30-days --bank-sum 1500000
clojure -M -m analitica.cli audit kpi measure -m wb -f 2026-03-01 -t 2026-03-31
clojure -M -m analitica.cli audit kpi list
clojure -M -m analitica.cli audit kpi show --latest
clojure -M -m analitica.cli audit verdict list
clojure -M -m analitica.cli audit verdict show B-003
clojure -M -m analitica.cli audit fixture capture -m wb -p last-30-days --name wb-march-2026
clojure -M -m analitica.cli audit fixture list
clojure -M -m analitica.cli audit fixture verify --name wb-march-2026

# Interactive menu
clojure -M -m analitica.cli menu

# Check database status
clojure -M -m analitica.cli status
```

### REPL

```clojure
(require '[analitica.core :refer :all])
(start!)
(help)    ;; show all available commands

;; Sync data
(sync/sync! :all :last-30-days)
(sync/sync! :finance :last-30-days :marketplace :ozon)
(sync/sync! :finance :last-30-days :marketplace :ym)
(sync/sync! :1c)

;; Reports (nil marketplace = all marketplaces)
(sales/dashboard :last-7-days)
(finance/report {:from "2026-03-01" :to "2026-03-31"})
(ue/report :last-30-days)
(ue/report :last-30-days :marketplace :ozon)
(pnl/report :last-30-days)
(pnl/report :last-30-days :marketplace :ym)
(abc/report :last-30-days)
(returns/report :last-30-days)
(stock/overview)
(stock/risk 14)
(trends/wow)
(trends/mom)
(buyout/report :last-30-days)

;; Export
(sales/export-excel :last-30-days "reports/sales.xlsx")
(pnl/export-excel :last-30-days "reports/pnl.xlsx")
(ue/export-excel :last-30-days "reports/ue.xlsx")
```

### Period formats

Reports and sync commands accept periods as keywords or date maps:

| Keyword | Meaning |
|---------|---------|
| `:today` | Current day |
| `:yesterday` | Previous day |
| `:last-7-days` | Last 7 days |
| `:last-30-days` | Last 30 days |
| `:this-week` | Monday to today |
| `:this-month` | 1st of month to today |

Custom range: `{:from "2026-03-01" :to "2026-03-31"}`

## Data Sources

| Source | What | Key Endpoints |
|--------|------|---------------|
| **Wildberries** | Sales, orders, stocks, finance, product stats, storage, regions, prices, ads | `/api/v1/supplier/*`, `/api/v5/supplier/reportDetailByPeriod`, `/api/v2/nm-report/*` |
| **Ozon** | FBO/FBS orders, per-article realization (finance), cash-flow, stocks, analytics, storage costs, prices | `/v3/posting/fbo/list`, `/v2/finance/realization`, `/v1/finance/cash-flow-statement/list`, `/v2/analytics/data` |
| **Yandex.Market** | Orders, order stats (commissions), stocks | `/campaigns/{id}/stats/orders`, `/campaigns/{id}/orders` |
| **1C Export** | Cost prices by article/barcode | `1c/units.csv` |

## Architecture

```
WB API   ──►  wb/transform.clj    ──►┐
Ozon API ──►  ozon/transform.clj  ──►├──  SQLite  ──►  domain/*.clj  ──►  Web API / CLI / REPL
YM API   ──►  ym/transform.clj    ──►┘         │
1C CSV   ──►  cost_price.clj      ────────────►┘
```

**Layers:**

- **Marketplace layer** (`marketplace/`) — protocol-based abstraction; each marketplace has a `client` (HTTP + rate limiting), `api` (endpoint wrappers), `transform` (raw JSON → domain model), and `impl` (protocol implementation)
- **Storage layer** (`db.clj`) — SQLite via next.jdbc; schema, DDL migrations, insert-batch!, typed query helpers
- **Domain layer** (`domain/`) — pure business logic; all functions accept either raw API data or fetch from DB; marketplace-agnostic calculations
- **Report layer** (`report/`) — console table formatting and Excel/CSV export via docjure
- **Web layer** (`web/`) — Ring/Compojure server, Hiccup HTML generation, HTMX-driven filters, Chart.js + Tabulator via CDN
- **Sync layer** (`sync.clj`) — orchestrates API fetches and DB writes; supports incremental sync per marketplace and data type

**Key design decisions:**

- `nil` marketplace = all marketplaces (no WHERE clause in SQL); specific marketplace = filtered
- Normalized operations: all marketplaces map to `"sale"` / `"return"` / `"other"`
- Article-based cost matching: 1C cost prices mapped by article (with color) or barcode
- Built-in token-bucket rate limiter per API section
- HTMX `hx-include` wires all filter inputs together; updates only `#report-content` fragment

## Project Structure

```
src/analitica/
├── core.clj                    # REPL entry point, start!/stop!, help
├── cli.clj                     # CLI interface (commands + interactive menu)
├── config.clj                  # Configuration loader (.env + config.edn via Aero)
├── db.clj                      # SQLite schema, DDL migrations, query helpers
├── sync.clj                    # API → SQLite synchronization orchestrator
│
├── marketplace/
│   ├── protocol.clj            # MarketplaceAPI protocol (8 methods)
│   ├── registry.clj            # Active marketplace instance registry
│   ├── wb/                     # Wildberries
│   │   ├── client.clj          # HTTP client with per-section rate limiting
│   │   ├── api.clj             # Endpoint wrappers (sales, finance, stocks, ads, etc.)
│   │   ├── transform.clj       # Raw JSON → unified domain model
│   │   └── impl.clj            # MarketplaceAPI protocol implementation
│   ├── ozon/                   # Ozon (FBO + FBS)
│   │   ├── client.clj          # HTTP client (Client-Id / Api-Key auth)
│   │   ├── api.clj             # Orders, finance, stocks, analytics, storage, prices
│   │   ├── transform.clj       # Data transformation with SKU/FBO-FBS resolution
│   │   └── impl.clj            # Protocol implementation
│   └── ym/                     # Yandex.Market (FBS)
│       ├── client.clj          # HTTP client (Api-Key auth)
│       ├── api.clj             # Orders, order-stats, stocks
│       ├── transform.clj       # order-stats commissions → finance model
│       └── impl.clj            # Protocol implementation
│
├── domain/                     # Business logic (pure functions or DB-backed)
│   ├── sales.clj               # Sales dashboard: by-day, by-article, by-category
│   ├── finance.clj             # Financial report: by-article with full cost breakdown
│   ├── unit_economics.clj      # Unit economics per SKU (profit, margin, cost matching)
│   ├── pnl.clj                 # P&L statement: revenue → net profit waterfall
│   ├── abc.clj                 # ABC analysis: A/B/C categorization by revenue/profit
│   ├── stock.clj               # Stock levels, turnover rate, out-of-stock risk
│   ├── returns.clj             # Return rates by article and dynamics
│   ├── buyout.clj              # Buyout rate analysis by article
│   ├── geography.clj           # Sales by region and city
│   ├── trends.clj              # WoW / MoM / daily trend comparisons
│   ├── ads.clj                 # Sales funnel: views → cart → orders → buyouts
│   ├── prices.clj              # Current prices and discounts monitoring
│   └── cost_price.clj          # 1C cost price loader (CSV / EDN formats)
│
├── report/
│   ├── table.clj               # ASCII table formatting for REPL/CLI output
│   └── export.clj              # Excel (.xlsx) and CSV export via docjure
│
├── util/
│   ├── http.clj                # HTTP client wrapper + token-bucket rate limiter
│   ├── time.clj                # Date/period helpers, period keyword → [from to]
│   └── math.clj                # Rounding, percentages, safe division
│
└── web/
    ├── server.clj              # Ring/Compojure routes, Jetty server, validation
    ├── layout.clj              # Base HTML layout (Tailwind, HTMX, Chart.js, Tabulator)
    ├── components.clj          # Reusable Hiccup UI components (metric cards, charts, tables)
    ├── pages/
    │   ├── dashboard.clj       # Dashboard page: KPI cards, sales chart, marketplace shares
    │   ├── reports.clj         # Unified report page template for all 10 report types
    │   └── sync.clj            # Sync management page: trigger controls + live progress log
    └── api/
        ├── report.clj          # /api/report/:type — table data (JSON) for Tabulator
        ├── charts.clj          # /api/chart/* — Chart.js datasets for all report types
        ├── export.clj          # /api/export/:type — Excel/CSV file download
        ├── metrics.clj         # /api/metrics/* — dashboard KPIs and data coverage stats
        └── sync.clj            # /api/sync — SSE streaming sync trigger
```

## Module Descriptions

### Entry points

| Module | Description |
|--------|-------------|
| `core.clj` | REPL namespace: calls `(start!)` to init DB and load config, exposes `help` to list all available commands, re-exports key domain vars for interactive use |
| `cli.clj` | CLI interface built on `tools.cli`; commands: `sync`, `report`, `status`, `menu`; the interactive `menu` uses a numbered prompt loop |
| `config.clj` | Loads `config.edn` via Aero (supports `#env` tag); merges `.env` file for API tokens; exposes `marketplace-config` and `cost-price-config` |
| `db.clj` | SQLite schema (10 tables + indexes), DDL migrations (with in-place schema upgrades for `product_stats` and `prices`), `insert-batch!` (chunked multi-row INSERT OR REPLACE), `storage-by-article` and `ad-spend-by-article` aggregate queries |
| `sync.clj` | Orchestrates incremental sync: fetches from marketplace API, transforms, writes to DB; supports `:all`, `:finance`, `:sales`, `:stocks`, `:product-stats`, `:ad-stats`, `:storage`, `:regions`, `:prices`, `:1c` |

### Marketplace layer

Each marketplace follows the same 4-file pattern:

| File | Role |
|------|------|
| `client.clj` | Low-level HTTP with auth headers and per-section token-bucket rate limiting |
| `api.clj` | One function per API endpoint; handles pagination and date chunking |
| `transform.clj` | Converts raw API response maps to the unified domain model (kebab-case keys, normalized operations) |
| `impl.clj` | Implements `MarketplaceAPI` protocol: `fetch-sales`, `fetch-orders`, `fetch-finance`, `fetch-stocks`, `fetch-product-stats`, `fetch-ad-stats`, `fetch-storage`, `fetch-prices` |

`protocol.clj` defines the 8-method `MarketplaceAPI` protocol. `registry.clj` holds a map of `{:wb <impl> :ozon <impl> :ym <impl>}` initialized at startup.

### Domain layer

All domain functions follow the same convention: accept either raw data (from API or DB query) or fetch from DB when called with `:source :db`. `nil` marketplace = all marketplaces combined.

| Module | Key functions | Notes |
|--------|--------------|-------|
| `sales.clj` | `fetch-sales`, `by-day`, `by-article`, `by-category`, `dashboard` | Splits sales/returns by `type` field |
| `finance.clj` | `fetch-finance`, `by-article`, `totals` | Aggregates commission, logistics, storage, for-pay per article |
| `unit_economics.clj` | `calculate`, `report`, `export-excel` | Joins finance data with cost prices; per-unit profit, margin, buyout rate |
| `pnl.clj` | `calculate`, `report` | Single-row P&L: revenue → commission → logistics → storage → COGS → ads → net profit |
| `abc.clj` | `analyze-by`, `summary`, `report` | Sorts by criterion (`:revenue` or `:profit`), assigns A/B/C by 80/15/5 cumulative % |
| `stock.clj` | `fetch-stocks`, `by-article`, `by-warehouse`, `with-turnover`, `overview`, `risk` | Turnover = sales velocity / stock; risk = days until stockout |
| `returns.clj` | `by-article`, `report` | Return rate = returned / (sold + returned) |
| `buyout.clj` | `analyze`, `report` | Buyout rate from `product_stats`: buyouts / orders |
| `geography.clj` | `fetch-regions`, `by-region`, `report` | Aggregates qty and sum by region |
| `trends.clj` | `wow`, `mom`, `daily` | Compares current vs. prior period; delta and delta% for key metrics |
| `ads.clj` | `funnel`, `report` | Views → add-to-cart → orders → buyouts conversion funnel |
| `prices.clj` | `fetch-prices`, `report` | Current price, discount, club discount per article |
| `cost_price.clj` | `load-cost-prices`, `match-cost` | Loads from CSV or EDN; matches by article+color or barcode |

### Report layer

| Module | Description |
|--------|-------------|
| `report/table.clj` | ASCII table renderer for REPL and CLI output; auto-sizes columns |
| `report/export.clj` | Excel export via docjure (`dk.ative/docjure`): creates workbook, styles header row, auto-sizes columns; also CSV export via `clojure.data.csv` |

### Web layer

| Module | Description |
|--------|-------------|
| `web/server.clj` | Compojure routes + Jetty server; validates `period` and `marketplace` params (400 on invalid); `marketplace=all` and `marketplace=nil` both mean "all marketplaces"; serves HTML pages and JSON API |
| `web/layout.clj` | Base HTML5 page layout: loads Tailwind CSS, HTMX, Chart.js, and Tabulator from CDN; renders navigation sidebar; wraps page content |
| `web/components.clj` | Reusable Hiccup components: `metric-card` (KPI with WoW delta badge), `chart-container` (Chart.js canvas with JS init), `tabulator-table` (Tabulator init with server-side JSON URL, column defs, frozen first column, pagination) |
| `web/pages/dashboard.clj` | Dashboard: summary KPI cards (revenue, orders, returns, profit), daily sales line chart, marketplace revenue donut chart |
| `web/pages/reports.clj` | Unified report page template for all 10 report types; renders period + marketplace + article (UE only) filters with HTMX; export buttons; chart container; Tabulator table; no-data banner |
| `web/pages/sync.clj` | Sync page: marketplace + period selectors, sync-type buttons, live progress log via SSE (`hx-ext="sse"`), data coverage table showing row counts and date ranges per table |
| `web/api/report.clj` | `GET /api/report/:type` — returns JSON array for Tabulator; dispatches by report type; supports `?period`, `?marketplace`, `?article` (UE only), `?trend-type` (trends only) |
| `web/api/charts.clj` | `GET /api/chart/report` and `GET /api/chart/share` — returns Chart.js-compatible `{:labels [...] :datasets [...]}` maps for each report type |
| `web/api/export.clj` | `GET /api/export/:type` — streams Excel or CSV file download; reuses `report-data` for data and `report/export.clj` for serialization |
| `web/api/metrics.clj` | `GET /api/metrics/*` — dashboard KPI values (revenue, orders, profit, returns, WoW deltas) and data coverage stats (row count + date range per DB table) |
| `web/api/sync.clj` | `POST /api/sync` — triggers sync in a background thread; streams progress lines as Server-Sent Events; enforces single concurrent sync via atom |

## Database

SQLite database (`analitica.db`) with the following tables:

| Table | Purpose |
|-------|---------|
| `sales` | Individual sale and return transactions |
| `orders` | Orders with status |
| `finance` | Financial breakdown per article (commission, logistics, storage, penalties, for-pay) |
| `stocks` | Current stock levels by warehouse |
| `cost_prices` | Cost prices loaded from 1C (by article + barcode) |
| `product_stats` | Product funnel metrics (views, add-to-cart, orders, buyouts) per period |
| `prices` | Current prices and discounts |
| `ad_stats` | Advertising campaign metrics (spend, clicks, CTR, orders) |
| `region_sales` | Sales aggregated by region and city |
| `paid_storage` | Daily storage costs by barcode and warehouse |

All tables include a `marketplace` column. `nil` marketplace in queries = no WHERE filter = all rows across all marketplaces.

## API Schema Validation

Marketplace API responses are validated against Malli contracts before analytical
aggregation, catching schema drift as soon as raw data arrives. Contracts live as
EDN files under `resources/schemas/<marketplace>/<endpoint>.edn` and are loaded
into an in-memory registry at CLI startup.

Validation is **opt-in per endpoint** — only endpoints with a registered contract
are validated; others pass through unchanged. Critical violations (missing
required fields, type mismatches) abort the ingest with an `ex-info` diagnostic;
extra fields produce a warning via mulog but do not block.

CLI:

```bash
# List registered schemas
clojure -M -m analitica.cli schema list

# Show full schema for an endpoint
clojure -M -m analitica.cli schema show :wb/report-detail-by-period

# Diff current contract vs. the last N raw samples
clojure -M -m analitica.cli schema diff :wb/report-detail-by-period --sample 20

# Regenerate from upstream OpenAPI (WB/YM only; Ozon has no public spec)
clojure -M -m analitica.cli schema regenerate --marketplace wb            # dry-run
clojure -M -m analitica.cli schema regenerate --marketplace wb --apply    # write back

# Infer a candidate schema from raw samples (starting point for new endpoints)
clojure -M -m analitica.cli schema infer :ozon/new-endpoint --sample 10 --out /tmp/inferred.edn
```

Raw API responses are **always persisted to `raw_data` before validation**, so
diagnostic post-mortems remain possible even when validation aborts a run.

MVP contracts (v1):
- `:wb/report-detail-by-period` — detailed finance report
- `:ozon/finance-realization` — monthly per-article realization (primary Ozon P&L source since 2026-04-22)
- `:ozon/cash-flow-statement` — period-level cash-flow (validation reference)
- `:ym/order-stats` — delivered order stats with commission breakdown

See [specs/001-openapi-schemas/](specs/001-openapi-schemas/) for the full design.

## Calculation Audit

The `audit` CLI subcommand tree validates analytical outputs against external
ground truth (bank statements, captured fixtures, marketplace-native reports)
and tracks investigations of calculation discrepancies.

- **`audit reconcile`** — compares per-article aggregate revenue against
  bank-payout total and marketplace-raw rows; categorises rows into
  `:expected` / `:suspicious` / `:unclassified` using five rules
  (ppvz_reward commission, return netting, ad-allocation, etc.).
- **`audit kpi measure|list|show`** — records an Accuracy KPI baseline (WB-only
  in MVP). Baseline is refused when bank reference is incomplete (FR-011).
- **`audit verdict list|show`** — reads bug-hypothesis verdicts from
  `specs/002-calculation-audit/verdicts.md` (conclusions: `:confirmed`,
  `:refuted`, `:fixed`, `:confirmed-deferred`, `:not-yet-verdicted`).
- **`audit fixture capture|list|verify`** — captures and re-verifies
  ground-truth snapshots under `specs/002-calculation-audit/fixtures/` so
  regressions are caught deterministically.

Exit codes (`0` success, `1` suspicious discrepancies, `2` unclassified
operations, `3` input error, `4` baseline refused) let this be wired into
CI or pre-release gates.

See [specs/002-calculation-audit/](specs/002-calculation-audit/) for rules,
fixtures, and verdicts; [specs/003-finance-row-completeness/](specs/003-finance-row-completeness/)
covers the canonical FinanceRow schema and the per-marketplace `:ad-cost`
transform paths.

## Testing

### Default run

```bash
clojure -M:test
```

Integration tests (web UI, sync-route orchestration, test-db-isolation probe)
are tagged with `^:integration` meta and skipped by default — they require a
provisioned test database (`ANALITICA_DB=test-analitica.db`) and populated
fixtures. Run them explicitly with:

```bash
ANALITICA_DB=test-analitica.db clojure -M:test --no-skip-meta :integration
```

## License

Released under the [MIT License](LICENSE) — copyright (c) 2026 Marker
project authors.
