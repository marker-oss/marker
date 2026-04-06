(ns analitica.cli
  (:require [analitica.config :as config]
            [analitica.db :as db]
            [analitica.sync :as sync]
            [analitica.marketplace.registry :as registry]
            [analitica.marketplace.wb.client :as wb-client]
            [analitica.marketplace.wb.impl]
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
            [clojure.tools.cli :refer [parse-opts]]
            [clojure.string :as str]
            [com.brunobonacci.mulog :as mu]))

(def cli-options
  [["-p" "--period PERIOD" "Period: last-7-days, last-30-days, this-month, etc."
    :default "last-30-days"]
   ["-f" "--from DATE" "Start date: 2026-03-01"]
   ["-t" "--to DATE" "End date: 2026-03-31"]
   ["-e" "--export PATH" "Export to file (csv/xlsx)"]
   ["-h" "--help" "Show help"]])

(defn- init! []
  (config/load-config)
  (db/init!)
  (mu/start-publisher! {:type :console})
  (let [wb-cfg (get-in (config/config) [:marketplaces :wb])]
    (registry/register! :wb (wb-client/make-client wb-cfg)))
  (cost-price/load-from-1c))

(defn- resolve-period [{:keys [from to period]}]
  (if (and from to)
    {:from from :to to}
    (keyword period)))

(defn- handle-sync [args opts]
  (let [what   (keyword (first args))
        period (resolve-period opts)]
    (case what
      :all     (sync/sync! :all period)
      :sales   (sync/sync! :sales period)
      :orders  (sync/sync! :orders period)
      :finance (sync/sync! :finance period)
      :stocks  (sync/sync! :stocks)
      :stats   (sync/sync! :stats period)
      :prices  (sync/sync! :prices)
      :regions (sync/sync! :regions period)
      :1c      (sync/sync! :1c)
      (println "Unknown sync target:" (first args)))))

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
  sync <target>     Sync data from API to SQLite
    targets: all, sales, orders, finance, stocks, stats, prices, regions, 1c

  report <type>     Generate report
    types: sales, finance, ue, abc, returns, stock, pnl, geo, trends, wow, mom, buyout

  status            Show database status

Options:
  -p, --period      Period keyword (last-7-days, last-30-days, this-month)
  -f, --from        Start date (2026-03-01)
  -t, --to          End date (2026-03-31)
  -e, --export      Export to file path (.csv or .xlsx)
  -h, --help        Show this help

Examples:
  clojure -M -m analitica.cli sync all -p last-30-days
  clojure -M -m analitica.cli sync finance -f 2026-03-01 -t 2026-03-31
  clojure -M -m analitica.cli report pnl -f 2026-03-01 -t 2026-03-31
  clojure -M -m analitica.cli report sales -p last-7-days -e reports/sales.xlsx
  clojure -M -m analitica.cli status
"))

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
          "sync"   (handle-sync rest-args options)
          "report" (handle-report rest-args options)
          "status" (sync/status)
          (do (println "Unknown command:" command) (print-help)))))))
