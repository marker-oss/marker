(ns analitica.core
  (:require [analitica.config :as config]
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
  "Initialize the system. Loads config, creates marketplace clients."
  []
  (config/load-config)
  (mu/start-publisher! {:type :console})
  (let [wb-cfg (get-in (config/config) [:marketplaces :wb])]
    (registry/register! :wb (wb-client/make-client wb-cfg)))
  (println "=== Analitica started ===")
  (println "Registered marketplaces:" (vec (registry/registered)))
  (println "Try (help) for available commands."))

(defn mp
  "Get marketplace client. Defaults to :wb."
  ([] (mp :wb))
  ([key] (registry/get-marketplace key)))

(defn help []
  (println "
=== ANALITICA ===

  (start!)                              -- initialize system

  SALES:
  (sales/dashboard :last-7-days)
  (sales/dashboard :last-30-days)
  (sales/dashboard {:from \"2026-03-01\" :to \"2026-03-31\"})

  FINANCE:
  (finance/report :last-30-days)
  (finance/report {:from \"2026-03-01\" :to \"2026-03-31\"})

  STOCK:
  (stock/overview)                      -- current stock levels
  (stock/risk 14)                       -- items running out in 14 days

  UNIT ECONOMICS:
  (ue/report :last-30-days)             -- profit per article
  (cost-price/load-from-csv \"data/cost-prices.csv\")
  (cost-price/set-price! \"article\" 500.0)

  ABC ANALYSIS:
  (abc/report :last-30-days)            -- by revenue (default)
  (abc/report :last-30-days :by :for-pay)

  RETURNS:
  (returns/report :last-30-days)

  ADS / FUNNEL:
  (ads/overview :last-7-days)           -- views, cart, orders, buyouts

  PRICES:
  (prices/current)                      -- all prices and discounts
  (prices/high-discount 50)             -- items with >50% discount

  PERIODS: :today :yesterday :last-7-days :last-30-days :this-week :this-month
           {:from \"2026-03-01\" :to \"2026-03-31\"}
"))
