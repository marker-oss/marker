# Concept Crosswalk — Marketplace API → L1 Canon

> **Phase B артефакт** методологии canonical-first. Для каждого
> бизнес-концепта показано, **какой raw-поле какого endpoint'а** его
> закрывает у каждого MP. Цель — сделать L1-контракт **выводимым** из
> реальной семантики API, а не «придуманным», и обнаружить:
>
> 1. **Конфликты семантики** — когда одно и то же название означает
>    разные вещи у разных MP.
> 2. **Скрытые источники** — когда field/concept уже есть в API, но
>    мы не используем (и наш L1 показывает gap несправедливо).
> 3. **Подлинные gaps** — когда concept не выражен ни одним endpoint'ом.
>
> **Источники данных**:
> - `docs/inventory/{wb,ozon,ym}-schemas.tsv` — извлечённые response-схемы
>   ключевых endpoint'ов (~865 полей суммарно).
> - YAML/JSON specs в `docs/inventory/{wb,ym}-specs/`,
>   `resources/schemas/ozon/swagger.json`.
>
> Дата: 2026-04-28. Связанные документы:
> - [`marketplace-inventory.md`](marketplace-inventory.md) — Phase A полный реестр
> - [`canonical-formulas.md`](canonical-formulas.md) — L2 формулы (целевой потребитель)
> - [`data-dictionary.md`](data-dictionary.md) — L1 контракт (что нужно скорректировать)

## Легенда

- ✅ — concept покрыт primary endpoint'ом
- 🟡 — частично (только в специфичных случаях)
- ❌ — отсутствует в API (подлинный gap)
- 🔵 — есть, но мы не используем
- ⚠️ — семантика расходится с canonical (требует transform)

В колонках указан **path → field** (или `path → field.subfield`).

---

## 1. Identity

### 1.1 article (внешний ID товара)

| MP | Endpoint | Field | Notes |
|---|---|---|---|
| WB | `/api/v5/supplier/reportDetailByPeriod` | `sa_name` | Артикул продавца |
| WB | `/api/finance/v1/sales-reports/detailed` | `vendorCode` 🔵 | Тот же концепт, новый API |
| WB | `/api/v1/supplier/sales` | `supplierArticle` | Везде одно и то же |
| Ozon | `/v2/finance/realization` | `result.rows[].item.offer_id` | Артикул селлера |
| Ozon | `/v3/posting/fbo/list` | `products[*].offer_id` | events |
| YM | `/campaigns/{}/stats/orders` | `result.orders[].items[].shopSku` | shopSku ≡ article |

**Решение для L1**: ✅ единое поле `article: string` без расхождений.

### 1.2 internal MP id (sku / nm_id)

| MP | Endpoint | Field | Тип | Канон-имя |
|---|---|---|---|---|
| WB | report-detail | `nm_id` | integer | `nm_id` |
| Ozon | finance/realization | `result.rows[].item.sku` | integer | (сейчас в L1 `nm_id` — **misleading**) |
| YM | stats/orders | `result.orders[].items[].marketSku` | object → id | (сейчас не сохраняется) |

⚠️ **L1 issue**: поле `nm-id` хранит WB nmId (integer), а Ozon SKU тоже пишется в `nm-id`. У YM — marketSku хранится отдельно/теряется. Поле семантически перегружено.

**RFC-1**: переименовать `nm-id` → `mp-internal-id` (cross-MP), сохранять marketSku для YM.

### 1.3 barcode

| MP | Endpoint | Field | Notes |
|---|---|---|---|
| WB | report-detail | `barcode` | string |
| WB | finance v1 detailed | `sku` 🔵 | **семантический конфликт**: WB-новый API называет barcode `sku` |
| Ozon | finance/realization | `result.rows[].item.barcode` | string |
| YM | stats/orders | (не возвращается) | ❌ |

⚠️ **Конфликт**: в новом WB API `sku` означает barcode, в Ozon `sku` означает internal id. **Не путать**.

### 1.4 order/transaction id

| MP | Endpoint | Field | Назначение |
|---|---|---|---|
| WB | report-detail | `srid` | Уникальный ID заказа (links к FBS-сборке) |
| WB | report-detail | `order_uid` 🔵 | ID транзакции (один заказ = одна корзина) |
| WB | supplier/sales | `gNumber` | ID корзины покупателя |
| WB | supplier/sales | `saleID` | ID продажи (`S***`) или возврата (`R***`) |
| Ozon | transaction/list | `result.operations[].posting.posting_number` | Номер отправления |
| Ozon | finance/realization/posting | `rows[].order.posting_number` 🔵 | linkpoint per-order |
| YM | stats/orders | `result.orders[].id` | YM order id |

**Решение для L1**: ✅ `sale-id`/`rrd-id` уже есть. Можно добавить `cart-id` (gNumber/order_uid/posting_number) как **группирующий** ключ для multi-item orders.

**RFC-2**: добавить ли `cart-id` как опциональное поле для группировки строк одного заказа? Полезно для UE «сколько артикулов в корзине».

---

## 2. Operation Classification

### 2.1 operation_kind (sale/return/service/adjustment)

Это самое расходящееся поле. У каждого MP свой источник «о чём строка».

| MP | Endpoint | Raw field | Распознавание sale/return |
|---|---|---|---|
| WB | report-detail | `supplier_oper_name` | Строка вида "Продажа" / "Возврат" / "Логистика" / "Хранение" / "Компенсация ущерба" / ... |
| WB | report-detail | `doc_type_name` | Тип документа (доп. категоризация) |
| WB | finance v1 detailed | `sellerOperName` 🔵 | То же, новое имя |
| WB | supplier/sales | `saleID` префикс `S` (sale) / `R` (return) | event-stream признак |
| Ozon | finance/realization | `delivery_commission.quantity` > 0 → sale, `return_commission.quantity` > 0 → return | derived |
| Ozon | transaction/list | `operation_type` (50+ значений) + `services[].name` (100+ значений) | enum classifier |
| YM | stats/orders | `result.orders[].status` enum | DELIVERED → sale, CANCELLED → cancel, returns через separate flow |
| YM | stats/orders | `result.orders[].commissions[].type` | для service-строк |

⚠️ **WB operation enum дрейф**: сейчас в L1 `operation` хранится raw-русская строка (см. data-dictionary §finance). По L2 §4.3 это нарушение контракта.

⚠️ **Ozon `services[].name`** — 100+ значений, классификация в transform.clj через map. Нет в L1 как enum.

⚠️ **YM**: не различает «return» и «cancel» в API явно — нужен domain-уровневый mapping.

**RFC-3** (P0): принять **двухуровневую** классификацию по ISO 20022 / OFX образцу:
- `operation_kind` (canonical, enum) — sale / return / service / adjustment
- `operation_subtype` (raw) — оригинальное значение из MP (логистика / хранение / MarketplaceServiceItemReturnAfterDelivery / RETURNED_ORDERS_STORAGE / ...)

L2 формулы используют `operation_kind`, audit/UI используют `operation_subtype`.

### 2.2 buyout vs return

Buyout = выкуп (фактическая продажа после физической доставки), return = возврат покупателем.

| MP | Endpoint | Признак |
|---|---|---|
| WB | report-detail | `report_type=2` — отчёт по выкупам, `=1` — основной |
| WB | analytics/buyouts (отдельный) | специализированный отчёт |
| Ozon | `/v1/finance/products/buyout` 🔵 | dedicated buyout endpoint, не используем |
| Ozon | finance/realization | разница `delivery_commission.quantity` − `return_commission.quantity` | derived |
| YM | stats/orders | status = `DELIVERED` (no return) | event-level |

✅ Concept покрыт всеми, но **источники разной природы**: WB — отчётный flag, Ozon — derived, YM — event status.

---

## 3. Money — Gross (что покупатель заплатил)

### 3.1 retail_price (цена за единицу)

| MP | Endpoint | Field | Семантика |
|---|---|---|---|
| WB | report-detail | `retail_price` | До скидок |
| WB | report-detail | `retail_price_withdisc_rub` 🔵 | После скидки продавца |
| Ozon | finance/realization | `delivery_commission.price_per_instance` | До или после? см. RFC-4 |
| Ozon | finance/realization | `seller_price_per_instance` (только в `realization/posting`) 🔵 | После скидки селлера |
| YM | stats/orders | `result.orders[].items[].prices[]` где `type=BUYER` → `costPerItem` | После всех скидок (включая купоны) |

⚠️ **RFC-4**: какую цену сохранять в L1 `retail_price`? До/после скидок? Сейчас неконсистентно.
- WB пишет `retail_price` (до) и `retail_price_withdisc_rub` (после) — оба доступны.
- Ozon — только after-discount-seller (есть в `posting` версии).
- YM — только after-everything (BUYER price).

**Предложение**: сохранять **before-all-discounts** (gross list price) в `retail_price`, и **buyer-paid** в новом поле `buyer_price`. Текущее L1 `price-with-disc` = промежуточный (after-seller, before-MP). Все три имеют смысл.

### 3.2 retail_amount (gross total)

| MP | Endpoint | Field | Расчёт |
|---|---|---|---|
| WB | report-detail | `retail_amount` | прямое |
| Ozon | finance/realization | вычисляемое: `quantity × seller_price_per_instance` (только в posting) | derived |
| Ozon | (period) | not provided per-row, нужно складывать | ⚠️ |
| YM | stats/orders | `prices[type=BUYER].total` | прямое |

✅ Concept присутствует, формула стабильна.

### 3.3 buyer-paid total (что фактически списано с покупателя)

| MP | Endpoint | Field |
|---|---|---|
| WB | supplier/sales | `finishedPrice` ✅ | **«Фактическая цена с учётом всех скидок (к взиманию с покупателя)»** |
| WB | report-detail | (нет прямого поля; вычисляется через retail_amount × (1 − total_discount_pct)) | ⚠️ derived |
| Ozon | (нет прямого поля per-row) | ❌ |
| YM | stats/orders | `prices[type=BUYER].total` ✅ | прямое |

⚠️ **L1 gap**: концепт «buyer-paid» (что покупатель реально заплатил после ВСЕХ скидок включая СПП/cashback/купоны) есть у WB (`finishedPrice`) и YM, но не выделен в L1. Это часть памяти про YM `for_pay` semantics.

---

## 4. Money — Discounts (скидки)

### 4.1 seller_discount (скидка продавца, %)

| MP | Endpoint | Field | Notes |
|---|---|---|---|
| WB | report-detail | `sale_percent` | согласованный продуктовый дисконт |
| WB | report-detail | `product_discount_for_report` 🔵 | итоговая согласованная скидка |
| WB | report-detail | `supplier_promo` 🔵 | промокод |
| WB | report-detail | `seller_promo_discount` 🔵 | дополнительная скидка по собственной акции |
| WB | report-detail | `loyalty_discount` 🔵 | скидка лояльности от продавца |
| Ozon | (нет explicit field; inferable из price difference) | ⚠️ | |
| YM | (определяется через subsidies?) | ⚠️ | |

⚠️ **L1 хранит только `sale_percent`** — теряется WB богатая декомпозиция скидок.

### 4.2 mp_sponsored_discount (СПП / Yandex cashback / Ozon premium)

Это важнейший concept — **MP кладёт деньги поверх payout продавцу** для удержания клиента. Источники разные:

| MP | Concept | Endpoint | Field |
|---|---|---|---|
| WB | СПП — Скидка постоянного Покупателя | report-detail | `ppvz_spp_prc` (% от цены) |
| WB | СПП | (нет ₽-поля; derived) | ⚠️ |
| WB | WB Кошелёк payment discount | supplier/sales | `paymentSaleAmount` 🔵 (₽) |
| WB | cashback программа лояльности | report-detail | `cashback_amount`, `cashback_discount`, `cashback_commission_change` 🔵 |
| WB | wibes-скидка | report-detail | `wibes_wb_discount_percent` 🔵 |
| WB | installment cofinancing | report-detail | `installment_cofinancing_amount` 🔵 |
| Ozon | bank_coinvestment / stars / pick_up_point_coinvestment | finance/realization | `delivery_commission.bank_coinvestment` etc. |
| Ozon | bonus | finance/realization | `delivery_commission.bonus` |
| Ozon | compensation | finance/realization | `delivery_commission.compensation` |
| YM | YANDEX_CASHBACK | stats/orders | `subsidies[type=YANDEX_CASHBACK].amount` |
| YM | SUBSIDY (промо/купоны) | stats/orders | `subsidies[type=SUBSIDY].amount` |
| YM | DELIVERY (DBS) | stats/orders | `subsidies[type=DELIVERY].amount` |
| YM | cofinanceValue | stats/orders | `items[].cofinanceValue` 🔵 |
| YM | MARKETPLACE price (купоны) | stats/orders | `prices[type=MARKETPLACE].total` |
| YM | CASHBACK price | stats/orders | `prices[type=CASHBACK].total` |

⚠️ **Это самый зашумлённый concept** — у каждого MP минимум 3-5 sub-categories, и они **семантически разные**:
- WB СПП — скидка, которую WB даёт ИЗ СВОЕГО кармана покупателю; для селлера это «незаметная» скидка (он получит payout как будто скидки не было).
- Ozon `bonus`/`bank_coinvestment` — выплаты ПАРТНЁРОВ (банков), не самого Ozon.
- YM SUBSIDY — баллы, которые селлер получает в виде уменьшения комиссии.

**RFC-5** (P0): нужна ли в L1 декомпозиция `mp_sponsored_discount` по источникам, или достаточно одного агрегата? Сейчас в L1 есть только `spp-prc` (WB-only).

---

## 5. Money — Net Payout

### 5.1 for_pay (к перечислению)

| MP | Endpoint | Field | Транзакционная семантика |
|---|---|---|---|
| WB | report-detail | `ppvz_for_pay` | Sale: положительное, return: отрицательное (raw) |
| WB | finance v1 detailed | `forPay` 🔵 (string!) | Тот же concept, JSON число → string |
| WB | supplier/sales | `forPay` | Per-event |
| Ozon | finance/realization | `delivery_commission.amount` | для sale; `return_commission.amount` для return |
| Ozon | transaction/list | `amount` | за всю операцию (sale + services); для services отдельно |
| YM | stats/orders | derived из commissions (FEE+AGENCY+DELIVERY+PAYMENT) | вычисленное |
| YM | (нет single field) | ❌ derived | ⚠️ |

⚠️ **YM нет прямого `for_pay`** — приходится вычислять. Это известно (см. §6.3 в canonical-formulas).

✅ В L1 поле `for-pay` устаканено: всегда ≥ 0, знак через `operation`. Это работает.

### 5.2 mp_commission (комиссия в ₽)

| MP | Endpoint | Field |
|---|---|---|
| WB | report-detail | `ppvz_sales_commission` |
| WB | finance v1 detailed | `commission_value` 🔵 |
| Ozon | finance/realization | `delivery_commission.commission` (итоговая) |
| Ozon | finance/realization | `delivery_commission.standard_fee` (базовая) |
| Ozon | transaction/list | `sale_commission` (numeric) |
| YM | stats/orders | `commissions[type=FEE].actual` |

✅ Покрыто. ⚠️ В L1 поле называется `wb-commission` (WB-prefix) — misleading. Уже отмечено в data-dictionary §finance known gaps.

**RFC-6** (P1): переименовать `wb-commission` → `mp-commission`.

---

## 6. Money — Costs Outside `for_pay`

### 6.1 logistics / delivery_cost

| MP | Endpoint | Field | Grain |
|---|---|---|---|
| WB | report-detail | `delivery_rub` | per-row (но 0 на sale-строках, > 0 на отдельных «Логистика»-строках) |
| WB | finance v1 detailed | `deliveryRub` 🔵 | то же |
| Ozon | transaction/list | `delivery_charge` 🔵 (deprecated, до 2024-02-01) | per-operation |
| Ozon | transaction/list | `services[name=MarketplaceServiceItem*Logistic]` | per-event |
| Ozon | cash-flow-statement | `details.delivery.delivery_services` | period-level |
| YM | stats/orders | `commissions[type=DELIVERY_TO_CUSTOMER].actual` | per-order |

✅ Покрыто всеми. ⚠️ Ozon — раздроблено по двум разным endpoint'ам.

### 6.2 storage_fee

| MP | Endpoint | Field | Notes |
|---|---|---|---|
| WB | paid_storage | `warehousePrice` | per (date, barcode, warehouse) |
| WB | report-detail | `storage_fee` | per-row (но обычно 0 — реальные данные через paid_storage) |
| Ozon | cash-flow-statement | `details.services.items[name=MarketplaceServiceStorageStub]` | period-level (нет per-article) |
| Ozon | report/placement/by-products 🔵 | (XLSX отчёт, не в OpenAPI) | возможно per-article? |
| YM | stats/orders | `commissions[type=RETURNED_ORDERS_STORAGE].actual` 🔵 | **только для FBS, только для возвратов** |
| YM | reports/united-marketplace-services 🔵 | (downloadable file) | **возможно полное хранение** (untested) |

⚠️ **YM не такой пустой как казалось**: `RETURNED_ORDERS_STORAGE` у нас игнорируется. Это **не** общее хранение FBO, но это что-то.

⚠️ **L1 gap**: для YM поле `storage_fee` всегда `nil`, хотя API даёт частичные данные. Можно улучшить.

**RFC-7** (P1): подключить YM `commissions[type=RETURNED_ORDERS_STORAGE]`.

### 6.3 acceptance / sorting

| MP | Endpoint | Field |
|---|---|---|
| WB | report-detail | `acceptance` |
| WB | acceptance_report 🔵 | dedicated отчёт |
| Ozon | transaction/list | `services[name=MarketplaceServiceItem*Sorting / *IntakeSorting]` |
| YM | stats/orders | `commissions[type=SORTING].actual` 🔵 |
| YM | stats/orders | `commissions[type=INTAKE_SORTING].actual` 🔵 |

⚠️ **L1 gap**: YM SORTING/INTAKE_SORTING не подключены, поле `acceptance` для YM = `nil`.

**RFC-8** (P1): подключить YM SORTING/INTAKE_SORTING.

### 6.4 penalty / fines

| MP | Endpoint | Field |
|---|---|---|
| WB | report-detail | `penalty` |
| WB | analytics/measurement-penalties 🔵 | детализация |
| WB | analytics/deductions 🔵 | детализация |
| WB | analytics/goods-labeling 🔵 | детализация |
| WB | analytics/antifraud-details 🔵 | **самовыкупы** — отдельный concept |
| Ozon | cash-flow-statement | `details.others` (возможно — нужно проверить) |
| YM | (no direct field; manual?) | ❌ |

⚠️ **L1 gap**: для YM penalty всегда `nil`. Нет API endpoint'а.

**RFC-9**: WB штрафы можно декомпозировать на 4 sub-источника (measurement / deductions / labeling / antifraud). Нужно ли?

### 6.5 deduction (прочие удержания)

| MP | Endpoint | Field |
|---|---|---|
| WB | report-detail | `deduction` |
| Ozon | cash-flow-statement | `details.others.*` (?) |
| YM | (нет) | ❌ |

### 6.6 additional_payment (доплаты, компенсации)

| MP | Endpoint | Field |
|---|---|---|
| WB | report-detail | `additional_payment` |
| WB | report-detail | `rebill_logistic_cost` 🔵 — возмещение издержек по перевозке |
| Ozon | cash-flow-statement | `corrections`, `compensation` |
| Ozon | finance/compensation 🔵 | dedicated отчёт (async) |
| Ozon | finance/decompensation 🔵 | dedicated отчёт (async) |
| YM | stats/orders | `subsidies[type=DELIVERY]` (DBS) |

### 6.7 acquiring_fee (эквайринг)

| MP | Endpoint | Field |
|---|---|---|
| WB | report-detail | `acquiring_fee` |
| WB | finance/v1/acquiring/detailed 🔵 | + VAT, invoice details |
| Ozon | cash-flow-statement | `details.others` (acquiring отдельно?) |
| Ozon | transaction/list | (внутри `services[]`?) | ⚠️ нужно проверить |
| YM | stats/orders | `commissions[type=PAYMENT_TRANSFER].actual` |
| YM | stats/orders | `commissions[type=AGENCY].actual` (приём платежа) |

⚠️ **YM**: PAYMENT_TRANSFER + AGENCY — оба компонента acquiring. Сейчас в L1 `acquiring-fee` = только PAYMENT_TRANSFER (см. data-dictionary §finance source mapping).

**RFC-10** (P1): что считать YM acquiring? PAYMENT_TRANSFER или PAYMENT_TRANSFER + AGENCY?

---

## 7. Account-Level Services (no article)

Это операции МП **без артикула** — подписки, перемещения, пакетирование, штрафы общего характера.

| MP | Endpoint | Где живут |
|---|---|---|
| WB | report-detail | строки с `sa_name = nil` (нерегулярные, ~0.3% потерь) |
| WB | finance/v1/sales-reports/detailed 🔵 | возможно явные |
| Ozon | cash-flow-statement | `details.services.items[]` — name + price |
| Ozon | transaction/list | services с `posting=null` |
| YM | (нет explicit account-level concept в API) | ❌ |

✅ Покрыто Ozon (через cash_flow_periods). ⚠️ WB account-level теряются (B-002). YM — нет такого concept'а.

### 7.1 Subscription / platform fees

| MP | Service name |
|---|---|
| WB | (внутри report-detail) |
| Ozon | `MarketplaceServiceItemElectronicServiceStencil` (Premium) |
| YM | `LOYALTY_PARTICIPATION_FEE` |

### 7.2 Warehouse movement

| MP | Field |
|---|---|
| WB | (отсутствует?) |
| Ozon | `services` items с *Movement |
| YM | `INTAKE_SORTING` (частично) |

### 7.3 Packaging

| MP | Field |
|---|---|
| WB | (отсутствует) |
| Ozon | services items |
| YM | (отсутствует) |

---

## 8. Advertising

### 8.1 ad_cost (per-row или per-event)

| MP | Endpoint | Field |
|---|---|---|
| WB | adv/v3/fullstats | `apps[].sum` per (campaign, date, nm_id) |
| WB | adv/v1/promotion/count | список кампаний |
| Ozon | (нет per-row в finance; возможно через services?) | ⚠️ |
| YM | stats/orders | `items[].bidFee` (буст продаж per-item) |
| YM | stats/orders | `commissions[type=AUCTION_PROMOTION]` (буст с оплатой за продажи) |

⚠️ **L1**: `ad_cost` помечено в finance row, но populated только в YM transform. WB ad-spend хранится в отдельной таблице `ad_stats`. Ozon ad-spend через transaction/list services (которые мы НЕ парсим как ad).

**RFC-11**: унифицировать ad_cost — либо везде в `finance.ad_cost` (с пере-распределением для WB), либо явно зафиксировать «WB → ad_stats, Ozon+YM → finance.ad_cost».

---

## 9. Geography

### 9.1 region / city / country

| MP | Endpoint | Field |
|---|---|---|
| WB | analytics/region-sale | `region`, `city`, `country`, `fo` (округ) |
| WB | supplier/sales | `regionName`, `oblastOkrugName`, `countryName` 🔵 — **то же per-event** |
| Ozon | (нет publicly доступного per-region отчёта) | ❌ |
| YM | stats/orders | `result.orders[].deliveryRegion.id` + `.name` 🔵 |
| YM | reports/sales-geography 🔵 | (downloadable file) |

⚠️ **L1**: `region_sales` table — WB-only. Но **WB supplier/sales уже даёт regionName per-event** — теоретически можно собрать region_sales **из существующих данных** без отдельного endpoint'а.

⚠️ **YM region**: есть в каждом заказе, мы не сохраняем.

**RFC-12** (P1): `region_sales` table — единственный источник у WB сейчас `analytics/region-sale` (period-aggregated). Можно дополнить из `supplier/sales` (per-event) для cross-MP consistency.

---

## 10. Inventory / Stocks

### 10.1 quantity available

| MP | Endpoint | Field |
|---|---|---|
| WB | supplier/stocks | `quantity` |
| Ozon | analytics/stock_on_warehouses | per warehouse |
| YM | campaigns/{}/offers/stocks | counts |

### 10.2 history (snapshots over time)

| MP | Endpoint |
|---|---|
| WB | analytics/v1/stocks-report/wb-warehouses 🔵 | **есть история!** |
| Ozon | analytics/turnover/stocks 🔵 | turnover history |
| YM | reports/goods-turnover 🔵 | turnover (downloadable) |

⚠️ **L1 stocks gap «no historical time series»** в data-dictionary — на самом деле API даёт. Просто мы делаем snapshot.

**RFC-13** (P2): эволюционно — добавить историю остатков (новая таблица `stocks_history`).

---

## 11. Special Concepts (cross-MP issues)

### 11.1 returns_qty signed

⚠️ **Проблема знаков `quantity` для returns** (data-dict §finance known gaps):
- WB raw: `quantity` для return-строки **может быть** negative ИЛИ positive (depends on report version) + `return_amount` поле.
- Ozon: всегда positive (через separate row).
- YM: status-based (нет negative quantity).

**RFC-14** (P0): зафиксировать инвариант `quantity ≥ 0 always` в L1, transform нормализует. (Уже отмечено в обзоре L1.)

### 11.2 for_pay sign on returns

⚠️ Аналогично для `for_pay`:
- WB raw: на return — **отрицательное**.
- Ozon raw: positive (return_commission.amount > 0, **семантически означает «к возврату от селлера МП»**).
- YM: derived, всегда positive.

**RFC-15** (P0): инвариант `for-pay ≥ 0 always`, знак через `operation`. (Уже отмечено в обзоре L1.)

---

## 12. Summary: RFCs (открытые вопросы)

| # | Severity | Status | Вопрос | Источник |
|---|---|---|---|---|
| RFC-1 | P1 | ✅ **closed 2026-04-28 (no-rename)** | Рассмотрели: ~256 cross-cutting refs (Clojure schema/transforms/consumers, SQL columns, HTML data-attributes, URL query params, JS payloads). Cost-benefit отрицательный: имя — косметика, поведение корректное (все 3 MP консистентно пишут internal id в это поле). Закрыто **через документацию** — semantics зафиксированы в `data-dictionary.md` §finance и в docstring `schema/normalized/finance.clj`. Имя `:nm-id` остаётся, но семантически читается как «MP internal product id». | §1.2 |
| RFC-2 | P3 | open | Добавить `cart-id` для группировки multi-item orders | §1.4 |
| RFC-3 | **P0** | ✅ **closed 2026-04-28** | Двухуровневая operation (`kind` + `subtype`) — добавлены `:operation-kind` (canonical enum) и `:operation-subtype` (raw classifier) в L1 схему. WB / Ozon / YM transforms заполняют оба поля. Domain consumers переключены на `:operation-kind` с fallback на legacy `:operation` строку. См. `test/analitica/marketplace/operation_kind_test.clj`. | §2.1 |
| RFC-4 | P1 | ✅ **closed 2026-04-28 (no-rename)** | Каждый MP имеет свою native convention для «revenue base». Полная кросс-MP алигнация требует потери точности. Закрыто **через документацию** в `data-dictionary.md` §finance Price-field semantics. Для точных buyer-paid сумм рекомендуется использовать endpoint-specific поля (WB `finishedPrice`, YM `prices[BUYER]`, Ozon `accruals_for_sale`). | §3.1 |
| RFC-5 | **P0** | ✅ **closed 2026-04-28 (no-decomp)** | Решение: **не делаем** per-source decomposition. Анализ показал семантическую гетерогенность: WB СПП — buyer-side (не влияет на `for-pay` селлера, видна только в retail−price-with-disc); Ozon bonus/bank/stars/pup и YM subsidy — seller-side (уже включены в `for-pay`). Один унифицированный L1-поле невозможно без потери смысла. Текущее состояние сохранено: WB → `:spp-prc` (%); Ozon/YM → агрегаты в `for-pay`. Декомпозиция per-source может быть добавлена при появлении бизнес-задачи (например, отчёт «откуда пришли деньги от MP-субсидий»). | §4.2 |
| RFC-6 | P1 | ✅ **closed 2026-04-28** | Переименовали `:wb-commission` → `:mp-commission` (schema, transforms, consumers, sync, tests). DB-колонка переименована миграцией `ALTER TABLE finance RENAME COLUMN`. | §5.2 |
| RFC-7 | P1 | ✅ **closed 2026-04-28** | YM `commissions[type=RETURNED_ORDERS_STORAGE]` → `:storage-fee` (раньше всегда `nil`). | §6.2 |
| RFC-8 | P1 | ✅ **closed 2026-04-28** | YM `SORTING + INTAKE_SORTING + RETURN_PROCESSING` → `:acceptance` (раньше всегда `nil`). | §6.3 |
| RFC-9 | P3 | open | Декомпозиция WB penalty на 4 sub-источника | §6.4 |
| RFC-10 | P1 | ✅ **closed 2026-04-28** | YM acquiring = `PAYMENT_TRANSFER + AGENCY` (раньше AGENCY ошибочно складывался с FEE в `:wb-commission`; FEE теперь чистый, AGENCY переехал в `:acquiring-fee`). Также `EXPRESS_DELIVERY_TO_CUSTOMER + CROSSREGIONAL_DELIVERY` теперь попадают в `:delivery-cost`. | §6.7 |
| RFC-11 | P1 | ✅ **closed 2026-04-28 (already unified)** | Анализ показал: `domain/pnl.clj/ad-spend-total` уже имеет четкую preference order — **canonical путь** = `SUM(finance.ad_cost)` (primary для всех MP); **legacy fallback** = `ad_stats` JOIN (только для WB на периоды до US5-миграции). YM/Ozon используют только canonical. По мере прогресса миграции `ad_stats` становится pure raw-cache (полезен для views/clicks/CTR метрик, где finance.ad_cost не подходит). Закрыто **через документацию** — архитектура корректная, требуется лишь пояснение. | §8.1 |
| RFC-12 | P1 | ✅ **closed 2026-04-28** | `domain/geography.clj/fetch-regions` теперь принимает `:source :sales` и `:source :combined` для агрегации per-event region из `sales` таблицы (WB `regionName`, YM `deliveryRegion.name`). Закрывает gap «region_sales только WB». Geography report можно теперь скормить смешанные данные из обоих источников через `:combined`. Ozon по-прежнему без regional покрытия (нет API). | §9.1 |
| RFC-13 | P2 | ✅ **closed 2026-04-28** | Добавлена таблица `stocks_history` (daily snapshot of `stocks`) + Malli схема + миграция. `domain/stock.clj` получил `fetch-history`, `velocity`, `days-of-supply`, `stock-trend`. `analitica.materialize/snapshot-stocks-history!` идемпотентно записывает текущий день. Forward-only — backfill старых дат невозможен. MP-native history endpoints (WB stocks-report, Ozon turnover, YM goods-turnover) отложены до бизнес-нужды. | §10.2 |
| RFC-14 | **P0** | ✅ **closed 2026-04-28** | Инвариант `quantity ≥ 0` — нормализация в WB / YM transforms (Ozon / WB sales уже соответствовал). Schema tightening (`[:>= 0]`) отложен до backfill старых данных. | §11.1 |
| RFC-15 | **P0** | ✅ **closed 2026-04-28** | Инвариант `for-pay ≥ 0` — нормализация в WB / YM transforms (Ozon уже соответствовал). YM «cancelled» переклассифицированы в `:adjustment` с for-pay=0; убыток (bidFee + commissions) остаётся в dedicated полях. Schema tightening отложен до backfill. | §11.2 |

---

## 13. Findings — что мы недополучаем сейчас

Обнаружено в Phase B (помимо Phase A inventory):

| Тип находки | Концепт | Источник | Влияние |
|---|---|---|---|
| **Скрытый источник** | YM storage частично есть | `commissions[RETURNED_ORDERS_STORAGE]` | YM UE/P&L underestimates по storage |
| **Скрытый источник** | YM acceptance частично есть | `commissions[SORTING/INTAKE_SORTING]` | YM UE/P&L underestimates по acceptance |
| **Скрытый источник** | YM region per-event | `stats/orders.deliveryRegion` | region_sales для YM можно построить |
| **Скрытый источник** | WB region per-event | `supplier/sales.regionName` | альтернативный путь к region_sales |
| **Скрытый источник** | WB cashback/wibes/installment | report-detail | новые подкатегории discount'а |
| **Скрытый источник** | Ozon `seller_price_per_instance` | finance/realization/posting | per-article net amount |
| **Семантический конфликт** | `sku` ≠ `sku` | WB (=barcode) vs Ozon (=internal id) | docs warning |
| **Семантический конфликт** | `nm-id` хранит и WB nmId и Ozon SKU | L1 design | RFC-1 |
| **Подлинный gap** | YM penalty/deduction | нет API | документирован как N/A |
| **Подлинный gap** | YM general FBO storage (не RETURNED_ORDERS_STORAGE) | возможно reports/united-marketplace-services | требует empirical sampling |
| **Подлинный gap** | Ozon per-region | нет API | документирован как N/A |

---

## 14. Что дальше

### Закрыто перед Phase C (2026-04-28)

✅ **RFC-3 / RFC-14 / RFC-15** — двухуровневая `:operation-kind` + `:operation-subtype`,
инварианты `quantity ≥ 0` и `for-pay ≥ 0`. Изменения: `schema/normalized/finance.clj`,
все три `marketplace/*/transform.clj`, `domain/finance.clj`, `audit/rule_impl.clj`.
Тесты: `test/analitica/marketplace/operation_kind_test.clj` (11 tests / 36 assertions).
Полный прогон: 760 тестов / 2787 assertions, 0 падений.

Это было важно сделать **до** Phase C, потому что reconciliation tests
складывают `for-pay` по `:operation-kind` группам — без нормализации знаков
и канонической классификации сравнение с банком/`account/balance` давало бы
ложные расхождения.

### Phase C — Reconciliation Rules

1. Из crosswalk вывести пары endpoint'ов внутри одного MP, которые
   должны численно сходиться:
   - WB `account/balance.current` ⟷ SUM(`report-detail.ppvz_for_pay`) − ad_spend (за весь период)
   - Ozon `cash-flow-statement.payments.payment` ⟷ SUM(`finance/realization.delivery_commission.amount`) (sale + return) за тот же период
   - Ozon `transaction/list.amount` ⟷ `finance/realization` (агрегаты)
   - YM `stats/orders` всё ⟷ `reports/united-netting` (downloadable)
2. Опционально — empirical sampling: сгенерировать YM `united-marketplace-services`
   отчёт на реальном аккаунте и зафиксировать его структуру (RFC-7 candidate).

Phase D — L1 Contract Refresh — применит оставшиеся (открытые) RFC-decisions
к `data-dictionary.md` + соответствующим Malli схемам и transforms. Кандидаты
после Phase C: только P3 — RFC-2 (cart-id), RFC-9 (WB penalty decomp), RFC-13 (stocks history). Все P0/P1 RFC закрыты 2026-04-28.
(YM commission types).

---

## Приложения

- `docs/inventory/wb-schemas.tsv` — 455 полей из 15 WB endpoint'ов
- `docs/inventory/ozon-schemas.tsv` — 253 полей из 13 Ozon endpoint'ов
- `docs/inventory/ym-schemas.tsv` — 157 полей из 8 YM endpoint'ов
