(ns analitica.config
  (:require [aero.core :as aero]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(defonce ^:private state (atom nil))

(defn- load-env-file!
  "Reads .env file and sets entries as system properties
   so that aero's #env tag can resolve them."
  [path]
  (let [f (io/file path)]
    (when (.exists f)
      (doseq [line (str/split-lines (slurp f))
              :let [line (str/trim line)]
              :when (and (seq line)
                         (not (str/starts-with? line "#"))
                         (str/includes? line "="))
              :let [[k v] (str/split line #"=" 2)]]
        (System/setProperty (str/trim k) (str/trim v))))))

(defn load-config
  ([] (load-config "config.edn"))
  ([path]
   (load-env-file! ".env")
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
