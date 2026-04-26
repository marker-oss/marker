(ns analitica.web.pages.reports-test
  (:require [clojure.test :refer [deftest is testing]]
            [analitica.web.pages.reports :as pages]
            [hiccup.core :as h]))

;; Access private fn via var
(def ^:private schema-col->tabulator #'pages/schema-col->tabulator)

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

(deftest report-page-compare-test
  (testing "KPI row renders delta when :compare payload provided"
    (let [html (h/html
                 (pages/report-page :ue "last-30-days" nil
                   :totals {:total-revenue 1100 :total-profit 220 :margin-pct 20.0 :drr-pct 8.0}
                   :compare {:totals {:total-revenue 1000 :total-profit 200 :margin-pct 20.0 :drr-pct 8.5}}))]
      ;; Revenue delta: (1100-1000)/1000 = 10% → up arrow + text-green
      (is (re-find #"↑" html))
      (is (re-find #"text-green" html)))))

;; ---------------------------------------------------------------------------
;; Bug-fix test: :linkable? propagated through schema-col->tabulator
;; ---------------------------------------------------------------------------

(deftest schema-col->tabulator-propagates-linkable-test
  (testing "schema-col->tabulator preserves :linkable? true"
    (let [col {:key :article :title "Артикул" :format :text
               :default-visible? true :linkable? true}
          result (schema-col->tabulator col)]
      (is (true? (:linkable? result))
          ":linkable? must be present in the tabulator column map")))

  (testing "schema-col->tabulator does not set :linkable? when absent"
    (let [col {:key :revenue :title "Выручка" :format :rub :default-visible? true}
          result (schema-col->tabulator col)]
      (is (nil? (:linkable? result))
          ":linkable? must be absent when not set in the schema column")))

  (testing "report-page for :ue renders sku-link formatter in HTML (end-to-end)"
    ;; enrich-column emits a JS formatter containing 'sku-link' when :linkable? true
    (let [html (h/html (pages/report-page :ue "last-30-days" nil))]
      (is (re-find #"sku-link" html)
          "Tabulator column config must contain sku-link formatter for :linkable? article column"))))
