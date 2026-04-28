(ns analitica.audit.rules-test
  "Tests for analitica.audit.rules — registry, classify, and (later) individual
   reconciliation rules."
  (:require [clojure.test :refer [deftest testing is]]
            [analitica.audit.rules :as r]))

;; ---------------------------------------------------------------------------
;; T009 — classify (core tolerance logic from research.md §R5)
;; ---------------------------------------------------------------------------
;;
;; Rule (permissive OR):
;;   :expected when |Δabs| ≤ abs-threshold OR |Δrel| ≤ rel-threshold
;;   :suspicious otherwise
;; Threshold defaults from tasks.md: rel=0.01 (1%), abs=10.0 (₽).

(def ^:private default-t {:rel 0.01 :abs 10.0})

(deftest classify-absolute-wins
  (testing "small absolute delta → :expected via abs threshold"
    (is (= :expected (r/classify 5.0  0.5   default-t))
        "5₽ out of some large base is a huge relative but tiny absolute → expected")
    (is (= :expected (r/classify 10.0 0.99  default-t))
        "exactly on abs threshold → expected (<=)")
    (is (= :expected (r/classify 0    0     default-t))
        "zero delta → expected")))

(deftest classify-relative-wins
  (testing "small relative delta → :expected via rel threshold"
    (is (= :expected (r/classify 200.0 0.005 default-t))
        "200₽ but only 0.5% of 40k → expected via rel")
    (is (= :expected (r/classify 50.0  0.01  default-t))
        "exactly on rel threshold → expected (<=)")))

(deftest classify-both-above-threshold-is-suspicious
  (testing "both absolute and relative above tolerance → :suspicious"
    (is (= :suspicious (r/classify 50.0  0.05  default-t))
        "50₽ AND 5% → neither threshold met")
    (is (= :suspicious (r/classify 10.01 0.0101 default-t))
        "just above both → suspicious")))

(deftest classify-rounds-boundary-behaviour
  (testing "threshold boundaries are inclusive (<=)"
    (is (= :expected (r/classify 10.0 0.02 default-t)) "abs == threshold")
    (is (= :suspicious (r/classify 10.001 0.02 default-t))
        "abs just over AND rel over → suspicious")
    (is (= :expected (r/classify 11.0 0.01 default-t))
        "rel == threshold (0.01) wins even though abs is over")))

(deftest classify-handles-negative-signs
  (testing "classify uses Math/abs internally — negatives classified same as positives"
    (is (= :expected   (r/classify -5.0  -0.005 default-t)))
    (is (= :suspicious (r/classify -50.0 -0.05  default-t)))))

(deftest classify-requires-numeric-deltas
  (testing "non-numeric inputs throw ex-info"
    (is (thrown? clojure.lang.ExceptionInfo
                 (r/classify "5"  0.01 default-t)))
    (is (thrown? clojure.lang.ExceptionInfo
                 (r/classify 5     nil default-t)))))

(deftest classify-requires-valid-tolerance
  (testing "tolerance must contain :abs and :rel"
    (is (thrown? clojure.lang.ExceptionInfo
                 (r/classify 5 0.01 {:rel 0.01})))
    (is (thrown? clojure.lang.ExceptionInfo
                 (r/classify 5 0.01 {:abs 10.0})))))

(deftest classify-unclassified-is-separate
  (testing "classify-unclassified always returns :unclassified (distinct path)"
    (is (= :unclassified (r/classify-unclassified)))))

;; ---------------------------------------------------------------------------
;; Registry basics
;; ---------------------------------------------------------------------------

(deftest register-and-lookup
  (r/clear-registry!)
  (r/register-rule! {:rule/id          :demo-a
                     :rule/description "A demo"
                     :rule/marketplace :all
                     :rule/sources     [:raw-finance]
                     :rule/severity    :informational
                     :rule/fn          (fn [_ctx] [])})
  (r/register-rule! {:rule/id          :demo-b
                     :rule/marketplace #{:wb}
                     :rule/sources     [:sales]
                     :rule/fn          (fn [_ctx] [])})
  (is (= 2 (count (r/all-rules))))
  (is (= [:demo-a :demo-b] (mapv :rule/id (r/all-rules)))
      "all-rules is sorted by :rule/id for deterministic ordering")
  (is (= :demo-a (:rule/id (r/get-rule :demo-a))))
  (is (nil? (r/get-rule :non-existent)))
  (r/clear-registry!))

(deftest marketplace-filter
  (r/clear-registry!)
  (r/register-rule! {:rule/id :all-rule      :rule/marketplace :all      :rule/fn (fn [_] [])})
  (r/register-rule! {:rule/id :wb-only       :rule/marketplace #{:wb}    :rule/fn (fn [_] [])})
  (r/register-rule! {:rule/id :ozon-only     :rule/marketplace #{:ozon}  :rule/fn (fn [_] [])})
  (r/register-rule! {:rule/id :wb-and-ozon   :rule/marketplace #{:wb :ozon} :rule/fn (fn [_] [])})
  (is (= #{:all-rule :wb-only :wb-and-ozon}
         (set (map :rule/id (r/rules-for-marketplace :wb)))))
  (is (= #{:all-rule :ozon-only :wb-and-ozon}
         (set (map :rule/id (r/rules-for-marketplace :ozon)))))
  (is (= #{:all-rule}
         (set (map :rule/id (r/rules-for-marketplace :ym)))))
  (r/clear-registry!))

(deftest register-rule-requires-id
  (is (thrown? clojure.lang.ExceptionInfo
               (r/register-rule! {:rule/description "no id"}))))

(deftest register-rule-replaces-existing
  (r/clear-registry!)
  (r/register-rule! {:rule/id :x :rule/description "v1" :rule/fn (fn [_] [])})
  (r/register-rule! {:rule/id :x :rule/description "v2" :rule/fn (fn [_] [])})
  (is (= 1 (count (r/all-rules))))
  (is (= "v2" (:rule/description (r/get-rule :x))))
  (r/clear-registry!))

;; ---------------------------------------------------------------------------
;; run-rule: normal + exception-safe path
;; ---------------------------------------------------------------------------

(deftest run-rule-happy-path
  (let [ctx  {:ctx/period {:from "2026-03-01" :to "2026-03-31"}
              :ctx/marketplace :wb
              :ctx/tolerance default-t}
        rule {:rule/id :returns-two
              :rule/fn (fn [_ctx]
                         [{:disc/rule-id :returns-two :disc/marketplace :wb
                           :disc/period {:from "2026-03-01" :to "2026-03-31"}
                           :disc/location {} :disc/delta {:a 1 :b 2 :abs 1 :rel 0.5 :unit :rub}
                           :disc/classification :suspicious
                           :disc/classification-reason "test"}
                          {:disc/rule-id :returns-two :disc/marketplace :wb
                           :disc/period {:from "2026-03-01" :to "2026-03-31"}
                           :disc/location {} :disc/delta {:a 1 :b 2 :abs 1 :rel 0.5 :unit :rub}
                           :disc/classification :expected
                           :disc/classification-reason "test"}])}
        result (r/run-rule rule ctx)]
    (is (vector? result))
    (is (= 2 (count result)))))

(deftest run-rule-swallows-exceptions
  (let [ctx  {:ctx/period {:from "2026-03-01" :to "2026-03-31"}
              :ctx/marketplace :wb
              :ctx/tolerance default-t}
        rule {:rule/id :buggy
              :rule/fn (fn [_ctx] (throw (Exception. "boom")))}
        result (r/run-rule rule ctx)]
    (is (= 1 (count result)) "exception wrapped into exactly one synthetic discrepancy")
    (is (= :unclassified (:disc/classification (first result))))
    (is (= :buggy (:disc/rule-id (first result))))
    (is (re-find #"boom" (:disc/classification-reason (first result))))))

;; E-3 (2026-04-28) — per-rule tolerance override.
;; A rule may carry `:rule/tolerance` to displace the global ctx tolerance
;; for the duration of its run. Used by design-gap rules (aggregate-vs-raw,
;; wb-finance-vs-sales-events) so the global default can stay strict
;; without polluting reports with predictable noise.

(deftest run-rule-per-rule-tolerance-override
  (testing "rule's :rule/tolerance replaces ctx tolerance during its run"
    (let [seen-tolerances (atom [])
          ctx  {:ctx/period {:from "2026-03-01" :to "2026-03-31"}
                :ctx/marketplace :wb
                :ctx/tolerance {:abs 10.0 :rel 0.01}}
          rule {:rule/id        :loose-rule
                :rule/tolerance {:abs 1000.0 :rel 0.5}
                :rule/fn (fn [c]
                           (swap! seen-tolerances conj (:ctx/tolerance c))
                           [])}]
      (r/run-rule rule ctx)
      (is (= [{:abs 1000.0 :rel 0.5}] @seen-tolerances)
          "rule fn observes the per-rule tolerance, not the ctx default"))))

(deftest run-rule-no-override-uses-ctx-tolerance
  (testing "without :rule/tolerance the ctx default is used unchanged"
    (let [seen-tolerances (atom [])
          ctx  {:ctx/period {:from "2026-03-01" :to "2026-03-31"}
                :ctx/marketplace :wb
                :ctx/tolerance default-t}
          rule {:rule/id :no-override
                :rule/fn (fn [c]
                           (swap! seen-tolerances conj (:ctx/tolerance c))
                           [])}]
      (r/run-rule rule ctx)
      (is (= [default-t] @seen-tolerances)))))

(deftest run-rule-override-does-not-leak-to-caller
  (testing "ctx is not mutated — caller still sees the original tolerance"
    (let [ctx  {:ctx/period {:from "2026-03-01" :to "2026-03-31"}
                :ctx/marketplace :wb
                :ctx/tolerance default-t}
          rule {:rule/id        :leaky?
                :rule/tolerance {:abs 999.0 :rel 0.99}
                :rule/fn (fn [_] [])}]
      (r/run-rule rule ctx)
      (is (= default-t (:ctx/tolerance ctx))
          "caller's ctx unchanged"))))

;; ---------------------------------------------------------------------------
;; make-context validation
;; ---------------------------------------------------------------------------

(deftest make-context-requires-fields
  (is (thrown? clojure.lang.ExceptionInfo
               (r/make-context {:marketplace :wb :tolerance default-t})))
  (is (thrown? clojure.lang.ExceptionInfo
               (r/make-context {:period {:from "x" :to "y"} :tolerance default-t})))
  (is (thrown? clojure.lang.ExceptionInfo
               (r/make-context {:period {:from "x" :to "y"} :marketplace :wb :tolerance {:rel 0.01}})))
  (is (map? (r/make-context {:period {:from "x" :to "y"}
                             :marketplace :wb
                             :tolerance default-t}))))
