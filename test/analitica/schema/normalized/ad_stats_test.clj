(ns analitica.schema.normalized.ad-stats-test
  (:require [clojure.test :refer [deftest is testing]]
            [analitica.schema.normalized.ad-stats :as sut]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [analitica.test-helpers :as th]))

(def minimal-row
  {:campaign-id 123 :date "2026-03-15" :nm-id 0})

(deftest minimal-row-validates
  (is (sut/valid? minimal-row)))

(deftest missing-pk-field-rejected
  (doseq [k [:campaign-id :date :nm-id]]
    (is (not (sut/valid? (dissoc minimal-row k))) (str "missing " k))))

(deftest production-rows-conform
  (testing "Every ad_stats row passes AdStatsRow"
    (if-let [ds (th/db-or-skip)]
      (let [rows (jdbc/execute! ds ["SELECT * FROM ad_stats LIMIT 1000"]
                                {:builder-fn rs/as-kebab-maps})
            rows (map th/strip-ns rows)
            {:keys [ok bad]} (sut/validate-rows rows)]
        (is (empty? bad)
            (str "Non-conforming: " (count bad)
                 "\nFirst 3 errors: " (vec (take 3 bad)))))
      (is true "skipped — no DB configured"))))
