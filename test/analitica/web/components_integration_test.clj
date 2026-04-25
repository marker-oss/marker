(ns analitica.web.components-integration-test
  "Integration tests for components with layout."
  (:require [clojure.test :refer [deftest is testing]]
            [analitica.web.components :as c]
            [analitica.web.layout :as layout]
            [hiccup.core :refer [html]]))

(deftest components-in-layout-test
  (testing "metric-card renders in page layout"
    (let [card (c/metric-card {:title "Выручка"
                                :value 1250000
                                :unit "₽"
                                :delta 12.5})
          page (layout/page "Test" [:div card] :active-route "/")
          html-str (html page)]
      (is (re-find #"Выручка" html-str))
      (is (re-find #"1250000" html-str))
      (is (re-find #"↑" html-str))))

  (testing "chart-container renders in page layout"
    (let [chart (c/chart-container {:id "test-chart"
                                     :type "line"
                                     :title "Test Chart"
                                     :api-url "/api/test"})
          page (layout/page "Test" [:div chart] :active-route "/")
          html-str (html page)]
      (is (re-find #"Test Chart" html-str))
      (is (re-find #"<canvas" html-str))
      (is (re-find #"new Chart" html-str))))

  (testing "tabulator-table renders in page layout"
    (let [table (c/tabulator-table {:id "test-table"
                                     :api-url "/api/report/sales"
                                     :columns [{:title "Артикул" :field "article"}
                                               {:title "Выручка" :field "revenue"}]})
          page (layout/page "Test" [:div table] :active-route "/reports/sales")
          html-str (html page)]
      (is (re-find #"new Tabulator" html-str))
      (is (re-find #"/api/report/sales" html-str))))

  (testing "sync-log renders in page layout"
    (let [log (c/sync-log {})
          page (layout/page "Sync" [:div log] :active-route "/sync")
          html-str (html page)]
      (is (re-find #"Прогресс синхронизации" html-str))
      (is (re-find #"hx-ext=\"sse\"" html-str))
      (is (re-find #"/api/sync/stream" html-str))))

  (testing "data-coverage-bar renders in page layout"
    (let [bar (c/data-coverage-bar {:label "WB Sales"
                                     :filled-days 25
                                     :total-days 30})
          page (layout/page "Sync" [:div bar] :active-route "/sync")
          html-str (html page)]
      (is (re-find #"WB Sales" html-str))
      (is (re-find #"25/30" html-str))))

  (testing "multiple components render together"
    (let [content [:div
                   (c/metric-card {:title "Выручка" :value 1000000 :unit "₽"})
                   (c/metric-card {:title "Заказы" :value 500})
                   (c/chart-container {:id "chart1" :type "line" :api-url "/api/chart/sales"})
                   (c/tabulator-table {:id "table1" :api-url "/api/report/sales"
                                       :columns [{:title "ID" :field "id"}]})]
          page (layout/page "Dashboard" content :active-route "/")
          html-str (html page)]
      (is (re-find #"Выручка" html-str))
      (is (re-find #"Заказы" html-str))
      (is (re-find #"new Chart" html-str))
      (is (re-find #"new Tabulator" html-str)))))
