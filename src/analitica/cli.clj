(ns analitica.cli
  (:require [analitica.config :as config]
            [analitica.db :as db]
            [analitica.sync :as sync]
            [analitica.ingest :as ingest]
            [analitica.materialize :as materialize]
            [analitica.audit.hook :as audit-hook]
            [analitica.marketplace.registry :as registry]
            [analitica.marketplace.wb.client :as wb-client]
            [analitica.marketplace.wb.impl]
            [analitica.marketplace.ozon.client]
            [analitica.marketplace.ozon.impl]
            [analitica.marketplace.ym.client]
            [analitica.marketplace.ym.impl]
            [analitica.domain.cost-price :as cost-price]
            [analitica.domain.sales :as sales]
            [analitica.domain.finance :as finance]
            [analitica.domain.unit-economics :as ue]
            [analitica.domain.abc :as abc]
            [analitica.domain.returns :as returns]
            [analitica.domain.stock :as stock]
            [analitica.domain.pnl :as pnl]
            [analitica.domain.geography :as geo]
            [analitica.domain.trends :as trends]
            [analitica.domain.buyout :as buyout]
            [analitica.audit.rules :as audit-rules]
            [analitica.audit.report :as audit-report]
            [analitica.audit.rule-impl :as audit-rule-impl]
            [analitica.audit.core :as audit-core]
            [analitica.audit.bank :as audit-bank]
            [analitica.audit.kpi :as audit-kpi]
            [analitica.audit.verdict :as audit-verdict]
            [analitica.audit.fixture :as audit-fixture]
            [analitica.util.time :as util-time]
            [analitica.schema.infer :as schema-infer]
            [analitica.schema.loader :as schema-loader]
            [analitica.schema.regenerate :as schema-regenerate]
            [analitica.schema.registry :as schema-registry]
            [analitica.schema.validator :as schema-validator]
            [clojure.tools.cli :refer [parse-opts]]
            [clojure.pprint :as pp]
            [clojure.string :as str]
            [analitica.logging :as logging]))

(def cli-options
  [["-p" "--period PERIOD" "Period: last-7-days, last-30-days, this-month, etc."
    :default "last-30-days"]
   ["-f" "--from DATE" "Start date: 2026-03-01"]
   ["-t" "--to DATE" "End date: 2026-03-31"]
   ["-m" "--marketplace MP" "Marketplace: wb, ozon, ym (default: wb)"
    :default "wb"]
   ["-e" "--export PATH" "Export to file (csv/xlsx)"]
   ["-h" "--help" "Show help"]])

(defn- reparse-subcommand-args
  "Top-level -main uses parse-opts with :in-order true so subcommands like
   `audit` can own their flags. The downside: flags placed AFTER a simple
   subcommand (e.g. `report pnl -m ym`) never reach parse-opts and the
   defaults leak through. Handlers that use the shared cli-options call this
   to re-parse their rest-args and recover those flags, merging over the
   top-level opts."
  [args opts]
  (let [{:keys [options arguments]} (parse-opts args cli-options :in-order false)]
    [arguments (merge opts options)]))

(declare handle-ingest)
(declare handle-materialize)
(declare handle-rebuild)
(declare handle-report)
(declare handle-audit)
(declare handle-schema)

(defn- prompt
  [text]
  (print text)
  (flush)
  (str/trim (or (read-line) "")))

(defn- menu-choose
  [title options]
  (println (str "\n" title))
  (doseq [[idx {:keys [label]}] (map-indexed vector options)]
    (println (format "  %d) %s" (inc idx) label)))
  (loop []
    (let [choice (prompt "Выберите номер: ")
          idx    (when (re-matches #"\d+" choice)
                   (dec (Integer/parseInt choice)))]
      (if (and idx (<= 0 idx) (< idx (count options)))
        (nth options idx)
        (do
          (println "Некорректный выбор, попробуйте снова.")
          (recur))))))

(defn- menu-period []
  (let [custom? (prompt "Использовать свой период? (y/N): ")]
    (if (re-matches #"(?i)^y" custom?)
      {:from (prompt "Дата начала (YYYY-MM-DD): ")
       :to   (prompt "Дата конца (YYYY-MM-DD): ")}
      (let [p (prompt "Период [last-30-days]: ")]
        {:period (if (seq p) p "last-30-days")}))))

(defn- menu-marketplace []
  (let [{:keys [value]} (menu-choose "Маркетплейс?"
                                     [{:label "Wildberries" :value "wb"}
                                      {:label "Ozon"        :value "ozon"}
                                      {:label "Яндекс Маркет" :value "ym"}])]
    value))

(defn- menu-export []
  (let [exp? (prompt "Экспортировать в файл? (y/N): ")]
    (when (re-matches #"(?i)^y" exp?)
      (let [path (prompt "Путь к файлу (например reports/ue.xlsx): ")]
        (when (seq path) path)))))

(defn- handle-menu-report []
  (let [reports [{:label "sales" :value "sales"}
                 {:label "finance" :value "finance"}
                 {:label "ue" :value "ue"}
                 {:label "abc" :value "abc"}
                 {:label "returns" :value "returns"}
                 {:label "stock" :value "stock"}
                 {:label "pnl" :value "pnl"}
                 {:label "geo" :value "geo"}
                 {:label "trends" :value "trends"}
                 {:label "wow" :value "wow"}
                 {:label "mom" :value "mom"}
                 {:label "buyout" :value "buyout"}]
        {:keys [value]} (menu-choose "REPORT: какой отчёт?" reports)
        period (menu-period)
        export (menu-export)
        opts   (cond-> period export (assoc :export export))]
    (handle-report [value] opts)))

(defn- handle-menu-ingest []
  (let [targets [{:label "all" :value :all}
                 {:label "sales" :value :sales}
                 {:label "orders" :value :orders}
                 {:label "finance" :value :finance}
                 {:label "storage" :value :storage}
                 {:label "stocks" :value :stocks}
                 {:label "stats" :value :stats}
                 {:label "prices" :value :prices}
                 {:label "regions" :value :regions}
                 {:label "cashflow (Ozon)" :value :cashflow}]
        {:keys [value]} (menu-choose "INGEST: что загрузить?" targets)
        mp     (menu-marketplace)
        period (menu-period)]
    (handle-ingest [(name value)] (cond-> period mp (assoc :marketplace mp)))))

(defn- handle-menu-rebuild []
  (let [targets [{:label "all" :value :all}
                 {:label "sales" :value :sales}
                 {:label "orders" :value :orders}
                 {:label "finance" :value :finance}
                 {:label "storage" :value :storage}
                 {:label "stocks" :value :stocks}
                 {:label "stats" :value :stats}
                 {:label "prices" :value :prices}
                 {:label "regions" :value :regions}
                 {:label "cashflow (Ozon)" :value :cashflow}]
        {:keys [value]} (menu-choose "REBUILD: что пересчитать из сырых данных?" targets)
        mp     (menu-marketplace)
        period (menu-period)]
    (handle-rebuild [(name value)] (cond-> period mp (assoc :marketplace mp)))))

(defn- menu []
  (println "\nAnalitica CLI — интерактивный режим")
  (loop []
    (let [{:keys [value]} (menu-choose "Что хотите сделать?"
                                       [{:label "Ingest (API -> сырые данные)" :value :ingest}
                                        {:label "Rebuild (сырые данные -> БД)" :value :rebuild}
                                        {:label "Сформировать отчёт" :value :report}
                                        {:label "Статус БД" :value :status}
                                        {:label "Выход" :value :exit}])]
      (case value
        :ingest  (do (handle-menu-ingest) (recur))
        :rebuild (do (handle-menu-rebuild) (recur))
        :report  (do (handle-menu-report) (recur))
        :status  (do (sync/status) (recur))
        :exit    (println "Готово.")
        (recur)))))

(defn- init! []
  (config/load-config)
  (db/init!)
  (config/reload!)
  (logging/start-publishers!)
  (let [wb-cfg (get-in (config/config) [:marketplaces :wb])]
    (registry/register! :wb (wb-client/make-client wb-cfg)))
  (when-let [ozon-cfg (config/ozon-config)]
    (try
      (registry/register! :ozon (analitica.marketplace.ozon.client/make-client ozon-cfg))
      (catch Exception e
        (println "Warning: Could not register Ozon:" (.getMessage e)))))
  (when-let [ym-cfg (config/ym-config)]
    (try
      (registry/register! :ym (analitica.marketplace.ym.client/make-client ym-cfg))
      (catch Exception e
        (println "Warning: Could not register YM:" (.getMessage e)))))
  (println "Registered marketplaces:" (vec (registry/registered)))
  ;; Prefer DB as the runtime source of truth for cost prices. When the
  ;; cost_prices table is empty (fresh install or cleared for re-ingest),
  ;; bootstrap from the local 1C CSV via the canonical CostSource path so
  ;; we don't lose the existing repo fallback.
  ;; (Same logic lives in core/start! for web/REPL entry points.)
  (let [{:keys [articles]} (cost-price/load-from-db!)]
    (if (pos? articles)
      (println (str "Загружено себестоимостей из БД: " articles " артикулов"))
      (do (println "cost_prices DB is empty → bootstrapping from 1c/units.csv")
          (try (sync/sync-1c!)
               (catch Throwable t
                 (println "  Bootstrap failed:" (.getMessage t)))))))
  (let [{:keys [loaded errors]} (schema-loader/load-all!)]
    (println (str "Registered " loaded " API schemas from resources/schemas/"))
    (doseq [{:keys [file message]} errors]
      (println (str "  Warning: " file " failed to load: " message))))
  (audit-rule-impl/register-all!))

(defn- resolve-period [{:keys [from to period]}]
  (if (and from to)
    {:from from :to to}
    (keyword period)))

(defn- handle-ingest [args opts]
  (let [[args opts] (reparse-subcommand-args args opts)
        what   (keyword (first args))
        period (resolve-period opts)
        mp     (keyword (:marketplace opts "wb"))]
    (case what
      :all     (ingest/ingest! :all     :period period :marketplace mp)
      :sales   (ingest/ingest! :sales   :period period :marketplace mp)
      :orders  (ingest/ingest! :orders  :period period :marketplace mp)
      :finance (ingest/ingest! :finance :period period :marketplace mp)
      :storage (ingest/ingest! :storage :period period :marketplace mp)
      :stocks  (ingest/ingest! :stocks  :marketplace mp)
      :stats   (ingest/ingest! :stats   :period period :marketplace mp)
      :prices  (ingest/ingest! :prices  :marketplace mp)
      :regions  (ingest/ingest! :regions  :period period :marketplace mp)
      :cashflow (ingest/ingest! :cashflow :period period :marketplace mp)
      (println "Unknown ingest target:" (first args)))))

(defn- handle-materialize [args opts]
  (let [[args opts] (reparse-subcommand-args args opts)
        what   (keyword (first args))
        period (resolve-period opts)
        mp     (keyword (:marketplace opts "wb"))
        result (case what
                 :all     (materialize/materialize! :all     :period period :marketplace mp)
                 :sales   (materialize/materialize! :sales   :period period :marketplace mp)
                 :orders  (materialize/materialize! :orders  :period period :marketplace mp)
                 :finance (materialize/materialize! :finance :period period :marketplace mp)
                 :storage (materialize/materialize! :storage :period period :marketplace mp)
                 :stocks  (materialize/materialize! :stocks  :marketplace mp)
                 :stats   (materialize/materialize! :stats   :period period :marketplace mp)
                 :prices  (materialize/materialize! :prices  :marketplace mp)
                 :regions  (materialize/materialize! :regions  :period period :marketplace mp)
                 :cashflow (materialize/materialize! :cashflow :period period :marketplace mp)
                 (do (println "Unknown materialize target:" (first args)) ::unknown))]
    ;; E-6 (2026-04-28): post-materialize audit hook. Fires only for
    ;; finance-touching targets and only when --from/--to gave a concrete
    ;; period (the hook silently skips keyword periods). Errors never
    ;; propagate — see audit.hook docstring.
    (when (and (not= ::unknown result) (not (:no-audit opts)))
      (audit-hook/audit-after-materialize!
        {:entity-type what
         :period      (when (map? period) period)
         :marketplace mp}))
    result))

(defn- handle-rebuild [args opts]
  (let [[args opts] (reparse-subcommand-args args opts)
        what   (keyword (first args))
        period (resolve-period opts)
        mp     (keyword (:marketplace opts "wb"))]
    (case what
      :all     (materialize/rebuild! :all     :period period :marketplace mp)
      :sales   (materialize/rebuild! :sales   :period period :marketplace mp)
      :orders  (materialize/rebuild! :orders  :period period :marketplace mp)
      :finance (materialize/rebuild! :finance :period period :marketplace mp)
      :storage (materialize/rebuild! :storage :period period :marketplace mp)
      :stocks  (materialize/rebuild! :stocks  :marketplace mp)
      :stats   (materialize/rebuild! :stats   :period period :marketplace mp)
      :prices  (materialize/rebuild! :prices  :marketplace mp)
      :regions  (materialize/rebuild! :regions  :period period :marketplace mp)
      :cashflow (materialize/rebuild! :cashflow :period period :marketplace mp)
      (println "Unknown rebuild target:" (first args)))))

(defn- handle-report [args opts]
  (let [[args opts] (reparse-subcommand-args args opts)
        what   (first args)
        period (resolve-period opts)
        export (:export opts)
        mp     (when-let [m (:marketplace opts)] (keyword m))]
    (case what
      "sales"   (if export
                  (sales/export-excel period export :marketplace mp)
                  (sales/dashboard period :marketplace mp))
      "finance" (if export
                  (finance/export-excel period export :marketplace mp)
                  (finance/report period :marketplace mp))
      "ue"      (if export
                  (ue/export-excel period export :marketplace mp)
                  (ue/report period :marketplace mp))
      "abc"     (abc/report period :marketplace mp)
      "returns" (returns/report period :marketplace mp)
      "stock"   (if export
                  (stock/export-excel export :marketplace mp)
                  (stock/overview :marketplace mp))
      "pnl"     (if export
                  (pnl/export-excel period export :marketplace mp)
                  (pnl/report period :marketplace mp))
      "geo"     (if export
                  (geo/export-excel period export :marketplace mp)
                  (geo/report period :marketplace mp))
      "trends"  (trends/daily period :marketplace mp)
      "wow"     (trends/wow :marketplace mp)
      "mom"     (trends/mom :marketplace mp)
      "buyout"  (if export
                  (buyout/export-excel period export :marketplace mp)
                  (buyout/report period :marketplace mp))
      (println "Unknown report:" what))))

(defn- print-help []
  (println "
Analitica CLI — Marketplace Analytics

Usage: clojure -M -m analitica.cli <command> [options]

Commands:
  ingest <target>       Fetch raw data from API to raw_data table
    targets: all, sales, orders, finance, storage, stocks, stats, prices, regions, cashflow

  materialize <target>  Transform raw data into analytical tables (no API calls)
    targets: all, sales, orders, finance, storage, stocks, stats, prices, regions, cashflow

  rebuild <target>      Clear + re-materialize analytical tables from raw data
    targets: all, sales, orders, finance, storage, stocks, stats, prices, regions, cashflow

  report <type>         Generate report
    types: sales, finance, ue, abc, returns, stock, pnl, geo, trends, wow, mom, buyout

  audit <subcommand>    Calculation audit: reconciliation, KPI, verdicts, fixtures
    subcommands: reconcile, kpi, verdict, fixture (use `audit help` for details)

  1c                    Load cost prices from 1C CSV into SQLite

  menu                  Interactive menu (no need to type commands)

  status                Show database status

Options:
  -p, --period      Period keyword (last-7-days, last-30-days, this-month)
  -f, --from        Start date (2026-03-01)
  -t, --to          End date (2026-03-31)
  -m, --marketplace Marketplace: wb, ozon, ym (default: wb)
  -e, --export      Export to file path (.csv or .xlsx)
  -h, --help        Show this help

Examples:
  clojure -M -m analitica.cli ingest finance -f 2026-03-01 -t 2026-03-31
  clojure -M -m analitica.cli materialize finance -f 2026-03-01 -t 2026-03-31
  clojure -M -m analitica.cli rebuild all -p last-30-days -m ozon
  clojure -M -m analitica.cli report pnl -f 2026-03-01 -t 2026-03-31
  clojure -M -m analitica.cli menu
  clojure -M -m analitica.cli status
"))

(defn- audit-help []
  (println "
Analitica CLI — audit subcommands

Usage: clojure -M -m analitica.cli audit <subcommand> [options]

Subcommands:
  reconcile             Build reconciliation report for a period/marketplace
                        (run `audit help` for the full command reference)
  kpi measure|list|show Measure / inspect Accuracy KPI baseline (WB-only in MVP)
  verdict list|show     Read bug-hypothesis verdicts from verdicts.md
                        (list supports --conclusion :confirmed|:refuted|:fixed|:confirmed-deferred|:not-yet-verdicted)
  fixture capture|list|verify
                        Manage ground-truth fixtures (operator-local; see docs/architecture.md §Audit)

Exit codes:
  0  success, no suspicious discrepancies
  1  success with suspicious discrepancies / KPI misses target
  2  unclassified operations detected (new data types)
  3  input error (invalid options, missing file)
  4  KPI baseline refused (incomplete bank reference, FR-011)

Currently registered rules:")
  (doseq [rule (audit-rules/all-rules)]
    (println (audit-rules/format-rule-summary rule)))
  (when (empty? (audit-rules/all-rules))
    (println "  (no rules registered yet — implementations arrive in T018..T022)")))

;; ---------------------------------------------------------------------------
;; audit reconcile
;; ---------------------------------------------------------------------------

(def ^:private reconcile-cli-options
  ;; Global CLI options (-p/-f/-t/-m) are re-declared here because top-level
  ;; parse-opts runs with :in-order true (to avoid rejecting subcommand
  ;; flags like --bank-sum) and therefore doesn't consume them itself.
  [["-p" "--period PERIOD" "Period keyword (last-30-days, this-month, ...)"
    :default "last-30-days"]
   ["-f" "--from DATE" "Start date: 2026-03-01"]
   ["-t" "--to DATE" "End date: 2026-03-31"]
   ["-m" "--marketplace MP" "Marketplace: wb, ozon, ym"
    :default "wb"]
   [nil "--bank-sum N" "Total bank payout sum for the period (scalar reference)"
    :parse-fn #(try (Double/parseDouble %) (catch Exception _ nil))]
   [nil "--bank-csv PATH" "Bank payouts CSV (auto-detects ',' or ';' delimiter)"]
   [nil "--out PATH" "Path to write EDN report"]
   [nil "--rules R1,R2,..." "Comma-separated rule-ids to restrict execution"
    :parse-fn (fn [s] (mapv keyword (clojure.string/split s #",")))]
   [nil "--top-n N" "Number of top causes to show in stdout summary"
    :default 10
    :parse-fn parse-long]])

(defn- resolve-reconcile-period
  "Build {:from :to} from global options. Requires -f/-t or -p."
  [opts]
  (cond
    (and (:from opts) (:to opts))
    {:from (:from opts) :to (:to opts)}
    (:period opts)
    (let [[from to] (util-time/period (keyword (:period opts)))]
      {:from from :to to})
    :else
    (throw (ex-info "audit reconcile requires -f/-t or -p" {:opts opts}))))

(defn- parse-reconcile-bank-input
  "Build :bank-input map from --bank-sum or --bank-csv options, or nil."
  [{:keys [bank-sum bank-csv]} period]
  (cond
    (number? bank-sum) {:sum bank-sum}
    (seq bank-csv)     (audit-bank/parse-bank-csv bank-csv period)
    :else              nil))

(defn- handle-audit-reconcile
  "Dispatch for `audit reconcile` subcommand. Returns exit code (not
   System/exit — caller promotes). Parses subcommand-specific flags, builds
   context, runs the pipeline, prints stdout, optionally writes EDN."
  [rest-args global-options]
  (let [{:keys [options errors]}
        (parse-opts rest-args reconcile-cli-options :in-order false)
        ;; Merge global CLI options (-f/-t/-p/-m) with subcommand-local ones
        ;; (--bank-sum, --bank-csv, --out, --rules, --top-n)
        merged-options (merge global-options options)]
    (cond
      errors
      (do (doseq [e errors] (println "Error:" e))
          3)
      :else
      (try
        (let [period (resolve-reconcile-period merged-options)
              marketplace (keyword (:marketplace merged-options "wb"))
              tolerance   (config/audit-tolerance)
              bank-input  (try
                            (parse-reconcile-bank-input merged-options period)
                            (catch Throwable t
                              (println "Error reading bank reference:" (.getMessage t))
                              ::bank-error))]
          (if (= ::bank-error bank-input)
            3
            (let [{:keys [report exit-code rendered]}
                  (audit-core/run-reconcile!
                    {:marketplace marketplace
                     :period      period
                     :tolerance   tolerance
                     :bank-input  bank-input
                     :rules       (:rules merged-options)
                     :top-n       (:top-n merged-options)})]
              (println rendered)
              (when-let [out (:out merged-options)]
                (audit-report/write-edn! report out)
                (println (str "Wrote report EDN: " out)))
              (long (or exit-code 0)))))
        (catch clojure.lang.ExceptionInfo e
          (println "audit reconcile failed:" (.getMessage e))
          3)
        (catch Throwable t
          (println "audit reconcile crashed:" (.getMessage t))
          5)))))

;; ---------------------------------------------------------------------------
;; audit kpi
;; ---------------------------------------------------------------------------

(def ^:private kpi-measure-cli-options
  ;; Global -p/-f/-t/-m are re-declared here for the same reason as in
  ;; reconcile-cli-options: top-level parse-opts is :in-order true.
  [["-p" "--period PERIOD" "Period keyword (last-30-days, this-month, ...)"
    :default "last-30-days"]
   ["-f" "--from DATE" "Start date: 2026-03-01"]
   ["-t" "--to DATE" "End date: 2026-03-31"]
   ["-m" "--marketplace MP" "Marketplace (MVP only supports wb)"
    :default "wb"]
   [nil "--bank-sum N" "Total bank payout sum for the period (required if no --bank-csv)"
    :parse-fn #(try (Double/parseDouble %) (catch Exception _ nil))]
   [nil "--bank-csv PATH" "Bank payouts CSV (auto-detects ',' or ';' delimiter)"]
   [nil "--excel-sum N" "Secondary reference — sum from manual excel reconciliation"
    :parse-fn #(try (Double/parseDouble %) (catch Exception _ nil))]
   [nil "--excel-by-article PATH" "CSV article,expected_for_pay for detailed reference"]
   [nil "--skus S1,S2,..." "Explicit SKU list (overrides top-30 selection)"
    :parse-fn (fn [s] (mapv str/trim (str/split s #",")))]
   [nil "--captured-by NAME" "Operator name (default: $USER env var)"]])

(def ^:private kpi-list-cli-options
  [[nil "--marketplace MP" "Filter by marketplace (wb|ozon|ym)"]
   [nil "--mp MP" "Alias for --marketplace"]
   [nil "--limit N" "Max rows to show (default 20)"
    :default 20
    :parse-fn parse-long]])

(defn- resolve-kpi-period
  "Build {:from :to} from CLI options. Same rule as reconcile."
  [opts]
  (cond
    (and (:from opts) (:to opts))
    {:from (:from opts) :to (:to opts)}
    (:period opts)
    (let [[from to] (util-time/period (keyword (:period opts)))]
      {:from from :to to})
    :else
    (throw (ex-info "audit kpi measure requires -f/-t or -p" {:opts opts}))))

(defn- parse-kpi-bank-input
  "Build :bank-input from --bank-sum or --bank-csv. Returns nil if neither
   is supplied so the caller can emit the canonical error message."
  [{:keys [bank-sum bank-csv]} period]
  (cond
    (number? bank-sum) {:sum bank-sum}
    (seq bank-csv)     (audit-bank/parse-bank-csv bank-csv period)
    :else              nil))

(defn- fmt-delta-pct [pct]
  (format "%+.2f%%" (double (or pct 0.0))))

(defn- fmt-num [n]
  (format "%,.2f" (double (or n 0.0))))

(defn- render-kpi-measurement-line
  "Render one measurement as a single-line table row for `audit kpi list`."
  [{:keys [kpi-id captured-at marketplace period-from period-to
           delta-rel-pct verdict]}]
  (format "  %-36s  %-19s  %-4s  %s..%s  delta=%s  %s"
          kpi-id
          (or captured-at "—")
          (or marketplace "—")
          (or period-from "—")
          (or period-to "—")
          (fmt-delta-pct delta-rel-pct)
          (or verdict "—")))

(defn- render-kpi-measurement-detail
  "Render one measurement in full, for `audit kpi show <id>`."
  [m]
  (let [{:keys [kpi-id captured-at captured-by marketplace
                period-from period-to sku-list sku-selection-method
                reference-bank-sum reference-excel-sum
                reference-excel-by-article measured-value
                delta-abs-rub delta-rel-pct verdict breakdown report-id]} m]
    (str
      "KPI measurement " kpi-id "\n"
      "  captured-at:   " (or captured-at "—") "\n"
      "  captured-by:   " (or captured-by "—") "\n"
      "  marketplace:   " (or marketplace "—") "\n"
      "  period:        " (or period-from "—") " .. " (or period-to "—") "\n"
      "  SKUs:          " (count (or sku-list [])) " article(s), selection="
      (or sku-selection-method "—") "\n"
      "    " (clojure.string/join ", " (take 10 (or sku-list [])))
      (when (> (count (or sku-list [])) 10) " …") "\n"
      "  bank-sum:      " (fmt-num reference-bank-sum) "\n"
      (when reference-excel-sum
        (str "  excel-sum:     " (fmt-num reference-excel-sum) "\n"))
      (when reference-excel-by-article
        (str "  excel-map:     "
             (count reference-excel-by-article) " article(s)\n"))
      "  measured:      " (fmt-num measured-value) "\n"
      "  delta:         " (fmt-num delta-abs-rub)
      " (" (fmt-delta-pct delta-rel-pct) ")\n"
      "  verdict:       " (or verdict "—") "\n"
      (when breakdown
        (str "  breakdown:\n"
             (with-out-str (pp/pprint breakdown))))
      "  report-id:     " (or report-id "—") "\n")))

(defn- handle-audit-kpi-measure
  "Execute `audit kpi measure` and return an exit code."
  [rest-args global-options]
  (let [{:keys [options errors]}
        (parse-opts rest-args kpi-measure-cli-options :in-order false)
        merged (merge global-options options)]
    (cond
      errors
      (do (doseq [e errors] (println "Error:" e))
          3)
      :else
      (try
        (let [period      (resolve-kpi-period merged)
              marketplace (keyword (:marketplace merged "wb"))]
          (cond
            (not= :wb marketplace)
            (do (println (str "Error: KPI measurement is MVP-gated to WB; got "
                              marketplace ". Use `audit reconcile` for other marketplaces."))
                3)

            :else
            (let [bank-input (try
                               (parse-kpi-bank-input merged period)
                               (catch Throwable t
                                 (println "Error reading bank reference:" (.getMessage t))
                                 ::bank-error))]
              (cond
                (= ::bank-error bank-input)
                3

                (nil? bank-input)
                (do (println "Error: Bank reference required for KPI measurement. Use --bank-sum N or --bank-csv PATH.")
                    3)

                :else
                (try
                  (let [tolerance   (config/audit-tolerance)
                        captured-by (or (:captured-by merged)
                                        (System/getenv "USER")
                                        "unknown")
                        kpi-id (audit-kpi/measure!
                                 {:marketplace marketplace
                                  :period      period
                                  :tolerance   tolerance
                                  :bank-input  bank-input
                                  :skus        (:skus merged)
                                  :excel-sum   (:excel-sum merged)
                                  :captured-by captured-by})
                        row (audit-kpi/show-measurement kpi-id)]
                    (println (render-kpi-measurement-detail row))
                    (case (keyword (:verdict row))
                      :meets-kpi 0
                      :misses-kpi 1
                      0))
                  (catch clojure.lang.ExceptionInfo e
                    (let [data (ex-data e)]
                      (case (:type data)
                        :incomplete-bank-reference
                        (do (println (.getMessage e))
                            (println "Refusing to record baseline (FR-011).")
                            4)
                        :kpi-mvp-gated-to-wb
                        (do (println (.getMessage e)) 3)
                        (do (println "audit kpi measure failed:" (.getMessage e))
                            3)))))))))
        (catch Throwable t
          (println "audit kpi measure crashed:" (.getMessage t))
          5)))))

(defn- handle-audit-kpi-list
  "Execute `audit kpi list` and return an exit code."
  [rest-args _global-options]
  (let [{:keys [options errors]}
        (parse-opts rest-args kpi-list-cli-options :in-order false)]
    (cond
      errors
      (do (doseq [e errors] (println "Error:" e))
          3)
      :else
      (try
        (let [mp   (or (:marketplace options) (:mp options))
              opts (cond-> {:limit (:limit options)}
                     mp (assoc :marketplace (keyword mp)))
              rows (audit-kpi/list-measurements opts)]
          (if (empty? rows)
            (println (str "No KPI measurements recorded"
                          (when mp (str " for marketplace=" mp))
                          "."))
            (do
              (println (format "KPI measurements (%d):" (count rows)))
              (println (format "  %-36s  %-19s  %-4s  %s  %s  %s"
                               "kpi-id" "captured-at" "mp" "period" "Δrel" "verdict"))
              (doseq [row rows]
                (println (render-kpi-measurement-line row)))))
          0)
        (catch Throwable t
          (println "audit kpi list crashed:" (.getMessage t))
          5)))))

(defn- handle-audit-kpi-show
  "Execute `audit kpi show <id>` and return an exit code."
  [rest-args _global-options]
  (let [kpi-id (first rest-args)]
    (cond
      (nil? kpi-id)
      (do (println "Usage: audit kpi show <kpi-id>") 3)
      :else
      (try
        (if-let [row (audit-kpi/show-measurement kpi-id)]
          (do (println (render-kpi-measurement-detail row)) 0)
          (do (println (str "No KPI measurement with kpi-id=" kpi-id))
              3))
        (catch Throwable t
          (println "audit kpi show crashed:" (.getMessage t))
          5)))))

(defn- handle-audit-kpi
  "Dispatch for `audit kpi` subcommand. Delegates to measure/list/show."
  [rest-args global-options]
  (let [sub (first rest-args)]
    (case sub
      nil       (do (println "Usage: audit kpi measure|list|show [options]") 3)
      "measure" (handle-audit-kpi-measure (rest rest-args) global-options)
      "list"    (handle-audit-kpi-list    (rest rest-args) global-options)
      "show"    (handle-audit-kpi-show    (rest rest-args) global-options)
      (do (println "Unknown audit kpi subcommand:" sub)
          (println "Usage: audit kpi measure|list|show [options]")
          3))))

;; ---------------------------------------------------------------------------
;; audit verdict
;; ---------------------------------------------------------------------------

(def ^:private verdict-list-cli-options
  [[nil "--conclusion K" "Filter by conclusion keyword (:confirmed, :refuted, :fixed, :confirmed-deferred, :not-yet-verdicted)"
    :parse-fn (fn [s]
                (let [trimmed (str/trim s)
                      raw     (if (str/starts-with? trimmed ":")
                                (subs trimmed 1)
                                trimmed)]
                  (keyword raw)))]])

(defn- truncate-middle
  "Clip `s` to at most `n` characters, appending `…` when clipped."
  [s n]
  (let [s (or s "")]
    (if (<= (count s) n)
      s
      (str (subs s 0 (max 0 (- n 1))) "…"))))

(defn- render-verdict-list-row
  [{:verdict/keys [id title conclusion linked-ticket]}]
  (format "  %-6s  %-50s  %-20s  %s"
          (or id "—")
          (truncate-middle title 50)
          (str (or conclusion "—"))
          (truncate-middle (or linked-ticket "—") 50)))

(defn- handle-audit-verdict-list
  "Execute `audit verdict list`. Returns exit code (always 0 unless parse error)."
  [rest-args]
  (let [{:keys [options errors]}
        (parse-opts rest-args verdict-list-cli-options :in-order false)]
    (cond
      errors
      (do (doseq [e errors] (println "Error:" e))
          3)
      :else
      (try
        (let [rows (audit-verdict/verdicts-list
                     :conclusion-filter (:conclusion options))]
          (if (empty? rows)
            (println (str "No verdicts found"
                          (when-let [c (:conclusion options)]
                            (str " for conclusion=" c))
                          "."))
            (do
              (println (format "Bug-hypothesis verdicts (%d):" (count rows)))
              (println (format "  %-6s  %-50s  %-20s  %s"
                               "id" "title" "conclusion" "linked-ticket"))
              (doseq [row rows]
                (println (render-verdict-list-row row)))))
          0)
        (catch Throwable t
          (println "audit verdict list crashed:" (.getMessage t))
          5)))))

(defn- handle-audit-verdict-show
  "Execute `audit verdict show <id>`. Exit 0 if found, 3 if not found."
  [rest-args]
  (let [id (first rest-args)]
    (cond
      (nil? id)
      (do (println "Usage: audit verdict show <verdict-id>") 3)
      :else
      (try
        (if-let [md (audit-verdict/verdict-show id)]
          (do (println md) 0)
          (do (println (str "No verdict with id=" id))
              (println "Use `audit verdict list` to see available verdicts.")
              3))
        (catch Throwable t
          (println "audit verdict show crashed:" (.getMessage t))
          5)))))

(defn- handle-audit-verdict
  "Dispatch for `audit verdict` subcommand. Delegates to list/show."
  [rest-args]
  (let [sub (first rest-args)]
    (case sub
      nil     (do (println "Usage: audit verdict list|show [options]") 3)
      "list"  (handle-audit-verdict-list (rest rest-args))
      "show"  (handle-audit-verdict-show (rest rest-args))
      (do (println "Unknown audit verdict subcommand:" sub)
          (println "Usage: audit verdict list|show [options]")
          3))))

;; ---------------------------------------------------------------------------
;; audit fixture
;; ---------------------------------------------------------------------------

(def ^:private fixture-capture-cli-options
  [["-p" "--period PERIOD" "Period keyword (last-30-days, this-month, ...)"]
   ["-f" "--from DATE" "Start date: 2026-03-01"]
   ["-t" "--to DATE" "End date: 2026-03-31"]
   ["-m" "--marketplace MP" "Marketplace: wb, ozon, ym"
    :default "wb"]
   [nil "--from-report ID" "Optional: attach an existing report-id to :fixture/source"]
   [nil "--fixtures-dir PATH" "Override fixtures directory (operator-local; default: see analitica.audit.fixture/default-fixtures-dir)"]
   [nil "--notes TEXT" "Free-form operator notes — why this period is clean"]])

(defn- resolve-fixture-period
  "Build {:from :to} from CLI options. Requires -f/-t or -p."
  [opts]
  (cond
    (and (:from opts) (:to opts))
    {:from (:from opts) :to (:to opts)}
    (:period opts)
    (let [[from to] (util-time/period (keyword (:period opts)))]
      {:from from :to to})
    :else
    (throw (ex-info "audit fixture capture requires -f/-t or -p" {:opts opts}))))

(defn- render-fixture-list-row
  "One-line table row for `audit fixture list`."
  [{:fixture/keys [id marketplace period captured-at row-count source]}]
  (format "  %-20s  %-5s  %s..%s  rows=%-6d  captured=%-19s  src=%s"
          (or id "—")
          (str (or marketplace "—"))
          (or (:from period) "—")
          (or (:to period) "—")
          (long (or row-count 0))
          (or captured-at "—")
          (str (get source :report-id "—"))))

(defn- handle-audit-fixture-capture
  "Execute `audit fixture capture <id>`. Exit 0 on success, 3 on error."
  [rest-args global-options]
  (let [fixture-id (first rest-args)
        flag-args  (rest rest-args)]
    (cond
      (nil? fixture-id)
      (do (println "Usage: audit fixture capture <fixture-id> [-f ... -t ... -m ...] [--from-report ID] [--notes TEXT]")
          3)

      :else
      (let [{:keys [options errors]}
            (parse-opts flag-args fixture-capture-cli-options :in-order false)
            merged (merge global-options options)]
        (cond
          errors
          (do (doseq [e errors] (println "Error:" e)) 3)

          :else
          (try
            (let [period      (resolve-fixture-period merged)
                  marketplace (keyword (:marketplace merged "wb"))
                  fix-dir     (:fixtures-dir merged)
                  captured-by (or (:captured-by merged)
                                  (System/getenv "USER")
                                  "unknown")
                  args        (cond-> {:id           fixture-id
                                       :marketplace  marketplace
                                       :period       period
                                       :captured-by  captured-by}
                                (:from-report merged) (assoc :from-report (:from-report merged))
                                (:notes merged)       (assoc :notes (:notes merged))
                                fix-dir               (assoc :fixtures-dir fix-dir))
                  fixture     (audit-fixture/capture-fixture! args)]
              (println (format "Captured fixture %s" fixture-id))
              (println (format "  marketplace:    %s" (name marketplace)))
              (println (format "  period:         %s .. %s"
                               (:from period) (:to period)))
              (println (format "  rows:           %d" (:fixture/row-count fixture)))
              (println (format "  sha256 (first): %s"
                               (subs (:fixture/sha256-of-rows fixture) 0 16)))
              (println (format "  captured-at:    %s" (:fixture/captured-at fixture)))
              (when-let [rep (get-in fixture [:fixture/source :report-id])]
                (println (format "  source:         %s" rep)))
              (println)
              (println "  expected.pnl:")
              (doseq [[k v] (sort-by key (get-in fixture [:fixture/expected :pnl]))]
                (println (format "    %-20s %s" (str k) v)))
              (println (format "  expected.unit-economics: %d article(s)"
                               (count (get-in fixture [:fixture/expected :unit-economics]))))
              (println (format "  expected.sales-qty.total: %s"
                               (get-in fixture [:fixture/expected :sales-qty :total])))
              (println)
              (println (str "Tip: run `audit reconcile` BEFORE `capture` to confirm the period is clean."))
              0)
            (catch clojure.lang.ExceptionInfo e
              (println "audit fixture capture failed:" (.getMessage e))
              3)
            (catch Throwable t
              (println "audit fixture capture crashed:" (.getMessage t))
              5)))))))

(defn- handle-audit-fixture-list
  "Execute `audit fixture list`. Exit always 0 (empty list is valid)."
  [rest-args _global-options]
  (let [fix-dir (->> rest-args (drop-while #(not= "--fixtures-dir" %)) second)]
    (try
      (let [rows (if fix-dir
                   (audit-fixture/list-fixtures :fixtures-dir fix-dir)
                   (audit-fixture/list-fixtures))]
        (if (empty? rows)
          (println (str "No fixtures found in " (or fix-dir audit-fixture/default-fixtures-dir) "."))
          (do
            (println (format "Ground-truth fixtures (%d):" (count rows)))
            (println (format "  %-20s  %-5s  %-23s  %-10s  %-19s  %s"
                             "id" "mp" "period" "row-count" "captured-at" "source"))
            (doseq [row rows]
              (println (render-fixture-list-row row)))))
        0)
      (catch Throwable t
        (println "audit fixture list crashed:" (.getMessage t))
        5))))

(defn- render-fixture-diff-details
  "Pretty-print pnl-diff + ue-diff + sales-qty-diff from verify-fixture!.
   Called only when verdict = :diff."
  [{:keys [pnl-diff ue-diff sales-qty-diff]}]
  (when (seq pnl-diff)
    (println "  PnL diff:")
    (doseq [[k {:keys [expected actual]}] (sort-by key pnl-diff)]
      (println (format "    %-25s expected=%s  actual=%s"
                       (str k) expected actual))))
  (when (seq ue-diff)
    (println (format "  Unit-economics diff (%d article(s)):" (count ue-diff)))
    (doseq [[art d] (sort-by key ue-diff)]
      (if (= :missing-in-current-db d)
        (println (format "    %-20s missing from current DB" art))
        (do (println (format "    %s:" art))
            (doseq [[k {:keys [expected actual]}] (sort-by key d)]
              (println (format "      %-20s expected=%s  actual=%s"
                               (str k) expected actual)))))))
  (when sales-qty-diff
    (println (format "  sales-qty.total: expected=%s  actual=%s"
                     (:expected sales-qty-diff)
                     (:actual sales-qty-diff)))))

(defn- handle-audit-fixture-verify
  "Execute `audit fixture verify <id>`. Exit 0 if :match, 1 if :diff, 3 if
   :not-found / :sha-mismatch."
  [rest-args _global-options]
  (let [fixture-id (first rest-args)
        flag-args  (rest rest-args)
        fix-dir    (->> flag-args (drop-while #(not= "--fixtures-dir" %)) second)]
    (cond
      (nil? fixture-id)
      (do (println "Usage: audit fixture verify <fixture-id> [--fixtures-dir PATH]") 3)

      :else
      (try
        (let [result (if fix-dir
                       (audit-fixture/verify-fixture! fixture-id :fixtures-dir fix-dir)
                       (audit-fixture/verify-fixture! fixture-id))]
          (case (:verdict result)
            :match
            (do (println (format "Fixture %s: MATCH (no regression)" fixture-id)) 0)

            :diff
            (do (println (format "Fixture %s: DIFF — formulas changed" fixture-id))
                (render-fixture-diff-details result)
                1)

            :sha-mismatch
            (do (println (format "Fixture %s: SHA mismatch (DB drift)" fixture-id))
                (println (format "  fixture sha256: %s" (:fixture-sha result)))
                (println (format "  current sha256: %s" (:current-sha result)))
                (println (format "  row-count: fixture=%d  current=%d"
                                 (get-in result [:row-count :fixture])
                                 (get-in result [:row-count :current])))
                (println "  The DB has drifted from the snapshot — expected values are stale.")
                (println "  Re-run `audit fixture capture` after confirming the new state is clean.")
                3)

            :not-found
            (do (println (format "Fixture %s not found at %s"
                                 fixture-id (:path result)))
                3)

            (do (println (format "Unexpected verdict for %s: %s"
                                 fixture-id (pr-str result)))
                5)))
        (catch Throwable t
          (println "audit fixture verify crashed:" (.getMessage t))
          5)))))

(defn- handle-audit-fixture
  "Dispatch for `audit fixture` subcommand. Delegates to capture/list/verify."
  [rest-args global-options]
  (let [sub (first rest-args)]
    (case sub
      nil        (do (println "Usage: audit fixture capture|list|verify [options]") 3)
      "capture"  (handle-audit-fixture-capture (rest rest-args) global-options)
      "list"     (handle-audit-fixture-list    (rest rest-args) global-options)
      "verify"   (handle-audit-fixture-verify  (rest rest-args) global-options)
      (do (println "Unknown audit fixture subcommand:" sub)
          (println "Usage: audit fixture capture|list|verify [options]")
          3))))

(defn handle-audit
  "Dispatch audit subcommand. Scaffold — real implementations are landed
   incrementally in later tasks (T041 verdict, T048 fixture).

   Returns an exit code; `-main` promotes it to `System/exit` only for
   non-zero values (keeps test runs non-destructive)."
  [rest-args options]
  (let [sub (first rest-args)]
    (case sub
      nil        (do (audit-help) 0)
      "help"     (do (audit-help) 0)
      "--help"   (do (audit-help) 0)
      "-h"       (do (audit-help) 0)
      "reconcile" (handle-audit-reconcile (rest rest-args) options)
      "kpi"      (handle-audit-kpi (rest rest-args) options)
      "verdict"  (handle-audit-verdict (rest rest-args))
      "fixture"  (handle-audit-fixture (rest rest-args) options)
      (do (println "Unknown audit subcommand:" sub)
          (audit-help)
          3))))

(defn- schema-help []
  (println "
Analitica CLI — schema subcommands

Usage: clojure -M -m analitica.cli schema <subcommand> [options]

Subcommands:
  list                          List all registered endpoint contracts
  show <endpoint-id>            Print the full contract for one endpoint
  diff <endpoint-id> [--sample N]
                                Compare contract against N recent raw samples
  regenerate [--marketplace M] [--apply]
                                Regenerate contracts from upstream OpenAPI (WB/YM)
  infer <endpoint-id> [--sample N] [--out PATH]
                                Derive a candidate schema from recent raw data

See docs/architecture.md §Schema contracts for the schema-command reference.

Exit codes: 0 ok, 1 violations, 2 partial support, 3 input error,
            4 target not eligible (e.g. regenerate on :manual), 5+ internal

Currently registered schemas:")
  (let [contracts (schema-registry/all-endpoints)]
    (if (empty? contracts)
      (println "  (none — place EDN files under resources/schemas/<mp>/*.edn)")
      (doseq [c contracts]
        (println (format "  %-40s [%s] %s  v%d"
                         (str (:endpoint/id c))
                         (name (:endpoint/marketplace c))
                         (:endpoint/api-path c)
                         (:contract/version c)))))))

(defn- parse-endpoint-id
  "Convert a CLI-arg string like \":wb/report-detail-by-period\" into the
   corresponding keyword. Accepts both with and without leading colon."
  [s]
  (when (and s (seq s))
    (keyword (if (.startsWith ^String s ":")
               (subs s 1)
               s))))

(defn- handle-schema-show
  "Return exit code: 0 on success, 3 if endpoint not found."
  [rest-args]
  (let [endpoint-id (parse-endpoint-id (first rest-args))]
    (if-let [contract (and endpoint-id (schema-registry/lookup endpoint-id))]
      (do (pp/pprint contract) 0)
      (do (println (str "Endpoint not found: " (pr-str endpoint-id)))
          (println "Available endpoints:")
          (doseq [c (schema-registry/all-endpoints)]
            (println "  " (:endpoint/id c)))
          3))))

(defn- handle-schema-diff
  "Compare current contract against the last N raw_data records for the
   endpoint. Returns exit code: 0 clean, 1 violations found, 3 input
   error. Does NOT call System/exit — the top-level dispatcher does."
  [rest-args]
  (let [endpoint-id (parse-endpoint-id (first rest-args))
        sample-flag (->> rest-args rest (drop-while #(not= "--sample" %)) second)
        sample-n    (or (some-> sample-flag parse-long) 10)]
    (if-not (and endpoint-id (schema-registry/lookup endpoint-id))
      (do (println (str "Endpoint not found: " (pr-str endpoint-id))) 3)
      (let [contract  (schema-registry/lookup endpoint-id)
            marketplace (name (:endpoint/marketplace contract))
            samples   (seq (->> (db/query
                                  ["SELECT source, entity_type, date_from, date_to, payload
                                    FROM raw_data
                                    WHERE source = ?
                                    ORDER BY ingested_at DESC
                                    LIMIT ?"
                                   marketplace sample-n])
                                (mapv (fn [row]
                                        (try (db/parse-json (:payload row))
                                             (catch Exception _ nil))))
                                (remove nil?)))]
        (if-not samples
          (do (println (str "No raw_data samples found for marketplace=" marketplace))
              3)
          (let [results (mapv #(schema-validator/validate contract %) samples)
                all-violations (mapcat :result/violations results)
                by-kind (group-by :violation/kind all-violations)]
            (println (format "Schema diff for %s (sample: %d raw responses)"
                             (str (:endpoint/id contract)) (count samples)))
            (println)
            (if (empty? all-violations)
              (do (println "  ✓ no drift detected") 0)
              (do (doseq [[kind viols] (sort-by key by-kind)]
                    (let [severity (:violation/severity (first viols))]
                      (println (format "  [%s] %s  count=%d"
                                       (name severity) (name kind) (count viols)))
                      (doseq [v (take 3 viols)]
                        (println (format "    path=%s  expected=%s  actual=%s  (×%d)"
                                         (pr-str (:violation/path v))
                                         (:violation/expected v)
                                         (:violation/actual v)
                                         (:violation/occurrences v))))))
                  1))))))))

(defn- flag-value
  "Return the value immediately after `--flag` in a vector of string args,
   or nil if the flag is absent."
  [args flag]
  (->> args (drop-while #(not= flag %)) second))

(defn- handle-schema-regenerate
  "Fetch upstream OpenAPI for every :upstream-openapi contract (optionally
   filtered by --marketplace) and diff vs. the current schema. With
   --apply, overwrite the source EDN file. Without --apply, print diff
   only. Returns exit code (0 clean/ok, 2 partial, 3 error)."
  [rest-args]
  (let [mp-flag     (flag-value rest-args "--marketplace")
        marketplace (when mp-flag (keyword mp-flag))
        apply?      (boolean (some #{"--apply"} rest-args))
        results     (schema-regenerate/regenerate-all!
                      {:marketplace marketplace
                       :apply?      apply?})]
    (if (empty? results)
      (do (println "No contracts to regenerate"
                   (when marketplace (str "for marketplace " marketplace)))
          0)
      (do
        (doseq [r results]
          (println)
          (println (str (:endpoint-id r) "  [" (name (:status r)) "]"))
          (case (:status r)
            :skipped    (println "  reason:" (:reason r))
            :error      (println "  error:" (:reason r))
            :no-changes (println "  no schema changes detected")
            (:changed :partial)
            (let [{:keys [added removed]} (:diff r)]
              (when (seq added)
                (println (str "  added (" (count added) "):  " (vec added))))
              (when (seq removed)
                (println (str "  removed (" (count removed) "): " (vec removed))))
              (doseq [w (:warnings r)]
                (println (str "  warn: " (:context w) " — " (:detail w))))
              (println "  applied?:" (boolean (:applied? r))))))
        (cond
          (some #(= :error (:status %)) results)   3
          (some #(= :partial (:status %)) results) 2
          :else                                     0)))))

(defn- handle-schema-infer
  "Infer a schema from raw_data samples for the given endpoint-id.
   Prints to stdout (or writes to --out) in the full Contract EDN format
   for review. Returns exit code 0 ok, 3 not found / no samples."
  [rest-args]
  (let [endpoint-id (parse-endpoint-id (first rest-args))
        sample-n    (or (some-> (flag-value rest-args "--sample") parse-long) 10)
        out-path    (flag-value rest-args "--out")]
    (if-not endpoint-id
      (do (println "Usage: schema infer <endpoint-id> [--sample N] [--out PATH]") 3)
      (try
        (let [contract (schema-infer/infer-contract endpoint-id sample-n)]
          (if out-path
            (do (spit out-path
                      (with-out-str (pp/pprint contract)))
                (println (str "wrote " out-path))
                0)
            (do (pp/pprint contract)
                0)))
        (catch clojure.lang.ExceptionInfo e
          (println "infer failed:" (.getMessage e))
          3)))))

(defn handle-schema
  "Dispatch schema subcommands. Returns exit code (0/1/3/4); only the
   top-level `-main` promotes it to `System/exit`."
  [rest-args _options]
  (let [sub (first rest-args)]
    (case sub
      nil      (do (schema-help) 0)
      "help"   (do (schema-help) 0)
      "--help" (do (schema-help) 0)
      "-h"     (do (schema-help) 0)
      "list"   (do (schema-help) 0)
      "show"       (handle-schema-show (rest rest-args))
      "diff"       (handle-schema-diff (rest rest-args))
      "regenerate" (handle-schema-regenerate (rest rest-args))
      "infer"      (handle-schema-infer (rest rest-args))
      (do (println "Unknown schema subcommand:" sub)
          (schema-help)
          3))))

(defn -main [& args]
  (let [{:keys [options arguments errors]} (parse-opts args cli-options :in-order true)
        command (first arguments)
        rest-args (rest arguments)]
    (cond
      (:help options) (print-help)
      errors          (do (doseq [e errors] (println "Error:" e)) (print-help))
      (nil? command)  (print-help)
      :else
      (do
        (init!)
        (case command
          "ingest"      (handle-ingest rest-args options)
          "materialize" (handle-materialize rest-args options)
          "rebuild"     (handle-rebuild rest-args options)
          "report"      (handle-report rest-args options)
          "audit"       (let [code (handle-audit rest-args options)]
                          (when (and (integer? code) (not (zero? code)))
                            (System/exit code)))
          "schema"      (let [code (handle-schema rest-args options)]
                          (when (and (integer? code) (not (zero? code)))
                            (System/exit code)))
          "1c"          (sync/sync-1c!)
          "menu"        (menu)
          "status"      (sync/status)
          (do (println "Unknown command:" command) (print-help)))))))
