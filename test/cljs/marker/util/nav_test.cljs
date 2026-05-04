(ns marker.util.nav-test
  "Tests for marker.util.nav — pure helpers for SPA navigation
   (section/tab structure, legacy URL redirects)."
  (:require [cljs.test :refer [deftest is testing]]
            [marker.util.nav :as nav]))

;; ---------------------------------------------------------------------------
;; legacy-redirect
;; ---------------------------------------------------------------------------

(deftest legacy-redirect-target
  (testing "old :pnl maps to [:finance :pnl]"
    (is (= [:finance :pnl] (nav/legacy-redirect :pnl))))
  (testing "old :unit maps to [:finance :unit-calc]"
    (is (= [:finance :unit-calc] (nav/legacy-redirect :unit))))
  (testing "old :cost-prices maps to [:products :cost-prices]"
    (is (= [:products :cost-prices] (nav/legacy-redirect :cost-prices))))
  (testing "old [:report :ue] maps to [:finance :unit-table]"
    (is (= [:finance :unit-table] (nav/legacy-redirect [:report :ue]))))
  (testing "old [:report :stock] maps to [:products :stocks]"
    (is (= [:products :stocks] (nav/legacy-redirect [:report :stock]))))
  (testing "old [:report :returns] maps to [:finance :returns]"
    (is (= [:finance :returns] (nav/legacy-redirect [:report :returns]))))
  (testing "old [:report :abc] maps to [:products :abc]"
    (is (= [:products :abc] (nav/legacy-redirect [:report :abc]))))
  (testing "old [:report :trends] maps to [:dynamics :trends]"
    (is (= [:dynamics :trends] (nav/legacy-redirect [:report :trends]))))
  (testing "unknown route returns nil"
    (is (nil? (nav/legacy-redirect :nonexistent)))))

;; ---------------------------------------------------------------------------
;; section-tabs
;; ---------------------------------------------------------------------------

(deftest section-tabs-listing
  (testing "finance section lists 7 tabs in order"
    (is (= [:pnl :unit-calc :unit-table :returns :losses :finance :plan-fact]
           (mapv :id (nav/section-tabs :finance)))))
  (testing "products section lists 5 tabs in order"
    (is (= [:skus :stocks :abc :cost-prices :storage]
           (mapv :id (nav/section-tabs :products)))))
  (testing "dynamics section lists 4 tabs in order"
    (is (= [:trends :sales :geo :buyout]
           (mapv :id (nav/section-tabs :dynamics)))))
  (testing "unknown section returns empty"
    (is (empty? (nav/section-tabs :nonsense))))
  (testing "all tabs have :id and :label"
    (doseq [section [:finance :products :dynamics]]
      (doseq [t (nav/section-tabs section)]
        (is (keyword? (:id t))    (str section " tab missing :id"))
        (is (string?  (:label t)) (str section " tab missing :label"))))))

;; ---------------------------------------------------------------------------
;; default-tab
;; ---------------------------------------------------------------------------

(deftest default-tab-for-section
  (testing "finance defaults to :pnl"
    (is (= :pnl (nav/default-tab :finance))))
  (testing "products defaults to :skus"
    (is (= :skus (nav/default-tab :products))))
  (testing "dynamics defaults to :trends"
    (is (= :trends (nav/default-tab :dynamics))))
  (testing "single-page section returns nil"
    (is (nil? (nav/default-tab :pulse))))
  (testing "unknown section returns nil"
    (is (nil? (nav/default-tab :nonsense)))))

;; ---------------------------------------------------------------------------
;; valid-tab?
;; ---------------------------------------------------------------------------

(deftest valid-tab-check
  (testing "valid combinations return true"
    (is (true? (nav/valid-tab? :finance :pnl)))
    (is (true? (nav/valid-tab? :products :stocks)))
    (is (true? (nav/valid-tab? :dynamics :buyout))))
  (testing "invalid tab in valid section returns false"
    (is (false? (nav/valid-tab? :finance :stocks)))
    (is (false? (nav/valid-tab? :products :pnl))))
  (testing "invalid section returns false"
    (is (false? (nav/valid-tab? :nonsense :foo)))))
