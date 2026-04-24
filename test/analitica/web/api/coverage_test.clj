(ns analitica.web.api.coverage-test
  "Basic shape test for the /api/coverage endpoint helper. Runs against the
   live SQLite DB via the :integration marker."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [analitica.web.api.coverage :as coverage]
            [analitica.db :as db]))

(use-fixtures :once
  (fn [f]
    (db/init!)
    (f)))

(deftest ^:integration days-with-data-test
  (testing "returns a vector (possibly empty) of ISO date strings"
    (let [days (coverage/days-with-data "2026-04-01" "2026-04-30")]
      (is (vector? days))
      (is (every? string? days))))

  (testing "accepts :marketplace keyword and returns a vector"
    (let [days (coverage/days-with-data "2026-04-01" "2026-04-30" :marketplace :wb)]
      (is (vector? days))
      (is (every? string? days))))

  (testing "returns empty vector for future date range with no data"
    (let [days (coverage/days-with-data "2099-01-01" "2099-01-31")]
      (is (vector? days))
      (is (empty? days)))))
