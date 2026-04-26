(ns analitica.web.pages.dashboard-test
  (:require [clojure.test :refer [deftest is testing]]
            [analitica.web.pages.dashboard :as dashboard]))

(deftest summary-page-test
  (testing "summary-page renders without errors"
    (let [result (dashboard/summary-page :last-week)]
      (is (vector? result))
      (is (= :div (first result)))))
  
  (testing "summary-page accepts keyword period"
    (let [result (dashboard/summary-page :last-7-days)]
      (is (vector? result))))
  
  (testing "summary-page accepts map period"
    (let [result (dashboard/summary-page {:from "2026-04-01" :to "2026-04-30"})]
      (is (vector? result))))
  
  (testing "summary-page shows no-data banner when metrics are zero"
    (let [zero-metrics {:revenue 0.0
                        :orders 0
                        :profit 0.0
                        :return-rate 0.0}
          result (dashboard/summary-page :last-week :metrics zero-metrics)
          html-str (pr-str result)]
      (is (vector? result))
      ;; Check that no-data banner is present
      (is (re-find #"Нет данных за выбранный период" html-str))))
  
  (testing "summary-page does not show no-data banner when metrics have data"
    (let [metrics {:revenue 100000.0
                   :orders 50
                   :profit 20000.0
                   :return-rate 5.0}
          result (dashboard/summary-page :last-week :metrics metrics)
          html-str (pr-str result)]
      (is (vector? result))
      ;; Check that no-data banner is NOT present
      (is (not (re-find #"Нет данных за выбранный период" html-str))))))

(deftest marketplace-dashboard-test
  (testing "marketplace-dashboard shows no-data banner when metrics are zero"
    (let [zero-metrics {:revenue 0.0
                        :orders 0
                        :profit 0.0
                        :return-rate 0.0}
          result (dashboard/marketplace-dashboard :wb :last-week :metrics zero-metrics)
          html-str (pr-str result)]
      (is (vector? result))
      ;; Check that no-data banner is present
      (is (re-find #"Нет данных за выбранный период" html-str)))))

;; ---------------------------------------------------------------------------
;; format-number — was rendering 10 944 as "01 449" (digits inside each 3-group
;; were left in reversed form). Regression tests guard the fix.
;; ---------------------------------------------------------------------------

(deftest format-number-regression
  (let [fmt #'analitica.web.pages.dashboard/format-number]
    (testing "≥10k numbers preserve digit order within each group"
      (is (= "10 944" (fmt 10944))   "regression: was '01 449'")
      (is (= "31 449" (fmt 31449))   "regression: was '13 944'")
      (is (= "147 630" (fmt 147630)) "regression: was '741 036'")
      (is (= "320 246" (fmt 320246))))
    (testing "<1k numbers have no separator"
      (is (= "0"   (fmt 0)))
      (is (= "5"   (fmt 5)))
      (is (= "999" (fmt 999))))
    (testing "thousand boundary"
      (is (= "1 000" (fmt 1000)))
      (is (= "9 999" (fmt 9999))))
    (testing "millions"
      (is (= "1 000 000"  (fmt 1000000)))
      (is (= "12 345 678" (fmt 12345678))))
    (testing "negative + nil"
      (is (= "-31 449" (fmt -31449)))
      (is (nil? (fmt nil))))))
