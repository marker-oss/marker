(ns analitica.web.components.pulse.plan-fact-test
  (:require [clojure.test :refer [deftest is testing]]
            [analitica.web.components.pulse.plan-fact :as pf]))

(defn- text [v]
  (clojure.string/join " "
    (filter string? (tree-seq coll? seq v))))

(def ^:private full-data
  {:period-month   "2026-05"
   :marketplace    :wb
   :days-elapsed   22
   :days-in-month  30
   :targets        [{:metric :revenue :target 500000.0
                     :actual-mtd 312000.0 :last-7d 70000.0}
                    {:metric :orders  :target 2500.0
                     :actual-mtd 1800.0  :last-7d 400.0}]})

(deftest renders-card-per-target
  (let [v (pf/render full-data)
        t (text v)]
    (is (re-find #"Выручка" t))
    (is (re-find #"500" t) "target visible")
    (is (re-find #"312" t) "MTD visible")))

(deftest shows-no-plan-callout-when-targets-empty
  (let [v (pf/render (assoc full-data :targets []))
        t (text v)]
    (is (re-find #"План.*не задан" t))
    (is (re-find #"/plan" (pr-str v)))))

(deftest survives-nil-input
  (is (vector? (pf/render nil))))

(deftest renders-pace-multiplier
  (let [v (pf/render (assoc full-data :targets
                       [{:metric :revenue :target 1000000.0
                         :actual-mtd 100000.0 :last-7d 70000.0}]))]
    (is (re-find #"×|x" (text v)))))
