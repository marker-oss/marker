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
