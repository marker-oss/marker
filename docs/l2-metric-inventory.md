# L2 Metric Inventory

> **L2-A артефакт** методологии canonical-first для **L2 слоя**
> (формулы, в отличие от L1 контракта). Полный реестр всех метрик из
> [`canonical-formulas.md`](canonical-formulas.md) с кросс-ссылкой на
> код в `src/analitica/domain/`.
>
> Цель — сделать L2 видимым целиком, чтобы можно было:
> 1. Обнаружить **canon-without-code** (метрики в доке, не реализованы).
> 2. Обнаружить **code-without-canon** (метрики в коде, не задокументированы).
> 3. Найти **label mismatches** (как «Комиссия МП» = `wb-reward` в P&L,
>    исправлено 2026-04-28).
> 4. Подсветить **inconsistencies** между отчётами (одна метрика
>    считается по-разному в UE и P&L).
>
> Источники:
> - `docs/canonical-formulas.md` — авторитетный спек (3766 строк).
> - `docs/inventory/l2-metrics.tsv` — сырая выгрузка 96 секций.
> - `docs/inventory/l2-coverage.tsv` — кросс-референс с кодом.
>
> Дата: 2026-04-28.

## Сводная таблица

| Report | Sections | Metrics tracked* | Primary file | Coverage |
|---|---:|---:|---|---:|
| Finance         | 11 | 11 | `domain/finance.clj`         | 11 / 11 = 100% |
| P&L             |  9 | 19 | `domain/pnl.clj`             | 19 / 19 = 100% |
| Unit Economics  | 12 | 59 | `domain/unit_economics.clj`  | 52 / 59 = 88% |
| Buyout          |  8 |  4 | `domain/buyout.clj`          |  4 / 4 = 100% |
| ABC             |  8 |  – | `domain/abc.clj`             | n/a (no Members lines) |
| Sales           |  8 |  – | `domain/sales.clj`           | n/a |
| Stock           |  9 |  – | `domain/stock.clj`           | n/a |
| Returns         |  8 |  – | `domain/returns.clj`         | n/a |
| Geography       |  7 |  – | `domain/geography.clj`       | n/a |
| Trends          |  8 |  – | `domain/trends.clj`          | n/a |
| Losses          |  8 |  – | `domain/losses.clj`          | n/a |
| **Total**       | **96** | **93** | — | — |

\* "Metrics tracked" = unique metric keywords listed in `**Members:**`
lines. Sections without an explicit Members line are visible (with
formulas) but their keywords aren't enumerated. **First L2 finding**:
the canonical-formulas doc has **inconsistent structure** — Finance /
P&L / UE / Buyout use `**Members:**`, the other 6 reports don't. Either:
- Add Members lines to the missing reports for symmetry.
- Or accept that some reports are "narrative" rather than "members + formulas".

---

## По отчётам

### Finance (`domain/finance.clj`)

| § | Section title | Members | In code |
|---|---|---|---|
| Finance.1 | `article-row` per-article aggregates | revenue, wb-reward, mp-commission, acquiring, sales-pay, returns-pay, logistics, penalties, additional, acceptance, deduction | 11/11 ✅ |
| Finance.2 | `:spp-amount` derivative | (referenced but not Members-listed) | ✅ |
| Finance.3 | `:storage` coalescence | — | ✅ |
| Finance.4 | `:for-pay` net (sales − returns) | — | ✅ |
| Finance.5 | `:cost-price` and `:total-cost` | — | ✅ |
| Finance.6 | `empty-article-row` fallback | — | ✅ |
| Finance.7 | `totals` period rollup | — | ✅ |
| Finance.8 | `by-report-id` weekly split (WB only) | — | ✅ |
| Finance.9-11 | Coverage matrix / Known gaps / Verification | — | n/a (doc-only) |

**Health**: 100% coverage in primary file.

**🟡 Label issue (carried over from Phase D)**: 
- P&L строка `:wb-reward` была подписана "Комиссия МП" вместо "Возмещение ПВЗ" — исправлено в pnl.clj 2026-04-28.
- Аналогичная путаница может существовать в `finance.clj` строках:
  ```
  [:revenue "Выручка"] [:wb-reward "Комиссия МП"] [:mp-commission "Комиссия продаж"]
  ```
  → требует ревью (RFC' candidate).

### P&L (`domain/pnl.clj`)

| § | Section title | Members | Notes |
|---|---|---|---|
| P&L.1 | Period monetary aggregates (pass-throughs) | revenue, wb-reward, logistics, storage, acceptance, penalties, deduction, additional, for-pay, cogs | ✅ |
| P&L.2 | Ad spend total | (separate path, ad_stats fallback) | ✅ |
| P&L.3 | Gross profit | (formula-only, no Members) | ✅ |
| P&L.4 | Net profit and margins | net-profit, margin-gross, margin-net | ✅ |
| P&L.5 | Quantity and per-event derivatives | sales-qty, returns-qty, buyout-rate, avg-check, profit-per-sale, articles | ✅ |
| P&L.6 | Ozon cash-flow adjustments (optional) | — | ✅ |
| P&L.7-9 | Coverage / gaps / verification | — | n/a |

**Health**: 100% coverage. Phase D label fix applied.

### Unit Economics (`domain/unit_economics.clj`)

UE — самый большой по числу метрик (59 across 12 sections). Coverage 88%.

| § | Title | Members | In code |
|---|---|---|---|
| UE.1 | Article-level operations and units | sales-qty, returns-qty, **ops**, **net-qty**, total-ops | 3/5 ⚠️ |
| UE.2 | Per-article monetary pass-throughs | revenue, mp-commission, wb-reward, logistics, storage, acceptance, penalties, acquiring, deduction, additional, for-pay, total-cost, spp-amount | 13/13 ✅ |
| UE.3 | `total-wb-costs` derived | (formula-only) | ✅ |
| UE.4 | Article profit (absolute) | (formula-only) | ✅ |
| UE.5 | Ad spend allocation | (formula-only) | ✅ |
| UE.6 | Per-unit amortization (families) | revenue-per-unit, reward-per-unit, cost-per-unit, acquiring-per-unit, sales-qty, logistics-per-unit, storage-per-unit, accept-per-unit, payout-per-unit, profit-per-unit, **net-qty**, logistics-per-op, total-ops | 12/13 ⚠️ |
| UE.7 | Percentage metrics | buyout-rate, margin-pct, wb-cost-pct, cogs-pct, logistics-pct, drr-pct | ✅ |
| UE.8 | Summary monetary totals | total-revenue, total-wb-reward, total-logistics, total-storage, total-acceptance, total-penalties, total-acquiring, total-deduction, total-additional, total-ad-spend, total-wb-costs, total-spp, total-for-pay, total-cost, total-profit | ✅ |
| UE.9 | Summary derived | margin-pct, wb-cost-pct, cogs-pct, drr-pct, profit-per-sale, avg-check, buyout-rate | ✅ |
| UE.10-12 | Coverage / gaps / verification | — | n/a |

**🟡 L2 finding — `ops` / `net-qty` listed as Members but not exposed as fields**:

```
ops[a]      := sales-qty + returns-qty                  ← canonical formula
net-qty[a]  := max(1, sales-qty − returns-qty)          ← canonical formula
```

В коде (`unit_economics.clj`) эти выражения вычисляются **inline** при per-unit делении (UE.6), но **не хранятся** как отдельные ключи `:ops` / `:net-qty` в результате. Doc обещает их как Members, код не предоставляет.

**Решение**: либо expose через `:ops`/`:net-qty` (тогда дашборд может их показывать), либо удалить из Members-list (clarification, не реализация). RFC' candidate.

### Buyout (`domain/buyout.clj`)

| § | Title | Members |
|---|---|---|
| Buyout.7 | True buyout (orders-aware) | placed, cancelled, cancel-rate, true-buyout-rate |
| (Buyout.1-6, 8) | sub-headers без Members | — |

**Health**: 4/4 ✅.

### ABC, Sales, Stock, Returns, Geography, Trends, Losses

Все 7 reports — **нет** `**Members:**` строк в `canonical-formulas.md`. Формулы есть, метрики тоже есть, но не enumerated.

**L2-finding**: doc structure inconsistency. Можно либо:
1. Добавить Members lines в эти 7 reports для симметрии (~15-30 минут работы).
2. Принять что эти reports описаны narrative-style, и поправить L2-A парсер.

В любом случае — это технический долг доку, не L2-формул как таковых.

---

## Cross-cutting findings

> ✅ **Все 5 findings закрыты в L2-E (2026-04-28)**.

### F-1 — Label drift между отчётами  · ✅ closed

Тот же L1 field `:wb-reward` отображается в UI с разными лейблами:

| Файл | Лейбл | Статус |
|---|---|---|
| `domain/pnl.clj` | "Возмещение ПВЗ" / "PVZ Reimbursement" | ✅ |
| `domain/unit_economics.clj` | "Возмещение ПВЗ" / "PVZ Reimbursement" | ✅ |
| `domain/finance.clj` | "Возмещение ПВЗ" | ✅ |
| `web/report_schemas.clj` | "Возмещение ПВЗ" | ✅ |
| `web/api/export.clj` | "Возмещение ПВЗ" | ✅ |

**Итог**: единый лейбл применён везде. Дополнительно `:mp-commission`
теперь корректно отображается как "Комиссия МП" в `finance.clj` —
эта строка раньше ошибочно занимала `:wb-reward`.

### F-2 — `:operation-kind` в L2 формулах · ✅ closed

`canonical-formulas.md` §3.1–3.3 + §4.3 переписаны на `operation_kind`
вместо `operation`. Прямые ссылки на двухуровневый enum
`(:sale / :return / :service / :adjustment)` + `:operation_subtype`
для raw классификатора. RFC-3/14/15 явно помечены закрытыми в §4.3.

### F-3 — `Math/abs` removal в by-article · ✅ closed

`canonical-formulas.md` §Finance.4 переписана: формула теперь
`SUM(sales) − SUM(returns)` без abs. Объяснение про RFC-15
нормализацию + ссылка на E-2 backfill. Edge cases обновлены.

### F-4 — Reports без Members lines · ✅ closed

`Members:` строки добавлены в primary-aggregator секцию каждого из
7 narrative-reports: Sales.4, Stock.4, Returns.3, ABC.4,
Geography.1, Trends.1, Losses.4. L2-A парсер теперь enumeration
покрывает 132 ключа (было 93).

### F-5 — `:operation` (deprecated) vs `:operation-kind` · ✅ closed

`canonical-formulas.md` §4.3 явно помечает `operation` как
deprecated; новые формулы используют `operation_kind`. Согласовано
с L1 `data-dictionary.md` (RFC-3 closed 2026-04-28).

---

## L2 фазы — статус 2026-04-28

✅ **L2-A** (inventory): 96 секций, 132 metric keywords (после F-4) задокументированы.
✅ **L2-B** (cross-report matrix): 16 метрик в 2+ отчётах выявлены, 18 reconciliation pairs.
✅ **L2-C** (rules): `:l2-cross-report-agreement` правило с двух-уровневой классификацией.
✅ **L2-D** (validation): прогон на 17,679 production rows; найдены реальные account-level drift'ы, классифицированы как expected по design.
✅ **L2-E** (closures): 5 findings (F-1..F-5) закрыты — labels нормализованы, doc синхронизирован с RFC-3 → RFC-15.

## История L2-B / L2-C / L2-D / L2-E (рассуждения)

**L2-B (concept-crosswalk' for L2)**: для каждой метрики — какие L1 поля,
какие edge cases, какие cross-report sharing. Ловит:
- Метрики с одинаковым именем но разной формулой (UE.7 `buyout-rate` vs
  P&L.5 `buyout-rate` vs Buyout.1 — синхронизированы или нет?).
- Метрики которые вычисляются дважды (compute drift).

**L2-C (cross-report reconciliation)**: например
- `SUM(UE.net_profit per article) ⟷ P&L.net_profit per period`
- `Finance.totals.revenue ⟷ P&L.revenue ⟷ UE.total_revenue`

Эти три должны совпадать копейка-в-копейку. Если расходятся — bug.

**L2-D (validation)**: прогон на реальных данных — есть ли реальные
расхождения между отчётами?

**L2-E (closures)**: фиксы найденных RFC'.

---

## Приложения

- `docs/inventory/l2-metrics.tsv` — 96 sections × {report, section, title, members, formula, l1_fields}
- `docs/inventory/l2-coverage.tsv` — 93 metric × {report, section, primary_file, in_primary, hit_files, hit_count}
