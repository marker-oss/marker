(ns analitica.web.components.pulse.profit-forecast-test
  (:require [clojure.test :refer [deftest is testing]]
            [analitica.web.components.pulse.profit-forecast :as pf]))

(defn- text [v]
  (clojure.string/join " "
    (filter string? (tree-seq coll? seq v))))

(def ^:private data
  {:gross-profit-mtd 180000.0
   :gross-profit-target 250000.0
   :last-7d-gross-profit 50000.0
   :days-elapsed 22 :days-in-month 30
   :ad-budget-remaining 25000.0
   :romi-on-remaining 1.45})

(deftest renders-forecast
  (let [t (text (pf/render data))]
    (is (re-find #"(?iu)прогноз|forecast" t))))

(deftest shows-romi
  (let [t (text (pf/render data))]
    (is (re-find #"(?iu)ROMI|romi" t))
    (is (re-find #"1[.,]4" t))))

(deftest empty-when-no-data
  (let [t (text (pf/render {}))]
    (is (re-find #"(?iu)нет данных|—" t))))

(deftest survives-nil
  (is (vector? (pf/render nil))))
