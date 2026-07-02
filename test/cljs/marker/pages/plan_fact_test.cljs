(ns marker.pages.plan-fact-test
  "Unit tests for the pure helpers in marker.pages.plan-fact (017).
   No DOM / UIx rendering — only the variance-rendering data layer.
   Run via: shadow-cljs compile test

   The load-bearing contract (contracts/plan-fact-sku.edn §3):
     plan=nil          → «—» everywhere, NOT −100%
     plan>0, actual=0  → Δ%=−100 renders signed and adverse-red, no error
     plan=0            → Δ% nil (no divide-by-zero), tone falls back to Δabs"
  (:require [cljs.test :refer [deftest is testing]]
            [marker.pages.plan-fact :as pf]))

(def NBSP " ")
(def MINUS "−")

;; ---------------------------------------------------------------------------
;; metric->info
;; ---------------------------------------------------------------------------

(deftest metric->info-resolution
  (testing "canonical keyword slug"
    (let [{:keys [label suffix positive-if-grow]} (pf/metric->info :revenue)]
      (is (= "Выручка" label))
      (is (= :rub suffix))
      (is (true? positive-if-grow))))

  (testing "string form (defensive: non-Transit payloads)"
    (is (= "Выручка" (:label (pf/metric->info "revenue")))))

  (testing "grow-bad metric polarity"
    (is (false? (:positive-if-grow (pf/metric->info :drr-pct))))
    (is (false? (:positive-if-grow (pf/metric->info :advertising)))))

  (testing "unknown metric falls back to raw name + grow-good, never throws"
    (let [{:keys [label positive-if-grow]} (pf/metric->info :mystery-metric)]
      (is (= "mystery-metric" label))
      (is (true? positive-if-grow))))

  (testing "nil metric → em-dash label, never throws"
    (is (= "—" (:label (pf/metric->info nil))))))

;; ---------------------------------------------------------------------------
;; fmt-variance-abs / fmt-variance-pct — edge-safe rendering
;; ---------------------------------------------------------------------------

(deftest fmt-variance-abs-edge-safe
  (testing "nil (plan not set) → «—», not 0"
    (is (= "—" (pf/fmt-variance-abs nil :rub))))

  (testing "positive ₽ carries explicit + sign"
    (is (= (str "+1" NBSP "234" NBSP "₽") (pf/fmt-variance-abs 1234 :rub))))

  (testing "negative ₽ uses Unicode minus"
    (is (= (str MINUS "200" NBSP "₽") (pf/fmt-variance-abs -200 :rub))))

  (testing "non-₽ suffix gets manual + prefix for positives"
    (is (= "+12,3%" (pf/fmt-variance-abs 12.34 :pct)))))

(deftest fmt-variance-pct-edge-safe
  (testing "nil plan → «—», NOT −100%"
    (is (= "—" (pf/fmt-variance-pct nil))))

  (testing "positive signed with RU comma decimal"
    (is (= "+12,3%" (pf/fmt-variance-pct 12.34))))

  (testing "plan>0 & actual=0 backend edge (−100%) renders without error"
    (is (= "-100,0%" (pf/fmt-variance-pct -100.0))))

  (testing "zero renders unsigned"
    (is (= "0,0%" (pf/fmt-variance-pct 0)))))

;; ---------------------------------------------------------------------------
;; variance-tone — favourable/adverse colouring by metric polarity
;; ---------------------------------------------------------------------------

(deftest variance-tone-polarity
  (testing "nil plan → nil variance → flat (neutral, no colour)"
    (is (= "flat" (pf/variance-tone {:metric :revenue :plan nil :actual 500.0
                                     :variance-abs nil :variance-pct nil}))))

  (testing "grow-good metric: actual ≥ plan → favourable green (up)"
    (is (= "up" (pf/variance-tone {:metric :revenue
                                   :variance-abs 200.0 :variance-pct 20.0}))))

  (testing "grow-good metric: actual < plan → adverse red (down)"
    (is (= "down" (pf/variance-tone {:metric :revenue
                                     :variance-abs -200.0 :variance-pct -20.0}))))

  (testing "plan>0, actual=0 (−100%) → adverse, no error"
    (is (= "down" (pf/variance-tone {:metric :revenue
                                     :variance-abs -1000.0 :variance-pct -100.0}))))

  (testing "grow-bad metric (ДРР): overshooting plan is adverse"
    (is (= "down" (pf/variance-tone {:metric :drr-pct
                                     :variance-abs 2.0 :variance-pct 10.0})))
    (is (= "up" (pf/variance-tone {:metric :drr-pct
                                   :variance-abs -2.0 :variance-pct -10.0}))))

  (testing "plan=0 edge: pct nil → tone falls back to Δabs"
    (is (= "up" (pf/variance-tone {:metric :revenue
                                   :variance-abs 50.0 :variance-pct nil}))))

  (testing "zero variance → flat"
    (is (= "flat" (pf/variance-tone {:metric :revenue
                                     :variance-abs 0.0 :variance-pct 0.0})))))

;; ---------------------------------------------------------------------------
;; totals-variance / fmt-total-plan — footer row honesty
;; ---------------------------------------------------------------------------

(deftest totals-variance-edge-safe
  (testing "backend sums with (reduce + 0.0 …): plan=0.0 means NO plans → nils"
    (is (= {:abs nil :pct nil} (pf/totals-variance {:plan 0.0 :actual 500.0}))))

  (testing "nil totals → nils, never throws"
    (is (= {:abs nil :pct nil} (pf/totals-variance nil)))
    (is (= {:abs nil :pct nil} (pf/totals-variance {:plan nil :actual nil}))))

  (testing "normal totals → abs and pct"
    (let [{:keys [abs pct]} (pf/totals-variance {:plan 1000.0 :actual 1200.0})]
      (is (= 200.0 abs))
      (is (< (js/Math.abs (- pct 20.0)) 1e-9)))))

(deftest fmt-total-plan-honesty
  (testing "no plans (Σ=0.0) → «—», never «0 ₽»"
    (is (= "—" (pf/fmt-total-plan 0.0)))
    (is (= "—" (pf/fmt-total-plan nil))))

  (testing "positive plan renders as ₽"
    (is (= (str "1" NBSP "000" NBSP "₽") (pf/fmt-total-plan 1000.0)))))

;; ---------------------------------------------------------------------------
;; current-month — YYYY-MM shape for the selector default
;; ---------------------------------------------------------------------------

(deftest current-month-shape
  (is (some? (re-matches #"\d{4}-(0[1-9]|1[0-2])" (pf/current-month)))))

;; ---------------------------------------------------------------------------
;; api-error lookup — url-keyed error routing
;; ---------------------------------------------------------------------------

(deftest table-error-url-matching
  (let [err {:message "boom" :status 500}]
    (testing "exact url"
      (is (= err (pf/table-error {"/api/v1/plan/sku" err}))))

    (testing "url with query string (the load event always appends params)"
      (is (= err (pf/table-error
                  {"/api/v1/plan/sku?period_month=2026-07&marketplace=wb" err}))))

    (testing "import urls do NOT leak into the table error slot"
      (is (nil? (pf/table-error {"/api/v1/plan/sku/preview" err})))
      (is (nil? (pf/table-error {"/api/v1/plan/sku/import" err}))))

    (testing "empty / unrelated errors → nil"
      (is (nil? (pf/table-error {})))
      (is (nil? (pf/table-error {"/api/v1/marker/pnl" err}))))))

(deftest import-error-url-matching
  (let [err {:message "bad file" :status 422}]
    (is (= err (pf/import-error {"/api/v1/plan/sku/preview" err})))
    (is (= err (pf/import-error {"/api/v1/plan/sku/import" err})))
    (testing "table GET error does not surface in the import card"
      (is (nil? (pf/import-error {"/api/v1/plan/sku?period_month=2026-07" err}))))))
