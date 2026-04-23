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
