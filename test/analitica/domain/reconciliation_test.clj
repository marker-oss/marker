(ns analitica.domain.reconciliation-test
  "Tests for the pure reconcile fn (FR-P4.6).
   No DB required — all inputs are injected."
  (:require [clojure.test :refer [deftest is testing]]
            [analitica.domain.reconciliation :as recon]))

;; ---------------------------------------------------------------------------
;; Pure reconcile — canonical test from brief
;; ---------------------------------------------------------------------------

(deftest pnl-vs-payout-delta
  (testing "delta = payout - pnl, per-article rolls up to totals"
    (let [recon (recon/reconcile
                  {:pnl-by-article    {"A" 1000.0 "B" 500.0}
                   :payout-by-article {"A" 990.0  "B" 500.0}})]
      (is (== 1500.0 (:pnl-total recon)))
      (is (== 1490.0 (:payout-total recon)))
      (is (== -10.0  (:delta recon)))
      (is (= -10.0 (:delta (first (filter #(= "A" (:article %)) (:per-article recon)))))))))

(deftest article-present-in-payout-only
  (testing "Articles present only in payout-by-article get pnl=0.0"
    (let [recon (recon/reconcile
                  {:pnl-by-article    {"A" 100.0}
                   :payout-by-article {"A" 90.0 "B" 50.0}})]
      (is (== 100.0 (:pnl-total recon)))
      (is (== 140.0 (:payout-total recon)))
      (is (== 40.0  (:delta recon)))
      (let [b-row (first (filter #(= "B" (:article %)) (:per-article recon)))]
        (is (some? b-row))
        (is (== 0.0   (:pnl b-row)))
        (is (== 50.0  (:payout b-row)))
        (is (== 50.0  (:delta b-row)))))))

(deftest article-present-in-pnl-only
  (testing "Articles present only in pnl-by-article get payout=0.0"
    (let [recon (recon/reconcile
                  {:pnl-by-article    {"A" 100.0 "C" 200.0}
                   :payout-by-article {"A" 90.0}})]
      (is (== 300.0  (:pnl-total recon)))
      (is (== 90.0   (:payout-total recon)))
      (is (== -210.0 (:delta recon)))
      (let [c-row (first (filter #(= "C" (:article %)) (:per-article recon)))]
        (is (some? c-row))
        (is (== 200.0  (:pnl c-row)))
        (is (== 0.0    (:payout c-row)))
        (is (== -200.0 (:delta c-row)))))))

(deftest empty-maps-produce-zero-totals
  (testing "Both maps empty → all zeros, no per-article rows"
    (let [recon (recon/reconcile {:pnl-by-article {} :payout-by-article {}})]
      (is (== 0.0 (:pnl-total recon)))
      (is (== 0.0 (:payout-total recon)))
      (is (== 0.0 (:delta recon)))
      (is (empty? (:per-article recon))))))

(deftest per-article-vector-contains-all-articles
  (testing "Union of both maps appears in :per-article"
    (let [recon (recon/reconcile
                  {:pnl-by-article    {"A" 10.0 "B" 20.0}
                   :payout-by-article {"B" 18.0 "C" 5.0}})
          arts  (set (map :article (:per-article recon)))]
      (is (= #{"A" "B" "C"} arts)))))
