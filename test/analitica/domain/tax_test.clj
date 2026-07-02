(ns analitica.domain.tax-test
  "US2 (spec 015) — tax config store + pure compute.
   TDD invariant tests T013-T018 per contracts/tax-opex-api.md §4/§5 and
   quickstart.md §2/§5. Money model is LOCKED (data-model.md §3):

     usn-income          base = for_pay
     usn-income-expense  base = for_pay − (if official-cost-price (+ cogs opex) opex), max 0
     vat                 = round2(revenue × vat_rate)  (output VAT on gross)
     none/osno/patent    → tax 0, vat 0
     tax/vat ≥ 0 always; убыток ⇒ tax 0 (INV-3, no 1%-floor in v1)."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [analitica.domain.tax :as tax]
            [malli.core :as m]
            analitica.test-helpers))

(use-fixtures :once analitica.test-helpers/with-test-db)

;; ---------------------------------------------------------------------------
;; T013 — Malli schemas
;; ---------------------------------------------------------------------------

(deftest t013-malli-schemas
  (testing "TaxConfigRow — valid row passes, invalid rows fail"
    (is (m/validate tax/TaxConfigRow
                    {:year 2026 :month 5 :taxation-type :usn-income
                     :usn-rate 0.06 :vat-rate 0.0 :official-cost-price true}))
    (is (not (m/validate tax/TaxConfigRow
                         {:year 2026 :month 13 :taxation-type :usn-income
                          :usn-rate 0.06 :vat-rate 0.0 :official-cost-price true}))
        "month = 13 must fail")
    (is (not (m/validate tax/TaxConfigRow
                         {:year 2026 :month 5 :taxation-type :usn-income
                          :usn-rate 1.5 :vat-rate 0.0 :official-cost-price true}))
        "usn-rate > 1.0 must fail")
    (is (not (m/validate tax/TaxConfigRow
                         {:year 2026 :month 5 :taxation-type :bogus
                          :usn-rate 0.06 :vat-rate 0.0 :official-cost-price true}))
        "taxation-type outside enum must fail"))

  (testing "TaxComputeInput / TaxComputeResult schemas"
    (is (m/validate tax/TaxComputeInput
                    {:taxation-type :usn-income :usn-rate 0.06 :vat-rate 0.0
                     :official-cost-price true :for-pay 830000.0 :revenue 1000000.0
                     :cogs 300000.0 :opex 170000.0}))
    (is (m/validate tax/TaxComputeResult
                    {:tax-base 830000.0 :tax 49800.0 :vat 0.0}))
    (is (not (m/validate tax/TaxComputeResult
                         {:tax-base 830000.0 :tax -1.0 :vat 0.0}))
        "negative tax must fail TaxComputeResult")))

;; ---------------------------------------------------------------------------
;; T014 — УСН Доходы: base = for_pay
;; ---------------------------------------------------------------------------

(deftest t014-usn-income
  (testing "УСН Доходы: tax-base = for_pay, tax = round2(for_pay × rate), vat 0"
    (let [r (tax/compute-period
              {:taxation-type :usn-income :usn-rate 0.06 :vat-rate 0.0
               :official-cost-price true :for-pay 830000.0 :revenue 1000000.0
               :cogs 300000.0 :opex 170000.0})]
      (is (= 830000.0 (:tax-base r)))
      (is (= 49800.0 (:tax r)))
      (is (= 0.0 (:vat r))))))

;; ---------------------------------------------------------------------------
;; T015 — УСН Доходы−Расходы: base = for_pay − deductible (official-cost-price gates cogs)
;; ---------------------------------------------------------------------------

(deftest t015-usn-income-expense
  (testing "official-cost-price true ⇒ base = for_pay − (cogs + opex)"
    (let [r (tax/compute-period
              {:taxation-type :usn-income-expense :usn-rate 0.15 :vat-rate 0.0
               :official-cost-price true :for-pay 830000.0 :revenue 1000000.0
               :cogs 300000.0 :opex 170000.0})]
      (is (= 360000.0 (:tax-base r)))          ; 830000 − (300000 + 170000)
      (is (= 54000.0 (:tax r)))))              ; round2(360000 × 0.15)

  (testing "official-cost-price false ⇒ cogs NOT deducted (FR-019)"
    (let [r (tax/compute-period
              {:taxation-type :usn-income-expense :usn-rate 0.15 :vat-rate 0.0
               :official-cost-price false :for-pay 830000.0 :revenue 1000000.0
               :cogs 300000.0 :opex 170000.0})]
      (is (= 660000.0 (:tax-base r)))          ; 830000 − 170000 (cogs excluded)
      (is (= 99000.0 (:tax r))))))             ; round2(660000 × 0.15)

;; ---------------------------------------------------------------------------
;; T016 — НДС (output VAT on gross revenue), independent of УСН
;; ---------------------------------------------------------------------------

(deftest t016-vat
  (testing "vat = round2(revenue × vat_rate), on gross, independent of УСН"
    (let [r (tax/compute-period
              {:taxation-type :usn-income :usn-rate 0.06 :vat-rate 0.20
               :official-cost-price true :for-pay 830000.0 :revenue 1000000.0
               :cogs 0.0 :opex 0.0})]
      (is (= 200000.0 (:vat r)))               ; 1000000 × 0.20
      (is (= 49800.0 (:tax r)))                ; УСН unaffected by НДС
      (is (= 830000.0 (:tax-base r))))))

;; ---------------------------------------------------------------------------
;; T017 — INV-3: tax ≥ 0 on loss; none/osno/patent → 0
;; ---------------------------------------------------------------------------

(deftest t017-inv3-tax-nonneg-and-unsupported-regimes
  (testing "убыток Д−Р ⇒ base ≤ 0 ⇒ tax 0 (max(0,…), no 1%-floor)"
    (let [r (tax/compute-period
              {:taxation-type :usn-income-expense :usn-rate 0.15 :vat-rate 0.0
               :official-cost-price true :for-pay 100000.0 :revenue 120000.0
               :cogs 300000.0 :opex 50000.0})]
      (is (= -250000.0 (:tax-base r)))         ; 100000 − (300000 + 50000)
      (is (= 0.0 (:tax r)))                    ; max(0, base) ⇒ 0
      (is (>= (:tax r) 0.0))))

  (testing "none / osno / patent ⇒ tax 0, vat 0 (forward-compatible)"
    (doseq [t [:none :osno :patent]]
      (let [r (tax/compute-period
                {:taxation-type t :usn-rate 0.06 :vat-rate 0.20
                 :official-cost-price true :for-pay 830000.0 :revenue 1000000.0
                 :cogs 300000.0 :opex 170000.0})]
        (is (= 0.0 (:tax r)) (str t " ⇒ tax 0"))
        (is (= 0.0 (:vat r)) (str t " ⇒ vat 0"))))))

;; ---------------------------------------------------------------------------
;; T018 — Store round-trip: save/read, percent→fraction, mid-year, nil month
;; ---------------------------------------------------------------------------

(deftest t018-store-round-trip
  (testing "save-config! + config-for-month round-trip (rates in fractions)"
    (tax/save-config! [{:year 2027 :month 5 :taxation-type :usn-income
                        :usn-rate 0.06 :vat-rate 0.0 :official-cost-price true}])
    (let [row (tax/config-for-month 2027 5)]
      (is (= :usn-income (:taxation-type row)))
      (is (= 0.06 (:usn-rate row)))
      (is (true? (:official-cost-price row)))))

  (testing "UI percents normalized to fractions (:usn-rate-pct 6 ⇒ 0.06)"
    (tax/save-config! [{:year 2027 :month 7 :taxation-type :usn-income
                        :usn-rate-pct 6 :vat-rate-pct 20 :official-cost-price true}])
    (let [row (tax/config-for-month 2027 7)]
      (is (= 0.06 (:usn-rate row)))
      (is (= 0.20 (:vat-rate row)))))

  (testing "mid-year rate change: each month keeps its own rate"
    (tax/save-config! [{:year 2027 :month 8 :taxation-type :usn-income
                        :usn-rate 0.06 :vat-rate 0.0 :official-cost-price true}
                       {:year 2027 :month 9 :taxation-type :usn-income
                        :usn-rate 0.07 :vat-rate 0.0 :official-cost-price true}])
    (is (= 0.06 (:usn-rate (tax/config-for-month 2027 8))))
    (is (= 0.07 (:usn-rate (tax/config-for-month 2027 9)))))

  (testing "UPSERT: re-save same (year,month) updates in place"
    (tax/save-config! [{:year 2027 :month 8 :taxation-type :usn-income-expense
                        :usn-rate 0.15 :vat-rate 0.0 :official-cost-price false}])
    (let [row (tax/config-for-month 2027 8)]
      (is (= :usn-income-expense (:taxation-type row)))
      (is (= 0.15 (:usn-rate row)))
      (is (false? (:official-cost-price row)))))

  (testing "config-for-month for unconfigured month ⇒ nil (⇒ tax 0, FR-004)"
    (is (nil? (tax/config-for-month 2027 12))))

  (testing "fetch-config returns the year's rows"
    (let [rows (tax/fetch-config 2027)]
      (is (seq rows))
      (is (every? #(= 2027 (:year %)) rows))
      (is (contains? (set (map :month rows)) 5)))))
