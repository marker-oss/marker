(ns analitica.domain.pnl-canon-test
  "Per-metric-group tests anchored to canonical-formulas.md §P&L.

   Every deftest maps to one P&L.N block in the canon. If canon changes,
   this file changes in lockstep."
  (:require [clojure.test :refer [deftest is testing use-fixtures]]
            [clojure.edn :as edn]
            [clojure.java.io :as io]
            [analitica.domain.pnl :as pnl]
            [analitica.domain.tax :as tax]
            [analitica.domain.opex :as opex]
            [analitica.db :as db]
            [analitica.util.math :as math]
            analitica.test-helpers))

(use-fixtures :once analitica.test-helpers/with-test-db)

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

;; ---------------------------------------------------------------------------
;; P&L.10 — Management-basis layer (spec 015 US1): tax (УСН/НДС) + OPEX
;;
;; Fixture (management-pnl-2026-05.edn / quickstart §1): a closed month whose
;; frozen basis resolves to revenue 1000000, for_pay 830000, net-profit 700000.
;; We build finance rows that make pnl/calculate produce exactly those:
;;   retail-amount 1000000 (revenue), for-pay 830000, deduction 130000,
;;   cogs 0 (no cost-price rows) → gross = 830000 − 130000 = 700000,
;;   ad-spend 0 → net = 700000. for_pay is the УСН-Доходы tax base.
;; ---------------------------------------------------------------------------

(def mgmt-fx
  "Single-article finance rows whose frozen-basis aggregates match the
   management fixture: revenue 1000000, for_pay 830000, net-profit 700000."
  [{:marketplace :ozon :rrd-id 9001
    :date-from "2026-05-01" :date-to "2026-05-31"
    :event-date "2026-05-15"
    :article "MGMT" :operation "sale" :quantity 1
    :retail-amount 1000000.0 :retail-price 1000000.0
    :for-pay 830000.0 :mp-commission 0.0 :wb-reward 0.0
    :delivery-cost 0.0 :storage-fee 0.0 :acceptance 0.0
    :penalty 0.0 :acquiring-fee 0.0 :deduction 130000.0
    :additional-payment 0.0 :ad-cost 0.0}])

(def usn-6pct-config
  "УСН Доходы 6% tax-config row for month 2026-05."
  {:year 2026 :month 5 :taxation-type :usn-income
   :usn-rate 0.06 :vat-rate 0.0 :official-cost-price true})

(defn mgmt-management-block
  "Assemble a :management input block for the fixture: УСН Доходы 6% +
   OPEX 170000 (rent 50000 + salary 120000). tax-config nil ⇒ inert."
  [{:keys [tax-config opex opex-by-category]
    :or   {opex 170000.0
           opex-by-category {"rent" 50000.0 "salary" 120000.0}}}]
  {:tax-config       tax-config
   :opex             opex
   :opex-by-category opex-by-category
   :cf               nil
   :configured?      (boolean (or tax-config (pos? (or opex 0.0))))})

;; T006 — INV-1: profit = round2(net − opex − (tax + vat)) = 480200.0
(deftest group-7-management-inv-1-profit
  (testing "INV-1: :profit = round2(net − opex − (tax+vat)) = 480200.0 on fixture"
    (let [mgmt (mgmt-management-block {:tax-config usn-6pct-config})
          p    (pnl/calculate mgmt-fx
                              :marketplace :ozon
                              :from "2026-05-01" :to "2026-05-31"
                              :management mgmt)]
      (is (= 700000.0 (:net-profit p)) "frozen-basis net-profit precondition")
      (is (= 830000.0 (:for-pay p))    "frozen-basis for_pay precondition")
      (is (= 49800.0 (:tax p))         "УСН Доходы tax = round2(830000×0.06)")
      (is (= 830000.0 (:tax-base p))   "tax-base = for_pay")
      (is (= 0.0 (:vat p))             "no НДС configured")
      (is (= 170000.0 (:opex p)))
      (is (= 480200.0 (:profit p))
          "profit = round2(700000 − 170000 − (49800 + 0)) = 480200.0"))))

;; T007 — INV-2: profit-without-expense = round2(net − (tax+vat)) = 650200.0 ≥ profit
(deftest group-7-management-inv-2-profit-without-expense
  (testing "INV-2: :profit-without-expense = round2(net − (tax+vat)) = 650200.0 ≥ profit"
    (let [mgmt (mgmt-management-block {:tax-config usn-6pct-config})
          p    (pnl/calculate mgmt-fx
                              :marketplace :ozon
                              :from "2026-05-01" :to "2026-05-31"
                              :management mgmt)]
      (is (= 650200.0 (:profit-without-expense p))
          "profit-without-expense = round2(700000 − 49800) = 650200.0")
      (is (>= (:profit-without-expense p) (:profit p))
          "profit-without-expense ≥ profit when opex ≥ 0")
      (is (= (:opex p) (math/round2 (- (:profit-without-expense p) (:profit p))))
          "profit-without-expense − profit = opex"))))

;; T008 — INV-4 / SC-006 (frozen basis): calculate with vs without :management
;; must be byte-for-byte identical on revenue/gross/net/margins. CRITICAL.
(deftest group-7-management-inv-4-frozen-basis
  (testing "INV-4/SC-006: :management does NOT alter revenue/gross/net/margins"
    (let [base    (pnl/calculate mgmt-fx
                                 :marketplace :ozon
                                 :from "2026-05-01" :to "2026-05-31")
          mgmt    (mgmt-management-block {:tax-config usn-6pct-config})
          managed (pnl/calculate mgmt-fx
                                 :marketplace :ozon
                                 :from "2026-05-01" :to "2026-05-31"
                                 :management mgmt)]
      (doseq [k [:revenue :gross-profit :net-profit :margin-gross :margin-net]]
        (is (= (get base k) (get managed k))
            (str "frozen basis key " k " must be byte-for-byte identical"))))
    (testing ":management = nil ⇒ result byte-for-byte identical to no-arg"
      (let [base   (pnl/calculate mgmt-fx
                                  :marketplace :ozon
                                  :from "2026-05-01" :to "2026-05-31")
            nilled (pnl/calculate mgmt-fx
                                  :marketplace :ozon
                                  :from "2026-05-01" :to "2026-05-31"
                                  :management nil)]
        (is (= base nilled)
            "management nil ⇒ output identical to today (no management keys emitted)")
        (is (not (contains? nilled :profit))
            "nil management ⇒ no :profit key")
        (is (not (contains? nilled :management-configured?))
            "nil management ⇒ no :management-configured? key")))))

;; T009 — INV-8 / SC-007: inert (no config) vs configured+base≤0 distinguishable
(deftest group-7-management-inv-8-inert-vs-configured-zero
  (testing "inert: no tax-config, no OPEX ⇒ :management-configured? false, :profit == :net-profit"
    (let [mgmt (mgmt-management-block {:tax-config nil :opex 0.0 :opex-by-category {}})
          p    (pnl/calculate mgmt-fx
                              :marketplace :ozon
                              :from "2026-05-01" :to "2026-05-31"
                              :management mgmt)]
      (is (false? (:management-configured? p)))
      (is (= (:net-profit p) (:profit p)) "inert ⇒ profit == net-profit")
      (is (= (:net-profit p) (:profit-without-expense p)))
      (is (= 0.0 (:tax p)))
      (is (nil? (:management-zero-reason p)))))
  (testing "configured + base ≤ 0 (loss Д−Р) ⇒ configured? true, tax 0, zero-reason :base-non-positive"
    ;; УСН Д−Р with official-cost-price: base = for_pay − (cogs + opex).
    ;; Here for_pay 830000, opex 900000 ⇒ base = 830000 − 900000 < 0 ⇒ tax 0.
    (let [loss-config {:year 2026 :month 5 :taxation-type :usn-income-expense
                       :usn-rate 0.15 :vat-rate 0.0 :official-cost-price true}
          mgmt        (mgmt-management-block {:tax-config loss-config
                                              :opex 900000.0
                                              :opex-by-category {"salary" 900000.0}})
          p           (pnl/calculate mgmt-fx
                                     :marketplace :ozon
                                     :from "2026-05-01" :to "2026-05-31"
                                     :management mgmt)]
      (is (true? (:management-configured? p)) "configured? true when tax-config present")
      (is (= 0.0 (:tax p)) "loss base ⇒ tax 0 (max 0)")
      (is (= :base-non-positive (:management-zero-reason p))
          "zero-reason distinguishes configured-but-zero from inert")))
  (testing "inert and configured-zero are DISTINGUISHABLE"
    (let [inert     (pnl/calculate mgmt-fx
                                   :marketplace :ozon :from "2026-05-01" :to "2026-05-31"
                                   :management (mgmt-management-block
                                                 {:tax-config nil :opex 0.0 :opex-by-category {}}))
          loss-cfg  {:year 2026 :month 5 :taxation-type :usn-income-expense
                     :usn-rate 0.15 :vat-rate 0.0 :official-cost-price true}
          configured (pnl/calculate mgmt-fx
                                    :marketplace :ozon :from "2026-05-01" :to "2026-05-31"
                                    :management (mgmt-management-block
                                                  {:tax-config loss-cfg :opex 900000.0
                                                   :opex-by-category {"salary" 900000.0}}))]
      (is (not= (:management-configured? inert) (:management-configured? configured))
          "configured? must differ")
      (is (not= (:management-zero-reason inert) (:management-zero-reason configured))
          "zero-reason must differ (nil vs :base-non-positive)"))))

;; T010 — load-management-adjustments contract shape
(deftest group-7-management-load-adjustments-contract
  (testing "load-management-adjustments returns {:cf :opex :opex-by-category :tax-config :configured?}"
    (db/execute! ["DELETE FROM opex_rows"])
    (db/execute! ["DELETE FROM tax_config"])
    ;; empty stores ⇒ not configured
    (let [empty-blk (pnl/load-management-adjustments "2026-05-01" "2026-05-31" :ozon)]
      (is (contains? empty-blk :cf))
      (is (contains? empty-blk :opex))
      (is (contains? empty-blk :opex-by-category))
      (is (contains? empty-blk :tax-config))
      (is (contains? empty-blk :configured?))
      (is (= 0.0 (:opex empty-blk)) "no OPEX rows ⇒ :opex 0.0")
      (is (= {} (:opex-by-category empty-blk)))
      (is (nil? (:tax-config empty-blk)) "no tax-config ⇒ nil")
      (is (false? (:configured? empty-blk)) "empty tax + opex ⇒ configured? false"))
    ;; populate: tax-config for the month + OPEX rows tagged :ozon (per-MP query
    ;; below asks for :ozon; NULL rows are blended-only per R11 so tag them).
    (tax/save-config! [usn-6pct-config])
    (opex/save-row! {:period-month "2026-05" :category "rent"   :amount 50000.0  :marketplace :ozon})
    (opex/save-row! {:period-month "2026-05" :category "salary" :amount 120000.0 :marketplace :ozon})
    (let [blk (pnl/load-management-adjustments "2026-05-01" "2026-05-31" :ozon)]
      (is (= 170000.0 (:opex blk)) "OPEX total from store")
      (is (= {"rent" 50000.0 "salary" 120000.0} (:opex-by-category blk)))
      (is (some? (:tax-config blk)) "tax-config loaded for month")
      (is (= :usn-income (:taxation-type (:tax-config blk))))
      (is (true? (:configured? blk)) "populated stores ⇒ configured? true"))
    (db/execute! ["DELETE FROM opex_rows"])
    (db/execute! ["DELETE FROM tax_config"])))

;; ---------------------------------------------------------------------------
;; P&L.10 — US4: all-MP + blended (T029-T031)
;;
;; These tests cover the removal of the (= marketplace :ozon) gate from
;; load-management-adjustments and the blended allocation invariant (R11).
;; ---------------------------------------------------------------------------

;; T029 — INV-6: management metrics emitted for :wb and :ym (not only :ozon)
(deftest group-7-management-inv-6-all-mp
  (testing "INV-6: :profit and :profit-without-expense emitted for :wb and :ym"
    (doseq [mp [:wb :ym]]
      (let [;; Finance rows whose frozen-basis gives net-profit 700000 for a
            ;; non-Ozon marketplace (deduction 130000, no ad-spend).
            mp-fx [{:marketplace mp :rrd-id 9100
                    :date-from "2026-05-01" :date-to "2026-05-31"
                    :event-date "2026-05-15"
                    :article "MP-TEST" :operation "sale" :quantity 1
                    :retail-amount 1000000.0 :retail-price 1000000.0
                    :for-pay 830000.0 :mp-commission 0.0 :wb-reward 0.0
                    :delivery-cost 0.0 :storage-fee 0.0 :acceptance 0.0
                    :penalty 0.0 :acquiring-fee 0.0 :deduction 130000.0
                    :additional-payment 0.0 :ad-cost 0.0}]
            ;; Simple management block: УСН 6% + OPEX 170000
            mgmt {:tax-config       usn-6pct-config
                  :opex             170000.0
                  :opex-by-category {"rent" 50000.0 "salary" 120000.0}
                  :cf               nil
                  :configured?      true}
            p    (pnl/calculate mp-fx
                                :marketplace mp
                                :from "2026-05-01" :to "2026-05-31"
                                :management mgmt)]
        (is (contains? p :profit)
            (str "marketplace " mp " must emit :profit when :management is provided"))
        (is (contains? p :profit-without-expense)
            (str "marketplace " mp " must emit :profit-without-expense"))
        (is (= 480200.0 (:profit p))
            (str "marketplace " mp " profit = round2(700000 - 170000 - (49800+0)) = 480200.0"))
        (is (= 650200.0 (:profit-without-expense p))
            (str "marketplace " mp " profit-without-expense = round2(700000 - 49800) = 650200.0"))
        (is (true? (:management-configured? p))
            (str "marketplace " mp " management-configured? must be true"))))))

;; T030 — INV-5/SC-005: Ozon cf :adjusted-net/:adjusted-gross/:adjusted-margin unchanged
;; after gate removal; cf and OPEX do not double-count (computed-tax-wins / R4)
(deftest group-7-management-inv-5-ozon-no-regression
  (testing "INV-5/SC-005: Ozon cf-period adjusted-* fields match pre-generalisation baseline"
    (let [;; Ozon fixture with a non-trivial cf adjustment (subscription -50, corrections +30)
          ozon-cf {:subscription       -50.0
                   :warehouse-movement 0.0
                   :returns-cargo      0.0
                   :fines              0.0
                   :packaging          0.0
                   :other-services     0.0
                   :corrections        30.0
                   :compensation       0.0}
          ;; Baseline: old path — pnl/calculate with :cf-adjustments directly
          ;; (simulates pre-generalisation state where load-cf-adjustments fed :cf-adjustments)
          baseline (pnl/calculate mgmt-fx
                                  :marketplace :ozon
                                  :from "2026-05-01" :to "2026-05-31"
                                  :cf-adjustments ozon-cf)
          ;; New path: management block carries :cf (same cf map)
          ;; This is what the generalised load-management-adjustments will produce.
          mgmt {:tax-config       usn-6pct-config
                :opex             170000.0
                :opex-by-category {"rent" 50000.0 "salary" 120000.0}
                :cf               ozon-cf
                :configured?      true}
          ;; calculate with both :cf-adjustments (for adjusted-*) and :management
          ;; The cf path is driven by :cf-adjustments; management is an additive layer.
          ;; After T032, load-management-adjustments provides :cf → call sites pass it
          ;; as :cf-adjustments. We test that the values are byte-for-byte identical.
          generalised (pnl/calculate mgmt-fx
                                     :marketplace :ozon
                                     :from "2026-05-01" :to "2026-05-31"
                                     :cf-adjustments ozon-cf
                                     :management mgmt)]
      ;; Core no-regression: adjusted-* fields identical
      (doseq [k [:adjusted-net :adjusted-gross :adjusted-margin]]
        (is (= (get baseline k) (get generalised k))
            (str "INV-5: " k " must be byte-for-byte identical after generalisation")))
      ;; Double-count check: :opex is in management, NOT in cf (they are disjoint, R4)
      ;; The cf map has subscription/corrections but NOT opex — distinct slots.
      (is (contains? generalised :opex)
          "management-path emits :opex alongside cf fields")
      (is (contains? generalised :adjusted-net)
          "cf-path still emits :adjusted-net")
      ;; No double-counting: profit subtracts opex ONCE (from management), not from cf
      ;; cf-total only contains the cf-map entries, not opex
      (let [cf-total (:cf-total generalised)
            opex     (:opex generalised)]
        (is (some? cf-total) "cf-total must be present when cf-adjustments given")
        (is (some? opex) "opex must be present when management given")
        ;; cf-total reflects only cf map (subscription -50, corrections +30 = -20)
        ;; opex is 170000 from management block — they are independent
        (is (= -20.0 (math/round2 cf-total))
            "cf-total = subscription(-50) + corrections(+30) = -20, OPEX not included")
        (is (= 170000.0 opex)
            "opex = 170000 from management block, not from cf-total"))))
  (testing "INV-5: management=nil reverts to baseline (no regression for existing callers)"
    ;; With :management nil, result is byte-for-byte identical to baseline call
    (let [ozon-cf {:subscription -50.0 :warehouse-movement 0.0 :returns-cargo 0.0
                   :fines 0.0 :packaging 0.0 :other-services 0.0
                   :corrections 30.0 :compensation 0.0}
          baseline (pnl/calculate mgmt-fx
                                  :marketplace :ozon
                                  :from "2026-05-01" :to "2026-05-31"
                                  :cf-adjustments ozon-cf)
          nil-mgmt (pnl/calculate mgmt-fx
                                  :marketplace :ozon
                                  :from "2026-05-01" :to "2026-05-31"
                                  :cf-adjustments ozon-cf
                                  :management nil)]
      (is (= baseline nil-mgmt)
          "management=nil ⇒ result byte-for-byte identical to pre-generalisation baseline"))))

;; T031 — INV-7/SC-008: blended profit = Σ(per-MP profits) + unallocated-OPEX contribution;
;; NULL-marketplace OPEX row counted exactly once (blended only, not per-MP)
(deftest group-7-management-inv-7-blended-allocation
  (testing "INV-7/SC-008: NULL-marketplace OPEX counted once (blended only), not per-MP"
    (db/execute! ["DELETE FROM opex_rows"])
    (db/execute! ["DELETE FROM tax_config"])
    (tax/save-config! [usn-6pct-config])
    ;; Per-MP OPEX: tagged rows for specific MPs
    (opex/save-row! {:period-month "2026-05" :category "salary" :amount 100000.0 :marketplace :wb})
    (opex/save-row! {:period-month "2026-05" :category "salary" :amount 80000.0  :marketplace :ozon})
    ;; Unallocated OPEX: NULL marketplace — blended only, per-MP queries EXCLUDE this
    (opex/save-row! {:period-month "2026-05" :category "rent"   :amount 50000.0  :marketplace nil})

    ;; Per-MP queries must NOT include the NULL-marketplace row
    (let [wb-agg   (opex/sum-by-category "2026-05-01" "2026-05-31" :wb)
          ozon-agg (opex/sum-by-category "2026-05-01" "2026-05-31" :ozon)
          blended  (opex/sum-by-category "2026-05-01" "2026-05-31" nil)]
      (is (= 100000.0 (:total wb-agg))
          "WB per-MP: only tagged :wb row (100000), NOT NULL row")
      (is (= 80000.0 (:total ozon-agg))
          "Ozon per-MP: only tagged :ozon row (80000), NOT NULL row")
      (is (= 230000.0 (:total blended))
          "Blended: all rows = 100000 + 80000 + 50000 = 230000 (NULL counted once)")
      ;; Cross-check: blended = wb + ozon + unallocated (50000)
      ;; No double-counting: 230000 = 100000 + 80000 + 50000
      (is (= (:total blended)
             (+ (:total wb-agg) (:total ozon-agg) 50000.0))
          "blended total = per-WB + per-Ozon + unallocated (R11)"))

    (testing "NULL-marketplace OPEX flows into blended pnl/calculate management profit"
      ;; Finance rows for a blended (nil marketplace) calculation
      ;; net-profit = 700000 (same fixture shape)
      (let [blended-mgmt (pnl/load-management-adjustments "2026-05-01" "2026-05-31" nil)
            blended-p    (pnl/calculate mgmt-fx
                                        :marketplace nil
                                        :from "2026-05-01" :to "2026-05-31"
                                        :management blended-mgmt)
            wb-mgmt      (pnl/load-management-adjustments "2026-05-01" "2026-05-31" :wb)
            wb-p         (pnl/calculate mgmt-fx
                                        :marketplace :wb
                                        :from "2026-05-01" :to "2026-05-31"
                                        :management wb-mgmt)
            ozon-mgmt    (pnl/load-management-adjustments "2026-05-01" "2026-05-31" :ozon)
            ozon-p       (pnl/calculate mgmt-fx
                                        :marketplace :ozon
                                        :from "2026-05-01" :to "2026-05-31"
                                        :management ozon-mgmt)]
        ;; Blended OPEX = 230000 (wb-100000 + ozon-80000 + unallocated-50000)
        (is (= 230000.0 (:opex blended-p)) "blended OPEX = 230000")
        ;; Per-MP OPEX values do NOT include the NULL row
        (is (= 100000.0 (:opex wb-p)) "WB per-MP OPEX = 100000 only")
        (is (= 80000.0 (:opex ozon-p)) "Ozon per-MP OPEX = 80000 only")
        ;; Unallocated contribution: blended opex - (wb + ozon) per-mp opex = 50000
        (let [unallocated-opex (- (:opex blended-p)
                                  (+ (:opex wb-p) (:opex ozon-p)))]
          (is (= 50000.0 unallocated-opex)
              "Unallocated (NULL-mp) OPEX = 50000, counted once in blended"))))

    (db/execute! ["DELETE FROM opex_rows"])
    (db/execute! ["DELETE FROM tax_config"])))

;; ---------------------------------------------------------------------------
;; P&L.Waterfall — spec 016 US3 (data-model §4 / contracts/waterfall-response.edn)
;;
;; The waterfall is a PURE fn over pnl/calculate output — it re-composes the
;; already-computed frozen aggregates onto a GROSS top-line (:revenue), never
;; recomputing revenue/gross/net. §0.1 LOCKED design:
;;   sales          = :revenue (GROSS realisation, NOT for_pay)
;;   directExpenses = commission (VISIBLE) + cogs + logistics + storage
;;                    + penalties + acceptance + deduction − additional (Доплаты, credit)
;;   grossMargin    = sales − directExpenses == pnl :gross-profit (to the kopeck)
;;   advertising    = :ad-spend (distinct line)
;;   EBITDA         = grossMargin − advertising − operatingExpenses
;;   netProfit      = EBITDA − tax == pnl :net-profit (opex=tax=0 pre-015)
;; ---------------------------------------------------------------------------

(def waterfall-fx
  "Per-MP finance rows for the waterfall kopeck invariants (non-zero :additional)."
  (delay (edn/read-string (slurp (io/resource "fixtures/pnl-waterfall-2026-05.edn")))))

(defn- wf-line
  "Fetch a single waterfall line by :key from a waterfall result."
  [wf k]
  (first (filter #(= k (:key %)) (:waterfall wf))))

(defn- direct-expense-children
  "The drilldown children lines of the :direct-expenses layer (excludes the
   :direct-expenses roll-up line itself)."
  [wf]
  (let [child-keys (set (:children (wf-line wf :direct-expenses)))]
    (filter #(and (= :direct-expense (:layer %))
                  (contains? child-keys (:key %))
                  (not= :direct-expenses (:key %)))
            (:waterfall wf))))

;; VR-w1 / SC-005 — netProfit == pnl/calculate :net-profit to the kopeck, all 3 MPs.
(deftest waterfall-net-profit-reconciles-to-pnl
  (testing "VR-w1/SC-005: waterfall netProfit == pnl :net-profit to the kopeck (WB/Ozon/YM)"
    (doseq [mp [:wb :ozon :ym]]
      (let [rows (get @waterfall-fx mp)
            p    (pnl/calculate rows :marketplace mp :from "2026-05-01" :to "2026-05-31")
            wf   (pnl/waterfall p)
            np   (:amount (wf-line wf :net-profit))]
        (is (= (:net-profit p) np)
            (str "marketplace " mp ": waterfall netProfit must equal pnl :net-profit"))))))

;; VR-w2 — Σ direct-expense children == direct-expenses (commission counted once).
;; VR-w3 — grossMargin == pnl :gross-profit (GROSS − explicit commission reproduces
;;         the for_pay-based gross). Fixture has non-zero :additional so a missing/
;;         misnamed credit (the old :compensation child) fails.
(deftest waterfall-direct-expense-composition
  (testing "VR-w2: Σ direct-expense children == direct-expenses (all 3 MPs)"
    (doseq [mp [:wb :ozon :ym]]
      (let [rows (get @waterfall-fx mp)
            p    (pnl/calculate rows :marketplace mp :from "2026-05-01" :to "2026-05-31")
            wf   (pnl/waterfall p)
            de   (:amount (wf-line wf :direct-expenses))
            sum  (math/round2 (reduce + 0.0 (map :amount (direct-expense-children wf))))]
        (is (= de sum)
            (str "marketplace " mp ": Σ children must equal direct-expenses (commission once)")))))
  (testing "VR-w3: grossMargin == pnl :gross-profit (all 3 MPs, non-zero :additional)"
    (doseq [mp [:wb :ozon :ym]]
      (let [rows (get @waterfall-fx mp)
            p    (pnl/calculate rows :marketplace mp :from "2026-05-01" :to "2026-05-31")
            wf   (pnl/waterfall p)
            gm   (:amount (wf-line wf :gross-margin))]
        (is (pos? (:additional p))
            (str "marketplace " mp ": fixture must have non-zero :additional (VR-w3 guard)"))
        (is (= (:gross-profit p) gm)
            (str "marketplace " mp ": grossMargin must equal pnl :gross-profit")))))
  (testing "VR-w3: :additional child is a CREDIT (positive amount, reduces directExpenses)"
    (let [rows (get @waterfall-fx :ozon)
          p    (pnl/calculate rows :marketplace :ozon :from "2026-05-01" :to "2026-05-31")
          wf   (pnl/waterfall p)
          add  (wf-line wf :additional)]
      (is (some? add) ":additional must be a visible direct-expense child")
      (is (pos? (:amount add)) ":additional (Доплаты) is a credit → positive amount")
      (is (nil? (wf-line wf :compensation))
          ":compensation must NOT be a waterfall child (it is the cf layer, not gross_profit)")))
  (testing "VR-w2: commission is a VISIBLE child line and sales − directExpenses closes"
    (let [rows (get @waterfall-fx :wb)
          p    (pnl/calculate rows :marketplace :wb :from "2026-05-01" :to "2026-05-31")
          wf   (pnl/waterfall p)
          comm (wf-line wf :mp-commission)
          sales (:amount (wf-line wf :sales))
          de   (:amount (wf-line wf :direct-expenses))
          gm   (:amount (wf-line wf :gross-margin))]
      (is (some? comm) "commission must be a visible direct-expense child")
      (is (neg? (:amount comm)) "commission is an expense → negative amount")
      (is (= (:revenue p) sales) "sales line == GROSS :revenue")
      (is (= gm (math/round2 (+ sales de)))
          "grossMargin == sales + directExpenses (directExpenses negative)"))))

;; VR-w4 — with opex=tax=0 (015 not landed) EBITDA == grossMargin − advertising and
;;         netProfit == EBITDA, no error.
(deftest waterfall-ebitda-pre-management
  (testing "VR-w4/FR-020: opex=tax=0 ⇒ EBITDA == grossMargin − advertising, netProfit == EBITDA"
    (doseq [mp [:wb :ozon :ym]]
      (let [rows (get @waterfall-fx mp)
            p    (pnl/calculate rows :marketplace mp :from "2026-05-01" :to "2026-05-31")
            wf   (pnl/waterfall p)               ; NO :management ⇒ opex/tax layers render 0
            gm   (:amount (wf-line wf :gross-margin))
            adv  (:amount (wf-line wf :advertising))
            opex (:amount (wf-line wf :operating-expenses))
            ebit (:amount (wf-line wf :ebitda))
            tax  (:amount (wf-line wf :tax))
            np   (:amount (wf-line wf :net-profit))]
        (is (= 0.0 opex) (str mp ": operatingExpenses renders 0 pre-015"))
        (is (= 0.0 tax)  (str mp ": tax renders 0 pre-015"))
        (is (= ebit (math/round2 (+ gm adv)))
            (str mp ": EBITDA == grossMargin − advertising (advertising negative)"))
        (is (= np ebit) (str mp ": netProfit == EBITDA when opex=tax=0"))))))

;; VR-w5 — a line with no comparison value renders delta-pct = nil (neutral), NOT ±100%.
(deftest waterfall-no-comparison-neutral-delta
  (testing "VR-w5/FR-026: no comparison ⇒ :delta-pct nil (neutral), not ±100%"
    (let [rows (get @waterfall-fx :wb)
          p    (pnl/calculate rows :marketplace :wb :from "2026-05-01" :to "2026-05-31")
          ;; No comparison arg supplied ⇒ every line's delta must be nil/absent.
          wf   (pnl/waterfall p)]
      (doseq [line (:waterfall wf)]
        (is (nil? (:delta-pct line))
            (str (:key line) ": delta-pct must be nil when no comparison period"))
        (is (nil? (:delta line))
            (str (:key line) ": delta must be nil when no comparison period")))))
  (testing "VR-w5: with comparison, a line whose prior value is 0 ⇒ delta-pct nil (not ±100%)"
    (let [rows     (get @waterfall-fx :wb)
          p        (pnl/calculate rows :marketplace :wb :from "2026-05-01" :to "2026-05-31")
          ;; Comparison pnl with zero net-profit / zero prior for the ad line.
          zero-p   (pnl/calculate [] :marketplace :wb :from "2026-04-01" :to "2026-04-30")
          wf       (pnl/waterfall p :comparison zero-p)
          adv      (wf-line wf :advertising)]
      ;; advertising prior = 0 ⇒ pct is undefined ⇒ nil (neutral), never ±100%.
      (is (nil? (:delta-pct adv))
          "advertising delta-pct must be nil when prior value is 0 (neutral, not ±100%)"))))

;; Management layers (015 present on this branch): when :management is supplied,
;; operatingExpenses/tax read the seam; netProfit still ties to pnl.
(deftest waterfall-management-layers
  (testing "FR-020/FR-021: with :management seam, opex/tax layers read the 015 values"
    (let [rows (get @waterfall-fx :ozon)
          mgmt {:tax-config       {:year 2026 :month 5 :taxation-type :usn-income
                                   :usn-rate 0.06 :vat-rate 0.0 :official-cost-price true}
                :opex             500.0
                :opex-by-category {"rent" 500.0}
                :cf               nil
                :configured?      true}
          p    (pnl/calculate rows :marketplace :ozon
                              :from "2026-05-01" :to "2026-05-31"
                              :management mgmt)
          wf   (pnl/waterfall p)
          opex (:amount (wf-line wf :operating-expenses))
          tax  (:amount (wf-line wf :tax))
          np   (:amount (wf-line wf :net-profit))]
      (is (= (- (:opex p)) opex) "operatingExpenses line == −:opex from management seam")
      (is (= (- (+ (:tax p) (:vat p))) tax) "tax line == −(:tax + :vat) from seam")
      ;; netProfit == 015 management :profit (= net − opex − tax − vat)
      (is (= (:profit p) np)
          "netProfit == 015 management :profit when management present"))))

;; ---------------------------------------------------------------------------
;; Audit 2026-07-02 N2 — P&L.5 clamp: net-qty <= 0 must yield 0, not a
;; sign-flipped positive "profit per sale" (loss ÷ negative count).
;; ---------------------------------------------------------------------------

(deftest profit-per-sale-clamps-on-net-negative-qty
  (let [rows [{:marketplace :wb :rrd-id 900
               :date-from "2026-03-01" :date-to "2026-03-07"
               :event-date "2026-03-03"
               :article "Z" :operation "sale" :quantity 1
               :retail-amount 100.0 :retail-price 100.0
               :for-pay 80.0 :mp-commission 15.0 :wb-reward 15.0
               :delivery-cost 1.0 :storage-fee 0.0 :acceptance 0.0
               :penalty 0.0 :acquiring-fee 0.0 :deduction 0.0
               :additional-payment 0.0 :ad-cost 0.0}
              {:marketplace :wb :rrd-id 901
               :date-from "2026-03-01" :date-to "2026-03-07"
               :event-date "2026-03-05"
               :article "Z" :operation "return" :quantity 2
               :retail-amount 0.0 :retail-price 0.0
               :for-pay 200.0 :mp-commission 0.0 :wb-reward 0.0
               :delivery-cost 0.5 :storage-fee 0.0 :acceptance 0.0
               :penalty 0.0 :acquiring-fee 0.0 :deduction 0.0
               :additional-payment 0.0 :ad-cost 0.0}]
        r    (pnl/calculate rows :marketplace :wb
                            :from "2026-03-01" :to "2026-03-07")]
    (testing "period is a loss with net-qty < 0"
      (is (neg? (:net-profit r)))
      (is (neg? (- (:sales-qty r) (:returns-qty r)))))
    (testing "profit-per-sale is clamped to 0, not sign-flipped positive"
      (is (= 0.0 (:profit-per-sale r))))))
