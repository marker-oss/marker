(ns analitica.domain.cost-price
  (:require [clojure.java.io :as io]
            [clojure.edn :as edn]))

(defonce ^:private cost-prices (atom {}))

(defn load-from-edn
  "Load cost prices from EDN file.
   Expected format: {\"article-1\" 500.0, \"article-2\" 300.0}"
  [path]
  (let [f (io/file path)]
    (if (.exists f)
      (do
        (reset! cost-prices (edn/read-string (slurp f)))
        (println "Loaded" (count @cost-prices) "cost prices from" path))
      (println "Cost price file not found:" path))))

(defn load-from-csv
  "Load cost prices from CSV file.
   Expected format: article,cost_price (with header row)"
  [path]
  (let [f (io/file path)]
    (if (.exists f)
      (let [lines (rest (clojure.string/split-lines (slurp f)))
            data  (->> lines
                       (map #(clojure.string/split % #"[,;\t]"))
                       (filter #(>= (count %) 2))
                       (map (fn [[art price]] [(clojure.string/trim art)
                                               (Double/parseDouble (clojure.string/trim price))]))
                       (into {}))]
        (reset! cost-prices data)
        (println "Loaded" (count data) "cost prices from" path))
      (println "Cost price file not found:" path))))

(defn set-price!
  "Manually set cost price for an article."
  [article price]
  (swap! cost-prices assoc article price))

(defn get-price
  "Get cost price for an article. Returns nil if not set."
  [article]
  (get @cost-prices article))

(defn all-prices []
  @cost-prices)
