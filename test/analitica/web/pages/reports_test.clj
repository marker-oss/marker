(ns analitica.web.pages.reports-test
  (:require [clojure.test :refer [deftest is testing]]
            [analitica.web.pages.reports :as pages]
            [hiccup.core :as h]))

(deftest report-page-reads-from-schema-test
  (testing "report-page uses schema title (Юнит-экономика)"
    (let [html (h/html (pages/report-page :ue "last-30-days" nil))]
      (is (re-find #"Юнит-экономика" html))))

  (testing "report-page works for :pnl (rows-mode :none)"
    (let [html (h/html (pages/report-page :pnl "last-30-days" nil))]
      (is (re-find #"P&amp;L|P&L" html))))

  (testing "report-page for :stock renders"
    (let [html (h/html (pages/report-page :stock "last-30-days" nil))]
      (is (re-find #"Остатки" html)))))

(deftest report-page-with-totals-test
  (testing "report-page renders KPI row when totals provided"
    (let [html (h/html (pages/report-page :ue "last-30-days" nil
                         :totals {:total-revenue 1000 :total-profit 200
                                  :margin-pct 20.0 :drr-pct 8.0}))]
      (is (re-find #"Выручка" html))
      (is (re-find #"1 000" html))
      (is (re-find #"Маржа" html))))

  (testing "report-page renders drawer when totals provided"
    (let [html (h/html (pages/report-page :ue "last-30-days" nil
                         :totals {:total-revenue 1000}))]
      (is (re-find #"Все метрики" html)))))

(deftest report-page-tabs-test
  (testing "UE page has table/chart/drawer tabs"
    (let [html (h/html (pages/report-page :ue "last-30-days" nil :totals {:total-revenue 1}))]
      (is (re-find #"data-tab=\"table\"" html))
      (is (re-find #"data-tab=\"chart\"" html))
      (is (re-find #"data-tab=\"drawer\"" html))))

  (testing "P&L page has no :table tab"
    (let [html (h/html (pages/report-page :pnl "last-30-days" nil :totals {:revenue 1}))]
      (is (re-find #"data-tab=\"chart\"" html))
      (is (not (re-find #"data-tab=\"table\"" html))))))
