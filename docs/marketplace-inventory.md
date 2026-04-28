# Marketplace API Inventory

> **Phase A артефакт** методологии canonical-first. Полный реестр endpoint'ов
> WB / Ozon / YM с классификацией по канон-релевантным классам и отметкой
> "используем ли мы это сейчас". Цель — сделать видимым **white space**:
> какие отчёты МП публикуют, но мы не подключали, и закрывают ли они
> существующие gaps в L1/L2 (см. [`canonical-formulas.md` §7](canonical-formulas.md#7-known-gaps-чтобы-не-всплывали-повторно)).
>
> **Источники specs**:
> - WB: [eslazarev/wildberries-sdk](https://github.com/eslazarev/wildberries-sdk) `specs/01-...14-*.yaml` (auto-mirror `dev.wildberries.ru/api/swagger/yaml/ru/`)
> - Ozon: `resources/schemas/ozon/swagger.json` (локальный, OpenAPI v2.1)
> - YM: [yandex-market/yandex-market-partner-api](https://github.com/yandex-market/yandex-market-partner-api) `openapi/paths/`
>
> Полные TSV-выгрузки: `docs/inventory/{wb,ozon,ym}-endpoints.tsv`.
> Дата фиксации: 2026-04-28.

## Executive summary

| MP | Endpoints в spec | Использовано | % покрытия | Секции/тегов |
|---|---:|---:|---:|---:|
| **WB** | 306 | ~13 | 4.2% | 14 |
| **Ozon** | 425 | ~11 | 2.6% | 68 |
| **YM** | 158 | ~5 | 3.2% | ~30 |
| **Total** | **889** | **~29** | **~3.3%** | — |

Мы видим API через очень узкую щёлку. Это объяснимо для MVP, но при
расширении канона ориентироваться на полный реестр обязательно — иначе
будем «изобретать» concepts, для которых у MP уже есть готовый отчёт.

---

## Классификация (canon-relevant классы)

| Класс | Назначение | Что должно из этого попасть в L1 |
|---|---|---|
| **settlement** | расчётные/денежные отчёты с агрегациями за период | `finance` (per-row), `cash_flow_periods` (period-level) |
| **event-stream** | per-event факты (заказы, продажи, возвраты) | `sales` |
| **catalog** | товарный справочник, категории, бренды | dim (пока денормализовано в `finance.subject/brand`) |
| **inventory** | остатки, поступления, пути в пути | `stocks` |
| **analytics** | агрегаты, готовые ad-hoc отчёты | feeds reports напрямую |
| **advertising** | рекламные кампании, статистика | `ad_stats` |
| **warehousing** | склады, тарифы, размещение | `paid_storage` + tariffs (нет таблицы) |
| **reference** | регионы, валюты, единицы измерения | dim |
| **operations** | отгрузки, упаковка, маркировка | вне scope canon |
| **other** | чаты, отзывы, вопросы | вне scope canon |

---

## WB (Wildberries) — 306 endpoints, 14 секций

| # | Секция | Endpoints | Используем | Класс (преимущ.) |
|---|---|---:|---:|---|
| 01 | general | 9 | 0 | reference |
| 02 | products | 49 | 0 | catalog |
| 03 | orders-fbs | 34 | 0 | event-stream / operations |
| 04 | orders-dbw | 16 | 0 | event-stream / operations |
| 05 | orders-dbs | 20 | 0 | event-stream / operations |
| 06 | in-store-pickup | 28 | 0 | operations |
| 07 | orders-fbw | 7 | 0 | warehousing |
| 08 | promotion | 37 | **1** | advertising |
| 09 | communications | 25 | 0 | other |
| 10 | tariffs | 5 | 0 | reference / warehousing |
| 11 | analytics | 17 | 1 | analytics |
| 12 | reports | 25 | **6** | settlement / inventory |
| 13 | finances | 12 | 1 | settlement |
| 14 | wbd | 22 | 0 | other (WB Digital) |

**Используемые endpoints:**

| Endpoint | Секция | Назначение | Питает L1 |
|---|---|---|---|
| `/api/v1/supplier/sales` | 12-reports | продажи (event) | `sales` |
| `/api/v1/supplier/orders` | 12-reports | заказы (event) | `sales` |
| `/api/v1/supplier/stocks` | 12-reports | остатки | `stocks` |
| `/api/v5/supplier/reportDetailByPeriod` | 13-finances | settlement (per-row) | `finance` |
| `/api/v1/paid_storage` (+ tasks) | 12-reports | платное хранение | `paid_storage` |
| `/api/v1/analytics/region-sale` | 12-reports | регионы | `region_sales` |
| `/api/v1/warehouse_remains` (+ tasks) | 12-reports | детальные остатки | `stocks` (косвенно) |
| `/api/v2/list/goods/filter` | (другая) | каталог goods | dim |
| `/api/v2/nm-report/detail` | 11-analytics | nm-аналитика | UE |
| `/adv/v3/fullstats` | 08-promotion | рекламная статистика | `ad_stats` |
| `/adv/v1/promotion/count` | 08-promotion | список кампаний | вспомогательное |

**🔴 Notable неиспользуемое (закрывает существующие gaps или открывает новые):**

| Endpoint | Что даёт | Закрывает gap |
|---|---|---|
| `/api/finance/v1/sales-reports/list` + `detailed` | **новый** finance API (наследник v5) | candidate замена `/api/v5/supplier/reportDetailByPeriod` |
| `/api/finance/v1/acquiring/list` + `detailed` | отдельный отчёт по эквайрингу | детализация `acquiring_fee` (сейчас уже внутри `for_pay`) |
| `/api/v1/account/balance` | баланс продавца | reconciliation pair (B-006) — sanity check |
| `/api/analytics/v1/measurement-penalties` | удержания за занижение габаритов | детализация `penalty` |
| `/api/analytics/v1/deductions` | подмены и неверные вложения | детализация `deduction` |
| `/api/v1/analytics/antifraud-details` | **самовыкупы** (антифрод) | новый concept, нет L1 поля |
| `/api/v1/analytics/goods-labeling` | удержания за маркировку | детализация `penalty` |
| `/api/v1/analytics/goods-return` | возвраты и перемещения товаров | альтернативный источник для `sales` (returns) |
| `/api/v1/acceptance_report` | операции при приёмке | детализация `acceptance` |
| `/api/v1/documents/list` + download | закрывающие документы | reconciliation pair (бухучёт) |
| `/api/v1/analytics/excise-report` | акцизные товары | вне scope MVP |
| `/api/analytics/v1/stocks-report/wb-warehouses` | **история** остатков | закрывает gap §stocks "no historical time series" |

---

## Ozon — 425 endpoints, 68 тегов

| Тег | Endpoints | Используем | Класс |
|---|---:|---:|---|
| FinanceAPI | 10 | **2** | settlement |
| ReportAPI | 11 | **2** | settlement / reports |
| AnalyticsAPI | 6 | **1** | analytics / inventory |
| Premium | 10 | **1** | analytics |
| FBO | 14 | **1** | event-stream |
| FBS | 21 | **1** | event-stream |
| FboSupplyRequest | 30 | 0 | warehousing |
| DeliveryFBS | 26 | 0 | operations |
| FBSWarehouseSetup | 17 | 0 | warehousing |
| WarehouseAPI | 10 | 0 | warehousing |
| Prices&StocksAPI | 11 | 0 | catalog / inventory |
| ProductAPI | 20 | **2** | catalog |
| ReturnsAPI / ReturnAPI / RFBSReturnsAPI | 4+8+8 | 0 | event-stream |
| CategoryAPI | 4 | 0 | catalog |
| CertificationAPI | 15 | 0 | reference |
| PricingStrategyAPI | 12 | 0 | catalog |
| Promos | 8 | 0 | advertising |
| BetaMethod / Quants / Digital / SellerActions | 35 | 0 | beta / other |
| (остальные ~50 тегов) | ~150 | 0 | разное |

**Используемые endpoints:**

| Endpoint | Тег | Назначение | Питает L1 |
|---|---|---|---|
| `/v2/finance/realization` | FinanceAPI | period realization | `finance` |
| `/v3/finance/transaction/list` | FinanceAPI | транзакции (legacy) | `finance` (fallback) |
| `/v1/finance/cash-flow-statement/list` | ReportAPI | period cash-flow | `cash_flow_periods` |
| `/v1/report/info/{id}` + `/v1/report/postings/create` | ReportAPI | async reports | postings |
| `/v3/posting/fbo/list` + `/v3/posting/fbs/list` | FBO/FBS | event-stream | `sales` |
| `/v3/product/info/list` + `/v3/product/list` | ProductAPI | каталог | dim |
| `/v2/analytics/stock_on_warehouses` | AnalyticsAPI | остатки | `stocks` |
| `/v1/analytics/data` | Premium | аналитические агрегаты | reports |

**🔴 Notable неиспользуемое:**

| Endpoint | Что даёт | Закрывает gap |
|---|---|---|
| `/v1/finance/realization/posting` | **per-order** realization (детализация) | **закрывает Ozon UE gap §6.2** — per-article fulfillment |
| `/v1/finance/products/buyout` | отчёт по выкупленным товарам | альтернативный источник `buyout` |
| `/v1/finance/compensation` + `/v1/finance/decompensation` | компенсации/декомпенсации | детализация `additional_payment` |
| `/v1/finance/mutual-settlement` | взаиморасчёты | reconciliation pair |
| `/v1/report/placement/by-products/create` | **placement cost by products** | возможно закрывает per-article storage у Ozon |
| `/v1/report/placement/by-supplies/create` | placement cost by supplies | same |
| `/v1/report/discounted/create` | уценённые товары | новый concept |
| `/v1/finance/realization/by-day` (Premium) | per-day realization | trends детализация |
| `/v1/analytics/turnover/stocks` | оборачиваемость | новый concept для UE |
| `/v1/analytics/average-delivery-time` | время доставки | reports / SLA |
| `/v1/returns/list` (FBO+FBS) | информация о возвратах | детализация returns |
| `/v2/report/returns/create` | возвраты как отчёт | альтернативный источник |
| `/v1/finance/document-b2b-sales` | продажи юрлицам | новый segment |

---

## YM (Yandex Market) — 158 endpoints

| Тег (топ) | Endpoints | Используем |
|---|---:|---:|
| reports | 25 | 0 (!) |
| orders | 16 | **1** |
| shipments | 12 | 0 |
| returns | 9 | 0 |
| chats | 7 | 0 |
| business-offer-mappings | 6 | 0 |
| prices | 5 | **1** |
| order-delivery | 5 | 0 |
| outlets | 5 | 0 |
| goods-feedback | 5 | 0 |
| (другие 20+) | ~63 | ~3 |

**Используемые endpoints:**

| Endpoint | Назначение | Питает L1 |
|---|---|---|
| `/campaigns/{id}/orders` | заказы | `sales` |
| `/campaigns/{id}/stats/orders` | per-order stats с commissions | `finance`, `sales` |
| `/businesses/{id}/stats/skus` | per-sku stats | UE |
| `/campaigns/{id}/offers/stocks` | остатки | `stocks` |
| `/campaigns/{id}/offer-prices` | цены | dim |

**🔴 Notable неиспользуемое — особенно остро для YM (тег `reports` пустой!):**

| Endpoint | Что даёт | Закрывает gap |
|---|---|---|
| `/v2/reports/united-marketplace-services/generate` | **отчёт по стоимости услуг** | **🟢 закрывает YM storage/acceptance gap §6.3** |
| `/v2/reports/goods-realization/generate` | per-product realization | альтернатива для `finance` (вместо `stats/orders`) |
| `/v2/reports/united-netting/generate` | отчёт по платежам | reconciliation pair с `stats/orders.netting` |
| `/v2/reports/united-orders/generate` | отчёт по заказам | альтернатива `sales` |
| `/v2/reports/united-returns/generate` | возвраты и невыкупы | детализация returns |
| `/v2/reports/sales-geography/generate` | география продаж | **закрывает gap §region_sales для YM** |
| `/v2/reports/goods-turnover/generate` | оборачиваемость | новый concept для UE |
| `/v2/reports/goods-movement/generate` | движение товаров | новый concept |
| `/v2/reports/key-indicators/generate` | KPI | dashboard data |
| `/v2/reports/closure-documents/generate` + `detalization` | закрывающие документы | reconciliation pair (бухучёт) |
| `/v2/reports/goods-prices/generate` | цены | dim history |
| `/v2/reports/stocks-on-warehouses/generate` | остатки на складах | альтернатива `offers/stocks` |

---

## White-space анализ — что MP даёт vs что мы используем

### По канон-классам:

| Класс | WB white space | Ozon white space | YM white space |
|---|---|---|---|
| **settlement** | новый finance v1 API, acquiring | per-order realization, placement, compensation | **весь reports/* — 25 endpoints** |
| **event-stream** | analytics/goods-return | Returns API (3 теги), V2 returns report | reports/united-returns |
| **inventory** | warehouse_remains история | turnover, average-delivery-time | reports/stocks-on-warehouses, turnover |
| **analytics** | антифрод, deductions, brand-share | Premium analytics, search-queries | KPI, sales-geography |
| **catalog** | products (49 endpoints!) | Prices&Stocks, PricingStrategy | offer-mappings (6) |
| **warehousing** | tariffs (5), acceptance_report | FBSWarehouseSetup, WarehouseAPI | warehouses |
| **advertising** | promotion (37, мы используем 1) | Promos (8) | bids, promos |

### Прямое отображение на gaps в [`canonical-formulas.md` §7](canonical-formulas.md#7-known-gaps-чтобы-не-всплывали-повторно):

| Gap | Endpoint, который может закрыть | Что подтвердить |
|---|---|---|
| **B-002** WB account-level потери ~0.3% | `/api/finance/v1/sales-reports/detailed` (новый API) | даёт ли он account-level отдельной строкой |
| **B-003** ad-spend по мульти-артикульным кампаниям WB | — | API не помогает; нужна формула weighting |
| **WB operation не нормализован** | — | проблема transform, не API |
| **Ozon UE без cash-flow компенсации** (§6.2) | `/v1/finance/realization/posting` или `/v1/report/placement/by-products` | даёт ли per-article fulfillment cost |
| **YM нет storage/acceptance/penalty** (§6.3) | `/v2/reports/united-marketplace-services/generate` | прочитать структуру отчёта — содержит ли storage |
| **Ozon `additional_payment` отсутствует в finance** (§6.4) | `/v1/finance/compensation` + `/v1/finance/decompensation` | да, по названию подходит |
| **`region_sales` только WB** | `/v2/reports/sales-geography/generate` (YM) | YM закрывается; Ozon — пока нет аналога |
| **`stocks` нет истории** | `/api/analytics/v1/stocks-report/wb-warehouses` (WB) + `/v1/analytics/turnover/stocks` (Ozon) | для WB и Ozon есть; YM — `goods-turnover` |

---

## Что делать с этим (вход в Phase B)

Phase B — Concept Crosswalk — должна:

1. **Прочитать структуру** топ-приоритетных неиспользуемых endpoint'ов:
   - YM `united-marketplace-services` (закрывает наибольший gap)
   - Ozon `realization/posting` (закрывает Ozon UE)
   - WB `finance/v1/sales-reports/detailed` (наследник нашего основного источника)
2. **Зафиксировать concept→endpoint mapping** в обратной матрице
   (для каждого бизнес-концепта — primary + fallback в каждом MP).
3. **Обозначить decision-records**: «считаем ли мы acquiring отдельным
   concept'ом, или оставляем внутри `for_pay`?», «нужен ли нам
   antifraud-details?» и т.п.

Phase A артефакты остаются точкой входа: при добавлении нового
endpoint'а — обновить TSV и эту таблицу.

---

## Полные данные

- `docs/inventory/wb-endpoints.tsv` — 306 строк: `section\tpath\tmethod\ttag\tsummary`
- `docs/inventory/ozon-endpoints.tsv` — 425 строк: `path\tmethod\ttag\tsummary`
- `docs/inventory/ym-endpoints.tsv` — 158 строк: `path\tmethod\ttag\tsummary`
- `docs/inventory/wb-specs/*.yaml` — 14 локальных копий WB OpenAPI
- `docs/inventory/ym-specs/openapi.yaml` — главный YM spec (paths split)
- `resources/schemas/ozon/swagger.json` — полный Ozon spec
