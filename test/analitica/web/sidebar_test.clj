(ns analitica.web.sidebar-test
  (:require [clojure.test :refer [deftest is testing]]
            [hiccup.core :refer [html]]
            [analitica.web.layout :as layout]))

;; ---------------------------------------------------------------------------
;; Sidebar structure tests — pure rendering, no DB, no :integration tag
;; ---------------------------------------------------------------------------

(deftest sidebar-renders-6-groups-test
  (testing "nav-items contains exactly 6 top-level groups"
    (is (= 6 (count layout/nav-items))))

  (testing "group labels are the 6 expected sections"
    (let [labels (set (map :label layout/nav-items))]
      (is (contains? labels "Главная"))
      (is (contains? labels "Финансы"))
      (is (contains? labels "Товары"))
      (is (contains? labels "Склады"))
      (is (contains? labels "Управление"))
      (is (contains? labels "План"))))

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
  (testing "rendered sidebar HTML contains all 6 group headings"
    (let [page-html (layout/page "Test" [:div "content"] :active-route "/")]
      (is (re-find #"Главная" page-html))
      (is (re-find #"Финансы" page-html))
      (is (re-find #"Товары" page-html))
      (is (re-find #"Склады" page-html))
      (is (re-find #"Управление" page-html))
      (is (re-find #"План" page-html)))))

(deftest sidebar-active-route-highlighting-test
  (testing "/reports/pnl makes Финансы group <details> open"
    ;; rendered = [:details.mb-1 {:open true} [:summary ...] [:div.ml-4.mt-1 ...]]
    (let [item     (first (filter #(= "Финансы" (:label %)) layout/nav-items))
          rendered (#'layout/nav-item item "/reports/pnl")
          tag      (first rendered)
          attrs    (second rendered)]
      (is (= :details.mb-1 tag)
          "Финансы nav-item should render as :details.mb-1")
      (is (true? (:open attrs))
          "Финансы group should be :open true when /reports/pnl is active")))

  (testing "/reports/pnl makes the P&L child anchor carry bg-blue-500"
    ;; child-div = [:div.ml-4.mt-1 (lazy-seq-of-anchors)]
    ;; the lazy seq is the second element of child-div
    (let [item      (first (filter #(= "Финансы" (:label %)) layout/nav-items))
          rendered  (#'layout/nav-item item "/reports/pnl")
          child-div (nth rendered 3)
          anchors   (second child-div)            ; lazy seq produced by `for`
          pnl-anchor (first (filter #(= "/reports/pnl" (get-in % [1 :href])) anchors))]
      (is (some? pnl-anchor)
          "P&L anchor must exist inside Финансы group")
      (is (re-find #"bg-blue-500" (get-in pnl-anchor [1 :class]))
          "Active child anchor should carry bg-blue-500")))

  (testing "Главная leaf link carries bg-blue-600 when active-route is /"
    ;; rendered = [:div.mb-1 [:a {:href "/" :class "..."} label]]
    (let [item     (first (filter #(= "Главная" (:label %)) layout/nav-items))
          rendered (#'layout/nav-item item "/")
          anchor   (second rendered)
          cls      (get-in anchor [1 :class])]
      (is (re-find #"bg-blue-600" cls)
          "Главная anchor should carry bg-blue-600 when route / is active")))

  (testing "Главная leaf link does NOT carry bg-blue-600 when active-route is /wb"
    (let [item     (first (filter #(= "Главная" (:label %)) layout/nav-items))
          rendered (#'layout/nav-item item "/wb")
          anchor   (second rendered)
          cls      (get-in anchor [1 :class])]
      (is (not (re-find #"bg-blue-600" cls))
          "Главная anchor should not be highlighted when active-route does not match"))))

(deftest homepage-title-test
  (testing "summary-page function exists in dashboard namespace"
    (require 'analitica.web.pages.dashboard)
    (is (fn? (var-get (ns-resolve 'analitica.web.pages.dashboard 'summary-page)))
        "summary-page should be a public function in dashboard ns")))
