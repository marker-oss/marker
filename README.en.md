# Marker

Marketplace analytics for e-commerce sellers on WB / Ozon / Yandex.Market — Clojure + SQLite + ClojureScript SPA.

> **Educational project, under active development.**
> Marker is the product brand; the codebase keeps the historical `analitica` namespace.

> 🇷🇺 Русская версия — [README.md](README.md)

---

## Features

- **Multi-marketplace** — unified protocol for WB, Ozon, and Yandex.Market
- **Sync** — incremental API data ingestion with rate-limit control
- **Sales** — revenue, orders, returns by day / article / category / warehouse
- **Finance** — per-article breakdown: commissions, logistics, storage, penalties, ad spend
- **Unit economics** — profit per SKU with cost price from 1C
- **P&L** — full profit & loss statement
- **ABC analysis** — product classification by revenue / profit (80/15/5 rule)
- **Stock & turnover** — warehouse levels, out-of-stock risk
- **Returns** — return rate dynamics by article
- **Geography** — sales by region and city
- **Trends** — WoW and MoM comparisons
- **Calculation audit** — reconciliation vs. bank statement, KPI baseline, hypothesis verdicts, regression fixtures
- **Excel / CSV export** — for all reports from both CLI and web UI

---

## Architecture overview

```
WB API   ──►  wb/transform    ──►┐
Ozon API ──►  ozon/transform  ──►├──  SQLite  ──►  domain/*.clj  ──►  Web API / CLI / REPL
YM API   ──►  ym/transform    ──►┘
1C CSV   ──►  cost_price      ──────────────────►┘
```

Layers: **marketplace** (HTTP clients + rate-limit) → **storage** (SQLite / next.jdbc) →
**domain** (business logic, MP-agnostic) → **report** (tables + Excel/CSV) → **web** (API + UI).

**UI — re-frame ClojureScript SPA (`src/cljs/marker/`) — the primary frontend;
server-rendered HTMX pages are the legacy/transitional layer.**

Full details — [docs/architecture.md](docs/architecture.md).

---

## Quick start

### Requirements

- Java 11+
- [Clojure CLI](https://clojure.org/guides/install_clojure)
- Node.js 18+ (for the SPA)
- WB API token (Ozon / YM — optional)

### 1. Configuration

```bash
cp config.example.edn config.edn
# Open config.edn and fill in your API tokens
```

### 2. Backend

```bash
clojure -M -m analitica.web.server
# http://localhost:3000
```

### 3. Frontend (SPA)

```bash
npm install
npx shadow-cljs watch app
# SPA available at http://localhost:3000/app
```

### 4. First sync

```bash
clojure -M -m analitica.cli sync all -p last-30-days
```

---

## Usage

### CLI

```bash
# Sync
clojure -M -m analitica.cli sync all -p last-30-days
clojure -M -m analitica.cli sync finance -f 2026-03-01 -t 2026-03-31
clojure -M -m analitica.cli status

# Reports
clojure -M -m analitica.cli report pnl  -p last-30-days
clojure -M -m analitica.cli report ue   -p last-30-days
clojure -M -m analitica.cli report sales -p last-7-days -e reports/sales.xlsx

# Calculation audit
clojure -M -m analitica.cli audit reconcile -m wb -p last-30-days --bank-sum 1500000
clojure -M -m analitica.cli audit kpi measure -m wb -f 2026-03-01 -t 2026-03-31
```

Full command reference — [docs/architecture.md](docs/architecture.md).

### REPL

```clojure
(require '[analitica.core :refer :all])
(start!)
(help)   ;; list all available commands
```

### Period formats

| Keyword         | Meaning                  |
|-----------------|--------------------------|
| `:today`        | Current day              |
| `:yesterday`    | Previous day             |
| `:last-7-days`  | Last 7 days              |
| `:last-30-days` | Last 30 days             |
| `:this-week`    | Monday through today     |
| `:this-month`   | 1st of month to today    |

Custom range: `{:from "2026-03-01" :to "2026-03-31"}`

---

## Documentation

| File | Contents |
|------|----------|
| [docs/architecture.md](docs/architecture.md) | Full architecture, layers, key design decisions |
| [docs/dev-setup.md](docs/dev-setup.md) | Local development, REPL, SPA build, tests, linter |
| [docs/data-dictionary.md](docs/data-dictionary.md) | Field dictionary for all reports |
| [docs/db-schema.md](docs/db-schema.md) | SQLite database schema |
| [docs/canonical-formulas.md](docs/canonical-formulas.md) | Canonical unit-economics and P&L formulas |
| [docs/reconciliation.md](docs/reconciliation.md) | Reconciliation against marketplace seller accounts |
| [docs/deploy.md](docs/deploy.md) | Deployment (local network / server) |
| [docs/marker-api.md](docs/marker-api.md) | Web server REST API reference |
| [docs/concept-crosswalk.md](docs/concept-crosswalk.md) | Term mapping WB / Ozon / YM → canonical |
| [CONTRIBUTING.md](CONTRIBUTING.md) | How to contribute |
| [SECURITY.md](SECURITY.md) | Security policy |

---

## Testing

```bash
# Clojure (unit; integration tests skipped without real tokens)
clojure -M:test

# ClojureScript
npx shadow-cljs compile test
```

More details — [docs/dev-setup.md](docs/dev-setup.md).

---

## License

[MIT](LICENSE) © 2026 Marker project authors.
Contributions — [CONTRIBUTING.md](CONTRIBUTING.md).
