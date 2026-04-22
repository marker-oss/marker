(ns analitica.web.layout-test
  (:require [clojure.test :refer [deftest is testing]]
            [analitica.web.layout :as layout]))

(deftest ^:integration page-test
  (testing "page function generates valid HTML structure"
    (let [html (layout/page "Test Page" [:div "Test Content"])]
      (is (string? html))
      (is (re-find #"<!DOCTYPE html>" html))
      (is (re-find #"Test Page - Analitica" html))
      (is (re-find #"Test Content" html))))
  
  (testing "page includes all required CDN resources"
    (let [html (layout/page "Test" [:div "Content"])]
      (is (re-find #"cdn.tailwindcss.com" html))
      (is (re-find #"unpkg.com/htmx.org" html))
      (is (re-find #"chart.js" html))
      (is (re-find #"tabulator-tables" html))))
  
  (testing "page includes sidebar navigation"
    (let [html (layout/page "Test" [:div "Content"])]
      (is (re-find #"Дашборд" html))
      (is (re-find #"Отчёты" html))
      (is (re-find #"Синхронизация" html))))
  
  (testing "page includes header elements"
    (let [html (layout/page "Test" [:div "Content"])]
      (is (re-find #"Период:" html))
      (is (re-find #"Sync All" html))
      (is (re-find #"Последняя синхронизация" html))))
  
  (testing "page highlights active route"
    (let [html (layout/page "Test" [:div "Content"] :active-route "/wb")]
      (is (re-find #"bg-blue-600" html))
      (is (string? html))))
  
  (testing "page includes period selector options"
    (let [html (layout/page "Test" [:div "Content"])]
      (is (re-find #"Прошлая неделя" html))
      (is (re-find #"Последние 7 дней" html))
      (is (re-find #"Последние 30 дней" html))
      (is (re-find #"Этот месяц" html))
      (is (re-find #"Произвольный диапазон" html)))))
