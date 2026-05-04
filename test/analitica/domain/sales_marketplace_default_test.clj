(ns analitica.domain.sales-marketplace-default-test
  "F7: report-fns silently scoped queries to WB when caller omitted
   :marketplace. The `:or {marketplace :wb}` default in fetch-sales /
   fetch-orders made 'all marketplaces' unreachable through the normal
   high-level API (sales/buyout/returns reports + REPL / CLI usage).

   These tests pin the corrected behaviour: missing :marketplace must
   produce a query without any `marketplace = ?` clause, returning
   rows from every MP."
  (:require [clojure.test :refer [deftest is testing]]
            [analitica.domain.sales :as sales]
            [analitica.db :as db]))

(defn- capturing-query [captured]
  (fn [sql-params]
    (reset! captured sql-params)
    []))

(deftest fetch-sales-without-marketplace-omits-mp-clause
  (let [captured (atom nil)]
    (with-redefs [db/query (capturing-query captured)]
      (sales/fetch-sales {:from "2026-04-01" :to "2026-04-30"}))
    (let [[sql & params] @captured]
      (testing "SQL contains no marketplace = ? filter"
        (is (not (re-find #"marketplace\s*=\s*\?" sql))
            (str "Expected no MP filter, got SQL: " sql)))
      (testing "params contain only [from to], not WB"
        (is (= 2 (count params))
            (str "Expected 2 params (from, to). Got: " params))))))

(deftest fetch-sales-with-marketplace-keeps-mp-clause
  (let [captured (atom nil)]
    (with-redefs [db/query (capturing-query captured)]
      (sales/fetch-sales {:from "2026-04-01" :to "2026-04-30"} :marketplace :ozon))
    (let [[sql & params] @captured]
      (testing "explicit :marketplace adds MP filter"
        (is (re-find #"marketplace\s*=\s*\?" sql)))
      (testing "params include marketplace name"
        (is (some #{"ozon"} params))))))

(deftest fetch-orders-without-marketplace-omits-mp-clause
  (let [captured (atom nil)]
    (with-redefs [db/query (capturing-query captured)]
      (sales/fetch-orders {:from "2026-04-01" :to "2026-04-30"}))
    (let [[sql & params] @captured]
      (testing "SQL contains no marketplace = ? filter"
        (is (not (re-find #"marketplace\s*=\s*\?" sql))
            (str "Expected no MP filter, got SQL: " sql)))
      (testing "params contain only [from to], not WB"
        (is (= 2 (count params))
            (str "Expected 2 params (from, to). Got: " params))))))

(deftest fetch-orders-with-marketplace-keeps-mp-clause
  (let [captured (atom nil)]
    (with-redefs [db/query (capturing-query captured)]
      (sales/fetch-orders {:from "2026-04-01" :to "2026-04-30"} :marketplace :wb))
    (let [[sql & params] @captured]
      (testing "explicit :marketplace adds MP filter"
        (is (re-find #"marketplace\s*=\s*\?" sql)))
      (testing "params include marketplace name"
        (is (some #{"wb"} params))))))
