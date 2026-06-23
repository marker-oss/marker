(ns analitica.config
  (:require [aero.core :as aero]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(defonce ^:private state (atom nil))
(defonce ^:private last-path (atom "config.edn"))

(defn- ->path
  "Turn a dotted setting key into an assoc-in path into the config tree.
   First segment selects the subtree; remaining segments are keywordized.
   Special-case: mp.<mp>.<field> → [:marketplaces <mp> <field>]."
  [dotted-key]
  (let [segs (str/split dotted-key #"\.")]
    (case (first segs)
      "mp"       (into [:marketplaces] (map keyword (rest segs)))
      "business" (into [:business]     (map keyword (rest segs)))
      "sync"     (into [:sync]         (map keyword (rest segs)))
      "notify"   (into [:notify]       (map keyword (rest segs)))
      ;; default: keywordize every segment
      (mapv keyword segs))))

(defn- apply-overrides
  "Overlay flat {dotted-key value} settings onto the aero base config."
  [base overrides]
  (reduce-kv (fn [cfg k v] (assoc-in cfg (->path k) v))
             base
             overrides))

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
  ([] (load-config @last-path))
  ([path]
   (reset! last-path path)
   (load-env-file! ".env")
   (let [f (io/file path)]
     (when-not (.exists f)
       (throw (ex-info (str "Config file not found: " path
                            ". Copy config.example.edn to config.edn and fill in your API keys.")
                       {:path path})))
     (let [base      (aero/read-config f)
           overrides (let [db-initialized? (try ((requiring-resolve 'analitica.db/initialized?))
                                                (catch Throwable _ false))]
                       (if db-initialized?
                         ;; DB is up: call overrides and let real errors propagate
                         (try ((requiring-resolve 'analitica.settings/overrides))
                              (catch Throwable e
                                (println "WARNING: failed to load DB setting overrides:" (.getMessage e))
                                (throw e)))
                         ;; DB not yet initialized: silently return no overrides
                         {}))
           cfg       (apply-overrides base overrides)]
       (reset! state cfg)
       cfg))))

(defn reload!
  "Re-read config.edn + DB overrides and swap the config atom. Returns the
   new config. Callers that also cache derived state (e.g. marketplace
   clients) must re-init after this — see analitica.core/reload-config!."
  []
  (load-config @last-path))

(defn config
  "Returns current config. Call (load-config) first."
  []
  (or @state
      (throw (ex-info "Config not loaded. Call (analitica.config/load-config) first." {}))))

(defn wb-token []
  (get-in (config) [:marketplaces :wb :api-token]))

(defn api-key
  "Static API key for X-API-Key auth on mutating routes. nil when unconfigured."
  []
  (get-in (config) [:api-key]))

(defn cors-origins
  "Allowed CORS origins. Defaults to the pilot host + localhost when unset."
  []
  (or (get-in (config) [:cors-origins])
      ["https://marker.shegida.ru" "http://localhost:3000"]))

(defn wb-rate-limits []
  (get-in (config) [:marketplaces :wb :rate-limits]))

(defn ozon-config []
  (get-in (config) [:marketplaces :ozon]))

(defn ym-config []
  (get-in (config) [:marketplaces :ym]))

(def ^:private default-audit-tolerance
  {:rel 0.01 :abs 10.0})

(defn audit-tolerance
  "Tolerance thresholds for reconciliation discrepancy classification.
   Falls back to defaults when :audit/:tolerance is absent from config
   (so a stale config.edn does not break the audit CLI)."
  []
  (merge default-audit-tolerance
         (get-in (config) [:audit :tolerance])))
