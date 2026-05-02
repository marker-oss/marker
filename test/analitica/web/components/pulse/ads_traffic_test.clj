(ns analitica.web.components.pulse.ads-traffic-test
  (:require [clojure.test :refer [deftest is testing]]
            [analitica.web.components.pulse.ads-traffic :as at]))

(defn- text [v]
  (clojure.string/join " "
    (filter string? (tree-seq coll? seq v))))

(def ^:private data
  {:impressions 1250000
   :clicks 18500
   :ctr-pct 1.48
   :cpc-rub 12.30
   :romi 1.65
   :drr-pct 8.8})

(deftest renders-six-metrics
  (let [t (text (at/render data))]
    (is (re-find #"(?iu)показ" t))
    (is (re-find #"(?iu)клик" t))
    (is (re-find #"(?iu)ctr" t))
    (is (re-find #"(?iu)cpc" t))
    (is (re-find #"(?iu)romi" t))
    (is (re-find #"(?iu)дрр" t))))

(deftest empty-state
  (is (re-find #"(?iu)нет данных|—" (text (at/render {})))))

(deftest survives-nil
  (is (vector? (at/render nil))))
