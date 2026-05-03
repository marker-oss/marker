(ns analitica.web.api.report-compare-test
  "Tests for Phase 3: universal compare-mode.
   Covers enrich-with-compare, report-data compare shape,
   schema supports-compare? flags, and delta column injection."
  (:require [clojure.test :refer [deftest is testing]]
            [analitica.web.api.report :as report]
            [analitica.web.report-schemas :as rs]))

;; ---------------------------------------------------------------------------
;; 3.1 — Schema supports-compare? flags
;; ---------------------------------------------------------------------------

(deftest pnl-supports-compare-test
  (testing "P&L schema has :supports-compare? true"
    (is (true? (:supports-compare? (rs/get-schema :pnl))))))

(deftest abc-supports-compare-test
  (testing "ABC schema has :supports-compare? true"
    (is (true? (:supports-compare? (rs/get-schema :abc))))))

(deftest trends-supports-compare-false-test
  (testing "Trends schema remains :supports-compare? false — it is inherently compare-based"
    (is (false? (:supports-compare? (rs/get-schema :trends))))))

(deftest snapshot-reports-no-compare-test
  (testing "Snapshot reports (stock, geo, losses) keep :supports-compare? false"
    (is (false? (:supports-compare? (rs/get-schema :stock))))
    (is (false? (:supports-compare? (rs/get-schema :geo))))
    (is (false? (:supports-compare? (rs/get-schema :losses))))))

;; ---------------------------------------------------------------------------
;; 3.2 — report-data compare shape
;; ---------------------------------------------------------------------------

(deftest report-data-compare-shape-ue-test
  (testing "report-data :ue with :compare :prev returns {:rows :totals :compare {:rows :totals}}"
    (let [result (report/report-data :ue {:from "2026-04-01" :to "2026-04-30"}
                                     :compare :prev)]
      (is (map? result))
      (is (contains? result :rows))
      (is (contains? result :totals))
      (is (contains? result :compare))
      (is (map? (:compare result)))
      (is (contains? (:compare result) :rows))
      (is (contains? (:compare result) :totals)))))

(deftest report-data-compare-shape-pnl-test
  (testing "report-data :pnl with :compare :prev returns correct shape"
    (let [result (report/report-data :pnl {:from "2026-04-01" :to "2026-04-30"}
                                     :compare :prev)]
      (is (contains? result :compare))
      (is (map? (:compare result)))
      (is (contains? (:compare result) :totals)))))

(deftest report-data-compare-shape-abc-test
  (testing "report-data :abc with :compare :prev returns correct shape"
    (let [result (report/report-data :abc {:from "2026-04-01" :to "2026-04-30"}
                                     :compare :prev)]
      (is (contains? result :compare))
      (is (map? (:compare result))))))

(deftest report-data-no-compare-key-by-default-test
  (testing "report-data without :compare does not return :compare key"
    (doseq [rt [:pnl :abc :ue :finance :sales]]
      (let [result (report/report-data rt {:from "2026-04-01" :to "2026-04-30"})]
        (is (not (contains? result :compare))
            (str "no :compare key for " rt " without compare param"))))))

;; ---------------------------------------------------------------------------
;; 3.3 — enrich-with-compare helper
;; ---------------------------------------------------------------------------

(deftest enrich-with-compare-basic-test
  (testing "enrich-with-compare adds _prev/_delta/_delta_pct columns"
    (let [current [{:article "A" :revenue 1000.0 :sales-qty 10}
                   {:article "B" :revenue 500.0  :sales-qty 5}
                   {:article "C" :revenue 300.0  :sales-qty 3}]
          prev    [{:article "A" :revenue 800.0  :sales-qty 8}
                   {:article "B" :revenue 600.0  :sales-qty 6}]
          result  (report/enrich-with-compare current prev :article [:revenue :sales-qty])]
      (is (= 3 (count result)))

      ;; Article A — has prev match
      (let [a (first result)]
        (is (= 800.0 (:revenue_prev a)))
        (is (= 200.0 (:revenue_delta a)))
        (is (= 25.0 (:revenue_delta_pct a)))
        (is (= 8 (:sales-qty_prev a)))
        ;; delta is computed as float (rounded), even for integer columns
        (is (= 2.0 (:sales-qty_delta a)))
        (is (= 25.0 (:sales-qty_delta_pct a))))

      ;; Article B — prev > current (negative delta)
      (let [b (second result)]
        (is (= 600.0 (:revenue_prev b)))
        (is (= -100.0 (:revenue_delta b)))
        ;; delta_pct = 100 * -100 / 600 = -16.67 (rounded to 2 decimals)
        (is (some? (:revenue_delta_pct b))))

      ;; Article C — no prev match
      (let [c (nth result 2)]
        (is (nil? (:revenue_prev c)))
        (is (nil? (:revenue_delta c)))
        (is (nil? (:revenue_delta_pct c)))))))

;; Bug #12: docstring promises `100 × delta / |prev|`, but the impl divided
;; by raw `prev`. With negative prev (e.g. profit improving from -1000 to
;; -500) the sign flipped and the user saw "-50%" instead of "+50%". These
;; tests pin the abs-of-prev semantics across both signs.

(deftest enrich-with-compare-negative-prev-improvement
  (testing "Loss shrinking (prev=-1000 → curr=-500) shows positive delta_pct"
    (let [current [{:article "A" :profit -500.0}]
          prev    [{:article "A" :profit -1000.0}]
          result  (report/enrich-with-compare current prev :article [:profit])
          row     (first result)]
      (is (= 500.0 (:profit_delta row)))
      (is (= 50.0  (:profit_delta_pct row))
          "Improvement of 500 over a loss of 1000 = +50%, not -50%"))))

(deftest enrich-with-compare-negative-prev-deterioration
  (testing "Loss growing (prev=-500 → curr=-1000) shows negative delta_pct"
    (let [current [{:article "A" :profit -1000.0}]
          prev    [{:article "A" :profit -500.0}]
          result  (report/enrich-with-compare current prev :article [:profit])
          row     (first result)]
      (is (= -500.0 (:profit_delta row)))
      (is (= -100.0 (:profit_delta_pct row))
          "Loss doubled (worsened by another 500 over a 500 base) = -100%"))))

(deftest enrich-with-compare-negative-prev-crossing-zero
  (testing "Sign flip (prev=-200 → curr=300) — improvement of 500 over base 200 = +250%"
    (let [current [{:article "A" :profit 300.0}]
          prev    [{:article "A" :profit -200.0}]
          result  (report/enrich-with-compare current prev :article [:profit])
          row     (first result)]
      (is (= 500.0 (:profit_delta row)))
      (is (= 250.0 (:profit_delta_pct row))))))

(deftest enrich-with-compare-zero-prev-test
  (testing "enrich-with-compare: delta_pct is nil when prev value is zero"
    (let [current [{:article "X" :revenue 100.0}]
          prev    [{:article "X" :revenue 0.0}]
          result  (report/enrich-with-compare current prev :article [:revenue])]
      (is (nil? (:revenue_delta_pct (first result))))
      ;; delta itself is computable
      (is (= 100.0 (:revenue_delta (first result)))))))

(deftest enrich-with-compare-empty-prev-test
  (testing "enrich-with-compare with empty prev returns rows with nil delta fields"
    (let [current [{:article "A" :revenue 500.0}]
          result  (report/enrich-with-compare current [] :article [:revenue])]
      (is (= 1 (count result)))
      (is (nil? (:revenue_prev (first result))))
      (is (nil? (:revenue_delta (first result))))
      (is (nil? (:revenue_delta_pct (first result)))))))

(deftest enrich-with-compare-rounding-test
  (testing "enrich-with-compare rounds delta to 2 decimal places"
    (let [current [{:article "A" :revenue 100.0}]
          prev    [{:article "A" :revenue 300.0}]
          result  (report/enrich-with-compare current prev :article [:revenue])]
      (let [r (first result)]
        ;; delta = -200.0, delta_pct = -66.67
        (is (= -200.0 (:revenue_delta r)))
        (is (= -66.67 (:revenue_delta_pct r)))))))

;; ---------------------------------------------------------------------------
;; 3.4 — delta-supported? column flags
;; ---------------------------------------------------------------------------

(deftest ue-delta-supported-columns-test
  (testing "UE schema has delta-supported? on revenue, profit, sales-qty"
    (let [cols (into {} (map (juxt :key :delta-supported?) (:columns (rs/get-schema :ue))))]
      (is (true? (get cols :revenue)))
      (is (true? (get cols :profit)))
      (is (true? (get cols :sales-qty))))))

(deftest finance-delta-supported-columns-test
  (testing "Finance schema has delta-supported? on revenue, for-pay, sales-qty"
    (let [cols (into {} (map (juxt :key :delta-supported?) (:columns (rs/get-schema :finance))))]
      (is (true? (get cols :revenue)))
      (is (true? (get cols :for-pay)))
      (is (true? (get cols :sales-qty))))))

(deftest abc-delta-supported-columns-test
  (testing "ABC schema has delta-supported? on revenue and sales-qty"
    (let [cols (into {} (map (juxt :key :delta-supported?) (:columns (rs/get-schema :abc))))]
      (is (true? (get cols :revenue)))
      (is (true? (get cols :sales-qty))))))

(deftest sales-delta-supported-columns-test
  (testing "Sales schema has delta-supported? on revenue and sales-count"
    (let [cols (into {} (map (juxt :key :delta-supported?) (:columns (rs/get-schema :sales))))]
      (is (true? (get cols :revenue)))
      (is (true? (get cols :sales-count))))))

(deftest non-numeric-cols-not-delta-supported-test
  (testing "Identity (text) columns are never delta-supported"
    (doseq [rt [:ue :finance :abc :sales :returns :buyout]]
      (let [schema (rs/get-schema rt)
            text-cols (filter #(= :text (:format %)) (:columns schema))]
        (doseq [c text-cols]
          (is (not (true? (:delta-supported? c)))
              (str "text col " (:key c) " in " rt " should not be delta-supported")))))))

;; ---------------------------------------------------------------------------
;; 3.5 — Enriched-rows smoke test (unit — no DB needed)
;; ---------------------------------------------------------------------------

(deftest enrich-with-compare-revenue-delta-test
  (testing "enrich-with-compare injects :revenue_delta for matching article rows"
    (let [current-rows [{:article "ART-1" :revenue 1200.0 :profit 400.0 :sales-qty 12}
                        {:article "ART-2" :revenue 800.0  :profit 200.0 :sales-qty 8}]
          prev-rows    [{:article "ART-1" :revenue 1000.0 :profit 300.0 :sales-qty 10}]
          result       (report/enrich-with-compare current-rows prev-rows :article
                                                   [:revenue :profit :sales-qty])]
      ;; ART-1 matches prev — should have revenue_delta injected
      (let [art1 (first result)]
        (is (= 200.0 (:revenue_delta art1))
            "ART-1 revenue delta should be 1200 - 1000 = 200")
        (is (some? (:revenue_delta_pct art1))
            "ART-1 revenue_delta_pct should be computed")
        (is (= 1000.0 (:revenue_prev art1))
            "ART-1 revenue_prev should be the previous period value"))
      ;; ART-2 has no prev match — delta fields should be nil
      (let [art2 (second result)]
        (is (nil? (:revenue_delta art2))
            "ART-2 has no prev row, revenue_delta must be nil")
        (is (nil? (:revenue_prev art2))
            "ART-2 has no prev row, revenue_prev must be nil")))))
