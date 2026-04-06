(ns analitica.marketplace.registry)

(defonce ^:private registry (atom {}))

(defn register! [marketplace-key impl]
  (swap! registry assoc marketplace-key impl))

(defn get-marketplace [marketplace-key]
  (or (get @registry marketplace-key)
      (throw (ex-info (str "Marketplace not registered: " marketplace-key
                           ". Available: " (keys @registry))
                      {:key marketplace-key}))))

(defn registered []
  (keys @registry))
