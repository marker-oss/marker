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
| 2 | P&L              | `analitica.domain.pnl`             | [§P&L](#pl)                          |
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
- Формулы в коде: [src/analitica/domain/finance.clj](../src/analitica/domain/finance.clj), [src/analitica/domain/pnl.clj](../src/analitica/domain/pnl.clj), [src/analitica/domain/unit_economics.clj](../src/analitica/domain/unit_economics.clj).
