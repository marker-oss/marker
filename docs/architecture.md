# Architecture

**Статус**: живой документ. Описывает, как система декомпозирована на слои и функции.

**Назначение**: дать ясный map для навигации по коду; зафиксировать идиомы, которым следуем; выделить, где архитектура уже чистая, а где есть долг.

**Аудитория**: разработчики, которые пишут/читают/ревьюят код.

Связанные документы:
- [docs/canonical-formulas.md](./canonical-formulas.md) — контракт данных (finance-row) и формулы.
- [docs/db-schema.md](./db-schema.md) — ER-диаграмма, связи между таблицами, статус Malli-покрытия.
- [docs/vision.md](./vision.md) — границы продукта.

---

## 1. Принципы

Все следующие принципы — не декоративные. Каждое отступление от них в коде должно иметь комментарий с обоснованием.

### 1.1. Idiomatic Clojure

1. **Pure-in-center, effects-at-edges**. Чистые функции считают, side-effect'ы живут только на входе (ingest) и выходе (render, materialize). Pure-функции помечаются именем без `!`; side-effecting — с `!`.
2. **Data over objects**. Транспорт — Clojure maps и seqs. Никаких records/protocols там, где хватает данных.
3. **Threading для pipeline**. `->` для последовательных трансформаций одного значения, `->>` для коллекций.
4. **Seq abstractions**. `map`, `filter`, `reduce`, `group-by`, `mapcat`, `into` — вместо ручных циклов.
5. **Namespaced keywords** на границах контрактов: `:endpoint/id`, `:contract/source`, `:marketplace`. Ненаспейсенные — для локальных данных.
6. **Validation at boundaries**. Malli-схемы на точках пересечения слоёв (API → raw_data, canonical-row → finance-таблица). Внутри слоя — trust.
7. **Maps > positional args**. Функции >3 параметров принимают map опций через `{:keys [...]}`.
8. **Dynamic vars — только config**. `^:dynamic *aggregation-threshold*` и т.п. для тест-override; не для бизнес-данных.
9. **Lazy sequences mindfully**. Realize границу (`mapv`, `into []`) на выходе слоя, если возможен head-retention или IO в chain.

### 1.2. Архитектурные

1. **Слой не знает про соседа справа**. Transform не знает про БД. Calculate не знает про МП. Render не знает про формулы.
2. **Каждый слой имеет явный контракт ввода и вывода** — либо записанный (Malli-схема), либо документированный в этом файле.
3. **Слой testable in isolation** — можно написать unit-тест без запуска соседей. Side-effecting слои — через `with-redefs` / тестовые fixtures.
4. **Идемпотентность на write-путях**. Повторный ingest/materialize за тот же период даёт тот же результат БД.

### 1.3. Naming conventions

| Суффикс / префикс | Значение | Пример |
|---|---|---|
| `foo!` | side effect (IO, БД, сеть) | `ingest-finance!`, `db/execute!` |
| `foo` | pure computation | `by-article`, `diff-schemas` |
| `->foo` | transform `X → foo` (pure) | `->finance-line`, `->orders` |
| `foo->bar` | конвертация `foo` в `bar` (pure) | `finance->row`, `order->row` |
| `foo-by-bar` | grouping/aggregation | `by-article`, `by-report-id` |
| `with-foo` | middleware / scope wrapper | `with-validation`, `with-isolated-db` |
| `make-foo` | constructor для сложного map | `make-discrepancy`, `make-report` |
| `*foo*` | dynamic var | `*aggregation-threshold*` |

---

## 2. Слоёная карта

Три pipeline, которые пересекаются только через артефакты БД (`raw_data`, `finance`, `ad_stats`, `cash_flow_periods`).

```
 ┌──────────────────────── WRITE pipeline ──────────────────────────────┐
 │  L1 Schema      L2 Ingest       L3 Transform    L4 Materialize       │
 │  contract ──▶   ingest-*!  ──▶  ->finance-*  ──▶  materialize-*!     │
 │  (EDN)          (raw_data)      (pure seqs)       (finance/…)        │
 └──────────────────────────────────────────────────────────────────────┘
                                                                  │
                                                                  ▼
 ┌──────────────────────── READ pipeline ───────────────────────────────┐
 │  L5 Enrich    L6 Aggregate     L7 Calculate       L8 Render          │
 │  fetch+join ▶ by-article   ──▶ pnl/ue/sales  ──▶  table/export       │
 └──────────────────────────────────────────────────────────────────────┘
                                                                  ▲
                                                                  │
 ┌──────────────────────── AUDIT pipeline ──────────────────────────────┐
 │  L9 Registry  L10 Reconcile   L11 Verdict                            │
 │  rules     ▶  run-rule-set ▶  verdicts.md                            │
 └──────────────────────────────────────────────────────────────────────┘
```

---

## 3. Data contracts

### 3.1. raw_data (L2 → L3)

Таблица `raw_data`:
```
(source TEXT, entity_type TEXT, date_from DATE, date_to DATE,
 payload TEXT /* JSON */, ingested_at TIMESTAMP)
```

Payload — JSON as-is от API. Никаких интерпретаций. Это свойство важно: оно позволяет перематериализовать при смене transform.

### 3.2. Canonical finance-row (L3 → L4 и L4 → L6)

Определён в [docs/canonical-formulas.md §4](./canonical-formulas.md#4-canonical-finance-row-контракт). В коде — таблица `finance` в [src/analitica/db.clj](../src/analitica/db.clj).

Инварианты (см. canonical-formulas.md):
- `operation ∈ {:sale :return :service :adjustment}` (сейчас частично нарушен — WB имеет raw-значения "Логистика", "Хранение").
- `for_pay ≥ 0` для всех строк (включая returns).
- `article = nil` означает account-level.

### 3.3. Metrics shape (L7 → L8)

Pure map, зависит от функции:
- `pnl/calculate` → `{:revenue :gross-profit :net-profit :margin-gross :margin-net :sales-qty …}`
- `ue/calculate` → seq of `{:article :profit :margin-pct :profit-per-unit …}`
- `finance/totals` → `{:total-revenue :total-for-pay :articles-count …}`

Контракт каждой неявный, задаётся keywords в коде. Будущий шаг — задокументировать через Malli.

---

## 4. Layer 1 — Schema contracts

**Ответственность**: формальное описание ответов API и contract-схем.

**Код**:
- [src/analitica/schema/registry.clj](../src/analitica/schema/registry.clj) — in-memory registry.
- [src/analitica/schema/loader.clj](../src/analitica/schema/loader.clj) — загрузка EDN при старте.
- [src/analitica/schema/validator.clj](../src/analitica/schema/validator.clj) — `validate`, `with-validation`.
- [src/analitica/schema/openapi.clj](../src/analitica/schema/openapi.clj) — OpenAPI → Malli.
- [src/analitica/schema/regenerate.clj](../src/analitica/schema/regenerate.clj), [infer.clj](../src/analitica/schema/infer.clj) — авто-инструменты.
- [resources/schemas/<mp>/*.edn](../resources/schemas/) — хранилище контрактов.

**Ключевые функции**:
```clojure
;; pure
(registry/lookup endpoint-id)          ;; => contract-map | nil
(registry/all-endpoints)               ;; => sorted seq
(validator/validate contract response) ;; => {:status :ok/:warned/:failed :violations […]}
(openapi/->malli spec)                 ;; => {:schema malli :warnings […]}

;; side-effecting (!)
(registry/register! contract)          ;; mutates registry atom
(loader/load-all!)                     ;; reads disk → registry
(validator/validate! endpoint response) ;; throws on :failed
(validator/with-validation             ;; fetch → save-raw → validate
  endpoint-id fetch-fn save-raw-fn)
```

**Идиома**: registry — `defonce atom`, read через `deref`, write через `swap!` в `register!/clear!`. Pure-функции читают atom один раз в начале вызова.

---

## 5. Layer 2 — Ingest

**Ответственность**: HTTP-запрос к API, сохранение ответа в `raw_data`, валидация по схеме.

**Код**: [src/analitica/ingest.clj](../src/analitica/ingest.clj), [src/analitica/marketplace/<mp>/api.clj](../src/analitica/marketplace/).

**Структура**:
```clojure
;; Public entry points — все с !
(ingest-finance!   period & opts)
(ingest-sales!     period & opts)
(ingest-orders!    period & opts)
(ingest-storage!   period & opts)
(ingest-stocks!    & opts)
(ingest-prices!    & opts)
(ingest-regions!   period & opts)
(ingest-product-stats! period & opts)

;; Private per-marketplace chunks
(ingest-wb-finance-chunk!   client from to)
(ingest-ozon-realization!   client from to)   ;; monthly loop
(ingest-ym-finance!         client from to)
```

**Инварианты**:
1. **save-raw before validate** (FR-004). Всегда: `(with-validation endpoint-id fetch-fn save-raw-fn)`.
2. **Chunking** — каждый МП знает свои лимиты, реализация скрыта в `ingest-<mp>-<entity>-chunk!`.
3. **Ошибка одного chunk'а не валит остальных** — `reduce + try/catch`.

**Идиома**:
```clojure
(reduce (fn [acc [chunk-from chunk-to]]
          (try
            (+ acc (ingest-wb-finance-chunk! client chunk-from chunk-to))
            (catch Exception e
              (println (str "  ERROR " chunk-from ".." chunk-to ": " (.getMessage e)))
              acc)))
        0
        (t/date-chunks from to 30))
```
— `reduce` как fold, error-isolation per element, progress counter.

**Что НЕ делает**: не парсит payload, не маппит в canonical-row, не пишет в analytical tables.

---

## 6. Layer 3 — Transform

**Ответственность**: чистое преобразование raw JSON → seq canonical-rows.

**Код**: `src/analitica/marketplace/<mp>/transform.clj`.

**Структура** (единый шаблон для всех МП):
```clojure
;; Single item converter
(defn- ->finance-line [raw]
  {:marketplace       :wb
   :rrd-id            (:rrd_id raw)
   :for-pay           (:ppvz_for_pay raw)
   ;; … ещё ~25 полей из canonical contract
   })

;; Seq converter — тонкий mapv
(defn ->finance-report [raw-list]
  (mapv ->finance-line raw-list))
```

**Идиома**:
- **Каждый `->foo` — pure**. Zero side effects, zero globals.
- **`mapv` вместо `map`** на выходе слоя — реализуется сразу, чтобы потребитель не схватил lazy-chain с чужим scope.
- **Destructuring в ключе**: `(get raw :ppvz_for_pay)` или `(:ppvz_for_pay raw)` — единообразно внутри файла.
- **Fallback через `or`**: `(or (:brand raw) (:brand-name raw))` — когда API-поле меняло имя между версиями.

**Ozon-специфика** — один raw-row → 0-2 canonical-rows:
```clojure
(defn- realization-row->finance-rows [rrow date-from date-to]
  (into []
        (remove nil?
                [(when (pos? (... :delivery_commission :quantity)) sale-row)
                 (when (pos? (... :return_commission :quantity)) return-row)])))

(defn ->finance-from-realization [realization-raw]
  (let [rows (:rows realization-raw)]
    (into [] (mapcat #(realization-row->finance-rows % …) rows))))
```
— `mapcat` для разворачивания "0 или более" на элемент.

**Что НЕ делает**: не обращается к БД, не делает сетевых вызовов, не бросает исключений (кроме assert'ов на инварианты).

---

## 7. Layer 4 — Materialize

**Ответственность**: собрать L3 и L2 в оффлайн-pipeline: `raw_data → transform → INSERT`.

**Код**: [src/analitica/materialize.clj](../src/analitica/materialize.clj).

**Структура**:
```clojure
;; Pure helper
(defn- transform-finance [source raw-items]
  (case source
    "wb"   (wb-t/->finance-report raw-items)
    "ozon" (ozon-t/->finance-report raw-items (db/ozon-sku-map))
    "ym"   (ym-t/->finance-from-order-stats raw-items)))

;; Side-effecting entry point
(defn materialize-finance! [period & {:keys [marketplace]}]
  (let [[from to] (resolve-period period)
        raw-items (load-raw (name marketplace) :finance from to)
        data      (transform-finance (name marketplace) raw-items)
        rows      (mapv sync/finance->row data)
        cnt       (db/insert-batch! :finance sync/finance-columns rows)]
    (println (str "Materialized finance: " cnt))
    cnt))
```

**Идиома — threading через `let`**:
каждый шаг pipeline вынесен в отдельный let-binding с осмысленным именем (`raw-items` → `data` → `rows` → `cnt`). Это Clojure-эквивалент `|>` в pipeline-language'ах, но с именами, которые помогают читать.

**Диспатч по МП через `case`**: один switch-point на materialize, не разбросанный if'ами.

**Идемпотентность**:
- `INSERT OR REPLACE BY rrd_id` в `db/insert-batch!` для сроу с натуральным ключом.
- `DELETE ... WHERE marketplace=... AND date range` перед `INSERT` для случаев без натурального ключа (Ozon realization после B-005).

**Что НЕ делает**: не считает метрик, не делает сетевых вызовов (это часть контракта — "чистый rebuild оффлайн").

---

## 8. Layer 5 — Enrich *(текущее состояние: disperced)*

**Ответственность** (должна быть): объединить canonical `finance`-rows с внешними источниками — `cost_prices`, `paid_storage`, `ad_stats`, `cash_flow_periods`.

**Текущее состояние**: размазан по L6 и L7.
- `finance/line-cost` читает `cost_prices` во время агрегации ([finance.clj:39-43](../src/analitica/domain/finance.clj#L39-L43)).
- `pnl/calculate` читает `ad_stats` через прямой SQL ([pnl.clj:37-38](../src/analitica/domain/pnl.clj#L37-L38)).
- `pnl/load-cf-adjustments` ходит в `cash_flow_periods` ([pnl.clj:103-107](../src/analitica/domain/pnl.clj#L103-L107)).
- `ue/report` подтягивает `db/storage-by-article` и `db/ad-spend-by-article`.

**Проблемы**:
1. **Ad-spend в pnl без `marketplace`-фильтра** — один из следствий отсутствия enrich-слоя.
2. **Дублирование чтения** — один и тот же отчёт делает 4-5 обращений к БД вместо одного.
3. **Нет явного контракта** "enriched finance-row".

**Целевая форма** (см. §13 "Известный долг"):
```clojure
;; src/analitica/domain/enrich.clj  — будущий файл
(defn enrich
  "finance-rows → enriched-rows, обогащённые cogs, storage, ad-spend."
  [finance-rows {:keys [from to marketplace]}]
  (let [cost-lookup (cost-price/index-for from to marketplace)
        storage-map (db/storage-by-article from to :marketplace marketplace)
        ad-map      (db/ad-spend-by-article from to :marketplace marketplace)]
    (mapv #(assoc %
                  :cogs (cost-lookup (:article %) (:barcode %))
                  :paid-storage-override (get storage-map (:article %))
                  :ad-spend (get ad-map (:article %) 0.0))
          finance-rows)))
```

---

## 9. Layer 6 — Aggregate

**Ответственность**: группировка canonical-rows по ключу + `SUM`/`COUNT`.

**Код**: [src/analitica/domain/finance.clj](../src/analitica/domain/finance.clj).

**Функции**:
```clojure
(defn by-article  [finance-data & {:keys [storage-by-article articles sort-key]}])
(defn by-sku      [finance-data & {:keys [size-map]}])
(defn by-report-id [finance-data])
(defn totals      [finance-data])
```

**Идиома**:
```clojure
(->> finance-data
     (group-by :article)
     (map (fn [[article lines]] (article-row article lines …)))
     (sort-by :for-pay >))
```
— классический `group-by → map → sort` pipeline через `->>`.

**Инвариант**: функции pure. Зависят только от переданных аргументов. Никакого БД, никакого глобального state.

**Что пока не идеально**:
- `article-row` внутри вызывает `line-cost`, который читает `cost_prices` через `db/` — это нарушение чистоты (см. §13).
- Агрегация учитывает `:operation ∈ {sale, Продажа, return, Возврат}` — хардкод вместо canonical `#{:sale :return}`.

---

## 10. Layer 7 — Calculate

**Ответственность**: применение формул к агрегатам → named metrics.

**Код**: [src/analitica/domain/pnl.clj](../src/analitica/domain/pnl.clj), [src/analitica/domain/unit_economics.clj](../src/analitica/domain/unit_economics.clj), `src/analitica/domain/sales.clj`.

**Шаблон**:
```clojure
(defn calculate
  "Pure: finance-data → named metrics."
  [finance-data & {:keys [cf-adjustments]}]
  (let [by-art        (finance/by-article finance-data)
        revenue       (reduce + 0.0 (map :revenue by-art))
        for-pay       (reduce + 0.0 (map :for-pay by-art))
        cogs          (reduce + 0.0 (map :total-cost by-art))
        ;; … ещё слагаемые
        gross-profit  (- for-pay cogs logistics storage penalties acceptance deduction
                         (- additional))
        net-profit    (- gross-profit ad-spend)]
    (cond->
      {:revenue (math/round2 revenue)
       :gross-profit (math/round2 gross-profit)
       :net-profit (math/round2 net-profit)
       ;; …
       }
      has-cf? (assoc :cf-total (math/round2 cf-total) …))))
```

**Идиомы**:
- **Round at the boundary**, not в процессе: `(math/round2 x)` применяется только в финальном map.
- **`cond->` для условных полей**: если есть `cf-adjustments`, добавляем CF-метрики. Без if/else-ветвления результата.
- **Формулы — декларация в `let`**, не разбросаны по if'ам. Читаются как уравнение.

**Текущие отступления** (дефолт — чистота, но):
- `pnl/calculate` читает `ad_stats` через SQL (должно приходить в enrich — см. §8). Из-за этого функция не совсем pure. Помечено в §13.

---

## 11. Layer 8 — Render

**Ответственность**: сериализация metrics в CLI-таблицу / CSV / Excel / Web-JSON.

**Код**:
- [src/analitica/report/table.clj](../src/analitica/report/table.clj) — `print-table`, `print-summary`.
- [src/analitica/report/export.clj](../src/analitica/report/export.clj) — `to-csv`, `to-excel`.
- [src/analitica/web/](../src/analitica/web/) — HTTP endpoints.

**Pattern**: функция `report` в каждом domain-namespace — thin wrapper:
```clojure
(defn report [period & opts]
  (let [data    (fetch-finance period :marketplace marketplace)
        summary (totals data)]
    (table/print-summary "ФИНАНСОВЫЙ ОТЧЁТ" [["Выручка" (:total-revenue summary)] …])
    (table/print-table cols (by-article data))
    summary))
```

**Идиома**: columns definitions — отдельные top-level `def`:
```clojure
(def ^:private finance-export-cols
  [[:article "Артикул"] [:brand "Бренд"] …])
```
Таблица переиспользуется между CLI, CSV, Excel — одна spec, три рендерера.

---

## 12. Layer 9–11 — Audit

### 12.1. Layer 9 — Rule registry (pure)

**Код**: [src/analitica/audit/rules.clj](../src/analitica/audit/rules.clj).

```clojure
(defn register-rule! [rule-id rule-map])      ;; side effect
(defn classify [discrepancy])                  ;; pure → :ok/:warn/:error
(defn make-context [period marketplace])       ;; pure → rule context map
(defn run-rule [rule context])                 ;; pure → discrepancy or nil
```

### 12.2. Layer 10 — Reconcile

**Код**: [src/analitica/audit/rule_impl.clj](../src/analitica/audit/rule_impl.clj), [report.clj](../src/analitica/audit/report.clj).

Правило — map: `{:rule/id :aggregate-vs-raw :rule/fn (fn [ctx] discrepancy)}`. Все правила независимы → run parallel через `pmap` потенциально.

### 12.3. Layer 11 — Verdict

**Артефакт**: [specs/002-calculation-audit/verdicts.md](../specs/002-calculation-audit/verdicts.md) — markdown, редактируется человеком через git, не CLI.

**Идиома**: persistence через файл, а не через БД. Это осознанный выбор — verdict должен переживать rebuild БД и быть review'имым в PR.

---

## 13. Known debt (архитектурный)

Тех.долг в порядке приоритета.

1. **Enrich не выделен как слой (§8)**.
   Last-mile join-ы с `cost_prices`/`ad_stats`/`storage`/`cash_flow` размазаны по L6 и L7.
   Эффект: `pnl.ad-spend` без marketplace-фильтра → баг. `finance.line-cost` делает IO внутри pure-aggregate.
   **Fix**: `src/analitica/domain/enrich.clj`, pipeline `fetch → enrich → aggregate → calculate`.

2. **operation не нормализован (L3 WB transform)**.
   В `finance` попадают `"Логистика"`, `"Хранение"`, `"Компенсация ущерба"` — это raw-значения из `supplier_oper_name`.
   Эффект: `by-article` использует whitelist вместо canonical `#{:sale :return}`; теряется 0.3% account-level.
   **Fix**: маппить в `operation = :service` + `doc_type = оригинал`; пересмотреть `by-article` фильтр.

3. ~~**Canonical finance-row без Malli-валидации на L3→L4**.~~ ✅ **Closed 2026-04-22**.
   Реализовано в [src/analitica/domain/finance_row.clj](../src/analitica/domain/finance_row.clj). `materialize-finance!` теперь вызывает `log-bad-finance-rows!` перед `insert-batch!` — нарушения контракта логируются в stdout с группировкой по `(marketplace, field)`. Валидация non-fatal: bad rows всё равно пишутся в БД (raw_data — источник истины). Покрыто 7 unit-тестами.

4. **Ozon UE без cf-compensation**.
   `pnl/calculate` компенсирует через `:cf-adjustments`, `ue/calculate` — нет.
   Эффект: Ozon UE по артикулам завышает прибыль на величину services.
   **Fix**: `ue/calculate` должен принимать `:account-adjustments` аналогично pnl; либо enrich-слой (см. п.1) раздаёт services по артикулам пропорционально revenue.

5. **Метрики отчётов без Malli-контракта (L7→L8)**.
   `pnl/calculate` возвращает map с ~30 ключами; render-слой полагается на их имена.
   Эффект: рефакторинг pnl может молча сломать CSV/Excel.
   **Fix**: Malli-схема `PnLResult`, `UEResult`, `SalesResult`; или хотя бы docstring с перечнем.

6. ~~**Ad-spend в pnl без marketplace-фильтра**.~~ ✅ **Closed 2026-04-22**.
   Вынесен в `pnl/ad-spend-total` с JOIN `ad_stats ↔ finance.nm_id` для фильтрации по marketplace. `pnl/calculate` принимает `:marketplace` опцию; `report` и `export-excel` прокидывают её из своих аргументов.

7. **`:spp-amount` без бизнес-смысла**.
   Вычисляется как `for_pay - retail`, отображается как "Компенсация СПП".
   **Fix**: либо опираться на `spp_prc × retail`, либо переименовать в "ΔPayout-Retail".

---

## 14. Тестирование по слоям

| Слой | Стратегия | Файлы |
|---|---|---|
| L1 Schema | unit: registry add/lookup, validator on fixture | `test/analitica/schema/*_test.clj` |
| L2 Ingest | integration: `with-redefs` на HTTP client; проверяем что `raw_data` заполнен | `test/analitica/ingest_test.clj` (TBD) |
| L3 Transform | unit: fixture JSON → assert canonical-row shape | `test/analitica/marketplace/*/transform_test.clj` |
| L4 Materialize | integration: in-memory sqlite fixture, ингест fake raw, проверить `finance` | `test/analitica/materialize_test.clj` (TBD) |
| L5 Enrich | unit на pure join (когда будет выделен) | — |
| L6 Aggregate | unit: seq of maps → assert by-article shape | existing tests |
| L7 Calculate | unit: finance-data fixture → assert metrics | existing tests |
| L8 Render | unit: metrics → snapshot file/string | existing tests |
| L9-L11 Audit | unit + integration на audit rules | `test/analitica/audit/*_test.clj` |

**Общий fixture paradigm**: `test/analitica/test_helpers.clj` предоставляет `with-isolated-db`, `make-finance-row`, и т.п. Тесты не делят state.

---

## 15. Навигация для нового разработчика

Пришёл в проект, хочешь добавить новую метрику:

1. Прочитать [canonical-formulas.md §3](./canonical-formulas.md#3-canonical-metrics) — как метрика определяется.
2. Посмотреть §10 этого файла — где живёт `calculate`.
3. Добавить формулу в соответствующий domain-namespace.
4. Добавить тест в соответствующий `*_test.clj`.
5. Обновить canonical-formulas.md §3 и §5.

Хочешь добавить новый МП:

1. [canonical-formulas.md §8.2](./canonical-formulas.md#82-новый-marketplace) — чеклист.
2. Schema EDN в `resources/schemas/<mp>/`.
3. `api.clj` — HTTP-клиент (L2).
4. `transform.clj` — raw → canonical (L3).
5. Диспатч в `materialize-finance!` (L4) через `case`.
6. Заполнить столбец в `canonical-formulas.md §6.4`.

Хочешь отлаживать расхождение:

1. [verdicts.md](../specs/002-calculation-audit/verdicts.md) — возможно уже описано.
2. Audit rule (L9-L10) — добавить правило, воспроизводящее симптом.
3. Fixture из реального raw_data (берётся через `db/raw_data`).
4. Вердикт в `verdicts.md` после выяснения причины.
