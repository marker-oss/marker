(ns analitica.web.components-test
  (:require [clojure.test :refer [deftest is testing]]
            [analitica.web.components :as c]
            [hiccup.core :refer [html]]))

(deftest metric-card-test
  (testing "metric-card renders basic structure"
    (let [result (c/metric-card {:title "Выручка"
                                  :value 1250000
                                  :unit "₽"})]
      (is (vector? result))
      (is (= :div.bg-white.rounded-lg.shadow.p-6 (first result)))))

  (testing "metric-card with positive delta"
    (let [result (c/metric-card {:title "Выручка"
                                  :value 1250000
                                  :unit "₽"
                                  :delta 12.5})
          html-str (html result)]
      (is (re-find #"↑" html-str))
      (is (re-find #"text-green-600" html-str))))

  (testing "metric-card with negative delta"
    (let [result (c/metric-card {:title "Возвраты"
                                  :value 5.2
                                  :unit "%"
                                  :delta -2.3})
          html-str (html result)]
      (is (re-find #"↓" html-str))
      (is (re-find #"text-red-600" html-str))))

  (testing "metric-card without delta"
    (let [result (c/metric-card {:title "Заказы"
                                  :value 1500})]
      (is (vector? result))
      ;; Should not contain delta elements
      (is (not (re-find #"WoW" (html result)))))))

(deftest chart-container-test
  (testing "chart-container renders canvas and script"
    (let [result (c/chart-container {:id "test-chart"
                                      :type "line"
                                      :title "Test Chart"
                                      :api-url "/api/test"})
          html-str (html result)]
      (is (re-find #"<canvas" html-str))
      (is (re-find #"id=\"test-chart\"" html-str))
      (is (re-find #"new Chart" html-str))
      (is (re-find #"/api/test" html-str))))

  (testing "chart-container without title"
    (let [result (c/chart-container {:id "chart2"
                                      :type "bar"
                                      :api-url "/api/data"})]
      (is (vector? result)))))

(deftest tabulator-table-test
  (testing "tabulator-table renders container and script"
    (let [result (c/tabulator-table {:id "test-table"
                                      :api-url "/api/report/sales"
                                      :columns [{:title "Артикул" :field "article"}
                                                {:title "Выручка" :field "revenue"}]})
          html-str (html result)]
      (is (re-find #"id=\"test-table\"" html-str))
      (is (re-find #"new Tabulator" html-str))
      (is (re-find #"/api/report/sales" html-str))))

  (testing "tabulator-table with frozen columns"
    (let [result (c/tabulator-table {:id "frozen-table"
                                      :api-url "/api/data"
                                      :columns [{:title "ID" :field "id"}
                                                {:title "Name" :field "name"}]
                                      :frozen-cols 1})
          html-str (html result)]
      (is (re-find #"frozen" html-str)))))

(deftest period-selector-test
  (testing "period-selector renders select element"
    (let [result (c/period-selector {:current-period "last-week"})
          html-str (html result)]
      (is (re-find #"<select" html-str))
      (is (re-find #"last-week" html-str))
      (is (re-find #"hx-get" html-str))))

  (testing "period-selector with custom target"
    (let [result (c/period-selector {:target "#custom-target"})
          html-str (html result)]
      (is (re-find #"#custom-target" html-str)))))

(deftest sync-log-test
  (testing "sync-log renders SSE container"
    (let [result (c/sync-log {})
          html-str (html result)]
      (is (re-find #"hx-ext=\"sse\"" html-str))
      (is (re-find #"sse-connect" html-str))
      (is (re-find #"/api/sync/stream" html-str))))

  (testing "sync-log with custom stream URL"
    (let [result (c/sync-log {:stream-url "/custom/stream"})
          html-str (html result)]
      (is (re-find #"/custom/stream" html-str)))))

(deftest data-coverage-bar-test
  (testing "data-coverage-bar renders progress bar"
    (let [result (c/data-coverage-bar {:label "WB Sales"
                                        :filled-days 25
                                        :total-days 30})
          html-str (html result)]
      (is (re-find #"WB Sales" html-str))
      (is (re-find #"25/30" html-str))))

  (testing "data-coverage-bar calculates percentage correctly"
    (let [result (c/data-coverage-bar {:label "Test"
                                        :filled-days 90
                                        :total-days 100})
          html-str (html result)]
      (is (re-find #"90\.0%" html-str))
      (is (re-find #"bg-green-500" html-str))))

  (testing "data-coverage-bar with low coverage shows red"
    (let [result (c/data-coverage-bar {:label "Test"
                                        :filled-days 10
                                        :total-days 100})
          html-str (html result)]
      (is (re-find #"bg-red-500" html-str))))

  (testing "data-coverage-bar with date range"
    (let [result (c/data-coverage-bar {:label "Test"
                                        :filled-days 20
                                        :total-days 30
                                        :date-from "2026-04-01"
                                        :date-to "2026-04-30"})
          html-str (html result)]
      (is (re-find #"2026-04-01" html-str))
      (is (re-find #"2026-04-30" html-str)))))

(deftest kpi-card-test
  (testing "renders title and value"
    (let [html (hiccup.core/html
                (c/kpi-card {:title "Выручка" :value 1830000 :format :rub}))]
      (is (re-find #"Выручка" html))
      (is (re-find #"1 830 000" html))))

  (testing "renders delta up arrow for positive"
    (let [html (hiccup.core/html
                (c/kpi-card {:title "X" :value 100 :format :rub :delta 12.4}))]
      (is (re-find #"↑" html))
      (is (re-find #"text-green" html))))

  (testing "inverted direction — positive delta shows red"
    (let [html (hiccup.core/html
                (c/kpi-card {:title "ДРР" :value 8.2 :format :pct
                             :delta 2.5 :delta-direction :inverted}))]
      (is (re-find #"text-red" html)))))
