(ns analitica.web.pages.digest-test
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [hiccup.core :refer [html]]
            [analitica.web.pages.digest :as digest]))

;; ---------------------------------------------------------------------------
;; Test data
;; ---------------------------------------------------------------------------

(def sample-kpi-data
  {:revenue          4900000
   :net-profit       1200000
   :margin           24.0
   :drr              12.0
   :revenue-delta    0.15
   :net-profit-delta 0.08
   :margin-delta     -0.02
   :drr-delta        0.00
   :revenue-sparkline  [400000 420000 380000 450000 490000 510000 490000]
   :profit-sparkline   [100000 110000  90000 120000 115000 125000 120000]
   :margin-sparkline   [22.0 23.0 21.0 24.5 24.0 25.0 24.0]
   :drr-sparkline      [13.0 12.5 12.0 11.5 12.0 12.0 12.0]})

(def sample-alerts
  [{:rule :OUT_OF_STOCK :severity :red
    :title "Заканчивается: Product A / M"
    :body "Product A — остатков на 3 дней (5 шт/день)"
    :action-route "/reports/stock?article=art-1"
    :action-label "Смотреть остатки"}
   {:rule :MARGIN_DROP :severity :yellow
    :title "Маржа упала на 18%"
    :body "Маржа упала на 18.0% за период (с 30.0% до 12.0%)"
    :action-route "/reports/pnl"
    :action-label "Смотреть P&L"}])

(def sample-movers
  [{:article "art-1" :name "Product A" :revenue 1300000 :prev-revenue 900000 :delta-pct 44.4}
   {:article "art-2" :name "Product B" :revenue 800000  :prev-revenue 500000 :delta-pct 60.0}])

(def sample-fallers
  [{:article "art-3" :name "Product C" :revenue 200000 :prev-revenue 500000 :delta-pct -60.0}])

(def sample-freshness
  {:wb "2026-04-25T18:30:00" :ozon "2026-04-24T12:00:00" :ym nil})

(def sample-daily-revenue
  (mapv (fn [i] {:day (str "2026-04-" (inc i)) :revenue (* 100000 (+ 1 (rand)))})
        (range 30)))

;; ---------------------------------------------------------------------------
;; 2.3 Tests: sparkline SVG rendering
;; ---------------------------------------------------------------------------

(deftest test-sparkline-renders-svg
  (testing "sparkline renders an SVG element"
    (let [result (html (digest/sparkline [10 20 30 40 50]))]
      (is (str/includes? result "<svg")
          "sparkline should render an SVG element")
      (is (str/includes? result "polyline")
          "sparkline should contain a polyline"))))

(deftest test-sparkline-empty-data
  (testing "sparkline handles empty data without NaN or exception"
    (is (some? (digest/sparkline []))
        "sparkline should return something for empty data")
    (let [result (html (digest/sparkline []))]
      (is (not (str/includes? result "NaN"))
          "sparkline should not produce NaN for empty data"))))

(deftest test-sparkline-single-value
  (testing "sparkline handles single value without exception"
    (let [result (html (digest/sparkline [42]))]
      (is (string? result)
          "sparkline should return a string for single value")
      (is (not (str/includes? result "NaN"))
          "sparkline should not produce NaN for single value"))))

(deftest test-sparkline-viewbox
  (testing "sparkline uses 80x24 viewBox"
    (let [result (html (digest/sparkline [1 2 3 4 5]))]
      (is (str/includes? result "80 24")
          "sparkline should use 80 24 viewBox"))))

;; ---------------------------------------------------------------------------
;; 2.3 Tests: metric-card with sparkline data
;; ---------------------------------------------------------------------------

(deftest test-metric-card-renders-with-sparkline
  (testing "kpi-card or metric-card accepts :sparkline-data and renders sparkline"
    (let [result (html (digest/metric-card-with-sparkline
                        {:label "Выручка"
                         :value 4900000
                         :delta 15.0
                         :format :rub
                         :sparkline-data [100 200 150 300 250]}))]
      (is (str/includes? result "Выручка")
          "Card should contain label")
      (is (str/includes? result "svg")
          "Card should contain sparkline SVG"))))

(deftest test-metric-card-renders-without-sparkline
  (testing "metric-card renders gracefully when sparkline-data is empty"
    (let [result (html (digest/metric-card-with-sparkline
                        {:label "Прибыль"
                         :value 1200000
                         :delta 8.0
                         :format :rub
                         :sparkline-data []}))]
      (is (str/includes? result "Прибыль")
          "Card should still contain label with empty sparkline"))))

;; ---------------------------------------------------------------------------
;; 2.5 Tests: alert-card rendering
;; ---------------------------------------------------------------------------

(deftest test-alert-card-renders-red-border
  (testing "alert-card with :red severity gets a red border class"
    (let [alert {:rule :OUT_OF_STOCK :severity :red
                 :title "Low stock" :body "Only 3 days left"
                 :action-route "/reports/stock" :action-label "View"}
          result (html (digest/alert-card alert))]
      (is (str/includes? result "red")
          "Red severity alert should have red styling")
      (is (str/includes? result "Low stock")
          "Alert should contain the title"))))

(deftest test-alert-card-renders-action-button
  (testing "alert-card renders an action button-link"
    (let [alert {:rule :MARGIN_DROP :severity :yellow
                 :title "Margin Drop" :body "Fell 18 pts"
                 :action-route "/reports/pnl" :action-label "View P&L"}
          result (html (digest/alert-card alert))]
      (is (str/includes? result "/reports/pnl")
          "Alert card should contain action route")
      (is (str/includes? result "View P&L")
          "Alert card should contain action label"))))

;; ---------------------------------------------------------------------------
;; 2.4 Tests: top-movers and top-fallers tables
;; ---------------------------------------------------------------------------

(deftest test-top-movers-table-renders
  (testing "top-movers-table renders a table with article data"
    (let [result (html (digest/top-movers-table sample-movers))]
      (is (str/includes? result "Product A")
          "Should render article name")
      (is (str/includes? result "+")
          "Should show positive delta"))))

(deftest test-top-fallers-table-renders
  (testing "top-fallers-table renders a table with negative delta"
    (let [result (html (digest/top-fallers-table sample-fallers))]
      (is (str/includes? result "Product C")
          "Should render article name")
      (is (str/includes? result "-")
          "Should show negative delta"))))

;; ---------------------------------------------------------------------------
;; 2.6 Tests: freshness panel
;; ---------------------------------------------------------------------------

(deftest test-freshness-panel-renders
  (testing "freshness-panel renders marketplace freshness info"
    (let [result (html (digest/freshness-panel sample-freshness))]
      (is (str/includes? result "WB")
          "Should mention WB")
      (is (str/includes? result "Ozon")
          "Should mention Ozon"))))

(deftest test-freshness-panel-shows-old-wb-warning
  (testing "freshness-panel shows lag warning when WB data is old (>6 days)"
    ;; 10 days old
    (let [old-date (-> (java.time.LocalDate/now) (.minusDays 10) str)
          freshness {:wb (str old-date "T12:00:00") :ozon nil :ym nil}
          result (html (digest/freshness-panel freshness))]
      (is (or (str/includes? result "отстаёт")
              (str/includes? result "дней"))
          "Should show lag warning for old WB data"))))

;; ---------------------------------------------------------------------------
;; 2.7/2.8 Tests: page renders correct structure
;; ---------------------------------------------------------------------------

(deftest test-page-renders-kpi-tiles
  (testing "digest/render-page produces KPI tiles section"
    (let [result (html (digest/render-page
                        {:kpi            sample-kpi-data
                         :alerts         sample-alerts
                         :movers         sample-movers
                         :fallers        sample-fallers
                         :freshness      sample-freshness
                         :from           "2026-03-27"
                         :to             "2026-04-25"
                         :daily-revenue  sample-daily-revenue}))]
      (is (str/includes? result "digest-page")
          "Page should have digest-page wrapper")
      (is (str/includes? result "Выручка")
          "Page should contain revenue KPI tile"))))

(deftest test-page-renders-alerts-section
  (testing "digest/render-page produces alerts section with DOM ID"
    (let [result (html (digest/render-page
                        {:kpi       sample-kpi-data
                         :alerts    sample-alerts
                         :movers    []
                         :fallers   []
                         :freshness sample-freshness
                         :from      "2026-03-27"
                         :to        "2026-04-25"
                         :daily-revenue []}))]
      (is (str/includes? result "digest-alerts")
          "Page should have #digest-alerts section")
      (is (str/includes? result "Заканчивается")
          "Page should render OUT_OF_STOCK alert"))))

(deftest test-page-renders-movers-fallers
  (testing "digest/render-page renders movers and fallers sections"
    (let [result (html (digest/render-page
                        {:kpi       sample-kpi-data
                         :alerts    []
                         :movers    sample-movers
                         :fallers   sample-fallers
                         :freshness sample-freshness
                         :from      "2026-03-27"
                         :to        "2026-04-25"
                         :daily-revenue []}))]
      (is (str/includes? result "digest-movers")
          "Page should have #digest-movers section")
      (is (str/includes? result "Топ")
          "Page should have top-movers heading"))))
