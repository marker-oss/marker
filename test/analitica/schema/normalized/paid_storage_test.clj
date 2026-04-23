(ns analitica.schema.normalized.paid-storage-test
  (:require [clojure.test :refer [deftest is testing]]
            [analitica.schema.normalized.paid-storage :as sut]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [analitica.test-helpers :as th]))

(def minimal-row
  {:date "2026-03-15" :article "ABC-1" :barcode "" :warehouse ""
   :cost 12.50 :marketplace :wb})

(deftest minimal-row-validates
  (is (sut/valid? minimal-row)))

(deftest negative-cost-rejected
  (is (not (sut/valid? (assoc minimal-row :cost -1.0)))))

(deftest production-rows-conform
  (testing "Every paid_storage row passes PaidStorageRow"
    (if-let [ds (th/db-or-skip)]
      (let [rows (jdbc/execute! ds ["SELECT * FROM paid_storage LIMIT 1000"]
                                {:builder-fn rs/as-kebab-maps})
            rows (->> rows
                      (map th/strip-ns)
                      (map #(update % :marketplace (fn [v] (some-> v keyword)))))
            {:keys [ok bad]} (sut/validate-rows rows)]
        (is (empty? bad)
            (str "Non-conforming: " (count bad)
                 "\nFirst 3 errors: " (vec (take 3 bad)))))
      (is true "skipped — no DB configured"))))
