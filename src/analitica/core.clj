(ns analitica.core
  (:require [analitica.config :as config]
            [analitica.marketplace.registry :as registry]
            [analitica.marketplace.wb.client :as wb-client]
            [analitica.marketplace.wb.impl]
            [analitica.marketplace.protocol :as proto]
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
╔══════════════════════════════════════════════════════╗
║                   ANALITICA                          ║
╠══════════════════════════════════════════════════════╣
║                                                      ║
║  (start!)                 — initialize system        ║
║  (mp)                     — get WB client            ║
║                                                      ║
║  Marketplace Protocol (use with (mp)):               ║
║  (proto/fetch-orders (mp) from to)                   ║
║  (proto/fetch-sales (mp) from to)                    ║
║  (proto/fetch-stocks (mp))                           ║
║  (proto/fetch-finance-report (mp) from to)           ║
║  (proto/fetch-product-stats (mp) from to)            ║
║  (proto/fetch-prices (mp))                           ║
║                                                      ║
║  Dates: \"2026-04-01\" or use (t/period :last-30-days) ║
║                                                      ║
╚══════════════════════════════════════════════════════╝
"))
