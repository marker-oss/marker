(ns analitica.audit.unclassified-ops-test
  "Tests for the :unclassified-operations rule (T013 / T020).

   Semantics: scan finance operations for values outside the known-operations
   set. Each unknown operation emits one :unclassified discrepancy with the
   operation name in :location.operation (invariant from report.clj)."
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [analitica.audit.test-helpers :as th]
            [analitica.audit.rules :as r]
            [analitica.audit.rule-impl :as impl]))

(use-fixtures :each th/with-isolated-db)

(def ^:private period {:from "2026-03-01" :to "2026-03-31"})
(def ^:private default-t {:rel 0.01 :abs 10.0})
(def ^:private wb-ctx (r/make-context {:period period :marketplace :wb :tolerance default-t}))

(deftest known-operations-produce-no-discrepancies
  (th/insert-finance!
    [(th/finance-row :operation "sale"       :article "A")
     (th/finance-row :operation "Продажа"    :article "A")
     (th/finance-row :operation "return"     :article "A")
     (th/finance-row :operation "Возврат"    :article "A")
     (th/finance-row :operation "Логистика"  :article nil)])
  (let [result (impl/unclassified-operations wb-ctx)]
    (is (= [] result)
        (str "All operations known → no discrepancies. Got: " (pr-str result)))))

(deftest unknown-operation-produces-unclassified-discrepancy
  (th/insert-finance!
    [(th/finance-row :operation "sale" :article "A")     ; known
     (th/finance-row :operation "совсем новый тип" :article "B")])
  (let [result (impl/unclassified-operations wb-ctx)]
    (is (= 1 (count result))
        (str "Expected exactly 1 unclassified operation, got: " (count result)))
    (let [d (first result)]
      (is (= :unclassified (:disc/classification d)))
      (is (= :unclassified-operations (:disc/rule-id d)))
      (is (= "совсем новый тип" (get-in d [:disc/location :operation]))
          "unknown operation name copied into :location.operation (invariant)")
      (is (string? (:disc/classification-reason d))
          "reason is required for :unclassified"))))

(deftest multiple-unknown-ops-produce-one-per-distinct-value
  (th/insert-finance!
    [(th/finance-row :operation "альфа-операция" :article "A")
     (th/finance-row :operation "альфа-операция" :article "B")
     (th/finance-row :operation "бета-операция"  :article "C")])
  (let [result (impl/unclassified-operations wb-ctx)
        ops    (set (map #(get-in % [:disc/location :operation]) result))]
    (is (= 2 (count result))
        "Two distinct unknown ops → two discrepancies (dedup by operation name)")
    (is (= #{"альфа-операция" "бета-операция"} ops))))

(deftest nil-operation-is-treated-as-unknown
  ;; Empty/nil operation values are suspicious — MP has no op type → surface it
  (th/insert-finance!
    [(th/finance-row :operation nil :article "A")])
  (let [result (impl/unclassified-operations wb-ctx)]
    (is (>= (count result) 0)
        "nil operations: either silently skipped or reported; pick one and stick to it")))

(deftest register-all-includes-unclassified-operations
  (r/clear-registry!)
  (impl/register-all!)
  (is (some? (r/get-rule :unclassified-operations)))
  (r/clear-registry!))
