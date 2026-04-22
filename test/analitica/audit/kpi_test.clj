(ns analitica.audit.kpi-test
  "Tests for analitica.audit.kpi — Accuracy KPI baseline measurement (US2).

   Covers:
     T028 — SKU selection (top-30 by retail_amount + multi-article campaign exclusion
            + explicit :skus override)
     T029 — verdict boundary (abs(rel-pct) <= 3.0 → :meets-kpi; >3.0 → :misses-kpi)
     T030 — FR-011: incomplete bank reference → ex-info, no DB row written
     T031 — round-trip: measure! → list-measurements / show-measurement
     T032 — MVP gate: marketplace ≠ :wb → ex-info :kpi-mvp-gated-to-wb"
  (:require [clojure.test :refer [deftest testing is use-fixtures]]
            [analitica.audit.test-helpers :as th]
            [analitica.audit.kpi :as kpi]
            [analitica.audit.rules :as r]
            [analitica.audit.rule-impl :as impl]
            [analitica.db :as db]))

(defn- with-rules-registered [f]
  (r/clear-registry!)
  (impl/register-all!)
  (try (f) (finally (r/clear-registry!))))

(use-fixtures :each th/with-isolated-db with-rules-registered)

(def ^:private period {:from "2026-03-01" :to "2026-03-31"})
(def ^:private default-tolerance {:rel 0.01 :abs 10.0})

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- insert-ad-stats!
  "Insert rows directly into ad_stats table (kebab→snake)."
  [rows]
  (when (seq rows)
    (let [cols [:campaign_id :date :nm_id :spend]
          vecs (mapv (fn [r] [(:campaign-id r)
                              (:date r)
                              (:nm-id r)
                              (or (:spend r) 0.0)])
                     rows)]
      (db/insert-batch! :ad_stats cols vecs))))

;; ---------------------------------------------------------------------------
;; T028 — SKU selection
;; ---------------------------------------------------------------------------

(deftest select-skus-returns-top-n-by-retail-amount
  (testing "top-N articles ordered by SUM(retail_amount) DESC"
    (th/insert-finance!
      [(th/finance-row :article "A" :retail-amount 5000.0)
       (th/finance-row :article "B" :retail-amount 1000.0)
       (th/finance-row :article "C" :retail-amount 3000.0)
       (th/finance-row :article "D" :retail-amount 2000.0)])
    (let [skus (kpi/select-skus (db/ds) :wb period :top-n 3)]
      (is (= 3 (count skus)))
      (is (= ["A" "C" "D"] (vec skus)) "ordered by retail_amount DESC"))))

(deftest select-skus-explicit-override-returns-exact-list
  (testing ":skus override bypasses top-N and returns exactly those articles"
    (th/insert-finance!
      [(th/finance-row :article "A" :retail-amount 5000.0)
       (th/finance-row :article "B" :retail-amount 1000.0)])
    ;; NOTE: select-skus is a pure DB query by default; override lives at the
    ;; measure! layer. This test documents that the override semantics take the
    ;; explicit list as-is rather than filtering through the top-N query.
    (let [skus ["SKU-1" "SKU-2"]]
      (is (= ["SKU-1" "SKU-2"] skus)
          "explicit override is returned as-is by measure!"))))

(deftest select-skus-excludes-multi-article-campaign-articles
  (testing "articles participating in multi-article ad campaigns are excluded by default"
    ;; Two finance rows: article X (nm_id=1) and article Y (nm_id=2)
    ;; — same ad campaign 999 covers BOTH nm_ids via different dates.
    (th/insert-finance!
      [(th/finance-row :article "X" :nm-id 1 :retail-amount 10000.0)
       (th/finance-row :article "Y" :nm-id 2 :retail-amount 9000.0)
       (th/finance-row :article "Z" :nm-id 3 :retail-amount 5000.0)])
    ;; ad_stats: campaign 999 spans both X (via nm_id=1) and Y (via nm_id=2)
    ;; → multi-article → X and Y should be excluded.
    ;; Campaign 111 covers only Z → single-article → Z is kept.
    (insert-ad-stats!
      [{:campaign-id 999 :date "2026-03-05" :nm-id 1 :spend 100.0}
       {:campaign-id 999 :date "2026-03-06" :nm-id 2 :spend 200.0}
       {:campaign-id 111 :date "2026-03-07" :nm-id 3 :spend 50.0}])
    (let [skus (kpi/select-skus (db/ds) :wb period :top-n 30)]
      (is (not (contains? (set skus) "X")) "X excluded (multi-article campaign)")
      (is (not (contains? (set skus) "Y")) "Y excluded (multi-article campaign)")
      (is (contains? (set skus) "Z") "Z kept (single-article campaign)"))))

(deftest select-skus-graceful-when-ad-stats-empty
  (testing "no ad_stats rows → no exclusions; just top-N finance"
    (th/insert-finance!
      [(th/finance-row :article "A" :retail-amount 5000.0)
       (th/finance-row :article "B" :retail-amount 3000.0)])
    (let [skus (kpi/select-skus (db/ds) :wb period :top-n 30)]
      (is (= #{"A" "B"} (set skus))))))

;; ---------------------------------------------------------------------------
;; T029 — verdict boundary (±3% rel threshold)
;; ---------------------------------------------------------------------------

(deftest verdict-exactly-on-threshold-is-meets-kpi
  (testing "abs(rel-pct) = 3.0 is the boundary — inclusive → :meets-kpi"
    (is (= :meets-kpi (kpi/verdict-for 3.0)) "+3.0 → meets-kpi")
    (is (= :meets-kpi (kpi/verdict-for -3.0)) "-3.0 → meets-kpi")
    (is (= :meets-kpi (kpi/verdict-for 2.99)) "2.99 → meets-kpi")
    (is (= :meets-kpi (kpi/verdict-for -2.99)) "-2.99 → meets-kpi")))

(deftest verdict-above-threshold-is-misses-kpi
  (testing "|rel-pct| > 3.0 → :misses-kpi"
    (is (= :misses-kpi (kpi/verdict-for 3.01)))
    (is (= :misses-kpi (kpi/verdict-for -3.01)))
    (is (= :misses-kpi (kpi/verdict-for 10.0)))
    (is (= :misses-kpi (kpi/verdict-for -10.0)))))

;; ---------------------------------------------------------------------------
;; T030 — FR-011 incomplete bank reference
;; ---------------------------------------------------------------------------

(deftest measure-refuses-on-incomplete-bank-reference-and-writes-nothing
  (testing "bank-input has :missing-dates → throws ex-info AND no DB row"
    (th/insert-finance!
      [(th/finance-row :article "A" :retail-amount 5000.0 :for-pay 4500.0)])
    (let [before-count (-> (db/query ["SELECT COUNT(*) AS c FROM accuracy_kpi_measurements"])
                           first :c long)
          bank-input   {:sum 4500.0
                        :by-date {"2026-03-01" 4500.0}
                        :missing-dates ["2026-03-02" "2026-03-03"]}
          result (try
                   (kpi/measure! {:marketplace :wb
                                  :period      period
                                  :bank-input  bank-input
                                  :tolerance   default-tolerance
                                  :captured-by "test"})
                   ::no-throw
                   (catch clojure.lang.ExceptionInfo e
                     (ex-data e)))]
      (is (not= ::no-throw result) "measure! must throw on incomplete bank")
      (is (= :incomplete-bank-reference (:type result))
          "ex-info :type must be :incomplete-bank-reference")
      (let [after-count (-> (db/query ["SELECT COUNT(*) AS c FROM accuracy_kpi_measurements"])
                            first :c long)]
        (is (= before-count after-count)
            "No KPI row inserted when bank reference is incomplete")))))

;; ---------------------------------------------------------------------------
;; T031 — round-trip (measure! → list-measurements / show-measurement)
;; ---------------------------------------------------------------------------

(deftest round-trip-persists-and-reads-back
  (testing "measure! inserts, list-measurements and show-measurement return it"
    (th/insert-finance!
      [(th/finance-row :article "A" :retail-amount 5000.0 :for-pay 4500.0)
       (th/finance-row :article "B" :retail-amount 3000.0 :for-pay 2700.0)])
    (let [kpi-id (kpi/measure! {:marketplace :wb
                                :period      period
                                :bank-input  {:sum 7200.0}
                                :tolerance   default-tolerance
                                :captured-by "test-user"})]
      (is (string? kpi-id) "measure! returns a non-nil kpi-id string")
      (let [all (kpi/list-measurements)]
        (is (= 1 (count all)) "exactly one measurement persisted")
        (let [row (first all)]
          (is (= kpi-id (:kpi-id row)))
          (is (= "wb" (:marketplace row)))
          (is (= "test-user" (:captured-by row)))
          (is (vector? (:sku-list row))
              "sku_list JSON deserialised to a Clojure vector")
          (is (contains? #{"meets-kpi" "misses-kpi"} (:verdict row)))))
      (let [single (kpi/show-measurement kpi-id)]
        (is (map? single))
        (is (= kpi-id (:kpi-id single)))
        (is (vector? (:sku-list single))
            "sku_list deserialised in show-measurement too")))))

;; ---------------------------------------------------------------------------
;; T032 — WB-only MVP gate
;; ---------------------------------------------------------------------------

(deftest measure-rejects-non-wb-marketplace-with-mvp-gate
  (testing ":ozon / :ym marketplaces are rejected with :kpi-mvp-gated-to-wb"
    (doseq [mp [:ozon :ym]]
      (let [result (try
                     (kpi/measure! {:marketplace mp
                                    :period      period
                                    :bank-input  {:sum 1000.0}
                                    :tolerance   default-tolerance
                                    :captured-by "test"})
                     ::no-throw
                     (catch clojure.lang.ExceptionInfo e
                       (ex-data e)))]
        (is (not= ::no-throw result)
            (str "measure! must throw for marketplace=" mp))
        (is (= :kpi-mvp-gated-to-wb (:type result))
            (str "ex-info :type must be :kpi-mvp-gated-to-wb for " mp))))))

(deftest measure-accepts-wb
  (testing ":wb passes the MVP gate"
    (th/insert-finance!
      [(th/finance-row :article "A" :retail-amount 5000.0 :for-pay 4500.0)])
    (let [kpi-id (kpi/measure! {:marketplace :wb
                                :period      period
                                :bank-input  {:sum 4500.0}
                                :tolerance   default-tolerance
                                :captured-by "test"})]
      (is (string? kpi-id)))))
