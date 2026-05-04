(ns marker.pages.products.stocks-test
  "Pure helpers for the «Склады» tab (Phase 2 of the SPA UI restructure)."
  (:require [cljs.test :refer [deftest is testing]]
            [marker.pages.products.stocks :as stocks]))

(deftest days-status-thresholds
  (testing "nil → ok"
    (is (= "ok" (stocks/days->status nil))))
  (testing "below 7 → danger"
    (is (= "danger" (stocks/days->status 0)))
    (is (= "danger" (stocks/days->status 3)))
    (is (= "danger" (stocks/days->status 6))))
  (testing "7-13 → warning"
    (is (= "warning" (stocks/days->status 7)))
    (is (= "warning" (stocks/days->status 13))))
  (testing "14+ → success"
    (is (= "success" (stocks/days->status 14)))
    (is (= "success" (stocks/days->status 30)))
    (is (= "success" (stocks/days->status 999)))))

(deftest sort-warehouses-by-quantity-full-desc
  (testing "rows sorted by quantity-full descending"
    (let [rows [{:warehouse "A" :quantity-full 5}
                {:warehouse "B" :quantity-full 50}
                {:warehouse "C" :quantity-full 20}]]
      (is (= ["B" "C" "A"]
             (mapv :warehouse (stocks/sort-warehouses rows))))))
  (testing "missing :quantity-full treated as 0"
    (let [rows [{:warehouse "A"}
                {:warehouse "B" :quantity-full 10}
                {:warehouse "C"}]]
      (is (= "B" (-> (stocks/sort-warehouses rows) first :warehouse))))))
