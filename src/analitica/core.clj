(ns analitica.core
  (:require [analitica.config :as config]
            [analitica.db :as db]
            [analitica.sync :as sync]
            [analitica.ingest :as ingest]
            [analitica.materialize :as materialize]
            [analitica.marketplace.registry :as registry]
            [analitica.marketplace.wb.client :as wb-client]
            [analitica.marketplace.wb.impl]
            [analitica.marketplace.ozon.client :as ozon-client]
            [analitica.marketplace.ozon.impl]
            [analitica.marketplace.ym.client :as ym-client]
            [analitica.marketplace.ym.impl]
            [analitica.marketplace.protocol :as proto]
            [analitica.domain.sales :as sales]
            [analitica.domain.finance :as finance]
            [analitica.domain.stock :as stock]
            [analitica.domain.unit-economics :as ue]
            [analitica.domain.abc :as abc]
            [analitica.domain.returns :as returns]
            [analitica.domain.ads :as ads]
            [analitica.domain.prices :as prices]
            [analitica.domain.cost-price :as cost-price]
            [analitica.domain.pnl :as pnl]
            [analitica.domain.geography :as geo]
            [analitica.domain.trends :as trends]
            [analitica.domain.buyout :as buyout]
            [analitica.util.time :as t]
            [com.brunobonacci.mulog :as mu]))

(defn start!
  "Initialize the system: config, DB, marketplace clients."
  []
  (config/load-config)
  (db/init!)
  (mu/start-publisher! {:type :console})
  (let [wb-cfg (get-in (config/config) [:marketplaces :wb])]
    (registry/register! :wb (wb-client/make-client wb-cfg)))
  (when-let [ozon-cfg (config/ozon-config)]
    (try
      (registry/register! :ozon (ozon-client/make-client ozon-cfg))
      (catch Exception e
        (println "Warning: Could not register Ozon:" (.getMessage e)))))
  (when-let [ym-cfg (config/ym-config)]
    (try
      (registry/register! :ym (ym-client/make-client ym-cfg))
      (catch Exception e
        (println "Warning: Could not register YM:" (.getMessage e)))))
  (println "=== Analitica started ===")
  (println "Registered marketplaces:" (vec (registry/registered)))
  ;; Prefer DB as the runtime source of truth for cost prices. When the
  ;; cost_prices table is empty (fresh install or cleared for re-ingest),
  ;; bootstrap from the local 1C CSV via the canonical CostSource path so
  ;; we don't lose the existing repo fallback.
  (let [{:keys [articles]} (cost-price/load-from-db!)]
    (if (pos? articles)
      (println (str "Загружено себестоимостей из БД: " articles " артикулов"))
      (do (println "cost_prices DB is empty → bootstrapping from 1c/units.csv")
          (try (sync/sync-1c!)
               (catch Throwable t
                 (println "  Bootstrap failed:" (.getMessage t)))))))
  (sync/status)
  (println "\nTry (help) for available commands."))

(defn mp
  ([] (mp :wb))
  ([key] (registry/get-marketplace key)))

(defn help []
  (println "
=== ANALITICA ===

  (start!)                              -- init system

  INGEST (API -> raw_data):
  (ingest/ingest! :all :period :last-30-days)           -- fetch everything
  (ingest/ingest! :finance :period :last-30-days)       -- fetch finance report
  (ingest/ingest! :stocks :marketplace :ozon)           -- fetch stock snapshot

  MATERIALIZE (raw_data -> analytical tables):
  (materialize/materialize! :all :period :last-30-days) -- rebuild everything
  (materialize/rebuild! :finance :period :last-30-days) -- clear+rebuild finance

  COST PRICES:
  (sync/sync-1c!)                       -- load 1C CSV into cost_prices

  STATUS:
  (sync/status)                         -- DB row counts

  REPORTS:
  (sales/dashboard :last-7-days)        -- sales by day/article/category
  (finance/report :last-30-days)        -- WB financial report breakdown
  (ue/report :last-30-days)             -- unit economics per SKU
  (abc/report :last-30-days)            -- ABC analysis
  (returns/report :last-30-days)        -- return rate analysis
  (stock/overview)                      -- stock by warehouse/article
  (stock/risk 14)                       -- out-of-stock risk
  (pnl/report :last-30-days)            -- P&L report
  (geo/report :last-30-days)            -- geography of sales
  (trends/wow)                          -- week-over-week
  (trends/mom)                          -- month-over-month
  (trends/daily :last-30-days)          -- daily dynamics
  (buyout/report :last-30-days)         -- buyout rate analysis
  (ads/overview :last-7-days :source :api)  -- sales funnel
  (prices/current)                      -- prices & discounts

  EXPORT:
  (sales/export-excel period path)
  (finance/export-excel period path)
  (ue/export-excel period path)
  (pnl/export-excel period path)
  (stock/export-excel path)
  (geo/export-excel period path)
  (buyout/export-excel period path)

  CLI (from terminal):
  clojure -M -m analitica.cli ingest all -p last-30-days
  clojure -M -m analitica.cli rebuild all -p last-30-days
  clojure -M -m analitica.cli report pnl -f 2026-03-01 -t 2026-03-31
  clojure -M -m analitica.cli report sales -e reports/sales.xlsx
  clojure -M -m analitica.cli status

  PERIODS: :today :yesterday :last-7-days :last-30-days :this-week :this-month
           {:from \"2026-03-01\" :to \"2026-03-31\"}
"))
