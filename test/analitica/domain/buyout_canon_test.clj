(ns analitica.domain.buyout-canon-test
  "Per-metric-group tests anchored to canonical-formulas.md §Buyout.

   Every deftest maps to one Buyout.N block in the canon. If canon changes,
   this file changes in lockstep.

   Data model note: the `sales` table is a pure activity log — one row per
   unit event. This fixture uses in-memory maps; no finance rows, no DB.
   Buyout functions are unit-count-based: monetary fields are ignored.

   The public `analyze` fn composes internal grouping logic with
   `sales/fetch-sales`. Tests stub `fetch-sales` via `with-redefs` to avoid
   DB dependency, as recommended in §Buyout.4."
  (:require [clojure.test :refer [deftest is testing]]
            [analitica.domain.buyout :as buyout]
            [analitica.domain.sales :as sales]
            [analitica.util.math :as math]))

;; ---------------------------------------------------------------------------
;; Shared fixture: 5 articles, designed for clean integer assertions.
;;
;; Article A: 8 sales + 2 returns → ordered=10, buyout-rate=80.0%
;; Article B: 3 sales + 3 returns → ordered=6,  buyout-rate=50.0% (low: ordered≥3, rate<70)
;; Article C: 10 sales + 0 returns → ordered=10, buyout-rate=100.0%
;; Article D: 1 sale  + 0 returns → ordered=1,  buyout-rate=100.0% (NOT low: ordered < 3)
;; Article E: 1 sale  + 4 returns → ordered=5,  buyout-rate=20.0%  (low: ordered≥3, rate<70)
;;
;; Period totals:
;;   total-o = 10+6+10+1+5 = 32
;;   total-b = 8+3+10+1+1  = 23
;;   total-r = 2+3+0+0+4   = 9
;;   overall = math/percentage(23, 32) = 71.88%
;; ---------------------------------------------------------------------------

(def fx
  "Inline sales/return rows. Monetary :for-pay present but should NOT affect counts."
  [;; Article A — 8 sales
   {:date "2026-03-01T08:00:00" :type :sale   :article "A" :subject "shirts" :for-pay 800.0}
   {:date "2026-03-01T09:00:00" :type :sale   :article "A" :subject "shirts" :for-pay 800.0}
   {:date "2026-03-01T10:00:00" :type :sale   :article "A" :subject "shirts" :for-pay 800.0}
   {:date "2026-03-05T08:00:00" :type :sale   :article "A" :subject "shirts" :for-pay 800.0}
   {:date "2026-03-05T09:00:00" :type :sale   :article "A" :subject "shirts" :for-pay 800.0}
   {:date "2026-03-05T10:00:00" :type :sale   :article "A" :subject "shirts" :for-pay 800.0}
   {:date "2026-03-10T08:00:00" :type :sale   :article "A" :subject "shirts" :for-pay 800.0}
   {:date "2026-03-10T09:00:00" :type :sale   :article "A" :subject "shirts" :for-pay 800.0}
   ;; Article A — 2 returns
   {:date "2026-03-01T15:00:00" :type :return :article "A" :subject "shirts" :for-pay -800.0}
   {:date "2026-03-15T11:00:00" :type :return :article "A" :subject "shirts" :for-pay -800.0}
   ;; Article B — 3 sales + 3 returns (low buyout, qualifies for low filter)
   {:date "2026-03-02T08:00:00" :type :sale   :article "B" :subject "pants"  :for-pay 500.0}
   {:date "2026-03-02T09:00:00" :type :sale   :article "B" :subject "pants"  :for-pay 500.0}
   {:date "2026-03-02T10:00:00" :type :sale   :article "B" :subject "pants"  :for-pay 500.0}
   {:date "2026-03-03T08:00:00" :type :return :article "B" :subject "pants"  :for-pay -500.0}
   {:date "2026-03-03T09:00:00" :type :return :article "B" :subject "pants"  :for-pay -500.0}
   {:date "2026-03-03T10:00:00" :type :return :article "B" :subject "pants"  :for-pay -500.0}
   ;; Article C — 10 sales, 0 returns (perfect buyout)
   {:date "2026-03-04T08:00:00" :type :sale   :article "C" :subject "hats"   :for-pay 300.0}
   {:date "2026-03-04T09:00:00" :type :sale   :article "C" :subject "hats"   :for-pay 300.0}
   {:date "2026-03-04T10:00:00" :type :sale   :article "C" :subject "hats"   :for-pay 300.0}
   {:date "2026-03-04T11:00:00" :type :sale   :article "C" :subject "hats"   :for-pay 300.0}
   {:date "2026-03-04T12:00:00" :type :sale   :article "C" :subject "hats"   :for-pay 300.0}
   {:date "2026-03-06T08:00:00" :type :sale   :article "C" :subject "hats"   :for-pay 300.0}
   {:date "2026-03-06T09:00:00" :type :sale   :article "C" :subject "hats"   :for-pay 300.0}
   {:date "2026-03-06T10:00:00" :type :sale   :article "C" :subject "hats"   :for-pay 300.0}
   {:date "2026-03-06T11:00:00" :type :sale   :article "C" :subject "hats"   :for-pay 300.0}
   {:date "2026-03-06T12:00:00" :type :sale   :article "C" :subject "hats"   :for-pay 300.0}
   ;; Article D — 1 sale only (ordered < 3 — NOT flagged low even though rate would allow it)
   {:date "2026-03-07T08:00:00" :type :sale   :article "D" :subject "socks"  :for-pay 100.0}
   ;; Article E — 1 sale + 4 returns → 20% buyout (low: ordered=5 ≥ 3, rate < 70)
   {:date "2026-03-08T08:00:00" :type :sale   :article "E" :subject "gloves" :for-pay 200.0}
   {:date "2026-03-08T09:00:00" :type :return :article "E" :subject "gloves" :for-pay -200.0}
   {:date "2026-03-08T10:00:00" :type :return :article "E" :subject "gloves" :for-pay -200.0}
   {:date "2026-03-08T11:00:00" :type :return :article "E" :subject "gloves" :for-pay -200.0}
   {:date "2026-03-08T12:00:00" :type :return :article "E" :subject "gloves" :for-pay -200.0}])

(defn- find-article [rows article]
  (first (filter #(= article (:article %)) rows)))

;; ---------------------------------------------------------------------------
;; Buyout.1 — analyze computes buyout-rate correctly
;; ---------------------------------------------------------------------------

(deftest analyze-computes-buyout-rate
  (with-redefs [sales/fetch-sales (fn [_period & _opts] fx)]
    (let [rows (buyout/analyze [:last-7-days])
          a    (find-article rows "A")
          b    (find-article rows "B")
          c    (find-article rows "C")]

      (testing "Article A: 8 sold, 2 returned, ordered=10, buyout-rate=80.0%"
        (is (= 8  (:bought a)))
        (is (= 2  (:returned a)))
        (is (= 10 (:ordered a)))
        (is (= 80.0 (:buyout-rate a))))

      (testing "Article B: 3 sold, 3 returned, ordered=6, buyout-rate=50.0%"
        (is (= 3  (:bought b)))
        (is (= 3  (:returned b)))
        (is (= 6  (:ordered b)))
        (is (= 50.0 (:buyout-rate b))))

      (testing "Article C: 10 sold, 0 returned, ordered=10, buyout-rate=100.0%"
        (is (= 10  (:bought c)))
        (is (= 0   (:returned c)))
        (is (= 10  (:ordered c)))
        (is (= 100.0 (:buyout-rate c)))))))

;; ---------------------------------------------------------------------------
;; Buyout.1 — analyze sorts ascending by buyout-rate (worst first)
;; ---------------------------------------------------------------------------

(deftest analyze-sorts-ascending-worst-first
  (with-redefs [sales/fetch-sales (fn [_period & _opts] fx)]
    (let [rows (buyout/analyze [:last-7-days])]

      (testing "produces 5 rows (one per article)"
        (is (= 5 (count rows))))

      (testing "first row has lowest buyout-rate (E = 20.0%)"
        (is (= "E" (:article (first rows)))))

      (testing "second row is B (50.0%)"
        (is (= "B" (:article (second rows)))))

      (testing "last row — one of the 100% articles (C or D)"
        (let [last-rate (:buyout-rate (last rows))]
          (is (= 100.0 last-rate)))))))

;; ---------------------------------------------------------------------------
;; Buyout.2 — buyout-rate + (100 - buyout-rate) = 100 for all articles
;; ---------------------------------------------------------------------------

(deftest buyout-plus-return-equals-100-algebraically
  ;; Verifies the complementarity identity: buyout-rate and return-rate sum to 100.
  ;; We verify algebraically (no cross-ns coupling) that for every row with non-nil
  ;; buyout-rate, the complement (100 - buyout-rate) reconstructs the return-rate.
  (with-redefs [sales/fetch-sales (fn [_period & _opts] fx)]
    (let [rows (buyout/analyze [:last-7-days])]
      (doseq [row (remove #(nil? (:buyout-rate %)) rows)]
        (let [br    (:buyout-rate row)
              rr    (math/round2 (- 100.0 br))
              total (:ordered row)
              rets  (:returned row)
              expected-rr (math/percentage rets total)]
          (testing (str "Article " (:article row) ": buyout-rate=" br " → return-rate=" rr)
            (is (= rr expected-rr))))))))

;; ---------------------------------------------------------------------------
;; Buyout.6.1 — :ordered is total ops (sold + returned), NOT orders placed
;; ---------------------------------------------------------------------------

(deftest ordered-is-total-ops-not-orders
  ;; Guards against renaming drift. :ordered must always equal :bought + :returned.
  (with-redefs [sales/fetch-sales (fn [_period & _opts] fx)]
    (let [rows (buyout/analyze [:last-7-days])]
      (doseq [row rows]
        (testing (str "Article " (:article row) ": :ordered = :bought + :returned")
          (is (= (:ordered row)
                 (+ (:bought row) (:returned row)))))))))

;; ---------------------------------------------------------------------------
;; Buyout.3 — report low-filter: ordered≥3 AND (or buyout-rate 100) < 70
;; ---------------------------------------------------------------------------

(deftest report-low-filter-threshold
  ;; The filter logic is in `report`, not `analyze`. We replicate it here
  ;; against the analyze output to verify the threshold behaviour.
  (with-redefs [sales/fetch-sales (fn [_period & _opts] fx)]
    (let [rows (buyout/analyze [:last-7-days])
          low  (filter #(and (>= (:ordered %) 3)
                             (< (or (:buyout-rate %) 100) 70))
                       rows)
          low-articles (set (map :article low))]

      (testing "Article B (ordered=6, rate=50%) is in low"
        (is (contains? low-articles "B")))

      (testing "Article E (ordered=5, rate=20%) is in low"
        (is (contains? low-articles "E")))

      (testing "Article D (ordered=1, rate=100%) is NOT in low (ordered < 3)"
        (is (not (contains? low-articles "D"))))

      (testing "Article A (ordered=10, rate=80%) is NOT in low (rate ≥ 70)"
        (is (not (contains? low-articles "A"))))

      (testing "Article C (ordered=10, rate=100%) is NOT in low"
        (is (not (contains? low-articles "C"))))

      (testing "exactly 2 articles flagged low (B and E)"
        (is (= 2 (count low)))))))

;; ---------------------------------------------------------------------------
;; Buyout.3 / Buyout.6.4 — nil buyout-rate (zero-op article) excluded from low
;; ---------------------------------------------------------------------------

(deftest nil-buyout-rate-excluded-from-low
  ;; An article that appears in the sales table with 0 events (impossible via
  ;; normal ingest, but possible if analyzed against an empty filtered set)
  ;; gets buyout-rate = nil from math/percentage(0, 0).
  ;; The (or nil 100) guard treats nil as 100 → NOT flagged low.
  (let [zero-op-row {:article "Z" :subject "zero" :ordered 0 :bought 0
                     :returned 0 :buyout-rate nil}
        rows [zero-op-row]
        low  (filter #(and (>= (:ordered %) 3)
                           (< (or (:buyout-rate %) 100) 70))
                     rows)]
    (testing "zero-op article with nil buyout-rate is NOT in low (ordered < 3)"
      (is (empty? low)))

    ;; Also verify the (or 100) guard independently for an article with ordered≥3 but nil rate
    (let [nil-rate-row {:article "Z2" :subject "nil-rate" :ordered 5 :bought 0
                        :returned 5 :buyout-rate nil}
          low2 (filter #(and (>= (:ordered %) 3)
                             (< (or (:buyout-rate %) 100) 70))
                       [nil-rate-row])]
      (testing "(or nil 100) = 100 → NOT < 70 → nil-rate article with ordered≥3 excluded from low"
        (is (empty? low2))))))

;; ---------------------------------------------------------------------------
;; Buyout.1 edge case — empty input returns empty seq
;; ---------------------------------------------------------------------------

(deftest empty-input-returns-empty
  (with-redefs [sales/fetch-sales (fn [_period & _opts] [])]
    (let [rows (buyout/analyze [:last-7-days])]
      (testing "analyze on empty sales returns empty seq, not nil"
        (is (empty? rows)))
      (testing "result has zero elements"
        (is (= 0 (count rows)))))))
