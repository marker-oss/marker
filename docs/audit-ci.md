# Audit Integration (Phase E-6)

> Триггеры для запуска reconciliation rules. Audit framework живёт в
> `src/analitica/audit/`, правила — в `rule_impl.clj`, каталог
> в [`reconciliation.md`](reconciliation.md).
>
> Date: 2026-04-28 (post-sync hook), updated from cron-only design.

## Два триггера

1. **Post-materialize хук** *(default, рекомендованный)* — после каждого
   `cli materialize finance` или `cli materialize all` с конкретным
   периодом аудит запускается автоматически и печатает digest.
   **Не требует крона / внешнего хоста.** Работает там, где работает
   твой CLI.
2. **CLI on-demand** — `clojure -M:audit ...` для разовых прогонов
   (исследование, CI-регрессия, period-cumulative проверки).

В единосельерном workflow хук покрывает 95% пользы крона. Крон опционален
ниже, но обычно избыточен.

---

## Post-materialize хук

### Что происходит

После каждого `cli materialize finance` или `cli materialize all` с
явным `--from/--to` периодом, `analitica.audit.hook/audit-after-materialize!`
вызывает `audit/run-reconcile!` на свежем периоде и пишет digest вида:

```
──────────────────────────────────────────────────────────────
  Audit (post-materialize)  wb  2026-04-01 → 2026-04-30
──────────────────────────────────────────────────────────────
  expected:        18
  suspicious:       2
  unclassified:     0

  Findings (non-:expected):
  • aggregate-vs-raw                suspicious   account-level bleed: Δ=42k₽ (rel=0.31)
  • wb-finance-vs-sales-events      suspicious   sync skew Δ=132k₽
```

### Когда срабатывает

| Materialize target | Hook fires? | Reason |
|---|---|---|
| `:finance` | ✅ | основной use-case — finance just changed |
| `:all` | ✅ | sweep mode — finance тоже включён |
| `:sales` / `:orders` / `:stocks` / `:prices` / `:regions` / `:cashflow` | ⚪ skip | audit правила читают только из `finance` |
| Любой target с keyword-период (`:last-30-days`) | ⚪ skip | rules требуют конкретный `{:from :to}` |

### Опт-аут

```bash
clojure -M -m analitica.cli materialize finance \
  --from 2026-04-01 --to 2026-04-30 --marketplace wb \
  --no-audit                                # ← skip the hook
```

Используй когда нужно прогнать большой backfill без шума в логах
(или когда отдельный `:audit` запуск планируется отдельно).

### Failure mode

Если правило бросает / DB уезжает / любой другой сбой в audit —
xook ловит исключение, пишет `WARN: post-materialize audit failed ...`
в `*err*` и **никогда** не блокирует успешный materialize. Audit —
сигнал, не блокер.

---

## CLI on-demand

Для исследования, period-cumulative прогонов, или CI-регрессии.

```bash
# Whole month
clojure -M:audit --period 2026-04 --marketplace all

# Custom range
clojure -M:audit --from 2026-04-01 --to 2026-04-26 --marketplace ozon

# Persist report for diffing across runs
clojure -M:audit --period 2026-04 --marketplace all --report-dir reports/audit/

# Override default tolerance (per-rule overrides still apply)
clojure -M:audit --period 2026-04 --tolerance-abs 50 --tolerance-rel 0.005

# Help
clojure -M:audit --help
```

### Exit codes

| Code | Meaning | What CI should do |
|---:|---|---|
| 0 | clean — no findings | green build |
| 1 | one or more `:suspicious` | flag, investigate, optionally fail build |
| 2 | one or more `:unclassified` (rule threw / unknown input) | **fail build**, rule needs fix |
| 64 | bad CLI args | fail with hint |
| 70 | runtime error (DB connection lost, etc.) | fail with `STDERR` content |

### Что пишется в EDN (с `--report-dir`)

Filename format: `audit-{from}_{to}_{scope}-{stamp}.edn`. Содержит
**полный** report с каждым discrepancy (включая `:expected`) плюс rule
metadata. Полезно для:
- diff'а двух последовательных прогонов (новые findings = drift),
- backfill в дашборд,
- forensic review при изменении `:suspicious` count.

---

## CI: regression check на изменения кода

Если хочется ловить регрессии формул при PR — добавь GitHub Actions
job, который прогоняет audit на тестовой DB:

```yaml
# .github/workflows/audit-regression.yml
name: Audit regression
on: [pull_request]
jobs:
  audit:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: DeLaGuardo/setup-clojure@13
        with:
          cli: '1.12.0.1530'
      - name: Run unit tests (includes audit rule tests)
        run: clojure -M:test
      - name: Audit on test fixture DB (если есть фикстура)
        if: hashFiles('test-fixtures/analitica.db') != ''
        env:
          ANALITICA_DB: ./test-fixtures/analitica.db
        run: |
          mkdir -p reports
          clojure -M:audit --period 2026-04 --marketplace all --report-dir reports/
      - uses: actions/upload-artifact@v4
        if: always()
        with:
          name: audit-report
          path: reports/
```

Это ловит **изменения в формулах** (через `:l2-cross-report-agreement`
и friends), а не data sync issues — последние закроет post-materialize
хук в production.

---

## Cron *(альтернатива, обычно избыточна)*

Если ты захочешь period-level cumulative прогон (например, проверять
закрытые месяцы после задержки realization) — крон всё ещё работает:

```cron
# Run period reconciliation monthly after realization is fully landed
0 8 1 * *  cd /home/mama/DEV/analitica && \
           ANALITICA_DB=/home/mama/DEV/analitica/analitica.db \
           clojure -M:audit --period $(date -d 'last month' '+\%Y-\%m') \
                            --marketplace all \
                            --report-dir /var/log/analitica/audit/ \
           > /var/log/analitica/audit/$(date -I).log 2>&1 || \
           mail -s "analitica audit FAIL ($?)" you@example.com < /var/log/analitica/audit/$(date -I).log
```

Notes:
- `\%` экранирование нужно потому что cron treats `%` specially.
- Заменить `mail` на slack-cli / telegram-bot / pagerduty trigger по вкусу.
- Для одного селлера monthly cadence обычно достаточно — daily через
  post-sync хук уже покрыто.

---

## Diffing reports between runs

Каждый report — self-contained EDN map. Для детектирования новых
`:suspicious` findings между прогонами:

```clojure
(require '[clojure.edn :as edn] '[clojure.set :as set])

(defn rule-ids-suspicious [report]
  (->> (:report/discrepancies report)
       (filter #(= :suspicious (:disc/classification %)))
       (map :disc/rule-id)
       set))

(let [prev (-> (slurp "reports/audit-...-yesterday.edn") edn/read-string)
      curr (-> (slurp "reports/audit-...-today.edn")     edn/read-string)
      new-findings (set/difference (rule-ids-suspicious curr)
                                   (rule-ids-suspicious prev))]
  new-findings)
```

---

## Per-rule debug

Запустить одно правило:

```clojure
(require '[analitica.db :as db]
         '[analitica.audit.rules :as r]
         '[analitica.audit.rule-impl :as ri])
(db/init!)
(ri/register-all!)
(let [rule (r/get-rule :ozon-finance-vs-cashflow)
      ctx  (r/make-context {:period {:from "2026-04-01" :to "2026-04-30"}
                            :marketplace :ozon
                            :tolerance {:abs 100.0 :rel 0.01}})]
  (r/run-rule rule ctx))
```

---

## Related

- [`reconciliation.md`](reconciliation.md) — full rule catalogue + tolerances + runbook
- [`audit-findings-2026-04-28.md`](audit-findings-2026-04-28.md) — Phase D first run + triage outcomes
- [`concept-crosswalk.md`](concept-crosswalk.md) — concept × MP × endpoint matrix
