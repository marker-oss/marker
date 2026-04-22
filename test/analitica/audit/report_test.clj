(ns analitica.audit.report-test
  "Tests for analitica.audit.report — Discrepancy/Report data layer,
   stable-report-id determinism, and summary aggregation."
  (:require [clojure.test :refer [deftest testing is]]
            [analitica.audit.report :as rep]))

(def ^:private default-period {:from "2026-03-01" :to "2026-03-31"})
(def ^:private default-t      {:rel 0.01 :abs 10.0})

;; ---------------------------------------------------------------------------
;; T010 — stable-report-id determinism
;; ---------------------------------------------------------------------------

(deftest stable-report-id-is-deterministic
  (testing "same inputs → same id, regardless of call order or whitespace"
    (is (= (rep/stable-report-id :wb default-period [:a :b] default-t)
           (rep/stable-report-id :wb default-period [:a :b] default-t)))))

(deftest stable-report-id-independent-of-rule-order
  (testing "rule order does not affect id (SC-006 reproducibility)"
    (is (= (rep/stable-report-id :wb default-period [:a :b :c] default-t)
           (rep/stable-report-id :wb default-period [:c :a :b] default-t)))))

(deftest stable-report-id-changes-with-marketplace
  (is (not= (rep/stable-report-id :wb default-period [:a] default-t)
            (rep/stable-report-id :ozon default-period [:a] default-t))))

(deftest stable-report-id-changes-with-period
  (is (not= (rep/stable-report-id :wb default-period [:a] default-t)
            (rep/stable-report-id :wb {:from "2026-04-01" :to "2026-04-30"} [:a] default-t))))

(deftest stable-report-id-changes-with-rules
  (is (not= (rep/stable-report-id :wb default-period [:a] default-t)
            (rep/stable-report-id :wb default-period [:a :b] default-t)))
  (is (not= (rep/stable-report-id :wb default-period [:a] default-t)
            (rep/stable-report-id :wb default-period [:b] default-t))))

(deftest stable-report-id-changes-with-tolerance
  (is (not= (rep/stable-report-id :wb default-period [:a] {:rel 0.01 :abs 10.0})
            (rep/stable-report-id :wb default-period [:a] {:rel 0.02 :abs 10.0})))
  (is (not= (rep/stable-report-id :wb default-period [:a] {:rel 0.01 :abs 10.0})
            (rep/stable-report-id :wb default-period [:a] {:rel 0.01 :abs 20.0}))))

(deftest stable-report-id-format
  (let [id (rep/stable-report-id :wb default-period [:a] default-t)]
    (is (string? id))
    (is (= 16 (count id)) "id truncated to 16 hex chars for readability")
    (is (re-matches #"[0-9a-f]{16}" id) "lowercase hex")))

;; ---------------------------------------------------------------------------
;; make-discrepancy validation
;; ---------------------------------------------------------------------------

(defn- base-disc [overrides]
  (merge {:rule-id :demo
          :marketplace :wb
          :period default-period
          :location {:article "A"}
          :delta {:a 100 :b 120 :abs 20.0 :rel 0.05 :unit :rub}
          :classification :suspicious
          :classification-reason "test"}
         overrides))

(deftest make-discrepancy-happy-path
  (let [d (rep/make-discrepancy (base-disc {}))]
    (is (= :demo (:disc/rule-id d)))
    (is (= :wb (:disc/marketplace d)))
    (is (= :suspicious (:disc/classification d)))
    (is (contains? (:disc/location d) :operation) "location always gets default keys")))

(deftest make-discrepancy-rejects-missing-required
  (is (thrown? clojure.lang.ExceptionInfo
               (rep/make-discrepancy (dissoc (base-disc {}) :rule-id))))
  (is (thrown? clojure.lang.ExceptionInfo
               (rep/make-discrepancy (dissoc (base-disc {}) :marketplace))))
  (is (thrown? clojure.lang.ExceptionInfo
               (rep/make-discrepancy (dissoc (base-disc {}) :period))))
  (is (thrown? clojure.lang.ExceptionInfo
               (rep/make-discrepancy (dissoc (base-disc {}) :classification)))))

(deftest make-discrepancy-rejects-negative-delta
  (is (thrown? clojure.lang.ExceptionInfo
               (rep/make-discrepancy (base-disc {:delta {:a 0 :b 0 :abs -1 :rel 0 :unit :rub}}))))
  (is (thrown? clojure.lang.ExceptionInfo
               (rep/make-discrepancy (base-disc {:delta {:a 0 :b 0 :abs 1 :rel -0.1 :unit :rub}})))))

(deftest make-discrepancy-rejects-bad-unit
  (is (thrown? clojure.lang.ExceptionInfo
               (rep/make-discrepancy (base-disc {:delta {:a 0 :b 0 :abs 1 :rel 0.1 :unit :wat}})))))

(deftest make-discrepancy-rejects-bad-classification
  (is (thrown? clojure.lang.ExceptionInfo
               (rep/make-discrepancy (base-disc {:classification :meh})))))

(deftest make-discrepancy-expected-requires-reason
  (is (thrown? clojure.lang.ExceptionInfo
               (rep/make-discrepancy (base-disc {:classification :expected
                                                 :classification-reason nil}))))
  (is (thrown? clojure.lang.ExceptionInfo
               (rep/make-discrepancy (base-disc {:classification :expected
                                                 :classification-reason "  "}))))
  (is (rep/make-discrepancy (base-disc {:classification :expected
                                        :classification-reason "rounding"}))))

(deftest make-discrepancy-unclassified-requires-operation
  (is (thrown? clojure.lang.ExceptionInfo
               (rep/make-discrepancy (base-disc {:classification :unclassified
                                                 :classification-reason "unknown op"
                                                 :location {:article "A"}})))
      "missing location.operation — rejected")
  (is (rep/make-discrepancy (base-disc {:classification :unclassified
                                        :classification-reason "unknown op"
                                        :location {:article "A" :operation "новый-тип"}}))))

;; ---------------------------------------------------------------------------
;; make-report: aggregation + invariants
;; ---------------------------------------------------------------------------

(defn- disc
  "Test helper producing a valid discrepancy with minimal caller noise."
  [rule-id classification delta-abs unit & {:keys [operation]}]
  (rep/make-discrepancy
    {:rule-id              rule-id
     :marketplace          :wb
     :period               default-period
     :location             (cond-> {:article "A"}
                             operation (assoc :operation operation))
     :delta                {:a 100 :b 80 :abs delta-abs :rel 0.2 :unit unit}
     :classification       classification
     :classification-reason (cond
                              (= classification :expected) "within tolerance"
                              (= classification :unclassified) "unknown op"
                              :else nil)}))

(deftest make-report-sums-counts
  (let [r (rep/make-report
           {:marketplace        :wb
            :period             default-period
            :rules-applied      [:r1 :r2]
            :sources-available  [:raw-finance :agg-finance]
            :discrepancies      [(disc :r1 :suspicious   100 :rub)
                                 (disc :r1 :suspicious    50 :rub)
                                 (disc :r1 :expected       1 :rub)
                                 (disc :r2 :unclassified  10 :count :operation "?")]
            :tolerance-snapshot default-t
            :captured-at        "2026-04-21T12:00:00Z"})]
    (is (= {:expected 1 :suspicious 2 :unclassified 1}
           (get-in r [:report/summary :counts])))
    (is (= 4 (count (:report/discrepancies r))))))

(deftest make-report-id-is-deterministic
  (let [args {:marketplace :wb :period default-period
              :rules-applied [:r1 :r2] :sources-available [:raw-finance]
              :discrepancies [] :tolerance-snapshot default-t
              :captured-at "2026-04-21T12:00:00Z"}
        a (rep/make-report args)
        b (rep/make-report (assoc args :captured-at "2099-12-31T23:59:59Z"))]
    (is (= (:report/id a) (:report/id b))
        "different captured-at → same report id (SC-006)")))

(deftest make-report-top-causes-is-sorted-by-delta-then-count
  (let [many-small  (repeat 10 (disc :small :suspicious 1.0 :rub))
        few-big     (repeat 2  (disc :big   :suspicious 100.0 :rub))
        one-mid     (repeat 3  (disc :mid   :suspicious 20.0 :rub))
        r (rep/make-report
            {:marketplace :wb :period default-period
             :rules-applied [:small :big :mid] :sources-available [:raw-finance]
             :discrepancies (concat many-small few-big one-mid)
             :tolerance-snapshot default-t
             :captured-at "2026-04-21T12:00:00Z"
             :top-n 10})
        top (get-in r [:report/summary :top-causes])]
    (is (= [:big :mid :small] (mapv :rule-id top))
        "sorted by sum-abs-delta desc: big=200, mid=60, small=10")))

(deftest make-report-top-n-truncation
  (let [discs (mapcat (fn [i]
                        [(disc (keyword (str "rule-" i))
                               :suspicious (* i 1.0) :rub)])
                      (range 1 16))
        r (rep/make-report
            {:marketplace :wb :period default-period
             :rules-applied (mapv #(keyword (str "rule-" %)) (range 1 16))
             :sources-available [:raw-finance]
             :discrepancies discs
             :tolerance-snapshot default-t
             :captured-at "2026-04-21T12:00:00Z"
             :top-n 5})]
    (is (= 5 (count (get-in r [:report/summary :top-causes]))))))

(deftest make-report-rules-applied-is-sorted
  (let [r (rep/make-report
            {:marketplace :wb :period default-period
             :rules-applied [:z-rule :a-rule :m-rule]
             :sources-available [:raw-finance]
             :discrepancies []
             :tolerance-snapshot default-t
             :captured-at "2026-04-21T12:00:00Z"})]
    (is (= [:a-rule :m-rule :z-rule] (:report/rules-applied r)))))
