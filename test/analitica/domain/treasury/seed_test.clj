(ns analitica.domain.treasury.seed-test
  "T029/T032 — seed from cash_flow_periods (FR-025, R5, DEC-3).
   Money comparisons are kopeck-exact on decimal-strings, never ε."
  (:require [clojure.test :refer [deftest testing is]]
            [analitica.util.math :as m]
            [analitica.domain.treasury.seed :as seed]))

(def ^:private ctx
  {:from "2026-04-01" :to "2026-04-30"
   :mp-account-id 1 :bank-account-id 2 :counterparty-id 3})

(deftest slice-amount-full-containment
  (testing "bucket fully inside window → full amount"
    (is (= "100.01" (m/d->str (seed/slice-amount (m/d "100.01")
                                                 "2026-04-06" "2026-04-12"
                                                 "2026-04-01" "2026-04-30"))))))

(deftest slice-amount-telescopes-to-kopeck   ;; DEC-3
  (testing "Apr 28..May 4 bucket (3d apr + 4d may): slices sum EXACTLY"
    (let [amt (m/d "100.01")
          apr (seed/slice-amount amt "2026-04-28" "2026-05-04" "2026-04-01" "2026-04-30")
          may (seed/slice-amount amt "2026-04-28" "2026-05-04" "2026-05-01" "2026-05-31")]
      (is (= "42.86" (m/d->str apr)))          ;; d-prorate(100.01, 3, 7)
      (is (= "57.15" (m/d->str may)))          ;; 100.01 − 42.86 (remainder absorbed)
      (is (= "100.01" (m/d->str (m/d+ apr may)))))))

(deftest bucket->ops-shapes
  (let [row {:id 7 :source "ozon"
             :period_begin "2026-04-06" :period_end "2026-04-12"
             :orders_amount 1000.50 :commission_amount -250.10
             :payment -700.00 :storage 0.0}
        ops (seed/bucket->ops row ctx)
        by-dir (group-by :direction ops)]
    (testing "sign → direction, abs amount, category from column map"
      (let [inc-op (first (:income by-dir))]
        (is (= "1000.50" (:amount inc-op)))
        (is (= "mp-payout" (:category inc-op)))
        (is (= :seed (:category-source inc-op)))
        (is (= 3 (:counterparty-id inc-op))))
      (let [exp-op (first (:expense by-dir))]
        (is (= "250.10" (:amount exp-op)))
        (is (= "services" (:category exp-op)))))
    (testing "payment → transfer MP→bank, no category"
      (let [tr (first (:transfer by-dir))]
        (is (= "700.00" (:amount tr)))
        (is (= 1 (:account-id tr)))
        (is (= 2 (:transfer-account-id tr)))
        (is (nil? (:category tr)))))
    (testing "zero columns skipped; common fields"
      (is (= 3 (count ops)))                                   ;; storage 0.0 skipped
      (is (every? #(= "seed:cash_flow_periods" (:source %)) ops))
      (is (every? #(= "2026-04-12" (:op-date %)) ops))         ;; bucket end inside window
      (is (every? :confirmed ops)))))

(deftest bucket->ops-clamps-op-date
  (testing "bucket end past window → op-date clamped to :to"
    (let [row {:id 8 :source "ozon" :period_begin "2026-04-28"
               :period_end "2026-05-04" :orders_amount 100.0}
          ops (seed/bucket->ops row ctx)]
      (is (= "2026-04-30" (:op-date (first ops)))))))
