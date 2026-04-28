(ns analitica.schema.normalized.stocks-history-test
  (:require [clojure.test :refer [deftest is testing]]
            [analitica.schema.normalized.stocks-history :as sut]))

(def minimal-row
  {:snapshot-date "2026-04-28"
   :marketplace   :wb
   :article       "ART-1"
   :warehouse     "Коледино"
   :quantity      42})

(deftest minimal-row-validates
  (is (sut/valid? minimal-row))
  (is (nil? (sut/explain minimal-row))))

(deftest missing-snapshot-date-rejected
  (let [row (dissoc minimal-row :snapshot-date)]
    (is (not (sut/valid? row)))
    (is (contains? (sut/explain row) :snapshot-date))))

(deftest unknown-marketplace-rejected
  (let [row (assoc minimal-row :marketplace :foobar)]
    (is (not (sut/valid? row)))))

(deftest warehouse-may-be-nil
  (testing "snapshots from MPs without warehouse attribution still validate"
    (is (sut/valid? (assoc minimal-row :warehouse nil)))))

(deftest extra-keys-allowed
  (testing "schema is open — extra fields don't break validation"
    (is (sut/valid? (assoc minimal-row :extra "ignored")))))
