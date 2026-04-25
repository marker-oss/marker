(ns analitica.web.pages.dashboard-integration-test
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [analitica.db :as db]
            [analitica.web.pages.dashboard :as dashboard]
            [clojure.string :as str]))

(use-fixtures :once
  (fn [f]
    (db/init!)
    (f)))

(deftest summary-dashboard-integration-test
  (testing "summary-dashboard renders complete HTML structure"
    (let [result (dashboard/summary-dashboard :last-week)
          html-str (str result)]
      
      ;; Check for metrics container with HTMX attributes
      (is (str/includes? html-str "metrics-container"))
      (is (str/includes? html-str "hx-get"))
      (is (str/includes? html-str "/api/metrics"))
      (is (str/includes? html-str "hx-trigger"))
      (is (str/includes? html-str "every 5m"))
      
      ;; Check for metric cards
      (is (str/includes? html-str "Выручка"))
      (is (str/includes? html-str "Заказы"))
      (is (str/includes? html-str "Прибыль"))
      (is (str/includes? html-str "Процент возвратов"))
      
      ;; Check for marketplace comparison table
      (is (str/includes? html-str "Сравнение маркетплейсов"))
      
      ;; Check for charts
      (is (str/includes? html-str "sales-chart"))
      (is (str/includes? html-str "share-chart"))
      (is (str/includes? html-str "Динамика продаж"))
      (is (str/includes? html-str "Доли маркетплейсов"))
      (is (str/includes? html-str "/api/chart/sales"))
      (is (str/includes? html-str "/api/chart/share"))))
  
  (testing "summary-dashboard includes period parameter in API URLs"
    (let [result (dashboard/summary-dashboard {:from "2026-04-01" :to "2026-04-30"})
          html-str (str result)]
      
      ;; Check that period is included in API URLs
      (is (str/includes? html-str "period=2026-04-01,2026-04-30"))))
  
  (testing "summary-dashboard with keyword period"
    (let [result (dashboard/summary-dashboard :last-30-days)
          html-str (str result)]
      
      ;; Check that keyword period is converted to string
      (is (str/includes? html-str "period=last-30-days")))))

