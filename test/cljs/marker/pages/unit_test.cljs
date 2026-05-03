(ns marker.pages.unit-test
  "Tests for the unit-economics computation helper."
  (:require [cljs.test :refer [deftest is testing]]
            [marker.pages.unit :refer [compute-unit-econ]]))

;; ---------------------------------------------------------------------------
;; Baseline smoke test
;; ---------------------------------------------------------------------------

(def ^:private baseline
  {:price 2500 :cogs 1200 :commission 17 :logistics 90 :returns 8 :ads 220})

(deftest baseline-calculation
  (testing "baseline produces expected values"
    (let [{:keys [profit margin roas total-cost]} (compute-unit-econ baseline)]
      ;; commission = 2500 * 0.17 = 425
      ;; returns    = 2500 * 0.08 = 200
      ;; total-cost = 1200 + 425 + 90 + 200 + 220 = 2135
      (is (= 2135.0 total-cost) "total cost")
      ;; profit = 2500 - 2135 = 365
      (is (= 365.0 profit) "profit")
      ;; margin = 365/2500 * 100 = 14.6
      (is (< (js/Math.abs (- margin 14.6)) 0.01) "margin ≈ 14.6%")
      ;; roas = 2500 / 220 ≈ 11.36
      (is (> roas 11) "ROAS > 11")
      (is (< roas 12) "ROAS < 12"))))

;; ---------------------------------------------------------------------------
;; Zero price guard
;; ---------------------------------------------------------------------------

(deftest zero-price-guard
  (testing "margin is 0 (not NaN/Infinity) when price is 0"
    (let [{:keys [margin profit roas]} (compute-unit-econ {:price 0 :cogs 0 :commission 0
                                                            :logistics 0 :returns 0 :ads 0})]
      (is (= 0 margin) "margin is 0 for zero price")
      (is (= 0.0 profit) "profit is 0")
      (is (= 0 roas) "roas is 0 when ads is also 0")))

  (testing "roas is 0 when ads is 0"
    (let [{:keys [roas]} (compute-unit-econ (assoc baseline :ads 0))]
      (is (= 0 roas)))))

;; ---------------------------------------------------------------------------
;; Negative profit (loss scenario)
;; ---------------------------------------------------------------------------

(deftest loss-scenario
  (testing "high costs → negative profit and negative margin"
    (let [params {:price 1000 :cogs 800 :commission 20 :logistics 150 :returns 10 :ads 300}
          {:keys [profit margin]} (compute-unit-econ params)]
      ;; commission = 200, returns = 100
      ;; total = 800 + 200 + 150 + 100 + 300 = 1550
      ;; profit = 1000 - 1550 = -550
      (is (neg? profit) "profit is negative")
      (is (neg? margin) "margin is negative")))

  (testing "profit formula is consistent with margin"
    (let [params {:price 3000 :cogs 1000 :commission 15 :logistics 80 :returns 5 :ads 100}
          {:keys [profit margin]} (compute-unit-econ params)]
      (is (pos? profit))
      (is (pos? margin))
      ;; margin = profit / price * 100
      (is (< (js/Math.abs (- margin (* (/ profit (:price params)) 100))) 0.001)
          "margin consistent with profit/price"))))

;; ---------------------------------------------------------------------------
;; Break-even semantics
;; ---------------------------------------------------------------------------

(deftest break-even-profitable
  (let [{:keys [break-even]} (compute-unit-econ baseline)]
    (is (zero? break-even) ":break-even is 0 when baseline is profitable")))

(deftest break-even-loss
  (let [{:keys [break-even]} (compute-unit-econ (assoc baseline :price 100))]
    (is (= js/Infinity break-even) ":break-even is Infinity when unprofitable")))

;; ---------------------------------------------------------------------------
;; Slider extremes
;; ---------------------------------------------------------------------------

(deftest slider-extremes
  (testing "max commission (30%) still produces a number"
    (let [{:keys [margin]} (compute-unit-econ (assoc baseline :commission 30))]
      (is (number? margin))
      (is (js/isFinite margin))))

  (testing "max returns (25%) still produces a number"
    (let [{:keys [margin]} (compute-unit-econ (assoc baseline :returns 25))]
      (is (number? margin))
      (is (js/isFinite margin))))

  (testing "max ads (600) still produces a number"
    (let [{:keys [roas]} (compute-unit-econ (assoc baseline :ads 600))]
      (is (number? roas))
      (is (pos? roas)))))
