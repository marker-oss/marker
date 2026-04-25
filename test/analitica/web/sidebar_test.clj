(ns analitica.web.sidebar-test
  (:require [clojure.test :refer [deftest is testing]]
            [hiccup.core :refer [html]]
            [analitica.web.layout :as layout]))

;; ---------------------------------------------------------------------------
;; Sidebar structure tests — pure rendering, no DB, no :integration tag
;; ---------------------------------------------------------------------------

(deftest sidebar-renders-5-groups-test
  (testing "nav-items contains exactly 5 top-level groups"
    (is (= 5 (count layout/nav-items))))

  (testing "group labels are the 5 expected sections"
    (let [labels (set (map :label layout/nav-items))]
      (is (contains? labels "Главная"))
      (is (contains? labels "Финансы"))
      (is (contains? labels "Товары"))
      (is (contains? labels "Склады"))
      (is (contains? labels "Управление"))))

  (testing "each report appears in exactly one group"
    (let [all-child-routes (mapcat (fn [item]
                                     (map :route (:children item)))
                                   layout/nav-items)
          ;; reports that should be present
          expected #{"/reports/pnl" "/reports/ue" "/reports/finance" "/reports/returns"
                     "/reports/sales" "/reports/abc" "/reports/trends" "/reports/buyout"
                     "/reports/geo" "/reports/stock"}]
      ;; all expected routes exist exactly once
      (doseq [r expected]
        (is (= 1 (count (filter #{r} all-child-routes)))
            (str r " should appear exactly once across all groups")))))

  (testing "Финансы group contains P&L, UE, Finance details, Returns"
    (let [fin (first (filter #(= "Финансы" (:label %)) layout/nav-items))
          routes (set (map :route (:children fin)))]
      (is (contains? routes "/reports/pnl"))
      (is (contains? routes "/reports/ue"))
      (is (contains? routes "/reports/finance"))
      (is (contains? routes "/reports/returns"))))

  (testing "Товары group contains Sales, ABC, Trends, Buyout, Geo"
    (let [goods (first (filter #(= "Товары" (:label %)) layout/nav-items))
          routes (set (map :route (:children goods)))]
      (is (contains? routes "/reports/sales"))
      (is (contains? routes "/reports/abc"))
      (is (contains? routes "/reports/trends"))
      (is (contains? routes "/reports/buyout"))
      (is (contains? routes "/reports/geo"))))

  (testing "Склады group contains Stock"
    (let [stock (first (filter #(= "Склады" (:label %)) layout/nav-items))
          routes (set (map :route (:children stock)))]
      (is (contains? routes "/reports/stock"))))

  (testing "Управление group contains Sync"
    (let [mgmt (first (filter #(= "Управление" (:label %)) layout/nav-items))
          routes (set (map :route (:children mgmt)))]
      (is (contains? routes "/sync"))))

  (testing "Главная group has route /"
    (let [home (first (filter #(= "Главная" (:label %)) layout/nav-items))]
      (is (= "/" (:route home))))))

(deftest sidebar-html-contains-5-group-labels-test
  (testing "rendered sidebar HTML contains all 5 group headings"
    (let [page-html (layout/page "Test" [:div "content"] :active-route "/")]
      (is (re-find #"Главная" page-html))
      (is (re-find #"Финансы" page-html))
      (is (re-find #"Товары" page-html))
      (is (re-find #"Склады" page-html))
      (is (re-find #"Управление" page-html)))))

(deftest sidebar-active-route-highlighting-test
  (testing "active route /reports/pnl highlights the Финансы group as open"
    (let [page-html (layout/page "Test" [:div "content"] :active-route "/reports/pnl")]
      ;; The <details> for Финансы should have open attribute
      (is (re-find #"(?s)Финансы.*?open|open.*?Финансы" page-html)
          "Финансы group should be open when /reports/pnl is active")))

  (testing "active route /reports/pnl shows P&L item highlighted"
    (let [page-html (layout/page "Test" [:div "content"] :active-route "/reports/pnl")]
      (is (re-find #"bg-blue-500" page-html)
          "Active child item should have highlight class")))

  (testing "active route / highlights Главная"
    (let [page-html (layout/page "Test" [:div "content"] :active-route "/")]
      (is (re-find #"bg-blue-600" page-html)
          "Active top-level item should have highlight class"))))

(deftest homepage-title-test
  (testing "summary-page function exists in dashboard namespace"
    (require 'analitica.web.pages.dashboard)
    (is (fn? (var-get (ns-resolve 'analitica.web.pages.dashboard 'summary-page)))
        "summary-page should be a public function in dashboard ns")))
