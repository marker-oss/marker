(ns analitica.domain.finance-row-test
  "Tests for the canonical FinanceRow Malli schema.

   US0 (spec 003-finance-row-completeness) adds the optional `:ad-cost`
   field. This namespace exercises both the base contract (required
   fields, enum constraints) and the new field's nilable/optional
   behaviour."
  (:require [clojure.test :refer [deftest testing is]]
            [analitica.domain.finance-row :as frow]))

(def ^:private valid-wb-row
  {:marketplace    :wb
   :rrd-id         123
   :date-from      "2026-03-01"
   :date-to        "2026-03-07"
   :article        "sku-1"
   :operation      "sale"
   :quantity       1
   :for-pay        500.0
   :retail-amount  650.0
   :wb-commission  120.0
   :delivery-cost  0.0
   :penalty        0.0})

(def ^:private valid-ozon-row
  {:marketplace    :ozon
   :rrd-id         -123456789
   :date-from      "2026-03-01"
   :date-to        "2026-03-31"
   :article        "offer-7"
   :operation      "sale"
   :quantity       2
   :for-pay        440.0
   :retail-amount  600.0
   :nm-id          987654
   :wb-commission  60.0})

(deftest valid?-accepts-canonical-rows
  (is (frow/valid? valid-wb-row))
  (is (frow/valid? valid-ozon-row)))

(deftest valid?-rejects-missing-required
  (testing "missing :marketplace"
    (is (not (frow/valid? (dissoc valid-wb-row :marketplace)))))
  (testing "missing :for-pay"
    (is (not (frow/valid? (dissoc valid-wb-row :for-pay)))))
  (testing "bad marketplace enum"
    (is (not (frow/valid? (assoc valid-wb-row :marketplace :amazon))))))

(deftest optional-fields-accept-nil
  (is (frow/valid? (assoc valid-wb-row :storage-fee nil)))
  (is (frow/valid? (assoc valid-wb-row :penalty nil :deduction nil))))

(deftest validate-rows-partitions
  (let [good valid-wb-row
        bad  (dissoc valid-wb-row :for-pay)
        {:keys [ok bad] :as _result} (frow/validate-rows [good bad good])]
    (is (= 2 (count ok)))
    (is (= 1 (count bad)))
    (is (map? (:error (first bad))))))

(deftest explain-returns-nil-on-valid
  (is (nil? (frow/explain valid-wb-row))))

(deftest explain-returns-humanized-on-invalid
  (let [explanation (frow/explain (dissoc valid-wb-row :for-pay))]
    (is (map? explanation))
    (is (contains? explanation :for-pay))))

(deftest summarize-bad-groups-by-marketplace-and-field
  (let [bad [{:row {:marketplace :wb} :error {:for-pay ["missing"]}}
             {:row {:marketplace :wb} :error {:for-pay ["missing"]}}
             {:row {:marketplace :ozon} :error {:rrd-id ["bad"]}}]
        s   (frow/summarize-bad bad)]
    (is (re-find #"wb → for-pay: 2" s))
    (is (re-find #"ozon → rrd-id: 1" s))))

;; ---------------------------------------------------------------------------
;; US0 — :ad-cost field
;; ---------------------------------------------------------------------------

(deftest ad-cost-accepts-int
  (testing "integer :ad-cost is valid"
    (is (frow/valid? (assoc valid-wb-row :ad-cost 100)))
    (is (frow/valid? (assoc valid-ozon-row :ad-cost 0)))))

(deftest ad-cost-accepts-double
  (testing "double :ad-cost is valid"
    (is (frow/valid? (assoc valid-wb-row :ad-cost 100.5)))
    (is (frow/valid? (assoc valid-ozon-row :ad-cost 12.34)))))

(deftest ad-cost-is-optional
  (testing "FinanceRow without :ad-cost key is valid (WB/MVP keeps it nil)"
    (is (not (contains? valid-wb-row :ad-cost)))
    (is (frow/valid? valid-wb-row))))

(deftest ad-cost-is-nilable
  (testing ":ad-cost may be explicitly nil"
    (is (frow/valid? (assoc valid-wb-row :ad-cost nil)))))

(deftest ad-cost-rejects-non-numeric
  (testing "string :ad-cost is invalid"
    (is (not (frow/valid? (assoc valid-wb-row :ad-cost "abc"))))
    (let [explanation (frow/explain (assoc valid-wb-row :ad-cost "abc"))]
      (is (contains? explanation :ad-cost)))))
