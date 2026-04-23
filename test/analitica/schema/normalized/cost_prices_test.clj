(ns analitica.schema.normalized.cost-prices-test
  (:require [clojure.test :refer [deftest is testing]]
            [analitica.schema.normalized.cost-prices :as sut]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [analitica.test-helpers :as th]))

(def minimal-row
  {:article "ABC-1" :barcode "" :cost-price 150.0})

(deftest minimal-row-validates
  (is (sut/valid? minimal-row)))

(deftest negative-cost-price-rejected
  ;; Business rule: cost_price must be >= 0
  (is (not (sut/valid? (assoc minimal-row :cost-price -10.0)))))

(deftest missing-cost-price-rejected
  (is (not (sut/valid? (dissoc minimal-row :cost-price)))))

(deftest production-rows-conform
  (testing "Every cost_prices row passes CostPriceRow"
    (if-let [ds (th/db-or-skip)]
      (let [rows (jdbc/execute! ds ["SELECT * FROM cost_prices LIMIT 1000"]
                                {:builder-fn rs/as-kebab-maps})
            rows (map th/strip-ns rows)
            {:keys [ok bad]} (sut/validate-rows rows)]
        (is (empty? bad)
            (str "Non-conforming: " (count bad) " / "
                 (+ (count ok) (count bad))
                 "\nFirst 3 errors: " (vec (take 3 bad)))))
      (is true "skipped — no DB configured"))))
