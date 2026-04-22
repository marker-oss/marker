(ns analitica.cli
  (:require [analitica.config :as config]
            [analitica.db :as db]
            [analitica.sync :as sync]
            [analitica.ingest :as ingest]
            [analitica.materialize :as materialize]
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
            [analitica.schema.infer :as schema-infer]
            [analitica.schema.loader :as schema-loader]
            [analitica.schema.regenerate :as schema-regenerate]
            [analitica.schema.registry :as schema-registry]
            [analitica.schema.validator :as schema-validator]
            [clojure.tools.cli :refer [parse-opts]]
            [clojure.pprint :as pp]
            [clojure.string :as str]
            [com.brunobonacci.mulog :as mu]))

(def cli-options
  [["-p" "--period PERIOD" "Period: last-7-days, last-30-days, this-month, etc."
    :default "last-30-days"]
   ["-f" "--from DATE" "Start date: 2026-03-01"]
   ["-t" "--to DATE" "End date: 2026-03-31"]
   ["-m" "--marketplace MP" "Marketplace: wb, ozon, ym (default: wb)"
    :default "wb"]
   ["-e" "--export PATH" "Export to file (csv/xlsx)"]
   ["-h" "--help" "Show help"]])

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
  (mu/start-publisher! {:type :console})
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
  (cost-price/load-from-1c)
  (let [{:keys [loaded errors]} (schema-loader/load-all!)]
    (println (str "Registered " loaded " API schemas from resources/schemas/"))
    (doseq [{:keys [file message]} errors]
      (println (str "  Warning: " file " failed to load: " message)))))

(defn- resolve-period [{:keys [from to period]}]
  (if (and from to)
    {:from from :to to}
    (keyword period)))

(defn- handle-ingest [args opts]
  (let [what   (keyword (first args))
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
  (let [what   (keyword (first args))
        period (resolve-period opts)
        mp     (keyword (:marketplace opts "wb"))]
    (case what
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
      (println "Unknown materialize target:" (first args)))))

(defn- handle-rebuild [args opts]
  (let [what   (keyword (first args))
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
  (let [what   (first args)
        period (resolve-period opts)
        export (:export opts)]
    (case what
      "sales"   (if export
                  (sales/export-excel period export)
                  (sales/dashboard period))
      "finance" (if export
                  (finance/export-excel period export)
                  (finance/report period))
      "ue"      (if export
                  (ue/export-excel period export)
                  (ue/report period))
      "abc"     (abc/report period)
      "returns" (returns/report period)
      "stock"   (if export
                  (stock/export-excel export)
                  (stock/overview))
      "pnl"     (if export
                  (pnl/export-excel period export)
                  (pnl/report period))
      "geo"     (if export
                  (geo/export-excel period export)
                  (geo/report period))
      "trends"  (trends/daily period)
      "wow"     (trends/wow)
      "mom"     (trends/mom)
      "buyout"  (if export
                  (buyout/export-excel period export)
                  (buyout/report period))
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

Subcommands (scaffold; implementations arrive in later tasks):
  reconcile             Build reconciliation report for a period/marketplace
                        (see specs/002-calculation-audit/contracts/cli-audit.md)
  kpi measure|list|show Measure / inspect Accuracy KPI baseline (WB-only in MVP)
  verdict list|show     Read bug-hypothesis verdicts from verdicts.md
  fixture capture|list|verify
                        Manage ground-truth fixtures under specs/.../fixtures/

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

(defn handle-audit
  "Dispatch audit subcommand. Scaffold — real implementations are landed
   incrementally in later tasks (T026 reconcile, T036 kpi, T041 verdict,
   T048 fixture)."
  [rest-args _options]
  (let [sub (first rest-args)]
    (case sub
      nil        (audit-help)
      "help"     (audit-help)
      "--help"   (audit-help)
      "-h"       (audit-help)
      "reconcile" (do (println "audit reconcile — not yet implemented (T026)")
                      (println "See specs/002-calculation-audit/contracts/cli-audit.md")
                      (System/exit 3))
      "kpi"      (do (println "audit kpi — not yet implemented (T036)")
                     (System/exit 3))
      "verdict"  (do (println "audit verdict — not yet implemented (T041)")
                     (System/exit 3))
      "fixture"  (do (println "audit fixture — not yet implemented (T048)")
                     (System/exit 3))
      (do (println "Unknown audit subcommand:" sub)
          (audit-help)
          (System/exit 3)))))

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

See specs/001-openapi-schemas/contracts/cli-schema.md for full contract.

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
  (let [{:keys [options arguments errors]} (parse-opts args cli-options)
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
          "audit"       (handle-audit rest-args options)
          "schema"      (let [code (handle-schema rest-args options)]
                          (when (and (integer? code) (not (zero? code)))
                            (System/exit code)))
          "1c"          (sync/sync-1c!)
          "menu"        (menu)
          "status"      (sync/status)
          (do (println "Unknown command:" command) (print-help)))))))
