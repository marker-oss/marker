(ns analitica.config
  (:require [aero.core :as aero]
            [clojure.java.io :as io]))

(defonce ^:private state (atom nil))

(defn load-config
  ([] (load-config "config.edn"))
  ([path]
   (let [f (io/file path)]
     (when-not (.exists f)
       (throw (ex-info (str "Config file not found: " path
                            ". Copy config.example.edn to config.edn and fill in your API keys.")
                       {:path path})))
     (let [cfg (aero/read-config f)]
       (reset! state cfg)
       cfg))))

(defn config
  "Returns current config. Call (load-config) first."
  []
  (or @state
      (throw (ex-info "Config not loaded. Call (analitica.config/load-config) first." {}))))

(defn wb-token []
  (get-in (config) [:marketplaces :wb :api-token]))

(defn wb-rate-limits []
  (get-in (config) [:marketplaces :wb :rate-limits]))
