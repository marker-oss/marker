(ns analitica.web.components.sku-sheet-test
  (:require [clojure.test :refer [deftest is testing]]
            [analitica.web.components.sku-sheet :as sheet]
            [hiccup.core :refer [html]]))

(def ^:private sample-summary
  {:article       "DRESS-3452"
   :nm-id         145673821
   :sales-count   124
   :returns-count 8
   :revenue       125000.0
   :cogs          75000.0
   :margin-pct    40.0
   :roi           1.67
   :daily-revenue [{:date "2026-04-01" :revenue 4000.0}
                   {:date "2026-04-02" :revenue 3500.0}
                   {:date "2026-04-03" :revenue 5000.0}]
   :recent-ops    [{:date "2026-04-26" :type "sale"   :marketplace "wb"   :amount 890.0}
                   {:date "2026-04-26" :type "return" :marketplace "wb"   :amount -890.0}
                   {:date "2026-04-25" :type "sale"   :marketplace "ozon" :amount 1200.0}]})

(deftest render-smoke
  (testing "render returns a non-empty HTML string"
    (let [html (sheet/render sample-summary :from "2026-04-01" :to "2026-04-30")]
      (is (string? html))
      (is (pos? (count html))))))

(deftest render-article-present
  (testing "article name appears in output"
    (let [html (sheet/render sample-summary :from "2026-04-01" :to "2026-04-30")]
      (is (re-find #"DRESS-3452" html)))))

(deftest render-nm-id-present
  (testing "nm-id appears in output when provided"
    (let [html (sheet/render sample-summary :from "2026-04-01" :to "2026-04-30")]
      (is (re-find #"145673821" html)))))

(deftest render-kpis
  (testing "all 4 KPI labels are present"
    (let [html (sheet/render sample-summary :from "2026-04-01" :to "2026-04-30")]
      (is (re-find #"Продажи" html))
      (is (re-find #"Возвраты" html))
      (is (re-find #"Маржа" html))
      (is (re-find #"ROI" html)))))

(deftest render-kpi-values
  (testing "KPI numeric values appear in output"
    (let [html (sheet/render sample-summary :from "2026-04-01" :to "2026-04-30")]
      (is (re-find #"124" html))    ; sales-count
      (is (re-find #"8" html))     ; returns-count
      (is (re-find #"40.0%" html)) ; margin-pct
      (is (re-find #"1.67x" html))))) ; roi

(deftest render-zero-kpis
  (testing "zeros render as 0, not blank"
    (let [empty-summary {:article "X" :nm-id nil
                         :sales-count 0 :returns-count 0
                         :revenue 0.0 :cogs 0.0
                         :margin-pct 0.0 :roi 0.0
                         :daily-revenue [] :recent-ops []}
          html (sheet/render empty-summary)]
      (is (re-find #">0<" html)))))

(deftest render-sparkline
  (testing "sparkline SVG is present when daily-revenue has data"
    (let [html (sheet/render sample-summary :from "2026-04-01" :to "2026-04-30")]
      (is (re-find #"<svg" html))
      (is (re-find #"polyline" html)))))

(deftest render-sparkline-empty
  (testing "no sparkline crash when daily-revenue is empty"
    (let [s    (assoc sample-summary :daily-revenue [])
          html (sheet/render s)]
      (is (string? html))
      (is (re-find #"Нет данных" html)))))

(deftest render-cross-links
  (testing "cross-report links to main reports are present"
    (let [html (sheet/render sample-summary :from "2026-04-01" :to "2026-04-30")]
      (is (re-find #"/reports/sales" html))
      (is (re-find #"/reports/ue" html))
      (is (re-find #"/reports/returns" html))
      (is (re-find #"/reports/stock" html)))))

(deftest render-ops-table
  (testing "recent ops entries appear"
    (let [html (sheet/render sample-summary :from "2026-04-01" :to "2026-04-30")]
      (is (re-find #"Продажа" html))
      (is (re-find #"Возврат" html))
      (is (re-find #"2026-04-26" html)))))

(deftest render-not-found
  (testing "render-not-found returns a Hiccup vector"
    (let [result (sheet/render-not-found "ABC-999")]
      (is (vector? result))))
  (testing "render-not-found HTML contains identifier and not-found label"
    (let [html-str (html (sheet/render-not-found "ABC-999"))]
      (is (string? html-str))
      (is (re-find #"ABC-999" html-str))
      (is (re-find #"не найден" html-str)))))

(deftest render-period-label
  (testing "period appears in output when from/to provided"
    (let [html (sheet/render sample-summary :from "2026-04-01" :to "2026-04-30")]
      (is (re-find #"2026-04-01" html))
      (is (re-find #"2026-04-30" html)))))
