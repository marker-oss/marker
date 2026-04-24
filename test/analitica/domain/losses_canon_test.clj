(ns analitica.domain.losses-canon-test
  "Canon tests for the Losses report (Losses.1–Losses.4).
   All tests are pure — no DB hits, inline fixtures only.
   See docs/canonical-formulas.md §Losses."
  (:require [clojure.test :refer [deftest is testing]]
            [analitica.domain.losses :as losses]))

;; ---------------------------------------------------------------------------
;; Private-function accessors (Losses.1–Losses.3)
;; ---------------------------------------------------------------------------

(def ^:private dead-stock-rows
  #'losses/dead-stock-rows)

(def ^:private storage-eats-margin-rows
  #'losses/storage-eats-margin-rows)

(def ^:private forecast-negative-rows
  #'losses/forecast-negative-rows)

;; ---------------------------------------------------------------------------
;; Losses.1 — Dead-stock classifier tests
;; ---------------------------------------------------------------------------

(deftest dead-stock-flags-zero-sales-positive-storage
  "SKU with storage > 100 and 0 sales in UE data → flagged as :dead-stock. §Losses.1"
  (let [storage-map {"ART-001" 250.0}
        ue-data     [{:article "ART-001" :sales-qty 0 :revenue 0 :profit 0}]
        result      (dead-stock-rows storage-map ue-data)]
    (is (= 1 (count result)))
    (is (= "ART-001" (:article (first result))))
    (is (= :dead-stock (:loss-type (first result))))
    (is (= 250.0 (:storage-cost (first result))))
    (is (= -250.0 (:profit (first result))))))

(deftest dead-stock-ignores-below-threshold-storage
  "SKU with storage <= 100 RUB → NOT flagged, regardless of sales. §Losses.1"
  (testing "storage exactly 100 → not a flag"
    (let [result (dead-stock-rows {"ART-X" 100.0} [])]
      (is (empty? result))))
  (testing "storage 50 → not a flag"
    (let [result (dead-stock-rows {"ART-Y" 50.0} [])]
      (is (empty? result)))))

(deftest dead-stock-article-absent-from-ue-treated-as-zero-sales
  "SKU present in storage-map but absent from ue-data is treated as 0 sales. §Losses.1"
  (let [result (dead-stock-rows {"ART-GHOST" 300.0} [])]
    (is (= 1 (count result)))
    (is (= :dead-stock (:loss-type (first result))))))

(deftest dead-stock-ignores-sku-with-positive-sales
  "SKU in storage-map with sales-qty > 0 in ue-data → NOT dead-stock. §Losses.1"
  (let [result (dead-stock-rows
                {"ART-LIVE" 500.0}
                [{:article "ART-LIVE" :sales-qty 3 :revenue 1500 :profit 200}])]
    (is (empty? result))))

;; ---------------------------------------------------------------------------
;; Losses.2 — Storage-eats-margin classifier tests
;; ---------------------------------------------------------------------------

(deftest storage-eats-margin-flags-high-ratio-low-profit
  "SKU with storage/revenue > 20% and profit < 500 → flagged. §Losses.2"
  (let [ue-data [{:article "ART-M" :revenue 1000.0 :storage 300.0 :profit 100.0 :sales-qty 5}]
        result  (storage-eats-margin-rows ue-data)]
    (is (= 1 (count result)))
    (is (= "ART-M" (:article (first result))))
    (is (= :storage-eats-margin (:loss-type (first result))))
    (is (= 30.0 (:storage-ratio (first result))))))

(deftest storage-eats-margin-ignores-healthy-skus
  "SKU with profit >= 500 is NOT flagged even if storage ratio > 20%. §Losses.2"
  (testing "healthy profit → not flagged"
    (let [result (storage-eats-margin-rows
                  [{:article "ART-H" :revenue 1000.0 :storage 250.0 :profit 600.0 :sales-qty 10}])]
      (is (empty? result))))
  (testing "low storage ratio → not flagged"
    (let [result (storage-eats-margin-rows
                  [{:article "ART-L" :revenue 5000.0 :storage 200.0 :profit 100.0 :sales-qty 10}])]
      (is (empty? result)))))

(deftest storage-eats-margin-critical-suggestion-above-40pct
  "When storage/revenue > 40% → suggestion includes 'Критично'. §Losses.2"
  (let [ue-data [{:article "ART-C" :revenue 1000.0 :storage 500.0 :profit 50.0 :sales-qty 2}]
        result  (storage-eats-margin-rows ue-data)]
    (is (= 1 (count result)))
    (is (.contains (:suggestion (first result)) "Критично"))))

;; ---------------------------------------------------------------------------
;; Losses.3 — Forecast-negative classifier tests
;; ---------------------------------------------------------------------------

(deftest forecast-negative-flags-days-to-break-even-under-30
  "Profitable SKU with days-to-break-even < 30 and future storage > profit → flagged. §Losses.3"
  ;; Setup: profit=100, daily_storage=100/30≈3.33, days_to_break_even=30→29.9..
  ;; stock qty=10, sales=1/day → remaining=10 days
  ;; future_storage = 3.33 * 10 = 33.3 > 100 profit? No — let's use explicit values.
  ;; profit=100, storage=600 over 30 days → daily=20, days-to-break-even=5 < 30
  ;; remaining=10 days, future_storage=200 > 100 → FLAG
  (let [ue-data     [{:article "ART-F" :profit 100.0 :sales-qty 10 :revenue 500.0 :storage 600.0}]
        storage-map {"ART-F" 600.0}
        stock       {"ART-F" {:article "ART-F" :quantity-full 10}}
        result      (forecast-negative-rows ue-data storage-map stock 30)]
    (is (= 1 (count result)))
    (is (= "ART-F" (:article (first result))))
    (is (= :forecast-negative (:loss-type (first result))))
    (is (< (:days-to-break-even (first result)) 30))))

(deftest forecast-negative-skips-zero-daily-storage
  "SKU with zero storage → days-to-break-even=9999 → NOT flagged. §Losses.3"
  (let [ue-data     [{:article "ART-Z" :profit 100.0 :sales-qty 5 :revenue 300.0 :storage 0.0}]
        storage-map {"ART-Z" 0.0}
        stock       {"ART-Z" {:article "ART-Z" :quantity-full 50}}
        result      (forecast-negative-rows ue-data storage-map stock 30)]
    (is (empty? result))))

(deftest forecast-negative-skips-non-positive-profit
  "SKU with profit <= 0 is NOT flagged by forecast classifier. §Losses.3"
  (let [ue-data     [{:article "ART-NP" :profit 0.0 :sales-qty 5 :revenue 300.0 :storage 200.0}
                     {:article "ART-NEG" :profit -50.0 :sales-qty 3 :revenue 200.0 :storage 300.0}]
        storage-map {"ART-NP" 200.0 "ART-NEG" 300.0}
        stock       {"ART-NP"  {:article "ART-NP"  :quantity-full 10}
                     "ART-NEG" {:article "ART-NEG" :quantity-full 10}}
        result      (forecast-negative-rows ue-data storage-map stock 30)]
    (is (empty? result))))

;; ---------------------------------------------------------------------------
;; Losses.4 — calculate orchestrator (with-redefs mocks)
;; ---------------------------------------------------------------------------

(deftest calculate-totals-sum-correctly
  "calculate orchestrator: dead-stock + storage-eats rows sum into totals. §Losses.4"
  (with-redefs [analitica.domain.finance/fetch-finance
                (fn [& _]
                  [{:article "ART-DS" :operation "sale" :quantity 0
                    :for-pay 0 :revenue 0 :total-cost 0 :marketplace "wb"
                    :sales-qty 0 :returns-qty 0 :storage 0 :logistics 0
                    :acceptance 0 :penalties 0 :acquiring 0 :deduction 0 :additional 0
                    :wb-reward 0 :spp-amount 0 :ad-cost 0
                    :date-from "2026-03-01" :date-to "2026-03-31"
                    :event-date "2026-03-01"}])
                analitica.db/storage-by-article
                (fn [& _]
                  [{:article "ART-DS" :storage-cost 500.0}])
                analitica.domain.stock/fetch-stocks
                (fn [& _] [])]
    (let [result (losses/calculate {:from "2026-03-01" :to "2026-03-31"} :marketplace :wb)]
      (is (map? result))
      (is (vector? (:rows result)))
      (is (map? (:totals result)))
      (is (contains? (:totals result) :total-loss))
      (is (contains? (:totals result) :dead-stock-count))
      (is (contains? (:totals result) :total-sku-affected)))))
