(ns analitica.freshness-test
  "Unit tests for analitica.freshness — stale-data detection.
   All tests use the pure stale-info* arity that accepts a pre-fetched
   last-syncs map, so no DB access is needed."
  (:require [clojure.test :refer [deftest is testing]]
            [analitica.freshness :as f]))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- iso-days-ago
  "Return an ISO datetime string for N days before 2026-04-26 (test today)."
  [n]
  (let [base  (java.time.LocalDate/of 2026 4 26)
        d     (.minusDays base n)]
    (str d "T03:40:00")))

(def ^:private today "2026-04-26")

;; ---------------------------------------------------------------------------
;; 1. report-data-sources — 12 report types
;; ---------------------------------------------------------------------------

(deftest report-data-sources-all-types
  (testing "All 12 report types map to at least one data source"
    (doseq [rt [:sales :orders :finance :ue :pnl :abc
                :stock :returns :buyout :geo :trends :losses]]
      (let [srcs (f/report-data-sources rt)]
        (is (seq srcs) (str "Report " rt " should have at least one data source"))
        (is (every? keyword? srcs) (str "Sources for " rt " should be keywords")))))

  (testing "sales -> :sales only"
    (is (= #{:sales} (set (f/report-data-sources :sales)))))

  (testing "orders -> :orders only"
    (is (= #{:orders} (set (f/report-data-sources :orders)))))

  (testing "finance -> :finance only"
    (is (= #{:finance} (set (f/report-data-sources :finance)))))

  (testing "ue -> :sales + :finance + :stats (broadest)"
    (is (= #{:sales :finance :stats} (set (f/report-data-sources :ue)))))

  (testing "pnl -> :finance only"
    (is (= #{:finance} (set (f/report-data-sources :pnl)))))

  (testing "abc -> :finance + :sales"
    (is (= #{:finance :sales} (set (f/report-data-sources :abc)))))

  (testing "stock -> :stocks"
    (is (= #{:stocks} (set (f/report-data-sources :stock)))))

  (testing "returns -> :finance + :sales"
    (is (= #{:finance :sales} (set (f/report-data-sources :returns)))))

  (testing "buyout -> :finance + :sales"
    (is (= #{:finance :sales} (set (f/report-data-sources :buyout)))))

  (testing "geo -> :sales"
    (is (= #{:sales} (set (f/report-data-sources :geo)))))

  (testing "trends -> :sales + :finance"
    (is (= #{:sales :finance} (set (f/report-data-sources :trends)))))

  (testing "losses -> :finance + :stocks"
    (is (= #{:finance :stocks} (set (f/report-data-sources :losses))))))

;; ---------------------------------------------------------------------------
;; 2. stale-info — fresh (all within threshold) returns :ok
;; ---------------------------------------------------------------------------

(deftest stale-info-fresh-test
  (testing "When last sync is 1 day ago for :wb/:sales (threshold 2), status is :ok"
    (let [last-syncs {[:wb :sales] (iso-days-ago 1)}
          result (f/stale-info* {:report :sales :marketplace :wb
                                  :today today}
                                 last-syncs)]
      (is (= :ok (:status result)))))

  (testing "WB finance synced 6 days ago (threshold 7) → :ok"
    (let [last-syncs {[:wb :finance] (iso-days-ago 6)}
          result (f/stale-info* {:report :pnl :marketplace :wb
                                  :today today}
                                 last-syncs)]
      (is (= :ok (:status result)))))

  (testing "All sources fresh → :ok with no reason"
    (let [last-syncs {[:wb :finance] (iso-days-ago 3)
                      [:wb :sales]   (iso-days-ago 1)}
          result (f/stale-info* {:report :abc :marketplace :wb
                                  :today today}
                                 last-syncs)]
      (is (= :ok (:status result))))))

;; ---------------------------------------------------------------------------
;; 3. stale-info — stale finance WB
;; ---------------------------------------------------------------------------

(deftest stale-info-stale-finance-wb-test
  (testing "WB finance synced 10 days ago (threshold 7) → :stale"
    (let [last-syncs {[:wb :finance] (iso-days-ago 10)}
          result (f/stale-info* {:report :pnl :marketplace :wb
                                  :today today}
                                 last-syncs)]
      (is (= :stale (:status result)))
      (is (string? (:reason result)))
      (is (re-find #"WB" (:reason result)))
      (is (= 10 (:age-days result)))
      (is (= [:wb :finance] (:worst-pair result)))
      (is (= 7 (:max-lag-days result)))))

  (testing "Stale result includes last-sync timestamp"
    (let [ts       (iso-days-ago 10)
          last-syncs {[:wb :finance] ts}
          result (f/stale-info* {:report :pnl :marketplace :wb
                                  :today today}
                                 last-syncs)]
      (is (= ts (:last-sync result))))))

;; ---------------------------------------------------------------------------
;; 4. Boundary: age = max-lag-days exactly → NOT stale (strict >)
;; ---------------------------------------------------------------------------

(deftest stale-info-boundary-not-stale-test
  (testing "Age exactly = threshold is NOT stale"
    ;; WB finance threshold = 7, so age=7 should NOT be stale
    (let [last-syncs {[:wb :finance] (iso-days-ago 7)}
          result (f/stale-info* {:report :pnl :marketplace :wb
                                  :today today}
                                 last-syncs)]
      (is (= :ok (:status result)))))

  (testing "Age one day over threshold IS stale"
    (let [last-syncs {[:wb :finance] (iso-days-ago 8)}
          result (f/stale-info* {:report :pnl :marketplace :wb
                                  :today today}
                                 last-syncs)]
      (is (= :stale (:status result))))))

;; ---------------------------------------------------------------------------
;; 5. Marketplace filter — :ym marketplace doesn't flag WB staleness
;; ---------------------------------------------------------------------------

(deftest stale-info-respects-marketplace-filter
  (testing "When :marketplace :ym, WB stale finance is not flagged"
    (let [last-syncs {[:wb :finance] (iso-days-ago 15) ; WB stale
                      [:ym :finance] (iso-days-ago 1)}  ; YM fresh
          result (f/stale-info* {:report :pnl :marketplace :ym
                                  :today today}
                                 last-syncs)]
      (is (= :ok (:status result)))))

  (testing "When :marketplace :ym, YM stale finance IS flagged"
    (let [last-syncs {[:wb :finance] (iso-days-ago 1) ; WB fresh
                      [:ym :finance] (iso-days-ago 5)} ; YM stale (threshold 2)
          result (f/stale-info* {:report :pnl :marketplace :ym
                                  :today today}
                                 last-syncs)]
      (is (= :stale (:status result)))
      (is (= [:ym :finance] (:worst-pair result))))))

;; ---------------------------------------------------------------------------
;; 6. :all / nil marketplace → checks all 3 MPs, returns worst
;; ---------------------------------------------------------------------------

(deftest stale-info-all-marketplace-returns-worst
  (testing ":all marketplace checks all 3 MPs"
    (let [last-syncs {[:wb  :finance] (iso-days-ago 1)   ; WB fresh
                      [:ozon :finance] (iso-days-ago 35)  ; Ozon stale (threshold 30)
                      [:ym  :finance] (iso-days-ago 1)}   ; YM fresh
          result (f/stale-info* {:report :pnl :marketplace :all
                                  :today today}
                                 last-syncs)]
      (is (= :stale (:status result)))
      (is (= [:ozon :finance] (:worst-pair result)))
      (is (= 35 (:age-days result)))))

  (testing "nil marketplace behaves same as :all"
    (let [last-syncs {[:wb  :finance] (iso-days-ago 1)
                      [:ozon :finance] (iso-days-ago 35)
                      [:ym  :finance] (iso-days-ago 1)}
          result (f/stale-info* {:report :pnl :marketplace nil
                                  :today today}
                                 last-syncs)]
      (is (= :stale (:status result)))
      (is (= [:ozon :finance] (:worst-pair result)))))

  (testing "All-MPs check picks the most stale pair"
    ;; WB finance 10 days ago (threshold 7 → age-excess 3)
    ;; Ozon finance 35 days ago (threshold 30 → age-excess 5)
    ;; Ozon should win as worst
    (let [last-syncs {[:wb   :finance] (iso-days-ago 10)
                      [:ozon :finance] (iso-days-ago 35)
                      [:ym   :finance] (iso-days-ago 1)}
          result (f/stale-info* {:report :pnl :marketplace :all
                                  :today today}
                                 last-syncs)]
      (is (= :stale (:status result)))
      (is (= [:ozon :finance] (:worst-pair result))))))

;; ---------------------------------------------------------------------------
;; 7. Missing last-sync (nil) → treated as stale (unknown = stale)
;; ---------------------------------------------------------------------------

(deftest stale-info-nil-last-sync-is-stale
  (testing "Nil last-sync means never synced → stale"
    (let [last-syncs {[:wb :finance] nil}
          result (f/stale-info* {:report :pnl :marketplace :wb
                                  :today today}
                                 last-syncs)]
      (is (= :stale (:status result))))))

;; ---------------------------------------------------------------------------
;; 8. Multi-source report: any stale source → report stale
;; ---------------------------------------------------------------------------

(deftest stale-info-multi-source-any-stale
  (testing "UE report: finance stale even if sales fresh → stale overall"
    (let [last-syncs {[:wb :sales]   (iso-days-ago 1)    ; fresh
                      [:wb :finance] (iso-days-ago 10)   ; stale (threshold 7)
                      [:wb :stats]   (iso-days-ago 1)}   ; fresh
          result (f/stale-info* {:report :ue :marketplace :wb
                                  :today today}
                                 last-syncs)]
      (is (= :stale (:status result)))
      (is (= [:wb :finance] (:worst-pair result)))))

  (testing "UE report: all fresh → ok"
    (let [last-syncs {[:wb :sales]   (iso-days-ago 1)
                      [:wb :finance] (iso-days-ago 3)
                      [:wb :stats]   (iso-days-ago 1)}
          result (f/stale-info* {:report :ue :marketplace :wb
                                  :today today}
                                 last-syncs)]
      (is (= :ok (:status result))))))
