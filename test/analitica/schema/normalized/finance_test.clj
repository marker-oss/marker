(ns analitica.schema.normalized.finance-test
  (:require [clojure.test :refer [deftest is testing]]
            [analitica.schema.normalized.finance :as sut]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [analitica.test-helpers :as th]))

(def minimal-row
  {:marketplace :wb
   :rrd-id      1
   :date-from   "2026-03-01"
   :date-to     "2026-03-07"
   :article     "ABC-123"
   :operation   "sale"
   :quantity    1
   :for-pay     100.0})

(deftest minimal-row-validates
  (is (sut/valid? minimal-row))
  (is (nil? (sut/explain minimal-row))))

(deftest missing-marketplace-rejected
  (let [row (dissoc minimal-row :marketplace)]
    (is (not (sut/valid? row)))
    (is (contains? (sut/explain row) :marketplace))))

(deftest unknown-marketplace-rejected
  (let [row (assoc minimal-row :marketplace :foobar)]
    (is (not (sut/valid? row)))))

(deftest all-optional-fields-nilable
  (let [row (merge minimal-row
                   {:nm-id nil :barcode nil :subject nil :brand nil
                    :doc-type nil :retail-price nil :retail-amount nil
                    :sale-percent nil :commission-pct nil :mp-commission nil
                    :wb-reward nil :wb-kvw-prc nil :spp-prc nil
                    :price-with-disc nil :delivery-amount nil
                    :return-amount nil :delivery-cost nil :penalty nil
                    :storage-fee nil :acceptance nil :additional-payment nil
                    :deduction nil :acquiring-fee nil :ad-cost nil})]
    (is (sut/valid? row))))

(deftest production-rows-conform
  (testing "Every row currently in the finance table passes FinanceRow"
    (if-let [ds (th/db-or-skip)]
      (let [rows (jdbc/execute! ds ["SELECT * FROM finance LIMIT 1000"]
                                {:builder-fn rs/as-kebab-maps})
            ;; as-kebab-maps returns namespaced keys (:finance/rrd-id); strip ns
            ;; DB stores marketplace as string; convert to keyword for schema check
            rows (map #(-> % th/strip-ns (update :marketplace keyword)) rows)
            {:keys [ok bad]} (sut/validate-rows rows)]
        (is (empty? bad)
            (str "Non-conforming rows: " (count bad) " / "
                 (+ (count ok) (count bad))
                 "\nFirst 3 errors: " (vec (take 3 bad)))))
      (do (println "  (skipped — no ANALITICA_TEST_DB / ANALITICA_DB set)")
          (is true "skipped — no DB configured")))))

;; ---------------------------------------------------------------------------
;; T005 (spec 012) — :net-sales optional key + YM negative :for-pay
;; ---------------------------------------------------------------------------

(deftest net-sales-optional-key
  (testing "FinanceRow accepts optional :net-sales (spec 012)"
    (is (sut/valid? (assoc minimal-row :net-sales 1700.0)) "present numeric")
    (is (sut/valid? (assoc minimal-row :net-sales nil))    "explicit nil")
    (is (sut/valid? minimal-row)                           "absent (optional)")))

(deftest ym-negative-for-pay-allowed
  (testing "YM sale row :for-pay may be negative (loss-making SKU — FR-005/RFC-15 exception)"
    (let [row (assoc minimal-row :marketplace :ym :for-pay -569.4 :net-sales 2544.0)]
      (is (sut/valid? row))
      (is (nil? (sut/explain row)))))
  (testing "WB row without :net-sales remains valid (net == gross)"
    (is (sut/valid? (assoc minimal-row :marketplace :wb)))))
