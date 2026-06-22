(ns analitica.settings.validate
  "Probe MP credentials by building a throwaway client and making one cheap
   authed call (fetch-stocks). Used before persisting secrets so the operator
   gets a green/red verdict. Never throws — always returns {:valid? :detail}."
  (:require [analitica.marketplace.protocol :as proto]
            [analitica.marketplace.wb.client :as wb]
            [analitica.marketplace.ozon.client :as ozon]
            [analitica.marketplace.ym.client :as ym]
            ;; impl namespaces extend-type the protocol onto the client records:
            [analitica.marketplace.wb.impl]
            [analitica.marketplace.ozon.impl]
            [analitica.marketplace.ym.impl]))

(defn- build-client [mp cfg]
  (case mp
    :wb   (wb/make-client cfg)
    :ozon (ozon/make-client cfg)
    :ym   (ym/make-client cfg)
    (throw (ex-info (str "Unknown marketplace: " mp) {:mp mp}))))

(defn validate-credentials
  "Build a client from cfg and probe with fetch-stocks (authed). Returns
   {:valid? boolean :detail string}. Never throws."
  [mp cfg]
  (try
    (let [client (build-client mp cfg)]
      (proto/fetch-stocks client)
      {:valid? true :detail "OK"})
    (catch clojure.lang.ExceptionInfo e
      (if (= :unauthorized (:type (ex-data e)))
        {:valid? false :detail "Неверный токен (401 Unauthorized)"}
        {:valid? false :detail (or (.getMessage e) "Ошибка проверки")}))
    (catch Throwable e
      {:valid? false :detail (or (.getMessage e) "Ошибка проверки")})))
