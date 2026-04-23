(ns analitica.schema.normalized.cash-flow-periods-test
  (:require [clojure.test :refer [deftest is testing]]
            [analitica.schema.normalized.cash-flow-periods :as sut]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [analitica.test-helpers :as th]))

(def minimal-row
  {:source :ozon
   :period-begin "2026-03-01"
   :period-end "2026-03-07"
   :synced-at "2026-04-01T12:00:00Z"})

(deftest minimal-row-validates
  (is (sut/valid? minimal-row)))

(deftest unknown-source-rejected
  (is (not (sut/valid? (assoc minimal-row :source :foobar)))))

(deftest production-rows-conform
  (testing "Every cash_flow_periods row passes CashFlowPeriodRow"
    (if-let [ds (th/db-or-skip)]
      (let [rows (jdbc/execute! ds ["SELECT * FROM cash_flow_periods"]
                                {:builder-fn rs/as-kebab-maps})
            rows (->> rows
                      (map th/strip-ns)
                      (map #(update % :source (fn [v] (some-> v keyword)))))
            {:keys [ok bad]} (sut/validate-rows rows)]
        (is (empty? bad)
            (str "Non-conforming: " (count bad)
                 "\nFirst 3 errors: " (vec (take 3 bad)))))
      (is true "skipped — no DB configured"))))
