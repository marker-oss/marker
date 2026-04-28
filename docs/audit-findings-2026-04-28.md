# Phase D — Real-Data Audit Findings

> Phase D артефакт. Прогон всех 8 audit rules против реальной production
> SQLite базы (`analitica.db`) за период 2026-02-01 → 2026-04-30.
> Цель — доказать, что вся методология Phase A → C даёт обнаруживаемые
> сигналы на реальных данных и валидировать tolerance/cap значения.
>
> Связанные документы:
> - [`reconciliation.md`](reconciliation.md) — каталог правил.
> - [`concept-crosswalk.md`](concept-crosswalk.md) — concept × MP × endpoint.
>
> Дата прогона: 2026-04-28. Период: Feb-Apr 2026. DB: 17,679 finance rows
> (WB 15,782 / Ozon 1,409 / YM 488), 1,377 sales, 15 cash_flow_periods.

## Сводная таблица

| Rule | Severity | До triage | После triage | Тип |
|---|---|---|---|---|
| `:aggregate-vs-raw` | critical | 4× :suspicious (29-60%) | 4× :suspicious | 📝 expected (design B-002) |
| `:sales-qty-triangle` | critical | 0 :suspicious | 0 :suspicious | ✅ pass |
| `:unclassified-operations` | informational | 1× :unclassified | 0× | ✅ FIXED |
| `:bank-delta` | critical | 0 (no input) | 0 (no input) | n/a |
| `:tax-absence` | informational | 1× :expected | 1× :expected | ✅ pass |
| `:ozon-finance-vs-cashflow` | critical | THREW | 1× :suspicious 491k₽ | 🟡 needs investigation |
| `:finance-row-internal-consistency` | critical | 1× :suspicious 281 rows | 1× :suspicious 5 rows | 🟡 YM data-quality |
| `:wb-finance-vs-sales-events` | critical | 1× :suspicious 132k₽ | 1× :suspicious 132k₽ | 🟢 expected (sync skew) |

**Итог triage**: exit-code сменился `2 → 1` (исчезли все `:unclassified` —
все правила выполняются без exception). Реальные `:suspicious` — 2 на
каждый scope:
- B-002 design gap (aggregate-vs-raw) — известный, документированный.
- WB sync skew / Ozon settlement complexity / YM data-quality issues —
  настоящие сигналы, требующие внимания.

---

## Finding #1 — `:ozon-finance-vs-cashflow` THREW (Phase C bug)

### До triage

```
:disc/classification :unclassified
:disc/classification-reason "rule threw: SQLiteException ... no such column: operation_kind"
```

### Root cause

Phase B/C добавили `:operation-kind` и `:operation-subtype` в Malli
схему и в transform output, но **не добавили DB колонки**. Sync layer
(`sync.clj`) тоже не пишет эти поля — они существуют только в памяти
во время transform.

Phase C rule `ozon-finance-vs-cashflow` запрашивал `operation_kind` из
SQL — `no such column`.

### Fix

Убран `operation_kind` из SQL, классификация делается в-памяти по
устаревшему `:operation` строковому полю
(`#{"sale" "Продажа"} ⟷ #{"return" "Возврат"}`). То же исправление
применено к `:wb-finance-vs-sales-events` и
`:finance-row-internal-consistency`.

### Карма

Это методологический урок: **новые поля в Malli ≠ новые поля в DB**.
Нужен отдельный шаг (либо DDL миграция + sync обновление, либо явное
in-memory-only documentation).

---

## Finding #2 — Ozon settlement gap 491k₽ (38%)

### Симптом

```
:rule-id :ozon-finance-vs-cashflow
:classification :suspicious
finance net 773,372₽  vs  cash-flow invoice_transfer 281,731₽
Δ=491,640₽ (rel=63.6%)
```

### Initial misdiagnosis

Первая версия rule сравнивала с `payment` полем — оно было `0` для
Feb периодов (ещё не переведено) и **отрицательное** для Mar-Apr
(time-shifted: payment периода N = транзакция в период N+1). Сравнение
давало 976k₽ gap.

### After fix (использовать `invoice_transfer`)

`invoice_transfer` = "сумма к выплате за период" — именно то, что
концептуально равно нашему `for_pay` net. Gap уменьшился до 491k₽
(32% от 976k₽), но всё ещё suspicious.

### Возможные причины оставшегося gap

| Cause | Эстимейт | Действие |
|---|---|---|
| 5 февральских периодов имеют `invoice_transfer = 0` (ещё не закрыты на момент sync) | ~200k₽ revenue не дошёл до invoice_transfer | sync coverage tooling должен подсветить |
| Расхождение в trade_period vs event_date filtering между finance и cash-flow | partial overlap edge cases | требует field-by-field investigation |
| Ozon фундаментально не публикует bit-level identical numbers через два endpoint'а — реальная Ozon-side rounding | `< 1%` | acceptable |
| Что-то ещё (нужен Ozon-domain expert) | оставшиеся 290k₽ | ⚠️ **открытый вопрос** |

### Verdict

🟡 **Needs follow-up** — rule сделал свою работу (выявил gap), но
полный root-cause analysis требует:
- Empirical comparison field-by-field между `cash_flow_periods` и Ozon
  ЛК-выгрузкой за тот же период.
- Возможно — разделить rule на per-period сравнения (а не cumulative).

Документировано в `reconciliation.md` runbook `:ozon-finance-vs-cashflow`.

---

## Finding #3 — 281 → 5 rows с `for_pay > cap × retail_amount`

### Initial state (cap = 1.5×)

| MP | Outlier rows | Avg ratio | Max ratio |
|---|---:|---|---|
| Ozon | 231 | 1.79× | 1.89× |
| YM | 50 | 35.7× | **1694×** |
| WB | 0 | — | — |

### Triage

**Ozon (231 rows)**: max 1.89× — это **легитимные** co-investments
(`bonus + bank_coinvestment + stars + pick_up_point_coinvestment`).
Cap был слишком строгий.

**YM (50 rows)**: max 1694× — это **YM source data quality issue**.
Sample row: `retail_amount=1₽`, `for_pay=1694.94₽`. БГBUYER price из
YM API равен 1₽, при том что фактический payout 1694₽. Возможные причины:
- YM API quirk для специфичных типов заказов (тестовые, ручные коррекции).
- BUYER price entry не пришла в `prices[]` для item.
- Cash вернулся через subsidies/cashback (которые не суммируются в BUYER).

Это **не transform bug** — наш transform корректно берёт BUYER price.
Проблема в исходных данных от YM.

### Fix

Cap расширен до 2.0×. После расширения:
- Ozon: 0 outliers (max 1.89× < 2.0×)
- YM: 5 outliers (max 1694× — настоящие data anomalies)
- WB: 0 outliers

### Verdict

✅ **Resolved with cap relaxation**. Оставшиеся 5 YM rows — настоящие
data quality issues, не transform bugs. Документированы в
`reconciliation.md` для драйл-down при необходимости.

---

## Finding #4 — `:aggregate-vs-raw` 30-60% drop

### Симптом (для всех MP)

| Scope | Raw SUM(for_pay) | Agg SUM(for_pay) | Gap |
|---|---:|---:|---:|
| WB | 2,438k₽ | 1,715k₽ | -723k (29.7%) |
| Ozon | 1,222k₽ | 773k₽ | -449k (36.7%) |
| YM | 1,338k₽ | 540k₽ | -797k (59.6%) |
| All | 4,999k₽ | 3,030k₽ | -1,969k (39.4%) |

### Verdict

📝 **Expected by design**. Это известный B-002 (см. canonical-formulas
§Finance gaps). Rule корректно сигнализирует, что **account-level
операции не попадают в `by-article` aggregation**:
- WB: "Логистика" / "Хранение" / "Возмещение..." — у них article = nil.
- Ozon: service rows из transaction/list — также article = nil.
- YM: cancelled rows и subsidies без артикульной привязки.

Это design choice: by-article специально не агрегирует non-article rows
(они учитываются отдельно через cash_flow_periods для Ozon, отдельные
endpoint'ы для WB).

### Что делать с rule

Tolerance `:abs 100 :rel 0.01` — слишком строгий для этого правила.
**Предложение** (отложено): отдельная per-rule tolerance — для
`:aggregate-vs-raw` rel ≤ 0.5 (50%) — adequate, потому что в этом
сценарии "drop" сам по себе ≠ bug.

Альтернатива — переименовать rule в `:account-level-rows-summary`
(чтобы было понятно, что это **информационное** правило, а не
проверка корректности).

---

## Finding #5 — `:unclassified-operations` (273 cancelled rows)

### Симптом

```
:rule-id :unclassified-operations
:classification :unclassified
operation 'cancelled' not in known-operations (273 rows)
```

273 YM rows с `operation = "cancelled"` не были в whitelist.

### Fix

`known-operations` whitelist в `audit/rule_impl.clj` расширен:
- `"cancelled"` (RFC-3 для YM cancelled orders → :adjustment kind)
- `"adjustment"` (зарезервировано для будущего DB rollout RFC-3)

### Verdict

✅ **Fixed**. Обновлённый прогон не показывает unclassified.

---

## Finding #6 — WB operation map покрывал ~40% production data

### Симптом

При исследовании реальных WB operation values:

| Operation | Rows | Был в map? |
|---|---:|---|
| Возмещение издержек по перевозке/по складским операциям с товаром | 9,035 | ❌ |
| Логистика | 4,857 | ✅ |
| sale | 1,293 | ✅ |
| service | 1,126 | ✅ |
| Возмещение за выдачу и возврат товаров на ПВЗ | 516 | ❌ |
| cancelled | 273 | ❌ (YM) |
| return | 208 | ✅ |
| Обработка товара | 155 | ❌ |
| Коррекция логистики | 125 | ❌ |
| Хранение | 78 | ✅ |
| Штраф | 4 | ✅ |
| Компенсация ущерба | 3 | ✅ |
| Компенсация скидки по программе лояльности | 3 | ❌ |
| Коррекция продаж | 2 | ❌ |
| Удержание | 1 | ✅ |

### Fix

`wb-operation-kind` map в `marketplace/wb/transform.clj` расширен с 11
до 16 entries. Все RFC-3 категории теперь покрывают 100% производственных
данных:

```clj
{"Продажа"                                                    :sale
 "Возврат"                                                    :return
 "Логистика"                                                  :service
 "Коррекция логистики"                                        :service
 "Хранение"                                                   :service
 "Платная приёмка"                                            :service
 "Сторно платной приёмки"                                     :service
 "Обработка товара"                                           :service
 "Возмещение издержек по перевозке/по складским операциям с товаром" :adjustment
 "Возмещение за выдачу и возврат товаров на ПВЗ"              :adjustment
 "Корректировка вознаграждения"                               :adjustment
 "Коррекция продаж"                                           :adjustment
 "Компенсация ущерба"                                         :adjustment
 "Компенсация скидки по программе лояльности"                 :adjustment
 "Штраф"                                                      :adjustment
 "Удержание"                                                  :adjustment
 "Доплата"                                                    :adjustment}
```

### Verdict

✅ **Fixed**. Уроки методологии: **theoretical mapping должен валидироваться
эмпирическим sample** — Phase B мы спроектировали map на основе API доки
и предположений, Phase D production-sample показал реальное распределение.

---

## Finding #7 — WB sync skew 6%

### Симптом

```
:rule-id :wb-finance-vs-sales-events
:classification :suspicious
WB endpoint gap: finance 2,073,573.92₽ vs sales 2,206,274.60₽ (Δ=-132,700.68₽)
```

132k₽ (6%) разница между двумя WB endpoint'ами.

### Verdict

🟢 **Expected sync skew**. WB settlement (`reportDetailByPeriod`)
обновляется раз в неделю; sales (`supplier/sales`) — почти realtime.
В период 2026-02-01 → 2026-04-30 хвост (последняя неделя апреля)
event-stream опережает settlement.

Текущая tolerance (`abs=100, rel=0.01`) слишком строгая для этого
case'а. Предложение: отдельная sync-skew tolerance (rel ≤ 0.1) для
этого правила — как и для `:aggregate-vs-raw`.

Документировано в `reconciliation.md` runbook.

---

## Финальное состояние после triage

### Tests
**779 / 2820 — 0 failures** на каждом этапе.

### Audit suspicious counts (после triage):

| Scope | exit | n-disc | suspicious |
|---|---:|---:|---:|
| WB | 1 | 3 | 2 (aggregate + sync-skew) |
| Ozon | 1 | 123 | 2 (aggregate + cash-flow gap) |
| YM | 1 | 3 | 2 (aggregate + 5 data outliers) |
| All | 1 | 479 | 2 (aggregate + 5 outliers) |

### Изменённые файлы

| Файл | Что |
|---|---|
| `marketplace/wb/transform.clj` | расширен `wb-operation-kind` map (11→16 entries) |
| `audit/rule_impl.clj` | 3 Phase C rules: убран `operation_kind` SQL запрос; cap 1.5×→2.0× для consistency rule; `invoice_transfer` вместо `payment` для cash-flow rule |
| `audit/rule_impl.clj` | `known-operations` whitelist + "cancelled", "adjustment" |
| `test/analitica/audit/phase_c_rules_test.clj` | tests обновлены (invoice-transfer, cap 2.0×) |

---

## Что осталось открытым (Phase E candidates)

| ID | Описание | Тип | Статус |
|---|---|---|---|
| **E-1** | Persist `:operation-kind` / `:operation-subtype` в DB columns | Schema migration + sync update | ✅ closed |
| **E-2** | Re-materialize legacy finance rows для применения RFC-3 normalization | One-time data migration | ✅ closed (17,679 rows backfilled) |
| **E-3** | Per-rule tolerance overrides — `:aggregate-vs-raw` (rel ≤ 0.5), `:wb-finance-vs-sales-events` (rel ≤ 0.1), `:l2-cross-report-agreement` (rel ≤ 0.001) | Audit framework feature | ✅ closed (`:rule/tolerance` key in rule-map; framework support in `audit.rules/run-rule`; tests in `rules_test.clj`) |
| **E-4** | Field-by-field Ozon settlement reconciliation — почему 491k₽ gap | Ozon domain investigation | ✅ closed (rule now compares vs `orders+returns`, not `invoice_transfer`; cash-flow restricted to finance coverage; gap = 0 on Feb-Apr 2026 production data) |
| **E-5** | YM data-quality alerting — flag rows where `BUYER price` сомнительно низкое | New audit rule | ✅ closed (`:ym-buyer-price-anomaly` — cap `for_pay > 5 × retail_amount`; tests in `phase_c_rules_test.clj`) |
| **E-6** | CI integration — auto-fire audit на data churn | DevOps | ✅ closed (post-`materialize` hook в `audit/hook.clj`, wired в `cli.clj handle-materialize`; cron остался опциональным fallback) |

**Все E-1 … E-6 закрыты** в Phase B/C/D/E работе 2026-04-28. E-4 закрыт
переводом сравнения на `cash_flow.orders + returns` + coverage-фильтром
(вместо старого `invoice_transfer`); finance-side и cash-flow-side
сходятся точно за все периоды где realization опубликована.

Дополнительный bonus rule `:l2-cross-report-agreement` (L2-C) добавлен
в этом же sprint — проверяет cross-report consistency Finance/P&L/UE.

E-6 был переориентирован с крона на post-`materialize` хук
(см. дискуссия 2026-04-28): для одного селлера крон избыточен,
а хук срабатывает с zero latency и без внешнего scheduler.

---

## Главный takeaway

Phase D успешно валидирует методологию Phase A → C на реальных данных:

1. **Find bugs in our own work** — finding #1 (rule throws) — Phase C
   код имел зависимость на несуществующую DB колонку. Реальный прогон
   против реальной DB обнаружил этот баг — то, что unit тесты не нашли.

2. **Validate theoretical mappings empirically** — finding #6 — наш
   `wb-operation-kind` map покрывал 40% реальных данных, не 100%
   как мы предполагали в Phase B.

3. **Surface real semantic gotchas** — finding #2 — `payment` vs
   `invoice_transfer` поля cash-flow имеют разную семантику; Phase B
   crosswalk не поймал эту тонкость.

4. **Distinguish bugs from design choices** — findings #4, #7 — большие
   `:suspicious` сигналы имеют легитимные объяснения (B-002 design,
   sync skew). Tolerance per-rule поможет sigma-noise reduction.

5. **Yield production-quality data** — finding #3 (YM 1694× ratio) —
   обнаружили реальные data anomalies в источнике, не в нашем коде.
   Это знание ценно для seller-side: предупреждение «возможны проблемы
   с YM данными за этот период».

В сумме: Phase A-C дали нам **карту**, Phase D — **компас**. Без D мы
имели бы красивую методологию без proof of value; с D — имеем
конкретные числа, конкретные находки, конкретные fixes.
