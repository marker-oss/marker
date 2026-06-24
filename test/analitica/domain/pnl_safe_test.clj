(ns analitica.domain.pnl-safe-test
  (:require [clojure.test :refer [deftest is]]
            [analitica.domain.pnl :as pnl]
            [analitica.domain.sales :as sales]
            [analitica.web.api.report :as report]
            [analitica.db :as db]
            [analitica.util.safe :as safe]))

(deftest ad-cost-sum-logs-and-falls-back-on-db-error
  (let [calls (atom [])]
    (with-redefs [db/query (fn [_] (throw (ex-info "db down" {})))
                  safe/report-error! (fn [ctx _] (swap! calls conj ctx))]
      ;; ad-cost-sum is private; call via the var
      (is (nil? (#'pnl/ad-cost-sum "2026-01-01" "2026-01-31" :wb))))
    (is (= 1 (count @calls)))))

(deftest compute-report-logs-and-falls-back-on-calc-throw
  ;; compute-report's :sales branch calls sales/fetch-sales — force it to throw
  ;; and assert report-data returns the {:rows [] :totals {}} fallback AND logged.
  (let [calls (atom [])]
    (with-redefs [sales/fetch-sales (fn [& _] (throw (ex-info "calc boom" {})))
                  safe/report-error! (fn [ctx _] (swap! calls conj ctx))]
      (is (= {:rows [] :totals {}}
             (report/report-data :sales {:from "2026-01-01" :to "2026-01-31"}
                                 :marketplace :wb))))
    (is (= [::report/compute-report-failed] @calls))))
