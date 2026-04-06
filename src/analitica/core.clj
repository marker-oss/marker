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

  (start!)                              -- init system (config + DB + WB client)

  SYNC (API -> SQLite):
  (sync/sync! :sales :last-30-days)     -- sync sales
  (sync/sync! :orders :last-30-days)    -- sync orders
  (sync/sync! :finance :last-30-days)   -- sync financial report
  (sync/sync! :stocks)                  -- sync stock levels
  (sync/sync! :1c)                      -- load cost prices from 1C
  (sync/sync! :all :last-30-days)       -- sync everything
  (sync/status)                         -- row counts per table

  REPORTS (from SQLite by default, :source :api for live):
  (sales/dashboard :last-7-days)
  (finance/report :last-30-days)
  (stock/overview)
  (stock/risk 14)
  (ue/report :last-30-days)
  (abc/report :last-30-days)
  (returns/report :last-30-days)
  (ads/overview :last-7-days :source :api)
  (prices/current)

  EXPORT:
  (sales/export-csv :last-30-days \"reports/sales.csv\")
  (sales/export-excel :last-30-days \"reports/sales.xlsx\")
  (finance/export-excel :last-30-days \"reports/finance.xlsx\")
  (stock/export-excel \"reports/stock.xlsx\")

  COST PRICES:
  (cost-price/load-from-1c)             -- load from 1c/units.csv
  (cost-price/set-price! \"art\" 500.0)

  PERIODS: :today :yesterday :last-7-days :last-30-days :this-week :this-month
           {:from \"2026-03-01\" :to \"2026-03-31\"}
"))
