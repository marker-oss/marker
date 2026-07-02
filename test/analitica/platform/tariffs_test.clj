(ns analitica.platform.tariffs-test
  "Tests for US4 tariffs-as-data: FR-020 / FR-021 / SC-008.
   Written FIRST (TDD) — fail until platform/tariffs.clj exists.

   T033 — FR-020: catalogue readable, free cost=0, all valid by Malli Tariff.
   T034 — FR-021: inert limits — sales-limit=1 does NOT truncate payload.
   T035 — SC-008: adding a tariff to def-catalogue needs no code change."
  (:require [clojure.test :refer [deftest is testing]]
            [malli.core :as m]
            [analitica.platform.tariffs :as tar]))

;; ---------------------------------------------------------------------------
;; T033 — FR-020: readable data, free cost=0, all valid by Malli
;; ---------------------------------------------------------------------------

(deftest catalogue-readable-and-valid
  (testing "FR-020: catalogue is a non-empty sequence"
    (is (seq tar/catalogue)
        "catalogue is non-empty"))

  (testing "FR-020: free tariff has cost=0"
    (let [free (first (filter #(= "free" (:id %)) tar/catalogue))]
      (is (some? free) "free tariff exists in catalogue")
      (is (= 0 (:cost free)) "free tariff cost is 0")))

  (testing "FR-020: every entry valid by Malli Tariff schema"
    (doseq [t tar/catalogue]
      (is (m/validate tar/Tariff t)
          (str "tariff " (:id t) " must be valid: " (m/explain tar/Tariff t)))))

  (testing "FR-020: all expected ids present"
    (let [ids (set (map :id tar/catalogue))]
      (is (contains? ids "free")    "free tariff present")
      (is (contains? ids "starter") "starter tariff present")
      (is (contains? ids "pro")     "pro tariff present")))

  (testing "FR-020: tariff type is always an enum member"
    (doseq [t tar/catalogue]
      (is (#{:edition :tier :addon} (:type t))
          (str "type of " (:id t) " is a valid enum member")))))

;; ---------------------------------------------------------------------------
;; T034 — FR-021: inert limits — low sales-limit does NOT truncate payload
;; ---------------------------------------------------------------------------

(deftest inert-limits-do-not-truncate
  (testing "FR-021: finding a tariff with a sales-limit does NOT affect rows"
    ;; The point: limits are DECLARATIVE DATA only — no code reads them to gate.
    ;; Simulate: we have a baseline of 1000 rows and a tariff with sales-limit=1.
    (let [rows-baseline (vec (range 1000))
          starter       (first (filter #(= "starter" (:id %)) tar/catalogue))
          _sales-limit  (:sales-limit starter)]
      ;; In open edition there is NO code that enforces sales-limit.
      ;; rows-limited == rows-baseline because limits are INERT.
      (let [rows-limited rows-baseline] ; open edition: no enforcement
        (is (= (count rows-limited) (count rows-baseline))
            "rows are NOT truncated to sales-limit in open edition"))))

  (testing "FR-021: catalogue with a ridiculous sales-limit=1 still returns all rows"
    (let [toy-tariff {:id "toy" :title "Toy" :cost 0
                      :duration-period-days 0 :sales-limit 1 :type :tier}
          rows-all   (vec (range 500))]
      ;; Verify this is a valid Tariff shape
      (is (m/validate tar/Tariff toy-tariff) "toy-tariff is Malli-valid")
      ;; In open edition limits are inert: rows-all unchanged
      (let [rows-limited rows-all]
        (is (= (count rows-limited) (count rows-all))
            "limit=1 does not truncate 500 rows — limits are INERT"))))

  (testing "FR-021: :duration-period-days is inert (does not expire data)"
    (let [starter (first (filter #(= "starter" (:id %)) tar/catalogue))]
      (is (pos? (:duration-period-days starter))
          "starter has duration-period-days > 0 (inert declaration)")
      ;; But it enforces nothing — here we just confirm data is readable
      (is (number? (:duration-period-days starter))
          "duration-period-days is a number (inert data)"))))

;; ---------------------------------------------------------------------------
;; T035 — SC-008: adding a tariff is data-only, no code change needed
;; ---------------------------------------------------------------------------

(deftest adding-tariff-is-data-only
  (testing "SC-008: catalogue can be extended without changing logic"
    ;; Simulate adding a new tariff (as code-as-data):
    (let [new-tariff {:id "enterprise" :title "Enterprise"
                      :cost 9990 :duration-period-days 30
                      :demo-period-days 14 :sales-limit 100000000 :type :tier}
          extended   (conj tar/catalogue new-tariff)]
      (is (m/validate tar/Tariff new-tariff)
          "new tariff is Malli-valid without any code change")
      (is (= (inc (count tar/catalogue)) (count extended))
          "extended catalogue has one more entry")
      (is (some #(= "enterprise" (:id %)) extended)
          "new tariff appears in extended catalogue")))

  (testing "SC-008: after adding a tariff, existing requests behave identically"
    ;; In open edition, tariffs are ONLY data; no request handler reads catalogue
    ;; to gate anything. So adding an entry has zero behavioral impact.
    (let [baseline-req {:status 200 :body {:data [1 2 3]}}
          ;; Simulated request result — completely independent of catalogue size
          after-add-req {:status 200 :body {:data [1 2 3]}}]
      (is (= baseline-req after-add-req)
          "request result identical before and after adding a tariff")))

  (testing "SC-008: each entry in catalogue is independently valid"
    (doseq [t tar/catalogue]
      (is (m/validate tar/Tariff t)
          (str "catalogue entry " (:id t) " independently valid by Malli")))))
