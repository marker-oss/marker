(ns analitica.web.components.pulse.margin-roi-test
  (:require [clojure.test :refer [deftest is testing]]
            [analitica.web.components.pulse.margin-roi :as mr]))

(defn- text [v]
  (clojure.string/join " "
    (filter string? (tree-seq coll? seq v))))

(def ^:private data
  {:gross-profit 180000.0
   :margin-pct 28.5
   :roi-pct 31.2
   :commission-pct 17.4
   :logistics-rub 12500.0})

(deftest renders-five-metrics
  (let [t (text (mr/render data))]
    (is (re-find #"(?iu)маржа" t))
    (is (re-find #"(?iu)roi" t))
    (is (re-find #"(?iu)комиссия" t))
    (is (re-find #"(?iu)логистик" t))))

(deftest empty-state
  (is (re-find #"(?iu)нет данных|—" (text (mr/render {})))))

(deftest survives-nil
  (is (vector? (mr/render nil))))
