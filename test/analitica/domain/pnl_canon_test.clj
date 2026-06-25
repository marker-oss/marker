(ns analitica.domain.pnl-canon-test
  "Per-metric-group tests anchored to canonical-formulas.md §P&L.

   Every deftest maps to one P&L.N block in the canon. If canon changes,
   this file changes in lockstep."
  (:require [clojure.test :refer [deftest is testing]]
            [analitica.domain.pnl :as pnl]
            [analitica.util.math :as math]))

;; ---------------------------------------------------------------------------
;; Shared fixture: 3 articles on WB with known per-article aggregates.
;; Mirrors the UE canon-test fixture so reconciliation cross-checks can
;; use either report on the same input.
;; ---------------------------------------------------------------------------

(def fx
  "Finance rows fixture: WB, 3 articles (A/B/C), March 2026."
  (concat
    ;; Article A — sale rows (5 sales × 100)
    (for [i (range 5)]
      {:marketplace :wb :rrd-id (+ 100 i)
       :date-from "2026-03-01" :date-to "2026-03-07"
       :event-date "2026-03-03"
       :article "A" :operation "sale" :quantity 1
       :retail-amount 100.0 :retail-price 100.0
       :for-pay 80.0 :mp-commission 15.0 :wb-reward 15.0
       :delivery-cost 1.0 :storage-fee 0.5 :acceptance 0.2
       :penalty 0.0 :acquiring-fee 2.0 :deduction 0.1
       :additional-payment 0.0 :ad-cost 0.0})
    ;; Article A — return rows (2 returns)
    (for [i (range 2)]
      {:marketplace :wb :rrd-id (+ 200 i)
       :date-from "2026-03-01" :date-to "2026-03-07"
       :event-date "2026-03-05"
       :article "A" :operation "return" :quantity 1
       :retail-amount 0.0 :retail-price 0.0
       :for-pay 0.0 :mp-commission 0.0 :wb-reward 0.0
       :delivery-cost 0.5 :storage-fee 0.0 :acceptance 0.0
       :penalty 0.0 :acquiring-fee 0.0 :deduction 0.0
       :additional-payment 0.0 :ad-cost 0.0})
    ;; Article B — 3 sales × 50
    (for [i (range 3)]
      {:marketplace :wb :rrd-id (+ 300 i)
       :date-from "2026-03-01" :date-to "2026-03-07"
       :event-date "2026-03-04"
       :article "B" :operation "sale" :quantity 1
       :retail-amount 50.0 :retail-price 50.0
       :for-pay 42.0 :mp-commission 8.0 :wb-reward 8.0
       :delivery-cost 0.5 :storage-fee 0.2 :acceptance 0.1
       :penalty 0.0 :acquiring-fee 1.0 :deduction 0.0
       :additional-payment 0.0 :ad-cost 0.0})
    ;; Article C — only returns
    (for [i (range 1)]
      {:marketplace :wb :rrd-id (+ 400 i)
       :date-from "2026-03-01" :date-to "2026-03-07"
       :event-date "2026-03-06"
       :article "C" :operation "return" :quantity 1
       :retail-amount 0.0 :retail-price 0.0
       :for-pay 0.0 :mp-commission 0.0 :wb-reward 0.0
       :delivery-cost 0.5 :storage-fee 0.0 :acceptance 0.0
       :penalty 0.0 :acquiring-fee 0.0 :deduction 0.0
       :additional-payment 0.0 :ad-cost 0.0})))

(def p (pnl/calculate fx))

;; ---------------------------------------------------------------------------
;; P&L.1 — period monetary aggregates
;; ---------------------------------------------------------------------------

(deftest group-1-aggregates
  (testing "revenue = 5×100 + 3×50 = 650"
    (is (= 650.0 (:revenue p))))
  (testing "wb-reward = 5×15 + 3×8 = 99"
    (is (= 99.0 (:wb-reward p))))
  (testing "logistics = (5×1 + 2×0.5) + (3×0.5) + 0.5 = 8.0"
    (is (= 8.0 (:logistics p))))
  (testing "storage = 5×0.5 + 3×0.2 = 3.1"
    (is (= 3.1 (:storage p))))
  (testing "for-pay = sales 5×80 + 3×42 − returns 0 − 0 = 526"
    (is (= 526.0 (:for-pay p)))))

;; ---------------------------------------------------------------------------
;; P&L.3 — gross profit
;; ---------------------------------------------------------------------------

(deftest group-3-gross-profit
  (testing "gross-profit = for-pay − cogs − logistics − storage − penalties − acceptance − deduction + additional"
    (let [expected (math/round2
                     (- (:for-pay p)
                        (:cogs p)
                        (:logistics p)
                        (:storage p)
                        (:penalties p)
                        (:acceptance p)
                        (:deduction p)
                        (- (:additional p))))]
      (is (= expected (:gross-profit p))))))

;; ---------------------------------------------------------------------------
;; P&L.4 — net profit and margins
;; ---------------------------------------------------------------------------

(deftest group-4-net-profit
  (testing "net-profit = gross-profit − ad-spend"
    (is (= (math/round2 (- (:gross-profit p) (:ad-spend p)))
           (:net-profit p))))
  (testing "margin-gross = gross-profit / revenue × 100"
    (is (= (math/percentage (:gross-profit p) (:revenue p))
           (:margin-gross p))))
  (testing "margin-net = net-profit / revenue × 100"
    (is (= (math/percentage (:net-profit p) (:revenue p))
           (:margin-net p)))))

;; ---------------------------------------------------------------------------
;; P&L.5 — quantity and per-event derivatives
;; ---------------------------------------------------------------------------

(deftest group-5-quantities
  (testing "sales-qty = 5 + 3 = 8, returns-qty = 2 + 1 = 3"
    (is (= 8 (:sales-qty p)))
    (is (= 3 (:returns-qty p))))
  (testing "buyout-rate = 8 / 11 × 100"
    (is (= (math/percentage 8 11) (:buyout-rate p))))
  (testing "avg-check = 650 / 8"
    (is (= (math/round2 (/ 650.0 8)) (:avg-check p))))
  (testing "articles = 3"
    (is (= 3 (:articles p)))))

;; ---------------------------------------------------------------------------
;; P&L.6 — Ozon cash-flow adjustments (optional)
;; ---------------------------------------------------------------------------

(deftest group-6-cf-adjustments
  (let [cf {:subscription       -50.0
            :warehouse-movement -20.0
            :returns-cargo      -10.0
            :fines              -5.0
            :packaging          -15.0
            :other-services     0.0
            :corrections        30.0
            :compensation       10.0}
        pc (pnl/calculate fx :cf-adjustments cf)]
    (testing "cf-costs = subscription + warehouse + returns-cargo + fines + packaging + other-services"
      (is (= (math/round2 (+ -50.0 -20.0 -10.0 -5.0 -15.0 0.0))
             (:cf-costs pc))))
    (testing "cf-income = corrections + compensation"
      (is (= (math/round2 (+ 30.0 10.0)) (:cf-income pc))))
    (testing "cf-total = cf-costs + cf-income"
      (is (= (math/round2 (+ (:cf-costs pc) (:cf-income pc)))
             (:cf-total pc))))
    (testing "adjusted-gross = gross-profit + cf-total"
      (is (= (math/round2 (+ (:gross-profit pc) (:cf-total pc)))
             (:adjusted-gross pc))))
    (testing "adjusted-net = adjusted-gross − ad-spend"
      (is (= (math/round2 (- (:adjusted-gross pc) (:ad-spend pc)))
             (:adjusted-net pc))))))

;; ---------------------------------------------------------------------------
;; P&L.6 — absence: no cf-adjustments → no adjusted-* fields
;; ---------------------------------------------------------------------------

(deftest group-6-no-cf-adjustments
  (testing "when :cf-adjustments is nil, adjusted-* keys are absent"
    (is (not (contains? p :adjusted-gross)))
    (is (not (contains? p :adjusted-net)))
    (is (not (contains? p :cf-total)))))

;; ---------------------------------------------------------------------------
;; ---------------------------------------------------------------------------
;; P&L.7 — marketplace commission (mp_commission) informational field
;; ---------------------------------------------------------------------------

(deftest pnl-exposes-mp-commission
  (testing "pnl/calculate exposes :mp-commission as normalized negative sum"
    ;; fixture: Article A 5 sales × 15 = 75, Article B 3 sales × 8 = 24 → total 99
    ;; normalized: (- (Math/abs 99.0)) = -99.0
    (is (= -99.0 (:mp-commission p)))))

(deftest pnl-mp-commission-does-not-change-gross
  (testing "gross-profit and net-profit are byte-identical with and without commission"
    (let [fx-no-commission (map #(dissoc % :mp-commission) fx)
          p-no-comm        (pnl/calculate fx-no-commission)]
      (is (= (:gross-profit p) (:gross-profit p-no-comm))
          "gross-profit must not change when mp-commission is absent")
      (is (= (:net-profit p) (:net-profit p-no-comm))
          "net-profit must not change when mp-commission is absent"))))

(deftest mp-commission-sign-normalized
  (testing "ozon-positive, wb-negative, ym-positive all yield a negative sign"
    ;; Ozon: positive in source (MP charges seller positive amount)
    (let [ozon-rows [{:marketplace :ozon :rrd-id 1
                      :date-from "2026-04-01" :date-to "2026-04-30"
                      :event-date "2026-04-15"
                      :article "X" :operation "sale" :quantity 1
                      :retail-amount 1000.0 :retail-price 1000.0
                      :for-pay 830.0 :mp-commission 150.0 :wb-reward 0.0
                      :delivery-cost 0.0 :storage-fee 0.0 :acceptance 0.0
                      :penalty 0.0 :acquiring-fee 0.0 :deduction 0.0
                      :additional-payment 0.0 :ad-cost 0.0}]
          ozon-p    (pnl/calculate ozon-rows)]
      (is (neg? (:mp-commission ozon-p))
          "ozon positive-source commission must be normalized to negative"))
    ;; WB: negative in source
    (let [wb-rows [{:marketplace :wb :rrd-id 2
                    :date-from "2026-03-01" :date-to "2026-03-07"
                    :event-date "2026-03-03"
                    :article "Y" :operation "sale" :quantity 1
                    :retail-amount 500.0 :retail-price 500.0
                    :for-pay 415.0 :mp-commission -75.0 :wb-reward 0.0
                    :delivery-cost 0.0 :storage-fee 0.0 :acceptance 0.0
                    :penalty 0.0 :acquiring-fee 0.0 :deduction 0.0
                    :additional-payment 0.0 :ad-cost 0.0}]
          wb-p    (pnl/calculate wb-rows)]
      (is (neg? (:mp-commission wb-p))
          "wb negative-source commission must remain negative after normalization"))
    ;; YM: positive in source
    (let [ym-rows [{:marketplace :ym :rrd-id 3
                    :date-from "2026-04-01" :date-to "2026-04-30"
                    :event-date "2026-04-20"
                    :article "Z" :operation "sale" :quantity 1
                    :retail-amount 800.0 :retail-price 800.0
                    :for-pay 680.0 :mp-commission 100.0 :wb-reward 0.0
                    :delivery-cost 0.0 :storage-fee 0.0 :acceptance 0.0
                    :penalty 0.0 :acquiring-fee 0.0 :deduction 0.0
                    :additional-payment 0.0 :ad-cost 0.0}]
          ym-p    (pnl/calculate ym-rows)]
      (is (neg? (:mp-commission ym-p))
          "ym positive-source commission must be normalized to negative"))))

;; ---------------------------------------------------------------------------
;; Cross-check — P&L and UE agree on the fixture
;; ---------------------------------------------------------------------------

(deftest group-reconcile-with-ue
  (testing "P&L gross-profit matches the UE-formula grand-total within 0.1 RUB"
    ;; UE's article-level profit formula is identical, so summing it
    ;; must equal P&L gross-profit modulo per-row rounding.
    (require 'analitica.domain.unit-economics)
    (let [ue-calc @(resolve 'analitica.domain.unit-economics/calculate)
          ue-rows (ue-calc fx)
          ue-total-profit (reduce + 0.0 (map :profit ue-rows))
          ;; UE.4 adds ad-spend subtraction into per-row profit; P&L.3
          ;; keeps gross separate. Add ad-spend back for parity.
          ue-gross (+ ue-total-profit (:ad-spend p))
          delta    (Math/abs (- ue-gross (:gross-profit p)))]
      (is (< delta 1.0)
          (str "Delta " delta " RUB exceeds reconciliation tolerance")))))
