(ns analitica.schema.normalized.region-sales-test
  (:require [clojure.test :refer [deftest is testing]]
            [analitica.schema.normalized.region-sales :as sut]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [analitica.test-helpers :as th]))

(def minimal-row
  {:nm-id 0 :article "" :region "" :city ""
   :date-from "2026-03-01" :date-to "2026-03-31"})

(deftest minimal-row-validates
  (is (sut/valid? minimal-row)))

(deftest production-rows-conform
  (testing "Every region_sales row passes RegionSalesRow"
    (if-let [ds (th/db-or-skip)]
      (let [rows (jdbc/execute! ds ["SELECT * FROM region_sales LIMIT 1000"]
                                {:builder-fn rs/as-kebab-maps})
            rows (map th/strip-ns rows)
            {:keys [ok bad]} (sut/validate-rows rows)]
        (is (empty? bad)
            (str "Non-conforming: " (count bad)
                 "\nFirst 3 errors: " (vec (take 3 bad)))))
      (is true "skipped — no DB configured"))))
