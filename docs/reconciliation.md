# Reconciliation Rules — Phase C

> Phase C артефакт методологии canonical-first. Систематизирует
> **пары источников**, которые должны численно сходиться внутри одного MP
> или между нашими таблицами. Каждое правило — автоматизированный
> sanity-check, прокидываемый через существующий audit framework
> (`analitica.audit.rules`).
>
> Связанные документы:
> - [`marketplace-inventory.md`](marketplace-inventory.md) — Phase A полный реестр
> - [`concept-crosswalk.md`](concept-crosswalk.md) — Phase B concept × MP × endpoint
> - [`canonical-formulas.md`](canonical-formulas.md) — L2 формулы
> - [`data-dictionary.md`](data-dictionary.md) — L1 контракт
>
> Дата: 2026-04-28. Реализация: `src/analitica/audit/rule_impl.clj`.
> Тесты: `test/analitica/audit/phase_c_rules_test.clj`.

## Зачем нужны reconciliation rules

L1/L2 контракты гарантируют **внутреннюю консистентность** наших формул
(L2 формулы корректно выражены через L1 поля). Reconciliation rules
гарантируют **внешнюю консистентность** — что наши L1 данные совпадают
с тем, что MP сообщает по другим endpoint'ам или таблицам.

Без них любой расчёт в `domain/*` живёт в изоляции: формула может быть
математически верной, но если входные L1 данные неполные/искажённые
(пропущенные операции, неполная синхронизация, transform bug),
результат всё равно ложный.

Reconciliation rules дают:
1. **Detection** — расхождение появляется как `:suspicious` discrepancy с
   точной локацией и evidence.
2. **Numerical confidence** — без них «работает / не работает» —
   субъективно; с ними есть конкретный delta в рублях.
3. **Drift alarm** — если MP меняет API (новое поле, deprecated endpoint),
   расхождения вылезают на следующем audit-прогоне.

## Catalogue

| Rule ID | Scope | Source A | Source B | Что проверяет |
|---|---|---|---|---|
| `:aggregate-vs-raw` | all MP | `finance` raw | `finance` aggregated by-article | Account-level операции не теряются при group-by article |
| `:sales-qty-triangle` | all MP | `finance` qty | `sales` count | `orders` count | Триангуляция qty между тремя источниками |
| `:unclassified-operations` | all MP | `finance` operations | known whitelist | Surface неизвестных raw operation |
| `:bank-delta` | all MP | `finance.for_pay` | bank reference | Совпадение с банковской выпиской (опционально) |
| `:tax-absence` | all MP | doc-level | doc-level | Документирующее правило (out-of-scope tax) |
| **`:ozon-finance-vs-cashflow`** *(Phase C)* | Ozon | `finance.for_pay` net | `cash_flow_periods.invoice_transfer` | Ozon settlement: per-row vs period-level отчёты сходятся |
| **`:finance-row-internal-consistency`** *(Phase C)* | all MP | `finance.for_pay` | `finance.retail_amount` | Within-row sanity: `for_pay ≤ 2.0 × retail_amount` для sale-rows |
| **`:wb-finance-vs-sales-events`** *(Phase C)* | WB | `finance.for_pay` sale | `sales.for_pay` sale-events | WB cross-endpoint: settlement vs per-event sources |
| **`:ym-buyer-price-anomaly`** *(E-5)* | YM | `finance.retail_amount` | `finance.for_pay` | YM source data quality: BUYER price подозрительно низкая (для_pay > 5× retail) |
| **`:l2-cross-report-agreement`** *(L2-C)* | all MP | `finance.totals` / `pnl/calculate` | `unit_economics.totals` | Same-input metric drift между Finance / P&L / UE — bug в одном из формул |

---

## Phase C правила — детализация

### `:ozon-finance-vs-cashflow`

**Что сравнивается**:
```
SUM(finance.for_pay) sale rows over period
  − SUM(finance.for_pay) return rows over period
⟷
SUM(cash_flow_periods.invoice_transfer) for source=:ozon over overlapping period
```

**Почему важно**: Ozon публикует одни и те же settlement данные через
два независимых endpoint'а:
- `/v2/finance/realization` — per-row, строки попадают в `finance`
- `/v1/finance/cash-flow-statement/list` — period-level, в `cash_flow_periods`

Если они не сходятся — пропущенные транзакции на одной из сторон,
неполная синхронизация, либо transform/normalize ошибка в Ozon
коннекторе.

**Tolerance reasoning**: за один период обе суммы должны совпадать в
пределах `:abs` (по умолчанию 100₽) или `:rel` (1%). Отдельные account-level
service-операции (subscription, packaging) уже учтены — они приходят в
обе таблицы.

**Когда no-op**:
- Scope не Ozon (`marketplace ∈ {:wb :ym}` → пропускается).
- Обе таблицы пусты для периода.

**Discrepancy evidence**:
- `:finance-rows` — кол-во строк в finance.
- `:cash-flow-rows` — кол-во строк в cash_flow_periods.
- `:sales-pay`, `:returns-pay`, `:net-pay` — компоненты разности.
- `:cf-payment` — суммарный payment из cash-flow.

### `:finance-row-internal-consistency`

**Что проверяется**: для строк типа `sale`, инвариант `for_pay ≤ 2.0 × retail_amount`.

**Почему 2.0×, а не 1.0×**: Ozon co-investments (`bank_coinvestment`,
`pick_up_point_coinvestment`, `stars`) могут *легитимно* поднимать
`for_pay` ВЫШЕ `retail_amount` — банки/АПВЗ доплачивают селлеру за
продажу. Phase D real-data calibration показала максимальное
ratio = 1.89× в проде; cap 2.0× отсекает только катастрофические
искажения (например, двойное умножение на quantity или попадание
account-level subsidy в per-row finance). Изначальный cap 1.5×
давал 281 false-positive на февраль-апрель 2026; переход на 2.0×
оставил 5 истинных аномалий — все в YM (см. `:ym-buyer-price-anomaly`).

**Зачем нужен 2.0× cap, если просто можно сравнить per-row компоненты?**
В `finance` row хранится только итоговое `for_pay`, не его
декомпозиция. Эту проверку можно делать в pure-DB запросе без
дополнительных колонок.

**Не проверяем**: обратное направление (`retail_amount >> for_pay`) —
это **ожидаемо**, `for_pay` — netto после комиссий. Нормально, что он
меньше `retail_amount`.

**Discrepancy evidence**:
- `:cap-factor` — 2.0 (для понимания threshold)
- `:outliers` — до 10 строк-нарушителей с `:rrd-id`, `:marketplace`, `:article`, `:quantity`, `:retail-amount`, `:for-pay`
- `:total` — общее количество outlier-строк

### `:wb-finance-vs-sales-events`

**Что сравнивается** (только WB):
```
SUM(finance.for_pay) sale rows over period
⟷
SUM(sales.for_pay) sale events (type ∈ {"sale", "S"}) over same period
```

**Почему важно**: `finance.for_pay` приходит из
`/api/v5/supplier/reportDetailByPeriod` (settlement отчёт WB).
`sales.for_pay` — из `/api/v1/supplier/sales` (event-stream).
Эти endpoint'ы синкаются независимо и независимо обновляются на стороне
WB (settlement отчёт обновляется раз в неделю, event-stream — почти
realtime). При корректной синхронизации они должны совпадать в течение
24 часов после закрытия отчёта.

**Tolerance reasoning**: типичный sync skew (event-stream опережает
settlement на 24h) даёт расхождения в пределах `:rel` 1%. Большие
расхождения сигнализируют:
- Settlement отчёт не подгрузился полностью (одна из недель упущена).
- Event-stream имеет операции, которые WB не учёл в settlement.
- Transform bug (например, неправильный `event_date` мешает overlap-фильтру).

**Когда no-op**:
- Scope не WB.
- Одна из таблиц (`finance` или `sales`) пуста для периода — это
  скорее «ещё не синкали», чем настоящее расхождение. Sync-coverage
  tooling должно его поймать отдельно.

**Discrepancy evidence**:
- `:finance-rows`, `:sales-rows` — кол-ва строк.
- `:finance-sum`, `:sales-sum` — суммы для сравнения.

### `:ym-buyer-price-anomaly` *(E-5)*

**Что проверяется** (только YM): для sale-строк инвариант
`for_pay ≤ 5 × retail_amount`. Cap 5× намеренно очень мягкий —
ловит только катастрофическую YM-quirk: периодически `prices[BUYER].total`
приходит как `1₽`, тогда как фактическая выплата селлеру — тысячи рублей.

**Почему это data-quality, а не transform bug**: наш YM transform
честно сохраняет `BUYER.total` как `retail_amount`. Аномалия — на
стороне YM API (наблюдалась для cash-on-delivery edge cases, partial
delivery, ручных корректировок цены). Без surface-фильтра 5 таких
строк за Mar-Apr 2026 дают gross-ошибку UE на конкретный артикул
(`for_pay/qty` выглядит как 1700₽ при retail 1₽).

**Tolerance reasoning**: cap 5× выбран эмпирически — отсекает класс
«1₽ → 1700₽» (ratio ≈ 1700×), но не флагает легитимные YM субсидии
где `for_pay ≈ 2 × retail`.

**Severity**: `:informational`. Это не bug нашего кода — селлеру
просто полезно знать «эти строки в YM ЛК выглядят странно».

**Discrepancy evidence**:
- `:cap-factor` — 5
- `:outliers` — до 10 YM строк с `:rrd-id`, `:article`, `:event-date`,
  `:retail-amount`, `:for-pay`, `:mp-commission`, `:delivery-cost`
- `:total` — общее количество outlier-строк
- `:note` — `"Cross-check against YM ЛК; not a transform bug"`

### `:l2-cross-report-agreement` *(L2-C)*

**Что сравнивается**: на одних и тех же finance-строках за период
вычисляются три независимых отчёта (Finance.totals / P&L.calculate /
UE.totals); каждая пара (метрика, отчёт-A, отчёт-B) проверяется
на численное совпадение.

**Почему важно**: Finance/P&L/UE — три формуляра, читающие
тот же raw, но через разные функции и фильтры. Если revenue в P&L
≠ revenue в Finance — значит одна из формул дрейфует (другой filter,
другой default, другой rounding). Это самая чистая «is L2 internally
consistent?» проверка.

**Two-tier классификация**:
- **strict-метрики** (`:revenue`, `:sales-qty`, `:returns-qty`,
  `:for-pay`, `:acquiring`, `:acceptance`) — flagged `:suspicious`
  при любом drift сверх ±1₽ / 0.1%.
- **account-level метрики** (`:storage`, `:wb-reward`, `:deduction`,
  `:additional`, `:penalties`, `:logistics`) — drift `:expected`
  by-design: UE дропает строки с `article=""` (account-level
  subsidies, общий WB-reward), Finance/P&L их включают.

**Tolerance**: per-rule override `{:abs 1.0 :rel 0.001}` — strict, потому
что речь о внутренней консистентности кода, не о sync skew или
data quality.

**Discrepancy evidence** (per-pair):
- `:reports-checked` — `[:ue :pnl]` etc.
- `:both-keys` — `{:ue :total-revenue, :pnl :revenue}` (поясняет почему
  ключи разные).
- `:account-level?` — boolean, для классификации.

---

## Tolerance philosophy

Audit framework в `analitica.audit.rules/classify` использует **OR-логику**:
discrepancy classified as `:expected` если *либо* `abs delta ≤ tolerance.abs`,
*либо* `rel delta ≤ tolerance.rel`.

Это намеренно permissive: при больших суммах rel < 1% важнее, при малых
— abs < 100₽ достаточно. Маленькое расхождение — round-off; большое
относительное — bug.

Default tolerance из CLI: `{:abs 10.0 :rel 0.01}`. Большинство правил
наследуют это, но три имеют **per-rule override** (E-3, 2026-04-28):

| Rule | Override | Reason |
|---|---|---|
| `:aggregate-vs-raw` | `{:abs 1000.0 :rel 0.5}` | design-gap rule (by-article вычитает returns, raw суммирует) — drift до 50% legitimate |
| `:wb-finance-vs-sales-events` | `{:abs 100.0 :rel 0.1}` | WB settlement syncs weekly, sales feed near-realtime — sync skew 5-10% normal |
| `:l2-cross-report-agreement` | `{:abs 1.0 :rel 0.001}` | внутренняя консистентность L2 кода — drift только rounding |

Override живёт в `:rule/tolerance` ключе rule-map; framework
`audit.rules/run-rule` подменяет `ctx/tolerance` на время выполнения
правила без mutability в caller-side ctx.

---

## Что делать когда правило сработало

### `:ozon-finance-vs-cashflow` → suspicious

1. Проверить, что `cash_flow_periods` синкнулся за период
   (`metrics-api/sync-coverage`).
2. Проверить, что `finance` для Ozon не урезан фильтром (account-level
   service-операции должны быть в `cash_flow_periods`, не в `finance` —
   они **не** должны входить в SUM).
3. Если cash-flow > finance — пропустили часть realization-периодов
   (партиальный sync).
4. Если finance > cash-flow — один из realization-периодов задвоился,
   либо услуги попали в finance но не в cash-flow.

### `:finance-row-internal-consistency` → outliers

1. Открыть конкретные `:rrd-id` из `:evidence.outliers`.
2. Сравнить с raw данными MP (jdbc query или live API call).
3. Чаще всего — bug в transform.clj, например:
   - Двойное применение quantity множителя.
   - Неверный знак при normalize.
   - Поле `seller_price_per_instance` для Ozon содержит nil → `(* q nil)` бросает / даёт 0.

### `:wb-finance-vs-sales-events` → suspicious

1. Сначала — проверить sync skew. Settlement WB обновляется раз в неделю.
   Если разница между периодом и текущей датой < 7 дней, разница
   ожидаема.
2. Иначе — проверить, что обе синхронизации запускаются регулярно.
3. Большие расхождения требуют ручного сравнения с WB ЛК — выгрузить
   еженедельный finance отчёт и сравнить с нашей версией row-by-row.

---

## Что не покрыто Phase C

### Не реализовано (требует empirical sample)

- **YM `stats/orders` ⟷ `reports/united-netting`** — отчёт downloadable,
  не имеет OpenAPI схемы; нужен реальный сэмпл с production-аккаунта,
  чтобы зафиксировать формат. Phase C ограничилась тем, что доступно
  программно.
- **Ozon `realization` ⟷ `transaction/list`** — оба endpoint'а уже
  питают `finance`, формальная reconciliation между ними внутри
  материализованной таблицы возможна, но требует tracking source-
  endpoint per row (сейчас не сохраняем).

### Намеренно отложено

- **WB account/balance ⟷ SUM(for_pay)** — `account/balance` не
  синхронизируется в нашу DB. Можно добавить (RFC).
- **Cross-MP cumulative — все MP вместе ⟷ суммарный bank reference** —
  bank-delta уже это делает с одним числом; cross-MP version излишняя.

---

## Запуск reconciliation

Через CLI (существующий audit-инструмент):

```bash
clj -M:run -m analitica.audit.core run \
  --period 2026-03 --marketplace ozon \
  --tolerance.abs 100 --tolerance.rel 0.01
```

Через REPL:

```clojure
(require '[analitica.audit.core :as audit]
         '[analitica.audit.rule-impl :as ri])

(ri/register-all!)
(audit/run-reconcile! {:marketplace :ozon
                       :period {:from "2026-03-01" :to "2026-03-31"}
                       :tolerance {:abs 100.0 :rel 0.01}})
```

Returns `{:report ... :exit-code 0|1}`. Exit code 1 если есть `:suspicious`
discrepancies.

Полный perso-rule запуск:

```clojure
(require '[analitica.audit.rules :as r])
(r/run-rule (r/get-rule :ozon-finance-vs-cashflow)
            (r/make-context {:period {:from "2026-03-01" :to "2026-03-31"}
                             :marketplace :ozon
                             :tolerance {:abs 100.0 :rel 0.01}}))
```

---

## Что дальше

Phase C закрывает Reconciliation. Следующие шаги (опциональны,
зависят от приоритетов):

1. **Phase D — L1 contract refresh** — применить оставшиеся P3 RFCs
   (если возникнут бизнес-задачи).
2. **Empirical YM sampling** — сгенерировать `united-marketplace-services`
   и `united-netting` отчёты на production-аккаунте, зафиксировать
   структуру → новая reconciliation rule между YM stats/orders и
   downloadable reports.
3. **Reconciliation в CI** — настроить prod-data audit run раз в сутки,
   alert при появлении `:suspicious` (отдельная инфраструктура).
