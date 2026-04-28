# Database Schema

**Статус**: живой справочник по БД и всем неявным связям между таблицами. SQLite не enforce'ит foreign key в нашей схеме — связи держатся на соглашениях (`article`, `nm_id`, `barcode`, `marketplace`), которые задокументированы здесь.

**Связанные документы**:
- [docs/architecture.md](./architecture.md) — как данные движутся между таблицами.
- [docs/canonical-formulas.md](./canonical-formulas.md) — что значит каждое поле в `finance`.

DDL — в [src/analitica/db.clj](../src/analitica/db.clj).

---

## 1. Общая карта

14 таблиц разделены на 4 зоны ответственности:

| Зона | Таблицы | Роль |
|---|---|---|
| **Ingest буфер** | `raw_data` | JSON-as-is от API, истина последней инстанции |
| **Материализованные факты** | `finance`, `sales`, `orders`, `stocks`, `prices`, `paid_storage`, `product_stats`, `ad_stats`, `region_sales`, `cash_flow_periods` | Парсенные данные, готовые к агрегации |
| **Reference data** | `cost_prices`, `ozon_sku_map` | Справочники: себестоимость (из 1С), mapping sku↔offer_id |
| **Audit / мета** | `accuracy_kpi_measurements` | KPI точности расчётов |

---

## 2. ER-диаграмма

```mermaid
erDiagram
    raw_data ||--o{ finance : "materialize-finance!"
    raw_data ||--o{ sales   : "materialize-sales!"
    raw_data ||--o{ orders  : "materialize-orders!"
    raw_data ||--o{ stocks  : "materialize-stocks!"
    raw_data ||--o{ prices  : "materialize-prices!"
    raw_data ||--o{ paid_storage : "materialize-storage!"
    raw_data ||--o{ product_stats : "materialize-product-stats!"
    raw_data ||--o{ region_sales : "materialize-regions!"
    raw_data ||--o{ cash_flow_periods : "materialize-cashflow!"

    finance }o--|| cost_prices : "article+barcode"
    finance }o--o{ ad_stats    : "nm_id"
    finance }o--o{ paid_storage : "barcode+date"
    finance }o--|| ozon_sku_map : "sku (Ozon only)"

    sales     }o--o{ cost_prices : "article+barcode"
    orders    }o--o{ cost_prices : "article+barcode"
    stocks    }o--o{ prices      : "article+marketplace"

    cash_flow_periods }o--o{ finance : "source+period (Ozon P&L)"
    accuracy_kpi_measurements }o--o{ finance : "sku_list (audit)"

    raw_data {
        int id PK
        string source "wb/ozon/ym"
        string entity_type "finance/sales/orders/stocks/..."
        date date_from
        date date_to
        text payload "JSON as-is"
        int item_count
        timestamp ingested_at
        UK source_entity_dates "UNIQUE"
    }

    finance {
        int rrd_id PK "natural for WB, hash for Ozon/YM"
        int report_id "weekly report id (WB)"
        date date_from
        date date_to
        string article "seller SKU"
        int nm_id "MP internal product id"
        string barcode
        string operation "sale/return/... canonical enum"
        int quantity
        real retail_amount
        real for_pay "MP net payout, ALWAYS >= 0"
        real mp_commission
        real delivery_cost
        real storage_fee
        real acceptance
        real penalty
        real additional_payment
        real deduction
        real acquiring_fee
        string marketplace "wb/ozon/ym"
        timestamp synced_at
    }

    sales {
        string sale_id PK
        date date
        string article
        int nm_id
        string barcode
        string type "sale/return"
        real total_price
        real for_pay
        string marketplace
    }

    orders {
        string order_id PK
        date date
        string article
        int nm_id
        string barcode
        string status
        real price
        string marketplace
    }

    stocks {
        int id PK
        string article
        int nm_id
        string barcode
        string warehouse
        int quantity
        int quantity_full
        string marketplace
    }

    cost_prices {
        string article PK
        string barcode PK
        real cost_price
        string nomenclature
        int quantity_1c
        date updated_at
    }

    prices {
        int nm_id
        string article PK
        real price
        int discount
        int club_discount
        string marketplace PK
    }

    product_stats {
        string article PK
        date date_from PK
        date date_to
        string marketplace PK
        int nm_id
        int views
        int add_to_cart
        int orders
        real orders_sum
        int buyouts
        real buyouts_sum
    }

    ad_stats {
        int campaign_id PK
        date date PK
        int nm_id
        real spend
        int views
        int clicks
        real cpc
        real ctr
        int orders
    }

    region_sales {
        int nm_id
        string article
        string region
        string city
        string country
        int qty
        real sum_price
        date date_from
        date date_to
    }

    paid_storage {
        date date PK
        string barcode PK
        string warehouse PK
        string marketplace PK
        string article
        int nm_id
        real cost
        real volume
        int barcodes_count
    }

    ozon_sku_map {
        int sku PK
        string offer_id "== article in finance"
    }

    cash_flow_periods {
        int id PK
        string source "ozon"
        date period_begin
        date period_end
        real orders_amount
        real returns_amount
        real commission_amount
        real delivery_logistics
        real storage
        real subscription
        real fines
        real acquiring
        real invoice_transfer "banked payout"
    }

    accuracy_kpi_measurements {
        string kpi_id PK
        timestamp captured_at
        string marketplace
        date period_from
        date period_to
        text sku_list
        real reference_bank_sum
        real measured_value
        real delta_rel_pct
        string verdict "meets-kpi/misses-kpi"
    }
```

---

## 3. Логические ключи связи

SQLite не enforce'ит FK — связи держатся на соглашениях в коде. Здесь зафиксированы все способы join между таблицами.

| Join | Поля | Использование |
|---|---|---|
| `finance ↔ cost_prices` | `(article, barcode)` | `finance/line-cost` → себестоимость на строку |
| `finance ↔ ad_stats` | `nm_id` + marketplace (через JOIN `finance.marketplace`) | `db/ad-spend-by-article`, `pnl/ad-spend-total` |
| `finance ↔ paid_storage` | `barcode` + `date` | `db/storage-by-article` для override |
| `cash_flow_periods ↔ finance` | `source = 'ozon'`, period_begin/end в рамках finance date_range | `pnl/load-cf-adjustments` — account-level корректировки Ozon |
| `ozon_sku_map ↔ Ozon raw_data` | `sku` (в API) → `offer_id` → `article` в finance | Ozon transform когда приходит только sku |
| `stocks ↔ prices` | `(article, marketplace)` | UE для per-unit расчётов (не реализовано напрямую) |
| `accuracy_kpi ↔ finance` | `sku_list` (JSON) + `period_from/to` + `marketplace` | Audit reconcile |

**Уязвимость**: если `article` или `nm_id` отсутствуют в строке finance (account-level), эти join'ы отваливаются. Отсюда B-002 (account-level операции теряются в `by-article`).

---

## 4. Natural keys и идемпотентность

Каждая таблица имеет PK, который позволяет `INSERT OR REPLACE` без дублей:

| Таблица | PK / UNIQUE | Эффект повторного ingest'а |
|---|---|---|
| `raw_data` | `UNIQUE(source, entity_type, date_from, date_to)` | перезаписывает payload |
| `finance` | `rrd_id` (natural WB, hash Ozon/YM) | перезаписывает строку |
| `sales`, `orders` | `sale_id` / `order_id` | перезаписывает |
| `stocks` | `id AUTOINCREMENT` + полная очистка перед materialize (snapshot) | чистый replace |
| `prices` | `(article, marketplace)` | перезаписывает |
| `product_stats` | `(article, date_from, marketplace)` | перезаписывает |
| `ad_stats` | `(campaign_id, date)` | перезаписывает |
| `paid_storage` | `(date, barcode, warehouse, marketplace)` | перезаписывает |
| `cost_prices` | `(article, barcode)` | перезаписывает из 1С |
| `cash_flow_periods` | `UNIQUE(source, period_begin, period_end)` | перезаписывает |
| `ozon_sku_map` | `sku` | перезаписывает |
| `accuracy_kpi_measurements` | `kpi_id` | write-only (audit log) |
| `region_sales` | — | **накапливает дубли!** — известный долг |

---

## 5. Индексы

```sql
CREATE INDEX idx_sales_date         ON sales(date);
CREATE INDEX idx_sales_article      ON sales(article);
CREATE INDEX idx_orders_date        ON orders(date);
CREATE INDEX idx_finance_article    ON finance(article);
CREATE INDEX idx_finance_report     ON finance(report_id);
CREATE INDEX idx_stocks_article     ON stocks(article);
CREATE INDEX idx_paid_storage_date  ON paid_storage(date, marketplace);
CREATE INDEX idx_paid_storage_barcode ON paid_storage(barcode);
```

**Что НЕ индексировано и часто запрашивается**:
- `raw_data(source, entity_type, date_from)` — для `materialize-*!`. При большом объёме raw_data сканы станут заметны. TODO.
- `finance(marketplace, date_from)` — все domain-запросы через него. Возможная оптимизация.
- `ad_stats(nm_id)` — JOIN с finance.

---

## 6. Malli coverage status

Где Malli уже защищает контракт, где нет.

### 6.1. API-contracts (resources/schemas/)

| Marketplace / Endpoint | Malli схема | Где используется |
|---|---|---|
| WB `report-detail-by-period` | ✅ [resources/schemas/wb/](../resources/schemas/wb/report-detail-by-period.edn) | `ingest-wb-finance-chunk!` |
| Ozon `finance-realization` | ✅ [resources/schemas/ozon/](../resources/schemas/ozon/finance-realization.edn) | `ingest-ozon-realization!` |
| Ozon `cash-flow-statement` | ✅ [resources/schemas/ozon/](../resources/schemas/ozon/cash-flow-statement.edn) | (схема есть, валидация в ingest-ozon-cashflow! пока не подключена) |
| YM `order-stats` | ✅ [resources/schemas/ym/](../resources/schemas/ym/order-stats.edn) | `ingest-ym-finance!` (через YM ветку `ingest-finance!`) |
| WB orders / sales / storage / stocks / prices / regions / product_stats | ❌ | — |
| Ozon postings / storage / stocks / prices / product_stats | ❌ | — |
| YM orders / stocks / prices / product_stats | ❌ | — |

**Итого**: 4 из ~19 endpoint'ов (~21%).

### 6.2. Canonical in-memory rows (transform → materialize)

| Row type | Malli схема | Где |
|---|---|---|
| `finance-row` | ✅ | [src/analitica/domain/finance_row.clj](../src/analitica/domain/finance_row.clj) |
| `sales-row` | ❌ | — |
| `orders-row` | ❌ | — |
| `stocks-row` | ❌ | — |
| `price-row` | ❌ | — |
| `paid-storage-row` | ❌ | — |
| `product-stat-row` | ❌ | — |
| `cash-flow-period` | ❌ | — |

**Итого**: 1 из 8 (~12%).

### 6.3. Domain metrics (calculate → render)

| Metric map | Malli схема | Где возвращается |
|---|---|---|
| `PnLResult` | ❌ | `domain/pnl/calculate` |
| `UEResult` | ❌ | `domain/unit_economics/calculate` |
| `SalesResult` | ❌ | `domain/sales/*` |
| `FinanceSummary` | ❌ | `domain/finance/totals` |
| `AuditReport` | ❌ | `audit/report/make-report` |
| `Discrepancy` | ❌ | `audit/report/make-discrepancy` |

**Итого**: 0 из 6 (0%).

### 6.4. Контракты БД-строк

**Итого**: 0 из 14 (0%).

Можно ли вывести Malli прямо из DDL автоматически? Да, но с ограничениями: SQLite типы — слабые, `REAL` может быть любым числом, `TEXT` — любой строкой. Автогенерация даст baseline, ручная доводка нужна для семантики (enum-ы, формат дат, знаки).

---

## 7. План расширения Malli (если нужно, не делаем сейчас)

Порядок по ценности / трудоёмкости:

### 7.1. Hi-value, low-effort

1. **Canonical `sales-row` / `orders-row`** — симметрично `finance-row`, та же схема-пайплайн, что в L3→L4.
2. **`cash-flow-period` row** — структура уже в `cashflow-columns`, легко переносится. Нужно для валидации `ozon-t/->cash-flow-periods`.
3. **`PnLResult` / `UEResult` / `FinanceSummary`** — маппинг в Malli 30-40 полей; защищает границу Render.

### 7.2. Mid-value, mid-effort

4. **Оставшиеся API-endpoint'ы** — по одному endpoint'у на transform-функцию. Добавлять по мере обновлений API или обнаружения проблем.
5. **`stocks-row`, `price-row`, `paid-storage-row`, `product-stat-row`** — мелкие, симметричные.

### 7.3. Low-value, high-effort

6. **DB-table контракты** — дубль `finance-columns` в сmalli формате + валидация перед `INSERT`. Польза сомнительная, т.к. уже есть валидация на L3 (`finance-row`) и физические ограничения SQLite.

---

## 8. Ссылки

- DDL: [src/analitica/db.clj](../src/analitica/db.clj#L22) (с L22).
- Материализация: [src/analitica/materialize.clj](../src/analitica/materialize.clj).
- Canonical finance-row schema: [src/analitica/domain/finance_row.clj](../src/analitica/domain/finance_row.clj).
- API schemas: [resources/schemas/](../resources/schemas/).
