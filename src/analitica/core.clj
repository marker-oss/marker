(ns analitica.core
  (:require [analitica.config :as config]
            [analitica.db :as db]
            [analitica.sync :as sync]
            [analitica.marketplace.registry :as registry]
            [analitica.marketplace.wb.client :as wb-client]
            [analitica.marketplace.wb.impl]
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
  (println "=== Analitica started ===")
  (println "Registered marketplaces:" (vec (registry/registered)))
  (sync/status)
  (println "\nTry (help) for available commands."))

(defn mp
  ([] (mp :wb))
  ([key] (registry/get-marketplace key)))

(defn help []
  (println "
=== ANALITICA ===

  (start!)                              -- init system

  SYNC (API -> SQLite):
  (sync/sync! :all :last-30-days)       -- sync everything
  (sync/sync! :sales :last-30-days)     -- sales
  (sync/sync! :orders :last-30-days)    -- orders
  (sync/sync! :finance :last-30-days)   -- financial report
  (sync/sync! :stocks)                  -- stock levels
  (sync/sync! :stats :last-30-days)     -- product stats (funnel)
  (sync/sync! :prices)                  -- current prices
  (sync/sync! :regions :last-30-days)   -- geography
  (sync/sync! :1c)                      -- cost prices from 1C
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
  clojure -M -m analitica.cli sync all -p last-30-days
  clojure -M -m analitica.cli report pnl -f 2026-03-01 -t 2026-03-31
  clojure -M -m analitica.cli report sales -e reports/sales.xlsx
  clojure -M -m analitica.cli status

  PERIODS: :today :yesterday :last-7-days :last-30-days :this-week :this-month
           {:from \"2026-03-01\" :to \"2026-03-31\"}
"))
