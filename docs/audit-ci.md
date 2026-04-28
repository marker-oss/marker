# Audit CI Integration (Phase E-6)

> Scheduled / CI-driven reconciliation runs using `analitica.audit.cli`.
> Audit framework is in `src/analitica/audit/`, rules in `rule_impl.clj`,
> doc in [`reconciliation.md`](reconciliation.md).
>
> Date: 2026-04-28.

## CLI usage

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

### What's printed to stdout

- One-screen digest (counts + non-`:expected` findings with rule id, classification, reason).
- When `--report-dir` is given, the path of the written EDN report.

### What's written to disk (with `--report-dir`)

Filename format: `audit-{from}_{to}_{scope}-{stamp}.edn`. Contains the
**full** report with every discrepancy (including `:expected`) plus rule
metadata. Useful for:

- Diffing two consecutive runs (new findings = drift).
- Backfill into a dashboard.
- Forensic review when `:suspicious` count changes between runs.

## Cron setup

Add to `crontab`:

```cron
# Run analytics audit daily at 08:00 MSK; alert if exit ≠ 0
0 8 * * *  cd /home/mama/DEV/analitica && \
           ANALITICA_DB=/home/mama/DEV/analitica/analitica.db \
           clojure -M:audit --period $(date -d 'yesterday' '+\%Y-\%m') \
                            --marketplace all \
                            --report-dir /var/log/analitica/audit/ \
           > /var/log/analitica/audit/$(date -I).log 2>&1 || \
           mail -s "analitica audit FAIL ($?)" you@example.com < /var/log/analitica/audit/$(date -I).log
```

Notes:
- `$(date -d 'yesterday' '+%Y-%m')` audits the previous **calendar
  month** so the realization data has had time to land. For
  same-day audits, use `--from $(date -I -d '-7 days') --to $(date -I)`.
- The `\%` escapes are needed because cron treats `%` specially.
- Replace `mail` with your channel of choice (slack-cli, telegram-bot,
  pagerduty trigger).

## GitHub Actions skeleton

```yaml
# .github/workflows/audit.yml
name: Reconciliation audit
on:
  schedule:
    - cron: '0 5 * * *'   # daily at 05:00 UTC = 08:00 MSK
  workflow_dispatch:
jobs:
  audit:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - uses: DeLaGuardo/setup-clojure@13
        with:
          cli: '1.12.0.1530'
      - name: Restore SQLite DB from S3
        run: aws s3 cp s3://analitica-prod-db/analitica.db ./analitica.db
        env:
          AWS_ACCESS_KEY_ID:     ${{ secrets.AWS_KEY }}
          AWS_SECRET_ACCESS_KEY: ${{ secrets.AWS_SECRET }}
      - name: Run audit
        run: |
          mkdir -p reports
          clojure -M:audit \
                  --period $(date -d 'yesterday' '+%Y-%m') \
                  --marketplace all \
                  --report-dir reports/
        env:
          ANALITICA_DB: ./analitica.db
      - uses: actions/upload-artifact@v4
        if: always()
        with:
          name: audit-report
          path: reports/
```

## Diffing reports between runs

Each report is a self-contained EDN map. To detect new
`:suspicious` findings between successive runs:

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

A simple shell wrapper that exits non-zero on new findings is
straightforward to bolt on once the EDN files land in a known location.

## Recommended cadence

| Frequency | Period arg | Use case |
|---|---|---|
| Daily | `--from $(date -I -d -7 days) --to $(date -I)` | Catch sync gaps within a week |
| Monthly | `--period $(date -d 'last month' '+%Y-%m')` | Full-month reconciliation after realization closes |
| On-demand | `--period 2026-04 --marketplace ozon` | Investigation when something looks off |

## Per-rule debug

To run a single rule:

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

## Related

- [`reconciliation.md`](reconciliation.md) — full rule catalogue + tolerances + runbook
- [`audit-findings-2026-04-28.md`](audit-findings-2026-04-28.md) — Phase D first run + triage outcomes
- [`concept-crosswalk.md`](concept-crosswalk.md) — concept × MP × endpoint matrix
