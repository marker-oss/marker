# Analitica

Marketplace analytics tool for Wildberries sellers. Built in Clojure.

Collects data from WB API, stores in SQLite, generates reports and exports to Excel/CSV. Integrates with 1C for cost price data.

## Features

- **Sales Dashboard** — revenue, orders, returns by day/article/category/warehouse
- **Financial Report** — WB weekly report breakdown per article (commission, logistics, storage, penalties)
- **Unit Economics** — profit per SKU with cost price from 1C (matched by barcode)
- **P&L Report** — full profit & loss statement
- **ABC Analysis** — categorize products by revenue/profit (80/15/5 rule)
- **Stock & Turnover** — stock levels, turnover rate, out-of-stock risk
- **Returns Analysis** — return rates by article, dynamics
- **Buyout Rate** — which products get returned most
- **Geography** — sales by region and city
- **Trend Analysis** — WoW and MoM comparisons, daily dynamics
- **Price Monitoring** — current prices and discounts
- **Sales Funnel** — views, cart, orders, buyouts, conversion rates
- **Excel/CSV Export** — all reports exportable

## Requirements

- Java 11+
- [Clojure CLI](https://clojure.org/guides/install_clojure)
- Wildberries API token (from seller cabinet)

## Setup

1. Clone the repository
2. Copy config template:
   ```bash
   cp config.example.edn config.edn
   ```
3. Create `.env` file with your WB API token:
   ```
   WB_API_TOKEN=your_token_here
   ```
4. (Optional) Place 1C cost price export at `1c/units.csv`

## Usage

### CLI

```bash
# Sync all data for last 30 days
clojure -M -m analitica.cli sync all -p last-30-days

# Sync specific data
clojure -M -m analitica.cli sync finance -f 2026-03-01 -t 2026-03-31
clojure -M -m analitica.cli sync stocks
clojure -M -m analitica.cli sync 1c

# Generate reports
clojure -M -m analitica.cli report pnl -f 2026-03-01 -t 2026-03-31
clojure -M -m analitica.cli report sales -p last-7-days
clojure -M -m analitica.cli report ue -p last-30-days

# Export to Excel
clojure -M -m analitica.cli report sales -p last-30-days -e reports/sales.xlsx
clojure -M -m analitica.cli report pnl -f 2026-03-01 -t 2026-03-31 -e reports/pnl.xlsx

# Check database status
clojure -M -m analitica.cli status
```

### REPL

```clojure
(require '[analitica.core :refer :all])
(start!)

;; Sync data
(sync/sync! :all :last-30-days)
(sync/sync! :1c)

;; Reports
(sales/dashboard :last-7-days)
(finance/report {:from "2026-03-01" :to "2026-03-31"})
(ue/report :last-30-days)
(pnl/report :last-30-days)
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

## Data Sources

| Source | What | Endpoint |
|--------|------|----------|
| WB Statistics | Sales, orders, stocks, finance | `/api/v1/supplier/*`, `/api/v5/supplier/reportDetailByPeriod` |
| WB Analytics | Product stats, storage, regions | `/api/v2/nm-report/*`, `/api/v1/analytics/*` |
| WB Prices | Current prices and discounts | `/api/v2/list/goods/filter` |
| 1C Export | Cost prices by barcode | `1c/units.csv` |

## Architecture

```
WB API  ──►  transform.clj  ──►  SQLite  ──►  domain/*.clj  ──►  report (console/Excel)
1C CSV  ──►  cost_price.clj ──►  SQLite  ──┘
```

- **Multi-marketplace ready** — protocol-based abstraction for future Ozon/YM support
- **SQLite storage** — data persisted locally, reports generated from DB
- **Barcode-based cost matching** — 1C cost prices mapped to WB data by barcode
- **Rate limiting** — built-in per-API-section rate limiter

## Project Structure

```
src/analitica/
├── core.clj              # REPL entry point
├── cli.clj               # CLI interface
├── config.clj             # Configuration (.env + config.edn)
├── db.clj                 # SQLite schema and queries
├── sync.clj               # API → SQLite synchronization
├── marketplace/
│   ├── protocol.clj       # MarketplaceAPI protocol
│   ├── registry.clj       # Marketplace registry
│   └── wb/                # Wildberries implementation
│       ├── api.clj        # API endpoint wrappers
│       ├── client.clj     # HTTP client with rate limiting
│       ├── transform.clj  # Raw JSON → domain model
│       └── impl.clj       # Protocol implementation
├── domain/                # Business logic
│   ├── sales.clj          # Sales dashboard
│   ├── finance.clj        # Financial report
│   ├── unit_economics.clj # Unit economics
│   ├── pnl.clj            # P&L report
│   ├── abc.clj            # ABC analysis
│   ├── stock.clj          # Stock & turnover
│   ├── returns.clj        # Returns analysis
│   ├── buyout.clj         # Buyout rate
│   ├── geography.clj      # Geography of sales
│   ├── trends.clj         # WoW/MoM trends
│   ├── ads.clj            # Sales funnel
│   ├── prices.clj         # Price monitoring
│   └── cost_price.clj     # 1C cost price loader
├── report/
│   ├── table.clj          # Console table formatting
│   └── export.clj         # CSV/Excel export
└── util/
    ├── http.clj           # HTTP client, retry, rate limiter
    ├── time.clj           # Date/period helpers
    └── math.clj           # Rounding, percentages
```

## License

Private / Internal use.
