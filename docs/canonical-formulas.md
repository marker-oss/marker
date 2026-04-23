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
- Формулы в коде: [src/analitica/domain/finance.clj](../src/analitica/domain/finance.clj), [src/analitica/domain/pnl.clj](../src/analitica/domain/pnl.clj), [src/analitica/domain/unit_economics.clj](../src/analitica/domain/unit_economics.clj).
