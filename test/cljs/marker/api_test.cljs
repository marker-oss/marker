(ns marker.api-test
  "Unit tests for marker.api Transit encode/decode helpers.
   These are pure unit tests — no HTTP requests, no browser needed.
   Run via: shadow-cljs compile test"
  (:require [cljs.test :refer [deftest is testing]]
            [marker.api :as api]))

;; ---------------------------------------------------------------------------
;; Transit roundtrip
;; ---------------------------------------------------------------------------

(deftest encode-decode-roundtrip-simple-map
  (testing "roundtrip a simple keyword-keyed map"
    (let [m {:foo "bar" :n 42 :b true}
          s (api/encode-transit m)
          d (api/decode-transit s)]
      (is (string? s)    "encode produces a string")
      (is (= m d)        "decoded value equals original"))))

(deftest encode-decode-roundtrip-nested
  (testing "roundtrip a nested map with vectors and numbers"
    (let [m {:kpis {:revenue {:value 1234567.0 :delta-pct 5.2 :spark [1 2 3]}
                    :orders  {:value 320 :delta-pct -1.1 :spark []}}
             :alerts [{:kind "danger" :title "Test" :body "body" :cta "CTA"}]}
          s (api/encode-transit m)
          d (api/decode-transit s)]
      (is (= m d) "nested roundtrip equals original"))))

(deftest encode-decode-nil-values
  (testing "nil values survive roundtrip"
    (let [m {:roas nil :plan nil :delta-pct nil}
          d (api/decode-transit (api/encode-transit m))]
      (is (= m d) "nil values preserved"))))

(deftest encode-decode-keyword-keys
  (testing "keyword keys are preserved through Transit"
    (let [m {:marker/page :pulse :marker/mp-filter [:wb :ozon :ym]}
          d (api/decode-transit (api/encode-transit m))]
      (is (= :pulse              (:marker/page d))       "namespaced keyword value preserved")
      (is (= [:wb :ozon :ym]    (:marker/mp-filter d))   "keyword vector preserved"))))

(deftest encode-decode-empty-collections
  (testing "empty vector and map roundtrip correctly"
    (is (= [] (api/decode-transit (api/encode-transit []))))
    (is (= {} (api/decode-transit (api/encode-transit {}))))))

;; ---------------------------------------------------------------------------
;; build-params helper
;; ---------------------------------------------------------------------------

(deftest build-params-all-mps
  (testing "all 3 MPs → no :mp param (nil)"
    (let [p (api/build-params {:mp-filter [:wb :ozon :ym] :period "Последние 30 дней" :compare false})]
      (is (nil? (:mp p)) "all MPs should not set ?mp="))))

(deftest build-params-subset-mps
  (testing "subset of MPs → :mp param set"
    (let [p (api/build-params {:mp-filter [:wb :ozon] :period "Сегодня" :compare false})]
      (is (= "wb,ozon" (:mp p)) "two MPs encoded as comma-joined string"))))

(deftest build-params-single-mp
  (testing "single MP → :mp param is just that mp name"
    (let [p (api/build-params {:mp-filter [:ym] :period "Сегодня" :compare false})]
      (is (= "ym" (:mp p))))))

(deftest build-params-compare-flag
  (testing "compare true → :compare \"true\""
    (let [p (api/build-params {:mp-filter [:wb :ozon :ym] :period "Последние 30 дней" :compare true})]
      (is (= "true" (:compare p))))))

(deftest build-params-no-compare
  (testing "compare false → :compare key absent"
    (let [p (api/build-params {:mp-filter [:wb :ozon :ym] :period "Последние 30 дней" :compare false})]
      (is (nil? (:compare p))))))

;; ---------------------------------------------------------------------------
;; resolve-period resolver  (now public)
;; Fixed "now" = 2026-05-15  (js/Date. 2026 4 15)  — month is 0-indexed so 4 = May
;; ---------------------------------------------------------------------------

(def may-15 (js/Date. 2026 4 15))

(deftest period-today
  (testing "Сегодня → same date for from and to"
    (let [p (api/resolve-period "Сегодня" may-15)]
      (is (= {:from "2026-05-15" :to "2026-05-15"} p)))))

(deftest period-yesterday
  (testing "Вчера → day before now"
    (let [p (api/resolve-period "Вчера" may-15)]
      (is (= {:from "2026-05-14" :to "2026-05-14"} p)))))

(deftest period-last-7-days
  (testing "Последние 7 дней → today-7 to today (mirrors backend last-7-days)"
    (let [p (api/resolve-period "Последние 7 дней" may-15)]
      (is (= {:from "2026-05-08" :to "2026-05-15"} p)))))

(deftest period-last-30-days
  (testing "Последние 30 дней → today-30 to today (mirrors backend last-30-days)"
    (let [p (api/resolve-period "Последние 30 дней" may-15)]
      (is (= {:from "2026-04-15" :to "2026-05-15"} p)))))

(deftest period-this-month
  (testing "Этот месяц → first of month to today"
    (let [p (api/resolve-period "Этот месяц" may-15)]
      (is (= {:from "2026-05-01" :to "2026-05-15"} p)))))

(deftest period-last-month
  (testing "Прошлый месяц → Apr 1 to Apr 30 when now is May 15"
    (let [p (api/resolve-period "Прошлый месяц" may-15)]
      (is (= {:from "2026-04-01" :to "2026-04-30"} p)))))

(deftest period-this-quarter
  (testing "Этот квартал → Q2 starts Apr 1 when now is May 15"
    (let [p (api/resolve-period "Этот квартал" may-15)]
      (is (= {:from "2026-04-01" :to "2026-05-15"} p)))))

(deftest resolve-period-this-quarter-q1
  (testing "Этот квартал — January falls in Q1 starting Jan 1"
    (let [jan-15 (js/Date. 2026 0 15)]
      (is (= {:from "2026-01-01" :to "2026-01-15"}
             (api/resolve-period "Этот квартал" jan-15))))))

(deftest period-this-year
  (testing "Этот год → Jan 1 of current year to today"
    (let [p (api/resolve-period "Этот год" may-15)]
      (is (= {:from "2026-01-01" :to "2026-05-15"} p)))))

(deftest period-custom-range-passthrough
  (testing "Custom YYYY-MM-DD,YYYY-MM-DD → parsed verbatim"
    (let [p (api/resolve-period "2026-01-15,2026-02-20" may-15)]
      (is (= {:from "2026-01-15" :to "2026-02-20"} p)))))

(deftest period-unknown-label
  (testing "Unknown label → empty map (backend default)"
    (let [p (api/resolve-period "Какой-то текст" may-15)]
      (is (= {} p)))))

(deftest period-nil
  (testing "nil → empty map"
    (let [p (api/resolve-period nil may-15)]
      (is (= {} p)))))

;; Edge case: January → previous month should roll back to December of prior year
(deftest period-last-month-january-rollback
  (testing "Прошлый месяц when now is Jan 10, 2026 → Dec 1–31, 2025"
    (let [jan-10 (js/Date. 2026 0 10)   ; month 0 = January
          p (api/resolve-period "Прошлый месяц" jan-10)]
      (is (= {:from "2025-12-01" :to "2025-12-31"} p)))))

;; ---------------------------------------------------------------------------
;; format-period-range
;; ---------------------------------------------------------------------------

(deftest format-period-range-today-nil
  (testing "Сегодня → nil (self-describing label)"
    (is (nil? (api/format-period-range "Сегодня" may-15)))))

(deftest format-period-range-yesterday-nil
  (testing "Вчера → nil (self-describing label)"
    (is (nil? (api/format-period-range "Вчера" may-15)))))

(deftest format-period-range-unknown-nil
  (testing "Unknown label → nil"
    (is (nil? (api/format-period-range "Какой-то текст" may-15)))))

(deftest format-period-range-nil-nil
  (testing "nil period → nil"
    (is (nil? (api/format-period-range nil may-15)))))

(deftest format-period-range-this-month
  (testing "Этот месяц → DD.MM–DD.MM range"
    (is (= "01.05–15.05" (api/format-period-range "Этот месяц" may-15)))))

(deftest format-period-range-last-month
  (testing "Прошлый месяц (Apr) → 01.04–30.04"
    (is (= "01.04–30.04" (api/format-period-range "Прошлый месяц" may-15)))))

(deftest format-period-range-last-7-days
  (testing "Последние 7 дней → 08.05–15.05"
    (is (= "08.05–15.05" (api/format-period-range "Последние 7 дней" may-15)))))

(deftest format-period-range-this-year
  (testing "Этот год → 01.01–15.05 (same year, no year suffix)"
    (is (= "01.01–15.05" (api/format-period-range "Этот год" may-15)))))

(deftest format-period-range-custom-range
  (testing "Custom range → formatted DD.MM–DD.MM"
    (is (= "15.01–20.02" (api/format-period-range "2026-01-15,2026-02-20" may-15)))))

(deftest format-period-range-custom-single-day
  (testing "Custom single-day range → single DD.MM"
    (is (= "15.01" (api/format-period-range "2026-01-15,2026-01-15" may-15)))))

(deftest format-period-range-cross-year
  (testing "Cross-year custom range → DD.MM.YYYY–DD.MM.YYYY"
    (is (= "15.12.2025–10.01.2026"
           (api/format-period-range "2025-12-15,2026-01-10" may-15)))))
