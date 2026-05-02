(ns analitica.web.components.pulse.sales-conversion-test
  (:require [clojure.test :refer [deftest is testing]]
            [analitica.web.components.pulse.sales-conversion :as sc]))

(defn- text [v]
  (clojure.string/join " "
    (filter string? (tree-seq coll? seq v))))

(def ^:private data
  {:orders-qty 2400 :orders-rub 1800000.0
   :avg-check  750.0 :buyout-pct 87.5
   :wow {:orders-qty 0.05  :orders-rub -0.02
         :avg-check  -0.01 :buyout-pct 0.003}})

(deftest renders-four-metrics
  (let [t (text (sc/render data))]
    (is (re-find #"Заказы" t))
    (is (re-find #"Ср.*чек" t))
    (is (re-find #"выкуп" t))))

(deftest renders-wow-deltas
  (let [t (text (sc/render data))]
    (is (re-find #"%" t))))

(deftest empty-state-when-no-data
  (let [t (text (sc/render {}))]
    (is (re-find #"(?iu)нет данных|—" t))))

(deftest survives-nil
  (is (vector? (sc/render nil))))
