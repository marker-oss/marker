(ns marker.pages.reports-test
  "Tests for the report KPI tile-selection helper (LT1).
   `kpi-tiles-shown` decides which schema :kpi specs get a tile, given the
   backend :totals map — it must be honest: no tile without a real number."
  (:require [cljs.test :refer [deftest is testing]]
            [marker.pages.reports :refer [kpi-tiles-shown]]))

(def ^:private kpi
  [{:key :total-revenue :title "Выручка"        :format :rub}
   {:key :total-sales   :title "Продажи"         :format :int}
   {:key :profit-current :title "Прибыль сейчас" :format :rub}])

(deftest tile-shown-when-key-present
  (testing "kpi key present in totals → tile shown"
    (let [shown (kpi-tiles-shown kpi {:total-revenue 1000.0 :total-sales 42})]
      (is (= [:total-revenue :total-sales] (mapv :key shown))))))

(deftest tile-hidden-when-key-absent
  (testing "kpi key absent from totals → no tile"
    (let [shown (kpi-tiles-shown kpi {:total-revenue 1000.0})]
      (is (= [:total-revenue] (mapv :key shown))))))

(deftest tile-hidden-when-value-nil
  (testing "kpi key present but nil-valued → no tile (honest)"
    (let [shown (kpi-tiles-shown kpi {:total-revenue 1000.0
                                      :total-sales   nil
                                      :profit-current nil})]
      (is (= [:total-revenue] (mapv :key shown))))))

(deftest no-tiles-on-empty-totals
  (testing "empty totals → no tiles"
    (is (empty? (kpi-tiles-shown kpi {})))))

(deftest zero-value-is-shown
  (testing "a real 0 is a backing number → tile shown (some? 0 is true)"
    (let [shown (kpi-tiles-shown kpi {:total-revenue 0.0 :total-sales 0})]
      (is (= [:total-revenue :total-sales] (mapv :key shown))))))

(deftest caps-at-six
  (testing "no more than 6 tiles even when totals has more matching keys"
    (let [big-kpi (mapv (fn [i] {:key (keyword (str "k" i)) :title (str i) :format :int})
                        (range 10))
          totals  (into {} (map (fn [i] [(keyword (str "k" i)) i]) (range 10)))
          shown   (kpi-tiles-shown big-kpi totals)]
      (is (= 6 (count shown))))))
