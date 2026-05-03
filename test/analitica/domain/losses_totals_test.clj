(ns analitica.domain.losses-totals-test
  "Bug #10: losses/calculate's :storage-eats-loss summed every :profit
   in the eat-rows seq, including the positive ones (filter allows
   profit < 500). That understates loss — and worse, mixes positive
   profits into a 'loss' figure that the UI labels as a deficit.
   Pin the negative-only semantics."
  (:require [clojure.test :refer [deftest is testing]]
            [analitica.domain.losses :as losses]))

(def ^:private loss-totals
  #'losses/loss-totals)

(deftest storage-eats-loss-sums-only-negative-profits
  (testing "Eat-rows with mixed sign: only negative profits count toward loss"
    (let [eat [{:loss-type :storage-eats-margin :profit -200.0}
               {:loss-type :storage-eats-margin :profit  300.0}
               {:loss-type :storage-eats-margin :profit -100.0}]
          t   (loss-totals [] eat [])]
      (is (== -300.0 (:storage-eats-loss t))
          "Sum: -200 + -100 = -300; the +300 row must NOT inflate the loss to +0"))))

(deftest storage-eats-loss-zero-when-all-rows-positive
  (testing "If every eat-row has profit ≥ 0 there's no loss to report"
    (let [eat [{:loss-type :storage-eats-margin :profit 100.0}
               {:loss-type :storage-eats-margin :profit 200.0}]
          t   (loss-totals [] eat [])]
      (is (zero? (:storage-eats-loss t))))))

(deftest dead-stock-loss-sums-row-profits
  (testing "Dead-stock profits are constructed as -storage-cost (always ≤ 0) so a plain sum is correct"
    (let [dead [{:loss-type :dead-stock :profit -250.0}
                {:loss-type :dead-stock :profit -500.0}]
          t    (loss-totals dead [] [])]
      (is (== -750.0 (:dead-stock-loss t))))))

(deftest total-loss-sums-only-negative-across-all-classes
  (testing "Aggregate loss filters to neg-profit rows across dead+eat+fcst"
    (let [dead [{:loss-type :dead-stock :profit -100.0}]
          eat  [{:loss-type :storage-eats-margin :profit  50.0}
                {:loss-type :storage-eats-margin :profit -75.0}]
          fcst [{:loss-type :forecast-negative   :profit  20.0}]
          t    (loss-totals dead eat fcst)]
      (is (== -175.0 (:total-loss t))
          "Sum of negatives only: -100 + -75 = -175"))))

(deftest counts-stay-with-row-counts-not-loss-amounts
  (testing "Row-count totals are unaffected by the negative-only filter"
    (let [dead [{:loss-type :dead-stock :profit -100.0}]
          eat  [{:loss-type :storage-eats-margin :profit 50.0}
                {:loss-type :storage-eats-margin :profit -75.0}]
          fcst [{:loss-type :forecast-negative :profit 20.0}]
          t    (loss-totals dead eat fcst)]
      (is (= 1 (:dead-stock-count   t)))
      (is (= 2 (:storage-eats-count t)))
      (is (= 1 (:forecast-count     t)))
      (is (= 4 (:total-sku-affected t))))))
