(ns analitica.schema.validator-test
  "Tests for analitica.schema.validator — validate/validate!/aggregation/extras."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [analitica.schema.registry :as r]
            [analitica.schema.validator :as v]))

(defn- with-clean-registry [f]
  (r/clear!)
  (try (f) (finally (r/clear!))))

(use-fixtures :each with-clean-registry)

(defn- contract
  [id schema]
  {:endpoint/id          id
   :endpoint/marketplace :wb
   :endpoint/api-path    "/x"
   :endpoint/method      :get
   :contract/source      {:kind :manual :generated-at "2026-04-22"}
   :contract/response-schema schema
   :contract/version     1})

;; ---------------------------------------------------------------------------
;; T011 — basic validate happy/missing/mismatch
;; ---------------------------------------------------------------------------

(deftest validate-ok-returns-ok-status
  (let [c (contract :t/ok [:map [:x :int]])]
    (is (= :ok (:result/status (v/validate c {:x 42})))))
  (let [c (contract :t/ok-opt [:map [:x :int] [:y {:optional true} :string]])]
    (is (= :ok (:result/status (v/validate c {:x 42}))))))

(deftest validate-missing-required-is-failed
  (let [c (contract :t/miss [:map [:x :int]])
        r (v/validate c {})]
    (is (= :failed (:result/status r)))
    (is (= 1 (count (:result/violations r))))
    (is (contains? #{:required-missing :type-mismatch}
                   (:violation/kind (first (:result/violations r)))))))

(deftest validate-type-mismatch-is-failed
  (let [c (contract :t/type [:map [:x :int]])
        r (v/validate c {:x "not-int"})]
    (is (= :failed (:result/status r)))
    (is (= :type-mismatch (:violation/kind (first (:result/violations r)))))))

;; ---------------------------------------------------------------------------
;; T012 — extras → warning (not failed)
;; ---------------------------------------------------------------------------

(deftest extra-field-is-warning
  (let [c (contract :t/extra [:map [:x :int]])
        r (v/validate c {:x 42 :y 99 :z "hello"})]
    (is (= :warned (:result/status r))
        "extras alone should not block — only warn")
    (let [kinds (set (map :violation/kind (:result/violations r)))]
      (is (contains? kinds :extra-field))
      (is (not (contains? kinds :type-mismatch)))
      (is (not (contains? kinds :required-missing))))))

(deftest mixed-extras-and-critical-still-failed
  (let [c (contract :t/mix [:map [:x :int]])
        r (v/validate c {:x "bad" :y 99})]
    (is (= :failed (:result/status r))
        "a single critical wins over any number of warnings")))

;; ---------------------------------------------------------------------------
;; T013 — aggregation (FR-009)
;; ---------------------------------------------------------------------------

(deftest aggregates-same-kind-same-path-above-threshold
  (let [c (contract :t/agg [:sequential [:map [:x :int]]])
        data (vec (repeat 100 {:x "not-int"}))
        r (v/validate c data)]
    (is (= :failed (:result/status r)))
    (is (= 1 (count (:result/violations r)))
        "100 identical type-mismatches fold into a single aggregated violation")
    (let [viol (first (:result/violations r))]
      (is (= 100 (:violation/occurrences viol)))
      (is (= 3 (count (:violation/sample viol)))
          "sample shows first 3 example paths"))))

(deftest small-counts-stay-unaggregated
  (binding [v/*aggregation-threshold* 5]
    (let [c (contract :t/small [:sequential [:map [:x :int]]])
          ;; 3 bad elements — below threshold, should NOT aggregate
          data [{:x "a"} {:x "b"} {:x "c"}]
          r (v/validate c data)]
      (is (= 3 (count (:result/violations r)))
          "small counts preserve per-row detail"))))

;; ---------------------------------------------------------------------------
;; T014 — nested path
;; ---------------------------------------------------------------------------

(deftest nested-path-is-full-vector
  (let [c (contract :t/nested
                    [:map
                     [:items
                      [:sequential [:map [:price :double]]]]])
        r (v/validate c {:items [{:price 1.0} {:price "wrong"}]})]
    (is (= :failed (:result/status r)))
    (let [viol (first (:result/violations r))]
      (is (= [:items 1 :price] (:violation/path viol))))))

;; ---------------------------------------------------------------------------
;; T015 — validate! throw-behaviour
;; ---------------------------------------------------------------------------

(deftest validate-bang-passes-through-on-ok
  (r/register! (contract :t/b1 [:map [:x :int]]))
  (is (= {:x 42} (v/validate! :t/b1 {:x 42}))))

(deftest validate-bang-throws-on-critical
  (r/register! (contract :t/b2 [:map [:x :int]]))
  (try
    (v/validate! :t/b2 {:x "bad"})
    (is false "should have thrown")
    (catch clojure.lang.ExceptionInfo e
      (is (= :schema-violation (:type (ex-data e))))
      (is (= :t/b2 (:endpoint-id (ex-data e)))))))

(deftest validate-bang-does-not-throw-on-warnings-only
  (r/register! (contract :t/b3 [:map [:x :int]]))
  ;; extras produce :warned → validate! returns response (does NOT throw)
  (is (= {:x 42 :extra 99} (v/validate! :t/b3 {:x 42 :extra 99}))))

(deftest validate-bang-noop-without-contract
  ;; FR-001: opt-in per endpoint. No contract → response returned unchanged.
  (is (= "anything" (v/validate! :not/registered "anything"))))

;; ---------------------------------------------------------------------------
;; with-validation wrapper — order of operations (FR-004 invariant)
;; ---------------------------------------------------------------------------

(deftest with-validation-saves-raw-before-validating
  ;; If validate! throws, save-raw-fn MUST have already been called.
  (r/register! (contract :t/raw [:map [:x :int]]))
  (let [saved (atom nil)
        fetch (fn [] {:x "bad"})
        save  (fn [r] (reset! saved r))]
    (is (thrown? clojure.lang.ExceptionInfo
                 (v/with-validation :t/raw fetch save)))
    (is (= {:x "bad"} @saved)
        "raw response must be persisted even though validation later failed")))

(deftest with-validation-returns-response-on-ok
  (r/register! (contract :t/raw-ok [:map [:x :int]]))
  (let [saved (atom nil)]
    (is (= {:x 42}
           (v/with-validation :t/raw-ok
                              (constantly {:x 42})
                              #(reset! saved %))))
    (is (= {:x 42} @saved))))
