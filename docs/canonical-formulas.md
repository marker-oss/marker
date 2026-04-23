# Canonical Formulas (L2)

> **L2 layer of the canon.** Each metric below is defined in terms of L1
> normalized fields documented in [`data-dictionary.md`](data-dictionary.md).
> If a definition needs raw-MP semantics, it links out to the L1 section
> instead of repeating the detail.
>
> Authored 2026-04-23 canon audit; see
> [`superpowers/specs/2026-04-23-canon-audit-l1-l2-design.md`](superpowers/specs/2026-04-23-canon-audit-l1-l2-design.md)
> for design decisions behind the L1/L2 split.

## Report Index

Each report maps to a domain namespace and a per-metric section in this file.
Until Phase 3 rolls out, only Finance, P&L, and Unit Economics are fully
populated; other reports list the namespace but defer full canonization.

| # | Report | Domain ns | Canon section |
|---|---|---|---|
| 1 | Finance          | `analitica.domain.finance`         | [§Finance](#finance)                 |
| 2 | P&L              | `analitica.domain.pnl`             | [§P&L](#pl) *— Phase 3 (2026-04-24)* |
| 3 | Unit Economics   | `analitica.domain.unit-economics`  | [§Unit Economics](#unit-economics)   |
| 4 | ABC              | `analitica.domain.abc`             | *Phase 3*                            |
| 5 | Sales            | `analitica.domain.sales`           | *Phase 3*                            |
| 6 | Stock            | `analitica.domain.stock`           | *Phase 3*                            |
| 7 | Returns          | `analitica.domain.returns`         | *Phase 3*                            |
| 8 | Buyout           | `analitica.domain.buyout`          | *Phase 3*                            |
| 9 | Geography        | `analitica.domain.geography`       | *Phase 3*                            |
| 10| Trends           | `analitica.domain.trends`          | *Phase 3*                            |

## L2 Contract Format (per metric or metric group)

1. **Formula** — prose + math in L1 terms.
2. **Economic justification** — why this definition (theory / practice).
3. **Inputs** — L1 fields with `data-dictionary.md#<table>` anchors.
4. **Edge cases** — zero-division, returns, cancellations, nils, multi-MP.
5. **Verification** — pointer to test in `test/analitica/...`.

---

## Canonical Finance Model

**Статус**: живой эталон. Все финансовые расчёты в проекте обязаны соответствовать этому документу. При расхождении кода и документа — исправлять нужно то, что ошибается (обычно код; если определение устарело — сначала обновить этот документ, потом код).

**Назначение**: зафиксировать, что именно мы считаем и из чего; развести бизнес-определения метрик от деталей API маркетплейсов; сделать добавление нового МП и новой метрики механической работой.

**Аудитория**: разработчики, которые трогают `domain/*`, `marketplace/*/transform.clj`, `audit/*`, а также автор новых метрик в отчётах.

---

### 1. С чего отталкиваемся — бизнес-вопросы

Селлер хочет ответов на следующее. Всё остальное в проекте — средство:

1. **Сколько я заработал чистыми за период?** (P&L → net profit)
2. **Какие артикулы приносят деньги, какие — убытки?** (unit economics по артикулу)
3. **Где уходит маржа — на комиссии МП, логистику, хранение, рекламу?** (декомпозиция издержек)
4. **Соответствует ли наш расчёт тому, что реально пришло на счёт?** (сверка с cash_flow / банком)
5. **Что изменилось относительно прошлого периода / прошлого года?** (тренды — out of scope этого документа)

Формулы из §3 — это инструменты ответа на вопросы 1–4. Вопросы 5+ строятся поверх.

---

### 2. Принцип: canonical-first

```
Business Questions
      │
      ▼
Canonical Metrics  ← формулы пишутся в терминах canonical-row полей
      │
      ▼
Canonical Finance Row  ← API-независимый контракт (§4)
      │
      ▼
Marketplace Transform  ← подгоняет каждый МП под контракт (§6)
      │
      ▼
API endpoints  ← выбираются по тому, дают ли нужные поля
```

**Правило №1**: формулы не знают слова `ppvz_for_pay` или `delivery_commission`. Они знают только поля canonical-row.
**Правило №2**: если новый МП не даёт какое-то поле canonical-row — transform обязан либо вычислить его, либо вернуть `nil`, и это явно документируется в §6.
**Правило №3**: метрики, которые не могут быть посчитаны из-за `nil` в нужных полях, возвращают `nil` (не `0`) и отмечаются в отчёте как "N/A".

---

### 3. Canonical Metrics

Все формулы в терминах canonical-row (§4). Агрегация `SUM(...)` подразумевается по всем строкам в периоде для заданного scope (артикул / МП / аккаунт).

#### 3.1. Выручка (revenue)

```
revenue := SUM(retail_amount) для operation=sale
```
- **Бизнес-смысл**: сумма, которую заплатили покупатели в рознице, до всех комиссий МП, до возвратов.
- **Единицы**: руб.

#### 3.2. Возвраты (returns)

```
returns_revenue := SUM(retail_amount) для operation=return
returns_qty     := SUM(quantity)      для operation=return
```

#### 3.3. Net payout от МП (mp_payout)

```
mp_payout := SUM(for_pay) для operation=sale
           − SUM(for_pay) для operation=return
```
- **Бизнес-смысл**: сколько МП перечислит селлеру (или уже перечислил) за период, с учётом возвратов.
- **Критическое соглашение**: `for_pay` на return-строках **хранится положительным** во всех МП (WB по умолчанию, Ozon приводится в transform, YM приводится). Вычитание идёт через сравнение operation, **не через знак**.
- **Единицы**: руб.

#### 3.4. Commission / эквайринг / СПП (деструктуризация mp_payout)

```
mp_commission  := SUM(wb_commission)   — прямая комиссия МП в руб
mp_reward      := SUM(wb_reward)       — совокупное вознаграждение МП (где применимо)
acquiring      := SUM(acquiring_fee)   — эквайринг (банк. комиссия)
```
- **Важно**: это **информационные** декомпозиции — они уже учтены внутри `for_pay` на уровне API. Отдельно вычитать их из `mp_payout` **нельзя** — будет двойное списание.

#### 3.5. Fulfillment-издержки (логистика, хранение, приёмка)

```
logistics  := SUM(delivery_cost)
storage    := SUM(storage_fee)  ИЛИ  SUM(paid_storage_api.cost) — см. §6.3
acceptance := SUM(acceptance)
```
- **Бизнес-смысл**: платные операции МП, которые на WB приходят **отдельными строками** с `for_pay=0` (а не внутри `for_pay`).
- **Поэтому их можно и нужно вычитать из `mp_payout`** для получения gross-profit — они ещё не вычтены.

#### 3.6. Штрафы, удержания, доплаты (прочие корректировки)

```
penalties  := SUM(penalty)
deduction  := SUM(deduction)
additional := SUM(additional_payment)   — положительное = МП доплачивает
```

#### 3.7. Account-level услуги (подписка, перемещение, …)

Это операции МП **без артикула** (account-level). Они **не попадают** в `by-article`.

```
account_services := из cash_flow_periods (Ozon) ИЛИ из finance-строк с :article=nil (WB)
```

- **Ozon**: извлекается из `cash_flow_periods` — поля `subscription`, `warehouse_movement`, `returns_cargo`, `fines`, `packaging`, `other_services`. См. §6.3.
- **WB**: на текущий момент **не покрыто** (см. B-002: реальная потеря ~0.3%, в пределах tolerance).
- **YM**: не применимо (`stats/orders` даёт всё на уровне заказа).

#### 3.8. COGS (себестоимость)

```
cogs := SUM(cost_price.get(article, barcode) × quantity) для operation=sale
```
- Источник: таблица `cost_prices` (ingest из 1С CSV).
- Если цены нет — считается `0`, метрики с `cogs` возвращают degraded-результат; в отчёте предупреждение.

#### 3.9. Ad-spend (реклама)

```
ad_spend_total            := SUM(ad_stats.spend WHERE marketplace=M AND date ∩ period)
ad_spend_per_article[a]   := распределение ad_spend_total по кампаниям → артикулам
```
- **Текущее ограничение (B-003)**: мульти-артикульные кампании распределяют spend на первый артикул. **Корректное** распределение — пропорционально выручке артикула в рамках кампании — ещё не реализовано.
- **BUG (известный, не починен)**: `pnl.calculate` читает `sum(spend)` **без фильтра по marketplace**. В мультимаркетной установке P&L одной МП включает рекламу другой. Fix — добавить `AND marketplace = ?`.

#### 3.10. Gross Profit (валовая прибыль до рекламы)

```
gross_profit := mp_payout
              − cogs
              − logistics
              − storage
              − acceptance
              − penalties
              − deduction
              + additional        ← additional положительный по определению
              + account_services_income   (Ozon: corrections, compensation)
              − account_services_costs    (Ozon: subscription, warehouse-movement, etc.)
```

**Почему так, а не иначе**:
- `mp_payout` уже **net** от комиссии/эквайринга/СПП — их повторно не вычитаем.
- `mp_payout` **не включает** fulfillment и account-level — их вычитаем отдельно.
- `additional` у WB бывает когда МП возмещает селлеру что-то (+ к прибыли).
- `account_services` — только для тех МП, где они выделены отдельно (сейчас Ozon).

#### 3.11. Net Profit (чистая прибыль)

```
net_profit := gross_profit − ad_spend_total − tax
```
- **tax** — out of scope MVP per [vision §13](./vision.md#13). Селлер сам вычитает налог на своей стороне.
- В коде формула: `net_profit := gross_profit − ad_spend`.

#### 3.12. Производные метрики

```
margin_gross_pct := gross_profit / revenue × 100
margin_net_pct   := net_profit   / revenue × 100
cogs_pct         := cogs         / revenue × 100
drr_pct          := ad_spend     / revenue × 100       — "ДРР"
buyout_rate_pct  := sales_qty    / (sales_qty + returns_qty) × 100
avg_check        := revenue      / sales_qty
profit_per_sale  := net_profit   / (sales_qty − returns_qty)     — прибыль на успешную доставку
```

Все `%`-метрики возвращают `nil` при делении на 0 (см. `util.math/percentage`).

---

### 4. Canonical Finance Row — контракт

Каждая строка в таблице `finance` **обязана** соответствовать этой спецификации, независимо от МП.

#### 4.1. Identity

| Поле | Тип | Обяз. | Семантика |
|---|---|---|---|
| `marketplace` | enum `:wb / :ozon / :ym` | да | источник строки |
| `rrd_id` | integer | да | уникальный id (натуральный у WB, hash у Ozon/YM) |
| `report_id` | integer / nil | нет | id weekly-отчёта (WB), null для остальных |
| `date_from` | ISO date | да | начало периода, к которому относится строка |
| `date_to` | ISO date | да | конец периода |

#### 4.2. Product

| Поле | Тип | Обяз. | Семантика |
|---|---|---|---|
| `article` | string / nil | **nil = account-level** | артикул продавца (WB sa_name, Ozon offer_id, YM shopSku) |
| `nm_id` | long / nil | нет | внутренний id товара в МП |
| `barcode` | string / nil | нет | штрихкод конкретного размера/вариации |
| `subject` | string / nil | нет | предметная категория |
| `brand` | string / nil | нет | бренд |

#### 4.3. Operation

| Поле | Тип | Обяз. | Семантика |
|---|---|---|---|
| `operation` | enum `sale / return / service / adjustment` | да | **canonical**: только эти 4 значения |
| `doc_type` | string / nil | нет | оригинальный тип документа из МП |
| `quantity` | integer | да | штук; для return положительное, для service = 0 |

**⚠️ Известное отклонение от canonical**: сейчас в таблице встречаются строки с `operation = "Логистика"`, `"Хранение"`, `"other"`, и т.п. — это **нарушение контракта**. По-хорошему они должны мапиться в `service`, а оригинал сохраняться в `doc_type`. Нормализация — отдельная задача.

#### 4.4. Revenue side (что покупатель заплатил / получил обратно)

| Поле | Тип | Единицы | Семантика |
|---|---|---|---|
| `retail_price` | decimal | руб/шт | розничная цена до СПП |
| `retail_amount` | decimal | руб | `retail_price × quantity`; **всегда положительное** |
| `sale_percent` | int / nil | % | сидка продавца |
| `price_with_disc` | decimal / nil | руб | цена после скидки |

#### 4.5. MP-side payout (что МП списывает/выплачивает)

| Поле | Тип | Знак | Семантика |
|---|---|---|---|
| `for_pay` | decimal | **≥ 0 всегда** | net payout от МП; для return — абсолютное значение того, что МП вернёт себе |
| `wb_commission` | decimal | ≥ 0 | комиссия МП в руб (уже внутри `for_pay`) |
| `wb_reward` | decimal / nil | ≥ 0 | совокупное вознаграждение МП (WB) |
| `commission_pct` | decimal / nil | % | % комиссии |
| `wb_kvw_prc` | decimal / nil | % | % КВВ (WB-специфичное) |
| `spp_prc` | decimal / nil | % | % СПП (WB) |
| `acquiring_fee` | decimal | ≥ 0 | эквайринг (уже внутри `for_pay`) |
| `delivery_amount` | decimal / nil | — | сумма доставки от WB |
| `return_amount` | decimal / nil | — | сумма возврата от WB |

#### 4.6. Fulfillment costs (платим МП поверх for_pay)

| Поле | Тип | Знак | Семантика |
|---|---|---|---|
| `delivery_cost` | decimal | ≥ 0 | логистика; на WB живёт на отдельных строках operation=Логистика |
| `storage_fee` | decimal | ≥ 0 | хранение; часто 0, заполняется через paid_storage API |
| `acceptance` | decimal | ≥ 0 | приёмка |

#### 4.7. Прочее

| Поле | Тип | Знак | Семантика |
|---|---|---|---|
| `penalty` | decimal | ≥ 0 | штрафы |
| `deduction` | decimal | ≥ 0 | прочие удержания |
| `additional_payment` | decimal | ≥ 0 | доплаты от МП селлеру (корректировки) |

---

### 5. Metric × Required Fields

Матрица "какая метрика что требует". Если хоть одно required-поле = `nil` для всех строк в scope → метрика возвращает `nil` / помечается N/A.

| Метрика | Required canonical fields | Additional sources |
|---|---|---|
| `revenue` | `retail_amount`, `operation` | — |
| `mp_payout` | `for_pay`, `operation` | — |
| `logistics` | `delivery_cost` | — |
| `storage` | `storage_fee` | или `paid_storage` (см. §6.3) |
| `acceptance` | `acceptance` | — |
| `cogs` | `article`, `barcode`, `quantity`, `operation` | `cost_prices` |
| `ad_spend_total` | — | `ad_stats` |
| `gross_profit` | всё выше, кроме ad | `cash_flow_periods` (для Ozon) |
| `net_profit` | gross_profit + ad_spend | — |
| `margin_*`, `*_pct` | соответствующий базовый + `revenue` | — |
| `buyout_rate` | `quantity`, `operation` | — |

---

### 6. Marketplace × Field Coverage

Кто что даёт и как transform приводит к каноническому виду.

#### 6.1. WB (`report-detail-by-period`)

| Canonical field | Источник API | Transform |
|---|---|---|
| `for_pay` | `ppvz_for_pay` | прямое |
| `wb_commission` | `ppvz_sales_commission` | прямое |
| `wb_reward` | `ppvz_reward` | прямое |
| `retail_amount` | `retail_amount` | прямое |
| `delivery_cost` | `delivery_rub` | прямое; **на sale-строках = 0, на отдельных "Логистика"-строках > 0** |
| `operation` | `supplier_oper_name` | `"Продажа"→"sale"`, `"Возврат"→"return"`, **остальное пока остаётся как есть** |
| `article` | `sa_name` | прямое; **`nil` для account-level операций** |
| `acquiring_fee` | `acquiring_fee` | прямое |
| `storage_fee` | `storage_fee` | прямое; как правило 0, полная логистика через paid_storage API |

**Gaps WB**:
- `operation` не нормализован (см. §4.3) — остаются строки "Логистика", "Хранение", "Компенсация ущерба", и т.п. Фильтруются в `by-article` по whitelist `{sale, return, Продажа, Возврат}`, т.е. account-level потери = ~0.3% за период (см. verdicts.md B-002).

#### 6.2. Ozon (`/v2/finance/realization` — после B-005 fix)

| Canonical field | Источник API | Transform |
|---|---|---|
| `for_pay` | `delivery_commission.amount` (sale) / `return_commission.amount` (return) | прямое; **для return приводится в `+`** |
| `wb_commission` | `standard_fee − amount` | **вычисленное**: может быть отрицательным если у продавца был bonus |
| `retail_amount` | `quantity × seller_price_per_instance` | вычисленное |
| `article` | `item.offer_id` | прямое |
| `nm_id` | `item.sku` | прямое |
| `operation` | qty в delivery_commission или return_commission | `"sale"` / `"return"` |
| `delivery_cost`, `storage_fee`, `acceptance` | — | всегда 0 в finance; реальные значения в `cash_flow_periods` |
| `acquiring_fee` | — | всегда 0; реальное значение в `cash_flow_periods.acquiring` |

**Gaps Ozon**:
- Fulfillment- и account-level-расходы **не попадают** в `finance` — они хранятся в `cash_flow_periods` (материализуются отдельно).
- В `pnl.calculate` это компенсируется через `:cf-adjustments` (см. [pnl.clj:47-62](../src/analitica/domain/pnl.clj#L47-L62)).
- **Внимание**: в `unit_economics` компенсация через cash_flow НЕ подключена — Ozon UE по артикулам показывает brutto с завышенной прибылью. Это известное ограничение.

#### 6.3. YM (`/campaigns/{id}/stats/orders`)

| Canonical field | Источник API | Transform |
|---|---|---|
| `for_pay` | `MARKETPLACE price − FEE − AGENCY − DELIVERY_TO_CUSTOMER − PAYMENT_TRANSFER − AUCTION_PROMOTION` | **вычисленное** из per-order commissions |
| `wb_commission` | `FEE + AGENCY` | агрегат |
| `acquiring_fee` | `PAYMENT_TRANSFER` | прямое |
| `delivery_cost` | `DELIVERY_TO_CUSTOMER` | прямое |
| `retail_amount` | `BUYER price × count` | вычисленное |
| `article` | `item.shopSku` | прямое |
| `operation` | `status` | `DELIVERED→sale`, остальное→`return` |
| `storage_fee`, `acceptance`, `penalty`, `deduction` | — | всегда `nil` (не даётся через stats/orders) |

**Gaps YM**:
- Хранение, приёмка, штрафы — недоступны через текущий endpoint. Storage-расходы для YM считаются нулевыми (это занижает издержки).
- Возвраты идентифицируются по `status != "DELIVERED"` — это приближение, возможно захватывает отменённые заказы.
- Мульти-комиссионные заказы: order-level комиссии делятся поровну между items, а не пропорционально стоимости.

#### 6.4. Общая матрица покрытия

| Canonical field | WB | Ozon | YM |
|---|---|---|---|
| `for_pay` | ✅ | ✅ (после B-005) | ✅ (вычисленный) |
| `retail_amount` | ✅ | ✅ | ✅ |
| `article` | ✅ (nil для account-level) | ✅ | ✅ |
| `operation` (normalized) | ⚠️ (не нормализован) | ✅ | ✅ |
| `logistics` | ✅ (отд. строки) | ❌ → cash_flow | ✅ |
| `storage` | ⚠️ (через paid_storage API) | ❌ → cash_flow | ❌ |
| `acceptance` | ✅ | ❌ → cash_flow | ❌ |
| `acquiring_fee` | ✅ | ❌ → cash_flow | ✅ |
| `penalty` | ✅ | ❌ → cash_flow | ❌ |
| `deduction` | ✅ | ❌ | ❌ |
| `additional_payment` | ✅ | ❌ | ❌ |
| `account_services` | ⚠️ (теряются) | ✅ cash_flow | ❌ |

**Легенда**: ✅ есть, ⚠️ частично/обходное решение, ❌ отсутствует.

---

### 7. Known Gaps (чтобы не всплывали повторно)

1. ~~**`pnl.calculate` :ad-spend без marketplace-фильтра**~~ ✅ **Closed 2026-04-22** — `pnl/calculate` принимает `:marketplace` и JOIN'ит `ad_stats ↔ finance.nm_id` для правильного scope.
2. **B-003: ad-spend по мульти-артикульным кампаниям** — текущее распределение неверно, требует weighting по выручке артикула.
3. **WB operation-normalization** — строки "Логистика", "Компенсация ущерба" и т.п. остаются как есть, фильтруются whitelist'ом. По-хорошему → `operation=service` + `doc_type=оригинал`.
4. **Ozon unit_economics без cash_flow-компенсации** — P&L Ozon корректный, UE — нет (завышает прибыль на величину services).
5. **YM — нет storage/acceptance/penalty** — в формулах для YM эти слагаемые = 0, что занижает издержки.
6. **`:spp-amount` = `for_pay − retail`** — сейчас вычисляется в `by-article` как разница и называется "Компенсация СПП". Корректнее либо опираться на `spp_prc × retail`, либо переименовать в "ΔPayout-Retail" без претензий на бизнес-смысл.
7. **B-006: audit-правило `:aggregate-vs-raw`** naive raw-sum для возвратов даёт false positive 28.7% на WB. Требует MP-aware baseline либо evidence-decomposition.

---

### 8. Как добавить

#### 8.1. Новая метрика

1. Добавить определение в §3 (бизнес-смысл + формула в терминах canonical-row).
2. Добавить строку в матрицу §5 (required fields).
3. Проверить по §6.4, что все МП покрывают required поля. Где не покрывают — решить: degraded (`nil`/`0`) или compensating source (см. cash_flow).
4. Реализовать в `domain/*`.
5. Написать audit-правило в `audit/rule_impl` (если метрика ключевая).

#### 8.2. Новый marketplace

1. Написать `marketplace/<mp>/transform.clj` → `->finance-report`, возвращающий записи по схеме §4.
2. Заполнить столбец в §6.4 — какие поля покрыты.
3. Для непокрытых полей — либо `nil`, либо compensating source; задокументировать в §6.x.
4. Добавить EDN-схему в `resources/schemas/<mp>/…` для ответа API (spec 001).
5. Прогнать audit-rules на реальных данных — финализировать gaps в §7.

#### 8.3. Новый endpoint в существующем МП

1. Описать EDN-схему ответа в `resources/schemas/<mp>/<endpoint>.edn`.
2. Если endpoint источник для нового canonical-поля — сначала обновить §4 (контракт), потом transform.
3. Если замещает старый (как `/v2/finance/realization` заменил `/v3/finance/transaction/list`) — оставить verdict в `specs/002-calculation-audit/verdicts.md`, описать причину и сверку.

---

### 9. Ссылки

- [specs/002-calculation-audit/verdicts.md](../specs/002-calculation-audit/verdicts.md) — история баг-гипотез по формулам, принятые решения.
- [specs/001-openapi-schemas/](../specs/001-openapi-schemas/) — формализация API-контрактов (Malli + OpenAPI).
- [docs/vision.md](./vision.md) — границы продукта (особенно §13 про налоги).

---

## Unit Economics

The Unit Economics report decomposes finance-row data **per article** to
answer: "is this article making or losing money, and where does the
margin leak?" All metrics build on L1 fields in
[`data-dictionary.md#finance`](data-dictionary.md#finance),
[`data-dictionary.md#paid_storage`](data-dictionary.md#paid_storage),
[`data-dictionary.md#ad_stats`](data-dictionary.md#ad_stats), and
[`data-dictionary.md#cost_prices`](data-dictionary.md#cost_prices).

Implementation: `src/analitica/domain/unit_economics.clj` `calculate`
(row-level) and `totals` (aggregate). Verification tests:
`test/analitica/domain/unit_economics_canon_test.clj`.

Metrics in this section are grouped to avoid duplicating nearly-identical
block templates. Each group has one 5-point block covering its members.

### UE.1 — Article-level operations and units

**Members:** `sales-qty`, `returns-qty`, `ops`, `net-qty`, `total-ops`.

**Formula**

```
sales-qty[a]   := SUM(quantity)      WHERE operation ∈ {sale-kind}      AND article=a
returns-qty[a] := SUM(quantity)      WHERE operation ∈ {return-kind}    AND article=a
ops[a]         := sales-qty + returns-qty
net-qty[a]     := max(1, sales-qty − returns-qty)    — clamped lower bound
total-ops[a]   := max(1, ops)                        — clamped lower bound
```

**Economic justification.** Buyouts and returns are both physical events
that cost logistics + storage; both count toward `ops`. `net-qty` (what
the buyer kept) is the denominator for per-unit amortization of per-sale
costs (COGS, payout). `total-ops` is the denominator for per-operation
costs (logistics spread across shipments + returns). Clamping to `max(1, …)`
preserves non-nan output when an article has only returns or no data.

**Inputs**

- `finance.operation`, `finance.quantity`, `finance.article` — see
  [`data-dictionary.md#finance`](data-dictionary.md#finance).
- The `{sale-kind}` / `{return-kind}` sets are defined in
  [`data-dictionary.md#finance` → Field dictionary → `operation`](data-dictionary.md#finance).

**Edge cases**

- Article with only returns: `sales-qty = 0`, `returns-qty > 0`,
  `net-qty = 1` (clamped), `total-ops = returns-qty`. Per-unit metrics
  still return finite values.
- Ozon per-service rows have `quantity = 0` or `nil`; they contribute 0
  to these sums and do not distort counts.
- **Known gap:** Current code clamps `net-qty` using
  `(max 1 (- sales-qty returns-qty))` which **hides full-return scenarios**
  — an article where `returns-qty > sales-qty` shows as `net-qty = 1`
  even though net physical throughput is negative. Documented, not fixed
  in Phase 2.

**Verification.** `unit_economics_canon_test.clj` ›
`group-1-qty-and-ops`:

- Given 5 sales + 2 returns for article `A`, asserts
  `sales-qty = 5, returns-qty = 2, ops = 7, net-qty = 3, total-ops = 7`.
- Given 0 sales + 0 returns: `net-qty = 1, total-ops = 1` (clamp kicks in).

---

### UE.2 — Per-article monetary pass-throughs

**Members:** `:revenue`, `:wb-commission`, `:wb-reward`, `:logistics`,
`:storage`, `:acceptance`, `:penalties`, `:acquiring`, `:deduction`,
`:additional`, `:for-pay`, `:total-cost`, `:spp-amount`.

**Formula.** Each is `SUM(<finance.field>) WHERE article=a` over the period,
filtered by operation where applicable (see per-field notes below). UE does
not recompute these — they are pulled directly from `finance/by-article`
(which applies the same semantics as the §Finance section of this document).

| Metric | Source |
|---|---|
| `:revenue`         | `SUM(retail-amount) WHERE operation ∈ sale-kind` |
| `:wb-commission`   | `SUM(wb-commission)` (all rows, includes returns sign-preserved) |
| `:wb-reward`       | `SUM(wb-reward)` |
| `:logistics`       | `SUM(delivery-cost)` |
| `:storage`         | `SUM(storage-fee) + paid_storage.cost` (merged per article — see §Finance) |
| `:acceptance`      | `SUM(acceptance)` |
| `:penalties`       | `SUM(penalty)` |
| `:acquiring`       | `SUM(acquiring-fee)` |
| `:deduction`       | `SUM(deduction)` |
| `:additional`      | `SUM(additional-payment)` |
| `:for-pay`         | `SUM(for-pay) WHERE sale-kind − SUM(for-pay) WHERE return-kind` — see §mp_payout |
| `:total-cost`      | `SUM(cost-price × quantity) WHERE operation ∈ sale-kind` |
| `:spp-amount`      | derived in `finance/by-article` as `for_pay − retail_with_discount` on sales (WB only) |

**Economic justification.** Each pass-through mirrors the Finance section's
per-article breakdown. UE is a *decomposition* report, not a redefinition:
it must agree with Finance exactly for these rows.

**Inputs** — see Finance Model §3.2–3.8 in this document; rows from
[`data-dictionary.md#finance`](data-dictionary.md#finance),
[`data-dictionary.md#paid_storage`](data-dictionary.md#paid_storage),
[`data-dictionary.md#cost_prices`](data-dictionary.md#cost_prices).

**Edge cases.** All nil-tolerant: missing field → 0 in the sum.
`:spp-amount` is nil for Ozon/YM (see Finance §3.4 known gap).
`:total-cost` is 0 when cost_prices has no entry for `(article, barcode)` —
the UE report prints a "cost not loaded" warning in this case.

**Verification.** `unit_economics_canon_test.clj` ›
`group-2-monetary-passthroughs`: on a fixture with 1 sale of 100 at
commission 15 / logistics 5 / storage 2, asserts UE's per-article totals
equal the Finance totals byte-for-byte.

---

### UE.3 — Derived total: `total-wb-costs`

**Formula**

```
total-wb-costs[a] := (wb-reward + logistics + storage +
                     acceptance + penalties + acquiring + deduction)
```

*Note:* `:additional` is excluded (it's a CREDIT to the seller, not a
cost); `:ad-spend` is tracked separately (see UE.5); `:total-cost` is COGS,
not a marketplace cost.

**Economic justification.** This is the "all marketplace-side bites" figure
— everything the MP took from the seller in one line. It's the numerator
of `wb-cost-pct`. Excluding `:additional` prevents double-counting
(additional reduces the cost, not adds to it).

**Inputs.** All seven UE.2 pass-through fields, all from
[`data-dictionary.md#finance`](data-dictionary.md#finance).

**Edge cases.** Sum-of-zeros returns 0. No divisor here, so no zero-div
concerns.

**Verification.** `unit_economics_canon_test.clj` › `group-3-total-mp-cost`:
asserts `total-wb-costs = wb-reward + logistics + storage + acceptance
+ penalties + acquiring + deduction` on a manually-constructed row with
each field non-zero.

---

### UE.4 — Article profit (absolute)

**Formula**

```
profit[a] := for-pay
           − total-cost
           − logistics
           − storage
           − penalties
           − acceptance
           − deduction
           − ad-spend
           + additional            ← additional is a credit, add back
```

**Economic justification.** Profit = cash in (MP payout) − direct variable
costs. `for-pay` is already net of `:wb-commission`, `:wb-reward`,
`:acquiring`, `:spp-amount` at the MP side (see canonical Finance Model
§3.4). Re-subtracting them would double-count. `:total-cost` (COGS) is
subtracted because it's from the 1C side, not MP side. `:logistics /
:storage / :penalties / :acceptance / :deduction` are MP costs NOT already
inside `:for-pay` on WB (they arrive as separate `finance_rows` with
`for_pay = 0`; see `wb-logistics-on-separate-rows` observation in memory).
`:additional` is a seller credit (WB occasionally refunds something) —
added. `:ad-spend` is allocated per article from `ad_stats` (WB) or the
`ad-cost` column of `finance_rows` (Ozon/YM).

**Inputs.**

- `for-pay` from [`data-dictionary.md#finance`](data-dictionary.md#finance).
- `total-cost` from [`data-dictionary.md#cost_prices`](data-dictionary.md#cost_prices) × quantity.
- `ad-spend` from [`data-dictionary.md#ad_stats`](data-dictionary.md#ad_stats) (WB) or `finance.ad-cost` (all MP).
- Other per-article fields from [`data-dictionary.md#finance`](data-dictionary.md#finance).

**Edge cases.**

- `additional = 0` most of the time; safe nil → 0.
- `ad-spend` nil when ad_stats not synced → treated as 0; margin over-states.
- Negative `profit` is valid (loss-making article).
- **Known gap:** Code computes profit **without subtracting `:acquiring`**.
  The canonical Finance §3.10 says `gross_profit = mp_payout − cogs −
  logistics − storage − acceptance − penalties − deduction + additional`.
  `mp_payout = for_pay_sale − for_pay_return` already subtracts acquiring
  at the row level via the MP API, so UE is correct not to double-subtract.
  Verified: UE profit matches P&L net profit on single-article fixtures
  (see Phase-2 test `profit-matches-pnl-single-article`).

**Verification.** `unit_economics_canon_test.clj` › `group-4-profit`:
asserts profit formula on a hand-built fixture covering all 9 summands;
asserts agreement with P&L `net-profit` on a single-article period.

---

### UE.5 — Ad spend allocation

**Formula**

```
ad-spend[a] := ad-spend-by-article[a] OR 0
```

where `ad-spend-by-article` is produced by
`analitica.db/ad-spend-by-article` from `ad_stats` (WB) or `finance.ad-cost`
(Ozon/YM), allocated per article per the rules in §Finance §3.9.

**Economic justification.** Advertising is a marketing cost attributable
to the article it drove orders to. For campaigns covering multiple
articles, the allocation is proportional to revenue per article within the
campaign (spec 003 US5 migration). If no allocation is available (nil),
we treat `ad-spend = 0` — this understates costs but is preferable to
dropping the article from the report.

**Inputs.** `ad_stats` (WB) per
[`data-dictionary.md#ad_stats`](data-dictionary.md#ad_stats); `finance.ad-cost`
per [`data-dictionary.md#finance`](data-dictionary.md#finance).

**Edge cases.**

- Article with ads but no sales: appears in UE with `profit = −ad-spend`.
- Ozon/YM: ad-spend comes from `finance.ad-cost` directly (no per-campaign
  breakdown stored).
- **Known gap (B-003, legacy).** Multi-article WB campaigns with unresolved
  apps[].nm currently go to `nm_id=0` in `ad_stats` — spec 003 US5
  migrated to proportional-by-revenue, but historical rows may still use
  first-article allocation.

**Verification.** `unit_economics_canon_test.clj` › `group-5-ad-spend`:
asserts article with ad-spend-by-article = {A 100} has `ad-spend = 100`,
no-entry article has `ad-spend = 0`, sum across articles equals total
ad_stats spend in the period.

---

### UE.6 — Per-unit amortization (families)

**Members:** `:revenue-per-unit`, `:reward-per-unit`, `:cost-per-unit`,
`:acquiring-per-unit` (denominator = `sales-qty`); `:logistics-per-unit`,
`:storage-per-unit`, `:accept-per-unit`, `:payout-per-unit`,
`:profit-per-unit` (denominator = `net-qty`); `:logistics-per-op`
(denominator = `total-ops`).

**Formula**

```
per-unit metric := metric-total / denominator        [round to 2 dp]
```

Denominator depends on the metric's cost category:

| Category | Denominator | Metrics |
|---|---|---|
| per-sale amortized | `sales-qty` | revenue, reward, cost, acquiring |
| per-kept-unit amortized | `net-qty` (sales − returns, clamped ≥1) | logistics, storage, acceptance, payout, profit |
| per-operation amortized | `total-ops` (sales + returns, clamped ≥1) | logistics-per-op |

All divisions use `math/safe-div` (returns `0` on divide-by-zero) and
`math/round2`.

**Economic justification.** Different costs attach to different events:

- *Per-sale* — revenue and commissions realize at sale (regardless of
  later returns). Cost-of-goods commits at sale too (the unit left the
  warehouse). Acquiring is per-transaction.
- *Per-kept-unit* — logistics/storage aren't "returned" when a buyer
  returns; but their per-unit burden on the seller's margin is measured
  against the *units the buyer kept* (net). Payout per unit and profit
  per unit use the same denominator because they describe "what you
  earned per successful delivery."
- *Per-operation* — "logistics-per-op" answers "what does one shipment
  (outbound or return) cost me on this article" — useful for benchmarking
  MP rate cards.

`logistics` intentionally has **two** per-unit views (`-per-op` and
`-per-unit`) because the question they answer differs.

**Inputs.** All UE.2 pass-throughs + UE.1 counts.

**Edge cases.**

- Division-by-zero prevented by `safe-div` → `0` output (not `nil`).
- Clamped denominators mean `per-unit` numbers for an empty-article row
  are 0 / 1 = 0 — safe but meaningless; report consumers should filter.
- Rounding to 2 dp can mask sub-kopek differences in rec reconcile.

**Verification.** `unit_economics_canon_test.clj` › `group-6-per-unit`:
asserts each per-unit metric equals its total divided by the correct
clamped denominator on a fixture with 5 sales / 2 returns / known totals.

---

### UE.7 — Percentage metrics

**Members:** `:buyout-rate`, `:margin-pct`, `:wb-cost-pct`, `:cogs-pct`,
`:logistics-pct`, `:drr-pct`.

**Formula**

```
buyout-rate[a]  := sales-qty / ops × 100               (via math/percentage)
margin-pct[a]   := profit / revenue × 100
wb-cost-pct[a]  := total-wb-costs / revenue × 100
cogs-pct[a]     := total-cost / revenue × 100
logistics-pct[a]:= logistics / revenue × 100
drr-pct[a]      := ad-spend / revenue × 100
```

**Economic justification.**

- `buyout-rate` = conversion of ordered to kept. Core marketplace KPI.
- `margin-pct` = operational margin (post all direct costs and ads).
- `*-pct` share-of-revenue metrics isolate where margin leaks go.
- `drr-pct` ("ДРР" — доля рекламных расходов) — marketing spend as %
  revenue, standard Russian marketplace KPI.

**Inputs.** All derive from UE.1 counts + UE.2 monetary + UE.3/4 totals.

**Edge cases.**

- `ops = 0` → `buyout-rate = nil` (division by zero handled in
  `math/percentage`).
- `revenue = 0` → all `*-pct` metrics = nil (meaningful: no denominator).
- Clamps in UE.1 do not leak here — `percentage` uses raw counts.
- Negative `profit` yields negative `margin-pct` (valid: loss-making
  article).

**Verification.** `unit_economics_canon_test.clj` › `group-7-percentages`:
asserts each %-metric equals numerator / denominator × 100 on a
non-trivial fixture, and returns nil on zero-denominator fixtures.

---

### UE.8 — Summary monetary totals (aggregation)

**Members:** `:total-revenue`, `:total-wb-reward`, `:total-logistics`,
`:total-storage`, `:total-acceptance`, `:total-penalties`,
`:total-acquiring`, `:total-deduction`, `:total-additional`,
`:total-ad-spend`, `:total-wb-costs`, `:total-spp`, `:total-for-pay`,
`:total-cost`, `:total-profit`.

**Formula**

```
total-X := SUM(row-level-X) across all article rows      [round to 2 dp]
```

where `row-level-X` is the matching per-article metric from UE.2, UE.3,
UE.4, UE.5, or a pass-through. Aggregation ignores nil values (treats as 0).

**Economic justification.** Period-level roll-up answers "how did this
seller's account perform across all articles in this window." It's the
UE equivalent of P&L's top-line figures but computed from the UE
decomposition, so rounding differences vs P&L appear only at 2nd-decimal
resolution.

**Inputs.** Every row-level metric from UE.1-UE.5.

**Edge cases.**

- Empty period → all totals = 0.
- **Known issue: `:total-profit` may differ from P&L `:net-profit`** by
  up to 2 kopek per article × article count due to independent rounding
  of per-article `profit`. For reconciliation-grade numbers, P&L is the
  source of truth; UE totals are decomposition-consistent.
- Rows with `:additional = nil` safely treated as 0.

**Verification.** `unit_economics_canon_test.clj` › `group-8-totals-sum`:
asserts each `total-*` = sum of per-article `*` on a 3-article fixture;
asserts UE total-profit agrees with P&L net-profit within 0.1 RUB
tolerance.

---

### UE.9 — Summary derived metrics

**Members:** `:margin-pct`, `:wb-cost-pct`, `:cogs-pct`, `:drr-pct`,
`:profit-per-sale`, `:avg-check`, summary `:buyout-rate`.

**Formula**

```
margin-pct     := total-profit / total-revenue × 100
wb-cost-pct    := total-wb-costs / total-revenue × 100
cogs-pct       := total-cost / total-revenue × 100
drr-pct        := total-ad-spend / total-revenue × 100
profit-per-sale:= total-profit / net-qty             ← net-qty = sales-qty − returns-qty (NOT clamped at summary level)
avg-check      := total-revenue / sales-qty
buyout-rate    := sales-qty / (sales-qty + returns-qty) × 100
```

where summary `sales-qty`, `returns-qty` are non-clamped sums across all
articles.

**Economic justification.** Same as UE.7 but at period level. Notably,
`net-qty` at summary level is **not clamped** (code uses raw subtraction),
unlike per-article where it is clamped to ≥1. This is because the report
consumer generally filters out zero-activity periods before reading
the summary, and a negative net-qty at summary-level genuinely means the
period had more returns than sales (a legitimate business state).

**Inputs.** UE.8 totals + summary `sales-qty`/`returns-qty`.

**Edge cases.**

- `net-qty ≤ 0` at summary → `profit-per-sale` via `safe-div` → 0. This
  is a LOSS-dominated period — the metric becomes uninformative but
  doesn't crash.
- `sales-qty = 0` → `avg-check = 0`, `buyout-rate = nil`.
- `total-revenue = 0` → all `*-pct` = nil.

**Verification.** `unit_economics_canon_test.clj` › `group-9-summary-derived`:
asserts each summary derived metric on a fixture matching UE.7 per-article
test, then asserts summary equals weighted average of per-article.

---

### UE.10 — Marketplace coverage matrix

| Metric family | WB | Ozon | YM |
|---|---|---|---|
| UE.1 (counts)                 | ✅ | ✅ | ✅ |
| UE.2 `:revenue`               | ✅ | ✅ | ✅ |
| UE.2 `:wb-commission`         | ✅ | ✅ (reused for commission_amount) | ✅ |
| UE.2 `:wb-reward`             | ✅ | ❌ (nil) | ❌ (nil) |
| UE.2 `:logistics`             | ✅ | ✅ (per-service rows) | partial (per bidFee only) |
| UE.2 `:storage`               | ✅ (paid_storage) | ✅ (per-service) | ❌ (no API) |
| UE.2 `:acceptance`            | ✅ | ❌ (nil) | ❌ (nil) |
| UE.2 `:penalties`             | ✅ | ✅ (cash_flow_periods fines) | ❌ (nil) |
| UE.2 `:acquiring`             | ✅ | ❌ (inside for-pay) | ❌ (inside for-pay) |
| UE.2 `:deduction`             | ✅ | partial | ❌ |
| UE.2 `:additional`            | ✅ | ❌ | ❌ |
| UE.2 `:for-pay`               | ✅ | ✅ | ✅ |
| UE.2 `:total-cost` (COGS)     | from 1C (all MP) |
| UE.2 `:spp-amount`            | ✅ | ❌ | ❌ |
| UE.3 `:total-wb-costs`        | ✅ | partial (depends on component coverage) | partial |
| UE.4 `:profit`                | ✅ | ✅ | ✅ |
| UE.5 `:ad-spend`              | ad_stats table | finance.ad-cost | finance.ad-cost |
| UE.6 per-unit                 | ✅ | ✅ | ✅ |
| UE.7 percentages              | ✅ | ✅ | ✅ |
| UE.8 totals                   | ✅ | ✅ | ✅ |
| UE.9 derived summary          | ✅ | ✅ | ✅ |

### UE.11 — Known gaps (Phase 2 exit state)

1. **Account-level costs not included for WB** (B-002). WB does not
   expose subscription / warehouse-movement / fines at per-article grain;
   these flow through `finance` rows with `article = nil` and are not
   surfaced in UE. Estimated unaccounted cost: ~0.3% of revenue.
2. **Ad-spend marketplace filter missing in legacy P&L path** (B-003).
   `pnl/ad-spend-total` historically SELECTed from ad_stats without
   filtering `marketplace`, so in multi-MP installs UE profit for one
   MP could be inflated/deflated by another MP's spend. Fixed for the
   canonical per-article path (UE.5); `pnl` path still has the read.
   **This does not affect UE's `profit` or `:total-profit`** because
   UE uses `ad-spend-by-article`, which is already MP-filtered.
3. **Net-qty clamp hides full-return scenarios** (UE.1 edge case). An
   article with 2 sales and 5 returns shows `net-qty = 1` instead of
   `-3`. Per-unit metrics are misleading for such articles; UE report
   flags them in the "Убыточные артикулы" section via `profit < 0`,
   which is the practical escape hatch.
4. **Rounding drift UE.total-profit vs P&L.net-profit** (up to
   2 kopek × article-count). UE totals are per-article-sum; P&L is
   grand-total. For regulatory reconciliation, use P&L.
5. **Storage for YM is 0** (see UE.10). Margin is overstated for YM
   sellers who do use YM FBO storage.
6. **`:spp-amount` semantic ambiguity.** UE passes it through without a
   formula of its own because Finance §7.6 flags its definition as
   unclear. `:spp-compensation` = rounded pass-through only.
7. **COGS coverage gap** (Phase-2 finding 2026-04-23). The 1C CSV loaded
   into `analitica.domain.cost-price` groups prices as
   `{article → first_barcode_price}`. About 45% of sold barcodes on WB
   March 2026 have no article-level match — those sales get COGS = 0,
   artificially improving reported margin. Underlying issue is 1C export
   not covering all barcodes, not a code bug. Needs product action;
   alternately the loader could warn when matched ratio drops below a
   threshold.
8. **Ozon realization is month-aggregated.** Unlike WB/YM where event_date
   is per-event, Ozon `/v2/finance/realization` delivers monthly batches
   keyed by article. All rows materialised from a single realization
   response share `event_date = header.start_date` (month-first).
   Queries narrower than one month give approximate results for Ozon
   (they include all of the covering month). Per-transaction granularity
   requires leaning on `/v3/finance/transaction/list` (which does have
   `operation_date`); the hybrid path UPDATEs cost fields but does not
   re-date the realization rows.

**Resolved during Phase-2 verification:**

- **Overlap-inflation in finance queries** (formerly tracked here as an
  open gap). Resolved by the 2026-04-23 `event_date` migration: finance
  rows now carry the per-event date extracted at transform time, and
  `db-finance` filters by `event_date BETWEEN` rather than report-period
  overlap. Legacy rows without event_date fall back to overlap semantics
  — re-materialising from `raw_data` eliminates the fallback path. Impact
  on WB March 2026: revenue 1,107k → 854k, logistics 318k → 238k.
- **WB paid_storage 3× inflation** (transform multiplied
  `warehousePrice × barcodesCount` when `warehousePrice` was already a
  total). Resolved 2026-04-23 in
  `marketplace/wb/transform.clj/->storage-cost` plus a coalesce pass in
  `->storage-costs` that sums duplicate raw rows sharing one
  (date, barcode, warehouse) key before insert. Impact on WB March 2026:
  storage 362k → 110k.

### UE.12 — Verification summary

- All 9 metric groups have at least one deftest in
  `test/analitica/domain/unit_economics_canon_test.clj`.
- Integration test: `profit-matches-pnl-single-article` asserts UE's
  per-article `:profit` equals P&L's `:net-profit` when the fixture has
  one article on one MP with ad-spend allocated.
- Reconciliation test: on a 3-article fixture, `SUM UE.profit` equals
  P&L `:net-profit` within 0.1 RUB.
- Regression coverage: `clojure -M:test` green on the whole suite.

---

## P&L

The P&L report rolls the finance event stream up to period-level cash
items: gross profit, net profit, margins, and — for Ozon — the
cash-flow-statement adjustments that account for charges without a
per-article attribution. All metrics build on L1 fields in
[`data-dictionary.md#finance`](data-dictionary.md#finance),
[`data-dictionary.md#ad_stats`](data-dictionary.md#ad_stats),
[`data-dictionary.md#cost_prices`](data-dictionary.md#cost_prices), and
[`data-dictionary.md#cash_flow_periods`](data-dictionary.md#cash_flow_periods).

Implementation: `src/analitica/domain/pnl.clj/calculate`. Verification
tests: `test/analitica/domain/pnl_canon_test.clj`.

Authored 2026-04-24 using the 5-point template from §Unit Economics.
Uses the same post-2026-04-23 ingest pipeline (event_date filter, Ozon
`bonus` + `compensation` in for_pay, YM subsidies, WB paid_storage
single-multiplication fix) — absolute numbers match UE totals within
the rounding tolerance documented in UE.11 #4.

### P&L.1 — Period monetary aggregates (pass-throughs)

**Members:** `:revenue`, `:wb-reward`, `:logistics`, `:storage`,
`:acceptance`, `:penalties`, `:deduction`, `:additional`, `:for-pay`,
`:cogs`.

**Formula**

```
revenue    := SUM(article.revenue)       across by-article rows
wb-reward  := SUM(article.wb-reward)
logistics  := SUM(article.logistics)
storage    := SUM(article.storage)
acceptance := SUM(article.acceptance)
penalties  := SUM(article.penalties)
deduction  := SUM(article.deduction)
additional := SUM(article.additional)
for-pay    := SUM(article.for-pay)       ← already net = sale − return
cogs       := SUM(article.total-cost)
```

Each `article.<field>` is the per-article aggregate defined in
[§Unit Economics UE.2](#ue2--per-article-monetary-pass-throughs).
P&L never recomputes them — it grand-totals UE's row output. Rounding
to 2 dp.

**Economic justification.** P&L is the seller-facing period summary;
keeping it as a sum over the UE decomposition means every cost line on
P&L can be drilled to the article level. Zero formula divergence — UE
totals and P&L aggregates are identical by construction.

**Inputs.** All UE.2 per-article fields derived from
[`data-dictionary.md#finance`](data-dictionary.md#finance). Storage is
coalesced with [`paid_storage`](data-dictionary.md#paid_storage) via
`db/storage-by-article` in the UE path.

**Edge cases.**

- Missing fields (nil) are skipped — `reduce + 0.0 … (or (:foo %) 0)`.
- `:for-pay` on P&L already subtracts returns (UE.2 semantics for WB,
  Ozon `amount+bonus+compensation+stars`, YM `buyer − commissions +
  subsidies`).
- Empty period → all aggregates = 0.0.

**Verification.** `pnl_canon_test.clj` › `group-1-aggregates`: on a
fixture with known per-article values, asserts each P&L aggregate
equals the sum across articles.

---

### P&L.2 — Ad spend total

**Formula**

```
ad-spend := canonical-path OR legacy-fallback

canonical-path := SUM(finance.ad_cost) for period and marketplace
legacy-fallback := SUM(ad_stats.spend) JOIN finance.nm_id for the same

preference order:
  YM or Ozon  → canonical only (never legacy)
  WB or all   → canonical if > 0; otherwise legacy fallback
```

See pnl.clj lines 59-90 for the dispatch.

**Economic justification.** Rollup of advertising spend for the period.
Since WB historically stored ad spend in a separate `ad_stats` table,
the legacy fallback covers pre-spec-003 data. Post-migration periods
use the canonical `finance.ad_cost` field (populated by spec 003 US5 for
WB and natively by YM/Ozon ingest) so the number is always MP-filtered
and consistent with UE.5.

**Inputs.** `finance.ad_cost` for the canonical path;
[`data-dictionary.md#ad_stats`](data-dictionary.md#ad_stats) via
nm_id-JOIN for the fallback.

**Edge cases.**

- Canonical SUM = 0 for WB triggers legacy path, even if legacy also
  returns 0.
- Both paths filter by marketplace when provided; all-MP queries on the
  legacy path sum across MPs (behaviour of spec-003 pre-migration).
- DB-schema drift (missing `ad_cost` column) → canonical returns `nil`,
  legacy used.
- Negative ad_spend is never expected; not clamped.

**Verification.** `pnl_canon_test.clj` › `group-2-ad-spend-*`:
synthetic fixture with preset ad_cost asserts canonical path hit; a
separate fixture with zero ad_cost on WB plus legacy ad_stats triggers
fallback.

---

### P&L.3 — Gross profit

**Formula**

```
gross-profit := for-pay
              − cogs
              − logistics
              − storage
              − penalties
              − acceptance
              − deduction
              + additional      ← additional is seller credit, add back
```

**Economic justification.** Direct mirror of UE.4 profit at the period
level. `for-pay` is already net of MP commission / acquiring / SPP /
bonus (per UE.2 semantics per MP); UE.4 argues why ad-spend is kept
outside gross and subtracted only in net (below). P&L inherits that
separation.

**Inputs.** All P&L.1 aggregates.

**Edge cases.**

- Negative `gross-profit` is legitimate (loss-making period).
- `additional` is rare in practice for Ozon/YM (usually 0 per UE.10
  coverage matrix); for WB it's a seller credit (refunds) that increases
  profit.

**Verification.** `pnl_canon_test.clj` › `group-3-gross-profit`:
substitute into the formula and assert equality with `pnl/calculate`
output on a fixture.

---

### P&L.4 — Net profit and margins

**Members:** `:net-profit`, `:margin-gross`, `:margin-net`.

**Formula**

```
net-profit    := gross-profit − ad-spend
margin-gross  := gross-profit / revenue × 100
margin-net    := net-profit   / revenue × 100
```

**Economic justification.** Advertising isn't a unit cost per se (no
article directly produces it), so in the canonical break it's
subtracted once at the period level. `margin-*` as share-of-revenue is
the standard business KPI. No tax is subtracted — MVP-scope per
[vision §13](./vision.md#13).

**Inputs.** P&L.3 + P&L.2 + P&L.1 revenue.

**Edge cases.**

- `revenue = 0` → both margins = nil (`math/percentage` handles div-by-0).
- Negative margins are valid.
- When `ad-spend` legacy fallback fires with 0 result on WB, margins
  equal gross margins (no ad cost to subtract).

**Verification.** `pnl_canon_test.clj` › `group-4-net-profit`:
asserts net = gross − ad-spend, margins = ratios of the corresponding
profits to revenue.

---

### P&L.5 — Quantity and per-event derivatives

**Members:** `:sales-qty`, `:returns-qty`, `:buyout-rate`, `:avg-check`,
`:profit-per-sale`, `:articles`.

**Formula**

```
sales-qty   := SUM(article.sales-qty)
returns-qty := SUM(article.returns-qty)
net-qty     := sales-qty − returns-qty       (NOT clamped at summary level)
buyout-rate := sales-qty / (sales-qty + returns-qty) × 100
avg-check   := revenue   / sales-qty
profit-per-sale := net-profit / net-qty
articles    := count of distinct articles in the period
```

**Economic justification.** Identical to UE.9 derivations — P&L is UE
aggregated. `profit-per-sale` uses a non-clamped `net-qty` (may be ≤ 0
on loss-dominated periods) and then `safe-div` returns 0 in that
degenerate case, same as UE.

**Inputs.** P&L.1 quantities + P&L.4 net-profit.

**Edge cases.**

- `sales-qty = 0` → `avg-check = 0`, `buyout-rate = nil`.
- More returns than sales (net-qty ≤ 0) → `profit-per-sale = 0` via
  safe-div clamp.
- `:articles` counts articles in by-article after UE's grouping — zero
  when no finance data for the period.

**Verification.** `pnl_canon_test.clj` › `group-5-quantities`:
asserts each derivative against the known fixture values.

---

### P&L.6 — Ozon cash-flow adjustments (optional)

**Members** (only present when `cf-adjustments` argument is supplied):
`:cf-subscription`, `:cf-warehouse`, `:cf-returns-cargo`, `:cf-fines`,
`:cf-packaging`, `:cf-other-services`, `:cf-corrections`,
`:cf-compensation`, `:cf-costs`, `:cf-income`, `:cf-total`,
`:adjusted-gross`, `:adjusted-net`, `:adjusted-margin`.

**Formula**

```
cf-costs       := cf-subscription + cf-warehouse + cf-returns-cargo
                + cf-fines + cf-packaging + cf-other-services
cf-income      := cf-corrections + cf-compensation
cf-total       := cf-costs + cf-income
adjusted-gross := gross-profit + cf-total
adjusted-net   := adjusted-gross − ad-spend
adjusted-margin:= adjusted-net / revenue × 100
```

The `cf-*` pass-throughs are read from `cash_flow_periods` via
`analitica.db/cash-flow-adjustments` and fed in by the report layer
(pnl.clj `load-cf-adjustments`). `pnl/calculate` treats them as plain
numbers (sign already applied at the source — costs arrive positive,
income arrives positive, and the canon sums them directly).

**Economic justification.** Ozon bills account-level services
(subscriptions, FBO warehouse moves, returns cargo, platform fines,
packaging, misc services) without per-article attribution. Ignoring
them understates Ozon's real cost structure by the P&L coverage (UE.11
already flags this as UE.10 known gap). The cash-flow-statement is
Ozon's authoritative source for these amounts; we fold them into an
`adjusted-*` variant alongside the per-article `gross/net` so callers
can choose the view appropriate for their use case.

**Inputs.**
[`data-dictionary.md#cash_flow_periods`](data-dictionary.md#cash_flow_periods)
via `db/cash-flow-adjustments`, scoped to the requested date range.

**Edge cases.**

- For WB / YM the caller should NOT pass `:cf-adjustments` — `report`
  only loads them when `:marketplace = :ozon`.
- When the caller passes an empty map `{}` the adjusted-* fields are
  still emitted (all zeros).
- `corrections` / `compensation` may be negative in rare cases (Ozon
  reversing a previously-issued compensation); canon sums algebraically.
- `:adjusted-margin` returns nil when revenue = 0.

**Verification.** `pnl_canon_test.clj` › `group-6-cf-adjustments`:
synthetic cf-adjustments map asserts each aggregate; cross-checks that
`adjusted-gross = gross-profit + cf-total` holds exactly.

---

### P&L.7 — Marketplace coverage matrix

| Metric family | WB | Ozon | YM |
|---|---|---|---|
| P&L.1 monetary pass-throughs | ✅ (inherits UE.2 per-MP coverage) |
| P&L.2 `:ad-spend` canonical  | migrating | ✅ from `finance.ad_cost` | ✅ from `finance.ad_cost` |
| P&L.2 `:ad-spend` legacy fallback | ✅ when canonical is 0 | ❌ not used | ❌ not used |
| P&L.3 `:gross-profit`         | ✅ | ✅ | ✅ |
| P&L.4 `:net-profit`           | ✅ | ✅ | ✅ |
| P&L.5 quantities              | ✅ | ✅ | ✅ |
| P&L.6 cf-adjustments          | ❌ (no WB cash-flow endpoint) | ✅ auto-loaded for `:marketplace :ozon` | ❌ (no YM cash-flow endpoint) |

### P&L.8 — Known gaps (inherited + P&L-specific)

All UE.11 gaps apply (they ride the same ingest pipeline). P&L adds:

1. **Legacy ad-spend fallback has no MP filter on the `nil` marketplace
   path.** When P&L is called with `:marketplace nil` and canonical
   ad_cost is 0, `legacy-ad-spend-sum` returns a cross-MP `SUM(spend)`.
   Harmless for single-MP deployments; incorrect for multi-MP P&L
   sweeps. `pnl/calculate` is almost always called per-MP via
   `pnl/report`, so real impact is minimal.
2. **P&L does not subtract `:acquiring`** — acquiring is already inside
   `for_pay` on WB/Ozon (and rolled into YM commissions) so subtracting
   it on P&L would double-count. Same semantics as UE.4.
3. **No tax line.** Out of scope for MVP. Seller applies their own tax
   rate downstream.
4. **CF adjustments only for Ozon.** WB has account-level services
   (subscription, promotional fees, ~0.3% of revenue) that don't reach
   per-article rows and aren't in any endpoint we ingest. Inherited
   UE.11 #1 / B-002.

### P&L.9 — Verification summary

- Every P&L.N group has a deftest in
  `test/analitica/domain/pnl_canon_test.clj`.
- `group-reconcile-with-ue`: asserts that on a shared fixture,
  `pnl/calculate` gross-profit matches the UE-formula grand-total
  within 0.1 RUB.
- Regression coverage: `clojure -M:test` green on the whole suite.

---

## Finance

The Finance report decomposes the event stream to the per-article level: it
is the bookkeeping view that precedes P&L and UE. Where UE computes derived
KPIs (margin, profit) and P&L aggregates them to one period row, Finance
exposes the raw monetary flows grouped by article, then optionally rolls
them into a period total or a weekly (`by-report-id`) split.

Implementation: `src/analitica/domain/finance.clj` — `by-article`, `totals`,
`by-report-id`. Verification tests:
`test/analitica/domain/finance_canon_test.clj`.

Authored 2026-04-24 using the 5-point template from §Unit Economics.
Uses the same post-2026-04-23 ingest pipeline (event_date filter, Ozon
`bonus` + `compensation` in for_pay, YM subsidies, WB paid_storage
single-multiplication fix).

---

### Finance.1 — `article-row` per-article aggregates

**Members:** `:revenue`, `:wb-reward`, `:wb-commission`, `:acquiring`,
`:sales-pay`, `:returns-pay`, `:logistics`, `:penalties`, `:additional`,
`:acceptance`, `:deduction`.

**Formula**

```
;; partition by operation type first
sales-lines  := filter operation ∈ {"sale","Продажа"}
return-lines := filter operation ∈ {"return","Возврат"}

revenue       := round2 SUM(retail-amount   over sales-lines)
wb-reward     := round2 SUM(wb-reward       over ALL lines)
wb-commission := round2 SUM(wb-commission   over sales-lines)
acquiring     := round2 SUM(acquiring-fee   over ALL lines)
sales-pay     := round2 SUM(for-pay         over sales-lines)
returns-pay   := round2 SUM(for-pay         over return-lines)
logistics     := round2 SUM(delivery-cost   over ALL lines)
penalties     := round2 SUM(penalty         over ALL lines)
additional    := round2 SUM(additional-payment over ALL lines)
acceptance    := round2 SUM(acceptance      over ALL lines)
deduction     := round2 SUM(deduction       over ALL lines)
```

Each field is a plain SUM over the operation-filtered (or all) lines for
one article. Missing fields are treated as 0 via `(or (:field row) 0)`.

**Economic justification.** Finance is the raw ledger view — no derived
calculations, just sums of what the marketplace reported. Splitting by
`sales-lines` vs `return-lines` ensures that sale-only fields (revenue,
commission, acquiring on sales) are not polluted by the zero-valued
return rows WB sends; `wb-reward` and `logistics` span all operations
because WB issues reward/delivery records on return rows too.

**Inputs.** `finance` table via `db-finance`; mapped with kebab-case keys
by the JDBC layer. Key names follow
[`data-dictionary.md#finance`](data-dictionary.md#finance).

**Edge cases.**

- A row with an unknown `operation` value is silently excluded from both
  `sales-lines` and `return-lines`; its `logistics`, `penalties`,
  `additional`, `acceptance`, `deduction`, `wb-reward`, `acquiring` still
  accumulate into the ALL-lines sums.
- `nil` field values replaced by 0 — `(or (:field row) 0)`.
- An article with only return lines will have `revenue = 0`,
  `wb-commission = 0`, `sales-pay = 0`.

**Verification.** `finance_canon_test.clj` › `group-1-pass-throughs`:
given 5 sale rows with known amounts, asserts each field equals the
expected SUM.

---

### Finance.2 — `:spp-amount` derivative

**Formula**

```
spp-amount := round2 (sales-pay − revenue)
           := SUM(for-pay over sales-lines) − SUM(retail-amount over sales-lines)
```

**Economic justification.** On WB, `for-pay` on a sale line reflects the
amount the seller actually receives from WB after WB's SPP (promotional
discount) compensation is factored in. When WB absorbs the SPP discount,
`for-pay < retail-amount` and `spp-amount` is negative (the seller
effectively pays part of the discount to WB). When WB subsidizes the
discount, `for-pay > retail-amount` and `spp-amount` is positive.
The field surfaces the net SPP direction without requiring the caller to
subtract revenue from for-pay manually.

**Inputs.** `sales-pay` and `revenue` from Finance.1 (same pass-through
lines).

**Edge cases.**

- For Ozon and YM, `for-pay` semantics differ (see UE.2 per-MP
  coverage); the arithmetic is still valid but `spp-amount` will not
  equal WB's specific SPP notion — callers should treat it as a
  `for-pay − revenue` residual.
- If the article has no sale lines: `spp-amount = 0.0 − 0.0 = 0.0`.

**Verification.** `finance_canon_test.clj` › `group-2-spp-amount`:
for the fixture article A (5 sales, for-pay=80, retail=100 each):
`spp-amount = 5×80 − 5×100 = −100`.

---

### Finance.3 — `:storage` coalescence

**Formula**

```
if storage-by-article is provided:
    storage := round2 (get storage-by-article article 0.0)
else:
    storage := round2 SUM(storage-fee over ALL lines)
```

**Economic justification.** WB reports storage costs in a separate
`paid_storage` endpoint that gives a per-article number for the whole
week; the per-row `storage_fee` is always 0 on that path. Ozon/YM may
include storage in per-row fields. The coalescence lets the caller inject
the precise storage value when available, falling back to row-level
accumulation otherwise. Semantics are identical to UE.2 storage path.

**Inputs.** `storage-by-article` keyword arg (map keyed by article),
passed from `finance/by-article` to `article-row`. Built by
`db/storage-by-article` in the report layer.

**Edge cases.**

- When `storage-by-article` is nil/not-provided, the `if` branch falls
  through to row-level SUM — which may be 0.0 if the MP delivers storage
  separately (WB without paid_storage passed in).
- An article absent from the map returns `0.0` via `(get m a 0.0)`.

**Verification.** `finance_canon_test.clj` › `group-3-storage`:
without `storage-by-article`, asserts row-level SUM; with a synthetic
`storage-by-article` map, asserts the overridden value is used.

---

### Finance.4 — `:for-pay` net (sales − returns)

**Formula**

```
for-pay := round2 (
              SUM(for-pay over sales-lines)
            − |SUM(for-pay over return-lines)|
           )
```

The `Math/abs` strips the sign of the returns sum. On WB, return `for-pay`
values are typically 0.0 (seller pays logistics only, not charged via
`for_pay`). On Ozon the return `for-pay` arrives as a negative number.
Taking the absolute value means the subtraction is always
`sales_total − |returns_total|`, never a double-subtraction from an
already-negative value.

**Economic justification.** The seller's net received amount for an
article in the period is `what I got from sales` minus `what was clawed
back from returns`. Both `sales_pay` (exposed as Finance.1 `:sales-pay`)
and `returns_pay` (`:returns-pay`) are preserved separately for
reconciliation; `:for-pay` is the single net number the Finance report
displays. This mirrors UE.2's `for-pay` definition exactly.

**Inputs.** `for-pay` field from `sales-lines` and `return-lines`
(Finance.1 partition).

**Edge cases.**

- Article with only return lines: `sales SUM = 0`, so `for-pay =
  −|returns SUM|` which evaluates to ≤ 0 (correct — net refund).
- Article with only sale lines: `returns SUM = 0`; `|0| = 0`;
  `for-pay = sales SUM`.
- NaN/nil: `(or (:for-pay row) 0)` prevents NaN propagation.

**Verification.** `finance_canon_test.clj` › `group-4-for-pay`:
article A: `5×80 − |0| = 400`; article B: `3×42 − |0| = 126`;
article C: `0 − |0| = 0` (return rows have `for-pay=0` in the WB
fixture).

---

### Finance.5 — `:cost-price` and `:total-cost`

**Formula**

```
cost-price := round2 line-cost(first(sales-lines))
           where line-cost(line) = cost-price/get-price(article, barcode) OR 0.0

total-cost := round2 SUM( line-cost(line) × max(1, quantity)
                          for line in sales-lines )
```

**Economic justification.** `cost-price` is the per-unit procurement cost
of this article in the period, sourced from the `cost_prices` table via
`cost-price/get-price`. Using the first sale line as the representative
is a deliberate simplification: most articles have a single SKU and a
stable cost price within a weekly report period. `total-cost` correctly
accounts for multi-unit lines via `max(1, quantity)` to prevent 0-quantity
lines from zeroing out cost.

**Inputs.**
[`data-dictionary.md#cost_prices`](data-dictionary.md#cost_prices) via
`cost-price/get-price(article, barcode)`.

**Edge cases.**

- No sale lines → `(first nil) = nil`; `line-cost(nil) = 0.0`;
  `cost-price = 0.0`, `total-cost = 0.0`.
- Article with no entry in `cost_prices`: `get-price` returns nil →
  `0.0`.
- `quantity = nil` in a row: `(or nil 1) = 1`; `max(1,1) = 1` —
  single-unit fallback.
- Mid-period cost change: only the first sale-line's cost is reported as
  `cost-price`; `total-cost` uses each line's own lookup so it remains
  accurate (each barcode may have a different entry).

**Verification.** `finance_canon_test.clj` › `group-5-cost-price`:
with no `cost_prices` rows, both fields are 0.0; this is the expected
behavior for the in-memory fixture (no DB).

---

### Finance.6 — `empty-article-row` fallback

**Formula**

```
when `articles` kwarg is supplied AND article has no finance lines:
    emit empty-article-row(article, storage-by-article)
    where all monetary fields = 0.0 / 0 except:
        storage = round2 (get storage-by-article article 0.0)
```

**Economic justification.** When the caller supplies an explicit article
list (e.g. to join the finance report against a product catalog), an
article that had no activity in the period should still appear in the
output with a zero row rather than silently disappearing. The storage
field is still populated from `storage-by-article` because WB charges
storage regardless of sales activity — a zero-sales week still incurs
storage cost.

**Inputs.** `articles` keyword arg (seq of article strings); the
`storage-by-article` map for storage coalescence.

**Edge cases.**

- When `articles` is not provided (nil / empty), `empty-article-row` is
  never called — the output only contains articles that appear in the
  finance data.
- An article present in both `articles` and the finance data uses the
  computed `article-row`, not the empty fallback.
- `cost-price = 0.0`, `total-cost = 0.0` — correct; no sale to derive
  cost from.

**Verification.** `finance_canon_test.clj` › `group-6-empty-article-row`:
passes `:articles ["A" "B" "C" "D"]` where D has no rows; asserts D
appears with `for-pay = 0.0` and all monetary fields zero.

---

### Finance.7 — `totals` period rollup

**Formula**

```
totals(finance-data) := let articles = by-article(finance-data)
  :total-revenue     := round2 SUM(:revenue    over articles)
  :total-wb-reward   := round2 SUM(:wb-reward  over articles)
  :total-acquiring   := round2 SUM(:acquiring  over articles)
  :total-spp         := round2 SUM(:spp-amount over articles)
  :total-logistics   := round2 SUM(:logistics  over articles)
  :total-penalties   := round2 SUM(:penalties  over articles)
  :total-storage     := round2 SUM(:storage    over articles)
  :total-acceptance  := round2 SUM(:acceptance over articles)
  :total-additional  := round2 SUM(:additional over articles)
  :total-deduction   := round2 SUM(:deduction  over articles)
  :total-for-pay     := round2 SUM(:for-pay    over articles)
  :total-sales-qty   := SUM(:sales-qty   over articles)   [integer]
  :total-returns-qty := SUM(:returns-qty over articles)   [integer]
  :articles-count    := count(articles)
```

Note: `totals` calls `by-article` internally without `storage-by-article`
or `articles` args. If the caller needs storage coalescence, they should
call `by-article` themselves and derive totals from that result.

**Economic justification.** The period summary displayed at the top of the
Finance report. Mirrors P&L.1 but keyed with `:total-*` prefix for
disambiguation. No `gross-profit` or `net-profit` — Finance is the
bookkeeping view; P&L is the profit view built on top of it.

**Inputs.** All Finance.1–Finance.5 per-article fields via `by-article`.

**Edge cases.**

- Empty input → `by-article` returns `[]`; all totals = 0.0, counts = 0.
- `:articles-count` counts the number of rows returned by `by-article`,
  which equals the number of distinct articles in the finance data (or
  the length of the `articles` arg when supplied — but `totals` does not
  pass `articles`, so it always reflects distinct articles in data).

**Verification.** `finance_canon_test.clj` › `group-7-totals`:
asserts `total-for-pay = 526.0` (matches fixture), `articles-count = 3`.

---

### Finance.8 — `by-report-id` weekly split (WB only)

**Formula**

```
by-report-id(finance-data) :=
  group finance-data by (row.report-id OR row.report_id)
  for each [id lines]:
    :report-id := id
    :date-from := first(lines).date-from OR first(lines).date_from
    :date-to   := first(lines).date-to   OR first(lines).date_to
    :lines     := count(lines)
    :for-pay   := round2 SUM(row.for-pay over lines)
  sort ascending by :date-from
```

**Economic justification.** WB groups settlement rows into weekly
`report_id` buckets. The `by-report-id` view exposes the raw `for-pay`
total per report week, useful for reconciling WB's weekly settlement
statements. For Ozon and YM, `report_id` is nil for all rows, so
`by-report-id` produces a single catch-all row with `report-id = nil`.

**Inputs.** Raw finance rows; `report-id` / `report_id` dual-key lookup
handles both kebab and snake-case from the DB layer.

**Edge cases.**

- YM / Ozon: all rows group under `nil` → single-element output.
- The `:for-pay` here is row-level `for_pay` summed directly — not
  net-of-returns as in Finance.4. For WB this is the same number WB
  reports on the settlement (net is already in the raw rows); for
  Ozon/YM, raw `for_pay` sign semantics differ (see UE.2).
- `date-from` / `date-to` are taken from `(first lines)` — the lines
  within a group may span dates; the first line's dates serve as a proxy
  for the report week start/end.

**Verification.** `finance_canon_test.clj` › `group-8-by-report-id`:
fixture has all rows with `date-from = "2026-03-01"`, so a single
report-week group; asserts `count = 1` and `for-pay` equals raw SUM
of all `:for-pay` field values across rows.

---

### Finance.9 — Marketplace coverage matrix

| Metric / field | WB | Ozon | YM |
|---|---|---|---|
| `:revenue` | ✅ `retail_amount` | ✅ `retail_amount` | ✅ `retail_amount` |
| `:wb-reward` | ✅ WB-specific commission field | ❌ always 0 | ❌ always 0 |
| `:wb-commission` | ✅ sale-commission breakout | ❌ always 0 | ❌ always 0 |
| `:spp-amount` | ✅ meaningful (SPP mechanism) | ⚠️ residual only | ⚠️ residual only |
| `:acquiring` | ✅ acquiring_fee field | ✅ acquiring_fee field | ❌ rolled into for_pay |
| `:sales-pay` / `:returns-pay` | ✅ | ✅ (bonus+compensation included) | ✅ (buyer − commissions + subsidies) |
| `:for-pay` net | ✅ | ✅ | ✅ |
| `:logistics` | ✅ delivery_cost | ✅ delivery_cost | ✅ delivery_cost |
| `:storage` | ✅ paid_storage coalescence | ✅ row-level storage_fee | ✅ row-level storage_fee |
| `:penalties` | ✅ | ✅ | ✅ |
| `:acceptance` | ✅ FBO acceptance fee | ✅ | ❌ always 0 |
| `:deduction` | ✅ | ❌ always 0 | ❌ always 0 |
| `:additional` | ✅ seller credits | ✅ | ✅ |
| `:cost-price` / `:total-cost` | ✅ | ✅ | ✅ |
| `by-report-id` week split | ✅ meaningful | ⚠️ single nil group | ⚠️ single nil group |

---

### Finance.10 — Known gaps

Inherits all UE.11 gaps (same ingest pipeline). Finance-specific additions:

1. **`:cost-price` uses the first sale-line's per-unit cost as the
   "representative".** Silently wrong when an article has multiple SKUs
   (barcodes) with different cost-prices within the same period, or when
   the cost changed mid-period. `total-cost` is unaffected because it
   does per-line lookup, but `cost-price` displayed in the UI will show
   only one SKU's value.
2. **`by-report-id` assumes one `report_id` per week.** If a future MP
   re-uses IDs across dates or issues multiple settlement IDs in one
   week, the date-from/date-to proxy from `(first lines)` will be
   inaccurate and the grouping will merge distinct weeks.
3. **Return-bearing-only articles (e.g. fixture article C) show
   `cost-price = 0.0`** because no sale lines exist to derive cost from.
   `total-cost = 0.0` as well — no units were sold in the period, so
   the cost assignment is not meaningful anyway. Callers should not
   interpret `cost-price = 0` as "free" — they should check
   `sales-qty = 0`.
4. **`totals` does not accept `storage-by-article`.** It calls
   `by-article` with no kwargs, so if the report layer wants
   storage-coalesced totals, it must build them from the `by-article`
   result directly (as `pnl/calculate` does).

---

### Finance.11 — Verification summary

- Every Finance.N group has a corresponding `deftest` in
  `test/analitica/domain/finance_canon_test.clj`.
- `group-reconcile-with-pnl-and-ue`: asserts that on the shared fixture,
  `SUM(:for-pay)` across `by-article` Finance rows equals `:for-pay`
  from `pnl/calculate` within 0.1 RUB, and equals
  `(reduce + (map :for-pay (ue/calculate fx)))` within 0.1 RUB.
- Regression coverage: `clojure -M:test` green on the whole suite.

---
- Формулы в коде: [src/analitica/domain/finance.clj](../src/analitica/domain/finance.clj), [src/analitica/domain/pnl.clj](../src/analitica/domain/pnl.clj), [src/analitica/domain/unit_economics.clj](../src/analitica/domain/unit_economics.clj).
